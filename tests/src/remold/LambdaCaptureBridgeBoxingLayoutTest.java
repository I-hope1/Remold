package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The capture-bridge layout self-check must survive Kotlin's primitive specialisation.
 *
 * <p>kotlinc emits {@code (Int) -> Int} as an impl method with a <b>primitive</b> descriptor ({@code (I)I}) while
 * the indy's {@code instantiatedMethodType} stays <b>boxed</b>
 * ({@code (Ljava/lang/Integer;)Ljava/lang/Integer;}). javac never splits the two, so the old strict comparison of
 * "captures ++ instantiated SAM params == impl descriptor" never held on that shape and the whole family fell back
 * to ghost stubs ({@code CAPTURE-BRIDGE-SKIPPED … matchCount=0}).</p>
 *
 * <p>What must stay strict: the two directions are not interchangeable. Widening the self-check must not allow an
 * {@code Integer} to be forwarded into an {@code int} parameter — that is a VerifyError at the bridge body, so the
 * pairing gate keeps an exact "forwarded tail" check (invariant B2).</p>
 */
class LambdaCaptureBridgeBoxingLayoutTest {

	// ------------------------------------------------------------------ layout self-check

	@Test
	void kotlinIntToIntLayoutHolds() {
		// val f: (Int) -> Int = { it + 1 }  →  impl (I)I, instantiated (Ljava/lang/Integer;)Ljava/lang/Integer;
		assertTrue(LambdaCaptureBridge.layoutHolds(info("(I)I", "(Ljava/lang/Integer;)Ljava/lang/Integer;")),
			"a primitive impl descriptor must match a boxed instantiated SAM type");
	}

	@Test
	void kotlinNoArgIntLayoutHolds() {
		assertTrue(LambdaCaptureBridge.layoutHolds(info("()I", "()Ljava/lang/Integer;")),
			"boxed return alone must also be tolerated");
	}

	@Test
	void capturesStayStrictAndBoxedParamsFollow() {
		// Captured String + (Int) -> Int: descriptor (Ljava/lang/String;I)I, instantiated (Ljava/lang/String;Ljava/lang/Integer;)Ljava/lang/Integer;
		assertTrue(LambdaCaptureBridge.layoutHolds(
			info("(Ljava/lang/String;I)I", "(Ljava/lang/Integer;)Ljava/lang/Integer;", "Ljava/lang/String;")),
			"captures keep their exact types; only the SAM params may differ by boxing");
	}

	@Test
	void primitiveMismatchIsNotEquivalent() {
		// int impl vs long-shaped instantiated type is NOT a boxing difference.
		assertFalse(LambdaCaptureBridge.layoutHolds(info("(J)J", "(Ljava/lang/Integer;)Ljava/lang/Integer;")),
			"int vs long must never look like a boxing difference");
	}

	@Test
	void referenceMismatchIsNotEquivalent() {
		assertFalse(LambdaCaptureBridge.layoutHolds(info("(Ljava/lang/String;)V", "(Ljava/lang/Object;)V")),
			"reference types must stay exactly equal (no Object widening)");
	}

	@Test
	void arityMismatchIsNotEquivalent() {
		assertFalse(LambdaCaptureBridge.boxingEquivalentLayout("(I)I", "(ILjava/lang/Integer;)Ljava/lang/Integer;"),
			"a different parameter count is never equivalent");
	}

	// ------------------------------------------------------------------ pairing gate (B2)

	@Test
	void kotlinPrimitivePairBuildsABridge() {
		// Old and new versions of the same Kotlin lambda: identical shapes, capture table shrunk by one int.
		LambdaAligner.SyntheticInfo oldInfo = info("(II)I", "(Ljava/lang/Integer;)Ljava/lang/Integer;", "I");
		LambdaAligner.SyntheticInfo newInfo = info("(I)I", "(Ljava/lang/Integer;)Ljava/lang/Integer;");
		oldInfo.hostKey = newInfo.hostKey = "M:run()V";
		assertTrue(LambdaCaptureBridge.bridgeGatesHold(oldInfo, newInfo),
			"a Kotlin primitive lambda pair must be bridgeable");
	}

	@Test
	void boxedToPrimitiveForwardingIsRejected() {
		// Same instantiated SAM type, but the impl descriptors differ in the FORWARDED tail: the bridge would
		// push an Integer into an int parameter. Must be rejected even though the layout check tolerates boxing.
		LambdaAligner.SyntheticInfo oldInfo = info("(I)V", "(Ljava/lang/Integer;)V");
		LambdaAligner.SyntheticInfo newInfo = info("(Ljava/lang/Integer;)V", "(Ljava/lang/Integer;)V");
		oldInfo.hostKey = newInfo.hostKey = "M:run()V";
		assertFalse(LambdaCaptureBridge.bridgeGatesHold(oldInfo, newInfo),
			"forwarding an Integer into an int parameter would be a VerifyError — no bridge");
	}

	@Test
	void javacShapedPairStillBridges() {
		// Regression guard: the ordinary javac shape (instantiated == impl types) keeps working.
		LambdaAligner.SyntheticInfo oldInfo = info("(Ljava/lang/String;I)V", "(I)V", "Ljava/lang/String;");
		LambdaAligner.SyntheticInfo newInfo = info("(I)V", "(I)V");
		oldInfo.hostKey = newInfo.hostKey = "M:run()V";
		assertTrue(LambdaCaptureBridge.bridgeGatesHold(oldInfo, newInfo),
			"javac-shaped lambdas must keep bridging");
	}

	// ------------------------------------------------------------------ fixture

	/** A static (capture-only) synthetic lambda with one shared function-interface type. */
	private static LambdaAligner.SyntheticInfo info(String desc, String instSig, String... captured) {
		LambdaAligner.SyntheticInfo info = new LambdaAligner.SyntheticInfo(
			"invoke$lambda", desc, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
			desc.hashCode(), "invoke$lambda", true);
		info.instSig = instSig;
		info.samSig = "()Ljava/lang/Object;";
		info.fiType = "kotlin/jvm/functions/Function1";
		info.captured = new ArrayList<>(Arrays.asList(captured));
		return info;
	}
}
