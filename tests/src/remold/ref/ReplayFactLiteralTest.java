package remold.ref;

import org.junit.jupiter.api.Test;
import remold.vcs.GenerationalFieldLedger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Literal canonicalisation for commit-level replay facts
 * ({@code docs/topology/16-bytecode-changeset-vcs.md} §3.8).
 *
 * <p><b>What is guarded</b>: {@code float}/{@code double} facts must be stored as the raw bit
 * pattern. A decimal rendering would make the old-value CAS comparison depend on
 * {@code Double.toString} round-tripping, which is exactly the class of drift the contract bans.</p>
 */
class ReplayFactLiteralTest {

	/** {@code -0.0f} and {@code 0.0f} compare equal but have different bits; the bits are the fact. */
	@Test
	void floatFactsKeepTheRawBitPattern() {
		assertEquals("0x80000000", InitFix.literalOf(-0.0f, "-0.0", "F"),
		 "negative zero must not collapse into zero");
		assertEquals("0x00000000", InitFix.literalOf(0.0f, "0.0", "F"));
		assertEquals("0x3fc00000", InitFix.literalOf(1.5f, "1.5", "F"));
	}

	@Test
	void doubleFactsKeepTheRawBitPattern() {
		assertEquals("0x8000000000000000", InitFix.literalOf(-0.0d, "-0.0", "D"));
		assertEquals("0x7ff8000000000000", InitFix.literalOf(Double.NaN, "NaN", "D"));
		assertEquals("0x3ff8000000000000", InitFix.literalOf(1.5d, "1.5", "D"));
	}

	/** Integral and reference carriers keep their canonical text. */
	@Test
	void otherCarriersKeepCanonicalText() {
		assertEquals("7", InitFix.literalOf(7, "7", "I"));
		assertEquals("7", InitFix.literalOf(7L, "7", "J"));
		assertEquals("abc", InitFix.literalOf("abc", "abc", "Ljava/lang/String;"));
		assertEquals("null", InitFix.literalOf(null, null, "Ljava/lang/String;"),
		 "an absent literal still renders as the null token");
	}

	/** The kind must follow the descriptor, and only {@code String} references are literal carriers. */
	@Test
	void literalKindFollowsTheDescriptor() {
		assertEquals("INT", InitFix.literalKindOf("I"));
		assertEquals("LONG", InitFix.literalKindOf("J"));
		assertEquals("FLOAT_BITS", InitFix.literalKindOf("F"));
		assertEquals("DOUBLE_BITS", InitFix.literalKindOf("D"));
		assertEquals("STRING", InitFix.literalKindOf("Ljava/lang/String;"));
		assertEquals("NONE", InitFix.literalKindOf("Ljava/util/List;"));
		assertEquals("NONE", InitFix.literalKindOf(""));
		assertEquals("NONE", InitFix.literalKindOf(null));
	}

	/**
	 * {@code Z/B/S/C} are the four primitive carriers both the {@code L1} channel and the {@code A}
	 * channel accept (they gate on "compile-time constant of the field's type", not on a subset).
	 * Leaving them at {@code NONE} makes the consumer refuse every such field as
	 * {@code REFUSED_NONE_LITERAL}, i.e. the whole kind silently loses cross-generation replay.
	 */
	@Test
	void narrowPrimitivesHaveAKind() {
		assertEquals("BOOLEAN", InitFix.literalKindOf("Z"));
		assertEquals("BYTE", InitFix.literalKindOf("B"));
		assertEquals("SHORT", InitFix.literalKindOf("S"));
		assertEquals("CHAR", InitFix.literalKindOf("C"));
	}

	/**
	 * Both sides of a fact must land on the same text. The {@code A} tier conditions on the
	 * <b>natural</b> old value (a boxed {@code Boolean}/{@code Byte}/...) but writes the new value
	 * from the <b>raw bytecode constant</b> (always an {@code Integer} for {@code Z/B/S/C}). Rendering
	 * those two through {@code String.valueOf} verbatim yields {@code "true"} vs {@code "1"} and
	 * {@code "A"} vs {@code "65"}, which never compare equal — the conditional CAS would then fail
	 * closed on every generation instead of comparing the initialisers.
	 */
	@Test
	void narrowPrimitivesRenderTheSameTextOnBothSides() {
		assertEquals(InitFix.literalOf(Boolean.TRUE, "true", "Z"), InitFix.literalOf(1, "1", "Z"),
		 "boolean: natural true and raw 1 must render identically");
		assertEquals("true", InitFix.literalOf(1, "1", "Z"));
		assertEquals("false", InitFix.literalOf(0, "0", "Z"));
		assertEquals(InitFix.literalOf((byte) -5, "-5", "B"), InitFix.literalOf(-5, "-5", "B"),
		 "byte: natural byte and raw int must render identically");
		assertEquals("-5", InitFix.literalOf(-5, "-5", "B"));
		assertEquals(InitFix.literalOf((short) 300, "300", "S"), InitFix.literalOf(300, "300", "S"),
		 "short: natural short and raw int must render identically");
		assertEquals("300", InitFix.literalOf(300, "300", "S"));
		assertEquals(InitFix.literalOf('A', "A", "C"), InitFix.literalOf(65, "65", "C"),
		 "char: natural char and raw int must render identically");
		assertEquals("A", InitFix.literalOf(65, "65", "C"));
	}

	/**
	 * A {@code char} fact must stay distinguishable from everything else through its kind, not
	 * through the text: the canonical form is a single character, so a char and a one-letter string
	 * share the text. The kind column is what keeps them apart.
	 */
	@Test
	void narrowPrimitiveKindsAndTextsAgreeWithTheCanonicalChecker() {
		assertEquals(InitFix.literalOf('7', "7", "C"), InitFix.literalOf(7, "7", "I"),
		 "a char and an int may share their text, and they do here");
		assertNotEquals(InitFix.literalKindOf("C"), InitFix.literalKindOf("I"),
		 "the kind column is what separates them");
		assertTrue(GenerationalFieldLedger.isCanonical(InitFix.literalKindOf("C"),
		 InitFix.literalOf('A', "A", "C"), InitFix.literalOf(66, "66", "C")),
		 "a char fact is canonical, so the ledger accepts it instead of refusing the kind");
		assertTrue(GenerationalFieldLedger.isCanonical(InitFix.literalKindOf("B"),
		 InitFix.literalOf((byte) 1, "1", "B"), InitFix.literalOf(2, "2", "B")));
		assertTrue(GenerationalFieldLedger.isCanonical(InitFix.literalKindOf("S"),
		 InitFix.literalOf((short) 1, "1", "S"), InitFix.literalOf(2, "2", "S")));
		assertTrue(GenerationalFieldLedger.isCanonical(InitFix.literalKindOf("Z"),
		 InitFix.literalOf(Boolean.FALSE, "false", "Z"), InitFix.literalOf(1, "1", "Z")));
	}
}
