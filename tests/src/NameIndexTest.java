import remold.LambdaAligner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 名字键索引缺陷独立断言（JUnit 化）。
 *
 * <p>场景：第二轮对齐（对齐结果作为旧类，再次对齐同一份新类）。旧类中同时存在活叶子与上一轮注入的
 * 幽灵叶子（同名不同描述符）时，名字索引必须保留活方法；因此第二轮应在 Step 1 就完成配对
 * （step1Pairs == 2，passAPairs == 0）。矩阵覆盖 JDK 8/17/21 × 正反序。</p>
 *
 * <p><b>判据分两层</b>：{@code LAST_STATS} 只能说明"走了哪条路"，产物字节码才说明
 * "活方法有没有被幽灵抢走名字"。所以除了计数器，还必须断言第二轮的产物形态。</p>
 */
public class NameIndexTest {

	static byte[] align(byte[] o, byte[] n, boolean reverse) {
		try {
			LambdaAligner.TEST_REVERSE_GROUP_ORDER = reverse;
			return LambdaAligner.align(o, n);
		} finally {
			LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		}
	}

	static Path findPath(String rel) {
		Path p = Paths.get(rel);
		if (Files.exists(p)) return p;
		Path p2 = Paths.get("tests", rel);
		if (Files.exists(p2)) return p2;
		Path p3 = Paths.get("scratch/hstest", rel);
		if (Files.exists(p3)) return p3;
		return p;
	}

	static void testJdk(String label, String v1Path, String v2Path, boolean reverse) throws Exception {
		byte[] v1 = Files.readAllBytes(findPath(v1Path));
		byte[] v2 = Files.readAllBytes(findPath(v2Path));

		String orderTag = reverse ? " [反序]" : " [正序]";
		System.out.println("== " + label + orderTag + " ==");

		// 第一轮：V1 -> V2
		LambdaAligner.LAST_STATS = null;
		byte[] out1 = align(v1, v2, reverse);
		assertNotNull(out1, label + orderTag + " 第一轮 align 返回非空");

		// 第二轮：V2产物 -> V2（检测同名活/幽灵对共存下的索引行为）
		LambdaAligner.LAST_STATS = null;
		byte[] out2 = align(out1, v2, reverse);
		assertNotNull(out2, label + orderTag + " 第二轮 align 返回非空");

		LambdaAligner.AlignmentStats stats = LambdaAligner.LAST_STATS;
		assertNotNull(stats, label + orderTag + " 第二轮配对统计非空（已正常写入 LAST_STATS）");

		System.out.println("   第二轮统计: " + stats);
		if (stats != null) {
			assertEquals(2, stats.step1Pairs,
				label + orderTag + " 第二轮中层与叶子必须全部通过 Step 1 证据匹配（实际=" + stats.step1Pairs + "）");
			assertEquals(0, stats.passAPairs,
				label + orderTag + " 第二轮不应回退到 A 趟降级兜底（实际=" + stats.passAPairs + "）");
		}

		// —— 以下才是终局断言：计数器只说明走了哪条路，产物字节码才说明配得对不对 ——
		ClassNode before = parse(out1);
		ClassNode after = parse(out2);

		// 1. 前置基线：第一轮产物确实已经存在"同名活方法 + 同名幽灵"这对，否则下面的断言会空转。
		//    名字不写死：javac 8 把 lambda 体内的嵌套 lambda 命名为 lambda$null$N，
		//    javac 17/21 才是 lambda$build$N —— 所以从幽灵自己身上取名。
		assertEquals(1, ghostCount(before),
			label + orderTag + " 基线：第一轮产物应含恰好 1 个孤儿桩（实际: " + table(before) + "）");
		MethodNode ghostBefore = onlyGhost(before);
		assertNotNull(ghostBefore, label + orderTag + " 基线：必须能找到那个孤儿桩（实际: " + table(before) + "）");
		String liveName = ghostBefore.name;
		MethodNode liveBefore = method(before, liveName, "(II)V");
		assertNotNull(liveBefore, label + orderTag + " 基线：第一轮产物应含与幽灵同名的活叶子 "
			+ liveName + "(II)V（实际: " + table(before) + "）");
		assertFalse(calls(liveBefore, "onOrphanInvoked"),
			label + orderTag + " 基线：同名活叶子本身不能是孤儿桩");
		assertTrue(calls(liveBefore, "use"), label + orderTag + " 基线：活叶子体应调用 use");

		// 2. 第二轮必须是零改动：活方法与幽灵方法的名字、归属、方法体都不许动。
		//    名字索引一旦把活方法的名字让给幽灵，这里必然变（活方法被改名或被换成熔断桩）。
		assertArrayEquals(out1, out2,
			label + orderTag + " 第二轮必须对第一轮产物零改动（实际: out1 " + table(before)
				+ " vs out2 " + table(after) + "）");

		// 3. 活方法仍然是活方法，且方法体没被换掉。
		MethodNode live = method(after, liveName, "(II)V");
		assertNotNull(live, label + orderTag + " 活叶子 " + liveName + "(II)V 必须存在（实际: " + table(after) + "）");
		assertFalse(calls(live, "onOrphanInvoked"),
			label + orderTag + " 活叶子必须保持活方法，不能被幽灵顶替");
		assertTrue(calls(live, "use"),
			label + orderTag + " 活叶子的方法体必须未被换掉（应仍调用 use）");

		// 4. 同名幽灵仍然恰好一个：既不能被"复活"成第二个活方法，也不能被静默丢弃。
		assertEquals(1, ghostCount(after),
			label + orderTag + " 同名幽灵 " + liveName + "(I)V 必须恰好保留一个（实际: " + table(after) + "）");

		// 5. 产物自洽：不得出现重复的 name+desc。
		assertEquals(keySet(after).size(), after.methods.size(),
			label + orderTag + " 产物方法名+描述符必须唯一（实际: " + table(after) + "）");

		System.out.println();
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

	static boolean calls(MethodNode mn, String name) {
		if (mn == null || mn.instructions == null) return false;
		for (AbstractInsnNode insn : mn.instructions) {
			if (insn instanceof MethodInsnNode mi && mi.name.equals(name)) return true;
		}
		return false;
	}

	static int ghostCount(ClassNode cn) {
		int n = 0;
		for (MethodNode mn : cn.methods) {
			if (calls(mn, "onOrphanInvoked")) n++;
		}
		return n;
	}

	static MethodNode onlyGhost(ClassNode cn) {
		for (MethodNode mn : cn.methods) {
			if (calls(mn, "onOrphanInvoked")) return mn;
		}
		return null;
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

	@Test
	void secondRoundUsesNameIndexWithoutGhostCollision(@TempDir Path tmp) throws Exception {
		for (int major : new int[] { 8, 17, 21 }) {
			String javac = HstestFixtures.javac(major);
			Path d1 = tmp.resolve("fa" + major + "v1");
			Path d2 = tmp.resolve("fa" + major + "v2");
			HstestFixtures.compile(javac, d1,
				HstestFixtures.find("compA/Time.java"), HstestFixtures.find("compA/v1/FixtureA.java"));
			HstestFixtures.compile(javac, d2,
				HstestFixtures.find("compA/Time.java"), HstestFixtures.find("compA/v2/FixtureA.java"));

			String v1 = d1.resolve("FixtureA.class").toString();
			String v2 = d2.resolve("FixtureA.class").toString();
			testJdk("JDK" + major, v1, v2, false);
			testJdk("JDK" + major, v1, v2, true);
		}
	}
}
