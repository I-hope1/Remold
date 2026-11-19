import remold.HotswapBatchListener;
import remold.RemoldAgent;
import remold.vcs.ChangesetLog;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Retained-heap / OldGen / GC-pause baseline for the VCS side-car accounting
 * ({@code docs/topology/21-b1-gc-baseline-readings.md}).
 *
 * <p>Drives {@code ROUNDS} real hot updates of four mutually dependent classes (each round rewrites
 * one of them, so the batch has to align against a baseline that a previous round produced) and
 * samples, per round: heap used after a settling GC, OldGen occupancy, and the GC pause spent inside
 * the round. Two byte-budget phases run in the same JVM and the same measurement window: the default
 * Eff budget (16 MiB) and a forced-eviction budget of zero, so B2 has a before/after reference that
 * is not confounded by a restart.</p>
 *
 * <p>Every round also asserts the mechanism actually ran (the new constant is visible and the chain
 * advanced by exactly one generation), so a flat metric curve cannot be mistaken for "accounting is
 * cheap" when the accounting never happened.</p>
 */
public final class VcsGcBaselineProbe {

	private static final int PHASE_ROUNDS = 30;
	private static final String[] NAMES = {"A", "B", "C", "D"};

	private static Path classesDir;
	private static Path srcDir;
	private static URLHolder loader;
	private static final AtomicInteger batches = new AtomicInteger();
	private static volatile CountDownLatch latch = new CountDownLatch(0);
	/** Per-class constants, so bumping exactly one of them changes the value the whole graph yields. */
	private static final int[] VERSIONS = {1, 1, 1, 1};
	private static int round;

	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Throwable {
		System.out.println("=== VcsGcBaselineProbe: retained heap / OldGen / GC pause over hot-update rounds ===");
		if (RemoldAgent.getInstrumentation() == null) {
			System.err.println("[FAIL] no instrumentation; run with -javaagent");
			System.exit(1);
		}
		if (!Boolean.getBoolean("remold.vcs.enabled")) {
			System.err.println("[FAIL] remold.vcs.enabled must be true");
			System.exit(1);
		}
		Path rootDir = Paths.get(System.getProperty("vcsGcProbe.dir",
		 Paths.get("build", "vcs-gc-baseline").toString())).toAbsolutePath();
		classesDir = rootDir.resolve("classes");
		srcDir = rootDir.resolve("src");
		deleteRecursively(rootDir);
		Files.createDirectories(classesDir);
		Files.createDirectories(srcDir);
		ChangesetLog.INSTANCE.reset();

		RemoldAgent.addBatchListener(new HotswapBatchListener() {
			@Override
			public void afterInternalBatch(List<ClassDefinition> redefinedClasses) {
				batches.incrementAndGet();
				latch.countDown();
			}
		});

		for (String name : NAMES) compile(name);
		loader = new URLHolder(new java.net.URLClassLoader(new java.net.URL[]{classesDir.toUri().toURL()},
		 VcsGcBaselineProbe.class.getClassLoader()));
		checkEq(sumVersions(), call(), "precondition: the four-class graph is loaded and running");

		RemoldAgent.setWatchPaths("vcsgcprobe", List.of(classesDir), List.of());
		RemoldAgent.setFileShakeMs(150);
		Thread.sleep(300L);

		// Warm-up: the first batches pay class-loading and JIT costs that do not belong to the
		// steady-state reading. Warm-up rounds are not measured.
		for (int i = 0; i < 4; i++) runRound();

		List<Sample> phaseDefault = runPhase("default-16MiB", null);
		List<Sample> phaseZero = runPhase("forced-eviction-0B", "0");

		report("default-16MiB", phaseDefault);
		report("forced-eviction-0B", phaseZero);

		System.out.println("effBytesHeld=" + ChangesetLog.INSTANCE.effBytesHeld()
		 + " effDropped=" + ChangesetLog.INSTANCE.effDroppedCount()
		 + " scopes=" + ChangesetLog.INSTANCE.liveScopes());
		check(ChangesetLog.INSTANCE.effDroppedCount() > 0,
		 "the forced-eviction phase actually dropped Eff bytes (otherwise it is not a second budget)");

		System.out.println("=== VCS GC BASELINE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	private static List<Sample> runPhase(String label, String effBudget) throws Exception {
		if (effBudget == null) {
			System.clearProperty("remold.vcs.eff_bytes");
		} else {
			System.setProperty("remold.vcs.eff_bytes", effBudget);
		}
		int droppedBefore = ChangesetLog.INSTANCE.effDroppedCount();
		List<Sample> samples = new ArrayList<>();
		for (int i = 0; i < PHASE_ROUNDS; i++) {
			Sample sample = runRound();
			sample.droppedDelta = ChangesetLog.INSTANCE.effDroppedCount() - droppedBefore;
			samples.add(sample);
		}
		if ("default-16MiB".equals(label)) {
			check(samples.stream().allMatch(s -> s.droppedDelta == 0),
			 "phase 1 (16 MiB) evicts nothing");
		}
		return samples;
	}

	private static Sample runRound() throws Exception {
		long seqBefore = headSeq();
		long pauseBefore = gcPauseMillis();
		latch = new CountDownLatch(1);
		int before = batches.get();

		round++;
		int rotated = round % NAMES.length;
		VERSIONS[rotated]++;
		compile(NAMES[rotated]);

		if (!latch.await(20, TimeUnit.SECONDS) || batches.get() <= before) {
			throw new IllegalStateException("round did not fire a batch (round=" + round
			 + ", rotated=" + NAMES[rotated] + ")");
		}
		checkEq(sumVersions(), call(), "round " + round + ": the new constant is visible in the dependent graph");
		long seqAfter = headSeq();
		check(seqAfter == seqBefore + 1,
		 "round " + round + ": exactly one generation committed (before=" + seqBefore + ", after=" + seqAfter + ")");

		long pause = gcPauseMillis() - pauseBefore;
		settle();
		MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		MemoryUsage oldGen = oldGen();
		Sample sample = new Sample();
		sample.round = round;
		sample.retainedHeapBytes = heap.getUsed();
		sample.oldGenBytes = oldGen == null ? -1L : oldGen.getUsed();
		sample.pauseMillis = pause;
		sample.effBytesHeld = ChangesetLog.INSTANCE.effBytesHeld();
		return sample;
	}

	private static long headSeq() {
		List<Long> scopes = ChangesetLog.INSTANCE.liveScopes();
		if (scopes.isEmpty()) return 0L;
		return ChangesetLog.INSTANCE.head(scopes.get(0)).map(cs -> cs.seq()).orElse(0L);
	}

	private static MemoryUsage oldGen() {
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			String name = pool.getName();
			if (name.contains("Old") || name.contains("Tenured")) return pool.getUsage();
		}
		return null;
	}

