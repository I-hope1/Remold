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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regression probe for the InitFix lifecycle in the bulk-failure -> per-class fallback path.
 *
 * <p><b>Defect</b>: {@code RemoldAgent.applyRedefinitions} used to call
 * {@code InitFix.afterRedefineFailed(def)} for every definition <b>before</b> retrying the class
 * individually. That call drops the pending patch. When the individual retry then succeeds, the
 * class is live but {@code InitFix.afterRedefine} finds no patch: surviving instances keep the
 * type default, {@code @RemoldReinit} records are cleared, and the ledger falsely says the class
 * failed to redefine.</p>
 *
 * <p>The probe builds a batch with one good class (schema change + new field + reinit field) and
 * one bad class. Failure is injected at the {@code Instrumentation} boundary: the bulk call throws
 * without reaching the JVM (the same atomic "nothing applied" contract), the good class is then
 * redefined for real one class at a time, and the bad class's single retry throws. The assertions
 * cover both halves of the contract: the successful class keeps its patch and ledger stays clean,
 * while the failed class has its patch popped and its ledger records the failure.</p>
 *
 * <p>Run via {@code ./gradlew :tests:initFixFallbackProbe -Pp05.jbr=<DCEVM/JBR home>}.</p>
 */
public class InitFixFallbackProbe {

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
		package fallback;
		public class Good {
		    public int a = 1;
		    public String hi() { return "v1:" + a; }
		}
		""";

	static final String GOOD_V2 = """
		package fallback;
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
		package fallback;
		public class Bad {
		    public int a = 1;
		}
		""";

	static final String BAD_V2 = """
		package fallback;
		public class Bad {
		    public int a = 1;
		    public int badNew = 99;
		}
		""";

	public static void main(String[] args) throws Throwable {
		System.out.println("=== InitFixFallbackProbe: bulk failure -> per-class retry ===");
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
			"fallback.Good", GOOD_V1,
			"fallback.Bad", BAD_V1));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of(
			"fallback.Good", GOOD_V2,
			"fallback.Bad", BAD_V2));

		FixtureLoader loader = new FixtureLoader(v1);
		Class<?> good = Class.forName("fallback.Good", true, loader);
		Class<?> bad = Class.forName("fallback.Bad", true, loader);
		Object goodInstance = good.getConstructor().newInstance();
		bad.getConstructor().newInstance(); // initialize Bad so its patch/ledger path is realistic

		checkEq("v1:1", call(good, goodInstance, "hi"), "precondition: Good runs v1");
		checkEq(1, getInt(good, goodInstance, "a"), "precondition: Good has the v1 layout");

		// Production puts the runtime baseline into bytecodeCache before InitFix.transform runs
		// (the oracle resolves nest metadata through that cache).
		RemoldAgent.bytecodeCache.put("fallback.Good", v2.get("fallback.Good"));
		RemoldAgent.bytecodeCache.put("fallback.Bad", v2.get("fallback.Bad"));
		InitFix.transform(good, v2.get("fallback.Good"),
		                  ClassDiffUtil.diff(v1.get("fallback.Good"), v2.get("fallback.Good")));
		InitFix.transform(bad, v2.get("fallback.Bad"),
		                  ClassDiffUtil.diff(v1.get("fallback.Bad"), v2.get("fallback.Bad")));

		check(InitFix.getLastReport(good) != null && InitFix.getLastReport(good).patchGenerated(),
			"precondition: Good patch generated");
		check(InitFix.getLastReport(bad) != null && InitFix.getLastReport(bad).patchGenerated(),
			"precondition: Bad patch generated");

		AtomicInteger bulkCalls = new AtomicInteger();
		AtomicInteger singleCalls = new AtomicInteger();
		Instrumentation counting = failingInstrumentation(real, bulkCalls, singleCalls);
		Field instField = RemoldAgent.class.getDeclaredField("inst");
		instField.setAccessible(true);
		instField.set(null, counting);
		try {
			List<ClassDefinition> definitions = new ArrayList<>();
			definitions.add(new ClassDefinition(good, v2.get("fallback.Good")));
			definitions.add(new ClassDefinition(bad, v2.get("fallback.Bad")));

			Class<?> roundSkips = Class.forName("remold.RemoldAgent$RoundSkips");
			var ctor = roundSkips.getDeclaredConstructor();
			ctor.setAccessible(true);
			Object skips = ctor.newInstance();

			Method apply = RemoldAgent.class.getDeclaredMethod(
				"applyRedefinitions", List.class, java.util.Collection.class, roundSkips);
			apply.setAccessible(true);
			apply.invoke(null, definitions, List.of(), skips);
		} finally {
			instField.set(null, real);
		}

		check(bulkCalls.get() == 1, "bulk redefine was attempted exactly once (actual=" + bulkCalls.get() + ")");
		check(singleCalls.get() == 2, "per-class fallback retried both classes (actual=" + singleCalls.get() + ")");

		// Mirror the production post-processing order: after applyRedefinitions, only successful
		// classes receive afterRedefine. Good succeeded individually; Bad did not.
		InitFix.afterRedefine(good);

		checkEq(42, getInt(good, goodInstance, "b"),
			"Good's surviving instance received the new field initializer");
		checkEq(7, getInt(good, goodInstance, "r"),
			"Good's @RemoldReinit field was applied");
		checkEq("v2:1,42,7", call(good, goodInstance, "hi"),
			"Good's redefined body sees the patched values");
		check(InitFix.getUnpatchedFields(good).isEmpty(),
			"Good has no ledger entry (actual=" + InitFix.getUnpatchedFields(good) + ")");
		check(reinitAppliedContains(good, "r"),
			"Good's REINIT_APPLIED record survived the fallback path");

		Set<InitFix.UnpatchedField> badLedger = InitFix.getUnpatchedFields(bad);
		check(badLedger.stream().anyMatch(f -> f.reason().contains("redefine failed")),
			"Bad's ledger records the redefine failure (actual=" + badLedger + ")");
		check(!pendingContains(bad), "Bad's pending patch was popped");
		check("v2:1,42,7".equals(call(good, goodInstance, "hi")),
			"Good's body remains v2 after the failed Bad retry");

		System.out.println("\n=== INITFIX FALLBACK PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	static Instrumentation failingInstrumentation(Instrumentation real,
	                                               AtomicInteger bulkCalls,
	                                               AtomicInteger singleCalls) {
		return (Instrumentation) Proxy.newProxyInstance(
			InitFixFallbackProbe.class.getClassLoader(),
			new Class<?>[]{Instrumentation.class},
			(proxy, method, args) -> {
				if ("redefineClasses".equals(method.getName()) && args != null && args.length == 1
				    && args[0] instanceof ClassDefinition[] defs) {
					if (defs.length > 1) {
						// Deterministic bulk failure: no definition reaches the JVM, which is exactly
						// the atomicity contract the production fallback relies on. Injecting the
						// failure at the Instrumentation boundary keeps the probe stable on JBR,
						// where malformed definitions crash VM_EnhancedRedefineClasses sorting.
						bulkCalls.incrementAndGet();
						throw new UnsupportedOperationException("probe: simulated bulk redefine failure");
					}
					singleCalls.incrementAndGet();
					String name = defs[0].getDefinitionClass().getName();
					if ("fallback.Bad".equals(name)) {
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

	static boolean reinitAppliedContains(Class<?> host, String fieldName) throws Exception {
		Field f = InitFix.class.getDeclaredField("REINIT_APPLIED");
		f.setAccessible(true);
		Object map = f.get(null);
		if (!(map instanceof Map<?, ?> m)) return false;
		Object perHost = m.get(host);
		return perHost instanceof Map<?, ?> fields && fields.containsKey(fieldName);
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
		private final Map<String, byte[]> classes;

		FixtureLoader(Map<String, byte[]> classes) {
			super(InitFixFallbackProbe.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}
