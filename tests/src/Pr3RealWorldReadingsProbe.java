import remold.AnnotationTransformer;
import remold.LambdaAligner;
import remold.vcs.AlignmentMetaStore;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * PR3 真实规模语料读数探针：<b>只测量、不下结论、不改产品代码</b>。
 *
 * <p>回答三个读数问题（对应战役不变量 M1／M2／M3）：</p>
 * <ol>
 *   <li><b>M1</b>：{@code forceStaticLambdas} 在本语料上命中多少类、这些类在 Run R 下的
 *       {@code runRGate} 直方图长什么样（是否真的落到 {@code RAW_PREV_MISMATCH}）。</li>
 *   <li><b>M2</b>：{@code Raw_prev} blob 的真实占用（{@code blobBytes()}）、预算告罄导致的丢弃
 *       （{@code droppedBlobCount()}）、以及每次 {@code commit} 的墙钟耗时分布——用来验证或推翻
 *       "提交成本随条目数线性增长（{@code blobBytes()} 全仓扫描）"这一推断。</li>
 *   <li><b>M3</b>：{@code runRGate} 与 {@code positionalFallbacks} 的真实分布，并把"缺 LVT"
 *       与"Run R／Run E 基线差异（E_prev 幽灵与桥在字符串常量见证表里缺失）"分开计数。</li>
 * </ol>
 *
 * <p><b>为什么是外部探针而不是单测</b>：这些读数依赖真实规模的类集合与真实 {@code align()} 行为，
 * 断言化只会把它们变成"某一轮恰好成立的数字"。本探针不做断言，只打印压缩表。</p>
 *
 * <p><b>语料入口全部走系统属性</b>（探针内不含任何仓库路径或外部工程知识）：</p>
 * <ul>
 *   <li>{@code -Dpr3.corpus.new=<dir>}：新一代 class 目录，缺省 {@code <remold.projectRoot>/core/build/classes/java/main}；</li>
 *   <li>{@code -Dpr3.corpus.old=<dir>}：上一代 class 目录；缺省时退回"同目录模式"（gen1 == gen2）；</li>
 *   <li>{@code -Dpr3.corpus.limit=<n>}：采样上限（默认 400，0 = 全部），按内部名稳定排序后截断；</li>
 *   <li>{@code -Dpr3.arm=main|budget}：主档／预算对照档（预算档由独立 JVM 以小额
 *       {@code -Dremold.vcs.meta_blob_bytes} 启动，因为预算在类初始化时定值）。</li>
 * </ul>
 *
 * <p><b>测量纪律</b>：所有阶段在同一次进程内、同一语料集合、同一采样顺序上完成；耗时一律报
 * min/median/p90/max 与分位对比，不给单一聚合值。任何类解析／对齐异常都计入 {@code failed}
 * 并体现在汇总里，不被记成成功。</p>
 */
public final class Pr3RealWorldReadingsProbe {

	/** 目录里读出的一个类样本。 */
	private static final class Sample {
		final String internalName;
		final byte[] gen1;
		final byte[] gen2;
		byte[] gen1Forced;
		byte[] gen2Forced;
		boolean forced1Changed;
		boolean forced2Changed;
		int indyLambdaSites;
		int lambdaMethods;
		boolean missingLvt;

		Sample(String internalName, byte[] gen1, byte[] gen2) {
			this.internalName = internalName;
			this.gen1 = gen1;
			this.gen2 = gen2;
		}

		String dotName() {
			return internalName.replace('/', '.');
		}
	}

