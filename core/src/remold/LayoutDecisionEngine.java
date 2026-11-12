package remold;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import remold.RemoldAgent.LayoutAction;
import remold.RemoldAgent.LayoutDecision;
import remold.RemoldAgent.Liveness;
import remold.RemoldAgent.RemovedMemberScan;
import remold.RemoldAgent.RoundSkips;
import remold.offset.SuperclassDriftGate;

/**
 * 实例状态布局门的<b>纯判决</b>：给定已算出的差异信息，决定放行 / 豁免 / 拒绝。
 *
 * <p>本类不读盘、不扫堆、不碰 {@link RemoldAgent} 的任何可变状态 —— 所有判据都从参数进来，
 * 判决从返回值出去。这样"为什么拒绝"可以脱离热更流水线单独推演与单测。</p>
 *
 * <p><b>为什么判决要独立成类</b>：判据的分支密度高（删除 / 改类型 / 改静态性 / 方法删除 /
 * 父类漂移 × 存活实例三态 × 批次外读者 × {@code @RemoldReinit} 授权 × {@code reject/warn/off}），
 * 且每条分支都对应一种运行期失败形态（{@code NoSuchFieldError}、{@code AbstractMethodError}、
 * 静默分派转移、静默读零值）。把它和"取字节、执行动作、回滚事务"的接线混在同一个类里，
 * 任何一次判据修订都必须重读周围 500 行流水线代码。</p>
 *
 * <p><b>类型仍留在 {@link RemoldAgent}</b>：{@link LayoutDecision}、{@link LayoutAction}、
 * {@link Liveness}、{@link RemovedMemberScan} 是对外可见的判决词汇（回归用例与宿主代码按
 * {@code RemoldAgent.LayoutDecision} 绑定），本类只搬走算法，不搬走词汇表。</p>
 */
final class LayoutDecisionEngine {

	private LayoutDecisionEngine() { }

	/** 由点分类名求宿主类名：{@code Foo$1$2 -> Foo}；非匿名（含具名内部类 {@code Foo$Bar}）原样返回。 */
	static String hostNameOf(String className) {
		return className.replaceAll("\\$\\d+(\\$\\d+)*$", "");
	}

	/**
	 * 便捷重载：先 diff 两版字节，再交给核心纯函数。diff 失败 / 取不到字节 → SKIP。
	 */
	static LayoutDecision decide(String className, byte[] oldBytes, byte[] newBytes,
	                             String mode, Liveness liveness, Set<String> batchNames) {
		return decide(className, oldBytes, newBytes, mode, liveness, batchNames,
		              RemovedMemberScan.NOT_SCANNED);
	}

	/** 便捷重载（带批次外读者判定）。 */
	static LayoutDecision decide(String className, byte[] oldBytes, byte[] newBytes,
	                             String mode, Liveness liveness, Set<String> batchNames,
	                             RemovedMemberScan removedFieldReaders) {
		return decide(className, oldBytes, newBytes, mode, liveness, batchNames,
		              removedFieldReaders, null);
	}

	/** 便捷重载（带批次外读者判定与父类字段漂移判定）。 */
	static LayoutDecision decide(String className, byte[] oldBytes, byte[] newBytes,
	                             String mode, Liveness liveness, Set<String> batchNames,
	                             RemovedMemberScan removedFieldReaders,
	                             SuperclassDriftGate.DriftDecision drift) {
		if (LayoutGate.MODE_OFF.equals(mode)) {
			return new LayoutDecision(LayoutAction.PASS, null, hostNameOf(className), Set.of());
		}
		if (oldBytes == null || newBytes == null) {
			return new LayoutDecision(LayoutAction.SKIP, null, hostNameOf(className), Set.of());
		}
		List<String> changed;
		Set<String> reinitFields = Set.of();
		Map<String, String> unextractableReinit = Map.of();
		try {
			ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(oldBytes, newBytes);
			changed = diff.changedFields;
			reinitFields = diff.reinitFields;
			if (!reinitFields.isEmpty() && diff.newClass != null) {
				unextractableReinit = remold.ref.InitFix.unextractableReinitFields(null, diff.newClass, reinitFields);
			}
		} catch (Throwable t) {
			return new LayoutDecision(LayoutAction.SKIP, null, hostNameOf(className), Set.of());
		}
		return decide(className, changed, mode, liveness, batchNames, removedFieldReaders, drift, reinitFields, unextractableReinit);
	}

