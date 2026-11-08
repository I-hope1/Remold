package remold;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次热重载批次的可观测结果快照。
 *
 * <p><b>语义规范：</b></p>
 * <ul>
 *   <li>本类只承载<b>已经观测到的事实</b>：批次耗时、成功重定义的类名、Mixin 模板的增量、
 *       InitFix 本轮的修补规模。拿不到的数据以空列表、{@code 0} 或 {@code -1} 表示，
 *       调用方不得把它们解释成推测值。</li>
 *   <li>不可变：所有列表在构造时做防御性拷贝并包装为只读视图。</li>
 *   <li>本类不持有 {@link Class}、{@link ClassLoader} 或任何宿主对象引用，可以安全地跨线程传递。</li>
 * </ul>
 *
 * <p>批次结束时由 Agent 构建并通过
 * {@link HotswapBatchListener#afterInternalBatchReport(HotswapBatchReport)} 发布。
 * 该回调是 {@link HotswapBatchListener#afterInternalBatch(List)} 的补充，不是替代：
 * 既有监听器继续收到后者，行为不变。</p>
 */
public final class HotswapBatchReport {

	/** 耗时未知时的哨兵值（例如数据源没有提供起始时间）。 */
	public static final long UNKNOWN_ELAPSED = -1L;

	private final long elapsedMillis;
	private final List<String> redefinedClasses;
	private final List<MixinChange> mixinChanges;
	private final InitFixStats initFixStats;

	/**
	 * @param elapsedMillis   批次处理耗时（毫秒）；{@code < 0} 表示未知
	 * @param redefinedClasses 本批成功重定义的类名（点分）；{@code null} 视为空
	 * @param mixinChanges    本批观察到变更的 Mixin 模板；{@code null} 视为空
	 * @param initFixStats    本批 InitFix 的修补规模；{@code null} 视为空
	 */
	public HotswapBatchReport(long elapsedMillis, List<String> redefinedClasses,
	                          List<MixinChange> mixinChanges, InitFixStats initFixStats) {
		this.elapsedMillis = elapsedMillis < 0 ? UNKNOWN_ELAPSED : elapsedMillis;
		this.redefinedClasses = immutableCopy(redefinedClasses);
		this.mixinChanges = immutableCopy(mixinChanges);
		this.initFixStats = initFixStats == null ? InitFixStats.EMPTY : initFixStats;
	}

	/** @return 批次处理耗时（毫秒），或 {@link #UNKNOWN_ELAPSED} */
	public long getElapsedMillis() {
		return elapsedMillis;
	}

	/** @return 本批成功重定义的类名（点分，可能为空） */
	public List<String> getRedefinedClasses() {
		return redefinedClasses;
	}

	/** @return 本批观察到变更的 Mixin 模板（可能为空） */
	public List<MixinChange> getMixinChanges() {
		return mixinChanges;
	}

	/** @return 本批 InitFix 的修补规模（非 {@code null}） */
	public InitFixStats getInitFixStats() {
		return initFixStats;
	}

	/**
	 * 批次是否产生了任何值得展示/记录的活动。
	 *
	 * <p>Mixin 模板变更与 InitFix 修补可能在“没有类被 redefine”的批次里发生
	 * （例如只改 Mixin 类：它本身是不可加载模板，目标类由独立任务重织入）。</p>
	 */
	public boolean hasObservableActivity() {
		return !redefinedClasses.isEmpty() || !mixinChanges.isEmpty() || initFixStats.hasActivity();
	}

	@Override
	public String toString() {
		return "HotswapBatchReport{elapsed=" + (elapsedMillis < 0 ? "unknown" : elapsedMillis + "ms")
			+ ", redefined=" + redefinedClasses.size()
			+ ", mixins=" + mixinChanges.size()
			+ ", initFix=" + initFixStats + '}';
	}

	private static <T> List<T> immutableCopy(List<T> source) {
		if (source == null || source.isEmpty()) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(new ArrayList<>(source));
	}

	/**
	 * 单个 Mixin 模板在本批次中的变更记录。
	 *
	 * <p><b>增量口径</b>：{@link #isDeltaKnown()} 为 {@code true} 时，四个增量字段是
	 * “相对本进程上一次成功热更时该 Mixin 的版本”的成员级差分；为 {@code false} 时
	 * 没有可比较的基线（例如客户端启动后第一次改该 Mixin），四个增量字段恒为 {@code 0}，
	 * 调用方<b>必须</b>退化为只展示类名/总量，不能把 {@code 0} 当作“没有新增”。</p>
	 *
	 * <p>{@link #getUniqueFieldCount()} 与 {@link #getInjectHandlerCount()} 始终是
	 * 新版本里的<b>总量</b>，在增量未知时仍然可用。</p>
	 */
	public static final class MixinChange {
		private final String mixinClass;
		private final boolean deltaKnown;
		private final int uniqueFieldsAdded;
		private final int uniqueFieldsRemoved;
		private final int injectHandlersAdded;
		private final int injectHandlersRemoved;
		private final int uniqueFieldCount;
		private final int injectHandlerCount;
		private final List<String> addedUniqueFields;
		private final List<String> addedInjectHandlers;

		/**
		 * @param mixinClass            Mixin 点分类名
		 * @param deltaKnown            是否存在可比较的上一次版本
		 * @param uniqueFieldsAdded     新增的 {@code @Unique} 字段数；增量未知时传 {@code 0}
		 * @param uniqueFieldsRemoved   删除的 {@code @Unique} 字段数；增量未知时传 {@code 0}
		 * @param injectHandlersAdded   新增的 {@code @Inject} handler 数；增量未知时传 {@code 0}
		 * @param injectHandlersRemoved 删除的 {@code @Inject} handler 数；增量未知时传 {@code 0}
		 * @param uniqueFieldCount      新版本中 {@code @Unique} 字段总数
		 * @param injectHandlerCount    新版本中 {@code @Inject} handler 总数
		 * @param addedUniqueFields     新增 {@code @Unique} 字段名（可空）
		 * @param addedInjectHandlers   新增 {@code @Inject} handler 名（可空）
		 */
		public MixinChange(String mixinClass, boolean deltaKnown,
		                   int uniqueFieldsAdded, int uniqueFieldsRemoved,
		                   int injectHandlersAdded, int injectHandlersRemoved,
		                   int uniqueFieldCount, int injectHandlerCount,
		                   List<String> addedUniqueFields, List<String> addedInjectHandlers) {
			this.mixinClass = mixinClass;
			this.deltaKnown = deltaKnown;
			this.uniqueFieldsAdded = nonNegative(uniqueFieldsAdded);
			this.uniqueFieldsRemoved = nonNegative(uniqueFieldsRemoved);
			this.injectHandlersAdded = nonNegative(injectHandlersAdded);
			this.injectHandlersRemoved = nonNegative(injectHandlersRemoved);
			this.uniqueFieldCount = nonNegative(uniqueFieldCount);
			this.injectHandlerCount = nonNegative(injectHandlerCount);
			this.addedUniqueFields = immutableCopy(addedUniqueFields);
			this.addedInjectHandlers = immutableCopy(addedInjectHandlers);
		}

		public String getMixinClass() {
			return mixinClass;
		}

		/** @return 增量是否可证明；{@code false} 时增量字段无意义 */
		public boolean isDeltaKnown() {
			return deltaKnown;
		}

		public int getUniqueFieldsAdded() {
			return uniqueFieldsAdded;
		}

		public int getUniqueFieldsRemoved() {
			return uniqueFieldsRemoved;
		}

		public int getInjectHandlersAdded() {
			return injectHandlersAdded;
		}

		public int getInjectHandlersRemoved() {
			return injectHandlersRemoved;
		}

		public int getUniqueFieldCount() {
			return uniqueFieldCount;
		}

		public int getInjectHandlerCount() {
			return injectHandlerCount;
		}

		public List<String> getAddedUniqueFields() {
			return addedUniqueFields;
		}

		public List<String> getAddedInjectHandlers() {
			return addedInjectHandlers;
		}

		@Override
		public String toString() {
			return "MixinChange{" + mixinClass + ", deltaKnown=" + deltaKnown
				+ ", unique=" + uniqueFieldCount + " (" + uniqueFieldsAdded + "+/"
				+ uniqueFieldsRemoved + "-), inject=" + injectHandlerCount + " ("
				+ injectHandlersAdded + "+/" + injectHandlersRemoved + "-)}";
		}
	}

	/**
	 * 本批 InitFix 的修补规模。
	 *
	 * <p>只统计 {@code applyPatch} 真正跑完的类。中途熔断（{@code LinkageError}）的类不会出现在这里，
	 * 因此本对象不会把“尝试过”说成“已修补”。</p>
	 *
	 * <p>{@link #getLiveInstances()} 是<b>按声明类分别枚举后求和</b>的目标数：同一个对象若同时是
	 * 多个受补宿主的实例会被重复计入。它回答的是“总共处理了多少个实例位点”，
	 * 不是“堆里有多少个唯一对象”。</p>
	 */
	public static final class InitFixStats {
		public static final InitFixStats EMPTY =
			new InitFixStats(Collections.emptyList(), 0, 0, 0, Collections.emptyList());

		private final List<String> patchedClasses;
		private final int liveInstances;
		private final int patchedFields;
		private final int incompleteFields;
		private final List<String> fieldNames;

		/**
		 * @param patchedClasses   真的执行完补丁的宿主类名
		 * @param liveInstances    枚举到的实例位点数（按类求和）
		 * @param patchedFields    参与修补的字段数（实例 + 静态，去重）
		 * @param incompleteFields 运行期失败或因依赖失败被放弃的字段数
		 * @param fieldNames       参与修补的字段名（去重）
		 */
		public InitFixStats(List<String> patchedClasses, int liveInstances, int patchedFields,
		                    int incompleteFields, List<String> fieldNames) {
			this.patchedClasses = immutableCopy(patchedClasses);
			this.liveInstances = nonNegative(liveInstances);
			this.patchedFields = nonNegative(patchedFields);
			this.incompleteFields = nonNegative(incompleteFields);
			this.fieldNames = immutableCopy(fieldNames);
		}

		public List<String> getPatchedClasses() {
			return patchedClasses;
		}

		public int getLiveInstances() {
			return liveInstances;
		}

		public int getPatchedFields() {
			return patchedFields;
		}

		public int getIncompleteFields() {
			return incompleteFields;
		}

		public List<String> getFieldNames() {
			return fieldNames;
		}

		/** @return 本轮是否真的修补过任何字段 */
		public boolean hasActivity() {
			return patchedFields > 0 || liveInstances > 0 || !patchedClasses.isEmpty();
		}

		@Override
		public String toString() {
			return "InitFixStats{classes=" + patchedClasses.size() + ", instances=" + liveInstances
				+ ", fields=" + patchedFields + ", incomplete=" + incompleteFields + '}';
		}
	}

	private static int nonNegative(int value) {
		return value < 0 ? 0 : value;
	}
}