	/** 一轮（一个阶段）的读数累加器。 */
	private static final class Phase {
		final String name;
		final Map<String, Integer> gate = new TreeMap<>();
		final Map<String, Integer> baseline = new TreeMap<>();
		final Map<String, Integer> posHist = new TreeMap<>();
		final Map<String, Integer> gateAmongForced = new TreeMap<>();
		final Map<String, Integer> gateAmongMissingLvt = new TreeMap<>();
		final Map<String, Integer> gateAmongNoMissingLvt = new TreeMap<>();
		final Map<String, Integer> gateAmongLambdaSite = new TreeMap<>();
		final Map<String, Integer> posAmongMissingLvt = new TreeMap<>();
		final Map<String, Integer> posAmongHasLvt = new TreeMap<>();
		/** 逐类的 positionalFallbacks（-1 = 该类本轮没有结论），用于 Run R / Run E 的逐类配对。 */
		int[] posByClass = new int[0];
		final List<Long> commitNanos = new ArrayList<>();
		final List<Long> alignNanos = new ArrayList<>();
		int ok;
		int failed;
		int rawToEffEmpty;
		final List<Integer> rawToEffSizes = new ArrayList<>();
		long blobBytes;
		long blobBudget;
		int droppedBlobs;
		int headCount;
		int historyCount;

		Phase(String name) {
			this.name = name;
		}

		void addGate(Map<String, Integer> target, String key) {
			target.merge(key == null ? "<null>" : key, 1, Integer::sum);
		}

		void initPerClass(int n) {
			posByClass = new int[n];
			Arrays.fill(posByClass, -1);
		}

		void clearStoreReadings() {
			AlignmentMetaStore store = AlignmentMetaStore.INSTANCE;
			blobBytes = store.blobBytes();
			droppedBlobs = store.droppedBlobCount();
			headCount = store.headCount();
			historyCount = store.historyCount();
			blobBudget = AlignmentMetaStore.BLOB_BUDGET_BYTES;
		}
	}

	private Pr3RealWorldReadingsProbe() {
	}

	public static void main(String[] args) throws Exception {
		try {
			run();
		} catch (Throwable t) {
			System.out.println("PR3-READINGS FAILED: " + t.getClass().getName() + ": " + t.getMessage());
			t.printStackTrace(System.out);
			System.exit(1);
		}
	}

	private static void run() throws Exception {
		String arm = System.getProperty("pr3.arm", "main").trim();
		Path projectRoot = Path.of(System.getProperty("remold.projectRoot", ".")).toAbsolutePath().normalize();
		Path newDir = resolve(projectRoot, System.getProperty("pr3.corpus.new",
			"core/build/classes/java/main"));
		String oldProp = System.getProperty("pr3.corpus.old");
		Path oldDir = oldProp == null || oldProp.isBlank() ? null : resolve(projectRoot, oldProp);
		int limit = Integer.getInteger("pr3.corpus.limit", 400);
		boolean sameDirMode = oldDir == null;
		Path gen1Dir = sameDirMode ? newDir : oldDir;

		System.out.println("=== PR3 REAL-WORLD READINGS ===");
		System.out.println("arm=" + arm + " mode=" + (sameDirMode ? "same-dir(gen1==gen2)" : "two-gen")
			+ " limit=" + (limit == 0 ? "all" : String.valueOf(limit)));
		System.out.println("corpus.new=" + newDir);
		System.out.println("corpus.old=" + (sameDirMode ? "<same as new>" : gen1Dir.toString()));
		System.out.println("metaBlobBudget=" + AlignmentMetaStore.BLOB_BUDGET_BYTES
			+ "B perClassCap=" + AlignmentMetaStore.PER_CLASS_BLOB_BYTES + "B");

		List<Sample> samples = load(gen1Dir, newDir, sameDirMode, limit);
		System.out.println("corpus.files: selected=" + samples.size());
		if (samples.isEmpty()) {
			System.out.println("PR3-READINGS EMPTY CORPUS: nothing to measure");
			return;
		}

		// ---- 预备：一份字节的静态特征（forceStaticLambdas 是否改写、有无 lambda 站点、是否缺 LVT） ----
		ClassLoader loader = Pr3RealWorldReadingsProbe.class.getClassLoader();
		int parseFailed = 0;
		List<Sample> usable = new ArrayList<>(samples.size());
		for (Sample s : samples) {
			try {
				s.gen1Forced = AnnotationTransformer.forceStaticLambdas(s.gen1, s.internalName, loader);
				s.gen2Forced = AnnotationTransformer.forceStaticLambdas(s.gen2, s.internalName, loader);
				s.forced1Changed = !Arrays.equals(s.gen1, s.gen1Forced);
				s.forced2Changed = !Arrays.equals(s.gen2, s.gen2Forced);
				ClassNode cn = readFull(s.gen1);
				int indy = 0;
				int lambdas = 0;
				boolean missingLvt = false;
				for (MethodNode mn : cn.methods) {
					if (mn.instructions != null) {
						for (AbstractInsnNode insn : mn.instructions) {
							if (insn instanceof InvokeDynamicInsnNode indyInsn
								&& indyInsn.bsm != null
								&& indyInsn.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
								indy++;
							}
						}
					}
					if (isLambdaMethodName(mn.name)) {
						lambdas++;
						if (mn.localVariables == null || mn.localVariables.isEmpty()) missingLvt = true;
					}
				}
				s.indyLambdaSites = indy;
				s.lambdaMethods = lambdas;
				s.missingLvt = missingLvt;
				usable.add(s);
			} catch (Throwable t) {
				parseFailed++;
				System.out.println("[skip] " + s.internalName + ": " + t.getClass().getSimpleName()
					+ ": " + oneLine(t.getMessage()));
			}
		}
		System.out.println("corpus.parsed=" + usable.size() + " parse-failed=" + parseFailed);
		printStaticSummary(usable);

		// ---- 阶段 E 先跑：既取 M3 的兼容跑道基线，又当作 align 的热身轮（顺序在输出里明示） ----
		Phase phaseE = measureRunE(usable, loader);
		Phase phaseRaw = measureRunR(usable, loader, false);
		Phase phaseForced = measureRunR(usable, loader, true);
		Phase phaseMixed = measureGateControl(usable, loader);

		printPhase(phaseE);
		printPhase(phaseRaw);
		printPhase(phaseForced);
		printPhase(phaseMixed);

		printM3Delta(phaseRaw, phaseE);
		printCommitScaling(phaseRaw, phaseForced);
		printFinalVerdict(phaseRaw, phaseForced, phaseMixed);
	}

