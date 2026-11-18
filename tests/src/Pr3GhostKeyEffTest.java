import remold.LambdaAligner;
import remold.vcs.AlignmentMetaStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Run R 下幽灵生前指纹必须按<b>生效侧键</b>写进桩体，否则"删掉又原样加回"的 lambda 认领不回自己的槽位。
 *
 * <p><b>缺陷形态</b>：{@code resurrectOrphanedLambdas} 里 {@code ghostHashByKey} 的第三个填充块
 * 用的是 {@code ctx.oldGroups} 的 {@code oi.name + oi.desc}（<b>Raw</b> 键），而查表用的是
 * {@code toInject} 的 {@code mn.name + mn.desc}（<b>Eff</b> 键，来自 {@code E_prev}）。
 * Run R 下活体 lambda 被避障改名后 {@code effKey != rawKey}，于是：</p>
 * <ul>
 *   <li>Eff 名在 R_prev 里恰好是<b>另一个同签名兄弟</b>的 Raw 名时 → 幽灵桩拿到兄弟的生前指纹（串号）；</li>
 *   <li>否则 → 拿到 {@code 0L}（无标记）。</li>
 * </ul>
 * <p>两种形态都会让下一轮的 {@code stepGhostRevive} 认不出"这就是原来那个 lambda"。</p>
 *
 * <p><b>四轮夹具</b>（javac 21 现编，宿主 {@code run}）：</p>
 * <pre>
 *   v1: add(-&gt; a()); add(-&gt; b());                 javac: $0=a, $1=b
 *   v2: add(-&gt; x()); add(-&gt; a()); add(-&gt; b());      javac: $0=x, $1=a, $2=b
 *       → 第 1 轮产物 Eff_2 把 a/b 改名成 $0/$1，x 拿自造名 $3
 *   v3: add(-&gt; x()); add(-&gt; a());                 → b 在 Eff_3 里被幽灵化到 $1
 *       R_prev(=raw2) 的 Raw $1 恰好是<b>兄弟 a</b> —— 这就是串号的现场
 *   v4: 与 v2 同源（原封不动加回 b）
 * </pre>
 *
 * <p><b>为什么用 Run R + Run E 混合</b>：桩体标记只在"老侧能看到幽灵"时被消费，而 Run R 的老侧是
 * {@code R_prev}（不含幽灵）。所以第 3 轮显式关掉 {@code META_SOURCE} 走兼容跑道（老侧就是 Eff_3），
 * 此时 {@code stepGhostRevive} 能读到<b>上一轮 Run R 写进桩体的标记</b>——标记错就认领不回来。
 * 这样断言仍然落在终局：产物里 {@code lambda$run$1} 必须是活体且执行 b 自己的旧体，而不是幽灵桩。</p>
 */
class Pr3GhostKeyEffTest {

	private static final String CLASS = "test/GhostKeyCase";
	private static final String V1 = """
		        list.add(() -> a());
		        list.add(() -> b());""";
	private static final String V2 = """
		        list.add(() -> x());
		        list.add(() -> a());
		        list.add(() -> b());""";
	private static final String V3 = """
		        list.add(() -> x());
		        list.add(() -> a());""";

	private boolean savedMetaSource;
	private String savedRevive;

	@BeforeEach
	void setUp() {
		savedMetaSource = LambdaAligner.META_SOURCE;
		savedRevive = System.getProperty("remold.agent.ghost_revive");
		LambdaAligner.META_SOURCE = true;
		AlignmentMetaStore.INSTANCE.reset();
		LambdaAligner.clearLoggedWarnings();
	}

	@AfterEach
	void tearDown() {
		LambdaAligner.META_SOURCE = savedMetaSource;
		if (savedRevive == null) System.clearProperty("remold.agent.ghost_revive");
		else System.setProperty("remold.agent.ghost_revive", savedRevive);
		LambdaAligner.LAST_RESULT = null;
		AlignmentMetaStore.INSTANCE.reset();
	}

