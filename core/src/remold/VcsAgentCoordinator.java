package remold;

import remold.ref.InitFix;
import remold.util.CRC64;
import remold.vcs.AlignmentMetaStore;
import remold.vcs.BytecodePack;
import remold.vcs.Changeset;
import remold.vcs.ChangesetLog;
import remold.vcs.GenerationalFieldLedger;
import remold.vcs.VcsCommitGate;

import java.lang.instrument.ClassDefinition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static remold.RemoldAgent.*;

/**
 * VCS 旁路提交与代际回退协调器。
 */
final class VcsAgentCoordinator {
	private VcsAgentCoordinator() {}

	/**
	 * PR1 旁路暂存：本批次"对齐已经算完、但尚未确认 JVM 最终生效"的逐类记录。
	 *
	 * <p>写入点在 {@code LambdaAligner.align} 返回之后，<b>不是</b>提交点：真正提交只在批次最终结果确定后由
	 * {@link #commitVcsBatch} 完成；本批任何回滚或 {@code LAMBDA_STRICT} 拒绝都会整批丢弃。</p>
	 */
	static final Map<String, VcsPending> PENDING_VCS = new ConcurrentHashMap<>();

	/** 旁路暂存体：对齐输入/产出 + 当轮契约字段。只在批次内短暂存活。 */
	static final class VcsPending {
		final ClassLoader loader;
		final byte[] oldEffective;
		final byte[] preAlign;
		final byte[] alignOutput;
		final LambdaAligner.AlignmentResult result;

		VcsPending(ClassLoader loader, byte[] oldEffective, byte[] preAlign, byte[] alignOutput,
		           LambdaAligner.AlignmentResult result) {
			this.loader = loader;
			this.oldEffective = oldEffective;
			this.preAlign = preAlign;
			this.alignOutput = alignOutput;
			this.result = result;
		}
	}

