package remold.vcs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consumer-side generational reconciliation gate
 * ({@code docs/vcs/05-generational-field-ledger.md} §3.3 / §4-R5, criteria A5, A8, A14, G8).
 *
 * <p><b>What is guarded</b>: the decision that feeds {@code KIND_CONDITIONAL_EXPECTED}. The expectation
 * and the written value must both come from <b>one</b> fact of the <b>target</b> generation; a fact that
 * is out of window, discontinuous, shaped non-canonically, recorded under another {@code algoVersion} or
 * carrying another descriptor must be refused fail-closed instead of being normalised or re-derived from
 * decimal text.</p>
 */
class GenerationalReplayGateTest {

	private static final long SCOPE = 31L;
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

	private Changeset commit(String rawHash, String eff) {
		Map<String, byte[]> now = new LinkedHashMap<>();
		now.put(CLASS, bytes(eff));
		return ChangesetLog.INSTANCE.commit(SCOPE, "r" + rawHash, entry(rawHash, bytes(eff)),
		 Changeset.Manifest.EMPTY, List.of(), Changeset.currentAlgoVersion(), "",
		 Changeset.PatchTiming.AFTER_COMMIT, Map.of(), now);
	}

	private void record(Changeset gen, String field, String desc, String kind,
	                    String oldLiteral, String newLiteral) {
		Changeset.ReplayFact fact = new Changeset.ReplayFact("probe.Holder", field, desc, "L1",
		 oldLiteral, newLiteral, kind, "", "", "");
		assertEquals(GenerationalFieldLedger.RecordResult.ACCEPTED,
		 GenerationalFieldLedger.INSTANCE.record(loader,
		  new GenerationalFieldLedger.LedgerKey(SCOPE, CLASS, field),
		  new GenerationalFieldLedger.GenRef(gen.seq(), gen.id()), fact, gen.algoVersion()),
		 "precondition: the fact must enter the index");
	}

	private GenerationalReplayGate.Decision decide(long targetSeq, String field, String desc, String kind) {
		GenerationalFieldLedger.Resolved resolved = GenerationalFieldLedger.INSTANCE.resolve(
		 loader, ChangesetLog.INSTANCE, SCOPE, CLASS, field, targetSeq);
		return GenerationalReplayGate.decide(resolved, desc, kind);
	}

	// ------------------------------------------------------------------------------------------
	// Positive: the fact of the target generation is used verbatim, both sides.
	// ------------------------------------------------------------------------------------------

	@Test
	void targetGenerationFactBecomesTheExpectationAndTheWrittenValue() {
		Changeset gen = commit("h1", "v1");
		record(gen, "f", "I", "INT", "1", "2");

		GenerationalReplayGate.Decision decision = decide(gen.seq(), "f", "I", "INT");

		assertTrue(decision.writable(), "the fact must be usable: " + decision);
		assertEquals("1", decision.oldLiteral(), "the comparison side comes from the fact");
		assertEquals("2", decision.newLiteral(), "the write side is the same fact's new literal");
		assertEquals(gen.seq(), decision.fromGeneration());
		assertEquals(Integer.valueOf(1), GenerationalReplayGate.toBootstrapConstant("INT", "1", "I"));
	}

	/**
	 * A14: after a forward revert the polluted generation's fact is gone, so the target generation's
	 * pair is the only usable one — and it must be the one that reaches the CAS.
	 */
	@Test
	void afterARevertOnlyTheTargetGenerationsPairIsUsable() {
		Changeset one = commit("h1", "v1");
		record(one, "f", "I", "INT", "1", "2");
		Changeset two = commit("h1", "v2");
		record(two, "f", "I", "INT", "2", "3");

		GenerationalFieldLedger.INSTANCE.invalidateAfterRevert(loader, one.seq());
		Changeset revert = ChangesetLog.INSTANCE.appendForwardRevert(SCOPE,
		 ChangesetLog.INSTANCE.revertPlan(SCOPE, one.seq()), "revert",
		 Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.AFTER_COMMIT);

		GenerationalReplayGate.Decision decision = decide(revert.seq(), "f", "I", "INT");

		assertTrue(decision.writable(), "the revert generation resolves single-hop: " + decision);
		assertEquals("1", decision.oldLiteral(),
		 "expectation = the target generation's old literal (never the reverted-away write)");
		assertEquals("2", decision.newLiteral(), "write value = the same fact's new literal");
		assertFalse("3".equals(decision.newLiteral()),
		 "the reverted-away generation's written value must never be the expectation or the write");
	}

	// ------------------------------------------------------------------------------------------
	// No fact at all is NOT a refusal: the index is a derived index (§2.3), its absence only
	// changes cost. The caller then keeps the round's ClassDiff pair.
	// ------------------------------------------------------------------------------------------

