// Remold Gradle Script Plugin (Kotlin DSL)
// Usage: apply(from = "https://raw.githubusercontent.com/I-hope1/Remold/v<version>/remold.gradle.kts")
//        本地开发: apply(from = file("../remold.gradle.kts"))
// 注意：远程引入务必用 **tag**（v0.2.0）而不是 main —— 脚本与 agent / api 必须同版本，
//       否则脚本按新接口拼参数、agent 还是旧版本，会在运行期才炸。完整引导块（本地多路径优先 +
//       版本缓存 + -Premold.script 覆盖）见 mindustry-mod-template / minecraft-fabric-template 的构建脚本。

import java.io.File
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.compile.JavaCompile

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
    var buildMode: String? = null
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
    val targetJar: File?,
    val buildMode: String = "gradle"
)

val remoldExt = project.extensions.create<RemoldExtension>("remold")
var cachedConfig: RemoldResolvedConfig? = null

project.plugins.withId("java") {
    // 确保编译输出包含完整调试符号（行号、源码、局部变量表），供 InitFix 参数回溯、Lambda 对齐与调试使用
    tasks.withType<JavaCompile>().configureEach {
        options.isDebug = true
        options.debugOptions.debugLevel = "source,lines,vars"
    }

    val sourceSets = project.extensions.getByType<SourceSetContainer>()
    val isWin = System.getProperty("os.name").lowercase().contains("win")
    val localRemoldDir = File(project.rootDir, ".gradle/remold")

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

    /** 解析运行用 JVM（可执行文件路径与 JRE 家目录）。 */
    fun resolveJbr(): Pair<String, String> {
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
                project.logger.warn("[Remold] Specified JBR path does not exist: $jbr. Falling back to project SDK JVM.")
            }
        }
        return Pair(execPath, execHome)
    }

    /** 解析部署目标目录。 */
    fun resolveDeployDir(): File? {
        val custom = remoldExt.deployDir
            ?: remoldExt.modsDir
            ?: project.findProperty("remold.deployDir")
            ?: project.findProperty("remold.modsDir")
            ?: project.findProperty("remoldDeployDir")
            ?: project.findProperty("remoldModsDir")
        return if (custom != null) project.file(custom) else null
    }

    /** 是否属于基于散装类路径（Classpath-based）驱动的模组开发生态（如 Minecraft Fabric / Quilt / NeoForge / Forge）。 */
    fun isClassBasedEnvironment(): Boolean {
        val pluginIds = listOf(
            "fabric-loom",
            "dev.architectury.loom",
            "org.quiltmc.loom",
            "net.neoforged.moddev",
            "net.neoforged.gradle.userdev",
            "net.minecraftforge.gradle",
            "gg.essential.multi-version"
        )
        for (id in pluginIds) {
            if (project.plugins.hasPlugin(id)) return true
        }
        val extNames = listOf("loom", "minecraft", "neoForge")
        for (ext in extNames) {
            if (project.extensions.findByName(ext) != null) return true
        }
        if (project.tasks.findByName("runClient") != null) {
            if (project.tasks.findByName("remapJar") != null || project.tasks.findByName("jar") != null) {
                return true
            }
        }
        return false
    }

    /** 是否自动执行 deployArtifact。 */
    fun shouldAutoDeploy(): Boolean {
        if (isClassBasedEnvironment()) {
            val explicitAutoDeploy = remoldExt.autoDeploy
                ?: project.findProperty("remold.autoDeploy")?.toString()?.toBoolean()
                ?: false
            if (explicitAutoDeploy) {
                project.logger.warn("[Remold] [WARN] 'autoDeploy = true' is ignored in Minecraft/Loom environment. In-dev runs use loose classes; outputting JARs to run/mods causes namespace crashes and bytecode rollbacks.")
            }
            return false
        }
        return remoldExt.autoDeploy
            ?: project.findProperty("remold.autoDeploy")?.toString()?.toBoolean()
            ?: (resolveDeployDir() != null)
    }

    fun downloadRemoteJar(urlStr: String, targetFile: File): Boolean {
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
        return ok
    }

    fun syncIntoLocalRemold(src: File?, dest: File): File {
        if (src == null || !src.exists()) return dest
        return try {
            if (src.canonicalPath == dest.canonicalPath) {
                src
            } else if (dest.exists() && dest.length() == src.length() && dest.lastModified() == src.lastModified()) {
                dest
            } else {
                dest.parentFile.mkdirs()
                java.nio.file.Files.copy(src.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                dest.setLastModified(src.lastModified())
                dest
            }
        } catch (_: Exception) {
            src
        }
    }

    /**
     * 统一的 Jar 本地查找、Gradle 缓存探测、detachedConfiguration 解析与远端 Releases 下载。
     */
    fun resolveOrDownloadJar(
        artifactLabel: String,
        ver: String?,
        candidates: List<File>,
        cachedJar: File?,
        mavenNotations: List<String>,
        downloadUrls: List<String>
    ): File? {
        val localFound = candidates.firstOrNull { it.exists() }
        if (localFound != null && localFound.exists()) {
            return localFound
        }
        if (cachedJar != null && cachedJar.exists() && cachedJar.length() > 1024) {
            return cachedJar
        }
        var resolvedJar: File? = null
        if (ver != null) {
            for (notation in mavenNotations) {
                try {
                    val detached = project.configurations.detachedConfiguration(
                        project.dependencies.create(notation)
                    )
                    detached.isTransitive = false
                    val resolved = detached.resolve()
                    if (resolved.isNotEmpty()) {
                        resolvedJar = resolved.first()
                        break
                    }
                } catch (_: Exception) { }
            }
        }
        if ((resolvedJar == null || !resolvedJar.exists()) && cachedJar != null && downloadUrls.isNotEmpty()) {
            project.logger.lifecycle("[Remold] Downloading $artifactLabel (version: $ver)...")
            cachedJar.parentFile?.mkdirs()
            for (urlStr in downloadUrls) {
                if (downloadRemoteJar(urlStr, cachedJar)) {
                    resolvedJar = cachedJar
                    project.logger.lifecycle("[Remold] $artifactLabel ready: ${cachedJar.absolutePath}")
                    break
                }
            }
        }
        return resolvedJar
    }

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

    /**
     * 是否属于 Minecraft Forge / NeoForge 生态：声明了对应 Gradle 插件或依赖。
     * 命中即默认挂载 plugin-forge（可用 -Premold.autoForge=false 关闭）。
     */
    fun usesForgeEcosystem(): Boolean {
        if (project.plugins.hasPlugin("net.minecraftforge.gradle")
            || project.plugins.hasPlugin("net.neoforged.moddev")
            || project.plugins.hasPlugin("net.neoforged.gradle.userdev")
            || project.extensions.findByName("neoForge") != null
            || project.extensions.findByName("minecraft") != null
        ) {
            return true
        }
        return try {
            project.configurations.any { cfg ->
                try {
                    cfg.dependencies.any { dep ->
                        dep.group == "net.minecraftforge" || dep.group == "net.neoforged" || dep.group?.startsWith("net.neoforged") == true
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
        cachedConfig?.let { return it }

        // 0. 解析目标 jar (若配置了 jar，进入纯净 JAR 模式)
        val jarProp = remoldExt.jar
            ?: project.findProperty("remold.jar")
            ?: project.findProperty("remoldJar")
        var targetJar: File? = null
        if (jarProp != null) {
            targetJar = project.file(jarProp)
            if (!targetJar.exists()) {
                project.logger.warn("[Remold] Notice: Target launch JAR does not exist yet: ${targetJar.absolutePath} (can be ignored if not running immediately)")
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
                throw GradleException("[Remold] Could not read Main-Class from MANIFEST.MF of ${targetJar.name}. Please specify it explicitly via remold { mainClass = \"...\" }.")
            } else if (targetJar != null) {
                main = "unknown.MainClass"
            } else if (project.plugins.hasPlugin("fabric-loom") || project.extensions.findByName("loom") != null) {
                main = "net.fabricmc.loader.impl.launch.knot.KnotClient"
            } else {
                throw GradleException("[Remold] Neither mainClass nor jar is configured. Please specify via remold { mainClass = \"...\" }, remold { jar = \"...\" }, or gradle.properties.")
            }
        }

        // 2. 解析运行用 JVM
        val (execPath, execHome) = resolveJbr()

        // 3. 解析版本与 agent jar
        val ver = remoldExt.version
            ?: (project.findProperty("remold.version") as? String)
            ?: (project.findProperty("remoldVersion") as? String)
            ?: (if (project.rootProject.hasProperty("remoldVersion")) project.rootProject.property("remoldVersion") as? String else null)

        var agentFile: File? = (remoldExt.agentJar ?: (project.findProperty("remold.agentJar") as? String))?.let { File(it) }
        val agentCandidates = mutableListOf<File>()
        if (agentFile != null) {
            agentCandidates.add(agentFile)
        }
        agentCandidates.addAll(listOf(
            project.file("libs/remold-agent.jar"),
            project.file("remold-agent.jar"),
            project.rootProject.file("core/build/libs/remold-agent.jar"),
            project.rootProject.file("../core/build/libs/remold-agent.jar"),
            File(localRemoldDir, "remold-agent.jar")
        ))

        val cachedAgentJar = ver?.let { File(project.gradle.gradleUserHomeDir, "caches/remold/remold-agent-$ver.jar") }
        val agentNotations = if (ver != null) listOf(
            "com.github.I-hope1.Remold:core:$ver",
            "com.github.I-hope1.Remold:core:$ver:all"
        ) else emptyList()
        val agentUrls = if (ver != null) listOf(
            "https://github.com/I-hope1/Remold/releases/download/v$ver/remold-agent.jar",
            "https://github.com/I-hope1/Remold/releases/download/$ver/remold-agent.jar"
        ) else emptyList()

        agentFile = resolveOrDownloadJar(
            "remold-agent.jar",
            ver,
            agentCandidates,
            cachedAgentJar,
            agentNotations,
            agentUrls
        )

        if (agentFile == null || !agentFile.exists()) {
            throw GradleException(
                "[Remold] remold-agent.jar was not found and could not be downloaded automatically (target version: $ver)!\n" +
                "Please check your network connection or specify a valid version in remold { version = \"...\" },\n" +
                "or specify a local file explicitly in remold { agentJar = \"/path/to/remold-agent.jar\" }."
            )
        }

        val safeAgent = syncIntoLocalRemold(agentFile, File(localRemoldDir, "remold-agent.jar"))

        // 4. 自动推导编译输出与资源目录并拼装监听路径
        val rawBuildMode = remoldExt.buildMode
            ?: project.findProperty("remold.buildMode")?.toString()
            ?: project.findProperty("remold.build_mode")?.toString()
            ?: project.findProperty("remold.builder")?.toString()
            ?: project.findProperty("remoldBuildMode")?.toString()

        val buildMode = if (!rawBuildMode.isNullOrBlank() && rawBuildMode != "null") {
            rawBuildMode
        } else {
            val gradleXml = File(project.rootDir, ".idea/gradle.xml")
            if (gradleXml.isFile && gradleXml.readText().contains("name=\"delegatedBuild\" value=\"false\"")) {
                "idea"
            } else {
                "auto"
            }
        }
        val normalizedBuildMode = buildMode.trim().lowercase()

        val classesDirs = sourceSets["main"].output.classesDirs.files
        val resourceDirs = sourceSets["main"].resources.srcDirs
        val assetsDir = File(project.projectDir, "assets")
        val outResourceDir = sourceSets["main"].output.resourcesDir

        val allCandidateDirs = mutableListOf<File>()
        if (normalizedBuildMode == "idea") {
            val ideaOutDir = File(project.projectDir, "out/production")
            val subs = if (ideaOutDir.isDirectory) ideaOutDir.listFiles { f -> f.isDirectory } else null
            if (!subs.isNullOrEmpty()) {
                allCandidateDirs.addAll(subs)
            } else {
                allCandidateDirs.add(ideaOutDir)
            }
        } else if (normalizedBuildMode == "auto") {
            allCandidateDirs.addAll(classesDirs)
            val ideaOutDir = File(project.projectDir, "out/production")
            if (ideaOutDir.isDirectory) {
                val subs = ideaOutDir.listFiles { f -> f.isDirectory }
                if (!subs.isNullOrEmpty()) {
                    allCandidateDirs.addAll(subs)
                } else {
                    allCandidateDirs.add(ideaOutDir)
                }
            }
        } else {
            allCandidateDirs.addAll(classesDirs)
        }

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
        args.add("-Dremold.build_mode=$normalizedBuildMode")
        args.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED")
        args.add("--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED")
        args.add("--add-exports=java.base/jdk.internal.loader=ALL-UNNAMED")
        args.add("--enable-native-access=ALL-UNNAMED")

        if (remoldExt.debug || "true".equals(System.getProperty("remold.debug"), ignoreCase = true)) {
            val port = remoldExt.debugPort
            args.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$port")
            project.logger.lifecycle("[Remold] JDWP debugging enabled, listening on localhost: 127.0.0.1:$port")
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
        val autoMixin = (project.findProperty("remold.autoMixin") as? String)?.toBoolean() ?: true
        if (autoMixin && requestedPlugins.none { it.equals("mixin", ignoreCase = true) } && usesMixinEcosystem()) {
            requestedPlugins.add("mixin")
        }
        val autoForge = (project.findProperty("remold.autoForge") as? String)?.toBoolean() ?: true
        if (autoForge && requestedPlugins.none { it.equals("forge", ignoreCase = true) || it.equals("neoforge", ignoreCase = true) } && usesForgeEcosystem()) {
            requestedPlugins.add("forge")
        }
        val distinctPlugins = requestedPlugins.distinct()

        val pluginJars = mutableListOf<File>()
        for (explicit in remoldExt.pluginJars) {
            val f = project.file(explicit)
            if (f.exists()) {
                pluginJars.add(syncIntoLocalRemold(f, File(localRemoldDir, "plugins/${f.name}")))
            } else {
                project.logger.warn("[Remold] Specified plugin jar does not exist: ${f.absolutePath}")
            }
        }
        if (distinctPlugins.isNotEmpty()) {
            for (pluginName in distinctPlugins) {
                val targetModule = if (pluginName.equals("neoforge", ignoreCase = true)) "forge" else pluginName
                val alreadyProvided = pluginJars.any { it.name.contains("remold-plugin-$targetModule") || it.name.contains("plugin-$targetModule") }
                if (alreadyProvided) {
                    continue
                }
                val candidates = mutableListOf<File>()
                if (ver != null) {
                    candidates.add(project.rootProject.file("plugin-$targetModule/build/libs/remold-plugin-$targetModule-$ver.jar"))
                }
                candidates.add(project.rootProject.file("plugin-$targetModule/build/libs/remold-plugin-$targetModule.jar"))
                if (ver != null) {
                    candidates.add(project.rootProject.file("../plugin-$targetModule/build/libs/remold-plugin-$targetModule-$ver.jar"))
                }
                candidates.add(project.rootProject.file("../plugin-$targetModule/build/libs/remold-plugin-$targetModule.jar"))
                try {
                    project.rootDir.parentFile?.listFiles()?.forEach { sibling ->
                        if (sibling.isDirectory) {
                            if (ver != null) {
                                candidates.add(File(sibling, "plugin-$targetModule/build/libs/remold-plugin-$targetModule-$ver.jar"))
                            }
                            candidates.add(File(sibling, "plugin-$targetModule/build/libs/remold-plugin-$targetModule.jar"))
                        }
                    }
                } catch (_: Throwable) { }
                if (ver != null) {
                    val m2Root = File(System.getProperty("user.home"), ".m2/repository")
                    listOf("io/github/i-hope1/remold", "com/github/I-hope1/Remold").forEach { groupPath ->
                        candidates.add(File(m2Root, "$groupPath/plugin-$targetModule/$ver/plugin-$targetModule-$ver.jar"))
                    }
                }

                val cachedPluginJar = ver?.let { File(project.gradle.gradleUserHomeDir, "caches/remold/remold-plugin-$targetModule-$ver.jar") }
                val pluginNotations = if (ver != null) listOf("com.github.I-hope1.Remold:plugin-$targetModule:$ver") else emptyList()
                val pluginUrls = if (ver != null) listOf(
                    "https://github.com/I-hope1/Remold/releases/download/v$ver/remold-plugin-$targetModule-$ver.jar",
                    "https://github.com/I-hope1/Remold/releases/download/v$ver/remold-plugin-$targetModule.jar",
                    "https://github.com/I-hope1/Remold/releases/download/$ver/remold-plugin-$targetModule-$ver.jar",
                    "https://github.com/I-hope1/Remold/releases/download/$ver/remold-plugin-$targetModule.jar"
                ) else emptyList()

                val resolvedPluginJar = resolveOrDownloadJar(
                    "remold-plugin-$targetModule.jar",
                    ver,
                    candidates,
                    cachedPluginJar,
                    pluginNotations,
                    pluginUrls
                )

                if (resolvedPluginJar != null && resolvedPluginJar.exists()) {
                    pluginJars.add(syncIntoLocalRemold(resolvedPluginJar, File(localRemoldDir, "plugins/${resolvedPluginJar.name}")))
                } else {
                    project.logger.warn("[Remold] Plugin remold-plugin-$targetModule jar not found; can be ignored if embedded or not required.")
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

        val resolved = RemoldResolvedConfig(
            mainClass = main,
            executable = execPath,
            executableHome = execHome,
            agentFile = safeAgent,
            allWatchDirs = allWatchDirs,
            watchPath = watchPath,
            jvmArgs = args,
            appArgs = appArgs,
            runtimeClasspath = runtimeClasspath,
            targetJar = targetJar,
            buildMode = normalizedBuildMode
        )
        cachedConfig = resolved
        return resolved
    }

    tasks.register<JavaExec>("runRemold") {
        group = "application"
        description = "Runs the application with Remold Java Agent & JBR DCEVM (blocking foreground)"

        classpath = sourceSets["main"].runtimeClasspath

        doFirst {
            val conf = resolveRemoldConfig()
            if (conf.targetJar != null && !conf.targetJar.exists()) {
                throw GradleException("[Remold] Target launch JAR does not exist; cannot launch application: ${conf.targetJar.absolutePath}")
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

            logger.lifecycle("[Remold] Launch configuration assembled:")
            if (conf.targetJar != null) {
                logger.lifecycle("  - Target JAR: ${conf.targetJar.absolutePath}")
            }
            logger.lifecycle("  - Main class: ${conf.mainClass}")
            logger.lifecycle("  - Agent: ${conf.agentFile.absolutePath}")
            logger.lifecycle("  - Watch directories (${conf.allWatchDirs.size}): ${conf.watchPath}")
            if (conf.appArgs.isNotEmpty()) {
                logger.lifecycle("  - App arguments: ${conf.appArgs.joinToString(" ")}")
            }
            if (remoldExt.fileShakeMs != null) {
                logger.lifecycle("  - File Shake: ${remoldExt.fileShakeMs}ms")
            }
            if (conf.executable != "java") {
                logger.lifecycle("  - JBR: ${conf.executable}")
            }
        }
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
                ?: throw GradleException("[Remold] autoDeploy is enabled but deploy directory is not configured! Please specify via remold { deployDir = \"...\" } or gradle.properties (remold.deployDir=...).")
        })
        doLast {
            logger.lifecycle("[Remold] Artifact deployed to: ${resolveDeployDir()?.absolutePath}")
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
            if (conf.buildMode == "idea") {
                sb.append("      <option name=\"Make\" enabled=\"true\" />\n")
            } else if (shouldAutoDeploy()) {
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
            if (conf.buildMode == "idea") {
                sb.append("      <option name=\"Make\" enabled=\"true\" />\n")
            } else if (shouldAutoDeploy()) {
                sb.append("      <option name=\"Gradle.BeforeRunTask\" enabled=\"true\" tasks=\"deployArtifact\" externalProjectPath=\"\$PROJECT_DIR\$\" />\n")
            } else {
                sb.append("      <option name=\"Gradle.BeforeRunTask\" enabled=\"true\" tasks=\"classes\" externalProjectPath=\"\$PROJECT_DIR\$\" />\n")
            }
            sb.append("    </method>\n")
            sb.append("  </configuration>\n")
        }
        sb.append("</component>\n")

        xmlFile.writeText(sb.toString())

        // 自动适配 Fabric Loom：若存在 Loom 生成的 Minecraft_Client.xml / Minecraft_Server.xml，自动同步 Remold 插件、探针参数与运行前 deployArtifact
        try {
            val loomRunConfigs = listOf("Minecraft_Client.xml", "Minecraft_Server.xml")
            for (configName in loomRunConfigs) {
                val clientXml = File(ideaRunDir, configName)
                if (!clientXml.exists()) continue

                var content = clientXml.readText()
                var modified = false

                // 1. 同步 VM_PARAMETERS
                val regex = Regex("""<option name="VM_PARAMETERS" value="([^"]*)"\s*/>""")
                val match = regex.find(content)
                if (match != null) {
                    val existing = match.groupValues[1]
                    var updated = existing
                    val agentRegex = Regex("""(?:^|\s)-javaagent:[^\s=]+remold-agent\.jar[^\s]*""")
                    for (arg in conf.jvmArgs) {
                        val portableArg = makePortable(arg)
                        if (arg.startsWith("-javaagent:") && arg.contains("remold-agent.jar")) {
                            if (agentRegex.containsMatchIn(updated)) {
                                updated = agentRegex.replace(updated, java.util.regex.Matcher.quoteReplacement(" $portableArg")).trim()
                            } else if (!updated.contains(portableArg)) {
                                updated = if (updated.isNotBlank()) "$updated $portableArg" else portableArg
                            }
                        } else if (arg.startsWith("-Dremold.plugin.jars=")) {
                            val p = Regex("""(?:^|\s)-Dremold\.plugin\.jars=[^\s]*""")
                            updated = p.replace(updated, "").trim()
                            updated = if (updated.isNotBlank()) "$updated $portableArg" else portableArg
                        } else if (arg.startsWith("-Dremold.plugins=")) {
                            val p = Regex("""(?:^|\s)-Dremold\.plugins=[^\s]*""")
                            updated = p.replace(updated, "").trim()
                            updated = if (updated.isNotBlank()) "$updated $portableArg" else portableArg
                        } else if (arg.startsWith("-Dremold.build_mode=")) {
                            val p = Regex("""(?:^|\s)-Dremold\.build_mode=[^\s]*""")
                            updated = p.replace(updated, "").trim()
                            updated = if (updated.isNotBlank()) "$updated $portableArg" else portableArg
                        } else if (!updated.contains(portableArg) && !updated.contains(arg)) {
                            updated = if (updated.isNotBlank()) "$updated $portableArg" else portableArg
                        }
                    }
                    if (updated != existing) {
                        content = content.replace(match.value, "<option name=\"VM_PARAMETERS\" value=\"${xmlEscape(updated)}\"/>")
                        modified = true
                    }
                }

                val expectedBeforeRunBlock = "    <method v=\"2\">\n      <option name=\"Make\" enabled=\"true\" />\n    </method>"
                val methodRegex = Regex("""(?s)<method v="2">.*?</method>|<method v="2"\s*/>""")

                if (content.contains("tasks=\"deployArtifact\"") || content.contains("tasks=\":classes\"") || content.contains("tasks=\"classes\"") || !content.contains("<option name=\"Make\" enabled=\"true\" />")) {
                    if (methodRegex.containsMatchIn(content)) {
                        content = methodRegex.replace(content, java.util.regex.Matcher.quoteReplacement(expectedBeforeRunBlock))
                        modified = true
                    }
                }

                if (modified) {
                    clientXml.writeText(content)
                    logger.lifecycle("[Remold] Automatically synchronized Remold parameters into $configName")
                }
            }
        } catch (_: Throwable) { }

        xmlFile
    }

    project.afterEvaluate {
        val autoDeploy = shouldAutoDeploy()

        val (execPath, _) = resolveJbr()
        if (execPath != "java") {
            tasks.named<JavaExec>("runRemold") {
                javaLauncher.set(null as org.gradle.jvm.toolchain.JavaLauncher?)
            }
        }

        listOf("runRemold", "startRemold", "generateRemoldScript", "generateIdeaRunConfig").forEach { name ->
            tasks.named(name) {
                if (autoDeploy) dependsOn("deployArtifact")
                if (remoldExt.dependsOn.isNotEmpty()) dependsOn(remoldExt.dependsOn)
            }
        }
        if (autoDeploy) {
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
                        logger.lifecycle("[Remold] IntelliJ IDEA run configuration ready: ${out.absolutePath}")
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
                        logger.lifecycle("[Remold] Injected hot-reload agent parameters into Fabric Loom runClient!")
                    }
                }
            } catch (_: Throwable) { }
        }

        // 自动适配 NeoForge ModDevGradle 项目：注入 client 运行配置
        try {
            val runsExt = project.extensions.findByName("runs")
                ?: (project.extensions.findByName("neoForge") as? org.gradle.api.plugins.ExtensionAware)?.extensions?.findByName("runs")
            if (runsExt != null) {
                val getByName = runsExt.javaClass.getMethod("findByName", String::class.java)
                val clientRun = getByName.invoke(runsExt, "client")
                if (clientRun != null) {
                    val conf = resolveRemoldConfig()
                    try {
                        val jvmArgsProp = clientRun.javaClass.getMethod("getJvmArguments").invoke(clientRun)
                        if (jvmArgsProp is org.gradle.api.provider.ListProperty<*>) {
                            @Suppress("UNCHECKED_CAST")
                            (jvmArgsProp as org.gradle.api.provider.ListProperty<String>).addAll(conf.jvmArgs)
                            logger.lifecycle("[Remold] Injected hot-reload agent parameters into NeoForge ModDevGradle runClient!")
                        }
                    } catch (_: Throwable) {
                        try {
                            val jvmArgMethod = clientRun.javaClass.getMethod("jvmArg", String::class.java)
                            for (arg in conf.jvmArgs) {
                                jvmArgMethod.invoke(clientRun, arg)
                            }
                            logger.lifecycle("[Remold] Injected hot-reload agent parameters into NeoForge runClient!")
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}

        // 自动适配 ForgeGradle 项目：注入 minecraft.runs.client 运行配置
        try {
            val mcExt = project.extensions.findByName("minecraft")
            if (mcExt != null) {
                val getRuns = mcExt.javaClass.getMethod("getRuns")
                val runs = getRuns.invoke(mcExt) as? org.gradle.api.NamedDomainObjectContainer<*>
                val client = runs?.findByName("client")
                if (client != null) {
                    val conf = resolveRemoldConfig()
                    val jvmArgMethod = client.javaClass.getMethod("jvmArg", String::class.java)
                    for (arg in conf.jvmArgs) {
                        jvmArgMethod.invoke(client, arg)
                    }
                    logger.lifecycle("[Remold] Injected hot-reload agent parameters into ForgeGradle runClient!")
                }
            }
        } catch (_: Throwable) {}
    }

    tasks.register("generateIdeaRunConfig") {
        group = "application"
        description = "Generates IntelliJ IDEA run configuration into .idea/runConfigurations/Run_with_Remold.xml"
        dependsOn("classes")

        doLast {
            val xmlFile = generateIdeaRunConfigInternal()
            if (xmlFile != null) {
                logger.lifecycle("[Remold] Generated IntelliJ IDEA run configuration: ${xmlFile.absolutePath}")
                logger.lifecycle("[Remold] Open this project in IDEA to launch directly with the run/debug configuration.")
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
                throw GradleException("[Remold] Target launch JAR does not exist; cannot launch application: ${conf.targetJar.absolutePath}")
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

            logger.lifecycle("[Remold] Generated native launch script:")
            logger.lifecycle("  - Windows: ${batFile.absolutePath}")
            logger.lifecycle("  - Linux/macOS: ${shFile.absolutePath}")
            logger.lifecycle("  - Args file: ${argsFile.absolutePath}")
            logger.lifecycle("[Remold] Run the script directly to launch the application without Gradle daemon overhead.")
        }
    }

    tasks.register("startRemold") {
        group = "application"
        description = "Starts the application in a detached process so Gradle Daemon is immediately freed"
        dependsOn("generateRemoldScript")

        doLast {
            val conf = resolveRemoldConfig()
            if (conf.targetJar != null && !conf.targetJar.exists()) {
                throw GradleException("[Remold] Target launch JAR does not exist; cannot launch application: ${conf.targetJar.absolutePath}")
            }
            val buildDir = project.layout.buildDirectory.asFile.get()
            val argsFile = File(buildDir, "remold/remold.args")
            val isWin = System.getProperty("os.name").lowercase().contains("win")

            if (isWin) {
                ProcessBuilder("cmd", "/c", "start", "\"Remold [${project.name}]\"", conf.executable, "@${argsFile.absolutePath}")
                    .directory(project.projectDir)
                    .start()
                logger.lifecycle("[Remold] Application launched in a separate console window.")
            } else {
                val logFile = File(buildDir, "remold/remold.log")
                ProcessBuilder(conf.executable, "@${argsFile.absolutePath}")
                    .directory(project.projectDir)
                    .redirectOutput(logFile)
                    .redirectErrorStream(true)
                    .start()
                logger.lifecycle("[Remold] Application launched in background. Output log: ${logFile.absolutePath}")
            }
            logger.lifecycle("[Remold] Gradle Daemon released (IDLE). Run ./gradlew compileJava in terminal anytime to trigger hot-reload.")
        }
    }
}
