package remold;

import remold.annotation.*;
import remold.offset.LayoutPredictor;
import remold.offset.OffsetBindingIndex;
import remold.offset.OffsetScanner;
import remold.offset.SuperclassDriftGate;
import remold.profiler.DynamicProfilerAPI;
import remold.ref.HotswapBridge;
import remold.ref.InitFix;
import remold.ref.RemovedMemberReaders;
import remold.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.*;
import java.lang.instrument.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.jar.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.zip.*;

import static remold.MountManager.*;

/**
 * HotSwap Agent 核心协调器。
 *
 * <p><b>设计文档与状态索引</b>：
 * 设计文档参见 {@code docs/topology/}（索引见 {@code docs/ANONYMOUS_CLASS_TOPOLOGY_PLAN.md}）与 {@code docs/initfix/}；
 * 实现状态参见 {@code docs/status.md} 与 {@code AGENTS.md}。</p>
 *
 * <p>基于 Java Instrumentation（DCEVM / JBR-21+ 增强类重定义能力）的热代码重载核心调度器。
 * 负责文件监听防抖、字节码差分分析、匿名类与局部类拓扑对齐、状态布局安全门拦截、
 * 字段初始化修复器（InitFix）联动、原子重定义事务调度及重载生命周期回调。</p>
 *
 * <h2>热更处理完整流水线（Pipeline）</h2>
 * <ol>
 *   <li><b>监听与防抖（Watch & Debounce）</b>：
 *       通过 {@link WatcherThread} 监控源码编译输出目录与 JAR 包，文件变动由单线程调度器
 *       进行时间窗口防抖（{@link #FILE_SHAKE_MS}），收集变动路径后统一进入 {@link #processChanges}。</li>
 *   <li><b>读取与差分分析（Read & Diff）</b>：
 *       从磁盘读取新编译字节码并与 {@link #bytecodeCache} 中当前运行的活跃字节码对比，
 *       由 {@link ClassDiffUtil} 提取结构与方法体变更。</li>
 *   <li><b>安全前置门（Defensive Gates）</b>：
 *       <ul>
 *         <li><b>继承体系守卫</b>：父类或接口发生变动（{@code diff.hierarchyChanged}）一律拒绝重定义，提示重启；</li>
 *         <li><b>局部类编号漂移门</b>（{@link LocalClassGuard}）：检测宿主内同名局部类编号位移（{@code docs/topology/07-layout-gate-and-risks.md} §2），
 *             按 {@link #LOCAL_CLASS_GUARD} 模式（默认 {@code reject}）原子移出宿主组；</li>
 *         <li><b>重定义层布局安全门</b>（{@link LayoutGate}）：检测具名类或用户声明字段的删除/改类型（{@code docs/topology/07-layout-gate-and-risks.md} §1），
 *             按 {@link #LAYOUT_GATE} 模式（默认 {@code reject}）原子移出宿主组。</li>
 *       </ul>
 *   </li>
 *   <li><b>匿名类拓扑对齐（Anonymous Class Cascading Alignment）</b>：
 *       由 {@link AnonClassAligner} 按四级置信度体系（Tier 1~4，参考 {@code docs/topology/04-tiers-and-rejection.md}）
 *       解决重新编译引起的 {@code Foo$N} 编号漂移问题：
 *       <ul>
 *         <li>匹配到的历史类：通过 {@link org.objectweb.asm.commons.ClassRemapper} 一致性重命名为旧类名；</li>
 *         <li>未匹配的新类：分配未占用的安全新编号，预登记至 {@link AnnotationTransformer#pendingAlignedClasses}
 *             待首次加载时拦截；</li>
 *         <li>未匹配的旧类：作为孤儿旧类（{@code orphanOldClasses}）在 JVM 中保留不动以维持存活实例方法调用（{@code docs/topology/01-invariants-and-remapping.md} §2 核心不变量）；</li>
 *         <li>歧义或超时：抛出异常并将宿主组整体移出本轮重定义（{@link #rejectHostGroup}）。</li>
 *       </ul>
 *   </li>
 *   <li><b>初值纯度审查与补丁装配（InitFix.transform）</b>：
 *       在重定义生效前，{@link InitFix#transform} 针对新增声明字段与标有 {@link RemoldReinit}
 *       的字段进行数据依赖切片与纯度安全门审查，生成独立静态方法的 hidden nestmate 伴生类补丁，并在
 *       {@link InitFix#snapshotInstances(Class)} 捕获存量实例快照。</li>
 *   <li><b>原子重定义执行（Redefine Execution）</b>：
 *       执行事务预登记（{@code tx.preRegister()}），随后调用 {@link Instrumentation#redefineClasses(ClassDefinition...)}。
 *       若发生批量失败，自动尝试单类降级重定义，并在事务一致性校验失败时原子回滚（{@code tx.rollback()}）。</li>
 *   <li><b>存量实例初始化驱动（InitFix.applyPatch）</b>：
 *       重定义生效后，调用 {@link InitFix#applyPatch}，通过 Native JVMTI 堆遍历
 *       （{@link LibTool#getInstances(Class)}，参见 {@code docs/initfix/05-jvmti-heap.md}）
 *       或 {@link InstanceTracker} 检索存活实例，执行条件 CAS 写入（{@link HotswapBridge}），
 *       并将未解决字段记入台账（{@code FieldLedger}）。</li>
 *   <li><b>生命周期回调（Lifecycle Callbacks）</b>：
 *       扫描类中标记的 {@link OnReload} 方法，自动调用静态回调或在全部存活实例上执行实例刷新。</li>
 * </ol>
 *
 * <h2>并发控制与锁序纪律（Concurrency & Lock Ordering）</h2>
 * <p>全流程由全局独占重入锁 {@link #HOTSWAP_LOCK} 保护，确保监听器防抖线程与外部手动触发
 * （如 {@link #triggerHotswap()}、{@link #retransformLoaded()}）严格串行执行。</p>
 * <p><b>必须遵守的死锁规避纪律</b>：
 * <ol>
 *   <li>{@link AnnotationTransformer#transform} 及类加载回调路径上<b>永远不得获取全局锁</b>；</li>
 *   <li>固定锁获取顺序：<b>先获取 {@code HOTSWAP_LOCK}，再进入局部同步块（如 {@code pendingChanges}）</b>，禁止反向获取。</li>
 * </ol>
 * </p>
 *
 * @see AnonClassAligner
 * @see LayoutGate
 * @see LocalClassGuard
 * @see InitFix
 * @see HotswapBridge
 * @see AnnotationTransformer
 */
public class RemoldAgent {
	//region Configuration Fields
	public static boolean      DEBUG              = Boolean.parseBoolean(System.getenv("remold.agent.debug"));
	public static boolean      UCP_APPEND         = Boolean.parseBoolean(System.getProperty("remold.agent.ucp_append", "true"));
	/**
	 * 文件监控防抖时间窗口（毫秒）。
	 * 支持通过 System Property ({@code -Dremold.agent.file_shake_ms=...} 或 {@code -Dremold.fileShakeMs=...})、
	 * 环境变量 {@code REMOLD_AGENT_FILE_SHAKE_MS}、agentArgs ({@code file_shake_ms=...}) 或
	 * {@link #setFileShakeMs(int)} 动态配置。默认 1200ms。
	 */
	public static volatile int FILE_SHAKE_MS      = intProp("remold.agent.file_shake_ms", "remold.fileShakeMs", 1200);
	public static RedefineMode REDEFINE_MODE;
	public static String[]     HOTSWAP_BLACKLIST  = new String[0];
	public static boolean      RETRANSFORM_LOADED = Boolean.parseBoolean(System.getProperty("remold.agent.retransform_loaded", "false"));
	public static boolean      ENABLE_HOTSWAP_EVENT;
	public static boolean      FORCE_REINIT;
	public static boolean      LAMBDA_ALIGN;
	public static boolean      INIT_FIX           = boolProp("remold.agent.init_fix", "remold.initFix", true);
	public static boolean      FORCE_STATIC_LAMBDA = boolProp("remold.agent.force_static_lambda", "remold.agent.force_static_lambdas", false);
	public static boolean      UI_HOOK;
	/**
	 * 匿名类对齐总开关（{@code docs/topology/06-runtime-and-perf.md} §4）。默认开启。
	 *
	 * <p><b>关闭语义</b>：不是"按类名照旧重定义"（那正是编号位移篡夺场景本身），而是把含有匿名类的
	 * 宿主组整体移出本批重定义（见 {@link #rejectHostGroup}）。</p>
	 */
	public static boolean      ANON_ALIGN         = boolProp("remold.agent.anon_align", "remold.anonAlign.enabled", true);
	/** 严格模式（{@code docs/topology/06-runtime-and-perf.md} §4）：Tier 4 歧义 / 嵌套深度超限时按 {@code docs/topology/04-tiers-and-rejection.md} §4 拒绝整个宿主组。 */
	public static boolean      ANON_STRICT        = boolProp("remold.agent.anon_strict", "remold.anonAlign.strict", false);
	/** 匿名类对齐诊断日志（{@code docs/topology/06-runtime-and-perf.md} §4）：打印层级决策链。 */
	public static boolean      ANON_DEBUG         = boolProp("remold.agent.anon_debug", "remold.anonAlign.debug", false);

	/**
	 * 实例状态布局门模式（{@code docs/topology/07-layout-gate-and-risks.md} §1 的精确变体）：{@code reject} / {@code warn} / {@code off}。
	 *
	 * <p>门守的是"字段布局变化后，<b>已存在的实例</b>读新字段得零值"。三种模式：</p>
	 * <ul>
	 *   <li>{@code reject}（默认）—— 布局不兼容<b>且有存活实例</b>时拒绝配对；
	 *       新类分配未占用编号，旧类成为孤儿并保留，存活实例继续跑旧逻辑（{@code docs/topology/01-invariants-and-remapping.md} §2）。</li>
	 *   <li>{@code warn} —— 照旧在 Tier 4 配对并原地重定义，只打强告警。
	 *       给"我就想原地更新、界面马上会重建"的场景用。计数器照记，
	 *       这样用户能看到"本来会被拒绝的有几次"。</li>
	 *   <li>{@code off} —— 完全恢复旧行为：不扫实例、不打日志。</li>
	 * </ul>
	 *
	 * <p>用字符串而非 boolean，是因为将来可能加更多档（如按类注解 {@code @RemoldReinit}），
	 * 那时再加一个值即可，不用再破坏一次属性语义。</p>
	 */
	public static String ANON_LAYOUT_GATE = strProp("remold.agent.anon_layout_gate", "reject");

	/**
	 * 实例状态布局门（重定义层，全体类，非仅匿名类）模式：{@code reject} / {@code warn} / {@code off}（{@code docs/topology/07-layout-gate-and-risks.md} §1）。
	 *
	 * <p>与 {@link #ANON_LAYOUT_GATE} 的分工：后者在<b>对齐器</b>里挡"匿名类合成捕获字段变化"
	 * （Tier 4 配对前）；本开关在<b>重定义层</b>挡"具名类 / 匿名类用户声明字段的删/改类型/改静态性"，
	 * 判据是现成的 {@code ClassDiff.changedFields}。两者互补，默认都是 {@code reject}。</p>
	 *
	 * <ul>
	 *   <li>{@code reject}（默认）—— 字段被删除 / 改类型 / 改静态性时，把该宿主及其匿名类
	 *       整组移出本轮重定义（复用 {@code docs/topology/04-tiers-and-rejection.md} §4 的宿主组拒绝通道），存活实例继续跑旧逻辑；</li>
	 *   <li>{@code warn} —— 照旧重定义，只打强告警（给"界面马上会重建"的场景用）；</li>
	 *   <li>{@code off} —— 完全恢复旧行为：不做任何布局检查。</li>
	 * </ul>
	 *
	 * <p>纯新增字段一律放行：InitFix 会为存活实例补初始化，这正是热更时新增字段的期望语义。</p>
	 */
	public static String LAYOUT_GATE = strProp("remold.agent.layout_gate", "reject");

	/**
	 * 内存偏移漂移与访问器安全门严格模式（{@code -Dremold.agent.offset_strict=true} / {@code -Dremold.offsetStrict=true}）。
	 *
	 * <p>开启后，当类发生物理字段漂移或增删时，若存在无法 clean-load 验证的子孙类或未解析盲区，
	 * 直接 fail-closed 阻断该类的重定义，避免静默内存破坏。</p>
	 */
	public static boolean OFFSET_STRICT = boolProp("remold.agent.offset_strict", "remold.offsetStrict", false);

	/**
	 * 同名局部类编号漂移止血门（{@code docs/topology/07-layout-gate-and-risks.md} §2）模式：{@code reject} / {@code warn} / {@code off}。
	 *
	 * <p>局部类原名直通（{@code isAnonymousClassName} 拒绝准入），重定义按类名一一对应。同一宿主内
	 * 同简单名的局部类（{@code Foo$1Helper}、{@code Foo$2Helper}）在前插/删除/换序后物理编号整体
	 * 位移 → 存活实例被无关实现顶替（静默错配）。触发条件与匿名类位移同类，但局部类不在对齐器视野内。</p>
	 *
	 * <ul>
	 *   <li>{@code reject}（默认）—— 本批里某宿主出现"同名局部类"，且**新旧任一侧**同名计数 ≥2 时，
	 *       把该宿主及其 {@code host$...} 整族移出本轮重定义（复用 {@code docs/topology/04-tiers-and-rejection.md} §4 宿主组拒绝通道）；</li>
	 *   <li>{@code warn} —— 照旧重定义，只打强告警（观测用）；</li>
	 *   <li>{@code off} —— 完全恢复旧行为：不做任何局部类检查。</li>
	 * </ul>
	 *
	 * <p>只把"新批次里出现局部类"的宿主纳入候选；简单名唯一、或本批未触及局部类的宿主不受影响。</p>
	 */
	public static String LOCAL_CLASS_GUARD = strProp("remold.agent.local_class_guard", "reject");

	/** 读字符串属性并做合法性校验；非法值回退到默认值并告警（不静默接受拼错的开关）。 */
	private static String strProp(String key, String def) {
		String v = System.getProperty(key);
		if (v == null) return def;
		v = v.trim().toLowerCase();
		if (v.equals(LayoutGate.MODE_REJECT) || v.equals(LayoutGate.MODE_WARN) || v.equals(LayoutGate.MODE_OFF)) {
			return v;
		}
		// 复用 error()：拼错的开关值被静默忽略，比"多认一个别名"危险得多
		System.err.println("[Remold] Unknown " + key + " value '" + v + "'; falling back to '" + def + "'");
		return def;
	}
	//endregion

	//region Core State Management
	static               Instrumentation     inst;
	public static final  String              SOURCE_CLI      = "cli";
	public static final  String              SOURCE_RUNTIME  = "runtime";

	/** 来源分槽映射表：sourceId -> 该来源下的待监控目录集合 */
	private static final Map<String, Set<Path>> watchDirSources = new ConcurrentHashMap<>();
	/** 来源分槽映射表：sourceId -> 该来源下的待监控 Jar 集合 */
	private static final Map<String, Set<Path>> watchJarSources = new ConcurrentHashMap<>();

	static final         Set<Path>           activeWatchDirs = new CopyOnWriteArraySet<>();
	/** 直接作为 watch 路径传入的 jar/zip 文件（区别于目录内扫描到的） */
	static final         Set<Path>           activeWatchJars = new CopyOnWriteArraySet<>();
	private static final List<WatcherThread> activeWatchers  = new CopyOnWriteArrayList<>();

	/**
	 * 在不使用retransform的情况下，确保旧bytecode正确的唯一方法
	 * dotClassName -> bytecode（JVM 实际生效/织入后基线，供 LayoutGate、InitFix、redefineClasses 使用）
	 */
	public static final Map<String, byte[]> bytecodeCache = new ConcurrentHashMap<>();

	/**
	 * 源码级未织入基线缓存（用于对齐器与源码变更分析，避免 Mixin 等增强注入的合成方法/字段污染对齐逻辑）
	 * dotClassName -> sourceBytecode
	 */
	public static final Map<String, byte[]> sourceBytecodeCache = new ConcurrentHashMap<>();

	/**
	 * 字节码重织入桥接接口（用于 Mixin、AspectJ、自定义字节码增强器在热重载时重新织入）。
	 *
	 * <p>在类对齐完成后、进入布局安全门 (LayoutGate) 与重定义 (redefineClasses) 之前介入，
	 * 将源码级编译产物转换为带有增强钩子（如 Mixin @Inject/@Unique）的运行时字节码。</p>
	 *
	 * @deprecated 契约已上移到 {@link remold.BytecodeTransformerBridge}（api 包），
	 *             插件与业务代码应直接实现该接口并调用
	 *             {@link #addTransformerBridge(remold.BytecodeTransformerBridge)}。
	 *             本类型仅作为既有调用方的兼容别名保留。
	 */
	@Deprecated
	public interface BytecodeTransformerBridge extends remold.BytecodeTransformerBridge {
	}

	private static final List<remold.BytecodeTransformerBridge> transformerBridges = new CopyOnWriteArrayList<>();

	public static void addTransformerBridge(remold.BytecodeTransformerBridge bridge) {
		if (bridge != null && !transformerBridges.contains(bridge)) {
			transformerBridges.add(bridge);
		}
	}

	public static void removeTransformerBridge(remold.BytecodeTransformerBridge bridge) {
		if (bridge != null) {
			transformerBridges.remove(bridge);
		}
	}

	/** 执行所有已注册的字节码重织入桥 */
	public static byte[] applyTransformerBridges(ClassLoader loader, String dotClassName, Class<?> targetClass, byte[] bytecode) {
		if (transformerBridges.isEmpty() || bytecode == null) return bytecode;
		byte[] current = bytecode;
		for (remold.BytecodeTransformerBridge bridge : transformerBridges) {
			try {
				byte[] transformed = bridge.transform(loader, dotClassName, targetClass, current);
				if (transformed != null && transformed.length > 0) {
					current = transformed;
				}
			} catch (Throwable t) {
				error("[BRIDGE-ERROR] Error transforming " + dotClassName + " with " + bridge.getClass().getName(), t);
			}
		}
		return current;
	}

	//region Dispatcher & SPI Lifecycle
	private static volatile MainThreadDispatcher mainThreadDispatcher = Runnable::run;
	private static final List<HotswapBatchListener> batchListeners = new CopyOnWriteArrayList<>();
	private static final List<HotswapPlugin> plugins = new CopyOnWriteArrayList<>();

	/**
	 * 设置主线程调度器（默认 {@code Runnable::run}）。
	 * GUI 宿主可在启动时注册（例如 Mindustry 注册 {@code Core.app::post}）。
	 */
	public static void setMainThreadDispatcher(MainThreadDispatcher dispatcher) {
		mainThreadDispatcher = dispatcher != null ? dispatcher : Runnable::run;
	}

	public static MainThreadDispatcher getMainThreadDispatcher() {
		return mainThreadDispatcher;
	}

	public static void postToMain(Runnable task) {
		if (task != null) {
			mainThreadDispatcher.dispatch(task);
		}
	}

	/** 注册热重载批次生命周期监听器 */
	public static void addBatchListener(HotswapBatchListener listener) {
		if (listener != null && !batchListeners.contains(listener)) {
			batchListeners.add(listener);
		}
	}

	/** 注销热重载批次生命周期监听器 */
	public static void removeBatchListener(HotswapBatchListener listener) {
		if (listener != null) {
			batchListeners.remove(listener);
		}
	}

	private static void notifyBatchListeners(List<ClassDefinition> definitions) {
		if (definitions == null || definitions.isEmpty()) return;
		for (HotswapBatchListener listener : batchListeners) {
			try {
				listener.afterInternalBatch(definitions);
			} catch (Throwable t) {
				error("[HotswapBatchListener] Error notifying batch listener: " + listener.getClass().getName(), t);
			}
		}
	}

	public static void notifyExternalClassRedefined(Class<?> clazz, byte[] wovenBytes) {
		if (clazz == null) return;
		for (HotswapBatchListener listener : batchListeners) {
			try {
				listener.onExternalClassRedefined(clazz, wovenBytes);
			} catch (Throwable t) {
				error("[HotswapBatchListener] Error notifying external redefine: " + clazz.getName(), t);
			}
		}
	}

	/**
	 * 通知所有注册的扩展插件发生资源文件变动。
	 *
	 * @param assetChanges 变动的资源文件路径集合
	 */
	public static void notifyAssetChanges(Set<Path> assetChanges) {
		if (assetChanges == null || assetChanges.isEmpty()) return;
		for (HotswapPlugin plugin : plugins) {
			try {
				plugin.onAssetsChanged(assetChanges);
			} catch (Throwable t) {
				error("[HotswapPlugin] Error notifying asset changes to plugin: " + plugin.getName(), t);
			}
		}
	}

	private static final Set<HotswapPlugin> initializedPlugins = Collections.newSetFromMap(new IdentityHashMap<>());

	private static void triggerPluginInit(HotswapPlugin plugin) {
		if (plugin == null || inst == null) return;
		synchronized (initializedPlugins) {
			if (!initializedPlugins.add(plugin)) return;
		}
		try {
			plugin.onAgentInit(inst);
		} catch (Throwable t) {
			error("[HotswapPlugin] Error initializing plugin: " + plugin.getName(), t);
		}
	}

	/** 注册热重载扩展插件 */
	public static void registerPlugin(HotswapPlugin plugin) {
		if (plugin == null) return;
		for (HotswapPlugin p : plugins) {
			if (p == plugin || p.getClass() == plugin.getClass() || (p.getName() != null && p.getName().equals(plugin.getName()))) {
				return;
			}
		}
		plugins.add(plugin);
		triggerPluginInit(plugin);
	}

	/** 注销热重载扩展插件 */
	public static void unregisterPlugin(HotswapPlugin plugin) {
		if (plugin != null) {
			plugins.remove(plugin);
		}
	}

	/** 注册孤儿 Lambda 熔断判定谓词（供插件如 UpdateRef 注册） */
	public static void addOrphanBreaker(OrphanBreakerPredicate predicate) {
		LambdaAligner.addOrphanBreaker(predicate);
	}

	public static void removeOrphanBreaker(OrphanBreakerPredicate predicate) {
		LambdaAligner.removeOrphanBreaker(predicate);
	}

	/** 注册栈遍历透明帧判定谓词（供 UI / 代理框架插件注册） */
	public static void addTransparentFramePredicate(TransparentFramePredicate predicate) {
		LambdaAligner.addTransparentFramePredicate(predicate);
	}

	public static void removeTransparentFramePredicate(TransparentFramePredicate predicate) {
		LambdaAligner.removeTransparentFramePredicate(predicate);
	}

