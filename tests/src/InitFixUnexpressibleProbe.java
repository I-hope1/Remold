import remold.RemoldAgent;
import remold.ref.InitFix;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * InitFix refusal gate, end-to-end: a newly added field whose initializer exists in source but cannot be
 * sliced must take the whole class out of the batch instead of leaving the field at its type default.
 *
 * <p>Why this matters (real-machine Kotlin finding): {@code val y: String = n ?: "fb"} (and {@code if}/{@code when})
 * compiles to branch instructions that are outside the straight-line slice whitelist, and the class used to be
 * redefined anyway with no patch — existing instances then read {@code null} for a field declared non-null, and the
 * NPE surfaced far from the hot-swap. The gate now refuses the class loudly ({@code [INITFIX-REFUSE]} +
 * {@code INITFIX-UNEXPRESSIBLE-INIT}) and asks for a restart.</p>
 *
 * <p>The fixture mirrors that shape with a ternary ({@code label}) and, as a control, an external factory call
 * ({@code List.of}) which the extractor <b>does</b> accept — so a green R1 proves the refusal is specific to the
 * unsliceable initializer rather than a blanket rejection of every added field.</p>
 *
 * <p>Rounds (all through the production driver {@code RemoldAgent.processChanges}, real redefineClasses on
 * JBR/DCEVM):</p>
 * <ol>
 *   <li><b>R0 baseline</b>: method-body only → must apply (proves the pipeline works before any refusal).</li>
 *   <li><b>R1 refuse</b>: add {@code Object[] seed = new Object[]{"a","b"}} → class must be kept out of the batch,
 *       the live instance must stay on the previous version, and no {@code HotSwap successful} may be printed.</li>
 *   <li><b>R2 control</b>: same class, now adding a constant-initialized field <em>and</em> a field with no
 *       initializer → must apply. This is the "change to an unrelated input" control: the gate must not be a
 *       blanket refusal of every added field.</li>
 * </ol>
 */
public class InitFixUnexpressibleProbe {

