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
		// 条目预算可被系统属性覆盖：这里显式压到 64，避免用例为验证淘汰而灌入上万个条目。
		System.setProperty("remold.vcs.derived_cache_entries", "64");
		try {
			for (int i = 0; i < 200; i++) {
				byte[] blob = { (byte) i, (byte) (i >> 8) };
				DerivedDataCache.get("bound", blob, 0L, () -> new Object());
			}
			long[] stats = DerivedDataCache.stats();
			assertTrue(stats[3] <= DerivedDataCache.maxEntries(),
				"entry count must stay within bound, actual=" + stats[3]);
			assertTrue(stats[2] > 0L, "eviction must have happened");
		} finally {
			System.clearProperty("remold.vcs.derived_cache_entries");
		}
	}

	/** 条目预算与字节预算都能被系统属性覆盖（默认值按实测校准，见 docs/vcs/01 §3.7）。 */
	@Test
	void budgetsComeFromSystemProperties() {
		assertEquals(DerivedDataCache.MAX_ENTRIES, DerivedDataCache.maxEntries(), "default entry budget");
		assertEquals(DerivedDataCache.MAX_BYTES, DerivedDataCache.maxBytes(), "default byte budget");
		System.setProperty("remold.vcs.derived_cache_entries", "7");
		System.setProperty("remold.vcs.derived_cache_bytes", "4096");
		try {
			assertEquals(7, DerivedDataCache.maxEntries(), "entry budget must follow the property");
			assertEquals(4096L, DerivedDataCache.maxBytes(), "byte budget must follow the property");
			for (int i = 0; i < 20; i++) {
				DerivedDataCache.get("prop", new byte[]{ (byte) i }, 512L, () -> new Object());
			}
			assertTrue(DerivedDataCache.stats()[3] <= 7, "override must actually bound the entry count");
		} finally {
			System.clearProperty("remold.vcs.derived_cache_entries");
			System.clearProperty("remold.vcs.derived_cache_bytes");
		}
	}

	/**
	 * 总开关：关闭时既不读也不写，但派生结果照旧返回；{@code blobKey} 在关闭时返回 null
	 * （调用方据此走透传分支，不再计算 SHA-256）。
	 */
	@Test
	void disabledSwitchIsPassThroughAndSkipsHashing() {
		System.setProperty("remold.vcs.derived_cache", "false");
		try {
			byte[] blob = { 7, 7, 7 };
			AtomicInteger calls = new AtomicInteger();
			Object a = DerivedDataCache.get("off", blob, 8L, () -> { calls.incrementAndGet(); return new Object(); });
			Object b = DerivedDataCache.get("off", blob, 8L, () -> { calls.incrementAndGet(); return new Object(); });
			assertNotSame(a, b, "disabled switch must not reuse a value");
			assertEquals(2, calls.get(), "disabled switch must derive every time");
			assertEquals(0L, DerivedDataCache.stats()[3], "disabled switch must not store entries");
			assertNull(DerivedDataCache.blobKey(blob), "disabled switch must skip hashing (null key)");
			assertNull(DerivedDataCache.peek("off", blob), "disabled switch must not answer peeks");
		} finally {
			System.clearProperty("remold.vcs.derived_cache");
		}
	}

	/** 预先算好的 blob 键可以复用，且与按字节查询落到同一条目上。 */
	@Test
	void precomputedBlobKeySharesEntriesWithByteLookup() {
		byte[] blob = { 9, 8, 7, 6, 5 };
		AtomicInteger calls = new AtomicInteger();
		Object byBytes = DerivedDataCache.get("shared", blob, 8L, () -> { calls.incrementAndGet(); return "v"; });
		String key = DerivedDataCache.blobKey(blob);
		assertEquals(64, key.length(), "SHA-256 hex key must be 64 chars");
		Object byKey = DerivedDataCache.getByKey("shared", key, 8L,
		                                         () -> { calls.incrementAndGet(); return "other"; });
		assertSame(byBytes, byKey, "precomputed key must hit the entry stored by the byte overload");
		assertEquals(1, calls.get(), "deriver must run once across both overloads");
		assertSame(byBytes, DerivedDataCache.peekByKey("shared", key), "peekByKey must see the entry");
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
