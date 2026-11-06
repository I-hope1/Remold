import remold.HotswapBatchListener;
import remold.RemoldAgent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P3: redefineDynamic with no bytecodeCache baseline used to skip LayoutGate/InitFix.
 * The class is loaded before the agent baseline exists; sourceBytecodeCache has v1,
 * so the fallback can still supply a baseline.
 */
public class RedefineDynamicNoBaselineProbe {
	static int passed = 0, failed = 0;
	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static final String V1 = """
		package rd;
		public class Host { public int a = 1; }
		""";
	static final String V2 = """
		package rd;
		public class Host { public int a = 1; public int b = 42; }
		""";

	public static void main(String[] args) throws Exception {
		System.out.println("=== RedefineDynamicNoBaselineProbe ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "instrumentation available");
		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of("rd.Host", V1));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of("rd.Host", V2));
		FixtureLoader loader = new FixtureLoader(v1);
		Class<?> host = Class.forName("rd.Host", true, loader);
		Object instance = host.getConstructor().newInstance();

		RemoldAgent.bytecodeCache.remove("rd.Host");
		RemoldAgent.sourceBytecodeCache.put("rd.Host", v1.get("rd.Host"));
		check(!RemoldAgent.bytecodeCache.containsKey("rd.Host"),
			"precondition: no bytecodeCache baseline");
		check(RemoldAgent.sourceBytecodeCache.containsKey("rd.Host"),
			"precondition: sourceBytecodeCache has v1 as fallback");

		boolean ok = RemoldAgent.redefineDynamic(host, v2.get("rd.Host"));
		check(ok, "redefineDynamic returned true");
		check(hasField(host, "b"), "schema field b exists after redefine");
		int b;
		try {
			var f = host.getDeclaredField("b");
			f.setAccessible(true);
			b = f.getInt(instance);
		} catch (NoSuchFieldException e) {
			b = -1;
		}
		System.out.println("   [OBS] existing instance b=" + b);
		check(b == 42, "existing instance received InitFix patch b=42 (actual=" + b + ")");
		// --- no-op branch: runtime bytes equal the baseline; must return true WITHOUT redefining ---
		Map<String, byte[]> noopBytes = LayoutGateAssert.compile(Map.of("rd.NoOp", """
			package rd;
			public class NoOp { public int a = 1; }
			"""));
		FixtureLoader noopLoader = new FixtureLoader(noopBytes);
		Class<?> noopClass = Class.forName("rd.NoOp", true, noopLoader);
		byte[] runtime = RemoldAgent.fetchBytecodeMemory(noopClass);
		check(runtime != null, "precondition: current runtime bytes readable for the no-op case");
		RemoldAgent.bytecodeCache.put("rd.NoOp", runtime);

		AtomicInteger noopBatches = new AtomicInteger();
		HotswapBatchListener noopListener = new HotswapBatchListener() {
			@Override public void afterInternalBatch(List<ClassDefinition> redefinedClasses) {
				noopBatches.incrementAndGet();
			}
		};
		List<String> infos = new ArrayList<>();
		RemoldAgent.Logger previousLogger = RemoldAgent.logger;
		RemoldAgent.logger = new CapturingLogger(previousLogger, infos);
		RemoldAgent.addBatchListener(noopListener);
		boolean noop;
		try {
			noop = RemoldAgent.redefineDynamic(noopClass, runtime);
		} finally {
			RemoldAgent.removeBatchListener(noopListener);
			RemoldAgent.logger = previousLogger;
		}
		check(noop, "no-op redefineDynamic returns true");
		check(noopBatches.get() == 0,
			"no-op redefineDynamic performs no redefine (batch listener not called, actual=" + noopBatches.get() + ")");
		check(infos.stream().anyMatch(m -> m.contains("[DYNAMIC-REDEFINE] no-op")),
			"no-op branch emits an explicit log line");

		// Control: a real redefine still fires the batch listener exactly once.
		AtomicInteger realBatches = new AtomicInteger();
		HotswapBatchListener realListener = new HotswapBatchListener() {
			@Override public void afterInternalBatch(List<ClassDefinition> redefinedClasses) {
				realBatches.incrementAndGet();
			}
		};
		Map<String, byte[]> v3 = LayoutGateAssert.compile(Map.of("rd.Host", """
			package rd;
			public class Host { public int a = 1; public int b = 42; public int c = 7; }
			"""));
		RemoldAgent.addBatchListener(realListener);
		boolean real;
		try {
			real = RemoldAgent.redefineDynamic(host, v3.get("rd.Host"));
		} finally {
			RemoldAgent.removeBatchListener(realListener);
		}
		check(real, "real redefineDynamic returns true");
		check(realBatches.get() == 1,
			"real redefine fires the batch listener exactly once (actual=" + realBatches.get() + ")");

		// Fail-closed: a second class with no bytecodeCache/source/resource baseline.
		Map<String, byte[]> none = LayoutGateAssert.compile(Map.of("rd.None", """
			package rd;
			public class None { public int a = 1; }
			"""));
		FixtureLoader noneLoader = new FixtureLoader(none);
		Class<?> noneClass = Class.forName("rd.None", true, noneLoader);
		RemoldAgent.bytecodeCache.remove("rd.None");
		RemoldAgent.sourceBytecodeCache.remove("rd.None");
		boolean noneOk = RemoldAgent.redefineDynamic(noneClass, none.get("rd.None"));
		check(!noneOk, "all three baselines missing => fail closed (actual=" + noneOk + ")");
		System.out.println("=== SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	static boolean hasField(Class<?> type, String name) {
		try { type.getDeclaredField(name); return true; }
		catch (NoSuchFieldException e) { return false; }
	}

	static final class CapturingLogger implements RemoldAgent.Logger {
		final RemoldAgent.Logger delegate;
		final List<String> infos;
		CapturingLogger(RemoldAgent.Logger delegate, List<String> infos) {
			this.delegate = delegate;
			this.infos = infos;
		}
		@Override public void log(String msg) { delegate.log(msg); }
		@Override public void info(String msg) { infos.add(msg); delegate.info(msg); }
		@Override public void warn(String msg) { delegate.warn(msg); }
		@Override public void error(String msg) { delegate.error(msg); }
		@Override public void error(String msg, Throwable t) { delegate.error(msg, t); }
	}

	static final class FixtureLoader extends ClassLoader {
		final Map<String, byte[]> classes;
		FixtureLoader(Map<String, byte[]> classes) {
			super(RedefineDynamicNoBaselineProbe.class.getClassLoader());
			this.classes = classes;
		}
		@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}
