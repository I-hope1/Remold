import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code @JvmStatic @OnReload} must fire once, not twice.
 *
 * <p>kotlinc turns {@code object Foo { @JvmStatic @OnReload fun ping() } } into two annotated members of the same
 * shape: an instance {@code ping()} (the real body) and a static forwarder {@code ping()} that loads the singleton
 * and calls it. For {@code companion object} the pair lives in two classes (outer forwarder → {@code Foo$Companion}
 * instance). The dispatcher walked annotated methods per class, so both identities ran the body: a reload callback
 * that rebuilds a cache or flips a flag ran twice per hotswap.</p>
 *
 * <p>Rounds (production driver {@code RemoldAgent.processChanges}, real redefine on JBR/DCEVM):</p>
 * <ol>
 *   <li><b>R0 baseline</b>: method-body-only edit of the host → the callback must run exactly once (the forwarder
 *       and its target share one implementation key).</li>
 *   <li><b>R1 control</b>: a class with an annotated static method that has a real body (no delegation) and an
 *       unrelated annotated instance method → each must still run once (the dedupe must not swallow real pairs).</li>
 * </ol>
 */
public class OnReloadJvmStaticProbe {

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

	/** One static counter per role, so a double fire is directly observable. */
	static final String COUNTER = """
		package orl;
		public final class Counter {
		    public static int companionBody;
		    public static int realStaticBody;
		    public static int instanceBody;
		    public static void reset() { companionBody = 0; realStaticBody = 0; instanceBody = 0; }
		}
		""";

	/**
	 * Mimics {@code object Holder { @JvmStatic @OnReload fun ping() }}: the static member is a pure forwarder to the
	 * singleton's instance member of the same name and descriptor — exactly what kotlinc generates.
	 */
	static final String HOLDER_V1 = """
		package orl;
		public final class Holder {
		    public static String stage() { return "v1"; }
		    public static final Companion Companion = new Companion();
		    @remold.annotation.OnReload
		    public static void ping() { Companion.ping(); }
		    public static final class Companion {
		        @remold.annotation.OnReload
		        public void ping() { Counter.companionBody++; }
		    }
		}
		""";

	static final String HOLDER_V1B = """
		package orl;
		public final class Holder {
		    public static String stage() { return "v1b"; }
		    public static final Companion Companion = new Companion();
		    @remold.annotation.OnReload
		    public static void ping() { Companion.ping(); }
		    public static final class Companion {
		        @remold.annotation.OnReload
		        public void ping() { Counter.companionBody = Counter.companionBody + 1; }
		    }
		}
		""";

	/**
	 * Control: a static callback with its own body (not a forwarder) plus an unrelated instance callback. Both must
	 * still run — the dedupe must only collapse "forwarder + its own target".
	 */
	static final String CONTROL_V1 = """
		package orl;
		public final class Pair {
		    public static final Pair INSTANCE = new Pair();
		    public static String stage() { return "c1"; }
		    @remold.annotation.OnReload
		    public static void reloadStatic() { Counter.realStaticBody++; }
		    @remold.annotation.OnReload
		    public void reloadInstance() { Counter.instanceBody++; }
		}
		""";

	static final String CONTROL_V1B = """
		package orl;
		public final class Pair {
		    public static final Pair INSTANCE = new Pair();
		    public static String stage() { return "c1b"; }
		    @remold.annotation.OnReload
		    public static void reloadStatic() { Counter.realStaticBody++; }
		    @remold.annotation.OnReload
		    public void reloadInstance() { Counter.instanceBody++; }
		}
		""";

