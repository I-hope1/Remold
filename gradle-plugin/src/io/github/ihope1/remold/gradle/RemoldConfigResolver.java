package io.github.ihope1.remold.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * Resolves and caches runtime configurations, JBR paths, agents, plugins, and watch paths.
 */
public final class RemoldConfigResolver {

    private RemoldResolvedConfig cachedConfig;

    public synchronized RemoldResolvedConfig resolve(Project project, RemoldExtension ext) {
        if (cachedConfig != null) {
            return cachedConfig;
        }

        // 0. Resolve target JAR (if configured, enters pure JAR mode)
        Object jarProp = ext.getJar();
        if (jarProp == null) jarProp = project.findProperty("remold.jar");
        if (jarProp == null) jarProp = project.findProperty("remoldJar");

        File targetJar = null;
        if (jarProp != null) {
            targetJar = project.file(jarProp);
            if (!targetJar.exists()) {
                project.getLogger().warn("[Remold] Notice: Target launch JAR does not exist yet: " + targetJar.getAbsolutePath() + " (can be ignored if not running immediately)");
            }
        }

        // 1. Resolve mainClass
        String main = ext.getMainClass();
        if (main == null && project.findProperty("remold.mainClass") != null) {
            main = String.valueOf(project.findProperty("remold.mainClass"));
        }
        if (main == null && project.findProperty("remoldMainClass") != null) {
            main = String.valueOf(project.findProperty("remoldMainClass"));
        }
        if (main == null) {
            JavaApplication app = (JavaApplication) project.getExtensions().findByName("application");
            if (app != null && app.getMainClass().isPresent()) {
                main = app.getMainClass().get();
            }
        }
        if (main == null && project.findProperty("mainClassName") != null) {
            main = String.valueOf(project.findProperty("mainClassName"));
        }

        if (main == null && targetJar != null && targetJar.exists()) {
            try (JarFile jf = new JarFile(targetJar)) {
                if (jf.getManifest() != null && jf.getManifest().getMainAttributes() != null) {
                    main = jf.getManifest().getMainAttributes().getValue("Main-Class");
                }
            } catch (Exception ignored) {
            }
        }

        if (main == null) {
            if (targetJar != null && targetJar.exists()) {
                throw new GradleException("[Remold] Could not read Main-Class from MANIFEST.MF of " + targetJar.getName() + ". Please specify it explicitly via remold { mainClass = \"...\" }.");
            } else if (targetJar != null) {
                main = "unknown.MainClass";
            } else if (project.getPlugins().hasPlugin("fabric-loom") || project.getExtensions().findByName("loom") != null) {
                main = "net.fabricmc.loader.impl.launch.knot.KnotClient";
            } else {
                throw new GradleException("[Remold] Neither mainClass nor jar is configured. Please specify via remold { mainClass = \"...\" }, remold { jar = \"...\" }, or gradle.properties.");
            }
        }

        // 2. Resolve executable and JRE home
        String[] execAndHome = resolveExecutableAndHome(project, ext);
        String execPath = execAndHome[0];
        String execHome = execAndHome[1];

        // 3. Resolve version and agent jar
        File localRemoldDir = new File(project.getRootDir(), ".gradle/remold");
        String ver = ext.getVersion();
        if (ver == null && project.findProperty("remold.version") != null) {
            ver = String.valueOf(project.findProperty("remold.version"));
        }
        if (ver == null && project.findProperty("remoldVersion") != null) {
            ver = String.valueOf(project.findProperty("remoldVersion"));
        }
        if (ver == null && project.getRootProject().hasProperty("remoldVersion")) {
            ver = String.valueOf(project.getRootProject().property("remoldVersion"));
        }

        File agentFile = null;
        String explicitAgent = ext.getAgentJar();
        if (explicitAgent == null && project.findProperty("remold.agentJar") != null) {
            explicitAgent = String.valueOf(project.findProperty("remold.agentJar"));
        }
        if (explicitAgent != null) {
            agentFile = new File(explicitAgent);
        }

        if (agentFile == null || !agentFile.exists()) {
            List<File> candidates = Arrays.asList(
                    project.file("libs/remold-agent.jar"),
                    project.file("remold-agent.jar"),
                    project.getRootProject().file("core/build/libs/remold-agent.jar"),
                    project.getRootProject().file("../core/build/libs/remold-agent.jar"),
                    new File(localRemoldDir, "remold-agent.jar")
            );
            for (File c : candidates) {
                if (c.exists()) {
                    agentFile = c;
                    break;
                }
            }
        }

        if (agentFile == null || !agentFile.exists()) {
            if (ver == null) {
                throw new GradleException("[Remold] Remold version is not configured! Please specify via remold { version = \"...\" } or gradle.properties (remold.version=...).");
            }
            File cacheDir = new File(project.getGradle().getGradleUserHomeDir(), "caches/remold");
            File cachedJar = new File(cacheDir, "remold-agent-" + ver + ".jar");

            if (cachedJar.exists() && cachedJar.length() > 1024) {
                agentFile = cachedJar;
            } else {
                agentFile = resolveViaDetachedConfiguration(project, "com.github.I-hope1.Remold:core:" + ver);
                if (agentFile == null || !agentFile.exists()) {
                    agentFile = resolveViaDetachedConfiguration(project, "com.github.I-hope1.Remold:core:" + ver + ":all");
                }
                if (agentFile == null || !agentFile.exists()) {
                    project.getLogger().lifecycle("[Remold] Local agent jar not found; downloading remold-agent.jar (version: " + ver + ")...");
                    List<String> downloadUrls = Arrays.asList(
                            "https://github.com/I-hope1/Remold/releases/download/v" + ver + "/remold-agent.jar",
                            "https://github.com/I-hope1/Remold/releases/download/" + ver + "/remold-agent.jar"
                    );
                    cacheDir.mkdirs();
                    for (String url : downloadUrls) {
                        if (downloadRemoteJar(url, cachedJar)) {
                            agentFile = cachedJar;
                            project.getLogger().lifecycle("[Remold] Agent jar ready: " + cachedJar.getAbsolutePath());
                            break;
                        }
                    }
                }
            }
        }

        if (agentFile == null || !agentFile.exists()) {
            throw new GradleException(
                    "[Remold] remold-agent.jar was not found and could not be downloaded automatically (target version: " + ver + ")!\n" +
                    "Please check your network connection or specify a valid version in remold { version = \"...\" },\n" +
                    "or specify a local file explicitly in remold { agentJar = \"/path/to/remold-agent.jar\" }."
            );
        }

        File safeAgent = syncIntoLocalRemold(agentFile, new File(localRemoldDir, "remold-agent.jar"));

        // 4. Derive compilation outputs and resources watch paths
        String buildMode = resolveBuildMode(project, ext);
        SourceSetContainer sourceSets = (SourceSetContainer) project.getExtensions().getByName("sourceSets");
        SourceSet mainSourceSet = sourceSets.getByName("main");
        Set<File> classesDirs = mainSourceSet.getOutput().getClassesDirs().getFiles();
        Set<File> resourceDirs = mainSourceSet.getResources().getSrcDirs();
        File assetsDir = new File(project.getProjectDir(), "assets");
        File outResourceDir = mainSourceSet.getOutput().getResourcesDir();

        Set<File> allCandidateDirs = new LinkedHashSet<>();
        if ("idea".equals(buildMode)) {
            File ideaOutDir = new File(project.getProjectDir(), "out/production");
            boolean foundModuleOut = false;
            if (ideaOutDir.isDirectory()) {
                File[] subs = ideaOutDir.listFiles(File::isDirectory);
                if (subs != null) {
                    for (File sub : subs) {
                        allCandidateDirs.add(sub);
                        foundModuleOut = true;
                    }
                }
            }
            if (!foundModuleOut) {
                allCandidateDirs.add(ideaOutDir);
            }
        } else if ("auto".equals(buildMode)) {
            allCandidateDirs.addAll(classesDirs);
            File ideaOutDir = new File(project.getProjectDir(), "out/production");
            if (ideaOutDir.isDirectory()) {
                File[] subs = ideaOutDir.listFiles(File::isDirectory);
                boolean foundSub = false;
                if (subs != null) {
                    for (File sub : subs) {
                        allCandidateDirs.add(sub);
                        foundSub = true;
                    }
                }
                if (!foundSub) {
                    allCandidateDirs.add(ideaOutDir);
                }
            }
        } else {
            allCandidateDirs.addAll(classesDirs);
        }

        allCandidateDirs.addAll(resourceDirs);
        if (assetsDir.exists()) allCandidateDirs.add(assetsDir);
        if (outResourceDir != null && outResourceDir.exists()) allCandidateDirs.add(outResourceDir);
        // watchDirs may arrive as File or as a path string (script-plugin syntax); project.file handles both.
        for (Object watchDir : ext.getWatchDirs()) {
            if (watchDir != null) allCandidateDirs.add(project.file(watchDir));
        }

        List<File> allWatchDirs = new ArrayList<>();
        for (File dir : allCandidateDirs) {
            if (dir.exists() && !allWatchDirs.contains(dir)) {
                allWatchDirs.add(dir);
            }
        }

        StringBuilder watchPathBuilder = new StringBuilder();
        for (File dir : allWatchDirs) {
            if (watchPathBuilder.length() > 0) watchPathBuilder.append(File.pathSeparator);
            watchPathBuilder.append(dir.getAbsolutePath());
        }
        String watchPath = watchPathBuilder.toString();

        // 5. Assemble JVM arguments
        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.add("-XX:+IgnoreUnrecognizedVMOptions");
        jvmArgs.add("-XX:+AllowEnhancedClassRedefinition");
        jvmArgs.add("-javaagent:" + safeAgent.getAbsolutePath() + "=" + watchPath);
        // 不再注入 --add-exports / --add-opens：agent 在 premain 与插件初始化时用
        // Instrumentation.redefineModule（+ EVERYONE_MODULE 兜底）自举打开 java.base 的 9 个内部包，
        // 见 core/src/remold/ModuleOpener。这里只保留 Java 24+ 对受限本地方法（JVMTI 原生库）的授权，
        // 旧 JDK 会以 -XX:+IgnoreUnrecognizedVMOptions 忽略它。
        jvmArgs.add("--enable-native-access=ALL-UNNAMED");

        if (ext.isDebug() || "true".equalsIgnoreCase(System.getProperty("remold.debug"))) {
            int port = ext.getDebugPort();
            jvmArgs.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:" + port);
            project.getLogger().lifecycle("[Remold] JDWP debugging enabled, listening on localhost: 127.0.0.1:" + port);
        }

        jvmArgs.addAll(ext.getExtraJvmArgs());
        jvmArgs.add("-Dremold.build_mode=" + buildMode);
        if (ext.getFileShakeMs() != null) {
            jvmArgs.add("-Dremold.agent.file_shake_ms=" + ext.getFileShakeMs());
        }
        if (ext.getAssetShakeMs() != null) {
            jvmArgs.add("-Dremold.agent.asset_shake_ms=" + ext.getAssetShakeMs());
        }

        if (project.getPlugins().hasPlugin("fabric-loom") || project.getExtensions().findByName("loom") != null || usesMixinEcosystem(project)) {
            File ideaClasses = new File(project.getProjectDir(), "out/production/classes");
            File ideaResources = new File(project.getProjectDir(), "out/production/resources");
            List<String> groupsList = new ArrayList<>();
            groupsList.add(ideaClasses.getAbsolutePath() + File.pathSeparator + ideaResources.getAbsolutePath());
            if (!classesDirs.isEmpty() && outResourceDir != null) {
                StringBuilder grp = new StringBuilder();
                for (File cd : classesDirs) {
                    if (grp.length() > 0) grp.append(File.pathSeparator);
                    grp.append(cd.getAbsolutePath());
                }
                grp.append(File.pathSeparator).append(outResourceDir.getAbsolutePath());
                groupsList.add(grp.toString());
            }
            String groupSep = File.pathSeparator + File.pathSeparator;
            jvmArgs.add("-Dfabric.classPathGroups=" + String.join(groupSep, groupsList));
        }

        // 6. Resolve plugins (e.g., arc, mixin, forge)
        List<String> requestedPlugins = new ArrayList<>();
        for (String p : ext.getPlugins()) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty() && !requestedPlugins.contains(trimmed)) {
                requestedPlugins.add(trimmed);
            }
        }
        Object pluginProps = project.findProperty("remold.plugins");
        if (pluginProps == null) pluginProps = project.findProperty("remoldPlugins");
        if (pluginProps != null && !String.valueOf(pluginProps).trim().isEmpty()) {
            for (String p : String.valueOf(pluginProps).split("[,;]")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty() && !requestedPlugins.contains(trimmed)) {
                    requestedPlugins.add(trimmed);
                }
            }
        }

        boolean autoMixin = project.findProperty("remold.autoMixin") == null
                || Boolean.parseBoolean(String.valueOf(project.findProperty("remold.autoMixin")));
        if (autoMixin && !containsIgnoreCase(requestedPlugins, "mixin") && usesMixinEcosystem(project)) {
            requestedPlugins.add("mixin");
        }

        boolean autoForge = project.findProperty("remold.autoForge") == null
                || Boolean.parseBoolean(String.valueOf(project.findProperty("remold.autoForge")));
        if (autoForge && !containsIgnoreCase(requestedPlugins, "forge") && !containsIgnoreCase(requestedPlugins, "neoforge") && usesForgeEcosystem(project)) {
            requestedPlugins.add("forge");
        }

        boolean autoFabric = project.findProperty("remold.autoFabric") == null
                || Boolean.parseBoolean(String.valueOf(project.findProperty("remold.autoFabric")));
        if (autoFabric && !containsIgnoreCase(requestedPlugins, "fabric") && usesFabricEcosystem(project)) {
            requestedPlugins.add("fabric");
        }

        List<File> pluginJars = new ArrayList<>();
        for (Object explicit : ext.getPluginJars()) {
            File f = project.file(explicit);
            if (f.exists()) {
                pluginJars.add(syncIntoLocalRemold(f, new File(localRemoldDir, "plugins/" + f.getName())));
            } else {
                project.getLogger().warn("[Remold] Specified plugin jar does not exist: " + f.getAbsolutePath());
            }
        }

        if (!requestedPlugins.isEmpty()) {
            for (String pluginName : requestedPlugins) {
                String targetModule = pluginName.equalsIgnoreCase("neoforge") ? "forge" : pluginName;
                boolean alreadyProvided = false;
                for (File pj : pluginJars) {
                    if (pj.getName().contains("remold-plugin-" + targetModule) || pj.getName().contains("plugin-" + targetModule)) {
                        alreadyProvided = true;
                        break;
                    }
                }
                if (alreadyProvided) continue;

                File resolvedPluginJar = resolvePluginJar(project, targetModule, ver);
                if (resolvedPluginJar != null && resolvedPluginJar.exists()) {
                    pluginJars.add(syncIntoLocalRemold(resolvedPluginJar, new File(localRemoldDir, "plugins/" + resolvedPluginJar.getName())));
                } else {
                    project.getLogger().warn("[Remold] Plugin remold-plugin-" + targetModule + " jar not found; can be ignored if embedded or not required.");
                }
            }

            jvmArgs.add("-Dremold.plugins=" + String.join(",", requestedPlugins));
            if (!pluginJars.isEmpty()) {
                StringBuilder pjBuilder = new StringBuilder();
                for (File pj : pluginJars) {
                    if (pjBuilder.length() > 0) pjBuilder.append(File.pathSeparator);
                    pjBuilder.append(pj.getAbsolutePath());
                }
                jvmArgs.add("-Dremold.plugin.jars=" + pjBuilder.toString());
            }
        }

        FileCollection runtimeClasspath = targetJar != null
                ? project.files(targetJar)
                : mainSourceSet.getRuntimeClasspath();

        List<String> appArgs = new ArrayList<>();
        for (Object arg : ext.getArgs()) {
            if (arg != null) appArgs.add(arg.toString());
        }

        cachedConfig = new RemoldResolvedConfig(
                main,
                execPath,
                execHome,
                safeAgent,
                allWatchDirs,
                watchPath,
                jvmArgs,
                appArgs,
                runtimeClasspath,
                targetJar,
                buildMode
        );
        return cachedConfig;
    }

    public synchronized void clearCache() {
        this.cachedConfig = null;
    }

    public static String[] resolveExecutableAndHome(Project project, RemoldExtension ext) {
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        String execPath = new File(System.getProperty("java.home"), isWin ? "bin/java.exe" : "bin/java").getAbsolutePath();
        String execHome = toJreHome(System.getProperty("java.home"), isWin);
        if (!new File(execPath).exists()) {
            execPath = "java";
            execHome = "";
        }

        String jbr = ext.getJbrPath();
        if (jbr == null && project.findProperty("remold.jbr") != null) {
            jbr = String.valueOf(project.findProperty("remold.jbr"));
        }
        if (jbr == null && project.findProperty("jbr.home") != null) {
            jbr = String.valueOf(project.findProperty("jbr.home"));
        }
        if (jbr == null) {
            jbr = System.getenv("JBR_HOME");
        }
        if (jbr == null) {
            jbr = System.getProperty("jbr.home");
        }

        if (jbr != null && !jbr.trim().isEmpty()) {
            File jbrDir = new File(jbr);
            File javaBin = jbrDir.isFile() ? jbrDir : new File(jbrDir, isWin ? "bin/java.exe" : "bin/java");
            if (javaBin.exists()) {
                execPath = javaBin.getAbsolutePath();
                execHome = toJreHome(javaBin.getAbsolutePath(), isWin);
            } else {
                project.getLogger().warn("[Remold] Specified JBR path does not exist: " + jbr + ". Falling back to project SDK JVM.");
            }
        }
        return new String[]{execPath, execHome};
    }

    public static String toJreHome(String path, boolean isWin) {
        if (path == null || path.trim().isEmpty()) return "";
        File f = new File(path);
        if (f.isFile() || f.getName().equalsIgnoreCase("java") || f.getName().equalsIgnoreCase("java.exe")) {
            File bin = f.getParentFile();
            if (bin == null || !bin.getName().equalsIgnoreCase("bin")) return "";
            f = bin.getParentFile();
            if (f == null) return "";
        }
        if (f.getName().equalsIgnoreCase("jre")) {
            File parent = f.getParentFile();
            if (parent != null && new File(parent, isWin ? "bin/java.exe" : "bin/java").exists()) {
                f = parent;
            }
        }
        return f.isDirectory() ? f.getAbsolutePath() : "";
    }

    public static String resolveBuildMode(Project project, RemoldExtension ext) {
        String mode = ext.getBuildMode();
        if (mode == null && project.findProperty("remold.buildMode") != null) {
            mode = String.valueOf(project.findProperty("remold.buildMode"));
        }
        if (mode == null && project.findProperty("remold.build_mode") != null) {
            mode = String.valueOf(project.findProperty("remold.build_mode"));
        }
        if (mode == null && project.findProperty("remold.builder") != null) {
            mode = String.valueOf(project.findProperty("remold.builder"));
        }
        if (mode == null && project.findProperty("remoldBuildMode") != null) {
            mode = String.valueOf(project.findProperty("remoldBuildMode"));
        }
        if (mode == null || mode.trim().isEmpty() || "null".equals(mode)) {
            File gradleXml = new File(project.getRootDir(), ".idea/gradle.xml");
            if (gradleXml.isFile()) {
                try {
                    String content = new String(Files.readAllBytes(gradleXml.toPath()), StandardCharsets.UTF_8);
                    if (content.contains("name=\"delegatedBuild\" value=\"false\"")) {
                        return "idea";
                    }
                } catch (Throwable ignored) {
                }
            }
            mode = "auto";
        }
        return mode.trim().toLowerCase();
    }

    public static File resolveDeployDir(Project project, RemoldExtension ext) {
        Object custom = ext.getDeployDir();
        if (custom == null) custom = ext.getModsDir();
        if (custom == null) custom = project.findProperty("remold.deployDir");
        if (custom == null) custom = project.findProperty("remold.modsDir");
        if (custom == null) custom = project.findProperty("remoldDeployDir");
        if (custom == null) custom = project.findProperty("remoldModsDir");
        return custom != null ? project.file(custom) : null;
    }

    /**
     * 判断当前工程是否属于基于散装类路径（Classpath-based）驱动的模组开发生态（如 Minecraft Fabric / Quilt / NeoForge / Forge）。
     * 在该生态中，运行期直接消费散装 .class，严禁在运行前执行 jar / remapJar 打包部署到 run/mods/。
     */
    public static boolean isClassBasedEnvironment(Project project) {
        if (project == null) return false;

        // 1. 插件 ID 判定
        String[] pluginIds = {
            "fabric-loom",
            "dev.architectury.loom",
            "org.quiltmc.loom",
            "net.neoforged.moddev",
            "net.neoforged.gradle.userdev",
            "net.minecraftforge.gradle",
            "gg.essential.multi-version"
        };
        for (String id : pluginIds) {
            if (project.getPlugins().hasPlugin(id)) {
                return true;
            }
        }

        // 2. 扩展对象（Extension）判定
        String[] extensionNames = {"loom", "minecraft", "neoForge"};
        for (String extName : extensionNames) {
            if (project.getExtensions().findByName(extName) != null) {
                return true;
            }
        }

        // 3. 核心任务特征判定（存在 runClient 且伴随 remapJar 或 jar）
        if (project.getTasks().findByName("runClient") != null) {
            if (project.getTasks().findByName("remapJar") != null || project.getTasks().findByName("jar") != null) {
                return true;
            }
        }

        return false;
    }

    public static boolean shouldAutoDeploy(Project project, RemoldExtension ext) {
        if (isClassBasedEnvironment(project)) {
            // 散装类生态下强制禁用 autoDeploy，防止向 run/mods 输出未混淆或已混淆 JAR 引发双源加载冲突与 Mixin 崩溃
            boolean explicitAutoDeploy = ext.getAutoDeploy() != null && ext.getAutoDeploy();
            Object prop = project.findProperty("remold.autoDeploy");
            if (prop != null && Boolean.parseBoolean(String.valueOf(prop))) {
                explicitAutoDeploy = true;
            }
            if (explicitAutoDeploy) {
                project.getLogger().warn("[Remold] [WARN] 'autoDeploy = true' is ignored in Minecraft/Loom environment. In-dev runs use loose classes; outputting JARs to run/mods causes namespace crashes and bytecode rollbacks.");
            }
            return false;
        }

        if (ext.getAutoDeploy() != null) {
            return ext.getAutoDeploy();
        }
        Object prop = project.findProperty("remold.autoDeploy");
        if (prop != null) {
            return Boolean.parseBoolean(String.valueOf(prop));
        }
        return resolveDeployDir(project, ext) != null;
    }

    private static File resolvePluginJar(Project project, String targetModule, String ver) {
        List<File> candidates = new ArrayList<>();
        if (ver != null) {
            candidates.add(project.getRootProject().file("plugin-" + targetModule + "/build/libs/remold-plugin-" + targetModule + "-" + ver + ".jar"));
        }
        candidates.add(project.getRootProject().file("plugin-" + targetModule + "/build/libs/remold-plugin-" + targetModule + ".jar"));
        if (ver != null) {
            candidates.add(project.getRootProject().file("../plugin-" + targetModule + "/build/libs/remold-plugin-" + targetModule + "-" + ver + ".jar"));
        }
        candidates.add(project.getRootProject().file("../plugin-" + targetModule + "/build/libs/remold-plugin-" + targetModule + ".jar"));

        try {
            File parent = project.getRootDir().getParentFile();
            if (parent != null && parent.isDirectory()) {
                File[] siblings = parent.listFiles();
                if (siblings != null) {
                    for (File sibling : siblings) {
                        if (sibling.isDirectory()) {
                            if (ver != null) {
                                candidates.add(new File(sibling, "plugin-" + targetModule + "/build/libs/remold-plugin-" + targetModule + "-" + ver + ".jar"));
                            }
                            candidates.add(new File(sibling, "plugin-" + targetModule + "/build/libs/remold-plugin-" + targetModule + ".jar"));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (ver != null) {
            File m2Root = new File(System.getProperty("user.home"), ".m2/repository");
            String[] groupPaths = new String[]{"io/github/i-hope1/remold", "com/github/I-hope1/Remold"};
            for (String groupPath : groupPaths) {
                candidates.add(new File(m2Root, groupPath + "/plugin-" + targetModule + "/" + ver + "/plugin-" + targetModule + "-" + ver + ".jar"));
            }
            candidates.add(new File(project.getGradle().getGradleUserHomeDir(), "caches/remold/remold-plugin-" + targetModule + "-" + ver + ".jar"));
        }

        for (File c : candidates) {
            if (c.exists()) return c;
        }

        if (ver != null) {
            File resolved = resolveViaDetachedConfiguration(project, "com.github.I-hope1.Remold:plugin-" + targetModule + ":" + ver);
            if (resolved != null && resolved.exists()) return resolved;

            File cacheDir = new File(project.getGradle().getGradleUserHomeDir(), "caches/remold");
            File cachedJar = new File(cacheDir, "remold-plugin-" + targetModule + "-" + ver + ".jar");
            if (cachedJar.exists() && cachedJar.length() > 1024) {
                return cachedJar;
            }

            project.getLogger().lifecycle("[Remold] Downloading plugin: remold-plugin-" + targetModule + ".jar (version: " + ver + ")...");
            List<String> pluginUrls = Arrays.asList(
                    "https://github.com/I-hope1/Remold/releases/download/v" + ver + "/remold-plugin-" + targetModule + "-" + ver + ".jar",
                    "https://github.com/I-hope1/Remold/releases/download/v" + ver + "/remold-plugin-" + targetModule + ".jar",
                    "https://github.com/I-hope1/Remold/releases/download/" + ver + "/remold-plugin-" + targetModule + "-" + ver + ".jar",
                    "https://github.com/I-hope1/Remold/releases/download/" + ver + "/remold-plugin-" + targetModule + ".jar"
            );
            cacheDir.mkdirs();
            for (String url : pluginUrls) {
                if (downloadRemoteJar(url, cachedJar)) {
                    project.getLogger().lifecycle("[Remold] Plugin ready: " + cachedJar.getAbsolutePath());
                    return cachedJar;
                }
            }
        }
        return null;
    }

    private static File resolveViaDetachedConfiguration(Project project, String notation) {
        try {
            Dependency dep = project.getDependencies().create(notation);
            Configuration detached = project.getConfigurations().detachedConfiguration(dep);
            detached.setTransitive(false);
            Set<File> files = detached.resolve();
            if (!files.isEmpty()) {
                return files.iterator().next();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static File syncIntoLocalRemold(File src, File dest) {
        if (src == null || !src.exists()) return dest;
        try {
            if (src.getCanonicalPath().equals(dest.getCanonicalPath())) {
                return src;
            } else if (dest.exists() && dest.length() == src.length() && dest.lastModified() == src.lastModified()) {
                return dest;
            } else {
                if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
                Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                dest.setLastModified(src.lastModified());
                return dest;
            }
        } catch (Exception ignored) {
            return src;
        }
    }

    public static boolean downloadRemoteJar(String urlStr, File targetFile) {
        String curUrl = urlStr;
        for (int hop = 0; hop < 5; hop++) {
            try {
                URI uri = URI.create(curUrl);
                HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Remold-Gradle-Plugin");
                int code = conn.getResponseCode();
                if (code >= 300 && code <= 399) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.trim().isEmpty()) break;
                    curUrl = loc.startsWith("http") ? loc : uri.resolve(loc).toString();
                    continue;
                }
                if (code == 200) {
                    try (InputStream inStream = conn.getInputStream();
                         OutputStream outStream = Files.newOutputStream(targetFile.toPath())) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = inStream.read(buffer)) != -1) {
                            outStream.write(buffer, 0, read);
                        }
                    }
                    if (targetFile.exists() && targetFile.length() > 1024) {
                        return true;
                    } else {
                        targetFile.delete();
                    }
                }
                break;
            } catch (Exception ignored) {
                targetFile.delete();
                break;
            }
        }
        return false;
    }

    public static boolean usesMixinEcosystem(Project project) {
        if (project.getPlugins().hasPlugin("fabric-loom") || project.getExtensions().findByName("loom") != null) {
            return true;
        }
        try {
            for (Configuration cfg : project.getConfigurations()) {
                try {
                    for (Dependency dep : cfg.getDependencies()) {
                        if ("org.spongepowered".equals(dep.getGroup()) || "net.fabricmc".equals(dep.getGroup())) {
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 是否属于 Minecraft Fabric / Quilt 生态：声明了 Loom 系插件、loom 扩展或 net.fabricmc / org.quiltmc 依赖。
     *
     * 命中即默认挂载 plugin-fabric（可用 {@code -Premold.autoFabric=false} 关闭）：class path group 注入、
     * {@code mods} 目录排除与残留制品巡检都在该插件里，缺失的表现是"热更静默不生效"。
     */
    public static boolean usesFabricEcosystem(Project project) {
        if (project.getPlugins().hasPlugin("fabric-loom")
                || project.getPlugins().hasPlugin("dev.architectury.loom")
                || project.getPlugins().hasPlugin("org.quiltmc.loom")
                || project.getExtensions().findByName("loom") != null) {
            return true;
        }
        try {
            for (Configuration cfg : project.getConfigurations()) {
                try {
                    for (Dependency dep : cfg.getDependencies()) {
                        String grp = dep.getGroup();
                        if ("net.fabricmc".equals(grp) || "org.quiltmc".equals(grp)) {
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean usesForgeEcosystem(Project project) {
        if (project.getPlugins().hasPlugin("net.minecraftforge.gradle")
                || project.getPlugins().hasPlugin("net.neoforged.moddev")
                || project.getPlugins().hasPlugin("net.neoforged.gradle.userdev")
                || project.getExtensions().findByName("neoForge") != null
                || project.getExtensions().findByName("minecraft") != null) {
            return true;
        }
        try {
            for (Configuration cfg : project.getConfigurations()) {
                try {
                    for (Dependency dep : cfg.getDependencies()) {
                        String grp = dep.getGroup();
                        if (grp != null && (grp.equals("net.minecraftforge") || grp.equals("net.neoforged") || grp.startsWith("net.neoforged"))) {
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean containsIgnoreCase(List<String> list, String target) {
        for (String s : list) {
            if (s.equalsIgnoreCase(target)) return true;
        }
        return false;
    }
}
