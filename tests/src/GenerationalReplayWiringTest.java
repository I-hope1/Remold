import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import remold.ClassDiffUtil;
import remold.RemoldAgent;
import remold.ref.InitializerReplay;
import remold.ref.InitFix;
import remold.vcs.AlignmentMetaStore;
import remold.vcs.Changeset;
import remold.vcs.ChangesetLog;
import remold.vcs.GenerationalFieldLedger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Analysis-time wiring of {@code GenerationalFieldLedger.resolve} into the {@code L1} replay decision
 * ({@code docs/topology/20-generational-field-ledger.md} §3.3, criteria A5 / A14, invariants G6 / G8).
 *
 * <p><b>What is guarded</b>: once a scope has generational history, a replay candidate whose fact is
 * stale / discontinuous / out of window must be <b>dropped from the patch</b>, reported as
 * {@code rejected: ...} in {@link InitFix.PatchReport#replayFields()} and recorded as unclosed debt in
 * {@link GenerationalFieldLedger}. Without the wiring the very same round reports {@code replayed} and
 * silently patches against the current {@code ClassDiff} pair — the "no generational evidence" path, which
 * must stay byte-identical to the pre-B2 behaviour (that is the second case below).</p>
 *
 * <p>No DCEVM needed: the decision is pure analysis ({@code InitFix.transform} + a real in-memory
 * {@link ChangesetLog} / ledger). The runtime consequence (a conditional CAS that misses) is locked by
 * {@code :tests:vcsA14Probe}.</p>
 */
public class GenerationalReplayWiringTest {

	private static final String PKG = "grw1";
	private static final String BASE = PKG + ".Base";
	private static final String HOST = PKG + ".Host";
	private static final String ANON = PKG + ".Host$1";
	private static final String ANON_INTERNAL = PKG + "/Host$1";
	/**
	 * L1 replays an <b>inherited</b> field, so the fact (and therefore the ledger key) belongs to the
	 * <b>declaring</b> class — {@code InitFix.l1ReplayFact} uses {@code ReplayField.declaringClass}.
	 * The consumer must query the same class or it would never see the fact.
	 */
	private static final String OWNER_INTERNAL = PKG + "/Base";
	private static final String FIELD = "v";

	private Map<String, byte[]> v2Classes;
	private byte[] anonV1;
	private byte[] anonV2;
	private ClassLoader loader;
	private Class<?> host;
	private long scope;

	@AfterEach
	void cleanUp() {
		System.clearProperty(InitializerReplay.PARENTS_PROPERTY);
		RemoldAgent.bytecodeCache.remove(ANON);
		ChangesetLog.INSTANCE.reset();
		GenerationalFieldLedger.INSTANCE.reset();
	}

	/**
	 * Loads only the in-memory classes; the resource stream must work too, because
	 * {@code HierarchyTreeOracle} resolves the inherited field through the loader rather than through
	 * {@code Class.forName} (offline analysis must not force class initialisation).
	 */
	private static final class MemoryLoader extends ClassLoader {
		private final Map<String, byte[]> classes;

		MemoryLoader(Map<String, byte[]> classes) {
			super(GenerationalReplayWiringTest.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}

		@Override
		public InputStream getResourceAsStream(String name) {
			if (name.endsWith(".class")) {
				byte[] bytes = classes.get(name.substring(0, name.length() - 6).replace('/', '.'));
				if (bytes != null) return new ByteArrayInputStream(bytes);
			}
			return super.getResourceAsStream(name);
		}
	}

	/**
	 * The double-brace shape: {@code Base} declares the field, the anonymous subclass writes it in an
	 * instance initialiser. {@code InitializerReplay} only replays <b>inherited</b> fields (a field the
	 * anonymous class declares itself is the ordinary own-field path), so the field must live on the
	 * parent — this is the shape {@code tests/src/DoubleBraceProbe.java} exercises.
	 */
	private static Map<String, String> sources(int initializer) {
		Map<String, String> out = new LinkedHashMap<>();
		out.put(BASE, "package " + PKG + ";\npublic class Base { public int " + FIELD + "; }\n");
		out.put(HOST, "package " + PKG + ";\n"
		 + "public class Host {\n"
		 + "  public Base make() { return new Base() {{ " + FIELD + " = " + initializer + "; }}; }\n"
		 + "}\n");
		return out;
	}

	/** Compile v1/v2, load the v2 anonymous class through a private loader and register its scope. */
	private void fixture() throws Exception {
		Map<String, byte[]> v1 = LayoutGateAssert.compile(sources(1));
		v2Classes = LayoutGateAssert.compile(sources(2));
		this.anonV1 = v1.get(ANON);
		this.anonV2 = v2Classes.get(ANON);
		assertNotNull(anonV1, "precondition: v1 anonymous class bytes");
		assertNotNull(anonV2, "precondition: v2 anonymous class bytes");

		loader = new MemoryLoader(v2Classes);
		host = loader.loadClass(ANON);
		// Production order: the transform target's bytes are in bytecodeCache before InitFix runs.
		RemoldAgent.bytecodeCache.put(ANON, anonV2);
		System.setProperty(InitializerReplay.PARENTS_PROPERTY, BASE);

		scope = AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		transformWith(anonV1, anonV2);
	}

	private InitFix.PatchReport report;

	private void transformWith(byte[] anonV1, byte[] anonV2) {
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(anonV1, anonV2);
		assertFalse(diff.addedInstanceFields.contains(FIELD),
		 "precondition: the field already exists in v1, so this is an L1 replay candidate");
		InitFix.transform(host, anonV2, diff);
		report = InitFix.getLastReport(host);
		assertNotNull(report, "a report must always be published");
	}

	/** Commit one generation whose entry for the anonymous class carries {@code rawHash}. */
	private Changeset commit(String rawHash) {
		byte[] blob = (rawHash + "-base").getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Map<String, Changeset.Entry> entries = new LinkedHashMap<>();
		entries.put(OWNER_INTERNAL, new Changeset.Entry(OWNER_INTERNAL, rawHash,
		 ChangesetLog.effSha256(blob), Changeset.metaDigest(OWNER_INTERNAL + rawHash, List.of(),
		  Map.of(), Map.of(), 0)));
		// The Eff blob must be committed too: a forward revert reads it back by {@code effHash}.
		return ChangesetLog.INSTANCE.commit(scope, "r-" + rawHash, entries, Changeset.Manifest.EMPTY,
		 List.of(), Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.AFTER_COMMIT,
		 Map.of(), Map.of(OWNER_INTERNAL, blob));
	}

	/**
	 * Land a forward revert: the gate only overrides the round's {@code ClassDiff} pair in a revert
	 * context (HEAD is a revert generation, or the key already owes a debt), so the reconcile cases below
	 * have to build one — exactly like a real {@code revertTo} does.
	 */
	private Changeset revertTo(long targetSeq) {
		return ChangesetLog.INSTANCE.appendForwardRevert(scope,
		 ChangesetLog.INSTANCE.revertPlan(scope, targetSeq), "revert",
		 Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.AFTER_COMMIT);
	}

	/** Record one {@code L1} fact for {@code (scope, Host$1, v)} at {@code gen}. */
	private void record(Changeset gen, String oldLiteral, String newLiteral) {
		Changeset.ReplayFact fact = new Changeset.ReplayFact(BASE, FIELD, "I", "L1",
		 oldLiteral, newLiteral, "INT", "", "", "");
		GenerationalFieldLedger.RecordResult result = GenerationalFieldLedger.INSTANCE.record(loader,
		 new GenerationalFieldLedger.LedgerKey(scope, OWNER_INTERNAL, FIELD),
		 new GenerationalFieldLedger.GenRef(gen.seq(), gen.id()), fact, gen.algoVersion());
		assertEquals(GenerationalFieldLedger.RecordResult.ACCEPTED, result,
		 "precondition: the fact must enter the index");
	}

	/** The replay candidate really is an L1 candidate (guards against a fixture that silently drifts). */
	private void assertReplayCandidate() {
		assertTrue(report.replayFields().containsKey(FIELD),
		 "precondition: " + FIELD + " must be an L1 replay candidate, report=" + report);
	}

	// ------------------------------------------------------------------------------------------

	/**
	 * §4-R3 / review finding: a generation whose conditional CAS never wrote contributes no
	 * expectation. If the field is unclosed and no generation has a confirmed write, reconciling
	 * against the newest fact would use a <b>theory</b> value as if it were on the heap — the round
	 * would write the previous initialiser back (e.g. 5 instead of 6) while looking successful.
	 *
	 * <p>Guarded here at the decision layer: the candidate is dropped, the debt stays open.</p>
	 */
	@Test
	void unclosedFieldWithoutAConfirmedWriteRefusesToReconcile() throws Exception {
		fixture();
		// The fact recorded for the skipped generation is theory only: its CAS never landed.
		Changeset gen = commit("h1");
		record(gen, "2", "5");
		GenerationalFieldLedger.INSTANCE.markUnclosed(loader, OWNER_INTERNAL, FIELD);

		// A later ordinary round (HEAD is not a revert generation) must not use that theory value.
		transformWith(anonV1, anonV2);

		String result = report.replayFields().get(FIELD);
		assertNotNull(result, "the refused candidate must stay visible in the report: "
		 + report.replayFields());
		assertTrue(result.startsWith("rejected: "), "the report must say it was refused: " + result);
		assertTrue(result.contains("confirmed write"),
		 "the reason must name the cause (no confirmed write): " + result);
		assertFalse(report.replayGenerated(), "a refused replay must not be counted as generated");
		assertEquals(1, GenerationalFieldLedger.INSTANCE.unclosedCount(loader),
		 "the debt stays open until an explicit recovery writes the field");
	}

	/**
	 * No generational evidence for this key: the gate stays out of the way and the round behaves
	 * exactly as before B2 (the fact index is derived, so its absence only changes cost, §2.3).
	 */
	@Test
	void withoutAFactTheRoundKeepsTheClassDiffPair() throws Exception {
		fixture();
		commit("h1");

		transformWith(LayoutGateAssert.compile(sources(1)).get(ANON),
		 LayoutGateAssert.compile(sources(2)).get(ANON));

		assertReplayCandidate();
		assertEquals("replayed", report.replayFields().get(FIELD),
		 "a round without a fact must not be refused: " + report.replayFields());
		assertFalse(GenerationalFieldLedger.INSTANCE.isUnclosed(loader, OWNER_INTERNAL, FIELD),
		 "no debt may be recorded when nothing was refused");
	}

	/**
	 * A plain forward round keeps the round's {@code ClassDiff} pair even when a fact exists: the fact
	 * describes the previous drift, so overriding a forward round with it would leave the expectation one
	 * generation behind and stall every replay (§3.1: the generational context only re-bases the
	 * comparison side once a revert is in effect).
	 */
	@Test
	void aForwardRoundKeepsTheClassDiffPairEvenWithAFact() throws Exception {
		fixture();
		Changeset first = commit("h1");
		record(first, "1", "2");
		// The chain moves without a revert: the fact is now separated from the head by a structure change,
		// so a gate that re-based this round on the ledger would have to refuse the candidate.
		commit("h2");

		transformWith(LayoutGateAssert.compile(sources(1)).get(ANON),
		 LayoutGateAssert.compile(sources(2)).get(ANON));

		assertReplayCandidate();
		assertEquals("replayed", report.replayFields().get(FIELD),
		 "a forward round is not re-based on the ledger: " + report.replayFields());
		assertFalse(GenerationalFieldLedger.INSTANCE.isUnclosed(loader, OWNER_INTERNAL, FIELD));
	}

	/** A fact on the target generation is usable: the candidate stays in the patch. */
	@Test
	void factOnTheTargetGenerationKeepsTheCandidateWritable() throws Exception {
		Map<String, byte[]> v1 = LayoutGateAssert.compile(sources(1));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(sources(2));
		loader = new MemoryLoader(v2);
		host = loader.loadClass(ANON);
		RemoldAgent.bytecodeCache.put(ANON, v2.get(ANON));
		System.setProperty(InitializerReplay.PARENTS_PROPERTY, BASE);
		scope = AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		Changeset gen = commit("h1");
		record(gen, "1", "2");
		commit("h2");
		revertTo(gen.seq());

		transformWith(v1.get(ANON), v2.get(ANON));

		assertReplayCandidate();
		assertEquals("replayed", report.replayFields().get(FIELD),
		 "a fact that passes every gate must keep the candidate: " + report.replayFields());
		assertFalse(GenerationalFieldLedger.INSTANCE.isUnclosed(loader, OWNER_INTERNAL, FIELD));
	}

	/**
	 * A14 / G6: the structure moved between the fact and the target generation, so the recorded
	 * initialiser no longer describes it. The candidate must leave the patch, the report must say why,
	 * and the debt must live in the generational ledger (not in the transient {@code PENDING} slot).
	 */
	@Test
	void discontinuousFactDropsTheCandidateAndRecordsTheDebt() throws Exception {
		fixture();
		Changeset first = commit("h1");
		record(first, "1", "2");
		Changeset second = commit("h2");
		revertTo(second.seq());

		transformWith(LayoutGateAssert.compile(sources(1)).get(ANON),
		 LayoutGateAssert.compile(sources(2)).get(ANON));

		String result = report.replayFields().get(FIELD);
		assertNotNull(result, "the refused candidate must stay visible in the report: "
		 + report.replayFields());
		assertTrue(result.startsWith("rejected: "), "the report must say it was refused: " + result);
		assertTrue(result.contains("not continuous"), "the reason must name the cause: " + result);
		assertTrue(result.contains("OVERWRITE"), "the reason must name the recovery path: " + result);
		assertFalse(report.replayGenerated(), "a refused replay must not be counted as generated");
		assertTrue(GenerationalFieldLedger.INSTANCE.isUnclosed(loader, OWNER_INTERNAL, FIELD),
		 "the unclosed debt must live in the generational ledger, keyed by the declaring class");
	}
}
