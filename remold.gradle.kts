// Remold Gradle Script Plugin (Kotlin DSL)
// Usage: apply(from = "remold.gradle.kts") (or raw GitHub URL)

import java.io.File
import org.gradle.api.plugins.JavaApplication

open class RemoldExtension {
    var version: String? = null
    var agentJar: String? = null
    var jbrPath: String? = null
    var debug: Boolean = false
    var debugPort: Int = 5005
    var mainClass: String? = null
    var jar: Any? = null
    var args: MutableList<Any> = mutableListOf()
    var watchDirs: MutableList<File> = mutableListOf()
    var extraJvmArgs: MutableList<String> = mutableListOf()
    var fileShakeMs: Int? = null
    var dependsOn: MutableList<Any> = mutableListOf()
    var deployDir: Any? = null
    var modsDir: Any? = null
    var autoDeploy: Boolean? = null
    val plugins: MutableList<String> = mutableListOf()

    fun plugins(vararg p: String) {
        this.plugins.addAll(p.toList())
    }

    fun jar(target: Any) {
        this.jar = target
    }

    fun args(vararg newArgs: Any) {
        this.args.addAll(newArgs.toList())
    }

    fun dependsOn(vararg tasks: Any) {
        this.dependsOn.addAll(tasks.toList())
    }

    fun deployDir(dir: Any) {
        this.deployDir = dir
        this.autoDeploy = true
    }

    fun modsDir(dir: Any) {
        this.modsDir = dir
        this.autoDeploy = true
    }
}

data class RemoldResolvedConfig(
    val mainClass: String,
    val executable: String,
    val agentFile: File,
    val allWatchDirs: List<File>,
    val watchPath: String,
    val jvmArgs: List<String>,
    val appArgs: List<String>,
    val runtimeClasspath: org.gradle.api.file.FileCollection,
    val targetJar: File?
)

val remoldExt = project.extensions.create<RemoldExtension>("remold")