	@Test
	void ghostMarkerUsesEffectiveKeySoTheRestoredLambdaReclaimsItsSlot(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] raw1 = compile(javac, tmp, "v1", V1);
		byte[] raw2 = compile(javac, tmp, "v2", V2);
		byte[] raw3 = compile(javac, tmp, "v3", V3);
		byte[] raw4 = compile(javac, tmp, "v4", V2);   // 与 v2 同源：b 的方法体逐字节等价
		ClassLoader loader = new ClassLoader() { };

		// ---- 第 1 轮：v1 -> v2。a/b 保名，x 拿自造名，为第 2 轮的键错位做准备。 ----
		LambdaAligner.LAST_RESULT = null;
		byte[] eff2 = LambdaAligner.align(raw1, raw2, null, null, null, loader);
		LambdaAligner.AlignmentResult r1 = requireResult("round 1");
		Bytecode b2 = new Bytecode(eff2);
		assertEquals("a", b2.literalOf("lambda$run$0"), "premise: $0 carries the a-body, got " + b2);
		assertEquals("b", b2.literalOf("lambda$run$1"), "premise: $1 carries the b-body, got " + b2);
		String invented = only(r1.inventedNames);
		assertEquals("x", b2.literalOf(invented), "premise: the invented name carries the x-body, got " + b2);
		commit(loader, raw2, eff2, r1);

		// ---- 第 2 轮：v2 -> v3（删掉 b）。必须真的走 Run R（否则这条用例测的是兼容跑道）。 ----
		LambdaAligner.LAST_RESULT = null;
		byte[] eff3 = LambdaAligner.align(eff2, raw3, null, null, null, loader);
		LambdaAligner.AlignmentResult r2 = requireResult("round 2");
		assertEquals(LambdaAligner.BaselineTree.RAW_PREV, r2.baselineTree,
			"round 2 must pair against R_prev, got baselineTree=" + r2.baselineTree);
		assertEquals(LambdaAligner.RunRGate.PASSED, r2.runRGate,
			"round 2 must pass every Run R gate, got runRGate=" + r2.runRGate);

		Bytecode b3 = new Bytecode(eff3);
		assertEquals("x", b3.literalOf(invented), "premise: the x-slot survives round 2, got " + b3);
		assertEquals("a", b3.literalOf("lambda$run$0"), "premise: $0 keeps the a-body, got " + b3);
		assertTrue(b3.isGhost("lambda$run$1"),
			"premise: the deleted b must be ghostified at its effective slot $1, got " + b3);
		commit(loader, raw3, eff3, r2);

		// ---- 第 3 轮：v4 原封不动加回 b。 ----
		// 关掉 Run R（META_SOURCE=false）：老侧变成 Eff_3，幽灵与它的桩体标记都在老侧，
		// stepGhostRevive 才有机会读到上一轮 Run R 写下的那份标记。
		LambdaAligner.META_SOURCE = false;
		System.setProperty("remold.agent.ghost_revive", "true");
		LambdaAligner.LAST_RESULT = null;
		byte[] eff4 = LambdaAligner.align(eff3, raw4, null, null, r2.inventedNames, loader);
		Bytecode b4 = new Bytecode(eff4);

