import remold.LambdaAligner;
import remold.vcs.AlignmentMetaStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR3 多轮连续热更夹具：四轮（{@code v1 -> v2 -> v3 -> v4 -> v5}），每轮的旧侧都是
 * <b>上一轮真正进 JVM 的字节</b>，因此第二轮之后每轮的 {@code E_prev} 里都真的带着上一轮注入的
 * 幽灵桩 / 保留体 / 捕获桥。
 *
 * <p><b>为什么要多轮</b>：既有夹具（{@link Pr3RawAlignmentScenarioTest}）最多两代，且每种污染
 * 单独出现。真实工程里幽灵、保留体、捕获桥是<b>累积</b>的：后一轮的继承集必须同时枚举到
 * 前面几轮注入的幽灵、保留体与桥。这类"污染里的污染"没有用例走过。</p>
 *
 * <p><b>污染是怎么产生的（每轮新增一种）</b>：</p>
 * <pre>
 *   v1 -&gt; v2  捕获表缩水（(String)V -&gt; ()V）   -&gt; 捕获桥
 *   v2 -&gt; v3  删掉 void lambda gag()           -&gt; 幽灵桩（默认值桩 + LDC 生前指纹标记）
 *   v3 -&gt; v4  删掉非 void lambda rv1()         -&gt; 保留体（旧方法体 + 3 参熔断入口标记）
 *   v4 -&gt; v5  只改一个活体方法体（b -&gt; bMod）
 * </pre>
 * <p>v3 还在 {@code run} 头部插入了一个新 lambda，所以活体槽位整体位移——名字只能靠
 * Raw ↔ Raw 的槽位身份 + 翻译表落到位的，位置兜底必然出错。</p>
 *
 * <p><b>终局判据</b>（都落在最后一轮产物的可观测语义上）：</p>
 * <ol>
 *   <li>把最后一轮产物真正<b>加载并执行</b>：新 CallSite 造出的 lambda 必须跑出 v5 的方法体
 *       （{@code trace} 序列），说明产物可校验、可加载、绑定正确——不只是"字节看起来对"；</li>
 *   <li>活体名不许换主人：{@code eff5} 里每个生效侧名字承载的方法体与上一轮同一槽位一致
 *       （字节码 + 运行期双重确认）；</li>
 *   <li>幽灵桩必须跨轮存活且身份不变：{@code ghostOriginalHash} 在后几代产物里逐代相等
 *       （LDC 生前指纹标记仍在，下一轮仍可被认领）；</li>
 *   <li>保留体必须跨轮搬运且仍指向旧方法体（3 参熔断入口 + 旧体内的业务调用都在）；</li>
 *   <li>捕获桥必须跨轮存在、仍转发到同一语义目标（运行期调用 {@code capHost("payload")} 得到的
 *       是"丢弃已删捕获后"的 {@code cs:k}，而不是老实例语义的 {@code cs:payload}）；</li>
 *   <li>每一轮 Run R 的读数：{@code runRGate=PASSED} / {@code baselineTree=RAW_PREV} /
 *       {@code positionalFallbacks == 0}——配对必须来自证据，不是位置兜底。</li>
 * </ol>
 *
 * <p><b>对照组</b>：javac 产物里既没有 {@code onOrphanInvoked} 也没有 {@code onBridgeInvoked}
 * （{@code fixture premise} 段逐版本断言），因此第 3~5 条断言的证据来源只能是
 * {@code E_prev} 的继承集，不可能是"顺着新字节冒出来的"。</p>
 *
 * <p><b>变异锚点</b>：让 Run R 的继承集不从 {@code E_prev} 枚举（改成拿 {@code R_prev} 当继承源）
 * → 幽灵桩与捕获桥都会消失，第 3/5 条断言必红。</p>
 */
public class Pr3MultiRoundHotSwapTest {

	private static final String CLASS = "test/MRCase";


	/**
	 * 捕获桥的老键描述符：v1 的 lambda 有一个捕获，v2 起捕获被删掉。
	 *
	 * <p>名字与槽号<b>刻意不硬编码</b>：javac 的 {@code lambda$host$N} 编号口径随版本不同
	 * （有的按宿主方法各自计数，有的按整个类计数），同一个夹具换个 javac 就会换名字。
	 * 因此本用例全部按"方法体承载的业务调用"动态发现键，只把描述符变化钉死——
	 * 要证明的是"同名不许换主人"，不是"名字必须是 $N"。</p>
	 */
	private static final String CAP_OLD_DESC = "(Ljava/lang/String;)V";

