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
import remold.ref.InitializerDrift;
import remold.ref.InitializerReplay;
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
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.*;

import static remold.MountManager.*;

import remold.RemoldAgent.AlignmentTransaction;
import remold.RemoldAgent.BytecodeTransformerBridge;
import remold.RemoldAgent.GateCachedDiff;
import remold.RemoldAgent.RedefineMode;
import remold.RemoldAgent.RedefineOutcome;
import remold.RemoldAgent.RoundSkips;
import remold.ref.PendingLoadPatch;

import static remold.RemoldAgent.ANON_ALIGN;
import static remold.RemoldAgent.BATCH_REDEFINE_MILLIS;
import static remold.RemoldAgent.BATCH_STAGE_A_MILLIS;
import static remold.RemoldAgent.BATCH_STAGE_B_MILLIS;
import static remold.RemoldAgent.BATCH_STAGE_C_MILLIS;
import static remold.RemoldAgent.BATCH_TWOPHASE_SKEL_MILLIS;
import static remold.RemoldAgent.DEBUG;
import static remold.RemoldAgent.DIFF_LOG;
import static remold.RemoldAgent.FORCE_STATIC_LAMBDA;
import static remold.RemoldAgent.HOTSWAP_BLACKLIST;
import static remold.RemoldAgent.LAMBDA_ALIGN;
import static remold.RemoldAgent.LIVENESS_BATCH;
import static remold.RemoldAgent.LIVENESS_BATCH_DONE;
import static remold.RemoldAgent.LIVE_INSTANCE_CACHE;
import static remold.RemoldAgent.LIVE_INSTANCE_SCAN_MILLIS;
import static remold.RemoldAgent.PERF_LOG;
import static remold.RemoldAgent.REDEFINE_MODE;
import static remold.RemoldAgent.TWO_PHASE;
import static remold.RemoldAgent.UCP_APPEND;
import static remold.RemoldAgent.activeWatchDirs;
import static remold.RemoldAgent.alignAccessMonotonicity;
import static remold.RemoldAgent.alignAnonClasses;
import static remold.RemoldAgent.alignMixinGeneratedMembers;
import static remold.RemoldAgent.applyLocalClassGuard;
import static remold.RemoldAgent.applyRedefineLayoutGate;
import static remold.RemoldAgent.applyRedefinitions;
import static remold.RemoldAgent.applyTransformerBridges;
import static remold.RemoldAgent.applyTwoPhase;
import static remold.RemoldAgent.batchWatchLabel;
import static remold.RemoldAgent.beginSourceRound;
import static remold.RemoldAgent.calculateHash;
import static remold.RemoldAgent.commitVcsBatch;
import static remold.RemoldAgent.enterInternalRetransform;
import static remold.RemoldAgent.error;
import static remold.RemoldAgent.exitInternalRedefine;
import static remold.RemoldAgent.fileDiskHashes;
import static remold.RemoldAgent.getRuntimeBytecode;
import static remold.RemoldAgent.getSourceBytecode;
import static remold.RemoldAgent.handleAnnotationDefaultChangeInBatch;
import static remold.RemoldAgent.hasRuntimeBytecode;
import static remold.RemoldAgent.info;
import static remold.RemoldAgent.init;
import static remold.RemoldAgent.injectNewClass;
import static remold.RemoldAgent.inst;
import static remold.RemoldAgent.inventedSlots;
import static remold.RemoldAgent.isEnhancedHotswapEnabled;
import static remold.RemoldAgent.isNonLoadableClass;
import static remold.RemoldAgent.loadClassSnap;
import static remold.RemoldAgent.loadedClassesMap;
import static remold.RemoldAgent.log;
import static remold.RemoldAgent.mustSkipForMissingEnhancedRedefinition;
import static remold.RemoldAgent.normalizeAlignBaseline;
import static remold.RemoldAgent.normalizeAlignResolver;
import static remold.RemoldAgent.notifyBatchListeners;
import static remold.RemoldAgent.pendingChanges;
import static remold.RemoldAgent.pendingInvented;
import static remold.RemoldAgent.postToMain;
import static remold.RemoldAgent.recordRuntimeBytecode;
import static remold.RemoldAgent.recordSourceBytecode;
import static remold.RemoldAgent.rejectHostGroup;
import static remold.RemoldAgent.rollbackSourceCache;
import static remold.RemoldAgent.runtimeBytecodeEntries;
import static remold.RemoldAgent.transformerBridges;
import static remold.RemoldAgent.warn;

/**
 * 批量热更主体执行器（从 {@link RemoldAgent} 拆出）。
 */
final class BatchHotswapExecutor {

	private BatchHotswapExecutor() {}

