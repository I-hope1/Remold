pluginManagement {
    val remoldVersion = providers.gradleProperty("remold.version").getOrElse("0.2.4")
    plugins {
        id("io.github.i-hope1.remold") version remoldVersion
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.github.i-hope1.remold") {
                useModule("com.github.I-hope1.Remold:gradle-plugin:${requested.version ?: remoldVersion}")
            }
        }
    }
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}

rootProject.name = "example-java-mod"
