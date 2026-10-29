plugins {
    java
}

// ==================== 统一常量 ====================
// 版本号与环境判断只在这里定义一次：原先 Remold 版本写了两遍、OS 判断写了两遍（一处 "win"、一处 "windows"），
// 改一处漏一处就是运行期才炸的坑。

val osName = System.getProperty("os.name").lowercase()
val isWindows = osName.contains("win")

// Remold 版本：脚本 / agent / api 三者必须同版本（apply 拉脚本 与 api 依赖共用这一份）
val remoldVersion = (findProperty("remold.version") as? String) ?: "0.1.3"

// 目标 Mindustry 版本 (如 v160, v160.5, latest, be)。
// 默认值可被 -PmindustryVersion=... / gradle.properties 里的 mindustryVersion= 覆盖。
val mindustryVersion = (findProperty("mindustryVersion") as? String)
    ?: (findProperty("mindustry.version") as? String)
    ?: "v160.5"

// "be" 取 MindustryBuilds 的 master 构建，其余取正式版 dependencies.jar
val mindustryCoordinate =
    if (mindustryVersion == "be") "Anuken:MindustryBuilds:latest" else "Anuken:Mindustry:$mindustryVersion"

// Mindustry 的 mods 目录 = <dataDir>/mods。dataDir 优先级与游戏本体一致（ClientLauncher 先读
// -Dmindustry.data.dir，再读 MINDUSTRY_DATA_DIR），默认值对齐 arc 的 OS.getAppDataDirectoryString：
// Win %APPDATA%/Mindustry、macOS ~/Library/Application Support/Mindustry、Linux $XDG_DATA_HOME/Mindustry 或 ~/.local/share/Mindustry。
if (findProperty("remold.deployDir") == null && findProperty("remold.modsDir") == null) {
    val home = System.getProperty("user.home")
    val base = when {
        isWindows -> System.getenv("APPDATA") ?: "$home/AppData/Roaming"
        osName.contains("mac") -> "$home/Library/Application Support"
        else -> System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
    }
    val dataDir = System.getProperty("mindustry.data.dir") ?: System.getenv("MINDUSTRY_DATA_DIR") ?: "$base/Mindustry"
    extra["remold.deployDir"] = File(dataDir, "mods").absolutePath
}

// 本地源码树里开发 Remold 时用上一层的脚本（改了立即生效），否则按 tag 拉取对应版本
apply(from = file("../remold.gradle.kts").takeIf { it.isFile }
    ?: "https://raw.githubusercontent.com/I-Hope1/Remold/v$remoldVersion/remold.gradle.kts")

sourceSets.main {
    java.setSrcDirs(listOf("src"))
}

repositories {
    // Anuken 组独占绑定到下面的 ivy 仓库（exclusiveContent）：Mindustry 的 dependencies.jar 只存在于
    // GitHub Releases，若 mavenCentral / jitpack 排在前面，每次解析都会先去它们那里碰一遍 404 —— 既慢，
    // 又给依赖混淆留了口子（同名坐标被别处接住）。独占后这两者根本不会参与 Anuken 的解析。
    exclusiveContent {
        forRepository {
            // 官方 ivy 规则：从 Mindustry GitHub Releases 下载 dependencies.jar（官方最稳定源）
            ivy {
                url = uri("https://github.com/")
                patternLayout {
                    val pattern = when (mindustryVersion) {
                        "latest" -> "/[organisation]/[module]/releases/[revision]/download/dependencies.jar"
                        "be" -> "/[organisation]/[module]/releases/download/master/[revision].jar"
                        else -> "/[organisation]/[module]/releases/download/[revision]/dependencies.jar"
                    }
                    val path = pattern.substring(0, pattern.lastIndexOf('/'))

                    artifact("$path/[classifier].jar")
                    artifact(pattern.replaceFirst(Regex("""\.jar$"""), "(-[classifier]).jar"))
                }
                metadataSources { artifact() }
            }
        }
        filter {
            includeGroup("Anuken")
        }
    }

    mavenCentral()
    maven("https://jitpack.io")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// 源码与脚本里都有中文。Windows 上 javac 默认按平台编码（GBK）解析源文件，不显式指定会乱码甚至编译失败。
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    // 仅在编译期引用 Mindustry，绝不打包进模组，绝不污染系统类加载器
    compileOnly(mindustryCoordinate)

    // Remold 注解与 API 依赖：提供 @RemoldReinit, @OnReload, @HotRebuild 等热重载控制注解
    compileOnly("com.github.I-hope1.Remold:api:v$remoldVersion") {
        attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
    }
}

