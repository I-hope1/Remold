package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐结果必须给**每一个**新匿名类一个落地槽位。
 *
 * <p>这条不变量对应一类真实故障：宿主字节码引用了某个新匿名类的名字，而该名字在批次里没有目标槽位，
 * 于是热更后构造宿主直接 {@code NoClassDefFoundError}（现场形态：捕获增长导致重编号，新类 {@code $6}
 * 从未被定义）。这里断言"新匿名类 → 目标名"的映射完整、目标名非空，且**新建**（旧版本没有对应类）
 * 的匿名类同样拿到槽位——它恰恰是最容易被静默丢掉的那一类。</p>
 */
class AlignedAnonSlotCompletenessTest {

	private static final String HOST = "example/KtAnonTarget";

	private static byte[] kotlinAnon(String name, String enclosing, int value) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
			name, null, "java/lang/Object", null);
		cw.visitOuterClass(enclosing, null, null);
		cw.visitInnerClass(name, null, null, Opcodes.ACC_FINAL | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
		mv.visitCode();
		mv.visitIntInsn(Opcodes.BIPUSH, value);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] host(String name, String... instantiated) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		for (String anon : instantiated) {
			mv.visitTypeInsn(Opcodes.NEW, anon);
			mv.visitInsn(Opcodes.POP);
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(2, 1);
		mv.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void everyNewAnonymousClassGetsATargetSlot() {
		String oldAnon = HOST + "$propAnonA$1";
		String newAnon = HOST + "$propAnonA$1";
		Map<String, byte[]> oldAnons = new LinkedHashMap<>();
		oldAnons.put(oldAnon, kotlinAnon(oldAnon, HOST, 1));
		Map<String, byte[]> newAnons = new LinkedHashMap<>();
		newAnons.put(newAnon, kotlinAnon(newAnon, HOST, 2));

		AnonClassAligner.Result res = AnonClassAligner.align(
			HOST, host(HOST, newAnon), oldAnons, newAnons,
			n -> oldAnons.get(n), n -> newAnons.get(n), n -> false, new java.util.HashSet<>());

		assertEquals(newAnons.size(), res.alignedAnonClasses.size(),
			"every new anonymous class must be installed under some target name");
		String target = res.renameMap.get(newAnon);
		assertNotNull(target, "the new anonymous class must have a target slot");
		assertFalse(target.isEmpty(), "an empty target name would be dropped downstream");
		assertTrue(res.alignedAnonClasses.containsKey(target),
			"the aligned bytes must be keyed by the very target name the host will reference");
	}

	@Test
	void aBrandNewAnonymousClassAlsoGetsASlot() {
		String existing = HOST + "$propAnonA$1";
		String fresh = HOST + "$anonStruct$1";
		Map<String, byte[]> oldAnons = new LinkedHashMap<>();
		oldAnons.put(existing, kotlinAnon(existing, HOST, 1));
		Map<String, byte[]> newAnons = new LinkedHashMap<>();
		newAnons.put(existing, kotlinAnon(existing, HOST, 1));
		newAnons.put(fresh, kotlinAnon(fresh, HOST, 7));

		AnonClassAligner.Result res = AnonClassAligner.align(
			HOST, host(HOST, existing, fresh), oldAnons, newAnons,
			n -> oldAnons.get(n), n -> newAnons.get(n), n -> false, new java.util.HashSet<>());

		String freshTarget = res.renameMap.get(fresh);
		assertNotNull(freshTarget, "a newly declared anonymous class must still receive a slot");
		assertTrue(res.alignedAnonClasses.containsKey(freshTarget),
			"the host references this new class, so its bytes must be installed under that target name");
	}
}
