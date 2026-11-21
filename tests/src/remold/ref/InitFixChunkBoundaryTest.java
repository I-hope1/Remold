package remold.ref;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批实例切块边界（{@code nextBatchChunkEnd}）。
 *
 * <p>实测缺陷：步长从 4 放大到 8192 时没有钳到 {@code total}。剩余量落在
 * {@code (4096, 8192)} 时（例如 {@code total = 15000}：8192 + 6808），返回的块尾会越过数组末端，
 * 生成器只判 {@code localIndex < localTo}，于是每个实例抛越界，连续撞满单字段失败配额后
 * 把已经补成功的字段当成"放弃"，融合批还会退回 {@code fallbackFusedGroupFrom} 重复刷一遍条件 CAS。</p>
 *
 * <p>这里锁两条性质：块尾永远 {@code <= total}，且按返回的块尾推进能<b>恰好</b>覆盖全部实例一次。</p>
 */
class InitFixChunkBoundaryTest {

	/** 触发区间的边界：12288 是"刚好落在 4096 剩余"的最后一点，12289 起进入缺陷区间。 */
	private static final int[] TOTALS = {1, 4, 4096, 4097, 8192, 12288, 12289, 15000, 16383, 16384,
	 20481, 24575, 32767, 65536};

	@Test
	void chunkEndNeverExceedsTotal() {
		List<String> offenders = new ArrayList<>();
		for (int total : TOTALS) {
			int pos = 0;
			int calls = 0;
			while (pos < total && calls < 100_000) {
				int end = InitFixRuntimeDriver.nextBatchChunkEnd(pos, total, calls++);
				if (end <= pos || end > total) {
					offenders.add("total=" + total + " pos=" + pos + " calls=" + calls + " end=" + end);
					break;
				}
				pos = end;
			}
		}
		assertEquals(List.of(), offenders,
		 "a chunk end must satisfy pos < end <= total, otherwise every instance in the chunk throws");
	}

	@Test
	void everyInstanceIsCoveredExactlyOnce() {
		for (int total : TOTALS) {
			int pos = 0;
			int calls = 0;
			int covered = 0;
			while (pos < total && calls < 100_000) {
				int end = InitFixRuntimeDriver.nextBatchChunkEnd(pos, total, calls++);
				covered += end - pos;
				pos = end;
			}
			assertEquals(total, covered, "total=" + total + " must be covered exactly once");
			assertEquals(0, pos - total, "total=" + total + " must land exactly on the end");
		}
	}

	/** 15000 就是实测现场：修复前第 2049 次调用返回 16384，修复后必须返回 15000。 */
	@Test
	void theMeasuredFifteenThousandCaseIsClamped() {
		int pos = 8192;
		int end = InitFixRuntimeDriver.nextBatchChunkEnd(pos, 15000, 2048);
		assertEquals(15000, end, "the widened step must clamp to the array length");
	}

	/** 步长策略本身不变：预热期按 4 递增，之后放大，避免把修复做成"整段一次调用"。 */
	@Test
	void warmupAndWidenedStepsArePreserved() {
		assertEquals(4, InitFixRuntimeDriver.nextBatchChunkEnd(0, 100_000, 0)
		 - 0, "the first calls must advance by 4");
		assertEquals(16384, InitFixRuntimeDriver.nextBatchChunkEnd(8192, 100_000, 2048),
			"after the warmup the step widens to 8192");
		assertEquals(4096, InitFixRuntimeDriver.nextBatchChunkEnd(0, 4096, 0),
		 "a remaining span of <= 4096 is finished in one call");
		assertEquals(5000, InitFixRuntimeDriver.nextBatchChunkEnd(904, 5000, 0),
		 "the tail is always finished in one call once it shrinks to 4096");
	}
}
