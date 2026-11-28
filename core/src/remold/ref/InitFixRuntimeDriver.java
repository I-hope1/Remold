package remold.ref;

import org.objectweb.asm.*;
import remold.*;
import remold.annotation.RemoldReinit;
import remold.util.*;
import remold.vcs.GenerationalFieldLedger;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static remold.RemoldAgent.log;
import static remold.ref.InitFix.*;
import static remold.ref.InitFixPatchEmitter.descriptorValueClass;

/**
 * InitFix 运行期逐实例补丁驱动与生命周期管理。
 */
final class InitFixRuntimeDriver {
	private InitFixRuntimeDriver() {}

	// ==================== 应用与生命周期 ====================

	/**
	 * 供外部重定义（IDE JDWP / 外部 Agent）后置异步任务调用的修补方法。
	 * 先复核 JVM 是否真正成功重定义了该类；若被 JVM 拒绝则立即注销并记入台账（保证不变量 5）。
	 */
	static void afterRedefineExternal(Class<?> clazz) {
		if (!RemoldAgent.INIT_FIX || clazz == null) return;

		PendingPatch patch = PENDING.get(clazz);
		if (patch == null) return;

		// 验证字段是否已在 JVM 中就绪（若未就绪说明 redefine 被 JVM 拒绝）
		// L1 重放字段是继承字段，getDeclaredField 必然找不到，只取非重放任务做样本。
		FieldPatchTask sampleTask = null;
		for (FieldPatchTask task : patch.plan().allTasks()) {
			if (!task.replay()) {
				sampleTask = task;
				break;
			}
		}
		if (sampleTask != null) {
			try {
				clazz.getDeclaredField(sampleTask.fieldName());
			} catch (NoSuchFieldException e) {
				RemoldAgent.warn("Class " + clazz.getName() + " was not redefined by JVM; aborting external patch.");
				afterRedefineFailed(clazz);
				return;
			}
		}
		afterRedefine(clazz);
	}

	static void afterRedefine(Class<?> clazz) {
		if (!RemoldAgent.INIT_FIX || clazz == null) return;

		PendingPatch patch = PENDING.remove(clazz);
		if (patch == null) return;

		try {
			applyPatch(clazz, patch);
		} catch (Throwable e) {
			// applyPatch 半途夭折（例如 LinkageError 熔断）：我们不知道哪些字段已经写成功，
			// 只能保守地把整个计划记入台账 —— 下一轮条件 CAS 会跳过已写入的值，
			// OVERWRITE 字段重写同一个值也是幂等的。
			for (FieldPatchTask task : patch.plan().allTasks()) {
				if (task.replay()) continue; // L1 replay does not use the ledger
				ledgerPut(clazz, task.fieldName(), "patch aborted: " + e);
				publishBatchFailure(clazz, task.fieldName());
			}
			// 中止的轮次不是"失败"：值类型重放的 applied 记录整类作废，下一轮重新判定，
			// 不能让半途写成功的记录把中止轮说成已应用（docs/initfix/14 §3）。
			valueReplayAppliedClear(clazz);
			RemoldAgent.error("Field init patch failed: " + e.getMessage(), e);
		}
	}

	/**
	 * 类根本没有进 {@code redefineClasses}（被批次门整类拒绝）：只丢暂存补丁，<b>不</b>写
	 * "redefine failed; patch never applied" 债务。
	 *
	 * <p>与 {@link #afterRedefineFailed} 的区别就是"有没有尝试过"：真失败说明类结构已经推进、
	 * 计划随时作废，必须记账；而整类被拒时 JVM 里什么都没变，下一轮会重新判定并重新生成计划，
	 * 记账只会让台账里多出一条误导性的"补丁失败"。计划期已记的"未补字段"记录保持不动。</p>
	 */
	static void discardPending(Class<?> clazz) {
		if (clazz == null) return;
		PendingPatch dropped = PENDING.remove(clazz);
		if (dropped == null) return;
		// 类没重定义 ⇒ 本轮什么都没写进去：作废"已应用"指纹，下一轮必须重新判定。
		reinitAppliedClear(clazz);
		valueReplayAppliedClear(clazz);
		log("Dropped pending field init patch for " + clazz.getName()
		    + " (the class was kept out of the batch by a gate; nothing was redefined)");
	}

	static void afterRedefineFailed(Class<?> clazz) {
		if (clazz == null) return;
		PendingPatch dropped = PENDING.remove(clazz);
		if (dropped != null) {
			// 补丁一个字段都没写进去，"已应用"记录跟着作废：下一轮必须重新判定，不能凭旧指纹跳过。
			reinitAppliedClear(clazz);
			valueReplayAppliedClear(clazz);
			// docs/initfix/02-closure-and-ledger.md §2 的核心场景：redefine 失败，但补丁计划已经生成、类结构随时可能推进；
			// 这些字段一个都没补，必须记账，否则它们再也不会出现在 Diff 里。
			for (FieldPatchTask task : dropped.plan().allTasks()) {
				// L1 重放字段不写台账（docs/initfix/12-L1-existing-initializer-replay.md §3）。
				if (task.replay()) continue;
				ledgerPut(clazz, task.fieldName(), "redefine failed; patch never applied");
				publishBatchFailure(clazz, task.fieldName());
			}
			log("Dropped pending field init patch for " + clazz.getName()
			    + " after redefine failure; " + dropped.plan().allTasks().size()
			    + " field(s) recorded in FieldLedger");
		}
	}

	/**
	 * 宿主侧补丁驱动器（{@code docs/initfix/03-runtime-driver.md} §2）。
	 *
	 * <p>逐字段调度：每个字段一个独立的静态直线方法，失败只影响该字段与其下游，
	 * 不会像"单方法承载全部字段"那样由一行异常跳过其后所有字段。依赖字段失败的
	 * 字段会被显式跳过并记账（{@code FieldLedger} 记入失败原因）。</p>
	 *
	 * <p>{@code LinkageError} 仍然上抛熔断（AGENTS.md 不变量 4）：它意味着类元数据假设已被
	 * JVM 打破，继续修补没有意义。</p>
	 */
	/**
	 * A 档 apply 重放的安全前置检查（{@code docs/initfix/13-kotlin-apply-replay.md} §5）：
	 * 把 {@code GETSTATIC holder.dest} 会取回的对象先读一遍，确认非 null 且运行时类型确实是
	 * 目标类型 T。缺失的检查会让 Unsafe 把 T 的字段偏移套到不匹配的对象上（内存安全问题），
	 * 因此这里是硬门，读不到/读失败一律视为不可重放。
	 *
	 * <p>只在补丁执行前做一次（不是逐实例），不参与分析期判定；返回 {@code null} 表示可写。</p>
	 */
	private static String applyDestinationSkipReason(
	 Class<?> host, ApplyInitializerReplay.ReplayField replay) {

		Object value;
		try {
			Field dest = host.getDeclaredField(replay.dest());
			dest.setAccessible(true);
			value = dest.get(null);
		} catch (Throwable t) {
			return "cannot read the holder's static field " + replay.dest() + ": " + t;
		}
		if (value == null) {
			return "the holder's static field " + replay.dest() + " is null";
		}
		try {
			Class<?> target = Class.forName(replay.targetClass().replace('/', '.'), false,
			 host.getClassLoader());
			if (!target.isInstance(value)) {
				return "the holder's static field " + replay.dest() + " holds "
				       + value.getClass().getName() + ", not " + target.getName();
			}
		} catch (Throwable t) {
			return "cannot resolve the apply object type " + replay.targetClass() + ": " + t;
		}
		return null;
	}

