import remold.LambdaAligner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pass B 兜底对齐断言（JUnit 化）。
 *
 * <p><b>契约（批次 I 之后）</b>：Pass B 只为"<b>方法体可证明等价</b>、但深度/形状变了"的新叶子
 * 兜底配对，<b>不再</b>按位置把旧名字交给一个方法体不同的新叶子。判据见
 * {@code LambdaAligner#fallbackPairingAllowed}：{@code bodyFingerprint} 相等（常量池索引 /
 * 局部槽位 / 可折叠算式已归一）即为等价证明；证明不了就走 fail-closed——旧名幽灵化（老
 * {@code CallSite} 响亮熔断）、新 lambda 拿避障名、记一次 {@code BODY_MISMATCH}。</p>
 *
 * <p>因此本类按两个方向分别钉住契约，外加一条大样本误报断言：</p>
 * <ul>
 *   <li>{@link #passBRefusesRebindWhenBodyDiffers}——<b>该拒的必须拒</b>：v1 叶子 {@code () -> act1()}，
 *       v2 把新叶子 {@code () -> act2()} 包进新容器。旧夹具下这是一次静默改绑（老 callsite 跑
 *       act2 却不报错），现在是 {@code BODY_MISMATCH} + 幽灵桩 + {@code passB == 0}。</li>
 *   <li>{@link #passBPairsRelocatedLeafWithProvablyEqualBody}——<b>Pass B 仍可达</b>：叶子体等价、
 *       只是被搬进了一层新容器。为了让证据真的落到 Pass B（而不是被 Step 1 的票据指纹先吃掉），
 *       v2 的常量用等价改写（{@code BIPUSH 6} → {@code LDC 2; LDC 3; IMUL}）表示——票据指纹不同、
 *       体指纹相同，这正是 {@code bodyFingerprint} 归一化的目标形态。</li>
 *   <li>{@link #bodyFingerprintNeverRefusesSlotShiftedRepoLambdas}——<b>大样本误报</b>：仓库内全部
 *       含 lambda 的类，同源 {@code -g} / {@code -g:none} 编译，并把新版本的局部槽位整体平移
 *       （票号变、体指纹应不变），断言 {@code BODY_MISMATCH == 0}。</li>
 * </ul>
 *
 * <p><b>判据分两层</b>：{@code AlignmentResult.stats} 只能说明"走了哪条路"，产物字节码才说明
 * "配对结果对不对"。所以除了计数器，还必须断言终局形态。</p>
 */
public class PassBTest {

	/**
	 * (a) 拒绝用例：旧叶子 {@code () -> act1()}、新叶子 {@code () -> act2()} 被包进新容器。
	 *
	 * <p>两者名字撞车（javac 后序编号让新叶子也落在 {@code lambda$build$0}）、深度不同、方法体不同。
	 * 旧契约按位置把它们配成一对，老 callsite 静默执行 act2；新契约必须拒绝：旧名幽灵化
	 * （老 callsite 熔断）、新叶子拿避障名、{@code passA/passB == 0}。</p>
	 */
	@Test
	void passBRefusesRebindWhenBodyDiffers(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		File src1 = findFile("passB/v1/testPassB/PassBCase.java");
		File src2 = findFile("passB/v2/testPassB/PassBCase.java");

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.getAbsolutePath());

		byte[] v1 = Files.readAllBytes(outV1.resolve("testPassB/PassBCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("testPassB/PassBCase.class"));

		LambdaAligner.LAST_RESULT = null;
		LambdaAligner.TEST_EVENTS.clear();
		byte[] aligned = LambdaAligner.align(v1, v2);

		assertNotNull(aligned, "aligned 产物非空");
		assertNotNull(LambdaAligner.LAST_RESULT, "对齐结果已记录");

		// 1. 计数器：这条路径必须被拒绝，且不得从 A/B 趟漏过去。
		assertTrue(LambdaAligner.LAST_RESULT.stats.bodyMismatchRefusals > 0,
			"方法体不等价且无独立身份证据时必须拒绝配对（记 BODY_MISMATCH）: " + LambdaAligner.LAST_RESULT.stats);
		assertEquals(0, LambdaAligner.LAST_RESULT.stats.passBPairs,
			"passB 不得把旧名字交给方法体不同的新叶子: " + LambdaAligner.LAST_RESULT.stats);
		assertEquals(0, LambdaAligner.LAST_RESULT.stats.passAPairs,
			"passA 跳过深度不匹配候选 (passA == 0): " + LambdaAligner.LAST_RESULT.stats);

		// 2. 留痕：拒绝必须响亮（事件按旧/新名字精确归因，供宿主侧熔断与人工排查）。
		boolean bodyMismatchEvent = LambdaAligner.TEST_EVENTS.stream()
			.anyMatch(ev -> "BODY_MISMATCH".equals(ev.kind)
			                && "lambda$build$0".equals(ev.oldName) && "lambda$build$0".equals(ev.newName));
		assertTrue(bodyMismatchEvent,
			"必须记录一条针对 lambda$build$0 的 BODY_MISMATCH 事件（实际事件: " + LambdaAligner.TEST_EVENTS + "）");

		// —— 以下才是终局断言：计数器只说明走了哪条路，产物字节码才说明配得对不对 ——
		ClassNode out = parse(aligned);

		// 3. 旧槽位必须是幽灵桩：老 callsite 一调用就响亮熔断，绝不静默跑错语义。
		MethodNode oldSlot = method(out, "lambda$build$0", "()V");
		assertNotNull(oldSlot, "旧槽位 lambda$build$0()V 必须留在产物里（实际方法表: " + table(out) + "）");
		assertTrue(calls(oldSlot, "onOrphanInvoked"),
			"旧槽位必须是幽灵桩（老 callsite 响亮熔断），不能退化成任何一个活语义（实际方法表: " + table(out) + "）");
		assertFalse(calls(oldSlot, "act2"),
			"旧槽位绝不能静默改绑到新叶子语义 act2()（实际方法表: " + table(out) + "）");

		// 4. 新链完整可达：build() -> 容器 -> 新叶子，且新叶子拿的是避障名（不再顶替旧槽位）。
		String containerName = firstIndyTarget(out, "build");
		MethodNode container = method0(out, containerName);
		assertNotNull(container, "build() 的 indy 目标必须在产物中存在（实际方法表: " + table(out) + "）");
		assertFalse(calls(container, "onOrphanInvoked"), "build() 指向的容器不能是孤儿熔断桩");
		String leafName = firstIndyTarget(out, containerName);
		assertNotEquals("lambda$build$0", leafName,
			"新叶子不得复用被幽灵化的旧槽位名（实际: " + leafName + "）");
		MethodNode newLeaf = method0(out, leafName);
		assertNotNull(newLeaf, "新叶子必须存在（实际方法表: " + table(out) + "）");
		assertTrue(calls(newLeaf, "act2"), "新叶子必须执行新语义 act2()（实际方法表: " + table(out) + "）");

		// 5. 只有一个幽灵（旧槽位），没有额外方法被静默丢弃。
		assertEquals(1, ghostCount(out), "本轮只应产生旧槽位这一个幽灵（实际方法表: " + table(out) + "）");

		// 6. 产物自洽：不得出现重复的 name+desc（否则同名两版里必有一个是死的）。
		assertEquals(keySet(out).size(), out.methods.size(),
			"产物方法名+描述符必须唯一（实际方法表: " + table(out) + "）");
	}

	/**
	 * (b) 可达用例：Pass B 仍能为"体可证明等价"的搬迁叶子兜底。
	 *
	 * <p>v1 的叶子 {@code () -> { int[] a = new int[6]; return "k:" + a.length; }} 在 v2 里被搬进一层
	 * 新容器，深度从 0 变 1（Pass A 要求深度相等，会跳过它）。为了让 Pass B 真的可达，v2 的常量用
	 * <b>等价改写</b>表示（{@code BIPUSH 6} → {@code LDC 2; LDC 3; IMUL}）：票据指纹因此不同
	 * （Step 1 不会先吃掉这对），而 {@code bodyFingerprint} 按值编码 + 折叠算式后两侧相同——
	 * 这正是"等价证明"该放行的形态。</p>
	 *
	 * <p>断言 {@code passBPairs > 0}、{@code passAPairs == 0}、<b>无</b> {@code BODY_MISMATCH}，
	 * 且产物里旧槽位仍是活方法（无幽灵）、新链完整。</p>
	 */
	@Test
	void passBPairsRelocatedLeafWithProvablyEqualBody(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path outV1 = Files.createDirectories(tmp.resolve("mv1"));
		Path outV2 = Files.createDirectories(tmp.resolve("mv2"));

		File src1 = findFile("passB/move2/v1/testPassBMove2/PassBMove2Case.java");
		File src2 = findFile("passB/move2/v2/testPassBMove2/PassBMove2Case.java");

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.getAbsolutePath());

		byte[] v1 = Files.readAllBytes(outV1.resolve("testPassBMove2/PassBMove2Case.class"));
		byte[] v2raw = Files.readAllBytes(outV2.resolve("testPassBMove2/PassBMove2Case.class"));

		byte[] v2 = foldConstantRewrite(v2raw);
		assertFalse(Arrays.equals(v2raw, v2), "夹具前提：常量等价改写必须真的改变了字节码");
		ClassNode nextCn = parse(v2);
		String newLeafName = null;
		for (MethodNode mn : nextCn.methods) {
			if (mn.name.startsWith("lambda$") && "()Ljava/lang/String;".equals(mn.desc)) newLeafName = mn.name;
		}
		assertNotNull(newLeafName, "夹具前提：v2 里应有一个 Supplier<String> 叶子（" + table(nextCn) + "）");
		String oldLeafName = null;
		ClassNode oldCn = parse(v1);
		for (MethodNode mn : oldCn.methods) {
			if (mn.name.startsWith("lambda$") && "()Ljava/lang/String;".equals(mn.desc)) oldLeafName = mn.name;
		}
		assertNotNull(oldLeafName, "夹具前提：v1 里应有一个 Supplier<String> 叶子（" + table(oldCn) + "）");
		assertEquals(oldLeafName, newLeafName,
			"夹具前提：javac 后序编号让新叶子仍叫旧名字（" + oldLeafName + " vs " + newLeafName + "）");

		LambdaAligner.LAST_RESULT = null;
		LambdaAligner.TEST_EVENTS.clear();
		byte[] aligned = LambdaAligner.align(v1, v2);

		assertNotNull(aligned, "aligned 产物非空");
		assertNotNull(LambdaAligner.LAST_RESULT, "对齐结果已记录");

		// 1. 走的是 Pass B（A 趟被深度差挡住），且没有把等价体误判成"不等价"。
		assertTrue(LambdaAligner.LAST_RESULT.stats.passBPairs > 0,
			"passB 必须记录到非零配对（体等价搬迁可达）: " + LambdaAligner.LAST_RESULT.stats);
		assertEquals(0, LambdaAligner.LAST_RESULT.stats.passAPairs,
			"passA 跳过深度不匹配候选 (passA == 0): " + LambdaAligner.LAST_RESULT.stats);
		assertEquals(0, LambdaAligner.LAST_RESULT.stats.bodyMismatchRefusals,
			"体可证明等价的搬迁不得触发任何 BODY_MISMATCH: " + LambdaAligner.LAST_RESULT.stats);
		assertTrue(LambdaAligner.TEST_EVENTS.stream().noneMatch(ev -> "BODY_MISMATCH".equals(ev.kind)),
			"不得记录 BODY_MISMATCH 事件（实际: " + LambdaAligner.TEST_EVENTS + "）");

		// —— 终局断言：产物里旧槽位仍活着，且承载的就是新叶子的载荷 ——
		ClassNode out = parse(aligned);

		MethodNode oldSlot = method(out, oldLeafName, "()Ljava/lang/String;");
		assertNotNull(oldSlot, "旧槽位 " + oldLeafName + " 必须留在产物里（实际方法表: " + table(out) + "）");
		assertFalse(calls(oldSlot, "onOrphanInvoked"),
			"体等价搬迁不该把旧槽位幽灵化（实际方法表: " + table(out) + "）");

		// 载荷断言：叶子必须仍是"int[6].length 拼进字符串"那一段——等价常量改写后
		// 形态可能是 BIPUSH 6，也可能是折叠后的 LDC 2 / LDC 3 / IMUL；两者都接受，但必须真的在。
		assertTrue(newArrayPayloadKept(oldSlot),
			"旧槽位必须承载新叶子载荷（int[6] + \"k:\" 拼接）：" + describe(oldSlot));

		// 新链完整：build() -> 容器 -> 叶子。容器若成了幽灵，新代码一跑就熔断。
		String containerName = firstIndyTarget(out, "build");
		MethodNode container = method0(out, containerName);
		assertNotNull(container, "build() 的 indy 目标必须在产物中存在（实际方法表: " + table(out) + "）");
		assertFalse(calls(container, "onOrphanInvoked"), "build() 指向的容器不能是孤儿熔断桩");
		assertEquals(oldLeafName, firstIndyTarget(out, containerName),
			"容器必须仍指向叶子 " + oldLeafName + "（实际: " + firstIndyTarget(out, containerName) + "）");

		// 没有任何方法被静默丢弃，且产物自洽。
		assertEquals(0, ghostCount(out), "体等价搬迁不该产生幽灵（实际方法表: " + table(out) + "）");
		assertEquals(keySet(out).size(), out.methods.size(),
			"产物方法名+描述符必须唯一（实际方法表: " + table(out) + "）");
	}

	/**
	 * (c) 大样本误报：仓库内所有含 lambda 的类，同源 {@code -g} / {@code -g:none} 编译，再把新版本
	 * 的局部变量槽位整体平移。
	 *
	 * <p>两次编译的<b>源码相同</b>，等价性没有争议；槽位平移是捕获表增删带来的同形变化。
	 * 票据指纹（{@code MethodFingerprinter} 计入 {@code var} 下标）会变，因此配对确实要经过
	 * {@code fallbackPairingAllowed} 的体指纹判据——若归一化漏掉槽位，这里就会冒出
	 * {@code BODY_MISMATCH}。断言 <b>0 次</b>，并报告样本数 N。</p>
	 *
	 * <p><b>语料范围（批次 I 第二轮扩到 {@code plugin-*}）</b>：{@code core/src} + {@code api/src} 一次编译
	 * （互相依赖），{@code plugin-fabric}/{@code plugin-mixin}/{@code plugin-forge} 各自单独编译。
	 * 插件的外部第三方依赖（{@code org.spongepowered:mixin}）从本地 Gradle 缓存按坐标发现；
	 * 缓存里没有就<b>不静默跳过</b>，而是把它记进 {@link Corpus#gaps} 并打印
	 * {@code [BodyFingerprintFP-GAP]} 行（缺口在 {@code docs/topology/09-lambda-capture-bridge.md}
	 * 有显式条目）。{@code plugin-arc} 无论缓存有没有都编不动，原因见该方法末尾登记的缺口。</p>
	 */
	@Test
	void bodyFingerprintNeverRefusesSlotShiftedRepoLambdas(@TempDir Path tmp) throws Exception {
		printGateState();
		String javac = javac21();
		String classpath = System.getProperty("java.class.path");
		File repoRoot = new File("..");

		Corpus corpus = new Corpus();

		// ---- 语料 1：core/src + api/src（同一 javac 调用，互相依赖） ----
		List<File> coreApi = javaSources(repoRoot.toPath().resolve("core/src"));
		coreApi.addAll(javaSources(repoRoot.toPath().resolve("api/src")));
		assertTrue(coreApi.size() >= 40, "夹具前提：可编译的源文件太少（" + coreApi.size() + "）");
		Path outG = Files.createDirectories(tmp.resolve("g"));
		Path outNone = Files.createDirectories(tmp.resolve("none"));
		compile(javac, classpath, "-g", outG, coreApi);
		compile(javac, classpath, "-g:none", outNone, coreApi);
		measureCorpus(outG, outNone, "core+api", corpus);

		// ---- 语料 2：plugin-*（各自独立编译；外部依赖从本地 Gradle 缓存按坐标发现） ----
		File mixinJar = pluginDependencyJar("org.spongepowered", "mixin", "0.8.5");
		for (String module : new String[]{"plugin-fabric/src", "plugin-mixin/src", "plugin-forge/src"}) {
			Path root = repoRoot.toPath().resolve(module);
			if (!Files.isDirectory(root)) {
				corpus.gaps.add(module + ": 目录不存在");
				continue;
			}
			List<File> sources = javaSources(root);
			if (sources.isEmpty()) {
				corpus.gaps.add(module + ": 没有 .java 源文件");
				continue;
			}
			boolean needsMixin = module.startsWith("plugin-mixin") || module.startsWith("plugin-forge");
			if (needsMixin && mixinJar == null) {
				corpus.gaps.add(module + ": 需要 org.spongepowered:mixin:0.8.5，本地 Gradle 缓存未命中，"
					+ "离线无法编译（缓存根: " + gradleCacheRoot() + "）");
				continue;
			}
			String cp = mixinJar == null ? classpath : classpath + File.pathSeparator + mixinJar.getAbsolutePath();
			String tag = module.replace('/', '_');
			Path g = Files.createDirectories(tmp.resolve("g_" + tag));
			Path n = Files.createDirectories(tmp.resolve("none_" + tag));
			compile(javac, cp, "-g", g, sources);
			compile(javac, cp, "-g:none", n, sources);
			measureCorpus(g, n, module, corpus);
		}

		// plugin-arc 的显式缺口（与缓存是否命中无关：障碍在 Mindustry 制品本身）。
		corpus.gaps.add("plugin-arc/src: Mindustry 的编译期制品不是纯 class 包"
			+ "（Anuken:Mindustry:v160.5 内含 1264 个未编译的生成 .java，如 mindustry/io/TypeIO.java 依赖"
			+ " @TypeIOHandler 生成的类型）；javac 会把 arc/mindustry 引用解析到这些源文件，"
			+ "随即以'找不到 mindustry.annotations.Annotations'/'找不到 TypeIOHandler'失败，"
			+ "而补齐它们需要 Mindustry 自带的注解处理器（离线缓存里没有该制品）。"
			+ "该缺口在 docs/topology/09-lambda-capture-bridge.md 显式登记。");

		int withLambdas = corpus.withLambdas;
		assertTrue(withLambdas >= 20,
			"样本前提：仓库内含 lambda 的类应 ≥20（实际 N=" + withLambdas + "，分模块: " + corpus.perModule + "）");
		assertTrue(corpus.withLambdasByModule.getOrDefault("core+api", 0) >= 20,
			"样本前提：core+api 语料不得因重构而塌缩（实际 " + corpus.withLambdasByModule + "）");
		assertTrue(corpus.withLambdasByModule.getOrDefault("plugin-fabric/src", 0) > 0
				|| corpus.gaps.stream().anyMatch(g -> g.startsWith("plugin-fabric")),
			"plugin-fabric 无外部依赖，必须真的进语料（实际 " + corpus.withLambdasByModule + "）");
		assertEquals(withLambdas, corpus.aligned, "每个含 lambda 的类都必须对齐成功");
		assertTrue(corpus.refusals.isEmpty(),
			"同源 -g / -g:none + 槽位平移不得产生任何 BODY_MISMATCH（N=" + withLambdas + "）："
				+ String.join(" | ", corpus.refusals));

		System.out.println("[GHOSTATTR] SUMMARY gate=" + GATE_MODE + " N=" + withLambdas
			+ " classesWithGhosts=" + corpus.classesWithGhosts + " totalGhosts=" + corpus.totalGhosts
			+ " totalRefusals=" + corpus.totalRefusals);
		System.out.println("[BodyFingerprintFP] N=" + withLambdas + " classes, BODY_MISMATCH=0"
			+ ", perModule=" + corpus.perModule);
		for (String gap : corpus.gaps) System.out.println("[BodyFingerprintFP-GAP] " + gap);
	}

	/**
	 * 兜底门禁开关的<b>阳性对照</b>：在<b>同一条语料 harness 调用路径</b>（{@code LambdaAligner.align}
	 * + {@code stats} + 产物形态断言）上喂一个 L5 种子，门禁开/关必须给出<b>不同读数</b>。
	 *
	 * <p>动机：§9.7 的幽灵归因依据是"关门禁与开门禁两趟读数逐字节零差异"。零差异只有在
	 * "开关确实改变了被测实现"时才携带信息；若开关没生效，两趟跑的是同一配置，零差异什么都不说明。
	 * 本用例就是那条前提的对照：门禁<b>开</b>时 L5 种子必须被拒绝（{@code BODY_MISMATCH} + 旧槽位
	 * 幽灵化），门禁<b>关</b>时同一对必须静默错绑（无告警、{@code refusals == 0}、旧槽位被内层体顶替）。
	 *
	 * <p>{@code [GATE-STATE]} 行由 {@link #printGateState()} 打印：它读的是<b>实际被加载的</b>
	 * {@code LambdaAligner.class} 字节（数 {@code fallbackPairingAllowed} 调用点），不是源码或开关
	 * 变量的推测值，所以"开关改了但被测类没重编"这类情形会被当场照出来——两趟 {@code alignerSha256}
	 * 相同即证明第二趟没有换实现。
	 *
	 * <p>两种种子形态都跑：L5 原样（两侧 {@code -g}）与套上语料变换的形态（新侧 {@code -g:none} +
	 * 槽位平移 3，即 §9.6/§9.7 语料用的同形改造）。后者用来确认"语料变换本身不把 L5 形态消解掉"。
	 */
	@Test
	void gatePositiveControlOnL5Seed(@TempDir Path tmp) throws Exception {
		boolean gateOn = printGateState();
		String javac = javac21();
		String classpath = System.getProperty("java.class.path");

		Reading plain = runL5Control(tmp.resolve("l5plain"), javac, classpath, false);
		Reading shifted = runL5Control(tmp.resolve("l5shift"), javac, classpath, true);
		String mode = gateOn ? "on" : "off";
		System.out.println("[GATE-CTRL] seed=L5 gate=" + mode + " plain=" + plain + " corpusTransform=" + shifted);

		if (gateOn) {
			assertTrue(plain.refusals > 0, "门禁开：L5 种子必须被拒绝（记 BODY_MISMATCH）: " + plain);
			assertTrue(plain.ghost, "门禁开：旧槽位必须幽灵化（老 CallSite 响亮熔断）: " + plain);
			assertFalse(plain.innerPayload, "门禁开：旧槽位绝不能承载内层体: " + plain);
			assertTrue(plain.events > 0, "门禁开：必须留一条 BODY_MISMATCH 事件: " + plain);
			assertTrue(shifted.refusals > 0, "门禁开：语料变换后的 L5 种子同样必须被拒绝: " + shifted);
			assertTrue(shifted.ghost, "门禁开：语料变换后旧槽位仍必须幽灵化: " + shifted);
			assertTrue(shifted.events > 0, "门禁开：语料变换后仍必须留 BODY_MISMATCH 事件: " + shifted);
		} else {
			assertEquals(0, plain.refusals,
				"门禁关：L5 种子仍被拒绝 —— 门禁开关在语料 harness 里没有生效（两趟其实是同一配置）: " + plain);
			assertFalse(plain.ghost, "门禁关：旧槽位不该幽灵化（应为静默错绑）: " + plain);
			assertTrue(plain.innerPayload, "门禁关：旧槽位必须被内层体静默顶替（这就是错绑形态）: " + plain);
			assertEquals(0, plain.events, "门禁关：不得留 BODY_MISMATCH 事件: " + plain);
		}
		LambdaAligner.clearLoggedWarnings();   // 不把本用例的去重键留给后续用例
	}

	/** L5 阳性对照的一趟读数：计数器 + 旧槽位的终局形态。 */
	static final class Reading {
		final int refusals, passA, passB, events;
		final boolean ghost, innerPayload;

		Reading(int refusals, int passA, int passB, int events, boolean ghost, boolean innerPayload) {
			this.refusals = refusals;
			this.passA = passA;
			this.passB = passB;
			this.events = events;
			this.ghost = ghost;
			this.innerPayload = innerPayload;
		}

		@Override
		public String toString() {
			return "{refusals=" + refusals + " passA=" + passA + " passB=" + passB
				+ " bodyMismatchEvents=" + events + " oldSlotGhost=" + ghost
				+ " oldSlotInnerPayload=" + innerPayload + "}";
		}
	}

	/**
	 * 跑一趟 L5 对照：V1 叶子 {@code () -> "P0:v1"}，V2 把 {@code () -> "P0c:v2"} 包进一层新容器
	 * （javac 后序编号让新内层恰好也叫 {@code lambda$build$0}）。{@code corpusTransform} 为真时
	 * 新侧按语料口径压成 {@code -g:none} 并把局部槽位整体平移 3。
	 */
	static Reading runL5Control(Path dir, String javac, String classpath, boolean corpusTransform) throws Exception {
		Path srcV1 = Files.createDirectories(dir.resolve("src/v1"));
		Path srcV2 = Files.createDirectories(dir.resolve("src/v2"));
		File v1 = writeJava(srcV1.resolve("GateCtrl.java"), l5WrapSource(false));
		File v2 = writeJava(srcV2.resolve("GateCtrl.java"), l5WrapSource(true));
		Path outV1 = Files.createDirectories(dir.resolve("v1"));
		Path outV2 = Files.createDirectories(dir.resolve("v2"));
		compile(javac, classpath, "-g", outV1, List.of(v1));
		compile(javac, classpath, corpusTransform ? "-g:none" : "-g", outV2, List.of(v2));

		byte[] oldBytes = Files.readAllBytes(outV1.resolve("GateCtrl.class"));
		byte[] newBytes = Files.readAllBytes(outV2.resolve("GateCtrl.class"));
		if (corpusTransform) newBytes = shiftLocalSlots(newBytes, 3);

		String oldName = null;
		for (MethodNode mn : parse(oldBytes).methods) {
			if (mn.name.startsWith("lambda$") && "()Ljava/lang/String;".equals(mn.desc)) oldName = mn.name;
		}
		assertNotNull(oldName, "夹具前提：V1 里必须有一个 ()String 叶子");

		LambdaAligner.LAST_RESULT = null;
		LambdaAligner.clearLoggedWarnings();   // 去重缓存是进程级的：每条腿重新计事件
		byte[] aligned = LambdaAligner.align(oldBytes, newBytes);
		assertNotNull(aligned, "对齐产物非空");
		ClassNode out = parse(aligned);
		MethodNode slot = method(out, oldName, "()Ljava/lang/String;");
		assertNotNull(slot, "旧槽位 " + oldName + " 必须留在产物里（" + table(out) + "）");

		int events = 0;
		for (LambdaAligner.AlignmentEvent ev : LambdaAligner.TEST_EVENTS) {
			if ("BODY_MISMATCH".equals(ev.kind)) events++;
		}
		return new Reading(LambdaAligner.LAST_RESULT.stats.bodyMismatchRefusals,
			LambdaAligner.LAST_RESULT.stats.passAPairs,
			LambdaAligner.LAST_RESULT.stats.passBPairs,
			events,
			calls(slot, "onOrphanInvoked"),
			hasStringPayload(slot, "P0c:v2"));
	}

	/**
	 * L5 种子源码（与 {@code LambdaAlignTortureTest} 的 {@code wrapSrc} 同形，但<b>类名独立</b>）。
	 *
	 * <p>类名必须与 torture 套件的 {@code Wrap} 不同：{@code LambdaAligner} 的告警去重键是
	 * {@code 类名#BODY#新<-旧}，同名同方法名会让后跑用例的 {@code BODY_MISMATCH} 事件被去重吞掉
	 * （实测：本用例沿用 {@code Wrap} 时，
	 * {@code LambdaAlignTortureTest#l5AddingInnerLambdaRefusesToRebindOldSlot} 在整套跑里转红）。
	 * 每条腿之前还会 {@link LambdaAligner#clearLoggedWarnings()}，让事件读数完整。</p>
	 */
	static String l5WrapSource(boolean wrapped) {
		StringBuilder sb = new StringBuilder();
		sb.append("import java.util.*;\n");
		sb.append("import java.util.function.*;\n\n");
		sb.append("public class GateCtrl {\n");
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

	static File writeJava(Path target, String source) throws Exception {
		Files.createDirectories(target.getParent());
		Files.write(target, source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return target.toFile();
	}

	/** 方法体里是否出现含 {@code needle} 的字符串常量（用来判定旧槽位跑了谁的逻辑）。 */
	static boolean hasStringPayload(MethodNode mn, String needle) {
		if (mn == null || mn.instructions == null) return false;
		for (AbstractInsnNode insn : mn.instructions) {
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s && s.contains(needle)) return true;
		}
		return false;
	}

	/**
	 * 打印<b>实际生效</b>的兜底门禁状态：解析被加载的 {@code LambdaAligner.class}，数
	 * {@code fallbackPairingAllowed} 的调用点。0 个即"门禁关"（调用点被短路/移除），≥1 即"门禁开"。
	 *
	 * <p>附带类字节长度与 SHA-256：两趟对照若 SHA 相同，就证明第二趟没有换实现，读数零差异不可用。
	 */
	static boolean printGateState() throws Exception {
		byte[] bytes = loadedAlignerBytes();
		ClassNode cn = parse(bytes);
		int sites = 0;
		for (MethodNode mn : cn.methods) {
			if (mn.instructions == null) continue;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof MethodInsnNode mi && "fallbackPairingAllowed".equals(mi.name)) sites++;
			}
		}
		boolean on = sites > 0;
		GATE_MODE = on ? "on" : "off";
		System.out.println("[GATE-STATE] bodyFingerprint=" + GATE_MODE
			+ " callSites=" + sites + " alignerBytes=" + bytes.length + " alignerSha256=" + sha256(bytes));
		return on;
	}

	/** 本次进程里实际生效的门禁状态（由 {@link #printGateState()} 从被测类字节解析后写入）。 */
	static String GATE_MODE = "unknown";

	/** 读被测类的实际字节（从类自身所在资源取，不受源码/开关变量推测影响）。 */
	static byte[] loadedAlignerBytes() throws Exception {
		try (InputStream in = LambdaAligner.class.getResourceAsStream("LambdaAligner.class")) {
			assertNotNull(in, "必须能读到被测的 LambdaAligner.class 资源");
			java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
			return bos.toByteArray();
		}
	}

	static String sha256(byte[] bytes) throws Exception {
		byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
		StringBuilder sb = new StringBuilder();
		for (byte b : d) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}

	/** 语料统计（跨模块累加）。 */
	static class Corpus {
		int withLambdas;
		int aligned;
		int totalGhosts;
		int classesWithGhosts;
		int totalRefusals;
		final List<String> refusals = new ArrayList<>();
		final List<String> gaps = new ArrayList<>();
		final Map<String, Integer> withLambdasByModule = new LinkedHashMap<>();
		final Map<String, Integer> classesScannedByModule = new LinkedHashMap<>();
		String perModule = "";
	}

	/** 跑一个已编译语料目录：逐类对齐、统计含 lambda 的样本数与 BODY_MISMATCH 次数。 */
	static void measureCorpus(Path outG, Path outNone, String tag, Corpus corpus) throws Exception {
		int scanned = 0, withLambdas = 0, aligned = 0;
		List<Path> classes;
		try (Stream<Path> st = Files.walk(outG)) {
			classes = st.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList());
		}
		for (Path p : classes) {
			Path q = outNone.resolve(outG.relativize(p).toString());
			if (!Files.exists(q)) continue;
			scanned++;
			byte[] oldBytes = Files.readAllBytes(p);
			ClassNode cn = parse(oldBytes);
			int lambdas = 0;
			for (MethodNode mn : cn.methods) if (mn.name.startsWith("lambda$")) lambdas++;
			if (lambdas == 0) continue;                       // 不含 lambda 的类不构成本组样本
			withLambdas++;

			// 新版本 = 同源 -g:none 编译 + 槽位整体平移（等价性由源码相同保证，与体指纹无关）。
			byte[] newBytes = shiftLocalSlots(Files.readAllBytes(q), 3);
			LambdaAligner.LAST_RESULT = null;
			LambdaAligner.TEST_EVENTS.clear();
			byte[] product = LambdaAligner.align(oldBytes, newBytes);
			assertNotNull(product, "对齐产物非空: " + p);
			aligned++;
			// 逐类机器读数（含门禁状态）：幽灵归因的对照就跑在这些行上——两趟若行内 gate 字段不同、
			// 其余字段逐字节相同，才说明"这批幽灵与门禁无关"；若两趟 gate 相同，则零差异不携带信息。
			int lambdasNew = 0;
			for (MethodNode mn : parse(newBytes).methods) if (mn.name.startsWith("lambda$")) lambdasNew++;
			int ghosts = ghostCount(parse(product));
			int refusals = LambdaAligner.LAST_RESULT.stats.bodyMismatchRefusals;
			System.out.println("[GHOSTATTR] gate=" + GATE_MODE + " tag=" + tag
				+ " class=" + outG.relativize(p).toString().replace('\\', '/')
				+ " lambdasOld=" + lambdas + " lambdasNew=" + lambdasNew + " ghosts=" + ghosts
				+ " refusals=" + refusals
				+ " step1=" + LambdaAligner.LAST_RESULT.stats.step1Pairs
				+ " passA=" + LambdaAligner.LAST_RESULT.stats.passAPairs
				+ " passB=" + LambdaAligner.LAST_RESULT.stats.passBPairs);
			corpus.totalGhosts += ghosts;
			corpus.totalRefusals += refusals;
			if (ghosts > 0) corpus.classesWithGhosts++;
			if (refusals > 0) {
				corpus.refusals.add(tag + "/" + outG.relativize(p) + " lambdas=" + lambdas + " "
					+ LambdaAligner.LAST_RESULT.stats);
			}
		}
		corpus.withLambdas += withLambdas;
		corpus.aligned += aligned;
		corpus.withLambdasByModule.put(tag, withLambdas);
		corpus.classesScannedByModule.put(tag, scanned);
		StringBuilder sb = new StringBuilder(corpus.perModule);
		if (sb.length() > 0) sb.append(", ");
		sb.append(tag).append('=').append(withLambdas).append('/').append(scanned);
		corpus.perModule = sb.toString();
	}

	/** 递归收集目录下的 {@code .java} 源文件。 */
	static List<File> javaSources(Path root) throws Exception {
		List<File> out = new ArrayList<>();
		if (!Files.isDirectory(root)) return out;
		try (Stream<Path> st = Files.walk(root)) {
			st.filter(p -> p.toString().endsWith(".java")).forEach(p -> out.add(p.toFile()));
		}
		out.sort(java.util.Comparator.comparing(File::getAbsolutePath));
		return out;
	}

	/** 本地 Gradle 缓存的 {@code modules-2/files-2.1} 根；取不到返回 {@code null}。 */
	static File gradleCacheRoot() {
		List<File> candidates = new ArrayList<>();
		String guh = System.getenv("GRADLE_USER_HOME");
		if (guh != null && !guh.isEmpty()) candidates.add(new File(guh));
		String home = System.getProperty("user.home");
		if (home != null && !home.isEmpty()) candidates.add(new File(home, ".gradle"));
		for (File g : candidates) {
			File c = new File(g, "caches/modules-2/files-2.1");
			if (c.isDirectory()) return c;
		}
		return null;
	}

	/**
	 * 按 Maven 坐标从本地 Gradle 缓存里找一个<b>非 sources</b> jar。
	 *
	 * <p>只用于给 {@code plugin-*} 语料补第三方 {@code compileOnly} 依赖；缓存未命中就返回
	 * {@code null}，由调用方登记显式缺口（不静默降低覆盖面）。</p>
	 */
	static File pluginDependencyJar(String group, String artifact, String version) {
		File cache = gradleCacheRoot();
		if (cache == null) return null;
		File moduleDir = new File(new File(cache, group), artifact);
		File versionDir = new File(moduleDir, version);
		if (!versionDir.isDirectory()) return null;
		File[] hashes = versionDir.listFiles(File::isDirectory);
		if (hashes == null) return null;
		java.util.Arrays.sort(hashes, java.util.Comparator.comparing(File::getName));
		for (File hash : hashes) {
			File[] jars = hash.listFiles(f -> f.isFile() && f.getName().endsWith(".jar")
				&& !f.getName().endsWith("-sources.jar") && !f.getName().endsWith("-javadoc.jar"));
			if (jars == null || jars.length == 0) continue;
			java.util.Arrays.sort(jars, java.util.Comparator.comparing(File::getName));
			return jars[0];
		}
		return null;
	}


	//region 夹具：等价常量改写（BIPUSH 6 → LDC 2; LDC 3; IMUL）
	/** 把叶子里的 {@code BIPUSH 6} 改写成 {@code LDC 2; LDC 3; IMUL}（值等价、指令形态不同）。 */
	static byte[] foldConstantRewrite(byte[] bytes) {
		ClassNode cn = parse(bytes);
		boolean rewrote = false;
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$") || mn.instructions == null) continue;
			for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof IntInsnNode ii) || ii.getOpcode() != Opcodes.BIPUSH || ii.operand != 6) continue;
				InsnList replace = new InsnList();
				replace.add(new LdcInsnNode(2));
				replace.add(new LdcInsnNode(3));
				replace.add(new InsnNode(Opcodes.IMUL));
				mn.instructions.insert(ii, replace);
				mn.instructions.remove(ii);
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
	//endregion

	//region 夹具：局部变量槽位整体平移
	/** 把局部变量槽整体平移（{@code VarInsn} / {@code IincInsn} / LVT / maxLocals）。 */
	static byte[] shiftLocalSlots(byte[] bytes, int delta) {
		ClassNode cn = parse(bytes);
		for (MethodNode mn : cn.methods) {
			if (mn.instructions == null) continue;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof VarInsnNode v) v.var += delta;
				else if (insn instanceof IincInsnNode i) i.var += delta;
			}
			if (mn.localVariables != null) for (LocalVariableNode lv : mn.localVariables) lv.index += delta;
			if (mn.maxLocals > 0) mn.maxLocals += delta;
		}
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}
	//endregion

	/** 满仓编译一批源文件（关掉注解处理，内部 API 走 add-exports）。 */
	static void compile(String javac, String classpath, String debug, Path out, List<File> sources) throws Exception {
		List<String> cmd = new ArrayList<>(Arrays.asList(javac, "-nowarn", "-proc:none", "-encoding", "UTF-8", debug,
			"--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
			"--add-exports", "java.base/jdk.internal.reflect=ALL-UNNAMED",
			"--add-exports", "java.base/jdk.internal.loader=ALL-UNNAMED",
			"-cp", classpath, "-d", out.toString()));
		for (File f : sources) cmd.add(f.getAbsolutePath());
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		StringBuilder log = new StringBuilder();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
			String line;
			while ((line = r.readLine()) != null) log.append(line).append('\n');
		}
		int rc = p.waitFor();
		if (rc != 0) throw new IllegalStateException("javac " + debug + " 失败（rc=" + rc + "）：\n"
			+ log.substring(0, Math.min(4000, log.length())));
	}

	/** 叶子载荷是否还在：既接受 {@code BIPUSH 6}，也接受折叠后的 {@code LDC 2 / LDC 3 / IMUL}。 */
	static boolean newArrayPayloadKept(MethodNode mn) {
		boolean newArray = false, concat = false;
		for (AbstractInsnNode insn : mn.instructions) {
			if (insn.getOpcode() == Opcodes.NEWARRAY) newArray = true;
			if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
				for (Object arg : indy.bsmArgs) {
					if (arg instanceof String s && s.startsWith("k:")) concat = true;
				}
			}
		}
		return newArray && concat;
	}

	static String describe(MethodNode mn) {
		StringBuilder sb = new StringBuilder(mn.name + mn.desc + " ops=[");
		for (AbstractInsnNode insn : mn.instructions) {
			int op = insn.getOpcode();
			if (op >= 0) sb.append(org.objectweb.asm.util.Printer.OPCODES[op]).append(' ');
		}
		return sb.append(']').toString();
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

	static MethodNode method0(ClassNode cn, String name) {
		if (name == null) return null;
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name)) return mn;
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
		if (methodName == null) return null;
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
