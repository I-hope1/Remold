plugins {
    java
    // Loom 1.17.x is the last line that runs on JDK 21 (the DCEVM JBR the probe needs).
    id("fabric-loom") version "1.17.21"
}

fun prop(name: String): String = providers.gradleProperty(name).get()

val minecraftVersion = prop("minecraft_version")
val yarnMappings = prop("yarn_mappings")
val loaderVersion = prop("loader_version")

version = prop("mod_version")
group = prop("mod_group")

base {
    archivesName = prop("mod_id")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

// Dumps what run-anon.sh needs to launch a server that loads the mod from
// run/mods (the Gradle output dirs stay off the runtime classpath): the Loom
// runtime classpath, the compile classpath and the dev jar path.
//
// Keep this file free of block/KDoc comments above the registration: with
// Gradle 9.7.1 + Loom 1.17.21 one of them made the Kotlin DSL script stop
// applying the remaining statements silently (no error, task just missing).
tasks.register("anonEnv") {
    dependsOn("jar")
    doLast {
        val jarTask = tasks.named<Jar>("jar").get()
        val main = sourceSets.main.get()
        val text = buildString {
            appendLine("CP=" + main.runtimeClasspath.asPath)
            appendLine("COMPILE_CP=" + main.compileClasspath.asPath)
            appendLine("JAR=" + jarTask.archiveFile.get().asFile.absolutePath)
        }
        val file = layout.buildDirectory.file("anon/env.txt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(text)
        println("[anonEnv] wrote " + file.absolutePath)
    }
}