	// ---------------------------------------------------------------------------------------------
	// 阶段实现
	// ---------------------------------------------------------------------------------------------

	/**
	 * Run E（兼容跑道）：{@code META_SOURCE=false}，旧侧 = 上一代生效字节。
	 *
	 * <p>同一份 {@code gen1Forced} 既当"上一代生效字节"，也是 Run R 阶段的 {@code E_prev}，
	 * 因此两阶段的差异只来自"配对基线"这一项，可直接相减得到 M3 的基线归因。</p>
	 */
	private static Phase measureRunE(List<Sample> samples, ClassLoader loader) {
		Phase p = new Phase("E(runE)");
		p.initPerClass(samples.size());
		LambdaAligner.META_SOURCE = false;
		AlignmentMetaStore.INSTANCE.reset();
		for (int i = 0; i < samples.size(); i++) {
			Sample s = samples.get(i);
			try {
				long t0 = System.nanoTime();
				LambdaAligner.align(s.gen1Forced, s.gen2, null, null, Set.of(), loader);
				p.alignNanos.add(System.nanoTime() - t0);
				LambdaAligner.AlignmentResult r = LambdaAligner.lastResultFor(s.internalName);
				record(p, i, s, r);
				p.ok++;
			} catch (Throwable t) {
				p.failed++;
				noteFailure(p, s, t);
			}
		}
		p.clearStoreReadings();
		return p;
	}