	/**
	 * 提交本批次的旁路记录（PR1）。
	 *
	 * <p><b>整批原子</b>：只有本批所有进入重定义的类最终都生效、且没有任何事务回滚 /
	 * {@code LAMBDA_STRICT} 拒绝时才记录；否则整批丢弃。</p>
	 */
	static void commitVcsBatch(List<ClassDefinition> definitions, Set<String> redefinedOk,
	                                   Set<String> rolledBack, boolean lambdaStrictRejected,
	                                   boolean twoPhaseApplied) {
		int total = definitions == null ? 0 : definitions.size();
		int applied = 0;
		if (definitions != null) {
			for (ClassDefinition def : definitions) {
				if (redefinedOk.contains(def.getDefinitionClass().getName())) applied++;
			}
		}
		if (!VcsCommitGate.shouldCommit(total, applied, rolledBack.size(), lambdaStrictRejected)) {
			int discarded = PENDING_VCS.size();
			PENDING_VCS.clear();
			if (definitions != null) {
				for (ClassDefinition def : definitions) {
					// aborted 轮次只记诊断计数：不分配 seq、不推进 head（规格 §6.3）。
					ChangesetLog.INSTANCE.abort(
					 AlignmentMetaStore.INSTANCE.loaderScopeId(def.getDefinitionClass().getClassLoader()));
				}
			}
			if (DEBUG) log("[VCS] batch not fully applied (ok=" + redefinedOk.size()
				+ "/" + (definitions == null ? 0 : definitions.size())
				+ ", rolledBack=" + rolledBack.size() + ", lambdaStrict=" + lambdaStrictRejected
				+ "); " + discarded + " pending record(s) discarded.");
			return;
		}
		// 每个 loader scope 一条链：entries 按 scope 分组，批次原子性已由上面的 gate 保证（§2.1.3）。
		Map<ClassLoader, Map<String, Changeset.Entry>> perScope = new IdentityHashMap<>();
		Map<ClassLoader, Map<String, byte[]>> effBefore = new IdentityHashMap<>();
		Map<ClassLoader, Map<String, byte[]>> effNow = new IdentityHashMap<>();
		Map<ClassLoader, Set<String>> scopeClassNames = new IdentityHashMap<>();
		List<Class<?>> committedClasses = new ArrayList<>();
		for (ClassDefinition def : definitions) {
			String className = def.getDefinitionClass().getName();
			String slashName = className.replace('.', '/');
			VcsPending p = PENDING_VCS.remove(className);
			if (p == null) continue;
			byte[] jvmBytes = def.getDefinitionClassFile();
			try {
				Map<String, Long> ghosts = LambdaAligner.scanGhostOriginHashes(p.alignOutput);
				Set<String> invented = p.result == null ? Set.of() : p.result.inventedNames;
				Map<String, String> mapping = p.result == null ? Map.of() : p.result.methodMapping;
				// 下一轮 Raw <-> Raw 的翻译表：Raw 方法键 → 本轮生效侧方法键。显式传值（不依赖
				// "align 之后立刻 commit"的时序），批次间被丢弃时为空表 —— 读侧会整类退回兼容跑道。
				Map<String, String> rawToEff = p.result == null ? Map.of() : p.result.rawToEffKeys;
				int bridges = p.result == null ? 0 : p.result.bridgeCount;
				AlignmentMetaStore.INSTANCE.commit(p.loader, slashName,
					p.preAlign, p.alignOutput, jvmBytes, invented, ghosts, mapping, bridges, rawToEff);
				AlignmentMetaStore.Entry head = AlignmentMetaStore.INSTANCE.head(p.loader, slashName);
				if (head != null) {
					String metaHash = Changeset.metaDigest(slashName, invented, ghosts, mapping, bridges);
					perScope.computeIfAbsent(p.loader, ignored -> new LinkedHashMap<>())
					 .put(slashName, new Changeset.Entry(slashName, head.preAlignSha256,
					  head.jvmEffSha256, metaHash));
					scopeClassNames.computeIfAbsent(p.loader, ignored -> new LinkedHashSet<>()).add(slashName);
					if (p.oldEffective != null) {
						effBefore.computeIfAbsent(p.loader, ignored -> new LinkedHashMap<>()).put(slashName, p.oldEffective);
					}
					effNow.computeIfAbsent(p.loader, ignored -> new LinkedHashMap<>()).put(slashName, jvmBytes);
				}
				committedClasses.add(def.getDefinitionClass());
				if (BytecodePack.enabled()) {
					BytecodePack.append(new BytecodePack.Entry(slashName, p.oldEffective, p.preAlign,
					 p.alignOutput, jvmBytes, invented, mapping, bridges));
				}
			} catch (Throwable t) {
				warn("[VCS] record failed for " + className + ": " + t);
			}
		}
		PENDING_VCS.clear();
		if (!perScope.isEmpty()) {
			String roundId = definitions.isEmpty() ? "" : TwoPhaseCoordinator.batchWatchLabel(definitions);
			Changeset.Manifest manifest = InitFix.batchCommitManifest(committedClasses);
			List<Changeset.ReplayFact> facts = InitFix.batchReplayFacts();
			Changeset.PatchTiming timing = twoPhaseApplied
			 ? Changeset.PatchTiming.BEFORE_COMMIT : Changeset.PatchTiming.AFTER_COMMIT;
			String buildId = System.getProperty("remold.build_id", "");
			for (Map.Entry<ClassLoader, Map<String, Changeset.Entry>> scope : perScope.entrySet()) {
				try {
					Changeset cs = ChangesetLog.INSTANCE.commit(
					 AlignmentMetaStore.INSTANCE.loaderScopeId(scope.getKey()), roundId,
					 scope.getValue(), scopeManifest(manifest, scopeClassNames.get(scope.getKey())),
					 facts, Changeset.currentAlgoVersion(), buildId, timing,
					 effBefore.getOrDefault(scope.getKey(), Map.of()),
					 effNow.getOrDefault(scope.getKey(), Map.of()));
					// 链式回放包按 seq 逐轮导出：提交后立即写本轮目录，离线侧可按任意 seq 单独检出（§7.1）。
					if (BytecodePack.enabled()) BytecodePack.exportRound(cs);
					// 台账是派生索引（docs/vcs/05 §2.3）：逐条事实带代坐标入索引，口径不合规的
					// 记录在这里就被拒（不索引），消费侧只会看到规范事实。
					recordLedgerFacts(scope.getKey(), cs);
				} catch (Throwable t) {
					warn("[VCS] changeset commit failed: " + t);
				}
			}
		}
	}

