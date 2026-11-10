package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClassDiffUtil#mustRejectHierarchyChange} 的判决表测试。
 *
 * <p>背景：JBR/DCEVM 的增强重定义可以改写接口表（Mixin Duck 接口热更依赖这条），但父类变更没有本地实证，
 * 一律拒绝。批量路径与动态路径共用同一判决，因此这里直接锁死四种组合。</p>
 */
class ClassDiffHierarchyTest {

	@Test
	void addedInterfaceIsRejectedOnStandardHotspotAndAllowedOnDcevm() {
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(
			classBytes("java/lang/Object", "com/example/A"),
			classBytes("java/lang/Object", "com/example/A", "com/example/DuckAccess"));

		assertTrue(diff.hierarchyChanged);
		assertFalse(diff.superChanged, "only the interface table changed");
		assertTrue(diff.interfaceChanged());
		assertTrue(diff.addedInterfaces.contains("com/example/DuckAccess"));
		assertTrue(diff.removedInterfaces.isEmpty());
		assertTrue(ClassDiffUtil.mustRejectHierarchyChange(diff, false),
			"standard HotSpot cannot change the interface table");
		assertFalse(ClassDiffUtil.mustRejectHierarchyChange(diff, true),
			"enhanced redefinition (JBR/DCEVM) may add an interface");
	}

	@Test
	void removedInterfaceIsRejectedOnStandardHotspotAndAllowedOnDcevm() {
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(
			classBytes("java/lang/Object", "com/example/A", "com/example/DuckAccess"),
			classBytes("java/lang/Object", "com/example/A"));

		assertTrue(diff.interfaceChanged());
		assertTrue(diff.removedInterfaces.contains("com/example/DuckAccess"));
		assertTrue(diff.addedInterfaces.isEmpty());
		assertTrue(ClassDiffUtil.mustRejectHierarchyChange(diff, false));
		assertFalse(ClassDiffUtil.mustRejectHierarchyChange(diff, true));
	}

	@Test
	void superclassChangeIsRejectedEvenOnDcevm() {
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(
			classBytes("java/lang/Object"),
			classBytes("com/example/Base"));

		assertTrue(diff.superChanged);
		assertFalse(diff.interfaceChanged(), "a superclass change is not an interface change");
		assertTrue(ClassDiffUtil.mustRejectHierarchyChange(diff, false));
		assertTrue(ClassDiffUtil.mustRejectHierarchyChange(diff, true),
			"no local evidence for superclass redefinition; keep rejecting it");
	}

	@Test
	void unchangedHierarchyIsNeverRejected() {
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(
			classBytes("java/lang/Object", "com/example/A"),
			classBytes("java/lang/Object", "com/example/A"));

		assertFalse(diff.hierarchyChanged);
		assertFalse(diff.interfaceChanged());
		assertFalse(ClassDiffUtil.mustRejectHierarchyChange(diff, false));
		assertFalse(ClassDiffUtil.mustRejectHierarchyChange(diff, true));
	}

	@Test
	void descriptionNamesTheActualDirection() {
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(
			classBytes("java/lang/Object", "com/example/A"),
			classBytes("java/lang/Object", "com/example/B"));

		String text = ClassDiffUtil.describeHierarchyChange(diff);
		assertTrue(text.contains("interfaces"), text);
		assertTrue(text.contains("+[com/example/B]"), text);
		assertTrue(text.contains("-[com/example/A]"), text);
		assertEquals("none", ClassDiffUtil.describeHierarchyChange(
			ClassDiffUtil.diff(classBytes("java/lang/Object"), classBytes("java/lang/Object"))));
	}

	private static byte[] classBytes(String superName, String... interfaces) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Probe", null, superName, interfaces);
		writer.visitEnd();
		return writer.toByteArray();
	}
}
