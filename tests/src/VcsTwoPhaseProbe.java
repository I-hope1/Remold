import remold.HotswapBatchListener;
import remold.LambdaAligner;
import remold.RemoldAgent;
import remold.vcs.AlignmentMetaStore;
import remold.vcs.BytecodePack;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PR1 two-phase end-to-end probe: a real batch that triggers {@code InitFix.requiresTwoPhase}
 * (added instance field + a live pre-existing instance) while the lambda set also changes.
 *
 * <p>Assertions (see {@code docs/topology/16-bytecode-changeset-vcs.md} §5.3 / §7.4):</p>
 * <ol>
 *   <li><b>stage A was actually taken</b> — the agent's stage-A rewrite counter advanced;</li>
 *   <li><b>exactly one generation was recorded</b> — head generation goes {@code 0 -> 1} (not
 *       {@code 2}), i.e. stage A and stage C cannot both commit (double-record guard);</li>
 *   <li><b>terminal state is real</b> — the pre-existing instance's newly added field is patched
 *       to its initializer value ({@code addedValue() == 42}), the redefined lambda still runs,
 *       and the recorded {@code jvmEffective} equals the bytes the agent reports as runtime bytes;</li>
 *   <li><b>strict-rejection batch records nothing</b> — a captured-lambda class compiled without
 *       {@code -g} under {@code LAMBDA_STRICT} is rejected; value and head generation stay unchanged.</li>
 * </ol>
 *
 * <p>Run: {@code ./gradlew :tests:vcsTwoPhaseProbe -Pp05.jbr=<DCEVM JDK>} (not wired into {@code check}).</p>
 */
public final class VcsTwoPhaseProbe {

	private static final String CLASS = "probe/TwoPhaseSubject";

	private static int passed;
	private static int failed;

	private static Path rootDir;
	private static Path classesDir;
	private static Path srcDir;
	private static Path packDir;
	private static URLClassLoader loader;
	private static Object instance;

	private static volatile CountDownLatch batchLatch = new CountDownLatch(0);
	private static final AtomicInteger batchCount = new AtomicInteger();

