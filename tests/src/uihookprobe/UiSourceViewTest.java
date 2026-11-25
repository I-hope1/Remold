package uihookprobe;

import arc.scene.Element;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import remold.uihook.CellPropertyRef;
import remold.uihook.SourceSnippet;
import remold.uihook.UiSource;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract of the UI source view: excerpt window, target marker, clamping, source-root
 * resolution, and the tracked-cell lookup used by {@link UiSource}.
 *
 * <p>The integration cases call the tracker from this package (not {@code remold.*}) because
 * {@code CellPropertyRef.findHostContext} skips frames under {@code remold/} while resolving the
 * creation site; the host frame must therefore be ordinary user code.</p>
 */
class UiSourceViewTest {

	@AfterEach
	void resetTracker() {
		CellPropertyRef.setEnabled(false);
		CellPropertyRef.clearAll();
		restoreUiThread();
	}

	@Test
	void rendersFourLinesOfContextAroundTarget(@TempDir Path dir) throws Exception {
		Path file = writeLines(dir.resolve("MyUI.java"), 20);

		SourceSnippet snippet = SourceSnippet.fromFile(file, "example/MyUI", "build", 10, 4);

		assertEquals(10, snippet.targetLine());
		assertEquals(9, snippet.lines().size(), "4 lines of context on each side plus the target line");
		assertEquals(6, snippet.lines().get(0).number());
		assertEquals(14, snippet.lines().get(8).number());
		assertEquals("line-10", snippet.lines().get(4).text());

		String text = snippet.text();
		assertTrue(text.contains("example.MyUI#build:10"), text);
		assertTrue(text.contains("> 10 | line-10"), text);
		assertFalse(text.contains("> 6 |"), "the marker must stay on the target line: " + text);
	}

	@Test
	void clampsWindowAtFileStart(@TempDir Path dir) throws Exception {
		Path file = writeLines(dir.resolve("MyUI.java"), 20);

		SourceSnippet snippet = SourceSnippet.fromFile(file, "example/MyUI", "build", 2, 4);

		assertEquals(1, snippet.lines().get(0).number());
		assertEquals(6, snippet.lines().get(snippet.lines().size() - 1).number());
		assertEquals(6, snippet.lines().size());
		assertNull(snippet.note());
	}

	@Test
	void clampsWindowAtFileEnd(@TempDir Path dir) throws Exception {
		Path file = writeLines(dir.resolve("MyUI.java"), 20);

		SourceSnippet snippet = SourceSnippet.fromFile(file, "example/MyUI", "build", 19, 4);

		assertEquals(15, snippet.lines().get(0).number());
		assertEquals(20, snippet.lines().get(snippet.lines().size() - 1).number());
		assertEquals(6, snippet.lines().size());
	}

	@Test
	void zeroContextShowsOnlyTheTargetLine(@TempDir Path dir) throws Exception {
		Path file = writeLines(dir.resolve("MyUI.java"), 20);

		SourceSnippet snippet = SourceSnippet.fromFile(file, "example/MyUI", "build", 7, 0);

		assertEquals(1, snippet.lines().size());
		assertEquals(7, snippet.lines().get(0).number());
	}

	@Test
	void missingSourceFileYieldsANoteInsteadOfThrowing(@TempDir Path dir) {
		SourceSnippet snippet = SourceSnippet.at("example/MyUI", "build", 5, "MyUI.java", 4, List.of(dir));

		assertNull(snippet.sourcePath());
		assertTrue(snippet.lines().isEmpty());
		assertNotNull(snippet.note());
		assertTrue(snippet.note().contains("source file not found"), snippet.note());
		assertTrue(snippet.text().contains("source file not found"), snippet.text());
	}

	@Test
	void resolvesSourceFileUnderTheConfiguredRoot(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("example").resolve("MyUI.java");
		Files.createDirectories(file.getParent());
		writeLines(file, 20);

		SourceSnippet snippet = SourceSnippet.at("example/MyUI", "build", 10, "MyUI.java", 2, List.of(dir));

		assertNotNull(snippet.sourcePath());
		assertTrue(snippet.sourcePath().endsWith("example/MyUI.java"), snippet.sourcePath());
		assertEquals(8, snippet.lines().get(0).number());
		assertEquals(12, snippet.lines().get(snippet.lines().size() - 1).number());
	}

	@Test
	void fallsBackToTheClassSimpleNameWhenTheSourceFileNameIsUnknown(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("example").resolve("MyUI.java");
		Files.createDirectories(file.getParent());
		writeLines(file, 10);

		// Nested class host: the source file is still MyUI.java.
		SourceSnippet snippet = SourceSnippet.at("example/MyUI$Buttons", "build", 3, null, 1, List.of(dir));

		assertNotNull(snippet.sourcePath());
		assertTrue(snippet.sourcePath().endsWith("example/MyUI.java"), snippet.sourcePath());
		assertEquals(3, snippet.targetLine());
	}

	@Test
	void trackedCellExposesItsCreationSite() {
		CellPropertyRef.enable();
		forceUiThreadToCurrent();

		Table table = new Table();
		Cell<?> cell = table.add(new Element());
		CellPropertyRef.onCellBound(cell);

		CellPropertyRef.CellIdentity id = CellPropertyRef.identityOf(cell);
		assertNotNull(id, "precondition: the cell was bound while tracking is enabled");
		assertEquals("uihookprobe/UiSourceViewTest", id.hostClass());
		assertTrue(id.line() > 0, "the recorded creation line must be a real source line");

		SourceSnippet snippet = UiSource.of(cell);
		assertTrue(snippet.text().contains("uihookprobe.UiSourceViewTest#"), snippet.text());

		assertEquals(cell, UiSource.cellOf(cell.get()));
		assertEquals(snippet.targetLine(), UiSource.of(cell.get()).targetLine());
	}

	@Test
	void unknownCellYieldsANote() {
		SourceSnippet snippet = UiSource.of(new Table().add(new Element()));

		assertNull(snippet.sourcePath());
		assertTrue(snippet.lines().isEmpty());
		assertNotNull(snippet.note());
		assertTrue(snippet.note().contains("not tracked"), snippet.note());
	}

	// region helpers

	private static Path writeLines(Path file, int count) throws Exception {
		StringBuilder sb = new StringBuilder();
		for (int i = 1; i <= count; i++) sb.append("line-").append(i).append('\n');
		Files.createDirectories(file.getParent());
		Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
		return file;
	}

	/**
	 * Tests run without an Arc application, so {@code CellPropertyRef.isUiThread()} normally
	 * accepts the calling thread. Pin it explicitly so the case stays deterministic if another
	 * test in the same JVM has installed {@code Core.app}.
	 */
	private static void forceUiThreadToCurrent() {
		try {
			Field f = CellPropertyRef.class.getDeclaredField("uiThread");
			f.setAccessible(true);
			f.set(null, Thread.currentThread());
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("cannot pin the tracker UI thread", e);
		}
	}

	private static void restoreUiThread() {
		try {
			Field f = CellPropertyRef.class.getDeclaredField("uiThread");
			f.setAccessible(true);
			f.set(null, null);
		} catch (ReflectiveOperationException ignored) {
		}
	}

	// endregion
}
