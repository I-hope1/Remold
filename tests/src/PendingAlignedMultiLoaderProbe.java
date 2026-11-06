import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import remold.AnnotationTransformer;
import remold.RemoldAgent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;

/**
 * Investigation: pendingAlignedClasses is keyed only by class name, not by
 * (ClassLoader, name). Repro sequence: loader A already loaded Host/Host$1;
 * an alignment transaction commits; A's Host$1 is really redefined; the pending
 * entry remains; loader B's first load of the same binary name consumes it.
 */
public class PendingAlignedMultiLoaderProbe {
	static int passed = 0, failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static void knownBug(String msg) {
		System.out.println("   [KNOWN-BUG] " + msg);
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=== PendingAlignedMultiLoaderProbe ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		if (inst == null) {
			System.err.println("[FAIL] no instrumentation");
			System.exit(1);
		}
		String internal = "dup/Host$1";
		String dot = "dup.Host$1";
		byte[] v1 = gen(internal, false);
		byte[] v2 = gen(internal, true);

		FixtureLoader loaderA = new FixtureLoader(v1, internal);
		Class<?> hostA = Class.forName("dup.Host", true, loaderA);
		Class<?> anonA = Class.forName(dot, true, loaderA);
		check(!hasAlignedMarker(anonA), "precondition: A's Host$1 is loaded without alignedMarker");

		AnnotationTransformer.pendingAlignedClasses.clear();
		RemoldAgent.AlignmentTransaction tx = new RemoldAgent.AlignmentTransaction("dup.Host");
		tx.pendingAdds.put(dot, v2);
		tx.preRegister();
		tx.commit();
		check(AnnotationTransformer.pendingAlignedClasses.containsKey(internal),
			"precondition: committed alignment entry is pending for " + internal);

		// Real redefine of A's already-loaded Host$1; this is the "A loaded, commit, redefine" step.
		inst.redefineClasses(new ClassDefinition(anonA, v2));
		check(hasAlignedMarker(anonA), "A's Host$1 was really redefined with the aligned bytes");
		check(AnnotationTransformer.pendingAlignedClasses.containsKey(internal),
			"pending entry survives A's redefine because it was already loaded (not consumed)");

		// B is a different loader that has never loaded the class.
		ClassLoader loaderB = new FixtureLoader(v1, internal);
		AnnotationTransformer transformer = new AnnotationTransformer();
		byte[] forB = transformer.transform(loaderB, internal, null, null, v1);
		boolean bGotAligned = forB != null && hasAlignedMarker(forB);
		System.out.println("   [OBS] loaderB returned=" + (forB == null ? "null" : "non-null")
			+ ", alignedMarker=" + bGotAligned
			+ ", pendingAfter=" + AnnotationTransformer.pendingAlignedClasses.containsKey(internal));
		knownBug("loader B first load got loader A's aligned bytes (no loader key); pendingAfter="
			+ AnnotationTransformer.pendingAlignedClasses.containsKey(internal));
		check(!AnnotationTransformer.pendingAlignedClasses.containsKey(internal),
			"pending entry is consumed by loader B's first load");
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

	static boolean hasAlignedMarker(Class<?> type) {
		try { type.getDeclaredField("alignedMarker"); return true; }
		catch (NoSuchFieldException e) { return false; }
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

	static final class FixtureLoader extends ClassLoader {
		final byte[] anonBytes;
		final String anonInternal;
		FixtureLoader(byte[] anonBytes, String anonInternal) {
			super(PendingAlignedMultiLoaderProbe.class.getClassLoader());
			this.anonBytes = anonBytes;
			this.anonInternal = anonInternal;
		}
		@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
			if (name.equals(anonInternal.replace('/', '.'))) {
				return defineClass(name, anonBytes, 0, anonBytes.length);
			}
			if (name.equals("dup.Host")) {
				ClassWriter cw = new ClassWriter(0);
				cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "dup/Host", null, "java/lang/Object", null);
				var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
				mv.visitCode();
				mv.visitVarInsn(Opcodes.ALOAD, 0);
				mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
				mv.visitInsn(Opcodes.RETURN);
				mv.visitMaxs(1, 1);
				mv.visitEnd();
				cw.visitEnd();
				return defineClass(name, cw.toByteArray(), 0, cw.toByteArray().length);
			}
			throw new ClassNotFoundException(name);
		}
	}
}
