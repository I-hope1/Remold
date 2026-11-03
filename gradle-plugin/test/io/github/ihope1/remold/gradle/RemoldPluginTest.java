package io.github.ihope1.remold.gradle;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import org.gradle.api.Project;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoldPluginTest {

    @TempDir
    File tempDir;

    @Test
    void appliesPluginAndRegistersExtensionAndTasks() {
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir)
                .build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(RemoldPlugin.class);

        RemoldExtension ext = (RemoldExtension) project.getExtensions().findByName("remold");
        Assertions.assertNotNull(ext, "remold extension should be registered");

        // Verify task registrations
        Assertions.assertNotNull(project.getTasks().findByName("runRemold"), "runRemold task should be registered");
        Assertions.assertNotNull(project.getTasks().findByName("deployArtifact"), "deployArtifact task should be registered");
        Assertions.assertNotNull(project.getTasks().findByName("deployMod"), "deployMod task should be registered");
        Assertions.assertNotNull(project.getTasks().findByName("generateIdeaRunConfig"), "generateIdeaRunConfig task should be registered");
        Assertions.assertNotNull(project.getTasks().findByName("generateRemoldScript"), "generateRemoldScript task should be registered");
        Assertions.assertNotNull(project.getTasks().findByName("startRemold"), "startRemold task should be registered");

        JavaCompile compileJava = (JavaCompile) project.getTasks().getByName("compileJava");
        Assertions.assertTrue(compileJava.getOptions().isDebug(), "compileJava should have debug enabled");
        Assertions.assertEquals("source,lines,vars", compileJava.getOptions().getDebugOptions().getDebugLevel(), "debugLevel should be source,lines,vars");
    }

    @Test
    void extensionDslAndConfigResolution() throws Exception {
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir)
                .build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(RemoldPlugin.class);

        RemoldExtension ext = (RemoldExtension) project.getExtensions().getByName("remold");
        ext.setMainClass("com.example.Main");
        ext.setVersion("0.2.1");
        ext.plugins("arc", "mixin");
        ext.args("arg1", "arg2");
        ext.setDebug(true);
        ext.setDebugPort(5006);

        // Create dummy agent jar locally to satisfy resolution without remote download
        File dummyAgent = new File(tempDir, "remold-agent.jar");
        Files.write(dummyAgent.toPath(), new byte[2048]);

        RemoldConfigResolver resolver = new RemoldConfigResolver();
        RemoldResolvedConfig conf = resolver.resolve(project, ext);

        Assertions.assertEquals("com.example.Main", conf.getMainClass());
        Assertions.assertEquals(dummyAgent.getName(), conf.getAgentFile().getName());
        Assertions.assertEquals(Arrays.asList("arg1", "arg2"), conf.getAppArgs());
        Assertions.assertTrue(conf.getJvmArgs().stream().anyMatch(a -> a.contains("-agentlib:jdwp") && a.contains("5006")), "JDWP port should be 5006");
        Assertions.assertTrue(conf.getJvmArgs().stream().anyMatch(a -> a.contains("-Dremold.plugins=arc,mixin")), "Plugins arg should be assembled");

        // Verify resolver caching idempotency
        RemoldResolvedConfig cached = resolver.resolve(project, ext);
        Assertions.assertSame(conf, cached, "RemoldConfigResolver should return cached instance");
    }

    @Test
    void autoDeployResolution() {
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir)
                .build();
        project.getPluginManager().apply("java");
        RemoldExtension ext = new RemoldExtension();

        Assertions.assertFalse(RemoldConfigResolver.shouldAutoDeploy(project, ext));
        Assertions.assertNull(RemoldConfigResolver.resolveDeployDir(project, ext));

        ext.deployDir(new File(tempDir, "mods"));
        Assertions.assertTrue(RemoldConfigResolver.shouldAutoDeploy(project, ext));
        Assertions.assertEquals(new File(tempDir, "mods").getAbsolutePath(), RemoldConfigResolver.resolveDeployDir(project, ext).getAbsolutePath());
    }

    @Test
    void ideaRunConfigGeneratorPortabilityAndXml() throws Exception {
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir)
                .build();
        project.getPluginManager().apply("java");

        File dummyAgent = new File(tempDir, "agent.jar");
        Files.write(dummyAgent.toPath(), new byte[2048]);

        RemoldResolvedConfig conf = new RemoldResolvedConfig(
                "com.example.App",
                "java",
                new File(tempDir, "dummy-jdk").getAbsolutePath(),
                dummyAgent,
                Collections.emptyList(),
                "",
                Arrays.asList("-javaagent:" + dummyAgent.getAbsolutePath()),
                Arrays.asList("hello"),
                project.files(),
                null
        );

        File xml = IdeaRunConfigGenerator.generate(project, conf, false, false);
        Assertions.assertTrue(xml.isFile());
        String content = Files.readString(xml.toPath());
        Assertions.assertTrue(content.contains("type=\"Application\""));
        Assertions.assertTrue(content.contains("MAIN_CLASS_NAME\" value=\"com.example.App\""));
        Assertions.assertTrue(content.contains("$PROJECT_DIR$"), "Paths relative to rootDir should use $PROJECT_DIR$");
    }

    @Test
    void embeddedScriptResourcesArePresentAndValid() throws Exception {
        try (InputStream groovy = RemoldPlugin.class.getResourceAsStream("/remold/remold.gradle");
             InputStream kotlin = RemoldPlugin.class.getResourceAsStream("/remold/remold.gradle.kts")) {
            Assertions.assertNotNull(groovy, "remold.gradle resource must be bundled in plugin jar");
            Assertions.assertNotNull(kotlin, "remold.gradle.kts resource must be bundled in plugin jar");

            byte[] gBytes = groovy.readAllBytes();
            byte[] kBytes = kotlin.readAllBytes();
            Assertions.assertTrue(gBytes.length > 1000, "remold.gradle should be a non-trivial script");
            Assertions.assertTrue(kBytes.length > 1000, "remold.gradle.kts should be a non-trivial script");
        }
    }
}
