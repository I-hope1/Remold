import remold.AnnotationTransformer;
import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LayoutGate reject → next-round recover, end-to-end (item 5B).
 *
 * <p>Drives the production batch pipeline ({@code RemoldAgent.processChanges}) through regular
 * watcher-style rounds against fixtures compiled in memory. Everything is pure JVM: the agent is
 * attached with {@code -javaagent} and JBR/DCEVM provides the structural redefinition. A full
 * round takes milliseconds, so the whole probe runs in seconds.</p>
 *
 * <p>Sequence:</p>
 * <ul>
 *   <li><b>Round 0</b> (baseline): method-body-only change → must redefine, proving the pipeline
 *       works before any reject.</li>
 *   <li><b>Round 1</b> (reject): incompatible field-type change on a class with a live instance →
 *       must log {@code [LAYOUT-REJECT]}, the live instance must keep running the OLD body, the
 *       class definition and both bytecode baselines must be untouched, and no pending patch /
 *       ledger entry / alignment entry may be left behind.</li>
 *   <li><b>Control</b> (same illegal change, no live instance): the very same type change on a
 *       class with zero instances must be WAIVED, not rejected — the reject is caused by the live
 *       instance, not by the type change itself.</li>
 *   <li><b>Round 2</b> (recover): legal structural change (add a field + body change) immediately
 *       after the reject → must redefine, the new field must be initialized on the surviving
 *       instance by InitFix, and the reject record must be cleared (no dirty lock).</li>
 * </ul>
 *
 * <p>Run: {@code ./gradlew :tests:layoutRejectRecoverProbe -Pp05.jbr=<DCEVM JDK>}.</p>
 */
public class LayoutRejectRecoverProbe {

	static int passed = 0, failed = 0;

