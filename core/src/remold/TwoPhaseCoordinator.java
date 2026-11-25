package remold;

import remold.ref.HeapFieldWatch;
import remold.ref.InitFix;
import remold.ref.StageASkeleton;

import java.lang.instrument.ClassDefinition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import remold.RemoldAgent.AlignmentTransaction;
import remold.RemoldAgent.RedefineOutcome;
import remold.RemoldAgent.RoundSkips;

import static remold.RemoldAgent.BATCH_REDEFINE_MILLIS;
import static remold.RemoldAgent.BATCH_STAGE_A_MILLIS;
import static remold.RemoldAgent.BATCH_STAGE_B_MILLIS;
import static remold.RemoldAgent.BATCH_STAGE_C_MILLIS;
import static remold.RemoldAgent.BATCH_TWOPHASE_SKEL_MILLIS;
import static remold.RemoldAgent.DEBUG;
import static remold.RemoldAgent.LAST_REDEFINE_VM_LEVEL_FAILURE;
import static remold.RemoldAgent.PERF_LOG;
import static remold.RemoldAgent.VM_POISONED_CLASSES;
import static remold.RemoldAgent.alignAccessMonotonicity;
import static remold.RemoldAgent.commitInvented;
import static remold.RemoldAgent.enterInternalRedefine;
import static remold.RemoldAgent.error;
import static remold.RemoldAgent.exitInternalRedefine;
import static remold.RemoldAgent.info;
import static remold.RemoldAgent.inst;
import static remold.RemoldAgent.isVmLevelRedefineFailure;
import static remold.RemoldAgent.loadedClassesMap;
import static remold.RemoldAgent.loaderScope;
import static remold.RemoldAgent.log;
import static remold.RemoldAgent.markVmLevelFailure;
import static remold.RemoldAgent.recordCacheLoader;
import static remold.RemoldAgent.recordRuntimeBytecode;
import static remold.RemoldAgent.warn;

/**
 * 两阶段 Schema-First 重定义与批量降级重定义协调器。
 */
final class TwoPhaseCoordinator {
	private TwoPhaseCoordinator() {}

	static RedefineOutcome applyTwoPhase(List<ClassDefinition> definitions,
	                                             Map<String, byte[]> oldEffectiveBytes,
	                                             Collection<AlignmentTransaction> transactions,
	                                             RoundSkips skips,
	                                             Set<String> injectedClasses) {
		return applyTwoPhase(definitions, oldEffectiveBytes, null, transactions, skips, injectedClasses);
	}

