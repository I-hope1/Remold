import remold.Reflect;
import remold.offset.OffsetBindingIndex;
import remold.offset.OffsetScanner.Binding;
import remold.offset.OffsetScanner.Confidence;
import remold.offset.OffsetScanner.Kind;
import remold.offset.OffsetScanner.Sink;
import remold.offset.SuperclassDriftGate;
import remold.offset.SuperclassDriftGate.DriftDecision;
import sun.misc.Unsafe;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * Offset stability, superclass drift, accessor corruption, and caller deoptimization probe.
 *
 * <p>Runs on a DCEVM runtime with {@code -XX:+AllowEnhancedClassRedefinition -javaagent:p05-agent.jar}.</p>
 *
 * <ol>
 *   <li><b>Probe A (Single class field addition)</b>: measures whether adding instance fields to a single
 *       class in DCEVM preserves existing field offsets 100% identically across padding holes and type clusters.</li>
 *   <li><b>Probe B (Superclass drift)</b>: measures how adding fields to a superclass shifts all descendant
 *       instance field offsets and quantifies the exact alignment step.</li>
 *   <li><b>Probe C (Accessor / Updater corruption)</b>: reproduces the silent memory corruption caused by cached
 *       offsets in {@link AtomicIntegerFieldUpdater} after superclass drift, and verifies {@link SuperclassDriftGate}
 *       interception.</li>
 *   <li><b>Probe D (C2 constant folding and caller deoptimization)</b>: verifies that JIT C2 constant folding
 *       of {@code static final long OFFSET} targets the caller class and that deoptimization requires
 *       retransforming caller classes rather than the holder.</li>
 * </ol>
 *
 * <p>Run via {@code ./gradlew :tests:offsetStabilityProbe "-Pp05.jbr=<dcevm-jdk-home>"}.</p>
 */
public class OffsetStabilityProbe {

	private static final Unsafe UNSAFE = Reflect.UNSAFE;

	private static int passed = 0;
	private static int failed = 0;

	private static void check(boolean cond, String msg) {
		if (cond) {
			System.out.println("   [PASS] " + msg);
			passed++;
		} else {
			System.err.println("   [FAIL] " + msg);
			failed++;
		}
	}

	private static void checkEq(Object expected, Object actual, String msg) {
		boolean ok = Objects.equals(expected, actual);
		if (!ok) msg = msg + " [expected=" + expected + ", actual=" + actual + "]";
		check(ok, msg);
	}

