package remold.ref;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import remold.BatchReportScope;
import remold.ClassDiffUtil;
import remold.HotswapBatchReport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Battle B wiring tests for the value-type {@code new} replay channel
 * ({@code docs/initfix/14-value-type-new-replay.md}): the additive
 * {@link HotswapBatchReport.InitFixStats} surface, the {@link InitFix.PatchReport} value-replay
 * component and compatibility constructors, the {@link InitFix.PatchPlan#valueReplayTasks()}
 * ledger exclusion, the applied-record generation/invalidation logic and the patch-class expression
 * factory emitter. No runtime replay is executed here.
 */
class ValueTypeReplayWiringTest {

	private static final String FIELD_DESC = "Lkx/Vec;";

	private static ValueTypeReplay.Field field(String name, String oldFp, String newFp,
	                                          String ctorFp, String equalsFp) {
		return new ValueTypeReplay.Field(name, false, FIELD_DESC, oldFp, newFp, ctorFp, equalsFp);
	}

	// ==================== api: InitFixStats additive surface ====================

	@Test
	void initFixStatsLegacyConstructorSemanticsUnchanged() {
		HotswapBatchReport.InitFixStats stats = new HotswapBatchReport.InitFixStats(
			List.of("com.example.A"), 7, 3, 1, List.of("f1", "f2"));

		assertEquals(List.of("com.example.A"), stats.getPatchedClasses());
		assertEquals(7, stats.getLiveInstances());
		assertEquals(3, stats.getPatchedFields());
		assertEquals(1, stats.getIncompleteFields());
		assertEquals(List.of("f1", "f2"), stats.getFieldNames());
		// The legacy constructor must leave every value-replay counter and list empty.
		assertEquals(0, stats.getValueReplayApplied());
		assertEquals(0, stats.getValueReplaySkipped());
		assertEquals(0, stats.getValueReplayFailed());
		assertEquals(0, stats.getValueReplayRejected());
		assertTrue(stats.getValueReplayFailedFields().isEmpty());
		assertTrue(stats.getValueReplayRejectedFields().isEmpty());
		assertTrue(stats.hasActivity());
		assertTrue(HotswapBatchReport.InitFixStats.EMPTY.getValueReplayFailedFields().isEmpty());
		assertEquals(0, HotswapBatchReport.InitFixStats.EMPTY.getValueReplayRejected());
		assertTrue(HotswapBatchReport.InitFixStats.EMPTY.toString().contains("valueReplay"));
	}

	@Test
	void initFixStatsValueReplayCountersAndLists() {
		List<String> failed = new ArrayList<>(List.of("com.example.A.f: boom"));
		List<String> rejected = new ArrayList<>(List.of(
			"com.example.A.v: VALUE-REPLAY-EQ-CUSTOM: custom equals"));
		HotswapBatchReport.InitFixStats stats = new HotswapBatchReport.InitFixStats(
			List.of(), 0, 0, 0, List.of(), 2, 1, 3, 4, failed, rejected);

		assertEquals(2, stats.getValueReplayApplied());
		assertEquals(1, stats.getValueReplaySkipped());
		assertEquals(3, stats.getValueReplayFailed());
		assertEquals(4, stats.getValueReplayRejected());
		assertEquals(List.of("com.example.A.f: boom"), stats.getValueReplayFailedFields());
		assertEquals(1, stats.getValueReplayRejectedFields().size());
		// Immutable defensive copies: mutating the input lists must not leak into the snapshot.
		failed.add("late");
		assertEquals(1, stats.getValueReplayFailedFields().size());
		assertThrows(UnsupportedOperationException.class,
			() -> stats.getValueReplayRejectedFields().add("x"));
		// A value-replay-only activity still surfaces in hasActivity() and toString().
		assertTrue(stats.hasActivity());
		assertTrue(stats.toString().contains("applied=2"), stats.toString());
	}

	@Test
	void initFixStatsClampsNegativeCounters() {
		HotswapBatchReport.InitFixStats stats = new HotswapBatchReport.InitFixStats(
			null, -1, -1, -1, null, -4, -3, -2, -1, null, null);

		assertTrue(stats.getPatchedClasses().isEmpty());
		assertTrue(stats.getFieldNames().isEmpty());
		assertEquals(0, stats.getLiveInstances());
		assertEquals(0, stats.getPatchedFields());
		assertEquals(0, stats.getValueReplayApplied());
		assertEquals(0, stats.getValueReplaySkipped());
		assertEquals(0, stats.getValueReplayFailed());
		assertEquals(0, stats.getValueReplayRejected());
		assertFalse(stats.hasActivity());
	}

	/**
	 * Analysis may register rejected value-replay counts before the runtime outcome; the additive
	 * {@link BatchReportScope#recordInitFix} overload must accumulate both rounds into one snapshot.
	 */
	@Test
	void batchScopeAccumulatesValueReplayStats() {
		BatchReportScope.begin();
		HotswapBatchReport report;
		try {
			// Analysis round: rejected only.
			BatchReportScope.recordInitFix("com.example.A", 0, List.of(), 0,
				0, 0, 0, 1, List.of(),
				List.of("com.example.A.v: VALUE-REPLAY-EQ-CUSTOM: custom equals"));
			// Runtime round: applied/skipped/failed plus the rejected count is passed once more by the driver.
			BatchReportScope.recordInitFix("com.example.A", 3, List.of("f"), 1,
				1, 2, 1, 0, List.of("com.example.A.g: VALUE-REPLAY-CTOR-IMPURE: puts"),
				List.of());
			report = finishBatch();
		} finally {
			if (BatchReportScope.isActive()) BatchReportScope.discard();
		}

		assertTrue(report != null, "batch report must be produced");
		HotswapBatchReport.InitFixStats stats = report.getInitFixStats();
		assertEquals(1, stats.getValueReplayApplied());
		assertEquals(2, stats.getValueReplaySkipped());
		assertEquals(1, stats.getValueReplayFailed());
		assertEquals(1, stats.getValueReplayRejected());
		assertEquals(List.of("com.example.A.g: VALUE-REPLAY-CTOR-IMPURE: puts"),
			stats.getValueReplayFailedFields());
		assertEquals(List.of("com.example.A.v: VALUE-REPLAY-EQ-CUSTOM: custom equals"),
			stats.getValueReplayRejectedFields());
		assertEquals(3, stats.getLiveInstances());
		assertEquals(1, stats.getIncompleteFields());
	}

	@Test
	void batchScopeLegacyOverloadLeavesValueReplayEmpty() {
		BatchReportScope.begin();
		HotswapBatchReport report;
		try {
			BatchReportScope.recordInitFix("com.example.B", 2, List.of("x"), 0);
			report = finishBatch();
		} finally {
			if (BatchReportScope.isActive()) BatchReportScope.discard();
		}
		HotswapBatchReport.InitFixStats stats = report.getInitFixStats();
		assertEquals(0, stats.getValueReplayApplied());
		assertEquals(0, stats.getValueReplayRejected());
		assertTrue(stats.getValueReplayRejectedFields().isEmpty());
	}

	private static HotswapBatchReport finishBatch() {
		try {
			Method finish = BatchReportScope.class.getDeclaredMethod("finish", List.class);
			finish.setAccessible(true);
			return (HotswapBatchReport) finish.invoke(null, List.of());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("BatchReportScope.finish(List) shape changed", e);
		}
	}

	// ==================== PatchReport compatibility ====================

	@Test
	void patchReportLegacyConstructorsKeepValueReplayEmpty() {
		InitFix.PatchReport three = new InitFix.PatchReport(Map.of(), Map.of(), false);
		assertTrue(three.valueReplayFields().isEmpty());
		assertTrue(three.instanceFields().isEmpty());
		assertFalse(three.patchGenerated());

		InitFix.PatchReport six = new InitFix.PatchReport(
			Map.of(), Map.of(), true, Map.of("v", "replayed"), Map.of(), Map.of());
		assertTrue(six.valueReplayFields().isEmpty());
		assertTrue(six.patchGenerated());
		assertEquals("replayed", six.replayFields().get("v"));
	}

	@Test
	void patchReportCarriesValueReplayDecisions() {
		Map<String, InitFix.FieldDecision> decisions = new LinkedHashMap<>();
		decisions.put("v", InitFix.FieldDecision.ACCEPTED);
		decisions.put("s", InitFix.FieldDecision.rejected(
			"VALUE-REPLAY-STATIC-FINAL-NEEDS-ENHANCED: needs enhanced hotswap"));
		InitFix.PatchReport report = new InitFix.PatchReport(
			Map.of(), Map.of(), false, Map.of(), Map.of(), Map.of(), decisions);

		assertEquals(2, report.valueReplayFields().size());
		assertEquals(InitFix.FieldStatus.ACCEPTED, report.valueReplayFields().get("v").status());
		assertEquals(InitFix.FieldStatus.REJECTED, report.valueReplayFields().get("s").status());
		// Defensive copy + unmodifiable view.
		decisions.put("late", InitFix.FieldDecision.ACCEPTED);
		assertEquals(2, report.valueReplayFields().size());
		assertThrows(UnsupportedOperationException.class,
			() -> report.valueReplayFields().put("x", InitFix.FieldDecision.ACCEPTED));
	}

	// ==================== PatchPlan ledger exclusion ====================

	@Test
	void patchPlanValueReplayTasksAreExcludedFromAllTasks() {
		InitFix.ValueReplayTask task = new InitFix.ValueReplayTask("v", false, FIELD_DESC,
			"old-fp", "new-fp", "ctor-fp", "equals-fp", "buildOld$v", "buildNew$v");
		InitFix.PatchPlan plan = new InitFix.PatchPlan(
			List.of(), List.of(), Map.of(), Map.of(), List.of(), List.of(task));

		assertFalse(plan.isEmpty(), "a value-replay-only plan must not be treated as empty");
		assertTrue(plan.valueReplayTasks().size() == 1);
		assertEquals("buildOld$v", plan.valueReplayTasks().get(0).buildOldMethod());
		assertTrue(plan.allTasks().isEmpty(),
			"value-replay tasks must stay out of allTasks(): TTL/afterRedefineFailed must not ledger them");
		assertFalse(plan.hasStatic());
		assertFalse(plan.hasInstance());
	}

	@Test
	void patchPlanLegacyConstructorsHaveNoValueReplayTasks() {
		InitFix.PatchPlan two = new InitFix.PatchPlan(List.of(), List.of());
		assertTrue(two.isEmpty());
		assertTrue(two.valueReplayTasks().isEmpty());

		InitFix.PatchPlan five = new InitFix.PatchPlan(
			List.of(), List.of(), Map.of(), Map.of(), List.of());
		assertTrue(five.valueReplayTasks().isEmpty());
	}

	// ==================== full-diff requirement (needsMethodBodies) ====================

	/**
	 * Both production call sites ({@code DynamicRedefineExecutor} and the {@code diff_log=false}
	 * batch path) compute a structural diff first and then call
	 * {@code InitFix.needsMethodBodies} followed by {@code ClassDiffUtil.ensureFullClassNodes}.
	 * With the channel enabled the predicate must ask for full bodies, otherwise the initializer
	 * expressions are invisible and the channel silently never fires.
	 */
	@Test
	void needsMethodBodiesForcesFullDiffWhenValueReplayEnabled() {
		byte[] v1 = versionedClass(1);
		byte[] v2 = versionedClass(2);
		ClassDiffUtil.ClassDiff structural = ClassDiffUtil.diffStructural(v1, v2);
		assertFalse(hasCode(structural.oldClass), "premise: diffStructural strips method bodies");
		assertFalse(hasCode(structural.newClass), "premise: diffStructural strips method bodies");

		String previous = System.getProperty(InitFix.VALUE_REPLAY_PROPERTY);
		try {
			System.clearProperty(InitFix.VALUE_REPLAY_PROPERTY);
			assertFalse(InitFix.needsMethodBodies(null, structural),
				"property off must keep the legacy structural diff for a body-only change");

			System.setProperty(InitFix.VALUE_REPLAY_PROPERTY, "true");
			assertTrue(InitFix.needsMethodBodies(null, structural),
				"property on must request full method bodies for initializer analysis");

			// The call-site sequence must actually restore the instruction trees.
			ClassDiffUtil.ensureFullClassNodes(structural, v1, v2);
			assertTrue(hasCode(structural.oldClass), "ensureFullClassNodes must restore old bodies");
			assertTrue(hasCode(structural.newClass), "ensureFullClassNodes must restore new bodies");
		} finally {
			if (previous == null) System.clearProperty(InitFix.VALUE_REPLAY_PROPERTY);
			else System.setProperty(InitFix.VALUE_REPLAY_PROPERTY, previous);
		}
	}

	private static boolean hasCode(ClassNode node) {
		if (node == null) return false;
		for (MethodNode method : node.methods) {
			if (method.instructions != null && method.instructions.size() > 0) return true;
		}
		return false;
	}

	private static byte[] versionedClass(int marker) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "fx/Versioned", null, "java/lang/Object", null);
		MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();
		MethodVisitor stamp = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
			"stamp", "()I", null, null);
		stamp.visitCode();
		stamp.visitLdcInsn(marker);
		stamp.visitInsn(Opcodes.IRETURN);
		stamp.visitMaxs(0, 0);
		stamp.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	// ==================== property + applied-record generation ====================

	@Test
	void valueReplayPropertyIsOffByDefault() {
		String previous = System.getProperty(InitFix.VALUE_REPLAY_PROPERTY);
		try {
			System.clearProperty(InitFix.VALUE_REPLAY_PROPERTY);
			assertFalse(InitFix.valueReplayEnabled(), "value replay must default to off");
			System.setProperty(InitFix.VALUE_REPLAY_PROPERTY, "true");
			assertTrue(InitFix.valueReplayEnabled());
		} finally {
			if (previous == null) System.clearProperty(InitFix.VALUE_REPLAY_PROPERTY);
			else System.setProperty(InitFix.VALUE_REPLAY_PROPERTY, previous);
		}
	}

	@Test
	void appliedRecordInvalidatesOnFingerprintChanges() {
		Class<?> host = ValueTypeReplayWiringTest.class;
		ValueTypeReplay.Field applied = field("genRecord", "old", "new", "ctor", "equals");

		// First round: no record -> the field must trigger.
		assertNull(InitFix.valueReplaySkipReason(host, applied));

		// After a successful write the same initializer must not trigger again.
		InitFix.valueReplayAppliedMarkWritten(host, applied);
		String unchanged = InitFix.valueReplaySkipReason(host, applied);
		assertTrue(unchanged != null && unchanged.contains("unchanged-initializer"),
			"recorded fingerprint must suppress a re-write, got " + unchanged);

		// A different NEW initializer fingerprint must trigger again (initializer changed).
		assertNull(InitFix.valueReplaySkipReason(host,
			field("genRecord", "old", "new-2", "ctor", "equals")));

		// Constructor fingerprint change invalidates the record without replaying this round.
		String invalidated = InitFix.valueReplaySkipReason(host,
			field("genRecord", "old", "new", "ctor-2", "equals"));
		assertTrue(invalidated != null && invalidated.contains("invalidated"),
			"constructor fingerprint change must invalidate the record, got " + invalidated);
		assertNull(InitFix.valueReplaySkipReason(host, applied),
			"invalidated record must be deleted so the next initializer change triggers");

		// equals fingerprint change behaves identically.
		InitFix.valueReplayAppliedMarkWritten(host, applied);
		assertTrue(InitFix.valueReplaySkipReason(host,
			field("genRecord", "old", "new", "ctor", "equals-2")) != null);
		assertNull(InitFix.valueReplaySkipReason(host, applied));

		// Runtime failure and runtime skip must both drop the record (no applied generation).
		InitFix.valueReplayAppliedMarkWritten(host, applied);
		InitFix.valueReplayAppliedMarkFailed(host, "genRecord");
		assertNull(InitFix.valueReplaySkipReason(host, applied), "failed must not be remembered as applied");
		InitFix.valueReplayAppliedMarkWritten(host, applied);
		InitFix.valueReplayAppliedMarkSkipped(host, "genRecord");
		assertNull(InitFix.valueReplaySkipReason(host, applied), "skipped must not be remembered as applied");

		// Whole-class invalidation (redefine failure / TTL) drops every record.
		InitFix.valueReplayAppliedMarkWritten(host, applied);
		InitFix.valueReplayAppliedClear(host);
		assertNull(InitFix.valueReplaySkipReason(host, applied));
	}

	@Test
	void appliedRecordsAreKeyedPerField() {
		Class<?> host = ValueTypeReplayWiringTest.class;
		ValueTypeReplay.Field first = field("isoFirst", "old", "new", "ctor", "equals");
		ValueTypeReplay.Field second = field("isoSecond", "old", "new", "ctor", "equals");

		InitFix.valueReplayAppliedMarkWritten(host, first);
		assertTrue(InitFix.valueReplaySkipReason(host, first) != null);
		assertNull(InitFix.valueReplaySkipReason(host, second),
			"an applied record must not leak to a different field");
		InitFix.valueReplayAppliedClear(host);
	}

	// ==================== emitter: buildOld/buildNew factories ====================

	@Test
	void expressionFactoryMethodEmitsStraightLineConstructor() {
		List<AbstractInsnNode> expression = List.of(
			new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"),
			new InsnNode(Opcodes.DUP),
			new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));

		MethodNode method = InitFixPatchEmitter.expressionFactoryMethod(
			"buildNew$v", expression);

		assertEquals("buildNew$v", method.name);
		assertEquals("()Ljava/lang/Object;", method.desc);
		assertEquals((Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC), method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC));
		assertEquals(expression.size() + 1, method.instructions.size());
		assertEquals(Opcodes.NEW, method.instructions.get(0).getOpcode());
		assertEquals(Opcodes.ARETURN, method.instructions.getLast().getOpcode());
		// The carved nodes are cloned: the analysis-owned instruction objects must not be re-linked.
		assertNotSame(expression.get(0), method.instructions.get(0));

		// The emitted factory must produce a structurally valid class file.
		ClassNode patch = new ClassNode();
		patch.version = Opcodes.V11;
		patch.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
		patch.name = "fx/Host$$HotswapPatch";
		patch.superName = "java/lang/Object";
		patch.methods.add(method);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		patch.accept(writer);
		ClassNode reparsed = new ClassNode();
		new ClassReader(writer.toByteArray()).accept(reparsed, 0);
		assertEquals(1, reparsed.methods.size());
		assertEquals("()Ljava/lang/Object;", reparsed.methods.get(0).desc);
	}

	@Test
	void factoryMethodNamesAreSanitizedPerField() {
		Set<String> used = new LinkedHashSet<>();
		assertEquals("buildOld$v", InitFixPatchEmitter.uniqueMethodName("buildOld$", "v", used));
		// Illegal method-name characters (obfuscated / injected bytecode field names) are replaced.
		assertEquals("buildNew$a_b_c", InitFixPatchEmitter.uniqueMethodName("buildNew$", "a.b;c", used));
		assertEquals("buildOld$v$2", InitFixPatchEmitter.uniqueMethodName("buildOld$", "v", used));
	}
}
