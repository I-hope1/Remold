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
}
