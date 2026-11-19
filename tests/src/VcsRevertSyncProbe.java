import remold.HotswapBatchListener;
import remold.RemoldAgent;
import remold.vcs.AlignmentMetaStore;
import remold.vcs.Changeset;
import remold.vcs.ChangesetLog;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * B2 revert-synchronisation probe: real agent + real redefine.
 *
 * <p><b>What it proves</b> (each item is the regression lock for one defect):</p>
 * <ol>
 *   <li><b>Cache synchronisation</b>: after a revert the next ordinary hot update must still see the
 *       reverted generation as its baseline. The fixture therefore carries a <b>lambda</b> (so
 *       {@code LambdaAligner} reads {@code bytecodeCache}) and <b>re-adds after the revert a field
 *       that the reverted-away generation already had</b> (so {@code ClassDiffUtil.diff} reads it).
 *       A lambda-free single-class probe cannot fail here: neither consumer touches the cache.</li>
 *   <li><b>{@code Eff_0} target</b>: a class whose first modification lies inside the reverted
 *       interval must be revertable to its pre-generation bytes. It was never committed to
 *       {@code AlignmentMetaStore}, so the generation lookup must not be the gate that decides it,
 *       and the class must end up with <b>no</b> generation identity (next Run R falls back to Run E).</li>
 *   <li><b>Multi-scope atomicity</b>: a revert that cannot be satisfied for one scope must leave every
 *       other scope untouched (no redefine, no forward {@code Changeset}).</li>
 * </ol>
 *
 * <p>Requires a DCEVM-capable JVM with {@code -XX:+AllowEnhancedClassRedefinition} and
 * {@code -Dremold.vcs.revert=true}; run through
 * {@code ./gradlew :tests:vcsRevertSyncProbe -Pp05.jbr=<dir>}.</p>
 */
public final class VcsRevertSyncProbe {

	private static Path classesB;
	private static Path srcB;
	private static Path classesC;
	private static Path srcC;
	private static URLClassLoader loaderB;
	private static URLClassLoader loaderC;
	private static final AtomicInteger batches = new AtomicInteger();
	private static volatile CountDownLatch latch = new CountDownLatch(0);

