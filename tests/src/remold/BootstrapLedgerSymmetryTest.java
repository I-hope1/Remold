package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import remold.vcs.Changeset;
import remold.vcs.ChangesetLog;
import remold.vcs.GenerationalFieldLedger;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Commit-side ledger symmetry ({@code docs/topology/20-generational-field-ledger.md} §2.4).
 *
 * <p><b>What is guarded</b>: the {@code GenerationalFieldLedger} reserves a bootstrap slot
 * ({@code scope 0}) for classes on the bootstrap loader. If the commit hook skipped
 * {@code loader == null}, that slot could never be filled while the read side still consults it —
 * a silent asymmetry rather than a visible failure.</p>
 */
class BootstrapLedgerSymmetryTest {

	@AfterEach
	void cleanUp() {
		ChangesetLog.INSTANCE.reset();
		GenerationalFieldLedger.INSTANCE.reset();
	}

	private static Changeset commitWithFact() {
		Changeset.ReplayFact fact = new Changeset.ReplayFact("probe.Holder", "f", "I", "L1",
		 "1", "2", "INT", "", "", "");
		return ChangesetLog.INSTANCE.commit(0L, "bootstrap-round",
		 java.util.Map.of(), Changeset.Manifest.EMPTY, List.of(fact),
		 Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.AFTER_COMMIT,
		 java.util.Map.of(), java.util.Map.of());
	}

	/** The commit hook must feed the bootstrap slot instead of returning early. */
	@Test
	void bootstrapFactsReachTheBootstrapSlot() {
		Changeset cs = commitWithFact();

		RemoldAgent.recordLedgerFacts(null, cs);

		Optional<GenerationalFieldLedger.Fact> fact =
		 GenerationalFieldLedger.INSTANCE.latest(null, "probe/Holder", "f", cs.seq());
		assertTrue(fact.isPresent(), "a bootstrap-loader fact must be indexed, not dropped");
		assertEquals(cs.seq(), fact.get().gen().seq());
		assertEquals("2", fact.get().newLiteral());
		assertEquals(1L, (long) GenerationalFieldLedger.INSTANCE.counters().get("accepted"));
	}
}