	static void applyPatch(Class<?> host, PendingPatch patch) throws Throwable {
		final boolean perfLog = RemoldAgent.PERF_LOG;
		final long tPatchStart = perfLog ? System.nanoTime() : 0L;

		// 影子 Class 归一（docs/initfix/03-runtime-driver.md §4 的 SELF 作用域判定前提）：
		// JBR 结构性 redefine 会把静态字段里持有的 Class 引用改写成旧版本影子对象
		// （同名同加载器、旧字段元数据）。影子类上的 LibTool.getInstances 返回 0，
		// 且存活实例的 getClass() 是 canonical 类而不是影子类 —— 不归一的话
		// "运行时类恰好等于声明类"会把所有实例判成不匹配，SELF 静默什么都不写。
		host = ClassIdentity.canonicalize(host);

		// 详细失败日志配额是"本轮"的（见 DETAILED_FAILURE_LOGS 的 Javadoc）：
		// 不重置的话它是进程级累计，前几轮热更失败满 5 次之后，
		// 之后所有热更都不再打详细栈。
		DETAILED_FAILURE_LOGS.set(0);

		// 条件 CAS 统计是"本轮"的：先清掉可能残留的计数，避免上一轮的数据混进本轮告警。
		// 正常路径下每轮结束都会 drain；这里兜住异常路径留下的残留。
		HotswapBridge.drainConditionalStats();
		HotswapBridge.drainReplayStats();

		if (!isInitialized(host)) {
			// 不变量 1：未初始化类直接跳过，<b>不</b>记账、也<b>不</b>发布跨类失败令牌。它的 <clinit> 还没跑过，
			// 首次访问时 JVM 会执行新 <clinit> 得到正确初值；在此处记账并牵连下游，会把读它的同批字段
			// 误判成"依赖失败"而永久停在默认值。
			log("Skip field init patch, class not initialized (or initializing/failed): "
			    + host.getName());
			return;
		}

		long tCollectNs = 0L;
		long tCompactNs = 0L;
		long tSelfScanNs = 0L;
		long tDefineNs = 0L;
		long tStaticNs = 0L;
		long tApplyReplayNs = 0L;
		long tInstNs = 0L;

		PatchPlan    plan  = patch.plan();
		Set<String>  replayTaskFields = new LinkedHashSet<>();
		for (FieldPatchTask task : plan.allTasks()) {
			if (task.replay()) replayTaskFields.add(task.fieldName());
		}
		// 值类型 new 初值重放（docs/initfix/14 §3）：只跑 W0-W4 写入，不写 FieldLedger、
		// 不改变默认补丁语义。实例目标需要和常规补丁同一份存活实例枚举。
		boolean hasValueReplay = !plan.valueReplayTasks().isEmpty();
		boolean valueReplayHasInstance = hasInstanceValueReplay(plan);
		Object[] alive = null;
		if (plan.hasInstance() || valueReplayHasInstance) {
			long tBeforeCollect = perfLog ? System.nanoTime() : 0L;
			Object[] raw = collectRawInstancesForPatch(host);
			long tAfterCollect = perfLog ? System.nanoTime() : 0L;
			alive = compactNonNull(raw);
			// 本批次的枚举结果留给 @OnReload 回调复用（见 StageBInstanceCache）：
			// 一次结构性热更里补丁和实例回调各枚举一次堆，百万级实例下这是双倍开销。
			StageBInstanceCache.put(host, alive);
			long tAfterCompact = perfLog ? System.nanoTime() : 0L;
			if (perfLog) {
				tCollectNs = tAfterCollect - tBeforeCollect;
				tCompactNs = tAfterCompact - tAfterCollect;
			}
			// 纯值类型实例重放的空枚举是"无存活实例"的 no-op（docs/initfix/14 A6），不是失败，
			// 因此不能在这里早退：要让值类型重放段把 skipped/no-live-instance 结论落进报告。
			if (alive.length == 0 && !plan.hasStatic() && !hasValueReplay) {
				if (perfLog) {
					RemoldAgent.info(formatApplyPatchPerf(host, tPatchStart, 0,
					 tCollectNs, tCompactNs, 0L, 0L, 0L, 0L, 0L, 0L));
				}
				RemoldAgent.info("[InitFix] No live instances and no static patch needed for " + host.getName()
				    + ", skip");
				return;
			}
		}

		// SELF 作用域的可写目标数：枚举结果含子类/匿名子类实例，只有运行时类恰好等于宿主的那部分
		// 才能承接 SELF 字段的写入（docs/initfix/03-runtime-driver.md §4）。这个计数同时喂给
		// reinitHint —— 否则"SELF 字段没有任何本类实例可写"会被误报成已生效。
		// 没有 SELF 字段时整段跳过：它按实例做身份比较，纯属白付。
		long tBeforeSelfScan = perfLog ? System.nanoTime() : 0L;
		boolean hasSelfOnlyTasks = false;
		for (FieldPatchTask task : plan.instanceTasks()) {
			hasSelfOnlyTasks |= task.selfOnly();
		}
		int selfOnlyTargets = 0;
		if (hasSelfOnlyTasks && alive != null) {
			// host 在本方法开头已经归一为 canonical 类，这里走不重复 Class.forName 的快路径。
			for (Object o : alive) {
				if (scopeMatchesCanonical(host, o, RemoldReinit.Scope.SELF)) selfOnlyTargets++;
			}
		}
		if (perfLog) tSelfScanNs = System.nanoTime() - tBeforeSelfScan;

		long tBeforeDefine = perfLog ? System.nanoTime() : 0L;
		Lookup   h  = Reflect.defineHiddenClass(host, patch.bytes());
		Class<?> pc = h.lookupClass();

		// 必须在取 Handle 时就 asType 适配：init$F 的形参是宿主类型，驱动手里只有 Object。
		BoundInstanceTask[] instanceTasks           = new BoundInstanceTask[plan.instanceTasks().size()];
		boolean[]           constantBound           = new boolean[instanceTasks.length];
		boolean             hasInstanceDependencies = false;
		MethodType          batchMethodType         = MethodType.methodType(
		 int.class, Object[].class, int.class, int.class, Throwable[].class);
		for (int i = 0; i < instanceTasks.length; i++) {
			FieldPatchTask task = plan.instanceTasks().get(i);
			hasInstanceDependencies |= !task.dependencies().isEmpty();
		}
		for (int i = 0; i < instanceTasks.length; i++) {
			FieldPatchTask task = plan.instanceTasks().get(i);
			MethodHandle handle = h.findStatic(pc, task.methodName(), MethodType.methodType(void.class, host))
			 .asType(MethodType.methodType(void.class, Object.class));
			MethodHandle batchHandle = null;
			if (!hasInstanceDependencies && task.constantInit() != null) {
				ConstantInitSpec spec = task.constantInit();
				try {
					long offset = HotswapBridge.resolveInstanceFieldOffset(host, task.fieldName(), spec.fieldDesc());
					MethodHandle loopTemplate = HotswapBridge.constantBatchLoop(
					 descriptorValueClass(spec.fieldDesc()), spec.forceWrite());
					batchHandle = MethodHandles.insertArguments(
					 loopTemplate, 0, host, task.fieldName(), offset, spec.value(), task.selfOnly());
					constantBound[i] = true;
				} catch (LinkageError le) {
					if (!isolatableLinkFailure(le)) throw le;
					// 留空，回退到隐藏类批方法走原有隔离失败与计数逻辑。
				} catch (Throwable t) {
					RemoldAgent.warn("Persistent constant batch loop unavailable for "
					                 + host.getName() + "." + task.fieldName() + ": " + t);
					batchHandle = null;
				}
			}
			if (batchHandle == null && task.batchMethodName() != null) {
				batchHandle = h.findStatic(pc, task.batchMethodName(), batchMethodType);
			}
			instanceTasks[i] = new BoundInstanceTask(task.fieldName(), task.dependencies(), handle,
			 batchHandle, task.selfOnly());
		}
		List<BoundFusedBatchGroup> fusedGroupList = new ArrayList<>(plan.fusedBatchGroups().size());
		MethodType fusedBatchMethodType = MethodType.methodType(
		 int.class, Object[].class, int.class, int.class, boolean[].class, Throwable[].class, int[].class);
		for (FusedBatchGroup group : plan.fusedBatchGroups()) {
			int[] indices = group.taskIndices();
			boolean allConstantBound = indices.length > 0;
			for (int idx : indices) {
				if (!constantBound[idx]) {
					allConstantBound = false;
					break;
				}
			}
			// 若整组字段均已命中常驻 C2 常量批写循环，直接各自跑已预热的常驻通道，免去每轮新隐藏类编译等待。
			if (allConstantBound) continue;
			MethodHandle handle = h.findStatic(pc, group.methodName(), fusedBatchMethodType);
			fusedGroupList.add(new BoundFusedBatchGroup(handle, indices));
		}
		BoundFusedBatchGroup[] fusedGroups = fusedGroupList.toArray(new BoundFusedBatchGroup[0]);

		Map<String, MethodHandle> staticHandles = new LinkedHashMap<>();
		for (FieldPatchTask task : plan.staticTasks()) {
			staticHandles.put(task.fieldName(),
			 h.findStatic(pc, task.methodName(), MethodType.methodType(void.class)));
		}

		// A 档 apply 重放：补丁方法名按 applyFields 插入顺序编号（装配与驱动同序，不重算名字）。
		List<Map.Entry<String, ApplyInitializerReplay.ReplayField>> applyEntries =
		 new ArrayList<>(plan.applyFields().entrySet());
		MethodHandle[] applyHandles = new MethodHandle[applyEntries.size()];
		for (int i = 0; i < applyEntries.size(); i++) {
			applyHandles[i] = h.findStatic(pc, APPLY_REPLAY_PREFIX + i,
			 MethodType.methodType(void.class));
		}

		Set<String>         failedFields   = new LinkedHashSet<>();

		Set<String>         skippedFields  = new LinkedHashSet<>();
		Map<String, String> failureReasons = new LinkedHashMap<>();
		if (perfLog) tDefineNs = System.nanoTime() - tBeforeDefine;

		// ---- 静态字段：整个补丁只跑一次 ----
		long tBeforeStatic = perfLog ? System.nanoTime() : 0L;
		if (!plan.staticTasks().isEmpty()) {
			RemoldAgent.info("Applying static field init patch to " + host.getName()
			                  + ", fields=" + plan.staticTasks().size());
			long start = System.nanoTime();
			runTasks(host, plan.staticTasks(), staticHandles, null,
			 failedFields, skippedFields, failureReasons);
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			RemoldAgent.info("Static field init patch for " + host.getName()
			                  + " done in " + elapsedMs + "ms");
		}
		if (perfLog) tStaticNs = System.nanoTime() - tBeforeStatic;

		// ---- A 档 apply 重放：静态持有者 <clinit> 里的 T().apply{...} ----
		// 每个候选只写"GETSTATIC holder.dest 取回的那一个对象"；运期改过的值由 expected-CAS 跳过。
		// 失败/跳过<b>不写 FieldLedger</b>（与 L1 同口径：显式开启的尽力重放，不是欠账），
		// 单独记在 applyFailed/applySkipped 里，供下面的 [INIT-APPLY-REPLAY] 汇总。
		long tBeforeApplyReplay = perfLog ? System.nanoTime() : 0L;
		Set<String>         applyFailed  = new LinkedHashSet<>();
		Set<String>         applySkipped = new LinkedHashSet<>();
		Map<String, String> applyReasons = new LinkedHashMap<>();
		if (!applyEntries.isEmpty()) {
			RemoldAgent.info("Applying static holder apply replay to " + host.getName()
			                 + ", targets=" + applyEntries.size());
			long start = System.nanoTime();
			for (int i = 0; i < applyEntries.size(); i++) {
				String key = applyEntries.get(i).getKey();
				ApplyInitializerReplay.ReplayField replay = applyEntries.get(i).getValue();
				String skip = applyDestinationSkipReason(host, replay);
				if (skip != null) {
					applySkipped.add(key);
					applyReasons.put(key, skip);
					RemoldAgent.warn("[INIT-APPLY-REPLAY] " + host.getName() + ": " + key
					                 + " NOT replayed: " + skip);
					continue;
				}
				try {
					applyHandles[i].invokeExact();
				} catch (LinkageError le) {
					if (!isolatableLinkFailure(le)) throw le;
					applyFailed.add(key);
					applyReasons.put(key, String.valueOf(le));
					RemoldAgent.warn("[INIT-APPLY-REPLAY] " + host.getName() + ": " + key
					                 + " replay failed: " + le);
				} catch (Throwable t) {
					applyFailed.add(key);
					applyReasons.put(key, String.valueOf(t));
					RemoldAgent.warn("[INIT-APPLY-REPLAY] " + host.getName() + ": " + key
					                 + " replay failed: " + t);
				}
			}
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			RemoldAgent.info("Static holder apply replay for " + host.getName()
			                 + " done in " + elapsedMs + "ms");
		}

		// 静态侧失败必须在实例阶段开始前冻结一份快照：它全局唯一，对每个实例都生效；
		// 而实例侧失败只影响出错的那个实例，不能混进这份快照（否则又变成跨实例牵连）。
		Set<String> staticFailures = new LinkedHashSet<>(failedFields);
		Set<String> staticSkips    = new LinkedHashSet<>(skippedFields);
		if (perfLog) tApplyReplayNs = System.nanoTime() - tBeforeApplyReplay;

		// ---- 实例字段：逐实例、逐字段 ----
		long tBeforeInst = perfLog ? System.nanoTime() : 0L;
		if (plan.hasInstance() && alive != null && alive.length > 0) {
			RemoldAgent.info("Applying instance field init patch to " + host.getName()
			                  + ", enumerated=" + alive.length
			                  + (hasSelfOnlyTasks ? ", selfScopeTargets=" + selfOnlyTargets : "")
			                  + ", fields=" + plan.instanceTasks().size());

			long start = System.nanoTime();
			if (hasInstanceDependencies) {
				runDependentInstanceTasks(host, alive, instanceTasks,
				 failedFields, skippedFields, failureReasons,
				 union(staticFailures, staticSkips, Set.of()));
			} else {
				runIndependentInstanceTasks(host, alive, instanceTasks, fusedGroups,
				 failedFields, skippedFields, failureReasons);
			}
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

			StringBuilder sb = new StringBuilder("Instance field init patch for ")
			 .append(host.getName())
			 .append(" done: fields=").append(plan.instanceTasks().size())
			 .append(", failedFields=").append(failedFields)
			 .append(", skippedFields=").append(skippedFields)
			 .append(", elapsed=").append(elapsedMs).append("ms");
			RemoldAgent.info(sb.toString());

			if (elapsedMs > 1000) {
				RemoldAgent.warn("Instance field init patch for " + host.getName()
				                  + " took " + elapsedMs
				                  + "ms, hotswap thread was blocked");
			}
		}
		long tAfterInst = perfLog ? System.nanoTime() : 0L;
		if (perfLog) tInstNs = tAfterInst - tBeforeInst;

		// ---- 值类型 new 初值重放：W0-W4（docs/initfix/14 §3） ----
		// 放在静态 / apply / 实例阶段之后：这些字段是既有字段的对象级替换，不参与上面任何"补丁"语义
		// （不写 FieldLedger、不进 FieldPatchTask 台账口径、不改变 patchGenerated）。
		ValueReplayResult valueReplayResult = ValueReplayResult.EMPTY;
		if (hasValueReplay) {
			long start = System.nanoTime();
			valueReplayResult = runValueReplay(host, plan, alive, h, pc);
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			if (valueReplayResult.hasOutcome()) {
				RemoldAgent.info("[VALUE-REPLAY] " + host.getName() + " done: applied="
				                 + valueReplayResult.appliedFields()
				                 + ", skipped=" + valueReplayResult.skippedFields()
				                 + ", failed=" + valueReplayResult.failedFields()
				                 + ", elapsed=" + elapsedMs + "ms");
			}
		}

		// 没真正写进去的注解字段：撤回"已应用"记录，否则下一轮会被"初值未变"误跳过，永久停在旧值。
		for (String f : plan.reinitFields().keySet()) {
			if (failedFields.contains(f) || skippedFields.contains(f)) reinitAppliedRemove(host, f);
		}

		// @RemoldReinit 生效点名（docs/initfix/03-runtime-driver.md §4）：
		// "存量值被重置"与"新增字段被初始化"是两件不同的事，补丁日志只报"打了几个字段"分不出来。
		String reinitLine = reinitHint(host.getName(), plan,
		 failedFields, skippedFields, alive == null ? 0 : alive.length, selfOnlyTargets);
		if (reinitLine != null) RemoldAgent.info(reinitLine);

		// L1 重放：本轮真正跑过的字段记入"已处理"，供 L0 跳过"初值变了但不会重放"的告警；
		// 失败/跳过的字段不记（让 L0 继续提示），也不写 FieldLedger（L1 是尽力重放，不是欠账）。
		for (String f : replayTaskFields) {
			if (failedFields.contains(f) || skippedFields.contains(f)) continue;
			InitializerReplay.recordApplied(host.getName(), f);
		}
		if (!replayTaskFields.isEmpty()) {
			Set<String> replayFailed = new LinkedHashSet<>(failedFields);
			replayFailed.retainAll(replayTaskFields);
			Set<String> replaySkipped = new LinkedHashSet<>(skippedFields);
			replaySkipped.retainAll(replayTaskFields);
			if (!replayFailed.isEmpty() || !replaySkipped.isEmpty()) {
				RemoldAgent.warn("[INIT-REPLAY] " + host.getName() + ": replay failed=" + replayFailed
				                 + ", skipped=" + replaySkipped + " (not recorded in FieldLedger)");
			}
		}

		if (BATCH_CONTEXT.get() != null) {
			for (FieldPatchTask task : plan.allTasks()) {
				String token = batchFailureToken(new InitFixBatchGraph.FieldKey(
				 batchHostKeyCurrent(host), task.fieldName()));
				if (failedFields.contains(task.fieldName()) || skippedFields.contains(task.fieldName())) {
					BATCH_FAILURES.get().add(token);
					continue;
				}
				if (!task.isStatic() && (alive == null || alive.length == 0)) continue;
				BATCH_FAILURES.get().remove(batchFailureToken(
				 new InitFixBatchGraph.FieldKey(batchHostKeyCurrent(host), task.fieldName())));
			}
		}
		if (!failedFields.isEmpty() || !skippedFields.isEmpty()) {
			// docs/initfix/02-closure-and-ledger.md §2 待补台账：本轮没补上的字段记入，下一轮候选集自动并回来。
			// 先写 failed，再写 skipped —— 熔断字段同时属于两者（它确实失败过，
			// 又被"放弃"），而 skipped 的原因更具体（"达到失败上限后放弃"），
			// 因此必须后写、覆盖掉笼统的 runtime failure。
			for (String f : failedFields) {
				if (replayTaskFields.contains(f)) continue; // L1 replay is not a ledger field
				ledgerPut(host, f, "runtime failure: "
				                   + failureReasons.getOrDefault(f, "patch execution failed"));
			}
			for (String f : skippedFields) {
				if (replayTaskFields.contains(f)) continue;
				ledgerPut(host, f, "skipped: "
				                   + failureReasons.getOrDefault(f, "a dependency field failed to patch"));
			}
			Set<String> failedForLedger = new LinkedHashSet<>(failedFields);
			failedForLedger.removeAll(replayTaskFields);
			Set<String> skippedForLedger = new LinkedHashSet<>(skippedFields);
			skippedForLedger.removeAll(replayTaskFields);
			if (!failedForLedger.isEmpty() || !skippedForLedger.isEmpty()) {
				RemoldAgent.warn("Field init patch incomplete for " + host.getName()
				                  + ": failed=" + failedForLedger + ", skipped=" + skippedForLedger
				                  + " (recorded in FieldLedger; will be reconsidered in next hotswap cycle)");
			}
		}

		// 条件 CAS 跳过汇总：字段在补丁执行前已被别的线程赋过非默认值 → CAS 失败 → 补丁未生效。
		// 这是"已解决"而非"失败"（条件写本来就该保守跳过），所以不进台账、不参与上面的失败告警；
		// 但它必须被<b>报告</b> —— 否则用户无法区分"补上了"与"因已有值而跳过"。
		//
		// 仅首轮有告警价值：台账重试轮里，已补过的实例会因"字段已非默认值"而跳过，
		// 那是预期行为，报成 warn 会误导。重试轮因此降为 info。
		Map<String, long[]> casStats = HotswapBridge.drainConditionalStats();
		if (!casStats.isEmpty()) {
			long         totalSkipped = 0;
			List<String> skippedNames = new ArrayList<>();
			for (Map.Entry<String, long[]> e : casStats.entrySet()) {
				long skipped = e.getValue()[1];
				if (skipped > 0) {
					totalSkipped += skipped;
					skippedNames.add(e.getKey() + " x" + skipped);
				}
			}
			if (totalSkipped > 0) {
				String msg = "Field init patch skipped " + totalSkipped + " conditional write(s) for "
				             + host.getName() + " (field already had a non-default value): "
				             + skippedNames;
				if (ledgerSnapshot(host).isEmpty()) {
					RemoldAgent.warn(msg + " (conditional write conservatively skipped by design; use @RemoldReinit(OVERWRITE) to force overwrite)");
				} else {
					// 重试轮：这些跳过多半来自上一轮已成功补过的实例，属预期
					RemoldAgent.info(msg + " (retry pass, typically instance already patched in prior round)");
				}
			}
		}

		// 重放统计按 owner 拆分：L1 的 owner 是锚点类自身，apply 的 owner 是 apply 对象类型 T。
		// 混在一条 [INIT-REPLAY] 里会把"另一个类的字段被写了"说成"宿主的实例字段被写了"。
		Map<String, long[]> replayStats = HotswapBridge.drainReplayStats();
		String               hostPrefix  = host.getName() + "#";
		Map<String, long[]>  l1Stats      = new LinkedHashMap<>();
		Map<String, long[]>  applyStats   = new LinkedHashMap<>();
		for (Map.Entry<String, long[]> e : replayStats.entrySet()) {
			(e.getKey().startsWith(hostPrefix) ? l1Stats : applyStats).put(e.getKey(), e.getValue());
		}
		// L1 重放统计：写入/跳过数分别来自校验通过字段的 expected-CAS 结果。
		// 跳过意味着该实例的当前值已不等于旧初值（运行期改过），按设计保守放弃写入。
		if (!l1Stats.isEmpty()) {
			long         totalWritten = 0;
			long         totalSkipped = 0;
			List<String> skippedNames = new ArrayList<>();
			for (Map.Entry<String, long[]> e : l1Stats.entrySet()) {
				totalWritten += e.getValue()[0];
				long skipped = e.getValue()[1];
				totalSkipped += skipped;
				if (skipped > 0) skippedNames.add(e.getKey() + " x" + skipped);
			}
			RemoldAgent.info("[INIT-REPLAY] " + host.getName() + ": wrote " + totalWritten
			                 + " instance field(s)"
			                 + (totalSkipped > 0
			                    ? ", skipped " + totalSkipped
			                      + " whose current value no longer equals the old initializer: " + skippedNames
			                    : ""));
		}
		// A 档 apply 重放统计：owner 是目标类型 T；dest 为 null/类型不符的候选已在执行前跳过，
		// 这里的 skipped 只表示"运行期值已不等于旧初值"（条件 CAS 按设计保守放弃）。
		if (!applyStats.isEmpty() || !applyFailed.isEmpty() || !applySkipped.isEmpty()) {
			long         written = 0;
			long         skipped = 0;
			List<String> skippedNames = new ArrayList<>();
			for (Map.Entry<String, long[]> e : applyStats.entrySet()) {
				written += e.getValue()[0];
				long s = e.getValue()[1];
				skipped += s;
				if (s > 0) skippedNames.add(e.getKey() + " x" + s);
			}
			RemoldAgent.info("[INIT-APPLY-REPLAY] " + host.getName() + ": wrote " + written
			                 + " apply-object field(s)"
			                 + (skipped > 0
			                    ? ", skipped " + skipped
			                      + " whose current value no longer equals the old initializer: " + skippedNames
			                    : ""));
			if (!applyFailed.isEmpty() || !applySkipped.isEmpty()) {
				StringBuilder why = new StringBuilder();
				for (Map.Entry<String, String> e : applyReasons.entrySet()) {
					if (why.length() > 0) why.append("; ");
					why.append(e.getKey()).append(" -> ").append(e.getValue());
				}
				RemoldAgent.warn("[INIT-APPLY-REPLAY] " + host.getName() + ": replay incomplete"
				                 + " (failed=" + applyFailed + ", skipped=" + applySkipped + ")"
				                 + (why.length() > 0 ? " (" + why + ")" : "")
				                 + "; not recorded in FieldLedger");
			}
		}

		// 代际重放的运行期对账（G6 / G7）：CAS 未命中的字段按"没写成"记账，不冒充已应用。
		recordReplayOutcome(host, plan, patch.replayOwners(), l1Stats, applyStats,
		 failedFields, skippedFields, alive == null ? 0 : alive.length);

		// 批次报告：只统计真正跑完的补丁。字段名与实例数都是本轮实测值，
		// 失败/被放弃的字段单独计数（不把"尝试过"说成"已修补"）。
		Set<String> patchedFieldNames = new LinkedHashSet<>();
		for (FieldPatchTask task : plan.allTasks()) {
			patchedFieldNames.add(task.fieldName());
		}
		if (!patchedFieldNames.isEmpty()) {
			Set<String> incomplete = new LinkedHashSet<>(failedFields);
			incomplete.addAll(skippedFields);
			BatchReportScope.recordInitFix(host.getName(),
				alive == null ? 0 : alive.length,
				new ArrayList<>(patchedFieldNames), incomplete.size());
		}
		// 值类型重放的运行期三态进批次报告（docs/initfix/14 §8）：分析期已登记 rejected，
		// 这里 valueReplayRejected 固定 0 以免重复计数。纯值类型轮由它提供 liveInstances/字段名。
		if (hasValueReplay && valueReplayResult.hasOutcome()) {
			BatchReportScope.recordInitFix(host.getName(),
				patchedFieldNames.isEmpty() ? (alive == null ? 0 : alive.length) : 0,
				valueReplayResult.fieldNames(), 0,
				valueReplayResult.appliedFields().size(),
				valueReplayResult.skippedFields().size(),
				valueReplayResult.failedFields().size(), 0,
				valueReplayResult.failedDetails(), null);
		}
		if (perfLog) {
			long tPostNs = System.nanoTime() - tAfterInst;
			RemoldAgent.info(formatApplyPatchPerf(host, tPatchStart, alive == null ? 0 : alive.length,
			 tCollectNs, tCompactNs, tSelfScanNs, tDefineNs, tStaticNs, tApplyReplayNs, tInstNs, tPostNs));
		}
	}

