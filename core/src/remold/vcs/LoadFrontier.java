package remold.vcs;

import java.util.ArrayDeque;
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

	/** 取走全部待处理出生（flush 语义：调用方拿到即视为已消费）。 */
	public static java.util.List<Birth> drain() {
		synchronized (LOCK) {
			java.util.List<Birth> out = java.util.List.copyOf(QUEUE);
			QUEUE.clear();
			return out;
		}
	}

	/** 测试与诊断用：重置全部状态。 */
	public static void reset() {
		synchronized (LOCK) {
			QUEUE.clear();
			watermark = 0L;
			droppedBirths = 0L;
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
