import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import remold.ClassDiffUtil;
import remold.Reflect;
import remold.RemoldAgent;
import remold.ref.InitFix;
import remold.util.LibTool;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * P0.5 pre-flight probe for the two-phase (Schema-First) redefinition roadmap.
 *
 * <p>Runs on a DCEVM-capable runtime (JBR 17/21) with
 * {@code -XX:+AllowEnhancedClassRedefinition -javaagent:p05-agent.jar}. The agent is deliberately
 * minimal ({@link P05Agent}): no Remold features are initialized, so the numbers reflect raw
 * JVM/DCEVM capability instead of Remold's own layout gates.</p>
 *
 * <ol>
 *   <li><b>M1 double schema redefinition</b>: V1 (no new field) -> V2 (stage A skeleton: new field +
 *       new private members + new lambda, old bodies untouched) -> V3 (stage C: new bodies).
 *       Reports per-stage latency and GC deltas. A second case chains two consecutive schema changes
 *       (add field b, then add field c) to find the harder boundary.</li>
 *   <li><b>M2 stage A linking</b>: after stage A, a nestmate hidden "companion" class is generated with
 *       ASM and must link {@code invokevirtual}/{@code invokestatic} to the newly injected private
 *       members. A negative control proves the same companion cannot link before stage A.</li>
 *   <li><b>M3 heap iteration scaling</b>: {@code LibTool.getInstances} over 0 / 100k / 500k / 1M live
 *       instances, reporting wall time and heap cost.</li>
 * </ol>
 *
 * <p>Run via {@code ./gradlew :tests:p05Probe -Pp05.jbr=&lt;dcevm-jdk-home&gt;}.</p>
 */
public class P05Probe {

	static int passed = 0;
	static int failed = 0;

	static void check(boolean cond, String msg) {
		if (cond) {
			System.out.println("   [PASS] " + msg);
			passed++;
		} else {
			System.err.println("   [FAIL] " + msg);
			failed++;
		}
	}