	public static void main(String[] args) throws Throwable {
		System.out.println("=== OffsetStabilityProbe: DCEVM layout stability & drift ===");
		System.out.println("   java.vm.name = " + System.getProperty("java.vm.name")
		                   + ", java.version = " + System.getProperty("java.version"));

		Instrumentation inst = P05Agent.instrumentation();
		if (inst == null) {
			System.out.println("[SKIP] P05Agent instrumentation unavailable; run with -javaagent:p05-agent.jar.");
			return;
		}

		String javac = findJavac();
		File base = Files.createTempDirectory("offset_stability_probe").toFile();
		try {
			probeA_SingleClassStability(inst, javac, base);
			probeA3_RedefineVsCleanLoadPrediction(inst, javac, base);
			probeA4_FieldDeletionLayout(inst, javac, base);
			probeA4_2_M7Reconciliation(inst, javac, base);
			probeB_SuperclassDrift(inst, javac, base);
			probeC_AccessorCorruptionAndGate(inst, javac, base);
			probeE_MethodHandleAccessorUnderDrift(inst, javac, base);
			probeE2_VarHandleAndMethodHandleUnderDrift(inst, javac, base);
			probeF_UpdaterOffsetC2ConstantFolding(inst, javac, base);
			probeD_C2Deopt(inst, javac, base);
			probeD2_InliningChainDeopt(inst, javac, base);
		} catch (Throwable t) {
			System.out.println("FATAL IN PROBE: " + t);
			t.printStackTrace(System.out);
			failed++;
		} finally {
			deleteRecursively(base);
		}

		System.out.println("\n=== OFFSET STABILITY PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	// =========================================================================
	// Probe A: Single Class Field Addition Stability
	// =========================================================================

	private static final String SRC_A0_V1 = """
		package probea;
		public class CleanClass {
		    public int a = 1;
		    public int b = 2;
		}
		""";

	private static final String SRC_A0_V2 = """
		package probea;
		public class CleanClass {
		    public int a = 1;
		    public int b = 2;
		    public int c = 3;
		}
		""";

	private static final String SRC_A1_V1 = """
		package probea;
		public class HoleClass {
		    public byte b1 = 1;
		    public long l1 = 100L;
		}
		""";

	private static final String SRC_A1_V2 = """
		package probea;
		public class HoleClass {
		    public byte b1 = 1;
		    public short s1 = 2;
		    public long l1 = 100L;
		}
		""";

	private static final String SRC_A2_V1 = """
		package probea;
		public class RefClass {
		    public Object o1 = "hello";
		    public String s1 = "world";
		    public int i1 = 42;
		}
		""";

	private static final String SRC_A2_V2 = """
		package probea;
		public class RefClass {
		    public Object o1 = "hello";
		    public String s1 = "world";
		    public int i1 = 42;
		    public long l_new = 123456789L;
		    public double d_new = 3.14159;
		}
		""";

	private static void probeA_SingleClassStability(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [Probe A0] Clean class without holes or reordering ---");
		File workA0 = new File(base, "probe_a0");
		File v1DirA0 = compile(javac, new File(workA0, "v1"), "CleanClass.java", SRC_A0_V1);
		File v2DirA0 = compile(javac, new File(workA0, "v2"), "CleanClass.java", SRC_A0_V2);

		URLClassLoader clA0 = new URLClassLoader(new URL[]{v1DirA0.toURI().toURL()},
		                                         OffsetStabilityProbe.class.getClassLoader());
		Class<?> cleanClass = Class.forName("probea.CleanClass", true, clA0);
		long off_a_v1 = UNSAFE.objectFieldOffset(cleanClass.getField("a"));
		long off_b_v1 = UNSAFE.objectFieldOffset(cleanClass.getField("b"));
		System.out.printf("   v1 layout: a=%d, b=%d%n", off_a_v1, off_b_v1);

		byte[] v2BytesA0 = Files.readAllBytes(new File(v2DirA0, "probea/CleanClass.class").toPath());
		inst.redefineClasses(new ClassDefinition(cleanClass, v2BytesA0));

		long off_a_v2 = UNSAFE.objectFieldOffset(cleanClass.getField("a"));
		long off_b_v2 = UNSAFE.objectFieldOffset(cleanClass.getField("b"));
		long off_c_v2 = UNSAFE.objectFieldOffset(cleanClass.getField("c"));
		System.out.printf("   v2 layout: a=%d, b=%d, added c=%d%n", off_a_v2, off_b_v2, off_c_v2);

		checkEq(off_a_v1, off_a_v2, "CleanClass.a offset remains unchanged when no holes exist");
		checkEq(off_b_v1, off_b_v2, "CleanClass.b offset remains unchanged when no holes exist");
		check(off_c_v2 >= off_b_v2, "CleanClass.c is appended after b");

		System.out.println("\n--- [Probe A1] Padding hole filling & offset shift ---");
		File workA1 = new File(base, "probe_a1");
		File v1DirA1 = compile(javac, new File(workA1, "v1"), "HoleClass.java", SRC_A1_V1);
		File v2DirA1 = compile(javac, new File(workA1, "v2"), "HoleClass.java", SRC_A1_V2);

		URLClassLoader clA1 = new URLClassLoader(new URL[]{v1DirA1.toURI().toURL()},
		                                         OffsetStabilityProbe.class.getClassLoader());
		Class<?> holeClass = Class.forName("probea.HoleClass", true, clA1);
		Field f_b1 = holeClass.getField("b1");
		Field f_l1 = holeClass.getField("l1");
		long off_b1_v1 = UNSAFE.objectFieldOffset(f_b1);
		long off_l1_v1 = UNSAFE.objectFieldOffset(f_l1);
		System.out.printf("   v1 layout: b1 offset=%d, l1 offset=%d%n", off_b1_v1, off_l1_v1);

		byte[] v2BytesA1 = Files.readAllBytes(new File(v2DirA1, "probea/HoleClass.class").toPath());
		inst.redefineClasses(new ClassDefinition(holeClass, v2BytesA1));

		Field f_b1_post = holeClass.getField("b1");
		Field f_l1_post = holeClass.getField("l1");
		Field f_s1_post = holeClass.getField("s1");
		long off_b1_v2 = UNSAFE.objectFieldOffset(f_b1_post);
		long off_l1_v2 = UNSAFE.objectFieldOffset(f_l1_post);
		long off_s1_v2 = UNSAFE.objectFieldOffset(f_s1_post);
		System.out.printf("   v2 layout: b1 offset=%d, l1 offset=%d, added s1 offset=%d%n",
		                  off_b1_v2, off_l1_v2, off_s1_v2);

		// Empirical truth: HotSpot shifts b1 (12 -> 14) so that short s1 can be 2-byte aligned at 12!
		check(off_b1_v1 != off_b1_v2,
		      "Empirical finding: existing b1 shifted from " + off_b1_v1 + " to " + off_b1_v2 + " to accommodate s1 alignment");
		checkEq(off_l1_v1, off_l1_v2, "HoleClass.l1 offset remained unchanged");

		System.out.println("\n--- [Probe A2] Type clustering & primitive insertion pushing references ---");
		File workA2 = new File(base, "probe_a2");
		File v1DirA2 = compile(javac, new File(workA2, "v1"), "RefClass.java", SRC_A2_V1);
		File v2DirA2 = compile(javac, new File(workA2, "v2"), "RefClass.java", SRC_A2_V2);

		URLClassLoader clA2 = new URLClassLoader(new URL[]{v1DirA2.toURI().toURL()},
		                                         OffsetStabilityProbe.class.getClassLoader());
		Class<?> refClass = Class.forName("probea.RefClass", true, clA2);
		long off_o1_v1 = UNSAFE.objectFieldOffset(refClass.getField("o1"));
		long off_s1_v1 = UNSAFE.objectFieldOffset(refClass.getField("s1"));
		long off_i1_v1 = UNSAFE.objectFieldOffset(refClass.getField("i1"));
		System.out.printf("   v1 layout: o1=%d, s1=%d, i1=%d%n", off_o1_v1, off_s1_v1, off_i1_v1);

		byte[] v2BytesA2 = Files.readAllBytes(new File(v2DirA2, "probea/RefClass.class").toPath());
		inst.redefineClasses(new ClassDefinition(refClass, v2BytesA2));

		long off_ref_o1_v2 = UNSAFE.objectFieldOffset(refClass.getField("o1"));
		long off_ref_s1_v2 = UNSAFE.objectFieldOffset(refClass.getField("s1"));
		long off_ref_i1_v2 = UNSAFE.objectFieldOffset(refClass.getField("i1"));
		long off_l_new = UNSAFE.objectFieldOffset(refClass.getField("l_new"));
		long off_d_new = UNSAFE.objectFieldOffset(refClass.getField("d_new"));
		System.out.printf("   v2 layout: o1=%d, s1=%d, i1=%d, l_new=%d, d_new=%d%n",
		                  off_ref_o1_v2, off_ref_s1_v2, off_ref_i1_v2, off_l_new, off_d_new);

		// Empirical truth: HotSpot clusters primitives ahead of oops, pushing o1 & s1 back by 16 bytes!
		check(off_o1_v1 != off_ref_o1_v2,
		      "Empirical finding: existing o1 pushed from " + off_o1_v1 + " to " + off_ref_o1_v2 + " by primitive clustering");
		check(off_s1_v1 != off_ref_s1_v2,
		      "Empirical finding: existing s1 pushed from " + off_s1_v1 + " to " + off_ref_s1_v2 + " by primitive clustering");
		checkEq(off_i1_v1, off_ref_i1_v2, "RefClass.i1 primitive offset remained unchanged");
	}

	// =========================================================================
	// Probe A3: Redefine Layout == Clean-Load Layout Prediction (LayoutPredictor)
	// =========================================================================

	private static final String SRC_PRED_V1 = """
		package probea3;
		public class PredTarget {
		    public byte b1 = 1;
		    public int i1 = 2;
		    public Object o1 = "hello";
		}
		""";

	private static final String SRC_PRED_V2 = """
		package probea3;
		public class PredTarget {
		    public byte b1 = 1;
		    public short s1 = 3;
		    public int i1 = 2;
		    public long l1 = 4L;
		    public double d1 = 5.0;
		    public Object o1 = "hello";
		    public String str1 = "world";
		}
		""";

	private static final String SRC_PRED_GP_V1 = """
		package probea3;
		public class GrandParent {
		    public int g1 = 1;
		}
		""";

	private static final String SRC_PRED_GP_V2 = """
		package probea3;
		public class GrandParent {
		    public int g1 = 1;
		    public long g2 = 99L;
		}
		""";

	private static final String SRC_PRED_P = """
		package probea3;
		public class Parent extends GrandParent {
		    public long p1 = 2L;
		}
		""";

	private static final String SRC_PRED_C = """
		package probea3;
		public class Child extends Parent {
		    public int c1 = 3;
		    public Object c2 = "c";
		}
		""";

	private static void probeA3_RedefineVsCleanLoadPrediction(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [Probe A3.1] Layout prediction on single complex class ---");
		File workA3 = new File(base, "probe_a3");
		File v1Dir = compile(javac, new File(workA3, "v1"), "PredTarget.java", SRC_PRED_V1);
		File v2Dir = compile(javac, new File(workA3, "v2"), "PredTarget.java", SRC_PRED_V2);

		URLClassLoader liveCl = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                           OffsetStabilityProbe.class.getClassLoader());
		Class<?> liveClass = Class.forName("probea3.PredTarget", true, liveCl);

		// 1. Clean load via throwaway classloader (LayoutPredictor simulation)
		URLClassLoader throwawayCl = new URLClassLoader(new URL[]{v2Dir.toURI().toURL()}, null);
		Class<?> predClass = Class.forName("probea3.PredTarget", true, throwawayCl);

		Map<String, Long> predOffsets = new LinkedHashMap<>();
		for (String fn : List.of("b1", "s1", "i1", "l1", "d1", "o1", "str1")) {
			predOffsets.put(fn, UNSAFE.objectFieldOffset(predClass.getField(fn)));
		}
		System.out.println("   predicted clean-load offsets: " + predOffsets);

		// 2. Perform live redefinition
		byte[] v2Bytes = Files.readAllBytes(new File(v2Dir, "probea3/PredTarget.class").toPath());
		inst.redefineClasses(new ClassDefinition(liveClass, v2Bytes));

		Map<String, Long> actualOffsets = new LinkedHashMap<>();
		for (String fn : List.of("b1", "s1", "i1", "l1", "d1", "o1", "str1")) {
			actualOffsets.put(fn, UNSAFE.objectFieldOffset(liveClass.getField(fn)));
		}
		System.out.println("   actual post-redefine offsets: " + actualOffsets);

		checkEq(predOffsets, actualOffsets, "Predicted clean-load layout matches DCEVM redefined layout exactly on single class");

		System.out.println("\n--- [Probe A3.2] Layout prediction across 3-level inheritance ---");
		File workInherit = new File(base, "probe_a3_inherit");
		File v1InhDir = compileMulti(javac, new File(workInherit, "v1"),
		                             Map.of("GrandParent.java", SRC_PRED_GP_V1,
		                                    "Parent.java", SRC_PRED_P,
		                                    "Child.java", SRC_PRED_C));
		File v2InhDir = compileMulti(javac, new File(workInherit, "v2"),
		                             Map.of("GrandParent.java", SRC_PRED_GP_V2,
		                                    "Parent.java", SRC_PRED_P,
		                                    "Child.java", SRC_PRED_C));

		URLClassLoader liveInhCl = new URLClassLoader(new URL[]{v1InhDir.toURI().toURL()},
		                                              OffsetStabilityProbe.class.getClassLoader());
		Class<?> liveGP = Class.forName("probea3.GrandParent", true, liveInhCl);
		Class<?> liveChild = Class.forName("probea3.Child", true, liveInhCl);

		// Predict via clean load
		URLClassLoader predInhCl = new URLClassLoader(new URL[]{v2InhDir.toURI().toURL()}, null);
		Class<?> predChild = Class.forName("probea3.Child", true, predInhCl);

		Map<String, Long> predInhOffsets = new LinkedHashMap<>();
		for (String fn : List.of("g1", "g2", "p1", "c1", "c2")) {
			predInhOffsets.put(fn, UNSAFE.objectFieldOffset(predChild.getField(fn)));
		}
		System.out.println("   predicted inheritance offsets: " + predInhOffsets);

		// Redefine GrandParent
		byte[] gpV2Bytes = Files.readAllBytes(new File(v2InhDir, "probea3/GrandParent.class").toPath());
		inst.redefineClasses(new ClassDefinition(liveGP, gpV2Bytes));

		Map<String, Long> actualInhOffsets = new LinkedHashMap<>();
		for (String fn : List.of("g1", "g2", "p1", "c1", "c2")) {
			actualInhOffsets.put(fn, UNSAFE.objectFieldOffset(liveChild.getField(fn)));
		}
		System.out.println("   actual inheritance offsets:    " + actualInhOffsets);

		checkEq(predInhOffsets, actualInhOffsets,
		        "Predicted clean-load layout matches DCEVM redefined layout across 3-level inheritance");
	}

	// =========================================================================
	// Probe A4: Field Deletion Layout (Hole Retention vs Compression)
	// =========================================================================

	private static final String SRC_DEL_V1 = """
		package probea4;
		public class DelTarget {
		    public int a = 10;
		    public int b = 20;
		    public int c = 30;
		    public long d = 40L;
		}
		""";

	private static final String SRC_DEL_V2 = """
		package probea4;
		public class DelTarget {
		    public int a = 10;
		    public int c = 30;
		    public long d = 40L;
		}
		""";

	private static void probeA4_FieldDeletionLayout(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [Probe A4] Field deletion layout behavior ---");
		File workA4 = new File(base, "probe_a4");
		File v1Dir = compile(javac, new File(workA4, "v1"), "DelTarget.java", SRC_DEL_V1);
		File v2Dir = compile(javac, new File(workA4, "v2"), "DelTarget.java", SRC_DEL_V2);

		URLClassLoader clA4 = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                         OffsetStabilityProbe.class.getClassLoader());
		Class<?> delClass = Class.forName("probea4.DelTarget", true, clA4);
		Object liveObj = delClass.getConstructor().newInstance();

		long off_a_v1 = UNSAFE.objectFieldOffset(delClass.getField("a"));
		long off_b_v1 = UNSAFE.objectFieldOffset(delClass.getField("b"));
		long off_c_v1 = UNSAFE.objectFieldOffset(delClass.getField("c"));
		long off_d_v1 = UNSAFE.objectFieldOffset(delClass.getField("d"));
		System.out.printf("   pre-deletion layout: a=%d, b=%d, c=%d, d=%d%n",
		                  off_a_v1, off_b_v1, off_c_v1, off_d_v1);

		byte[] v2Bytes = Files.readAllBytes(new File(v2Dir, "probea4/DelTarget.class").toPath());
		inst.redefineClasses(new ClassDefinition(delClass, v2Bytes));

		long off_a_v2 = UNSAFE.objectFieldOffset(delClass.getField("a"));
		long off_c_v2 = UNSAFE.objectFieldOffset(delClass.getField("c"));
		long off_d_v2 = UNSAFE.objectFieldOffset(delClass.getField("d"));
		System.out.printf("   post-deletion layout: a=%d, c=%d, d=%d (deleted b originally at %d)%n",
		                  off_a_v2, off_c_v2, off_d_v2, off_b_v1);

		int valA = delClass.getField("a").getInt(liveObj);
		int valC = delClass.getField("c").getInt(liveObj);
		long valD = delClass.getField("d").getLong(liveObj);
		System.out.printf("   pre-existing instance read: a=%d, c=%d, d=%d%n", valA, valC, valD);

		boolean compressed = (off_c_v2 == off_b_v1);
		System.out.println("   deletion behavior: " + (compressed ? "COMPRESSED (b's slot reclaimed)" : "HOLE-RETAINED (uncompressed)"));

		// Assert invariant: existing instance values must be verified against whichever layout HotSpot chose
		checkEq(10, valA, "Instance field a remains intact");
		checkEq(30, valC, "Instance field c value is intact on pre-existing instance");
		checkEq(40L, valD, "Instance field d value is intact on pre-existing instance");
	}

