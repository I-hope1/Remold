package io.github.ihope1.remold.gradle;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.compile.JavaCompile;

/**
 * Remold Gradle Plugin (Binary Plugin).
 *
 * <p>Usage in {@code build.gradle.kts}:
 * <pre>{@code
 * plugins {
 *     id("io.github.i-hope1.remold") version "0.2.7"
 * }
 * remold {
 *     version = "0.2.7"
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

                task.doFirst(t -> {
                    RemoldResolvedConfig conf = resolver.resolve(project, ext);
                    if (conf.getTargetJar() != null && !conf.getTargetJar().exists()) {
                        throw new GradleException("[Remold] Target launch JAR does not exist; cannot launch application: " + conf.getTargetJar().getAbsolutePath());
                    }
                    task.getMainClass().set(conf.getMainClass());
                    task.setClasspath(conf.getRuntimeClasspath());
                    if (!conf.getAppArgs().isEmpty()) {
                        task.args(conf.getAppArgs());
                    }
                    if (!"java".equals(conf.getExecutable())) {
                        task.setExecutable(conf.getExecutable());
                    }
                    task.jvmArgs(conf.getJvmArgs());

                    project.getLogger().lifecycle("[Remold] Launch configuration assembled:");
                    if (conf.getTargetJar() != null) {
                        project.getLogger().lifecycle("  - Target JAR: " + conf.getTargetJar().getAbsolutePath());
                    }
                    project.getLogger().lifecycle("  - Main class: " + conf.getMainClass());
                    project.getLogger().lifecycle("  - Agent: " + conf.getAgentFile().getAbsolutePath());
                    project.getLogger().lifecycle("  - Watch directories (" + conf.getAllWatchDirs().size() + "): " + conf.getWatchPath());
                    if (!conf.getAppArgs().isEmpty()) {
                        project.getLogger().lifecycle("  - App arguments: " + String.join(" ", conf.getAppArgs()));
                    }
                    if (ext.getFileShakeMs() != null) {
                        project.getLogger().lifecycle("  - File Shake: " + ext.getFileShakeMs() + "ms");
                    }
                    if (!"java".equals(conf.getExecutable())) {
                        project.getLogger().lifecycle("  - JBR: " + conf.getExecutable());
                    }
                });
            });

            // Register deployArtifact
            project.getTasks().register("deployArtifact", Copy.class, task -> {
                task.setGroup("application");
                task.setDescription("Builds and deploys the output JAR into the specified deploy directory");
                task.dependsOn(project.provider(() -> project.getTasks().findByName("remapJar") != null ? "remapJar" : "jar"));
                task.doFirst(t -> resolver.resolve(project, ext));
                task.from(project.provider(() -> {
                    Task remap = project.getTasks().findByName("remapJar");
                    return remap != null ? remap : project.getTasks().getByName("jar");
                }));
                task.into(project.provider(() -> {
                    File deployDir = RemoldConfigResolver.resolveDeployDir(project, ext);
                    if (deployDir == null) {
                        throw new GradleException("[Remold] autoDeploy is enabled but deploy directory is not configured! Please specify via remold { deployDir = \"...\" } or gradle.properties (remold.deployDir=...).");
                    }
                    return deployDir;
                }));
                task.doLast(t -> {
                    File deployDir = RemoldConfigResolver.resolveDeployDir(project, ext);
                    project.getLogger().lifecycle("[Remold] Artifact deployed to: " + (deployDir != null ? deployDir.getAbsolutePath() : "unknown"));
                });
            });

            // Register deployMod
            project.getTasks().register("deployMod", task -> {
                task.setGroup("application");
                task.setDescription("Alias for deployArtifact");
                task.dependsOn("deployArtifact");
            });

            // Hook classes doLast
            project.getTasks().named("classes").configure(task -> {
                task.doLast(t -> {
                    try {
                        File localAgent = new File(project.getRootDir(), ".gradle/remold/remold-agent.jar");
                        if (!localAgent.exists() || localAgent.length() <= 1024) {
                            resolver.resolve(project, ext);
                        }
                    } catch (Exception ignored) {
                    }
                });
            });

            // Register generateIdeaRunConfig
            project.getTasks().register("generateIdeaRunConfig", task -> {
                task.setGroup("application");
                task.setDescription("Generates IntelliJ IDEA run configuration into .idea/runConfigurations/Run_with_Remold.xml");
                task.dependsOn("classes");

                task.doLast(t -> {
                    RemoldResolvedConfig conf = resolver.resolve(project, ext);
                    boolean shouldAutoDeploy = RemoldConfigResolver.shouldAutoDeploy(project, ext);
                    boolean isLoom = project.getPlugins().hasPlugin("fabric-loom") || project.getExtensions().findByName("loom") != null;
                    File xmlFile = IdeaRunConfigGenerator.generate(project, conf, shouldAutoDeploy, isLoom);
                    if (xmlFile != null) {
                        project.getLogger().lifecycle("[Remold] Generated IntelliJ IDEA run configuration: " + xmlFile.getAbsolutePath());
                        project.getLogger().lifecycle("[Remold] Open this project in IDEA to launch directly with the run/debug configuration.");
                    }
                });
            });

            // Register generateRemoldScript
            project.getTasks().register("generateRemoldScript", task -> {
                task.setGroup("application");
                task.setDescription("Generates native standalone launcher scripts and args file to run Remold without Gradle Daemon");
                task.dependsOn("classes", "generateIdeaRunConfig");

                task.doLast(t -> {
                    RemoldResolvedConfig conf = resolver.resolve(project, ext);
                    if (conf.getTargetJar() != null && !conf.getTargetJar().exists()) {
                        throw new GradleException("[Remold] Target launch JAR does not exist; cannot launch application: " + conf.getTargetJar().getAbsolutePath());
                    }
                    File buildDir = project.getLayout().getBuildDirectory().getAsFile().get();
                    File remoldDir = new File(buildDir, "remold");
                    remoldDir.mkdirs();

                    File argsFile = new File(remoldDir, "remold.args");
                    List<String> lines = new ArrayList<>(conf.getJvmArgs());
                    lines.add("-cp");
                    lines.add(conf.getRuntimeClasspath().getAsPath());
                    lines.add(conf.getMainClass());
                    lines.addAll(conf.getAppArgs());

                    try (BufferedWriter writer = Files.newBufferedWriter(argsFile.toPath(), StandardCharsets.UTF_8)) {
                        for (String line : lines) {
                            if (line.contains(" ") || line.contains("\t")) {
                                writer.write("\"" + line.replace("\\", "\\\\") + "\"\n");
                            } else {
                                writer.write(line + "\n");
                            }
                        }
                    } catch (IOException e) {
                        throw new GradleException("[Remold] Failed to write args file: " + argsFile.getAbsolutePath(), e);
                    }

                    File batFile = new File(buildDir, "run-remold.bat");
                    String batContent = "@echo off\r\nset \"JAVA_EXE=" + conf.getExecutable() + "\"\r\nif not exist \"%JAVA_EXE%\" set \"JAVA_EXE=java\"\r\n\"%JAVA_EXE%\" @\"%~dp0remold\\remold.args\" %*\r\n";
                    try {
                        Files.write(batFile.toPath(), batContent.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new GradleException("[Remold] Failed to write bat script: " + batFile.getAbsolutePath(), e);
                    }

                    File shFile = new File(buildDir, "run-remold.sh");
                    String shContent = "#!/usr/bin/env bash\nDIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"\nJAVA_EXE=\"" + conf.getExecutable() + "\"\nif [ ! -f \"$JAVA_EXE\" ]; then\n    JAVA_EXE=\"java\"\nfi\nexec \"$JAVA_EXE\" \"@$DIR/remold/remold.args\" \"$@\"\n";
                    try {
                        Files.write(shFile.toPath(), shContent.getBytes(StandardCharsets.UTF_8));
                        shFile.setExecutable(true, false);
                    } catch (Exception ignored) {
                    }

                    project.getLogger().lifecycle("[Remold] Generated native launch script:");
                    project.getLogger().lifecycle("  - Windows: " + batFile.getAbsolutePath());
                    project.getLogger().lifecycle("  - Linux/macOS: " + shFile.getAbsolutePath());
                    project.getLogger().lifecycle("  - Args file: " + argsFile.getAbsolutePath());
                    project.getLogger().lifecycle("[Remold] Run the script directly to launch the application without Gradle daemon overhead.");
                });
            });

            // Register startRemold
            project.getTasks().register("startRemold", task -> {
                task.setGroup("application");
                task.setDescription("Starts the application in a detached process so Gradle Daemon is immediately freed");
                task.dependsOn("generateRemoldScript");

                task.doLast(t -> {
                    RemoldResolvedConfig conf = resolver.resolve(project, ext);
                    if (conf.getTargetJar() != null && !conf.getTargetJar().exists()) {
                        throw new GradleException("[Remold] Target launch JAR does not exist; cannot launch application: " + conf.getTargetJar().getAbsolutePath());
                    }
                    File buildDir = project.getLayout().getBuildDirectory().getAsFile().get();
                    File argsFile = new File(buildDir, "remold/remold.args");
                    boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");

                    try {
                        if (isWin) {
                            new ProcessBuilder("cmd", "/c", "start", "\"Remold [" + project.getName() + "]\"", conf.getExecutable(), "@" + argsFile.getAbsolutePath())
                                    .directory(project.getProjectDir())
                                    .start();
                            project.getLogger().lifecycle("[Remold] Application launched in a separate console window.");
                        } else {
                            File logFile = new File(buildDir, "remold/remold.log");
                            new ProcessBuilder(conf.getExecutable(), "@" + argsFile.getAbsolutePath())
                                    .directory(project.getProjectDir())
                                    .redirectOutput(logFile)
                                    .redirectErrorStream(true)
                                    .start();
                            project.getLogger().lifecycle("[Remold] Application launched in background. Output log: " + logFile.getAbsolutePath());
                        }
                    } catch (IOException e) {
                        throw new GradleException("[Remold] Failed to start application process", e);
                    }
                    project.getLogger().lifecycle("[Remold] Gradle Daemon released (IDLE). Run ./gradlew compileJava in terminal anytime to trigger hot-reload.");
                });
            });

            // Hook afterEvaluate
            project.afterEvaluate(p -> {
                boolean shouldAutoDeploy = RemoldConfigResolver.shouldAutoDeploy(project, ext);

                // Configure toolchain unset on runRemold if custom JBR provided
                String[] execAndHome = RemoldConfigResolver.resolveExecutableAndHome(project, ext);
                if (!"java".equals(execAndHome[0])) {
                    project.getTasks().named("runRemold", JavaExec.class, t -> {
                        t.getJavaLauncher().set((org.gradle.jvm.toolchain.JavaLauncher) null);
                    });
                }

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
                            boolean isLoom = project.getPlugins().hasPlugin("fabric-loom") || project.getExtensions().findByName("loom") != null;
                            File out = IdeaRunConfigGenerator.generate(project, conf, shouldAutoDeploy, isLoom);
                            if (out != null) {
                                project.getLogger().lifecycle("[Remold] IntelliJ IDEA run configuration ready: " + out.getAbsolutePath());
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }

                // Fabric Loom auto injection (client + dedicated server)
                if (project.getPlugins().hasPlugin("fabric-loom") || project.getExtensions().findByName("loom") != null) {
                    try {
                        Object loom = project.getExtensions().findByName("loom");
                        if (loom != null) {
                            Method getRuns = loom.getClass().getMethod("getRuns");
                            injectIntoLoaderRuns(project, resolver.resolve(project, ext), getRuns.invoke(loom), "Fabric Loom");
                        }
                    } catch (Throwable ignored) {
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
        });
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