	/**
	 * Run R（Raw ↔ Raw）：先按"上一代"的形态记一代元数据，再跑本轮对齐。
	 *
	 * <p>{@code forcedVariant=false}（主档）：元数据记录 {@code preAlign = gen1}（未归一化）而
	 * {@code alignOutput/jvmEffective = gen1Forced}，对齐输入是 {@code gen1Forced → gen2}；
	 * 这是"blob 存上一轮待对齐字节、生效侧是归一化形态"的形态，也是 M1 关心的"forceStaticLambdas
	 * 是否会让 {@code RAW_PREV_MISMATCH} 变多"的直接观测。</p>
	 *
	 * <p>{@code forcedVariant=true}（对照档）：两侧都用 {@code forceStaticLambdas} 归一化后的形态
	 * （{@code preAlign = alignOutput = gen1Forced}，{@code gen1Forced → gen2Forced}），即生产链
	 * "两侧同形态"的形态。</p>
	 */
	private static Phase measureRunR(List<Sample> samples, ClassLoader loader, boolean forcedVariant) {
		Phase p = new Phase(forcedVariant ? "R(forced-both)" : "R(rawBlob)");
		p.initPerClass(samples.size());
		AlignmentMetaStore.INSTANCE.reset();
		LambdaAligner.META_SOURCE = true;
		for (int i = 0; i < samples.size(); i++) {
			Sample s = samples.get(i);
			try {
				byte[] preAlign = forcedVariant ? s.gen1Forced : s.gen1;
				byte[] eff = s.gen1Forced;
				byte[] newSide = forcedVariant ? s.gen2Forced : s.gen2;
				Map<String, String> translation = translationTable(preAlign, eff);
				long c0 = System.nanoTime();
				AlignmentMetaStore.INSTANCE.commit(loader, s.internalName, preAlign, eff, eff,
					Set.of(), Map.of(), Map.of(), 0, translation);
				p.commitNanos.add(System.nanoTime() - c0);

				long t0 = System.nanoTime();
				LambdaAligner.align(eff, newSide, null, null, Set.of(), loader);
				p.alignNanos.add(System.nanoTime() - t0);
				LambdaAligner.AlignmentResult r = LambdaAligner.lastResultFor(s.internalName);
				record(p, i, s, r);
				p.ok++;
			} catch (Throwable t) {
				p.failed++;
				noteFailure(p, s, t);
			}
		}
		p.clearStoreReadings();
		return p;
	}

	/**
	 * 闸门判定力对照：把已提交条目的 {@code R_prev} blob 换成另一份字节，再对齐，
	 * 期望 {@code runRGate = RAW_PREV_MISMATCH}。
	 *
	 * <p>为什么需要它：真实语料上 {@code RAW_PREV_MISMATCH} 的计数如果恒为 0，无法区分
	 * "门真的判过" 与 "门根本没起作用"。这里用反射只改<b>探针自己刚写进去的</b>那一条记录的
	 * blob（不改产品代码、不改产品状态的生命周期），取前 {@value #CONTROL_CLASSES} 个类做对照。</p>
	 */
	private static Phase measureGateControl(List<Sample> samples, ClassLoader loader) {
		int budget = Math.min(CONTROL_CLASSES, samples.size());
		Phase p = new Phase("C(blob-corrupt)");
		p.initPerClass(budget);
		AlignmentMetaStore.INSTANCE.reset();
		LambdaAligner.META_SOURCE = true;
		for (int i = 0; i < budget; i++) {
			Sample s = samples.get(i);
			try {
				byte[] eff = s.gen1Forced;
				Map<String, String> translation = translationTable(s.gen1, eff);
				AlignmentMetaStore.INSTANCE.commit(loader, s.internalName, s.gen1, eff, eff,
					Set.of(), Map.of(), Map.of(), 0, translation);
				// 换成"另一个类"的待对齐字节：哈希必然与记录不符，同时类名也对不上。
				Sample donor = samples.get((i + 1) % samples.size());
				replaceRawPrev(loader, s.internalName, donor.gen1);
				long t0 = System.nanoTime();
				LambdaAligner.align(eff, s.gen2, null, null, Set.of(), loader);
				p.alignNanos.add(System.nanoTime() - t0);
				LambdaAligner.AlignmentResult r = LambdaAligner.lastResultFor(s.internalName);
				record(p, i, s, r);
				p.ok++;
			} catch (Throwable t) {
				p.failed++;
				noteFailure(p, s, t);
			}
		}
		p.clearStoreReadings();
		return p;
	}

	private static final int CONTROL_CLASSES = 25;

