package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import remold.RemoldAgent.LayoutAction;
import remold.RemoldAgent.LayoutDecision;
import remold.RemoldAgent.Liveness;
import remold.RemoldAgent.RemovedMemberScan;
import remold.ref.InitFix;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchHotpathOptimizationTest {

	@Test
	void initFixRequiresTwoPhaseAvoidsLiveProbeWhenNoPendingPatchOrStaticOnly() throws Exception {
		AtomicInteger probeCalls = new AtomicInteger();
		BooleanSupplier countingProbe = () -> {
			probeCalls.incrementAndGet();
			return true;
		};

		assertFalse(InitFix.requiresTwoPhase(null, countingProbe));
		assertEquals(0, probeCalls.get(), "null host must not invoke live instance probe");

		assertFalse(InitFix.requiresTwoPhase(String.class, countingProbe));
		assertEquals(0, probeCalls.get(), "host with no PENDING patch must not invoke live instance probe");

		// Define two isolated test classes to verify real PENDING + static-only vs instance-field lazy probing.
		String staticPkgName = "test.BatchHotpathStaticHost";
		byte[] staticV1 = buildSimpleClass(staticPkgName.replace('.', '/'), false, false);
		byte[] staticV2 = buildSimpleClass(staticPkgName.replace('.', '/'), true, false);
		Class<?> staticHost = defineAndInit(staticPkgName, staticV1);
		try {
			ClassDiffUtil.ClassDiff staticDiff = ClassDiffUtil.diff(staticV1, staticV2);
			InitFix.transform(staticHost, staticV2, staticDiff);
			assertTrue(InitFix.requiresTwoPhase(staticHost, countingProbe),
				"static-only new field must trigger two-phase");
			assertEquals(0, probeCalls.get(),
				"static-only patch does not need live instance probe");
		} finally {
			InitFix.afterRedefineFailed(staticHost);
		}

		String instName = "test.BatchHotpathInstHost";
		byte[] instV1 = buildSimpleClass(instName.replace('.', '/'), false, false);
		byte[] instV2 = buildSimpleClass(instName.replace('.', '/'), false, true);
		Class<?> instHost = defineAndInit(instName, instV1);
		try {
			ClassDiffUtil.ClassDiff instDiff = ClassDiffUtil.diff(instV1, instV2);
			InitFix.transform(instHost, instV2, instDiff);
			assertTrue(InitFix.requiresTwoPhase(instHost, countingProbe),
				"instance new field with live instances must trigger two-phase");
			assertEquals(1, probeCalls.get(),
				"instance patch must consult live probe once");

			probeCalls.set(0);
			assertFalse(InitFix.requiresTwoPhase(instHost, () -> {
				probeCalls.incrementAndGet();
				return false;
			}), "instance patch with zero live instances does not trigger two-phase");
			assertEquals(1, probeCalls.get());
		} finally {
			InitFix.afterRedefineFailed(instHost);
		}
	}

	@Test
	void layoutDecisionEngineEvaluatesLivenessLazily() {
		AtomicInteger probeCalls = new AtomicInteger();
		Supplier<Liveness> liveProbe = () -> {
			probeCalls.incrementAndGet();
			return Liveness.LIVE;
		};

		// 1. COMPATIBLE (pure addition) -> no probe
		LayoutDecision dCompat = RemoldAgent.decideLayout(
			"com.example.Foo", List.of("+ added"), LayoutGate.MODE_REJECT, liveProbe,
			Set.of("com.example.Foo"), RemovedMemberScan.NOT_SCANNED, null, Set.of(), Map.of());
		assertEquals(LayoutAction.PASS, dCompat.action());
		assertEquals(0, probeCalls.get(), "COMPATIBLE verdict must not probe live instances");

		// 2. REMOVED_FIELD (waive & reject) -> no probe
		RemovedMemberScan cleanScan = new RemovedMemberScan(true, List.of(), List.of(), false);
		LayoutDecision dRemovedWaive = RemoldAgent.decideLayout(
			"com.example.Foo", List.of("- gone"), LayoutGate.MODE_REJECT, liveProbe,
			Set.of("com.example.Foo"), cleanScan, null, Set.of(), Map.of());
		assertEquals(LayoutAction.WAIVE_NO_READERS, dRemovedWaive.action());
		assertEquals(0, probeCalls.get(), "REMOVED_FIELD WAIVE_NO_READERS must not probe live instances");

		LayoutDecision dRemovedUnscanned = RemoldAgent.decideLayout(
			"com.example.Foo", List.of("- gone"), LayoutGate.MODE_REJECT, liveProbe,
			Set.of("com.example.Foo"), RemovedMemberScan.NOT_SCANNED, null, Set.of(), Map.of());
		assertEquals(LayoutAction.REJECT, dRemovedUnscanned.action());
		assertEquals(0, probeCalls.get(), "REMOVED_FIELD unscanned must not probe live instances");

		// 3. Static change -> !staticChange short-circuits before probe
		LayoutDecision dStaticType = RemoldAgent.decideLayout(
			"com.example.Foo", List.of("- *count", "+ *count"), LayoutGate.MODE_REJECT, liveProbe,
			Set.of("com.example.Foo"), cleanScan, null, Set.of(), Map.of());
		assertEquals(LayoutAction.REJECT, dStaticType.action());
		assertEquals(0, probeCalls.get(), "static field type change must not probe live instances");

		// 4. Enum constant change -> no probe
		LayoutDecision dEnum = RemoldAgent.decideLayout(
			"com.example.MyEnum", List.of("~ enum constant order changed: A"), LayoutGate.MODE_REJECT, liveProbe,
			Set.of("com.example.MyEnum"), RemovedMemberScan.NOT_SCANNED, null, Set.of(), Map.of());
		assertEquals(LayoutAction.REJECT, dEnum.action());
		assertEquals(0, probeCalls.get(), "enum order change must not probe live instances");

		// 5. Non-static instance field type change -> must invoke probe
		LayoutDecision dInstType = RemoldAgent.decideLayout(
			"com.example.Foo", List.of("- val", "+ val"), LayoutGate.MODE_REJECT, liveProbe,
			Set.of("com.example.Foo"), cleanScan, null, Set.of(), Map.of());
		assertEquals(LayoutAction.REJECT, dInstType.action());
		assertEquals(1, probeCalls.get(), "instance field type change must invoke live probe");
	}

	@Test
	void batchAnonHostMatchesRegexContractWithoutRegex() {
		assertNull(AnonClassAligner.batchAnonHost(null));
		assertEquals("", AnonClassAligner.batchAnonHost(""));
		assertEquals("com.foo.Bar", AnonClassAligner.batchAnonHost("com.foo.Bar"));
		assertEquals("com.foo.Bar", AnonClassAligner.batchAnonHost("com.foo.Bar$1"));
		assertEquals("com.foo.Bar", AnonClassAligner.batchAnonHost("com.foo.Bar$1$2$30"));
		assertEquals("com.foo.Bar$Inner", AnonClassAligner.batchAnonHost("com.foo.Bar$Inner$4"));
		assertEquals("com.foo.Bar$1Local", AnonClassAligner.batchAnonHost("com.foo.Bar$1Local"));
		assertEquals("com.foo.Bar$", AnonClassAligner.batchAnonHost("com.foo.Bar$"));
		assertEquals("com.foo.Bar$", AnonClassAligner.batchAnonHost("com.foo.Bar$$1"));
		assertEquals("", AnonClassAligner.batchAnonHost("$1"));
	}

	@Test
	void annotationTransformerFastPreFilterPreservesBehavior() throws Exception {
		ClassLoader cl = BatchHotpathOptimizationTest.class.getClassLoader();
		byte[] plain = buildSimpleClass("test/PlainForTransformer", false, false);

		// Fast path: no LambdaMetafactory in constant pool returns exact same byte[] reference.
		assertSame(plain, AnnotationTransformer.forceStaticLambdas(plain, "test/PlainForTransformer", cl));

		// Fast path: plain class without @Profile / @Tracker returns null from transform.
		AnnotationTransformer transformer = new AnnotationTransformer();
		assertNull(transformer.transform(cl, "test/PlainForTransformer", null, null, plain));

		// Positive path: class with LambdaMetafactory invokedynamic still gets processed and marked idempotently.
		byte[] withLambda = buildClassWithNonCapturingLambda("test/LambdaHolderForOpt");
		byte[] forced = AnnotationTransformer.forceStaticLambdas(withLambda, "test/LambdaHolderForOpt", cl);
		assertNotNull(forced);
		// Re-running must hit fast marker check and return the exact same array reference.
		assertSame(forced, AnnotationTransformer.forceStaticLambdas(forced, "test/LambdaHolderForOpt", cl));
	}

	@Test
	void classDiffReturnsIndependentInstancesAcrossConsecutiveCalls() {
		byte[] hostAV1 = buildSimpleClass("test/DiffHostA", false, false);
		byte[] hostAV2 = buildSimpleClass("test/DiffHostA", true, false);
		byte[] hostBV1 = buildSimpleClass("test/DiffHostB", false, false);
		byte[] hostBV2 = buildSimpleClass("test/DiffHostB", false, true);

		ClassDiffUtil.ClassDiff diffA = ClassDiffUtil.diff(hostAV1, hostAV2);
		ClassDiffUtil.ClassDiff diffB = ClassDiffUtil.diff(hostBV1, hostBV2);

		assertNotSame(diffA, diffB, "consecutive ClassDiffUtil.diff calls must not alias the same result object");
		assertEquals(Set.of("sVal"), diffA.addedStaticFields);
		assertTrue(diffA.addedInstanceFields.isEmpty());
		assertEquals(Set.of("iVal"), diffB.addedInstanceFields);
		assertTrue(diffB.addedStaticFields.isEmpty());
	}

	private static byte[] buildSimpleClass(String internalName, boolean withStaticField, boolean withInstanceField) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		if (withStaticField) {
			cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sVal", "I", null, 42).visitEnd();
		}
		if (withInstanceField) {
			cw.visitField(Opcodes.ACC_PUBLIC, "iVal", "I", null, null).visitEnd();
		}
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		if (withInstanceField) {
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitIntInsn(Opcodes.BIPUSH, 7);
			mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "iVal", "I");
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] buildClassWithNonCapturingLambda(String internalName) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();

		MethodVisitor make = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
			"()Ljava/lang/Runnable;", null, null);
		make.visitCode();
		Handle bsm = new Handle(
			Opcodes.H_INVOKESTATIC,
			"java/lang/invoke/LambdaMetafactory",
			"metafactory",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
				+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
				+ "Ljava/lang/invoke/CallSite;",
			false);
		Handle target = new Handle(
			Opcodes.H_INVOKESTATIC,
			internalName,
			"lambda$make$0",
			"()V",
			false);
		make.visitInvokeDynamicInsn(
			"run",
			"()Ljava/lang/Runnable;",
			bsm,
			Type.getMethodType("()V"),
			target,
			Type.getMethodType("()V"));
		make.visitInsn(Opcodes.ARETURN);
		make.visitMaxs(0, 0);
		make.visitEnd();

		MethodVisitor body = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
			"lambda$make$0", "()V", null, null);
		body.visitCode();
		body.visitInsn(Opcodes.RETURN);
		body.visitMaxs(0, 0);
		body.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void describeAnnotationDefaultFastPathStillDetectsRealAnnotationChanges() {
		byte[] clsV1 = buildSimpleClass("test/PlainAnnoSkip", false, false);
		byte[] clsV2 = buildSimpleClass("test/PlainAnnoSkip", true, true);
		assertNull(ClassDiffUtil.describeAnnotationDefaultChange(clsV1, clsV2),
			"non-annotation classes must fast-path to null");

		byte[] annoV1 = buildAnnotationClass("test/SampleAnno", 1);
		byte[] annoV1Same = buildAnnotationClass("test/SampleAnno", 1);
		byte[] annoV2Changed = buildAnnotationClass("test/SampleAnno", 2);
		assertNull(ClassDiffUtil.describeAnnotationDefaultChange(annoV1, annoV1Same));
		String changed = ClassDiffUtil.describeAnnotationDefaultChange(annoV1, annoV2Changed);
		assertNotNull(changed, "real @interface default value change must still be reported");
		assertTrue(changed.contains("value"), "change description should name the modified member: " + changed);

		String bad = ClassDiffUtil.describeAnnotationDefaultChange(new byte[]{1, 2, 3}, clsV2);
		assertNotNull(bad);
		assertTrue(bad.startsWith("cannot verify AnnotationDefault"));
	}

	@Test
	void mergedScanPreservesLvtCaptureParamMappingAndLineStrip() {
		String name = "test/MergedScanHost";
		byte[] v1 = buildClassWithCapturedLvtLambda(name, false);
		byte[] v2 = buildClassWithCapturedLvtLambda(name, true);

		// Different line numbers & reordered synthetic method must still match identically
		// when line numbers/LVT nodes are stripped during merged scan, and LVT param names
		// must allow alignment without throwing or dropping capture bridge.
		byte[] aligned = LambdaAligner.align(v1, v2);
		LambdaAligner.AlignmentResult res = LambdaAligner.LAST_RESULT;
		assertNotNull(aligned);
		assertNotNull(res.stats);
		assertEquals(1, res.stats.step1Pairs,
			"captured lambda with shifted line numbers should still exact-match in step1");

		Map<String, String[]> pOld = LambdaAligner.readLambdaParamNames(v1);
		String[] pNames = pOld.get("lambda$compute$0(IILjava/lang/String;)I");
		assertNotNull(pNames);
		assertEquals(3, pNames.length);
		assertEquals("x", pNames[0]);
		assertEquals("y", pNames[1]);
		assertEquals("s", pNames[2]);

		// Without LVT on static receiver-taking synthetic, RECEIVER_NAME normalization stays intact.
		byte[] staticRec = buildClassWithStaticReceiverLambda(name);
		Map<String, String[]> pRec = LambdaAligner.readLambdaParamNames(staticRec);
		String[] recNames = pRec.get("lambda$rec$0(L" + name + ";)V");
		assertNotNull(recNames);
		assertEquals(LambdaAligner.RECEIVER_NAME, recNames[0]);
	}

	@Test
	void initFixReusesDiffNewClassAndPreScannedReinitFields() throws Exception {
		String hostName = "test.ReinitPreScanHost";
		byte[] v1 = buildReinitHost(hostName.replace('.', '/'), 10);
		byte[] v2 = buildReinitHost(hostName.replace('.', '/'), 20);
		Class<?> host = defineAndInit(hostName, v1);
		try {
			ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(v1, v2);
			assertTrue(diff.addedStaticFields.isEmpty());
			assertTrue(diff.addedInstanceFields.isEmpty());
			InitFix.transform(host, v2, diff);
			assertTrue(InitFix.requiresTwoPhase(host, () -> false),
				"@RemoldReinit on existing static field must still enqueue PENDING patch even without added fields");
		} finally {
			InitFix.afterRedefineFailed(host);
		}
	}

	private static byte[] buildAnnotationClass(String internalName, int defaultValue) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17,
			Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION,
			internalName, null, "java/lang/Object", new String[]{"java/lang/annotation/Annotation"});
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "value", "()I", null, null);
		AnnotationVisitor dv = mv.visitAnnotationDefault();
		dv.visit(null, defaultValue);
		dv.visitEnd();
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] buildClassWithCapturedLvtLambda(String internalName, boolean v2Shift) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();

		String lambdaName = v2Shift ? "lambda$compute$1" : "lambda$compute$0";
		MethodVisitor compute = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "compute",
			"(IILjava/lang/String;)Ljava/util/function/IntSupplier;", null, null);
		compute.visitCode();
		Handle bsm = new Handle(
			Opcodes.H_INVOKESTATIC,
			"java/lang/invoke/LambdaMetafactory",
			"metafactory",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
				+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
				+ "Ljava/lang/invoke/CallSite;",
			false);
		Handle target = new Handle(
			Opcodes.H_INVOKESTATIC,
			internalName,
			lambdaName,
			"(IILjava/lang/String;)I",
			false);
		compute.visitVarInsn(Opcodes.ILOAD, 0);
		compute.visitVarInsn(Opcodes.ILOAD, 1);
		compute.visitVarInsn(Opcodes.ALOAD, 2);
		compute.visitInvokeDynamicInsn(
			"getAsInt",
			"(IILjava/lang/String;)Ljava/util/function/IntSupplier;",
			bsm,
			Type.getMethodType("()I"),
			target,
			Type.getMethodType("()I"));
		compute.visitInsn(Opcodes.ARETURN);
		compute.visitMaxs(0, 0);
		compute.visitEnd();

		MethodVisitor body = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
			lambdaName, "(IILjava/lang/String;)I", null, null);
		body.visitCode();
		Label start = new Label();
		Label end = new Label();
		body.visitLabel(start);
		body.visitLineNumber(v2Shift ? 42 : 10, start);
		body.visitVarInsn(Opcodes.ILOAD, 0);
		body.visitVarInsn(Opcodes.ILOAD, 1);
		body.visitInsn(Opcodes.IADD);
		body.visitVarInsn(Opcodes.ALOAD, 2);
		body.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
		body.visitInsn(Opcodes.IADD);
		body.visitLabel(end);
		body.visitInsn(Opcodes.IRETURN);
		body.visitLocalVariable("x", "I", null, start, end, 0);
		body.visitLocalVariable("y", "I", null, start, end, 1);
		body.visitLocalVariable("s", "Ljava/lang/String;", null, start, end, 2);
		body.visitMaxs(0, 0);
		body.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] buildClassWithStaticReceiverLambda(String internalName) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		MethodVisitor body = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
			"lambda$rec$0", "(L" + internalName + ";)V", null, null);
		body.visitCode();
		body.visitInsn(Opcodes.RETURN);
		body.visitMaxs(0, 0);
		body.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] buildReinitHost(String internalName, int staticVal) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "COUNT", "I", null, null);
		AnnotationVisitor av = fv.visitAnnotation("Lremold/annotation/RemoldReinit;", true);
		av.visitEnd();
		fv.visitEnd();

		MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		clinit.visitIntInsn(Opcodes.BIPUSH, staticVal);
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "COUNT", "I");
		clinit.visitInsn(Opcodes.RETURN);
		clinit.visitMaxs(0, 0);
		clinit.visitEnd();

		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Class<?> defineAndInit(String dotName, byte[] bytes) throws Exception {
		ClassLoader cl = new ClassLoader(BatchHotpathOptimizationTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (dotName.equals(name)) {
					return defineClass(name, bytes, 0, bytes.length);
				}
				return super.findClass(name);
			}
		};
		return Class.forName(dotName, true, cl);
	}
}
