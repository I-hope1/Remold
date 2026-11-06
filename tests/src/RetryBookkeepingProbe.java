import remold.ClassDiffUtil;
import remold.RemoldAgent;
import remold.ref.InitFix;
import remold.util.LibTool;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regression probe: a bookkeeping failure in the per-class retry must not be reported as a
 * redefine failure.
 *
 * <p><b>Defect</b>: {@code applyRedefinitions}'s individual retry put {@code bytecodeCache.put},
 * {@code commitInvented} and {@code successfulClasses.add} in the <b>same try</b> as
 * {@code inst.redefineClasses(def)}. If bookkeeping threw after the JVM had already applied the
 * class, the catch treated the class as redefine-failed: it called
 * {@code InitFix.afterRedefineFailed} (popping the pending patch and writing a false ledger entry)
 * and left the class out of the successful set, so the caller never ran {@code afterRedefine}.
 * The class is live but its surviving instances keep the field type defaults.</p>
 *
 * <p>The probe injects the bookkeeping failure without a production seam: it puts a set whose
 * {@code isEmpty()} throws into {@code pendingInvented}, which is exactly what
 * {@code commitInvented} dereferences. Redefinition itself runs for real (good class) or fails
 * deterministically at the {@link Instrumentation} boundary (bulk call + bad class).</p>
 *
 * <p>Run via {@code ./gradlew :tests:retryBookkeepingProbe -Pp05.jbr=<DCEVM/JBR home>}.</p>
 */
public class RetryBookkeepingProbe {

	static int passed = 0;
	static int failed = 0;

	static void check(boolean ok, String msg) {
		if (ok) {
			System.out.println("   [PASS] " + msg);
			passed++;
		} else {
			System.err.println("   [FAIL] " + msg);
			failed++;
		}
	}

	static final String GOOD_V1 = """
		package bookkeep;
		public class Good {
		    public int a = 1;
		    public String hi() { return "v1:" + a; }
		}
		""";

	static final String GOOD_V2 = """
		package bookkeep;
		import remold.annotation.RemoldReinit;
		public class Good {
		    public int a = 1;
		    public int b = 42;
		    @RemoldReinit(mode = RemoldReinit.Mode.OVERWRITE)
		    public int r = 7;
		    public String hi() { return "v2:" + a + "," + b + "," + r; }
		}
		""";

	static final String BAD_V1 = """
		package bookkeep;
		public class Bad {
		    public int a = 1;
		}
		""";

	static final String BAD_V2 = """
		package bookkeep;
		public class Bad {
		    public int a = 1;
		    public int badNew = 99;
		}
		""";

