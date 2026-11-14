package io.github.ihope1.remold.gradle;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.CommandLineArgumentProvider;
import org.gradle.process.JavaForkOptions;

/**
 * Remold Gradle Plugin (Binary Plugin).
 *
 * <p>Usage in {@code build.gradle.kts}:
 * <pre>{@code
 * plugins {
 *     id("io.github.i-hope1.remold") version "0.2.8"
 * }
 * remold {
 *     version = "0.2.8"
 * }
 * }</pre>
 */
public final class RemoldPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        RemoldExtension ext = project.getExtensions().create("remold", RemoldExtension.class);
        RemoldConfigResolver resolver = new RemoldConfigResolver();

        // Cache cleanup for Remold artifacts — the analogue of `clean` for `build/`.
        // Project-scoped by default: a project task must not silently wipe the machine-wide download
        // cache, so `<gradleUserHome>/caches/remold` is only deleted with --global (or the property).
        // Deliberately NOT wired into `clean`: wiping the shared agent cache would force a re-download
        // on every clean. Users run it explicitly after changing remoldVersion or after a corrupt cache.
        project.getTasks().register("cleanRemoldCache", CleanRemoldCacheTask.class, task -> {
            task.setGroup("remold");
            task.setDescription("Deletes project-local Remold caches (.gradle/remold, build/remold);"
                    + " pass --global to also delete the shared <gradleUserHome>/caches/remold cache (agent + plugin jars)");
            task.setProjectCacheDir(new File(project.getRootDir(), ".gradle/remold"));
            task.setGlobalCacheDir(new File(project.getGradle().getGradleUserHomeDir(), "caches/remold"));
            task.setBuildCacheDir(project.getLayout().getBuildDirectory().dir("remold").get().getAsFile());
            Object cleanGlobal = project.findProperty("remold.cleanGlobalCache");
            if (cleanGlobal == null) cleanGlobal = project.findProperty("remold.cleanGlobal");
            if (cleanGlobal != null) {
                task.setIncludeGlobalCache(Boolean.parseBoolean(String.valueOf(cleanGlobal).trim()));
            }
        });

        project.getPlugins().withId("java", javaPlugin -> {
            // Ensure full debugging symbols (source, lines, vars) for InitFix parameter tracking & lambda alignment
            project.getTasks().withType(JavaCompile.class).configureEach(compile -> {
                compile.getOptions().setDebug(true);
                compile.getOptions().getDebugOptions().setDebugLevel("source,lines,vars");
            });

            SourceSetContainer sourceSets = (SourceSetContainer) project.getExtensions().getByName("sourceSets");
            SourceSet mainSourceSet = sourceSets.getByName("main");

            // Register runRemold
            project.getTasks().register("runRemold", JavaExec.class, task -> {
                task.setGroup("application");
                task.setDescription("Runs the application with Remold Java Agent & JBR DCEVM (blocking foreground)");
                task.setClasspath(mainSourceSet.getRuntimeClasspath());
            });

            // Register deployArtifact
            project.getTasks().register("deployArtifact", Copy.class, task -> {
                task.setGroup("application");
                task.setDescription("Builds and deploys the output JAR into the specified deploy directory");
            });

            // Register deployMod
            project.getTasks().register("deployMod", task -> {
                task.setGroup("application");
                task.setDescription("Alias for deployArtifact");
                task.dependsOn("deployArtifact");
            });

            // Register generateIdeaRunConfig
            project.getTasks().register("generateIdeaRunConfig", GenerateIdeaRunConfigTask.class, task -> {
                task.setGroup("application");
                task.setDescription("Generates IntelliJ IDEA run configuration into .idea/runConfigurations/Run_with_Remold.xml");
                task.dependsOn("classes");
            });

            // Register generateRemoldScript
            project.getTasks().register("generateRemoldScript", GenerateRemoldScriptTask.class, task -> {
                task.setGroup("application");
                task.setDescription("Generates native standalone launcher scripts and args file to run Remold without Gradle Daemon");
                task.dependsOn("classes", "generateIdeaRunConfig");
            });

            // Register startRemold
            project.getTasks().register("startRemold", StartRemoldTask.class, task -> {
                task.setGroup("application");
                task.setDescription("Starts the application in a detached process so Gradle Daemon is immediately freed");
                task.dependsOn("generateRemoldScript");
            });

            // Configure task inputs and dependencies during afterEvaluate (configuration-cache-safe)
            project.afterEvaluate(p -> {
                boolean shouldAutoDeploy = RemoldConfigResolver.shouldAutoDeploy(project, ext);

                // Configure toolchain unset on runRemold if custom JBR provided
                String[] execAndHome = RemoldConfigResolver.resolveExecutableAndHome(project, ext);
                if (!"java".equals(execAndHome[0])) {
                    project.getTasks().named("runRemold", JavaExec.class, t ->
                            t.getJavaLauncher().set((JavaLauncher) null));
                }

                // Configure deployArtifact statically without capturing Project or RemoldExtension
                project.getTasks().named("deployArtifact", Copy.class, task -> {
                    Task remap = project.getTasks().findByName("remapJar");
                    Task artifactTask = remap != null ? remap : project.getTasks().findByName("jar");
                    if (artifactTask != null) {
                        task.dependsOn(artifactTask);
                        task.from(artifactTask);
                    }
                    File deployDir = RemoldConfigResolver.resolveDeployDir(project, ext);
                    if (deployDir != null) {
                        task.into(deployDir);
                        String deployPath = deployDir.getAbsolutePath();
                        task.doLast(t -> t.getLogger().lifecycle("[Remold] Artifact deployed to: " + deployPath));
                    } else {
                        task.doFirst(t -> {
                            throw new GradleException(
                                    "[Remold] autoDeploy is enabled but deploy directory is not configured!"
                                            + " Please specify via remold { deployDir = \"...\" } or gradle.properties (remold.deployDir=...).");
                        });
                    }
                });

                // Deduplicated task dependency wiring
                List<String> targetTasks = Arrays.asList("runRemold", "startRemold", "generateRemoldScript", "generateIdeaRunConfig");
                for (String taskName : targetTasks) {
                    project.getTasks().named(taskName, t -> {
                        if (shouldAutoDeploy) {
                            t.dependsOn("deployArtifact");
                        }
                        if (!ext.getDependsOn().isEmpty()) {
                            t.dependsOn(ext.getDependsOn());
                        }
                    });
                }

                if (shouldAutoDeploy) {
                    for (String runTaskName : new String[]{"runClient", "runServer"}) {
                        Task runTask = project.getTasks().findByName(runTaskName);
                        if (runTask != null) {
                            runTask.dependsOn("deployArtifact");
                        }
                    }
                }

                File ideaDir = new File(project.getRootDir(), ".idea");
                File xmlFile = new File(project.getRootDir(), ".idea/runConfigurations/Run_with_Remold.xml");
                if (ideaDir.exists()) {
                    Task classesTask = project.getTasks().findByName("classes");
                    if (classesTask != null) {
                        classesTask.finalizedBy("generateIdeaRunConfig");
                    }
                    if (!xmlFile.exists()) {
                        try {
                            RemoldResolvedConfig conf = resolver.resolve(project, ext);
                            boolean isLoom = isLoomProject(project);
                            File out = IdeaRunConfigGenerator.generate(project, conf, shouldAutoDeploy, isLoom);
                            if (out != null) {
                                project.getLogger().lifecycle("[Remold] IntelliJ IDEA run configuration ready: " + out.getAbsolutePath());
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }

                // Fabric Loom auto injection (client + dedicated server)
                if (isLoomProject(project)) {
                    try {
                        Object loom = project.getExtensions().findByName("loom");
                        if (loom != null) {
                            Method getRuns = loom.getClass().getMethod("getRuns");
                            injectIntoLoaderRuns(project, resolver.resolve(project, ext), getRuns.invoke(loom), "Fabric Loom");
                        }
                    } catch (Throwable ignored) {
                    }
                    JavaExec loomRun = findLoomSourceRunTask(project);
                    if (loomRun != null) {
                        wireLoomScriptPrerequisites(project, loomRun);
                    } else {
                        project.getGradle().projectsEvaluated(g -> {
                            JavaExec deferredLoomRun = findLoomSourceRunTask(project);
                            if (deferredLoomRun != null) {
                                wireLoomScriptPrerequisites(project, deferredLoomRun);
                            }
                        });
                    }
                }

                // NeoForge ModDevGradle auto injection (client + dedicated server)
                try {
                    Object runsExt = project.getExtensions().findByName("runs");
                    if (runsExt == null && project.getExtensions().findByName("neoForge") instanceof ExtensionAware) {
                        runsExt = ((ExtensionAware) project.getExtensions().findByName("neoForge")).getExtensions().findByName("runs");
                    }
                    if (runsExt != null) {
                        injectIntoLoaderRuns(project, resolver.resolve(project, ext), runsExt, "NeoForge ModDevGradle");
                    }
                } catch (Throwable ignored) {
                }

                // ForgeGradle auto injection (client + dedicated server)
                try {
                    Object mcExt = project.getExtensions().findByName("minecraft");
                    if (mcExt != null) {
                        Method getRuns = mcExt.getClass().getMethod("getRuns");
                        injectIntoLoaderRuns(project, resolver.resolve(project, ext), getRuns.invoke(mcExt), "ForgeGradle");
                    }
                } catch (Throwable ignored) {
                }
            });

            // Resolve lazy launch tasks right before Configuration Cache serialization when selected in the task graph
            project.getGradle().getTaskGraph().whenReady(graph -> {
                Task classesTask = project.getTasks().findByName("classes");
                if (classesTask != null && graph.hasTask(classesTask)) {
                    try {
                        RemoldResolvedConfig warmConf = resolver.resolveDeferredSync(project, ext, false);
                        Map<File, File> syncs = new LinkedHashMap<>(warmConf.getPendingSyncs());
                        if (!syncs.isEmpty()) {
                            classesTask.doLast(t -> RemoldConfigResolver.syncAll(syncs));
                        }
                    } catch (Exception ignored) {
                    }
                }

                Task deployTask = project.getTasks().findByName("deployArtifact");
                if (deployTask != null && graph.hasTask(deployTask)) {
                    try {
                        RemoldResolvedConfig deployConf = resolver.resolveDeferredSync(project, ext, false);
                        Map<File, File> syncs = new LinkedHashMap<>(deployConf.getPendingSyncs());
                        if (!syncs.isEmpty()) {
                            deployTask.doFirst(t -> RemoldConfigResolver.syncAll(syncs));
                        }
                    } catch (Exception ignored) {
                    }
                }

                Task rawRunRemold = project.getTasks().findByName("runRemold");
                if (rawRunRemold instanceof JavaExec && graph.hasTask(rawRunRemold)) {
                    ensureStandaloneLauncherSupported(project, "runRemold", false);
                    RemoldResolvedConfig conf = resolver.resolveDeferredSync(project, ext, true);
                    configureRunRemold((JavaExec) rawRunRemold, conf, ext);
                }

                Task rawStartTask = project.getTasks().findByName("startRemold");
                if (rawStartTask instanceof StartRemoldTask && graph.hasTask(rawStartTask)) {
                    ensureStandaloneLauncherSupported(project, "startRemold", true);
                    StartRemoldTask startTask = (StartRemoldTask) rawStartTask;
                    JavaExec loomRun = isLoomProject(project) ? findLoomSourceRunTask(project) : null;
                    RemoldResolvedConfig conf = resolver.resolveDeferredSync(project, ext, loomRun == null);
                    startTask.setTargetJar(conf.getTargetJar());
                    startTask.setBuildOutputDir(project.getLayout().getBuildDirectory().getAsFile().get());
                    startTask.setProjectName(project.getName());
                    if (loomRun != null) {
                        startTask.setWorkingDir(resolveLoomWorkingDir(project, loomRun));
                        startTask.setExecutable(resolveLoomExecutable(conf, loomRun));
                        startTask.setEnvironment(extractCustomEnvironment(project, loomRun));
                    } else {
                        startTask.setWorkingDir(project.getProjectDir());
                        startTask.setExecutable(conf.getExecutable());
                    }
                }

                Task rawScriptTask = project.getTasks().findByName("generateRemoldScript");
                if (rawScriptTask instanceof GenerateRemoldScriptTask && graph.hasTask(rawScriptTask)) {
                    ensureStandaloneLauncherSupported(project, "generateRemoldScript", true);
                    GenerateRemoldScriptTask scriptTask = (GenerateRemoldScriptTask) rawScriptTask;
                    JavaExec loomRun = isLoomProject(project) ? findLoomSourceRunTask(project) : null;
                    RemoldResolvedConfig conf = resolver.resolveDeferredSync(project, ext, loomRun == null);
                    scriptTask.setTargetJar(conf.getTargetJar());
                    scriptTask.setBuildOutputDir(project.getLayout().getBuildDirectory().getAsFile().get());
                    scriptTask.setPendingSyncs(conf.getPendingSyncs());
                    if (loomRun != null) {
                        String loomMainClass = loomRun.getMainClass().getOrNull();
                        if (loomMainClass == null || loomMainClass.trim().isEmpty()) {
                            throw new GradleException("[Remold] Fabric Loom task ':" + loomRun.getName()
                                    + "' has no mainClass configured; cannot export launcher script.");
                        }
                        scriptTask.setWorkingDir(resolveLoomWorkingDir(project, loomRun));
                        scriptTask.setExecutable(resolveLoomExecutable(conf, loomRun));
                        scriptTask.setMainClass(loomMainClass);
                        scriptTask.setJvmArgs(mergeLoomJvmArgs(loomRun, conf));
                        scriptTask.setAppArgs(mergeLoomAppArgs(loomRun, conf));
                        scriptTask.setEnvironment(extractCustomEnvironment(project, loomRun));
                        scriptTask.getRuntimeClasspath().from(loomRun.getClasspath());
                    } else {
                        scriptTask.setExecutable(conf.getExecutable());
                        scriptTask.setMainClass(conf.getMainClass());
                        scriptTask.setJvmArgs(conf.getJvmArgs());
                        scriptTask.setAppArgs(conf.getAppArgs());
                        scriptTask.getRuntimeClasspath().from(conf.getRuntimeClasspath());
                    }
                }

                Task rawIdeaTask = project.getTasks().findByName("generateIdeaRunConfig");
                if (rawIdeaTask instanceof GenerateIdeaRunConfigTask && graph.hasTask(rawIdeaTask)) {
                    boolean shouldAutoDeploy = RemoldConfigResolver.shouldAutoDeploy(project, ext);
                    boolean isLoom = isLoomProject(project);
                    boolean explicit = isTaskExplicitlyRequested(project,
                            "generateIdeaRunConfig", "generateRemoldScript", "startRemold");
                    configureIdeaRunConfigTask((GenerateIdeaRunConfigTask) rawIdeaTask,
                            project, ext, resolver, shouldAutoDeploy, isLoom, !explicit);
                }
            });
        });

        // Explicit remold.attach(...) targets work across Java, Test, application, and loader run targets
        project.afterEvaluate(p -> {
            if (ext.getAttachTargets().isEmpty()) {
                return;
            }
            boolean shouldAutoDeploy = RemoldConfigResolver.shouldAutoDeploy(project, ext);
            RemoldResolvedConfig attachConf = resolver.resolveDeferredSync(project, ext, false);
            for (Object target : ext.getAttachTargets()) {
                applyAttachTarget(project, ext, attachConf, target, shouldAutoDeploy);
            }
        });
    }

    private static boolean isLoomProject(Project project) {
        return project.getPlugins().hasPlugin("fabric-loom") || project.getExtensions().findByName("loom") != null;
    }

    static void ensureStandaloneLauncherSupported(Project project, String taskName) {
        ensureStandaloneLauncherSupported(project, taskName, !"runRemold".equals(taskName));
    }

    static void ensureStandaloneLauncherSupported(
            Project project, String taskName, boolean allowLoomRunExtraction) {
        if (!RemoldConfigResolver.isClassBasedEnvironment(project)) {
            return;
        }
        boolean isLoom = isLoomProject(project);
        JavaExec loomRun = isLoom ? findLoomSourceRunTask(project) : null;
        if (allowLoomRunExtraction && isLoom && loomRun != null) {
            return;
        }
        String loaderTaskHint;
        if (isLoom) {
            loaderTaskHint = loomRun != null
                    ? "Use Loom's 'runClient'/'runServer' task or './gradlew startRemold' (or the IDEA"
                            + " 'Minecraft Client' Application run configuration with"
                            + " loom.runs.configureEach { preferGradleTask = false } to avoid holding a Gradle daemon)."
                    : "No Loom 'runClient'/'runServer' JavaExec task was found to extract launch arguments from;"
                            + " use Loom's native run tasks or the IDEA 'Minecraft Client' Application run configuration.";
        } else {
            loaderTaskHint = "Use the loader's native run tasks (e.g. 'runClient'/'runServer') or IDE run configurations.";
        }
        throw new GradleException("[Remold] Task ':" + taskName + "' cannot run in standalone mode in this"
                + " Fabric Loom / Forge / NeoForge environment"
                + " (bypassing DevLaunchInjector / ModLauncher causes Bootstrap/IllegalAccessError crashes). "
                + loaderTaskHint);
    }

    static JavaExec findLoomSourceRunTask(Project project) {
        Object prop = project.findProperty("remold.loomRunTask");
        if (prop != null) {
            String custom = prop.toString().trim();
            if (!custom.isEmpty()) {
                Task task = project.getTasks().findByName(custom);
                return task instanceof JavaExec ? (JavaExec) task : null;
            }
        }
        for (String name : new String[]{"runClient", "runServer"}) {
            Task task = project.getTasks().findByName(name);
            if (task instanceof JavaExec) {
                return (JavaExec) task;
            }
        }
        return null;
    }

    private static void wireLoomScriptPrerequisites(Project project, JavaExec loomRun) {
        List<Object> prereqs = new ArrayList<>();
        try {
            Set<? extends Task> resolved = loomRun.getTaskDependencies().getDependencies(loomRun);
            for (Task dep : resolved) {
                if (dep != null && dep != loomRun) {
                    prereqs.add(dep);
                }
            }
        } catch (Throwable ignored) {
        }
        if (prereqs.isEmpty()) {
            for (Object dep : loomRun.getDependsOn()) {
                if (dep != null && dep != loomRun) {
                    prereqs.add(dep);
                }
            }
        }
        if (!prereqs.isEmpty()) {
            project.getTasks().named("generateRemoldScript", t -> t.dependsOn(prereqs));
        }
    }

    private static File resolveLoomWorkingDir(Project project, JavaExec loomRun) {
        File taskDir = loomRun.getWorkingDir();
        // Loom's AbstractRunTask leaves JavaExec.workingDir at projectDir until exec() runs;
        // prefer explicit task overrides, then Loom's internalRunDir / RunConfiguration directory.
        if (taskDir != null && !Objects.equals(taskDir.getAbsoluteFile(), project.getProjectDir().getAbsoluteFile())) {
            return taskDir;
        }
        Object internalDirProp = invokeAccessibleNoArg(loomRun, "getInternalRunDir");
        if (internalDirProp instanceof DirectoryProperty) {
            Directory dirVal = ((DirectoryProperty) internalDirProp).getOrNull();
            if (dirVal != null) {
                return dirVal.getAsFile();
            }
        }
        Object runConfig = findLoomRunConfig(project, loomRun);
        if (runConfig != null) {
            Object runDirProp = invokeAccessibleNoArg(runConfig, "getRunDirectory");
            if (runDirProp instanceof DirectoryProperty) {
                Directory dirVal = ((DirectoryProperty) runDirProp).getOrNull();
                if (dirVal != null) {
                    return dirVal.getAsFile();
                }
            }
            Object runDirStr = invokeAccessibleNoArg(runConfig, "getRunDir");
            if (runDirStr != null) {
                String rel = runDirStr.toString().trim();
                if (!rel.isEmpty()) {
                    File candidate = new File(rel);
                    return candidate.isAbsolute() ? candidate : new File(project.getProjectDir(), rel);
                }
            }
        }
        return new File(project.getProjectDir(), "run");
    }

    private static String resolveLoomExecutable(RemoldResolvedConfig conf, JavaExec loomRun) {
        if (!"java".equals(conf.getExecutable())) {
            return conf.getExecutable();
        }
        try {
            if (loomRun.getJavaLauncher().isPresent()) {
                return loomRun.getJavaLauncher().get().getExecutablePath().getAsFile().getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        String taskExec = loomRun.getExecutable();
        if (taskExec != null && !taskExec.trim().isEmpty()) {
            return taskExec;
        }
        return conf.getExecutable();
    }

    static List<String> mergeLoomJvmArgs(JavaExec loomRun, RemoldResolvedConfig conf) {
        String loomArgFile = resolveLoomArgFilePath(loomRun);
        List<String> base = stripClasspathArgs(loomRun.getAllJvmArgs(), loomArgFile);
        List<String> extra = computeExtraJvmArgs(base, conf.getJvmArgs());
        if (extra.isEmpty()) {
            return base;
        }
        List<String> merged = new ArrayList<>(base.size() + extra.size());
        merged.addAll(base);
        merged.addAll(extra);
        return merged;
    }

    private static List<String> mergeLoomAppArgs(JavaExec loomRun, RemoldResolvedConfig conf) {
        List<String> merged = new ArrayList<>();
        List<String> taskArgs = loomRun.getArgs();
        if (taskArgs != null) {
            for (String arg : taskArgs) {
                if (arg != null) {
                    merged.add(arg);
                }
            }
        }
        for (CommandLineArgumentProvider provider : loomRun.getArgumentProviders()) {
            if (provider == null) continue;
            Iterable<String> provided = provider.asArguments();
            if (provided == null) continue;
            for (String arg : provided) {
                if (arg != null) {
                    merged.add(arg);
                }
            }
        }
        for (String arg : conf.getAppArgs()) {
            if (arg != null && !merged.contains(arg)) {
                merged.add(arg);
            }
        }
        return merged;
    }

    static List<String> stripClasspathArgs(List<String> args) {
        return stripClasspathArgs(args, null);
    }

    static List<String> stripClasspathArgs(List<String> args, String loomArgFilePath) {
        if (args == null || args.isEmpty()) {
            return Collections.emptyList();
        }
        String normalizedArgFile = loomArgFilePath != null ? loomArgFilePath.replace('\\', '/') : null;
        List<String> cleaned = new ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null) continue;
            if ("-cp".equals(arg) || "-classpath".equals(arg) || "--class-path".equals(arg)) {
                if (i + 1 < args.size()) {
                    i++;
                }
                continue;
            }
            if (arg.startsWith("--class-path=")) {
                continue;
            }
            if (isLoomClasspathArgFileToken(arg, normalizedArgFile)) {
                continue;
            }
            cleaned.add(arg);
        }
        return cleaned;
    }

    private static boolean isLoomClasspathArgFileToken(String arg, String normalizedLoomArgFile) {
        if (!arg.startsWith("@") || arg.length() <= 1) {
            return false;
        }
        String filePart = arg.substring(1).replace('\\', '/');
        if (normalizedLoomArgFile != null && normalizedLoomArgFile.equals(filePart)) {
            return true;
        }
        return filePart.contains("/loom-cache/argFiles/");
    }

    private static String resolveLoomArgFilePath(JavaExec loomRun) {
        Object prop = invokeAccessibleNoArg(loomRun, "getArgFilePath");
        if (prop instanceof Property<?>) {
            Object val = ((Property<?>) prop).getOrNull();
            return val != null ? val.toString() : null;
        }
        return null;
    }

    private static Map<String, String> extractCustomEnvironment(Project project, JavaExec loomRun) {
        Map<String, Object> mergedEnv = new LinkedHashMap<>();
        Map<String, Object> taskEnv = loomRun.getEnvironment();
        if (taskEnv != null) {
            mergedEnv.putAll(taskEnv);
        }
        Object internalEnvProp = invokeAccessibleNoArg(loomRun, "getInternalEnvironmentVars");
        if (internalEnvProp instanceof MapProperty<?, ?>) {
            Map<?, ?> raw = ((MapProperty<?, ?>) internalEnvProp).getOrNull();
            if (raw != null) {
                for (Map.Entry<?, ?> e : raw.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        mergedEnv.put(e.getKey().toString(), e.getValue());
                    }
                }
            }
        } else {
            Object runConfig = findLoomRunConfig(project, loomRun);
            if (runConfig != null) {
                Object cfgEnv = invokeAccessibleNoArg(runConfig, "getEnvironmentVars");
                if (cfgEnv instanceof MapProperty<?, ?>) {
                    Map<?, ?> raw = ((MapProperty<?, ?>) cfgEnv).getOrNull();
                    if (raw != null) {
                        for (Map.Entry<?, ?> e : raw.entrySet()) {
                            if (e.getKey() != null && e.getValue() != null) {
                                mergedEnv.put(e.getKey().toString(), e.getValue());
                            }
                        }
                    }
                }
            }
        }
        if (mergedEnv.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> processEnv = System.getenv();
        Map<String, String> diff = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mergedEnv.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = entry.getKey();
            String val = String.valueOf(entry.getValue());
            if (!processEnv.containsKey(key) || !Objects.equals(processEnv.get(key), val)) {
                diff.put(key, val);
            }
        }
        return diff;
    }

    private static Object findLoomRunConfig(Project project, JavaExec loomRun) {
        try {
            Object loom = project.getExtensions().findByName("loom");
            if (loom == null) return null;
            Object runs = loom.getClass().getMethod("getRuns").invoke(loom);
            if (runs == null) return null;
            Method findByName = runs.getClass().getMethod("findByName", String.class);
            String taskName = loomRun.getName();
            List<String> candidates = new ArrayList<>();
            if (taskName.startsWith("run") && taskName.length() > 3) {
                String suffix = taskName.substring(3);
                candidates.add(Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1));
            }
            candidates.add(taskName);
            for (String candidate : candidates) {
                Object found = findByName.invoke(runs, candidate);
                if (found != null) {
                    return found;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object invokeAccessibleNoArg(Object target, String methodName) {
        if (target == null) return null;
        for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                Method m = cls.getDeclaredMethod(methodName);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isTaskExplicitlyRequested(Project project, String... taskNames) {
        List<String> requested = project.getGradle().getStartParameter().getTaskNames();
        if (requested == null || requested.isEmpty()) {
            return false;
        }
        for (String req : requested) {
            if (req == null) continue;
            for (String candidate : taskNames) {
                if (req.equals(candidate) || req.endsWith(":" + candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void configureRunRemold(JavaExec task, RemoldResolvedConfig conf, RemoldExtension ext) {
        task.getMainClass().set(conf.getMainClass());
        task.setClasspath(conf.getRuntimeClasspath());
        if (!conf.getAppArgs().isEmpty()) {
            task.args(conf.getAppArgs());
        }
        if (!"java".equals(conf.getExecutable())) {
            task.setExecutable(conf.getExecutable());
            task.getJavaLauncher().set((JavaLauncher) null);
        }
        appendJvmArgsDeduplicated(task, conf.getJvmArgs());

        List<String> summaryLines = new ArrayList<>();
        summaryLines.add("[Remold] Launch configuration assembled:");
        if (conf.getTargetJar() != null) {
            summaryLines.add("  - Target JAR: " + conf.getTargetJar().getAbsolutePath());
        }
        summaryLines.add("  - Main class: " + conf.getMainClass());
        summaryLines.add("  - Agent: " + conf.getAgentFile().getAbsolutePath());
        summaryLines.add("  - Watch directories (" + conf.getAllWatchDirs().size() + "): " + conf.getWatchPath());
        if (!conf.getAppArgs().isEmpty()) {
            summaryLines.add("  - App arguments: " + String.join(" ", conf.getAppArgs()));
        }
        if (ext.getFileShakeMs() != null) {
            summaryLines.add("  - File Shake: " + ext.getFileShakeMs() + "ms");
        }
        if (!"java".equals(conf.getExecutable())) {
            summaryLines.add("  - JBR: " + conf.getExecutable());
        }
        File targetJar = conf.getTargetJar();
        Map<File, File> pendingSyncs = new LinkedHashMap<>(conf.getPendingSyncs());

        task.doFirst(t -> {
            if (targetJar != null && !targetJar.exists()) {
                throw new GradleException("[Remold] Target launch JAR does not exist; cannot launch application: "
                        + targetJar.getAbsolutePath());
            }
            RemoldConfigResolver.syncAll(pendingSyncs);
            for (String line : summaryLines) {
                t.getLogger().lifecycle(line);
            }
        });
    }

    private static void configureIdeaRunConfigTask(
            GenerateIdeaRunConfigTask task,
            Project project,
            RemoldExtension ext,
            RemoldConfigResolver resolver,
            boolean shouldAutoDeploy,
            boolean isLoom,
            boolean skipWhenUnconfigured) {
        task.setRootDir(project.getRootDir());
        task.setProjectName(project.getName());
        task.setShouldAutoDeploy(shouldAutoDeploy);
        task.setLoom(isLoom);
        task.setSkipWhenUnconfigured(skipWhenUnconfigured);
        try {
            RemoldResolvedConfig conf = resolver.resolveDeferredSync(project, ext, true);
            task.setMainClass(conf.getMainClass());
            task.setExecutableHome(conf.getExecutableHome());
            task.setTargetJar(conf.getTargetJar());
            task.setBuildMode(conf.getBuildMode());
            task.setJvmArgs(conf.getJvmArgs());
            task.setAppArgs(conf.getAppArgs());
            task.setPendingSyncs(conf.getPendingSyncs());
            task.setMissingConfigMessage(null);
        } catch (GradleException e) {
            task.setMissingConfigMessage(e.getMessage());
        }
    }

    static void applyAttachTarget(
            Project project,
            RemoldExtension ext,
            RemoldResolvedConfig conf,
            Object target,
            boolean shouldAutoDeploy) {
        if (target == null) return;

        Task resolvedTask = null;
        if (target instanceof String) {
            String name = (String) target;
            resolvedTask = project.getTasks().findByName(name);
            if (resolvedTask == null && name.startsWith(":")) {
                resolvedTask = project.getTasks().findByPath(name);
            }
            if (resolvedTask == null) {
                throw new GradleException("[Remold] Cannot attach to task '" + name + "': task does not exist.");
            }
        } else if (target instanceof TaskProvider<?>) {
            resolvedTask = ((TaskProvider<?>) target).get();
        } else if (target instanceof Task) {
            resolvedTask = (Task) target;
        }

        if (resolvedTask != null) {
            if (!(resolvedTask instanceof JavaForkOptions)) {
                throw new GradleException("[Remold] Cannot attach to task '" + resolvedTask.getName()
                        + "' (" + resolvedTask.getClass().getName()
                        + "): task does not implement JavaForkOptions.");
            }
            JavaForkOptions fork = (JavaForkOptions) resolvedTask;
            appendJvmArgsDeduplicated(fork, conf.getJvmArgs());
            if (!"java".equals(conf.getExecutable())) {
                fork.setExecutable(conf.getExecutable());
                if (resolvedTask instanceof JavaExec) {
                    ((JavaExec) resolvedTask).getJavaLauncher().set((JavaLauncher) null);
                } else if (resolvedTask instanceof Test) {
                    ((Test) resolvedTask).getJavaLauncher().set((JavaLauncher) null);
                }
            }
            if (shouldAutoDeploy && project.getTasks().findByName("deployArtifact") != null) {
                resolvedTask.dependsOn("deployArtifact");
            }
            if (!ext.getDependsOn().isEmpty()) {
                resolvedTask.dependsOn(ext.getDependsOn());
            }
            Map<File, File> pendingSyncs = new LinkedHashMap<>(conf.getPendingSyncs());
            if (!pendingSyncs.isEmpty()) {
                resolvedTask.doFirst(t -> RemoldConfigResolver.syncAll(pendingSyncs));
            }
            project.getLogger().lifecycle("[Remold] Attached hot-reload agent to task :" + resolvedTask.getName());
            return;
        }

        RemoldConfigResolver.syncAll(conf.getPendingSyncs());
        if (injectRunJvmArgs(target, conf.getJvmArgs())) {
            project.getLogger().lifecycle("[Remold] Attached hot-reload agent to custom run target: " + target);
            return;
        }

        throw new GradleException("[Remold] Unsupported remold.attach target type: "
                + target.getClass().getName()
                + ". Expected JavaExec, Test, JavaForkOptions, TaskProvider, task name, or loader run object.");
    }

    static void appendJvmArgsDeduplicated(JavaForkOptions fork, List<String> jvmArgs) {
        if (fork == null || jvmArgs == null || jvmArgs.isEmpty()) return;
        List<String> existingList = fork.getJvmArgs();
        List<String> toAdd = computeExtraJvmArgs(existingList, jvmArgs);
        if (!toAdd.isEmpty()) {
            fork.jvmArgs(toAdd);
        }
    }

    static List<String> computeExtraJvmArgs(List<String> existing, List<String> jvmArgs) {
        if (jvmArgs == null || jvmArgs.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> existingList = existing != null ? existing : Collections.emptyList();
        boolean hasAgent = false;
        for (String arg : existingList) {
            if (arg != null && arg.startsWith("-javaagent:") && arg.contains("remold-agent.jar")) {
                hasAgent = true;
                break;
            }
        }
        List<String> toAdd = new ArrayList<>();
        for (String arg : jvmArgs) {
            if (arg == null) continue;
            if (arg.startsWith("-javaagent:") && arg.contains("remold-agent.jar")) {
                if (hasAgent) continue;
                hasAgent = true;
                toAdd.add(arg);
            } else if (!existingList.contains(arg)) {
                toAdd.add(arg);
            }
        }
        return toAdd;
    }

    /**
     * Injects the Remold agent JVM arguments into every run environment the loader exposes
     * (client, dedicated server and Fabric client/server gametest runs), so
     * {@code ./gradlew runServer} / {@code runClientGameTest} get the same agent as
     * {@code runClient}. Run environments the loader did not register are silently skipped.
     */
    static void injectIntoLoaderRuns(Project project, RemoldResolvedConfig conf, Object runs, String label) {
        if (runs == null || conf == null) return;
        for (String env : new String[]{"client", "server", "clientGameTest", "gameTest"}) {
            Object run;
            try {
                run = runs.getClass().getMethod("findByName", String.class).invoke(runs, env);
            } catch (Throwable ignored) {
                continue;
            }
            if (run == null) continue;
            if (injectRunJvmArgs(run, conf.getJvmArgs())) {
                project.getLogger().lifecycle("[Remold] Injected hot-reload agent parameters into " + label
                        + " run" + Character.toUpperCase(env.charAt(0)) + env.substring(1) + "!");
            }
        }
    }

    /**
     * Adds JVM arguments through whichever run-config API the loader exposes
     * ({@code getJvmArguments()} list property, {@code vmArg(String)} or {@code jvmArg(String)}).
     *
     * @return {@code true} when an injection API was found and used
     */
    static boolean injectRunJvmArgs(Object run, List<String> jvmArgs) {
        if (run == null || jvmArgs == null || jvmArgs.isEmpty()) return false;
        try {
            Object property = run.getClass().getMethod("getJvmArguments").invoke(run);
            if (property instanceof ListProperty) {
                @SuppressWarnings("unchecked")
                ListProperty<String> list = (ListProperty<String>) property;
                list.addAll(jvmArgs);
                return true;
            }
        } catch (Throwable ignored) {
        }
        for (String methodName : new String[]{"vmArg", "jvmArg"}) {
            try {
                Method method = run.getClass().getMethod(methodName, String.class);
                for (String arg : jvmArgs) {
                    method.invoke(run, arg);
                }
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