	/**
	 * 把批次级 manifest 收窄到单个 scope：跨 scope 时每条 Changeset 只带自己范围内的宿主与边（§2.1.3）。
	 */
	private static Changeset.Manifest scopeManifest(Changeset.Manifest manifest, Set<String> slashNames) {
		if (manifest == null || slashNames == null || slashNames.isEmpty()) {
			return manifest == null ? Changeset.Manifest.EMPTY : manifest;
		}
		Set<String> dotted = new LinkedHashSet<>();
		for (String name : slashNames) dotted.add(name.replace('/', '.'));
		List<String> hosts = new ArrayList<>();
		for (String host : manifest.orderedHosts()) {
			for (String name : dotted) {
				if (host.endsWith(":" + name)) {
					hosts.add(host);
					break;
				}
			}
		}
		List<String> nodes = new ArrayList<>();
		for (String node : manifest.nodes()) {
			for (String name : dotted) {
				if (node.startsWith(name + ".")) {
					nodes.add(node);
					break;
				}
			}
		}
		List<String> missing = new ArrayList<>();
		for (String name : manifest.candidateMissing()) {
			if (dotted.contains(name)) missing.add(name);
		}
		return new Changeset.Manifest(hosts, manifest.edges(), nodes, missing);
	}

	/**
	 * 把一代的 {@code replayFacts} 逐条推进代际台账（{@code docs/vcs/05-generational-field-ledger.md} §2.3）。
	 *
	 * <p>台账是<b>派生索引</b>：口径不合规的记录在这里被拒（不索引、计数），消费侧因此只会看到
	 * 规范事实；索引丢了可以按 {@code Changeset} 重建，事实丢了只能拒绝。</p>
	 */
	static void recordLedgerFacts(ClassLoader loader, Changeset cs) {
		// {@code loader == null} 是 bootstrap 槽（scope 0）的合法情形：台账专门为它留了槽，
		// 这里若跳过就会出现"台账有槽、提交侧永不填"的口径不对称。
		if (cs == null || cs.replayFacts().isEmpty()) return;
		GenerationalFieldLedger.GenRef gen = new GenerationalFieldLedger.GenRef(cs.seq(), cs.id());
		for (Changeset.ReplayFact fact : cs.replayFacts()) {
			String internal = fact.ownerClass().replace('.', '/');
			GenerationalFieldLedger.INSTANCE.record(loader,
			 new GenerationalFieldLedger.LedgerKey(cs.loaderScope(), internal, fact.field()),
			 gen, fact, cs.algoVersion());
		}
		// 历史索引跟随 Changeset 窗口，没有第二个旋钮（§2.3）。
		GenerationalFieldLedger.INSTANCE.pruneToWindow(loader,
		 ChangesetLog.INSTANCE.chain(cs.loaderScope()).isEmpty() ? 1L
		  : ChangesetLog.INSTANCE.chain(cs.loaderScope()).get(0).seq());
	}

