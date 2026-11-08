import remold.LayoutGate;
import remold.RemoldAgent;
import remold.ref.ApplyInitializerReplay;
import remold.ref.InitFix;
import remold.util.LibTool;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * End-to-end probe for the A-tier Kotlin-apply replay ({@code docs/initfix/13-kotlin-apply-replay.md}).
 *
 * <p>The fixture is compiled with javac but its {@code <clinit>} bytecode is the same shape the
 * Kotlin compiler inlines for {@code object Blocks { val duo = Turret("duo").apply { health = 250 } }}:
 * {@code NEW T; ...; ASTORE; ALOAD; <const>; PUTFIELD T.field; ALOAD; PUTSTATIC holder.dest}
 * (side-by-side javap evidence is recorded in {@code docs/initfix/13-kotlin-apply-replay.md} §2).</p>
 *
 * <p>Each scenario uses a distinct package so the agent's static caches cannot leak between them.
 * Run via {@code ./gradlew :tests:kotlinApplyProbe -Pp05.jbr=<DCEVM JDK>}.</p>
 */
public class KotlinApplyProbe {

	static int passed = 0;
	static int failed = 0;
	static final List<String> WARNINGS = new ArrayList<>();
	static final List<String> INFOS    = new ArrayList<>();
	static Instrumentation inst;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static void checkEq(Object expected, Object actual, String msg) {
		boolean ok = expected == null ? actual == null : expected.equals(actual);
		check(ok, msg + " [expected=" + expected + ", actual=" + actual + "]");
	}