	// ==================== 值类型 new 初值重放执行（docs/initfix/14 §3 W0-W4） ====================

	/** W4 有界重试次数：CAS 竞争时单实例从 W1 重新开始的最大尝试次数。 */
	private static final int VALUE_REPLAY_MAX_ATTEMPTS = 3;

	/** 一轮真实执行过的值类型重放字段三态（供日志、PatchReport 回写与批次报告使用）。 */
	private record ValueReplayResult(List<String> appliedFields, List<String> skippedFields,
	                                 List<String> failedFields, List<String> failedDetails) {

		static final ValueReplayResult EMPTY =
		 new ValueReplayResult(List.of(), List.of(), List.of(), List.of());

		boolean hasOutcome() {
			return !appliedFields.isEmpty() || !skippedFields.isEmpty() || !failedFields.isEmpty();
		}

		/** 本轮本通道碰过的字段名（去重、保序）：作为批次报告的 fieldNames。 */
		List<String> fieldNames() {
			Set<String> names = new LinkedHashSet<>();
			names.addAll(appliedFields);
			names.addAll(skippedFields);
			names.addAll(failedFields);
			return new ArrayList<>(names);
		}
	}

	/** 本轮是否有需要存活实例枚举的值类型重放任务（实例目标；静态目标不需要枚举）。 */
	private static boolean hasInstanceValueReplay(PatchPlan plan) {
		for (ValueReplayTask task : plan.valueReplayTasks()) {
			if (!task.isStatic()) return true;
		}
		return false;
	}