	/**
	 * 核心纯函数：给定已算出的 {@code changedFields}、模式、存活实例判定与批次类名集，判定动作。
	 *
	 * <p>把接线里最容易出错的部分（拒绝要带哪些类、off/skip/waive 出口）抽成可单测的纯逻辑。
	 * 调用位置（必须在 {@code applyRedefinitions} 之前）由 {@code applyRedefineLayoutGate} 的接线守卫。</p>
	 *
	 * <ul>
	 *   <li>{@code OFF} → PASS，不解析。</li>
	 *   <li>{@code changedFields == null}（无法 diff）→ SKIP（调用方负责告警）。</li>
	 *   <li>字段兼容（含纯新增）→ PASS。</li>
	 *   <li>不兼容 + {@code warn} → PASS，但 {@code layout} 非兼容（调用方强告警）。</li>
	 *   <li>不兼容 + {@code reject}（默认）+ <b>无存活实例且非静态字段</b> → WAIVE。</li>
	 *   <li>不兼容 + {@code reject} + 有/未知存活实例，或涉及静态字段 → REJECT +
	 *       {@code dropped} = 宿主 + 匿名子类。</li>
	 * </ul>
	 *
	 * <p><b>为什么有一条静态例外</b>：实例字段的旧值只在实例上，没有实例就无所谓"读到旧值/零值"；
	 * 静态字段是类级别共享的，类只要加载过就可能已被写入，故不因无实例而放行。</p>
	 */
	static LayoutDecision decide(String className, List<String> changedFields,
	                             String mode, Liveness liveness, Set<String> batchNames) {
		return decide(className, changedFields, mode, liveness, batchNames,
		              RemovedMemberScan.NOT_SCANNED);
	}

	/**
	 * 带"批次外读者"判定的版本。
	 *
	 * <p><b>删除字段的判据不是存活实例</b>：删掉字段后，存活实例只是丢了一个没人读的槽位，
	 * 真正会出事的是<b>改动后还有代码解析得到它</b>——那会在未来任意一次执行时抛
	 * {@code NoSuchFieldError}，且抛点在热更事务之外。所以本条只看 {@code removedFieldReaders}：
	 * 读者为空则放行，有读者则拒绝并列出类名。</p>
	 *
	 * <p>其余不兼容（改类型 / 改静态性）与删除的差别在于：它们的旧值会被<b>静默</b>丢弃或错读，
	 * 读者集合证明不了安全，因此仍按存活实例判。</p>
	 *
	 * <p>{@code removedFieldReaders.scanned() == false} 时必须退回存活实例判据——
	 * 把"没扫"当成"没读者"是本条最危险的读法。</p>
	 */
	static LayoutDecision decide(String className, List<String> changedFields,
	                             String mode, Liveness liveness, Set<String> batchNames,
	                             RemovedMemberScan scan) {
		return decide(className, changedFields, mode, liveness, batchNames, scan, null);
	}

	/** 带"批次外读者"判定与"父类实例字段漂移"判定的版本。 */
	static LayoutDecision decide(String className, List<String> changedFields,
	                             String mode, Liveness liveness, Set<String> batchNames,
	                             RemovedMemberScan scan,
	                             SuperclassDriftGate.DriftDecision drift) {
		return decide(className, changedFields, mode, liveness, batchNames, scan, drift, Set.of());
	}

	/**
	 * 带"批次外读者"判定、"父类实例字段漂移"判定与"@RemoldReinit 授权重置字段"的全功能版本。
	 */
	static LayoutDecision decide(String className, List<String> changedFields,
	                             String mode, Liveness liveness, Set<String> batchNames,
	                             RemovedMemberScan scan,
	                             SuperclassDriftGate.DriftDecision drift,
	                             Set<String> reinitFields) {
		return decide(className, changedFields, mode, liveness, batchNames, scan, drift, reinitFields, Map.of());
	}

