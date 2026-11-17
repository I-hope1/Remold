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

	/** 默认条目上限。 */
	public static final int MAX_ENTRIES = 512;
	/** 默认估算字节上限。 */
	public static final long MAX_BYTES = 8L * 1024 * 1024;

	/** 单条最长保留时间不计；纯内存、无时钟。 */

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
		String key = kind + ':' + digestHex(blob);
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
		synchronized (CACHE) {
			Entry e = CACHE.get(kind + ':' + digestHex(blob));
			return e == null ? null : (T) e.value;
		}
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
		var it = CACHE.entrySet().iterator();
		while (it.hasNext() && (CACHE.size() > MAX_ENTRIES || cachedBytes > MAX_BYTES)) {
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
