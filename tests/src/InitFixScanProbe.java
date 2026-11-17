import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import remold.ClassDiffUtil;
import remold.RemoldAgent;
import remold.ref.HierarchyTreeOracle;
import remold.ref.ReinitScopeGate;
import remold.ref.StageBInstanceCache;
import remold.ref.InitFix;

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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * InitFix 侧扫描成本探针（PR2 slice 3 · Batch A）。
 *
 * <p>只测量、不断言、不改变生产逻辑。四个分段：</p>
 * <ol>
 *   <li><b>[1] 每次 {@code InitFix.transform} 的真实成本</b>：每轮用全新 {@code Class}（新加载器 defineClass）
 *       重复跑同一份 diff，取 min / median / max——热更的真实形态就是"每个类在每轮只被处理一次"。</li>
 *   <li><b>[2] 成本归因</b>：JFR {@code jdk.ExecutionSample}（1 ms）采样，按栈顶帧归类到
 *       ASM 数据流 / 字节解析 / {@code HierarchyTreeOracle}/{@code NestView} / InitFix 其余部分。
 *       这是"不碰生产代码也能看真实调用栈"的唯一手段。</li>
 *   <li><b>[3] 跨类字节读取的广度</b>：同一语料上重复调用 {@code HierarchyTreeOracle.classBytes}，
 *       统计读了多少个类、同一份内容被重复解码多少次；再对 Nest 夹具统计一次 transform 内每个成员的读取次数。</li>
 *   <li><b>[4] Reinit 作用域门的触发频次与成本</b>：{@code unsafeInheritedReinitFields} 在子类数量
 *       0 / 4 / 32 下的成本曲线，以及"没有 {@code @RemoldReinit} 字段"时的空转成本。</li>
 * </ol>
 *
 * <p><b>读数口径（§3 数据汇报铁律）</b>：所有耗时都是同一进程、同一轮次内测得的墙钟时间；
 * 探针必须<b>串行排他</b>运行（跑之前不要有别的 Gradle/探针在跑）；凡是结论，必须同时看 min 与 median。</p>
 */
public final class InitFixScanProbe {

	// ------------------------------------------------------------------ fixtures

	/** 一份夹具：旧/新两版字节 + 主类内部名。 */
	record Case(String dotName, byte[] v1, byte[] v2, Map<String, byte[]> v2Classes) {
		String internalName() { return dotName.replace('.', '/'); }
	}

	static final String EAGER_V1 = """
		package Probe;
		public class Eager {
			private int a;
			private final String tag;
			public Eager(int a) { this.a = a; this.tag = "v1"; }
			public int get() { return a; }
			public String tag() { return tag; }
		}
		""";

	static final String EAGER_V2 = """
		package Probe;
		public class Eager {
			private int a;
			private final String tag;
			private final int x = a * 2 + 1;
			private final String s = constTag();
			public Eager(int a) { this.a = a; this.tag = "v2"; }
			public int get() { return a + x; }
			public String tag() { return tag; }
			private static String constTag() { return "T"; }
		}
		""";

	static final String PARAM_V1 = """
		package Probe;
		public class Param {
			private int a;
			public Param(int a) { this.a = a; }
			public int get() { return a; }
			public int pick(int i) {
				if (i > 0) return a + i;
				return a - i;
			}
		}
		""";

	static final String PARAM_V2 = """
		package Probe;
		public class Param {
			private int a;
			private final int y = a + 1;
			private final int z = lookup(a);
			public Param(int a) { this.a = a; }
			public int get() { return a + y + z; }
			public int pick(int i) {
				if (i > 0) return a + i;
				return a - i;
			}
			private static int lookup(int v) { return v * 3; }
		}
		""";

	static final String OUTER_V1 = """
		package Probe;
		public class Outer {
			private int seed;
			public Outer(int seed) { this.seed = seed; }
			public int seed() { return seed; }
			public class A { public int read() { return seed + 1; } }
			public class B { public int read() { return seed + 2; } }
		}
		""";

