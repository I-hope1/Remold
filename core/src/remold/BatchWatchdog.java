package remold;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

/**
 * 批次卡死看门狗：批次（{@code HOTSWAP_LOCK} 的持有者）开始时注册、结束时注销，
 * 只有<b>同一批次超过阈值仍未结束</b>时才动作一次。
 *
 * <p><b>为什么必须有</b>：启动期落批次导致的 JVM 挂死从来没有精确线程栈——
 * 挂死时没人能按 Ctrl+Break，日志里只剩"最后一句话"。看门狗的价值不是"防止挂死"，
 * 而是<b>下次再挂时自动留下可归因的线程栈</b>：谁在等哪把锁、持锁线程停在哪一帧。</p>
 *
 * <p><b>常态开销</b>：一个批次只挂一个一次性延迟任务（{@link ScheduledExecutorService#schedule}），
 * 批内在阈值前正常结束时任务被取消，不做任何轮询、不采样、不攒数据。</p>
 *
 * <p><b>同一批次只 dump 一次</b>：{@code dumped} 标志在 {@link #arm} 时重置，
 * 因此"一个批次一次"，而不是"一个进程一次"；也不会在挂死期间刷爆日志。</p>
 *
 * <p><b>作用域 API（{@link #enter} / {@link #exit}）</b>：看门狗必须挂在<b>真正执行批次的
 * 那个方法</b>上，而不是挂在某个入口的包装方法上——否则"从别的入口进来的批次"就没有仪器。
 * {@link #enter(long, String)} 把看门狗绑到当前线程并 arm，{@link #exit()} 在
 * {@code finally} 里注销并解绑；<b>同一批次内可重入</b>：嵌套的 {@code enter} 复用同一个
 * 看门狗、不重复注册，因此一个批次最多一条 {@link #PREFIX}，只有最外层的 {@code exit}
 * 才真正注销。异常路径只要走 {@code finally} 就必然注销，不留残留定时任务。</p>
 *
 * <p><b>时钟契约</b>：{@link #arm(long, String)} / {@link #stallCheck(long)} 的"现在"
 * 由调用方传入，必须与 {@link System#nanoTime()} 同一时钟。单测因此可以喂一个
 * "未来的读数"验证阈值判定，不需要 sleep。</p>
 */
public final class BatchWatchdog {

	/** 所有看门狗输出行的统一前缀（可归因，便于 grep）。 */
	public static final String PREFIX = "[HOTSWAP-STALL]";

	/** 系统属性名：批次卡死阈值（毫秒），默认 {@link #DEFAULT_STALL_MS}。 */
	public static final String PROP_STALL_MS = "remold.agent.batch_stall_ms";

	/** 默认阈值：15s。正常批次是百毫秒级，15s 未结束基本只可能是挂死。 */
	public static final long DEFAULT_STALL_MS = 15_000L;

	/**
	 * 阈值下限：5s。再小的阈值会在"大工程全量重编译后的第一批"上误报，
	 * 而误报会让人习惯性忽略这个前缀，等于把仪器关掉。
	 */
	public static final long MIN_STALL_MS = 5_000L;

	/** dump 输出目标（可注入，便于单测捕获而不污染日志）。 */
	public interface StallSink {
		void dump(String report);
	}

	private static volatile ScheduledExecutorService scheduler;

	/** 当前线程正在进行的批次看门狗（同一批次内的重入复用同一个实例）。 */
	private static final ThreadLocal<BatchWatchdog> CURRENT = new ThreadLocal<>();

	/** 进程内当前处于 armed 状态的看门狗数量。批次串行（HOTSWAP_LOCK），因此一个批次最多一个。 */
	private static final AtomicInteger ARMED_WATCHDOGS = new AtomicInteger();

	/** 构造注入点；{@code null} = 默认 sink 的看门狗。生产路径不设置。 */
	private static volatile LongFunction<BatchWatchdog> factory;

	private final long       stallMillis;
	private final long       stallNanos;
	private final StallSink  sink;

	private final AtomicBoolean dumped = new AtomicBoolean(false);

	private volatile boolean armed;
	private volatile long    armedAtNanos;
	private volatile String  label = "";
	private volatile Thread  owner;

	/** 同一批次的嵌套深度：{@code > 1} 表示批次内重入，只在最外层注销。 */
	private int depth;

	private ScheduledFuture<?> task;

	/** 使用默认 sink（{@code System.err} + 尽力落盘 {@code build/remold-stall-<ts>.txt}）。 */
	public BatchWatchdog(long stallMillis) {
		this(stallMillis, BatchWatchdog::defaultSink);
	}

