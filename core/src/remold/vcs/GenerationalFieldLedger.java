package remold.vcs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * 代际字段台账：把 {@code Changeset.replayFacts} 的事实按代坐标索引起来，供跨代重放对账
 * （{@code docs/vcs/05-generational-field-ledger.md} §2 / §3）。
 *
 * <p><b>它是派生索引，不是真值</b>：真值是各代 {@code Changeset.replayFacts}；索引丢了可以重建，
 * 事实丢了只能拒绝（§2.3）。因此这里只存字符串、标量记录与枚举，</p>
 *
 * <p><b>不持有 {@code Class} / {@code ClassLoader} / 字节数组</b>：槽用 {@link WeakHashMap} 弱键挂
 * loader，值里只有 {@code (scope, 内部名, field)} 与 {@link GenRef} 这类纯标量；loader 卸载后整槽
 * 静默消失，不会拖住加载器（§2.4）。</p>
 *
 * <p><b>两类保留策略，不共用一个窗口</b>（§2.3）：未闭环债务（判过 {@code MISS_NO_FACT} / 写入失败
 * 的字段）不被 Changeset 窗口淘汰——它代表"这一步还没完成"，与"历史索引太长"是两件事；历史索引跟随
 * {@link ChangesetLog#window()}，没有第二个旋钮。</p>
 */
public final class GenerationalFieldLedger {

	/** 事实所属的代数：{@code seq} 定坐标，{@code changesetId} 是校验位（§2.2）。 */
	public record GenRef(long seq, String changesetId) {
		public GenRef {
			if (seq <= 0) throw new IllegalArgumentException("seq must be >= 1");
			changesetId = changesetId == null ? "" : changesetId;
		}
	}

	/** 台账键：scope 来自 {@code AlignmentMetaStore.loaderScopeId}，与 {@code Changeset.loaderScope} 同源。 */
	public record LedgerKey(long scope, String className, String field) {
		public LedgerKey {
			if (className == null || className.isEmpty()) throw new IllegalArgumentException("className");
			if (field == null || field.isEmpty()) throw new IllegalArgumentException("field");
		}
	}

	/**
	 * 一条已记录事实的只读快照（§3.3：期望与写入值必须来自同一对，禁止跨事实混搭）。
	 *
	 * <p>{@code desc} 与 {@code algoVersion} 都只作<b>校验位</b>：前者挡住同名异型，后者挡住跨口径
	 * 复用（消费侧据此 fail-closed 拒绝 {@code vcs=1} 之类旧记录，见
	 * {@link GenerationalReplayGate}）。两者都是字符串，不改变"值里不持 {@code Class}"的形状。</p>
	 */
	public record Fact(LedgerKey key, GenRef gen, String tier, String desc,
	                   String oldLiteral, String newLiteral, String literalKind, String algoVersion) {
	}

	/** 写入台账的结果；拒绝都是 fail-closed，不做事后补正（§4-R5）。 */
	public enum RecordResult {
		/** 口径与形态都合格。 */
		ACCEPTED,
		/** 事实来自更早的 {@code algoVersion}：口径不同，拒绝而不是猜。 */
		REFUSED_LEGACY_VERSION,
		/** {@code FLOAT_BITS}/{@code DOUBLE_BITS} 的 kind 配十进制字面量，或 kind 与字面量形态不符。 */
		REFUSED_NON_CANONICAL,
		/** 没有代坐标（例如 {@code REINIT} 档无字面量），不进事实索引。 */
		REFUSED_NO_GENERATION
	}

	private static final class ScopeSlot {
		/** 首次记录时绑定；{@code -1} 表示尚未绑定（槽是先按 loader 建、后按事实定 scope）。 */
		long scope;
		/** {@code class\u0001field} → 降序（新代在前）的事实列表，受窗口约束。 */
		final Map<String, Deque<Fact>> history = new HashMap<>();
		/** 未闭环债务：只记键，不随窗口淘汰。 */
		final Set<String> unclosed = new TreeSet<>();
		/** 每代的引擎写入结果（§3.4），随回退整体作废。 */
		final Map<String, ArrayDeque<ReinitApplied>> reinitApplied = new HashMap<>();

		ScopeSlot(long scope) {
			this.scope = scope;
		}
	}

	/** 引擎写入结果：只记标量，不记运行期对象（§3.4）。 */
	public record ReinitApplied(String className, String field, String key, String result, GenRef gen) {
	}

	private final Map<ClassLoader, ScopeSlot> slots = new WeakHashMap<>();
	private final ScopeSlot bootstrapSlot = new ScopeSlot(0L);
	private long acceptedCount;
	private long refusedLegacyCount;
	private long refusedNonCanonicalCount;

	/** 单例：与 {@link ChangesetLog} / {@link AlignmentMetaStore} 同生命周期口径。 */
	public static final GenerationalFieldLedger INSTANCE = new GenerationalFieldLedger();

	private ScopeSlot slotFor(ClassLoader loader) {
		if (loader == null) return bootstrapSlot;
		return slots.computeIfAbsent(loader, ignored -> new ScopeSlot(-1L));
	}

	private static String rowKey(String className, String field) {
		return className + '\u0001' + field;
	}

	/**
	 * 记一条事实。口径不合规即拒绝并计数，<b>不</b>就地规范化：把十进制文本补成位模式等于伪造事实
	 * （{@code docs/vcs/01-bytecode-changeset-vcs.md} §3.8）。
	 */
	public synchronized RecordResult record(ClassLoader loader, LedgerKey key, GenRef gen,
	                                        Changeset.ReplayFact fact, String algoVersion) {
		if (gen == null || fact == null) return RecordResult.REFUSED_NO_GENERATION;
		if (algoVersion == null || !algoVersion.equals(Changeset.currentAlgoVersion())) {
			refusedLegacyCount++;
			return RecordResult.REFUSED_LEGACY_VERSION;
		}
		if (!isCanonical(fact.literalKind(), fact.oldLiteral(), fact.newLiteral())) {
			refusedNonCanonicalCount++;
			return RecordResult.REFUSED_NON_CANONICAL;
		}
		ScopeSlot slot = slotFor(loader);
		if (slot.scope < 0L) slot.scope = key.scope();
		Fact entry = new Fact(key, gen, fact.tier(), fact.desc(),
		 fact.oldLiteral(), fact.newLiteral(), fact.literalKind(), algoVersion);
		Deque<Fact> rows = slot.history.computeIfAbsent(rowKey(key.className(), key.field()),
		 ignored -> new ArrayDeque<>());
		rows.addFirst(entry);
		acceptedCount++;
		return RecordResult.ACCEPTED;
	}

	/**
	 * 字面量形态判定（§4-R5）：比较与写入都必须用规范形态，两侧任一不合规即整条拒绝。
	 *
	 * <p>只有 {@code FLOAT_BITS}/{@code DOUBLE_BITS} 有可校验的形态：其余档位按"非空即可"处理，
	 * 因为整数/字符串的十进制文本本身就是规范形。</p>
	 */
	public static boolean isCanonical(String literalKind, String oldLiteral, String newLiteral) {
		if (literalKind == null || "NONE".equals(literalKind)) return false;
		if (oldLiteral == null || newLiteral == null) return false;
		switch (literalKind) {
			case "FLOAT_BITS":
				return oldLiteral.matches("0x[0-9a-f]{8}") && newLiteral.matches("0x[0-9a-f]{8}");
			case "DOUBLE_BITS":
				return oldLiteral.matches("0x[0-9a-f]{16}") && newLiteral.matches("0x[0-9a-f]{16}");
			default:
				return true;
		}
	}

	/** 某键在 {@code seq <= targetSeq} 的最新事实（索引查询；身份与相邻性校验由消费侧做，§3.3）。 */
	public synchronized Optional<Fact> latest(ClassLoader loader, String className, String field, long targetSeq) {
		Deque<Fact> rows = slotFor(loader).history.get(rowKey(className, field));
		if (rows == null) return Optional.empty();
		for (Fact fact : rows) {
			if (fact.gen().seq() <= targetSeq) return Optional.of(fact);
		}
		return Optional.empty();
	}

	/** 某键的全部事实，新代在前（读侧返回快照拷贝，不暴露内部集合）。 */
	public synchronized List<Fact> facts(ClassLoader loader, String className, String field) {
		Deque<Fact> rows = slotFor(loader).history.get(rowKey(className, field));
		return rows == null ? List.of() : List.copyOf(rows);
	}

	/** 该 scope 里这条键是否还有事实（区分 {@code MISS_NO_FACT} 与 {@code MISS_WINDOW}）。 */
	public synchronized boolean hasAnyFact(ClassLoader loader, String className, String field) {
		Deque<Fact> rows = slotFor(loader).history.get(rowKey(className, field));
		return rows != null && !rows.isEmpty();
	}

	/** 记一条引擎写入结果（§3.4：`key == null` 表示判不了初值是否变过，该字段每代照常重置）。 */
	public synchronized void recordReinitApplied(ClassLoader loader, String className, String field,
	                                             String key, String result, GenRef gen) {
		ScopeSlot slot = slotFor(loader);
		slot.reinitApplied.computeIfAbsent(rowKey(className, field), ignored -> new ArrayDeque<>())
		 .addFirst(new ReinitApplied(className, field, key, result == null ? "" : result, gen));
	}

	/**
	 * 回退提交后整体作废 {@code seq > target} 的引擎写入记录（§3.4 / A14）。
	 *
	 * @return 被作废的条目数
	 */
	public synchronized int invalidateAfterRevert(ClassLoader loader, long targetSeq) {
		ScopeSlot slot = slotFor(loader);
		int dropped = 0;
		for (Deque<ReinitApplied> rows : slot.reinitApplied.values()) {
			dropped += removeAfter(rows, targetSeq);
		}
		for (Deque<Fact> rows : slot.history.values()) {
			dropped += removeFactsAfter(rows, targetSeq);
		}
		return dropped;
	}

	private static int removeAfter(Deque<ReinitApplied> rows, long targetSeq) {
		int before = rows.size();
		rows.removeIf(row -> row.gen().seq() > targetSeq);
		return before - rows.size();
	}

	private static int removeFactsAfter(Deque<Fact> rows, long targetSeq) {
		int before = rows.size();
		rows.removeIf(row -> row.gen().seq() > targetSeq);
		return before - rows.size();
	}

	/**
	 * 按 Changeset 窗口淘汰历史索引；<b>不动</b>未闭环债务（§2.3 两类保留）。
	 *
	 * @param oldestSeq 窗口里仍可寻址的最小 {@code seq}
	 * @return 被淘汰的索引条目数
	 */
	public synchronized int pruneToWindow(ClassLoader loader, long oldestSeq) {
		ScopeSlot slot = slotFor(loader);
		int dropped = 0;
		for (Map.Entry<String, Deque<Fact>> entry : slot.history.entrySet()) {
			Deque<Fact> rows = entry.getValue();
			int before = rows.size();
			rows.removeIf(row -> row.gen().seq() < oldestSeq);
			dropped += before - rows.size();
		}
		slot.history.entrySet().removeIf(entry -> entry.getValue().isEmpty()
		 && !slot.unclosed.contains(entry.getKey()));
		return dropped;
	}

	/** §3.3 的判定结果；只有 {@link Resolution#EXACT} 与 {@link Resolution#CHAINED} 允许写入。 */
	public enum Resolution {
		EXACT, CHAINED, MISS_NO_FACT, MISS_WINDOW, REFUSED_DISCONTINUOUS, REFUSED_NONE_LITERAL,
		/** 事实登记的 {@code algoVersion} 与当前口径不同：拒绝而不是猜（§4-R5 / G8）。 */
		REFUSED_LEGACY_VERSION
	}

	/**
	 * 一次对账的结论。{@code oldLiteral} 是比较侧期望，{@code newLiteral} 是写入值——两者必须同源。
	 *
	 * @param fromGeneration   事实所在代
	 * @param targetGeneration 本次对账解析到的目标代（回退代已被单跳解析）
	 * @param desc             事实登记的字段描述符（校验位，可为空）
	 * @param algoVersion      事实登记时的口径指纹（校验位）
	 */
	public record Resolved(Resolution resolution, long fromGeneration, long targetGeneration, GenRef gen,
	                       String oldLiteral, String newLiteral, String literalKind,
	                       String desc, String algoVersion) {
		public boolean writable() {
			return resolution == Resolution.EXACT || resolution == Resolution.CHAINED;
		}
	}

	private static final Resolved NO_FACT = miss(Resolution.MISS_NO_FACT, 0L, null);

	private static Resolved miss(Resolution resolution, long target, GenRef gen) {
		return new Resolved(resolution, 0L, target, gen, null, null, null, null, null);
	}

	/**
	 * 按 §3.3 做确定性查找：目标代 → 最新可采信事实 → 身份 / 相邻性 / 形态三关。
	 *
	 * <p><b>期望来源唯一</b>：比较侧与写入侧都取自<b>同一条</b>事实（{@code oldLiteral} →
	 * {@code newLiteral}）。被回退掉的污染代事实、引擎写入值、类型默认值都<b>不是</b>期望
	 * （§3.3 D12）——否则实例仍持有污染代写入值时会被判"无需写"，回退变成静默 no-op。</p>
	 *
	 * @param targetSeq 调用方请求对账到的那一代；若该代本身是回退代，按 {@code revertedToSeq}
	 *                  单跳解析到具体目标（<b>不做</b>多跳传递闭包）
	 */
	public synchronized Resolved resolve(ClassLoader loader, ChangesetLog log, long scope,
	                                     String className, String field, long targetSeq) {
		long target = revertTargetOf(log, scope, targetSeq);
		Deque<Fact> rows = slotFor(loader).history.get(rowKey(className, field));
		if (rows == null || rows.isEmpty()) {
			// 事实可能在窗口外：链本身也退到了 target 之前，说明这条事实不可重建（§2.3）。
			return log.bySeq(scope, target).isEmpty()
			 ? miss(Resolution.MISS_WINDOW, target, null) : NO_FACT;
		}
		Fact candidate = null;
		boolean sawAnyAtOrBefore = false;
		for (Fact fact : rows) {
			if (fact.gen().seq() > target) continue;
			sawAnyAtOrBefore = true;
			candidate = fact;
			break;
		}
		if (!sawAnyAtOrBefore) return NO_FACT;
		Changeset source = log.bySeq(scope, candidate.gen().seq()).orElse(null);
		if (source == null || !source.id().equals(candidate.gen().changesetId())) {
			// 代已出窗，或 id 对不上（同 seq 不同代）：拒绝，且不回落到"最近活代"顶替。
			return miss(Resolution.MISS_WINDOW, target, candidate.gen());
		}
		if (!isAdjacent(log, scope, className, candidate.gen().seq(), target)) {
			return miss(Resolution.REFUSED_DISCONTINUOUS, target, candidate.gen());
		}
		// 口径（algoVersion）在形态之前判：旧口径的字面量形态本来就不该被解读，先拒绝更窄。
		// 索引层已经在 record() 拒收跨口径记录，这里是消费侧的第二道（§4-R5 / G8）。
		if (candidate.algoVersion() == null
		 || !candidate.algoVersion().equals(Changeset.currentAlgoVersion())) {
			return new Resolved(Resolution.REFUSED_LEGACY_VERSION, 0L, target, candidate.gen(),
			 null, null, null, candidate.desc(), candidate.algoVersion());
		}
		if (candidate.literalKind() == null || "NONE".equals(candidate.literalKind())
		 || !isCanonical(candidate.literalKind(), candidate.oldLiteral(), candidate.newLiteral())) {
			return new Resolved(Resolution.REFUSED_NONE_LITERAL, 0L, target, candidate.gen(),
			 null, null, candidate.literalKind(), candidate.desc(), candidate.algoVersion());
		}
		Resolution resolution = candidate.gen().seq() == target ? Resolution.EXACT : Resolution.CHAINED;
		return new Resolved(resolution, candidate.gen().seq(), target, candidate.gen(),
		 candidate.oldLiteral(), candidate.newLiteral(), candidate.literalKind(),
		 candidate.desc(), candidate.algoVersion());
	}

	/** 回退代沿 {@code revertedToSeq} 单跳解析；非回退代就是它自己。 */
	private static long revertTargetOf(ChangesetLog log, long scope, long seq) {
		Optional<Changeset> cs = log.bySeq(scope, seq);
		if (cs.isPresent() && cs.get().revertedToSeq() > 0L) return cs.get().revertedToSeq();
		return seq;
	}

	/**
	 * 相邻性（§3.3 第 2 条）：该类在 {@code [g, target]} 区间内 {@code rawHash} 恒定。
	 *
	 * <p>「最近有事实」不等于「事实还成立」：非常量初始化不产出记录，中间代初值可能已经变过。
	 * 只有 {@code rawHash} 在整段区间里一字未动，才能证明这条事实仍描述当前结构。</p>
	 */
	private static boolean isAdjacent(ChangesetLog log, long scope, String className, long from, long target) {
		List<Changeset> chain = log.chain(scope);
		String expected = null;
		for (Changeset cs : chain) {
			if (cs.seq() < from || cs.seq() > target) continue;
			Changeset.Entry entry = cs.entries().get(className);
			if (entry == null) continue;
			if (expected == null) {
				expected = entry.rawHash();
			} else if (!expected.equals(entry.rawHash())) {
				return false;
			}
		}
		return expected != null;
	}

	/** 记一个未闭环字段（写入失败 / 无事实 / 形态被拒都落到这里，见 §6-A5）。 */
	public synchronized void markUnclosed(ClassLoader loader, String className, String field) {
		slotFor(loader).unclosed.add(rowKey(className, field));
	}

	/**
	 * 字段已从新布局里消失时按 <b>(loader, 类, 字段)</b> 精确释放它的债。
	 *
	 * <p>未闭环债务不随 Changeset 窗口淘汰，字段删掉后不出账就会永久留存。这里要求键完全匹配：
	 * 早先按"字段名在整个 loader 内匹配"的写法会误删同 loader 下另一个类同名字段的债务，
	 * 而债务出账的语义是"这个类的这个字段不再需要补"，不能跨类。</p>
	 *
	 * @return 被释放的债务条目数（0 或 1）
	 */
	public synchronized int releaseDebt(ClassLoader loader, String className, String field) {
		if (className == null || field == null || field.isEmpty()) return 0;
		boolean removed = slotFor(loader).unclosed.remove(className + '\u0001' + field);
		return removed ? 1 : 0;
	}

	/** 字段闭环后从债务表移除。 */
	public synchronized void markClosed(ClassLoader loader, String className, String field) {
		slotFor(loader).unclosed.remove(rowKey(className, field));
	}

	/** 该键当前是否欠着（未闭环）；写入成功后的闭环判定用它，不必暴露行键格式。 */
	public synchronized boolean isUnclosed(ClassLoader loader, String className, String field) {
		return slotFor(loader).unclosed.contains(rowKey(className, field));
	}

	public synchronized List<String> unclosed(ClassLoader loader) {
		return new ArrayList<>(slotFor(loader).unclosed);
	}

	public synchronized int unclosedCount(ClassLoader loader) {
		return slotFor(loader).unclosed.size();
	}

	/** 该 loader 上最近一次记录的引擎写入结果（诊断与 A14 断言用）。 */
	public synchronized List<ReinitApplied> reinitApplied(ClassLoader loader, String className, String field) {
		Deque<ReinitApplied> rows = slotFor(loader).reinitApplied.get(rowKey(className, field));
		return rows == null ? List.of() : List.copyOf(rows);
	}

	/** 诊断读数：已接受 / 因口径拒绝 / 因形态拒绝。 */
	public synchronized Map<String, Long> counters() {
		Map<String, Long> out = new LinkedHashMap<>();
		out.put("accepted", acceptedCount);
		out.put("refusedLegacy", refusedLegacyCount);
		out.put("refusedNonCanonical", refusedNonCanonicalCount);
		return Collections.unmodifiableMap(out);
	}

	/** 清空（测试与 agent 重启口径）。 */
	public synchronized void reset() {
		slots.clear();
		bootstrapSlot.history.clear();
		bootstrapSlot.unclosed.clear();
		bootstrapSlot.reinitApplied.clear();
		acceptedCount = 0L;
		refusedLegacyCount = 0L;
		refusedNonCanonicalCount = 0L;
	}

	/** 仍持有槽的 loader 数（弱表，仅诊断）。 */
	public synchronized int liveLoaderCount() {
		return slots.size();
	}
}
