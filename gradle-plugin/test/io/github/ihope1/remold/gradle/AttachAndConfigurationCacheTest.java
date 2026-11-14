package io.github.ihope1.remold.gradle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies real Gradle Configuration Cache reuse and {@code remold.attach(...)} across Groovy and Kotlin DSLs.
 */
class AttachAndConfigurationCacheTest {

    @Test
    void configurationCacheReusesBuiltInTasks(@TempDir Path projectDir) throws IOException {
        Path agentJar = projectDir.resolve("libs/test-agent.jar");
        createMinimalAgentJar(agentJar);
        writeJavaMain(projectDir);

        write(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"cc-built-in\"\n");
        write(projectDir.resolve("build.gradle.kts"), String.join("\n",
                "plugins {",
                "    java",
                "    id(\"io.github.i-hope1.remold\")",
                "}",
                "remold {",
                "    mainClass = \"demo.Main\"",
                "    agentJar = \"libs/test-agent.jar\"",
                "}",
                ""));

        BuildResult first = run(projectDir, "generateRemoldScript", "--configuration-cache", "--rerun-tasks");
        assertTrue(first.getOutput().contains("Configuration cache entry stored"),
                "First run must store Configuration Cache entry:\n" + first.getOutput());
        assertTrue(Files.isRegularFile(projectDir.resolve(".idea/runConfigurations/Run_with_Remold.xml")));
        assertTrue(Files.isRegularFile(projectDir.resolve("build/run-remold.sh")));
        assertTrue(Files.isRegularFile(projectDir.resolve("build/remold/remold.args")));

        BuildResult second = run(projectDir, "generateRemoldScript", "--configuration-cache", "--rerun-tasks");
        // First run of generateIdeaRunConfig creates .idea/, updating RemoldPlugin's .idea detection;
        // once .idea/ exists, subsequent runs must reuse the configuration cache cleanly.
        BuildResult third = run(projectDir, "generateRemoldScript", "--configuration-cache", "--rerun-tasks");
        assertTrue(third.getOutput().contains("Reusing configuration cache"),
                "Steady-state run must reuse Configuration Cache entry:\n"
                        + second.getOutput() + "\n---\n" + third.getOutput());

        BuildResult clean1 = run(projectDir, "cleanRemoldCache", "--configuration-cache");
        assertTrue(clean1.getOutput().contains("Configuration cache entry stored"),
                "First cleanRemoldCache must store CC entry:\n" + clean1.getOutput());
        BuildResult clean2 = run(projectDir, "cleanRemoldCache", "--configuration-cache");
        assertTrue(clean2.getOutput().contains("Reusing configuration cache"),
                "Second cleanRemoldCache must reuse CC entry:\n" + clean2.getOutput());
    }

    @Test
    void groovyAttachInjectsJavaExecAndPassesConfigurationCache(@TempDir Path projectDir) throws IOException {
        Path agentJar = projectDir.resolve("libs/test-agent.jar");
        createMinimalAgentJar(agentJar);
        writeJavaMain(projectDir);

        write(projectDir.resolve("settings.gradle"), "rootProject.name = 'attach-groovy'\n");
        write(projectDir.resolve("build.gradle"), String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'io.github.i-hope1.remold'",
                "}",
                "remold {",
                "    agentJar = 'libs/test-agent.jar'",
                "    attach 'customExec'",
                "}",
                "tasks.register('customExec', JavaExec) {",
                "    classpath = sourceSets.main.runtimeClasspath",
                "    mainClass = 'demo.Main'",
                "}",
                ""));

        BuildResult first = run(projectDir, "customExec", "--configuration-cache");
        String out1 = first.getOutput();
        assertTrue(out1.contains("Configuration cache entry stored"),
                "First customExec must store CC entry:\n" + out1);
        assertTrue(out1.contains("JVMARG=-javaagent:") && out1.contains("remold-agent.jar"),
                "Attached JavaExec must launch with -javaagent:\n" + out1);
        assertTrue(out1.contains("JVMARG=-XX:+AllowEnhancedClassRedefinition"),
                "Attached JavaExec must receive -XX:+AllowEnhancedClassRedefinition:\n" + out1);