	static void checkEq(Object expected, Object actual, String msg) {
		boolean ok = expected == null ? actual == null : expected.equals(actual);
		if (!ok) msg = msg + " [expected=" + expected + ", actual=" + actual + "]";
		check(ok, msg);
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=== P0.5 Probe: two-phase schema redefinition pre-flight ===");
		System.out.println("   java.vm.name = " + System.getProperty("java.vm.name")
		                   + ", java.version = " + System.getProperty("java.version"));
		System.out.println("   enhanced redefine flag present = " + hasEnhancedFlag());
		System.out.println("   max heap = " + (Runtime.getRuntime().maxMemory() >> 20) + " MiB");

		Instrumentation inst = P05Agent.instrumentation();
		if (inst == null) {
			System.out.println("[SKIP] P05Agent instrumentation unavailable; run with -javaagent:p05-agent.jar.");
			return;
		}
		if (!inst.isRedefineClassesSupported()) {
			System.out.println("[SKIP] redefineClasses not supported by this runtime.");
			return;
		}

		String javac = findJavac();
		File tempDir = Files.createTempDirectory("p05_probe").toFile();
		try {
			measureDoubleRedefine(inst, javac, tempDir);
			measureStageALinking(inst, javac, tempDir);
			measureHeapIteration();
			measurePatchLoop(javac, tempDir);
		} finally {
			deleteRecursively(tempDir);
		}

		System.out.println("\n=== P0.5 PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	// ------------------------------------------------------------------------------------------------
	// M1: double schema redefinition
	// ------------------------------------------------------------------------------------------------

	static final String SRC_V1 = """
		package p05;
		public class Subj {
		    public int a = 1;
		    public String hi() { return "v1:" + a; }
		    public static int marker() { return 1; }
		}
		""";

	/** Stage A skeleton: new field, new private members, new lambda; old bodies deliberately untouched. */
	static final String SRC_V2A = """
		package p05;
		public class Subj {
		    public int a = 1;
		    public int b = 42;
		    public String hi() { return "v1:" + a; }
		    private String nf() { return "nf"; }
		    private static int ns() { return 7; }
		    public Runnable mk() { return () -> { }; }
		    public static int marker() { return 1; }
		}
		""";

	/** Stage C: full new bytecode; body now reads the new field. */
	static final String SRC_V3 = """
		package p05;
		public class Subj {
		    public int a = 1;
		    public int b = 42;
		    public String hi() { return "v2:" + a + "," + b; }
		    private String nf() { return "nf"; }
		    private static int ns() { return 7; }
		    public Runnable mk() { return () -> { }; }
		    public static int marker() { return 2; }
		}
		""";

	/** Second consecutive schema change: adds field c on top of stage A. */
	static final String SRC_V2C = """
		package p05;
		public class Subj {
		    public int a = 1;
		    public int b = 42;
		    public int c = 99;
		    public String hi() { return "v1:" + a; }
		    public static int marker() { return 1; }
		}
		""";

	static void measureDoubleRedefine(Instrumentation inst, String javac, File baseDir) throws Exception {
		System.out.println("\n--- [M1a] stage A (schema change) -> stage C (body only) ---");
		File work = new File(baseDir, "m1a");
		File outV1 = compileInto(javac, work, "v1", SRC_V1);
		File outV2 = compileInto(javac, work, "v2a", SRC_V2A);
		File outV3 = compileInto(javac, work, "v3", SRC_V3);
		File v2Class = classFile(outV2, "p05/Subj.class");
		File v3Class = classFile(outV3, "p05/Subj.class");

		int iterations = 8;
		long[] stageA = new long[iterations];
		long[] stageC = new long[iterations];
		long gcCount = 0, gcMs = 0;

		for (int i = 0; i < iterations; i++) {
			URLClassLoader cl = newLoader(outV1);
			try {
				Class<?> subj = Class.forName("p05.Subj", true, cl);
				Object legacy = subj.getConstructor().newInstance();
				if (i == 0) checkEq("v1:1", callString(subj, legacy, "hi"), "v1 body returns v1:1");

				long[] gc0 = gcSnapshot();
				stageA[i] = redefineMicros(inst, subj, v2Class);
				long[] gc1 = gcSnapshot();
				gcCount += gc1[0] - gc0[0];
				gcMs += gc1[1] - gc0[1];

				if (i == 0) {
					Field fb = subj.getDeclaredField("b");
					Object fresh = subj.getConstructor().newInstance();
					check(fb.getInt(legacy) == 0, "legacy instance keeps default 0 for the new field (InitFix's job)");
					check(fb.getInt(fresh) == 42, "instance created after stage A runs the real field initializer");
					checkEq("v1:1", callString(subj, legacy, "hi"),
					        "stage A keeps the old body, which never reads the new field");
				}

				stageC[i] = redefineMicros(inst, subj, v3Class);

				if (i == 0) {
					Object fresh = subj.getConstructor().newInstance();
					checkEq("v2:1,0", callString(subj, legacy, "hi"),
					        "stage C body reads 0 on the legacy instance until InitFix patches it");
					checkEq("v2:1,42", callString(subj, fresh, "hi"),
					        "stage C body reads the real value on instances created after stage A");
					checkEq(2, subj.getMethod("marker").invoke(null), "stage C static body is live");
				}
			} finally {
				cl.close();
			}
		}
		report("M1a stage A (schema change)", stageA, gcCount, gcMs);
		report("M1a stage C (body only)", stageC, 0, 0);

		System.out.println("\n--- [M1b] two consecutive schema changes (add b, then add c) ---");
		File workB = new File(baseDir, "m1b");
		File outB1 = compileInto(javac, workB, "v1", SRC_V1);
		File outB2 = compileInto(javac, workB, "v2a", SRC_V2A);
		File outB3 = compileInto(javac, workB, "v2c", SRC_V2C);
		File b2Class = classFile(outB2, "p05/Subj.class");
		File b3Class = classFile(outB3, "p05/Subj.class");

		int rounds = 6;
		long[] first = new long[rounds];
		long[] second = new long[rounds];
		boolean chainOk = true;
		for (int i = 0; i < rounds; i++) {
			URLClassLoader cl = newLoader(outB1);
			try {
				Class<?> subj = Class.forName("p05.Subj", true, cl);
				first[i] = redefineMicros(inst, subj, b2Class);
				try {
					second[i] = redefineMicros(inst, subj, b3Class);
				} catch (Throwable t) {
					chainOk = false;
					System.err.println("   [FAIL] second consecutive schema change threw " + describe(t));
					break;
				}
				if (i == 0) {
					Field fb = subj.getDeclaredField("b");
					Field fc = subj.getDeclaredField("c");
					Object o = subj.getConstructor().newInstance();
					check(fb.getInt(o) == 42 && fc.getInt(o) == 99,
					      "both fields added by consecutive schema changes are live and initialized");
				}
			} finally {
				cl.close();
			}
		}
		check(chainOk, "two consecutive schema redefinitions are accepted by the runtime");
		report("M1b first schema change (add b)", first, 0, 0);
		report("M1b second schema change (add c)", second, 0, 0);
	}

	// ------------------------------------------------------------------------------------------------
	// M2: stage A pre-injected members must be linkable from a nestmate companion
	// ------------------------------------------------------------------------------------------------

	static void measureStageALinking(Instrumentation inst, String javac, File baseDir) throws Exception {
		System.out.println("\n--- [M2] companion linkage against stage A pre-injected members ---");
		File work = new File(baseDir, "m2");
		File outV1 = compileInto(javac, work, "v1", SRC_V1);
		File outV2 = compileInto(javac, work, "v2a", SRC_V2A);
		File v2Class = classFile(outV2, "p05/Subj.class");

		URLClassLoader cl = newLoader(outV1);
		try {
			Class<?> subj = Class.forName("p05.Subj", true, cl);
			Object target = subj.getConstructor().newInstance();
			byte[] companion = companionBytes();

			// Negative control: the same companion must fail to link before stage A.
			Throwable before = tryCompanion(subj, target, companion);
			check(before != null, "companion cannot link before stage A (got " + describe(before) + ")");

			redefineMicros(inst, subj, v2Class);

			Throwable after = tryCompanion(subj, target, companion);
			check(after == null, "companion links new private instance+static members after stage A"
			                     + (after == null ? "" : " (got " + describe(after) + ")"));

			Object lambda = subj.getMethod("mk").invoke(target);
			check(lambda instanceof Runnable, "new lambda call site resolves after stage A");
			if (lambda instanceof Runnable r) r.run();

			Throwable repeat = tryCompanion(subj, target, companion);
			check(repeat == null, "repeated companion definition stays stable");
		} finally {
			cl.close();
		}
	}

	/** Returns null on success, the thrown cause on failure. */
	static Throwable tryCompanion(Class<?> subj, Object target, byte[] companionBytes) {
		try {
			// Same path InitFix uses: IMPL_LOOKUP-backed privateLookupIn + NESTMATE hidden class.
			MethodHandles.Lookup lookup = Reflect.defineHiddenClass(subj, companionBytes);
			Class<?> companion = lookup.lookupClass();
			Method call = companion.getDeclaredMethod("call", Object.class);
			Object result = call.invoke(null, target);
			if (!"nf:7".equals(result)) {
				return new AssertionError("unexpected companion result: " + result);
			}
			return null;
		} catch (InvocationTargetException e) {
			return e.getCause() != null ? e.getCause() : e;
		} catch (Throwable t) {
			return t;
		}
	}

	/**
	 * Builds {@code p05/Companion} bytecode equivalent to:
	 * {@code public static String call(Object o) { return ((Subj) o).nf() + ":" + Subj.ns(); }}
	 */
	static byte[] companionBytes() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "p05/Companion", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
		                                  "call", "(Ljava/lang/Object;)Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
		mv.visitInsn(Opcodes.DUP);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitTypeInsn(Opcodes.CHECKCAST, "p05/Subj");
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "p05/Subj", "nf", "()Ljava/lang/String;", false);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
		                   "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
		mv.visitLdcInsn(":");
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
		                   "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "p05/Subj", "ns", "()I", false);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
		                   "(I)Ljava/lang/StringBuilder;", false);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
		                   "()Ljava/lang/String;", false);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	// ------------------------------------------------------------------------------------------------
	// M3: heap iteration scaling
	// ------------------------------------------------------------------------------------------------

	/** Fixture type for heap iteration; instances are held alive by the probe itself. */
	public static final class HeapSubj {
		public int v;
		public HeapSubj(int v) { this.v = v; }
	}

	static void measureHeapIteration() {
		System.out.println("\n--- [M3] LibTool.getInstances scaling ---");
		try {
			if (!LibTool.initialized()) LibTool.init();
		} catch (Throwable t) {
			System.out.println("   [SKIP] native libtool unavailable: " + describe(t));
			return;
		}

		List<HeapSubj> keep = new ArrayList<>();
		for (int n : new int[]{0, 100_000, 500_000, 1_000_000}) {
			keep.clear();
			System.gc();
			keep = new ArrayList<>(Math.max(n, 1));
			for (int i = 0; i < n; i++) keep.add(new HeapSubj(i));

			long heapBefore = usedHeap();
			long t0 = System.nanoTime();
			HeapSubj[] got = LibTool.getInstances(HeapSubj.class);
			long us = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - t0);
			long heapAfter = usedHeap();

			long t1 = System.nanoTime();
			HeapSubj[] again = LibTool.getInstances(HeapSubj.class);
			long us2 = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - t1);

			System.out.printf("   n=%-8d first=%-8s repeat=%-8s returned=%-8d arrayHeap=%-6d KiB%n",
			                  n, us + "us", us2 + "us", got.length, (heapAfter - heapBefore) >> 10);
			check(got.length == n, "getInstances returned exactly " + n + " live instances");
			check(again.length == n, "repeat call is stable at n=" + n);
		}
		keep.clear();
	}

	// ------------------------------------------------------------------------------------------------
	// M4: enumeration vs patch loop — the two halves of the read-through window
	// ------------------------------------------------------------------------------------------------

	static final String SRC_BULK_V1 = """
		package p05;
		public class Bulk {
		    public int a = 1;
		}
		""";

	static final String SRC_BULK_V2 = """
		package p05;
		public class Bulk {
		    public int a = 1;
		    public int b = 42;
		}
		""";

	/**
	 * Splits {@code applyPatch} into its two halves, because they sit on different sides of the
	 * read-through window: enumeration (only present when the snapshot is taken after the
	 * redefinition) and the conditional-CAS loop.
	 */
	static void measurePatchLoop(String javac, File baseDir) throws Exception {
		System.out.println("\n--- [M4] enumeration vs conditional-CAS loop ---");
		File work = new File(baseDir, "m4");
		File outV1 = compileInto(javac, work, "v1", "Bulk.java", SRC_BULK_V1);
		File outV2 = compileInto(javac, work, "v2", "Bulk.java", SRC_BULK_V2);
		byte[] v1 = Files.readAllBytes(classFile(outV1, "p05/Bulk.class").toPath());
		byte[] v2 = Files.readAllBytes(classFile(outV2, "p05/Bulk.class").toPath());

		// Load v2 directly: the field already exists, so no redefinition is needed to exercise the
		// patch driver — this measurement is about the driver's cost, not about DCEVM.
		URLClassLoader cl = newLoader(outV2);
		try {
			Class<?> host = Class.forName("p05.Bulk", true, cl);
			Field fb = host.getField("b");

			for (int n : new int[]{100_000, 1_000_000}) {
				Object[] keep = new Object[n];
				for (int i = 0; i < n; i++) keep[i] = Reflect.UNSAFE.allocateInstance(host);

				RemoldAgent.bytecodeCache.put("p05.Bulk", v2);
				InitFix.transform(host, v2, ClassDiffUtil.diff(v1, v2));

				long t0 = System.nanoTime();
				InitFix.afterRedefine(host);
				long totalUs = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - t0);

				long t1 = System.nanoTime();
				Object[] got = LibTool.getInstances(host);
				long enumUs = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - t1);

				long casUs = totalUs - enumUs;
				System.out.printf("   n=%-8d applyPatch=%-8dus  enumerate=%-8dus  impliedCAS=%-8dus  enumShare=%.1f%%%n",
				                  n, totalUs, enumUs, casUs, totalUs == 0 ? 0.0 : 100.0 * enumUs / totalUs);

				check(got.length == n, "enumeration still sees all " + n + " live instances");
				check(fb.getInt(keep[0]) == 42 && fb.getInt(keep[n - 1]) == 42,
				      "the CAS loop actually wrote the patched value at n=" + n);

				keep = null;
				System.gc();
			}
		} finally {
			cl.close();
		}
	}

	// ------------------------------------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------------------------------------

	static boolean hasEnhancedFlag() {
		for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
			if (a.contains("AllowEnhancedClassRedefinition")) return true;
		}
		return false;
	}

	static long redefineMicros(Instrumentation inst, Class<?> clazz, File classFile) throws Exception {
		byte[] bytes = Files.readAllBytes(classFile.toPath());
		ClassDefinition def = new ClassDefinition(clazz, bytes);
		long t0 = System.nanoTime();
		inst.redefineClasses(def);
		return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - t0);
	}

	static String callString(Class<?> clazz, Object target, String name) throws Exception {
		return (String) clazz.getMethod(name).invoke(target);
	}

	static long[] gcSnapshot() {
		long count = 0, ms = 0;
		for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
			count += bean.getCollectionCount();
			ms += bean.getCollectionTime();
		}
		return new long[]{count, ms};
	}

	static long usedHeap() {
		Runtime rt = Runtime.getRuntime();
		return rt.totalMemory() - rt.freeMemory();
	}

	static void report(String label, long[] micros, long gcCount, long gcMs) {
		if (micros.length == 0) {
			System.out.println("   " + label + " (no samples)");
			return;
		}
		long[] sorted = micros.clone();
		Arrays.sort(sorted);
		long sum = 0;
		for (long v : sorted) sum += v;
		int p95 = (int) Math.min(sorted.length - 1L, Math.max(0L, Math.round(sorted.length * 0.95) - 1));
		System.out.printf("   %-32s n=%d min=%dus p50=%dus p95=%dus max=%dus avg=%dus gc=%d/%dms%n",
		                  label, sorted.length, sorted[0], sorted[sorted.length / 2],
		                  sorted[p95], sorted[sorted.length - 1], sum / sorted.length, gcCount, gcMs);
	}

	static File compileInto(String javac, File workDir, String variant, String source) throws Exception {
		return compileInto(javac, workDir, variant, "Subj.java", source);
	}

	static File compileInto(String javac, File workDir, String variant, String fileName, String source) throws Exception {
		File srcDir = new File(workDir, variant + "/src");
		File outDir = new File(workDir, variant + "/out");
		srcDir.mkdirs();
		outDir.mkdirs();
		File f = new File(srcDir, fileName);
		Files.writeString(f.toPath(), source);
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outDir.getAbsolutePath(), f.getAbsolutePath());
		return outDir;
	}

	static File classFile(File outDir, String relative) {
		return new File(outDir, relative);
	}

	static URLClassLoader newLoader(File outDir) throws Exception {
		// Parent must be the application loader: the stage-B companion class is a nestmate of the
		// fixture and links remold.ref.HotswapBridge through its defining loader.
		return new URLClassLoader(new URL[]{outDir.toURI().toURL()}, P05Probe.class.getClassLoader());
	}

	static String findJavac() {
		String javac = System.getProperty("hstest.javac21");
		if (javac != null && new File(javac).exists()) return javac;
		javac = System.getenv("HSTEST_JAVAC21");
		if (javac != null && new File(javac).exists()) return javac;
		File home = new File(System.getProperty("java.home"), "bin/javac" + (isWindows() ? ".exe" : ""));
		if (home.exists()) return home.getAbsolutePath();
		return "javac";
	}

	static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("win");
	}

	static String describe(Throwable t) {
		if (t == null) return "none";
		String s = t.getClass().getSimpleName();
		return t.getMessage() == null ? s : s + ": " + t.getMessage();
	}

	static void runCmd(String... cmd) throws Exception {
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

	static void deleteRecursively(File f) {
		if (f == null || !f.exists()) return;
		File[] children = f.listFiles();
		if (children != null) for (File c : children) deleteRecursively(c);
		//noinspection ResultOfMethodCallIgnored
		f.delete();
	}
}