	/**
	 * 执行本轮所有值类型 {@code new} 初值重放任务（{@code docs/initfix/14} §3）。
	 *
	 * <p>字段级三态：至少一个实例写入成功且无实例失败 → written/applied（写 applied 记录）；
	 * 全部实例 skipped（或实例枚举为空）→ skipped；任一实例失败 / W0-W3 异常 / 槽位不可解析
	 * → failed。failed 与 skipped 都<b>不</b>写 applied 记录，也<b>不</b>写 {@code FieldLedger}，
	 * 只进 {@code PatchReport}、批次报告与英文日志。异常按字段隔离；{@code LinkageError} 仍按
	 * 不变量 4 判定是否熔断。</p>
	 */
	private static ValueReplayResult runValueReplay(Class<?> host, PatchPlan plan, Object[] alive,
	                                                Lookup patchLookup, Class<?> patchClass) {
		List<String> appliedFields = new ArrayList<>();
		List<String> skippedFields = new ArrayList<>();
		List<String> failedFields = new ArrayList<>();
		List<String> failedDetails = new ArrayList<>();

		for (ValueReplayTask task : plan.valueReplayTasks()) {
			String fieldName = task.fieldName();
			try {
				HotswapBridge.ReferenceFieldSlot slot = HotswapBridge.resolveReferenceField(
				 host, fieldName, task.desc(), task.isStatic());
				MethodHandle buildOld = bindValueReplayFactory(
				 patchLookup, patchClass, task.buildOldMethod());
				MethodHandle buildNew = bindValueReplayFactory(
				 patchLookup, patchClass, task.buildNewMethod());
				// W0：旧值每字段只重建一次（静态槽位与全部实例共用）。
				Object rebuiltOld = buildOld.invokeExact();

				if (task.isStatic()) {
					HotswapBridge.ReferenceReplayOutcome outcome = HotswapBridge.replayReferenceField(
					 slot, null, rebuiltOld, () -> buildNew.invokeExact(),
					 VALUE_REPLAY_MAX_ATTEMPTS, null);
					switch (outcome) {
						case WRITTEN -> recordValueReplayWritten(host, task, appliedFields);
						case SKIPPED -> recordValueReplaySkipped(host, task,
						 "skipped: the live static value no longer equals the rebuilt old initializer,"
						 + " so the write was preserved by design", skippedFields);
						case EXHAUSTED -> recordValueReplayFailed(host, task,
						 "VALUE-REPLAY-CAS-EXHAUSTED: the reference CAS never matched after "
						 + VALUE_REPLAY_MAX_ATTEMPTS + " attempts", failedFields, failedDetails);
					}
					continue;
				}

				if (alive == null || alive.length == 0) {
					// A6：scope 内没有存活实例 = no-op，不是失败；不写 applied 记录、不标 written。
					recordValueReplaySkipped(host, task,
					 "skipped: no live instance to replay (no-op by design)", skippedFields);
					continue;
				}

				int writtenInstances = 0;
				int skippedInstances = 0;
				int failedInstances = 0;
				String instanceFailure = null;
				for (Object target : alive) {
					HotswapBridge.ReferenceReplayOutcome outcome;
					try {
						// W1-W4：新值每实例独立构造；重试复用同一实例的新值并重读/重判等。
						outcome = HotswapBridge.replayReferenceField(
						 slot, target, rebuiltOld, () -> buildNew.invokeExact(),
						 VALUE_REPLAY_MAX_ATTEMPTS, null);
					} catch (LinkageError le) {
						if (!isolatableLinkFailure(le)) throw le;
						failedInstances++;
						if (instanceFailure == null) instanceFailure = String.valueOf(le);
						continue;
					} catch (Throwable t) {
						failedInstances++;
						if (instanceFailure == null) instanceFailure = String.valueOf(t);
						continue;
					}
					switch (outcome) {
						case WRITTEN -> writtenInstances++;
						case SKIPPED -> skippedInstances++;
						case EXHAUSTED -> {
							failedInstances++;
							if (instanceFailure == null) {
								instanceFailure = "VALUE-REPLAY-CAS-EXHAUSTED: the reference CAS never"
								 + " matched after " + VALUE_REPLAY_MAX_ATTEMPTS + " attempts";
							}
						}
					}
				}
				if (failedInstances > 0) {
					recordValueReplayFailed(host, task, instanceFailure
					 + " (instances: written=" + writtenInstances + ", skipped=" + skippedInstances
					 + ", failed=" + failedInstances + ")", failedFields, failedDetails);
				} else if (writtenInstances > 0) {
					// 至少一个实例写入成功：字段记 applied；skipped 实例是"按设计保留"，不掩盖写入。
					recordValueReplayWritten(host, task, appliedFields);
				} else {
					recordValueReplaySkipped(host, task,
					 "skipped: every live instance no longer equals the rebuilt old initializer,"
					 + " so the writes were preserved by design", skippedFields);
				}
			} catch (LinkageError le) {
				if (!isolatableLinkFailure(le)) throw le;
				recordValueReplayFailed(host, task, "runtime failure: " + le,
				 failedFields, failedDetails);
			} catch (Throwable t) {
				recordValueReplayFailed(host, task, "runtime failure: " + t,
				 failedFields, failedDetails);
			}
		}
		return new ValueReplayResult(List.copyOf(appliedFields), List.copyOf(skippedFields),
		 List.copyOf(failedFields), List.copyOf(failedDetails));
	}

