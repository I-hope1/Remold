import remold.LambdaAligner;
import remold.vcs.AlignmentMetaStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PoC: alignment must produce byte-identical output whether the previous generation's metadata
 * is read from {@link AlignmentMetaStore} ("meta path") or supplied the legacy way (".class
 * markers" + caller-passed invented names).
 *
 * <p>Contract under test:</p>
 * <ol>
 *   <li>With {@code META_SOURCE} on and a matching head entry, the aligner reads the previous
 *       generation's ghost hashes and invented names from the store, and the byte output equals
 *       the legacy path's output for the same input sequence.</li>
 *   <li>With {@code META_SOURCE} on but no matching entry, the aligner falls back to the markers
 *       and produces exactly the legacy output (no behavioral change when metadata is absent).</li>
 *   <li>Entries are keyed by (loader identity, class name): metadata recorded under one loader is
 *       invisible to another loader and to the no-loader namespace.</li>
 *   <li>Head never evicts; history is bounded per class and globally.</li>
 * </ol>
 *
 * <p>Mutation anchors (each must turn this class red when flipped):</p>
 * <ul>
 *   <li>Ignore {@code oldMeta.inventedNames} -> round 2/3 outputs diverge from the legacy run.</li>
 *   <li>Drop the loader from the store key -> the loader isolation test goes red.</li>
 *   <li>Evict head together with history -> the eviction test goes red.</li>
 *   <li>Use head without the eff-hash check -> round 2 of the fallback test cannot match legacy.</li>
 * </ul>
 */
public class AlignmentMetaEquivalenceTest {

	static { System.out.println("[diag] launch property remold.poc.meta_source="
		+ System.getProperty("remold.poc.meta_source")); }

	private static final String CLASS = "test/MetaCase";

	@BeforeEach
	@AfterEach
	void resetState() {
		LambdaAligner.META_SOURCE = false;
		LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		LambdaAligner.LAST_RESULT = null;
		AlignmentMetaStore.INSTANCE.reset();
	}

	/** Mirrors RemoldAgent's commit contract: record only after the redefine is known to be effective. */
	private static void commit(ClassLoader loader, byte[] oldEffective, byte[] preAlign, byte[] alignedOutput) {
		LambdaAligner.AlignmentResult r = LambdaAligner.LAST_RESULT;
		String name;
		try {
			name = new org.objectweb.asm.ClassReader(alignedOutput).getClassName();
		} catch (Throwable t) {
			name = CLASS;
		}
		AlignmentMetaStore.INSTANCE.commit(loader, name, preAlign, alignedOutput, alignedOutput,
			r == null ? Set.of() : r.inventedNames,
			LambdaAligner.scanGhostOriginHashes(alignedOutput),
			r == null ? Map.of() : r.methodMapping,
			r == null ? 0 : r.bridgeCount);
	}

	private static final class RunResult {
		final List<byte[]> out = new ArrayList<>();
		final List<Integer> metaGenerations = new ArrayList<>();
		final List<Set<String>> invented = new ArrayList<>();
		final List<Set<String>> supplied = new ArrayList<>();
	}

	/**
	 * Legacy path: caller feeds back {@code inventedNames} from the previous round.
	 */
	private static RunResult runLegacy(byte[][] raw) {
		RunResult r = new RunResult();
		Set<String> invented = Set.of();
		byte[] prev = raw[0];
		for (int i = 1; i < raw.length; i++) {
			LambdaAligner.LAST_RESULT = null;
			byte[] next = LambdaAligner.align(prev, raw[i], null, null, invented);
			assertNotNull(next, "legacy round " + i + " produced no bytes");
			assertNotNull(LambdaAligner.LAST_RESULT, "legacy round " + i + " published no result");
			invented = LambdaAligner.LAST_RESULT.inventedNames;
			commit(null, prev, raw[i], next);
			r.out.add(next);
			r.metaGenerations.add(LambdaAligner.LAST_RESULT.metaGeneration);
			r.invented.add(invented);
			r.supplied.add(LambdaAligner.LAST_RESULT.suppliedInventedNames);
			prev = next;
		}
		return r;
	}

	/**
	 * Meta path: the caller passes nothing; the store supplies invented names and ghost hashes.
	 *
	 * @param clearEveryRound when true, the store is wiped before every round so each round has to
	 *                        fall back to the bytecode markers
	 */
	private static RunResult runMeta(byte[][] raw, boolean clearEveryRound) {
		LambdaAligner.META_SOURCE = true;
		RunResult r = new RunResult();
		byte[] prev = raw[0];
		for (int i = 1; i < raw.length; i++) {
			if (clearEveryRound) AlignmentMetaStore.INSTANCE.reset();
			LambdaAligner.LAST_RESULT = null;
			byte[] next = LambdaAligner.align(prev, raw[i], null, null, null);
			assertNotNull(next, "meta round " + i + " produced no bytes");
			assertNotNull(LambdaAligner.LAST_RESULT, "meta round " + i + " published no result");
			r.out.add(next);
			r.metaGenerations.add(LambdaAligner.LAST_RESULT.metaGeneration);
			commit(null, prev, raw[i], next);
			r.invented.add(LambdaAligner.LAST_RESULT.inventedNames);
			r.supplied.add(LambdaAligner.LAST_RESULT.suppliedInventedNames);
			prev = next;
		}
		return r;
	}

	@Test
	void metaPathMatchesLegacyPathAcrossRounds(@TempDir Path tmp) throws Exception {
		byte[][] raw = compileRounds(tmp);

		RunResult legacy = runLegacy(raw);
		AlignmentMetaStore.INSTANCE.reset();
		LambdaAligner.LAST_RESULT = null;
		RunResult meta = runMeta(raw, false);

		// Baseline: the fixture must actually exercise invented names, otherwise byte equality
		// would be vacuous (both rounds would carry an empty set).
		boolean sawInvented = false;
		for (Set<String> s : legacy.invented) if (!s.isEmpty()) sawInvented = true;
		assertTrue(sawInvented, "fixture must make the aligner invent at least one name");

		// Legacy run never consults meta.
		for (int g : legacy.metaGenerations) assertEquals(-1, g, "legacy path must not read meta");

		// Meta path: first round has no predecessor, later rounds read generation 1..n-1.
		assertEquals(-1, meta.metaGenerations.get(0), "round 1 has no previous generation to read");
		for (int i = 1; i < meta.metaGenerations.size(); i++) {
			assertEquals(i, meta.metaGenerations.get(i),
				"round " + (i + 1) + " must read generation " + i + " from the store");
		}

		// The meta path must feed exactly the same invented-name set the legacy caller feeds:
		// this is the contract point where "meta 为准" replaces the parameter, and it turns red
		// when the meta-supplied set is dropped (unlike byte equality, which the fixture's
		// pairing happens to preserve).
		boolean sawSupplied = false;
		for (int i = 0; i < legacy.supplied.size(); i++) {
			assertEquals(legacy.supplied.get(i), meta.supplied.get(i),
				"round " + (i + 1) + ": meta must supply the same invented names as the caller");
			if (!meta.supplied.get(i).isEmpty()) sawSupplied = true;
		}
		assertTrue(sawSupplied, "fixture must exercise a non-empty invented-name set");

		// Primary acceptance: byte-identical output.
		assertEquals(legacy.out.size(), meta.out.size());
		for (int i = 0; i < legacy.out.size(); i++) {
			assertArrayEquals(legacy.out.get(i), meta.out.get(i),
				"round " + (i + 1) + ": meta path must equal legacy byte output");
		}

		// The store now holds the last generation; ghosts recorded with their pre-death hashes.
		AlignmentMetaStore.Entry head = AlignmentMetaStore.INSTANCE.head(null, CLASS);
		assertNotNull(head, "head metadata must exist after the run");
		assertEquals(raw.length - 1, head.generation, "generation must track rounds");
		assertFalse(head.ghostHashes.isEmpty(), "ghosts must be recorded with pre-death hashes");
	}

	@Test
	void missingMetadataFallsBackToMarkers(@TempDir Path tmp) throws Exception {
		byte[][] raw = compileRounds(tmp);

		// Legacy path with no caller-supplied invented names: the only information available is
		// what lives in the class bytes (markers).
		RunResult bytesOnly = new RunResult();
		byte[] prev = raw[0];
		for (int i = 1; i < raw.length; i++) {
			LambdaAligner.LAST_RESULT = null;
			byte[] next = LambdaAligner.align(prev, raw[i], null, null, null);
			assertNotNull(next);
			bytesOnly.out.add(next);
			bytesOnly.metaGenerations.add(LambdaAligner.LAST_RESULT.metaGeneration);
			prev = next;
		}
		for (int g : bytesOnly.metaGenerations) assertEquals(-1, g);

		// Meta mode but with the store wiped before every round: must fall back to the very same
		// markers and therefore produce the very same bytes.
		RunResult fallback = runMeta(raw, true);
		for (int i = 0; i < bytesOnly.out.size(); i++) {
			assertEquals(-1, fallback.metaGenerations.get(i),
				"round " + (i + 1) + " must fall back when no metadata matches");
			assertArrayEquals(bytesOnly.out.get(i), fallback.out.get(i),
				"round " + (i + 1) + ": fallback must be byte-identical to the marker-only path");
		}
	}

	@Test
	void loaderIsolatesMetadata(@TempDir Path tmp) throws Exception {
		byte[][] raw = compileRounds(tmp);
		ClassLoader a = new ClassLoader() { };
		ClassLoader b = new ClassLoader() { };

		LambdaAligner.META_SOURCE = true;
		LambdaAligner.LAST_RESULT = null;
		byte[] outA = LambdaAligner.align(raw[0], raw[1], null, null, null, a);
		assertNotNull(outA);
		commit(a, raw[0], raw[1], outA);
		assertNotNull(AlignmentMetaStore.INSTANCE.head(a, CLASS), "loader A metadata must be recorded");
		// Unrelated input: the same class name under other loader identities must not see it.
		assertNull(AlignmentMetaStore.INSTANCE.head(b, CLASS),
			"loader B must not see loader A metadata for the same class name");
		assertNull(AlignmentMetaStore.INSTANCE.head(null, CLASS),
			"the no-loader namespace must not see loader A metadata");

		LambdaAligner.LAST_RESULT = null;
		byte[] outB = LambdaAligner.align(raw[0], raw[1], null, null, null, b);
		commit(b, raw[0], raw[1], outB);
		assertArrayEquals(outA, outB, "same input must align the same under another loader");
		assertNotNull(AlignmentMetaStore.INSTANCE.head(b, CLASS), "loader B metadata must be recorded");

		// Independent chains: each loader starts at generation 1.
		assertEquals(1, AlignmentMetaStore.INSTANCE.head(a, CLASS).generation);
		assertEquals(1, AlignmentMetaStore.INSTANCE.head(b, CLASS).generation);
	}

	@Test
	void headSurvivesHistoryEviction(@TempDir Path tmp) throws Exception {
		byte[][] raw = compileRounds(tmp);
		ClassLoader loader = new ClassLoader() { };
		LambdaAligner.META_SOURCE = true;

		byte[] prev = raw[0];
		int rounds = AlignmentMetaStore.HISTORY_PER_CLASS + 4;
		for (int i = 0; i < rounds; i++) {
			LambdaAligner.LAST_RESULT = null;
			byte[] before = prev;
			prev = LambdaAligner.align(prev, raw[1], null, null, null, loader);
			assertNotNull(prev);
			commit(loader, before, raw[1], prev);
		}

		AlignmentMetaStore.Entry head = AlignmentMetaStore.INSTANCE.head(loader, CLASS);
		assertNotNull(head, "head must never be evicted");
		assertEquals(rounds, head.generation, "head must track the newest generation");

		List<AlignmentMetaStore.Entry> history = AlignmentMetaStore.INSTANCE.history(loader, CLASS);
		assertEquals(AlignmentMetaStore.HISTORY_PER_CLASS, history.size(),
			"history must be bounded per class");
		assertEquals(rounds, history.get(0).generation, "history must keep the newest generations");
		assertEquals(rounds - AlignmentMetaStore.HISTORY_PER_CLASS + 1,
			history.get(history.size() - 1).generation, "history must drop the oldest generations");
		assertTrue(AlignmentMetaStore.INSTANCE.historyCount() <= AlignmentMetaStore.HISTORY_MAX_ENTRIES,
			"global history budget must hold");
	}

	@Test
	void unrelatedClassMetadataIsNotConsulted(@TempDir Path tmp) throws Exception {
		byte[][] raw = compileRounds(tmp);
		LambdaAligner.META_SOURCE = true;

		LambdaAligner.LAST_RESULT = null;
		byte[] out1 = LambdaAligner.align(raw[0], raw[1], null, null, null);
		assertNotNull(out1);
		commit(null, raw[0], raw[1], out1);

		// Replace the store content with data for a different class only.
		AlignmentMetaStore.INSTANCE.reset();
		Map<String, Long> fakeGhosts = new HashMap<>();
		fakeGhosts.put("lambda$run$0()V", 12345L);
		AlignmentMetaStore.INSTANCE.commit(null, "test/Other",
			new byte[]{1}, new byte[]{2}, new byte[]{3},
			Set.of("lambda$run$7"), fakeGhosts, Map.of(), 0);
		assertNull(AlignmentMetaStore.INSTANCE.head(null, CLASS),
			"metadata must be class-scoped");

		LambdaAligner.LAST_RESULT = null;
		byte[] out2 = LambdaAligner.align(out1, raw[2], null, null, null);
		assertNotNull(out2);
		assertEquals(-1, LambdaAligner.LAST_RESULT.metaGeneration,
			"unrelated class metadata must not be read");
	}

	@Test
	void staleMetadataForDifferentOldBytesIsIgnored(@TempDir Path tmp) throws Exception {
		byte[][] raw = compileRounds(tmp);
		LambdaAligner.META_SOURCE = true;

		// Round 1 records generation 1 for the class.
		LambdaAligner.LAST_RESULT = null;
		byte[] out1 = LambdaAligner.align(raw[0], raw[1], null, null, null);
		assertNotNull(out1);
		commit(null, raw[0], raw[1], out1);
		assertNotNull(AlignmentMetaStore.INSTANCE.head(null, CLASS));

		// Feed unrelated old bytes: the head entry exists but was produced from different bytes,
		// so it must not be used (it would install a wrong ghost hash / invented-name set).
		LambdaAligner.LAST_RESULT = null;
		byte[] other = LambdaAligner.align(raw[0], raw[2], null, null, null);
		assertNotNull(other);
		assertEquals(-1, LambdaAligner.LAST_RESULT.metaGeneration,
			"metadata produced from different old bytes must not be used");
	}

	// ---------------------------------------------------------------------
	// Fixture: V1 (a,b) -> V2 (delete a, add c at front) -> V3 (a,c,b) -> V4 (edit b).
	// V2 forces the aligner to invent a fresh name for c because javac reuses $0 which the
	// ghost of a already owns; V3 then depends on knowing that invented name.
	// ---------------------------------------------------------------------

	private static byte[][] compileRounds(Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		// V1 (a,b) -> V2 (delete a) -> V3 (re-add a) -> V4 (edit b).
		// V2 leaves a ghost for a; V3's re-added a must take a fresh (invented) name because the
		// ghost still owns javac's $0; V4 then depends on knowing that invented name.
		String v1 = source("list.add(() -> a());\n        list.add(() -> b());");
		String v2 = source("list.add(() -> b());");
		String v3 = source("list.add(() -> a());\n        list.add(() -> b());");
		String v4 = source("list.add(() -> a());\n        list.add(() -> bMod());");

		byte[][] raw = new byte[4][];
		String[] srcs = { v1, v2, v3, v4 };
		for (int i = 0; i < srcs.length; i++) {
			Path srcDir = Files.createDirectories(tmp.resolve("src" + (i + 1)));
			Path outDir = Files.createDirectories(tmp.resolve("out" + (i + 1)));
			Path src = srcDir.resolve("MetaCase.java");
			Files.writeString(src, srcs[i], StandardCharsets.UTF_8);
			HstestFixtures.compile(javac, outDir, src.toFile());
			raw[i] = Files.readAllBytes(outDir.resolve("test/MetaCase.class"));
		}
		return raw;
	}

	private static String source(String body) {
		return "package test;\n"
			+ "import java.util.List;\n"
			+ "public class MetaCase {\n"
			+ "    static void a() { System.out.println(\"a\"); }\n"
			+ "    static void b() { System.out.println(\"b\"); }\n"
			+ "    static void bMod() { System.out.println(\"bmod\"); }\n"
			+ "    public void run(List<Runnable> list) {\n"
			+ "        " + body + "\n"
			+ "    }\n"
			+ "}\n";
	}

	/** Kept for diagnostics: count methods that call onOrphanInvoked. */
	@SuppressWarnings("unused")
	private static int ghostCount(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		int n = 0;
		for (MethodNode mn : cn.methods) {
			if (mn.name.startsWith("lambda$")) n++;
		}
		return n;
	}
}