	/** 反射替换探针刚提交的那条记录的 {@code R_prev} blob（仅用于闸门判定力对照）。 */
	private static void replaceRawPrev(ClassLoader loader, String internalName, byte[] bytes) throws Exception {
		AlignmentMetaStore.Entry e = AlignmentMetaStore.INSTANCE.head(loader, internalName);
		if (e == null) throw new IllegalStateException("no head entry for " + internalName);
		Field f = AlignmentMetaStore.Entry.class.getDeclaredField("rawPrevBytes");
		f.setAccessible(true);
		f.set(e, bytes.clone());
	}

	private static void record(Phase p, int index, Sample s, LambdaAligner.AlignmentResult r) {
		if (r == null) {
			p.addGate(p.gate, "<no-result>");
			p.addGate(p.baseline, "<no-result>");
			return;
		}
		String gate = String.valueOf(r.runRGate);
		p.addGate(p.gate, gate);
		p.addGate(p.baseline, String.valueOf(r.baselineTree));
		String posBucket = bucket(r.positionalFallbacks);
		p.posHist.merge(posBucket, 1, Integer::sum);
		if (index >= 0 && index < p.posByClass.length) p.posByClass[index] = r.positionalFallbacks;
		if (s.forced1Changed || s.forced2Changed) p.addGate(p.gateAmongForced, gate);
		p.addGate(s.missingLvt ? p.gateAmongMissingLvt : p.gateAmongNoMissingLvt, gate);
		if (s.indyLambdaSites > 0 || s.lambdaMethods > 0) p.addGate(p.gateAmongLambdaSite, gate);
		(s.missingLvt ? p.posAmongMissingLvt : p.posAmongHasLvt).merge(posBucket, 1, Integer::sum);
		if (r.rawToEffKeys.isEmpty()) p.rawToEffEmpty++;
		else p.rawToEffSizes.add(r.rawToEffKeys.size());
	}

	private static void noteFailure(Phase p, Sample s, Throwable t) {
		if (p.failed <= 5) {
			System.out.println("[failed] " + p.name + " " + s.internalName + ": "
				+ t.getClass().getSimpleName() + ": " + oneLine(t.getMessage()));
		}
	}

	// ---------------------------------------------------------------------------------------------
	// 输出
	// ---------------------------------------------------------------------------------------------

	private static void printStaticSummary(List<Sample> samples) {
		int n = samples.size();
		int forcedChanged = 0;
		int forcedChangedOfLambda = 0;
		int withSite = 0;
		int missingLvt = 0;
		List<Integer> forcedDelta = new ArrayList<>();
		for (Sample s : samples) {
			boolean lambda = s.indyLambdaSites > 0 || s.lambdaMethods > 0;
			if (lambda) withSite++;
			if (s.missingLvt) missingLvt++;
			if (s.forced1Changed || s.forced2Changed) {
				forcedChanged++;
				if (lambda) forcedChangedOfLambda++;
				forcedDelta.add((s.gen1Forced.length - s.gen1.length)
					+ (s.gen2Forced.length - s.gen2.length));
			}
		}
		System.out.println("classes.withLambdaSite=" + withSite + " (" + pct(withSite, n) + "%)"
			+ " withoutSite=" + (n - withSite)
			+ " missingLvt=" + missingLvt + " (" + pct(missingLvt, n) + "%)");
		System.out.println("forceStaticLambdas.rewritten=" + forcedChanged + " (" + pct(forcedChanged, n) + "% of classes, "
			+ pct(forcedChangedOfLambda, withSite) + "% of lambda classes)"
			+ " sizeDelta sum/min/median/max=" + sum(forcedDelta) + "/" + statLine(forcedDelta));
	}

