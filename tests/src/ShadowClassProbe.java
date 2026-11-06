import remold.RemoldAgent;
import remold.profiler.DynamicProfilerAPI;
import remold.profiler.ProfilerData;
import remold.util.LibTool;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Item 2 red/green probe for the JBR shadow-Class problem (agent + DCEVM, no Forge).
 *
 * <p>JBR 21.0.9 redefine rewrites Class references held in static fields to an old-version
 * shadow Class (same name/loader, old fields). This probe:
 * <ol>
 *   <li>creates a shadow by a structural redefine,</li>
 *   <li>requires {@code LibTool.getInstances(shadow)} to be normalized to the canonical class,</li>
 *   <li>requires {@code DynamicProfilerAPI.toggleEntityProbe(shadow, ...)} to instrument the
 *       canonical class (counter must run on the live instance),</li>
 *   <li>requires the toggle to report success and the off-toggle to stop the counter.</li>
 * </ol>
 */
public class ShadowClassProbe {
	static int passed = 0, failed = 0;

	/** Static field: JBR rewrites this to the shadow Class after the redefine. */
	static Class<?> holderRef;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static final String V1 = """
		package sc;
		public class Target { public int x = 1; public void probe() { x = x + 1; } }
		""";
	static final String V2 = """
		package sc;
		public class Target { public int x = 1; public int y = 2; public void probe() { x = x + 1; } }
		""";

	public static void main(String[] args) throws Exception {
		System.out.println("=== ShadowClassProbe ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "instrumentation available");

		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of("sc.Target", V1));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of("sc.Target", V2));
		FixtureLoader loader = new FixtureLoader(v1);
		Class<?> canonical = Class.forName("sc.Target", true, loader);
		Object instance = canonical.getConstructor().newInstance();
		holderRef = canonical;

		RemoldAgent.bytecodeCache.put("sc.Target", v1.get("sc.Target"));
		RemoldAgent.sourceBytecodeCache.put("sc.Target", v1.get("sc.Target"));
		check(RemoldAgent.redefineDynamic(canonical, v2.get("sc.Target")),
			"structural redefine (add field y) returned true");

		Class<?> after = Class.forName("sc.Target", false, loader);
		check(after == canonical, "canonical Class identity stable across redefine");
		Class<?> shadow = holderRef;
		check(shadow != canonical, "static field now holds a shadow Class object");

		// 1) JVMTI heap scan with the shadow Class must be normalized to canonical.
		Object[] found = LibTool.getInstances(shadow);
		check(found != null && found.length == 1,
			"LibTool.getInstances(shadow) normalized to canonical (count="
				+ (found == null ? "null" : found.length) + ")");

		// 2) Entity probe registered through the shadow Class must instrument canonical.
		ProfilerData.stats.remove("Target.probe");
		Method toggle = DynamicProfilerAPI.class.getMethod(
			"toggleEntityProbe", Class.class, String.class, boolean.class);
		Object onResult = toggle.invoke(null, shadow, "probe", true);
		boolean onOk = !(onResult instanceof Boolean) || (Boolean) onResult;
		check(onOk, "toggleEntityProbe(shadow, enable) reports success");
		canonical.getMethod("probe").invoke(instance);
		ProfilerData.MethodStats ms = ProfilerData.stats.get("Target.probe");
		long count = ms == null ? 0 : ms.count().sum();
		check(count >= 1, "entity probe counter runs on the canonical instance (count=" + count + ")");

		// 3) Disable through the shadow Class as well; the counter must stop.
		Object offResult = toggle.invoke(null, shadow, "probe", false);
		boolean offOk = !(offResult instanceof Boolean) || (Boolean) offResult;
		check(offOk, "toggleEntityProbe(shadow, disable) reports success");
		canonical.getMethod("probe").invoke(instance);
		ProfilerData.MethodStats ms2 = ProfilerData.stats.get("Target.probe");
		long count2 = ms2 == null ? 0 : ms2.count().sum();
		check(count2 == count, "counter stopped after disable (before=" + count
			+ ", after=" + count2 + ")");

		System.out.println("=== SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	static final class FixtureLoader extends ClassLoader {
		final Map<String, byte[]> classes;

		FixtureLoader(Map<String, byte[]> classes) {
			super(ShadowClassProbe.class.getClassLoader());
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
