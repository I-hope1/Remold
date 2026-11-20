package remold;

import remold.util.Names;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import remold.ref.InitFix;
import remold.vcs.AlignmentMetaStore;
import remold.vcs.BytecodePack;
import remold.vcs.Changeset;
import remold.vcs.ChangesetLog;

/**
 * 动态重定义的提交刻度：{@code redefineDynamic} 自成一代（{@code roundId = "dynamic:<fqcn>"}）。
 *
 * <p><b>为什么不并入批次</b>：{@code redefineDynamic} 是独立持锁的单类事务（{@code HOTSWAP_LOCK}、
 * 自己的 {@code BatchReportScope}、自己的 A→B→C），调用时往往根本没有进行中的
 * {@code BatchHotswapExecutor} 批次。若等下一次文件变更再顺带记账，中间的 JVM 生效字节、
 * 加载前沿水位线与代际台账会长期脱账，期间任何区间回退都会漏掉这一轮。</p>
 *
 * <p><b>状态转移与 {@code seq} 的关系</b>（规格 {@code docs/vcs/01} §2.1.1 的提交刻度）：</p>
 * <ol>
 *   <li><b>no-op</b>：{@code oldBytes == runtimeBytes}，没有调用 {@code redefineClasses} → 不提交、不 abort；</li>
 *   <li><b>失败</b>：桥接/布局门/层级门/注解默认值门/Stage C 失败 → {@link #abort(ClassLoader)} 记中止计数，
 *       {@code seq} 不前进；</li>
 *   <li><b>成功</b>：{@code redefineClasses} 返回之后 → {@link #commit} 产生一代，{@code seq + 1}。</li>
 * </ol>
 *
 * <p><b>字节四元组口径</b>：动态路径没有 {@code LambdaAligner}，入参即源码字节，桥接与对齐之后的终稿才是
 * 生效字节。因此 {@code preAlign = sourceBytes}、{@code alignOutput = jvmBytes = runtimeBytes}、
 * {@code effBefore = oldBytes}、{@code effNow = runtimeBytes}。</p>
 *
 * <p><b>已知边界</b>：本入口不建立 InitFix 批次上下文，{@link InitFix#batchCommitManifest} 与
 * {@link InitFix#batchReplayFacts} 会各自退回空表——动态代的 manifest/facts 因此是空的，不代表
 * "本轮没有字段决策"，只代表"本入口不做批次级分析"。单类包一层批次上下文是后续项（见 {@code docs/status.md}）。</p>
 */
final class DynamicVcsCommit {

	static final String ROUND_PREFIX = "dynamic:";

	private DynamicVcsCommit() { }

	/**
	 * 为一次<b>已成功</b>的动态重定义提交一代。
	 *
	 * @param target       被重定义的类
	 * @param oldBytes     重定义前的生效字节（基线）；可为 {@code null}（此时不写 {@code Eff_0}）
	 * @param sourceBytes  入参源码字节（未织入/未对齐）
	 * @param runtimeBytes 最终交给 {@code redefineClasses} 的生效字节
	 * @param twoPhase     A→B→C 两阶段是否已应用（决定 {@code patchTiming}）
	 * @return 提交出的代；提交失败返回 {@code null}（调用方不得据此回滚 JVM 状态）
	 */
	static Changeset commit(Class<?> target, byte[] oldBytes, byte[] sourceBytes, byte[] runtimeBytes,
	                        boolean twoPhase) {
		if (target == null || runtimeBytes == null) return null;
		ClassLoader loader = target.getClassLoader();
		String dot = target.getName();
		String slash = Names.internal(dot);
		try {
			Set<String> invented = Set.of();
			Map<String, Long> ghosts = Map.of();
			Map<String, String> mapping = Map.of();
			int bridges = 0;
			AlignmentMetaStore.INSTANCE.commit(loader, slash, sourceBytes, runtimeBytes, runtimeBytes,
			 invented, ghosts, mapping, bridges, Map.of());
			AlignmentMetaStore.Entry head = AlignmentMetaStore.INSTANCE.head(loader, slash);
			if (head == null) return null;

			String metaHash = Changeset.metaDigest(slash, invented, ghosts, mapping, bridges);
			Map<String, Changeset.Entry> entries = new LinkedHashMap<>();
			entries.put(slash, new Changeset.Entry(slash, head.preAlignSha256, head.jvmEffSha256, metaHash));

			Changeset.Manifest manifest = InitFix.batchCommitManifest(List.of(target));
			List<Changeset.ReplayFact> facts = InitFix.batchReplayFacts();
			Changeset.PatchTiming timing = twoPhase
			 ? Changeset.PatchTiming.BEFORE_COMMIT : Changeset.PatchTiming.AFTER_COMMIT;
			long scope = AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
			Map<String, byte[]> effBefore = oldBytes == null ? Map.of() : Map.of(slash, oldBytes);
			Map<String, byte[]> effNow = Map.of(slash, runtimeBytes);

			Changeset cs = ChangesetLog.INSTANCE.commit(scope, ROUND_PREFIX + dot, entries, manifest, facts,
			 Changeset.currentAlgoVersion(), System.getProperty("remold.build_id", ""), timing,
			 effBefore, effNow);
			if (BytecodePack.enabled()) {
				// 与批次侧同序：先把本轮的类×代条目写进单轮布局，exportRound 才匹配得到（否则 entryCount=0）。
				BytecodePack.append(new BytecodePack.Entry(slash, oldBytes, sourceBytes, runtimeBytes,
				 runtimeBytes, invented, mapping, bridges));
				BytecodePack.exportRound(cs);
			}
			VcsAgentCoordinator.recordLedgerFacts(loader, cs);
			if (RemoldAgent.DEBUG) {
				RemoldAgent.log("[VCS] dynamic round committed: seq=" + cs.seq() + " round=" + cs.roundId());
			}
			return cs;
		} catch (Throwable t) {
			// 提交失败只影响历史可查性，绝不影响已经生效的 JVM 状态：不抛、不回滚。
			RemoldAgent.warn("[VCS] dynamic round commit failed for " + dot + ": " + t);
			return null;
		}
	}

	/** 记一次中止（{@code seq} 不前进，只累加诊断计数）。 */
	static void abort(ClassLoader loader) {
		ChangesetLog.INSTANCE.abort(AlignmentMetaStore.INSTANCE.loaderScopeId(loader));
	}
}
