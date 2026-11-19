package remold.vcs;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

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

	/** Eff 字节预算：{@code -Dremold.vcs.eff_bytes} 覆盖，默认 16 MiB（与 Raw_prev 预算同量级）。 */
	public static final long DEFAULT_EFF_BYTES = 16L * 1024 * 1024;

	private final Map<Long, ArrayDeque<Changeset>> chains = new LinkedHashMap<>();
	private final Map<Long, Long> abortedCounts = new LinkedHashMap<>();
	/** 内容寻址的 Eff 字节（Eff_0 与逐代 Eff 共用，按 sha256 去重）：回退的唯一字节来源。 */
	private final Map<String, byte[]> effBlobs = new LinkedHashMap<>();
	private long effBytesHeld;
	private int effDroppedCount;

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
		return commit(loaderScope, roundId, entries, manifest, replayFacts, algoVersion, buildId,
		 patchTiming, Map.of(), Map.of());
	}

	/**
	 * 提交一代，并固化回退所需的两类字节引用。
	 *
	 * @param effBefore 本轮之前每类的 Eff 字节：只为「首次进入本 scope 链」的类固化 {@code Eff_0}；
	 *                  已在链上的类忽略该参数（它们的回退目标是链上更早那一代）。
	 * @param effNow    本轮提交后的 Eff 字节（用于后续回退到本代）
	 */
	public synchronized Changeset commit(long loaderScope, String roundId,
	                                     Map<String, Changeset.Entry> entries,
	                                     Changeset.Manifest manifest,
	                                     List<Changeset.ReplayFact> replayFacts,
	                                     String algoVersion, String buildId,
	                                     Changeset.PatchTiming patchTiming,
	                                     Map<String, byte[]> effBefore,
	                                     Map<String, byte[]> effNow) {
		ArrayDeque<Changeset> chain = chains.computeIfAbsent(loaderScope, ignored -> new ArrayDeque<>());
		Changeset parent = chain.peekLast();
		long seq = parent == null ? 1L : parent.seq() + 1L;
		Map<String, Changeset.Entry> sorted = entries == null ? Map.of() : entries;
		Map<String, String> eff0 = parent == null ? new TreeMap<>() : new TreeMap<>(parent.eff0());
		for (String className : sorted.keySet()) {
			if (eff0.containsKey(className)) continue;
			byte[] before = effBefore == null ? null : effBefore.get(className);
			if (before == null) continue;
			// 首次进入链：此刻的字节就是 Eff_0，区间回退到「首次修改之前」只能靠它（§2.1.1）。
			eff0.put(className, retainEff(before));
		}
		if (effNow != null) {
			for (Map.Entry<String, byte[]> e : effNow.entrySet()) retainEff(e.getValue());
		}
		Changeset next = Changeset.create(parent == null ? null : parent.id(), seq, roundId, loaderScope,
		 sorted, manifest, replayFacts, algoVersion, buildId, patchTiming, eff0, 0L);
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

	/**
	 * 保留一份 Eff 字节（内容寻址、按 sha256 去重）。超预算按插入序淘汰最旧的一份。
	 *
	 * @return 该字节的 sha256（128 位十六进制）
	 */
	private String retainEff(byte[] bytes) {
		if (bytes == null || bytes.length == 0) return "";
		String sha = effSha256(bytes);
		if (effBlobs.containsKey(sha)) return sha;
		long budget = Long.getLong("remold.vcs.eff_bytes", DEFAULT_EFF_BYTES);
		if (budget <= 0) {
			effDroppedCount++;
			return sha;
		}
		while (effBytesHeld + bytes.length > budget && !effBlobs.isEmpty()) {
			Map.Entry<String, byte[]> eldest = effBlobs.entrySet().iterator().next();
			effBytesHeld -= eldest.getValue().length;
			effBlobs.remove(eldest.getKey());
			effDroppedCount++;
		}
		if (bytes.length > budget) {
			effDroppedCount++;
			return sha;
		}
		effBlobs.put(sha, bytes.clone());
		effBytesHeld += bytes.length;
		return sha;
	}

	/** 已保留的 Eff 字节总量（诊断读数）。 */
	public synchronized long effBytesHeld() {
		return effBytesHeld;
	}

	/** 因预算或超单条上限而未保留的 Eff 次数（诊断读数）。 */
	public synchronized int effDroppedCount() {
		return effDroppedCount;
	}

	/** 某一代的 Eff 字节；该代未导出或字节已被淘汰时为空。 */
	public synchronized Optional<byte[]> effBytes(long loaderScope, long seq) {
		Optional<Changeset> cs = bySeq(loaderScope, seq);
		if (cs.isEmpty()) return Optional.empty();
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (Changeset.Entry entry : cs.get().entries().values()) {
			byte[] bytes = effBlobs.get(entry.effHash());
			if (bytes == null) return Optional.empty();
			out.put(entry.className(), bytes);
		}
		return out.isEmpty() ? Optional.empty() : Optional.of(joinBytes(out));
	}

	private static byte[] joinBytes(Map<String, byte[]> byClass) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (Map.Entry<String, byte[]> e : new TreeMap<>(byClass).entrySet()) {
			out.writeBytes(e.getKey().getBytes(StandardCharsets.UTF_8));
			out.write(0);
			out.writeBytes(e.getValue());
			out.write(0);
		}
		return out.toByteArray();
	}

	/** 回退计划里的一类目标。 */
	public record RevertTarget(String className, String effHash, byte[] bytes) {
	}

	/** 回退计划：目标代 + 需要重定义的类集合（含各自目标字节）。 */
	public record RevertPlan(long targetSeq, long headSeq, Map<String, RevertTarget> targets) {
		public boolean isEmpty() {
			return targets.isEmpty();
		}
	}

	/**
	 * 计算回退到 {@code targetSeq} 需要的目标字节集合（§2.1.1 区间回退约束）。
	 *
	 * <p>取 {@code (targetSeq, HEAD]} 内所有被改过类的并集：每类取其 {@code seq <= targetSeq} 的最新
	 * Eff；若该类的首次修改就落在被回退区间内，则取它的 {@code Eff_0}。任一类拿不到字节即整体拒绝，
	 * 不做部分回退。</p>
	 */
	public synchronized RevertPlan revertPlan(long loaderScope, long targetSeq) {
		ArrayDeque<Changeset> chain = chains.get(loaderScope);
		if (chain == null || chain.isEmpty()) throw new NotRevertableException(null, Changeset.Availability.ADDRESSABLE);
		Changeset head = chain.peekLast();
		Changeset target = null;
		for (Changeset cs : chain) {
			if (cs.seq() == targetSeq) target = cs;
		}
		if (target == null) throw new NotRevertableException(null, Changeset.Availability.ADDRESSABLE);
		if (targetSeq >= head.seq()) return new RevertPlan(targetSeq, head.seq(), Map.of());

		Set<String> touched = new LinkedHashSet<>();
		for (Changeset cs : chain) {
			if (cs.seq() > targetSeq) touched.addAll(cs.entries().keySet());
		}
		Map<String, RevertTarget> targets = new LinkedHashMap<>();
		List<String> missing = new ArrayList<>();
		for (String className : new TreeMap<>(toMap(touched)).keySet()) {
			Changeset.Entry latest = null;
			for (Changeset cs : chain) {
				if (cs.seq() > targetSeq) continue;
				Changeset.Entry candidate = cs.entries().get(className);
				if (candidate != null) latest = candidate;
			}
			// Eff_0 只在引入该类的后代上记录，并被后续各代原样携带；新区间回退必须从最新一代取它。
			String effHash = latest == null ? head.eff0().get(className) : latest.effHash();
			byte[] bytes = effHash == null ? null : effBlobs.get(effHash);
			if (bytes == null) {
				missing.add(className);
				continue;
			}
			targets.put(className, new RevertTarget(className, effHash, bytes.clone()));
		}
		if (!missing.isEmpty()) {
			// 点名缺字节的类：否则只能在"回退被拒"与"回退错代"之间靠猜。
			throw new NotRevertableException(target, Changeset.Availability.REPLAYABLE, missing);
		}
		return new RevertPlan(targetSeq, head.seq(), targets);
	}

	private static Map<String, String> toMap(Set<String> names) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String name : names) map.put(name, name);
		return map;
	}

	/**
	 * 回退成功后追加一条前向 Changeset（{@code seq = HEAD+1}，{@code revertedToSeq = targetSeq}）。
	 *
	 * <p>不倒拨 {@code seq}、不造分叉：离线链式导出保持线性，回退本身也是一代（§2.1.1）。</p>
	 */
	public synchronized Changeset appendForwardRevert(long loaderScope, RevertPlan plan,
	                                                  String roundId, String algoVersion,
	                                                  String buildId,
	                                                  Changeset.PatchTiming patchTiming) {
		ArrayDeque<Changeset> chain = chains.get(loaderScope);
		if (chain == null || chain.isEmpty()) throw new IllegalStateException("no chain for scope " + loaderScope);
		Changeset head = chain.peekLast();
		Map<String, Changeset.Entry> entries = new TreeMap<>();
		for (RevertTarget t : new TreeMap<>(plan.targets()).values()) {
			Changeset.Entry source = null;
			for (Changeset cs : chain) {
				Changeset.Entry candidate = cs.entries().get(t.className());
				if (candidate != null) source = candidate;
			}
			String rawHash = source == null ? t.effHash() : source.rawHash();
			String metaHash = source == null ? "" : source.metaHash();
			entries.put(t.className(), new Changeset.Entry(t.className(), rawHash, t.effHash(), metaHash));
		}
		long seq = head.seq() + 1L;
		Changeset next = Changeset.create(head.id(), seq, roundId, loaderScope, entries,
		 Changeset.Manifest.EMPTY, List.of(), algoVersion, buildId, patchTiming,
		 head.eff0(), plan.targetSeq());
		chain.addLast(next);
		int window = window();
		while (chain.size() > window) chain.removeFirst();
		return next;
	}

	/**
	 * 内容寻址哈希：Eff 字节与 {@code Changeset.Entry.effHash} 必须用同一个函数。
	 *
	 * <p>这里刻意用<b>完整 64 位十六进制</b>而不是树根那种 128 位截断：{@code Entry.effHash} 来自
	 * {@link AlignmentMetaStore} 的 {@code jvmEffSha256}（全宽），两者不一致时按哈希取字节永远取不到。</p>
	 */
	public static String effSha256(byte[] bytes) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			StringBuilder sb = new StringBuilder(64);
			for (byte b : digest) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
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

	/** 当前仍有链的 scope 代号集合（升序，供回退入口按 scope 逐个执行）。 */
	public synchronized List<Long> liveScopes() {
		List<Long> scopes = new ArrayList<>(chains.keySet());
		Collections.sort(scopes);
		return scopes;
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
		effBlobs.clear();
		effBytesHeld = 0L;
		effDroppedCount = 0;
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
		private final transient List<String> missingClasses;

		NotRevertableException(Changeset target, Changeset.Availability level) {
			this(target, level, List.of());
		}

		NotRevertableException(Changeset target, Changeset.Availability level, List<String> missingClasses) {
			super("NOT_REVERTABLE: seq=" + (target == null ? -1 : target.seq())
			 + " level=" + level
			 + (missingClasses == null || missingClasses.isEmpty() ? "" : " missing=" + missingClasses));
			this.target = target;
			this.level = level;
			this.missingClasses = missingClasses == null ? List.of() : List.copyOf(missingClasses);
		}

		public List<String> missingClasses() {
			return missingClasses;
		}

		public Changeset target() {
			return target;
		}

		public Changeset.Availability level() {
			return level;
		}
	}
}
