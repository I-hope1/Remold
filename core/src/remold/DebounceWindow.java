package remold;

/**
 * 文件热更防抖的纯状态机（不依赖真实时间、线程或调度器）。
 *
 * <p><b>语义</b>：Class 流与资源流各自维护一个 trailing-edge 静默窗口。每收到一个事件，
 * 就把对应流的 deadline 重置为 {@code now + window}；只有当某条流连续 {@code window}
 * 没有新事件时，才认为该流本轮结束。两条流互不影响：资源保存不会推迟 Class 批次，
 * Class 编译也不会推迟资源回调。</p>
 *
 * <p>{@code jar/zip} 与 {@code .class} 同属 Class 流（由调用方在收事件时选择
 * {@link #onClass(long)}），因为 jar 变化会影响类路径，拆成第三路会造成连续触发。</p>
 *
 * <p>本类只做时间判断，不持有队列、不触发动作。调用方负责在到期后 drain 对应队列，
 * 并调用 {@link #clearClass()} / {@link #clearAsset()} 把该流标记为“已处理”。</p>
 */
public final class DebounceWindow {

	private long classWindowNanos;
	private long assetWindowNanos;

	private boolean classPending;
	private boolean assetPending;
	private long classDeadlineNanos;
	private long assetDeadlineNanos;

	/**
	 * @param classWindowMillis Class/jar 流的静默窗口（毫秒，负数按 0 处理）
	 * @param assetWindowMillis 资源/配置流的静默窗口（毫秒，负数按 0 处理）
	 */
	public DebounceWindow(long classWindowMillis, long assetWindowMillis) {
		setWindows(classWindowMillis, assetWindowMillis);
	}

	/** 更新两条流的窗口；已排队的 deadline 保持不变，只影响之后的新事件。 */
	public synchronized void setWindows(long classWindowMillis, long assetWindowMillis) {
		this.classWindowNanos = Math.max(0L, classWindowMillis) * 1_000_000L;
		this.assetWindowNanos = Math.max(0L, assetWindowMillis) * 1_000_000L;
	}

	/** 收到一次 Class/jar 事件：重置 Class 流的静默 deadline。 */
	public synchronized void onClass(long nowNanos) {
		classPending = true;
		classDeadlineNanos = nowNanos + classWindowNanos;
	}

	/** 收到一次资源/配置事件：重置资源流的静默 deadline。 */
	public synchronized void onAsset(long nowNanos) {
		assetPending = true;
		assetDeadlineNanos = nowNanos + assetWindowNanos;
	}

	/** 是否还有未处理的 Class 事件。 */
	public synchronized boolean hasPendingClass() {
		return classPending;
	}

	/** 是否还有未处理的资源事件。 */
	public synchronized boolean hasPendingAsset() {
		return assetPending;
	}

	/**
	 * 距离下一次到期还有多少纳秒；两条流都清空时返回 {@code -1}。
	 * 已到期返回 {@code 0}。
	 */
	public synchronized long nextDelayNanos(long nowNanos) {
		long delay = Long.MAX_VALUE;
		if (classPending) {
			delay = Math.min(delay, classDeadlineNanos - nowNanos);
		}
		if (assetPending) {
			delay = Math.min(delay, assetDeadlineNanos - nowNanos);
		}
		if (delay == Long.MAX_VALUE) {
			return -1L;
		}
		return Math.max(0L, delay);
	}

	/** Class 流是否已静默到期（只代表“时间到”，调用方还要确认队列非空）。 */
	public synchronized boolean isClassDue(long nowNanos) {
		return classPending && classDeadlineNanos <= nowNanos;
	}

	/** 资源流是否已静默到期。 */
	public synchronized boolean isAssetDue(long nowNanos) {
		return assetPending && assetDeadlineNanos <= nowNanos;
	}

	/** Class 批次已处理完：清除 Class 流状态。 */
	public synchronized void clearClass() {
		classPending = false;
		classDeadlineNanos = 0L;
	}

	/** 资源批次已处理完：清除资源流状态。 */
	public synchronized void clearAsset() {
		assetPending = false;
		assetDeadlineNanos = 0L;
	}

	/** 手动强制触发后清空两条流的状态（调用方同时要取消已挂起的调度）。 */
	public synchronized void clearAll() {
		clearClass();
		clearAsset();
	}

	/** 当前 Class 窗口（毫秒），主要供诊断/日志使用。 */
	public synchronized long getClassWindowMillis() {
		return classWindowNanos / 1_000_000L;
	}

	/** 当前资源窗口（毫秒）。 */
	public synchronized long getAssetWindowMillis() {
		return assetWindowNanos / 1_000_000L;
	}
}
