package remold.vcs;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按<b>内容寻址</b>的派生数据缓存（PR2 · Per-Blob Derived Data 第一切片）。
 *
 * <p><b>缓存模型</b>：键 = {@code (kind, SHA-256(blob))}，值 = 对该 blob 的<b>纯派生</b>结果。
 * 与"缓存 {@code align()} 整包结果"不同：这里只缓存单份字节码的不可变派生事实，跨轮复用发生在
 * "同一份字节在这一轮是 old、上一轮是 new"的场合——连续热更里 {@code eff(v2) == preAlign(v2)} 时，
 * 下一轮对 old 侧的重复解析/遍历直接命中。</p>
 *
 * <p><b>为什么不用两个字节数组的哈希做键</b>：{@code align} 的输出还取决于 {@code inventedOldNames}、
 * 外部匿名类字节、策略开关与 {@code LAST_RESULT} 副作用，只用
 * {@code (preAlignHash, oldEffHash)} 会静默错配。内容寻址到单份 blob 则没有这个问题。</p>
 *
 * <p><b>不变量</b>：</p>
 * <ul>
 *   <li>值必须不可变且<b>不得持有</b> {@code Class}/{@code ClassLoader}/{@code ClassNode} 等可变对象；
 *       派生器拿到的可变对象只能在计算期内使用。</li>
 *   <li>有界：按条目数与估算字节双限淘汰（LRU），最坏情况不涨内存。</li>
 *   <li>线程安全：内部单锁；命中路径只做哈希与查表。</li>
 * </ul>
 */
public final class DerivedDataCache {

	/** 默认条目上限（{@code -Dremold.vcs.derived_cache_entries} 可覆盖）。 */
	public static final int MAX_ENTRIES = 16_384;
	/** 默认估算字节上限（{@code -Dremold.vcs.derived_cache_bytes} 可覆盖）。 */
	public static final long MAX_BYTES = 8L * 1024 * 1024;

	/**
	 * 生效的条目上限。
	 *
	 * <p>为什么可覆盖：不同派生种类的<b>工作集</b>差两个数量级——对齐侧按"本轮改动的类 × 方法数"计
	 * （典型批次几百条），读者扫描侧按"整个工程类语料"计（上千条）。实测（{@code DerivedDataBench}，
	 * 读数见 {@code docs/vcs/01-bytecode-changeset-vcs.md} §3.7）：条目上限低于一轮工作集时
	 * 命中率直接掉到 0（LRU 每轮把上一轮的条目全冲掉），缓存反而只剩开销。</p>
	 *
	 * <p>因此默认值取到"<b>字节</b>预算成为真正的内存上限"的量级（每条约 0.5～1.8 KiB），
	 * 条目上限只作防"小值刷屏"的兜底，而不是主预算。真要更紧的内存，把
	 * {@code -Dremold.vcs.derived_cache_bytes} 调小即可。</p>
	 */
	public static int maxEntries() {
		return (int) Math.max(1L, Long.getLong("remold.vcs.derived_cache_entries", MAX_ENTRIES));
	}

	/** 生效的估算字节上限。 */
	public static long maxBytes() {
		return Math.max(1L, Long.getLong("remold.vcs.derived_cache_bytes", MAX_BYTES));
	}

	/** 单条最长保留时间不计；纯内存、无时钟。 */

	/**
	 * 总开关 {@code -Dremold.vcs.derived_cache}（默认 {@code true}）。
	 *
	 * <p><b>每次调用都读系统属性</b>，而不是像普通常量那样在类初始化时固化：测量型探针要在<b>同一个 JVM、
	 * 同一轮次</b>里对照"命中缓存"与"每次重算"两档（两个进程的读数不能直接比）。代价是每次缓存访问多一次
	 * {@code System.getProperty}，相对被缓存的派生量（按 blob 的解析/数据流分析，微秒到毫秒级）可以忽略。</p>
	 *
	 * <p>关闭后所有 {@code get} 都退化为"直接调用派生器、不读不写缓存"，即输出与开启时完全一致。</p>
	 */
	public static boolean enabled() {
		return Boolean.parseBoolean(System.getProperty("remold.vcs.derived_cache", "true"));
	}

	private static final class Entry {
		final Object value;
		final long bytes;

		Entry(Object value, long bytes) {
			this.value = value;
			this.bytes = bytes;
		}
	}

	/** 访问序 LRU：Map 的 key 是 kind + digest 十六进制。 */
	private static final Map<String, Entry> CACHE = new LinkedHashMap<>(256, 0.75f, true);

	private static long cachedBytes;
	private static long hits;
	private static long misses;
	private static long evictions;

	private DerivedDataCache() {
	}

