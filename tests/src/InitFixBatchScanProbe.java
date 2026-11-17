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

	private static final List<String> infoLog = java.util.Collections.synchronizedList(new ArrayList<>());
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

		measureLiveInstanceScope();
		measureBatchCost(1);
		measureBatchCost(4);
		measureBatchCost(8);
		measureBatchCost(16);
		measureBatchCost(64);
		measureLivenessProbe();

		System.out.println("\n=== INITFIX BATCH SCAN PROBE: "
			+ (failures == 0 ? "self-checks passed" : failures + " CHECK(S) FAILED") + " ===");
		if (failures != 0) System.exit(1);
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
			check("K=" + k + ": batch-on must perform exactly one heap walk per round", allEqual(walksOn, 1));
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
				allEqual(walksOn, 1) && allEqual(walksOff, 1));
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
		public class BatchScope {
			int plain = 1;
			@remold.annotation.RemoldReinit(scope = remold.annotation.RemoldReinit.Scope.SELF)
			int self = 1;
			@remold.annotation.RemoldReinit(scope = remold.annotation.RemoldReinit.Scope.INCLUDE_SUBCLASSES)
			int incl = 1;
			public BatchScope() { }
		}
		""";

	private static final String SCOPE_V2 = """
		package Probe;
		public class BatchScope {
			int plain = 1;
			@remold.annotation.RemoldReinit(scope = remold.annotation.RemoldReinit.Scope.SELF)
			int self = 2;
			@remold.annotation.RemoldReinit(scope = remold.annotation.RemoldReinit.Scope.INCLUDE_SUBCLASSES)
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
