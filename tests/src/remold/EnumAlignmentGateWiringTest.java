package remold;

import org.junit.jupiter.api.Test;
import remold.ClassDiffUtil.ClassDiff;
import remold.LayoutGate.Verdict;
import remold.RemoldAgent.LayoutAction;
import remold.RemoldAgent.LayoutDecision;
import remold.RemoldAgent.Liveness;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 枚举顺序门与 Mixin 成员对齐器的接线回归（前线 P0-2 缺陷）。
 *
 * <p>【背景】普通枚举的合成字段 {@code $VALUES} 满足 {@code MixinMemberAlign.hasMixinGeneratedMembers}
 * 里"名字含 {@code $} 且 {@code ACC_SYNTHETIC}"的宽判据，于是 {@code alignMixinGeneratedMembers} 对
 * <b>普通枚举</b>也执行；{@code orderMembers} 在 {@code ClassDiffUtil} 做枚举顺序检查<b>之前</b>把字段表
 * 恢复成基线顺序，{@code shiftedEnumConstants} 恒为空，{@code CHANGED_ENUM_ORDER} 门永远不触发
 * （真机日志：{@code [MIXIN-ALIGN] ... restored the baseline member order} 紧接
 * {@code [DIFF] ... *Modified: [$values, <clinit>]}，没有 "enum constant order changed"）。</p>
 *
 * <p>【本用例的因果链】</p>
 * <ol>
 *   <li>前置基线：对齐器输入就是重排后的新字节（真实产线形态，{@code alignMixinGeneratedMembers}
 *       在布局门之前被调用）；</li>
 *   <li>终局断言：对齐器输出再送 {@code ClassDiffUtil.diff} 必须仍能看到常量顺序漂移，
 *       并且 {@code decideLayout} 必须 REJECT；</li>
 *   <li>对照组：纯方法体修改的枚举，对齐器不得凭空制造差异（不得误伤）。</li>
 * </ol>
 */
class EnumAlignmentGateWiringTest {

	private static final String ENUM_HOST = "test.Color";

	private static final String COLOR_V1 =
		"package test;\n"
		+ "public enum Color {\n"
		+ "    RED, GREEN, BLUE;\n"
		+ "    public String tag() { return \"v1\"; }\n"
		+ "}\n";

	/** 重排：BLUE(0), RED(1), GREEN(2)。 */
	private static final String COLOR_REORDERED =
		"package test;\n"
		+ "public enum Color {\n"
		+ "    BLUE, RED, GREEN;\n"
		+ "    public String tag() { return \"v2\"; }\n"
		+ "}\n";

	/** 对照组：常量集合与顺序都没变，只改方法体。 */
	private static final String COLOR_BODY_ONLY =
		"package test;\n"
		+ "public enum Color {\n"
		+ "    RED, GREEN, BLUE;\n"
		+ "    public String tag() { return \"v2\"; }\n"
		+ "}\n";

	/** 前插：YELLOW 把已有常量的 ordinal 全部推后。 */
	private static final String COLOR_PREPENDED =
		"package test;\n"
		+ "public enum Color {\n"
		+ "    YELLOW, RED, GREEN, BLUE;\n"
		+ "    public String tag() { return \"v2\"; }\n"
		+ "}\n";

	/** 末尾追加：旧常量 ordinal 不变，但 <clinit> 不重跑 → 新常量运行期恒为 null。 */
	private static final String COLOR_APPENDED =
		"package test;\n"
		+ "public enum Color {\n"
		+ "    RED, GREEN, BLUE, PURPLE;\n"
		+ "    public String tag() { return \"v2\"; }\n"
		+ "}\n";

	@Test
	void reorderedEnumConstantsSurviveMixinMemberAlignment() {
		byte[] v1 = compileOne(ENUM_HOST, COLOR_V1);
		byte[] v2 = compileOne(ENUM_HOST, COLOR_REORDERED);

		// 前置基线：对齐器接收到的确实是重排后的字节（不是磁盘旧字节）。
		assertTrue(ClassDiffUtil.diff(v1, v2).enumOrderChanged,
			"precondition: the raw candidate really carries the reordered constants");

		byte[] aligned = alignThroughProductionPath(v1, v2);

		ClassDiff diff = ClassDiffUtil.diff(v1, aligned);
		assertTrue(diff.enumOrderChanged,
			"the enum-order gate must still see the drift after Mixin member alignment;"
				+ " masked changedFields=" + diff.changedFields);
		assertFalse(diff.shiftedEnumConstants.isEmpty(), "shifted constants must be named for the operator");

		LayoutDecision d = RemoldAgent.decideLayout(ENUM_HOST, v1, aligned, "reject", Liveness.LIVE,
			Set.of(ENUM_HOST));
		assertEquals(LayoutAction.REJECT, d.action(),
			"a reordered enum must be rejected before it reaches redefineClasses");
		assertTrue(d.reason() != null && d.reason().contains("enum constant order changed"), d.reason());
	}

