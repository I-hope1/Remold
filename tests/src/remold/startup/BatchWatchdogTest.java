package remold.startup;

import org.junit.jupiter.api.Test;
import remold.BatchWatchdog;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BatchWatchdog} 的反假阳/反刷屏契约测试。
 *
 * <p><b>守的是什么</b>：启动期批次挂死的精确线程栈从来没抓到过，看门狗是唯一能在
 * 挂死现场留下证据的仪器。仪器有两种坏法，本类各锁一条：</p>
 * <ul>
 *   <li><b>该动静的时候不响</b>（阈值判定失效）→ 挂死时什么都没有；</li>
 *   <li><b>不该响的时候乱响</b>（阈值前/批次结束后/同批次反复 dump）→ 前缀被忽略，
 *       等于把仪器关掉。</li>
 * </ul>
 *
 * <p>判定完全由 {@link BatchWatchdog#stallCheck(long)} 的入参时钟驱动，
 * 因此没有任何 sleep（除了最后一条真调度用例，它靠"把批次开始时间设到过去"
 * 让任务立刻触发，而不是等 5 秒）。</p>
 */
class BatchWatchdogTest {

	private static final long MS = 1_000_000L;
	private static final long STALL_MS = BatchWatchdog.MIN_STALL_MS;
	private static final long STALL_NS = STALL_MS * MS;

	private static final class RecordingSink implements BatchWatchdog.StallSink {
		final List<String> reports = new CopyOnWriteArrayList<>();

		@Override
		public void dump(String report) {
			reports.add(report);
		}

		int count() {
			return reports.size();
		}

		String only() {
			assertEquals(1, reports.size(), "expected exactly one dump");
			return reports.get(0);
		}
	}

	@Test
	void doesNotDumpBeforeTheThreshold() {
		RecordingSink sink = new RecordingSink();
		BatchWatchdog watchdog = new BatchWatchdog(STALL_MS, sink);
		try {
			long base = System.nanoTime();
			watchdog.arm(base, "batch-before-threshold");

			assertFalse(watchdog.stallCheck(base + STALL_NS - 1),
			 "one nanosecond short of the threshold must not dump");
			assertEquals(0, sink.count(), "no dump before the threshold");
			assertFalse(watchdog.hasDumped(), "the once-flag must stay clear");

			// Threshold is inclusive: the batch that is still running at exactly stall_ms has stalled.
			assertTrue(watchdog.stallCheck(base + STALL_NS), "at the threshold the watchdog must dump");
			assertEquals(1, sink.count());
			assertTrue(watchdog.hasDumped());
		} finally {
			watchdog.disarm();
		}
	}

	@Test
	void dumpsTheFullThreadStackExactlyOncePerBatch() {
		RecordingSink sink = new RecordingSink();
		BatchWatchdog watchdog = new BatchWatchdog(STALL_MS, sink);
		try {
			long base = System.nanoTime();
			watchdog.arm(base, "batch-once");

			assertTrue(watchdog.stallCheck(base + STALL_NS + 5 * MS));
			String report = sink.only();

			assertTrue(report.startsWith(BatchWatchdog.PREFIX), "every dump line must carry the grep-able prefix");
			assertTrue(report.contains("batch-once"), "the dump must name the stale batch");
			assertTrue(report.contains("dumpAllThreads(true, true)"),
			 "the dump must be produced by ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)");
			assertTrue(report.contains(Thread.currentThread().getName()),
			 "the stalling batch thread must appear in the dump (attribution)");
			assertTrue(report.contains("BatchWatchdogTest"),
			 "the dump must contain real stack frames, not just headers");
			assertTrue(report.contains("state="), "thread entries must carry the thread state");

			// Second probe inside the same batch: no second dump (otherwise a hang floods the log).
			assertFalse(watchdog.stallCheck(base + 2 * STALL_NS + 60 * MS),
			 "a frozen batch must not dump again");
			assertEquals(1, sink.count(), "same batch => exactly one dump");
		} finally {
			watchdog.disarm();
		}
	}

	@Test
	void newBatchRearmsTheOnceFlag() {
		RecordingSink sink = new RecordingSink();
		BatchWatchdog watchdog = new BatchWatchdog(STALL_MS, sink);
		try {
			long first = System.nanoTime();
			watchdog.arm(first, "batch-1");
			assertTrue(watchdog.stallCheck(first + STALL_NS));
			assertEquals(1, sink.count());

			long second = System.nanoTime();
			watchdog.arm(second, "batch-2");
			assertFalse(watchdog.hasDumped(), "arming a new batch resets the once-flag");
			assertTrue(watchdog.stallCheck(second + STALL_NS));
			assertEquals(2, sink.count(), "a different batch deserves its own dump");
			assertTrue(sink.reports.get(1).contains("batch-2"));
		} finally {
			watchdog.disarm();
		}
	}

	@Test
	void disarmedWatchdogNeverDumps() {
		RecordingSink sink = new RecordingSink();
		BatchWatchdog watchdog = new BatchWatchdog(STALL_MS, sink);
		long base = System.nanoTime();
		watchdog.arm(base, "batch-finished-in-time");
		watchdog.disarm();

		assertFalse(watchdog.stallCheck(base + 10 * STALL_NS),
		 "a batch that already finished must not produce a stall dump");
		assertEquals(0, sink.count());
		assertFalse(watchdog.isArmed());
	}

	@Test
	void aBrokenSinkCannotBreakTheBatch() {
		BatchWatchdog watchdog = new BatchWatchdog(STALL_MS, report -> {
			throw new IllegalStateException("disk full / stderr closed");
		});
		try {
			long base = System.nanoTime();
			watchdog.arm(base, "batch-broken-sink");
			assertTrue(watchdog.stallCheck(base + STALL_NS),
			 "the stall was still detected even though the sink failed");
			assertTrue(watchdog.hasDumped());
		} finally {
			watchdog.disarm();
		}
	}

	@Test
	void thresholdFloorAndPropertyResolution() {
		RecordingSink sink = new RecordingSink();
		BatchWatchdog watchdog = new BatchWatchdog(10L, sink);
		try {
			assertEquals(BatchWatchdog.MIN_STALL_MS, watchdog.getStallMillis(),
			 "a sub-floor threshold would fire on healthy large batches and train people to ignore the prefix");
		} finally {
			watchdog.disarm();
		}

		assertEquals(BatchWatchdog.DEFAULT_STALL_MS, BatchWatchdog.resolveStallMillis(null),
		 "absent property keeps the 15s default");
		assertEquals(BatchWatchdog.DEFAULT_STALL_MS, BatchWatchdog.resolveStallMillis("  "),
		 "blank property keeps the default");
		assertEquals(BatchWatchdog.DEFAULT_STALL_MS, BatchWatchdog.resolveStallMillis("abc"),
		 "a typo must not disable the watchdog");
		assertEquals(BatchWatchdog.MIN_STALL_MS, BatchWatchdog.resolveStallMillis("1"),
		 "below the floor clamps up to the floor");
		assertEquals(30_000L, BatchWatchdog.resolveStallMillis("30000"));
	}

	@Test
	void scheduledTimerFiresOnceForABatchThatStartedInThePast() throws Exception {
		List<String> reports = new CopyOnWriteArrayList<>();
		CountDownLatch fired = new CountDownLatch(1);
		BatchWatchdog watchdog = new BatchWatchdog(STALL_MS, report -> {
			reports.add(report);
			fired.countDown();
		});
		try {
			// Batch started long ago and never finished => the one-shot timer must fire immediately.
			watchdog.arm(System.nanoTime() - 2 * STALL_NS, "batch-stale");
			assertTrue(fired.await(10, TimeUnit.SECONDS),
			 "the watchdog timer must fire on its own for a batch that is already past the threshold");
			assertEquals(1, reports.size());
			assertTrue(reports.get(0).contains("batch-stale"));
		} finally {
			watchdog.disarm();
		}
	}

	@Test
	void scheduledTimerStaysSilentWhenTheBatchEndsInTime() throws Exception {
		List<String> reports = new CopyOnWriteArrayList<>();
		BatchWatchdog watchdog = new BatchWatchdog(STALL_MS, reports::add);
		try {
			watchdog.arm(System.nanoTime(), "batch-healthy");
			// End the batch well before the threshold: the scheduled task must be cancelled.
			watchdog.disarm();
			Thread.sleep(200); // only proves the task was cancelled *and* not retried; no threshold wait
			assertEquals(0, reports.size(), "a healthy batch must never produce a stall dump");
		} finally {
			watchdog.disarm();
		}
	}
}
