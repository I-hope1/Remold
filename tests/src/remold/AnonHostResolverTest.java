package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnonHostResolver} 的契约测试。
 *
 * <p>夹具按 kotlinc 2.3.0 的实测字节形态合成（读数为：匿名类带 {@code EnclosingMethod} 指向真实外层类，
 * {@code InnerClasses} 条目的 {@code outerName}/{@code innerName} 均为 {@code null}、带 {@code ACC_SYNTHETIC}）：
 * 宿主必须来自属性，名字剥离尾部数字只能作回退。</p>
 */
class AnonHostResolverTest {

	private static final String HOST = "example/KtAnonTarget";

	/** 合成一个 kotlinc 形态的匿名类：EnclosingMethod 指向 enclosing，InnerClasses 自身条目 outer/inner 皆 null。 */
	private static byte[] kotlinAnon(String name, String enclosing) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
			name, null, "java/lang/Object", null);
		cw.visitOuterClass(enclosing, null, null);
		cw.visitInnerClass(name, null, null, Opcodes.ACC_FINAL | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** 合成一个普通类（宿主、接口等），不带匿名标记。 */
	private static byte[] plainClass(String name) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** 合成 javac 形态的匿名类：只有名字里的数字链，没有 EnclosingMethod（旧路径必须照旧可用）。 */
	private static byte[] javacAnonNoEnclosing(String name) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
			name, null, "java/lang/Object", null);
		cw.visitInnerClass(name, null, null, Opcodes.ACC_FINAL | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void propertyAnonymousObjectTakesItsRealHostFromTheEnclosingMethod() {
		String cls = HOST + "$propAnonA$1";
		AnonHostResolver.Identity id = AnonHostResolver.resolve(cls, kotlinAnon(cls, HOST));

		assertEquals(HOST, id.host(), "host must come from EnclosingMethod, not from stripping the name");
		assertTrue(id.anonymous(), "kotlinc anonymous classes carry the anonymous marker");
		assertTrue(id.byAttribute(), "the host must be attribute-derived");
		assertEquals("propAnonA", id.suffixPath());
		assertEquals(1, id.index());
	}

	@Test
	void methodLocalAnonymousObjectsShareTheMethodNameAndDifferByIndex() {
		byte[] b1 = kotlinAnon(HOST + "$makeUnnamedAnons$1", HOST);
		byte[] b2 = kotlinAnon(HOST + "$makeUnnamedAnons$3", HOST);
		AnonHostResolver.Identity i1 = AnonHostResolver.resolve(HOST + "$makeUnnamedAnons$1", b1);
		AnonHostResolver.Identity i3 = AnonHostResolver.resolve(HOST + "$makeUnnamedAnons$3", b2);

		assertEquals(HOST, i1.host());
		assertEquals(HOST, i3.host());
		assertEquals(i1.suffixPath(), i3.suffixPath(), "same declaration site");
		assertEquals(1, i1.index());
		assertEquals(3, i3.index());
	}

	@Test
	void nestedAnonymousObjectHangsUnderTheOuterAnonymousClass() {
		String outer = HOST + "$outerAnon$1";
		String inner = outer + "$innerObj$1";
		AnonHostResolver.Identity id = AnonHostResolver.resolve(inner, kotlinAnon(inner, outer));

		assertEquals(outer, id.host(), "a nested anonymous object is hosted by the enclosing anonymous class");
		assertTrue(id.anonymous());
		assertEquals("innerObj", id.suffixPath());
	}

	@Test
	void aRenamedLocalVariableKeepsTheHostAndIndexButChangesTheSuffixPath() {
		String oldName = HOST + "$makeLocalNamedAnons$first$1";
		String newName = HOST + "$makeLocalNamedAnons$renamedFirst$1";
		AnonHostResolver.Identity o = AnonHostResolver.resolve(oldName, kotlinAnon(oldName, HOST));
		AnonHostResolver.Identity n = AnonHostResolver.resolve(newName, kotlinAnon(newName, HOST));

		assertEquals(o.host(), n.host(), "renaming a local variable must not change the host");
		assertEquals(o.index(), n.index(), "the index is the declaration order, not the variable name");
		assertNotEquals(o.pairKey(), n.pairKey(),
			"the pair key reflects the name; pairing must fall back to host+index when it differs");
	}

	@Test
	void plainClassesAreNotAnonymous() {
		AnonHostResolver.Identity host = AnonHostResolver.resolve(HOST, plainClass(HOST));
		assertFalse(host.anonymous());
		assertEquals(HOST, host.host());
		assertEquals("", host.suffixPath());
		assertEquals(-1, host.index());
	}

	@Test
	void theTextFallbackStillWorksWhenNoBytecodeIsAvailable() {
		AnonHostResolver.Identity id = AnonHostResolver.resolve(HOST + "$1", null);
		assertEquals(HOST, id.host(), "plain Java numbering keeps the historical text derivation");
		assertTrue(id.anonymous(), "without bytecode the name-based digit chain is the only evidence");
		assertFalse(id.byAttribute());
		assertEquals(1, id.index());
	}

	@Test
	void theTextFallbackMisreadsKotlinNamesAndThatIsWhyAttributesWin() {
		String cls = HOST + "$propAnonA$1";
		AnonHostResolver.Identity textOnly = AnonHostResolver.resolve(cls, null);
		AnonHostResolver.Identity fromBytes = AnonHostResolver.resolve(cls, kotlinAnon(cls, HOST));

		assertEquals(HOST + "$propAnonA", textOnly.host(),
			"text fallback invents a host that never existed - the defect this class removes");
		assertEquals(HOST, fromBytes.host());
		assertNotEquals(textOnly.host(), fromBytes.host());
	}

	@Test
	void familyMembershipFollowsTheAttributesAndRejectsPhantomHosts() {
		String cls = HOST + "$propAnonA$1";
		byte[] bytes = kotlinAnon(cls, HOST);

		assertTrue(AnonHostResolver.isFamilyMember(HOST, cls, bytes),
			"a named Kotlin anonymous object belongs to its real host");
		assertFalse(AnonHostResolver.isFamilyMember(HOST + "$propAnonA", cls, bytes),
			"the phantom host owns nothing");
		assertFalse(AnonHostResolver.isFamilyMember(HOST, HOST, bytes), "a class is not its own family member");
	}

	@Test
	void aNamedInnerClassIsNotAFamilyMember() {
		String inner = HOST + "$Helper";
		AnonHostResolver.Identity id = AnonHostResolver.resolve(inner, plainClass(inner));
		assertFalse(id.anonymous(), "Host$Helper is a named inner class, not an anonymous one");
		assertFalse(AnonHostResolver.isFamilyMember(HOST, inner, plainClass(inner)));
	}

	@Test
	void javacAnonymousClassesWithoutEnclosingMethodStillCountAsFamilyMembers() {
		String cls = HOST + "$1";
		byte[] bytes = javacAnonNoEnclosing(cls);
		assertTrue(AnonHostResolver.isFamilyMember(HOST, cls, bytes));
		assertTrue(AnonHostResolver.resolve(cls, bytes).anonymous());
	}

	/** 合成带 EnclosingMethod 且 InnerClasses.innerName 非空的具名局部类（kotlinc/javac 真实形态）。 */
	private static byte[] namedLocalClass(String name, String enclosing, String simpleName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
			name, null, "java/lang/Object", null);
		cw.visitOuterClass(enclosing, "makeLocalAnon", "()V");
		cw.visitInnerClass(name, null, simpleName, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void kotlinNamedLocalClassWithEnclosingMethodIsNotAnonymous() {
		String dummy = HOST + "$makeLocalAnon$DummyBox";
		String box1 = HOST + "$makeLocalAnon$Box1";
		byte[] dummyBytes = namedLocalClass(dummy, HOST, "DummyBox");
		byte[] box1Bytes = namedLocalClass(box1, HOST, "Box1");

		assertFalse(AnonHostResolver.resolve(dummy, dummyBytes).anonymous(),
			"Kotlin named local class carries non-null innerName and must not be classified as anonymous");
		assertFalse(AnonClassAligner.isBatchAnonClass(dummy, dummyBytes));
		assertFalse(AnonClassAligner.isAnonymousClass(dummyBytes, HOST));
		assertFalse(AnonHostResolver.isFamilyMember(HOST, dummy, dummyBytes));

		assertFalse(AnonHostResolver.resolve(box1, box1Bytes).anonymous(),
			"Named local class whose simple name ends in a digit must not be classified as anonymous");
		assertFalse(AnonHostResolver.isFamilyMember(HOST, box1, box1Bytes));
		assertFalse(AnonHostResolver.isAnonymousName(box1, HOST));
		assertFalse(AnonClassAligner.isHostSuffixFamilyMember(HOST, box1));
	}

	@Test
	void javacNamedLocalClassWithEnclosingMethodIsNotAnonymous() {
		String local = HOST + "$1Local";
		byte[] bytes = namedLocalClass(local, HOST, "Local");

		assertFalse(AnonHostResolver.resolve(local, bytes).anonymous());
		assertFalse(AnonClassAligner.isBatchAnonClass(local, bytes));
		assertFalse(AnonClassAligner.isAnonymousClass(bytes, HOST));
	}

	@Test
	void anonymousInsideKotlinLocalClassGroupsUnderLocalClassWhileLocalClassesStayExcluded() {
		String localBox = HOST + "$makeLocalAnon$LocalBox";
		String dummyBox = HOST + "$makeLocalAnon$DummyBox";
		String innerAnon = localBox + "$op$1";

		java.util.Map<String, byte[]> batch = new java.util.LinkedHashMap<>();
		batch.put(HOST, plainClass(HOST));
		batch.put(dummyBox, namedLocalClass(dummyBox, HOST, "DummyBox"));
		batch.put(localBox, namedLocalClass(localBox, HOST, "LocalBox"));
		batch.put(innerAnon, kotlinAnon(innerAnon, localBox));

		java.util.Map<String, java.util.Map<String, byte[]>> grouped = AnonClassAligner.groupBatchAnons(batch);
		assertFalse(grouped.containsKey(HOST),
			"named local classes DummyBox/LocalBox must not be grouped as anonymous classes under host: " + grouped);
		assertTrue(grouped.containsKey(localBox),
			"anonymous object inside LocalBox must be grouped under LocalBox: " + grouped);
		assertEquals(java.util.Set.of(innerAnon), grouped.get(localBox).keySet());

		// Even if DummyBox is passed directly into align() alongside a real anon, it must never be aligned or renamed to $1.
		String realAnon = HOST + "$makeLocalAnon$1";
		java.util.Map<String, byte[]> oldAnons = java.util.Map.of(realAnon, kotlinAnon(realAnon, HOST));
		java.util.Map<String, byte[]> newAnons = new java.util.LinkedHashMap<>();
		newAnons.put(dummyBox, namedLocalClass(dummyBox, HOST, "DummyBox"));
		newAnons.put(realAnon, kotlinAnon(realAnon, HOST));
		AnonClassAligner.Result res = AnonClassAligner.align(HOST, plainClass(HOST), oldAnons, newAnons);
		assertFalse(res.renameMap.containsKey(dummyBox),
			"named local class must never be renamed by AnonClassAligner: " + res.renameMap);
		assertEquals(realAnon, res.renameMap.get(realAnon));
	}
}