	/**
	 * 回退到某个已提交代（{@code docs/vcs/01-bytecode-changeset-vcs.md} §2.1.1 / §4.1）。
	 *
	 * <p>只承诺<b>未来正确</b>：结构与字方法体回到目标代，已经写进存量实例的字段值保持现状，
	 * 该堆事实由调用方记入 {@code PatchReport} 与告警。</p>
	 *
	 * <p><b>三阶段，任一阶段失败都不留下半套回退</b>：</p>
	 * <ol>
	 *   <li><b>预检</b>：所有 scope 都先算计划、找类、确认目标可达。不动 JVM、不动工作头、不动链、
	 *       不动缓存——多 scope 时"第 1 个成功、第 2 个抛错"是原来最危险的形态。</li>
	 *   <li><b>redefine</b>：全部 scope 的字节都装进 JVM。任一 scope 失败就把已 redefine 的 scope
	 *       尽力写回回退前字节并告警，且<b>不追加任何 Changeset</b>。</li>
	 *   <li><b>工作头 + 缓存 + 链</b>：代际目标走 {@code restoreHead}，{@code Eff_0} 目标走
	 *       {@code forgetGenerations}；然后把 agent 侧四张内存表对齐到"JVM 现在真正在跑的字节"，
	 *       最后才追加前向代。</li>
	 * </ol>
	 *
	 * <p>成功路径追加一条前向 Changeset（{@code seq = HEAD+1}、{@code revertedToSeq = target}），
	 * 不倒拨 {@code seq}、不造分叉，链式导出保持线性。</p>
	 *
	 * @return 人可读的回退摘要；开 {@code -Dremold.vcs.revert} 才允许执行
	 */
	static String revertTo(long targetSeq) {
		if (!Boolean.getBoolean("remold.vcs.revert")) {
			throw new IllegalStateException("NOT_REVERTABLE: revert execution is disabled"
			 + " (set -Dremold.vcs.revert=true)");
		}
		if (inst == null) throw new IllegalStateException("NOT_REVERTABLE: instrumentation is not attached");

		// ---- 阶段 1：全量预检 ----
		Map<Long, ChangesetLog.RevertPlan> plans = new LinkedHashMap<>();
		List<RevertScope> work = new ArrayList<>();
		for (long scope : ChangesetLog.INSTANCE.liveScopes()) {
			ChangesetLog.RevertPlan plan = ChangesetLog.INSTANCE.revertPlan(scope, targetSeq);
			plans.put(scope, plan);
			if (plan.isEmpty()) continue;
			RevertScope step = new RevertScope(scope, plan);
			for (ChangesetLog.RevertTarget target : plan.targets().values()) {
				Class<?> cls = findLoadedClassByName(target.className());
				if (cls == null) {
					throw new IllegalStateException("NOT_REVERTABLE: class is not loaded: " + target.className());
				}
				ClassLoader loader = cls.getClassLoader();
				// {@code Eff_0} 从未进过 AlignmentMetaStore，{@code hasGeneration} 对它必然 false：
				// 两条路径必须分开判，用同一个放水判据会让"回退到原始态"永远被拒。
				if (target.fromGeneration()
				 && !AlignmentMetaStore.INSTANCE.hasGeneration(loader, target.className(), target.effHash())) {
					throw new IllegalStateException("NOT_REVERTABLE: working head for " + target.className()
					 + " is no longer addressable at the target generation");
				}
				step.add(new RevertClass(cls, loader, target,
				 bytecodeCache.get(target.className().replace('/', '.'))));
			}
			work.add(step);
		}

		// ---- 阶段 2：把字节全部装进 JVM ----
		List<RevertScope> redefined = new ArrayList<>();
		try {
			for (RevertScope scope : work) {
				inst.redefineClasses(scope.definitions().toArray(new ClassDefinition[0]));
				redefined.add(scope);
			}
		} catch (Throwable t) {
			restorePreRevertBytes(redefined);
			throw new IllegalStateException("NOT_REVERTABLE: revert redefine failed: " + t, t);
		}

		// ---- 阶段 3a：可逆的工作头切换（代际目标）----
		try {
			for (RevertScope scope : redefined) {
				for (RevertClass rc : scope.classes) {
					if (!rc.target.fromGeneration()) continue;
					if (!AlignmentMetaStore.INSTANCE.restoreHead(rc.loader, rc.target.className(),
					 rc.target.effHash())) {
						throw new IllegalStateException("revert redefine succeeded but working head sync failed for "
						 + rc.target.className() + "; the next Run R baseline would be wrong");
					}
				}
			}
		} catch (RuntimeException e) {
			restorePreRevertBytes(redefined);
			throw e;
		}

		// ---- 阶段 3b：{@code Eff_0} 目标的代际身份作废（不可逆，必须放在最后）----
		for (RevertScope scope : redefined) {
			for (RevertClass rc : scope.classes) {
				if (rc.target.fromGeneration()) continue;
				int dropped = AlignmentMetaStore.INSTANCE.forgetGenerations(rc.loader, rc.target.className());
				warn("[VCS] " + rc.target.className() + " reverts to its Eff_0 baseline: dropped "
				 + dropped + " recorded generation(s), so the next Run R falls back to Run E.");
			}
		}

		// ---- 阶段 3c：缓存同步 + 追加前向代 ----
		StringBuilder summary = new StringBuilder();
		for (Map.Entry<Long, ChangesetLog.RevertPlan> entry : plans.entrySet()) {
			long scope = entry.getKey();
			ChangesetLog.RevertPlan plan = entry.getValue();
			if (plan.isEmpty()) {
				summary.append("scope=").append(scope).append(":noop;");
				continue;
			}
			ClassLoader forwardLoader = null;
			for (RevertScope done : redefined) {
				if (done.scope != scope) continue;
				for (RevertClass rc : done.classes) {
					// 四张 agent 内存表必须与"JVM 现在真正在跑的字节"对齐，否则下一轮
					// LambdaAligner / ClassDiffUtil / 磁盘跳过判据都会拿被退掉的污染代当基线。
					syncCachesAfterRevert(rc.loader, rc.target);
					if (forwardLoader == null) forwardLoader = rc.loader;
				}
			}
			Changeset forward = ChangesetLog.INSTANCE.appendForwardRevert(scope, plan,
			 batchWatchLabelFromNames(plan.targets().keySet()), Changeset.currentAlgoVersion(),
			 System.getProperty("remold.build_id", ""), Changeset.PatchTiming.AFTER_COMMIT);
			// 被回退掉的代不再有任何承诺：事实与引擎写入记录整体作废（20 §3.4），宁可多写一次。
			int invalidated = GenerationalFieldLedger.INSTANCE.invalidateAfterRevert(forwardLoader, targetSeq);
			warn("[VCS] reverted scope " + scope + " to seq " + targetSeq + " (" + plan.targets().size()
			 + " class(es)); existing instance field values are NOT rolled back"
			 + " (heap values keep their current state, structure/bodies follow seq " + targetSeq + ").");
			summary.append("scope=").append(scope).append(":seq=").append(forward.seq())
			 .append(":classes=").append(plan.targets().size())
			 .append(":invalidated=").append(invalidated).append(';');
		}
		return summary.toString();
	}

