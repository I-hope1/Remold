package io.github.ihope1.remold.gradle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Structural contract of a launchable {@code remold-agent.jar}.
 *
 * <p>The version label alone cannot decide whether a jar is usable: the published {@code v0.2.8}
 * release declares {@code Agent-Class: remold.RemoldAgent} plus {@code Remold-Version: 0.2.8}, i.e.
 * it passes every version comparison while being a structurally older artifact. Launching it makes
 * hot swap look healthy and reflective entry points fail, so these tests pin both directions: a
 * current-shape jar must pass, and a same-version-but-old-shape jar must be rejected with an
 * actionable message.</p>
 */
class RemoldAgentJarCheckTest {

    @TempDir
    File tempDir;

    /** A jar with the given entry point and marker presence (see {@link TestAgentJars#write}). */
    private File writeAgentJar(String version, String entryClass, boolean withMarker) throws IOException {
        String name = "remold-agent-" + version + "-" + System.nanoTime() + ".jar";
        return TestAgentJars.write(tempDir, name, version, entryClass, withMarker);
    }

    @Test
    void currentShapeJarPasses() throws Exception {
        File jar = writeAgentJar("0.2.8", RemoldAgentJarCheck.EXPECTED_ENTRY_CLASS, true);
        List<String> problems = RemoldAgentJarCheck.problems(jar);
        Assertions.assertTrue(problems.isEmpty(), "a current-shape agent jar must pass, but got: " + problems);
    }

    @Test
    void legacyEntryPointIsRejectedEvenWhenTheVersionMatches() throws Exception {
        // Exactly the published v0.2.8 shape: same version label, legacy entry point, no marker API.
        File jar = writeAgentJar("0.2.8", RemoldAgentJarCheck.LEGACY_ENTRY_CLASS, false);
        List<String> problems = RemoldAgentJarCheck.problems(jar);

        Assertions.assertFalse(problems.isEmpty(), "a legacy agent jar must not pass silently");
        String joined = String.join("\n", problems);
        Assertions.assertTrue(joined.contains(RemoldAgentJarCheck.LEGACY_ENTRY_CLASS),
                "the report must name the legacy entry point it found, got: " + joined);
        Assertions.assertTrue(joined.contains(RemoldAgentJarCheck.EXPECTED_ENTRY_CLASS),
                "the report must name the entry point the build expects, got: " + joined);
        Assertions.assertTrue(joined.contains("lastResultFor"),
                "the report must name the missing capability, got: " + joined);
    }

    @Test
    void missingMarkerApiIsRejectedEvenWithTheRightEntryPoint() throws Exception {
        File jar = writeAgentJar("0.2.8", RemoldAgentJarCheck.EXPECTED_ENTRY_CLASS, false);
        List<String> problems = RemoldAgentJarCheck.problems(jar);
        Assertions.assertEquals(1, problems.size(), "only the missing capability may be reported: " + problems);
        Assertions.assertTrue(problems.get(0).contains("lastResultFor"), problems.get(0));
    }

    @Test
    void unverifiableOrAbsentJarsAreRejected() throws Exception {
        File noVersion = writeAgentJar(null, RemoldAgentJarCheck.EXPECTED_ENTRY_CLASS, true);
        List<String> problems = RemoldAgentJarCheck.problems(noVersion);
        Assertions.assertTrue(String.join("\n", problems).contains("no Remold-Version"),
                "a jar without version metadata cannot be verified: " + problems);

        File garbage = new File(tempDir, "garbage.jar");
        java.nio.file.Files.write(garbage.toPath(), new byte[]{1, 2, 3, 4});
        Assertions.assertFalse(RemoldAgentJarCheck.problems(garbage).isEmpty(), "an unreadable jar must be rejected");
        Assertions.assertFalse(RemoldAgentJarCheck.problems(new File(tempDir, "absent.jar")).isEmpty(),
                "a missing jar must be rejected");
        Assertions.assertFalse(RemoldAgentJarCheck.problems(null).isEmpty(), "a null jar must be rejected");
    }

    @Test
    void failureMessageTellsHowToFixIt() throws Exception {
        File jar = writeAgentJar("0.2.8", RemoldAgentJarCheck.LEGACY_ENTRY_CLASS, false);
        List<String> problems = RemoldAgentJarCheck.problems(jar);
        String message = RemoldAgentJarCheck.failureMessage(jar, problems, "0.2.8");

        Assertions.assertTrue(message.contains("-Premold.agentJar="), "must name the override property: " + message);
        Assertions.assertTrue(message.contains("core:shadowJar"), "must name the task that builds a good jar");
        Assertions.assertTrue(message.contains("NoSuchMethodException"),
                "must name the false-green symptom users actually see");
        Assertions.assertTrue(message.contains("-Premold.agentJarCheck=warn"),
                "must document the escape hatch for an intentional old pin");
        Assertions.assertTrue(message.contains(jar.getAbsolutePath()), "must name the offending jar path");
    }

    @Test
    void checkModeParsingIsExplicit() {
        Assertions.assertEquals("fail", RemoldConfigResolver.agentJarCheckMode(null),
                "with no project there is no property, so the default must stay fail-closed");
    }

    @Test
    void twoFilesAreComparedByContentNotByName() throws Exception {
        File good = writeAgentJar("0.2.8", RemoldAgentJarCheck.EXPECTED_ENTRY_CLASS, true);
        File bad = writeAgentJar("0.2.8", RemoldAgentJarCheck.LEGACY_ENTRY_CLASS, false);
        List<String> results = new ArrayList<>();
        results.add("good=" + RemoldAgentJarCheck.problems(good).isEmpty());
        results.add("bad=" + RemoldAgentJarCheck.problems(bad).isEmpty());
        Assertions.assertEquals(List.of("good=true", "bad=false"), results,
                "the check must depend on the jar contents, not on its file name (both are remold-agent-0.2.8-*.jar)");
    }
}
