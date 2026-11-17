import remold.ClassDiffUtil;
import remold.RemoldAgent;
import remold.ref.InitFix;
import remold.ref.StageBInstanceCache;
import remold.util.LibTool;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次级单次全堆遍历探针（PR2 slice 3 · Batch D）：{@code docs/initfix/05-jvmti-heap.md} §「批次实例收集」。
 *
 * <p>四段，每段都带自检（假设不成立即非 0 退出）：</p>
 * <ol>
 *   <li><b>[1] 真实活实例的终局断言</b>：宿主 + 子类实例都在堆里，一批两个类，验证
 *       {@code @RemoldReinit(SELF)} 只写宿主实例、{@code (INCLUDE_SUBCLASSES)} 覆盖子类实例、
 *       新增字段覆盖全部实例——断言落在<b>字段值</b>（业务可观测的最后一环），不是调用成功。</li>
 *   <li><b>[2] K=1/16/64 的排他读数</b>：开/关批收集逐轮交替（同轮次、同口径），
 *       {@code collect}（实例枚举）与 {@code full}（整个补丁阶段）各报 min / median / max。</li>
 *   <li><b>[3] 全堆遍历次数 K→1</b>：用 native 侧真实计数器取证，批收集必须把 K 次压成 1 次；
 *       同时给出"关批收集"的 K 次对照。</li>
 *   <li><b>[4] D3 存在性探测</b>：{@code hasLiveInstancesBatch} 对 K 个零实例类只扫 1 次堆
 *       （逐类探测是 K 次），且结果与逐类探测逐项相等。</li>
 * </ol>
 *
 * <p><b>读数口径（AGENTS.md §3）</b>：所有耗时都来自本进程、同一轮次、逐轮交替的实测；
 * 结论必须同时对 min 与 median 成立，只对一个成立时探针自己报「口径敏感」。</p>
 */
public final class InitFixBatchScanProbe {

	private static final List<String> infoLog = Collections.synchronizedList(new ArrayList<>());
	private static int failures;
	private static int instanceCount = 8;
	private static int rounds = 21;

	public static void main(String[] args) throws Exception {
		RemoldAgent.logger = new RemoldAgent.Logger() {
			public void log(String msg) { }
			public void info(String msg) { infoLog.add(msg); }
			public void warn(String msg) { }
			public void error(String msg) { }
			public void error(String msg, Throwable t) { }
		};
		RemoldAgent.initConfig();
		RemoldAgent.INIT_FIX = true;

		System.out.println("=== InitFix batch heap scan probe (PR2 slice 3 · Batch D) ===");
		System.out.printf("   java.version=%s  libtool=%s%n", System.getProperty("java.version"),
		                  System.getProperty("remold.path.libtool", "<java.library.path>"));
		check("native LibTool must be loadable", LibTool.initialized() || LibTool.tryInit());
		check("native library must expose the batch heap-scan entry points (build core/cpp/tool.cpp"
			+ " with a C++ compiler or pass -Dremold.path.libtool)", LibTool.batchScanIsNative());
		check("heap-walk counter must be available", LibTool.heapWalkCount() >= 0);

		minRepro();
		measureLiveInstanceScope();
		measureBatchCost(1);
		measureBatchCost(4);
		measureBatchCost(8);
		measureBatchCost(16);
		measureBatchCost(64);
		measureReportEquivalence();
		measureLivenessProbe();
		measureLargeHeapControl();

		System.out.println("\n=== INITFIX BATCH SCAN PROBE: "
			+ (failures == 0 ? "self-checks passed" : failures + " CHECK(S) FAILED") + " ===");
		if (failures != 0) System.exit(1);
	}

	// ------------------------------------------------------------------ [6] report equivalence

	/**
	 * 开关等价（PatchReport 口径）：同一形态的两批夹具，一批开批收集、一批关批收集，
	 * 断言 {@code PatchReport}（instance/static 字段的 status / reason / warnings、patchGenerated、
	 * 重放字段）与补丁写入结果**逐项相同**。
	 *
	 * <p>这条是批次收集"可以随时退场"的依据：批收集只是把 K 次枚举并成 1 次，不得改变任何可观测行为。</p>
	 */
	private static void measureReportEquivalence() throws Exception {
		System.out.println("\n--- [6] report equivalence: batch collection on vs off ---");
		// 两组夹具的类名前缀不同（避免同名类互相污染），比较前把前缀归一掉。
		ScopeRound onRound  = runScopeRound("Probe.EqOn", true);
		ScopeRound offRound = runScopeRound("Probe.EqOff", false);
		String on  = onRound.signature().replace("EqOn", "EQ");
		String off = offRound.signature().replace("EqOff", "EQ");
		System.out.printf("   heap walks: on=%d off=%d%n", onRound.walks(), offRound.walks());
		System.out.println("   on  = " + on);
		System.out.println("   off = " + off);
		check("[6] batch collection on/off must produce identical reports and patch results", on.equals(off));
		// 反空转：关掉开关必须真的把批遍历关掉（否则"开/关等价"会因为两边都在批而白过）。
		check("[6] switching batch collection off must really fall back to one walk per class ("
		      + (onRound.targets()) + " targets)", offRound.walks() == onRound.targets());
		check("[6] the batch round must collapse those walks into one", onRound.walks() == 1);
		check("[6] the round must actually patch something (otherwise the comparison is vacuous)",
		      on.contains("instance={added=") && on.contains("added=7"));
	}

	/** 一轮的结果：可逐字比较的报告签名 + 本轮真实堆遍历次数 + 目标类数。 */
	private record ScopeRound(String signature, long walks, int targets) { }

