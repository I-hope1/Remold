package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards for the two fallback heuristics: a unique named-field PUT is a hard identity anchor, and
 * renamed fallback pairs whose own-class helper calls are disjoint must not be paired by order.
 *
 * <p>These are deliberately narrow unit tests for the pure decision helpers; the end-to-end L21/L22
 * scenarios are covered by the external lambda regression suite.</p>
 */
class PutAnchorFallbackTest {

	private static final String PUT_A = "PUT:probe/Host.handlerA Lprobe/Handler;";
	private static final String PUT_B = "PUT:probe/Host.handlerB Lprobe/Handler;";

	@Test
	void sameUniquePutIsAnAnchor() {
		assertTrue(LambdaAligner.uniquePutAnchorForTest(
		 PUT_A, PUT_A, List.of(PUT_A, "CALL:probe/Host.reg(Ljava/lang/Runnable;)V"), List.of(PUT_A)));
	}

	@Test
	void duplicatePutDoesNotBecomeAnAnchor() {
		assertFalse(LambdaAligner.uniquePutAnchorForTest(
		 PUT_A, PUT_A, List.of(PUT_A, PUT_A), List.of(PUT_A)),
		 "an if/else duplicate write must not be treated as a unique field identity");
	}

	@Test
	void differentUniquePutsConflict() {
		assertTrue(LambdaAligner.putAnchorConflictForTest(
		 PUT_A, PUT_B, List.of(PUT_A), List.of(PUT_B)),
		 "two unique PUT anchors in the same host point at different fields");
	}

	@Test
	void samePutDoesNotConflict() {
		assertFalse(LambdaAligner.putAnchorConflictForTest(
		 PUT_A, PUT_A, List.of(PUT_A), List.of(PUT_A)));
	}

	@Test
	void nonPutConsumerIsNotAnAnchor() {
		String call = "CALL:probe/Host.reg(Ljava/lang/Runnable;)V";
		assertFalse(LambdaAligner.uniquePutAnchorForTest(call, call, List.of(call), List.of(call)));
		assertFalse(LambdaAligner.putAnchorConflictForTest(call, PUT_A, List.of(call), List.of(PUT_A)));
	}

	@Test
	void disjointOwnClassHelpersAreVetoed() {
		assertTrue(LambdaAligner.disjointSameClassHelpersForTest(
		 Set.of("probe/Host#helperC()V"),
		 Set.of("probe/Host#helperB()V")));
	}

	@Test
	void sharedOwnClassHelperIsNotVetoed() {
		assertFalse(LambdaAligner.disjointSameClassHelpersForTest(
		 Set.of("probe/Host#helperC()V", "probe/Host#log()V"),
		 Set.of("probe/Host#helperB()V", "probe/Host#log()V")));
	}

	@Test
	void ownHelpersExcludeStaticPackagePrivateAndExternalCalls() {
		ClassNode owner = new ClassNode();
		owner.name = "probe/Host";
		owner.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "helperB", "()V", null, null));
		owner.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "helperC", "()V", null, null));
		owner.methods.add(new MethodNode(Opcodes.ACC_STATIC, "staticBusiness", "()V", null, null));
		MethodNode lambda = new MethodNode(Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
		 "lambda$run$0", "()V", null, null);
		lambda.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "probe/Host",
		 "helperB", "()V", false));
		lambda.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "probe/Host",
		 "staticBusiness", "()V", false));
		lambda.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String",
		 "trim", "()Ljava/lang/String;", false));

		Set<String> helpers = LambdaAligner.ownHelpersForTest(lambda, owner);
		assertTrue(helpers.contains("probe/Host#helperB()V"), "an instance helper belongs to the set: " + helpers);
		assertFalse(helpers.contains("probe/Host#staticBusiness()V"),
		 "a static package-private business helper must not trigger the L22 veto: " + helpers);
		assertFalse(helpers.contains("java/lang/String#trim()Ljava/lang/String;"),
		 "external calls must not trigger the L22 veto: " + helpers);
	}

	@Test
	void sameNameIsVetoedOnlyWhenTheOldHelperMovedToAMethodReference() {
		assertTrue(LambdaAligner.helperMovedToNewMethodRefForTest(
		 Set.of("probe/Host#helperC()V"),
		 Set.of("probe/Host#helperC()V")),
		 "L22: this::helperC replaces the old lambda body, so same-name must not steal it");
		assertFalse(LambdaAligner.helperMovedToNewMethodRefForTest(
		 Set.of("probe/Host#doB()V"),
		 Set.of()),
		 "deep2: an in-place doB -> doB2 edit is not a method-reference conversion");
	}
}
