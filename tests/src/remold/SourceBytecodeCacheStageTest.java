package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 风险 16 回归锁：{@code sourceBytecodeCache} 的并发语义窗口。
 *
 * <p>事务从"写入本轮候选源码字节"到"提交 / 回滚"之间存在真实窗口（跨度含匿名类对齐、织入桥、
 * 布局门与 {@code redefineClasses}）。本用例用真实线程 + 闩锁把读方放进窗口内，断言读方只能读到
 * <b>上一个已提交版本</b>，绝不读到本轮候选人；提交后同一读方断言读到新值；回滚后断言仍是旧值。</p>
 */
class SourceBytecodeCacheStageTest {

	private static final byte[] V1 = {1, 1, 1};
	private static final byte[] V2 = {2, 2, 2};
	private static final byte[] V3 = {3, 3, 3};

	private static final String WINDOW_NAME = "srcstage.Window";
	private static final String ROUND_NAME  = "srcstage.Round";
	private static final String PAIR_A      = "srcstage.PairA";
	private static final String PAIR_B      = "srcstage.PairB";

	@AfterEach
	void cleanUp() {
		// 任何断言提前失败都不得把暂存轮次留给后续用例
		RemoldAgent.abandonSourceRound();
		for (String name : new String[]{WINDOW_NAME, ROUND_NAME, PAIR_A, PAIR_B}) {
			RemoldAgent.sourceBytecodeCache.remove(name);
		}
	}

	/**
	 * 核心并发回归锁：窗口内读方（另一线程）读的是上一版已提交字节；提交后读新值；回滚后仍读旧值。
	 */
	@Test
	void readerInOpenWindowSeesPreviousCommittedBytesOnly() throws Exception {
		RemoldAgent.sourceBytecodeCache.put(WINDOW_NAME, V1);
		// 前置基线确认：上一版已生效
		assertArrayEquals(V1, RemoldAgent.getSourceBytecode(WINDOW_NAME),
			"precondition: V1 is the committed baseline before the round starts");

		RemoldAgent.SourceBytecodeStaging.Round round = RemoldAgent.beginSourceRound();
		RemoldAgent.recordSourceBytecode(WINDOW_NAME, V2);
		// 前置基线确认：候选人确实进了本轮暂存（否则"读不到"是真空转）
		assertArrayEquals(V2, round.stagedBytes(WINDOW_NAME),
			"precondition: the candidate bytes are staged inside the transaction window");
		assertArrayEquals(V1, RemoldAgent.getSourceBytecode(WINDOW_NAME),
			"the transaction thread must not expose its own staged candidate via the reader API");

		// 窗口仍打开：让另一线程进来读（真实并发，不是顺序调用装样子）
		CountDownLatch readerReady = new CountDownLatch(1);
		CountDownLatch writerDone  = new CountDownLatch(1);
		AtomicReference<byte[]> viaView = new AtomicReference<>();
		AtomicReference<byte[]> viaApi  = new AtomicReference<>();
		AtomicReference<Throwable> readerFailure = new AtomicReference<>();
		Thread reader = new Thread(() -> {
			try {
				if (!readerReady.await(5, TimeUnit.SECONDS)) {
					readerFailure.set(new AssertionError("reader never observed the open transaction window"));
					return;
				}
				viaView.set(RemoldAgent.sourceBytecodeCache.get(WINDOW_NAME));
				viaApi.set(RemoldAgent.getSourceBytecode(WINDOW_NAME));
			} catch (Throwable t) {
				readerFailure.set(t);
			} finally {
				writerDone.countDown();
			}
		}, "srcstage-window-reader");
		reader.start();
		readerReady.countDown();
		assertTrue(writerDone.await(5, TimeUnit.SECONDS), "reader did not finish within the timeout");
		reader.join(5000);
		assertFalse(reader.isAlive());
		assertNull(readerFailure.get());
		assertArrayEquals(V1, viaView.get(),
			"a reader inside the open transaction window must see the previously committed bytes");
		assertArrayEquals(V1, viaApi.get(),
			"getSourceBytecode must never read the staged candidate of a running transaction");

		// 提交：新值才对读方可见
		RemoldAgent.rollbackSourceCache(Map.of(WINDOW_NAME, V1), Set.of(WINDOW_NAME), Set.of(WINDOW_NAME));
		assertArrayEquals(V2, RemoldAgent.getSourceBytecode(WINDOW_NAME),
			"after a successful commit the reader must see the new bytes");
		assertNull(RemoldAgent.currentSourceRound(), "a committed round must be unregistered from the thread");

		// 回滚：候选值不得前进
		RemoldAgent.SourceBytecodeStaging.Round rejected = RemoldAgent.beginSourceRound();
		RemoldAgent.recordSourceBytecode(WINDOW_NAME, V3);
		assertArrayEquals(V3, rejected.stagedBytes(WINDOW_NAME));
		assertArrayEquals(V2, readFromOtherThread(WINDOW_NAME),
			"a class that fails later in the round must stay invisible while the window is open");
		RemoldAgent.rollbackSourceCache(Map.of(WINDOW_NAME, V2), Set.of(), Set.of(WINDOW_NAME));
		assertEquals(0, rejected.stagedCount(), "a rolled back round must leave no staged entry behind");
		assertArrayEquals(V2, RemoldAgent.getSourceBytecode(WINDOW_NAME),
			"a rolled back round must keep the last committed baseline");
	}

