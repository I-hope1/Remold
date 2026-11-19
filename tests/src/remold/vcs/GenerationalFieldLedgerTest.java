package remold.vcs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational field ledger storage layer
 * ({@code docs/topology/20-generational-field-ledger.md} §2 / §3.4).
 *
 * <p><b>What is guarded</b>: facts are indexed by {@code (scope, internal name, field)} plus
 * {@code GenRef(seq, changesetId)}; the un-patched debt survives window pruning while the history
 * index does not; a record whose literal form or algorithm version is not canonical is refused
 * rather than repaired; and a forward revert invalidates every {@code REINIT_APPLIED} row and fact
 * from the reverted-away generations.</p>
 */
class GenerationalFieldLedgerTest {

	private static final long SCOPE = 7L;

	private final ClassLoader loader = new ClassLoader(null) {
	};

	@AfterEach
	void cleanUp() {
		GenerationalFieldLedger.INSTANCE.reset();
	}

	private static Changeset.ReplayFact fact(String oldLiteral, String newLiteral, String kind) {
		return new Changeset.ReplayFact("probe/Holder", "f", "F", "L1",
		 oldLiteral, newLiteral, kind, "", "", "");
	}

	private GenerationalFieldLedger.RecordResult record(long seq, String oldLiteral, String newLiteral,
	                                                    String kind) {
		return record(seq, oldLiteral, newLiteral, kind, Changeset.currentAlgoVersion());
	}

	private GenerationalFieldLedger.RecordResult record(long seq, String oldLiteral, String newLiteral,
	                                                    String kind, String algoVersion) {
		return GenerationalFieldLedger.INSTANCE.record(loader,
		 new GenerationalFieldLedger.LedgerKey(SCOPE, "probe/Holder", "f"),
		 new GenerationalFieldLedger.GenRef(seq, "id-" + seq), fact(oldLiteral, newLiteral, kind), algoVersion);
	}

	/** F1: the index keeps the generation coordinate and both literal sides of the same fact. */
	@Test
	void factsAreIndexedByGenerationAndKeepBothSides() {
		assertEquals(GenerationalFieldLedger.RecordResult.ACCEPTED, record(2, "0x3f800000", "0x40000000", "FLOAT_BITS"));
		assertEquals(GenerationalFieldLedger.RecordResult.ACCEPTED, record(3, "0x40000000", "0x40400000", "FLOAT_BITS"));

		GenerationalFieldLedger.Fact latest = GenerationalFieldLedger.INSTANCE
		 .latest(loader, "probe/Holder", "f", 3L).orElseThrow();
		assertEquals(3L, latest.gen().seq());
		assertEquals("id-3", latest.gen().changesetId(), "the changeset id is kept as the verifier");
		assertEquals("0x40000000", latest.oldLiteral());
		assertEquals("0x40400000", latest.newLiteral());

		GenerationalFieldLedger.Fact earlier = GenerationalFieldLedger.INSTANCE
		 .latest(loader, "probe/Holder", "f", 2L).orElseThrow();
		assertEquals(2L, earlier.gen().seq(), "a target of 2 must not see the seq 3 fact");
	}

	/** F1: scope is part of the key, so two loaders's facts never bleed into each other. */
	@Test
	void scopeSeparatesLedgers() {
		ClassLoader other = new ClassLoader(null) {
		};
		GenerationalFieldLedger.INSTANCE.record(loader,
		 new GenerationalFieldLedger.LedgerKey(SCOPE, "probe/Holder", "f"),
		 new GenerationalFieldLedger.GenRef(1, "a"), fact("1", "2", "INT"), Changeset.currentAlgoVersion());
		assertTrue(GenerationalFieldLedger.INSTANCE.latest(loader, "probe/Holder", "f", 1L).isPresent());
		assertFalse(GenerationalFieldLedger.INSTANCE.latest(other, "probe/Holder", "f", 1L).isPresent(),
		 "another loader's ledger must be empty");
	}

