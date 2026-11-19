package remold.ref;

import org.junit.jupiter.api.Test;
import remold.vcs.Changeset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Commit-level replay facts must carry the same canonical form on BOTH sides
 * ({@code docs/topology/16-bytecode-changeset-vcs.md} §3.8).
 *
 * <p><b>What is guarded</b>: the {@code L1} tier's new value comes from the replay plan's real
 * boxed constant, not from its human-readable rendering. Only normalising the old side would make
 * multi-generation replay unusable for {@code float}/{@code double}: {@code -0.0} vs {@code 0.0},
 * NaN payloads and decimal rounding all differ from their text form.</p>
 */
class ReplayFactsFormatTest {

	private static InitializerReplay.ReplayField l1(String desc, Object oldValue, String oldText,
	                                                Object newValue, String newText) {
		return new InitializerReplay.ReplayField("f", desc, "probe/Holder", 0x0001,
		 oldValue, oldValue, oldText, newText, newValue);
	}

	private static boolean canonical(String literal, String kind) {
		if ("FLOAT_BITS".equals(kind)) return literal != null && literal.matches("0x[0-9a-f]{8}");
		if ("DOUBLE_BITS".equals(kind)) return literal != null && literal.matches("0x[0-9a-f]{16}");
		return literal != null;
	}

	/**
	 * E1: {@code -0.0f} on the new side must be the bit pattern. The rendered text says "-0.0",
	 * which is also what {@code 0.0f} renders as, so a text-derived fact would silently conflate
	 * two different initialisers.
	 */
	@Test
	void l1NewSideKeepsRawFloatBits() {
		Changeset.ReplayFact fact = InitFix.l1ReplayFact(l1("F", 1.0f, "1.0", -0.0f, "-0.0"));

		assertEquals("0x3f800000", fact.oldLiteral(), "the old side is already canonical");
		assertEquals("0x80000000", fact.newLiteral(),
		 "the new side must be the raw bit pattern, not the decimal rendering");
		assertEquals("FLOAT_BITS", fact.literalKind());
		assertTrue(canonical(fact.oldLiteral(), fact.literalKind()));
		assertTrue(canonical(fact.newLiteral(), fact.literalKind()),
		 "both sides must satisfy the same canonical form");
	}

	/**
	 * E1: a NaN payload is only visible in the bits. {@code Double.toString} prints "NaN" for every
	 * payload, so a text-derived new side would erase the distinction the CAS comparison needs.
	 */
	@Test
	void l1NewSideKeepsTheNaNPayload() {
		double payload = Double.longBitsToDouble(0x7ff8000000000123L);
		Changeset.ReplayFact fact = InitFix.l1ReplayFact(l1("D", 1.5d, "1.5", payload, "NaN"));

		assertEquals("0x7ff8000000000123", fact.newLiteral(),
		 "the payload must survive; the rendered text 'NaN' carries none of it");
		assertEquals("DOUBLE_BITS", fact.literalKind());
	}

	/**
	 * E2: a record whose new side is still decimal text is *detectable* — kind says
	 * {@code FLOAT_BITS} while the literal does not have the bit-pattern form. Consumers refuse
	 * such records fail-closed (the refusal path lands with the B2 ledger; this case pins the
	 * shape that makes the refusal possible).
	 */
	@Test
	void decimalNewSideIsDetectableAgainstItsKind() {
		Changeset.ReplayFact canonicalFact = InitFix.l1ReplayFact(l1("F", 1.0f, "1.0", 1.5f, "1.5"));
		assertTrue(canonical(canonicalFact.newLiteral(), canonicalFact.literalKind()),
		 "a canonical record passes the check");

		// The shape a pre-bump record has: FLOAT_BITS kind with a decimal literal.
		Changeset.ReplayFact legacyShape = new Changeset.ReplayFact("probe/Holder", "f", "F", "L1",
		 "0x3f800000", "1.5", "FLOAT_BITS", "", "", "");
		assertFalse(canonical(legacyShape.newLiteral(), legacyShape.literalKind()),
		 "a decimal new side cannot masquerade as canonical, so consumers can refuse it");
	}

	/** E1: integral carriers keep decimal text on both sides — the rule is per-kind, not blanket. */
	@Test
	void integralCarriersKeepDecimalOnBothSides() {
		Changeset.ReplayFact fact = InitFix.l1ReplayFact(l1("I", 1, "1", 7, "7"));

		assertEquals("1", fact.oldLiteral());
		assertEquals("7", fact.newLiteral());
		assertEquals("INT", fact.literalKind());
	}

	/** E3: the canonical-form change is visible in the algo version that the Changeset id binds. */
	@Test
	void algoVersionMarksTheRawBitsFormat() {
		assertTrue(Changeset.currentAlgoVersion().startsWith("vcs=2"),
		 "the two-sided raw-bits format must bump algoVersion (actual=" + Changeset.currentAlgoVersion() + ")");
	}
}
