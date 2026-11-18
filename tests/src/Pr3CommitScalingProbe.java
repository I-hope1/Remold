import remold.vcs.AlignmentMetaStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.lang.System.out;

/**
 * PR3 受控条目数微基准：单次 {@code AlignmentMetaStore.INSTANCE.commit(...)} 的耗时随"仓内条目数"如何变化，
 * 以及这条增长落在哪个环节上（blob 保留 / 全表遍历 / 分配与 GC）。
 *
 * <p><b>要回答的问题</b>（PR #12 挂账的 {@code O(entries)}）：commit 路径里有几处"遍历全仓"的动作——
 * {@code retainBlob} 每次先算 {@code blobBytes()}（全表求和）、{@code trimGlobalBudget} 每次先算
 * {@code liveHistoryEntries()}（全表计数），预算压力下 {@code dropOldestHistoryBlob} 还要再扫一遍。
 * 条目数涨到千级时这些全表遍历是否已经构成瓶颈？</p>
 *
 * <p><b>控制变量</b>：每档用<b>同一份等长字节</b>当 {@code preAlign/alignOutput/jvmEffective}，唯一的自变量是
 * "仓里已经有多少条 entry"（不同类名、同一 loader 槽）。所以斜率的单位是「每千条 entry 带来的单次 commit 增量」。</p>
 *
 * <p><b>防自证 / 反空转</b>：</p>
 * <ol>
 *   <li>每档每轮断言 {@code headCount()}/{@code historyCount()} 真的长到目标条数（测的不是空循环）；</li>
 *   <li>每档先跑升序（50→2000）再跑降序（2000→50）：真增长对两个方向都成立；纯 JIT/缓存预热只对第一遍成立；</li>
 *   <li><b>O(1) 对照</b>：同一状态下顺带计 {@code head(loader, name)} 的耗时（一次哈希查找、不做全表遍历）。
 *       commit 涨而 head 不涨，才能把增量归给 commit 自己的活；两者一起涨说明是环境/GC 噪声；</li>
 *   <li><b>环节消融</b>（各跑一个子进程，因为预算与 blob 体积都是定值口径）：
 *       {@code --mode=no-blob}（{@code -Dremold.vcs.meta_blob_bytes=0}，整条 blob 保留链不执行）、
 *       {@code --mode=tiny-blob}（{@code -Dpr3.entryBytes=64}，保留 blob 但分配量降到 1/64）、
 *       {@code --mode=low-budget}（{@code =-65536}，预算压力下走"丢 blob"分支）；</li>
 *   <li>每档记 GC 次数/耗时增量，用来判断"增长是不是被 GC 抬起来的"。</li>
 * </ol>
 *
 * <p><b>口径</b>：每档报 min / median / p90 / max（微秒）+ 轮中位数跨度；判定阈值
 * {@link #SLOPE_THRESHOLD_US_PER_1000} 先定后测。耗时读数必须在没有其它 Gradle/探针并发抢占时单跑
 * （AGENTS §9 第 4 条），所以 {@code [env]} 行会打印跑前/跑后的 loadavg。
 * <b>判定的结论是读数，不影响退出码</b>：退出码只反映"这次测量是否成立"（反空转断言、预算断言、子进程成败）。</p>
 */
public final class Pr3CommitScalingProbe {

