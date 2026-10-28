plugins {
    java
}

// Mindustry 的 mods 目录 = <dataDir>/mods。dataDir 优先级与游戏本体一致（ClientLauncher 先读
// -Dmindustry.data.dir，再读 MINDUSTRY_DATA_DIR），默认值对齐 arc 的 OS.getAppDataDirectoryString：
// Win %APPDATA%/Mindustry、macOS ~/Library/Application Support/Mindustry、Linux $XDG_DATA_HOME/Mindustry 或 ~/.local/share/Mindustry。
if (findProperty("remold.deployDir") == null && findProperty("remold.modsDir") == null) {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    val base = when {
        os.contains("win") -> System.getenv("APPDATA") ?: "$home/AppData/Roaming"
        os.contains("mac") -> "$home/Library/Application Support"
        else -> System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
    }
    val dataDir = System.getProperty("mindustry.data.dir") ?: System.getenv("MINDUSTRY_DATA_DIR") ?: "$base/Mindustry"
    extra["remold.deployDir"] = File(dataDir, "mods").absolutePath
}

// 本地源码树里开发 Remold 时用上一层的脚本（改了立即生效），否则按 tag 拉取对应版本
apply(from = file("../remold.gradle.kts").takeIf { it.isFile }
    ?: "https://raw.githubusercontent.com/I-Hope1/Remold/v${findProperty("remold.version") ?: "0.1.3"}/remold.gradle.kts")

sourceSets.main {
    java.setSrcDirs(listOf("src"))
}

// 目标 Mindustry 版本 (如 v160, v160.5, latest, be)
val mindustryVersion = "v160.5"
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val sdkRoot: String? = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
val projectName = project.name

repositories {
    mavenCentral()
    maven("https://jitpack.io")

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

        content {
            if (mindustryVersion == "be") {
                includeVersion("Anuken", "MindustryBuilds", "latest")
            } else {
                includeVersion("Anuken", "Mindustry", mindustryVersion)
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // 仅在编译期引用 Mindustry，绝不打包进模组，绝不污染系统类加载器
    compileOnly(if (mindustryVersion == "be") "Anuken:MindustryBuilds:latest" else "Anuken:Mindustry:$mindustryVersion")

    // Remold 注解与 API 依赖：提供 @RemoldReinit, @OnReload, @HotRebuild 等热重载控制注解
    val remoldVersion = (project.findProperty("remold.version") as? String) ?: "0.1.3"
    compileOnly("com.github.I-hope1.Remold:api:v$remoldVersion") { attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) } }
}

// 桌面端模组打包任务
tasks.jar {
    archiveFileName.set("${projectName}Desktop.jar")

    from(configurations.runtimeClasspath.map { files ->
        files.map { if (it.isDirectory) it else zipTree(it) }
    })

    from(projectDir) {
        include("mod.hjson")
        include("icon.png")
    }

    from("assets/") {
        include("**")
    }
}

// ==================== Remold 热重载自动化配置 ====================
// 推荐直接在 gradle.properties 中配置 remold.jar 与 remold.autoDeploy=true
// 亦可在本脚本中通过 extra 属性覆盖配置，例如：
// extra["remold.jar"] = "D:/Mindustry/Mindustry.jar"
// extra["remold.autoDeploy"] = true

// ==================== 可选：Android d8/dex 打包任务 (与官方模版对齐) ====================
val jarAndroid = tasks.register("jarAndroid") {
    dependsOn(tasks.jar)

    doLast {
        val sdk = sdkRoot?.let { File(it) } ?: return@doLast
        val platformRoot = File(sdk, "platforms").listFiles()
            ?.sortedDescending()
            ?.firstOrNull { File(it, "android.jar").exists() }
            ?: return@doLast

        val classpath = (configurations.compileClasspath.get().files +
                configurations.runtimeClasspath.get().files +
                File(platformRoot, "android.jar"))

        val d8 = if (isWindows) "d8.bat" else "d8"
        val command = buildList {
            add(d8)
            classpath.forEach { addAll(listOf("--classpath", it.path)) }
            addAll(listOf("--min-api", "21"))
            addAll(listOf("--output", "${projectName}Android.jar"))
            add("${projectName}Desktop.jar")
        }

        ProcessBuilder(command)
            .directory(layout.buildDirectory.dir("libs").get().asFile)
            .inheritIO()
            .start()
            .waitFor()
    }
}

tasks.register<Jar>("deploy") {
    dependsOn(jarAndroid, tasks.jar)
    archiveFileName.set("$projectName.jar")

    val libs = layout.buildDirectory.dir("libs")
    from(provider {
        listOfNotNull(
            zipTree(libs.get().file("${projectName}Desktop.jar")),
            if (libs.get().file("${projectName}Android.jar").asFile.exists())
                zipTree(libs.get().file("${projectName}Android.jar"))
            else null
        )
    })
}

