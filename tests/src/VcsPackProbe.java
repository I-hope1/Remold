import remold.HotswapBatchListener;
import remold.LambdaAligner;
import remold.RemoldAgent;
import remold.vcs.AlignmentMetaStore;
import remold.vcs.BytecodePack;
import remold.vcs.Changeset;
import remold.vcs.ChangesetLog;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *   <li><b>chain export by seq</b> (R4..R6): three more successive real redefine rounds each commit
 *       one {@link Changeset}; {@link BytecodePack#exportRound(Changeset)} must write one
 *       {@code seq-<n>/} directory per round with its manifest, {@link BytecodePack#chainRounds()}
 *       must return exactly {@code [1, 2, 3]}, every round's manifest {@code parentId} must equal the
 *       previous round's {@code changesetId} (whose manifest {@code seq} is {@code n-1}), and
 *       replaying seq 2 read from disk <em>after</em> seq 3 was exported must still reproduce that
 *       round's bytes byte-exactly.</li>
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

		// --- R4..R6: chain export keyed by committed seq ---
		// A fresh change log so the exported chain starts at seq 1; the pack dir keeps its legacy
		// single-round entries (that layout must keep working alongside the chain layout).
		ChangesetLog.INSTANCE.reset();
		long scope = AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		String[] chainValues = { "v4", "v5", "v6" };
		List<Long> exportedSeqs = new ArrayList<>();
		List<byte[]> seq2ReplayAtExport = new ArrayList<>();
		Changeset seq2 = null;
		for (int i = 0; i < chainValues.length; i++) {
			long wantSeq = i + 1L;
			String tag = "R" + (4 + i);
			CountDownLatch latch = arm();
			compile(chainSource(chainValues[i]));
			check(latch.await(10, TimeUnit.SECONDS), tag + ": batch must fire after " + chainValues[i] + " write");
			checkEq(chainValues[i], call(), tag + ": redefined value visible");
			Changeset cs = awaitHead(scope, wantSeq);
			check(cs != null, tag + ": a changeset with seq " + wantSeq + " must be committed");
			if (cs == null) break;
			checkEq(wantSeq, cs.seq(), tag + ": committed seq");
			check(BytecodePack.exportRound(cs),
				tag + ": exportRound(cs) must write seq-" + wantSeq + "/");
			Optional<BytecodePack.Round> readBack = BytecodePack.round(wantSeq);
			check(readBack.isPresent(), tag + ": round(" + wantSeq + ") readable from disk");
			if (readBack.isEmpty()) continue;
			BytecodePack.Round round = readBack.get();
			exportedSeqs.add(round.seq());
			checkEq(1, round.entries().size(), tag + ": exported round must hold this round's one entry");
			checkEq(cs.id(), round.id(), tag + ": manifest changesetId equals committed id");
			checkEq(cs.parentId() == null ? "" : cs.parentId(), round.parentId(),
				tag + ": manifest parentId equals committed parent");
			checkEq(cs.roundId(), round.roundId(), tag + ": manifest roundId equals committed roundId");
			checkEq(cs.loaderScope(), round.loaderScope(), tag + ": manifest loaderScope");
			checkEq(cs.algoVersion(), round.algoVersion(), tag + ": manifest algoVersion");
			checkEq(cs.patchTiming().name(), round.patchTiming(), tag + ": manifest patchTiming");
			Optional<BytecodePack.Entry> entry = round.entry("probe/Subject");
			check(entry.isPresent(), tag + ": exported round holds probe/Subject");
			Changeset.Entry want = cs.entries().get("probe/Subject");
			check(want != null, tag + ": committed changeset holds probe/Subject");
			if (entry.isPresent() && want != null) {
				BytecodePack.Entry e = entry.get();
				checkEq(want.rawHash(), AlignmentMetaStore.sha256Hex(e.preAlign),
					tag + ": exported preAlign bytes are the committed raw bytes");
				checkEq(want.effHash(), AlignmentMetaStore.sha256Hex(e.jvmEffective),
					tag + ": exported jvmEffective bytes are the committed eff bytes");
				check(Arrays.equals(e.jvmEffective, e.alignOutput),
					tag + ": exported jvmEffective equals the alignment output");
			}
			if (wantSeq == 2L) {
				seq2 = cs;
				// Precondition for the "seq 2 alone" assertion below: replay now, while seq 3 does
				// not exist yet, and keep the bytes to compare against the post-seq-3 replay.
				for (BytecodePack.Entry e : round.entries()) {
					LambdaAligner.LAST_RESULT = null;
					byte[] replayed = LambdaAligner.align(e.oldEffective, e.preAlign);
					check(replayed != null, tag + ": seq-2 replay produced bytes");
					if (replayed != null) seq2ReplayAtExport.add(replayed);
				}
				check(!seq2ReplayAtExport.isEmpty(), tag + ": precondition: seq-2 replay recorded bytes");
			}
		}

		checkEq(List.of(1L, 2L, 3L), BytecodePack.chainRounds(),
			"R7: exported seqs ascending and exactly [1, 2, 3]");
		checkEq(List.of(1L, 2L, 3L), exportedSeqs, "R7: every exported round reported its seq");
		check(BytecodePack.round(9L).isEmpty(),
			"R7: absent seq yields an empty Optional (negative control)");

		// Parent chain read straight from the manifest files on disk.
		Map<String, String> m1 = readManifest(BytecodePack.manifestPath(packDir, 1L));
		Map<String, String> m2 = readManifest(BytecodePack.manifestPath(packDir, 2L));
		Map<String, String> m3 = readManifest(BytecodePack.manifestPath(packDir, 3L));
		checkEq(Integer.toString(BytecodePack.FORMAT_VERSION), m1.get("formatVersion"),
			"R7: manifest formatVersion");
		checkEq("1", m1.get("seq"), "R7: seq-1 manifest seq field");
		checkEq("2", m2.get("seq"), "R7: seq-2 manifest seq field");
		checkEq("3", m3.get("seq"), "R7: seq-3 manifest seq field");
		check(m1.getOrDefault("parentId", "?").isEmpty(), "R7: the first round has no parent");
		checkEq(m1.get("changesetId"), m2.get("parentId"),
			"R7: seq-2 parentId equals the seq-1 changeset id");
		checkEq(m2.get("changesetId"), m3.get("parentId"),
			"R7: seq-3 parentId equals the seq-2 changeset id");
		// Same chain, resolved through the read API: parentId(n) == id(n-1), and id(n-1) is the
		// round whose manifest seq is n-1 (asserted just above), so each parent is the previous seq.
		checkEq(BytecodePack.round(1L).map(BytecodePack.Round::id).orElse(""),
			m2.get("parentId"), "R7: seq-2 parent is the round exported as seq 1");
		checkEq(BytecodePack.round(2L).map(BytecodePack.Round::id).orElse(""),
			m3.get("parentId"), "R7: seq-3 parent is the round exported as seq 2");

		// Replaying seq 2 alone: read only seq-2/', after seq 3 was exported.
		BytecodePack.Round round2 = BytecodePack.round(packDir, 2L).orElse(null);
		check(round2 != null, "R7: seq-2 round still readable after seq-3 export");
		if (round2 != null) {
			List<byte[]> afterSeq3 = new ArrayList<>();
			for (BytecodePack.Entry e : round2.entries()) {
				LambdaAligner.LAST_RESULT = null;
				byte[] replayed = LambdaAligner.align(e.oldEffective, e.preAlign);
				check(replayed != null, "R7[seq-2]: replay produced bytes");
				if (replayed == null) continue;
				check(Arrays.equals(e.alignOutput, replayed),
					"R7[seq-2]: replay must be byte-identical to the exported alignment output");
				afterSeq3.add(replayed);
			}
			checkEq(seq2ReplayAtExport.size(), afterSeq3.size(),
				"R7[seq-2]: replay entry count unchanged by the seq-3 export");
			for (int i = 0; i < Math.min(seq2ReplayAtExport.size(), afterSeq3.size()); i++) {
				check(Arrays.equals(seq2ReplayAtExport.get(i), afterSeq3.get(i)),
					"R7[seq-2]: replay bytes identical before and after the seq-3 export");
			}
			// Per-round isolation: the seq-3 directory must not share bytes with seq-2.
			BytecodePack.Round round3 = BytecodePack.round(packDir, 3L).orElse(null);
			if (round3 != null) {
				Optional<BytecodePack.Entry> e2 = round2.entry("probe/Subject");
				Optional<BytecodePack.Entry> e3 = round3.entry("probe/Subject");
				check(e2.isPresent() && e3.isPresent(), "R7: seq-2 and seq-3 both hold probe/Subject");
				if (e2.isPresent() && e3.isPresent()) {
					check(!Arrays.equals(e2.get().alignOutput, e3.get().alignOutput),
						"R7: seq-2 and seq-3 hold distinct per-round bytes (no shared slot)");
				}
			}
		}

		// Determinism: exporting the same round again (with a later round present) must be
		// byte-identical on disk, and must still find that round's bytes by content.
		if (seq2 != null) {
			String fingerprintBefore = dirFingerprint(BytecodePack.roundDir(packDir, 2L));
			check(BytecodePack.exportRound(seq2), "R7: re-export of seq 2 with a later round present");
			checkEq(fingerprintBefore, dirFingerprint(BytecodePack.roundDir(packDir, 2L)),
				"R7: re-export is byte-identical (deterministic manifest + entry order)");
			checkEq(List.of(1L, 2L, 3L), BytecodePack.chainRounds(),
				"R7: re-export does not add or drop rounds");
		}

		// Clean-process equivalence: the offline replay must not depend on runtime store state.
		ChangesetLog.INSTANCE.reset();
		AlignmentMetaStore.INSTANCE.reset();
		BytecodePack.Round cleanRoom = BytecodePack.round(packDir, 2L).orElse(null);
		check(cleanRoom != null, "R7: seq-2 readable after store/log reset");
		if (cleanRoom != null) {
			for (BytecodePack.Entry e : cleanRoom.entries()) {
				LambdaAligner.LAST_RESULT = null;
				byte[] replayed = LambdaAligner.align(e.oldEffective, e.preAlign);
				check(replayed != null && Arrays.equals(e.alignOutput, replayed),
					"R7[seq-2]: replay in a clean store still byte-identical");
			}
		}

		// Backward compatibility: the legacy single-round layout is untouched by the chain export.
		List<BytecodePack.Entry> legacy = BytecodePack.readAll(packDir);
		check(legacy.size() >= 4, "R7: legacy single-round entries still readable (actual="
			+ legacy.size() + ")");

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

	/** Wait until the commit tick published a changeset with the expected seq for this loader scope. */
	private static Changeset awaitHead(long scope, long wantSeq) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			Optional<Changeset> head = ChangesetLog.INSTANCE.head(scope);
			if (head.isPresent() && head.get().seq() == wantSeq) return head.get();
			Thread.sleep(20L);
		}
		return ChangesetLog.INSTANCE.head(scope).filter(cs -> cs.seq() == wantSeq).orElse(null);
	}

	/** Tiny {@code key=value} reader for the round manifest (the probe must read the file itself). */
	private static Map<String, String> readManifest(Path file) throws Exception {
		Map<String, String> out = new LinkedHashMap<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (line.isEmpty() || line.charAt(0) == '#') continue;
			int eq = line.indexOf('=');
			if (eq > 0) out.put(line.substring(0, eq), line.substring(eq + 1));
		}
		return out;
	}

	/** {@code name:sha256} per file in ascending name order, to detect any byte drift on re-export. */
	private static String dirFingerprint(Path dir) throws Exception {
		StringBuilder sb = new StringBuilder();
		try (var s = Files.list(dir)) {
			for (Path p : s.sorted().toList()) {
				sb.append(p.getFileName()).append(':')
					.append(AlignmentMetaStore.sha256Hex(Files.readAllBytes(p))).append('\n');
			}
		}
		return sb.toString();
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

	/**
	 * Same shape as {@link #v3()} but with a distinct returned value, so each chain round is a real
	 * redefine with a fresh body while the lambda topology stays comparable across rounds.
	 */
	private static String chainSource(String marker) {
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
			+ "        return \"" + marker + "\";\n"
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