// 桌面端模组打包任务
tasks.named<Jar>("jar") {
    archiveFileName.set("${project.name}Desktop.jar")

    from(configurations.runtimeClasspath.map { files ->
        files.map { if (it.isDirectory) it else zipTree(it) }
    })

    // 直接指定文件，不再 from(projectDir) { include(...) } —— 后者会把整个项目目录（含 build/、.gradle/）
    // 当成 source root 去遍历一遍，只为挑两个文件。
    from("mod.hjson")
    from("icon.png")

    from("assets") {
        include("**")
    }
}

// ==================== Remold 热重载自动化配置 ====================
// 推荐直接在 gradle.properties 中配置 remold.jar 与 remold.autoDeploy=true
// 亦可在本脚本中通过 extra 属性覆盖配置，例如：
// extra["remold.jar"] = "D:/Mindustry/Mindustry.jar"
// extra["remold.autoDeploy"] = true

// ==================== 可选：Android d8/dex 打包任务 (与官方模版对齐) ====================
// 把 Desktop.jar 转成 classes.dex 打进 Android.jar，供移动端加载。

val androidSdkRoot: String? = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
val androidSdkDir: File? = androidSdkRoot
    ?.takeIf { it.isNotBlank() }
    ?.let { File(it) }
    ?.takeIf { it.isDirectory }

val androidMinApi = "21"
val d8ExecutableName = if (isWindows) "d8.bat" else "d8"

/**
 * 把目录名里的数字段抽出来做比较："android-9" -> [9]、"android-34" -> [34]、"34.0.0" -> [34, 0, 0]。
 * 直接按文件名字符串倒序会把 android-9 排在 android-34 前面（"9" > "3"）。
 */
fun numericVersionKey(name: String): List<Int> =
    Regex("""\d+""").findAll(name).map { it.value.toInt() }.toList()

val dirVersionOrder: Comparator<File> = Comparator { a, b ->
    val ka = numericVersionKey(a.name)
    val kb = numericVersionKey(b.name)
    var i = 0
    while (i < maxOf(ka.size, kb.size)) {
        val x = ka.getOrElse(i) { 0 }
        val y = kb.getOrElse(i) { 0 }
        if (x != y) return@Comparator x.compareTo(y)
        i++
    }
    0
}

/** 数字版本最大的 platforms/android-XX/android.jar。 */
val androidPlatformJar: File? = androidSdkDir?.resolve("platforms")?.listFiles()
    ?.filter { it.isDirectory && File(it, "android.jar").isFile }
    ?.maxWithOrNull(dirVersionOrder)
    ?.resolve("android.jar")

/** 数字版本最大的 build-tools/<ver>/d8[.bat]；不再从 PATH 里随便捡一个 d8。 */
val androidD8Executable: File? = androidSdkDir?.resolve("build-tools")?.listFiles()
    ?.filter { it.isDirectory && File(it, d8ExecutableName).isFile }
    ?.maxWithOrNull(dirVersionOrder)
    ?.resolve(d8ExecutableName)

/** SDK / platform / build-tools 三者齐全才算能打 Android 包。 */
val androidReady = androidSdkDir != null && androidPlatformJar != null && androidD8Executable != null