	static final String OUTER_V2 = """
		package Probe;
		public class Outer {
			private int seed;
			private final int bump = seed + 7;
			public Outer(int seed) { this.seed = seed; }
			public int seed() { return seed + bump; }
			public class A {
				private final int local = seed + 3;
				public int read() { return seed + 1 + local; }
			}
			public class B {
				private final int local = seed + 4;
				public int read() { return seed + 2 + local; }
			}
		}
		""";

	static final String BASE_V1 = """
		package Probe;
		public class Base {
			@remold.annotation.RemoldReinit(scope = remold.annotation.RemoldReinit.Scope.INCLUDE_SUBCLASSES)
			int v = 1;
			public int get() { return v; }
		}
		""";

	static final String BASE_V2 = """
		package Probe;
		public class Base {
			@remold.annotation.RemoldReinit(scope = remold.annotation.RemoldReinit.Scope.INCLUDE_SUBCLASSES)
			int v = 2;
			public int get() { return v; }
		}
		""";

	private static final List<String> infoLog = java.util.Collections.synchronizedList(new ArrayList<>());

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

		Path root = Path.of(System.getProperty("remold.projectRoot", System.getProperty("user.dir")));
		System.out.println("=== InitFix scan probe (PR2 slice 3 · Batch A) ===");
		System.out.printf("   java.version=%s  root=%s%n", System.getProperty("java.version"), root);

		List<Case> cases = List.of(
			eagerCase(), paramCase(), outerCase());
		Case big = bigCase(60);
		Case baseCase = baseCase();

		measurePerTransform(cases, big);
		measureAttribution(List.of(big, eagerCase(), outerCase()));
		measureCrossClassWidth(root, cases);
		measureReinitScope(baseCase);

		check("afterRedefine must succeed for every transform", afterRedefineFailures == 0);
		check("gate must reject when a subclass writes the inherited field", gateRejected > 0);
		check("nest members must actually be read during a transform", nestReads > 0);
		check("product perf log must yield phase lines", perfLines > 0);
		check("JFR must attribute a majority of samples to InitFix", initFixSampleRatio > 0.5);

