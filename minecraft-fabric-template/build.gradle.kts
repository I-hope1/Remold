plugins {
    java
    // 版本约束：本模板要求 Gradle JVM = 21（带 DCEVM 的 JBR 21）。
    // Loom 1.18+ 的插件变体要求 JVM 25，跑在 21 上会解析失败；1.17.x 是「Java 21 + Gradle 9」的最后一个可用组合。
    id("fabric-loom") version "1.17.21"
    id("io.github.i-hope1.remold")
}

fun prop(name: String): String = providers.gradleProperty(name).get()

val minecraftVersion = prop("minecraft_version")
val yarnMappings = prop("yarn_mappings")
val loaderVersion = prop("loader_version")
val fabricVersion = prop("fabric_version")

val modId = prop("mod_id")
val modVersion = prop("mod_version")
val modGroup = prop("mod_group")

// Remold 版本：脚本 / agent / api 三者必须同版本
val remoldVersion = providers.gradleProperty("remold.version").getOrElse("0.2.8")

version = modVersion
group = modGroup

base {
    archivesName = modId
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenLocal()
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
    compileOnly("com.github.I-hope1.Remold:api:v$remoldVersion")

    // 纯逻辑单测（HotSwapToastModel）：不加载 Minecraft，只跑普通 JUnit。
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}


// client 运行目录与 IDE 运行配置沿用 Loom 默认值（runDir = run，ideConfigGenerated = true）。
// 旧写法 ideConfigGenerated(true) / runDir("run") 在 Loom 1.18 起已废弃，会刷弃用警告。
loom {
    runs {
        named("client") {
            // Remold 脚本会向这个运行配置注入 -javaagent 与 DCEVM 参数
        }
        configureEach {
            // 禁用 Gradle 任务模式，强制生成原生的 IDEA Application 启动项
            preferGradleTask = false
        }
    }
}

tasks.processResources {
    val resourceTargets = mapOf(
        "version" to modVersion,
        "minecraft_version" to minecraftVersion,
        "loader_version" to loaderVersion
    )
    inputs.properties(resourceTargets)

    filesMatching("fabric.mod.json") {
        expand(resourceTargets)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}