	@Test
	void prependedEnumConstantSurvivesMixinMemberAlignment() {
		byte[] v1 = compileOne(ENUM_HOST, COLOR_V1);
		byte[] v2 = compileOne(ENUM_HOST, COLOR_PREPENDED);

		byte[] aligned = alignThroughProductionPath(v1, v2);

		ClassDiff diff = ClassDiffUtil.diff(v1, aligned);
		assertTrue(diff.enumOrderChanged,
			"a prepended constant shifts every existing ordinal and must stay visible to the gate;"
				+ " masked changedFields=" + diff.changedFields);
		assertEquals(LayoutAction.REJECT,
			RemoldAgent.decideLayout(ENUM_HOST, v1, aligned, "reject", Liveness.LIVE, Set.of(ENUM_HOST)).action());
	}

	@Test
	void plainEnumIsNotTreatedAsMixinTarget() {
		byte[] v1 = compileOne(ENUM_HOST, COLOR_V1);
		assertFalse(remold.ref.MixinMemberAlign.hasMixinGeneratedMembers(v1),
			"an ordinary enum's $VALUES/$values are javac/JVM synthesized members, not Mixin-generated ones");
	}

	@Test
	void bodyOnlyEnumChangeIsNotDisturbedByAlignment() {
		byte[] v1 = compileOne(ENUM_HOST, COLOR_V1);
		byte[] v2 = compileOne(ENUM_HOST, COLOR_BODY_ONLY);

		byte[] aligned = alignThroughProductionPath(v1, v2);

		ClassDiff diff = ClassDiffUtil.diff(v1, aligned);
		assertFalse(diff.enumOrderChanged, "a body-only edit must not be turned into an order change");
		assertTrue(diff.changedFields.isEmpty(),
			"field tables must be untouched for a body-only enum edit: " + diff.changedFields);
		assertTrue(diff.modifiedBodyMethods.contains("tag()Ljava/lang/String;"),
			"the edited method must still be detected as a body change: " + diff.modifiedBodyMethods);
		assertEquals(LayoutAction.PASS,
			RemoldAgent.decideLayout(ENUM_HOST, v1, aligned, "reject", Liveness.LIVE, Set.of(ENUM_HOST)).action());
	}

	@Test
	void appendedEnumConstantIsRejectedAfterMixinAlignment() {
		// 追加常量不改变既有 ordinal，顺序门不触发；但 <clinit> 不重跑，PURPLE 恒 null、$VALUES 不扩容。
		// 对齐器（若它因任何原因运行）不得吞掉这条集合变化信号。
		byte[] v1 = compileOne(ENUM_HOST, COLOR_V1);
		byte[] v2 = compileOne(ENUM_HOST, COLOR_APPENDED);

		byte[] aligned = alignThroughProductionPath(v1, v2);

		ClassDiff diff = ClassDiffUtil.diff(v1, aligned);
		assertTrue(diff.addedEnumConstants.contains("PURPLE"),
			"the appended constant must stay visible after member alignment: " + diff.addedEnumConstants);
		LayoutDecision d = RemoldAgent.decideLayout(ENUM_HOST, v1, aligned, "reject", Liveness.LIVE,
			Set.of(ENUM_HOST));
		assertEquals(LayoutAction.REJECT, d.action(),
			"appending an enum constant cannot take effect without re-running <clinit>; it must be rejected");
		assertTrue(d.reason() != null && d.reason().contains("enum constant set changed"), d.reason());
	}

