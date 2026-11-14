package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
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
