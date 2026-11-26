package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 现场形态：宿主本身就是匿名类，它唯一的子匿名类中间多了一段名字（kotlinc 的属性名）。
 *
 * <pre>
 *   VerAnonTarget            (real host)
 *     VerAnonTarget$3        (anonymous object: `val outerAnon = object { ... }`)
 *       VerAnonTarget$3$innerObj$1   (nested anonymous object inside it)
 * </pre>
 *
 * <p>现场读数：对 {@code VerAnonTarget$3} 启动对齐时 {@code level 1: old=0, new=0} 之后就没有 level 2 的输出，
 * 子类被 {@code ANON-NOT-ALIGNED} 移出本批，存量与新建实例都停在旧版本。名字层级应当是
 * {@code suffix = innerObj$1} → level 2；若这里返回 level 1 或整个被准入判据丢掉，层级桶就是空的。</p>
 */
class NestedKotlinShapeAlignTest {

	private static final String REAL_HOST = "nlv/VerAnonTarget";
	private static final String ANON_HOST = REAL_HOST + "$3";
	private static final String CHILD     = ANON_HOST + "$innerObj$1";

	/** kotlinc 形态的匿名类：带 EnclosingMethod 指向宿主，InnerClasses 自项 outer/inner 均为 null。 */
	private static byte[] kotlinAnon(String name, String enclosing, String body) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
		cw.visitOuterClass(enclosing, null, null);
		cw.visitInnerClass(name, null, null, Opcodes.ACC_FINAL | Opcodes.ACC_STATIC);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "v", "()Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitLdcInsn(body);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void theLevelIsDerivedFromTheNameSuffixNotFromTheSegmentCount() {
		assertEquals(1, AnonClassAligner.getHierarchyLevel(REAL_HOST, ANON_HOST),
			"the anonymous object is a direct child of the real class");
		assertEquals(2, AnonClassAligner.getHierarchyLevel(ANON_HOST, CHILD),
			"with VerAnonTarget$3 as the host, $innerObj$1 is level 2 - a level-1-only walk finds nothing");
	}

	@Test
	void theKotlinShapeIsAdmittedForItsOwnHost() {
		assertTrue(AnonClassAligner.isBatchAnonClass(CHILD, kotlinAnon(CHILD, ANON_HOST, "inner-v1")),
			"the nested object must be admitted under the anonymous parent, not only under the real host");
		assertTrue(AnonClassAligner.isAnonymousClass(kotlinAnon(CHILD, ANON_HOST, "inner-v1"), ANON_HOST),
			"admission is attribute-first: EnclosingMethod names the anonymous parent");
	}

	/**
	 * 现场真正卡住的形态：分组时新侧名字已改写为 {@code $3$innerObj$1}，但**字节**里的
	 * {@code EnclosingMethod} 仍写着编译期的 {@code $4}。若不认别名，准入判否 → 宿主层级桶为空 →
	 * {@code level 1: old=0, new=0} → 子类 ANON-NOT-ALIGNED，存量与新建实例都停在旧版本。
	 */
	@Test
	void aChildWhoseBytesStillNameThePreRenameHostIsStillAdmitted() {
		String preRenameHost = REAL_HOST + "$4";
		AnonClassAligner.setEnclosingAliases(Set.of(preRenameHost));
		try {
			assertTrue(AnonClassAligner.isAnonymousClass(kotlinAnon(CHILD, preRenameHost, "inner-v2"), ANON_HOST),
				"the pre-rename enclosing name must be accepted for this host");
		} finally {
			AnonClassAligner.clearEnclosingAliases();
		}
		assertFalse(AnonClassAligner.isAnonymousClass(kotlinAnon(CHILD, preRenameHost, "inner-v2"), ANON_HOST),
			"without the alias the pre-rename name must not be admitted (aliases must not leak between groups)");
	}

	@Test
	void aChildWhoseBytesStillNameThePreRenameHostGetsAlignedNotDropped() {
		String preRenameHost = REAL_HOST + "$4";
		byte[] hostBytes = kotlinAnon(ANON_HOST, REAL_HOST, "outer-v2");
		byte[] oldChild  = kotlinAnon(CHILD, ANON_HOST, "inner-v1");
		// 编译期名字来源于外层顺移：字节内部仍自称 $4$innerObj$1
		byte[] newChild  = kotlinAnon(ANON_HOST + "$innerObj$1", preRenameHost, "inner-v2");

		Map<String, byte[]> oldAnons = new LinkedHashMap<>();
		oldAnons.put(CHILD, oldChild);
		Map<String, byte[]> newAnons = new LinkedHashMap<>();
		newAnons.put(CHILD, newChild);

		AnonClassAligner.setEnclosingAliases(Set.of(preRenameHost));
		AnonClassAligner.Result res;
		try {
			res = AnonClassAligner.align(ANON_HOST, hostBytes, oldAnons, newAnons,
				n -> oldAnons.get(n), n -> newAnons.get(n), n -> false, Set.of());
		} finally {
			AnonClassAligner.clearEnclosingAliases();
		}
		assertNotNull(res.renameMap.get(CHILD),
			"the nested object must be consumed, otherwise the field symptom repeats: ANON-NOT-ALIGNED");
	}

	@Test
	void aHostWhoseOnlyChildSitsAtLevelTwoStillGetsThatChildAligned() {
		byte[] hostBytes = kotlinAnon(ANON_HOST, REAL_HOST, "outer-v2");
		byte[] oldChild  = kotlinAnon(CHILD, ANON_HOST, "inner-v1");
		byte[] newChild  = kotlinAnon(CHILD, ANON_HOST, "inner-v2");

		Map<String, byte[]> oldAnons = new LinkedHashMap<>();
		oldAnons.put(CHILD, oldChild);
		Map<String, byte[]> newAnons = new LinkedHashMap<>();
		newAnons.put(CHILD, newChild);

		AnonClassAligner.Result res = AnonClassAligner.align(
			ANON_HOST, hostBytes, oldAnons, newAnons,
			n -> oldAnons.get(n), n -> newAnons.get(n), n -> false, Set.of());

		String target = res.renameMap.get(CHILD);
		assertNotNull(target,
			"the nested object must be consumed by the alignment; otherwise the caller drops it as ANON-NOT-ALIGNED"
				+ " and both the surviving and fresh instances keep the old body");
		assertTrue(res.alignedAnonClasses.containsKey(target),
			"consumed means its bytes are carried under the resolved target name");
		assertEquals("inner-v2", new String(res.alignedAnonClasses.get(target)).contains("inner-v2")
			? "inner-v2" : "missing", "the aligned bytes must be the new body, not the old one");
	}
}
