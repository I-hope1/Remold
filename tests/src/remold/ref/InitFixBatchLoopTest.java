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

	@Test
	void fusedBatchGroupUpdatesMultiFieldHostWithExactStatsAndActiveMask() throws Throwable {
		HotswapBridge.drainConditionalStats();
		String baseName = "remold.ref.FusedHostSample";
		String subName = "remold.ref.FusedSubSample";
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
			assertEquals(1, plan.fusedBatchGroups().size(), "independent batch fields must fuse into 1 group");
			InitFix.FusedBatchGroup group = plan.fusedBatchGroups().get(0);
			assertEquals("initBatchGroup$0", group.methodName());
			assertArrayEquals(new int[]{0, 1, 2}, group.taskIndices());

			byte[] patchBytes = InitFix.pendingBytesForTest(host);
			Lookup patchLookup = Reflect.defineHiddenClass(host, patchBytes);
			Class<?> patchClass = patchLookup.lookupClass();
			MethodType fusedType = MethodType.methodType(
			 int.class, Object[].class, int.class, int.class,
			 boolean[].class, Throwable[].class, int[].class);
			MethodHandle fusedMh = patchLookup.findStatic(patchClass, group.methodName(), fusedType);

			Object[] targets = new Object[]{baseZero, basePreset, subInst};
			boolean[] active = new boolean[]{true, true, true};
			Throwable[] errOut = new Throwable[1];
			int[] errSlotOut = new int[1];
			int rc = (int) fusedMh.invokeExact(targets, 0, targets.length, active, errOut, errSlotOut);
			assertEquals(-1, rc);
			assertNull(errOut[0]);

			assertEquals(42, addedInt.getInt(baseZero));
			assertEquals("ok", addedStr.get(baseZero));
			assertEquals(99, forcedInt.getInt(baseZero));

			assertEquals(7, addedInt.getInt(basePreset));
			assertEquals("keep", addedStr.get(basePreset));
			assertEquals(99, forcedInt.getInt(basePreset));

			assertEquals(42, addedInt.getInt(subInst));
			assertEquals("ok", addedStr.get(subInst));
			assertEquals(88, forcedInt.getInt(subInst));

			Map<String, long[]> stats = HotswapBridge.drainConditionalStats();
			assertArrayEquals(new long[]{2L, 1L}, stats.get(host.getName() + "#addedInt"));
			assertArrayEquals(new long[]{2L, 1L}, stats.get(host.getName() + "#addedStr"));
			assertNull(stats.get(host.getName() + "#forcedInt"));

			// Verify active mask disables individual slots cleanly without touching disabled fields.
			Object maskedHost = host.getConstructor().newInstance();
			addedInt.setInt(maskedHost, 0);
			addedStr.set(maskedHost, null);
			forcedInt.setInt(maskedHost, 1);
			boolean[] maskSecondOff = new boolean[]{true, false, true};
			int maskedRc = (int) fusedMh.invokeExact(
			 new Object[]{maskedHost}, 0, 1, maskSecondOff, errOut, errSlotOut);
			assertEquals(-1, maskedRc);
			assertEquals(42, addedInt.getInt(maskedHost));
			assertNull(addedStr.get(maskedHost), "masked-out slot must stay untouched");
			assertEquals(99, forcedInt.getInt(maskedHost));
			HotswapBridge.drainConditionalStats();
		} finally {
			InitFix.afterRedefineFailed(host);
			HotswapBridge.drainConditionalStats();
		}
	}

	@Test
	void fusedBatchDriverIsolatesMidLoopFailuresRespectsBudgetAndPropagatesFatalLinkageError() throws Throwable {
		MethodHandle singleF0 = MethodHandles.lookup().findStatic(
		 InitFixBatchLoopTest.class, "markSlot0Single", MethodType.methodType(void.class, Object.class));
		MethodHandle singleF1 = MethodHandles.lookup().findStatic(
		 InitFixBatchLoopTest.class, "markSlot1Single", MethodType.methodType(void.class, Object.class));
		MethodHandle singleF2 = MethodHandles.lookup().findStatic(
		 InitFixBatchLoopTest.class, "markSlot2Single", MethodType.methodType(void.class, Object.class));
		MethodHandle noopBatch = MethodHandles.lookup().findStatic(
		 InitFixBatchLoopTest.class, "noopBatch",
		 MethodType.methodType(int.class, Object[].class, int.class, int.class, Throwable[].class));
		MethodHandle fusedGroupMh = MethodHandles.lookup().findStatic(
		 InitFixBatchLoopTest.class, "simulatedFusedThreeSlotGroup",
		 MethodType.methodType(int.class, Object[].class, int.class, int.class,
		  boolean[].class, Throwable[].class, int[].class));

		InitFix.BoundInstanceTask[] tasks = new InitFix.BoundInstanceTask[]{
		 new InitFix.BoundInstanceTask("f0", Set.of(), singleF0, noopBatch, false),
		 new InitFix.BoundInstanceTask("f1", Set.of(), singleF1, noopBatch, false),
		 new InitFix.BoundInstanceTask("f2", Set.of(), singleF2, noopBatch, false)
		};
		InitFix.BoundFusedBatchGroup[] groups = new InitFix.BoundFusedBatchGroup[]{
		 new InitFix.BoundFusedBatchGroup(fusedGroupMh, new int[]{0, 1, 2})
		};

		int[][] counts = new int[12][3];
		Object[] targets = counts;
		Set<String> failedFields = new LinkedHashSet<>();
		Set<String> skippedFields = new LinkedHashSet<>();
		Map<String, String> failureReasons = new LinkedHashMap<>();

		InitFix.runIndependentInstanceTasks(
		 InitFixBatchLoopTest.class, targets, tasks, groups,
		 failedFields, skippedFields, failureReasons);

		assertTrue(failedFields.contains("f1"));
		assertTrue(skippedFields.contains("f1"), "f1 must hit InstanceFailureBudget=8 and disable its fused slot");
		assertFalse(failedFields.contains("f0"));
		assertFalse(failedFields.contains("f2"));
		for (int i = 0; i < targets.length; i++) {
			assertEquals(1, counts[i][0], "f0 (< failed slot) must execute once at index " + i);
			assertEquals(1, counts[i][2], "f2 (> failed slot) must execute once even at failed index " + i);
			int expectedF1 = (i == 0) ? 1 : 0;
			assertEquals(expectedF1, counts[i][1], "f1 must succeed only at index 0, index " + i);
		}

		MethodHandle fatalFusedMh = MethodHandles.lookup().findStatic(
		 InitFixBatchLoopTest.class, "simulatedFatalFusedGroup",
		 MethodType.methodType(int.class, Object[].class, int.class, int.class,
		  boolean[].class, Throwable[].class, int[].class));
		InitFix.BoundInstanceTask fatalTask = new InitFix.BoundInstanceTask(
		 "fatal", Set.of(), singleF0, noopBatch, false);
		assertThrows(LinkageError.class, () ->
		 InitFix.runIndependentInstanceTasks(
		  InitFixBatchLoopTest.class, new Object[]{new int[3]},
		  new InitFix.BoundInstanceTask[]{fatalTask},
		  new InitFix.BoundFusedBatchGroup[]{new InitFix.BoundFusedBatchGroup(fatalFusedMh, new int[]{0})},
		  new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashMap<>()));
	}

	static void markSlot0Single(Object target) {
		((int[]) target)[0]++;
	}

	static void markSlot1Single(Object target) {
		((int[]) target)[1]++;
	}

	static void markSlot2Single(Object target) {
		((int[]) target)[2]++;
	}

	static int noopBatch(Object[] targets, int from, int to, Throwable[] errOut) {
		return -1;
	}

	static int simulatedFusedThreeSlotGroup(
	 Object[] targets, int from, int to, boolean[] active, Throwable[] errOut, int[] errSlotOut) {
		for (int i = from; i < to; i++) {
			int[] row = (int[]) targets[i];
			if (active[0]) row[0]++;
			if (active[1]) {
				if (i >= 1 && i <= 10) {
					errOut[0] = new IllegalStateException("f1 bad at " + i);
					errSlotOut[0] = 1;
					return i;
				}
				row[1]++;
			}
			if (active[2]) row[2]++;
		}
		return -1;
	}

	static int simulatedFatalFusedGroup(
	 Object[] targets, int from, int to, boolean[] active, Throwable[] errOut, int[] errSlotOut) {
		errOut[0] = new NoSuchMethodError("fatal fused linkage");
		errSlotOut[0] = 0;
		return from;
	}

	@Test
	void persistentConstantBatchLoopCoversTypesRawBitsSelfScopeAndIsolatesBadReceiver() throws Throwable {
		HotswapBridge.drainConditionalStats();
		String baseName = "remold.ref.ConstLoopHost";
		String subName = "remold.ref.ConstLoopSub";
		byte[] v1Base = buildConstHostClass(baseName.replace('.', '/'), "java/lang/Object", false);
		byte[] v2Base = buildConstHostClass(baseName.replace('.', '/'), "java/lang/Object", true);
		byte[] subBytes = buildConstHostClass(subName.replace('.', '/'), baseName.replace('.', '/'), false);

		ByteLoader loader = new ByteLoader();
		Class<?> host = loader.define(baseName, v2Base);
		Class<?> sub = loader.define(subName, subBytes);

		Object zeroHost = host.getConstructor().newInstance();
		Object presetHost = host.getConstructor().newInstance();
		Object subInst = sub.getConstructor().newInstance();

		// Reset zeroHost to type defaults (and negative zero for raw-bits check on presetHost).
		host.getField("zVal").setBoolean(zeroHost, false);
		host.getField("bVal").setByte(zeroHost, (byte) 0);
		host.getField("cVal").setChar(zeroHost, '\u0000');
		host.getField("sVal").setShort(zeroHost, (short) 0);
		host.getField("iVal").setInt(zeroHost, 0);
		host.getField("jVal").setLong(zeroHost, 0L);
		host.getField("fVal").setFloat(zeroHost, 0.0f);
		host.getField("dVal").setDouble(zeroHost, 0.0d);
		host.getField("strVal").set(zeroHost, null);
		host.getField("forcedSelf").set(zeroHost, "old");

		// Preset host keeps custom values + -0.0f / -0.0d (non-zero raw bits must be skipped).
		host.getField("zVal").setBoolean(presetHost, false);
		host.getField("bVal").setByte(presetHost, (byte) 9);
		host.getField("cVal").setChar(presetHost, 'X');
		host.getField("sVal").setShort(presetHost, (short) 11);
		host.getField("iVal").setInt(presetHost, 5);
		host.getField("jVal").setLong(presetHost, 99L);
		host.getField("fVal").setFloat(presetHost, -0.0f);
		host.getField("dVal").setDouble(presetHost, -0.0d);
		host.getField("strVal").set(presetHost, "custom");
		host.getField("forcedSelf").set(presetHost, "old");

		host.getField("iVal").setInt(subInst, 0);
		host.getField("fVal").setFloat(subInst, 0.0f);
		host.getField("dVal").setDouble(subInst, 0.0d);
		host.getField("strVal").set(subInst, null);
		host.getField("forcedSelf").set(subInst, "sub-old");

		ClassDiff diff = ClassDiffUtil.diff(v1Base, v2Base);
		InitFix.transform(host, v2Base, diff);
		try {
			InitFix.PatchPlan plan = InitFix.pendingPlanForTest(host);
			assertNotNull(plan);
			assertEquals(10, plan.instanceTasks().size());
			for (InitFix.FieldPatchTask task : plan.instanceTasks()) {
				assertNotNull(task.constantInit(),
					"constant field slice must attach ConstantInitSpec: " + task.fieldName());
			}

			// Verify persistent loop directly including null slots and ClassCastException isolation.
			long iOff = HotswapBridge.resolveInstanceFieldOffset(host, "iVal", "I");
			long fOff = HotswapBridge.resolveInstanceFieldOffset(host, "fVal", "F");
			long dOff = HotswapBridge.resolveInstanceFieldOffset(host, "dVal", "D");
			long strOff = HotswapBridge.resolveInstanceFieldOffset(host, "strVal", "Ljava/lang/String;");
			long forceOff = HotswapBridge.resolveInstanceFieldOffset(host, "forcedSelf", "Ljava/lang/String;");

			MethodHandle iLoop = HotswapBridge.constantBatchLoop(int.class, false);
			MethodHandle fLoop = HotswapBridge.constantBatchLoop(float.class, false);
			MethodHandle dLoop = HotswapBridge.constantBatchLoop(double.class, false);
			MethodHandle strLoop = HotswapBridge.constantBatchLoop(Object.class, false);
			MethodHandle forceLoop = HotswapBridge.constantBatchLoop(Object.class, true);

			Object[] targets = new Object[]{zeroHost, null, presetHost, subInst};
			Throwable[] errOut = new Throwable[1];
			assertEquals(-1, (int) iLoop.invokeExact(host, "iVal", iOff, 42, false, targets, 0, targets.length, errOut));
			assertEquals(-1, (int) fLoop.invokeExact(host, "fVal", fOff, 1.5f, false, targets, 0, targets.length, errOut));
			assertEquals(-1, (int) dLoop.invokeExact(host, "dVal", dOff, 2.5d, false, targets, 0, targets.length, errOut));
			assertEquals(-1, (int) strLoop.invokeExact(host, "strVal", strOff, (Object) "hello", false, targets, 0, targets.length, errOut));
			assertEquals(-1, (int) forceLoop.invokeExact(host, "forcedSelf", forceOff, (Object) "forced", true, targets, 0, targets.length, errOut));

			assertEquals(42, host.getField("iVal").getInt(zeroHost));
			assertEquals(1.5f, host.getField("fVal").getFloat(zeroHost));
			assertEquals(2.5d, host.getField("dVal").getDouble(zeroHost));
			assertEquals("hello", host.getField("strVal").get(zeroHost));
			assertEquals("forced", host.getField("forcedSelf").get(zeroHost));

			assertEquals(5, host.getField("iVal").getInt(presetHost));
			assertEquals(Float.floatToRawIntBits(-0.0f),
				Float.floatToRawIntBits(host.getField("fVal").getFloat(presetHost)),
				"raw-bits CAS must preserve -0.0f");
			assertEquals(Double.doubleToRawLongBits(-0.0d),
				Double.doubleToRawLongBits(host.getField("dVal").getDouble(presetHost)),
				"raw-bits CAS must preserve -0.0d");
			assertEquals("custom", host.getField("strVal").get(presetHost));
			assertEquals("forced", host.getField("forcedSelf").get(presetHost));

			assertEquals(42, host.getField("iVal").getInt(subInst));
			assertEquals(1.5f, host.getField("fVal").getFloat(subInst));
			assertEquals(2.5d, host.getField("dVal").getDouble(subInst));
			assertEquals("hello", host.getField("strVal").get(subInst));
			assertEquals("sub-old", host.getField("forcedSelf").get(subInst), "SELF scope must skip subclass");

			Map<String, long[]> stats = HotswapBridge.drainConditionalStats();
			assertArrayEquals(new long[]{2L, 1L}, stats.get(host.getName() + "#iVal"));
			assertArrayEquals(new long[]{2L, 1L}, stats.get(host.getName() + "#fVal"));
			assertArrayEquals(new long[]{2L, 1L}, stats.get(host.getName() + "#dVal"));
			assertArrayEquals(new long[]{2L, 1L}, stats.get(host.getName() + "#strVal"));
			assertNull(stats.get(host.getName() + "#forcedSelf"));

			// Wrong element type inside Object[] throws ClassCastException at exact failing index.
			Object[] badTargets = new Object[]{zeroHost, "not-a-host", subInst};
			int failedIdx = (int) strLoop.invokeExact(host, "strVal", strOff, (Object) "next", false, badTargets, 0, badTargets.length, errOut);
			assertEquals(1, failedIdx);
			assertInstanceOf(ClassCastException.class, errOut[0]);
			Map<String, long[]> errStats = HotswapBridge.drainConditionalStats();
			assertArrayEquals(new long[]{0L, 1L}, errStats.get(host.getName() + "#strVal"),
				"stats accumulated before failing element must flush cleanly");

			if (LibTool.initialized()) {
				// Probe-injected link failure on one field falls back and remains isolated.
				HotswapBridge.setBootstrapFailureProbe(host.getName() + "#bVal");
				InitFix.afterRedefine(host);
				HotswapBridge.setBootstrapFailureProbe(null);
			} else {
				InitFix.afterRedefineFailed(host);
			}
		} finally {
			HotswapBridge.setBootstrapFailureProbe(null);
			InitFix.afterRedefineFailed(host);
			HotswapBridge.drainConditionalStats();
		}
	}

	@Test
	void progressiveChunkingVisitsLargeArrayOnceAndPreservesBudgetAcrossChunkBoundaries() throws Throwable {
		MethodHandle countChunkBatch = MethodHandles.lookup().findStatic(
		 InitFixBatchLoopTest.class, "simulatedLargeCountingBatch",
		 MethodType.methodType(int.class, Object[].class, int.class, int.class, Throwable[].class));
		MethodHandle fusedChunkGroup = MethodHandles.lookup().findStatic(
		 InitFixBatchLoopTest.class, "simulatedLargeFusedWithFailures",
		 MethodType.methodType(int.class, Object[].class, int.class, int.class,
		  boolean[].class, Throwable[].class, int[].class));
		MethodHandle singleF0 = MethodHandles.lookup().findStatic(
		 InitFixBatchLoopTest.class, "markSlot0Single", MethodType.methodType(void.class, Object.class));
		MethodHandle singleF1 = MethodHandles.lookup().findStatic(
		 InitFixBatchLoopTest.class, "markSlot1Single", MethodType.methodType(void.class, Object.class));

		final int N = 10_000; // Crosses 2048*4 = 8192 small-chunk phase into 8192 big-chunk tail.
		int[][] counts = new int[N][2];
		Object[] targets = counts;

		InitFix.BoundInstanceTask singleTask = new InitFix.BoundInstanceTask(
		 "single", Set.of(), singleF0, countChunkBatch, false);
		InitFix.runIndependentInstanceTasks(
		 InitFixBatchLoopTest.class, targets, new InitFix.BoundInstanceTask[]{singleTask},
		 new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashMap<>());
		for (int i = 0; i < N; i++) {
			assertEquals(1, counts[i][0], "single chunked task must visit index " + i + " once");
			counts[i][0] = 0;
		}

		// Fused group across 10,000 targets where slot 1 fails at 8 small/large boundary indices.
		InitFix.BoundInstanceTask[] fusedTasks = new InitFix.BoundInstanceTask[]{
		 new InitFix.BoundInstanceTask("f0", Set.of(), singleF0, countChunkBatch, false),
		 new InitFix.BoundInstanceTask("f1", Set.of(), singleF1, countChunkBatch, false)
		};
		InitFix.BoundFusedBatchGroup[] groups = new InitFix.BoundFusedBatchGroup[]{
		 new InitFix.BoundFusedBatchGroup(fusedChunkGroup, new int[]{0, 1})
		};
		Set<String> failed = new LinkedHashSet<>();
		Set<String> skipped = new LinkedHashSet<>();
		InitFix.runIndependentInstanceTasks(
		 InitFixBatchLoopTest.class, targets, fusedTasks, groups,
		 failed, skipped, new LinkedHashMap<>());

		assertTrue(failed.contains("f1"));
		assertTrue(skipped.contains("f1"), "8th failure across chunk boundaries must trigger budget give-up");
		for (int i = 0; i < N; i++) {
			assertEquals(1, counts[i][0], "f0 must visit index " + i + " once across all chunks");
		}
		assertEquals(0, counts[8192][1], "8th failing index 8192 must stay failed");
		assertEquals(0, counts[8193][1], "element 8193 after budget give-up must be skipped");
	}

	private static byte[] buildConstHostClass(String internalName, String superInternal, boolean withFields) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, superInternal, null);
		if (withFields) {
			cw.visitField(Opcodes.ACC_PUBLIC, "zVal", "Z", null, null).visitEnd();
			cw.visitField(Opcodes.ACC_PUBLIC, "bVal", "B", null, null).visitEnd();
			cw.visitField(Opcodes.ACC_PUBLIC, "cVal", "C", null, null).visitEnd();
			cw.visitField(Opcodes.ACC_PUBLIC, "sVal", "S", null, null).visitEnd();
			cw.visitField(Opcodes.ACC_PUBLIC, "iVal", "I", null, null).visitEnd();
			cw.visitField(Opcodes.ACC_PUBLIC, "jVal", "J", null, null).visitEnd();
			cw.visitField(Opcodes.ACC_PUBLIC, "fVal", "F", null, null).visitEnd();
			cw.visitField(Opcodes.ACC_PUBLIC, "dVal", "D", null, null).visitEnd();
			cw.visitField(Opcodes.ACC_PUBLIC, "strVal", "Ljava/lang/String;", null, null).visitEnd();
			FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC, "forcedSelf", "Ljava/lang/String;", null, null);
			AnnotationVisitor av = fv.visitAnnotation("Lremold/annotation/RemoldReinit;", true);
			av.visitEnum("mode", "Lremold/annotation/RemoldReinit$Mode;", "OVERWRITE");
			av.visitEnum("scope", "Lremold/annotation/RemoldReinit$Scope;", "SELF");
			av.visitEnd();
			fv.visitEnd();
		}
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", "()V", false);
		if (withFields) {
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitInsn(Opcodes.ICONST_1);
			mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "zVal", "Z");
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitIntInsn(Opcodes.BIPUSH, 7);
			mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "bVal", "B");
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitIntInsn(Opcodes.BIPUSH, 'A');
			mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "cVal", "C");
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitIntInsn(Opcodes.SIPUSH, 321);
			mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "sVal", "S");
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitIntInsn(Opcodes.BIPUSH, 42);
			mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "iVal", "I");
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitLdcInsn(1234567890123L);
			mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "jVal", "J");
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitLdcInsn(1.5f);
			mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "fVal", "F");
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitLdcInsn(2.5d);
			mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "dVal", "D");
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitLdcInsn("hello");
			mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "strVal", "Ljava/lang/String;");
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitLdcInsn("forced");
			mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "forcedSelf", "Ljava/lang/String;");
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	static int simulatedLargeCountingBatch(Object[] targets, int from, int to, Throwable[] errOut) {
		for (int i = from; i < to; i++) {
			((int[]) targets[i])[0]++;
		}
		return -1;
	}

	static int simulatedLargeFusedWithFailures(
	 Object[] targets, int from, int to, boolean[] active, Throwable[] errOut, int[] errSlotOut) {
		for (int i = from; i < to; i++) {
			int[] row = (int[]) targets[i];
			if (active[0]) row[0]++;
			if (active[1]) {
				// 7 failures in small chunks (5..11) + 1 failure across big-chunk boundary (8192).
				if ((i >= 5 && i <= 11) || i == 8192 || i == 8193) {
					errOut[0] = new IllegalStateException("fail at " + i);
					errSlotOut[0] = 1;
					return i;
				}
				row[1]++;
			}
		}
		return -1;
	}
}