	/** 一次回退里的一个类：类对象、目标，以及回退前 JVM 里跑的字节（失败回滚用）。 */
	private record RevertClass(Class<?> type, ClassLoader loader, ChangesetLog.RevertTarget target,
	                           byte[] preRevertBytes) { }

	/** 一个 scope 的回退单元（records 不能增量构建，这里用可变容器）。 */
	private static final class RevertScope {
		final long scope;
		final ChangesetLog.RevertPlan plan;
		final List<RevertClass> classes = new ArrayList<>();

		RevertScope(long scope, ChangesetLog.RevertPlan plan) {
			this.scope = scope;
			this.plan = plan;
		}

		void add(RevertClass rc) {
			classes.add(rc);
		}

		List<ClassDefinition> definitions() {
			List<ClassDefinition> defs = new ArrayList<>(classes.size());
			for (RevertClass rc : classes) defs.add(new ClassDefinition(rc.type, rc.target.bytes()));
			return defs;
		}
	}

	/**
	 * 多 scope 回退中途失败时的尽力回滚：把已经 redefine 的类写回回退前字节，并明确告警。
	 *
	 * <p>"尽力"是诚实的边界：回滚本身也可能失败（JVM 拒绝二次重定义等），那时唯一的正确动作是
	 * 告警要求重启，而不是假装回退没发生过。</p>
	 */
	private static void restorePreRevertBytes(List<RevertScope> redefined) {
		for (RevertScope scope : redefined) {
			List<ClassDefinition> back = new ArrayList<>(scope.classes.size());
			for (RevertClass rc : scope.classes) {
				if (rc.preRevertBytes == null || rc.preRevertBytes.length == 0) continue;
				back.add(new ClassDefinition(rc.type, rc.preRevertBytes));
			}
			if (back.isEmpty()) continue;
			try {
				inst.redefineClasses(back.toArray(new ClassDefinition[0]));
				warn("[VCS] revert aborted after scope " + scope.scope + " was already redefined;"
				 + " restored the pre-revert bytes of " + back.size() + " class(es) in that scope.");
			} catch (Throwable t) {
				warn("[VCS] revert aborted AND the pre-revert bytes of scope " + scope.scope
				 + " could not be restored (" + t + "); a restart is required.");
			}
		}
	}