	private static void printPhase(Phase p) {
		System.out.println("--- phase " + p.name + " ---");
		System.out.println("  ok=" + p.ok + " failed=" + p.failed);
		System.out.println("  runRGate=" + dist(p.gate, p.ok));
		System.out.println("  baselineTree=" + dist(p.baseline, p.ok));
		System.out.println("  positionalFallbacks=" + dist(p.posHist, p.ok));
		System.out.println("  rawToEffKeys: empty=" + p.rawToEffEmpty
			+ " nonEmpty=" + p.rawToEffSizes.size() + " size(min/median/max)="
			+ statLine(new ArrayList<>(sorted(p.rawToEffSizes))));
		if (!p.gateAmongForced.isEmpty()) {
			System.out.println("  runRGate|forceStaticLambdasHit=" + dist(p.gateAmongForced, count(p.gateAmongForced)));
		}
		if (!p.gateAmongMissingLvt.isEmpty() || !p.gateAmongNoMissingLvt.isEmpty()) {
			System.out.println("  runRGate|missingLvt=" + dist(p.gateAmongMissingLvt, count(p.gateAmongMissingLvt))
				+ " runRGate|hasLvt=" + dist(p.gateAmongNoMissingLvt, count(p.gateAmongNoMissingLvt)));
		}
		if (!p.gateAmongLambdaSite.isEmpty()) {
			System.out.println("  runRGate|hasLambdaSite=" + dist(p.gateAmongLambdaSite, count(p.gateAmongLambdaSite)));
		}
		if (!p.posAmongMissingLvt.isEmpty() || !p.posAmongHasLvt.isEmpty()) {
			System.out.println("  positionalFallbacks|missingLvt="
				+ dist(p.posAmongMissingLvt, count(p.posAmongMissingLvt))
				+ " positionalFallbacks|hasLvt=" + dist(p.posAmongHasLvt, count(p.posAmongHasLvt)));
		}
		System.out.println("  store: blobBytes=" + p.blobBytes + "B / budget=" + p.blobBudget + "B ("
			+ pct(p.blobBytes, Math.max(1, p.blobBudget)) + "%) droppedBlobCount=" + p.droppedBlobs
			+ " head=" + p.headCount + " history=" + p.historyCount);
		System.out.println("  alignWallClock(ms) n=" + p.alignNanos.size() + " " + ms(p.alignNanos));
		if (!p.commitNanos.isEmpty()) {
			System.out.println("  commitWallClock(ms) n=" + p.commitNanos.size() + " " + ms(p.commitNanos));
		}
	}

	/** M3：Run R 与 Run E 的 positionalFallbacks 逐类配对比较（同一语料、同一次进程、逐类下标对齐）。 */
	private static void printM3Delta(Phase raw, Phase e) {
		int better = 0;
		int equal = 0;
		int worse = 0;
		int paired = 0;
		int rawOnly = 0;
		int eOnly = 0;
		for (int i = 0; i < raw.posByClass.length; i++) {
			int a = raw.posByClass[i];
			int b = i < e.posByClass.length ? e.posByClass[i] : -1;
			if (a < 0 && b < 0) continue;
			if (a >= 0 && b < 0) { rawOnly++; continue; }
			if (a < 0 && b >= 0) { eOnly++; continue; }
			paired++;
			if (a < b) better++;
			else if (a > b) worse++;
			else equal++;
		}
		System.out.println("--- M3 delta (per-class pairing: rawBlob vs runE) ---");
		System.out.println("  pairedClasses=" + paired + " fewerFallbacksInRunR=" + better
			+ " equal=" + equal + " moreFallbacksInRunR=" + worse
			+ " rawOnlyResult=" + rawOnly + " runEOnlyResult=" + eOnly);
		System.out.println("  fallbackTotalClasses rawBlob=" + classesWithFallback(raw.posByClass)
			+ " runE=" + classesWithFallback(e.posByClass)
			+ " fallbackSum rawBlob=" + fallbackSum(raw.posByClass) + " runE=" + fallbackSum(e.posByClass));
	}

	private static int classesWithFallback(int[] pos) {
		int n = 0;
		for (int v : pos) if (v > 0) n++;
		return n;
	}

	private static int fallbackSum(int[] pos) {
		int n = 0;
		for (int v : pos) if (v > 0) n += v;
		return n;
	}

	/** M2：提交耗时随仓内条目数增长的趋势（同一轮内的首尾四分位对比）。 */
	private static void printCommitScaling(Phase a, Phase b) {
		System.out.println("--- M2 commit scaling (per-phase, first vs last quartile) ---");
		printQuartiles(a);
		printQuartiles(b);
	}