	/**
	 * 处理文件变化的核心逻辑（批次主体；看门狗由 {@link #processChanges} 负责登记/注销）。
	 */
	static void processChangesBatch(Set<Path> changedFiles, Class<?>[] classes) {
		final long batchStartNanos = System.nanoTime();
		// 新批次开始：上一批未被 @OnReload 消费的实例引用不再需要，立即释放。
		StageBInstanceCache.clear();
		// 批次可观测数据的起点：从读盘、对齐、织入、布局门、redefine 到 InitFix 全部计入。
		// 收集器挂在当前线程上；即使没有任何监听器，这里也只多一次小对象分配，不改变批次行为。
		BatchReportScope.begin();
		resetLiveInstanceCache();
		long pkgStartNanos = PERF_LOG ? System.nanoTime() : 0L;
		refreshPackageLoaders(classes);
		long snapStartNanos = PERF_LOG ? System.nanoTime() : 0L;

		// 获取当前所有已加载类的快照
		loadClassSnap(classes);
		long ioStartNanos = PERF_LOG ? System.nanoTime() : 0L;

		List<ClassDefinition> definitions = new ArrayList<>();
		// List<Class<?>>        unloadedClasses  = new ArrayList<>();
		int skippedCount  = 0;
		int injectedCount = 0;
		// 本轮"作为新类定义"的类名（此前未加载）：它们不参与 Stage A 重定义，也不得被一致性校验回滚。
		Set<String> injectedClasses = new HashSet<>();
		// 两阶段需要"补丁分析使用的阶段 C 字节"与"JVM 当前生效的旧字节"两份输入。
		Map<String, byte[]> oldEffectiveBytes = new HashMap<>();
		boolean             needsTwoPhase     = false;

		Map<String, byte[]> newBatchBytes = new LinkedHashMap<>(changedFiles.size());
		Map<String, Path>   classToPath   = new HashMap<>(changedFiles.size());
		// 磁盘原始字节的指纹：只由 Files.readAllBytes 的结果计算，绝不含对齐/织入后的改写。
		Map<String, Long>   rawDiskHashes  = new HashMap<>(changedFiles.size());
		// 目标类名 -> 它在磁盘上的原始类名（匿名类重命名后两者不同）。
		Map<String, String> targetToRawName = new HashMap<>(changedFiles.size());
		// 本轮"改了但没生效"的台账：摘要行据此点名跳过了谁（见 RoundSkips）。
		RoundSkips skips = new RoundSkips();
		// 旁路记录（PR1）：批次开始先清暂存，避免上一批未提交项串到本批。
		VcsAgentCoordinator.PENDING_VCS.clear();
		boolean lambdaStrictRejected = false;
		// 本轮因注解默认值变化而整体作废的类（非 null = 事务共退：本批一个类都不重定义）。
		String annotationDefaultAbort = null;
		for (Path p : changedFiles) {
			try {
				byte[] bc = Files.readAllBytes(p);
				String cn = Utils.getClassNameASM(bc);
				if (cn != null) {
					Path existingPath = classToPath.get(cn);
					if (existingPath != null) {
						try {
							FileTime curTime = Files.getLastModifiedTime(p);
							FileTime oldTime = Files.getLastModifiedTime(existingPath);
							if (curTime.compareTo(oldTime) <= 0) {
								if (DEBUG) log("[TIMESTAMP-SKIP] Skipping older class file " + p + " for " + cn + " (existing " + existingPath + " is newer)");
								continue;
							}
						} catch (Throwable ignored) {
						}
					}
					newBatchBytes.put(cn, bc);
					classToPath.put(cn, p);
					rawDiskHashes.put(cn, calculateHash(bc));
					targetToRawName.put(cn, cn);
				} else {
					// 不并进 skippedCount：那个计数只统计"磁盘哈希没变"的类，含义是"本来就没改"。
					// 读不出类名是"改了但读不了"，属于跳过，进台账由摘要点名。
					skips.add(p.toString(), RoundSkips.verdict("NO-CLASS-NAME",
						"unreadable class file", "recompile it and hot-swap again"));
					error("[SKIP] No className: " + p);
				}
			} catch (Throwable ignored) { }
		}

		// 批次匿名类按**类自身**归属分组：一个类只归一个宿主。不能拿"宿主前缀"去过滤整个批次——
		// 那会让某个宿主的对齐周期看到并移走别的宿主的类（跨宿主误归属 / 误移出）。
		Map<String, Map<String, byte[]>> newAnonsByHost = AnonClassAligner.groupBatchAnons(newBatchBytes);

		// 收集需要对齐匿名类的所有宿主类：宿主自身变了，或它的匿名类变了
		Set<String> hostClassesToAlign = new LinkedHashSet<>();
		for (Map.Entry<String, byte[]> entry : newBatchBytes.entrySet()) {
			String cn = entry.getKey();
			long newHash = calculateHash(entry.getValue());
			long cnKey = CRC64.hashString(cn);
			boolean changedOnDisk;
			synchronized (fileDiskHashes) {
				long oldHash = fileDiskHashes.get(cnKey);
				changedOnDisk = (oldHash == -1 || oldHash == LongLongMap.NOT_FOUND || oldHash != newHash);
			}
			if (!changedOnDisk) continue;
			String host = AnonClassAligner.batchAnonHost(cn);
			if (host != null && !host.equals(cn) && AnonClassAligner.isBatchAnonClass(cn, entry.getValue())) {
				hostClassesToAlign.add(host);        // 匿名类变了：宿主跟着对齐
			} else {
				hostClassesToAlign.add(cn);          // 宿主自己变了：它的匿名类要跟着对齐
			}
		}

		// 记录所有对齐过程中产生的旧孤儿类（不得在本次重定义中被误更新）
		Set<String>                       allOrphanClasses = new HashSet<>();
		Map<String, AlignmentTransaction> transactions     = new LinkedHashMap<>();

		// 【指纹延后落账】类名 hash → 新磁盘指纹。循环里只登记，不写 fileDiskHashes；
		// 待 applyRedefinitions 完成后，仅对**确实成功**的类落账（见本方法末尾）。
		// 这样失败/被拒的类下次触发时不会被误判为 "file hash unchanged" 而跳过。
		LongLongMap pendingHashes = new LongLongMap(256);
		// 【失败回队】类名 → 源文件路径，用于重定义失败后把文件放回 pendingChanges。
		// 注意：hierarchyChanged 的拒绝**不**回队 —— 那种变更 JVM 层面不支持，重试无意义，
		// 必须重启；回队只会造成"每次触发都失败一次"的噪音。
		Map<String, Path> retryCandidates = new HashMap<>();

		long anonStartNanos = System.nanoTime();
		long pkgMs = PERF_LOG ? TimeUnit.NANOSECONDS.toMillis(snapStartNanos - pkgStartNanos) : 0L;
		long snapMs = PERF_LOG ? TimeUnit.NANOSECONDS.toMillis(ioStartNanos - snapStartNanos) : 0L;
		long ioMs = PERF_LOG ? TimeUnit.NANOSECONDS.toMillis(anonStartNanos - ioStartNanos) : 0L;
		long prepMs = PERF_LOG ? TimeUnit.NANOSECONDS.toMillis(anonStartNanos - batchStartNanos) : 0L;
		// 单遍按宿主分桶旧匿名类，避免在 hostClassesToAlign 外层循环里对全量 loadedClassesMap / bytecodeCache 做 O(H * N) 扫描与 ASM 解析。
		String singleAlignHost = hostClassesToAlign.size() == 1 ? hostClassesToAlign.iterator().next() : null;
		if (!hostClassesToAlign.isEmpty()) {
			for (Map.Entry<String, Class<?>> entry : loadedClassesMap.entrySet()) {
				String cName = entry.getKey();
				String host = resolveAnonHostCandidate(cName, hostClassesToAlign, singleAlignHost);
				if (host == null) continue;
				if (!AnonClassAligner.isAnonymousClassName(host, cName)) continue;
				if (hasRuntimeBytecode(cName)) continue;
				byte[] bc = fetchOriginalBytecode(entry.getValue());
				if (bc != null && AnonClassAligner.isBatchAnonClass(cName, bc)) recordRuntimeBytecode(cName, bc);
			}
		}
		Map<String, Map<String, byte[]>> oldAnonsByHost = new HashMap<>();
		if (!hostClassesToAlign.isEmpty()) {
			for (Map.Entry<String, byte[]> entry : runtimeBytecodeEntries()) {
				String cName = entry.getKey();
				String host = resolveAnonHostCandidate(cName, hostClassesToAlign, singleAlignHost);
				if (host == null) continue;
				byte[] src = getSourceBytecode(cName);
				byte[] oldBytes = src != null ? src : entry.getValue();
				if (!AnonClassAligner.isBatchAnonClass(cName, oldBytes)) continue;
				oldAnonsByHost.computeIfAbsent(host, k -> new HashMap<>()).put(cName, oldBytes);
			}
		}

		for (String hostName : hostClassesToAlign) {
			String hostSlash = hostName.replace('.', '/');
			Map<String, byte[]> oldAnon = new HashMap<>(
				oldAnonsByHost.getOrDefault(hostName, Collections.emptyMap()));

			// 只取**本宿主**的匿名类（按类自身归属分组，绝不拿"本宿主前缀"去扫整批）
			Map<String, byte[]> newAnon = new HashMap<>(
				newAnonsByHost.getOrDefault(hostName, Collections.emptyMap()));

			if (oldAnon.isEmpty() && newAnon.isEmpty()) {
				continue;
			}

			if (!ANON_ALIGN) {
				// 总开关关闭：**不能**退化成"按类名照旧重定义" —— 那正是编号位移篡夺场景本身。
				// 唯一安全的关闭语义是把该宿主组整体移出本批重定义。
				rejectHostGroup(newBatchBytes, classToPath, hostName, skips,
				 new AnonClassAligner.AlignmentRejectedException(hostSlash,
					"anonymous class alignment disabled (remold.agent.anon_align=false)"));
				continue;
			}

			AlignmentTransaction tx = new AlignmentTransaction(hostName);
			for (Map.Entry<String, byte[]> entry : oldAnon.entrySet()) {
				tx.pinOldBytes.put(entry.getKey(), entry.getValue());
			}

			byte[] hostBytes = newBatchBytes.get(hostName);
			if (DEBUG) {
				log("[ANON_ALIGN] Aligning anonymous classes for host: " + hostName + " (old=" + oldAnon.size() + ", new=" + newAnon.size() + ")");
			}

			Function<String, byte[]> oldRes = name -> {
				String dot = name.replace('/', '.');
				byte[] src = getSourceBytecode(dot);
				return src != null ? src : getRuntimeBytecode(dot);
			};
			Function<String, byte[]> newRes = name -> newBatchBytes.get(name.replace('/', '.'));

			AnonClassAligner.Result res;
			try {
				res = alignAnonClasses(hostSlash, hostBytes, oldAnon, newAnon, oldRes, newRes,
				 BatchHotswapExecutor::hasLiveInstances,
				 // 避让上一批已分配、但还没被加载的对齐类编号（挂在 pendingAlignedClasses 里）。
				 // 传**快照**而非 keySet() 实时视图：视图会让编号分配随挂起集合的并发变化漂移，
				 // 破坏对齐结果的可确定性。
				 Set.copyOf(AnnotationTransformer.pendingAlignedClasses.keySet()));
			} catch (Throwable t) {
				// docs/topology/04-tiers-and-rejection.md §4：以「宿主类 + 其下属全部匿名类」为原子单元整体拒绝。
				//
				// 这里**必须** continue 而不能让异常冒泡：冒泡会让整个 processChanges 中断，
				// 结果是"本轮所有类的热更静默失效"（异常最终只留在 ScheduledFuture 里，无人观测）；
				// 而对齐失败也不能只跳过匿名类 —— 该宿主的新字节码会引用到未对齐的 Foo$N。
				// 因此正确做法是连宿主一起移出本批，其余宿主照常处理。
				rejectHostGroup(newBatchBytes, classToPath, hostName, skips, t);
				continue;
			}

			if (res.alignedHostBytes != null) {
				newBatchBytes.put(hostName, res.alignedHostBytes);
			}
			// 对齐重命名后，目标类名对应的磁盘原始类名可能不同；指纹必须跟随原始磁盘文件。
			for (Map.Entry<String, String> rename : res.renameMap.entrySet()) {
				String rawDot    = rename.getKey().replace('/', '.');
				String targetDot = rename.getValue().replace('/', '.');
				targetToRawName.put(targetDot, rawDot);
			}

			for (String orphan : res.orphanOldClasses) {
				allOrphanClasses.add(orphan.replace('/', '.'));
			}

			// 真匿名类不得按原名重定义（编号位移会篡夺槽位），所以未对齐的一律移出本批；
			// 但"移出"必须可观测：对齐器没消费的类要进跳过台账并点名 warn（S22 曾在此静默丢弃）。
			for (String originalAnonName : newAnon.keySet()) {
				String rawSlash = originalAnonName.replace('.', '/');
				boolean consumed = res.renameMap.containsKey(rawSlash)
					|| res.alignedAnonClasses.containsKey(rawSlash);
				newBatchBytes.remove(originalAnonName);
				if (!consumed) {
					Path anonPath = classToPath.get(originalAnonName);
					skips.add(anonPath == null ? originalAnonName : anonPath.toString(),
						RoundSkips.verdict("ANON-NOT-ALIGNED",
							"anonymous class was not consumed by host alignment", "recompile it and hot-swap again"));
					warn("[ANON_ALIGN] " + originalAnonName + " was not aligned under host " + hostName
						+ "; it is kept out of this batch");
				}
			}

			// 将对齐重命名后的新匿名类注入批次并登记到事务
			Path hostPath = classToPath.get(hostName);
			for (Map.Entry<String, byte[]> entry : res.alignedAnonClasses.entrySet()) {
				String targetSlash  = entry.getKey();
				String targetDot    = targetSlash.replace('/', '.');
				byte[] alignedBytes = entry.getValue();

				newBatchBytes.put(targetDot, alignedBytes);
				if (hostPath != null && !classToPath.containsKey(targetDot)) {
					classToPath.put(targetDot, hostPath);
				}

				tx.pendingAdds.put(targetSlash, alignedBytes);
				tx.cacheUpdates.put(targetDot, alignedBytes);
				tx.targetClasses.add(targetDot);
			}
			transactions.put(hostName, tx);
		}

		// 记录源码级对齐基线
		//
		// 注意：这里写入的是**本轮新字节**，而同一方法里的 lambda 对齐（下面约 150 行后）
		// 需要的是**上一轮的源码基线**。所以先把旧值快照下来，供本轮对齐读取；
		// 否则 align(old == new) 会静默地什么都不做（实测见 docs/initfix/08-test-audit.md §10.5）。
		Map<String, byte[]> prevSourceBytes = new HashMap<>();
		Map<String, byte[]> sourceBefore = new HashMap<>();
		Map<String, byte[]> driftOldBytes = new HashMap<>();
		Set<String> loadedBefore = new HashSet<>(loadedClassesMap.keySet());
		for (String key : newBatchBytes.keySet()) {
			byte[] prev = getSourceBytecode(key);
			sourceBefore.put(key, prev);
			driftOldBytes.put(key, getRuntimeBytecode(key));
			if (prev != null) prevSourceBytes.put(key, prev);
		}
		// 风险 16（并发语义窗口）：本轮源码基线不再直接写已提交视图，而是进入按轮次的 staged 容器。
		// 从这一行到下面的 rollbackSourceCache 之间，任何外部 redefine/retransform 触发的读方
		// （no-op 判定、对齐旧侧、Mixin 兜底）只能读到上一版已生效字节，不会读到本轮候选字节。
		beginSourceRound();
		for (Map.Entry<String, byte[]> e : newBatchBytes.entrySet()) {
			recordSourceBytecode(e.getKey(), e.getValue());
		}

		// ---- 字节码重织入桥（Mixin / AspectJ / 自定义字节码增强器）----
		// 位置：源码级对齐之后、局部类门与布局门之前。
		// 保证对齐器看到的是纯净源码，而布局门与 JVM redefine 看到的是织入后的物理结构。
		long bridgeStartNanos = PERF_LOG ? System.nanoTime() : 0L;
		if (!transformerBridges.isEmpty()) {
			for (String className : new ArrayList<>(newBatchBytes.keySet())) {
				byte[] rawBytes = newBatchBytes.get(className);
				if (rawBytes == null) continue;
				Class<?> targetClass = loadedClassesMap.get(className);
				ClassLoader cl = targetClass != null ? targetClass.getClassLoader() : findTargetClassLoader(className, classToPath.get(className));
				byte[] weaved;
				try {
					weaved = applyTransformerBridges(cl, className, targetClass, rawBytes);
				} catch (BytecodeTransformerBridge.Abort abort) {
					// 事务一致性（单类粒度）：该类本轮整体移出批次，既不 redefine 织入后的新字节，
					// 也不退回未织入的原始字节。宿主与它关联的类一起保持已加载旧版。
					newBatchBytes.remove(className);
					classToPath.remove(className);
					skips.add(className, RoundSkips.verdict("BRIDGE-TRANSACTION-ABORT",
						"bytecode bridge refused this class for the whole round: " + abort.getMessage(),
						"nothing was redefined for this class; the next batch retries"));
					if (targetClass != null) InitFix.afterRedefineFailed(targetClass);
					continue;
				}
				weaved = alignMixinGeneratedMembers(className, targetClass, weaved);
				if (weaved != null && weaved != rawBytes) {
					newBatchBytes.put(className, weaved);
					for (AlignmentTransaction tx : transactions.values()) {
						if (tx.cacheUpdates.containsKey(className)) {
							tx.cacheUpdates.put(className, weaved);
						}
						String slash = className.replace('.', '/');
						if (tx.pendingAdds.containsKey(slash)) {
							tx.pendingAdds.put(slash, weaved);
						}
					}
				}
			}
		}

		// ---- 访问权限单调对齐（Access Monotonicity Alignment）----
		// 桥接产物可能来自原始 jar/磁盘字节，丢掉宿主加载器在 Mixin 之前做的访问变换；
		// 一旦以更窄权限送回 JVM，JBR 增强重定义会把子类一并 link/verify，跨包 protected 访问
		// 会当场 VerifyError -> InternalError。这里按已加载运行时字节码单调对齐，并在自检门拒绝失败项。
		for (String className : new ArrayList<>(newBatchBytes.keySet())) {
			byte[] rawBytes = newBatchBytes.get(className);
			if (rawBytes == null) continue;
			Class<?> targetClass = loadedClassesMap.get(className);
			byte[] aligned = alignAccessMonotonicity(className, targetClass, rawBytes);
			if (aligned != null) {
				if (aligned != rawBytes) newBatchBytes.put(className, aligned);
				continue;
			}
			newBatchBytes.remove(className);
			classToPath.remove(className);
			skips.add(className, RoundSkips.verdict("ACCESS-GATE-REFUSED",
				"member access narrowed after re-weave",
				"refused before redefineClasses; see [ACCESS-GATE]"));
			if (targetClass != null) InitFix.afterRedefineFailed(targetClass);
		}

		// 插件在桥接阶段会把 Mixin 等模板类登记为"不可加载"。它们不能进入本批：
		// 既不能被当作普通新类 defineClass，也不能被当作已加载类 redefine，更不能参与读者扫描。
		newBatchBytes.keySet().removeIf(RemoldAgent::isNonLoadableClass);

		long anonEndNanos = System.nanoTime();
		long anonMs = TimeUnit.NANOSECONDS.toMillis(anonEndNanos - anonStartNanos);
		long anonAlignMs = PERF_LOG ? TimeUnit.NANOSECONDS.toMillis(bridgeStartNanos - anonStartNanos) : 0L;
		long bridgeMs = PERF_LOG ? TimeUnit.NANOSECONDS.toMillis(anonEndNanos - bridgeStartNanos) : 0L;

		Map<ClassLoader, Map<String, byte[]>> batchView = new IdentityHashMap<>();
		for (Map.Entry<String, byte[]> entry : newBatchBytes.entrySet()) {
			Class<?> loaded = loadedClassesMap.get(entry.getKey());
			ClassLoader loader = loaded != null ? loaded.getClassLoader()
				: findTargetClassLoader(entry.getKey(), classToPath.get(entry.getKey()));
			batchView.computeIfAbsent(loader, ignored -> new LinkedHashMap<>()).put(entry.getKey(), entry.getValue());
		}
		InitFix.beginBatchBytecodeView(batchView);

		// ---- 同名局部类编号漂移止血门（docs/topology/07-layout-gate-and-risks.md §2）----
		//
		// 位置：宿主匿名对齐之后、实例布局门之前。局部类不在匿名对齐视野内（名字含简单名，被准入闸门
		// 拒绝），故单开一道门。命中即把宿主及其 `host$...` 整族移出本批，复用同一宿主组拒绝通道。
		long guardStartNanos = System.nanoTime();
		applyLocalClassGuard(newBatchBytes, classToPath, transactions, skips);
		long guardMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - guardStartNanos);