	public static void main(String[] args) throws Throwable {
		System.out.println("=== KotlinApplyProbe ===");
		inst = RemoldAgent.getInstrumentation();
		if (inst == null) {
			System.err.println("[FAIL] no instrumentation");
			System.exit(1);
		}
		RemoldAgent.INIT_FIX = true;
		RemoldAgent.ANON_ALIGN = true;
		RemoldAgent.LAYOUT_GATE = LayoutGate.MODE_REJECT;
		RemoldAgent.OFFSET_STRICT = false;
		RemoldAgent.DEBUG = false;
		RemoldAgent.Logger baseLogger = RemoldAgent.logger;
		RemoldAgent.logger = new RemoldAgent.Logger() {
			public void log(String msg) { baseLogger.log(msg); }
			public void info(String msg) { INFOS.add(msg); baseLogger.info(msg); }
			public void warn(String msg) { WARNINGS.add(msg); baseLogger.warn(msg); }
			public void error(String msg) { baseLogger.error(msg); }
			public void error(String msg, Throwable t) { baseLogger.error(msg, t); }
		};
		try {
			if (!LibTool.initialized()) LibTool.init();
		} catch (Throwable t) {
			System.err.println("[FAIL] native libtool unavailable: " + t);
			System.exit(1);
		}

		scenarioReplayAndConditionalSkip();
		scenarioSubWordFields();
		scenarioNonWhitelisted();
		scenarioNullDestination();

		System.out.println("\n=== KOTLIN-APPLY PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	// ------------------------------------------------------------------------------------------------
	// A1: whitelisted holder, one field replayed, one runtime-modified field skipped, unchanged field silent.
	// ------------------------------------------------------------------------------------------------
	static void scenarioReplayAndConditionalSkip() throws Exception {
		System.out.println("\n--- AP1: apply replay is conditional on the old initializer ---");
		String pkg = "ktap1";
		String v1Blocks = """
			package ktap1;
			public class Blocks {
			    public static final Turret duo;
			    public static final Turret duoEdited;
			    public static final Turret duoSame;
			    static {
			        Turret a = new Turret("duo");      a.health = 90; duo = a;
			        Turret b = new Turret("edited");   b.health = 90; duoEdited = b;
			        Turret c = new Turret("same");     c.health = 90; duoSame = c;
			    }
			}
			""";
		String v2Blocks = v1Blocks.replace("a.health = 90", "a.health = 100")
			.replace("b.health = 90", "b.health = 100");
		Fixture f = fixture(pkg,
			Map.of(pkg + ".Blocks", v1Blocks),
			Map.of(pkg + ".Blocks", v2Blocks));
		Class<?> blocks = f.load(pkg + ".Blocks");
		Class<?> turret = f.load(pkg + ".Turret");
		Object duo = getStatic(blocks, "duo");
		Object edited = getStatic(blocks, "duoEdited");
		Object same = getStatic(blocks, "duoSame");
		checkEq(90, getInt(turret, duo, "health"), "AP1 precondition: duo.health=90 (old initializer ran)");
		checkEq(90, getInt(turret, edited, "health"), "AP1 precondition: duoEdited.health=90");
		checkEq(90, getInt(turret, same, "health"), "AP1 precondition: duoSame.health=90");
		setInt(turret, edited, "health", 42);

		long driftBefore = countWarnings("[INIT-DRIFT]");
		int infoBefore = INFOS.size();
		System.setProperty(ApplyInitializerReplay.HOLDERS_PROPERTY, pkg + ".Blocks");
		try {
			f.hotswap(pkg + ".Blocks");
		} finally {
			System.clearProperty(ApplyInitializerReplay.HOLDERS_PROPERTY);
		}

		Object duoAfter = getStatic(blocks, "duo");
		InitFix.PatchReport report = InitFix.getLastReport(blocks);
		List<String> newInfos = INFOS.subList(Math.min(infoBefore, INFOS.size()), INFOS.size());
		System.out.println("   [OBS] duo.health=" + getInt(turret, duo, "health")
			+ ", edited.health=" + getInt(turret, edited, "health")
			+ ", same.health=" + getInt(turret, same, "health")
			+ ", sameObject=" + (duoAfter == duo)
			+ ", report=" + (report == null ? null : report.applyReplayFields())
			+ ", patch=" + (report != null && report.patchGenerated())
			+ ", ledger=" + InitFix.getUnpatchedFields(blocks));
		System.out.println("   [OBS] new INFOS: " + newInfos);

		checkEq(100, getInt(turret, duo, "health"), "AP1 old object is replayed 90 -> 100");
		check(duoAfter == duo, "AP1 replay writes the one live object read back through GETSTATIC dest");
		checkEq(42, getInt(turret, edited, "health"),
			"AP1 runtime-modified value (42) is not overwritten by the expected-CAS");
		checkEq(90, getInt(turret, same, "health"), "AP1 unchanged initializer is not replayed");
		check(report != null, "AP1 InitFix report exists (report=" + report + ")");
		check(report != null && report.applyReplayGenerated(), "AP1 an apply replay patch was generated");
		check(report != null && !report.patchGenerated(),
			"AP1 apply replay must not flip the ordinary patchGenerated flag");
		check(report != null && "replayed".equals(report.applyReplayFields().get("duo -> ktap1.Turret.health")),
			"AP1 report names duo -> ktap1.Turret.health as replayed (actual="
			+ (report == null ? null : report.applyReplayFields()) + ")");
		check(report != null && !report.applyReplayFields().containsKey("duoSame -> ktap1.Turret.health"),
			"AP1 unchanged field is not reported");
		check(InitFix.getUnpatchedFields(blocks).isEmpty(),
			"AP1 apply replay does not write FieldLedger (actual=" + InitFix.getUnpatchedFields(blocks) + ")");
		check(countWarnings("[INIT-DRIFT]") == driftBefore,
			"AP1 static holder produces no INIT-DRIFT warns (before=" + driftBefore
			+ ", after=" + countWarnings("[INIT-DRIFT]") + ")");
		check(newInfos.stream().anyMatch(s -> s.contains("[INIT-APPLY-REPLAY]")
			&& s.contains("wrote 1 apply-object field(s)") && s.contains("skipped 1")),
			"AP1 replay stats report one write and one expected-CAS skip: " + newInfos);
	}

	// ------------------------------------------------------------------------------------------------
	// A1b: byte/short/char target fields exercise the sub-word expected-CAS path (JDK 8 emulated CAS).
	// ------------------------------------------------------------------------------------------------
	static void scenarioSubWordFields() throws Exception {
		System.out.println("\n--- AP1b: byte/short/char fields use the sub-word expected-CAS path ---");
		String pkg = "ktap1b";
		String v1Blocks = """
			package ktap1b;
			public class Blocks {
			    public static final Turret duo;
			    static {
			        Turret t = new Turret("duo");
			        t.b = (byte) 7;
			        t.s = (short) 300;
			        t.c = 'A';
			        duo = t;
			    }
			}
			""";
		String v2Blocks = """
			package ktap1b;
			public class Blocks {
			    public static final Turret duo;
			    static {
			        Turret t = new Turret("duo");
			        t.b = (byte) 9;
			        t.s = (short) 400;
			        t.c = 'B';
			        duo = t;
			    }
			}
			""";
		String turret = """
			package ktap1b;
			public class Turret {
			    public int health = -1;
			    public byte b;
			    public short s;
			    public char c;
			    public Turret(String n) { }
			}
			""";
		Fixture f = fixtureWithTurret(pkg,
			Map.of(pkg + ".Blocks", v1Blocks), turret,
			Map.of(pkg + ".Blocks", v2Blocks), turret);
		Class<?> blocks = f.load(pkg + ".Blocks");
		Class<?> target = f.load(pkg + ".Turret");
		Object duo = getStatic(blocks, "duo");
		checkEq((byte) 7, getField(target, duo, "b", byte.class), "AP1b precondition: b=7");
		checkEq((short) 300, getField(target, duo, "s", short.class), "AP1b precondition: s=300");
		checkEq('A', getField(target, duo, "c", char.class), "AP1b precondition: c='A'");

		System.setProperty(ApplyInitializerReplay.HOLDERS_PROPERTY, pkg + ".Blocks");
		try {
			f.hotswap(pkg + ".Blocks");
		} finally {
			System.clearProperty(ApplyInitializerReplay.HOLDERS_PROPERTY);
		}
		InitFix.PatchReport report = InitFix.getLastReport(blocks);
		System.out.println("   [OBS] b=" + getField(target, duo, "b", byte.class)
			+ ", s=" + getField(target, duo, "s", short.class)
			+ ", c=" + getField(target, duo, "c", char.class)
			+ ", report=" + (report == null ? null : report.applyReplayFields()));
		checkEq((byte) 9, getField(target, duo, "b", byte.class), "AP1b byte field is replayed 7 -> 9");
		checkEq((short) 400, getField(target, duo, "s", short.class), "AP1b short field is replayed 300 -> 400");
		checkEq('B', getField(target, duo, "c", char.class), "AP1b char field is replayed 'A' -> 'B'");
		check(report != null && report.applyReplayFields().size() == 3,
			"AP1b all three sub-word fields are reported (actual="
			+ (report == null ? null : report.applyReplayFields()) + ")");
		check(InitFix.getUnpatchedFields(blocks).isEmpty(), "AP1b sub-word replay does not write FieldLedger");
	}

	// ------------------------------------------------------------------------------------------------
	// A2: same fixture, holder not whitelisted -> nothing may be replayed (去触发对照).
	// ------------------------------------------------------------------------------------------------
	static void scenarioNonWhitelisted() throws Exception {
		System.out.println("\n--- AP2: non-whitelisted holder keeps the old value ---");
		String pkg = "ktap2";
		String v1Blocks = """
			package ktap2;
			public class Blocks {
			    public static final Turret duo;
			    static { Turret t = new Turret("duo"); t.health = 90; duo = t; }
			}
			""";
		String v2Blocks = v1Blocks.replace("t.health = 90", "t.health = 100");
		Fixture f = fixture(pkg,
			Map.of(pkg + ".Blocks", v1Blocks),
			Map.of(pkg + ".Blocks", v2Blocks));
		Class<?> blocks = f.load(pkg + ".Blocks");
		Class<?> turret = f.load(pkg + ".Turret");
		Object duo = getStatic(blocks, "duo");
		checkEq(90, getInt(turret, duo, "health"), "AP2 precondition: duo.health=90");

		int infoBefore = INFOS.size();
		f.hotswap(pkg + ".Blocks");
		Object duoAfter = getStatic(blocks, "duo");
		InitFix.PatchReport report = InitFix.getLastReport(blocks);
		List<String> newInfos = INFOS.subList(Math.min(infoBefore, INFOS.size()), INFOS.size());
		checkEq(90, getInt(turret, duo, "health"), "AP2 without a whitelist the live object keeps 90");
		check(duoAfter == duo, "AP2 no object replacement happens without a whitelist");
		check(report == null || !report.applyReplayGenerated(),
			"AP2 no apply replay patch is generated (report=" + report + ")");
		check(newInfos.stream().noneMatch(s -> s.contains("[INIT-APPLY-REPLAY]")),
			"AP2 no apply replay runs without a whitelist: " + newInfos);
	}

	// ------------------------------------------------------------------------------------------------
	// A3: dest field is null at patch time -> warn + skip, never a memory-unsafe write.
	// ------------------------------------------------------------------------------------------------
	static void scenarioNullDestination() throws Exception {
		System.out.println("\n--- AP3: null destination is refused with a warn ---");
		String pkg = "ktap3";
		String v1Blocks = """
			package ktap3;
			public class Blocks {
			    public static Turret duo;
			    static { Turret t = new Turret("duo"); t.health = 90; duo = t; }
			}
			""";
		String v2Blocks = v1Blocks.replace("t.health = 90", "t.health = 100");
		Fixture f = fixture(pkg,
			Map.of(pkg + ".Blocks", v1Blocks),
			Map.of(pkg + ".Blocks", v2Blocks));
		Class<?> blocks = f.load(pkg + ".Blocks");
		Class<?> turret = f.load(pkg + ".Turret");
		Object old = getStatic(blocks, "duo");
		checkEq(90, getInt(turret, old, "health"), "AP3 precondition: duo.health=90");
		setStatic(blocks, "duo", null);

		long warnBefore = countWarnings("[INIT-APPLY-REPLAY]");
		System.setProperty(ApplyInitializerReplay.HOLDERS_PROPERTY, pkg + ".Blocks");
		try {
			f.hotswap(pkg + ".Blocks");
		} finally {
			System.clearProperty(ApplyInitializerReplay.HOLDERS_PROPERTY);
		}
		checkEq(null, getStatic(blocks, "duo"), "AP3 null destination stays null");
		checkEq(90, getInt(turret, old, "health"),
			"AP3 the detached old object is not written (no provenance through the null dest)");
		check(countWarnings("[INIT-APPLY-REPLAY]") > warnBefore,
			"AP3 a null destination is refused with an INIT-APPLY-REPLAY warn");
		check(WARNINGS.stream().anyMatch(w -> w.contains("[INIT-APPLY-REPLAY]")
			&& w.contains("is null")),
			"AP3 the warn names the null destination: " + WARNINGS);
		check(InitFix.getUnpatchedFields(blocks).isEmpty(),
			"AP3 a refused apply destination does not write FieldLedger");
	}

	// ==================== fixture helpers ====================

	static int countWarnings(String prefix) {
		int n = 0;
		for (String w : WARNINGS) if (w.contains(prefix)) n++;
		return n;
	}

	static final class Fixture {
		final Map<String, byte[]> v2;
		final FixtureLoader loader;
		final Map<String, Path> paths = new java.util.HashMap<>();

		Fixture(Map<String, byte[]> v2, FixtureLoader loader) {
			this.v2 = v2;
			this.loader = loader;
		}

		Class<?> load(String name) throws Exception {
			return Class.forName(name, true, loader);
		}

		void hotswap(String... classNames) throws Exception {
			Set<Path> changed = new LinkedHashSet<>();
			for (String name : classNames) {
				Path p = paths.get(name);
				if (p == null) throw new IllegalArgumentException("no path for " + name);
				changed.add(p);
			}
			Method process = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
			process.setAccessible(true);
			process.invoke(null, changed, inst.getAllLoadedClasses());
		}
	}

	static Fixture fixture(String pkg, Map<String, String> v1Sources, Map<String, String> v2Sources)
	 throws Exception {
		String turret = "package " + pkg + "; public class Turret { public int health = -1; public Turret(String n) { } }";
		return fixtureWithTurret(pkg, v1Sources, turret, v2Sources, turret);
	}

	static Fixture fixtureWithTurret(String pkg, Map<String, String> v1Sources, String turretV1,
	                                 Map<String, String> v2Sources, String turretV2) throws Exception {
		Map<String, String> withTurretV1 = new java.util.LinkedHashMap<>(v1Sources);
		Map<String, String> withTurretV2 = new java.util.LinkedHashMap<>(v2Sources);
		withTurretV1.putIfAbsent(pkg + ".Turret", turretV1);
		withTurretV2.putIfAbsent(pkg + ".Turret", turretV2);

		Map<String, byte[]> b1 = LayoutGateAssert.compile(withTurretV1);
		Map<String, byte[]> b2 = LayoutGateAssert.compile(withTurretV2);
		FixtureLoader loader = new FixtureLoader(b1);
		for (Map.Entry<String, byte[]> e : b1.entrySet()) {
			RemoldAgent.bytecodeCache.put(e.getKey(), e.getValue());
			RemoldAgent.sourceBytecodeCache.put(e.getKey(), e.getValue());
		}
		Path dir = Files.createTempDirectory("kotlin_apply");
		Fixture f = new Fixture(b2, loader);
		for (Map.Entry<String, byte[]> e : b2.entrySet()) {
			Path p = dir.resolve(e.getKey().replace('.', '/') + ".class");
			Files.createDirectories(p.getParent());
			Files.write(p, e.getValue());
			f.paths.put(e.getKey(), p);
		}
		return f;
	}

	static final class FixtureLoader extends ClassLoader {
		final Map<String, byte[]> classes;

		FixtureLoader(Map<String, byte[]> classes) {
			super(KotlinApplyProbe.class.getClassLoader());
			this.classes = classes;
		}

		@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	static Object getStatic(Class<?> owner, String name) throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(null);
	}

	static void setStatic(Class<?> owner, String name, Object value) throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(null, value);
	}

	static Object getField(Class<?> declaring, Object target, String name, Class<?> type) throws Exception {
		Field field = declaring.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	static int getInt(Class<?> declaring, Object target, String name) throws Exception {
		Field field = declaring.getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(target);
	}

	static void setInt(Class<?> declaring, Object target, String name, int value) throws Exception {
		Field field = declaring.getDeclaredField(name);
		field.setAccessible(true);
		field.setInt(target, value);
	}
}
