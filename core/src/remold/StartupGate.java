package remold;

/**
 * 启动就绪门闩的纯状态机（不依赖真实时钟、线程或调度器）。
 *
 * <p><b>语义</b>：状态单向 {@code PENDING → READY}。{@code PENDING} 期间宿主尚未就绪
 * （典型场景：Minecraft 启动期 {@code KnotClassLoader} 与 Mixin 转换器正在高频并发加载类），
 * 此时热更批次一律不落，只把变更留在队列里。</p>
 *
 * <p><b>为什么必须有</b>：启动期落入热更批次会让 JVM 挂死，而精确线程栈从未抓到过。
 * 门闩把"这一批会不会撞上启动风暴"从运气问题变成确定性判断：就绪前不织入，
 * 挂死面直接消失（万一仍挂，{@link BatchWatchdog} 负责留下可归因的线程栈）。</p>
 *
 * <p><b>时钟契约</b>：本类不读 {@code System.nanoTime()}。所有"现在"由调用方传入
 * （见 {@link #isReadyAt(long)}），因此单测可以完全确定性驱动，不需要 sleep，
 * 也不会因为机器快慢而抖动。</p>
 *
 * <p><b>超时自动放行</b>：{@code -Dremold.agent.ready_timeout_ms}，默认 {@code 0} =
 * 不自动放行。默认关掉是刻意的——宿主永远不报就绪时"放行了但其实没就绪"比"一直押着"
 * 更危险：押着最多是热更不生效（日志会说话），放行则可能是又一次挂死。</p>
 */
public final class StartupGate {

	/** 系统属性名：PENDING 状态的最长容忍时间（毫秒），{@code 0} = 不超时放行。 */
	public static final String PROP_READY_TIMEOUT_MS = "remold.agent.ready_timeout_ms";

	/** 默认不做超时自动放行。 */
	public static final long DEFAULT_READY_TIMEOUT_MS = 0L;

	private final long timeoutNanos;

	private boolean ready;
	private boolean timedOutRelease;
	private boolean timeoutArmed;
	private long    pendingSinceNanos;

	/**
	 * @param timeoutMillis PENDING 状态超时（毫秒）；{@code <= 0} 表示不超时放行
	 */
	public StartupGate(long timeoutMillis) {
		this.timeoutNanos = Math.max(0L, timeoutMillis) * 1_000_000L;
	}

	/**
	 * 把系统属性原文解析成超时毫秒数（纯函数，便于单测直接喂字符串）。
	 *
	 * @param raw {@code -Dremold.agent.ready_timeout_ms} 的原文，可为 {@code null}
	 * @return 非负毫秒数；缺省、空白、非法值一律按 {@link #DEFAULT_READY_TIMEOUT_MS}
	 */
	public static long resolveTimeoutMillis(String raw) {
		if (raw == null) return DEFAULT_READY_TIMEOUT_MS;
		String v = raw.trim();
		if (v.isEmpty()) return DEFAULT_READY_TIMEOUT_MS;
		try {
			long parsed = Long.parseLong(v);
			return Math.max(0L, parsed);
		} catch (NumberFormatException ignored) {
			// 非法配置按默认值处理，绝不因为一个笔误把门闩变成永久关闭。
			return DEFAULT_READY_TIMEOUT_MS;
		}
	}

	/**
	 * 标记宿主已就绪：状态翻到 {@link #READY} 且不再回退。
	 *
	 * @return {@code true} 表示本次调用真正发生了 {@code PENDING → READY}（用于"每次
	 *         就绪状态变化只打一条日志"的判定）
	 */
	public synchronized boolean markReady() {
		if (ready) return false;
		ready = true;
		return true;
	}

	/** 当前是否已就绪。（不推进时间、不评估超时；需要超时语义请用 {@link #isReadyAt(long)}） */
	public synchronized boolean isReady() {
		return ready;
	}

	/**
	 * 以调用方给出的"现在"评估就绪状态，包含超时自动放行。
	 *
	 * <p>第一次调用（且配置了超时）时开始计时：{@code PENDING} 从"首次观测"算起。
	 * 超时后自动翻到 {@link #READY}，并通过 {@link #isTimedOutRelease()} 标明归因，
	 * 方便日志写成"超时放行"而不是伪装成宿主真的就绪了。</p>
	 *
	 * @param nowNanos 当前单调时钟读数（纳秒），必须与 {@link System#nanoTime()} 同一时钟
	 * @return 是否已就绪
	 */
	public synchronized boolean isReadyAt(long nowNanos) {
		if (ready) return true;
		if (timeoutNanos <= 0L) return false;
		if (!timeoutArmed) {
			timeoutArmed = true;
			pendingSinceNanos = nowNanos;
			return false;
		}
		if (nowNanos - pendingSinceNanos >= timeoutNanos) {
			ready = true;
			timedOutRelease = true;
			return true;
		}
		return false;
	}

	/** 就绪是否由超时自动放行产生（而不是宿主插件报告就绪）。 */
	public synchronized boolean isTimedOutRelease() {
		return timedOutRelease;
	}

	/** 配置的超时毫秒数；{@code 0} 表示不超时放行。 */
	public synchronized long getTimeoutMillis() {
		return timeoutNanos / 1_000_000L;
	}

	/**
	 * PENDING 已经持续了多久（纳秒）；未配置超时或尚未开始计时返回 {@code -1}。
	 * 只供诊断/日志使用，不改变状态。
	 */
	public synchronized long getPendingElapsedNanos(long nowNanos) {
		if (ready || !timeoutArmed) return -1L;
		return Math.max(0L, nowNanos - pendingSinceNanos);
	}
}
