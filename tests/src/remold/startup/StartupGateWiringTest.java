package remold.startup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import remold.DebounceWindow;
import remold.HotswapPlugin;
import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动就绪门闩的漏斗接线测试：<b>未就绪时不落批次、就绪后只跑一次</b>。
 *
 * <p><b>为什么必须驱动真实漏斗</b>：门闩只在 {@code RemoldAgent.triggerDueHotswap()} 这一条
 * 唯一批次入口上生效。单独测 {@link remold.StartupGate} 只能证明状态机对，不能证明
 * "未就绪时变更真的留在队列里、插件回调真的没被调用"。这里通过反射调用真实的
 * {@code triggerDueHotswap}（伪造 {@code inst} 为 Instrumentation 动态代理），断言落在
 * 业务终局：队列里还剩什么、插件回调被叫了几次。</p>
 *
 * <p><b>对照组</b>（缺一不可）：</p>
 * <ul>
 *   <li>去触发：同一批变更在宿主未就绪时不许被 drain（负向阻断）；</li>
 *   <li>换无关输入：同一个实现、同一批变更，只把 {@code isHostReady()} 从 false 换成 true，
 *       断言立刻 drain 且插件回调恰好一次（正向放行）。</li>
 * </ul>
 */
class StartupGateWiringTest {

	private static final Path CLASS_FILE  = Paths.get("build", "startup-gate", "Counter.class");
	private static final Path ASSET_FILE  = Paths.get("build", "startup-gate", "sheet.png");
	private static final Path CLASS_FILE2 = Paths.get("build", "startup-gate", "Second.class");

	private RecordingLogger   logger;
	private StubPlugin        plugin;
	private Instrumentation   savedInst;
	private long              savedClassWindowMs;
	private long              savedAssetWindowMs;

	// region 反射句柄
	private static Field field(String name) {
		try {
			Field f = RemoldAgent.class.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("RemoldAgent." + name + " is gone; the wiring test must be updated", e);
		}
	}

	private static Method method(String name, Class<?>... types) {
		try {
			Method m = RemoldAgent.class.getDeclaredMethod(name, types);
			m.setAccessible(true);
			return m;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("RemoldAgent." + name + " is gone; the wiring test must be updated", e);
		}
	}

	private static final Field PENDING_CLASSES = field("pendingChanges");
	private static final Field PENDING_ASSETS  = field("pendingAssetChanges");
	private static final Field PENDING_JARS    = field("pendingJars");
	private static final Field DEBOUNCE_FIELD  = field("DEBOUNCE");
	private static final Field INST_FIELD      = field("inst");
	private static final Field SCHEDULED_TASK  = field("scheduledTask");
	private static final Method TRIGGER_DUE    = method("triggerDueHotswap");
	// endregion