	private static long gcPauseMillis() {
		long total = 0L;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			long time = gc.getCollectionTime();
			if (time > 0) total += time;
		}
		return total;
	}

	/** Two settling collections so the reading is retained heap, not the round's garbage. */
	private static void settle() throws InterruptedException {
		for (int i = 0; i < 2; i++) {
			System.gc();
			Thread.sleep(40L);
		}
	}

	private static void report(String label, List<Sample> samples) {
		long[] heap = samples.stream().mapToLong(s -> s.retainedHeapBytes).sorted().toArray();
		long[] old = samples.stream().mapToLong(s -> s.oldGenBytes).filter(v -> v >= 0).sorted().toArray();
		long[] pause = samples.stream().mapToLong(s -> s.pauseMillis).sorted().toArray();
		long[] eff = samples.stream().mapToLong(s -> s.effBytesHeld).sorted().toArray();
		System.out.println("[READING] " + label + " rounds=" + samples.size()
		 + " retainedHeapMB min/median/max=" + mb(heap)
		 + " oldGenMB min/median/max=" + mb(old)
		 + " gcPauseMs min/median/max=" + num(pause)
		 + " effHeldBytes min/median/max=" + num(eff));
	}

	private static String mb(long[] sorted) {
		if (sorted.length == 0) return "n/a";
		return one(sorted[0]) + "/" + one(sorted[sorted.length / 2]) + "/" + one(sorted[sorted.length - 1]);
	}

	private static String num(long[] sorted) {
		if (sorted.length == 0) return "n/a";
		return sorted[0] + "/" + sorted[sorted.length / 2] + "/" + sorted[sorted.length - 1];
	}

	private static String one(long bytes) {
		return String.format("%.2f", bytes / (1024.0 * 1024.0));
	}

	private static int call() throws Exception {
		Object d = loader.loader.loadClass("probe.D").getDeclaredConstructor().newInstance();
		return (Integer) d.getClass().getMethod("d").invoke(d);
	}

	private static int sumVersions() {
		int sum = 0;
		for (int v : VERSIONS) sum += v;
		return sum;
	}

	private static void compile(String className) throws Exception {
		Path file = srcDir.resolve("probe/" + className + ".java");
		Files.createDirectories(file.getParent());
		Files.write(file, source(className, VERSIONS[indexOf(className)]).getBytes(StandardCharsets.UTF_8));
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) throw new IllegalStateException("no system java compiler");
		// The four classes depend on each other, so the just-compiled siblings must be on the
		// classpath; without it javac fails on the very first dependent class.
		int rc = javac.run(null, null, null, "-nowarn", "-proc:none", "-encoding", "UTF-8",
		 "-classpath", classesDir.toString(), "-d", classesDir.toString(), file.toString());
		if (rc != 0) throw new IllegalStateException("javac failed rc=" + rc + " for " + className);
	}

	/**
	 * Four mutually dependent classes: D→C→B→A, and each node's constant folds in the current
	 * version, so rewriting any one of them changes the value the whole graph produces.
	 */
	private static int indexOf(String className) {
		for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(className)) return i;
		throw new IllegalArgumentException("unknown fixture class " + className);
	}

	private static String source(String className, int v) {
		switch (className) {
			case "A":
				return "package probe;\npublic class A {\n  public int a() { return " + v + "; }\n}\n";
			case "B":
				return "package probe;\npublic class B {\n  public int b() { return new A().a() + " + v + "; }\n}\n";
			case "C":
				return "package probe;\npublic class C {\n  public int c() { return new B().b() + " + v + "; }\n}\n";
			default:
				return "package probe;\npublic class D {\n  public int d() { return new C().c() + " + v + "; }\n}\n";
		}
	}

	private static final class URLHolder {
		final java.net.URLClassLoader loader;

		URLHolder(java.net.URLClassLoader loader) {
			this.loader = loader;
		}
	}

	private static final class Sample {
		int round;
		long retainedHeapBytes;
		long oldGenBytes;
		long pauseMillis;
		long effBytesHeld;
		int droppedDelta;
	}

	private static void check(boolean condition, String what) {
		if (condition) {
			passed++;
			System.out.println("[PASS] " + what);
		} else {
			failed++;
			System.out.println("[FAIL] " + what);
		}
	}

	private static void checkEq(int expected, int actual, String what) {
		check(expected == actual, what + " (expected=" + expected + ", actual=" + actual + ")");
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// probe tear-down is best effort
				}
			});
		}
	}
}
