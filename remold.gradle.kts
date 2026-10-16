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

data class RemoldResolvedConfig(
    val mainClass: String,
    val executable: String,
    val agentFile: File,
    val allWatchDirs: List<File>,
    val watchPath: String,
    val jvmArgs: List<String>,
    val runtimeClasspath: org.gradle.api.file.FileCollection
)

val remoldExt = project.extensions.create<RemoldExtension>("remold")

project.plugins.withId("java") {
    val sourceSets = project.extensions.getByType<SourceSetContainer>()

    fun resolveRemoldConfig(): RemoldResolvedConfig {
        // 1. 解析 mainClass
        val main = remoldExt.mainClass
            ?: (project.extensions.findByName("application") as? JavaApplication)?.mainClass?.orNull
            ?: (project.findProperty("mainClassName") as? String)
            ?: throw GradleException("[Remold] 未配置 mainClass。请在 remold { mainClass = \"...\" } 或 application 插件中指定。")

        // 2. 解析 JBR 路径
        var execPath = "java"
        val jbr = remoldExt.jbrPath
            ?: System.getenv("JBR_HOME")
            ?: System.getProperty("jbr.home")
        if (!jbr.isNullOrBlank()) {
            val jbrDir = File(jbr)
            val isWin = System.getProperty("os.name").lowercase().contains("win")
            val javaBin = if (jbrDir.isFile) jbrDir else File(jbrDir, if (isWin) "bin/java.exe" else "bin/java")
            if (javaBin.exists()) {
                execPath = javaBin.absolutePath
            } else {
                project.logger.warn("[Remold] 指定的 JBR 路径不存在: $jbr，将使用默认 JVM 运行。")
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

                if (agentFile == null || !agentFile.exists()) {
                    project.logger.lifecycle("[Remold] 本地未找到 agent jar，正在自动拉取 remold-agent.jar (版本: $ver)...")
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
                                project.logger.lifecycle("[Remold] agent jar 已自动就绪: ${cachedJar.absolutePath}")
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
            project.logger.lifecycle("[Remold] JDWP 调试已开启，仅监听本机: 127.0.0.1:$port")
        }

        args.addAll(remoldExt.extraJvmArgs)
        if (remoldExt.fileShakeMs != null) {
            args.add("-Dremold.agent.file_shake_ms=${remoldExt.fileShakeMs}")
        }

        return RemoldResolvedConfig(
            mainClass = main,
            executable = execPath,
            agentFile = agentFile,
            allWatchDirs = allWatchDirs,
            watchPath = watchPath,
            jvmArgs = args,
            runtimeClasspath = sourceSets["main"].runtimeClasspath
        )
    }

    tasks.register<JavaExec>("runRemold") {
        group = "application"
        description = "Runs the application with Remold Java Agent & JBR DCEVM (blocking foreground)"

        classpath = sourceSets["main"].runtimeClasspath

        doFirst {
            val conf = resolveRemoldConfig()
            mainClass.set(conf.mainClass)
            if (conf.executable != "java") {
                executable = conf.executable
            }
            jvmArgs(conf.jvmArgs)

            logger.lifecycle("[Remold] 启动配置已装配:")
            logger.lifecycle("  - Agent: ${conf.agentFile.absolutePath}")
            logger.lifecycle("  - Watch 目录 (${conf.allWatchDirs.size} 个): ${conf.watchPath}")
            if (remoldExt.fileShakeMs != null) {
                logger.lifecycle("  - File Shake: ${remoldExt.fileShakeMs}ms")
            }
            if (conf.executable != "java") {
                logger.lifecycle("  - JBR: ${conf.executable}")
            }
        }
    }

    tasks.register("generateRemoldScript") {
        group = "application"
        description = "Generates native standalone launcher scripts and args file to run Remold without Gradle Daemon"
        dependsOn("classes")

        doLast {
            val conf = resolveRemoldConfig()
            val buildDir = project.layout.buildDirectory.asFile.get()
            val remoldDir = File(buildDir, "remold")
            remoldDir.mkdirs()

            val argsFile = File(remoldDir, "remold.args")
            val lines = mutableListOf<String>()
            lines.addAll(conf.jvmArgs)
            lines.add("-cp")
            lines.add(conf.runtimeClasspath.asPath)
            lines.add(conf.mainClass)

            argsFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                lines.forEach { line ->
                    if (line.contains(' ') || line.contains('\t')) {
                        writer.write("\"${line.replace("\\", "\\\\")}\"\n")
                    } else {
                        writer.write("$line\n")
                    }
                }
            }

            val batFile = File(buildDir, "run-remold.bat")
            batFile.writeText("@echo off\r\nset \"JAVA_EXE=${conf.executable}\"\r\nif not exist \"%JAVA_EXE%\" set \"JAVA_EXE=java\"\r\n\"%JAVA_EXE%\" @\"%~dp0remold\\remold.args\" %*\r\n")

            val shFile = File(buildDir, "run-remold.sh")
            shFile.writeText("#!/usr/bin/env bash\nDIR=\"\$(cd \"\$(dirname \"\${BASH_SOURCE[0]}\")\" && pwd)\"\nJAVA_EXE=\"${conf.executable}\"\nif [ ! -f \"\$JAVA_EXE\" ]; then\n    JAVA_EXE=\"java\"\nfi\nexec \"\$JAVA_EXE\" \"@\$DIR/remold/remold.args\" \"\$@\"\n")
            try {
                shFile.setExecutable(true, false)
            } catch (_: Exception) { }

            logger.lifecycle("[Remold] 已生成原生启动脚本:")
            logger.lifecycle("  - Windows: ${batFile.absolutePath}")
            logger.lifecycle("  - Linux/macOS: ${shFile.absolutePath}")
            logger.lifecycle("  - Args 文件: ${argsFile.absolutePath}")
            logger.lifecycle("[Remold] 直接运行脚本即可启动应用，不经由 Gradle，零 Daemon 占用！")
        }
    }

    tasks.register("startRemold") {
        group = "application"
        description = "Starts the application in a detached process so Gradle Daemon is immediately freed"
        dependsOn("generateRemoldScript")

        doLast {
            val conf = resolveRemoldConfig()
            val buildDir = project.layout.buildDirectory.asFile.get()
            val argsFile = File(buildDir, "remold/remold.args")
            val isWin = System.getProperty("os.name").lowercase().contains("win")

            if (isWin) {
                ProcessBuilder("cmd", "/c", "start", "\"Remold [${project.name}]\"", conf.executable, "@${argsFile.absolutePath}")
                    .directory(project.projectDir)
                    .start()
                logger.lifecycle("[Remold] 应用已在独立控制台窗口启动！")
            } else {
                val logFile = File(buildDir, "remold/remold.log")
                ProcessBuilder(conf.executable, "@${argsFile.absolutePath}")
                    .directory(project.projectDir)
                    .redirectOutput(logFile)
                    .redirectErrorStream(true)
                    .start()
                logger.lifecycle("[Remold] 应用已在后台脱离启动！输出日志: ${logFile.absolutePath}")
            }
            logger.lifecycle("[Remold] Gradle Daemon 已立即释放 (IDLE)。随时可在终端执行 ./gradlew compileJava 触发热重载。")
        }
    }
}
