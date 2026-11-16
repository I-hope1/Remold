package io.github.ihope1.remold.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.UnexpectedBuildFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end wiring of the build-completion marker: a real {@code classes} build must write it,
 * configuration-cache reuse must still write it on the second run, and {@code buildTrigger = false}
 * must not write it at all.
 */
class BuildTriggerTestKitTest {

    @Test
    void classesWritesMarkerAndConfigurationCacheStillWritesItOnReuse(@TempDir Path projectDir) throws IOException {
        createMinimalAgentJar(projectDir.resolve("libs/test-agent.jar"));
        writeJavaMain(projectDir);
        write(projectDir.resolve("settings.gradle"), "rootProject.name = 'build-trigger-cc'\n");
        write(projectDir.resolve("build.gradle"), String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'io.github.i-hope1.remold'",
                "}",
                "remold {",
                "    agentJar = 'libs/test-agent.jar'",
                "    mainClass = 'demo.Main'",
                "}",
                ""));

        BuildResult first = run(projectDir, "classes", "--configuration-cache", "--rerun-tasks");
        assertTrue(first.getOutput().contains("Configuration cache entry stored"),
                "first run must store the configuration cache entry:\n" + first.getOutput());
        Path marker = BuildTriggerWriter.markerFile(projectDir.resolve("build").toFile()).toPath();
        assertTrue(Files.isRegularFile(marker), "classes must write the build-trigger marker: " + marker);
        String firstContent = Files.readString(marker, StandardCharsets.UTF_8);
        long firstEnd = endMillis(firstContent);
        String firstBuildId = buildId(firstContent);

        BuildResult second = run(projectDir, "classes", "--configuration-cache", "--rerun-tasks");
        assertTrue(second.getOutput().contains("Reusing configuration cache"),
                "second run must reuse the configuration cache entry:\n" + second.getOutput());
        String secondContent = Files.readString(marker, StandardCharsets.UTF_8);
        long secondEnd = endMillis(secondContent);
        String secondBuildId = buildId(secondContent);
        assertTrue(secondEnd >= firstEnd, "second marker must carry a fresh end timestamp: "
                + firstContent + " / " + secondContent);
        assertFalse(firstBuildId.equals(secondBuildId), "each build must have a distinct build_id");
        assertTrue(secondContent.startsWith("remold-build-trigger v1"), secondContent);
    }

    @Test
    void buildTriggerFalseDoesNotWriteMarker(@TempDir Path projectDir) throws IOException {
        createMinimalAgentJar(projectDir.resolve("libs/test-agent.jar"));
        writeJavaMain(projectDir);
        write(projectDir.resolve("settings.gradle"), "rootProject.name = 'build-trigger-off'\n");
        write(projectDir.resolve("build.gradle"), String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'io.github.i-hope1.remold'",
                "}",
                "remold {",
                "    agentJar = 'libs/test-agent.jar'",
                "    mainClass = 'demo.Main'",
                "    buildTrigger = false",
                "}",
                ""));

        run(projectDir, "classes", "--rerun-tasks");
        Path marker = BuildTriggerWriter.markerFile(projectDir.resolve("build").toFile()).toPath();
        assertFalse(Files.exists(marker), "buildTrigger=false must not write the marker: " + marker);
    }

    @Test
    void failedCompilationDoesNotWriteMarker(@TempDir Path projectDir) throws IOException {
        createMinimalAgentJar(projectDir.resolve("libs/test-agent.jar"));
        write(projectDir.resolve("src/main/java/demo/Main.java"), String.join("\n",
                "package demo;",
                "public class Main {",
                "    this is not java",
                "}",
                ""));
        write(projectDir.resolve("settings.gradle"), "rootProject.name = 'build-trigger-break'\n");
        write(projectDir.resolve("build.gradle"), String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'io.github.i-hope1.remold'",
                "}",
                "remold {",
                "    agentJar = 'libs/test-agent.jar'",
                "    mainClass = 'demo.Main'",
                "}",
                ""));

        try {
            run(projectDir, "classes", "--rerun-tasks");
        } catch (UnexpectedBuildFailure expected) {
            // classes must fail; the assertion below is about the marker, not the failure mode.
        }
        Path marker = BuildTriggerWriter.markerFile(projectDir.resolve("build").toFile()).toPath();
        assertFalse(Files.exists(marker), "a failed compile must not produce a marker: " + marker);
    }

    private static long endMillis(String content) {
        for (String line : content.split("\n")) {
            if (line.startsWith("build_end_millis=")) {
                return Long.parseLong(line.substring("build_end_millis=".length()).trim());
            }
        }
        throw new AssertionError("marker has no build_end_millis: " + content);
    }

    private static String buildId(String content) {
        for (String line : content.split("\n")) {
            if (line.startsWith("build_id=")) {
                return line.substring("build_id=".length()).trim();
            }
        }
        throw new AssertionError("marker has no build_id: " + content);
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
        write(projectDir.resolve("src/main/java/demo/Main.java"), String.join("\n",
                "package demo;",
                "public class Main {",
                "    public static void main(String[] args) { }",
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