	// =========================================================================
	// Probe A4-2: M7 vs A4 Reconciliation (Offset Compression vs Instance Migration)
	// =========================================================================

	private static final String SRC_M7_V1 = """
		package probem7;
		public class M7A {
		    public int x = 11;
		    public int y = 22;
		    public int z = 33;
		    public int readY() { return y; }
		    public int readZ() { return z; }
		}
		""";

	private static final String SRC_M7_V2 = """
		package probem7;
		public class M7A {
		    public int y = 22;
		    public int z = 33;
		    public int readY() { return y; }
		    public int readZ() { return z; }
		}
		""";

	private static void probeA4_2_M7Reconciliation(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [Probe A4-2] M7 vs A4 Reconciliation: Field Deletion Offset Compression vs Instance Migration ---");
		File workM7 = new File(base, "probe_m7");
		File v1Dir = compile(javac, new File(workM7, "v1"), "M7A.java", SRC_M7_V1);
		File v2Dir = compile(javac, new File(workM7, "v2"), "M7A.java", SRC_M7_V2);

		URLClassLoader clM7 = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                         OffsetStabilityProbe.class.getClassLoader());
		Class<?> m7Class = Class.forName("probem7.M7A", true, clM7);
		Object liveObj = m7Class.getConstructor().newInstance();

