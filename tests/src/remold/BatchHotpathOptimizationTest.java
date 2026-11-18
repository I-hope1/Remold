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
import remold.RemoldAgent.GateCachedDiff;
import remold.RemoldAgent.LayoutAction;
import remold.RemoldAgent.LayoutDecision;
import remold.RemoldAgent.Liveness;
import remold.RemoldAgent.RemovedMemberScan;
import remold.RemoldAgent.RoundSkips;
import remold.annotation.OnReload;
import remold.ref.AccessAlign;
import remold.ref.ClassHierarchyOracle.HierarchyCheck;
import remold.ref.InitFix;
import remold.ref.MixinMemberAlign;
import remold.ref.StageBInstanceCache;
import remold.util.LibTool;

import java.lang.instrument.ClassDefinition;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

	@Test
	void diffLogAndPerfDetailDefaultsAndSwitchBehavior() {
		byte[] v1 = buildSimpleClass("test/DiffGateHost", false, false);
		byte[] v2 = buildSimpleClass("test/DiffGateHost", true, false);
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(v1, v2);

		RemoldAgent.Logger prevLogger = RemoldAgent.logger;
		boolean prevDiffLog = RemoldAgent.DIFF_LOG;
		boolean prevDebug = RemoldAgent.DEBUG;
		List<String> infos = new ArrayList<>();
		List<String> logs = new ArrayList<>();
		RemoldAgent.logger = new RemoldAgent.Logger() {
			@Override public void log(String msg) { logs.add(msg); }
			@Override public void info(String msg) { infos.add(msg); }
			@Override public void warn(String msg) {}
			@Override public void error(String msg) {}
			@Override public void error(String msg, Throwable t) {}
		};
		try {
			assertTrue(prevDiffLog, "DIFF_LOG must default to true");
			assertFalse(RemoldAgent.PERF_LOG, "PERF_LOG (detailed breakdown) must default to false");

			RemoldAgent.DIFF_LOG = false;
			RemoldAgent.DEBUG = false;
			ClassDiffUtil.logDiff("test.DiffGateHost", diff);
			assertTrue(infos.isEmpty(), "DIFF_LOG=false & DEBUG=false must suppress info [DIFF]");
			assertTrue(logs.stream().anyMatch(m -> m.startsWith("[DIFF] ")),
				"DIFF_LOG=false must demote [DIFF] block to debug log()");

			infos.clear();
			logs.clear();
			RemoldAgent.DIFF_LOG = true;
			ClassDiffUtil.logDiff("test.DiffGateHost", diff);
			assertTrue(infos.stream().anyMatch(m -> m.startsWith("[DIFF] ")),
				"DIFF_LOG=true must emit [DIFF] via info()");
		} finally {
			RemoldAgent.logger = prevLogger;
			RemoldAgent.DIFF_LOG = prevDiffLog;
			RemoldAgent.DEBUG = prevDebug;
		}
	}

	@Test
	void alignFastPathsPreserveSemanticsAndAvoidRewritingWhenUnchanged() {
		String internal = "test/AlignFastPathHost";
		byte[] baseline = buildAlignHost(internal, Opcodes.ACC_PUBLIC, "handler$zce000$cfg$tick", 1);
		byte[] bodyOnlyEdit = buildAlignHost(internal, Opcodes.ACC_PUBLIC, "handler$zce000$cfg$tick", 2);
		byte[] renamedEdit = buildAlignHost(internal, Opcodes.ACC_PUBLIC, "handler$zzc999$cfg$tick", 2);
		byte[] narrowedEdit = buildAlignHost(internal, Opcodes.ACC_PRIVATE, "handler$zce000$cfg$tick", 2);

		assertTrue(MixinMemberAlign.hasMixinGeneratedMembers(baseline));
		assertFalse(MixinMemberAlign.hasMixinGeneratedMembers(buildSimpleClass(internal, false, false)));

		MixinMemberAlign.Result noOpMember = MixinMemberAlign.align(bodyOnlyEdit, baseline);
		assertFalse(noOpMember.changed());
		assertSame(bodyOnlyEdit, noOpMember.bytes,
			"metadata-identical MixinMemberAlign must return candidate bytes untouched");

		MixinMemberAlign.Result remappedMember = MixinMemberAlign.align(renamedEdit, baseline);
		assertTrue(remappedMember.changed());
		assertEquals(1, remappedMember.renamed);
		assertNotSame(renamedEdit, remappedMember.bytes);

		AccessAlign.Result noOpAccess = AccessAlign.align(bodyOnlyEdit, baseline);
		assertFalse(noOpAccess.changed());
		assertTrue(noOpAccess.narrowed.isEmpty());
		assertSame(bodyOnlyEdit, noOpAccess.bytes,
			"unchanged access flags must return candidate bytes without ClassWriter rewrite");

		AccessAlign.Result widenedAccess = AccessAlign.align(narrowedEdit, baseline);
		assertTrue(widenedAccess.changed());
		assertTrue(widenedAccess.adjusted > 0);
		assertTrue(widenedAccess.narrowed.isEmpty());
	}

	@Test
	void processAnnotationsSkipsClassesWithoutOnReloadDescriptor() throws Exception {
		String withoutName = "test.NoOnReloadHost";
		byte[] withoutBytes = buildSimpleClass(withoutName.replace('.', '/'), false, false);
		Class<?> withoutHost = defineAndInit(withoutName, withoutBytes);

		String withName = "test.WithOnReloadHost";
		byte[] withBytes = buildOnReloadHost(withName.replace('.', '/'));
		Class<?> withHost = defineAndInit(withName, withBytes);

		MainThreadDispatcher prevDispatcher = RemoldAgent.getMainThreadDispatcher();
		DispatchTally tally = new DispatchTally();
		RemoldAgent.setMainThreadDispatcher(tally);
		try {
			RemoldAgent.processAnnotations(List.of(new ClassDefinition(withoutHost, withoutBytes)));
			assertEquals(0, tally.own(),
				"class without @OnReload descriptor must skip getDeclaredMethods dispatch");

			RemoldAgent.processAnnotations(List.of(new ClassDefinition(withHost, withBytes)));
			assertEquals(1, tally.own(),
				"class with @OnReload descriptor must dispatch annotation processing");
			assertEquals(1, withHost.getField("hits").getInt(null),
				"@OnReload static method must still run once");
		} finally {
			RemoldAgent.setMainThreadDispatcher(prevDispatcher);
		}
	}

	/**
	 * 派发计数器：只把<b>本线程同步派发</b>的任务算作"本次调用的决策"，外来任务单独记账。
	 *
	 * <p><b>为什么必须按线程分账</b>：{@code RemoldAgent.scheduleDeferredPostRedefine} 把后置任务
	 * 交给共享的定时器延迟 20 ms 再 {@code postToMain}，而 {@code postToMain} 用的是<b>那一刻</b>
	 * 装着的主线程派发器。于是"上一个用例最后一行触发了外部重定义"的定时任务，会落到
	 * <b>下一个用例</b>刚装好的派发器上——落地线程是 {@code HotSwap-Scheduler}，不是测试线程。
	 * 集成树里 {@code SourceBytecodeCacheMatrixTest} 的外部重定义段正是这个形态，它与本类相邻时
	 * 会把 `dispatches` 顶到 1，让"没有 @OnReload 描述符就不该派发"的断言偶发变红
	 * （2026-11-18 集成树全量跑挂过一次；{@link #delayedForeignDispatchDoesNotPolluteOnReloadSkipCounting()}
	 * 用同一机制做确定性复现）。</p>
	 *
	 * <p>任务本身照常执行（不改变派发语义），只是不计入本次调用的读数。</p>
	 */
	private static final class DispatchTally implements MainThreadDispatcher {
		private final Thread testThread = Thread.currentThread();
		private final AtomicInteger own = new AtomicInteger();
		private final AtomicInteger foreign = new AtomicInteger();

		@Override
		public void dispatch(Runnable task) {
			if (Thread.currentThread() == testThread) own.incrementAndGet();
			else foreign.incrementAndGet();
			task.run();
		}

		int own() { return own.get(); }

		int foreign() { return foreign.get(); }
	}

	/**
	 * 确定性复现并锁定"外来延迟派发"的形态：别的用例排下的 20 ms 后置任务落在本用例窗口里时，
	 * 既不能把 {@code own()} 顶起来，也不能被当成 {@code processAnnotations} 的决定。
	 *
	 * <p>机制证据见 {@link DispatchTally}；这里排一个真实的后置任务（{@link RemoldAgent#scheduleDeferredPostRedefine}）
	 * 而不是伪造线程，保证锁的是生产路径。</p>
	 */
	@Test
	void delayedForeignDispatchDoesNotPolluteOnReloadSkipCounting() throws Exception {
		RemoldAgent.scheduleDeferredPostRedefine(LeakProbe.class);

		String name = "test.NoOnReloadHostUnderLeak";
		byte[] bytes = buildSimpleClass(name.replace('.', '/'), false, false);
		Class<?> host = defineAndInit(name, bytes);

		MainThreadDispatcher prev = RemoldAgent.getMainThreadDispatcher();
		DispatchTally tally = new DispatchTally();
		RemoldAgent.setMainThreadDispatcher(tally);
		try {
			RemoldAgent.processAnnotations(List.of(new ClassDefinition(host, bytes)));
			assertEquals(0, tally.own(),
				"a class without @OnReload must not dispatch, even while a foreign task is in flight");

			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
			while (System.nanoTime() < deadline && tally.foreign() == 0) Thread.sleep(10);

			assertTrue(tally.foreign() >= 1,
				"repro precondition: the deferred post-redefine task must land on the installed dispatcher "
					+ "from another thread (this is the leak that used to flake the sibling assertion)");
			assertEquals(0, tally.own(),
				"a foreign dispatch must never be counted as this call's dispatch decision");
		} finally {
			RemoldAgent.setMainThreadDispatcher(prev);
		}
	}

	/** 延迟后置任务的载体：只要是合法 Class 即可（没有 @OnReload）。 */
	static final class LeakProbe { }

	@Test
	void processAnnotationsInstanceFastPathSupportsPrivateNonVoidAndIsolatesErrors() {
		SingleReloadTarget targetA = new SingleReloadTarget();
		SingleReloadTarget targetB = new SingleReloadTarget();
		StageBInstanceCache.clear();
		StageBInstanceCache.put(SingleReloadTarget.class, new Object[]{targetA, targetB});
		try {
			RemoldAgent.processAnnotationsInternal(SingleReloadTarget.class);
		} finally {
			StageBInstanceCache.clear();
		}
		assertEquals(1, targetA.calls);
		assertEquals(1, targetB.calls);

		MultiReloadTarget multi = new MultiReloadTarget();
		StageBInstanceCache.put(MultiReloadTarget.class, new Object[]{multi});
		try {
			RemoldAgent.processAnnotationsInternal(MultiReloadTarget.class);
		} finally {
			StageBInstanceCache.clear();
		}
		assertEquals(1, multi.firstHits);
		assertEquals(1, multi.secondHits);
	}

	@Test
	void resolveAnonHostCandidateFiltersNonDigitsAndSiblingPrefixes() {
		Set<String> single = Collections.singleton("com.Foo");
		assertNull(RemoldAgent.resolveAnonHostCandidate("com.Foo", single, "com.Foo"));
		assertNull(RemoldAgent.resolveAnonHostCandidate("com.Foo$Inner", single, "com.Foo"));
		assertNull(RemoldAgent.resolveAnonHostCandidate("com.FooBar$1", single, "com.Foo"));
		assertEquals("com.Foo", RemoldAgent.resolveAnonHostCandidate("com.Foo$1", single, "com.Foo"));
		assertEquals("com.Foo", RemoldAgent.resolveAnonHostCandidate("com.Foo$1$2", single, "com.Foo"));

		Set<String> multi = Set.of("com.Foo", "com.Bar");
		assertEquals("com.Bar", RemoldAgent.resolveAnonHostCandidate("com.Bar$3", multi, null));
		assertNull(RemoldAgent.resolveAnonHostCandidate("com.Other$3", multi, null));
	}

	static final class SingleReloadTarget {
		int calls;

		@OnReload
		private int reloadAndReturnNonVoid() {
			calls++;
			return 42;
		}
	}

	static final class MultiReloadTarget {
		int firstHits;
		int secondHits;

		@OnReload
		private void firstThrows() {
			firstHits++;
			throw new IllegalStateException("boom");
		}

		@OnReload
		public void secondStillRuns() {
			secondHits++;
		}
	}

	@Test
	void passthroughTransformCountsHooksAndSkipsHierarchyRegistration() throws Exception {
		String internal = "test/PassthroughSubclassProbe";
		String dotName = internal.replace('/', '.');
		byte[] bytes = buildSimpleClass(internal, false, false);
		Class<?> subClass = defineAndInit(dotName, bytes);
		AnnotationTransformer transformer = new AnnotationTransformer();

		boolean prevPassthrough = RemoldAgent.INTERNAL_AFFECTED_PASSTHROUGH;
		RemoldAgent.INTERNAL_AFFECTED_PASSTHROUGH = true;
		RemoldAgent.enterInternalRedefine(Collections.singleton("test.SomeOtherTarget"));
		try {
			byte[] out = transformer.transform(subClass.getClassLoader(), internal, subClass, null, bytes);
			assertNull(out, "internal affected subclass passthrough must return null");
			RemoldAgent.InternalRedefineContext ctx = RemoldAgent.INTERNAL_CONTEXT.get();
			assertNotNull(ctx);
			assertEquals(1, ctx.passthroughHookHits);
			assertEquals(0, ctx.targetHookHits);
			assertEquals(HierarchyCheck.UNKNOWN,
				AnnotationTransformer.HierarchyTree.checkAssignableFrom("java/lang/Number", internal, null),
				"passthrough fast-path must skip HierarchyTree.register for affected subclasses");
		} finally {
			RemoldAgent.exitInternalRedefine();
			RemoldAgent.INTERNAL_AFFECTED_PASSTHROUGH = prevPassthrough;
		}
	}

	private static byte[] buildAlignHost(String internalName, int methodAccess, String handlerName, int bodyVal) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();

		MethodVisitor handler = cw.visitMethod(methodAccess, handlerName, "()I", null, null);
		handler.visitCode();
		handler.visitLdcInsn(bodyVal);
		handler.visitInsn(Opcodes.IRETURN);
		handler.visitMaxs(1, 1);
		handler.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] buildOnReloadHost(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "hits", "I", null, 0).visitEnd();
		MethodVisitor onReload = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onReload", "()V", null, null);
		AnnotationVisitor av = onReload.visitAnnotation(Type.getDescriptor(OnReload.class), true);
		av.visitEnd();
		onReload.visitCode();
		onReload.visitFieldInsn(Opcodes.GETSTATIC, internalName, "hits", "I");
		onReload.visitInsn(Opcodes.ICONST_1);
		onReload.visitInsn(Opcodes.IADD);
		onReload.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "hits", "I");
		onReload.visitInsn(Opcodes.RETURN);
		onReload.visitMaxs(2, 0);
		onReload.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void diffStructuralMatchesFullDiffStructureAndInitFixRequestsBodiesOnlyWhenNeeded() throws Exception {
		String bodyHostName = "test.DiffStructuralBodyHost";
		byte[] v1 = buildAlignHost(bodyHostName.replace('.', '/'), Opcodes.ACC_PUBLIC, "handler$zce000$cfg$tick", 1);
		byte[] v2 = buildAlignHost(bodyHostName.replace('.', '/'), Opcodes.ACC_PUBLIC, "handler$zce000$cfg$tick", 2);
		Class<?> bodyHost = defineAndInit(bodyHostName, v1);

		ClassDiffUtil.ClassDiff full = ClassDiffUtil.diff(v1, v2);
		ClassDiffUtil.ClassDiff structural = ClassDiffUtil.diffStructural(v1, v2);
		assertEquals(full.hierarchyChanged, structural.hierarchyChanged);
		assertEquals(full.changedFields, structural.changedFields);
		assertEquals(full.addedMethods, structural.addedMethods);
		assertEquals(full.removedMethods, structural.removedMethods);
		assertEquals(List.of("handler$zce000$cfg$tick()I"), full.modifiedBodyMethods);
		assertTrue(structural.modifiedBodyMethods.isEmpty(),
			"structural diff skips method body fingerprinting");
		assertFalse(InitFix.needsMethodBodies(bodyHost, structural),
			"pure body edit without added fields/@RemoldReinit/ledger/replay does not need method bodies");

		String addedFieldName = "test.DiffStructuralFieldHost";
		byte[] fv1 = buildSimpleClass(addedFieldName.replace('.', '/'), false, false);
		byte[] fv2 = buildSimpleClass(addedFieldName.replace('.', '/'), false, true);
		Class<?> fieldHost = defineAndInit(addedFieldName, fv1);

		ClassDiffUtil.ClassDiff fieldStructural = ClassDiffUtil.diffStructural(fv1, fv2);
		assertEquals(Set.of("iVal"), fieldStructural.addedInstanceFields);
		assertTrue(InitFix.needsMethodBodies(fieldHost, fieldStructural),
			"added instance field must request full method bodies for InitFix");
		ClassDiffUtil.ensureFullClassNodes(fieldStructural, fv1, fv2);
		assertFalse(fieldStructural.newClass.methods.isEmpty());
		assertTrue(fieldStructural.newClass.methods.get(0).instructions.size() > 0,
			"ensureFullClassNodes must populate method instructions");

		// Calling diff() repeatedly on the same oldBytes must remain correct even with mutated-array check.
		byte[] copyV1 = v1.clone();
		ClassDiffUtil.ClassDiff first = ClassDiffUtil.diff(copyV1, v2);
		assertEquals(List.of("handler$zce000$cfg$tick()I"), first.modifiedBodyMethods);
		ClassDiffUtil.ClassDiff second = ClassDiffUtil.diff(copyV1, v2);
		assertEquals(first.modifiedBodyMethods, second.modifiedBodyMethods);
	}

	@Test
	void removedMemberScannerSharesStructuralDiffCacheAndSkipsIdenticalBytes() throws Exception {
		String hostName = "test.BatchHotpathSharedDiffHost";
		byte[] v1 = buildAlignHost(hostName.replace('.', '/'), Opcodes.ACC_PUBLIC, "handler$zce000$cfg$tick", 1);
		byte[] v2Body = buildAlignHost(hostName.replace('.', '/'), Opcodes.ACC_PUBLIC, "handler$zce000$cfg$tick", 2);
		Class<?> host = defineAndInit(hostName, v1);

		boolean prevDiffLog = RemoldAgent.DIFF_LOG;
		boolean prevDebug = RemoldAgent.DEBUG;
		RemoldAgent.bytecodeCache.put(hostName, v1);
		RemoldAgent.loadedClassesMap.put(hostName, host);
		try {
			RemoldAgent.DIFF_LOG = false;
			RemoldAgent.DEBUG = false;

			// 1. Identical byte arrays short-circuit without touching ClassDiffUtil or polluting cache.
			Map<String, GateCachedDiff> identicalCache = new HashMap<>();
			assertTrue(RemovedMemberScanner.scan(Map.of(hostName, v1.clone()), Map.of(), identicalCache).isEmpty());
			assertTrue(identicalCache.isEmpty(), "identical bytes must skip diff computation and cache put");

			// 2. Method-body edit populates structural cache in scanner and is reused by applyRedefineLayoutGate.
			Map<String, GateCachedDiff> sharedCache = new HashMap<>();
			assertTrue(RemovedMemberScanner.scan(Map.of(hostName, v2Body), Map.of(), sharedCache).isEmpty());
			GateCachedDiff fromScanner = sharedCache.get(hostName);
			assertNotNull(fromScanner, "scanner miss must populate GateCachedDiff");
			assertSame(v1, fromScanner.oldBytes());
			assertSame(v2Body, fromScanner.newBytes());
			assertFalse(fromScanner.bodyDiffComputed(),
				"fast batch mode (DIFF_LOG=false, DEBUG=false) must only run structural diff");
			assertFalse(fromScanner.fullNodesLoaded());

			Map<String, byte[]> batch = new HashMap<>();
			batch.put(hostName, v2Body);
			RemoldAgent.applyRedefineLayoutGate(
				batch, Map.of(), new HashMap<>(), new RoundSkips(), sharedCache, new long[1]);
			GateCachedDiff afterGate = sharedCache.get(hostName);
			assertNotNull(afterGate);
			assertSame(fromScanner.diff(), afterGate.diff(),
				"applyRedefineLayoutGate must reuse the ClassDiff computed by RemovedMemberScanner");

			// 3. When DIFF_LOG is enabled, scanner upgrades to full diff so alignDiff logDiff needs no re-parse.
			RemoldAgent.DIFF_LOG = true;
			Map<String, GateCachedDiff> diffLogCache = new HashMap<>();
			RemovedMemberScanner.scan(Map.of(hostName, v2Body), Map.of(), diffLogCache);
			GateCachedDiff loggedEntry = diffLogCache.get(hostName);
			assertNotNull(loggedEntry);
			assertTrue(loggedEntry.bodyDiffComputed());
			assertTrue(loggedEntry.fullNodesLoaded());
			assertEquals(List.of("handler$zce000$cfg$tick()I"), loggedEntry.diff().modifiedBodyMethods);
		} finally {
			RemoldAgent.bytecodeCache.remove(hostName);
			RemoldAgent.loadedClassesMap.remove(hostName);
			RemoldAgent.DIFF_LOG = prevDiffLog;
			RemoldAgent.DEBUG = prevDebug;
		}
	}

	@Test
	void resetLiveInstanceCacheClearsBatchStageBMillis() {
		RemoldAgent.BATCH_STAGE_B_MILLIS.set(42L);
		RemoldAgent.resetLiveInstanceCache();
		assertEquals(0L, RemoldAgent.BATCH_STAGE_B_MILLIS.get());
	}

	@Test
	void libToolHasLiveInstanceDetectsPresenceAndRejectsNonObjectClasses() throws Exception {
		String clsName = "test.FastLiveProbeSubject";
		byte[] bytes = buildSimpleClass(clsName.replace('.', '/'), false, true);
		Class<?> subject = defineAndInit(clsName, bytes);

		assertFalse(LibTool.hasLiveInstance(subject),
			"freshly defined class with no allocated instances must return false");

		Object instance = subject.getDeclaredConstructor().newInstance();
		assertTrue(LibTool.hasLiveInstance(subject),
			"class with a reachable live instance must return true via fast early-abort probe");
		Reference.reachabilityFence(instance);

		assertThrows(IllegalArgumentException.class, () -> LibTool.hasLiveInstance(int.class));
		assertThrows(IllegalArgumentException.class, () -> LibTool.hasLiveInstance(Object[].class));
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
