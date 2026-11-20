package remold.vcs;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Deque;

/**
 * 加载前沿：把"运行期第一次看见某个类"（birth）这件事从编辑时间轴里分离出来。
 *
 * <p><b>为什么不创建 Changeset</b>：开放世界里懒加载由玩家行为驱动，一轮跑图可能带来上百次 birth。
 * 若把它们当作变更代提交，既会污染 {@code seq}，也会把 {@link ChangesetLog} 好不容易压到的
 * {@code O(1)} 提交语义毁掉（规格 {@code docs/vcs/07-load-frontier-and-birth-watermark.md} L1/L2）。</p>
 *
 * <p><b>这里只做两件事</b>：推进单调水位线；把 {@code (loaderId, 内部名, eff 哈希)} 这样的标量元组
 * 入队。加载路径上不解析字节码、不算摘要、不做跨类分析——分析只能发生在队列 flush 时（L2/L3）。</p>
 *
 * <p><b>有界</b>：队列超过上限即丢弃最旧的条目并累计 {@code droppedBirths}。丢弃会写进当轮清单，
 * 而一旦发生过丢弃，所有"可覆写"结论都必须降级为 {@code OPAQUE_GUARDED}（L4/L5）——因为回放端
 * 再也无法完整重现当时的加载状态。</p>
 */
public final class LoadFrontier {

	/** 队列上限：加载路径上不允许无界增长。 */
	public static final int DEFAULT_CAPACITY = 4096;

	private static final Object LOCK = new Object();
	private static final Deque<Birth> QUEUE = new ArrayDeque<>();
	private static int capacity = DEFAULT_CAPACITY;
	private static long watermark;
	private static long droppedBirths;
	private static long unresolvedBirths;
	private static HashResolver HASH_RESOLVER;

	private LoadFrontier() { }

	/**
	 * 一次出生事件。
	 *
	 * @param loaderId  存活期内单调的 loader 槽位号（不持 {@code ClassLoader}，避免拖住 Metaspace）
	 * @param internalName 内部名（{@code a/b/C}），字符串，便于跨卸载留存
	 * @param effSha256 该次出生真正生效的 Eff 字节哈希（替换后的字节，不是原始 buffer）
	 */
	public record Birth(long loaderId, String internalName, String effSha256) { }

	/** 记录一次出生：推进水位线并入队；触限时丢弃最旧条目并计数。 */
	public static void birth(long loaderId, String internalName, String effSha256) {
		if (internalName == null || internalName.isEmpty()) return;
		synchronized (LOCK) {
			watermark++;
			if (QUEUE.size() >= capacity) {
				QUEUE.pollFirst();
				droppedBirths++;
			}
			QUEUE.addLast(new Birth(loaderId, internalName, effSha256 == null ? "" : effSha256));
		}
	}

	/** 当前加载前沿水位（单调只增）。 */
	public static long watermark() {
		synchronized (LOCK) {
			return watermark;
		}
	}

	/** 尚未 flush 的出生条目数。 */
	public static int pending() {
		synchronized (LOCK) {
			return QUEUE.size();
		}
	}

	/** 因触限被丢弃的出生条目累计数；非零表示回放不可完整重现。 */
	public static long droppedBirths() {
		synchronized (LOCK) {
			return droppedBirths;
		}
	}

	/**
	 * flush 的结果：区分"哈希可确定"与"无法确定"的出生。
	 *
	 * <p>无法确定的一律按 L5 保守处理——相关字段的结论只能是 {@code OPAQUE_GUARDED}，不允许硬算。</p>
	 */
	public record Flush(List<Birth> resolved, int unresolved) {
		public Flush {
			resolved = resolved == null ? List.of() : List.copyOf(resolved);
		}
	}

