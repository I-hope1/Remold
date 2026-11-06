import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import remold.AnnotationTransformer;
import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;

/**
 * Investigation: pendingAlignedClasses is keyed only by class name, not by
 * (ClassLoader, name). A committed alignment entry for Host$1 in loader A can be
 * consumed by the first load of the same binary name in loader B.
 */
public class PendingAlignedMultiLoaderProbe {
	static int passed = 0, failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=== PendingAlignedMultiLoaderProbe ===");
		String internal = "dup/Host$1";
		String dot = "dup.Host$1";
		byte[] original = gen(internal, false);
		byte[] aligned = gen(internal, true);

		AnnotationTransformer.pendingAlignedClasses.clear();
		// Simulate a successful alignment transaction: commit leaves the pending entry in place.
		RemoldAgent.AlignmentTransaction tx = new RemoldAgent.AlignmentTransaction("dup.Host");
		tx.pendingAdds.put(dot, aligned);
		tx.preRegister();
		tx.commit();
		check(AnnotationTransformer.pendingAlignedClasses.containsKey(internal),
			"precondition: committed alignment entry is still pending for " + internal);

		ClassLoader loaderA = new ClassLoader(PendingAlignedMultiLoaderProbe.class.getClassLoader()) {};
		ClassLoader loaderB = new ClassLoader(PendingAlignedMultiLoaderProbe.class.getClassLoader()) {};
		AnnotationTransformer transformer = new AnnotationTransformer();

		byte[] forA = transformer.transform(loaderA, internal, null, null, original);
		check(forA != null && hasAlignedMarker(forA),
			"loader A first load receives aligned bytes (expected: entry owner)");
		check(!AnnotationTransformer.pendingAlignedClasses.containsKey(internal),
			"pending entry is consumed by loader A's first load");

		// Re-commit the same entry to model a second host / another loader's pending state.
		tx.committed = false;
		tx.preRegistered = false;
		tx.preRegister();
		tx.commit();
		byte[] forB = transformer.transform(loaderB, internal, null, null, original);
		boolean bGotAligned = forB != null && hasAlignedMarker(forB);
		System.out.println("   [OBS] loaderB returned=" + (forB == null ? "null" : "non-null")
			+ ", alignedMarker=" + bGotAligned
			+ ", pendingAfter=" + AnnotationTransformer.pendingAlignedClasses.containsKey(internal));
		check(bGotAligned,
			"BUG REPRODUCED: loader B first load got loader A's aligned bytes (no loader key)");
		check(!AnnotationTransformer.pendingAlignedClasses.containsKey(internal),
			"pending entry is consumed by loader B as well");
		System.out.println("=== SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	static byte[] gen(String name, boolean aligned) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		if (aligned) {
			FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC, "alignedMarker", "I", null, null);
			fv.visitEnd();
		}
		var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	static boolean hasAlignedMarker(byte[] bytes) {
		final boolean[] found = {false};
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override public FieldVisitor visitField(int access, String name, String desc,
				String sig, Object value) {
				if ("alignedMarker".equals(name)) found[0] = true;
				return null;
			}
		}, 0);
		return found[0];
	}
}
