import remold.LayoutGate;
import remold.Reflect;
import remold.RemoldAgent;
import remold.offset.OffsetScanner;
import remold.ref.InitFix;
import remold.util.LibTool;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Investigation-only probe for double-brace initialization (`new XXX() {{ ... }}`).
 *
 * <p>Each scenario uses a distinct package so static caches (bytecodeCache, fileDiskHashes,
 * InitFix PENDING/LEDGER) cannot leak between them. No production code is modified.</p>
 *
 * <p>Run via {@code ./gradlew :tests:doubleBraceProbe}.</p>
 */
public class DoubleBraceProbe {

	static int passed = 0;
	static int failed = 0;
	static Instrumentation inst;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static void knownBug(String msg) {
		System.out.println("   [KNOWN-BUG] " + msg);
	}

	static void checkEq(Object expected, Object actual, String msg) {
		boolean ok = expected == null ? actual == null : expected.equals(actual);
		check(ok, msg + " [expected=" + expected + ", actual=" + actual + "]");
	}

	public static void main(String[] args) throws Throwable {
		System.out.println("=== DoubleBraceProbe ===");
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
		try {
			if (!LibTool.initialized()) LibTool.init();
		} catch (Throwable t) {
			System.err.println("[FAIL] native libtool unavailable: " + t);
			System.exit(1);
		}

		scenarioA1();
		scenarioA2();
		scenarioB();
		scenarioC();
		scenarioD1();
		scenarioD1b();
		scenarioD2();
		scenarioD2b();
		scenarioB2();
		scenarioB2NoReinit();
		scenarioE1();
		scenarioE2();
		scenarioE2Own();
		scenarioE3();

		System.out.println("\n=== DOUBLE-BRACE PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	// ------------------------------------------------------------------------------------------------
	// A1: v2 adds a double-brace anonymous class where none existed.
	// ------------------------------------------------------------------------------------------------
	static void scenarioA1() throws Exception {
		System.out.println("\n--- A1: v2 adds new double-brace anonymous class ---");
		String pkg = "dba1";
		Map<String, String> v1 = Map.of(
			pkg + ".Host", """
				package dba1;
				public class Host {
				    public XXX make() { return null; }
				}
				""",
			pkg + ".XXX", """
				package dba1;
				public class XXX { public int property; }
				""");
		Map<String, String> v2 = Map.of(
			pkg + ".Host", """
				package dba1;
				public class Host {
				    public XXX make() { return new XXX() {{ property = 7; }}; }
				}
				""",
			pkg + ".XXX", """
				package dba1;
				public class XXX { public int property; }
				""");
		Fixture f = fixture(v1, v2);
		Object host = f.newHost();
		f.hotswapAll();
		Object anon = call0(host, "make");
		Class<?> xxx = Class.forName(pkg + ".XXX", true, f.loader);
		check(anon != null, "A1 make() returns a new anonymous instance");
		checkEq(pkg + ".Host$1", anon.getClass().getName(), "A1 new class is Host$1");
		check(anon.getClass().getSuperclass() == xxx, "A1 anonymous class extends XXX");
		checkEq(7, getInt(anon, xxx, "property"), "A1 double-brace initializer ran (property)");
	}

	// ------------------------------------------------------------------------------------------------
	// A2: v2 inserts a new double-brace anonymous class before an existing one.
	// ------------------------------------------------------------------------------------------------
	static void scenarioA2() throws Exception {
		System.out.println("\n--- A2: new double-brace anonymous class inserted before existing one ---");
		String pkg = "dba2";
		Map<String, String> v1 = Map.of(
			pkg + ".Host", """
				package dba2;
				public class Host {
				    public XXX[] make() {
				        return new XXX[]{ new XXX() {{ property = 1; }} };
				    }
				}
				""",
			pkg + ".XXX", """
				package dba2;
				public class XXX { public int property; }
				""");
		Map<String, String> v2 = Map.of(
			pkg + ".Host", """
				package dba2;
				public class Host {
				    public XXX[] make() {
				        return new XXX[]{
				            new XXX() {{ property = 99; }},
				            new XXX() {{ property = 1; }}
				        };
				    }
				}
				""",
			pkg + ".XXX", """
				package dba2;
				public class XXX { public int property; }
				""");
		Fixture f = fixture(v1, v2);
		Object host = f.newHost();
		Object oldAnon = ((Object[]) call0(host, "make"))[0];
		f.hotswapAll();
		Object[] arr = (Object[]) call0(host, "make");
		Class<?> xxx = Class.forName(pkg + ".XXX", true, f.loader);
		checkEq(2, arr.length, "A2 make() now returns two anonymous instances");
		checkEq(99, getInt(arr[0], xxx, "property"), "A2 inserted first instance has property=99");
		checkEq(1, getInt(arr[1], xxx, "property"), "A2 existing slot has property=1");
		check(arr[1].getClass() == oldAnon.getClass(),
			"A2 existing anonymous class kept its old Class (actual=" + arr[1].getClass().getName()
			+ ", old=" + oldAnon.getClass().getName() + ")");
		check(arr[0].getClass() != oldAnon.getClass(),
			"A2 inserted instance is a new Class (actual=" + arr[0].getClass().getName() + ")");
	}

	// ------------------------------------------------------------------------------------------------
	// B: existing double-brace instance, initializer block value changed.
	// ------------------------------------------------------------------------------------------------
	static void scenarioB() throws Exception {
		System.out.println("\n--- B: existing instance, initializer block 1 -> 2 ---");
		String pkg = "dbb";
		Map<String, String> v1 = Map.of(
			pkg + ".Host", """
				package dbb;
				public class Host {
				    public XXX make() { return new XXX() {{ property = 1; }}; }
				}
				""",
			pkg + ".XXX", """
				package dbb;
				public class XXX { public int property; }
				""");
		Map<String, String> v2 = Map.of(
			pkg + ".Host", """
				package dbb;
				public class Host {
				    public XXX make() { return new XXX() {{ property = 2; }}; }
				}
				""",
			pkg + ".XXX", """
				package dbb;
				public class XXX { public int property; }
				""");
		Fixture f = fixture(v1, v2);
		Object host = f.newHost();
		Object old = call0(host, "make");
		Class<?> anon = old.getClass();
		Class<?> xxx = Class.forName(pkg + ".XXX", true, f.loader);
		checkEq(1, getInt(old, xxx, "property"), "B precondition: old instance property=1");

		f.hotswapAll();
		Object fresh = call0(host, "make");
		checkEq(1, getInt(old, xxx, "property"), "B old instance keeps property=1 (no constructor replay)");
		checkEq(2, getInt(fresh, xxx, "property"), "B new instance gets property=2");
		InitFix.PatchReport report = InitFix.getLastReport(anon);
		check(report == null || !report.patchGenerated(),
			"B InitFix did not generate a patch for the anonymous class (report=" + report + ")");
		check(InitFix.getUnpatchedFields(anon).isEmpty(),
			"B no FieldLedger entry (actual=" + InitFix.getUnpatchedFields(anon) + ")");
	}

	// ------------------------------------------------------------------------------------------------
	// B2: Mindustry-style parent field health, anonymous subclass initializer 90 -> 100;
	// parent field marked @RemoldReinit in v2.
	// ------------------------------------------------------------------------------------------------
	static void scenarioB2() throws Exception {
		System.out.println("--- B2: parent Block.health, two anonymous subclasses, parent @RemoldReinit ---");
		String pkg = "dbb2";
		Map<String, String> v1 = Map.of(
			pkg + ".Block", """
				package dbb2;
				public class Block { public int health = 1; }
				""",
			pkg + ".Host", """
				package dbb2;
				public class Host {
				    public Block makeA() { return new Block() {{ health = 90; }}; }
				    public Block makeB() { return new Block() {{ health = 91; }}; }
				    public Block makeC() { return new Block() {{ health = 50; }}; }
				}
				""");
		Map<String, String> v2 = Map.of(
			pkg + ".Block", """
				package dbb2;
				import remold.annotation.RemoldReinit;
				public class Block {
				    @RemoldReinit(mode = RemoldReinit.Mode.OVERWRITE)
				    public int health = 1;
				}
				""",
			pkg + ".Host", """
				package dbb2;
				public class Host {
				    public Block makeA() { return new Block() {{ health = 100; }}; }
				    public Block makeB() { return new Block() {{ health = 101; }}; }
				    public Block makeC() { return new Block() {{ health = 50; }}; }
				}
				""");
		Fixture f = fixture(v1, v2);
		Object host = f.newHost();
		Object oldA = call0(host, "makeA");
		Object oldB = call0(host, "makeB");
		Object oldC = call0(host, "makeC");
		Class<?> anonA = oldA.getClass();
		Class<?> anonB = oldB.getClass();
		Class<?> anonC = oldC.getClass();
		Class<?> block = Class.forName(pkg + ".Block", true, f.loader);
		checkEq(90, getInt(oldA, block, "health"), "B2 precondition: oldA.health=90");
		checkEq(91, getInt(oldB, block, "health"), "B2 precondition: oldB.health=91");
		checkEq(50, getInt(oldC, block, "health"), "B2 precondition: oldC.health=50 (unchanged initializer)");

		f.hotswapAll();
		// Measured after the patch, before any fresh instance exists: the patch saw only oldA/B/C.
		int blockInstancesAtPatch = LibTool.getInstances(block).length;
		Object freshA = call0(host, "makeA");
		Object freshB = call0(host, "makeB");
		Object freshC = call0(host, "makeC");
		int oldHealthA = getInt(oldA, block, "health");
		int oldHealthB = getInt(oldB, block, "health");
		int oldHealthC = getInt(oldC, block, "health");
		InitFix.PatchReport anonReportA = InitFix.getLastReport(anonA);
		InitFix.PatchReport anonReportB = InitFix.getLastReport(anonB);
		InitFix.PatchReport anonReportC = InitFix.getLastReport(anonC);
		InitFix.PatchReport blockReport = InitFix.getLastReport(block);
		int blockInstancesAfterFresh = LibTool.getInstances(block).length;
		System.out.println("   [OBS] B2 blockInstancesAtPatch=" + blockInstancesAtPatch
			+ ", blockInstancesAfterFresh=" + blockInstancesAfterFresh);
		System.out.println("   [OBS] B2 oldA.health=" + oldHealthA + ", oldB.health=" + oldHealthB
			+ ", oldC.health=" + oldHealthC
			+ ", newA.health=" + getInt(freshA, block, "health")
			+ ", newB.health=" + getInt(freshB, block, "health")
			+ ", newC.health=" + getInt(freshC, block, "health")
			+ ", anonPatchA=" + (anonReportA != null && anonReportA.patchGenerated())
			+ ", anonPatchB=" + (anonReportB != null && anonReportB.patchGenerated())
			+ ", anonPatchC=" + (anonReportC != null && anonReportC.patchGenerated())
			+ ", blockPatch=" + (blockReport != null && blockReport.patchGenerated())
			+ ", blockInstancesAtPatch=" + blockInstancesAtPatch
			+ ", anonLedgerA=" + InitFix.getUnpatchedFields(anonA)
			+ ", anonLedgerB=" + InitFix.getUnpatchedFields(anonB)
			+ ", anonLedgerC=" + InitFix.getUnpatchedFields(anonC)
			+ ", blockLedger=" + InitFix.getUnpatchedFields(block));
		System.out.println("   [OBS] B2 instances: oldA=" + System.identityHashCode(oldA)
			+ " (before), oldB=" + System.identityHashCode(oldB)
			+ " (before), oldC=" + System.identityHashCode(oldC)
			+ " (before), freshA=" + System.identityHashCode(freshA)
			+ " (after), freshB=" + System.identityHashCode(freshB)
			+ " (after), freshC=" + System.identityHashCode(freshC) + " (after)");

		checkEq(100, getInt(freshA, block, "health"), "B2 newA gets 100");
		checkEq(101, getInt(freshB, block, "health"), "B2 newB gets 101");
		checkEq(50, getInt(freshC, block, "health"), "B2 newC keeps 50 (unchanged initializer)");
		check(anonReportA == null || !anonReportA.patchGenerated(),
			"B2 anonymous class A has no InitFix patch (actual=" + anonReportA + ")");
		check(anonReportB == null || !anonReportB.patchGenerated(),
			"B2 anonymous class B has no InitFix patch (actual=" + anonReportB + ")");
		check(InitFix.getUnpatchedFields(anonA).isEmpty(), "B2 anon A ledger empty");
		check(InitFix.getUnpatchedFields(anonB).isEmpty(), "B2 anon B ledger empty");
		check(anonReportC == null || !anonReportC.patchGenerated(),
			"B2 anonymous class C (unchanged) has no InitFix patch (actual=" + anonReportC + ")");
		check(InitFix.getUnpatchedFields(anonC).isEmpty(), "B2 anon C ledger empty");
		check(blockReport != null && blockReport.patchGenerated(),
			"B2 parent Block has a patch (actual=" + blockReport + ")");
		checkEq(3, blockInstancesAtPatch, "B2 patch-time enumeration sees exactly 3 old instances");
		checkEq(6, blockInstancesAfterFresh, "B2 later query sees 6 after freshA/B/C are created");
		checkEq(1, oldHealthA, "B2 parent @RemoldReinit resets oldA to parent initializer 1");
		checkEq(1, oldHealthB, "B2 parent @RemoldReinit resets oldB to parent initializer 1");
		checkEq(1, oldHealthC, "B2 parent @RemoldReinit also resets UNCHANGED anon C (50 -> 1)");
	}

	// ------------------------------------------------------------------------------------------------
	// B2-no-reinit: same shape without @RemoldReinit; old instances must stay at 90/91.
	// ------------------------------------------------------------------------------------------------
	static void scenarioB2NoReinit() throws Exception {
		System.out.println("--- B2-no-reinit: parent Block.health without @RemoldReinit ---");
		String pkg = "dbb2n";
		Map<String, String> v1 = Map.of(
			pkg + ".Block", """
				package dbb2n;
				public class Block { public int health = 1; }
				""",
			pkg + ".Host", """
				package dbb2n;
				public class Host {
				    public Block makeA() { return new Block() {{ health = 90; }}; }
				    public Block makeB() { return new Block() {{ health = 91; }}; }
				}
				""");
		Map<String, String> v2 = Map.of(
			pkg + ".Block", """
				package dbb2n;
				public class Block { public int health = 1; }
				""",
			pkg + ".Host", """
				package dbb2n;
				public class Host {
				    public Block makeA() { return new Block() {{ health = 100; }}; }
				    public Block makeB() { return new Block() {{ health = 101; }}; }
				}
				""");
		Fixture f = fixture(v1, v2);
		Object host = f.newHost();
		Object oldA = call0(host, "makeA");
		Object oldB = call0(host, "makeB");
		Class<?> block = Class.forName(pkg + ".Block", true, f.loader);
		f.hotswapAll();
		// Measured after the patch, before any fresh instance exists: the patch saw only oldA/B/C.
		int blockInstancesAtPatch = LibTool.getInstances(block).length;
		Object freshA = call0(host, "makeA");
		int oldHealthA = getInt(oldA, block, "health");
		int oldHealthB = getInt(oldB, block, "health");
		InitFix.PatchReport anonReportA = InitFix.getLastReport(oldA.getClass());
		InitFix.PatchReport blockReport = InitFix.getLastReport(block);
		System.out.println("   [OBS] B2n oldA.health=" + oldHealthA + ", oldB.health=" + oldHealthB
			+ ", newA.health=" + getInt(freshA, block, "health")
			+ ", anonPatchA=" + (anonReportA != null && anonReportA.patchGenerated())
			+ ", blockPatch=" + (blockReport != null && blockReport.patchGenerated())
			+ ", anonLedgerA=" + InitFix.getUnpatchedFields(oldA.getClass())
			+ ", blockLedger=" + InitFix.getUnpatchedFields(block));
		checkEq(100, getInt(freshA, block, "health"), "B2n newA gets 100");
		checkEq(90, oldHealthA, "B2n oldA stays 90 without @RemoldReinit");
		checkEq(91, oldHealthB, "B2n oldB stays 91 without @RemoldReinit");
		check(anonReportA == null || !anonReportA.patchGenerated(), "B2n anon A has no patch");
		check(blockReport == null || !blockReport.patchGenerated(), "B2n parent Block has no patch");
	}

	// ------------------------------------------------------------------------------------------------
	// C: add a field inside the double-brace anonymous class.
	// ------------------------------------------------------------------------------------------------
	static void scenarioC() throws Exception {
		System.out.println("\n--- C: add field inside double-brace anonymous class ---");
		String pkg = "dbc";
		Map<String, String> v1 = Map.of(
			pkg + ".Host", """
				package dbc;
				public class Host {
				    public XXX make() { return new XXX() {{ property = 1; }}; }
				}
				""",
			pkg + ".XXX", """
				package dbc;
				public class XXX { public int property; }
				""");
		Map<String, String> v2 = Map.of(
			pkg + ".Host", """
				package dbc;
				public class Host {
				    public XXX make() {
				        return new XXX() {
				            { property = 1; }
				            int extra = 5;
				        };
				    }
				}
				""",
			pkg + ".XXX", """
				package dbc;
				public class XXX { public int property; }
				""");
		Fixture f = fixture(v1, v2);
		Object host = f.newHost();
		Object old = call0(host, "make");
		Class<?> anon = old.getClass();
		Class<?> xxx = Class.forName(pkg + ".XXX", true, f.loader);
		checkEq(1, getInt(old, xxx, "property"), "C precondition: old instance property=1");

		f.hotswapAll();
		Object fresh = call0(host, "make");
		checkEq(5, getInt(old, anon, "extra"), "C old anonymous instance received extra=5");
		checkEq(5, getInt(fresh, anon, "extra"), "C new anonymous instance has extra=5");
		InitFix.PatchReport report = InitFix.getLastReport(anon);
		check(report != null && report.patchGenerated(), "C InitFix generated a patch (report=" + report + ")");
		check(InitFix.getUnpatchedFields(anon).isEmpty(),
			"C no FieldLedger entry (actual=" + InitFix.getUnpatchedFields(anon) + ")");
	}

	// ------------------------------------------------------------------------------------------------
	// D1: added field initializer reads a newly added captured local (val$x).
	// ------------------------------------------------------------------------------------------------
	static void scenarioD1() throws Exception {
		System.out.println("\n--- D1: added field initializer reads newly added captured local (val$x) ---");
		String pkg = "dbd1";
		Map<String, String> v1 = Map.of(
			pkg + ".Host", """
				package dbd1;
				public class Host {
				    public XXX make(int seed) { return new XXX() {{ property = 1; }}; }
				}
				""",
			pkg + ".XXX", """
				package dbd1;
				public class XXX { public int property; }
				""");
		Map<String, String> v2 = Map.of(
			pkg + ".Host", """
				package dbd1;
				public class Host {
				    public XXX make(int seed) {
				        final int outer = seed;
				        return new XXX() {
				            int extra;
				            { property = 1; extra = 100 + outer; }
				        };
				    }
				}
				""",
			pkg + ".XXX", """
				package dbd1;
				public class XXX { public int property; }
				""");
		Fixture f = fixture(v1, v2);
		Object host = f.newHost();
		Object old = call1(host, "make", int.class, 42);
		Class<?> anon = old.getClass();
		Class<?> xxx = Class.forName(pkg + ".XXX", true, f.loader);
		checkEq(1, getInt(old, xxx, "property"), "D1 precondition: old instance property=1");

		f.hotswapAll();
		Object fresh = call1(host, "make", int.class, 42);
		boolean oldHasExtra = hasField(anon, "extra");
		int oldExtra = oldHasExtra ? getInt(old, anon, "extra") : -1;
		boolean freshHasExtra = hasField(fresh.getClass(), "extra");
		int freshExtra = freshHasExtra ? getInt(fresh, fresh.getClass(), "extra") : -1;
		InitFix.PatchReport report = InitFix.getLastReport(anon);
		System.out.println("   [OBS] D1 oldClass=" + anon.getName() + ", oldHasExtra=" + oldHasExtra
			+ ", old.extra=" + oldExtra + ", newClass=" + fresh.getClass().getName()
			+ ", new.extra=" + freshExtra
			+ ", patch=" + (report != null && report.patchGenerated())
			+ ", ledger=" + InitFix.getUnpatchedFields(anon));
		checkEq(142, freshExtra, "D1 new instance gets 100 + captured 42 = 142");
		check(!oldHasExtra || oldExtra == 100,
			"D1 old instance cannot recover the captured value; oldHasExtra=" + oldHasExtra
			+ ", old.extra=" + oldExtra);
	}

	// ------------------------------------------------------------------------------------------------
	// D1b: v1 already captures the local; v2 adds a field that reads the same captured value.
	// ------------------------------------------------------------------------------------------------
	static void scenarioD1b() throws Exception {
		System.out.println("\n--- D1b: v1 already captures val$x; v2 adds field reading it ---");
		String pkg = "dbd1b";
		Map<String, String> v1 = Map.of(
			pkg + ".Host", """
				package dbd1b;
				public class Host {
				    public XXX make(int seed) {
				        final int outer = seed;
				        return new XXX() {{ property = outer; }};
				    }
				}
				""",
			pkg + ".XXX", """
				package dbd1b;
				public class XXX { public int property; }
				""");
		Map<String, String> v2 = Map.of(
			pkg + ".Host", """
				package dbd1b;
				public class Host {
				    public XXX make(int seed) {
				        final int outer = seed;
				        return new XXX() {
				            int extra;
				            { property = outer; extra = 100 + outer; }
				        };
				    }
				}
				""",
			pkg + ".XXX", """
				package dbd1b;
				public class XXX { public int property; }
				""");
		Fixture f = fixture(v1, v2);
		Object host = f.newHost();
		Object old = call1(host, "make", int.class, 42);
		Class<?> anon = old.getClass();
		Class<?> xxx = Class.forName(pkg + ".XXX", true, f.loader);
		checkEq(42, getInt(old, xxx, "property"), "D1b precondition: old.property=42 (captured)");
		f.hotswapAll();
		Object fresh = call1(host, "make", int.class, 42);
		boolean oldHasExtra = hasField(anon, "extra");
		int oldExtra = oldHasExtra ? getInt(old, anon, "extra") : -1;
		boolean freshHasExtra = hasField(fresh.getClass(), "extra");
		int freshExtra = freshHasExtra ? getInt(fresh, fresh.getClass(), "extra") : -1;
		InitFix.PatchReport report = InitFix.getLastReport(anon);
		System.out.println("   [OBS] D1b oldHasExtra=" + oldHasExtra + ", old.extra=" + oldExtra
			+ ", new.extra=" + freshExtra
			+ ", patch=" + (report != null && report.patchGenerated())
			+ ", ledger=" + InitFix.getUnpatchedFields(anon));
		check(oldHasExtra && oldExtra == 142, "D1b old instance gets extra=142 from existing val$x");
		checkEq(142, freshExtra, "D1b new instance gets 142");
	}

	// ------------------------------------------------------------------------------------------------
	// D2: added field initializer reads a newly added outer this (this$0).
	// ------------------------------------------------------------------------------------------------
	static void scenarioD2() throws Exception {
		System.out.println("\n--- D2: added field initializer reads newly added outer this (this$0) ---");
		String pkg = "dbd2";
		Map<String, String> v1 = Map.of(
			pkg + ".Host", """
				package dbd2;
				public class Host {
				    public int value = 7;
				    public XXX make() { return new XXX() {{ property = 1; }}; }
				}
				""",
			pkg + ".XXX", """
				package dbd2;
				public class XXX { public int property; }
				""");
		Map<String, String> v2 = Map.of(
			pkg + ".Host", """
				package dbd2;
				public class Host {
				    public int value = 7;
				    public XXX make() {
				        return new XXX() {
				            int extra;
				            { property = 1; extra = 100 + Host.this.value; }
				        };
				    }
				}
				""",
			pkg + ".XXX", """
				package dbd2;
				public class XXX { public int property; }
				""");
		Fixture f = fixture(v1, v2);
		Object host = f.newHost();
		Object old = call0(host, "make");
		Class<?> anon = old.getClass();
		Class<?> xxx = Class.forName(pkg + ".XXX", true, f.loader);
		checkEq(1, getInt(old, xxx, "property"), "D2 precondition: old instance property=1");

		f.hotswapAll();
		Object fresh = call0(host, "make");
		boolean oldHasExtra = hasField(anon, "extra");
		int oldExtra = oldHasExtra ? getInt(old, anon, "extra") : -1;
		boolean freshHasExtra = hasField(fresh.getClass(), "extra");
		int freshExtra = freshHasExtra ? getInt(fresh, fresh.getClass(), "extra") : -1;
		InitFix.PatchReport report = InitFix.getLastReport(anon);
		System.out.println("   [OBS] D2 oldClass=" + anon.getName() + ", oldHasExtra=" + oldHasExtra
			+ ", old.extra=" + oldExtra + ", newClass=" + fresh.getClass().getName()
			+ ", new.extra=" + freshExtra
			+ ", patch=" + (report != null && report.patchGenerated())
			+ ", ledger=" + InitFix.getUnpatchedFields(anon));
		checkEq(107, freshExtra, "D2 new instance gets 100 + outer.value 7 = 107");
		check(!oldHasExtra || oldExtra == 100 || oldExtra == 107,
			"D2 old instance did not get a crash; oldHasExtra=" + oldHasExtra
			+ ", old.extra=" + oldExtra);
	}

	// ------------------------------------------------------------------------------------------------
	// D2b: v1 already uses outer this; v2 adds a field reading the same this$0.
	// ------------------------------------------------------------------------------------------------
	static void scenarioD2b() throws Exception {
		System.out.println("\n--- D2b: v1 already uses this$0; v2 adds field reading it ---");
		String pkg = "dbd2b";
		Map<String, String> v1 = Map.of(
			pkg + ".Host", """
				package dbd2b;
				public class Host {
				    public int value = 7;
				    public XXX make() { return new XXX() {{ property = Host.this.value; }}; }
				}
				""",
			pkg + ".XXX", """
				package dbd2b;
				public class XXX { public int property; }
				""");
		Map<String, String> v2 = Map.of(
			pkg + ".Host", """
				package dbd2b;
				public class Host {
				    public int value = 7;
				    public XXX make() {
				        return new XXX() {
				            int extra;
				            { property = Host.this.value; extra = 100 + Host.this.value; }
				        };
				    }
				}
				""",
			pkg + ".XXX", """
				package dbd2b;
				public class XXX { public int property; }
				""");
		Fixture f = fixture(v1, v2);
		Object host = f.newHost();
		Object old = call0(host, "make");
		Class<?> anon = old.getClass();
		Class<?> xxx = Class.forName(pkg + ".XXX", true, f.loader);
		checkEq(7, getInt(old, xxx, "property"), "D2b precondition: old.property=7 (outer this)");
		f.hotswapAll();
		Object fresh = call0(host, "make");
		boolean oldHasExtra = hasField(anon, "extra");
		int oldExtra = oldHasExtra ? getInt(old, anon, "extra") : -1;
		boolean freshHasExtra = hasField(fresh.getClass(), "extra");
		int freshExtra = freshHasExtra ? getInt(fresh, fresh.getClass(), "extra") : -1;
		InitFix.PatchReport report = InitFix.getLastReport(anon);
		System.out.println("   [OBS] D2b oldHasExtra=" + oldHasExtra + ", old.extra=" + oldExtra
			+ ", new.extra=" + freshExtra
			+ ", patch=" + (report != null && report.patchGenerated())
			+ ", ledger=" + InitFix.getUnpatchedFields(anon));
		check(oldHasExtra && oldExtra == 107, "D2b old instance gets extra=107 from existing this$0");
		checkEq(107, freshExtra, "D2b new instance gets 107");
	}

	// ------------------------------------------------------------------------------------------------
	// E1: superclass XXX gains a field; existing anonymous subclass instance.
	// ------------------------------------------------------------------------------------------------
	static void scenarioE1() throws Exception {
		System.out.println("\n--- E1: superclass XXX gains a field; existing Host$1 instance ---");
		String pkg = "dbe1";
		Map<String, String> v1 = Map.of(
			pkg + ".Host", """
				package dbe1;
				public class Host {
				    public XXX make() { return new XXX() {{ property = 1; }}; }
				}
				""",
			pkg + ".XXX", """
				package dbe1;
				public class XXX { public int base = 1; public int property; }
				""");
		Map<String, String> v2 = Map.of(
			pkg + ".Host", """
				package dbe1;
				public class Host {
				    public XXX make() { return new XXX() {{ property = 1; }}; }
				}
				""",
			pkg + ".XXX", """
				package dbe1;
				public class XXX { public int base = 1; public int added = 9; public int property; }
				""");
		Fixture f = fixture(v1, v2);
		Object host = f.newHost();
		Object old = call0(host, "make");
		Class<?> anon = old.getClass();
		Class<?> xxx = Class.forName(pkg + ".XXX", true, f.loader);
		checkEq(1, getInt(old, xxx, "base"), "E1 precondition: old.base=1");
		checkEq(1, getInt(old, xxx, "property"), "E1 precondition: old.property=1");

		f.hotswap(pkg + ".XXX");
		Field added = xxx.getDeclaredField("added");
		added.setAccessible(true);
		int oldAdded = added.getInt(old);
		InitFix.PatchReport report = InitFix.getLastReport(xxx);
		System.out.println("   [OBS] E1 old.added=" + oldAdded + ", patch=" + (report != null && report.patchGenerated())
			+ ", ledger=" + InitFix.getUnpatchedFields(xxx));
		checkEq(9, oldAdded, "E1 existing anonymous subclass instance received superclass field added=9");
		checkEq(1, getInt(old, xxx, "property"), "E1 existing anonymous field property survived");
	}

	// ------------------------------------------------------------------------------------------------
	// E2: binding on an INHERITED field of the anonymous subclass; measure the real offset drift.
	// ------------------------------------------------------------------------------------------------
	static void scenarioE2() throws Exception {
		System.out.println("\n--- E2: superclass XXX gains a field; binding on INHERITED Host$1.property ---");
		String pkg = "dbe2";
		Map<String, String> v1 = Map.of(
			pkg + ".Host", """
				package dbe2;
				public class Host {
				    public XXX make() { return new XXX() {{ property = 1; }}; }
				}
				""",
			pkg + ".XXX", """
				package dbe2;
				public class XXX { public int base = 1; public int property; }
				""");
		Map<String, String> v2 = Map.of(
			pkg + ".Host", """
				package dbe2;
				public class Host {
				    public XXX make() { return new XXX() {{ property = 1; }}; }
				}
				""",
			pkg + ".XXX", """
				package dbe2;
				public class XXX { public int base = 1; public int added = 9; public int property; }
				""");
		Fixture f = fixture(v1, v2);
		Object host = f.newHost();
		Object old = call0(host, "make");
		Class<?> anon = old.getClass();
		Class<?> xxx = Class.forName(pkg + ".XXX", true, f.loader);
		checkEq(1, getInt(old, xxx, "property"), "E2 precondition: old.property=1");

		Field propBefore = xxx.getDeclaredField("property");
		propBefore.setAccessible(true);
		long offsetBefore = Reflect.UNSAFE.objectFieldOffset(propBefore);

		RemoldAgent.registerOffsetBinding(new OffsetScanner.Binding(
			pkg.replace('.', '/') + "/Host$1", "property",
			OffsetScanner.Kind.INSTANCE_OFFSET, OffsetScanner.Sink.STATIC_FIELD,
			pkg.replace('.', '/') + "/Holder", "OFF", "probe", OffsetScanner.Confidence.EXACT));
		try {
			f.hotswap(pkg + ".XXX");
		} finally {
			RemoldAgent.clearOffsetIndex();
		}

		Field propAfter = xxx.getDeclaredField("property");
		propAfter.setAccessible(true);
		long offsetAfter = Reflect.UNSAFE.objectFieldOffset(propAfter);
		int viaNew = Reflect.UNSAFE.getInt(old, offsetAfter);
		int viaOld = Reflect.UNSAFE.getInt(old, offsetBefore);
		boolean addedExists;
		try {
			xxx.getDeclaredField("added");
			addedExists = true;
		} catch (NoSuchFieldException e) {
			addedExists = false;
		}
		System.out.println("   [OBS] E2 offsetBefore=" + offsetBefore + ", offsetAfter=" + offsetAfter
			+ ", viaNew=" + viaNew + ", viaOld=" + viaOld + ", addedExists=" + addedExists);
		knownBug("E2 binding on INHERITED field property was NOT detected (XXX was redefined)");
		checkEq(1, viaNew, "E2 new offset still reads property=1");
		if (offsetBefore != offsetAfter) {
			knownBug("E2 offset moved " + offsetBefore + " -> " + offsetAfter
				+ " and the old offset reads " + viaOld + " (not property=1)");
		} else {
			knownBug("E2 offset did not move; only a documentation correction would be needed");
		}
	}

	// ------------------------------------------------------------------------------------------------
	// E2-own control: binding on XXX's own declared field; the gate must reject.
	// ------------------------------------------------------------------------------------------------
	static void scenarioE2Own() throws Exception {
		System.out.println("--- E2-own: binding on XXX's own field property (control) ---");
		String pkg = "dbe2o";
		Map<String, String> v1 = Map.of(
			pkg + ".Host", """
				package dbe2o;
				public class Host {
				    public XXX make() { return new XXX() {{ property = 1; }}; }
				}
				""",
			pkg + ".XXX", """
				package dbe2o;
				public class XXX { public int base = 1; public int property; }
				""");
		Map<String, String> v2 = Map.of(
			pkg + ".Host", """
				package dbe2o;
				public class Host {
				    public XXX make() { return new XXX() {{ property = 1; }}; }
				}
				""",
			pkg + ".XXX", """
				package dbe2o;
				public class XXX { public int base = 1; public int added = 9; public int property; }
				""");
		Fixture f = fixture(v1, v2);
		Object host = f.newHost();
		Object old = call0(host, "make");
		Class<?> xxx = Class.forName(pkg + ".XXX", true, f.loader);
		checkEq(1, getInt(old, xxx, "property"), "E2-own precondition: old.property=1");

		RemoldAgent.registerOffsetBinding(new OffsetScanner.Binding(
			pkg.replace('.', '/') + "/XXX", "property",
			OffsetScanner.Kind.INSTANCE_OFFSET, OffsetScanner.Sink.STATIC_FIELD,
			pkg.replace('.', '/') + "/Holder", "OFF", "probe", OffsetScanner.Confidence.EXACT));
		try {
			f.hotswap(pkg + ".XXX");
		} finally {
			RemoldAgent.clearOffsetIndex();
		}
		boolean addedExists;
		try {
			xxx.getDeclaredField("added");
			addedExists = true;
		} catch (NoSuchFieldException e) {
			addedExists = false;
		}
		System.out.println("   [OBS] E2-own addedExists=" + addedExists
			+ ", old.property=" + getInt(old, xxx, "property"));
		check(!addedExists, "E2-own binding on XXX's own field caused SuperclassDriftGate to reject");
		checkEq(1, getInt(old, xxx, "property"), "E2-own existing instance kept property=1");
	}

	// ------------------------------------------------------------------------------------------------
	// E3: binding on a field DECLARED in the anonymous subclass; the gate should reject.
	// ------------------------------------------------------------------------------------------------
	static void scenarioE3() throws Exception {
		System.out.println("\n--- E3: superclass XXX gains a field; binding on Host$1 declared field ---");
		String pkg = "dbe3";
		Map<String, String> v1 = Map.of(
			pkg + ".Host", """
				package dbe3;
				public class Host {
				    public XXX make() {
				        return new XXX() { int own = 1; { property = 1; } };
				    }
				}
				""",
			pkg + ".XXX", """
				package dbe3;
				public class XXX { public int base = 1; public int property; }
				""");
		Map<String, String> v2 = Map.of(
			pkg + ".Host", """
				package dbe3;
				public class Host {
				    public XXX make() {
				        return new XXX() { int own = 1; { property = 1; } };
				    }
				}
				""",
			pkg + ".XXX", """
				package dbe3;
				public class XXX { public int base = 1; public int added = 9; public int property; }
				""");
		Fixture f = fixture(v1, v2);
		Object host = f.newHost();
		Object old = call0(host, "make");
		Class<?> anon = old.getClass();
		Class<?> xxx = Class.forName(pkg + ".XXX", true, f.loader);
		checkEq(1, getInt(old, anon, "own"), "E3 precondition: old.own=1");

		byte[] sourceBefore = RemoldAgent.sourceBytecodeCache.get(pkg + ".XXX");
		RemoldAgent.registerOffsetBinding(new OffsetScanner.Binding(
			pkg.replace('.', '/') + "/Host$1", "own",
			OffsetScanner.Kind.INSTANCE_OFFSET, OffsetScanner.Sink.STATIC_FIELD,
			pkg.replace('.', '/') + "/Holder", "OFF", "probe", OffsetScanner.Confidence.EXACT));
		try {
			f.hotswap(pkg + ".XXX");
		} finally {
			RemoldAgent.clearOffsetIndex();
		}
		boolean addedExists;
		try {
			xxx.getDeclaredField("added");
			addedExists = true;
		} catch (NoSuchFieldException e) {
			addedExists = false;
		}
		check(!addedExists, "E3 binding on DECLARED field own caused SuperclassDriftGate to reject");
		checkEq(1, getInt(old, anon, "own"), "E3 existing instance kept own=1");
		byte[] sourceAfter = RemoldAgent.sourceBytecodeCache.get(pkg + ".XXX");
		System.out.println("   [OBS] E3 sourceBefore=" + (sourceBefore == null ? "null" : sourceBefore.length)
			+ ", sourceAfter=" + (sourceAfter == null ? "null" : sourceAfter.length)
			+ ", equal=" + Arrays.equals(sourceBefore, sourceAfter));
		check(sourceBefore != null && Arrays.equals(sourceBefore, sourceAfter),
			"E3 rejected class keeps its previous sourceBytecodeCache baseline");
	}

	// ------------------------------------------------------------------------------------------------
	// Fixture helpers
	// ------------------------------------------------------------------------------------------------

	static final class Fixture {
		final Map<String, byte[]> v2;
		final FixtureLoader loader;
		final Map<String, Path> paths = new HashMap<>();

		Fixture(Map<String, byte[]> v2, FixtureLoader loader) {
			this.v2 = v2;
			this.loader = loader;
		}

		Object newHost() throws Exception {
			Class<?> host = Class.forName(loader.firstHost, true, loader);
			return host.getConstructor().newInstance();
		}

		void hotswapAll() throws Exception {
			hotswap(v2.keySet().toArray(new String[0]));
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

	static Fixture fixture(Map<String, String> v1Sources, Map<String, String> v2Sources) throws Exception {
		Map<String, byte[]> v1 = LayoutGateAssert.compile(v1Sources);
		Map<String, byte[]> v2 = LayoutGateAssert.compile(v2Sources);
		String firstHost = v1Sources.keySet().stream().filter(k -> k.endsWith(".Host")).findFirst().orElseThrow();
		FixtureLoader loader = new FixtureLoader(v1, firstHost);
		for (Map.Entry<String, byte[]> e : v1.entrySet()) {
			RemoldAgent.bytecodeCache.put(e.getKey(), e.getValue());
			RemoldAgent.sourceBytecodeCache.put(e.getKey(), e.getValue());
		}
		Path dir = Files.createTempDirectory("double_brace");
		Fixture f = new Fixture(v2, loader);
		for (Map.Entry<String, byte[]> e : v2.entrySet()) {
			Path p = dir.resolve(e.getKey().replace('.', '/') + ".class");
			Files.createDirectories(p.getParent());
			Files.write(p, e.getValue());
			f.paths.put(e.getKey(), p);
		}
		return f;
	}

	static final class FixtureLoader extends ClassLoader {
		final Map<String, byte[]> classes;
		final String firstHost;
		FixtureLoader(Map<String, byte[]> classes, String firstHost) {
			super(DoubleBraceProbe.class.getClassLoader());
			this.classes = classes;
			this.firstHost = firstHost;
		}
		@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	static Object call0(Object target, String name) throws Exception {
		return target.getClass().getMethod(name).invoke(target);
	}

	static Object call1(Object target, String name, Class<?> type, Object arg) throws Exception {
		return target.getClass().getMethod(name, type).invoke(target, arg);
	}

	static boolean hasField(Class<?> type, String name) {
		try { type.getDeclaredField(name); return true; }
		catch (NoSuchFieldException e) { return false; }
	}

	static int getInt(Object target, Class<?> declaring, String name) throws Exception {
		Field f = declaring.getDeclaredField(name);
		f.setAccessible(true);
		return f.getInt(target);
	}
}