	@Test
	void withoutAnyFactTheGateStaysOutOfTheWay() {
		commit("h1", "v1");

		GenerationalReplayGate.Decision decision = decide(1L, "f", "I", "INT");

		assertTrue(decision.oblivious(), "a missing fact must not turn into a refusal: " + decision);
		assertFalse(decision.writable());
		assertFalse(decision.refused());
	}

	// ------------------------------------------------------------------------------------------
	// Negative branches: each one is a fail-closed refusal.
	// ------------------------------------------------------------------------------------------

	/** §3.3: the structure moved between the fact and the target, so the fact is stale. */
	@Test
	void discontinuousFactIsRefused() {
		Changeset first = commit("h1", "v1");
		record(first, "f", "I", "INT", "1", "2");
		commit("h2", "v2");

		GenerationalReplayGate.Decision decision = decide(2L, "f", "I", "INT");

		assertTrue(decision.refused(), "a stale fact must be refused: " + decision);
		assertNull(decision.oldLiteral(), "a refusal carries no expectation");
	}

	/** §3.3: the fact's generation is out of window (id no longer matches). */
	@Test
	void outOfWindowFactIsRefusedInsteadOfSubstituted() {
		Changeset gen = commit("h1", "v1");
		Changeset.ReplayFact fact = new Changeset.ReplayFact("probe.Holder", "f", "I", "L1",
		 "1", "2", "INT", "", "", "");
		GenerationalFieldLedger.INSTANCE.record(loader,
		 new GenerationalFieldLedger.LedgerKey(SCOPE, CLASS, "f"),
		 new GenerationalFieldLedger.GenRef(gen.seq(), "other-id"), fact, gen.algoVersion());

		GenerationalReplayGate.Decision decision = decide(gen.seq(), "f", "I", "INT");

		assertTrue(decision.refused(), "id mismatch is out-of-window: " + decision);
		assertTrue(decision.reason().contains("no substitute"), decision.reason());
	}

	/**
	 * A8 / G8: a {@code FLOAT_BITS} fact whose literal is decimal text must be refused — never
	 * normalised into a bit pattern (that would be forging a fact).
	 */
	@Test
	void nonCanonicalFloatBitsFactIsRefusedWithoutNormalising() {
		GenerationalFieldLedger.Resolved resolved = new GenerationalFieldLedger.Resolved(
		 GenerationalFieldLedger.Resolution.EXACT, 4L, 4L, new GenerationalFieldLedger.GenRef(4L, "id"),
		 "1.0", "2.5", "FLOAT_BITS", "F", Changeset.currentAlgoVersion());

		GenerationalReplayGate.Decision decision =
		 GenerationalReplayGate.decide(resolved, "F", "FLOAT_BITS");

		assertTrue(decision.refused(), "decimal text in a bit-pattern kind must be refused: " + decision);
		assertTrue(decision.reason().contains("canonical"), decision.reason());
	}

	/** G8: a fact produced under another {@code algoVersion} is refused, not reinterpreted. */
	@Test
	void legacyAlgoVersionIsRefusedOnTheConsumerSide() {
		GenerationalFieldLedger.Resolved resolved = new GenerationalFieldLedger.Resolved(
		 GenerationalFieldLedger.Resolution.EXACT, 4L, 4L, new GenerationalFieldLedger.GenRef(4L, "id"),
		 "1", "2", "INT", "I", "vcs=1|asm=9|jdk=21");

		GenerationalReplayGate.Decision decision = GenerationalReplayGate.decide(resolved, "I", "INT");

		assertTrue(decision.refused(), "a legacy-algoVersion fact must be refused: " + decision);
		assertTrue(decision.reason().contains("algoVersion"), decision.reason());
	}

	/** §4-R2 / A9: the {@code REINIT} tier has no literal, so it can never feed a cross-generation CAS. */
	@Test
	void reinitTierFactIsRefused() {
		GenerationalFieldLedger.Resolved resolved = new GenerationalFieldLedger.Resolved(
		 GenerationalFieldLedger.Resolution.REFUSED_NONE_LITERAL, 0L, 4L,
		 new GenerationalFieldLedger.GenRef(4L, "id"), null, null, "NONE", "", Changeset.currentAlgoVersion());

		GenerationalReplayGate.Decision decision = GenerationalReplayGate.decide(resolved, "I", "INT");

		assertTrue(decision.refused(), "no literal means no reconciliation: " + decision);
	}

	/** The recorded literal kind must match the field descriptor, otherwise the pair is meaningless. */
	@Test
	void mismatchedLiteralKindIsRefused() {
		GenerationalFieldLedger.Resolved resolved = new GenerationalFieldLedger.Resolved(
		 GenerationalFieldLedger.Resolution.EXACT, 4L, 4L, new GenerationalFieldLedger.GenRef(4L, "id"),
		 "1", "2", "INT", "J", Changeset.currentAlgoVersion());

		assertTrue(GenerationalReplayGate.decide(resolved, "J", "LONG").refused(),
		 "INT facts must not be replayed into a long field");
	}