	@BeforeEach
	@AfterEach
	void resetState() {
		LambdaAligner.META_SOURCE = false;
		LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		LambdaAligner.LAST_RESULT = null;
		LambdaAligner.clearLoggedWarnings();
		LambdaAligner.TEST_EVENTS.clear();
		AlignmentMetaStore.INSTANCE.reset();
	}

	@Test
	void fourRoundsCarryGhostRetainedBodyAndBridgeFromEffectiveBytes(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[][] raw = new byte[6][];
		for (int v = 1; v <= 5; v++) raw[v] = compile(javac, tmp, "v" + v, v);

		// ---- 前置基线 1：javac 产物里的槽位身份（按方法体发现，不依赖 javac 的编号口径） ----
		Bytecode r1 = new Bytecode(raw[1]);
		assertNotNull(r1.keyCarrying("a"), "fixture premise: v1 raw must contain the a-lambda");
		assertNotNull(r1.keyCarrying("b"), "fixture premise: v1 raw must contain the b-lambda");
		assertNotNull(r1.keyCarrying("g"), "fixture premise: v1 raw must contain the g-lambda");
		assertNotNull(r1.keyCarrying("rv1"), "fixture premise: v1 raw must contain the rv1-lambda");
		String rawCapKey = r1.keyCarrying("cs");
		assertNotNull(rawCapKey, "fixture premise: v1 raw must contain the cs-lambda (the bridge source)");
		assertTrue(rawCapKey.endsWith(CAP_OLD_DESC),
			"fixture premise: the bridge source must take exactly one capture, got " + rawCapKey);

		Bytecode r3 = new Bytecode(raw[3]);
		assertNotNull(r3.keyCarrying("x"), "fixture premise: v3 raw must contain the front-inserted x-lambda");

		// ---- 前置基线 2（对照组前提）：javac 产物里没有任何注入物 ----
		for (int v = 1; v <= 5; v++) {
			Bytecode rv = new Bytecode(raw[v]);
			assertFalse(rv.anyCall("onOrphanInvoked"),
				"fixture premise: javac output of v" + v + " has no injected orphan stub");
			assertFalse(rv.anyCall("onBridgeInvoked"),
				"fixture premise: javac output of v" + v + " has no capture bridge");
		}

		LambdaAligner.META_SOURCE = true;
		ClassLoader loader = new ClassLoader() { };

		// ============================ 第 1 轮：v1 -> v2，捕获表缩水 -> 建桥 ============================
		byte[] eff2 = align(raw[1], raw[2], loader);
		LambdaAligner.AlignmentResult res1 = LambdaAligner.LAST_RESULT;
		assertNotNull(res1, "round 1 must publish an alignment result");
		assertEquals(1, res1.bridgeCount,
			"fixture premise: dropping the only capture must build exactly one capture bridge, got "
				+ res1.bridgeCount);
		commit(loader, raw[2], eff2, res1);

		Bytecode e2 = new Bytecode(eff2);
		String aKey = e2.keyCarrying("a");
		String bKey = e2.keyCarrying("b");
		String gKey = e2.keyCarrying("g");
		String rvKey = e2.keyCarrying("rv1");
		String csKey = e2.keyCarrying("cs");
		assertNotNull(aKey, "fixture premise: eff2 must contain the a-lambda, got " + e2);
		assertNotNull(bKey, "fixture premise: eff2 must contain the b-lambda, got " + e2);
		assertNotNull(gKey, "fixture premise: eff2 must contain the g-lambda, got " + e2);
		assertNotNull(rvKey, "fixture premise: eff2 must contain the rv1-lambda, got " + e2);
		assertNotNull(csKey, "fixture premise: eff2 must contain the cs-lambda, got " + e2);
		assertEquals(1, e2.bridgeKeys().size(),
			"fixture premise: the dropped-capture lambda must survive as exactly one bridge, got " + e2);
		String bridgeKey = e2.bridgeKeys().get(0);
		assertEquals(rawCapKey, bridgeKey,
			"the bridge key is what old CallSites resolve: it must equal the source lambda's key");
		assertEquals(csKey, e2.bridgeTarget(bridgeKey),
			"fixture premise: the bridge must forward to this round's live cs-lambda, got " + e2);

		// ============================ 第 2 轮：v2 -> v3，删 void lambda -> 幽灵桩 ============================
		byte[] eff3 = align(eff2, raw[3], loader);
		LambdaAligner.AlignmentResult res2 = assertRunRTookOver("round 2 (front-inserted lambda + deleted void lambda)");
		assertEquals(1, res2.metaGeneration,
			"round 2 must read generation 1 from meta, got " + res2.metaGeneration);
		commit(loader, raw[3], eff3, res2);

		Bytecode e3 = new Bytecode(eff3);
		assertEquals(aKey, e3.keyCarrying("a"),
			"round 2 must not hand the a-slot to another lambda, got " + e3);
		assertEquals(bKey, e3.keyCarrying("b"),
			"round 2 must not hand the b-slot to another lambda, got " + e3);
		assertEquals(rvKey, e3.keyCarrying("rv1"),
			"round 2 must not hand the rv1-slot to another lambda, got " + e3);
		assertTrue(e3.isGhost(gKey),
			"the deleted void lambda must survive as an inherited ghost stub under its own key, got " + e3);
		assertFalse(e3.isRetainedBody(gKey),
			"a void orphan keeps the default-value stub (remold.agent.orphan_retain_void=false), got " + e3);
		long ghostHash = e3.ghostHash(gKey);
		assertNotEquals(0L, ghostHash,
			"the ghost stub must carry its pre-mortem fingerprint marker (LDC <long>; POP2)");
		// 桥必须跨轮存活（它不在 R_prev 里，只能来自 E_prev 继承）。
		assertTrue(e3.isBridge(bridgeKey),
			"round 2 must inherit the capture bridge from E_prev, got " + e3);
		assertEquals(csKey, e3.bridgeTarget(bridgeKey),
			"the inherited bridge must still forward to the same target, got " + e3);
		// v3 插入的新 lambda 只能拿新槽位，不能抢任何活体名。
		String xKey = e3.keyCarrying("x");
		assertNotNull(xKey, "the front-inserted x-lambda must survive as a live method, got " + e3);
		for (String live : new String[] { aKey, bKey, rvKey, gKey, csKey }) {
			assertNotEquals(live, xKey,
				"the new x-lambda must take a fresh slot, not the live key " + live + ", got " + e3);
		}

		// ============================ 第 3 轮：v3 -> v4，删非 void lambda -> 保留体 ============================
		byte[] eff4 = align(eff3, raw[4], loader);
		LambdaAligner.AlignmentResult res3 = assertRunRTookOver("round 3 (deleted non-void lambda)");
		assertEquals(2, res3.metaGeneration,
			"round 3 must read generation 2 from meta, got " + res3.metaGeneration);
		commit(loader, raw[4], eff4, res3);

		Bytecode e4 = new Bytecode(eff4);
		assertEquals(aKey, e4.keyCarrying("a"), "round 3 must not hand the a-slot to another lambda, got " + e4);
		assertEquals(bKey, e4.keyCarrying("b"), "round 3 must not hand the b-slot to another lambda, got " + e4);
		assertEquals(xKey, e4.keyCarrying("x"), "round 3 must not hand the x-slot to another lambda, got " + e4);
		assertEquals(ghostHash, e4.ghostHash(gKey),
			"the ghost fingerprint must not drift between rounds, got " + e4);
		assertTrue(e4.isRetainedBody(rvKey),
			"the deleted non-void lambda must be retained as a body-carrying stub under its own key, got " + e4);
		assertEquals("rv1", e4.bodyOf(rvKey),
			"the retained body must still be the old rv1 body, got " + e4);
		assertTrue(e4.isBridge(bridgeKey),
			"round 3 must still inherit the capture bridge, got " + e4);
		assertEquals(csKey, e4.bridgeTarget(bridgeKey),
			"the capture bridge must still point at the same semantic target, got " + e4);
		long retainedHash = e4.ghostHash(rvKey);
		assertNotEquals(0L, retainedHash,
			"the retained body must carry the ghost identity marker of the removed lambda");

		// ============================ 第 4 轮：v4 -> v5，只改活体方法体 ============================
		byte[] eff5 = align(eff4, raw[5], loader);
		LambdaAligner.AlignmentResult res4 = assertRunRTookOver("round 4 (live body tweak)");
		assertEquals(3, res4.metaGeneration,
			"round 4 must read generation 3 from meta, got " + res4.metaGeneration);
		commit(loader, raw[5], eff5, res4);

		Bytecode e5 = new Bytecode(eff5);
		// ---- 终局 A：活体名 -> 方法体（字节码口径） ----
		assertEquals(aKey, e5.keyCarrying("a"),
			"round 4 must not hand the a-slot to another lambda, got " + e5);
		assertEquals(bKey, e5.keyCarrying("bMod"),
			"round 4 must bind the b-slot to its edited body (same source slot, new body), got " + e5);
		assertEquals(xKey, e5.keyCarrying("x"),
			"round 4 must not hand the x-slot to another lambda, got " + e5);
		assertFalse(e5.isGhost(bKey),
			"the b-slot must stay a live method, got " + e5);

		// ---- 终局 B：幽灵 / 保留体 / 桥跨四轮不变 ----
		assertTrue(e5.isGhost(gKey),
			"the ghost stub must survive four rounds, got " + e5);
		assertEquals(ghostHash, e5.ghostHash(gKey),
			"the ghost's pre-mortem fingerprint must be identical across the later generations "
				+ "(still claimable next round), got " + e5);
		assertTrue(e5.isRetainedBody(rvKey),
			"the retained body must be carried into v5, got " + e5);
		assertEquals("rv1", e5.bodyOf(rvKey),
			"the carried retained body must still be the old rv1 body, got " + e5);
		assertEquals(retainedHash, e5.ghostHash(rvKey),
			"the retained body must keep its ghost identity across rounds, got " + e5);
		assertTrue(e5.isBridge(bridgeKey),
			"the capture bridge must survive four rounds, got " + e5);
		assertEquals(csKey, e5.bridgeTarget(bridgeKey),
			"the capture bridge must still point at the same target key, got " + e5);
		assertEquals(csKey, e5.keyCarrying("cs"),
			"the bridge target must still be the live cs-lambda, got " + e5);

		// ---- 终局 C：把最后一轮产物真正加载并执行（运行期口径） ----
		Class<?> loaded = new BytesLoader().define(eff5);
		Object instance = loaded.getDeclaredConstructor().newInstance();
		List<Runnable> callSites = new ArrayList<>();
		List<Supplier<String>> suppliers = new ArrayList<>();
		loaded.getMethod("run", List.class, List.class).invoke(instance, callSites, suppliers);
		for (Runnable r : callSites) r.run();
		assertEquals("xabMod", staticField(loaded, "trace"),
			"the aligned class must be loadable and its new CallSites must run the v5 bodies in order");
		assertTrue(suppliers.isEmpty(),
			"the removed Supplier lambda must not come back as a live CallSite in v5");

		// 桥的终局语义：老 CallSite 认的是**老键**（一个捕获的 (String)V）。直接按老签名调用桥，
		// 它必须丢弃那个已被删除的捕获、转发到"不再读 s"的新方法体上（cs:k），
		// 而不是老实例语义（cs:payload）——否则老隐藏类拿到的就是过期的捕获值。
		Method bridge = loaded.getDeclaredMethod(nameOf(bridgeKey), String.class);
		bridge.setAccessible(true);
		bridge.invoke(Modifier.isStatic(bridge.getModifiers()) ? null : instance, "payload");
		assertEquals("cs:k", staticField(loaded, "last"),
			"the inherited bridge must forward to the live body that ignores the dropped capture");

		// 幽灵桩的终态：老 CallSite 必须能解析到这个方法（否则就是 NoSuchMethodError）。
		loaded.getDeclaredMethod(nameOf(gKey));
	}

