package remold;

import remold.annotation.*;
import remold.evict.ReflectionCacheManager;
import remold.offset.LayoutPredictor;
import remold.offset.OffsetBindingIndex;
import remold.offset.OffsetScanner;
import remold.offset.SuperclassDriftGate;
import remold.profiler.DynamicProfilerAPI;
import remold.ref.HeapFieldWatch;
import remold.ref.InitFix;
import remold.ref.StageASkeleton;
import remold.ref.StageBInstanceCache;
import remold.vcs.AlignmentMetaStore;
import remold.vcs.ChangesetLog;
import remold.vcs.VcsCommitGate;
import remold.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.*;
import java.lang.instrument.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.*;

import static remold.MountManager.*;

import remold.RemoldAgent.BytecodeTransformerBridge;
import remold.RemoldAgent.GateCachedDiff;
import remold.RemoldAgent.InternalRedefineContext;
import remold.RemoldAgent.LayoutAction;
import remold.RemoldAgent.LayoutDecision;
import remold.RemoldAgent.ListenerTiming;
import remold.RemoldAgent.Liveness;
import remold.RemoldAgent.RemovedMemberScan;

import static remold.RemoldAgent.HOTSWAP_LOCK;
import static remold.RemoldAgent.INTERNAL_CONTEXT;
import static remold.RemoldAgent.LAST_REDEFINE_VM_LEVEL_FAILURE;
import static remold.RemoldAgent.LAYOUT_GATE;
import static remold.RemoldAgent.PERF_LOG;
import static remold.RemoldAgent.VM_POISONED_CLASSES;
import static remold.RemoldAgent.alignAccessMonotonicity;
import static remold.RemoldAgent.alignMixinGeneratedMembers;
import static remold.RemoldAgent.annotationDefaultGate;
import static remold.RemoldAgent.applyTransformerBridges;
import static remold.RemoldAgent.checkSuperclassDriftIfApplicable;
import static remold.RemoldAgent.decideLayout;
import static remold.RemoldAgent.enterInternalRedefine;
import static remold.RemoldAgent.error;
import static remold.RemoldAgent.exitInternalRedefine;
import static remold.RemoldAgent.fetchOriginalBytecode;
import static remold.RemoldAgent.getRuntimeBytecode;
import static remold.RemoldAgent.getSourceBytecode;
import static remold.RemoldAgent.hasLiveInstances;
import static remold.RemoldAgent.info;
import static remold.RemoldAgent.inst;
import static remold.RemoldAgent.isEnhancedHotswapEnabled;
import static remold.RemoldAgent.isVmLevelRedefineFailure;
import static remold.RemoldAgent.markVmLevelFailure;
import static remold.RemoldAgent.notifyBatchListeners;
import static remold.RemoldAgent.processAnnotations;
import static remold.RemoldAgent.recordCacheLoader;
import static remold.RemoldAgent.recordRuntimeBytecode;
import static remold.RemoldAgent.recordSourceBytecode;
import static remold.RemoldAgent.warn;

/**
 * 动态单类（无磁盘文件）重定义执行器（从 {@link RemoldAgent} 拆出）。
 */
final class DynamicRedefineExecutor {

	private DynamicRedefineExecutor() {}

	/**
	 * 动态生成类的热更入口（供 Aspector、动态代理框架等无磁盘文件的场景调用）。
	 *
	 * <p>直接对内存中的类进行重定义，跳过磁盘文件监听与文件 Diff，但仍执行字节码重织入桥、
	 * 布局安全门 (LayoutGate) 校验与 InitFix 实例初始化补丁。</p>
	 *
	 * @param targetClass 已加载的目标类
	 * @param newBytecode 新生成的未织入或已织入字节码
	 * @return 是否重定义成功
	 */
	/**
	 * 为单类动态重定义打开一个最小批次上下文。
	 *
	 * <p>键是 loader 身份（{@code null} 表示 bootstrap 槽），值是"该类这一轮的生效字节"。
	 * 与批次路径的差别只在规模：这里永远只有一个类，因此 manifest 的边/节点都是单点，
	 * facts 也只可能来自本类。</p>
	 */
	private static void beginSingleClassBatch(Class<?> targetClass, String className, byte[] runtimeBytes) {
		Map<ClassLoader, Map<String, byte[]>> view = new IdentityHashMap<>();
		Map<String, byte[]> bytes = new LinkedHashMap<>();
		bytes.put(className, runtimeBytes);
		view.put(targetClass.getClassLoader(), bytes);
		InitFix.beginBatchBytecodeView(view);
	}