	/** 受控条目数档（{@code -Dpr3.sizes=...} 可覆盖）。 */
	private static final int[] SIZES = parseSizes(System.getProperty("pr3.sizes", "50,200,1000,2000"));
	/** 每条 entry 的 blob 体积（{@code -Dpr3.entryBytes=...} 可覆盖，用于把"分配/GC"与"全表遍历"分开）。 */
	private static final int ENTRY_BYTES = Integer.getInteger("pr3.entryBytes", 4096);
	/** 每档的丢弃式预热轮数（{@code -Dpr3.commitScaling.warmup}，默认 3；0 = 不预热）。 */
	private static final int WARMUP = Integer.getInteger("pr3.commitScaling.warmup", 3);
	/** 主矩阵每档轮数（≥5）。 */
	private static final int ROUNDS = Integer.getInteger("pr3.rounds", 7);
	/** 消融子进程每档轮数：只用来定位增量落在哪个环节，不进斜率判定。 */
	private static final int ABLATION_ROUNDS = 3;
	/** 每轮计时样本数（在"已经堆到 N 条"之后再追加这么多条，逐条计时）。 */
	private static final int SAMPLES = Integer.getInteger("pr3.samples", 15);
	/** 斜率判定阈值：每 1000 条 entry 的中位数增量低于此值视为可忽略。先定后测。 */
	private static final double SLOPE_THRESHOLD_US_PER_1000 = 5.0;
	/** 低预算档的字节预算。 */
	private static final long LOW_BUDGET_BYTES = 65536L;

	private Pr3CommitScalingProbe() { }

	public static void main(String[] args) throws Exception {
		String mode = argMode(args);
		out.println("[env] mode=" + mode + " jvm=" + System.getProperty("java.vm.name") + " "
			+ System.getProperty("java.version") + " os=" + System.getProperty("os.name")
			+ " cpus=" + Runtime.getRuntime().availableProcessors()
			+ " blobBudget=" + AlignmentMetaStore.BLOB_BUDGET_BYTES + "B perClassCap="
			+ AlignmentMetaStore.PER_CLASS_BLOB_BYTES + "B entryBytes=" + ENTRY_BYTES + "B"
			+ " sizes=" + Arrays.toString(SIZES) + " warmupRounds=" + WARMUP
			+ " loadavg=" + loadAverage());

		if (!"default".equals(mode)) {
			runAblationTier(mode);
			out.println("PR3-COMMIT-SCALING-" + mode.toUpperCase() + " OK loadavg=" + loadAverage());
			return;
		}

		warmUp();
		Map<Integer, Stats> ascending = runPass(true, ROUNDS);
		Map<Integer, Stats> descending = runPass(false, ROUNDS);
		reportPass("asc", ascending);
		reportPass("desc", descending);
		verdict(ascending, descending);

		Map<String, String> ablations = new LinkedHashMap<>();
		ablations.put("no-blob", runChild("no-blob", "0", null));
		ablations.put("tiny-blob", runChild("tiny-blob", null, String.valueOf(64)));
		ablations.put("low-budget", runChild("low-budget", String.valueOf(LOW_BUDGET_BYTES), null));
		out.println("[ablation] committed-path decomposition (same fixture, only the named factor changes):");
		for (Map.Entry<String, String> e : ablations.entrySet()) {
			out.println("[ablation] " + e.getKey() + " => " + e.getValue());
		}
		out.println("[env] loadavgAfter=" + loadAverage());
		out.println("PR3-COMMIT-SCALING OK");
	}

	private static String argMode(String[] args) {
		for (String a : args) {
			if (a.startsWith("--mode=")) return a.substring("--mode=".length());
		}
		return "default";
	}

	// ------------------------------------------------------------------------------------------------
	// 主矩阵：升序 + 降序（每档同时计 commit 与 O(1) 对照 head）
	// ------------------------------------------------------------------------------------------------