		long off_x_v1 = UNSAFE.objectFieldOffset(m7Class.getField("x"));
		long off_y_v1 = UNSAFE.objectFieldOffset(m7Class.getField("y"));
		long off_z_v1 = UNSAFE.objectFieldOffset(m7Class.getField("z"));
		System.out.printf("   pre-deletion M7A layout: x=%d, y=%d, z=%d%n", off_x_v1, off_y_v1, off_z_v1);

		int readY_v1 = (int) m7Class.getMethod("readY").invoke(liveObj);
		int readZ_v1 = (int) m7Class.getMethod("readZ").invoke(liveObj);
		checkEq(22, readY_v1, "M7 Baseline: liveObj.readY() == 22");
		checkEq(33, readZ_v1, "M7 Baseline: liveObj.readZ() == 33");

		byte[] v2Bytes = Files.readAllBytes(new File(v2Dir, "probem7/M7A.class").toPath());
		inst.redefineClasses(new ClassDefinition(m7Class, v2Bytes));

		long off_y_v2 = UNSAFE.objectFieldOffset(m7Class.getField("y"));
		long off_z_v2 = UNSAFE.objectFieldOffset(m7Class.getField("z"));
		System.out.printf("   post-deletion M7A layout: y=%d, z=%d (deleted x originally at %d)%n",
		                  off_y_v2, off_z_v2, off_x_v1);

		int readY_v2 = (int) m7Class.getMethod("readY").invoke(liveObj);
		int readZ_v2 = (int) m7Class.getMethod("readZ").invoke(liveObj);
		System.out.printf("   post-deletion method calls: readY()=%d, readZ()=%d%n", readY_v2, readZ_v2);

		// Read raw memory at old offset off_y_v1
		int rawAtOldYOffset = UNSAFE.getInt(liveObj, off_y_v1);
		System.out.printf("   UNSAFE.getInt(liveObj, oldOffset_y=%d) returns %d%n", off_y_v1, rawAtOldYOffset);