	/**
	 * 绑定补丁类里 {@code ()Ljava/lang/Object;} 形式的 buildOld/buildNew 工厂方法。
	 *
	 * <p>发射器固定返回 {@code Object}（宿主字段可能声明为 {@code Object} 或接口，表达式构造类型不必
	 * 与字段描述符一致），因此查找类型就是 {@code ()Object}，无需按字段描述符解析。</p>
	 */
	private static MethodHandle bindValueReplayFactory(Lookup patchLookup, Class<?> patchClass,
	                                                   String methodName) throws Throwable {
		return patchLookup.findStatic(patchClass, methodName, MethodType.methodType(Object.class));
	}

	/** 值类型重放 task 的 applied 记录形态（记录键与 {@link ValueTypeReplay.Field} 同构）。 */
	private static ValueTypeReplay.Field valueReplayAppliedField(ValueReplayTask task) {
		return new ValueTypeReplay.Field(task.fieldName(), task.isStatic(), task.desc(),
		 task.oldFingerprint(), task.newFingerprint(), task.ctorFingerprint(),
		 task.equalsFingerprint());
	}

	private static void recordValueReplayWritten(Class<?> host, ValueReplayTask task,
	                                             List<String> appliedFields) {
		String fieldName = task.fieldName();
		if (!appliedFields.contains(fieldName)) appliedFields.add(fieldName);
		InitFix.valueReplayAppliedMarkWritten(host, valueReplayAppliedField(task));
		InitFix.valueReplayRecordOutcome(host, fieldName, InitFix.FieldStatus.ACCEPTED, null);
		RemoldAgent.info("[VALUE-REPLAY] " + host.getName() + "." + fieldName
		 + " applied: the live value(s) equalled the rebuilt old initializer and the conditional"
		 + " reference CAS succeeded; the applied record is written");
	}

	private static void recordValueReplaySkipped(Class<?> host, ValueReplayTask task,
	                                             String reason, List<String> skippedFields) {
		String fieldName = task.fieldName();
		if (!skippedFields.contains(fieldName)) skippedFields.add(fieldName);
		InitFix.valueReplayAppliedMarkSkipped(host, fieldName);
		InitFix.valueReplayRecordOutcome(host, fieldName, InitFix.FieldStatus.NOTHING_TO_PATCH, reason);
		if (InitFix.valueReplayFirstOutcomeLog(
		 host.getName(), fieldName, task.newFingerprint(), false)) {
			RemoldAgent.info("[VALUE-REPLAY] " + host.getName() + "." + fieldName + " " + reason
			 + " (no applied record; the channel re-enters only when the initializer changes again)");
		}
	}

	private static void recordValueReplayFailed(Class<?> host, ValueReplayTask task, String detail,
	                                            List<String> failedFields,
	                                            List<String> failedDetails) {
		String fieldName = task.fieldName();
		if (!failedFields.contains(fieldName)) failedFields.add(fieldName);
		InitFix.valueReplayAppliedMarkFailed(host, fieldName);
		InitFix.valueReplayRecordOutcome(host, fieldName, InitFix.FieldStatus.REJECTED,
		 "failed: " + detail + " (not recorded in FieldLedger; not retried automatically)");
		if (!failedDetails.contains(host.getName() + "." + fieldName + ": " + detail)) {
			failedDetails.add(host.getName() + "." + fieldName + ": " + detail);
		}
		if (InitFix.valueReplayFirstOutcomeLog(
		 host.getName(), fieldName, task.newFingerprint(), true)) {
			RemoldAgent.warn("[VALUE-REPLAY] " + host.getName() + "." + fieldName + " failed: " + detail
			 + " (not recorded in FieldLedger; the channel will not retry automatically,"
			 + " the next initializer change or an explicit force re-enters it)");
		}
	}

	private static String formatApplyPatchPerf(Class<?> host, long patchStartNs, int liveCount,
	 long collectNs, long compactNs, long selfScanNs, long defineNs,
	 long staticNs, long applyReplayNs, long instNs, long postNs) {
		double totalMs = (System.nanoTime() - patchStartNs) / 1_000_000.0;
		return String.format(Locale.ROOT,
		 "[InitFix] applyPatch(%s): live=%d, total=%.1fms [collect=%.1f, compact=%.1f, selfScan=%.1f, define=%.1f, static=%.1f, applyReplay=%.1f, inst=%.1f, post=%.1f]",
		 host.getName(), liveCount, totalMs,
		 collectNs / 1_000_000.0, compactNs / 1_000_000.0, selfScanNs / 1_000_000.0,
		 defineNs / 1_000_000.0, staticNs / 1_000_000.0, applyReplayNs / 1_000_000.0,
		 instNs / 1_000_000.0, postNs / 1_000_000.0);
	}

	/**
	 * {@code @RemoldReinit} 生效点名的文案（{@code docs/initfix/03-runtime-driver.md} §4）。
	 *
	 * <p>默认作用域只含"本次新增字段"，已有字段能进补丁<b>只能</b>是注解要求的。但补丁日志本身
	 * 只报"给几个字段打了补丁"，两者在日志里长得一模一样：用户看到
	 * {@code Applying static field init patch ..., fields=1} 时无从判断自己的存量值为什么被改了，
	 * 也不知道"我改了初值却没生效"是因为没标注解。这一行负责把这件事说破。</p>
	 *
	 * <p>纯函数（只读入参，不碰 {@link RemoldAgent}），文案因此可以被测试直接断言。</p>
	 *
	 * @param className       宿主类名，仅用于日志
	 * @param plan            本轮补丁计划；{@link PatchPlan#reinitFields()} 为空时整行不输出
	 * @param failedFields    运行期失败的字段
	 * @param skippedFields   因依赖失败被放弃的字段
	 * @param instanceTargets 实例阶段枚举到的实例数（{@code INCLUDE_SUBCLASSES} 字段的可写目标数）
	 * @param selfTargets     {@code SELF} 字段的可写目标数（运行时类恰好等于宿主的实例数）；
	 *                        SELF 字段不能用 {@code instanceTargets} 判"有没有实例可写"，
	 *                        否则"只有子类实例存活"会被误报成已生效
	 * @return 需要输出的整行日志；没有任何注解字段真正生效时返回 {@code null}（保持静默）
	 */
	static String reinitHint(
	 String className, PatchPlan plan, Set<String> failedFields,
	 Set<String> skippedFields, int instanceTargets, int selfTargets) {

		if (plan == null || plan.reinitFields().isEmpty()) return null;

		Set<String> staticNames = new HashSet<>();
		for (FieldPatchTask task : plan.staticTasks()) staticNames.add(task.fieldName());
		Set<String> selfOnlyNames = new HashSet<>();
		for (FieldPatchTask task : plan.instanceTasks()) {
			if (task.selfOnly()) selfOnlyNames.add(task.fieldName());
		}

		StringBuilder applied = new StringBuilder();
		StringBuilder notDone = new StringBuilder();
		for (Map.Entry<String, String> e : plan.reinitFields().entrySet()) {
			String  field    = e.getKey();
			boolean isStatic = staticNames.contains(field);
			int     targets  = selfOnlyNames.contains(field) ? selfTargets : instanceTargets;
			String  why;
			if (failedFields.contains(field)) {
				why = "failed";
			} else if (skippedFields.contains(field)) {
				why = "skipped";
			} else if (!isStatic && targets == 0) {
				// 计划里有、但没有实例可写：说"生效"就是撒谎。
				why = "no live instance";
			} else {
				why = null;
			}

			StringBuilder target = why == null ? applied : notDone;
			if (target.length() > 0) target.append(", ");
			target.append(field).append(" (").append(e.getValue())
			 .append(isStatic ? ", static" : ", instance").append(')');
			if (why != null) target.append(" - ").append(why);
		}

		StringBuilder line = new StringBuilder("[REINIT] @RemoldReinit ");
		if (applied.length() > 0) {
			line.append("applied to ").append(className).append(": ").append(applied);
			if (notDone.length() > 0) line.append("; not applied: ").append(notDone);
			return line.append(" (default scope only includes newly added fields; these fields entered candidates via annotation; "
			                    + "modifying initial values of unannotated existing fields will not be reset)").toString();
		}
		return line.append("did not take effect on ").append(className)
		 .append(": ").append(notDone).toString();
	}

	/**
	 * 判断一个存活实例是否落在该注解字段的重置作用域内（{@code docs/initfix/03-runtime-driver.md} §4）。
	 *
	 * <p>纯函数：不碰堆、不写状态，供驱动层与测试直接调用。</p>
	 *
	 * <p><b>必须先归一宿主 Class</b>：JBR 结构性 redefine 会把静态字段里持有的 Class 引用改写成
	 * 旧版本影子对象（同名同加载器、旧字段元数据）。影子类与 canonical 类不是同一个
	 * {@code java.lang.Class}，直接拿它做 {@code isInstance} / 身份比较会把所有实例判成不匹配。</p>
	 *
	 * @param host     声明该字段的宿主类（允许是影子类，内部归一）
	 * @param instance 堆里的存活实例
	 * @param scope    {@link RemoldReinit.Scope#SELF} 只匹配运行时类恰好等于宿主的实例；
	 *                 {@code INCLUDE_SUBCLASSES}（含 {@code null}）匹配宿主及其子类/匿名子类实例
	 * @return 该实例是否应被写入；{@code null} 入参一律返回 {@code false}（fail-closed）
	 */
	static boolean scopeMatches(Class<?> host, Object instance, RemoldReinit.Scope scope) {
		if (host == null || instance == null) return false;
		return scopeMatchesCanonical(ClassIdentity.canonicalize(host), instance, scope);
	}

