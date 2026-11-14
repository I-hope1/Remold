package remold.mctoast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import remold.HotswapBatchReport;
import remold.HotswapBatchReport.InitFixStats;
import remold.HotswapBatchReport.MixinChange;

/**
 * HotSwap Toast 的纯展示模型：把 {@link HotswapBatchReport} 整理成「标题 + 最多 3 行摘要」。
 *
 * <p>本类不依赖 Minecraft，因此可在普通 JVM 单元测试中直接断言格式化行为。所有展示文案均为
 * ASCII 英文，以确保跨终端、跨平台字体下都能清晰显示。</p>
 */
public final class ToastModel {

	/** Toast 正文摘要行的硬上限，避免长批次撑出卡片。 */
	public static final int MAX_SUMMARY_LINES = 3;

	private final String title;
	private final List<String> summaryLines;

	private ToastModel(String title, List<String> summaryLines) {
		this.title = title;
		this.summaryLines = Collections.unmodifiableList(new ArrayList<>(summaryLines));
	}

	public String getTitle() {
		return title;
	}

	public List<String> getSummaryLines() {
		return summaryLines;
	}

	/** 从 Agent 批次报告构建展示模型。 */
	public static ToastModel from(HotswapBatchReport report) {
		if (report == null) {
			return from(HotswapBatchReport.UNKNOWN_ELAPSED, Collections.emptyList(),
				Collections.emptyList(), InitFixStats.EMPTY);
		}
		return from(report.getElapsedMillis(), report.getRedefinedClasses(),
			report.getMixinChanges(), report.getInitFixStats());
	}

	/**
	 * 从结构化批次字段构建展示模型。
	 *
	 * @param elapsedMillis    批次耗时（毫秒）；{@code < 0} 表示未知
	 * @param redefinedClasses 本批成功重定义的类名（可空）
	 * @param mixins           Mixin 变更列表（可空）
	 * @param initFix          InitFix 统计（可空）
	 */
	public static ToastModel from(long elapsedMillis, List<String> redefinedClasses,
	                              List<MixinChange> mixins, InitFixStats initFix) {
		List<String> classes = redefinedClasses == null ? Collections.emptyList() : redefinedClasses;
		List<MixinChange> mixinList = mixins == null ? Collections.emptyList() : mixins;

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
		if (lines.isEmpty()) {
			lines.add("No observable reload changes");
		}
		return new ToastModel("[HotSwap] " + formatElapsed(elapsedMillis), lines);
	}

	static String formatElapsed(long elapsedMillis) {
		if (elapsedMillis < 0) {
			return "Elapsed unknown";
		}
		return elapsedMillis + " ms";
	}

	private static String formatClasses(List<String> classes) {
		if (classes.size() == 1) {
			return "1 class reloaded: " + simpleName(classes.get(0));
		}
		if (classes.size() <= 3) {
			List<String> names = new ArrayList<>(classes.size());
			for (String name : classes) {
				names.add(simpleName(name));
			}
			return classes.size() + " classes reloaded: " + String.join(", ", names);
		}
		return classes.size() + " classes reloaded";
	}

	static String formatMixins(List<MixinChange> mixins) {
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
		for (MixinChange mixin : mixins) {
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
			return "Mixins (" + mixins.size() + "): delta unknown";
		}
		String delta = formatDelta(uniqueAdded, uniqueRemoved, injectAdded, injectRemoved);
		return delta == null
			? "Mixins (" + mixins.size() + "): no member delta"
			: "Mixins (" + mixins.size() + "): " + delta;
	}

	private static String formatSingleMixin(MixinChange mixin) {
		String name = simpleName(mixin.getMixinClass());
		if (mixin.isDeltaKnown()) {
			String delta = formatDelta(mixin.getUniqueFieldsAdded(), mixin.getUniqueFieldsRemoved(),
				mixin.getInjectHandlersAdded(), mixin.getInjectHandlersRemoved());
			return delta == null
				? "Mixin " + name + ": no member delta"
				: "Mixin " + name + ": " + delta;
		}
		return "Mixin " + name + ": delta unknown (" + mixin.getUniqueFieldCount() + " @Unique / "
			+ mixin.getInjectHandlerCount() + " @Inject)";
	}

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

	static String formatInitFix(InitFixStats initFix) {
		if (initFix == null || !initFix.hasActivity()) {
			return null;
		}
		StringBuilder line = new StringBuilder("InitFix: ");
		if (initFix.getLiveInstances() > 0) {
			line.append(initFix.getLiveInstances())
				.append(initFix.getLiveInstances() == 1 ? " instance / " : " instances / ");
		}
		line.append(initFix.getPatchedFields())
			.append(initFix.getPatchedFields() == 1 ? " field" : " fields");
		List<String> names = initFix.getFieldNames();
		if (!names.isEmpty()) {
			line.append(" (").append(joinLimited(names, 2)).append(')');
		}
		if (initFix.getIncompleteFields() > 0) {
			line.append(", ").append(initFix.getIncompleteFields()).append(" incomplete");
		}
		return line.toString();
	}

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
		return String.join(", ", values.subList(0, limit))
			+ " + " + (values.size() - limit) + " more";
	}

	@Override
	public String toString() {
		return title + " | " + String.join(" | ", summaryLines);
	}
}