	@Test
	void removedEnumConstantIsNotResurrectedByAlignment() {
		byte[] v1 = compileOne(ENUM_HOST, COLOR_V1);
		byte[] v2 = compileOne(ENUM_HOST,
			"package test;\n"
			+ "public enum Color {\n"
			+ "    RED, GREEN;\n"
			+ "    public String tag() { return \"v2\"; }\n"
			+ "}\n");

		byte[] aligned = alignThroughProductionPath(v1, v2);

		ClassDiff diff = ClassDiffUtil.diff(v1, aligned);
		assertTrue(diff.changedFields.stream().anyMatch(s -> s.contains("BLUE")),
			"a deleted enum constant must stay deleted (no tombstone field resurrection): "
				+ diff.changedFields);
		assertEquals(LayoutAction.REJECT,
			RemoldAgent.decideLayout(ENUM_HOST, diff.changedFields, "reject", Liveness.LIVE,
				Set.of(ENUM_HOST)).action(),
			"removing an enum constant is a field removal with no in-batch reader proof; reject");
	}

	@Test
	void jvmSynthesizedFieldNamesAreNotMixinMarkers() {
		// 内部类外部引用 + javac 断言/switch-map 字段都不是 Mixin 产物。
		assertFalse(remold.ref.MixinMemberAlign.hasMixinGeneratedMembers(
			asmClass("fixture/Inner", "this$0:Lfixture/Outer;", "payload:I")));
		assertFalse(remold.ref.MixinMemberAlign.hasMixinGeneratedMembers(
			asmClass("fixture/WithAssert", "$assertionsDisabled:Z", "$SwitchMap$test$Color:[I",
				"$VALUES:[Lfixture/EnumTarget;")));
		// @Unique 形态（合成 + 含 $）仍然必须被识别为 Mixin 产物。
		assertTrue(remold.ref.MixinMemberAlign.hasMixinGeneratedMembers(
			asmClass("fixture/MixinTarget", "honkai$erosion:D")));
	}

	@Test
	void mixinWovenEnumKeepsCandidateConstantOrderButAlignsMethods() {
		byte[] baseline = asmEnumWithHandler("zce000", new String[]{"RED", "GREEN", "BLUE"},
			new String[]{"tag", "handler$zce000$cfg$foo", "<init>"});
		byte[] candidate = asmEnumWithHandler("zzc000", new String[]{"BLUE", "RED", "GREEN"},
			new String[]{"handler$zzc000$cfg$foo", "tag", "<init>"});

		remold.ref.MixinMemberAlign.Result result = remold.ref.MixinMemberAlign.align(candidate, baseline);

		assertEquals(List.of("BLUE", "RED", "GREEN"), enumConstants(result.bytes),
			"the enum constant field order must stay the candidate order: the order gate reads it");
		assertEquals(List.of("BLUE", "RED", "GREEN"), enumConstants(candidate),
			"precondition: the candidate really is reordered");
		assertTrue(result.renamed > 0, "the Mixin-generated handler must be renamed back to the baseline name");
		assertTrue(hasMethod(result.bytes, "handler$zce000$cfg$foo"),
			"the Mixin-generated handler must carry the loaded baseline name");
		assertFalse(hasMethod(result.bytes, "handler$zzc000$cfg$foo"),
			"the drifted unique-id name must not survive alignment");
	}

	/**
	 * 走产线路径：把旧字节放进 {@code bytecodeCache} 作基线，再调用
	 * {@link RemoldAgent#alignMixinGeneratedMembers}（布局门之前的那次调用）。
	 */
	private static byte[] alignThroughProductionPath(byte[] baseline, byte[] candidate) {
		String key = ENUM_HOST;
		byte[] previous = RemoldAgent.bytecodeCache.get(key);
		RemoldAgent.bytecodeCache.put(key, baseline);
		try {
			return RemoldAgent.alignMixinGeneratedMembers(key, null, candidate);
		} finally {
			if (previous == null) RemoldAgent.bytecodeCache.remove(key);
			else RemoldAgent.bytecodeCache.put(key, previous);
		}
	}

	private static byte[] compileOne(String dotName, String source) {
		Map<String, byte[]> out = compile(Map.of(dotName, source));
		byte[] b = out.get(dotName);
		assertNotNull(b, "编译失败：" + dotName);
		return b;
	}

	// ------------------------------------------------------------------------------------------
	// ASM fixtures: enum ordering kept / JVM-synthesized field shapes / Mixin-woven enum
	// ------------------------------------------------------------------------------------------

	private static final String ASM_ENUM = "fixture/EnumTarget";