	/**
	 * 丢弃式预热：**每一档**先跑 {@link #WARMUP} 轮完整流程（reset → 堆到该档 → 追加样本 → O(1) 对照），
	 * 不入账。条目数越大，commit 路径里的 O(entries) 代码被调用得越多、编译/内联越晚，所以预热必须按档做，
	 * 而不是只在小档上跑几遍（实测：只在小档预热时，2000 档的第一轮会把 C2 编译时间算进去）。
	 *
	 * <p>{@code -Dpr3.commitScaling.warmup=<n>} 控制轮数，默认 3；{@code 0} 表示不预热（只看冷启动形态）。</p>
	 */
	private static void warmUp() {
		if (WARMUP <= 0) {
			out.println("[warmup] skipped (-Dpr3.commitScaling.warmup=" + WARMUP + ")");
			return;
		}
		long t0 = System.nanoTime();
		AlignmentMetaStore store = AlignmentMetaStore.INSTANCE;
		byte[] blob = blob();
		for (int size : SIZES) {
			for (int rep = 0; rep < WARMUP; rep++) {
				ClassLoader loader = newLoader();
				store.reset();
				String tag = "warm-" + size + "-" + rep;
				build(store, loader, blob, tag, 0, size);
				for (int i = 0; i < SAMPLES; i++) {
					store.commit(loader, tag + "-s" + i, blob, blob, blob,
						invented(), ghosts(), mapping(), 1, rawToEff());
					store.head(loader, tag + "/C" + (size / 2));
				}
			}
		}
		out.println("[warmup] " + WARMUP + " discarded rounds per tier over " + Arrays.toString(SIZES)
			+ " done in " + ms(System.nanoTime() - t0) + "ms (-Dpr3.commitScaling.warmup)");
	}

	private static Map<Integer, Stats> runPass(boolean ascending, int rounds) {
		int[] order = SIZES.clone();
		if (!ascending) reverse(order);
		Map<Integer, Stats> bySize = new LinkedHashMap<>();
		for (int size : order) {
			bySize.put(size, runSize(size, "p" + (ascending ? "asc" : "desc") + "-" + size, rounds));
		}
		return bySize;
	}

	/**
	 * 一档：{@code rounds} 轮，每轮 {@code reset()} → 静默堆到 {@code size} 条 → 逐条计时追加 {@link #SAMPLES} 条，
	 * 并顺带计时同样次数 {@code head(...)} 查找。计时样本落在"仓里已有 size..size+SAMPLES-1 条"的窗口内，
	 * 所以读数是<b>该条目数水位下的边际成本</b>，不是"从 0 堆到 N"的平均成本。
	 */
	private static Stats runSize(int size, String tag, int rounds) {
		AlignmentMetaStore store = AlignmentMetaStore.INSTANCE;
		byte[] blob = blob();
		Stats stats = new Stats(size);
		long gcCountBefore = gcCount();
		long gcMillisBefore = gcMillis();
		for (int round = 0; round < rounds; round++) {
			ClassLoader loader = newLoader();
			store.reset();
			long t0 = System.nanoTime();
			build(store, loader, blob, tag + "-r" + round, 0, size);
			stats.buildNanos += System.nanoTime() - t0;
			requireHead(store, size, tag, round);
			String probeName = tag + "-r" + round + "/C" + (size / 2);

			long[] samples = new long[SAMPLES];
			long[] controls = new long[SAMPLES];
			for (int i = 0; i < SAMPLES; i++) {
				String name = tag + "-r" + round + "-s" + i;
				long s0 = System.nanoTime();
				AlignmentMetaStore.Entry e = store.commit(loader, name, blob, blob, blob,
					invented(), ghosts(), mapping(), 1, rawToEff());
				samples[i] = System.nanoTime() - s0;
				if (e == null) throw new IllegalStateException(tag + ": commit returned null at sample " + i);
				if (e.generation != 1) throw new IllegalStateException(tag + ": first commit for " + name
					+ " must be generation 1, got " + e.generation);

				long c0 = System.nanoTime();
				AlignmentMetaStore.Entry hit = store.head(loader, probeName);
				controls[i] = System.nanoTime() - c0;
				if (hit == null) throw new IllegalStateException(tag + ": the O(1) control lookup must hit an"
					+ " existing entry, got null for " + probeName);
			}
			// 反空转：追加的样本必须真的进了仓，且没有被全局历史预算淘汰。
			requireHead(store, size + SAMPLES, tag, round);
			requireHistory(store, size + SAMPLES, tag, round);
			stats.addRound(samples, controls, store.blobBytes(), store.droppedBlobCount());
		}
		stats.gcCount = gcCount() - gcCountBefore;
		stats.gcMillis = gcMillis() - gcMillisBefore;
		return stats.summarize();
	}