	/** 跑一轮"8 类批次"的补丁，返回报告签名与遍历次数。 */
	private static ScopeRound runScopeRound(String prefix, boolean batchOn) throws Exception {
		Map<String, String> sources1 = new LinkedHashMap<>();
		Map<String, String> sources2 = new LinkedHashMap<>();
		String hostName = prefix + "Host";
		sources1.put(hostName, scopeSource(hostName, false));
		sources2.put(hostName, scopeSource(hostName, true));
		List<String> otherNames = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			String name = prefix + "Other" + i;
			otherNames.add(name);
			sources1.put(name, classSource(name, false));
			sources2.put(name, classSource(name, true));
		}
		Map<String, byte[]> v1 = compile(sources1);
		Map<String, byte[]> v2 = compile(sources2);
		ByteLoader loader = new ByteLoader(v2);
		Class<?> host = Class.forName(hostName, true, loader);
		List<Class<?>> others = new ArrayList<>();
		for (String name : otherNames) others.add(Class.forName(name, true, loader));

		List<Object> hostInstances = new ArrayList<>();
		Constructor<?> ctor = host.getDeclaredConstructor();
		ctor.setAccessible(true);
		for (int i = 0; i < 3; i++) hostInstances.add(ctor.newInstance());
		List<Object> otherInstances = new ArrayList<>();
		List<Field> otherAdded = new ArrayList<>();
		for (Class<?> other : others) {
			Constructor<?> c = other.getDeclaredConstructor(int.class);
			c.setAccessible(true);
			Field f = other.getDeclaredField("added");
			f.setAccessible(true);
			otherAdded.add(f);
			for (int i = 0; i < 3; i++) otherInstances.add(c.newInstance(0));
		}
		Field added = host.getDeclaredField("added");
		added.setAccessible(true);
		for (Object o : hostInstances) added.setInt(o, 0);
		for (int i = 0; i < otherInstances.size(); i++) otherAdded.get(i / 3).setInt(otherInstances.get(i), 0);

		List<Class<?>> targets = new ArrayList<>();
		targets.add(host);
		targets.addAll(others);

		boolean previous = InitFix.batchHeapScanEnabled();
		InitFix.setBatchHeapScanEnabled(batchOn);
		long walksBefore = LibTool.heapWalkCount();
		try {
			transform(host, v1.get(hostName), v2.get(hostName));
			for (Class<?> other : others) {
				transform(other, v1.get(other.getName()), v2.get(other.getName()));
			}
			// 两种模式都调用同样的 API，由产品侧的开关决定走不走批路径——
			// 测试自己 if 掉调用的话，开关写坏也测不出来。
			InitFix.beginBatchInstanceCollection(targets);
			for (Class<?> c : targets) InitFix.afterRedefine(c);
			InitFix.endBatchInstanceCollection();
		} finally {
			InitFix.setBatchHeapScanEnabled(previous);
		}

