import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import remold.LambdaAligner;

/**
 * #2 回归：捕获桥第二轮搬运时，目标方法的 {@code captured} 可能为 null（它不在任何 indy 上，
 * captured 只在 recordLambdaShape 遇 indy 时赋值）。不得直接解引用，否则 NPE 被 align 捕获后
 * 整类降级为未对齐字节，旧桥键直接消失。
 *
 * <p>夹具：V1 捕获 int、V2 丢弃捕获（第一轮建桥）；V3 把宿主里的 indy 换成 {@code ACONST_NULL}
 * （lambda 方法还在，但不再被 indy 触达 → captured == null），同时旧桥目标与新死方法同名同体
 * 仍会配对。</p>
 */
class BridgeRepointNullCaptureTest {

	private static final String V1 =
		"import java.util.function.Supplier;\n"
		+ "public class BridgeHost {\n"
		+ "  static Supplier<String> s;\n"
		+ "  static void make() { int n = 1; s = () -> \"A\" + n; }\n"
		+ "}\n";

	private static final String V2 =
		"import java.util.function.Supplier;\n"
		+ "public class BridgeHost {\n"
		+ "  static Supplier<String> s;\n"
		+ "  static void make() { s = () -> \"A\"; }\n"
		+ "}\n";

	/** 把 make() 里的 indy 换成 ACONST_NULL（保留 lambda 合成方法本身，只断掉 indy 触达）。 */
	static byte[] cutIndy(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		for (MethodNode mn : cn.methods) {
			if (!"make".equals(mn.name)) continue;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof InvokeDynamicInsnNode) {
					mn.instructions.set(insn, new InsnNode(Opcodes.ACONST_NULL));
					break;
				}
			}
		}
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
	}

	@Test
	void bridgeRepointSurvivesTargetWithNullCaptured() throws Exception {
		String javac = HstestFixtures.javac(21);
		Path tmp = Files.createTempDirectory("bridgenull");
		Path v1out = Files.createDirectories(tmp.resolve("v1"));
		Path v2out = Files.createDirectories(tmp.resolve("v2"));
		Path s1 = Files.createDirectories(tmp.resolve("s1"));
		Path s2 = Files.createDirectories(tmp.resolve("s2"));
		Path f1 = s1.resolve("BridgeHost.java");
		Path f2 = s2.resolve("BridgeHost.java");
		Files.writeString(f1, V1);
		Files.writeString(f2, V2);
		HstestFixtures.compile(javac, v1out, f1.toFile());
		HstestFixtures.compile(javac, v2out, f2.toFile());

		byte[] v1 = Files.readAllBytes(v1out.resolve("BridgeHost.class"));
		byte[] v2 = Files.readAllBytes(v2out.resolve("BridgeHost.class"));

		// 第一轮：捕获缩减建桥，旧键 (I)String 应留下桥体。
		byte[] aligned12 = LambdaAligner.align(v1, v2);
		ClassNode a12 = new ClassNode();
		new ClassReader(aligned12).accept(a12, 0);
		MethodNode bridge = null;
		for (MethodNode mn : a12.methods) {
			if ("lambda$make$0".equals(mn.name) && "(I)Ljava/lang/String;".equals(mn.desc)) bridge = mn;
		}
		assertNotNull(bridge, "前提：第一轮必须为 (I)String 建出捕获桥，实际方法表 "
			+ a12.methods.stream().map(m -> m.name + m.desc).toList());

		// 第二轮：目标的 indy 被切断（captured == null），旧桥键必须仍留下（不得因 NPE 整类降级）。
		byte[] v3 = cutIndy(v2);
		byte[] aligned23 = LambdaAligner.align(aligned12, v3);
		ClassNode a23 = new ClassNode();
		new ClassReader(aligned23).accept(a23, 0);

		boolean oldKeyKept = false;
		for (MethodNode mn : a23.methods) {
			if ("lambda$make$0".equals(mn.name) && "(I)Ljava/lang/String;".equals(mn.desc)) oldKeyKept = true;
		}
		assertTrue(oldKeyKept,
			"目标 captured == null 时只应跳过桥搬运，不得抛 NPE 让整类降级（旧键丢失）；实际方法表 "
			+ a23.methods.stream().map(m -> m.name + m.desc).toList());
	}
}
