package remold.startup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import remold.BatchWatchdog;
import remold.BytecodeTransformerBridge;
import remold.DebounceWindow;
import remold.HotswapPlugin;
import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 看门狗<b>全入口覆盖</b>测试：防抖批次、手动 {@code triggerHotswap}、{@code retransformLoaded}
 * 三条路径都必须真的挂上看门狗，且<b>同一批次只 dump 一次</b>。
 *
 * <p><b>守的是什么</b>：看门狗原来只包住防抖 Class 批次（{@code runClassBatch}），手动入口与
 * {@code retransformLoaded} 卡死时日志里不会留下任何线程栈——而"没有输出"与"挂死"在真机上
 * 长得一模一样。把看门狗下沉到 {@code processChanges} 后，还要守住两个反向风险：</p>
 * <ul>
 *   <li><b>漏挂</b>：某条入口没有看门狗 → 本类的入口用例会红（看门狗从未被 arm）；</li>
 *   <li><b>重复注册</b>：把下沉后的看门狗留在 {@code runClassBatch} 上 → 同一批次出现两条
 *       {@code [HOTSWAP-STALL]} → 前缀被训练成噪声，等于把仪器关掉。</li>
 * </ul>
 *
 * <p><b>怎么做到确定性</b>：不 sleep、不赌定时器。批次体内通过两条"批次内必然被调用"的钩子
 * 拿到 {@link BatchWatchdog#current()} 并直接驱动 {@code stallCheck(未来读数)}：</p>
 * <ul>
 *   <li>{@code processChanges} 里的字节码重织入桥（{@link RemoldAgent#addTransformerBridge}）；</li>
 *   <li>{@code retransformLoaded} 里的 {@code Instrumentation.retransformClasses}（替身直接执行）。</li>
 * </ul>
 * <p>因此"看门狗在这个批次内已登记"与"第二次判定不再 dump"都是业务终局断言，而不是日志观测。</p>
 */
class BatchWatchdogCoverageTest {

	private static final long STALL_MS = BatchWatchdog.MIN_STALL_MS;
	private static final long STALL_NS = STALL_MS * 1_000_000L;

	/** 批次体内的伪类：不加载、不重定义，只用来让重织入桥在批次内被调用。 */
	private static final String SUBJECT_INTERNAL_NAME = "remold/startup/watchdog/BatchWatchdogCoverageSubject";
	private static final String SUBJECT_CLASS_NAME    = "remold.startup.watchdog.BatchWatchdogCoverageSubject";

	private final List<String>     reports = new CopyOnWriteArrayList<>();
	private final InBatchRecorder  recorder = new InBatchRecorder();
	private final AtomicReference<BatchWatchdog> created = new AtomicReference<>();

	private FakeInstrumentation fake;
	private Instrumentation     savedInst;
	private long                savedClassWindowMs;
	private long                savedAssetWindowMs;
	private RecordingLogger     logger;
	private StubPlugin          plugin;

	// region 反射句柄
	private static Field field(String name) {
		try {
			Field f = RemoldAgent.class.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("RemoldAgent." + name + " is gone; the coverage test must be updated", e);
		}
	}

	private static Method method(String name, Class<?>... types) {
		try {
			Method m = RemoldAgent.class.getDeclaredMethod(name, types);
			m.setAccessible(true);
			return m;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("RemoldAgent." + name + " is gone; the coverage test must be updated", e);
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

	private static DebounceWindow debounce() {
		try {
			return (DebounceWindow) DEBOUNCE_FIELD.get(null);
		} catch (IllegalAccessException e) {
			throw new AssertionError(e);
		}
	}

	private static void enqueue(Set<Path> classes) {
		long now = System.nanoTime();
		Set<Path> monitor = pendingClasses();
		synchronized (monitor) {
			monitor.addAll(classes);
			debounce().onClass(now);
		}
	}

	private static void triggerDue() {
		try {
			TRIGGER_DUE.invoke(null);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("triggerDueHotswap threw: " + e.getCause(), e);
		}
	}

	@BeforeEach
	void setUp() throws Exception {
		logger = new RecordingLogger();
		logger.install();
		fake = new FakeInstrumentation();
		savedInst = RemoldAgent.getInstrumentation();
		INST_FIELD.set(null, fake.proxy());
		savedClassWindowMs = debounce().getClassWindowMillis();
		savedAssetWindowMs = debounce().getAssetWindowMillis();
		debounce().setWindows(0, 0);
		clearQueues();
		cancelScheduledTask();
		RemoldAgent.resetStartupGate();
		plugin = new StubPlugin();
		RemoldAgent.registerPlugin(plugin);
		// 伪类只作为"批次体内可观测的字节码"存在：登记成不可加载模板，避免它被真的 defineClass。
		RemoldAgent.markNonLoadableClass(SUBJECT_CLASS_NAME);
		BatchWatchdog.setWatchdogFactory(ms -> {
			BatchWatchdog watchdog = new BatchWatchdog(ms, reports::add);
			created.set(watchdog);
			return watchdog;
		});
	}

	@AfterEach
	void tearDown() throws Exception {
		try {
			BatchWatchdog.setWatchdogFactory(null);
			for (BytecodeTransformerBridge bridge : bridges) {
				RemoldAgent.removeTransformerBridge(bridge);
			}
			bridges.clear();
			RemoldAgent.unregisterPlugin(plugin);
			cancelScheduledTask();
			clearQueues();
			debounce().setWindows(savedClassWindowMs, savedAssetWindowMs);
			RemoldAgent.resetStartupGate();
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

	/** 生成一个最小类文件：批次只把它当"磁盘上改动的类"读盘，桥接阶段就是它的观测点。 */
	private static Path writeSubjectClassFile() throws Exception {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, SUBJECT_INTERNAL_NAME, null, "java/lang/Object", null);
		cw.visitEnd();
		Path file = Paths.get("build", "watchdog-coverage", "BatchWatchdogCoverageSubject.class");
		Files.createDirectories(file.getParent());
		Files.write(file, cw.toByteArray());
		return file;
	}

	/** 把"批次内看门狗状态"的观测点装到重织入桥上（{@code processChanges} 必然经过它）。 */
	private void installInBatchBridge() {
		BytecodeTransformerBridge bridge = (loader, dotClassName, targetClass, bytecode) -> {
			if (SUBJECT_CLASS_NAME.equals(dotClassName)) recorder.record();
			return null;
		};
		RemoldAgent.addTransformerBridge(bridge);
		bridges.add(bridge);
	}

	private final List<BytecodeTransformerBridge> bridges = new ArrayList<>();

	/**
	 * 批次内观测：看门狗必须已 arm；连续两次 {@code stallCheck} 只允许第一次 dump。
	 *
	 * <p>时钟是"未来读数"，因此判定与真实阈值无关——不需要 sleep，也不依赖机器快慢。</p>
	 */
	private final class InBatchRecorder {
		final List<Integer> armedWatchdogs = new CopyOnWriteArrayList<>();
		final List<Boolean> armed = new CopyOnWriteArrayList<>();
		final List<Boolean> dumps = new CopyOnWriteArrayList<>();

		void record() {
			// 同一批次只允许一个 armed 看门狗：出现 2 就说明同一批次挂了两份仪器
			// （典型回归：把看门狗留在 runClassBatch 上、又下沉到 processChanges），
			// 超时后会打出两条 [HOTSWAP-STALL]。
			armedWatchdogs.add(BatchWatchdog.armedWatchdogs());
			BatchWatchdog watchdog = BatchWatchdog.current();
			armed.add(watchdog != null && watchdog.isArmed());
			if (watchdog == null) return;
			dumps.add(watchdog.stallCheck(System.nanoTime() + 100 * STALL_NS));
			dumps.add(watchdog.stallCheck(System.nanoTime() + 100 * STALL_NS));
		}

		void reset() {
			armedWatchdogs.clear();
			armed.clear();
			dumps.clear();
		}
	}

	/** 断言"本批次恰好挂了一个看门狗、恰好 dump 一次、结束后干净注销"。 */
	private void assertExactlyOneDumpForThisBatch(String entry) {
		assertEquals(1, recorder.armed.size(), entry + ": the in-batch probe must run exactly once per batch");
		assertEquals(List.of(1), recorder.armedWatchdogs,
		 entry + ": exactly one watchdog may be armed for one batch (two would print two [HOTSWAP-STALL])");
		assertEquals(List.of(Boolean.TRUE), recorder.armed,
		 entry + ": the watchdog must already be armed while the batch body runs");
		assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), recorder.dumps,
		 entry + ": the first in-batch stall check dumps, the second must not (one dump per batch)");
		assertEquals(1, reports.size(), entry + ": exactly one [HOTSWAP-STALL] dump for this batch");

		BatchWatchdog watchdog = created.get();
		assertNotNull(watchdog, entry + ": the batch must have created a watchdog");
		assertFalse(watchdog.isArmed(), entry + ": the batch must disarm the watchdog when it ends");
		assertFalse(watchdog.hasPendingTask(), entry + ": no scheduled task may survive the batch");
		assertNull(BatchWatchdog.current(), entry + ": the batch must leave no watchdog bound to the thread");
		assertEquals(0, BatchWatchdog.armedWatchdogs(), entry + ": the batch must leave no armed watchdog behind");
	}

	// region 入口覆盖

	@Test
	void debounceBatchArmsTheWatchdogInsideProcessChanges() throws Exception {
		installInBatchBridge();

		enqueue(Set.of(writeSubjectClassFile()));
		triggerDue();

		assertExactlyOneDumpForThisBatch("debounce batch");
	}

	@Test
	void manualTriggerHotswapArmsTheWatchdogToo() throws Exception {
		installInBatchBridge();

		enqueue(Set.of(writeSubjectClassFile()));
		RemoldAgent.triggerHotswap();

		// 反向风险：若看门狗仍只包在 runClassBatch 上，这条路径的 dump 数为 0（本断言变红）。
		assertExactlyOneDumpForThisBatch("manual triggerHotswap");
	}

	@Test
	void retransformLoadedArmsTheWatchdog() {
		fake.modifiable = true;
		fake.loadedClasses = new Class<?>[]{BatchWatchdogCoverageTest.class};
		fake.onRetransformClasses = recorder::record;

		RemoldAgent.retransformLoaded();

		assertExactlyOneDumpForThisBatch("retransformLoaded");
	}

	@Test
	void interleavedEntriesProduceExactlyOneDumpPerBatch() throws Exception {
		installInBatchBridge();
		Path subject = writeSubjectClassFile();

		enqueue(Set.of(subject));
		triggerDue();
		assertEquals(1, reports.size(), "first (debounce) batch: exactly one dump");

		enqueue(Set.of(subject));
		RemoldAgent.triggerHotswap();
		assertEquals(2, reports.size(),
		 "second (manual) batch: exactly one more dump — the previous batch's watchdog must not be reused,"
		 + " and a double registration would have produced two");

		enqueue(Set.of(subject));
		triggerDue();
		assertEquals(3, reports.size(), "third (debounce) batch: exactly one more dump");

		assertEquals(6, recorder.dumps.size(), "three batches, two checks each");
		for (int batch = 0; batch < 3; batch++) {
			assertEquals(Boolean.TRUE, recorder.dumps.get(batch * 2),
			 "batch " + batch + ": the first in-batch check must dump");
			assertEquals(Boolean.FALSE, recorder.dumps.get(batch * 2 + 1),
			 "batch " + batch + ": the second in-batch check must stay silent (one dump per batch)");
		}
	}

	// endregion

	// region 异常路径注销

	@Test
	void watchdogIsUnregisteredWhenTheBatchThrows() {
		// 批次体在 refreshPackageLoaders 上 NPE：异常必须穿过看门狗作用域，注销由 finally 保证。
		fake.loadedClasses = new Class<?>[]{null};
		enqueue(Set.of(Paths.get("build", "watchdog-coverage", "does-not-matter.class")));

		triggerDue();

		assertTrue(logger.joined().contains("[HOTSWAP] Hot-swap pipeline aborted"),
		 "the exception path must be reported (otherwise the test cannot tell it from a silent batch)");

		BatchWatchdog watchdog = created.get();
		assertNotNull(watchdog, "the watchdog must have been armed before the body threw");
		assertFalse(watchdog.isArmed(), "an aborted batch must still disarm the watchdog");
		assertFalse(watchdog.hasPendingTask(), "an aborted batch must not leave a scheduled task behind");
		assertNull(BatchWatchdog.current(), "the aborted batch must not leave a watchdog bound to the thread");
		assertFalse(watchdog.stallCheck(System.nanoTime() + 100 * STALL_NS),
		 "a disarmed watchdog must never dump later (a residual task would produce a bogus stall report)");
		assertEquals(0, reports.size(), "no stall dump may be produced by an aborted batch");
	}

	// endregion

	// region fixtures

	/** 嵌套 enter 只登记一次：同一批次内的重入复用同一个看门狗，最外层 exit 才注销。 */
	@Test
	void nestedEnterReusesTheSameWatchdog() {
		BatchWatchdog outer = BatchWatchdog.enter(STALL_MS, "outer");
		try {
			BatchWatchdog inner = BatchWatchdog.enter(STALL_MS, "inner");
			assertEquals(outer, inner, "a nested enter inside the same batch must reuse the watchdog");
			BatchWatchdog.exit();
			assertTrue(outer.isArmed(), "the inner exit must not disarm the still-running outer batch");
			assertEquals(outer, BatchWatchdog.current(), "the outer batch must stay bound to the thread");
		} finally {
			BatchWatchdog.exit();
		}
		assertFalse(outer.isArmed(), "the outermost exit must disarm");
		assertNull(BatchWatchdog.current(), "the outermost exit must unbind the thread");
		assertFalse(outer.hasPendingTask(), "the outermost exit must cancel the scheduled task");
	}

	private static final class StubPlugin implements HotswapPlugin {
		@Override
		public String getName() {
			return "stub-watchdog-coverage";
		}

		@Override
		public boolean isHostReady() {
			return true;
		}
	}

	/** 只记录、不落盘：本类断言的是"aborted"上报与批次终局，不读控制台。 */
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

	/** 动态代理替身：只实现被测路径真正用到的 Instrumentation 方法。 */
	private static final class FakeInstrumentation implements InvocationHandler {
		volatile Class<?>[] loadedClasses = new Class<?>[0];
		volatile Runnable   onRetransformClasses = () -> { };
		volatile boolean    modifiable;

		Instrumentation proxy() {
			return (Instrumentation) Proxy.newProxyInstance(
			 BatchWatchdogCoverageTest.class.getClassLoader(),
			 new Class<?>[]{Instrumentation.class}, this);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			switch (method.getName()) {
				case "getAllLoadedClasses": return loadedClasses;
				case "getInitiatedClasses": return new Class<?>[0];
				case "isRetransformClassesSupported": return true;
				case "isRedefineClassesSupported": return true;
				case "isModifiableClass": return modifiable;
				case "retransformClasses": onRetransformClasses.run(); return null;
				case "toString": return "fake-Instrumentation";
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				default: break;
			}
			Class<?> rt = method.getReturnType();
			if (rt == boolean.class) return false;
			if (rt == int.class) return 0;
			if (rt == long.class) return 0L;
			return null;
		}
	}
	// endregion
}