	/**
	 * 取派生数据；未命中时用 {@code deriver} 计算并缓存。
	 *
	 * @param kind          派生种类（不同种类同 blob 不互串）
	 * @param blob          原始字节（不保留引用，只用于计算哈希）
	 * @param estimatedBytes 该派生结果的估算占用，用于字节预算
	 * @param deriver       派生器；必须返回不可变对象，且不得把 {@code blob} 或可变解析结果留在返回值里
	 */
	public static <T> T get(String kind, byte[] blob, long estimatedBytes, Deriver<T> deriver) {
		if (blob == null) return deriver.derive();
		return getByKey(kind, blobKey(blob), estimatedBytes, deriver);
	}

	/**
	 * 取派生数据（调用方已算好 blob 键）。
	 *
	 * <p>为什么单独开这一层：同一个 blob 在一次派生流程里可能被查多次（例如对齐侧按方法查同一份类的
	 * 派生事实）。把 {@code blobKey} 提到流程入口算一次，避免每次查表都重算 SHA-256——哈希本身对
	 * 十几 KiB 的类文件已是微秒级，重复算会吃掉缓存省下的那部分。</p>
	 *
	 * @param kind          派生种类
	 * @param blobKey       {@link #blobKey(byte[])} 的结果；{@code null} 表示"未启用缓存"（直接派生）
	 * @param estimatedBytes 该派生结果的估算占用
	 * @param deriver       派生器
	 */
	public static <T> T getByKey(String kind, String blobKey, long estimatedBytes, Deriver<T> deriver) {
		if (blobKey == null || !enabled()) return deriver.derive();
		String key = kind + ':' + blobKey;
		synchronized (CACHE) {
			Entry e = CACHE.get(key);
			if (e != null) {
				hits++;
				@SuppressWarnings("unchecked")
				T v = (T) e.value;
				return v;
			}
			misses++;
		}
		T value = deriver.derive();
		synchronized (CACHE) {
			if (CACHE.containsKey(key)) return value; // 并发下另一线程已填，保留先到的
			CACHE.put(key, new Entry(value, Math.max(0L, estimatedBytes)));
			cachedBytes += Math.max(0L, estimatedBytes);
			evictIfNeeded();
		}
		return value;
	}

	/** 只读查询；命中返回 {@code null} 表示未缓存。 */
	@SuppressWarnings("unchecked")
	public static <T> T peek(String kind, byte[] blob) {
		if (blob == null) return null;
		return peekByKey(kind, blobKey(blob));
	}

	/** 只读查询（调用方已算好 blob 键）。 */
	@SuppressWarnings("unchecked")
	public static <T> T peekByKey(String kind, String blobKey) {
		if (blobKey == null || !enabled()) return null;
		synchronized (CACHE) {
			Entry e = CACHE.get(kind + ':' + blobKey);
			return e == null ? null : (T) e.value;
		}
	}

	/**
	 * 内容键：{@code SHA-256(blob)} 的小写十六进制。
	 *
	 * <p>由调用方在流程入口算一次并复用；{@code null} 输入返回 {@code null}（调用方据此走"不缓存"分支）。</p>
	 */
	public static String blobKey(byte[] blob) {
		return blob == null || !enabled() ? null : digestHex(blob);
	}

	/** 统计快照：{@code [hits, misses, evictions, entries, bytes]}。 */
	public static long[] stats() {
		synchronized (CACHE) {
			return new long[]{ hits, misses, evictions, CACHE.size(), cachedBytes };
		}
	}

	/** 清空并重置统计（测试与 agent 重启口径）。 */
	public static void reset() {
		synchronized (CACHE) {
			CACHE.clear();
			cachedBytes = 0L;
			hits = 0L;
			misses = 0L;
			evictions = 0L;
		}
	}

	/** 派生器：只在未命中时调用。 */
	public interface Deriver<T> {
		T derive();
	}

	private static void evictIfNeeded() {
		int maxEntries = maxEntries();
		long maxBytes = maxBytes();
		var it = CACHE.entrySet().iterator();
		while (it.hasNext() && (CACHE.size() > maxEntries || cachedBytes > maxBytes)) {
			Entry e = it.next().getValue();
			cachedBytes -= e.bytes;
			it.remove();
			evictions++;
		}
	}

	static String digestHex(byte[] blob) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] d = md.digest(blob);
			char[] out = new char[d.length * 2];
			final char[] hex = "0123456789abcdef".toCharArray();
			for (int i = 0; i < d.length; i++) {
				out[i * 2] = hex[(d[i] >> 4) & 0xF];
				out[i * 2 + 1] = hex[d[i] & 0xF];
			}
			return new String(out);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
