package com.example.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HotSwap Toast 的纯展示模型：把 Agent 侧报告整理成「标题 + 最多 3 行摘要」。
 *
 * <p>本类<b>不依赖 Minecraft</b>，因此可以在普通 JVM 单测里直接断言格式化结果。
 * 它只做两件事：</p>
 * <ol>
 *   <li>把可缺省的数据（耗时未知、没有 Mixin、没有 InitFix）降级成明确的文字，
 *       而不是编造 0 ms / 0 个实例；</li>
 *   <li>把行数硬性限制为 3 行，避免长类名列表把卡片撑爆。</li>
 * </ol>
 *
 * <p>数据来源契约见 {@code remold.HotswapBatchReport}：{@code deltaKnown=false} 表示该 Mixin
 * 没有可比较的上一次版本，此时不得展示增量，只能展示当前总量。</p>
 */
public final class HotSwapToastModel {

	/** 摘要行上限。 */
	public static final int MAX_SUMMARY_LINES = 3;

	private final String title;
	private final List<String> summaryLines;

	private HotSwapToastModel(String title, List<String> summaryLines) {
		this.title = title;
		this.summaryLines = Collections.unmodifiableList(new ArrayList<>(summaryLines));
	}

	public String getTitle() {
		return title;
	}

	public List<String> getSummaryLines() {
		return summaryLines;
	}

	/**
	 * 从结构化批次数据构建模型。
	 *
	 * @param elapsedMillis   批次耗时；{@code < 0} 表示未知
	 * @param redefinedClasses 本批成功重定义的类名（可空）
	 * @param mixins           Mixin 变更（可空）
	 * @param initFix          InitFix 统计（可空）
	 */
	public static HotSwapToastModel from(long elapsedMillis, List<String> redefinedClasses,
	                                     List<MixinSummary> mixins, InitFixSummary initFix) {
		List<String> classes = redefinedClasses == null ? Collections.emptyList() : redefinedClasses;
		List<MixinSummary> mixinList = mixins == null ? Collections.emptyList() : mixins;

		List<String> lines = new ArrayList<>(MAX_SUMMARY_LINES);
		if (!classes.isEmpty()) {
			lines.add(formatClasses(classes));
		}
		String mixinLine = formatMixins(mixinList);
		if (mixinLine != null && lines.size() < MAX_SUMMARY_LINES) {
			lines.add(mixinLine);
		}
		String initFixLine = formatInitFix(initFix);
		if (initFixLine != null && lines.size() < MAX_SUMMARY_LINES) {
			lines.add(initFixLine);
		}
		// 只有“什么都没观测到”时才补一句：绝不臆造耗时或数量。
		if (lines.isEmpty()) {
			lines.add("本批次没有可展示的数据");
		}
		return new HotSwapToastModel("[HotSwap] " + formatElapsed(elapsedMillis), lines);
	}

	/**
	 * 旧版 Agent（v0.2.5 及更早）只能提供 {@code afterInternalBatch(List<ClassDefinition>)}。
	 * 此时耗时、Mixin 增量与 InitFix 规模都拿不到，必须明确标注，而不是填 0。
	 */
	public static HotSwapToastModel legacy(List<String> redefinedClasses) {
		List<String> classes = redefinedClasses == null ? Collections.emptyList() : redefinedClasses;
		List<String> lines = new ArrayList<>();
		lines.add(classes.isEmpty() ? "0 个类重定义" : formatClasses(classes));
		lines.add("旧版 Agent：耗时 / Mixin / InitFix 数据不可用");
		return new HotSwapToastModel("[HotSwap] 热更完成", lines);
	}

	static String formatElapsed(long elapsedMillis) {
		if (elapsedMillis < 0) {
			return "耗时未知";
		}
		return elapsedMillis + " ms";
	}

	private static String formatClasses(List<String> classes) {
		if (classes.size() == 1) {
			return "1 个类已热更：" + simpleName(classes.get(0));
		}
		if (classes.size() <= 3) {
			List<String> names = new ArrayList<>(classes.size());
			for (String name : classes) {
				names.add(simpleName(name));
			}
			return classes.size() + " 个类已热更：" + String.join(", ", names);
		}
		return classes.size() + " 个类已热更";
	}

	static String formatMixins(List<MixinSummary> mixins) {
		if (mixins == null || mixins.isEmpty()) {
			return null;
		}
		if (mixins.size() == 1) {
			return formatSingleMixin(mixins.get(0));
		}
		int uniqueAdded = 0;
		int uniqueRemoved = 0;
		int injectAdded = 0;
		int injectRemoved = 0;
		boolean deltaKnown = true;
		for (MixinSummary mixin : mixins) {
			if (!mixin.isDeltaKnown()) {
				deltaKnown = false;
				continue;
			}
			uniqueAdded += mixin.getUniqueFieldsAdded();
			uniqueRemoved += mixin.getUniqueFieldsRemoved();
			injectAdded += mixin.getInjectHandlersAdded();
			injectRemoved += mixin.getInjectHandlersRemoved();
		}
		if (!deltaKnown) {
			return "Mixin " + mixins.size() + " 个（增量未知）";
		}
		String delta = formatDelta(uniqueAdded, uniqueRemoved, injectAdded, injectRemoved);
		return delta == null
			? "Mixin " + mixins.size() + " 个（无成员增量）"
			: "Mixin " + mixins.size() + " 个（" + delta + "）";
	}

