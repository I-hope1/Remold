pluginManagement {
    val remoldVersion = providers.gradleProperty("remold.version").getOrElse("0.2.7")
    plugins {
        id("io.github.i-hope1.remold") version remoldVersion
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.github.i-hope1.remold") {
                // JitPack 坐标统一带 v 前缀（与 api / core / plugin-* 的 jitpack publication 以及
                // 仓库根的 remold.gradle(.kts) shim 一致）；用户写不写前缀都能命中。
                val requestedPluginVersion = (requested.version ?: remoldVersion).toString().removePrefix("v")
                useModule("com.github.I-hope1.Remold:gradle-plugin:v$requestedPluginVersion")
            }
        }
    }
    repositories {
        mavenLocal()
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}

rootProject.name = "fabric-gametest-1.21.4"