	/** 哈希解析口：由调用方（知道 {@code bytecodeCache} 语义的那一层）注入，避免本类反向依赖 agent。 */
	@FunctionalInterface
	public interface HashResolver {
		/**
		 * @return 该次出生真正生效字节的 sha256；<b>无法唯一确定时必须返回 {@code null}</b>
		 *         （例如同一类名在多个 loader 下都出生过、或缓存里已经没有这一条）
		 */
		String hashOf(long loaderId, String internalName);
	}

	/**
	 * 消费队列并补齐哈希。
	 *
	 * <p>解析不出来的条目<b>不会</b>被当成"哈希为空"静默放行：它们计入 {@code unresolved}，
	 * 由调用方据此把相关结论降级。</p>
	 */
	public static Flush flush(HashResolver resolver) {
		if (resolver == null) throw new IllegalArgumentException("resolver");
		List<Birth> pending;
		synchronized (LOCK) {
			pending = List.copyOf(QUEUE);
			QUEUE.clear();
		}
		List<Birth> resolved = new java.util.ArrayList<>(pending.size());
		int unresolved = 0;
		for (Birth b : pending) {
			String hash = b.effSha256() == null || b.effSha256().isEmpty()
			 ? resolver.hashOf(b.loaderId(), b.internalName()) : b.effSha256();
			if (hash == null || hash.isEmpty()) {
				unresolved++;
				continue;
			}
			resolved.add(new Birth(b.loaderId(), b.internalName(), hash));
		}
		synchronized (LOCK) {
			unresolvedBirths += unresolved;
		}
		return new Flush(resolved, unresolved);
	}

	/** 已 flush 但无法确定哈希的出生累计数；非零表示写点闭包不完备。 */
	public static long unresolvedBirths() {
		synchronized (LOCK) {
			return unresolvedBirths;
		}
	}

	/**
	 * 当前世界是否只允许保守结论：发生过丢弃或存在无法确定哈希的出生时为 {@code true}（规格 L4/L5）。
	 */
	public static boolean conservativeOnly() {
		synchronized (LOCK) {
			return droppedBirths > 0L || unresolvedBirths > 0L;
		}
	}

	/** 取走全部待处理出生（flush 语义：调用方拿到即视为已消费）。 */
	public static List<Birth> drain() {
		synchronized (LOCK) {
			List<Birth> out = List.copyOf(QUEUE);
			QUEUE.clear();
			return out;
		}
	}

	/**
	 * 注册哈希解析口（由 agent 层在初始化时注册；只允许一个，重复注册覆盖）。
	 *
	 * <p>依赖方向：{@code vcs} 不认识 {@code RemoldAgent}，所以口子朝内开。</p>
	 */
	public static void setResolver(HashResolver resolver) {
		synchronized (LOCK) {
			HASH_RESOLVER = resolver;
		}
	}

	/**
	 * 提交前的 flush 边界（规格 L3）：把待处理出生消费成计数，供本轮清单记账。
	 *
	 * <p>没有注册解析口时无法补哈希，全部计入 {@code unresolved}——这是保守方向，不是放行。</p>
	 */
	public static Flush flushBeforeCommit() {
		HashResolver resolver;
		synchronized (LOCK) {
			resolver = HASH_RESOLVER;
		}
		if (resolver == null) {
			int size = pending();
			if (size > 0) {
				drain();
				synchronized (LOCK) {
					unresolvedBirths += size;
				}
			}
			return new Flush(List.of(), size);
		}
		return flush(resolver);
	}

	/** 测试与诊断用：重置全部状态。 */
	public static void reset() {
		synchronized (LOCK) {
			QUEUE.clear();
			watermark = 0L;
			droppedBirths = 0L;
			unresolvedBirths = 0L;
			HASH_RESOLVER = null;
			capacity = DEFAULT_CAPACITY;
		}
	}

	/** 测试用：改容量以验证触限丢弃与计数。 */
	static void capacityForTest(int value) {
		synchronized (LOCK) {
			capacity = Math.max(1, value);
		}
	}
}
