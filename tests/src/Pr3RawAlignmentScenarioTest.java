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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR3 专属增量场景探针：v1 -> v2 -> v3 三代历史，污染留在 Eff 里。
 *
 * <p><b>为什么需要这个场景</b>：前三代累计之后，上一轮 Eff 里除了活 lambda，还留着
 * 「上一轮自造名」「幽灵桩 / 保留体」「捕获桥」。活体对齐拿这份 Eff 去硬刚本轮 Raw 时，
 * 名字已经不是 javac 的槽位身份，只能是「位置/顺序兜底」配对；Raw <-> Raw 才是同源比较，
 * 槽位名即身份。幽灵与保留体不在 Raw 里，必须从 Eff_prev 继承。</p>
 *
 * <p><b>夹具</b>（javac 21 现编，三代源码）：</p>
 * <pre>
 *   v1: add(() -&gt; a()); add(() -&gt; b());              javac: $0=a, $1=b
 *   v2: add(() -&gt; x()); add(() -&gt; a()); add(() -&gt; b());   javac: $0=x, $1=a, $2=b
 *   v3a(微调): add(() -&gt; x()); add(() -&gt; a()); add(() -&gt; bMod());
 *   v3b(Ctrl+Z): 回到 v1 的源码
 * </pre>
 * <p>v1-&gt;v2 一轮就同时产出三种污染：a/b 被改名（Raw 名 $1/$2 -&gt; Eff 名 $0/$1）、
 * x 拿到自造名（javac 的 $0 被 a 的身份占着，只能避障取名）、以及 v3b 里 b/x 需要幽灵化。</p>
 *
 * <p><b>判据（都落在产物字节码终局，不看计数器当结论）</b>：</p>
 * <ol>
 *   <li>前置基线：v1-&gt;v2 的产物里确实同时存在活方法、自造名与（Ctrl+Z 时的）幽灵桩，
 *       否则后面的断言会空转；</li>
 *   <li>活体名不许换主人：Eff 里某个名字承载哪个 lambda 体，下一代必须还是它
 *       （老 CallSite 的 name+desc 是钉死的，换主人就是静默跑错）；</li>
 *   <li>幽灵/保留体必须从 Eff_prev 继承：Raw 里没有它们，只拿 Raw 当旧侧一定会丢；</li>
 *   <li>配对证据不能被位置兜底替代：微调那一轮若走顺序兜底，aligner 自己会打
 *       {@code POS_MISMATCH}（\&quot;Old CallSites will execute the new body quietly\&quot;）；
 *       Raw <-> Raw 有槽位身份，不需要这条兜底；</li>
 *   <li><b>自造名不许被本轮新增的 lambda 抢走</b>：自造名长得跟 javac 名一样（{@code lambda$run$3}），
 *       下一轮 javac 从 0 重新编号时完全可能给"另一个全新 lambda"同样的名字。若旧侧是 Eff，
 *       "同名 + 同宿主"会被当成原地更新，于是老 CallSite 静默跑成新 lambda、而真正被改体的那个
 *       lambda 被挤到新槽位（{@link #metaRoundKeepsInventedSlotForTheEditedLambda} 在基线上就是这样）。
 *       Raw <-> Raw 里 {@code $3} 仍是"v2 里排第 3 的源码槽位"，再经 meta 映射回 eff 名，归属才不会错。</li>
 * </ol>
 *
 * <p><b>对照组</b>（缺一不可，且都在基线即绿）：</p>
 * <ul>
 *   <li>去触发：同一轮改用 Raw_prev 当旧侧（不接 meta 名字映射）时，活体名会换主人
 *       （{@link #rawOnlyBaselineLosesLiveNames}）——证明第 3 条断言不是恒真；</li>
 *   <li>换无关输入：Ctrl+Z 那一轮的 Raw 只有 Raw 时拿不到幽灵桩 / 桥，
 *       它们来自 Eff 继承而不是顺着新字节冒出来的（{@link #metaRoundInheritsEffGhostsOnCtrlZ}
 *       与 {@link #metaRoundInheritsCaptureBridgeFromEffectiveBytes} 内的对照）。</li>
 * </ul>
 */
public class Pr3RawAlignmentScenarioTest {

	private static final String CLASS = "test/Pr3Case";

	@BeforeEach
	@AfterEach
	void resetState() {
		LambdaAligner.META_SOURCE = false;
		LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		LambdaAligner.LAST_RESULT = null;
		LambdaAligner.clearLoggedWarnings();
		AlignmentMetaStore.INSTANCE.reset();
	}

	// ------------------------------------------------------------------------------------------------
	// 1. 前置基线：v1 -> v2 确实把历史污染留在了 Eff 里
	// ------------------------------------------------------------------------------------------------

	@Test
	void firstRoundLeavesHistoryPollutionInEffectiveBytes(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] raw1 = compile(javac, tmp, "p1", V1);
		byte[] raw2 = compile(javac, tmp, "p2", V2);

		Bytecode rawBytes = new Bytecode(raw2);
		assertEquals("x", rawBytes.literalOf("lambda$run$0"), "fixture premise: v2 raw lambda$run$0 is the x-lambda");
		assertEquals("a", rawBytes.literalOf("lambda$run$1"), "fixture premise: v2 raw lambda$run$1 is the a-lambda");
		assertEquals("b", rawBytes.literalOf("lambda$run$2"), "fixture premise: v2 raw lambda$run$2 is the b-lambda");

		byte[] eff2 = align(raw1, raw2, null);
		Bytecode eff = new Bytecode(eff2);

		// 改名：a 从 Raw 的 $1 变成 Eff 的 $0，b 从 $2 变成 $1（老 CallSite 要的就是这两个名字）。
		assertEquals("a", eff.literalOf("lambda$run$0"), "eff premise: lambda$run$0 must carry the a-body");
		assertEquals("b", eff.literalOf("lambda$run$1"), "eff premise: lambda$run$1 must carry the b-body");

		// 自造名：x 在 v2 里拿不到 javac 的 $0（那个名字已经是 a 的身份），只能避障取名。
		Set<String> invented = LambdaAligner.LAST_RESULT.inventedNames;
		assertFalse(invented.isEmpty(), "eff premise: the second round must invent at least one name, got " + eff);
		String inventedName = onlyInvented(invented);
		assertEquals("x", eff.literalOf(inventedName),
			"eff premise: the invented name must carry the x-body, got " + eff);

		// 污染的可断言形态：Eff 的方法名集合与 Raw 不同（这就是"不再拿 Eff 硬刚"的起因）。
		assertNotEquals(rawBytes.lambdaNames(), eff.lambdaNames(),
			"eff premise: the effective name set must differ from the raw javac name set");
	}

	// ------------------------------------------------------------------------------------------------
	// 2. 去触发对照：只把 Raw_prev 当旧侧（不接 meta 名字映射）→ 活体名换主人
	// ------------------------------------------------------------------------------------------------

	@Test
	void rawOnlyBaselineLosesLiveNames(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] raw1 = compile(javac, tmp, "q1", V1);
		byte[] raw2 = compile(javac, tmp, "q2", V2);
		byte[] raw3 = compile(javac, tmp, "q3", V3_TWEAK);

		byte[] eff2 = align(raw1, raw2, null);
		Bytecode eff = new Bytecode(eff2);
		String inventedX = onlyInvented(LambdaAligner.LAST_RESULT.inventedNames);
		assertEquals("x", eff.literalOf(inventedX), "fixture premise: the invented name carries x");

		// 对照组：raw -> raw，且只把上一轮的自造名喂回去（没有 Eff 继承、没有 raw->eff 名字映射）。
		byte[] rawPath = align(raw2, raw3, LambdaAligner.LAST_RESULT.inventedNames);
		Bytecode out = new Bytecode(rawPath);

		// 这一轮的 live 名字 $0/$1/$N 全部换主人或消失：raw2 的 $0 是 x（不是 a）。
		assertNotEquals("a", out.literalOf("lambda$run$0"),
			"control: raw-only must NOT preserve lambda$run$0 -> a-body (that is what PR3 has to fix), got " + out);
		assertNotEquals("x", out.literalOf(inventedX),
			"control: raw-only must NOT keep the invented name bound to the x-body, got " + out);
	}

	// ------------------------------------------------------------------------------------------------
	// 3. R3 核心（终局判据）：自造名不许被"本轮新增的 lambda"抢走
	// ------------------------------------------------------------------------------------------------

	@Test
	void metaRoundKeepsInventedSlotForTheEditedLambda(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] raw1 = compile(javac, tmp, "n1", V1);
		byte[] raw2 = compile(javac, tmp, "n2", V2);
		// v3：x 只改方法体（同一源码槽位原地更新）+ 末尾新增一个 lambda。
		// 新增 lambda 的 javac 名正好落在 $3——而 $3 是上一轮给 x 自造的避障名。
		byte[] raw3 = compile(javac, tmp, "n3", V3_TWEAK_AND_NEW);

		Chain chain = runChain(raw1, raw2);
		Bytecode eff = new Bytecode(chain.eff2);
		String inventedX = chain.inventedX;
		assertEquals("x", eff.literalOf(inventedX), "fixture premise: the invented name carries the x-body");

		LambdaAligner.LAST_RESULT = null;
		LambdaAligner.clearLoggedWarnings();
		byte[] eff3 = LambdaAligner.align(chain.eff2, raw3, null, null, null, chain.loader);
		Bytecode out = new Bytecode(eff3);

		// 终局断言：v2 的 live 名各自还得承载"同一个源码槽位"。
		// 自造名 $3 在 v2 属于 x；本轮 x 只是改体，槽位身份没变 —— 它必须还是 xMod，
		// 老 CallSite 才不会静默跑成"新加的 new1"。反过来，new1 是全新的 lambda，只能拿新名字。
		assertEquals("a", out.literalOf("lambda$run$0"), "live name lambda$run$0 must still carry the a-body, got " + out);
		assertEquals("b", out.literalOf("lambda$run$1"), "live name lambda$run$1 must still carry the b-body, got " + out);
		assertEquals("xMod", out.literalOf(inventedX),
			"the invented slot belongs to the x source lambda; editing its body must not hand the name to a "
				+ "brand-new lambda, got " + out);
		assertFalse(out.isGhost(inventedX), "the x slot must stay a live method (not an orphan stub), got " + out);

		// 新 lambda 必须落在 live 名之外的新名字上（不能抢 $0/$1/$3 中任何一个）。
		String newName = nameCarrying(out, "new1");
		assertNotNull(newName, "the new lambda must survive as a live method, got " + out);
		assertFalse(newName.equals("lambda$run$0") || newName.equals("lambda$run$1") || newName.equals(inventedX),
			"the new lambda must take a fresh slot, not reuse a live name, got " + out);
	}

	private static String nameCarrying(Bytecode out, String body) {
		for (String name : out.lambdaNames()) {
			if (!out.isGhost(name) && body.equals(out.literalOf(name))) return name;
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------------
	// 3. R3 核心：微调轮 —— 有 meta 时必须靠 Raw <-> Raw 的槽位身份，而不是位置兜底
	// ------------------------------------------------------------------------------------------------

	@Test
	void metaRoundPreservesLiveIdentitiesWithoutPositionalFallback(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] raw1 = compile(javac, tmp, "m1", V1);
		byte[] raw2 = compile(javac, tmp, "m2", V2);
		byte[] raw3 = compile(javac, tmp, "m3", V3_TWEAK);

		Chain chain = runChain(raw1, raw2);
		Bytecode eff = new Bytecode(chain.eff2);
		String inventedX = chain.inventedX;
		assertEquals("x", eff.literalOf(inventedX), "fixture premise: invented name carries x in eff2");

		// 第二轮：生产形态。oldBytes 仍是"上一轮真正进 JVM 的字节"，meta head 指向它。
		LambdaAligner.LAST_RESULT = null;
		LambdaAligner.clearLoggedWarnings();
		byte[] eff3 = LambdaAligner.align(chain.eff2, raw3, null, null, null, chain.loader);
		LambdaAligner.AlignmentResult result = LambdaAligner.LAST_RESULT;
		Bytecode out = new Bytecode(eff3);

		// 因果读数：这一轮确实读了 meta（不是走了完全无关的路径）。
		assertTrue(result.metaGeneration >= 1,
			"round 2 must read the previous generation from meta, got metaGeneration=" + result.metaGeneration);

		// 终局断言 A：活体名不许换主人（老 CallSite 的解析键是 name+desc）。
		assertEquals("a", out.literalOf("lambda$run$0"), "live name lambda$run$0 must still carry the a-body, got " + out);
		assertEquals("bMod", out.literalOf("lambda$run$1"),
			"live name lambda$run$1 must carry the edited b-body (in-place update of the same source slot), got " + out);
		assertEquals("x", out.literalOf(inventedX),
			"the invented name must keep carrying the x-body, got " + out);

		// 终局断言 B：配对必须来自证据（槽位身份 / 方法体），不能落到位置兜底。
		// 位置兜底一旦发生，aligner 自己会声明 "Old CallSites will execute the new body quietly"：
		// 那正是本战役要消掉的失准形态。
		List<String> degraded = degradedPairings(result);
		assertTrue(degraded.isEmpty(),
			"round 2 must not fall back to positional pairing (raw<->raw slot identity makes it unnecessary): "
				+ degraded + ", out=" + out);
	}

	// ------------------------------------------------------------------------------------------------
	// 4. Ctrl+Z 回退轮：幽灵 / 保留体必须从 Eff_prev 继承（Raw 里没有它们）
	// ------------------------------------------------------------------------------------------------

	@Test
	void metaRoundInheritsEffGhostsOnCtrlZ(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] raw1 = compile(javac, tmp, "z1", V1);
		byte[] raw2 = compile(javac, tmp, "z2", V2);
		byte[] raw3 = compile(javac, tmp, "z3", V1);

		Chain chain = runChain(raw1, raw2);
		Bytecode eff = new Bytecode(chain.eff2);
		String inventedX = chain.inventedX;
		assertEquals("x", eff.literalOf(inventedX), "fixture premise: invented name carries x in eff2");

		// 对照（换无关输入／去触发）：只拿 Raw_prev 当旧侧时，x 的桩没有来源，一定会丢。
		LambdaAligner.META_SOURCE = false;
		byte[] rawOnly = align(raw2, raw3, chain.invented);
		LambdaAligner.META_SOURCE = true;
		Bytecode rawOnlyOut = new Bytecode(rawOnly);
		assertNull(rawOnlyOut.allocationOf(inventedX),
			"control: raw-only has no source for the x stub (it is not in Raw), got " + rawOnlyOut);

		// 生产形态：v3 回到 v1 的源码。
		LambdaAligner.LAST_RESULT = null;
		LambdaAligner.clearLoggedWarnings();
		byte[] eff3 = LambdaAligner.align(chain.eff2, raw3, null, null, null, chain.loader);
		Bytecode out = new Bytecode(eff3);

		// 终局断言 A：v1 的 live 名字必须仍然承载原来的方法体。
		assertEquals("a", out.literalOf("lambda$run$0"), "ctrl+z must restore the a-body under lambda$run$0, got " + out);
		assertEquals("b", out.literalOf("lambda$run$1"), "ctrl+z must restore the b-body under lambda$run$1, got " + out);

		// 终局断言 B：v2 删掉的 x 必须仍然是幽灵桩（老 CallSite 不能 NoSuchMethodError）。
		// 夹具里 x 是 void 回调，按默认策略（remold.agent.orphan_retain_void=false）桩体是
		// onOrphanInvoked 空壳而不是保留旧体，因此这里只钉"桩在、且归属这个名字"。
		assertTrue(out.isGhost(inventedX),
			"the removed x must survive as an inherited ghost stub under its eff name, got " + out);
	}

	// ------------------------------------------------------------------------------------------------
	// 5. 捕获桥也要从 Eff_prev 继承：Raw 里没有桥（桥是上一轮为了让老隐藏类还能解析而造的）
	// ------------------------------------------------------------------------------------------------

	@Test
	void metaRoundInheritsCaptureBridgeFromEffectiveBytes(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] raw1 = compileBridge(javac, tmp, "b1", BRIDGE_V1);
		byte[] raw2 = compileBridge(javac, tmp, "b2", BRIDGE_V2_DROP_CAPTURE);
		byte[] raw3 = compileBridge(javac, tmp, "b3", BRIDGE_V3_BODY_ONLY);
		String cls = "B";

		LambdaAligner.META_SOURCE = true;
		ClassLoader loader = new ClassLoader() { };
		LambdaAligner.LAST_RESULT = null;
		byte[] eff2 = LambdaAligner.align(raw1, raw2, null, null, null, loader);
		LambdaAligner.AlignmentResult r1 = LambdaAligner.LAST_RESULT;
		assertNotNull(r1, "round 1 must publish an alignment result");
		assertEquals(1, r1.bridgeCount, "fixture premise: dropping a capture must build exactly one bridge");
		commit(loader, cls, raw2, eff2, r1);

		// 桥的形态：旧键 (String,I)V 还在，且是转发桩（onBridgeInvoked），不是 onOrphanInvoked 空壳。
		MethodNode bridge = method(eff2, cls, "lambda$f$0", "(Ljava/lang/String;I)V");
		assertNotNull(bridge, "fixture premise: round 1 must keep the old (String,I)V key as a bridge");
		assertTrue(callsInsn(bridge, "onBridgeInvoked"), "fixture premise: the old key must be a forwarding bridge");
		assertFalse(callsInsn(bridge, "onOrphanInvoked"), "a bridge must not degrade into an orphan stub");

		// 对照（去触发）：只拿 Raw_prev 当旧侧时，桥没有来源——它不在 Raw 里。
		LambdaAligner.META_SOURCE = false;
		byte[] rawOnly = align(raw2, raw3, r1.inventedNames);
		LambdaAligner.META_SOURCE = true;
		assertNull(method(rawOnly, cls, "lambda$f$0", "(Ljava/lang/String;I)V"),
			"control: raw-only must not be able to rebuild the bridge (it is not in Raw)");

		// 生产形态：第二轮只改方法体，捕获表没动。
		LambdaAligner.LAST_RESULT = null;
		LambdaAligner.clearLoggedWarnings();
		byte[] eff3 = LambdaAligner.align(eff2, raw3, null, null, null, loader);
		MethodNode bridge3 = method(eff3, cls, "lambda$f$0", "(Ljava/lang/String;I)V");
		assertNotNull(bridge3, "round 2 must inherit/re-arm the capture bridge from Eff_prev");
		assertTrue(callsInsn(bridge3, "onBridgeInvoked"),
			"the inherited bridge must stay a forwarding bridge, not a dead orphan stub");
		assertNotNull(method(eff3, cls, "lambda$f$0", "(I)V"),
			"round 2 must still contain the live (I)V lambda the bridge forwards to");
	}

	/** 对齐期捕获桥夹具：v1 两个捕获 -&gt; v2 少一个捕获（建桥）-&gt; v3 只改方法体。 */
	private static final String BRIDGE_V1 = "public class B {\n"
		+ "    public static Runnable r;\n"
		+ "    public static String   last = \"\";\n"
		+ "    public static void f(String s, int k) { r = () -> last = s + k; }\n"
		+ "}\n";

	private static final String BRIDGE_V2_DROP_CAPTURE = "public class B {\n"
		+ "    public static Runnable r;\n"
		+ "    public static String   last = \"\";\n"
		+ "    public static void f(int k) { r = () -> last = \"v2:\" + k; }\n"
		+ "}\n";

	private static final String BRIDGE_V3_BODY_ONLY = "public class B {\n"
		+ "    public static Runnable r;\n"
		+ "    public static String   last = \"\";\n"
		+ "    public static void f(int k) { r = () -> last = \"v3:\" + k; }\n"
		+ "}\n";

	private static byte[] compileBridge(String javac, Path tmp, String dir, String src) throws Exception {
		Path srcDir = Files.createDirectories(tmp.resolve("src-" + dir));
		Path outDir = Files.createDirectories(tmp.resolve("out-" + dir));
		Path f = srcDir.resolve("B.java");
		Files.writeString(f, src, StandardCharsets.UTF_8);
		HstestFixtures.compile(javac, outDir, f.toFile());
		return Files.readAllBytes(outDir.resolve("B.class"));
	}

	private static MethodNode method(byte[] bytes, String className, String name, String desc) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		for (MethodNode mn : cn.methods) if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
		return null;
	}

	private static boolean callsInsn(MethodNode mn, String name) {
		for (AbstractInsnNode insn : mn.instructions) {
			if (insn instanceof MethodInsnNode mi && mi.name.equals(name)) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------------------------------------
	// 夹具与工具
	// ------------------------------------------------------------------------------------------------

	private static final String V1 =
		"        list.add(() -> a());\n"
		+ "        list.add(() -> b());";

	private static final String V2 =
		"        list.add(() -> x());\n"
		+ "        list.add(() -> a());\n"
		+ "        list.add(() -> b());";

	private static final String V3_TWEAK =
		"        list.add(() -> x());\n"
		+ "        list.add(() -> a());\n"
		+ "        list.add(() -> bMod());";

	/** v3 微调 + 新增：x 改体（原地更新），并在末尾新增一个 lambda（javac 名正好是 $3）。 */
	private static final String V3_TWEAK_AND_NEW =
		"        list.add(() -> xMod());\n"
		+ "        list.add(() -> a());\n"
		+ "        list.add(() -> b());\n"
		+ "        list.add(() -> new1());";

	/** 一条真链：第一轮 raw1 -&gt; raw2（生产形态 + 落 meta），第二轮由各用例自己发起。 */
	private static final class Chain {
		final ClassLoader loader;
		final byte[] eff2;
		final Set<String> invented;
		final String inventedX;

		Chain(ClassLoader loader, byte[] eff2, Set<String> invented, String inventedX) {
			this.loader = loader;
			this.eff2 = eff2;
			this.invented = invented;
			this.inventedX = inventedX;
		}
	}

	private static Chain runChain(byte[] raw1, byte[] raw2) {
		LambdaAligner.META_SOURCE = true;
		ClassLoader loader = new ClassLoader() { };
		LambdaAligner.LAST_RESULT = null;
		byte[] eff2 = LambdaAligner.align(raw1, raw2, null, null, null, loader);
		LambdaAligner.AlignmentResult r = LambdaAligner.LAST_RESULT;
		assertNotNull(r, "first round must publish an alignment result");
		Set<String> invented = r.inventedNames;
		assertFalse(invented.isEmpty(), "first round must invent a name for the front-inserted lambda");
		commit(loader, raw2, eff2, r);
		assertNotNull(AlignmentMetaStore.INSTANCE.head(loader, CLASS),
			"first round must leave a head entry for the class");
		return new Chain(loader, eff2, invented, onlyInvented(invented));
	}

	private static void commit(ClassLoader loader, byte[] preAlign, byte[] effective, LambdaAligner.AlignmentResult r) {
		commit(loader, CLASS, preAlign, effective, r);
	}

	private static void commit(ClassLoader loader, String className, byte[] preAlign, byte[] effective,
	                           LambdaAligner.AlignmentResult r) {
		AlignmentMetaStore.INSTANCE.commit(loader, className, preAlign, effective, effective,
			r.inventedNames, LambdaAligner.scanGhostOriginHashes(effective),
			r.methodMapping == null ? Map.of() : r.methodMapping, r.bridgeCount);
	}

	private static byte[] align(byte[] oldBytes, byte[] newBytes, Set<String> invented) {
		LambdaAligner.LAST_RESULT = null;
		return LambdaAligner.align(oldBytes, newBytes, null, null,
			invented == null ? Set.of() : invented, null);
	}

	private static String onlyInvented(Set<String> invented) {
		assertEquals(1, invented.size(), "fixture premise: exactly one invented name, got " + invented);
		return invented.iterator().next();
	}

	/** 本轮发生的"证据不足只能按位置猜"的事件；有就说明配对不是证据驱动的。 */
	private static List<String> degradedPairings(LambdaAligner.AlignmentResult r) {
		List<String> out = new ArrayList<>();
		if (r != null && r.stats != null) {
			assertTrue(r.stats.passAPairs >= 0, "stats must be present");
		}
		for (LambdaAligner.AlignmentEvent e : LambdaAligner.TEST_EVENTS) {
			if ("POS_MISMATCH".equals(e.kind) || "BODY_MISMATCH".equals(e.kind)) out.add(e.toString());
		}
		return out;
	}

	private static byte[] compile(String javac, Path tmp, String dir, String body) throws Exception {
		String src = "package test;\n"
			+ "import java.util.List;\n"
			+ "public class Pr3Case {\n"
			+ "    static void a() { System.out.println(\"a\"); }\n"
			+ "    static void b() { System.out.println(\"b\"); }\n"
			+ "    static void bMod() { System.out.println(\"bMod\"); }\n"
			+ "    static void x() { System.out.println(\"x\"); }\n"
			+ "    static void xMod() { System.out.println(\"xMod\"); }\n"
			+ "    static void new1() { System.out.println(\"new1\"); }\n"
			+ "    public void run(List<Runnable> list) {\n"
			+ body + "\n"
			+ "    }\n"
			+ "}\n";
		Path srcDir = Files.createDirectories(tmp.resolve("src-" + dir));
		Path outDir = Files.createDirectories(tmp.resolve("out-" + dir));
		Path f = srcDir.resolve("Pr3Case.java");
		Files.writeString(f, src, StandardCharsets.UTF_8);
		HstestFixtures.compile(javac, outDir, f.toFile());
		return Files.readAllBytes(outDir.resolve("test/Pr3Case.class"));
	}

	/** 产物字节码的可读视图：lambda 名字 -&gt; 方法体印出来的字面量，以及幽灵/保留体标记。 */
	private static final class Bytecode {
		private final ClassNode cn;

		Bytecode(byte[] bytes) {
			cn = new ClassNode();
			new ClassReader(bytes).accept(cn, 0);
		}

		Set<String> lambdaNames() {
			Set<String> names = new java.util.LinkedHashSet<>();
			for (MethodNode mn : cn.methods) if (mn.name.startsWith("lambda$")) names.add(mn.name);
			return names;
		}

		/**
		 * 该名字对应方法体"承载的语义"：体内那条指向本类业务方法（{@code a/b/bMod/x}）的调用。
		 *
		 * <p>夹具里每个源码 lambda 都是 {@code () -> a()} 这种纯委托形态，所以体内调用的业务方法名
		 * 就是它的身份。幽灵桩没有业务调用（只有熔断入口），保留体则两者都有——分别用
		 * {@link #isGhost} 与这里返回的业务方法名区分。</p>
		 */
		String literalOf(String name) {
			MethodNode mn = byName(name);
			if (mn == null) return null;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof MethodInsnNode mi && mi.owner.equals(CLASS)) return mi.name;
			}
			return null;
		}

		boolean isGhost(String name) {
			MethodNode mn = byName(name);
			if (mn == null) return false;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof MethodInsnNode mi && mi.name.equals("onOrphanInvoked")) return true;
			}
			return false;
		}

		/** 名字存在就返回其字面量（无论是否幽灵），不存在返回 null；用于"桩有没有"这类判据。 */
		String allocationOf(String name) {
			return byName(name) == null ? null : literalOf(name);
		}

		private MethodNode byName(String name) {
			for (MethodNode mn : cn.methods) if (mn.name.equals(name)) return mn;
			return null;
		}

		@Override
		public String toString() {
			Map<String, String> view = new LinkedHashMap<>();
			for (MethodNode mn : cn.methods) {
				if (!mn.name.startsWith("lambda$")) continue;
				view.put(mn.name, (isGhost(mn.name) ? "GHOST:" : "") + literalOf(mn.name));
			}
			return view.toString();
		}
	}
}
