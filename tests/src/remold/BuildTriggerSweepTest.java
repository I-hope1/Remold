package remold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuildTriggerSweep} 的过滤契约：递归 class、根级 jar、mtime 下界、忽略谓词、重叠目录去重。
 */
class BuildTriggerSweepTest {

	private static final long NOW = 1_700_000_100_000L;

	private static void write(Path p, String content, long mtimeMillis) throws Exception {
		Files.createDirectories(p.getParent());
		Files.write(p, content.getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(p, FileTime.fromMillis(mtimeMillis));
	}

	@Test
	void collectsRecentClassesRecursivelyAndRootArchivesOnly(@TempDir Path root) throws Exception {
		write(root.resolve("a/A.class"), "a", NOW);
		write(root.resolve("deep/b/B.class"), "b", NOW);
		write(root.resolve("lib.jar"), "jar", NOW);
		write(root.resolve("nested/pkg/nested.jar"), "jar", NOW);

		BuildTriggerSweep.Result result = BuildTriggerSweep.collect(
			Set.of(root), Collections.emptySet(), NOW - 1_000L, p -> false);

		assertEquals(2, result.classes.size(), result.classes.toString());
		assertEquals(1, result.jars.size(), result.jars.toString());
		assertTrue(result.jars.get(0).getFileName().toString().equals("lib.jar"));
	}

	@Test
	void excludesOldFilesButIncludesTheBoundary(@TempDir Path root) throws Exception {
		write(root.resolve("old/Old.class"), "old", NOW - 10_000L);
		write(root.resolve("edge/Edge.class"), "edge", NOW);

		BuildTriggerSweep.Result result = BuildTriggerSweep.collect(
			Set.of(root), Collections.emptySet(), NOW, p -> false);

		assertEquals(1, result.classes.size(), result.classes.toString());
		assertTrue(result.classes.get(0).getFileName().toString().equals("Edge.class"));
	}

	@Test
	void ignoredPathsAreNotCollected(@TempDir Path root) throws Exception {
		write(root.resolve("keep/Keep.class"), "keep", NOW);
		write(root.resolve("mods/Excluded.class"), "excluded", NOW);

		BuildTriggerSweep.Result result = BuildTriggerSweep.collect(
			Set.of(root), Collections.emptySet(), NOW - 1_000L,
			p -> p.toString().replace('\\', '/').contains("/mods/"));

		assertEquals(1, result.classes.size(), result.classes.toString());
		assertTrue(result.classes.get(0).getFileName().toString().equals("Keep.class"));
	}

	@Test
	void directJarsAreIncludedWhenRecent(@TempDir Path root) throws Exception {
		Path jar = root.resolve("direct.jar");
		write(jar, "jar", NOW);

		BuildTriggerSweep.Result result = BuildTriggerSweep.collect(
			Collections.emptySet(), Set.of(jar), NOW - 1_000L, p -> false);

		assertEquals(0, result.classes.size());
		assertEquals(1, result.jars.size(), result.jars.toString());
	}

	@Test
	void overlappingDirectoriesAreDeduplicated(@TempDir Path root) throws Exception {
		write(root.resolve("child/C.class"), "c", NOW);
		Set<Path> dirs = new LinkedHashSet<>();
		dirs.add(root);
		dirs.add(root.resolve("child"));

		BuildTriggerSweep.Result result = BuildTriggerSweep.collect(
			dirs, Collections.emptySet(), NOW - 1_000L, p -> false);

		assertEquals(1, result.classes.size(), result.classes.toString());
	}

	@Test
	void missingWatchDirectoryIsTolerated(@TempDir Path root) {
		Path missing = root.resolve("does-not-exist");
		BuildTriggerSweep.Result result = BuildTriggerSweep.collect(
			Set.of(missing), Collections.emptySet(), NOW, p -> false);

		assertTrue(result.isEmpty());
	}

	@Test
	void emptyInputsProduceEmptyResult() {
		BuildTriggerSweep.Result result = BuildTriggerSweep.collect(
			Collections.emptySet(), Collections.emptySet(), NOW, p -> false);

		assertTrue(result.isEmpty());
		assertEquals(0, result.size());
		assertFalse(result.classes.iterator().hasNext());
	}
}
