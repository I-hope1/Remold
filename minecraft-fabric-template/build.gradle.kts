plugins {
    java
    // 版本约束：本模板要求 Gradle JVM = 21（带 DCEVM 的 JBR 21）。
    // Loom 1.18+ 的插件变体要求 JVM 25，跑在 21 上会解析失败；1.17.x 是「Java 21 + Gradle 9」的最后一个可用组合。
    id("fabric-loom") version "1.17.21"
}

val minecraftVersion = property("minecraft_version") as String
val yarnMappings = property("yarn_mappings") as String
val loaderVersion = property("loader_version") as String
val fabricVersion = property("fabric_version") as String

val modId = property("mod_id") as String
val modVersion = property("mod_version") as String
val modGroup = property("mod_group") as String

version = modVersion
group = modGroup

base {
    archivesName.set(modId)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://jitpack.io")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")

    // Remold 注解与 API 依赖：提供 @RemoldReinit, @OnReload 等热重载控制注解
    val remoldVersion = (project.findProperty("remold.version") as? String) ?: "0.1.3"
    compileOnly("com.github.I-hope1.Remold:api:v$remoldVersion")
}

// 本地源码树里开发 Remold 时用上一层的脚本（改了立即生效），否则按 tag 拉取对应版本
apply(from = file("../remold.gradle.kts").takeIf { it.isFile }
    ?: "https://raw.githubusercontent.com/I-Hope1/Remold/v${findProperty("remold.version") ?: "0.1.3"}/remold.gradle.kts")

// client 运行目录与 IDE 运行配置沿用 Loom 默认值（runDir = run，ideConfigGenerated = true）。
// 旧写法 ideConfigGenerated(true) / runDir("run") 在 Loom 1.18 起已废弃，会刷弃用警告。
loom {
    runs {
        named("client") {
            // Remold 脚本会向这个运行配置注入 -javaagent 与 DCEVM 参数
        }
    }
}

tasks.processResources {
    inputs.property("version", modVersion)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", loaderVersion)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to modVersion,
            "minecraft_version" to minecraftVersion,
            "loader_version" to loaderVersion
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
