import remold.HotswapBatchListener;
import remold.LambdaAligner;
import remold.RemoldAgent;
import remold.vcs.AlignmentMetaStore;
import remold.vcs.BytecodePack;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PR1 end-to-end probe: real agent + real file watching + real JVM redefine, then replay the
 * exported pack offline and assert byte-identical alignment.
 *
 * <p>Scenarios (see {@code docs/topology/16-bytecode-changeset-vcs.md} §7.3):</p>
 * <ol>
 *   <li><b>record on</b>: one lambda-deleting hot swap must produce exactly one pack entry, and
 *       the redefined class must report the new value (the record must not disturb the redefine).</li>
 *   <li><b>record off</b>: the next hot swap must still succeed and add no pack entry
 *       (control: the side channel is off the main path).</li>
 *   <li><b>offline replay</b>: for every recorded entry,
 *       {@code align(oldEffective, preAlign)} must equal {@code alignOutput} byte-for-byte, the
 *       contract fields ({@code inventedNames}/{@code methodMapping}/{@code bridgeCount}) must
 *       match, and the recorded JVM-effective bytes must equal the alignment output.</li>
 * </ol>
 *
 * <p>Run: {@code ./gradlew :tests:vcsPackProbe -Pp05.jbr=<DCEVM JDK>} (not wired into {@code check}).</p>
 */
public final class VcsPackProbe {

	private static int passed;
	private static int failed;

	private static Path rootDir;
	private static Path classesDir;
	private static Path srcDir;
	private static Path packDir;
	private static URLClassLoader loader;
	private static Method valueMethod;

	private static volatile CountDownLatch batchLatch = new CountDownLatch(0);
	private static final AtomicInteger batchCount = new AtomicInteger();

