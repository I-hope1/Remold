package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import remold.vcs.DerivedDataCache;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-blob derived data cache: content addressing, kind separation, bounded eviction.
 *
 * <p>Controls: a different blob must miss (so the assertion is not satisfied by "cache always returns"),
 * and a different kind must not return another kind's value (so keys cannot collide across derivers).</p>
 */
public class DerivedDataCacheTest {

	@AfterEach
	void tearDown() {
		DerivedDataCache.reset();
	}

	@Test
	void sameBlobHitsOnceAndReusesTheSameValue() {
		byte[] blob = { 1, 2, 3, 4 };
		AtomicInteger calls = new AtomicInteger();
		Object first = DerivedDataCache.get("k", blob, 16L, () -> {
			calls.incrementAndGet();
			return new Object();
		});
		Object second = DerivedDataCache.get("k", blob.clone(), 16L, () -> {
			calls.incrementAndGet();
			return new Object();
		});
		assertSame(first, second, "identical blob content must hit the cache (not a new derivation)");
		assertEquals(1, calls.get(), "deriver must run exactly once");
	}

	@Test
	void differentBlobOrKindMisses() {
		AtomicInteger calls = new AtomicInteger();
		DerivedDataCache.get("k", new byte[]{ 1 }, 8L, () -> { calls.incrementAndGet(); return "a"; });
		Object otherBlob = DerivedDataCache.get("k", new byte[]{ 2 }, 8L, () -> { calls.incrementAndGet(); return "b"; });
		Object otherKind = DerivedDataCache.get("k2", new byte[]{ 1 }, 8L, () -> { calls.incrementAndGet(); return "c"; });
		assertEquals("b", otherBlob, "different blob must re-derive");
		assertEquals("c", otherKind, "different kind must not reuse another kind's value");
		assertEquals(3, calls.get(), "all three calls must derive");
		assertNull(DerivedDataCache.peek("k", new byte[]{ 9 }), "unknown blob must peek as absent");
	}

	@Test
	void entryCountIsBounded() {
		for (int i = 0; i < DerivedDataCache.MAX_ENTRIES + 100; i++) {
			byte[] blob = { (byte) i, (byte) (i >> 8) };
			DerivedDataCache.get("bound", blob, 0L, () -> new Object());
		}
		long[] stats = DerivedDataCache.stats();
		assertTrue(stats[3] <= DerivedDataCache.MAX_ENTRIES,
			"entry count must stay within bound, actual=" + stats[3]);
		assertTrue(stats[2] > 0L, "eviction must have happened");
	}

	@Test
	void byteBudgetIsEnforced() {
		// Each entry claims 1 MiB: the second one already exceeds the 8 MiB bound after 8 entries.
		for (int i = 0; i < 32; i++) {
			byte[] blob = { (byte) i };
			DerivedDataCache.get("bytes", blob, 1024L * 1024L, () -> new Object());
		}
		long[] stats = DerivedDataCache.stats();
		assertTrue(stats[4] <= DerivedDataCache.MAX_BYTES,
			"estimated bytes must stay within bound, actual=" + stats[4]);
	}

	@Test
	void nullBlobBypassesCache() {
		AtomicInteger calls = new AtomicInteger();
		Object a = DerivedDataCache.get("n", null, 0L, () -> { calls.incrementAndGet(); return new Object(); });
		Object b = DerivedDataCache.get("n", null, 0L, () -> { calls.incrementAndGet(); return new Object(); });
		assertNotSame(a, b, "null blob has no identity and must not be cached");
		assertEquals(2, calls.get());
		assertEquals(0L, DerivedDataCache.stats()[3], "no entry may be stored for a null blob");
	}
}