val jarAndroid = tasks.register("jarAndroid") {
    group = "build"
    description = "把 Desktop.jar 转成 classes.dex 并打包为 Android.jar（需要 ANDROID_HOME / ANDROID_SDK_ROOT）"
    dependsOn(tasks.named("jar"))

    // 配置期取出并落到局部变量：doLast 里不再触碰 configurations（configuration cache 可复用，不再每次失效）。
    val sdkDir = androidSdkDir
    val platformJar = androidPlatformJar
    val d8Path = androidD8Executable
    val d8Name = d8ExecutableName
    val minApi = androidMinApi
    val modName = project.name
    val compileClasspathFiles = configurations.compileClasspath
    val runtimeClasspathFiles = configurations.runtimeClasspath
    val libs = layout.buildDirectory.dir("libs")
    val desktopJar = libs.map { it.file("${modName}Desktop.jar") }
    val androidJar = libs.map { it.file("${modName}Android.jar") }

    // 声明 inputs/outputs：classpath 与 Desktop.jar 没变就判定 UP-TO-DATE，不再每次重跑 d8。
    inputs.files(compileClasspathFiles, runtimeClasspathFiles, desktopJar).withPropertyName("d8Classpath")
    inputs.property("androidMinApi", minApi)
    platformJar?.let { inputs.file(it).withPropertyName("androidJar") }
    d8Path?.let { inputs.file(it).withPropertyName("d8Executable") }
    outputs.file(androidJar)

    doLast {
        // 找不到 SDK 不再静默 return（那样构建显示成功、产物却少了 Android 包）。
        val sdk = sdkDir ?: throw GradleException(
            "[jarAndroid] 未找到 Android SDK：请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT 环境变量。\n" +
                "  只需要桌面版时执行 `./gradlew jar`（`deploy` 在检测不到 SDK 时会自动只打桌面包）。"
        )
        val androidJarFile = platformJar ?: throw GradleException(
            "[jarAndroid] 在 ${File(sdk, "platforms").absolutePath} 下没有找到任何 android.jar。\n" +
                "  请在 SDK Manager 中至少安装一个 Android Platform。"
        )
        val d8 = d8Path ?: throw GradleException(
            "[jarAndroid] 在 ${File(sdk, "build-tools").absolutePath} 下没有找到 $d8Name。\n" +
                "  请在 SDK Manager 中安装 Android SDK Build-Tools。"
        )

        val outDir = libs.get().asFile
        val desktopJarFile = desktopJar.get().asFile
        val androidJarOut = androidJar.get().asFile
        if (!desktopJarFile.isFile) {
            throw GradleException("[jarAndroid] 未找到 ${desktopJarFile.absolutePath}，请先执行 :jar。")
        }
        outDir.mkdirs()
        // d8 对已存在的输出文件行为不稳定，先清掉上次产物
        androidJarOut.delete()

        val command = mutableListOf<String>()
        command.add(d8.absolutePath)
        (compileClasspathFiles.get().files + runtimeClasspathFiles.get().files + androidJarFile).forEach {
            command.add("--classpath")
            command.add(it.absolutePath)
        }
        command.add("--min-api")
        command.add(minApi)
        command.add("--output")
        command.add(androidJarOut.absolutePath)
        command.add(desktopJarFile.absolutePath)

        logger.lifecycle("[jarAndroid] d8: ${d8.absolutePath}")
        logger.lifecycle("[jarAndroid] android.jar: ${androidJarFile.absolutePath}")

        val exitCode = ProcessBuilder(command)
            .directory(outDir)
            .inheritIO()
            .start()
            .waitFor()

        // 退出码必须检查：d8 失败时 Gradle 仍然会把任务判为成功，产物却是空的/旧的。
        if (exitCode != 0) {
            throw GradleException(
                "[jarAndroid] d8 执行失败（退出码 $exitCode）：\n  " + command.joinToString(" ") +
                    "\n  详见上方 d8 输出。"
            )
        }
        if (!androidJarOut.isFile) {
            throw GradleException("[jarAndroid] d8 返回成功但未生成 ${androidJarOut.absolutePath}。")
        }
        logger.lifecycle("[jarAndroid] 已生成: ${androidJarOut.absolutePath}")
    }
}

tasks.register<Jar>("deploy") {
    group = "build"
    description = "合并 Desktop.jar 与 Android.jar 为可分发的 ${project.name}.jar"

    if (androidReady) {
        dependsOn(jarAndroid)
    }
    dependsOn(tasks.named("jar"))

    archiveFileName.set("${project.name}.jar")

    // 两个 jar 都带 mod.hjson / assets / META-INF：默认 INCLUDE 会把同名条目重复写进归档。
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val modName = project.name
    val androidAvailable = androidReady
    val libs = layout.buildDirectory.dir("libs")
    from(provider {
        buildList {
            add(zipTree(libs.get().file("${modName}Desktop.jar")))
            val androidJar = libs.get().file("${modName}Android.jar")
            if (androidAvailable && androidJar.asFile.exists()) {
                add(zipTree(androidJar))
            }
        }
    })

    doFirst {
        if (!androidAvailable) {
            logger.warn(
                "[deploy] 未检测到可用的 Android 工具链（ANDROID_HOME / ANDROID_SDK_ROOT + platforms + build-tools），" +
                    "本次只打包桌面版；如需 Android dex 包，安装 SDK 后重新执行。"
            )
        }
    }
}
