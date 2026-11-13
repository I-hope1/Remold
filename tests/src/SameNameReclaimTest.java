import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import remold.LambdaAligner;

/**
 * #4 回归：一个旧简单名只能被一个方法占用（acceptCandidate 必须与 step2PassA/step2 对称地查
 * usedOldNames）。
 *
 * <p>夹具用 javac 产出两条不同 SAM 的 lambda（{@code ()V} 与 {@code (I)V}），再用 ASM 把旧侧
 * 两条合成方法改成**同名异型**（{@code lambda$make$0()V} 与 {@code lambda$make$0(I)V}）。新侧两条
 * 改成互不相同、且都不等于旧名的名字（{@code lambda$make$7}/{@code lambda$make$8}），于是两条
 * 新方法都只能靠"体等价"去抢旧名——第二条若不被 usedOldNames 拦住，就会再次占用
 * {@code lambda$make$0}。</p>
 */
class SameNameReclaimTest {

	private static final String SRC =
		"import java.util.function.*;\n"
		+ "public class SameNameHost {\n"
		+ "  static void act1() {}\n"
		+ "  static void act2(int i) {}\n"
		+ "  static Runnable r1;\n"
		+ "  static IntConsumer r2;\n"
		+ "  static void make() {\n"
		+ "    r1 = () -> act1();\n"
		+ "    r2 = (i) -> act2(i);\n"
		+ "  }\n"
		+ "}\n";

	/** 把类里的合成方法按 map 改名，并同步改写 indy 引导参数里的 Handle。 */
	static byte[] renameLambdas(byte[] bytes, Map<String, String> renames) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		for (MethodNode mn : cn.methods) {
			String to = renames.get(mn.name);
			if (to != null) mn.name = to;
		}
		for (MethodNode mn : cn.methods) {
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
					for (int i = 0; i < indy.bsmArgs.length; i++) {
						if (!(indy.bsmArgs[i] instanceof Handle h)) continue;
						String to = renames.get(h.getName());
						if (to == null || !cn.name.equals(h.getOwner())) continue;
						indy.bsmArgs[i] = new Handle(h.getTag(), h.getOwner(), to, h.getDesc(), h.isInterface());
					}
				} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle h) {
					String to = renames.get(h.getName());
					if (to != null && cn.name.equals(h.getOwner())) {
						ldc.cst = new Handle(h.getTag(), h.getOwner(), to, h.getDesc(), h.isInterface());
					}
				}
			}
		}
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/** 找到体内调用 {@code act2} 的方法（新侧被拒配后应拿避障名，不得再叫 lambda$make$0）。 */
	static MethodNode bodyCalling(ClassNode cn, String callee) {
		for (MethodNode mn : cn.methods) {
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof MethodInsnNode m && m.name.equals(callee)) return mn;
			}
		}
		return null;
	}

	@Test
	void secondNewMethodCannotReclaimUsedOldSimpleName() throws Exception {
		String javac = HstestFixtures.javac(21);
		Path tmp = Files.createTempDirectory("samename");
		Path src = Files.createDirectories(tmp.resolve("src"));
		Path out = Files.createDirectories(tmp.resolve("out"));
		Path file = src.resolve("SameNameHost.java");
		Files.writeString(file, SRC);
		HstestFixtures.compile(javac, out, file.toFile());

		byte[] compiled = Files.readAllBytes(out.resolve("SameNameHost.class"));

		// 旧侧：两条合成方法同名异型（lambda$make$0()V 与 lambda$make$0(I)V）。
		Map<String, String> oldRenames = new LinkedHashMap<>();
		oldRenames.put("lambda$make$0", "lambda$make$0");
		oldRenames.put("lambda$make$1", "lambda$make$0");
		byte[] v1 = renameLambdas(compiled, oldRenames);

		// 新侧：两条都换成与旧名无关的名字，只能靠体等价去抢旧名。
		Map<String, String> newRenames = new LinkedHashMap<>();
		newRenames.put("lambda$make$0", "lambda$make$7");
		newRenames.put("lambda$make$1", "lambda$make$8");
		byte[] v2 = renameLambdas(compiled, newRenames);

		// 前提校验：旧侧确实同名异型。
		ClassNode oldCn = new ClassNode();
		new ClassReader(v1).accept(oldCn, 0);
		int sameName = 0;
		for (MethodNode mn : oldCn.methods) if ("lambda$make$0".equals(mn.name)) sameName++;
		assertTrue(sameName >= 2, "夹具前提：旧侧必须有至少两个 lambda$make$0（实际 " + sameName + "）");

		byte[] aligned = LambdaAligner.align(v1, v2);
		ClassNode alignedCn = new ClassNode();
		new ClassReader(aligned).accept(alignedCn, 0);

		MethodNode act2Body = bodyCalling(alignedCn, "act2");
		assertNotNull(act2Body, "产物里必须还能找到调用 act2 的方法体（新逻辑不许丢）");
		assertFalse("lambda$make$0".equals(act2Body.name),
			"第二条新方法不得再占用已被第一条使用的旧简单名 lambda$make$0（实际: " + act2Body.name
			+ "，方法表: " + alignedCn.methods.stream().map(m -> m.name + m.desc).toList() + "）");
	}
}
