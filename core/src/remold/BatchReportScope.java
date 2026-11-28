package remold;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 单个热重载批次的可观测数据收集器（core 内部实现）。
 *
 * <p>生命周期：{@link #begin()} 在批次入口调用，{@link #finish(List)} 在发布回调前调用，
 * 由 {@code RemoldAgent} 负责成对调用。收集器挂在 {@link ThreadLocal} 上而不是静态共享字段：
 * 批次处理、Mixin 模板 reload 与 {@code InitFix.applyPatch} 都在同一个批处理线程上串行执行，
 * 而外部重定义 / 其它线程的写入不应混进本批。</p>
 *
 * <p><b>与监听器的关系</b>：没有注册任何 {@link HotswapBatchListener} 时，本类仍然会记录
 * 少量计数（批次开始建一个 State，字段名去重），不会改变重定义、布局门或 InitFix 的任何行为。</p>
 *
 * <p>本类不是 API：插件（plugin-mixin 等）只调用公开的记录方法；报告结构的定义在
 * {@link HotswapBatchReport}。</p>
 */
public final class BatchReportScope {

	private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

	private BatchReportScope() {
	}

	/** 开始一个新批次；若当前线程已有未结束的批次，则压栈保留。 */
	public static void begin() {
		State previous = CURRENT.get();
		CURRENT.set(new State(System.nanoTime(), previous));
	}

	/**
	 * 结束当前批次并生成报告。
	 *
	 * @param redefinedClasses 本批成功重定义的类名
	 * @return 报告；当前线程没有活动批次时返回 {@code null}
	 */
	static HotswapBatchReport finish(List<String> redefinedClasses) {
		State state = CURRENT.get();
		if (state == null) {
			return null;
		}
		restore(state);
		long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - state.startNanos);
		return new HotswapBatchReport(elapsed, redefinedClasses,
			new ArrayList<>(state.mixinChanges), state.toInitFixStats());
	}

	/** 丢弃当前批次（异常路径），不生成报告。若存在外层批次则恢复外层。 */
	public static void discard() {
		State state = CURRENT.get();
		if (state != null) {
			restore(state);
		}
	}

	/**
	 * 记录一个 Mixin 模板的变更。无活动批次时静默忽略（例如外部重定义路径）。
	 *
	 * @param change 变更记录，{@code null} 忽略
	 */
	public static void recordMixinChange(HotswapBatchReport.MixinChange change) {
		if (change == null) {
			return;
		}
		State state = CURRENT.get();
		if (state != null) {
			state.mixinChanges.add(change);
		}
	}

	/**
	 * 记录一个宿主类跑完 InitFix 补丁后的规模。
	 *
	 * <p>只在 {@code applyPatch} 正常跑完时调用；中途熔断的类不会上报，
	 * 避免把“尝试过”记成“已修补”。</p>
	 *
	 * @param className        宿主点分类名
	 * @param liveInstances    枚举到的实例位点数
	 * @param fieldNames       参与修补的字段名（可空）
	 * @param incompleteFields 失败/被放弃的字段数
	 */
	public static void recordInitFix(String className, int liveInstances,
	                                 List<String> fieldNames, int incompleteFields) {
		recordInitFix(className, liveInstances, fieldNames, incompleteFields,
			0, 0, 0, 0, null, null);
	}

	/**
	 * 记录一个宿主的 InitFix 规模，并附加值类型 {@code new} 初值重放
	 * （{@code docs/initfix/14-value-type-new-replay.md} §8）的四个计数与明细列表。
	 *
	 * <p><b>分析期也可调用</b>：分析阶段能确定"被拒"计数（运行期 applied/skipped/failed 由驱动
	 * 在 {@code applyPatch} 之后补齐），因此本重载允许分两次累加。{@code fieldNames} 为 {@code null}
	 * 表示不追加字段名，明细列表 {@code null} 视为空。</p>
	 *
	 * @param className                 宿主点分类名
	 * @param liveInstances             枚举到的实例位点数
	 * @param fieldNames                参与修补的字段名（可空）
	 * @param incompleteFields          失败/被放弃的字段数
	 * @param valueReplayApplied        值类型重放写入成功数
	 * @param valueReplaySkipped        值类型重放跳过数
	 * @param valueReplayFailed         值类型重放失败数
	 * @param valueReplayRejected       值类型重放分析期拒绝数
	 * @param valueReplayFailedFields   失败明细（{@code 点分类名.字段: 原因}，可空）
	 * @param valueReplayRejectedFields 拒绝明细（{@code 点分类名.字段: 判决码/原因}，可空）
	 */
	public static void recordInitFix(String className, int liveInstances,
	                                 List<String> fieldNames, int incompleteFields,
	                                 int valueReplayApplied, int valueReplaySkipped,
	                                 int valueReplayFailed, int valueReplayRejected,
	                                 List<String> valueReplayFailedFields,
	                                 List<String> valueReplayRejectedFields) {
		if (className == null) {
			return;
		}
		State state = CURRENT.get();
		if (state == null) {
			return;
		}
		state.patchedClasses.add(className);
		state.liveInstances += Math.max(0, liveInstances);
		state.incompleteFields += Math.max(0, incompleteFields);
		if (fieldNames != null) {
			state.fieldNames.addAll(fieldNames);
		}
		state.valueReplayApplied += Math.max(0, valueReplayApplied);
		state.valueReplaySkipped += Math.max(0, valueReplaySkipped);
		state.valueReplayFailed += Math.max(0, valueReplayFailed);
		state.valueReplayRejected += Math.max(0, valueReplayRejected);
		if (valueReplayFailedFields != null) {
			state.valueReplayFailedFields.addAll(valueReplayFailedFields);
		}
		if (valueReplayRejectedFields != null) {
			state.valueReplayRejectedFields.addAll(valueReplayRejectedFields);
		}
	}

	/** 当前线程是否有活动批次（测试/诊断用）。 */
	public static boolean isActive() {
		return CURRENT.get() != null;
	}

	private static void restore(State state) {
		if (state.previous != null) {
			CURRENT.set(state.previous);
		} else {
			CURRENT.remove();
		}
	}

	private static final class State {
		final long startNanos;
		final State previous;
		final List<HotswapBatchReport.MixinChange> mixinChanges = new ArrayList<>();
		final Set<String> patchedClasses = new LinkedHashSet<>();
		final Set<String> fieldNames = new LinkedHashSet<>();
		final List<String> valueReplayFailedFields = new ArrayList<>();
		final List<String> valueReplayRejectedFields = new ArrayList<>();
		int liveInstances;
		int incompleteFields;
		int valueReplayApplied;
		int valueReplaySkipped;
		int valueReplayFailed;
		int valueReplayRejected;

		State(long startNanos, State previous) {
			this.startNanos = startNanos;
			this.previous = previous;
		}

		HotswapBatchReport.InitFixStats toInitFixStats() {
			if (patchedClasses.isEmpty()) {
				return HotswapBatchReport.InitFixStats.EMPTY;
			}
			return new HotswapBatchReport.InitFixStats(
				new ArrayList<>(patchedClasses), liveInstances, fieldNames.size(),
				incompleteFields, new ArrayList<>(fieldNames),
				valueReplayApplied, valueReplaySkipped, valueReplayFailed, valueReplayRejected,
				valueReplayFailedFields, valueReplayRejectedFields);
		}
	}
}
