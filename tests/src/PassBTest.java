import remold.LambdaAligner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pass B 兜底对齐断言（JUnit 化）。
 *
 * <p>场景：v1 的叶子 {@code () -> act1()} 在 v2 里被新 lambda 包裹一层并改成
 * {@code () -> act2()}，于是它的 upDepth 从 0 变成 1。Pass A 要求深度相等，会跳过它；
 * Pass B 放宽条件，把这个新叶子兜底配回旧叶子的槽位。</p>
 *
 * <p><b>判据分两层</b>：{@code LAST_STATS} 只能说明"走了哪条路"，产物字节码才说明
 * "配对结果对不对"。所以除了计数器，还必须断言终局形态——老 callsite 的名字仍在、
 * 仍是活方法、且执行的是新语义。</p>
 *
 * <p><b>已知限制（务必别误读覆盖范围）</b>：javac 的 lambda 编号是后序的（容器先被包住、
 * 叶子最后编号），所以 v2 的新叶子本来就落在旧叶子的槽位 {@code lambda$build$0} 上。
 * 实测本夹具的对齐产物与 v2 <b>逐字节相同</b>，Pass B 记到的是一次"同名 → 同名"的恒等配对
 * （{@code passBPairs++}，但 renameMap 为空）。因此下面的终局断言只能钉住"终局形态正确"
 * （错误改名会红），<b>不能</b>证明"Pass B 真的完成了一次改名"——要覆盖后者，需要另建
 * "新叶子落在不同槽位"的夹具。</p>
 */
public class PassBTest {

	@Test
	void passBCounterIsReachable(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		File src1 = findFile("passB/v1/testPassB/PassBCase.java");
		File src2 = findFile("passB/v2/testPassB/PassBCase.java");

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.getAbsolutePath());

		byte[] v1 = Files.readAllBytes(outV1.resolve("testPassB/PassBCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("testPassB/PassBCase.class"));

		LambdaAligner.LAST_STATS = null;
		byte[] aligned = LambdaAligner.align(v1, v2);

		assertNotNull(aligned, "aligned 产物非空");
		assertNotNull(LambdaAligner.LAST_STATS, "LAST_STATS 已记录");
		assertTrue(LambdaAligner.LAST_STATS.passBPairs > 0,
			"passB 计数器成功记录到非零配对: " + LambdaAligner.LAST_STATS);
		assertEquals(0, LambdaAligner.LAST_STATS.passAPairs,
			"passA 跳过深度不匹配候选 (passA == 0): " + LambdaAligner.LAST_STATS);

		// —— 以下才是终局断言：计数器只说明走了哪条路，产物字节码才说明配得对不对 ——
		ClassNode out = parse(aligned);

		// 1. 老 callsite 的名字必须仍然对应一个活方法：既没消失（NoSuchMethodError），
		//    也没被降级成 onOrphanInvoked 熔断桩（老代码一调用就炸）。
		MethodNode oldLeafSlot = method(out, "lambda$build$0", "()V");
		assertNotNull(oldLeafSlot,
			"老 callsite lambda$build$0()V 必须存在于产物中（实际方法表: " + table(out) + "）");
		assertFalse(calls(oldLeafSlot, "onOrphanInvoked"),
			"lambda$build$0()V 必须保持活方法，不能退化成孤儿熔断桩");

		// 2. 老 callsite 现在执行的必须是新语义 act2()，而不是旧的 act1()：
		//    名字保住但方法体停在旧版本，等于热更静默失效。
		assertTrue(calls(oldLeafSlot, "act2"),
			"老 callsite 必须执行新叶子语义 act2()（实际方法表: " + table(out) + "）");
		assertFalse(calls(oldLeafSlot, "act1"),
			"老 callsite 不得仍绑在已删除的 act1() 上");

		// 3. 新链完整可达：build() -> 容器 -> 叶子。容器若成了幽灵，新代码一跑就熔断。
		MethodNode container = method(out, firstIndyTarget(out, "build"), "()V");
		assertNotNull(container,
			"build() 的 indy 目标必须在产物中存在（实际方法表: " + table(out) + "）");
		assertFalse(calls(container, "onOrphanInvoked"), "build() 指向的容器不能是孤儿熔断桩");
		assertEquals("lambda$build$0", firstIndyTarget(out, container.name),
			"容器必须仍指向叶子 lambda$build$0（实际: " + firstIndyTarget(out, container.name) + "）");

		// 4. 没有方法被静默丢弃：本轮所有旧方法都有归宿，不该产生幽灵。
		assertEquals(0, ghostCount(out), "本轮不该产生幽灵（实际方法表: " + table(out) + "）");

		// 5. 产物自洽：不得出现重复的 name+desc（否则同名两版里必有一个是死的）。
		assertEquals(keySet(out).size(), out.methods.size(),
			"产物方法名+描述符必须唯一（实际方法表: " + table(out) + "）");
	}

	static ClassNode parse(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		return cn;
	}

	static MethodNode method(ClassNode cn, String name, String desc) {
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
		}
		return null;
	}

