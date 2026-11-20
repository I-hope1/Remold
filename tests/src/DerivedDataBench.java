import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import remold.LambdaAligner;
import remold.ref.RemovedMemberReaders;
import remold.vcs.DerivedDataCache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * PR2「派生数据缓存」的规模量化基准：先量出对齐侧与读者侧各自的真实成本，
 * 再据此决定缓存粒度（读数写进 {@code docs/vcs/01-bytecode-changeset-vcs.md}，本类不进门禁）。
 *
 * <p>唯一的断言是"缓存开/关的产物必须相等"（对齐侧逐字节、读者侧类型世界+引用集合），
 * 那是缓存正确性的必要条件；真正的门禁用例在 JUnit（{@code DerivedDataEquivalenceTest}）。</p>
 *
 * <p><b>口径</b>：同一 JVM、同一语料、同一轮次；开/关两档<b>逐轮交替</b>测量（不是"先测完一档再测另一档"），
 * 避免 JIT/GC 暖机把差异算到缓存头上。耗时一律报 min/median/max。</p>
 *
 * <p>跑法：{@code ./gradlew :tests:derivedDataBench}；
 * 预算对照：{@code ./gradlew :tests:derivedDataBench -Premold.vcs.derived_cache_entries=4096}。</p>
 */
public final class DerivedDataBench {

	private static final int REPS = 3;
	private static final int WARM = 1;
	/** 模拟"一批改了 k 个类"时的 k。 */
	private static final int BATCH = 8;
	/** 每个生成类的 lambda 数。 */
	private static final int LAMBDAS = 120;

	private static int mismatches;

	public static void main(String[] args) throws Exception {
		System.out.println("=== Derived data sizing bench (PR2) ===");
		System.out.printf("   java.version=%s  entries_cap=%d  bytes_cap=%.0fKiB  corpus_batch=%d  lambdas/class=%d%n",
		                  System.getProperty("java.version"), DerivedDataCache.maxEntries(),
		                  DerivedDataCache.maxBytes() / 1024.0, BATCH, LAMBDAS);
		Path root = Path.of(System.getProperty("remold.projectRoot", System.getProperty("user.dir")));

		List<byte[]> corpus = lambdaCorpus(root);
		if (corpus.isEmpty()) {
			System.out.println("[SKIP] no lambda-bearing classes under " + root);
			return;
		}
		long bytes = 0;
		for (byte[] b : corpus) bytes += b.length;
		System.out.printf("   project lambda corpus=%d classes, %.2f MiB, avg=%.1f KiB%n",
		                  corpus.size(), bytes / 1024.0 / 1024.0, bytes / 1024.0 / corpus.size());

		mode(false);
		perClassAlign(corpus);
		wholeCorpusPass(corpus, "cache off");
		consumerDataflowShare(corpus);

		alignCacheOnOff(corpus);
		crossRoundSteadyState(corpus);
		readerFactsCorpus(root);
		report();
	}

	// ------------------------------------------------------------------------------------------------
	// A. 对齐侧：现状成本
	// ------------------------------------------------------------------------------------------------

	/**
	 * 每个类单独计时：{@code align(bytes, bytes)} 走完整管线（扫描 → 匹配 → 复活 → 发布），
	 * 只有"字节完全相同"这一件事与真实轮次不同（真实轮次 old/new 不同，成本只会更高）。
	 */
	private static void perClassAlign(List<byte[]> corpus) {
		System.out.println("\n--- [1] per-class align() cost (self-align, derived_cache=off) ---");
		long[] perClass = new long[corpus.size()];
		int n = 0;
		for (byte[] b : corpus) {
			DerivedDataCache.reset();
			for (int w = 0; w < WARM; w++) LambdaAligner.align(b, b);
			long best = Long.MAX_VALUE;
			for (int r = 0; r < REPS; r++) {
				DerivedDataCache.reset();
				long t0 = System.nanoTime();
				LambdaAligner.align(b, b);
				long dt = System.nanoTime() - t0;
				if (dt < best) best = dt;
			}
			perClass[n++] = best;
		}
		long[] v = Arrays.copyOf(perClass, n);
		Arrays.sort(v);
		long total = 0;
		for (long x : perClass) total += x;
		System.out.printf("   n=%d  min=%.2fms  median=%.2fms  max=%.2fms  total=%.1fms%n",
		                  n, v[0] / 1e6, v[v.length / 2] / 1e6, v[v.length - 1] / 1e6, total / 1e6);
		System.out.printf("   p90=%.2fms  p99=%.2fms%n",
		                  v[(int) Math.min(v.length - 1L, Math.round(v.length * 0.90))] / 1e6,
		                  v[(int) Math.min(v.length - 1L, Math.round(v.length * 0.99))] / 1e6);
		double median = v[v.length / 2] / 1e6;
		System.out.printf("   projection (median x k changed classes/batch): k=1 %.2fms  k=5 %.2fms  k=20 %.2fms%n",
		                  median, median * 5, median * 20);
	}

