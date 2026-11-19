package remold.ref;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import remold.ClassDiffUtil;
import remold.ClassDiffUtil.ClassDiff;
import remold.RemoldAgent;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CrossClassInitFixTest {
	private static final class ByteLoader extends ClassLoader {
		ByteLoader() { super(CrossClassInitFixTest.class.getClassLoader()); }
		Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
	}

	@BeforeEach
	void enableInitFix() {
		RemoldAgent.INIT_FIX = true;
	}

	@AfterEach
	void clearBatchState() {
		InitFix.endBatchBytecodeView();
	}

	/** Old bytes: no fields at all, so the target field is genuinely added. */
	private static byte[] empty(String name) {
		return build(name, null, false, false, false);
	}

	/** Target bytes: one static field `value`, read from {@code dependencyHost.dependencyField} when given. */
	private static byte[] withValue(String name, String dependencyHost, String dependencyField, boolean unrelated) {
		return build(name, dependencyHost == null ? null
		 : new String[]{dependencyHost, dependencyField}, true, unrelated, false);
	}

	/** Adds `derived = value + 1` so the class carries an in-class dependency on `value`. */
	private static byte[] withValueAndDerived(String name, String dependencyHost, String dependencyField) {
		return build(name, dependencyHost == null ? null
		 : new String[]{dependencyHost, dependencyField}, true, false, true);
	}

	private static byte[] build(String name, String[] dependency, boolean withField, boolean unrelated,
	                            boolean derived) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		String internal = name.replace('.', '/');
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
		if (withField) {
			cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "I", null, null).visitEnd();
		}
		if (unrelated) {
			cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "independent", "I", null, null).visitEnd();
		}
		if (derived) {
			cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "derived", "I", null, null).visitEnd();
		}
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		if (!withField) {
			clinit.visitInsn(Opcodes.RETURN);
			clinit.visitMaxs(0, 0);
			clinit.visitEnd();
			cw.visitEnd();
			return cw.toByteArray();
		}
		if (dependency != null) {
			clinit.visitFieldInsn(Opcodes.GETSTATIC, dependency[0].replace('.', '/'), dependency[1], "I");
		} else {
			clinit.visitIntInsn(Opcodes.BIPUSH, 23);
		}
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, internal, "value", "I");
		if (unrelated) {
			clinit.visitIntInsn(Opcodes.BIPUSH, 17);
			clinit.visitFieldInsn(Opcodes.PUTSTATIC, internal, "independent", "I");
		}
		if (derived) {
			clinit.visitFieldInsn(Opcodes.GETSTATIC, internal, "value", "I");
			clinit.visitInsn(Opcodes.ICONST_1);
			clinit.visitInsn(Opcodes.IADD);
			clinit.visitFieldInsn(Opcodes.PUTSTATIC, internal, "derived", "I");
		}
		clinit.visitInsn(Opcodes.RETURN);
		clinit.visitMaxs(0, 0);
		clinit.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Map<ClassLoader, Map<String, byte[]>> view(ClassLoader loader, String a, byte[] aBytes,
	                                                           String b, byte[] bBytes) {
		Map<ClassLoader, Map<String, byte[]>> view = new IdentityHashMap<>();
		Map<String, byte[]> bytes = new LinkedHashMap<>();
		bytes.put(a, aBytes);
		bytes.put(b, bBytes);
		view.put(loader, bytes);
		return view;
	}

	@Test
	void crossClassCycleRejectsOnlyCyclicFieldsAndKeepsIndependentTask() throws Exception {
		ByteLoader loader = new ByteLoader();
		String aName = "crossbatch.A";
		String bName = "crossbatch.B";
		byte[] aOld = empty(aName);
		byte[] bOld = empty(bName);
		byte[] aNew = withValue(aName, bName, "value", true);
		byte[] bNew = withValue(bName, aName, "value", false);
		Class<?> a = loader.define(aName, aOld);
		Class<?> b = loader.define(bName, bOld);
		InitFix.beginBatchBytecodeView(view(loader, aName, aNew, bName, bNew));
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
		}
	}

	@Test
	void loaderScopedOverlaySuppliesDependencyMetadata() {
		ByteLoader loader = new ByteLoader();
		String name = "crossbatch.Metadata";
		byte[] bytes = withValue(name, null, null, false);
		loader.define(name, bytes);
		InitFix.beginBatchBytecodeView(view(loader, name, bytes, name + "2", bytes));
		HierarchyTreeOracle oracle = new HierarchyTreeOracle(loader);
		assertEquals(Optional.of(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
		 oracle.getFieldModifiers(name.replace('.', '/'), "value", "I"));
	}

	@Test
	void candidateWithMissingBatchBytesFailsClosedInsteadOfFallingBackToGlobalCache() throws Exception {
		ByteLoader loader = new ByteLoader();
		String readerName = "crossbatch.Reader";
		String candidateName = "crossbatch.Candidate";
		byte[] readerOld = empty(readerName);
		byte[] candidateOld = empty(candidateName);
		byte[] readerNew = withValue(readerName, candidateName, "value", true);
		byte[] candidateNew = withValue(candidateName, null, null, false);
		Class<?> reader = loader.define(readerName, readerOld);
		loader.define(candidateName, candidateOld);

		// A stale global-cache entry exists for the candidate: the fail-closed branch must ignore it.
		String dotted = candidateName;
		RemoldAgent.bytecodeCache.put(dotted, candidateOld);
		try {
			// The candidate enters the batch (so it is a known candidate) but is dropped before redefine:
			// the view keeps its identity, removes its bytes, and analysis must fail closed instead of reading
			// the stale global-cache copy staged above.
			InitFix.beginBatchBytecodeView(view(loader, readerName, readerNew, candidateName, candidateNew));
			assertTrue(InitFix.batchViewActive(loader));
			InitFix.transform(reader, readerNew, ClassDiffUtil.diff(readerOld, readerNew));
			InitFix.setBatchDefinitions(java.util.List.of(new java.lang.instrument.ClassDefinition(reader, readerNew)));
			assertTrue(InitFix.batchCandidateMissing(loader, candidateName.replace('.', '/')));
			assertNull(new HierarchyTreeOracle(loader).classBytes(candidateName.replace('.', '/')),
			 "missing candidate must not fall back to the stale global cache");
			InitFix.finishBatchTransforms();
			// The rejection itself may be issued by the protected-status gate or by the cross-class candidate
			// gate (defence in depth); what this test pins is that the stale cache is never consulted.
			assertEquals(InitFix.FieldStatus.REJECTED,
			 InitFix.getLastReport(reader).staticFields().get("value").status());
			assertEquals(InitFix.FieldStatus.ACCEPTED,
			 InitFix.getLastReport(reader).staticFields().get("independent").status());
		} finally {
			RemoldAgent.bytecodeCache.remove(dotted);
			InitFix.afterRedefineFailed(reader);
		}
	}

	@Test
	void sameNamedHostsInDifferentLoadersDoNotShareBatchNodes() throws Exception {
		ByteLoader first = new ByteLoader();
		ByteLoader second = new ByteLoader();
		String name = "crossbatch.Same";
		byte[] oldBytes = empty(name);
		byte[] newBytes = withValue(name, name, "value", false);
		Class<?> firstHost = first.define(name, oldBytes);
		Class<?> secondHost = second.define(name, oldBytes);
		assertNotSame(firstHost, secondHost);

		Map<ClassLoader, Map<String, byte[]>> bytes = new IdentityHashMap<>();
		bytes.put(first, Map.of(name, newBytes));
		bytes.put(second, Map.of(name, newBytes));
		InitFix.beginBatchBytecodeView(bytes);
		try {
			// The first loader's node declares a self-cycle through `value`; the second loader's identical
			// class must be judged independently (its own field also self-reads, so both are rejected here) —
			// the point under test is that neither loader's bytes are visible from the other oracle.
			assertNotNull(new HierarchyTreeOracle(first).classBytes(name.replace('.', '/')));
			assertNotNull(new HierarchyTreeOracle(second).classBytes(name.replace('.', '/')));
			assertNull(new HierarchyTreeOracle(new ByteLoader()).classBytes(name.replace('.', '/')),
			 "an unrelated loader must not see either overlay");

			InitFix.transform(firstHost, newBytes, ClassDiffUtil.diff(oldBytes, newBytes));
			InitFix.finishBatchTransforms();
			assertEquals(1, InitFix.getLastReport(firstHost).staticFields().size());
			assertNull(InitFix.getLastReport(secondHost),
			 "second loader's class must not be analysed by the first loader's transform");
		} finally {
			InitFix.afterRedefineFailed(firstHost);
			InitFix.afterRedefineFailed(secondHost);
		}
	}

	@Test
	void crossClassRejectionAlsoRejectsTheInClassDependentsOfTheRejectedField() throws Exception {
		ByteLoader loader = new ByteLoader();
		String readerName = "crossbatch.ReaderDerived";
		String candidateName = "crossbatch.CandidateDerived";
		byte[] readerOld = empty(readerName);
		byte[] candidateOld = empty(candidateName);
		// value reads the candidate (rejected because its bytes are missing), derived = value + 1 is purely
		// in-class. Rejecting value alone would let derived read an unpatched zero.
		byte[] readerNew = withValueAndDerived(readerName, candidateName, "value");
		byte[] candidateNew = withValue(candidateName, null, null, false);
		Class<?> reader = loader.define(readerName, readerOld);
		loader.define(candidateName, candidateOld);
		InitFix.beginBatchBytecodeView(view(loader, readerName, readerNew, candidateName, candidateNew));
		try {
			InitFix.transform(reader, readerNew, ClassDiffUtil.diff(readerOld, readerNew));
			InitFix.setBatchDefinitions(java.util.List.of(new java.lang.instrument.ClassDefinition(reader, readerNew)));
			InitFix.finishBatchTransforms();
			assertEquals(InitFix.FieldStatus.REJECTED,
			 InitFix.getLastReport(reader).staticFields().get("value").status());
			assertEquals(InitFix.FieldStatus.REJECTED,
			 InitFix.getLastReport(reader).staticFields().get("derived").status(),
			 "an in-class dependent field must follow its rejected prerequisite instead of patching from a zero");
			// A fully rejected class stages no plan at all, which is equivalent to "no task survived".
			var stagedPlan = InitFix.pendingPlanForTest(reader);
			assertTrue(stagedPlan == null || stagedPlan.staticTasks().isEmpty(),
			 "no task may survive for a field whose prerequisite was rejected");
		} finally {
			InitFix.afterRedefineFailed(reader);
		}
	}

	@Test
	void inClassDependenciesStayOutOfTheCrossClassGraph() throws Exception {
		ByteLoader loader = new ByteLoader();
		String name = "crossbatch.LocalEdges";
		byte[] oldBytes = empty(name);
		// derived = value + 1 reads only this class: it must not become a self/cross edge, and no "@batch:"
		// token may be attached to it (historical risk: self-loop -> Tarjan cycle -> whole field rejected).
		byte[] newBytes = withValueAndDerived(name, null, null);
		Class<?> host = loader.define(name, oldBytes);
		InitFix.beginBatchBytecodeView(view(loader, name, newBytes, name + "Other", newBytes));
		try {
			InitFix.transform(host, newBytes, ClassDiffUtil.diff(oldBytes, newBytes));
			InitFix.setBatchDefinitions(java.util.List.of(new java.lang.instrument.ClassDefinition(host, newBytes)));
			InitFix.finishBatchTransforms();
			assertEquals(InitFix.FieldStatus.ACCEPTED, InitFix.getLastReport(host).staticFields().get("value").status());
			assertEquals(InitFix.FieldStatus.ACCEPTED, InitFix.getLastReport(host).staticFields().get("derived").status(),
			 "an in-class dependency must not be turned into a cross-class self-cycle");
			assertTrue(InitFix.pendingPlanForTest(host).staticTasks().stream()
			 .flatMap(task -> task.dependencies().stream()).noneMatch(dep -> dep.startsWith("@batch:")),
			 "in-class dependencies must not emit @batch: failure tokens");
		} finally {
			InitFix.afterRedefineFailed(host);
		}
	}
}