	/**
	 * @param stallMillis 阈值毫秒数；低于 {@link #MIN_STALL_MS} 一律抬到下限
	 * @param sink        dump 输出目标，{@code null} 表示使用默认 sink
	 */
	public BatchWatchdog(long stallMillis, StallSink sink) {
		this.stallMillis = Math.max(MIN_STALL_MS, stallMillis);
		this.stallNanos  = this.stallMillis * 1_000_000L;
		this.sink        = sink == null ? BatchWatchdog::defaultSink : sink;
	}

	/**
	 * 把系统属性原文解析成阈值毫秒数（纯函数）。
	 *
	 * @param raw {@code -Dremold.agent.batch_stall_ms} 的原文，可为 {@code null}
	 * @return 不小于 {@link #MIN_STALL_MS} 的毫秒数；缺省/空白/非法值按 {@link #DEFAULT_STALL_MS}
	 */
	public static long resolveStallMillis(String raw) {
		if (raw == null) return DEFAULT_STALL_MS;
		String v = raw.trim();
		if (v.isEmpty()) return DEFAULT_STALL_MS;
		try {
			return Math.max(MIN_STALL_MS, Long.parseLong(v));
		} catch (NumberFormatException ignored) {
			// 配置笔误不该让看门狗消失：退回默认阈值。
			return DEFAULT_STALL_MS;
		}
	}

	/**
	 * 注入点：看门狗构造（{@code null} = 恢复生产默认：默认 sink 的看门狗）。
	 *
	 * <p>存在的理由：单测必须捕获 dump 而不写 {@code System.err} / 不落盘，否则断言只能靠
	 * 抓日志。生产路径不设置它。</p>
	 *
	 * @param newFactory 看门狗工厂，可为 {@code null}
	 */
	public static void setWatchdogFactory(LongFunction<BatchWatchdog> newFactory) {
		factory = newFactory;
	}

	/**
	 * 批次作用域开始：把看门狗绑到当前线程并 arm。
	 *
	 * <p><b>为什么是作用域而不是"某个入口的包装"</b>：批次可以从防抖调度、手动
	 * {@code triggerHotswap}、{@code retransformLoaded} 等多条路径进入，只包住其中一条
	 * 会让其余路径在卡死时没有任何线程栈。把 {@code enter} 放进真正执行批次的方法里，
	 * 覆盖面由"方法被谁调用"决定，而不是由"谁记得加包装"决定。</p>
	 *
	 * <p><b>同一批次可重入</b>：嵌套调用复用同一个看门狗并只累加深度，不重新 arm
	 * （{@code arm} 会重置 once 标志并换掉定时任务）——这正是"同一批次只 dump 一次"的实现基础。</p>
	 *
	 * @param stallMillis 阈值毫秒数（低于 {@link #MIN_STALL_MS} 会被抬到下限）
	 * @param label       批次标识（进 dump 头部，便于归因是哪一批）
	 * @return 本批次的看门狗；调用方必须把 {@link #exit()} 放进 {@code finally}
	 */
	public static BatchWatchdog enter(long stallMillis, String label) {
		BatchWatchdog existing = CURRENT.get();
		if (existing != null) {
			existing.depth++;
			return existing;
		}
		BatchWatchdog watchdog = create(stallMillis);
		watchdog.depth = 1;
		CURRENT.set(watchdog);
		watchdog.arm(System.nanoTime(), label);
		return watchdog;
	}

	/**
	 * 批次作用域结束：注销看门狗并解绑当前线程。
	 *
	 * <p><b>必须放在 {@code finally}</b>：批次抛异常时若跳过注销，看门狗会在超时后误报一次，
	 * 而且线程绑定会泄漏到下一次批次（导致下一批拿到"上一批"的看门狗、once 标志已被消耗）。
	 * 嵌套进入只递减深度，只有最外层才真正注销。</p>
	 */
	public static void exit() {
		BatchWatchdog watchdog = CURRENT.get();
		if (watchdog == null) return;
		if (watchdog.depth > 1) {
			watchdog.depth--;
			return;
		}
		watchdog.depth = 0;
		try {
			watchdog.disarm();
		} finally {
			CURRENT.remove();
		}
	}

	/**
	 * 当前 armed 的看门狗数量。
	 *
	 * <p><b>守的不变量</b>：批次由 {@code HOTSWAP_LOCK} 串行化，一个批次最多只能有一个
	 * armed 的看门狗——出现 2 就意味着同一批次挂了两份仪器，超时后会打出两条
	 * {@link #PREFIX}，把可归因的前缀训练成噪声。批次外的正常值是 {@code 0}。</p>
	 */
	public static int armedWatchdogs() {
		return ARMED_WATCHDOGS.get();
	}

