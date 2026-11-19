package remold.vcs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collection;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 一轮热更提交刻度的不可变记录（{@code docs/topology/16-bytecode-changeset-vcs.md} §2.1.1）。
 *
 * <p>本类型只持有字符串、哈希与 loader scope 代号：不持有 {@code Class}、{@code ClassLoader}、
 * {@code ClassNode} 或任何字节数组，因此可以安全地长期驻留而不拖住类元数据（不变量 C8）。</p>
 *
 * <p>{@code manifest} 与 {@code replayFacts} 是独立字段，不塞进 {@link Entry}：前者是批次级快照，
 * 后者是提交级事实，混进"每类一档"的 entries 会污染其语义，并且区间回退时需要迁移格式。</p>
 */
public record Changeset(
 String id,
 String parentId,
 long seq,
 String roundId,
 long loaderScope,
 Map<String, Entry> entries,
 Manifest manifest,
 List<ReplayFact> replayFacts,
 String rawRoot,
 String effRoot,
 String metaRoot,
 String algoVersion,
 String buildId,
 PatchTiming patchTiming,
 Map<String, String> eff0,
 long revertedToSeq) {

	/** 提交时刻字段补丁的状态时刻：单阶段补丁在提交之后，两阶段在提交之前（§2.1.3）。 */
	public enum PatchTiming {
		/** 两阶段：A→B→C 在提交点之前完成，提交时刻字段已补。 */
		BEFORE_COMMIT,
		/** 单阶段：提交点之后才跑 Stage B，提交时刻字段尚未补。 */
		AFTER_COMMIT
	}

	/** 代际可用性（§2.1.1 三级表）。 */
	public enum Availability {
		/** 只需 Changeset 本身：可查代、可读 manifest 与 replayFacts。 */
		ADDRESSABLE,
		/** 另有 Raw 字节与 Meta 记录：可重算 Eff、可离线逐字节重放。 */
		REPLAYABLE,
		/** Eff 字节可得：可运行期回退到该代。 */
		REVERTABLE
	}

	/** 每类一档：三棵树的哈希引用。 */
	public record Entry(String className, String rawHash, String effHash, String metaHash) {
		public Entry {
			className = Objects.requireNonNull(className, "className");
			rawHash = rawHash == null ? "" : rawHash;
			effHash = effHash == null ? "" : effHash;
			metaHash = metaHash == null ? "" : metaHash;
		}
	}

	/**
	 * 批次分析快照（§2.1.2）。字段全是字符串，拒绝原因用枚举码而非自由文本。
	 *
	 * @param orderedHosts     本轮 Stage B 宿主顺序（{@code loaderScope:类名}），拓扑序
	 * @param edges            字段级有向边 {@code fromHost.fromField -> toHost.toField}
	 * @param nodes            参与依赖判定的字段；被拒者带枚举码后缀
	 * @param candidateMissing 因候选类字节缺失而 fail-closed 的类名
	 */
	public record Manifest(List<String> orderedHosts, List<String> edges, List<String> nodes,
	                       List<String> candidateMissing) {
		public Manifest {
			orderedHosts = copy(orderedHosts);
			edges = copy(edges);
			nodes = copy(nodes);
			candidateMissing = copy(candidateMissing);
		}

		private static List<String> copy(List<String> in) {
			return in == null ? List.of() : List.copyOf(in);
		}

		public static final Manifest EMPTY = new Manifest(List.of(), List.of(), List.of(), List.of());

		/** 空边集是合法提交：该类的 Raw/Eff 变化仍需提交坐标，否则后续 parent 会断层（§6.3）。 */
		public boolean hasEdges() {
			return !edges.isEmpty();
		}
	}

	/**
	 * 提交级事实（§3.8）：某一代该字段的初值是什么、来自哪种重放档位。
	 *
	 * @param tier        {@code L1} / {@code A} / {@code REINIT}
	 * @param literalKind {@code INT} / {@code LONG} / {@code FLOAT_BITS} / {@code DOUBLE_BITS} /
	 *                    {@code STRING} / {@code NULL} / {@code NONE}
	 */
	public record ReplayFact(String ownerClass, String field, String desc, String tier,
	                         String oldLiteral, String newLiteral, String literalKind,
	                         String provenance, String scope, String mode) {
		public ReplayFact {
			ownerClass = Objects.requireNonNull(ownerClass, "ownerClass");
			field = Objects.requireNonNull(field, "field");
			desc = desc == null ? "" : desc;
			tier = tier == null ? "" : tier;
			literalKind = literalKind == null ? "NONE" : literalKind;
		}
	}

	public Changeset {
		id = Objects.requireNonNull(id, "id");
		if (seq < 1) throw new IllegalArgumentException("seq must start at 1, got " + seq);
		roundId = roundId == null ? "" : roundId;
		buildId = buildId == null ? "" : buildId;
		algoVersion = algoVersion == null ? "" : algoVersion;
		entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
		manifest = manifest == null ? Manifest.EMPTY : manifest;
		replayFacts = replayFacts == null ? List.of() : List.copyOf(replayFacts);
		patchTiming = patchTiming == null ? PatchTiming.AFTER_COMMIT : patchTiming;
		eff0 = eff0 == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(eff0));
		if (revertedToSeq < 0) throw new IllegalArgumentException("revertedToSeq must be >= 0");
	}

	/**
	 * 构造一条代际记录并计算内容寻址 {@code id} 与三棵树根哈希。
	 *
	 * <p>{@code id} 对 {@code (parentId, seq, 内容)} 确定；同一份字节内容在不同 {@code seq} 上必须得到
	 * 不同 {@code id}（{@code A → B → A} 的回改是新代，不是旧代复用）。</p>
	 */
	public static Changeset create(String parentId, long seq, String roundId, long loaderScope,
	                               Map<String, Entry> entries, Manifest manifest,
	                               List<ReplayFact> replayFacts, String algoVersion, String buildId,
	                               PatchTiming patchTiming) {
		return create(parentId, seq, roundId, loaderScope, entries, manifest, replayFacts,
		 algoVersion, buildId, patchTiming, Map.of(), 0L);
	}

	/**
	 * 扩展构造：额外固化 {@code Eff_0} 基线哈希与（回退时的）目标代序号。
	 *
	 * <p>{@code eff0} 只在该类首次进入本 scope 链的那一代填写，之后各代原样携带；它是区间回退到
	 * 「首次修改之前」时的唯一字节来源（{@code docs/topology/16-bytecode-changeset-vcs.md} §2.1.1）。</p>
	 */
	public static Changeset create(String parentId, long seq, String roundId, long loaderScope,
	                               Map<String, Entry> entries, Manifest manifest,
	                               List<ReplayFact> replayFacts, String algoVersion, String buildId,
	                               PatchTiming patchTiming, Map<String, String> eff0,
	                               long revertedToSeq) {
		Map<String, Entry> sorted = new TreeMap<>(entries);
		String rawRoot = rootOf(sorted, Entry::rawHash);
		String effRoot = rootOf(sorted, Entry::effHash);
		String metaRoot = rootOf(sorted, Entry::metaHash);
		String eff0Digest = identityRoot(eff0);
		String id = sha128(String.join("\u0000",
		 parentId == null ? "" : parentId,
		 Long.toString(seq),
		 sortedDigest(sorted),
		 rawRoot, effRoot, metaRoot,
		 manifestDigest(manifest),
		 eff0Digest,
		 Long.toString(revertedToSeq),
		 algoVersion == null ? "" : algoVersion));
		return new Changeset(id, parentId, seq, roundId, loaderScope, sorted, manifest, replayFacts,
		 rawRoot, effRoot, metaRoot, algoVersion, buildId, patchTiming, eff0, revertedToSeq);
	}

	private static String identityRoot(Map<String, String> values) {
		Map<String, String> sorted = new TreeMap<>(values == null ? Map.of() : values);
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : sorted.entrySet()) {
			sb.append(e.getKey()).append('\u0001').append(e.getValue()).append('\u0001');
		}
		return sha128(sb.toString());
	}

	private static String rootOf(Map<String, Entry> sorted, Function<Entry, String> hash) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Entry> e : sorted.entrySet()) {
			sb.append(e.getKey()).append('\u0001').append(hash.apply(e.getValue())).append('\u0001');
		}
		return sha128(sb.toString());
	}

	private static String sortedDigest(Map<String, Entry> sorted) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Entry> e : sorted.entrySet()) {
			Entry v = e.getValue();
			sb.append(e.getKey()).append('\u0001')
			 .append(v.rawHash()).append('\u0001')
			 .append(v.effHash()).append('\u0001')
			 .append(v.metaHash()).append('\u0002');
		}
		return sha128(sb.toString());
	}

	private static String manifestDigest(Manifest manifest) {
		if (manifest == null) return sha128("");
		List<String> parts = new ArrayList<>();
		parts.add(String.join("\u0001", manifest.orderedHosts()));
		parts.add(String.join("\u0001", manifest.edges()));
		parts.add(String.join("\u0001", manifest.nodes()));
		parts.add(String.join("\u0001", manifest.candidateMissing()));
		return sha128(String.join("\u0002", parts));
	}

	/**
	 * Meta 树的条目指纹：把自造名、幽灵生前指纹、方法映射与桥数量按确定序规范化后取哈希。
	 *
	 * <p>规范化顺序固定（集合排序、映射按键排序），因此同一份元数据在任何遍历顺序下得到同一哈希。</p>
	 */
	public static String metaDigest(String className, Collection<String> inventedNames,
	                                Map<String, Long> ghostHashes, Map<String, String> methodMapping,
	                                int bridgeCount) {
		StringBuilder sb = new StringBuilder(className == null ? "" : className);
		List<String> invented = new ArrayList<>(inventedNames == null ? List.of() : inventedNames);
		Collections.sort(invented);
		for (String name : invented) sb.append('|').append(name);
		Map<String, Long> ghosts = new TreeMap<>(ghostHashes == null ? Map.of() : ghostHashes);
		for (Map.Entry<String, Long> e : ghosts.entrySet()) sb.append('#').append(e.getKey()).append('=').append(e.getValue());
		Map<String, String> mapping = new TreeMap<>(methodMapping == null ? Map.of() : methodMapping);
		for (Map.Entry<String, String> e : mapping.entrySet()) sb.append('@').append(e.getKey()).append('>').append(e.getValue());
		sb.append('~').append(bridgeCount);
		return sha128(sb.toString());
	}

	/** 派生算法版本指纹（§2.1.1）：ASM API 级别 + 运行期 JDK + 本契约版本。 */
	public static String currentAlgoVersion() {
		// vcs=2: replayFacts 的 L1 档新侧改为真实原始位模式（此前是十进制文本，浮点会失真）。
		// 位模式参与 id 预算，格式变更必须体现在版本号里，旧记录才能被识别并拒绝。
		return "vcs=2|asm=9|jdk=" + Runtime.version().feature();
	}

	/** SHA-256 截断到 128 位，十六进制小写（§2.2 内容哈希规则）。 */
	static String sha128(String text) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(32);
			for (int i = 0; i < 16; i++) sb.append(String.format("%02x", digest[i]));
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