	public static void main(String[] args) throws Throwable {
		System.out.println("=== VcsTwoPhaseProbe: two-phase commit timing + strict-reject rollback ===");
		if (RemoldAgent.getInstrumentation() == null) {
			System.err.println("[FAIL] no instrumentation; run with -javaagent");
			System.exit(1);
		}
		if (!Boolean.getBoolean("remold.vcs.enabled")) {
			System.err.println("[FAIL] remold.vcs.enabled must be true");
			System.exit(1);
		}
		String packProp = System.getProperty(BytecodePack.DIR_PROPERTY);
		if (packProp == null) {
			System.err.println("[FAIL] " + BytecodePack.DIR_PROPERTY + " is not set");
			System.exit(1);
		}
		packDir = Paths.get(packProp).toAbsolutePath();
		rootDir = Paths.get(System.getProperty("vcsTwoPhaseProbe.dir",
			Paths.get("build", "vcs-two-phase-probe").toString())).toAbsolutePath();
		classesDir = rootDir.resolve("classes");
		srcDir = rootDir.resolve("src");
		deleteRecursively(rootDir);
		Files.createDirectories(classesDir);
		Files.createDirectories(srcDir);
		BytecodePack.clear(packDir);
		Files.createDirectories(packDir);
		AlignmentMetaStore.INSTANCE.reset();

		RemoldAgent.addBatchListener(new HotswapBatchListener() {
			@Override
			public void afterInternalBatch(List<ClassDefinition> redefinedClasses) {
				batchCount.incrementAndGet();
				batchLatch.countDown();
			}
		});

		compile("probe/TwoPhaseSubject.java", v1(), true);
		compile("probe/StrictSubject.java", strictV1(), true);
		loader = new URLClassLoader(new URL[]{ classesDir.toUri().toURL() }, VcsTwoPhaseProbe.class.getClassLoader());
		Class<?> cls = Class.forName("probe.TwoPhaseSubject", true, loader);
		Class.forName("probe.StrictSubject", true, loader);
		instance = cls.getDeclaredConstructor().newInstance();
		checkEq("v1", value(), "precondition: v1 loaded and running");

		RemoldAgent.setWatchPaths("vcstwophase", List.of(classesDir), List.of());
		RemoldAgent.setFileShakeMs(300);
		Thread.sleep(400L);

		// ---- T1: two-phase batch (added instance field + lambda deletion) ----
		long stageBBefore = stageBMillis();
		CountDownLatch l1 = arm();
		compile("probe/TwoPhaseSubject.java", v2(), true);
		check(l1.await(15, TimeUnit.SECONDS), "T1: batch must fire after v2 write");
		checkEq("v2", value(), "T1: redefined value visible");
		check(stageBMillis() > stageBBefore,
			"T1: two-phase stage B must actually run (counter advanced from " + stageBBefore
				+ " to " + stageBMillis() + ")");
		checkEq(42, addedValue(), "T1: the pre-existing instance's added field must be patched to 42");

		AlignmentMetaStore.Entry head = AlignmentMetaStore.INSTANCE.head(loader, CLASS);
		check(head != null, "T1: head recorded after the two-phase batch");
		if (head != null) {
			// The double-record guard: stage A must not commit a generation of its own.
			checkEq(1, head.generation, "T1: exactly one generation recorded (not 2)");
		}
		List<BytecodePack.Entry> pack = BytecodePack.readAll(packDir);
		checkEq(1, pack.size(), "T1: exactly one replay entry (stage A must not export its own)");
		if (!pack.isEmpty()) {
			byte[] runtime = RemoldAgent.getRuntimeBytecode("probe.TwoPhaseSubject");
			check(runtime != null && java.util.Arrays.equals(runtime, pack.get(0).jvmEffective),
				"T1: recorded jvmEffective must equal the agent's runtime bytes (stage C bytes)");
			LambdaAlignerReplay.verify(pack.get(0));
		}

		// ---- T2: LAMBDA_STRICT rejection (captured lambda compiled without -g) ----
		// Control: unchanged class reports 15 (a + b with a=5, b=10).
		checkEq(15, strictValue(5), "T2: precondition: StrictSubject v1 behavior (a+b=15)");
		RemoldAgent.LAMBDA_STRICT = true;
		CountDownLatch fenceLatch;
		try {
			compile("probe/StrictSubject.java", strictV2WithoutLvt(), false);
			// 严格拒绝是"空批次"（0 个类到达 JVM），不会触发任何批次回调，也没有完成信号。
			// 用一个确定性围栏代替它：等防抖窗口过后，再写一个合法变更并等它重定义成功；
			// 管线按序处理同一监控目录的事件，围栏批次完成即说明前一个（被拒）批次已经走完。
			Thread.sleep(1500L);
			fenceLatch = arm();
			compile("probe/TwoPhaseSubject.java", v3(), true);
		} finally {
			RemoldAgent.LAMBDA_STRICT = false;
		}
		check(fenceLatch.await(15, TimeUnit.SECONDS), "T2: fence batch (valid change after rejection) must redefine");
		checkEq("v3", value(), "T2: fence batch applies normally after the rejection");
		// The new body would have returned 16; a rejected batch must keep 15.
		checkEq(15, strictValue(5), "T2: the rejected class must keep the previous behavior (not 16)");
		check(AlignmentMetaStore.INSTANCE.head(loader, "probe/StrictSubject") == null,
			"T2: rejected class must never be recorded");
		AlignmentMetaStore.Entry afterReject = AlignmentMetaStore.INSTANCE.head(loader, CLASS);
		check(afterReject != null && afterReject.generation == 2,
			"T2: fence batch advances by exactly one generation (actual="
				+ (afterReject == null ? "null" : afterReject.generation) + ")");
		checkEq(2, BytecodePack.readAll(packDir).size(),
			"T2: exactly the two successful batches exported entries (rejection exported none)");

		System.out.println("\n=== VCS TWO-PHASE PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	/** Offline replay of one recorded entry: byte equality + contract fields. */
	private static final class LambdaAlignerReplay {
		static void verify(BytecodePack.Entry e) {
			LambdaAligner.LAST_RESULT = null;
			byte[] replayed = LambdaAligner.align(e.oldEffective, e.preAlign);
			check(java.util.Arrays.equals(e.alignOutput, replayed),
				"T1-replay: offline replay must equal the recorded alignment output");
			LambdaAligner.AlignmentResult r = LambdaAligner.LAST_RESULT;
			check(r != null && r.inventedNames.equals(e.inventedNames),
				"T1-replay: inventedNames contract");
		}
	}

	private static long stageBMillis() throws Exception {
		Field f = RemoldAgent.class.getDeclaredField("BATCH_STAGE_B_MILLIS");
		f.setAccessible(true);
		return ((AtomicLong) f.get(null)).get();
	}

	private static CountDownLatch arm() {
		CountDownLatch l = new CountDownLatch(1);
		batchLatch = l;
		return l;
	}

	private static String value() throws Exception {
		return String.valueOf(loader.loadClass("probe.TwoPhaseSubject").getMethod("value").invoke(instance));
	}

	private static int strictValue(int a) throws Exception {
		Class<?> strict = loader.loadClass("probe.StrictSubject");
		Object receiver = strict.getDeclaredConstructor().newInstance();
		java.util.function.IntSupplier supplier = (java.util.function.IntSupplier)
			strict.getMethod("create", int.class).invoke(receiver, a);
		return supplier.getAsInt();
	}

	private static Object addedValue() throws Exception {
		Method m = loader.loadClass("probe.TwoPhaseSubject").getMethod("addedValue");
		return m.invoke(instance);
	}

	private static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	private static void checkEq(Object expected, Object actual, String msg) {
		boolean ok = expected == null ? actual == null : expected.equals(actual);
		check(ok, msg + " [expected=" + expected + ", actual=" + actual + "]");
	}

	private static void compile(String relativePath, String source, boolean withLvt) throws Exception {
		Path file = srcDir.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, source, StandardCharsets.UTF_8);
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) throw new IllegalStateException("no system java compiler");
		List<String> args = new ArrayList<>(List.of("-nowarn", "-proc:none", "-encoding", "UTF-8",
			"-d", classesDir.toString()));
		if (withLvt) args.add("-g");
		args.add(file.toString());
		int rc = javac.run(null, null, null, args.toArray(new String[0]));
		if (rc != 0) throw new IllegalStateException("javac failed rc=" + rc);
	}

