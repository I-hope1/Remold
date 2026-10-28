package io.github.ihope1.remold.gradle;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoldPluginTest {

    @TempDir
    File tempDir;

    @Test
    void appliesGroovyScriptToProject() {
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir)
                .build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(RemoldPlugin.class);

        File script = new File(tempDir, ".gradle/remold/scripts/remold.gradle");
        Assertions.assertTrue(script.isFile(), "Materialized remold.gradle should exist");
        Assertions.assertTrue(script.length() > 0, "remold.gradle should not be empty");

        Assertions.assertNotNull(project.getExtensions().findByName("remold"), "remold extension should be registered");
        Assertions.assertNotNull(project.getTasks().findByName("runRemold"), "runRemold task should be registered");
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

    @Test
    void idempotencyPreservesScriptFile() throws Exception {
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir)
                .build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(RemoldPlugin.class);

        File script = new File(tempDir, ".gradle/remold/scripts/remold.gradle");
        long firstModified = script.lastModified();

        // Re-applying the plugin on the same project
        project.getPluginManager().apply(RemoldPlugin.class);
        Assertions.assertEquals(firstModified, script.lastModified(), "Re-applying with identical content should not re-write");
    }
}
