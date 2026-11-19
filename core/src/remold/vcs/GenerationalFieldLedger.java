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
 * （{@code docs/topology/20-generational-field-ledger.md} §2 / §3）。
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

	/** 一条已记录事实的只读快照（§3.3：期望与写入值必须来自同一对，禁止跨事实混搭）。 */
	public record Fact(LedgerKey key, GenRef gen, String tier, String desc,
	                   String oldLiteral, String newLiteral, String literalKind) {
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
	 * （{@code docs/topology/16-bytecode-changeset-vcs.md} §3.8）。
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
		 fact.oldLiteral(), fact.newLiteral(), fact.literalKind());
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

	/** 记一个未闭环字段（写入失败 / 无事实 / 形态被拒都落到这里，见 §6-A5）。 */
	public synchronized void markUnclosed(ClassLoader loader, String className, String field) {
		slotFor(loader).unclosed.add(rowKey(className, field));
	}

	/** 字段闭环后从债务表移除。 */
	public synchronized void markClosed(ClassLoader loader, String className, String field) {
		slotFor(loader).unclosed.remove(rowKey(className, field));
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