	private static String formatSingleMixin(MixinSummary mixin) {
		String name = simpleName(mixin.getMixinClass());
		if (mixin.isDeltaKnown()) {
			String delta = formatDelta(mixin.getUniqueFieldsAdded(), mixin.getUniqueFieldsRemoved(),
				mixin.getInjectHandlersAdded(), mixin.getInjectHandlersRemoved());
			return delta == null
				? "Mixin " + name + "：无成员增量"
				: "Mixin " + name + "：" + delta;
		}
		return "Mixin " + name + "：增量未知（当前 " + mixin.getUniqueFieldCount() + " @Unique / "
			+ mixin.getInjectHandlerCount() + " @Inject）";
	}

	/** 例：{@code +1 @Unique / +1 @Inject}；没有任何变化时返回 {@code null}。 */
	static String formatDelta(int uniqueAdded, int uniqueRemoved, int injectAdded, int injectRemoved) {
		List<String> parts = new ArrayList<>(4);
		if (uniqueAdded > 0) {
			parts.add("+" + uniqueAdded + " @Unique");
		}
		if (uniqueRemoved > 0) {
			parts.add("-" + uniqueRemoved + " @Unique");
		}
		if (injectAdded > 0) {
			parts.add("+" + injectAdded + " @Inject");
		}
		if (injectRemoved > 0) {
			parts.add("-" + injectRemoved + " @Inject");
		}
		return parts.isEmpty() ? null : String.join(" / ", parts);
	}

	static String formatInitFix(InitFixSummary initFix) {
		if (initFix == null || !initFix.hasActivity()) {
			return null;
		}
		StringBuilder line = new StringBuilder("InitFix：");
		if (initFix.getLiveInstances() > 0) {
			line.append(initFix.getLiveInstances()).append(" 个实例 / ");
		}
		line.append(initFix.getPatchedFields()).append(" 字段");
		List<String> names = initFix.getFieldNames();
		if (!names.isEmpty()) {
			line.append("（").append(joinLimited(names, 2)).append("）");
		}
		if (initFix.getIncompleteFields() > 0) {
			line.append("，").append(initFix.getIncompleteFields()).append(" 个未完成");
		}
		return line.toString();
	}

	/** 取类名最后一段，避免包名挤占卡片宽度。 */
	static String simpleName(String className) {
		if (className == null || className.isEmpty()) {
			return "?";
		}
		int dot = className.lastIndexOf('.');
		return dot < 0 ? className : className.substring(dot + 1);
	}

	private static String joinLimited(List<String> values, int limit) {
		if (values.size() <= limit) {
			return String.join(", ", values);
		}
		return String.join(", ", values.subList(0, limit)) + " 等 " + values.size() + " 个";
	}

	@Override
	public String toString() {
		return title + " | " + String.join(" | ", summaryLines);
	}

	/** 单个 Mixin 的可展示数据；字段语义与 {@code HotswapBatchReport.MixinChange} 一致。 */
	public static final class MixinSummary {
		private final String mixinClass;
		private final boolean deltaKnown;
		private final int uniqueFieldsAdded;
		private final int uniqueFieldsRemoved;
		private final int injectHandlersAdded;
		private final int injectHandlersRemoved;
		private final int uniqueFieldCount;
		private final int injectHandlerCount;

		public MixinSummary(String mixinClass, boolean deltaKnown,
		                    int uniqueFieldsAdded, int uniqueFieldsRemoved,
		                    int injectHandlersAdded, int injectHandlersRemoved,
		                    int uniqueFieldCount, int injectHandlerCount) {
			this.mixinClass = mixinClass;
			this.deltaKnown = deltaKnown;
			this.uniqueFieldsAdded = nonNegative(uniqueFieldsAdded);
			this.uniqueFieldsRemoved = nonNegative(uniqueFieldsRemoved);
			this.injectHandlersAdded = nonNegative(injectHandlersAdded);
			this.injectHandlersRemoved = nonNegative(injectHandlersRemoved);
			this.uniqueFieldCount = nonNegative(uniqueFieldCount);
			this.injectHandlerCount = nonNegative(injectHandlerCount);
		}

		public String getMixinClass() {
			return mixinClass;
		}

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
	}

	/** InitFix 的可展示数据；字段语义与 {@code HotswapBatchReport.InitFixStats} 一致。 */
	public static final class InitFixSummary {
		private final List<String> patchedClasses;
		private final int liveInstances;
		private final int patchedFields;
		private final int incompleteFields;
		private final List<String> fieldNames;

		public InitFixSummary(List<String> patchedClasses, int liveInstances, int patchedFields,
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

		public boolean hasActivity() {
			return liveInstances > 0 || patchedFields > 0 || !patchedClasses.isEmpty();
		}
	}

	private static <T> List<T> immutableCopy(List<T> source) {
		if (source == null || source.isEmpty()) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(new ArrayList<>(source));
	}

	private static int nonNegative(int value) {
		return value < 0 ? 0 : value;
	}
}
