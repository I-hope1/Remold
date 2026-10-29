package remold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import remold.offset.OffsetBindingIndex;
import remold.offset.OffsetScanner.Binding;
import remold.offset.OffsetScanner.Confidence;
import remold.offset.OffsetScanner.Kind;
import remold.offset.OffsetScanner.Sink;
import remold.offset.SuperclassDriftGate;
import remold.offset.SuperclassDriftGate.DriftDecision;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 SuperclassDriftGate 对父类新增实例字段引发的子类物理偏移漂移的判定精准度与因果性。
 */
public class SuperclassDriftGateTest {

	private static final String BASE = "t/Base";
	private static final String SUB = "t/Sub";
	private static final String LEAF = "t/Leaf";

	@Test
	@DisplayName("基线放行：父类新增实例字段，子孙类无任何已知偏移持有者且无存活动态持有者")
	public void testNoHoldersPass() {
		// 1. 前置基线确认：索引中没有任何指向 SUB 的绑定
		OffsetBindingIndex emptyIndex = OffsetBindingIndex.empty();
		assertTrue(emptyIndex.isEmpty(), "基线确认：空索引");

		// 2. 触发判定
		DriftDecision decision = SuperclassDriftGate.checkSuperclassDrift(
			BASE,
			emptyIndex,
			base -> Set.of(SUB),
			cls -> List.of("x", "y"),
			holder -> false
		);

		// 3. 终局断言：安全放行
		assertTrue(decision.isSafe(), "无持有者时必须放行");
		assertTrue(decision.hits().isEmpty());
		assertTrue(decision.dynamicHits().isEmpty());
		assertTrue(decision.formatReport().contains("PASS"));
	}

	@Test
	@DisplayName("精确命中阻断：子类实例字段偏移被 static 常量持有，父类加字段必须阻断")
	public void testExactHolderRejected() {
		Binding exactBinding = new Binding(
			SUB, "subField",
			Kind.INSTANCE_OFFSET, Sink.STATIC_FIELD,
			"t/Holder", "SUB_FIELD_OFFSET",
			"Holder.<clinit>:15", Confidence.EXACT
		);

		// 1. 前置基线确认：索引能精确查出绑定
		OffsetBindingIndex index = OffsetBindingIndex.from(List.of(exactBinding));
		assertEquals(1, index.lookup(SUB, "subField").size(), "前置确认：索引查出绑定");
		assertTrue(index.lookup(SUB, "otherField").isEmpty(), "前置确认：无关字段无绑定");

		// 2. 触发判定：Base 加字段，Sub 包含 subField
		DriftDecision decision = SuperclassDriftGate.checkSuperclassDrift(
			BASE,
			index,
			base -> Set.of(SUB),
			cls -> List.of("subField", "otherField"),
			holder -> false
		);

		// 3. 因果链终局断言：阻断并报告具体现场
		assertFalse(decision.isSafe(), "存在持有者必须阻断");
		assertEquals(1, decision.hits().size());
		assertEquals("subField", decision.hits().get(0).fieldName());
		assertEquals("t/Holder", decision.hits().get(0).binding().holderClass());
		assertTrue(decision.formatReport().contains("SUPERCLASS_FIELD_DRIFT"));
		assertTrue(decision.formatReport().contains("SUB_FIELD_OFFSET"));

		// 4. 对照组（去触发）：如果子类没有 subField，只有 otherField，断言必须放行
		DriftDecision counterfactual = SuperclassDriftGate.checkSuperclassDrift(
			BASE,
			index,
			base -> Set.of(SUB),
			cls -> List.of("otherField"),
			holder -> false
		);
		assertTrue(counterfactual.isSafe(), "对照组：子类未声明被持有的字段时必须放行");
	}

	@Test
	@DisplayName("类级通配阻断：子类存在反射遍历或拼接字段的通配绑定，子类任何字段漂移均被拦截")
	public void testWildcardHolderRejected() {
		Binding wildcardBinding = new Binding(
			SUB, null,
			Kind.INSTANCE_OFFSET, Sink.ESCAPED,
			"t/WildcardHolder", "OFFSETS",
			"WildcardHolder.<clinit>:25", Confidence.WILDCARD_FIELD
		);

		OffsetBindingIndex index = OffsetBindingIndex.from(List.of(wildcardBinding));
		assertFalse(index.lookupClassWildcards(SUB).isEmpty(), "前置确认：存在类级通配");

		DriftDecision decision = SuperclassDriftGate.checkSuperclassDrift(
			BASE,
			index,
			base -> Set.of(SUB),
			cls -> List.of("anyField"),
			holder -> false
		);

		assertFalse(decision.isSafe(), "类级通配必须拦截子类字段漂移");
		assertEquals(1, decision.hits().size());
		assertEquals(Confidence.WILDCARD_FIELD, decision.hits().get(0).binding().conf());
	}