	/** 供动态代码生成框架提交新字节码执行重定义（别名） */
	public static boolean redefine(Class<?> targetClass, byte[] newBytecode) {
		return redefineDynamic(targetClass, newBytecode);
	}

	/** 获取源码基线（对齐后、未织入第三方切片的原始字节，供 Diff / 对齐比对用） */
	public static byte[] getSourceBytecode(String dotClassName) {
		return dotClassName != null ? sourceBytecodeCache.get(dotClassName) : null;
	}

	/** 获取运行时生效基线（织入 Mixin / 插件后的实际运行字节，供 InitFix / Schema 判断用） */
	public static byte[] getRuntimeBytecode(String dotClassName) {
		return dotClassName != null ? bytecodeCache.get(dotClassName) : null;
	}
	//endregion

	/**
	 * 内部热更重定义上下文，用于在 {@link Instrumentation#redefineClasses} 与 {@link AnnotationTransformer#transform}
	 * 之间传递调用起源与目标类集合，精准识别是否由 RemoldAgent 自身发起。
	 */
	public static final class InternalRedefineContext {
		public final Set<String> targetDotClasses;
		public final long timestampNanos;
		public final boolean isRetransform;
		final InternalRedefineContext previous;

		public InternalRedefineContext(Collection<String> targetDotClasses, boolean isRetransform, InternalRedefineContext previous) {
			this.targetDotClasses = targetDotClasses == null ? Collections.emptySet() : new HashSet<>(targetDotClasses);
			this.timestampNanos = System.nanoTime();
			this.isRetransform = isRetransform;
			this.previous = previous;
		}

		public boolean isTarget(String dotClassName) {
			if (targetDotClasses.contains(dotClassName)) return true;
			return previous != null && previous.isTarget(dotClassName);
		}
	}

	public static final ThreadLocal<InternalRedefineContext> INTERNAL_CONTEXT = new ThreadLocal<>();

	public static void enterInternalRedefine(Collection<String> dotClassNames) {
		InternalRedefineContext prev = INTERNAL_CONTEXT.get();
		INTERNAL_CONTEXT.set(new InternalRedefineContext(dotClassNames, false, prev));
	}

	public static void enterInternalRetransform(Collection<String> dotClassNames) {
		InternalRedefineContext prev = INTERNAL_CONTEXT.get();
		INTERNAL_CONTEXT.set(new InternalRedefineContext(dotClassNames, true, prev));
	}

	public static void exitInternalRedefine() {
		InternalRedefineContext curr = INTERNAL_CONTEXT.get();
		if (curr != null) {
			if (curr.previous != null) {
				INTERNAL_CONTEXT.set(curr.previous);
			} else {
				INTERNAL_CONTEXT.remove();
			}
		} else {
			INTERNAL_CONTEXT.remove();
		}
	}

	/**
	 * 为外部触发的类重定义（IDE JDWP / 外部 Agent）调度后置异步任务，在 JVM 完成类元数据安装后执行 InitFix 修补与 @OnReload。
	 */
	public static void scheduleDeferredPostRedefine(Class<?> clazz) {
		if (clazz == null) return;
		Runnable task = () -> {
			try {
				InitFix.afterRedefineExternal(clazz);
			} catch (Throwable t) {
				error("[EXTERNAL-HOTSWAP] Error during deferred InitFix for " + clazz.getName(), t);
			}

			try {
				processAnnotationsInternal(clazz);
			} catch (Throwable t) {
				error("[EXTERNAL-HOTSWAP] Error during deferred @OnReload for " + clazz.getName(), t);
			}

			try {
				notifyExternalClassRedefined(clazz, getRuntimeBytecode(clazz.getName()));
			} catch (Throwable t) {
				error("[EXTERNAL-HOTSWAP] Error during deferred notifyExternalClassRedefined for " + clazz.getName(), t);
			}
		};

		scheduler.schedule(() -> postToMain(task), 20, TimeUnit.MILLISECONDS);
	}

	// 仅记录byte的指纹
	private static final LongLongMap fileDiskHashes = new LongLongMap(2048);