	private static void requireHead(AlignmentMetaStore store, int expected, String tag, int round) {
		int head = store.headCount();
		if (head != expected) {
			throw new IllegalStateException(tag + " round " + round + ": headCount must be " + expected
				+ " (the store really holds the entries, not an empty loop), got " + head);
		}
	}

	private static void requireHistory(AlignmentMetaStore store, int expected, String tag, int round) {
		int history = store.historyCount();
		if (history != expected) {
			throw new IllegalStateException(tag + " round " + round + ": historyCount must be " + expected
				+ " (no global history trimming may interfere with this tier), got " + history);
		}
	}

	private static void build(AlignmentMetaStore store, ClassLoader loader, byte[] blob,
	                          String tag, int from, int to) {
		for (int i = from; i < to; i++) {
			store.commit(loader, tag + "/C" + i, blob, blob, blob,
				invented(), ghosts(), mapping(), 1, rawToEff());
		}
	}

	// ------------------------------------------------------------------------------------------------
	// 读数与判定
	// ------------------------------------------------------------------------------------------------

	private static void reportPass(String pass, Map<Integer, Stats> bySize) {
		for (Stats s : bySize.values()) {
			out.println("[size=" + s.size + "] pass=" + pass + " rounds=" + s.rounds + " samples=" + s.count()
				+ " blobBytes=" + s.blobBytes + " dropped=" + s.dropped
				+ " commit: min=" + us(s.min) + "us median=" + us(s.median) + "us p90=" + us(s.p90)
				+ "us max=" + us(s.max) + "us medianNs=" + s.median
				+ " roundMedianSpread=" + us(s.roundMedianMin) + ".." + us(s.roundMedianMax) + "us"
				+ " | head(O(1) control): median=" + us(s.controlMedian) + "us max=" + us(s.controlMax) + "us"
				+ " | gc=" + s.gcCount + "/" + s.gcMillis + "ms buildTotal=" + ms(s.buildNanos) + "ms");
		}
	}

	private static void verdict(Map<Integer, Stats> ascending, Map<Integer, Stats> descending) {
		double slopeAsc = slope(ascending);
		double slopeDesc = slope(descending);
		double pairAsc = pairwiseSlope(ascending);
		double pairDesc = pairwiseSlope(descending);
		double minAsc = slopeOf(ascending, true);
		double minDesc = slopeOf(descending, true);
		double controlAsc = slopeOfControls(ascending);
		double controlDesc = slopeOfControls(descending);
		String pairLabel = "pairwise(" + lowTier() + "->" + SIZES[SIZES.length - 1] + ")";
		out.println("[slope] pass=asc leastSquares=" + fmt(slopeAsc) + "us/1000entries " + pairLabel + "="
			+ fmt(pairAsc) + "us/1000entries");
		out.println("[slope] pass=desc leastSquares=" + fmt(slopeDesc) + "us/1000entries " + pairLabel + "="
			+ fmt(pairDesc) + "us/1000entries");
		out.println("[slope] min-statistic asc=" + fmt(minAsc) + "us/1000entries desc=" + fmt(minDesc)
			+ "us/1000entries (min = least-interference sample of each tier)");
		out.println("[slope] O(1)-control asc=" + fmt(controlAsc) + "us/1000entries desc="
			+ fmt(controlDesc) + "us/1000entries (same store state, single hash lookup)");
		out.println("[slope] threshold=" + fmt(SLOPE_THRESHOLD_US_PER_1000)
			+ "us/1000entries (defined before measuring)");

		boolean rising = slopeAsc > 0 && slopeDesc > 0;
		// 判据对 min 与 median 同时成立才算"可忽略"（AGENTS §6：不得只报有利的那个统计量）。
		boolean negligible = Math.abs(slopeAsc) < SLOPE_THRESHOLD_US_PER_1000
			&& Math.abs(slopeDesc) < SLOPE_THRESHOLD_US_PER_1000
			&& Math.abs(minAsc) < SLOPE_THRESHOLD_US_PER_1000
			&& Math.abs(minDesc) < SLOPE_THRESHOLD_US_PER_1000;
		boolean controlFlat = Math.abs(controlAsc) < SLOPE_THRESHOLD_US_PER_1000
			&& Math.abs(controlDesc) < SLOPE_THRESHOLD_US_PER_1000;
		out.println("[verdict] rising-with-entries=" + rising
			+ " O(1)-control-flat=" + controlFlat
			+ " => " + (negligible ? "NEGLIGIBLE" : "MEASURABLE"));
		if (rising && !controlFlat) {
			out.println("[verdict] WARNING: the O(1) control grows too; part of the slope is environment/GC noise");
		}
		if (!negligible) {
			out.println("[verdict] the commit path is NOT O(1) in the number of stored entries;"
				+ " see the ablation tiers below for which part carries the growth");
		}
	}

