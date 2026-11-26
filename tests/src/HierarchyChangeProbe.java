import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interface add / remove / reorder and superclass change on a live class: what really happens.
 *
 * <p>{@code ClassDiffUtil.mustRejectHierarchyChange} rejects a superclass change unconditionally but only rejects
 * interface changes when the JVM has no enhanced redefinition, so "interface changes are unsupported" would be an
 * unsupported claim. This probe measures each shape on JBR/DCEVM instead of inferring it:</p>
 *
 * <ol>
 *   <li><b>R0</b> control: a body-only edit must apply and must not disturb {@code instanceof}.</li>
 *   <li><b>R1</b> add an interface: the <em>surviving</em> instance and a fresh instance must both satisfy
 *       {@code instanceof NewIface}, and calls through the new interface must dispatch.</li>
 *   <li><b>R2</b> remove that interface again: both instances must stop satisfying it.</li>
 *   <li><b>R3</b> change the superclass: the class must be refused by name ({@code HIERARCHY-CHANGED}) and stay
 *       functional on the old parent, not silently half-applied.</li>
 * </ol>
 */
public class HierarchyChangeProbe {

	static int passed = 0;
	static int failed = 0;
	static final List<String> logs = new ArrayList<>();

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
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
			System.out.println("HCP-AGENT [" + level + "] " + msg);
		}
	}

	// ------------------------------------------------------------------ fixtures

	static final String IFACE_ALPHA = """
		package hcp;
		public interface Alpha { String alpha(); }
		""";

	static final String IFACE_BETA = """
		package hcp;
		public interface Beta { String beta(); }
		""";

	/** v1: implements Alpha only. */
	static final String IMPL_V1 = """
		package hcp;
		public class Impl implements Alpha {
		    public String stage() { return "v1"; }
		    public String alpha() { return "alpha-v1"; }
		}
		""";

	/** v1b: body-only edit (control round). */
	static final String IMPL_V1B = """
		package hcp;
		public class Impl implements Alpha {
		    public String stage() { return "v1b"; }
		    public String alpha() { return "alpha-v1b"; }
		}
		""";

	/** v2: Alpha + Beta — an interface was ADDED to a live class. */
	static final String IMPL_V2 = """
		package hcp;
		public class Impl implements Alpha, Beta {
		    public String stage() { return "v2"; }
		    public String alpha() { return "alpha-v2"; }
		    public String beta() { return "beta-v2"; }
		}
		""";

	/** v3: back to Alpha only — the interface was REMOVED again. */
	static final String IMPL_V3 = """
		package hcp;
		public class Impl implements Alpha {
		    public String stage() { return "v3"; }
		    public String alpha() { return "alpha-v3"; }
		}
		""";

	static final String BASE_A = """
		package hcp;
		public class BaseA { public String base() { return "baseA"; } }
		""";

	static final String BASE_B = """
		package hcp;
		public class BaseB { public String base() { return "baseB"; } }
		""";

	/** v4 would change the superclass: BaseA -> BaseB, which no redefinition mode supports. */
	static final String IMPL_V4_OTHER_SUPER = """
		package hcp;
		public class Impl extends BaseB implements Alpha {
		    public String stage() { return "v4"; }
		    public String alpha() { return "alpha-v4"; }
		}
		""";

	public static void main(String[] args) throws Throwable {
		System.out.println("=== HierarchyChangeProbe: interface add/remove vs superclass change ===");
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
		Map<String, String> commonSources = new HashMap<>();
		commonSources.put("hcp.Alpha", IFACE_ALPHA);
		commonSources.put("hcp.Beta", IFACE_BETA);
		commonSources.put("hcp.BaseA", BASE_A);
		commonSources.put("hcp.BaseB", BASE_B);
		Map<String, byte[]> common = LayoutGateAssert.compile(commonSources);
		Map<String, byte[]> v1 = compile(commonSources, "hcp.Impl", IMPL_V1);
		Map<String, byte[]> v1b = compile(commonSources, "hcp.Impl", IMPL_V1B);
		Map<String, byte[]> v2 = compile(commonSources, "hcp.Impl", IMPL_V2);
		Map<String, byte[]> v3 = compile(commonSources, "hcp.Impl", IMPL_V3);
		Map<String, byte[]> v4 = compile(commonSources, "hcp.Impl", IMPL_V4_OTHER_SUPER);

		Map<String, byte[]> initial = new HashMap<>();
		putAll(initial, common);
		initial.put("hcp.Impl", v1.get("hcp.Impl"));
		FixtureLoader loader = new FixtureLoader(initial);
		Class<?> implClass = Class.forName("hcp.Impl", true, loader);
		Object survivor = implClass.getDeclaredConstructor().newInstance();
		Object fresh0 = implClass.getDeclaredConstructor().newInstance();

		Class<?> alpha = Class.forName("hcp.Alpha", true, loader);
		Class<?> beta = Class.forName("hcp.Beta", true, loader);
		check(alpha.isInstance(survivor) && !beta.isInstance(survivor),
			"R0-baseline: the live instance implements Alpha and not Beta");

		Path dir = Files.createTempDirectory("remold-hierarchy");
		Path implPath = dir.resolve("hcp/Impl.class");

		// ================= R0: body-only control =================
		System.out.println("-- R0: body-only edit (control) --");
		write(implPath, v1b.get("hcp.Impl"));
		process(implPath, inst);
		check("v1b".equals(call(survivor, "stage")), "R0: the surviving instance runs the new body (" + call(survivor, "stage") + ")");
		check(alpha.isInstance(survivor) && !beta.isInstance(survivor), "R0: instanceof set unchanged");

		// ================= R1: ADD an interface =================
		System.out.println("-- R1: add interface Beta to a live class --");
		write(implPath, v2.get("hcp.Impl"));
		process(implPath, inst);
		// The surviving instance's Class object is the same object that was redefined, so instanceof must follow.
		check("v2".equals(call(survivor, "stage")), "R1: the surviving instance runs v2 (" + call(survivor, "stage") + ")");
		check(beta.isInstance(survivor),
			"R1: the surviving instance satisfies instanceof Beta=" + beta.isInstance(survivor));
		String viaBeta;
		try {
			ViaBeta invoker = (ViaBeta) createInterfaceInvoker(beta, survivor);
			viaBeta = invoker.call();
		} catch (Throwable t) {
			viaBeta = "<threw " + t.getClass().getSimpleName() + ": " + t.getMessage() + ">";
		}
		check("beta-v2".equals(viaBeta), "R1: dispatch through the newly added interface returns '" + viaBeta + "'");
		Object freshAfterAdd = implClass.getDeclaredConstructor().newInstance();
		check(beta.isInstance(freshAfterAdd), "R1: a freshly created instance also implements Beta");
		check(alpha.isInstance(survivor), "R1: the original interface is still implemented");

		// ================= R2: REMOVE the interface again =================
		System.out.println("-- R2: remove interface Beta again --");
		write(implPath, v3.get("hcp.Impl"));
		process(implPath, inst);
		check("v3".equals(call(survivor, "stage")), "R2: the surviving instance runs v3 (" + call(survivor, "stage") + ")");
		check(!beta.isInstance(survivor),
			"R2: instanceof Beta is now false (actual=" + beta.isInstance(survivor) + ")");
		check(alpha.isInstance(survivor), "R2: Alpha remains implemented");

		// ================= R3: change the superclass =================
		System.out.println("-- R3: change the superclass (must be refused by name) --");
		logs.clear();
		write(implPath, v4.get("hcp.Impl"));
		Throwable escaped = null;
		try {
			process(implPath, inst);
		} catch (Throwable t) {
			escaped = t;
		}
		check(escaped == null, "R3: the round did not throw (" + describe(escaped) + ")");
		boolean refused = false;
		for (String line : snapshot()) {
			if (line.contains("HIERARCHY-CHANGED") || line.contains("REJECTED: Class hierarchy change")) { refused = true; break; }
		}
		check(refused, "R3: the agent refused the class by name (HIERARCHY-CHANGED / REJECTED logged)");
		check("v3".equals(call(survivor, "stage")),
			"R3: the surviving instance still runs the last accepted version (" + call(survivor, "stage") + ")");
		check(alpha.isInstance(survivor), "R3: the refused round did not corrupt the interface set");
	}

	// ------------------------------------------------------------------ helpers

	interface ViaBeta { String call(); }

	/** Reflectively adapt the surviving instance to the freshly added interface. */
	static Object createInterfaceInvoker(Class<?> beta, Object instance) throws Exception {
		if (!beta.isInstance(instance)) throw new IllegalStateException("instance does not implement Beta");
		java.lang.reflect.Method m = beta.getMethod("beta");
		m.setAccessible(true);
		return (ViaBeta) () -> {
			try {
				return (String) m.invoke(instance);
			} catch (java.lang.reflect.InvocationTargetException e) {
				throw new RuntimeException(e.getCause());
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		};
	}

	static String call(Object instance, String method) {
		try {
			Method m = instance.getClass().getMethod(method);
			m.setAccessible(true);
			return String.valueOf(m.invoke(instance));
		} catch (Throwable t) {
			Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : t;
			return "<threw " + cause.getClass().getSimpleName() + ">";
		}
	}

	static String describe(Throwable t) {
		if (t == null) return "none";
		Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : t;
		return cause.getClass().getName() + ": " + cause.getMessage();
	}

	static List<String> snapshot() {
		synchronized (logs) { return List.copyOf(logs); }
	}

	static Map<String, byte[]> compile(Map<String, String> common, String name, String source) {
		Map<String, String> sources = new HashMap<>(common);
		sources.put(name, source);
		return LayoutGateAssert.compile(sources);
	}

	static void putAll(Map<String, byte[]> target, Map<String, byte[]> source) {
		for (Map.Entry<String, byte[]> e : source.entrySet()) {
			if (e.getKey().equals("hcp.Impl")) continue;
			target.put(e.getKey(), e.getValue());
		}
	}

	static void write(Path path, byte[] bytes) throws Exception {
		Files.createDirectories(path.getParent());
		Files.write(path, bytes);
	}

	static void process(Path changed, Instrumentation inst) throws Exception {
		Method m = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
		m.setAccessible(true);
		m.invoke(null, Set.of(changed), inst.getAllLoadedClasses());
	}

	static final class FixtureLoader extends ClassLoader {
		private final Map<String, byte[]> classes;
		FixtureLoader(Map<String, byte[]> classes) {
			super(HierarchyChangeProbe.class.getClassLoader());
			this.classes = classes;
		}
		@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	static void summary() {
		System.out.println("=== SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
	}
}
