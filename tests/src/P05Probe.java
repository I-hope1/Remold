import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
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
			measureHeapProportionality(javac, tempDir);
			measureSafepoint(javac, tempDir);
			measureFieldRemoval(javac, tempDir);
			measureMethodRemoval(javac, tempDir);
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

	/** 读探针 JVM 写到 {@code -Dp05.logdir} 下的日志；缺失时返回空串。 */
	static String readLog(String name) {
		String dir = System.getProperty("p05.logdir");
		if (dir == null) return "";
		File f = new File(dir, name);
		if (!f.exists()) return "";
		try {
			return Files.readString(f.toPath());
		} catch (Exception e) {
			return "";
		}
	}

	/** safepoint 日志里所有 {@code HeapIterateOperation} 的 (uptimenanos, totalNanos)。 */
	static List<long[]> heapIterateEntries() {
		List<long[]> out = new ArrayList<>();
		String text = readLog("safepoint.log");
		if (text.isEmpty()) return out;
		java.util.regex.Pattern p = java.util.regex.Pattern.compile(
			"^\\[(\\d+)ns\\].*Safepoint \"HeapIterateOperation\".*Total: (\\d+) ns");
		for (String line : text.split("\n")) {
			java.util.regex.Matcher m = p.matcher(line);
			if (m.find()) out.add(new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2))});
		}
		return out;
	}

	/** nanoTime → 日志的 uptimenanos 原点（一次采样对齐，误差在毫秒级）。 */
	static long uptimeBaseNanos;
	static long nanoBase;

	static void alignClocks() {
		nanoBase = System.nanoTime();
		uptimeBaseNanos = ManagementFactory.getRuntimeMXBean().getUptime() * 1_000_000L;
	}

	static long toUptimeNanos(long nanoTime) {
		return uptimeBaseNanos + (nanoTime - nanoBase);
	}

	static void measureHeapIteration() {
		System.out.println("\n--- [M3] LibTool.getInstances scaling ---");
		try {
			if (!LibTool.initialized()) LibTool.init();
		} catch (Throwable t) {
			System.out.println("   [SKIP] native libtool unavailable: " + describe(t));
			return;
		}

		alignClocks();
		List<long[]> sampleWindows = new ArrayList<>();
		List<HeapSubj> keep = new ArrayList<>();
		for (int n : new int[]{0, 100_000, 500_000, 1_000_000}) {
			keep.clear();
			System.gc();
			keep = new ArrayList<>(Math.max(n, 1));
			for (int i = 0; i < n; i++) keep.add(new HeapSubj(i));

			long heapBefore = usedHeap();
			HeapSubj[] got = LibTool.getInstances(HeapSubj.class);
			long heapAfter = usedHeap();

			// 每档重复 5 次，报 min/median/max：单次采样分不清「温和增长」和「跨次抖动」。
			long[] samples = new long[5];
			for (int r = 0; r < samples.length; r++) {
				long t = System.nanoTime();
				LibTool.getInstances(HeapSubj.class);
				long e = System.nanoTime();
				samples[r] = TimeUnit.NANOSECONDS.toMicros(e - t);
				sampleWindows.add(new long[]{n, toUptimeNanos(t), toUptimeNanos(e)});
			}
			long[] sorted = samples.clone();
			java.util.Arrays.sort(sorted);
			long median = sorted[sorted.length / 2];

			System.out.printf("   n=%-8d min=%-7s median=%-7s max=%-7s perItem=%.2fus  returned=%-8d arrayHeap=%-6d KiB%n",
			                  n, sorted[0] + "us", median + "us", sorted[sorted.length - 1] + "us",
			                  n == 0 ? 0.0 : median / (double) n, got.length, (heapAfter - heapBefore) >> 10);
			check(got.length == n, "getInstances returned exactly " + n + " live instances");
			check(LibTool.getInstances(HeapSubj.class).length == n, "repeat call is stable at n=" + n);
		}
		keep.clear();

		// 运行结束后再读日志（unified logging 有缓冲），把每次调用的 STW 段与墙钟对齐。
		try {
			Thread.sleep(800);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
		List<long[]> entries = heapIterateEntries();
		System.out.println("   [STW 拆分] 每档 5 个样本：墙钟中位数 / 其中 HeapIterateOperation 的 STW");
		for (int n : new int[]{0, 100_000, 500_000, 1_000_000}) {
			List<Long> walls = new ArrayList<>();
			List<Long> stws = new ArrayList<>();
			for (long[] w : sampleWindows) {
				if (w[0] != n) continue;
				walls.add((w[2] - w[1]) / 1000);
				long stw = 0;
				for (long[] e : entries) {
					if (e[0] >= w[1] && e[0] <= w[2]) stw += e[1];
				}
				stws.add(stw / 1000);
			}
			if (walls.isEmpty()) continue;
			java.util.Collections.sort(walls);
			java.util.Collections.sort(stws);
			long wm = walls.get(walls.size() / 2);
			long sm = stws.get(stws.size() / 2);
			System.out.printf("   n=%-8d wallMedian=%-7dus stwMedian=%-7dus outside=%-7dus stwShare=%.0f%%%n",
			                  n, wm, sm, wm - sm, wm == 0 ? 0.0 : 100.0 * sm / wm);
		}
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
	// M5: does enumeration scale with the target class's instance count, or with the whole heap?
	// ------------------------------------------------------------------------------------------------

	static void measureHeapProportionality(String javac, File baseDir) throws Exception {
		System.out.println("\n--- [M5] enumeration cost: target instances vs whole heap ---");
		File work = new File(baseDir, "m5");
		File out = compileInto(javac, work, "v2", "Bulk.java", SRC_BULK_V2);

		URLClassLoader cl = newLoader(out);
		try {
			Class<?> host = Class.forName("p05.Bulk", true, cl);

			for (int n : new int[]{10_000, 100_000, 1_000_000}) {
				Object[] keep = new Object[n];
				for (int i = 0; i < n; i++) keep[i] = Reflect.UNSAFE.allocateInstance(host);

				long us = warmEnumerationMicros(host);
				System.out.printf("   target=%-8d unrelated=0        enumerate=%-8dus%n", n, us);
				check(LibTool.getInstances(host).length == n, "enumeration sees exactly " + n + " targets");

				keep = null;
				System.gc();
			}

			// Same target count as the "small" case, but a million unrelated objects on the heap.
			Object[] filler = new Object[1_000_000];
			for (int i = 0; i < filler.length; i++) filler[i] = new byte[16];
			Object[] few = new Object[100];
			for (int i = 0; i < few.length; i++) few[i] = Reflect.UNSAFE.allocateInstance(host);

			long us = warmEnumerationMicros(host);
			System.out.printf("   target=%-8d unrelated=1M       enumerate=%-8dus%n", 100, us);
			check(LibTool.getInstances(host).length == 100, "enumeration sees exactly 100 targets");

			filler = null;
			few = null;
			System.gc();
		} finally {
			cl.close();
		}
	}

	/** Second call, to skip the one-off native-library / tag-channel warm-up. */
	static long warmEnumerationMicros(Class<?> host) {
		LibTool.getInstances(host);
		long t0 = System.nanoTime();
		LibTool.getInstances(host);
		return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - t0);
	}

	// ------------------------------------------------------------------------------------------------
	// M6: does heap enumeration stop the world?
	// ------------------------------------------------------------------------------------------------

	static volatile long HEARTBEATS;
	static volatile long MAX_GAP_NANOS;

	/**
	 * 判断枚举期间业务线程是否被停过。
	 *
	 * <p>只看总吞吐是不够的：67% 的心跳速率既可能是「全程并发 + 资源争抢」，也可能是「一段 STW 把
	 * 平均拉低」。两种读法对窗口的含义完全不同，所以这里记录<b>相邻两次自增的最大间隔</b>——
	 * 若存在一段几百毫秒的 STW，最大间隔会直接暴露它，而总吞吐会把它平均掉。
	 * 同时把 safepoint 日志写到文件，用于交叉验证。</p>
	 */
	static void measureSafepoint(String javac, File baseDir) throws Exception {
		System.out.println("\n--- [M6] does enumeration stop the world? (max-gap + safepoint log) ---");
		File work = new File(baseDir, "m6");
		File out = compileInto(javac, work, "v2", "Bulk.java", SRC_BULK_V2);

		URLClassLoader cl = newLoader(out);
		try {
			Class<?> host = Class.forName("p05.Bulk", true, cl);
			Object[] keep = new Object[1_000_000];
			for (int i = 0; i < keep.length; i++) keep[i] = Reflect.UNSAFE.allocateInstance(host);

			HEARTBEATS = 0;
			MAX_GAP_NANOS = 0;
			Thread hb = new Thread(() -> {
				long last = System.nanoTime();
				while (!Thread.currentThread().isInterrupted()) {
					long now = System.nanoTime();
					long gap = now - last;
					last = now;
					if (gap > MAX_GAP_NANOS) MAX_GAP_NANOS = gap;
					HEARTBEATS++;
				}
			});
			hb.setDaemon(true);
			hb.start();

			Thread.sleep(100);
			long b0 = HEARTBEATS;
			Thread.sleep(100);
			long b1 = HEARTBEATS;
			double beatsPerMs = (b1 - b0) / 100.0;

			MAX_GAP_NANOS = 0;                 // 只统计枚举窗口内的最大间隔
			long c0 = HEARTBEATS;
			long t0 = System.nanoTime();
			LibTool.getInstances(host);
			long us = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - t0);
			long c1 = HEARTBEATS;
			long maxGapMs = TimeUnit.NANOSECONDS.toMillis(MAX_GAP_NANOS);

			hb.interrupt();
			hb.join(1000);

			double expected = beatsPerMs * (us / 1000.0);
			long observed = c1 - c0;
			double stallShare = us == 0 ? 0.0 : (double) maxGapMs / (us / 1000.0);
			System.out.printf("   enumerate=%-8dus  beats=%d (expected~%.0f, %.0f%%)  maxGap=%dms (%.0f%% of call)%n",
			                  us, observed, expected, expected == 0 ? 0.0 : 100.0 * observed / expected,
			                  maxGapMs, 100.0 * stallShare);
			System.out.println(stallShare >= 0.1
				? "   => one stall covers a large share of the call: a stop-the-world segment exists"
				: "   => no large stall: the call is not one long stop-the-world");
			check(beatsPerMs > 0, "heartbeat thread was actually running before the measurement");
			check(true, "safepoint behaviour recorded (see the two lines above)");

			keep = null;
			System.gc();
		} finally {
			cl.close();
		}
	}

	// ------------------------------------------------------------------------------------------------
	// M7: named-class field removal
	// ------------------------------------------------------------------------------------------------

	/** v1: fields x,y,z in that declaration order — the order decides the slot layout. */
	static final String SRC_REM_A_V1 = """
		package rem;
		public class A {
		    public int x = 11;
		    public int y = 22;
		    public int z = 33;
		    public int readY() { return y; }
		    public int readZ() { return z; }
		}
		""";

	/** v2: {@code x} removed, everything else byte-identical. */
	static final String SRC_REM_A_V2 = """
		package rem;
		public class A {
		    public int y = 22;
		    public int z = 33;
		    public int readY() { return y; }
		    public int readZ() { return z; }
		}
		""";

	/** Compiled against v1, never redefined: its {@code GETFIELD rem/A.x} is the out-of-batch reader. */
	static final String SRC_REM_B = """
		package rem;
		public class B {
		    public static int readX(A a) { return a.x; }
		}
		""";

	/** Compiled against v1, never redefined: {@code w} sits after A's three fields. */
	static final String SRC_REM_C = """
		package rem;
		public class C extends A {
		    public int w = 44;
		    public int readW() { return w; }
		    public void writeW(int v) { w = v; }
		}
		""";

	/**
	 * v3: after {@code x} was removed, add a brand-new field {@code q}. The question this answers is
	 * whether the JVM reuses the abandoned slot — if it does, {@code q} on a surviving instance reads
	 * whatever {@code x} used to hold, and {@code InitFix}'s conditional CAS (which only writes when
	 * the field is still the type default) would skip it, leaving the field permanently stale.
	 */
	static final String SRC_REM_A_V3 = """
		package rem;
		public class A {
		    public int y = 22;
		    public int z = 33;
		    public int q = 77;
		    public int readY() { return y; }
		    public int readZ() { return z; }
		    public int readQ() { return q; }
		}
		""";

	/**
	 * Why is field removal on a <em>named</em> class refused? The measured answer, split into the
	 * cases that actually differ:
	 *
	 * <ul>
	 *   <li><b>M7a</b> out-of-batch reader whose constant-pool entry was already resolved
	 *       (it ran before the removal) — the cached offset may still be usable, so it can silently
	 *       return the value of a field that no longer exists;</li>
	 *   <li><b>M7b</b> the same reader, but never executed before the removal — re-resolution has to
	 *       look the field up again and may fail cleanly;</li>
	 *   <li><b>M7c</b> a <em>new</em> instance after the removal: if {@code readY} still returns 22
	 *       the old slot layout was preserved; if it returns 0 the layout was compacted and the
	 *       redefined class now disagrees with every surviving instance;</li>
	 *   <li><b>M7d</b> a surviving instance's other fields;</li>
	 *   <li><b>M7e</b> a subclass that was not redefined and whose field sits after the removed one.</li>
	 * </ul>
	 *
	 * <p>These are observations, not assertions about what the JVM is supposed to do: the point is
	 * to replace "删除字段后果不可预期" with a concrete failure mode.</p>
	 */
	static void measureFieldRemoval(String javac, File baseDir) throws Exception {
		System.out.println("\n--- [M7] named-class field removal: out-of-batch readers ---");
		File work = new File(baseDir, "m7");
		File v1 = compileInto(javac, work, "v1", "A.java", SRC_REM_A_V1);
		compileInto(javac, work, "v1", "B.java", SRC_REM_B);
		compileInto(javac, work, "v1", "C.java", SRC_REM_C);
		File v2 = compileInto(javac, work, "v2", "A.java", SRC_REM_A_V2);
		File aV2 = new File(v2, "rem/A.class");
		File v3 = compileInto(javac, work, "v3", "A.java", SRC_REM_A_V3);
		File aV3 = new File(v3, "rem/A.class");

		// ---- M7a: reader already executed, so its constant-pool entry is resolved and cached ----
		URLClassLoader cl = newLoader(v1);
		try {
			Class<?> a = Class.forName("rem.A", true, cl);
			Class<?> b = Class.forName("rem.B", true, cl);
			Object inst = a.getConstructor().newInstance();
			checkEq(11, invokeInt(b, "readX", new Class<?>[]{a}, null, inst),
			        "M7a precondition: the out-of-batch reader sees x=11 before the removal");
			checkEq(22, invokeInt(a, "readY", new Class<?>[0], inst, null),
			        "M7a precondition: surviving field y=22 before the removal");

			long us = redefineMicros(P05Agent.instrumentation(), a, aV2);
			System.out.println("   [M7a] redefine accepted, " + us + "us (x removed, live instance present)");

			System.out.println("        resolved-CP reader  -> " + tryInt(b, "readX", new Class<?>[]{a}, null, inst));
			System.out.println("        surviving y / z on the old instance -> "
			                   + tryInt(a, "readY", new Class<?>[0], inst, null) + " / "
			                   + tryInt(a, "readZ", new Class<?>[0], inst, null));

			// M7c: a brand-new instance. Its slot layout comes from the *redefined* class; the
			// pre-existing instance still has the old one. If readY differs between them, the
			// redefined class disagrees with the survivors.
			Object fresh = a.getConstructor().newInstance();
			System.out.println("        NEW instance y / z -> "
			                   + tryInt(a, "readY", new Class<?>[0], fresh, null) + " / "
			                   + tryInt(a, "readZ", new Class<?>[0], fresh, null));
			System.out.println("        NEW instance readX (field is gone from the class) -> "
			                   + tryInt(b, "readX", new Class<?>[]{a}, null, fresh));
			check(true, "M7a/M7c outcomes recorded (see the lines above)");
		} finally {
			cl.close();
		}

		// ---- M7b: same reader, but its constant-pool entry was never resolved before the removal ----
		cl = newLoader(v1);
		try {
			Class<?> a = Class.forName("rem.A", true, cl);
			Class<?> b = Class.forName("rem.B", true, cl);
			Object inst = a.getConstructor().newInstance();
			// Deliberately do NOT call readX here: its GETFIELD must stay unresolved.
			checkEq(22, invokeInt(a, "readY", new Class<?>[0], inst, null),
			        "M7b precondition: A is loaded and usable, but B.readX has never run");

			redefineMicros(P05Agent.instrumentation(), a, aV2);
			System.out.println("   [M7b] unresolved-CP reader -> "
			                   + tryInt(b, "readX", new Class<?>[]{a}, null, inst));
			check(true, "M7b outcome recorded (see the line above)");
		} finally {
			cl.close();
		}

		// ---- M7f: remove x, then add a new field q — is the abandoned slot reused? ----
		cl = newLoader(v1);
		try {
			Class<?> a = Class.forName("rem.A", true, cl);
			Class<?> b = Class.forName("rem.B", true, cl);
			Object inst = a.getConstructor().newInstance();
			checkEq(11, invokeInt(b, "readX", new Class<?>[]{a}, null, inst),
			        "M7f precondition: x=11 is physically present in the slot that is about to be abandoned");
			checkEq(22, invokeInt(a, "readY", new Class<?>[0], inst, null),
			        "M7f precondition: y=22 before the removals");
			redefineMicros(P05Agent.instrumentation(), a, aV2);
			redefineMicros(P05Agent.instrumentation(), a, aV3);
			String q = tryInt(a, "readQ", new Class<?>[0], inst, null);
			System.out.println("   [M7f] after remove-x then add-q, q on the SURVIVING instance -> " + q);
			System.out.println("         (0 = the slot was appended; 11 = the abandoned x slot was reused,"
			                   + " which makes InitFix's conditional CAS skip it forever)");
			Object fresh = a.getConstructor().newInstance();
			System.out.println("         q on a NEW instance -> " + tryInt(a, "readQ", new Class<?>[0], fresh, null));
			check(true, "M7f outcome recorded (see the lines above)");
		} finally {
			cl.close();
		}

		// ---- M7e: subclass not in the batch, its field sits after the removed one ----
		cl = newLoader(v1);
		try {
			Class<?> a = Class.forName("rem.A", true, cl);
			Class<?> c = Class.forName("rem.C", true, cl);
			Object ci = c.getConstructor().newInstance();
			checkEq(44, invokeInt(c, "readW", new Class<?>[0], ci, null),
			        "M7e precondition: subclass field w=44 before the removal");

			redefineMicros(P05Agent.instrumentation(), a, aV2);
			System.out.println("   [M7e] subclass w after removing a parent field -> "
			                   + tryInt(c, "readW", new Class<?>[0], ci, null));
			c.getMethod("writeW", int.class).invoke(ci, 99);
			System.out.println("        after writeW(99): w=" + tryInt(c, "readW", new Class<?>[0], ci, null)
			                   + ", parent y=" + tryInt(a, "readY", new Class<?>[0], ci, null)
			                   + ", parent z=" + tryInt(a, "readZ", new Class<?>[0], ci, null));
			check(true, "M7e outcome recorded (see the lines above)");
		} finally {
			cl.close();
		}
	}

	// ------------------------------------------------------------------------------------------------
	// M8: named-class method removal — upcast readers and virtual dispatch
	// ------------------------------------------------------------------------------------------------

	/**
	 * A reader's constant-pool entry names the <b>compile-time static type</b>, so when the removed
	 * method overrides a supertype method the {@code Methodref} / {@code InterfaceMethodref} owner
	 * sits <b>above</b> the declaring class:
	 *
	 * <pre>
	 *   viaI(I i)      -&gt; InterfaceMethodref rem2/I.m:()Ljava/lang/String;
	 *   viaSuper(Super)-&gt; Methodref          rem2/Super.m:()Ljava/lang/String;
	 *   viaA(A a)      -&gt; Methodref          rem2/A.m:()Ljava/lang/String;
	 * </pre>
	 *
	 * <p>Resolution only walks <em>up</em> (JVMS 5.4.3.3), so the first two resolve to {@code I.m} /
	 * {@code Super.m} and never mention {@code A.m}. An analysis that only matches
	 * {@code declaring == A} therefore misses them. Dispatch, on the other hand, walks <em>down</em>
	 * (JVMS 5.4.6): before the removal both of them land on {@code A.m}. That asymmetry is exactly
	 * what this measurement is about.</p>
	 */
	static final String SRC_M8_I = """
		package rem2;
		public interface I { String m(); }
		""";

	static final String SRC_M8_SUPER = """
		package rem2;
		public class Super { public String m() { return "Super.m"; } }
		""";

	static final String SRC_M8_A_V1 = """
		package rem2;
		public class A extends Super implements I {
		    @Override public String m() { return "A.m"; }
		}
		""";

	/** Same class with {@code m} removed: the override and the interface implementation are gone. */
	static final String SRC_M8_A_V2 = """
		package rem2;
		public class A extends Super implements I {
		}
		""";

	static final String SRC_M8_READER = """
		package rem2;
		public class Reader {
		    public static String viaI(I i) { return i.m(); }
		    public static String viaSuper(Super s) { return s.m(); }
		    public static String viaA(A a) { return a.m(); }
		}
		""";

	/** No superclass implementation: removing {@code n} leaves a concrete class with no body. */
	static final String SRC_M8_J = """
		package rem3;
		public interface J { String n(); }
		""";

	static final String SRC_M8_ONLY_V1 = """
		package rem3;
		public class Only implements J {
		    @Override public String n() { return "Only.n"; }
		}
		""";

	static final String SRC_M8_ONLY_V2 = """
		package rem3;
		public class Only implements J {
		}
		""";

	static final String SRC_M8_READER3 = """
		package rem3;
		public class Reader3 { public static String viaJ(J j) { return j.n(); } }
		""";

	/** Control: a method nobody overrides — the reference resolves straight to the declaring class. */
	static final String SRC_M8_PLAIN_V1 = """
		package rem4;
		public class Plain { public String p() { return "Plain.p"; } }
		""";

	static final String SRC_M8_PLAIN_V2 = """
		package rem4;
		public class Plain { }
		""";

	static final String SRC_M8_READER4 = """
		package rem4;
		public class Reader4 { public static String call(Plain x) { return x.p(); } }
		""";

	/**
	 * Method removal splits into two mechanisms, and they fail differently:
	 *
	 * <ul>
	 *   <li><b>M8a</b> the removed method overrides a superclass method <em>and</em> implements an
	 *       interface method, and a superclass implementation remains. Upcast readers
	 *       ({@code viaI}, {@code viaSuper}) resolve above {@code A}, so a naive
	 *       {@code declaring == A} attribution never sees them; at run time they keep working but
	 *       <b>silently dispatch somewhere else</b>. The downcast reader ({@code viaA}) names
	 *       {@code A} directly and fails loudly.</li>
	 *   <li><b>M8b</b> the removed method was the only implementation of an interface method. The
	 *       upcast reader resolves to {@code J.n}, and the concrete class is left with no body.</li>
	 *   <li><b>M8c</b> control: nobody overrides it, so resolution already names the declaring
	 *       class and the existing judgement handles it.</li>
	 * </ul>
	 */
	static void measureMethodRemoval(String javac, File baseDir) throws Exception {
		System.out.println("\n--- [M8] named-class method removal: upcast readers and virtual dispatch ---");

		// ---- M8a: override + interface implementation, superclass implementation survives ----
		File workA = new File(baseDir, "m8a");
		File a1 = compileInto(javac, workA, "v1", "I.java", SRC_M8_I);
		compileInto(javac, workA, "v1", "Super.java", SRC_M8_SUPER);
		compileInto(javac, workA, "v1", "A.java", SRC_M8_A_V1);
		compileInto(javac, workA, "v1", "Reader.java", SRC_M8_READER);
		File a2 = compileInto(javac, workA, "v2", "A.java", SRC_M8_A_V2, a1);

		URLClassLoader cl = newLoader(a1);
		try {
			Class<?> a = Class.forName("rem2.A", true, cl);
			Class<?> i = Class.forName("rem2.I", true, cl);
			Class<?> sup = Class.forName("rem2.Super", true, cl);
			Class<?> rd = Class.forName("rem2.Reader", true, cl);
			Object inst = a.getConstructor().newInstance();

			// Baseline first: if these do not dispatch to A.m before the removal, a later change
			// proves nothing. Both upcast paths have to land on A.m.
			checkEq("A.m", tryInt(rd, "viaI", new Class<?>[]{i}, null, inst),
			        "M8a precondition: viaI(I) dispatches to A.m before the removal");
			checkEq("A.m", tryInt(rd, "viaSuper", new Class<?>[]{sup}, null, inst),
			        "M8a precondition: viaSuper(Super) dispatches to A.m before the removal");
			checkEq("A.m", tryInt(rd, "viaA", new Class<?>[]{a}, null, inst),
			        "M8a precondition: viaA(A) calls A.m directly before the removal");

			long us = redefineMicros(P05Agent.instrumentation(), a, new File(a2, "rem2/A.class"));
			System.out.println("   [M8a] redefine accepted, " + us + "us (A.m removed, live instance present)");

			System.out.println("        viaI(I) after removal      -> " + tryInt(rd, "viaI", new Class<?>[]{i}, null, inst));
			System.out.println("        viaSuper(Super) after      -> " + tryInt(rd, "viaSuper", new Class<?>[]{sup}, null, inst));
			System.out.println("        viaA(A) after              -> " + tryInt(rd, "viaA", new Class<?>[]{a}, null, inst));
			check(true, "M8a outcomes recorded (see the lines above)");
		} finally {
			cl.close();
		}

		// ---- M8b: the removed method was the only implementation of J.n ----
		// 这一档不能源码编译：删掉唯一实现后 `Only` 是具体类却未覆盖抽象方法，javac 直接报错。
		// 只能直接改字节码——要测的是 JVM 的行为，不是 javac 的检查。
		File workB = new File(baseDir, "m8b");
		File b1 = compileInto(javac, workB, "v1", "J.java", SRC_M8_J);
		compileInto(javac, workB, "v1", "Only.java", SRC_M8_ONLY_V1);
		compileInto(javac, workB, "v1", "Reader3.java", SRC_M8_READER3);
		byte[] onlyV2 = removeMethod(Files.readAllBytes(new File(b1, "rem3/Only.class").toPath()),
		                             "n", "()Ljava/lang/String;");

		cl = newLoader(b1);
		try {
			Class<?> only = Class.forName("rem3.Only", true, cl);
			Class<?> j = Class.forName("rem3.J", true, cl);
			Class<?> rd = Class.forName("rem3.Reader3", true, cl);
			Object inst = only.getConstructor().newInstance();
			checkEq("Only.n", tryInt(rd, "viaJ", new Class<?>[]{j}, null, inst),
			        "M8b precondition: viaJ(J) dispatches to Only.n before the removal");

			redefineMicros(P05Agent.instrumentation(), only, onlyV2);
			System.out.println("   [M8b] Only.n removed; viaJ(J) -> "
			                   + tryInt(rd, "viaJ", new Class<?>[]{j}, null, inst));
			System.out.println("         (a concrete class left without the interface body)");
			check(true, "M8b outcome recorded (see the line above)");
		} finally {
			cl.close();
		}

		// ---- M8c: control, nobody overrides p() ----
		File workC = new File(baseDir, "m8c");
		File c1 = compileInto(javac, workC, "v1", "Plain.java", SRC_M8_PLAIN_V1);
		compileInto(javac, workC, "v1", "Reader4.java", SRC_M8_READER4);
		File c2 = compileInto(javac, workC, "v2", "Plain.java", SRC_M8_PLAIN_V2, c1);

		cl = newLoader(c1);
		try {
			Class<?> plain = Class.forName("rem4.Plain", true, cl);
			Class<?> rd = Class.forName("rem4.Reader4", true, cl);
			Object inst = plain.getConstructor().newInstance();
			checkEq("Plain.p", tryInt(rd, "call", new Class<?>[]{plain}, null, inst),
			        "M8c precondition: call(Plain) reaches Plain.p before the removal");

			redefineMicros(P05Agent.instrumentation(), plain, new File(c2, "rem4/Plain.class"));
			System.out.println("   [M8c] Plain.p removed; call(Plain) -> "
			                   + tryInt(rd, "call", new Class<?>[]{plain}, null, inst));
			check(true, "M8c outcome recorded (see the line above)");
		} finally {
			cl.close();
		}
	}

	/** Calls a static/instance method and renders the value or the thrown exception as text. */
	static String tryInt(Class<?> owner, String name, Class<?>[] paramTypes, Object target, Object... args) {
		try {
			Object r = owner.getMethod(name, paramTypes).invoke(target, args);
			return String.valueOf(r);
		} catch (InvocationTargetException e) {
			Throwable c = e.getCause();
			return "threw " + c.getClass().getName() + ": " + c.getMessage();
		} catch (Throwable t) {
			return "threw " + t.getClass().getName() + ": " + t.getMessage();
		}
	}

	static int invokeInt(Class<?> owner, String name, Class<?>[] paramTypes, Object target, Object... args)
		throws Exception {
		return (int) owner.getMethod(name, paramTypes).invoke(target, args);
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
		return redefineMicros(inst, clazz, Files.readAllBytes(classFile.toPath()));
	}

	static long redefineMicros(Instrumentation inst, Class<?> clazz, byte[] bytes) throws Exception {
		ClassDefinition def = new ClassDefinition(clazz, bytes);
		long t0 = System.nanoTime();
		inst.redefineClasses(def);
		return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - t0);
	}

	/**
	 * 从字节码里删掉一个方法。
	 *
	 * <p>有些删除形态<b>无法用源码表达</b>：删掉一个具体类对接口方法的唯一实现后，源码本身就不合法
	 * （javac 会报"未覆盖抽象方法"）。这类夹具只能直接改字节码，否则测的就不是 JVM 的行为而是
	 * javac 的检查。</p>
	 */
	static byte[] removeMethod(byte[] classBytes, String name, String desc) {
		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, 0);
		int before = cn.methods.size();
		cn.methods.removeIf(m -> m.name.equals(name) && m.desc.equals(desc));
		if (cn.methods.size() == before) {
			throw new IllegalStateException("no such method: " + name + desc);
		}
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
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
		return compileInto(javac, workDir, variant, fileName, source, null);
	}

	/**
	 * @param extraCp 额外放在 classpath 上的输出目录。多版本夹具要用它：v2 只重编被改的那个类，
	 *                它引用的父类/接口仍来自 v1 的输出目录。
	 */
	static File compileInto(String javac, File workDir, String variant, String fileName, String source,
	                        File extraCp) throws Exception {
		File srcDir = new File(workDir, variant + "/src");
		File outDir = new File(workDir, variant + "/out");
		srcDir.mkdirs();
		outDir.mkdirs();
		File f = new File(srcDir, fileName);
		Files.writeString(f.toPath(), source);
		// 输出目录必须在 classpath 上：多文件夹具要互相引用（B 编译时引用 A 的字段）。
		String cp = outDir.getAbsolutePath();
		if (extraCp != null) cp = cp + File.pathSeparator + extraCp.getAbsolutePath();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8",
		       "-cp", cp,
		       "-d", outDir.getAbsolutePath(), f.getAbsolutePath());
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
