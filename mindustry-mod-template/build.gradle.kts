plugins {
    java
}

// 引入 Remold 热重载自动化脚本插件 (本地优先，独立运行自动回退 GitHub 官方源)
val localRemold = rootProject.file("../remold.gradle.kts")
if (localRemold.exists()) {
    apply(from = localRemold)
} else {
    apply(from = "https://raw.githubusercontent.com/I-hope1/Remold/main/remold.gradle.kts")
}

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