        BuildResult second = run(projectDir, "customExec", "--configuration-cache", "--rerun-tasks");
        String out2 = second.getOutput();
        assertTrue(out2.contains("Reusing configuration cache"),
                "Second customExec must reuse CC entry:\n" + out2);
        assertTrue(out2.contains("JVMARG=-javaagent:") && out2.contains("remold-agent.jar"),
                "Cached customExec must still pass -javaagent:\n" + out2);
    }

    @Test
    void kotlinAttachSupportsTaskProviderDeduplicatesAndToleratesIdeaDirWithoutMainClass(@TempDir Path projectDir)
            throws IOException {
        Path agentJar = projectDir.resolve("libs/test-agent.jar");
        createMinimalAgentJar(agentJar);
        writeJavaMain(projectDir);
        Files.createDirectories(projectDir.resolve(".idea"));

        write(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"attach-kotlin\"\n");
        write(projectDir.resolve("build.gradle.kts"), String.join("\n",
                "plugins {",
                "    java",
                "    id(\"io.github.i-hope1.remold\")",
                "}",
                "val customExec = tasks.register<JavaExec>(\"customExec\") {",
                "    classpath = sourceSets.main.get().runtimeClasspath",
                "    mainClass.set(\"demo.Main\")",
                "}",
                "remold {",
                "    agentJar = \"libs/test-agent.jar\"",
                "    attach(customExec)",
                "    attach(\"customExec\")",
                "}",
                ""));

        BuildResult classesResult = run(projectDir, "classes", "--configuration-cache");
        assertTrue(classesResult.getOutput().contains("BUILD SUCCESSFUL"),
                "classes in attach-only .idea project must not fail on missing mainClass:\n" + classesResult.getOutput());

        BuildResult run1 = run(projectDir, "customExec", "--configuration-cache");
        String out1 = run1.getOutput();
        long agentArgCount = out1.lines().filter(l -> l.startsWith("JVMARG=-javaagent:")).count();
        assertEquals(1, agentArgCount, "Duplicate attach calls must only inject one -javaagent:\n" + out1);

        BuildResult run2 = run(projectDir, "customExec", "--configuration-cache", "--rerun-tasks");
        assertTrue(run2.getOutput().contains("Reusing configuration cache"),
                "Kotlin attach run must reuse CC entry:\n" + run2.getOutput());
        assertFalse(run2.getOutput().contains("Neither mainClass nor jar is configured"));
    }

    @Test
    void attachFailsClosedWhenTargetTaskMissing(@TempDir Path projectDir) throws IOException {
        Path agentJar = projectDir.resolve("libs/test-agent.jar");
        createMinimalAgentJar(agentJar);

        write(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"attach-missing\"\n");
        write(projectDir.resolve("build.gradle.kts"), String.join("\n",
                "plugins {",
                "    java",
                "    id(\"io.github.i-hope1.remold\")",
                "}",
                "remold {",
                "    agentJar = \"libs/test-agent.jar\"",
                "    attach(\"missingRunTask\")",
                "}",
                ""));

        BuildResult failed = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("tasks", "--console=plain")
                .buildAndFail();
        assertTrue(failed.getOutput().contains("Cannot attach to task 'missingRunTask': task does not exist"),
                "Missing attach target must fail closed with clear message:\n" + failed.getOutput());
    }

    @Test
    void loomGenerateRemoldScriptExtractsRunClientAndReusesConfigCache(@TempDir Path tempRoot) throws IOException {
        Path projectDir = tempRoot.resolve("ws/loom-demo");
        Files.createDirectories(projectDir);
        writeJavaMain(projectDir);
        Path agentJar = projectDir.resolve("libs/remold-agent.jar");
        createMinimalAgentJar(agentJar);

        write(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"loom-export-demo\"\n");
        String agentPathUnix = agentJar.toAbsolutePath().toString().replace('\\', '/');
        write(projectDir.resolve("build.gradle.kts"), String.join("\n",
                "plugins {",
                "    java",
                "    id(\"io.github.i-hope1.remold\")",
                "}",
                "extensions.add(\"loom\", Object())",
                "remold {",
                "    agentJar = \"libs/remold-agent.jar\"",
                "}",
                "val configureClientLaunch by tasks.registering {",
                "    doLast {",
                "        println(\"CONFIGURE_LAUNCH_RAN\")",
                "    }",
                "}",
                "tasks.register<JavaExec>(\"runClient\") {",
                "    dependsOn(configureClientLaunch, tasks.named(\"classes\"))",
                "    classpath = sourceSets[\"main\"].runtimeClasspath",
                "    mainClass.set(\"net.fabricmc.devlaunchinjector.Main\")",
                "    workingDir = file(\"run\")",
                "    jvmArgs(\"-Dfabric.dli.env=client\", \"-javaagent:" + agentPathUnix + "\")",
                "    args(\"--assetIndex\", \"1.21\")",
                "    environment(\"REMOLD_TEST_ENV\", \"loom-ok\")",
                "    doFirst {",
                "        println(\"RUN_CLIENT_SHOULD_NOT_RUN\")",
                "    }",
                "}",
                ""));

        BuildResult run1 = run(projectDir, "generateRemoldScript", "--configuration-cache");
        String out1 = run1.getOutput();
        assertTrue(out1.contains("CONFIGURE_LAUNCH_RAN"), "Must run Loom prerequisite task:\n" + out1);
        assertFalse(out1.contains("RUN_CLIENT_SHOULD_NOT_RUN"), "Must NOT execute runClient itself:\n" + out1);

        Path argsFile = projectDir.resolve("build/remold/remold.args");
        assertTrue(Files.exists(argsFile), "remold.args must be generated");
        List<String> lines = Files.readAllLines(argsFile, StandardCharsets.UTF_8);
        assertTrue(lines.contains("-Dfabric.dli.env=client"), "Missing DLI system property:\n" + lines);
        assertTrue(lines.contains("net.fabricmc.devlaunchinjector.Main"), "Missing DLI mainClass:\n" + lines);
        assertTrue(lines.contains("--assetIndex") && lines.contains("1.21"), "Missing Loom app args:\n" + lines);
        assertEquals(1, lines.stream().filter(l -> l.equals("-cp")).count(),
                "Must emit -cp exactly once (no duplicate from JavaExec.getAllJvmArgs):\n" + lines);
        assertEquals(1, lines.stream().filter(l -> l.contains("-javaagent:") && l.contains("remold-agent.jar")).count(),
                "Must deduplicate remold-agent.jar -javaagent:\n" + lines);

        String bat = new String(Files.readAllBytes(projectDir.resolve("build/run-remold.bat")), StandardCharsets.UTF_8);
        assertTrue(bat.contains("cd /d \""), "bat script must cd into Loom workingDir:\n" + bat);
        assertTrue(bat.contains("set \"REMOLD_TEST_ENV=loom-ok\""), "bat script must export custom env:\n" + bat);

        String sh = new String(Files.readAllBytes(projectDir.resolve("build/run-remold.sh")), StandardCharsets.UTF_8);
        assertTrue(sh.contains("/run\""), "sh script must cd into Loom workingDir:\n" + sh);
        assertTrue(sh.contains("export REMOLD_TEST_ENV='loom-ok'"), "sh script must export custom env:\n" + sh);

        BuildResult run2 = run(projectDir, "generateRemoldScript", "--configuration-cache", "--rerun-tasks");
        BuildResult run3 = run(projectDir, "generateRemoldScript", "--configuration-cache", "--rerun-tasks");
        assertTrue(run3.getOutput().contains("Reusing configuration cache"),
                "Steady-state Loom generateRemoldScript must reuse configuration cache:\n"
                        + run2.getOutput() + "\n---\n" + run3.getOutput());

        BuildResult runRemoldFailed = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("runRemold", "--console=plain")
                .buildAndFail();
        assertTrue(runRemoldFailed.getOutput().contains("startRemold"),
                "runRemold on Loom must fail with startRemold/runClient hint:\n" + runRemoldFailed.getOutput());
    }

    private static BuildResult run(Path projectDir, String... args) {
        String[] all = new String[args.length + 1];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = "--console=plain";
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(all)
                .build();
    }

    private static void writeJavaMain(Path projectDir) throws IOException {
        Path src = projectDir.resolve("src/main/java/demo/Main.java");
        Files.createDirectories(src.getParent());
        write(src, String.join("\n",
                "package demo;",
                "import java.lang.management.ManagementFactory;",
                "public class Main {",
                "    public static void main(String[] args) {",
                "        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {",
                "            System.out.println(\"JVMARG=\" + arg);",
                "        }",
                "    }",
                "}",
                ""));
    }

    private static void createMinimalAgentJar(Path jarPath) throws IOException {
        Files.createDirectories(jarPath.getParent());
        Path tmpSrcDir = jarPath.getParent().resolve("tmp-agent-src");
        Files.createDirectories(tmpSrcDir);
        Path javaFile = tmpSrcDir.resolve("Premain.java");
        write(javaFile, "public class Premain { public static void premain(String args) {} }\n");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null,
                "-d", tmpSrcDir.toAbsolutePath().toString(),
                javaFile.toAbsolutePath().toString());
        if (rc != 0) {
            throw new IOException("Failed to compile test Premain agent");
        }
        byte[] classBytes = Files.readAllBytes(tmpSrcDir.resolve("Premain.class"));

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), "Premain");
        manifest.getMainAttributes().put(new Attributes.Name("Can-Redefine-Classes"), "true");
        manifest.getMainAttributes().put(new Attributes.Name("Can-Retransform-Classes"), "true");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
            jos.putNextEntry(new JarEntry("Premain.class"));
            jos.write(classBytes);
            jos.closeEntry();
            // Pad above RemoldConfigResolver's 1024-byte agent validity threshold
            jos.putNextEntry(new JarEntry("padding.bin"));
            jos.write(new byte[2048]);
            jos.closeEntry();
        }
        Files.write(jarPath, baos.toByteArray());
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