	/** 整个语料连做三轮（每轮从空缓存开始）：给"每轮都要重新派生一遍"的稳态成本一个读数。 */
	private static void wholeCorpusPass(List<byte[]> corpus, String label) {
		System.out.println("\n--- [2] whole-corpus pass, " + label + " ---");
		for (int round = 0; round < 3; round++) {
			DerivedDataCache.reset();
			long t0 = System.nanoTime();
			for (byte[] b : corpus) LambdaAligner.align(b, b);
			System.out.printf("   round %d: %.2fms%n", round + 1, (System.nanoTime() - t0) / 1e6);
		}
	}

	/**
	 * 消费者数据流（{@code findConsumers} 里的 ASM {@code Analyzer}）占多少。
	 *
	 * <p>用既有的设计级变异开关 {@code ACTIVE_MUTATION="4"}（{@code findConsumers} 恒返回空映射）
	 * 做<b>消融</b>：同一 JVM、同一语料、交替跑两档。注意它同时改变了配对结果（少了消费者证据 →
	 * 配对变少），所以差值是"消费者数据流的<b>量级上界</b>"，不是纯耗时项。</p>
	 */
	private static void consumerDataflowShare(List<byte[]> corpus) {
		System.out.println("\n--- [3] consumer dataflow share: ablation via ACTIVE_MUTATION=4 ---");
		long[] with = new long[3];
		long[] without = new long[3];
		for (int round = 0; round < with.length; round++) {
			String saved = LambdaAligner.ACTIVE_MUTATION;
			try {
				LambdaAligner.ACTIVE_MUTATION = saved;
				DerivedDataCache.reset();
				long t0 = System.nanoTime();
				for (byte[] b : corpus) LambdaAligner.align(b, b);
				with[round] = System.nanoTime() - t0;

				LambdaAligner.ACTIVE_MUTATION = "4";
				DerivedDataCache.reset();
				t0 = System.nanoTime();
				for (byte[] b : corpus) LambdaAligner.align(b, b);
				without[round] = System.nanoTime() - t0;
			} finally {
				LambdaAligner.ACTIVE_MUTATION = saved;
			}
			System.out.printf("   round %d: with=%.1fms  without=%.1fms%n",
			                  round + 1, with[round] / 1e6, without[round] / 1e6);
		}
		double wm = medianMs(with);
		double om = medianMs(without);
		System.out.printf("   median: with=%.1fms  without=%.1fms  delta=%.1fms (%.0f%% of with)%n",
		                  wm, om, wm - om, 100.0 * (wm - om) / Math.max(1e-9, wm));
	}

	// ------------------------------------------------------------------------------------------------
	// B. 对齐侧：缓存开/关
	// ------------------------------------------------------------------------------------------------

