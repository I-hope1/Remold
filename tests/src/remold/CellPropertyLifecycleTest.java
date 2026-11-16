package remold;

import arc.scene.Element;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import remold.uihook.CellPropertyRef;
import remold.uihook.CellPropertyRef.ElementCallback;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle and concurrency contract of {@link CellPropertyRef}'s internal registries.
 *
 * <p>Covers two residual risks:</p>
 * <ol>
 *   <li><b>Mixed lock domains</b>: {@code chainCache}/{@code templateCache} used to be plain
 *       {@code HashMap}s whose reads happen outside {@code synchronized (cellState)}, while
 *       {@code clearAll} cleared them <em>inside</em> that lock — the lock looked protective but
 *       had no mutex with the producers. {@code KNOWN_DEFAULTS} was a bare {@code WeakHashMap}-backed
 *       set (its own probe-expunge mutates the table even on {@code contains}).</li>
 *   <li><b>Pinned callbacks</b>: the registered-callback table held a strong reference to the
 *       callback (usually a closure over the host class), so an unreachable element kept the whole
 *       object graph alive until the next hotswap happened to rebuild callbacks.</li>
 * </ol>
 *
 * <p><b>Test strategy note</b>: a deterministic ConcurrentModificationException repro would need a
 * scheduling seam that production code does not expose. The concurrency part therefore asserts
 * (a) the containers that make the read/clear domain safe and (b) a stress smoke test. The
 * container assertion is what the mutation check targets; the stress smoke is a complement, not
 * the primary evidence.</p>
 */
class CellPropertyLifecycleTest {

	@AfterEach
	void tearDown() {
		CellPropertyRef.TRACKED_CALLBACKS.clear();
	}

	// region callback registry

	@Test
	void pruneDropsDeadElementOrCallbackEntries() {
		Element element = new Element();
		Object callback = new Object();
		ElementCallback entry = new ElementCallback(new WeakReference<>(element), callback, "button", "host/Class");
		CellPropertyRef.TRACKED_CALLBACKS.add(entry);
		assertEquals(1, CellPropertyRef.TRACKED_CALLBACKS.size(), "precondition: the entry is registered");

		// Baseline: a fully live entry must survive pruning, otherwise the assertions below could
		// pass because prune drops everything unconditionally.
		CellPropertyRef.pruneStaleCallbacks();
		assertEquals(1, CellPropertyRef.TRACKED_CALLBACKS.size(), "prune must keep a live element+callback entry");

		entry.elementRef.clear();
		CellPropertyRef.pruneStaleCallbacks();
		assertEquals(0, CellPropertyRef.TRACKED_CALLBACKS.size(),
		 "an entry whose element is unreachable must be pruned without waiting for a hotswap");

		Element liveElement = new Element();
		Object deadCallback = new Object();
		ElementCallback second = new ElementCallback(new WeakReference<>(liveElement), deadCallback, "field", "host/Class");
		CellPropertyRef.TRACKED_CALLBACKS.add(second);
		assertEquals(1, CellPropertyRef.TRACKED_CALLBACKS.size(), "precondition: the second entry is registered");

		second.callbackRef.clear();
		CellPropertyRef.pruneStaleCallbacks();
		assertEquals(0, CellPropertyRef.TRACKED_CALLBACKS.size(),
		 "an entry whose callback is unreachable must be pruned as well");
	}

	@Test
	void unreachableCallbackIsNotPinnedByRegistry() throws Exception {
		CellPropertyRef.TRACKED_CALLBACKS.clear();
		WeakReference<Object> callbackRef = registerDisposableCallback();
		assertTrue(callbackRef.get() != null, "precondition: the callback is strongly reachable at registration");

		// Baseline: while reachable, pruning must keep the entry.
		CellPropertyRef.pruneStaleCallbacks();
		assertEquals(1, CellPropertyRef.TRACKED_CALLBACKS.size(), "a reachable callback must stay registered");

		awaitCollected(callbackRef);
		CellPropertyRef.pruneStaleCallbacks();
		assertEquals(0, CellPropertyRef.TRACKED_CALLBACKS.size(),
		 "the registry must not keep an unreachable callback alive (strong reference regression)");
	}

	/** Keeps the element/callback lives confined to this frame so the caller cannot pin them. */
	private static WeakReference<Object> registerDisposableCallback() {
		Element element = new Element();
		Object callback = new Object();
		CellPropertyRef.registerElementCallback(element, callback, "button");
		return new WeakReference<>(callback);
	}

	private static void awaitCollected(WeakReference<?> ref) throws InterruptedException {
		for (int i = 0; i < 60 && ref.get() != null; i++) {
			System.gc();
			Thread.sleep(20);
			// Allocation pressure: keeps a no-op loop from being optimized into "never collect".
			if (new byte[1024].length == 0) throw new IllegalStateException("unreachable");
		}
		assertNull(ref.get(), "GC never collected the callback; weak retention cannot be proven in this run");
	}

	// endregion

	// region concurrency-safe containers

	@Test
	void cachesUseConcurrencySafeContainers() throws Exception {
		assertTrue(staticField("chainCache") instanceof ConcurrentHashMap,
		 "chainCache is read outside the cellState lock; it must be a concurrent container");
		assertTrue(staticField("templateCache") instanceof ConcurrentHashMap,
		 "templateCache is read outside the cellState lock; it must be a concurrent container");

		Object knownDefaults = staticField("KNOWN_DEFAULTS");
		assertTrue(knownDefaults.getClass().getName().contains("Synchronized"),
		 "KNOWN_DEFAULTS wraps a WeakHashMap; every access must be synchronized: " + knownDefaults.getClass());
		assertTrue(CellPropertyRef.TRACKED_CALLBACKS.getClass().getName().contains("Synchronized"),
		 "TRACKED_CALLBACKS must stay a synchronized set");
	}

	private static Object staticField(String name) throws Exception {
		Field f = CellPropertyRef.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(null);
	}

	@Test
	void concurrentStatsAndClearsDoNotThrow() throws Exception {
		int threads = 4;
		CountDownLatch start = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread[] workers = new Thread[threads];
		for (int t = 0; t < threads; t++) {
			final int id = t;
			workers[t] = new Thread(() -> {
				try {
					start.await();
					for (int i = 0; i < 2_000; i++) {
						switch ((id + i) & 1) {
							case 0 -> CellPropertyRef.getStats();
							case 1 -> {
								CellPropertyRef.clearAll();
								CellPropertyRef.clearClassRecords("host/Class" + (i & 3));
								CellPropertyRef.pruneStaleCallbacks();
							}
							default -> { }
						}
					}
				} catch (Throwable e) {
					failure.compareAndSet(null, e);
				}
			}, "cell-cache-stress-" + t);
			workers[t].start();
		}
		start.countDown();
		for (Thread w : workers) {
			w.join(TimeUnit.SECONDS.toMillis(30));
			assertTrue(!w.isAlive(), "stress worker did not finish in time: " + w.getName());
		}
		assertNull(failure.get(), "concurrent stats/clear must not throw: " + failure.get());
	}

	// endregion
}
