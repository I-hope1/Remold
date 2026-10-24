plugins {
    java
    id("fabric-loom") version "1.8.11"
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
}

// 引入 Remold 热重载自动化脚本插件 (本地开发优先，独立使用回退官方 GitHub 源)
val localRemold = rootProject.file("../remold.gradle.kts")
if (localRemold.exists()) {
    apply(from = localRemold)
} else {
    apply(from = "https://raw.githubusercontent.com/I-hope1/Remold/main/remold.gradle.kts")
}

loom {
    runs {
        named("client") {
            ideConfigGenerated(true)
            runDir("run")
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
