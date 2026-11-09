pluginManagement {
    repositories {
        mavenLocal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "anon-probe"
