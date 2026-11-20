package remold;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import remold.util.Names;

/**
 * 工作区 Raw 字节的按需索引（包 A 最后一项）。
 *
 * <p><b>为什么不做启动全扫</b>：工程类动辄上千，全盘扫目录树会把启动时间压在"打开编辑器之前"；
 * 而绝大多数批次只碰一小撮类。因此这里只在<b>真的需要</b>某个类的原始字节时向
 * {@link BytecodeSources} 问一次，并把结果缓存在<b>批内</b>——批次结束即清空，不需要跨批次失效协议。</p>
 *
 * <p><b>三条 fail-closed 纪律</b>：</p>
 * <ol>
 *   <li><b>找不到</b>就是找不到：返回 {@code null}，调用方按"不可证"处理，绝不退回猜测；</li>
 *   <li><b>歧义</b>（多个来源给出不同字节）同样返回 {@code null} 并计入 {@code ambiguous}；
 *       把运行期已织入的字节当成磁盘 Raw 是最难查的一类错误；</li>
 *   <li>索引<b>只回答</b>"工作区里这个类的原始字节是什么"，不参与任何裁决方向。</li>
 * </ol>
 *
 * <p><b>批生命周期</b>：{@link #beginBatch()} / {@link #endBatch()} 与 InitFix 的批次上下文同寿；
 * 批次外查询不落缓存（避免退化成一张没有寿命管理的全局表），只计入 {@code unattributedHits}。</p>
 */
public final class WorkspaceRawIndex {

	/** 一次查询的结论。 */
	public enum Status {
		/** 唯一来源命中。 */
		HIT,
		/** 没有来源给出字节。 */
		MISS,
		/** 多个来源给出不同字节：不可证。 */
		AMBIGUOUS
	}

	/** 查询结果。 */
	public record Result(Status status, byte[] bytes, int distinctSources) {
		public static final Result MISS = new Result(Status.MISS, null, 0);
		public static final Result AMBIGUOUS = new Result(Status.AMBIGUOUS, null, 0);

		public boolean usable() {
			return status == Status.HIT && bytes != null && bytes.length > 0;
		}
	}

	private static final Object LOCK = new Object();
	private static final ThreadLocal<Map<String, Result>> BATCH_CACHE = new ThreadLocal<>();
	private static long hits;
	private static long misses;
	private static long ambiguous;
	private static long unattributedHits;
	private static long totalNanos;
	private static long maxNanos;

	private WorkspaceRawIndex() { }

	/** 打开批内缓存（与 InitFix 批次上下文同寿）。 */
	public static void beginBatch() {
		synchronized (LOCK) {
			BATCH_CACHE.set(new HashMap<>());
		}
	}

	/** 关闭并丢弃批内缓存；重复调用无害。 */
	public static void endBatch() {
		synchronized (LOCK) {
			BATCH_CACHE.remove();
		}
	}

	/**
	 * 查询某个类的原始字节。
	 *
	 * @param internalNameOrDotted 内部名或点分名
	 * @return 命中给字节；找不到或有歧义给 {@code null}（调用方按不可证处理）
	 */
	public static byte[] lookup(String internalNameOrDotted) {
		return query(internalNameOrDotted).bytes();
	}

	/** 与 {@link #lookup(String)} 相同，但把结论形态交出来（供诊断与用例断言）。 */
	public static Result query(String internalNameOrDotted) {
		if (internalNameOrDotted == null || internalNameOrDotted.isEmpty()) return Result.MISS;
		// 缓存键按内部名归一：同一批里点分与斜杠混查必须命中同一条，否则会白打两次来源。
		String key = Names.internal(internalNameOrDotted);
		Map<String, Result> cache = BATCH_CACHE.get();
		if (cache != null) {
			Result cached = cache.get(key);
			if (cached != null) return cached;
		}

		long start = System.nanoTime();
		List<byte[]> found = BytecodeSources.lookupAll(internalNameOrDotted);
		Result result;
		if (found.isEmpty()) {
			result = Result.MISS;
		} else if (found.size() > 1) {
			// 多个来源给出不同字节：不猜哪一个才是 Raw。
			result = new Result(Status.AMBIGUOUS, null, found.size());
		} else {
			byte[] bytes = found.get(0);
			result = new Result(Status.HIT, bytes, 1);
		}
		record(result, System.nanoTime() - start, cache != null);
		if (cache != null) cache.put(key, result);
		return result;
	}

	private static void record(Result result, long nanos, boolean attributed) {
		synchronized (LOCK) {
			switch (result.status()) {
				case HIT:
					hits++;
					if (!attributed) unattributedHits++;
					break;
				case AMBIGUOUS:
					ambiguous++;
					break;
				case MISS:
				default:
					misses++;
					break;
			}
			totalNanos += nanos;
			if (nanos > maxNanos) maxNanos = nanos;
		}
	}

	/** 诊断读数：命中/未命中/歧义/批次外命中与耗时。 */
	public static Map<String, Long> counters() {
		synchronized (LOCK) {
			Map<String, Long> out = new LinkedHashMap<>();
			out.put("hits", hits);
			out.put("misses", misses);
			out.put("ambiguous", ambiguous);
			out.put("unattributedHits", unattributedHits);
			out.put("totalNanos", totalNanos);
			out.put("maxNanos", maxNanos);
			return Collections.unmodifiableMap(out);
		}
	}

	/** 测试与 agent 重启口径。 */
	public static void reset() {
		synchronized (LOCK) {
			BATCH_CACHE.remove();
			hits = 0L;
			misses = 0L;
			ambiguous = 0L;
			unattributedHits = 0L;
			totalNanos = 0L;
			maxNanos = 0L;
		}
	}
}