	static final List<String> logs = Collections.synchronizedList(new ArrayList<>());

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static void summary() {
		System.out.println("=== SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
	}

	/** The production log sink, captured so assertions can name the rejection line. */
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
			logs.add(line);
			System.out.println("AGENT " + line);
		}
	}

	// ------------------------------------------------------------------ fixtures

	static final String HOST_V1 = """
		package lr;
		public class Host {
		    public int keep = 7;
		    public int doomed = 42;
		    public String tag() { return "v1:" + keep + ":" + doomed; }
		    public int keepValue() { return keep; }
		}
		""";

	static final String HOST_V1B = """
		package lr;
		public class Host {
		    public int keep = 7;
		    public int doomed = 42;
		    public String tag() { return "v1b:" + keep + ":" + doomed; }
		    public int keepValue() { return keep; }
		}
		""";

	/** Illegal relative to the running baseline: {@code keep} changes int → long. */
	static final String HOST_V2_ILLEGAL = """
		package lr;
		public class Host {
		    public long keep = 7L;
		    public int doomed = 42;
		    public String tag() { return "v2:" + keep + ":" + doomed; }
		    public long keepValue() { return keep; }
		}
		""";

	/** Legal relative to the running baseline: pure added field + method body change. */
	static final String HOST_V3_LEGAL = """
		package lr;
		public class Host {
		    public int keep = 7;
		    public int doomed = 42;
		    public int added = 99;
		    public String tag() { return "v3:" + keep + ":" + doomed + ":" + added; }
		    public int keepValue() { return keep; }
		    public int addedValue() { return added; }
		}
		""";

	static final String CTL_V1 = """
		package lr;
		public class Ctl {
		    public int keep = 1;
		    public String tag() { return "ctl1:" + keep; }
		}
		""";

	static final String CTL_V2_TYPE_CHANGE = """
		package lr;
		public class Ctl {
		    public long keep = 1L;
		    public String tag() { return "ctl2:" + keep; }
		}
		""";

	public static void main(String[] args) throws Throwable {
		System.out.println("=== LayoutRejectRecoverProbe ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "P0 instrumentation is available (must run with -javaagent remold-agent.jar)");
		check(RemoldAgent.isEnhancedHotswapEnabled(),
			"P0 enhanced class redefinition is enabled (round 2 adds a field; use JBR/DCEVM)");
		if (inst == null) {
			summary();
			System.exit(1);
		}

		try {
			run(inst);
		} catch (Throwable t) {
			// A crash is a FAIL, not a silent abort: keep the failure count and print why.
			check(false, "probe aborted: " + t);
			t.printStackTrace();
		}
		summary();
		if (failed > 0) System.exit(1);
	}

	static void run(Instrumentation inst) throws Throwable {
		RemoldAgent.logger = new CaptureLogger();

		// -------- compile every version of the fixtures (in-memory javac) --------
		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of("lr.Host", HOST_V1));
		Map<String, byte[]> v1b = LayoutGateAssert.compile(Map.of("lr.Host", HOST_V1B));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of("lr.Host", HOST_V2_ILLEGAL));
		Map<String, byte[]> v3 = LayoutGateAssert.compile(Map.of("lr.Host", HOST_V3_LEGAL));
		Map<String, byte[]> ctl1 = LayoutGateAssert.compile(Map.of("lr.Ctl", CTL_V1));
		Map<String, byte[]> ctl2 = LayoutGateAssert.compile(Map.of("lr.Ctl", CTL_V2_TYPE_CHANGE));

		// -------- load the fixtures through the real agent (first-load baseline capture) --------
		FixtureLoader loader = new FixtureLoader(Map.of("lr.Host", v1.get("lr.Host"), "lr.Ctl", ctl1.get("lr.Ctl")));
		Class<?> host = Class.forName("lr.Host", true, loader);
		Object hostInstance = host.getConstructor().newInstance();
		Class<?> ctl = Class.forName("lr.Ctl", true, loader); // loaded, but deliberately NO instance

		check("v1:7:42".equals(invoke(hostInstance, "tag")), "R0 precondition: the fixture runs v1");
		check(Arrays.equals(RemoldAgent.sourceBytecodeCache.get("lr.Host"), v1.get("lr.Host")),
			"R0 precondition: first load captured the v1 source baseline");
		check(Arrays.equals(RemoldAgent.bytecodeCache.get("lr.Host"), v1.get("lr.Host")),
			"R0 precondition: first load captured the v1 runtime baseline");
		check(fieldType(host, "keep") == int.class, "R0 precondition: lr.Host.keep is int");

		Path dir = Files.createTempDirectory("remold-layout-recovery");
		Path hostPath = dir.resolve("lr/Host.class");
		Path ctlPath = dir.resolve("lr/Ctl.class");
		writeClass(hostPath, v1.get("lr.Host"));
		writeClass(ctlPath, ctl1.get("lr.Ctl"));

		// ================= Round 0: the pipeline works before any reject =================
		System.out.println("-- Round 0: method-body-only change (baseline) --");
		int mark = logs.size();
		writeClass(hostPath, v1b.get("lr.Host"));
		process(hostPath, inst);
		List<String> r0 = slice(mark);
		check("v1b:7:42".equals(invoke(hostInstance, "tag")),
			"R0: the live instance runs the new body (v1b)");
		check(has(r0, "HotSwap successful"), "R0: the batch really redefined (HotSwap successful)");
		check(Arrays.equals(RemoldAgent.bytecodeCache.get("lr.Host"), v1b.get("lr.Host")),
			"R0: the runtime baseline advanced to v1b");
		check(Arrays.equals(RemoldAgent.sourceBytecodeCache.get("lr.Host"), v1b.get("lr.Host")),
			"R0: the source baseline advanced to v1b");

		// ================= Round 1: illegal layout change is rejected =================
		System.out.println("-- Round 1: incompatible field-type change with a live instance --");
		mark = logs.size();
		long rejectsBefore = rejectCount();
		writeClass(hostPath, v2.get("lr.Host"));
		process(hostPath, inst);
		List<String> r1 = slice(mark);
		check(has(r1, "[LAYOUT-REJECT] lr.Host"),
			"R1: the batch logged [LAYOUT-REJECT] lr.Host");
		check(has(r1, "field type changed"),
			"R1: the rejection names the change kind (field type changed)");
		check(!has(r1, "HotSwap successful"),
			"R1: nothing was redefined this round (no HotSwap successful line)");
		check(rejectCount() == rejectsBefore + 1,
			"R1: the LAYOUT_GATE_REJECTED counter advanced by exactly one (actual delta="
				+ (rejectCount() - rejectsBefore) + ")");

		check("v1b:7:42".equals(invoke(hostInstance, "tag")),
			"R1: the live instance still runs the OLD body (v1b) — reject did not half-apply");
		check(invokeInt(hostInstance, "keepValue") == 7,
			"R1: the live instance keeps its field value (7)");
		check(fieldType(host, "keep") == int.class,
			"R1: the class definition is intact (lr.Host.keep is still int)");
		check(Arrays.equals(RemoldAgent.bytecodeCache.get("lr.Host"), v1b.get("lr.Host")),
			"R1: bytecodeCache still holds the applied v1b baseline (no poisoned runtime baseline)");
		check(Arrays.equals(RemoldAgent.sourceBytecodeCache.get("lr.Host"), v1b.get("lr.Host")),
			"R1: sourceBytecodeCache rolled back to the applied v1b baseline");
		check(!AnnotationTransformer.pendingAlignedClasses.containsKey("lr.Host")
				&& !AnnotationTransformer.pendingAlignedClasses.containsKey("lr/Host"),
			"R1: no alignment/pending class entry was left behind");
		check(!hasPendingPatch(host), "R1: no InitFix pending patch was left behind");
		check(InitFixLedgerSize(host) == 0, "R1: the FieldLedger for lr.Host is empty");
		check(rejectRecordCount("lr.Host") == 1,
			"R1: the rejection was recorded once (same reason count = 1, no stuck loop yet)");
		check(!has(r1, "[LAYOUT-STUCK]"), "R1: no [LAYOUT-STUCK] warning on the first rejection");

		// ================= Control: identical illegal change, no live instance =================
		System.out.println("-- Control: the same type change WITHOUT a live instance (no trigger) --");
		mark = logs.size();
		writeClass(ctlPath, ctl2.get("lr.Ctl"));
		process(ctlPath, inst);
		List<String> rc = slice(mark);
		check(has(rc, "[LAYOUT-WAIVE] lr.Ctl"),
			"CTL: no live instance ⇒ the same type change is WAIVED, not rejected"
				+ " (the reject in R1 is caused by the live instance)");
		check(!has(rc, "[LAYOUT-REJECT] lr.Ctl"), "CTL: lr.Ctl was NOT rejected");
		check(has(rc, "HotSwap successful"), "CTL: the waived class really redefined");
		check(Arrays.equals(RemoldAgent.bytecodeCache.get("lr.Ctl"), ctl2.get("lr.Ctl")),
			"CTL: the runtime baseline advanced to the type-changed version");
		check("ctl2:1".equals(invoke(ctl.getConstructor().newInstance(), "tag")),
			"CTL: the redefined class runs ctl2 (new code is live)");

		// ================= Round 2: legal structural change recovers immediately =================
		System.out.println("-- Round 2: legal add-field change right after the reject --");
		mark = logs.size();
		writeClass(hostPath, v3.get("lr.Host"));
		process(hostPath, inst);
		List<String> r2 = slice(mark);
		check(!has(r2, "[LAYOUT-REJECT] lr.Host"), "R2: the next round is not blocked by the reject");
		check(has(r2, "HotSwap successful"), "R2: the legal change redefined (HotSwap successful)");
		check(invoke(hostInstance, "tag").equals("v3:7:42:99"),
			"R2: the surviving instance runs the new body AND the added field was initialized by"
				+ " InitFix to 99 (actual=" + invoke(hostInstance, "tag") + ")");
		check(invokeInt(hostInstance, "addedValue") == 99,
			"R2: the surviving instance's added field reads 99 through the class's own method");
		check(invokeInt(hostInstance, "keepValue") == 7,
			"R2: the pre-existing field value was preserved (7)");
		check("v3:7:42:99".equals(invoke(host.getConstructor().newInstance(), "tag")),
			"R2: a newly created instance also initializes correctly (constructor path)");
		check(Arrays.equals(RemoldAgent.bytecodeCache.get("lr.Host"), v3.get("lr.Host")),
			"R2: the runtime baseline advanced to v3");
		check(Arrays.equals(RemoldAgent.sourceBytecodeCache.get("lr.Host"), v3.get("lr.Host")),
			"R2: the source baseline advanced to v3");
		check(!hasPendingPatch(host), "R2: no pending InitFix patch remains after a successful apply");
		check(InitFixLedgerSize(host) == 0, "R2: the FieldLedger stays empty (the added field was patched)");
		check(rejectRecordCount("lr.Host") == 0,
			"R2: the reject record was cleared on success (no dirty lock)");
		check(rejectCount() == rejectsBefore + 1,
			"R2: the reject counter did not advance (this round passed the gate)");
	}

	// ------------------------------------------------------------------ driver helpers

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
		synchronized (logs) {
			return new ArrayList<>(logs.subList(mark, logs.size()));
		}
	}

	static boolean has(List<String> round, String needle) {
		for (String line : round) {
			if (line.contains(needle)) return true;
		}
		return false;
	}

	static Object invoke(Object target, String name) throws Exception {
		return target.getClass().getMethod(name).invoke(target);
	}

	static int invokeInt(Object target, String name) throws Exception {
		return (Integer) invoke(target, name);
	}

	// ------------------------------------------------------------------ state probes (reflection)

	static Class<?> fieldType(Class<?> type, String name) throws Exception {
		return type.getDeclaredField(name).getType();
	}

	/** {@code RemoldAgent.LAYOUT_GATE_REJECTED} is package-private; read it without touching prod code. */
	static long rejectCount() throws Exception {
		Field f = RemoldAgent.class.getDeclaredField("LAYOUT_GATE_REJECTED");
		f.setAccessible(true);
		return ((AtomicLong) f.get(null)).get();
	}

	/** Same-reason consecutive rejection record: the thing that would become a "stuck" loop. */
	static int rejectRecordCount(String className) throws Exception {
		Field f = RemoldAgent.class.getDeclaredField("REJECT_HISTORY");
		f.setAccessible(true);
		Map<?, ?> history = (Map<?, ?>) f.get(null);
		Object record = history.get(className);
		if (record == null) return 0;
		Method count = record.getClass().getDeclaredMethod("count");
		count.setAccessible(true);
		return (Integer) count.invoke(record);
	}

	/** Whether InitFix still holds a pending patch for this host (the "half-applied patch" poison). */
	static boolean hasPendingPatch(Class<?> host) throws Exception {
		Field f = Class.forName("remold.ref.InitFix").getDeclaredField("PENDING");
		f.setAccessible(true);
		return ((Map<?, ?>) f.get(null)).containsKey(host);
	}

	static int InitFixLedgerSize(Class<?> host) {
		return remold.ref.InitFix.getUnpatchedFields(host).size();
	}

	static final class FixtureLoader extends ClassLoader {
		private final Map<String, byte[]> classes;

		FixtureLoader(Map<String, byte[]> classes) {
			super(LayoutRejectRecoverProbe.class.getClassLoader());
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