	/** The instance created right after round 1: it must survive the revert and two more generations. */
	private static Object counterInstance;
	private static Method counterTotal;
	private static Method counterLambda;
	private static Method extraMarker;
	private static Method otherValue;

	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Throwable {
		System.out.println("=== VcsRevertSyncProbe: revert cache sync / Eff_0 target / multi-scope atomicity ===");
		if (RemoldAgent.getInstrumentation() == null) {
			System.err.println("[FAIL] no instrumentation; run with -javaagent");
			System.exit(1);
		}
		if (!Boolean.getBoolean("remold.vcs.enabled") || !Boolean.getBoolean("remold.vcs.revert")) {
			System.err.println("[FAIL] remold.vcs.enabled and remold.vcs.revert must both be true");
			System.exit(1);
		}
		Path rootDir = Paths.get(System.getProperty("vcsRevertSyncProbe.dir",
		 Paths.get("build", "vcs-revert-sync-probe").toString())).toAbsolutePath();
		classesB = rootDir.resolve("b");
		srcB = rootDir.resolve("srcB");
		classesC = rootDir.resolve("c");
		srcC = rootDir.resolve("srcC");
		deleteRecursively(rootDir);
		Files.createDirectories(classesB);
		Files.createDirectories(srcB);
		Files.createDirectories(classesC);
		Files.createDirectories(srcC);
		AlignmentMetaStore.INSTANCE.reset();
		ChangesetLog.INSTANCE.reset();

		RemoldAgent.addBatchListener(new HotswapBatchListener() {
			@Override
			public void afterInternalBatch(List<ClassDefinition> redefinedClasses) {
				batches.incrementAndGet();
				latch.countDown();
			}
		});

		// G1: no `bonus` field at all, so a later generation that has it is a real "added field".
		compileB(counterSource(1, false), extraSource(1, false));
		compileC(otherSource(10));
		loaderB = new URLClassLoader(new URL[]{classesB.toUri().toURL()}, VcsRevertSyncProbe.class.getClassLoader());
		loaderC = new URLClassLoader(new URL[]{classesC.toUri().toURL()}, VcsRevertSyncProbe.class.getClassLoader());
		// Fix the scope order explicitly: scope ids are assigned on first use, and the multi-scope
		// atomicity case below is only meaningful when the *satisfiable* scope is processed first.
		long scopeB = AlignmentMetaStore.INSTANCE.loaderScopeId(loaderB);
		long scopeC = AlignmentMetaStore.INSTANCE.loaderScopeId(loaderC);
		check(scopeB < scopeC, "scope B is ordered before scope C (" + scopeB + " < " + scopeC + ")");
		Class<?> counter = Class.forName("probe.Counter", true, loaderB);
		Class<?> extra = Class.forName("probe.Extra", true, loaderB);
		Class<?> other = Class.forName("probe.Other", true, loaderC);
		counterTotal = counter.getMethod("total");
		counterLambda = counter.getMethod("viaLambda");
		extraMarker = extra.getMethod("marker");
		otherValue = other.getMethod("value");
		checkEq(2, invokeInt(counterTotal, counter), "precondition: G1 Counter.total() == 2");
		checkEq(2, invokeInt(counterLambda, counter), "precondition: G1 Counter.viaLambda() == 2 (factor 1)");
		checkEq(1, invokeInt(extraMarker, extra), "precondition: G1 Extra.marker() == 1");
		checkEq(10, invokeInt(otherValue, other), "precondition: G1 Other.value() == 10");

		RemoldAgent.setWatchPaths("vcsrevertsyncprobe", List.of(classesB, classesC), List.of());
		RemoldAgent.setFileShakeMs(250);
		Thread.sleep(400L);

		// ---- Round 1 -> seq 1 in both scopes (Extra deliberately does not change) ----
		check(batch(counterSource(2, false), null, otherSource(11)), "R1: batch fires for both scopes");
		checkEq(2, invokeInt(counterTotal, counter), "R1: Counter.total() == 2");
		checkEq(4, invokeInt(counterLambda, counter), "R1: the lambda follows the seq-1 source (2 * 2 = 4)");
		// The surviving instance is created against the seq-1 generation, i.e. with no `bonus` field.
		counterInstance = counter.getDeclaredConstructor().newInstance();
		check(ChangesetLog.INSTANCE.head(scopeB).orElseThrow().seq() == 1, "R1: scope B is at seq 1");

		// ---- Round 2 -> seq 2: Counter adds `bonus`, Extra gains its first field, scope C advances ----
		// Scope C needs a second generation so that reverting it to seq 1 is real work, not a noop:
		// a revert to the current head never has a target to validate, which would hide the atomicity case.
		check(batch(counterSource(2, true, 9), extraSource(0, true), otherSource(12)),
		 "R2: batch fires for the field-adding generation");
		checkEq(11, invokeInt(counterTotal, counterInstance),
		 "R2: the added field is initialised on the surviving instance (2 + 9)");
		checkEq(22, invokeInt(counterLambda, counterInstance),
		 "R2: the lambda follows seq 2 (11 * 2 = 22)");
		checkEq(45, invokeInt(extraMarker, extra), "R2: Extra gained its field (41 + 4)");
		Changeset head2 = ChangesetLog.INSTANCE.head(scopeB).orElseThrow();
		check(head2.seq() == 2, "R2: scope B is at seq 2 (actual=" + head2.seq() + ")");
		check(ChangesetLog.INSTANCE.head(scopeC).orElseThrow().seq() == 2,
		 "R2: scope C advanced to seq 2");
		check(extraHasGeneration(), "R2: Extra has a recorded generation before the revert");

		// ---- The real revert to seq 1: generation target (Counter) + Eff_0 target (Extra) ----
		String summary = RemoldAgent.revertTo(1L);
		checkEq(2, invokeInt(counterTotal, counterInstance),
		 "R3: Counter is back on the seq-1 applied bytes (2, not the seq-2 11)");
		checkEq(4, invokeInt(counterLambda, counterInstance),
		 "R3: the lambda is back on the seq-1 body (2 * 2 = 4)");
		checkEq(1, invokeInt(extraMarker, extra), "R3: Extra is back on its Eff_0 bytes (1, not 45)");
		checkEq(11, invokeInt(otherValue, other),
		 "R3: the revert covers every live scope, so scope C goes back to its seq-1 bytes too");
		Changeset forward = ChangesetLog.INSTANCE.head(scopeB).orElseThrow();
		check(forward.seq() == 3 && forward.revertedToSeq() == 1L,
		 "R3: the revert lands as forward seq 3 with revertedToSeq=1 (actual seq=" + forward.seq()
		  + ", revertedTo=" + forward.revertedToSeq() + ", summary=" + summary + ")");
		AlignmentMetaStore.Entry counterHead = AlignmentMetaStore.INSTANCE.head(loaderB, "probe/Counter");
		Changeset.Entry seq2Counter = head2.entries().get("probe/Counter");
		check(counterHead != null && seq2Counter != null,
		 "R3: Counter keeps a generation identity after the revert");
		check(counterHead != null && seq2Counter != null
		 && !counterHead.jvmEffSha256.equals(seq2Counter.effHash()),
		 "R3: Counter's working head was switched back (head is no longer the reverted-away generation)");
		check(!extraHasGeneration(),
		 "R3: Extra has NO generation identity left (Eff_0 is not a restoreHead)");

		// ---- The load-bearing part: an ordinary update after the revert, adding `bonus` back ----
		check(batch(counterSource(2, true, 5), extraSource(2, false), null),
		 "R4: an ordinary update still fires after the revert");
		checkEq(7, invokeInt(counterTotal, counterInstance),
		 "R4: the re-added field is initialised on the surviving instance (2 + 5 = 7, not a skipped"
		  + " write caused by diffing against the reverted-away generation)");
		checkEq(14, invokeInt(counterLambda, counterInstance),
		 "R4: the lambda follows the new source (7 * 2 = 14)");
		checkEq(2, invokeInt(extraMarker, extra),
		 "R4: Extra's post-revert update applies (Run E fallback after Eff_0)");
		Changeset afterRevert = ChangesetLog.INSTANCE.head(scopeB).orElseThrow();
		check(afterRevert.seq() == 4,
		 "R4: the ordinary update continues the chain at seq 4 (actual=" + afterRevert.seq() + ")");

		// ---- Multi-scope atomicity: scope C's target generation is gone, so nothing may move ----
		// Emulates history eviction: the reverted-to generation is no longer addressable in scope C.
		int droppedC = AlignmentMetaStore.INSTANCE.forgetGenerations(loaderC, "probe/Other");
		check(droppedC > 0, "A1: precondition - scope C's target generation is no longer addressable");
		long headBefore = ChangesetLog.INSTANCE.head(scopeB).orElseThrow().seq();
		long headCBefore = ChangesetLog.INSTANCE.head(scopeC).orElseThrow().seq();
		boolean refused = false;
		try {
			RemoldAgent.revertTo(1L);
		} catch (IllegalStateException e) {
			refused = e.getMessage() != null && e.getMessage().contains("NOT_REVERTABLE");
		}
		check(refused, "A1: a revert that cannot satisfy every scope is refused (NOT_REVERTABLE)");
		checkEq(7, invokeInt(counterTotal, counterInstance),
		 "A1: the refused revert leaves scope B on its seq-4 bytes (no partial redefine)");
		checkEq(14, invokeInt(counterLambda, counterInstance),
		 "A1: the refused revert leaves scope B's lambda on the seq-4 body");
		checkEq(2, invokeInt(extraMarker, extra),
		 "A1: the refused revert leaves Extra on its seq-4 bytes");
		check(ChangesetLog.INSTANCE.head(scopeB).orElseThrow().seq() == headBefore,
		 "A1: the refused revert appends no forward Changeset in scope B");
		check(ChangesetLog.INSTANCE.head(scopeC).orElseThrow().seq() == headCBefore,
		 "A1: the refused revert appends no forward Changeset in scope C");

		System.out.println("=== VCS REVERT SYNC PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	/** True when {@code Extra} still has a generation identity in {@code AlignmentMetaStore}. */
	private static boolean extraHasGeneration() {
		return AlignmentMetaStore.INSTANCE.head(loaderB, "probe/Extra") != null
		 || !AlignmentMetaStore.INSTANCE.history(loaderB, "probe/Extra").isEmpty();
	}

	/**
	 * Compile one round. A {@code null} source means "leave that class untouched this round", so it
	 * produces no generation (which is what makes a class eligible for an {@code Eff_0} target).
	 */
	private static boolean batch(String counterSrc, String extraSrc, String otherSrc) throws Exception {
		latch = new CountDownLatch(1);
		int before = batches.get();
		if (counterSrc != null || extraSrc != null) {
			compileB(counterSrc != null ? counterSrc : readSource(srcB, "probe/Counter.java"),
			 extraSrc != null ? extraSrc : readSource(srcB, "probe/Extra.java"));
		}
		if (otherSrc != null) compileC(otherSrc);
		boolean fired = latch.await(20, TimeUnit.SECONDS);
		return fired && batches.get() > before;
	}

	private static int invokeInt(Method method, Object target) throws Exception {
		Object value = method.invoke(target);
		return value instanceof Integer i ? i : -1;
	}

	private static int invokeInt(Method method, Class<?> owner) throws Exception {
		return invokeInt(method, owner.getDeclaredConstructor().newInstance());
	}

	private static String readSource(Path dir, String relative) throws IOException {
		return Files.readString(dir.resolve(relative), StandardCharsets.UTF_8);
	}

	/**
	 * Counter carries a lambda so {@code LambdaAligner.align} runs on every generation: its old side
	 * comes from {@code bytecodeCache}, exactly the table a revert must synchronise.
	 *
	 * @param factor    lambda multiplier of this generation
	 * @param withBonus whether the {@code bonus} field exists in this generation
	 * @param bonus     initialiser of {@code bonus} when it exists
	 */
	private static String counterSource(int factor, boolean withBonus) {
		return counterSource(factor, withBonus, 9);
	}

	private static String counterSource(int factor, boolean withBonus, int bonus) {
		StringBuilder sb = new StringBuilder();
		sb.append("package probe;\n")
		 .append("import java.util.function.Supplier;\n")
		 .append("public class Counter {\n")
		 .append("  private int base = 1;\n");
		if (withBonus) sb.append("  private int bonus = ").append(bonus).append(";\n");
		sb.append("  public int total() { return base * 2")
		 .append(withBonus ? " + bonus" : "").append("; }\n")
		 .append("  public int viaLambda() { Supplier<Integer> s = () -> total() * ").append(factor)
		 .append("; return s.get(); }\n")
		 .append("}\n");
		return sb.toString();
	}

	/** {@code Extra} is untouched in round 1, so its first modification is inside the reverted interval. */
	private static String extraSource(int base, boolean withField) {
		return "package probe;\n"
		 + "public class Extra {\n"
		 + (withField ? "  private int flag = 4;\n" : "")
		 + "  public int marker() { return " + base + (withField ? " + 41 + flag" : "") + "; }\n"
		 + "}\n";
	}

	private static String otherSource(int value) {
		return "package probe;\n"
		 + "public class Other {\n"
		 + "  public int value() { return " + value + "; }\n"
		 + "}\n";
	}

	private static void compileB(String counterSrc, String extraSrc) throws Exception {
		compile(new String[]{"probe/Counter.java", counterSrc, "probe/Extra.java", extraSrc}, srcB, classesB);
	}

	private static void compileC(String otherSrc) throws Exception {
		compile(new String[]{"probe/Other.java", otherSrc}, srcC, classesC);
	}

	private static void compile(String[] pairs, Path srcDir, Path outDir) throws Exception {
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) throw new IllegalStateException("no system java compiler");
		for (int i = 0; i < pairs.length; i += 2) {
			Path file = srcDir.resolve(pairs[i]);
			Files.createDirectories(file.getParent());
			Files.write(file, pairs[i + 1].getBytes(StandardCharsets.UTF_8));
		}
		String[] args = new String[pairs.length / 2 + 6];
		int n = 0;
		args[n++] = "-nowarn";
		args[n++] = "-proc:none";
		args[n++] = "-encoding";
		args[n++] = "UTF-8";
		args[n++] = "-d";
		args[n++] = outDir.toString();
		for (int i = 0; i < pairs.length; i += 2) args[n++] = srcDir.resolve(pairs[i]).toString();
		int rc = javac.run(null, null, null, args);
		if (rc != 0) throw new IllegalStateException("javac failed rc=" + rc);
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