	private static String nameOf(String key) {
		return key.substring(0, key.indexOf('('));
	}

	// ------------------------------------------------------------------------------------------------
	// 夹具与工具
	// ------------------------------------------------------------------------------------------------

	/** 第 {@code version} 版的 {@code capHost} 方法体：v1 有一个捕获，v2 起捕获被删掉。 */
	private static String capHostBody(int version) {
		return version == 1
			? "    public void capHost(String s) { r = () -> cs(s); }\n"
			: "    public void capHost(String s) { r = () -> cs(\"k\"); }\n";
	}

	/** 第 {@code version} 版的 {@code run} 方法体。 */
	private static String runBody(int version) {
		switch (version) {
			case 1:
			case 2:
				return "        list.add(() -> a());\n"
					+ "        list.add(() -> b());\n"
					+ "        list.add(() -> g());\n"
					+ "        sup.add(() -> rv1());\n";
			case 3:
				return "        list.add(() -> x());\n"
					+ "        list.add(() -> a());\n"
					+ "        list.add(() -> b());\n"
					+ "        sup.add(() -> rv1());\n";
			case 4:
				return "        list.add(() -> x());\n"
					+ "        list.add(() -> a());\n"
					+ "        list.add(() -> b());\n";
			default:
				return "        list.add(() -> x());\n"
					+ "        list.add(() -> a());\n"
					+ "        list.add(() -> bMod());\n";
		}
	}

