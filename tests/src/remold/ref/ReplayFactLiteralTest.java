package remold.ref;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
