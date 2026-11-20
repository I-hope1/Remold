import remold.HotswapBatchListener;
import remold.RemoldAgent;
import remold.vcs.AlignmentMetaStore;
import remold.vcs.Changeset;
import remold.vcs.ChangesetLog;
import remold.vcs.GenerationalFieldLedger;

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
 * ({@code docs/vcs/06-b1-gc-baseline-readings.md}).
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

	/**
	 * Ledger-filled mode ({@code -DvcsGcProbe.ledger=true}): an extra anonymous-subclass fixture drifts one
	 * inherited field's initialiser <b>every</b> round, so each generation carries a real {@code L1}
	 * {@code replayFacts} entry and the generational ledger is actually populated. Without it the four-class
	 * graph produces no facts at all and the reading says nothing about the ledger's own cost
	 * ({@code docs/vcs/06-b1-gc-baseline-readings.md} §8 boundary).
	 */
	private static boolean ledgerMode;
	/** Initialiser written by the anonymous subclass' instance-initialiser block, bumped every round. */
	private static int anonValue = 1;
	private static Object anonSurvivor;
	private static java.lang.reflect.Method anonAccessor;

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

		ledgerMode = Boolean.getBoolean("vcsGcProbe.ledger");
		for (String name : NAMES) compile(name);
		if (ledgerMode) {
			compileAnonFixture();
			GenerationalFieldLedger.INSTANCE.reset();
		}
		loader = new URLHolder(new java.net.URLClassLoader(new java.net.URL[]{classesDir.toUri().toURL()},
		 VcsGcBaselineProbe.class.getClassLoader()));
		checkEq(sumVersions(), call(), "precondition: the four-class graph is loaded and running");
		if (ledgerMode) {
			Class<?> host = loader.loader.loadClass("probe.Host");
			anonSurvivor = host.getMethod("make").invoke(host.getDeclaredConstructor().newInstance());
			anonAccessor = anonSurvivor.getClass().getSuperclass().getMethod("fValue");
			checkEq(anonValue, anonField(), "precondition: the replay fixture starts on its first initialiser");
		}

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

		if (ledgerMode) {
			checkLoaderScopeRelease();
			System.out.println("ledgerCounters=" + GenerationalFieldLedger.INSTANCE.counters()
			 + " ledgerRows=" + ledgerRows() + " liveLoaders="
			 + GenerationalFieldLedger.INSTANCE.liveLoaderCount());
		}

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
		if (ledgerMode) {
			// Convergence, stated as a bound instead of "looks flat": 30 rounds of real facts must not move
			// retained heap by more than a fraction of a megabyte, and the index must stay window-bounded.
			long[] heap = samples.stream().mapToLong(s -> s.retainedHeapBytes).sorted().toArray();
			double spreadMb = (heap[heap.length - 1] - heap[0]) / (1024.0 * 1024.0);
			check(spreadMb <= 1.0,
			 label + ": retained heap stays inside a 1 MB band across " + samples.size()
			  + " ledger-filled rounds (spread=" + String.format("%.2f", spreadMb) + " MB)");
			double firstHalf = medianMb(samples, 0, samples.size() / 2);
			double secondHalf = medianMb(samples, samples.size() / 2, samples.size());
			check(Math.abs(secondHalf - firstHalf) <= 0.5,
			 label + ": the second half does not drift from the first (median "
			  + String.format("%.2f", firstHalf) + " -> " + String.format("%.2f", secondHalf) + " MB)");
			long firstFacts = samples.get(0).acceptedFacts;
			long lastFacts = samples.get(samples.size() - 1).acceptedFacts;
			check(lastFacts > firstFacts,
			 label + ": the ledger really fills (" + firstFacts + " -> " + lastFacts + " accepted facts)");
			int window = Integer.getInteger("remold.vcs.changeset_window", 32);
			check(ledgerRows() <= window,
			 label + ": the fact index stays window-bounded (" + ledgerRows() + " rows, window="
			  + window + " of " + lastFacts + " recorded facts)");
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

		long factsBefore = ledgerMode ? acceptedFacts() : 0L;
		if (ledgerMode) {
			// A second, unrelated class change in the same batch: one more generation, one more fact.
			anonValue++;
			compileAnonHost();
		}

		if (!latch.await(20, TimeUnit.SECONDS) || batches.get() <= before) {
			throw new IllegalStateException("round did not fire a batch (round=" + round
			 + ", rotated=" + NAMES[rotated] + ")");
		}
		checkEq(sumVersions(), call(), "round " + round + ": the new constant is visible in the dependent graph");
		long seqAfter = headSeq();
		check(seqAfter == seqBefore + 1,
		 "round " + round + ": exactly one generation committed (before=" + seqBefore + ", after=" + seqAfter + ")");
		if (ledgerMode) {
			long grew = acceptedFacts() - factsBefore;
			check(grew >= 1L, "round " + round + ": the generation really carried a replay fact (+" + grew
			 + " accepted ledger entries, total=" + acceptedFacts() + ")");
			checkEq(anonValue, anonField(),
			 "round " + round + ": the L1 replay wrote the drifted initialiser into the live instance");
		}

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
		sample.acceptedFacts = ledgerMode ? acceptedFacts() : 0L;
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
		 + " effHeldBytes min/median/max=" + num(eff)
		 + (ledgerMode
		    ? " acceptedFacts min/median/max=" + num(samples.stream()
		       .mapToLong(s -> s.acceptedFacts).sorted().toArray())
		      + " ledgerRowsAtEnd=" + ledgerRows()
		    : ""));
	}

	private static double medianMb(List<Sample> samples, int from, int to) {
		long[] heap = samples.subList(from, to).stream().mapToLong(s -> s.retainedHeapBytes).sorted().toArray();
		return heap.length == 0 ? 0.0 : heap[heap.length / 2] / (1024.0 * 1024.0);
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

	/** Install the replay fixture: {@code Base} declares the field, the anonymous subclass drifts it. */
	private static void compileAnonFixture() throws Exception {
		writeSource("Base.java", "package probe;\n"
		 + "public class Base {\n"
		 + "  public int f = 0;\n"
		 + "  public int fValue() { return f; }\n"
		 + "}\n");
		writeSource("Host.java", "package probe;\n"
		 + "public class Host {\n"
		 + "  public Base make() { return new Base() {{ f = " + anonValue + "; }}; }\n"
		 + "}\n");
		// First round compiles both (Host needs Base on the classpath); later rounds rewrite Host only.
		compileFiles("Base.java", "Host.java");
	}

	/** Rewrite only {@code Host}: {@code Base} must stay byte-identical or the hierarchy lookup fails closed. */
	private static void compileAnonHost() throws Exception {
		writeSource("Host.java", "package probe;\n"
		 + "public class Host {\n"
		 + "  public Base make() { return new Base() {{ f = " + anonValue + "; }}; }\n"
		 + "}\n");
		compileFile("Host.java");
	}

	private static void writeSource(String name, String text) throws Exception {
		Path file = srcDir.resolve("probe/" + name);
		Files.createDirectories(file.getParent());
		Files.write(file, text.getBytes(StandardCharsets.UTF_8));
	}

	private static void compileFile(String name) throws Exception {
		compileFiles(name);
	}

	private static void compileFiles(String... names) throws Exception {
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) throw new IllegalStateException("no system java compiler");
		List<String> args = new ArrayList<>(List.of("-nowarn", "-proc:none", "-encoding", "UTF-8",
		 "-classpath", classesDir.toString(), "-d", classesDir.toString()));
		for (String name : names) args.add(srcDir.resolve("probe/" + name).toString());
		int rc = javac.run(null, null, null, args.toArray(new String[0]));
		if (rc != 0) throw new IllegalStateException("javac failed rc=" + rc + " for " + List.of(names));
	}

	private static int anonField() throws Exception {
		return (Integer) anonAccessor.invoke(anonSurvivor);
	}

	private static long acceptedFacts() {
		Long accepted = GenerationalFieldLedger.INSTANCE.counters().get("accepted");
		return accepted == null ? 0L : accepted;
	}

	/** Rows still indexed for the replayed field: bounded by the changeset window, not by the round count. */
	private static int ledgerRows() {
		ClassLoader scopeLoader = loader.loader;
		List<GenerationalFieldLedger.Fact> facts =
		 GenerationalFieldLedger.INSTANCE.facts(scopeLoader, "probe/Base", "f");
		return facts.size();
	}

	/** One probe-only scope, released at the end: proves an unloaded loader does not stay retained. */
	private static void checkLoaderScopeRelease() throws Exception {
		System.out.println("[SECTION] ledger scope release");
		int loadersBefore = GenerationalFieldLedger.INSTANCE.liveLoaderCount();
		java.lang.ref.WeakReference<ClassLoader> released = recordOnThrowawayLoader();
		// Control: the main loader is strongly held, so its slot must survive the very same GC.
		int mainFacts = ledgerRows();
		for (int i = 0; i < 4 && released.get() != null; i++) {
			System.gc();
			Thread.sleep(60L);
		}
		check(released.get() == null,
		 "the unloaded loader is collected (WeakReference cleared) once nothing else holds it");
		check(GenerationalFieldLedger.INSTANCE.liveLoaderCount() < loadersBefore + 1
		 || released.get() == null,
		 "its ledger slot goes with it (liveLoaderCount=" + GenerationalFieldLedger.INSTANCE.liveLoaderCount()
		  + ", before=" + loadersBefore + ")");
		check(ledgerRows() == mainFacts,
		 "the strongly-held loader keeps its rows across the same GC (" + mainFacts + " rows) - the"
		  + " assertion above can tell 'collected' from 'never recorded'");
	}

	/** Record one fact on a loader nobody keeps, and hand back only a weak reference to that loader. */
	private static java.lang.ref.WeakReference<ClassLoader> recordOnThrowawayLoader() {
		ClassLoader throwaway = new ClassLoader(null) { };
		long scope = AlignmentMetaStore.INSTANCE.loaderScopeId(throwaway);
		Changeset.ReplayFact fact = new Changeset.ReplayFact("probe.Base", "f", "I", "L1",
		 "1", "2", "INT", "", "", "");
		GenerationalFieldLedger.INSTANCE.record(throwaway,
		 new GenerationalFieldLedger.LedgerKey(scope, "probe/Base", "f"),
		 new GenerationalFieldLedger.GenRef(1L, "scope-release-probe"), fact,
		 Changeset.currentAlgoVersion());
		GenerationalFieldLedger.INSTANCE.markUnclosed(throwaway, "probe/Base", "f");
		return new java.lang.ref.WeakReference<>(throwaway);
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
		long acceptedFacts;
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
