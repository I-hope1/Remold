import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
		System.out.println("=== SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	static boolean hasField(Class<?> type, String name) {
		try { type.getDeclaredField(name); return true; }
		catch (NoSuchFieldException e) { return false; }
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