	static int passed = 0;
	static int failed = 0;
	static final List<String> logs = new ArrayList<>();

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static void summary() {
		System.out.println("=== SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
	}

	static final class CaptureLogger implements RemoldAgent.Logger {
		@Override public void log(String msg) { record("LOG", msg); }
		@Override public void info(String msg) { record("INFO", msg); }
		@Override public void warn(String msg) { record("WARN", msg); }
		@Override public void error(String msg) { record("ERROR", msg); }
		@Override public void error(String msg, Throwable t) {
			record("ERROR", msg + (t == null ? "" : " | " + t));
		}
		private void record(String level, String msg) {
			String line = "[" + level + "] " + msg;
			synchronized (logs) { logs.add(line); }
			System.out.println("AGENT " + line);
		}
	}

	// ------------------------------------------------------------------ fixtures

	static final String HOST_V1 = """
		package iu;
		public class Host {
		    public int keep = 7;
		    public String tag() { return "v1:" + keep; }
		    public int keepValue() { return keep; }
		}
		""";

	/** Method-body-only edit: the pipeline must keep working before any refusal. */
	static final String HOST_V1B = """
		package iu;
		public class Host {
		    public int keep = 7;
		    public String tag() { return "v1b:" + keep; }
		    public int keepValue() { return keep; }
		}
		""";

	/**
	 * Refused shape: {@code label} uses a ternary, whose branch instructions are outside the slice whitelist.
	 * {@code seed} uses an external factory call, which the extractor accepts — the pair makes the refusal
	 * provably field-specific.
	 */
	static final String HOST_V2_REFUSED = """
		package iu;
		public class Host {
		    public int keep = 7;
		    public java.util.List<String> seed = java.util.List.of("a", "b");
		    public String label = keep > 0 ? "pos" : "neg";
		    public String tag() { return "v2:" + keep + ":" + (seed == null ? "null" : seed.size()) + ":" + label; }
		    public int keepValue() { return keep; }
		}
		""";

	/**
	 * Control: back to a legal delta from the running baseline — one constant-initialized field plus one field
	 * with no initializer at all. Both must be accepted (constant is sliceable; "no initializer" is legitimate).
	 */
	static final String HOST_V3_LEGAL = """
		package iu;
		public class Host {
		    public int keep = 7;
		    public int added = 99;
		    public int plain;
		    public String tag() { return "v3:" + keep + ":" + added + ":" + plain; }
		    public int keepValue() { return keep; }
		    public int addedValue() { return added; }
		    public int plainValue() { return plain; }
		}
		""";

	public static void main(String[] args) throws Throwable {
		System.out.println("=== InitFixUnexpressibleProbe ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "P0 instrumentation is available (run with -javaagent remold-agent.jar)");
		check(RemoldAgent.isEnhancedHotswapEnabled(),
			"P0 enhanced class redefinition is enabled (the rounds add fields; use JBR/DCEVM)");
		if (inst == null) {
			summary();
			System.exit(1);
			return;
		}
		RemoldAgent.logger = new CaptureLogger();
		try {
			run(inst);
		} catch (Throwable t) {
			check(false, "probe aborted: " + t);
			t.printStackTrace();
		}
		summary();
		if (failed > 0) System.exit(1);
	}

	static void run(Instrumentation inst) throws Throwable {
		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of("iu.Host", HOST_V1));
		Map<String, byte[]> v1b = LayoutGateAssert.compile(Map.of("iu.Host", HOST_V1B));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of("iu.Host", HOST_V2_REFUSED));
		Map<String, byte[]> v3 = LayoutGateAssert.compile(Map.of("iu.Host", HOST_V3_LEGAL));

		FixtureLoader loader = new FixtureLoader(Map.of("iu.Host", v1.get("iu.Host")));
		Class<?> host = Class.forName("iu.Host", true, loader);
		Object live = host.getConstructor().newInstance();
		check("v1:7".equals(invoke(live, "tag")), "R0 precondition: the fixture runs v1");
		check(!hasField(host, "seed"), "R0 precondition: no seed field on the baseline class");

		Path dir = Files.createTempDirectory("remold-initfix-unexpressible");
		Path hostPath = dir.resolve("iu/Host.class");
		writeClass(hostPath, v1.get("iu.Host"));

		// ================= R0: the pipeline works before any refusal =================
		System.out.println("-- R0: method-body-only change (baseline) --");
		int mark = logs.size();
		writeClass(hostPath, v1b.get("iu.Host"));
		process(hostPath, inst);
		List<String> r0 = slice(mark);
		check("v1b:7".equals(invoke(live, "tag")), "R0: the live instance runs the new body (v1b)");
		check(has(r0, "HotSwap successful"), "R0: the batch really redefined (HotSwap successful)");
		check(Arrays.equals(RemoldAgent.bytecodeCache.get("iu.Host"), v1b.get("iu.Host")),
			"R0: the runtime baseline advanced to v1b");

		// ================= R1: an unsliceable initializer takes the class out of the batch =================
		System.out.println("-- R1: added field with an array-construction initializer (must be refused) --");
		mark = logs.size();
		writeClass(hostPath, v2.get("iu.Host"));
		process(hostPath, inst);
		List<String> r1 = slice(mark);
		check(has(r1, "[INITFIX-REFUSE] iu.Host"),
			"R1: the gate names the class ([INITFIX-REFUSE] iu.Host)");
		check(has(r1, "new field initializer cannot be extracted"),
			"R1: the gate states the reason family");
		String refuseLine = firstLine(r1, "[INITFIX-REFUSE] iu.Host");
		check(refuseLine != null && refuseLine.contains("label"),
			"R1: the refusal names the rejected field (label, the ternary initializer): " + refuseLine);
		check(has(r1, "INITFIX-UNEXPRESSIBLE-INIT"),
			"R1: the skip ledger records the dedicated verdict");
		check(has(r1, "RESTART to adopt this class"),
			"R1: the log tells the user what to do (restart)");
		check(!has(r1, "HotSwap successful"),
			"R1: nothing is reported as redefined — no false success line");
		check("v1b:7".equals(invoke(live, "tag")),
			"R1: the live instance still runs the previous body (v1b), not a half-applied v2");
		check(invokeInt(live, "keepValue") == 7, "R1: the live instance keeps its field value (7)");
		check(!hasField(host, "seed"), "R1: the class definition was NOT updated (no seed field)");
		check(Arrays.equals(RemoldAgent.bytecodeCache.get("iu.Host"), v1b.get("iu.Host")),
			"R1: the runtime baseline still holds v1b (no poisoned baseline)");
		check(!hasPendingPatch(host), "R1: no InitFix pending patch was left behind");
		// The unpatched ledger is "不遗忘": it must remember that `label` still owes a value, so a later
		// round retries instead of silently forgetting. What must NOT happen is a patch that pretends success.
		String ledger = InitFix.getUnpatchedFields(host).toString();
		check(ledger.contains("label"),
			"R1: the unpatched ledger keeps the refused field on record (retry on a later round): " + ledger);
		check(!ledger.contains("seed"),
			"R1: the ledger does not blame the accepted factory-call field (seed): " + ledger);

		// ================= R2: control — a legal added field still applies =================
		System.out.println("-- R2: control — constant-initialized + uninitialized fields must still apply --");
		mark = logs.size();
		writeClass(hostPath, v3.get("iu.Host"));
		process(hostPath, inst);
		List<String> r2 = slice(mark);
		check("v3:7:99:0".equals(invoke(live, "tag")),
			"R2: the live instance runs v3 and reads added=99 / plain=0");
		check(invokeInt(live, "addedValue") == 99, "R2: the constant-initialized field was patched to 99");
		check(invokeInt(live, "plainValue") == 0, "R2: the uninitialized field keeps the source-level default 0");
		check(has(r2, "HotSwap successful"), "R2: the batch redefined the class");
		check(hasField(host, "added"), "R2: the class definition now declares added");
		check(Arrays.equals(RemoldAgent.bytecodeCache.get("iu.Host"), v3.get("iu.Host")),
			"R2: the runtime baseline advanced to v3");
	}

