// Remold Gradle Script Plugin (Kotlin DSL)
// Usage: apply(from = "https://raw.githubusercontent.com/I-hope1/Remold/v<version>/remold.gradle.kts")
//        本地开发: apply(from = file("../remold.gradle.kts"))
// 注意：远程引入务必用 **tag**（v0.1.3）而不是 main —— 脚本与 agent / api 必须同版本，
//       否则脚本按新接口拼参数、agent 还是旧版本，会在运行期才炸。完整引导块（本地多路径优先 +
//       版本缓存 + -Premold.script 覆盖）见 mindustry-mod-template / minecraft-fabric-template 的构建脚本。

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
    /** 显式指定的插件 Jar（本地开发 Remold 插件时用，优先于自动解析/下载）。 */
    val pluginJars: MutableList<Any> = mutableListOf()

    fun plugins(vararg p: String) {
        this.plugins.addAll(p.toList())
    }

    fun pluginJar(file: Any) {
        this.pluginJars.add(file)
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
    /** 运行用 JVM 的家目录（JDK/JRE home）。IDE 只认家目录，不认 `bin/java[.exe]`。 */
    val executableHome: String,
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

    /**
     * 是否属于 SpongePowered Mixin 生态：Fabric Loom 项目，或显式声明了 net.fabricmc / org.spongepowered 依赖。
     * 命中即默认挂载 plugin-mixin（可用 -Premold.autoMixin=false 关闭）。
     */
    fun usesMixinEcosystem(): Boolean {
        if (project.plugins.hasPlugin("fabric-loom") || project.extensions.findByName("loom") != null) {
            return true
        }
        return try {
            project.configurations.any { cfg ->
                try {
                    cfg.dependencies.any { dep ->
                        dep.group == "org.spongepowered" || dep.group == "net.fabricmc"
                    }
                } catch (_: Throwable) {
                    false
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

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
            } else if (project.plugins.hasPlugin("fabric-loom") || project.extensions.findByName("loom") != null) {
                main = "net.fabricmc.loader.impl.launch.knot.KnotClient"
            } else {
                throw GradleException("[Remold] 未配置 mainClass 或 jar。请在 remold { mainClass = \"...\" }、remold { jar = \"...\" } 或 gradle.properties 中指定。")
            }
        }

        // 2. 解析运行用 JVM
        // 默认使用「运行 Gradle 的这个 JVM」——在 IDEA 里就是 Gradle JVM（项目 SDK），
        // 而不是 PATH / JAVA_HOME 里的 java，避免构建用的 SDK 与启动用的 JDK 不一致。
        val isWin = System.getProperty("os.name").lowercase().contains("win")

        /**
         * 由 JVM 家目录或 java 可执行文件反推出 IDE 认可的 **JRE 家目录**。
         * IDEA 的 ALTERNATIVE_JRE_PATH 只接受家目录（或已注册的 SDK 名）；写入 `...\bin\java.exe`
         * 会被判定为路径不存在并直接弹框拒绝启动。取不到时返回空串（此时不写该字段）。
         */
        fun toJreHome(path: String?): String {
            if (path.isNullOrBlank()) return ""
            var f = File(path)
            if (f.isFile || f.name.equals("java", true) || f.name.equals("java.exe", true)) {
                val bin = f.parentFile ?: return ""
                if (!bin.name.equals("bin", true)) return ""
                f = bin.parentFile ?: return ""
            }
            // JDK 8 的 java.home 通常是 <jdk>/jre，向上取一层才是 IDE 认可的 JDK 家目录
            if (f.name.equals("jre", true)) {
                val parent = f.parentFile
                if (parent != null && File(parent, if (isWin) "bin/java.exe" else "bin/java").exists()) f = parent
            }
            return if (f.isDirectory) f.absolutePath else ""
        }

        var execPath = File(System.getProperty("java.home"), if (isWin) "bin/java.exe" else "bin/java").absolutePath
        var execHome = toJreHome(System.getProperty("java.home"))
        if (!File(execPath).exists()) {
            execPath = "java"
            execHome = ""
        }
        val jbr = remoldExt.jbrPath
            ?: (project.findProperty("remold.jbr") as? String)
            ?: (project.findProperty("jbr.home") as? String)
            ?: System.getenv("JBR_HOME")
            ?: System.getProperty("jbr.home")
        if (!jbr.isNullOrBlank()) {
            val jbrDir = File(jbr)
            val javaBin = if (jbrDir.isFile) jbrDir else File(jbrDir, if (isWin) "bin/java.exe" else "bin/java")
            if (javaBin.exists()) {
                execPath = javaBin.absolutePath
                execHome = toJreHome(javaBin.absolutePath)
            } else {
                project.logger.warn("[Remold] 指定的 JBR 路径不存在: $jbr，将使用项目 SDK 的 JVM 运行。")
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
        // 显式指定优先：remold { agentJar = "..." } 或 gradle.properties 的 remold.agentJar
        var agentFile: File? = (remoldExt.agentJar ?: (project.findProperty("remold.agentJar") as? String))?.let { File(it) }
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
                    try {
                        val detachedAll = project.configurations.detachedConfiguration(
                            project.dependencies.create("com.github.I-hope1.Remold:core:$ver:all")
                        )
                        detachedAll.isTransitive = false
                        val resolvedAll = detachedAll.resolve()
                        if (resolvedAll.isNotEmpty()) {
                            agentFile = resolvedAll.first()
                        }
                    } catch (_: Exception) { }
                }

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

        var safeAgent: File = agentFile

        // 同步一份到项目目录 .gradle/remold/ 以支持 IDEA 运行配置使用 $PROJECT_DIR$ 便携相对路径
        try {
            val rootCanonical = project.rootDir.canonicalPath
            val agentCanonical = safeAgent.canonicalPath
            if (!agentCanonical.startsWith(rootCanonical)) {
                val localAgent = File(project.rootDir, ".gradle/remold/remold-agent.jar")
                if (!localAgent.exists() || localAgent.length() != safeAgent.length()) {
                    localAgent.parentFile.mkdirs()
                    java.nio.file.Files.copy(safeAgent.toPath(), localAgent.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                safeAgent = localAgent
            }
        } catch (_: Exception) { }

        // 4. 自动推导编译输出与资源目录并拼装监听路径
        val classesDirs = sourceSets["main"].output.classesDirs.files
        val resourceDirs = sourceSets["main"].resources.srcDirs
        val assetsDir = File(project.projectDir, "assets")
        val outResourceDir = sourceSets["main"].output.resourcesDir
        val allCandidateDirs = mutableListOf<File>()
        allCandidateDirs.addAll(classesDirs)
        allCandidateDirs.addAll(resourceDirs)
        if (assetsDir.exists()) allCandidateDirs.add(assetsDir)
        if (outResourceDir != null && outResourceDir.exists()) allCandidateDirs.add(outResourceDir)
        allCandidateDirs.addAll(remoldExt.watchDirs)
        val allWatchDirs = allCandidateDirs.filter { it.exists() }.distinct()
        val watchPath = allWatchDirs.joinToString(File.pathSeparator) { it.absolutePath }

        // 5. 装配 JVM 参数
        val args = mutableListOf<String>()
        args.add("-XX:+IgnoreUnrecognizedVMOptions")
        args.add("-XX:+AllowEnhancedClassRedefinition")
        args.add("-javaagent:${safeAgent.absolutePath}=$watchPath")
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

        // 6. 解析扩展插件配置 (如 arc / mixin)
        val requestedPlugins = mutableListOf<String>()
        requestedPlugins.addAll(remoldExt.plugins.map { it.trim() }.filter { it.isNotEmpty() })
        val pluginProps = (project.findProperty("remold.plugins") as? String)
            ?: (project.findProperty("remoldPlugins") as? String)
        if (!pluginProps.isNullOrBlank()) {
            requestedPlugins.addAll(pluginProps.split("[,;]").map { it.trim() }.filter { it.isNotEmpty() })
        }
        // 自动挂载：Mixin 生态项目（Fabric Loom / 声明了 Mixin 依赖）默认启用 plugin-mixin。
        // 可用 -Premold.autoMixin=false 关闭。
        val autoMixin = (project.findProperty("remold.autoMixin") as? String)?.toBoolean() ?: true
        if (autoMixin && requestedPlugins.none { it.equals("mixin", ignoreCase = true) } && usesMixinEcosystem()) {
            requestedPlugins.add("mixin")
        }
        val distinctPlugins = requestedPlugins.distinct()

        val pluginJars = mutableListOf<File>()
        // 显式指定的插件 Jar 优先（本地开发 Remold 插件时无需发布/缓存）
        for (explicit in remoldExt.pluginJars) {
            val f = project.file(explicit)
            if (f.exists()) {
                pluginJars.add(f)
            } else {
                project.logger.warn("[Remold] 指定的插件 Jar 不存在: ${f.absolutePath}")
            }
        }
        if (distinctPlugins.isNotEmpty()) {
            for (pluginName in distinctPlugins) {
                // 该插件已由显式 pluginJar(...) 提供时跳过解析
                val alreadyProvided = pluginJars.any { it.name.contains("plugin-$pluginName") }
                if (alreadyProvided) {
                    continue
                }
                val candidates = mutableListOf<File>()
                candidates.add(project.rootProject.file("plugin-$pluginName/build/libs/remold-plugin-$pluginName-$ver.jar"))
                candidates.add(project.rootProject.file("plugin-$pluginName/build/libs/remold-plugin-$pluginName.jar"))
                candidates.add(project.rootProject.file("../plugin-$pluginName/build/libs/remold-plugin-$pluginName-$ver.jar"))
                candidates.add(project.rootProject.file("../plugin-$pluginName/build/libs/remold-plugin-$pluginName.jar"))
                // 本地开发便利：模板与 Remold 本体仓库并排放置时（../<repo>/plugin-x/build/libs）
                try {
                    project.rootDir.parentFile?.listFiles()?.forEach { sibling ->
                        if (sibling.isDirectory) {
                            candidates.add(File(sibling, "plugin-$pluginName/build/libs/remold-plugin-$pluginName-$ver.jar"))
                            candidates.add(File(sibling, "plugin-$pluginName/build/libs/remold-plugin-$pluginName.jar"))
                        }
                    }
                } catch (_: Throwable) { }
                // 本地联调：publishToMavenLocal 的产物优先于远端发布版。
                // 两个坐标不是大小写差异：build.gradle 里 group = 'io.github.i-hope1.remold'（首段 io），
                // 插件解析走的是 JitPack 约定坐标 com.github.I-hope1.Remold（首段 com），是两个不同命名空间。
                // 其中 io.github.i-hope1.remold 目前**只在 mavenLocal 存在**（README 标注为预留的 Central 坐标），
                // 对外可用的一律是 JitPack 坐标。因此下面这条候选只服务本地联调：文件不存在即跳过，
                // 不会影响其他开发者。按文件路径探测，不依赖 repositories 是否声明 mavenLocal()；
                // 两种拼法都列，避免在大小写敏感的文件系统（Linux）上漏查。
                if (ver != null) {
                    val m2Root = File(System.getProperty("user.home"), ".m2/repository")
                    listOf("io/github/i-hope1/remold", "com/github/I-hope1/Remold").forEach { groupPath ->
                        candidates.add(File(m2Root, "$groupPath/plugin-$pluginName/$ver/plugin-$pluginName-$ver.jar"))
                    }
                }
                if (ver != null) {
                    candidates.add(File(project.gradle.gradleUserHomeDir, "caches/remold/remold-plugin-$pluginName-$ver.jar"))
                }
                var resolvedPluginJar = candidates.firstOrNull { it.exists() }

                if ((resolvedPluginJar == null || !resolvedPluginJar.exists()) && ver != null) {
                    try {
                        val detached = project.configurations.detachedConfiguration(
                            project.dependencies.create("com.github.I-hope1.Remold:plugin-$pluginName:$ver")
                        )
                        detached.isTransitive = false
                        val resolved = detached.resolve()
                        if (resolved.isNotEmpty()) {
                            resolvedPluginJar = resolved.first()
                        }
                    } catch (_: Exception) { }
                }

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
                    var safePlugin: File = resolvedPluginJar
                    try {
                        val rootCanonical = project.rootDir.canonicalPath
                        val pluginCanonical = safePlugin.canonicalPath
                        if (!pluginCanonical.startsWith(rootCanonical)) {
                            val localPlugin = File(project.rootDir, ".gradle/remold/plugins/${safePlugin.name}")
                            if (!localPlugin.exists() || localPlugin.length() != safePlugin.length()) {
                                localPlugin.parentFile.mkdirs()
                                java.nio.file.Files.copy(safePlugin.toPath(), localPlugin.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            }
                            safePlugin = localPlugin
                        }
                    } catch (_: Exception) { }
                    pluginJars.add(safePlugin)
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
            executableHome = execHome,
            agentFile = safeAgent,
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
        dependsOn(provider {
            if (project.tasks.findByName("remapJar") != null) "remapJar" else "jar"
        })
        doFirst {
            resolveRemoldConfig()
        }
        from(provider {
            project.tasks.findByName("remapJar") ?: tasks.named("jar").get()
        })
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
        // IDEA 的 ALTERNATIVE_JRE_PATH 只认 JRE/JDK 家目录（或已注册的 SDK 名），
        // 写 java.exe 会被判为无效并弹框拒绝启动；统一转成家目录，并改用 / 分隔以对齐 IDE 的注册表口径。
        val jrePath = conf.executableHome.replace('\\', '/')
        val hasCustomJre = jrePath.isNotEmpty()

        val xmlFile = File(ideaRunDir, "Run_with_Remold.xml")
        val sb = StringBuilder()
        sb.append("<component name=\"ProjectRunConfigurationManager\">\n")

        val isLoom = project.plugins.hasPlugin("fabric-loom") || project.extensions.findByName("loom") != null
        if (isLoom) {
            sb.append("  <configuration default=\"false\" name=\"Run with Remold\" type=\"GradleRunConfiguration\" factoryName=\"Gradle\">\n")
            sb.append("    <ExternalSystemSettings>\n")
            sb.append("      <option name=\"executionName\" />\n")
            sb.append("      <option name=\"externalProjectPath\" value=\"\$PROJECT_DIR\$\" />\n")
            sb.append("      <option name=\"externalSystemIdString\" value=\"GRADLE\" />\n")
            sb.append("      <option name=\"scriptParameters\" value=\"\" />\n")
            sb.append("      <option name=\"taskDescriptions\">\n")
            sb.append("        <list />\n")
            sb.append("      </option>\n")
            sb.append("      <option name=\"taskNames\">\n")
            sb.append("        <list>\n")
            sb.append("          <option value=\"runClient\" />\n")
            sb.append("        </list>\n")
            sb.append("      </option>\n")
            sb.append("      <option name=\"vmOptions\" value=\"\" />\n")
            sb.append("    </ExternalSystemSettings>\n")
            sb.append("    <ExternalSystemDebugServerProcess>true</ExternalSystemDebugServerProcess>\n")
            sb.append("    <ExternalSystemReAttachDebugProcess>true</ExternalSystemReAttachDebugProcess>\n")
            sb.append("    <DebugAllEnabled>false</DebugAllEnabled>\n")
            sb.append("    <RunAsTest>false</RunAsTest>\n")
            sb.append("    <method v=\"2\" />\n")
            sb.append("  </configuration>\n")
        } else if (conf.targetJar != null) {
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
        if (shouldAutoDeploy) {
            tasks.findByName("runClient")?.dependsOn("deployArtifact")
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

        // 自动适配 Fabric Loom 项目：注入 runClient 运行配置
        if (project.plugins.hasPlugin("fabric-loom") || project.extensions.findByName("loom") != null) {
            try {
                val loom = project.extensions.findByName("loom")
                if (loom != null) {
                    val getRuns = loom.javaClass.getMethod("getRuns")
                    val runs = getRuns.invoke(loom) as? org.gradle.api.NamedDomainObjectContainer<*>
                    val client = runs?.findByName("client")
                    if (client != null) {
                        val conf = resolveRemoldConfig()
                        val vmArgMethod = client.javaClass.getMethod("vmArg", String::class.java)
                        for (arg in conf.jvmArgs) {
                            vmArgMethod.invoke(client, arg)
                        }
                        logger.lifecycle("[Remold] 已自动为 Fabric Loom 的 runClient 注入热重载探针参数！")
                    }
                }
            } catch (_: Throwable) { }
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
