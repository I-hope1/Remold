package remold.offset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import remold.offset.LayoutPredictor.DriftingField;
import remold.offset.OffsetScanner.Binding;
import remold.offset.OffsetScanner.Confidence;
import remold.offset.OffsetScanner.Kind;
import remold.offset.OffsetScanner.Sink;
import remold.offset.SuperclassDriftGate.DriftDecision;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class LayoutPredictorTest {

	private static byte[] genClass(String internalName, String superName, List<String[]> fields) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, superName, null);
		for (String[] f : fields) {
			int acc = Opcodes.ACC_PUBLIC;
			if ("static".equals(f[2])) acc |= Opcodes.ACC_STATIC;
			cw.visitField(acc, f[0], f[1], null, null).visitEnd();
		}
		var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	@DisplayName("LayoutPredictor 干净加载与物理字段偏移预测")
	public void testPredictOffsets() {
		String dotName = "remold.offset.test.PredSample";
		String internalName = dotName.replace('.', '/');

		byte[] bytes = genClass(internalName, "java/lang/Object", List.of(
			new String[]{"a", "I", "instance"},
			new String[]{"b", "J", "instance"},
			new String[]{"c", "Ljava/lang/String;", "instance"}
		));

		Map<String, Long> offsets = LayoutPredictor.predictOffsets(
			dotName,
			Map.of(internalName, bytes),
			name -> null,
			LayoutPredictorTest.class.getClassLoader()
		);

		assertNotNull(offsets, "Prediction offsets should not be null");
		assertTrue(offsets.containsKey("a"), "Field 'a' must be in predicted offsets");
		assertTrue(offsets.containsKey("b"), "Field 'b' must be in predicted offsets");
		assertTrue(offsets.containsKey("c"), "Field 'c' must be in predicted offsets");
		assertTrue(offsets.get("a") > 0);
		assertTrue(offsets.get("b") > 0);
		assertTrue(offsets.get("c") > 0);
	}

	@Test
	@DisplayName("结果型漂移探测：空洞填补与类型重排引发本类已有字段漂移被精准检出")
	public void testFindDriftingFieldsOnLiveClass() throws Exception {
		// 使用已加载的测试类作为基线
		String internal = "remold/offset/test/HoleTarget";
		String dotName = internal.replace('/', '.');

		byte[] v1Bytes = genClass(internal, "java/lang/Object", List.of(
			new String[]{"b1", "B", "instance"},
			new String[]{"l1", "J", "instance"}
		));

		// 在内存加载 v1
		LayoutPredictor.EphemeralClassLoader loaderV1 = new LayoutPredictor.EphemeralClassLoader(
			Map.of(internal, v1Bytes), null, LayoutPredictorTest.class.getClassLoader()
		);
		Class<?> liveTargetClass = Class.forName(dotName, false, loaderV1);

		// 生成 v2 字节码：插入 short s1 填补 13..15 空洞，导致 b1 漂移
		byte[] v2Bytes = genClass(internal, "java/lang/Object", List.of(
			new String[]{"b1", "B", "instance"},
			new String[]{"s1", "S", "instance"},
			new String[]{"l1", "J", "instance"}
		));

		List<DriftingField> drifting = LayoutPredictor.findDriftingFields(
			liveTargetClass,
			v2Bytes,
			Map.of(internal, v2Bytes),
			name -> null,
			base -> Collections.emptySet()
		);

		assertNotNull(drifting, "Drifting fields list should not be null");
		// b1 发生漂移
		boolean hasB1Drift = drifting.stream().anyMatch(d -> d.fieldName().equals("b1"));
		assertTrue(hasB1Drift, "Existing field 'b1' must be identified as drifting due to hole filling");
	}

	@Test
	@DisplayName("checkLayoutDrift 判定：命中漂移字段时阻断，无关字段放行")
	public void testCheckLayoutDriftGate() {
		DriftingField dfB1 = new DriftingField("remold/offset/test/Target", "b1", 12, 14);
		DriftingField dfOther = new DriftingField("remold/offset/test/Target", "other", 20, 24);

		Binding bindingB1 = new Binding(
			"remold/offset/test/Target", "b1",
			Kind.INSTANCE_OFFSET, Sink.STATIC_FIELD,
			"remold/offset/test/Holder", "B1_OFF",
			"Holder.<clinit>:5", Confidence.EXACT
		);

		OffsetBindingIndex indexWithB1 = OffsetBindingIndex.from(List.of(bindingB1));

		// 1. 命中漂移字段 -> 阻断
		DriftDecision decisionReject = SuperclassDriftGate.checkLayoutDrift(
			"remold/offset/test/Target",
			List.of(dfB1),
			indexWithB1,
			holder -> false
		);
		assertFalse(decisionReject.isSafe(), "Must reject when drifting field has a registered binding");
		assertTrue(decisionReject.formatReport().contains("LAYOUT_DRIFT"), "Report must identify LAYOUT_DRIFT");
		assertEquals(1, decisionReject.hits().size());
		assertEquals("b1", decisionReject.hits().get(0).fieldName());

		// 2. 对照组：漂移字段不在绑定图中 -> 放行
		DriftDecision decisionPass = SuperclassDriftGate.checkLayoutDrift(
			"remold/offset/test/Target",
			List.of(dfOther),
			indexWithB1,
			holder -> false
		);
		assertTrue(decisionPass.isSafe(), "Control: Must pass when drifting field is not cached by any holder");

		// 3. 对照组：无漂移字段 -> 放行
		DriftDecision decisionNoDrift = SuperclassDriftGate.checkLayoutDrift(
			"remold/offset/test/Target",
			Collections.emptyList(),
			indexWithB1,
			holder -> false
		);
		assertTrue(decisionNoDrift.isSafe(), "Control: Must pass when no fields drifted");
	}

	@Test
	@DisplayName("checkLayoutDrift 动态持有者堆存活判定")
	public void testDynamicHolderHandling() {
		DriftingField df = new DriftingField("remold/offset/test/Target", "x", 16, 24);

		Binding dynBinding = new Binding(
			null, "x",
			Kind.UPDATER, Sink.INSTANCE_FIELD,
			"java/util/concurrent/atomic/AtomicIntegerFieldUpdaterImpl", "offset",
			"Updater.<init>:10", Confidence.UNRESOLVED_CLASS
		);
		OffsetBindingIndex dynIndex = OffsetBindingIndex.from(List.of(dynBinding));

		// 堆上有存活实例 -> 拒绝
		DriftDecision rejectLive = SuperclassDriftGate.checkLayoutDrift(
			"remold/offset/test/Target",
			List.of(df),
			dynIndex,
			holder -> holder.contains("AtomicIntegerFieldUpdater")
		);
		assertFalse(rejectLive.isSafe(), "Must reject when unresolved dynamic holder has live instances on heap");
		assertEquals(1, rejectLive.dynamicHits().size());

		// 堆上无存活实例 -> 放行
		DriftDecision passDead = SuperclassDriftGate.checkLayoutDrift(
			"remold/offset/test/Target",
			List.of(df),
			dynIndex,
			holder -> false
		);
		assertTrue(passDead.isSafe(), "Must pass when dynamic holder has NO live instances on heap");
	}

	@Test
	@DisplayName("predictLayout 与 Gate：删除字段冲突检测 (DELETED_FIELD_OFFSET_CONFLICT)")
	public void testDeletedFieldConflictGate() throws Exception {
		String internal = "remold/offset/test/DelSample";
		String dotName = internal.replace('/', '.');

		byte[] v1Bytes = genClass(internal, "java/lang/Object", List.of(
			new String[]{"x", "I", "instance"},
			new String[]{"y", "I", "instance"}
		));

		LayoutPredictor.EphemeralClassLoader loaderV1 = new LayoutPredictor.EphemeralClassLoader(
			Map.of(internal, v1Bytes), null, LayoutPredictorTest.class.getClassLoader()
		);
		Class<?> liveClass = Class.forName(dotName, false, loaderV1);

		// v2: 删掉 x
		byte[] v2Bytes = genClass(internal, "java/lang/Object", Collections.singletonList(
			new String[]{"y", "I", "instance"}
		));

		LayoutPredictor.PredictionResult result = LayoutPredictor.predictLayout(
			liveClass,
			v2Bytes,
			Map.of(internal, v2Bytes),
			name -> null,
			base -> Collections.emptySet()
		);

		assertTrue(result.isComplete(), "Prediction must be complete");
		assertTrue(result.hasDeletedFields(), "Must detect deleted field 'x'");
		assertEquals(1, result.deletedFields().size());
		assertEquals("x", result.deletedFields().get(0).fieldName());

		// 绑定图中缓存了被删除的字段 x -> 阻断
		Binding bindingX = new Binding(
			internal, "x",
			Kind.INSTANCE_OFFSET, Sink.STATIC_FIELD,
			"remold/offset/test/Holder", "X_OFF",
			"Holder.<clinit>:1", Confidence.EXACT
		);
		OffsetBindingIndex indexWithX = OffsetBindingIndex.from(List.of(bindingX));

		DriftDecision decision = SuperclassDriftGate.checkLayoutDrift(
			internal,
			result,
			indexWithX,
			holder -> false,
			false,
			0
		);

		assertFalse(decision.isSafe(), "Must reject when deleted field has cached holder");
		assertTrue(decision.formatReport().contains("DELETED_FIELD_OFFSET_CONFLICT"),
			"Report must identify DELETED_FIELD_OFFSET_CONFLICT");
		assertEquals(1, decision.deletedHits().size());

		// 对照组：绑定图中只缓存了未删除的 y，且 y 未漂移 -> 放行
		Binding bindingY = new Binding(
			internal, "y",
			Kind.INSTANCE_OFFSET, Sink.STATIC_FIELD,
			"remold/offset/test/Holder", "Y_OFF",
			"Holder.<clinit>:2", Confidence.EXACT
		);
		OffsetBindingIndex indexWithY = OffsetBindingIndex.from(List.of(bindingY));

		// 如果 y 漂移了则根据漂移判定，若无漂移则放行
		LayoutPredictor.PredictionResult noDriftResult = LayoutPredictor.PredictionResult.complete(
			Collections.emptyList(),
			List.of(new LayoutPredictor.DeletedField(internal, "x", 12))
		);
		DriftDecision decisionPass = SuperclassDriftGate.checkLayoutDrift(
			internal,
			noDriftResult,
			indexWithY,
			holder -> false,
			false,
			0
		);
		assertTrue(decisionPass.isSafe(), "Control: Must pass when deleted field is NOT cached and others didn't drift");
	}

	@Test
	@DisplayName("Incomplete Prediction 与严格模式安全门 (UNPREDICTABLE_LAYOUT_DRIFT)")
	public void testIncompletePredictionAndStrictMode() {
		String target = "remold/offset/test/BaseTarget";
		String unpredictedDescendant = "remold/offset/test/UnknownSub";

		LayoutPredictor.PredictionResult incomplete = LayoutPredictor.PredictionResult.partial(
			Collections.emptyList(),
			Collections.emptyList(),
			Set.of(unpredictedDescendant)
		);

		OffsetBindingIndex emptyIndex = OffsetBindingIndex.from(Collections.emptyList());

		// 1. 严格模式下：存在未复现子孙类 -> 直接阻断
		DriftDecision rejectStrict = SuperclassDriftGate.checkLayoutDrift(
			target,
			incomplete,
			emptyIndex,
			holder -> false,
			true, // strictMode
			3 // blindSpots
		);
		assertFalse(rejectStrict.isSafe(), "Strict mode must reject when descendant prediction is incomplete");
		assertTrue(rejectStrict.formatReport().contains("UNPREDICTABLE_LAYOUT_DRIFT"),
			"Report must state UNPREDICTABLE_LAYOUT_DRIFT in strict mode");
		assertTrue(rejectStrict.formatReport().contains("Blind spot count: 3"),
			"Report must include blind spot count");

		// 2. 非严格模式下：未复现子孙类没有任何缓存持有者 -> 容忍放行
		DriftDecision passNonStrict = SuperclassDriftGate.checkLayoutDrift(
			target,
			incomplete,
			emptyIndex,
			holder -> false,
			false, // non-strict
			3
		);
		assertTrue(passNonStrict.isSafe(), "Non-strict mode must pass when unpredicted class has no cached holders");
		assertTrue(passNonStrict.formatReport().contains("Blind spots: 3"),
			"Pass report must log blind spots for observability");

		// 3. 非严格模式下：未复现子孙类有通配或字段持有者 -> 依然 fail-closed 阻断
		Binding subBinding = new Binding(
			unpredictedDescendant, "*",
			Kind.INSTANCE_OFFSET, Sink.STATIC_FIELD,
			"remold/offset/test/Holder", "SUB_OFF",
			"Holder.<clinit>:1", Confidence.WILDCARD_FIELD
		);
		OffsetBindingIndex subIndex = OffsetBindingIndex.from(List.of(subBinding));
		DriftDecision rejectNonStrictWithHolder = SuperclassDriftGate.checkLayoutDrift(
			target,
			incomplete,
			subIndex,
			holder -> false,
			false,
			1
		);
		assertFalse(rejectNonStrictWithHolder.isSafe(), "Non-strict mode must still reject when unpredicted class has cached holder");
	}

	@Test
	@DisplayName("包级私有父类与 Runtime Package 跨 Loader 访问安全 (IllegalAccessError 防御与对照)")
	public void testPackagePrivateSuperclassRuntimePackageAccess() throws Exception {
		String baseInternal = "remold/offset/testpkg/PkgPrivateBase";
		String childInternal = "remold/offset/testpkg/PublicChild";
		String childDot = "remold.offset.testpkg.PublicChild";

		// 1. 生成包级私有父类（无 ACC_PUBLIC）与公共子类
		ClassWriter cwBase = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cwBase.visit(Opcodes.V11, 0, baseInternal, null, "java/lang/Object", null);
		cwBase.visitField(Opcodes.ACC_PUBLIC, "baseField", "I", null, null).visitEnd();
		var mvBase = cwBase.visitMethod(0, "<init>", "()V", null, null);
		mvBase.visitCode();
		mvBase.visitVarInsn(Opcodes.ALOAD, 0);
		mvBase.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mvBase.visitInsn(Opcodes.RETURN);
		mvBase.visitMaxs(1, 1);
		mvBase.visitEnd();
		cwBase.visitEnd();
		byte[] baseBytes = cwBase.toByteArray();

		ClassWriter cwChild = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cwChild.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, childInternal, null, baseInternal, null);
		cwChild.visitField(Opcodes.ACC_PUBLIC, "childField", "I", null, null).visitEnd();
		var mvChild = cwChild.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mvChild.visitCode();
		mvChild.visitVarInsn(Opcodes.ALOAD, 0);
		mvChild.visitMethodInsn(Opcodes.INVOKESPECIAL, baseInternal, "<init>", "()V", false);
		mvChild.visitInsn(Opcodes.RETURN);
		mvChild.visitMaxs(1, 1);
		mvChild.visitEnd();
		cwChild.visitEnd();
		byte[] childV1Bytes = cwChild.toByteArray();

		// 子类 v2（新增一个字段）
		ClassWriter cwChildV2 = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cwChildV2.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, childInternal, null, baseInternal, null);
		cwChildV2.visitField(Opcodes.ACC_PUBLIC, "childField", "I", null, null).visitEnd();
		cwChildV2.visitField(Opcodes.ACC_PUBLIC, "childField2", "J", null, null).visitEnd();
		var mvChildV2 = cwChildV2.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mvChildV2.visitCode();
		mvChildV2.visitVarInsn(Opcodes.ALOAD, 0);
		mvChildV2.visitMethodInsn(Opcodes.INVOKESPECIAL, baseInternal, "<init>", "()V", false);
		mvChildV2.visitInsn(Opcodes.RETURN);
		mvChildV2.visitMaxs(1, 1);
		mvChildV2.visitEnd();
		cwChildV2.visitEnd();
		byte[] childV2Bytes = cwChildV2.toByteArray();

		// 用独立 ClassLoader 加载已有类
		ClassLoader testParentLoader = new ClassLoader(LayoutPredictorTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if ("remold.offset.testpkg.PkgPrivateBase".equals(name)) {
					return defineClass(name, baseBytes, 0, baseBytes.length);
				}
				if ("remold.offset.testpkg.PublicChild".equals(name)) {
					return defineClass(name, childV1Bytes, 0, childV1Bytes.length);
				}
				return super.findClass(name);
			}
		};

		Class<?> liveChildClass = Class.forName(childDot, false, testParentLoader);

		// 正向验证：fallbackProvider 提供了父类字节码 -> 自动注入同一 EphemeralClassLoader，成功预测
		LayoutPredictor.PredictionResult successRes = LayoutPredictor.predictLayout(
			liveChildClass,
			childV2Bytes,
			Collections.emptyMap(),
			internal -> baseInternal.equals(internal) ? baseBytes : null,
			desc -> Collections.emptySet()
		);
		assertTrue(successRes.isComplete(), "When ancestor bytecode is provided, prediction must succeed without IllegalAccessError");
		assertTrue(successRes.unpredictedClasses().isEmpty(), "Unpredicted classes must be empty");

		// 负向对照：fallbackProvider 无法提供父类字节码 -> 委派给父 Loader 发生 Runtime Package 隔离，必须捕获并进入 unpredictedClasses
		LayoutPredictor.PredictionResult failedRes = LayoutPredictor.predictLayout(
			liveChildClass,
			childV2Bytes,
			Collections.emptyMap(),
			internal -> null, // 无父类字节码
			desc -> Collections.emptySet()
		);
		assertFalse(failedRes.isComplete(), "Counterfactual: Without ancestor bytecode, package-private superclass must fail clean-load");
		assertTrue(failedRes.unpredictedClasses().contains(childInternal),
			"Child class must be marked unpredicted due to IllegalAccessError cross-loader access");
	}
}