	private static void printQuartiles(Phase p) {
		if (p.commitNanos.size() < 8) {
			System.out.println("  " + p.name + ": n=" + p.commitNanos.size() + " (too few for quartiles)");
			return;
		}
		long[] all = toArray(p.commitNanos);
		int q = all.length / 4;
		long[] head = Arrays.copyOfRange(all, 0, q);
		long[] tail = Arrays.copyOfRange(all, all.length - q, all.length);
		System.out.println("  " + p.name + ": n=" + all.length
			+ " totalMs=" + fmt(nanosToMs(sumNanos(all)))
			+ " firstQuartileMedianMs=" + fmt(nanosToMs(medianOf(head)))
			+ " lastQuartileMedianMs=" + fmt(nanosToMs(medianOf(tail)))
			+ " (store entries grew from about " + (q == 0 ? 0 : 1) + " to " + all.length + ")");
	}

	private static void printFinalVerdict(Phase raw, Phase forced, Phase control) {
		System.out.println("--- M1 summary (forceStaticLambdas vs Run R gate) ---");
		System.out.println("  rawBlob: gate=" + dist(raw.gate, raw.ok));
		System.out.println("  forced-both: gate=" + dist(forced.gate, forced.ok));
		System.out.println("  control(blob-corrupted): gate=" + dist(control.gate, control.ok)
			+ "   <- nonzero RAW_PREV_MISMATCH here is what proves the gate is live");
		System.out.println("PR3-READINGS DONE");
	}

	// ---------------------------------------------------------------------------------------------
	// 语料读取与工具
	// ---------------------------------------------------------------------------------------------

	private static Path resolve(Path projectRoot, String raw) {
		Path p = Path.of(raw);
		return (p.isAbsolute() ? p : projectRoot.resolve(p)).toAbsolutePath().normalize();
	}

	private static List<Sample> load(Path gen1Dir, Path gen2Dir, boolean sameDir, int limit) throws IOException {
		List<String> names = listClassNames(gen1Dir);
		if (!sameDir) names.retainAll(listClassNames(gen2Dir));
		names.sort(Comparator.naturalOrder());
		if (limit > 0 && names.size() > limit) names = names.subList(0, limit);
		List<Sample> out = new ArrayList<>(names.size());
		for (String name : names) {
			byte[] g1;
			byte[] g2;
			try {
				g1 = read(gen1Dir, name);
				g2 = sameDir ? g1.clone() : read(gen2Dir, name);
			} catch (IOException e) {
				continue;
			}
			out.add(new Sample(name, g1, g2));
		}
		return out;
	}