	/** 最小二乘斜率（以 1000 条为步长，median 口径）。 */
	private static double slope(Map<Integer, Stats> bySize) {
		return leastSquares(bySize, StatKind.MEDIAN);
	}

	private static double slopeOf(Map<Integer, Stats> bySize, boolean useMin) {
		return leastSquares(bySize, useMin ? StatKind.MIN : StatKind.MEDIAN);
	}

	private static double slopeOfControls(Map<Integer, Stats> bySize) {
		return leastSquares(bySize, StatKind.CONTROL);
	}

	private enum StatKind { MEDIAN, MIN, CONTROL }

	private static double leastSquares(Map<Integer, Stats> bySize, StatKind kind) {
		double n = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
		for (Stats s : bySize.values()) {
			double x = s.size / 1000.0;
			double raw = kind == StatKind.MIN ? s.min : kind == StatKind.CONTROL ? s.controlMedian : s.median;
			double y = raw / 1000.0;
			n++;
			sx += x;
			sy += y;
			sxx += x * x;
			sxy += x * y;
		}
		double denom = n * sxx - sx * sx;
		return denom == 0 ? 0 : (n * sxy - sx * sy) / denom;
	}

	/** 两端点斜率（第二档 → 最后一档），与最小二乘互为口径校验。 */
	private static double pairwiseSlope(Map<Integer, Stats> bySize) {
		Stats lo = bySize.get(lowTier());
		Stats hi = bySize.get(SIZES[SIZES.length - 1]);
		if (lo == null || hi == null) return 0;
		return (hi.median - lo.median) / 1000.0 / ((hi.size - lo.size) / 1000.0);
	}

	// ------------------------------------------------------------------------------------------------
	// 消融档（各一个子进程：预算与 blob 体积都是定值口径）
	// ------------------------------------------------------------------------------------------------

