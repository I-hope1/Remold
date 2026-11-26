package remold;

import remold.annotation.*;
import remold.evict.ReflectionCacheManager;
import remold.offset.LayoutPredictor;
import remold.offset.OffsetBindingIndex;
import remold.offset.OffsetScanner;
import remold.offset.SuperclassDriftGate;
import remold.profiler.DynamicProfilerAPI;
import remold.ref.HeapFieldWatch;
import remold.ref.HotswapBridge;
import remold.ref.InitFix;
import remold.ref.PendingLoadPatch;
import remold.ref.StageBInstanceCache;
import remold.vcs.AlignmentMetaStore;
import remold.vcs.Changeset;
import remold.vcs.ChangesetLog;
import remold.vcs.LoadFrontier;
import remold.vcs.GenerationalFieldLedger;
import remold.vcs.VcsCommitGate;
import remold.vcs.BytecodePack;
import remold.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.*;
import java.lang.instrument.*;
import java.lang.invoke.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.jar.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
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
 *       的字段进行数据依赖切片与纯度安全门审查，生成独立静态方法的 hidden nestmate 伴生类补丁并暂存待补计划。</li>
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
	/**
	 * {@code @RemoldReinit} 的强制重放逃生口（{@code -Dremold.agent.force_reinit=true}，默认关闭）。
	 *
	 * <p>正常语义下，封闭表达式的初值指纹未变时<b>跳过</b>重置（只改方法体的热更不该动数据）。
	 * 打开本开关后忽略指纹、本轮一律重放声明类初值切片——用于"初值表达式没改，但存量值被运行期
	 * 改脏了、需要手工打回初值"的场景。它<b>不</b>改变取值来源：值仍来自声明类（不是运行时子类）的
	 * 初值切片；按实例运行时类取值是另一条设计边界（不在当前范围，见 {@code docs/status.md}；
	 * 设计讨论见 {@code docs/initfix/03-runtime-driver.md} §4）。</p>
	 */
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

	/** 是否输出性能细分拆项日志（{@code -Dremold.agent.perf_log=true} 开启；默认 {@code false} 仅保留单行摘要）。 */
	public static boolean PERF_LOG = boolProp("remold.agent.perf_log", "remold.perfLog", false);

	/** 是否输出每个类的 {@code [DIFF]} 变更明细（默认 {@code true}；{@code -Dremold.agent.diff_log=false} 可关闭）。 */
	public static boolean DIFF_LOG = boolProp("remold.agent.diff_log", "remold.diffLog", true);

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
	static final ScopeKeyedNames INVENTED_SLOTS = new ScopeKeyedNames();

	/**
	 * {@link #INVENTED_SLOTS} 的只读 {@code Map} 视图。
	 *
	 * <p><b>为什么分成两个字段</b>：这个字段是 agent 的公开面（历史插件/探针按
	 * {@code Map<String, Set<String>>} 读它），声明类型必须保持 {@code java.util.Map} 的字节码描述符，
	 * 否则旧插件在链接期就 {@code NoSuchFieldError}。精确 scope 的读写走包内可见的
	 * {@link #INVENTED_SLOTS}，两者指向<b>同一个实例</b>，不存在两份状态。</p>
	 */
	public static final Map<String, java.util.Set<String>> inventedSlots = INVENTED_SLOTS;

	/**
	 * 本轮算出的自造名，redefine 成功后才并入 {@link #INVENTED_SLOTS}。
	 *
	 * <p>键 = <b>(loader scope, 类名)</b>：scope 号由 {@link AlignmentMetaStore#loaderScopeId} 统一分配
	 * （与 {@link AnnotationTransformer.PendingAlignedClasses}、{@link PendingLoadPatch} 同一口径）。
	 * 只按类名键会在"两个 loader 各有一个同名类"时串用——A 算出的自造名被 B 的成功回执取走，
	 * 下一轮 B 的 lambda 会按 A 的槽位名配对（{@code docs/status.md} §5 风险 15）。</p>
	 *
	 * <p>生产写入侧（对齐期）已能拿到 {@code alignLoader}，所以走精确 scope 写入
	 * （{@link ScopeKeyedNames#put(long, String, Set)}），不再靠 {@link #scopeOfLoadedClass} 猜；
	 * 成功回执（{@link #commitInvented}) 按同一 scope 取。</p>
	 */
	static final ScopeKeyedNames pendingInvented = new ScopeKeyedNames();

	/** 源码级未织入基线的 staged → commit 两级容器（风险 16：并发语义窗口）。 */
	private static final SourceBytecodeStaging SOURCE_BYTES = new SourceBytecodeStaging();

	/**
	 * 源码级未织入基线缓存（用于对齐器与源码变更分析，避免 Mixin 等增强注入的合成方法/字段污染对齐逻辑）
	 * dotClassName -> sourceBytecode
	 *
	 * <p>这里暴露的只是<b>已提交视图</b>：一次 redefine 事务从"写入候选字节"到"提交/回滚"之间，
	 * 候选字节只存在于 {@link #SOURCE_ROUND} 指向的轮次暂存里，读方（对齐器、外部重定义的
	 * no-op 判定、Mixin 兜底）永远只看到上一版已生效字节。提交时整表替换快照，因此一轮多键
	 * 写入对读方是原子的（不会看到半批提交）。</p>
	 */
	public static final Map<String, byte[]> sourceBytecodeCache = SOURCE_BYTES.committedView();

	/**
	 * 当前线程进行中的源码基线事务轮次；{@code null} 表示无事务（写入直接落已提交视图）。
	 *
	 * <p>按线程而非全局持有：轮次暂存不会跨轮、也不会串到别的事务线程；读方从不读它。</p>
	 */
	private static final ThreadLocal<SourceBytecodeStaging.Round> SOURCE_ROUND = new ThreadLocal<>();

	/**
	 * 源码级未织入基线的 staged → commit 两级容器（风险 16：<b>并发语义窗口</b>）。
	 *
	 * <p><b>为什么需要它</b>：一次 redefine 事务在"写入本轮候选源码字节"到"失败回滚 / 成功提交"之间
	 * 存在真实窗口，跨度包含匿名类对齐、织入桥、布局门与 {@code redefineClasses}。直接写全局缓存时，
	 * 窗口内的读方（外部 redefine/retransform 触发的 no-op 判定、对齐旧侧、Mixin 兜底）会读到
	 * <b>从未生效</b>的字节，从而静默跳过织入或拿错基线。</p>
	 *
	 * <p><b>职责边界</b>：</p>
	 * <ul>
	 *   <li><b>已提交视图</b>：{@link #committedView()} 是读方唯一可见的字节。读取无锁，写入在锁内
	 *       整表替换快照，因此一轮多处提交对读方表现为"要么全见旧值、要么全见新值"。</li>
	 *   <li><b>轮次暂存</b>：{@link #begin()} 产出按轮次身份存在的 {@link Round}（挂在调用线程上，
	 *       不跨轮、不跨线程串味）。事务窗口内的写入只落这里，读方永远看不到。</li>
	 *   <li>提交：{@link Round#commit} 按类判定是否生效，再<b>一次性</b>并入已提交视图并清空暂存；
	 *       回滚：{@link Round#discard()} 整份丢弃，已提交视图保持上一版，不留半写条目。</li>
	 * </ul>
	 *
	 * <p><b>不变量</b>：键仍是 dot 类名；不持 {@code Class}、不持 {@code ClassLoader}、不新增强引用；
	 * 值仍是调用方给的那份字节数组（与旧实现一致，不做防御性拷贝）。</p>
	 */
	static final class SourceBytecodeStaging {

		/** 已提交快照；读方无锁读取，写方在 {@link #writeLock} 内整表替换。 */
		private volatile Map<String, byte[]> committed = Map.of();

		private final Object writeLock = new Object();

		byte[] committedGet(String key) {
			return key == null ? null : committed.get(key);
		}

		/** 非事务写：直接进已提交视图（首次加载登记 / 外部重定义 / VCS 回退等非事务调用方）。 */
		byte[] committedPut(String key, byte[] bytes) {
			if (key == null || bytes == null) return null;
			synchronized (writeLock) {
				Map<String, byte[]> next = new HashMap<>(committed);
				byte[] prev = next.put(key, bytes);
				committed = Collections.unmodifiableMap(next);
				return prev;
			}
		}

		/** 非事务删：立即从已提交视图移除（清理时机与旧实现一致）。 */
		byte[] committedRemove(String key) {
			if (key == null) return null;
			synchronized (writeLock) {
				if (!committed.containsKey(key)) return null;
				Map<String, byte[]> next = new HashMap<>(committed);
				byte[] prev = next.remove(key);
				committed = Collections.unmodifiableMap(next);
				return prev;
			}
		}

		/** 一次性并入多条：读方在替换前后看到的都是完整快照，不会看到半批提交。 */
		void committedPutAll(Map<String, byte[]> entries) {
			if (entries == null || entries.isEmpty()) return;
			synchronized (writeLock) {
				Map<String, byte[]> next = new HashMap<>(committed);
				next.putAll(entries);
				committed = Collections.unmodifiableMap(next);
			}
		}

		/** 读方使用的已提交视图（实现 {@link Map}，但写入走的仍是快照替换，不是原地改）。 */
		Map<String, byte[]> committedView() {
			return new CommittedView();
		}

		/** 开启一轮暂存。调用方负责在本轮结束时 commit 或 discard。 */
		Round begin() {
			return new Round(this);
		}

		/**
		 * 单轮暂存容器。生命周期严格由开启它的线程掌握：
		 * {@link #stage} → {@link #commit} / {@link #discard}。
		 */
		static final class Round {
			private final SourceBytecodeStaging owner;
			private final Map<String, byte[]> staged = new LinkedHashMap<>();

			Round(SourceBytecodeStaging owner) {
				this.owner = owner;
			}

			/** 只写暂存；同一 key 重复 stage 以最后一次为准。读方看不到。 */
			void stage(String key, byte[] bytes) {
				if (key == null || bytes == null) return;
				staged.put(key, bytes);
			}

			byte[] stagedBytes(String key) {
				return key == null ? null : staged.get(key);
			}

			int stagedCount() {
				return staged.size();
			}

			boolean isEmpty() {
				return staged.isEmpty();
			}

			/** 回滚：整份丢弃，已提交视图保持上一版，不留半写条目。 */
			void discard() {
				staged.clear();
			}

			/**
			 * 提交：按类判定本轮暂存是否生效，再原子并入已提交视图。
			 *
			 * <p>判据与旧的回滚语义逐条对齐（未生效类在已提交视图里本来就还是上一版，丢弃暂存即可）：</p>
			 * <ul>
			 *   <li>{@code previous} 里没有该 key：本轮不负责它（例如事务期间由外部回调直接写的条目），保留新字节；</li>
			 *   <li>该类重定义成功（{@code applied}）：保留新字节；</li>
			 *   <li>该类本轮新注入（旧源码为空且不在 {@code loadedBefore}）：保留新字节；</li>
			 *   <li>其余未生效类：丢弃暂存条目，已提交视图不动。</li>
			 * </ul>
			 */
			void commit(Map<String, byte[]> previous, Set<String> applied, Set<String> loadedBefore) {
				Map<String, byte[]> effective = new LinkedHashMap<>();
				for (Map.Entry<String, byte[]> entry : staged.entrySet()) {
					String key = entry.getKey();
					if (!previous.containsKey(key) || applied.contains(key)) {
						effective.put(key, entry.getValue());
						continue;
					}
					byte[] prev = previous.get(key);
					if (prev == null && !loadedBefore.contains(key)) {
						effective.put(key, entry.getValue());
					}
				}
				// 先结束本轮，再并入快照：即使并入失败也不会把条目漏给下一轮。
				staged.clear();
				owner.committedPutAll(effective);
			}
		}

		/** 已提交视图：{@code get} 读当前快照；{@code put}/{@code remove} 走快照替换（非事务写）。 */
		private final class CommittedView extends AbstractMap<String, byte[]> {
			@Override
			public byte[] get(Object key) {
				return committed.get(key);
			}

			@Override
			public boolean containsKey(Object key) {
				return committed.containsKey(key);
			}

			@Override
			public int size() {
				return committed.size();
			}

			@Override
			public Set<Entry<String, byte[]>> entrySet() {
				return committed.entrySet();
			}

			@Override
			public byte[] put(String key, byte[] value) {
				return committedPut(key, value);
			}

			@Override
			public byte[] remove(Object key) {
				return committedRemove(key instanceof String ? (String) key : null);
			}

			@Override
			public void clear() {
				synchronized (writeLock) {
					committed = Map.of();
				}
			}
		}
	}


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

	static final List<remold.BytecodeTransformerBridge> transformerBridges = new CopyOnWriteArrayList<>();

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
	static final Set<String> VM_POISONED_CLASSES = ConcurrentHashMap.newKeySet();

	/** 最近一次重定义是否以 JVM 级失败告终（插件据此把用户引向重启，而不是误报 LayoutGate）。 */
	static volatile boolean LAST_REDEFINE_VM_LEVEL_FAILURE;

	/** 该类是否已被本会话的 JVM 级重定义失败拉黑。 */
	public static boolean isVmRedefinitionPoisoned(String dotClassName) {
		return dotClassName != null && VM_POISONED_CLASSES.contains(dotClassName);
	}

	/** 最近一次重定义是否以 InternalError/VerifyError 这类 JVM 级失败告终。 */
	public static boolean lastRedefineFailedAtVmLevel() {
		return LAST_REDEFINE_VM_LEVEL_FAILURE;
	}

	static boolean isVmLevelRedefineFailure(Throwable t) {
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
	static void markVmLevelFailure(String className, Throwable t) {
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
	static final List<HotswapPlugin> plugins = new CopyOnWriteArrayList<>();

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
	static final class ListenerTiming {
		String slowestName;
		long slowestMs = -1L;
	}

	static void notifyBatchListeners(List<ClassDefinition> definitions, HotswapBatchReport report) {
		notifyBatchListeners(definitions, report, null);
	}

	static void notifyBatchListeners(List<ClassDefinition> definitions, HotswapBatchReport report,
	                                         ListenerTiming timing) {
		boolean hasDefinitions = definitions != null && !definitions.isEmpty();
		boolean hasReport = report != null && report.hasObservableActivity();
		if (!hasDefinitions && !hasReport) return;
		for (HotswapBatchListener listener : batchListeners) {
			long startNanos = timing != null ? System.nanoTime() : 0L;
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
			if (timing != null) {
				long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
				if (elapsedMs > timing.slowestMs) {
					timing.slowestMs = elapsedMs;
					timing.slowestName = listener.getClass().getSimpleName();
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

	static void triggerPluginInit(HotswapPlugin plugin) {
		if (plugin == null || inst == null) return;
		synchronized (initializedPlugins) {
			if (!initializedPlugins.add(plugin)) return;
		}
		try {
			// 插件类由宿主加载器（Knot / ModLauncher）加载，它们的 unnamed module 在 premain 时通常还不存在，
			// 所以这里**再开一次**模块：此刻 TCCL / 插件自身的 module 就是宿主那一份，redefineModule 才能把它
			// 纳入目标集合。否则插件代码读 jdk.internal.* 仍会 IllegalAccessError，用户还是得写 --add-exports。
			ModuleOpener.openForAgent(inst);
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
		return dotClassName != null ? SOURCE_BYTES.committedGet(dotClassName) : null;
	}

	/**
	 * 写入源码级未织入基线缓存。
	 *
	 * <p>有进行中的事务轮次时只写轮次暂存（读方看不到，等提交才可见）；否则直接进已提交视图
	 * （首次加载登记、外部重定义回调、VCS 回退等非事务路径）。</p>
	 */
	static void recordSourceBytecode(String dotClassName, byte[] bytecode) {
		if (dotClassName == null || bytecode == null) return;
		SourceBytecodeStaging.Round round = SOURCE_ROUND.get();
		if (round != null) {
			round.stage(dotClassName, bytecode);
		} else {
			SOURCE_BYTES.committedPut(dotClassName, bytecode);
		}
	}

	/** 移除源码级未织入基线缓存条目（清理时机与旧实现一致：立即作用于已提交视图）。 */
	static void removeSourceBytecode(String dotClassName) {
		if (dotClassName != null) {
			SOURCE_BYTES.committedRemove(dotClassName);
		}
	}

	/**
	 * 开启一轮源码基线事务暂存。
	 *
	 * <p>防御：若本线程上一轮因异常未收尾，先整份丢弃，绝不让上一轮的条目串到新轮。</p>
	 */
	static SourceBytecodeStaging.Round beginSourceRound() {
		abandonSourceRound();
		SourceBytecodeStaging.Round round = SOURCE_BYTES.begin();
		SOURCE_ROUND.set(round);
		return round;
	}

	/** 当前线程未收尾的暂存轮次（仅事务自身可见；无事务返回 {@code null}）。 */
	static SourceBytecodeStaging.Round currentSourceRound() {
		return SOURCE_ROUND.get();
	}

	/** 放弃本线程未收尾的暂存轮次（异常路径防御）：整份丢弃，不留半写条目。 */
	static void abandonSourceRound() {
		SourceBytecodeStaging.Round round = SOURCE_ROUND.get();
		if (round == null) return;
		SOURCE_ROUND.remove();
		round.discard();
	}

	/** 获取运行时生效基线（织入 Mixin / 插件后的实际运行字节，供 InitFix / Schema 判断用） */
	public static byte[] getRuntimeBytecode(String dotClassName) {
		return dotClassName != null ? bytecodeCache.get(dotClassName) : null;
	}

	/** 判断运行时生效基线缓存是否已包含指定类。 */
	public static boolean hasRuntimeBytecode(String dotClassName) {
		return dotClassName != null && bytecodeCache.containsKey(dotClassName);
	}

	/** 写入运行时生效基线缓存。 */
	public static void recordRuntimeBytecode(String dotClassName, byte[] bytecode) {
		if (dotClassName != null && bytecode != null) {
			bytecodeCache.put(dotClassName, bytecode);
		}
	}

	/** 若尚未登记则写入运行时生效基线缓存。 */
	static void recordRuntimeBytecodeIfAbsent(String dotClassName, byte[] bytecode) {
		if (dotClassName != null && bytecode != null) {
			bytecodeCache.putIfAbsent(dotClassName, bytecode);
		}
	}

	/** 遍历运行时生效基线缓存的只读快照视图。 */
	static Set<Map.Entry<String, byte[]>> runtimeBytecodeEntries() {
		return Collections.unmodifiableSet(bytecodeCache.entrySet());
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
		int targetHookHits;
		int passthroughHookHits;

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
	static final LongLongMap fileDiskHashes = new LongLongMap(2048);

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
	static final Set<Path>                pendingJars    = new HashSet<>();
	/** 待通知的资源文件变动（非 .class 资源，如贴图、语言包等），防抖后统一通知；所有访问均在 synchronized(pendingChanges) 内 */
	static final Set<Path>                pendingAssetChanges = new HashSet<>();
	static final ScheduledExecutorService scheduler      = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "HotSwap-Scheduler");
		t.setDaemon(true);
		return t;
	});
	static       ScheduledFuture<?>       scheduledTask;
	static final DebounceWindow           DEBOUNCE = new DebounceWindow(FILE_SHAKE_MS, ASSET_SHAKE_MS);

	//region Build-trigger marker state

	/**
	 * 构建完成 marker 文件；{@code null} = 未配置（只走 {@link #FILE_SHAKE_MS} 兜底）。
	 *
	 * <p>配置来源：{@code -Dremold.agent.build_trigger_file=<abs>}（别名 {@code remold.buildTriggerFile}）。
	 * 契约见 {@code docs/runtime/01-build-trigger-mode.md}。</p>
	 */
	static volatile Path BUILD_TRIGGER_FILE;

	/** 首个 marker 的扫描下界余量（毫秒）：吸收文件系统 mtime 精度与挂钟边界；后续 marker 不使用。 */
	static final long BUILD_TRIGGER_LOOKBACK_MILLIS = 2000L;

	/** 扫描条目数告警阈值（只告警、不截断）。 */
	static final int BUILD_TRIGGER_SWEEP_WARN = 5000;

	/** agent 启动挂钟毫秒：首个 marker 的扫描下界与陈旧判定基准。 */
	static final long AGENT_START_MILLIS = System.currentTimeMillis();

	/** 最近一次被接受的 marker 的结束挂钟毫秒；{@code 0} = 还没有。 */
	static volatile long lastProcessedBuildEndMillis;

	/** 最近一次被接受的 marker 的 build_id（去重键）。 */
	static volatile String lastProcessedBuildId;

	//endregion

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
	static final ReentrantLock HOTSWAP_LOCK = new ReentrantLock();

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
	static volatile StartupGate STARTUP_GATE =
	 new StartupGate(StartupGate.resolveTimeoutMillis(System.getProperty(StartupGate.PROP_READY_TIMEOUT_MS)));

	/**
	 * 批次卡死阈值（毫秒，{@code -Dremold.agent.batch_stall_ms}，默认 15000，下限 5000）。
	 * 由 {@link BatchWatchdog} 在批次开始时读取。
	 */
	public static volatile long BATCH_STALL_MS =
	 BatchWatchdog.resolveStallMillis(System.getProperty(BatchWatchdog.PROP_STALL_MS));

	/** 门闩未就绪时的重查间隔下限：绝不能按"防抖已到期"的 0 延迟自旋。 */
	static final long GATE_RETRY_MIN_MS = 100L;

	/** 门闩阻断日志去重（同一批变更只打一条，避免按重查节奏刷屏）。 */
	static volatile boolean gateBlockLogged;
	/** 就绪状态变迁日志去重（每次 PENDING → READY 只打一条）。 */
	static volatile boolean gateReadyLogged;

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
	static final Set<ClassFileTransformer> TRAILING_OBSERVERS =
		java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

	/** {@code initTransformer} 已经把登记在册的观测器全部挂上 JVM 之后置 true。 */
	static volatile boolean TRAILING_OBSERVERS_ARMED;

	private static final java.util.concurrent.atomic.AtomicBoolean BOOTSTRAP_APPENDED =
		new java.util.concurrent.atomic.AtomicBoolean();

	static void appendSelfToBootstrapSearch(Instrumentation inst) {
		if (inst == null || RemoldAgent.class.getClassLoader() == null
		    || !BOOTSTRAP_APPENDED.compareAndSet(false, true)) {
			return;
		}
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
		// 第一件事：用 Instrumentation.redefineModule 把 jdk.internal.* 对 ALL-UNNAMED 打开/导出，
		// 之后任何 Remold 类再去碰这些包都不会抛 IllegalAccessError / InaccessibleObjectException，
		// 用户也就不必再传 --add-exports / --add-opens（见 core/src/remold/ModuleOpener.java）。
		ModuleOpener.openForAgent(inst);
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
		ModuleOpener.openForAgent(inst);
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
	 * <b>仅供单测</b>：读取内部防抖状态机，用于确定性验证 sticky due 与"marker 取消兜底"。
	 * 生产代码请通过 {@link DebounceWindow} 的公开方法间接使用。
	 */
	static DebounceWindow debounceForTest() {
		return DEBOUNCE;
	}

	/**
	 * <b>仅供单测</b>：复位 build-trigger 相关静态状态（marker 路径、去重键、三条队列、防抖状态与已挂调度），
	 * 避免用例之间串扰。不会停止普通 class watcher。
	 */
	static void resetBuildTriggerForTest() {
		BUILD_TRIGGER_FILE = null;
		lastProcessedBuildEndMillis = 0L;
		lastProcessedBuildId = null;
		FileWatcherService.stopBuildTriggerWatcher();
		synchronized (pendingChanges) {
			pendingChanges.clear();
			pendingJars.clear();
			pendingAssetChanges.clear();
			if (scheduledTask != null && !scheduledTask.isDone()) {
				scheduledTask.cancel(false);
			}
			scheduledTask = null;
		}
		DEBOUNCE.clearAll();
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
		RemoldAgentBootstrap.init(agentArgs, reinit);
	}

	public static void init(String source, String agentArgs, boolean reinit) {
		RemoldAgentBootstrap.init(source, agentArgs, reinit);
	}

	public static void setWatchPaths(String source, String paths) {
		RemoldAgentBootstrap.setWatchPaths(source, paths);
	}

	public static void setWatchPaths(String source, Collection<Path> dirs, Collection<Path> jars) {
		RemoldAgentBootstrap.setWatchPaths(source, dirs, jars);
	}

	public static void addWatchPaths(String source, String paths) {
		RemoldAgentBootstrap.addWatchPaths(source, paths);
	}

	public static void removeWatchSource(String source) {
		RemoldAgentBootstrap.removeWatchSource(source);
	}

	public static Set<Path> getWatchDirs(String source) {
		return RemoldAgentBootstrap.getWatchDirs(source);
	}

	public static Set<Path> getWatchJars(String source) {
		return RemoldAgentBootstrap.getWatchJars(source);
	}

	public static Map<String, Set<Path>> getAllWatchDirSources() {
		return RemoldAgentBootstrap.getAllWatchDirSources();
	}

	public static Map<String, Set<Path>> getAllWatchJarSources() {
		return RemoldAgentBootstrap.getAllWatchJarSources();
	}

	private static void initTransformer() {
		RemoldAgentBootstrap.initTransformer();
	}

	public static void addTrailingLoadObserver(ClassFileTransformer observer) {
		RemoldAgentBootstrap.addTrailingLoadObserver(observer);
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
			if (!RemoldAgentBootstrap.isProjectLocation(protectionDomain)) return null;
			bytecodeCache.putIfAbsent(className.replace('/', '.'), classfileBuffer);
			// 出生事件：只记标量（scope 号 + 内部名）并推进加载前沿水位线，不在这里算摘要、不解析字节码
			// （规格 docs/vcs/07 L2）。字节哈希留到 flush 时按需补，避免把成本压到加载路径上。
			// 只对工作区类记录：外部库/JDK 不在"写点证明"的闭包内，记进来只会让队列被噪声撑爆。
			if (LOAD_HOOKS) {
				LoadFrontier.birth(AlignmentMetaStore.INSTANCE.loaderScopeId(loader), className, "");
			}
			return null;
		}
	}

	/**
	 * 出生事件与待决出生补丁是否生效。
	 *
	 * <p>默认开启；{@code -Dremold.probe.load_hooks=false} 只用于"开/关对照"的测量，
	 * 关掉之后出生水位线不再推进、待决出生补丁不再应用，行为退回到引入加载前沿之前。
	 * 它<b>不是</b>给用户用的降级开关：关掉意味着规格 {@code docs/vcs/07} 的闭包不再完整。</p>
	 */
	public static final boolean LOAD_HOOKS = !"false".equalsIgnoreCase(
	 System.getProperty("remold.probe.load_hooks"));

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
	static boolean boolProp(String primary, String alias, boolean def) {
		String v = System.getProperty(primary);
		if (v == null && alias != null) v = System.getProperty(alias);
		return v == null ? def : Boolean.parseBoolean(v.trim());
	}

	static int intProp(String primary, String alias, int def) {
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

	static int intPropAsset(String primary, String alias, int def) {
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
		RemoldAgentBootstrap.initConfig();
	}

	static void bootstrapPlugins(String agentArgs) {
		RemoldAgentBootstrap.bootstrapPlugins(agentArgs);
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
	static void retransformLoaded(Class<?>[] classes) {
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
	/**
	 * 已加载类快照（实现是 {@link LoadedClassIndex}：按 {@code (loader scope, 类名)} 分键）。
	 *
	 * <p><b>声明类型是二进制兼容契约</b>：这个字段历史上是
	 * {@code ConcurrentHashMap<String, Class<?>>}，已发布的插件 jar（{@code plugin-fabric} 的就绪探测、
	 * {@code plugin-mc-toast} 的宿主类定位）按旧字段描述符
	 * {@code Ljava/util/concurrent/ConcurrentHashMap;} 链接它——声明类型一变就是链接期
	 * {@code NoSuchFieldError}（{@code loadClassSnap} 的原子切换也是整体替换引用，读者语义不变）。
	 * 因此这里保持旧声明类型、装入 {@link LoadedClassIndex} 实例：描述符不变、行为升级。
	 * 需要精确 scope 的调用方用 {@link #loadedClassIndex()}。</p>
	 */
	public static volatile ConcurrentHashMap<String, Class<?>> loadedClassesMap = new LoadedClassIndex();

	/**
	 * {@link #loadedClassesMap} 的类型化读取口（精确 scope 查 {@link LoadedClassIndex#get(long, String)}）。
	 *
	 * <p>字段的声明类型为了二进制兼容保持在 {@code ConcurrentHashMap}；生产路径上它<b>总是</b>
	 * {@link LoadedClassIndex}。万一有人塞进一张普通 {@code ConcurrentHashMap}，这里按每份副本自己的
	 * loader 重建索引（scope 由 loader 推导，不是猜），避免调用方拿到 {@code null} 而静默漏扫。</p>
	 */
	public static LoadedClassIndex loadedClassIndex() {
		ConcurrentHashMap<String, Class<?>> current = loadedClassesMap;
		return current instanceof LoadedClassIndex index ? index : new LoadedClassIndex(current);
	}

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

	static void loadClassSnap(Class<?>[] classes) {
		LoadedClassIndex index = new LoadedClassIndex();
		// loader → scope 只解析一次：AlignmentMetaStore 的 scope 分配是加锁的，逐类调用会在
		// 万级已加载类上反复抢同一把锁。
		Map<ClassLoader, Long> scopes = new IdentityHashMap<>();
		for (Class<?> c : classes) {
			if (c == null) continue;
			String name = c.getName();
			if (isNonLoadableClass(name)) {
				// 模板类若被历史版本误注入过，把它排除出已加载快照：
				// 既不重定义，也不让读者扫描把它当外部读者。
				continue;
			}
			ClassLoader loader = c.getClassLoader();
			Long scope = scopes.get(loader);
			if (scope == null) {
				scope = AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
				scopes.put(loader, scope);
			}
			// 按 (scope, 类名) 登记：同名类在另一个 loader 下的副本不再被覆盖/顶掉。
			index.put(scope, name, c);
		}
		loadedClassesMap = index;               // 原子切换，读者要么看到完整旧快照，要么完整新快照
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
	static void processChanges(Set<Path> changedFiles, Class<?>[] classes) {
		BatchWatchdog.enter(BATCH_STALL_MS, "class-batch(" + changedFiles.size() + " file(s))");
		try {
			processChangesBatch(changedFiles, classes);
		} finally {
			// 旁路暂存（PR1）必须在这里兜底清空：批次中途抛 Throwable 时，提交点可能压根没跑到，
			// 残余条目会带着上一批的对齐输入污染下一批。
			InitFix.endBatchBytecodeView();
			VcsAgentCoordinator.PENDING_VCS.clear();
			BatchWatchdog.exit();
		}
	}

	/** 实例判定缓存：点分类名 → 是否存活实例。见 {@link #hasLiveInstances}。 */
	static final Map<String, Boolean> LIVE_INSTANCE_CACHE       = new ConcurrentHashMap<>();
	/** 布局门批次级存活探测的类名集合；空集合表示没有批次上下文。 */
	static volatile Set<String> LIVENESS_BATCH = Set.of();
	/** 本轮的批次级存活预探测是否已完成（含"不需要做"）。 */
	static final AtomicBoolean LIVENESS_BATCH_DONE = new AtomicBoolean(true);
	/** 累计的实例扫描耗时（毫秒），用于观察全堆遍历开销。 */
	static final AtomicLong LIVE_INSTANCE_SCAN_MILLIS = new AtomicLong();
	/** 本批次 {@code inst.redefineClasses} 累计耗时（毫秒）。 */
	static final AtomicLong BATCH_REDEFINE_MILLIS = new AtomicLong();
	/** 本批次 {@link InitFix#afterRedefine}（两阶段 Stage B + 1-phase 后处理）累计耗时（毫秒）。 */
	static final AtomicLong BATCH_STAGE_B_MILLIS = new AtomicLong();
	static final AtomicLong BATCH_STAGE_A_MILLIS = new AtomicLong();
	static final AtomicLong BATCH_STAGE_C_MILLIS = new AtomicLong();
	static final AtomicLong BATCH_TWOPHASE_SKEL_MILLIS = new AtomicLong();

	private static void processChangesBatch(Set<Path> changedFiles, Class<?>[] classes) {
		BatchHotswapExecutor.processChangesBatch(changedFiles, classes);
	}

	public static boolean reportMemberMetadataDrift(Map<String, byte[]> newBytes, Map<String, byte[]> oldBytes) {
		return BatchHotswapExecutor.reportMemberMetadataDrift(newBytes, oldBytes);
	}

	static boolean hasLiveInstances(Class<?> c) {
		return BatchHotswapExecutor.hasLiveInstances(c);
	}

	static boolean hasLiveInstances(String dotClassName) {
		return BatchHotswapExecutor.hasLiveInstances(dotClassName);
	}

	static void resetLiveInstanceCache() {
		BatchHotswapExecutor.resetLiveInstanceCache();
	}

	static void setLivenessBatch(Collection<String> classNames) {
		BatchHotswapExecutor.setLivenessBatch(classNames);
	}

	static void processAnnotations(List<ClassDefinition> definitions) {
		BatchHotswapExecutor.processAnnotations(definitions);
	}

	static void processAnnotationsInternal(Class<?> clazz) {
		BatchHotswapExecutor.processAnnotationsInternal(clazz);
	}

	static String resolveAnonHostCandidate(String cName, byte[] bytes,
	                                      Set<String> hostClassesToAlign, String singleHost) {
		return BatchHotswapExecutor.resolveAnonHostCandidate(cName, bytes, hostClassesToAlign, singleHost);
	}

	/** 无字节码的重载：只保留给"名字足够"的调用点（单宿主模式与纯 Java 形态）。 */
	static String resolveAnonHostCandidate(String cName, Set<String> hostClassesToAlign, String singleHost) {
		return BatchHotswapExecutor.resolveAnonHostCandidate(cName, null, hostClassesToAlign, singleHost);
	}

	static boolean isBlacklisted(String className) {
		return BatchHotswapExecutor.isBlacklisted(className);
	}

	public static byte[] fetchBytecodeMemory(Class<?> clazz) throws UnmodifiableClassException {
		return BatchHotswapExecutor.fetchBytecodeMemory(clazz);
	}

	public static byte[] fetchCurrentBytecode(Class<?> clazz) {
		return BatchHotswapExecutor.fetchCurrentBytecode(clazz);
	}

	public static byte[] fetchOriginalBytecode(Class<?> clazz) {
		return BatchHotswapExecutor.fetchOriginalBytecode(clazz);
	}
	//endregion

	//region Class Injection and Redefinition
	/**
	 * 直接定义类，而不是被动加载。
	 *
	 * <p>基线顺序：bytecodeCache → fetchOriginalBytecode → sourceBytecodeCache；
	 * 三者都没有时 warn 并拒绝，不能跳过 LayoutGate/InitFix 静默重定义。</p>
	 */
	static boolean injectNewClass(String className, Path path, byte[] bytes) {
		return injectNewClass(className, path, bytes, null);
	}

	/**
	 * 同 {@link #injectNewClass(String, Path, byte[])}，但允许调用方指定**加载器提示**。
	 *
	 * <p>对齐器重编号/新合成的匿名类在磁盘上没有同名文件，按路径推断加载器会退回"按包名猜"，
	 * 结果是 defineClass 装进了错误的加载器：定义本身成功（没有任何告警），但宿主所在加载器看不见它，
	 * 构造宿主时照样 NoClassDefFoundError。这类类的正确加载器就是它宿主的加载器。</p>
	 *
	 * @param loaderHint 目标加载器；为 null 时按原有路径推断逻辑
	 */
	static boolean injectNewClass(String className, Path path, byte[] bytes, ClassLoader loaderHint) {
		if (isNonLoadableClass(className)) {
			// Mixin 模板类不是可加载类：defineClass 会污染宿主 ClassLoader，
			// 并让读者扫描把它误认为外部读者。
			info("[SKIP-INJECT] " + className + " is registered as a non-loadable template; defineClass skipped");
			return false;
		}
		try {
			ClassLoader loader = loaderHint != null ? loaderHint : findTargetClassLoader(className, path);
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
		/**
		 * 本事务 pending 登记用的 loader scope。
		 *
		 * <p>构造时按宿主类解析：宿主已加载就取它的 {@code ClassLoader} 身份，由
		 * {@link AlignmentMetaStore#loaderScopeId} 分配 scope 号（与 {@code PendingLoadPatch} /
		 * {@code InitFix} / {@code LoadFrontier} 同一口径）；宿主此刻还没加载则退回
		 * {@link PendingLoadPatch#ANY_SCOPE}，出生时"先查精确 scope、再退回哨兵"。</p>
		 *
		 * <p>只在构造时解析一次：preRegister / rollback / commit 必须落在同一个 scope 上，
		 * 否则"登记进去、回滚删不掉"。</p>
		 */
		public final long                pendingScope;

		public AlignmentTransaction(String hostName) {
			this.hostName = hostName;
			this.pendingScope = scopeOfLoadedClass(hostName);
		}

		public void preRegister() {
			if (preRegistered || committed) return;
			preRegistered = true;
			for (Map.Entry<String, byte[]> entry : pendingAdds.entrySet()) {
				if (!isNameableTarget(entry.getKey(), "pendingAdds")) continue;
				String targetSlash = entry.getKey().replace('.', '/');
				String targetDot   = entry.getKey().replace('/', '.');
				byte[] bytes       = entry.getValue();
				AnnotationTransformer.PENDING_ALIGNED.put(pendingScope, targetSlash, bytes);
				AnnotationTransformer.PENDING_ALIGNED.put(pendingScope, targetDot, bytes);
			}
		}

		public void commit() {
			if (committed) return;
			preRegister();
			committed = true;
			for (Map.Entry<String, byte[]> entry : cacheUpdates.entrySet()) {
				if (!isNameableTarget(entry.getKey(), "cacheUpdates")) continue;
				bytecodeCache.put(entry.getKey().replace('/', '.'), entry.getValue());
			}
		}

		/**
		 * 事务只登记**有名字**的类。
		 *
		 * <p>{@code bytecodeCache} 是 {@link java.util.concurrent.ConcurrentHashMap}：空键会抛
		 * {@code NullPointerException: Cannot invoke "Object.hashCode()" because "key" is null}，
		 * 而这发生在一轮热更的提交阶段，会把整批的收尾打断。真正的问题是上游给了没有目标类名的条目
		 * （对齐器无法为某个新匿名类决定落地槽位时就会出现），这里按 fail-closed 只跳过该条并点名，
		 * 不把空名塞进缓存。</p>
		 */
		private boolean isNameableTarget(String name, String source) {
			if (name != null && !name.isEmpty()) return true;
			warn("[HOTSWAP-REJECT] Anonymous class alignment produced an entry without a target class name"
			     + " in " + source + " for host " + hostName + "; the entry is skipped"
			     + " (the previous class definition keeps running). Recompile without moving anonymous"
			     + " classes, or restart to adopt the new layout.");
			return false;
		}

		public void rollback(boolean pinOldToPending) {
			if (committed) return;
			for (String key : pendingAdds.keySet()) {
				if (key == null || key.isEmpty()) continue;
				AnnotationTransformer.PENDING_ALIGNED.remove(pendingScope, key.replace('.', '/'));
				AnnotationTransformer.PENDING_ALIGNED.remove(pendingScope, key.replace('/', '.'));
			}
			if (pinOldToPending) {
				// 将被磁盘新文件覆盖但重定义未生效的旧版本字节码钉在 pending 中
				for (Map.Entry<String, byte[]> entry : pinOldBytes.entrySet()) {
					if (entry.getValue() != null && entry.getKey() != null && !entry.getKey().isEmpty()) {
						String slash = entry.getKey().replace('.', '/');
						String dot   = entry.getKey().replace('/', '.');
						AnnotationTransformer.PENDING_ALIGNED.put(pendingScope, slash, entry.getValue());
						AnnotationTransformer.PENDING_ALIGNED.put(pendingScope, dot, entry.getValue());
					}
				}
			}
		}
	}

	/**
	 * 已加载类的 loader scope；类尚未加载（或查询失败）时退回 {@link PendingLoadPatch#ANY_SCOPE}。
	 *
	 * <p>scope 号统一由 {@link AlignmentMetaStore#loaderScopeId} 分配，这里只负责"类名 → 当前定义
	 * loader"这一段，不自己造身份。</p>
	 *
	 * <p><b>降级语义</b>：这里拿不到 loader，只能走 {@link LoadedClassIndex#get(Object)} 的"仅类名"
	 * 查询——同名类在两个 loader 下各有一份且无唯一权威副本时，它返回 {@code null}（并 warn），
	 * 于是本次解析退回 {@link PendingLoadPatch#ANY_SCOPE}。哨兵的含义是"出生时先查精确 scope、再退回
	 * 它"，方向保守，<b>不会</b>把 A 的 loader 冒充成 B 的。拿得到 loader 的调用方应当直接用
	 * {@link LoadedClassIndex#get(long, String)}。</p>
	 */
	static long scopeOfLoadedClass(String dotClassName) {
		try {
			Class<?> loaded = dotClassName == null ? null : loadedClassesMap.get(dotClassName);
			if (loaded != null) return AlignmentMetaStore.INSTANCE.loaderScopeId(loaded.getClassLoader());
		} catch (Throwable ignored) {
			// 解析失败按"加载器未知"处理：方向保守，绝不冒充某个 scope。
		}
		return PendingLoadPatch.ANY_SCOPE;
	}

	static void rejectHostGroup(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                            String hostName, RoundSkips skips, Throwable t) {
		BatchLayoutCoordinator.rejectHostGroup(newBatchBytes, classToPath, hostName, skips, t);
	}

	static List<String> dropHostGroup(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                  String hostName) {
		return BatchLayoutCoordinator.dropHostGroup(newBatchBytes, classToPath, hostName);
	}

	static void dropEnumSwitchDependents(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                     String enumName, RoundSkips skips) {
		BatchLayoutCoordinator.dropEnumSwitchDependents(newBatchBytes, classToPath, enumName, skips);
	}

	static void dropEnumConstantDependents(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                       String enumName, List<String> addedConstants, RoundSkips skips) {
		BatchLayoutCoordinator.dropEnumConstantDependents(newBatchBytes, classToPath, enumName, addedConstants, skips);
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

		/**
		 * 本轮被 {@link RedefineEntryGate}（class 文件合法性门禁）拒掉的类名。
		 *
		 * <p><b>为什么单独留一份</b>：判决文本是给人读的，程序判据不能靠解字符串。而"被拒类对应的
		 * {@link AlignmentTransaction} 不许提交"这条规则必须有<b>机器可读</b>的输入，否则
		 * {@code TwoPhaseCoordinator} 只能看到过滤后的 {@code definitions}、看不到"谁被拒了"，
		 * 于是把被拒类的事务一起 commit —— 未生效字节就此变成基线（{@code docs/status.md} §5 风险 15/16）。</p>
		 */
		private final Set<String> bytecodeRejected = new LinkedHashSet<>();

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

		/** 记一条"被 class 文件合法性门禁拒掉"的结构化事实（见 {@link #bytecodeRejected}）。 */
		void markBytecodeRejected(String className) {
			if (className != null && !className.isEmpty()) bytecodeRejected.add(className);
		}

		/**
		 * 本轮被合法性门禁拒掉的类名（只读快照）。
		 *
		 * <p>消费方只有一处：{@link RedefineEntryGate#withoutRejectedGroups} 据此把"含被拒类的事务组"
		 * 整组移出提交范围。<b>不得</b>把它当成通用拒绝清单使用——其它门的拒绝语义不同（有的要回队、
		 * 有的要重启），混用会改变那些路径的行为。</p>
		 */
		Set<String> bytecodeRejected() { return Set.copyOf(bytecodeRejected); }

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

	static void applyLocalClassGuard(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                 Map<String, AlignmentTransaction> transactions, RoundSkips skips) {
		BatchLayoutCoordinator.applyLocalClassGuard(newBatchBytes, classToPath, transactions, skips);
	}

	static LocalClassGuard.Decision decideLocalClassGuard(List<ClassNode> oldClasses, List<ClassNode> newClasses,
	                                                    String mode, Set<String> batchNames) {
		return LocalClassGuard.decide(oldClasses, newClasses, mode, batchNames);
	}

	static AnonClassAligner.Result alignAnonClasses(String hostSlash, byte[] hostBytes,
	                                                Map<String, byte[]> oldAnon, Map<String, byte[]> newAnon,
	                                                Function<String, byte[]> oldRes, Function<String, byte[]> newRes,
	                                                Predicate<String> hasLive, Set<String> reserved) {
		return AnonClassAligner.align(hostSlash, hostBytes, oldAnon, newAnon, oldRes, newRes, hasLive, reserved);
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

	public static OffsetBindingIndex getOffsetIndex() {
		return BatchLayoutCoordinator.getOffsetIndex();
	}

	public static void registerOffsetBinding(OffsetScanner.Binding binding) {
		BatchLayoutCoordinator.registerOffsetBinding(binding);
	}

	public static void clearOffsetIndex() {
		BatchLayoutCoordinator.clearOffsetIndex();
	}

	static void scanLoadedClassForOffsets(byte[] classfileBuffer) {
		BatchLayoutCoordinator.scanLoadedClassForOffsets(classfileBuffer);
	}

	static SuperclassDriftGate.DriftDecision checkSuperclassDriftIfApplicable(
	 String className, Class<?> targetClass, List<String> changedFields,
	 byte[] newBytes, Map<String, byte[]> newBatchBytes) {
		return BatchLayoutCoordinator.checkSuperclassDriftIfApplicable(
		 className, targetClass, changedFields, newBytes, newBatchBytes);
	}


	/** 布局门与后续 alignDiff 共用的同字节数组 diff 缓存，避免对同一个类重复解析两次。 */
	record GateCachedDiff(byte[] oldBytes, byte[] newBytes, ClassDiffUtil.ClassDiff diff,
	                      boolean bodyDiffComputed, boolean fullNodesLoaded) {}

	private static void applyRedefineLayoutGate(Map<String, byte[]> newBatchBytes,
	                                            Map<String, Path> classToPath,
	                                            Map<String, AlignmentTransaction> transactions,
	                                            RoundSkips skips) {
		BatchLayoutCoordinator.applyRedefineLayoutGate(newBatchBytes, classToPath, transactions, skips);
	}

	static void applyRedefineLayoutGate(Map<String, byte[]> newBatchBytes,
	                                    Map<String, Path> classToPath,
	                                    Map<String, AlignmentTransaction> transactions,
	                                    RoundSkips skips,
	                                    Map<String, GateCachedDiff> diffCache,
	                                    long[] readersMsOut) {
		BatchLayoutCoordinator.applyRedefineLayoutGate(newBatchBytes, classToPath, transactions, skips, diffCache, readersMsOut);
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

	/** 懒求值版布局决策入口：仅当决策分支确实需要存活实例判定时才触发 {@code livenessSupplier}。 */
	public static LayoutDecision decideLayout(String className, List<String> changedFields,
	                                          String mode, Supplier<Liveness> livenessSupplier, Set<String> batchNames,
	                                          RemovedMemberScan scan,
	                                          SuperclassDriftGate.DriftDecision drift,
	                                          Set<String> reinitFields,
	                                          Map<String, String> unextractableReinitFields) {
		return LayoutDecisionEngine.decide(className, changedFields, mode, livenessSupplier, batchNames, scan, drift, reinitFields, unextractableReinitFields);
	}

	/** @see LayoutDecisionEngine#skipVerdictForLayout(LayoutDecision, RemovedMemberScan) */
	public static String skipVerdictForLayout(LayoutDecision d, RemovedMemberScan scan) {
		return LayoutDecisionEngine.skipVerdictForLayout(d, scan);
	}

	/**
	 * redefine 成功后，把本轮算出的"自造槽位名"并入 {@link #INVENTED_SLOTS}。
	 *
	 * <p>只在成功路径调用：失败/回滚时旧基线（{@link #bytecodeCache}）没有前进，
	 * 自造名集合也必须保持与旧基线一致，否则下一轮会拿"从未加载过的版本"的身份去做配对。</p>
	 *
	 * <p><b>回执必须按同一 scope 取/写</b>：写侧（对齐期）用本类的定义 loader 的 scope 写入，
	 * 回执侧必须用同一个 scope 消费与落账。按类名落账会让 A 的成功回执改掉 B 的自造名集合
	 * （{@code docs/status.md} §5 风险 15）。</p>
	 */
	static void commitInvented(long scope, String className) {
		java.util.Set<String> pending = pendingInvented.take(scope, className);
		if (pending == null || pending.isEmpty()) {
			// 精确 scope 删除：这里**不能**退化成"按名字删所有 scope"，
			// 那会把另一个 loader 名下同名类的自造名一起抹掉。
			INVENTED_SLOTS.remove(scope, className);
		} else {
			INVENTED_SLOTS.put(scope, className, pending);
		}
	}

	/** 类定义 loader 的 scope（{@link AlignmentMetaStore#loaderScopeId} 是唯一分配口径）。 */
	static long loaderScope(ClassLoader loader) {
		return AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
	}

	/**
	 * 收尾一轮源码基线事务：把缓存回滚到"上一版 + 本轮生效类"的状态。
	 *
	 * <p>有进行中的轮次时（生产路径）：按类判定本轮暂存是否生效，<b>一次性</b>原子并入已提交视图，
	 * 其余条目整份丢弃（未生效类在已提交视图里本来就还是上一版，回滚即"丢弃暂存"）。判据与旧语义
	 * 逐条对齐：成功重定义的类保留新源码；本轮新注入的类（没有旧源码、也不在 {@code loadedBefore}）
	 * 保留新源码；其余未生效类回到上一版。</p>
	 *
	 * <p>没有进行中的轮次时（直接写已提交视图的调用方 / 单测），退化为对已提交视图做同样的按类恢复。</p>
	 */
	static void rollbackSourceCache(Map<String, byte[]> sourceBefore, Set<String> applied,
	                                Set<String> loadedBefore) {
		SourceBytecodeStaging.Round round = SOURCE_ROUND.get();
		if (round != null) {
			try {
				round.commit(sourceBefore, applied, loadedBefore);
			} finally {
				SOURCE_ROUND.remove();
			}
			return;
		}
		for (String key : sourceBefore.keySet()) {
			if (applied.contains(key)) continue;
			byte[] prev = sourceBefore.get(key);
			// 新注入类（没有旧源码、也不在 loadedBefore）保持新源码；其余未成功类回滚。
			if (prev == null && !loadedBefore.contains(key)) continue;
			if (prev == null) SOURCE_BYTES.committedRemove(key);
			else SOURCE_BYTES.committedPut(key, prev);
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
	static void commitVcsBatch(List<ClassDefinition> definitions, Set<String> redefinedOk,
	                                   Set<String> rolledBack, boolean lambdaStrictRejected,
	                                   boolean twoPhaseApplied) {
		VcsAgentCoordinator.commitVcsBatch(definitions, redefinedOk, rolledBack, lambdaStrictRejected, twoPhaseApplied);
	}

	static void recordLedgerFacts(ClassLoader loader, Changeset cs) {
		VcsAgentCoordinator.recordLedgerFacts(loader, cs);
	}

	public static String revertTo(long targetSeq) {
		return VcsAgentCoordinator.revertTo(targetSeq);
	}

	//region Redefine 入口：class 文件合法性门禁（风险 13）

	/**
	 * 当前 JVM 能加载的最高 class file major。
	 *
	 * <p>{@code major = feature + 44} 是 JVMS 的固定换算（JDK 8 → 52、11 → 55、17 → 61、21 → 65），
	 * 因此这里按运行期 {@link Runtime#version()} 推导而不是钉死一个数：钉死会让未来 JDK 上的合法
	 * 产物被误拒，也会让"未知 major 显式拒绝"退化成"按某个历史版本卡"。推导值在类初始化时取一次，
	 * 热更不会改变运行期 JVM，重复取没有意义。</p>
	 */
	static final int MAX_CLASS_MAJOR = Runtime.version().feature() + 44;

	/**
	 * 廉价硬拦截：判断一份字节能否安全送进 {@code Instrumentation.redefineClasses}。
	 *
	 * <p><b>为什么必须在 Java 层判</b>：JBR 25 + {@code AllowEnhancedClassRedefinition} 收到畸形
	 * {@code ClassDefinition} 不是抛 {@code LinkageError}，而是在
	 * {@code VM_EnhancedRedefineClasses::do_topological_class_sorting} 触发
	 * {@code EXCEPTION_ACCESS_VIOLATION} 直接终止进程（{@code docs/status.md} §5 风险 13）——
	 * 没有异常可捕获、没有回滚可做，所以只能"进 JVM 之前"判。</p>
	 *
	 * <p><b>五项检查，全部 fail-closed</b>：</p>
	 * <ol>
	 *   <li>魔数 {@code 0xCAFEBABE}；</li>
	 *   <li><b>廉价前置：常量池逐项走一遍</b>（tag → 长度 → 边界），任何越界/未知 tag/非法索引都拒绝。
	 *       刻意<b>不</b>用"长度 ≥ N"这种代理判据：截断点可以落在任何一项中间，只有真正走完常量池
	 *       才能证明后续字段可读；</li>
	 *   <li>{@code major} 不超过 {@link #MAX_CLASS_MAJOR}（未知/更新的 class 版本显式拒绝，
	 *       不让 JVM 去猜）；</li>
	 *   <li>{@code this_class} 解析出的内部名与目标类的内部名一致——二进制名不匹配会让增强重定义
	 *       的拓扑排序读到错位的元数据。比较按<b>字节</b>做（JVMS 4.4.7 的 modified UTF-8 语义），
	 *       不走标准 UTF-8 解码：见 {@link #walkClassFile}；</li>
	 *   <li><b>结构完整性：ASM 真解析一遍</b>（{@link #asmStructureProblem}）。前四项只看头部与常量池，
	 *       看不到 {@code super_class} 之后的字段/方法/属性与尾部，截断在那些位置仍然"合法"。</li>
	 * </ol>
	 *
	 * <p><b>两道结构检查的分工</b>：廉价前置检查先跑，语义是"<b>不依赖任何第三方解析器</b>也能拒掉
	 * 头部/常量池级别的畸形"——ASM 对某些畸形输入的行为不作保证（可能抛任意 {@code RuntimeException}，
	 * 也可能在超大 {@code poolCount} 上先分配大数组），所以它不能当第一道；ASM 解析跑在它之后，
	 * 负责"常量池之后被截断/损坏"的那部分结构，因为重写一份覆盖字段/方法/属性的走查器等于把 ASM 的
	 * 工作量再做一遍，且更容易漏项。</p>
	 *
	 * <p><b>两道 {@code major} 判据都要保留</b>：本类的 {@link #MAX_CLASS_MAJOR} 按运行期 JVM 推导，
	 * 而 {@link ClassReader} 自带一份"打包进来的 ASM 认识哪些版本"的口径，两者不一定相等。前者先判，
	 * 后者遇到它不认识的版本会抛 {@code IllegalArgumentException("Unsupported class file version")}，
	 * 这里同样收敛成拒绝并显式点名原因（fail-closed：ASM 解析不了就无法证明结构完整）。</p>
	 *
	 * <p>本方法<b>只读</b>传入数组，不复制、不重读磁盘：调用方必须把校验过的这同一份引用交给
	 * {@code redefineClasses}（{@link ClassDefinition#getDefinitionClassFile()}），否则"校验"与
	 * "真正送进 JVM"之间会出现新的 TOCTOU 窗口。</p>
	 *
	 * @return {@code null} 表示可以安全进入 redefine；否则是英文拒绝原因（直接进 {@code warn}）
	 */
	static String definitionProblem(Class<?> type, byte[] bytes) {
		if (type == null) return "target class is null";
		if (bytes == null) return "class file bytes are null";
		if (bytes.length < 8) return "class file is shorter than the 8-byte header (" + bytes.length + " bytes)";
		if ((bytes[0] & 0xFF) != 0xCA || (bytes[1] & 0xFF) != 0xFE
		    || (bytes[2] & 0xFF) != 0xBA || (bytes[3] & 0xFF) != 0xBE) {
			return "bad magic " + hex4(bytes, 0) + " (expected cafebabe)";
		}
		int major = u2(bytes, 6);
		if (major < 45) return "implausible class file major " + major;
		if (major > MAX_CLASS_MAJOR) {
			return "class file major " + major + " is newer than this JVM's maximum " + MAX_CLASS_MAJOR;
		}
		try {
			String cheap = walkClassFile(type, bytes, major);
			if (cheap != null) return cheap;
		} catch (RuntimeException e) {
			// 走查器自己抛的越界/非法项一律收敛成拒绝：fail-closed，绝不让"没读完"变成"放行"。
			return "unparseable class file: " + e.getMessage();
		}
		return asmStructureProblem(bytes);
	}

	/**
	 * 第二道结构检查：用 ASM {@link ClassReader} 把整份字节真解析一遍。
	 *
	 * <p>为什么需要它：{@link #walkClassFile} 只走到 {@code this_class}，{@code super_class}、
	 * {@code interfaces}、字段/方法表、属性表与尾部结构一个都没读——"常量池完整、之后被截断"的字节
	 * 在它眼里完全合法。{@code accept()} 会逐项走完类、字段、方法（含 {@code Code} 属性里的指令流）
	 * 与属性表，越界读、非法长度、损坏的结构都会抛出来。</p>
	 *
	 * <p><b>边界（有意保留）</b>：ASM 对不认识的属性按声明长度跳过、不做内容校验，因此"截断点正好落在
	 * 最后一个未知属性的 payload 里"不会被这一步发现。这一类残留由 {@code docs/status.md} §5 风险 13
	 * 的关闭条件统一登记，不在此处再加第三道走查器。</p>
	 *
	 * @return {@code null} 表示 ASM 能完整解析；否则是英文拒绝原因
	 */
	private static String asmStructureProblem(byte[] bytes) {
		try {
			new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) { }, 0);
			return null;
		} catch (RuntimeException e) {
			String message = e.getMessage();
			if (message != null && message.startsWith("Unsupported class file")) {
				// ASM 的版本口径窄于本 JVM：不能证明结构完整 → 拒绝，并说清是哪一个口径不够。
				return "the bundled ASM cannot parse this class file (" + message
				     + "); this JVM accepts up to major " + MAX_CLASS_MAJOR
				     + ", so the agent predates the class file version";
			}
			return "unparseable class file (ASM " + e.getClass().getSimpleName()
			     + (message == null ? "" : ": " + message) + ")";
		}
	}

	/**
	 * 常量池走查 + 名字核对；越界一律抛 {@link IllegalStateException}，由调用方收敛成拒绝原因。
	 *
	 * <p><b>名字比较按字节做，不解码</b>：常量池里的 {@code Utf8} 是 JVMS 4.4.7 的 modified UTF-8，
	 * 对增补字符（{@code U+10000} 以上）用的是<b>代理对拆成两个三字节序列</b>（CESU-8 形态），
	 * 标准 UTF-8 解码会把这种合法序列解成替换字符，于是合法类名（如含 {@code U+1F600}）与
	 * {@code Class.getName()} 被误判成不一致而<b>误拒合法字节</b>。这里把目标名用同一编码重新编一遍，
	 * 逐字节比较：既没有解码口径问题，也不受代理对/替换字符影响。</p>
	 */
	private static String walkClassFile(Class<?> type, byte[] bytes, int major) {
		int pos = 8;
		int poolCount = readU2(bytes, pos, "constant pool count");
		pos += 2;
		if (poolCount < 1) return "constant pool count " + poolCount + " is empty";
		// 常量池索引 1..poolCount-1；Long/Double 各占两个槽（后一个索引不可寻址）。
		int[] classEntryNameIndex = new int[poolCount];
		// Utf8 项只记原始字节区间，不解码：比较口径见方法 Javadoc。{@code -1} = 不是 Utf8 项。
		int[] utf8Offset = new int[poolCount];
		int[] utf8Length = new int[poolCount];
		Arrays.fill(utf8Offset, -1);
		for (int i = 1; i < poolCount; i++) {
			int tag = readU1(bytes, pos, "tag of constant #" + i);
			pos += 1;
			switch (tag) {
				case 1: { // Utf8
					int length = readU2(bytes, pos, "Utf8 length of constant #" + i);
					pos += 2;
					require(bytes, pos, length, "Utf8 payload of constant #" + i);
					utf8Offset[i] = pos;
					utf8Length[i] = length;
					pos += length;
					break;
				}
				case 7: // Class
					classEntryNameIndex[i] = readU2(bytes, pos, "name index of constant #" + i);
					pos += 2;
					break;
				case 8: case 16: case 19: case 20: // String / MethodType / Module / Package
					require(bytes, pos, 2, "constant #" + i);
					pos += 2;
					break;
				case 15: // MethodHandle
					require(bytes, pos, 3, "constant #" + i);
					pos += 3;
					break;
				case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
					// Integer / Float / Fieldref / Methodref / InterfaceMethodref / NameAndType /
					// Dynamic / InvokeDynamic
					require(bytes, pos, 4, "constant #" + i);
					pos += 4;
					break;
				case 5: case 6: // Long / Double 占两个常量池槽
					require(bytes, pos, 8, "constant #" + i);
					pos += 8;
					if (i + 1 >= poolCount) {
						throw new IllegalStateException("constant #" + i
						    + " is a two-slot entry but the pool ends right after it");
					}
					i++;
					break;
				default:
					return "unknown constant pool tag " + tag + " at constant #" + i;
			}
		}
		// access_flags(2) + this_class(2)：这两项可读才说明常量池之后的结构没有在边界上断开。
		// super_class 及其之后的一切（interfaces / fields / methods / attributes / 尾部）不在这一步的
		// 覆盖范围内 —— 那是第二步 ASM 解析的职责（见 definitionProblem 的五项检查与分工说明）。
		require(bytes, pos, 4, "class header after the constant pool");
		int thisClassIndex = readU2(bytes, pos + 2, "this_class");
		if (thisClassIndex <= 0 || thisClassIndex >= poolCount) {
			return "this_class index " + thisClassIndex + " is out of the constant pool range";
		}
		int nameIndex = classEntryNameIndex[thisClassIndex];
		if (nameIndex <= 0 || nameIndex >= poolCount) {
			return "this_class does not point at a CONSTANT_Class entry";
		}
		int nameOffset = utf8Offset[nameIndex];
		if (nameOffset < 0 || utf8Length[nameIndex] <= 0) {
			return "this_class name is not a readable Utf8 entry";
		}
		String expected = Names.internal(type.getName());
		if (!sameBytes(bytes, nameOffset, utf8Length[nameIndex], modifiedUtf8(expected))) {
			// 只用于消息：这一份解码结果不参与判定（判定已按字节做完）。
			String shown = new String(bytes, nameOffset, utf8Length[nameIndex], StandardCharsets.UTF_8);
			return "binary name '" + shown + "' does not match the target class '" + expected + "'";
		}
		return null;
	}

	/** 定长区间逐字节比较（{@code expected} 长度不符即不相等）。 */
	private static boolean sameBytes(byte[] bytes, int offset, int length, byte[] expected) {
		if (expected.length != length) return false;
		for (int i = 0; i < length; i++) {
			if (bytes[offset + i] != expected[i]) return false;
		}
		return true;
	}

	/**
	 * 把字符串编成 JVMS 4.4.7 的 <b>modified UTF-8</b>（常量池 {@code CONSTANT_Utf8} 的编码）。
	 *
	 * <p>与标准 UTF-8 的差别只有两处，但两处都必须照抄：{@code NUL} 编成 {@code C0 80}；
	 * 增补字符（{@code U+10000} 以上）在 Java 字符串里是两个代理项，各自单独编成三字节，
	 * 因此一个增补字符在常量池里占 <b>6 字节</b>（CESU-8 形态）。这样"编码再逐字节比较"
	 * 与 JVM 写进去的形态完全同构，不依赖任何解码器的容错口径。</p>
	 */
	static byte[] modifiedUtf8(String value) {
		int length = 0;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			length += (c >= 0x0001 && c <= 0x007F) ? 1 : (c <= 0x07FF) ? 2 : 3;
		}
		byte[] out = new byte[length];
		int at = 0;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c >= 0x0001 && c <= 0x007F) {
				out[at++] = (byte) c;
			} else if (c <= 0x07FF) {
				out[at++] = (byte) (0xC0 | ((c >> 6) & 0x1F));
				out[at++] = (byte) (0x80 | (c & 0x3F));
			} else {
				out[at++] = (byte) (0xE0 | ((c >> 12) & 0x0F));
				out[at++] = (byte) (0x80 | ((c >> 6) & 0x3F));
				out[at++] = (byte) (0x80 | (c & 0x3F));
			}
		}
		return out;
	}

	/**
	 * 入口门禁：把不安全的定义<b>逐个</b>摘出本批并 {@code warn}。
	 *
	 * <p>单个畸形类不得拖垮整批：与"单字段链接失败只隔离该字段"同一取向——其余类照常 redefine，
	 * 被摘掉的类进 {@link RoundSkips}，由同轮的 skip 摘要点名，用户不会看到一条静默的成功行。</p>
	 *
	 * <p>放行下去的就是刚才校验过的那一个 {@link ClassDefinition} 实例（同一个字节数组引用），
	 * 既不复制也不重读磁盘——这是"校验用的字节 == 送进 redefine 的字节"的唯一保证方式。</p>
	 */
	static List<ClassDefinition> gateDefinitions(List<ClassDefinition> definitions, RoundSkips skips) {
		return RedefineEntryGate.gate(definitions, skips);
	}

	private static void require(byte[] bytes, int pos, int length, String what) {
		if (pos < 0 || length < 0 || pos + length > bytes.length) {
			throw new IllegalStateException("truncated class file while reading " + what
			    + " (needs " + length + " byte(s) at offset " + pos + ", file length " + bytes.length + ")");
		}
	}

	private static int readU1(byte[] bytes, int pos, String what) {
		require(bytes, pos, 1, what);
		return bytes[pos] & 0xFF;
	}

	private static int readU2(byte[] bytes, int pos, String what) {
		require(bytes, pos, 2, what);
		return u2(bytes, pos);
	}

	private static int u2(byte[] bytes, int pos) {
		return ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
	}

	private static String hex4(byte[] bytes, int pos) {
		StringBuilder sb = new StringBuilder(8);
		for (int i = 0; i < 4; i++) {
			if (pos + i >= bytes.length) { sb.append("??"); continue; }
			sb.append(Character.forDigit((bytes[pos + i] >> 4) & 0xF, 16));
			sb.append(Character.forDigit(bytes[pos + i] & 0xF, 16));
		}
		return sb.toString();
	}

	static RedefineOutcome applyTwoPhase(List<ClassDefinition> definitions,
	                                             Map<String, byte[]> oldEffectiveBytes,
	                                             Collection<AlignmentTransaction> transactions,
	                                             RoundSkips skips,
	                                             Set<String> injectedClasses) {
		return applyTwoPhase(definitions, oldEffectiveBytes, null, transactions, skips, injectedClasses);
	}

	/**
	 * @param changedNames 本轮真实改动的类名（磁盘字节 ≠ 运行期基线），只用于摘要计数与措辞；
	 *                     {@code null} = 调用方未提供，退化为"入参即变更"的旧口径。
	 */
	static RedefineOutcome applyTwoPhase(List<ClassDefinition> definitions,
	                                             Map<String, byte[]> oldEffectiveBytes,
	                                             Collection<String> changedNames,
	                                             Collection<AlignmentTransaction> transactions,
	                                             RoundSkips skips,
	                                             Set<String> injectedClasses) {
		return TwoPhaseCoordinator.applyTwoPhase(RedefineEntryGate.gate(definitions, skips), oldEffectiveBytes,
		 changedNames, transactions, skips, injectedClasses);
	}

	static String batchWatchLabel(List<ClassDefinition> defs) {
		return TwoPhaseCoordinator.batchWatchLabel(defs);
	}

	public static boolean stageAConsistent(String hostName, Set<String> targets, Set<String> ok,
	                                       Set<String> injected, Set<String> loaded) {
		return TwoPhaseCoordinator.stageAConsistent(hostName, targets, ok, injected, loaded);
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
	static RedefineOutcome applyRedefinitions(List<ClassDefinition> definitions,
	                                                  Collection<AlignmentTransaction> transactions,
	                                                  RoundSkips skips) {
		return applyRedefinitions(definitions, null, transactions, skips);
	}

	/**
	 * @param changedNames 本轮真实改动的类名；{@code null} = 调用方未提供（旧口径）。
	 *                     只影响摘要计数与措辞，不参与任何放行/拒绝判决。
	 */
	static RedefineOutcome applyRedefinitions(List<ClassDefinition> definitions,
	                                                  Collection<String> changedNames,
	                                                  Collection<AlignmentTransaction> transactions,
	                                                  RoundSkips skips) {
		return TwoPhaseCoordinator.applyRedefinitions(RedefineEntryGate.gate(definitions, skips), changedNames,
		 transactions, skips);
	}

	record RedefineOutcome(Set<String> successful, Set<String> rolledBack) { }
	//endregion

	//region File Processing Utilities

	static void initializeAgentState(Class<?>[] classes) {
		AgentWatchCoordinator.initializeAgentState(classes);
	}

	public static boolean isAssetFile(Path path) {
		return AgentWatchCoordinator.isAssetFile(path);
	}

	static void handleAssetChange(Path changedAsset) {
		AgentWatchCoordinator.handleAssetChange(changedAsset);
	}

	static void handleFileChange(Path changedFile) {
		AgentWatchCoordinator.handleFileChange(changedFile);
	}

	static void handleJarChange(Path jarPath) {
		AgentWatchCoordinator.handleJarChange(jarPath);
	}

	static void handleBuildTriggerFile(Path changed) {
		AgentWatchCoordinator.handleBuildTriggerFile(changed);
	}

	private static void triggerDueHotswap() {
		AgentWatchCoordinator.triggerDueHotswap();
	}

	private static void triggerHotswapWith(Class<?>[] classes) {
		AgentWatchCoordinator.triggerHotswapWith(classes);
	}

	public static void triggerHotswap() {
		AgentWatchCoordinator.triggerHotswap();
	}

	public static boolean redefineDynamic(Class<?> targetClass, byte[] newBytecode) {
		return DynamicRedefineExecutor.redefineDynamic(targetClass, newBytecode);
	}

	private static LayoutDecision decideDynamicLayout(Class<?> targetClass, String className,
	                                                   ClassDiffUtil.ClassDiff diff, byte[] runtimeBytes) {
		return DynamicRedefineExecutor.decideDynamicLayout(targetClass, className, diff, runtimeBytes);
	}


	static long calculateHash(byte[] data) {
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

	static {
		Diag.setSink(new Diag.Sink() {
			@Override public void log(String msg) { RemoldAgent.log(msg); }
			@Override public void info(String msg) { RemoldAgent.info(msg); }
			@Override public void warn(String msg) { RemoldAgent.warn(msg); }
			@Override public void error(String msg, Throwable t) {
				if (t == null) RemoldAgent.error(msg); else RemoldAgent.error(msg, t);
			}
			@Override public boolean isDebugEnabled() { return RemoldAgent.DEBUG; }
			@Override public boolean isPerfEnabled() { return RemoldAgent.PERF_LOG; }
		});
	}
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

	/**
	 * 注解默认值（{@code AnnotationDefault}）变化的降级通道：<b>默认关闭</b>。
	 *
	 * <p>默认行为是 fail-closed：检测到注解默认值变化就拒绝该类的重定义，批量路径还把整轮
	 * 一起作废（事务共退），并要求重启。只有显式写
	 * {@code -Dremold.agent.annotation_default_change=allow} 时才放行，且每次都会打
	 * {@code [ANNOTATION-DEFAULT]} 告警说明"已加载的消费者仍会读到旧默认值"。</p>
	 *
	 * <p>之所以要求显式：JVM 探针读数（JBR 21 / JBR 25，标准 HotSpot 与
	 * {@code -XX:+AllowEnhancedClassRedefinition}）显示 {@code redefineClasses} 会成功，
	 * 但已加载的消费者类继续读到旧默认值——放过去就是"静默按旧行为走"。</p>
	 */
	public static boolean annotationDefaultChangeAllowed() {
		String value = System.getProperty("remold.agent.annotation_default_change");
		return value != null && "allow".equalsIgnoreCase(value.trim());
	}

	/**
	 * 注解默认值门禁的统一判决（批量路径与动态路径共用这一个入口，单测直接覆盖它）。
	 *
	 * <p>判决顺序：比对出默认值变化 → 若降级通道关闭则返回拒绝原因；若显式开启则打
	 * {@code [ANNOTATION-DEFAULT]} 警告（<b>指名类与具体成员</b>，不允许静默放行）并返回 {@code null}。</p>
	 *
	 * @param className 点分类名（日志与拒绝原因都要指名）
	 * @param oldBytes  已加载基线字节（{@code null} = 无基线，不判定）
	 * @param newBytes  候选字节
	 * @return {@code null} = 放行（可能已打警告）；否则为拒绝原因（含具体成员与值变化）
	 */
	static String annotationDefaultGate(String className, byte[] oldBytes, byte[] newBytes) {
		String change = ClassDiffUtil.describeAnnotationDefaultChange(oldBytes, newBytes);
		if (change == null) {
			return null;
		}
		if (annotationDefaultChangeAllowed()) {
			warn("[ANNOTATION-DEFAULT] " + className + ": " + change
				+ " — allowed by -Dremold.agent.annotation_default_change=allow; classes that are"
				+ " already loaded will keep the old default value.");
			return null;
		}
		return change;
	}

	/**
	 * 批量路径的注解默认值处置（判定 + 拒绝日志 + 整轮共退，抽成可单测入口）。
	 *
	 * <p>调用方拿到非 {@code null} 后需要 {@code continue}（跳过本类），并记住本轮已作废。</p>
	 *
	 * @return 触发共退的类名；{@code null} = 本类放行（可能已因显式降级通道打过 WARN）
	 */
	static String handleAnnotationDefaultChangeInBatch(String className, byte[] oldBytes, byte[] newBytes,
			Map<String, AlignmentTransaction> transactions, List<ClassDefinition> definitions,
			Map<String, byte[]> oldEffectiveBytes, Map<String, Path> retryCandidates, RoundSkips skips) {
		String change = annotationDefaultGate(className, oldBytes, newBytes);
		if (change == null) {
			return null;
		}
		// 探针读数（JBR 21/25，标准 HotSpot 与 -XX:+AllowEnhancedClassRedefinition）：
		// 改掉注解类型的默认值后 redefineClasses 会成功，但已加载的消费者类继续读到旧默认值
		// （标准 HotSpot 下连重定义之后才加载的消费者也读到旧值）。只重定义注解类型修不好这一点，
		// 所以本轮整体作废（事务共退），要求重启。
		error("REJECTED: annotation default value changed for " + className + " (" + change
			+ "); classes that are already loaded keep the OLD default value, so redefining the annotation"
			+ " type cannot take effect. This batch is aborted as a whole (transaction co-rollback, nothing"
			+ " was redefined) — RESTART required. Explicit override:"
			+ " -Dremold.agent.annotation_default_change=allow (already-loaded classes will still keep the"
			+ " old default).");
		abortRoundForAnnotationDefault(className, change, transactions, definitions, oldEffectiveBytes,
			retryCandidates, skips);
		return className;
	}

	/**
	 * 注解默认值变化的<b>整轮共退</b>记账（批量路径专用，抽成可单测入口）。
	 *
	 * <p>语义：本批一个类都不重定义（连坐）。动作：回滚本类对齐事务并移出事务表 →
	 * 清空本批待重定义集合与旧字节映射 → 清空重试候选（重试改不了已加载类的旧默认值）→
	 * 在 {@link RoundSkips} 里留下点名判决。调用方随后也需要跳过本类（{@code continue}）。</p>
	 *
	 * @return 本次被作废的重定义条目数（{@code 0} = 本轮本来就没有待重定义类）
	 */
	static int abortRoundForAnnotationDefault(String className, String change,
			Map<String, AlignmentTransaction> transactions, List<ClassDefinition> definitions,
			Map<String, byte[]> oldEffectiveBytes, Map<String, Path> retryCandidates, RoundSkips skips) {
		int dropped = definitions == null ? 0 : definitions.size();
		if (transactions != null) {
			AlignmentTransaction own = className == null ? null : transactions.get(className);
			if (own != null) {
				own.rollback(true);
				transactions.remove(className);
			}
			for (AlignmentTransaction tx : new ArrayList<>(transactions.values())) {
				tx.rollback(true);
			}
			transactions.clear();
		}
		if (definitions != null) definitions.clear();
		if (oldEffectiveBytes != null) oldEffectiveBytes.clear();
		if (retryCandidates != null) retryCandidates.clear();
		if (skips != null && className != null) {
			skips.add(className, RoundSkips.verdict("ANNOTATION-DEFAULT-CHANGED", change,
				"restart required — already-loaded classes keep serving the old default"));
		}
		return dropped;
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
	static byte[] normalizeAlignBaseline(byte[] bytes, String slashName, ClassLoader loader) {
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
	static Function<String, byte[]> normalizeAlignResolver(
	 Function<String, byte[]> raw, ClassLoader loader) {
		return normalizeAlignResolver(raw, loader, FORCE_STATIC_LAMBDA ? new HashMap<>() : null);
	}

	static Function<String, byte[]> normalizeAlignResolver(
	 Function<String, byte[]> raw, ClassLoader loader, Map<String, byte[]> cache) {
		if (!FORCE_STATIC_LAMBDA) return raw;
		Map<String, byte[]> targetCache = cache != null ? cache : new HashMap<>();
		return name -> {
			if (targetCache.containsKey(name)) return targetCache.get(name);
			byte[] out = normalizeAlignBaseline(raw.apply(name), name, loader);
			targetCache.put(name, out);
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