	@SuppressWarnings("unchecked")
	private static Set<Path> pendingClasses() {
		try {
			return (Set<Path>) PENDING_CLASSES.get(null);
		} catch (IllegalAccessException e) {
			throw new AssertionError(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static Set<Path> pendingAssets() {
		try {
			return (Set<Path>) PENDING_ASSETS.get(null);
		} catch (IllegalAccessException e) {
			throw new AssertionError(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static Set<Path> pendingJars() {
		try {
			return (Set<Path>) PENDING_JARS.get(null);
		} catch (IllegalAccessException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * 通过反射读门闩状态：断言只看业务终局（队列里还剩什么），不硬链接新 API。
	 *
	 * <p>这样反向移除实现时测试仍然<b>编译得过</b>，红的是行为断言而不是编译错误
	 * ——编译失败会把"变异击杀"变成假阳性。</p>
	 */
	private static boolean gateReady() {
		try {
			Object gate = RemoldAgent.class.getMethod("startupGate").invoke(null);
			return gate != null && (Boolean) gate.getClass().getMethod("isReady").invoke(gate);
		} catch (ReflectiveOperationException e) {
			return false; // 实现里没有门闩：等价于"永远未就绪"，由行为断言负责变红
		}
	}

	/** 复位门闩；实现里没有门闩时无事可做。 */
	private static void resetGate() {
		try {
			RemoldAgent.class.getMethod("resetStartupGate").invoke(null);
		} catch (ReflectiveOperationException ignored) {
			// 反向变异（无门闩实现）路径
		}
	}

	private static DebounceWindow debounce() {
		try {
			return (DebounceWindow) DEBOUNCE_FIELD.get(null);
		} catch (IllegalAccessException e) {
			throw new AssertionError(e);
		}
	}

	/** 排队一批变更，但<b>不</b>通过 handleXxx（那里会顺带挂一个 0 延迟的调度任务，破坏确定性）。 */
	private static void enqueue(Set<Path> classes, Set<Path> assets) {
		long now = System.nanoTime();
		// 生产代码用 `synchronized (pendingChanges)`：这里必须用同一个监视器对象，
		// 否则测试与批次线程会各拿一把锁，"变更还在队列里"的断言就不可信了。
		Set<Path> monitor = pendingClasses();
		synchronized (monitor) {
			if (!classes.isEmpty()) {
				monitor.addAll(classes);
				debounce().onClass(now);
			}
			if (!assets.isEmpty()) {
				pendingAssets().addAll(assets);
				debounce().onAsset(now);
			}
		}
	}

	private static void trigger() {
		try {
			TRIGGER_DUE.invoke(null);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("triggerDueHotswap threw: " + e.getCause(), e);
		}
	}

	private static Instrumentation fakeInstrumentation() {
		InvocationHandler handler = (proxy, m, args) -> {
			switch (m.getName()) {
				case "getAllLoadedClasses": return new Class<?>[0];
				case "getInitiatedClasses": return new Class<?>[0];
				case "isRetransformClassesSupported": return true;
				case "isRedefineClassesSupported": return true;
				case "isNativeMethodPrefixSupported": return false;
				case "toString": return "fake-Instrumentation";
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				default: break;
			}
			Class<?> rt = m.getReturnType();
			if (rt == boolean.class) return false;
			if (rt == int.class) return 0;
			if (rt == long.class) return 0L;
			if (rt == void.class) return null;
			return null;
		};
		return (Instrumentation) Proxy.newProxyInstance(
		 StartupGateWiringTest.class.getClassLoader(),
		 new Class<?>[]{Instrumentation.class}, handler);
	}

	@BeforeEach
	void setUp() throws Exception {
		logger = new RecordingLogger();
		// 保存并替换日志器：本类断言的是 [READY-GATE] 的条数，必须直接读日志通道。
		logger.install();
		savedInst = RemoldAgent.getInstrumentation();
		INST_FIELD.set(null, fakeInstrumentation());
		savedClassWindowMs = debounce().getClassWindowMillis();
		savedAssetWindowMs = debounce().getAssetWindowMillis();
		debounce().setWindows(0, 0);
		clearQueues();
		cancelScheduledTask();
		resetGate();
		plugin = new StubPlugin(false);
		RemoldAgent.registerPlugin(plugin);
	}

	@AfterEach
	void tearDown() throws Exception {
		try {
			RemoldAgent.unregisterPlugin(plugin);
			cancelScheduledTask();
			clearQueues();
			debounce().setWindows(savedClassWindowMs, savedAssetWindowMs);
			resetGate();
			INST_FIELD.set(null, savedInst);
		} finally {
			logger.restore();
		}
	}

	private static void clearQueues() {
		synchronized (pendingClasses()) {
			pendingClasses().clear();
			pendingAssets().clear();
			pendingJars().clear();
		}
	}

	private static void cancelScheduledTask() {
		try {
			ScheduledFuture<?> task = (ScheduledFuture<?>) SCHEDULED_TASK.get(null);
			if (task != null) task.cancel(false);
			SCHEDULED_TASK.set(null, null);
		} catch (IllegalAccessException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void notReadyHostDefersTheBatchAndKeepsEveryChangeQueued() {
		enqueue(Set.of(CLASS_FILE), Set.of(ASSET_FILE));

		trigger();

		assertTrue(pendingClasses().contains(CLASS_FILE),
		 "while the host is not ready the class change must stay queued (no drain into a batch)");
		assertTrue(pendingAssets().contains(ASSET_FILE),
		 "asset callbacks must be deferred as well, otherwise the plugin sees a half-batch");
		assertEquals(0, plugin.assetCalls.size(), "no batch may run while the host is not ready");
		assertFalse(gateReady(), "the gate must stay PENDING");

		assertEquals(1, logger.count("[READY-GATE] hot-swap batches deferred"),
		 "the deferral must be reported exactly once, naming the blocker");
		assertTrue(logger.joined().contains("stub-startup-gate"),
		 "the deferral line must name the plugin that vetoed, otherwise it is not attributable");
	}

	@Test
	void repeatedDeferralsOfTheSameBatchAreLoggedOnlyOnce() {
		enqueue(Set.of(CLASS_FILE), Set.of());

		trigger();
		trigger();
		trigger();

		assertTrue(pendingClasses().contains(CLASS_FILE));
		assertEquals(1, logger.count("[READY-GATE] hot-swap batches deferred"),
		 "re-probing a still-pending batch must not spam one line per retry");
	}

	@Test
	void readyHostRunsTheQueuedBatchExactlyOnce() {
		enqueue(Set.of(CLASS_FILE), Set.of(ASSET_FILE));

		// Negative control first: the very same input is deferred while the host is not ready.
		trigger();
		assertEquals(0, plugin.assetCalls.size());
		assertTrue(pendingClasses().contains(CLASS_FILE));

		// Now flip only the readiness signal; everything else stays identical.
		plugin.hostReady = true;
		trigger();

		assertFalse(pendingClasses().contains(CLASS_FILE),
		 "once the host is ready the queued class change must leave the queue in this batch");
		assertFalse(pendingAssets().contains(ASSET_FILE), "the asset change must be delivered in that same batch");
		assertEquals(1, plugin.assetCalls.size(), "the deferred asset batch must be delivered exactly once");
		assertTrue(gateReady(), "the first all-ready observation opens the gate");
		assertEquals(1, logger.count("[READY-GATE] host reported ready"),
		 "the PENDING -> READY transition must be logged exactly once");

		// Second trigger with an empty queue must not re-deliver anything.
		trigger();
		assertEquals(1, plugin.assetCalls.size(), "nothing may be delivered twice");
		assertEquals(1, logger.count("[READY-GATE] host reported ready"),
		 "a gate that is already READY must not log the transition again");
	}

	@Test
	void pluginVetoIsHonoredEvenAfterTheGateOpened() {
		enqueue(Set.of(CLASS_FILE), Set.of());
		plugin.hostReady = true;
		trigger();
		assertFalse(pendingClasses().contains(CLASS_FILE));
		assertTrue(gateReady());

		// The host reports "not safe right now" again: conservative behaviour wins (fail-closed).
		plugin.hostReady = false;
		enqueue(Set.of(CLASS_FILE2), Set.of());
		trigger();

		assertTrue(pendingClasses().contains(CLASS_FILE2),
		 "a plugin veto must still defer batches after the startup window has passed");
		assertEquals(1, logger.count("[READY-GATE] hot-swap batches deferred"),
		 "a new blocking episode gets its own single line");
	}

	@Test
	void missingPluginReadinessDefaultsToTrueAndKeepsBehaviourUnchanged() {
		// No extra plugin: with every registered plugin on the default isHostReady()==true,
		// the gate must open on the first observation and the batch must run.
		RemoldAgent.unregisterPlugin(plugin);
		assertTrue(new DefaultReadyPlugin().isHostReady(),
		 "the interface default must be true so existing plugins keep their behaviour (binary compatibility)");
		enqueue(Set.of(CLASS_FILE), Set.of());
		trigger();
		assertFalse(pendingClasses().contains(CLASS_FILE), "default readiness must not block hot swap");
		assertTrue(gateReady());
		assertEquals(0, logger.count("[READY-GATE] hot-swap batches deferred"));
	}

	// region fixtures

	/** {@code isHostReady()} 用默认实现的插件：既有插件（没跟上这个回调）必须行为不变。 */
	private static final class DefaultReadyPlugin implements HotswapPlugin {
		@Override
		public String getName() {
			return "stub-default-ready";
		}
	}

	private static final class StubPlugin implements HotswapPlugin {
		volatile boolean hostReady;
		final List<Set<Path>> assetCalls = new CopyOnWriteArrayList<>();

		StubPlugin(boolean hostReady) {
			this.hostReady = hostReady;
		}

		@Override
		public String getName() {
			return "stub-startup-gate";
		}

		@Override
		public boolean isHostReady() {
			return hostReady;
		}

		@Override
		public void onAssetsChanged(Set<Path> changedAssets) {
			assetCalls.add(new HashSet<>(changedAssets));
		}
	}

	/** 只记录、不落盘；断言 [READY-GATE] 的条数与归因文本。 */
	private static final class RecordingLogger implements RemoldAgent.Logger {
		private final List<String> lines = new ArrayList<>();
		private RemoldAgent.Logger previous;

		void install() {
			previous = RemoldAgent.logger;
			RemoldAgent.logger = this;
			assertNotNull(previous, "there must be a default logger to restore");
		}

		void restore() {
			if (previous != null) RemoldAgent.logger = previous;
		}

		synchronized int count(String prefix) {
			int n = 0;
			for (String line : lines) {
				if (line.startsWith(prefix)) n++;
			}
			return n;
		}

		synchronized String joined() {
			return String.join("\n", lines);
		}

		private synchronized void record(String level, String msg) {
			lines.add(msg == null ? "" : msg);
		}

		@Override public void log(String msg) { record("DEBUG", msg); }
		@Override public void info(String msg) { record("INFO", msg); }
		@Override public void warn(String msg) { record("WARN", msg); }
		@Override public void error(String msg) { record("ERROR", msg); }
		@Override public void error(String msg, Throwable t) { record("ERROR", msg); }
	}
	// endregion
}
