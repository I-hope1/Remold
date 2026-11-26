import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two field-reported failures that only appear in {@code lazy_load} mode, reproduced on a real JVM.
 *
 * <p><b>Residual 1 (crash)</b>: the aligner has to renumber a new anonymous class because its compiled slot is
 * already taken by a class that survived. The host's new bytecode then references a name that exists nowhere on
 * disk ({@code Host$4} while the build output only has {@code Host$1..$3}). {@code lazy_load} resolves classes by
 * asking the class loader to read a file of that name, so the reference died with
 * {@code NoClassDefFoundError} on the next {@code new Host()}. Renumbered classes must be injected instead.</p>
 *
 * <p><b>Residual 2 (rollback)</b>: an outer anonymous class shifts slots and is aligned back to the old slot
 * ({@code Host$3 -> Host$2}); its nested anonymous class compiled as {@code Host$3$innerObj$1} while the old side
 * lives at {@code Host$2$innerObj$1}. Aligning the inner group under the pre-rename host name found no old side
 * ({@code level 2: old=0, new=1}) and Stage A rolled the whole family back, so the surviving inner instance kept
 * running the old body.</p>
 */
public class LazyLoadRenumberProbe {

	static int passed = 0;
	static int failed = 0;
	static final List<String> logs = new ArrayList<>();

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "LRN-PASS " : "LRN-FAIL ") + msg);
		if (ok) passed++; else failed++;
	}

	static final class CaptureLogger implements RemoldAgent.Logger {
		@Override public void log(String msg) { record("LOG", msg); }
		@Override public void info(String msg) { record("INFO", msg); }
		@Override public void warn(String msg) { record("WARN", msg); }
		@Override public void error(String msg) { record("ERROR", msg); }
		@Override public void error(String msg, Throwable t) { record("ERROR", msg + (t == null ? "" : " | " + t)); }
		private void record(String level, String msg) {
			synchronized (logs) { logs.add("[" + level + "] " + msg); }
			System.out.println("LRN-AGENT [" + level + "] " + msg);
		}
	}

	// ------------------------------------------------------------------ fixtures

	/**
	 * v1: three anonymous classes, in order alpha, beta, gamma, plus a nested object inside beta.
	 * v2: alpha is DELETED, delta is appended at the end. javac therefore compiles
	 * {@code beta->$1, gamma->$2, delta->$3} (shifted), the aligner matches beta back to {@code $2} and gamma to
	 * {@code $3}, and delta — whose compiled name {@code $3} is now taken — must be pushed to a FRESH slot.
	 */
	/**
	 * v1: X (captures a String), B (captures an int, contains a nested object), C (captures nothing).
	 * Each anonymous class has a distinct captured-field shape so the aligner can tell them apart instead of
	 * refusing on ambiguity.
	 *
	 * v2: X is DELETED and D (captures a double) is appended. javac compiles B->$1, C->$2, D->$3; the aligner
	 * matches B back to the old $2 and C to the old $3, so D's compiled name $3 is taken and D must be pushed to a
	 * fresh slot — exactly the name that exists nowhere on disk.
	 */
	/**
	 * v1: x (no capture), b (captures an int, contains a nested object), c (no capture).
	 * Every anonymous class has a distinct shape so the aligner can tell them apart.
	 *
	 * v2: x is deleted and d (captures a double) is appended. javac compiles b->$1, c->$2, d->$3; the aligner
	 * matches b back to the old $2 and c to the old $3, so d's compiled name $3 is already taken and d must be
	 * pushed to a fresh slot — a name that exists nowhere on disk.
	 *
	 * Note: no declared field is removed by this edit, so the layout gate has nothing to refuse.
	 */
	/**
	 * v1: x (no capture), b (captures an int, contains a nested object), c (no capture).
	 *
	 * v2: x's anonymous class disappears (the field itself stays, assigned null), and c now captures `ctag`.
	 * One edit therefore produces the two field-reported shapes at once:
	 * <ul>
	 *   <li>javac renumbers the survivors (b -> $1, c -> $2) and the aligner matches b back to the old $2, so the
	 *       nested object must follow ({@code $1$1 -> $2$1});</li>
	 *   <li>c's bytecode gained a capture field, so the layout gate refuses to reuse its old class and it gets a
	 *       FRESH slot — a name that exists nowhere on disk, which is exactly what lazy_load cannot resolve.</li>
	 * </ul>
	 * No declared field is removed, so nothing here is a layout-gate refusal of the host itself.
	 */
	static final String HOST_V1 = """
		package lrn;
		public class TabHost {
		    public final Runnable x = new Runnable() { public void run() { Sink.add("x-v1"); } };
		    private final int btag = 7;
		    private final int ctag = 3;
		    public final Runnable b = new Runnable() {
		        public void run() {
		            Sink.add("b-v1:" + btag + ":" + new Object() {
		                public String toString() { return "inner-v1"; }
		            });
		        }
		    };
		    public final Runnable c = new Runnable() { public void run() { Sink.add("c-v1"); } };
		    public String stage() { return "v1"; }
		}
		""";

	static final String HOST_V2 = """
		package lrn;
		public class TabHost {
		    public final Runnable x = null;
		    private final int btag = 7;
		    private final int ctag = 3;
		    public final Runnable b = new Runnable() {
		        public void run() {
		            Sink.add("b-v2:" + btag + ":" + new Object() {
		                public String toString() { return "inner-v2"; }
		            });
		        }
		    };
		    public final Runnable c = new Runnable() { public void run() { Sink.add("c-v2:" + ctag); } };
		    public String stage() { return "v2"; }
		}
		""";

	static final String SINK = """
		package lrn;
		import java.util.ArrayList;
		import java.util.List;
		public final class Sink {
		    public static final List<String> LINES = new ArrayList<>();
		    public static void add(String s) { LINES.add(s); }
		    public static void clear() { LINES.clear(); }
		    public static String joined() { return String.join("|", LINES); }
		}
		""";

	public static void main(String[] args) throws Throwable {
		System.out.println("=== LazyLoadRenumberProbe: renumbered anon classes and nested shifts under lazy_load ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "P0 instrumentation is available");
		check(RemoldAgent.isEnhancedHotswapEnabled(), "P0 enhanced class redefinition is enabled (JBR/DCEVM)");
		if (inst == null) { summary(); System.exit(1); return; }
		RemoldAgent.logger = new CaptureLogger();
		try {
			run(inst);
		} catch (Throwable t) {
			check(false, "probe aborted: " + describe(t));
			t.printStackTrace();
		}
		summary();
		if (failed > 0) System.exit(1);
	}

	static void run(Instrumentation inst) throws Throwable {
		Map<String, String> v1Sources = new LinkedHashMap<>();
		v1Sources.put("lrn.Sink", SINK);
		v1Sources.put("lrn.TabHost", HOST_V1);
		Map<String, byte[]> v1 = LayoutGateAssert.compile(v1Sources);

		Map<String, String> v2Sources = new LinkedHashMap<>();
		v2Sources.put("lrn.Sink", SINK);
		v2Sources.put("lrn.TabHost", HOST_V2);
		Map<String, byte[]> v2 = LayoutGateAssert.compile(v2Sources);

		// 落盘 v1（磁盘上只有编译期名字）—— 与真实构建输出一致
		Path dir = Files.createTempDirectory("remold-lazyload-renumber");
		for (Map.Entry<String, byte[]> e : v1.entrySet()) {
			Path p = dir.resolve(e.getKey().replace('.', '/') + ".class");
			Files.createDirectories(p.getParent());
			Files.write(p, e.getValue());
		}

		Map<String, byte[]> initial = new HashMap<>(v1);
		FixtureLoader loader = new FixtureLoader(initial);
		Class<?> hostClass = Class.forName("lrn.TabHost", true, loader);
		Class<?> sinkClass = Class.forName("lrn.Sink", true, loader);
		Object survivor = hostClass.getDeclaredConstructor().newInstance();
		check("v1".equals(hostClass.getMethod("stage").invoke(survivor)), "R0-baseline: host runs v1");

		// 切换到现场模式：ModTools/Arc 运行期把模式切成 lazy_load
		RemoldAgent.REDEFINE_MODE = RemoldAgent.RedefineMode.lazy_load;
		RemoldAgent.UCP_APPEND = true;
		RemoldAgent.ANON_DEBUG = true;
		System.out.println("-- mode=lazy_load; writing v2 build output to disk --");

		// v2 全量落盘，并把**所有**产物路径喂给 processChanges —— 真实构建工具也是整目录喂变更集，
		// 匿名类必须一起进批次，否则对齐器只能看到宿主（new=0）。
		Set<Path> changed = new java.util.LinkedHashSet<>();
		for (Map.Entry<String, byte[]> e : v2.entrySet()) {
			Path p = dir.resolve(e.getKey().replace('.', '/') + ".class");
			Files.createDirectories(p.getParent());
			Files.write(p, e.getValue());
			changed.add(p);
		}
		process(changed, inst);

		check("v2".equals(hostClass.getMethod("stage").invoke(survivor)),
			"R1: the host really took the new version (stage=" + hostClass.getMethod("stage").invoke(survivor) + ")");

		// 残留 1 的现场动作：新增一个宿主实例 —— 它会执行 NEW <delta 的重编号类名>
		String constructOutcome = "ok";
		Object fresh = null;
		try {
			fresh = hostClass.getDeclaredConstructor().newInstance();
		} catch (Throwable t) {
			Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : t;
			constructOutcome = cause.getClass().getName() + ": " + cause.getMessage();
		}
		check(fresh != null,
			"R1: constructing the host after the hot swap does not throw (outcome=" + constructOutcome + ")");

		// 残留 2 的现场读数：内层嵌套匿名类必须跟着外层改名对齐
		boolean innerAligned = false;
		boolean rolledBack = false;
		for (String line : snapshot()) {
			if (line.contains("level 2: old=0")) innerAligned = false;
			if (line.contains("ROLLED-BACK-A")) rolledBack = true;
		}
		check(!rolledBack, "R1: no Stage A rollback for the nested family");

		if (fresh != null) {
			sinkClass.getMethod("clear").invoke(null);
			((Runnable) hostClass.getField("b").get(fresh)).run();
			String out = String.valueOf(sinkClass.getMethod("joined").invoke(null));
			check(out.contains("b-v2") && out.contains("inner-v2"),
				"R1: the nested anonymous object inside b runs its new body (observable=" + out + ")");
			sinkClass.getMethod("clear").invoke(null);
			((Runnable) hostClass.getField("c").get(fresh)).run();
			String freshCOut = String.valueOf(sinkClass.getMethod("joined").invoke(null));
			check(freshCOut.contains("c-v2"),
				"R1: the renumbered invisible class (fresh slot, must be injected) runs (observable=" + freshCOut + ")");
		}

		// 存量实例的内层也必须跟上（残留 2 的终局）
		sinkClass.getMethod("clear").invoke(null);
		((Runnable) hostClass.getField("b").get(survivor)).run();
		String survivorOut = String.valueOf(sinkClass.getMethod("joined").invoke(null));
		check(survivorOut.contains("inner-v2"),
			"R1: the SURVIVING host's nested anonymous object also runs the new body (observable=" + survivorOut + ")");
	}

	// ------------------------------------------------------------------ helpers

	static String describe(Throwable t) {
		if (t == null) return "none";
		Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : t;
		return cause.getClass().getName() + ": " + cause.getMessage();
	}

	static List<String> snapshot() {
		synchronized (logs) { return List.copyOf(logs); }
	}

	static void process(Set<Path> changed, Instrumentation inst) throws Exception {
		Method m = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
		m.setAccessible(true);
		m.invoke(null, changed, inst.getAllLoadedClasses());
	}

	static final class FixtureLoader extends ClassLoader {
		private final Map<String, byte[]> classes;
		FixtureLoader(Map<String, byte[]> classes) {
			super(LazyLoadRenumberProbe.class.getClassLoader());
			this.classes = classes;
		}
		@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	static void summary() {
		System.out.println("LRN-SUMMARY passed=" + passed + " failed=" + failed);
	}
}