		System.out.println("\n=== INITFIX SCAN PROBE: " + (failures == 0 ? "self-checks passed" : failures + " CHECK(S) FAILED") + " ===");
		if (failures != 0) System.exit(1);
	}

	// ------------------------------------------------------------------ [1] per-transform cost

	/**
	 * 每次 transform 的真实成本：每轮新建加载器 + defineClass，得到全新的 {@code Class}，
	 * 于是 ledger / PENDING 都是"首见"状态——与热更批次里"每个类每轮只处理一次"一致。
	 */
	private static void measurePerTransform(List<Case> cases, Case big) {
		System.out.println("\n--- [1] per-transform InitFix cost (fresh Class each round) ---");
		List<Case> all = new ArrayList<>(cases);
		all.add(big);
		long[] every = new long[all.size() * 60];
		int n = 0;
		for (Case c : all) {
			long[] nanos = new long[60];
			for (int i = 0; i < nanos.length; i++) {
				nanos[i] = transformOnce(c);
				every[n++] = nanos[i];
			}
			System.out.printf("   %-14s %s%n", c.dotName(), describe(nanos));
		}
		System.out.printf("   %-14s %s%n", "ALL", describe(Arrays.copyOf(every, n)));

		System.out.printf("   afterRedefine failures=%d (应为 0；非 0 说明补丁没被真实应用)%n",
		                  afterRedefineFailures);
		long[] apply = new long[40];
		Case probeCase = big;
		for (int i = 0; i < apply.length; i++) {
			transformOnce(probeCase);
			apply[i] = lastAfterRedefineNanos;
		}
		System.out.printf("   %-14s %s  (applyPatch 段，不在 transform 计时内)%n", "afterRedefine",
		                  describe(apply));

		// 产品自带 Perf Log（-Dremold.agent.perf_log 的同一套打点）给出 applyPatch 内部分段，
		// 比 JFR 采样可靠：JVMTI 堆遍历在 native 态，ExecutionSample 基本采不到，
		// 于是"applyPatch 占 96% 墙钟"在采样里只表现为 10% 的样本（采样口径固有偏差）。
		System.out.println("   applyPatch phase split (product perf log, n=40):");
		RemoldAgent.PERF_LOG = true;
		infoLog.clear();
		for (int i = 0; i < 40; i++) transformOnce(probeCase);
		RemoldAgent.PERF_LOG = false;
		reportApplyPatchPhases();

		System.out.println("   scale sweep (median of 40, fresh Class each round):");
		for (int methods : new int[]{5, 20, 60, 240}) {
			Case c = bigCase(methods);
			long[] nanos = new long[40];
			for (int i = 0; i < nanos.length; i++) nanos[i] = transformOnce(c);
			System.out.printf("     methods=%-4d bytes=%-6d %s%n",
			                  methods, c.v2().length, describe(nanos));
		}
	}

	private static int afterRedefineFailures;
	private static int gateRejected;
	private static int nestReads;
	private static int perfLines;
	private static double initFixSampleRatio;
	private static int failures;

	private static void check(String what, boolean ok) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + what);
		if (!ok) failures++;
	}
	private static int transformsSinceBatchClear;
	private static volatile long lastAfterRedefineNanos;

	/** 跑一次 transform 并返回墙钟纳秒；diff 计算不计入（属于上游，不在 InitFix 内）。 */
	private static long transformOnce(Case c) {
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(c.v1(), c.v2());
		ByteLoader loader = new ByteLoader(c.v2Classes());
		Class<?> host;
		try {
			host = Class.forName(c.dotName(), true, loader);
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException(e);
		}
		RemoldAgent.bytecodeCache.put(c.internalName(), c.v2());
		long t0 = System.nanoTime();
		InitFix.transform(host, c.v2(), diff);
		long dt = System.nanoTime() - t0;
		if (InitFix.getLastReport(host) == null) throw new IllegalStateException("no report for " + c.dotName());
		// 关键：真实热更在 redefine 成功后一定会调 afterRedefine 把 PENDING 条目摘掉。
		// 本探针同样必须调——否则 PENDING 会随每轮新增的 Class 越积越多，而
		// cleanupStalePatches() 每次 transform 都要扫全表，测出来的就是"探针自身的历史包袱"
		// 而不是 InitFix 的成本（首版没调时该函数独占 23.2% 样本，属测量假象）。
		long a0 = System.nanoTime();
		try {
			InitFix.afterRedefine(host);
		} catch (RuntimeException e) {
			afterRedefineFailures++;
		}
		lastAfterRedefineNanos = System.nanoTime() - a0;
		// 批次边界：真实热更每批结束会清批次级缓存（StageBInstanceCache.clear()）。
		// 探针若不清，该缓存会随每轮新增的 Class 无界增长，而它每次读写都要 sweep 全表——
		// 首版没清时 afterRedefine 中位 23.3 ms、StageBInstanceCache 占 48.3% 样本，属探针自身的历史包袱。
		if (++transformsSinceBatchClear >= 64) {
			transformsSinceBatchClear = 0;
			StageBInstanceCache.clear();
		}
		return dt;
	}

	/** 解析 {@code [InitFix] applyPatch(...)} 的分段耗时并报中位数。 */
	private static void reportApplyPatchPhases() {
		Map<String, List<Double>> phases = new LinkedHashMap<>();
		List<Double> totals = new ArrayList<>();
		var pattern = java.util.regex.Pattern.compile(
			"applyPatch\\(.*\\): live=(\\d+), total=([0-9.]+)ms \\[(.*)\\]");
		for (String msg : new ArrayList<>(infoLog)) {
			var m = pattern.matcher(msg);
			if (!m.find()) continue;
			totals.add(Double.parseDouble(m.group(2)));
			for (String part : m.group(3).split(",")) {
				String[] kv = part.trim().split("=");
				if (kv.length == 2) {
					phases.computeIfAbsent(kv[0].trim(), k -> new ArrayList<>())
					      .add(Double.parseDouble(kv[1].trim()));
				}
			}
		}
		perfLines = totals.size();
		if (totals.isEmpty()) {
			System.out.println("      [WARN] no applyPatch perf lines captured");
			return;
		}
		System.out.printf("      total       median=%.2fms  n=%d  (live instances=%d)%n",
		                  median(totals), totals.size(), 0);
		phases.forEach((k, v) -> System.out.printf("      %-11s median=%.2fms  share=%.1f%%%n",
		                                           k, median(v), 100.0 * median(v) / Math.max(1e-9, median(totals))));
	}

	private static double median(List<Double> values) {
		if (values.isEmpty()) return 0.0;
		List<Double> copy = new ArrayList<>(values);
		java.util.Collections.sort(copy);
		return copy.get(copy.size() / 2);
	}

	// ------------------------------------------------------------------ [2] attribution

	/**
	 * JFR 采样归因：不修改产品代码，靠真实调用栈把时间分到各段。
	 *
	 * <p>为什么必须这么测：`AliasInterpreter` 是 `InitFix` 的私有内部类，"把它关掉再测差值"这类消融
	 * 必须改生产代码；Batch A 的边界不允许改 `core/`。采样给的是"这些时间里栈顶落在哪"，属于观测，
	 * 不是对实现的重写。</p>
	 */
	private static void measureAttribution(List<Case> cases) throws Exception {
		System.out.println("\n--- [2] cost attribution (JFR ExecutionSample @1ms) ---");
		java.nio.file.Path dump;
		try (jdk.jfr.Recording rec = new jdk.jfr.Recording()) {
			// 1 ms 周期的栈采样会把被观测循环拖慢一个数量级（实测 4215 → 288 次/4s），
			// 样本量反而更少；5 ms / 20 s 是这里的折中。
			rec.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(5));
			rec.setToDisk(true);
			rec.start();
			long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
			int rounds = 0;
			while (System.nanoTime() < deadline) {
				for (Case c : cases) {
					transformOnce(c);
					rounds++;
				}
			}
			rec.stop();
			dump = Files.createTempFile("initfix-scan-probe", ".jfr");
			rec.dump(dump);
			System.out.printf("   workload: %d transforms in 20s%n", rounds);
		}
		Map<String, Integer> buckets = new LinkedHashMap<>();
		int samples = 0;
		int inInitFix = 0;
		try (jdk.jfr.consumer.RecordingFile file = new jdk.jfr.consumer.RecordingFile(dump)) {
			while (file.hasMoreEvents()) {
				jdk.jfr.consumer.RecordedEvent e = file.readEvent();
				if (!"jdk.ExecutionSample".equals(e.getEventType().getName())) continue;
				jdk.jfr.consumer.RecordedStackTrace st = e.getStackTrace();
				if (st == null || st.getFrames().isEmpty()) continue;
				samples++;
				// 只看"确实在 InitFix 调用栈里"的样本：JIT/GC/类定义等线程也会被采样，
				// 把它们算进分母会把各段占比整体稀释（实测未过滤时 jdk/other 占 62.8%）。
				boolean inside = false;
				for (jdk.jfr.consumer.RecordedFrame f : st.getFrames()) {
					String cls = String.valueOf(f.getMethod().getType().getName());
					if (cls.startsWith("remold.ref.InitFix") || cls.startsWith("org.objectweb.asm.")) {
						inside = true;
						break;
					}
				}
				if (!inside) continue;
				inInitFix++;
				// 归因到"离栈顶最近的自家代码帧"：栈顶常是 HashMap/Arrays/Unsafe 之类的底层方法，
				// 按栈顶归类会把一半样本塞进 jdk/other（实测未上溯时 jdk/other 占 52.5%），
				// 而那段时间到底属于哪一段，只有上溯到第一个 remold.* / org.objectweb.asm.* 帧才知道。
				String cls = null;
				String method = null;
				for (jdk.jfr.consumer.RecordedFrame f : st.getFrames()) {
					String c = String.valueOf(f.getMethod().getType().getName());
					if (c.startsWith("remold.") || c.startsWith("org.objectweb.asm.")) {
						cls = c;
						method = String.valueOf(f.getMethod().getName());
						break;
					}
				}
				buckets.merge(bucketOf(cls == null ? "?" : cls, method), 1, Integer::sum);
			}
		}
		Files.deleteIfExists(dump);
		initFixSampleRatio = inInitFix / (double) Math.max(1, samples);
		System.out.printf("   samples=%d  in-initfix=%d (%.1f%%)%n",
		                  samples, inInitFix, 100.0 * inInitFix / Math.max(1, samples));
		System.out.println("   注：ExecutionSample 只采 Java 态栈，JVMTI 堆遍历等 native 段被少采，"
		                   + "占比仅作扫描各段的相对比较，绝对切分以 applyPatch perf log 为准");
		final int total = Math.max(1, inInitFix);
		buckets.entrySet().stream()
		       .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
		       .limit(22)
		       .forEach(e -> System.out.printf("   %-34s %6d  %5.1f%%%n",
		                                        e.getKey(), e.getValue(), 100.0 * e.getValue() / total));
	}

	/**
	 * 归因标签：自己代码的"类#方法"。
	 *
	 * <p>相比"按包归类"，方法级标签是这里唯一的有效粒度：`initfix-other` 这类桶一旦占 47.7%，
	 * 按包分组就再也分不出"到底哪一步贵"，等于没测。</p>
	 */
	private static String bucketOf(String cls, String method) {
		String m = method == null ? "?" : method;
		if (cls.startsWith("remold.ref.InitFix$AliasInterpreter")) return "AliasInterpreter#" + m;
		if (cls.startsWith("org.objectweb.asm.tree.analysis.")) return simple(cls) + "#" + m;
		if (cls.startsWith("org.objectweb.asm.ClassReader")) return "ClassReader#" + m;
		if (cls.startsWith("org.objectweb.asm.ClassWriter")) return "ClassWriter#" + m;
		if (cls.startsWith("org.objectweb.asm.tree.")) return "asm.tree#" + m;
		if (cls.startsWith("org.objectweb.asm.")) return simple(cls) + "#" + m;
		if (cls.contains("NestView")) return "NestView#" + m;
		if (cls.startsWith("remold.")) return simple(cls) + "#" + m;
		return "jdk/other";
	}

	private static String simple(String cls) {
		int dot = cls.lastIndexOf('.');
		return dot < 0 ? cls : cls.substring(dot + 1);
	}

	// ------------------------------------------------------------------ [3] cross-class width

	/**
	 * 跨类字节读取的广度：同一语料上把 {@code classBytes} 读两遍，看"读了多少个类"与
	 * "同一份内容被解码多少次"。第二遍仍全部重解码，说明 oracle 侧没有任何内容级复用。
	 */
	private static void measureCrossClassWidth(Path root, List<Case> cases) throws Exception {
		System.out.println("\n--- [3] HierarchyTreeOracle cross-class byte reads (project corpus) ---");
		List<byte[]> corpus = collectProjectClasses(root, 1200);
		if (corpus.isEmpty()) {
			System.out.println("   [SKIP] no compiled classes under " + root);
		} else {
			long totalBytes = 0;
			List<String> names = new ArrayList<>();
			for (byte[] b : corpus) {
				names.add(new ClassReader(b).getClassName());
				totalBytes += b.length;
			}
			System.out.printf("   corpus=%d classes, %.2f MiB%n", names.size(), totalBytes / 1024.0 / 1024.0);
			HierarchyTreeOracle oracle = new HierarchyTreeOracle(InitFixScanProbe.class.getClassLoader());
			for (int round = 1; round <= 2; round++) {
				long t0 = System.nanoTime();
				int found = 0;
				Set<String> blobs = new LinkedHashSet<>();
				for (String name : names) {
					byte[] b = oracle.classBytes(name);
					if (b == null) continue;
					found++;
					blobs.add(sha256(b));
				}
				long dt = System.nanoTime() - t0;
				System.out.printf("   round %d: reads=%d found=%d distinct_blobs=%d  %.2f ms total, %.1f us/read%n",
				                  round, names.size(), found, blobs.size(), dt / 1e6, dt / 1e3 / names.size());
			}
			System.out.println("   (round 1 vs round 2 相同 ⇒ oracle 侧无内容级复用，每轮全部重新解码)");
		}

		System.out.println("\n--- [3b] Nest member reads inside one transform ---");
		for (Case c : cases) {
			if (!c.dotName().endsWith(".Outer")) continue;
			CountingLoader loader = new CountingLoader(c.v2Classes());
			Class<?> host = Class.forName(c.dotName(), true, loader);
			RemoldAgent.bytecodeCache.put(c.internalName(), c.v2());
			ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(c.v1(), c.v2());
			loader.resetCounts();
			InitFix.transform(host, c.v2(), diff);
			nestReads = loader.reads();
			System.out.printf("   %s: nest members defined=%d, resource reads=%d %s%n",
			                  c.dotName(), c.v2Classes().size() - 1, loader.reads(), loader.readCounts());
		}
	}

	// ------------------------------------------------------------------ [4] Reinit gate

	/**
	 * Reinit 作用域门：子类数量 0 / 4 / 32 下的成本曲线 + 无注解字段时的空转成本。
	 *
	 * <p>"空转"是生产里的常态（绝大多数类没有 {@code @RemoldReinit} 字段），所以先量它是不是真的免费。</p>
	 */
	private static void measureReinitScope(Case baseCase) throws Exception {
		System.out.println("\n--- [4] ReinitScopeGate (unsafeInheritedReinitFields) ---");
		ClassNode baseNode = new ClassNode();
		new ClassReader(baseCase.v2()).accept(baseNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		Set<String> reinit = Set.of("v");

		long[] empty = new long[200];
		Class<?> baseHost = define(baseCase);
		for (int i = 0; i < empty.length; i++) {
			long t0 = System.nanoTime();
			Map<String, String> r = InitFix.unsafeInheritedReinitFields(baseHost, baseNode, Set.of());
			empty[i] = System.nanoTime() - t0;
			if (!r.isEmpty()) throw new IllegalStateException("empty reinit set must short-circuit");
		}
		System.out.printf("   no @RemoldReinit fields (fast path): %s%n", describe(empty));

		// 注意：直接调 InitFix.unsafeInheritedReinitFields 时，子类要么没被层级树登记、要么字节不在
		// bytecodeCache 里，门会走"没有已知子类"的早退路径——那条路径的成本恒为常数，测不出真实工作量。
		// 所以门本身按**纯函数**测：自己给出 subtypes 与 subtypeBytes，量真正吃亏的那段。
		for (int subs : new int[]{1, 4, 32}) {
			Case family = subtypeFamily(subs);
			Map<String, byte[]> subtypeBytes = new LinkedHashMap<>();
			for (Map.Entry<String, byte[]> e : family.v2Classes().entrySet()) {
				if (!e.getKey().equals("Probe.Base")) subtypeBytes.put(e.getKey(), e.getValue());
			}
			long[] nanos = new long[40];
			int rejected = 0;
			Map<String, String> last = Map.of();
			for (int i = 0; i < nanos.length; i++) {
				long t0 = System.nanoTime();
				last = ReinitScopeGate.unsafeInheritedFields(baseNode, reinit, subtypeBytes.keySet(), subtypeBytes);
				nanos[i] = System.nanoTime() - t0;
				if (!last.isEmpty()) rejected++;
				gateRejected = Math.max(gateRejected, rejected);
			}
			System.out.printf("   pure gate, subtypes=%-3d %s  rejected=%d/%d %s%n",
			                  subs, describe(nanos), rejected, nanos.length,
			                  last.isEmpty() ? "" : last.keySet());
		}
	}

	// ------------------------------------------------------------------ fixtures

	private static Case eagerCase() {
		Map<String, String> v1 = Map.of("Probe.Eager", EAGER_V1);
		Map<String, String> v2 = Map.of("Probe.Eager", EAGER_V2);
		return caseOf("Probe.Eager", v1, v2);
	}

	private static Case paramCase() {
		Map<String, String> v1 = Map.of("Probe.Param", PARAM_V1);
		Map<String, String> v2 = Map.of("Probe.Param", PARAM_V2);
		return caseOf("Probe.Param", v1, v2);
	}

	private static Case outerCase() {
		Map<String, String> v1 = Map.of("Probe.Outer", OUTER_V1);
		Map<String, String> v2 = Map.of("Probe.Outer", OUTER_V2);
		return caseOf("Probe.Outer", v1, v2);
	}

	/**
	 * 更接近真实工程的夹具：{@code methods} 个各有分支的方法 + 一个内部类。
	 *
	 * <p>为什么需要它：三个小夹具的 transform 只花 0.8 ms，JFR 采样几乎采不到 InitFix 帧；
	 * 而真实工程的类动辄几十上百个方法，成本分布完全不同。Big 夹具的作用是让"占比"这件事可测。</p>
	 */
	private static Case bigCase(int methods) {
		String v1 = bigSource(methods, false);
		String v2 = bigSource(methods, true);
		return caseOf("Probe.Big", Map.of("Probe.Big", v1), Map.of("Probe.Big", v2));
	}

	private static String bigSource(int methods, boolean withAdded) {
		StringBuilder sb = new StringBuilder();
		sb.append("package Probe;\n");
		sb.append("public class Big {\n");
		sb.append("\tprivate int seed;\n");
		sb.append("\tprivate final int[] table = new int[16];\n");
		if (withAdded) {
			sb.append("\tprivate final int added = seed + 3;\n");
			sb.append("\tprivate final java.util.List<String> names = java.util.List.of(\"a\", \"b\");\n");
		}
		sb.append("\tpublic Big(int seed) { this.seed = seed; for (int i = 0; i < table.length; i++) table[i] = i + seed; }\n");
		for (int i = 0; i < methods; i++) {
			sb.append("\tpublic int m").append(i).append("(int x) {\n");
			sb.append("\t\tint acc = seed + x;\n");
			sb.append("\t\tfor (int k = 0; k < 4; k++) { if ((k & 1) == 0) acc += table[k] + ").append(i).append("; else acc -= k; }\n");
			sb.append("\t\treturn acc > 0 ? acc : -acc;\n");
			sb.append("\t}\n");
		}
		sb.append("\tpublic class Inner { public int read() { return seed + 1; } }\n");
		sb.append("}\n");
		return sb.toString();
	}

	private static Case baseCase() {
		Map<String, String> v1 = Map.of("Probe.Base", BASE_V1);
		Map<String, String> v2 = Map.of("Probe.Base", BASE_V2);
		return caseOf("Probe.Base", v1, v2);
	}

	/** 一个父类 + K 个子类；每个子类的 {@code <init>} 都写父类那个 {@code @RemoldReinit} 字段。 */
	private static Case subtypeFamily(int subs) {
		Map<String, String> v1 = new LinkedHashMap<>();
		Map<String, String> v2 = new LinkedHashMap<>();
		v1.put("Probe.Base", BASE_V1);
		v2.put("Probe.Base", BASE_V2);
		for (int i = 0; i < subs; i++) {
			String name = "Probe.Sub" + i;
			String body = """
				package Probe;
				public class Sub%d extends Base {
					public Sub%d(int seed) { super(); this.v = seed + %d; }
				}
				""".formatted(i, i, i + 10);
			v1.put(name, body);
			v2.put(name, body);
		}
		Map<String, byte[]> v2Classes = compile(v2);
		return new Case("Probe.Base", compile(v1).get("Probe.Base"), v2Classes.get("Probe.Base"), v2Classes);
	}

	private static Case caseOf(String dotName, Map<String, String> v1, Map<String, String> v2) {
		Map<String, byte[]> c1 = compile(v1);
		Map<String, byte[]> c2 = compile(v2);
		byte[] a = c1.get(dotName);
		byte[] b = c2.get(dotName);
		if (a == null || b == null) throw new IllegalStateException("compile failed for " + dotName);
		return new Case(dotName, a, b, c2);
	}

	private static Class<?> define(Case c) throws Exception {
		return Class.forName(c.dotName(), true, new ByteLoader(c.v2Classes()));
	}

	// ------------------------------------------------------------------ helpers

	private static String describe(long[] nanos) {
		long[] sorted = nanos.clone();
		Arrays.sort(sorted);
		long sum = 0;
		for (long v : sorted) sum += v;
		return String.format("min=%.3fms median=%.3fms max=%.3fms mean=%.3fms n=%d",
		                     sorted[0] / 1e6, sorted[sorted.length / 2] / 1e6,
		                     sorted[sorted.length - 1] / 1e6, sum / 1e6 / sorted.length, sorted.length);
	}

	private static String sha256(byte[] bytes) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
		StringBuilder sb = new StringBuilder(digest.length * 2);
		for (byte b : digest) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	/** 只从内存字节码 defineClass 的加载器；资源流也要能读到 Nest 成员。 */
	static final class ByteLoader extends ClassLoader {
		private final Map<String, byte[]> classes;

		ByteLoader(Map<String, byte[]> classes) {
			super(InitFixScanProbe.class.getClassLoader());
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

	/** 统计资源读取次数的加载器（[3b] 用）。 */
	static final class CountingLoader extends ClassLoader {
		private final Map<String, byte[]> classes;
		private final Map<String, Integer> counts = new LinkedHashMap<>();

		CountingLoader(Map<String, byte[]> classes) {
			super(InitFixScanProbe.class.getClassLoader());
			this.classes = classes;
		}

		void resetCounts() { counts.clear(); }

		int reads() { return counts.values().stream().mapToInt(Integer::intValue).sum(); }

		Map<String, Integer> readCounts() { return counts; }

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
			if (bytes == null) return null;
			counts.merge(dot, 1, Integer::sum);
			return new java.io.ByteArrayInputStream(bytes);
		}
	}

	/** 收集工程里已编译的 class 文件（有上限，探针只关心量级与分布）。 */
	private static List<byte[]> collectProjectClasses(Path root, int limit) throws IOException {
		// Gradle 把产物放在各子工程的 build/classes 下，根 build/ 通常只有 agent-env 之类的杂项，
		// 所以按子工程逐个找（core / tests / plugin-* / api / panama）。
		List<Path> bases = new ArrayList<>();
		for (String sub : List.of("core", "tests", "api", "panama", "plugin-arc", "plugin-fabric",
		                          "plugin-forge", "plugin-mixin", "gradle-plugin")) {
			Path p = root.resolve(sub).resolve("build").resolve("classes");
			if (Files.isDirectory(p)) bases.add(p);
		}
		if (bases.isEmpty()) return List.of();
		List<byte[]> out = new ArrayList<>();
		try (Stream<Path> stream = bases.stream().flatMap(p -> {
			try {
				return Files.walk(p);
			} catch (IOException e) {
				return Stream.empty();
			}
		})) {
			stream.filter(p -> p.toString().endsWith(".class"))
			      .filter(p -> !p.toString().contains("/test-results/"))
			      .sorted(Comparator.comparingLong(p -> {
				      try { return Files.size(p); } catch (IOException e) { return 0L; }
			      }))
			      .limit(limit)
			      .forEach(p -> {
				      try { out.add(Files.readAllBytes(p)); } catch (IOException ignored) { }
			      });
		}
		return out;
	}

	/** 内存 javac（与 {@code InitFixOracle} 同一套路，但自成一体，不依赖 oracle 的静态状态）。 */
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
		if (!ok) {
			throw new IllegalStateException("fixture compile failed: " + diagnostics.getDiagnostics());
		}
		return out;
	}
}