	/**
	 * {@link #scopeMatches} 的已归一快路径：调用方保证 {@code canonicalHost} 已经是 canonical 类
	 * （例如 {@code applyPatch} 开头统一归一后的宿主），因此不再逐实例调用 {@code Class.forName}。
	 */
	private static boolean scopeMatchesCanonical(Class<?> canonicalHost, Object instance,
	                                             RemoldReinit.Scope scope) {
		if (canonicalHost == null || instance == null) return false;
		if (scope == RemoldReinit.Scope.SELF) return instance.getClass() == canonicalHost;
		return canonicalHost.isInstance(instance);
	}

	static PatchPlan pendingPlanForTest(Class<?> clazz) {
		PendingPatch patch = PENDING.get(ClassIdentity.canonicalize(clazz));
		return patch == null ? null : patch.plan();
	}

	static byte[] pendingBytesForTest(Class<?> clazz) {
		PendingPatch patch = PENDING.get(ClassIdentity.canonicalize(clazz));
		return patch == null ? null : patch.bytes();
	}

	static void runIndependentInstanceTasks(
	 Class<?> host, Object[] targets, BoundInstanceTask[] tasks,
	 Set<String> failedFields, Set<String> skippedFields, Map<String, String> failureReasons) {
		runIndependentInstanceTasks(host, targets, tasks, new BoundFusedBatchGroup[0],
		 failedFields, skippedFields, failureReasons);
	}

	static void runIndependentInstanceTasks(
	 Class<?> host, Object[] targets, BoundInstanceTask[] tasks, BoundFusedBatchGroup[] fusedGroups,
	 Set<String> failedFields, Set<String> skippedFields, Map<String, String> failureReasons) {
		InstanceFailureBudget budget = new InstanceFailureBudget();
		Throwable[] errOut = null;
		boolean[] handled = fusedGroups != null && fusedGroups.length > 0 ? new boolean[tasks.length] : null;
		if (fusedGroups != null && fusedGroups.length > 0) {
			errOut = new Throwable[1];
			int[] errSlotOut = new int[1];
			for (BoundFusedBatchGroup group : fusedGroups) {
				for (int idx : group.taskIndices()) {
					handled[idx] = true;
				}
				runFusedBatchGroup(host, targets, tasks, group, budget, errOut, errSlotOut,
				 failedFields, skippedFields, failureReasons);
			}
		}
		for (int i = 0; i < tasks.length; i++) {
			if (handled != null && handled[i]) continue;
			BoundInstanceTask task = tasks[i];
			if (task.batchHandle() != null) {
				if (errOut == null) errOut = new Throwable[1];
				runBatchInstanceTask(host, targets, task, budget, errOut,
				 failedFields, skippedFields, failureReasons);
			} else {
				for (Object target : targets) {
					executeIndependentTask(host, target, task, budget, failedFields, skippedFields, failureReasons);
				}
			}
		}
	}

	private static void runFusedBatchGroup(
	 Class<?> host, Object[] targets, BoundInstanceTask[] tasks, BoundFusedBatchGroup group,
	 InstanceFailureBudget budget, Throwable[] errOut, int[] errSlotOut,
	 Set<String> failedFields, Set<String> skippedFields, Map<String, String> failureReasons) {
		int[] indices = group.taskIndices();
		if (indices.length == 0 || targets.length == 0) return;
		boolean[] active = new boolean[indices.length];
		int activeCount = 0;
		for (int j = 0; j < indices.length; j++) {
			String fieldName = tasks[indices[j]].fieldName();
			if (budget.exhausted(fieldName)) {
				budget.recordGiveUp(fieldName, skippedFields, failureReasons);
			} else {
				active[j] = true;
				activeCount++;
			}
		}
		int pos = 0;
		int chunkCalls = 0;
		while (pos < targets.length && activeCount > 0) {
			errOut[0] = null;
			errSlotOut[0] = -1;
			int chunkEnd = nextBatchChunkEnd(pos, targets.length, chunkCalls++);
			int failedIdx;
			try {
				failedIdx = (int) group.handle().invokeExact(
				 targets, pos, chunkEnd, active, errOut, errSlotOut);
			} catch (LinkageError le) {
				if (!isolatableLinkFailure(le)) throw le;
				fallbackFusedGroupFrom(host, targets, pos, indices, active, tasks, budget, errOut,
				 failedFields, skippedFields, failureReasons);
				return;
			} catch (Throwable t) {
				fallbackFusedGroupFrom(host, targets, pos, indices, active, tasks, budget, errOut,
				 failedFields, skippedFields, failureReasons);
				return;
			}
			if (failedIdx < 0) {
				pos = chunkEnd;
				continue;
			}
			int slotJ = errSlotOut[0];
			Throwable failure = errOut[0];
			if (failure instanceof LinkageError le && !isolatableLinkFailure(le)) throw le;
			if (slotJ >= 0 && slotJ < indices.length && active[slotJ]) {
				BoundInstanceTask failedTask = tasks[indices[slotJ]];
				recordTaskFailure(host, failedTask.fieldName(), failure, failedFields, failureReasons);
				budget.recordFailure(failedTask.fieldName());
				if (budget.exhausted(failedTask.fieldName())) {
					budget.recordGiveUp(failedTask.fieldName(), skippedFields, failureReasons);
					active[slotJ] = false;
					activeCount--;
				}
				Object failedTarget = targets[failedIdx];
				for (int m = slotJ + 1; m < indices.length; m++) {
					if (!active[m]) continue;
					BoundInstanceTask remTask = tasks[indices[m]];
					executeIndependentTask(host, failedTarget, remTask, budget,
					 failedFields, skippedFields, failureReasons);
					if (budget.exhausted(remTask.fieldName())) {
						budget.recordGiveUp(remTask.fieldName(), skippedFields, failureReasons);
						active[m] = false;
						activeCount--;
					}
				}
			}
			pos = failedIdx + 1;
		}
	}

	private static void fallbackFusedGroupFrom(
	 Class<?> host, Object[] targets, int startPos, int[] indices, boolean[] active,
	 BoundInstanceTask[] tasks, InstanceFailureBudget budget, Throwable[] errOut,
	 Set<String> failedFields, Set<String> skippedFields, Map<String, String> failureReasons) {
		for (int j = 0; j < indices.length; j++) {
			if (!active[j]) continue;
			BoundInstanceTask task = tasks[indices[j]];
			if (task.batchHandle() != null) {
				runBatchInstanceTaskFrom(host, targets, startPos, task, budget, errOut,
				 failedFields, skippedFields, failureReasons);
			} else {
				for (int k = startPos; k < targets.length; k++) {
					executeIndependentTask(host, targets[k], task, budget,
					 failedFields, skippedFields, failureReasons);
				}
			}
		}
	}

	/**
	 * 大数组渐进切块：小于等于 4096 一次性跑完；更大数组前 2048 次以步长 4 推进（仅覆盖前 8192 个元素，
	 * 每元素严格访问一次），稳定通过方法入口计数触发 C1/C2 编译而不依赖异步 OSR，随后按 8192 大块收尾。
	 */
	/**
	 * 下一块的结束下标（不含）。
	 *
	 * <p>前 2048 次调用按 4 递增把 JIT 的调用点预热到稳定形态，之后放大到 8192 以减少解释/调用开销。
	 * 步长放大必须钳到 {@code total}：剩余量落在 {@code (4096, 8192)} 时（{@code total} 属于
	 * 12289..16383 等区间），{@code pos + 8192} 会越过数组末端，生成器的循环只判
	 * {@code localIndex < localTo}，于是每个实例都抛 {@code ArrayIndexOutOfBoundsException}，
	 * 直到单个字段的失败配额被撞满——已补成功的字段因此被当成"失败放弃"，
	 * 而两阶段路径会退回 {@code fallbackFusedGroupFrom}，对同一段实例重复做条件 CAS。</p>
	 *
	 * @return {@code pos < 返回值 <= total}
	 */
	static int nextBatchChunkEnd(int pos, int total, int chunkCalls) {
		int remaining = total - pos;
		if (remaining <= 4096) return total;
		return Math.min(total, chunkCalls < 2048 ? pos + 4 : pos + 8192);
	}

	private static void runBatchInstanceTask(
	 Class<?> host, Object[] targets, BoundInstanceTask task, InstanceFailureBudget budget,
	 Throwable[] errOut, Set<String> failedFields, Set<String> skippedFields,
	 Map<String, String> failureReasons) {
		runBatchInstanceTaskFrom(host, targets, 0, task, budget, errOut,
		 failedFields, skippedFields, failureReasons);
	}

	private static void runBatchInstanceTaskFrom(
	 Class<?> host, Object[] targets, int startPos, BoundInstanceTask task, InstanceFailureBudget budget,
	 Throwable[] errOut, Set<String> failedFields, Set<String> skippedFields,
	 Map<String, String> failureReasons) {
		int pos = startPos;
		int chunkCalls = 0;
		while (pos < targets.length) {
			if (budget.exhausted(task.fieldName())) {
				budget.recordGiveUp(task.fieldName(), skippedFields, failureReasons);
				return;
			}
			errOut[0] = null;
			int chunkEnd = nextBatchChunkEnd(pos, targets.length, chunkCalls++);
			int failedIdx;
			try {
				failedIdx = (int) task.batchHandle().invokeExact(targets, pos, chunkEnd, errOut);
			} catch (LinkageError le) {
				if (!isolatableLinkFailure(le)) throw le;
				recordTaskFailure(host, task.fieldName(), le, failedFields, failureReasons);
				budget.recordFailure(task.fieldName());
				pos++;
				continue;
			} catch (Throwable t) {
				recordTaskFailure(host, task.fieldName(), t, failedFields, failureReasons);
				budget.recordFailure(task.fieldName());
				pos++;
				continue;
			}
			if (failedIdx < 0) {
				pos = chunkEnd;
				continue;
			}
			Throwable failure = errOut[0];
			if (failure instanceof LinkageError le && !isolatableLinkFailure(le)) throw le;
			recordTaskFailure(host, task.fieldName(), failure, failedFields, failureReasons);
			budget.recordFailure(task.fieldName());
			pos = failedIdx + 1;
		}
	}