	private static byte[] compile(String javac, Path tmp, String dir, int version) throws Exception {
		String src = "package test;\n"
			+ "import java.util.List;\n"
			+ "import java.util.function.Supplier;\n"
			+ "public class MRCase {\n"
			+ "    public static Runnable r;\n"
			+ "    public static String last = \"\";\n"
			+ "    public static String trace = \"\";\n"
			+ "    static void a() { trace = trace + \"a\"; }\n"
			+ "    static void b() { trace = trace + \"b\"; }\n"
			+ "    static void bMod() { trace = trace + \"bMod\"; }\n"
			+ "    static void x() { trace = trace + \"x\"; }\n"
			+ "    static void g() { trace = trace + \"g\"; }\n"
			+ "    static String rv1() { return \"rv1\"; }\n"
			+ "    static void cs(String s) { last = \"cs:\" + s; }\n"
			+ capHostBody(version)
			+ "    public void run(List<Runnable> list, List<Supplier<String>> sup) {\n"
			+ runBody(version)
			+ "    }\n"
			+ "}\n";
		Path srcDir = Files.createDirectories(tmp.resolve("src-" + dir));
		Path outDir = Files.createDirectories(tmp.resolve("out-" + dir));
		Path f = srcDir.resolve("MRCase.java");
		Files.writeString(f, src, StandardCharsets.UTF_8);
		// -g：带上 LocalVariableTable，让捕获分析拿到形参名（对齐器的强证据）。
		HstestFixtures.compile(javac, outDir, List.of("-g"), f.toFile());
		return Files.readAllBytes(outDir.resolve("test/MRCase.class"));
	}