	private static String runChild(String mode, String blobBudget, String entryBytes) throws Exception {
		List<String> cmd = new ArrayList<>();
		cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
		cmd.add("-cp");
		cmd.add(System.getProperty("java.class.path"));
		cmd.add("-Dfile.encoding=UTF-8");
		cmd.add("-Dstdout.encoding=UTF-8");
		cmd.add("-Dstderr.encoding=UTF-8");
		cmd.add("-Dpr3.rounds=" + ABLATION_ROUNDS);
		// 消融档只用 1 轮预热：它们定位"增量落在哪个环节"，不进斜率判定，省掉重复的冷启动成本。
		cmd.add("-Dpr3.commitScaling.warmup=1");
		if (blobBudget != null) cmd.add("-Dremold.vcs.meta_blob_bytes=" + blobBudget);
		if (entryBytes != null) cmd.add("-Dpr3.entryBytes=" + entryBytes);
		cmd.add("Pr3CommitScalingProbe");
		cmd.add("--mode=" + mode);

		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		StringBuilder kept = new StringBuilder();
		int warnLines = 0;
		String firstWarn = null;
		try (BufferedReader r = new BufferedReader(
			new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				// 消融档会按类名逐条打 "Raw_prev blob not recorded" 的 warn（log-once 按类名去重，
				// 仓里全是新类名，于是每次都打）。这里只保留条数与首条样本，避免日志被淹；
				// 条数本身就是"该消融档真的生效了"的证据。
				if (line.contains("[Remold] [WARN]")) {
					warnLines++;
					if (firstWarn == null) firstWarn = line;
					continue;
				}
				out.println("[child:" + mode + "] " + line);
				if (line.startsWith("[size=") && line.contains("pass=asc")) kept.append(compact(line)).append(' ');
				if (line.startsWith("[tier]")) kept.append(compact(line)).append(' ');
			}
		}
		if (warnLines > 0) {
			out.println("[child:" + mode + "] suppressedWarnLines=" + warnLines + " first="
				+ (firstWarn == null ? "" : firstWarn.replace("[Remold] [WARN] ", "")));
		}
		boolean done = p.waitFor(900, TimeUnit.SECONDS);
		if (!done) {
			p.destroyForcibly();
			throw new IllegalStateException(mode + " child timed out");
		}
		if (p.exitValue() != 0) {
			throw new IllegalStateException(mode + " child exited " + p.exitValue()
				+ " (its own fail-closed assertions are in the log above)");
		}
		return kept.toString().trim();
	}

	private static String compact(String line) {
		int blob = line.indexOf(" blobBytes=");
		int commit = line.indexOf(" commit:");
		int gc = line.indexOf(" | gc=");
		if (line.startsWith("[size=")) {
			return line.substring(0, line.indexOf(']') + 1) + (blob > 0 ? line.substring(blob, commit) : "")
				+ " " + (commit > 0 ? line.substring(commit + 1, gc > 0 ? gc : line.length()) : "");
		}
		return line;
	}

