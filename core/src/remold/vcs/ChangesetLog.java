package remold.vcs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 每个 loader scope 一条 Changeset 链（{@code docs/topology/16-bytecode-changeset-vcs.md} §2.1.1）。
 *
 * <p>链上只存 {@link Changeset}：全是字符串、哈希与 scope 代号，不持 {@code ClassLoader}，
 * 因此 scope 用单调 long 寻址而不是用 loader 对象做键（不变量 C8）。</p>
 *
 * <p>{@code seq} 只属于已提交代：未通过 {@link VcsCommitGate} 的轮次调用 {@link #abort} 记一条诊断，
 * 不分配序号、不推进 head（不变量 C1）。</p>
 */
public final class ChangesetLog {

	public static final ChangesetLog INSTANCE = new ChangesetLog();

	/** 每 scope 保留的代际条数上限；与 {@code HISTORY_PER_CLASS = 8} 对齐（8 × 4 个互依赖类）。 */
	public static final int DEFAULT_WINDOW = 32;

	private final Map<Long, ArrayDeque<Changeset>> chains = new LinkedHashMap<>();
	private final Map<Long, Long> abortedCounts = new LinkedHashMap<>();

	private ChangesetLog() {
	}

	/** 窗口上限：{@code -Dremold.vcs.changeset_window} 覆盖，下限 1。 */
	public static int window() {
		Integer raw = Integer.getInteger("remold.vcs.changeset_window");
		int value = raw == null ? DEFAULT_WINDOW : raw;
		return Math.max(1, value);
	}

	/**
	 * 提交一代。调用方必须已经通过 {@link VcsCommitGate}；本方法不做判据判断，只负责序号与链维护。
	 *
	 * @return 新写入的不可变记录
	 */
	public synchronized Changeset commit(long loaderScope, String roundId,
	                                     Map<String, Changeset.Entry> entries,
	                                     Changeset.Manifest manifest,
	                                     List<Changeset.ReplayFact> replayFacts,
	                                     String algoVersion, String buildId,
	                                     Changeset.PatchTiming patchTiming) {
		ArrayDeque<Changeset> chain = chains.computeIfAbsent(loaderScope, ignored -> new ArrayDeque<>());
		Changeset parent = chain.peekLast();
		long seq = parent == null ? 1L : parent.seq() + 1L;
		Changeset next = Changeset.create(parent == null ? null : parent.id(), seq, roundId, loaderScope,
		 entries == null ? Map.of() : entries, manifest, replayFacts,
		 algoVersion, buildId, patchTiming);
		chain.addLast(next);
		int window = window();
		while (chain.size() > window) chain.removeFirst();
		return next;
	}

	/**
	 * 记录一次未提交的轮次：不分配 {@code seq}、不推进 head，只累加诊断计数。
	 *
	 * <p>aborted 轮次刻意不建 Changeset：{@code seq} 只表示"已提交代"，
	 * 否则回退侧要处理第二类节点（§6.3）。</p>
	 */
	public synchronized void abort(long loaderScope) {
		abortedCounts.merge(loaderScope, 1L, Long::sum);
	}

	/** 该 scope 的未提交轮次数（诊断读数）。 */
	public synchronized long abortedCount(long loaderScope) {
		Long n = abortedCounts.get(loaderScope);
		return n == null ? 0L : n;
	}

	public synchronized Optional<Changeset> head(long loaderScope) {
		ArrayDeque<Changeset> chain = chains.get(loaderScope);
		return chain == null ? Optional.empty() : Optional.ofNullable(chain.peekLast());
	}

	public synchronized Optional<Changeset> bySeq(long loaderScope, long seq) {
		ArrayDeque<Changeset> chain = chains.get(loaderScope);
		if (chain == null) return Optional.empty();
		for (Changeset cs : chain) {
			if (cs.seq() == seq) return Optional.of(cs);
		}
		return Optional.empty();
	}

	/** 当前保留的代际条数。 */
	public synchronized int size(long loaderScope) {
		ArrayDeque<Changeset> chain = chains.get(loaderScope);
		return chain == null ? 0 : chain.size();
	}

	/** 已提交的 scope 数（诊断读数；scope 卸载后其链仍按窗口淘汰）。 */
	public synchronized int liveScopeCount() {
		return chains.size();
	}

	/** 按 seq 升序的快照，供导出包与诊断使用。 */
	public synchronized List<Changeset> chain(long loaderScope) {
		ArrayDeque<Changeset> chain = chains.get(loaderScope);
		return chain == null ? List.of() : List.copyOf(new ArrayList<>(chain));
	}

	public synchronized void reset() {
		chains.clear();
		abortedCounts.clear();
	}

	/**
	 * 判定某代可达的可用性等级（§2.1.1 三级表）。
	 *
	 * @param rawAvailable Raw 字节仍在
	 * @param metaAvailable Meta 记录仍在
	 * @param effAvailable Eff 字节可得（现存或已由 Raw+Meta 重算成功）
	 */
	public static Changeset.Availability availability(boolean rawAvailable, boolean metaAvailable,
	                                                  boolean effAvailable) {
		if (effAvailable) return Changeset.Availability.REVERTABLE;
		if (rawAvailable && metaAvailable) return Changeset.Availability.REPLAYABLE;
		return Changeset.Availability.ADDRESSABLE;
	}

	/**
	 * 回退前置条件：目标代必须达到 {@code REVERTABLE}。
	 *
	 * <p>只有 {@code ADDRESSABLE} 时拒绝执行：缺代的半吊子回退会毁掉批次一致性，
	 * 比不回退更危险（§2.1.1）。</p>
	 *
	 * @throws NotRevertableException 目标代达不到 {@code REVERTABLE}
	 */
	public static void requireRevertable(Changeset target, boolean rawAvailable,
	                                     boolean metaAvailable, boolean effAvailable) {
		Changeset.Availability level = availability(rawAvailable, metaAvailable, effAvailable);
		if (level != Changeset.Availability.REVERTABLE) {
			throw new NotRevertableException(target, level);
		}
	}

	/** 目标代达不到 {@code REVERTABLE}：携带实际等级供调用方记录。 */
	public static final class NotRevertableException extends IllegalStateException {
		private final transient Changeset target;
		private final transient Changeset.Availability level;

		NotRevertableException(Changeset target, Changeset.Availability level) {
			super("NOT_REVERTABLE: seq=" + (target == null ? -1 : target.seq())
			 + " level=" + level);
			this.target = target;
			this.level = level;
		}

		public Changeset target() {
			return target;
		}

		public Changeset.Availability level() {
			return level;
		}
	}
}