	/** 回滚不留脏：失败一轮后暂存为空，且紧接着的成功一轮不受影响。 */
	@Test
	void failedRoundLeavesNoDirtyEntriesAndNextRoundIsUnaffected() throws Exception {
		RemoldAgent.sourceBytecodeCache.put(ROUND_NAME, V1);
		assertArrayEquals(V1, RemoldAgent.getSourceBytecode(ROUND_NAME));

		RemoldAgent.SourceBytecodeStaging.Round failed = RemoldAgent.beginSourceRound();
		RemoldAgent.recordSourceBytecode(ROUND_NAME, V2);
		assertEquals(1, failed.stagedCount(), "precondition: one candidate entry is staged");
		assertArrayEquals(V1, readFromOtherThread(ROUND_NAME),
			"the doomed round's candidate must never become visible");

		// 本轮没有任何类生效 → 回滚
		RemoldAgent.rollbackSourceCache(Map.of(ROUND_NAME, V1), Set.of(), Set.of(ROUND_NAME));
		assertTrue(failed.isEmpty(), "rollback must discard every staged entry");
		assertEquals(0, failed.stagedCount());
		assertNull(RemoldAgent.currentSourceRound(), "the failed round must be unregistered");
		assertArrayEquals(V1, RemoldAgent.getSourceBytecode(ROUND_NAME),
			"a failed round must not advance the committed baseline");

		// 下一轮：不得继承上一轮的任何条目，成功提交后新值生效
		RemoldAgent.SourceBytecodeStaging.Round second = RemoldAgent.beginSourceRound();
		assertEquals(0, second.stagedCount(),
			"a fresh round must not inherit entries from the previous (failed) round");
		RemoldAgent.recordSourceBytecode(ROUND_NAME, V3);
		assertArrayEquals(V1, readFromOtherThread(ROUND_NAME),
			"the second round's candidate is invisible until it commits");
		RemoldAgent.rollbackSourceCache(Map.of(ROUND_NAME, V1), Set.of(ROUND_NAME), Set.of(ROUND_NAME));
		assertArrayEquals(V3, RemoldAgent.getSourceBytecode(ROUND_NAME),
			"the second round commits its new baseline");
	}

	/** 部分生效：只有成功类的新字节并入已提交视图，未生效类保持旧值，提交后暂存清空。 */
	@Test
	void partialRoundCommitsOnlyAppliedClassesAndClearsStaged() {
		RemoldAgent.sourceBytecodeCache.put(PAIR_A, V1);
		RemoldAgent.sourceBytecodeCache.put(PAIR_B, V1);
		assertArrayEquals(V1, RemoldAgent.getSourceBytecode(PAIR_A));
		assertArrayEquals(V1, RemoldAgent.getSourceBytecode(PAIR_B));

		RemoldAgent.SourceBytecodeStaging.Round round = RemoldAgent.beginSourceRound();
		RemoldAgent.recordSourceBytecode(PAIR_A, V2);
		RemoldAgent.recordSourceBytecode(PAIR_B, V2);
		Map<String, byte[]> previous = Map.of(PAIR_A, V1, PAIR_B, V1);
		assertArrayEquals(V1, RemoldAgent.getSourceBytecode(PAIR_A),
			"neither candidate may leak into the committed view before the commit");
		assertArrayEquals(V1, RemoldAgent.getSourceBytecode(PAIR_B));

		RemoldAgent.rollbackSourceCache(previous, Set.of(PAIR_A), Set.of(PAIR_A, PAIR_B));
		assertArrayEquals(V2, RemoldAgent.getSourceBytecode(PAIR_A),
			"the applied class keeps its new bytes");
		assertArrayEquals(V1, RemoldAgent.getSourceBytecode(PAIR_B),
			"the class that did not take effect keeps the previous bytes");
		assertEquals(0, round.stagedCount(), "commit must clear the staged container");
	}

	/**
	 * 既有行为不变：同键重复写入以最后一次为准、{@code remove} 立即作用于已提交视图、
	 * 无事务时的写入立即可见（键 / 值 / 清理时机都与旧实现一致）。
	 */
	@Test
	void nonTransactionalWritesKeepTheOldSemantics() {
		RemoldAgent.recordSourceBytecode(PAIR_A, V1);
		assertArrayEquals(V1, RemoldAgent.getSourceBytecode(PAIR_A),
			"without a transaction the write is immediately visible");
		RemoldAgent.recordSourceBytecode(PAIR_A, V2);
		assertArrayEquals(V2, RemoldAgent.getSourceBytecode(PAIR_A),
			"a repeated write wins with the last value");

		// 有事务在进行中时 remove 仍是立即清理已提交视图（清理时机不变）
		RemoldAgent.beginSourceRound();
		RemoldAgent.removeSourceBytecode(PAIR_A);
		assertNull(RemoldAgent.getSourceBytecode(PAIR_A),
			"removeSourceBytecode must keep acting on the committed view immediately");
		assertNull(RemoldAgent.sourceBytecodeCache.get(PAIR_A));
	}

	/** 另一线程读已提交视图（真实线程，非顺序调用）。 */
	private static byte[] readFromOtherThread(String name) throws InterruptedException {
		AtomicReference<byte[]> seen = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread reader = new Thread(() -> {
			try {
				seen.set(RemoldAgent.getSourceBytecode(name));
			} catch (Throwable t) {
				failure.set(t);
			}
		}, "srcstage-reader");
		reader.start();
		reader.join(5000);
		assertFalse(reader.isAlive(), "reader thread did not finish in time");
		assertNull(failure.get());
		return seen.get();
	}
}