		// 终局断言：原来的槽位必须被活体 lambda 收回，并且执行的是 b 自己的旧体。
		assertFalse(b4.isGhost("lambda$run$1"),
			"the restored lambda must reclaim its ghost slot $1 instead of leaving a stub behind, got " + b4);
		assertEquals("b", b4.literalOf("lambda$run$1"),
			"the reclaimed slot must run the restored b-body, got " + b4);
		assertEquals("lambda$run$1", nameCarrying(b4, "b"),
			"exactly one live b-body, at the reclaimed slot (not at a fresh name), got " + b4);
		assertEquals("a", b4.literalOf("lambda$run$0"), "the a-slot must stay untouched, got " + b4);
		assertEquals("x", b4.literalOf(invented), "the invented x-slot must stay untouched, got " + b4);
	}

	// ------------------------------------------------------------------------------------------------

	private static LambdaAligner.AlignmentResult requireResult(String what) {
		LambdaAligner.AlignmentResult r = LambdaAligner.LAST_RESULT;
		assertNotNull(r, what + " must publish an AlignmentResult");
		return r;
	}

	private static String only(Set<String> names) {
		assertEquals(1, names.size(), "premise: exactly one invented name, got " + names);
		return names.iterator().next();
	}

	private static void commit(ClassLoader loader, byte[] preAlign, byte[] effective,
	                           LambdaAligner.AlignmentResult r) {
		assertNotNull(r, "commit needs the AlignmentResult of the round it records");
		AlignmentMetaStore.INSTANCE.commit(loader, CLASS, preAlign, effective, effective,
			r.inventedNames, LambdaAligner.scanGhostOriginHashes(effective),
			r.methodMapping == null ? Map.of() : r.methodMapping, r.bridgeCount, r.rawToEffKeys);
	}

	private static byte[] compile(String javac, Path tmp, String dir, String body) throws Exception {
		String src = "package test;\n"
			+ "import java.util.List;\n"
			+ "public class GhostKeyCase {\n"
			+ "    static void a() { System.out.println(\"a\"); }\n"
			+ "    static void b() { System.out.println(\"b\"); }\n"
			+ "    static void x() { System.out.println(\"x\"); }\n"
			+ "    public void run(List<Runnable> list) {\n"
			+ body + "\n"
			+ "    }\n"
			+ "}\n";
		Path srcDir = Files.createDirectories(tmp.resolve("src-" + dir));
		Path outDir = Files.createDirectories(tmp.resolve("out-" + dir));
		Path f = srcDir.resolve("GhostKeyCase.java");
		Files.writeString(f, src, StandardCharsets.UTF_8);
		HstestFixtures.compile(javac, outDir, f.toFile());
		return Files.readAllBytes(outDir.resolve("test/GhostKeyCase.class"));
	}

	private static String nameCarrying(Bytecode out, String body) {
		String found = null;
		int count = 0;
		for (String name : out.lambdaNames()) {
			if (!out.isGhost(name) && body.equals(out.literalOf(name))) {
				found = name;
				count++;
			}
		}
		return count == 1 ? found : null;
	}

	/** 产物视图：lambda 方法名 -> 它委托的业务方法（幽灵桩为 null，另带桩体标记值）。 */
	private static final class Bytecode {
		private final ClassNode cn;

		Bytecode(byte[] bytes) {
			cn = new ClassNode();
			new ClassReader(bytes).accept(cn, 0);
		}

		Iterable<String> lambdaNames() {
			Map<String, String> names = new LinkedHashMap<>();
			for (MethodNode mn : cn.methods) names.put(mn.name, mn.desc);
			return names.keySet();
		}

		String literalOf(String name) {
			for (MethodNode mn : cn.methods) {
				if (!mn.name.equals(name)) continue;
				for (AbstractInsnNode insn : mn.instructions) {
					if (insn instanceof MethodInsnNode mi && mi.owner.equals(CLASS)) return mi.name;
				}
			}
			return null;
		}

		/** 幽灵桩标记：{@code LDC <long>; POP2} 里那个 long（没有标记返回 Long.MIN_VALUE）。 */
		long ghostMarker(String name) {
			for (MethodNode mn : cn.methods) {
				if (!mn.name.equals(name)) continue;
				for (AbstractInsnNode insn : mn.instructions) {
					if (insn instanceof LdcInsnNode ld && ld.cst instanceof Long v) return v;
				}
			}
			return Long.MIN_VALUE;
		}

		boolean isGhost(String name) {
			for (MethodNode mn : cn.methods) {
				if (!mn.name.equals(name)) continue;
				for (AbstractInsnNode insn : mn.instructions) {
					if (insn instanceof LdcInsnNode ld && ld.cst instanceof Long) return true;
				}
			}
			return false;
		}

		@Override
		public String toString() {
			Map<String, String> view = new LinkedHashMap<>();
			for (MethodNode mn : cn.methods) {
				if (mn.name.startsWith("lambda$")) {
					String body = literalOf(mn.name);
					view.put(mn.name + (isGhost(mn.name) ? "(ghost marker=" + ghostMarker(mn.name) + ")" : ""),
						body == null ? "-" : body);
				}
			}
			return view.toString();
		}
	}
}
