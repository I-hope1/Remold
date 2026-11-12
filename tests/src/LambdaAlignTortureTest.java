import remold.LambdaAligner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * lambda 对齐压力测试（自动化版）。
 *
 * <p><b>判据</b>：老 callsite 是按<b>方法名</b>绑定到合成方法上的。重定义之后，旧实例再调用
 * {@code lambda$build$N} 时，命中的是<b>新类里同名方法</b>。所以"编号错位"的故障形态是
 * "同一个名字指向了别人的 body"——这纯字节码可判，不需要真的 reload：
 * 从旧类解析每个 {@code lambda$*} 的逻辑身份，再在 {@code LambdaAligner.align} 的产物里
 * 按同名方法取身份，两者必须相等。</p>
 *
 * <p><b>逻辑身份</b>由 lambda 体内的 tag 常量给出（{@code "A:v1"} → {@code A}，
 * 字符串拼接的 recipe {@code "B:v1:\u0001"} 同样取 {@code B}）。容器 lambda 自身没有 tag，
 * 用"它包着哪个 tag"（{@code via:D}）来定身份。版本号（{@code v1}/{@code v2}）不参与比较：
 * 改 body 是合法的，改名字才不是。</p>
 *
 * <p><b>矩阵</b>（每个变体都跑 JDK 8/17/21 的 javac，因为嵌套 lambda 的命名规则不同）：</p>
 * <ul>
 *   <li>V2 只改 body —— 基线，名字不该动；</li>
 *   <li>V3 在 {@code build()} 最前面插入新 lambda —— 编号整体后移，没对齐时 A/B/C 会串 body；</li>
 *   <li>V4 删掉 B、交换 C/D 顺序 —— 删除应当留下孤儿桩，重排必须还原。</li>
 * </ul>
 *
 * <p>另外覆盖两个此前没有测试碰过的载体：静态初始化 {@code lambda$static$N} 与
 * 字段初始化 {@code lambda$new$N}。</p>
 */
public class LambdaAlignTortureTest {

	@Test
	void oldCallsitesKeepTheirOwnBodyAcrossEdits(@TempDir Path tmp) throws Exception {
		for (int major : new int[] { 8, 17, 21 }) {
			String javac = HstestFixtures.javac(major);
			Path v1 = compile(javac, tmp, "jdk" + major + "/v1", src("v1", false, false, false));
			Path v2 = compile(javac, tmp, "jdk" + major + "/v2", src("v2", false, false, false));
			Path v3 = compile(javac, tmp, "jdk" + major + "/v3", src("v3", true, false, false));
			Path v4 = compile(javac, tmp, "jdk" + major + "/v4", src("v4", false, true, true));

			byte[] base = Files.readAllBytes(v1.resolve("Torture.class"));

			checkPair("JDK" + major + " V2(只改 body)", base, Files.readAllBytes(v2.resolve("Torture.class")), 8);
			checkPair("JDK" + major + " V3(前插 Z)", base, Files.readAllBytes(v3.resolve("Torture.class")), 8);
			checkPair("JDK" + major + " V4(删 B + 交换 C/D)", base, Files.readAllBytes(v4.resolve("Torture.class")), 7);
		}
	}

	/**
	 * 自检：把产物里两个 lambda 的名字对调（模拟"编号错位"），身份比较必须变红。
	 *
	 * <p>没有这条，{@link #checkAligned} 里那条 {@code assertEquals(id, identity(...))} 到底
	 * 有没有判定力就只能靠人读代码猜——它也可能因为身份解析退化成 {@code null == null} 而恒绿。</p>
	 */
	@Test
	void checkerDetectsSwappedBodies(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		Path v1 = compile(javac, tmp, "self/v1", src("v1", false, false, false));
		Path v3 = compile(javac, tmp, "self/v3", src("v3", true, false, false));

		byte[] base = Files.readAllBytes(v1.resolve("Torture.class"));
		byte[] newBytes = Files.readAllBytes(v3.resolve("Torture.class"));
		byte[] aligned = LambdaAligner.align(base, newBytes);

		byte[] swapped = swapTwoLambdaNames(aligned);
		AssertionError err = assertThrows(AssertionError.class,
			() -> checkAligned("自检(名字对调)", parse(base), parse(newBytes), parse(swapped), 8));
		assertTrue(err.getMessage() != null && err.getMessage().contains("换了 body"),
			"自检必须打在身份比较那条断言上（实际消息: " + err.getMessage() + "）");
	}

	/**
	 * 定点用例：Step 1 证据匹配、保留槽位、跨组指纹认领、Pass A/B 深度。
	 *
	 * <p>每个变体都从同一份 {@code Stress} v1 出发，检查"旧名字仍绑在同一个逻辑 lambda 上"。</p>
	 */
	@Test
	void targetedCasesKeepOldSlots(@TempDir Path tmp) throws Exception {
		for (int major : new int[] { 8, 21 }) {
			String javac = HstestFixtures.javac(major);
			byte[] base = Files.readAllBytes(
				compile(javac, tmp, "s" + major + "/v1", stressSrc("v1", "BASE")).resolve("Stress.class"));

			checkPair("JDK" + major + " S1-a(g1 前插 Z)", base, bytesOf(javac, tmp, "s" + major + "/s1a", "S1A"), 12);
			checkPair("JDK" + major + " RS-p(只删 A2)", base, bytesOf(javac, tmp, "s" + major + "/rsp", "RSP"), 12);
			checkPair("JDK" + major + " XG-a(宿主 g4 改名 g4x)", base, bytesOf(javac, tmp, "s" + major + "/xga", "XGA"), 12);
			checkPair("JDK" + major + " PA-a(B1 下沉一层)", base, bytesOf(javac, tmp, "s" + major + "/paa", "PAA"), 12);
		}
	}

	/**
	 * L5 的第二形态：删掉 {@code A2} 的同时追加一个同描述符的 {@code A9}。
	 *
	 * <p><b>红基线（修复前）</b>：Pass A/B 只校验签名、子树形状与子归属，<b>不校验方法体</b>；
	 * 删一个、加一个之后，{@code A9} 在旧组里按位置找到了形状相同的 {@code A2}，
	 * "名字没变"被当成"原地更新"，{@code warnPositionalMismatch} 也不响。终局是 A2 的槽位
	 * 被 A9 静默顶替、没有幽灵桩——老 CallSite 跑进别人的 body。</p>
	 *
	 * <p><b>修复后（fail-closed）</b>：两条证据都不成立——方法体不等价（常量值从
	 * {@code "A2:v1"} 变成 {@code "A9:v1"}），骨架在全类范围内不唯一（A1/A2/A3 同为
	 * "返回常量"），且该候选与 Step 1 已定案的锚点（A1/A3/A4/A5 指纹配对）<b>顺序交叉</b>
	 * （A2 在旧序列里排第 2，A9 在新序列里排第 5）。判据拒绝配对并打 {@code BODY_MISMATCH}：
	 * A2 的槽位留幽灵桩，A9 拿自造名。</p>
	 *
	 * <p><b>与"纯粹原地改常量"的区别</b>：{@code "A2:v1"→"A2:v2"} 两侧同名、同宿主，
	 * 走"名字锚定"那档证据直接放行（见 {@code alteredConstantsKeepSlotsWithoutWarn}）；
	 * 这里名字换了主人，才必须拒绝。</p>
	 */
	@Test
	void deletedSlotLeavesGhostInsteadOfBeingReused(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] base = Files.readAllBytes(
			compile(javac, tmp, "lim/v1", stressSrc("v1", "BASE")).resolve("Stress.class"));
		ClassNode oldCn = parse(base);
		byte[] next = bytesOf(javac, tmp, "lim/rsa", "RSA");
		ClassNode nextCn = parse(next);

		MethodNode a2 = byKey(oldCn, "lambda$g1$1", "()Ljava/lang/String;");
		MethodNode a3 = byKey(oldCn, "lambda$g1$2", "()Ljava/lang/String;");
		assertNotNull(a2, "夹具前提：v1 里 A2 占 lambda$g1$1");
		assertNotNull(a3, "夹具前提：v1 里 A3 占 lambda$g1$2");
		assertEquals("A2", identity(oldCn, a2, 0));
		assertEquals("A3", identity(oldCn, a3, 0));

		LambdaAligner.TEST_EVENTS.clear();
		ClassNode aligned = parse(LambdaAligner.align(base, next));

		// 1) A2 的槽位必须留幽灵桩（老 CallSite 响亮熔断），而不是被 A9 顶替。
		MethodNode slot1 = byKey(aligned, "lambda$g1$1", "()Ljava/lang/String;");
		assertNotNull(slot1, "老 A2 的槽位整个消失了（更糟的形态）：" + table(aligned));
		assertTrue(isGhost(slot1),
			"删掉的 A2 必须留幽灵桩，不能被别的 lambda 静默顶替（" + table(aligned) + "）");

		// 2) 必须留痕：BODY_MISMATCH 告警点名 A2 ← A9。
		boolean warned = false;
		for (LambdaAligner.AlignmentEvent e : LambdaAligner.TEST_EVENTS) {
			if ("BODY_MISMATCH".equals(e.kind) && "lambda$g1$1".equals(e.oldName)) warned = true;
		}
		assertTrue(warned, "拒绝配对必须打 BODY_MISMATCH 告警（事件: "
			+ LambdaAligner.TEST_EVENTS.size() + " 条）");

