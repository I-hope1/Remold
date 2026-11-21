package remold.ref;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import remold.RemoldAgent;

import java.io.InputStream;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 遮蔽新增字段（与祖先同名同描述符）的决策与写入语义。
 *
 * <p>分析期部分锁 F1（可读祖先才采纳）、F3（非遮蔽字段不进表）、F4（不可读 / 静态性不符 /
 * 祖先链不可解析一律 fail-closed 并告警）；运行期部分直接驱动
 * {@link HotswapBridge#bootstrapAncestor} 的调用点，锁 F1（当前值等于祖先值才回写）、
 * F2（既不等于默认值也不等于祖先值必须跳过）。真机路径由 {@code ShadowFieldInitFixProbe} 覆盖。</p>
 */
public class ShadowFieldInitFixTest {

	// ---------------------------------------------------------------- fixtures

	/** 可读的公开实例字段。 */
	public static class Anc {
		public int x = 10;
		public String s = "a";
	}

	/** 遮蔽 {@link Anc#x} / {@link Anc#s}：分析与运行期两条路径的阳性用例。 */
	public static class Shadow extends Anc {
		public int x = 10;
		public String s = "a";
	}

	/** 祖先在祖父层：最近声明类必须是祖父，而不是中间类。 */
	public static class GrandParent {
		public int g = 7;
	}

	public static class Middle extends GrandParent {
	}

	public static class Leaf extends Middle {
		public int g = 7;
	}

	/** 祖先字段 private：不可读，F4 fail-closed。 */
	public static class PrivateAnc {
		private int p = 1;

		public int readP() {
			return p;
		}
	}

	public static class PrivateShadow extends PrivateAnc {
		public int p = 1;
	}

	/** 静态性不符：祖先静态字段无法在实例上取值，F4 fail-closed。 */
	public static class StaticAnc {
		public static int st = 3;
	}

	public static class StaticShadow extends StaticAnc {
		public int st = 3;
	}

	/** 同名异描述符：不是遮蔽（字段名 + 描述符双键），F3 保持原行为。 */
	public static class TypeAnc {
		public int t = 1;
	}

	public static class TypeShadow extends TypeAnc {
		public long t = 1L;
	}

	/** 祖先链上没有同名字段：普通新增字段，F3。 */
	public static class PlainAnc {
		public int other = 1;
	}

	public static class PlainShadow extends PlainAnc {
		public int fresh = 1;
	}

	private RemoldAgent.Logger previousLogger;
	private final List<String> warnings = new ArrayList<>();

	@BeforeEach
	void captureWarnings() {
		previousLogger = RemoldAgent.logger;
		RemoldAgent.logger = new RemoldAgent.Logger() {
			@Override public void log(String msg) { }

			@Override public void info(String msg) { }

			@Override public void warn(String msg) {
				warnings.add(msg);
			}

			@Override public void error(String msg) { }

			@Override public void error(String msg, Throwable t) { }
		};
	}

	@AfterEach
	void restoreLogger() {
		RemoldAgent.logger = previousLogger;
	}

	// ---------------------------------------------------------------- analysis phase

	@Test
	void readableAncestorDeclarationIsAdopted() throws Exception {
		ClassNode preferred = newClassNode(Shadow.class);
		ClassHierarchyOracle oracle = new HierarchyTreeOracle(
			ShadowFieldInitFixTest.class.getClassLoader(), preferred);

		Map<String, String> shadows = InitFix.detectShadowedAddedFields(
			oracle, preferred.name, Set.of("x", "s"), fieldNodes(preferred));

		assertEquals(internalName(Anc.class), shadows.get("x"),
			"an added int field must resolve the nearest readable ancestor declaration");
		assertEquals(internalName(Anc.class), shadows.get("s"),
			"an added reference field resolves the same way");
		assertTrue(warnings.isEmpty(), "a readable shadowing declaration must not warn, was " + warnings);
	}

	@Test
	void nearestDeclarationIsResolvedFromTheAncestorChain() throws Exception {
		ClassNode preferred = newClassNode(Leaf.class);
		ClassHierarchyOracle oracle = new HierarchyTreeOracle(
			ShadowFieldInitFixTest.class.getClassLoader(), preferred);

		Map<String, String> shadows = InitFix.detectShadowedAddedFields(
			oracle, preferred.name, Set.of("g"), fieldNodes(preferred));

		assertEquals(internalName(GrandParent.class), shadows.get("g"),
			"the declaration class must be the nearest ancestor that declares the field, not the subclass");
	}

	@Test
	void privateAncestorFieldFailsClosedWithWarning() throws Exception {
		ClassNode preferred = newShadowClassNode(PrivateShadow.class);
		ClassHierarchyOracle oracle = new HierarchyTreeOracle(
			ShadowFieldInitFixTest.class.getClassLoader(), preferred);

		Map<String, String> shadows = InitFix.detectShadowedAddedFields(
			oracle, preferred.name, Set.of("p"), fieldNodes(preferred));

		assertFalse(shadows.containsKey("p"),
			"a private ancestor field cannot be read, so the field must keep the zero-value conditional write");
		assertTrue(warnings.stream().anyMatch(w -> w.contains("SHADOW-FIELD") && w.contains(".p")),
			"the fail-closed fallback must warn, was " + warnings);
	}

	@Test
	void staticAncestorFieldFailsClosedWithWarning() throws Exception {
		ClassNode preferred = newShadowClassNode(StaticShadow.class);
		ClassHierarchyOracle oracle = new HierarchyTreeOracle(
			ShadowFieldInitFixTest.class.getClassLoader(), preferred);

		Map<String, String> shadows = InitFix.detectShadowedAddedFields(
			oracle, preferred.name, Set.of("st"), fieldNodes(preferred));

		assertFalse(shadows.containsKey("st"),
			"a static ancestor field has no per-instance value, so the field must keep the zero-value conditional write");
		assertTrue(warnings.stream().anyMatch(w -> w.contains("SHADOW-FIELD") && w.contains(".st")),
			"the static mismatch must warn, was " + warnings);
	}

	@Test
	void differentDescriptorIsNotShadowing() throws Exception {
		ClassNode preferred = newShadowClassNode(TypeShadow.class);
		ClassHierarchyOracle oracle = new HierarchyTreeOracle(
			ShadowFieldInitFixTest.class.getClassLoader(), preferred);

		Map<String, String> shadows = InitFix.detectShadowedAddedFields(
			oracle, preferred.name, Set.of("t"), fieldNodes(preferred));

		assertFalse(shadows.containsKey("t"),
			"a same-name different-descriptor field is not a shadowing field in this contract");
		assertTrue(warnings.isEmpty(), "a different descriptor is not a fail-closed case, was " + warnings);
	}

	@Test
	void plainAddedFieldIsNotListed() throws Exception {
		ClassNode preferred = newShadowClassNode(PlainShadow.class);
		ClassHierarchyOracle oracle = new HierarchyTreeOracle(
			ShadowFieldInitFixTest.class.getClassLoader(), preferred);

		Map<String, String> shadows = InitFix.detectShadowedAddedFields(
			oracle, preferred.name, Set.of("fresh"), fieldNodes(preferred));

		assertTrue(shadows.isEmpty(), "a non-shadowing added field keeps the former path");
		assertTrue(warnings.isEmpty(), "a non-shadowing added field must not warn, was " + warnings);
	}

	@Test
	void unresolvableSuperclassChainFailsClosedWithWarning() {
		ClassNode unknown = new ClassNode();
		unknown.version = Opcodes.V17;
		unknown.name = "shadowreq/UnknownChild";
		unknown.superName = "shadowreq/MissingAncestor";
		unknown.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "x", "I", null, null));
		ClassHierarchyOracle oracle = new HierarchyTreeOracle(
			ShadowFieldInitFixTest.class.getClassLoader(), unknown);

		Map<String, String> shadows = InitFix.detectShadowedAddedFields(
			oracle, unknown.name, Set.of("x"), fieldNodes(unknown));

		assertTrue(shadows.isEmpty(),
			"an unreadable superclass chain must not be treated as 'definitely not shadowing'");
		assertTrue(warnings.stream().anyMatch(w -> w.contains("SHADOW-FIELD")),
			"the unprovable case must warn, was " + warnings);
	}

	/**
	 * 顺序无关性：同一批次里中间类也新增了同名字段时，比较基准必须是<b>重定义前</b>层级上的
	 * 那条声明（祖先的旧值），否则中间类先被补丁写上新值后，子类的期望值会随之漂移。
	 *
	 * <p>夹具：{@code Child extends Parent extends Grand}，Grand 一直声明 {@code int x}；
	 * Parent 的批次新版本新增 {@code int x}（DCEVM 复制给 Child 的仍是 Grand.x 的值），
	 * 这里把 Parent 的重定义前字节码登记进运行期基线，批次视图里只放 Parent 的新版本。</p>
	 */
	@Test
	void shadowingAncestorIsResolvedFromThePreRedefineHierarchy() throws Exception {
		String grandName = "shadowreq/Grand";
		String parentName = "shadowreq/Parent";
		String childName = "shadowreq/Child";
		byte[] grandBytes = simpleClass(grandName, "java/lang/Object", new String[]{"x", "I", "1"});
		byte[] parentOldBytes = simpleClass(parentName, grandName, null);
		byte[] parentNewBytes = simpleClass(parentName, grandName, new String[]{"x", "I", "200"});
		byte[] childBytes = simpleClass(childName, parentName, new String[]{"x", "I", "300"});
		ClassNode childNode = new ClassNode();
		new ClassReader(childBytes).accept(childNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

		RemoldAgent.recordRuntimeBytecode(grandName.replace('/', '.'), grandBytes);
		RemoldAgent.recordRuntimeBytecode(parentName.replace('/', '.'), parentOldBytes);
		Map<ClassLoader, Map<String, byte[]>> view = new java.util.IdentityHashMap<>();
		Map<String, byte[]> batchBytes = new java.util.LinkedHashMap<>();
		batchBytes.put(parentName, parentNewBytes);
		batchBytes.put(childName, childBytes);
		view.put(ShadowFieldInitFixTest.class.getClassLoader(), batchBytes);
		InitFix.beginBatchBytecodeView(view);
		try {
			ClassHierarchyOracle oracle = new HierarchyTreeOracle(
				ShadowFieldInitFixTest.class.getClassLoader(), childNode);
			Map<String, String> shadows = InitFix.detectShadowedAddedFields(
				oracle, childName, Set.of("x"), fieldNodes(childNode));
			assertEquals(grandName, shadows.get("x"),
				"the expectation must come from the declaration that existed before the redefine,"
				+ " not from the in-batch declaration added by the intermediate ancestor");
		} finally {
			InitFix.endBatchBytecodeView();
		}
	}

	/** 生成一个只有无参构造器与可选 {@code int} 字段的最小类。 */
	private static byte[] simpleClass(String name, String superName, String[] field) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, superName, null);
		if (field != null) {
			writer.visitField(Opcodes.ACC_PUBLIC, field[0], field[1], null, null).visitEnd();
		}
		var init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	// ---------------------------------------------------------------- emitter wiring

	@Test
	void emitterUsesAncestorBootstrapForShadowedFields() throws Exception {
		ClassNode host = newClassNode(Shadow.class);
		String className = host.name;
		List<AbstractInsnNode> slice = new ArrayList<>(List.of(
			new VarInsnNode(Opcodes.ALOAD, 0),
			new LdcInsnNode(200),
			new FieldInsnNode(Opcodes.PUTFIELD, className, "x", "I")));

		List<AbstractInsnNode> rewritten = InitFixPatchEmitter.rewriteFieldSlice(
			className, className, slice, Map.of(), Set.of("x"), Set.of(), Map.of(),
			Map.of("x", internalName(Anc.class)));

		InvokeDynamicInsnNode indy = onlyIndy(rewritten);
		assertEquals("bootstrapAncestor", indy.bsm.getName(),
			"a shadowed field must be written through the ancestor-expected bootstrap");
		assertEquals(HotswapBridge.KIND_CONDITIONAL_ANCESTOR, indy.bsmArgs[0]);
		assertEquals(Type.getObjectType(internalName(Anc.class)), indy.bsmArgs[4],
			"the bootstrap must carry the ancestor declaration class resolved at analysis time");
		assertEquals("(L" + className + ";I)V", indy.desc, "the call-site shape is unchanged");
	}

	@Test
	void emitterKeepsZeroValueConditionalWriteForNonShadowedFields() throws Exception {
		ClassNode host = newClassNode(PlainShadow.class);
		String className = host.name;
		List<AbstractInsnNode> slice = new ArrayList<>(List.of(
			new VarInsnNode(Opcodes.ALOAD, 0),
			new LdcInsnNode(41),
			new FieldInsnNode(Opcodes.PUTFIELD, className, "fresh", "I")));

		List<AbstractInsnNode> rewritten = InitFixPatchEmitter.rewriteFieldSlice(
			className, className, slice, Map.of(), Set.of("fresh"), Set.of(), Map.of(), Map.of());

		InvokeDynamicInsnNode indy = onlyIndy(rewritten);
		assertEquals("bootstrap", indy.bsm.getName());
		assertEquals(HotswapBridge.KIND_CONDITIONAL, indy.bsmArgs[0],
			"non-shadowed added fields keep the zero-value conditional CAS");
	}

	// ---------------------------------------------------------------- runtime call site

	/**
	 * F1：槽位当前值等于同实例上祖先字段的值（DCEVM 迁移复制的结果）时必须回写。
	 * 计数器先清零再链接：链接本身会建立零计数条目，先 drain 会把它摘掉并与后续自增脱钩。
	 */
	@Test
	void callSiteWritesWhenTheSlotEqualsTheAncestorValue() throws Throwable {
		HotswapBridge.drainConditionalStats();
		Shadow shadow = new Shadow();
		shadow.x = 10;

		MethodHandle writer = ancestorWriter("x", int.class);
		writer.invoke(shadow, 200);

		assertEquals(200, shadow.x, "the migrated slot value must be replaced by the new initializer");
		long[] stats = HotswapBridge.drainConditionalStats().get(Shadow.class.getName() + "#x");
		assertTrue(stats != null && stats[0] == 1 && stats[1] == 0,
			"the write must be counted as written, was " + (stats == null ? "absent" : stats[0] + "/" + stats[1]));
	}

	/** F2：既不等于默认值也不等于祖先值（真被写过 / 来源不明）必须跳过并计入 skipped。 */
	@Test
	void callSiteSkipsWhenTheSlotWasReallyWritten() throws Throwable {
		HotswapBridge.drainConditionalStats();
		Shadow shadow = new Shadow();
		shadow.x = 777;

		MethodHandle writer = ancestorWriter("x", int.class);
		writer.invoke(shadow, 200);

		assertEquals(777, shadow.x, "a value that is neither the default nor the ancestor value must not be overwritten");
		long[] stats = HotswapBridge.drainConditionalStats().get(Shadow.class.getName() + "#x");
		assertTrue(stats != null && stats[0] == 0 && stats[1] == 1,
			"the skip must be counted so it can be reported, was "
			+ (stats == null ? "absent" : stats[0] + "/" + stats[1]));
	}

	/** F2 边界：默认零值也不等于祖先值，同样不得被当成"合法初始状态"。 */
	@Test
	void callSiteSkipsWhenTheSlotIsTheTypeDefault() throws Throwable {
		Shadow shadow = new Shadow();
		shadow.x = 0;

		MethodHandle writer = ancestorWriter("x", int.class);
		writer.invoke(shadow, 200);

		assertEquals(0, shadow.x,
			"the shadowing contract deliberately narrows the expected value to the ancestor value");
	}

	/** 引用字段同一契约：等于祖先引用才写，真被写过就保留。 */
	@Test
	void callSiteHandlesReferenceFields() throws Throwable {
		Shadow shadow = new Shadow();
		shadow.s = "a";

		MethodHandle writer = ancestorWriter("s", String.class);
		writer.invoke(shadow, "fresh");
		assertEquals("fresh", shadow.s, "a reference shadow field with the inherited value must be rewritten");

		shadow.s = "written-by-user";
		writer.invoke(shadow, "fresh");
		assertEquals("written-by-user", shadow.s, "a genuinely written reference value must be kept");
	}

	/** F4 运行期复核：祖先字段 private 时链接失败（单字段隔离），不退化成猜测。 */
	@Test
	void callSiteFailsClosedWhenTheAncestorFieldIsNotReadable() {
		Throwable failure = assertThrows(Throwable.class, () -> HotswapBridge.bootstrapAncestor(
			MethodHandles.lookup(), "p", MethodType.methodType(void.class, PrivateShadow.class, int.class),
			HotswapBridge.KIND_CONDITIONAL_ANCESTOR, Opcodes.PUTFIELD,
			PrivateShadow.class, PrivateShadow.class, PrivateAnc.class));
		assertTrue(failure instanceof java.lang.BootstrapMethodError,
			"an unreadable ancestor field must fail the call site, not silently fall back to a guess");
		assertTrue(failure.getCause() != null && failure.getCause().toString().contains("private"),
			"the failure must name the readability problem, was " + failure.getCause());
	}

	/** F4 运行期复核：祖先字段静态性与实例槽位不符时链接失败。 */
	@Test
	void callSiteFailsClosedWhenTheAncestorFieldIsStatic() {
		Throwable failure = assertThrows(Throwable.class, () -> HotswapBridge.bootstrapAncestor(
			MethodHandles.lookup(), "st", MethodType.methodType(void.class, StaticShadow.class, int.class),
			HotswapBridge.KIND_CONDITIONAL_ANCESTOR, Opcodes.PUTFIELD,
			StaticShadow.class, StaticShadow.class, StaticAnc.class));
		assertTrue(failure instanceof java.lang.BootstrapMethodError,
			"a static ancestor field must fail the call site, not silently fall back to a guess");
		assertTrue(failure.getCause() != null && failure.getCause().toString().contains("static"),
			"the failure must name the static mismatch, was " + failure.getCause());
	}

	// ---------------------------------------------------------------- helpers

	private MethodHandle ancestorWriter(String field, Class<?> valueType) throws Throwable {
		MethodType type = MethodType.methodType(void.class, Shadow.class, valueType);
		CallSite site = HotswapBridge.bootstrapAncestor(
			MethodHandles.lookup(), field, type, HotswapBridge.KIND_CONDITIONAL_ANCESTOR,
			Opcodes.PUTFIELD, Shadow.class, Shadow.class, Anc.class);
		return site.dynamicInvoker();
	}

	private static InvokeDynamicInsnNode onlyIndy(List<AbstractInsnNode> insns) {
		InvokeDynamicInsnNode found = null;
		for (AbstractInsnNode insn : insns) {
			if (insn instanceof InvokeDynamicInsnNode indy) {
				assertNull(found, "exactly one bridge call site is expected");
				found = indy;
			}
		}
		assertTrue(found != null, "the rewritten slice must carry a bridge call site");
		return found;
	}

	private static ClassNode newClassNode(Class<?> type) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes(type)).accept(node,
			ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return node;
	}

	/** 解析测试夹具的新版本字节码，作为 {@link HierarchyTreeOracle} 的 preferred 节点。 */
	private static ClassNode newShadowClassNode(Class<?> type) throws Exception {
		return newClassNode(type);
	}

	private static Map<String, FieldNode> fieldNodes(ClassNode node) {
		Map<String, FieldNode> nodes = new HashMap<>();
		for (FieldNode field : node.fields) nodes.put(field.name, field);
		return nodes;
	}

	private static String internalName(Class<?> type) {
		return type.getName().replace('.', '/');
	}

	private static byte[] classBytes(Class<?> type) throws Exception {
		String resource = "/" + type.getName().replace('.', '/') + ".class";
		try (InputStream in = type.getResourceAsStream(resource)) {
			if (in == null) throw new AssertionError("class bytes unavailable: " + resource);
			return in.readAllBytes();
		}
	}
}
