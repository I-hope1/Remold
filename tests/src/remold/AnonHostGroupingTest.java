package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次分组的宿主归属：kotlinc 形态必须归到真实宿主名下，而不是"剥尾部数字"得到的假宿主。
 *
 * <p>夹具按 kotlinc 2.3.0 实测形态合成（匿名类带 {@code EnclosingMethod}，{@code InnerClasses} 自身条目
 * outer/inner 皆 null）。真实字节上的同一断言由真机探针覆盖。</p>
 */
class AnonHostGroupingTest {

	private static final String HOST = "example/KtAnonTarget";

	private static byte[] kotlinAnon(String name, String enclosing) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
			name, null, "java/lang/Object", null);
		cw.visitOuterClass(enclosing, null, null);
		cw.visitInnerClass(name, null, null, Opcodes.ACC_FINAL | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] enumConstantBody(String name, String enclosing) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_ENUM | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
			name, null, enclosing, null);
		cw.visitOuterClass(enclosing, null, null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void kotlinNamedAnonymousObjectsGroupUnderTheirRealHost() {
		Map<String, byte[]> batch = new LinkedHashMap<>();
		for (String cls : new String[]{
			HOST + "$propAnonA$1", HOST + "$anonStruct$1", HOST + "$fnObjAnon$1",
			HOST + "$makeUnnamedAnons$1", HOST + "$makeUnnamedAnons$2",
			HOST + "$makeLocalNamedAnons$first$1"}) {
			batch.put(cls, kotlinAnon(cls, HOST));
		}

		Map<String, Map<String, byte[]>> groups = AnonClassAligner.groupBatchAnons(batch);

		assertEquals(1, groups.size(), "six Kotlin anonymous objects share one real host");
		assertTrue(groups.containsKey(HOST), "the group key must be the enclosing class, not a phantom name");
		assertEquals(6, groups.get(HOST).size());
		assertFalse(groups.containsKey(HOST + "$propAnonA"),
			"the phantom host derived from the name must not appear");
	}

	@Test
	void nestedAnonymousObjectGroupsUnderTheOuterAnonymousClass() {
		String outer = HOST + "$outerAnon$1";
		String inner = outer + "$innerObj$1";
		Map<String, byte[]> batch = new LinkedHashMap<>();
		batch.put(outer, kotlinAnon(outer, HOST));
		batch.put(inner, kotlinAnon(inner, outer));

		Map<String, Map<String, byte[]>> groups = AnonClassAligner.groupBatchAnons(batch);

		assertTrue(groups.containsKey(outer), "the nested object's host is its enclosing anonymous class");
		assertTrue(groups.containsKey(HOST));
		assertEquals(2, groups.size());
	}

	@Test
	void enumConstantBodiesAreNotGroupedAsAnonymousClasses() {
		String body = HOST + "$Choice$1";
		Map<String, byte[]> batch = new LinkedHashMap<>();
		batch.put(body, enumConstantBody(body, HOST + "$Choice"));

		Map<String, Map<String, byte[]>> groups = AnonClassAligner.groupBatchAnons(batch);

		assertTrue(groups.isEmpty(),
			"enum constant bodies have their own routing path; grouping them would strand them on an old body");
	}

	@Test
	void plainJavaNumberingStillGroupsUnderTheHost() {
		Map<String, byte[]> batch = new LinkedHashMap<>();
		for (int i = 1; i <= 3; i++) {
			String cls = HOST + "$" + i;
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
				cls, null, "java/lang/Object", null);
			cw.visitInnerClass(cls, null, null, Opcodes.ACC_FINAL | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
			cw.visitEnd();
			batch.put(cls, cw.toByteArray());
		}

		Map<String, Map<String, byte[]>> groups = AnonClassAligner.groupBatchAnons(batch);

		assertEquals(1, groups.size());
		assertTrue(groups.containsKey(HOST));
		assertEquals(3, groups.get(HOST).size());
	}
}
