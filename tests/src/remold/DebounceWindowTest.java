package remold;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DebounceWindow} 的无 sleep 契约测试：Class 与资源流各自的 trailing-edge 静默窗口
 * 必须独立，Class 事件不能被资源事件重置，反之亦然。
 */
class DebounceWindowTest {

	private static final long MS = 1_000_000L;

	@Test
	void everyClassEventResetsTheClassQuietWindow() {
		DebounceWindow window = new DebounceWindow(800, 250);
		window.onClass(0);
		assertEquals(800 * MS, window.nextDelayNanos(0));
		assertFalse(window.isClassDue(799 * MS));

		window.onClass(500 * MS);
		assertEquals(800 * MS, window.nextDelayNanos(500 * MS));
		assertFalse(window.isClassDue(1299 * MS));
		assertTrue(window.isClassDue(1300 * MS));
	}

	@Test
	void assetEventsDoNotResetTheClassWindow() {
		DebounceWindow window = new DebounceWindow(800, 250);
		window.onClass(0);
		window.onAsset(100 * MS);
		// Class deadline remains t=800ms; asset deadline is t=350ms.
		assertFalse(window.isClassDue(700 * MS));
		assertTrue(window.isClassDue(800 * MS));
		assertFalse(window.isAssetDue(349 * MS));
		assertTrue(window.isAssetDue(350 * MS));
	}

	@Test
	void classEventsDoNotResetTheAssetWindow() {
		DebounceWindow window = new DebounceWindow(800, 250);
		window.onAsset(0);
		window.onClass(100 * MS);
		assertTrue(window.isAssetDue(250 * MS));
		assertFalse(window.isClassDue(899 * MS));
		assertTrue(window.isClassDue(900 * MS));
	}

	@Test
	void nextDelayUsesTheEarlierOfTheTwoDeadlines() {
		DebounceWindow window = new DebounceWindow(800, 250);
		window.onClass(0);
		window.onAsset(100 * MS);
		assertEquals(150 * MS, window.nextDelayNanos(200 * MS));
		window.clearAsset();
		assertEquals(600 * MS, window.nextDelayNanos(200 * MS));
		window.clearClass();
		assertEquals(-1L, window.nextDelayNanos(200 * MS));
	}

	@Test
	void zeroWidthWindowIsDueImmediately() {
		DebounceWindow window = new DebounceWindow(0, 0);
		window.onClass(123);
		window.onAsset(123);
		assertTrue(window.isClassDue(123));
		assertTrue(window.isAssetDue(123));
		assertEquals(0L, window.nextDelayNanos(123));
	}

	@Test
	void clearAllResetsBothStreams() {
		DebounceWindow window = new DebounceWindow(800, 250);
		window.onClass(0);
		window.onAsset(0);
		window.clearAll();
		assertFalse(window.hasPendingClass());
		assertFalse(window.hasPendingAsset());
		assertEquals(-1L, window.nextDelayNanos(0));
	}
}