	/** 方法体内是否调用了同名方法（幽灵判定沿用本项目的 onOrphanInvoked 约定）。 */
	static boolean calls(MethodNode mn, String name) {
		if (mn == null || mn.instructions == null) return false;
		for (AbstractInsnNode insn : mn.instructions) {
			if (insn instanceof MethodInsnNode mi && mi.name.equals(name)) return true;
		}
		return false;
	}

	/** 指定方法里第一个指向本类合成方法的 invokedynamic 目标名。 */
	static String firstIndyTarget(ClassNode cn, String methodName) {
		for (MethodNode mn : cn.methods) {
			if (!mn.name.equals(methodName)) continue;
			for (AbstractInsnNode insn : mn.instructions) {
				if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs == null) continue;
				for (Object arg : indy.bsmArgs) {
					if (arg instanceof Handle h && cn.name.equals(h.getOwner())) return h.getName();
				}
			}
		}
		return null;
	}

	static int ghostCount(ClassNode cn) {
		int n = 0;
		for (MethodNode mn : cn.methods) {
			if (calls(mn, "onOrphanInvoked")) n++;
		}
		return n;
	}

	static Set<String> keySet(ClassNode cn) {
		Set<String> keys = new HashSet<>();
		for (MethodNode mn : cn.methods) keys.add(mn.name + mn.desc);
		return keys;
	}

	static String table(ClassNode cn) {
		StringBuilder sb = new StringBuilder("[");
		for (MethodNode mn : cn.methods) {
			if (mn.name.startsWith("lambda$") || calls(mn, "onOrphanInvoked")) {
				sb.append(mn.name).append(mn.desc)
					.append(calls(mn, "onOrphanInvoked") ? "(GHOST) " : " ");
			}
		}
		return sb.append("]").toString();
	}

	static String javac21() {
		String j = System.getProperty("hstest.javac21");
		if (j != null && new File(j).exists()) return j;
		j = System.getenv("HSTEST_JAVAC21");
		if (j != null && new File(j).exists()) return j;
		j = "F:/files/java/jdks/openjdk-21.0.2/bin/javac.exe";
		return new File(j).exists() ? j : "javac";
	}

	static File findFile(String rel) {
		File f = new File(rel);
		if (f.exists()) return f;
		File f2 = new File("tests", rel);
		if (f2.exists()) return f2;
		File f3 = new File("scratch/hstest", rel);
		if (f3.exists()) return f3;
		return f;
	}

	static void runCmd(String... cmd) throws Exception {
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
			String line;
			while ((line = r.readLine()) != null) {
				System.out.println("[javac] " + line);
			}
		}
		int rc = p.waitFor();
		if (rc != 0) throw new RuntimeException("Command failed with exit " + rc + ": " + Arrays.toString(cmd));
	}
}