	private static LambdaAligner.AlignmentResult assertRunRTookOver(String what) {
		LambdaAligner.AlignmentResult result = LambdaAligner.LAST_RESULT;
		assertNotNull(result, what + ": align must publish a result");
		assertEquals(LambdaAligner.BaselineTree.RAW_PREV, result.baselineTree,
			what + " must pair against R_prev, got baselineTree=" + result.baselineTree);
		assertEquals(LambdaAligner.RunRGate.PASSED, result.runRGate,
			what + " must pass every Run R gate, got runRGate=" + result.runRGate);
		assertEquals(0, result.positionalFallbacks,
			what + " must pair from evidence only, got positionalFallbacks=" + result.positionalFallbacks);
		assertFalse(result.rawToEffKeys.isEmpty(),
			what + " must publish a non-empty Raw->Eff translation table");
		return result;
	}

	private static byte[] align(byte[] oldBytes, byte[] newBytes, ClassLoader loader) {
		LambdaAligner.LAST_RESULT = null;
		return LambdaAligner.align(oldBytes, newBytes, null, null, null, loader);
	}

	/** 显式翻译表落库（生产路径由 {@code RemoldAgent} 传值，不依赖 align-后-立即-commit 的时序）。 */
	private static void commit(ClassLoader loader, byte[] preAlign, byte[] effective,
	                           LambdaAligner.AlignmentResult r) {
		assertNotNull(r, "commit needs the alignment result of this generation");
		AlignmentMetaStore.INSTANCE.commit(loader, CLASS, preAlign, effective, effective,
			r.inventedNames, LambdaAligner.scanGhostOriginHashes(effective),
			r.methodMapping == null ? Map.of() : r.methodMapping, r.bridgeCount, r.rawToEffKeys);
	}

	private static String staticField(Class<?> cls, String name) throws Exception {
		Field f = cls.getField(name);
		return (String) f.get(null);
	}

	/** 从字节码定义类（夹具不参与测试类路径，只能自己 define）。 */
	private static final class BytesLoader extends ClassLoader {
		Class<?> define(byte[] bytes) {
			return defineClass(null, bytes, 0, bytes.length);
		}
	}

	/** 产物字节码的可读视图：方法体身份、幽灵/保留体/桥形态、桥的转发目标。 */
	private static final class Bytecode {
		private final ClassNode cn;
		private final Map<String, Long> ghostHashes;

		Bytecode(byte[] bytes) {
			cn = new ClassNode();
			new ClassReader(bytes).accept(cn, 0);
			ghostHashes = LambdaAligner.scanGhostOriginHashes(bytes);
		}