		// 3) A9 活着、拿到自造名；A1/A3 的身份不变（只有"多余那一对"被拒绝）。
		assertTrue(allIds(aligned).containsAll(Set.of("A1", "A3", "A4", "A5", "A9")),
			"身份集合缺项（" + table(aligned) + "）");
		assertEquals(Set.of("A1", "A3", "A4", "A5", "A9", "B1", "B2", "B2in", "C1", "C2", "C3", "D1"),
			allIds(aligned), "产物身份集合必须与新类一致（" + table(aligned) + "）");
		assertFalse(LambdaAligner.LAST_RESULT.inventedNames.isEmpty(),
			"A9 必须拿自造槽位（否则它占了某个旧名）");
		assertNoDanglingIndy("lim", aligned);
	}

	/** 同一对 (旧类, 新类) 反复对齐必须逐字节一致——不能依赖 HashMap 迭代顺序。 */
	@Test
	void alignmentIsDeterministic(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] base = Files.readAllBytes(
			compile(javac, tmp, "det/v1", stressSrc("v1", "BASE")).resolve("Stress.class"));
		byte[] next = bytesOf(javac, tmp, "det/s1a", "S1A");

		byte[] first = LambdaAligner.align(base, next);
		for (int i = 2; i <= 10; i++) {
			assertArrayEquals(first, LambdaAligner.align(base, next),
				"第 " + i + " 次对齐与第 1 次不一致，说明结果依赖了非确定性的遍历顺序");
		}
	}

	/**
	 * 计数账要平：每个旧 lambda 要么被某一趟配对（{@code step1 + passA + passB}），
	 * 要么进幽灵。{@code hashMismatchPairs} / {@code consumerMismatchPairs} 是配对的**叠加标记**，
	 * 不是独立的一趟，不能加进这个和。
	 */
	@Test
	void statsAccountForEveryOldLambda(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		Path v1 = compile(javac, tmp, "acc/v1", stressSrc("v1", "BASE"));
		byte[] base = Files.readAllBytes(v1.resolve("Stress.class"));
		byte[] next = bytesOf(javac, tmp, "acc/s1a", "S1A");

		int oldLambdas = 0;
		for (MethodNode mn : parse(base).methods) if (mn.name.startsWith("lambda$")) oldLambdas++;

		LambdaAligner.LAST_RESULT = null;
		ClassNode aligned = parse(LambdaAligner.align(base, next));
		LambdaAligner.AlignmentResult r = LambdaAligner.LAST_RESULT;
		assertNotNull(r, "对齐结果未记录");
		LambdaAligner.AlignmentStats s = r.stats;
		assertNotNull(s, "stats 未记录");

		int matched = s.step1Pairs + s.passAPairs + s.passBPairs;
		int ghosts = 0;
		for (MethodNode mn : aligned.methods) if (isGhost(mn)) ghosts++;
		// 第三桶：捕获桥。它既不参与配对（桥不改名），也不是幽灵桩（它真的转发）。
		int bridges = r.bridgeCount;

		assertEquals(oldLambdas, matched + ghosts + bridges,
			"旧 lambda 共 " + oldLambdas + " 个，但三趟配对合计 " + matched + " + 幽灵 " + ghosts
				+ " + 捕获桥 " + bridges
				+ " 对不上（stats=" + s + "，方法表 " + table(aligned) + "）");
		assertTrue(s.hashMismatchPairs <= matched,
			"hashMismatchPairs(" + s.hashMismatchPairs + ") 是配对的叠加标记，不该超过配对总数 " + matched);
	}

	/**
	 * 删除中间层（UD-a）：最内层必须保住槽位；认领不上的旧层必须留桩，不能被静默顶替。
	 *
	 * <p>实测（javac 21）：旧 {@code $0}=inner、{@code $1}=middle、{@code $2}=outer；对齐后
	 * {@code $0} 仍是 inner，新 outer 拿到了 {@code $1} 这个<b>名字</b>但用自己的描述符
	 * （{@code ()String}），而旧 middle 的原始键 {@code $1()Supplier} 与旧 outer 的 {@code $2()String}
	 * 各留了一个孤儿桩。也就是说：名字被复用，但旧 callsite 的 (名字, 描述符) 组合仍然指向桩，
	 * 不会被静默重定向——这是这里真正要守住的性质。</p>
	 */
	@Test
	void deletingMiddleLayerKeepsInnermostAndStubsTheRest(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] base = Files.readAllBytes(
			compile(javac, tmp, "uda/v1", depthSrc(true)).resolve("Depth.class"));
		byte[] next = Files.readAllBytes(
			compile(javac, tmp, "uda/v2", depthSrc(false)).resolve("Depth.class"));

		ClassNode oldCn = parse(base);
		ClassNode aligned = parse(LambdaAligner.align(base, next));

		String innerName = null;
		String middleName = null;
		String outerName = null;
		String middleDesc = null;
		String outerDesc = null;
		for (MethodNode mn : oldCn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			String id = identity(oldCn, mn, 0);
			if ("P2ii".equals(id)) innerName = mn.name;
			else if ("via:P2ii".equals(id)) { middleName = mn.name; middleDesc = mn.desc; }
			else if ("P2".equals(id)) { outerName = mn.name; outerDesc = mn.desc; }
		}
		assertNotNull(innerName, "夹具前提：v1 里必须能找到最内层 P2ii（" + table(oldCn) + "）");
		assertNotNull(middleName, "夹具前提：v1 里必须能找到中间层（" + table(oldCn) + "）");
		assertNotNull(outerName, "夹具前提：v1 里必须能找到外层 P2（" + table(oldCn) + "）");

		// 1. 最内层：shape 与旧 inner 相同、只是深度从 2 降到 1 → Pass B 必须认领回原槽位。
		MethodNode innerSlot = byKey(aligned, innerName, "()Ljava/lang/String;");
		assertNotNull(innerSlot, "旧 inner 的槽位消失了：" + table(aligned));
		assertEquals("P2ii", identity(aligned, innerSlot, 0), "旧 inner 的槽位必须仍是 P2ii：" + table(aligned));

		// 2. 认领不上的两层：原始 (名字, 描述符) 必须各留一个孤儿桩，不能静默重定向。
		MethodNode middleStub = byKey(aligned, middleName, middleDesc);
		assertNotNull(middleStub, "旧 middle 的原始键没有留桩：" + table(aligned));
		assertTrue(isGhost(middleStub), "旧 middle 的原始键必须是孤儿桩：" + table(aligned));

		MethodNode outerStub = byKey(aligned, outerName, outerDesc);
		assertNotNull(outerStub, "旧 outer 的原始键没有留桩：" + table(aligned));
		assertTrue(isGhost(outerStub), "旧 outer 的原始键必须是孤儿桩：" + table(aligned));

		// 3. 产物自洽（同名不同描述符是合法的：$1()String 与 $1()Supplier 是两个键）。
		Set<String> keys = new HashSet<>();
		for (MethodNode mn : aligned.methods) {
			assertTrue(keys.add(mn.name + mn.desc), "产物出现重复的 name+desc：" + table(aligned));
		}
		assertNoDanglingIndy("UD-a", aligned);
	}

	static String depthSrc(boolean withMiddle) {
		StringBuilder sb = new StringBuilder();
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class Depth {\n");
		sb.append("  static final Map<String, Supplier<String>> H = new LinkedHashMap<>();\n");
		sb.append("  static void reg(String k, Supplier<String> s) { H.put(k, s); }\n");
		sb.append("  void d() {\n");
		if (withMiddle) {
			sb.append("    reg(\"P2\", () -> { Supplier<Supplier<String>> i = () -> () -> \"P2ii:v1\";")
				.append(" return \"P2:v1:\" + i.get().get(); });\n");
		} else {
			sb.append("    reg(\"P2\", () -> { Supplier<String> i = () -> \"P2ii:v2\";")
				.append(" return \"P2:v2:\" + i.get(); });\n");
		}
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/**
	 * L5：体内**新增**内层 lambda（把叶子包进新容器）——旧槽位不许被静默顶替。
	 *
	 * <p><b>红基线（修复前的实测形态）</b>：旧 {@code P0 = lambda$build$0()String}，V2 里
	 * javac 后序编号让新 inner 恰好也叫 {@code lambda$build$0}；Pass B 放宽深度但保留 shape，
	 * 新 outer 的 shape {@code (())} 与旧 P0 的 {@code ()} 不等被淘汰，新 inner 的 shape 相等
	 * 于是顶替了旧槽位。<b>一条告警都没有</b>：{@code warnPositionalMismatch} 的判据是
	 * "名字变了"，这里恰好同名。老 CallSite 于是静默跑 {@code P0c} 的体。</p>
	 *
	 * <p><b>修复后的契约（fail-closed）</b>：方法体不逐条等价，且宿主形态从
	 * {@code M:build(...)} 翻成了 {@code L:<outer 指纹>}——这是"该 lambda 换了嵌套层次"的
	 * 直接证据。判据拒绝配对并打 {@code BODY_MISMATCH} WARN：旧槽位幽灵化（老 CallSite 熔断、
	 * 跑 {@code onOrphanInvoked} 并返回 null），V2 的两个 lambda 各自活下来。</p>
	 */
	@Test
	void l5AddingInnerLambdaRefusesToRebindOldSlot(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] base = Files.readAllBytes(
			compile(javac, tmp, "shc/v1", wrapSrc(false)).resolve("Wrap.class"));
		byte[] next = Files.readAllBytes(
			compile(javac, tmp, "shc/v2", wrapSrc(true)).resolve("Wrap.class"));

		// 前提：V2 的 outer 与旧 P0 描述符相同 —— 旧槽位本可以承载它（这不是类型系统逼的降级）。
		ClassNode nextCn = parse(next);
		int sameDesc = 0;
		for (MethodNode mn : nextCn.methods) {
			if (mn.name.startsWith("lambda$") && "()Ljava/lang/String;".equals(mn.desc)) sameDesc++;
		}
		assertEquals(2, sameDesc, "夹具前提：V2 的两个 lambda 都应是 ()String（" + table(nextCn) + "）");

		ClassNode oldCn = parse(base);
		LambdaAligner.TEST_EVENTS.clear();
		ClassNode aligned = parse(LambdaAligner.align(base, next));

		String oldName = null;
		for (MethodNode mn : oldCn.methods) {
			if (mn.name.startsWith("lambda$") && "P0".equals(identity(oldCn, mn, 0))) oldName = mn.name;
		}
		assertNotNull(oldName, "夹具前提：v1 里必须能找到 P0（" + table(oldCn) + "）");
		assertEquals("P0", identity(oldCn, byKey(oldCn, oldName, "()Ljava/lang/String;"), 0),
			"夹具前提：旧槽位必须承载 P0（" + table(oldCn) + "）");

		// 1) 终局：旧槽位被幽灵化 —— 老 CallSite 响亮熔断，而不是静默执行 P0c 的体。
		MethodNode slot = byKey(aligned, oldName, "()Ljava/lang/String;");
		assertNotNull(slot, "旧 P0 的槽位消失了（" + table(aligned) + "）");
		assertTrue(isGhost(slot), "L5：旧 P0 的槽位必须幽灵化（拒绝配对），实际 " + table(aligned));
		// 非 void 孤儿默认保留上一轮方法体（docs/topology/11）：幽灵仍调用 onOrphanInvoked
		// 完成策略分发/熔断，但随后执行旧逻辑而不是返回 null；老 CallSite 因此拿到合法返回值。
		assertEquals("P0", identity(aligned, slot, 0),
			"非 void 幽灵必须保留旧方法体（" + table(aligned) + "）");

		// 2) 必须留痕：BODY_MISMATCH 告警点名"旧 P0 ← 新 inner"。
		boolean warned = false;
		for (LambdaAligner.AlignmentEvent e : LambdaAligner.TEST_EVENTS) {
			if ("BODY_MISMATCH".equals(e.kind) && oldName.equals(e.oldName)) warned = true;
		}
		assertTrue(warned, "L5：拒绝配对必须打 BODY_MISMATCH 告警（事件: "
			+ LambdaAligner.TEST_EVENTS.size() + " 条）");

		// 3) fail-closed 不等于丢功能：V2 的两个 lambda 都还在，且身份各自正确。
		assertEquals(Set.of("P0", "P0c"), allIds(aligned),
			"V2 的两个 lambda 必须都活着且身份正确（" + table(aligned) + "）");
		assertNoDanglingIndy("sh_c", aligned);
	}

	static String wrapSrc(boolean wrapped) {
		StringBuilder sb = new StringBuilder();
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class Wrap {\n");
		sb.append("  public void build(Map<String, Supplier<?>> m) {\n");
		if (wrapped) {
			sb.append("    m.put(\"P0\", (Supplier<String>) () -> { Supplier<String> c = () -> \"P0c:v2\";")
				.append(" return \"P0:v2:\" + c.get(); });\n");
		} else {
			sb.append("    m.put(\"P0\", (Supplier<String>) () -> \"P0:v1\");\n");
		}
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/**
	 * L5b 回归：**删掉**内层 lambda —— <b>既有</b>告警形态（幽灵桩 + 运行期 {@code warn}/null）不得变化。
	 *
	 * <p>与 L5（体内新增内层 lambda）互为镜像，但两者的正确处置不同：删内层时外层自己还在，
	 * 只是 shape 变了、认领不上，于是旧名走幽灵桩（老 CallSite 调用时
	 * {@code onOrphanInvoked} 打 warn 并返回 null）。这条路径在修复前就<b>已经</b>是响亮的，
	 * 本用例把它钉住：新判据不许把它升级成别的形态，也不许在它上面误报 {@code BODY_MISMATCH}。</p>
	 */
	@Test
	void l5bDeletingInnerLambdaKeepsGhostBehaviour(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] base = Files.readAllBytes(
			compile(javac, tmp, "l5b/v1", depthSrc(true)).resolve("Depth.class"));
		byte[] next = Files.readAllBytes(
			compile(javac, tmp, "l5b/v2", depthSrc(false)).resolve("Depth.class"));

		ClassNode oldCn = parse(base);
		String innerName = null, middleName = null, outerName = null;
		String middleDesc = null, outerDesc = null;
		for (MethodNode mn : oldCn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			String id = identity(oldCn, mn, 0);
			if ("P2ii".equals(id)) innerName = mn.name;
			else if ("via:P2ii".equals(id)) { middleName = mn.name; middleDesc = mn.desc; }
			else if ("P2".equals(id)) { outerName = mn.name; outerDesc = mn.desc; }
		}
		assertNotNull(innerName, "夹具前提：v1 里必须能找到最内层 P2ii（" + table(oldCn) + "）");
		assertNotNull(middleName, "夹具前提：v1 里必须能找到中间层（" + table(oldCn) + "）");
		assertNotNull(outerName, "夹具前提：v1 里必须能找到外层 P2（" + table(oldCn) + "）");

		LambdaAligner.TEST_EVENTS.clear();
		ClassNode aligned = parse(LambdaAligner.align(base, next));

		// 1) 最内层保住槽位（名字 + 身份都不变）——删除内层不该让幸存者失去身份。
		MethodNode inner = byKey(aligned, innerName, "()Ljava/lang/String;");
		assertNotNull(inner, "最内层的槽位消失了（" + table(aligned) + "）");
		assertFalse(isGhost(inner), "最内层不该被幽灵化（" + table(aligned) + "）");
		assertEquals("P2ii", identity(aligned, inner, 0), "最内层必须仍承载自己的 body（" + table(aligned) + "）");

		// 2) 被删的中间层与外层各留一个幽灵桩：老 CallSite 走 onOrphanInvoked（warn + null）。
		MethodNode middleStub = byKey(aligned, middleName, middleDesc);
		MethodNode outerStub = byKey(aligned, outerName, outerDesc);
		assertNotNull(middleStub, "中间层的原始键没有留桩（" + table(aligned) + "）");
		assertNotNull(outerStub, "外层的原始键没有留桩（" + table(aligned) + "）");
		assertTrue(isGhost(middleStub), "中间层必须是幽灵桩（" + table(aligned) + "）");
		assertTrue(isGhost(outerStub), "外层必须是幽灵桩（" + table(aligned) + "）");

		Set<String> ghosted = new LinkedHashSet<>();
		boolean bodyMismatch = false;
		for (LambdaAligner.AlignmentEvent e : LambdaAligner.TEST_EVENTS) {
			if ("GHOST".equals(e.kind)) ghosted.add(e.oldName);
			if ("BODY_MISMATCH".equals(e.kind)) bodyMismatch = true;
		}
		assertTrue(ghosted.containsAll(Set.of(middleName, outerName)),
			"幽灵化必须留 GHOST 事件（实际 " + ghosted + "）");
		assertFalse(bodyMismatch, "删内层 lambda 这条路径不该触发 BODY_MISMATCH（既有行为不得变化）");
		assertNoDanglingIndy("l5b", aligned);
	}

	/**
	 * 正向对照：<b>原地改常量</b>（名字、宿主都不变）必须继续保住槽位，且不打 BODY_MISMATCH。
	 *
	 * <p>这是最常见的开发动作（同一方法里把几个字面量改一遍）。它与 L5 的区别只在
	 * "名字是否换了主人"：这里合成方法名由 javac 原地保留，因此"名字 + 宿主形态"那档
	 * 身份证据成立，不需要方法体逐条等价。</p>
	 */
	@Test
	void alteredConstantsKeepSlotsWithoutWarn(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "ac/v1", stressSrc("v1", "BASE")).resolve("Stress.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "ac/v2", stressSrc("v2", "BASE")).resolve("Stress.class"));

		ClassNode oldCn = parse(v1);
		LambdaAligner.TEST_EVENTS.clear();
		ClassNode aligned = parse(LambdaAligner.align(v1, v2));

		int checked = 0;
		for (MethodNode mn : oldCn.methods) {
			String id = identity(oldCn, mn, 0);
			if (id == null) continue;
			MethodNode hit = byKey(aligned, mn.name, mn.desc);
			assertNotNull(hit, "旧槽位 " + key(mn) + " 消失了（" + table(aligned) + "）");
			assertFalse(isGhost(hit), "旧槽位 " + key(mn) + "（" + id + "）不该被幽灵化（" + table(aligned) + "）");
			assertEquals(id, identity(aligned, hit, 0),
				"旧槽位 " + key(mn) + " 必须仍承载 " + id + "（" + table(aligned) + "）");
			checked++;
		}
		assertTrue(checked >= 8, "夹具前提：可判身份的旧 lambda 太少（" + checked + "）");
		assertEquals(0, bodyMismatchRefusals(),
			"原地改常量不该触发任何 BODY_MISMATCH 拒绝（" + LambdaAligner.LAST_RESULT.stats + "）");
	}

	/** 纯语句顺序调整（数据依赖不变）：单 lambda 宿主里改完仍是同一个槽位，且无告警。 */
	@Test
	void fpStatementReorderWithoutNameShiftKeepsSlot(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "fpa/v1", reorderSrc(false)).resolve("Reo.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "fpa/v2", reorderSrc(true)).resolve("Reo.class"));

		ClassNode oldCn = parse(v1);
		ClassNode nextCn = parse(v2);
		String oldName = null;
		for (MethodNode mn : oldCn.methods) if (mn.name.startsWith("lambda$")) oldName = mn.name;
		assertNotNull(oldName, "夹具前提：v1 应有 lambda（" + table(oldCn) + "）");

		LambdaAligner.TEST_EVENTS.clear();
		ClassNode aligned = parse(LambdaAligner.align(v1, v2));
		MethodNode hit = byKey(aligned, oldName, "()Ljava/lang/String;");
		assertNotNull(hit, "旧槽位 " + oldName + " 消失了（" + table(aligned) + "）");
		assertFalse(isGhost(hit), "纯语句重排不该把旧槽位幽灵化（" + table(aligned) + "）");
		assertEquals("PA", identity(aligned, hit, 0), "旧槽位必须仍承载自己的 body（" + table(aligned) + "）");
		assertEquals(0, bodyMismatchRefusals(),
			"纯语句重排（名字未变）不该触发 BODY_MISMATCH（" + LambdaAligner.LAST_RESULT.stats + "）");
		assertEquals(allIds(nextCn), allIds(aligned), "产物身份集合必须与新类一致（" + table(aligned) + "）");
	}

	/**
	 * 误报实测（第 1 类：纯语句顺序调整）：<b>语句重排 + 前插新 lambda</b>（名字整体位移）。
	 *
	 * <p>方法体指令序变了、javac 名字也变了，但两侧仍有可用的<b>顺序一致性</b>证据：
	 * 位置信息仍指向同一个 lambda（旧序列里 PA 之后没有别人，新序列里它后面是新来的 Z）。
	 * 判据因此放行、<b>不产生</b> {@code BODY_MISMATCH}：这条是"不该误报"的正面证据，
	 * 与 {@link #l5AddingInnerLambdaRefusesToRebindOldSlot}（该拒的必须拒）互为对照。</p>
	 */
	@Test
	void fpStatementReorderWithNameShiftKeepsSlot(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "fpb/v1", reorderPlusInsertSrc(false)).resolve("Reo2.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "fpb/v2", reorderPlusInsertSrc(true)).resolve("Reo2.class"));

		ClassNode oldCn = parse(v1);
		ClassNode nextCn = parse(v2);
		String oldName = null;
		for (MethodNode mn : oldCn.methods) {
			if (mn.name.startsWith("lambda$") && "PA".equals(identity(oldCn, mn, 0))) oldName = mn.name;
		}
		assertNotNull(oldName, "夹具前提：v1 里必须能找到 PA（" + table(oldCn) + "）");
		String newPaName = null;
		for (MethodNode mn : nextCn.methods) {
			if (mn.name.startsWith("lambda$") && "PA".equals(identity(nextCn, mn, 0))) newPaName = mn.name;
		}
		assertNotEquals(oldName, newPaName,
			"夹具前提：前插之后 PA 的 javac 名字应当位移（old=" + oldName + ", new=" + newPaName + "）");

		LambdaAligner.TEST_EVENTS.clear();
		ClassNode aligned = parse(LambdaAligner.align(v1, v2));
		MethodNode hit = byKey(aligned, oldName, "()Ljava/lang/String;");
		assertNotNull(hit, "旧槽位 " + oldName + " 消失了（" + table(aligned) + "）");
		assertFalse(isGhost(hit), "顺序证据成立时不该幽灵化旧槽位（" + table(aligned) + "）");
		assertEquals("PA", identity(aligned, hit, 0),
			"旧槽位必须仍承载 PA（" + table(aligned) + "）");
		assertEquals(0, bodyMismatchRefusals(),
			"语句重排 + 名字位移（无交叉）不该触发 BODY_MISMATCH（" + LambdaAligner.LAST_RESULT.stats + "）");
		assertEquals(allIds(nextCn), allIds(aligned), "产物身份集合必须与新类一致（" + table(aligned) + "）");
	}

	/** 纯语句重排（数据依赖不变）的夹具：声明顺序对调，结果不变。 */
	static String reorderSrc(boolean reordered) {
		StringBuilder sb = new StringBuilder();
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class Reo {\n");
		sb.append("  public void build(Map<String, Supplier<String>> m) {\n");
		if (reordered) {
			sb.append("    m.put(\"PA\", () -> { int b = 20; int a = 10; return \"PA:\" + (a * b); });\n");
		} else {
			sb.append("    m.put(\"PA\", () -> { int a = 10; int b = 20; return \"PA:\" + (a * b); });\n");
		}
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/** 上一条 + 前插一个新 lambda，让 javac 编号整体位移。 */
	static String reorderPlusInsertSrc(boolean next) {
		StringBuilder sb = new StringBuilder();
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class Reo2 {\n");
		sb.append("  public void build(Map<String, Supplier<String>> m) {\n");
		if (next) sb.append("    m.put(\"Z\", () -> \"Z:v2\");\n");
		if (next) {
			sb.append("    m.put(\"PA\", () -> { int b = 20; int a = 10; return \"PA:\" + (a * b); });\n");
		} else {
			sb.append("    m.put(\"PA\", () -> { int a = 10; int b = 20; return \"PA:\" + (a * b); });\n");
		}
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/**
	 * 归一化证据（局部槽位）：把字节码里业务方法的局部变量槽整体平移 +3。
	 *
	 * <p>指令序列、常量值、引用目标都没变，只是槽号换了一套编号（不同编译器 / 不同局部变量
	 * 分配策略的等价产物）。体指纹按"首次出现顺序"重编号，因此这类差异<b>不能</b>
	 * 被当成"方法体变了"：必须仍旧配对、且不产生 {@code BODY_MISMATCH}。</p>
	 */
	@Test
	void fpLocalSlotRenumberingIsNormalizedAway(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "fpc/v1", slotSrc()).resolve("Slot.class"));
		byte[] v2 = shiftLocalSlots(v1, 3);

		assertFalse(java.util.Arrays.equals(v1, v2), "夹具前提：槽位重编号必须真的改变了字节码");

		ClassNode oldCn = parse(v1);
		LambdaAligner.TEST_EVENTS.clear();
		ClassNode aligned = parse(LambdaAligner.align(v1, v2));

		int checked = 0;
		for (MethodNode mn : oldCn.methods) {
			String id = identity(oldCn, mn, 0);
			if (id == null) continue;
			MethodNode hit = byKey(aligned, mn.name, mn.desc);
			assertNotNull(hit, "旧槽位 " + key(mn) + " 消失了（" + table(aligned) + "）");
			assertFalse(isGhost(hit), "槽位重编号不该让旧槽位幽灵化（" + table(aligned) + "）");
			assertEquals(id, identity(aligned, hit, 0),
				"旧槽位 " + key(mn) + " 必须仍承载 " + id + "（" + table(aligned) + "）");
			checked++;
		}
		assertEquals(2, checked, "夹具前提：应有 2 个可判身份的 lambda（" + table(oldCn) + "）");
		assertEquals(0, bodyMismatchRefusals(),
			"仅槽号位移必须被归一化掉，不得算作方法体不等价（" + LambdaAligner.LAST_RESULT.stats + "）");
	}

	/** 夹具：两个 lambda，体内都有局部变量（槽位平移才有素材）。 */
	static String slotSrc() {
		StringBuilder sb = new StringBuilder();
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class Slot {\n");
		sb.append("  public void build(Map<String, Supplier<String>> m) {\n");
		sb.append("    m.put(\"S1\", () -> { String s = \"s1\"; int n = s.length(); return \"S1:\" + (n + 1); });\n");
		sb.append("    m.put(\"S2\", () -> { String s = \"s2\"; int n = s.length(); return \"S2:\" + (n + 2); });\n");
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/** 把业务方法的局部变量槽整体平移（{@code VarInsn} / {@code IincInsn} / LVT / maxLocals）。 */
	static byte[] shiftLocalSlots(byte[] bytes, int delta) {
		ClassNode cn = parse(bytes);
		for (MethodNode mn : cn.methods) {
			if (mn.instructions == null) continue;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof VarInsnNode v) v.var += delta;
				else if (insn instanceof IincInsnNode i) i.var += delta;
			}
			if (mn.localVariables != null) {
				for (LocalVariableNode lv : mn.localVariables) lv.index += delta;
			}
			if (mn.maxLocals > 0) mn.maxLocals += delta;
		}
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/**
	 * 归一化证据（常量）：同一常量用不同指令形态表示 + 可折叠算式改写。
	 *
	 * <p>{@code BIPUSH 6} 改写成 {@code LDC 2; LDC 3; IMUL}：常量池索引不同、指令条数不同、
	 * 字面量形态不同，但值是同一个 6。体指纹按<b>值</b>编码常量并做折叠，因此这同样
	 * <b>不能</b>被当成"方法体变了"。</p>
	 */
	@Test
	void fpFoldedConstantsAreNormalizedAway(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "fpd/v1", constSrc()).resolve("Const.class"));
		byte[] v2 = foldConstantRewrite(v1);

		assertFalse(java.util.Arrays.equals(v1, v2), "夹具前提：常量改写必须真的改变了字节码");

		ClassNode oldCn = parse(v1);
		LambdaAligner.TEST_EVENTS.clear();
		ClassNode aligned = parse(LambdaAligner.align(v1, v2));

		int checked = 0;
		for (MethodNode mn : oldCn.methods) {
			String id = identity(oldCn, mn, 0);
			if (id == null) continue;
			MethodNode hit = byKey(aligned, mn.name, mn.desc);
			assertNotNull(hit, "旧槽位 " + key(mn) + " 消失了（" + table(aligned) + "）");
			assertFalse(isGhost(hit), "等价常量改写不该让旧槽位幽灵化（" + table(aligned) + "）");
			assertEquals(id, identity(aligned, hit, 0),
				"旧槽位 " + key(mn) + " 必须仍承载 " + id + "（" + table(aligned) + "）");
			checked++;
		}
		assertEquals(2, checked, "夹具前提：应有 2 个可判身份的 lambda（" + table(oldCn) + "）");
		assertEquals(0, bodyMismatchRefusals(),
			"等价常量改写必须被归一化掉，不得算作方法体不等价（" + LambdaAligner.LAST_RESULT.stats + "）");
	}

	/** 夹具：两个分别返回 / 拼接常量的 lambda。 */
	static String constSrc() {
		StringBuilder sb = new StringBuilder();
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class Const {\n");
		sb.append("  public void build(Map<String, Supplier<String>> m) {\n");
		sb.append("    m.put(\"C1\", () -> { int[] a = new int[6]; return \"C1:\" + a.length; });\n");
		sb.append("    m.put(\"C2\", () -> { int[] a = new int[6]; return \"C2:\" + a.length; });\n");
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/** 把 {@code BIPUSH 6} 改写成 {@code LDC 2; LDC 3; IMUL}（值等价、形态不同）。 */
	static byte[] foldConstantRewrite(byte[] bytes) {
		ClassNode cn = parse(bytes);
		boolean rewrote = false;
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$") || mn.instructions == null) continue;
			InsnList list = mn.instructions;
			for (AbstractInsnNode insn = list.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof IntInsnNode ii) || ii.getOpcode() != org.objectweb.asm.Opcodes.BIPUSH) continue;
				if (ii.operand != 6) continue;
				InsnList replace = new InsnList();
				replace.add(new LdcInsnNode(2));
				replace.add(new LdcInsnNode(3));
				replace.add(new InsnNode(org.objectweb.asm.Opcodes.IMUL));
				list.insert(ii, replace);
				list.remove(ii);
				rewrote = true;
				break;
			}
			if (rewrote) break;
		}
		if (!rewrote) throw new IllegalStateException("夹具里找不到 BIPUSH 6，改写失败");
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/**
	 * gp_e：只重命名可序列化 lambda 捕获的局部变量。
	 *
	 * <p>javac 的可序列化 lambda 叫 {@code lambda$m$<hex>$N}，而 hex 是"合成方法签名的哈希"，
	 * 里面含 javac 推导出的参数名——重命名捕获变量会改 hex（实测 {@code 220b63a5}→{@code 220b63c4}），
	 * 于是 {@code logicalName} 变、组 key 变，lambda 掉出原组。描述符没变、body 没变，
	 * 所以跨组指纹认领应当把它拉回原槽位。</p>
	 */
	@Test
	void renamingCapturedLocalKeepsSlotViaCrossGroupClaim(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] base = Files.readAllBytes(
			compile(javac, tmp, "gpe/v1", gpSrc(false)).resolve("Gp.class"));
		byte[] next = Files.readAllBytes(
			compile(javac, tmp, "gpe/v2", gpSrc(true)).resolve("Gp.class"));

		ClassNode oldCn = parse(base);
		ClassNode nextCn = parse(next);

		String oldName = null;
		String newName = null;
		for (MethodNode mn : oldCn.methods) if (mn.name.startsWith("lambda$")) oldName = mn.name;
		for (MethodNode mn : nextCn.methods) if (mn.name.startsWith("lambda$")) newName = mn.name;
		assertNotNull(oldName, "夹具前提：v1 应有可序列化 lambda（" + table(oldCn) + "）");
		assertNotNull(newName, "夹具前提：v2 应有可序列化 lambda（" + table(nextCn) + "）");

		// 前提：重命名捕获变量确实改变了 hex → logicalName 变了（否则这条用例测不到东西）。
		assertNotEquals(oldName, newName,
			"夹具前提：重命名捕获变量应当改变 lambda 的 hex（实际两侧同名 " + oldName + "）");

		ClassNode aligned = parse(LambdaAligner.align(base, next));

		MethodNode slot = byNameOnly(aligned, oldName);
		assertNotNull(slot, "旧槽位 " + oldName + " 消失了——跨组指纹认领没有把它拉回来（" + table(aligned) + "）");
		assertFalse(isGhost(slot), "旧槽位不该被幽灵化（" + table(aligned) + "）");
		assertEquals("G1", identity(aligned, slot, 0),
			"旧槽位必须仍承载 G1 的 body（" + table(aligned) + "）");

		assertNoDanglingIndy("gp_e", aligned);
	}

	static String gpSrc(boolean renamedLocal) {
		String v = renamedLocal ? "t" : "s";
		StringBuilder sb = new StringBuilder();
		sb.append("import java.io.Serializable;\n");
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class Gp {\n");
		sb.append("  interface SS extends Supplier<String>, Serializable {}\n");
		sb.append("  static final Map<String, SS> H = new LinkedHashMap<>();\n");
		sb.append("  static void reg(String k, SS s) { H.put(k, s); }\n");
		sb.append("  void m() {\n");
		sb.append("    String ").append(v).append(" = \"G1\";\n");
		sb.append("    reg(\"G1\", () -> \"G1:v1:\" + ").append(v).append(");\n");
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/** 编译指定变体并返回 {@code Stress.class} 的字节码。 */
	static byte[] bytesOf(String javac, Path tmp, String sub, String variant) throws Exception {
		return Files.readAllBytes(compile(javac, tmp, sub, stressSrc("v1", variant)).resolve("Stress.class"));
	}

	/**
	 * 多轮"前插 + 全量改常量"时，<b>主干 lambda</b>（A1/A2/A3）必须一直跟着自己的槽位（ed_ins）。
	 *
	 * <p>每轮所有方法体都变（指纹全失效），javac 又因为前插而整体重编号。此时还剩的线索只有
	 * "两边各自的方法表顺序"，而它是 <b>javac 的实现细节，不是语义</b>——javac 8/11/17/21
	 * 实测都按源序<b>降序</b>发射合成方法，所以"从表头对齐"等价于"按源码尾部对齐"，
	 * 对<b>前插</b>恰好成立。这是启发式，不是证明。</p>
	 *
	 * <p>本用例覆盖的是启发式成立的那部分：旧类 {@code $0..$2} 与新类 {@code $2..$4} 是同一批
	 * lambda 的位移，逐个都能对上。**剩余的那个"多出来的新 lambda"无法判定**（见
	 * {@link #surplusSlotGetsDisplacedByOrderHeuristic}）——那是信息论边界，不是这条能覆盖的。</p>
	 */
	@Test
	void multiRoundInsertionKeepsEveryOldSlot(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[][] versions = new byte[4][];
		for (int i = 0; i < versions.length; i++) {
			versions[i] = Files.readAllBytes(
				compile(javac, tmp, "chain/v" + (i + 1), chainSrc(i + 1)).resolve("Chain.class"));
		}

		byte[] cur = versions[0];
		Set<String> invented = Set.of();
		for (int round = 1; round < versions.length; round++) {
			ClassNode oldCn = parse(cur);
			byte[] aligned = LambdaAligner.align(cur, versions[round], null, null, invented);
			invented = LambdaAligner.LAST_RESULT.inventedNames;
			// 第 1 轮会插入 Z1 并给它一个自造槽位；如果这里为空，说明"自造名上报"退化，
			// 后面的轮次就会失去保护却仍然全绿——必须先钉住这个前提。
			if (round == 1) {
				assertFalse(invented.isEmpty(),
					"第 1 轮应当产生至少一个自造槽位名（实际为空，自造名上报已退化）");
			}
			ClassNode out = parse(aligned);

			int checked = 0;
			for (MethodNode mn : oldCn.methods) {
				String id = identity(oldCn, mn, 0);
				if (id == null || !id.startsWith("A")) continue;
				MethodNode hit = byKey(out, mn.name, mn.desc);
				assertNotNull(hit, "第 " + round + " 轮：旧槽位 " + mn.name + mn.desc
					+ "（" + id + "）消失了（" + table(out) + "）");
				String now = identity(out, hit, 0);
				assertEquals(id, now, "第 " + round + " 轮：旧槽位 " + mn.name + " 原本承载 " + id
					+ "，产物里却变成 " + now + "——老 CallSite 会静默跑别人的方法体（" + table(out) + "）");
				checked++;
			}
			assertTrue(checked >= 3, "第 " + round + " 轮夹具退化：只找到 " + checked + " 个可识别的 A* 槽位");
			cur = aligned;   // 链式：下一轮的"旧类"是这一轮真正生效的产物
		}
	}

	/**
	 * 信息论边界：多出来的那个新 lambda 会把上一轮新引入的槽位顶掉（已知缺陷）。
	 *
	 * <p>第 2 轮的形状（实测，javac 8/11/17/21 一致）：</p>
	 * <pre>
	 * old: [$2=A3 $1=A2 $0=A1 $4=Z1]      4 个旧槽
	 * new: [$4=A3 $3=A2 $2=A1 $1=Z2 $0=Z1] 5 个新 lambda（前插了 Z2）
	 * out: [$2=A3 $1=A2 $0=A1 $4=Z2 $5=Z1] ← $4 本该是 Z1，现在装的是 Z2
	 * </pre>
	 *
	 * <p>A1/A2/A3 靠"两边表头对齐"对上了，但剩下的 1 个旧槽要面对 2 个新候选，
	 * 而两者的方法体都变了（指纹全失效）、名字也都被重排过——<b>没有任何字节码级证据</b>
	 * 能区分"Z1 换了号"和"Z2 是新来的"。按顺序猜就是把 Z2 放进 Z1 的槽位：
	 * 第一轮创建、跨 reload 持有的 Z1 实例会安静地执行 Z2 的方法体。</p>
	 *
	 * <p>本用例以 {@code known-limitation} 钉住当前行为（不是期望行为）。正确的方向是
	 * <b>拒绝猜</b>：候选不唯一时留孤儿桩，让老 CallSite 抛 {@code NoSuchMethodError}，
	 * 而不是静默跑别人的体。改动之后本用例必须变红并改成正向断言。</p>
	 *
	 * <p><b>已裁定（2026-10-31）：保留现状，不改配对语义。</b>它与
	 * {@link #appendingLambdaShiftsEveryOldSlot} 互为镜像，字节码特征完全相同，没有规则能
	 * 同时修好两条；而真实场景里 Step 1 会按指纹先把未改动的方法体锁死，落到这里的概率极低。
	 * 见 {@code docs/initfix/08-test-audit.md} §10.15。</p>
	 */
	@Test
	@Tag("known-limitation")
	void surplusSlotGetsDisplacedByOrderHeuristic(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "sp/v1", chainSrc(1)).resolve("Chain.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "sp/v2", chainSrc(2)).resolve("Chain.class"));
		byte[] v3 = Files.readAllBytes(compile(javac, tmp, "sp/v3", chainSrc(3)).resolve("Chain.class"));

		Set<String> invented = Set.of();
		byte[] r1 = LambdaAligner.align(v1, v2, null, null, invented);
		invented = LambdaAligner.LAST_RESULT.inventedNames;

		// 前置基线：第 1 轮给 Z1 分配了一个自造槽位，且旧类里确实存在这个槽位。
		assertFalse(invented.isEmpty(), "第 1 轮应当产生自造槽位名，否则用例测不到东西");
		ClassNode r1Cn = parse(r1);
		String z1Slot = null;
		for (String name : invented) {
			if ("Z1".equals(identity(r1Cn, byKey(r1Cn, name, "()Ljava/lang/Object;"), 0))) z1Slot = name;
		}
		assertNotNull(z1Slot, "第 1 轮产物里必须能找到承载 Z1 的自造槽位（" + table(r1Cn) + "）");

		ClassNode oldCn = parse(r1);
		byte[] r2 = LambdaAligner.align(r1, v3, null, null, invented);
		ClassNode out = parse(r2);
		assertNotNull(oldCn, "解析第一轮产物用于诊断");

		// 现状：Z1 的槽位被 Z2 顶掉，Z1 自己拿了新槽位。
		MethodNode displaced = byKey(out, z1Slot, "()Ljava/lang/Object;");
		assertNotNull(displaced, "Z1 的槽位不该消失（" + table(out) + "）");
		assertEquals("Z2", identity(out, displaced, 0),
			"现状：Z1 的槽位（" + z1Slot + "）被 Z2 顶替。若这里变红，说明已改成"
			+ "\"候选不唯一就拒绝配对\"，请把断言改为：该槽位必须是幽灵桩（" + table(out) + "）");
	}

	/**
	 * 末尾追加 + 改全部常量：当前实现整体位移一格（已知缺陷，比前插更常见）。
	 *
	 * <pre>
	 * v1 : [$2=A3 $1=A2 $0=A1]
	 * v2 : [$3=B1 $2=A3 $1=A2 $0=A1]   ← 只追加 B1，A1/A2/A3 的 javac 编号没变
	 * out: [$2=B1 $1=A3 $0=A2 $4=A1]   ← 每个旧槽都往下挪了一格
	 * </pre>
	 *
	 * <p>成因：javac 按源序<b>降序</b>发射合成方法，末尾追加的 B1 反而排在方法表首位。
	 * 配对循环按方法表顺序跑，B1 第一个被处理，它没有同名旧方法，于是走"取第一个未匹配旧方法"
	 * 的兜底，把 {@code $2(A3)} 抢走；等真正的 {@code $2(A3)} 轮到时，同名槽位已被占用，
	 * 只能继续往后挪——多米诺式整体位移。</p>
	 *
	 * <p><b>这条与前插那条是镜像的，且无法用同一条规则同时修好</b>：两条里"同名但方法体不同"
	 * 都出现，但一条是真匹配（本用例）、一条是假匹配（前插那条的 {@code new $2(A2) ↔ old $2(A3)}）。
	 * 字节码层面无法区分，属于信息论边界。</p>
	 *
	 * <p><b>已裁定（2026-10-31）：保留现状，不改配对语义。</b>理由见
	 * {@code docs/initfix/08-test-audit.md} §10.15：行号与现有方法表逆序同构、破不了平局；
	 * 真实热更里同方法内其它 lambda 极少同轮全量改体，Step 1 的指纹匹配会先锁死槽位，
	 * 落到这里的概率极低。本用例记录的是<b>已接受</b>的限制，不是待办。</p>
	 */
	@Test
	@Tag("known-limitation")
	void appendingLambdaShiftsEveryOldSlot(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "app/v1", appendSrc(1)).resolve("App.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "app/v2", appendSrc(2)).resolve("App.class"));

		ClassNode oldCn = parse(v1);
		ClassNode out = parse(LambdaAligner.align(v1, v2));

		int shifted = 0;
		for (MethodNode mn : oldCn.methods) {
			String id = identity(oldCn, mn, 0);
			if (id == null || !id.startsWith("A")) continue;
			MethodNode hit = byKey(out, mn.name, mn.desc);
			assertNotNull(hit, "旧槽位 " + mn.name + " 消失了（" + table(out) + "）");
			String now = identity(out, hit, 0);
			if (!id.equals(now)) shifted++;
		}
		// 现状：三个旧槽全部错位。若这里变红，说明已改成"拒绝猜"或已按追加方向修正，
		// 请把断言改为：每个 A* 槽位都必须是它自己的 tag，或者被幽灵化（响亮失败）。
		assertEquals(3, shifted, "现状：末尾追加应导致 3 个旧槽整体位移（" + table(out) + "）");
	}

	/**
	 * {@code A1..A3} 依次写入；{@code ver>=2} 时在末尾追加 {@code B1}，且所有常量都改。
	 */
	static String appendSrc(int ver) {
		StringBuilder sb = new StringBuilder();
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class App {\n");
		sb.append("  public void build(Map<String, Supplier<?>> m) {\n");
		for (int i = 1; i <= 3; i++) {
			sb.append("    m.put(\"A").append(i).append("\", () -> \"A").append(i)
			  .append(":v").append(ver).append("\");\n");
		}
		if (ver >= 2) sb.append("    m.put(\"B1\", () -> \"B1:v").append(ver).append("\");\n");
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/**
	 * 每轮"只在最前面插一个新 lambda、其余一字不动"的链式版本。
	 * @param ver 版本号（1 起）；{@code ver>=2} 时前缀会多出 {@code Z1..Z(ver-1)}
	 */
	static String chainSrc(int ver) {
		StringBuilder sb = new StringBuilder();
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class Chain {\n");
		sb.append("  public void build(Map<String, Supplier<?>> m) {\n");
		for (int i = 1; i < ver; i++) {
			sb.append("    m.put(\"Z").append(i).append("\", () -> \"Z").append(i)
			  .append(":v").append(ver).append("\");\n");
		}
		for (int i = 1; i <= 3; i++) {
			sb.append("    m.put(\"A").append(i).append("\", () -> \"A").append(i)
			  .append(":v").append(ver).append("\");\n");
		}
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/**
	 * 同一方法内两个"同形状、同骨架"的 lambda 被传给**不同消费点**，靠 consumer 区分。
	 *
	 * <p>你举的例子正是这个形态：{@code build()} 里 {@code btnSave(() -> "v2")} 与
	 * {@code btnDelete(() -> "v2")}——宿主方法相同（组 key 相同）、形状相同、骨架相同
	 * （都只是 {@code LDC; ARETURN}），普通指纹与骨架指纹都没有区分度，
	 * 唯一能分开它们的是 consumer：{@code CALL:...btnSave(...)} 与 {@code CALL:...btnDelete(...)}。</p>
	 *
	 * <p>夹具用前插第三个 lambda 制造重编号，让"同名"这条证据失效（否则会掩盖 consumer 的作用）。</p>
	 */
	@Test
	void consumerDistinguishesSameShapeLambdasInOneMethod(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "cons/v1", consumerSrc(1)).resolve("Cons.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "cons/v2", consumerSrc(2)).resolve("Cons.class"));

		ClassNode oldCn = parse(v1);
		ClassNode out = parse(LambdaAligner.align(v1, v2));

		int checked = 0;
		for (MethodNode mn : oldCn.methods) {
			String id = identity(oldCn, mn, 0);
			if (id == null) continue;
			if (!id.equals("S") && !id.equals("D")) continue;
			MethodNode hit = byKey(out, mn.name, mn.desc);
			assertNotNull(hit, "旧槽位 " + mn.name + "（" + id + "）消失了（" + table(out) + "）");
			assertEquals(id, identity(out, hit, 0),
				"旧槽位 " + mn.name + " 原本承载 " + id + "（" + table(out) + "）");
			checked++;
		}
		assertEquals(2, checked, "夹具前提：应当有 S/D 两个可识别槽位（" + table(out) + "）");
	}

	/** 两个同形 lambda 传给不同 sink；{@code ver>=2} 时前插第三个，制造重编号。 */
	static String consumerSrc(int ver) {
		StringBuilder sb = new StringBuilder();
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class Cons {\n");
		sb.append("  static void save(Supplier<String> s) { s.get(); }\n");
		sb.append("  static void del(Supplier<String> s) { s.get(); }\n");
		sb.append("  static void extra(Supplier<String> s) { s.get(); }\n");
		sb.append("  public void build() {\n");
		if (ver == 1) {
			// v1：save 在前、del 在后（方法表里 del 在前）
			sb.append("    save(() -> \"S:v1\");\n");
			sb.append("    del(() -> \"D:v1\");\n");
		} else {
			// v2：前插 extra 且把 save 挪到最后 —— 新旧两侧的相对顺序**相反**，
			// 于是"取第一个未匹配旧方法"必然落到 del 身上，只有 consumer 能纠正。
			sb.append("    extra(() -> \"X:v2\");\n");
			sb.append("    del(() -> \"D:v2\");\n");
			sb.append("    save(() -> \"S:v2\");\n");
		}
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/**
	 * 同一方法内、形状相同但**指令骨架迥异**的两个 lambda，改常量 + 末尾追加后不许串位。
	 *
	 * <p>这是"只改常量"最常见的真实形态：{@code A1} 是一段循环，{@code A2} 是一行字符串。
	 * 普通指纹因为常量变化全部失效，Pass A/B 只剩 (深度, shape) 可用，两者完全相同 → 盲盒；
	 * 骨架指纹（忽略字面量）保留指令结构，能把它们分开。</p>
	 *
	 * <p>追加的 {@code B1} 骨架与前两者都不同，所以"两侧都唯一"规则会让它落空、拿新槽位，
	 * 而不是抢走 {@code A2} 的槽位。</p>
	 */
	@Test
	void skeletonDistinguishesStructurallyDifferentLambdas(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "skel/v1", skeletonSrc(1)).resolve("Skel.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "skel/v2", skeletonSrc(2)).resolve("Skel.class"));

		ClassNode oldCn = parse(v1);
		ClassNode out = parse(LambdaAligner.align(v1, v2));

		int checked = 0;
		for (MethodNode mn : oldCn.methods) {
			String id = identity(oldCn, mn, 0);
			if (id == null || !id.startsWith("A")) continue;
			MethodNode hit = byKey(out, mn.name, mn.desc);
			assertNotNull(hit, "旧槽位 " + mn.name + " 消失了（" + table(out) + "）");
			assertEquals(id, identity(out, hit, 0),
				"旧槽位 " + mn.name + " 原本承载 " + id + "（" + table(out) + "）");
			checked++;
		}
		assertEquals(2, checked, "夹具前提：应当有 2 个可识别的 A* 槽位（" + table(out) + "）");
	}

	/** {@code A1} 是循环、{@code A2} 是常量；{@code ver>=2} 时末尾追加一个算术 lambda。 */
	static String skeletonSrc(int ver) {
		StringBuilder sb = new StringBuilder();
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class Skel {\n");
		sb.append("  public void build(Map<String, Supplier<?>> m) {\n");
		sb.append("    m.put(\"A1\", () -> { int s = 0; for (int i = 0; i < 3; i++) s += i;")
		  .append(" return \"A1:v").append(ver).append(":\" + s; });\n");
		sb.append("    m.put(\"A2\", () -> \"A2:v").append(ver).append("\");\n");
		if (ver >= 2) {
			sb.append("    m.put(\"B1\", () -> String.valueOf(1 + 2) + \":B1\");\n");
		}
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/**
	 * 删掉一个 lambda、下一轮又原封不动加回来，必须复活它生前的槽位（rs_revive）。
	 *
	 * <p>此前这条是"单向退休"：桩体被换成 {@code onOrphanInvoked} 空壳后，指纹就变成空壳的，
	 * 第 3 轮再也认不出原主，只能拿新槽位——老 CallSite 永远停在熔断桩上。</p>
	 *
	 * <p>现在桩体里钉了生前指纹（{@code LDC <long>; POP2}），第 3 轮按"指纹完全一致 + 描述符相同"
	 * 认领回原槽位。**只在指纹一致时复活**：方法体改过的不算同一个，宁可拿新槽位。</p>
	 */
	@Test
	void deletedLambdaIsRevivedWhenAddedBackUnchanged(@TempDir Path tmp) throws Exception {
		// 复活通道默认关闭（现有三条不变量按"不复活"编码），本用例显式开启后验证能力本身。
		String prev = System.getProperty("remold.agent.ghost_revive");
		System.setProperty("remold.agent.ghost_revive", "true");
		try {
			doReviveCase(tmp);
		} finally {
			if (prev == null) System.clearProperty("remold.agent.ghost_revive");
			else System.setProperty("remold.agent.ghost_revive", prev);
		}
	}

	private void doReviveCase(Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "rev/v1", reviveSrc(1)).resolve("Rev.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "rev/v2", reviveSrc(2)).resolve("Rev.class"));
		byte[] v3 = Files.readAllBytes(compile(javac, tmp, "rev/v3", reviveSrc(3)).resolve("Rev.class"));

		// 前置基线：V3 必须与 V1 逐字节相同，否则不是"原封不动加回来"，用例就测不到复活。
		assertArrayEquals(v1, v3, "夹具前提：V3 必须与 V1 完全相同");

		ClassNode v1Cn = parse(v1);
		String a2Slot = nameOfIdentity(v1Cn, "A2");
		assertNotNull(a2Slot, "夹具前提：V1 里找不到承载 A2 的 lambda（" + table(v1Cn) + "）");

		// 第 2 轮：删掉 A2 → 槽位必须留桩，且桩体带生前指纹标记。
		byte[] r2Bytes = LambdaAligner.align(v1, v2);
		ClassNode r2 = parse(r2Bytes);
		MethodNode ghost = byKey(r2, a2Slot, "()Ljava/lang/Object;");
		assertNotNull(ghost, "第 2 轮应为被删的 A2 留桩（" + table(r2) + "）");
		assertTrue(isGhost(ghost), "第 2 轮 A2 的槽位必须是幽灵桩（" + table(r2) + "）");
		assertNotEquals(0L, ghostMarker(ghost),
			"幽灵桩体里必须带生前指纹标记，否则第 3 轮无法复活");

		// 第 3 轮：原封不动加回来 → 槽位必须复活成真的 A2。
		LambdaAligner.TEST_EVENTS.clear();
		ClassNode r3 = parse(LambdaAligner.align(r2Bytes, v3));
		MethodNode revived = byKey(r3, a2Slot, "()Ljava/lang/Object;");
		assertNotNull(revived, "第 3 轮 A2 的槽位消失了（" + table(r3) + "）");
		assertFalse(isGhost(revived), "第 3 轮 A2 的槽位应当已复活，不再是幽灵桩（" + table(r3) + "）");
		assertEquals("A2", identity(r3, revived, 0),
			"复活后槽位必须承载 A2 的 body（" + table(r3) + "）");

		boolean sawRevive = false;
		for (LambdaAligner.AlignmentEvent e : LambdaAligner.TEST_EVENTS) {
			if ("GHOST_REVIVE".equals(e.kind)) sawRevive = true;
		}
		assertTrue(sawRevive, "必须记录 GHOST_REVIVE 事件（实际事件数 " + LambdaAligner.TEST_EVENTS.size() + "）");
	}

	/** 幽灵复活夹具：V2 删掉 A2，V3 与 V1 完全相同。 */
	static String reviveSrc(int ver) {
		StringBuilder sb = new StringBuilder();
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class Rev {\n");
		sb.append("  public void build(Map<String, Supplier<?>> m) {\n");
		for (int i = 1; i <= 3; i++) {
			if (ver == 2 && i == 2) continue;          // V2：删掉 A2
			sb.append("    m.put(\"A").append(i).append("\", () -> \"A").append(i).append(":v1\");\n");
		}
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/** 幽灵桩体开头的生前指纹标记（没有则返回 0）。 */
	static long ghostMarker(MethodNode mn) {
		int steps = 0;
		for (AbstractInsnNode n = mn.instructions.getFirst(); n != null && steps < 3; n = n.getNext(), steps++) {
			if (n instanceof LdcInsnNode ldc && ldc.cst instanceof Long v) return v;
		}
		return 0L;
	}

	/**
	 * 可序列化 lambda：改名后 `$deserializeLambda$` 里的 implMethodName 与 switch key 必须同步。
	 *
	 * <p>失败形态是运行期 {@code IllegalArgumentException: Invalid lambda deserialization}——
	 * 老序列化流里的 implMethodName 指向一个已不存在（或被改到别处）的方法。这条路径此前
	 * **全仓库零覆盖**：两个死探针碰到 `$deserializeLambda$` 都主动跳过了。</p>
	 *
	 * <p>本用例只覆盖**字节码层判据**（两条不变量），不覆盖真 reload 后的反序列化往返——
	 * 那需要 JBR/DCEVM 起 agent，属 `LiveDcevmTest` 那一层。</p>
	 */
	@Test
	void deserializeLambdaImplNamesFollowRenames(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "ser/v1", serSrc(1)).resolve("Ser.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "ser/v2", serSrc(2)).resolve("Ser.class"));

		ClassNode oldCn = parse(v1);
		ClassNode newCn = parse(v2);
		MethodNode des = byNameOnly(newCn, "$deserializeLambda$");
		assertNotNull(des, "夹具前提：v2 必须含 $deserializeLambda$（" + table(newCn) + "）");

		// 前置基线：v1 里 S1 的槽位名与 v2 里 S1 的 javac 名必须不同，否则"改名"没发生，用例空转。
		String oldSlot = nameOfIdentity(oldCn, "S1");
		String newSlot = nameOfIdentity(newCn, "S1");
		assertNotNull(oldSlot, "夹具前提：v1 里找不到承载 S1 的 lambda（" + table(oldCn) + "）");
		assertNotNull(newSlot, "夹具前提：v2 里找不到承载 S1 的 lambda（" + table(newCn) + "）");
		assertNotEquals(oldSlot, newSlot,
			"夹具前提：前插应让 S1 换号（v1=" + oldSlot + " v2=" + newSlot + "），否则测不到改名");

		ClassNode out = parse(LambdaAligner.align(v1, v2));

		// 不变量 1：$deserializeLambda$ 里每个 lambda$ 形态的字符串常量都必须指向产物里真实存在的方法。
		// 老流按 implMethodName 反查方法，指向不存在的方法 = Invalid lambda deserialization。
		Set<String> outNames = new HashSet<>();
		for (MethodNode mn : out.methods) outNames.add(mn.name);
		MethodNode outDes = byNameOnly(out, "$deserializeLambda$");
		assertNotNull(outDes, "产物丢了 $deserializeLambda$（" + table(out) + "）");

		int nameConsts = 0;
		for (String s : stringConsts(outDes)) {
			if (!s.startsWith("lambda$")) continue;
			nameConsts++;
			assertTrue(outNames.contains(s),
				"$deserializeLambda$ 引用了产物里不存在的方法名 \"" + s + "\"（" + table(out) + "）");
		}
		assertTrue(nameConsts > 0, "夹具前提：$deserializeLambda$ 里应当有 lambda$ 形态的名字常量");

		// 不变量 2：按 implMethodName.hashCode() 分派的那个 lookupswitch，每个 key 必须能在
		// 它自己的 case 块里找到 hash 相同的字符串。改名后没重算 key，块里就没有任何字符串
		// 的 hash 等于旧 key，运行期会落到 default（同样抛 Invalid lambda deserialization）。
		//
		// 注意 javac 会生成**两个** lookupswitch：第一个按方法名 hash，第二个按序号 0/1/...，
		// 只校验前者（后者用 isMethodNameHashSwitch 精确识别）。
		int checkedSwitches = 0;
		for (AbstractInsnNode insn : outDes.instructions) {
			if (!(insn instanceof LookupSwitchInsnNode sw)) continue;
			if (!isMethodNameHashSwitch(sw)) continue;
			checkedSwitches++;
			Set<LabelNode> boundaries = new HashSet<>(sw.labels);
			if (sw.dflt != null) boundaries.add(sw.dflt);
			for (int i = 0; i < sw.labels.size(); i++) {
				List<String> block = stringsInBlock(sw.labels.get(i), boundaries);
				assertFalse(block.isEmpty(), "case 块里没有字符串，无法核对 key（第 " + i + " 项）");
				int key = (int) sw.keys.get(i);
				boolean hit = false;
				for (String s : block) if (s.hashCode() == key) hit = true;
				assertTrue(hit, "switch key " + key + " 在 case 块内找不到对应字符串 " + block
					+ "：改名后未重算 key");
			}
		}
		assertTrue(checkedSwitches > 0, "夹具前提：应当至少识别到一个按方法名 hash 分派的 switch");
	}

	/** 可序列化 lambda 夹具：{@code ver>=2} 时在最前面插一个新 lambda，迫使 S1 换号。 */
	static String serSrc(int ver) {
		StringBuilder sb = new StringBuilder();
		sb.append("import java.io.Serializable;\n");
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class Ser {\n");
		sb.append("  interface SS extends Supplier<String>, Serializable {}\n");
		sb.append("  public void build(Map<String, Supplier<?>> m) {\n");
		if (ver >= 2) sb.append("    m.put(\"Z\", (SS) () -> \"Z:v2\");\n");
		sb.append("    m.put(\"S1\", (SS) () -> \"S1:v").append(ver).append("\");\n");
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/** 找某个身份（tag 头）对应的 lambda 方法名；找不到返回 null。 */
	static String nameOfIdentity(ClassNode cn, String id) {
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			if (id.equals(identity(cn, mn, 0))) return mn.name;
		}
		return null;
	}

	/** 方法体里所有字符串常量（去重保序）。 */
	static List<String> stringConsts(MethodNode mn) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : mn.instructions) {
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s && !out.contains(s)) out.add(s);
		}
		return out;
	}

	/** 该 lookupswitch 是否由 {@code String.hashCode()} 分派（往前找最近的调用点）。 */
	static boolean isMethodNameHashSwitch(LookupSwitchInsnNode sw) {
		int steps = 0;
		for (AbstractInsnNode p = sw.getPrevious(); p != null && steps < 12; p = p.getPrevious(), steps++) {
			if (p instanceof MethodInsnNode m) {
				return "hashCode".equals(m.name) && "java/lang/String".equals(m.owner);
			}
		}
		return false;
	}

	/** 从某个 case 标签开始、到下一个边界之前的所有字符串常量（去重保序）。 */
	static List<String> stringsInBlock(LabelNode start, Set<LabelNode> boundaries) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn = start; insn != null; insn = insn.getNext()) {
			if (insn != start && insn instanceof LabelNode ln && boundaries.contains(ln)) break;
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s && !out.contains(s)) out.add(s);
		}
		return out;
	}

	/**
	 * force-static 必须让每个 lambda 恰好带一个 receiver（sg_b）。
	 *
	 * <p>同一个 lambda 有两种等价写法：{@code () -> this.x}（javac 发实例方法 {@code ()}）
	 * 与 {@code () -> self.x}（javac 发静态方法 {@code (T)}）。强制静态化必须把两者归一到
	 * 同一个形状 {@code (T)}，否则同一个 lambda 在"改写成显式 self"的那一轮描述符会变成
	 * {@code (T,T)}，旧槽位认不出它、被幽灵化，老 CallSite 静默失效。</p>
	 */
	@Test
	void forceStaticGivesExactlyOneReceiver(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		String src = String.join("\n",
			"import java.util.*;",
			"import java.util.function.*;",
			"public class Fs {",
			"  int base = 1;",
			"  public void build(Map<String, Supplier<?>> m) {",
			"    Fs self = this;",
			"    m.put(\"IMPLICIT\", () -> \"A:\" + base);",        // 实例 ()String
			"    m.put(\"EXPLICIT\", () -> \"B:\" + self.base);",   // 静态 (Fs)String
			"    m.put(\"PLAIN\", () -> \"C\");",                   // 静态 ()String，零捕获
			"  }",
			"}");
		Path out = compile(javac, tmp, "fs", src);
		byte[] raw = Files.readAllBytes(out.resolve("Fs.class"));

		byte[] forced = remold.AnnotationTransformer.forceStaticLambdas(
			raw, "Fs", LambdaAlignTortureTest.class.getClassLoader());
		assertNotSame(raw, forced, "forceStaticLambdas 什么都没改，用例失去意义");

		ClassNode cn = parse(forced);
		Map<String, Integer> receiverCount = new TreeMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(mn.desc);
			int owners = 0;
			for (org.objectweb.asm.Type a : args) {
				if (a.getDescriptor().equals("LFs;")) owners++;
			}
			receiverCount.put(mn.name + mn.desc, owners);
			assertEquals(1, owners, "每个 lambda 必须恰好一个 receiver：" + mn.name + mn.desc
				+ " 实际 " + owners + " 个（" + receiverCount + "）");
			assertTrue((mn.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0,
				"force-static 后必须都是静态方法：" + mn.name + mn.desc);
		}
		assertEquals(3, receiverCount.size(), "三个 lambda 都该被处理：" + receiverCount);

		// 幂等：再跑一次不许再前置一个 this（标记字段是第一重保证）
		byte[] twice = remold.AnnotationTransformer.forceStaticLambdas(
			forced, "Fs", LambdaAlignTortureTest.class.getClassLoader());
		assertArrayEquals(forced, twice, "forceStaticLambdas 不幂等：第二次调用改变了字节码");
	}


	/**
	 * 核心断言：老名字在新类里的归属不许变。
	 *
	 * @param minIdentified 老类里"有身份"的 lambda 数量下界，防止夹具本身退化导致断言空转
	 */
	static void checkPair(String label, byte[] oldBytes, byte[] newBytes, int minIdentified) {
		checkAligned(label, parse(oldBytes), parse(newBytes),
			parse(LambdaAligner.align(oldBytes, newBytes)), minIdentified);
	}

	static void checkAligned(String label, ClassNode oldCn, ClassNode newCn, ClassNode aligned,
	                         int minIdentified) {
		Set<String> newIds = allIds(newCn);

		int identified = 0;
		for (MethodNode om : oldCn.methods) {
			if (!om.name.startsWith("lambda$")) continue;
			String id = identity(oldCn, om, 0);
			if (id == null) {
				// 无 tag 且不是容器（同体去重的那两个 lambda）：至少要保证没被丢掉。
				MethodNode same = byKey(aligned, om.name, om.desc);
				assertNotNull(same, label + "：" + key(om) + " 在产物里消失了（老 callsite 会 NoSuchMethodError）");
				assertFalse(isGhost(same), label + "：" + key(om) + " 不该被幽灵化，新类里它还在");
				continue;
			}
			identified++;

			MethodNode am = byKey(aligned, om.name, om.desc);
			assertNotNull(am, label + "：" + key(om) + " 在产物里消失了（老 callsite 会 NoSuchMethodError）"
				+ "，实际方法表 " + table(aligned));

			if (isGhost(am)) {
				// 幽灵只允许出现在"这个逻辑 lambda 在新版本里确实被删了"的情形。
				assertFalse(newIds.contains(id),
					label + "：" + key(om) + "（身份 " + id + "）被降级成幽灵桩，但新类里仍有同一逻辑的 lambda");
				continue;
			}

			assertEquals(id, identity(aligned, am, 0),
				label + "：老 callsite " + key(om) + " 在产物里换了 body（期望身份 " + id
					+ "，实际 " + identity(aligned, am, 0) + "），实际方法表 " + table(aligned));
		}

		assertTrue(identified >= minIdentified,
			label + "：可判身份的老 lambda 只有 " + identified + " 个（< " + minIdentified
				+ "），夹具或身份解析退化了，断言会空转");

		// 对齐不许凭空增删逻辑 lambda：产物里的身份集合必须与新类一致。
		assertEquals(newIds, allIds(aligned),
			label + "：产物的 lambda 身份集合与新类不一致，实际方法表 " + table(aligned));

		// 产物必须自洽：没有悬空 indy，也没有重复的 name+desc。
		assertNoDanglingIndy(label, aligned);
		Set<String> keys = new HashSet<>();
		for (MethodNode mn : aligned.methods) {
			assertTrue(keys.add(mn.name + mn.desc), label + "：产物出现重复方法 " + key(mn));
		}
	}

	// ---------------- 身份解析 ----------------

	/** lambda 的逻辑身份：自己的 tag，或（容器）"via:子 tag"。无 tag 返回 null。 */
	static String identity(ClassNode cn, MethodNode mn, int depth) {
		if (mn == null || depth > 4) return null;
		String tag = stringTag(mn);
		if (tag != null) return tag;
		List<String> kids = childLambdas(cn, mn);
		if (kids.size() == 1) {
			MethodNode kid = byName(cn, kids.get(0));
			String inner = identity(cn, kid, depth + 1);
			if (inner != null) return "via:" + inner;
		}
		return null;
	}

	/** 方法体里第一个 {@code "X:..."} 形态的字符串常量（含字符串拼接的 recipe）的 X。 */
	static String stringTag(MethodNode mn) {
		if (mn.instructions == null) return null;
		for (AbstractInsnNode insn : mn.instructions) {
			String s = null;
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String str) {
				s = str;
			} else if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
				for (Object arg : indy.bsmArgs) {
					if (arg instanceof String str) { s = str; break; }
				}
			}
			if (s == null) continue;
			int colon = s.indexOf(':');
			if (colon <= 0) continue;
			String head = s.substring(0, colon);
			if (head.matches("[A-Z][A-Za-z0-9]*")) return head;
		}
		return null;
	}

	/** 方法体里 invokedynamic 指向本类的合成方法名（去重保序）。 */
	static List<String> childLambdas(ClassNode cn, MethodNode mn) {
		LinkedHashSet<String> kids = new LinkedHashSet<>();
		if (mn.instructions == null) return new ArrayList<>(kids);
		for (AbstractInsnNode insn : mn.instructions) {
			if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs == null) continue;
			for (Object arg : indy.bsmArgs) {
				if (arg instanceof Handle h && cn.name.equals(h.getOwner()) && h.getName().startsWith("lambda$")) {
					kids.add(h.getName() + h.getDesc());
				}
			}
		}
		return new ArrayList<>(kids);
	}

	/**
	 * 逻辑身份集合。
	 *
	 * <p><b>幽灵不参与</b>：非 {@code void} 孤儿的幽灵默认保留上一轮方法体
	 * （{@code docs/topology/11-orphan-lambda-body-retention.md}），其方法体仍能解析出旧身份，
	 * 但它是"死名字"，不算产物里活着的逻辑 lambda。身份集合只统计非幽灵方法，
	 * 否则"产物身份集合 == 新类身份集合"会被保留体污染。</p>
	 */
	static Set<String> allIds(ClassNode cn) {
		Set<String> ids = new HashSet<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			if (isGhost(mn)) continue;
			String id = identity(cn, mn, 0);
			if (id != null) ids.add(id);
		}
		return ids;
	}

	// ---------------- 通用小工具 ----------------

	static ClassNode parse(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		return cn;
	}

	/**
	 * 自检用：把产物里两个"同描述符、不同身份"的 lambda 名字对调，制造"名字还在但 body 换了"。
	 * 只改名不改指令，所以 indy 句柄会变悬空——这不影响自检，身份比较是按名字取方法。
	 */
	static byte[] swapTwoLambdaNames(byte[] bytes) {
		ClassNode cn = parse(bytes);
		MethodNode first = null;
		MethodNode second = null;
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			if (!"()Ljava/lang/String;".equals(mn.desc)) continue;
			String tag = stringTag(mn);
			if (tag == null) continue;
			if (first == null) {
				first = mn;
			} else if (!tag.equals(stringTag(first))) {
				second = mn;
				break;
			}
		}
		if (first == null || second == null) {
			throw new IllegalStateException("自检夹具里找不到两个同描述符、不同身份的 lambda");
		}
		String name = first.name;
		first.name = second.name;
		second.name = name;
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
	}

	static boolean isGhost(MethodNode mn) {
		if (mn == null || mn.instructions == null) return false;
		for (AbstractInsnNode insn : mn.instructions) {
			if (insn instanceof MethodInsnNode mi
				&& mi.owner.equals("remold/LambdaAligner") && mi.name.equals("onOrphanInvoked")) return true;
		}
		return false;
	}

	static MethodNode byKey(ClassNode cn, String name, String desc) {
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
		}
		return null;
	}

	static MethodNode byName(ClassNode cn, String key) {
		for (MethodNode mn : cn.methods) {
			if ((mn.name + mn.desc).equals(key)) return mn;
		}
		return null;
	}

	/** 按方法名取（用于描述符不写死的场景）。 */
	static MethodNode byNameOnly(ClassNode cn, String name) {
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name)) return mn;
		}
		return null;
	}

	static String key(MethodNode mn) {
		return mn.name + mn.desc;
	}

	static void assertNoDanglingIndy(String label, ClassNode cn) {
		for (MethodNode mn : cn.methods) {
			if (mn.instructions == null) continue;
			for (AbstractInsnNode insn : mn.instructions) {
				if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs == null) continue;
				for (Object arg : indy.bsmArgs) {
					if (!(arg instanceof Handle h) || !cn.name.equals(h.getOwner())) continue;
					assertNotNull(byKey(cn, h.getName(), h.getDesc()),
						label + "：" + key(mn) + " 的 indy 指向不存在的方法 " + h.getName() + h.getDesc());
				}
			}
		}
	}

	static String table(ClassNode cn) {
		StringBuilder sb = new StringBuilder("[");
		for (MethodNode mn : cn.methods) {
			if (mn.name.startsWith("lambda$") || isGhost(mn)) {
				sb.append(mn.name).append(mn.desc)
					.append(isGhost(mn) ? "(GHOST) " : "=" + identity(cn, mn, 0) + " ");
			}
		}
		return sb.append("]").toString();
	}

	// ---------------- 夹具 ----------------

	/**
	 * 夹具源码。三个开关分别对应 V3（前插 Z）与 V4（删 B、交换 C/D）。
	 * 只用 Java 7 语法，保证 javac 8 也能编。
	 */
	static String src(String ver, boolean insertZ, boolean deleteB, boolean swapCD) {
		StringBuilder sb = new StringBuilder();
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class Torture {\n");
		sb.append("  static Supplier<String> SF = () -> \"S:").append(ver).append("\";\n");
		sb.append("  Supplier<String> inst = () -> \"T:").append(ver).append("\";\n");
		sb.append("  int base = 100;\n");
		sb.append("  String who() { return \"E:").append(ver).append("\"; }\n");
		sb.append("  public void build(Map<String, Object> out, int cap) {\n");
		sb.append("    int local = cap * 2;\n");
		if (insertZ) {
			sb.append("    out.put(\"Z\", (Supplier<String>) () -> \"Z:").append(ver).append("\");\n");
		}
		sb.append("    out.put(\"A\", (Supplier<String>) () -> \"A:").append(ver).append("\");\n");
		if (!deleteB) {
			sb.append("    out.put(\"B\", (Supplier<String>) () -> \"B:").append(ver).append(":\" + local);\n");
		}
		String c = "    out.put(\"C\", (Supplier<String>) () -> \"C:" + ver + ":\" + base);\n";
		String d = "    out.put(\"D\", (Supplier<Supplier<String>>) () -> () -> \"D:" + ver + ":\" + local);\n";
		sb.append(swapCD ? d : c).append(swapCD ? c : d);
		sb.append("    out.put(\"E\", (Supplier<String>) this::who);\n");
		sb.append("    out.put(\"F1\", (Supplier<String>) () -> \"same\");\n");
		sb.append("    out.put(\"F2\", (Supplier<String>) () -> \"same\");\n");
		sb.append("    out.put(\"H\", (BiFunction<Integer, String, String>) (i, s) -> \"H:")
			.append(ver).append(":\" + i + s);\n");
		sb.append("    out.put(\"S\", SF);\n");
		sb.append("    out.put(\"T\", inst);\n");
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	static Path compile(String javac, Path root, String sub, String source) throws Exception {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("public class (\\w+)").matcher(source);
		if (!m.find()) throw new IllegalArgumentException("夹具源码里找不到 public class");
		Path srcDir = Files.createDirectories(root.resolve(sub).resolve("src"));
		Path outDir = Files.createDirectories(root.resolve(sub).resolve("out"));
		Path file = srcDir.resolve(m.group(1) + ".java");
		Files.writeString(file, source);
		HstestFixtures.compile(javac, outDir, file.toFile());
		return outDir;
	}

	/**
	 * 第二族夹具：多宿主方法（{@code g1}..{@code g5}），用于针对 Step 1 证据匹配、Pass A 深度、
	 * 跨组指纹认领与保留槽位的定点用例。每个 lambda 的返回值都以自己的 key 开头，key 即身份。
	 *
	 * <p>变体：{@code S1A} 在 {@code g1} 最前面插 Z；{@code RSA} 删 A2 并追加同描述符的 A9；
	 * {@code XGA} 把宿主 {@code g4} 改名成 {@code g4x}；{@code PAA} 把 B1 包一层使 upDepth 0→1。</p>
	 */
	static String stressSrc(String ver, String variant) {
		boolean s1a = variant.equals("S1A");
		boolean rsa = variant.equals("RSA");
		boolean rsp = variant.equals("RSP");
		boolean xga = variant.equals("XGA");
		boolean paa = variant.equals("PAA");
		boolean deleteA2 = rsa || rsp;

		StringBuilder sb = new StringBuilder();
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class Stress {\n");
		sb.append("  static final Map<String, Supplier<String>> H = new LinkedHashMap<>();\n");
		sb.append("  int base = 1;\n");
		sb.append("  static void reg(String k, Supplier<String> s) { H.put(k, s); }\n");

		sb.append("  void g1(int p, String q) {\n");
		if (s1a) sb.append("    reg(\"Z\", () -> \"Z:").append(ver).append("\");\n");
		sb.append("    reg(\"A1\", () -> \"A1:").append(ver).append("\");\n");
		if (!deleteA2) sb.append("    reg(\"A2\", () -> \"A2:").append(ver).append("\");\n");
		sb.append("    reg(\"A3\", () -> \"A3:").append(ver).append("\");\n");
		sb.append("    reg(\"A4\", () -> \"A4:").append(ver).append(":\" + p);\n");
		sb.append("    reg(\"A5\", () -> \"A5:").append(ver).append(":\" + q);\n");
		if (rsa) sb.append("    reg(\"A9\", () -> \"A9:").append(ver).append("\");\n");
		sb.append("  }\n");

		sb.append("  void g2() {\n");
		if (paa) {
			// 叶子被一个新 lambda 包住：描述符不变，upDepth 从 0 变成 1。
			sb.append("    reg(\"B1\", ((Supplier<Supplier<String>>) () -> () -> \"B1:")
				.append(ver).append("\").get());\n");
		} else {
			sb.append("    reg(\"B1\", () -> \"B1:").append(ver).append("\");\n");
		}
		sb.append("    reg(\"B2\", () -> { Supplier<String> in = () -> \"B2in:").append(ver)
			.append("\"; return \"B2:").append(ver).append(":\" + in.get(); });\n");
		sb.append("  }\n");

		sb.append("  void g3() {\n");
		sb.append("    reg(\"C1\", () -> \"C1:").append(ver).append(":\" + base);\n");
		sb.append("    reg(\"C2\", () -> \"C2:").append(ver).append(":\" + base);\n");
		sb.append("    reg(\"C3\", () -> \"C3:").append(ver).append(":\" + base);\n");
		sb.append("  }\n");

		sb.append("  void ").append(xga ? "g4x" : "g4").append("() { reg(\"D1\", () -> \"D1:")
			.append(ver).append(":\" + base); }\n");
		sb.append("  void g5() { }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/** 本轮对齐里"方法体不等价 ⇒ 拒绝配对"的告警条数（按事件计数，不依赖 stats 字段）。 */
	static int bodyMismatchRefusals() {
		int n = 0;
		for (LambdaAligner.AlignmentEvent e : LambdaAligner.TEST_EVENTS) {
			if ("BODY_MISMATCH".equals(e.kind)) n++;
		}
		return n;
	}

}
