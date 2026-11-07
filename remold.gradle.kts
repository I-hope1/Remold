// Remold Gradle 脚本插件（薄壳，Kotlin DSL）
//
// 与 remold.gradle 相同：本文件不含任何构建逻辑，只把二进制插件 `io.github.i-hope1.remold`
// （`:gradle-plugin`）挂到当前工程上。解析、任务注册与参数拼装全部在插件里实现。
//
// 插件来源（从高到低）：
//   1. -Premold.pluginJar=<本地插件 jar> 或环境变量 REMOLD_PLUGIN_JAR（离线 / 本地开发用）
//   2. JitPack 坐标 com.github.I-Hope1.Remold:gradle-plugin:v<version>，
//      version 取 -Premold.version / gradle.properties 的 remoldVersion，缺省用下面的内联版本
//
// 注意：务必用 tag 地址引入本脚本（.../v<version>/remold.gradle.kts），不要用 main。

import org.gradle.api.Plugin
import org.gradle.api.Project

buildscript {
    // Constants must live inside the buildscript block: its receiver is ScriptHandler, and a
    // reference to a script-level val does not resolve from inside it (same limitation as Groovy).
    val shimVersion = "0.2.5"
    val explicitJar = (project.findProperty("remold.pluginJar") ?: System.getenv("REMOLD_PLUGIN_JAR"))?.toString()
    val version = (project.findProperty("remold.version")
        ?: project.findProperty("remoldVersion")
        ?: shimVersion).toString()

    if (explicitJar != null) {
        dependencies {
            classpath(files(explicitJar))
        }
    } else {
        repositories {
            maven("https://jitpack.io")
            mavenCentral()
            gradlePluginPortal()
        }
        dependencies {
            classpath("com.github.I-Hope1.Remold:gradle-plugin:v$version")
        }
    }
}

// 必须按 Class 应用：字符串形式的 apply(plugin = "<id>") 走插件注册表，而注册表在脚本编译期就已固定，
// 看不到上面 buildscript 块新增的 classpath（实测会抛 Plugin not found）。
@Suppress("UNCHECKED_CAST")
val remoldPluginClass = buildscript.classLoader.loadClass("io.github.ihope1.remold.gradle.RemoldPlugin") as Class<Plugin<Project>>
project.plugins.apply(remoldPluginClass)