	private static String v1() {
		return "package probe;\n"
			+ "import java.util.*;\n"
			+ "public class TwoPhaseSubject {\n"
			+ "    private int base = 1;\n"
			+ "    static void a() { System.out.println(\"a\"); }\n"
			+ "    static void b() { System.out.println(\"b\"); }\n"
			+ "    public String value() {\n"
			+ "        List<Runnable> l = new ArrayList<>();\n"
			+ "        l.add(() -> a());\n"
			+ "        l.add(() -> b());\n"
			+ "        l.forEach(Runnable::run);\n"
			+ "        return \"v1\";\n"
			+ "    }\n"
			+ "}\n";
	}

	/** Adds an instance field with a non-constant initializer; deletes lambda a. */
	private static String v2() {
		return "package probe;\n"
			+ "import java.util.*;\n"
			+ "public class TwoPhaseSubject {\n"
			+ "    private int base = 1;\n"
			+ "    private int added = 42;\n"
			+ "    static void b() { System.out.println(\"b\"); }\n"
			+ "    public int addedValue() { return added; }\n"
			+ "    public String value() {\n"
			+ "        List<Runnable> l = new ArrayList<>();\n"
			+ "        l.add(() -> b());\n"
			+ "        l.forEach(Runnable::run);\n"
			+ "        return \"v2\";\n"
			+ "    }\n"
			+ "}\n";
	}

	/** Re-adds lambda a; compiled with -g, so this is the valid "fence" change. */
	private static String v3() {
		return "package probe;\n"
			+ "import java.util.*;\n"
			+ "public class TwoPhaseSubject {\n"
			+ "    private int base = 1;\n"
			+ "    private int added = 42;\n"
			+ "    static void a() { System.out.println(\"a\"); }\n"
			+ "    static void b() { System.out.println(\"b\"); }\n"
			+ "    public int addedValue() { return added; }\n"
			+ "    public String value() {\n"
			+ "        List<Runnable> l = new ArrayList<>();\n"
			+ "        l.add(() -> a());\n"
			+ "        l.add(() -> b());\n"
			+ "        l.forEach(Runnable::run);\n"
			+ "        return \"v3\";\n"
			+ "    }\n"
			+ "}\n";
	}

	private static String strictV1() {
		return "package probe;\n"
			+ "public class StrictSubject {\n"
			+ "    public java.util.function.IntSupplier create(int a) {\n"
			+ "        int b = a * 2;\n"
			+ "        return () -> a + b;\n"
			+ "    }\n"
			+ "}\n";
	}

	/** Same captures, new body (would be 16), compiled without -g: LAMBDA_STRICT must reject it. */
	private static String strictV2WithoutLvt() {
		return "package probe;\n"
			+ "public class StrictSubject {\n"
			+ "    public java.util.function.IntSupplier create(int a) {\n"
			+ "        int b = a * 2;\n"
			+ "        return () -> a + b + 1;\n"
			+ "    }\n"
			+ "}\n";
	}

	private static void deleteRecursively(Path p) throws Exception {
		if (p == null || !Files.exists(p)) return;
		try (var s = Files.walk(p)) {
			for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
				if (!x.equals(p)) Files.deleteIfExists(x);
			}
		}
	}
}