project.plugins.withId("java") {
    val sourceSets = project.extensions.getByType<SourceSetContainer>()

    fun resolveRemoldConfig(): RemoldResolvedConfig {
        // 0. 解析目标 jar (若配置了 jar，进入纯净 JAR 模式)
        val jarProp = remoldExt.jar
            ?: project.findProperty("remold.jar")
            ?: project.findProperty("remoldJar")
        var targetJar: File? = null
        if (jarProp != null) {
            targetJar = project.file(jarProp)
            if (!targetJar.exists()) {
                project.logger.warn("[Remold] 注意：指定的目标启动 JAR 尚不存在: ${targetJar.absolutePath}（非立即运行时可忽略）")
            }
        }

        // 1. 解析 mainClass
        var main = remoldExt.mainClass
            ?: (project.findProperty("remold.mainClass") as? String)
            ?: (project.findProperty("remoldMainClass") as? String)
            ?: (project.extensions.findByName("application") as? JavaApplication)?.mainClass?.orNull
            ?: (project.findProperty("mainClassName") as? String)

        if (main == null && targetJar != null && targetJar.exists()) {
            try {
                val jf = java.util.jar.JarFile(targetJar)
                main = jf.manifest?.mainAttributes?.getValue("Main-Class")
                jf.close()
            } catch (_: Exception) { }
        }

        if (main == null) {
            if (targetJar != null && targetJar.exists()) {
                throw GradleException("[Remold] 未能从 ${targetJar.name} 的 MANIFEST.MF 中读取到 Main-Class，请在 remold { mainClass = \"...\" } 中显式指定。")
            } else if (targetJar != null) {
                main = "unknown.MainClass"
            } else {
                throw GradleException("[Remold] 未配置 mainClass 或 jar。请在 remold { mainClass = \"...\" }、remold { jar = \"...\" } 或 gradle.properties 中指定。")
            }
        }

        // 2. 解析 JBR 路径
        var execPath = "java"
        val jbr = remoldExt.jbrPath
            ?: (project.findProperty("remold.jbr") as? String)
            ?: (project.findProperty("jbr.home") as? String)
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

        val downloadRemoteJar = { urlStr: String, targetFile: File ->
            var ok = false
            var curUrl = urlStr
            for (hop in 0 until 5) {
                try {
                    val uri = java.net.URI.create(curUrl)
                    val conn = uri.toURL().openConnection() as? java.net.HttpURLConnection ?: break
                    conn.connectTimeout = 10000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "Remold-Gradle-Plugin")
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location")
                        if (loc.isNullOrBlank()) break
                        curUrl = if (loc.startsWith("http")) loc else uri.resolve(loc).toString()
                        continue
                    }
                    if (code == 200) {
                        conn.inputStream.use { inStream ->
                            targetFile.outputStream().use { outStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                        if (targetFile.exists() && targetFile.length() > 1024) {
                            ok = true
                            break
                        } else {
                            targetFile.delete()
                        }
                    }
                    break
                } catch (_: Exception) {
                    targetFile.delete()
                    break
                }
            }
            ok
        }

        // 3. 解析版本与 agent jar
        val ver = remoldExt.version
            ?: (project.findProperty("remold.version") as? String)
            ?: (project.findProperty("remoldVersion") as? String)
            ?: (if (project.rootProject.hasProperty("remoldVersion")) project.rootProject.property("remoldVersion") as? String else null)
        var agentFile: File? = remoldExt.agentJar?.let { File(it) }
        if (agentFile == null || !agentFile.exists()) {
            val candidates = listOf(
                project.file("libs/remold-agent.jar"),
                project.file("remold-agent.jar"),
                project.rootProject.file("core/build/libs/remold-agent.jar"),
                project.rootProject.file("../core/build/libs/remold-agent.jar")
            )
            agentFile = candidates.firstOrNull { it.exists() }
        }

        // 若本地未找到，尝试从 Gradle 依赖缓存或官方 Releases 自动下载
        if (agentFile == null || !agentFile.exists()) {
            if (ver == null) {
                throw GradleException("[Remold] 未配置 Remold 版本号！请在 remold { version = \"...\" } 或 gradle.properties (remold.version=...) 中指定。")
            }
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
                        if (downloadRemoteJar(urlStr, cachedJar)) {
                            agentFile = cachedJar
                            project.logger.lifecycle("[Remold] agent jar 已自动就绪: ${cachedJar.absolutePath}")
                            break
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

        // 同步一份到项目目录 .gradle/remold/ 以支持 IDEA 运行配置使用 $PROJECT_DIR$ 便携相对路径
        try {
            val rootCanonical = project.rootDir.canonicalPath
            val agentCanonical = agentFile.canonicalPath
            if (!agentCanonical.startsWith(rootCanonical)) {
                val localAgent = File(project.rootDir, ".gradle/remold/remold-agent.jar")
                if (!localAgent.exists() || localAgent.length() != agentFile.length()) {
                    localAgent.parentFile.mkdirs()
                    java.nio.file.Files.copy(agentFile.toPath(), localAgent.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                agentFile = localAgent
            }
        } catch (_: Exception) { }

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

        // 6. 解析扩展插件配置 (如 arc)
        val requestedPlugins = mutableListOf<String>()
        requestedPlugins.addAll(remoldExt.plugins.map { it.trim() }.filter { it.isNotEmpty() })
        val pluginProps = (project.findProperty("remold.plugins") as? String)
            ?: (project.findProperty("remoldPlugins") as? String)
        if (!pluginProps.isNullOrBlank()) {
            requestedPlugins.addAll(pluginProps.split("[,;]").map { it.trim() }.filter { it.isNotEmpty() })
        }
        val distinctPlugins = requestedPlugins.distinct()

        val pluginJars = mutableListOf<File>()
        if (distinctPlugins.isNotEmpty()) {
            for (pluginName in distinctPlugins) {
                val candidates = mutableListOf<File>()
                candidates.add(project.rootProject.file("plugin-$pluginName/build/libs/remold-plugin-$pluginName-$ver.jar"))
                candidates.add(project.rootProject.file("plugin-$pluginName/build/libs/remold-plugin-$pluginName.jar"))
                candidates.add(project.rootProject.file("../plugin-$pluginName/build/libs/remold-plugin-$pluginName-$ver.jar"))
                candidates.add(project.rootProject.file("../plugin-$pluginName/build/libs/remold-plugin-$pluginName.jar"))
                if (ver != null) {
                    candidates.add(File(project.gradle.gradleUserHomeDir, "caches/remold/remold-plugin-$pluginName-$ver.jar"))
                }
                var resolvedPluginJar = candidates.firstOrNull { it.exists() }

                if ((resolvedPluginJar == null || !resolvedPluginJar.exists()) && ver != null) {
                    val cacheDir = File(project.gradle.gradleUserHomeDir, "caches/remold")
                    val cachedJar = File(cacheDir, "remold-plugin-$pluginName-$ver.jar")
                    if (cachedJar.exists() && cachedJar.length() > 1024) {
                        resolvedPluginJar = cachedJar
                    } else {
                        project.logger.lifecycle("[Remold] 正在自动拉取插件: remold-plugin-$pluginName.jar (版本: $ver)...")
                        val pluginUrls = listOf(
                            "https://github.com/I-hope1/Remold/releases/download/v$ver/remold-plugin-$pluginName-$ver.jar",
                            "https://github.com/I-hope1/Remold/releases/download/v$ver/remold-plugin-$pluginName.jar",
                            "https://github.com/I-hope1/Remold/releases/download/$ver/remold-plugin-$pluginName-$ver.jar",
                            "https://github.com/I-hope1/Remold/releases/download/$ver/remold-plugin-$pluginName.jar"
                        )
                        cacheDir.mkdirs()
                        for (urlStr in pluginUrls) {
                            if (downloadRemoteJar(urlStr, cachedJar)) {
                                resolvedPluginJar = cachedJar
                                project.logger.lifecycle("[Remold] 插件已自动就绪: ${cachedJar.absolutePath}")
                                break
                            }
                        }
                    }
                }

                if (resolvedPluginJar != null && resolvedPluginJar.exists()) {
                    try {
                        val rootCanonical = project.rootDir.canonicalPath
                        val pluginCanonical = resolvedPluginJar.canonicalPath
                        if (!pluginCanonical.startsWith(rootCanonical)) {
                            val localPlugin = File(project.rootDir, ".gradle/remold/plugins/${resolvedPluginJar.name}")
                            if (!localPlugin.exists() || localPlugin.length() != resolvedPluginJar.length()) {
                                localPlugin.parentFile.mkdirs()
                                java.nio.file.Files.copy(resolvedPluginJar.toPath(), localPlugin.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            }
                            resolvedPluginJar = localPlugin
                        }
                    } catch (_: Exception) { }
                    pluginJars.add(resolvedPluginJar)
                } else {
                    project.logger.warn("[Remold] 未找到插件 remold-plugin-$pluginName 的 Jar 包，若已内嵌或无需外部 Jar 可忽略。")
                }
            }

            args.add("-Dremold.plugins=${distinctPlugins.joinToString(",")}")
            if (pluginJars.isNotEmpty()) {
                val pluginJarsPath = pluginJars.joinToString(File.pathSeparator) { it.absolutePath }
                args.add("-Dremold.plugin.jars=$pluginJarsPath")
            }
        }

        val runtimeClasspath = if (targetJar != null) project.files(targetJar) else sourceSets["main"].runtimeClasspath
        val appArgs = remoldExt.args.map { it.toString() }

        return RemoldResolvedConfig(
            mainClass = main,
            executable = execPath,
            agentFile = agentFile,
            allWatchDirs = allWatchDirs,
            watchPath = watchPath,
            jvmArgs = args,
            appArgs = appArgs,
            runtimeClasspath = runtimeClasspath,
            targetJar = targetJar
        )
    }

    tasks.register<JavaExec>("runRemold") {
        group = "application"
        description = "Runs the application with Remold Java Agent & JBR DCEVM (blocking foreground)"

        classpath = sourceSets["main"].runtimeClasspath

        doFirst {
            val conf = resolveRemoldConfig()
            if (conf.targetJar != null && !conf.targetJar.exists()) {
                throw GradleException("[Remold] 指定的目标启动 JAR 不存在，无法启动应用: ${conf.targetJar.absolutePath}")
            }
            mainClass.set(conf.mainClass)
            classpath = conf.runtimeClasspath
            if (conf.appArgs.isNotEmpty()) {
                args(conf.appArgs)
            }
            if (conf.executable != "java") {
                setExecutable(conf.executable)
            }
            jvmArgs(conf.jvmArgs)

            logger.lifecycle("[Remold] 启动配置已装配:")
            if (conf.targetJar != null) {
                logger.lifecycle("  - 目标启动 JAR: ${conf.targetJar.absolutePath}")
            }
            logger.lifecycle("  - 主类: ${conf.mainClass}")
            logger.lifecycle("  - Agent: ${conf.agentFile.absolutePath}")
            logger.lifecycle("  - Watch 目录 (${conf.allWatchDirs.size} 个): ${conf.watchPath}")
            if (conf.appArgs.isNotEmpty()) {
                logger.lifecycle("  - 应用参数: ${conf.appArgs.joinToString(" ")}")
            }
            if (remoldExt.fileShakeMs != null) {
                logger.lifecycle("  - File Shake: ${remoldExt.fileShakeMs}ms")
            }
            if (conf.executable != "java") {
                logger.lifecycle("  - JBR: ${conf.executable}")
            }
        }
    }

    fun resolveDeployDir(): File? {
        val custom = remoldExt.deployDir
            ?: remoldExt.modsDir
            ?: project.findProperty("remold.deployDir")
            ?: project.findProperty("remold.modsDir")
            ?: project.findProperty("remoldDeployDir")
            ?: project.findProperty("remoldModsDir")
        return if (custom != null) project.file(custom) else null
    }

    tasks.register<Copy>("deployArtifact") {
        group = "application"
        description = "Builds and deploys the output JAR into the specified deploy directory"
        dependsOn("jar")
        doFirst {
            resolveRemoldConfig()
        }
        from(tasks.named("jar"))
        into(provider {
            resolveDeployDir()
                ?: throw GradleException("[Remold] 开启了 autoDeploy 但未指定目标部署目录！请在 remold { deployDir = \"...\" } 或 gradle.properties (remold.deployDir=...) 中配置。")
        })
        doLast {
            logger.lifecycle("[Remold] 产物已自动部署至: ${resolveDeployDir()?.absolutePath}")
        }
    }

    tasks.register("deployMod") {
        group = "application"
        description = "Alias for deployArtifact"
        dependsOn("deployArtifact")
    }

    tasks.named("classes") {
        doLast {
            try {
                val localAgent = File(project.rootDir, ".gradle/remold/remold-agent.jar")
                if (!localAgent.exists() || localAgent.length() <= 1024) {
                    resolveRemoldConfig()
                }
            } catch (_: Exception) { }
        }
    }

    val generateIdeaRunConfigInternal: () -> File? = {
        val conf = resolveRemoldConfig()
        val ideaRunDir = File(project.rootDir, ".idea/runConfigurations")
        ideaRunDir.mkdirs()

        fun xmlEscape(s: String?): String {
            if (s == null) return ""
            return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        }

        fun makePortable(p: String?): String {
            if (p == null) return ""
            val rootPath = project.rootDir.absolutePath.replace('\\', '/')
            var norm = p.replace('\\', '/')
            val rootLower = rootPath.lowercase()
            while (true) {
                val normLower = norm.lowercase()
                val idx = normLower.indexOf(rootLower)
                if (idx == -1) break
                norm = norm.substring(0, idx) + "\$PROJECT_DIR\$" + norm.substring(idx + rootPath.length)
            }
            return norm
        }

        val portableJvmArgs = conf.jvmArgs.joinToString(" ") { makePortable(it) }
        val portableAppArgs = conf.appArgs.joinToString(" ") { makePortable(it) }
        val jrePath = if (conf.executable != "java") conf.executable else ""
        val hasCustomJre = jrePath.isNotEmpty()

        val xmlFile = File(ideaRunDir, "Run_with_Remold.xml")
        val sb = StringBuilder()
        sb.append("<component name=\"ProjectRunConfigurationManager\">\n")

        if (conf.targetJar != null) {
            val portableJar = makePortable(conf.targetJar.absolutePath)
            sb.append("  <configuration default=\"false\" name=\"Run with Remold\" type=\"JarApplication\">\n")
            sb.append("    <option name=\"JAR_PATH\" value=\"${xmlEscape(portableJar)}\" />\n")
            sb.append("    <option name=\"VM_PARAMETERS\" value=\"${xmlEscape(portableJvmArgs)}\" />\n")
            if (portableAppArgs.isNotEmpty()) {
                sb.append("    <option name=\"PROGRAM_PARAMETERS\" value=\"${xmlEscape(portableAppArgs)}\" />\n")
            }
            sb.append("    <option name=\"WORKING_DIRECTORY\" value=\"\$PROJECT_DIR\$\" />\n")
            if (hasCustomJre) {
                sb.append("    <option name=\"ALTERNATIVE_JRE_PATH\" value=\"${xmlEscape(jrePath)}\" />\n")
                sb.append("    <option name=\"ALTERNATIVE_JRE_PATH_ENABLED\" value=\"true\" />\n")
            }
            sb.append("    <method v=\"2\">\n")
            val shouldAutoDeploy = remoldExt.autoDeploy
                ?: project.findProperty("remold.autoDeploy")?.toString()?.toBoolean()
                ?: (remoldExt.deployDir != null || remoldExt.modsDir != null || project.findProperty("remold.deployDir") != null || project.findProperty("remold.modsDir") != null || project.findProperty("remoldDeployDir") != null || project.findProperty("remoldModsDir") != null)
            if (shouldAutoDeploy) {
                sb.append("      <option name=\"Gradle.BeforeRunTask\" enabled=\"true\" tasks=\"deployArtifact\" externalProjectPath=\"\$PROJECT_DIR\$\" />\n")
            } else {
                sb.append("      <option name=\"Gradle.BeforeRunTask\" enabled=\"true\" tasks=\"classes\" externalProjectPath=\"\$PROJECT_DIR\$\" />\n")
            }
            sb.append("    </method>\n")
            sb.append("  </configuration>\n")
        } else {
            val moduleName = "${project.name}.main"
            sb.append("  <configuration default=\"false\" name=\"Run with Remold\" type=\"Application\" factoryName=\"Application\">\n")
            sb.append("    <option name=\"MAIN_CLASS_NAME\" value=\"${xmlEscape(conf.mainClass)}\" />\n")
            sb.append("    <module name=\"${xmlEscape(moduleName)}\" />\n")
            sb.append("    <option name=\"VM_PARAMETERS\" value=\"${xmlEscape(portableJvmArgs)}\" />\n")
            if (portableAppArgs.isNotEmpty()) {
                sb.append("    <option name=\"PROGRAM_PARAMETERS\" value=\"${xmlEscape(portableAppArgs)}\" />\n")
            }
            sb.append("    <option name=\"WORKING_DIRECTORY\" value=\"\$PROJECT_DIR\$\" />\n")
            if (hasCustomJre) {
                sb.append("    <option name=\"ALTERNATIVE_JRE_PATH\" value=\"${xmlEscape(jrePath)}\" />\n")
                sb.append("    <option name=\"ALTERNATIVE_JRE_PATH_ENABLED\" value=\"true\" />\n")
            }
            sb.append("    <method v=\"2\">\n")
            sb.append("      <option name=\"Make\" enabled=\"true\" />\n")
            sb.append("    </method>\n")
            sb.append("  </configuration>\n")
        }
        sb.append("</component>\n")

        xmlFile.writeText(sb.toString())
        xmlFile
    }

    project.afterEvaluate {
        val shouldAutoDeploy = remoldExt.autoDeploy
            ?: project.findProperty("remold.autoDeploy")?.toString()?.toBoolean()
            ?: (remoldExt.deployDir != null || remoldExt.modsDir != null || project.findProperty("remold.deployDir") != null || project.findProperty("remold.modsDir") != null || project.findProperty("remoldDeployDir") != null || project.findProperty("remoldModsDir") != null)

        tasks.named<JavaExec>("runRemold") {
            val jbr = remoldExt.jbrPath
                ?: (project.findProperty("remold.jbr") as? String)
                ?: (project.findProperty("jbr.home") as? String)
                ?: System.getenv("JBR_HOME")
                ?: System.getProperty("jbr.home")
            if (!jbr.isNullOrBlank()) {
                javaLauncher.set(null as org.gradle.jvm.toolchain.JavaLauncher?)
            }
            if (shouldAutoDeploy) {
                dependsOn("deployArtifact")
            }
            if (remoldExt.dependsOn.isNotEmpty()) {
                dependsOn(remoldExt.dependsOn)
            }
        }
        tasks.named("startRemold") {
            if (shouldAutoDeploy) {
                dependsOn("deployArtifact")
            }
            if (remoldExt.dependsOn.isNotEmpty()) {
                dependsOn(remoldExt.dependsOn)
            }
        }
        tasks.named("generateRemoldScript") {
            if (shouldAutoDeploy) {
                dependsOn("deployArtifact")
            }
            if (remoldExt.dependsOn.isNotEmpty()) {
                dependsOn(remoldExt.dependsOn)
            }
        }
        tasks.named("generateIdeaRunConfig") {
            if (shouldAutoDeploy) {
                dependsOn("deployArtifact")
            }
            if (remoldExt.dependsOn.isNotEmpty()) {
                dependsOn(remoldExt.dependsOn)
            }
        }

        val ideaDir = File(project.rootDir, ".idea")
        val xmlFile = File(project.rootDir, ".idea/runConfigurations/Run_with_Remold.xml")
        if (ideaDir.exists()) {
            tasks.findByName("classes")?.finalizedBy("generateIdeaRunConfig")
            if (!xmlFile.exists()) {
                try {
                    val out = generateIdeaRunConfigInternal()
                    if (out != null) {
                        logger.lifecycle("[Remold] 自动就绪 IntelliJ IDEA 运行配置: ${out.absolutePath}")
                    }
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    tasks.register("generateIdeaRunConfig") {
        group = "application"
        description = "Generates IntelliJ IDEA run configuration into .idea/runConfigurations/Run_with_Remold.xml"
        dependsOn("classes")

        doLast {
            val xmlFile = generateIdeaRunConfigInternal()
            if (xmlFile != null) {
                logger.lifecycle("[Remold] 已生成 IntelliJ IDEA 运行配置: ${xmlFile.absolutePath}")
                logger.lifecycle("[Remold] 用 IDEA 打开本项目即可直接点击右上角运行 / 调试按钮！")
            }
        }
    }

    tasks.register("generateRemoldScript") {
        group = "application"
        description = "Generates native standalone launcher scripts and args file to run Remold without Gradle Daemon"
        dependsOn("classes", "generateIdeaRunConfig")

        doLast {
            val conf = resolveRemoldConfig()
            if (conf.targetJar != null && !conf.targetJar.exists()) {
                throw GradleException("[Remold] 指定的目标启动 JAR 不存在，无法启动应用: ${conf.targetJar.absolutePath}")
            }
            val buildDir = project.layout.buildDirectory.asFile.get()
            val remoldDir = File(buildDir, "remold")
            remoldDir.mkdirs()

            val argsFile = File(remoldDir, "remold.args")
            val lines = mutableListOf<String>()
            lines.addAll(conf.jvmArgs)
            lines.add("-cp")
            lines.add(conf.runtimeClasspath.asPath)
            lines.add(conf.mainClass)
            if (conf.appArgs.isNotEmpty()) {
                lines.addAll(conf.appArgs)
            }

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
            if (conf.targetJar != null && !conf.targetJar.exists()) {
                throw GradleException("[Remold] 指定的目标启动 JAR 不存在，无法启动应用: ${conf.targetJar.absolutePath}")
            }
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
