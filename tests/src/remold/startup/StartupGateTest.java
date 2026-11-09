package remold.startup;

import org.junit.jupiter.api.Test;
import remold.StartupGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StartupGate} 的无 sleep 契约测试。
 *
 * <p><b>守的是什么</b>：启动期（类加载器 + Mixin 转换器高频并发加载）不许落热更批次。
 * 门闩是这条约束的唯一状态载体，因此这里锁三件事：① PENDING 期间绝不放行；
 * ② 就绪变迁只发生一次（日志"每次状态变化打一条"依赖它）；③ 超时放行是显式选择，
 * 默认不生效，且必须是"纯时间推进"，不能靠 wall clock。</p>
 */
class StartupGateTest {

	private static final long MS = 1_000_000L;

	@Test
	void pendingGateNeverReportsReady() {
		StartupGate gate = new StartupGate(StartupGate.DEFAULT_READY_TIMEOUT_MS);
		assertFalse(gate.isReady(), "a fresh gate must be PENDING");
		assertFalse(gate.isReadyAt(0L), "PENDING must not report ready at t=0");
		assertFalse(gate.isReadyAt(365L * 24 * 3600 * 1000L * MS),
		 "with ready_timeout_ms=0 the gate must never auto-release, however long we wait");
		assertEquals(0L, gate.getTimeoutMillis(), "default timeout is 0 (no auto release)");
	}

	@Test
	void markReadyTransitionsExactlyOnce() {
		StartupGate gate = new StartupGate(0L);
		assertTrue(gate.markReady(), "first markReady must flip PENDING -> READY");
		assertTrue(gate.isReady());
		assertFalse(gate.markReady(), "second markReady must report no state change (log-once contract)");
		assertTrue(gate.isReadyAt(0L));
		assertFalse(gate.isTimedOutRelease(), "an explicit markReady is not a timeout release");
	}

	@Test
	void timeoutAutoReleaseOnlyAfterTheConfiguredWindow() {
		StartupGate gate = new StartupGate(2_000L);
		// First observation arms the countdown: this call must still be PENDING.
		assertFalse(gate.isReadyAt(1_000 * MS), "the first observation only arms the countdown");
		assertFalse(gate.isReadyAt(2_999 * MS), "just before the window must stay PENDING");
		assertFalse(gate.isReady(), "isReady() does not advance time, so it is still PENDING");
		assertTrue(gate.isReadyAt(3_000 * MS), "at exactly ready_timeout_ms the gate releases");
		assertTrue(gate.isTimedOutRelease(), "the release must be attributable to the timeout, not to the host");
		assertTrue(gate.isReady());
	}

	@Test
	void timeoutDoesNotRearmAfterMarkReady() {
		StartupGate gate = new StartupGate(1_000L);
		assertTrue(gate.markReady());
		assertTrue(gate.isReadyAt(0L));
		assertFalse(gate.isTimedOutRelease());
		assertEquals(-1L, gate.getPendingElapsedNanos(0L), "a READY gate has no pending elapsed time");
	}

	@Test
	void pendingElapsedTracksTheTimeSinceFirstObservation() {
		StartupGate gate = new StartupGate(5_000L);
		assertEquals(-1L, gate.getPendingElapsedNanos(0L), "elapsed is undefined before the first observation");
		gate.isReadyAt(400 * MS);
		assertEquals(100 * MS, gate.getPendingElapsedNanos(500 * MS));
	}

	@Test
	void resolveTimeoutPropertyIsPureAndFailsClosed() {
		assertEquals(StartupGate.DEFAULT_READY_TIMEOUT_MS, StartupGate.resolveTimeoutMillis(null),
		 "absent property keeps the default (no auto release)");
		assertEquals(StartupGate.DEFAULT_READY_TIMEOUT_MS, StartupGate.resolveTimeoutMillis("   "),
		 "blank property keeps the default");
		assertEquals(StartupGate.DEFAULT_READY_TIMEOUT_MS, StartupGate.resolveTimeoutMillis("not-a-number"),
		 "a typo must never turn the gate into a permanent blocker or a permanent opener");
		assertEquals(StartupGate.DEFAULT_READY_TIMEOUT_MS, StartupGate.resolveTimeoutMillis("-1"),
		 "negative values clamp to the default (no auto release)");
		assertEquals(30_000L, StartupGate.resolveTimeoutMillis("30000"));
		assertEquals(30_000L, StartupGate.resolveTimeoutMillis(" 30000 "));
	}
}