		// ---- 实例状态布局安全门（docs/topology/07-layout-gate-and-risks.md §1，全体类，非仅匿名类）----
		//
		// 位置：宿主对齐之后、逐类 redefine 之前。这样具名类与匿名类一起被覆盖，
		// 且被拒的宿主组可以干净地复用 docs/topology/04-tiers-and-rejection.md §4 的"宿主 + 下属匿名类整组移出本批"通道
		// （`rejectHostGroup` / `dropHostGroup`）。
		//
		// 分工：对齐器的 Tier 4 门只挡匿名类的**合成捕获字段**（ClassDiff 会过滤掉它们，
		// 这里看不见）；本门只看现成的 `ClassDiff.changedFields`，即**非合成**字段的
		// 删/改类型/改静态性。纯新增放行（交给 InitFix）。
		long gateStartNanos = System.nanoTime();
		Map<String, GateCachedDiff> gateDiffCache = new HashMap<>();
		long[] readersMsOut = new long[1];
		applyRedefineLayoutGate(newBatchBytes, classToPath, transactions, skips, gateDiffCache, readersMsOut);
		long readersMs = readersMsOut[0];
		long gateMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - gateStartNanos) - readersMs);

		long alignDiffStartNanos = System.nanoTime();
		long fetchNanos = 0L;
		long lambdaNanos = 0L;
		long diffNanos = 0L;
		long annDefNanos = 0L;
		long initFixNanos = 0L;
		// 同批内复用按 ClassLoader 分桶的 normalizeAlignBaseline 缓存，避免对同一关联类反复展开 frames 重算。
		Map<ClassLoader, Map<String, byte[]>> normOldByLoader = new HashMap<>();
		Map<ClassLoader, Map<String, byte[]>> normNewByLoader = new HashMap<>();

		for (Map.Entry<String, byte[]> batchEntry : newBatchBytes.entrySet()) {
			String className = batchEntry.getKey();
			byte[] bytecode  = batchEntry.getValue();
			Path   path      = classToPath.get(className);
			if (DEBUG) log("Processing changes: " + (path != null ? path : className));

			if (isNonLoadableClass(className)) {
				if (DEBUG) log("[SKIP-TEMPLATE] " + className + " is a non-loadable template; skipped");
				continue;
			}

			if (allOrphanClasses.contains(className)) {
				if (DEBUG) log("[ORPHAN-RETAIN] Retaining orphan class: " + className);
				continue;
			}

			try {
				if (isBlacklisted(className)) {
					if (DEBUG) log("[SKIP-BLACKLIST] " + className);
					skips.add(className, RoundSkips.verdict("BLACKLIST",
						"matches remold.agent.hotswap.blacklist", "drop the prefix or restart"));
					continue;
				}

				// 指纹取磁盘原始字节：对齐/织入后的 bytecode 不能参与“磁盘是否变化”的判定。
				String rawName    = targetToRawName.getOrDefault(className, className);
				Long   rawHash    = rawDiskHashes.get(rawName);
				long   newHash    = rawHash != null ? rawHash : calculateHash(bytecode);
				long   classNameKey = CRC64.hashString(rawName);

				// 检查指纹
				synchronized (fileDiskHashes) {
					long oldDiskHash = fileDiskHashes.get(classNameKey);
					if (oldDiskHash != -1 && oldDiskHash != LongLongMap.NOT_FOUND && oldDiskHash == newHash) {
						if (DEBUG) log("[SKIP] " + className + " (file hash unchanged)");
						skippedCount++;
						continue;
					}
					// 【不在此处落账】指纹必须等到该类**确实重定义成功**后才记录。
					//
					// 旧实现把 put 放在这里（重定义之前），于是以下两条"没生效"的路径
					// 也会留下已记账的指纹，导致下一次触发被判为 "file hash unchanged" 而跳过 ——
					// 文件明明改了，却永远不再尝试重试：
					//   • hierarchyChanged 的 continue（本方法下方）—— 需重启，属于已知拒绝；
					//   • applyRedefinitions 批量/单类失败 —— 本轮没生效，但指纹已写。
					// 因此只在这里登记"待落账"，真正的 put 由 applyRedefinitions 之后按成功集合执行。
					pendingHashes.put(classNameKey, newHash);
				}

				Class<?> targetClass = loadedClassesMap.get(className);

				if (targetClass != null) {
					// 过滤 JVM 不可修改类（如 Hidden Class、VM 内部动态代理类等）
					if (inst != null && !inst.isModifiableClass(targetClass)) {
						if (DEBUG) log("[SKIP-UNMODIFIABLE] " + className + " is not modifiable by JVM.");
						skips.add(className, RoundSkips.verdict("NOT-MODIFIABLE",
							"hidden / VM-generated class the JVM refuses to redefine", "restart"));
						continue;
					}
					// 类已加载：无论模式，都必须执行 redefinition
					if (DEBUG) log("[MODIFIED] " + className);


					byte[] newBytecode = bytecode;
					byte[] oldBytecode = getRuntimeBytecode(className);

					// 如果缓存里没有，主动触发一次 retransform 来"偷"取字节码
					if (oldBytecode == null) {
						long t0 = System.nanoTime();
						oldBytecode = fetchOriginalBytecode(targetClass);
						fetchNanos += System.nanoTime() - t0;
						if (oldBytecode != null) {
							recordRuntimeBytecode(className, oldBytecode);
						}
					}

					// 执行 ASM Diff
					if (oldBytecode != null) {
						long lambdaT0 = System.nanoTime();
						if (LAMBDA_ALIGN) {
							boolean hasOldLambdas = LambdaAligner.hasPotentialLambdas(oldBytecode)
								|| LambdaAligner.hasPotentialLambdas(prevSourceBytes.get(className));
							boolean hasNewLambdas = LambdaAligner.hasPotentialLambdas(newBytecode);
							boolean hasInvented = inventedSlots.containsKey(className)
								&& !inventedSlots.get(className).isEmpty();
							if (hasOldLambdas || hasNewLambdas || hasInvented) {
								// 【基线一致性】oldBytecode 来自 bytecodeCache，是"上一次 transform 之后
								// JVM 里实际生效"的形态（若开了 FORCE_STATIC_LAMBDA，则其中的 lambda 已被
								// forceStaticLambdas 转成"静态 + this 显式首参数"）。而 newBytecode 是刚
								// 从磁盘读到的原始编译产物，还是"实例 lambda"。两者形态不一致时，
								// LambdaAligner 会把同一个 lambda 判成"删除 + 新增"，生成
								// lambda$build$21 这类避障名，随后 redefine 触发的 transform 又会把新类
								// 强制成静态，于是 JVM 里仍持有老 CallSite 的 UI 监听器直接
								// NoSuchMethodError。这里让 newBytecode 先过一遍同样的归一化，
								// 保证比对双方是同一种形态。该方法幂等，即便随后的 transform 再跑一次
								// 也不会二次前置 this。
								final ClassLoader alignLoader = targetClass.getClassLoader();
								final String alignSlash = className.replace('.', '/');
								if (FORCE_STATIC_LAMBDA) {
									newBytecode = AnnotationTransformer.forceStaticLambdas(
									 newBytecode, alignSlash, alignLoader);
								}
								// 【两侧基线必须同形态】newBytecode 上面刚归一化过，旧侧也必须归一化。
								// sourceBytecodeCache 在批量路径上存的是**磁盘原始字节码**（实例 lambda），
								// 而 JVM 里实际生效的 bytecodeCache 才是归一化后的形态。若旧侧直接用
								// sourceBytecodeCache，LambdaAligner 会因 desc 不同把同一个 lambda 判成
								// "删除 + 新增"：复活出的幽灵空壳沿用未归一化的签名（例如
								// lambda$buildTitle$5(ImageButton)V），而老 CallSite 持有的是归一化后的
								// 签名（lambda$buildTitle$5(Window, ImageButton)V）——redefine 之后直接
								// NoSuchMethodError。原 lambda 还会被重复幽灵化出一份同名异型的空壳。
								Function<String, byte[]> oldResolver =
								 normalizeAlignResolver(name -> {
									byte[] src = prevSourceBytes.get(name.replace('/', '.'));
									if (src == null) src = getSourceBytecode(name.replace('/', '.'));
									return src != null ? src : getRuntimeBytecode(name.replace('/', '.'));
								 }, alignLoader, normOldByLoader.computeIfAbsent(alignLoader, k -> new HashMap<>()));
								Function<String, byte[]> newResolver =
								 normalizeAlignResolver(name -> newBatchBytes.get(name.replace('/', '.')), alignLoader,
								                        normNewByLoader.computeIfAbsent(alignLoader, k -> new HashMap<>()));
								byte[] oldSrc = prevSourceBytes.get(className);
								// 基线优先用 JVM 里**实际生效**的那份（bytecodeCache，含上一轮注入的孤儿桩）：
								// 源码基线不含桩，多轮序列下会与运行期真实形态脱节（rs_revive 实测：
								// 删 A2 再加回，第二轮 A2 槽位串到 A3、A3 直接 NoSuchMethodError）。
								byte[] alignOld = normalizeAlignBaseline(
								 oldBytecode != null ? oldBytecode : oldSrc, alignSlash, alignLoader);
								// PR1 旁路：Raw 阶段的定义 = 进入 align() 之前的待对齐产物
								// （已过前置归一化 forceStaticLambdas 等变换，但不是磁盘原始字节）。
								byte[] preAlignBytes = newBytecode;
								// 只在"运行期基线确实变了、但选出来的对齐基线却等于新字节"时才告警：
								// IDE 重复构建、字节码没变时 oldBytecode 也等于 newBytecode，那属于正常情况。
								if (Arrays.equals(alignOld, newBytecode)
									&& oldBytecode != null && !Arrays.equals(oldBytecode, newBytecode)) {
									warn("[LambdaAligner] baseline == new bytes for " + className
										+ " while the loaded class differs — alignment had nothing to align"
										+ " (stale source cache?). Old callsites will bind by raw javac numbering this round.");
								}
								try {
									// loader 维度必须显式传真实类加载器：AlignmentMetaStore 按 loader 身份分槽，
									// 传 null 只会命中 bootstrap 槽，应用类加载器下的类永远取不到元数据
									// （历史缺陷：开关生效但读数不变，见 docs/vcs/03-live-raw-to-raw-alignment.md §9 F1）。
									newBytecode = LambdaAligner.align(alignOld, newBytecode, oldResolver, newResolver,
									 inventedSlots.getOrDefault(className, Set.of()), alignLoader);
									// 本轮算出的"自造名"先挂起，等 redefine 成功再落账（失败就丢弃，
									// 因为旧基线没前进，下一轮仍然是同一份旧字节）。
									// 从本轮快照里取，避免读到别的轮次（失败时快照的集合是空的，不是 null）。
									LambdaAligner.AlignmentResult aligned = LambdaAligner.LAST_RESULT;
									pendingInvented.put(className,
									 aligned == null ? Set.of() : aligned.inventedNames);
									if (Boolean.getBoolean("remold.vcs.enabled")) {
										VcsAgentCoordinator.PENDING_VCS.put(className, new VcsAgentCoordinator.VcsPending(alignLoader, alignOld,
											preAlignBytes, newBytecode, aligned));
									}
								} catch (LambdaAligner.LambdaAlignmentStrictException e) {
									lambdaNanos += System.nanoTime() - lambdaT0;
									lambdaStrictRejected = true;
									error("REJECTED: " + e.getMessage() + ". Please recompile with '-g' or restart.");
									AlignmentTransaction tx = transactions.get(className);
									if (tx != null) {
										tx.rollback(true);
										transactions.remove(className);
									}
									skips.add(className, RoundSkips.verdict("LAMBDA-STRICT-REJECT",
										"captured lambda missing LocalVariableTable (compiled without -g)",
										"recompile with '-g' or restart"));
									continue;
								}
							}
						}
						lambdaNanos += System.nanoTime() - lambdaT0;
						// 没有 lambdas 的类不走对齐分支，但同样需要提交刻度：否则它既进不了 Changeset，
						// 也无法被区间回退（B1 的回退集合按 Changeset 的 entries 计算）。
						if (LAMBDA_ALIGN && Boolean.getBoolean("remold.vcs.enabled")
						    && !VcsAgentCoordinator.PENDING_VCS.containsKey(className)) {
							VcsAgentCoordinator.PENDING_VCS.put(className, new VcsAgentCoordinator.VcsPending(targetClass.getClassLoader(), oldBytecode,
							 newBytecode, newBytecode, null));
						}
					}
					if (oldBytecode != null) {
						long diffT0 = System.nanoTime();
						GateCachedDiff gateCached = gateDiffCache.get(className);
						boolean needFullMethodHashes = DIFF_LOG || DEBUG;
						ClassDiffUtil.ClassDiff diff;
						boolean fullNodesLoaded;
						if (gateCached != null
						    && gateCached.oldBytes() == oldBytecode
						    && gateCached.newBytes() == newBytecode
						    && (!needFullMethodHashes || gateCached.bodyDiffComputed())) {
							diff = gateCached.diff();
							fullNodesLoaded = gateCached.fullNodesLoaded();
						} else {
							diff = needFullMethodHashes
								? ClassDiffUtil.diff(oldBytecode, newBytecode)
								: ClassDiffUtil.diffStructural(oldBytecode, newBytecode);
							fullNodesLoaded = needFullMethodHashes;
						}
						if (needFullMethodHashes) {
							ClassDiffUtil.logDiff(className, diff);
						}
						diffNanos += System.nanoTime() - diffT0;
						long annDefT0 = System.nanoTime();
						String annotationDefaultAbortClass =
							handleAnnotationDefaultChangeInBatch(className, oldBytecode, newBytecode,
								transactions, definitions, oldEffectiveBytes, retryCandidates, skips);
						annDefNanos += System.nanoTime() - annDefT0;
						if (annotationDefaultAbortClass != null) {
							annotationDefaultAbort = annotationDefaultAbortClass;
							continue;
						}
						if (ClassDiffUtil.mustRejectHierarchyChange(diff, isEnhancedHotswapEnabled())) {
							error("REJECTED: Class hierarchy change detected for " + className + " ("
								+ ClassDiffUtil.describeHierarchyChange(diff) + ")."
								+ (diff.superChanged
									? " Changing the superclass is not supported by any redefinition mode. Please RESTART application."
									: " Interface changes require enhanced class redefinition (JBR/DCEVM with"
										+ " -XX:+AllowEnhancedClassRedefinition); this JVM has none. Please RESTART application."));
							AlignmentTransaction tx = transactions.get(className);
							if (tx != null) {
								tx.rollback(true);
								transactions.remove(className);
							}
							// 直接跳过该类的重定义，避免抛出 UnsupportedOperationException
							skips.add(className, RoundSkips.verdict("HIERARCHY-CHANGED",
								"superclass / interfaces changed",
								"restart — the JVM cannot apply this"));
							continue;
						}

						if (mustSkipForMissingEnhancedRedefinition(
						 diff.structureChanged(), isEnhancedHotswapEnabled())) {
							// 标准 HotSpot 的 redefineClasses 不允许 schema 变化，且批量提交是全有或全无：
							// 放它进 definitions 会让同批次里纯方法体修改的类一起失败。
							// 因此把该类摘出本批，其余类照常重定义。
							error("REJECTED: " + className + " has field/method structure changes but"
							      + " this JVM has no enhanced class redefinition (JBR/DCEVM)."
							      + " Skipping this class; other classes in this batch continue."
							      + " See docs/initfix/04-target-design.md.");
							AlignmentTransaction tx = transactions.get(className);
							if (tx != null) {
								tx.rollback(true);
								transactions.remove(className);
							}
							// 与 hierarchyChanged 同理：受 JVM 能力限制的拒绝，重试永远不会成功，
							// 不登记 retryCandidates，避免"每次触发都失败一次"的噪音。
							skips.add(className, RoundSkips.verdict("NO-ENHANCED-REDEFINE",
								"field/method structure changed but this JVM has no enhanced redefinition",
								"run on JBR/DCEVM, or restart"));
							continue;
						}
						if (diff.structureChanged()) {
							log("[DCEVM] Structure change detected, proceeding with enhanced redefinition.");
						}
						long initFixT0 = System.nanoTime();
						if (!fullNodesLoaded && InitFix.needsMethodBodies(targetClass, diff)) {
							ClassDiffUtil.ensureFullClassNodes(diff, oldBytecode, newBytecode);
						}
						InitFix.transform(targetClass, newBytecode, diff);
						initFixNanos += System.nanoTime() - initFixT0;
					} else {
						log("[WARN] Cannot diff " + className + " (missing old bytecode). Proceeding with redefine.");
					}
					if (oldBytecode != null) oldEffectiveBytes.put(className, oldBytecode);

					definitions.add(new ClassDefinition(targetClass, newBytecode));
					// 登记为"待重定义"：成败由 applyRedefinitions 决定。
					// 若最终失败，文件会被放回 pendingChanges 以便下次重试。
					//
					// 位置很关键：必须在 hierarchyChanged 的 continue **之后** ——
					// 那种拒绝受 JVM 能力限制，重试永远不会成功，回队只会造成
					// "每次触发都失败一次"的噪音。这里只会登记真正进入 definitions 的类。
					if (path != null) retryCandidates.put(className, path);
				} else {
					if (DEBUG) log("[NEW] " + className);
					// 类尚未加载：根据 REDEFINE_MODE 处理
					// 若 path 来自 jar 临时目录，需还原为 watchDir 内的虚拟路径以正确推断 ClassLoader
					Path clHintPath = JarExtractor.tmpToJar().containsKey(path)
					 ? JarExtractor.resolveWatchDirPath(JarExtractor.tmpToJar().get(path), className)
					 : path;
					// clHintPath == null 表示来自直接传入的 activeWatchJar，无虚拟 watchDir 路径，
					// 退化到纯包名推断（findTargetClassLoader(className)），inject 照常走，lazy_load 挂 tmpDir 路径
					if (REDEFINE_MODE == RedefineMode.inject) {
						boolean injected = injectNewClass(className, clHintPath, bytecode);
						if (injected) {
							recordRuntimeBytecode(className, bytecode);
							injectedCount++;
							injectedClasses.add(className);
							// 注入成功即视为生效：指纹可立即落账（不经过 applyRedefinitions）。
							// 失败时不落账，下次触发会重试。指纹同样只记磁盘原始字节。
							String injectRawName = targetToRawName.getOrDefault(className, className);
							Long   injectRawHash = rawDiskHashes.get(injectRawName);
							long   diskHash = injectRawHash != null ? injectRawHash : calculateHash(bytecode);
							synchronized (fileDiskHashes) {
								fileDiskHashes.put(CRC64.hashString(injectRawName), diskHash);
							}
						} else {
							// 注入失败：类仍未加载，登记出生点补丁作为兜底（失败时不落指纹，下次触发会重试）。
							PendingLoadPatch.offerAny(className, bytecode);
						}
					} else if (REDEFINE_MODE == RedefineMode.lazy_load && UCP_APPEND) {
						ClassLoader loader = findTargetClassLoader(className, clHintPath);
						// lazy_load + jar：ClassLoader 推断用虚拟路径（clHintPath），
						// 实际挂载用真实 tmpDir 路径，否则 ClassLoader 找不到 class 文件
						Path mountPath = JarExtractor.tmpToJar().containsKey(path) ? path : clHintPath;
						if (mountPath != null) {
							PackageUnsealer.unsealClassPackage(loader, className);
							mountForClass(loader, mountPath);
						}
						recordRuntimeBytecode(className, bytecode);
						// 类尚未加载：登记出生点补丁，让它第一次加载时直接拿到这次编辑的字节
						// （加载器此刻还未知，按 ANY_SCOPE 登记；出生点的工作区归属校验兜底）。
						PendingLoadPatch.offerAny(className, bytecode);
						// lazy_load 只是挂载路径，类尚未真正加载生效 —— 指纹**不**落账，
						// 等它真正被加载/重定义时再记，否则会漏掉后续的首次加载。
					}
				}
			} catch (Throwable e) {
				error("Failed to process " + path, e);
			}
		}
		InitFix.setBatchDefinitions(definitions);
		InitFix.finishBatchTransforms();
		if (TWO_PHASE) {
			for (ClassDefinition definition : definitions) {
				if (InitFix.requiresTwoPhase(definition.getDefinitionClass())) {
					needsTwoPhase = true;
					break;
				}
			}
		}

		// 注解默认值变化的**事务共退**：本轮整体作废（连坐），避免"注解类型换上了新默认值、
		// 已加载的消费者仍读旧默认值"这种半套状态。记账动作在
		// {@link #abortRoundForAnnotationDefault} 里（同一入口被单测覆盖），这里只补一条可见的总结。
		// 下游的 sourceBytecodeCache 回滚 / 指纹不落账 / 不回队逻辑照常执行（outcome 为空）。
		if (annotationDefaultAbort != null) {
			error("[HOTSWAP] Round aborted: annotation default value changed in " + annotationDefaultAbort
				+ "; no class of this batch was redefined (transaction co-rollback)."
				+ " RESTART required for the new default to take effect.");
		}

		// 批量执行重定义（针对已加载类）。
		// 两阶段：当本批确实要补"新增/类型变更字段"时，先提交阶段 A（新结构 + 旧方法体，构造器保持新体），
		// 让存量实例在旧行为下被补齐，再提交阶段 C 的新方法体；否则沿用单次重定义的快路径。
		long alignDiffMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - alignDiffStartNanos);

		long redefineWallStartNanos = PERF_LOG ? System.nanoTime() : 0L;
		RedefineOutcome outcome;
		if (needsTwoPhase && TWO_PHASE) {
			outcome = applyTwoPhase(definitions, oldEffectiveBytes, transactions.values(), skips, injectedClasses);
		} else {
			if (HeapFieldWatch.enabled() && !definitions.isEmpty()) {
				HeapFieldWatch.snapshot("before-redefine", batchWatchLabel(definitions));
			}
			outcome = applyRedefinitions(definitions, transactions.values(), skips);
			if (HeapFieldWatch.enabled() && !outcome.successful().isEmpty()) {
				HeapFieldWatch.snapshot("after-redefine", batchWatchLabel(definitions));
			}
		}
		Set<String>     redefinedOk = outcome.successful();
		Set<String>     rolledBack  = outcome.rolledBack();
		// PR1 旁路记录（写入点）：只在本批最终重定义结果确定之后提交；对齐期的暂存不在这里。
		if (Boolean.getBoolean("remold.vcs.enabled")) {
			try {
				commitVcsBatch(definitions, redefinedOk, rolledBack, lambdaStrictRejected,
				 needsTwoPhase && TWO_PHASE);
			} catch (Throwable t) {
				warn("[VCS] side-channel record skipped: " + t);
			}
		}
		long redefineWallEndNanos = PERF_LOG ? System.nanoTime() : 0L;

		// sourceBytecodeCache 是“最近一次实际生效的源码基线”，不是“本轮编译结果”。
		// 对齐阶段必须能看到本轮新字节（它读的是 prevSourceBytes 快照，不是缓存），所以候选字节只进 staged；
		// 这里按类判定生效性后一次性原子并入已提交视图，未生效类丢弃，注入的新类与成功类保留新源码。
		rollbackSourceCache(sourceBefore, redefinedOk, loadedBefore);

		// ---- 指纹落账 + 失败回队 ----
		//
		// 指纹只对"确实生效"的类记录。未生效的类不落账，因此下次触发不会被
		// "file hash unchanged" 误跳过 —— 这正是本次修复的核心。
		//
		// 同时把重定义失败的类的源文件放回 pendingChanges：失败的文件在
		// triggerHotswapWith0 里已被取走，而 triggerHotswap() 不重扫磁盘，
		// 不回队的话"再热更一次"实际上什么也不会发生（文件不再被写入就不会有新的 watcher 事件）。
		//
		// **例外：被 rollback 的事务组不回队。** 那种情况下宿主可能已生效、而 cache/pending
		// 被钉回旧版本；重试会拿与 JVM 实际状态不一致的基线重新对齐。只提示重启。
		int requeued    = 0;
		int notRequeued = 0;
		for (Map.Entry<String, Path> e : retryCandidates.entrySet()) {
			String className = e.getKey();
			if (redefinedOk.contains(className)) {
				// 成功：落账指纹（键必须是磁盘原始类名，不能是重命名后的目标类名）
				String rawName = targetToRawName.getOrDefault(className, className);
				synchronized (fileDiskHashes) {
					fileDiskHashes.put(CRC64.hashString(rawName), pendingHashes.get(CRC64.hashString(rawName)));
				}
				continue;
			}
			if (rolledBack.contains(className)) {
				// 被 rollback 的组：不落账、也不回队（回队不安全，见上）
				notRequeued++;
				if (DEBUG) log("[RETRY-SKIP] " + className + " in rolled-back group; not requeued.");
				continue;
			}
			// 普通失败：不落账（下次仍会尝试），并回队以便立即重试
			Path p = e.getValue();
			if (p != null && !Files.exists(p)) {
				// jar 来源的类：临时目录会在下一次 extractJarToTemp 时被清空重建，
				// 回队的路径可能已不存在。不报出来这些类会静默丢失重试。
				error("[RETRY-QUEUE] Source file no longer exists for " + className + ": " + p
				      + " (likely a jar temp dir that was rebuilt); this class cannot be retried"
				      + " until its jar is written again.");
				continue;
			}
			pendingChanges.add(p);
			requeued++;
			if (DEBUG) log("[RETRY-QUEUE] " + className + " failed to redefine; requeued " + p);
		}
		if (requeued > 0) {
			warn("[HOTSWAP-RETRY] " + requeued + " class(es) failed to redefine and were requeued; "
			     + "press hot-swap again to retry.");
		}
		if (notRequeued > 0) {
			warn("[HOTSWAP-RETRY] " + notRequeued + " class(es) in an inconsistent group were NOT requeued; "
			     + "RESTART is required for those.");
		}

		// 后处理是否真的跑起来了，必须能从日志里看出来：这一阶段的缺失（InitFix 补丁、@OnReload 回调）
		// 与"重定义成功"长得一模一样，真机排查时无法区分。DEBUG 下打首尾两行即可定性。
		if (DEBUG) log("[POST] post-processing " + redefinedOk.size() + " class(es): " + redefinedOk);

		long commitMs = PERF_LOG ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - redefineWallEndNanos) : 0L;
		List<ClassDefinition> successfulDefinitions = new ArrayList<>(definitions.size());
		List<Class<?>> patchTargets = new ArrayList<>(definitions.size());
		for (ClassDefinition def : definitions) {
			if (!redefinedOk.contains(def.getDefinitionClass().getName())) continue;
			successfulDefinitions.add(def);
			patchTargets.add(def.getDefinitionClass());
		}
		long stageBStart = System.nanoTime();
		// 批次级单次堆遍历（与两阶段 Stage B 同一入口）：本批 K 个类的实例收集只做一次全堆扫描。
		// 两阶段路径下这些类的 PENDING 已在 Stage B 被摘掉，这里过滤后为空 → 不产生额外遍历。
		InitFix.beginBatchInstanceCollection(patchTargets);
		try {
			for (Class<?> clazz : InitFix.orderBatchHosts(patchTargets)) {
				try {
					InitFix.afterRedefine(clazz);
				} catch (Throwable e) {
					error("Failed to process InitFix.", e);
					InitFix.afterRedefineFailed(clazz);
				}
			}
		} finally {
			InitFix.endBatchInstanceCollection();
			InitFix.endBatchBytecodeView();
		}
		BATCH_STAGE_B_MILLIS.addAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stageBStart));
		if (HeapFieldWatch.enabled() && !(needsTwoPhase && TWO_PHASE) && !successfulDefinitions.isEmpty()) {
			HeapFieldWatch.snapshot("after-field-patch", batchWatchLabel(successfulDefinitions));
		}

		// 必须在 InitFix 补丁（静态字段与存量实例字段）全部就位后，再对成功重定义的类触发 @OnReload 回调；
		// 严禁在补丁就绪前触发，否则回调读到未初始化的零值（时序反转）。
		long annStartNanos = PERF_LOG ? System.nanoTime() : 0L;
		processAnnotations(successfulDefinitions);
		if (DEBUG) log("[POST] post-processing done for " + successfulDefinitions.size() + " class(es)");
		List<String> redefinedNames = new ArrayList<>(successfulDefinitions.size());
		for (ClassDefinition def : successfulDefinitions) {
			redefinedNames.add(def.getDefinitionClass().getName());
		}
		long listenStartNanos = PERF_LOG ? System.nanoTime() : 0L;
		// finish 会清掉当前线程的批次状态；即使本批没有类成功，也要发布
		// “Mixin 模板已 reload / InitFix 已修补”这类可观测活动（见 notifyBatchListeners）。
		notifyBatchListeners(successfulDefinitions, BatchReportScope.finish(redefinedNames));
		long listenEndNanos = PERF_LOG ? System.nanoTime() : 0L;
		if (!definitions.isEmpty() || injectedCount > 0) {
			boolean twoPhaseMode = needsTwoPhase && TWO_PHASE;
			long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - batchStartNanos);
			long redefMs = BATCH_REDEFINE_MILLIS.get();
			long stageBMs = BATCH_STAGE_B_MILLIS.get();
			String prepDetail = PERF_LOG
				? "prep=" + prepMs + "ms [pkg=" + pkgMs + ", snap=" + snapMs + ", io=" + ioMs + "], "
				: "";
			String anonDetail = PERF_LOG
				? " [align=" + anonAlignMs + ", bridge=" + bridgeMs + "]"
				: "";
			String alignDiffDetail = PERF_LOG
				? " [fetch=" + TimeUnit.NANOSECONDS.toMillis(fetchNanos) + "ms"
				  + ", lambda=" + TimeUnit.NANOSECONDS.toMillis(lambdaNanos) + "ms"
				  + ", diff=" + TimeUnit.NANOSECONDS.toMillis(diffNanos) + "ms"
				  + ", annDef=" + TimeUnit.NANOSECONDS.toMillis(annDefNanos) + "ms"
				  + ", initFix=" + TimeUnit.NANOSECONDS.toMillis(initFixNanos) + "ms]"
				: "";
			String redefineDetail = "";
			String postDetail = "";
			if (PERF_LOG) {
				long skelMs = BATCH_TWOPHASE_SKEL_MILLIS.get();
				long stageBInRedef = twoPhaseMode ? stageBMs : 0L;
				long redefWrapMs = Math.max(0L,
					TimeUnit.NANOSECONDS.toMillis(redefineWallEndNanos - redefineWallStartNanos)
					- redefMs - stageBInRedef - skelMs);
				redefineDetail = twoPhaseMode
					? " [A=" + BATCH_STAGE_A_MILLIS.get() + ", C=" + BATCH_STAGE_C_MILLIS.get()
					  + ", skel=" + skelMs + ", wrap=" + redefWrapMs + "]"
					: " [wrap=" + redefWrapMs + "]";
				long annMs = TimeUnit.NANOSECONDS.toMillis(listenStartNanos - annStartNanos);
				long listenMs = TimeUnit.NANOSECONDS.toMillis(listenEndNanos - listenStartNanos);
				long postMs = commitMs + annMs + listenMs;
				postDetail = ", post=" + postMs + "ms [commit=" + commitMs
					+ ", ann=" + annMs + ", listen=" + listenMs + "]";
			}
			info("[HOTSWAP-BATCH] summary: mode=" + (twoPhaseMode ? "2-phase" : "1-phase")
			     + ", total=" + totalMs + "ms"
			     + " (" + prepDetail
			     + "anon=" + anonMs + "ms" + anonDetail
			     + ", guard=" + guardMs + "ms"
			     + ", readers=" + readersMs + "ms"
			     + ", gate=" + gateMs + "ms"
			     + ", alignDiff=" + alignDiffMs + "ms" + alignDiffDetail
			     + ", liveScan=" + LIVE_INSTANCE_SCAN_MILLIS.get() + "ms"
			     + ", redefine=" + redefMs + "ms" + redefineDetail
			     + ", stageB=" + stageBMs + "ms"
			     + postDetail
			     + ", redefined=" + successfulDefinitions.size() + ")");
		}

		// L0/L1 必须在 InitFix 后处理（afterRedefine）之后运行：
		// 一是 L1 的"已重放"记录在 applyPatch 里写入，L0 需要据此抑制误导性告警；
		// 二是补丁只能作用于真正重定义成功的类。门拒绝/跳过的类必须零 INIT-DRIFT、零实例枚举。
		long tailStartNanos = PERF_LOG ? System.nanoTime() : 0L;
		Map<String, byte[]> appliedForDrift = new LinkedHashMap<>();
		for (String key : newBatchBytes.keySet()) {
			if (redefinedOk.contains(key)) appliedForDrift.put(key, newBatchBytes.get(key));
		}
		// Drain even when no replay ran: a stale record must not leak into the next round.
		Map<String, Set<String>> replayedForDrift = InitializerReplay.drainApplied();
		InitializerDrift.report(appliedForDrift, driftOldBytes, replayedForDrift);
		long metaStartNanos = PERF_LOG ? System.nanoTime() : 0L;

		Set<Class<?>> successfulClasses = new HashSet<>();
		for (ClassDefinition def : successfulDefinitions) {
			successfulClasses.add(def.getDefinitionClass());
		}
		// 参数注解 / 泛型签名漂移：成员级 WARN 点名（显式登记"用户自持旧句柄不安全"）。
		// 成员级点名只覆盖字段/方法/构造器的 Signature 与参数注解；类级泛型签名（getGenericSuperclass）不在检测范围，本行也不改变缓存驱逐范围。
		reportMemberMetadataDrift(appliedForDrift, driftOldBytes);
		long evictStartNanos = PERF_LOG ? System.nanoTime() : 0L;
		boolean schemaOrMetadataChanged = false;
		for (ClassDefinition def : successfulDefinitions) {
			String name = def.getDefinitionClass().getName();
			byte[] oldBytes = driftOldBytes.get(name);
			byte[] newBytes = appliedForDrift.getOrDefault(name, def.getDefinitionClassFile());
			if (ReflectionCacheManager.hasSerializationSchemaChange(oldBytes, newBytes)) {
				schemaOrMetadataChanged = true;
				break;
			}
		}
		ReflectionCacheManager.evictAll(successfulClasses, schemaOrMetadataChanged);
		if (PERF_LOG && (!definitions.isEmpty() || injectedCount > 0)) {
			long tailEndNanos = System.nanoTime();
			long tailMs = TimeUnit.NANOSECONDS.toMillis(tailEndNanos - tailStartNanos);
			long driftMs = TimeUnit.NANOSECONDS.toMillis(metaStartNanos - tailStartNanos);
			long metaMs = TimeUnit.NANOSECONDS.toMillis(evictStartNanos - metaStartNanos);
			long evictMs = TimeUnit.NANOSECONDS.toMillis(tailEndNanos - evictStartNanos);
			info("[HOTSWAP-PERF] post-summary tail=" + tailMs + "ms [drift=" + driftMs
			     + ", meta=" + metaMs + ", evict=" + evictMs + "]");
		}

		// 与上面的 [HOTSWAP-SKIPPED] 区分：这里是"磁盘哈希没变，本来就没改"，不是被门拦下。
		if (skippedCount > 0) info("Unchanged: " + skippedCount + " class(es) had identical bytes on disk.");
		if (injectedCount > 0) info("Injected " + injectedCount + " new classes.");
	}

	/**
	 * 参数注解 / 泛型签名漂移的接线点：逐类比对重定义前的有效字节与本轮生效字节，成员级点名并 {@code warn}。
	 *
	 * <p>这条告警是"用户自持旧 {@code Method} 句柄不安全"的唯一登记入口：缓存驱逐只覆盖已注册驱逐器，
	 * 无法修复用户代码或未注册框架里已经解析好的句柄。缺了它，读到日志的人会把
	 * "驱逐过缓存"误读成"已解决"。</p>
	 *
	 * <p><b>仅限内部用途（internal use only）</b>：本方法是 Agent 内部接线点，不是公开 API；
	 * 保持 {@code public static} 只因调用方（{@code tests/src/remold/evict} 包）与
	 * {@link RemoldAgent} 不同包——同包可见性改不了。语义/签名随批次演进不承诺兼容。</p>
	 *
	 * <p>单测用 {@link #logger} 捕获告警做终局断言，而不是复制一份比对逻辑（复制出来的那份不会随实现漂移）。</p>
	 *
	 * @param newBytes 本轮生效字节（键为目标类名）
	 * @param oldBytes 重定义前的有效字节（键同上；缺键表示没有基线，不判定，也不当作"没变"）
	 * @return 是否发现至少一处漂移（调用方据此把缓存驱逐范围扩到派生类）
	 */
	static boolean reportMemberMetadataDrift(Map<String, byte[]> newBytes, Map<String, byte[]> oldBytes) {
		if (newBytes == null || newBytes.isEmpty()) return false;
		boolean drift = false;
		for (Map.Entry<String, byte[]> e : newBytes.entrySet()) {
			byte[] before = oldBytes == null ? null : oldBytes.get(e.getKey());
			if (before == null) continue;
			List<String> lines = ReflectionCacheManager
				.describeMemberMetadataDrift(e.getKey(), before, e.getValue());
			if (lines.isEmpty()) continue;
			drift = true;
			for (String line : lines) warn(line);
		}
		return drift;
	}


	/**
	 * 布局门用：某个类是否还有存活实例（{@code docs/topology/07-layout-gate-and-risks.md} §1 的精确变体）。
	 *
	 * <p><b>判定规则（三条都很关键，别简化）</b>：</p>
	 * <ol>
	 *   <li><b>{@code LibTool} 优先</b> —— 走 JVMTI 的 {@code IterateOverInstancesOfClass}，
	 *       覆盖全部堆实例。</li>
	 *   <li><b>{@code LibTool} 不可用或异常 ⇒ 视为"有实例"。</b>
	 *       刻意<b>不</b>回退到 {@link InstanceTracker}：它只是一个由注入代码
	 *       {@code register()} 填充的弱集合，对匿名类基本是空的，
	 *       信它会误判成"无实例"而放行 —— 那正是我们最不想要的方向。
	 *       保守方向的代价只是少配对，放行的代价是静默读零值。</li>
	 *   <li><b>按类缓存</b> —— 每次扫描都是一次全堆遍历（会触发 safepoint），
	 *       而同一批里可能有多个类命中。</li>
	 * </ol>
	 *
	 * <p><b>语义边界</b>：返回值 {@code false} 只表示<b>本轮 JVMTI 堆扫描没有看见</b>该类的实例，
	 * 不能反证“实例不存在”（扫描不可用/异常时走保守分支返回 {@code true}；扫描本身也看不到
	 * 它没覆盖到的对象）。因此派生的日志不得把 {@code false} 写成“实例已死”或安全结论，
	 * 只能表述为“扫描可见的活实例为 0”。</p>
	 *
	 * <p>仅用于布局不兼容的类，因此调用频率很低。</p>
	 */
	static boolean hasLiveInstances(Class<?> c) {
		if (c == null) return false;
		String dotClassName = c.getName();
		Boolean cached = LIVE_INSTANCE_CACHE.get(dotClassName);
		if (cached != null) return cached;

		// 批次级惰性预探测：同一批里 K 个类各问一次 liveness 就是 K 次全堆遍历，改成
		// "第一次未命中时对整批做一次遍历"（docs/initfix/05-jvmti-heap.md §「批次存在性探测」）。
		// 只在布局门显式设了批次上下文时才生效；单类/外部路径行为不变。
		prefetchBatchLiveness();
		cached = LIVE_INSTANCE_CACHE.get(dotClassName);
		if (cached != null) return cached;

		boolean result;
		long    t0 = System.nanoTime();
		try {
			if (!LibTool.initialized()) {
				// 尝试初始化；失败会抛 UnsatisfiedLinkError
				LibTool.init();
			}
			result = LibTool.hasLiveInstance(c);
		} catch (Throwable t) {
			// JVMTI 不可用 / 初始化失败 / 扫描异常 —— 一律按"有实例"处理（保守）。
			warn("[ANON-LAYOUT] Cannot scan instances for " + dotClassName
			     + " (" + t.getClass().getSimpleName()
			     + (t.getMessage() != null ? ": " + t.getMessage() : "")
			     + "); assuming LIVE instances exist.");
			result = true;
		}
		long ms = (System.nanoTime() - t0) / 1_000_000;
		LIVE_INSTANCE_SCAN_MILLIS.addAndGet(ms);
		if (DEBUG || ms > 50) {
			log("[ANON-LAYOUT] instance scan " + dotClassName + " -> " + (result ? "LIVE" : "none")
			    + " (" + ms + " ms)");
		}
		LIVE_INSTANCE_CACHE.put(dotClassName, result);
		return result;
	}

	static boolean hasLiveInstances(String dotClassName) {
		Class<?> c = loadedClassesMap.get(dotClassName);
		if (c == null) {
			// 旧类未加载 ⇒ 不可能有实例。这是**安全**方向的"无实例"：
			// 未加载的类没有对象可言，放行配对不会让任何东西读到零值。
			return false;
		}
		return hasLiveInstances(c);
	}

	/** 每轮热更开始时清空实例扫描缓存（实例的存活状况会随轮次变化）。 */
	static void resetLiveInstanceCache() {
		LIVE_INSTANCE_CACHE.clear();
		LIVE_INSTANCE_SCAN_MILLIS.set(0);
		BATCH_REDEFINE_MILLIS.set(0);
		BATCH_STAGE_B_MILLIS.set(0);
		BATCH_STAGE_A_MILLIS.set(0);
		BATCH_STAGE_C_MILLIS.set(0);
		BATCH_TWOPHASE_SKEL_MILLIS.set(0);
		LIVENESS_BATCH = Set.of();
		LIVENESS_BATCH_DONE.set(true);
	}

	/**
	 * 声明本批次待判定存活性的类名集合（布局门批次级预探测的作用域）。
	 *
	 * <p>布局门只在「布局不兼容」的类上问 liveness，逐类问就是逐次全堆遍历。批次上下文一旦声明，
	 * 第一次未命中缓存时用 {@link LibTool#hasLiveInstancesBatch(Class[])} 对整批做<b>一次</b>遍历并
	 * 填满缓存，后续同批类直接命中缓存。传空集合即关闭（单类 / 外部热更路径）。</p>
	 */
	static void setLivenessBatch(Collection<String> classNames) {
		LIVENESS_BATCH = classNames == null || classNames.isEmpty()
			? Set.of()
			: Collections.unmodifiableSet(new LinkedHashSet<>(classNames));
		LIVENESS_BATCH_DONE.set(LIVENESS_BATCH.size() < 2);
	}

	/** 批次级惰性存活预探测：整批一次遍历，结果填进 {@link #LIVE_INSTANCE_CACHE}。 */
	private static void prefetchBatchLiveness() {
		if (LIVENESS_BATCH_DONE.get() || !LIVENESS_BATCH_DONE.compareAndSet(false, true)) return;
		Set<String> names = LIVENESS_BATCH;
		if (names.size() < 2) return;
		List<Class<?>> classes = new ArrayList<>(names.size());
		List<String>  keys    = new ArrayList<>(names.size());
		for (String name : names) {
			Class<?> c = loadedClassesMap.get(name);
			// 未加载的类没有实例可言：与 hasLiveInstances(String) 的既有语义一致。
			if (c == null) continue;
			keys.add(name);
			classes.add(c);
		}
		if (classes.size() < 2) return;
		long t0 = System.nanoTime();
		boolean[] live;
		try {
			if (!LibTool.initialized() && !LibTool.tryInit()) return;
			live = LibTool.hasLiveInstancesBatch(classes.toArray(new Class<?>[0]));
		} catch (Throwable t) {
			// 批探测失败不改变判定方向：逐类路径（含"扫描不可用 ⇒ 视为有实例"）照常兜住。
			warn("[ANON-LAYOUT] Batch liveness probe failed (" + t.getClass().getSimpleName()
			     + (t.getMessage() != null ? ": " + t.getMessage() : "")
			     + "); falling back to one heap walk per class.");
			return;
		}
		if (live == null || live.length != keys.size()) return;
		for (int i = 0; i < live.length; i++) {
			LIVE_INSTANCE_CACHE.put(keys.get(i), live[i]);
		}
		long ms = (System.nanoTime() - t0) / 1_000_000;
		LIVE_INSTANCE_SCAN_MILLIS.addAndGet(ms);
		if (DEBUG) log("[ANON-LAYOUT] batch instance scan: " + classes.size()
		               + " class(es) in one heap walk (" + ms + " ms)");
	}

	private static final byte[] ON_RELOAD_DESC_BYTES =
		"Lremold/annotation/OnReload;".getBytes(StandardCharsets.US_ASCII);

	/**
	 * 在重定义与 InitFix 补丁（静态字段与存量实例字段）全部就位后调用。
	 *
	 * <p>扫描目标类中标记了 {@link OnReload} 的方法并触发调用。必须在 InitFix 之后触发，
	 * 保证回调执行时新增/覆写的字段已处于就绪态，杜绝时序反转读到零值。</p>
	 */
	static void processAnnotations(List<ClassDefinition> definitions) {
		if (definitions == null || definitions.isEmpty()) return;
		for (ClassDefinition def : definitions) {
			Class<?> clazz = def.getDefinitionClass();
			if (clazz == null) continue;
			byte[] bytes = def.getDefinitionClassFile();
			// Skip getDeclaredMethods() + runtime annotation parsing when the class bytes have no @OnReload descriptor.
			if (bytes != null && !AnnotationTransformer.containsAscii(bytes, ON_RELOAD_DESC_BYTES)) {
				continue;
			}
			postToMain(() -> processAnnotationsInternal(clazz));
		}
	}

	static void processAnnotationsInternal(Class<?> clazz) {
		if (clazz == null) return;
		List<Method> staticMethods = new ArrayList<>(2);
		List<Method> instanceMethods = new ArrayList<>(2);
		for (Method m : clazz.getDeclaredMethods()) {
			if (m.isAnnotationPresent(OnReload.class)) {
				m.setAccessible(true);
				if (Modifier.isStatic(m.getModifiers())) {
					staticMethods.add(m);
				} else {
					instanceMethods.add(m);
				}
			}
		}
		if (staticMethods.isEmpty() && instanceMethods.isEmpty()) return;
		info("[Reload] Found @OnReload on " + clazz);

		for (Method m : staticMethods) {
			try {
				m.invoke(null);
				if (DEBUG) log("[Reload] Invoked @OnReload static method: " + m.getName());
			} catch (Throwable e) {
				error("Error invoking static @OnReload method " + m.getName() + " on " + clazz.getName(), e);
			}
		}

		if (!instanceMethods.isEmpty()) {
			Object[] instances = null;
			// stageB 补丁刚刚枚举过同一批实例：直接复用，省掉一次完整 JVMTI 堆遍历
			// （2M 实例实测约 2.9s）。快照语义与"枚举时间点"的差异见 StageBInstanceCache。
			try {
				instances = StageBInstanceCache.take(ClassIdentity.canonicalize(clazz));
			} catch (Throwable ignored) {
			}
			if (instances == null) {
				try {
					instances = LibTool.getInstances(clazz);
				} catch (Throwable ignored) {
				}
			}
			if (instances == null || instances.length == 0) {
				List<?> trackerInstances = InstanceTracker.getInstances(clazz);
				if (!trackerInstances.isEmpty()) {
					instances = trackerInstances.toArray();
				}
			}
			if (instances == null) instances = new Object[0];
			// 实例回调是"对存量实例"，不是"模组收到通知"：零实例时它一次都不会跑。
			// 这种情况原先完全静默（只打一行 "Found @OnReload"），最容易被误读成"回调没生效"，
			// 所以必须显式说明"没有调用"以及为什么。
			if (instances.length == 0) {
				warn("[Reload] " + clazz.getName() + " declares " + instanceMethods.size()
				     + " instance @OnReload method(s) but NO live instance was found — they were NOT"
				     + " invoked. @OnReload on an instance method is a per-instance callback, not a"
				     + " 'mod reloaded' notification: a static-only holder (or a class whose instances"
				     + " are already gone) never receives it. Use a static @OnReload method instead if"
				     + " you want one call per hotswap.");
			} else {
				info("[Reload] Invoking " + instanceMethods.size() + " instance @OnReload method(s) on "
				     + instances.length + " live instance(s) of " + clazz.getName() + ".");
			}
			MethodHandle[] instanceHandles = new MethodHandle[instanceMethods.size()];
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			for (int i = 0; i < instanceMethods.size(); i++) {
				Method m = instanceMethods.get(i);
				if (m.getParameterCount() == 0) {
					try {
						instanceHandles[i] = lookup.unreflect(m)
							.asType(MethodType.methodType(void.class, Object.class));
					} catch (Throwable ignored) {
					}
				}
			}
			if (instanceMethods.size() == 1) {
				Method singleMethod = instanceMethods.get(0);
				MethodHandle singleHandle = instanceHandles[0];
				for (Object obj : instances) {
					invokeReloadMethod(obj, singleMethod, singleHandle);
				}
			} else {
				for (Object obj : instances) {
					for (int i = 0; i < instanceMethods.size(); i++) {
						invokeReloadMethod(obj, instanceMethods.get(i), instanceHandles[i]);
					}
				}
			}
		}
	}

	static String resolveAnonHostCandidate(
	 String cName, Set<String> hostClassesToAlign, String singleHost
	) {
		int len = cName.length();
		if (len == 0) return null;
		char last = cName.charAt(len - 1);
		if (last < '0' || last > '9') return null;
		if (singleHost != null) {
			int hostLen = singleHost.length();
			if (len <= hostLen + 1 || cName.charAt(hostLen) != '$' || !cName.startsWith(singleHost)) {
				return null;
			}
		}
		String host = AnonClassAligner.batchAnonHost(cName);
		return (host != null && !host.equals(cName) && hostClassesToAlign.contains(host)) ? host : null;
	}

	private static void invokeReloadMethod(Object obj, Method m, MethodHandle handle) {
		try {
			if (handle != null) {
				handle.invokeExact(obj);
			} else {
				m.invoke(obj);
			}
			if (DEBUG) log("[Reload] Invoked @OnReload on " + obj + "." + m.getName());
		} catch (Throwable e) {
			error("Error invoking @OnReload on " + obj + "." + m.getName(), e);
		}
	}


	static boolean isBlacklisted(String className) {
		if (HOTSWAP_BLACKLIST == null) return false;
		for (String prefix : HOTSWAP_BLACKLIST) {
			if (!prefix.isEmpty() && className.startsWith(prefix)) return true;
		}
		return false;
	}

	static byte[] fetchBytecodeMemory(Class<?> clazz) throws UnmodifiableClassException {
		byte[][] bytecode = {null};
		class MyTransformer implements ClassFileTransformer {
			@Override
			public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
				if (
				 clazz == classBeingRedefined
					/* className.equals(clazz.getName()) && loader == clazz.getClassLoader() */) {
					bytecode[0] = classfileBuffer;
				}
				return null;
			}
		}
		MyTransformer transformer1 = new MyTransformer();
		inst.addTransformer(transformer1, true);
		enterInternalRetransform(Collections.singleton(clazz.getName()));
		try {
			inst.retransformClasses(clazz);
			return bytecode[0];
		} finally {
			exitInternalRedefine();
			inst.removeTransformer(transformer1);
		}
	}
	static byte[] fetchCurrentBytecode(Class<?> clazz) {
		String className = clazz.getName();
		if (hasRuntimeBytecode(className)) return getRuntimeBytecode(className);
		return fetchOriginalBytecode(clazz);
	}
	static byte[] fetchOriginalBytecode(Class<?> clazz) {
		String      path = clazz.getName().replace('.', '/') + ".class";
		ClassLoader cl   = clazz.getClassLoader();
		if (cl == null) cl = ClassLoader.getSystemClassLoader();

		try {
			// 核心扬弃：获取所有同名资源，执行空间隔离
			var resources = cl.getResources(path);

			while (resources.hasMoreElements()) {
				URL     url            = resources.nextElement();
				boolean isFromWatchDir = false;

				// 异质点校验：判断这个流是否来自我们监控（已被修改）的目录
				if ("file".equals(url.getProtocol())) {
					// 普通 .class 文件：直接判断路径是否在 watchDir 下
					try {
						Path resourcePath = Paths.get(url.toURI()).toAbsolutePath();
						for (Path watchDir : activeWatchDirs) {
							if (resourcePath.startsWith(watchDir.toAbsolutePath())) {
								isFromWatchDir = true;
								break;
							}
						}
					} catch (Exception ignored) {
						// URI格式异常，防守性跳过
					}
				} else if ("jar".equals(url.getProtocol())) {
					// jar 内 .class：URL 形如 jar:file:/watchdir/foo.jar!/com/Foo.class
					// 需要把宿主 jar 的路径单独解析出来再做判断
					try {
						String jarUrlStr = url.getPath();          // file:/watchdir/foo.jar!/com/Foo.class
						int    bangIdx   = jarUrlStr.indexOf("!/");
						if (bangIdx != -1) {
							String jarFileStr = jarUrlStr.substring(0, bangIdx); // file:/watchdir/foo.jar
							Path   jarPath    = Paths.get(new URI(jarFileStr)).toAbsolutePath();
							for (Path watchDir : activeWatchDirs) {
								if (jarPath.startsWith(watchDir.toAbsolutePath())) {
									isFromWatchDir = true;
									break;
								}
							}
						}
					} catch (Exception ignored) {
						// URI格式异常，防守性跳过
					}
				}

				// 只要不是来自监控目录（包括目录内的 jar），就视为未被污染的原始字节码
				if (!isFromWatchDir) {
					try (InputStream is = url.openStream()) {
						return is.readAllBytes();
					}
				} else {
					if (DEBUG) log(" Ignored polluted mount path: " + url);
				}
			}
		} catch (Throwable t) {
			// 吞掉异常，退化到盲狙
		}
		return null;
	}
}