	static boolean redefineDynamic(Class<?> targetClass, byte[] newBytecode) {
		boolean applied = redefineDynamicApplied(targetClass, newBytecode);
		// 失败（返回 false）才记中止：no-op 走的是"无需变更"的 true 分支，不是失败。
		if (!applied && targetClass != null) DynamicVcsCommit.abort(targetClass.getClassLoader());
		return applied;
	}

	/** 实际实现；{@code seq} 的三种状态转移由 {@link #redefineDynamic} 统一收口。 */
	private static boolean redefineDynamicApplied(Class<?> targetClass, byte[] newBytecode) {
		if (targetClass == null || newBytecode == null) return false;
		if (inst == null || !inst.isRedefineClassesSupported()) {
			error("[DYNAMIC-REDEFINE] JVM does not support class redefinition or agent not attached.");
			return false;
		}
		if (!inst.isModifiableClass(targetClass)) {
			warn("[DYNAMIC-REDEFINE] Class " + targetClass.getName() + " is not modifiable by JVM; skipped.");
			return false;
		}

		if (VM_POISONED_CLASSES.contains(targetClass.getName())) {
			LAST_REDEFINE_VM_LEVEL_FAILURE = true;
			warn("[DYNAMIC-REDEFINE] Refusing to redefine " + targetClass.getName()
			     + ": an earlier JVM-level redefine failure (InternalError/VerifyError) already poisoned"
			     + " this class in the current session. RESTART the client before further hot-swaps.");
			return false;
		}

		HOTSWAP_LOCK.lock();
		try {
			// 动态重定义同样是一个可观测批次：耗时从桥接/布局门之前开始算，
			// 到 InitFix 与回调完成结束。
			long dynStartNanos = System.nanoTime();
			BatchReportScope.begin();
			LAST_REDEFINE_VM_LEVEL_FAILURE = false;
			String className = targetClass.getName();
			byte[] sourceBytes = newBytecode;
			byte[] runtimeBytes;
			long bridgeStartNanos = System.nanoTime();
			try {
				runtimeBytes = applyTransformerBridges(targetClass.getClassLoader(), className, targetClass, newBytecode);
			} catch (BytecodeTransformerBridge.Abort abort) {
				// 事务一致性：桥接器判定本轮不能落地（例如目标类的合成内部类被闸拒绝）。
				// 此时既不能送织入后的新字节（会与旧合成类组成 NoSuchMethodError 分裂状态），
				// 也不能送未织入的原始字节（会抹掉 Mixin 钩子）——唯一正确动作是不 redefineClasses。
				warn("[DYNAMIC-REDEFINE] " + className + ": aborted by the bytecode bridge before redefineClasses ("
				     + abort.getMessage() + "). The class keeps its previously loaded bytes; nothing was redefined.");
				return false;
			}
			long bridgeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - bridgeStartNanos);
			long alignStartNanos = System.nanoTime();
			// 第二缺陷：Mixin 生成成员名/顺序先对齐回基线，让纯方法体热更退化为 0 新增/0 删除。
			runtimeBytes = alignMixinGeneratedMembers(className, targetClass, runtimeBytes);
			long mixinAlignMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - alignStartNanos);
			long accessAlignStartNanos = System.nanoTime();
			// 第一层：按已加载基线做访问权限单调对齐；自检门拒绝时绝不调用 redefineClasses。
			runtimeBytes = alignAccessMonotonicity(className, targetClass, runtimeBytes);
			long accessAlignMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - accessAlignStartNanos);
			long alignMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - alignStartNanos);
			if (runtimeBytes == null) {
				return false;
			}

			long baselineStartNanos = System.nanoTime();
			byte[] oldBytes = getRuntimeBytecode(className);
			if (oldBytes == null) oldBytes = fetchOriginalBytecode(targetClass);
			if (oldBytes == null) oldBytes = getSourceBytecode(className);
			long baselineMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - baselineStartNanos);
			if (oldBytes == null) {
				warn("[DYNAMIC-REDEFINE] No baseline for " + className
					+ " (bytecodeCache/sourceBytecodeCache/fetchOriginalBytecode all empty);"
					+ " refusing to redefine without LayoutGate and InitFix.");
				return false;
			}
			if (Arrays.equals(oldBytes, runtimeBytes)) {
				// 返回 true 在这里表示"无需变更"，不是"热更已生效"：没有任何类被 redefine，
				// 也不会触发 InitFix / @OnReload / 批次回调。必须默认可见地打出来，否则调用方
				// 无法区分"真重定义了"与"字节相同直接返回"。
				info("[DYNAMIC-REDEFINE] no-op: " + className
				     + " runtime bytecode equals the baseline; no redefine performed (nothing to change)."
				     + (PERF_LOG ? " (total=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dynStartNanos)
				        + "ms [bridge=" + bridgeMs + "ms, align=" + alignMs + "ms, baseline=" + baselineMs + "ms])"
				        : ""));
				return true;
			}
			long diffStartNanos = System.nanoTime();
			ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diffStructural(oldBytes, runtimeBytes);
			if (InitFix.needsMethodBodies(targetClass, diff)) {
				ClassDiffUtil.ensureFullClassNodes(diff, oldBytes, runtimeBytes);
			}
			String annotationDefaultChange = annotationDefaultGate(className, oldBytes, runtimeBytes);
			long diffMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - diffStartNanos);
			if (annotationDefaultChange != null) {
				// 探针读数：注解默认值变化后已加载的消费者类仍读旧默认值（JBR 21/25，含 DCEVM 运行时）。
				// 单类入口没有"同批连坐"的对象，拒绝本类即等于整体不重定义（宿主保持旧版）。
				error("[DYNAMIC-REDEFINE] " + className + ": annotation default value changed ("
					+ annotationDefaultChange + "); refusing the redefine because classes that are already"
					+ " loaded keep the OLD default value — RESTART required."
					+ " Explicit override: -Dremold.agent.annotation_default_change=allow.");
				return false;
			}
			if (ClassDiffUtil.mustRejectHierarchyChange(diff, isEnhancedHotswapEnabled())) {
				error("REJECTED: Class hierarchy change detected for dynamic class " + className + " ("
					+ ClassDiffUtil.describeHierarchyChange(diff) + ")."
					+ (diff.superChanged
						? " Changing the superclass is not supported by any redefinition mode; RESTART required."
						: " Interface changes require enhanced class redefinition (JBR/DCEVM); RESTART required on this JVM."));
				return false;
			}
			long gateStartNanos = System.nanoTime();
			LayoutDecision decision = decideDynamicLayout(targetClass, className, diff, runtimeBytes);
			long gateMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - gateStartNanos);
			String why = decision.reason() != null
				? decision.reason()
				: (decision.layout() != null ? decision.layout().detail() : "unknown reason");
			switch (decision.action()) {
				case PASS:
					break;
				case WAIVE_NO_READERS:
					warn("[LAYOUT-WAIVE] Dynamic class " + className + ": member(s) removed ("
					     + why + ") and no out-of-batch reader still needs them; redefining.");
					break;
				case WAIVE:
					warn("[LAYOUT-WAIVE] Dynamic class " + className + ": incompatible field layout ("
					     + why + ") but no scan-visible live instances; redefining anyway — new instances go"
					     + " through the new constructor and initialize correctly. This is what the heap scan"
					     + " saw, not proof that no instance exists.");
					break;
				case WAIVE_REINIT:
					info("[LAYOUT-REINIT] Dynamic class " + className
					     + ": field type change authorized by @RemoldReinit (" + why + ")");
					break;
				case SKIP:
					warn("[LAYOUT-SKIP] Dynamic class " + className + ": layout gate skipped — no"
					     + " usable baseline; redefining without a field-layout check.");
					break;
				case REJECT:
				default:
					warn("[LAYOUT-REJECT] Dynamic class " + className + " rejected: " + why);
					return false;
			}
			long initFixStartNanos = System.nanoTime();
			// 单类批次上下文：没有它，InitFix 的 batchCommitManifest / batchReplayFacts 会退回空表，
			// 动态代的 manifest/facts 就是空的（"没有分析"与"分析后无决策"无法区分）。
			beginSingleClassBatch(targetClass, className, runtimeBytes);
			InitFix.transform(targetClass, runtimeBytes, diff);
			InitFix.setBatchDefinitions(Collections.singletonList(new ClassDefinition(targetClass, runtimeBytes)));
			InitFix.finishBatchTransforms();

			// 动态单类入口也接两阶段（docs/initfix/04-target-design.md §1）：调用方
			// （MixinHotSwap / ForgeMixinHotSwap 等）在返回后立即做后续处理，所以
			// A → 补丁 → C 必须在本方法内部同步跑完——返回时 JVM 已是终态，不暴露阶段 A 中间态，
			// 公开 API 的返回语义不变。twoPhase 标志由 InitFix.transform 写进 PendingPatch，
			// 这里只消费 InitFix.requiresTwoPhase；不额外跑堆扫描（批次路径才有堆 liveness 旁路优化）。
			boolean twoPhase = InitFix.requiresTwoPhase(targetClass);
			byte[]  stageABytes = null;
			if (twoPhase) {
				try {
					stageABytes = StageASkeleton.buildWithLiveConstructors(oldBytes, runtimeBytes);
				} catch (Throwable t) {
					warn("[DYNAMIC-REDEFINE] " + className + ": cannot synthesise stage A (" + t
					     + "); falling back to a single redefine — the read-through window stays open.");
					twoPhase = false;
				}
			}
			long initFixMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - initFixStartNanos);

			// 堆实例哨兵三切面（默认关闭，-Dremold.probe.heapwatch=true 开启）：
			// 用于定位「某类字段在 redefine / 字段初始化补丁窗口内被置 null」这类跨类污染。
			HeapFieldWatch.snapshot("before-redefine", className);
			enterInternalRedefine(Collections.singleton(className));
			try {
				long stageAMs = 0L;
				long stageBMs = 0L;
				long stageCMs = 0L;
				long redefMs = 0L;
				if (twoPhase) {
					// ---- 阶段 A：新结构 + 旧方法体（构造器/<clinit> 保留新体） ----
					long tStageA = System.nanoTime();
					inst.redefineClasses(new ClassDefinition(targetClass, stageABytes));
					stageAMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tStageA);
					HeapFieldWatch.snapshot("after-stage-a", className);
					// 阶段 A 已生效：分析期 oracle 看的"新结构"仍是 C，但 JVM 当前真实形态是 A，
					// 缓存先指向 A（与批次两阶段同口径）；C 成功后再推进。
					recordRuntimeBytecode(className, stageABytes);
					info("[DYNAMIC-REDEFINE] " + className + ": stage A applied (new shape with old method"
					     + " bodies); patching live instances before stage C.");
					long tStageB = System.nanoTime();
					try {
						InitFix.afterRedefine(targetClass);
					} catch (Throwable t) {
						// 与批次路径一致：补丁失败不阻止阶段 C；未补字段停在默认值并记账。
						error("[DYNAMIC-REDEFINE] " + className + ": stage B patch failed (" + t + ");"
						      + " stage C will still be applied, so unpatched fields stay at defaults.", t);
					}
					stageBMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tStageB);
					HeapFieldWatch.snapshot("after-field-patch", className);

					// ---- 阶段 C：提交现成的 runtimeBytes（桥接与 InitFix.transform 已做过，不重跑） ----
					long tStageC = System.nanoTime();
					try {
						inst.redefineClasses(new ClassDefinition(targetClass, runtimeBytes));
					} catch (Throwable stageCFailure) {
						LAST_REDEFINE_VM_LEVEL_FAILURE = isVmLevelRedefineFailure(stageCFailure);
						if (LAST_REDEFINE_VM_LEVEL_FAILURE) {
							// VM 级失败（InternalError/VerifyError）：不再对同一类做第二次 redefine。
							markVmLevelFailure(className, stageCFailure);
							error("[DYNAMIC-REDEFINE] " + className + ": stage C redefine failed at the VM level ("
							      + stageCFailure + "); the class stays at stage A (old bodies). RESTART required.",
								stageCFailure);
							return false;
						}
						boolean rolledBack = false;
						try {
							inst.redefineClasses(new ClassDefinition(targetClass, oldBytes));
							recordRuntimeBytecode(className, oldBytes);
							rolledBack = true;
						} catch (Throwable rollbackFailure) {
							recordRuntimeBytecode(className, stageABytes);
							warn("[DYNAMIC-REDEFINE] " + className + ": stage C failed and rollback to the baseline"
							     + " also failed (" + rollbackFailure + "); the class stays at stage A (old bodies,"
							     + " patched fields). RESTART required.");
						}
						error("[DYNAMIC-REDEFINE] " + className + ": stage C redefine failed (" + stageCFailure + "); "
						      + (rolledBack ? "rolled back to the baseline bytes (best effort)."
						                    : "no rollback was possible — RESTART required."), stageCFailure);
						return false;
					}
					stageCMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tStageC);
					HeapFieldWatch.snapshot("after-stage-c", className);
				} else {
					long tRedef = System.nanoTime();
					inst.redefineClasses(new ClassDefinition(targetClass, runtimeBytes));
					redefMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tRedef);
					HeapFieldWatch.snapshot("after-redefine", className);
					recordRuntimeBytecode(className, runtimeBytes);
					long tStageB = System.nanoTime();
					try {
						InitFix.afterRedefine(targetClass);
					} catch (Throwable t) {
						error("Failed to run InitFix after redefine for " + className, t);
						InitFix.afterRedefineFailed(targetClass);
					}
					stageBMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tStageB);
					HeapFieldWatch.snapshot("after-field-patch", className);
				}
				// 提交刻度：只有 redefineClasses 真的成功之后才产生一代（no-op 与失败路径都不走这里）。
				DynamicVcsCommit.commit(targetClass, oldBytes, sourceBytes, runtimeBytes, twoPhase);
				long postStartNanos = System.nanoTime();
				recordRuntimeBytecode(className, runtimeBytes);
				recordSourceBytecode(className, sourceBytes);
				recordCacheLoader(className, targetClass.getClassLoader());
				List<ClassDefinition> defs = Collections.singletonList(new ClassDefinition(targetClass, runtimeBytes));
				long annoStartNanos = System.nanoTime();
				processAnnotations(defs);
				long annoMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - annoStartNanos);
				long finishStartNanos = System.nanoTime();
				HotswapBatchReport batchReport = BatchReportScope.finish(Collections.singletonList(className));
				long finishMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - finishStartNanos);
				long listenersStartNanos = System.nanoTime();
				ListenerTiming listenerTiming = PERF_LOG ? new ListenerTiming() : null;
				notifyBatchListeners(defs, batchReport, listenerTiming);
				long listenersMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - listenersStartNanos);
				long postMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - postStartNanos);
				InternalRedefineContext hookCtx = INTERNAL_CONTEXT.get();
				int targetHooks = hookCtx != null ? hookCtx.targetHookHits : 0;
				int passthroughHooks = hookCtx != null ? hookCtx.passthroughHookHits : 0;
				String maxListenerDetail = (listenerTiming != null && listenerTiming.slowestName != null)
					? ", max=" + listenerTiming.slowestName + ":" + listenerTiming.slowestMs + "ms"
					: "";
				long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dynStartNanos);
				String timingSuffix = " (total=" + totalMs + "ms"
					+ (PERF_LOG
					   ? " [bridge=" + bridgeMs + "ms"
					     + ", align=" + alignMs + "ms [mixin=" + mixinAlignMs + "ms, access=" + accessAlignMs + "ms]"
					     + ", baseline=" + baselineMs + "ms"
					     + ", diff=" + diffMs + "ms"
					     + ", gate=" + gateMs + "ms"
					     + ", initFix=" + initFixMs + "ms"
					     + (twoPhase
					        ? ", stageA=" + stageAMs + "ms, stageB=" + stageBMs + "ms, stageC=" + stageCMs + "ms"
					        : ", redefine=" + redefMs + "ms, stageB=" + stageBMs + "ms")
					     + ", hooks=[target=" + targetHooks + ", passthrough=" + passthroughHooks + "]"
					     + ", post=" + postMs + "ms [anno=" + annoMs + "ms, finish=" + finishMs
					     + "ms, listeners=" + listenersMs + "ms" + maxListenerDetail + "]]"
					   : "")
					+ ")";
				info("[DYNAMIC-REDEFINE] Successfully redefined " + className
				     + (twoPhase ? " (two-phase: A -> patch -> C)" : "") + timingSuffix);
				return true;
			} finally {
				InitFix.endBatchBytecodeView();
				exitInternalRedefine();
			}
		} catch (Throwable t) {
			LAST_REDEFINE_VM_LEVEL_FAILURE = isVmLevelRedefineFailure(t);
			if (LAST_REDEFINE_VM_LEVEL_FAILURE) {
				markVmLevelFailure(targetClass.getName(), t);
			} else {
				error("[DYNAMIC-REDEFINE] Failed to redefine " + targetClass.getName(), t);
			}
			InitFix.afterRedefineFailed(targetClass);
			return false;
		} finally {
			BatchReportScope.discard();
			HOTSWAP_LOCK.unlock();
		}
	}

	/**
	 * 动态单类重定义的布局判决。
	 *
	 * <p>与批量路径共用 {@link LayoutDecisionEngine}，但必须显式构造"批次"：{@code targetClass}
	 * 自己就是本批唯一成员。只有这样读者扫描才会把它当作批次内类（用新字节），而不是从
	 * {@code bytecodeCache} 里拿它的旧字节当成外部读者——否则删字段永远会被自己旧 handler 挡下。</p>
	 */
	static LayoutDecision decideDynamicLayout(Class<?> targetClass, String className,
	                                                   ClassDiffUtil.ClassDiff diff, byte[] runtimeBytes) {
		if (LayoutGate.MODE_OFF.equals(LAYOUT_GATE)) {
			return new LayoutDecision(LayoutAction.PASS, null, className, Set.of());
		}
		// 与批量路径同源的继承作用域门：命中即拒绝（warn 模式仍按既有语义强制放行）。
		if (!LayoutGate.MODE_WARN.equals(LAYOUT_GATE) && diff.newClass != null
		    && !diff.reinitFields.isEmpty()) {
			Map<String, String> unsafeReinitScope =
				InitFix.unsafeInheritedReinitFields(targetClass, diff.newClass, diff.reinitFields);
			if (!unsafeReinitScope.isEmpty()) {
				return LayoutDecisionEngine.rejectUnsafeReinitScope(
					className, Set.of(className), unsafeReinitScope);
			}
		}
		Supplier<Liveness> livenessSupplier = () -> LayoutGate.checkChangedFields(diff.changedFields).compatible()
			? Liveness.LIVE
			: (hasLiveInstances(className) ? Liveness.LIVE : Liveness.NONE);

		RemovedMemberScan scan = RemovedMemberScan.NOT_SCANNED;
		Path classFile = classFilePath(targetClass);
		if (classFile != null) {
			// inBatch 必须包含 target 自己。读者扫描器据此排除"本批类"，只扫批次外其他类。
			byte[] baseline = getRuntimeBytecode(className);
			Map<String, GateCachedDiff> localCache = null;
			if (baseline != null && diff != null && diff.oldClass != null && diff.newClass != null) {
				localCache = new HashMap<>();
				localCache.put(className, new GateCachedDiff(baseline, runtimeBytes, diff, false, false));
			}
			Map<String, RemovedMemberScan> scans = RemovedMemberScanner.scan(
				Map.of(className, runtimeBytes), Map.of(className, classFile), localCache);
			scan = scans.getOrDefault(className, RemovedMemberScan.NOT_SCANNED);
		}

		SuperclassDriftGate.DriftDecision drift = checkSuperclassDriftIfApplicable(
			className, targetClass, diff.changedFields, runtimeBytes, Map.of(className, runtimeBytes));

		return decideLayout(className, diff.changedFields, LAYOUT_GATE, livenessSupplier,
			Set.of(className), scan, drift, diff.reinitFields, Map.of());
	}

	/**
	 * 由已加载类的 CodeSource 还原它在磁盘上的 .class 路径，供读者扫描反推工程根目录。
	 * 取不到（运行时生成类、无 CodeSource 等）时返回 {@code null}；调用方必须退回"未扫描"判据。
	 */
	private static Path classFilePath(Class<?> type) {
		try {
			if (type == null || type.getProtectionDomain() == null
			    || type.getProtectionDomain().getCodeSource() == null
			    || type.getProtectionDomain().getCodeSource().getLocation() == null) {
				return null;
			}
			Path root = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
			return root.resolve(type.getName().replace('.', '/') + ".class");
		} catch (Throwable t) {
			return null;
		}
	}
}