	/** 当前线程正在进行的批次看门狗；不在批次内返回 {@code null}。 */
	public static BatchWatchdog current() {
		return CURRENT.get();
	}

	private static BatchWatchdog create(long stallMillis) {
		LongFunction<BatchWatchdog> f = factory;
		if (f != null) {
			try {
				BatchWatchdog injected = f.apply(stallMillis);
				if (injected != null) return injected;
			} catch (Throwable ignored) {
				// 注入点坏掉不能把批次带崩：退回生产默认。
			}
		}
		return new BatchWatchdog(stallMillis);
	}

	/**
	 * 批次开始：登记当前线程为批次线程，并挂一个一次性延迟任务。
	 *
	 * <p>重复调用等价于"上一个批次结束、新批次开始"：旧任务被取消、once 标志被重置。</p>
	 *
	 * @param nowNanos 当前单调时钟读数（纳秒），与 {@link System#nanoTime()} 同一时钟
	 * @param label    批次标识（进 dump 头部，便于归因是哪一批）
	 */
	public synchronized void arm(long nowNanos, String label) {
		cancelTaskLocked();
		this.owner        = Thread.currentThread();
		this.label        = label == null ? "" : label;
		this.armedAtNanos = nowNanos;
		this.dumped.set(false);
		if (!armed) ARMED_WATCHDOGS.incrementAndGet();
		this.armed        = true;

		// 剩余延迟按"已经过去多少"折算：生产路径 elapsed≈0，延迟就是一个完整阈值；
		// 单测可以把 nowNanos 设成过去，从而让任务立即触发而不必 sleep。
		long elapsed = System.nanoTime() - nowNanos;
		long delay   = Math.max(0L, stallNanos - elapsed);
		try {
			task = scheduler().schedule(this::fire, delay, TimeUnit.NANOSECONDS);
		} catch (Throwable ignored) {
			// 调度器不可用（关机/类加载未完成）时退化为"没有看门狗"，绝不因此让批次失败。
			task = null;
		}
	}

	/** 批次结束：注销看门狗。正常路径必须走到这里，否则超时后会误报一次。 */
	public synchronized void disarm() {
		if (armed) ARMED_WATCHDOGS.decrementAndGet();
		armed = false;
		cancelTaskLocked();
		if (owner == Thread.currentThread()) owner = null;
	}

	/** 当前是否处于"批次进行中"状态。 */
	public synchronized boolean isArmed() {
		return armed;
	}

	/**
	 * 是否还挂着一个未执行的定时任务。
	 *
	 * <p>注销后必须为 {@code false}：残留的定时任务在批次结束后仍会醒来看一次（虽然
	 * {@code armed == false} 让它不会 dump，但残留本身就说明注销路径漏了）。</p>
	 */
	public synchronized boolean hasPendingTask() {
		return task != null;
	}

	/** 本批次是否已经 dump 过（同批次不会第二次 dump）。 */
	public boolean hasDumped() {
		return dumped.get();
	}

	/** 阈值毫秒数（已按 {@link #MIN_STALL_MS} 抬升）。 */
	public long getStallMillis() {
		return stallMillis;
	}

	/** 定时任务体：真实时间推进后做一次判定。异常一律吞掉，看门狗不得影响批次。 */
	private void fire() {
		try {
			stallCheck(System.nanoTime());
		} catch (Throwable ignored) {
		}
	}

	/**
	 * 卡死判定：批次仍进行中、已超过阈值、且本批次尚未 dump 过——三者同时成立才动作。
	 *
	 * @param nowNanos 当前单调时钟读数（纳秒）
	 * @return {@code true} 表示本次调用真正输出了 dump
	 */
	public boolean stallCheck(long nowNanos) {
		String report;
		synchronized (this) {
			if (!armed) return false;
			long elapsed = nowNanos - armedAtNanos;
			if (elapsed < stallNanos) return false;
			// 同一批次只 dump 一次：挂死期间定时任务/调用方可能反复进来。
			if (!dumped.compareAndSet(false, true)) return false;
			report = buildReport(label, owner, stallMillis, Math.max(0L, elapsed / 1_000_000L));
		}
		try {
			// dumpAllThreads 会做安全点采样，把重活放在本对象锁之外。
			sink.dump(report);
		} catch (Throwable ignored) {
			// 输出目标坏了不是批次的问题：绝不让看门狗把批次带崩。
		}
		return true;
	}