	public static void main(String[] args) throws Throwable {
		System.out.println("=== OnReloadJvmStaticProbe ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "P0 instrumentation is available (run with -javaagent remold-agent.jar)");
		check(RemoldAgent.isEnhancedHotswapEnabled(), "P0 enhanced class redefinition is enabled (JBR/DCEVM)");
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
		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of(
			"orl.Counter", COUNTER, "orl.Holder", HOLDER_V1, "orl.Pair", CONTROL_V1));
		Map<String, byte[]> v1b = LayoutGateAssert.compile(Map.of(
			"orl.Counter", COUNTER, "orl.Holder", HOLDER_V1B, "orl.Pair", CONTROL_V1B));
		byte[] counterBytes = v1.get("orl.Counter");

		Map<String, byte[]> initial = new java.util.HashMap<>();
		initial.put("orl.Counter", counterBytes);
		initial.put("orl.Holder", v1.get("orl.Holder"));
		initial.put("orl.Holder$Companion", v1.get("orl.Holder$Companion"));
		initial.put("orl.Pair", v1.get("orl.Pair"));
		FixtureLoader loader = new FixtureLoader(initial);
		Class<?> counterClass = Class.forName("orl.Counter", true, loader);
		Class<?> holderClass = Class.forName("orl.Holder", true, loader);
		Class<?> pairClass = Class.forName("orl.Pair", true, loader);
		// Force the static initializers so the singleton instances exist before the rounds.
		holderClass.getMethod("stage").invoke(null);
		pairClass.getMethod("stage").invoke(null);
		counterClass.getMethod("reset").invoke(null);

		Path dir = Files.createTempDirectory("remold-onreload-jvmstatic");
		Path holderPath = dir.resolve("orl/Holder.class");
		Path companionPath = dir.resolve("orl/Holder$Companion.class");
		Path pairPath = dir.resolve("orl/Pair.class");
		writeClass(holderPath, v1.get("orl.Holder"));
		writeClass(companionPath, v1.get("orl.Holder$Companion"));
		writeClass(pairPath, v1.get("orl.Pair"));

		// ================= R0: the forwarder and its target are one callback =================
		System.out.println("-- R0: @JvmStatic forwarder + instance target must fire once --");
		writeClass(holderPath, v1b.get("orl.Holder"));
		writeClass(companionPath, v1b.get("orl.Holder$Companion"));
		process(Set.of(holderPath, companionPath), inst);
		check("v1b".equals(holderClass.getMethod("stage").invoke(null)),
			"R0: the host really was redefined (stage=" + holderClass.getMethod("stage").invoke(null) + ")");
		int companion = counterClass.getField("companionBody").getInt(null);
		check(companion == 1,
			"R0: the @OnReload body ran exactly once (actual=" + companion + ")"
				+ " — 2 means the static forwarder and its target both ran");

		// ================= R1: real static + unrelated instance callbacks still each run =================
		System.out.println("-- R1: control — a real static body and an unrelated instance callback --");
		counterClass.getMethod("reset").invoke(null);
		writeClass(pairPath, v1b.get("orl.Pair"));
		process(pairPath, inst);
		check("c1b".equals(pairClass.getMethod("stage").invoke(null)),
			"R1: the control class really was redefined (stage=" + pairClass.getMethod("stage").invoke(null) + ")");
		check(counterClass.getField("realStaticBody").getInt(null) == 1,
			"R1: the static callback with its own body ran once (actual="
				+ counterClass.getField("realStaticBody").getInt(null) + ")");
		check(counterClass.getField("instanceBody").getInt(null) == 1,
			"R1: the unrelated instance callback ran once on the live instance (actual="
				+ counterClass.getField("instanceBody").getInt(null) + ")");
		check(counterClass.getField("companionBody").getInt(null) == 0,
			"R1: the other class's callback did not run in this round");
	}

	// ------------------------------------------------------------------ helpers

	static void writeClass(Path path, byte[] bytes) throws Exception {
		Files.createDirectories(path.getParent());
		Files.write(path, bytes);
	}

	static void process(Path changed, Instrumentation inst) throws Exception {
		process(Set.of(changed), inst);
	}

	static void process(Set<Path> changed, Instrumentation inst) throws Exception {
		Method m = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
		m.setAccessible(true);
		m.invoke(null, changed, inst.getAllLoadedClasses());
	}

	static final class FixtureLoader extends ClassLoader {
		private final Map<String, byte[]> classes;

		FixtureLoader(Map<String, byte[]> classes) {
			super(OnReloadJvmStaticProbe.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	static boolean hasField(Class<?> type, String name) {
		for (Field f : type.getDeclaredFields()) if (f.getName().equals(name)) return true;
		return false;
	}
}