	private static final Set<Path>                pendingChanges = Collections.synchronizedSet(new HashSet<>());
	/** 待解压的 jar/zip，防抖后统一解压；所有访问均在 synchronized(pendingChanges) 内，无需额外同步 */
	private static final Set<Path>                pendingJars    = new HashSet<>();
	/** 待通知的资源文件变动（非 .class 资源，如贴图、语言包等），防抖后统一通知；所有访问均在 synchronized(pendingChanges) 内 */
	private static final Set<Path>                pendingAssetChanges = new HashSet<>();
	/** 临时解压出的 .class 路径 → 来源 jar 路径，用于 ClassLoader 推断 */
	private static final Map<Path, Path>          tmpToJar       = new ConcurrentHashMap<>();
	private static final ScheduledExecutorService scheduler      = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "HotSwap-Scheduler");
		t.setDaemon(true);
		return t;
	});
	private static       ScheduledFuture<?>       scheduledTask;

	/**
	 * 全局热更独占锁：保证 {@code processChanges} 全流程串行。
	 *
	 * <p><b>为什么需要它</b>：{@code scheduler} 是单线程的，因此"监听线程 → 防抖 → 热更"这条
	 * 路径本来就串行。但存在若干<b>绕过 scheduler 的入口</b>，它们直接在调用方线程上执行热更：</p>
	 * <ul>
	 *   <li>公开的 {@link #triggerHotswap()} —— 例如 {@code HotSwapDialog} 的 refresh 按钮，
	 *       它用 {@code Threads.daemon(...)} 起了<b>第二个线程源</b>，与 scheduler 上正在跑的那一轮重叠；</li>
	 *   <li>{@link #init} 的 else 分支（监控路径未变时直接同步调用）；</li>
	 *   <li>{@link #init} 整体（含 {@code initializeAgentState} / {@code restartWatchers}）—— 旧 watcher
	 *       排进 scheduler 的任务可能尚未跑完；</li>
	 *   <li>公开的 {@link #retransformLoaded()} —— 同样对应一个 UI 按钮。</li>
	 * </ul>
	 *
	 * <p><b>锁序纪律（必须遵守，否则死锁）</b>：</p>
	 * <ol>
	 *   <li><b>{@code AnnotationTransformer.transform} 及类加载路径上永远不得获取这把锁。</b>
	 *       持锁线程在 {@code redefineClasses} / {@code retransformClasses} 期间会触发类加载与 transform，
	 *       若 transform 反向抢锁即形成死锁。</li>
	 *   <li><b>不得在持有 {@code synchronized(pendingChanges)} 时获取这把锁。</b>
	 *       固定顺序为：先取 {@code HOTSWAP_LOCK}，再进 {@code synchronized(pendingChanges)}。
	 *       反向（持 pendingChanges 锁再抢全局锁）会与其他线程形成环路。</li>
	 * </ol>
	 *
	 * <p>可重入：{@link #init} 持锁后会再次经 {@link #triggerHotswap()} 进入，这是预期的。</p>
	 */
	private static final ReentrantLock HOTSWAP_LOCK = new ReentrantLock();

	/**
	 * 并发探针（测试用）：当前处于热更流程内的线程数，以及历史峰值。
	 * 用于"先红后绿"回归 —— 去掉 {@code HOTSWAP_LOCK} 时峰值应为 2，加上后恒为 1。
	 */
	static final AtomicInteger CONCURRENT_HOTSWAP      = new AtomicInteger();
	static final AtomicInteger CONCURRENT_HOTSWAP_PEAK = new AtomicInteger();

	/** 测试用：复位并发探针。 */
	public static void resetConcurrencyProbe() {
		CONCURRENT_HOTSWAP.set(0);
		CONCURRENT_HOTSWAP_PEAK.set(0);
	}

	/** 测试用：读取并发探针峰值。 */
	public static int peakConcurrentHotswaps() {
		return CONCURRENT_HOTSWAP_PEAK.get();
	}

	/** 动态设置文件防抖等待窗口（毫秒）。负数将被忽略。 */
	public static void setFileShakeMs(int fileShakeMs) {
		if (fileShakeMs >= 0) {
			FILE_SHAKE_MS = fileShakeMs;
		}
	}

	/** 获取当前文件防抖等待窗口（毫秒）。 */
	public static int getFileShakeMs() {
		return FILE_SHAKE_MS;
	}
	//endregion

	//region Agent Initialization
	static AnnotationTransformer transformer;

	public static void premain(String agentArgs, Instrumentation inst) {
		RemoldAgent.inst = inst;
		try {
			if (agentArgs != null && !agentArgs.trim().isEmpty()) {
				init(SOURCE_CLI, agentArgs, false);
			} else {
				initConfig();
				initTransformer();
			}
		} catch (Throwable t) {
			error("Critical error during agent initialization", t);
		}
	}

	public static void agentmain(String agentArgs, Instrumentation inst) {
		RemoldAgent.inst = inst;
		try {
			if (agentArgs != null && !agentArgs.trim().isEmpty()) {
				init(SOURCE_RUNTIME, agentArgs, false);
			} else {
				initConfig();
				initTransformer();
			}
		} catch (Throwable t) {
			error("Critical error during agent initialization", t);
		}
	}

	public static Instrumentation getInstrumentation() {
		return inst;
	}

	public static void init(String agentArgs, boolean reinit) {
		init(SOURCE_RUNTIME, agentArgs, reinit);
	}

	public static void init(String source, String agentArgs, boolean reinit) {
		// 整体持锁：本方法包含 retransformLoaded / initializeAgentState / restartWatchers，
		// 而旧 watcher 排进 scheduler 的任务可能尚未跑完。可重入，末尾 triggerHotswap() 嵌套获取无碍。
		HOTSWAP_LOCK.lock();
		try {
			init0(source, agentArgs, reinit);
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	private static void init0(String source, String agentArgs, boolean reinit) {
		initConfig();

		initTransformer();
		var loadedClasses = inst != null ? inst.getAllLoadedClasses() : new Class<?>[0];
		refreshPackageLoaders(loadedClasses);

		if (RETRANSFORM_LOADED && inst != null) {
			retransformLoaded(loadedClasses);
		}

		for (HotswapPlugin plugin : plugins) {
			triggerPluginInit(plugin);
		}

		if (source == null || source.trim().isEmpty()) {
			source = SOURCE_RUNTIME;
		}

		Set<Path> newWatchDirs = new HashSet<>();
		Set<Path> newWatchJars = new HashSet<>();
		if (agentArgs != null && !agentArgs.trim().isEmpty()) {
			parseWatchPaths(agentArgs, newWatchDirs, newWatchJars);
		}

		if (newWatchDirs.isEmpty() && newWatchJars.isEmpty()) {
			if (activeWatchDirs.isEmpty() && activeWatchJars.isEmpty()) {
				error("No valid watch paths provided.");
			}
			return;
		}

		setWatchPaths0(source, newWatchDirs, newWatchJars);
	}

	/**
	 * 设置指定来源的监控路径（支持分号或冒号分隔的目录或 Jar 路径）。
	 * 若 paths 为空，则移除该来源对应的监控路径。
	 *
	 * @param source 来源标识符（如 "cli", "mod-tools", "runtime" 等）
	 * @param paths  待监控路径（支持分号或冒号分隔）
	 */
	public static void setWatchPaths(String source, String paths) {
		HOTSWAP_LOCK.lock();
		try {
			if (source == null || source.trim().isEmpty()) {
				source = SOURCE_RUNTIME;
			}
			if (paths == null || paths.trim().isEmpty()) {
				removeWatchSource0(source);
				return;
			}
			Set<Path> dirs = new HashSet<>();
			Set<Path> jars = new HashSet<>();
			parseWatchPaths(paths, dirs, jars);
			setWatchPaths0(source, dirs, jars);
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	/**
	 * 设置指定来源的目录与 Jar 路径集合。
	 */
	public static void setWatchPaths(String source, Collection<Path> dirs, Collection<Path> jars) {
		HOTSWAP_LOCK.lock();
		try {
			if (source == null || source.trim().isEmpty()) {
				source = SOURCE_RUNTIME;
			}
			setWatchPaths0(source, dirs != null ? new HashSet<>(dirs) : Collections.emptySet(),
			                      jars != null ? new HashSet<>(jars) : Collections.emptySet());
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	/**
	 * 向指定来源追加监控路径。
	 */
	public static void addWatchPaths(String source, String paths) {
		HOTSWAP_LOCK.lock();
		try {
			if (source == null || source.trim().isEmpty()) {
				source = SOURCE_RUNTIME;
			}
			if (paths == null || paths.trim().isEmpty()) return;
			Set<Path> dirs = new HashSet<>(getWatchDirs(source));
			Set<Path> jars = new HashSet<>(getWatchJars(source));
			parseWatchPaths(paths, dirs, jars);
			setWatchPaths0(source, dirs, jars);
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	/**
	 * 移除指定来源的全部监控路径。
	 */
	public static void removeWatchSource(String source) {
		HOTSWAP_LOCK.lock();
		try {
			if (source == null) return;
			removeWatchSource0(source);
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	/** 获取指定来源的监控目录集合（只读不可变副本） */
	public static Set<Path> getWatchDirs(String source) {
		Set<Path> s = watchDirSources.get(source);
		return s == null ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(s));
	}

	/** 获取指定来源的监控 Jar 集合（只读不可变副本） */
	public static Set<Path> getWatchJars(String source) {
		Set<Path> s = watchJarSources.get(source);
		return s == null ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(s));
	}

	/** 获取当前所有来源的监控目录映射（只读不可变副本） */
	public static Map<String, Set<Path>> getAllWatchDirSources() {
		Map<String, Set<Path>> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Set<Path>> e : watchDirSources.entrySet()) {
			copy.put(e.getKey(), Collections.unmodifiableSet(new HashSet<>(e.getValue())));
		}
		return Collections.unmodifiableMap(copy);
	}

	/** 获取当前所有来源的监控 Jar 映射（只读不可变副本） */
	public static Map<String, Set<Path>> getAllWatchJarSources() {
		Map<String, Set<Path>> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Set<Path>> e : watchJarSources.entrySet()) {
			copy.put(e.getKey(), Collections.unmodifiableSet(new HashSet<>(e.getValue())));
		}
		return Collections.unmodifiableMap(copy);
	}

	private static void parseWatchPaths(String agentArgs, Set<Path> outDirs, Set<Path> outJars) {
		for (String s : agentArgs.split(File.pathSeparator)) {
			if (s.trim().isEmpty()) continue;
			String trimmed = s.trim();
			if (trimmed.startsWith("file_shake_ms=") || trimmed.startsWith("shake=") || trimmed.startsWith("fileShakeMs=")) {
				try {
					int val = Integer.parseInt(trimmed.substring(trimmed.indexOf('=') + 1).trim());
					if (val >= 0) {
						FILE_SHAKE_MS = val;
						info("File Shake MS configured via agentArgs: " + FILE_SHAKE_MS);
					}
				} catch (NumberFormatException e) {
					warn("Invalid file_shake_ms in agentArgs: " + trimmed);
				}
				continue;
			}
			Path   p  = Paths.get(trimmed).toAbsolutePath();
			String ps = p.toString();
			if (Files.isDirectory(p)) {
				outDirs.add(p);
			} else if ((ps.endsWith(".jar") || ps.endsWith(".zip")) && Files.isRegularFile(p)) {
				outJars.add(p);
			} else {
				error("Skipping invalid watch path (not a directory or jar/zip): " + p);
			}
		}
	}

	private static void setWatchPaths0(String source, Set<Path> dirs, Set<Path> jars) {
		if (dirs == null || dirs.isEmpty()) {
			watchDirSources.remove(source);
		} else {
			watchDirSources.put(source, new CopyOnWriteArraySet<>(dirs));
		}
		if (jars == null || jars.isEmpty()) {
			watchJarSources.remove(source);
		} else {
			watchJarSources.put(source, new CopyOnWriteArraySet<>(jars));
		}
		syncAggregatedWatchPaths();
	}

	private static void removeWatchSource0(String source) {
		watchDirSources.remove(source);
		watchJarSources.remove(source);
		syncAggregatedWatchPaths();
	}

	private static void syncAggregatedWatchPaths() {
		Set<Path> aggregatedDirs = new HashSet<>();
		for (Set<Path> s : watchDirSources.values()) {
			aggregatedDirs.addAll(s);
		}
		Set<Path> aggregatedJars = new HashSet<>();
		for (Set<Path> s : watchJarSources.values()) {
			aggregatedJars.addAll(s);
		}

		if (!activeWatchDirs.equals(aggregatedDirs) || !activeWatchJars.equals(aggregatedJars)) {
			info("Watch paths updated across sources:" + formatWatchSourcesLog());
			activeWatchDirs.clear();
			activeWatchDirs.addAll(aggregatedDirs);
			activeWatchJars.clear();
			activeWatchJars.addAll(aggregatedJars);
			if (inst != null) {
				initializeAgentState(inst.getAllLoadedClasses());
				restartWatchers();
			}
		} else {
			if (inst != null) {
				triggerHotswap();
			}
		}
	}

	private static String formatWatchSourcesLog() {
		StringBuilder sb = new StringBuilder();
		Set<String> allSources = new TreeSet<>();
		allSources.addAll(watchDirSources.keySet());
		allSources.addAll(watchJarSources.keySet());
		if (allSources.isEmpty()) {
			return " (none)";
		}
		for (String src : allSources) {
			Set<Path> d = watchDirSources.getOrDefault(src, Collections.emptySet());
			Set<Path> j = watchJarSources.getOrDefault(src, Collections.emptySet());
			sb.append("\n  - [").append(src).append("] dirs=").append(d).append(" jars=").append(j);
		}
		return sb.toString();
	}

	private static void initTransformer() {
		if (transformer == null) {
			transformer = new AnnotationTransformer();
			inst.addTransformer(transformer, true);
			// 必须也是 canRetransform = true：JVM 先跑完**所有** canRetransform=false 的 transformer，
			// 再跑 canRetransform=true 的那组（注册顺序只在组内生效）。若这里传 false，
			// 首次加载时本 transformer 会先于 AnnotationTransformer 执行，拿到的是对齐/织入**之前**
			// 的字节——缓存就存错了版本。同组内按注册顺序执行，所以它仍然排在 AnnotationTransformer 之后。
			inst.addTransformer(new CacheWarmingTransformer(), true);
			if (isEnhancedHotswapEnabled()) {
				try {
					DynamicProfilerAPI.init();
				} catch (Throwable ignored) { }
			} else {
				info("Skipped dynamic profiler injector because structural hotswap is unsupported.");
			}
		}
	}

	/**
	 * 首次加载时把工程类的字节码抄进 {@link #bytecodeCache}。
	 *
	 * <p><b>为什么需要它</b>：删除字段的读者扫描要求"已加载类读内存里当前生效的字节码"，
	 * 而 {@code bytecodeCache} 原先只在热更/对齐过程中被动填充。没有这一层，首轮热更时缓存几乎是空的，
	 * 扫描会判为不完整并退回存活实例判据——判据等于没接线
	 * （{@code docs/initfix/07-p05-probe-results.md} §14.7）。</p>
	 *
	 * <p><b>为什么是独立 transformer 而不是塞进 {@link AnnotationTransformer}</b>：后者对首次加载的类
	 * 会做对齐替换（{@code pendingAlignedClasses}），在它里面提前 {@code return null} 会跳过对齐；
	 * 注册在它之后则天然拿到对齐后的字节，而且本 transformer <b>永远返回 {@code null}</b>
	 * （不改字节、不参与任何决策），对加载流程零影响。</p>
	 *
	 * <p>只抄工程自身的类：判定见 {@link #isProjectLocation}。数组类与 hidden class 不会走到这里。</p>
	 */
	/**
	 * <p><b>为什么是 public</b>：集成探针 {@code CacheWarmingProbe} 要注册<b>同一个类</b>来验证
	 * "缓存里存的确实是对齐/织入之后的字节"，自己复制一份等价实现就测不到真实接线了。
	 * 除探针外不要在别处 new 它——它的唯一注册点在 {@link #initTransformer()}。</p>
	 */
	public static final class CacheWarmingTransformer implements ClassFileTransformer {
		@Override
		public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
		                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
			if (classBeingRedefined != null || className == null || classfileBuffer == null) return null;
			if (loader == null) return null;                        // bootstrap
			if (className.contains("/0x")) return null;             // hidden class（lambda 等）
			if (className.startsWith("remold/")) return null;        // agent 自身，避免递归
			scanLoadedClassForOffsets(classfileBuffer);
			if (!isProjectLocation(protectionDomain)) return null;
			bytecodeCache.putIfAbsent(className.replace('/', '.'), classfileBuffer);
			return null;
		}
	}

	/** CodeSource 位置 → 是否属于工程自身。按位置字符串缓存，避免每个类都解析一次 URI。 */
	private static final Map<String, Boolean> projectLocations = new ConcurrentHashMap<>();

	/**
	 * 判定一个保护域的位置是否属于"工程自身"。
	 *
	 * <p>先认 agent 正在监听的目录与 jar（{@code watchDirSources} / {@code watchJarSources} /
	 * {@code activeWatchJars}）——那才是工程的边界，mod 以 jar 形式分发时也成立。
	 * 监听配置为空时退回"file: 目录"判据：jar 里的类按依赖处理，不属于工程自身。</p>
	 */
	private static boolean isProjectLocation(ProtectionDomain pd) {
		if (pd == null) return false;
		java.security.CodeSource cs = pd.getCodeSource();
		if (cs == null || cs.getLocation() == null) return false;
		String key = cs.getLocation().toString();
		Boolean cached = projectLocations.get(key);
		if (cached != null) return cached;

		boolean project = false;
		try {
			java.net.URI uri = cs.getLocation().toURI();
			Path location = Path.of(uri).toAbsolutePath().normalize();
			for (Set<Path> dirs : watchDirSources.values()) {
				for (Path dir : dirs) {
					if (location.startsWith(dir.toAbsolutePath().normalize())) {
						project = true;
						break;
					}
				}
				if (project) break;
			}
			if (!project) {
				for (Path jar : activeWatchJars) {
					if (location.equals(jar.toAbsolutePath().normalize())) {
						project = true;
						break;
					}
				}
			}
			if (!project) {
				for (Set<Path> jars : watchJarSources.values()) {
					for (Path jar : jars) {
						if (location.equals(jar.toAbsolutePath().normalize())) {
							project = true;
							break;
						}
					}
					if (project) break;
				}
			}
			if (!project && watchDirSources.isEmpty() && activeWatchJars.isEmpty()) {
				project = "file".equals(uri.getScheme()) && Files.isDirectory(location);
			}
		} catch (Throwable ignored) {
			project = false;
		}
		projectLocations.put(key, project);
		return project;
	}

	/**
	 * 一次批量 {@code retransformClasses} 把缺字节的类补进缓存。
	 *
	 * <p><b>必须批量</b>：{@code retransformClasses} 每次调用都是一次 safepoint，逐类调会把热更拖成
	 * 几百次停顿。这里只用一个捕获型 transformer，不改字节、不参与布局判定。</p>
	 */
	private static void warmBytecodeCache(List<Class<?>> classes) {
		if (classes.isEmpty()) return;
		Class<?>[] targets = classes.toArray(new Class<?>[0]);
		Map<String, byte[]> captured = new HashMap<>();
		ClassFileTransformer capture = new ClassFileTransformer() {
			@Override
			public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			                        ProtectionDomain pd, byte[] buffer) {
				if (classBeingRedefined != null && className != null && buffer != null) {
					captured.put(className.replace('/', '.'), buffer);
				}
				return null;
			}
		};
		inst.addTransformer(capture, true);
		try {
			enterInternalRetransform(new LinkedHashSet<>(classes.stream().map(Class::getName).toList()));
			try {
				inst.retransformClasses(targets);
			} finally {
				exitInternalRedefine();
			}
		} catch (Throwable t) {
			warn("[LAYOUT-READERS] bulk retransform for cache warm-up failed: " + t);
			return;
		} finally {
			inst.removeTransformer(capture);
		}
		captured.forEach(bytecodeCache::putIfAbsent);
	}


	/**
	 * 读取布尔开关：优先 {@code primary}，缺省时回退到 {@code alias}。
	 *
	 * <p>{@code alias} 存在的唯一理由是兼容 {@code docs/topology/06-runtime-and-perf.md} §4 里
	 * 已经对外公布的 {@code remold.anonAlign.*} 拼写 —— 对一个"止血开关"而言，
	 * "按文档写下的名字被静默忽略"远比"同一个开关多认一个名字"危险。</p>
	 *
	 * <p><b>注意</b>：本方法会在类初始化期（字段赋值时）被调用，因此**不得**打日志 ——
	 * {@code logger} 字段声明在类体后部，此时仍为 {@code null}。</p>
	 */
	private static boolean boolProp(String primary, String alias, boolean def) {
		String v = System.getProperty(primary);
		if (v == null && alias != null) v = System.getProperty(alias);
		return v == null ? def : Boolean.parseBoolean(v.trim());
	}

	private static int intProp(String primary, String alias, int def) {
		String v = System.getProperty(primary);
		if (v == null && alias != null) v = System.getProperty(alias);
		if (v == null) {
			v = System.getProperty("remold.file_shake_ms");
		}
		if (v == null) {
			String envKey = primary.replace('.', '_').toUpperCase();
			v = System.getenv(envKey);
			if (v == null && alias != null) v = System.getenv(alias.replace('.', '_').toUpperCase());
			if (v == null) v = System.getenv(primary);
			if (v == null && alias != null) v = System.getenv(alias);
		}
		if (v == null || v.trim().isEmpty()) return def;
		try {
			int val = Integer.parseInt(v.trim());
			return val >= 0 ? val : def;
		} catch (NumberFormatException e) {
			return def;
		}
	}

	public static void initConfig() {
		info("DEBUG: " + DEBUG);
		REDEFINE_MODE = RedefineMode.valueOfFail(System.getProperty("remold.agent.redefine_mode", "inject"), RedefineMode.inject);
		info("Redefine Mode: " + REDEFINE_MODE);
		String bl = System.getProperty("remold.agent.hotswap_blacklist", "").trim();
		HOTSWAP_BLACKLIST = bl.isEmpty() ? new String[0] : bl.split(",");
		info("Injection Blacklist: " + String.join(",", HOTSWAP_BLACKLIST));
		INIT_FIX = boolProp("remold.agent.init_fix", "remold.initFix", true);
		FORCE_STATIC_LAMBDA = boolProp("remold.agent.force_static_lambda", "remold.agent.force_static_lambdas", false);
		for (String degradedSwitch : degradeForMissingEnhancedRedefinition(isEnhancedHotswapEnabled())) {
			warn("DEGRADED: " + degradedSwitch + " forced OFF. " + degradationReason(degradedSwitch));
		}
		info("InitFix: " + INIT_FIX);
		info("Force Static Lambda: " + FORCE_STATIC_LAMBDA);
		ENABLE_HOTSWAP_EVENT = Boolean.parseBoolean(System.getProperty("remold.agent.hotswap_event", "false"));
		info("HotSwap Event: " + ENABLE_HOTSWAP_EVENT);
		FORCE_REINIT = Boolean.parseBoolean(System.getProperty("remold.agent.force_reinit", "true"));
		info("Force Reinit: " + FORCE_REINIT);
		LAMBDA_ALIGN = Boolean.parseBoolean(System.getProperty("remold.agent.lambda_align", "true"));
		info("Lambda Align: " + LAMBDA_ALIGN);
		if (LAMBDA_ALIGN) {
			info("Lambda Alignment ENABLED. Warning: This may cause logical shifts if lambdas are reordered.");
		}
		FILE_SHAKE_MS = intProp("remold.agent.file_shake_ms", "remold.fileShakeMs", FILE_SHAKE_MS);
		info("File Shake MS: " + FILE_SHAKE_MS);
		UI_HOOK = Boolean.parseBoolean(System.getProperty("remold.agent.ui_hook", "false"));
		info("UI Hook: " + UI_HOOK);
		boolean cellPropertyRef = Boolean.parseBoolean(System.getProperty("remold.agent.cell_property_ref", "false"));
		info("Cell Property Ref: " + cellPropertyRef);
		loadConfiguredPlugins();
		for (HotswapPlugin plugin : plugins) {
			try {
				plugin.onConfigReload();
			} catch (Throwable t) {
				error("[HotswapPlugin] Error reloading plugin config: " + plugin.getName(), t);
			}
		}
		info("Anon Align: " + ANON_ALIGN + " (strict=" + ANON_STRICT + ", debug=" + ANON_DEBUG
		     + ", maxPerHost=" + AnonClassAligner.MAX_ANON_PER_HOST
		     + ", timeoutMs=" + AnonClassAligner.ALIGN_TIMEOUT_MS
		     + ", layoutGate=" + ANON_LAYOUT_GATE + ", redefineLayoutGate=" + LAYOUT_GATE
		     + ", localClassGuard=" + LOCAL_CLASS_GUARD + ")");
		if (ANON_ALIGN) {
			info("Anonymous Class Alignment ENABLED. Ambiguity is resolved conservatively (never by class name).");
		} else {
			info("Anonymous Class Alignment DISABLED. Host classes containing anonymous classes will be rejected as a whole.");
		}
		info("Structural HotSwap Supported: " + isEnhancedHotswapEnabled());
	}

	private static void loadConfiguredPlugins() {
		if (inst == null) return;

		// 1. 读取请求启用的插件名称/过滤集合（若未配置或为 * / all，则默认加载发现的所有插件）
		String pluginsProp = System.getProperty("remold.plugins");
		Set<String> requestedPlugins = null;
		if (pluginsProp != null && !pluginsProp.trim().isEmpty() && !pluginsProp.trim().equals("*") && !pluginsProp.trim().equalsIgnoreCase("all")) {
			requestedPlugins = new HashSet<>();
			for (String name : pluginsProp.split("[,;]")) {
				String trimmed = name.trim().toLowerCase();
				if (!trimmed.isEmpty()) requestedPlugins.add(trimmed);
			}
		}

		List<JarFile> pluginJars = new ArrayList<>();

		// 2. 读取并通过 Instrumentation 追加外部插件 JAR 到系统类加载器搜索路径
		String pluginJarsProp = System.getProperty("remold.plugin.jars");
		if (pluginJarsProp != null && !pluginJarsProp.trim().isEmpty()) {
			String[] jarPaths = pluginJarsProp.split("[;," + File.pathSeparator + "]");
			for (String rawPath : jarPaths) {
				String path = rawPath.trim();
				if (path.isEmpty()) continue;
				File jarFile = new File(path);
				if (jarFile.exists()) {
					try {
						JarFile jf = new JarFile(jarFile);
						inst.appendToSystemClassLoaderSearch(jf);
						pluginJars.add(jf);
						info("[Plugin] Appended plugin JAR to system search: " + jarFile.getAbsolutePath());
					} catch (Throwable t) {
						error("[Plugin] Failed to append plugin JAR: " + path, t);
					}
				} else {
					warn("[Plugin] Specified plugin JAR does not exist: " + path);
				}
			}
		}

		// 3. 从外部插件 JAR 的元数据中动态解析插件类配置 (SPI services 或 Manifest 声明，无任何名称硬编码)
		Set<String> candidateClassNames = new LinkedHashSet<>();
		for (JarFile jf : pluginJars) {
			// A. 读取 META-INF/services/remold.HotswapPlugin 服务发现配置
			try {
				ZipEntry serviceEntry = jf.getEntry("META-INF/services/remold.HotswapPlugin");
				if (serviceEntry != null) {
					try (BufferedReader reader = new BufferedReader(new InputStreamReader(jf.getInputStream(serviceEntry), StandardCharsets.UTF_8))) {
						String line;
						while ((line = reader.readLine()) != null) {
							line = line.trim();
							if (!line.isEmpty() && !line.startsWith("#")) {
								candidateClassNames.add(line);
							}
						}
					}
				}
			} catch (Throwable t) {
				warn("[Plugin] Failed to read SPI services descriptor from " + jf.getName() + ": " + t.getMessage());
			}

			// B. 读取 Manifest 中的 Remold-Plugin-Class 或 Plugin-Class 声明
			try {
				Manifest manifest = jf.getManifest();
				if (manifest != null) {
					String pluginAttr = manifest.getMainAttributes().getValue("Remold-Plugin-Class");
					if (pluginAttr == null) {
						pluginAttr = manifest.getMainAttributes().getValue("Plugin-Class");
					}
					if (pluginAttr != null && !pluginAttr.trim().isEmpty()) {
						for (String cls : pluginAttr.split("[,;]")) {
							String trimmed = cls.trim();
							if (!trimmed.isEmpty()) candidateClassNames.add(trimmed);
						}
					}
				}
			} catch (Throwable t) {
				warn("[Plugin] Failed to read Manifest from " + jf.getName() + ": " + t.getMessage());
			}
		}

		// 4. 实例化并通过插件自声明的名称 (plugin.getName()) 进行匹配注册
		for (String className : candidateClassNames) {
			try {
				Class<?> cls = Class.forName(className, true, ClassLoader.getSystemClassLoader());
				if (HotswapPlugin.class.isAssignableFrom(cls)) {
					HotswapPlugin p = (HotswapPlugin) cls.getDeclaredConstructor().newInstance();
					String pluginName = p.getName() != null ? p.getName().trim().toLowerCase() : "";
					String classLower = className.toLowerCase();
					if (requestedPlugins == null || requestedPlugins.contains(pluginName) || requestedPlugins.contains(classLower)) {
						registerPlugin(p);
						info("[Plugin] Automatically loaded and registered plugin: " + p.getName() + " (" + className + ")");
					}
				} else {
					warn("[Plugin] Class " + className + " does not implement HotswapPlugin");
				}
			} catch (Throwable t) {
				error("[Plugin] Failed to instantiate plugin class '" + className + "': " + t.getMessage(), t);
			}
		}

		// 5. 通过标准 ServiceLoader 探测系统类加载器中已注册的 HotswapPlugin 服务实现
		try {
			ServiceLoader<HotswapPlugin> loader = ServiceLoader.load(HotswapPlugin.class, ClassLoader.getSystemClassLoader());
			for (HotswapPlugin p : loader) {
				String pluginName = p.getName() != null ? p.getName().trim().toLowerCase() : "";
				String classLower = p.getClass().getName().toLowerCase();
				if (requestedPlugins == null || requestedPlugins.contains(pluginName) || requestedPlugins.contains(classLower)) {
					registerPlugin(p);
				}
			}
		} catch (Throwable ignored) { }

		// 6. 如果用户显式传入了类名（包含 package 点号且尚未被注册），尝试直接反射加载
		if (requestedPlugins != null) {
			for (String requested : requestedPlugins) {
				if (requested.contains(".")) {
					boolean alreadyRegistered = false;
					for (HotswapPlugin p : plugins) {
						if (p.getClass().getName().equalsIgnoreCase(requested)) {
							alreadyRegistered = true;
							break;
						}
					}
					if (!alreadyRegistered) {
						try {
							Class<?> cls = Class.forName(requested, true, ClassLoader.getSystemClassLoader());
							if (HotswapPlugin.class.isAssignableFrom(cls)) {
								HotswapPlugin p = (HotswapPlugin) cls.getDeclaredConstructor().newInstance();
								registerPlugin(p);
								info("[Plugin] Loaded explicit plugin class: " + p.getName() + " (" + requested + ")");
							}
						} catch (Throwable t) {
							error("[Plugin] Failed to load explicit plugin class '" + requested + "': " + t.getMessage(), t);
						}
					}
				}
			}
		}
	}
	//endregion

	//region Class Retransformation
	/** 对外api，刷新已加载的类（UI 按钮 B 会直接调用，必须与热更互斥） */
	public static void retransformLoaded() {
		HOTSWAP_LOCK.lock();
		try {
			retransformLoaded(inst.getAllLoadedClasses());
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	private static void retransformLoaded(Class<?>[] classes) {
		info("Force retransform all loaded classes...");
		List<Class<?>> candidates = new ArrayList<>();
		for (Class<?> loadedClass : classes) {
			if (!inst.isModifiableClass(loadedClass)) continue;
			if (isBlacklisted(loadedClass.getName())) continue;
			if (bytecodeCache.containsKey(loadedClass.getName())) continue;

			candidates.add(loadedClass);
		}
		if (candidates.isEmpty()) return;
		info("Found " + candidates.size() + " classes loaded before Agent start. Retransforming...");
		try {
			inst.retransformClasses(candidates.toArray(new Class[0]));
			info("Retransform complete.");
		} catch (UnmodifiableClassException e) {
			error("Failed to retransform some classes", e);
		} catch (Throwable t) {
			error("Critical error during retransform", t);
		}
	}
	//endregion

	//region HotSwap Core Logic
	public static volatile ConcurrentHashMap<String, Class<?>> loadedClassesMap = new ConcurrentHashMap<>();


	private static void loadClassSnap(Class<?>[] classes) {
		var newMap = new ConcurrentHashMap<String, Class<?>>((int) (classes.length / 0.75f) + 1);
		for (Class<?> c : classes) {
			String   name     = c.getName();
			Class<?> existing = newMap.get(name);
			if (existing == null || isChildClassLoader(c.getClassLoader(), existing.getClassLoader())) {
				newMap.put(name, c);
			}
		}
		loadedClassesMap = newMap;              // 原子切换，读者要么看到完整旧 map，要么完整新 map
	}

	/**
	 * 处理文件变化的核心逻辑
	 */
	private static void processChanges(Set<Path> changedFiles, Class<?>[] classes) {
		refreshPackageLoaders(classes);

		// 获取当前所有已加载类的快照
		loadClassSnap(classes);

		List<ClassDefinition> definitions = new ArrayList<>();
		// List<Class<?>>        unloadedClasses  = new ArrayList<>();
		int skippedCount  = 0;
		int injectedCount = 0;

		Map<String, byte[]> newBatchBytes = new LinkedHashMap<>(changedFiles.size());
		Map<String, Path>   classToPath   = new HashMap<>(changedFiles.size());
		// 本轮"改了但没生效"的台账：摘要行据此点名跳过了谁（见 RoundSkips）。
		RoundSkips skips = new RoundSkips();
		for (Path p : changedFiles) {
			try {
				byte[] bc = Files.readAllBytes(p);
				String cn = Utils.getClassNameASM(bc);
				if (cn != null) {
					newBatchBytes.put(cn, bc);
					classToPath.put(cn, p);
				} else {
					// 不并进 skippedCount：那个计数只统计"磁盘哈希没变"的类，含义是"本来就没改"。
					// 读不出类名是"改了但读不了"，属于跳过，进台账由摘要点名。
					skips.add(p.toString(), RoundSkips.verdict("NO-CLASS-NAME",
						"unreadable class file", "recompile it and hot-swap again"));
					error("[SKIP] No className: " + p);
				}
			} catch (Throwable ignored) { }
		}

		// 收集需要对齐匿名类的所有宿主类：仅当宿主自身或其内部类在磁盘上发生变化时才参与对齐
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
			if (changedOnDisk) {
				String host = cn.replaceAll("\\$\\d+(\\$\\d+)*$", "");
				hostClassesToAlign.add(host);
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

		for (String hostName : hostClassesToAlign) {
			String hostSlash = hostName.replace('.', '/');

			// 预热并收集已加载但尚未缓存的旧匿名类字节码
			for (Map.Entry<String, Class<?>> entry : loadedClassesMap.entrySet()) {
				String cName = entry.getKey();
				if (AnonClassAligner.isAnonymousClassName(hostSlash, cName)) {
					if (!bytecodeCache.containsKey(cName)) {
						byte[] bc = fetchOriginalBytecode(entry.getValue());
						if (bc != null) bytecodeCache.put(cName, bc);
					}
				}
			}

			Map<String, byte[]> oldAnon = new HashMap<>();
			for (Map.Entry<String, byte[]> entry : bytecodeCache.entrySet()) {
				if (AnonClassAligner.isAnonymousClassName(hostSlash, entry.getKey())) {
					byte[] src = sourceBytecodeCache.get(entry.getKey());
					oldAnon.put(entry.getKey(), src != null ? src : entry.getValue());
				}
			}

			Map<String, byte[]> newAnon = new HashMap<>();
			for (Map.Entry<String, byte[]> entry : newBatchBytes.entrySet()) {
				if (AnonClassAligner.isAnonymousClassName(hostSlash, entry.getKey())) {
					newAnon.put(entry.getKey(), entry.getValue());
				}
			}

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

			java.util.function.Function<String, byte[]> oldRes = name -> {
				String dot = name.replace('/', '.');
				byte[] src = sourceBytecodeCache.get(dot);
				return src != null ? src : bytecodeCache.get(dot);
			};
			java.util.function.Function<String, byte[]> newRes = name -> newBatchBytes.get(name.replace('/', '.'));

			AnonClassAligner.Result res;
			try {
				res = AnonClassAligner.align(hostSlash, hostBytes, oldAnon, newAnon, oldRes, newRes,
				 RemoldAgent::hasLiveInstances,
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

			for (String orphan : res.orphanOldClasses) {
				allOrphanClasses.add(orphan.replace('/', '.'));
			}

			// 清理已被重命名位移的原编译类名（避免旧名字被当作新类重复处理）
			for (String originalAnonName : newAnon.keySet()) {
				newBatchBytes.remove(originalAnonName);
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
		for (Map.Entry<String, byte[]> e : newBatchBytes.entrySet()) {
			sourceBytecodeCache.put(e.getKey(), e.getValue());
		}

		// ---- 字节码重织入桥（Mixin / AspectJ / 自定义字节码增强器）----
		// 位置：源码级对齐之后、局部类门与布局门之前。
		// 保证对齐器看到的是纯净源码，而布局门与 JVM redefine 看到的是织入后的物理结构。
		if (!transformerBridges.isEmpty()) {
			for (String className : new ArrayList<>(newBatchBytes.keySet())) {
				byte[] rawBytes = newBatchBytes.get(className);
				if (rawBytes == null) continue;
				Class<?> targetClass = loadedClassesMap.get(className);
				ClassLoader cl = targetClass != null ? targetClass.getClassLoader() : findTargetClassLoader(className, classToPath.get(className));
				byte[] weaved = applyTransformerBridges(cl, className, targetClass, rawBytes);
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

		// ---- 同名局部类编号漂移止血门（docs/topology/07-layout-gate-and-risks.md §2）----
		//
		// 位置：宿主匿名对齐之后、实例布局门之前。局部类不在匿名对齐视野内（名字含简单名，被准入闸门
		// 拒绝），故单开一道门。命中即把宿主及其 `host$...` 整族移出本批，复用同一宿主组拒绝通道。
		applyLocalClassGuard(newBatchBytes, classToPath, transactions, skips);

		// ---- 实例状态布局安全门（docs/topology/07-layout-gate-and-risks.md §1，全体类，非仅匿名类）----
		//
		// 位置：宿主对齐之后、逐类 redefine 之前。这样具名类与匿名类一起被覆盖，
		// 且被拒的宿主组可以干净地复用 docs/topology/04-tiers-and-rejection.md §4 的"宿主 + 下属匿名类整组移出本批"通道
		// （`rejectHostGroup` / `dropHostGroup`）。
		//
		// 分工：对齐器的 Tier 4 门只挡匿名类的**合成捕获字段**（ClassDiff 会过滤掉它们，
		// 这里看不见）；本门只看现成的 `ClassDiff.changedFields`，即**非合成**字段的
		// 删/改类型/改静态性。纯新增放行（交给 InitFix）。
		applyRedefineLayoutGate(newBatchBytes, classToPath, transactions, skips);

		for (Map.Entry<String, byte[]> batchEntry : newBatchBytes.entrySet()) {
			String className = batchEntry.getKey();
			byte[] bytecode  = batchEntry.getValue();
			Path   path      = classToPath.get(className);
			if (DEBUG) log("Processing changes: " + (path != null ? path : className));

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

				long newHash      = calculateHash(bytecode); // 现在返回 long
				long classNameKey = CRC64.hashString(className); // 类名也转为 long

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
					byte[] oldBytecode = bytecodeCache.get(className);

					// 如果缓存里没有，主动触发一次 retransform 来"偷"取字节码
					if (oldBytecode == null) {
						oldBytecode = fetchOriginalBytecode(targetClass);
						if (oldBytecode != null) {
							bytecodeCache.put(className, oldBytecode);
						}
					}

					// 执行 ASM Diff
					if (oldBytecode != null) {
						if (LAMBDA_ALIGN) {
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
							java.util.function.Function<String, byte[]> oldResolver =
							 normalizeAlignResolver(name -> {
								byte[] src = sourceBytecodeCache.get(name.replace('/', '.'));
								return src != null ? src : bytecodeCache.get(name.replace('/', '.'));
							 }, alignLoader);
							java.util.function.Function<String, byte[]> newResolver =
							 normalizeAlignResolver(name -> newBatchBytes.get(name.replace('/', '.')), alignLoader);
							byte[] oldSrc = sourceBytecodeCache.get(className);
							byte[] alignOld = normalizeAlignBaseline(
							 oldSrc != null ? oldSrc : oldBytecode, alignSlash, alignLoader);
							newBytecode = LambdaAligner.align(alignOld, newBytecode, oldResolver, newResolver);
						}
					}
					if (oldBytecode != null) {
						ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(oldBytecode, newBytecode);
						ClassDiffUtil.logDiff(className, diff);
						if (diff.hierarchyChanged) {
							error("REJECTED: Class hierarchy change detected for " + className + ". JBR/DCEVM does not support changing superclass/interfaces reliably. Please RESTART application.");
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
						InitFix.transform(targetClass, newBytecode, diff);
					} else {
						log("[WARN] Cannot diff " + className + " (missing old bytecode). Proceeding with redefine.");
					}

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
					Path clHintPath = tmpToJar.containsKey(path)
					 ? resolveWatchDirPath(tmpToJar.get(path), className)
					 : path;
					// clHintPath == null 表示来自直接传入的 activeWatchJar，无虚拟 watchDir 路径，
					// 退化到纯包名推断（findTargetClassLoader(className)），inject 照常走，lazy_load 挂 tmpDir 路径
					if (REDEFINE_MODE == RedefineMode.inject) {
						if (injectNewClass(className, clHintPath, bytecode)) {
							bytecodeCache.put(className, bytecode);
							injectedCount++;
							// 注入成功即视为生效：指纹可立即落账（不经过 applyRedefinitions）。
							// 失败时不落账，下次触发会重试。
							synchronized (fileDiskHashes) {
								fileDiskHashes.put(CRC64.hashString(className), calculateHash(bytecode));
							}
						}
					} else if (REDEFINE_MODE == RedefineMode.lazy_load && UCP_APPEND) {
						ClassLoader loader = findTargetClassLoader(className, clHintPath);
						// lazy_load + jar：ClassLoader 推断用虚拟路径（clHintPath），
						// 实际挂载用真实 tmpDir 路径，否则 ClassLoader 找不到 class 文件
						Path mountPath = tmpToJar.containsKey(path) ? path : clHintPath;
						if (mountPath != null) {
							PackageUnsealer.unsealClassPackage(loader, className);
							mountForClass(loader, mountPath);
						}
						bytecodeCache.put(className, bytecode);
						// lazy_load 只是挂载路径，类尚未真正加载生效 —— 指纹**不**落账，
						// 等它真正被加载/重定义时再记，否则会漏掉后续的首次加载。
					}
				}
			} catch (Throwable e) {
				error("Failed to process " + path, e);
			}
		}

		// 批量执行重定义（针对已加载类）
		RedefineOutcome outcome     = applyRedefinitions(definitions, transactions.values(), skips);
		Set<String>     redefinedOk = outcome.successful();
		Set<String>     rolledBack  = outcome.rolledBack();

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
				// 成功：落账指纹
				synchronized (fileDiskHashes) {
					fileDiskHashes.put(CRC64.hashString(className), pendingHashes.get(CRC64.hashString(className)));
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

		List<ClassDefinition> successfulDefinitions = new ArrayList<>(definitions.size());
		for (ClassDefinition def : definitions) {
			if (!redefinedOk.contains(def.getDefinitionClass().getName())) continue;
			successfulDefinitions.add(def);
			try {
				InitFix.afterRedefine(def.getDefinitionClass());
			} catch (Throwable e) {
				error("Failed to process InitFix.", e);
				InitFix.afterRedefineFailed(def.getDefinitionClass());
			}
		}

		// 必须在 InitFix 补丁（静态字段与存量实例字段）全部就位后，再对成功重定义的类触发 @OnReload 回调；
		// 严禁在补丁就绪前触发，否则回调读到未初始化的零值（时序反转）。
		processAnnotations(successfulDefinitions);
		notifyBatchListeners(successfulDefinitions);

		// 与上面的 [HOTSWAP-SKIPPED] 区分：这里是"磁盘哈希没变，本来就没改"，不是被门拦下。
		if (skippedCount > 0) info("Unchanged: " + skippedCount + " class(es) had identical bytes on disk.");
		if (injectedCount > 0) info("Injected " + injectedCount + " new classes.");
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
	 * <p>仅用于布局不兼容的类，因此调用频率很低。</p>
	 */
	static boolean hasLiveInstances(Class<?> c) {
		if (c == null) return false;
		String dotClassName = c.getName();
		Boolean cached = LIVE_INSTANCE_CACHE.get(dotClassName);
		if (cached != null) return cached;

		boolean result;
		long    t0 = System.nanoTime();
		try {
			if (!LibTool.initialized()) {
				// 尝试初始化；失败会抛 UnsatisfiedLinkError
				LibTool.init();
			}
			result = LibTool.getInstances(c).length > 0;
		} catch (Throwable t) {
			// JVMTI 不可用 / 初始化失败 / 扫描异常 —— 一律按"有实例"处理（保守）。
			warn("[ANON-LAYOUT] Cannot scan instances for " + dotClassName
			     + " (" + t.getClass().getSimpleName() + "); assuming LIVE instances exist.");
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
	private static void resetLiveInstanceCache() {
		LIVE_INSTANCE_CACHE.clear();
		LIVE_INSTANCE_SCAN_MILLIS.set(0);
	}

	/** 实例判定缓存：点分类名 → 是否存活实例。见 {@link #hasLiveInstances}。 */
	private static final Map<String, Boolean>                   LIVE_INSTANCE_CACHE       = new ConcurrentHashMap<>();
	/** 累计的实例扫描耗时（毫秒），用于观察全堆遍历开销。 */
	static final         java.util.concurrent.atomic.AtomicLong LIVE_INSTANCE_SCAN_MILLIS =
	 new java.util.concurrent.atomic.AtomicLong();

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
			Object[] instances = LibTool.initialized() ? LibTool.getInstances(clazz) : InstanceTracker.getInstances(clazz).toArray();
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
			for (Object obj : instances) {
				for (Method m : instanceMethods) {
					try {
						m.invoke(obj);
						if (DEBUG) log("[Reload] Invoked @OnReload on " + obj + "." + m.getName());
					} catch (Throwable e) {
						error("Error invoking @OnReload on " + obj + "." + m.getName(), e);
					}
				}
			}
		}
	}


	static boolean isBlacklisted(String className) {
		if (HOTSWAP_BLACKLIST == null) return false;
		for (String prefix : HOTSWAP_BLACKLIST) {
			if (!prefix.isEmpty() && className.startsWith(prefix)) return true;
		}
		return false;
	}

	public static byte[] fetchBytecodeMemory(Class<?> clazz) throws UnmodifiableClassException {
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
	public static byte[] fetchCurrentBytecode(Class<?> clazz) {
		String className = clazz.getName();
		if (bytecodeCache.containsKey(className)) return bytecodeCache.get(className);
		return fetchOriginalBytecode(clazz);
	}
	public static byte[] fetchOriginalBytecode(Class<?> clazz) {
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
							Path   jarPath    = Paths.get(new java.net.URI(jarFileStr)).toAbsolutePath();
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
	//endregion

	//region Class Injection and Redefinition
	/**
	 * 直接定义类，而不是被动加载
	 */
	private static boolean injectNewClass(String className, Path path, byte[] bytes) {
		try {
			ClassLoader loader = findTargetClassLoader(className, path);
			if (loader == null) {
				error("Could not find a suitable ClassLoader for new class: " + className);
				return false;
			}

			PackageUnsealer.unsealClassPackage(loader, className);
			Reflect.defineClass(className, bytes, 0, bytes.length, loader, null);
			info("[INJECTED] Successfully defined new class: " + className + " into " + loader);
			return true;
		} catch (LinkageError le) {
			if (DEBUG) error("Class already loaded: " + className, le);
			return true;
		} catch (Exception e) {
			error("Failed to inject new class: " + className + ". CAUTION: This may cause NoClassDefFoundError.", e);
			return false;
		}
	}

	/**
	 * 一次对齐操作的事务封装。
	 *
	 * <p>保证已加载类、未加载类的 pendingAlignedClasses 注入与 bytecodeCache 的提交具有原子性与一致性：
	 * <ul>
	 *   <li>重定义成功：整体提交（commit），将对齐类写入 pending 并更新 cache；</li>
	 *   <li>重定义失败或跳过：整体回滚（rollback），撤销新版本的 pending 注入，
	 *       并将生效旧版本字节码钉在 pending 中（{@code pinOldToPending}），防止旧宿主未来首次加载时读到磁盘上位移后的错误内容。</li>
	 * </ul>
	 */
	public static class AlignmentTransaction {
		public final String              hostName;
		public final Map<String, byte[]> pendingAdds   = new LinkedHashMap<>();
		public final Map<String, byte[]> cacheUpdates  = new LinkedHashMap<>();
		public final Map<String, byte[]> pinOldBytes   = new LinkedHashMap<>();
		public final Set<String>         targetClasses = new LinkedHashSet<>();
		public       boolean             preRegistered = false;
		public       boolean             committed     = false;

		public AlignmentTransaction(String hostName) {
			this.hostName = hostName;
		}

		public void preRegister() {
			if (preRegistered || committed) return;
			preRegistered = true;
			for (Map.Entry<String, byte[]> entry : pendingAdds.entrySet()) {
				String targetSlash = entry.getKey().replace('.', '/');
				String targetDot   = entry.getKey().replace('/', '.');
				byte[] bytes       = entry.getValue();
				AnnotationTransformer.pendingAlignedClasses.put(targetSlash, bytes);
				AnnotationTransformer.pendingAlignedClasses.put(targetDot, bytes);
			}
		}

		public void commit() {
			if (committed) return;
			preRegister();
			committed = true;
			for (Map.Entry<String, byte[]> entry : cacheUpdates.entrySet()) {
				bytecodeCache.put(entry.getKey().replace('/', '.'), entry.getValue());
			}
		}

		public void rollback(boolean pinOldToPending) {
			if (committed) return;
			for (String key : pendingAdds.keySet()) {
				AnnotationTransformer.pendingAlignedClasses.remove(key.replace('.', '/'));
				AnnotationTransformer.pendingAlignedClasses.remove(key.replace('/', '.'));
			}
			if (pinOldToPending) {
				// 将被磁盘新文件覆盖但重定义未生效的旧版本字节码钉在 pending 中
				for (Map.Entry<String, byte[]> entry : pinOldBytes.entrySet()) {
					if (entry.getValue() != null) {
						String slash = entry.getKey().replace('.', '/');
						String dot   = entry.getKey().replace('/', '.');
						AnnotationTransformer.pendingAlignedClasses.put(slash, entry.getValue());
						AnnotationTransformer.pendingAlignedClasses.put(dot, entry.getValue());
					}
				}
			}
		}
	}

	/**
	 * {@code docs/topology/04-tiers-and-rejection.md} §4 宿主级拒绝：把「宿主类 + 其下属全部匿名类」整体移出本批重定义。
	 *
	 * <p><b>为什么必须连新侧匿名类的原始类名一起移除</b>：一旦不做对齐，新编译产物的 {@code Foo$2}
	 * 与 JVM 中已加载的旧 {@code Foo$2} 同名但语义不同，把它送进 redefinition 就是把老实例的方法表
	 * 交给无关的新类 —— 正是本模块存在的理由。同理，宿主本身也必须一起移除，否则它的新字节码会
	 * 引用到没有被对齐过的 {@code Foo$N}。</p>
	 *
	 * <p>因此拒绝的语义是"这一组本轮完全不动"，与 {@code docs/topology/04-tiers-and-rejection.md} §4
	 * 的"以宿主 + 其下属全部匿名类为原子单元整体拒绝回滚"一致。</p>
	 * @param t 触发拒绝的异常；{@link AnonClassAligner.AlignmentRejectedException} 视为**预期**拒绝
	 *          （打 {@code [HOTSWAP-REJECT]} 告警），其它异常视为对齐器缺陷（打 error + 堆栈）
	 * @param skips 本轮摘要台账：宿主与整组匿名类都要登记，否则摘要里只剩"成功了几个"
	 */
	private static void rejectHostGroup(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                    String hostName, RoundSkips skips, Throwable t) {
		boolean expected = t instanceof AnonClassAligner.AlignmentRejectedException;
		String reason = expected
		 ? ((AnonClassAligner.AlignmentRejectedException) t).reason
		 : (t.getClass().getSimpleName() + ": " + t.getMessage());
		if (expected) {
			warn("[HOTSWAP-REJECT] Structural ambiguity detected in " + hostName
			     + ". Redefine skipped safely. Reason: " + reason
			     + ". Please hot-swap again or restart.");
		} else {
			error("[HOTSWAP-REJECT] Anonymous class alignment failed in " + hostName
			      + ". Redefine skipped safely. Reason: " + reason
			      + ". Please hot-swap again or restart.", t);
		}
		List<String> dropped = dropHostGroup(newBatchBytes, classToPath, hostName);
		skips.addGroup(dropped, hostName, RoundSkips.verdict("ANON-ALIGN-REJECT", reason,
			"hot-swap again, or restart if it repeats"));
	}

	/** 从批次中移除一个类（点分与斜杠两种键形态都移除，避免因键写法不同而漏删）。 */
	private static void dropFromBatch(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                  String className) {
		newBatchBytes.remove(className);
		classToPath.remove(className);
		String alt = className.indexOf('/') >= 0 ? className.replace('/', '.') : className.replace('.', '/');
		newBatchBytes.remove(alt);
		classToPath.remove(alt);
	}

	/**
	 * 宿主组移除核心：把「宿主 + 其下属全部匿名类」从本批移出。
	 *
	 * <p>{@link #rejectHostGroup}（对齐器拒绝）与 {@link #applyRedefineLayoutGate}（布局门拒绝）
	 * 共用同一机制，只是日志与触发理由不同 —— 保证两种拒绝的"原子单元"语义完全一致。</p>
	 */
	private static List<String> dropHostGroup(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                          String hostName) {
		String hostSlash = hostName.replace('.', '/');
		List<String> anons = new ArrayList<>();
		for (String cn : new ArrayList<>(newBatchBytes.keySet())) {
			if (AnonClassAligner.isAnonymousClassName(hostSlash, cn)) anons.add(cn);
		}
		dropFromBatch(newBatchBytes, classToPath, hostName);
		for (String anon : anons) dropFromBatch(newBatchBytes, classToPath, anon);
		List<String> dropped = new ArrayList<>(1 + anons.size());
		dropped.add(hostName);
		dropped.addAll(anons);
		return dropped;
	}

	/**
	 * 一轮热更里"改了但没生效"的类：类名 → 判决码。
	 *
	 * <p>存在的唯一理由是<b>摘要行</b>。{@code HotSwap successful: N classes redefined} 只报了成功的
	 * 个数，被布局门 / 对齐器 / 结构门拦下的类不会出现在任何一行汇总里：用户看到"成功"，实际运行态
	 * 还是旧代码，只能靠翻全量日志才知道自己关心的那个类被跳过了。这里把"跳过了谁"集中起来，
	 * 紧跟成功行点名。</p>
	 *
	 * <p>只收"<b>本该生效却被拦下</b>"的类。磁盘哈希未变（本来就没改）不属于这一类 —— 把它也收进来，
	 * 每轮都会刷出一大串无关类名，摘要立刻失去意义。</p>
	 *
	 * <p>实例随 {@code processChanges} 逐层传递，<b>不做静态共享</b>：并发热更时两轮的记录不能混。</p>
	 *
	 * <p>包内可见（而非 private）只为让 {@code tests/src/remold/HotswapSkipSummaryTest} 直接断言
	 * 台账语义与摘要格式；接线点仍需实机热更才能覆盖。</p>
	 */
	static final class RoundSkips {
		/** 摘要里最多点名的类数；超出只报个数，完整信息在同轮的拒绝日志里。 */
		static final int MAX_LISTED = 15;

		private final Map<String, String> byClass = new LinkedHashMap<>();

		/** 记一条。同名只保留<b>首个</b>判决 —— 第一个拦下它的门才是真正原因。 */
		void add(String className, String verdict) {
			if (className == null || className.isEmpty()) return;
			byClass.putIfAbsent(className, verdict == null ? "SKIPPED" : verdict);
		}

		/**
		 * 整组记（宿主 + 其匿名类）。
		 *
		 * <p>从属的匿名类单独标注"属于哪个宿主组"，否则摘要里冒出一串 {@code Foo$3} 完全看不懂
		 * 它们为什么被跳过。</p>
		 */
		void addGroup(Collection<String> classNames, String host, String verdict) {
			if (classNames == null) return;
			for (String n : classNames) {
				add(n, n.equals(host) ? verdict : verdict + " (same host group as " + host + ")");
			}
		}

		boolean isEmpty() { return byClass.isEmpty(); }

		/** 被跳过的类数（用于成功行上的"还有几个没进来"）。 */
		int size() { return byClass.size(); }

		/**
		 * 拼一条紧凑判决：{@code CODE: detail — action}（detail / action 为空则省略）。
		 *
		 * <p>摘要行必须能<b>单独读懂</b>：只给 {@code LAYOUT-REJECT} 这个码，用户还得往上翻几屏
		 * 才知道是哪个字段、该干什么。所以判决码后面直接跟"具体是什么"和"怎么办"，
		 * 形如 {@code LAYOUT-REJECT: field(s) removed: COST — restore the removed field(s) or restart}。</p>
		 */
		static String verdict(String code, String detail, String action) {
			StringBuilder sb = new StringBuilder(code == null ? "SKIPPED" : code);
			if (detail != null && !detail.isEmpty()) sb.append(": ").append(detail);
			if (action != null && !action.isEmpty()) sb.append(" — ").append(action);
			return sb.toString();
		}

		/**
		 * 打摘要。
		 *
		 * <p>必须走 {@code warn}：这是"你以为生效了其实没有"的路径，不能受 {@code DEBUG} 门控。</p>
		 *
		 * <p>方法名不叫 {@code log}：那会遮蔽外层的 {@code RemoldAgent.log(String)}，让本类里想打
		 * DEBUG 日志的代码编译不过（同名方法优先于外层静态导入式的解析）。</p>
		 */
		void report() {
			if (byClass.isEmpty()) return;
			warn("[HOTSWAP-SKIPPED] " + byClass.size() + " class(es) were skipped this round and did NOT"
			     + " take effect — the running code is still the previous version:");
			int shown = 0;
			for (Map.Entry<String, String> e : byClass.entrySet()) {
				if (shown++ >= MAX_LISTED) break;
				warn("[HOTSWAP-SKIPPED]   " + e.getKey() + "  <- " + e.getValue());
			}
			if (byClass.size() > MAX_LISTED) {
				warn("[HOTSWAP-SKIPPED]   ... and " + (byClass.size() - MAX_LISTED)
				     + " more (each one has its own reason in the log lines above).");
			}
		}
	}

	/** 由点分类名求宿主类名：{@code Foo$1$2 -> Foo}；非匿名（含具名内部类 {@code Foo$Bar}）原样返回。 */
	private static String hostNameOf(String className) {
		return className.replaceAll("\\$\\d+(\\$\\d+)*$", "");
	}

	/** 局部类止血门拒绝的宿主次数（诊断用，见 {@link #applyLocalClassGuard}）。 */
	static final java.util.concurrent.atomic.AtomicLong LOCAL_CLASS_REJECTED =
	 new java.util.concurrent.atomic.AtomicLong();

	/** 局部类止血门"放行但记录"（warn 模式）的次数。 */
	static final java.util.concurrent.atomic.AtomicLong LOCAL_CLASS_WAIVED =
	 new java.util.concurrent.atomic.AtomicLong();

	/**
	 * 同名局部类编号漂移止血（{@code docs/topology/07-layout-gate-and-risks.md} §2）的接线。
	 *
	 * <p>判决是纯函数 {@link LocalClassGuard#decide}；这里只负责"解析批次与已加载旧侧字节、执行动作、
	 * 回滚事务"。</p>
	 *
	 * <p>候选宿主 = 本批里出现的局部类（{@code EnclosingMethod.owner}）。对每个候选宿主，旧侧扫
	 * {@link #loadedClassesMap} 中其 {@code host$...} 已加载家族；新旧两侧交给纯函数按
	 * {@code (宿主, 简单名)} 归并，任一侧同名计数 ≥2 即命中。{@code reject} 时把宿主及其
	 * {@code host$...} 整族移出本批。</p>
	 */
	private static void applyLocalClassGuard(Map<String, byte[]> newBatchBytes,
	                                         Map<String, Path>   classToPath,
	                                         Map<String, AlignmentTransaction> transactions,
	                                         RoundSkips skips) {
		if (LocalClassGuard.MODE_OFF.equals(LOCAL_CLASS_GUARD)) return;

		// 新侧：解析批次全部类，收集局部类宿主（斜杠内部名）。
		List<ClassNode> newClasses = new ArrayList<>();
		Set<String> hosts = new LinkedHashSet<>();
		for (Map.Entry<String, byte[]> e : newBatchBytes.entrySet()) {
			ClassNode cn = parseClass(e.getValue());
			if (cn == null) continue;
			newClasses.add(cn);
			String h = LocalClassGuard.hostOf(cn);
			if (h != null) hosts.add(h);
		}
		if (hosts.isEmpty()) return;

		// 旧侧：对每个候选宿主，扫已加载的 host$... 家族。
		List<ClassNode> oldClasses = new ArrayList<>();
		for (String hostSlash : hosts) {
			String hostDot = hostSlash.replace('/', '.');
			for (Map.Entry<String, Class<?>> e : loadedClassesMap.entrySet()) {
				String name = e.getKey();
				if (!name.startsWith(hostDot + "$")) continue;
				byte[] bytes = bytecodeCache.get(name);
				if (bytes == null) {
					bytes = fetchOriginalBytecode(e.getValue());
					if (bytes != null) bytecodeCache.put(name, bytes);
				}
				if (bytes == null) continue;
				ClassNode cn = parseClass(bytes);
				if (cn != null) oldClasses.add(cn);
			}
		}

		LocalClassGuard.Decision d = LocalClassGuard.decide(oldClasses, newClasses, LOCAL_CLASS_GUARD,
			newBatchBytes.keySet());

		if (d.action() == LocalClassGuard.Action.PASS) {
			if (!d.collisions().isEmpty()) {
				LOCAL_CLASS_WAIVED.incrementAndGet();
				warn("[LOCAL-CLASS-WARN] same-named local classes in " + d.hosts()
				     + " (" + d.collisions() + "); redefining anyway because remold.agent."
				     + "local_class_guard=warn. Numbering may have shifted -> a live instance can silently"
				     + " run the wrong implementation. Set the default reject to refuse instead.");
			}
			return;
		}

		for (String hostDot : d.hosts()) {
			warn("[LOCAL-CLASS-REJECT] same-named local classes in " + hostDot + " (" + d.collisions()
			     + "). Redefine skipped safely: inserting/removing/reordering a same-named local class"
			     + " shifts their physical numbering, which would silently swap implementations for live"
			     + " instances. Host and its local/anonymous classes are skipped this round. TO PROCEED:"
			     + " restart (or make the local class simple name unique), or set -Dremold.agent."
			     + "local_class_guard=warn to force it and accept the mismatch.");
		}
		for (String dropped : d.dropped()) dropFromBatch(newBatchBytes, classToPath, dropped);
		// 摘要台账：宿主自己与它名下的局部/匿名类分开标注（多个宿主时不能互相挂错组）。
		String localVerdict = RoundSkips.verdict("LOCAL-CLASS-REJECT",
			"same-named local classes shifted their numbering",
			"rename the local class (or restart) to continue");
		for (String dropped : d.dropped()) {
			skips.add(dropped, d.hosts().contains(dropped)
			 ? localVerdict
			 : localVerdict + " (same host group as its enclosing class)");
		}
		for (String hostDot : d.hosts()) {
			LOCAL_CLASS_REJECTED.incrementAndGet();
			AlignmentTransaction tx = transactions.remove(hostDot);
			if (tx != null) tx.rollback(false);
		}
	}

	/** 解析类元数据（不取指令，只要 InnerClasses/EnclosingMethod）；失败返回 null（不阻断本批）。 */
	private static ClassNode parseClass(byte[] bytes) {
		if (bytes == null) return null;
		try {
			ClassNode cn = new ClassNode();
			new org.objectweb.asm.ClassReader(bytes)
			 .accept(cn, org.objectweb.asm.ClassReader.SKIP_CODE
			           | org.objectweb.asm.ClassReader.SKIP_DEBUG
			           | org.objectweb.asm.ClassReader.SKIP_FRAMES);
			return cn;
		} catch (Throwable t) {
			return null;
		}
	}

	/** 布局门拒绝的类/宿主次数（诊断用，见 {@link #applyRedefineLayoutGate}）。 */
	static final java.util.concurrent.atomic.AtomicLong LAYOUT_GATE_REJECTED =
	 new java.util.concurrent.atomic.AtomicLong();

	/** 一次拒绝的记录：原因 + 同一原因连续出现的次数。 */
	private record RejectRecord(String reason, int count) { }

	/**
	 * 类名 → 最近一次拒绝的记录。
	 *
	 * <p>拒绝<b>不会</b>更新运行态字节码（{@link #dropFromBatch} 只把类移出本批），而源码已经改了，
	 * 于是源码与运行态之间的差异会一直保留：下一次热更会 diff 出同一条变更、得到同一个结论。
	 * 这不是缺陷——放行就会让批次外读者在运行期抛 {@code LinkageError}——但用户很容易误以为
	 * "再热一次就好了"。这里统计次数，第二次起额外提示"这是循环，需要重启"。</p>
	 *
	 * <p>成功重定义后清除（见 {@link #clearRejected}）。</p>
	 */
	private static final Map<String, RejectRecord> REJECT_HISTORY = new ConcurrentHashMap<>();

	/** 记录一次拒绝，返回同一原因连续出现的次数（含本次）。 */
	public static int noteRejected(String className, String reason) {
		RejectRecord prev = REJECT_HISTORY.get(className);
		int n = (prev != null && prev.reason().equals(reason)) ? prev.count() + 1 : 1;
		REJECT_HISTORY.put(className, new RejectRecord(reason, n));
		return n;
	}

	/** 该类成功落地后清除拒绝记录。 */
	public static void clearRejected(String className) {
		REJECT_HISTORY.remove(className);
	}
	/** 布局门"放行但记录"的次数（warn 模式 / 无存活实例 waive）。 */
	static final java.util.concurrent.atomic.AtomicLong LAYOUT_GATE_WAIVED =
	 new java.util.concurrent.atomic.AtomicLong();

	/**
	 * 实例状态布局安全门（重定义层入口，{@code docs/topology/07-layout-gate-and-risks.md} §1，全体类）。
	 *
	 * <p>判据直接用现成的 {@code ClassDiff.changedFields}：纯新增放行（InitFix 初始化存活实例），
	 * 删除 / 同名改类型 / 改静态性则把该宿主及其匿名类整组移出本轮重定义。与对齐器 Tier 4 门互补 ——
	 * 那个门挡匿名类的合成捕获字段（被 {@code ClassDiff} 过滤，这里看不见），本门挡非合成字段。</p>
	 *
	 * <p>只检查<b>已加载</b>（有旧字节码、可能存在存活实例/静态状态）的类。未加载类没有旧状态可言，
	 * 首次加载会走新构造器，放行不会让任何东西读到零值。</p>
	 *
	 * <p>判决本身是纯函数 {@link #decideLayout}（给定旧/新字节、开关、批次类名集 → 动作 + 要移出的类）；
	 * 这里只负责"取字节、执行动作、撤销事务"的接线。{@code remold.agent.layout_gate=off} 时整体跳过。</p>
	 */
	/**
	 * 扫描"批次外读者"：本批被删的实例字段，在批次落地之后还有谁解析得到它
	 * （判据见 {@code docs/initfix/07-p05-probe-results.md} §13.6）。
	 *
	 * <h2>字节源：已加载的类只能读内存</h2>
	 * <p>已加载且不在本批的类，其字节码只从 {@link #bytecodeCache} 取——那是 JVM 里当前生效的那份。
	 * <b>严禁</b>回退去读磁盘构建目录：批次撕裂时（bulk 重定义失败后退回逐类、个别类失败被吞），
	 * 磁盘上的 {@code Reader.class} 可能已经是重编后的新版本，而 JVM 里跑的仍是旧字节码；
	 * 读磁盘会把真正的读者当成"已不再读"而漏掉。磁盘目录只用来补充<b>尚未加载</b>的工程类。</p>
	 *
	 * <p>取不到某个已加载工程类的内存字节时，本轮扫描判为<b>不完整</b>，整体退回存活实例判据
	 * ——把"没扫全"当成"没有读者"是本条最危险的读法。</p>
	 *
	 * <h2>范围</h2>
	 * <p>只覆盖工程自身的构建输出（由本批类的路径反推根目录）。JDK 与第三方 jar 不在范围内：
	 * 那里的读者属于已知边界，不是本函数能解决的（见 {@code docs/status.md} 风险 12）。</p>
	 *
	 * @return 类名 → 该类被删字段的批次外读者；空 map 表示本轮不做判定（没有删除，或扫描不完整）
	 */
	private static Map<String, RemovedMemberScan> scanRemovedMembers(
			Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath) {
		long start = System.nanoTime();

		// ---- 1. 本批被删的**实例**字段（静态字段不参与，理由见 decideLayout 的注释）----
		Map<String, List<RemovedMemberReaders.RemovedMember>> removedByClass = new LinkedHashMap<>();
		Map<String, byte[]> oldBatchBytes = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : newBatchBytes.entrySet()) {
			String className = e.getKey();
			byte[] oldBytes = bytecodeCache.get(className);
			if (oldBytes == null) {
				Class<?> c = loadedClassesMap.get(className);
				if (c != null) {
					try {
						oldBytes = fetchOriginalBytecode(c);
					} catch (Throwable ignored) {
						// 取不到旧基线：这个类无法判定，下面会因为 removedByClass 为空而整体跳过
					}
				}
			}
			if (oldBytes == null) continue;
			oldBatchBytes.put(internalName(className), oldBytes);
			List<RemovedMemberReaders.RemovedMember> removed = removedMembers(oldBytes, e.getValue());
			if (!removed.isEmpty()) removedByClass.put(className, removed);
		}
		if (removedByClass.isEmpty()) return Map.of();

		// ---- 2. 读者字节码：本批用新字节，批次外已加载的工程类用内存字节，未加载的工程类读磁盘 ----
		Map<String, byte[]> post = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : newBatchBytes.entrySet()) {
			post.put(internalName(e.getKey()), e.getValue());
		}

		Set<Path> roots = projectClassRoots(classToPath);
		List<Class<?>> missing = new ArrayList<>();
		for (Map.Entry<String, Class<?>> e : loadedClassesMap.entrySet()) {
			String name = e.getKey();
			if (newBatchBytes.containsKey(name)) continue;
			Class<?> clazz = e.getValue();
			// 数组类没有代码；hidden class（lambda 等）既不进 ClassFileTransformer 也不可重定义，
			// 它们引用的成员由宿主类的常量池覆盖。不滤掉这两类，uncached 会永远大于 0，
			// 判据等于永久失效（JBR 25 下 [Lp.Foo; 与 p.Foo$$Lambda/0x… 都会继承宿主的 CodeSource）。
			if (clazz.isArray() || clazz.isHidden()) continue;
			if (!inst.isModifiableClass(clazz)) continue;
			if (!isProjectClass(clazz, roots)) continue;
			byte[] bytes = bytecodeCache.get(name);
			if (bytes == null) {
				missing.add(clazz);
				continue;
			}
			post.putIfAbsent(internalName(name), bytes);
		}

		// Transformer 注册之前就已加载的漏网之鱼：一次批量 retransform 补齐（不要逐类调）。
		int uncached = 0;
		if (!missing.isEmpty()) {
			warmBytecodeCache(missing);
			for (Class<?> clazz : missing) {
				byte[] bytes = bytecodeCache.get(clazz.getName());
				if (bytes == null) {
					uncached++;
					continue;
				}
				post.putIfAbsent(internalName(clazz.getName()), bytes);
			}
		}
		if (uncached > 0) {
			warn("[LAYOUT-READERS] " + uncached + " loaded project class(es) have no cached bytecode;"
			     + " reader scan is inconclusive this round, falling back to the liveness check."
			     + " (Reading them from the build output would be wrong: after a torn batch the file"
			     + " may already be the new version while the JVM still runs the old one.)");
			return Map.of();
		}
		for (Path root : roots) collectClassFiles(root, post);

		// ---- 3. 判定 ----
		Map<String, byte[]> pre = new LinkedHashMap<>(post);
		pre.putAll(oldBatchBytes);
		List<RemovedMemberReaders.RemovedMember> allRemoved = new ArrayList<>();
		for (List<RemovedMemberReaders.RemovedMember> l : removedByClass.values()) allRemoved.addAll(l);

		// 先按"未扫描"铺好结果。扫描失败时也必须带着 removedMethods 标志返回：方法删除没有存活实例
		// 判据可以退，调用方若把"没扫成"读成"没读者"，删方法就会被静默放行。
		Map<String, RemovedMemberScan> out = new LinkedHashMap<>();
		for (Map.Entry<String, List<RemovedMemberReaders.RemovedMember>> e : removedByClass.entrySet()) {
			List<RemovedMemberReaders.RemovedMember> list = e.getValue();
			out.put(e.getKey(), RemovedMemberScan.unscanned(
				list.stream().anyMatch(m -> !m.isField()),
				list.stream().anyMatch(m -> m.isField() && m.isStatic()),
				list.stream().filter(RemovedMemberReaders.RemovedMember::hasConstantValue)
				    .map(RemovedMemberReaders.RemovedMember::name).toList()));
		}

		RemovedMemberReaders.Result result;
		try {
			result = RemovedMemberReaders.find(RemovedMemberReaders.universe(pre),
			                                   RemovedMemberReaders.universe(post), post, allRemoved);
		} catch (Throwable t) {
			warn("[LAYOUT-READERS] reader scan failed (" + t + "); falling back to the liveness check.");
			return out;
		}
		if (!result.inconclusive().isEmpty()) {
			warn("[LAYOUT-READERS] reader scan is inconclusive for " + result.inconclusive().size()
			     + " reference(s), e.g. " + result.inconclusive().get(0)
			     + "; falling back to the liveness check.");
			return out;
		}

		Map<String, List<String>> readersByClass = new LinkedHashMap<>();
		Map<String, List<String>> shiftedByClass = new LinkedHashMap<>();
		for (String className : removedByClass.keySet()) {
			readersByClass.put(className, new ArrayList<>());
			shiftedByClass.put(className, new ArrayList<>());
		}
		collectFindings(result.hits(), readersByClass);
		collectFindings(result.abstractMissing(), readersByClass);
		collectFindings(result.incompatibleAccess(), readersByClass);
		// 合法回退只记录、不阻断：删掉子类 Override 方法 / 遮蔽字段后，读者解析到父类实现或
		// 同静态性的父类字段。旧字节码本来就能正确解析（全量重编也一样），运行期零异常。
		collectFindings(result.dispatchShifted(), shiftedByClass);
		collectFindings(result.retargeted(), shiftedByClass);

		for (Map.Entry<String, RemovedMemberScan> e : out.entrySet()) {
			String className = e.getKey();
			RemovedMemberScan before = e.getValue();
			e.setValue(new RemovedMemberScan(true, readersByClass.get(className),
			                                 shiftedByClass.get(className), before.removedMethods(),
			                                 before.removedStaticFields(), before.constantFields()));
		}

		long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		info("[LAYOUT-READERS] scanned " + post.size() + " class(es) for removed members of "
		     + removedByClass.size() + " class(es) in " + ms + "ms; dangling=" + result.hits().size()
		     + ", abstract-gaps=" + result.abstractMissing().size()
		     + ", dispatch-shifts=" + result.dispatchShifted().size());
		return out;
	}

	/** 把命中的 reader 归到"被删成员所属的类"上。 */
	private static void collectFindings(List<RemovedMemberReaders.Hit> hits,
	                                    Map<String, List<String>> byClass) {
		for (RemovedMemberReaders.Hit hit : hits) {
			List<String> readers = byClass.get(dotted(hit.member().owner()));
			if (readers == null) continue;
			String reader = dotted(hit.reader());
			if (!readers.contains(reader)) readers.add(reader);
		}
	}

	/**
	 * 旧版里有、新版里没有的成员（实例字段 + 方法）。
	 *
	 * <p>合成标记字段不计（它们由对齐器负责）；{@code <init>} / {@code <clinit>} 不计——构造器不会被
	 * "删除后仍能工作"，删掉它们类本身就不可用了，那属于另一类问题。{@code $remold$} 前缀是热更管线
	 * 自己注入的，不算业务变更。</p>
	 *
	 * <p>方法与字段的差别在于<b>失败形态</b>：字段悬空抛 {@code NoSuchFieldError}；方法可能悬空
	 * （{@code NoSuchMethodError}），也可能被祖先实现静默接住（分派转移），或让具体类失去接口方法
	 * 的唯一实现（{@code AbstractMethodError}）。三者都由 {@link RemovedMemberReaders} 分类。</p>
	 */
	private static List<RemovedMemberReaders.RemovedMember> removedMembers(byte[] oldBytes,
	                                                                      byte[] newBytes) {
		ClassNode oldClass = new ClassNode();
		ClassNode newClass = new ClassNode();
		new ClassReader(oldBytes).accept(oldClass, ClassReader.SKIP_CODE);
		new ClassReader(newBytes).accept(newClass, ClassReader.SKIP_CODE);

		Set<String> fieldSurvivors = new HashSet<>();
		for (FieldNode f : newClass.fields) fieldSurvivors.add(f.name + ":" + f.desc);

		List<RemovedMemberReaders.RemovedMember> out = new ArrayList<>();
		for (FieldNode f : oldClass.fields) {
			if ((f.access & Opcodes.ACC_SYNTHETIC) != 0 || f.name.startsWith("$remold$")) continue;
			if (fieldSurvivors.contains(f.name + ":" + f.desc)) continue;
			out.add(new RemovedMemberReaders.RemovedMember(oldClass.name, f.name, f.desc, true,
				(f.access & Opcodes.ACC_PRIVATE) != 0,
				(f.access & Opcodes.ACC_STATIC) != 0,
				// ASM 把 ConstantValue 属性解析成 FieldNode.value。非 null 就是编译期常量，
				// javac 会把它内联进读者的 ldc/bipush——读者常量池里没有 Fieldref，扫描无从下手。
				f.value != null));
		}

		Set<String> methodSurvivors = new HashSet<>();
		for (MethodNode m : newClass.methods) methodSurvivors.add(m.name + ":" + m.desc);
		for (MethodNode m : oldClass.methods) {
			if ("<init>".equals(m.name) || "<clinit>".equals(m.name)) continue;
			if (m.name.startsWith("$remold$")) continue;
			if (methodSurvivors.contains(m.name + ":" + m.desc)) continue;
			out.add(new RemovedMemberReaders.RemovedMember(oldClass.name, m.name, m.desc, false,
			                                               (m.access & Opcodes.ACC_PRIVATE) != 0,
			                                               (m.access & Opcodes.ACC_STATIC) != 0));
		}
		return out;
	}

	/**
	 * 由本批类的文件路径反推工程构建输出根目录（{@code .../build/classes/java/main} 之类）。
	 * 做法是把类名里的包段数从路径末尾去掉。
	 */
	private static Set<Path> projectClassRoots(Map<String, Path> classToPath) {
		Set<Path> roots = new LinkedHashSet<>();
		if (classToPath == null) return roots;
		for (Map.Entry<String, Path> e : classToPath.entrySet()) {
			Path p = e.getValue();
			if (p == null) continue;
			int depth = e.getKey().split("\\.").length;
			for (int i = 0; i < depth && p != null; i++) p = p.getParent();
			if (p != null) roots.add(p.toAbsolutePath().normalize());
		}
		return roots;
	}

	/** 该已加载类是否属于工程自身的构建输出（按 code source 位置判断，不触发 IO）。 */
	private static boolean isProjectClass(Class<?> c, Set<Path> roots) {
		if (roots.isEmpty()) return false;
		ClassLoader loader = c.getClassLoader();
		if (loader == null || loader == ClassLoader.getPlatformClassLoader()) return false;
		String name = c.getName();
		for (String prefix : NON_PROJECT_PREFIXES) {
			if (name.startsWith(prefix)) return false;
		}
		try {
			java.security.CodeSource cs = c.getProtectionDomain() == null
			 ? null : c.getProtectionDomain().getCodeSource();
			if (cs == null || cs.getLocation() == null) return false;
			Path location = Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize();
			for (Path root : roots) {
				if (location.startsWith(root)) return true;
			}
		} catch (Throwable ignored) {
			// 拿不到位置就按"不是工程类"处理：范围外的读者是已知边界，不能因此判成"没有读者"。
		}
		return false;
	}

	/** 把构建输出里的 class 补进读者集合（只补尚未收录的：内存字节优先）。 */
	private static void collectClassFiles(Path root, Map<String, byte[]> into) {
		if (!Files.isDirectory(root)) return;
		try (var walk = Files.walk(root)) {
			for (Path p : walk.filter(Files::isRegularFile).toList()) {
				if (into.size() > MAX_READER_SCAN_CLASSES) {
					warn("[LAYOUT-READERS] class file cap (" + MAX_READER_SCAN_CLASSES
					     + ") reached under " + root + "; reader scan may be incomplete.");
					return;
				}
				if (!p.toString().endsWith(".class")) continue;
				Path rel = root.relativize(p);
				String internal = rel.toString().replace('\\', '/');
				internal = internal.substring(0, internal.length() - ".class".length());
				if (into.containsKey(internal)) continue;
				try {
					into.put(internal, Files.readAllBytes(p));
				} catch (Throwable ignored) {
					// 单个文件读失败不影响整体；缺失的类会在解析期表现为 UNKNOWN 并被判为不完整。
				}
			}
		} catch (Throwable t) {
			warn("[LAYOUT-READERS] cannot walk " + root + ": " + t);
		}
	}

	private static String internalName(String dottedName) {
		return dottedName.replace('.', '/');
	}

	private static String dotted(String internalName) {
		return internalName.replace('/', '.');
	}

	/** 读者扫描只看工程自身的类；这些前缀是 JDK / 标准库，不可能引用工程字段。 */
	private static final String[] NON_PROJECT_PREFIXES = {
		"java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.", "kotlin."
	};

	/** 读者扫描的类数上限，防止异常路径下把整个磁盘拖进来。 */
	private static final int MAX_READER_SCAN_CLASSES = 20_000;

	private static final OffsetScanner offsetScanner = new OffsetScanner(RemoldAgent::fetchBytecodeForOffsetScanner);
	private static final Map<String, OffsetScanner.Binding> scannedOffsetBindings = new ConcurrentHashMap<>();
	private static volatile OffsetBindingIndex offsetIndex = OffsetBindingIndex.empty();

	public static OffsetBindingIndex getOffsetIndex() {
		return offsetIndex;
	}

	public static void registerOffsetBinding(OffsetScanner.Binding binding) {
		if (binding != null) {
			scannedOffsetBindings.put(binding.toString(), binding);
			offsetIndex = OffsetBindingIndex.from(scannedOffsetBindings.values());
		}
	}

	public static void clearOffsetIndex() {
		scannedOffsetBindings.clear();
		offsetIndex = OffsetBindingIndex.empty();
	}

	private static void scanLoadedClassForOffsets(byte[] classfileBuffer) {
		if (classfileBuffer == null) return;
		try {
			List<OffsetScanner.Binding> list = offsetScanner.scan(classfileBuffer);
			if (list != null && !list.isEmpty()) {
				for (OffsetScanner.Binding b : list) {
					scannedOffsetBindings.put(b.toString(), b);
				}
				offsetIndex = OffsetBindingIndex.from(scannedOffsetBindings.values());
			}
		} catch (Throwable ignored) { }
	}

	private static byte[] fetchBytecodeForOffsetScanner(String internalName) {
		String dotName = dotted(internalName);
		byte[] b = bytecodeCache.get(dotName);
		if (b != null) return b;
		Class<?> c = loadedClassesMap.get(dotName);
		if (c != null) {
			try {
				b = fetchOriginalBytecode(c);
				if (b != null) return b;
			} catch (Throwable ignored) { }
		}
		try (InputStream in = RemoldAgent.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
			if (in != null) return in.readAllBytes();
		} catch (Throwable ignored) { }
		return null;
	}

	private static SuperclassDriftGate.DriftDecision checkSuperclassDriftIfApplicable(
			String className, Class<?> targetClass, List<String> changedFields,
			byte[] newBytes, Map<String, byte[]> newBatchBytes) {
		if (targetClass == null || changedFields == null || changedFields.isEmpty() || offsetIndex == null || offsetIndex.isEmpty()) {
			return null;
		}
		boolean hasInstanceFieldChange = false;
		for (String cf : changedFields) {
			if (cf != null && (cf.startsWith("+") || cf.startsWith("-") || cf.startsWith("~"))
			    && !cf.startsWith("+ *") && !cf.startsWith("+*")
			    && !cf.startsWith("- *") && !cf.startsWith("-*")
			    && !cf.startsWith("~ *") && !cf.startsWith("~*")) {
				hasInstanceFieldChange = true;
				break;
			}
		}
		if (!hasInstanceFieldChange) return null;

		LayoutPredictor.PredictionResult prediction = LayoutPredictor.predictLayout(
			targetClass,
			newBytes,
			newBatchBytes,
			RemoldAgent::fetchBytecodeForOffsetScanner,
			base -> getLoadedDescendantInternalNames(targetClass)
		);

		int blindSpots = (offsetIndex != null ? offsetIndex.dynamicHolders().size() : 0);

		SuperclassDriftGate.DriftDecision decision = SuperclassDriftGate.checkLayoutDrift(
			internalName(className),
			prediction,
			offsetIndex,
			RemoldAgent::hasLiveInstances,
			OFFSET_STRICT,
			blindSpots
		);

		if (decision == null) {
			// 如果预测器无法 clean-load 预测（如缺少依赖），退回保守全量子孙类扫描
			return SuperclassDriftGate.checkSuperclassDrift(
				internalName(className),
				offsetIndex,
				base -> getLoadedDescendantInternalNames(targetClass),
				RemoldAgent::getDeclaredInstanceFields,
				RemoldAgent::hasLiveInstances
			);
		}

		return decision;
	}

	private static Set<String> getLoadedDescendantInternalNames(Class<?> baseClass) {
		if (baseClass == null || baseClass.isInterface()) return Collections.emptySet();
		Set<String> descendants = new HashSet<>();
		for (Class<?> c : loadedClassesMap.values()) {
			if (c != null && c != baseClass && !c.isInterface() && baseClass.isAssignableFrom(c)) {
				descendants.add(internalName(c.getName()));
			}
		}
		return descendants;
	}

	private static Collection<String> getDeclaredInstanceFields(String internalName) {
		String dotName = dotted(internalName);
		Class<?> c = loadedClassesMap.get(dotName);
		if (c != null) {
			List<String> fields = new ArrayList<>();
			for (Field f : c.getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers())) {
					fields.add(f.getName());
				}
			}
			return fields;
		}
		byte[] b = bytecodeCache.get(dotName);
		if (b != null) {
			return parseInstanceFieldsFromBytecode(b);
		}
		return Collections.emptyList();
	}

	private static Collection<String> parseInstanceFieldsFromBytecode(byte[] b) {
		try {
			ClassReader cr = new ClassReader(b);
			ClassNode cn = new ClassNode();
			cr.accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			List<String> fields = new ArrayList<>();
			for (FieldNode fn : cn.fields) {
				if ((fn.access & Opcodes.ACC_STATIC) == 0) {
					fields.add(fn.name);
				}
			}
			return fields;
		} catch (Throwable ignored) {
			return Collections.emptyList();
		}
	}

	private static void applyRedefineLayoutGate(Map<String, byte[]> newBatchBytes,
	                                            Map<String, Path> classToPath,
	                                            Map<String, AlignmentTransaction> transactions,
	                                            RoundSkips skips) {
		if (LayoutGate.MODE_OFF.equals(LAYOUT_GATE)) return;

		// 扫一遍本次批次的新字节码，及时发现本批新引入的偏移量缓存
		for (byte[] newBytes : newBatchBytes.values()) {
			if (newBytes != null) scanLoadedClassForOffsets(newBytes);
		}

		// 本轮有"删实例字段"时才做读者扫描（内部先 diff 判断，没有删除就直接返回空表，零成本）。
		Map<String, RemovedMemberScan> readersByClass = scanRemovedMembers(newBatchBytes, classToPath);

		Set<String> rejectedHosts = new LinkedHashSet<>();
		for (String className : new ArrayList<>(newBatchBytes.keySet())) {
			if (isBlacklisted(className)) continue;
			if (rejectedHosts.contains(hostNameOf(className))) continue;

			byte[] newBytes = newBatchBytes.get(className);
			if (newBytes == null) continue;

			// 若该类在磁盘上的文件哈希未发生变化，说明本批未修改该类，跳过布局门检查
			long newHash = calculateHash(newBytes);
			long classNameKey = CRC64.hashString(className);
			synchronized (fileDiskHashes) {
				long oldDiskHash = fileDiskHashes.get(classNameKey);
				if (oldDiskHash != -1 && oldDiskHash != LongLongMap.NOT_FOUND && oldDiskHash == newHash) {
					continue;
				}
			}

			Class<?> targetClass = loadedClassesMap.get(className);
			if (targetClass == null) continue; // 未加载：无旧状态需要保护

			byte[] oldBytes = bytecodeCache.get(className);
			if (oldBytes == null) {
				oldBytes = fetchOriginalBytecode(targetClass);
				if (oldBytes != null) bytecodeCache.put(className, oldBytes);
			}
			if (oldBytes == null) {
				warn("[LAYOUT-SKIP] " + className + ": layout gate skipped — no baseline bytecode"
				     + " to diff. Redefining without a field-layout check.");
				continue;
			}

			List<String> changed;
			try {
				changed = ClassDiffUtil.diff(oldBytes, newBytes).changedFields;
			} catch (Throwable t) {
				warn("[LAYOUT-SKIP] " + className + ": layout gate skipped — cannot diff ("
				     + t.getClass().getSimpleName() + "). Redefining without a field-layout check.");
				continue;
			}

			// 只有布局不兼容时才去扫堆 —— 一次全堆遍历会触发 safepoint，不能每类都做。
			// hasLiveInstances 已把"取不到实例信息"保守折叠成 LIVE（见其 javadoc）。
			Liveness liveness = LayoutGate.checkChangedFields(changed).compatible()
			 ? Liveness.LIVE
			 : (hasLiveInstances(className) ? Liveness.LIVE : Liveness.NONE);

			SuperclassDriftGate.DriftDecision drift = checkSuperclassDriftIfApplicable(
				className, targetClass, changed, newBytes, newBatchBytes);

			LayoutDecision d = decideLayout(className, changed, LAYOUT_GATE, liveness,
			                                newBatchBytes.keySet(),
			                                readersByClass.getOrDefault(className, RemovedMemberScan.NOT_SCANNED),
			                                drift);

			// 显式出口：取不到基线 / 无法 diff 就不是"放行"，而是"没法判"。
			// 静默放行会重演"整轮静默失效"那类难排查的问题，所以必须说出来。
			if (d.action() == LayoutAction.SKIP) {
				warn("[LAYOUT-SKIP] " + className + ": layout gate skipped — no usable baseline"
				     + " bytecode to diff. Redefining without a field-layout check.");
				continue;
			}

			if (d.action() == LayoutAction.WAIVE_NO_READERS) {
				LAYOUT_GATE_WAIVED.incrementAndGet();
				clearRejected(className);
				RemovedMemberScan memberScan =
					readersByClass.getOrDefault(className, RemovedMemberScan.NOT_SCANNED);
				StringBuilder note = new StringBuilder();
				if (!memberScan.dispatchShifted().isEmpty()) {
					note.append(" Fallback(s): ").append(memberScan.dispatchShifted().size())
					    .append(" reference(s) now resolve to a supertype implementation or a")
					    .append(" same-kind field — that is what removing the override/shadow meant.");
				}
				if (memberScan.hasConstantFields()) {
					note.append(" Compile-time constant(s) removed: ").append(memberScan.constantFields())
					    .append(" — readers that inlined the value keep it and never touch the field;")
					    .append(" recompile them to pick up the new value.");
				}
				warn("[LAYOUT-WAIVE] " + className + ": member(s) removed ("
				     + (d.layout() == null ? "?" : d.layout().detail()) + ") and no out-of-batch reader"
				     + " still needs them; redefining. Surviving instances lose the removed slot, which"
				     + " nothing reads." + note
				     + " Scope note: only project classes were scanned — a reader inside a third-party"
				     + " jar is outside this check.");
				continue;
			}

			if (d.action() == LayoutAction.WAIVE) {
				LAYOUT_GATE_WAIVED.incrementAndGet();
				clearRejected(className);
				warn("[LAYOUT-WAIVE] " + className + ": incompatible field layout ("
				     + d.layout().detail() + ") but no live instances; redefining anyway — new"
				     + " instances go through the new constructor and initialize correctly.");
				continue;
			}

			if (d.action() == LayoutAction.REJECT) {
				LAYOUT_GATE_REJECTED.incrementAndGet();
				String why = d.reason() != null
					? d.reason()
					: "field layout incompatible (" + (d.layout() == null ? "unknown" : d.layout().detail()) + ")";
				int repeats = noteRejected(className, why);
				warn("[LAYOUT-REJECT] " + className + ": REFUSED redefine — " + why + ". Host "
				     + d.hostName() + " and its anonymous classes are"
				     + " skipped this round; existing instances keep running the OLD code, so this edit will"
				     + " NOT take effect for them. TO PROCEED: recreate/restart so the classes load fresh, or"
				     + " set -Dremold.agent.layout_gate=warn to force the redefine and accept the consequences.");
				if (repeats >= 2) {
					// 拒绝不改运行态：类仍带旧布局，而源码已经不同 → 下次 diff 出同一条变更 → 又被拒。
					// 用户很容易以为"再热一次就好了"，实际上只有重启或改读者能打破。
					warn("[LAYOUT-STUCK] " + className + ": refused " + repeats + " times for the same"
					     + " reason since its last successful redefine. The running class still holds the"
					     + " old layout while the source no longer matches it, so every later hotswap of"
					     + " this class will be refused the same way — including method-body-only edits,"
					     + " which are dropped together with the whole class. Restart (or otherwise"
					     + " recreate the class) to break the loop.");
				}
				for (String dropped : d.dropped()) dropFromBatch(newBatchBytes, classToPath, dropped);
				skips.addGroup(d.dropped(), d.hostName(),
				 skipVerdictForLayout(d, readersByClass.getOrDefault(className, RemovedMemberScan.NOT_SCANNED)));
				AlignmentTransaction tx = transactions.remove(d.hostName());
				if (tx != null) tx.rollback(false);
				rejectedHosts.add(d.hostName());
				continue;
			}

			// action == PASS：可能是"字段没变/纯新增"，也可能是 warn 模式下的强制放行。
			clearRejected(className);
			if (d.layout() != null && !d.layout().compatible()) {
				LAYOUT_GATE_WAIVED.incrementAndGet();
				warn("[LAYOUT-WARN] " + className + ": incompatible field layout ("
				     + d.layout().detail() + "); redefining anyway because remold.agent.layout_gate=warn."
				     + " Surviving instances may read 0 / misread old values until recreated.");
			} else if (d.layout() != null && !d.layout().detail().equals("compatible")) {
				log("[LAYOUT-ALLOW] " + className + ": " + d.layout().detail());
			}
		}
	}

	/** {@link #decideLayout} 的动作。 */
	public enum LayoutAction {
		/** 放行（含 warn 模式下的强制放行，与字段未变）。 */
		PASS,
		/** 布局不兼容，但无存活实例且不涉及静态字段 —— 放行（调用方应记 waived + 告警）。 */
		WAIVE,
		/**
		 * 删除字段，但<b>批次外没有读者</b> —— 放行。
		 *
		 * <p>与 {@link #WAIVE} 分开是因为理由不同：{@code WAIVE} 靠"没有存活实例"，本条靠
		 * "改动后没有代码还会解析到这个字段"。日志文案必须能区分，否则排查时会把两者混为一谈。</p>
		 */
		WAIVE_NO_READERS,
		/** 拒绝并整组移出本批。 */
		REJECT,
		/** 无法判定（取不到基线 / diff 失败）—— 放行但必须告警，不得静默。 */
		SKIP
	}

	/**
	 * 某类被删<b>实例字段</b>在<b>批次外</b>的读者判定
	 * （判据见 {@code docs/initfix/07-p05-probe-results.md} §13.6）。
	 *
	 * <p>{@code scanned == false} 表示本轮没有做这项判定（本批没有删除、或扫描前置条件不满足）。
	 * 这种情况必须退回原来的存活实例判据，<b>不能</b>当成"没有读者"。</p>
	 */
	/**
	 * 一轮"被删成员的批次外读者"扫描结果。
	 *
	 * @param scanned         本轮扫描是否完成。{@code false} 时调用方<b>必须</b>退回原判据——
	 *                        把"没扫成"读成"没读者"是这个判据最危险的失败模式
	 * @param readers         会悬空（{@code NoSuchFieldError} / {@code NoSuchMethodError}）或抛
	 *                        {@code AbstractMethodError} 的引用所在的类
	 * @param dispatchShifted 分派目标静默改变的引用所在的类（不抛错，但行为变了）
	 * @param removedMethods  本轮该类是否有<b>方法</b>被删。方法不经过 {@code LayoutGate}
	 *                        （{@code changedFields} 只描述字段），必须靠这个标志才知道要不要套方法判据
	 */
	public record RemovedMemberScan(boolean scanned, List<String> readers,
	                                List<String> dispatchShifted, boolean removedMethods,
	                                boolean removedStaticFields, List<String> constantFields) {
		public static final RemovedMemberScan NOT_SCANNED =
			new RemovedMemberScan(false, List.of(), List.of(), false, false, List.of());

		public RemovedMemberScan {
			readers = readers == null ? List.of() : List.copyOf(readers);
			dispatchShifted = dispatchShifted == null ? List.of() : List.copyOf(dispatchShifted);
			constantFields = constantFields == null ? List.of() : List.copyOf(constantFields);
		}

		/** 只关心方法与读者时的便捷构造。 */
		public RemovedMemberScan(boolean scanned, List<String> readers,
		                         List<String> dispatchShifted, boolean removedMethods) {
			this(scanned, readers, dispatchShifted, removedMethods, false, List.of());
		}

		/** 扫描没能完成时的占位：仍然保留"本轮删过方法/静态字段/常量"这些事实。 */
		public static RemovedMemberScan unscanned(boolean removedMethods, boolean removedStaticFields,
		                                          List<String> constantFields) {
			return new RemovedMemberScan(false, List.of(), List.of(), removedMethods,
			                             removedStaticFields, constantFields);
		}

		/**
		 * 是否存在<b>会导致运行期出错</b>的引用：悬空（{@code NoSuchFieldError} /
		 * {@code NoSuchMethodError}）、抽象方法缺失（{@code AbstractMethodError}）、
		 * 静态性翻转（{@code IncompatibleClassChangeError}）。
		 *
		 * <p>{@link #dispatchShifted()} 与 {@link #constantFields()} <b>不算</b>——前者是合法回退，
		 * 后者是编译期内联，都不抛异常、也不需要读者改代码。</p>
		 */
		public boolean hasFindings() {
			return !readers.isEmpty();
		}

		/** 是否有<b>编译期常量</b>被删——这类读者在常量池里没有 Fieldref，扫描看不到。 */
		public boolean hasConstantFields() {
			return !constantFields.isEmpty();
		}
	}

	/**
	 * 存活实例判定结果（三态）。{@link #UNKNOWN} 必须按"可能有实例"处理 ——
	 * 信它会误判成"无实例"而放行，那正是我们最不想要的方向。
	 */
	public enum Liveness { LIVE, NONE, UNKNOWN }

	/**
	 * 布局门判决（纯函数，无副作用）。
	 * @param action   PASS / WAIVE / REJECT / SKIP
	 * @param layout   规则结果；{@code null} 表示 OFF 或无法判定（此时看 {@code action}）
	 * @param hostName 由类名推出的宿主名（{@code Foo$1$2 -> Foo}）
	 * @param dropped  仅 REJECT 时非空：宿主 + 其下属全部匿名类（取自批次类名集）
	 * @param reason   仅 REJECT 时可能非空：说明拒绝的原因。{@code null} 表示"字段布局不兼容"
	 *                 （沿用旧日志文案）；方法删除这类不经过 {@code LayoutGate} 的情形必须自带说明，
	 *                 否则日志会把它说成"field layout incompatible"，误导排查方向
	 */
	public record LayoutDecision(LayoutAction action, LayoutGate.Result layout,
	                             String hostName, Set<String> dropped, String reason) {
		public LayoutDecision(LayoutAction action, LayoutGate.Result layout,
		                      String hostName, Set<String> dropped) {
			this(action, layout, hostName, dropped, null);
		}
	}

	/**
	 * 便捷重载：先 diff 两版字节，再交给核心纯函数。diff 失败 / 取不到字节 → SKIP。
	 */
	public static LayoutDecision decideLayout(String className, byte[] oldBytes, byte[] newBytes,
	                                          String mode, Liveness liveness, Set<String> batchNames) {
		return decideLayout(className, oldBytes, newBytes, mode, liveness, batchNames,
		                    RemovedMemberScan.NOT_SCANNED);
	}

	/** 便捷重载（带批次外读者判定）。 */
	public static LayoutDecision decideLayout(String className, byte[] oldBytes, byte[] newBytes,
	                                          String mode, Liveness liveness, Set<String> batchNames,
	                                          RemovedMemberScan removedFieldReaders) {
		return decideLayout(className, oldBytes, newBytes, mode, liveness, batchNames,
		                    removedFieldReaders, null);
	}

	/** 便捷重载（带批次外读者判定与父类字段漂移判定）。 */
	public static LayoutDecision decideLayout(String className, byte[] oldBytes, byte[] newBytes,
	                                          String mode, Liveness liveness, Set<String> batchNames,
	                                          RemovedMemberScan removedFieldReaders,
	                                          SuperclassDriftGate.DriftDecision drift) {
		if (LayoutGate.MODE_OFF.equals(mode)) {
			return new LayoutDecision(LayoutAction.PASS, null, hostNameOf(className), Set.of());
		}
		if (oldBytes == null || newBytes == null) {
			return new LayoutDecision(LayoutAction.SKIP, null, hostNameOf(className), Set.of());
		}
		List<String> changed;
		try {
			changed = ClassDiffUtil.diff(oldBytes, newBytes).changedFields;
		} catch (Throwable t) {
			return new LayoutDecision(LayoutAction.SKIP, null, hostNameOf(className), Set.of());
		}
		return decideLayout(className, changed, mode, liveness, batchNames, removedFieldReaders, drift);
	}

	/**
	 * 核心纯函数：给定已算出的 {@code changedFields}、模式、存活实例判定与批次类名集，判定动作。
	 *
	 * <p>把接线里最容易出错的部分（拒绝要带哪些类、off/skip/waive 出口）抽成可单测的纯逻辑。
	 * 调用位置（必须在 {@code applyRedefinitions} 之前）由 {@code applyRedefineLayoutGate} 的接线守卫。</p>
	 *
	 * <ul>
	 *   <li>{@code OFF} → PASS，不解析。</li>
	 *   <li>{@code changedFields == null}（无法 diff）→ SKIP（调用方负责告警）。</li>
	 *   <li>字段兼容（含纯新增）→ PASS。</li>
	 *   <li>不兼容 + {@code warn} → PASS，但 {@code layout} 非兼容（调用方强告警）。</li>
	 *   <li>不兼容 + {@code reject}（默认）+ <b>无存活实例且非静态字段</b> → WAIVE。</li>
	 *   <li>不兼容 + {@code reject} + 有/未知存活实例，或涉及静态字段 → REJECT +
	 *       {@code dropped} = 宿主 + 匿名子类。</li>
	 * </ul>
	 *
	 * <p><b>为什么有一条静态例外</b>：实例字段的旧值只在实例上，没有实例就无所谓"读到旧值/零值"；
	 * 静态字段是类级别共享的，类只要加载过就可能已被写入，故不因无实例而放行。</p>
	 */
	public static LayoutDecision decideLayout(String className, List<String> changedFields,
	                                          String mode, Liveness liveness, Set<String> batchNames) {
		return decideLayout(className, changedFields, mode, liveness, batchNames,
		                    RemovedMemberScan.NOT_SCANNED);
	}

	/**
	 * 带"批次外读者"判定的版本。
	 *
	 * <p><b>删除字段的判据不是存活实例</b>：删掉字段后，存活实例只是丢了一个没人读的槽位，
	 * 真正会出事的是<b>改动后还有代码解析得到它</b>——那会在未来任意一次执行时抛
	 * {@code NoSuchFieldError}，且抛点在热更事务之外。所以本条只看 {@code removedFieldReaders}：
	 * 读者为空则放行，有读者则拒绝并列出类名。</p>
	 *
	 * <p>其余不兼容（改类型 / 改静态性）与删除的差别在于：它们的旧值会被<b>静默</b>丢弃或错读，
	 * 读者集合证明不了安全，因此仍按存活实例判。</p>
	 *
	 * <p>{@code removedFieldReaders.scanned() == false} 时必须退回存活实例判据——
	 * 把"没扫"当成"没读者"是本条最危险的读法。</p>
	 */
	public static LayoutDecision decideLayout(String className, List<String> changedFields,
	                                          String mode, Liveness liveness, Set<String> batchNames,
	                                          RemovedMemberScan scan) {
		return decideLayout(className, changedFields, mode, liveness, batchNames, scan, null);
	}

	/**
	 * 带"批次外读者"判定与"父类实例字段漂移"判定的全功能版本。
	 */
	public static LayoutDecision decideLayout(String className, List<String> changedFields,
	                                          String mode, Liveness liveness, Set<String> batchNames,
	                                          RemovedMemberScan scan,
	                                          SuperclassDriftGate.DriftDecision drift) {
		String host = hostNameOf(className);
		if (LayoutGate.MODE_OFF.equals(mode)) {
			return new LayoutDecision(LayoutAction.PASS, null, host, Set.of());
		}
		if (changedFields == null) {
			return new LayoutDecision(LayoutAction.SKIP, null, host, Set.of());
		}
		if (scan == null) {
			scan = RemovedMemberScan.NOT_SCANNED;
		}
		LayoutGate.Result res = LayoutGate.checkChangedFields(changedFields);

		// 编译期常量（{@code ConstantValue}，JLS §13.4.9）<b>不阻断</b>：javac 把值内联进读者的
		// {@code ldc} / {@code bipush}，那些读者在运行期<b>根本不解析这个字段</b>，删掉它零异常；
		// 而未内联的读者常量池里仍有 {@code Fieldref}，会被 hits 正常抓到。
		// "内联"既不是错误，也不需要读者改代码——它本来就是 Java 常量的既有语义（改常量值同样
		// 要全量重编才生效）。调用方把 constantFields 打进日志即可，见 §16.1 的修订说明。

		// 方法删除不经过 LayoutGate（{@code changedFields} 只描述字段），必须单独判，而且要放在
		// compatible 早退<b>之前</b>——本轮完全可能只删了方法、字段一个都没动。
		//
		// 判据与字段同源（"批次外读者为空"），但失败形态不同：可能是 NoSuchMethodError、
		// AbstractMethodError，也可能是<b>静默的分派转移</b>（实测见 docs/initfix/07-p05-probe-results.md §15）。
		// 三种都拒绝——分派转移不抛异常，但行为已经变了。
		if (scan.removedMethods() && !LayoutGate.MODE_WARN.equals(mode)) {
			if (!scan.scanned()) {
				// 方法删除没有存活实例判据可以退：删除的影响与"当前有没有实例"正交。
				return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames),
					"method(s) removed but the reader scan could not complete — refusing because"
					+ " method removal has no liveness fallback");
			}
			if (scan.hasFindings()) {
				return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames),
					"method(s) removed and " + describeFindings(scan) + " still resolve to them");
			}
		}

		// 物理内存字段偏移漂移检查（S1 门控）：
		// 即使本类自身字段变动看起来是兼容新增（COMPATIBLE），但若引发了本类或子孙类实例字段漂移且存在持有者，则必须拦截
		if (drift != null && !drift.isSafe()) {
			LayoutGate.Verdict v = drift.formatReport().contains("[SUPERCLASS_FIELD_DRIFT]")
				? LayoutGate.Verdict.SUPERCLASS_FIELD_DRIFT
				: LayoutGate.Verdict.LAYOUT_DRIFT;
			LayoutGate.Result driftRes = new LayoutGate.Result(
				v,
				drift.formatReport()
			);
			if (LayoutGate.MODE_WARN.equals(mode)) {
				return new LayoutDecision(LayoutAction.PASS, driftRes, host, Set.of());
			}
			return new LayoutDecision(LayoutAction.REJECT, driftRes, host, droppedOf(host, batchNames), drift.formatReport());
		}

		if (res.compatible() || LayoutGate.MODE_WARN.equals(mode)) {
			return new LayoutDecision(LayoutAction.PASS, res, host, Set.of());
		}

		boolean staticChange = LayoutGate.hasStaticFieldChange(changedFields);

		// 删除字段（实例或静态）但读者扫描未完成 → 只能拒绝。
		//
		// 这里不能退回下面的 liveness 判据：liveness 回答的是"**现在**有没有实例会被错读"，
		// 而删除的风险是"**之后**新建的实例 / 静态访问被陈旧代码读"——两者正交。
		// 无实例时 `WAIVE` 会放行，等于把"没扫成"读成"没读者"，正是风险 12 本身。
		if (res.verdict() == LayoutGate.Verdict.REMOVED_FIELD && !scan.scanned()
		    && !LayoutGate.MODE_WARN.equals(mode)) {
			return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames),
				"field(s) removed but the reader scan could not complete — the liveness check"
				+ " answers a different question (whether an instance exists now, not whether stale"
				+ " code will read the field later)");
		}

		// 删除字段（实例或静态）：按"批次外读者"判，与存活实例无关（见方法注释）。
		// 改类型 / 改静态性不走这条：它们的旧值会被静默丢弃或错读，读者集合证明不了安全。
		if (res.verdict() == LayoutGate.Verdict.REMOVED_FIELD && scan.scanned()) {
			// 用 hasFindings() 而不是只看 readers()：字段改指向（父类/接口同名字段接住）
			// 不抛错但读到别的值，与方法的分派转移同类，同样不放行。
			if (!scan.hasFindings()) {
				return new LayoutDecision(LayoutAction.WAIVE_NO_READERS, res, host, Set.of());
			}
			return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames));
		}

		// 无存活实例且不涉及静态字段：没有状态会被错读，放行。
		// UNKNOWN 落入下面的 REJECT 分支（按"可能有实例"处理）。
		if (liveness == Liveness.NONE && !staticChange) {
			return new LayoutDecision(LayoutAction.WAIVE, res, host, Set.of());
		}
		return new LayoutDecision(LayoutAction.REJECT, res, host, droppedOf(host, batchNames));
	}

	/**
	 * 布局门拒绝的紧凑判决串（摘要行用）。
	 *
	 * <p>形如 {@code LAYOUT-REJECT: field(s) removed: COST — restore the removed field(s) or restart}：
	 * 判决码 + <b>具体是哪个字段</b> + <b>下一步怎么办</b>，一行读完不需要往上翻。</p>
	 *
	 * <p>细节取自 {@link LayoutDecision#layout()} 的 {@code detail()}（已点名字段名，去掉方括号便于阅读）。
	 * 读者侧信息只在<b>确实扫出了东西</b>时追加；扫描没做（改类型 / 无存活实例）时不能凭空补一句。</p>
	 *
	 * <p>包内可见，供 {@code HotswapSkipSummaryTest} 直接断言这一行的措辞。</p>
	 */
	public static String skipVerdictForLayout(LayoutDecision d, RemovedMemberScan scan) {
		String detail = d.layout() == null ? null : d.layout().detail();
		if (detail != null) {
			detail = detail.replace("[", "").replace("]", "").trim();
			// "field(s) removed: COST" → "field removed: COST"：只有一个字段时用单数，
			// 读起来不像模板腔（LayoutGate 那边要同时服务日志，保持中性写法，这里只做展示层归一）。
			String many = "field(s) removed: ";
			if (detail.startsWith(many) && detail.indexOf(',') < 0) {
				detail = "field removed: " + detail.substring(many.length());
			}
		}

		String note = null;
		if (d.reason() != null) {
			// 扫描没完成本身就是拒绝理由，必须说出来：否则"只是删了个字段"看起来像能放行。
			note = "(reader scan could not complete)";
		} else if (scan != null && scan.hasFindings()) {
			note = "(" + describeFindings(scan) + ")";
		}
		if (note != null) detail = detail == null ? note : detail + " " + note;

		String action = d.layout() != null && d.layout().verdict() == LayoutGate.Verdict.REMOVED_FIELD
		 ? "restore the removed field(s) or restart to continue"
		 : (d.layout() != null && (d.layout().verdict() == LayoutGate.Verdict.SUPERCLASS_FIELD_DRIFT || d.layout().verdict() == LayoutGate.Verdict.LAYOUT_DRIFT)
		    ? "avoid adding instance field(s) to superclass or restart to continue"
		    : "revert the field change or restart to continue");
		return RoundSkips.verdict("LAYOUT-REJECT", detail, action);
	}

	/** 把扫描结果说成人能读的一句话，用于拒绝日志。 */
	private static String describeFindings(RemovedMemberScan scan) {
		StringBuilder sb = new StringBuilder();
		if (!scan.readers().isEmpty()) {
			sb.append(scan.readers().size()).append(" out-of-batch reader(s)");
		}
		if (!scan.dispatchShifted().isEmpty()) {
			if (sb.length() > 0) sb.append(" and ");
			sb.append(scan.dispatchShifted().size()).append(" silent dispatch shift(s)");
		}
		return sb.length() == 0 ? "an unknown number of references" : sb.toString();
	}

	/** 拒绝时要移出本批的类：宿主 + 其下属全部匿名类。 */
	private static Set<String> droppedOf(String host, Set<String> batchNames) {
		Set<String> dropped = new LinkedHashSet<>();
		dropped.add(host);
		if (batchNames != null) {
			for (String n : batchNames) {
				if (AnonClassAligner.isAnonymousClassName(host, n)) dropped.add(n);
			}
		}
		return dropped;
	}

	/**
	 * 分块执行 Redefine，防止其中一个类出错导致所有类失败，并依据重定义成败驱动事务提交或回滚。
	 *
	 * <p>注：真正的多类原子一致性依赖 JVM 批量 {@code inst.redefineClasses(definitions)} 的原子调用；
	 * 当批量失败切换到单类模式时，属于尽力挽救兜底，客观上存在短暂的类间不一致时间窗口。</p>
	 * @return 确实重定义成功的类名集合。调用方据此决定是否落账文件指纹、以及失败类是否回队重试。
	 * <p>另有两条必须区分的语义，通过 {@link RedefineOutcome} 一并返回：</p>
	 * <ul>
	 *   <li>{@code rejectedHierarchy} —— 因 {@code hierarchyChanged} 被拒的类。
	 *       受 JVM 能力限制，重试永不会成功，<b>不得回队</b>。</li>
	 *   <li>{@code rolledBackGroups} —— 因事务组不一致而 {@code rollback} 的类。
	 *       这些类的宿主可能<b>已经在 JVM 里生效</b>，而 cache/pending 被钉回旧版本；
	 *       此时重试会拿"基线与 JVM 实际状态不一致"的数据重新对齐，<b>不得回队</b>。</li>
	 * </ul>
	 */
	private static RedefineOutcome applyRedefinitions(List<ClassDefinition> definitions,
	                                                  Collection<AlignmentTransaction> transactions,
	                                                  RoundSkips skips) {
		Set<String> successfulClasses = new HashSet<>();
		Set<String> rolledBackClasses = new HashSet<>();
		if (definitions.isEmpty()) {
			for (AlignmentTransaction tx : transactions) {
				tx.commit();
			}
			// 一条都没进 redefine 时，原来这里**完全静默**：用户只看到若干条 warn，然后等不到任何
			// "成功/失败"汇总，只能猜是不是热更没触发。有跳过项就必须点名，这是"整轮静默失效"
			// 那类最难排查的问题的正面对冲。
			if (!skips.isEmpty()) {
				warn("[HOTSWAP-SUMMARY] 0 class(es) were redefined this round — nothing reached the JVM."
				     + " The classes below are the ones that did not take effect:");
				skips.report();
			}
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
			inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));
			// 成功行本身也要带上"还有谁没进来"：用户最先看的就是这一行，只报成功个数会让
			// "我关心的类其实被跳过了"完全隐形。详细名单紧跟其后由 skips.report() 输出。
			info("HotSwap successful: " + definitions.size() + " classes redefined."
			     + (skips.isEmpty() ? "" : " " + skips.size()
			        + " class(es) were skipped this round — listed below."));
			for (ClassDefinition def : definitions) {
				bytecodeCache.put(def.getDefinitionClass().getName(), def.getDefinitionClassFile());
				successfulClasses.add(def.getDefinitionClass().getName());
			}
			for (AlignmentTransaction tx : transactions) {
				tx.commit();
			}
		} catch (Throwable t) {
			error("Bulk Redefine failed, switching to individual mode...", t);
			for (ClassDefinition def : definitions) {
				// 批量删除缓存
				InitFix.afterRedefineFailed(def.getDefinitionClass());
				try {
					inst.redefineClasses(def);
					bytecodeCache.put(def.getDefinitionClass().getName(), def.getDefinitionClassFile());
					successfulClasses.add(def.getDefinitionClass().getName());
					if (DEBUG) log("[OK] " + def.getDefinitionClass().getName());
				} catch (Throwable e) {
					error("[FAIL] " + def.getDefinitionClass().getName(), e);
					skips.add(def.getDefinitionClass().getName(), RoundSkips.verdict("REDEFINE-FAILED",
						e.getClass().getSimpleName(), "see the stack trace above; the file is requeued"));
					InitFix.afterRedefineFailed(def.getDefinitionClass());
				}
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

	/**
	 * {@link #applyRedefinitions} 的结果。
	 * @param successful 确实重定义成功的类名
	 * @param rolledBack 因事务组不一致被 rollback 的类名（宿主 + 其匿名类）——
	 *                   这些类<b>不得回队重试</b>，理由见该方法 javadoc
	 */
	private record RedefineOutcome(Set<String> successful, Set<String> rolledBack) { }
	//endregion

	//region File Processing Utilities

	private static void initializeAgentState(Class<?>[] classes) {
		info("Scanning files...");
		// 直接传入的 jar/zip 文件：立即解压扫描
		for (Path jar : activeWatchJars) {
			String stem = jar.getFileName().toString().replaceAll("[^a-zA-Z0-9_-]", "_");
			Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "remold-hotswap",
			 stem + "-" + Long.toHexString(CRC64.hashString(jar.toString())));
			List<Path> outOfSync = new ArrayList<>();
			try {
				Files.createDirectories(tmpDir);
				tmpToJar.entrySet().removeIf(e -> e.getValue().equals(jar));
				try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(jar)))) {
					ZipEntry entry;
					while ((entry = zis.getNextEntry()) != null) {
						try {
							if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
								byte[] diskBytes = zis.readAllBytes();
								String cName     = Utils.getClassNameASM(diskBytes);
								if (cName == null || isBlacklisted(cName)) continue;
								long diskHash = calculateHash(diskBytes);
								Path outPath  = tmpDir.resolve(entry.getName().replace('/', File.separatorChar));
								Files.createDirectories(outPath.getParent());
								Files.write(outPath, diskBytes);
								tmpToJar.put(outPath, jar);
								byte[] memBytes = bytecodeCache.get(cName);
								if (memBytes != null && calculateHash(memBytes) != diskHash) {
									if (DEBUG) log("[INIT-SYNC] " + cName + " (direct jar) out of sync.");
									outOfSync.add(outPath);
								}
								synchronized (fileDiskHashes) {
									fileDiskHashes.put(CRC64.hashString(cName), diskHash);
								}
							}
						} catch (Exception ignored) {
						} finally {
							zis.closeEntry();
						}
					}
				}
				if (!outOfSync.isEmpty()) pendingChanges.addAll(outOfSync);
			} catch (IOException e) {
				error("Failed to scan direct jar: " + jar, e);
			}
		}
		for (Path root : activeWatchDirs) {
			try (Stream<Path> walk = Files.walk(root)) {
				walk.filter(p -> {
					String s = p.toString();
					// jar/zip 只取 root 直属子文件（depth == 1），.class 全量递归
					if (s.endsWith(".jar") || s.endsWith(".zip")) return p.getParent().equals(root);
					return s.endsWith(".class");
				}).forEach(path -> {
					String ps = path.toString();
					if (ps.endsWith(".jar") || ps.endsWith(".zip")) {
						// 对 jar/zip：解压后做 hash 对比，out-of-sync 的类批量加入 pendingChanges
						Path   absJar = path.toAbsolutePath();
						String stem   = absJar.getFileName().toString().replaceAll("[^a-zA-Z0-9_-]", "_");
						Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "remold-hotswap",
						 stem + "-" + Long.toHexString(CRC64.hashString(absJar.toString())));
						List<Path> outOfSync = new ArrayList<>();
						try {
							Files.createDirectories(tmpDir);
							try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(absJar)))) {
								ZipEntry entry;
								while ((entry = zis.getNextEntry()) != null) {
									try {
										if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
											byte[] diskBytes = zis.readAllBytes();
											String cName     = Utils.getClassNameASM(diskBytes);
											if (cName == null || isBlacklisted(cName)) continue;
											long diskHash = calculateHash(diskBytes);

											byte[] memBytes = bytecodeCache.get(cName);
											if (memBytes != null && calculateHash(memBytes) != diskHash) {
												if (DEBUG) log("[INIT-SYNC] " + cName + " (in jar) is out of sync. Reloading...");
												Path outPath = tmpDir.resolve(entry.getName().replace('/', File.separatorChar));
												Files.createDirectories(outPath.getParent());
												Files.write(outPath, diskBytes);
												tmpToJar.put(outPath, absJar);
												outOfSync.add(outPath);
											}
											synchronized (fileDiskHashes) {
												fileDiskHashes.put(CRC64.hashString(cName), diskHash);
											}
										}
									} catch (Exception ignored) {
									} finally {
										zis.closeEntry();
									}
								}
							}
							// 批量加入，只锁一次
							if (!outOfSync.isEmpty()) pendingChanges.addAll(outOfSync);
						} catch (IOException ignored) { }
						return;
					}
					// 原始 .class 文件逻辑（保持不变）
					try {
						byte[] diskBytes = Files.readAllBytes(path);
						String cName     = Utils.getClassNameASM(diskBytes);
						if (cName == null || isBlacklisted(cName)) return;

						long diskHash = calculateHash(diskBytes);

						// 获取内存中的字节码（这是 transformLoaded 偷出来的）
						byte[] memBytes = bytecodeCache.get(cName);
						if (memBytes != null) {
							long memHash = calculateHash(memBytes);
							if (diskHash != memHash) {
								// 发现磁盘和内存不一致，手动加入待处理队列
								if (DEBUG) log("[INIT-SYNC] " + cName + " is out of sync. Reloading...");
								pendingChanges.add(path);
							}
						}
						// 只有在这里才记录磁盘哈希
						synchronized (fileDiskHashes) {
							fileDiskHashes.put(CRC64.hashString(cName), diskHash);
						}
					} catch (Exception ignored) { }
				});
			} catch (IOException ignored) { }
		}
		// 入参 classes 是调用方在 restartWatchers 之前取的快照，此刻可能已过期
		// （旧 watcher 排进 scheduler 的任务期间可能又有类被加载）。改走 triggerHotswap()，
		// 由它在锁内重新取一份新鲜快照。
		if (!pendingChanges.isEmpty() || !pendingAssetChanges.isEmpty()) triggerHotswap();
	}

	/**
	 * 判断文件是否为资源文件（贴图、字体、多语言包、配置文件、音频、着色器等）。
	 * 过滤掉隐藏文件、临时文件及字节码/压缩包。
	 */
	public static boolean isAssetFile(Path path) {
		if (path == null) return false;
		Path fileName = path.getFileName();
		if (fileName == null) return false;
		String name = fileName.toString().toLowerCase();
		if (name.startsWith(".") || name.endsWith(".tmp") || name.endsWith(".swp") || name.endsWith(".bak") || name.endsWith("~")) {
			return false;
		}
		if (name.endsWith(".class") || name.endsWith(".jar") || name.endsWith(".zip")) {
			return false;
		}
		return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
		    || name.endsWith(".properties")
		    || name.endsWith(".json") || name.endsWith(".hjson")
		    || name.endsWith(".ogg") || name.endsWith(".wav") || name.endsWith(".mp3")
		    || name.endsWith(".frag") || name.endsWith(".vert") || name.endsWith(".glsl")
		    || name.endsWith(".fnt") || name.endsWith(".ttf") || name.endsWith(".woff")
		    || name.endsWith(".atlas") || name.endsWith(".csv") || name.endsWith(".txt");
	}

	private static void handleAssetChange(Path changedAsset) {
		if (DEBUG) log("[ASSET] Queued asset change: " + changedAsset);
		synchronized (pendingChanges) {
			pendingAssetChanges.add(changedAsset);
			if (scheduledTask != null && !scheduledTask.isDone()) {
				scheduledTask.cancel(false);
			}
			scheduledTask = scheduler.schedule(RemoldAgent::triggerHotswap, FILE_SHAKE_MS, TimeUnit.MILLISECONDS);
		}
	}

	private static void handleFileChange(Path changedFile) {
		synchronized (pendingChanges) {
			pendingChanges.add(changedFile);
			if (scheduledTask != null && !scheduledTask.isDone()) {
				scheduledTask.cancel(false);
			}
			scheduledTask = scheduler.schedule(RemoldAgent::triggerHotswap, FILE_SHAKE_MS, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * 处理 jar/zip 变化：仅入队，与 .class 共用防抖，防止读到写了一半的 jar。
	 * 真正的解压在防抖结束后由 triggerHotswapWith → extractJarToTemp 完成。
	 */
	private static void handleJarChange(Path jarPath) {
		if (DEBUG) log("[JAR] Queued for extraction: " + jarPath);
		synchronized (pendingChanges) {
			pendingJars.add(jarPath.toAbsolutePath());
			if (scheduledTask != null && !scheduledTask.isDone()) {
				scheduledTask.cancel(false);
			}
			scheduledTask = scheduler.schedule(RemoldAgent::triggerHotswap, FILE_SHAKE_MS, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * 将 jar/zip 内所有 .class 解压到固定临时目录（基于 jar 路径 hash 保持稳定），
	 * 每次更新前先清空旧内容，避免 tmpdir 无限膨胀。
	 * 解压结果批量加入 pendingChanges，并维护 tmpToJar 映射。
	 */
	private static void extractJarToTemp(Path jarPath) {
		try {
			// 固定路径：<tmpdir>/remold-hotswap/<stem>-<jarPathHash>
			String stem = jarPath.getFileName().toString().replaceAll("[^a-zA-Z0-9_-]", "_");
			Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "remold-hotswap",
			 stem + "-" + Long.toHexString(CRC64.hashString(jarPath.toString())));

			// 清空旧内容（稳定路径，不删目录本身）
			if (Files.exists(tmpDir)) {
				try (Stream<Path> s = Files.walk(tmpDir)) {
					s.sorted(Comparator.reverseOrder())
					 .filter(p -> !p.equals(tmpDir))
					 .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
				}
			}
			Files.createDirectories(tmpDir);

			// 清理该 jar 上一次留下的 tmpToJar 条目，防止 map 无限增长
			tmpToJar.entrySet().removeIf(e -> e.getValue().equals(jarPath));

			List<Path> extracted = new ArrayList<>();
			List<Path> extractedAssets = new ArrayList<>();
			try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(jarPath)))) {
				ZipEntry entry;
				while ((entry = zis.getNextEntry()) != null) {
					try {
						if (!entry.isDirectory()) {
							String entryName = entry.getName();
							if (entryName.endsWith(".class")) {
								Path outPath = tmpDir.resolve(entryName.replace('/', File.separatorChar));
								Files.createDirectories(outPath.getParent());
								Files.write(outPath, zis.readAllBytes());
								tmpToJar.put(outPath, jarPath);
								extracted.add(outPath);
							} else {
								Path entryPath = Paths.get(entryName.replace('/', File.separatorChar));
								if (isAssetFile(entryPath)) {
									Path outPath = tmpDir.resolve(entryName.replace('/', File.separatorChar));
									Files.createDirectories(outPath.getParent());
									Files.write(outPath, zis.readAllBytes());
									extractedAssets.add(outPath);
								}
							}
						}
					} finally {
						zis.closeEntry();
					}
				}
			}

			if (extracted.isEmpty() && extractedAssets.isEmpty()) {
				if (DEBUG) log("[JAR] No relevant entries in: " + jarPath);
				return;
			}
			if (!extracted.isEmpty()) {
				info("[JAR] Extracted " + extracted.size() + " classes from " + jarPath.getFileName() + " → " + tmpDir);
			}
			if (!extractedAssets.isEmpty()) {
				info("[JAR] Extracted " + extractedAssets.size() + " assets from " + jarPath.getFileName() + " → " + tmpDir);
			}
			// 批量加入，只锁一次
			synchronized (pendingChanges) {
				if (!extracted.isEmpty()) pendingChanges.addAll(extracted);
				if (!extractedAssets.isEmpty()) pendingAssetChanges.addAll(extractedAssets);
			}
		} catch (IOException e) {
			error("Failed to extract jar/zip: " + jarPath, e);
		}
	}

	/**
	 * 将临时 .class 路径映射回 watchDir 内的虚拟路径，
	 * 供 findTargetClassLoader / mountForClass 正确推断 ClassLoader。
	 */
	private static Path resolveWatchDirPath(Path jarPath, String className) {
		// 情况一：jar 在某个 watchDir 目录下（目录模式）
		for (Path watchDir : activeWatchDirs) {
			if (jarPath.startsWith(watchDir)) {
				return watchDir.resolve(className.replace('.', File.separatorChar) + ".class");
			}
		}
		// 情况二：jar 本身就是直接传入的 activeWatchJar。
		// 此时没有对应的 watchDir，不需要虚拟路径，返回 null 让调用方
		// 退化到 findTargetClassLoader(className) 纯包名推断即可。
		if (activeWatchJars.contains(jarPath)) {
			return null;
		}
		error("[WARN] resolveWatchDirPath: jar not under any watchDir or watchJar: " + jarPath);
		return null;
	}

	private static void triggerHotswapWith(Class<?>[] classes) {
		// 锁不变量：任何进入 processChanges 的路径都必须持有 HOTSWAP_LOCK。
		// 用抛异常而非 assert —— assert 默认关闭（需 -ea），而这条不变量一旦被破坏就是
		// 静默的并发数据竞争，必须无条件暴露。将来有人新增绕过锁的入口时会立刻在这里炸掉。
		if (!HOTSWAP_LOCK.isHeldByCurrentThread()) {
			throw new IllegalStateException(
			 "triggerHotswapWith called without HOTSWAP_LOCK on thread "
			 + Thread.currentThread().getName()
			 + " — all hot-swap entry points must acquire HOTSWAP_LOCK first");
		}

		// 并发探针：测试用，记录同时处于热更流程内的线程数及其历史峰值。
		// 不加锁时应观察到峰值 2（scheduler 一轮 + 按钮线程一轮），加锁后恒为 1。
		// getAndAccumulate 本身是原子的，无需再补一次 set。
		int concurrent = CONCURRENT_HOTSWAP.incrementAndGet();
		CONCURRENT_HOTSWAP_PEAK.getAndAccumulate(concurrent, Math::max);
		try {
			triggerHotswapWith0(classes);
		} finally {
			CONCURRENT_HOTSWAP.decrementAndGet();
		}
	}

	private static void triggerHotswapWith0(Class<?>[] classes) {
		// 实例存活状况每轮都可能变（对象被创建/回收），缓存必须按轮清空
		resetLiveInstanceCache();
		// 先把所有待处理的 jar 解压（防抖已结束，文件写入完毕）
		Set<Path> jars;
		synchronized (pendingChanges) {
			jars = new HashSet<>(pendingJars);
			pendingJars.clear();
		}
		jars.forEach(RemoldAgent::extractJarToTemp);

		Set<Path> changes;
		Set<Path> assetChanges;
		synchronized (pendingChanges) {
			if (pendingChanges.isEmpty() && pendingAssetChanges.isEmpty()) return;
			changes = new HashSet<>(pendingChanges);
			pendingChanges.clear();
			assetChanges = new HashSet<>(pendingAssetChanges);
			pendingAssetChanges.clear();
		}
		if (!assetChanges.isEmpty()) {
			notifyAssetChanges(assetChanges);
		}
		if (!changes.isEmpty()) {
			processChanges(changes, classes);
		}
	}

	/** 对外api，触发热更新 */
	public static void triggerHotswap() {
		if (inst == null) return;
		HOTSWAP_LOCK.lock();
		try {
			// 快照必须在锁内取：排队等待期间 getAllLoadedClasses() 的结果会过期
			triggerHotswapWith(inst.getAllLoadedClasses());
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

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
	public static boolean redefineDynamic(Class<?> targetClass, byte[] newBytecode) {
		if (targetClass == null || newBytecode == null) return false;
		if (inst == null || !inst.isRedefineClassesSupported()) {
			error("[DYNAMIC-REDEFINE] JVM does not support class redefinition or agent not attached.");
			return false;
		}
		if (!inst.isModifiableClass(targetClass)) {
			warn("[DYNAMIC-REDEFINE] Class " + targetClass.getName() + " is not modifiable by JVM; skipped.");
			return false;
		}

		HOTSWAP_LOCK.lock();
		try {
			String className = targetClass.getName();
			byte[] sourceBytes = newBytecode;
			byte[] runtimeBytes = applyTransformerBridges(targetClass.getClassLoader(), className, targetClass, newBytecode);

			byte[] oldBytes = bytecodeCache.get(className);
			if (oldBytes != null) {
				ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(oldBytes, runtimeBytes);
				if (diff.hierarchyChanged) {
					error("REJECTED: Class hierarchy change detected for dynamic class " + className);
					return false;
				}
				LayoutGate.Result layoutRes = LayoutGate.checkChangedFields(diff.changedFields);
				if (!layoutRes.compatible() && LayoutGate.MODE_REJECT.equals(LAYOUT_GATE)) {
					if (hasLiveInstances(className) || LayoutGate.hasStaticFieldChange(diff.changedFields)) {
						warn("[LAYOUT-REJECT] Dynamic class " + className + " rejected: " + layoutRes.detail());
						return false;
					}
				}
				InitFix.transform(targetClass, runtimeBytes, diff);
			}

			enterInternalRedefine(Collections.singleton(className));
			try {
				inst.redefineClasses(new ClassDefinition(targetClass, runtimeBytes));
				bytecodeCache.put(className, runtimeBytes);
				sourceBytecodeCache.put(className, sourceBytes);
				try {
					InitFix.afterRedefine(targetClass);
				} catch (Throwable t) {
					error("Failed to run InitFix after redefine for " + className, t);
					InitFix.afterRedefineFailed(targetClass);
				}
				processAnnotations(Collections.singletonList(new ClassDefinition(targetClass, runtimeBytes)));
				notifyBatchListeners(Collections.singletonList(new ClassDefinition(targetClass, runtimeBytes)));
				info("[DYNAMIC-REDEFINE] Successfully redefined " + className);
				return true;
			} finally {
				exitInternalRedefine();
			}
		} catch (Throwable t) {
			error("[DYNAMIC-REDEFINE] Failed to redefine " + targetClass.getName(), t);
			InitFix.afterRedefineFailed(targetClass);
			return false;
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	private static long calculateHash(byte[] data) {
		return CRC64.update(data);
	}
	//endregion

	//region Logging System
	public static Logger logger = new DefaultLogger();


	public static class DefaultLogger implements Logger {
		@Override
		public void log(String msg) {
			if (DEBUG) System.out.println("[Remold] " + msg);
		}

		@Override
		public void info(String msg) {
			System.out.println("[Remold] " + msg);
		}

		@Override
		public void warn(String msg) {
			System.out.println("[Remold] [WARN] " + msg);
		}

		@Override
		public void error(String msg) {
			System.err.println("[Remold] " + msg);
		}

		@Override
		public void error(String msg, Throwable t) {
			System.err.println("[Remold] " + msg);
			t.printStackTrace(System.err);
		}
	}

	public interface Logger {
		void log(String msg);
		void info(String msg);
		void warn(String msg);
		void error(String msg);
		void error(String msg, Throwable t);
	}

	public static void log(String msg) { logger.log(msg); }
	public static void info(String msg) { logger.info(msg); }
	public static void warn(String s) { logger.warn(s); }
	public static void error(String msg) { logger.error(msg); }
	public static void error(String msg, Throwable t) { logger.error(msg, t); }
	//endregion

	//region Environment Detection
	static Class<?> cl_Element;

	private static final boolean ENHANCED_HOTSWAP;

	static {
		ENHANCED_HOTSWAP = probeEnhancedHotswap();
	}

	/**
	 * 探测当前 JVM 是否<b>真的</b>具备增强重定义能力（新增字段/方法）。
	 *
	 * <p><b>为什么不能看命令行参数</b>：{@code -XX:+AllowEnhancedClassRedefinition} 只存在于
	 * DCEVM/JBR 系构建里。标准 HotSpot 单独加它会直接启动失败（{@code Unrecognized VM option}）；
	 * 而一旦同时加了 {@code -XX:+IgnoreUnrecognizedVMOptions}，标准 HotSpot 会<b>接受并忽略</b>它，
	 * 但 {@link java.lang.management.RuntimeMXBean#getInputArguments()} 仍会原样回显这个字符串。
	 * 按参数文本判断就会把标准 JVM 误判成支持增强重定义，随后 {@code redefineClasses} 因
	 * schema 变化抛 {@code UnsupportedOperationException}，并连带同批次其它类一起失败。</p>
	 *
	 * <p><b>实际探测方式</b>：向 {@code HotSpotDiagnosticMXBean} 查询该 VM option。
	 * 标准 HotSpot 没有这个 option，会抛 {@code IllegalArgumentException}；DCEVM/JBR 系会返回
	 * {@code VMOption}，其 {@code value} 即该 flag 的实际状态。注意 JBR 的 non-DCEVM 构建
	 * 同样认识这个 flag，所以「option 存在」只说明是 JBR 系，真正的能力仍以 {@code value} 为准。</p>
	 *
	 * <p>任何异常（MXBean 不可用、非 HotSpot 运行时）一律返回 {@code false}：能力探测必须
	 * fail-closed，把「不知道」当成「没有」。</p>
	 */
	private static boolean probeEnhancedHotswap() {
		// 厂商标识是可靠证据：标准 HotSpot（Oracle / Adoptium / Zulu…）不会自称
		// JetBrains / DCEVM / Trava，而 DCEVM 系构建会。
		String runtime = System.getProperty("java.vm.vendor", "")
		 + ' ' + System.getProperty("java.vm.name", "")
		 + ' ' + System.getProperty("java.runtime.version", "");
		if (!looksLikeEnhancedRuntime(runtime)) return false;
		// 厂商对上了，再看 flag 是否真的开启。参数文本单独不可信（标准 JVM 靠
		// -XX:+IgnoreUnrecognizedVMOptions 也会回显它），但配合厂商标识就是可靠的。
		return ManagementFactory.getRuntimeMXBean().getInputArguments()
		 .contains("-XX:+AllowEnhancedClassRedefinition");
	}

	/**
	 * 运行时标识是否属于支持增强重定义的构建（DCEVM 系）。
	 *
	 * <p>抽成纯函数以便单测：{@code tests/src/EnhancedHotswapGateTest} 用真实的
	 * 厂商字符串断言标准 HotSpot 不会被判成增强运行时。</p>
	 */
	public static boolean looksLikeEnhancedRuntime(String runtimeIdentity) {
		if (runtimeIdentity == null) return false;
		String s = runtimeIdentity.toLowerCase(java.util.Locale.ROOT);
		return s.contains("jetbrains") || s.contains("dcevm") || s.contains("trava");
	}

	/**
	 * 当前 JVM 是否支持增强型热重载（增加字段/方法等）。
	 *
	 * @see #probeEnhancedHotswap()
	 */
	public static boolean isEnhancedHotswapEnabled() {
		return ENHANCED_HOTSWAP;
	}

	/**
	 * 该类的结构变化是否必须因「当前 JVM 没有增强重定义」而被摘出本批重定义。
	 *
	 * <p>抽成纯函数是为了让 {@code tests/src/EnhancedHotswapGateTest} 能脱离 Instrumentation
	 * 锁住两个方向：有结构变化且无能力时必须摘出；<b>没有</b>结构变化时绝不摘出。
	 * 后者是真实缺陷的回归断言 —— 早期实现把判断写成了 {@code !structureChanged()}，
	 * 导致真结构变化静默放行、无结构变化反而误报。</p>
	 *
	 * <p>{@code public} 仅为可测性，不是对外 API。</p>
	 */
	public static boolean mustSkipForMissingEnhancedRedefinition(
	 boolean structureChanged, boolean enhanced) {
		return structureChanged && !enhanced;
	}

	/**
	 * 无增强重定义能力时的降级：把<b>会自己制造 schema 变化</b>的开关强制关掉。
	 *
	 * <p>只降级 {@code FORCE_STATIC_LAMBDA}：它把实例 lambda 改写成「静态 + 显式 {@code this} 首参数」，
	 * 方法描述符随之改变，这本身就是 schema 变化。在标准 HotSpot 上放行它，等于每一轮都主动制造
	 * 一次 {@code UnsupportedOperationException}。</p>
	 *
	 * <p><b>为什么不连 {@code INIT_FIX} 一起关</b>：{@code INIT_FIX} 不会失败，它只是<b>没机会执行</b>——
	 * 新增字段构成的 schema 变化已在 {@link #mustSkipForMissingEnhancedRedefinition} 那一层被整类摘出。
	 * 强制关闭它换不来任何收益，却会波及所有<b>直接</b>调用 {@link remold.ref.InitFix#transform} 的路径
	 * （回归套件 {@code InitFixOracle} 就在标准 JVM 上跑 48 个场景），让它们静默空转。
	 * 降级只关「做了就会失败」的东西，不关「做了也没用」的东西。</p>
	 *
	 * <p>为什么降级而不报错：用户显式打开这个开关，说明他期望的是增强能力，而当前 JVM 给不了。
	 * 与其让每一轮热更都失败一次，不如关掉做不了的部分，让仍然合法的方法体热更继续工作。</p>
	 *
	 * <p>{@code enhanced} 抽成参数而不是内部直接调用 {@link #isEnhancedHotswapEnabled()}，
	 * 是为了让 {@code tests/src/EnhancedHotswapGateTest} 能锁住两个方向（有/无能力）。
	 * 本方法会修改 {@link #FORCE_STATIC_LAMBDA}。</p>
	 *
	 * @return 被降级关闭的开关名，顺序固定；空列表表示无需降级
	 *
	 * <p>{@code public} 仅为可测性，不是对外 API。</p>
	 */
	public static java.util.List<String> degradeForMissingEnhancedRedefinition(boolean enhanced) {
		java.util.List<String> degraded = new java.util.ArrayList<>(1);
		if (enhanced) return degraded;
		if (FORCE_STATIC_LAMBDA) {
			FORCE_STATIC_LAMBDA = false;
			degraded.add("remold.agent.force_static_lambda");
		}
		return degraded;
	}

	/**
	 * 降级原因文案，与 {@link #degradeForMissingEnhancedRedefinition(boolean)} 产出的开关名一一对应。
	 *
	 * <p>抽出来是为了让「为什么关」写在唯一一处，日志与文档不必各自维护一份说法。</p>
	 *
	 * <p>{@code public} 仅为可测性，不是对外 API。</p>
	 */
	public static String degradationReason(String switchName) {
		if ("remold.agent.force_static_lambda".equals(switchName)) {
			return "Rewriting instance lambdas into static ones changes their method descriptors,"
			 + " which is a schema change this JVM cannot apply.";
		}
		return "not supported by this JVM.";
	}

	/**
	 * 把一份「可能仍是磁盘原始形态」的字节码归一化成与送给 redefine 的形态一致。
	 *
	 * <p>背景：{@link #sourceBytecodeCache} 记录的是批量对齐产出的<b>源码级</b>字节码
	 * （实例 lambda），而 {@link #bytecodeCache} 记录的是 transform 之后 JVM 里
	 * 实际生效的形态。开启 {@code FORCE_STATIC_LAMBDA} 时，后者已被
	 * {@link AnnotationTransformer#forceStaticLambdas} 转成「静态 + this 显式首参数」。
	 * 这两者一旦混用，LambdaAligner 的两侧基线就不再是同一形态。</p>
	 *
	 * <p>后果不是「配不上对」这么温和：同一个 lambda 会因 desc 不同被判成
	 * 「删除 + 新增」，{@code resurrectOrphanedLambdas} 复活出的幽灵空壳沿用
	 * <b>未归一化</b>的签名，而 JVM 里老 {@code CallSite} 绑定的是<b>归一化后</b>
	 * 的签名。redefine 之后老 CallSite 解析不到方法，直接
	 * {@link NoSuchMethodError}（典型现象：{@code lambda$buildTitle$5(Window, ImageButton)V}
	 * 找不到）。同时原 lambda 还会被重复幽灵化出一份同名异型的空壳。</p>
	 *
	 * <p>本方法幂等：已带 {@code $remold$lambdasForced} 标记的字节码会被
	 * {@code forceStaticLambdas} 原样返回，不会二次前置 {@code this}。</p>
	 *
	 * @param bytes     待归一化的字节码，可为 {@code null}
	 * @param slashName 该类的内部名（形如 {@code com/example/Foo}）
	 * @param loader    用于计算 StackMapTable 的目标 ClassLoader，可为 {@code null}
	 * @return 归一化后的字节码；关闭开关或输入为 {@code null} 时原样返回
	 */
	private static byte[] normalizeAlignBaseline(byte[] bytes, String slashName, ClassLoader loader) {
		if (bytes == null || !FORCE_STATIC_LAMBDA) return bytes;
		return AnnotationTransformer.forceStaticLambdas(bytes, slashName, loader);
	}

	/**
	 * 给 LambdaAligner 的字节码解析器加一层「归一化 + 结果缓存」。
	 *
	 * <p>跨类查询（匿名类、宿主类）拿到的同样是源码级字节码，必须与主类一样归一化，
	 * 否则两侧基线仍会在别的类上错配。缓存避免同一个类在一次对齐中被反复
	 * {@code forceStaticLambdas}（每次都是一轮 ASM 解析 + 栈帧重算）。</p>
	 *
	 * @param raw    原始解析器，入参为内部名
	 * @param loader 目标 ClassLoader
	 * @return 归一化后的解析器；关闭开关时直接返回 {@code raw}
	 */
	private static java.util.function.Function<String, byte[]> normalizeAlignResolver(
	 java.util.function.Function<String, byte[]> raw, ClassLoader loader) {
		if (!FORCE_STATIC_LAMBDA) return raw;
		java.util.Map<String, byte[]> cache = new java.util.HashMap<>();
		return name -> {
			if (cache.containsKey(name)) return cache.get(name);
			byte[] out = normalizeAlignBaseline(raw.apply(name), name, loader);
			cache.put(name, out);
			return out;
		};
	}
	//endregion

	//region File Watcher Implementation
	private static void restartWatchers() {
		activeWatchers.forEach(Thread::interrupt);
		activeWatchers.clear();

		for (Path dir : activeWatchDirs) {
			try {
				WatcherThread watcher = new WatcherThread(dir, null);
				watcher.setDaemon(true);
				watcher.start();
				activeWatchers.add(watcher);
			} catch (IOException e) {
				error("Failed to start watcher for: " + dir, e);
			}
		}

		// 直接传入的 jar/zip：监控其父目录，但只响应该文件本身的事件
		for (Path jar : activeWatchJars) {
			Path parentDir = jar.getParent();
			if (parentDir == null) {
				error("Cannot determine parent directory for jar: " + jar);
				continue;
			}
			try {
				WatcherThread watcher = new WatcherThread(parentDir, jar);
				watcher.setDaemon(true);
				watcher.start();
				activeWatchers.add(watcher);
				info("[Watch] Watching jar: " + jar);
			} catch (IOException e) {
				error("Failed to start watcher for jar: " + jar, e);
			}
		}
	}
	/** 文件监控线程，这个类的设计本身就是可复用的 */
	private static class WatcherThread extends Thread {
		private final Path         root;
		private final Path         specificJar; // 非 null 时只响应该文件的事件（直接传入的 jar 模式）
		private final WatchService watchService;

		WatcherThread(Path root, Path specificJar) throws IOException {
			super("HotSwap-FileWatcher-" + root.getFileName()
			      + (specificJar != null ? "[" + specificJar.getFileName() + "]" : ""));
			this.root = root;
			this.specificJar = specificJar != null ? specificJar.toAbsolutePath() : null;
			this.watchService = FileSystems.getDefault().newWatchService();
		}

		@Override
		public void run() {
			if (DEBUG) log("[Watch] File watcher started for: " + root);
			try {
				registerAll(root);
				while (!Thread.currentThread().isInterrupted()) {
					WatchKey key          = watchService.take();
					Path     triggeredDir = (Path) key.watchable();

					for (WatchEvent<?> event : key.pollEvents()) {
						if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

						Path context  = (Path) event.context();
						Path fullPath = triggeredDir.resolve(context);

						// specificJar 模式：只响应目标 jar 文件本身，忽略其他一切
						if (specificJar != null) {
							if (fullPath.toAbsolutePath().equals(specificJar)) {
								handleJarChange(fullPath);
							}
							continue;
						}

						// 处理普通类与资源文件变化
						String name = fullPath.toString();
						if (name.endsWith(".class")) {
							handleFileChange(fullPath);
						} else if (isAssetFile(fullPath)) {
							handleAssetChange(fullPath);
						}

						// 处理 jar / zip 包变化（仅监控 root 一级，子目录不处理）
						if ((name.endsWith(".jar") || name.endsWith(".zip")) && triggeredDir.equals(root)) {
							handleJarChange(fullPath);
						}

						// 处理新目录创建
						if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath)) {
							if (DEBUG) log("[WATCH] New directory detected: " + fullPath);

							registerAll(fullPath);

							// 立即扫描该目录下现有的 .class 与资源文件（jar/zip 仅在 root 一级处理，新子目录不扫）
							try (Stream<Path> subFiles = Files.walk(fullPath)) {
								subFiles.filter(Files::isRegularFile).forEach(p -> {
									String pName = p.toString();
									if (pName.endsWith(".class")) {
										if (DEBUG) log("[WATCH] Found existing file in new dir: " + p);
										handleFileChange(p);
									} else if (isAssetFile(p)) {
										if (DEBUG) log("[WATCH] Found existing asset in new dir: " + p);
										handleAssetChange(p);
									}
								});
							} catch (IOException e) {
								error("Failed to scan new directory: " + fullPath, e);
							}
						}
					}
					if (!key.reset()) {
						if (DEBUG) log("WatchKey no longer valid: " + triggeredDir);
					}
				}
			} catch (IOException e) {
				error("File watcher encountered an error in " + getName(), e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (UncheckedIOException e) {
				error("File watcher encountered an unchecked IO error", e);
			} finally {
				if (DEBUG) log("[Watch] File watcher stopped for: " + root);
				try {
					watchService.close();
				} catch (IOException e) {
					error("Error closing watch service for " + root, e);
				}
			}
		}

		private void registerAll(Path startDir) throws IOException {
			if (!Files.exists(startDir)) {
				if (DEBUG) log("Skipping registration for non-existent directory: " + startDir);
				return;
			}
			try (Stream<Path> stream = Files.walk(startDir)) {
				stream.filter(Files::isDirectory).forEach(dir -> {
					try {
						dir.register(watchService,
						 StandardWatchEventKinds.ENTRY_CREATE,
						 StandardWatchEventKinds.ENTRY_MODIFY);
					} catch (IOException e) {
						if (DEBUG) log("Failed to register directory: " + dir);
					}
				});
			}
		}
	}
	//endregion

	//region Utility Methods
	public static Instrumentation getInst() {
		return inst;
	}
	public static String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	public enum RedefineMode {
		inject,
		lazy_load,
		;
		public static RedefineMode valueOfFail(String inject, RedefineMode def) {
			try {
				return RedefineMode.valueOf(inject);
			} catch (Exception e) {
				return def;
			}
		}
	}
	//endregion
}