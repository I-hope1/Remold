package remold.ref;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import remold.ClassDiffUtil;
import remold.ClassDiffUtil.ClassDiff;
import remold.RemoldAgent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CrossClassInitFixTest {
	private static final class ByteLoader extends ClassLoader {
		ByteLoader() { super(CrossClassInitFixTest.class.getClassLoader()); }
		Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
	}

	private static byte[] host(String name, boolean target, String dependencyHost, String dependencyField,
	                           boolean cycle, boolean unrelated) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		String internal = name.replace('.', '/');
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
		if (target) {
			cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "I", null, null).visitEnd();
			if (unrelated) cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "independent", "I", null, null).visitEnd();
		}
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
		MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		if (target) {
			clinit.visitFieldInsn(Opcodes.GETSTATIC, dependencyHost.replace('.', '/'), dependencyField, "I");
			clinit.visitFieldInsn(Opcodes.PUTSTATIC, internal, "value", "I");
			if (unrelated) { clinit.visitIntInsn(Opcodes.BIPUSH, 17); clinit.visitFieldInsn(Opcodes.PUTSTATIC, internal, "independent", "I"); }
		} else if (cycle) {
			clinit.visitIntInsn(Opcodes.BIPUSH, 23);
			clinit.visitFieldInsn(Opcodes.PUTSTATIC, internal, "value", "I");
		}
		clinit.visitInsn(Opcodes.RETURN); clinit.visitMaxs(0, 0); clinit.visitEnd();
		cw.visitEnd(); return cw.toByteArray();
	}

	@Test
	void crossClassCycleRejectsOnlyCyclicFieldsAndKeepsIndependentTask() throws Exception {
		RemoldAgent.INIT_FIX = true;
		ByteLoader loader = new ByteLoader();
		String aName = "crossbatch.A";
		String bName = "crossbatch.B";
		byte[] aOld = host(aName, false, bName, "value", false, false);
		byte[] aNew = host(aName, true, bName, "value", false, true);
		byte[] bOld = host(bName, false, aName, "value", false, false);
		byte[] bNew = host(bName, true, aName, "value", true, false);
		Class<?> a = loader.define(aName, aOld);
		Class<?> b = loader.define(bName, bOld);
		Map<ClassLoader, Map<String, byte[]>> view = new java.util.IdentityHashMap<>();
		view.put(loader, Map.of(aName, aNew, bName, bNew));
		InitFix.beginBatchBytecodeView(view);
		try {
			ClassDiff aDiff = ClassDiffUtil.diff(aOld, aNew);
			ClassDiff bDiff = ClassDiffUtil.diff(bOld, bNew);
			InitFix.transform(a, aNew, aDiff);
			InitFix.transform(b, bNew, bDiff);
			InitFix.finishBatchTransforms();
			assertEquals(InitFix.FieldStatus.REJECTED, InitFix.getLastReport(a).staticFields().get("value").status());
			assertEquals(InitFix.FieldStatus.REJECTED, InitFix.getLastReport(b).staticFields().get("value").status());
			assertEquals(InitFix.FieldStatus.ACCEPTED, InitFix.getLastReport(a).staticFields().get("independent").status());
			assertEquals(Set.of("independent"), InitFix.pendingPlanForTest(a).staticTasks().stream()
			 .map(InitFix.FieldPatchTask::fieldName).collect(java.util.stream.Collectors.toSet()));
		} finally {
			InitFix.afterRedefineFailed(a);
			InitFix.afterRedefineFailed(b);
			InitFix.endBatchBytecodeView();
		}
	}

	@Test
	void loaderScopedOverlaySuppliesDependencyMetadata() throws Exception {
		ByteLoader loader = new ByteLoader();
		String bName = "crossbatch.Metadata";
		byte[] bBytes = host(bName, true, bName, "value", false, false);
		loader.define(bName, bBytes);
		Map<ClassLoader, Map<String, byte[]>> view = new java.util.IdentityHashMap<>();
		view.put(loader, Map.of(bName, bBytes));
		InitFix.beginBatchBytecodeView(view);
		try {
			HierarchyTreeOracle oracle = new HierarchyTreeOracle(loader);
			assertEquals(Optional.of(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
			 oracle.getFieldModifiers(bName.replace('.', '/'), "value", "I"));
		} finally {
			InitFix.endBatchBytecodeView();
		}
	}

	@Test
	void sameNamedHostsInDistinctLoadersHaveDistinctBatchIdentity() throws Exception {
		ByteLoader first = new ByteLoader();
		ByteLoader second = new ByteLoader();
		String name = "crossbatch.Same";
		Class<?> a = first.define(name, host(name, false, name, "value", false, false));
		Class<?> b = second.define(name, host(name, false, name, "value", false, false));
		assertNotSame(a, b);
		assertNotSame(a.getClassLoader(), b.getClassLoader());
	}
}