	private static void runAblationTier(String mode) {
		if ("no-blob".equals(mode)) {
			if (AlignmentMetaStore.BLOB_BUDGET_BYTES > 0) {
				throw new IllegalStateException("the no-blob tier must start with"
					+ " -Dremold.vcs.meta_blob_bytes=0, got " + AlignmentMetaStore.BLOB_BUDGET_BYTES);
			}
		} else if ("low-budget".equals(mode)) {
			if (AlignmentMetaStore.BLOB_BUDGET_BYTES != LOW_BUDGET_BYTES) {
				throw new IllegalStateException("the low-budget tier must start with"
					+ " -Dremold.vcs.meta_blob_bytes=" + LOW_BUDGET_BYTES + ", got "
					+ AlignmentMetaStore.BLOB_BUDGET_BYTES);
			}
		} else if ("tiny-blob".equals(mode)) {
			if (ENTRY_BYTES >= 4096) {
				throw new IllegalStateException("the tiny-blob tier must run with a reduced -Dpr3.entryBytes,"
					+ " got " + ENTRY_BYTES);
			}
		} else {
			throw new IllegalStateException("unknown mode: " + mode);
		}

		List<Stats> all = new ArrayList<>();
		for (int size : SIZES) {
			Stats stats = runSize(size, "ab-" + mode + "-" + size, ABLATION_ROUNDS);
			all.add(stats);
			out.println("[size=" + size + "] pass=asc mode=" + mode + " rounds=" + ABLATION_ROUNDS
				+ " blobBytes=" + stats.blobBytes + " dropped=" + stats.dropped
				+ " commit: min=" + us(stats.min) + "us median=" + us(stats.median) + "us p90="
				+ us(stats.p90) + "us max=" + us(stats.max) + "us | head(O(1) control): median="
				+ us(stats.controlMedian) + "us | gc=" + stats.gcCount + "/" + stats.gcMillis + "ms");
		}
		Stats lo = all.get(lowTierIndex());
		Stats hi = all.get(all.size() - 1);
		double pair = (hi.median - lo.median) / 1000.0 / ((hi.size - lo.size) / 1000.0);
		out.println("[tier] mode=" + mode + " entryBytes=" + ENTRY_BYTES + " blobBudget="
			+ AlignmentMetaStore.BLOB_BUDGET_BYTES + "B slope(lowest->highest tier)=" + fmt(pair)
			+ "us/1000entries median@" + lo.size + "=" + us(lo.median) + "us median@" + hi.size + "="
			+ us(hi.median) + "us headControlSlope=" + fmt(pairOfControls(all)) + "us/1000entries");

		if ("no-blob".equals(mode) && AlignmentMetaStore.INSTANCE.blobBytes() != 0) {
			throw new IllegalStateException("the no-blob tier must hold no blob bytes, got "
				+ AlignmentMetaStore.INSTANCE.blobBytes());
		}
		if ("low-budget".equals(mode)) {
			// 降级的可观测读数不是 droppedBlobCount：预算不足时 retainBlob 的回收循环每次都能腾出
			// 一份最旧 blob 再装新的，所以"这一代没拿到 blob"从不发生（droppedBlobCount 只在单类上限、
			// 预算被关掉、或没有可回收对象时才涨）。预算压力的口径是"持有的字节被顶在 cap 附近"。
			long held = AlignmentMetaStore.INSTANCE.blobBytes();
			int dropped = AlignmentMetaStore.INSTANCE.droppedBlobCount();
			long capSlots = LOW_BUDGET_BYTES / ENTRY_BYTES;
			out.println("[tier] low-budget held=" + held + "B (cap " + LOW_BUDGET_BYTES + "B => about "
				+ capSlots + " blob slots of " + ENTRY_BYTES + "B) droppedBlobCount=" + dropped
				+ " (the reclaim loop frees the oldest blob instead of refusing the new one)");
			if (held > LOW_BUDGET_BYTES) {
				throw new IllegalStateException("blob budget exceeded: held " + held + "B > "
					+ LOW_BUDGET_BYTES + "B");
			}
			if (held < LOW_BUDGET_BYTES - ENTRY_BYTES) {
				throw new IllegalStateException("the 64KiB budget must stay saturated while entries keep"
					+ " arriving, got held=" + held + "B");
			}
		}
	}

	private static double pairOfControls(List<Stats> all) {
		Stats lo = all.get(lowTierIndex());
		Stats hi = all.get(all.size() - 1);
		return (hi.controlMedian - lo.controlMedian) / 1000.0 / ((hi.size - lo.size) / 1000.0);
	}

	// ------------------------------------------------------------------------------------------------
	// 工具
	// ------------------------------------------------------------------------------------------------

	/** 两端点斜率的低端：默认取第二档（避开最小档的固定开销），档数不足时退回第一档。 */
	private static int lowTier() {
		return SIZES[lowTierIndex()];
	}

	private static int lowTierIndex() {
		return SIZES.length < 3 ? 0 : 1;
	}

	private static int[] parseSizes(String spec) {
		String[] parts = spec.split(",");
		int[] outSizes = new int[parts.length];
		for (int i = 0; i < parts.length; i++) outSizes[i] = Integer.parseInt(parts[i].trim());
		Arrays.sort(outSizes);
		return outSizes;
	}

	private static void reverse(int[] a) {
		for (int i = 0; i < a.length / 2; i++) {
			int t = a[i];
			a[i] = a[a.length - 1 - i];
			a[a.length - 1 - i] = t;
		}
	}

	private static Set<String> invented() {
		return Set.of("lambda$run$0", "lambda$run$3");
	}