	/**
	 * 导出全部线程栈（含锁持有信息）。采样失败时返回 {@code null}，由报告写成
	 * "thread dump unavailable"，不让看门狗自己抛异常。
	 */
	private static ThreadInfo[] dumpAllThreads() {
		try {
			ThreadMXBean mx = ManagementFactory.getThreadMXBean();
			return mx.dumpAllThreads(true, true);
		} catch (Throwable t) {
			return null;
		}
	}

	private static String buildReport(String label, Thread owner, long stallMillis, long elapsedMillis) {
		StringBuilder sb = new StringBuilder(8192);
		sb.append(PREFIX).append(" hot-swap batch exceeded ").append(stallMillis)
		  .append(" ms while holding HOTSWAP_LOCK (elapsed ").append(elapsedMillis).append(" ms)\n");
		sb.append("  batch         : ").append(label).append('\n');
		sb.append("  batch thread  : ").append(owner == null ? "<unknown>" : describe(owner)).append('\n');
		sb.append("  threshold     : ").append(stallMillis).append(" ms (system property ")
		  .append(PROP_STALL_MS).append(", minimum ").append(MIN_STALL_MS).append(" ms)\n");
		sb.append("  intent        : the exact stack of a startup-time hot-swap hang was never captured;")
		  .append(" this dump is meant to attribute it.\n");
		sb.append("  dump source   : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)\n");

		ThreadInfo[] infos = dumpAllThreads();
		if (infos == null) {
			sb.append('\n').append(PREFIX).append(" thread dump unavailable (dumpAllThreads failed)\n");
			return sb.toString();
		}
		sb.append('\n').append("--- ").append(infos.length).append(" threads ---\n");
		for (ThreadInfo info : infos) {
			if (info == null) continue;
			sb.append('\n').append('"').append(info.getThreadName()).append("\" id=").append(info.getThreadId())
			  .append(" state=").append(info.getThreadState());
			if (info.getLockName() != null) {
				sb.append(" [waiting on ").append(info.getLockName());
				if (info.getLockOwnerName() != null) {
					sb.append(" held by \"").append(info.getLockOwnerName())
					  .append("\" id=").append(info.getLockOwnerId());
				}
				sb.append(']');
			}
			if (info.getBlockedTime() >= 0) sb.append(" blockedTime=").append(info.getBlockedTime()).append("ms");
			if (info.getWaitedTime() >= 0) sb.append(" waitedTime=").append(info.getWaitedTime()).append("ms");
			sb.append('\n');
			StackTraceElement[] frames = info.getStackTrace();
			for (StackTraceElement frame : frames) {
				sb.append("    at ").append(frame).append('\n');
			}
			if (frames.length == 0) sb.append("    <no stack trace available>\n");
		}
		return sb.toString();
	}

	private static String describe(Thread t) {
		return '"' + t.getName() + "\" id=" + t.getId() + " state=" + t.getState();
	}

	/**
	 * 默认输出：先写 {@code System.err}（保证挂死现场第一时间可见），再尽力落盘。
	 * 落盘失败/目录不存在一律忽略，绝不抛异常。
	 */
	private static void defaultSink(String report) {
		System.err.println(report);
		Path out = stallFilePath();
		if (out == null) return;
		try {
			Files.write(out, report.getBytes(StandardCharsets.UTF_8));
			System.err.println(PREFIX + " thread dump also written to " + out.toAbsolutePath());
		} catch (Throwable ignored) {
			// 落盘只是加分项：读不到磁盘不应改变看门狗的行为。
		}
	}

	/**
	 * 落盘路径 {@code build/remold-stall-<ts>.txt}；目录不存在时返回 {@code null}
	 * （不创建目录——引擎不该在用户工程里凭空造目录）。
	 */
	public static Path stallFilePath() {
		try {
			Path dir = Paths.get("build");
			if (!Files.isDirectory(dir)) return null;
			String ts = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
			return dir.resolve("remold-stall-" + ts + ".txt");
		} catch (Throwable t) {
			return null;
		}
	}

	private static ScheduledExecutorService scheduler() {
		ScheduledExecutorService s = scheduler;
		if (s != null) return s;
		synchronized (BatchWatchdog.class) {
			if (scheduler == null) {
				scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
					Thread t = new Thread(r, "Remold-BatchWatchdog");
					t.setDaemon(true);
					return t;
				});
			}
			return scheduler;
		}
	}

	private void cancelTaskLocked() {
		if (task != null) {
			task.cancel(false);
			task = null;
		}
	}
}