	/**
	 * F3: a {@code vcs=1} record carries a different literal convention, so it is refused instead of
	 * being read with today's rules. Normalising it here would forge a fact (§4-R5).
	 */
	@Test
	void legacyAlgoVersionIsRefused() {
		assertEquals(GenerationalFieldLedger.RecordResult.REFUSED_LEGACY_VERSION,
		 record(1, "0x3f800000", "1.5", "FLOAT_BITS", "vcs=1|asm=9|jdk=21"));
		assertFalse(GenerationalFieldLedger.INSTANCE.hasAnyFact(loader, "probe/Holder", "f"),
		 "a refused record must not enter the index at all");
		assertEquals(1L, GenerationalFieldLedger.INSTANCE.counters().get("refusedLegacy"));
	}

	/** F3: "FLOAT_BITS with a decimal literal" is the exact shape the old L1 new side produced. */
	@Test
	void nonCanonicalLiteralFormIsRefused() {
		assertEquals(GenerationalFieldLedger.RecordResult.REFUSED_NON_CANONICAL,
		 record(1, "0x3f800000", "1.5", "FLOAT_BITS"));
		assertEquals(GenerationalFieldLedger.RecordResult.REFUSED_NON_CANONICAL,
		 record(1, "0x3ff8000000000000", "1.5", "DOUBLE_BITS"));
		assertEquals(GenerationalFieldLedger.RecordResult.REFUSED_NON_CANONICAL,
		 record(1, null, "1", "INT"), "a missing side is not canonical either");
		assertEquals(0L, (long) GenerationalFieldLedger.INSTANCE.counters().get("accepted"));
		assertEquals(3L, (long) GenerationalFieldLedger.INSTANCE.counters().get("refusedNonCanonical"));
	}

	/** F3: canonical bit patterns and integral text pass. */
	@Test
	void canonicalFormsAreAccepted() {
		assertEquals(GenerationalFieldLedger.RecordResult.ACCEPTED, record(1, "0x80000000", "0x00000000", "FLOAT_BITS"));
		assertEquals(GenerationalFieldLedger.RecordResult.ACCEPTED, record(2, "7", "8", "INT"));
		assertTrue(GenerationalFieldLedger.INSTANCE.hasAnyFact(loader, "probe/Holder", "f"));
	}

	/**
	 * F2: window pruning drops the history index but must keep the un-patched debt. Killing the
	 * debt entry would make the next generation believe the field was never owed a write.
	 */
	@Test
	void windowPruningKeepsUnclosedDebt() {
		record(1, "1", "2", "INT");
		record(2, "2", "3", "INT");
		GenerationalFieldLedger.INSTANCE.markUnclosed(loader, "probe/Holder", "f");
		assertEquals(1, GenerationalFieldLedger.INSTANCE.unclosedCount(loader));

		// The window keeps only seq >= 3, so both rows fall out — and the debt must survive anyway.
		int dropped = GenerationalFieldLedger.INSTANCE.pruneToWindow(loader, 3L);

		assertEquals(2, dropped, "both rows are older than the window's oldest seq");
		assertFalse(GenerationalFieldLedger.INSTANCE.hasAnyFact(loader, "probe/Holder", "f"));
		assertEquals(List.of("probe/Holder\u0001f"), GenerationalFieldLedger.INSTANCE.unclosed(loader),
		 "the un-patched debt survives window eviction");
	}

	/** F2: closing the field lets the (already empty) row be collected. */
	@Test
	void closedDebtLeavesWithTheWindow() {
		record(1, "1", "2", "INT");
		GenerationalFieldLedger.INSTANCE.markUnclosed(loader, "probe/Holder", "f");
		GenerationalFieldLedger.INSTANCE.markClosed(loader, "probe/Holder", "f");

		GenerationalFieldLedger.INSTANCE.pruneToWindow(loader, 9L);

		assertEquals(0, GenerationalFieldLedger.INSTANCE.unclosedCount(loader));
		assertFalse(GenerationalFieldLedger.INSTANCE.hasAnyFact(loader, "probe/Holder", "f"));
	}

