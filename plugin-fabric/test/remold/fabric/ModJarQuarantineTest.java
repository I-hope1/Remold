package remold.fabric;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stale same-mod JARs are quarantined, not just reported.
 *
 * <p>Why this matters (reported from a real run): a packaged JAR left in {@code run/mods} makes the
 * loader treat it as a candidate container for the in-development mod, so the <b>JAR's old
 * bytecode</b> can be what actually runs while {@code build/classes} is ignored — silently, with no
 * error. Warning alone does not change that behaviour, hence the rename.</p>
 *
 * <p>Safety properties locked here: the file is renamed in place (nothing is deleted), a
 * pre-existing quarantine name is never overwritten, failures degrade to "not quarantined", and the
 * opt-out leaves the file alone.</p>
 */
class ModJarQuarantineTest {

	@Test
	void renamesInPlaceInsteadOfDeleting(@TempDir Path dir) throws IOException {
		Path jar = writeJar(dir, "example_mod-0.9.0.jar", "stale-bytes");

		Path quarantined = ModJarInspector.quarantine(jar);

		assertNotNull(quarantined, "quarantine must succeed for a writable jar");
		assertFalse(Files.exists(jar), "the original .jar name must be gone so no scanner picks it up");
		assertTrue(Files.exists(quarantined), "the bytes must survive under the quarantine name");
		assertTrue(quarantined.getFileName().toString().endsWith(ModJarInspector.QUARANTINE_SUFFIX),
			"quarantine name must not end with .jar, otherwise the loader still sees it");
		assertEquals("stale-bytes", Files.readString(quarantined), "content must be untouched");
	}

	@Test
	void neverOverwritesAnExistingQuarantine(@TempDir Path dir) throws IOException {
		Path jar = writeJar(dir, "example_mod-0.9.0.jar", "new-bytes");
		Path existing = dir.resolve("example_mod-0.9.0.jar" + ModJarInspector.QUARANTINE_SUFFIX);
		Files.write(existing, "old-bytes".getBytes(StandardCharsets.UTF_8));

		Path quarantined = ModJarInspector.quarantine(jar);

		assertNotNull(quarantined);
		assertEquals("old-bytes", Files.readString(existing), "the earlier quarantine must survive");
		assertEquals("new-bytes", Files.readString(quarantined), "the new file keeps its own bytes");
		assertFalse(existing.equals(quarantined), "a second quarantine must pick a distinct name");
	}

	@Test
	void missingSourceDegradesToNull(@TempDir Path dir) {
		assertNull(ModJarInspector.quarantine(dir.resolve("not-there.jar")),
			"a failing rename must return null so the caller falls back to a warning");
	}

	@Test
	void optOutLeavesTheJarInPlace(@TempDir Path dir) throws IOException {
		Path jar = writeJar(dir, "example_mod-0.9.0.jar", "stale-bytes");

		ModJarInspector.quarantineOrWarn(jar, "example_mod", false);

		assertTrue(Files.exists(jar), "with quarantine disabled the file must stay where it was");
	}
	
	private static Path writeJar(Path dir, String name, String content) throws IOException {
		Path p = dir.resolve(name);
		Files.write(p, content.getBytes(StandardCharsets.UTF_8));
		return p;
	}
}
