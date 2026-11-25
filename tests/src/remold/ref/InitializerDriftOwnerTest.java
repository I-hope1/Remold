package remold.ref;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INIT-DRIFT must not blame an inherited field for a write that targets somebody else's container.
 *
 * <p>Real-machine noise: a Kotlin class that captures a {@code var} compiles the capture into
 * {@code kotlin.jvm.internal.Ref$IntRef}; the host's {@code <init>} then contains
 * {@code PUTFIELD kotlin/jvm/internal/Ref$IntRef.element}. That field is neither declared by the host nor
 * inherited, yet the old rule ("not declared here ⇒ inherited") reported
 * {@code inherited field initializer changed but will NOT be replayed: element}, pointing the user at a parent
 * class that has nothing to do with the case.</p>
 *
 * <p>The rule now needs proof: a non-declared field counts as inherited only when the writing owner is in the
 * host's resolvable ancestor chain. Nothing provable ⇒ silence (this is a warning surface, so failing quiet beats
 * failing wrong).</p>
 */
class InitializerDriftOwnerTest {

	private static final String HOST = "t/Host";
	private static final String PARENT = "t/Parent";
	private static final String CAPTURE_BOX = "kotlin/jvm/internal/Ref$IntRef";

	@Test
	void writeIntoAForeignContainerIsNotInheritedDrift() {
		// <init> writes CAPTURE_BOX.element with a different value in v2; the box is not an ancestor.
		InitializerDrift.Drift drift = InitializerDrift.detectDetailed(
			host(1), host(2), Set.of(), Set.of(HOST, PARENT, "java/lang/Object"));

		assertTrue(drift.inherited().isEmpty(),
			"a capture-box write must not be reported as inherited drift: " + drift.inherited());
		assertTrue(drift.inheritedReinit().isEmpty(), drift.inheritedReinit().toString());
		assertTrue(drift.changed().isEmpty(), "the host does not declare element: " + drift.changed());
	}

	@Test
	void unresolvableAncestryFailsQuietInsteadOfBlamingAParent() {
		// No ancestry information at all: do not guess "inherited".
		InitializerDrift.Drift drift = InitializerDrift.detectDetailed(host(1), host(2), Set.of(), Set.of());

		assertTrue(drift.inherited().isEmpty() && drift.inheritedReinit().isEmpty(),
			"without proof nothing is reported (warning surface, silence beats a wrong claim): "
				+ drift.inherited() + drift.inheritedReinit());
	}

	// ------------------------------------------------------------------ fixtures

	/**
	 * A host whose {@code <init>} writes one field on a foreign owner.
	 *
	 * @param value the constant written (changing it across versions produces the drift)
	 */
	private static byte[] host(int value) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "t/Host", null, "t/Parent", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "t/Parent", "<init>", "()V", false);
		// Ref.IntRef box: new, dup, <init>, then element = <value>
		mv.visitTypeInsn(Opcodes.NEW, "kotlin/jvm/internal/Ref$IntRef");
		mv.visitInsn(Opcodes.DUP);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "kotlin/jvm/internal/Ref$IntRef", "<init>", "()V", false);
		mv.visitVarInsn(Opcodes.ASTORE, 1);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitLdcInsn(value);
		mv.visitFieldInsn(Opcodes.PUTFIELD, "kotlin/jvm/internal/Ref$IntRef", "element", "I");
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(3, 2);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Same write shape, but the owner is the declared parent — the provable inheritance case. */
	static byte[] hostWritingParent(int value) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "t/Host", null, "t/Parent", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "t/Parent", "<init>", "()V", false);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitLdcInsn(value);
		mv.visitFieldInsn(Opcodes.PUTFIELD, "t/Parent", "element", "I");
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(3, 2);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void provableAncestorWriteIsReported() {
		InitializerDrift.Drift drift = InitializerDrift.detectDetailed(
			hostWritingParent(1), hostWritingParent(2), Set.of(), Set.of(HOST, PARENT, "java/lang/Object"));
		assertEquals(List.of("element"), drift.inherited(),
			"a provable ancestor write is exactly the case this warning exists for");
	}

	@Test
	void provableAncestorWriteWithParentReinitIsReportedSeparately() {
		InitializerDrift.Drift drift = InitializerDrift.detectDetailed(
			hostWritingParent(1), hostWritingParent(2),
			Set.of("t.Parent.element"), Set.of(HOST, PARENT, "java/lang/Object"));
		assertEquals(List.of("element"), drift.inheritedReinit(),
			"@RemoldReinit on the parent field keeps its own, more specific warning");
	}
}
