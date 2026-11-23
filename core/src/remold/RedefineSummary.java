package remold;

import java.lang.instrument.ClassDefinition;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一轮热更的计数口径：把"本轮真实改动 / 实际生效 / 无实质变更 / 跳过"四个数拆开，各自独立。
 *
 * <p><b>为什么必须拆开</b>：{@code redefineClasses} 的入参是"本批要提交的定义"，它既包含用户真正改过的类，
 * 也包含仅因磁盘文件被重写而被重新读取、字节与运行期完全一致的类（无实质变更）。把入参个数当成
 * "成功重定义类数"会同时产生两种误导：</p>
 * <ul>
 *   <li><b>假成功</b>：用户改的类全部被门拒绝时，只要同批里有无关的无变更类被重新提交，
 *       日志仍然打 {@code HotSwap successful: N classes redefined}，而运行态仍是旧代码；</li>
 *   <li><b>计数虚高</b>：无实质变更的类被算成"重定义了 N 个类"，与用户的编辑量无关。</li>
 * </ul>
 *
 * <p>判据口径：{@code changed} 以"磁盘字节 vs 运行期基线逐字节比较"为准（在任何对齐/补丁改写之前取），
 * 且按类名去重；{@code applied} 只数<b>进了 redefineClasses 且成功</b>的变更类；{@code noop} 数入参里
 * 字节未变的类；{@code skipped} 由跳过台账提供（含被门拒绝者）。</p>
 *
 * <p>边界：本类只做计数与措辞，不做判决——是否允许某类进入本批由各门决定（见
 * {@code docs/status.md}）。{@code changedNames == null} 表示调用方未提供变更集（旧入口），
 * 此时退化为"入参即变更"，不产生假成功语义。</p>
 */
final class RedefineSummary {

	/** 本轮去重后的真实改动类数（含被跳过者）。 */
	final int changed;
	/** 其中真正进了 {@code redefineClasses} 并成功的类数。 */
	final int applied;
	/** 进了 {@code redefineClasses} 但字节与运行期基线一致的类数（无实质变更）。 */
	final int noop;
	/** 本轮被跳过/拒绝的类数。 */
	final int skipped;

	private RedefineSummary(int changed, int applied, int noop, int skipped) {
		this.changed = changed;
		this.applied = applied;
		this.noop = noop;
		this.skipped = skipped;
	}

	/**
	 * @param changedNames        本轮真实改动的类名（可为 {@code null} = 调用方未提供）
	 * @param appliedDefinitions  本批真正重定义成功的定义
	 * @param skipped             本轮跳过台账里的类数
	 */
	static RedefineSummary of(Collection<String> changedNames, List<ClassDefinition> appliedDefinitions,
	                          int skipped) {
		List<ClassDefinition> defs = appliedDefinitions == null ? List.of() : appliedDefinitions;
		if (changedNames == null) {
			// 旧入口：没有变更集信息时不得凭空判断"无实质变更"，一律按变更计（宁可虚高，不可假成功）。
			return new RedefineSummary(defs.size(), defs.size(), 0, skipped);
		}
		Set<String> changed = new LinkedHashSet<>(changedNames);
		// 按类名去重：两阶段路径会把同一类分别提交给阶段 A / 阶段 C，按调用次数累加会把
		// "2 个类" 报成 4 个（本类的存在意义之一就是堵住这种按阶段累加）。
		Set<String> appliedNames = new LinkedHashSet<>();
		Set<String> noopNames = new LinkedHashSet<>();
		for (ClassDefinition def : defs) {
			if (def == null || def.getDefinitionClass() == null) continue;
			String name = def.getDefinitionClass().getName();
			if (changed.contains(name)) appliedNames.add(name);
			else noopNames.add(name);
		}
		return new RedefineSummary(changed.size(), appliedNames.size(), noopNames.size(), skipped);
	}

	/** 本轮没有任何<b>真实改动</b>生效：此时禁止出现"成功"字样。 */
	boolean nothingTookEffect() {
		return applied == 0;
	}

	/**
	 * 摘要行：需要发声时返回该行，无事可报时返回 {@code null}。
	 *
	 * <p>三档语义：</p>
	 * <ul>
	 *   <li>{@code applied > 0} → 成功行（"成功"只能出现在这一档）；</li>
	 *   <li>{@code applied == 0} 且本轮<b>确有</b>改动或被跳过项 → 零生效行（{@code warn} 级）；</li>
	 *   <li>{@code applied == 0 && changed == 0 && skipped == 0} → {@code null}（纯重扫/无实质变更，
	 *       发声只会制造噪音——启动期首轮就是这种形态）。</li>
	 * </ul>
	 * <b>红线</b>：{@code applied == 0} 时返回的行里不得出现"成功"字样。
	 */
	String line() {
		if (!nothingTookEffect()) return successLine();
		if (changed == 0 && skipped == 0) return null;
		return noEffectLine();
	}

	/** 该摘要行是否必须以 {@code warn} 级输出（零生效 = 用户改动没落地）。 */
	boolean warnLevel() {
		return nothingTookEffect();
	}

	/** 成功行（仅当 {@link #nothingTookEffect()} 为 {@code false} 时可用）。 */
	String successLine() {
		StringBuilder sb = new StringBuilder("HotSwap successful: ").append(applied).append(" class(es) redefined.");
		if (noop > 0) {
			sb.append(' ').append(noop).append(" unchanged class(es) were redefined as no-ops.");
		}
		if (skipped > 0) {
			sb.append(' ').append(skipped).append(" class(es) were skipped this round — listed below.");
		}
		return sb.toString();
	}

	/** 零生效行：明确报"没有真实改动落地"，并点名跳过名单（调用方随后输出名单）。 */
	String noEffectLine() {
		StringBuilder sb = new StringBuilder("[HOTSWAP-SUMMARY] 0 changed class(es) took effect this round")
			.append(" — nothing reached the JVM.");
		if (noop > 0) {
			sb.append(' ').append(noop).append(" unchanged class(es) were redefined as no-ops.");
		}
		if (skipped > 0) {
			sb.append(" The classes below did not take effect:");
		}
		return sb.toString();
	}

	/** 单行计数摘要（批量性能行使用）。 */
	String counters() {
		return "changed=" + changed + ", applied=" + applied + ", noop=" + noop + ", skipped=" + skipped;
	}

	@Override
	public String toString() {
		return "RedefineSummary{" + counters() + '}';
	}
}