	private static Map<String, Long> ghosts() {
		return Map.of("lambda$run$5", 0x5151_5151L);
	}

	private static Map<String, String> mapping() {
		return Map.of("a()V", "b()V", "b()V", "a()V");
	}

	private static Map<String, String> rawToEff() {
		return Map.of("lambda$run$0:()V", "lambda$run$0:()V",
			"lambda$run$3:()V", "lambda$run$3:()V");
	}

	private static ClassLoader newLoader() {
		return new ClassLoader(Pr3CommitScalingProbe.class.getClassLoader()) { };
	}

	/** 固定体积、内容确定的 blob（每档同一份，体积由 {@code -Dpr3.entryBytes} 控制）。 */
	private static byte[] blob() {
		byte[] b = new byte[ENTRY_BYTES];
		for (int i = 0; i < b.length; i++) b[i] = (byte) ((i * 31 + 7) & 0xFF);
		return b;
	}

	private static long gcCount() {
		long n = 0;
		for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
			n += Math.max(0, bean.getCollectionCount());
		}
		return n;
	}

	private static long gcMillis() {
		long n = 0;
		for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
			n += Math.max(0, bean.getCollectionTime());
		}
		return n;
	}

	/** 机器负载读数：耗时类探针必须能交代"跑的时候有没有别的活在抢 CPU"。 */
	private static String loadAverage() {
		try {
			return Files.readString(Paths.get("/proc/loadavg"), StandardCharsets.UTF_8).trim();
		} catch (Exception e) {
			return "n/a";
		}
	}

	private static String us(long nanos) {
		return fmt(nanos / 1000.0);
	}

	private static String ms(long nanos) {
		return fmt(nanos / 1_000_000.0);
	}

	private static String fmt(double v) {
		return String.format("%.2f", v);
	}

	/** 一档的读数聚合：跨轮样本汇总 + 轮中位数跨度（稳定性用）+ O(1) 对照 + GC 增量。 */
	private static final class Stats {
		final int size;
		int rounds;
		private final List<Long> samples = new ArrayList<>();
		private final List<Long> controls = new ArrayList<>();
		private final List<Long> roundMedians = new ArrayList<>();
		long blobBytes;
		int dropped;
		long buildNanos;
		long gcCount;
		long gcMillis;
		long min;
		long median;
		long p90;
		long max;
		long roundMedianMin;
		long roundMedianMax;
		long controlMedian;
		long controlMax;

		Stats(int size) {
			this.size = size;
		}

		void addRound(long[] round, long[] control, long blobBytesHeld, int droppedCount) {
			rounds++;
			blobBytes = blobBytesHeld;
			dropped = droppedCount;
			roundMedians.add(median(round.clone()));
			for (long v : round) samples.add(v);
			for (long v : control) controls.add(v);
		}

		int count() {
			return samples.size();
		}

		/** 汇总（在全部轮次跑完后调用一次，口径固定：跨轮全样本）。 */
		Stats summarize() {
			long[] all = sorted(samples);
			min = all[0];
			median = all[all.length / 2];
			p90 = all[(int) Math.min(all.length - 1L, Math.ceil(0.9 * all.length) - 1)];
			max = all[all.length - 1];
			long[] ctrl = sorted(controls);
			controlMedian = ctrl[ctrl.length / 2];
			controlMax = ctrl[ctrl.length - 1];
			long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
			for (long v : roundMedians) {
				lo = Math.min(lo, v);
				hi = Math.max(hi, v);
			}
			roundMedianMin = lo;
			roundMedianMax = hi;
			return this;
		}

		private static long[] sorted(List<Long> values) {
			long[] all = new long[values.size()];
			for (int i = 0; i < all.length; i++) all[i] = values.get(i);
			Arrays.sort(all);
			return all;
		}

		private static long median(long[] sorted) {
			Arrays.sort(sorted);
			return sorted[sorted.length / 2];
		}
	}
}
