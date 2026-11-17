package remold;

import org.junit.jupiter.api.Test;
import remold.util.LibTool;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实例枚举分桶规划（窗口固定 2^18、桶数上限 64）的纯函数测试。
 *
 * <p>native 侧 {@code enumBucketCount} 与 {@link LibTool#enumerationBucketCount(long)} 必须是同一实现；
 * 大 N 的上限路径用真实实例无法覆盖（会先耗尽堆），因此在这里以纯函数形式验证。</p>
 */
public class EnumBucketPlanTest {

	private static final int WINDOW = 1 << 18;
	private static final int CAP = 64;

	private static int expected(long count) {
		if (count <= 0) return 1;
		long buckets = ((count - 1) >>18) + 1;
		return buckets > CAP ? CAP : (int) buckets;
	}

	@Test
	void windowIsTwoToThe18() {
		if (LibTool.initialized()) {
			assertTrue(LibTool.enumerationBucketPlanIsNative(),
				"Expected native enumeration bucket plan (not Java fallback)");
			assertEquals(WINDOW, LibTool.enumerationBucketWindow());
		}
	}

	@Test
	void bucketCountBoundaries() {
		long[][] cases = {
			{0, 1}, {1, 1}, {WINDOW - 1, 1}, {WINDOW, 1}, {WINDOW + 1, 2},
			{2L * WINDOW, 2}, {2L * WINDOW + 1, 3},
			{2_000_001, 8}, {4_000_001, 16},
			{8L * WINDOW, 8}, {32L * WINDOW, 32},
			{(long) CAP * WINDOW, CAP}, {(long) CAP * WINDOW + 1, CAP},
			{1L << 40, CAP},
		};
		boolean nativeLoaded = LibTool.initialized();
		for (long[] c : cases) {
			long count = c[0];
			int want = (int) c[1];
			assertEquals(expected(count), want, "model mismatch at " + count);
			if (nativeLoaded) {
				assertEquals(want, LibTool.enumerationBucketCount(count), "native mismatch at " + count);
			}
		}
	}

	@Test
	void overflowTailStaysInLastBucket() {
		// 超过 cap*window 的部分仍落最后一桶（窗口实际变大），不会越界、桶数不增长
		assertEquals(CAP, expected((long) CAP * WINDOW + 1));
		assertEquals(CAP, expected(4L * CAP * WINDOW));
	}
}
