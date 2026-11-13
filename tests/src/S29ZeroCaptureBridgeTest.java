import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import remold.LambdaAligner;

/**
 * S29 回归：旧 lambda 有 2 个同类型捕获（无 -g，类型映射歧义），新类里同宿主同 SAM 有
 * 一个 0 非接收者捕获的"死分支"（{@code () -> ""}）和一个同样板纹的兄弟（映射失败）。
 * 不得因为"只有死分支能过 planBridge"（hits == 1）就把旧实例桥到死分支；应放弃建桥、保留旧体。
 */
class S29ZeroCaptureBridgeTest {

	private static final String V1 = """
		import java.util.function.Supplier;
		public class S29Host {
		    public static Supplier<String> s;
		    public static Supplier<String> a;
		    static void make(String x, String y) {
		        a = () -> "a";
		        s = () -> x + y;
		    }
		}
		""";

	private static final String V2 = """
		import java.util.function.Supplier;
		public class S29Host {
		    public static Supplier<String> s;
		    public static Supplier<String> dead;
		    public static Supplier<String> sib;
		    public static Supplier<String> a;
		    static void make(String x, int y) {
		        dead = () -> "";
		        sib = () -> String.valueOf(x.compareTo(String.valueOf(y)));
		        a = () -> "a";
		    }
		}
		""";

	@Test
	void zeroCaptureDeadBranchIsNotBridgedForAmbiguousCaptures() throws Exception {
		String javac = HstestFixtures.javac(21);
		Path tmp = Files.createTempDirectory("s29");
		Path o1 = Files.createDirectories(tmp.resolve("v1"));
		Path o2 = Files.createDirectories(tmp.resolve("v2"));
		Path s1 = Files.createDirectories(tmp.resolve("s1"));
		Path s2 = Files.createDirectories(tmp.resolve("s2"));
		Path f1 = s1.resolve("S29Host.java");
		Path f2 = s2.resolve("S29Host.java");
		Files.writeString(f1, V1);
		Files.writeString(f2, V2);
		HstestFixtures.compile(javac, o1, f1.toFile());
		HstestFixtures.compile(javac, o2, f2.toFile());

		byte[] aligned = LambdaAligner.align(
			Files.readAllBytes(o1.resolve("S29Host.class")),
			Files.readAllBytes(o2.resolve("S29Host.class")));

		ClassNode out = new ClassNode();
		new ClassReader(aligned).accept(out, 0);

		// 旧体是 (String,String)String 的字符串拼接；找所有该描述符的方法，逐个检查。
		boolean sawOldBody = false;
		for (MethodNode mn : out.methods) {
			if (!"(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".equals(mn.desc)) continue;
			boolean concat = false;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof InvokeDynamicInsnNode i) concat = true;
				if (insn instanceof MethodInsnNode m && m.owner.equals(out.name) && "()Ljava/lang/String;".equals(m.desc)) {
					assertFalse(true, "旧槽位（" + mn.name + mn.desc + "）不得桥接到 0 捕获死分支（实际调用 "
						+ m.name + m.desc + "）");
				}
			}
			if (concat) sawOldBody = true;
		}
		assertTrue(sawOldBody, "旧 lambda 的拼接体必须仍在产物里（保留旧体），实际方法表 "
			+ out.methods.stream().map(m -> m.name + m.desc).toList());
	}
}