	private static List<String> listClassNames(Path dir) throws IOException {
		List<String> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			System.out.println("[warn] corpus dir is not a directory: " + dir);
			return out;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			s.filter(Files::isRegularFile)
				.map(p -> dir.relativize(p).toString().replace('\\', '/'))
				.filter(rel -> rel.endsWith(".class"))
				.filter(rel -> !rel.startsWith("META-INF/"))
				.filter(rel -> !rel.endsWith("module-info.class"))
				.forEach(rel -> out.add(rel.substring(0, rel.length() - ".class".length())));
		}
		return out;
	}

	private static byte[] read(Path dir, String internalName) throws IOException {
		return Files.readAllBytes(dir.resolve(internalName + ".class"));
	}

	private static ClassNode readFull(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		return cn;
	}

	private static boolean isLambdaMethodName(String name) {
		if (name == null || name.startsWith("<") || name.startsWith("access$")) return false;
		return name.contains("lambda$") || name.contains("$lambda") || name.contains("$anonfun$");
	}

	/**
	 * 构造 {@code Raw 方法键 → 生效侧方法键} 的翻译表。
	 *
	 * <p>判据与产品侧一致：覆盖 {@code R_prev} 里所有 lambda 系方法键（{@code translationCovers}
	 * 的准入前提）。映射按"同名同描述符优先，其次同名不同描述符（正是 {@code forceStaticLambdas}
	 * 前置 this 后的形态）"取；都对不上时保留原键（并计数 {@code unmapped}，由调用方读
	 * {@code rawToEffKeys} 是否为空体现）。</p>
	 */
	private static Map<String, String> translationTable(byte[] rawPrev, byte[] eff) {
		Map<String, String> effByKey = new LinkedHashMap<>();
		Map<String, String> effByKeyNoDesc = new LinkedHashMap<>();
		for (MethodNode mn : readFull(eff).methods) {
			effByKey.put(mn.name + ":" + mn.desc, mn.name + ":" + mn.desc);
			effByKeyNoDesc.putIfAbsent(mn.name, mn.name + ":" + mn.desc);
		}
		Map<String, String> out = new LinkedHashMap<>();
		for (MethodNode mn : readFull(rawPrev).methods) {
			if (!isLambdaMethodName(mn.name)) continue;
			String key = mn.name + ":" + mn.desc;
			String hit = effByKey.get(key);
			if (hit == null) hit = effByKeyNoDesc.get(mn.name);
			out.put(key, hit == null ? key : hit);
		}
		return out;
	}

	// ---- 小工具 ----

	private static String bucket(int n) {
		return n <= 0 ? "0" : (n >= 3 ? "3+" : String.valueOf(n));
	}

	private static String dist(Map<String, Integer> hist, int total) {
		if (hist.isEmpty()) return "{}";
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, Integer> en : hist.entrySet()) {
			if (!first) sb.append(", ");
			first = false;
			sb.append(en.getKey()).append('=').append(en.getValue());
			if (total > 0) sb.append(" (").append(pct(en.getValue(), total)).append("%)");
		}
		return sb.append('}').toString();
	}

	private static int count(Map<String, Integer> hist) {
		int n = 0;
		for (int v : hist.values()) n += v;
		return n;
	}

	private static String pct(long n, long total) {
		if (total <= 0) return "n/a";
		return String.format(Locale.ROOT, "%.1f", 100.0 * n / total);
	}

	private static long sum(List<Integer> values) {
		long n = 0;
		for (int v : values) n += v;
		return n;
	}

	private static List<Integer> sorted(List<Integer> values) {
		List<Integer> copy = new ArrayList<>(values);
		copy.sort(Comparator.naturalOrder());
		return copy;
	}

	private static String statLine(List<Integer> sortedValues) {
		if (sortedValues.isEmpty()) return "n/a";
		int min = sortedValues.get(0);
		int median = sortedValues.get(sortedValues.size() / 2);
		int max = sortedValues.get(sortedValues.size() - 1);
		return min + "/" + median + "/" + max;
	}

	private static String ms(List<Long> nanos) {
		if (nanos.isEmpty()) return "n/a";
		long[] all = toArray(nanos);
		return "total=" + fmt(nanosToMs(sumNanos(all)))
			+ " min=" + fmt(nanosToMs(all[0]))
			+ " median=" + fmt(nanosToMs(medianOf(all)))
			+ " p90=" + fmt(nanosToMs(all[Math.min(all.length - 1, (int) Math.round(all.length * 0.9))]))
			+ " max=" + fmt(nanosToMs(all[all.length - 1]));
	}

	private static long[] toArray(List<Long> nanos) {
		long[] all = new long[nanos.size()];
		for (int i = 0; i < all.length; i++) all[i] = nanos.get(i);
		Arrays.sort(all);
		return all;
	}

	private static long sumNanos(long[] sorted) {
		long n = 0;
		for (long v : sorted) n += v;
		return n;
	}

	private static long medianOf(long[] sorted) {
		if (sorted.length == 0) return 0;
		return sorted[sorted.length / 2];
	}

	private static double nanosToMs(long nanos) {
		return nanos / 1_000_000.0;
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.3f", v);
	}

	private static String oneLine(String s) {
		if (s == null) return "<no message>";
		String t = s.replace('\n', ' ').replace('\r', ' ');
		return t.length() > 160 ? t.substring(0, 160) + "..." : t;
	}
}