	/**
	 * 带"初值提取可行性核验"的完整版本（防 B4 授权放行后丢值）。
	 */
	static LayoutDecision decide(String className, List<String> changedFields,
	                             String mode, Liveness liveness, Set<String> batchNames,
	                             RemovedMemberScan scan,
	                             SuperclassDriftGate.DriftDecision drift,
	                             Set<String> reinitFields,
	                             Map<String, String> unextractableReinitFields) {
		String host = hostNameOf(className);
		if (LayoutGate.MODE_OFF.equals(mode)) {
			return new LayoutDecision(LayoutAction.PASS, null, host, Set.of());
		}
		if (changedFields == null) {
			return new LayoutDecision(LayoutAction.SKIP, null, host, Set.of());
		}
		if (scan == null) {
			scan = RemovedMemberScan.NOT_SCANNED;
		}
		LayoutGate.Result res = LayoutGate.checkChangedFields(changedFields);

		// 编译期常量（{@code ConstantValue}，JLS §13.4.9）<b>不阻断</b>：javac 把值内联进读者的
		// {@code ldc} / {@code bipush}，那些读者在运行期<b>根本不解析这个字段</b>，删掉它零异常；
		// 而未内联的读者常量池里仍有 {@code Fieldref}，会被 hits 正常抓到。
		// "内联"既不是错误，也不需要读者改代码——它本来就是 Java 常量的既有语义（改常量值同样
		// 要全量重编才生效）。调用方把 constantFields 打进日志即可，见 §16.1 的修订说明。

		// 方法删除不经过 LayoutGate（{@code changedFields} 只描述字段），必须单独判，而且要放在
		// compatible 早退<b>之前</b>——本轮完全可能只删了方法、字段一个都没动。
		//
		// 判据与字段同源（"批次外读者为空"），但失败形态不同：可能是 NoSuchMethodError、
		// AbstractMethodError，也可能是<b>静默的分派转移</b>（实测见 docs/initfix/07-p05-probe-results.md §15）。
		// 三种都拒绝——分派转移不抛异常，但行为已经变了。
		if (scan.removedMethods() && !LayoutGate.MODE_WARN.equals(mode)) {
			if (!scan.scanned()) {
				// 方法删除没有存活实例判据可以退：删除的影响与"当前有没有实例"正交。
				return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames),
					"method(s) removed but the reader scan could not complete — refusing because"
					+ " method removal has no liveness fallback");
			}
			if (scan.hasFindings()) {
				return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames),
					"method(s) removed and " + describeFindings(scan) + " still resolve to them");
			}
		}

		// 物理内存字段偏移漂移检查（S1 门控）：
		// 即使本类自身字段变动看起来是兼容新增（COMPATIBLE），但若引发了本类或子孙类实例字段漂移且存在持有者，则必须拦截
		if (drift != null && !drift.isSafe()) {
			LayoutGate.Verdict v = drift.formatReport().contains("[SUPERCLASS_FIELD_DRIFT]")
				? LayoutGate.Verdict.SUPERCLASS_FIELD_DRIFT
				: LayoutGate.Verdict.LAYOUT_DRIFT;
			LayoutGate.Result driftRes = new LayoutGate.Result(
				v,
				drift.formatReport()
			);
			if (LayoutGate.MODE_WARN.equals(mode)) {
				return new LayoutDecision(LayoutAction.PASS, driftRes, host, Set.of());
			}
			return new LayoutDecision(LayoutAction.REJECT, driftRes, host, droppedOf(host, batchNames), drift.formatReport());
		}

		if (res.compatible() || LayoutGate.MODE_WARN.equals(mode)) {
			return new LayoutDecision(LayoutAction.PASS, res, host, Set.of());
		}

		boolean staticChange = LayoutGate.hasStaticFieldChange(changedFields);

		// 枚举常量顺序变更（CHANGED_ENUM_ORDER）安全门控（R6b）：
		if (res.verdict() == LayoutGate.Verdict.CHANGED_ENUM_ORDER) {
			return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames),
				"enum constant order changed (" + res.detail() + ") — existing enum instances retain their"
				+ " original ordinals; reordering causes switch-on-enum and EnumMap to silently misroute"
				+ " (revert the enum constant order or restart to continue)");
		}

		// 枚举常量集合变更（ENUM_CONSTANT_SET_CHANGED）安全门控：
		// <clinit> 在热更时不重跑，新增常量不会被构造（恒 null、$VALUES 不扩容），删除的常量仍留在
		// 旧 $VALUES 数组里。继续 redefine 只会得到"HotSwap successful 但源码变更没生效"的假成功。
		if (res.verdict() == LayoutGate.Verdict.ENUM_CONSTANT_SET_CHANGED) {
			return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames),
				"enum constant set changed (" + res.detail() + ") — redefinition does not re-run <clinit>:"
				+ " an added constant would stay null and a removed one stays in the old $VALUES array, so"
				+ " values()/valueOf()/EnumMap/EnumSet and $SwitchMap would silently disagree with the source"
				+ " (revert the enum constant change or restart to continue)");
		}

		// Record 类新增组件（RECORD_COMPONENT_ADDED）安全门控（R1）：
		if (res.verdict() == LayoutGate.Verdict.RECORD_COMPONENT_ADDED) {
			// 情况 A：堆上无存活实例 → 安全放行，新实例正常通过规范构造器初始化
			if (liveness == Liveness.NONE) {
				return new LayoutDecision(LayoutAction.WAIVE, res, host, Set.of());
			}
			// 情况 B：堆上有存活实例 → 坚决拒绝，Record 组件无初值表达式且不可变，存量实例将恒为 0/null 破坏不变式
			return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames),
				"record component added (" + res.detail() + ") with surviving instances — records cannot"
				+ " have field initializers and existing instances cannot be initialized (restart to continue)");
		}

		// 字段类型变更（CHANGED_FIELD_TYPE）安全门控：
		if (res.verdict() == LayoutGate.Verdict.CHANGED_FIELD_TYPE) {
			List<String> typeChanged = LayoutGate.typeChangedFields(changedFields);
			if (scan.scanned() && scan.hasFindings()) {
				return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames),
					"field type changed and " + describeFindings(scan) + " still resolve to old descriptor");
			}
			// 情况 A：堆上无存活实例（liveInstances == 0）且非静态字段 → 直接放行
			if (liveness == Liveness.NONE && !staticChange) {
				return new LayoutDecision(LayoutAction.WAIVE, res, host, Set.of());
			}
			// 情况 B：堆上有存活实例（liveInstances > 0），且新字节码中该字段标有 @RemoldReinit
			if (!staticChange && reinitFields != null && !typeChanged.isEmpty() && reinitFields.containsAll(typeChanged)) {
				// 核心防护（B4）：校验 @RemoldReinit 标注的字段是否具备可提取的初值切片。
				// 若为 Record 组件或构造器参数自赋值字段，InitFix 无法提取初值，DCEVM 重定义后存量实例将被置为默认值（null/0）发生静默丢值。
				// 此时必须拒绝重定义，保持旧版本继续运行。
				if (unextractableReinitFields != null && !unextractableReinitFields.isEmpty()) {
					// 列出**全部**不可提取字段及原因：只报第一条会让用户以为改掉它就够了，
					// 实际下一次热更还会被另一条拒绝（同类所有改动都被挡住）。也给出两条明确出路。
					List<String> blocked = new ArrayList<>();
					for (Map.Entry<String, String> e : unextractableReinitFields.entrySet()) {
						if (typeChanged.contains(e.getKey())) {
							blocked.add(e.getKey() + ": " + e.getValue());
						}
					}
					if (!blocked.isEmpty()) {
						return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames),
							"field type changed: " + typeChanged + " with surviving instances — @RemoldReinit"
							+ " cannot reinitialize: " + blocked
							+ " (remove @RemoldReinit from these field(s), or revert the type change, or"
							+ " restart to continue)");
					}
				}
				return new LayoutDecision(LayoutAction.WAIVE_REINIT, res, host, Set.of());
			}
			// 情况 C：堆上有存活实例且未标 @RemoldReinit → 维持 LAYOUT-REJECT，优化提示文案
			String typeChangeHint = "annotate the field with @RemoldReinit and save again to switch its type"
				+ " and reset the value without a restart";
			return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames),
				"field type changed: " + typeChanged + " with surviving instances (" + typeChangeHint + ")");
		}

		// 删除字段（实例或静态）但读者扫描未完成 → 只能拒绝。
		//
		// 这里不能退回下面的 liveness 判据：liveness 回答的是"**现在**有没有实例会被错读"，
		// 而删除的风险是"**之后**新建的实例 / 静态访问被陈旧代码读"——两者正交。
		// 无实例时 `WAIVE` 会放行，等于把"没扫成"读成"没读者"，正是风险 12 本身。
		if (res.verdict() == LayoutGate.Verdict.REMOVED_FIELD && !scan.scanned()
		    && !LayoutGate.MODE_WARN.equals(mode)) {
			return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames),
				"field(s) removed but the reader scan could not complete — the liveness check"
				+ " answers a different question (whether an instance exists now, not whether stale"
				+ " code will read the field later)");
		}

		// 删除字段（实例或静态）：按"批次外读者"判，与存活实例无关（见方法注释）。
		// 改类型 / 改静态性不走这条：它们的旧值会被静默丢弃或错读，读者集合证明不了安全。
		if (res.verdict() == LayoutGate.Verdict.REMOVED_FIELD && scan.scanned()) {
			// 用 hasFindings() 而不是只看 readers()：字段改指向（父类/接口同名字段接住）
			// 不抛错但读到别的值，与方法的分派转移同类，同样不放行。
			if (!scan.hasFindings()) {
				return new LayoutDecision(LayoutAction.WAIVE_NO_READERS, res, host, Set.of());
			}
			return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames));
		}

		// 无存活实例且不涉及静态字段：没有状态会被错读，放行。
		// UNKNOWN 落入下面的 REJECT 分支（按"可能有实例"处理）。
		if (liveness == Liveness.NONE && !staticChange) {
			return new LayoutDecision(LayoutAction.WAIVE, res, host, Set.of());
		}
		return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames));
	}

	/**
	 * 布局门拒绝的紧凑判决串（摘要行用）。
	 *
	 * <p>形如 {@code LAYOUT-REJECT: field(s) removed: COST — restore the removed field(s) or restart}：
	 * 判决码 + <b>具体是哪个字段</b> + <b>下一步怎么办</b>，一行读完不需要往上翻。</p>
	 *
	 * <p>细节取自 {@link LayoutDecision#layout()} 的 {@code detail()}（已点名字段名，去掉方括号便于阅读）。
	 * 读者侧信息只在<b>确实扫出了东西</b>时追加；扫描没做（改类型 / 无存活实例）时不能凭空补一句。</p>
	 */
	static String skipVerdictForLayout(LayoutDecision d, RemovedMemberScan scan) {
		// Reinit 作用域门是"与 changedFields 无关"的独立拒绝：summary 行必须自解释，
		// 否则会落进下面通用的 "revert the field change" 文案里，看起来像普通字段变更。
		if (d.reason() != null && d.reason().startsWith("unsafe @RemoldReinit scope")) {
			return RoundSkips.verdict("LAYOUT-REJECT", "unsafe @RemoldReinit scope on inherited field(s)",
				"move @RemoldReinit to where the value is initialized, use scope = SELF, or use @OnReload");
		}
		String detail = d.layout() == null ? null : d.layout().detail();
		if (scan != null && scan.removedMethods() && "compatible".equals(detail)) {
			detail = "method(s) removed";
		}
		if (detail != null) {
			detail = detail.replace("[", "").replace("]", "").trim();
			// "field(s) removed: COST" → "field removed: COST"：只有一个字段时用单数，
			// 读起来不像模板腔（LayoutGate 那边要同时服务日志，保持中性写法，这里只做展示层归一）。
			String many = "field(s) removed: ";
			if (detail.startsWith(many) && detail.indexOf(',') < 0) {
				detail = "field removed: " + detail.substring(many.length());
			}
		}

		String note = null;
		if (d.reason() != null) {
			// 扫描没完成本身就是拒绝理由，必须说出来：否则"只是删了个字段"看起来像能放行。
			// 但只有"删成员"那一档的 reason 与读者扫描有关；字段改类型 / 物理漂移的 reason
			// 讲的是存活实例或偏移漂移，在这里补"reader scan could not complete"会断言一条
			// 与本次拒绝无关、甚至根本没执行的路径（AGENTS.md §8.1）。故按 verdict 收窄。
			boolean removedMembers = d.layout() != null
				&& d.layout().verdict() == LayoutGate.Verdict.REMOVED_FIELD;
			if (removedMembers) note = "(reader scan could not complete)";
		} else if (scan != null && scan.hasFindings()) {
			note = "(" + describeFindings(scan) + ")";
		}
		if (note != null) detail = detail == null ? note : detail + " " + note;

		String action = d.layout() != null && d.layout().verdict() == LayoutGate.Verdict.REMOVED_FIELD
		 ? "restore the removed field(s) or restart to continue"
		 : (d.layout() != null && d.layout().verdict() == LayoutGate.Verdict.RECORD_COMPONENT_ADDED
		    ? "revert the added record component(s) or restart to continue"
		    : (d.layout() != null && d.layout().verdict() == LayoutGate.Verdict.CHANGED_FIELD_TYPE
		       ? (d.reason() != null && d.reason().contains("@RemoldReinit cannot reinitialize")
		          ? "revert the field type change or restart to continue (initializer unextractable)"
		          : "annotate field(s) with @RemoldReinit, or revert the field change, or restart to continue")
		       : (d.layout() != null && d.layout().verdict() == LayoutGate.Verdict.CHANGED_ENUM_ORDER
		          ? "revert the enum constant order or restart to continue"
		       : (d.layout() != null && d.layout().verdict() == LayoutGate.Verdict.ENUM_CONSTANT_SET_CHANGED
		          ? "revert the enum constant add/remove or restart to continue"
		       : (d.layout() != null && (d.layout().verdict() == LayoutGate.Verdict.SUPERCLASS_FIELD_DRIFT || d.layout().verdict() == LayoutGate.Verdict.LAYOUT_DRIFT)
		          ? "avoid adding instance field(s) to superclass or restart to continue"
		          : (scan != null && scan.removedMethods()
		             ? "restore the removed method(s) or restart to continue"
		             : "revert the field change or restart to continue"))))));
		return RoundSkips.verdict("LAYOUT-REJECT", detail, action);
	}

	/** 把扫描结果说成人能读的一句话，用于拒绝日志。 */
	static String describeFindings(RemovedMemberScan scan) {
		StringBuilder sb = new StringBuilder();
		if (!scan.readers().isEmpty()) {
			sb.append(scan.readers().size()).append(" out-of-batch reader(s)");
			// 名字直接进拒绝日志：只给数量时，用户还得自己去翻扫描明细才能知道改哪一侧。
			// 最多列 3 个（保持一行可读），其余用 "+N more" 交代总数。
			appendFirstNames(sb, scan.readers());
		}
		if (!scan.dispatchShifted().isEmpty()) {
			if (sb.length() > 0) sb.append(" and ");
			sb.append(scan.dispatchShifted().size()).append(" silent dispatch shift(s)");
		}
		return sb.length() == 0 ? "an unknown number of references" : sb.toString();
	}

	/** {@code (p.ReaderA, p.ReaderB, p.ReaderC, +2 more)}；最多列 {@code MAX_NAMED_READERS} 个。 */
	private static void appendFirstNames(StringBuilder sb, List<String> names) {
		int named = Math.min(MAX_NAMED_READERS, names.size());
		sb.append(" (");
		for (int i = 0; i < named; i++) {
			if (i > 0) sb.append(", ");
			sb.append(names.get(i));
		}
		int rest = names.size() - named;
		if (rest > 0) sb.append(rest == 1 ? ", +1 more" : ", +" + rest + " more");
		sb.append(')');
	}

	/** 拒绝日志里最多点名的 reader 数：再多就不再增加可读性，只留总数。 */
	private static final int MAX_NAMED_READERS = 3;

	/** 拒绝时要移出本批的类：宿主 + 其下属全部匿名类。 */
	static Set<String> droppedOf(String host, Set<String> batchNames) {
		Set<String> dropped = new LinkedHashSet<>();
		dropped.add(host);
		if (batchNames != null) {
			for (String n : batchNames) {
				if (AnonClassAligner.isAnonymousClassName(host, n)) dropped.add(n);
			}
		}
		return dropped;
	}

	/**
	 * {@code @RemoldReinit(scope = INCLUDE_SUBCLASSES)} 命中"子类初始化器写继承字段"时的拒绝判决。
	 *
	 * <p>与字段/类型门分开构造：拒绝理由来自 {@link remold.ref.InitFix#unsafeInheritedReinitFields}
	 * 的静态分析（已登记子类 + 子类 {@code <init>} 写入），与 {@code changedFields} 无关，
	 * 因此不能等布局门先判 {@code COMPATIBLE} 再放行。</p>
	 */
	static LayoutDecision rejectUnsafeReinitScope(String className, Set<String> batchNames,
	                                              Map<String, String> reasons) {
		String host = hostNameOf(className);
		StringBuilder sb = new StringBuilder("unsafe @RemoldReinit scope on inherited field(s): ");
		boolean first = true;
		for (Map.Entry<String, String> e : reasons.entrySet()) {
			if (!first) sb.append("; ");
			first = false;
			sb.append(e.getKey()).append(" — ").append(e.getValue());
		}
		return new LayoutDecision(LayoutAction.REJECT, null, host, droppedOf(host, batchNames), sb.toString());
	}
}
