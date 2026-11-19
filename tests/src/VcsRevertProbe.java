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
 * B1 revert execution probe: real agent + real redefine.
 *
 * <p><b>What it proves</b>: after two committed generations a revert returns structure and method
 * bodies to the target generation, lands as a NEW forward generation, switches the working head so
 * the next ordinary hot update still aligns, and refuses (NOT_REVERTABLE) when the switch is off or
 * the target generation is unknown.</p>
 *
 * <p>Requires a DCEVM-capable JVM with {@code -XX:+AllowEnhancedClassRedefinition} and
 * {@code -Dremold.vcs.revert=true}; run through {@code ./gradlew :tests:vcsRevertProbe -Pp05.jbr=<dir>}.</p>
 */
public final class VcsRevertProbe {

	private static Path classesDir;
	private static Path srcDir;
	private static URLClassLoader loader;
	private static Method valueMethod;
	private static final AtomicInteger batches = new AtomicInteger();
	private static volatile CountDownLatch latch = new CountDownLatch(0);

	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Throwable {
		System.out.println("=== VcsRevertProbe: committed generations -> revert -> next update ===");
		if (RemoldAgent.getInstrumentation() == null) {
			System.err.println("[FAIL] no instrumentation; run with -javaagent");
			System.exit(1);
		}
		if (!Boolean.getBoolean("remold.vcs.enabled")) {
			System.err.println("[FAIL] remold.vcs.enabled must be true");
			System.exit(1);
		}
		Path rootDir = Paths.get(System.getProperty("vcsRevertProbe.dir",
		 Paths.get("build", "vcs-revert-probe").toString())).toAbsolutePath();
		classesDir = rootDir.resolve("classes");
		srcDir = rootDir.resolve("src");
		deleteRecursively(rootDir);
		Files.createDirectories(classesDir);
		Files.createDirectories(srcDir);
		AlignmentMetaStore.INSTANCE.reset();
		ChangesetLog.INSTANCE.reset();

		RemoldAgent.addBatchListener(new HotswapBatchListener() {
			@Override
			public void afterInternalBatch(List<ClassDefinition> redefinedClasses) {
				batches.incrementAndGet();
				latch.countDown();
			}
		});

		compile(source(1));
		loader = new URLClassLoader(new URL[]{classesDir.toUri().toURL()}, VcsRevertProbe.class.getClassLoader());
		Class<?> subject = Class.forName("probe.Subject", true, loader);
		valueMethod = subject.getMethod("value");
		checkEq(1, call(), "precondition: v1 loaded and running");

		RemoldAgent.setWatchPaths("vcsrevertprobe", List.of(classesDir), List.of());
		RemoldAgent.setFileShakeMs(250);
		Thread.sleep(400L);

		// Round 1 -> seq 1 (the first generation also freezes the pre-round bytes as Eff_0).
		check(batch(2), "R1: batch fires after v2 write");
		checkEq(2, call(), "R1: v2 visible");
		long scope = onlyScope();
		Changeset first = ChangesetLog.INSTANCE.head(scope).orElseThrow();
		check(first.seq() == 1, "R1: first commit takes seq 1 (actual=" + first.seq() + ")");
		check(first.eff0().containsKey("probe/Subject"),
		 "R1: the pre-round bytes are frozen as the Eff_0 baseline");

		// Round 2 -> seq 2.
		check(batch(3), "R2: batch fires after v3 write");
		checkEq(3, call(), "R2: v3 visible");
		Changeset second = ChangesetLog.INSTANCE.head(scope).orElseThrow();
		check(second.seq() == 2, "R2: second commit takes seq 2 (actual=" + second.seq() + ")");

		// Negative controls first: they must not touch the chain.
		try {
			System.clearProperty("remold.vcs.revert");
			boolean refused = false;
			try {
				RemoldAgent.revertTo(1L);
			} catch (IllegalStateException e) {
				refused = e.getMessage() != null && e.getMessage().contains("NOT_REVERTABLE");
			}
			check(refused, "N1: revert is refused while -Dremold.vcs.revert is off");
			checkEq(3, call(), "N1: the refused revert leaves the running code at v3");
			check(ChangesetLog.INSTANCE.head(scope).orElseThrow().seq() == 2,
			 "N1: the refused revert leaves the chain at seq 2");
		} finally {
			System.setProperty("remold.vcs.revert", "true");
		}

		boolean unknownRefused = false;
		try {
			RemoldAgent.revertTo(99L);
		} catch (IllegalStateException e) {
			unknownRefused = e.getMessage() != null && e.getMessage().contains("NOT_REVERTABLE");
		}
		check(unknownRefused, "N2: an unknown target generation is refused");

		// The revert itself: bodies must go back to seq 1, and the chain must grow.
		String summary = RemoldAgent.revertTo(1L);
		check(summary.contains("seq=3"), "R3: revert summarises the forward generation (actual=" + summary + ")");
		// seq 1 *applied* v2, so reverting to seq 1 restores v2 — not the head's v3. This is the
		// load-bearing semantic: the target is a generation's applied bytes, not the pre-round bytes
		// (that one is Eff_0 and is covered by ChangesetRevertTest).
		checkEq(2, call(), "R3: the target generation's applied bytes are restored (v2, not the head's v3)");
		Changeset forward = ChangesetLog.INSTANCE.head(scope).orElseThrow();
		check(forward.seq() == 3, "R3: the revert appends seq 3 instead of rewinding (actual=" + forward.seq() + ")");
		check(forward.revertedToSeq() == 1L, "R3: the forward generation records revertedToSeq=1");
		check(forward.parentId() != null && forward.parentId().equals(second.id()),
		 "R3: the forward generation keeps the chain linear (parent = previous head)");

		// D6, asserted directly: the working head must BE the target generation. Without this the
		// next Run R alignment would use the reverted-away bytes as baseline (silently wrong pair).
		AlignmentMetaStore.Entry headAfterRevert = AlignmentMetaStore.INSTANCE.head(loader, "probe/Subject");
		check(headAfterRevert != null && first.entries().get("probe/Subject").effHash()
		 .equals(headAfterRevert.jvmEffSha256),
		 "R3: the working head is switched back to the target generation");

		// The load-bearing part: after the revert the NEXT ordinary update must still work, which
		// requires the AlignmentMetaStore working head to have been switched back to seq 1.
		check(batch(4), "R4: an ordinary update still fires after the revert");
		checkEq(4, call(), "R4: the post-revert update applies (work head was switched back)");
		check(ChangesetLog.INSTANCE.head(scope).orElseThrow().seq() == 4,
		 "R4: the ordinary update continues the chain at seq 4");

		System.out.println("=== VCS REVERT PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	private static long onlyScope() {
		List<Long> scopes = ChangesetLog.INSTANCE.liveScopes();
		if (scopes.size() != 1) throw new IllegalStateException("expected exactly one scope, got " + scopes);
		return scopes.get(0);
	}

	private static boolean batch(int value) throws Exception {
		latch = new CountDownLatch(1);
		int before = batches.get();
		compile(source(value));
		boolean fired = latch.await(15, TimeUnit.SECONDS);
		return fired && batches.get() > before;
	}

	private static int call() throws Exception {
		try {
			return (Integer) valueMethod.invoke(loader.loadClass("probe.Subject").getDeclaredConstructor().newInstance());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String source(int value) {
		return "package probe;\n"
		 + "public class Subject {\n"
		 + "  public int value() { return " + value + "; }\n"
		 + "}\n";
	}

	private static void compile(String source) throws Exception {
		Path file = srcDir.resolve("probe/Subject.java");
		Files.createDirectories(file.getParent());
		Files.write(file, source.getBytes(StandardCharsets.UTF_8));
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) throw new IllegalStateException("no system java compiler");
		int rc = javac.run(null, null, null, "-nowarn", "-proc:none", "-encoding", "UTF-8",
		 "-d", classesDir.toString(), file.toString());
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
