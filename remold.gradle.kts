// Remold Gradle Script Plugin (Kotlin DSL)
// Usage: apply(from = "remold.gradle.kts") (or raw GitHub URL)

import java.io.File
import org.gradle.api.plugins.JavaApplication

open class RemoldExtension {
    var version: String = "0.1.0"
    var agentJar: String? = null
    var jbrPath: String? = null
    var debug: Boolean = false
    var debugPort: Int = 5005
    var mainClass: String? = null
    var watchDirs: MutableList<File> = mutableListOf()
    var extraJvmArgs: MutableList<String> = mutableListOf()
    var fileShakeMs: Int? = null
}

val remoldExt = project.extensions.create<RemoldExtension>("remold")

project.plugins.withId("java") {
    val sourceSets = project.extensions.getByType<SourceSetContainer>()

    tasks.register<JavaExec>("runRemold") {
        group = "application"
        description = "Runs the application with Remold Java Agent & JBR DCEVM"

        classpath = sourceSets["main"].runtimeClasspath

        doFirst {
            // 1. 解析 mainClass
            val main = remoldExt.mainClass
                ?: (project.extensions.findByName("application") as? JavaApplication)?.mainClass?.orNull
                ?: (project.findProperty("mainClassName") as? String)
            if (main != null) {
                mainClass.set(main)
            } else if (!mainClass.isPresent) {
                throw GradleException("[Remold] 未配置 mainClass。请在 remold { mainClass = \"...\" } 或 application 插件中指定。")
            }

            // 2. 解析 JBR 路径
            val jbr = remoldExt.jbrPath
                ?: System.getenv("JBR_HOME")
                ?: System.getProperty("jbr.home")
            if (!jbr.isNullOrBlank()) {
                val jbrDir = File(jbr)
                val isWin = System.getProperty("os.name").lowercase().contains("win")
                val javaBin = if (jbrDir.isFile) jbrDir else File(jbrDir, if (isWin) "bin/java.exe" else "bin/java")
                if (javaBin.exists()) {
                    executable = javaBin.absolutePath
                } else {
                    logger.warn("[Remold] 指定的 JBR 路径不存在: $jbr，将使用默认 JVM 运行。")
                }
            }

            // 3. 解析 agent jar
            var agentFile: File? = remoldExt.agentJar?.let { File(it) }
            if (agentFile == null || !agentFile.exists()) {
                val candidates = listOf(
                    project.file("libs/remold-agent.jar"),
                    project.file("remold-agent.jar"),
                    project.rootProject.file("core/build/libs/remold-agent.jar")
                )
                agentFile = candidates.firstOrNull { it.exists() }
            }

            // 若本地未找到，尝试从 Gradle 依赖缓存或官方 Releases 自动下载
            if (agentFile == null || !agentFile.exists()) {
                val ver = remoldExt.version
                val cacheDir = File(project.gradle.gradleUserHomeDir, "caches/remold")
                val cachedJar = File(cacheDir, "remold-agent-$ver.jar")

                if (cachedJar.exists() && cachedJar.length() > 1024) {
                    agentFile = cachedJar
                } else {
                    // 尝试 1: 通过 Gradle detachedConfiguration 从已有仓库（JitPack / MavenLocal 等）解析
                    try {
                        val detached = project.configurations.detachedConfiguration(
                            project.dependencies.create("com.github.I-hope1.Remold:core:$ver")
                        )
                        detached.isTransitive = false
                        val resolved = detached.resolve()
                        if (resolved.isNotEmpty()) {
                            agentFile = resolved.first()
                        }
                    } catch (_: Exception) { }

                    // 尝试 2: 从 GitHub Releases 自动下载至 Gradle 缓存目录
                    if (agentFile == null || !agentFile.exists()) {
                        logger.lifecycle("[Remold] 本地未找到 agent jar，正在自动拉取 remold-agent.jar (版本: $ver)...")
                        val downloadUrls = listOf(
                            "https://github.com/I-hope1/Remold/releases/download/v$ver/remold-agent.jar",
                            "https://github.com/I-hope1/Remold/releases/download/$ver/remold-agent.jar"
                        )
                        cacheDir.mkdirs()
                        for (urlStr in downloadUrls) {
                            try {
                                val uri = java.net.URI.create(urlStr)
                                var conn = uri.toURL().openConnection()
                                conn.connectTimeout = 10000
                                conn.readTimeout = 30000
                                if (conn is java.net.HttpURLConnection) {
                                    conn.instanceFollowRedirects = true
                                    val code = conn.responseCode
                                    if (code in 300..399) {
                                        val loc = conn.getHeaderField("Location")
                                        if (!loc.isNullOrBlank()) {
                                            conn = java.net.URI.create(loc).toURL().openConnection()
                                            conn.connectTimeout = 10000
                                            conn.readTimeout = 30000
                                        }
                                    }
                                }
                                conn.inputStream.use { inStream ->
                                    cachedJar.outputStream().use { outStream ->
                                        inStream.copyTo(outStream)
                                    }
                                }
                                if (cachedJar.exists() && cachedJar.length() > 1024) {
                                    agentFile = cachedJar
                                    logger.lifecycle("[Remold] agent jar 已自动就绪: ${cachedJar.absolutePath}")
                                    break
                                } else {
                                    cachedJar.delete()
                                }
                            } catch (_: Exception) {
                                cachedJar.delete()
                            }
                        }
                    }
                }
            }

            if (agentFile == null || !agentFile.exists()) {
                throw GradleException(
                    "[Remold] 未找到且无法自动下载 remold-agent.jar (目标版本: ${remoldExt.version})！\n" +
                    "请检查网络或在 remold { version = \"...\" } 中指定有效版本，\n" +
                    "亦可在 remold { agentJar = \"/path/to/remold-agent.jar\" } 中显式指定本地文件。"
                )
            }

            // 4. 自动推导编译输出目录并拼装监听路径
            val classesDirs = sourceSets["main"].output.classesDirs.files
            val allWatchDirs = (classesDirs + remoldExt.watchDirs).filter { it.exists() }
            val watchPath = allWatchDirs.joinToString(File.pathSeparator) { it.absolutePath }

            // 5. 装配 JVM 参数
            val args = mutableListOf<String>()
            args.add("-XX:+AllowEnhancedClassRedefinition")
            args.add("-javaagent:${agentFile.absolutePath}=$watchPath")
            args.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED")
            args.add("--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED")
            args.add("--add-exports=java.base/jdk.internal.loader=ALL-UNNAMED")
            args.add("--enable-native-access=ALL-UNNAMED")

            if (remoldExt.debug || "true".equals(System.getProperty("remold.debug"), ignoreCase = true)) {
                val port = remoldExt.debugPort
                args.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$port")
                logger.lifecycle("[Remold] JDWP 调试已开启，仅监听本机: 127.0.0.1:$port")
            }

            args.addAll(remoldExt.extraJvmArgs)
            if (remoldExt.fileShakeMs != null) {
                args.add("-Dremold.agent.file_shake_ms=${remoldExt.fileShakeMs}")
            }
            jvmArgs(args)

            logger.lifecycle("[Remold] 启动配置已装配:")
            logger.lifecycle("  - Agent: ${agentFile.absolutePath}")
            logger.lifecycle("  - Watch 目录 (${allWatchDirs.size} 个): $watchPath")
            if (remoldExt.fileShakeMs != null) {
                logger.lifecycle("  - File Shake: ${remoldExt.fileShakeMs}ms")
            }
            if (executable != "java") {
                logger.lifecycle("  - JBR: $executable")
            }
        }
    }
}
