package remold.ref;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import remold.ClassDiffUtil;
import remold.ClassDiffUtil.ClassDiff;
import remold.Reflect;
import remold.annotation.RemoldReinit;
import remold.util.LibTool;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InitFixBatchLoopTest {

	private static final class ByteLoader extends ClassLoader {
		Class<?> define(String name, byte[] bytes) {
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	private static byte[] buildBatchHostClass(
	 String internalName, String superInternal,
	 boolean withFields, boolean selfOnlyScope, boolean withThrowingSlice) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, superInternal, null);

		if (withFields) {
			cw.visitField(Opcodes.ACC_PUBLIC, "addedInt", "I", null, null).visitEnd();
			cw.visitField(Opcodes.ACC_PUBLIC, "addedStr", "Ljava/lang/String;", null, null).visitEnd();
			FieldVisitor forcedFv = cw.visitField(Opcodes.ACC_PUBLIC, "forcedInt", "I", null, null);
			AnnotationVisitor forcedAv = forcedFv.visitAnnotation("Lremold/annotation/RemoldReinit;", true);
			forcedAv.visitEnum("mode", "Lremold/annotation/RemoldReinit$Mode;", "OVERWRITE");
			if (selfOnlyScope) {
				forcedAv.visitEnum("scope", "Lremold/annotation/RemoldReinit$Scope;", "SELF");
			}
			forcedAv.visitEnd();
			forcedFv.visitEnd();

			if (withThrowingSlice) {
				cw.visitField(Opcodes.ACC_PUBLIC, "flag", "I", null, null).visitEnd();
				cw.visitField(Opcodes.ACC_PUBLIC, "derived", "I", null, null).visitEnd();
				MethodVisitor helper = cw.visitMethod(
				 Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "computeFromFlag", "(I)I", null, null);
				helper.visitCode();
				helper.visitVarInsn(Opcodes.ILOAD, 0);
				Label ok = new Label();
				helper.visitJumpInsn(Opcodes.IFGE, ok);
				helper.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
				helper.visitInsn(Opcodes.DUP);
				helper.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
				helper.visitInsn(Opcodes.ATHROW);
				helper.visitLabel(ok);
				helper.visitVarInsn(Opcodes.ILOAD, 0);
				helper.visitIntInsn(Opcodes.BIPUSH, 10);
				helper.visitInsn(Opcodes.IADD);
				helper.visitInsn(Opcodes.IRETURN);
				helper.visitMaxs(0, 0);
				helper.visitEnd();
			}
		}

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", "()V", false);
		if (withFields) {
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitIntInsn(Opcodes.BIPUSH, 42);
			mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "addedInt", "I");

			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitLdcInsn("ok");
			mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "addedStr", "Ljava/lang/String;");

			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitIntInsn(Opcodes.BIPUSH, 99);
			mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "forcedInt", "I");

			if (withThrowingSlice) {
				mv.visitVarInsn(Opcodes.ALOAD, 0);
				mv.visitIntInsn(Opcodes.BIPUSH, 1);
				mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "flag", "I");

				mv.visitVarInsn(Opcodes.ALOAD, 0);
				mv.visitVarInsn(Opcodes.ALOAD, 0);
				mv.visitFieldInsn(Opcodes.GETFIELD, internalName, "flag", "I");
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, internalName, "computeFromFlag", "(I)I", false);
				mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "derived", "I");
			}
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void batchMethodUpdatesInstancesWithExactStatsAndPreservesForceAndSelfScope() throws Throwable {
		HotswapBridge.drainConditionalStats();
		String baseName = "remold.ref.BatchHostSample";
		String subName = "remold.ref.BatchSubSample";
		byte[] v1Base = buildBatchHostClass(baseName.replace('.', '/'), "java/lang/Object", false, true, false);
		byte[] v2Base = buildBatchHostClass(baseName.replace('.', '/'), "java/lang/Object", true, true, false);
		byte[] subBytes = buildBatchHostClass(subName.replace('.', '/'), baseName.replace('.', '/'), false, false, false);

		ByteLoader loader = new ByteLoader();
		Class<?> host = loader.define(baseName, v2Base);
		Class<?> sub = loader.define(subName, subBytes);

		Object baseZero = host.getConstructor().newInstance();
		Object basePreset = host.getConstructor().newInstance();
		Object subInst = sub.getConstructor().newInstance();

		Field addedInt = host.getField("addedInt");
		Field addedStr = host.getField("addedStr");
		Field forcedInt = host.getField("forcedInt");

		// Reset to default/custom pre-patch state to verify conditional CAS vs OVERWRITE vs SELF scope.
		addedInt.setInt(baseZero, 0);
		addedStr.set(baseZero, null);
		forcedInt.setInt(baseZero, 5);

		addedInt.setInt(basePreset, 7);
		addedStr.set(basePreset, "keep");
		forcedInt.setInt(basePreset, 13);

		addedInt.setInt(subInst, 0);
		addedStr.set(subInst, null);
		forcedInt.setInt(subInst, 88);

		ClassDiff diff = ClassDiffUtil.diff(v1Base, v2Base);
		InitFix.transform(host, v2Base, diff);
		try {
			InitFix.PatchPlan plan = InitFix.pendingPlanForTest(host);
			assertNotNull(plan);
			assertEquals(3, plan.instanceTasks().size());
			for (InitFix.FieldPatchTask task : plan.instanceTasks()) {
				assertNotNull(task.batchMethodName(),
					"straight-line instance task must emit hidden-class batch loop: " + task.fieldName());
			}

			// Verify batch handle directly on the array before afterRedefine consumes PENDING,
			// so we can assert exact written/skipped counters from HotswapBridge.
			byte[] patchBytes = InitFix.pendingBytesForTest(host);
			Lookup patchLookup = Reflect.defineHiddenClass(host, patchBytes);
			Class<?> patchClass = patchLookup.lookupClass();
			MethodType batchType = MethodType.methodType(
			 int.class, Object[].class, int.class, int.class, Throwable[].class);

			Object[] targets = new Object[]{baseZero, basePreset, subInst};
			Throwable[] errOut = new Throwable[1];
			for (InitFix.FieldPatchTask task : plan.instanceTasks()) {
				MethodHandle batchMh = patchLookup.findStatic(patchClass, task.batchMethodName(), batchType);
				int rc = (int) batchMh.invokeExact(targets, 0, targets.length, errOut);
				assertEquals(-1, rc, "batch execution must complete cleanly for " + task.fieldName());
				assertNull(errOut[0]);
			}

			// baseZero: 0 -> 42, null -> "ok", 5 -> 99
			assertEquals(42, addedInt.getInt(baseZero));
			assertEquals("ok", addedStr.get(baseZero));
			assertEquals(99, forcedInt.getInt(baseZero));

			// basePreset: 7 preserved, "keep" preserved, 13 overwritten -> 99
			assertEquals(7, addedInt.getInt(basePreset));
			assertEquals("keep", addedStr.get(basePreset));
			assertEquals(99, forcedInt.getInt(basePreset));

			// subInst: addedInt/addedStr written (default scope), forcedInt untouched (SELF scope)
			assertEquals(42, addedInt.getInt(subInst));
			assertEquals("ok", addedStr.get(subInst));
			assertEquals(88, forcedInt.getInt(subInst));

			Map<String, long[]> stats = HotswapBridge.drainConditionalStats();
			long[] intCounts = stats.get(host.getName() + "#addedInt");
			assertNotNull(intCounts, "batch stats must record addedInt");
			assertArrayEquals(new long[]{2L, 1L}, intCounts);

			long[] strCounts = stats.get(host.getName() + "#addedStr");
			assertNotNull(strCounts, "batch stats must record addedStr");
			assertArrayEquals(new long[]{2L, 1L}, strCounts);

			assertNull(stats.get(host.getName() + "#forcedInt"),
				"OVERWRITE (KIND_FORCE) must not emit conditional skip/write counters");

			// End-to-end afterRedefine clears PENDING and runs without errors (reuses LibTool when available).
			if (LibTool.initialized()) {
				InitFix.afterRedefine(host);
				assertTrue(HotswapBridge.drainConditionalStats().isEmpty(),
					"afterRedefine must drain conditional stats cleanly");
			} else {
				InitFix.afterRedefineFailed(host);
			}
		} finally {
			InitFix.afterRedefineFailed(host);
			HotswapBridge.drainConditionalStats();
		}
	}

	@Test
	void batchConditionalExpectedWritesAndRecordsReplayStats() throws Throwable {
		HotswapBridge.drainReplayStats();
		DummyReplayHost match1 = new DummyReplayHost(7);
		DummyReplayHost modified = new DummyReplayHost(12);
		DummyReplayHost match2 = new DummyReplayHost(7);

		CallSite site = HotswapBridge.bootstrapExpected(
		 MethodHandles.lookup(), "value",
		 MethodType.methodType(boolean.class, DummyReplayHost.class, int.class),
		 HotswapBridge.KIND_CONDITIONAL_EXPECTED_BATCH, Opcodes.PUTFIELD,
		 DummyReplayHost.class, DummyReplayHost.class, 7);

		MethodHandle invoker = site.dynamicInvoker();
		long written = 0;
		long skipped = 0;
		for (DummyReplayHost inst : new DummyReplayHost[]{match1, modified, match2}) {
			if ((boolean) invoker.invokeExact(inst, 55)) written++;
			else skipped++;
		}
		HotswapBridge.addReplayStats(DummyReplayHost.class, "value", written, skipped);

		assertEquals(55, match1.value);
		assertEquals(12, modified.value);
		assertEquals(55, match2.value);

		Map<String, long[]> replayStats = HotswapBridge.drainReplayStats();
		assertArrayEquals(new long[]{2L, 1L}, replayStats.get(DummyReplayHost.class.getName() + "#value"));
	}

	@Test
	void batchFailureIsolatesBadInstancesRespectsBudgetAndPropagatesFatalLinkageError() throws Throwable {
		HotswapBridge.drainConditionalStats();
		AtomicInteger okCount = new AtomicInteger();

		// Batch handle simulating 12 targets where indices 1..10 throw RuntimeException:
		// budget=8 must give up after the 8th failure (index 8) without executing indices 9+.
		MethodHandle throwingBatch = MethodHandles.lookup().findStatic(
		 InitFixBatchLoopTest.class, "simulatedThrowingBatch",
		 MethodType.methodType(int.class, Object[].class, int.class, int.class, Throwable[].class));
		MethodHandle countBatch = MethodHandles.lookup().findStatic(
		 InitFixBatchLoopTest.class, "simulatedCountingBatch",
		 MethodType.methodType(int.class, Object[].class, int.class, int.class, Throwable[].class));
		MethodHandle noopSingle = MethodHandles.lookup().findStatic(
		 InitFixBatchLoopTest.class, "noopSingle", MethodType.methodType(void.class, Object.class));

		InitFix.BoundInstanceTask failingTask = new InitFix.BoundInstanceTask(
		 "f1", Set.of(), noopSingle, throwingBatch, false);
		InitFix.BoundInstanceTask secondTask = new InitFix.BoundInstanceTask(
		 "f2", Set.of(), noopSingle, countBatch, false);

		Object[] targets = new Object[12];
		for (int i = 0; i < targets.length; i++) targets[i] = okCount;

		Set<String> failedFields = new LinkedHashSet<>();
		Set<String> skippedFields = new LinkedHashSet<>();
		Map<String, String> failureReasons = new LinkedHashMap<>();

		InitFix.runIndependentInstanceTasks(
		 InitFixBatchLoopTest.class, targets,
		 new InitFix.BoundInstanceTask[]{failingTask, secondTask},
		 failedFields, skippedFields, failureReasons);

		assertTrue(failedFields.contains("f1"));
		assertTrue(skippedFields.contains("f1"), "8th failure must trigger InstanceFailureBudget give-up");
		assertFalse(failedFields.contains("f2"), "f1 failure must not block independent field f2");
		assertEquals(12, okCount.get(), "independent second field must still visit all 12 targets");
		assertTrue(failureReasons.get("f1").contains("8 times, giving up for this round"));

		// Fatal LinkageError inside batch loop must propagate immediately (invariant #4).
		MethodHandle fatalBatch = MethodHandles.lookup().findStatic(
		 InitFixBatchLoopTest.class, "simulatedFatalLinkageBatch",
		 MethodType.methodType(int.class, Object[].class, int.class, int.class, Throwable[].class));
		InitFix.BoundInstanceTask fatalTask = new InitFix.BoundInstanceTask(
		 "fatal", Set.of(), noopSingle, fatalBatch, false);

		assertThrows(LinkageError.class, () ->
		 InitFix.runIndependentInstanceTasks(
		  InitFixBatchLoopTest.class, new Object[]{"x"},
		  new InitFix.BoundInstanceTask[]{fatalTask},
		  new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashMap<>()));
	}

	static final class DummyReplayHost {
		int value;
		DummyReplayHost(int value) { this.value = value; }
	}

	static void noopSingle(Object ignored) { }

	static int simulatedThrowingBatch(Object[] targets, int from, int to, Throwable[] errOut) {
		for (int i = from; i < to; i++) {
			if (i >= 1 && i <= 10) {
				errOut[0] = new IllegalStateException("bad instance " + i);
				return i;
			}
		}
		return -1;
	}

	static int simulatedCountingBatch(Object[] targets, int from, int to, Throwable[] errOut) {
		for (int i = from; i < to; i++) {
			((AtomicInteger) targets[i]).incrementAndGet();
		}
		return -1;
	}

	static int simulatedFatalLinkageBatch(Object[] targets, int from, int to, Throwable[] errOut) {
		errOut[0] = new NoSuchMethodError("fatal linkage");
		return from;
	}
}
