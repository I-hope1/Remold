import remold.RemoldAgent;
import remold.profiler.DynamicProfilerAPI;
import remold.profiler.ProfilerData;

import java.lang.instrument.Instrumentation;
import java.util.Map;

/**
 * M1.1: prove that the entity probe still works after the C1 fix (the retransform now runs
 * inside an internal retransform context, so {@code AnnotationTransformer} no longer re-weaves
 * the class) — and that turning it off stops the counter.
 *
 * <p>Terminal observation is the {@link ProfilerData} counter injected by the real
 * {@code ProfilerTransformer} into the real target method; no internal counters of the
 * profiler API are consulted.</p>
 */
public class EntityProbeToggleProbe {
	static int passed = 0, failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static class Target {
		public int calls;

		public void probeMe() {
			calls++;
		}
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=== EntityProbeToggleProbe ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "instrumentation available");

		Target target = new Target();
		String key = "EntityProbeToggleProbe$Target.probeMe";

		// Baseline: probe off -> the method runs but nothing is recorded.
		ProfilerData.stats.remove(key);
		for (int i = 0; i < 3; i++) target.probeMe();
		check(count(key) == 0, "probe off: no counter before enabling (actual=" + count(key) + ")");

		// Enable the entity probe: real retransform + real ProfilerTransformer advice.
		DynamicProfilerAPI.toggleEntityProbe(Target.class, "probeMe", true);
		for (int i = 0; i < 5; i++) target.probeMe();
		long on = count(key);
		check(on == 5, "probe on: counter runs on the retransformed class (actual=" + on + ")");

		// Disable: the counter must stop.
		DynamicProfilerAPI.toggleEntityProbe(Target.class, "probeMe", false);
		long beforeOff = count(key);
		for (int i = 0; i < 5; i++) target.probeMe();
		long afterOff = count(key);
		check(afterOff == beforeOff,
			"probe off: counter stops after disabling (before=" + beforeOff + ", after=" + afterOff + ")");

		// The class itself must still be fully functional (no structural damage from the fix).
		check(target.calls == 13, "target method still executes normally (calls=" + target.calls + ")");

		System.out.println("=== SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	static long count(String key) {
		Map<String, ProfilerData.MethodStats> stats = ProfilerData.stats;
		ProfilerData.MethodStats s = stats.get(key);
		return s == null ? 0 : s.count().sum();
	}
}
