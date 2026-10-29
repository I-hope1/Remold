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
			probeB_SuperclassDrift(inst, javac, base);
			probeC_AccessorCorruptionAndGate(inst, javac, base);
			probeD_C2Deopt(inst, javac, base);
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
		callerClass.getMethod("loop", targetClass, int.class).invoke(null, targetObj, 30_000);
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