	// ------------------------------------------------------------------ helpers

	static void writeClass(Path path, byte[] bytes) throws Exception {
		Files.createDirectories(path.getParent());
		Files.write(path, bytes);
	}

	/** Invoke the production round driver exactly the way the watcher does (same private method). */
	static void process(Path changed, Instrumentation inst) throws Exception {
		Method m = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
		m.setAccessible(true);
		m.invoke(null, Set.of(changed), inst.getAllLoadedClasses());
	}

	static List<String> slice(int mark) {
		synchronized (logs) { return new ArrayList<>(logs.subList(mark, logs.size())); }
	}

	static String firstLine(List<String> round, String needle) {
		for (String line : round) if (line.contains(needle)) return line;
		return null;
	}

	static boolean has(List<String> round, String needle) {
		for (String line : round) if (line.contains(needle)) return true;
		return false;
	}

	static Object invoke(Object target, String name) throws Exception {
		Method m = target.getClass().getMethod(name);
		m.setAccessible(true);
		return m.invoke(target);
	}

	static int invokeInt(Object target, String name) throws Exception {
		return ((Number) invoke(target, name)).intValue();
	}

	static boolean hasField(Class<?> type, String name) {
		for (Field f : type.getDeclaredFields()) if (f.getName().equals(name)) return true;
		return false;
	}

	/** Minimal defining loader: one class per name, no parent delegation for the fixtures. */
	static final class FixtureLoader extends ClassLoader {
		private final Map<String, byte[]> classes;

		FixtureLoader(Map<String, byte[]> classes) {
			super(InitFixUnexpressibleProbe.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	static boolean hasPendingPatch(Class<?> host) throws Exception {
		Field f = InitFix.class.getDeclaredField("PENDING");
		f.setAccessible(true);
		return ((Map<?, ?>) f.get(null)).containsKey(host);
	}
}