	public static void main(String[] args) throws Throwable {
		System.out.println("=== RetryBookkeepingProbe: redefine success + bookkeeping failure ===");
		System.out.println("   java.vm.name = " + System.getProperty("java.vm.name")
		                   + ", java.version = " + System.getProperty("java.version"));

		Instrumentation real = RemoldAgent.getInstrumentation();
		if (real == null) {
			System.err.println("[FAIL] Instrumentation unavailable; run with -javaagent:remold-agent.jar");
			System.exit(1);
		}
		RemoldAgent.INIT_FIX = true;
		try {
			if (!LibTool.initialized()) LibTool.init();
		} catch (Throwable t) {
			System.err.println("[FAIL] native libtool unavailable; cannot enumerate surviving instances: " + t);
			System.exit(1);
		}

		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of(
			"bookkeep.Good", GOOD_V1,
			"bookkeep.Bad", BAD_V1));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of(
			"bookkeep.Good", GOOD_V2,
			"bookkeep.Bad", BAD_V2));

		FixtureLoader loader = new FixtureLoader(v1);
		Class<?> good = Class.forName("bookkeep.Good", true, loader);
		Class<?> bad = Class.forName("bookkeep.Bad", true, loader);
		Object goodInstance = good.getConstructor().newInstance();
		bad.getConstructor().newInstance();

		checkEq("v1:1", call(good, goodInstance, "hi"), "precondition: Good runs v1");

		// Production order: the runtime baseline is in bytecodeCache before InitFix.transform
		// runs (the oracle resolves nest metadata through that cache).
		RemoldAgent.bytecodeCache.put("bookkeep.Good", v2.get("bookkeep.Good"));
		RemoldAgent.bytecodeCache.put("bookkeep.Bad", v2.get("bookkeep.Bad"));
		InitFix.transform(good, v2.get("bookkeep.Good"),
		                  ClassDiffUtil.diff(v1.get("bookkeep.Good"), v2.get("bookkeep.Good")));
		InitFix.transform(bad, v2.get("bookkeep.Bad"),
		                  ClassDiffUtil.diff(v1.get("bookkeep.Bad"), v2.get("bookkeep.Bad")));
		check(InitFix.getLastReport(good) != null && InitFix.getLastReport(good).patchGenerated(),
			"precondition: Good patch generated");
		check(pendingContains(good), "precondition: Good has a pending patch");

		AtomicInteger bulkCalls = new AtomicInteger();
		AtomicInteger singleCalls = new AtomicInteger();
		Instrumentation injected = failingInstrumentation(real, bulkCalls, singleCalls);
		List<String> bookkeepingErrors = new ArrayList<>();
		RemoldAgent.Logger oldLogger = RemoldAgent.logger;
		RemoldAgent.logger = new RemoldAgent.Logger() {
			public void log(String msg) { }
			public void info(String msg) { }
			public void warn(String msg) { }
			public void error(String msg) { }
			public void error(String msg, Throwable t) {
				if (msg != null && msg.contains("[BOOKKEEPING]")) bookkeepingErrors.add(msg);
			}
		};

		Field instField = RemoldAgent.class.getDeclaredField("inst");
		instField.setAccessible(true);
		instField.set(null, injected);
		Field pendingInventedField = RemoldAgent.class.getDeclaredField("pendingInvented");
		pendingInventedField.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, Set<String>> pendingInvented = (Map<String, Set<String>>) pendingInventedField.get(null);
		Object outcome;
		try {
			pendingInvented.put("bookkeep.Good", throwingSet());
			List<ClassDefinition> definitions = new ArrayList<>();
			definitions.add(new ClassDefinition(good, v2.get("bookkeep.Good")));
			definitions.add(new ClassDefinition(bad, v2.get("bookkeep.Bad")));

			Class<?> roundSkips = Class.forName("remold.RemoldAgent$RoundSkips");
			var ctor = roundSkips.getDeclaredConstructor();
			ctor.setAccessible(true);
			Object skips = ctor.newInstance();

			Method apply = RemoldAgent.class.getDeclaredMethod(
				"applyRedefinitions", List.class, java.util.Collection.class, roundSkips);
			apply.setAccessible(true);
			outcome = apply.invoke(null, definitions, List.of(), skips);

			@SuppressWarnings("unchecked")
			Map<String, String> byClass = (Map<String, String>) field(skips, "byClass");
			String goodVerdict = byClass.get("bookkeep.Good");
			check(goodVerdict == null || !goodVerdict.startsWith("REDEFINE-FAILED"),
				"bookkeeping failure must not be recorded as REDEFINE-FAILED (actual=" + goodVerdict + ")");
		} finally {
			pendingInvented.remove("bookkeep.Good");
			instField.set(null, real);
			RemoldAgent.logger = oldLogger;
		}

		check(bulkCalls.get() == 1, "bulk redefine was attempted exactly once (actual=" + bulkCalls.get() + ")");
		check(singleCalls.get() == 2, "per-class fallback retried both classes (actual=" + singleCalls.get() + ")");

		Method successfulAccessor = outcome.getClass().getDeclaredMethod("successful");
		successfulAccessor.setAccessible(true);
		@SuppressWarnings("unchecked")
		Set<String> successful = (Set<String>) successfulAccessor.invoke(outcome);
		check(successful.contains("bookkeep.Good"),
			"Good must stay in the successful set after its bookkeeping failed (actual=" + successful + ")");
		check(pendingContains(good), "Good's pending patch must not be popped by a bookkeeping failure");
		check(InitFix.getUnpatchedFields(good).isEmpty(),
			"Good must have no 'redefine failed' ledger entry (actual=" + InitFix.getUnpatchedFields(good) + ")");
		check(!bookkeepingErrors.isEmpty(),
			"the bookkeeping failure must still be reported via error(...) (actual=" + bookkeepingErrors + ")");

		// The class is live even though its bookkeeping failed: v2 body runs on the surviving
		// instance. Values are still defaults because no patch was applied yet.
		checkEq("v2:1,0,0", call(good, goodInstance, "hi"),
			"precondition: Good's redefine itself succeeded (v2 body is live)");

		// Mirror the production post-processing order: after applyRedefinitions, successful
		// classes receive afterRedefine.
		InitFix.afterRedefine(good);
		checkEq(42, getInt(good, goodInstance, "b"),
			"Good's surviving instance received the new field initializer");
		checkEq(7, getInt(good, goodInstance, "r"),
			"Good's @RemoldReinit field was applied");
		checkEq("v2:1,42,7", call(good, goodInstance, "hi"),
			"Good's redefined body sees the patched values");
		check(InitFix.getUnpatchedFields(good).isEmpty(),
			"Good has no ledger entry after post-processing (actual=" + InitFix.getUnpatchedFields(good) + ")");

		System.out.println("\n=== RETRY BOOKKEEPING PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	/** A set whose {@code isEmpty()} throws: {@code commitInvented} hits it after the JVM applied the class. */
	static Set<String> throwingSet() {
		return new AbstractSet<>() {
			@Override public Iterator<String> iterator() { return List.of("probeInventedSlot").iterator(); }
			@Override public int size() { return 1; }
			@Override public boolean isEmpty() { throw new IllegalStateException("probe: bookkeeping failure"); }
		};
	}

	static Instrumentation failingInstrumentation(Instrumentation real,
	                                               AtomicInteger bulkCalls,
	                                               AtomicInteger singleCalls) {
		return (Instrumentation) Proxy.newProxyInstance(
			RetryBookkeepingProbe.class.getClassLoader(),
			new Class<?>[]{Instrumentation.class},
			(proxy, method, args) -> {
				if ("redefineClasses".equals(method.getName()) && args != null && args.length == 1
				    && args[0] instanceof ClassDefinition[] defs) {
					if (defs.length > 1) {
						bulkCalls.incrementAndGet();
						throw new UnsupportedOperationException("probe: simulated bulk redefine failure");
					}
					singleCalls.incrementAndGet();
					String name = defs[0].getDefinitionClass().getName();
					if ("bookkeep.Bad".equals(name)) {
						throw new UnsupportedOperationException("probe: simulated per-class failure for " + name);
					}
					try {
						return method.invoke(real, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				}
				try {
					return method.invoke(real, args);
				} catch (InvocationTargetException e) {
					throw e.getCause();
				}
			});
	}

	static Object field(Object target, String name) throws Exception {
		Field f = target.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(target);
	}

	static boolean pendingContains(Class<?> host) throws Exception {
		Field f = InitFix.class.getDeclaredField("PENDING");
		f.setAccessible(true);
		Object map = f.get(null);
		return map instanceof Map<?, ?> m && m.containsKey(host);
	}

	static void checkEq(Object expected, Object actual, String msg) {
		boolean ok = expected == null ? actual == null : expected.equals(actual);
		check(ok, msg + " [expected=" + expected + ", actual=" + actual + "]");
	}

	static Object call(Class<?> type, Object instance, String method) throws Exception {
		return type.getMethod(method).invoke(instance);
	}

	static int getInt(Class<?> type, Object instance, String field) throws Exception {
		Field f = type.getDeclaredField(field);
		f.setAccessible(true);
		return f.getInt(instance);
	}

	static final class FixtureLoader extends ClassLoader {
		private final Map<String, byte[]> classes = new LinkedHashMap<>();

		FixtureLoader(Map<String, byte[]> classes) {
			super(RetryBookkeepingProbe.class.getClassLoader());
			this.classes.putAll(classes);
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}