		StringBuilder sb = new StringBuilder();
		appendReport(sb, host);
		for (Class<?> other : others) appendReport(sb, other);
		sb.append(" added=").append(readInt(added, hostInstances.get(0)));
		sb.append(" others=");
		List<Integer> otherValues = new ArrayList<>();
		for (int i = 0; i < otherInstances.size(); i++) {
			otherValues.add(otherAdded.get(i / 3).getInt(otherInstances.get(i)));
		}
		Collections.sort(otherValues);
		sb.append(otherValues);
		return new ScopeRound(sb.toString(), LibTool.heapWalkCount() - walksBefore, targets.size());
	}

	private static void appendReport(StringBuilder sb, Class<?> host) {
		InitFix.PatchReport report = InitFix.getLastReport(host);
		sb.append(host.getSimpleName()).append('{');
		if (report == null) {
			sb.append("no-report}");
			return;
		}
		sb.append("gen=").append(report.patchGenerated());
		sb.append(" instance=").append(describeDecisions(report.instanceFields()));
		sb.append(" static=").append(describeDecisions(report.staticFields()));
		sb.append(" replay=").append(report.replayFields());
		sb.append(" applyReplay=").append(report.applyReplayFields());
		sb.append('}');
	}

	private static String describeDecisions(Map<String, InitFix.FieldDecision> decisions) {
		TreeMap<String, String> sorted = new TreeMap<>();
		decisions.forEach((k, v) -> sorted.put(k, v.status() + "/" + v.reason() + "/" + v.warnings()));
		return sorted.toString();
	}

	/** 带 @RemoldReinit 的最小宿主：SELF 一条 + 新增字段一条，足够产生非空报告。 */
	private static String scopeSource(String name, boolean v2) {
		String pkg = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "";
		String simple = name.substring(name.lastIndexOf('.') + 1);
		StringBuilder sb = new StringBuilder();
		if (!pkg.isEmpty()) sb.append("package ").append(pkg).append(";\n");
		sb.append("import remold.annotation.RemoldReinit;\n");
		sb.append("public class ").append(simple).append(" {\n");
		sb.append("  @RemoldReinit(scope = RemoldReinit.Scope.SELF) public int self;\n");
		if (v2) sb.append("  public int added = 7;\n");
		sb.append("  public ").append(simple).append("() { }\n");
		sb.append("}\n");
		return sb.toString();
	}

	// ------------------------------------------------------------------ [5] large-heap control

	/**
	 * 大堆对照（主指挥官追加要求）：堆里先塞大量**无关对象**，再放少量目标类实例。
	 *
	 * <p>为什么必须单量这一段：批收集的三条性能假设都只在"堆里无关对象很多"时才被检验——</p>
	 * <ol>
	 *   <li>{@code IterateThroughHeap(JVMTI_HEAP_FILTER_CLASS_UNTAGGED)} 必须让无关对象<b>不进回调</b>，
	 *       否则一次遍历就要为几十万个无关对象付回调成本；</li>
	 *   <li>{@code GetObjectsWithTags} 的成本随 {@code tag 数}增长（历史实测：4M 实例 16 桶 ≈3.3 s），
	 *       所以"活跃组多"时要看它是否劣化到不可接受；</li>
	 *   <li>逐类路径是 K 次遍历，与无关对象数无关；批收集若也无关，两者都随堆大小走，比例才稳定。</li>
	 * </ol>
	 *
	 * <p>本段刻意<b>不做</b>性能断言（大堆读数受 GC 与机器噪声影响），只做两件事：结果等价断言
	 * + 把读数打出来；是否要把活跃组上限从 64 收到 16，以这里的读数为准。</p>
	 */
	private static void measureLargeHeapControl() throws Exception {
		System.out.println("\n--- [5] large-heap control: many unrelated objects + few target instances ---");
		List<Object> ballast = new ArrayList<>();
		int unrelated = 200_000;
		for (int i = 0; i < unrelated; i++) {
			// 三类最常见的无关对象形态：String / byte[] / 小 Map（与真实堆的成分接近）
			ballast.add(i % 3 == 0 ? ("unrelated-" + i + "-" + (i * 31))
			           : i % 3 == 1 ? new byte[24]
			           : Map.of("k" + i, i));
		}
		KEEP_ALIVE.add(ballast);
		System.out.printf("   ballast: %d unrelated objects retained%n", unrelated);

		for (int k : new int[]{16, 64}) {
			List<String> names = new ArrayList<>();
			Map<String, String> sources = new LinkedHashMap<>();
			for (int i = 0; i < k; i++) {
				String name = "Probe.Heap" + k + "_" + i;
				names.add(name);
				sources.put(name, classSource(name, true));
			}
			Map<String, byte[]> bytes = compile(sources);
			Class<?>[] hosts = hostsOf(names, bytes);
			// 每个目标类放少量实例（真实热更里"绝大多数类零实例、少数类有几百实例"的混居形态）
			int perClass = 20;
			for (Class<?> host : hosts) {
				Constructor<?> ctor = host.getDeclaredConstructor(int.class);
				ctor.setAccessible(true);
				for (int i = 0; i < perClass; i++) KEEP_ALIVE.add(ctor.newInstance(i));
			}

			long[] walksBefore = {LibTool.heapWalkCount()};
			long t0 = System.nanoTime();
			Object[][] batch = LibTool.getInstancesBatch(hosts);
			long batchNanos = System.nanoTime() - t0;
			long batchWalks = LibTool.heapWalkCount() - walksBefore[0];

			long[] perClassNanosArr = new long[hosts.length];
			long perClassNanos = 0;
			Object[][] single = new Object[hosts.length][];
			for (int i = 0; i < hosts.length; i++) {
				long s0 = System.nanoTime();
				single[i] = LibTool.getInstances(hosts[i]);
				perClassNanosArr[i] = System.nanoTime() - s0;
				perClassNanos += perClassNanosArr[i];
			}

			boolean equal = true;
			int totalBatch = 0;
			int totalSingle = 0;
			for (int i = 0; i < hosts.length; i++) {
				totalBatch += batch[i] == null ? 0 : batch[i].length;
				totalSingle += single[i] == null ? 0 : single[i].length;
				if (!sameInstances(batch[i], single[i])) equal = false;
			}
			System.out.printf("   K=%-3d instances/class=%d: batch=%.2fms walks=%d | per-class=%.2fms walks=%d"
			                  + " | hits=%d/%d equal=%s%n",
			                  k, perClass, batchNanos / 1e6, batchWalks, perClassNanos / 1e6, hosts.length,
			                  totalBatch, totalSingle, equal);
			if (!equal) {
				for (int i = 0; i < hosts.length; i++) {
					int wrong = 0;
					if (batch[i] != null) {
						for (Object o : batch[i]) if (o.getClass() != hosts[i]) wrong++;
					}
					int dup = batch[i] == null ? 0 : batch[i].length;
					if (wrong > 0 || dup != (single[i] == null ? 0 : single[i].length)) {
						List<String> wrongNames = new ArrayList<>();
						if (batch[i] != null) {
							for (Object o : batch[i]) {
								if (o.getClass() != hosts[i]) {
									wrongNames.add(o.getClass().getName() + "@"
									          + Integer.toHexString(System.identityHashCode(o.getClass().getClassLoader())));
								}
							}
						}
						System.out.printf("      [DIAG] target#%d %s: batch=%d (wrongClass=%d) perClass=%d wrong=%s%n",
						                  i, hosts[i].getSimpleName(), dup, wrong,
						                  single[i] == null ? 0 : single[i].length, wrongNames);
					}
				}
			}
			check("K=" + k + " with a " + unrelated + "-object heap: batch and per-class results must be identical",
			      equal);
			check("K=" + k + " with a " + unrelated + "-object heap: batch must collapse walks to ceil(K/"
			      + LibTool.BATCH_TARGET_LIMIT + ")", batchWalks == expectedBatchWalks(k));
			check("K=" + k + " with a " + unrelated + "-object heap: every instance must be collected",
			      totalBatch == totalSingle && totalBatch >= k * perClass);
		}
		ballast.clear();
		KEEP_ALIVE.remove(ballast);
	}

	/** 批收集的期望全堆遍历次数：每块（≤ BATCH_TARGET_LIMIT 个 target）一次。 */
	private static long expectedBatchWalks(int k) {
		return Math.max(1, (k + LibTool.BATCH_TARGET_LIMIT - 1) / LibTool.BATCH_TARGET_LIMIT);
	}

	/** 两个实例数组是否含同一批对象（顺序不敏感）。 */
	private static boolean sameInstances(Object[] a, Object[] b) {
		if (a == null || b == null) return (a == null ? 0 : a.length) == (b == null ? 0 : b.length);
		if (a.length != b.length) return false;
		Set<Object> left = Collections.newSetFromMap(new IdentityHashMap<>());
		left.addAll(Arrays.asList(a));
		for (Object o : b) if (!left.remove(o)) return false;
		return left.isEmpty();
	}

	// ------------------------------------------------------------------ [0] minimal repro

	/**
	 * 最小复现（主指挥官排障指令 #1）：4 个类、每类 3 个实例、无无关对象。
	 *
	 * <p>分三种调用形态，判断污染是「单次调用内」还是「多次调用之间」才会出现：</p>
	 * <ol>
	 *   <li>一次调用 4 个 target（等价于 K=4 ≤ 一块）；</li>
	 *   <li>两次调用各 2 个 target（模拟 K=4 被切成两块）；</li>
	 *   <li>同一批连续调用两次（看第二次是否被上一次残留污染）。</li>
	 * </ol>
	 * <p>对每个落错行/落错类别的元素打印它的类、是否数组、长度与前几个元素，
	 * 便于判断"混进来的是结果行数组本身"还是"堆里被误打 tag 的无关数组"。</p>
	 */
	private static void minRepro() throws Exception {
		System.out.println("\n--- [0] minimal repro: 4 targets x 3 instances, no unrelated objects ---");
		Map<String, String> sources = new LinkedHashMap<>();
		List<String> names = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			String name = "Probe.Min" + i;
			names.add(name);
			sources.put(name, classSource(name, true));
		}
		Map<String, byte[]> bytes = compile(sources);
		Class<?>[] hosts = hostsOf(names, bytes);
		for (Class<?> host : hosts) {
			Constructor<?> ctor = host.getDeclaredConstructor(int.class);
			ctor.setAccessible(true);
			for (int i = 0; i < 3; i++) KEEP_ALIVE.add(ctor.newInstance(i));
		}

		Object[][] once = LibTool.getInstancesBatch(hosts);
		reportRows("one call / 4 targets", hosts, once, 3);

		Object[][] split = new Object[4][];
		Class<?>[] first = {hosts[0], hosts[1]};
		Class<?>[] second = {hosts[2], hosts[3]};
		Object[][] a = LibTool.getInstancesBatch(first);
		Object[][] b = LibTool.getInstancesBatch(second);
		split[0] = a[0]; split[1] = a[1]; split[2] = b[0]; split[3] = b[1];
		reportRows("two calls / 2 targets each", hosts, split, 3);

		Object[][] again = LibTool.getInstancesBatch(hosts);
		reportRows("second identical call", hosts, again, 3);

		check("[0] single call over 4 targets must be clean", rowsClean(hosts, once, 3));
		check("[0] two calls over 2 targets each must be clean", rowsClean(hosts, split, 3));
		check("[0] a repeated identical call must be clean", rowsClean(hosts, again, 3));
	}

	private static boolean rowsClean(Class<?>[] hosts, Object[][] rows, int expected) {
		for (int i = 0; i < hosts.length; i++) {
			if (rows[i] == null || rows[i].length != expected) return false;
			for (Object o : rows[i]) if (o.getClass() != hosts[i]) return false;
		}
		return true;
	}

	private static void reportRows(String label, Class<?>[] hosts, Object[][] rows, int expected) {
		System.out.printf("   [%s]%n", label);
		for (int i = 0; i < hosts.length; i++) {
			int n = rows[i] == null ? 0 : rows[i].length;
			int wrong = 0;
			String sample = "";
			if (rows[i] != null) {
				for (Object o : rows[i]) {
					if (o.getClass() == hosts[i]) continue;
					wrong++;
					sample = describeForeign(o);
				}
			}
			String deep = rows[i] == null ? "null" : deepNames(rows[i]);
			System.out.printf("      target#%d %-12s rows=%d (expect %d) wrong=%d %s row=%s%n",
			                  i, hosts[i].getSimpleName(), n, expected, wrong, sample, deep);
		}
	}

	private static String deepNames(Object[] row) {
		return deepNames(row, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
	}

	private static String deepNames(Object[] row, Set<Object> seen, int depth) {
		if (!seen.add(row)) return "<cycle>";
		if (depth > 3) return "<deep>";
		StringBuilder sb = new StringBuilder("[");
		for (int k = 0; k < row.length; k++) {
			Object e = row[k];
			if (k > 0) sb.append(',');
			sb.append(e == null ? "null"
			        : e instanceof Object[] arr ? ("arr" + arr.length + "<" + deepNames(arr, seen, depth + 1) + ">")
			        : e.getClass().getSimpleName());
		}
		return sb.append(']').toString();
	}

	private static String describeForeign(Object o) {
		StringBuilder sb = new StringBuilder("foreign=").append(o.getClass().getName());
		if (o instanceof Object[] arr) {
			sb.append(" len=").append(arr.length).append(" head=");
			for (int k = 0; k < Math.min(3, arr.length); k++) {
				Object e = arr[k];
				sb.append(e == null ? "null" : e.getClass().getSimpleName()).append(',');
			}
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ [1] live instances

	private static void measureLiveInstanceScope() throws Exception {
		System.out.println("\n--- [1] live instances: batch collection feeds the real patch (end-state assertions) ---");
		// 批次规模必须 ≥ InitFix.BATCH_HEAP_SCAN_MIN_TARGETS，否则批收集按设计不接合，
		// 这条用例就测不到"批收集喂给真实补丁"的链路。
		Map<String, String> sources1 = new LinkedHashMap<>();
		Map<String, String> sources2 = new LinkedHashMap<>();
		sources1.put("Probe.BatchScope", SCOPE_V1);
		sources2.put("Probe.BatchScope", SCOPE_V2);
		sources2.put("Probe.BatchScopeSub", SCOPE_SUB);
		for (int i = 0; i < 8; i++) {
			String name = "Probe.BatchOther" + i;
			sources1.put(name, classSource(name, false));
			sources2.put(name, classSource(name, true));
		}
		Map<String, byte[]> v1 = compile(sources1);
		Map<String, byte[]> v2 = compile(sources2);
		ByteLoader loader = new ByteLoader(v2);
		Class<?> host = Class.forName("Probe.BatchScope", true, loader);
		Class<?> sub  = Class.forName("Probe.BatchScopeSub", true, loader);
		List<Class<?>> others = new ArrayList<>();
		for (int i = 0; i < 8; i++) others.add(Class.forName("Probe.BatchOther" + i, true, loader));

		List<Object> hosts = new ArrayList<>();
		List<Object> subs = new ArrayList<>();
		Constructor<?> hostCtor = host.getDeclaredConstructor();
		Constructor<?> subCtor = sub.getDeclaredConstructor();
		hostCtor.setAccessible(true);
		subCtor.setAccessible(true);
		for (int i = 0; i < 3; i++) hosts.add(hostCtor.newInstance());
		for (int i = 0; i < 2; i++) subs.add(subCtor.newInstance());
		List<Object> otherInstances = new ArrayList<>();
		for (Class<?> other : others) {
			Constructor<?> otherCtor = other.getDeclaredConstructor(int.class);
			otherCtor.setAccessible(true);
			for (int i = 0; i < 4; i++) otherInstances.add(otherCtor.newInstance(i));
		}

		// 模拟"存量实例"：v2 构造器已经把新值写好了，这里把字段打回旧值（新增字段打回默认零值），
		// 让补丁必须真的写一次才可能得到新值——否则下面的断言会被"构造器早就写好了"蒙过去。
		Field selfF = host.getDeclaredField("self");
		Field inclF = host.getDeclaredField("incl");
		Field addedF = host.getDeclaredField("added");
		selfF.setAccessible(true);
		inclF.setAccessible(true);
		addedF.setAccessible(true);
		List<Object> all = new ArrayList<>(hosts);
		all.addAll(subs);
		for (Object o : all) {
			selfF.setInt(o, 1);
			inclF.setInt(o, 1);
			addedF.setInt(o, 0);
		}
		List<Field> otherAddedFields = new ArrayList<>();
		for (Class<?> other : others) {
			Field f = other.getDeclaredField("added");
			f.setAccessible(true);
			otherAddedFields.add(f);
		}
		for (int i = 0; i < otherInstances.size(); i++) {
			otherAddedFields.get(i / 4).setInt(otherInstances.get(i), 0);
		}
		check("baseline: host instances must start at the OLD values",
			hosts.stream().allMatch(o -> readInt(selfF, o) == 1 && readInt(addedF, o) == 0));

		List<Class<?>> batchTargets = new ArrayList<>();
		batchTargets.add(host);
		batchTargets.addAll(others);
		long before = LibTool.heapWalkCount();
		transform(host, v1.get("Probe.BatchScope"), v2.get("Probe.BatchScope"));
		for (Class<?> other : others) {
			String name = other.getName();
			transform(other, v1.get(name), v2.get(name));
		}
		InitFix.beginBatchInstanceCollection(batchTargets);
		for (Class<?> c : batchTargets) InitFix.afterRedefine(c);
		InitFix.endBatchInstanceCollection();
		long walks = LibTool.heapWalkCount() - before;

		int hostSelf = 0, hostIncl = 0, hostAdded = 0;
		for (Object o : hosts) {
			if (readInt(selfF, o) == 2) hostSelf++;
			if (readInt(inclF, o) == 2) hostIncl++;
			if (readInt(addedF, o) == 9) hostAdded++;
		}
		int subSelf = 0, subIncl = 0, subAdded = 0;
		for (Object o : subs) {
			if (readInt(selfF, o) == 2) subSelf++;
			if (readInt(inclF, o) == 2) subIncl++;
			if (readInt(addedF, o) == 9) subAdded++;
		}
		int otherAddedOk = 0;
		for (int i = 0; i < otherInstances.size(); i++) {
			// classSource(v2) 的初值是 42（新增字段），补丁必须把它写到每个存量实例上。
			if (readInt(otherAddedFields.get(i / 4), otherInstances.get(i)) == 42) otherAddedOk++;
		}

		System.out.printf("   batch of %d classes: walks=%d  host(SELF)=%d/%d  host(INCL)=%d/%d  host(added)=%d/%d%n",
		                  batchTargets.size(), walks, hostSelf, hosts.size(), hostIncl, hosts.size(),
		                  hostAdded, hosts.size());
		System.out.printf("   subclass instances: SELF=%d/%d (must stay 0)  INCL=%d/%d  added=%d/%d%n",
		                  subSelf, subs.size(), subIncl, subs.size(), subAdded, subs.size());
		System.out.printf("   other classes in the same batch: added=%d/%d%n",
		                  otherAddedOk, otherInstances.size());

		check("the whole patch round of " + batchTargets.size() + " classes must cost exactly one heap walk",
			walks == 1);
		check("@RemoldReinit(SELF) must write every host instance", hostSelf == hosts.size());
		check("@RemoldReinit(SELF) must NOT touch subclass instances", subSelf == 0);
		check("@RemoldReinit(INCLUDE_SUBCLASSES) must write host instances", hostIncl == hosts.size());
		check("@RemoldReinit(INCLUDE_SUBCLASSES) must write subclass instances", subIncl == subs.size());
		check("new instance field must be patched on host instances", hostAdded == hosts.size());
		check("new instance field must be patched on subclass instances", subAdded == subs.size());
		check("every class of the batch must get its own instances patched",
			otherAddedOk == otherInstances.size());

		// 保留强引用到探针结束：被 GC 回收就测不到"存活实例"了。
		KEEP_ALIVE.addAll(all);
		KEEP_ALIVE.addAll(otherInstances);
		StageBInstanceCache.clear();
	}

	private static int readInt(Field f, Object target) {
		try {
			return f.getInt(target);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	private static final List<Object> KEEP_ALIVE = new ArrayList<>();

	// ------------------------------------------------------------------ [2] + [3] cost and walk counts

	private static void measureBatchCost(int k) throws Exception {
		System.out.printf("%n--- [2] K=%d: batch collection on/off, %d rounds, alternating within the same round ---%n",
		                  k, rounds);
		List<String> names = new ArrayList<>();
		Map<String, String> sources1 = new LinkedHashMap<>();
		Map<String, String> sources2 = new LinkedHashMap<>();
		for (int i = 0; i < k; i++) {
			String name = "Probe.BatchK" + i;
			names.add(name);
			sources1.put(name, classSource(name, false));
			sources2.put(name, classSource(name, true));
		}
		Map<String, byte[]> v1 = compile(sources1);
		Map<String, byte[]> v2 = compile(sources2);

		RemoldAgent.PERF_LOG = true;
		long[] collectOn = new long[rounds], collectOff = new long[rounds];
		long[] fullOn = new long[rounds], fullOff = new long[rounds];
		long[] walksOn = new long[rounds], walksOff = new long[rounds];

		for (int r = -2; r < rounds; r++) {  // 前两轮预热，不计入
			for (boolean batch : new boolean[]{ true, false }) {
				Round result = runRound(names, v1, v2, batch);
				if (r < 0) continue;
				if (batch) {
					collectOn[r] = result.collectNanos;
					fullOn[r] = result.fullNanos;
					walksOn[r] = result.walks;
				} else {
					collectOff[r] = result.collectNanos;
					fullOff[r] = result.fullNanos;
					walksOff[r] = result.walks;
				}
			}
			if (r < 0) continue;
			StageBInstanceCache.clear();
		}
		RemoldAgent.PERF_LOG = false;

		System.out.printf("   collect  batch-on  %s%n", describe(collectOn));
		System.out.printf("   collect  batch-off %s%n", describe(collectOff));
		System.out.printf("   full     batch-on  %s%n", describe(fullOn));
		System.out.printf("   full     batch-off %s%n", describe(fullOff));
		System.out.printf("   heap walks per round: on=%s off=%s%n", describeCounts(walksOn), describeCounts(walksOff));

		// 一次诊断调用取 native 分段，回答"批遍历的时间花在哪一段"（enumerate 是 GetLoadedClasses +
		// IsAssignableFrom 归类，与堆规模无关、与已加载类数 × K 成正比）。
		long[] timings = new long[6];
		Class<?>[] diagHosts = hostsOf(names, v2);
		for (Class<?> host : diagHosts) {
			Constructor<?> ctor = host.getDeclaredConstructor(int.class);
			ctor.setAccessible(true);
			for (int i = 0; i < instanceCount; i++) KEEP_ALIVE.add(ctor.newInstance(i));
		}
		LibTool.getInstancesBatch(diagHosts, timings);
		System.out.printf("   native breakdown (one call): enumerate=%.3fms iterate=%.3fms collect=%.3fms"
			+ " fill=%.3fms cleanup=%.3fms hits=%d%n",
		                  timings[0] / 1e6, timings[1] / 1e6, timings[2] / 1e6, timings[3] / 1e6,
		                  timings[4] / 1e6, timings[5]);

		if (k >= InitFix.BATCH_HEAP_SCAN_MIN_TARGETS) {
			// 一次 native 批查询最多 BATCH_TARGET_LIMIT 个 target（槽位/tag 预算），K 更大时按块切分，
			// 所以断言是 ceil(K/LIMIT) 而不是恒等于 1：K=64 时 4 次遍历，仍是逐类 64 次的 1/16。
			check("K=" + k + ": batch-on must collapse walks to ceil(K/" + LibTool.BATCH_TARGET_LIMIT + ")",
			      allEqual(walksOn, expectedBatchWalks(k)));
		} else {
			check("K=" + k + " (below the engagement threshold): batch-on must not engage"
				+ " (same walk count as the per-class path)", allEqual(walksOn, k));
		}
		check("K=" + k + ": batch-off must perform one heap walk per class per round", allEqual(walksOff, k));
		if (k >= InitFix.BATCH_HEAP_SCAN_MIN_TARGETS) {
			check("K=" + k + ": collect must get cheaper (min and median) with batch collection",
				min(collectOn) < min(collectOff) && median(collectOn) < median(collectOff));
			check("K=" + k + ": full patch phase must not get slower (min and median) with batch collection",
				min(fullOn) <= min(fullOff) && median(fullOn) <= median(fullOff));
		} else if (k > 1) {
			// 低于接合阈值的批次刻意不启用批收集（一次全堆遍历 ≫ 一次按类遍历），
			// 因此这里必须看到与逐类路径完全相同的遍历次数与量级。
			check("K=" + k + " (below the engagement threshold): batch collection must not engage",
				allEqual(walksOn, k) && allEqual(walksOff, k));
		} else {
			check("K=1: batch collection must be a no-op (same walk count as the per-class path)",
				allEqual(walksOn, expectedBatchWalks(k)) && allEqual(walksOff, 1));
		}
		if (k > 1 && (min(collectOn) < min(collectOff)) != (median(collectOn) < median(collectOff))) {
			System.out.println("   [NOTE] collect comparison is sensitive to the statistic (min/median disagree)");
		}
	}

	record Round(long collectNanos, long fullNanos, long walks) { }

	private static Round runRound(List<String> names, Map<String, byte[]> v1, Map<String, byte[]> v2,
	                              boolean batch) throws Exception {
		ByteLoader loader = new ByteLoader(v2);
		List<Class<?>> hosts = new ArrayList<>(names.size());
		List<Object> instances = new ArrayList<>();
		for (String name : names) {
			Class<?> host = Class.forName(name, true, loader);
			hosts.add(host);
			Constructor<?> ctor = host.getDeclaredConstructor(int.class);
			ctor.setAccessible(true);
			for (int i = 0; i < instanceCount; i++) instances.add(ctor.newInstance(i));
		}
		for (int i = 0; i < names.size(); i++) {
			RemoldAgent.bytecodeCache.put(names.get(i).replace('.', '/'), v2.get(names.get(i)));
			transform(hosts.get(i), v1.get(names.get(i)), v2.get(names.get(i)));
		}

		infoLog.clear();
		long before = LibTool.heapWalkCount();
		long t0 = System.nanoTime();
		long collectNanos;
		if (batch) {
			long c0 = System.nanoTime();
			InitFix.beginBatchInstanceCollection(hosts);
			collectNanos = System.nanoTime() - c0;
		} else {
			collectNanos = 0L;
		}
		for (Class<?> host : hosts) InitFix.afterRedefine(host);
		if (batch) InitFix.endBatchInstanceCollection();
		long fullNanos = System.nanoTime() - t0;
		long walks = LibTool.heapWalkCount() - before;
		if (!batch) {
			// 关批收集时枚举发生在 applyPatch 内部：用产品自带的 perf log 分段把 collect 加回来，
			// 两种模式因此是同一个口径（"本批全部类的实例枚举耗时"）。
			// 格式：... [collect=0.1, compact=0.0, ...]（括号内没有 ms 后缀，取到下一个分隔符为止）
			for (String msg : new ArrayList<>(infoLog)) {
				int idx = msg.indexOf("[collect=");
				if (idx < 0) continue;
				int start = idx + "[collect=".length();
				int end = start;
				while (end < msg.length() && ",])".indexOf(msg.charAt(end)) < 0) end++;
				try {
					collectNanos += (long) (Double.parseDouble(msg.substring(start, end).trim()) * 1_000_000L);
				} catch (NumberFormatException ignored) { }
			}
		}
		KEEP_ALIVE.addAll(instances);
		return new Round(collectNanos, fullNanos, walks);
	}

	private static Class<?>[] hostsOf(List<String> names, Map<String, byte[]> bytes) throws Exception {
		ByteLoader loader = new ByteLoader(bytes);
		Class<?>[] hosts = new Class<?>[names.size()];
		for (int i = 0; i < names.size(); i++) hosts[i] = Class.forName(names.get(i), true, loader);
		return hosts;
	}

	private static void transform(Class<?> host, byte[] v1, byte[] v2) {
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(v1, v2);
		InitFix.transform(host, v2, diff);
		if (InitFix.getLastReport(host) == null) throw new IllegalStateException("no report for " + host.getName());
	}

	// ------------------------------------------------------------------ [4] D3 liveness probe

	private static void measureLivenessProbe() throws Exception {
		System.out.println("\n--- [4] D3: batch liveness probe (layout gate) ---");
		int k = 64;
		List<String> names = new ArrayList<>();
		Map<String, String> sources = new LinkedHashMap<>();
		for (int i = 0; i < k; i++) {
			String name = "Probe.DeadK" + i;
			names.add(name);
			sources.put(name, classSource(name, false));
		}
		Map<String, byte[]> bytes = compile(sources);
		ByteLoader loader = new ByteLoader(bytes);
		Class<?>[] targets = new Class<?>[k];
		for (int i = 0; i < k; i++) targets[i] = Class.forName(names.get(i), true, loader);

		long beforeBatch = LibTool.heapWalkCount();
		long b0 = System.nanoTime();
		boolean[] batch = LibTool.hasLiveInstancesBatch(targets);
		long batchNanos = System.nanoTime() - b0;
		long batchWalks = LibTool.heapWalkCount() - beforeBatch;

		long beforePerClass = LibTool.heapWalkCount();
		long p0 = System.nanoTime();
		boolean[] perClass = new boolean[k];
		for (int i = 0; i < k; i++) perClass[i] = LibTool.hasLiveInstance(targets[i]);
		long perClassNanos = System.nanoTime() - p0;
		long perClassWalks = LibTool.heapWalkCount() - beforePerClass;

		System.out.printf("   K=%d zero-instance classes: batch walks=%d (%.3fms)  per-class walks=%d (%.3fms)%n",
		                  k, batchWalks, batchNanos / 1e6, perClassWalks, perClassNanos / 1e6);
		check("D3: batch liveness probe must collapse K=" + k + " heap walks into one", batchWalks == 1);
		check("D3: per-class liveness probe must cost one walk per class", perClassWalks == k);
		check("D3: batch and per-class liveness results must agree", Arrays.equals(batch, perClass));
		check("D3: a never-instantiated class must report no live instance",
			!batch[0] && !perClass[0]);
	}

	// ------------------------------------------------------------------ fixtures

	private static final String SCOPE_V1 = """
		package Probe;
		import remold.annotation.RemoldReinit;
		public class BatchScope {
			int plain = 1;
			@RemoldReinit(scope = RemoldReinit.Scope.SELF)
			int self = 1;
			@RemoldReinit(scope = RemoldReinit.Scope.INCLUDE_SUBCLASSES)
			int incl = 1;
			public BatchScope() { }
		}
		""";

	private static final String SCOPE_V2 = """
		package Probe;
		import remold.annotation.RemoldReinit;
		public class BatchScope {
			int plain = 1;
			@RemoldReinit(scope = RemoldReinit.Scope.SELF)
			int self = 2;
			@RemoldReinit(scope = RemoldReinit.Scope.INCLUDE_SUBCLASSES)
			int incl = 2;
			int added = 9;
			public BatchScope() { }
		}
		""";

	private static final String SCOPE_SUB = """
		package Probe;
		public class BatchScopeSub extends BatchScope {
			public BatchScopeSub() { }
		}
		""";

	private static String classSource(String simpleName, boolean v2) {
		String cls = simpleName.substring(simpleName.indexOf('.') + 1);
		if (!v2) {
			return """
				package Probe;
				public class %s {
					private int seed;
					public %s(int seed) { this.seed = seed; }
					public int get() { return seed; }
				}
				""".formatted(cls, cls);
		}
		return """
			package Probe;
			public class %s {
				private int seed;
				private int added = 42;
				public %s(int seed) { this.seed = seed; }
				public int get() { return seed + added; }
			}
			""".formatted(cls, cls);
	}

	// ------------------------------------------------------------------ helpers

	private static void check(String what, boolean ok) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + what);
		if (!ok) failures++;
	}

	private static String describe(long[] nanos) {
		long[] sorted = nanos.clone();
		Arrays.sort(sorted);
		return String.format("min=%.3fms median=%.3fms max=%.3fms n=%d",
		                     sorted[0] / 1e6, sorted[sorted.length / 2] / 1e6, sorted[sorted.length - 1] / 1e6,
		                     sorted.length);
	}

	private static String describeCounts(long[] values) {
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		return sorted[0] == sorted[sorted.length - 1]
			? String.valueOf(sorted[0])
			: "min=" + sorted[0] + " max=" + sorted[sorted.length - 1];
	}

	private static long min(long[] values) {
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		return sorted[0];
	}

	private static long median(long[] values) {
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		return sorted[sorted.length / 2];
	}

	private static boolean allEqual(long[] values, long expected) {
		for (long v : values) if (v != expected) return false;
		return true;
	}

	/** 只从内存字节码 defineClass 的加载器。 */
	static final class ByteLoader extends ClassLoader {
		private final Map<String, byte[]> classes;

		ByteLoader(Map<String, byte[]> classes) {
			super(InitFixBatchScanProbe.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}

		@Override
		public java.io.InputStream getResourceAsStream(String name) {
			java.io.InputStream in = super.getResourceAsStream(name);
			if (in != null) return in;
			if (!name.endsWith(".class")) return null;
			String dot = name.substring(0, name.length() - ".class".length()).replace('/', '.');
			byte[] bytes = classes.get(dot);
			return bytes == null ? null : new java.io.ByteArrayInputStream(bytes);
		}
	}

	/** 内存 javac（与 {@code InitFixOracle} 同一套路，自成一体）。 */
	private static Map<String, byte[]> compile(Map<String, String> sources) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) throw new IllegalStateException("run this probe on a JDK, not a JRE");
		Map<String, byte[]> out = new LinkedHashMap<>();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		StandardJavaFileManager standard =
			compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
		JavaFileManager memory = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
			@Override
			public JavaFileObject getJavaFileForOutput(Location location, String className,
			                                           JavaFileObject.Kind kind, FileObject sibling) {
				URI uri = URI.create("mem:///" + className.replace('.', '/') + kind.extension);
				return new SimpleJavaFileObject(uri, kind) {
					@Override
					public OutputStream openOutputStream() {
						return new ByteArrayOutputStream() {
							@Override
							public void close() throws IOException {
								super.close();
								out.put(className, toByteArray());
							}
						};
					}
				};
			}
		};
		List<JavaFileObject> units = new ArrayList<>();
		for (Map.Entry<String, String> e : sources.entrySet()) {
			units.add(new SimpleJavaFileObject(
				URI.create("string:///" + e.getKey().replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
				JavaFileObject.Kind.SOURCE) {
				@Override
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
					return e.getValue();
				}
			});
		}
		List<String> options = List.of("-nowarn", "-proc:none", "-classpath",
		                               System.getProperty("java.class.path"));
		boolean ok = compiler.getTask(null, memory, diagnostics, options, null, units).call();
		if (!ok) throw new IllegalStateException("fixture compile failed: " + diagnostics.getDiagnostics());
		return out;
	}
}
