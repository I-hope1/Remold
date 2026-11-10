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
	void sameNameDifferentDescriptorIsRenamedAndAppended() {
		// R0: val$self: LTarget; → R1: val$self: LEntity;（同名异型）
		ClassNode fresh = synthetic("this$0:Lfixture/Target;", "val$helper:I", "val$self:Lnet/minecraft/entity/Entity;");
		fresh.methods.add(new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null));
		org.objectweb.asm.tree.MethodNode run = fresh.methods.get(1);
		run.instructions = new org.objectweb.asm.tree.InsnList();
		run.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD,
			"fixture/Target$Anonymous$abc", "val$self", "Lnet/minecraft/entity/Entity;"));
		run.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
		List<String> loaded = List.of("this$0:Lfixture/Target;", "val$self:Lfixture/Target;");

		java.util.Map<String, String> renamed = MixinSyntheticSchema.renameCollisions(fresh, loaded);

		assertEquals(1, renamed.size(), "the same-name field with a different descriptor must be renamed");
		String newName = renamed.get("val$self");
		assertTrue(newName.startsWith("val$self$remold$"), "deterministic schema-qualified name: " + newName);
		assertTrue(MixinSyntheticSchema.nodeFields(fresh).contains(newName + ":Lnet/minecraft/entity/Entity;"));
		// 访问点必须一起改写
		org.objectweb.asm.tree.FieldInsnNode access =
			(org.objectweb.asm.tree.FieldInsnNode) run.instructions.getFirst();
		assertEquals(newName, access.name, "GETFIELD must follow the rename");

		// 基线槽位原封不动（墓碑），新字段追加在末尾
		List<String> padded = MixinSyntheticSchema.padFields(fresh, loaded);
		assertEquals(List.of("val$self:Lfixture/Target;"), padded);
		List<String> table = MixinSyntheticSchema.nodeFields(fresh);
		assertEquals("this$0:Lfixture/Target;", table.get(0), "baseline slot 0 must not move");
		assertEquals("val$self:Lfixture/Target;", table.get(1), "baseline slot 1 must not move");
		assertTrue(table.contains("val$helper:I"));
		assertTrue(table.contains(newName + ":Lnet/minecraft/entity/Entity;"));
	}

	@Test
	void baselineConstructorOverloadIsRetainedAlongsideTheNewOne() {
		byte[] r0 = classBytes(synthetic("this$0:Lfixture/Target;", "val$self:Lfixture/Target;"),
			"(Lfixture/Target;Lfixture/Target;)V");
		ClassNode r1 = synthetic("this$0:Lfixture/Target;", "val$helper:I",
			"val$self:Lnet/minecraft/entity/Entity;");
		r1.methods.clear();
		r1.methods.add(new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "<init>",
			"(Lfixture/Target;ILnet/minecraft/entity/Entity;)V", null, null));

		List<String> retained = MixinSyntheticSchema.retainConstructors(r1, r0);

		assertEquals(List.of("(Lfixture/Target;Lfixture/Target;)V"), retained,
			"the baseline <init> must be retained as an overload");
		assertEquals(2, r1.methods.size(), "old and new <init> must coexist");
	}

	private static byte[] classBytes(ClassNode node, String ctorDesc) {
		node.methods.clear();
		org.objectweb.asm.tree.MethodNode ctor =
			new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "<init>", ctorDesc, null, null);
		ctor.instructions = new org.objectweb.asm.tree.InsnList();
		ctor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
		node.methods.add(ctor);
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
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

	// ---------------------------------------------------------------- 逐次字段表变化分类（战报拆分口径）

	/**
	 * Every synthetic redefine must be classifiable <b>from its own log line</b>: without it a battle
	 * report can only say "no tombstone and no rename line", which cannot tell APPEND_ONLY from
	 * UNCHANGED (the 24-snapshot real-host run had exactly that gap).
	 */
	@Test
	void fieldTableChangeIsClassifiedPerRedefine() {
		List<String> loaded = List.of("this$0:Lfixture/Target;", "val$self:Lfixture/Target;");

		ClassNode unchanged = synthetic("this$0:Lfixture/Target;", "val$self:Lfixture/Target;");
		assertEquals("UNCHANGED(appended=0, renamed=0, tombstone=0)",
			MixinSyntheticSchema.describeFieldTableChange(
				MixinSyntheticSchema.appendedFields(unchanged, loaded), java.util.Map.of(), List.of()),
			"an identical field table must be reported as UNCHANGED, not as an append");

		ClassNode appended = synthetic("this$0:Lfixture/Target;", "val$self:Lfixture/Target;", "val$extra:I");
		List<String> appendedFields = MixinSyntheticSchema.appendedFields(appended, loaded);
		assertEquals(List.of("val$extra:I"), appendedFields,
			"only a field the loaded class does not have may be reported as appended");
		assertEquals("APPEND_ONLY(appended=1, renamed=0, tombstone=0)",
			MixinSyntheticSchema.describeFieldTableChange(appendedFields, java.util.Map.of(), List.of()));

		ClassNode shrunk = synthetic("this$0:Lfixture/Target;");
		List<String> padded = MixinSyntheticSchema.padFields(shrunk, loaded);
		assertEquals("WOULD_SHRINK(appended=0, renamed=0, tombstone=1)",
			MixinSyntheticSchema.describeFieldTableChange(List.of(), java.util.Map.of(), padded),
			"a field that only survives as a tombstone must be reported as WOULD_SHRINK");

		assertEquals("TYPE_CHANGE(appended=0, renamed=1, tombstone=0)",
			MixinSyntheticSchema.describeFieldTableChange(List.of(),
				java.util.Map.of("val$self", "val$self$remold$1"), List.of()),
			"a renamed same-name field is a type change and must outrank the other labels");
	}
}
