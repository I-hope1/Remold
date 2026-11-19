package remold.vcs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic cross-generation resolution ({@code docs/topology/20-generational-field-ledger.md}
 * §3.2 / §3.3, negative criteria A5 and A14).
 *
 * <p><b>What is guarded</b>: the expectation a conditional CAS compares against is taken from the
 * <b>target</b> generation only. After a forward revert, the reverted-away generation's
 * {@code newLiteral} is never used — a value the engine wrote there stays on the heap, so treating it
 * as "already applied" would turn the revert into a silent no-op.</p>
 */
class GenerationalFieldLedgerResolveTest {

	private static final long SCOPE = 21L;
	private static final String CLASS = "probe/Holder";

	private final ClassLoader loader = new ClassLoader(null) {
	};

	@AfterEach
	void cleanUp() {
		ChangesetLog.INSTANCE.reset();
		GenerationalFieldLedger.INSTANCE.reset();
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static Map<String, Changeset.Entry> entry(String rawHash, byte[] eff) {
		Map<String, Changeset.Entry> map = new LinkedHashMap<>();
		map.put(CLASS, new Changeset.Entry(CLASS, rawHash, ChangesetLog.effSha256(eff),
		 Changeset.metaDigest(CLASS + rawHash, List.of(), Map.of(), Map.of(), 0)));
		return map;
	}

	/** Commits one generation and returns it; {@code rawHash} controls the adjacency check. */
	private Changeset commit(String rawHash, String eff, List<Changeset.ReplayFact> facts) {
		Map<String, byte[]> now = new LinkedHashMap<>();
		now.put(CLASS, bytes(eff));
		return ChangesetLog.INSTANCE.commit(SCOPE, "r" + rawHash, entry(rawHash, bytes(eff)),
		 Changeset.Manifest.EMPTY, facts, Changeset.currentAlgoVersion(), "",
		 Changeset.PatchTiming.AFTER_COMMIT, Map.of(), now);
	}

	private void record(Changeset gen, String oldLiteral, String newLiteral, String kind) {
		Changeset.ReplayFact fact = new Changeset.ReplayFact("probe.Holder", "f", "I", "L1",
		 oldLiteral, newLiteral, kind, "", "", "");
		assertEquals(GenerationalFieldLedger.RecordResult.ACCEPTED,
		 GenerationalFieldLedger.INSTANCE.record(loader,
		  new GenerationalFieldLedger.LedgerKey(SCOPE, CLASS, "f"),
		  new GenerationalFieldLedger.GenRef(gen.seq(), gen.id()), fact, gen.algoVersion()));
	}

	private GenerationalFieldLedger.Resolved resolve(long targetSeq) {
		return GenerationalFieldLedger.INSTANCE.resolve(loader, ChangesetLog.INSTANCE, SCOPE,
		 CLASS, "f", targetSeq);
	}

	/** A5: a fact recorded on the target generation is used verbatim, both sides. */
	@Test
	void factOnTheTargetGenerationResolvesExactly() {
		Changeset gen = commit("h1", "v1", List.of());
		record(gen, "1", "2", "INT");

		GenerationalFieldLedger.Resolved resolved = resolve(gen.seq());

		assertEquals(GenerationalFieldLedger.Resolution.EXACT, resolved.resolution());
		assertTrue(resolved.writable());
		assertEquals("1", resolved.oldLiteral(), "the comparison side comes from the same fact");
		assertEquals("2", resolved.newLiteral(), "the write side comes from the same fact");
	}

	/** §3.3: a fact from an earlier generation is reusable only while {@code rawHash} stays constant. */
	@Test
	void unchangedStructureLetsTheFactChain() {
		Changeset first = commit("h1", "v1", List.of());
		record(first, "1", "2", "INT");
		Changeset second = commit("h1", "v2", List.of());

		GenerationalFieldLedger.Resolved resolved = resolve(second.seq());

		assertEquals(GenerationalFieldLedger.Resolution.CHAINED, resolved.resolution());
		assertEquals(first.seq(), resolved.fromGeneration(), "the source generation is reported");
		assertEquals("1", resolved.oldLiteral());
	}

	/**
	 * §3.3: if the class structure moved between the fact and the target, the old fact no longer
	 * describes the initialiser, so it must not be reused.
	 */
	@Test
	void changedStructureRefusesTheStaleFact() {
		Changeset first = commit("h1", "v1", List.of());
		record(first, "1", "2", "INT");
		commit("h2", "v2", List.of());

		GenerationalFieldLedger.Resolved resolved = resolve(2L);

		assertEquals(GenerationalFieldLedger.Resolution.REFUSED_DISCONTINUOUS, resolved.resolution());
		assertFalse(resolved.writable());
		assertEquals(null, resolved.oldLiteral(), "a refused resolution carries no expectation");
	}

	/** §3.3: no fact at or before the target at all — a missing fact, not a missing window. */
	@Test
	void withoutAnyFactTheResolutionIsEmpty() {
		commit("h1", "v1", List.of());
		assertEquals(GenerationalFieldLedger.Resolution.MISS_NO_FACT, resolve(1L).resolution());
	}

	/**
	 * §3.3: an id mismatch means "same seq, different generation" — refuse, and do not fall back to
	 * the most recent live generation.
	 */
	@Test
	void mismatchedChangesetIdRefusesInsteadOfSubstituting() {
		Changeset gen = commit("h1", "v1", List.of());
		Changeset.ReplayFact fact = new Changeset.ReplayFact("probe.Holder", "f", "I", "L1",
		 "1", "2", "INT", "", "", "");
		GenerationalFieldLedger.INSTANCE.record(loader,
		 new GenerationalFieldLedger.LedgerKey(SCOPE, CLASS, "f"),
		 new GenerationalFieldLedger.GenRef(gen.seq(), "some-other-id"), fact, gen.algoVersion());

		GenerationalFieldLedger.Resolved resolved = resolve(gen.seq());

		assertEquals(GenerationalFieldLedger.Resolution.MISS_WINDOW, resolved.resolution());
		assertFalse(resolved.writable());
	}

	/**
	 * A14: {@code seq = 2} wrote {@code 1 -> 2} (engine-written), then a revert to {@code seq = 1}
	 * landed as {@code seq = 3}. Resolving at HEAD must use the target generation's fact
	 * ({@code 1 -> 2}), <b>not</b> the polluted generation's ({@code 2 -> 3}).
	 *
	 * <p>The consequence is fail-closed by construction: an instance still holding the engine-written
	 * {@code 2} does not match the expectation {@code 1}, so the conditional CAS misses and the field
	 * stays open. That is the safety gate, not a regression.</p>
	 */
	@Test
	void afterAForwardRevertTheExpectationComesFromTheTargetGenerationOnly() {
		Changeset one = commit("h1", "v1", List.of());
		record(one, "1", "2", "INT");
		Changeset two = commit("h1", "v2", List.of());
		record(two, "2", "3", "INT");

		ChangesetLog.RevertPlan plan = ChangesetLog.INSTANCE.revertPlan(SCOPE, one.seq());
		Changeset revert = ChangesetLog.INSTANCE.appendForwardRevert(SCOPE, plan, "revert",
		 Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.AFTER_COMMIT);
		GenerationalFieldLedger.INSTANCE.invalidateAfterRevert(loader, one.seq());

		GenerationalFieldLedger.Resolved resolved = resolve(revert.seq());

		assertEquals(GenerationalFieldLedger.Resolution.EXACT, resolved.resolution(),
		 "the revert generation resolves single-hop to its target");
		assertEquals(one.seq(), resolved.fromGeneration());
		assertEquals("1", resolved.oldLiteral(), "the expectation is the target's old literal");
		assertEquals("2", resolved.newLiteral(), "the write side is the same fact's new literal");
		assertNotEquals("3", resolved.newLiteral(), "the reverted-away generation's value is never used");
	}

	/**
	 * A14, the other half: the polluted generation's fact is gone from the index after the revert, so
	 * even a direct lookup at that seq cannot resurrect it as an expectation.
	 */
	@Test
	void theRevertedAwayGenerationLeavesNoFactBehind() {
		Changeset one = commit("h1", "v1", List.of());
		record(one, "1", "2", "INT");
		Changeset two = commit("h1", "v2", List.of());
		record(two, "2", "3", "INT");

		int dropped = GenerationalFieldLedger.INSTANCE.invalidateAfterRevert(loader, one.seq());

		assertEquals(1, dropped);
		assertFalse(GenerationalFieldLedger.INSTANCE.latest(loader, CLASS, "f", two.seq())
		 .map(fact -> "3".equals(fact.newLiteral())).orElse(false),
		 "the polluted generation's fact must not be findable any more");
	}
}