	/**
	 * 缓存开/关对照（整语料 self-align）。
	 *
	 * <p>开档只在第 1 轮前清空缓存：之后各轮保留上一轮填充的条目。这是"全部命中"的<b>上界</b>；
	 * 真实批次的形态是"旧侧命中、新侧未命中"，见 {@link #crossRoundSteadyState()}。</p>
	 */
	private static void alignCacheOnOff(List<byte[]> corpus) {
		System.out.println("\n--- [4] align derived_cache on/off (self-align, open loop) ---");
		long[] off = new long[REPS];
		long[] on = new long[REPS];
		boolean warmed = false;
		DerivedDataCache.reset();
		for (int round = 0; round < REPS; round++) {
			mode(false);
			long t0 = System.nanoTime();
			for (byte[] b : corpus) LambdaAligner.align(b, b);
			off[round] = System.nanoTime() - t0;

			mode(true);
			if (!warmed) {
				DerivedDataCache.reset();
				warmed = true;
			}
			t0 = System.nanoTime();
			for (byte[] b : corpus) LambdaAligner.align(b, b);
			on[round] = System.nanoTime() - t0;
			long[] stats = DerivedDataCache.stats();
			System.out.printf("   round %d: off=%.1fms  on=%.1fms  (hits=%d misses=%d evictions=%d entries=%d bytes=%.0fKiB)%n",
			                  round + 1, off[round] / 1e6, on[round] / 1e6,
			                  stats[0], stats[1], stats[2], stats[3], stats[4] / 1024.0);
		}
		long[] steady = Arrays.copyOfRange(on, 1, on.length);
		System.out.printf("   median: off=%.1fms  on(cold)=%.1fms  on(warm)=%.1fms  warm ratio=%.2fx%n",
		                  medianMs(off), on[0] / 1e6, medianMs(steady),
		                  medianMs(off) / Math.max(1e-9, medianMs(steady)));
	}

	/**
	 * 真实批次形态的稳态：一轮里旧侧是上一轮的新侧（命中），新侧是全新字节（未命中）。
	 *
	 * <p>语料是真实工程类（带 lambda 的那些），版本链由 {@link #resalt} 生成：同一套方法体加一条
	 * 不同名字的空方法，得到"同源但字节不同"的版本。两档对照：
	 * <ul>
	 *   <li>{@code batch=8}：典型热更批次规模（一批改几个类）；</li>
	 *   <li>{@code batch=all}：把整个 lambda 语料当一批，条目数会超出条目预算，用来观察淘汰的影响。</li>
	 * </ul>
	 */
	private static void crossRoundSteadyState(List<byte[]> corpus) {
		for (int batch : new int[] { BATCH, 0 }) {
			int k = batch == 0 ? corpus.size() : Math.min(batch, corpus.size());
			String label = batch == 0 ? "all " + k + " classes (over-budget)" : "k=" + k + " classes/batch";
			System.out.println("\n--- [5] cross-round steady state: " + label + " ---");
			int rounds = 5;
			List<List<byte[]>> gen = new ArrayList<>();
			for (int c = 0; c < k; c++) {
				List<byte[]> vs = new ArrayList<>();
				for (int v = 0; v < rounds + 1; v++) {
					vs.add(v == 0 ? corpus.get(c) : resalt(corpus.get(c), v));
				}
				gen.add(vs);
			}

			long[] off = new long[rounds];
			long[] on = new long[rounds];
			DerivedDataCache.reset();
			boolean warmed = false;
			List<byte[]> refOut = new ArrayList<>();
			List<byte[]> onOut = new ArrayList<>();
			for (int round = 0; round < rounds; round++) {
				mode(false);
				long t0 = System.nanoTime();
				for (int c = 0; c < k; c++) {
					byte[] out = LambdaAligner.align(gen.get(c).get(round), gen.get(c).get(round + 1));
					if (round == 0) refOut.add(out);
				}
				off[round] = System.nanoTime() - t0;

				mode(true);
				if (!warmed) {
					DerivedDataCache.reset();
					warmed = true;
				}
				t0 = System.nanoTime();
				for (int c = 0; c < k; c++) {
					byte[] out = LambdaAligner.align(gen.get(c).get(round), gen.get(c).get(round + 1));
					if (round == 0) onOut.add(out);
				}
				on[round] = System.nanoTime() - t0;
				long[] stats = DerivedDataCache.stats();
				System.out.printf("   round %d: off=%.2fms  on=%.2fms  (hits=%d misses=%d evictions=%d entries=%d)%n",
				                  round + 1, off[round] / 1e6, on[round] / 1e6,
				                  stats[0], stats[1], stats[2], stats[3]);
			}
			long[] steadyOff = Arrays.copyOfRange(off, 1, off.length);
			long[] steadyOn = Arrays.copyOfRange(on, 1, on.length);
			System.out.printf("   median: off(warm)=%.2fms  on(cold)=%.2fms  on(warm)=%.2fms  warm ratio=%.2fx%n",
			                  medianMs(steadyOff), on[0] / 1e6, medianMs(steadyOn),
			                  medianMs(steadyOff) / Math.max(1e-9, medianMs(steadyOn)));
			System.out.printf("   range (warm rounds): off=[%.2f..%.2f]ms  on=[%.2f..%.2f]ms%n",
			                  minMs(steadyOff), maxMs(steadyOff), minMs(steadyOn), maxMs(steadyOn));
			for (int c = 0; c < refOut.size(); c++) {
				if (!Arrays.equals(refOut.get(c), onOut.get(c))) mismatches++;
			}
		}
		System.out.printf("   byte-equality off vs on: %s%n", mismatches == 0 ? "equal" : "DIFFERENT");
	}