	/**
	 * @param changedNames 本轮真实改动的类名（磁盘字节 ≠ 运行期基线）；{@code null} = 调用方未提供，
	 *                     此时成功行退化为"入参即变更"的旧口径。仅用于计数措辞，不参与判决。
	 */
	static RedefineOutcome applyTwoPhase(List<ClassDefinition> definitions,
	                                             Map<String, byte[]> oldEffectiveBytes,
	                                             Collection<String> changedNames,
	                                             Collection<AlignmentTransaction> transactions,
	                                             RoundSkips skips,
	                                             Set<String> injectedClasses) {
		long startedNanos = System.nanoTime();
		// 被字节码门禁拒掉的类，其对齐事务一步都不许提交（见 RedefineEntryGate#withoutRejectedGroups）。
		// 必须在这里、stage A 的 preRegister 之前裁：preRegister 本身就会往 pendingAlignedClasses 登记。
		transactions = RedefineEntryGate.withoutRejectedGroups(transactions, skips);
		if (definitions.isEmpty()) {
			// 没有类进 redefine 时**不能** commit：commit 会把本轮从未生效的对齐字节写进
			// pendingAlignedClasses / bytecodeCache，等于把未生效字节变成基线（风险 15/16）。
			// 语义是"回滚"：登记过的条目撤掉，旧字节钉在 pending 里保护未来的首次加载。
			for (AlignmentTransaction tx : transactions) tx.rollback(true);
			// 与单阶段路径共用同一计数口径：本轮确有改动或被跳过才发声；纯重扫（无改动、无跳过）保持静默。
			emitNoEffect(RedefineSummary.of(changedNames, List.of(), skips.size()));
			skips.report();
			return new RedefineOutcome(Set.of(), Set.of());
		}

		// ---- 阶段 A：新结构 + 旧方法体（构造器保持新体） ----
		long skelStartNanos = PERF_LOG ? System.nanoTime() : 0L;
		List<ClassDefinition> stageADefinitions = new ArrayList<>(definitions.size());
		Map<String, byte[]>   stageABytes       = new HashMap<>(definitions.size());
		for (ClassDefinition def : definitions) {
			Class<?> clazz  = def.getDefinitionClass();
			String   name   = clazz.getName();
			byte[]   target = def.getDefinitionClassFile();
			byte[]   old    = oldEffectiveBytes.get(name);
			byte[]   skeleton;
			if (old == null) {
				// 没有可信旧字节就合成不出「旧方法体」；该类退化为直接提交目标字节（带告警）。
				warn("[HOTSWAP-2P] No old bytecode for " + name
				     + "; stage A falls back to the target bytes for this class.");
				skeleton = target;
			} else if (!InitFix.requiresTwoPhase(clazz)) {
				// 该类本身无需两阶段保护（如纯方法体修改或无零值暴露字段）：阶段 A 直接使用目标字节
				if (DEBUG) log("[HOTSWAP-2P] Stage A uses target bytes directly for " + name + " (no read-through hazard)");
				skeleton = target;
			} else {
				try {
					skeleton = StageASkeleton.buildWithLiveConstructors(old, target);
				} catch (Throwable t) {
					error("[HOTSWAP-2P] Cannot synthesise stage A for " + name
					      + "; falling back to the target bytes for this class.", t);
					skeleton = target;
				}
			}
			stageADefinitions.add(new ClassDefinition(clazz, skeleton));
			stageABytes.put(name, skeleton);
		}
		if (PERF_LOG) {
			BATCH_TWOPHASE_SKEL_MILLIS.addAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - skelStartNanos));
		}

		if (HeapFieldWatch.enabled()) {
			HeapFieldWatch.snapshot("before-redefine", batchWatchLabel(stageADefinitions));
		}
		long redefBeforeA = PERF_LOG ? BATCH_REDEFINE_MILLIS.get() : 0L;
		Set<String> stageAAbandoned = new HashSet<>();
		Set<String> stageAOk        = redefineStageA(stageADefinitions, stageABytes, transactions, skips, injectedClasses,
		                                              stageAAbandoned);
		if (PERF_LOG) {
			BATCH_STAGE_A_MILLIS.addAndGet(BATCH_REDEFINE_MILLIS.get() - redefBeforeA);
		}
		if (stageAOk.isEmpty()) {
			// stage A 一个类都没生效：preRegister 已经登记过本轮的对齐字节，必须整轮回滚。
			// 不撤的话这些"从未加载过的字节"会留在 pending 里，成为下一次首次加载的基线。
			for (AlignmentTransaction tx : transactions) tx.rollback(true);
			warn("[HOTSWAP-2P] Stage A did not apply any class; the two-phase transaction is abandoned.");
			skips.report();
			return new RedefineOutcome(Set.of(), Set.of());
		}
		if (HeapFieldWatch.enabled()) {
			HeapFieldWatch.snapshot("after-stage-a", batchWatchLabel(stageADefinitions));
		}
		info("[HOTSWAP-2P] Stage A applied for " + stageAOk.size()
		     + " class(es): new shape with old method bodies; patching live instances before stage C.");

		// isInitialized 二次检查（docs/initfix/04-target-design.md §1）：阶段 A 已生效后，若类的 <clinit>
		// 仍在别的线程里执行，补丁会被 InitFix 的未初始化守卫跳过；此时不能再提交阶段 C 的新方法体，
		// 否则新体读到的是默认值。把这类类留在阶段 A（旧体）并提示重启。
		Set<String> initialising = new HashSet<>();
		for (ClassDefinition def : stageADefinitions) {
			Class<?> clazz = def.getDefinitionClass();
			if (!stageAOk.contains(clazz.getName())) continue;
			if (!InitFix.isInitialized(clazz)) {
				error("[HOTSWAP-2P] " + clazz.getName() + " is being initialized by another thread"
				      + " (<clinit> in flight); stage C is withheld for it, so it stays on the old bodies."
				      + " RESTART is required for this class.");
				initialising.add(clazz.getName());
			}
		}
		if (!initialising.isEmpty()) {
			for (String name : initialising) {
				skips.add(name, RoundSkips.verdict("INITIALISING-AT-STAGE-A",
					"<clinit> in flight; stage C withheld", "restart required"));
			}
			// 这些类已经提交了阶段 A，重试基线不再等同于磁盘旧版本：不做自动回队，提示重启。
			stageAAbandoned.addAll(initialising);
			stageAOk.removeAll(initialising);
		}

		// ---- 阶段 B：真实 InitFix 驱动补丁（必须在阶段 C 之前全部完成） ----
		long patchStart = System.nanoTime();
		List<Class<?>> patchTargets = new ArrayList<>(stageAOk.size());
		for (ClassDefinition def : stageADefinitions) {
			if (stageAOk.contains(def.getDefinitionClass().getName())) patchTargets.add(def.getDefinitionClass());
		}
		// 批次级单次堆遍历：本批 K 个类的存活实例收集从 K 次全堆扫描收敛为 1 次
		// （docs/initfix/05-jvmti-heap.md §「批次实例收集」）。收集点在 redefine 生效之后、
		// 补丁执行之前——提前收集会漏掉窗口期新建的实例（docs/status.md 风险 6）。
		InitFix.beginBatchInstanceCollection(patchTargets);
		try {
			for (Class<?> clazz : InitFix.orderBatchHosts(patchTargets)) {
				try {
					InitFix.afterRedefine(clazz);
				} catch (Throwable t) {
					// afterRedefine 自己已兜住 LinkageError 等致命异常并记账；这里只防御它自身抛错。
					error("[HOTSWAP-2P] Stage B patch failed for " + clazz.getName()
					      + "; stage C will still be applied, so unpatched fields stay at defaults.", t);
				}
			}
		} finally {
			InitFix.endBatchInstanceCollection();
			// The batch bytecode view intentionally survives until the caller's Stage B loop ends: the outer
			// processChangesBatch still runs InitFix.orderBatchHosts over every redefined class, and clearing
			// the view here would silently drop cross-class ordering for any class that still holds a pending
			// patch (mixed two-phase / single-phase batches).
		}
		long patchMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - patchStart);
		BATCH_STAGE_B_MILLIS.addAndGet(patchMs);
		if (HeapFieldWatch.enabled()) {
			HeapFieldWatch.snapshot("after-field-patch", batchWatchLabel(stageADefinitions));
		}

		// ---- 阶段 C：只对阶段 A 成功的类提交完整新字节码 ----
		List<ClassDefinition> stageCDefinitions = new ArrayList<>(stageAOk.size());
		for (ClassDefinition def : definitions) {
			if (stageAOk.contains(def.getDefinitionClass().getName())) stageCDefinitions.add(def);
		}
		List<AlignmentTransaction> activeTransactions = new ArrayList<>(transactions.size());
		for (AlignmentTransaction tx : transactions) {
			if (stageAOk.contains(tx.hostName)) activeTransactions.add(tx);
		}
		long redefBeforeC = PERF_LOG ? BATCH_REDEFINE_MILLIS.get() : 0L;
		RedefineOutcome stageCOutcome = applyRedefinitions(stageCDefinitions, changedNames, activeTransactions, skips);
		if (PERF_LOG) {
			BATCH_STAGE_C_MILLIS.addAndGet(BATCH_REDEFINE_MILLIS.get() - redefBeforeC);
		}
		if (HeapFieldWatch.enabled() && !stageCOutcome.successful().isEmpty()) {
			HeapFieldWatch.snapshot("after-stage-c", batchWatchLabel(stageCDefinitions));
		}
		Set<String>     rolledBack    = new HashSet<>(stageCOutcome.rolledBack());
		rolledBack.addAll(stageAAbandoned);
		RedefineOutcome outcome = new RedefineOutcome(stageCOutcome.successful(), rolledBack);
		info("[HOTSWAP-2P] Two-phase transaction finished: A=" + stageAOk.size()
		     + " class(es), patch=" + patchMs + "ms, C=" + outcome.successful().size()
		     + " class(es), total=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos) + "ms"
		     + (outcome.successful().size() < stageAOk.size()
		        ? "; " + (stageAOk.size() - outcome.successful().size())
		          + " class(es) stayed at stage A (old behavior) — RESTART is required for those."
		        : ".")
		     );
		return outcome;
	}

	static String batchWatchLabel(List<ClassDefinition> defs) {
		if (defs == null || defs.isEmpty()) return "batch(0)";
		return defs.size() == 1
			? defs.get(0).getDefinitionClass().getName()
			: "batch(" + defs.size() + ")";
	}

	/**
	 * 只提交阶段 A 字节码：成功集合、缓存与事务登记都不算「本轮生效」，要等阶段 C 才落账。
	 *
	 * <p>失败的类立即丢弃其暂存补丁并记入台账（与单阶段 {@link #applyRedefinitions} 的失败语义一致）；
	 * 宿主与匿名类必须整组成功，否则回滚事务并整组移出阶段 C（重试基线已不一致，提示重启）。</p>
	 */
	/**
	 * Stage A 宿主/匿名一致性判据。
	 *
	 * <p><b>新注入组不参与 Stage A 重定义</b>：此前未加载、本轮通过 {@code injectNewClass} 或
	 * 待注入表定义的 {@code (host, anon)} 组，既不进 {@code definitions}，也不会有 {@code ok} 记录；
	 * 把它们纳入一致性校验会让整组被误判"半生效"并回滚（{@code ROLLED-BACK-A} + 要求重启），
	 * 而它们本来就已经按新字节码定义完毕。反过来，新注入的宿主配已加载的匿名类时，匿名类仍要校验。</p>
	 */
	static boolean stageAConsistent(String hostName, Set<String> targets, Set<String> ok,
	                                Set<String> injected, Set<String> loaded) {
		// 兼容旧调用口径：没有 requested 信息时，所有 loaded 类都视为"本轮要处理"。
		return stageAConsistent(hostName, targets, ok, injected, loaded, loaded);
	}

	/**
	 * 带"本轮实际提交 Stage A 的类名集合"的一致性判据。
	 *
	 * <p><b>为什么需要 requested</b>：宿主类可能本轮**没有变更**（例如只改最内层匿名类体），
	 * 因此不在 {@code definitions} 里、也不会进 {@code ok}；旧判据只看 host 是否在 ok 中，
	 * 会把这种"宿主保持不变、匿名类已生效"的形态误判成半生效并打出
	 * {@code ROLLED-BACK-A}。requested 只包含**本轮真正交给 Stage A 的类**：
	 * 未提交且已加载的 host 保持旧字节是预期状态，不需要出现在 ok 中；提交了却没成功
	 * 的类仍然必须回滚。被判据拒掉/过滤掉的类仍会出现在 requested 里，因此不会放松真正
	 * 的 host/anonymous 半生效检测。</p>
	 *
	 * @param requested 本轮进入 Stage A 的类名（过滤前）；{@code null} 等价于旧口径（所有 loaded 都算）
	 */
	static boolean stageAConsistent(String hostName, Set<String> targets, Set<String> ok,
	                                Set<String> injected, Set<String> loaded, Set<String> requested) {
		if (hostName == null) return false;
		boolean hostRequested = requested == null || requested.contains(hostName);
		boolean hostOk = ok.contains(hostName) || (injected != null && injected.contains(hostName));
		// 未请求且已加载的 host：本轮不改它，旧字节就是一致状态。
		boolean unchangedLoadedHost = !hostRequested && loaded != null && loaded.contains(hostName);
		if (!hostOk && !unchangedLoadedHost) return false;
		if (targets == null) return true;
		for (String target : targets) {
			if (injected != null && injected.contains(target)) continue;
			// 与宿主侧**对称**的豁免：本轮没有提交它的匿名类，保持旧字节就是一致状态。
			// 真机反例（Kotlin 宿主 7 个 ghost + 1 个 bridge 之后）：compileKotlin 重新输出宿主
			// .class（因内存字节与磁盘不同而进 Stage A），而匿名类 .class 磁盘字节未变、被过滤成
			// Unchanged ⇒ 它不在 requested 也不在 ok。旧判据仍要求它出现在 ok 里，于是每一轮都判
			// 宿主/匿名不一致并打出 ROLLED-BACK-A，把整个宿主锁死到重启。
			// 反之"提交了却没成功"的匿名类仍必须回滚（requested 含被过滤前的全部类）。
			if (requested != null && !requested.contains(target)) continue;
			if (loaded != null && loaded.contains(target) && !ok.contains(target)) return false;
		}
		return true;
	}

	static Set<String> redefineStageA(List<ClassDefinition> definitions,
	                                          Map<String, byte[]> stageABytes,
	                                          Collection<AlignmentTransaction> transactions,
	                                          RoundSkips skips,
	                                          Set<String> injectedClasses,
	                                          Set<String> abandonedOut) {
		for (AlignmentTransaction tx : transactions) tx.preRegister();

		// 过滤前先冻结"本轮提交 Stage A 的类"：宿主只改匿名类时不在 definitions/ok 中，
		// 但它是旧字节保持不变的预期状态，不能按半生效回滚。
		Set<String> requested = new HashSet<>(definitions.size());
		for (ClassDefinition def : definitions) requested.add(def.getDefinitionClass().getName());

		// 阶段 A 同样可能收到"重织入产物"，必须过同一道访问权限单调对齐 + 自检门。
		List<ClassDefinition> aligned = new ArrayList<>(definitions.size());
		for (ClassDefinition def : definitions) {
			String name = def.getDefinitionClass().getName();
			if (VM_POISONED_CLASSES.contains(name)) {
				warn("[VM-REDEFINE-POISON] Skipping stage A redefinition of " + name
				     + ": poisoned by an earlier JVM-level redefine failure; RESTART required.");
				skips.add(name, RoundSkips.verdict("VM-POISONED",
					"InternalError/VerifyError earlier this session", "restart required"));
				InitFix.afterRedefineFailed(def.getDefinitionClass());
				continue;
			}
			byte[] rawBytes = def.getDefinitionClassFile();
			byte[] alignedBytes = alignAccessMonotonicity(name, def.getDefinitionClass(), rawBytes);
			if (alignedBytes == null) {
				skips.add(name, RoundSkips.verdict("ACCESS-GATE-REFUSED",
					"member access narrowed after re-weave",
					"stage A refused; see [ACCESS-GATE]"));
				InitFix.afterRedefineFailed(def.getDefinitionClass());
				continue;
			}
			if (alignedBytes != rawBytes) {
				stageABytes.put(name, alignedBytes);
				def = new ClassDefinition(def.getDefinitionClass(), alignedBytes);
			}
			aligned.add(def);
		}
		definitions = aligned;

		// 与单阶段一致：被引用的匿名类先提交，宿主最后提交，降低半生效风险。
		definitions.sort((d1, d2) -> {
			boolean a1 = d1.getDefinitionClass().getName().contains("$");
			boolean a2 = d2.getDefinitionClass().getName().contains("$");
			if (a1 && !a2) return -1;
			if (!a1 && a2) return 1;
			return 0;
		});

		Set<String> targetNames = new HashSet<>();
		for (ClassDefinition def : definitions) targetNames.add(def.getDefinitionClass().getName());
		Set<String> ok = new HashSet<>();
		enterInternalRedefine(targetNames);
		try {
			try {
				long redefineStartNanos = System.nanoTime();
				try {
					inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));
				} finally {
					BATCH_REDEFINE_MILLIS.addAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - redefineStartNanos));
				}
				for (ClassDefinition def : definitions) ok.add(def.getDefinitionClass().getName());
			} catch (Throwable t) {
				error("[HOTSWAP-2P] Stage A bulk redefine failed, switching to individual mode...", t);
				for (ClassDefinition def : definitions) {
					String className = def.getDefinitionClass().getName();
					try {
						long redefineStartNanos = System.nanoTime();
						try {
							inst.redefineClasses(def);
						} finally {
							BATCH_REDEFINE_MILLIS.addAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - redefineStartNanos));
						}
						ok.add(className);
					} catch (Throwable e) {
						if (isVmLevelRedefineFailure(e)) {
							markVmLevelFailure(className, e);
						} else {
							error("[FAIL] stage A redefinition of " + className, e);
						}
						skips.add(className, RoundSkips.verdict("STAGE-A-FAILED",
						 e.getClass().getSimpleName(),
						 "see the stack trace above; the file is requeued"));
						InitFix.afterRedefineFailed(def.getDefinitionClass());
					}
				}
			}
		} finally {
			exitInternalRedefine();
		}

		// JVM 现在生效的是骨架字节码；缓存必须反映当前真实状态，供阶段 B 的层级/元数据查询使用。
		for (String name : ok) {
			byte[] bytes = stageABytes.get(name);
			if (bytes != null) {
				recordRuntimeBytecode(name, bytes);
				Class<?> c = loadedClassesMap.get(name);
				recordCacheLoader(name, c == null ? null : c.getClassLoader());
			}
		}

		// 宿主与匿名类必须同批生效；否则阶段 C 的基线已不一致，整组移出。
		Set<String> abandoned = new HashSet<>();
		for (AlignmentTransaction tx : transactions) {
			if (stageAConsistent(tx.hostName, tx.targetClasses, ok, injectedClasses,
			                     loadedClassesMap.keySet(), requested)) {
				continue;
			}
			error("[HOTSWAP-PARTIAL] Stage A: host " + tx.hostName
			      + " and its anonymous classes were not redefined consistently;"
			      + " rolling back pending injections and excluding the group from stage C."
			      + " RESTART is required for those classes.");
			tx.rollback(true);
			abandoned.add(tx.hostName);
			abandoned.addAll(tx.targetClasses);
			skips.add(tx.hostName, RoundSkips.verdict("ROLLED-BACK-A",
				"stage A host/anonymous mismatch", "restart required"));
		}
		if (!abandoned.isEmpty()) {
			for (String name : abandoned) {
				if (!ok.contains(name)) continue;
				Class<?> clazz = loadedClassesMap.get(name);
				if (clazz != null) InitFix.afterRedefineFailed(clazz);
			}
			abandonedOut.addAll(abandoned);
			ok.removeAll(abandoned);
		}
		return ok;
	}

	static RedefineOutcome applyRedefinitions(List<ClassDefinition> definitions,
	                                                  Collection<AlignmentTransaction> transactions,
	                                                  RoundSkips skips) {
		return applyRedefinitions(definitions, null, transactions, skips);
	}

	/**
	 * @param changedNames 本轮真实改动的类名（可为 {@code null} = 旧入口，退化为"入参即变更"）。
	 *                     只影响计数与摘要措辞：{@code changed} 为 0 时不得出现"成功"字样。
	 */
	static RedefineOutcome applyRedefinitions(List<ClassDefinition> definitions,
	                                                  Collection<String> changedNames,
	                                                  Collection<AlignmentTransaction> transactions,
	                                                  RoundSkips skips) {
		Set<String> successfulClasses = new HashSet<>();
		Set<String> rolledBackClasses = new HashSet<>();
		LAST_REDEFINE_VM_LEVEL_FAILURE = false;

		// 第四层：JVM 级失败后的不可恢复熔断——本会话内不再尝试重定义这些类。
		List<ClassDefinition> filtered = new ArrayList<>(definitions.size());
		for (ClassDefinition def : definitions) {
			String name = def.getDefinitionClass().getName();
			if (VM_POISONED_CLASSES.contains(name)) {
				warn("[VM-REDEFINE-POISON] Skipping " + name
				     + ": poisoned by an earlier JVM-level redefine failure (InternalError/VerifyError) in this session;"
				     + " RESTART the client before hot-swapping it again.");
				skips.add(name, RoundSkips.verdict("VM-POISONED",
					"InternalError/VerifyError earlier this session", "restart required"));
				InitFix.afterRedefineFailed(def.getDefinitionClass());
				continue;
			}
			filtered.add(def);
		}
		definitions = filtered;

		// 被字节码门禁拒掉的类，其对齐事务一步都不许提交（见 RedefineEntryGate#withoutRejectedGroups）。
		transactions = RedefineEntryGate.withoutRejectedGroups(transactions, skips);

		if (definitions.isEmpty()) {
			// "这一轮什么都没生效"的语义是**回滚**，不是"无事发生地提交"：commit() 会把本轮从未
			// 生效的对齐字节写进 pendingAlignedClasses / bytecodeCache，而这两张表都被下一轮当成
			// "JVM 里实际生效的基线"来读（docs/status.md §5 风险 15/16）。
			for (AlignmentTransaction tx : transactions) {
				tx.rollback(true);
			}
			// 一条都没进 redefine 时，原来这里**完全静默**：用户只看到若干条 warn，然后等不到任何
			// "成功/失败"汇总，只能猜是不是热更没触发。有跳过项就必须点名，这是"整轮静默失效"
			// 那类最难排查的问题的正面对冲。
			emitNoEffect(RedefineSummary.of(changedNames, List.of(), skips.size()));
			skips.report();
			return new RedefineOutcome(successfulClasses, rolledBackClasses);
		}

		// 关键竞态消除：在调用 redefineClasses 之前先乐观登记新的 pending 注入！
		// 避免宿主重定义成功到事务提交之间，并发线程或初始化方法首次加载未加载类时读到磁盘错位产物
		for (AlignmentTransaction tx : transactions) {
			tx.preRegister();
		}

		// 排序：被引用的匿名类优先重定义，宿主类最后重定义，降低单类重定义模式下的半生效风险
		definitions.sort((d1, d2) -> {
			boolean a1 = d1.getDefinitionClass().getName().contains("$");
			boolean a2 = d2.getDefinitionClass().getName().contains("$");
			if (a1 && !a2) return -1;
			if (!a1 && a2) return 1;
			return 0;
		});
		Set<String> targetNames = new HashSet<>();
		for (ClassDefinition def : definitions) {
			targetNames.add(def.getDefinitionClass().getName());
		}
		enterInternalRedefine(targetNames);
		try {
			long redefineStartNanos = System.nanoTime();
			try {
				inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));
			} finally {
				BATCH_REDEFINE_MILLIS.addAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - redefineStartNanos));
			}
			// 成功行必须按"真实改动"计数，并且**零生效时不得出现成功字样**：
			// 入参 definitions 里可能全是"仅因磁盘重写而被重新提交、字节未变"的类，
			// 此时用户改的类其实被门拒绝了，报 successful 会直接误导排障（假成功）。
			// 详细跳过名单由本轮末尾的 skips.report() 输出。
			emitSummary(RedefineSummary.of(changedNames, definitions, skips.size()));
			for (ClassDefinition def : definitions) {
				recordRuntimeBytecode(def.getDefinitionClass().getName(), def.getDefinitionClassFile());
				recordCacheLoader(def.getDefinitionClass().getName(), def.getDefinitionClass().getClassLoader());
				// 回执按"这个类自己的定义 loader"的 scope 取/落：与对齐期写入侧同一口径（风险 15）。
				commitInvented(loaderScope(def.getDefinitionClass().getClassLoader()),
				               def.getDefinitionClass().getName());
				successfulClasses.add(def.getDefinitionClass().getName());
			}
			for (AlignmentTransaction tx : transactions) {
				tx.commit();
			}
		} catch (Throwable t) {
			LAST_REDEFINE_VM_LEVEL_FAILURE = isVmLevelRedefineFailure(t);
			if (LAST_REDEFINE_VM_LEVEL_FAILURE) {
				// 批量失败无法归因到具体类；先记录标志，逐类重试时再精确拉黑。
				error("[VM-REDEFINE-POISON] Bulk redefine failed at JVM level ("
				      + t.getClass().getSimpleName() + "); falling back to per-class mode to attribute it.", t);
			} else {
				error("Bulk Redefine failed, switching to individual mode...", t);
			}
			// redefineClasses 对一批类是原子的：批量失败意味着没有任何类生效。
			// 因此 PENDING 补丁必须保留，留给下面的逐类重试；只有逐类重试也失败时才丢弃。
			for (ClassDefinition def : definitions) {
				String className = def.getDefinitionClass().getName();
				boolean redefined = false;
				try {
					long redefineStartNanos = System.nanoTime();
					try {
						inst.redefineClasses(def);
					} finally {
						BATCH_REDEFINE_MILLIS.addAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - redefineStartNanos));
					}
					redefined = true;
				} catch (Throwable e) {
					if (isVmLevelRedefineFailure(e)) {
						markVmLevelFailure(className, e);
					} else {
						error("[FAIL] " + className, e);
					}
					skips.add(className, RoundSkips.verdict("REDEFINE-FAILED",
						e.getClass().getSimpleName(), "see the stack trace above; the file is requeued"));
					InitFix.afterRedefineFailed(def.getDefinitionClass());
				}
				if (!redefined) continue;
				// 记账不属于重定义事务：JVM 已经应用了这个类，此处再抛错不能回退成
				// "redefine 失败" —— 那会 afterRedefineFailed（弹补丁 + 假台账）并让调用方
				// 跳过 afterRedefine，结果是类已生效但存量实例保持类型默认值。
				try {
					recordRuntimeBytecode(className, def.getDefinitionClassFile());
					recordCacheLoader(className, def.getDefinitionClass().getClassLoader());
					commitInvented(loaderScope(def.getDefinitionClass().getClassLoader()), className);
				} catch (Throwable be) {
					error("[BOOKKEEPING] " + className + " was redefined, but recording the new baseline failed."
						+ " The class is live; its pending InitFix patch is kept and will be applied.", be);
				}
				successfulClasses.add(className);
				if (DEBUG) log("[OK] " + className);
			}
			// 校验事务组的一致性
			for (AlignmentTransaction tx : transactions) {
				boolean hostOk     = successfulClasses.contains(tx.hostName);
				boolean allAnonsOk = true;
				for (String target : tx.targetClasses) {
					if (loadedClassesMap.containsKey(target) && !successfulClasses.contains(target)) {
						allAnonsOk = false;
					}
				}
				if (hostOk && allAnonsOk) {
					tx.commit();
				} else {
					if (hostOk != allAnonsOk) {
						error("[HOTSWAP-PARTIAL] Host " + tx.hostName + " and its anonymous classes redefined inconsistently! Note: classes already applied in JVM cannot be un-redefined; rolling back pending injections and cache, and pinning old bytecode in pending to protect future class loading.");
						error("[HOTSWAP-PARTIAL] Host " + tx.hostName
						      + ": NOT requeued for retry — a retry would re-align against a baseline"
						      + " that no longer matches the JVM's actual state. RESTART is required.");
					}
					tx.rollback(true);
					// 记录整组（宿主 + 其匿名类），供调用方排除出"失败回队"。
					// 原因：宿主可能已生效、而 cache/pending 被钉回旧版本，重试基线不一致。
					rolledBackClasses.add(tx.hostName);
					rolledBackClasses.addAll(tx.targetClasses);
					// 摘要台账：这些类的运行态可能已经半生效（宿主成功、匿名类失败），
					// 所以判决码不能写成"没生效"，必须说清"状态不一致，需重启"。
					skips.add(tx.hostName, RoundSkips.verdict("ROLLED-BACK",
						"host may already be live, its anonymous classes were not",
						"restart required"));
					skips.addGroup(tx.targetClasses, tx.hostName, RoundSkips.verdict("ROLLED-BACK",
						"group inconsistent with its host", "restart required"));
				}
			}
		} finally {
			exitInternalRedefine();
		}
		skips.report();
		return new RedefineOutcome(successfulClasses, rolledBackClasses);
	}

	/** 摘要行最外层出口：成功行走 info，零生效行走 warn，无事可报时静默。 */
	private static void emitSummary(RedefineSummary summary) {
		String line = summary.line();
		if (line == null) return;
		if (summary.warnLevel()) warn(line);
		else info(line);
	}

	/** 空定义路径专用：只有"确有改动"或"实有跳过"时才需要一条零生效汇总。 */
	private static void emitNoEffect(RedefineSummary summary) {
		if (summary.line() == null) return;
		warn(summary.line());
	}

	/**
	 * {@link #applyRedefinitions} 的结果。
	 * @param successful 确实重定义成功的类名
	 * @param rolledBack 因事务组不一致被 rollback 的类名（宿主 + 其匿名类）——
	 *                   这些类<b>不得回队重试</b>，理由见该方法 javadoc
	 */
}