	@Test
	@DisplayName("动态持有者因果联动：只有在堆上有存活实例时才阻断，无存活实例放行")
	public void testDynamicHolderLivenessCausality() {
		String dynamicHolder = "java/util/concurrent/atomic/AtomicIntegerFieldUpdater$AtomicIntegerFieldUpdaterImpl";
		Binding dynBinding = new Binding(
			null, "val",
			Kind.INSTANCE_OFFSET, Sink.INSTANCE_FIELD,
			dynamicHolder, "offset",
			"AtomicIntegerFieldUpdaterImpl.<init>:380", Confidence.UNRESOLVED_CLASS
		);

		OffsetBindingIndex index = OffsetBindingIndex.from(List.of(dynBinding));
		assertTrue(index.dynamicHolders().contains(dynamicHolder), "前置确认：识别为动态持有者");

		// 实验组：堆上有存活实例（模拟 LibTool 发现对象）
		DriftDecision liveDecision = SuperclassDriftGate.checkSuperclassDrift(
			BASE,
			index,
			base -> Set.of(SUB),
			cls -> List.of("f1"),
			holder -> holder.equals(dynamicHolder) // 模拟有实例
		);
		assertFalse(liveDecision.isSafe(), "动态持有者有存活实例时必须阻断");
		assertEquals(1, liveDecision.dynamicHits().size());
		assertEquals(dynamicHolder, liveDecision.dynamicHits().get(0).holderClass());

		// 对照组：堆上无存活实例
		DriftDecision noLiveDecision = SuperclassDriftGate.checkSuperclassDrift(
			BASE,
			index,
			base -> Set.of(SUB),
			cls -> List.of("f1"),
			holder -> false // 模拟无存活实例
		);
		assertTrue(noLiveDecision.isSafe(), "动态持有者无存活实例时必须放行（避免一票否决）");
	}

	@Test
	@DisplayName("深层继承链追溯：Base -> Middle -> Leaf，Leaf 字段被持有依然阻断")
	public void testDeepInheritanceDrift() {
		Binding leafBinding = new Binding(
			LEAF, "leafVal",
			Kind.VARHANDLE, Sink.STATIC_FIELD,
			"t/LeafHolder", "VH",
			"LeafHolder.<clinit>:10", Confidence.EXACT
		);

		OffsetBindingIndex index = OffsetBindingIndex.from(List.of(leafBinding));

		// Base 的子孙包括 Middle 和 Leaf
		DriftDecision decision = SuperclassDriftGate.checkSuperclassDrift(
			BASE,
			index,
			base -> Set.of("t/Middle", LEAF),
			cls -> cls.equals(LEAF) ? List.of("leafVal") : List.of("middleVal"),
			holder -> false
		);

		assertFalse(decision.isSafe(), "深层叶子类的字段漂移必须被识别");
		assertEquals(1, decision.hits().size());
		assertEquals(LEAF, decision.hits().get(0).descendantClass());
	}

	@Test
	@DisplayName("基类自身字段不误杀：持有者仅缓存了 Base 自身的字段偏移，Base 加字段天然安全放行")
	public void testBaseSelfFieldsNotMistaken() {
		// 持有者缓存的是 BASE 自身的字段偏移
		Binding baseBinding = new Binding(
			BASE, "baseExisting",
			Kind.INSTANCE_OFFSET, Sink.STATIC_FIELD,
			"t/BaseHolder", "BASE_OFFSET",
			"BaseHolder.<clinit>:8", Confidence.EXACT
		);

		OffsetBindingIndex index = OffsetBindingIndex.from(List.of(baseBinding));

		DriftDecision decision = SuperclassDriftGate.checkSuperclassDrift(
			BASE,
			index,
			base -> Set.of(SUB),
			cls -> List.of("subField1"),
			holder -> false
		);

		assertTrue(decision.isSafe(), "基类自身的字段在尾部追加时偏移不漂移，不得误阻断");
		assertTrue(decision.hits().isEmpty());
	}
}
