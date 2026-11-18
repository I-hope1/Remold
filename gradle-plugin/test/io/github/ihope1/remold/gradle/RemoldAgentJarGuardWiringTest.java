package io.github.ihope1.remold.gradle;

import java.io.File;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wiring of the agent-jar structural check into {@link RemoldConfigResolver#resolve}.
 *
 * <p>{@link RemoldAgentJarCheckTest} locks the pure inspection rules; this class locks the decision
 * that the resolver actually acts on them — a legacy jar must stop the build with an actionable
 * message, and the documented escape hatch must keep an intentional old pin usable.</p>
 */
class RemoldAgentJarGuardWiringTest {

    @TempDir
    File tempDir;

    private Project project() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(RemoldPlugin.class);
        return project;
    }

    private RemoldExtension extension(Project project) {
        RemoldExtension ext = (RemoldExtension) project.getExtensions().getByName("remold");
        ext.setMainClass("com.example.Main");
        ext.setVersion("0.2.8");
        return ext;
    }

    @Test
    void legacyAgentJarStopsResolutionWithActionableMessage() throws Exception {
        Project project = project();
        RemoldExtension ext = extension(project);
        // Same version label as the published v0.2.8 release, older structure: the false-green case.
        TestAgentJars.legacy(tempDir, "0.2.8", "remold-agent.jar");

        GradleException failure = Assertions.assertThrows(GradleException.class,
                () -> new RemoldConfigResolver().resolve(project, ext),
                "a legacy agent jar must not be launched silently");

        String message = failure.getMessage();
        Assertions.assertTrue(message.contains("remold.agentJar") || message.contains("agentJar"),
                "the message must name the override property: " + message);
        Assertions.assertTrue(message.contains(RemoldAgentJarCheck.EXPECTED_ENTRY_CLASS),
                "the message must name the entry point this build expects: " + message);
        Assertions.assertTrue(message.contains("remold-agent.jar"),
                "the message must name the offending jar: " + message);
    }

    @Test
    void currentAgentJarResolves() throws Exception {
        Project project = project();
        RemoldExtension ext = extension(project);
        File agent = TestAgentJars.current(tempDir, "0.2.8", "remold-agent.jar");

        RemoldResolvedConfig conf = new RemoldConfigResolver().resolve(project, ext);
        Assertions.assertNotNull(conf.getAgentFile(), "a current-shape agent jar must resolve");
        Assertions.assertEquals(agent.getName(), conf.getAgentFile().getName());
    }

    @Test
    void warnModeKeepsAnIntentionalOldPinUsable() throws Exception {
        Project project = project();
        RemoldExtension ext = extension(project);
        project.getExtensions().getExtraProperties().set("remold.agentJarCheck", "warn");
        TestAgentJars.legacy(tempDir, "0.2.8", "remold-agent.jar");

        RemoldResolvedConfig conf = new RemoldConfigResolver().resolve(project, ext);
        Assertions.assertNotNull(conf.getAgentFile(),
                "remold.agentJarCheck=warn must keep the build usable for an intentional old pin");
    }

    @Test
    void checkModeDefaultsToFailAndAcceptsDocumentedValues() {
        Assertions.assertEquals("fail", RemoldConfigResolver.agentJarCheckMode(null));

        Project project = project();
        Assertions.assertEquals("fail", RemoldConfigResolver.agentJarCheckMode(project),
                "no property means fail-closed");

        project.getExtensions().getExtraProperties().set("remold.agentJarCheck", "warn");
        Assertions.assertEquals("warn", RemoldConfigResolver.agentJarCheckMode(project));
        project.getExtensions().getExtraProperties().set("remold.agentJarCheck", "off");
        Assertions.assertEquals("off", RemoldConfigResolver.agentJarCheckMode(project));
        project.getExtensions().getExtraProperties().set("remold.agentJarCheck", "nonsense");
        Assertions.assertEquals("fail", RemoldConfigResolver.agentJarCheckMode(project),
                "an unrecognized value must fall back to the strict default");
    }
}