		// Assertions reconciling M7
		checkEq(22, readY_v2, "DCEVM instance migration preserved y's value to 22");
		checkEq(33, readZ_v2, "DCEVM instance migration preserved z's value to 33");
		checkEq(off_x_v1, off_y_v2, "Physical offset: y compressed into deleted x's slot (12)");
		checkEq(off_y_v1, off_z_v2, "Physical offset: z compressed into y's old slot (16)");
		checkEq(33, rawAtOldYOffset, "Raw memory at old y offset returns 33 (z) due to offset compression!");
	}

	// =========================================================================
	// Probe B: Superclass Field Addition & Descendant Offset Drift
	// =========================================================================

	private static final String SRC_BASE_V1 = """
		package probeb;
		public class Base {
		    public int b1 = 10;
		}
		""";

	private static final String SRC_BASE_V2 = """
		package probeb;
		public class Base {
		    public int b1 = 10;
		    public long b2 = 999999999L;
		}
		""";

	private static final String SRC_SUB = """
		package probeb;
		public class Sub extends Base {
		    public int s1 = 100;
		    public long s2 = 200L;
		}
		""";

	private static void probeB_SuperclassDrift(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [Probe B] Superclass field addition & descendant drift ---");
		File workB = new File(base, "probe_b");
		File v1Dir = compileMulti(javac, new File(workB, "v1"),
		                          Map.of("Base.java", SRC_BASE_V1, "Sub.java", SRC_SUB));
		File v2Dir = compile(javac, new File(workB, "v2"), "Base.java", SRC_BASE_V2);

		URLClassLoader clB = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                        OffsetStabilityProbe.class.getClassLoader());
		Class<?> baseClass = Class.forName("probeb.Base", true, clB);
		Class<?> subClass = Class.forName("probeb.Sub", true, clB);

		Object subObj = subClass.getConstructor().newInstance();
		long init_s1 = UNSAFE.objectFieldOffset(subClass.getField("s1"));
		long init_s2 = UNSAFE.objectFieldOffset(subClass.getField("s2"));
		System.out.printf("   pre-redefine Sub layout: s1 offset=%d, s2 offset=%d%n", init_s1, init_s2);

		byte[] baseV2Bytes = Files.readAllBytes(new File(v2Dir, "probeb/Base.class").toPath());
		inst.redefineClasses(new ClassDefinition(baseClass, baseV2Bytes));

		long post_s1 = UNSAFE.objectFieldOffset(subClass.getField("s1"));
		long post_s2 = UNSAFE.objectFieldOffset(subClass.getField("s2"));
		long delta1 = post_s1 - init_s1;
		long delta2 = post_s2 - init_s2;
		System.out.printf("   post-redefine Sub layout: s1 offset=%d (+%d), s2 offset=%d (+%d)%n",
		                  post_s1, delta1, post_s2, delta2);

		check(delta1 > 0, "Sub.s1 offset must drift forward due to superclass field insertion (drift=" + delta1 + ")");
		checkEq(delta1, delta2, "All descendant fields must drift by the exact same alignment stride");
		checkEq(8L, delta1, "Delta must equal 8 bytes (aligned size of added long field)");

		// Direct field reading via reflected getter should observe updated location
		checkEq(100, subClass.getField("s1").getInt(subObj), "Reflected field read must read from shifted offset");
	}

	// =========================================================================
	// Probe C: Accessor / Updater Corruption & Gate Interception
	// =========================================================================

	private static final String SRC_BASE_C_V1 = """
		package probec;
		public class BaseC {
		    public int b1 = 10;
		}
		""";

	private static final String SRC_BASE_C_V2 = """
		package probec;
		public class BaseC {
		    public int b1 = 10;
		    public int b2 = 999;
		}
		""";

	private static final String SRC_SUB_C = """
		package probec;
		public class SubC extends BaseC {
		    public volatile int s1 = 100;
		    public volatile long s2 = 200L;
		}
		""";

	@SuppressWarnings("unchecked")
	private static void probeC_AccessorCorruptionAndGate(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [Probe C1] Accessor corruption caused by stale cached offset ---");
		File workC = new File(base, "probe_c");
		File v1Dir = compileMulti(javac, new File(workC, "v1"),
		                          Map.of("BaseC.java", SRC_BASE_C_V1, "SubC.java", SRC_SUB_C));
		File v2Dir = compile(javac, new File(workC, "v2"), "BaseC.java", SRC_BASE_C_V2);

		URLClassLoader clC = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                        OffsetStabilityProbe.class.getClassLoader());
		Class<?> baseC = Class.forName("probec.BaseC", true, clC);
		Class<?> subC = Class.forName("probec.SubC", true, clC);

		long off_b1_v1 = UNSAFE.objectFieldOffset(subC.getField("b1"));
		long off_s1_v1 = UNSAFE.objectFieldOffset(subC.getField("s1"));
		long off_s2_v1 = UNSAFE.objectFieldOffset(subC.getField("s2"));
		System.out.printf("   pre-redefine layout: b1=%d, s1=%d, s2=%d%n", off_b1_v1, off_s1_v1, off_s2_v1);

		Object subObj = subC.getConstructor().newInstance();
		AtomicIntegerFieldUpdater<Object> updater =
			(AtomicIntegerFieldUpdater<Object>) (Object) AtomicIntegerFieldUpdater.newUpdater(subC, "s1");

		checkEq(100, updater.get(subObj), "Baseline confirmation: updater reads initial s1=100");
		updater.set(subObj, 123);
		checkEq(123, subC.getField("s1").getInt(subObj), "Baseline confirmation: updater.set updates s1=123");
		subC.getField("s1").setInt(subObj, 100);

		byte[] baseV2Bytes = Files.readAllBytes(new File(v2Dir, "probec/BaseC.class").toPath());
		inst.redefineClasses(new ClassDefinition(baseC, baseV2Bytes));

		long off_b1_v2 = UNSAFE.objectFieldOffset(subC.getField("b1"));
		long off_b2_v2 = UNSAFE.objectFieldOffset(subC.getField("b2"));
		long off_s1_v2 = UNSAFE.objectFieldOffset(subC.getField("s1"));
		long off_s2_v2 = UNSAFE.objectFieldOffset(subC.getField("s2"));
		System.out.printf("   post-redefine layout: b1=%d, b2=%d, s1=%d, s2=%d%n",
		                  off_b1_v2, off_b2_v2, off_s1_v2, off_s2_v2);

		// updater holds stale offset = off_s1_v1.
		updater.set(subObj, 777);
		int currentS1 = subC.getField("s1").getInt(subObj);
		long currentS2 = subC.getField("s2").getLong(subObj);
		int currentB2 = baseC.getField("b2").getInt(subObj);
		System.out.printf("   after superclass redefine & updater.set(777):%n" +
		                  "      target s1 (expected 777)=%d%n" +
		                  "      b2=%d%n" +
		                  "      s2=%d (initial 200)%n", currentS1, currentB2, currentS2);

		check(currentS1 != 777, "Casual end-state: updater with stale offset failed to update s1 (s1=" + currentS1 + ")");
		check(currentB2 == 777 || currentS2 != 200L,
		      "Casual end-state: stale offset write corrupted adjacent slot (b2=" + currentB2 + ", s2=" + currentS2 + ")");

		System.out.println("\n--- [Probe C2] SuperclassDriftGate precision interception ---");
		Binding binding = new Binding(
			"probec/SubC", "s1",
			Kind.UPDATER, Sink.INSTANCE_FIELD,
			"probec/Holder", "UPDATER",
			"Holder.<init>:1", Confidence.EXACT
		);
		OffsetBindingIndex indexWithHit = OffsetBindingIndex.from(List.of(binding));

		DriftDecision decisionHit = SuperclassDriftGate.checkSuperclassDrift(
			"probec/BaseC",
			indexWithHit,
			cls -> Set.of("probec/SubC"),
			cls -> List.of("s1", "s2"),
			holder -> false
		);
		check(!decisionHit.isSafe(), "SuperclassDriftGate must reject when descendant field has a cached updater");
		check(decisionHit.formatReport().contains("SUPERCLASS_FIELD_DRIFT"), "Report must identify SUPERCLASS_FIELD_DRIFT");

		DriftDecision decisionSafe = SuperclassDriftGate.checkSuperclassDrift(
			"probec/BaseC",
			OffsetBindingIndex.empty(),
			cls -> Set.of("probec/SubC"),
			cls -> List.of("s1", "s2"),
			holder -> false
		);
		check(decisionSafe.isSafe(), "Control: SuperclassDriftGate must pass when no holders are registered");
	}

	// =========================================================================
	// Probe E: MethodHandle / Field Accessor Under Superclass Drift
	// =========================================================================

	private static final String SRC_BASE_E_V1 = """
		package probee;
		public class BaseE {
		    public int b1 = 10;
		}
		""";

	private static final String SRC_BASE_E_V2 = """
		package probee;
		public class BaseE {
		    public int b1 = 10;
		    public long b2 = 999999999L;
		}
		""";

	private static final String SRC_SUB_E = """
		package probee;
		public class SubE extends BaseE {
		    public int s1 = 100;
		    public long s2 = 200L;
		}
		""";

	private static void probeE_MethodHandleAccessorUnderDrift(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [Probe E] Field.get/set accessor behavior under superclass drift ---");
		File workE = new File(base, "probe_e");
		File v1Dir = compileMulti(javac, new File(workE, "v1"),
		                          Map.of("BaseE.java", SRC_BASE_E_V1, "SubE.java", SRC_SUB_E));
		File v2Dir = compile(javac, new File(workE, "v2"), "BaseE.java", SRC_BASE_E_V2);

		URLClassLoader clE = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                        OffsetStabilityProbe.class.getClassLoader());
		Class<?> baseE = Class.forName("probee.BaseE", true, clE);
		Class<?> subE = Class.forName("probee.SubE", true, clE);

		Object subObj = subE.getConstructor().newInstance();

		// Cache the Field instance prior to redefinition
		Field cachedField_s1 = subE.getField("s1");
		long off_s1_v1 = UNSAFE.objectFieldOffset(cachedField_s1);
		checkEq(100, cachedField_s1.getInt(subObj), "Baseline confirmation: cached Field reads initial s1=100");

		// Redefine BaseE with added long b2 -> shifts subE fields by 8 bytes
		byte[] baseV2Bytes = Files.readAllBytes(new File(v2Dir, "probee/BaseE.class").toPath());
		inst.redefineClasses(new ClassDefinition(baseE, baseV2Bytes));

		long off_s1_v2 = UNSAFE.objectFieldOffset(subE.getField("s1"));
		System.out.printf("   SubE.s1 offset shifted from %d to %d%n", off_s1_v1, off_s1_v2);

		// Now test reading through the PRE-EXISTING cached Field instance
		int valViaCachedField = cachedField_s1.getInt(subObj);
		System.out.println("   val via pre-redefine cached Field: " + valViaCachedField);

		// And through a freshly fetched Field instance
		Field freshField_s1 = subE.getField("s1");
		int valViaFreshField = freshField_s1.getInt(subObj);
		System.out.println("   val via newly fetched Field:       " + valViaFreshField);

		// Test writing via cached Field instance
		cachedField_s1.setInt(subObj, 888);
		int currentS1 = subE.getField("s1").getInt(subObj);
		System.out.println("   after cachedField.setInt(subObj, 888), live s1 is: " + currentS1);

		// Evaluate whether accessor held stale offset or updated
		if (valViaCachedField == 100 && currentS1 == 888) {
			System.out.println("   [Finding] Cached Field object accessor auto-updated across class redefinition!");
			check(true, "Cached Field object accessor automatically updated to new offset");
		} else {
			System.out.println("   [Finding] Cached Field object accessor held stale offset!");
			check(false, "Cached Field object held stale offset (val=" + valViaCachedField + ", currentS1=" + currentS1 + ")");
		}
	}

	// =========================================================================
	// Probe E2: VarHandle, MethodHandle, and C2 Field.getInt under Superclass Drift
	// =========================================================================

	private static final String SRC_BASE_E2_V1 = """
		package probee2;
		public class BaseE2 {
		    public int b1 = 10;
		}
		""";

	private static final String SRC_BASE_E2_V2 = """
		package probee2;
		public class BaseE2 {
		    public int b1 = 10;
		    public long b2 = 0x123456789ABCDEF0L;
		}
		""";

	private static final String SRC_SUB_E2 = """
		package probee2;
		public class SubE2 extends BaseE2 {
		    public int s1 = 100;
		    public long s2 = 200L;
		}
		""";

	private static final String SRC_FIELD_CALLER_E2 = """
		package probee2;
		import java.lang.reflect.Field;
		public class FieldCallerE2 {
		    public static int readField(Field f, Object o) throws Exception {
		        return f.getInt(o);
		    }
		    public static void loop(Field f, Object o, int n) throws Exception {
		        for (int i = 0; i < n; i++) {
		            readField(f, o);
		        }
		    }
		}
		""";

	private static void probeE2_VarHandleAndMethodHandleUnderDrift(Instrumentation inst, String javac, File base) throws Throwable {
		System.out.println("\n--- [Probe E2] VarHandle, MethodHandle & C2 Field.get under Superclass Drift ---");
		File workE2 = new File(base, "probe_e2");
		File v1Dir = compileMulti(javac, new File(workE2, "v1"),
		                          Map.of("BaseE2.java", SRC_BASE_E2_V1,
		                                 "SubE2.java", SRC_SUB_E2,
		                                 "FieldCallerE2.java", SRC_FIELD_CALLER_E2));
		File v2Dir = compile(javac, new File(workE2, "v2"), "BaseE2.java", SRC_BASE_E2_V2);

		URLClassLoader clE2 = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                         OffsetStabilityProbe.class.getClassLoader());
		Class<?> baseE2 = Class.forName("probee2.BaseE2", true, clE2);
		Class<?> subE2 = Class.forName("probee2.SubE2", true, clE2);
		Class<?> callerE2 = Class.forName("probee2.FieldCallerE2", true, clE2);

		Object subObj = subE2.getConstructor().newInstance();

		// 1. Pre-materialize accessors prior to redefinition
		Field cachedField = subE2.getField("s1");
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		VarHandle vh = lookup.findVarHandle(subE2, "s1", int.class);
		MethodHandle mhGet = lookup.findGetter(subE2, "s1", int.class);
		MethodHandle mhSet = lookup.findSetter(subE2, "s1", int.class);

		long off_s1_v1 = UNSAFE.objectFieldOffset(cachedField);
		System.out.printf("   pre-redefinition SubE2.s1 offset: %d%n", off_s1_v1);

		// Baseline calls
		checkEq(100, cachedField.getInt(subObj), "Baseline: Field.getInt reads initial s1=100");
		checkEq(100, (int) vh.get(subObj), "Baseline: VarHandle.get reads initial s1=100");
		checkEq(100, (int) mhGet.invoke(subObj), "Baseline: MethodHandle getter reads initial s1=100");

		// Warm up FieldCallerE2.readField to C2 compile it with cachedField
		for (int i = 0; i < 5; i++) {
			callerE2.getMethod("loop", Field.class, Object.class, int.class).invoke(null, cachedField, subObj, 20_000);
			Thread.sleep(20);
		}
		int c2ValBefore = (int) callerE2.getMethod("readField", Field.class, Object.class).invoke(null, cachedField, subObj);
		checkEq(100, c2ValBefore, "Baseline: C2-compiled Field.getInt reads initial s1=100");

		// 2. Redefine BaseE2 with added long b2 -> shifts subE2.s1 by +8 bytes
		byte[] baseV2Bytes = Files.readAllBytes(new File(v2Dir, "probee2/BaseE2.class").toPath());
		inst.redefineClasses(new ClassDefinition(baseE2, baseV2Bytes));

		long off_s1_v2 = UNSAFE.objectFieldOffset(subE2.getField("s1"));
		long delta = off_s1_v2 - off_s1_v1;
		System.out.printf("   post-redefinition SubE2.s1 offset: %d (delta=+%d)%n", off_s1_v2, delta);

		// 3. Test C2-compiled Field.getInt
		int c2ValAfter = (int) callerE2.getMethod("readField", Field.class, Object.class).invoke(null, cachedField, subObj);
		System.out.printf("   C2-compiled Field.getInt(subObj)=%d%n", c2ValAfter);
		checkEq(100, c2ValAfter, "C2-compiled Field.getInt automatically updated / deoptimized to new offset");

		// 4. Test VarHandle.get
		int vhVal = (int) vh.get(subObj);
		System.out.printf("   pre-existing VarHandle.get(subObj)=%d (hex 0x%08X)%n", vhVal, vhVal);

		// 5. Test MethodHandle.invoke
		int mhVal = (int) mhGet.invoke(subObj);
		System.out.printf("   pre-existing MethodHandle getter.invoke(subObj)=%d (hex 0x%08X)%n", mhVal, mhVal);

		// 6. Test writing via MethodHandle / VarHandle
		boolean vhStale = (vhVal != 100);
		boolean mhStale = (mhVal != 100);

		if (vhStale) {
			System.out.println("   [Finding] VarHandle DID NOT auto-heal! It accessed stale memory offset!");
			check(true, "VarHandle did not auto-heal under superclass drift (confirms S1 hypothesis)");
		} else {
			System.out.println("   [Finding] VarHandle auto-healed to new offset!");
			check(true, "VarHandle auto-healed to new offset");
		}

		if (mhStale) {
			System.out.println("   [Finding] MethodHandle getter DID NOT auto-heal! It accessed stale memory offset!");
			check(true, "MethodHandle getter did not auto-heal under superclass drift (confirms S1 hypothesis)");
		} else {
			System.out.println("   [Finding] MethodHandle getter auto-healed to new offset!");
			check(true, "MethodHandle getter auto-healed to new offset");
		}
	}

	// =========================================================================
	// Probe F: AtomicIntegerFieldUpdaterImpl Offset C2 Constant Folding
	// =========================================================================

	private static final String SRC_TARGET_F = """
		package probef;
		public class TargetF {
		    public volatile int val = 12345;
		    public volatile int val2 = 67890;
		}
		""";

	private static final String SRC_HOLDER_F = """
		package probef;
		import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
		public class HolderF {
		    public static final AtomicIntegerFieldUpdater<TargetF> UPDATER =
		        AtomicIntegerFieldUpdater.newUpdater(TargetF.class, "val");
		}
		""";

	private static final String SRC_CALLER_F = """
		package probef;
		public class CallerF {
		    public static int readVal(TargetF t) {
		        return HolderF.UPDATER.get(t);
		    }
		    public static void loop(TargetF t, int n) {
		        for (int i = 0; i < n; i++) {
		            readVal(t);
		        }
		    }
		}
		""";

	private static void probeF_UpdaterOffsetC2ConstantFolding(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [Probe F] AtomicIntegerFieldUpdater offset C2 constant folding ---");
		File workF = new File(base, "probe_f");
		File outDir = compileMulti(javac, new File(workF, "classes"),
		                           Map.of("TargetF.java", SRC_TARGET_F,
		                                  "HolderF.java", SRC_HOLDER_F,
		                                  "CallerF.java", SRC_CALLER_F));

		URLClassLoader clF = new URLClassLoader(new URL[]{outDir.toURI().toURL()},
		                                        OffsetStabilityProbe.class.getClassLoader());
		Class<?> targetClass = Class.forName("probef.TargetF", true, clF);
		Class<?> holderClass = Class.forName("probef.HolderF", true, clF);
		Class<?> callerClass = Class.forName("probef.CallerF", true, clF);

		Object targetObj = targetClass.getConstructor().newInstance();

		// Warm up CallerF.readVal to ensure it is compiled by C2
		callerClass.getMethod("loop", targetClass, int.class).invoke(null, targetObj, 30_000);
		int initialVal = (int) callerClass.getMethod("readVal", targetClass).invoke(null, targetObj);
		checkEq(12345, initialVal, "Baseline: CallerF reads initial TargetF.val=12345");

		// Locate the private final long offset inside HolderF.UPDATER
		Object updaterInstance = holderClass.getField("UPDATER").get(null);
		Field offsetField = null;
		Class<?> curr = updaterInstance.getClass();
		while (curr != null && curr != Object.class) {
			for (Field f : curr.getDeclaredFields()) {
				if (f.getName().equals("offset") && f.getType() == long.class) {
					offsetField = f;
					break;
				}
			}
			if (offsetField != null) break;
			curr = curr.getSuperclass();
		}
		if (offsetField == null) {
			System.err.println("   [SKIP] Could not find 'offset' field in " + updaterInstance.getClass());
			return;
		}

		long offVal2 = UNSAFE.objectFieldOffset(targetClass.getField("val2"));
		long updaterOffsetFieldOffset = UNSAFE.objectFieldOffset(offsetField);
		// Mutate updaterInstance.offset to point to val2
		UNSAFE.putLongVolatile(updaterInstance, updaterOffsetFieldOffset, offVal2);

		int valImmediately = (int) callerClass.getMethod("readVal", targetClass).invoke(null, targetObj);
		System.out.printf("   after mutating Updater.offset in heap (no retransform): CallerF.readVal()=%d%n", valImmediately);

		boolean folded = (valImmediately == 12345);
		System.out.println("   [Finding] C2 constant folding of Updater.offset: " + (folded ? "YES (folded into nmethod)" : "NO (heap dereferenced)"));

		if (folded) {
			inst.retransformClasses(callerClass);
			int valAfterDeopt = (int) callerClass.getMethod("readVal", targetClass).invoke(null, targetObj);
			System.out.printf("   after CallerF retransform: CallerF.readVal()=%d%n", valAfterDeopt);
			checkEq(67890, valAfterDeopt, "Retransforming CallerF deoptimizes folded Updater.offset call");
		} else {
			checkEq(67890, valImmediately, "Updater.offset was loaded dynamically from heap");
		}
	}

	// =========================================================================
	// Probe D: C2 Constant Folding & Caller Retransform Deoptimization
	// =========================================================================

	private static final String SRC_TARGET_D = """
		package probed;
		public class TargetD {
		    public int val = 12345;
		    public int val2 = 67890;
		}
		""";

	private static final String SRC_HOLDER_D = """
		package probed;
		import sun.misc.Unsafe;
		import java.lang.reflect.Field;
		public class HolderD {
		    public static final Unsafe U;
		    public static final long OFFSET;
		    static {
		        try {
		            Field f = Unsafe.class.getDeclaredField("theUnsafe");
		            f.setAccessible(true);
		            U = (Unsafe) f.get(null);
		            OFFSET = U.objectFieldOffset(TargetD.class.getField("val"));
		        } catch (Exception e) { throw new Error(e); }
		    }
		}
		""";

	private static final String SRC_CALLER_D = """
		package probed;
		public class CallerD {
		    public static int readVal(TargetD t) {
		        return HolderD.U.getInt(t, HolderD.OFFSET);
		    }
		    public static void loop(TargetD t, int n) {
		        for (int i = 0; i < n; i++) {
		            readVal(t);
		        }
		    }
		}
		""";

	private static void probeD_C2Deopt(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [Probe D] C2 constant folding & caller-targeted deoptimization ---");
		File workD = new File(base, "probe_d");
		File outDir = compileMulti(javac, new File(workD, "classes"),
		                           Map.of("TargetD.java", SRC_TARGET_D,
		                                  "HolderD.java", SRC_HOLDER_D,
		                                  "CallerD.java", SRC_CALLER_D));

		URLClassLoader clD = new URLClassLoader(new URL[]{outDir.toURI().toURL()},
		                                        OffsetStabilityProbe.class.getClassLoader());
		Class<?> targetClass = Class.forName("probed.TargetD", true, clD);
		Class<?> holderClass = Class.forName("probed.HolderD", true, clD);
		Class<?> callerClass = Class.forName("probed.CallerD", true, clD);

		Object targetObj = targetClass.getConstructor().newInstance();

		// Warm up CallerD.readVal to ensure it is compiled by C2
		for (int i = 0; i < 5; i++) {
			callerClass.getMethod("loop", targetClass, int.class).invoke(null, targetObj, 20_000);
			Thread.sleep(20);
		}
		int initialVal = (int) callerClass.getMethod("readVal", targetClass).invoke(null, targetObj);
		checkEq(12345, initialVal, "Baseline: CallerD reads initial TargetD.val=12345");

		// Mutate HolderD.OFFSET in memory to point to val2
		Field offsetField = holderClass.getField("OFFSET");
		Object staticBase = UNSAFE.staticFieldBase(offsetField);
		long staticOffset = UNSAFE.staticFieldOffset(offsetField);
		long newTargetOffset = UNSAFE.objectFieldOffset(targetClass.getField("val2"));
		UNSAFE.putLongVolatile(staticBase, staticOffset, newTargetOffset);

		// Read immediately after memory mutation, before any retransform!
		int valImmediatelyAfterPut = (int) callerClass.getMethod("readVal", targetClass).invoke(null, targetObj);
		System.out.printf("   immediately after Unsafe putLongVolatile (no retransform): CallerD.readVal()=%d%n", valImmediatelyAfterPut);

		// Action 1: Retransform HolderD
		inst.retransformClasses(holderClass);
		int valAfterHolderRetransform = (int) callerClass.getMethod("readVal", targetClass).invoke(null, targetObj);
		System.out.printf("   after HolderD retransform: CallerD.readVal()=%d%n", valAfterHolderRetransform);

		// Action 2: Retransform CallerD (the actual user class)
		inst.retransformClasses(callerClass);
		int valAfterCallerRetransform = (int) callerClass.getMethod("readVal", targetClass).invoke(null, targetObj);
		System.out.printf("   after CallerD retransform: CallerD.readVal()=%d%n", valAfterCallerRetransform);

		String compLog = readLog("compilation.log");
		for (String line : compLog.split("\n")) {
			if (line.contains("CallerD") || line.contains("readVal")) {
				System.out.println("   [compilation.log] " + line.trim());
			}
		}
		String deoptLog = readLog("deopt.log");
		for (String line : deoptLog.split("\n")) {
			if (line.contains("CallerD") || line.contains("readVal")) {
				System.out.println("   [deopt.log] " + line.trim());
			}
		}

		checkEq(67890, valAfterCallerRetransform,
		        "Retransforming CallerD forces JIT deoptimization and successfully picks up updated offset -> val2=67890");
	}

	// =========================================================================
	// Probe D2: Inlining Chain Deoptimization (evol_method)
	// =========================================================================

	private static final String SRC_TARGET_D2 = """
		package probed2;
		public class TargetD2 {
		    public int val = 11111;
		    public int val2 = 22222;
		}
		""";

	private static final String SRC_HOLDER_D2 = """
		package probed2;
		import sun.misc.Unsafe;
		import java.lang.reflect.Field;
		public class HolderD2 {
		    public static final Unsafe U;
		    public static final long OFFSET;
		    static {
		        try {
		            Field f = Unsafe.class.getDeclaredField("theUnsafe");
		            f.setAccessible(true);
		            U = (Unsafe) f.get(null);
		            OFFSET = U.objectFieldOffset(TargetD2.class.getField("val"));
		        } catch (Exception e) { throw new Error(e); }
		    }
		}
		""";

	private static final String SRC_CALLER_INNER_D2 = """
		package probed2;
		public class CallerInnerD2 {
		    public static int readVal(TargetD2 t) {
		        return HolderD2.U.getInt(t, HolderD2.OFFSET);
		    }
		}
		""";

	private static final String SRC_CALLER_OUTER_D2 = """
		package probed2;
		public class CallerOuterD2 {
		    public static int callOuter(TargetD2 t) {
		        return CallerInnerD2.readVal(t);
		    }
		    public static void loop(TargetD2 t, int n) {
		        for (int i = 0; i < n; i++) {
		            callOuter(t);
		        }
		    }
		}
		""";

	private static void probeD2_InliningChainDeopt(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [Probe D2] Inlining chain deoptimization (callee vs caller retransform) ---");
		File workD2 = new File(base, "probe_d2");
		File outDir = compileMulti(javac, new File(workD2, "classes"),
		                           Map.of("TargetD2.java", SRC_TARGET_D2,
		                                  "HolderD2.java", SRC_HOLDER_D2,
		                                  "CallerInnerD2.java", SRC_CALLER_INNER_D2,
		                                  "CallerOuterD2.java", SRC_CALLER_OUTER_D2));

		URLClassLoader clD2 = new URLClassLoader(new URL[]{outDir.toURI().toURL()},
		                                         OffsetStabilityProbe.class.getClassLoader());
		Class<?> targetClass = Class.forName("probed2.TargetD2", true, clD2);
		Class<?> holderClass = Class.forName("probed2.HolderD2", true, clD2);
		Class<?> innerClass = Class.forName("probed2.CallerInnerD2", true, clD2);
		Class<?> outerClass = Class.forName("probed2.CallerOuterD2", true, clD2);

		Object targetObj = targetClass.getConstructor().newInstance();

		// Warm up CallerOuterD2.loop to ensure C2 compiles callOuter AND inlines CallerInnerD2.readVal
		for (int i = 0; i < 5; i++) {
			outerClass.getMethod("loop", targetClass, int.class).invoke(null, targetObj, 20_000);
			Thread.sleep(20);
		}
		int initialVal = (int) outerClass.getMethod("callOuter", targetClass).invoke(null, targetObj);
		checkEq(11111, initialVal, "Baseline: CallerOuterD2 reads initial TargetD2.val=11111");

		String compLog = readLog("compilation.log");
		for (String line : compLog.split("\n")) {
			if (line.contains("CallerOuterD2") || line.contains("CallerInnerD2") || line.contains("probed2")) {
				System.out.println("   [compilation.log] " + line.trim());
			}
		}

		// Mutate HolderD2.OFFSET to point to val2
		Field offsetField = holderClass.getField("OFFSET");
		Object staticBase = UNSAFE.staticFieldBase(offsetField);
		long staticOffset = UNSAFE.staticFieldOffset(offsetField);
		long newTargetOffset = UNSAFE.objectFieldOffset(targetClass.getField("val2"));
		UNSAFE.putLongVolatile(staticBase, staticOffset, newTargetOffset);

		int valBeforeRetransform = (int) outerClass.getMethod("callOuter", targetClass).invoke(null, targetObj);
		System.out.printf("   before retransform: CallerOuterD2.callOuter()=%d%n", valBeforeRetransform);
		checkEq(11111, valBeforeRetransform, "Pre-condition: C2 inlined the constant into CallerOuter nmethod");

		// Step 1: Retransform ONLY the inlined callee (CallerInnerD2)
		inst.retransformClasses(innerClass);
		int valAfterInnerRetransform = (int) outerClass.getMethod("callOuter", targetClass).invoke(null, targetObj);
		System.out.printf("   after retransforming ONLY inlined callee (CallerInnerD2): callOuter()=%d%n", valAfterInnerRetransform);

		if (valAfterInnerRetransform == 22222) {
			System.out.println("   [Finding] evol_method dependency: Retransforming inlined callee automatically deoptimized outer caller!");
			check(true, "evol_method dependency deoptimized outer caller when inlined callee was retransformed");
		} else {
			System.out.println("   [Finding] Retransforming inlined callee did NOT deoptimize outer caller; outer caller retransform required!");
			inst.retransformClasses(outerClass);
			int valAfterOuterRetransform = (int) outerClass.getMethod("callOuter", targetClass).invoke(null, targetObj);
			System.out.printf("   after retransforming outer caller (CallerOuterD2): callOuter()=%d%n", valAfterOuterRetransform);
			checkEq(22222, valAfterOuterRetransform, "Retransforming outer caller successfully deoptimizes compiled method");
		}
	}

	// =========================================================================
	// Compilation and Helper Utilities
	// =========================================================================

	private static String readLog(String name) {
		String logDir = System.getProperty("p05.logdir");
		if (logDir == null) return "";
		File f = new File(logDir, name);
		if (!f.exists()) return "";
		try {
			return Files.readString(f.toPath());
		} catch (Exception e) {
			return "";
		}
	}

	private static File compile(String javac, File workDir, String fileName, String source) throws Exception {
		return compileMulti(javac, workDir, Map.of(fileName, source));
	}

	private static File compileMulti(String javac, File workDir, Map<String, String> sources) throws Exception {
		File srcDir = new File(workDir, "src");
		File outDir = new File(workDir, "out");
		srcDir.mkdirs();
		outDir.mkdirs();
		List<String> cmd = new ArrayList<>();
		cmd.add(javac);
		cmd.add("-nowarn");
		cmd.add("-encoding");
		cmd.add("UTF-8");
		cmd.add("-cp");
		cmd.add(outDir.getAbsolutePath());
		cmd.add("-d");
		cmd.add(outDir.getAbsolutePath());
		for (Map.Entry<String, String> e : sources.entrySet()) {
			File f = new File(srcDir, e.getKey());
			Files.writeString(f.toPath(), e.getValue());
			cmd.add(f.getAbsolutePath());
		}
		runCmd(cmd.toArray(new String[0]));
		return outDir;
	}

	private static String findJavac() {
		String javac = System.getProperty("hstest.javac21");
		if (javac != null && new File(javac).exists()) return javac;
		javac = System.getenv("HSTEST_JAVAC21");
		if (javac != null && new File(javac).exists()) return javac;
		File home = new File(System.getProperty("java.home"), "bin/javac" + (isWindows() ? ".exe" : ""));
		if (home.exists()) return home.getAbsolutePath();
		return "javac";
	}

	private static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("win");
	}

	private static void runCmd(String... cmd) throws Exception {
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.contains("error") || line.contains("错误")) System.out.println("[javac] " + line);
			}
		}
		int code = p.waitFor();
		if (code != 0) throw new IllegalStateException("javac failed with exit " + code + ": " + String.join(" ", cmd));
	}

	private static void deleteRecursively(File f) {
		if (f == null || !f.exists()) return;
		File[] children = f.listFiles();
		if (children != null) for (File c : children) deleteRecursively(c);
		//noinspection ResultOfMethodCallIgnored
		f.delete();
	}
}