	/** 给同一套 lambda 的类文件加一条不同名字的空方法，得到"同源但字节不同"的版本。 */
	private static byte[] resalt(byte[] base, int salt) {
		ClassReader cr = new ClassReader(base);
		ClassWriter cw = new ClassWriter(0);
		cr.accept(cw, 0);
		var mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "remoldBenchSalt" + salt, "()V", null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	// ------------------------------------------------------------------------------------------------
	// C. 读者侧（类型世界 + 常量池引用）
	// ------------------------------------------------------------------------------------------------

	/**
	 * 读者扫描侧的派生量：{@code universe()}/{@code refs()} 的缓存开/关对照。
	 *
	 * <p>语料是整个工程类语料（不只带 lambda 的类）：读者扫描的范围就是它。模式按真实接线抄：
	 * 每轮先建类型世界，再判定一次（判定内部对每个类 {@code refs()}）。</p>
	 */
	private static void readerFactsCorpus(Path root) throws Exception {
		System.out.println("\n--- [6] reader-side derived facts (type world + refs), cache on/off ---");
		Map<String, byte[]> corpus = projectClasses(root);
		if (corpus.isEmpty()) {
			System.out.println("   [SKIP] no project classes");
			return;
		}
		long bytes = 0;
		for (byte[] b : corpus.values()) bytes += b.length;
		System.out.printf("   corpus=%d classes, %.2f MiB%n", corpus.size(), bytes / 1024.0 / 1024.0);

		long[] off = new long[REPS];
		long[] on = new long[REPS];
		boolean warmed = false;
		DerivedDataCache.reset();
		for (int round = 0; round < REPS; round++) {
			mode(false);
			long t0 = System.nanoTime();
			scanOnce(corpus);
			off[round] = System.nanoTime() - t0;

			mode(true);
			if (!warmed) {
				DerivedDataCache.reset();
				warmed = true;
			}
			t0 = System.nanoTime();
			scanOnce(corpus);
			on[round] = System.nanoTime() - t0;
			long[] stats = DerivedDataCache.stats();
			System.out.printf("   round %d: off=%.2fms  on=%.2fms  (hits=%d misses=%d evictions=%d entries=%d/%d bytes=%.0fKiB/%.0fKiB)%n",
			                  round + 1, off[round] / 1e6, on[round] / 1e6,
			                  stats[0], stats[1], stats[2], stats[3], DerivedDataCache.maxEntries(),
			                  stats[4] / 1024.0, DerivedDataCache.maxBytes() / 1024.0);
		}
		long[] steady = Arrays.copyOfRange(on, 1, on.length);
		System.out.printf("   median: off=%.2fms  on(cold)=%.2fms  on(warm)=%.2fms  warm ratio=%.2fx%n",
		                  medianMs(off), on[0] / 1e6, medianMs(steady),
		                  medianMs(off) / Math.max(1e-9, medianMs(steady)));

		mode(false);
		DerivedDataCache.reset();
		Map<String, RemovedMemberReaders.TypeInfo> worldOff = RemovedMemberReaders.universe(corpus);
		Map<String, Set<RemovedMemberReaders.Ref>> refsOff = refsOf(corpus);
		mode(true);
		DerivedDataCache.reset();
		Map<String, RemovedMemberReaders.TypeInfo> worldOn = RemovedMemberReaders.universe(corpus);
		Map<String, Set<RemovedMemberReaders.Ref>> refsOn = refsOf(corpus);
		boolean same = worldOff.equals(worldOn) && refsOff.equals(refsOn);
		if (!same) mismatches++;
		System.out.printf("   equality off vs on: %s%n", same ? "equal" : "DIFFERENT");
	}

	private static void scanOnce(Map<String, byte[]> corpus) {
		Map<String, RemovedMemberReaders.TypeInfo> world = RemovedMemberReaders.universe(corpus);
		RemovedMemberReaders.find(world, world, corpus,
		                          List.of(new RemovedMemberReaders.RemovedMember("p/None", "nope", "I", true, false)));
	}

	private static Map<String, Set<RemovedMemberReaders.Ref>> refsOf(Map<String, byte[]> corpus) {
		Map<String, Set<RemovedMemberReaders.Ref>> out = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : corpus.entrySet()) {
			out.put(e.getKey(), Set.copyOf(RemovedMemberReaders.refs(e.getValue())));
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------------------------------------

	private static void mode(boolean cacheEnabled) {
		System.setProperty("remold.vcs.derived_cache", Boolean.toString(cacheEnabled));
	}

	private static double minMs(long[] nanos) {
		return Arrays.stream(nanos).min().orElse(0L) / 1e6;
	}

	private static double maxMs(long[] nanos) {
		return Arrays.stream(nanos).max().orElse(0L) / 1e6;
	}

	private static double medianMs(long[] nanos) {
		long[] s = nanos.clone();
		Arrays.sort(s);
		return s[s.length / 2] / 1e6;
	}

	private static void report() {
		System.out.println("\n=== DERIVED DATA BENCH: "
		                   + (mismatches == 0 ? "outputs consistent" : "MISMATCHES=" + mismatches) + " ===");
		if (mismatches > 0) System.exit(1);
	}

	/** 语料：各子模块 {@code build/classes} 下带 lambda 标记的类（{@code hasPotentialLambdas} 为准）。 */
	private static List<byte[]> lambdaCorpus(Path root) throws Exception {
		List<byte[]> out = new ArrayList<>();
		for (byte[] b : projectClasses(root).values()) {
			if (LambdaAligner.hasPotentialLambdas(b)) out.add(b);
		}
		return out;
	}

	private static Map<String, byte[]> projectClasses(Path root) throws Exception {
		Map<String, byte[]> out = new LinkedHashMap<>();
		if (!Files.isDirectory(root)) return out;
		try (var modules = Files.newDirectoryStream(root)) {
			for (Path module : modules) {
				Path buildClasses = module.resolve("build").resolve("classes");
				if (!Files.isDirectory(buildClasses)) continue;
				try (Stream<Path> walk = Files.walk(buildClasses)) {
					for (Path p : walk.filter(Files::isRegularFile).toList()) {
						if (!p.toString().endsWith(".class")) continue;
						String rel = buildClasses.relativize(p).toString().replace('\\', '/');
						// Kotlin 夹具的 v1/v2 是两个互相替换的版本，同名类不能进同一份语料。
						if (rel.contains("/v1/") || rel.contains("/v2/")) continue;
						try {
							out.put(rel, Files.readAllBytes(p));
						} catch (Exception | LinkageError ignored) {
							// 读不了的类不进语料：本基准量的是解析成本
						}
					}
				}
			}
		}
		return out;
	}
}