	private static void executeIndependentTask(
	 Class<?> host, Object target, BoundInstanceTask task, InstanceFailureBudget budget,
	 Set<String> failedFields, Set<String> skippedFields, Map<String, String> failureReasons) {
		// SELF 作用域：枚举结果里含子类/匿名子类实例，只有恰好等于宿主的那部分可写。
		// host 在 applyPatch 开头已归一，用快路径避免逐实例 Class.forName。
		if (task.selfOnly() && !scopeMatchesCanonical(host, target, RemoldReinit.Scope.SELF)) return;
		if (budget.exhausted(task.fieldName())) {
			budget.recordGiveUp(task.fieldName(), skippedFields, failureReasons);
			return;
		}
		try {
			task.handle().invokeExact(target);
		} catch (LinkageError le) {
			if (!isolatableLinkFailure(le)) throw le;
			recordTaskFailure(host, task.fieldName(), le, failedFields, failureReasons);
			budget.recordFailure(task.fieldName());
		} catch (Throwable t) {
			recordTaskFailure(host, task.fieldName(), t, failedFields, failureReasons);
			budget.recordFailure(task.fieldName());
		}
	}

	/**
	 * 逐实例驱动的失败预算（{@code docs/initfix/03-runtime-driver.md} §2 的代价控制）。
	 *
	 * <p>按实例隔离"依赖跳过"之后，确定性失败（切片本身必然抛异常）会让每个实例
	 * 都各自失败一遍，日志与耗时放大成 N 倍。这里给每个字段一个本轮的失败配额，
	 * 用满即对后续实例放弃该字段。</p>
	 *
	 * <p>预算按<b>每个 applyPatch 一轮</b>计，不跨轮累积 —— 下一轮热更会重新给足配额，
	 * 与台账"下一轮重新纳入候选"的语义一致。</p>
	 */
	private static final class InstanceFailureBudget {
		private final Map<String, Integer> counts = new LinkedHashMap<>();
		private boolean anyFailure;

		boolean exhausted(String fieldName) {
			if (!anyFailure) return false;
			return counts.getOrDefault(fieldName, 0) >= MAX_INSTANCE_FAILURES_PER_FIELD;
		}

		void recordFailure(String fieldName) {
			anyFailure = true;
			counts.merge(fieldName, 1, Integer::sum);
		}

		/** 记一次"放弃"：该字段已耗尽配额，本轮不再重试。 */
		void recordGiveUp(String fieldName, Set<String> skippedFields,
		                  Map<String, String> failureReasons) {
			if (skippedFields.add(fieldName)) {
				// put 而非 putIfAbsent：首次失败已记下原始异常文本，
				// 这里的"放弃"是更具体的结论，覆盖它。
				failureReasons.put(fieldName, "field failed "
				                              + MAX_INSTANCE_FAILURES_PER_FIELD
				                              + " times, giving up for this round");
			}
		}
	}

	private static void runDependentInstanceTasks(
	 Class<?> host, Object[] targets, BoundInstanceTask[] tasks,
	 Set<String> failedFields, Set<String> skippedFields, Map<String, String> failureReasons,
	 Set<String> staticFailures) {

		InstanceFailureBudget budget = new InstanceFailureBudget();

		for (Object target : targets) {
			// docs/initfix/03-runtime-driver.md §2：依赖跳过必须<b>按实例</b>隔离。
			// 旧实现让所有实例共享 failed/skipped：某个实例的 X 失败后，
			// 其余实例的依赖字段 Y 全被跳过 —— 尽管它们的 X 是成功的，
			// 同一轮内状态因此是分裂的。这里每个 target 一份局部集合。
			//
			// 局部集合只继承<b>静态侧</b>的失败（staticFailures）：静态字段全局唯一，
			// 它失败时每个实例的下游都该跳过；而实例侧的失败只影响出错的那个实例。
			Set<String> localFailed  = new LinkedHashSet<>(staticFailures);
			Set<String> localSkipped = new LinkedHashSet<>();

			for (BoundInstanceTask task : tasks) {
				// SELF 作用域：子类/匿名子类实例不承接该字段的写入。
				if (task.selfOnly() && !scopeMatchesCanonical(host, target, RemoldReinit.Scope.SELF)) continue;
				// 熔断：该字段在本轮已连续失败多次，不再对后续实例重试。
				if (budget.exhausted(task.fieldName())) {
					localSkipped.add(task.fieldName());
					budget.recordGiveUp(task.fieldName(), skippedFields, failureReasons);
					continue;
				}

				Set<String> dependencies = task.dependencies();
				if (!dependencies.isEmpty()
				    && (!Collections.disjoint(dependencies, activeBatchFailures())
				        || !Collections.disjoint(dependencies, localFailed)
				        || !Collections.disjoint(dependencies, localSkipped))) {
					// 全局只做计数与记账；告警不按实例刷屏。
					localSkipped.add(task.fieldName());
					if (skippedFields.add(task.fieldName())) {
						failureReasons.putIfAbsent(task.fieldName(), "dependency failed: "
						                                             + dependencyFailureDetail(
						 dependencies, localFailed, localSkipped));
						RemoldAgent.warn("Skipping field init for " + host.getName() + "."
						                  + task.fieldName() + " because a dependency failed");
					}
					continue;
				}
				try {
					task.handle().invokeExact(target);
				} catch (LinkageError le) {
					if (!isolatableLinkFailure(le)) throw le;
					localFailed.add(task.fieldName());
					budget.recordFailure(task.fieldName());
					recordTaskFailure(host, task.fieldName(), le, failedFields, failureReasons);
				} catch (Throwable t) {
					localFailed.add(task.fieldName());
					budget.recordFailure(task.fieldName());
					recordTaskFailure(host, task.fieldName(), t, failedFields, failureReasons);
				}
			}
		}
	}

	private static void recordTaskFailure(
	 Class<?> host, String fieldName, Throwable failure,
	 Set<String> failedFields, Map<String, String> failureReasons) {
		failedFields.add(fieldName);
		failureReasons.putIfAbsent(fieldName, String.valueOf(failure));
		if (DETAILED_FAILURE_LOGS.incrementAndGet() <= MAX_DETAILED_FAILURES) {
			RemoldAgent.error("Field init patch failed for " + host.getName()
			                   + "." + fieldName + ": " + failure.getMessage(), failure);
		}
	}

	/**
	 * 按拓扑序执行一组字段任务。
	 * @param target 实例字段时为对象，静态字段时忽略
	 */
	private static void runTasks(
	 Class<?> host, List<FieldPatchTask> tasks, Map<String, MethodHandle> handles,
	 Object target, Set<String> failedFields, Set<String> skippedFields,
	 Map<String, String> failureReasons) {

		for (FieldPatchTask task : tasks) {
			if (!task.dependencies().isEmpty()
			    && (!Collections.disjoint(task.dependencies(), activeBatchFailures())
			        || !Collections.disjoint(task.dependencies(), failedFields)
			        || !Collections.disjoint(task.dependencies(), skippedFields))) {
				if (skippedFields.add(task.fieldName())) {
					failureReasons.putIfAbsent(task.fieldName(), "dependency failed: "
					                                             + dependencyFailureDetail(
					 task.dependencies(), failedFields, skippedFields));
					RemoldAgent.warn("Skipping field init for " + host.getName() + "."
					                  + task.fieldName() + " because a dependency failed");
				}
				continue;
			}
			MethodHandle mh = handles.get(task.fieldName());
			if (mh == null) {
				skippedFields.add(task.fieldName());
				failureReasons.putIfAbsent(task.fieldName(), "no MethodHandle for method");
				continue;
			}
			try {
				if (target == null) { mh.invokeExact(); } else mh.invokeExact(target);
			} catch (LinkageError le) {
				// AGENTS.md 不变量 4 熔断只针对"JVM 元数据假设已被打破"的系统性故障。
				// indy bootstrap 失败（BootstrapMethodError 包装了我们自己的解析失败）
				// 是<b>单字段</b>的链接问题，不该中止整轮 —— 那与"每字段一个方法、
				// 失败隔离"的设计目标直接冲突。
				if (!isolatableLinkFailure(le)) throw le;
				recordTaskFailure(host, task.fieldName(), le, failedFields, failureReasons);
			} catch (Throwable t) {
				recordTaskFailure(host, task.fieldName(), t, failedFields, failureReasons);
			}
		}
	}

	/**
	 * 判断一个 {@code LinkageError} 是否是<b>可隔离的单字段链接失败</b>，
	 * 而不是"JVM 元数据假设已被打破"的系统性故障（后者必须熔断，AGENTS.md 不变量 4）。
	 *
	 * <p>为什么需要区分：{@code HotswapBridge.bootstrap} 把任何 {@code Throwable} 都包成
	 * {@code BootstrapMethodError}（{@code LinkageError} 的子类）。因此某个字段的
	 * {@code findField} 解析失败，在驱动侧看起来与"类结构被破坏"一模一样 ——
	 * 一律 {@code throw} 会让<b>一个字段</b>的链接问题中止所有字段、所有实例，
	 * 与"每字段一个方法、失败隔离"的设计目标直接冲突。</p>
	 *
	 * <p><b>判定依据是"异常来源"而非异常类型</b>：{@code BootstrapMethodError} 既能包装
	 * 我们自己的解析失败（{@code NoSuchFieldException} 等），也能包装真正的
	 * {@code LinkageError}（实测 `getCause()` 两种情况都可能）。因此：</p>
	 * <ul>
	 *   <li>{@code BootstrapMethodError} 且 cause 是<b>非</b> {@code LinkageError}
	 *       → 我们自己 bootstrap 阶段的解析失败，只影响该字段 → 可隔离。</li>
	 *   <li>{@code BootstrapMethodError} 且 cause <b>是</b> {@code LinkageError}
	 *       → 真正的元数据问题被透传上来 → 熔断。</li>
	 *   <li>其它 {@code LinkageError}（{@code NoSuchFieldError}、{@code VerifyError}、
	 *       {@code IncompatibleClassChangeError} …）→ 熔断。</li>
	 * </ul>
	 *
	 * <p>误判代价不对称：把系统性故障当可隔离，会继续修补一个已经不可信的类
	 * （危险）；把可隔离失败当系统性故障，只是回到旧行为（保守但过度中止）。
	 * 因此判定取<b>窄</b>。</p>
	 */
	private static boolean isolatableLinkFailure(LinkageError le) {
		if (!(le instanceof BootstrapMethodError)) return false;
		Throwable cause = le.getCause();
		if (cause == null) return false;
		// cause 本身就是 LinkageError → 真正的元数据问题，必须熔断
		return !(cause instanceof LinkageError);
	}