	/** Same field name, different descriptor: the fact is invalid for this field (§2.1). */
	@Test
	void mismatchedDescriptorIsRefused() {
		GenerationalFieldLedger.Resolved resolved = new GenerationalFieldLedger.Resolved(
		 GenerationalFieldLedger.Resolution.EXACT, 4L, 4L, new GenerationalFieldLedger.GenRef(4L, "id"),
		 "1", "2", "INT", "J", Changeset.currentAlgoVersion());

		GenerationalReplayGate.Decision decision = GenerationalReplayGate.decide(resolved, "I", "INT");

		assertTrue(decision.refused(), "the recorded descriptor must still match: " + decision);
		assertTrue(decision.reason().contains("descriptor"), decision.reason());
	}

	// ------------------------------------------------------------------------------------------
	// Literal decoding: canonical forms only, raw bits where the contract says raw bits.
	// ------------------------------------------------------------------------------------------

	/** A8 / G9: {@code -0.0f} and NaN payloads survive the round trip; nothing goes through decimals. */
	@Test
	void floatBitsDecodeToTheExactBitPattern() {
		Object negativeZero = GenerationalReplayGate.toBootstrapConstant("FLOAT_BITS", "0x80000000", "F");
		assertNotNull(negativeZero);
		assertEquals(0x80000000, Float.floatToRawIntBits((Float) negativeZero),
		 "negative zero must keep its sign bit");

		Object quietNan = GenerationalReplayGate.toBootstrapConstant("FLOAT_BITS", "0x7fc00001", "F");
		assertEquals(0x7fc00001, Float.floatToRawIntBits((Float) quietNan),
		 "a NaN payload must be preserved bit for bit");

		Object positiveZero = GenerationalReplayGate.toBootstrapConstant("FLOAT_BITS", "0x00000000", "F");
		assertEquals(0x00000000, Float.floatToRawIntBits((Float) positiveZero));

		Object negativeZeroDouble = GenerationalReplayGate.toBootstrapConstant(
		 "DOUBLE_BITS", "0x8000000000000000", "D");
		assertEquals(0x8000000000000000L, Double.doubleToRawLongBits((Double) negativeZeroDouble));
	}

	/** G9: {@code Z/B/S/C} keep their canonical text and decode to the int carriers the CAS needs. */
	@Test
	void narrowPrimitivesDecodeToTheirIntCarriers() {
		assertEquals(Integer.valueOf(1),
		 GenerationalReplayGate.toBootstrapConstant("BOOLEAN", "true", "Z"));
		assertEquals(Integer.valueOf(0),
		 GenerationalReplayGate.toBootstrapConstant("BOOLEAN", "false", "Z"));
		assertEquals(Integer.valueOf(127),
		 GenerationalReplayGate.toBootstrapConstant("BYTE", "127", "B"));
		assertEquals(Integer.valueOf(-3),
		 GenerationalReplayGate.toBootstrapConstant("SHORT", "-3", "S"));
		assertEquals(Integer.valueOf('A'),
		 GenerationalReplayGate.toBootstrapConstant("CHAR", "A", "C"));
	}

	/** A decode failure is a refusal, never a decimal fallback. */
	@Test
	void undecodableLiteralYieldsNoConstant() {
		assertNull(GenerationalReplayGate.toBootstrapConstant("FLOAT_BITS", "1.5", "F"),
		 "decimal text is not a bit pattern");
		assertNull(GenerationalReplayGate.toBootstrapConstant("INT", "1.5", "I"));
		assertNull(GenerationalReplayGate.toBootstrapConstant("BOOLEAN", "yes", "Z"));
		assertNull(GenerationalReplayGate.toBootstrapConstant("STRING", "abc", "Ljava/lang/String;"),
		 "reference carriers have no replay channel");
		assertNull(GenerationalReplayGate.toBootstrapConstant("INT", "1", "F"),
		 "the kind must agree with the descriptor");
	}

	/** 被裁剪过的键：即使解析结果是 MISS_NO_FACT，也只能拒（降级为 OPAQUE_GUARDED）。 */
	@Test
	void prunedKeyIsRefusedInsteadOfOblivious() {
		GenerationalReplayGate.Decision d = GenerationalReplayGate.decide(null, "I", "INT", true);

		assertTrue(d.refused(), "a pruned field must not be treated as 'never had a fact'");
		assertTrue(d.reason().contains("OPAQUE_GUARDED"),
		 "the reason must name the downgrade target, actual=" + d.reason());
	}
}
