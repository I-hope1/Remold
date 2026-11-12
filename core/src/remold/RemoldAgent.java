package remold;

import remold.annotation.*;
import remold.offset.LayoutPredictor;
import remold.offset.OffsetBindingIndex;
import remold.offset.OffsetScanner;
import remold.offset.SuperclassDriftGate;
import remold.profiler.DynamicProfilerAPI;
import remold.ref.HotswapBridge;
import remold.ref.InitFix;
import remold.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

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
import java.util.regex.*;

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
 *       通过 {@link FileWatcherService} 监控源码编译输出目录与 JAR 包，文件变动由单线程调度器
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
 *       重定义生效后，调用 {@code InitFix.applyPatch}，通过 Native JVMTI 堆遍历
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
	 * Class/jar 文件监控防抖时间窗口（毫秒）。
	 * 每来一个 .class/jar 事件都会重置；连续该窗口没有 Class 事件才处理这一批。
	 * 支持通过 System Property ({@code -Dremold.agent.file_shake_ms=...} 或 {@code -Dremold.fileShakeMs=...})、
	 * 环境变量 {@code REMOLD_AGENT_FILE_SHAKE_MS} 或 {@link #setFileShakeMs(int)} 动态配置。默认 800ms。
	 */
	public static volatile int FILE_SHAKE_MS      = intProp("remold.agent.file_shake_ms", "remold.fileShakeMs", 800);
	/**
	 * 资源/配置文件的独立静默窗口（毫秒），默认 250ms。
	 * 只在资源流上重置；资源到期只 drain 资源队列，不会提前带出 Class/jar 批次。
	 */
	public static volatile int ASSET_SHAKE_MS     = intPropAsset("remold.agent.asset_shake_ms", "remold.assetShakeMs", 250);
	public static RedefineMode REDEFINE_MODE;
	public static String[]     HOTSWAP_BLACKLIST  = new String[0];
	public static boolean      RETRANSFORM_LOADED = Boolean.parseBoolean(System.getProperty("remold.agent.retransform_loaded", "false"));
	public static boolean      ENABLE_HOTSWAP_EVENT;
	public static boolean      FORCE_REINIT;
	public static boolean      LAMBDA_ALIGN       = boolProp("remold.agent.lambda_align", "remold.lambdaAlign.enabled", true);
	public static boolean      LAMBDA_STRICT      = boolProp("remold.agent.lambda_strict", "remold.lambdaAlign.strict", false);
	public static boolean      INIT_FIX           = boolProp("remold.agent.init_fix", "remold.initFix", true);
	/**
	 * 两阶段 Schema-First 重定义总开关（{@code docs/initfix/04-target-design.md} §1）。默认开启。
	 *
	 * <p>只在"本批确实要补新增/类型变更字段"时启用；纯方法体热更仍走单次重定义的快路径。
	 * 关闭后退化为旧行为：新方法体在补丁完成前即上线，存在读穿窗口（{@code docs/status.md} 风险 9）。</p>
	 */
	public static boolean      TWO_PHASE          = boolProp("remold.agent.two_phase", "remold.twoPhase", true);
	/**
	 * Mixin 生成成员名/顺序基线对齐开关（默认开启）。关闭时重织入产物保持 Mixin 新一轮 unique 名，
	 * 仅用于复现 JBR 内部的 {@code InternalError} 真相；正常运行必须开启。
	 */
	public static boolean      MIXIN_MEMBER_ALIGN = boolProp("remold.agent.mixin_member_align", "remold.mixinMemberAlign", true);
	/**
	 * JBR 增强重定义期间，对"受影响的子类"（非本次目标）的 ClassFileLoadHook 是否原样放行。
	 *
	 * <p>JBR 会把子类一并拉进重定义事务，并为每个子类回调 ClassFileLoadHook 取当前字节。这些
	 * 字节已经是运行期状态；Remold 若在此再走一次 Mixin 桥/布局门，JBR 拿到的就是二次织入的
	 * 新版本，link/verify 失败 → 裸 {@code InternalError}。关闭该开关仅用于复现/取证。</p>
	 */
	public static boolean      INTERNAL_AFFECTED_PASSTHROUGH = boolProp("remold.agent.internal_passthrough", "remold.internalPassthrough", true);
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

	/**
	 * 聚合后的监控目录集合：所有来源（{@code cli} / {@code runtime} / 插件）去重后的并集，
	 * 由 {@link WatchPathRegistry} 在来源变化时重建。监听器与 ClassLoader 推断都只读它。
	 */
	static final         Set<Path>           activeWatchDirs = new CopyOnWriteArraySet<>();
	/** 直接作为 watch 路径传入的 jar/zip 文件（区别于目录内扫描到的） */
	static final         Set<Path>           activeWatchJars = new CopyOnWriteArraySet<>();

	/** 构建模式：gradle（默认仅监控 build/classes）、idea（仅监控 out/production）、auto（两者均监控并时间戳仲裁） */
	public static volatile String BUILD_MODE = System.getProperty("remold.build_mode", "auto");

	public static void setBuildMode(String mode) {
		BUILD_MODE = mode != null ? mode.toLowerCase() : "auto";
	}

	public static String getBuildMode() {
		return BUILD_MODE;
	}

	/**
	 * 在不使用retransform的情况下，确保旧bytecode正确的唯一方法
	 * dotClassName -> bytecode（JVM 实际生效/织入后基线，供 LayoutGate、InitFix、redefineClasses 使用）
	 */
	public static final Map<String, byte[]> bytecodeCache = new ConcurrentHashMap<>();

	/**
	 * 每个类当前"由对齐器自造的 lambda 槽位名"（dotClassName -> names）。
	 *
	 * <p>自造名是上一轮给"旧类里找不到对应物"的新 lambda 分配的避障名，不是 javac 会产出的编号。
	 * 必须回传给 {@link LambdaAligner#align}，否则下一轮 javac 从头编号时，某个真正的新 lambda
	 * 可能正好落在同一个号上，"同名优先"会把老 CallSite 绑到别的 lambda 体上（静默跑错）。</p>
	 *
	 * <p>生命周期与 {@link #bytecodeCache} 严格一致：只在 redefine 成功后落账，
	 * 失败/回滚不动，避免"用一份从未加载过的版本"当身份依据。</p>
	 */
	public static final Map<String, java.util.Set<String>> inventedSlots = new ConcurrentHashMap<>();

	/** 本轮算出的自造名，redefine 成功后才并入 {@link #inventedSlots}。 */
	private static final Map<String, java.util.Set<String>> pendingInvented = new ConcurrentHashMap<>();

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

	/**
	 * 不可加载的模板类（Mixin 等）。
	 *
	 * <p>Mixin 类只是字节码模板，不是 JVM 里的普通类。Fabric 的 Knot 明确禁止直接加载它；
	 * 若 Remold 的「新类注入」把它 {@code defineClass} 进宿主 ClassLoader，会让它出现在
	 * {@code loadedClassesMap} / 读者扫描里，既污染布局判定，也可能在别处触发
	 * {@code NoClassDefFoundError}。</p>
	 *
	 * <p>插件在识别出 Mixin 模板后调用 {@link #markNonLoadableClass(String)}；核心据此
	 * 把它移出本批、跳过 {@code defineClass}，并让读者扫描忽略它。</p>
	 */
	private static final Set<String> NON_LOADABLE_CLASSES = ConcurrentHashMap.newKeySet();

	/** 登记一个只作为字节码模板存在的类（例如 Mixin）。 */
	public static void markNonLoadableClass(String dotClassName) {
		if (dotClassName != null && !dotClassName.isEmpty()) {
			NON_LOADABLE_CLASSES.add(dotClassName);
		}
	}

	/** 该类是否已被登记为不可加载的模板类。 */
	public static boolean isNonLoadableClass(String dotClassName) {
		return dotClassName != null && NON_LOADABLE_CLASSES.contains(dotClassName);
	}

	/**
	 * 执行所有已注册的字节码重织入桥。
	 *
	 * <p><b>{@link remold.BytecodeTransformerBridge.Abort} 必须原样向上抛</b>：它是"本轮放弃本类重定义"
	 * 的唯一信号（见该异常的类型注释）。把它和普通桥接失败一起吞掉，调用方会拿到"未织入的原始字节"
	 * 并照样送进 {@code redefineClasses}——那正是宿主被织入新调用点、配套合成类却没落地的分裂现场。
	 * 其余 {@code Throwable} 仍按原样记一条 {@code [BRIDGE-ERROR]} 并沿用上一个字节。</p>
	 */
	public static byte[] applyTransformerBridges(ClassLoader loader, String dotClassName, Class<?> targetClass, byte[] bytecode) {
		if (transformerBridges.isEmpty() || bytecode == null) return bytecode;
		byte[] current = bytecode;
		for (remold.BytecodeTransformerBridge bridge : transformerBridges) {
			try {
				byte[] transformed = bridge.transform(loader, dotClassName, targetClass, current);
				if (transformed != null && transformed.length > 0) {
					current = transformed;
				}
			} catch (remold.BytecodeTransformerBridge.Abort abort) {
				throw abort;
			} catch (Throwable t) {
				error("[BRIDGE-ERROR] Error transforming " + dotClassName + " with " + bridge.getClass().getName(), t);
			}
		}
		return current;
	}

	//region 访问权限单调对齐 / 平台预变换重放 / JVM 级失败熔断

	/**
	 * 平台 pre-Mixin 变换重放钩子：把原始字节先过一遍宿主加载器在 Mixin 之前做的变换。
	 *
	 * <p>例：Fabric dev 的 {@code PackageAccessFixer}（Yarn named 下非 private 成员全 public）、
	 * Fabric {@code AccessWidener}、Forge/NeoForge {@code AccessTransformer}。这些变换在正常加载时
	 * 由加载器完成，但 Mixin 目标重织入是从原始 jar/磁盘字节重新织的，必须在此重放，否则产物会以
	 * 更窄的访问权限送回 JVM（JBR 增强重定义会把子类一并 link/verify，跨包 protected 访问当场
	 * {@code VerifyError} -> {@code InternalError}）。</p>
	 *
	 * <p>钩子由 loader 集成插件注册；未注册或抛异常时原样返回，属可选双保险——第一层
	 * {@link #alignAccessMonotonicity} 的单调对齐不依赖它。</p>
	 */
	private static final List<java.util.function.BiFunction<String, byte[], byte[]>> SOURCE_TRANSFORMS =
		new CopyOnWriteArrayList<>();

	/** 注册一个平台 pre-Mixin 变换重放钩子（按注册顺序链式执行）。 */
	public static void registerSourceTransform(java.util.function.BiFunction<String, byte[], byte[]> transform) {
		if (transform != null && !SOURCE_TRANSFORMS.contains(transform)) {
			SOURCE_TRANSFORMS.add(transform);
		}
	}

	/** 依次执行已注册的 pre-Mixin 变换；单钩子失败只记一条 warn 并保持原字节，不影响主链路。 */
	public static byte[] applySourceTransforms(String dotClassName, byte[] sourceBytes) {
		if (sourceBytes == null || SOURCE_TRANSFORMS.isEmpty()) return sourceBytes;
		byte[] current = sourceBytes;
		for (java.util.function.BiFunction<String, byte[], byte[]> transform : SOURCE_TRANSFORMS) {
			try {
				byte[] transformed = transform.apply(dotClassName, current);
				if (transformed != null && transformed.length > 0) {
					current = transformed;
				}
			} catch (Throwable t) {
				warn("[SOURCE-TRANSFORM] Error replaying loader transform for " + dotClassName
				     + " with " + transform.getClass().getName() + "; keeping the untransformed bytes: " + t);
			}
		}
		return current;
	}

	/** 本会话内已发生 JVM 级重定义失败（InternalError/VerifyError）的类：拒绝再次重织入。 */
	private static final Set<String> VM_POISONED_CLASSES = ConcurrentHashMap.newKeySet();

	/** 最近一次重定义是否以 JVM 级失败告终（插件据此把用户引向重启，而不是误报 LayoutGate）。 */
	private static volatile boolean LAST_REDEFINE_VM_LEVEL_FAILURE;

	/** 该类是否已被本会话的 JVM 级重定义失败拉黑。 */
	public static boolean isVmRedefinitionPoisoned(String dotClassName) {
		return dotClassName != null && VM_POISONED_CLASSES.contains(dotClassName);
	}

	/** 最近一次重定义是否以 InternalError/VerifyError 这类 JVM 级失败告终。 */
	public static boolean lastRedefineFailedAtVmLevel() {
		return LAST_REDEFINE_VM_LEVEL_FAILURE;
	}

	private static boolean isVmLevelRedefineFailure(Throwable t) {
		if (t == null) return false;
		if (t instanceof InternalError || t instanceof VerifyError) return true;
		String n = t.getClass().getName();
		return "java.lang.InternalError".equals(n) || "java.lang.VerifyError".equals(n);
	}

	/**
	 * JVM 级重定义失败熔断：把目标类拉入本会话黑名单并醒目告警。
	 *
	 * <p>这类失败意味着 JVM 元数据/编译队列可能已不一致（实测可在数秒后引发 C1 崩溃），
	 * 继续对同一类重织入只会扩大污染面，必须要求重启。</p>
	 */
	private static void markVmLevelFailure(String className, Throwable t) {
		VM_POISONED_CLASSES.add(className);
		LAST_REDEFINE_VM_LEVEL_FAILURE = true;
		dumpVmInternalExceptions(className + " (" + t.getClass().getSimpleName() + ")");
		error("[VM-REDEFINE-POISON] JVM-level redefine failure for " + className
		      + " (" + t.getClass().getSimpleName() + "). The JVM metadata may already be inconsistent;"
		      + " this class is blacklisted for the rest of this session and further hot-swaps of it will be refused."
		      + " RESTART the client now — do not continue hot-reloading.", t);
	}

	/**
	 * 用 JVM 已加载的运行时字节码作基线，对候选重织入字节做<b>访问权限单调对齐</b>。
	 *
	 * <p>返回 {@code null} 表示自检门拒绝：要么对齐后仍有收窄（fail-closed），要么基线/候选
	 * 不可解析。调用方不得把 {@code null} 送入 {@code redefineClasses}。</p>
	 */
	static byte[] alignAccessMonotonicity(String className, Class<?> targetClass, byte[] candidate) {
		if (candidate == null) return null;
		byte[] baseline = bytecodeCache.get(className);
		if (baseline == null) baseline = sourceBytecodeCache.get(className);
		if (baseline == null) return candidate;   // 首次加载的新类：没有可对齐的基线
		try {
			remold.ref.AccessAlign.Result result = remold.ref.AccessAlign.align(candidate, baseline);
			if (!result.narrowed.isEmpty()) {
				error("[ACCESS-GATE] Refused to redefine " + className
				      + ": member access narrowed after re-weave (" + result.narrowed + ")."
				      + " The re-woven bytes lost a loader-time access transform"
				      + " (Fabric PackageAccessFixer / AccessWidener / Forge AccessTransformer)."
				      + " Refusing to send downgraded access flags to the JVM.");
				return null;
			}
			if (result.changed()) {
				info("[ACCESS-ALIGN] " + className + ": restored " + result.adjusted
				     + " class/member access flag(s) from the loaded baseline before redefine.");
			}
			return result.bytes;
		} catch (Throwable t) {
			error("[ACCESS-GATE] Refused to redefine " + className + ": access alignment failed ("
			      + t + "). Refusing to send unverified access flags to the JVM.", t);
			return null;
		}
	}

	/**
	 * 把重织入产物的 Mixin 生成成员名与成员顺序对齐回已加载基线（第二缺陷）。
	 *
	 * <p>只改 Mixin 方法体时，Mixin 会重新生成全部 {@code handler$z*}/{@code redirect$z*}/
	 * {@code wrapOperation$z*}/{@code localvar$z*} 名字，unique 前缀整体漂移；JBR 眼里就是
	 * "删掉一批方法 + 新增一批方法"。对齐后纯方法体热更退化为 0 新增/0 删除。</p>
	 *
	 * <p>失败时宽松降级（返回候选原样）——随后 {@link #alignAccessMonotonicity} 的自检门仍会
	 * 决定是否允许进入 JVM。</p>
	 */
	static byte[] alignMixinGeneratedMembers(String className, Class<?> targetClass, byte[] candidate) {
		if (candidate == null || !MIXIN_MEMBER_ALIGN) return candidate;
		byte[] baseline = bytecodeCache.get(className);
		if (baseline == null) baseline = sourceBytecodeCache.get(className);
		if (baseline == null) return candidate;
		if (!remold.ref.MixinMemberAlign.hasMixinGeneratedMembers(baseline)
		    && !remold.ref.MixinMemberAlign.hasMixinGeneratedMembers(candidate)) {
			return candidate;
		}
		try {
			remold.ref.MixinMemberAlign.Result result =
				remold.ref.MixinMemberAlign.align(candidate, baseline);
			if (result.changed()) {
				info("[MIXIN-ALIGN] " + className + ": mapped " + result.renamed
				     + " Mixin-generated member name(s) back to the loaded baseline"
				     + (result.reordered ? " and restored the baseline member order" : "") + ".");
			}
			if (!result.tombstonedFields.isEmpty()) {
				info("[MIXIN-ALIGN] " + className + ": retained " + result.tombstonedFields.size()
				     + " tombstone field(s) for baseline field(s) removed by the re-weave ("
				     + result.tombstonedFields + "); the instance layout stays constant so JBR/DCEVM"
				     + " does not shrink/expand the heap for this class.");
			}
			if (!result.tombstoned.isEmpty()) {
				info("[MIXIN-ALIGN] " + className + ": retained " + result.tombstoned.size()
				     + " tombstone stub(s) for baseline Mixin-generated member(s) removed by the re-weave ("
				     + result.tombstoned + "); in-flight stack frames keep linking instead of"
				     + " NoSuchMethodError.");
			}
			if (!result.missingFromCandidate.isEmpty()) {
				warn("[MIXIN-ALIGN] " + className + ": " + result.missingFromCandidate.size()
				     + " baseline Mixin-generated member(s) are still absent after re-weave ("
				     + result.missingFromCandidate + "); JBR may still treat this as a structural change.");
			}
			return result.bytes;
		} catch (Throwable t) {
			warn("[MIXIN-ALIGN] " + className + ": member alignment skipped (" + t + ").");
			return candidate;
		}
	}

	/**
	 * 透视 JBR 裸 {@code InternalError} 的真因：HotSpot 在 {@code CLEAR_PENDING_EXCEPTION} 前
	 * 已把内部异常写入 {@code Internal exceptions (N events)} 环形缓冲区，直接经 DiagnosticCommand
	 * {@code vmInfo} 取回最后若干行打进日志。
	 */
	private static void dumpVmInternalExceptions(String context) {
		try {
			javax.management.ObjectName name =
				new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand");
			Object out = java.lang.management.ManagementFactory.getPlatformMBeanServer().invoke(
				name, "vmInfo", new Object[]{null}, new String[]{String[].class.getName()});
			String text = String.valueOf(out);
			int idx = text.lastIndexOf("Internal exceptions");
			if (idx < 0) idx = text.lastIndexOf("Events (");
			if (idx < 0) {
				warn("[VM-INFO] " + context + ": no internal-exception section found in vmInfo output.");
				return;
			}
			String[] lines = text.substring(idx).split("\\R");
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < lines.length; i++) {
				String line = lines[i];
				if (i > 0 && (line.startsWith("Events (") || line.startsWith("VM Arguments:")
				    || line.startsWith("Dynamic libraries:") || line.startsWith("OS:")
				    || line.startsWith("Java Threads:"))) {
					break;   // 输出完整的 Internal exceptions 区块，到下一个区块头为止
				}
				sb.append(line).append('\n');
				if (i > 400) break;
			}
			error("[VM-INFO] " + context + " — JVM internal exception buffer:\n" + sb);
		} catch (Throwable t) {
			warn("[VM-INFO] DiagnosticCommand vmInfo unavailable (" + t + "); "
			     + "use -Xlog:exceptions=info as a fallback.");
		}
	}

	//endregion

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

	/**
	 * 发布批次结果。
	 *
	 * <p>旧回调 {@link HotswapBatchListener#afterInternalBatch} 的触发条件保持原样
	 * （仅当本批有成功重定义的类）；新回调 {@link HotswapBatchListener#afterInternalBatchReport}
	 * 额外覆盖“只改了 Mixin 模板 / 只做了 InitFix 修补”的批次。两个回调各自独立捕获异常，
	 * 新回调的实现出错不会影响旧监听器。</p>
	 */
	private static void notifyBatchListeners(List<ClassDefinition> definitions, HotswapBatchReport report) {
		boolean hasDefinitions = definitions != null && !definitions.isEmpty();
		boolean hasReport = report != null && report.hasObservableActivity();
		if (!hasDefinitions && !hasReport) return;
		for (HotswapBatchListener listener : batchListeners) {
			if (hasDefinitions) {
				try {
					listener.afterInternalBatch(definitions);
				} catch (Throwable t) {
					error("[HotswapBatchListener] Error notifying batch listener: " + listener.getClass().getName(), t);
				}
			}
			if (hasReport) {
				try {
					listener.afterInternalBatchReport(report);
				} catch (Throwable t) {
					error("[HotswapBatchListener] Error notifying batch report listener: " + listener.getClass().getName(), t);
				}
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
		remold.evict.ReflectionCacheManager.evictAll(Collections.singleton(clazz));
	}

	public static void registerCacheEvictor(remold.evict.ReflectionCacheEvictor evictor) {
		remold.evict.ReflectionCacheManager.registerEvictor(evictor);
	}

	public static void unregisterCacheEvictor(remold.evict.ReflectionCacheEvictor evictor) {
		remold.evict.ReflectionCacheManager.unregisterEvictor(evictor);
	}

	public static void registerObjectMapper(Object mapper) {
		remold.evict.ReflectionCacheManager.registerObjectMapper(mapper);
	}

	public static void registerGson(Object gson) {
		remold.evict.ReflectionCacheManager.registerGson(gson);
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

	/**
	 * 该路径是否被任一插件声明为"不监听"。
	 *
	 * <p>引擎自身不认识任何具体加载器的目录约定；"哪些目录在开发期不能监听"由插件投票。
	 * 调用频率高（目录遍历 + 每个监听事件），实现必须廉价。</p>
	 */
	static boolean isWatchPathIgnored(Path path) {
		if (path == null) return false;
		for (HotswapPlugin plugin : plugins) {
			try {
				if (plugin.isWatchPathIgnored(path)) return true;
			} catch (Throwable t) {
				if (DEBUG) log("[HotswapPlugin] isWatchPathIgnored failed for " + plugin.getName() + ": " + t);
			}
		}
		return false;
	}

	/** 通知所有插件：聚合后的监控路径集合已更新。 */
	static void notifyWatchPathsChanged() {
		Set<Path> dirs = Set.copyOf(activeWatchDirs);
		Set<Path> jars = Set.copyOf(activeWatchJars);
		for (HotswapPlugin plugin : plugins) {
			try {
				plugin.onWatchPathsChanged(dirs, jars);
			} catch (Throwable t) {
				error("[HotswapPlugin] Error in onWatchPathsChanged for " + plugin.getName(), t);
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

	/**
	 * 从监控目录中查找指定类的最新磁盘字节码（未经过字节码重织入的原始 .class）。
	 *
	 * @param dotClassName 目标类的点分全限定名（如 com.example.item.RemoldWandItem）
	 * @return 磁盘上的最新字节码，若未找到则返回 null
	 */
	public static byte[] findClassBytesOnDisk(String dotClassName) {
		if (dotClassName == null || activeWatchDirs.isEmpty()) return null;
		String relPath = dotClassName.replace('.', File.separatorChar) + ".class";
		Path newestPath = null;
		java.nio.file.attribute.FileTime newestTime = null;
		for (Path dir : activeWatchDirs) {
			try {
				Path p = dir.resolve(relPath);
				if (Files.isRegularFile(p)) {
					java.nio.file.attribute.FileTime time = Files.getLastModifiedTime(p);
					if (newestTime == null || time.compareTo(newestTime) > 0) {
						newestTime = time;
						newestPath = p;
					}
				}
			} catch (Throwable ignored) {
			}
		}
		if (newestPath != null) {
			try {
				return Files.readAllBytes(newestPath);
			} catch (Throwable ignored) {
			}
		}
		return null;
	}

	public static byte[] findClassBytesOnDisk(Class<?> clazz) {
		return clazz != null ? findClassBytesOnDisk(clazz.getName()) : null;
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
		/**
		 * Baseline capture (e.g. {@link #retransformLoaded}): run the external transform path to
		 * refresh bytecode caches, but treat it as "not a real redefinition" so no {@code @OnReload}
		 * is fired for classes that were merely retransformed.
		 */
		public final boolean suppressPostRedefine;
		final InternalRedefineContext previous;

		public InternalRedefineContext(Collection<String> targetDotClasses, boolean isRetransform, InternalRedefineContext previous) {
			this(targetDotClasses, isRetransform, false, previous);
		}

		InternalRedefineContext(Collection<String> targetDotClasses, boolean isRetransform, boolean suppressPostRedefine, InternalRedefineContext previous) {
			this.targetDotClasses = targetDotClasses == null ? Collections.emptySet() : new HashSet<>(targetDotClasses);
			this.timestampNanos = System.nanoTime();
			this.isRetransform = isRetransform;
			this.suppressPostRedefine = suppressPostRedefine;
			this.previous = previous;
		}

		public boolean isTarget(String dotClassName) {
			if (targetDotClasses.contains(dotClassName)) return true;
			return previous != null && previous.isTarget(dotClassName);
		}

		boolean suppressesPostRedefine() {
			return suppressPostRedefine || (previous != null && previous.suppressesPostRedefine());
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

	/**
	 * Marks a caller-driven bulk retransform (see {@link #retransformLoaded()}) as baseline capture:
	 * caches are refreshed but no post-redefine callbacks or disk-baseline records are emitted.
	 */
	static void enterBaselineCapture() {
		InternalRedefineContext prev = INTERNAL_CONTEXT.get();
		INTERNAL_CONTEXT.set(new InternalRedefineContext(Collections.emptySet(), false, true, prev));
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

	/**
	 * Records the bytes an external redefine (IDE JDWP / external agent) already applied, so the
	 * watcher's next round sees an unchanged disk hash and does not redefine / notify twice.
	 */
	static void recordExternalDiskBaseline(String dotClassName, byte[] bytes) {
		if (dotClassName == null || bytes == null) return;
		synchronized (fileDiskHashes) {
			fileDiskHashes.put(CRC64.hashString(dotClassName), calculateHash(bytes));
		}
	}

	/** Package-private probe for tests: the recorded disk hash for a class, or {@link Long#MIN_VALUE}. */
	static long recordedDiskBaseline(String dotClassName) {
		if (dotClassName == null) return Long.MIN_VALUE;
		synchronized (fileDiskHashes) {
			return fileDiskHashes.get(CRC64.hashString(dotClassName));
		}
	}

	/** 待处理的 .class 变动；包内可见以让 {@link JarExtractor} 批量入队（所有访问均在 synchronized(pendingChanges) 内） */
	static final Set<Path>                pendingChanges = Collections.synchronizedSet(new HashSet<>());
	/** 待解压的 jar/zip，防抖后统一解压；所有访问均在 synchronized(pendingChanges) 内，无需额外同步 */
	private static final Set<Path>                pendingJars    = new HashSet<>();
	/** 待通知的资源文件变动（非 .class 资源，如贴图、语言包等），防抖后统一通知；所有访问均在 synchronized(pendingChanges) 内 */
	static final Set<Path>                pendingAssetChanges = new HashSet<>();
	private static final ScheduledExecutorService scheduler      = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "HotSwap-Scheduler");
		t.setDaemon(true);
		return t;
	});
	private static       ScheduledFuture<?>       scheduledTask;
	private static final DebounceWindow           DEBOUNCE = new DebounceWindow(FILE_SHAKE_MS, ASSET_SHAKE_MS);

	/**
	 * 全局热更独占锁：保证 {@code processChanges} 全流程串行。
	 *
	 * <p><b>为什么需要它</b>：{@code scheduler} 是单线程的，因此"监听线程 → 防抖 → 热更"这条
	 * 路径本来就串行。但存在若干<b>绕过 scheduler 的入口</b>，它们直接在调用方线程上执行热更：</p>
	 * <ul>
	 *   <li>公开的 {@link #triggerHotswap()} —— 例如 {@code HotSwapDialog} 的 refresh 按钮，
	 *       它用 {@code Threads.daemon(...)} 起了<b>第二个线程源</b>，与 scheduler 上正在跑的那一轮重叠；</li>
	 *   <li>{@link #init} 的 else 分支（监控路径未变时直接同步调用）；</li>
	 *   <li>{@link #init} 整体（含 {@code initializeAgentState} / {@link FileWatcherService#restart}）—— 旧 watcher
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

	//region 启动就绪门闩与批次看门狗

	/**
	 * 启动就绪门闩（见 {@link StartupGate}）。{@code PENDING} 期间一个批次都不落：
	 * 启动期（类加载器 + Mixin 转换器高频并发加载）落入热更批次会让 JVM 挂死。
	 *
	 * <p>就绪判定来源是插件投票（{@link HotswapPlugin#isHostReady()}）：core 不出现任何
	 * 加载器/游戏专属判断。首个"全部插件就绪"的观测点把门闩翻到 READY，此后不再回退
	 * —— 启动期是一次性窗口，运行期抖动不该让热更整体停摆。</p>
	 */
	private static volatile StartupGate STARTUP_GATE =
	 new StartupGate(StartupGate.resolveTimeoutMillis(System.getProperty(StartupGate.PROP_READY_TIMEOUT_MS)));

	/**
	 * 批次卡死阈值（毫秒，{@code -Dremold.agent.batch_stall_ms}，默认 15000，下限 5000）。
	 * 由 {@link BatchWatchdog} 在批次开始时读取。
	 */
	public static volatile long BATCH_STALL_MS =
	 BatchWatchdog.resolveStallMillis(System.getProperty(BatchWatchdog.PROP_STALL_MS));

	/** 门闩未就绪时的重查间隔下限：绝不能按"防抖已到期"的 0 延迟自旋。 */
	private static final long GATE_RETRY_MIN_MS = 100L;

	/** 门闩阻断日志去重（同一批变更只打一条，避免按重查节奏刷屏）。 */
	private static volatile boolean gateBlockLogged;
	/** 就绪状态变迁日志去重（每次 PENDING → READY 只打一条）。 */
	private static volatile boolean gateReadyLogged;

	/** 当前门闩（诊断/单测用）。 */
	public static StartupGate startupGate() {
		return STARTUP_GATE;
	}

	/**
	 * 复位门闩（单测/诊断用；生产路径不应调用）。
	 *
	 * <p>复位是必要的：门闩一旦翻到 READY 就不再回退，单测需要从"未就绪"起点重新验证阻断行为。</p>
	 */
	public static void resetStartupGate() {
		STARTUP_GATE = new StartupGate(
		 StartupGate.resolveTimeoutMillis(System.getProperty(StartupGate.PROP_READY_TIMEOUT_MS)));
		gateBlockLogged = false;
		gateReadyLogged = false;
	}

	/** 当前生效的批次卡死阈值（毫秒）。 */
	public static long getBatchStallMs() {
		return BATCH_STALL_MS;
	}
	//endregion

	/** 测试用：复位并发探针。 */
	public static void resetConcurrencyProbe() {
		CONCURRENT_HOTSWAP.set(0);
		CONCURRENT_HOTSWAP_PEAK.set(0);
	}

	/** 测试用：读取并发探针峰值。 */
	public static int peakConcurrentHotswaps() {
		return CONCURRENT_HOTSWAP_PEAK.get();
	}

	/** 动态设置 Class/jar 防抖等待窗口（毫秒）。负数将被忽略。 */
	public static void setFileShakeMs(int fileShakeMs) {
		if (fileShakeMs >= 0) {
			FILE_SHAKE_MS = fileShakeMs;
			DEBOUNCE.setWindows(FILE_SHAKE_MS, ASSET_SHAKE_MS);
		}
	}

	/** 动态设置资源/配置防抖等待窗口（毫秒）。负数将被忽略。 */
	public static void setAssetShakeMs(int assetShakeMs) {
		if (assetShakeMs >= 0) {
			ASSET_SHAKE_MS = assetShakeMs;
			DEBOUNCE.setWindows(FILE_SHAKE_MS, ASSET_SHAKE_MS);
		}
	}

	/** 获取当前资源/配置防抖等待窗口（毫秒）。 */
	public static int getAssetShakeMs() {
		return ASSET_SHAKE_MS;
	}

	/** 获取当前文件防抖等待窗口（毫秒）。 */
	public static int getFileShakeMs() {
		return FILE_SHAKE_MS;
	}
	//endregion

	//region Agent Initialization
	static AnnotationTransformer transformer;

	/**
	 * 加载后观测器（只记录、不改字节的 transformer），按登记顺序。
	 *
	 * <p>见 {@link #addTrailingLoadObserver(java.lang.instrument.ClassFileTransformer)}：它们必须挂在
	 * 所有会改字节的 transformer 之后，才能看到 JVM 最终定义的那份字节。</p>
	 */
	private static final java.util.Set<java.lang.instrument.ClassFileTransformer> TRAILING_OBSERVERS =
		java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

	/** {@code initTransformer} 已经把登记在册的观测器全部挂上 JVM 之后置 true。 */
	private static volatile boolean TRAILING_OBSERVERS_ARMED;

	private static final java.util.concurrent.atomic.AtomicBoolean BOOTSTRAP_APPENDED =
		new java.util.concurrent.atomic.AtomicBoolean();

	private static void appendSelfToBootstrapSearch(Instrumentation inst) {
		if (inst == null || !BOOTSTRAP_APPENDED.compareAndSet(false, true)) return;
		try {
			java.security.CodeSource cs = RemoldAgent.class.getProtectionDomain().getCodeSource();
			if (cs != null && cs.getLocation() != null) {
				File file = new File(cs.getLocation().toURI());
				if (file.isFile() && file.getName().endsWith(".jar")) {
					inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(file));
					log("[Agent] Appended agent JAR to bootstrap classloader search: " + file.getName());
				}
			}
		} catch (Throwable t) {
			warn("[Agent] Failed to append agent JAR to bootstrap classloader search: " + t);
		}
	}
	public static void premain(String agentArgs, Instrumentation inst) {
		RemoldAgent.inst = inst;
		appendSelfToBootstrapSearch(inst);
		bootstrapPlugins(agentArgs);
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
		appendSelfToBootstrapSearch(inst);
		bootstrapPlugins(agentArgs);
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

	/**
	 * <b>仅供单测</b>：临时替换 {@link Instrumentation} 句柄，返回原句柄。
	 *
	 * <p>没有 {@code -javaagent} 的测试 JVM 里 {@code inst} 恒为 {@code null}，于是所有"到底有没有
	 * 调用 {@code redefineClasses}"的端到端断言都只能落在 {@code inst == null} 的早退分支上——那种
	 * 断言没有判定力（它证明的是"没接 agent"，不是"机制拒绝了重定义"）。用它把录制代理注进去，
	 * 断言才能落在真正的调用计数上。</p>
	 *
	 * <p>调用方<b>必须</b>在 {@code finally} 里用返回值还原：{@code inst} 非空会让其它代码以为 agent
	 * 已挂载。</p>
	 */
	public static Instrumentation swapInstrumentationForTest(Instrumentation replacement) {
		Instrumentation previous = inst;
		inst = replacement;
		return previous;
	}

	public static void init(String agentArgs, boolean reinit) {
		init(SOURCE_RUNTIME, agentArgs, reinit);
	}

	public static void init(String source, String agentArgs, boolean reinit) {
		// 整体持锁：本方法包含 retransformLoaded / initializeAgentState / FileWatcherService.restart，
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
		if (inst != null) {
			appendSelfToBootstrapSearch(inst);
		}
		remold.util.LibTool.tryInit();

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
			WatchPathRegistry.parse(agentArgs, newWatchDirs, newWatchJars);
		}

		if (newWatchDirs.isEmpty() && newWatchJars.isEmpty()) {
			if (activeWatchDirs.isEmpty() && activeWatchJars.isEmpty()) {
				error("No valid watch paths provided.");
			}
			return;
		}

		WatchPathRegistry.set(source, newWatchDirs, newWatchJars);
	}

	/**
	 * 设置指定来源的监控路径（支持分号或冒号分隔的目录或 Jar 路径）。
	 * 若 paths 为空，则移除该来源对应的监控路径。
	 *
	 * <p>锁纪律：路径变更会重建监听器并重扫已加载类，必须与热更互斥，因此统一在此持
	 * {@link #HOTSWAP_LOCK}；分槽记账与聚合见 {@link WatchPathRegistry}。</p>
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
				WatchPathRegistry.remove(source);
				return;
			}
			Set<Path> dirs = new HashSet<>();
			Set<Path> jars = new HashSet<>();
			WatchPathRegistry.parse(paths, dirs, jars);
			WatchPathRegistry.set(source, dirs, jars);
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
			WatchPathRegistry.set(source, dirs != null ? new HashSet<>(dirs) : Collections.emptySet(),
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
			WatchPathRegistry.parse(paths, dirs, jars);
			WatchPathRegistry.set(source, dirs, jars);
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
			WatchPathRegistry.remove(source);
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	/** 获取指定来源的监控目录集合（只读不可变副本） */
	public static Set<Path> getWatchDirs(String source) {
		return WatchPathRegistry.dirs(source);
	}

	/** 获取指定来源的监控 Jar 集合（只读不可变副本） */
	public static Set<Path> getWatchJars(String source) {
		return WatchPathRegistry.jars(source);
	}

	/** 获取当前所有来源的监控目录映射（只读不可变副本） */
	public static Map<String, Set<Path>> getAllWatchDirSources() {
		return WatchPathRegistry.allDirSources();
	}

	/** 获取当前所有来源的监控 Jar 映射（只读不可变副本） */
	public static Map<String, Set<Path>> getAllWatchJarSources() {
		return WatchPathRegistry.allJarSources();
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
			// 观测器最后挂（含 profiler 注入之后）：它们拿到的就是 JVM 最终定义的字节。
			synchronized (TRAILING_OBSERVERS) {
				for (java.lang.instrument.ClassFileTransformer observer : TRAILING_OBSERVERS) {
					registerTrailingObserver(observer);
				}
				TRAILING_OBSERVERS_ARMED = true;
			}
		}
	}

	/**
	 * 登记一个"加载后观测器"：只读加载期字节、不改任何字节的 transformer。
	 *
	 * <p><b>为什么需要延迟注册</b>：JVM 先整组跑 {@code canRetransform=false} 的 transformer，
	 * 再按注册顺序跑 {@code canRetransform=true} 的那组。观测器若在 {@link #initTransformer()} 之前
	 * 挂上（插件 {@code onPremain} 正是这么早的窗口），拿到的是对齐/织入<b>之前</b>的字节；
	 * 因此本方法只登记，真正的 {@code addTransformer} 落在 {@code initTransformer} 末尾。
	 * {@code initTransformer} 之后再登记则立即挂上（幂等：同一实例只挂一次）。</p>
	 *
	 * <p><b>边界</b>：观测器只覆盖 Remold 自身与 profiler 之后注册的 transformer；此后别的插件
	 * 若再挂 rewriter，它对字节的改写不在观测视野内。</p>
	 *
	 * @param observer 观测器；{@code null} 忽略
	 */
	public static void addTrailingLoadObserver(java.lang.instrument.ClassFileTransformer observer) {
		if (observer == null) {
			return;
		}
		synchronized (TRAILING_OBSERVERS) {
			if (!TRAILING_OBSERVERS.add(observer)) {
				return;
			}
			if (!TRAILING_OBSERVERS_ARMED || inst == null) {
				return;   // 精确落点：initTransformer 末尾统一挂
			}
			registerTrailingObserver(observer);
		}
	}

	private static void registerTrailingObserver(java.lang.instrument.ClassFileTransformer observer) {
		try {
			inst.addTransformer(observer, true);
		} catch (Throwable t) {
			warn("[Agent] Failed to register a trailing load observer; its view of the defined bytes is"
				+ " lost (callers must fail-closed instead of degrading silently): " + t);
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
			for (Set<Path> dirs : WatchPathRegistry.dirSources().values()) {
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
				for (Set<Path> jars : WatchPathRegistry.jarSources().values()) {
					for (Path jar : jars) {
						if (location.equals(jar.toAbsolutePath().normalize())) {
							project = true;
							break;
						}
					}
					if (project) break;
				}
			}
			if (!project && WatchPathRegistry.dirSources().isEmpty() && activeWatchJars.isEmpty()) {
				project = "file".equals(uri.getScheme()) && Files.isDirectory(location);
			}
		} catch (Throwable ignored) {
			project = false;
		}
		projectLocations.put(key, project);
		return project;
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

	private static int intPropAsset(String primary, String alias, int def) {
		String v = System.getProperty(primary);
		if (v == null && alias != null) {
			v = System.getProperty(alias);
		}
		if (v == null) {
			String envKey = primary.replace('.', '_').toUpperCase();
			v = System.getenv(envKey);
			if (v == null && alias != null) {
				v = System.getenv(alias.replace('.', '_').toUpperCase());
			}
		}
		if (v == null || v.trim().isEmpty()) {
			return def;
		}
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
		TWO_PHASE = boolProp("remold.agent.two_phase", "remold.twoPhase", true);
		MIXIN_MEMBER_ALIGN = boolProp("remold.agent.mixin_member_align", "remold.mixinMemberAlign", true);
		INTERNAL_AFFECTED_PASSTHROUGH = boolProp("remold.agent.internal_passthrough", "remold.internalPassthrough", true);
		FORCE_STATIC_LAMBDA = boolProp("remold.agent.force_static_lambda", "remold.agent.force_static_lambdas", false);
		for (String degradedSwitch : degradeForMissingEnhancedRedefinition(isEnhancedHotswapEnabled())) {
			warn("DEGRADED: " + degradedSwitch + " forced OFF. " + degradationReason(degradedSwitch));
		}
		info("InitFix: " + INIT_FIX);
		info("Two-phase redefine: " + TWO_PHASE);
		info("Force Static Lambda: " + FORCE_STATIC_LAMBDA);
		ENABLE_HOTSWAP_EVENT = Boolean.parseBoolean(System.getProperty("remold.agent.hotswap_event", "false"));
		info("HotSwap Event: " + ENABLE_HOTSWAP_EVENT);
		FORCE_REINIT = Boolean.parseBoolean(System.getProperty("remold.agent.force_reinit", "true"));
		info("Force Reinit: " + FORCE_REINIT);
		LAMBDA_ALIGN = boolProp("remold.agent.lambda_align", "remold.lambdaAlign.enabled", true);
		LAMBDA_STRICT = boolProp("remold.agent.lambda_strict", "remold.lambdaAlign.strict", false);
		info("Lambda Align: " + LAMBDA_ALIGN + " (strict=" + LAMBDA_STRICT + ")");
		if (LAMBDA_ALIGN) {
			info("Lambda Alignment ENABLED. Recompilation with '-g' (LocalVariableTable) is required for reliable lambda capture reordering detection.");
		}
		FILE_SHAKE_MS = intProp("remold.agent.file_shake_ms", "remold.fileShakeMs", FILE_SHAKE_MS);
		ASSET_SHAKE_MS = intPropAsset("remold.agent.asset_shake_ms", "remold.assetShakeMs", ASSET_SHAKE_MS);
		DEBOUNCE.setWindows(FILE_SHAKE_MS, ASSET_SHAKE_MS);
		info("File Shake MS: " + FILE_SHAKE_MS);
		info("Asset Shake MS: " + ASSET_SHAKE_MS);
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

	/**
	 * 插件只发现一次：{@link #bootstrapPlugins} 会在 premain 首段先跑，之后 {@link #initConfig} 再调
	 * 到这里时应直接返回，否则每次 {@code init()} 都会重新实例化一遍插件（注册口按类名去重，
	 * 新实例会被丢弃，白付构造开销与副作用）。
	 */
	private static final java.util.concurrent.atomic.AtomicBoolean PLUGINS_LOADED =
	 new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * 极早插件引导：完成插件发现并触发 {@link HotswapPlugin#onPremain}。
	 *
	 * <p><b>为什么必须早于 {@code init}</b>：有些运行期集成只有在宿主类加载器初始化之前设置才生效
	 * （例如按 class path group 模拟模组隔离的启动器）。这类知识属于具体加载器，因此放在插件里，
	 * 由引擎在 premain 首段统一提供窗口。</p>
	 *
	 * @param agentArgs 原始 agent 参数串，可为 {@code null}
	 */
	static void bootstrapPlugins(String agentArgs) {
		loadConfiguredPlugins();
		for (HotswapPlugin plugin : plugins) {
			try {
				plugin.onPremain(agentArgs);
			} catch (Throwable t) {
				error("[HotswapPlugin] Error in onPremain for " + plugin.getName(), t);
			}
		}
	}

	private static void loadConfiguredPlugins() {
		if (inst == null) return;
		if (!PLUGINS_LOADED.compareAndSet(false, true)) return;

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

	/**
	 * 全量重传已加载类（UI 按钮 B 与启动期基线捕获共用）。
	 *
	 * <p><b>看门狗</b>：本方法同样是一个批次（持有 {@code HOTSWAP_LOCK}、跑真实
	 * {@code retransformClasses}），但它<b>不</b>经过 {@code processChanges}，所以必须自己
	 * 登记 {@link BatchWatchdog}——否则这条路径卡死时日志里不会留下任何线程栈。
	 * 它与 {@code processChanges} 串行（都在同一把锁下），不会嵌套成同一批次。</p>
	 */
	private static void retransformLoaded(Class<?>[] classes) {
		BatchWatchdog.enter(BATCH_STALL_MS, "retransform-loaded(" + classes.length + " loaded class(es))");
		try {
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
				enterBaselineCapture();
				try {
					inst.retransformClasses(candidates.toArray(new Class[0]));
				} finally {
					exitInternalRedefine();
				}
				info("Retransform complete.");
			} catch (UnmodifiableClassException e) {
				error("Failed to retransform some classes", e);
			} catch (Throwable t) {
				error("Critical error during retransform", t);
			}
		} finally {
			BatchWatchdog.exit();
		}
	}
	//endregion

	//region HotSwap Core Logic
	public static volatile ConcurrentHashMap<String, Class<?>> loadedClassesMap = new ConcurrentHashMap<>();

	/** 每个 dot 名当前缓存字节的来源加载器 rank（0 bootstrap / 1 System-Agent / 2 宿主）与描述。 */
	private static final Map<String, Integer> CACHE_LOADER_RANK = new ConcurrentHashMap<>();
	private static final Map<String, String>  CACHE_LOADER_NAME = new ConcurrentHashMap<>();
	private static final Set<String>         DUPLICATE_LOAD_LOGGED = ConcurrentHashMap.newKeySet();

	/** 重定义成功后，把缓存字节的来源加载器也登记下来，供后续同名副本仲裁使用。 */
	public static void recordCacheLoader(String dotClassName, ClassLoader loader) {
		if (dotClassName == null) return;
		CACHE_LOADER_RANK.put(dotClassName, ClassCopySelector.loaderRank(loader));
		CACHE_LOADER_NAME.put(dotClassName, String.valueOf(loader));
	}

	/**
	 * 首次加载的基线缓存写入：按加载器权威性仲裁，<b>拒绝裸副本覆盖权威副本</b>。
	 *
	 * <p>真机事故的直接机制之一：同一个 dot 名先被宿主加载器（Knot）加载并缓存已织入字节，
	 * 之后 AppClassLoader 因某处 {@code Class.forName} 兜底又定义了一份裸副本；旧实现用
	 * {@code putIfAbsent} 虽然不会覆盖，但若裸副本先到，权威副本反而进不来。这里改为按 rank 仲裁：
	 * 只有 rank 更高（或本批对齐产物=authoritative）才允许写入；并打印一份带调用栈的
	 * {@code [DUP-LOAD]}，用于定位是谁定义了裸副本。</p>
	 *
	 * @return 本次写入是否生效
	 */
	static boolean updateInitialBaseline(String dotClassName, byte[] runtimeBytes, byte[] sourceBytes,
	                                     ClassLoader loader, boolean authoritative) {
		if (dotClassName == null || runtimeBytes == null) return false;
		if (remold.offset.LayoutPredictor.isEphemeralLoader(loader)) {
			// 抛弃式预测加载器定义的副本没有实例、不会初始化，绝不能进入基线缓存
			return false;
		}
		int newRank = ClassCopySelector.loaderRank(loader);
		Integer oldRank = CACHE_LOADER_RANK.get(dotClassName);
		String oldLoader = CACHE_LOADER_NAME.get(dotClassName);
		boolean exists = bytecodeCache.containsKey(dotClassName);
		if (exists && oldLoader != null && !oldLoader.equals(String.valueOf(loader))) {
			String key = dotClassName + "|" + loader;
			if (DUPLICATE_LOAD_LOGGED.add(key)) {
				warn("[DUP-LOAD] " + dotClassName + " was defined again by " + loader
				     + " (existing cache from " + oldLoader + ", rank " + oldRank + " -> " + newRank + ")."
				     + " A bare AppClassLoader copy has no Mixin weaving / access transforms and must never"
				     + " become the redefine baseline. Definition stack:\n" + stackTrace(40));
			}
		}
		boolean accept;
		if (!exists) {
			accept = true;
		} else if (authoritative) {
			accept = true;
		} else if (oldRank != null && newRank < oldRank) {
			accept = false;
		} else if (oldRank != null && newRank == oldRank) {
			accept = false;   // 同 rank 保持先到者，等价于旧的 putIfAbsent
		} else {
			accept = true;
		}
		if (accept) {
			bytecodeCache.put(dotClassName, runtimeBytes);
			sourceBytecodeCache.put(dotClassName, sourceBytes);
			CACHE_LOADER_RANK.put(dotClassName, Math.max(newRank, authoritative ? 2 : newRank));
			CACHE_LOADER_NAME.put(dotClassName, String.valueOf(loader));
		}
		return accept;
	}

	private static String stackTrace(int frames) {
		StackTraceElement[] trace = new Throwable().getStackTrace();
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		for (StackTraceElement e : trace) {
			// 输出完整帧（含 remold 内部帧），用于定位是谁定义了裸副本
			sb.append("    at ").append(e).append('\n');
			if (++shown >= frames) break;
		}
		return sb.toString();
	}


	/**
	 * 同名类存在多个副本时的取舍：优先"活"的那个。
	 *
	 * <p>{@link remold.offset.LayoutPredictor} 用抛弃式加载器独立加载新字节码预测物理布局，
	 * 该加载器以真实加载器为 parent 且 child-first，因此它定义的副本在类层级上"更子"。
	 * 但那份副本从不初始化、也没有任何实例：
	 * 一旦它赢下同名仲裁，后续每轮热更都会 redefine 一个死副本——日志照样打
	 * {@code HotSwap successful}，而 InitFix 因"类未初始化"跳过补丁、{@code @OnReload}
	 * 也扫不到，用户看到的就是"字段加了却不生效"。</p>
	 *
	 * <p>所以预测副本永远不参与仲裁：它既不能取代活类，也不能顶掉已选中的活类。</p>
	 */
	public static boolean isBetterLoadedClassCandidate(Class<?> candidate, Class<?> existing) {
		return ClassCopySelector.isBetter(candidate, existing);
	}

	private static void loadClassSnap(Class<?>[] classes) {
		var newMap = new ConcurrentHashMap<String, Class<?>>((int) (classes.length / 0.75f) + 1);
		for (Class<?> c : classes) {
			String   name     = c.getName();
			if (isNonLoadableClass(name)) {
				// 模板类若被历史版本误注入过，这里把它排除出已加载快照：
				// 既不重定义，也不让读者扫描把它当外部读者。
				continue;
			}
			Class<?> existing = newMap.get(name);
			if (isBetterLoadedClassCandidate(c, existing)) {
				newMap.put(name, c);
			}
		}
		loadedClassesMap = newMap;              // 原子切换，读者要么看到完整旧 map，要么完整新 map
	}

	/**
	 * 批次入口：把 {@link BatchWatchdog} 挂到<b>真正执行批次的这个方法</b>上。
	 *
	 * <p><b>为什么挂在这里而不是某个入口的包装</b>：本方法是唯一真正执行批次的路径，
	 * 防抖批次（{@code runClassBatch}）、手动 {@link #triggerHotswap()} 都经过它。
	 * 之前看门狗只包住防抖批次，手动入口卡死时日志里什么都不会留下——而"没有任何输出"
	 * 与"挂死"在真机上长得一模一样，事后无法归因。</p>
	 *
	 * <p><b>同一批次只 dump 一次</b>：{@link BatchWatchdog#enter} 在批次内可重入，
	 * 嵌套进入复用同一个看门狗，只有最外层 {@link BatchWatchdog#exit()} 才注销；
	 * 因此把看门狗从 {@code runClassBatch} 上移到这里不会造成同一批次两条
	 * {@link BatchWatchdog#PREFIX}。异常路径由 {@code finally} 保证注销。</p>
	 */
	private static void processChanges(Set<Path> changedFiles, Class<?>[] classes) {
		BatchWatchdog.enter(BATCH_STALL_MS, "class-batch(" + changedFiles.size() + " file(s))");
		try {
			processChangesBatch(changedFiles, classes);
		} finally {
			BatchWatchdog.exit();
		}
	}

	/**
	 * 处理文件变化的核心逻辑（批次主体；看门狗由 {@link #processChanges} 负责登记/注销）。
	 */
	private static void processChangesBatch(Set<Path> changedFiles, Class<?>[] classes) {
		// 批次可观测数据的起点：从读盘、对齐、织入、布局门、redefine 到 InitFix 全部计入。
		// 收集器挂在当前线程上；即使没有任何监听器，这里也只多一次小对象分配，不改变批次行为。
		BatchReportScope.begin();
		resetLiveInstanceCache();
		refreshPackageLoaders(classes);

		// 获取当前所有已加载类的快照
		loadClassSnap(classes);

		List<ClassDefinition> definitions = new ArrayList<>();
		// List<Class<?>>        unloadedClasses  = new ArrayList<>();
		int skippedCount  = 0;
		int injectedCount = 0;
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
		for (Path p : changedFiles) {
			try {
				byte[] bc = Files.readAllBytes(p);
				String cn = Utils.getClassNameASM(bc);
				if (cn != null) {
					Path existingPath = classToPath.get(cn);
					if (existingPath != null) {
						try {
							java.nio.file.attribute.FileTime curTime = Files.getLastModifiedTime(p);
							java.nio.file.attribute.FileTime oldTime = Files.getLastModifiedTime(existingPath);
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
			// 对齐重命名后，目标类名对应的磁盘原始类名可能不同；指纹必须跟随原始磁盘文件。
			for (Map.Entry<String, String> rename : res.renameMap.entrySet()) {
				String rawDot    = rename.getKey().replace('/', '.');
				String targetDot = rename.getValue().replace('/', '.');
				targetToRawName.put(targetDot, rawDot);
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
		//
		// 注意：这里写入的是**本轮新字节**，而同一方法里的 lambda 对齐（下面约 150 行后）
		// 需要的是**上一轮的源码基线**。所以先把旧值快照下来，供本轮对齐读取；
		// 否则 align(old == new) 会静默地什么都不做（实测见 docs/initfix/08-test-audit.md §10.5）。
		Map<String, byte[]> prevSourceBytes = new HashMap<>();
		Map<String, byte[]> sourceBefore = new HashMap<>();
		Map<String, byte[]> driftOldBytes = new HashMap<>();
		Set<String> loadedBefore = new HashSet<>(loadedClassesMap.keySet());
		for (String key : newBatchBytes.keySet()) {
			byte[] prev = sourceBytecodeCache.get(key);
			sourceBefore.put(key, prev);
			driftOldBytes.put(key, bytecodeCache.get(key));
			if (prev != null) prevSourceBytes.put(key, prev);
		}
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
				byte[] weaved;
				try {
					weaved = applyTransformerBridges(cl, className, targetClass, rawBytes);
				} catch (remold.BytecodeTransformerBridge.Abort abort) {
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
								java.util.function.Function<String, byte[]> oldResolver =
								 normalizeAlignResolver(name -> {
									byte[] src = prevSourceBytes.get(name.replace('/', '.'));
									if (src == null) src = sourceBytecodeCache.get(name.replace('/', '.'));
									return src != null ? src : bytecodeCache.get(name.replace('/', '.'));
								 }, alignLoader);
								java.util.function.Function<String, byte[]> newResolver =
								 normalizeAlignResolver(name -> newBatchBytes.get(name.replace('/', '.')), alignLoader);
								byte[] oldSrc = prevSourceBytes.get(className);
								// 基线优先用 JVM 里**实际生效**的那份（bytecodeCache，含上一轮注入的孤儿桩）：
								// 源码基线不含桩，多轮序列下会与运行期真实形态脱节（rs_revive 实测：
								// 删 A2 再加回，第二轮 A2 槽位串到 A3、A3 直接 NoSuchMethodError）。
								byte[] alignOld = normalizeAlignBaseline(
								 oldBytecode != null ? oldBytecode : oldSrc, alignSlash, alignLoader);
								// 只在"运行期基线确实变了、但选出来的对齐基线却等于新字节"时才告警：
								// IDE 重复构建、字节码没变时 oldBytecode 也等于 newBytecode，那属于正常情况。
								if (java.util.Arrays.equals(alignOld, newBytecode)
									&& oldBytecode != null && !java.util.Arrays.equals(oldBytecode, newBytecode)) {
									warn("[LambdaAligner] baseline == new bytes for " + className
										+ " while the loaded class differs — alignment had nothing to align"
										+ " (stale source cache?). Old callsites will bind by raw javac numbering this round.");
								}
								try {
									newBytecode = LambdaAligner.align(alignOld, newBytecode, oldResolver, newResolver,
									 inventedSlots.getOrDefault(className, java.util.Set.of()));
									// 本轮算出的"自造名"先挂起，等 redefine 成功再落账（失败就丢弃，
									// 因为旧基线没前进，下一轮仍然是同一份旧字节）。
									// 从本轮快照里取，避免读到别的轮次（失败时快照的集合是空的，不是 null）。
									LambdaAligner.AlignmentResult aligned = LambdaAligner.LAST_RESULT;
									pendingInvented.put(className,
									 aligned == null ? java.util.Set.of() : aligned.inventedNames);
								} catch (LambdaAligner.LambdaAlignmentStrictException e) {
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
					}
					if (oldBytecode != null) {
						ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(oldBytecode, newBytecode);
						ClassDiffUtil.logDiff(className, diff);
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
						InitFix.transform(targetClass, newBytecode, diff);
						if (InitFix.requiresTwoPhase(targetClass, hasLiveInstances(targetClass))) needsTwoPhase = true;
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
						if (injectNewClass(className, clHintPath, bytecode)) {
							bytecodeCache.put(className, bytecode);
							injectedCount++;
							// 注入成功即视为生效：指纹可立即落账（不经过 applyRedefinitions）。
							// 失败时不落账，下次触发会重试。指纹同样只记磁盘原始字节。
							String injectRawName = targetToRawName.getOrDefault(className, className);
							Long   injectRawHash = rawDiskHashes.get(injectRawName);
							long   diskHash = injectRawHash != null ? injectRawHash : calculateHash(bytecode);
							synchronized (fileDiskHashes) {
								fileDiskHashes.put(CRC64.hashString(injectRawName), diskHash);
							}
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
						bytecodeCache.put(className, bytecode);
						// lazy_load 只是挂载路径，类尚未真正加载生效 —— 指纹**不**落账，
						// 等它真正被加载/重定义时再记，否则会漏掉后续的首次加载。
					}
				}
			} catch (Throwable e) {
				error("Failed to process " + path, e);
			}
		}

		// 批量执行重定义（针对已加载类）。
		// 两阶段：当本批确实要补"新增/类型变更字段"时，先提交阶段 A（新结构 + 旧方法体，构造器保持新体），
		// 让存量实例在旧行为下被补齐，再提交阶段 C 的新方法体；否则沿用单次重定义的快路径。
		RedefineOutcome outcome;
		if (needsTwoPhase && TWO_PHASE) {
			outcome = applyTwoPhase(definitions, oldEffectiveBytes, transactions.values(), skips);
		} else {
			outcome = applyRedefinitions(definitions, transactions.values(), skips);
		}
		Set<String>     redefinedOk = outcome.successful();
		Set<String>     rolledBack  = outcome.rolledBack();

		// sourceBytecodeCache 是“最近一次实际生效的源码基线”，不是“本轮编译结果”。
		// 对齐阶段必须先看到本轮新字节，所以上面暂存写入；这里按类回滚未生效的已加载类。
		// 注入的新类不在 loadedBefore 中，保持新源码；成功类保持新源码。
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
		if (DEBUG) log("[POST] post-processing done for " + successfulDefinitions.size() + " class(es)");
		List<String> redefinedNames = new ArrayList<>(successfulDefinitions.size());
		for (ClassDefinition def : successfulDefinitions) {
			redefinedNames.add(def.getDefinitionClass().getName());
		}
		// finish 会清掉当前线程的批次状态；即使本批没有类成功，也要发布
		// “Mixin 模板已 reload / InitFix 已修补”这类可观测活动（见 notifyBatchListeners）。
		notifyBatchListeners(successfulDefinitions, BatchReportScope.finish(redefinedNames));

		// L0/L1 必须在 InitFix 后处理（afterRedefine）之后运行：
		// 一是 L1 的"已重放"记录在 applyPatch 里写入，L0 需要据此抑制误导性告警；
		// 二是补丁只能作用于真正重定义成功的类。门拒绝/跳过的类必须零 INIT-DRIFT、零实例枚举。
		Map<String, byte[]> appliedForDrift = new LinkedHashMap<>();
		for (String key : newBatchBytes.keySet()) {
			if (redefinedOk.contains(key)) appliedForDrift.put(key, newBatchBytes.get(key));
		}
		// Drain even when no replay ran: a stale record must not leak into the next round.
		Map<String, Set<String>> replayedForDrift = remold.ref.InitializerReplay.drainApplied();
		remold.ref.InitializerDrift.report(appliedForDrift, driftOldBytes, replayedForDrift);

		Set<Class<?>> successfulClasses = new HashSet<>();
		for (ClassDefinition def : successfulDefinitions) {
			successfulClasses.add(def.getDefinitionClass());
		}
		remold.evict.ReflectionCacheManager.evictAll(successfulClasses);

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
			Object[] instances = null;
			try {
				instances = remold.util.LibTool.getInstances(clazz);
			} catch (Throwable ignored) {
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
	 * 直接定义类，而不是被动加载。
	 *
	 * <p>基线顺序：bytecodeCache → fetchOriginalBytecode → sourceBytecodeCache；
	 * 三者都没有时 warn 并拒绝，不能跳过 LayoutGate/InitFix 静默重定义。</p>
	 */
	private static boolean injectNewClass(String className, Path path, byte[] bytes) {
		if (isNonLoadableClass(className)) {
			// Mixin 模板类不是可加载类：defineClass 会污染宿主 ClassLoader，
			// 并让读者扫描把它误认为外部读者。
			info("[SKIP-INJECT] " + className + " is registered as a non-loadable template; defineClass skipped");
			return false;
		}
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
			// 同名类已经存在：只有当前已加载字节与本次字节完全一致时，才能算"已经注入成功"。
			// 不一致时必须返回 false —— 调用方据此跳过 bytecodeCache.put 与指纹落账，
			// 否则运行基线会被一份从未生效的字节覆盖，后续对齐/InitFix 全部基于假基线。
			Class<?> loaded = loadedClassesMap.get(className);
			byte[] live = null;
			if (loaded != null) {
				try {
					live = fetchBytecodeMemory(loaded);
				} catch (Throwable ignored) {
					// retransform 不可用 / 被其它 transformer 拒绝：无法证明一致，只能保守拒绝。
				}
			}
			if (live != null && java.util.Arrays.equals(live, bytes)) {
				if (DEBUG) log("[INJECT-SKIP] " + className
					+ " was already loaded with identical bytes; nothing to do");
				return true;
			}
			warn("[INJECT-CONFLICT] " + className + " is already loaded with "
				+ (live == null ? "unverifiable" : "different") + " bytes; the new bytes were NOT recorded"
				+ " as the runtime baseline. Restart so the class is defined fresh.");
			return false;
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
	 * 级联阻断：当某个枚举因常量顺序变动被拒绝时，批次中任何持有该枚举 SwitchMap 或对其进行 switch 的调用类也必须移出。
	 * 避免调用方带着期望新 ordinal 的新 switch 代码被载入，导致与存量枚举实例的旧 ordinal 发生静默错位（R6b 守卫）。
	 */
	static void dropEnumSwitchDependents(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                     String enumDotName, RoundSkips skips) {
		String enumSlash = enumDotName.replace('.', '/');
		String switchMapKey = "$SwitchMap$" + enumSlash.replace('/', '$');
		String switchTableMethod = "$SWITCH_TABLE$" + enumSlash.replace('/', '$');
		List<String> dependents = new ArrayList<>();

		for (Map.Entry<String, byte[]> entry : newBatchBytes.entrySet()) {
			byte[] b = entry.getValue();
			if (b == null) continue;
			String clsName = entry.getKey();
			if (clsName.equals(enumDotName) || clsName.equals(enumSlash)) continue;

			try {
				org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(b);
				org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
				cr.accept(cn, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);

				boolean hasSwitch = false;
				for (org.objectweb.asm.tree.FieldNode fn : cn.fields) {
					if (fn.name.contains(switchMapKey)) {
						hasSwitch = true;
						break;
					}
				}
				if (!hasSwitch) {
					for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
						if (mn.name.contains(switchTableMethod)) {
							hasSwitch = true;
							break;
						}
					}
				}
				if (!hasSwitch) {
					for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
						for (org.objectweb.asm.tree.AbstractInsnNode insn = mn.instructions.getFirst();
						     insn != null; insn = insn.getNext()) {
							if (insn instanceof org.objectweb.asm.tree.FieldInsnNode) {
								org.objectweb.asm.tree.FieldInsnNode fin = (org.objectweb.asm.tree.FieldInsnNode) insn;
								if (fin.name.contains(switchMapKey)) {
									hasSwitch = true;
									break;
								}
							} else if (insn instanceof org.objectweb.asm.tree.MethodInsnNode) {
								org.objectweb.asm.tree.MethodInsnNode min = (org.objectweb.asm.tree.MethodInsnNode) insn;
								if (min.name.contains(switchTableMethod)) {
									hasSwitch = true;
									break;
								}
							}
						}
						if (hasSwitch) break;
					}
				}

				if (hasSwitch) {
					dependents.add(clsName);
				}
			} catch (Throwable ignored) {
			}
		}

		for (String dep : dependents) {
			dropFromBatch(newBatchBytes, classToPath, dep);
			if (skips != null) {
				skips.add(dep, RoundSkips.verdict("LAYOUT-REJECT",
					"depends on enum " + enumDotName + " whose constant order changed",
					"revert the enum constant order or restart to continue"));
			}
			warn("[LAYOUT-REJECT] " + dep + ": REFUSED redefine — depends on enum " + enumDotName
			     + " whose constant order changed; dropped to prevent silent switch-on-enum misrouting.");
		}
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

		boolean contains(String className) { return byClass.containsKey(className); }

		String get(String className) { return byClass.get(className); }

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
	 * 点分类名（{@code a.b.C}）→ 内部名（{@code a/b/C}）。
	 *
	 * <p>包内可见：读者扫描（{@link RemovedMemberScanner}）与偏移量扫描共用同一套命名换算，
	 * 两边各写一份会在改写字节码时静默错位。</p>
	 */
	static String internalName(String dottedName) {
		return dottedName.replace('.', '/');
	}

	/** 内部名 → 点分类名；与 {@link #internalName} 互为逆运算。 */
	static String dotted(String internalName) {
		return internalName.replace('/', '.');
	}

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
		Map<String, RemovedMemberScan> readersByClass = RemovedMemberScanner.scan(newBatchBytes, classToPath);

		Set<String> rejectedHosts = new LinkedHashSet<>();
		for (String className : new ArrayList<>(newBatchBytes.keySet())) {
			if (isBlacklisted(className)) continue;
			if (rejectedHosts.contains(LayoutDecisionEngine.hostNameOf(className))) continue;

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
			Set<String> reinitFields = Set.of();
			Map<String, String> unextractableReinit = Map.of();
			try {
				ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(oldBytes, newBytes);
				changed = diff.changedFields;
				reinitFields = diff.reinitFields;
				if (!reinitFields.isEmpty() && diff.newClass != null) {
					unextractableReinit = remold.ref.InitFix.unextractableReinitFields(targetClass, diff.newClass, reinitFields);
				}
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
			                                drift,
			                                reinitFields,
			                                unextractableReinit);

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

			if (d.action() == LayoutAction.WAIVE_REINIT) {
				LAYOUT_GATE_WAIVED.incrementAndGet();
				clearRejected(className);
				info("[LAYOUT-REINIT] " + className + ": 经 @RemoldReinit 授权放行字段类型变更 ("
				     + (d.layout() == null ? "?" : d.layout().detail()) + ")");
				continue;
			}

			if (d.action() == LayoutAction.WAIVE) {
				LAYOUT_GATE_WAIVED.incrementAndGet();
				clearRejected(className);
				warn("[LAYOUT-WAIVE] " + className + ": incompatible field layout ("
				     + d.layout().detail() + ") but no scan-visible live instances; redefining anyway — new"
				     + " instances go through the new constructor and initialize correctly. This is what the"
				     + " heap scan saw, not proof that no instance exists.");
				continue;
			}

			if (d.action() == LayoutAction.REJECT) {
				LAYOUT_GATE_REJECTED.incrementAndGet();
				String why = d.reason() != null
					? d.reason()
					: "field layout incompatible (" + (d.layout() == null ? "unknown" : d.layout().detail()) + ")";
				int repeats = noteRejected(className, why);
				String hint = "";
				if (d.layout() != null && d.layout().verdict() == LayoutGate.Verdict.CHANGED_FIELD_TYPE) {
					hint = "可在该字段上添加 @RemoldReinit 后再次编译保存，即可在不重启的情况下完成类型切换并重置初值；或 ";
				}
				warn("[LAYOUT-REJECT] " + className + ": REFUSED redefine — " + why + ". Host "
				     + d.hostName() + " and its anonymous classes are"
				     + " skipped this round; existing instances keep running the OLD code, so this edit will"
				     + " NOT take effect for them. TO PROCEED: " + hint + "recreate/restart so the classes load fresh, or"
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
				if (d.layout() != null && d.layout().verdict() == LayoutGate.Verdict.CHANGED_ENUM_ORDER) {
					dropEnumSwitchDependents(newBatchBytes, classToPath, className, skips);
				}
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
		/**
		 * 字段类型变更，且<b>经 @RemoldReinit 授权</b> —— 放行。
		 */
		WAIVE_REINIT,
		/** 拒绝并整组移出本批。 */
		REJECT,
		/** 无法判定（取不到基线 / diff 失败）—— 放行但必须告警，不得静默。 */
		SKIP
	}

	/**
	 * 一轮"被删成员的批次外读者"扫描结果（判据见 {@code docs/initfix/07-p05-probe-results.md} §13.6）。
	 *
	 * <p>{@code scanned == false} 表示本轮没有做这项判定（本批没有删除、或扫描前置条件不满足）。
	 * 这种情况调用方<b>必须拒绝</b>，<b>不能</b>当成"没有读者"。</p>
	 *
	 * @param scanned         本轮扫描是否完成。{@code false} 时调用方<b>必须</b>拒绝——删除的影响
	 *                        与当前是否有存活实例正交，退回 liveness 会把"没扫成"读成"没读者"
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
	 * 布局门判决的对外入口，算法见 {@link LayoutDecisionEngine}。
	 *
	 * <p>{@link LayoutDecision} / {@link LayoutAction} / {@link Liveness} / {@link RemovedMemberScan}
	 * 这几个类型仍由本类声明：它们是宿主与回归用例绑定的判决词汇。算法搬到协作类后，
	 * 「为什么拒绝」的推演不再需要读整条热更流水线。</p>
	 */
	public static LayoutDecision decideLayout(String className, byte[] oldBytes, byte[] newBytes,
	                                          String mode, Liveness liveness, Set<String> batchNames) {
		return LayoutDecisionEngine.decide(className, oldBytes, newBytes, mode, liveness, batchNames);
	}

	/** @see LayoutDecisionEngine#decide(String, byte[], byte[], String, Liveness, Set, RemovedMemberScan) */
	public static LayoutDecision decideLayout(String className, byte[] oldBytes, byte[] newBytes,
	                                          String mode, Liveness liveness, Set<String> batchNames,
	                                          RemovedMemberScan removedFieldReaders) {
		return LayoutDecisionEngine.decide(className, oldBytes, newBytes, mode, liveness, batchNames,
		                                   removedFieldReaders);
	}

	/** @see LayoutDecisionEngine#decide(String, byte[], byte[], String, Liveness, Set, RemovedMemberScan, SuperclassDriftGate.DriftDecision) */
	public static LayoutDecision decideLayout(String className, byte[] oldBytes, byte[] newBytes,
	                                          String mode, Liveness liveness, Set<String> batchNames,
	                                          RemovedMemberScan removedFieldReaders,
	                                          SuperclassDriftGate.DriftDecision drift) {
		return LayoutDecisionEngine.decide(className, oldBytes, newBytes, mode, liveness, batchNames,
		                                   removedFieldReaders, drift);
	}

	/** @see LayoutDecisionEngine#decide(String, List, String, Liveness, Set) */
	public static LayoutDecision decideLayout(String className, List<String> changedFields,
	                                          String mode, Liveness liveness, Set<String> batchNames) {
		return LayoutDecisionEngine.decide(className, changedFields, mode, liveness, batchNames);
	}

	/** @see LayoutDecisionEngine#decide(String, List, String, Liveness, Set, RemovedMemberScan) */
	public static LayoutDecision decideLayout(String className, List<String> changedFields,
	                                          String mode, Liveness liveness, Set<String> batchNames,
	                                          RemovedMemberScan scan) {
		return LayoutDecisionEngine.decide(className, changedFields, mode, liveness, batchNames, scan);
	}

	/** @see LayoutDecisionEngine#decide(String, List, String, Liveness, Set, RemovedMemberScan, SuperclassDriftGate.DriftDecision) */
	public static LayoutDecision decideLayout(String className, List<String> changedFields,
	                                          String mode, Liveness liveness, Set<String> batchNames,
	                                          RemovedMemberScan scan,
	                                          SuperclassDriftGate.DriftDecision drift) {
		return LayoutDecisionEngine.decide(className, changedFields, mode, liveness, batchNames, scan, drift);
	}

	/** @see LayoutDecisionEngine#decide(String, List, String, Liveness, Set, RemovedMemberScan, SuperclassDriftGate.DriftDecision, Set) */
	public static LayoutDecision decideLayout(String className, List<String> changedFields,
	                                          String mode, Liveness liveness, Set<String> batchNames,
	                                          RemovedMemberScan scan,
	                                          SuperclassDriftGate.DriftDecision drift,
	                                          Set<String> reinitFields) {
		return LayoutDecisionEngine.decide(className, changedFields, mode, liveness, batchNames, scan, drift, reinitFields);
	}

	/** @see LayoutDecisionEngine#decide(String, List, String, Liveness, Set, RemovedMemberScan, SuperclassDriftGate.DriftDecision, Set, Map) */
	public static LayoutDecision decideLayout(String className, List<String> changedFields,
	                                          String mode, Liveness liveness, Set<String> batchNames,
	                                          RemovedMemberScan scan,
	                                          SuperclassDriftGate.DriftDecision drift,
	                                          Set<String> reinitFields,
	                                          Map<String, String> unextractableReinitFields) {
		return LayoutDecisionEngine.decide(className, changedFields, mode, liveness, batchNames, scan, drift, reinitFields, unextractableReinitFields);
	}

	/** @see LayoutDecisionEngine#skipVerdictForLayout(LayoutDecision, RemovedMemberScan) */
	public static String skipVerdictForLayout(LayoutDecision d, RemovedMemberScan scan) {
		return LayoutDecisionEngine.skipVerdictForLayout(d, scan);
	}

	/**
	 * redefine 成功后，把本轮算出的"自造槽位名"并入 {@link #inventedSlots}。
	 *
	 * <p>只在成功路径调用：失败/回滚时旧基线（{@link #bytecodeCache}）没有前进，
	 * 自造名集合也必须保持与旧基线一致，否则下一轮会拿"从未加载过的版本"的身份去做配对。</p>
	 */
	private static void commitInvented(String className) {
		java.util.Set<String> pending = pendingInvented.remove(className);
		if (pending == null) {
			inventedSlots.remove(className);
		} else if (pending.isEmpty()) {
			inventedSlots.remove(className);
		} else {
			inventedSlots.put(className, pending);
		}
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
	static void rollbackSourceCache(Map<String, byte[]> sourceBefore, Set<String> applied,
	                                Set<String> loadedBefore) {
		for (String key : sourceBefore.keySet()) {
			if (applied.contains(key)) continue;
			byte[] prev = sourceBefore.get(key);
			// 新注入类（没有旧源码、也不在 loadedBefore）保持新源码；其余未成功类回滚。
			if (prev == null && !loadedBefore.contains(key)) continue;
			if (prev == null) sourceBytecodeCache.remove(key);
			else sourceBytecodeCache.put(key, prev);
		}
	}

	/**
	 * 两阶段 Schema-First 重定义的生产接线（{@code docs/initfix/04-target-design.md} §1）。
	 *
	 * <p><b>为什么需要它</b>：{@code redefineClasses} 返回后新方法体立刻可执行，而新增字段要等
	 * {@link InitFix#applyPatch} 枚举完堆才写入。重定义那一帧里如果业务线程（渲染/逻辑）先跑了
	 * 新方法体，读到的就是 {@code null}/{@code 0}——引用类型直接 NPE。两阶段把「结构上线」与
	 * 「新方法体上线」拆成两次重定义，中间夹一次补丁；窗口内的旧方法体在编译期就不知道新增字段，
	 * 不可能读穿。</p>
	 *
	 * <p><b>与设计稿的差异</b>：阶段 A 的构造器保留<b>新</b>执行体
	 * （{@code StageASkeleton.buildWithLiveConstructors}），因此阶段 A→C 之间新建的实例由新构造器
	 * 直接完成初始化，不需要 {@code ConstructorTailInjector} 反向推导取值切片。除构造器/静态初始化器
	 * 之外的既有方法仍整体回退旧体：旧字节码不引用新增字段，所以窗口内不会读穿。</p>
	 *
	 * <p><b>不启用条件</b>：本批没有任何「新增/类型变更字段」进入补丁计划（纯方法体热更）时，调用方
	 * 不会走这里；{@code -Dremold.agent.two_phase=false} 可强制回到单阶段快路径。</p>
	 */
	private static RedefineOutcome applyTwoPhase(List<ClassDefinition> definitions,
	                                             Map<String, byte[]> oldEffectiveBytes,
	                                             Collection<AlignmentTransaction> transactions,
	                                             RoundSkips skips) {
		long startedNanos = System.nanoTime();
		if (definitions.isEmpty()) {
			for (AlignmentTransaction tx : transactions) tx.commit();
			skips.report();
			return new RedefineOutcome(Set.of(), Set.of());
		}

		// ---- 阶段 A：新结构 + 旧方法体（构造器保持新体） ----
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
			} else if (!InitFix.requiresTwoPhase(clazz, hasLiveInstances(clazz))) {
				// 该类本身无需两阶段保护（如无存量实例且无静态字段，或纯方法体修改）：阶段 A 直接使用目标字节
				if (DEBUG) log("[HOTSWAP-2P] Stage A uses target bytes directly for " + name + " (no live instances / no read-through hazard)");
				skeleton = target;
			} else {
				try {
					skeleton = remold.ref.StageASkeleton.buildWithLiveConstructors(old, target);
				} catch (Throwable t) {
					error("[HOTSWAP-2P] Cannot synthesise stage A for " + name
					      + "; falling back to the target bytes for this class.", t);
					skeleton = target;
				}
			}
			stageADefinitions.add(new ClassDefinition(clazz, skeleton));
			stageABytes.put(name, skeleton);
		}

		Set<String> stageAAbandoned = new HashSet<>();
		Set<String> stageAOk        = redefineStageA(stageADefinitions, stageABytes, transactions, skips,
		                                              stageAAbandoned);
		if (stageAOk.isEmpty()) {
			warn("[HOTSWAP-2P] Stage A did not apply any class; the two-phase transaction is abandoned.");
			skips.report();
			return new RedefineOutcome(Set.of(), Set.of());
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
		for (ClassDefinition def : stageADefinitions) {
			Class<?> clazz = def.getDefinitionClass();
			if (!stageAOk.contains(clazz.getName())) continue;
			try {
				InitFix.afterRedefine(clazz);
			} catch (Throwable t) {
				// afterRedefine 自己已兜住 LinkageError 等致命异常并记账；这里只防御它自身抛错。
				error("[HOTSWAP-2P] Stage B patch failed for " + clazz.getName()
				      + "; stage C will still be applied, so unpatched fields stay at defaults.", t);
			}
		}
		long patchMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - patchStart);

		// ---- 阶段 C：只对阶段 A 成功的类提交完整新字节码 ----
		List<ClassDefinition> stageCDefinitions = new ArrayList<>(stageAOk.size());
		for (ClassDefinition def : definitions) {
			if (stageAOk.contains(def.getDefinitionClass().getName())) stageCDefinitions.add(def);
		}
		List<AlignmentTransaction> activeTransactions = new ArrayList<>(transactions.size());
		for (AlignmentTransaction tx : transactions) {
			if (stageAOk.contains(tx.hostName)) activeTransactions.add(tx);
		}
		RedefineOutcome stageCOutcome = applyRedefinitions(stageCDefinitions, activeTransactions, skips);
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

	/**
	 * 只提交阶段 A 字节码：成功集合、缓存与事务登记都不算「本轮生效」，要等阶段 C 才落账。
	 *
	 * <p>失败的类立即丢弃其暂存补丁并记入台账（与单阶段 {@link #applyRedefinitions} 的失败语义一致）；
	 * 宿主与匿名类必须整组成功，否则回滚事务并整组移出阶段 C（重试基线已不一致，提示重启）。</p>
	 */
	private static Set<String> redefineStageA(List<ClassDefinition> definitions,
	                                          Map<String, byte[]> stageABytes,
	                                          Collection<AlignmentTransaction> transactions,
	                                          RoundSkips skips,
	                                          Set<String> abandonedOut) {
		for (AlignmentTransaction tx : transactions) tx.preRegister();

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
				inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));
				for (ClassDefinition def : definitions) ok.add(def.getDefinitionClass().getName());
			} catch (Throwable t) {
				error("[HOTSWAP-2P] Stage A bulk redefine failed, switching to individual mode...", t);
				for (ClassDefinition def : definitions) {
					String className = def.getDefinitionClass().getName();
					try {
						inst.redefineClasses(def);
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
				bytecodeCache.put(name, bytes);
				Class<?> c = loadedClassesMap.get(name);
				recordCacheLoader(name, c == null ? null : c.getClassLoader());
			}
		}

		// 宿主与匿名类必须同批生效；否则阶段 C 的基线已不一致，整组移出。
		Set<String> abandoned = new HashSet<>();
		for (AlignmentTransaction tx : transactions) {
			boolean hostOk     = ok.contains(tx.hostName);
			boolean allAnonsOk = true;
			for (String target : tx.targetClasses) {
				if (loadedClassesMap.containsKey(target) && !ok.contains(target)) allAnonsOk = false;
			}
			if (hostOk && allAnonsOk) continue;
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

	private static RedefineOutcome applyRedefinitions(List<ClassDefinition> definitions,
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
				recordCacheLoader(def.getDefinitionClass().getName(), def.getDefinitionClass().getClassLoader());
				commitInvented(def.getDefinitionClass().getName());
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
					inst.redefineClasses(def);
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
					bytecodeCache.put(className, def.getDefinitionClassFile());
					recordCacheLoader(className, def.getDefinitionClass().getClassLoader());
					commitInvented(className);
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

	/**
	 * {@link #applyRedefinitions} 的结果。
	 * @param successful 确实重定义成功的类名
	 * @param rolledBack 因事务组不一致被 rollback 的类名（宿主 + 其匿名类）——
	 *                   这些类<b>不得回队重试</b>，理由见该方法 javadoc
	 */
	private record RedefineOutcome(Set<String> successful, Set<String> rolledBack) { }
	//endregion

	//region File Processing Utilities

	static void initializeAgentState(Class<?>[] classes) {
		info("Scanning files...");
		// 直接传入的 jar/zip 文件：立即解压扫描
		for (Path jar : activeWatchJars) {
			String stem = jar.getFileName().toString().replaceAll("[^a-zA-Z0-9_-]", "_");
			Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "remold-hotswap",
			 stem + "-" + Long.toHexString(CRC64.hashString(jar.toString())));
			List<Path> outOfSync = new ArrayList<>();
			try {
				Files.createDirectories(tmpDir);
				JarExtractor.tmpToJar().entrySet().removeIf(e -> e.getValue().equals(jar));
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
								JarExtractor.tmpToJar().put(outPath, jar);
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
		Map<String, Path> latestClassPaths = new HashMap<>();
		Map<String, byte[]> latestClassBytes = new HashMap<>();
		for (Path root : activeWatchDirs) {
			try (Stream<Path> walk = Files.walk(root)) {
				walk.filter(p -> {
					String s = p.toString().replace('\\', '/');
					if (isWatchPathIgnored(p)) return false;
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
												JarExtractor.tmpToJar().put(outPath, absJar);
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
					// 原始 .class 文件逻辑：按类名聚合，并取最后修改时间最新者
					try {
						byte[] diskBytes = Files.readAllBytes(path);
						String cName     = Utils.getClassNameASM(diskBytes);
						if (cName == null || isBlacklisted(cName)) return;

						Path existing = latestClassPaths.get(cName);
						if (existing != null) {
							try {
								if (Files.getLastModifiedTime(path).compareTo(Files.getLastModifiedTime(existing)) <= 0) {
									return;
								}
							} catch (Throwable ignored) {
							}
						}
						latestClassPaths.put(cName, path);
						latestClassBytes.put(cName, diskBytes);
					} catch (Exception ignored) { }
				});
			} catch (IOException ignored) { }
		}

		// 统一对最新版本的 .class 进行哈希比对与落账
		for (Map.Entry<String, Path> entry : latestClassPaths.entrySet()) {
			String cName = entry.getKey();
			Path   path  = entry.getValue();
			byte[] diskBytes = latestClassBytes.get(cName);
			if (diskBytes == null) continue;

			try {
				long diskHash = calculateHash(diskBytes);
				byte[] memBytes = bytecodeCache.get(cName);
				if (memBytes != null) {
					long memHash = calculateHash(memBytes);
					if (diskHash != memHash) {
						if (DEBUG) log("[INIT-SYNC] " + cName + " is out of sync. Reloading...");
						pendingChanges.add(path);
					}
				}
				synchronized (fileDiskHashes) {
					fileDiskHashes.put(CRC64.hashString(cName), diskHash);
				}
			} catch (Exception ignored) { }
		}
		// 入参 classes 是调用方在 FileWatcherService.restart 之前取的快照，此刻可能已过期
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

	static void handleAssetChange(Path changedAsset) {
		if (DEBUG) log("[ASSET] Queued asset change: " + changedAsset);
		synchronized (pendingChanges) {
			pendingAssetChanges.add(changedAsset);
			DEBOUNCE.onAsset(System.nanoTime());
		}
		rescheduleDebounce();
	}

	static void handleFileChange(Path changedFile) {
		synchronized (pendingChanges) {
			pendingChanges.add(changedFile);
			DEBOUNCE.onClass(System.nanoTime());
		}
		rescheduleDebounce();
	}

	/**
	 * 处理 jar/zip 变化：与 .class 共用 Class 流的静默窗口，防止读到写了一半的 jar。
	 * 真正的解压在 Class 批次到期后由 {@link JarExtractor#extract} 完成。
	 */
	static void handleJarChange(Path jarPath) {
		if (DEBUG) log("[JAR] Queued for extraction: " + jarPath);
		synchronized (pendingChanges) {
			pendingJars.add(jarPath.toAbsolutePath());
			DEBOUNCE.onClass(System.nanoTime());
		}
		rescheduleDebounce();
	}

	/** 按 {@link DebounceWindow} 的最早 deadline 重新挂调度。 */
	private static void rescheduleDebounce() {
		synchronized (pendingChanges) {
			rescheduleDebounceLocked();
		}
	}

	private static void rescheduleDebounceLocked() {
		long delay = DEBOUNCE.nextDelayNanos(System.nanoTime());
		if (scheduledTask != null && !scheduledTask.isDone()) {
			scheduledTask.cancel(false);
		}
		if (delay < 0) {
			scheduledTask = null;
			return;
		}
		scheduledTask = scheduler.schedule(RemoldAgent::triggerDueHotswap, delay, TimeUnit.NANOSECONDS);
	}

	/**
	 * 启动就绪门闩判定：{@code true} = 允许本批进入批次执行。
	 *
	 * <p><b>就绪来源</b>（core 不认识任何加载器/游戏专属概念）：</p>
	 * <ul>
	 *   <li>插件投票：{@link HotswapPlugin#isHostReady()} 任一返回 {@code false} 即未就绪；</li>
	 *   <li>{@link StartupGate} 的 PENDING → READY 一次性变迁（全部插件就绪的首个观测点）；</li>
	 *   <li>可选超时放行：{@code -Dremold.agent.ready_timeout_ms}（默认 {@code 0} = 不放行），
	 *       只用于"宿主插件永远不报就绪"这种死等，且日志必须标明是超时放行。</li>
	 * </ul>
	 *
	 * <p>调用方必须持有 {@code HOTSWAP_LOCK}；本方法内部只进 {@code synchronized(pendingChanges)}
	 * （锁序正确：先全局锁、后队列锁）。</p>
	 */
	private static boolean startupGateAllowsBatch() {
		StartupGate gate = STARTUP_GATE;
		long       now   = System.nanoTime();
		String     blocker = firstPluginNotReady();

		if (blocker != null) {
			// 插件明确说未就绪（宿主仍在启动期）：门闩保持 PENDING，一个批次都不落。
			// 唯一的出口是显式配置的超时放行——默认 0 表示宁可押着，也不要在启动风暴里落批次。
			// 注意：门闩此前已经 READY（启动期已过）时 isReadyAt 也返回 true，但那种情况
			// **不是**超时放行，插件的否票仍然生效（fail-closed），否则运行期插件回退就失去意义。
			boolean gateOpen = gate.isReadyAt(now);
			if (gateOpen && gate.isTimedOutRelease()) {
				logGateReadyOnce(true, blocker, gate);
				return true;
			}
			int classPending;
			int assetPending;
			synchronized (pendingChanges) {
				classPending = pendingChanges.size() + pendingJars.size();
				assetPending = pendingAssetChanges.size();
			}
			if (classPending == 0 && assetPending == 0) {
				// 没有待处理变更：没什么可推迟的，不挂重查（下一次入队会重新挂防抖）。
				return false;
			}
			if (!gateBlockLogged) {
				gateBlockLogged = true;
				warn("[READY-GATE] hot-swap batches deferred: plugin '" + blocker + "' reports isHostReady()=false; "
				     + classPending + " class/jar change(s) and " + assetPending + " asset change(s) stay queued"
				     + (gate.getTimeoutMillis() > 0
				         ? " (ready_timeout_ms=" + gate.getTimeoutMillis() + ")"
				         : " (ready_timeout_ms=0: no timeout release)")
				     + (gateOpen ? " [startup gate already READY; the plugin veto is honored]" : "")
				     + ". Further deferrals of this same batch are not logged again.");
			}
			rescheduleStartupGateRetry();
			return false;
		}

		// 全部插件就绪：首个观测点把门闩翻到 READY，此后不再回退（启动期是一次性窗口）。
		if (gate.markReady()) {
			logGateReadyOnce(false, null, gate);
		}
		if (!gate.isReady()) {
			// 防御：门闩没开就不放行。
			rescheduleStartupGateRetry();
			return false;
		}
		return true;
	}

	/** 返回第一个明确报告未就绪的插件名；全部就绪返回 {@code null}。 */
	private static String firstPluginNotReady() {
		for (HotswapPlugin plugin : plugins) {
			try {
				if (!plugin.isHostReady()) return plugin.getName();
			} catch (Throwable t) {
				// fail-closed：判定不了就按未就绪处理。细节走 DEBUG 门控（避免按重查节奏刷屏），
				// 阻断日志（warn）已经点名是哪个插件，信息不丢。
				log("[READY-GATE] plugin " + plugin.getName()
				    + " threw from isHostReady(); treating it as not ready: " + t);
				return plugin.getName();
			}
		}
		return null;
	}

	/** 就绪状态变化只打一条：{@code PENDING → READY} 或超时放行各一次。 */
	private static void logGateReadyOnce(boolean byTimeout, String blocker, StartupGate gate) {
		if (gateReadyLogged) return;
		gateReadyLogged = true;
		// 新的一次就绪允许下一段阻断重新打点，否则运行期再次未就绪时会静默。
		gateBlockLogged = false;
		if (byTimeout) {
			warn("[READY-GATE] ready_timeout_ms=" + gate.getTimeoutMillis()
			     + " elapsed while plugin '" + blocker + "' still reports isHostReady()=false — "
			     + "releasing the startup gate by timeout; batches resume even though the host may still be booting.");
		} else {
			info("[READY-GATE] host reported ready (all plugins return isHostReady()=true) —"
			     + " class/asset batches are enabled");
		}
	}

	/**
	 * 门闩未放行时的重查挂载：固定间隔（不小于 {@link #GATE_RETRY_MIN_MS}），
	 * 绝不复用 {@link #rescheduleDebounce()}——防抖已到期时它的延迟是 0，会变成锁内自旋。
	 */
	private static void rescheduleStartupGateRetry() {
		long retryMillis = Math.max(GATE_RETRY_MIN_MS, FILE_SHAKE_MS);
		synchronized (pendingChanges) {
			if (scheduledTask != null && !scheduledTask.isDone()) {
				scheduledTask.cancel(false);
			}
			scheduledTask = scheduler.schedule(RemoldAgent::triggerDueHotswap, retryMillis, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * 防抖到期入口：只处理已经静默到期的流。资源流到期时绝不提前 drain 尚未静默的
	 * Class/jar 队列；Class 流到期时也只在同一个批次里处理 Class + jar。
	 *
	 * <p><b>唯一批次入口</b>：启动就绪门闩在这里生效——宿主未就绪时<b>只</b>把变更留在
	 * {@code pendingChanges} / {@code pendingAssetChanges} 里，绝不进入 {@link #runClassBatch}
	 * 或 {@link #processChanges}（后者硬性要求持有 {@code HOTSWAP_LOCK}）。</p>
	 */
	private static void triggerDueHotswap() {
		if (inst == null) {
			return;
		}
		HOTSWAP_LOCK.lock();
		try {
			// 门闩在 drain 之前：未就绪时一条变更都不许被取走，否则"留在队列里"是空话。
			if (!startupGateAllowsBatch()) {
				return;
			}
			Set<Path> assets = null;
			Set<Path> jars = null;
			boolean classDue;
			boolean assetDue;
			synchronized (pendingChanges) {
				long now = System.nanoTime();
				classDue = DEBOUNCE.isClassDue(now)
					&& (!pendingChanges.isEmpty() || !pendingJars.isEmpty());
				assetDue = DEBOUNCE.isAssetDue(now) && !pendingAssetChanges.isEmpty();
				if (assetDue) {
					assets = new HashSet<>(pendingAssetChanges);
					pendingAssetChanges.clear();
					DEBOUNCE.clearAsset();
				}
				if (classDue) {
					jars = new HashSet<>(pendingJars);
					pendingJars.clear();
					// 先不清 Class pending：jar 解压可能向 pendingChanges 追加类文件，
					// 这些也是本批的一部分；最终 drain 放在解压之后。
				}
			}
			if (assets != null) {
				notifyAssetChanges(assets);
			}
			if (!classDue) {
				rescheduleDebounce();
			}
			if (classDue) {
				if (jars != null) {
					try {
						jars.forEach(JarExtractor::extract);
					} catch (Throwable t) {
						error("[HOTSWAP] Jar extraction failed; the current class batch will still be processed: " + t);
					}
				}
				Set<Path> changes;
				synchronized (pendingChanges) {
					changes = new HashSet<>(pendingChanges);
					pendingChanges.clear();
					// 解压期间若又有新的 jar 事件，留给下一批，不要把 Class 流标记为已处理。
					if (pendingJars.isEmpty()) {
						DEBOUNCE.clearClass();
					}
					rescheduleDebounceLocked();
				}
				runClassBatch(changes);
			}
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	/**
	 * 执行一批已经静默的 Class 变更；错误边界与手动触发保持一致。
	 *
	 * <p><b>看门狗不在这里</b>：{@link BatchWatchdog} 挂在 {@link #processChanges} 上，
	 * 这样手动 {@link #triggerHotswap()} 等入口也能覆盖。本方法<b>不得</b>再独立
	 * {@code arm} 一个看门狗——那会让同一批次出现两条 {@code [HOTSWAP-STALL]}
	 * （一次批次两次 dump = 前缀被训练成噪声）。这里只保留错误边界与批次统计的清理。</p>
	 */
	private static void runClassBatch(Set<Path> changes) {
		try {
			resetLiveInstanceCache();
			if (!changes.isEmpty()) {
				processChanges(changes, inst.getAllLoadedClasses());
			}
		} catch (Throwable t) {
			error("[HOTSWAP] Hot-swap pipeline aborted: redefine and post-processing are not atomic,"
			      + " so classes may have been redefined WITHOUT InitFix patches or @OnReload callbacks."
			      + " The next save re-runs from the current baseline; if it keeps failing, restart.", t);
		} finally {
			BatchReportScope.discard();
		}
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

	/**
	 * 一轮热更的统一错误边界。
	 *
	 * <p><b>为什么必须有</b>：本方法由防抖调度器以 {@code scheduler.schedule(RemoldAgent::triggerDueHotswap, ...)}
	 * 调用，抛出的一切都会被 {@link java.util.concurrent.Future} 静默吞掉——没有任何线程的
	 * uncaught handler 会看到它。而 {@code processChanges} 的后处理（InitFix 补丁、{@code @OnReload}
	 * 回调、缓存驱逐）排在 {@code redefineClasses} 成功之后：那里一旦抛异常，用户看到的就是
	 * 日志里"HotSwap successful"之后<b>再无下文</b>——新增字段停在 0、回调不触发，且毫无线索。</p>
	 *
	 * <p>所以这里兜住 {@link Throwable} 并显式 {@code error(...)}：宁可让这一轮失败得大声，
	 * 也不要让它失败得像成功。</p>
	 */
	private static void triggerHotswapWith0(Class<?>[] classes) {
		try {
			triggerHotswapWith0Unchecked(classes);
		} catch (Throwable t) {
			error("[HOTSWAP] Hot-swap pipeline aborted: redefine and post-processing are not atomic,"
			      + " so classes may have been redefined WITHOUT InitFix patches or @OnReload callbacks."
			      + " The next save re-runs from the current baseline; if it keeps failing, restart.", t);
		} finally {
			// 异常路径不会走到 processChanges 末尾的 finish；这里兜底清掉 ThreadLocal，
			// 防止下一轮批次把上一轮的半截统计混进来。
			BatchReportScope.discard();
		}
	}

	private static void triggerHotswapWith0Unchecked(Class<?>[] classes) {
		// 手动/强制入口：取消挂起的防抖调度并清空两条流的状态，立即处理全部队列。
		synchronized (pendingChanges) {
			if (scheduledTask != null && !scheduledTask.isDone()) {
				scheduledTask.cancel(false);
			}
			scheduledTask = null;
			DEBOUNCE.clearAll();
		}
		// 实例存活状况每轮都可能变（对象被创建/回收），缓存必须按轮清空
		resetLiveInstanceCache();
		// 先把所有待处理的 jar 解压（防抖已结束，文件写入完毕）
		Set<Path> jars;
		synchronized (pendingChanges) {
			jars = new HashSet<>(pendingJars);
			pendingJars.clear();
		}
		jars.forEach(JarExtractor::extract);

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
			BatchReportScope.begin();
			LAST_REDEFINE_VM_LEVEL_FAILURE = false;
			String className = targetClass.getName();
			byte[] sourceBytes = newBytecode;
			byte[] runtimeBytes;
			try {
				runtimeBytes = applyTransformerBridges(targetClass.getClassLoader(), className, targetClass, newBytecode);
			} catch (remold.BytecodeTransformerBridge.Abort abort) {
				// 事务一致性：桥接器判定本轮不能落地（例如目标类的合成内部类被闸拒绝）。
				// 此时既不能送织入后的新字节（会与旧合成类组成 NoSuchMethodError 分裂状态），
				// 也不能送未织入的原始字节（会抹掉 Mixin 钩子）——唯一正确动作是不 redefineClasses。
				warn("[DYNAMIC-REDEFINE] " + className + ": aborted by the bytecode bridge before redefineClasses ("
				     + abort.getMessage() + "). The class keeps its previously loaded bytes; nothing was redefined.");
				return false;
			}
			// 第二缺陷：Mixin 生成成员名/顺序先对齐回基线，让纯方法体热更退化为 0 新增/0 删除。
			runtimeBytes = alignMixinGeneratedMembers(className, targetClass, runtimeBytes);
			// 第一层：按已加载基线做访问权限单调对齐；自检门拒绝时绝不调用 redefineClasses。
			runtimeBytes = alignAccessMonotonicity(className, targetClass, runtimeBytes);
			if (runtimeBytes == null) {
				return false;
			}

			byte[] oldBytes = bytecodeCache.get(className);
			if (oldBytes == null) oldBytes = fetchOriginalBytecode(targetClass);
			if (oldBytes == null) oldBytes = sourceBytecodeCache.get(className);
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
				     + " runtime bytecode equals the baseline; no redefine performed (nothing to change).");
				return true;
			}
			ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(oldBytes, runtimeBytes);
			if (ClassDiffUtil.mustRejectHierarchyChange(diff, isEnhancedHotswapEnabled())) {
				error("REJECTED: Class hierarchy change detected for dynamic class " + className + " ("
					+ ClassDiffUtil.describeHierarchyChange(diff) + ")."
					+ (diff.superChanged
						? " Changing the superclass is not supported by any redefinition mode; RESTART required."
						: " Interface changes require enhanced class redefinition (JBR/DCEVM); RESTART required on this JVM."));
				return false;
			}
			LayoutDecision decision = decideDynamicLayout(targetClass, className, diff, runtimeBytes);
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
					     + ": 经 @RemoldReinit 授权放行字段类型变更 (" + why + ")");
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
			InitFix.transform(targetClass, runtimeBytes, diff);

			// 堆实例哨兵三切面（默认关闭，-Dremold.probe.heapwatch=true 开启）：
			// 用于定位「某类字段在 redefine / 字段初始化补丁窗口内被置 null」这类跨类污染。
			remold.ref.HeapFieldWatch.snapshot("before-redefine", className);
			enterInternalRedefine(Collections.singleton(className));
			try {
				inst.redefineClasses(new ClassDefinition(targetClass, runtimeBytes));
				remold.ref.HeapFieldWatch.snapshot("after-redefine", className);
				bytecodeCache.put(className, runtimeBytes);
				sourceBytecodeCache.put(className, sourceBytes);
				recordCacheLoader(className, targetClass.getClassLoader());
				try {
					InitFix.afterRedefine(targetClass);
				} catch (Throwable t) {
					error("Failed to run InitFix after redefine for " + className, t);
					InitFix.afterRedefineFailed(targetClass);
				}
				remold.ref.HeapFieldWatch.snapshot("after-field-patch", className);
				processAnnotations(Collections.singletonList(new ClassDefinition(targetClass, runtimeBytes)));
				notifyBatchListeners(Collections.singletonList(new ClassDefinition(targetClass, runtimeBytes)),
					BatchReportScope.finish(Collections.singletonList(className)));
				info("[DYNAMIC-REDEFINE] Successfully redefined " + className);
				return true;
			} finally {
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
	private static LayoutDecision decideDynamicLayout(Class<?> targetClass, String className,
	                                                   ClassDiffUtil.ClassDiff diff, byte[] runtimeBytes) {
		if (LayoutGate.MODE_OFF.equals(LAYOUT_GATE)) {
			return new LayoutDecision(LayoutAction.PASS, null, className, Set.of());
		}
		LayoutGate.Result layoutRes = LayoutGate.checkChangedFields(diff.changedFields);
		Liveness liveness = layoutRes.compatible()
			? Liveness.LIVE
			: (hasLiveInstances(className) ? Liveness.LIVE : Liveness.NONE);

		RemovedMemberScan scan = RemovedMemberScan.NOT_SCANNED;
		Path classFile = classFilePath(targetClass);
		if (classFile != null) {
			// inBatch 必须包含 target 自己。读者扫描器据此排除"本批类"，只扫批次外其他类。
			Map<String, RemovedMemberScan> scans = RemovedMemberScanner.scan(
				Map.of(className, runtimeBytes), Map.of(className, classFile));
			scan = scans.getOrDefault(className, RemovedMemberScan.NOT_SCANNED);
		}

		SuperclassDriftGate.DriftDecision drift = checkSuperclassDriftIfApplicable(
			className, targetClass, diff.changedFields, runtimeBytes, Map.of(className, runtimeBytes));

		return decideLayout(className, diff.changedFields, LAYOUT_GATE, liveness,
			Set.of(className), scan, drift, diff.reinitFields);
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
	/**
	 * 增强重定义能力探测与降级，实现见 {@link EnvironmentProbe}。
	 *
	 * <p>这里只保留门面入口：{@link RemoldAgent} 是外部调用方（插件、宿主、测试）唯一认识的类，
	 * 而探测与降级的判定逻辑已经搬到协作类，不再与热更流水线共享同一个类体。</p>
	 */
	public static boolean looksLikeEnhancedRuntime(String runtimeIdentity) {
		return EnvironmentProbe.looksLikeEnhancedRuntime(runtimeIdentity);
	}

	/** @see EnvironmentProbe#isFlagEnabled(java.util.List, String) */
	public static boolean isFlagEnabled(java.util.List<String> inputArgs, String flagName) {
		return EnvironmentProbe.isFlagEnabled(inputArgs, flagName);
	}

	/** @see EnvironmentProbe#isEnhancedHotswapEnabled() */
	public static boolean isEnhancedHotswapEnabled() {
		return EnvironmentProbe.isEnhancedHotswapEnabled();
	}

	/** @see EnvironmentProbe#mustSkipForMissingEnhancedRedefinition(boolean, boolean) */
	public static boolean mustSkipForMissingEnhancedRedefinition(
	 boolean structureChanged, boolean enhanced) {
		return EnvironmentProbe.mustSkipForMissingEnhancedRedefinition(structureChanged, enhanced);
	}

	/** @see EnvironmentProbe#degradeForMissingEnhancedRedefinition(boolean) */
	public static java.util.List<String> degradeForMissingEnhancedRedefinition(boolean enhanced) {
		return EnvironmentProbe.degradeForMissingEnhancedRedefinition(enhanced);
	}

	/** @see EnvironmentProbe#degradationReason(String) */
	public static String degradationReason(String switchName) {
		return EnvironmentProbe.degradationReason(switchName);
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