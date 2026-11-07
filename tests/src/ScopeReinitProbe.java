import remold.RemoldAgent;
import remold.annotation.RemoldReinit;
import remold.ref.InitFix;
import remold.util.ClassIdentity;
import remold.util.LibTool;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Red/green probe for {@code @RemoldReinit(scope = ...)} (item 4,
 * {@code docs/initfix/03-runtime-driver.md} §4). Requires agent + DCEVM; runs in seconds and
 * needs no Minecraft/Fabric/Forge server.
 *
 * <p>Scenarios:</p>
 * <ol>
 *   <li>{@code SELF} on a parent class with two live subclass instances: only the instance whose
 *       runtime class is exactly the declaring class is reinitialized; both subclass instances keep
 *       their old value.</li>
 *   <li>Default {@code INCLUDE_SUBCLASSES}: declaring-class instance and both subclass instances are
 *       all reinitialized (existing behaviour preserved).</li>
 *   <li>After a STRUCTURAL redefine (an instance field is added) {@code SELF} still filters exactly:
 *       the parent instance gets both the reinitialized old field and the newly added field, while
 *       subclass instances only get the added field. The Class reference held in a static field is
 *       canonicalized, so a JBR old-version shadow Class cannot confuse the exact-class filter.</li>
 *   <li>{@code scope} on a static field has no meaning: the field is still reinitialized and a single
 *       warn line is emitted.</li>
 * </ol>
 *
 * <p>The probe is written so that removing the exact-class filter ({@code instance.getClass() ==
 * canonical}) from {@link InitFix#scopeMatches} turns it red: scenarios 1 and 3 then observe the
 * parent initializer written into subclass instances. See the evidence file for the recorded
 * mutation check.</p>
 *
 * <p>Run: {@code ./gradlew :tests:scopeReinitProbe} (uses JBR_HOME / p05.jbr when set).</p>
 */
public class ScopeReinitProbe {
	static int passed = 0, failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static void obs(String msg) {
		System.out.println("   [OBS] " + msg);
	}

	/**
	 * Static field holding a Class reference across the structural redefine: JBR rewrites such
	 * references to an old-version shadow Class. Used to prove the exact-class filter canonicalizes
	 * the host before comparing identities.
	 */
	static Class<?> hostRef;

	/** Kept across scenarios so the structural-redefine scenario reuses the same class/loader. */
	static FixtureLoader parentLoader;
	static Class<?> parentClass;

	static final List<String> logLines = Collections.synchronizedList(new ArrayList<>());

	// ---------------------------------------------------------------- fixtures (compiled in-memory)

	static final String P_V1 = """
		package sr;
		public class Parent {
		    public int value = 1;
		    public int value() { return value; }
		    public int added() { return -1; }
		}
		""";

	static final String P_V2 = """
		package sr;
		import remold.annotation.RemoldReinit;
		public class Parent {
		    @RemoldReinit(scope = RemoldReinit.Scope.SELF)
		    public int value = 7;
		    public int value() { return value; }
		    public int added() { return -1; }
		}
		""";

	static final String P_V3 = """
		package sr;
		import remold.annotation.RemoldReinit;
		public class Parent {
		    @RemoldReinit(scope = RemoldReinit.Scope.SELF)
		    public int value = 9;
		    public int added = 5;
		    public int value() { return value; }
		    public int added() { return added; }
		}
		""";

	static final String CHILD_A = """
		package sr;
		public class ChildA extends Parent { }
		""";

	static final String CHILD_B = """
		package sr;
		public class ChildB extends Parent { }
		""";

	static final String P2_V1 = """
		package sr;
		public class Parent2 {
		    public int value = 1;
		    public int value() { return value; }
		}
		""";

	static final String P2_V2 = """
		package sr;
		import remold.annotation.RemoldReinit;
		public class Parent2 {
		    @RemoldReinit
		    public int value = 7;
		    public int value() { return value; }
		}
		""";

	static final String CHILD2_A = """
		package sr;
		public class Child2A extends Parent2 { }
		""";

	static final String CHILD2_B = """
		package sr;
		public class Child2B extends Parent2 { }
		""";

	static final String STATIC_V1 = """
		package sr;
		public class StaticHost {
		    public static int counter = 1;
		    public static int read() { return counter; }
		}
		""";

	static final String STATIC_V2 = """
		package sr;
		import remold.annotation.RemoldReinit;
		public class StaticHost {
		    @RemoldReinit(scope = RemoldReinit.Scope.SELF)
		    public static int counter = 3;
		    public static int read() { return counter; }
		}
		""";

	// ---------------------------------------------------------------- driver

	public static void main(String[] args) throws Exception {
		System.out.println("=== ScopeReinitProbe (item 4: @RemoldReinit scope) ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "instrumentation available");
		check(LibTool.initialized(),
			"LibTool JVMTI heap scan available (required to enumerate live instances)");
		if (inst == null || !LibTool.initialized()) {
			summary();
			return;
		}

		RemoldAgent.Logger original = RemoldAgent.logger;
		RemoldAgent.logger = new RemoldAgent.Logger() {
			@Override public void log(String msg) { original.log(msg); }
			@Override public void info(String msg) { logLines.add("[INFO] " + msg); original.info(msg); }
			@Override public void warn(String msg) { logLines.add("[WARN] " + msg); original.warn(msg); }
			@Override public void error(String msg) { logLines.add("[ERROR] " + msg); original.error(msg); }
			@Override public void error(String msg, Throwable t) { logLines.add("[ERROR] " + msg); original.error(msg, t); }
		};
		try {
			scenarioSelfOnParentWithSubclasses(inst);
			scenarioDefaultIncludeSubclasses(inst);
			scenarioStructuralRedefineWithShadowHost(inst);
			scenarioScopeOnStaticField(inst);
		} finally {
			RemoldAgent.logger = original;
		}
		summary();
	}

	/**
	 * SELF: declaring class + two live subclass instances. Only the exact-class instance may be
	 * reinitialized.
	 */
	private static void scenarioSelfOnParentWithSubclasses(Instrumentation inst) throws Exception {
		System.out.println("-- scenario 1: SELF on parent with two live subclass instances --");
		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of(
			"sr.Parent", P_V1, "sr.ChildA", CHILD_A, "sr.ChildB", CHILD_B));
		FixtureLoader loader = new FixtureLoader(v1);
		Class<?> parent = Class.forName("sr.Parent", true, loader);
		Class<?> childA = Class.forName("sr.ChildA", true, loader);
		Class<?> childB = Class.forName("sr.ChildB", true, loader);
		parentLoader = loader;
		parentClass = parent;
		Object p = parent.getConstructor().newInstance();
		Object a = childA.getConstructor().newInstance();
		Object b = childB.getConstructor().newInstance();

		Method value = parent.getMethod("value");
		check(intOf(value, p) == 1 && intOf(value, a) == 1 && intOf(value, b) == 1,
			"baseline (before redefine): parent and both subclass instances have value=1");

		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of("sr.Parent", P_V2));
		registerBaseline("sr.Parent", v1.get("sr.Parent"));
		check(RemoldAgent.redefineDynamic(parent, v2.get("sr.Parent")),
			"annotation-only redefine (scope=SELF) returned true");
		check(Class.forName("sr.Parent", false, loader) == parent,
			"canonical Class identity stable across annotation-only redefine");

		check(intOf(value, p) == 7,
			"SELF: declaring-class instance reinitialized to the new initializer (7), was "
				+ intOf(value, p));
		check(intOf(value, a) == 1,
			"SELF: direct subclass instance must stay at its old value (1), was " + intOf(value, a));
		check(intOf(value, b) == 1,
			"SELF: second subclass instance must stay at its old value (1), was " + intOf(value, b));
	}

	/** Default INCLUDE_SUBCLASSES: parent + subclass instances are all reinitialized. */
	private static void scenarioDefaultIncludeSubclasses(Instrumentation inst) throws Exception {
		System.out.println("-- scenario 2: default INCLUDE_SUBCLASSES --");
		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of(
			"sr.Parent2", P2_V1, "sr.Child2A", CHILD2_A, "sr.Child2B", CHILD2_B));
		FixtureLoader loader = new FixtureLoader(v1);
		Class<?> parent = Class.forName("sr.Parent2", true, loader);
		Class<?> childA = Class.forName("sr.Child2A", true, loader);
		Class<?> childB = Class.forName("sr.Child2B", true, loader);
		Object p = parent.getConstructor().newInstance();
		Object a = childA.getConstructor().newInstance();
		Object b = childB.getConstructor().newInstance();

		Method value = parent.getMethod("value");
		check(intOf(value, p) == 1 && intOf(value, a) == 1 && intOf(value, b) == 1,
			"baseline (before redefine): parent and both subclass instances have value=1");

		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of("sr.Parent2", P2_V2));
		registerBaseline("sr.Parent2", v1.get("sr.Parent2"));
		check(RemoldAgent.redefineDynamic(parent, v2.get("sr.Parent2")),
			"annotation-only redefine (default scope) returned true");

		check(intOf(value, p) == 7, "INCLUDE_SUBCLASSES: declaring-class instance reset to 7, was "
			+ intOf(value, p));
		check(intOf(value, a) == 7, "INCLUDE_SUBCLASSES: direct subclass instance reset to 7, was "
			+ intOf(value, a));
		check(intOf(value, b) == 7, "INCLUDE_SUBCLASSES: second subclass instance reset to 7, was "
			+ intOf(value, b));
	}

	/**
	 * Structural redefine (new instance field) after a static field already holds the host Class:
	 * SELF still filters exactly, added field still reaches subclass instances, and the shadow Class
	 * held in the static field is canonicalized before identity comparison.
	 */
	private static void scenarioStructuralRedefineWithShadowHost(Instrumentation inst) throws Exception {
		System.out.println("-- scenario 3: structural redefine + shadow-Class identity --");
		// Parent is already at v2 (SELF, value=7) with live parent + subclass instances from scenario 1.
		check(parentLoader != null && parentClass != null,
			"baseline: scenario 1 left a loaded Parent class + loader to reuse");
		if (parentLoader == null || parentClass == null) return;
		// The static field may have been rewritten to a JBR shadow Class by scenario 1's redefine:
		// normalize it before using it as a JVMTI class or for identity comparisons.
		Class<?> parent = ClassIdentity.canonicalize(parentClass);
		ClassLoader loader = parent.getClassLoader();
		Object p = findLiveInstance(parent);
		Object a = findLiveInstance(Class.forName("sr.ChildA", false, loader));
		Object b = findLiveInstance(Class.forName("sr.ChildB", false, loader));
		check(p != null && a != null && b != null,
			"baseline: the three live instances from scenario 1 are still reachable");

		// Both methods exist in v1/v2 already, so resolve them before the structural redefine.
		Method value = parent.getMethod("value");
		Method added = parent.getMethod("added");
		check(intOf(value, p) == 7 && intOf(value, a) == 1 && intOf(value, b) == 1,
			"baseline (before structural redefine): SELF state is p=7, a=1, b=1");
		check(intOf(added, p) == -1 && intOf(added, a) == -1 && intOf(added, b) == -1,
			"baseline (before structural redefine): added() reports -1 (field does not exist yet)");

		// This static field is what JBR rewrites to an old-version shadow Class on structural redefine.
		hostRef = parent;

		Map<String, byte[]> v3 = LayoutGateAssert.compile(Map.of("sr.Parent", P_V3));
		check(RemoldAgent.redefineDynamic(parent, v3.get("sr.Parent")),
			"structural redefine (add field 'added') returned true");
		check(Class.forName("sr.Parent", false, loader) == parent,
			"canonical Class identity stable across structural redefine");

		check(intOf(value, p) == 9,
			"SELF after structural redefine: declaring-class instance reset to 9, was "
				+ intOf(value, p));
		check(intOf(value, a) == 1,
			"SELF after structural redefine: subclass instance value untouched (1), was "
				+ intOf(value, a));
		check(intOf(value, b) == 1,
			"SELF after structural redefine: second subclass instance value untouched (1), was "
				+ intOf(value, b));

		check(intOf(added, p) == 5, "added field patched on declaring-class instance (5), was "
			+ intOf(added, p));
		check(intOf(added, a) == 5, "added field patched on subclass instance (5), was "
			+ intOf(added, a));
		check(intOf(added, b) == 5, "added field patched on second subclass instance (5), was "
			+ intOf(added, b));

		// ---- shadow-Class identity checks ----
		Class<?> shadow = hostRef;
		obs("static-field host ref after structural redefine: same=" + (shadow == parent)
			+ ", shadowOccurred=" + (shadow != parent));
		check(ClassIdentity.canonicalize(shadow) == parent,
			"static-field Class reference normalizes to the canonical Class (shadow handled)");
		check(InitFix.scopeMatches(shadow, p, RemoldReinit.Scope.SELF),
			"SELF via the shadow host still matches the declaring-class instance");
		check(!InitFix.scopeMatches(shadow, a, RemoldReinit.Scope.SELF)
			      && !InitFix.scopeMatches(shadow, b, RemoldReinit.Scope.SELF),
			"SELF via the shadow host still excludes both subclass instances");
		check(InitFix.scopeMatches(shadow, a, RemoldReinit.Scope.INCLUDE_SUBCLASSES),
			"INCLUDE_SUBCLASSES via the shadow host still matches subclass instances");
		check(LibTool.getInstances(shadow).length == 3,
			"LibTool.getInstances(shadow) enumerates all 3 live instances after canonicalization ("
				+ LibTool.getInstances(shadow).length + ")");
	}

	/**
	 * Scope on a static field has no meaning: the field is still reinitialized and exactly one warn
	 * line names the ignored scope.
	 */
	private static void scenarioScopeOnStaticField(Instrumentation inst) throws Exception {
		System.out.println("-- scenario 4: scope on a static field is ignored with a warn --");
		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of("sr.StaticHost", STATIC_V1));
		FixtureLoader loader = new FixtureLoader(v1);
		Class<?> host = Class.forName("sr.StaticHost", true, loader);
		Method read = host.getMethod("read");
		check(intOfStatic(read) == 1, "baseline (before redefine): static counter=1");

		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of("sr.StaticHost", STATIC_V2));
		registerBaseline("sr.StaticHost", v1.get("sr.StaticHost"));
		int warnBefore = countWarns("scope has no effect on static field");
		check(RemoldAgent.redefineDynamic(host, v2.get("sr.StaticHost")),
			"static-field redefine (scope=SELF) returned true");
		check(intOfStatic(read) == 3,
			"static @RemoldReinit field is still reinitialized to 3 (scope ignored), was "
				+ intOfStatic(read));

		int warnAfter = countWarns("scope has no effect on static field");
		check(warnAfter == warnBefore + 1,
			"exactly one warn line for the meaningless static scope (before=" + warnBefore
				+ ", after=" + warnAfter + ")");
		boolean namesField = false;
		synchronized (logLines) {
			for (String line : logLines) {
				if (line.startsWith("[WARN]") && line.contains("scope has no effect on static field")
				    && line.contains("sr.StaticHost.counter")) {
					namesField = true;
					break;
				}
			}
		}
		check(namesField, "the warn line names the offending static field sr.StaticHost.counter");
	}

	// ---------------------------------------------------------------- helpers

	private static int countWarns(String needle) {
		int n = 0;
		synchronized (logLines) {
			for (String line : logLines) {
				if (line.startsWith("[WARN]") && line.contains(needle)) n++;
			}
		}
		return n;
	}

	private static Object findLiveInstance(Class<?> type) {
		Class<?> canonical = ClassIdentity.canonicalize(type);
		Object[] found = LibTool.getInstances(canonical);
		for (Object o : found) {
			if (o != null && o.getClass() == canonical) return o;
		}
		return null;
	}

	private static int intOf(Method method, Object target) throws Exception {
		return (Integer) method.invoke(target);
	}

	private static int intOfStatic(Method method) throws Exception {
		return (Integer) method.invoke(null);
	}

	private static void registerBaseline(String className, byte[] bytes) {
		RemoldAgent.bytecodeCache.put(className, bytes);
		RemoldAgent.sourceBytecodeCache.put(className, bytes);
	}

	private static void summary() {
		System.out.println("=== SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	static final class FixtureLoader extends ClassLoader {
		final Map<String, byte[]> classes;

		FixtureLoader(Map<String, byte[]> classes) {
			super(ScopeReinitProbe.class.getClassLoader());
			this.classes = new LinkedHashMap<>(classes);
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}