	public static void main(String[] args) throws Throwable {
		System.out.println("=== VcsPackProbe: record -> export -> offline replay ===");
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
		rootDir = Paths.get(System.getProperty("vcsProbe.dir",
			Paths.get("build", "vcs-pack-probe").toString())).toAbsolutePath();
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

		compile(v1());
		loader = new URLClassLoader(new URL[]{ classesDir.toUri().toURL() }, VcsPackProbe.class.getClassLoader());
		Class<?> subject = Class.forName("probe.Subject", true, loader);
		valueMethod = subject.getMethod("value");
		checkEq("v1", call(), "precondition: v1 loaded and running");

		RemoldAgent.setWatchPaths("vcsprobe", List.of(classesDir), List.of());
		RemoldAgent.setFileShakeMs(300);
		Thread.sleep(400L);

		// --- R1: record on, delete a lambda -> one pack entry ---
		CountDownLatch l1 = arm();
		compile(v2());
		check(l1.await(10, TimeUnit.SECONDS), "R1: batch must fire after v2 write");
		checkEq("v2", call(), "R1: redefined value visible");
		List<BytecodePack.Entry> afterR1 = BytecodePack.readAll(packDir);
		check(afterR1.size() == 1, "R1: exactly one pack entry (actual=" + afterR1.size() + ")");
		AlignmentMetaStore.Entry headR1 = AlignmentMetaStore.INSTANCE.head(loader, "probe/Subject");
		check(headR1 != null, "R1: meta head recorded for the class");
		if (headR1 != null) {
			check(headR1.generation == 1, "R1: head generation starts at 1");
			check(!headR1.ghostHashes.isEmpty(),
				"R1: the deleted lambda must be recorded as a ghost with its pre-death hash");
		}

		// --- R2: record off -> redefine still works, no new entry ---
		System.clearProperty(BytecodePack.DIR_PROPERTY);
		try {
			CountDownLatch l2 = arm();
			compile(v3());
			check(l2.await(10, TimeUnit.SECONDS), "R2: batch must fire after v3 write");
			checkEq("v3", call(), "R2: redefined value visible with recording off");
		} finally {
			System.setProperty(BytecodePack.DIR_PROPERTY, packDir.toString());
		}
		List<BytecodePack.Entry> afterR2 = BytecodePack.readAll(packDir);
		check(afterR2.size() == afterR1.size(),
			"R2: recording off must not add entries (before=" + afterR1.size()
				+ ", after=" + afterR2.size() + ")");

		// --- R3: offline replay of the recorded entry ---
		check(!afterR1.isEmpty(), "R3: precondition: one recorded entry exists");
		for (BytecodePack.Entry e : afterR1) {
			LambdaAligner.LAST_RESULT = null;
			byte[] replayed = LambdaAligner.align(e.oldEffective, e.preAlign);
			check(replayed != null, "R3[" + e.className + "]: replay produced bytes");
			if (replayed == null) continue;
			check(java.util.Arrays.equals(e.alignOutput, replayed),
				"R3[" + e.className + "]: replay must be byte-identical to the recorded alignment output");
			check(java.util.Arrays.equals(e.jvmEffective, e.alignOutput),
				"R3[" + e.className + "]: recorded JVM-effective bytes must equal the alignment output");
			LambdaAligner.AlignmentResult r = LambdaAligner.LAST_RESULT;
			check(r != null, "R3[" + e.className + "]: replay published a result");
			if (r != null) {
				checkEq(e.inventedNames, r.inventedNames, "R3[" + e.className + "]: inventedNames contract");
				checkEq(e.methodMapping, r.methodMapping, "R3[" + e.className + "]: methodMapping contract");
				check(e.bridgeCount == r.bridgeCount, "R3[" + e.className + "]: bridgeCount contract");
			}
		}

		System.out.println("\n=== VCS PACK PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	private static CountDownLatch arm() {
		CountDownLatch l = new CountDownLatch(1);
		batchLatch = l;
		return l;
	}

	private static String call() throws Exception {
		return String.valueOf(valueMethod.invoke(loader.loadClass("probe.Subject").getDeclaredConstructor().newInstance()));
	}

	private static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	private static void checkEq(Object expected, Object actual, String msg) {
		boolean ok = expected == null ? actual == null : expected.equals(actual);
		check(ok, msg + " [expected=" + expected + ", actual=" + actual + "]");
	}

	private static void compile(String source) throws Exception {
		Path file = srcDir.resolve("probe/Subject.java");
		Files.createDirectories(file.getParent());
		Files.writeString(file, source, StandardCharsets.UTF_8);
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) throw new IllegalStateException("no system java compiler");
		int rc = javac.run(null, null, null, "-nowarn", "-proc:none", "-encoding", "UTF-8",
			"-d", classesDir.toString(), file.toString());
		if (rc != 0) throw new IllegalStateException("javac failed rc=" + rc);
	}

	private static String v1() {
		return "package probe;\n"
			+ "import java.util.*;\n"
			+ "public class Subject {\n"
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

	private static String v2() {
		return "package probe;\n"
			+ "import java.util.*;\n"
			+ "public class Subject {\n"
			+ "    static void b() { System.out.println(\"b\"); }\n"
			+ "    public String value() {\n"
			+ "        List<Runnable> l = new ArrayList<>();\n"
			+ "        l.add(() -> b());\n"
			+ "        l.forEach(Runnable::run);\n"
			+ "        return \"v2\";\n"
			+ "    }\n"
			+ "}\n";
	}

	private static String v3() {
		return "package probe;\n"
			+ "import java.util.*;\n"
			+ "public class Subject {\n"
			+ "    static void a() { System.out.println(\"a\"); }\n"
			+ "    static void b() { System.out.println(\"b\"); }\n"
			+ "    public String value() {\n"
			+ "        List<Runnable> l = new ArrayList<>();\n"
			+ "        l.add(() -> a());\n"
			+ "        l.add(() -> b());\n"
			+ "        l.forEach(Runnable::run);\n"
			+ "        return \"v3\";\n"
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
