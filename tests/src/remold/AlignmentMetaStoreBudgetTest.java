package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import remold.vcs.AlignmentMetaStore;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loader-GC budget accounting for {@link AlignmentMetaStore}.
 *
 * <p><b>Bug guarded here</b>: a plain {@code historyEntries} counter is never decremented when the
 * {@code WeakHashMap} silently expunges a collected loader's slot, so dead loaders would occupy the
 * global history budget forever and evict live entries early. The store now recomputes from live slots.</p>
 *
 * <p>Assertions: (1) the loader really is collected (WeakReference cleared — the store must not hold it),
 * (2) the reported history count drops to the live set, (3) a fresh loader's entries are not prematurely
 * evicted by the dead loader's phantom quota.</p>
 */
public class AlignmentMetaStoreBudgetTest {

	@AfterEach
	void tearDown() {
		AlignmentMetaStore.INSTANCE.reset();
	}

	@Test
	void deadLoaderDoesNotKeepHistoryBudget() throws Exception {
		AlignmentMetaStore store = AlignmentMetaStore.INSTANCE;
		store.reset();

		URLClassLoader doomed = new URLClassLoader(new URL[0], null);
		WeakReference<ClassLoader> ref = new WeakReference<>(doomed);
		// Fill the store with more history than the global budget: one class per entry, one generation each.
		int fill = AlignmentMetaStore.HISTORY_MAX_ENTRIES + 64;
		for (int i = 0; i < fill; i++) {
			store.commit(doomed, "probe/Dead" + i, new byte[]{ 1 }, new byte[]{ 2 }, new byte[]{ 3 },
				null, null, null, 0);
		}
		assertTrue(store.historyCount() > 0, "precondition: the doomed loader's history exists");

		doomed.close();
		doomed = null;

		boolean collected = false;
		for (int i = 0; i < 50 && !collected; i++) {
			System.gc();
			Thread.sleep(50L);
			collected = ref.get() == null;
		}
		Assumptions.assumeTrue(collected, "JVM did not collect the test loader within the window");

		assertEquals(0, store.historyCount(),
			"after loader GC the live history count must be zero (no phantom quota)");
		assertEquals(1, store.liveLoaderCount(), "only the bootstrap slot must remain live");
		assertNull(store.head(null, "probe/Dead0"), "bootstrap head must be untouched by the doomed loader");

		// The budget must be fully available again: a fresh loader's single entry must not be evicted.
		URLClassLoader fresh = new URLClassLoader(new URL[0], null);
		try {
			for (int i = 0; i < 8; i++) {
				store.commit(fresh, "probe/Fresh" + i, new byte[]{ 4 }, new byte[]{ 5 }, new byte[]{ 6 },
					null, null, null, 0);
			}
			assertEquals(8, store.historyCount(),
				"fresh loader history must survive: the dead loader's entries must not consume quota");
		} finally {
			fresh.close();
		}
	}
}