	/**
	 * 依赖跳过的可读归因：本地失败/跳过集合与跨类批次令牌合并，令牌显示成 {@code Host.field} 而不是
	 * 裸 {@code @batch:} 前缀。历史实现只合并本地集合，跨类跳过因此永远记成 {@code dependency failed: []}。
	 */
	private static String dependencyFailureDetail(
	 Set<String> dependencies, Set<String> localFailed, Set<String> localSkipped) {
		Set<String> detail = new LinkedHashSet<>(intersection(dependencies, localFailed, localSkipped));
		Set<String> batch = new LinkedHashSet<>(dependencies);
		batch.retainAll(activeBatchFailures());
		for (String token : batch) detail.add(token.replace("@batch:", ""));
		return detail.isEmpty() ? "<dependency failed>" : detail.toString();
	}

	private static Set<String> intersection(Set<String> deps, Set<String> a, Set<String> b) {
		Set<String> out = new LinkedHashSet<>();
		for (String d : deps) {
			if (a.contains(d) || b.contains(d)) out.add(d);
		}
		return out;
	}

	private static Set<String> union(Set<String> a, Set<String> b, Set<String> c) {
		Set<String> out = new LinkedHashSet<>();
		if (a != null) out.addAll(a);
		out.addAll(b);
		out.addAll(c);
		return out;
	}
	// (union 由 docs/initfix/02-closure-and-ledger.md §2 的 FieldLedger 使用；P1-③ 落地前保留为工具方法)

	private static final Object[] EMPTY_OBJECTS = new Object[0];

	/**
	 * 批次级实例收集的暂存槽（{@code docs/initfix/05-jvmti-heap.md} §「批次实例收集」）。
	 *
	 * <p>一次结构性热更里 K 个类各调一次 {@link #applyPatch} 就是 K 次全堆遍历。批次驱动在
	 * 「重定义生效之后、补丁执行之前」调 {@link #beginBatchInstanceCollection} 做<b>一次</b>遍历，
	 * 每个宿主在自己的 {@code applyPatch} 里消费属于自己的切片（{@link #takeBatchInstances}）。</p>
	 *
	 * <p><b>为什么不能更早收集</b>：实例必须在重定义生效<b>之后</b>枚举——提前拍快照会漏掉
	 * 「快照之后、生效之前」新建的实例（{@code docs/status.md} 风险 6）。批次入口因此只由
	 * 补丁循环的调用方在 redefine 之后调用。</p>
	 *
	 * <p>线程封闭：补丁驱动单线程执行（{@code docs/initfix/03-runtime-driver.md} §4），
	 * 因此用 {@link ThreadLocal} 而不是全局表，避免并发批次互相污染。</p>
	 */
	private static final ThreadLocal<Map<Class<?>, Object[]>> BATCH_INSTANCES = new ThreadLocal<>();

	/**
	 * 启用批次收集的最小 target 数。
	 *
	 * <p>一次批次遍历 = <b>一次全堆遍历</b>（{@code IterateThroughHeap} 带 class tag 过滤），而按类
	 * 遍历是 {@code IterateOverInstancesOfClass}——实测前者约等于后者的 9 倍（同一进程同一堆，
	 * 64 个零实例类：批 1 次 ≈ 84 ms vs 逐类 64 次 ≈ 588 ms ⇒ 单次批遍历 ≈ 9.2 ms × 9）。
	 * 因此 K 小的时候批收集反而是净亏：{@code tests/src/InitFixBatchScanProbe} 的 K=4/8/16 读数给出
	 * 交点，取 8 作为接合阈值（K=8 起持平，K≥16 明显占优）。低于阈值一律走原逐类路径，
	 * 保证小批次零额外开销。</p>
	 */

	/**
	 * 批次收集总开关（{@code -Dremold.agent.batch_heap_scan=false} 关闭）。
	 *
	 * <p>关掉之后每个宿主照旧各自枚举一次堆，<b>行为必须与开启时逐项等价</b>
	 * （{@code PatchReport} 的 status / reason / warnings、补丁字节、写入结果一致）——这是这条
	 * 性能通道可以随时退场的依据，也是等价性门禁的对照面（{@code BatchScanReportEquivalenceTest}）。</p>
	 */
	private static volatile boolean batchHeapScanEnabled =
		!"false".equalsIgnoreCase(System.getProperty("remold.agent.batch_heap_scan", "true"));

	/** 当前是否启用批次收集（诊断/对照用；默认取 {@code -Dremold.agent.batch_heap_scan}）。 */
	static boolean batchHeapScanEnabled() {
		return batchHeapScanEnabled;
	}

	/** 运行期开关批次收集：只影响"要不要批一次枚举"，两条路径的行为必须逐项等价。 */
	static void setBatchHeapScanEnabled(boolean enabled) {
		batchHeapScanEnabled = enabled;
	}

	/**
	 * 为一批宿主做一次全堆遍历，把每个宿主的存活实例切片暂存起来供各自的
	 * {@link #applyPatch} 消费。
	 *
	 * <p>过滤规则（与 {@code applyPatch} 的入口守卫一一对应）：宿主先经
	 * {@link ClassIdentity#canonicalize} 归一（影子类上枚举恒为 0），未初始化
	 * （{@link #isInitialized} 为 false）的类剔除，既没有常规实例任务、也没有值类型重放实例
	 * 目标的宿主不参与。过滤后不足
	 * {@link #InitFix.BATCH_HEAP_SCAN_MIN_TARGETS} 个宿主时直接返回：<b>小批次走原逐类路径，零额外开销</b>
	 * （K=1 自然也在此列）。</p>
	 *
	 * <p>无 native 库（{@link LibTool#initialized()} 为 false 且初始化失败）时同样直接返回，
	 * 让 {@code applyPatch} 走既有的 {@link InstanceTracker} 降级路径。</p>
	 *
	 * @param hosts 本批次待补丁的宿主类（可含重复/未加载为 null 的项）
	 */
	static void beginBatchInstanceCollection(Collection<Class<?>> hosts) {
		endBatchInstanceCollection();
		if (hosts == null || hosts.isEmpty()) return;
		if (!LibTool.initialized() && !LibTool.tryInit()) return;

		LinkedHashSet<Class<?>> targets = new LinkedHashSet<>();
		for (Class<?> host : hosts) {
			if (host == null) continue;
			Class<?> canonical = ClassIdentity.canonicalize(host);
			if (!isInitialized(canonical)) continue;
			PendingPatch patch = PENDING.get(canonical);
			if (patch == null) continue;
			if (!patch.plan().hasInstance() && !hasInstanceValueReplay(patch.plan())) continue;
			targets.add(canonical);
		}
		if (!batchHeapScanEnabled) return;
		if (targets.size() < InitFix.BATCH_HEAP_SCAN_MIN_TARGETS) return;

		Object[][] collected;
		try {
			collected = LibTool.getInstancesBatch(targets.toArray(new Class<?>[0]));
		} catch (Throwable t) {
			// 批收集失败不能连坐整批：退回逐类枚举，行为与改动前一致。
			RemoldAgent.warn("[InitFix] Batch instance collection failed (" + t
			     + "); falling back to one heap walk per class.");
			return;
		}
		if (collected == null || collected.length != targets.size()) return;
		Map<Class<?>, Object[]> byHost = new HashMap<>(targets.size() * 2);
		int i = 0;
		for (Class<?> target : targets) {
			byHost.put(target, collected[i] != null ? collected[i] : EMPTY_OBJECTS);
			i++;
		}
		BATCH_INSTANCES.set(byHost);
	}

	/** 结束批次级实例收集并释放暂存切片（切片被实例强引用，必须显式清掉）。 */
	static void endBatchInstanceCollection() {
		Map<Class<?>, Object[]> byHost = BATCH_INSTANCES.get();
		if (byHost != null) {
			byHost.clear();
			BATCH_INSTANCES.remove();
		}
	}

	/** 取出本批次为 {@code host} 预收集的实例切片；没有预收集时返回 {@code null}（走实时枚举）。 */
	private static Object[] takeBatchInstances(Class<?> host) {
		Map<Class<?>, Object[]> byHost = BATCH_INSTANCES.get();
		if (byHost == null) return null;
		Object[] instances = byHost.remove(host);
		if (byHost.isEmpty()) BATCH_INSTANCES.remove();
		return instances;
	}

	private static Object[] collectRawInstancesForPatch(Class<?> host) {
		// 枚举时机在重定义生效之后、applyPatch 内即时消费后即释放，不跨 redefine 持有，
		// 因此无需绕行 WeakReference 双重拷贝；直接复用枚举数组，仅在出现 null 槽时由调用方压缩。
		Object[] batched = takeBatchInstances(host);
		if (batched != null) return batched;
		return LibTool.initialized()
		 ? LibTool.getInstances(host)
		 : InstanceTracker.getInstances(host).toArray();
	}

	static Object[] compactNonNull(Object[] raw) {
		if (raw == null || raw.length == 0) return EMPTY_OBJECTS;
		int nulls = 0;
		for (Object o : raw) {
			if (o == null) nulls++;
		}
		if (nulls == 0) return raw;
		if (nulls == raw.length) return EMPTY_OBJECTS;
		Object[] out = new Object[raw.length - nulls];
		int w = 0;
		for (Object o : raw) {
			if (o != null) out[w++] = o;
		}
		return out;
	}

	/**
	 * 类是否已完成初始化。两阶段驱动在阶段 A 之后用它对每个类做二次检查
	 * （{@code docs/initfix/04-target-design.md} §1「isInitialized 二次检查」）。
	 */
	static boolean isInitialized(Class<?> clazz) {
		return !UNSAFE.shouldBeInitialized(clazz);
	}

}