		MethodNode method(String name, String desc) {
			for (MethodNode mn : cn.methods) if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
			return null;
		}

		String descOf(String name) {
			for (MethodNode mn : cn.methods) if (mn.name.equals(name)) return mn.desc;
			return null;
		}

		/** 方法体承载的语义：体内那条指向本类业务方法的调用。 */
		String bodyOf(String name, String desc) {
			return firstOwnCall(method(name, desc));
		}

		/** 同上，键 = {@code name + desc}。 */
		String bodyOf(String key) {
			return firstOwnCall(methodOfKey(key));
		}

		/**
		 * 承载 {@code body} 的方法键（{@code name + desc}）：体内那条本类业务调用就是身份。
		 *
		 * <p>用例据此发现"这个 lambda 现在叫什么"，而不是硬编码 javac 的编号——
		 * 编号口径随 javac 版本变化（按宿主方法计数 / 按整类计数），硬编码会把断言绑到编译器版本上。</p>
		 */
		String keyCarrying(String body) {
			for (MethodNode mn : cn.methods) {
				if (body.equals(firstOwnCall(mn))) return mn.name + mn.desc;
			}
			return null;
		}

		/** 全部带桥标记（{@code LDC loc; INVOKESTATIC onBridgeInvoked}）的方法键。 */
		List<String> bridgeKeys() {
			List<String> out = new ArrayList<>();
			for (MethodNode mn : cn.methods) {
				if (hasCall(mn, "onBridgeInvoked", null)) out.add(mn.name + mn.desc);
			}
			return out;
		}

		/** 默认值幽灵桩：2 参 {@code onOrphanInvoked(String,Z)V}。 */
		boolean isGhost(String key) {
			return hasCall(methodOfKey(key), "onOrphanInvoked", "(Ljava/lang/String;Z)V");
		}

		/** 保留体：3 参 {@code onOrphanInvoked(String,ZZ)V}。 */
		boolean isRetainedBody(String key) {
			return hasCall(methodOfKey(key), "onOrphanInvoked", "(Ljava/lang/String;ZZ)V");
		}

		boolean isBridge(String key) {
			return hasCall(methodOfKey(key), "onBridgeInvoked", "(Ljava/lang/String;)V");
		}

		/** 桥的委托目标键（{@code onBridgeInvoked} 之后那条核心调用的 {@code name+desc}）。 */
		String bridgeTarget(String key) {
			MethodNode mn = methodOfKey(key);
			if (mn == null) return null;
			MethodInsnNode core = null;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof MethodInsnNode mi && !mi.name.equals("onBridgeInvoked")) core = mi;
			}
			return core == null ? null : core.name + core.desc;
		}

		long ghostHash(String key) {
			Long h = ghostHashes.get(key);
			return h == null ? 0L : h;
		}

		/** 整类里是否有对 {@code callee} 的调用（任何描述符）。 */
		boolean anyCall(String callee) {
			for (MethodNode mn : cn.methods) {
				if (hasCall(mn, callee, null)) return true;
			}
			return false;
		}

		private MethodNode methodOfKey(String key) {
			int p = key.indexOf('(');
			return method(key.substring(0, p), key.substring(p));
		}

		private String firstOwnCall(MethodNode mn) {
			if (mn == null) return null;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof MethodInsnNode mi && mi.owner.equals(CLASS)
					&& !mi.name.equals("onOrphanInvoked") && !mi.name.equals("onBridgeInvoked")) {
					return mi.name;
				}
			}
			return null;
		}

		private static boolean hasCall(MethodNode mn, String callee, String desc) {
			if (mn == null) return false;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof MethodInsnNode mi && mi.name.equals(callee)
					&& (desc == null || desc.equals(mi.desc))) {
					return true;
				}
			}
			return false;
		}

		@Override
		public String toString() {
			Map<String, String> view = new LinkedHashMap<>();
			for (MethodNode mn : cn.methods) {
				String key = mn.name + mn.desc;
				boolean lambdaish = mn.name.startsWith("lambda$") || hasCall(mn, "onOrphanInvoked", null)
					|| hasCall(mn, "onBridgeInvoked", null);
				if (!lambdaish) continue;
				String kind = isBridge(key) ? "BRIDGE:"
					: isRetainedBody(key) ? "RETAINED:"
					: isGhost(key) ? "GHOST:" : "";
				view.put(key, kind + firstOwnCall(mn));
			}
			return view.toString();
		}
	}
}
