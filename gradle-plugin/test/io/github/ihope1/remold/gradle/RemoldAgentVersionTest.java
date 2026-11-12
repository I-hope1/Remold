package io.github.ihope1.remold.gradle;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Version metadata read from a local {@code remold-agent.jar}.
 *
 * <p>A local agent jar is the artifact that actually runs, so its manifest version is the ground
 * truth for "which release is this". The resolver adopts it when {@code remold.version} is absent
 * and warns when the configured version disagrees. These tests lock the manifest contract and the
 * comparison rules; the resolver wiring itself is exercised by {@code RemoldPluginTest}'s config
 * resolution test (where an unversioned dummy jar must simply be treated as unknown).</p>
 */
class RemoldAgentVersionTest {

    @TempDir
    File tempDir;

    @Test
    void readsDedicatedRemoldVersionAttribute() throws Exception {
        File jar = writeAgentJar("Remold-Version", "0.2.7", "Implementation-Version", "0.2.6");
        Assertions.assertEquals("0.2.7", RemoldConfigResolver.readAgentVersion(jar),
                "the dedicated Remold-Version attribute must win over Implementation-Version");
    }

    @Test
    void fallsBackToImplementationVersion() throws Exception {
        File jar = writeAgentJar("Implementation-Version", "0.2.7");
        Assertions.assertEquals("0.2.7", RemoldConfigResolver.readAgentVersion(jar));
    }

    @Test
    void missingMetadataIsUnknownNotAMatch() throws Exception {
        File jar = writeAgentJar();   // valid jar, no version attributes
        Assertions.assertNull(RemoldConfigResolver.readAgentVersion(jar));

        File garbage = new File(tempDir, "garbage.jar");
        Files.write(garbage.toPath(), new byte[]{1, 2, 3, 4});
        Assertions.assertNull(RemoldConfigResolver.readAgentVersion(garbage),
                "an unreadable jar must be reported as unknown, never as a matching version");
        Assertions.assertNull(RemoldConfigResolver.readAgentVersion(new File(tempDir, "absent.jar")));
        Assertions.assertNull(RemoldConfigResolver.readAgentVersion(null));
    }

    @Test
    void versionComparisonIgnoresTagPrefixButRejectsUnknown() {
        Assertions.assertTrue(RemoldConfigResolver.sameVersion("0.2.7", "v0.2.7"));
        Assertions.assertTrue(RemoldConfigResolver.sameVersion("v0.2.7", "V0.2.7"));
        Assertions.assertFalse(RemoldConfigResolver.sameVersion("0.2.7", "0.2.8"));
        Assertions.assertFalse(RemoldConfigResolver.sameVersion(null, "0.2.7"));
        Assertions.assertFalse(RemoldConfigResolver.sameVersion("", "0.2.7"));
        Assertions.assertFalse(RemoldConfigResolver.sameVersion("v", "0.2.7"));
    }

    @Test
    void refreshDecisionOnlyOnKnownMismatch() {
        // configured wins: a known-different local jar is refreshed
        Assertions.assertTrue(RemoldConfigResolver.shouldRefreshAgent(true, "0.2.7", "0.2.6"));
        // matching versions: keep the local jar (fast path / offline)
        Assertions.assertFalse(RemoldConfigResolver.shouldRefreshAgent(true, "0.2.7", "v0.2.7"));
        // no configured version: nothing to refresh to
        Assertions.assertFalse(RemoldConfigResolver.shouldRefreshAgent(false, null, "0.2.6"));
        // unknown local metadata: cannot prove a mismatch
        Assertions.assertFalse(RemoldConfigResolver.shouldRefreshAgent(true, "0.2.7", null));
    }

    /** Builds a real jar with the given manifest attribute pairs (none = manifest without version). */
    private File writeAgentJar(String... attributePairs) throws Exception {
        File jar = new File(tempDir, "remold-agent-" + System.nanoTime() + ".jar");
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        for (int i = 0; i + 1 < attributePairs.length; i += 2) {
            attrs.put(new Attributes.Name(attributePairs[i]), attributePairs[i + 1]);
        }
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
            out.flush();
        }
        // Sanity: the fixture really is a valid jar whose manifest is readable.
        try (JarFile check = new JarFile(jar)) {
            Assertions.assertNotNull(check.getManifest());
        }
        return jar;
    }
}