	/**
	 * 把 agent 侧四张表对齐到回退后的目标代。
	 *
	 * <p>漏掉这一步不会立刻报错，而是<b>下一轮</b>才错：{@code bytecodeCache} 决定
	 * {@code LambdaAligner.align} 的旧侧与 {@code ClassDiffUtil.diff} 的基线，{@code sourceBytecodeCache}
	 * 决定源码基线，{@code inventedSlots} 决定自造名身份，{@code fileDiskHashes} 决定"磁盘没变就跳过"。
	 * 任一留着污染代的值，回退后的第一次热更就会静默用错代配对，或在字段增删上判"本来就有"而跳过初始化。</p>
	 */
	private static void syncCachesAfterRevert(ClassLoader loader, ChangesetLog.RevertTarget target) {
		String dotted = target.className().replace('/', '.');
		bytecodeCache.put(dotted, target.bytes().clone());
		AlignmentMetaStore.Entry head = AlignmentMetaStore.INSTANCE.head(loader, target.className());
		Set<String> invented = head == null ? Set.of() : head.inventedNames;
		if (invented.isEmpty()) inventedSlots.remove(dotted);
		else inventedSlots.put(dotted, Set.copyOf(invented));
		byte[] raw = head == null ? null : head.rawPrevBytes();
		if (raw == null) {
			// 拿不到目标代的 Raw 源字节时宁可清空：读侧会退回 {@code bytecodeCache}，
			// 而留着污染代的源码基线会让下一轮拿错代的源码去配对。
			sourceBytecodeCache.remove(dotted);
		} else {
			sourceBytecodeCache.put(dotted, raw.clone());
		}
		// 磁盘上仍是被退掉的那份内容。标记"无可信基线"（-1 是 changedOnDisk 判据里的无值约定），
		// 让下一轮扫描把它当改动重新热更上去，而不是误判"未修改"把 JVM 永远留在目标代。
		synchronized (fileDiskHashes) {
			fileDiskHashes.put(CRC64.hashString(dotted), -1L);
		}
	}

	private static String batchWatchLabelFromNames(Collection<String> names) {
		return "revert:" + String.join(",", new TreeSet<>(names));
	}

	/** 按点分名找已加载类（回退入口用；找不到即拒绝，不做隐式加载）。 */
	private static Class<?> findLoadedClassByName(String dottedName) {
		if (inst == null || dottedName == null) return null;
		String internal = dottedName.replace('.', '/');
		for (Class<?> cls : inst.getAllLoadedClasses()) {
			if (cls.getName().replace('.', '/').equals(internal)) return cls;
		}
		return null;
	}

}
