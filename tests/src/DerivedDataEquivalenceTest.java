import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import remold.LambdaAligner;
import remold.LambdaAligner.AlignmentResult;
import remold.ref.RemovedMemberReaders;
import remold.vcs.DerivedDataCache;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR2 派生数据缓存的<b>等价性</b>门禁：同一输入下，缓存开与缓存关必须给出完全相同的产物。
 *
 * <p>为什么这是缓存的第一道门：按 blob 内容寻址的派生一旦漏了某个输入维度（方法、策略开关、
 * 匿名类字节），产物就会在<b>不抛异常</b>的情况下错位——热更最坏的失败形态正是"静默跑错"。
 * 因此这里比对两件事，缺一不可：</p>
 * <ol>
 *   <li><b>产物字节</b>逐字节相等（旧 CallSite 最终落到的方法体由它决定）；</li>
 *   <li><b>发布快照</b>（{@link AlignmentResult} 的全部字段）相等（自造名、方法映射、匿名类哈希、
 *       配对统计都是下游判据的输入）。</li>
 * </ol>
 *
 * <p>同时断言"缓存确实被用上了"（命中数 &gt; 0）：否则等价性可以靠"缓存从未生效"白白成立——
 * 那是本仓库明令禁止的空转绿。</p>
 *
 * <p>语料取自测试自身的编译产物（真实 javac 输出，含 lambda 合成方法与内部类），
 * 版本链由 {@link #resalt} 生成，因此不需要额外 javac 过程。</p>
 */
public class DerivedDataEquivalenceTest {

	/** 候选语料：测试自身编译出的类；只保留真正含 lambda 标记的那些（与生产判定同源）。 */
	private static final String[] CANDIDATES = {
		"ProbeBench.class",
		"ConcurrencyCheck.class",
		"ReaderScanProbe.class",
		"DerivedDataBench.class",
		"TwoPhaseProbe.class",
		"CacheWarmingProbe.class",
		"VcsPackProbe.class",
		"OffsetStabilityProbe.class",
	};

	private String savedProperty;
	private boolean savedReverse;

	@BeforeEach
	void saveGlobals() {
		savedProperty = System.getProperty("remold.vcs.derived_cache");
		savedReverse = LambdaAligner.TEST_REVERSE_GROUP_ORDER;
		DerivedDataCache.reset();
	}

	@AfterEach
	void restoreGlobals() {
		if (savedProperty == null) System.clearProperty("remold.vcs.derived_cache");
		else System.setProperty("remold.vcs.derived_cache", savedProperty);
		LambdaAligner.TEST_REVERSE_GROUP_ORDER = savedReverse;
		DerivedDataCache.reset();
	}

	/** 缓存开/关：产物字节与发布快照必须全部相等，且缓存必须真的命中过。 */
	@Test
	void cacheOnOffProduceIdenticalBytesAndResults() throws Exception {
		List<byte[]> corpus = corpus();
		Run off = run(corpus, false);
		Run on = run(corpus, true);

		assertEquals(off.describe(), on.describe(), "cache on/off must publish identical alignment results");
		for (int i = 0; i < off.bytes.size(); i++) {
			assertTrue(Arrays.equals(off.bytes.get(i), on.bytes.get(i)),
			           "cache on/off must produce byte-identical output for " + off.labels.get(i));
		}
		assertTrue(on.hits > 0, "cache must actually hit during the on run (otherwise this test proves nothing)");
		assertTrue(on.misses > 0, "first round must miss (otherwise the corpus never entered the cache)");
		assertEquals(0L, off.hits, "cache-off run must not read the cache");
		assertEquals(0L, off.entries, "cache-off run must not populate the cache");
	}

	/**
	 * 换序遍历不得改变产物，且开/关两档在换序下仍然相等。
	 *
	 * <p>{@link LambdaAligner#TEST_REVERSE_GROUP_ORDER} 是"结果不应依赖遍历顺序"的自检开关；
	 * 缓存把跨轮状态引进来后，这条性质必须重新确认一遍。</p>
	 */
	@Test
	void reverseGroupOrderKeepsCacheEquivalence() throws Exception {
		List<byte[]> corpus = corpus();
		LambdaAligner.TEST_REVERSE_GROUP_ORDER = true;
		try {
			Run off = run(corpus, false);
			Run on = run(corpus, true);
			assertEquals(off.describe(), on.describe(),
			             "reverse group order: cache on/off must publish identical results");
			for (int i = 0; i < off.bytes.size(); i++) {
				assertTrue(Arrays.equals(off.bytes.get(i), on.bytes.get(i)),
				           "reverse group order: cache on/off bytes differ for " + off.labels.get(i));
			}
			assertTrue(on.hits > 0, "reverse group order: cache must still hit");
		} finally {
			LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		}
	}

	/**
	 * 缓存键的维度契约：对齐侧的消费者数据流必须按 <b>(blob, 方法 name+desc)</b> 分别成条目，
	 * 且"没有消费者位点的方法"不得占条目。
	 *
	 * <p>为什么单独立一条：端到端"开/关输出相等"只能证明<b>用到的</b>那条派生是对的；如果键里漏掉
	 * 方法维度（同一份 blob 的所有方法共用一张标记表），只要该语料上消费者证据恰好不改写产物，
	 * 端到端断言就会**假绿**。本条直接按方法去查条目，把键的形状变成可断言读数。</p>
	 *
	 * <p>键必须带<b>描述符</b>：同名重载在同一个类里是合法形态，只按名字做键会互相覆盖。
	 * 这里用"只带名字的键必须查不到条目"来钉住这一点。</p>
	 */
	@Test
	void consumerCacheIsKeyedPerMethodAndBlob() throws Exception {
		byte[] oldBytes = pickFixtureWithConsumerSites();
		byte[] newBytes = resalt(oldBytes, 11);
		System.setProperty("remold.vcs.derived_cache", "true");
		DerivedDataCache.reset();
		LambdaAligner.align(oldBytes, newBytes, resolver(), resolver());

		String blobKey = DerivedDataCache.blobKey(newBytes);
		assertNotNull(blobKey, "cache must be enabled for this test");

		ClassNode cn = new ClassNode();
		new ClassReader(newBytes).accept(cn, ClassReader.SKIP_FRAMES);
		int withSite = 0;
		int withoutSite = 0;
		boolean nonEmpty = false;
		for (MethodNode mn : cn.methods) {
			String kind = LambdaAligner.CONSUMER_CACHE_KIND + '/' + mn.name + mn.desc;
			List<?> cached = DerivedDataCache.peekByKey(kind, blobKey);
			if (hasConsumerSite(cn.name, mn)) {
				withSite++;
				assertNotNull(cached, "consumer cache must hold a per-method entry for " + mn.name + mn.desc
				                     + " (key dimension 'method' is missing if this is null)");
				assertTrue(cached.size() <= mn.instructions.size(),
				           "cached tag table must not exceed the instruction stream of " + mn.name + mn.desc);
				for (Object tag : cached) {
					if (tag != null) { nonEmpty = true; break; }
				}
				// 名字键必须查不到东西：描述符是键的一部分（同名重载靠它区分）。
				assertNull(DerivedDataCache.peekByKey(LambdaAligner.CONSUMER_CACHE_KIND + '/' + mn.name, blobKey),
				           "cache key must include the descriptor, name-only key must not resolve");
			} else if (mn.instructions != null) {
				withoutSite++;
				assertNull(cached, "method without any consumer site must not occupy a cache entry: "
				                   + mn.name + mn.desc);
			}
		}
		assertTrue(withSite >= 2, "fixture must expose at least 2 methods with consumer sites, got " + withSite);
		assertTrue(withoutSite >= 1, "fixture must contain methods without consumer sites, got " + withoutSite);
		assertTrue(nonEmpty, "fixture must exercise the dataflow path (at least one non-empty tag table)");

		assertNotEquals(blobKey, DerivedDataCache.blobKey(oldBytes), "different bytes must produce different keys");
		assertNull(DerivedDataCache.peekByKey(LambdaAligner.CONSUMER_CACHE_KIND + '/' + cn.methods.get(0).name,
		                                      "not-a-real-blob-key"),
		           "unknown blob key must not resolve to a cached value");
	}

	/** 选一个"至少 2 个方法带消费者位点"的语料类：少于 2 个时键维度断言会退化成单点检查。 */
	private static byte[] pickFixtureWithConsumerSites() throws Exception {
		int best = -1;
		byte[] bestBytes = null;
		for (byte[] b : corpus()) {
			ClassNode cn = new ClassNode();
			new ClassReader(b).accept(cn, ClassReader.SKIP_FRAMES);
			int sites = 0;
			for (MethodNode mn : cn.methods) {
				if (hasConsumerSite(cn.name, mn)) sites++;
			}
			if (sites > best) {
				best = sites;
				bestBytes = b;
			}
		}
		assertNotNull(bestBytes, "corpus must not be empty");
		assertTrue(best >= 2, "no corpus class exposes 2+ consumer sites (best=" + best
		                      + "); the key-dimension assertions need at least two");
		return bestBytes;
	}

	/**
	 * 消费者位点预判必须与读取端逐字对齐：方法体<b>直接调用</b>本类合成方法也算位点。
	 *
	 * <p>为什么单独立一条：语料里的 lambda 基本都是 {@code invokedynamic} 形态，位点预判若只认
	 * {@code invokedynamic} 分支（漏掉"直接 {@code INVOKESTATIC lambda$x$N}"这一半），在真实工程里
	 * 就会对那些方法静默丢掉消费者证据——而这类方法在本仓库语料里恰好没有，端到端断言看不出来。
	 * 这里用定向夹具把这一半钉死。</p>
	 */
	@Test
	void consumerSitePreCheckCoversDirectSyntheticCalls() throws Exception {
		byte[] fixture = directSyntheticCallFixture();
		System.setProperty("remold.vcs.derived_cache", "true");
		DerivedDataCache.reset();
		LambdaAligner.align(fixture, fixture, resolver(), resolver());

		String blobKey = DerivedDataCache.blobKey(fixture);
		assertNotNull(blobKey, "cache must be enabled for this test");
		String direct = LambdaAligner.CONSUMER_CACHE_KIND + "/run()V";
		String plain = LambdaAligner.CONSUMER_CACHE_KIND + "/noop()V";
		assertNotNull(DerivedDataCache.peekByKey(direct, blobKey),
		              "a method that directly calls an internal synthetic method has a consumer site");
		assertNull(DerivedDataCache.peekByKey(plain, blobKey),
		           "a method with no synthetic call site must not occupy a cache entry");
	}

	/**
	 * 定向夹具：{@code run()} 直接 {@code INVOKESTATIC lambda$run$0()}（没有 {@code invokedynamic}），
	 * {@code noop()} 里什么都没有。
	 */
	private static byte[] directSyntheticCallFixture() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		String owner = "gen/DirectSyntheticCall";
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, owner, null, "java/lang/Object", null);

		var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();

		var body = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
		                          "lambda$run$0", "()V", null, null);
		body.visitCode();
		body.visitInsn(Opcodes.RETURN);
		body.visitMaxs(0, 0);
		body.visitEnd();

		var run = cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
		run.visitCode();
		run.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "lambda$run$0", "()V", false);
		run.visitInsn(Opcodes.RETURN);
		run.visitMaxs(0, 0);
		run.visitEnd();

		var noop = cw.visitMethod(Opcodes.ACC_PUBLIC, "noop", "()V", null, null);
		noop.visitCode();
		noop.visitInsn(Opcodes.RETURN);
		noop.visitMaxs(0, 0);
		noop.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * 与 {@code LambdaAligner.hasConsumerSite} 同判据的测试侧副本。
	 *
	 * <p>故意重复实现：测试要独立判定"该方法应不应该有条目"，若直接调产品方法，产品里的判据写错时
	 * 测试会跟着错，等于没测。两处判据必须逐字对齐（读取端只在"本类 lambda 调用位点"上读标记）。</p>
	 */
	private static boolean hasConsumerSite(String owner, MethodNode mn) {
		if (mn.instructions == null) return false;
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof InvokeDynamicInsnNode indy) {
				if (indy.bsmArgs == null || indy.bsmArgs.length < 2) continue;
				if (indy.bsmArgs[1] instanceof Handle h && owner.equals(h.getOwner()) && isSyntheticName(h.getName())) {
					return true;
				}
			} else if (n instanceof MethodInsnNode m) {
				if (owner.equals(m.owner) && isSyntheticName(m.name)) return true;
			}
		}
		return false;
	}

	/**
	 * 与 {@code MethodFingerprinter.isSyntheticName} 同判据的测试侧副本。
	 *
	 * <p>注意产品判据是 {@code contains}（不是 {@code startsWith}）：Kotlin/Scala 的名字形如
	 * {@code foo$lambda$1} / {@code foo$anonfun$1}，前缀不在开头。这里必须逐字对齐，
	 * 否则测试会把"产品认定为位点"的方法判成"无位点"，从而断言错方向。</p>
	 */
	private static boolean isSyntheticName(String name) {
		return name != null && (name.contains("lambda$") || name.contains("$lambda")
		                        || name.contains("$anonfun$") || name.contains("access$"));
	}

	/** 总开关关闭时不得写入任何条目，且产物与开启时相同。 */	/** 总开关关闭时不得写入任何条目，且产物与开启时相同。 */
	@Test
	void disabledSwitchNeitherPopulatesNorChangesOutput() throws Exception {
		List<byte[]> corpus = corpus();
		Run on = run(corpus, true);
		Run off = run(corpus, false);
		assertEquals(on.describe(), off.describe(), "disabled switch must not change results");
		assertEquals(0L, off.entries, "disabled switch must not populate the cache");
		assertEquals(0L, off.hits + off.misses, "disabled switch must not even consult the cache");
	}

	/** 读者侧的类型世界与引用集合：缓存开/关必须逐项相等，且命中过。 */
	@Test
	void readerFactsCacheIsEquivalent() throws Exception {
		Map<String, byte[]> classes = new LinkedHashMap<>();
		for (byte[] b : corpus()) {
			classes.put(new ClassReader(b).getClassName(), b);
		}

		System.setProperty("remold.vcs.derived_cache", "false");
		DerivedDataCache.reset();
		Map<String, RemovedMemberReaders.TypeInfo> worldOff = RemovedMemberReaders.universe(classes);
		Map<String, Set<RemovedMemberReaders.Ref>> refsOff = refs(classes);

		System.setProperty("remold.vcs.derived_cache", "true");
		DerivedDataCache.reset();
		// 接线形态就是"改动前世界 + 改动后世界"两份：同一份 blob 在两份世界里的解析必须命中同一条目。
		Map<String, RemovedMemberReaders.TypeInfo> preOn = RemovedMemberReaders.universe(classes);
		Map<String, RemovedMemberReaders.TypeInfo> postOn = RemovedMemberReaders.universe(classes);
		Map<String, Set<RemovedMemberReaders.Ref>> refsOn = refs(classes);
		long[] stats = DerivedDataCache.stats();

		assertEquals(worldOff, preOn, "reader type world must be identical with the cache on/off");
		assertEquals(preOn, postOn, "pre/post worlds must be identical for the same bytes");
		assertEquals(refsOff, refsOn, "reader refs must be identical with the cache on/off");
		assertEquals(classes.size(), stats[0], "pre/post worlds must reuse one cache entry per class");
		assertEquals(0L, stats[2], "reader-side budget must not evict within a single pass of this corpus");
	}

	// ------------------------------------------------------------------------------------------------
	// harness
	// ------------------------------------------------------------------------------------------------

	/** 一次完整运行（若干轮，跨轮复用）的产物与缓存读数。 */
	private static final class Run {
		final List<byte[]> bytes = new ArrayList<>();
		final List<String> labels = new ArrayList<>();
		final List<String> results = new ArrayList<>();
		long hits;
		long misses;
		long entries;

		String describe() {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < results.size(); i++) {
				sb.append(labels.get(i)).append(" => ").append(results.get(i)).append('\n');
			}
			return sb.toString();
		}
	}

	/**
	 * 跑三轮：{@code align(v0,v1)} → {@code align(v1,v2)} → {@code align(v2,v3)}。
	 *
	 * <p>第二、三轮的旧侧正是上一轮的新侧——这就是缓存要服务的真实形态（旧侧命中、新侧未命中）。
	 * 缓存关闭时每轮都从零派生；开启时只有第一轮是冷启动。</p>
	 */
	private static Run run(List<byte[]> corpus, boolean cacheEnabled) {
		System.setProperty("remold.vcs.derived_cache", Boolean.toString(cacheEnabled));
		DerivedDataCache.reset();
		Function<String, byte[]> resolver = resolver();
		Run run = new Run();
		for (int version = 0; version < 3; version++) {
			for (int c = 0; c < corpus.size(); c++) {
				byte[] oldBytes = version == 0 ? corpus.get(c) : resalt(corpus.get(c), version);
				byte[] newBytes = resalt(corpus.get(c), version + 1);
				byte[] out = LambdaAligner.align(oldBytes, newBytes, resolver, resolver);
				AlignmentResult snapshot = LambdaAligner.LAST_RESULT;
				assertNotNull(snapshot, "align must publish a result snapshot");
				run.bytes.add(out);
				run.labels.add("class#" + c + "/v" + (version + 1));
				run.results.add(fingerprint(snapshot));
			}
		}
		long[] stats = DerivedDataCache.stats();
		run.hits = stats[0];
		run.misses = stats[1];
		run.entries = stats[3];
		return run;
	}

	/** 快照的全字段指纹（map 按 key 排序，避免迭代序差异）。 */
	private static String fingerprint(AlignmentResult r) {
		StringBuilder sb = new StringBuilder();
		sb.append("class=").append(r.alignedClass)
		  .append(" old=").append(r.oldLen).append('/').append(r.oldHash)
		  .append(" new=").append(r.newLen).append('/').append(r.newHash)
		  .append(" bridges=").append(r.bridgeCount)
		  .append(" metaGen=").append(r.metaGeneration)
		  .append(" invented=").append(new TreeMap<>(toMap(r.inventedNames)))
		  .append(" supplied=").append(new TreeMap<>(toMap(r.suppliedInventedNames)))
		  .append(" missingLvt=").append(new TreeMap<>(toMap(r.missingLvtLambdas)))
		  .append(" mapping=").append(r.methodMapping == null ? "null" : new TreeMap<>(r.methodMapping))
		  .append(" oldAnon=").append(r.oldAnonHashes == null ? "null" : new TreeMap<>(r.oldAnonHashes))
		  .append(" newAnon=").append(r.newAnonHashes == null ? "null" : new TreeMap<>(r.newAnonHashes))
		  .append(" stats=").append(r.stats);
		return sb.toString();
	}

	private static Map<String, Boolean> toMap(Set<String> set) {
		Map<String, Boolean> m = new LinkedHashMap<>();
		for (String s : set) m.put(s, Boolean.TRUE);
		return m;
	}

	private static Map<String, Set<RemovedMemberReaders.Ref>> refs(Map<String, byte[]> classes) {
		Map<String, Set<RemovedMemberReaders.Ref>> out = new TreeMap<>();
		for (Map.Entry<String, byte[]> e : classes.entrySet()) {
			out.put(e.getKey(), Set.copyOf(RemovedMemberReaders.refs(e.getValue())));
		}
		return out;
	}

	/** 解析器：把 {@code a/b/C$1} 这类内部名映射到测试 classpath 上的字节，驱动匿名类哈希路径。 */
	private static Function<String, byte[]> resolver() {
		ClassLoader cl = DerivedDataEquivalenceTest.class.getClassLoader();
		return internalName -> {
			try (InputStream in = cl.getResourceAsStream(internalName + ".class")) {
				return in == null ? null : in.readAllBytes();
			} catch (Exception e) {
				return null;
			}
		};
	}

	/**
	 * 语料：测试自身编译产物里真正含 lambda 的类。
	 *
	 * <p>少于 2 个就直接失败：语料意外为空时，"开/关相等"会变成一个什么都没证明的绿。</p>
	 */
	private static List<byte[]> corpus() throws Exception {
		List<byte[]> out = new ArrayList<>();
		ClassLoader cl = DerivedDataEquivalenceTest.class.getClassLoader();
		for (String name : CANDIDATES) {
			try (InputStream in = cl.getResourceAsStream(name)) {
				if (in == null) continue;
				byte[] b = in.readAllBytes();
				if (!LambdaAligner.hasPotentialLambdas(b)) continue;
				out.add(b);
			}
		}
		assertFalse(out.size() < 2, "fixture corpus must contain at least 2 lambda-bearing classes, got " + out.size());
		return out;
	}

	/** 给同一个类文件追加一条不同名字的空方法：得到"同源但字节不同"的版本，用于版本链。 */
	private static byte[] resalt(byte[] base, int salt) {
		ClassReader cr = new ClassReader(base);
		ClassWriter cw = new ClassWriter(0);
		cr.accept(cw, 0);
		var mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "remoldEqSalt" + salt, "()V", null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
