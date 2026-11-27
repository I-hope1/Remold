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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

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
import static remold.RemoldAgent.INVENTED_SLOTS;
import static remold.RemoldAgent.isEnhancedHotswapEnabled;
import static remold.RemoldAgent.isNonLoadableClass;
import static remold.RemoldAgent.loadClassSnap;
import static remold.RemoldAgent.loadedClassesMap;
import static remold.RemoldAgent.loaderScope;
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
import static remold.RemoldAgent.dropHostGroup;
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
		// 旁路字节表按批清空：必须在对齐/注入**之前**，否则本轮注入的类会在注入后被抹掉（探针实测 A1）。
		InitFix.clearExtraBatchBytes();
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
		// 本轮真实改动类名（上一轮源码字节 ≠ 本轮磁盘字节）：只喂给摘要计数（RedefineSummary），不参与判决。
		// 必须在**读文件**阶段判定，早于匿名类对齐与各道门：被门拒绝的类同样属于"这轮改过但没生效"，
		// 否则"零生效"会被误报成"没有改动"（假成功的另一面）。
		Set<String> changedRawNames = new LinkedHashSet<>();
		Set<String> changedNames = new LinkedHashSet<>();
		// host -> 本轮类差异：InitFix 拒绝门要按"新增字段"精确筛选（见 dropUnexpressibleInitClasses）。
		Map<Class<?>, ClassDiffUtil.ClassDiff> diffByHost = new IdentityHashMap<>();
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
					byte[] prevSource = getSourceBytecode(cn);
					if (prevSource == null || !Arrays.equals(prevSource, bc)) changedRawNames.add(cn);
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
			String host = AnonHostResolver.hostOf(cn, entry.getValue());
			if (host != null && !host.equals(Names.internal(cn))
			    && AnonClassAligner.isBatchAnonClass(cn, entry.getValue())) {
				hostClassesToAlign.add(host);        // 匿名类变了：宿主跟着对齐
			} else {
				hostClassesToAlign.add(cn);          // 宿主自己变了：它的匿名类要跟着对齐
			}
		}

		// 记录所有对齐过程中产生的旧孤儿类（不得在本次重定义中被误更新）
		Set<String>                       allOrphanClasses = new HashSet<>();
		Map<String, ClassLoader>          alignedAnonLoaders = new HashMap<>();
		// 宿主改名累积表：本批里已确定 target 的匿名宿主 -> 它在编译期的旧名字。
		// 内层宿主组用它认"字节里还写着旧名字"的子类（现场：外层 $4 -> $3 后内层的 EnclosingMethod 仍是 $4）。
		Map<String, Set<String>>          hostAliases = new HashMap<>();
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
			// 候选预筛：名字落在某个待对齐宿主之下。**不能**用"名字推导宿主"来做最终判定 ——
			// kotlinc 把属性名拼进类名（Host$3$innerObj$1），剥掉尾部数字得到的是从不存在的
			// Host$3$innerObj，于是该类的宿主解析为 null、永远进不了旧侧桶：
			// 现场读数 host=Host$3 恒为 old=0，纯方法体修改也会被当成新类重新编号（$innerObj$2/$3），
			// 存量实例永久停在旧代码且绕过布局门。宿主必须按字节码属性（EnclosingMethod）解析。
			List<String> hostPrefixes = new ArrayList<>(hostClassesToAlign.size());
			for (String h : hostClassesToAlign) hostPrefixes.add(Names.internal(h) + "$");
			for (Map.Entry<String, Class<?>> entry : loadedClassesMap.entrySet()) {
				String cName = entry.getKey();
				String cSlash = Names.internal(cName);
				if (cSlash.isEmpty()) continue;
				char last = cSlash.charAt(cSlash.length() - 1);
				if (last < '0' || last > '9') continue;      // 便宜的预筛：尾部是数字链
				boolean underHost = false;
				for (String prefix : hostPrefixes) {
					if (cSlash.startsWith(prefix)) { underHost = true; break; }
				}
				if (!underHost) continue;
				if (hasRuntimeBytecode(cName)) continue;
				byte[] bc = fetchOriginalBytecode(entry.getValue());
				// 有字节码就按属性解析（权威），没有才退回名字口径；不要求宿主已在待对齐集合里
				// （集合建立于改名传播之前，属性宿主可能还是它的旧名字，见下方旧侧分桶的注释）。
				String host = bc != null
					? AnonHostResolver.hostOf(cName, bc)
					: resolveAnonHostCandidate(cName, null, hostClassesToAlign, singleAlignHost);
				if (host == null || Names.internal(cName).equals(host)) continue;
				if (!AnonHostResolver.isAnonymousName(cName, host)) continue;
				if (bc != null && AnonClassAligner.isBatchAnonClass(cName, bc)) recordRuntimeBytecode(cName, bc);
			}
		}
		// 旧侧分桶：按**字节码属性**解析出的宿主分组，但**不以"待对齐宿主集合"作为入桶过滤条件**。
		//
		// 现场形态：外层匿名类顺移（编译期 Host$4，旧运行期 Host$3）。集合在改名传播之前建立，于是里面只有
		// Host$4；旧类 Host$3$innerObj$1 按属性解析出宿主 Host$3，不在集合里 → 被过滤 → 旧侧桶恒为空
		// （level 2: old=0, new=1），每次编辑都被当成新类重新编号（$innerObj$2/$innerObj$3），存量实例永久
		// 停在旧代码且绕过布局门。因此这里只按"名字落在某个待对齐宿主之下"做便宜预筛，**归属判定推迟到
		// 逐个宿主对齐时**（那时 hostName 已经是传播后的最终名字）。
		Map<String, Map<String, byte[]>> oldAnonsByHost = new HashMap<>();
		if (!hostClassesToAlign.isEmpty()) {
			List<String> pendingPrefixes = new ArrayList<>(hostClassesToAlign.size());
			for (String h : hostClassesToAlign) pendingPrefixes.add(Names.internal(h) + "$");
			for (Map.Entry<String, byte[]> entry : runtimeBytecodeEntries()) {
				String cName  = entry.getKey();
				String cSlash = Names.internal(cName);
				boolean underPendingHost = false;
				for (String prefix : pendingPrefixes) {
					if (cSlash.startsWith(prefix)) { underPendingHost = true; break; }
				}
				if (!underPendingHost) continue;
				byte[] src = getSourceBytecode(cName);
				// 源基线字节只在这个文件**确实属于本类**时才能用：对齐重命名的目标名会经 raw 名映射
				// 拿回另一个类的文件（现场：`$3$innerObj$1` 取到 `$4$innerObj$1` 的字节，属性宿主是 $4，
				// 于是旧类被分到 $4 桶，$3 组永远看到 old=0）。自报类名不一致时退回运行期字节。
				byte[] oldBytes = src != null && cSlash.equals(selfInternalName(src)) ? src : entry.getValue();
				// 宿主解析（旧侧）：属性优先，但**必须与名字自洽**；属性过期（对齐改名/注入后缓存里的
				// EnclosingMethod 仍是旧名字）时，沿名字父链找到第一个"真实存在"的祖先。见 resolveOldAnonHost。
				String host = resolveOldAnonHost(cName, entry.getValue(), oldBytes, loadedClassesMap,
					hostClassesToAlign);
				if (host == null || host.equals(cSlash)) continue;
				if (!AnonClassAligner.isBatchAnonClass(cName, oldBytes)) continue;
				oldAnonsByHost.computeIfAbsent(Names.external(host), k -> new HashMap<>()).put(cName, oldBytes);
			}
		}

		// 由外向内处理宿主：外层匿名类被对齐改名后，其**内层嵌套类**的新侧名字必须跟着落到同一个
		// 名字空间里，才谈得上与旧内层配对（见下方 propagateAnonSubspace）。
		List<String> hostOrder = new ArrayList<>(hostClassesToAlign);
		// 按 anonDepth 严格升序：真实宿主（深度 0）先于匿名宿主，且**父匿名宿主先于子匿名宿主**
		// （Host$1 深度 1、Host$1$mid$1 深度 2）。只把"匿名/非匿名"折成两档会让子宿主抢在父宿主
		// 之前执行——父宿主还没把改名传播下来，子宿主旧侧桶恒为空，叶子层被判 ANON-NOT-ALIGNED。
		// 稳定排序：同深度宿主保持插入顺序，绝不按名字重排——匿名宿主的处理顺序决定新槽位分配，
		// 按名字重排会改变对齐结果（实测：class-mode 探针的 prepend 场景因此拿到一个从未被定义的槽位）。
		sortHostsOuterFirst(hostOrder);
		Set<String> runtimeNamesBefore = new HashSet<>(loadedClassesMap.keySet());
		// 搬移统计必须整批汇合后再做：跨嵌套宿主的一次搬移（Outer$Inner$1 -> Outer$1）会把孤儿与
		// 未配对的新类分到两个宿主组里，逐宿主上报永远看不到对方。
		List<AnonMoveHost> anonMoveHosts = new ArrayList<>();
		for (int hostIdx = 0; hostIdx < hostOrder.size(); hostIdx++) {
			String hostName = hostOrder.get(hostIdx);
			String hostSlash = hostName.replace('.', '/');
			Map<String, byte[]> oldAnon = new HashMap<>(
				oldAnonsByHost.getOrDefault(hostName, Collections.emptyMap()));

			// 只取**本宿主**的匿名类（按类自身归属分组，绝不拿"本宿主前缀"去扫整批）
			Map<String, byte[]> newAnon = new HashMap<>(
				newAnonsByHost.getOrDefault(hostName, Collections.emptyMap()));

			if (oldAnon.isEmpty() && newAnon.isEmpty()) {
				continue;
			}

			// Kotlin 的匿名类命名是 Host$方法名$序号（Host$metricTable$1$1），不是 JVM 规范的
			// Host$序号链。名字判据只认尾部数字链，于是这一族会被归到一个**不存在的宿主**名下
			// （Host$metricTable）：对齐时宿主字节永远取不到，匹配只能靠退化证据，最后把新匿名类
			// 重新编号注入成幽灵类名，而真宿主照旧按原名重定义 —— 结果是"宿主 v2 + 闭包 v1"的
			// 混合态，后续调用直接 NoClassDefFoundError。
			//
			// 对着解析不出宿主字节的宿主做对齐，本质上没有可信证据，因此按 fail-closed 处理：
			// 整族（真实祖先宿主 + 其名下匿名类）移出本批，旧版本继续生效。
			String ghostHostAncestor = ghostHostAncestor(hostName, newBatchBytes);
			if (ghostHostAncestor != null) {
				rejectGhostHostFamily(newBatchBytes, classToPath, hostName, ghostHostAncestor, skips,
					oldAnon.size(), newAnon.size());
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
			Set<String> aliases = hostAliases.getOrDefault(hostName, Collections.emptySet());
			AnonClassAligner.setEnclosingAliases(aliases);
			// 宿主链改名：别名（编译期名）→ 本组宿主目标名，用于字节重写，保证属性与运行时类名自洽。
			Map<String, String> hostRenames = new LinkedHashMap<>();
			for (String alias : aliases) hostRenames.put(alias, hostName);
			AnonClassAligner.setHostRenames(hostRenames);
			try {
				res = alignAnonClasses(hostSlash, hostBytes, oldAnon, newAnon, oldRes, newRes,
				 BatchHotswapExecutor::hasLiveInstances,
				 // 避让上一批已分配、但还没被加载的对齐类编号（挂在 pendingAlignedClasses 里）。
				 // 传**快照**而非 keySet() 实时视图：视图会让编号分配随挂起集合的并发变化漂移，
				 // 破坏对齐结果的可确定性。
				 Set.copyOf(AnnotationTransformer.pendingAlignedClasses.keySet()));
			} catch (Throwable t) {
				AnonClassAligner.clearEnclosingAliases();
				AnonClassAligner.clearHostRenames();
				// docs/topology/04-tiers-and-rejection.md §4：以「宿主类 + 其下属全部匿名类」为原子单元整体拒绝。
				//
				// 这里**必须** continue 而不能让异常冒泡：冒泡会让整个 processChanges 中断，
				// 结果是"本轮所有类的热更静默失效"（异常最终只留在 ScheduledFuture 里，无人观测）；
				// 而对齐失败也不能只跳过匿名类 —— 该宿主的新字节码会引用到未对齐的 Foo$N。
				// 因此正确做法是连宿主一起移出本批，其余宿主照常处理。
				rejectHostGroup(newBatchBytes, classToPath, hostName, skips, t);
				continue;
			}

			// 门：改名的目标必须真有落地字节。对齐器可能在"拒绝猜配对"的同时仍给新匿名类分配了全新槽位，
			// 于是宿主字节被改成 NEW Host$5，却没有任何定义被装上（$5 既不在批次、也不在磁盘）——
			// 运行期构造宿主直接 NoClassDefFoundError。这种半生效必须整族 fail-closed，
			// 与"宿主 v2 + 闭包 v1"是同一条纪律。
			List<String> missingTargets = new ArrayList<>();
			for (String target : res.renameMap.values()) {
				String targetDot = Names.external(target);
				if (res.alignedAnonClasses.containsKey(target)) continue;
				if (runtimeNamesBefore.contains(targetDot)) continue;   // 目标就是活着的旧类，无需装字节
				missingTargets.add(target);
			}
			if (!missingTargets.isEmpty()) {
				rejectHostGroup(newBatchBytes, classToPath, hostName, skips,
				 new AnonClassAligner.AlignmentRejectedException(hostSlash,
					"aligned host would reference anonymous class(es) with no bytes to install: " + missingTargets));
				continue;
			}
			AnonClassAligner.clearEnclosingAliases();
			AnonClassAligner.clearHostRenames();
			if (res.alignedHostBytes != null) {
				newBatchBytes.put(hostName, res.alignedHostBytes);
			}
			// 对齐重命名后，目标类名对应的磁盘原始类名可能不同；指纹必须跟随原始磁盘文件。
			for (Map.Entry<String, String> rename : res.renameMap.entrySet()) {
				String rawDot    = rename.getKey().replace('/', '.');
				String targetDot = rename.getValue().replace('/', '.');
				// 已被外层改名传播登记过的目标（其磁盘名是改名前的内层名）不得被覆盖。
				targetToRawName.putIfAbsent(targetDot, rawDot);
			}
			// 外层匿名类改名后，把同一改名传播到还没处理的内层宿主组：
			// 旧内层挂在**原始**宿主名下（$3$innerObj$1），新内层挂在改名前的宿主名下（$4$innerObj$1），
			// 若不传播，内层对齐就会以 $4 为宿主去旧侧找 $3 的内层（old=0），随后 Stage A 判定
			// "宿主与其匿名类未一致重定义"整族回滚，存量内层实例永远停在旧版本。
			Map<String, Map<String, String>> ancestorPatches = new LinkedHashMap<>();
			for (Map.Entry<String, String> rename : res.renameMap.entrySet()) {
				hostAliases.computeIfAbsent(Names.external(rename.getValue()), k -> new java.util.LinkedHashSet<>())
					.add(Names.external(rename.getKey()));
			}
			for (Map.Entry<String, String> rename : res.renameMap.entrySet()) {
				String outerTarget = Names.external(rename.getValue());
				Map<String, String> childRenames = propagateAnonSubspace(
					Names.external(rename.getKey()), outerTarget,
					hostOrder, hostIdx, newAnonsByHost, newBatchBytes, classToPath, targetToRawName,
					runtimeNamesBefore);
				if (!childRenames.isEmpty()) ancestorPatches.put(outerTarget, childRenames);
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

			// [ANON-MOVED] 计数器：先收齐本宿主组的孤儿与真正未配对的新匿名类，等整批宿主对齐完成后
			// 再按顶层 NestHost 池汇合比对（跨嵌套宿主的搬移只有那时才能碰面）。
			anonMoveHosts.add(new AnonMoveHost(hostName, res, oldAnon, newAnon));

			// 先把对齐产物的字节登记进**批次的字节视图**：这些类马上会被 defineClass 出去，但在 redefine 之前
			// 它们既不在批次视图、也不在运行期字节、也没有源码基线。InitFix 提取字段初值时用来判定构造器权限的
			// HierarchyTreeOracle 正好只能查这三处 —— 查不到就把宿主整类判成 INITFIX-REFUSE（现场形态：
			// new field = object : IntUnaryOperator { ... }）。登记之后权限判定能正常完成。
			Path hostPath = classToPath.get(hostName);
			Class<?> alignHostClass = loadedClassesMap.get(hostName);
			if (DEBUG) log("[ANON-TARGETS] host=" + hostName + " aligned=" + res.alignedAnonClasses.keySet()
				+ " rename=" + res.renameMap + " inBatch=" + newBatchBytes.keySet().containsAll(res.alignedAnonClasses.keySet()));
			for (Map.Entry<String, byte[]> entry : res.alignedAnonClasses.entrySet()) {
				String targetSlash  = entry.getKey();
				String targetDot    = targetSlash.replace('/', '.');
				byte[] alignedBytes = entry.getValue();
				// 对齐产物的加载器 = 宿主的加载器。按路径推断会在"磁盘上没有同名文件"时退回按包名猜，
				// 装错加载器后宿主看不见它（定义成功但构造仍 NoClassDefFoundError）。
				if (alignHostClass != null) alignedAnonLoaders.put(targetDot, alignHostClass.getClassLoader());

				newBatchBytes.put(targetDot, alignedBytes);
				if (hostPath != null && !classToPath.containsKey(targetDot)) {
					classToPath.put(targetDot, hostPath);
				}

				tx.pendingAdds.put(targetSlash, alignedBytes);
				tx.cacheUpdates.put(targetDot, alignedBytes);
				tx.targetClasses.add(targetDot);
			}
			// 内层改名完成后修祖先字节：外层类的字节已按外层改名重写过一次（Host$1 → Host$2），
			// 但它对内层类的引用当时还停在编译期名字（NEW Host$1$1）。不修就是"外层叫 $2、里面仍 NEW $1$1"，
			// 运行期 NoClassDefFoundError。必须在对齐结果入批**之后**做，否则改的是尚未写入的旧条目。
			for (Map.Entry<String, Map<String, String>> patch : ancestorPatches.entrySet()) {
				byte[] ancestor = newBatchBytes.get(patch.getKey());
				if (ancestor == null) continue;
				byte[] fixed = AnonClassAligner.remapClass(ancestor, patch.getValue());
				if (fixed != ancestor) newBatchBytes.put(patch.getKey(), fixed);
			}
			transactions.put(hostName, tx);
		}

		// 全批宿主对齐完成后再汇总搬移读数：逐宿主上报会让跨嵌套宿主的搬移退化成 [ANON-ORPHAN]。
		reportAnonMoves(anonMoveHosts);

		// 变更集映射到目标类名：definitions 里的名字是 target 名，被 Mixin 重命名的类两者不同。
		// 位置在匿名类对齐**之后**：此时 renameMap 已全部登记完，不会漏掉后处理宿主的重命名。
		Map<String, String> rawToTargetName = new HashMap<>(targetToRawName.size());
		for (Map.Entry<String, String> rename : targetToRawName.entrySet()) {
			rawToTargetName.put(rename.getValue(), rename.getKey());
		}
		for (String raw : changedRawNames) {
			changedNames.add(rawToTargetName.getOrDefault(raw, raw));
		}
		if (DEBUG) log("[CHANGED] raw=" + changedRawNames + " target=" + changedNames);

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
				// 运行期名字 ≠ 磁盘名字：这是对齐器重编号/新合成的类（如新槽位 Host$5 来自磁盘上的
				// Host$3）。它的字节不是从磁盘那个文件读的，磁盘文件"没变"完全不能推出"这个类已定义过"。
				// 用原始名的指纹跳过它，会让宿主字节里 NEW 的名字永远没人定义 → 构造期 NoClassDefFoundError。
				boolean renamedTarget = !rawName.equals(className);
				Long   rawHash    = rawDiskHashes.get(rawName);
				long   newHash    = rawHash != null ? rawHash : calculateHash(bytecode);
				long   classNameKey = CRC64.hashString(rawName);

				// 检查指纹
				synchronized (fileDiskHashes) {
					long oldDiskHash = fileDiskHashes.get(classNameKey);
					if (!renamedTarget
					    && oldDiskHash != -1 && oldDiskHash != LongLongMap.NOT_FOUND && oldDiskHash == newHash) {
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
					// loader scope 只解析一次：本类的 lambda 自造名读写都按它分键（docs/status.md §5 风险 15）。
					// 这里用的是**这个类自己的定义 loader**，不是按名字反查出来的猜测值。
					final long alignScope = loaderScope(targetClass.getClassLoader());
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
							// 读：精确 scope 优先（写入者身份兜底），绝不按名字跨 loader 借条目。
							Set<String> oldInvented = INVENTED_SLOTS.lookup(alignScope, className);
							boolean hasInvented = oldInvented != null && !oldInvented.isEmpty();
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
									 oldInvented == null ? Set.of() : oldInvented, alignLoader);
									// 本轮算出的"自造名"先挂起，等 redefine 成功再落账（失败就丢弃，
									// 因为旧基线没前进，下一轮仍然是同一份旧字节）。
									// 从本轮快照里取，避免读到别的轮次（失败时快照的集合是空的，不是 null）。
									LambdaAligner.AlignmentResult aligned = LambdaAligner.LAST_RESULT;
									// 写：按已知的 alignScope 精确登记，成功回执（commitInvented）按同一 scope 取。
									stageInventedNames(alignScope, className,
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
						diffByHost.put(targetClass, diff);
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
					// path == null 是合法状态：对齐器注入的类、或来自直接传入的 activeWatchJar 的类都没有
					// watchDir 内的路径。tmpToJar 是 ConcurrentHashMap，传 null 键会抛
					// "Cannot invoke Object.hashCode() because key is null"，所以必须先判空。
					Path clHintPath = path != null && JarExtractor.tmpToJar().containsKey(path)
					 ? JarExtractor.resolveWatchDirPath(JarExtractor.tmpToJar().get(path), className)
					 : path;
					// clHintPath == null 表示来自直接传入的 activeWatchJar，无虚拟 watchDir 路径，
					// 退化到纯包名推断（findTargetClassLoader(className)），inject 照常走，lazy_load 挂 tmpDir 路径
					if (REDEFINE_MODE == RedefineMode.inject) {
						boolean injected = injectNewClass(className, clHintPath, bytecode,
							alignedAnonLoaders.get(className));
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
							// 必须可见：出生点补丁只有在"同一个加载器按名请求该类"时才生效，若宿主字节已经
							// NEW 了这个名字，兜底不成立就是构造期 NoClassDefFoundError。
							warn("[NEW] " + className + " could not be defined now and will rely on a birth patch;"
								+ " if the host already references this name, constructing the host fails with"
								+ " NoClassDefFoundError until the class is defined.");
							PendingLoadPatch.offerAny(className, bytecode);
						}
					} else if (REDEFINE_MODE == RedefineMode.lazy_load && UCP_APPEND) {
						String lazyRawName = targetToRawName.get(className);
						// 被对齐器重编号/新合成的匿名类，其**运行期名字**在磁盘上根本不存在
						// （磁盘上只有编译期名字，例如 $3 而不是 $5）。lazy_load 是靠"按名到挂载目录找
						// .class"生效的，所以这类类永远不会被找到：宿主字节码里写着 NEW $5，构造时直接
						// NoClassDefFoundError。凡是运行期名字 ≠ 磁盘名字（name-mismatch）的类，懒加载
						// 路径必须让位给主动注入 —— 这也是 inject 模式对同类问题的既有处置。
						boolean nameMismatch = needsActiveInjection(className, targetToRawName);
						boolean injectedHere = nameMismatch
							&& injectNewClass(className, clHintPath, bytecode, alignedAnonLoaders.get(className));
						if (injectedHere) {
							recordRuntimeBytecode(className, bytecode);
							injectedCount++;
							injectedClasses.add(className);
							String lazyRaw = lazyRawName;
							Long lazyHash = rawDiskHashes.get(lazyRaw);
							long diskHash = lazyHash != null ? lazyHash : calculateHash(bytecode);
							synchronized (fileDiskHashes) {
								fileDiskHashes.put(CRC64.hashString(lazyRaw), diskHash);
							}
							if (DEBUG) log("[NEW] " + className + " (renumbered; injected instead of lazy-loaded, disk name=" + lazyRaw + ")");
						} else {
							if (nameMismatch) {
								// 注入也失败：类仍未定义。挂载 + 出生点补丁是最后的兜底，
								// 但要如实点名——这一类靠名字在磁盘上永远找不到。
								warn("[NEW] " + className + " has runtime name " + className + " but disk name "
									+ lazyRawName + "; it is not on disk, so lazy loading cannot resolve it."
									+ " Injection failed too; the class stays undefined until the next round.");
							}
							ClassLoader loader = findTargetClassLoader(className, clHintPath);
							// lazy_load + jar：ClassLoader 推断用虚拟路径（clHintPath），
							// 实际挂载用真实 tmpDir 路径，否则 ClassLoader 找不到 class 文件
							Path mountPath = path != null && JarExtractor.tmpToJar().containsKey(path) ? path : clHintPath;
							if (mountPath != null) {
								PackageUnsealer.unsealClassPackage(loader, className);
								mountForClass(loader, mountPath);
							}
							recordRuntimeBytecode(className, bytecode);
							// lazy_load 下未改名的新类不会走 defineClass 注入点（注入站点才登记旁路字节），
							// 而 setBatchDefinitions 会清空批次视图重建为"仅 definitions"。若不在这里登记，
							// InitFix 的构造器/成员可见性判定读不到本批新类，宿主新增字段会被 fail-closed
							// 判成 INITFIX-REFUSE（现场：new field = object : IntUnaryOperator { ... }）。
							// 字节取批次条目（已完成对齐/祖先修补），与 eventual 加载的版本一致。
							InitFix.registerBatchClassBytes(loader, Map.of(className, bytecode));
							// 类尚未加载：登记出生点补丁，让它第一次加载时直接拿到这次编辑的字节
							// （加载器此刻还未知，按 ANY_SCOPE 登记；出生点的工作区归属校验兜底）。
							PendingLoadPatch.offerAny(className, bytecode);
							// lazy_load 只是挂载路径，类尚未真正加载生效 —— 指纹**不**落账，
							// 等它真正被加载/重定义时再记，否则会漏掉后续的首次加载。
						}
					}
				}
			} catch (Throwable e) {
				error("Failed to process " + path, e);
			}
		}
		InitFix.setBatchDefinitions(definitions);
		InitFix.finishBatchTransforms();
		// 新增字段写了初值、切片却提取不到（Kotlin vararg 集合工厂 / ?: / if / when）：
		// 重定义照常生效而补丁缺失，存量实例上这些字段停在类型零值 —— Kotlin 的非空 val 会因此
		// 静默变成 null。安全优先：整类移出本批并点名，提示重启，绝不产出"只补一半"的结果。
		dropUnexpressibleInitClasses(definitions, newBatchBytes, classToPath, transactions, skips, diffByHost);
		// 兜底：任何**已经拿到跳过裁决**的类都不许再进 redefine。级联失效发生在批次里多处（拒绝门、对齐失败、
		// 匿名类族连坐），逐处摘不够——探针实测过"宿主被踢出、匿名类仍单独换上新体"这种半生效。
		pruneSkippedDefinitions(definitions, skips);
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
			outcome = applyTwoPhase(definitions, oldEffectiveBytes, changedNames, transactions.values(), skips, injectedClasses);
		} else {
			if (HeapFieldWatch.enabled() && !definitions.isEmpty()) {
				HeapFieldWatch.snapshot("before-redefine", batchWatchLabel(definitions));
			}
			outcome = applyRedefinitions(definitions, changedNames, transactions.values(), skips);
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
			     + ", " + RedefineSummary.of(changedNames, successfulDefinitions, skips.size()).counters() + ")");
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
	 * 生产写入点：把本轮对齐算出的"自造槽位名"按 <b>已知的 alignScope</b> 暂存，等 redefine 成功后由
	 * {@link RemoldAgent#commitInvented(long, String)} 按同一 scope 回执落账。
	 *
	 * <p><b>为什么单独成方法</b>：写入侧的对齐逻辑埋在批次主流程里，无法在单测里驱动；把"怎么写"收敛成
	 * 唯一一处入口，回归用例就能驱动<b>同一条</b>写入路径（而不是自己拼一个等价调用）——否则
	 * "生产写入退回纯类名"这类退化在测试里完全不可见（{@code docs/status.md} §5 风险 15）。</p>
	 *
	 * @param alignScope 这个类自己的定义 loader 的 scope（{@code AlignmentMetaStore#loaderScopeId}）
	 */
	static void stageInventedNames(long alignScope, String className, Set<String> inventedNames) {
		pendingInvented.put(alignScope, className, inventedNames);
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

	/** 本轮 {@code @OnReload} 已经跑过的实现键：{@code internalOwner#name#desc}。 */
	private static final Set<String> RELOAD_INVOKED = ConcurrentHashMap.newKeySet();

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
		// 本轮已派发的"实现"（owner#name#desc）。@JvmStatic 让同一个 Kotlin 函数同时以静态转发器和
		// 实例方法两种身份带着 @OnReload 出现，按类派发就会执行两次；集合的键是**实现**而不是入口，
		// 于是转发器与它的目标共用同一个键，谁先跑谁生效。
		RELOAD_INVOKED.clear();
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

	/** 一个方法的"实现"键：{@code owner#name#desc}（owner 用内部名，避免点/斜杠两种写法分裂）。 */
	private static String implementationKey(Class<?> owner, Method m) {
		// 用 MethodType 拿 JVM 描述符：这里的 Type 是 java.lang.reflect.Type（同一文件里有 reflector 用），
		// 直接用 ASM 的 Type.getMethodDescriptor 会被遮蔽。
		String desc = MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString();
		return Names.internal(owner.getName()) + "#" + m.getName() + "#" + desc;
	}

	/**
	 * 若 {@code m} 是 {@code @JvmStatic} 生成的**纯转发器**，返回它委派的实现键；否则 {@code null}。
	 *
	 * <p>判据刻意收窄：方法体除返回外只有一条 INVOKE，且被调方法的 owner+name+desc 与本方法同名同签名
	 * （Kotlin 的 {@code object} 转发器调 {@code Foo.reload()}，{@code companion object} 的转发器调
	 * {@code Foo$Companion.reload()}）。有其它指令（算术、字段写、分支）就不是转发器，绝不按名字猜。</p>
	 */
	private static String forwarderTargetKey(Class<?> clazz, Method m) {
		if (m.getParameterCount() != 0) return null;
		byte[] bytes = RemoldAgent.getRuntimeBytecode(clazz.getName());
		if (bytes == null) return null;
		ClassNode node = new ClassNode();
		try {
			new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (Throwable t) {
			return null;
		}
		String desc = MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString();
		for (MethodNode mn : node.methods) {
			if (!mn.name.equals(m.getName()) || !mn.desc.equals(desc)) continue;
			String calleeOwner = null;
			int invokes = 0;
			for (AbstractInsnNode insn : mn.instructions) {
				int op = insn.getOpcode();
				if (op < 0 || op == Opcodes.RETURN || op == Opcodes.IRETURN || op == Opcodes.ARETURN
				    || op == Opcodes.LRETURN || op == Opcodes.FRETURN || op == Opcodes.DRETURN) {
					continue;
				}
				if (insn instanceof MethodInsnNode mi) {
					invokes++;
					if (invokes > 1) return null;
					if (!mi.name.equals(m.getName()) || !mi.desc.equals(desc)) return null;
					calleeOwner = mi.owner;
					continue;
				}
				if (insn.getOpcode() == Opcodes.GETFIELD || insn.getOpcode() == Opcodes.PUTFIELD
				    || insn.getOpcode() == Opcodes.PUTSTATIC) {
					return null;                     // 写了字段：有副作用，不是纯转发器
				}
				if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode
				    || insn instanceof LookupSwitchInsnNode) {
					return null;
				}
			}
			if (invokes != 1 || calleeOwner == null) return null;
			return calleeOwner + "#" + m.getName() + "#" + desc;
		}
		return null;
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
			// @JvmStatic 转发器：方法体只是"取单例/伴生对象 → 调同名同描述符的方法"。它的目标才是实现，
			// 两边共用一个实现键，因此只执行一次（见 RELOAD_INVOKED 的注释）。
			String forwardKey = forwarderTargetKey(clazz, m);
			String ownKey = implementationKey(clazz, m);
			if (forwardKey != null && !RELOAD_INVOKED.add(forwardKey)) {
				if (DEBUG) log("[Reload] Skipping @OnReload static forwarder " + m.getName()
				               + " on " + clazz.getName() + ": its target already ran this round");
				continue;
			}
			if (forwardKey == null && !RELOAD_INVOKED.add(ownKey)) {
				if (DEBUG) log("[Reload] Skipping @OnReload static method " + m.getName()
				               + " on " + clazz.getName() + ": already ran this round");
				continue;
			}
			try {
				m.invoke(null);
				if (DEBUG) log("[Reload] Invoked @OnReload static method: " + m.getName());
			} catch (Throwable e) {
				// 调用失败要放掉键，否则下一轮会把"没跑成"当成"跑过了"。
				RELOAD_INVOKED.remove(forwardKey != null ? forwardKey : ownKey);
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
				if (!RELOAD_INVOKED.add(implementationKey(clazz, singleMethod))) {
					if (DEBUG) log("[Reload] Skipping instance @OnReload " + singleMethod.getName()
					               + " on " + clazz.getName() + ": already ran this round");
				} else {
					for (Object obj : instances) {
						invokeReloadMethod(obj, singleMethod, singleHandle);
					}
				}
			} else {
				for (int i = 0; i < instanceMethods.size(); i++) {
					Method m = instanceMethods.get(i);
					if (!RELOAD_INVOKED.add(implementationKey(clazz, m))) {
						if (DEBUG) log("[Reload] Skipping instance @OnReload " + m.getName()
						               + " on " + clazz.getName() + ": already ran this round");
						continue;
					}
					for (Object obj : instances) {
						invokeReloadMethod(obj, m, instanceHandles[i]);
					}
				}
			}
		}
	}

	static String resolveAnonHostCandidate(
	 String cName, byte[] bytes, Set<String> hostClassesToAlign, String singleHost
	) {
		int len = cName.length();
		if (len == 0) return null;
		char last = cName.charAt(len - 1);
		if (last < '0' || last > '9') return null;
		if (singleHost != null) {
			int hostLen = singleHost.length();
			if (len > hostLen + 1 && cName.charAt(hostLen) == '$' && cName.startsWith(singleHost)) {
				return singleHost;   // 单宿主模式：宿主已指定，无需解析
			}
		}
		String host = AnonHostResolver.hostOf(cName, bytes);
		return (host != null && !host.equals(Names.internal(cName)) && hostClassesToAlign.contains(host))
		 ? host : null;
	}

	/**
	 * 宿主是否真的存在：批次里有新字节、已加载、或能取到运行期/源码基线字节。
	 *
	 * <p>只有名字判据（{@link AnonClassAligner#batchAnonHost}）不够：Kotlin 的
	 * {@code Host$方法名$序号} 形态会推断出 {@code Host$方法名} 这种**不存在的宿主**。
	 * 幽灵宿主上的对齐没有可信证据，调用方必须走整族拒绝通道。</p>
	 */
	static boolean isResolvableAnonHost(String hostName, Map<String, byte[]> newBatchBytes) {
		if (hostName == null || hostName.isEmpty()) return false;
		if (newBatchBytes != null && newBatchBytes.containsKey(hostName)) return true;
		if (loadedClassesMap.containsKey(hostName)) return true;
		if (getRuntimeBytecode(hostName) != null) return true;
		return getSourceBytecode(hostName) != null;
	}

	/**
	 * 推断出的宿主不可解析时，返回可用于拒绝通道的**真实祖先宿主**；宿主本身可解析时返回 {@code null}。
	 *
	 * <p>逐级剥掉尾部 {@code $segment}：{@code Host$metricTable} → {@code Host}。一个祖先都解析不出来时
	 * 返回入参本身 —— 调用方据此只移出该前缀下的族（没有宿主可移）。</p>
	 */
	/**
	 * 该类在 {@code lazy_load} 模式下是否必须"主动注入"，而不是指望按名懒加载。
	 *
	 * <p>判据只有一条：批次里的**运行期名字**与**磁盘名字**是否一致。被对齐器重编号/新合成的匿名类，
	 * 磁盘上只有编译期名字（如 {@code Host$3}），而宿主新字节码引用的是新名字（如 {@code Host$5}）；
	 * 懒加载是按名到挂载目录找 {@code .class}，这类名字永远找不到，构造时直接 NoClassDefFoundError。
	 * 名字一致（含没有任何重映射记录）时维持原懒加载语义。</p>
	 */
	static boolean needsActiveInjection(String className, Map<String, String> targetToRawName) {
		if (className == null || targetToRawName == null) return false;
		String raw = targetToRawName.get(className);
		return raw != null && !raw.equals(className);
	}

	/**
	 * 旧侧匿名类的宿主解析。
	 *
	 * <p>顺序：运行期字节属性（须与名字自洽）→ 源基线字节属性（同样须自洽）→ 名字父链上第一个
	 * "已加载或已在本轮待对齐集合里"的祖先 → 兜底用属性。</p>
	 */
	static String resolveOldAnonHost(String cName, byte[] runtimeBytes, byte[] fallbackBytes,
	                                 Map<String, Class<?>> loadedClasses, Set<String> pendingHosts) {
		String cSlash = Names.internal(cName);
		String attr = AnonHostResolver.hostOf(cName, runtimeBytes);
		if (attr != null && cSlash.startsWith(attr + "$")) return Names.external(attr);
		String attrFallback = AnonHostResolver.hostOf(cName, fallbackBytes);
		if (attrFallback != null && cSlash.startsWith(attrFallback + "$")) return Names.external(attrFallback);
		// 沿名字父链逐段上溯。**必须按 '$' 切段**，不能用"剥尾部数字"（textHost）：中间段是属性名时没有
		// 数字可剥，textHost 会原地返回，循环退化成死循环（实测直接把探针挂住 10 分钟）。
		String parent = cSlash;
		while (true) {
			int lastDollar = parent.lastIndexOf('$');
			if (lastDollar <= 0) break;
			parent = parent.substring(0, lastDollar);
			String parentDot = Names.external(parent);
			if ((loadedClasses != null && loadedClasses.containsKey(parentDot))
			    || (pendingHosts != null && (pendingHosts.contains(parentDot) || pendingHosts.contains(parent)))) {
				return parentDot;
			}
		}
		return attr != null ? Names.external(attr) : (attrFallback != null ? Names.external(attrFallback) : null);
	}

	/** 字节码自报的内部类名；解析失败返回 {@code null}（调用方据此退回运行期字节）。 */
	static String selfInternalName(byte[] bytes) {
		if (bytes == null || bytes.length == 0) return null;
		try {
			return new org.objectweb.asm.ClassReader(bytes).getClassName();
		} catch (Throwable t) {
			return null;
		}
	}


	/** 单个宿主组的搬移统计输入：孤儿/未配对集合 + 两侧字节桶。 */
	static final class AnonMoveHost {
		final String hostName;
		final AnonClassAligner.Result res;
		final Map<String, byte[]> oldAnon;
		final Map<String, byte[]> newAnon;

		AnonMoveHost(String hostName, AnonClassAligner.Result res,
		             Map<String, byte[]> oldAnon, Map<String, byte[]> newAnon) {
			this.hostName = hostName;
			this.res = res;
			this.oldAnon = oldAnon;
			this.newAnon = newAnon;
		}
	}

	/** 单个待比对的匿名类（孤儿旧类或未配对的新类）。 */
	private static final class AnonMoveTarget {
		final String internalName;
		final byte[] bytes;
		final String hostName;

		AnonMoveTarget(String internalName, byte[] bytes, String hostName) {
			this.internalName = internalName;
			this.bytes = bytes;
			this.hostName = hostName;
		}
	}

	/**
	 * 输出 {@code [ANON-MOVED]} 与 {@code [ANON-ORPHAN]} 读数（只统计，不改变任何行为）。
	 *
	 * <p>计数口径（避免攒出脏数据）：在同一<b>顶层 NestHost 池</b>内同时存在<b>孤儿旧匿名类</b>与
	 * <b>真正未被配对的新匿名类</b>，且两者结构指纹（父类 + 接口集合）相等，才算一次搬移候选；
	 * 普通"删旧加新"结构指纹不同，不会被计入。不同顶层 NestHost（{@code ClassA$1} 与
	 * {@code ClassB$1}）绝不互相匹配。</p>
	 *
	 * <p>必须整批汇合后再统计：跨嵌套宿主的一次搬移（{@code Outer$Inner$movedOp$1} ->
	 * {@code Outer$movedOp$1}）会把孤儿与未配对的新类分到两个宿主组里，逐宿主上报永远看不到对方。</p>
	 *
	 * <p>两维读数：{@code live=N}（0 表示没有存量实例，本来就不需要复用）与 {@code gate=PASS|REJECT(reason)}
	 * （例如 {@code capture-grew}、{@code this0-type-changed}），后者直接回答"能过复用门禁的占比"。
	 * {@code scope=same-host|cross-host} 与 {@code candidateHost=} 区分搬移是宿主内还是跨嵌套宿主。</p>
	 */
	static void reportAnonMoves(List<AnonMoveHost> hosts) {
		Map<String, List<AnonMoveTarget>> orphansByNest = new LinkedHashMap<>();
		Map<String, List<AnonMoveTarget>> newByNest = new LinkedHashMap<>();
		for (AnonMoveHost host : hosts) {
			if (host == null || host.res == null) continue;
			Set<String> orphans = host.res.orphanOldClasses == null
				? Set.<String>of() : host.res.orphanOldClasses;
			for (String orphan : orphans) {
				String orphanSlash = Names.internal(orphan);
				byte[] orphanBytes = lookupAnonBytes(host.oldAnon, orphanSlash);
				String nestHost = topLevelNestHost(orphanBytes, orphanSlash);
				orphansByNest.computeIfAbsent(nestHost, k -> new ArrayList<>())
					.add(new AnonMoveTarget(orphanSlash, orphanBytes, host.hostName));
			}
			Set<String> unmatched = host.res.unmatchedNewClasses == null
				? Set.<String>of() : host.res.unmatchedNewClasses;
			for (String raw : unmatched) {
				String newSlash = Names.internal(raw);
				byte[] newBytes = lookupAnonBytes(host.newAnon, newSlash);
				String nestHost = topLevelNestHost(newBytes, newSlash);
				newByNest.computeIfAbsent(nestHost, k -> new ArrayList<>())
					.add(new AnonMoveTarget(newSlash, newBytes, host.hostName));
			}
		}

		for (Map.Entry<String, List<AnonMoveTarget>> entry : orphansByNest.entrySet()) {
			List<AnonMoveTarget> candidates = newByNest.getOrDefault(entry.getKey(), Collections.emptyList());
			for (AnonMoveTarget orphan : entry.getValue()) {
				String orphanShape = shapeOf(orphan.bytes, orphan.internalName);
				boolean reported = false;
				for (AnonMoveTarget candidate : candidates) {
					String candidateShape = shapeOf(candidate.bytes, candidate.internalName);
					if (orphanShape == null || !orphanShape.equals(candidateShape)) continue;
					int live = hasLiveInstances(Names.external(orphan.internalName)) ? 1 : 0;
					boolean captureGrew = captureTableGrew(orphan.bytes, candidate.bytes);
					String gate = live == 0 ? "PASS(no-live-instances)"
						: captureGrew ? "REJECT(capture-grew)" : "REJECT(live-instances;cause-unclassified)";
					String scope = orphan.hostName.equals(candidate.hostName) ? "same-host" : "cross-host";
					info("[ANON-MOVED] host=" + Names.internal(orphan.hostName)
					    + " orphan=" + orphan.internalName
					    + " candidate=" + candidate.internalName + " shape=" + orphanShape
					    + " live=" + live + " gate=" + gate
					    + " scope=" + scope
					    + " candidateHost=" + Names.internal(candidate.hostName));
					reported = true;
				}
				if (!reported) {
					info("[ANON-ORPHAN] host=" + Names.internal(orphan.hostName) + " orphan=" + orphan.internalName
					    + " shape=" + orphanShape
					    + " live=" + (hasLiveInstances(Names.external(orphan.internalName)) ? 1 : 0)
					    + " reason=no-structurally-equal-new-anon");
				}
			}
		}
	}

	/**
	 * 兼容旧调用点/单测的单宿主重载：语义等同只把这一组交给整批汇总。
	 */
	static void reportAnonMoves(String hostName, AnonClassAligner.Result res,
	                            Map<String, byte[]> oldAnon, Map<String, byte[]> newAnon) {
		reportAnonMoves(Collections.singletonList(new AnonMoveHost(hostName, res, oldAnon, newAnon)));
	}

	/** 字节桶的键可能是点分名或内部名：两种形态都试，避免结构指纹意外变成 {@code null}。 */
	private static byte[] lookupAnonBytes(Map<String, byte[]> map, String internalName) {
		if (map == null || internalName == null) return null;
		byte[] bytes = map.get(internalName);
		if (bytes != null) return bytes;
		bytes = map.get(Names.external(internalName));
		if (bytes != null) return bytes;
		return map.get(Names.internal(internalName));
	}

	/**
	 * 顶层 NestHost：优先读类字节码的 {@code NestHost} 属性，缺失/解析失败时退回"首个 {@code $}
	 * 之前的内部名"（即最外层类前缀）。同名嵌套宿主因此归入同一个搬移池。
	 */
	static String topLevelNestHost(byte[] bytes, String selfInternalName) {
		String selfSlash = Names.internal(selfInternalName);
		if (bytes != null && bytes.length > 0) {
			try {
				ClassNode cn = new ClassNode();
				new ClassReader(bytes).accept(cn,
					ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				if (cn.nestHostClass != null && !cn.nestHostClass.isEmpty()) {
					return Names.internal(cn.nestHostClass);
				}
			} catch (Throwable ignored) {
				// 解不开就退回名字前缀；统计不因坏字节而中断。
			}
		}
		int dollar = selfSlash.indexOf('$');
		return dollar > 0 ? selfSlash.substring(0, dollar) : selfSlash;
	}

	/** 结构指纹：父类 + 排序后的接口集合；解析失败返回 {@code null}（不计数，避免脏数据）。 */
	static String shapeOf(byte[] bytes, String selfSlash) {
		if (bytes == null || bytes.length == 0) return null;
		try {
			org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);
			String superName = cr.getSuperName();
			String[] interfaces = cr.getInterfaces();
			List<String> sorted = new ArrayList<>();
			if (interfaces != null) Collections.addAll(sorted, interfaces);   // 注意：List.of(String[]) 会被当成单个元素
			Collections.sort(sorted);
			return superName + "|" + String.join(",", sorted);
		} catch (Throwable t) {
			return null;
		}
	}

	/** 捕获表是否变大（新增合成捕获字段 = 典型"需要布局门"的形态）。 */
	static boolean captureTableGrew(byte[] oldBytes, byte[] newBytes) {
		return captureFieldCount(newBytes) > captureFieldCount(oldBytes);
	}

	static int captureFieldCount(byte[] bytes) {
		if (bytes == null || bytes.length == 0) return 0;
		try {
			org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);
			org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
			cr.accept(cn, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
			int n = 0;
			for (org.objectweb.asm.tree.FieldNode f : cn.fields) {
				if ((f.access & Opcodes.ACC_SYNTHETIC) != 0) n++;
			}
			return n;
		} catch (Throwable t) {
			return 0;
		}
	}

	/** 匿名类层级深度：名字里 {@code $} 越多越深，用于"由外向内"处理宿主组。 */
	static int anonDepth(String dotName) {
		if (dotName == null) return 0;
		int depth = 0;
		for (int i = 0; i < dotName.length(); i++) if (dotName.charAt(i) == '$') depth++;
		return depth;
	}

	/**
	 * 把宿主组按"由外向内"排序：真实宿主先于匿名宿主，父匿名宿主先于子匿名宿主。
	 *
	 * <p>{@link #anonDepth(String)} 对每一层嵌套都至少多一个 {@code $}，因此深度严格单调递增：
	 * Kotlin {@code Host$1$mid$1}、Java {@code Host$1$1} 与普通内部类 {@code Host$Inner$1} 都满足
	 * "祖先深度 &lt; 后代深度"。</p>
	 *
	 * <p>{@link List#sort} 是稳定排序：深度相同的宿主保持原插入顺序。这一点是硬约束——匿名宿主的
	 * 处理顺序决定新槽位分配，按名字重排会改变对齐结果（class-mode prepend 探针）。</p>
	 */
	static void sortHostsOuterFirst(List<String> hostOrder) {
		hostOrder.sort(Comparator.comparingInt(BatchHotswapExecutor::anonDepth));
	}

	/** 名字 {@code name} 若落在 {@code rawDot} 的子空间里，返回换到 {@code targetDot} 子空间后的名字。 */
	static String renameSubspace(String name, String rawDot, String targetDot) {
		if (name == null || rawDot == null || targetDot == null || rawDot.equals(targetDot)) return name;
		if (name.equals(rawDot)) return targetDot;
		String prefix = rawDot + "$";
		return name.startsWith(prefix) ? targetDot + name.substring(rawDot.length()) : name;
	}

	/**
	 * 把外层匿名类的一次改名传播到它名下、还没处理的内层宿主组。
	 *
	 * <p>背景（真机现场）：源码里外层匿名对象换了位置，编译产物变成 {@code Host$4}，对齐器把它匹配回
	 * 存量槽位 {@code Host$3}；而它的内层嵌套匿名类编译成 {@code Host$4$innerObj$1}，旧侧对应的是
	 * {@code Host$3$innerObj$1}。内层作为**独立宿主组**再次进入对齐时，宿主名若还停在改名前的
	 * {@code Host$4}，旧侧就什么都找不到（{@code level 2: old=0, new=1}），随后 Stage A 以
	 * "宿主与其匿名类未一致重定义"整族回滚，存量内层实例永远停在旧版本。</p>
	 *
	 * <p>只对"目标名是**已存在的运行期类**"的改名传播（即匹配回旧槽位）。若外层拿到的是**全新槽位**
	 * （旧外层因布局门拒绝而无法原地更新），老内层仍属于老外层，把新内层搬过去只会造成跨代串位，
	 * 因此不做传播——那种情况由布局门/注入路径处理。</p>
	 */
	static Map<String, String> propagateAnonSubspace(String rawDot, String targetDot, List<String> hostOrder, int fromIdx,
	                                  Map<String, Map<String, byte[]>> newAnonsByHost,
	                                  Map<String, byte[]> newBatchBytes,
	                                  Map<String, Path> classToPath,
	                                  Map<String, String> targetToRawName,
	                                  Set<String> runtimeNamesBefore) {
		Map<String, String> childRenames = new LinkedHashMap<>();
		if (rawDot == null || targetDot == null || rawDot.equals(targetDot)) return childRenames;
		if (runtimeNamesBefore == null || !runtimeNamesBefore.contains(targetDot)) return childRenames;

		// 1) 待处理宿主改名（只改还没轮到的，已处理过的不能再动）
		for (int i = fromIdx + 1; i < hostOrder.size(); i++) {
			String renamed = renameSubspace(hostOrder.get(i), rawDot, targetDot);
			if (!renamed.equals(hostOrder.get(i))) hostOrder.set(i, renamed);
		}

		// 2) 新侧桶：桶键（宿主名）与桶内类名一起迁移，字节同步重写
		Map<String, String> subspaceMap = new LinkedHashMap<>();
		subspaceMap.put(Names.internal(rawDot), Names.internal(targetDot));
		for (String bucketHost : new ArrayList<>(newAnonsByHost.keySet())) {
			String newBucketHost = renameSubspace(bucketHost, rawDot, targetDot);
			Map<String, byte[]> bucket = newAnonsByHost.get(bucketHost);
			if (bucket == null) continue;
			Map<String, byte[]> moved = new HashMap<>();
			for (Map.Entry<String, byte[]> e : bucket.entrySet()) {
				String newName = renameSubspace(e.getKey(), rawDot, targetDot);
				if (newName.equals(e.getKey())) { moved.put(e.getKey(), e.getValue()); continue; }
				subspaceMap.put(Names.internal(e.getKey()), Names.internal(newName));
				childRenames.put(Names.internal(e.getKey()), Names.internal(newName));
				byte[] remapped = AnonClassAligner.remapClass(e.getValue(), subspaceMap);
				subspaceMap.remove(Names.internal(e.getKey()));
				moved.put(newName, remapped);
				moveBatchEntry(newBatchBytes, classToPath, targetToRawName, e.getKey(), newName);
				// 迁移后再把**改过名**的字节写回批次：批次里必须留改名后的字节，
				// 否则内层组的 newRes 取到的是编译期名字的旧字节，对齐出来仍是 $4$...，改名等于白做。
				newBatchBytes.put(newName, remapped);
			}
			if (newBucketHost.equals(bucketHost)) {
				newAnonsByHost.put(bucketHost, moved);
			} else {
				newAnonsByHost.remove(bucketHost);
				newAnonsByHost.computeIfAbsent(newBucketHost, k -> new HashMap<>()).putAll(moved);
			}
		}

		return childRenames;
	}

	/** 把批次里一个类的登记整体改到新名字下（字节、路径、磁盘名映射三处保持一致）。 */
	static void moveBatchEntry(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                           Map<String, String> targetToRawName, String fromDot, String toDot) {
		if (fromDot == null || toDot == null || fromDot.equals(toDot)) return;
		byte[] bytes = newBatchBytes.remove(fromDot);
		if (bytes != null) newBatchBytes.put(toDot, bytes);
		Path path = classToPath.remove(fromDot);
		if (path != null) classToPath.put(toDot, path);
		String raw = targetToRawName.remove(fromDot);
		if (raw != null) targetToRawName.put(toDot, raw);
	}

	static String ghostHostAncestor(String hostName, Map<String, byte[]> newBatchBytes) {
		if (hostName == null) return null;
		if (isResolvableAnonHost(hostName, newBatchBytes)) return null;
		int cut = hostName.lastIndexOf('$');
		while (cut > 0) {
			String ancestor = hostName.substring(0, cut);
			if (isResolvableAnonHost(ancestor, newBatchBytes)) return ancestor;
			cut = ancestor.lastIndexOf('$');
		}
		return hostName;
	}

	/**
	 * 幽灵宿主族的 fail-closed 拒绝：真实祖先宿主 + 其名下全部匿名类整族移出本批。
	 *
	 * <p>不能只移出血统里的"匿名类"：真宿主这一轮的新字节引用的是重编号后的新匿名类名，
	 * 若宿主照旧重定义、匿名类却被移出，调用点就链接到不存在的类（{@code NoClassDefFoundError}）。
	 * 原子单元必须是"宿主 + 族"。</p>
	 */
	private static void rejectGhostHostFamily(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                          String ghostHost, String realHost, RoundSkips skips,
	                                          int oldCount, int newCount) {
		List<String> dropped = new ArrayList<>();
		if (!ghostHost.equals(realHost)) {
			dropped.addAll(dropHostGroup(newBatchBytes, classToPath, realHost));
		}
		// Kotlin 宿主名后缀族（Host$方法名$序号...）不被名字判据认作匿名类，必须按前缀显式移出；
		// 否则它们会按原名重定义 —— 那正是"编号位移篡夺槽位"本身。
		for (String cn : collectHostSuffixFamily(newBatchBytes.keySet(), realHost)) {
			if (dropped.contains(cn)) continue;
			dropBatchClass(newBatchBytes, classToPath, cn);
			dropped.add(cn);
		}
		String reason = "inferred host " + ghostHost + " resolves to no class"
		 + " (Kotlin host-suffix naming: Host$method$1$1)";
		warn("[ANON_ALIGN] " + reason + "; resolved real host: " + realHost
		     + ". Kept out of this batch: " + dropped.size() + " class(es)"
		     + " (recorded old anonymous classes: " + oldCount + ", new: " + newCount + ")."
		     + " Nothing in this family was redefined — the previous version keeps running."
		     + " Recompile without moving existing anonymous classes, or restart to adopt the new layout.");
		skips.addGroup(dropped, realHost, RoundSkips.verdict("ANON-GHOST-HOST", reason,
			"recompile without moving anonymous classes, or restart"));
	}

	/** 批次中属于 {@code host$...} 族、且尾部是数字链的类名（含跨级 Kotlin 形态 Host$m$1$1）。 */
	private static List<String> collectHostSuffixFamily(Set<String> batchKeys, String hostName) {
		List<String> out = new ArrayList<>();
		for (String cn : batchKeys) {
			// 判据只有一份：与 dropHostGroup 共用 AnonClassAligner.isHostSuffixFamilyMember，
			// 两条拒绝通道对"谁属于这一族"的回答必须完全一致。
			if (AnonClassAligner.isHostSuffixFamilyMember(hostName, cn)) out.add(cn);
		}
		return out;
	}

	/**
	 * 拒绝门：新增字段的初值表达式存在、但 {@code InitFixSliceExtractor} 提取不到。
	 *
	 * <p>为什么整类拒绝而不是只跳过字段：重定义会把新字段表换上，而补丁生成不了，存量实例读到类型
	 * 零值。对 Java 的原始类型这是"和源码语义一致"，对 Kotlin 的非空 {@code val} 就是"声明非空却
	 * 读到 null"，NPE 出现在离热更很远的位置（{@code tests/ktfix} 实测：vararg 工厂与 elvis/when
	 * 两支都是这个形态）。整类拒绝让损失止于"这个类需要重启"，而不是扩散成运行期空指针。</p>
	 *
	 * <p>只认 {@link InitFix#unexpressibleNewFieldInits} 判定出的那一族；初值来自构造器参数或无初值
	 * 的字段不受影响。</p>
	 */
	private static void dropUnexpressibleInitClasses(List<ClassDefinition> definitions,
	                                                Map<String, byte[]> newBatchBytes,
	                                                Map<String, Path> classToPath,
	                                                Map<String, AlignmentTransaction> transactions,
	                                                RoundSkips skips,
	                                                Map<Class<?>, ClassDiffUtil.ClassDiff> diffByHost) {
		if (definitions.isEmpty() || diffByHost.isEmpty()) return;
		List<ClassDefinition> kept = new ArrayList<>(definitions.size());
		for (ClassDefinition def : definitions) {
			Class<?> host = def.getDefinitionClass();
			Map<String, String> refused = InitFix.unexpressibleNewFieldInits(host, diffByHost.get(host));
			if (refused.isEmpty()) {
				kept.add(def);
				continue;
			}
			String hostName = host.getName();
			StringBuilder detail = new StringBuilder();
			for (Map.Entry<String, String> e : refused.entrySet()) {
				if (detail.length() > 0) detail.append("; ");
				detail.append(e.getKey()).append(" -> ").append(e.getValue());
			}
			warn("[INITFIX-REFUSE] " + hostName + ": new field initializer cannot be extracted ("
			     + detail + "). This class is kept out of this batch — redefining it would leave the field"
			     + " at its type default, which reads as null for a Kotlin non-null val."
			     + " RESTART to adopt this class.");
			skips.add(hostName, RoundSkips.verdict("INITFIX-UNEXPRESSIBLE-INIT", detail.toString(),
				"restart to adopt this class"));
			// 对齐结果一步都不许提交：类没进 redefine，事务却 commit 会把未生效字节变成基线（风险 15/16）。
			AlignmentTransaction tx = transactions.remove(hostName);
			if (tx != null) tx.rollback(true);
			dropBatchClass(newBatchBytes, classToPath, hostName);
			// 只丢暂存补丁：这个类根本没进 redefine，记成"redefine failed"债务是误导。
			InitFix.discardPending(host);
		}
		if (kept.size() == definitions.size()) return;

		// ---- 级联失效 ----
		// 一个类被踢出批次后，"批次内没人读它删掉的成员"这个前提也随之失效：LAYOUT-WAIVE 是按"读者在本批里"
		// 给出的，读者被踢走了，waive 就不能再算数。现场形态（Mindustry + JBR 25）：
		//   · MovedOuterTarget 被 INITFIX-REFUSE 踢出，MovedOuterTarget$Inner 仍执行了 getMovedOp() 的删除
		//     → 旧 MovedOuterTarget.eval() 运行期 NoSuchMethodError；
		//   · CounterAnonTarget 被踢出，其子匿名类仍把 <init>(LCounterAnonTarget;)V 改成 (LCounterAnonTarget;I)V
		//     → 新建实例 NoSuchMethodError。
		// 因此这里做两件事：①把被踢宿主的匿名类族一起移出本批；②把"删了成员"的其他批次类一并移出（保守：
		// 它们的无读者证明可能依赖被踢的那个类）。
		Set<String> droppedHosts = new LinkedHashSet<>();
		for (ClassDefinition def : definitions) droppedHosts.add(def.getDefinitionClass().getName());
		List<ClassDefinition> survivors = new ArrayList<>(kept.size());
		for (ClassDefinition def : kept) {
			Class<?> host = def.getDefinitionClass();
			String hostName = host.getName();
			ClassDiffUtil.ClassDiff diff = diffByHost.get(host);
			boolean removedSomething = diff != null
				&& (!diff.removedMethods.isEmpty() || !diff.changedFields.isEmpty());
			if (removedSomething) {
				warn("[BATCH-CASCADE] " + hostName + ": a host in this batch was dropped (INITFIX-UNEXPRESSIBLE-INIT)"
				     + " and this class removes member(s) [" + (diff == null ? "" :
				        String.join(", ", diff.removedMethods)) + "]. The no-in-batch-reader proof that allowed the"
				     + " removal no longer holds, so this class is kept out of this batch too. RESTART to adopt it.");
				skips.add(hostName, RoundSkips.verdict("BATCH-CASCADE-LAYOUT",
					"removal was waived because a reader was in this batch; that reader was dropped",
					"restart to adopt this class"));
				AlignmentTransaction tx = transactions.remove(hostName);
				if (tx != null) tx.rollback(true);
				dropBatchClass(newBatchBytes, classToPath, hostName);
				dropFamily(definitions, newBatchBytes, classToPath, skips, hostName);
				InitFix.discardPending(host);
				continue;
			}
			survivors.add(def);
		}
		// 被踢宿主的匿名类族：它们的构造器/方法签名依赖宿主的新形态，宿主不更新时它们也不能单独更新。
		for (String dropped : droppedHosts) {
			dropFamily(definitions, newBatchBytes, classToPath, skips, dropped);
		}
		definitions.clear();
		definitions.addAll(survivors);
	}

	/** 把 {@code host} 名下的匿名类族（{@code host$...}）一起移出本批，并记录原因。 */
	private static void dropFamily(List<ClassDefinition> definitions, Map<String, byte[]> newBatchBytes,
	                               Map<String, Path> classToPath, RoundSkips skips, String hostName) {
		String prefix = hostName + "$";
		Set<String> family = new LinkedHashSet<>();
		for (String key : new ArrayList<>(newBatchBytes.keySet())) {
			String dot = Names.external(key);
			if (dot.startsWith(prefix)) family.add(dot);
		}
		// 只从字节映射里摘掉是不够的：redefine 用的是 definitions，漏摘就会单独把匿名类换上去（探针实测：
		// 宿主被踢出，匿名类仍执行到新体，运行期靠旧宿主代码调用缺失成员直接 NoSuchMethodError）。
		for (ClassDefinition def : new ArrayList<>(definitions)) {
			String dot = def.getDefinitionClass().getName();
			if (dot.startsWith(prefix)) family.add(dot);
		}
		for (String dot : family) {
			skips.add(dot, RoundSkips.verdict("BATCH-CASCADE-FAMILY",
				"the host " + hostName + " was dropped from this batch",
				"restart to adopt this class"));
			dropBatchClass(newBatchBytes, classToPath, dot);
			definitions.removeIf(def -> def.getDefinitionClass().getName().equals(dot));
		}
	}


	/** 把已经登记了跳过裁决的类从 redefine 列表里摘掉（最后一道自检，不做判定、只做一致性）。 */
	private static void pruneSkippedDefinitions(List<ClassDefinition> definitions, RoundSkips skips) {
		if (definitions.isEmpty() || skips == null) return;
		int before = definitions.size();
		definitions.removeIf(def -> {
			String name = def.getDefinitionClass().getName();
			return skips.contains(name) || skips.contains(Names.external(name)) || skips.contains(Names.internal(name));
		});
		if (definitions.size() != before) {
			warn("[BATCH-PRUNE] " + (before - definitions.size())
			     + " class(es) carried a skip verdict and were dropped from this round's redefine list;"
			     + " keeping them would apply half of a batch whose counterpart was refused.");
		}
	}

	private static void dropBatchClass(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath, String cn) {
		// 两种键写法都要删：批次里点分名为主，但对齐器产出的条目可能是斜杠形态。
		for (String key : new String[]{cn, Names.external(cn), Names.internal(cn)}) {
			newBatchBytes.remove(key);
			classToPath.remove(key);
		}
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
