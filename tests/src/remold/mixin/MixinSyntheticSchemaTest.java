package remold.mixin;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema 锚定纯函数：合成匿名类的字段表必须"只增不减"，否则 JBR/DCEVM 会对全堆做
 * {@code ModifyInstances}（真机把 {@code LootContextPredicate.combinedCondition} 冲成 null 的根因）。
 */
class MixinSyntheticSchemaTest {

	private static ClassNode synthetic(String... fieldSignatures) {
		ClassNode node = new ClassNode();
		node.name = "fixture/Target$Anonymous$abc";
		for (String sig : fieldSignatures) {
			int colon = sig.indexOf(':');
			node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
				sig.substring(0, colon), sig.substring(colon + 1), null, null));
		}
		node.methods.add(new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "<init>",
			"(Lfixture/Target;Lfixture/Target;)V", null, null));
		return node;
	}

	@Test
	void shrinkingCaptureListIsPaddedBackToTheLoadedFieldTable() {
		// 加载态：3 字段 (this, int, Entity)；重生成：2 字段 (this, Target) → 缺 int 捕获
		ClassNode fresh = synthetic("this$0:Lfixture/Target;", "this$1:Lfixture/Target;");
		List<String> loaded = List.of("this$0:Lfixture/Target;", "val$count:I", "this$1:Lfixture/Target;");

		List<String> padded = MixinSyntheticSchema.padFields(fresh, loaded);

		assertEquals(List.of("val$count:I"), padded, "the dropped capture field must be tombstoned");
		assertEquals(loaded, MixinSyntheticSchema.nodeFields(fresh),
			"the field table must be identical to the loaded one (no shrink => no ModifyInstances)");
		assertTrue(MixinSyntheticSchema.canRedefineWithoutInstanceRewrite(fresh, loaded));
	}

	@Test
	void sameSchemaIsNotPadded() {
		List<String> loaded = List.of("this$0:Lfixture/Target;");
		ClassNode fresh = synthetic("this$0:Lfixture/Target;");

		assertTrue(MixinSyntheticSchema.padFields(fresh, loaded).isEmpty());
		assertEquals(loaded, MixinSyntheticSchema.nodeFields(fresh));
	}

	@Test
	void fieldTypeDriftIsRefusedInsteadOfTriggeringHeapRewrite() {
		// 同名不同描述符：墓碑字段无法表达类型漂移 → 必须 fail-closed
		ClassNode fresh = synthetic("val$count:J");
		List<String> loaded = List.of("val$count:I");

		assertFalse(MixinSyntheticSchema.canRedefineWithoutInstanceRewrite(fresh, loaded),
			"a field type drift must be refused (no ModifyInstances)");
	}

	@Test
	void schemaHashDistinguishesFieldSetsButIsStableForTheSameSchema() {
		String twoFields = MixinSyntheticSchema.schemaHash(
			synthetic("this$0:Lfixture/Target;", "this$1:Lfixture/Target;"));
		String threeFields = MixinSyntheticSchema.schemaHash(
			synthetic("this$0:Lfixture/Target;", "val$count:I", "this$1:Lfixture/Target;"));
		String sameTwoFields = MixinSyntheticSchema.schemaHash(
			synthetic("this$0:Lfixture/Target;", "this$1:Lfixture/Target;"));

		assertEquals(twoFields, sameTwoFields, "the same capture schema must hash identically");
		assertNotEquals(twoFields, threeFields, "a changed capture schema must hash differently");
	}
}