	/**
	 * F4: a forward revert invalidates the reverted-away generations' engine-written rows and facts.
	 * Keeping them would let the next round treat a polluted generation's value as "already applied".
	 */
	@Test
	void revertInvalidatesEverythingAfterTheTarget() {
		record(1, "1", "2", "INT");
		record(2, "2", "3", "INT");
		GenerationalFieldLedger.INSTANCE.recordReinitApplied(loader, "probe/Holder", "f", "OVERWRITE|abc",
		 "written=1", new GenerationalFieldLedger.GenRef(1, "id-1"));
		GenerationalFieldLedger.INSTANCE.recordReinitApplied(loader, "probe/Holder", "f", "OVERWRITE|abc",
		 "written=1", new GenerationalFieldLedger.GenRef(2, "id-2"));

		int dropped = GenerationalFieldLedger.INSTANCE.invalidateAfterRevert(loader, 1L);

		assertEquals(2, dropped, "the seq 2 fact and the seq 2 engine row are both gone");
		assertEquals(1L, GenerationalFieldLedger.INSTANCE.latest(loader, "probe/Holder", "f", 9L)
		 .orElseThrow().gen().seq(), "only the target generation's fact remains");
		assertEquals(1, GenerationalFieldLedger.INSTANCE.reinitApplied(loader, "probe/Holder", "f").size());
		assertEquals(1L, GenerationalFieldLedger.INSTANCE.reinitApplied(loader, "probe/Holder", "f")
		 .get(0).gen().seq());
	}

	/**
	 * H3: a field that disappears from the new layout can never be patched again, so its debt must be
	 * released with it. Un-patched debt is not evicted by the window, so leaking it here would be
	 * permanent.
	 */
	@Test
	void debtIsReleasedWhenTheFieldIsGone() {
		GenerationalFieldLedger.INSTANCE.markUnclosed(loader, "probe/Holder", "f");
		GenerationalFieldLedger.INSTANCE.markUnclosed(loader, "probe/Other", "f");
		assertEquals(2, GenerationalFieldLedger.INSTANCE.unclosedCount(loader));

		int released = GenerationalFieldLedger.INSTANCE.releaseDebt(loader, "probe/Holder", "f");

		assertEquals(1, released, "only the removed class's row is released");
		assertEquals(1, GenerationalFieldLedger.INSTANCE.unclosedCount(loader),
		 "another class with the same field name keeps its own debt");
		assertTrue(GenerationalFieldLedger.INSTANCE.unclosed(loader).get(0).startsWith("probe/Other"),
		 "releasing must never cross class boundaries");
	}

	/**
	 * F8 (shape half): the ledger keeps only scalars. A value holding a {@code Class} would keep the
	 * loader alive; this case pins the record shape so an accidental field addition shows up.
	 */
	@Test
	void factValuesHoldNoRuntimeHandles() {
		// Declared fields, not getRecordComponents(): the core is compiled through Jabel, which emits
		// records as plain classes without the Record attribute.
		for (java.lang.reflect.Field field : GenerationalFieldLedger.Fact.class.getDeclaredFields()) {
			Class<?> type = field.getType();
			assertFalse(Class.class.isAssignableFrom(type) || ClassLoader.class.isAssignableFrom(type),
			 "Fact must not carry a runtime handle: " + field.getName());
			assertFalse(byte[].class.equals(type), "Fact must not carry bytes: " + field.getName());
		}
		for (java.lang.reflect.Field field
		 : GenerationalFieldLedger.ReinitApplied.class.getDeclaredFields()) {
			Class<?> type = field.getType();
			assertFalse(Class.class.isAssignableFrom(type) || ClassLoader.class.isAssignableFrom(type),
			 "ReinitApplied must not carry a runtime handle: " + field.getName());
		}
	}
}
