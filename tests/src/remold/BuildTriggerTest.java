package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RemoldAgent#handleBuildTriggerFile(Path)} 的确定性契约：
 * marker 扫描入队、sticky due、陈旧 marker 忽略、空扫描不自旋、重复 marker 只处理一次、
 * 以及"marker 取消兜底"的方向性断言。全部在无 Instrumentation 的测试 JVM 内完成，
 * 不启动真实 watcher、不执行重定义。
 */
class BuildTriggerTest {

	@TempDir
	Path tmp;

	private Path watchDir;
	private Path marker;

	@BeforeEach
	void setUp() throws Exception {
		RemoldAgent.resetBuildTriggerForTest();
		RemoldAgent.activeWatchDirs.clear();
		RemoldAgent.activeWatchJars.clear();

		watchDir = tmp.resolve("classes");
		marker = tmp.resolve("build/remold/build-trigger");
		Files.createDirectories(watchDir);
		Files.createDirectories(marker.getParent());
		RemoldAgent.BUILD_TRIGGER_FILE = marker.toAbsolutePath().normalize();
		RemoldAgent.activeWatchDirs.add(watchDir.toAbsolutePath().normalize());
	}

	@AfterEach
	void tearDown() {
		RemoldAgent.resetBuildTriggerForTest();
		RemoldAgent.activeWatchDirs.clear();
		RemoldAgent.activeWatchJars.clear();
	}

	private Path writeMarker(long endMillis, String buildId) throws Exception {
		Files.write(marker, BuildTriggerMarker.format(endMillis, buildId, "classes")
			.getBytes(StandardCharsets.UTF_8));
		return marker;
	}

	private Path writeClass(String relative) throws Exception {
		Path p = watchDir.resolve(relative);
		Files.createDirectories(p.getParent());
		Files.write(p, new byte[]{1, 2, 3});
		return p.toAbsolutePath().normalize();
	}

	private boolean classDue() {
		return RemoldAgent.debounceForTest().isClassDue(System.nanoTime());
	}

	@Test
	void markerSweepsRecentClassAndMarksDueImmediately() throws Exception {
		Path clazz = writeClass("demo/Subject.class");
		long end = System.currentTimeMillis();
		RemoldAgent.handleBuildTriggerFile(writeMarker(end, "classes-1"));

		assertTrue(RemoldAgent.pendingChanges.contains(clazz), RemoldAgent.pendingChanges.toString());
		assertTrue(classDue(), "marker must latch the class stream to due-now");
		assertEquals(0L, RemoldAgent.debounceForTest().nextDelayNanos(System.nanoTime()));
	}

	@Test
	void staleMarkerIsIgnoredWithoutSideEffects() throws Exception {
		Path clazz = writeClass("demo/Old.class");
		long staleEnd = RemoldAgent.AGENT_START_MILLIS
		                - BuildTriggerMarker.CLOCK_SKEW_MILLIS - 5_000L;
		RemoldAgent.handleBuildTriggerFile(writeMarker(staleEnd, "classes-stale"));

		assertFalse(RemoldAgent.pendingChanges.contains(clazz));
		assertTrue(RemoldAgent.pendingChanges.isEmpty(), RemoldAgent.pendingChanges.toString());
		assertFalse(classDue(), "a rejected marker must not latch the class stream");
	}

	@Test
	void markerWithoutChangedFilesDoesNotLatchOrSpin() throws Exception {
		RemoldAgent.handleBuildTriggerFile(writeMarker(System.currentTimeMillis(), "classes-empty"));

		assertTrue(RemoldAgent.pendingChanges.isEmpty(), RemoldAgent.pendingChanges.toString());
		assertFalse(classDue(), "an empty sweep must not latch the class stream");
		assertEquals(-1L, RemoldAgent.debounceForTest().nextDelayNanos(System.nanoTime()));
	}

	@Test
	void duplicateMarkerIsProcessedOnlyOnce() throws Exception {
		Path clazz = writeClass("demo/Dup.class");
		long end = System.currentTimeMillis();
		Path m = writeMarker(end, "classes-dup");

		RemoldAgent.handleBuildTriggerFile(m);
		assertTrue(RemoldAgent.pendingChanges.contains(clazz));

		synchronized (RemoldAgent.pendingChanges) {
			RemoldAgent.pendingChanges.clear();
		}
		RemoldAgent.debounceForTest().clearAll();

		RemoldAgent.handleBuildTriggerFile(m);
		assertTrue(RemoldAgent.pendingChanges.isEmpty(),
			"the same build_id must not be processed twice: " + RemoldAgent.pendingChanges);
		assertFalse(classDue());
	}

	@Test
	void lateClassEventCannotPostponeLatchedDue() throws Exception {
		Path clazz = writeClass("demo/Late.class");
		RemoldAgent.handleBuildTriggerFile(writeMarker(System.currentTimeMillis(), "classes-late"));
		assertTrue(classDue());

		// A late event from a different watcher thread must not push the deadline back to now + window.
		RemoldAgent.handleFileChange(clazz);
		assertTrue(classDue(), "sticky due must survive a late class event");
		assertEquals(0L, RemoldAgent.debounceForTest().nextDelayNanos(System.nanoTime()));
	}

	@Test
	void secondMarkerDoesNotResweepFilesAcceptedByTheFirst() throws Exception {
		Path clazz = writeClass("demo/Once.class");
		long firstEnd = System.currentTimeMillis();
		Files.setLastModifiedTime(clazz, FileTime.fromMillis(firstEnd - 50L));
		RemoldAgent.handleBuildTriggerFile(writeMarker(firstEnd, "classes-first"));
		assertTrue(RemoldAgent.pendingChanges.contains(clazz));

		synchronized (RemoldAgent.pendingChanges) {
			RemoldAgent.pendingChanges.clear();
		}
		RemoldAgent.debounceForTest().clearAll();

		long secondEnd = firstEnd + 1_000L;
		RemoldAgent.handleBuildTriggerFile(writeMarker(secondEnd, "classes-second"));

		assertTrue(RemoldAgent.pendingChanges.isEmpty(),
			"a later marker must not re-sweep files already accepted by the previous marker: "
				+ RemoldAgent.pendingChanges);
		assertFalse(classDue());
	}

	@Test
	void markerIsIndependentOfTheFallbackWindowValue() throws Exception {
		int previous = RemoldAgent.getFileShakeMs();
		try {
			RemoldAgent.setFileShakeMs(5_000);
			Path clazz = writeClass("demo/Slow.class");
			RemoldAgent.handleBuildTriggerFile(writeMarker(System.currentTimeMillis(), "classes-slow"));

			assertTrue(RemoldAgent.pendingChanges.contains(clazz));
			assertTrue(classDue(), "marker must trigger immediately even with a long fallback window");
		} finally {
			RemoldAgent.setFileShakeMs(previous);
		}
	}
}
