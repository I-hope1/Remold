import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Investigation: retransformLoaded() on a class that already had a schema hot swap.
 * Does bytecodeCache fall back to the old version, and does LayoutGate treat the
 * resulting diff as a field deletion?
 */
public class RetransformBaselineProbe {
	static int passed = 0, failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static final String V1 = """
		package rb;
		public class Host {
		    public int a = 1;
		    public String hi() { return "v1:" + a; }
		}
		""";
	static final String V2 = """
		package rb;
		public class Host {
		    public int a = 1;
		    public int b = 2;
		    public String hi() { return "v2:" + a + "," + b; }
		}
		""";

	public static void main(String[] args) throws Throwable {
		System.out.println("=== RetransformBaselineProbe ===");
		Instrumentation real = RemoldAgent.getInstrumentation();
		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of("rb.Host", V1));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of("rb.Host", V2));
		FixtureLoader loader = new FixtureLoader(v1);
		Class<?> host = Class.forName("rb.Host", true, loader);
		Object instance = host.getConstructor().newInstance();
		check("v1:1".equals(call(instance, "hi")), "precondition: class runs v1");
		check(!hasField(host, "b"), "precondition: no schema field b");

		RemoldAgent.bytecodeCache.put("rb.Host", v1.get("rb.Host"));
		RemoldAgent.sourceBytecodeCache.put("rb.Host", v1.get("rb.Host"));
		Path dir = Files.createTempDirectory("retransform_baseline");
		Path p = dir.resolve("rb/Host.class");
		Files.createDirectories(p.getParent());
		Files.write(p, v2.get("rb.Host"));
		Method process = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
		process.setAccessible(true);
		process.invoke(null, Set.of(p), real.getAllLoadedClasses());
		check(hasField(host, "b"), "schema hot swap applied field b");
		check(RemoldAgent.bytecodeCache.get("rb.Host") == v2.get("rb.Host")
			|| java.util.Arrays.equals(RemoldAgent.bytecodeCache.get("rb.Host"), v2.get("rb.Host")),
			"bytecodeCache holds the applied v2 baseline");
		check(java.util.Arrays.equals(RemoldAgent.sourceBytecodeCache.get("rb.Host"), v2.get("rb.Host")),
			"sourceBytecodeCache holds the applied v2 baseline");

		AtomicInteger retransforms = new AtomicInteger();
		Instrumentation proxy = counting(real, retransforms);
		Field instField = RemoldAgent.class.getDeclaredField("inst");
		instField.setAccessible(true);
		instField.set(null, proxy);
		Method retransform = RemoldAgent.class.getDeclaredMethod("retransformLoaded", Class[].class);
		retransform.setAccessible(true);
		RemoldAgent.Logger oldLogger = RemoldAgent.logger;
		RemoldAgent.logger = new RemoldAgent.Logger() {
			public void log(String msg) { System.out.println("[RAW-LOG] " + msg); }
			public void info(String msg) { System.out.println("[RAW-INFO] " + msg); }
			public void warn(String msg) { System.out.println("[RAW-WARN] " + msg); }
			public void error(String msg) { System.out.println("[RAW-ERROR] " + msg); }
			public void error(String msg, Throwable t) { System.out.println("[RAW-ERROR] " + msg); }
		};
		try {
			System.out.println("--- raw retransform #1: schema class already in bytecodeCache ---");
			retransform.invoke(null, (Object) new Class<?>[]{host});
			check(retransforms.get() == 0,
				"retransformLoaded skips a class already present in bytecodeCache (actual=" + retransforms.get() + ")");
			check(java.util.Arrays.equals(RemoldAgent.sourceBytecodeCache.get("rb.Host"), v2.get("rb.Host")),
				"skipped retransform leaves the v2 source baseline (not v1)");

			// Force the class into the candidate set to exercise the fallback path.
			RemoldAgent.bytecodeCache.remove("rb.Host");
			System.out.println("--- raw retransform #2: bytecodeCache removed, fallback path ---");
			retransform.invoke(null, (Object) new Class<?>[]{host});
			check(retransforms.get() > 0,
				"forced retransform actually reached retransformClasses (actual=" + retransforms.get() + ")");
			check(java.util.Arrays.equals(RemoldAgent.sourceBytecodeCache.get("rb.Host"), v2.get("rb.Host")),
				"fallback baseline is the applied v2 source, not v1");
			check(hasField(host, "b"), "class still has b after forced retransform (no delete-field rejection)");
		} finally {
			RemoldAgent.logger = oldLogger;
			instField.set(null, real);
		}
		System.out.println("=== SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	static Instrumentation counting(Instrumentation real, AtomicInteger calls) {
		return (Instrumentation) Proxy.newProxyInstance(RetransformBaselineProbe.class.getClassLoader(),
			new Class<?>[]{Instrumentation.class}, (proxy, method, args) -> {
				if ("retransformClasses".equals(method.getName())) calls.incrementAndGet();
				try { return method.invoke(real, args); }
				catch (InvocationTargetException e) { throw e.getCause(); }
			});
	}

	static Object call(Object target, String name) throws Exception {
		return target.getClass().getMethod(name).invoke(target);
	}

	static boolean hasField(Class<?> type, String name) {
		try { type.getDeclaredField(name); return true; }
		catch (NoSuchFieldException e) { return false; }
	}

	static final class FixtureLoader extends ClassLoader {
		final Map<String, byte[]> classes;
		FixtureLoader(Map<String, byte[]> classes) {
			super(RetransformBaselineProbe.class.getClassLoader());
			this.classes = classes;
		}
		@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}