	private static byte[] asmClass(String internalName, String... nameDescPairs) {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
		cw.visit(org.objectweb.asm.Opcodes.V17,
			org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
			internalName, null, "java/lang/Object", null);
		for (String pair : nameDescPairs) {
			int colon = pair.indexOf(':');
			cw.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_SYNTHETIC,
				pair.substring(0, colon), pair.substring(colon + 1), null, null).visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** 最小的 Mixin 织入枚举：常量表 + $VALUES + 业务方法 tag() + 一个生成 handler。 */
	private static byte[] asmEnumWithHandler(String uniqueId, String[] constants, String[] methodOrder) {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
		cw.visit(org.objectweb.asm.Opcodes.V17,
			org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL
				| org.objectweb.asm.Opcodes.ACC_ENUM | org.objectweb.asm.Opcodes.ACC_SUPER,
			ASM_ENUM, null, "java/lang/Enum", null);
		for (String c : constants) {
			cw.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC
					| org.objectweb.asm.Opcodes.ACC_FINAL | org.objectweb.asm.Opcodes.ACC_ENUM,
				c, "L" + ASM_ENUM + ";", null, null).visitEnd();
		}
		cw.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_STATIC
				| org.objectweb.asm.Opcodes.ACC_FINAL | org.objectweb.asm.Opcodes.ACC_SYNTHETIC,
			"$VALUES", "[L" + ASM_ENUM + ";", null, null).visitEnd();
		String handlerName = "handler$" + uniqueId + "$cfg$foo";
		for (String m : methodOrder) {
			boolean handler = m.startsWith("handler$");
			String name = handler ? handlerName : m;
			String desc = "<init>".equals(name) ? "(Ljava/lang/String;I)V"
				: "tag".equals(name) ? "()Ljava/lang/String;" : "()V";
			int access;
			if (handler) access = org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
			else if ("<init>".equals(name)) access = org.objectweb.asm.Opcodes.ACC_PRIVATE;
			else access = org.objectweb.asm.Opcodes.ACC_PUBLIC;
			org.objectweb.asm.MethodVisitor mv = cw.visitMethod(access, name, desc, null, null);
			mv.visitCode();
			if ("<init>".equals(name)) {
				mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
				mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
				mv.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 2);
				mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Enum",
					"<init>", "(Ljava/lang/String;I)V", false);
				mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
				mv.visitMaxs(3, 3);
			} else if ("tag".equals(name)) {
				mv.visitLdcInsn("x");
				mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
				mv.visitMaxs(1, 1);
			} else {
				mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
				mv.visitMaxs(0, 1);
			}
			mv.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static List<String> enumConstants(byte[] bytes) {
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
		List<String> out = new ArrayList<>();
		for (org.objectweb.asm.tree.FieldNode f : node.fields) {
			if ((f.access & org.objectweb.asm.Opcodes.ACC_ENUM) != 0) out.add(f.name);
		}
		return out;
	}

	private static boolean hasMethod(byte[] bytes, String name) {
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
		for (org.objectweb.asm.tree.MethodNode m : node.methods) {
			if (m.name.equals(name)) return true;
		}
		return false;
	}

	/** 内存编译工具（与 EnumSwitchReorderGateTest 同形态）。 */
	private static Map<String, byte[]> compile(Map<String, String> sources) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		StandardJavaFileManager std = compiler.getStandardFileManager(null, null, null);
		Map<String, byte[]> out = new LinkedHashMap<>();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

		JavaFileManager memory = new ForwardingJavaFileManager<StandardJavaFileManager>(std) {
			@Override
			public JavaFileObject getJavaFileForOutput(Location location, String className,
			                                           JavaFileObject.Kind kind, FileObject sibling) {
				URI uri = URI.create("mem:///" + className.replace('.', '/') + kind.extension);
				return new SimpleJavaFileObject(uri, kind) {
					@Override
					public OutputStream openOutputStream() {
						return new ByteArrayOutputStream() {
							@Override
							public void close() throws IOException {
								super.close();
								out.put(className, toByteArray());
							}
						};
					}
				};
			}
		};

		List<JavaFileObject> units = new ArrayList<>();
		for (Map.Entry<String, String> e : sources.entrySet()) {
			URI uri = URI.create("string:///" + e.getKey().replace('.', '/') + ".java");
			units.add(new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
				@Override
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
					return e.getValue();
				}
			});
		}

		boolean ok = Boolean.TRUE.equals(compiler.getTask(null, memory, diagnostics,
			List.of("-proc:none", "-nowarn"), null, units).call());
		if (!ok) {
			StringBuilder sb = new StringBuilder("测试夹具编译失败：");
			for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
				sb.append("\n      ").append(d.getKind()).append(' ').append(d.getMessage(null));
			}
			throw new IllegalStateException(sb.toString());
		}
		return out;
	}
}
