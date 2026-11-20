package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作区 Raw 按需索引：懒查询 + 批内复用 + 找不到/歧义一律不可证。
 */
class WorkspaceRawIndexTest {

	private static final String CLASS = "probe/RawTarget";
	private static final byte[] RAW = "RAW-BYTES".getBytes(StandardCharsets.UTF_8);

	private Function<String, byte[]> source;
	private int calls;

	@BeforeEach
	void setUp() {
		WorkspaceRawIndex.reset();
		calls = 0;
	}

	@AfterEach
	void tearDown() {
		if (source != null) BytecodeSources.unregister(source);
		WorkspaceRawIndex.reset();
	}

	private void register(byte[] bytes) {
		source = (Function<String, byte[]>) name -> {
			calls++;
			return CLASS.equals(name.replace('.', '/')) ? bytes : null;
		};
		BytecodeSources.register(source);
	}

	/** 命中：拿到字节，且批内第二次查询不再打来源（懒加载 + 批内复用）。 */
	@Test
	void hitIsCachedWithinTheBatch() {
		register(RAW);
		WorkspaceRawIndex.beginBatch();
		try {
			assertArrayEquals(RAW, WorkspaceRawIndex.lookup(CLASS));
			int afterFirst = calls;
			assertArrayEquals(RAW, WorkspaceRawIndex.lookup(CLASS));
			assertEquals(afterFirst, calls, "a second lookup inside the same batch must not hit the sources");
			assertEquals(1L, WorkspaceRawIndex.counters().get("hits"));
		} finally {
			WorkspaceRawIndex.endBatch();
		}
	}

	/** 找不到：返回 null，且必须计入 misses（不猜、不退回任何默认字节）。 */
	@Test
	void missIsFailClosed() {
		register(RAW);
		assertNull(WorkspaceRawIndex.lookup("probe/NotThere"));
		assertEquals(1L, WorkspaceRawIndex.counters().get("misses"));
	}

	/** 歧义：两个来源给出不同字节 → 不可证，返回 null 并计数。 */
	@Test
	void conflictingSourcesAreAmbiguousNotGuessed() {
		register(RAW);
		byte[] other = "WOVEN-BYTES".getBytes(StandardCharsets.UTF_8);
		Function<String, byte[]> second = (Function<String, byte[]>) name ->
		 CLASS.equals(name.replace('.', '/')) ? other : null;
		BytecodeSources.register(second);
		try {
			assertNull(WorkspaceRawIndex.lookup(CLASS),
			 "two different byte images for one class must never be resolved by guessing");
			assertEquals(1L, WorkspaceRawIndex.counters().get("ambiguous"));
		} finally {
			BytecodeSources.unregister(second);
		}
	}

	/** 批次外查询可用但不落缓存：索引不会退化成一张没有寿命管理的全局表。 */
	@Test
	void lookupOutsideBatchIsNotCached() {
		register(RAW);
		assertArrayEquals(RAW, WorkspaceRawIndex.lookup(CLASS));
		int afterFirst = calls;
		assertArrayEquals(RAW, WorkspaceRawIndex.lookup(CLASS));
		assertTrue(calls > afterFirst, "outside a batch every lookup asks the sources again");
		assertEquals(2L, WorkspaceRawIndex.counters().get("unattributedHits"));
	}

	/** 批内缓存在批次结束时清空：下一批必须重新问来源（Raw 可能已经变了）。 */
	@Test
	void endingTheBatchDropsTheCache() {
		register(RAW);
		WorkspaceRawIndex.beginBatch();
		WorkspaceRawIndex.lookup(CLASS);
		int afterFirst = calls;
		WorkspaceRawIndex.endBatch();

		WorkspaceRawIndex.beginBatch();
		try {
			WorkspaceRawIndex.lookup(CLASS);
			assertTrue(calls > afterFirst, "a new batch must not reuse the previous batch's bytes");
		} finally {
			WorkspaceRawIndex.endBatch();
		}
	}

	/** 读数：耗时分布必须真实存在（不做"看起来很快"的假断言，只断言确有测量）。 */
	@Test
	void countersExposeTiming() {
		register(RAW);
		WorkspaceRawIndex.lookup(CLASS);
		Map<String, Long> counters = WorkspaceRawIndex.counters();
		assertTrue(counters.get("totalNanos") > 0L, "the index must actually measure lookup cost");
		assertTrue(counters.get("maxNanos") > 0L);
	}

	/** 同一批里点分与斜杠混查必须命中同一条缓存（否则会白打两次来源）。 */
	@Test
	void dottedAndSlashShareOneCacheEntry() {
		register(RAW);
		WorkspaceRawIndex.beginBatch();
		try {
			assertArrayEquals(RAW, WorkspaceRawIndex.lookup("probe.RawTarget"));
			int afterFirst = calls;
			assertArrayEquals(RAW, WorkspaceRawIndex.lookup("probe/RawTarget"));
			assertEquals(afterFirst, calls,
			 "the same class queried in both name forms must not hit the sources twice");
			assertEquals(1L, WorkspaceRawIndex.counters().get("hits"));
		} finally {
			WorkspaceRawIndex.endBatch();
		}
	}
}
