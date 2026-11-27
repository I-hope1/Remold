package remold.ref;

import jdk.internal.misc.Unsafe;
import remold.WorkspaceRawIndex;
import remold.ClassDiffUtil.ClassDiff;
import remold.*;
import remold.annotation.RemoldReinit;
import remold.vcs.AlignmentMetaStore;
import remold.vcs.Changeset;
import remold.vcs.ChangesetLog;
import remold.vcs.GenerationalFieldLedger;
import remold.vcs.GenerationalReplayGate;
import remold.vcs.ScopeVerdicts;
import remold.util.ClassIdentity;
import remold.util.LibTool;
import remold.util.Names;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.tree.analysis.Frame;

import java.lang.instrument.ClassDefinition;
import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static remold.RemoldAgent.log;

/**
 * Hotswap 时初始化修复器（InitFix）。
 *
 * <p>为热更新（Redefine）后<b>新增声明的字段</b>（存量实例 + 静态环境）以及标有
 * {@link remold.annotation.RemoldReinit} 的已有字段补全初始化值的核心系统。
 * 设计文档参见 {@code docs/initfix/} 目录系列文档；实现状态参见 {@code docs/status.md} 与 {@code AGENTS.md}。</p>
 *
 * <h2>五条核心不变量（任何改动均不得破坏）</h2>
 * <ol>
 *   <li><b>未初始化守卫</b>：{@code UNSAFE.shouldBeInitialized(clazz) == true} 时直接跳过修补。</li>
 *   <li><b>PENDING 弱键</b>：{@code synchronizedMap(WeakHashMap<Class<?>, PendingPatch>)}。
 *       {@code PendingPatch} 不得持有 {@code Class<?>} 强引用；保留 5 分钟 TTL 清扫（{@link #PENDING_TTL_NANOS}）。</li>
 *   <li><b>PatchReport 解耦</b>：只存类名字符串和不可变枚举，不得持有 {@code Class} 或 {@code ClassLoader}。</li>
 *   <li><b>LinkageError 熔断</b>：补丁抛 {@link LinkageError} 立即中止当前类。
 *       唯一例外：{@link BootstrapMethodError} 且 {@code getCause()} <b>不是</b> {@link LinkageError}，
 *       视为单字段链接失败，仅隔离该字段。判定保持窄。</li>
 *   <li><b>afterRedefineFailed</b>：Redefine 失败时立即注销并弹出暂存补丁，未补字段记入台账。</li>
 * </ol>
 *
 * <h2>判决分层与安全门（{@code docs/initfix/01-safety-gate.md}）</h2>
 * <p>整体策略是"宁可拒绝，不可误改"：分支、try/catch 相交、控制依赖、局部变量依赖、
 * 多根构造器不一致、循环依赖均予拒绝。误补会静默污染对象状态，漏补只是字段保持默认值。</p>
 * <ul>
 *   <li><b>T0 零值等价</b>（{@code docs/initfix/01-safety-gate.md} §1）：显式 {@code = null}/{@code = 0}/{@code = false}、仅声明未赋值、{@code ConstantValue}
 *       按位为零。标记 {@link FieldStatus#NOTHING_TO_PATCH}，零开销放行、不告警、不生成补丁
 *       （浮点按位比较，{@code -0.0f}/{@code NaN} 不算零值）。</li>
 *   <li><b>T1 编译期常量</b>（{@code docs/initfix/01-safety-gate.md} §1）：静态 {@code ConstantValue} 走专用通道。</li>
 *   <li><b>T2 纯计算切片</b>（{@code docs/initfix/01-safety-gate.md} §1）：经 {@link InitFixSliceExtractor.AliasInterpreter} 逆向数据流切片，并通过效应安全门。</li>
 *   <li><b>T3 复杂或不安全</b>（{@code docs/initfix/01-safety-gate.md} §1）：含分支、环境依赖、可变源字段，拒绝生成代码并输出诊断。</li>
 *   <li><b>T4 显式逃生口 {@code @HotswapInit}（目标设计，未落地）</b>（{@code docs/initfix/09-hotswapinit-design.md} 与
 *       {@code docs/initfix/01-safety-gate.md} §1）：规划中由伴生类直接调用该静态方法、免效应检查；
 *       当前没有该注解与转发实现，可用逃生口只有字段级 {@link remold.annotation.RemoldReinit}。</li>
 * </ul>
 *
 * <h2>效应检查与例外（{@code docs/initfix/01-safety-gate.md} §2）</h2>
 * <ul>
 *   <li><b>黑名单拦截</b>：非确定性（时间、随机数、UUID）、反射与动态调用、进程与类加载、
 *       IO/系统调用、日志输出；{@code ThreadLocal}（执行线程敏感）；外部复用 builder 变异。</li>
 *   <li><b>白名单放行</b>：基础集合无参构造、{@code Logger} 纯工厂与查询、{@code Objects.requireNonNull}、
 *       Kotlin {@code Intrinsics}、不可变集合工厂。</li>
 *   <li><b>窄例外（Kotlin trim 家族）</b>：允许 kotlinc 展开形态中的 {@code Object.toString}
 *       （仅限来源直接为 {@code StringsKt.trim/trimStart/trimEnd} 的三条纯字符串/CharSequence 闭包），
 *       其余 {@code Object.toString}/{@code hashCode} 维持黑名单拦截（fail-closed）。</li>
 * </ul>
 *
 * <h2>参数回溯不可变证明（{@code docs/initfix/01-safety-gate.md} §3）</h2>
 * <p>Java 中构造器参数直接赋值（{@code this.f = param}）在切片提取时，将 {@code ALOAD n} 重写为
 * {@code ALOAD 0; GETFIELD this.f}。重写前提是源字段可证明不可变：</p>
 * <ul>
 *   <li><b>条件 A</b>：源字段具有 {@code ACC_FINAL}（含 Kotlin {@code val}）。</li>
 *   <li><b>条件 B</b>：非 final 时必须为 {@code private}，且通过 {@link InitFixSafetyGate.NestView} 证明全 Nest 范围内
 *       除本次构造器 pattern 外<b>不存在第二处针对该字段的 {@code PUTFIELD}</b>。</li>
 * </ul>
 *
 * <h2>依赖闭包、成环检测与台账（{@code docs/initfix/02-closure-and-ledger.md}）</h2>
 * <ul>
 *   <li><b>成环检测（Tarjan SCC）</b>：在闭包不动点计算内部反复执行，只拒绝环成员，并触发依赖连带拒绝传播。</li>
 *   <li><b>出口后置闭合校验</b>：最终放行集合对 {@code depInstance ∪ depStatic} 依赖闭合，作为主防线。</li>
 *   <li><b>待补台账 {@code FieldLedger}</b>：覆盖六种情形（分析期被拒、运行期异常、依赖跳过、Redefine 失败、
 *       规划期异常、TTL 超时清扫），保证未成功修补字段下一轮重新纳入候选，永不遗忘。</li>
 * </ul>
 *
 * <h2>伴生类、驱动与写入协议（{@code docs/initfix/03-runtime-driver.md}）</h2>
 * <ul>
 *   <li><b>伴生类</b>：每个放行字段生成独立静态直线方法（实例 {@code init$F}，静态 {@code initStatic$F}），
 *       宿主 hidden nestmate 承载，隔离异常与加载死锁。</li>
 *   <li><b>逐实例驱动</b>：依赖失败按实例隔离；单字段每轮实例失败配额封顶为 8 次（{@link #MAX_INSTANCE_FAILURES_PER_FIELD}）。</li>
 *   <li><b>写入桥（{@link HotswapBridge}）</b>：默认使用条件 CAS（{@link HotswapBridge#KIND_CONDITIONAL}），
 *       float/double 统一走 raw bits CAS；跳过计数通过 {@code filterReturnValue} 汇总汇报；
 *       {@link remold.annotation.RemoldReinit.Mode#OVERWRITE} 走强制无条件写（{@link HotswapBridge#KIND_FORCE}）。</li>
 *   <li><b>合成标记字段过滤</b>：由 {@link ClassDiffUtil} 对称过滤 {@code ACC_SYNTHETIC} 与 {@code $remold$} 前缀字段。</li>
 * </ul>
 *
 * <h2>堆实例检索（{@code docs/initfix/05-jvmti-heap.md}）</h2>
 * <p>优先使用 {@link LibTool#getInstances(Class)} 进行 Native JVMTI 堆遍历，
 * 无需字节码插桩即可完整覆盖目标类及其派生子类存活实例；不可用时降级至 {@link InstanceTracker}。</p>
 *
 * <h2>核心已知限制与路线图关联（Known Limitations & Roadmap）</h2>
 * <ul>
 *   <li><b>static final 的 JIT 常量折叠与读穿限制</b>：
 *       JIT 编译可能已将旧的静态常量值内联折叠；同时重定义瞬时新方法体可能在补丁就位前读到默认零值（读穿 NPE）。
 *       单纯内存写无法撤销 JIT 常量折叠。目标解法详见 {@code docs/initfix/04-target-design.md} §1「两阶段 Schema-First 重定义」
 *       （Stage A 骨架先行，Stage B 补丁执行，Stage C 全量上线）。</li>
 *   <li><b>实例快照窗口限制与逐实例补丁非原子</b>：
 *       堆遍历快照到补丁生效之间存在微小时钟窗口，并发业务线程在此期间新建的对象可能漏补。
 *       目标解法详见 {@code docs/initfix/04-target-design.md} §4「构造器尾部插桩」
 *       （Stage A 构造器尾部注入切片，物理封死新建对象缝隙）。</li>
 *   <li><b>跨类批次拓扑依赖限制</b>：
 *       当前拓扑排序局限于单个宿主类内部（类内拓扑是既有契约）。若类 X 的新增字段切片直接读取类 Y 的新增字段，
 *       可能因跨类加载顺序非确定性读到未就绪值。目标解法详见 {@code docs/initfix/04-target-design.md} §5「跨类批次拓扑排序」。</li>
 *   <li><b>复杂控制流与不可切片表达式</b>：
 *       含分支（if/switch）、循环、try-catch、局部变量依赖、Kotlin {@code ?.} / {@code ?:} / {@code let} / {@code apply} 等展开形态会被安全门坚决拒绝，记入待补台账。
 *       目标逃生口设计详见 {@code docs/initfix/09-hotswapinit-design.md} 与 {@code docs/initfix/01-safety-gate.md}
 *       §1「T4 显式逃生口 @HotswapInit」（设计未落地；当前可用逃生口是 {@code @RemoldReinit}）。</li>
 * </ul>
 */
public class InitFix {
	private static final String STATIC_PATCH_PREFIX   = "initStatic$";
	private static final String INSTANCE_PATCH_PREFIX = "init$";
	private static final String BATCH_PATCH_PREFIX    = "initBatch$";
	private static final String BATCH_GROUP_PREFIX    = "initBatchGroup$";
	private static final int    MAX_FUSED_BATCH_FIELDS = 8;
	/** A 档 apply 重放的静态方法前缀；按 applyFields 插入顺序编号（驱动侧复用同一顺序）。 */
	static final String APPLY_REPLAY_PREFIX    = "applyReplay$";
	private static final String PATCH_SUFFIX          = "$$HotswapPatch";

	private static final long PENDING_TTL_NANOS     = TimeUnit.MINUTES.toNanos(5);
	static final int  MAX_DETAILED_FAILURES = 5;

	/**
	 * {@link #PENDING_TTL_NANOS} 的可调副本，仅供测试/诊断把 TTL 缩短以触发清扫。
	 * 设为 {@code <= 0} 表示"一经创建即视为过期"。
	 */
	private static volatile long pendingTtlNanos = PENDING_TTL_NANOS;

	/** 测试/诊断用：覆盖待补补丁的 TTL（见 {@link #pendingTtlNanos}）。 */
	public static void setPendingTtlNanos(long ttlNanos) {
		pendingTtlNanos = ttlNanos;
	}

	/** 测试/诊断用：立即跑一次过期清扫（生产路径由 {@code transform} 触发）。 */
	public static void sweepStalePatches() {
		cleanupStalePatches();
	}

	/**
	 * 单字段在<b>一次热更</b>内允许失败几次后熔断（{@code docs/initfix/03-runtime-driver.md} §2 逐实例驱动的代价控制）。
	 *
	 * <p>按实例隔离"依赖跳过"之后，确定性失败（切片本身必然抛异常）会让<b>每个实例
	 * 都各自失败一遍</b>，日志与耗时放大成 N 倍。这个配额把同一字段的失败次数封顶，
	 * 超过即停止继续尝试该字段（该字段及其下游仍照常记账）。</p>
	 */
	static final int MAX_INSTANCE_FAILURES_PER_FIELD = 8;

	/**
	 * 详细失败日志的全局配额：逐实例驱动下同一轮可能有成千上万个实例各自失败，
	 * 逐个打栈会淹没日志（{@code docs/initfix/03-runtime-driver.md} §2 的 {@code handleFieldException}）。
	 */
	static final AtomicInteger DETAILED_FAILURE_LOGS = new AtomicInteger();

	/** 识别存量重置注解 RemoldReinit。 */
	private static boolean isReinitDesc(String desc) {
		return "Lremold/annotation/RemoldReinit;".equals(desc);
	}
	private static boolean isReinitModeDesc(String desc) {
		return "Lremold/annotation/RemoldReinit$Mode;".equals(desc);
	}
	private static boolean isReinitScopeDesc(String desc) {
		return "Lremold/annotation/RemoldReinit$Scope;".equals(desc);
	}

	/**
	 * 一个被 {@code @RemoldReinit} 标注的字段及其写入协议。
	 *
	 * @param overwrite    写入协议：{@code OVERWRITE} 无条件覆写，否则条件 CAS
	 * @param selfOnly     {@code scope = SELF}：只重置运行时类恰好等于声明类的实例；
	 *                     {@code INCLUDE_SUBCLASSES}（默认）覆盖子类/匿名子类实例
	 * @param scopeExplicit 注解里是否<b>显式</b>写了 {@code scope} 元素。
	 *                     ASM 只序列化显式给出的元素，因此可据此区分"用户写了 scope"
	 *                     与"注解默认值"：静态字段只有一个槽位，作用域对它没有意义，
	 *                     只有用户显式写 scope 时才需要提醒（{@code docs/initfix/03-runtime-driver.md} §4）。
	 */
	record ReinitField(boolean overwrite, boolean selfOnly, boolean scopeExplicit) { }

	static final Unsafe UNSAFE = Unsafe.getUnsafe();

	static final Map<Class<?>, PendingPatch> PENDING =
	 Collections.synchronizedMap(new WeakHashMap<>());

	/**
	 * 最近一次 {@link #transform} 的报告，按宿主类弱键保存。
	 * <p>{@link PatchReport} 本身不持有宿主引用，避免 value 强引用 key
	 * 导致 WeakHashMap 永不回收。</p>
	 */
	private static final Map<Class<?>, PatchReport> REPORTS =
	 Collections.synchronizedMap(new WeakHashMap<>());

	/**
	 * 待补字段台账 {@code FieldLedger}（{@code docs/initfix/02-closure-and-ledger.md} §2）：
	 * {@code Class -> (字段名 -> 未补原因)}。
	 *
	 * <p>解决的问题：redefine 一旦成功，类结构就<b>不可逆</b> —— 宿主类已在物理内存里
	 * 携带了新字段，而字段可能没被补上（分析期被拒、运行期抛异常、依赖失败、或 redefine
	 * 本身失败）。如果不记账，下一轮的候选集只看"本轮 Diff 新增字段"，这些字段就会被永久遗忘
	 * （它们已经"存在"了，再也不会出现在 Diff 里）。</p>
	 *
	 * <p>键是弱引用的 {@code Class<?>}，值是纯字符串 —— 与 {@link #PENDING}/{@link #REPORTS}
	 * 同一策略：不阻止类卸载、不给 Metaspace 添强引用。类被回收后台账自动消失。</p>
	 */
	private static final Map<Class<?>, Map<String, String>> LEDGER =
	 Collections.synchronizedMap(new WeakHashMap<>());

	/** 台账里的一条未决字段。 */
	public record UnpatchedField(String fieldName, String reason) { }

	/**
	 * {@code @RemoldReinit} 已应用记录：{@code Class -> (字段名 -> "模式|初值切片指纹")}。
	 *
	 * <p>用途只有一个：判断"这个字段的初值表达式自上次应用以来有没有变"，从而让
	 * {@code OVERWRITE} 不再对<b>无关 redefine</b>（只改了别的方法体）重复覆写存量值
	 * （{@code docs/initfix/03-runtime-driver.md} §4）。</p>
	 *
	 * <p><b>只在封闭表达式上生效</b>（见 {@link #isClosedSlice}）。切片里一旦出现外部读取
	 * ——{@code ConfigLoader.getDefaultTag()} 这类跨类调用、{@code Other.VALUE} 这类他类静态字段——
	 * "字节码没变"就不再等价于"值没变"：被调用方的改动在宿主类的字节码里根本不可见。
	 * 这类字段一律不跳过，维持每轮重置的保守行为，宁可多写一次也不静默留下过期值。</p>
	 *
	 * <p>与 {@link #LEDGER} 同一策略：弱键 + 纯字符串值，不阻止类卸载、不反向持有 {@code Class}。</p>
	 */
	private static final Map<Class<?>, Map<String, String>> REINIT_APPLIED =
	 Collections.synchronizedMap(new WeakHashMap<>());

	/** Transient same-batch view; cleared at the end of each batch. */
	static final ThreadLocal<BatchContext> BATCH_CONTEXT = new ThreadLocal<>();

	/** Transient runtime failure tokens used only while a batch is being patched. */
	static final ThreadLocal<Set<String>> BATCH_FAILURES =
	 ThreadLocal.withInitial(LinkedHashSet::new);

	private record BatchInput(Class<?> host, byte[] newBytes, ClassDiff diff) { }
	private record BatchContext(Map<ClassLoader, Map<String, byte[]>> bytesByLoader,
	                            Map<String, Set<String>> candidateFields, List<BatchInput> inputs,
	                            Map<ClassLoader, String> loaderIds,
	                            Map<Class<?>, BatchPlan> plans,
	                            List<Changeset.ReplayFact> replayFacts) { }

	private record BatchPlan(PatchAnalysis analysis, Runnable publish) { }

	/**
	 * 类型改变字段的状态日志（**接口而非文案**：探针会拿它做前置断言）。
	 *
	 * <p>格式固定 {@code [TYPE-CHANGED-FIELD] cls=<internal> field=<name> state=<accepted|rejected|not-candidate> reason=<enum>}；
	 * 参数只放类名、字段名与原因枚举，**不放实例**——格式化被补丁对象会触发它的 {@code toString()}，把日志变成一次方法调用。</p>
	 */
	public static String typeChangedState(String className, String fieldName, String state, String reason) {
		return "[TYPE-CHANGED-FIELD] cls=" + className + " field=" + fieldName
		     + " state=" + state + " reason=" + reason;
	}

	/**
	 * 状态日志允许出现的原因枚举全集。
	 *
	 * <p>{@code other} 是兜底项，**不应当出现**：出现即说明归因没做完，探针框架会把它当失败处理。要新增原因，
	 * 必须同时更新本集合与 {@code TypeChangedFieldLogFormatTest} 的断言。</p>
	 */
	public static final java.util.Set<String> TYPE_CHANGED_REASONS = java.util.Set.of(
		"none", "ctor-param-assign", "no-initializer", "later-gate", "type-unchanged", "key-mismatch",
		"reinit-owned", "other");

	/** 把计划给的自由文本原因归一成枚举，避免日志里出现会变动的散文。 */
	public static String typeChangedReason(String rawReason) {
		if (rawReason == null) return "none";
		if (rawReason.contains("written outside its extraction")) return "ctor-param-assign";
		if (rawReason.contains("no safe initialization expression")
		    || rawReason.contains("no field initializer found in")) return "no-initializer";
		if (rawReason.contains("self-assignment")) return "ctor-param-assign";
		if (rawReason.contains("subsequent processing")) return "later-gate";
		return "other";
	}

	private record PatchAnalysis(Map<String, FieldDecision> instanceDecisions,
	                             Map<String, FieldDecision> staticDecisions,
	                             Map<String, Set<InitFixBatchGraph.FieldKey>> dependencies,
	                             BiConsumer<String, String> reject, Runnable close, Supplier<BuiltPatch> emit) {
		FieldDecision decision(String field) {
			return instanceDecisions.getOrDefault(field, staticDecisions.get(field));
		}
	}

	/** 只读视图：当前台账里该宿主的未决字段（测试与诊断用）。 */
	/** Install the transient same-batch bytecode view used by offline analysis. */
	public static void beginBatchBytecodeView(Map<ClassLoader, Map<String, byte[]>> byLoader) {
		Map<ClassLoader, Map<String, byte[]>> copy = new IdentityHashMap<>();
		if (byLoader != null) {
			for (Map.Entry<ClassLoader, Map<String, byte[]>> entry : byLoader.entrySet()) {
				Map<String, byte[]> bytes = new LinkedHashMap<>();
				if (entry.getValue() != null) bytes.putAll(entry.getValue());
				copy.put(entry.getKey(), bytes);
			}
		}
		Map<ClassLoader, String> loaderIds = new IdentityHashMap<>();
		Map<String, Set<String>> candidateFields = new LinkedHashMap<>();
		int nextId = 0;
		for (Map.Entry<ClassLoader, Map<String, byte[]>> entry : copy.entrySet()) {
			loaderIds.put(entry.getKey(), "L" + nextId++);
			for (String host : entry.getValue().keySet()) {
				candidateFields.put(batchHostKeyForId(loaderIds.get(entry.getKey()), host), Set.of());
			}
		}
		BATCH_CONTEXT.set(new BatchContext(copy, candidateFields, new ArrayList<>(), loaderIds,
		 new LinkedHashMap<>(), new ArrayList<>()));
		BATCH_FAILURES.get().clear();
		// 工作区 Raw 索引与批次上下文同寿：批内复用，批末清空（不做跨批次失效协议）。
		WorkspaceRawIndex.beginBatch();
	}

	/** Clear all transient same-batch state after redefine/post-processing completes. */
	public static void endBatchBytecodeView() {
		BATCH_CONTEXT.remove();
		BATCH_FAILURES.remove();
		WorkspaceRawIndex.endBatch();
	}

	/** Replace the provisional layout-gate view with the definitions that can actually be installed. */
	public static void setBatchDefinitions(List<ClassDefinition> definitions) {
		BatchContext context = BATCH_CONTEXT.get();
		if (context == null) return;
		context.bytesByLoader().clear();
		// Start from the keys registered when the view was opened: classes dropped by the layout/annotation
		// gates never reach transform(), and forgetting them here would let analysis silently read their stale
		// global-cache bytes instead of failing closed.
		Map<String, Set<String>> candidates = new LinkedHashMap<>(context.candidateFields());
		for (BatchInput input : context.inputs()) {
			if (input.diff() == null || input.diff().newClass == null) continue;
			Set<String> fields = new LinkedHashSet<>();
			fields.addAll(input.diff().addedInstanceFields);
			fields.addAll(input.diff().addedStaticFields);
			fields.addAll(scanReinitFields(input.diff().newClass).keySet());
			fields.addAll(ledgerSnapshot(input.host()).keySet());
			candidates.put(batchHostKey(context, input.host()), fields);
		}
		for (ClassDefinition definition : definitions) {
			Class<?> host = definition.getDefinitionClass();
			ClassLoader loader = host.getClassLoader();
			context.loaderIds().computeIfAbsent(loader, ignored -> "L" + context.loaderIds().size());
			context.bytesByLoader().computeIfAbsent(loader, ignored -> new LinkedHashMap<>())
			 .put(host.getName(), definition.getDefinitionClassFile());
		}
		BATCH_CONTEXT.set(new BatchContext(context.bytesByLoader(), candidates, context.inputs(),
		 context.loaderIds(), context.plans(), context.replayFacts()));
	}

	/** Build all batch patches only after every transform input has been collected. */
	public static void finishBatchTransforms() {
		BatchContext context = BATCH_CONTEXT.get();
		if (context == null) return;
		List<BatchInput> allInputs = new ArrayList<>(context.inputs());
		List<BatchInput> inputs = allInputs.stream().filter(input -> {
			Map<String, byte[]> bytes = context.bytesByLoader().get(input.host().getClassLoader());
			return bytes != null && bytes.containsKey(input.host().getName());
		}).toList();
		Map<String, Set<String>> candidates = new LinkedHashMap<>(context.candidateFields());
		for (BatchInput input : allInputs) {
			ClassDiff diff = input.diff();
			if (diff == null || diff.newClass == null) continue;
			Set<String> fields = new LinkedHashSet<>();
			fields.addAll(diff.addedInstanceFields);
			fields.addAll(diff.addedStaticFields);
			fields.addAll(scanReinitFields(diff.newClass).keySet());
			fields.addAll(ledgerSnapshot(input.host()).keySet());
			candidates.put(batchHostKey(context, input.host()), fields);
		}
		BATCH_CONTEXT.set(new BatchContext(context.bytesByLoader(), candidates, inputs,
		 context.loaderIds(), context.plans(), context.replayFacts()));
		for (BatchInput input : inputs) transformNow(input.host(), input.newBytes(), input.diff());
		convergeBatch(BATCH_CONTEXT.get());
		for (BatchPlan plan : context.plans().values()) plan.publish().run();
	}

	private static void convergeBatch(BatchContext context) {
		Map<String, PatchAnalysis> analyses = new LinkedHashMap<>();
		context.plans().forEach((host, plan) -> analyses.put(batchHostKey(context, host), plan.analysis()));
		while (true) {
			Map<InitFixBatchGraph.FieldKey, Set<InitFixBatchGraph.FieldKey>> edges = new LinkedHashMap<>();
			Map<InitFixBatchGraph.FieldKey, String> rejected = new LinkedHashMap<>();
			for (var entry : analyses.entrySet()) {
				PatchAnalysis analysis = entry.getValue();
				for (var dependency : analysis.dependencies().entrySet()) {
					String name = dependency.getKey();
					if (!analysis.decision(name).accepted()) continue;
					var field = new InitFixBatchGraph.FieldKey(entry.getKey(), name);
					Set<InitFixBatchGraph.FieldKey> active = new LinkedHashSet<>();
					for (var prerequisite : dependency.getValue()) {
						PatchAnalysis owner = analyses.get(prerequisite.host());
						FieldDecision decision = owner == null ? null : owner.decision(prerequisite.field());
						if (decision == null || decision.status() == FieldStatus.REJECTED) {
							rejected.putIfAbsent(field, "cross-class dependency unavailable: " + prerequisite.field());
						} else if (decision.accepted()) {
							active.add(prerequisite);
						}
					}
					edges.put(field, active);
				}
			}
			if (rejected.isEmpty()) {
				var graph = InitFixBatchGraph.graph(edges);
				for (var field : graph.cycleMembers()) rejected.put(field, "cross-class dependency cycle");
				if (rejected.isEmpty()) {
					for (var field : graph.conflictingHostReaders()) {
						rejected.put(field, "incompatible cross-class host ordering");
					}
				}
			}
			if (rejected.isEmpty()) {
				edges.forEach((field, prerequisites) -> analyses.get(field.host()).dependencies()
				 .put(field.field(), prerequisites));
				return;
			}
			Set<PatchAnalysis> affected = Collections.newSetFromMap(new IdentityHashMap<>());
			rejected.forEach((field, reason) -> {
				PatchAnalysis analysis = analyses.get(field.host());
				analysis.reject().accept(field.field(), reason);
				affected.add(analysis);
			});
			affected.forEach(analysis -> analysis.close().run());
		}
	}

	static boolean batchViewActive(ClassLoader loader) {
		BatchContext context = BATCH_CONTEXT.get();
		return context != null && context.bytesByLoader().containsKey(loader);
	}

	static boolean batchCandidateMissing(ClassLoader loader, String internalName) {
		BatchContext context = BATCH_CONTEXT.get();
		return context != null && context.bytesByLoader().containsKey(loader)
		       && context.candidateFields().containsKey(batchHostKey(context, internalName, loader))
		       && !context.bytesByLoader().get(loader).containsKey(internalName.replace('/', '.'));
	}

	/**
	 * 把"本批新产生、但还没进 redefine 的类字节"登记进批次视图（对齐后注入的新匿名类就是这一类）。
	 *
	 * <p>为什么必须登记：InitFix 在提取字段初值时会判定构造器是 {@code protected} 还是包内可见，判据来自
	 * {@link HierarchyTreeOracle#resolveMember}，而后者只查批次视图 / 运行期字节 / 源码基线。本批新编译或重编号的
	 * 匿名类三条都不在——于是构造器权限判不出来，{@code [INITFIX-REFUSE]} 把宿主整类踢出批次（现场：
	 * {@code new field val movedOp = object : IntUnaryOperator { ... }}）。</p>
	 *
	 * @param loader 目标加载器（宿主所在加载器）
	 * @param classes 类名（点分或内部名均可）到字节
	 */
	public static void registerBatchClassBytes(ClassLoader loader, Map<String, byte[]> classes) {
		if (loader == null || classes == null || classes.isEmpty()) return;
		Map<String, byte[]> extra = EXTRA_BYTES.computeIfAbsent(loader, ignored -> new java.util.LinkedHashMap<>());
		for (Map.Entry<String, byte[]> e : classes.entrySet()) {
			if (e.getKey() != null && e.getValue() != null) extra.put(Names.external(e.getKey()), e.getValue());
		}
		BatchContext context = BATCH_CONTEXT.get();
		if (context == null) return;
		Map<String, byte[]> bytes = context.bytesByLoader()
		 .computeIfAbsent(loader, ignored -> new LinkedHashMap<>());
		for (Map.Entry<String, byte[]> e : classes.entrySet()) {
			if (e.getKey() == null || e.getValue() == null) continue;
			bytes.put(Names.external(e.getKey()), e.getValue());
		}
	}

	/**
	 * 批次旁路字节表：**注入点在 {@link #BATCH_CONTEXT} 之外**（ThreadLocal 只在 transform 期间有值），
	 * 所以刚注入的类必须记在这里，才能被 InitFix 的可见性判定查到。
	 *
	 * <p>键弱引用加载器；值只放 {@code byte[]}，不反向持 {@code Class}。</p>
	 */
	private static final java.util.Map<ClassLoader, java.util.Map<String, byte[]>> EXTRA_BYTES =
	 java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	/** 一批开始时清空旁路字节表；调用点必须在**对齐/注入之前**。 */
	public static void clearExtraBatchBytes() {
		EXTRA_BYTES.clear();
	}

	static byte[] batchClassBytes(ClassLoader loader, String internalName) {
		BatchContext context = BATCH_CONTEXT.get();
		if (context == null || internalName == null) return null;
		Map<String, byte[]> bytes = context.bytesByLoader().get(loader);
		String dotted = Names.external(internalName);
		byte[] direct = bytes == null ? null : bytes.get(dotted);
		if (direct != null) return direct;
		Map<String, byte[]> extra = EXTRA_BYTES.get(loader);
		return extra == null ? null : extra.get(dotted);
	}

	static Set<String> activeBatchFailures() {
		return BATCH_CONTEXT.get() == null ? Set.of() : BATCH_FAILURES.get();
	}

	static String batchFailureToken(InitFixBatchGraph.FieldKey field) {
		return "@batch:" + field.host() + "." + field.field();
	}

	static void publishBatchFailure(Class<?> host, String field) {
		if (BATCH_CONTEXT.get() == null) return;
		BATCH_FAILURES.get().add(batchFailureToken(
		 new InitFixBatchGraph.FieldKey(batchHostKeyCurrent(host), field)));
	}

	private static String batchHostKeyForId(String loaderId, String host) {
		return loaderId + ":" + host.replace('/', '.');
	}

	private static String batchHostKey(BatchContext context, Class<?> host) {
		return context.loaderIds().getOrDefault(host.getClassLoader(), "bootstrap") + ":" + host.getName();
	}

	private static String batchCandidateOwnerKey(BatchContext context, String owner, ClassLoader loader) {
		String direct = batchHostKey(context, owner, loader);
		return context.candidateFields().containsKey(direct) ? direct : null;
	}

	private static String batchHostKey(BatchContext context, String owner, ClassLoader loader) {
		return context.loaderIds().getOrDefault(loader, "bootstrap") + ":" + owner.replace('/', '.');
	}

	static Set<String> batchCandidateFields(ClassLoader loader, String hostName) {
		BatchContext context = BATCH_CONTEXT.get();
		if (context == null || hostName == null) return Set.of();
		return context.candidateFields().getOrDefault(batchHostKey(context, hostName, loader), Set.of());
	}

	public static Set<UnpatchedField> getUnpatchedFields(Class<?> host) {
		if (host == null) return Set.of();
		synchronized (LEDGER) {
			Map<String, String> m = LEDGER.get(host);
			if (m == null || m.isEmpty()) return Set.of();
			Set<UnpatchedField> out = new LinkedHashSet<>();
			m.forEach((k, v) -> out.add(new UnpatchedField(k, v)));
			return out;
		}
	}

	static Map<String, String> ledgerSnapshot(Class<?> host) {
		synchronized (LEDGER) {
			Map<String, String> m = LEDGER.get(host);
			return m == null ? Map.of() : new LinkedHashMap<>(m);
		}
	}

	/** 记入台账（同名字段覆盖原因，保留最新一次）。 */
	static void ledgerPut(Class<?> host, String fieldName, String reason) {
		if (host == null || fieldName == null) return;
		String r = reason == null ? "unpatched" : reason;
		if (r.length() > 200) r = r.substring(0, 200) + "...";
		synchronized (LEDGER) {
			LEDGER.computeIfAbsent(host, k -> new LinkedHashMap<>()).put(fieldName, r);
		}
	}

	/**
	 * 仅在台账尚无该字段时记入（不覆盖已有原因）。
	 * <p>TTL 清扫用它：字段可能已被别处记下更具体的原因，不该被笼统的"过期"盖掉。</p>
	 */
	private static void ledgerPutIfAbsent(Class<?> host, String fieldName, String reason) {
		if (host == null || fieldName == null) return;
		String r = reason == null ? "unpatched" : reason;
		if (r.length() > 200) r = r.substring(0, 200) + "...";
		synchronized (LEDGER) {
			LEDGER.computeIfAbsent(host, k -> new LinkedHashMap<>()).putIfAbsent(fieldName, r);
		}
	}

	/** 出账：字段本轮已解决（补上、或判定无需补）。 */
	private static void ledgerRemove(Class<?> host, String fieldName) {
		if (host == null) return;
		synchronized (LEDGER) {
			Map<String, String> m = LEDGER.get(host);
			if (m == null) return;
			m.remove(fieldName);
			if (m.isEmpty()) LEDGER.remove(host);
		}
	}

	// ==================== @RemoldReinit 已应用记录（docs/initfix/03-runtime-driver.md §4） ====================

	private static String reinitAppliedGet(Class<?> host, String fieldName) {
		synchronized (REINIT_APPLIED) {
			Map<String, String> m = REINIT_APPLIED.get(host);
			return m == null ? null : m.get(fieldName);
		}
	}

	private static void reinitAppliedPut(Class<?> host, String fieldName, String key) {
		if (host == null || fieldName == null || key == null) return;
		synchronized (REINIT_APPLIED) {
			REINIT_APPLIED.computeIfAbsent(host, k -> new LinkedHashMap<>()).put(fieldName, key);
		}
	}

	static void reinitAppliedRemove(Class<?> host, String fieldName) {
		if (host == null) return;
		synchronized (REINIT_APPLIED) {
			Map<String, String> m = REINIT_APPLIED.get(host);
			if (m == null) return;
			m.remove(fieldName);
			if (m.isEmpty()) REINIT_APPLIED.remove(host);
		}
	}

	/** 整类记录作废：补丁没生效（redefine 失败 / 待补过期）时，下一轮必须重新写。 */
	static void reinitAppliedClear(Class<?> host) {
		if (host == null) return;
		REINIT_APPLIED.remove(host);
	}

	/**
	 * 只保留仍然带注解的字段的记录。
	 *
	 * <p>{@code ClassDiff} 看不见注解变化（它只比字段 {@code name+desc} 的增删与方法代码），
	 * 所以"注解被摘掉"这件事只能靠扫描新字节码得知。摘掉那轮字段根本不进候选集，
	 * 记录若留着，重新标注解时就会被判成"初值未变"而跳过。</p>
	 */
	private static void pruneReinitRecords(Class<?> host, Set<String> annotatedFields) {
		synchronized (REINIT_APPLIED) {
			Map<String, String> m = REINIT_APPLIED.get(host);
			if (m == null) return;
			m.keySet().retainAll(annotatedFields);
			if (m.isEmpty()) REINIT_APPLIED.remove(host);
		}
	}

	/**
	 * 该字段"初值表达式"的身份：{@code 模式|切片指纹}；切片不封闭时返回 {@code null}。
	 *
	 * <p>返回 {@code null} 的含义是"<b>判不了</b>"，调用方必须按"照常重置"处理 ——
	 * 这正是 {@code ConfigLoader.getDefaultTag()} 那一类字段的处境：切片本身一个字没变，
	 * 但被调用方的返回值可能变了，指纹看不出来。</p>
	 *
	 * <p>多根构造器口径不一致（各根的切片指纹不同）时同样返回 {@code null}：那种字段后续
	 * 会被 {@code fingerprintMismatchReason} 拒绝，不该在这里先替它做判断。</p>
	 */
	private static String reinitRecordKey(ReinitField mode, List<FieldExtract> extracts) {
		if (mode == null || extracts == null || extracts.isEmpty()) return null;
		String fp = null;
		for (FieldExtract fe : extracts) {
			if (!isClosedSlice(fe.instructions())) return null;
			String cur = fingerprint(fe.instructions());
			if (fp == null) fp = cur;
			else if (!fp.equals(cur)) return null;
		}
		return (mode.overwrite() ? "OVERWRITE" : "CONDITIONAL") + "|" + fp;
	}

	/**
	 * {@code @RemoldReinit} 本轮为什么写/为什么跳过（纯函数，供 {@code [REINIT] trigger} 日志与单测）。
	 *
	 * <p>用户对注解最常见的困惑是"我加了注解、只改方法体，为什么没重置"与"我改了个无关类，
	 * 为什么存量值被重置了"。触发原因是公开契约的一部分，必须能直接从日志读出来。</p>
	 *
	 * @param added            本轮新增字段（没有"上次"，必写）
	 * @param force            {@code -Dremold.agent.force_reinit=true}：忽略指纹强制重放
	 * @param closedExpression 初值切片封闭（值只由自身字节码决定）；否则判不了、照常重置
	 * @param appliedRecord    上次应用时记录的指纹；{@code null} 表示首轮/上次没写成
	 * @param currentKey       本次切片的指纹；{@code null} 表示不封闭
	 * @return 可直接进日志的短原因
	 */
	static String reinitTriggerReason(boolean added, boolean force, boolean closedExpression,
	                                  String appliedRecord, String currentKey) {
		if (added) return "new-field";
		if (force) return "forced (remold.agent.force_reinit)";
		if (!closedExpression || currentKey == null) return "open-expression (cannot prove value unchanged)";
		if (appliedRecord == null) return "first-time";
		return currentKey.equals(appliedRecord)
		 ? "unchanged-initializer (skipped)"
		 : "initializer-changed";
	}

	/**
	 * 封闭表达式判定：切片是否只由常量、算术、类型转换、栈操作与最终的那条写指令构成。
	 *
	 * <p>只有封闭切片的"字节码不变 ⟹ 值不变"才是定理。任何一次外部读取都会破坏它：</p>
	 * <ul>
	 *   <li>{@code INVOKESTATIC/INVOKEVIRTUAL/...}：被调用方的实现可能在本轮被改（跨类时
	 *       宿主类字节码完全看不出变化）；</li>
	 *   <li>{@code GETSTATIC}/{@code GETFIELD}：读的是运行期状态，可能被别处改写；</li>
	 *   <li>{@code INVOKEDYNAMIC}：同上，且引导方法可能读外部；</li>
	 *   <li>{@code NEW} 及其数组形态：新对象的身份每次不同，跳过与否都改变可观测行为，
	 *       交给调用方按"照常重置"处理更安全。</li>
	 * </ul>
	 *
	 * <p>这是<b>保守</b>判定：{@code new StringBuilder("a").toString()} 这种其实封闭的形态
	 * 也会被判为开放，代价只是"仍然每轮重置"（即改造前的行为），不会漏写。</p>
	 */
	private static boolean isClosedSlice(List<AbstractInsnNode> insns) {
		if (insns == null) return false;
		for (AbstractInsnNode n : insns) {
			switch (n.getOpcode()) {
				case Opcodes.GETSTATIC:
				case Opcodes.GETFIELD:
				case Opcodes.INVOKEVIRTUAL:
				case Opcodes.INVOKESPECIAL:
				case Opcodes.INVOKESTATIC:
				case Opcodes.INVOKEINTERFACE:
				case Opcodes.INVOKEDYNAMIC:
				case Opcodes.NEW:
				case Opcodes.NEWARRAY:
				case Opcodes.ANEWARRAY:
				case Opcodes.MULTIANEWARRAY:
				case Opcodes.IALOAD:
				case Opcodes.LALOAD:
				case Opcodes.FALOAD:
				case Opcodes.DALOAD:
				case Opcodes.AALOAD:
				case Opcodes.BALOAD:
				case Opcodes.CALOAD:
				case Opcodes.SALOAD:
				case Opcodes.MONITORENTER:
				case Opcodes.MONITOREXIT:
				case Opcodes.ATHROW:
					return false;
				default:
					break;
			}
		}
		return true;
	}

	/**
	 * 统一的补丁 bridge bootstrap：{@link HotswapBridge} 按 {@code kind} 分派到
	 * protected 成员桥接或新增字段的条件 CAS 写。
	 * <p>bsmArgs 布局：{@code [int kind, int opcode, Class owner, Class host]}。</p>
	 */
	static final Handle BRIDGE_BSM = new Handle(
	 Opcodes.H_INVOKESTATIC,
	 Type.getInternalName(HotswapBridge.class),
	 "bootstrap",
	 "(Ljava/lang/invoke/MethodHandles$Lookup;"
	 + "Ljava/lang/String;"
	 + "Ljava/lang/invoke/MethodType;"
	 + "I"
	 + "I"
	 + "Ljava/lang/Class;"
	 + "Ljava/lang/Class;"
	 + ")Ljava/lang/invoke/CallSite;",
	 false);

	/**
	 * L1 存量初值重放的 bootstrap：在 {@link #BRIDGE_BSM} 的 bsmArgs 后追加旧初值常量。
	 * <p>bsmArgs 布局：{@code [int kind, int opcode, Class owner, Class host, Object expected]}。</p>
	 */
	static final Handle BRIDGE_BSM_EXPECTED = new Handle(
	 Opcodes.H_INVOKESTATIC,
	 Type.getInternalName(HotswapBridge.class),
	 "bootstrapExpected",
	 "(Ljava/lang/invoke/MethodHandles$Lookup;"
	 + "Ljava/lang/String;"
	 + "Ljava/lang/invoke/MethodType;"
	 + "I"
	 + "I"
	 + "Ljava/lang/Class;"
	 + "Ljava/lang/Class;"
	 + "Ljava/lang/Object;"
	 + ")Ljava/lang/invoke/CallSite;",
	 false);

	/**
	 * 遮蔽新增字段的条件 CAS：expected 由 bootstrap 在运行期从同一实例的祖先字段读出。
	 * <p>bsmArgs 布局：{@code [int kind, int opcode, Class owner, Class host, Class ancestorOwner]}。</p>
	 */
	static final Handle BRIDGE_BSM_ANCESTOR = new Handle(
	 Opcodes.H_INVOKESTATIC,
	 Type.getInternalName(HotswapBridge.class),
	 "bootstrapAncestor",
	 "(Ljava/lang/invoke/MethodHandles$Lookup;"
	 + "Ljava/lang/String;"
	 + "Ljava/lang/invoke/MethodType;"
	 + "I"
	 + "I"
	 + "Ljava/lang/Class;"
	 + "Ljava/lang/Class;"
	 + "Ljava/lang/Class;"
	 + ")Ljava/lang/invoke/CallSite;",
	 false);

	private record BuiltPatch(byte[] bytes, PatchPlan plan, PatchReport report,
	                          Map<String, String> replayOwners) {
		boolean hasPatch() { return bytes != null; }
	}

	/** L1 档在代际对账表里的键前缀（L1 只写宿主自身字段，键就是字段名）。 */
	private static final String L1_GATE_PREFIX = "L1:";

	/** A 档在代际对账表里的键前缀（键是 {@link ApplyInitializerReplay.ReplayField#key()}）。 */
	private static final String APPLY_GATE_PREFIX = "A:";

	/**
	 * 一条重放候选的代际对账结论（{@code docs/vcs/05-generational-field-ledger.md} §3.3）。
	 *
	 * <p>只有 {@link GenerationalReplayGate.Mode#FACT} 会产出本记录：比较侧 {@code expectedConstant}
	 * 与写入侧 {@code writeConstant} 都是<b>同一条</b>事实的两个字面量解出来的 bootstrap 常量，
	 * 不存在第二期望来源（D12）。</p>
	 */
	private record GenerationalReplay(String oldLiteral, String newLiteral, String literalKind,
	                                  String fieldDesc, Object expectedConstant, Object writeConstant,
	                                  long fromGeneration) { }

	/** 常量直写批通道描述（供 {@link HotswapBridge#constantBatchLoop} 复用常驻 C2 循环）。 */
	public record ConstantInitSpec(String fieldDesc, boolean forceWrite, Object value) { }

	/**
	 * 单个字段的补丁任务（{@code docs/initfix/03-runtime-driver.md} §2）。
	 * @param methodName   伴生类里承载该字段的独立静态方法名
	 * @param fieldName    字段名（报告/失败台账的键）
	 * @param isStatic     静态字段（方法无参，整个补丁只调用一次）
	 * @param dependencies 本字段切片读取的其它<b>本轮受补</b>字段
	 * @param selfOnly     {@code @RemoldReinit(scope = SELF)}：只对运行时类恰好等于宿主的实例执行，
	 *                     枚举出来的子类/匿名子类实例必须跳过（{@code docs/initfix/03-runtime-driver.md} §4）
	 */
	public record FieldPatchTask(
	 String methodName, String fieldName, boolean isStatic, Set<String> dependencies,
	 Set<InitFixBatchGraph.FieldKey> batchDependencies,
	 boolean selfOnly, boolean replay, String batchMethodName, ConstantInitSpec constantInit) {

		public FieldPatchTask {
			dependencies = dependencies == null ? Set.of() : Set.copyOf(dependencies);
			batchDependencies = batchDependencies == null ? Set.of() : Set.copyOf(batchDependencies);
		}

		/** Default-scope task with no batch-level dependencies. */
		public FieldPatchTask(String methodName, String fieldName, boolean isStatic,
		                      Set<String> dependencies) {
			this(methodName, fieldName, isStatic, dependencies, Set.of(), false, false, null, null);
		}

		/** Default-scope task with explicit batch-level dependencies. */
		public FieldPatchTask(String methodName, String fieldName, boolean isStatic,
		                      Set<String> dependencies, Set<InitFixBatchGraph.FieldKey> batchDependencies) {
			this(methodName, fieldName, isStatic, dependencies, batchDependencies, false, false, null, null);
		}

		/** Default scope + non-replay task. */
		public FieldPatchTask(String methodName, String fieldName, boolean isStatic,
		                      Set<String> dependencies, boolean selfOnly) {
			this(methodName, fieldName, isStatic, dependencies, Set.of(), selfOnly, false, null, null);
		}

		/** Compatibility constructor without batch method name. */
		public FieldPatchTask(String methodName, String fieldName, boolean isStatic,
		                      Set<String> dependencies, boolean selfOnly, boolean replay) {
			this(methodName, fieldName, isStatic, dependencies, Set.of(), selfOnly, replay, null, null);
		}

		/** Compatibility constructor with batch method name. */
		public FieldPatchTask(String methodName, String fieldName, boolean isStatic,
		                      Set<String> dependencies, boolean selfOnly, boolean replay,
		                      String batchMethodName) {
			this(methodName, fieldName, isStatic, dependencies, Set.of(), selfOnly, replay, batchMethodName, null);
		}
	}

	/** 合并多个无依赖批循环任务为单次堆数组遍历的组描述。 */
	public record FusedBatchGroup(String methodName, int[] taskIndices) {
		public FusedBatchGroup {
			taskIndices = taskIndices == null ? new int[0] : taskIndices.clone();
		}

		public int[] taskIndices() {
			return taskIndices.clone();
		}
	}

	/**
	 * 一次热更的补丁计划：按拓扑序排列的逐字段任务（{@code docs/initfix/03-runtime-driver.md} §1 每字段独立静态方法 +
	 * {@code docs/initfix/03-runtime-driver.md} §2 宿主驱动调度）。
	 *
	 * @param reinitFields 本轮真正进了补丁的 {@code @RemoldReinit} 字段 -> 写入模式名
	 *                     （{@code OVERWRITE}/{@code CONDITIONAL}），按注解声明顺序保序。
	 *                     只收"注解确实兑现了"的字段：被安全门拒绝的不会出现在这里。
	 *                     用途见 {@link InitFix#reinitHint}（{@code docs/initfix/03-runtime-driver.md} §4）。
	 * @param applyFields  A 档 Kotlin apply 重放：候选键 -> 重放描述
	 *                     （{@code docs/initfix/13-kotlin-apply-replay.md}）。与普通静态字段任务分开，
	 *                     因为写入目标是<b>另一个类</b>（apply 对象类型）的字段；保序副本决定补丁方法名
	 *                     （{@code applyReplay$i}）与驱动顺序。
	 */
	public record PatchPlan(
	 List<FieldPatchTask> instanceTasks, List<FieldPatchTask> staticTasks,
	 Map<String, String> reinitFields,
	 Map<String, ApplyInitializerReplay.ReplayField> applyFields,
	 List<FusedBatchGroup> fusedBatchGroups) {

		public static final PatchPlan EMPTY =
		 new PatchPlan(List.of(), List.of(), Map.of(), Map.of(), List.of());

		public PatchPlan {
			instanceTasks = instanceTasks == null ? List.of() : List.copyOf(instanceTasks);
			staticTasks = staticTasks == null ? List.of() : List.copyOf(staticTasks);
			// 保序副本：日志里字段的先后必须与注解声明顺序一致（Map.copyOf 不保证顺序）。
			reinitFields = reinitFields == null || reinitFields.isEmpty()
			 ? Map.of()
			 : Collections.unmodifiableMap(new LinkedHashMap<>(reinitFields));
			applyFields = applyFields == null || applyFields.isEmpty()
			 ? Map.of()
			 : Collections.unmodifiableMap(new LinkedHashMap<>(applyFields));
			fusedBatchGroups = fusedBatchGroups == null ? List.of() : List.copyOf(fusedBatchGroups);
		}

		/** 兼容不带融合批组的构造。 */
		public PatchPlan(List<FieldPatchTask> instanceTasks, List<FieldPatchTask> staticTasks,
		                 Map<String, String> reinitFields,
		                 Map<String, ApplyInitializerReplay.ReplayField> applyFields) {
			this(instanceTasks, staticTasks, reinitFields, applyFields, List.of());
		}

		/** 兼容不带 apply 重放的构造。 */
		public PatchPlan(List<FieldPatchTask> instanceTasks, List<FieldPatchTask> staticTasks,
		                 Map<String, String> reinitFields) {
			this(instanceTasks, staticTasks, reinitFields, Map.of(), List.of());
		}

		/** 兼容不带注解信息的构造：本轮没有 {@code @RemoldReinit} 参与时用它。 */
		public PatchPlan(List<FieldPatchTask> instanceTasks, List<FieldPatchTask> staticTasks) {
			this(instanceTasks, staticTasks, Map.of(), Map.of(), List.of());
		}

		public boolean isEmpty() {
			return instanceTasks.isEmpty() && staticTasks.isEmpty() && applyFields.isEmpty();
		}

		/** apply 重放同样是"只跑一次"的静态任务：没有实例可枚举时也必须执行。 */
		public boolean hasStatic() { return !staticTasks.isEmpty() || !applyFields.isEmpty(); }
		public boolean hasInstance() { return !instanceTasks.isEmpty(); }

		public List<FieldPatchTask> allTasks() {
			List<FieldPatchTask> all = new ArrayList<>(staticTasks.size() + instanceTasks.size());
			all.addAll(staticTasks);
			all.addAll(instanceTasks);
			return all;
		}
	}

	/**
	 * 哈希语义告警（{@code docs/status.md} 风险 10）。
	 *
	 * <p>新增字段被 {@code hashCode}/{@code equals}/{@code compareTo} 读取时，已放进哈希容器的实例会落错桶。
	 * 这里刻意<b>只告警不拒绝</b>：破坏的成因是哈希公式变了，拒绝补丁既不能修复容器，还会让新代码读到 0。
	 * 用户需要知道的是"该重建容器或重启"，而不是"这个字段没补上"。</p>
	 */
	private static void warnHashSemanticDependencies(ClassNode newClass, byte[] newBytes, Set<String> addedInstanceFields) {
		List<HashSemantics.Finding> findings = newClass != null
			? HashSemantics.findDependencies(newClass, addedInstanceFields)
			: HashSemantics.findDependencies(newBytes, addedInstanceFields);
		if (findings.isEmpty()) return;
		RemoldAgent.warn("[HASH-SEMANTICS] " + HashSemantics.describe(findings)
		                 + "; instances already stored in HashMap/HashSet/TreeMap may end up in incorrect buckets or comparison order, "
		                 + "causing get/contains/remove to fail silently. Patches will still be applied, "
		                 + "but please rebuild the affected containers or restart.");
	}

	/**
	 * 已装配、等待重定义生效后执行的补丁。
	 *
	 * <p><b>不持有实例快照</b>：存活实例在 {@code applyPatch} 里、重定义生效<b>之后</b>才枚举。
	 * 早期版本在 {@code transform}/{@code beforeRedefine} 阶段先拍快照，快照完成到重定义生效之间
	 * 新建的实例永远进不了快照，其新增字段永久停在类型默认值（{@code docs/status.md} 风险 6）。
	 * 重定义之后枚举则天然完备：此后不可能再产生旧布局的实例，而新实例由新构造器初始化。</p>
	 */
	/**
	 * @param replayOwners L1 重放字段 -> 事实所属类（内部名）。L1 写的是<b>继承</b>字段，事实与台账键都在
	 *                     声明类上，运行期记账（CAS 未命中 / OVERWRITE 闭环）必须用同一个键。
	 */
	record PendingPatch(
	 PatchPlan plan,
	 byte[] bytes,
	 long createdNanos,
	 boolean twoPhase,
	 Map<String, String> replayOwners) {
	}

	record ProtectedAccess(int opcode, String owner, String name, String desc) { }

	record FieldExtract(
	 List<AbstractInsnNode> instructions,
	 Map<AbstractInsnNode, ProtectedAccess> protectedAccesses,
	 boolean dependsOnParam,
	 boolean fromRootCtor,
	 Set<AbstractInsnNode> originalInsns) { }

	record ParamField(FieldNode field, int slot) { }

	/**
	 * "源码里确实写了初值，但切片提取不到这条表达式"这一族的固定前缀。
	 *
	 * <p>用它区分两种完全不同的缺失：<b>这一族</b>意味着初值表达式存在、只是超出白名单（分支等），
	 * 存量实例必然只会读到零值 —— Kotlin 非空 {@code val} 会因此静默变成 {@code null}；而"初值来自
	 * 构造器参数 / 无初值"属另一回事，默认零值本来就是源码语义。前缀只在这里定义一次。</p>
	 */
	public static final String UNEXPRESSIBLE_INIT_PREFIX = "no safe initialization expression";

	/**
	 * 本轮新增字段里「源码写了初值、但切片提取不到」的那些：{@code 字段 -> 原因}。
	 *
	 * <p><b>为什么必须由调用方据此拒绝整类</b>：这类字段的初值表达式真实存在（Kotlin
	 * {@code listOf("a","b")} 的 vararg 数组构造、{@code ?:} / if / when 的分支），只是超出
	 * {@code InitFixSliceExtractor} 的直线白名单。重定义照常生效、补丁却没有，存量实例上该字段
	 * 停在类型零值 —— 对 Kotlin 的非空 {@code val} 就是"声明非空却读到 null"，NPE 会出现在离热更
	 * 很远的地方。因此调用方必须把这类类整批拒绝并提示重启，而不是只补一半。</p>
	 *
	 * <p>只认 {@link InitFixSafetyGate#UNEXPRESSIBLE_INIT_PREFIX} 这一族：初值来自构造器参数、
	 * 或压根没写初值的字段不在此列（默认零值本来就是源码语义）。</p>
	 *
	 * @param host 已加载宿主类
	 * @param diff 本轮的类差异（只取 {@code addedInstanceFields} / {@code addedStaticFields}）
	 * @return 字段名 -> 拒绝原因；无此类字段时返回空表
	 */
	public static Map<String, String> unexpressibleNewFieldInits(Class<?> host, ClassDiff diff) {
		if (host == null) return Map.of();
		return classifyUnexpressibleNewFieldInits(getLastReport(host), diff);
	}

	/** 纯函数形态（单测入口）：给定一份 {@link PatchReport} 与类差异，挑出不可提取初值的新增字段。 */
	public static Map<String, String> classifyUnexpressibleNewFieldInits(PatchReport report, ClassDiff diff) {
		if (report == null || diff == null) return Map.of();
		Map<String, String> out = new LinkedHashMap<>();
		collectUnexpressible(out, report.instanceFields(), diff.addedInstanceFields);
		collectUnexpressible(out, report.staticFields(), diff.addedStaticFields);
		return out;
	}

	private static void collectUnexpressible(Map<String, String> out,
	                                         Map<String, FieldDecision> decisions,
	                                         Set<String> addedFields) {
		if (decisions == null || decisions.isEmpty() || addedFields == null || addedFields.isEmpty()) return;
		for (String field : addedFields) {
			FieldDecision decision = decisions.get(field);
			if (decision == null || decision.status() != FieldStatus.REJECTED) continue;
			String reason = decision.reason();
			// Only genuine unsliceable initializer expressions ("no safe initialization expression in <init>...")
			// trigger [INITFIX-REFUSE]; fields with no initializer in constructors/clinit (e.g. lateinit var or
			// plain Java fields populated via setter) leave the JVM default value by design.
			if (reason == null || !reason.startsWith(InitFix.UNEXPRESSIBLE_INIT_PREFIX + " in ")) continue;
			out.put(field, reason);
		}
	}

	/**
	 * 一次构造器参数扫描的结果。
	 * @param accepted 可用作参数回溯的槽位映射
	 * @param rejected 被拒的槽位 -> 拒绝原因（按槽位记录，供 {@link InitFixSliceExtractor#checkSafe}
	 *                 把"真实原因"透传进 {@link PatchReport}，而不是笼统的
	 *                 "depends on local variables"）
	 */
	record ParamScan(Map<Integer, ParamField> accepted, Map<Integer, String> rejected) {
		static final ParamScan EMPTY = new ParamScan(Map.of(), Map.of());
	}

	/** 单个字段的放行决策状态。 */
	public enum FieldStatus {
		/** 已生成补丁代码。 */
		ACCEPTED,
		/**
		 * T0 零值等价（{@code docs/initfix/01-safety-gate.md} §1）：存量实例与静态环境本来就是该类型的
		 * 默认值，零开销放行，不生成补丁，也不告警。
		 */
		NOTHING_TO_PATCH,
		/** 未通过安全门，未生成补丁；{@link FieldDecision#reason()} 给出原因。 */
		REJECTED
	}

	/**
	 * 单个字段的放行决策。
	 * @param status   放行状态
	 * @param reason   被拒时的原因（{@link FieldStatus#REJECTED} 才有意义）
	 * @param warnings 已放行但带已知风险的说明（例如 {@code docs/initfix/03-runtime-driver.md} §4 注解豁免了"构造器里用过该字段"
	 *                 这条门时，构造器里的那部分用法不会被补丁重放）。空表示无风险。
	 */
	public record FieldDecision(FieldStatus status, String reason, List<String> warnings) {
		public FieldDecision {
			warnings = warnings == null ? List.of() : List.copyOf(warnings);
		}

		public static final FieldDecision ACCEPTED         = new FieldDecision(FieldStatus.ACCEPTED, null, List.of());
		public static final FieldDecision NOTHING_TO_PATCH =
		 new FieldDecision(FieldStatus.NOTHING_TO_PATCH, null, List.of());

		public static FieldDecision rejected(String reason) {
			return new FieldDecision(FieldStatus.REJECTED, reason, List.of());
		}

		/** 放行但带风险标记（报告里可见，同时会打一条 warn 日志）。 */
		public static FieldDecision accepted(String warning) {
			return new FieldDecision(FieldStatus.ACCEPTED, null,
			 warning == null ? List.of() : List.of(warning));
		}

		/** @return 是否已为该字段生成补丁代码 */
		public boolean accepted() {
			return status == FieldStatus.ACCEPTED;
		}
	}

	/**
	 * 一次 transform 的完整报告。
	 * <p>不持有宿主类引用，避免与 {@link #REPORTS} 的弱键形成强引用循环。</p>
	 *
	 * @param replayFields L1 存量初值重放字段 -> 结果（{@code replayed} 或 {@code rejected: 原因}）。
	 *                     独立于 {@link #instanceFields}：L1 不参与 {@code patchGenerated} 语义，
	 *                     也不写 {@code FieldLedger}（{@code docs/initfix/12-L1-existing-initializer-replay.md}）。
	 * @param applyReplayFields A 档 Kotlin apply 重放：{@code dest -> 目标类.字段} -> 结果。
	 *                     与 {@link #replayFields} 分开：写入目标是 apply 对象类型的字段，不是宿主的字段，
	 *                     台账与告警口径也不同（{@code docs/initfix/13-kotlin-apply-replay.md}）。
	 */
	public record PatchReport(
	 Map<String, FieldDecision> instanceFields,
	 Map<String, FieldDecision> staticFields,
	 boolean patchGenerated,
	 Map<String, String> replayFields,
	 Map<String, String> applyReplayFields,
	 Map<String, String> recoveredFields
	) {
		public PatchReport {
			replayFields = replayFields == null || replayFields.isEmpty()
			 ? Map.of()
			 : Collections.unmodifiableMap(new LinkedHashMap<>(replayFields));
			applyReplayFields = applyReplayFields == null || applyReplayFields.isEmpty()
			 ? Map.of()
			 : Collections.unmodifiableMap(new LinkedHashMap<>(applyReplayFields));
			recoveredFields = recoveredFields == null || recoveredFields.isEmpty()
			 ? Map.of()
			 : Collections.unmodifiableMap(new LinkedHashMap<>(recoveredFields));
		}

		/** 兼容不带 apply 重放诊断的构造。 */
		public PatchReport(Map<String, FieldDecision> instanceFields,
		                   Map<String, FieldDecision> staticFields,
		                   boolean patchGenerated,
		                   Map<String, String> replayFields) {
			this(instanceFields, staticFields, patchGenerated, replayFields, Map.of(), Map.of());
		}

		/** 兼容不带重放诊断的构造：默认路径（L1/A 档关闭）不会产生 replay 字段。 */
		public PatchReport(Map<String, FieldDecision> instanceFields,
		                   Map<String, FieldDecision> staticFields,
		                   boolean patchGenerated) {
			this(instanceFields, staticFields, patchGenerated, Map.of(), Map.of(), Map.of());
		}

		/** L1 是否真的生成了至少一个重放补丁。 */
		public boolean replayGenerated() {
			for (String result : replayFields.values()) {
				if ("replayed".equals(result)) return true;
			}
			return false;
		}

		/** A 档 apply 重放是否真的生成了至少一个补丁。 */
		public boolean applyReplayGenerated() {
			for (String result : applyReplayFields.values()) {
				if ("replayed".equals(result)) return true;
			}
			return false;
		}

		/**
		 * 代际对账后仍未闭环的字段名（运行期 CAS 未命中，或消费侧 fail-closed 拒绝）。
		 *
		 * <p>这些字段的值<b>没有</b>被写成目标代的值，且台账（
		 * {@link GenerationalFieldLedger#markUnclosed}）里留有债务；恢复只能靠显式重建或
		 * {@code @RemoldReinit(OVERWRITE)}（{@code docs/vcs/05-generational-field-ledger.md} §3.3 / G6）。</p>
		 */
		public List<String> unclosedReplayFields() {
			List<String> out = new ArrayList<>();
			for (Map.Entry<String, String> e : replayFields.entrySet()) {
				if (isSkippedReplayResult(e.getValue())) out.add(e.getKey());
			}
			for (Map.Entry<String, String> e : applyReplayFields.entrySet()) {
				if (isSkippedReplayResult(e.getValue())) out.add(e.getKey());
			}
			return out;
		}

		/**
		 * 经 {@code @RemoldReinit(OVERWRITE)} 或显式重建闭环的字段（字段名 -> 闭环方式）。
		 *
		 * <p>闭环意味着台账里那条未闭环债务已被 {@link GenerationalFieldLedger#markClosed} 清掉：
		 * 下一代的 {@code resolve} 不再把它当成"还欠着"（G7）。</p>
		 */
		public Map<String, String> recoveredFields() {
			return recoveredFields;
		}

		private static boolean isSkippedReplayResult(String result) {
			return result != null && result.startsWith(SKIP_RESULT_PREFIX);
		}
	}

	/** 重放结果里"跳过了、没写成"的统一前缀（运行期 CAS 未命中也用它，G6）。 */
	public static final String SKIP_RESULT_PREFIX = "skipped: ";

	/** 经 {@code @RemoldReinit(OVERWRITE)} 闭环的字段在 {@link PatchReport#recoveredFields()} 里的取值。 */
	public static final String RECOVERED_BY_OVERWRITE = "recovered: overwrite";

	public static PatchReport getLastReport(Class<?> host) {
		if (host == null) return null;
		return REPORTS.get(host);
	}

	/**
	 * 本轮补丁是否要求"重定义生效 → 补丁完成"之间不能有新方法体上线（两阶段读穿保护，
	 * {@code docs/initfix/04-target-design.md} §1）。
	 *
	 * <p>判据认两类任务：</p>
	 * <ol>
	 *   <li><b>新增/类型变更字段</b>：补丁前处于类型默认值（{@code null}/{@code 0}），新方法体读到会 NPE
	 *       或静默读零；</li>
	 *   <li><b>既有静态字段的重写</b>（{@code @RemoldReinit} / 重放写任务，且不是 {@code ConstantValue}
	 *       编译期字面量常量）：补丁写内存不会通知 JIT，单阶段下窗口期内重编译的读者可能一直读旧值；
	 *       阶段 C 的第二次 redefine 作为补丁后的 deopt 屏障。实测见
	 *       {@code tests/static-rewrite-probe}。</li>
	 * </ol>
	 * <p>实例字段的既有值重写（{@code @RemoldReinit} 实例字段 / L1 实例重放）默认值是旧值而不是零值，
	 * 不构成读穿，跟随单阶段路径即可；A 档 apply 重放写入的是<b>另一个类型</b>的字段，宿主阶段 C 的
	 * deopt 覆盖不到那个类型，故不纳入本判据。</p>
	 */
	/**
	 * 记录一档重放事实（{@code docs/vcs/01-bytecode-changeset-vcs.md} §3.8）。
	 *
	 * <p>只在批次分析期内累积：事实来自纯函数计划，不依赖运行期写回结果。字面量按描述符定型，
	 * {@code float}/{@code double} 存原始位模式而不是十进制近似。</p>
	 */
	/**
	 * 该键的代际事实是否被窗口裁剪过（{@code docs/vcs/07} L5）。
	 *
	 * <p>裁剪之后 {@code resolve} 可能返回 {@code MISS_NO_FACT}，而它的语义是"索引里没有证据"。
	 * 若真相是"曾经有、被裁掉了"，继续按 OBLIVIOUS 放行就是把遗忘当成从未发生。</p>
	 */
	private static boolean prunedFromLedger(ClassLoader loader, String className, String field) {
		if (className == null || field == null) return false;
		try {
			return GenerationalFieldLedger.INSTANCE.forgottenWriters(loader,
			 new GenerationalFieldLedger.LedgerKey(
			  AlignmentMetaStore.INSTANCE.loaderScopeId(loader),
			  Names.internal(className), field)) > 0;
		} catch (Throwable t) {
			// 查询失败按"被裁剪过"处理：方向保守，绝不因为查不到就放行。
			return true;
		}
	}

	private static void captureReplayFacts(Class<?> host,
	                                       Map<String, InitializerReplay.ReplayField> l1,
	                                       Map<String, ApplyInitializerReplay.ReplayField> apply) {
		BatchContext context = BATCH_CONTEXT.get();
		if (context == null) return;
		List<Changeset.ReplayFact> sink = context.replayFacts();
		if (l1 != null) {
			for (InitializerReplay.ReplayField field : l1.values()) sink.add(l1ReplayFact(field));
		}
		if (apply != null) {
			for (ApplyInitializerReplay.ReplayField field : apply.values()) {
				sink.add(new Changeset.ReplayFact(dotted(field.targetClass()), field.fieldName(), field.fieldDesc(),
				 "A", literalOf(field.expected(), field.oldText(), field.fieldDesc()),
				 literalOf(field.newRaw(), field.newText(), field.fieldDesc()),
				 literalKindOf(field.fieldDesc()), field.dest() + ":" + field.destDesc(), "", ""));
			}
		}
	}

	/**
	 * {@code L1} 档提交级事实（{@code docs/vcs/01-bytecode-changeset-vcs.md} §3.8）。
	 *
	 * <p>两侧字面量都取自字节码常量的自然值，<b>不</b>从 {@code oldText}/{@code newText} 反推：
	 * 那两列是给人读的十进制渲染（{@code -0.0f}、NaN payload 与十进制舍入都会在往返中失真），
	 * 只保证一侧规范会让该字段的多代重放整体不可用。</p>
	 */
	static Changeset.ReplayFact l1ReplayFact(InitializerReplay.ReplayField field) {
		return new Changeset.ReplayFact(dotted(field.declaringClass()), field.name(), field.desc(),
		 "L1", literalOf(field.expected(), field.oldText(), field.desc()),
		 literalOf(field.newExpected(), field.newText(), field.desc()),
		 literalKindOf(field.desc()), "", "", "");
	}

	/** 记录 {@code @RemoldReinit} 档事实：有 scope / mode，但按契约不要求字面量。 */
	private static void captureReinitFacts(Class<?> host, Map<String, ReinitField> reinit) {
		BatchContext context = BATCH_CONTEXT.get();
		if (context == null || reinit == null || reinit.isEmpty()) return;
		String owner = host.getName();
		for (Map.Entry<String, ReinitField> e : reinit.entrySet()) {
			ReinitField rf = e.getValue();
			context.replayFacts().add(new Changeset.ReplayFact(owner, e.getKey(), "", "REINIT",
			 null, null, "NONE", "", rf.selfOnly() ? "SELF" : "INCLUDE_SUBCLASSES",
			 rf.overwrite() ? "OVERWRITE" : "CONDITIONAL"));
		}
	}

	private static String dotted(String internalOrDotted) {
		return internalOrDotted == null ? "" : internalOrDotted.replace('/', '.');
	}

	/**
	 * 字面量定型（{@code docs/vcs/01-bytecode-changeset-vcs.md} §3.8）。
	 *
	 * <p>八种基本类型都有台账口径：{@code Z/B/S/C} 与 {@code I/J} 同档（编译期常量、{@code ConstantValue}
	 * 语义一致），{@code F/D} 走原始位模式。{@code L1} 与 A 档都放行全部基本类型，消费侧
	 * （{@code GenerationalFieldLedger.resolve}）见 {@code NONE} 一律 {@code REFUSED_NONE_LITERAL}；
	 * 把 {@code Z/B/S/C} 留在 {@code NONE} 会让这四类的跨代对账整类失效。</p>
	 */
	static String literalKindOf(String desc) {
		if (desc == null || desc.isEmpty()) return "NONE";
		switch (desc.charAt(0)) {
			case 'Z': return "BOOLEAN";
			case 'B': return "BYTE";
			case 'S': return "SHORT";
			case 'C': return "CHAR";
			case 'I': return "INT";
			case 'J': return "LONG";
			case 'F': return "FLOAT_BITS";
			case 'D': return "DOUBLE_BITS";
			case 'L': return "Ljava/lang/String;".equals(desc) ? "STRING" : "NONE";
			default: return "NONE";
		}
	}

	/**
	 * 字面量定型 + 规范化：{@code float}/{@code double} 一律取原始位模式十六进制，
	 * 避免十进制回读等价造成的对账漂移（§3.8）。
	 *
	 * <p>{@code Z/B/S/C} 的规范形与 {@code Boolean/Byte/Short/Character.toString} 一致
	 * （{@code true/false}、十进制、单字符），并且同样接受<b>常量池里的原始 Integer</b>：
	 * 这样"自然装箱值"（{@code L1} 的 {@code expected}）与"未装箱的字节码常量"（A 档的
	 * {@code newRaw}）落到同一段文本，两侧同源——否则 {@code boolean} 会写成
	 * {@code "true"} vs {@code "1"}，跨代 CAS 永远对不上。</p>
	 */
	static String literalOf(Object value, String fallback, String desc) {
		String kind = literalKindOf(desc);
		switch (kind) {
			case "FLOAT_BITS":
				if (value instanceof Float f) return String.format("0x%08x", Float.floatToRawIntBits(f));
				break;
			case "DOUBLE_BITS":
				if (value instanceof Double d) return String.format("0x%016x", Double.doubleToRawLongBits(d));
				break;
			case "BOOLEAN":
				if (value instanceof Boolean b) return b.toString();
				if (value instanceof Number n) return n.intValue() != 0 ? "true" : "false";
				break;
			case "BYTE":
				if (value instanceof Number n) return Byte.toString(n.byteValue());
				break;
			case "SHORT":
				if (value instanceof Number n) return Short.toString(n.shortValue());
				break;
			case "CHAR":
				if (value instanceof Character c) return c.toString();
				if (value instanceof Number n) return String.valueOf((char) n.intValue());
				break;
			default:
				break;
		}
		if (value != null) return String.valueOf(value);
		return fallback == null ? "null" : fallback;
	}

	/**
	 * 把 {@link GenerationalFieldLedger#resolve} 接进重放决策（
	 * {@code docs/vcs/05-generational-field-ledger.md} §3.3 / A14 / G6～G8）。
	 *
	 * <p><b>为什么要接</b>：L1 与 A 档的 {@code KIND_CONDITIONAL_EXPECTED} 期望值原本取自当轮
	 * {@code ClassDiff} 的旧初值。一旦发生过回退，那份 {@code ClassDiff} 描述的是"回退后 JVM 在跑的
	 * 字节"，而堆上的值可能是<b>被回退掉的污染代</b>写入的——用当轮旧初值做期望，会把污染代的写入值
	 * 当成"已经是目标代的值"，回退在堆侧静默 no-op。代际台账给出的 {@code (oldLiteral, newLiteral)}
	 * 一对才是可校验的期望与写入值（§3.3 / D12）。</p>
	 *
	 * <p><b>三种结论</b>：{@code OBLIVIOUS} 不动（索引里没有这条键的代际证据，沿用当轮 {@code ClassDiff}，
	 * 与引入代际对账前逐字节一致）；{@code FACT} 记一条 {@link GenerationalReplay}，发射时改写期望与写入值；
	 * {@code REFUSED} 把该字段从重放候选里摘掉、记 {@code rejected:} 决策（走既有 warn 出口）、并把债务
	 * 落到 {@link GenerationalFieldLedger#markUnclosed}（<b>不</b>碰瞬时补丁槽 {@link #PENDING}）。</p>
	 *
	 * @return {@code L1:/A: + key} -> 事实带出的期望与写入常量（只有 {@code FACT} 才有条目）
	 */
	/**
	 * 该键是否存在"确实写进堆"的记录（{@code OVERWRITE} / 恢复写入）。
	 *
	 * <p>判据只有一类可信来源：调用方在真正写出之后落的 {@code REINIT_APPLIED} 行。没写过就说明
	 * 所有 {@code ReplayFact} 都只是理论值（§4-R3），不能拿来当下一轮的期望。</p>
	 */
	private static boolean hasAppliedWrite(ClassLoader loader, String className, String field, long generation) {
		if (generation <= 0L) return false;
		for (GenerationalFieldLedger.ReinitApplied applied
		 : GenerationalFieldLedger.INSTANCE.reinitApplied(loader, className, field)) {
			if (applied.gen() == null || applied.gen().seq() != generation) continue;
			String result = applied.result();
			if (result != null && result.startsWith("written")) return true;
		}
		return false;
	}

	private static Map<String, GenerationalReplay> gateGenerationalReplay(
	 Class<?> host, String className,
	 Map<String, InitializerReplay.ReplayField> replayFields,
	 Map<String, ApplyInitializerReplay.ReplayField> applyFields,
	 Map<String, String> replayDecisions,
	 Map<String, String> applyDecisions) {

		Map<String, GenerationalReplay> out = new LinkedHashMap<>();
		if (replayFields.isEmpty() && applyFields.isEmpty()) return out;
		ClassLoader loader = host == null ? null : host.getClassLoader();
		long scope = AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		Optional<Changeset> head = ChangesetLog.INSTANCE.head(scope);
		// 本进程还没为这个 scope 提交过代：没有代际坐标，沿用当轮 ClassDiff（引入前行为）。
		if (head.isEmpty()) return out;
		long targetSeq = head.get().seq();
		// <b>只在回退上下文里改判</b>（§3.1："代际上下文只影响第二行"）。当轮 ClassDiff 的两侧只有在
		// "堆上的值可能是被回退掉那代写的" 时才不可信；正向代里 old 侧就是当前字节的初值，本身就正确，
		// 用代际事实去顶替它会让期望整体晚一代（每次都被判"不匹配"），把正常重放变成长期停滞。
		// 判据：HEAD 本身是回退代，或这条键还欠着未闭环债务（判过之后要保持 fail-closed 直到显式恢复）。
		boolean revertContext = head.get().revertedToSeq() > 0L;
		String display = className.replace('/', '.');

		for (String field : new ArrayList<>(replayFields.keySet())) {
			InitializerReplay.ReplayField replay = replayFields.get(field);
			// L1 的字段是<b>继承</b>字段：事实的 owner（{@code l1ReplayFact}）是声明类，不是匿名宿主，
			// 台账键必须同源，否则消费侧永远查不到这条事实。
			String owner = replay.declaringClass();
			if (!revertContext && !GenerationalFieldLedger.INSTANCE.isUnclosed(loader, owner, replay.name())) {
				continue;
			}
			GenerationalFieldLedger.Resolved resolved = GenerationalFieldLedger.INSTANCE.resolve(
			 loader, ChangesetLog.INSTANCE, scope, owner, replay.name(), targetSeq);
			if (!revertContext && GenerationalFieldLedger.INSTANCE.isUnclosed(loader, owner, replay.name())
			    && !hasAppliedWrite(loader, owner, replay.name(), resolved.fromGeneration())) {
				// 欠债且<b>待对账那一代</b>没有确认写入：那条 ReplayFact 只是理论初值（§4-R3），
				// 它的 newLiteral 从没进过堆。判据必须落在"该代"，不能是"历史上曾经写过任何一代"——
				// 否则回退保留的旧代 written 记录会给一条未写成的事实背书。
				refuseGenerationalReplay(loader, owner, replay.name());
				replayFields.remove(field);
				replayDecisions.put(field, "rejected: the field is unclosed and generation "
				 + resolved.fromGeneration() + " has no confirmed write, so its fact cannot supply an"
				 + " expectation (fail-closed until @RemoldReinit(OVERWRITE) or an explicit rebuild)");
				continue;
			}
			// 被裁剪过的键不能按 OBLIVIOUS 放行：MISS_NO_FACT 可能是"忘了"而不是"从来没有"（规格 L5）。
			GenerationalReplayGate.Decision decision = GenerationalReplayGate.decide(
			 resolved, replay.desc(), literalKindOf(replay.desc()),
			 prunedFromLedger(loader, owner, replay.name()));
			if (decision.oblivious()) continue;
			GenerationalReplay override = decision.writable()
			 ? toGenerationalReplay(decision, replay.desc()) : null;
			if (override == null) {
				refuseGenerationalReplay(loader, owner, replay.name());
				replayFields.remove(field);
				replayDecisions.put(field, "rejected: " + reasonOf(decision,
				 "replay fact literal cannot be decoded without re-deriving bits from decimal text"));
				continue;
			}
			out.put(L1_GATE_PREFIX + field, override);
			RemoldAgent.info("[INIT-REPLAY] " + display + "." + replay.name()
			 + " is reconciled against the generation-" + override.fromGeneration() + " replay fact"
			 + " (expect " + override.oldLiteral() + ", write " + override.newLiteral() + ")");
		}

		for (String key : new ArrayList<>(applyFields.keySet())) {
			ApplyInitializerReplay.ReplayField replay = applyFields.get(key);
			String targetInternal = replay.targetClass();
			if (!revertContext
			    && !GenerationalFieldLedger.INSTANCE.isUnclosed(loader, targetInternal, replay.fieldName())) {
				continue;
			}
			GenerationalFieldLedger.Resolved resolved = GenerationalFieldLedger.INSTANCE.resolve(
			 loader, ChangesetLog.INSTANCE, scope, targetInternal, replay.fieldName(), targetSeq);
			if (!revertContext
			    && !hasAppliedWrite(loader, targetInternal, replay.fieldName(), resolved.fromGeneration())) {
				refuseGenerationalReplay(loader, targetInternal, replay.fieldName());
				applyFields.remove(key);
				applyDecisions.put(key, "rejected: the field is unclosed and generation "
				 + resolved.fromGeneration() + " has no confirmed write, so its fact cannot supply an"
				 + " expectation (fail-closed until @RemoldReinit(OVERWRITE) or an explicit rebuild)");
				continue;
			}
			GenerationalReplayGate.Decision decision = GenerationalReplayGate.decide(
			 resolved, replay.fieldDesc(), literalKindOf(replay.fieldDesc()),
			 prunedFromLedger(loader, targetInternal, replay.fieldName()));
			if (decision.oblivious()) continue;
			GenerationalReplay override = decision.writable()
			 ? toGenerationalReplay(decision, replay.fieldDesc()) : null;
			if (override == null) {
				refuseGenerationalReplay(loader, targetInternal, replay.fieldName());
				applyFields.remove(key);
				applyDecisions.put(key, "rejected: " + reasonOf(decision,
				 "replay fact literal cannot be decoded without re-deriving bits from decimal text"));
				continue;
			}
			out.put(APPLY_GATE_PREFIX + key, override);
			RemoldAgent.info("[INIT-APPLY-REPLAY] " + display + ": " + key
			 + " is reconciled against the generation-" + override.fromGeneration() + " replay fact"
			 + " (expect " + override.oldLiteral() + ", write " + override.newLiteral() + ")");
		}
		return out;
	}

	/**
	 * 消费侧拒绝的记账出口：未闭环债务落台账（{@link GenerationalFieldLedger#markUnclosed}）。
	 *
	 * <p>告警不在这里发：拒绝文案以 {@code rejected: } 前缀进 {@link PatchReport#replayFields()} /
	 * {@link PatchReport#applyReplayFields()}，由既有的 {@link #logReplayResult} /
	 * {@link #logApplyReplayDecisions} 统一 warn（AGENTS §3：拒绝必须经统一出口可见，且只报一次）。</p>
	 */
	private static void refuseGenerationalReplay(ClassLoader loader, String internalName, String field) {
		GenerationalFieldLedger.INSTANCE.markUnclosed(loader, internalName, field);
	}

	/** 拒绝文案：事实给出的原因 + 固定的 fail-closed 后果与恢复路径（进 PatchReport，随 warn 输出）。 */
	private static String reasonOf(GenerationalReplayGate.Decision decision, String fallback) {
		String reason = decision.reason();
		String base = reason == null || reason.isEmpty() ? fallback : reason;
		return base + " (fail-closed: the field stays unclosed in GenerationalFieldLedger;"
		 + " recovery needs an explicit rebuild or @RemoldReinit(mode = OVERWRITE))";
	}

	/** 判决 → 期望/写入常量；任一侧解不出来就返回 {@code null}（调用方必须 fail-closed）。 */
	private static GenerationalReplay toGenerationalReplay(GenerationalReplayGate.Decision decision,
	                                                       String fieldDesc) {
		Object expected = GenerationalReplayGate.toBootstrapConstant(
		 decision.literalKind(), decision.oldLiteral(), fieldDesc);
		Object write = GenerationalReplayGate.toBootstrapConstant(
		 decision.literalKind(), decision.newLiteral(), fieldDesc);
		if (expected == null || write == null) return null;
		return new GenerationalReplay(decision.oldLiteral(), decision.newLiteral(),
		 decision.literalKind(), fieldDesc, expected, write, decision.fromGeneration());
	}

	/**
	 * 把 L1 重放切片里的"值"部分换成事实带出的规范常量（{@code docs/vcs/05} §3.3 D6）。
	 *
	 * <p>切片形状由 {@link #replayExtractReason} 锁定为 {@code ALOAD 0; <自包含常量>; PUTFIELD}，
	 * 因此这里保留首尾指令、只重建中间的值指令。写入值因此与比较侧同源。</p>
	 */
	private static List<AbstractInsnNode> withReplayValue(List<AbstractInsnNode> body, Object constant,
	                                                      String fieldDesc) {
		if (body == null || body.size() < 3 || constant == null) return body;
		List<AbstractInsnNode> out = new ArrayList<>(3);
		out.add(body.get(0));
		out.add(applyReplayConstant(constant, fieldDesc));
		out.add(body.get(body.size() - 1));
		return out;
	}

	/**
	 * 批次分析快照（{@code docs/vcs/01-bytecode-changeset-vcs.md} §2.1.2）。
	 *
	 * <p>提交时刻批次上下文仍存活（{@code endBatchBytecodeView} 在其后），因此这里读到的是
	 * Stage B 将要使用的那份图；宿主顺序取自同一确定性排序函数。</p>
	 */
	public static Changeset.Manifest batchCommitManifest(List<Class<?>> hosts) {
		BatchContext context = BATCH_CONTEXT.get();
		if (context == null) return Changeset.Manifest.EMPTY;
		List<String> orderedHosts = new ArrayList<>();
		for (Class<?> host : orderBatchHosts(hosts == null ? List.of() : hosts)) {
			orderedHosts.add(batchHostKey(context, host));
		}
		List<String> edges = new ArrayList<>();
		List<String> nodes = new ArrayList<>();
		for (Map.Entry<Class<?>, BatchPlan> e : context.plans().entrySet()) {
			String from = batchHostKey(context, e.getKey());
			PatchAnalysis analysis = e.getValue().analysis();
			for (Map.Entry<String, FieldDecision> decision : analysis.instanceDecisions().entrySet()) {
				nodes.add(from + "." + decision.getKey() + (decision.getValue().status() == FieldStatus.ACCEPTED
				 ? "" : "#" + decision.getValue().status().name()));
			}
			for (Map.Entry<String, FieldDecision> decision : analysis.staticDecisions().entrySet()) {
				nodes.add(from + "." + decision.getKey() + (decision.getValue().status() == FieldStatus.ACCEPTED
				 ? "" : "#" + decision.getValue().status().name()));
			}
			for (Map.Entry<String, Set<InitFixBatchGraph.FieldKey>> dep : analysis.dependencies().entrySet()) {
				for (InitFixBatchGraph.FieldKey to : dep.getValue()) {
					edges.add(from + "." + dep.getKey() + " -> " + to.host() + "." + to.field());
				}
			}
		}
		List<String> missing = new ArrayList<>();
		// 按 <b>值</b> 查 loader：候选键里的 id 是每次拼接出来的新 String，用 IdentityHashMap
		// 会把整批候选都查成"缺字节"。bootstrap loader 的 key 就是 null，也必须照常查
		// （bytesByLoader 本身允许 null key），否则它名下每个类都会被误报。
		Map<String, ClassLoader> byId = new HashMap<>();
		for (Map.Entry<ClassLoader, String> e : context.loaderIds().entrySet()) byId.put(e.getValue(), e.getKey());
		for (String candidate : context.candidateFields().keySet()) {
			int colon = candidate.indexOf(':');
			if (colon < 0) continue;
			String id = candidate.substring(0, colon);
			String name = candidate.substring(colon + 1);
			Map<String, byte[]> bytes = byId.containsKey(id) ? context.bytesByLoader().get(byId.get(id)) : null;
			if (bytes == null || !bytes.containsKey(name)) missing.add(name);
		}
		return Changeset.Manifest.of(orderedHosts, edges, nodes, missing);
	}

	/** 本轮的提交级事实快照；无批次上下文时返回空表。 */
	public static List<Changeset.ReplayFact> batchReplayFacts() {
		BatchContext context = BATCH_CONTEXT.get();
		return context == null ? List.of() : List.copyOf(context.replayFacts());
	}

	public static List<Class<?>> orderBatchHosts(List<Class<?>> hosts) {
		BatchContext context = BATCH_CONTEXT.get();
		if (context == null) return hosts;
		Map<String, Class<?>> byKey = new LinkedHashMap<>();
		Map<InitFixBatchGraph.FieldKey, Set<InitFixBatchGraph.FieldKey>> edges = new LinkedHashMap<>();
		for (Class<?> host : hosts) {
			String key = batchHostKey(context, host);
			byKey.put(key, host);
			PendingPatch patch = PENDING.get(host);
			if (patch == null) continue;
			for (FieldPatchTask task : patch.plan().allTasks()) {
				edges.put(new InitFixBatchGraph.FieldKey(key, task.fieldName()), task.batchDependencies());

			}
		}
		List<String> ordered = InitFixBatchGraph.graph(edges).topologicalHosts(byKey.keySet());
		if (ordered.isEmpty() && !byKey.isEmpty()) return List.copyOf(hosts);
		return ordered.stream().map(byKey::get).filter(Objects::nonNull).toList();
	}

	static String batchHostKeyCurrent(Class<?> host) {
		BatchContext context = BATCH_CONTEXT.get();
		return context == null ? host.getName() : batchHostKey(context, host);
	}

	public static boolean requiresTwoPhase(Class<?> host) {
		return requiresTwoPhase(host, true);
	}

	/**
	 * 判断目标类是否需要两阶段重定义（阶段 A 骨架提交以防御零值读穿）。
	 *
	 * <p>精细化门控：若补丁计划中只有实例字段任务（无静态字段任务），且堆中经过实例探测
	 * 确认没有任何存活实例，则不存在任何并发读穿窗口，可安全旁路阶段 A，避免无谓的双重 Redefine 元数据分配。</p>
	 */
	public static boolean requiresTwoPhase(Class<?> host, boolean hasLiveInstances) {
		return requiresTwoPhase(host, () -> hasLiveInstances);
	}

	/**
	 * 惰性探测版本：只有确认存在“需要两阶段保护且仅含实例字段”的挂起补丁时，才调用 {@code liveInstanceProbe}
	 * 触发 JVMTI 全堆扫描；没有补丁或含静态字段的类直接短路返回，避免无效 STW 停顿。
	 */
	public static boolean requiresTwoPhase(Class<?> host, BooleanSupplier liveInstanceProbe) {
		if (host == null) return false;
		PendingPatch patch = PENDING.get(host);
		if (patch == null || !patch.twoPhase()) return false;
		if (!patch.plan().hasStatic() && patch.plan().hasInstance()) {
			return liveInstanceProbe == null || liveInstanceProbe.getAsBoolean();
		}
		return true;
	}

	/** 见 {@link #requiresTwoPhase(Class)}：在补丁计划里找"零值暴露字段"的写入任务。 */
	private static boolean requiresReadThroughGuard(PatchPlan plan, ClassDiff diff) {
		if (plan == null || diff == null || plan.isEmpty()) return false;
		Set<String> exposed = new HashSet<>();
		if (diff.addedInstanceFields != null) exposed.addAll(diff.addedInstanceFields);
		if (diff.addedStaticFields != null) exposed.addAll(diff.addedStaticFields);
		if (diff.typeChangedFields != null) exposed.addAll(diff.typeChangedFields);
		if (!exposed.isEmpty()) {
			for (FieldPatchTask task : plan.instanceTasks()) {
				if (!task.replay() && exposed.contains(task.fieldName())) return true;
			}
			for (FieldPatchTask task : plan.staticTasks()) {
				if (!task.replay() && exposed.contains(task.fieldName())) return true;
			}
		}
		// 既有静态字段的重写没有"零值暴露"，但补丁后同样需要阶段 C 的 deopt 屏障。
		for (FieldPatchTask task : plan.staticTasks()) {
			if (rewritesExistingStaticField(task, diff)) return true;
		}
		return false;
	}

	/**
	 * 既有静态字段被改写（{@code @RemoldReinit} / 重放写任务）时是否需要"补丁后的 deopt 屏障"。
	 *
	 * <p>{@code Unsafe}/补丁写内存不会让已编译读者退优化：单阶段（redefine → patch）下，窗口期内
	 * 重编译的读者可能把旧值提升进机器码，patch 之后一直读到旧值；两阶段的阶段 C 会再 redefine 一次
	 * 把这类读者退优化（实测见 {@code tests/static-rewrite-probe} 的 single/two A/B 对照）。</p>
	 *
	 * <p>{@code ConstantValue} 编译期字面量常量除外：读者已把字面量内联进 class 文件，deopt 无法刷新，
	 * 属于 {@code @RemoldReinit} Javadoc 明示的不支持范围。</p>
	 */
	private static boolean rewritesExistingStaticField(FieldPatchTask task, ClassDiff diff) {
		if (task == null || !task.isStatic() || diff == null) return false;
		String name = task.fieldName();
		if (diff.addedStaticFields != null && diff.addedStaticFields.contains(name)) return false;
		if (diff.typeChangedFields != null && diff.typeChangedFields.contains(name)) return false;
		return !isCompileTimeConstant(diff, name);
	}

	/** 新字节码里该字段是否带 {@code ConstantValue}（编译期字面量常量；ASM 存在 {@code FieldNode.value}）。 */
	private static boolean isCompileTimeConstant(ClassDiff diff, String fieldName) {
		if (diff == null || diff.newClass == null || diff.newClass.fields == null) return false;
		for (FieldNode field : diff.newClass.fields) {
			if (field.name.equals(fieldName)) return field.value != null;
		}
		return false;
	}

	/**
	 * 判定本次热更是否真正需要读取方法体指令（字段新增、{@code @RemoldReinit}、未修补台账或初值重放）。
	 * 返回 {@code false} 时，动态重定义路径可只保留 {@code SKIP_CODE} 结构差异，跳过全类方法指令解码与指纹计算。
	 */
	public static boolean needsMethodBodies(Class<?> host, ClassDiff diff) {
		if (diff == null || diff.newClass == null) return true;
		if (!diff.addedStaticFields.isEmpty() || !diff.addedInstanceFields.isEmpty()
		    || !diff.reinitFields.isEmpty()) {
			return true;
		}
		if (host != null && !ledgerSnapshot(host).isEmpty()) return true;
		if (InitializerReplay.mightReplay(diff) || ApplyInitializerReplay.mightReplay(diff)) {
			return true;
		}
		return !scanReinitFields(diff.newClass).isEmpty();
	}

	/**
	 * 检查目标字段在给定类中是否具备可提取的初值切片（用于布局门 WAIVE_REINIT 前置核验，防 B4 静默丢值）。
	 *
	 * @param host        已加载的目标类引用（若类尚未加载可为 {@code null}）
	 * @param classNode   目标类新字节码的 ClassNode
	 * @param targetFields 待核验的字段名集合（通常为标有 @RemoldReinit 的变更字段）
	 * @return 无法提取初值的字段名及其原因映射；若所有字段皆可提取，则返回空 Map
	 */
	public static Map<String, String> unextractableReinitFields(
	 Class<?> host, ClassNode classNode, Set<String> targetFields) {
		if (classNode == null || targetFields == null || targetFields.isEmpty()) {
			return Map.of();
		}

		boolean isRecord = (classNode.access & Opcodes.ACC_RECORD) != 0
			|| (classNode.superName != null && "java/lang/Record".equals(classNode.superName));
		if (isRecord) {
			Map<String, String> reasons = new LinkedHashMap<>();
			for (String f : targetFields) {
				reasons.put(f, "record component has no field initializer (assigned from constructor parameter)");
			}
			return reasons;
		}

		HierarchyTreeOracle oracle = new HierarchyTreeOracle(host == null ? null : host.getClassLoader(), classNode);
		InitFixSafetyGate.NestView nest = InitFixSafetyGate.NestView.of(host, classNode, oracle);

		Map<String, FieldNode> fieldNodes = new HashMap<>();
		for (FieldNode fn : classNode.fields) {
			fieldNodes.put(fn.name, fn);
		}

		Set<String> instanceFields = new LinkedHashSet<>();
		Set<String> staticFields = new LinkedHashSet<>();
		for (String f : targetFields) {
			FieldNode fn = fieldNodes.get(f);
			if (fn != null && (fn.access & Opcodes.ACC_STATIC) != 0) {
				staticFields.add(f);
			} else {
				instanceFields.add(f);
			}
		}

		Map<String, String> unextractable = new LinkedHashMap<>();

		// 检查实例字段
		if (!instanceFields.isEmpty()) {
			List<MethodNode> initMethods = new ArrayList<>();
			for (MethodNode m : classNode.methods) {
				if ("<init>".equals(m.name)) initMethods.add(m);
			}
			if (initMethods.isEmpty()) {
				for (String f : instanceFields) {
					unextractable.put(f, "no <init> constructor found in class");
				}
			} else {
				Map<MethodNode, Boolean> rootCtorCache = new IdentityHashMap<>();
				int rootCtorCount = 0;
				for (MethodNode init : initMethods) {
					if (InitFixSliceExtractor.isRootConstructor(classNode, init, rootCtorCache)) rootCtorCount++;
				}
				if (rootCtorCount == 0) {
					for (String f : instanceFields) {
						unextractable.put(f, "no root constructor calling super() found");
					}
				} else {
					Set<String> privateMethods = new HashSet<>();
					for (MethodNode mn : classNode.methods) {
						if ((mn.access & Opcodes.ACC_PRIVATE) != 0) {
							privateMethods.add(mn.name + mn.desc);
						}
					}
					Map<String, List<FieldExtract>> instanceExtracts = new LinkedHashMap<>();
					Set<String> selfAssignedFields = new HashSet<>();
					Map<String, String> instanceReasons = new LinkedHashMap<>();

					for (MethodNode init : initMethods) {
						boolean fromRoot = InitFixSliceExtractor.isRootConstructor(classNode, init, rootCtorCache);
						ParamScan scan = InitFixSafetyGate.scanParamFields(host, classNode, init, nest);
						Map<Integer, ParamField> paramFields = scan.accepted();
						Map<String, FieldExtract> perField = InitFixSliceExtractor.extractFieldInits(
							host, classNode.name, init, instanceFields, false, privateMethods,
							paramFields, scan.rejected(), fromRoot, selfAssignedFields, instanceReasons, oracle);
						for (Map.Entry<String, FieldExtract> fe : perField.entrySet()) {
							instanceExtracts.computeIfAbsent(fe.getKey(), x -> new ArrayList<>()).add(fe.getValue());
						}
					}

					for (String f : instanceFields) {
						if (selfAssignedFields.contains(f)) {
							unextractable.put(f, "self-assignment from constructor parameter (patch would be a no-op)");
							continue;
						}
						List<FieldExtract> extracts = instanceExtracts.get(f);
						if (extracts == null || extracts.isEmpty()) {
							unextractable.put(f, instanceReasons.getOrDefault(f, "no field initializer found in constructors"));
							continue;
						}
						int fromRootCount = 0;
						for (FieldExtract fe : extracts) {
							if (fe.fromRootCtor()) fromRootCount++;
						}
						if (fromRootCount == 0) {
							unextractable.put(f, "field is only initialized in delegating constructors");
						} else if (rootCtorCount > 1 && fromRootCount < rootCtorCount) {
							unextractable.put(f, "field is only initialized in " + fromRootCount + " of " + rootCtorCount + " root constructors");
						}
					}
				}
			}
		}

		// 检查静态字段
		if (!staticFields.isEmpty()) {
			MethodNode clinit = null;
			for (MethodNode m : classNode.methods) {
				if ("<clinit>".equals(m.name)) {
					clinit = m;
					break;
				}
			}
			if (clinit == null) {
				for (String f : staticFields) {
					unextractable.put(f, "no <clinit> found in class");
				}
			} else {
				Set<String> privateMethods = new HashSet<>();
				for (MethodNode mn : classNode.methods) {
					if ((mn.access & Opcodes.ACC_PRIVATE) != 0) {
						privateMethods.add(mn.name + mn.desc);
					}
				}
				Map<String, String> staticReasons = new LinkedHashMap<>();
				Map<String, FieldExtract> perField = InitFixSliceExtractor.extractFieldInits(
					host, classNode.name, clinit, staticFields, true, privateMethods,
					Map.of(), Map.of(), true, null, staticReasons, oracle);
				for (String f : staticFields) {
					if (!perField.containsKey(f)) {
						unextractable.put(f, staticReasons.getOrDefault(f, "no field initializer found in <clinit>"));
					}
				}
			}
		}

		return unextractable;
	}

	/**
	 * {@code @RemoldReinit(scope = INCLUDE_SUBCLASSES)} 的继承作用域安全门（{@code docs/initfix/03-runtime-driver.md} §4）。
	 *
	 * <p>值来自<b>声明类</b>的初值切片，却写给全部子类实例；若某个子类在 {@code <init>} 里给这个
	 * 继承字段写了自己的值，重置会覆盖它且不重放子类赋值。这里枚举<b>已登记</b>的子类型（有实例
	 * 的子类必已加载并登记，见 {@link AnnotationTransformer.HierarchyTree#knownSubtypes(String, ClassLoader)}），
	 * 交给纯函数 {@link ReinitScopeGate} 判定。命中即返回字段名 → 拒绝原因，由布局门拒绝本轮重定义。</p>
	 *
	 * <p>字节来源与层级解析同源（{@link HierarchyTreeOracle}：bytecodeCache → {@code BytecodeSources}
	 * → 类加载器资源流），全程不触发类加载。读不到子类字节码时按"无法证明"记录，仍是拒绝方向。</p>
	 */
	public static Map<String, String> unsafeInheritedReinitFields(
	 Class<?> host, ClassNode classNode, Set<String> reinitFields) {
		if (classNode == null || reinitFields == null || reinitFields.isEmpty()) return Map.of();
		ClassLoader loader = host == null ? null : host.getClassLoader();
		HierarchyTreeOracle oracle = new HierarchyTreeOracle(loader, classNode);
		Set<String> subtypes = oracle.knownSubtypes(classNode.name);
		if (subtypes.isEmpty()) {
			// 空集放行必须带时间戳（S1/S3）：这条结论只在"当前加载前沿"内成立。
			// 记录之后，若世界推进/进入保守模式/条目被裁剪，后续查询会返回 STALE，
			// 那时不得再用"没看到子类"作为安全证明。
			ScopeVerdicts.Availability previous = ScopeVerdicts.INSTANCE.check(loader, classNode.name);
			ScopeVerdicts.INSTANCE.record(loader, classNode.name, true);
			if (previous == ScopeVerdicts.Availability.STALE) {
				// 上一次的"没看到子类"已经过期：这次重判得到空集是新的结论，不沿用旧的。
				log("Reinit scope verdict for " + Names.external(classNode.name)
				    + " was stale (load frontier advanced); recomputed on the current world");
			}
			return Map.of();
		}
		Map<String, byte[]> subtypeBytes = new LinkedHashMap<>();
		for (String subtype : subtypes) {
			byte[] bytes = oracle.classBytes(subtype);
			if (bytes != null) subtypeBytes.put(subtype, bytes);
		}
		return ReinitScopeGate.unsafeInheritedFields(classNode, reinitFields, subtypes, subtypeBytes);
	}

	public static void transform(Class<?> host, byte[] newBytes, ClassDiff diff) {
		if (!RemoldAgent.INIT_FIX) return;
		BatchContext context = BATCH_CONTEXT.get();
		if (context != null) {
			if (host != null) context.inputs().add(new BatchInput(host, newBytes, diff));
			return;
		}
		transformNow(host, newBytes, diff);
	}

	/**
	 * 字段被删掉时，按 {@code (loader, 本类内部名, 字段)} 精确释放该字段的代际债务。
	 *
	 * <p>只认结构信号 {@code diff.changedFields} 里的 {@code "- name"}：那是"本类不再声明这个字段"的
	 * 唯一事实来源。继承字段的债务键是<b>声明类</b>，声明类自己被热更时会走到同一分支，因此两边都能
	 * 出账，且不会跨类误删同名字段的债务。</p>
	 */
	private static void releaseRemovedFieldDebt(Class<?> host, ClassDiff diff) {
		if (host == null || diff == null || diff.changedFields.isEmpty()) return;
		String internal = Type.getInternalName(host);
		for (String change : diff.changedFields) {
			if (change == null || change.length() < 3 || !change.startsWith("- ")) continue;
			String field = change.substring(2).trim();
			int released = GenerationalFieldLedger.INSTANCE.releaseDebt(host.getClassLoader(), internal, field);
			if (released > 0) {
				log("FieldLedger: released generational debt for " + internal.replace('/', '.')
				    + "." + field + " (field no longer declared)");
			}
		}
	}

	private static void transformNow(Class<?> host, byte[] newBytes, ClassDiff diff) {
		if (!RemoldAgent.INIT_FIX) return;

		PENDING.remove(host);
		cleanupStalePatches();

		// 注解被摘掉的字段：作废它的"已应用"记录（docs/initfix/03-runtime-driver.md §4）。
		//
		// ClassDiff 只比结构（字段 name+desc 的增删、方法代码），**注解完全不参与**；
		// 而热更是否发生是上游按字节哈希判的 —— 所以"只摘掉一个注解"照样会走到这里。
		// 不在这里清理的话，将来重新标注解、初值又恰好没改时，会被判成"初值未变"而跳过：
		// 用户明明刚要求重置，却什么都不会发生。
		Map<String, ReinitField> preScannedReinit = null;
		if (diff != null) {
			if (diff.newClass != null) {
				preScannedReinit = scanReinitFields(diff.newClass);
				pruneReinitRecords(host, preScannedReinit.keySet());
			}
			for (String f : diff.typeChangedFields) {
				reinitAppliedRemove(host, f);
			}
			// 字段从新布局消失：它再也不可能有补丁，代际台账为它欠的债必须随字段一起出账。
			// 出账点是这里（结构真变了），而不是 pendingLedger 的"字段找不到"分支——那条分支
			// 只覆盖"补丁重试时字段没了"，L1/A 档的债务根本不走它。
			releaseRemovedFieldDebt(host, diff);
		}

		// L1（docs/initfix/12-L1-existing-initializer-replay.md）：默认关闭的存量初值重放。
		// 只对白名单父类的匿名子类、且旧/新初值都是编译期常量时生成补丁；检测失败只告警。
		InitializerReplay.Plan replayPlan = InitializerReplay.plan(host, diff);
		// 可变副本：代际对账要把被拒绝的候选从这里摘掉（否则它会照样进补丁）。
		Map<String, InitializerReplay.ReplayField> replayFields =
		 new LinkedHashMap<>(replayPlan.fields());
		if (!replayPlan.notices().isEmpty() && diff != null && diff.newClass != null) {
			String displayName = diff.newClass.name.replace('/', '.');
			for (String notice : replayPlan.notices()) {
				RemoldAgent.warn("[INIT-REPLAY] " + displayName + ": " + notice);
			}
		}

		// A 档（docs/initfix/13-kotlin-apply-replay.md）：Kotlin object / 顶层 val 的
		// `T().apply { field = const }`。默认关闭；只处理白名单持有者 <clinit> 的直线形态。
		ApplyInitializerReplay.Plan applyPlan = ApplyInitializerReplay.plan(host, diff);
		Map<String, ApplyInitializerReplay.ReplayField> applyFields =
		 new LinkedHashMap<>(applyPlan.fields());
		Map<String, String> applyDecisions = new LinkedHashMap<>(applyPlan.decisions());
		if (!applyPlan.notices().isEmpty() && diff != null && diff.newClass != null) {
			String displayName = diff.newClass.name.replace('/', '.');
			for (String notice : applyPlan.notices()) {
				RemoldAgent.warn("[INIT-APPLY-REPLAY] " + displayName + ": " + notice);
			}
		}
		// 代际对账（docs/vcs/05-generational-field-ledger.md §3.3）：回退之后当轮 ClassDiff
		// 不再是"JVM 里实际是什么"的可信描述，期望与写入值必须取自目标代的那一条事实。
		Map<String, String> replayDecisions = new LinkedHashMap<>();
		String analyzedName = diff == null || diff.newClass == null ? null : diff.newClass.name;
		Map<String, GenerationalReplay> generational = analyzedName == null
		 ? Map.of()
		 : gateGenerationalReplay(host, analyzedName, replayFields, applyFields,
		    replayDecisions, applyDecisions);
		logApplyReplayDecisions(analyzedName == null ? "?" : analyzedName.replace('/', '.'), applyDecisions);

		// 提交级事实（docs/vcs/01-bytecode-changeset-vcs.md §3.8）：分析期把两档重放的
		// 旧/新初值固化成事实，随 Changeset 落盘；读侧不再依赖旧字节是否还在预算窗口内。
		// 被代际对账拒绝的候选已经不在两个 map 里，因此不会产出事实。
		captureReplayFacts(host, replayFields, applyFields);

		Set<String> addedStaticFields   = diff.addedStaticFields;
		Set<String> addedInstanceFields = diff.addedInstanceFields;

		Map<String, String> pending = ledgerSnapshot(host);
		boolean hasReinit = preScannedReinit != null ? !preScannedReinit.isEmpty() : hasReinitAnnotation(newBytes);
		if (addedStaticFields.isEmpty() && addedInstanceFields.isEmpty()
		    && !hasReinit && pending.isEmpty()
		    && replayFields.isEmpty() && applyFields.isEmpty()
		    && replayDecisions.isEmpty() && applyDecisions.isEmpty()) {
			REPORTS.put(host, new PatchReport(Map.of(), Map.of(), false));
			return;
		}
		if (!pending.isEmpty()) {
			log("FieldLedger: retrying " + pending.size() + " unpatched field(s) of "
			    + diff.newClass.name + ": " + pending);
		}

		String className = diff.newClass.name;
		warnHashSemanticDependencies(diff.newClass, newBytes, addedInstanceFields);
		try {
			PatchAnalysis analysis = analyzePatch(host, diff.newClass, newBytes, className,
			 addedStaticFields, addedInstanceFields, diff.typeChangedFields, pending, preScannedReinit, replayFields, applyFields,
			 replayDecisions, applyDecisions, generational);
			if (diff.typeChangedFields != null) {
				for (String f : diff.typeChangedFields) {
					FieldDecision decided = analysis.decision(f);
					if (decided == null) {
						RemoldAgent.warn(typeChangedState(className, f, "not-candidate",
							"key-mismatch"));
					} else if (decided.status() == FieldStatus.ACCEPTED) {
						RemoldAgent.log(typeChangedState(className, f, "accepted", "none"));
					} else {
						// 被丢弃必须让人看到：行为的"没发生"与日志的"声称发生"不一致，是比缺日志更糟的形态。
						RemoldAgent.warn(typeChangedState(className, f, "rejected", typeChangedReason(decided.reason())));
					}
				}
			}
			Runnable publish = () -> publishPatch(host, diff, className, replayFields, applyFields, analysis);
			BatchContext batch = BATCH_CONTEXT.get();
			if (batch == null) publish.run();
			else batch.plans().put(host, new BatchPlan(analysis, publish));
		} catch (Throwable e) {
			RemoldAgent.error("Field init patch generation failed for " + className
			                   + ": " + e.getMessage(), e);
			// docs/initfix/02-closure-and-ledger.md §2：buildPatch 抛异常意味着没生成任何补丁，但 redefine 会照常推进，
			// 这些新增字段从此不再出现在 Diff 里。必须在这里记账，否则永久遗忘。
			// 已有的台账条目原样保留（ledgerPut 会覆盖原因，这里跳过以免丢掉上一轮的结论）。
			Map<String, String> existing = ledgerSnapshot(host);
			String              reason   = "patch generation failed: " + e;
			for (String f : addedInstanceFields) {
				if (!existing.containsKey(f)) ledgerPut(host, f, reason);
			}
			for (String f : addedStaticFields) {
				if (!existing.containsKey(f)) ledgerPut(host, f, reason);
			}
		}
	}

	private static void publishPatch(Class<?> host, ClassDiff diff, String className,
	 Map<String, InitializerReplay.ReplayField> replayFields,
	 Map<String, ApplyInitializerReplay.ReplayField> applyFields, PatchAnalysis analysis) {
		try {
			BuiltPatch built = analysis.emit().get();

			REPORTS.put(host, built.report());
			// 分析级台账：被拒的记入（下轮重试），放行/零值等价的出账。
			// 运行期失败由 applyPatch 追加记入 —— 两者顺序天然正确（applyPatch 在后）。
			ledgerUpdateFromReport(host, built.report());
			// 统一的拒绝告警出口：闭包各阶段（后续加工/依赖/成环/根构造器覆盖/指纹不一致）
			// 只调 log()，而 log() 是 DEBUG 门控的（RemoldAgent.DefaultLogger.log），
			// 默认环境下这些真正的 REJECTED 对用户完全静默。这里按原因聚合统一发 warn，
			// 各阶段不必再各自关心日志级别。
			warnRejectedFields(className, built.report());
			logReplayResult(className, built.report(), replayFields);
			logApplyReplayResult(className, built.report(), applyFields);

			if (!built.hasPatch()) return;

			// 存活实例不在这里枚举：重定义还没生效，此刻拍下的快照会漏掉「快照之后、生效之前」
			// 新建的实例。枚举推迟到 applyPatch（重定义之后），见 PendingPatch 的说明。
			boolean twoPhase = requiresReadThroughGuard(built.plan(), diff);
			PendingPatch patch = new PendingPatch(
			 built.plan(), built.bytes(), System.nanoTime(), twoPhase, built.replayOwners());
			PENDING.put(host, patch);
			if (twoPhase) {
				log("Two-phase guard required for " + className
				    + ": a newly added/type-changed field is actually patched, so new bodies must not"
				    + " go live before the patch (docs/initfix/04-target-design.md)");
			}
		} catch (Throwable failure) {
			for (String field : analysis.instanceDecisions().keySet()) {
				if (!replayFields.containsKey(field)) ledgerPutIfAbsent(host, field, "patch emission failed: " + failure);
			}
			for (String field : analysis.staticDecisions().keySet()) {
				ledgerPutIfAbsent(host, field, "patch emission failed: " + failure);
			}
			RemoldAgent.error("Field init patch emission failed for " + className, failure);
		}
	}

	private static void cleanupStalePatches() {
		if (PENDING.isEmpty()) return;
		long now = System.nanoTime();

		// 先在 PENDING 的锁内挑出过期条目并摘除，同时把"宿主弱键 + 字段名 + 原因"
		// 抄进一个局部清单；出锁后再写台账。
		//
		// 为什么不在 removeIf 的判定式里直接写台账：那会在持有 PENDING 锁的同时
		// 去拿 LEDGER 锁，两把锁的获取顺序就取决于调用路径，属于无谓的死锁面。
		// 清单元素只持有 <b>弱引用</b> 的宿主键（照 AGENTS.md 不变量 2，不新增强引用），
		// 弱引用在锁外可能已被清除，写台账前逐个 get() 复核。
		List<StalePatch> stale = new ArrayList<>();
		synchronized (PENDING) {
			PENDING.entrySet().removeIf(e -> {
				Class<?> k = e.getKey();
				if (k == null) return true;
				long age = now - e.getValue().createdNanos();
				if (age > pendingTtlNanos) {
					log("Dropping stale pending field init patch for " + k.getName()
					    + ", age=" + TimeUnit.NANOSECONDS.toSeconds(age) + "s");
					// 字段名从补丁计划取（与 afterRedefineFailed 同一来源）：
					// 此刻还没补上任何一个字段，它们必须记入台账，否则
					// redefine 推进后这些字段再也不会出现在 Diff 里 —— docs/initfix/02-closure-and-ledger.md §2 的遗忘场景。
					List<String> fields = new ArrayList<>();
					for (FieldPatchTask task : e.getValue().plan().allTasks()) {
						// L1 重放不是"本应补"的欠账，不写台账。
						if (task.replay()) continue;
						fields.add(task.fieldName());
					}
					stale.add(new StalePatch(new WeakReference<>(k), fields,
					 TimeUnit.NANOSECONDS.toSeconds(age)));
					return true;
				}
				return false;
			});
		}

		for (StalePatch s : stale) {
			Class<?> host = s.host().get();
			if (host == null) continue;   // 宿主已被回收：台账（弱键）也随之消失，无需记账
			// 补丁已过期、一个字段都没写进去：撤回"已应用"记录，下一轮重新判定而不是凭旧指纹跳过。
			reinitAppliedClear(host);
			for (String f : s.fields()) {
				// 只在台账尚无该字段时补记：不覆盖更具体的原因。
				// （ACCEPTED 字段在 transform 时已出账，所以这里写的是"真的没补上"的。）
				ledgerPutIfAbsent(host, f,
				 "pending patch expired before apply (age=" + s.ageSeconds() + "s)");
			}
		}
	}

	/** 被 TTL 清扫摘除的补丁：宿主弱键 + 未补字段名 + 年龄（秒）。 */
	private record StalePatch(WeakReference<Class<?>> host, List<String> fields, long ageSeconds) { }

	/**
	 * 识别本轮<b>新增实例字段里遮蔽了祖先同名字段</b>的那些，返回 {@code 字段名 -> 祖先声明类内部名}。
	 *
	 * <p>为什么需要它：DCEVM 重定义会把被遮蔽的继承槽位值复制进子类新增同名槽位，于是存量实例上
	 * 该槽位"已有非默认值"，而默认的零值条件 CAS 会据此跳过、把新初值丢掉。这里只负责把祖先
	 * 声明类找出来；比较基准由 {@link HotswapBridge#bootstrapAncestor} 在运行期读取。</p>
	 *
	 * <p>访问权限不是边界：DCEVM 的迁移复制按 {@code (name, desc)} 匹配，<b>不</b>区分访问权限，
	 * {@code private} 祖先字段的值同样会被复制进子类新槽。运行期读取走完整权限的 lookup，
	 * 因此 {@code private} / 跨包包内声明一律照常采纳。</p>
	 *
	 * <p>fail-closed 边界：祖先声明存在但<b>静态性不符</b>（实例槽位对不上静态字段，没有可比读数），
	 * 或祖先链任一环取不到字节码（无法证明"不是遮蔽"）时<b>不</b>采纳，退回默认零值条件 CAS 并告警
	 * —— 宁可漏补，也不拿猜测的期望值覆盖存量值。祖先链上根本没有同名同描述符声明则不是遮蔽，
	 * 保持原行为、不告警。</p>
	 *
	 * <p>本方法只读字节码元数据（{@link ClassHierarchyOracle}），不触发类加载，供分析期与单测共用。</p>
	 *
	 * <p><b>已知保守边界</b>：同一批 Stage B 里若祖先字段自己被 {@code @RemoldReinit} 先改写成了新值，
	 * 运行期读到的期望值就与新槽里那份 DCEVM 迁移副本对不上，条件 CAS 判不相等后按保守语义跳过
	 * （计入 skipped 并告警）：结果是漏补、字段留在继承值上等下一轮，<b>不是</b>把旧值误写进新字段。
	 * 该取舍刻意保留 —— 换成不看当前值的强制写会连带放弃"真被写过就不覆盖"的负向保证。</p>
	 *
	 * @param oracle             分析期层级查询口
	 * @param className          被重定义类的内部名
	 * @param addedInstanceFields 本轮新增的实例字段名
	 * @param fieldNodes         新版本类自身的字段表（取描述符）
	 */
	static Map<String, String> detectShadowedAddedFields(
	 ClassHierarchyOracle oracle, String className, Set<String> addedInstanceFields,
	 Map<String, FieldNode> fieldNodes) {
		if (addedInstanceFields.isEmpty()) return Map.of();
		if (!oracle.isSuperclassChainResolvable(className)) {
			RemoldAgent.warn("[SHADOW-FIELD] cannot read the complete superclass chain of "
			                 + Names.external(className) + " from bytecode, so it cannot be proven"
			                 + " whether an added field shadows an inherited one; every added field"
			                 + " keeps the zero-value conditional write (fail-closed)");
			return Map.of();
		}
		Map<String, String> shadows = new LinkedHashMap<>();
		for (String field : addedInstanceFields) {
			FieldNode fn = fieldNodes.get(field);
			if (fn == null || (fn.access & Opcodes.ACC_STATIC) != 0) continue;
			Optional<ClassHierarchyOracle.InheritedField> inherited;
			try {
				inherited = oracle.findInheritedField(className, field, fn.desc);
			} catch (RuntimeException failure) {
				RemoldAgent.warn("[SHADOW-FIELD] cannot inspect ancestor declarations of "
				                 + Names.external(className) + "." + field + ": " + failure
				                 + "; falling back to the zero-value conditional write (fail-closed)");
				continue;
			}
			if (inherited.isEmpty()) continue;
			ClassHierarchyOracle.InheritedField decl = inherited.get();
			String reason = shadowAncestorUnreadableReason(decl);
			if (reason != null) {
				RemoldAgent.warn("[SHADOW-FIELD] added field " + Names.external(className) + "." + field
				                 + " shadows " + Names.external(decl.declaringClass) + "." + field
				                 + ", but that inherited field " + reason
				                 + "; falling back to the zero-value conditional write (fail-closed)");
				continue;
			}
			// 用户可见告警：InitFix 只保证子类槽位按新初值写入，管不到 JBR DCEVM 的 relayout。
			// 实测（`tests/jbr-dcevm-shadowed-field/`、`docs/runtime/05-...`）：子类遮蔽父类同名字段时，
			// 下一轮结构性重定义会把最派生声明的值按名字写进同名祖先槽位，重定义之前就已存在的实例
			// 因此读到子类的值。这是上游行为，Remold 侧无法修正，只能点名告诉用户别这么写。
			RemoldAgent.warn("[SHADOW-FIELD] added field " + Names.external(className) + "." + field
			                 + " shadows " + Names.external(decl.declaringClass) + "." + field
			                 + "; InitFix will initialize the subclass slot, but JBR DCEVM relayout may copy"
			                 + " the most derived value into same-named ancestor slots of instances that"
			                 + " predate this redefinition. Avoid same-name field shadowing across a hot-reload"
			                 + " boundary, or rename the field.");
			shadows.put(field, decl.declaringClass);
		}
		return shadows;
	}

	/**
	 * 祖先声明能否作为运行期比较基准；返回 {@code null} 表示可用。
	 *
	 * <p>只拦真正读不到的形态：静态性不符（实例槽位没有可比读数）。访问权限不在此列 ——
	 * 运行期用完整权限的 lookup 读槽位，{@code private} 祖先照样能读到。</p>
	 */
	private static String shadowAncestorUnreadableReason(ClassHierarchyOracle.InheritedField decl) {
		if ((decl.access & Opcodes.ACC_STATIC) != 0) {
			return "is static, so its value cannot be compared on the instance";
		}
		return null;
	}

	/**
	 * 给"只声明不赋值"的遮蔽新增字段补一份"压入类型零值 + 写字段"的合成切片。
	 *
	 * <p>这类字段在新版本字节码里没有任何写指令，提取阶段自然拿不到切片；豁免 T0 之后会被
	 * {@code "no safe initialization expression found in constructors"} 拒掉，槽位仍停在 DCEVM
	 * 迁移复制进来的祖先值上。它的新初值就是类型默认值，所以在这类遮蔽字段上"写回 0"才是语义终局
	 * （{@code docs/initfix/01-safety-gate.md} §1 的 T0 判据只适用于"存量槽位本来就是默认值"）。</p>
	 *
	 * <p>只处理 {@link InitFixSafetyGate#isZeroEquivalentField} 认可的零值等价字段：有写指令却取不到
	 * 切片的形态说明那些写无法证明是零值写，不得替它编一个切片出来（宁可漏补）。</p>
	 */
	static void synthesizeZeroValueShadowExtracts(
	 InitFixSafetyGate.NestView nest, ClassNode newClass, String className,
	 Set<String> targetInstanceFields, Set<String> forceWriteFields,
	 Map<String, ReinitField> reinitFields, Set<String> acceptedReplay,
	 Set<String> selfAssignedFields, Map<String, String> shadowAncestors,
	 Map<String, FieldNode> fieldNodes, Map<String, List<FieldExtract>> instanceExtracts,
	 int rootCtorCount) {
		if (shadowAncestors.isEmpty()) return;
		for (String fieldName : shadowAncestors.keySet()) {
			if (!targetInstanceFields.contains(fieldName)) continue;
			if (forceWriteFields.contains(fieldName)) continue;
			if (reinitFields.containsKey(fieldName) || acceptedReplay.contains(fieldName)) continue;
			if (selfAssignedFields.contains(fieldName)) continue;
			if (instanceExtracts.containsKey(fieldName)) continue;
			FieldNode field = fieldNodes.get(fieldName);
			if (field == null) continue;
			if (!InitFixSafetyGate.isZeroEquivalentField(nest, newClass, field, false, instanceExtracts)) {
				continue;
			}
			int copies = Math.max(1, rootCtorCount);
			List<FieldExtract> slices = new ArrayList<>(copies);
			for (int i = 0; i < copies; i++) {
				List<AbstractInsnNode> insns = List.of(
				 new VarInsnNode(Opcodes.ALOAD, 0),
				 zeroValueInsn(field.desc),
				 new FieldInsnNode(Opcodes.PUTFIELD, className, fieldName, field.desc));
				slices.add(new FieldExtract(insns, Map.of(), false, true, new HashSet<>(insns)));
			}
			instanceExtracts.put(fieldName, slices);
			log("Synthesized zero-value slice for the shadowed added field " + className + "."
			    + fieldName + " (" + field.desc + "): the new initializer is the type default");
		}
	}

	/** 字段描述符对应的类型默认值压栈指令（与 {@link InitFixSafetyGate} 的 T0 判据同口径）。 */
	private static AbstractInsnNode zeroValueInsn(String desc) {
		char c = desc.isEmpty() ? 'I' : desc.charAt(0);
		return switch (c) {
			case 'L', '[' -> new InsnNode(Opcodes.ACONST_NULL);
			case 'J' -> new InsnNode(Opcodes.LCONST_0);
			case 'F' -> new InsnNode(Opcodes.FCONST_0);
			case 'D' -> new InsnNode(Opcodes.DCONST_0);
			default -> new InsnNode(Opcodes.ICONST_0);   // Z/B/C/S/I
		};
	}

	private static PatchAnalysis analyzePatch(
	 Class<?> host, ClassNode preParsedNewClass, byte[] newBytes, String className,
	 Set<String> addedStaticFields, Set<String> addedInstanceFields, Set<String> typeChangedFields,
	 Map<String, String> pendingLedger,
	 Map<String, ReinitField> preScannedReinit,
	 Map<String, InitializerReplay.ReplayField> replayFields,
	 Map<String, ApplyInitializerReplay.ReplayField> applyFields,
	 Map<String, String> replayDecisions,
	 Map<String, String> applyDecisions,
	 Map<String, GenerationalReplay> generational) {

		ClassNode newClass = preParsedNewClass;
		if (newClass == null) {
			newClass = new ClassNode();
			new ClassReader(newBytes).accept(newClass, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		}
		int hostVersion = newClass.version;

		Map<String, FieldNode> fieldNodes = new HashMap<>();
		for (FieldNode fn : newClass.fields) {
			fieldNodes.put(fn.name, fn);
		}

		Set<String> privateMethods = new HashSet<>();
		for (MethodNode mn : newClass.methods) {
			if ((mn.access & Opcodes.ACC_PRIVATE) != 0) {
				privateMethods.add(mn.name + mn.desc);
			}
		}

		List<MethodNode> initMethods = newClass.methods.stream()
		 .filter(m -> "<init>".equals(m.name))
		 .toList();
		MethodNode clinitMethod = newClass.methods.stream()
		 .filter(m -> "<clinit>".equals(m.name) && "()V".equals(m.desc))
		 .findFirst().orElse(null);

		Map<MethodNode, Boolean> rootCtorCache = new IdentityHashMap<>();
		int                      rootCtorCount = 0;
		for (MethodNode init : initMethods) {
			if (InitFixSliceExtractor.isRootConstructor(newClass, init, rootCtorCache)) rootCtorCount++;
		}
		if (rootCtorCount > 1) {
			log("Class " + className + " has " + rootCtorCount
			    + " independent root constructors");
		}

		// 宿主 Nest 视图：docs/initfix/01-safety-gate.md §3 的"全 Nest 单写证明"与 §1 T0 的"无写入"判定都基于它。
		// 全程只读字节码，不调用 Class.forName，避免在 transform 线程上触发类加载死锁。
		HierarchyTreeOracle oracle = new HierarchyTreeOracle(host == null ? null : host.getClassLoader(), newClass);
		InitFixSafetyGate.NestView nest = InitFixSafetyGate.NestView.of(host, newClass, oracle);

		// ==================== docs/initfix/03-runtime-driver.md §4 存量重置扩展：候选集 ====================
		// 默认只处理新增字段；标了 @RemoldReinit 的已有字段按显式声明进入候选集。
		Map<String, ReinitField> reinitFields         = preScannedReinit != null ? preScannedReinit : scanReinitFields(newClass);
		captureReinitFacts(host, reinitFields);
		Set<String>              targetInstanceFields = new LinkedHashSet<>(addedInstanceFields);
		Set<String>              targetStaticFields   = new LinkedHashSet<>(addedStaticFields);
		Set<String>              forceWriteFields     = new LinkedHashSet<>();
		Set<String>              selfOnlyFields       = new LinkedHashSet<>();
		List<String>             staticScopeIgnored   = new ArrayList<>();
		for (Map.Entry<String, ReinitField> e : reinitFields.entrySet()) {
			FieldNode fn = fieldNodes.get(e.getKey());
			if (fn == null) continue;   // 注解在不存在的字段上（理论上不可能）
			boolean     isStatic = (fn.access & Opcodes.ACC_STATIC) != 0;
			ReinitField rf       = e.getValue();
			(isStatic ? targetStaticFields : targetInstanceFields).add(e.getKey());
			if (rf.overwrite()) forceWriteFields.add(e.getKey());
			if (isStatic) {
				// 静态字段只有一个槽位，"只重置本类实例"无从谈起：作用域对静态字段没有意义。
				// 只在用户显式写了 scope 时提醒（默认值不是用户的声明，不该刷警告），然后忽略它。
				if (rf.scopeExplicit()) staticScopeIgnored.add(e.getKey());
			} else if (rf.selfOnly()) {
				selfOnlyFields.add(e.getKey());
			}
			log("@RemoldReinit on " + className + "." + e.getKey()
			    + " -> mode=" + (rf.overwrite() ? "OVERWRITE" : "CONDITIONAL")
			    + (isStatic ? "" : ", scope=" + (rf.selfOnly() ? "SELF" : "INCLUDE_SUBCLASSES")));
		}
		if (!staticScopeIgnored.isEmpty()) {
			// 单行告警：静态字段的 scope 被忽略，但静态补丁本身照常执行（不是拒绝路径）。
			RemoldAgent.warn("[REINIT-SCOPE] scope has no effect on static field(s) "
			                 + className.replace('/', '.') + "."
			                 + String.join(", ", staticScopeIgnored)
			                 + " and is ignored: a static field has a single slot, so SELF vs"
			                 + " INCLUDE_SUBCLASSES cannot select different targets.");
		}
		if (!reinitFields.isEmpty()) {
			log("@RemoldReinit expanded candidate set for " + className
			    + ": instance=" + targetInstanceFields + ", static=" + targetStaticFields);
		}

		// 新增实例字段若与祖先同名字段同描述符（遮蔽）：DCEVM 已把继承槽位值复制进新槽位，
		// 默认零值条件 CAS 会把它当成"已被写过"而跳过。这里定位可读的祖先声明类，
		// 由 {@link HotswapBridge#KIND_CONDITIONAL_ANCESTOR} 在运行期以祖先字段值为期望值。
		Set<String> shadowCandidates = new LinkedHashSet<>(addedInstanceFields);
		shadowCandidates.removeAll(forceWriteFields);
		Map<String, String> shadowAncestors =
		 detectShadowedAddedFields(oracle, className, shadowCandidates, fieldNodes);
		if (!shadowAncestors.isEmpty()) {
			log("Shadowed added field(s) of " + className + " resolve their expected value from: "
			    + shadowAncestors);
		}

		// L1 存量初值重放：继承字段不在本类字段表里，必须补一份合成的 FieldNode 元数据，
		// 否则后续安全门（后续加工检查）会因为 fieldNode == null 而整段跳过。
		for (InitializerReplay.ReplayField replay : replayFields.values()) {
			targetInstanceFields.add(replay.name());
			fieldNodes.putIfAbsent(replay.name(),
			 new FieldNode(replay.access(), replay.name(), replay.desc(), null, null));
		}

		// docs/initfix/02-closure-and-ledger.md §2 台账并集：候选字段集合 = 本轮 Diff 新增字段 ∪ 台账内未决字段（∪ 注解字段）。
		// 这些字段的写入语句仍在构造函数/<clinit> 里，所以仍走同一套提取与安全门；
		// 之所以必须显式并回来，是因为它们已经"存在"于旧字节码，永远不会再出现在 Diff 里。
		int retried = 0;
		for (String f : pendingLedger.keySet()) {
			FieldNode fn = fieldNodes.get(f);
			if (fn == null) {
				// 字段已从新版本里消失：无法再补，按已解决出账（避免台账里留死条目）
				ledgerRemove(host, f);
				reinitAppliedRemove(host, f);   // 记录随字段一起作废，防止同名重加时被误判"初值未变"
				// 字段已从新版本消失：代际台账里为它欠的债同样无法再补，必须随字段一起出账，
				// 否则会永久留在未闭环集合里（它不随 Changeset 窗口淘汰）。

				log("FieldLedger: dropping " + className + "." + f
				    + " (field no longer declared)");
				continue;
			}
			boolean isStatic = (fn.access & Opcodes.ACC_STATIC) != 0;
			(isStatic ? targetStaticFields : targetInstanceFields).add(f);
			retried++;
		}
		if (retried > 0) {
			log("FieldLedger: " + retried + " field(s) of " + className
			    + " re-entered the candidate set: " + pendingLedger.keySet());
		}

		// ==================== 实例字段提取 ====================
		Map<String, List<FieldExtract>> instanceExtracts   = new LinkedHashMap<>();
		Set<String>                     selfAssignedFields = new HashSet<>();
		Map<String, String>             instanceReasons    = new LinkedHashMap<>();
		for (MethodNode init : initMethods) {
			log("Extracting field init for " + className + "." + init.name + "()");
			boolean                  fromRoot    = InitFixSliceExtractor.isRootConstructor(newClass, init, rootCtorCache);
			ParamScan                scan        = InitFixSafetyGate.scanParamFields(host, newClass, init, nest);
			Map<Integer, ParamField> paramFields = scan.accepted();
			if (!paramFields.isEmpty()) {
				StringBuilder slots = new StringBuilder();
				for (Map.Entry<Integer, ParamField> en : paramFields.entrySet()) {
					if (slots.length() > 0) slots.append(", ");
					slots.append(en.getKey()).append("->").append(en.getValue().field().name);
				}
				log("Constructor param->field mapping in " + className
				    + "." + init.name + "(): " + slots);
			}
			Map<String, FieldExtract> perField = InitFixSliceExtractor.extractFieldInits(
			 host, className, init, targetInstanceFields, false, privateMethods,
			 paramFields, scan.rejected(), fromRoot, selfAssignedFields, instanceReasons, oracle);

			for (Map.Entry<String, FieldExtract> fe : perField.entrySet()) {
				instanceExtracts
				 .computeIfAbsent(fe.getKey(), x -> new ArrayList<>())
				 .add(fe.getValue());
			}
		}

		// ==================== 静态字段提取 ====================
		Map<String, List<FieldExtract>> staticExtracts = new LinkedHashMap<>();
		Map<String, String>             staticReasons  = new LinkedHashMap<>();
		if (clinitMethod != null) {
			Map<String, FieldExtract> perField = InitFixSliceExtractor.extractFieldInits(
			 host, className, clinitMethod, targetStaticFields,
			 true, privateMethods, Map.of(), Map.of(), true, null, staticReasons, oracle);
			for (Map.Entry<String, FieldExtract> fe : perField.entrySet()) {
				staticExtracts
				 .computeIfAbsent(fe.getKey(), x -> new ArrayList<>())
				 .add(fe.getValue());
			}
		}
		// ConstantValue 静态字段：无需经过 <clinit> 解析，直接生成 LDC + PUTSTATIC 提取片段，
		// 提前进入 staticExtracts，避免依赖闭包误杀依赖该常量的其他字段，并保证拓扑排序优先输出。
		for (FieldNode field : newClass.fields) {
			if ((field.access & Opcodes.ACC_STATIC) != 0
			    && targetStaticFields.contains(field.name)
			    && field.value != null
			    && !staticExtracts.containsKey(field.name)) {
				List<AbstractInsnNode> insns = List.of(
				 new LdcInsnNode(field.value),
				 new FieldInsnNode(Opcodes.PUTSTATIC, className, field.name, field.desc)
				);
				staticExtracts.computeIfAbsent(field.name, x -> new ArrayList<>())
				 .add(new FieldExtract(insns, Map.of(), false, false, new HashSet<>(insns)));
				log("Extracted constant field init from ConstantValue: "
				    + className + "." + field.name);
			}
		}

		List<MethodNode> staticScanMethods = new ArrayList<>(initMethods);
		if (clinitMethod != null) staticScanMethods.add(clinitMethod);

		// ==================== L1：存量初值重放的补丁前校验 ====================
		// 计划阶段只确认"旧/新初值都是常量"；这里再让正式提取器过一遍，确认写入切片确实
		// 是自包含的常量表达式、且每个根构造器都覆盖。失败的不进补丁、也不写台账（L1 是尽力重放）。
		Set<String>         acceptedReplay  = new LinkedHashSet<>();
		for (InitializerReplay.ReplayField replay : replayFields.values()) {
			String reason = replayExtractReason(
			 instanceExtracts.get(replay.name()), rootCtorCount, className, replay);
			if (reason != null) {
				replayDecisions.put(replay.name(), "rejected: " + reason);
				instanceExtracts.remove(replay.name());
				instanceReasons.putIfAbsent(replay.name(), reason);
			} else {
				replayDecisions.put(replay.name(), "replayed");
				acceptedReplay.add(replay.name());
			}
		}

		// ==================== 阶段 0.4：@RemoldReinit 初值指纹（docs/initfix/03-runtime-driver.md §4） ====================
		// 只对"封闭表达式"建指纹：它的值完全由自身字节码决定，字节码没变就等于值没变。
		// 开放表达式（跨类调用、他类静态字段……）这里拿不到 key —— 它们的值可能被外部改动带走，
		// 指纹相同也不能据此跳过，否则会静默留下过期值。
		Map<String, String> reinitKeys = new LinkedHashMap<>();
		for (Map.Entry<String, ReinitField> e : reinitFields.entrySet()) {
			String             f  = e.getKey();
			List<FieldExtract> ex = instanceExtracts.containsKey(f)
			 ? instanceExtracts.get(f) : staticExtracts.get(f);
			String key = reinitRecordKey(e.getValue(), ex);
			if (key != null) reinitKeys.put(f, key);
		}

		// ==================== 阶段 0.5：T0 零值等价过滤（docs/initfix/01-safety-gate.md §1） ====================
		// 显式 = null / = 0 / = false、仅声明未赋值、以及 static ConstantValue 按位为零的字段，
		// 存量实例与静态环境本来就是默认值：零开销放行，不生成补丁（也不会生成恒等 CAS 写）。
		// 浮点按位判定，-0.0f / -0.0d / NaN 的位模式非零，不算零值等价。
		//
		// 遮蔽新增字段是例外：DCEVM 迁移复制已经按 (name, desc) 把祖先槽位的值写进新槽，
		// 存量实例上这个槽位<b>不是</b>类型默认值。"零值等价"的前提在这里不成立，
		// 必须生成一次"祖先值 -> 新初值"的条件写，否则字段停在继承值上。
		synthesizeZeroValueShadowExtracts(nest, newClass, className, targetInstanceFields,
		 forceWriteFields, reinitFields, acceptedReplay, selfAssignedFields,
		 shadowAncestors, fieldNodes, instanceExtracts, rootCtorCount);
		// DCEVM 把"改字段描述符"当作"删旧字段 + 加新字段"，但**存量实例里的旧值会被保留**（实测：真机 JBR 21
		// 上读回来仍是原对象）。对这种字段，提取阶段通常拿不到可证明的初值（例如 this$0 由构造器参数赋值、
		// 没有任何常量来源），于是不生成补丁 —— 旧类型的对象就以新类型的身份留在堆里：之后任何 cast/调用都会炸，
		// 或者更糟，类型恰好可赋值时静默跑在错对象上。宁可显式清零（引用类型写 null、基本类型写 0，读错变成
		// NPE 而不是错对象），也不留一个类型错位的引用。
		Set<String> zeroInstanceFields = new LinkedHashSet<>();
		for (String f : targetInstanceFields) {
			if (selfAssignedFields.contains(f)) continue;
			if (reinitFields.containsKey(f)) continue;   // docs/initfix/03-runtime-driver.md §4：显式重置请求不受 T0 影响
			// L1 重放字段的"零值等价"不成立：存量值可能是旧初值（非零），新初值为 0 时也必须
			// 条件写回，不能按 T0 当作"已经是对的"跳过。
			if (acceptedReplay.contains(f)) continue;
			if (shadowAncestors.containsKey(f)) continue;   // 见上：遮蔽字段的存量槽位是继承值，不是类型默认值
			if (InitFixSafetyGate.isZeroEquivalentField(nest, newClass, fieldNodes.get(f), false, instanceExtracts)) {
				zeroInstanceFields.add(f);
			}
		}
		Set<String> zeroStaticFields = new LinkedHashSet<>();
		for (String f : targetStaticFields) {
			if (reinitFields.containsKey(f)) continue;
			if (InitFixSafetyGate.isZeroEquivalentField(nest, newClass, fieldNodes.get(f), true, staticExtracts)) {
				zeroStaticFields.add(f);
			}
		}
		for (String f : zeroInstanceFields) instanceExtracts.remove(f);
		for (String f : zeroStaticFields) staticExtracts.remove(f);
		if (!zeroInstanceFields.isEmpty() || !zeroStaticFields.isEmpty()) {
			log("Nothing to patch (zero-value equivalent) in " + className
			    + ": instance=" + zeroInstanceFields + ", static=" + zeroStaticFields);
		}

		// ==================== 阶段 0.6：@RemoldReinit 触发判定（docs/initfix/03-runtime-driver.md §4） ====================
		// 注解字段每轮都被拉进候选集（它永远不会再出现在 Diff 里）。若它的初值表达式一个字都没变，
		// 且表达式封闭（值只由自身字节码决定），这一轮重置唯一的效果就是把运行期改过的值打回去 ——
		// 只改了个方法体的热更不该动数据，跳过。
		//
		// 判不了的一律不跳过：开放表达式、新增字段（没有"上次"）、以及记录缺失（首轮/上次没写成）。
		// -Dremold.agent.force_reinit=true 时忽略指纹、全部重放（逃生口，见 RemoldAgent.FORCE_REINIT）。
		boolean forceReinit = RemoldAgent.FORCE_REINIT;
		Map<String, String> reinitTriggerReasons = new LinkedHashMap<>();
		Set<String> unchangedInstance = new LinkedHashSet<>();
		Set<String> unchangedStatic   = new LinkedHashSet<>();
		for (Map.Entry<String, ReinitField> e : reinitFields.entrySet()) {
			String f = e.getKey();
			List<FieldExtract> ex = instanceExtracts.containsKey(f)
			 ? instanceExtracts.get(f) : staticExtracts.get(f);
			String key = reinitRecordKey(e.getValue(), ex);
			boolean added = addedInstanceFields.contains(f) || addedStaticFields.contains(f);
			String applied = reinitAppliedGet(host, f);
			String reason = reinitTriggerReason(added, forceReinit, key != null, applied, key);
			reinitTriggerReasons.put(f, reason);
			if ("unchanged-initializer (skipped)".equals(reason)) {
				(staticExtracts.containsKey(f) ? unchangedStatic : unchangedInstance).add(f);
			}
		}
		for (String f : unchangedInstance) instanceExtracts.remove(f);
		for (String f : unchangedStatic) staticExtracts.remove(f);
		if (!reinitTriggerReasons.isEmpty()) {
			RemoldAgent.info("[REINIT] @RemoldReinit trigger on " + className.replace('/', '.') + ": "
				+ reinitTriggerReasons);
		}
		if (!unchangedInstance.isEmpty() || !unchangedStatic.isEmpty()) {
			RemoldAgent.info("[REINIT] @RemoldReinit kept as-is on " + className.replace('/', '.')
				+ " (initializer expression has not changed since last applied and is fully determined by bytecode; not rewriting): "
				+ "instance=" + unchangedInstance + ", static=" + unchangedStatic);
		}

		// ==================== 阶段 1：单字段判定（实例） ====================
		// 先把所有待处理字段标记为拒绝；有 extract 的会在此后覆盖为 ACCEPTED 或被具体原因拒绝。
		// 这样即便某字段在 extractFieldInits 里就被拒（不进 instanceExtracts），
		// PatchReport 里依然有它的一条决策记录，且原因是提取期记录下来的真实原因。
		Map<String, FieldDecision> instanceDecisions = new LinkedHashMap<>();
		for (String f : targetInstanceFields) {
			if (zeroInstanceFields.contains(f) || unchangedInstance.contains(f)) {
				instanceDecisions.put(f, FieldDecision.NOTHING_TO_PATCH);
				continue;
			}
			String reason = selfAssignedFields.contains(f)
			 ? "self-assignment from constructor parameter (patch would be a no-op)"
			 : instanceReasons.getOrDefault(f,
			 "no field initializer found in constructors");
			instanceDecisions.put(f, FieldDecision.rejected(reason));
		}
		Set<String> acceptedInstance = new LinkedHashSet<>();
		for (Map.Entry<String, List<FieldExtract>> e : instanceExtracts.entrySet()) {
			String             fieldName = e.getKey();
			List<FieldExtract> extracts  = e.getValue();

			int fromRootCount = 0;
			for (FieldExtract fe : extracts) {
				if (fe.fromRootCtor()) fromRootCount++;
			}

			String refuseReason;
			if (fromRootCount == 0) {
				refuseReason = "field is only initialized in delegating constructors";
			} else if (rootCtorCount > 1 && fromRootCount < rootCtorCount) {
				refuseReason = "field is only initialized in " + fromRootCount
				               + " of " + rootCtorCount + " root constructors";
			} else {
				// docs/initfix/01-safety-gate.md §4 构造器共识放宽：多根构造器 + 参数回溯并非绝对禁止。
				// 上面的分支已保证所有根构造器都覆盖了该字段，只要参数替换完成后的
				// 指令指纹 100% 一致，各构造路径的初始语义就是同构的，可以安全放行。
				// （源字段的不可变性由 docs/initfix/01-safety-gate.md §3 的证明在 scanParamFields 阶段保证。）
				refuseReason = fingerprintMismatchReason(extracts);
			}

			if (refuseReason != null) {
				log("Field '" + fieldName + "' appears in " + extracts.size()
				    + " constructors (" + fromRootCount + " root) of " + className
				    + "; refusing patch: " + refuseReason);
				instanceDecisions.put(fieldName, FieldDecision.rejected(refuseReason));
			} else {
				acceptedInstance.add(fieldName);
				instanceDecisions.put(fieldName, FieldDecision.ACCEPTED);
			}
		}

		// ==================== 静态字段：接受 clinit 的提取结果 ====================
		// 同上：先全部标记拒绝（带提取期记录的真实原因），再让 extract 命中的字段覆盖。
		Map<String, FieldDecision> staticDecisions = new LinkedHashMap<>();
		for (String f : targetStaticFields) {
			if (zeroStaticFields.contains(f) || unchangedStatic.contains(f)) {
				staticDecisions.put(f, FieldDecision.NOTHING_TO_PATCH);
				continue;
			}
			staticDecisions.put(f, FieldDecision.rejected(
			 staticReasons.getOrDefault(f,
				"no field initializer found in <clinit>")));
		}
		Set<String> acceptedStatic = new LinkedHashSet<>();
		for (String f : staticExtracts.keySet()) {
			acceptedStatic.add(f);
			staticDecisions.put(f, FieldDecision.ACCEPTED);
		}

		// ==================== 阶段 1.5 + 阶段 2：闭包迭代 ====================
		// 依赖门禁的判定基准：候选集减去 T0 零值等价字段。
		// 零值字段的存量值本来就是该类型的默认值，补丁不生成也"已经是对的"，
		// 因此它不构成"读到未补的新字段"—— 若不排除，a = f(b)（b = 0）会被误杀：
		// b 在 targetInstanceFields 里、却不在 acceptedInstance 里。
		// 两个集合在循环中都不被修改，故在建表后一次算好、循环内复用。
		Set<String> depInstance = new LinkedHashSet<>(targetInstanceFields);
		depInstance.removeAll(zeroInstanceFields);
		Set<String> depStatic = new LinkedHashSet<>(targetStaticFields);
		depStatic.removeAll(zeroStaticFields);

		Set<String> warnedExemptions = new HashSet<>();
		Map<String, Set<InitFixBatchGraph.FieldKey>> crossDependencies = new LinkedHashMap<>();
		Runnable close = () -> {
			crossDependencies.clear();
			// 跨类候选缺字节的拒绝必须发生在类内闭包<b>之前</b>：先踢出字段，再让 while(changed)
			// 把读它的类内下游一并收敛。放在闭包之后（历史顺序）会留下 X.c = X.a + 1 这种
			// "读着未补字段却被放行"的漏网。
			if (BATCH_CONTEXT.get() != null) {
				for (String field : new ArrayList<>(acceptedInstance)) {
					Optional<Set<InitFixBatchGraph.FieldKey>> deps =
					 batchDependencyOf(field, instanceExtracts, className, host.getClassLoader(), oracle);
					if (deps.isEmpty()) {
						instanceDecisions.put(field, FieldDecision.rejected(
						 "cross-class dependency unavailable: batch candidate bytes are missing"));
						acceptedInstance.remove(field);
					} else {
						crossDependencies.put(field, deps.get());
					}
				}
				for (String field : new ArrayList<>(acceptedStatic)) {
					Optional<Set<InitFixBatchGraph.FieldKey>> deps =
					 batchDependencyOf(field, staticExtracts, className, host.getClassLoader(), oracle);
					if (deps.isEmpty()) {
						staticDecisions.put(field, FieldDecision.rejected(
						 "cross-class dependency unavailable: batch candidate bytes are missing"));
						acceptedStatic.remove(field);
					} else {
						crossDependencies.put(field, deps.get());
					}
				}
			}
			// 注解豁免过的字段只告警一次，别在 while 循环里刷屏。
			boolean     changed          = true;
			while (changed) {
				changed = false;

				for (String f : new ArrayList<>(acceptedInstance)) {
					String reason = subsequentProcessingReason(
					 f, acceptedInstance, instanceExtracts, initMethods,
					 className, false, fieldNodes.get(f));
					if (reason == null) continue;
					if (reinitFields.containsKey(f)) {
						String warning = warnExemptedReinit(
						 className, f, fieldNodes.get(f), reason, false, warnedExemptions);
						instanceDecisions.put(f, FieldDecision.accepted(warning));
						continue;
					}
					log("Field '" + f + "' refused (subsequent processing): " + reason);
					acceptedInstance.remove(f);
					instanceDecisions.put(f, FieldDecision.rejected("subsequent processing: " + reason));
					changed = true;
				}

				for (String f : new ArrayList<>(acceptedStatic)) {
					String reason = subsequentProcessingReason(
					 f, acceptedStatic, staticExtracts, staticScanMethods,
					 className, true, fieldNodes.get(f));
					if (reason == null) continue;
					if (reinitFields.containsKey(f)) {
						String warning = warnExemptedReinit(
						 className, f, fieldNodes.get(f), reason, true, warnedExemptions);
						staticDecisions.put(f, FieldDecision.accepted(warning));
						continue;
					}
					log("Static field '" + f + "' refused (subsequent processing): " + reason);
					acceptedStatic.remove(f);
					staticDecisions.put(f, FieldDecision.rejected("subsequent processing: " + reason));
					changed = true;
				}

				for (String f : new ArrayList<>(acceptedInstance)) {
					String reason = depReason(
					 f, instanceExtracts.get(f), className,
					 acceptedInstance, acceptedStatic,
					 depInstance, depStatic);
					if (reason != null) {
						log("Field '" + f + "' refused (dependency): " + reason);
						acceptedInstance.remove(f);
						instanceDecisions.put(f, FieldDecision.rejected("dependency: " + reason));
						changed = true;
					}
				}

				for (String f : new ArrayList<>(acceptedStatic)) {
					String reason = depReason(
					 f, staticExtracts.get(f), className,
					 null, acceptedStatic,
					 null, depStatic);
					if (reason != null) {
						log("Static field '" + f + "' refused (dependency): " + reason);
						acceptedStatic.remove(f);
						staticDecisions.put(f, FieldDecision.rejected("dependency: " + reason));
						changed = true;
					}
				}

				// docs/initfix/02-closure-and-ledger.md §1 成环检测：并入同一固定点，而不是等闭包收敛后再做一次。
				//
				// 环成员被拒会改变接受集合，而依赖这些成员的字段可能早已通过 depReason
				// （跨组尤其明显：实例字段读静态字段时，静态侧成环被拒，实例侧早已放行）。
				// 若等闭包收敛后才检测，这些依赖方就是漏网之鱼 —— 补丁照常执行，
				// 读到未补的默认值，静默写入过期值。
				//
				// 收窄：只拒绝<b>环成员</b>（强连通分量），不整组拒绝。
				// 与环无关的字段因此得以保留 —— 整组拒绝虽安全，却白白损失热更覆盖面。
				// 安全性由闭包保证：环成员出局后，依赖它们的字段会在下一轮 depReason
				// 被连带拒绝；出口后置闭合校验（阶段 2.6）还会再兜一道底。
				Set<String> membersInstance = cycleMembers(acceptedInstance, instanceExtracts, className);
				if (!membersInstance.isEmpty()) {
					String desc = describeMembers(membersInstance);
					log("Cyclic dependency among instance fields of " + className
					    + "; refusing members: " + desc);
					for (String f : membersInstance) {
						if (acceptedInstance.remove(f)) {
							instanceDecisions.put(f, FieldDecision.rejected(
							 "cyclic dependency among instance fields: " + desc));
						}
					}
					changed = true;
				}

				Set<String> membersStatic = cycleMembers(acceptedStatic, staticExtracts, className);
				if (!membersStatic.isEmpty()) {
					String desc = describeMembers(membersStatic);
					log("Cyclic dependency among static fields of " + className
					    + "; refusing members: " + desc);
					for (String f : membersStatic) {
						if (acceptedStatic.remove(f)) {
							staticDecisions.put(f, FieldDecision.rejected(
							 "cyclic dependency among static fields: " + desc));
						}
					}
					changed = true;
				}
			}

			// ==================== 阶段 2.6：出口后置闭合校验（主防线） ====================
			// 不依赖前面的顺序是否正确：最终放行的集合必须对依赖闭合 ——
			// 每个放行字段读到的"本轮受补字段"都必须在放行集合内（T0 零值字段除外）。
			// 它同时覆盖 docs/initfix/02-closure-and-ledger.md §1（成环后依赖方不重判）与 docs/initfix/03-runtime-driver.md §2（静态依赖边缺失）以及将来
			// 同类的漏网。违反即整类拒绝并打 error，宁可少补也不静默写入过期值。
			String closureViolation = closureViolation(
			 acceptedInstance, acceptedStatic, instanceExtracts, staticExtracts,
			 depInstance, depStatic, className);
			if (closureViolation != null) {
				RemoldAgent.error("Field init patch refused for the whole class " + className
				                   + ": dependency closure violated: " + closureViolation);
				for (String f : new ArrayList<>(acceptedInstance)) {
					instanceDecisions.put(f, FieldDecision.rejected(
					 "dependency closure violated: " + closureViolation));
				}
				for (String f : new ArrayList<>(acceptedStatic)) {
					staticDecisions.put(f, FieldDecision.rejected(
					 "dependency closure violated: " + closureViolation));
				}
				acceptedInstance.clear();
				acceptedStatic.clear();
			}
		};
		close.run();
		return new PatchAnalysis(instanceDecisions, staticDecisions, crossDependencies, (field, reason) -> {
			if (acceptedInstance.remove(field)) instanceDecisions.put(field, FieldDecision.rejected(reason));
			if (acceptedStatic.remove(field)) staticDecisions.put(field, FieldDecision.rejected(reason));
		}, close, () -> emitPatch(host, className, hostVersion, targetInstanceFields, targetStaticFields,
		 forceWriteFields, selfOnlyFields, instanceExtracts, staticExtracts, instanceDecisions, staticDecisions,
		 acceptedInstance, acceptedStatic, acceptedReplay, replayFields, replayDecisions, applyFields,
		 applyDecisions, reinitFields, reinitKeys, crossDependencies, generational, shadowAncestors));
	}

	private static BuiltPatch emitPatch(Class<?> host, String className, int hostVersion,
	 Set<String> targetInstanceFields, Set<String> targetStaticFields, Set<String> forceWriteFields,
	 Set<String> selfOnlyFields, Map<String, List<FieldExtract>> instanceExtracts,
	 Map<String, List<FieldExtract>> staticExtracts, Map<String, FieldDecision> instanceDecisions,
	 Map<String, FieldDecision> staticDecisions, Set<String> acceptedInstance, Set<String> acceptedStatic,
	 Set<String> acceptedReplay, Map<String, InitializerReplay.ReplayField> replayFields,
	 Map<String, String> replayDecisions, Map<String, ApplyInitializerReplay.ReplayField> applyFields,
	 Map<String, String> applyDecisions, Map<String, ReinitField> reinitFields, Map<String, String> reinitKeys,
	 Map<String, Set<InitFixBatchGraph.FieldKey>> crossDependencies,
	 Map<String, GenerationalReplay> generational, Map<String, String> shadowAncestors) {
		// ==================== 阶段 2.5：拓扑排序 ====================
		// 到这里成环已被排除（阶段 2 的固定点），排序必定成功。
		List<String> orderedInstance = acceptedInstance.isEmpty()
		 ? List.of()
		 : topoSortFields(acceptedInstance, instanceExtracts, className);
		List<String> orderedStatic = acceptedStatic.isEmpty()
		 ? List.of()
		 : topoSortFields(acceptedStatic, staticExtracts, className);
		if (orderedInstance == null || orderedStatic == null) {
			// 理论上不可达：成环已在阶段 2 清空。真到了这里说明检测与排序口径不一致，
			// 属于实现缺陷，宁可整类拒绝也不发射依赖未闭合的补丁。
			RemoldAgent.error("Field init patch refused for the whole class " + className
			                   + ": cycle survived the fixpoint (instance="
			                   + (orderedInstance == null) + ", static="
			                   + (orderedStatic == null) + ")");
			for (String f : new ArrayList<>(acceptedInstance)) {
				instanceDecisions.put(f, FieldDecision.rejected("cycle survived fixpoint"));
			}
			for (String f : new ArrayList<>(acceptedStatic)) {
				staticDecisions.put(f, FieldDecision.rejected("cycle survived fixpoint"));
			}
			acceptedInstance.clear();
			acceptedStatic.clear();
			orderedInstance = List.of();
			orderedStatic = List.of();
		}

		// ==================== 阶段 3：逐字段发射（docs/initfix/03-runtime-driver.md §1 每字段独立静态方法） ====================
		// 每个放行字段单独一个静态直线方法：一行抛异常只影响该字段，不会像"单方法承载全部
		// 字段"那样跳过其后所有字段（docs/initfix/03-runtime-driver.md §2 的驱动依赖这一点做失败隔离）。
		String      hostInternal      = Type.getInternalName(host);
		Set<String> conditionalFields = new HashSet<>(targetInstanceFields);
		conditionalFields.addAll(targetStaticFields);
		conditionalFields.removeAll(forceWriteFields);

		ClassNode patch = new ClassNode();
		patch.version = Math.max(hostVersion, Opcodes.V11);
		patch.access = Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
		patch.name = className + PATCH_SUFFIX;
		patch.superName = "java/lang/Object";

		Set<String>          usedMethodNames = new HashSet<>();
		List<FieldPatchTask> instanceTasks   = new ArrayList<>();
		List<FieldPatchTask> staticTasks     = new ArrayList<>();
		List<InitFixPatchEmitter.BatchSliceSpec> batchSpecs      = new ArrayList<>();
		List<Integer>        batchIndices    = new ArrayList<>();
		boolean              hasInstanceDeps = false;

		// L1 重放字段 -> 旧初值常量（ASM bootstrap 常量）。只有通过补丁前校验的字段才携带。
		// 代际对账判 FACT 的字段，比较侧期望与写入值都改取那条事实（docs/vcs/05 §3.3）。
		Map<String, Object> replayArguments = new LinkedHashMap<>();
		Map<String, String> replayOwners = new LinkedHashMap<>();
		for (String fieldName : acceptedReplay) {
			InitializerReplay.ReplayField replay = replayFields.get(fieldName);
			if (replay == null) continue;
			GenerationalReplay fact = generational.get(L1_GATE_PREFIX + fieldName);
			replayArguments.put(fieldName,
			 fact != null ? fact.expectedConstant() : replay.bootstrapArgument());
			// 继承字段：事实与台账键都在声明类上（见 gateGenerationalReplay）。
			replayOwners.put(fieldName, replay.declaringClass());
		}

		for (String fieldName : orderedInstance) {
			FieldExtract chosen = null;
			for (FieldExtract fe : instanceExtracts.get(fieldName)) {
				if (fe.fromRootCtor()) {
					chosen = fe;
					break;
				}
			}
			if (chosen == null) chosen = instanceExtracts.get(fieldName).get(0);

			String method = InitFixPatchEmitter.uniqueMethodName(INSTANCE_PATCH_PREFIX, fieldName, usedMethodNames);
			GenerationalReplay fact = generational.get(L1_GATE_PREFIX + fieldName);
			List<AbstractInsnNode> chosenBody = new ArrayList<>(chosen.instructions());
			if (fact != null) {
				// 写入值也必须来自那条事实（§3.3 D6）：只剩"接收者 + 一个规范常量 + 唯一写指令"。
				chosenBody = withReplayValue(chosenBody, fact.writeConstant(), fact.fieldDesc());
			}
			List<AbstractInsnNode> body = InitFixPatchEmitter.rewriteFieldSlice(
			 hostInternal, className, chosenBody,
			 chosen.protectedAccesses(), conditionalFields, forceWriteFields, replayArguments,
			 shadowAncestors);
			boolean replayTask = acceptedReplay.contains(fieldName);
			boolean selfOnlyTask = replayTask || selfOnlyFields.contains(fieldName);
			String batchMethodName = null;
			ConstantInitSpec constantInit = null;
			InitFixPatchEmitter.BatchSliceSpec sliceSpec = InitFixPatchEmitter.inspectBatchSlice(fieldName, body, selfOnlyTask);
			if (sliceSpec != null) {
				String candidateBatchName = InitFixPatchEmitter.uniqueMethodName(BATCH_PATCH_PREFIX, fieldName, usedMethodNames);
				patch.methods.add(InitFixPatchEmitter.emitBatchMethod(candidateBatchName, className, sliceSpec));
				batchMethodName = candidateBatchName;
				batchSpecs.add(sliceSpec);
				batchIndices.add(instanceTasks.size());
				if (!replayTask) {
					constantInit = InitFixPatchEmitter.extractConstantInitSpec(fieldName, className, body);
				}
			}
			patch.methods.add(InitFixPatchEmitter.straightLineMethod(method, "(L" + className + ";)", body));
			// 依赖闭包必须同时统计实例侧与<b>静态侧</b>的读取。
			// 只传 acceptedInstance 会把切片里的 GETSTATIC 整个漏掉：静态字段补失败后，
			// 依赖它的实例任务不会被跳过，照常执行并读到未补的静态字段默认值 ——
			// 静默写入过期值，违反 docs/initfix/02-closure-and-ledger.md §1"切片不能依赖未补上的值"的核心原则。
			// （静态任务先于实例任务执行，其失败已落入共享的 failedFields，
			//  因此这里只要把边记全，跳过与记账就会自动发生。）
			Set<String> instanceDeps = new LinkedHashSet<>(
			 dependencyOf(fieldName, instanceExtracts, className, acceptedInstance));
			instanceDeps.addAll(
			 dependencyOf(fieldName, instanceExtracts, className, acceptedStatic));
			hasInstanceDeps |= !instanceDeps.isEmpty();
			Set<InitFixBatchGraph.FieldKey> batchDeps = crossDependencies.getOrDefault(fieldName, Set.of());
			for (InitFixBatchGraph.FieldKey dep : batchDeps) instanceDeps.add(batchFailureToken(dep));
			hasInstanceDeps |= !instanceDeps.isEmpty();
			instanceTasks.add(new FieldPatchTask(method, fieldName, false, instanceDeps,
			 batchDeps, selfOnlyTask, replayTask, batchMethodName, constantInit));
		}

		List<FusedBatchGroup> fusedBatchGroups = List.of();
		if (!hasInstanceDeps && batchSpecs.size() >= 2) {
			fusedBatchGroups = new ArrayList<>();
			for (int start = 0; start < batchSpecs.size(); start += MAX_FUSED_BATCH_FIELDS) {
				int end = Math.min(batchSpecs.size(), start + MAX_FUSED_BATCH_FIELDS);
				int groupSize = end - start;
				if (groupSize < 2) break;
				List<InitFixPatchEmitter.BatchSliceSpec> subSpecs = batchSpecs.subList(start, end);
				int[] indices = new int[groupSize];
				for (int k = 0; k < groupSize; k++) indices[k] = batchIndices.get(start + k);
				String groupMethod = InitFixPatchEmitter.uniqueMethodName(BATCH_GROUP_PREFIX, String.valueOf(fusedBatchGroups.size()),
				 usedMethodNames);
				patch.methods.add(InitFixPatchEmitter.emitFusedBatchMethod(groupMethod, className, subSpecs));
				fusedBatchGroups.add(new FusedBatchGroup(groupMethod, indices));
			}
		}

		for (String fieldName : orderedStatic) {
			FieldExtract fe     = staticExtracts.get(fieldName).get(0);
			String       method = InitFixPatchEmitter.uniqueMethodName(STATIC_PATCH_PREFIX, fieldName, usedMethodNames);
			List<AbstractInsnNode> body = InitFixPatchEmitter.rewriteFieldSlice(
			 hostInternal, className, new ArrayList<>(fe.instructions()),
			 fe.protectedAccesses(), conditionalFields, forceWriteFields, Map.of(), Map.of());
			patch.methods.add(InitFixPatchEmitter.straightLineMethod(method, "()", body));
			Set<InitFixBatchGraph.FieldKey> batchDeps = crossDependencies.getOrDefault(fieldName, Set.of());
			Set<String> staticDeps = new LinkedHashSet<>(
			 dependencyOf(fieldName, staticExtracts, className, acceptedStatic));
			for (InitFixBatchGraph.FieldKey dep : batchDeps) staticDeps.add(batchFailureToken(dep));
			staticTasks.add(new FieldPatchTask(method, fieldName, true, staticDeps, batchDeps));
		}

		// A 档 apply 重放（docs/initfix/13-kotlin-apply-replay.md §4 写入协议）：
		// 每个候选一个 `()V` 静态方法 = GETSTATIC holder.dest; <新常量>; indy(KIND_CONDITIONAL_EXPECTED)。
		// apply 任务名按 applyFields 的插入顺序编号（驱动侧用同一顺序解析句柄，不做名字重算）。
		// 目标字段属于 apply 对象类型 T，不属于宿主；expected 是旧初值常量，只有运行期值仍等于它才写。
		int applyIndex = 0;
		for (Map.Entry<String, ApplyInitializerReplay.ReplayField> applyEntry : applyFields.entrySet()) {
			ApplyInitializerReplay.ReplayField replay = applyEntry.getValue();
			String method = APPLY_REPLAY_PREFIX + applyIndex++;
			List<AbstractInsnNode> body = applyReplayBody(className, replay,
			 generational.get(APPLY_GATE_PREFIX + applyEntry.getKey()));
			patch.methods.add(InitFixPatchEmitter.straightLineMethod(method, "()", body));
			log("Emitting apply replay patch: " + className.replace('/', '.') + " -> "
			    + replay.key() + " (" + replay.oldText() + " -> " + replay.newText() + ")");
		}

		// 注解字段里"真的兑现了"的那些：只有进了有序任务表才算数（被安全门拒绝的字段不该被点名）。
		// applyPatch 靠它把"这个字段是注解要求重置的"和"这个字段是本次新增的"分开报，
		// 否则用户只看到 "Applying ... fields=1"，无从判断存量值为什么会被改。
		Map<String, String> reinitApplied = new LinkedHashMap<>();
		for (Map.Entry<String, ReinitField> e : reinitFields.entrySet()) {
			String f = e.getKey();
			if (!orderedInstance.contains(f) && !orderedStatic.contains(f)) continue;
			reinitApplied.put(f, e.getValue().overwrite() ? "OVERWRITE" : "CONDITIONAL");
		}

		PatchPlan plan = new PatchPlan(instanceTasks, staticTasks, reinitApplied, applyFields, fusedBatchGroups);

		// 记录本轮"应用了哪个初值表达式"（docs/initfix/03-runtime-driver.md §4）：下一轮据此判断是否还需要重置。
		// 只记真正进了补丁的字段；运行期失败/跳过由 applyPatch 撤回，redefine 失败/待补过期由对应路径整类作废。
		for (String f : reinitApplied.keySet()) {
			String key = reinitKeys.get(f);
			if (key != null) reinitAppliedPut(host, f, key);
		}

		PatchReport report = makeReport(plan, instanceDecisions, staticDecisions, replayFields, replayDecisions,
		 applyDecisions);

		if (plan.isEmpty()) {
			return new BuiltPatch(null, PatchPlan.EMPTY, report, Map.of());
		}

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		patch.accept(cw);
		return new BuiltPatch(cw.toByteArray(), plan, report, replayOwners);
	}

	/**
	 * 组装 {@link PatchReport}：L1/A 档重放字段不进 {@link #instanceFields}（否则会被台账/告警当成
	 * "本应补但没补"），也不改变"默认补丁是否生成"的语义。
	 */
	private static PatchReport makeReport(
	 PatchPlan plan,
	 Map<String, FieldDecision> instanceDecisions,
	 Map<String, FieldDecision> staticDecisions,
	 Map<String, InitializerReplay.ReplayField> replayFields,
	 Map<String, String> replayDecisions,
	 Map<String, String> applyDecisions) {

		for (String field : replayFields.keySet()) instanceDecisions.remove(field);

		boolean onlyReplay = !plan.isEmpty();
		for (FieldPatchTask task : plan.allTasks()) {
			if (!task.replay()) {
				onlyReplay = false;
				break;
			}
		}
		return new PatchReport(
		 Collections.unmodifiableMap(instanceDecisions),
		 Collections.unmodifiableMap(staticDecisions),
		 !plan.isEmpty() && !onlyReplay,
		 replayDecisions,
		 applyDecisions,
		 Map.of());
	}

	/**
	 * A 档 apply 重放的补丁体（{@code docs/initfix/13-kotlin-apply-replay.md} §4）：
	 * <pre>
	 *   GETSTATIC holder.dest : LT;
	 *   &lt;new constant&gt;
	 *   INVOKEDYNAMIC field (LT;I)V  bootstrapExpected(KIND_CONDITIONAL_EXPECTED, PUTFIELD,
	 *                                                 T.class, holder.class, old constant)
	 * </pre>
	 * {@code GETSTATIC} 是从 {@code <clinit>} 里唯一存储该对象的静态字段取回那<b>一个</b>存量对象
	 * （唯一 provenance，不枚举堆）；条件 CAS 由 {@link HotswapBridge#bootstrapExpected} 完成。
	 */
	private static List<AbstractInsnNode> applyReplayBody(
	 String holderInternal, ApplyInitializerReplay.ReplayField replay, GenerationalReplay fact) {

		List<AbstractInsnNode> body = new ArrayList<>(4);
		body.add(new FieldInsnNode(Opcodes.GETSTATIC, holderInternal,
		 replay.dest(), replay.destDesc()));
		body.add(applyReplayConstant(fact != null ? fact.writeConstant() : replay.newRaw(),
		 replay.fieldDesc()));
		body.add(new InvokeDynamicInsnNode(
		 replay.fieldName(),
		 "(" + replay.destDesc() + replay.fieldDesc() + ")V",
		 BRIDGE_BSM_EXPECTED,
		 new Object[]{
		  HotswapBridge.KIND_CONDITIONAL_EXPECTED,
		  Opcodes.PUTFIELD,
		  Type.getObjectType(replay.targetClass()),
		  Type.getObjectType(holderInternal),
		  fact != null ? fact.expectedConstant() : replay.bootstrapArgument()
		 }));
		return body;
	}

	/** 把新初值常量压栈的指令；常量类别由目标字段描述符决定（B/C/S/Z 均以 int 压栈）。 */
	private static AbstractInsnNode applyReplayConstant(Object raw, String fieldDesc) {
		if (!(raw instanceof Number number)) {
			throw new IllegalArgumentException("apply replay constant must be numeric, got " + raw);
		}
		char kind = fieldDesc.charAt(0);
		if (kind == 'J') {
			long value = number.longValue();
			if (value == 0L) return new InsnNode(Opcodes.LCONST_0);
			if (value == 1L) return new InsnNode(Opcodes.LCONST_1);
			return new LdcInsnNode(value);
		}
		if (kind == 'F') {
			float value = number.floatValue();
			if (value == 0.0f && Float.floatToRawIntBits(value) == 0) return new InsnNode(Opcodes.FCONST_0);
			if (value == 1.0f) return new InsnNode(Opcodes.FCONST_1);
			if (value == 2.0f) return new InsnNode(Opcodes.FCONST_2);
			return new LdcInsnNode(value);
		}
		if (kind == 'D') {
			double value = number.doubleValue();
			if (value == 0.0d && Double.doubleToRawLongBits(value) == 0L) return new InsnNode(Opcodes.DCONST_0);
			if (value == 1.0d) return new InsnNode(Opcodes.DCONST_1);
			return new LdcInsnNode(value);
		}
		int value = number.intValue();
		if (value >= -1 && value <= 5) return new InsnNode(Opcodes.ICONST_0 + value);
		if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, value);
		if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, value);
		return new LdcInsnNode(value);
	}

	/**
	 * L1 补丁前校验：重放切片必须是"接收者 this + 自包含常量 + 唯一写指令"。
	 * 提取器允许的其它形态（读字段、调用、装箱等）一律拒绝——重放的是初值表达式，不是构造器。
	 */
	private static String replayExtractReason(
	 List<FieldExtract> extracts, int rootCtorCount, String className,
	 InitializerReplay.ReplayField replay) {

		if (extracts == null || extracts.isEmpty()) {
			return "no safe initialization expression found in constructors";
		}
		if (extracts.size() != 1) {
			return "field is written in " + extracts.size() + " constructors";
		}
		if (rootCtorCount > 1) {
			return "class has " + rootCtorCount + " root constructors";
		}
		FieldExtract extract = extracts.get(0);
		if (!extract.fromRootCtor()) return "field is only initialized in delegating constructors";

		List<AbstractInsnNode> body = extract.instructions();
		if (body.isEmpty()) return "empty write slice";

		AbstractInsnNode last = body.get(body.size() - 1);
		if (!(last instanceof FieldInsnNode put)
		    || put.getOpcode() != Opcodes.PUTFIELD
		    || !put.owner.equals(className)
		    || !put.name.equals(replay.name())
		    || !put.desc.equals(replay.desc())) {
			return "unexpected write instruction shape";
		}
		if (!(body.get(0) instanceof VarInsnNode receiver)
		    || receiver.getOpcode() != Opcodes.ALOAD
		    || receiver.var != 0) {
			return "receiver is not this";
		}
		for (int i = 1; i < body.size() - 1; i++) {
			if (!isReplayValueInsn(body.get(i))) {
				return "new initializer expression is not a self-contained constant";
			}
		}
		return null;
	}

	/** 重放切片里允许出现的"纯值"指令：常量与数值转换（javac 已把常量算术折叠掉）。 */
	private static boolean isReplayValueInsn(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return true;
		if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return true;
		if (op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1) return true;
		if (op == Opcodes.FCONST_0 || op == Opcodes.FCONST_1 || op == Opcodes.FCONST_2) return true;
		if (op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1) return true;
		if (insn instanceof LdcInsnNode ldc) {
			Object cst = ldc.cst;
			return cst instanceof Integer || cst instanceof Long
			       || cst instanceof Float || cst instanceof Double;
		}
		return switch (op) {
			case Opcodes.I2L, Opcodes.I2F, Opcodes.I2D,
			     Opcodes.L2I, Opcodes.L2F, Opcodes.L2D,
			     Opcodes.F2I, Opcodes.F2L, Opcodes.F2D,
			     Opcodes.D2I, Opcodes.D2L, Opcodes.D2F,
			     Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> true;
			default -> false;
		};
	}

	/** L1 结果日志：成功走 info（点名新值/旧值），失败走 warn（配置了白名单却重放不了）。 */
	private static void logReplayResult(
	 String className, PatchReport report,
	 Map<String, InitializerReplay.ReplayField> replayFields) {

		if (report == null || report.replayFields().isEmpty()) return;
		String display = className.replace('/', '.');
		for (Map.Entry<String, String> entry : report.replayFields().entrySet()) {
			InitializerReplay.ReplayField replay = replayFields.get(entry.getKey());
			String result = entry.getValue();
			if ("replayed".equals(result)) {
				RemoldAgent.info("[INIT-REPLAY] " + display + "." + entry.getKey()
				                 + " <- " + (replay == null ? "?" : replay.newText())
				                 + " (conditional on old initializer "
				                 + (replay == null ? "?" : replay.oldText())
				                 + "; only live instances of this anonymous class)");
			} else if (result != null && result.startsWith("rejected: ")) {
				RemoldAgent.warn("[INIT-REPLAY] " + display + "." + entry.getKey()
				                 + " NOT replayed: " + result.substring("rejected: ".length()));
			}
		}
	}

	/**
	 * A 档 apply 重放的拒绝告警出口：本轮计划里所有 {@code rejected: } 决策逐条 warn。
	 * 与 L1 一样，拒绝必须可见（{@code AGENTS.md} §3）；成功明细由
	 * {@link #logApplyReplayResult} 在补丁装配后输出。
	 */
	private static void logApplyReplayDecisions(String display, Map<String, String> decisions) {
		if (decisions == null || decisions.isEmpty()) return;
		for (Map.Entry<String, String> entry : decisions.entrySet()) {
			String result = entry.getValue();
			if (result != null && result.startsWith("rejected: ")) {
				RemoldAgent.warn("[INIT-APPLY-REPLAY] " + display + ": " + entry.getKey()
				                 + " NOT replayed: " + result.substring("rejected: ".length()));
			}
		}
	}

	/**
	 * 重放通道的运行期对账（{@code docs/vcs/05-generational-field-ledger.md} §3.3 / G6 / G7）。
	 *
	 * <p><b>CAS 未命中 = 没写成</b>：条件写在"字段当前值已不是目标代的期望初值"时按设计跳过。跳过必须
	 * 是<b>可见的未完成</b>，不能读成"已改回"：</p>
	 * <ul>
	 *   <li>{@link PatchReport#applyReplayFields()} / {@link PatchReport#replayFields()} 该条改成
	 *       {@code skipped: ...}（不记"replayed"）；</li>
	 *   <li>发一条 warn 点名字段；</li>
	 *   <li>债务落 {@link GenerationalFieldLedger#markUnclosed}——<b>不</b>复用 {@link #PENDING}
	 *       弱键瞬时槽（那是补丁暂存，带 5 分钟 TTL，塞台账语义会同时破坏弱键不变量与
	 *       "台账只负责不遗忘"的分工）。</li>
	 * </ul>
	 *
	 * <p><b>OVERWRITE 写成 = 闭环</b>：{@code @RemoldReinit(mode = OVERWRITE)}（或显式重建）真的写了
	 * 该字段时，把台账里那条债务 {@link GenerationalFieldLedger#markClosed} 清掉，并在
	 * {@link PatchReport#recoveredFields()} 里记 {@code recovered: overwrite}，下一代不再把它当成欠着。</p>
	 */
	static void recordReplayOutcome(Class<?> host, PatchPlan plan,
	                                        Map<String, String> replayOwners,
	                                        Map<String, long[]> l1Stats, Map<String, long[]> applyStats,
	                                        Set<String> failedFields, Set<String> skippedFields,
	                                        int aliveCount) {
		ClassLoader loader = host.getClassLoader();
		String internal = Type.getInternalName(host);
		Set<String> skipped = new LinkedHashSet<>();

		for (FieldPatchTask task : plan.instanceTasks()) {
			if (!task.replay()) continue;
			long[] stat = l1Stats.get(host.getName() + "#" + task.fieldName());
			if (stat == null || stat[1] == 0L) continue;
			// L1 的字段是继承字段：台账键是声明类，不是本轮被热更的匿名宿主。
			String owner = replayOwners.getOrDefault(task.fieldName(), internal);
			skipped.add(task.fieldName());
			GenerationalFieldLedger.INSTANCE.markUnclosed(loader, owner, task.fieldName());
			RemoldAgent.warn("[INIT-REPLAY] " + host.getName() + "." + task.fieldName()
			 + " was NOT replayed: the current value no longer equals the target generation's"
			 + " expected initializer, so the conditional write was refused (fail-closed)."
			 + " The field stays unclosed in GenerationalFieldLedger;"
			 + " use @RemoldReinit(mode = OVERWRITE) or rebuild it explicitly to recover.");
		}

		for (Map.Entry<String, ApplyInitializerReplay.ReplayField> entry : plan.applyFields().entrySet()) {
			ApplyInitializerReplay.ReplayField replay = entry.getValue();
			String statKey = replay.targetClass().replace('/', '.') + "#" + replay.fieldName();
			long[] stat = applyStats.get(statKey);
			if (stat == null || stat[1] == 0L) continue;
			skipped.add(entry.getKey());
			GenerationalFieldLedger.INSTANCE.markUnclosed(loader, replay.targetClass(), replay.fieldName());
			RemoldAgent.warn("[INIT-APPLY-REPLAY] " + host.getName() + ": " + entry.getKey()
			 + " was NOT replayed: the current value no longer equals the target generation's"
			 + " expected initializer, so the conditional write was refused (fail-closed)."
			 + " The field stays unclosed in GenerationalFieldLedger;"
			 + " use @RemoldReinit(mode = OVERWRITE) or rebuild it explicitly to recover.");
		}

		// OVERWRITE / 显式重建真的写进去时闭环（G7）。只有确实执行过写才敢出账：
		// 静态字段一定执行；实例字段要求本轮至少枚举到一个实例。
		Map<String, String> recovered = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : plan.reinitFields().entrySet()) {
			String field = entry.getKey();
			if (!"OVERWRITE".equals(entry.getValue())) continue;
			if (failedFields.contains(field) || skippedFields.contains(field)) continue;
			boolean isStatic = false;
			boolean known = false;
			for (FieldPatchTask task : plan.staticTasks()) {
				if (task.fieldName().equals(field)) {
					isStatic = true;
					known = true;
					break;
				}
			}
			for (FieldPatchTask task : plan.instanceTasks()) {
				if (task.fieldName().equals(field)) {
					known = true;
					break;
				}
			}
			if (!known) continue;
			if (!isStatic && aliveCount == 0) continue;
			if (!GenerationalFieldLedger.INSTANCE.isUnclosed(loader, internal, field)) continue;
			GenerationalFieldLedger.INSTANCE.markClosed(loader, internal, field);
			recovered.put(field, RECOVERED_BY_OVERWRITE);
			RemoldAgent.info("[INIT-REPLAY] " + host.getName() + "." + field
			 + " was recovered by @RemoldReinit(OVERWRITE); its generational ledger debt is closed");
		}

		if (skipped.isEmpty() && recovered.isEmpty()) return;
		PatchReport base = REPORTS.get(host);
		if (base == null) return;
		Map<String, String> replay = new LinkedHashMap<>(base.replayFields());
		Map<String, String> apply = new LinkedHashMap<>(base.applyReplayFields());
		for (String key : skipped) {
			String result = SKIP_RESULT_PREFIX
			 + "current value no longer equals the target generation's expected initializer";
			if (replay.containsKey(key)) replay.put(key, result);
			else apply.put(key, result);
		}
		Map<String, String> recoveredAll = new LinkedHashMap<>(base.recoveredFields());
		recoveredAll.putAll(recovered);
		REPORTS.put(host, new PatchReport(base.instanceFields(), base.staticFields(),
		 base.patchGenerated(), replay, apply, recoveredAll));
	}

	/** A 档 apply 重放的成功明细（每个候选一行 info；拒绝已由 {@link #logApplyReplayDecisions} 报过）。 */
	private static void logApplyReplayResult(
	 String className, PatchReport report,
	 Map<String, ApplyInitializerReplay.ReplayField> applyFields) {

		if (report == null || report.applyReplayFields().isEmpty()) return;
		String display = className.replace('/', '.');
		for (Map.Entry<String, String> entry : report.applyReplayFields().entrySet()) {
			if (!"replayed".equals(entry.getValue())) continue;
			ApplyInitializerReplay.ReplayField replay = applyFields.get(entry.getKey());
			if (replay == null) continue;
			RemoldAgent.info("[INIT-APPLY-REPLAY] " + display + ": " + entry.getKey()
			                 + " <- " + replay.newText()
			                 + " (conditional on old initializer " + replay.oldText()
			                 + "; single live object read back through GETSTATIC " + replay.dest() + ")");
		}
	}


	/**
	 * Cross-class prerequisites read by one field's init slice.
	 *
	 * <p>Returns {@link Optional#empty()} when the slice reads a field of a <b>batch candidate</b> whose final
	 * bytes are not in the batch view: reading the stale global cache there would let the field be patched from
	 * a schema that will never be installed. Callers must reject the dependent field instead (fail closed).</p>
	 */
	private static Optional<Set<InitFixBatchGraph.FieldKey>> batchDependencyOf(
	 String fieldName, Map<String, List<FieldExtract>> extracts, String className,
	 ClassLoader loader, HierarchyTreeOracle oracle) {
		Set<InitFixBatchGraph.FieldKey> deps = new LinkedHashSet<>();
		BatchContext context = BATCH_CONTEXT.get();
		if (context == null) return Optional.of(deps);
		List<FieldExtract> fieldExtracts = extracts.get(fieldName);
		if (fieldExtracts == null) return Optional.of(deps);
		Map<String, byte[]> batchBytes = context.bytesByLoader().get(loader);
		for (FieldExtract extract : fieldExtracts) {
			for (AbstractInsnNode insn : extract.instructions()) {
				if (!(insn instanceof FieldInsnNode access)) continue;
				int opcode = access.getOpcode();
				if (opcode != Opcodes.GETFIELD && opcode != Opcodes.GETSTATIC) continue;
				Optional<ClassHierarchyOracle.MemberRef> member = oracle.resolveMember(
				 access.owner, access.name, access.desc, true);
				String owner = member.map(ref -> ref.declaringClass.replace('/', '.'))
				 .orElse(access.owner.replace('/', '.'));
				// In-class reads stay out of the cross-class graph: the local closure/topoSort already orders
				// them, and feeding them back would create self-loops (a field reading itself) plus spurious
				// "@batch:" tokens on ordinary same-class dependencies.
				if (owner.equals(className.replace('/', '.'))) continue;
				String ownerKey = batchCandidateOwnerKey(context, owner, loader);
				if (ownerKey != null && batchBytes != null && !batchBytes.containsKey(owner)) return Optional.empty();
				if (member.isEmpty()) {
					if (ownerKey != null && context.candidateFields().getOrDefault(ownerKey, Set.of()).contains(access.name)) {
						deps.add(new InitFixBatchGraph.FieldKey(ownerKey, access.name));
					}
					continue;
				}
				if (((member.get().access & Opcodes.ACC_STATIC) != 0) != (opcode == Opcodes.GETSTATIC)) continue;
				if (ownerKey == null || !context.candidateFields().getOrDefault(ownerKey, Set.of()).contains(access.name)) continue;
				deps.add(new InitFixBatchGraph.FieldKey(ownerKey, access.name));
			}
		}
		return Optional.of(deps);
	}

	/** 该字段切片读取的、同样在本轮受补的其它字段（docs/initfix/03-runtime-driver.md §2 的依赖失败传播用）。 */
	private static Set<String> dependencyOf(
	 String fieldName, Map<String, List<FieldExtract>> extracts,
	 String className, Set<String> acceptedFields) {

		Set<String>        deps   = new LinkedHashSet<>();
		List<FieldExtract> feList = extracts.get(fieldName);
		if (feList == null) return deps;
		for (FieldExtract fe : feList) {
			for (AbstractInsnNode n : fe.instructions()) {
				if (!(n instanceof FieldInsnNode fld)) continue;
				if (!fld.owner.equals(className)) continue;
				if (fld.name.equals(fieldName) || !acceptedFields.contains(fld.name)) continue;
				int op = fld.getOpcode();
				if (op == Opcodes.GETFIELD || op == Opcodes.GETSTATIC) deps.add(fld.name);
			}
		}
		return deps;
	}

	// ==================== 拓扑排序 ====================

	/**
	 * 对一组已放行字段做拓扑排序，保证被依赖字段先执行。
	 * <p>依赖关系从 {@link FieldExtract#instructions()} 里的 GETFIELD/GETSTATIC 派生。
	 * 返回 null 表示存在循环依赖。</p>
	 */
	private static List<String> topoSortFields(
	 Set<String> fields,
	 Map<String, List<FieldExtract>> extracts,
	 String className) {

		Map<String, Set<String>> deps = new LinkedHashMap<>();
		for (String f : fields) {
			Set<String>        d      = new LinkedHashSet<>();
			List<FieldExtract> feList = extracts.get(f);
			if (feList != null) {
				for (FieldExtract fe : feList) {
					for (AbstractInsnNode n : fe.instructions()) {
						if (!(n instanceof FieldInsnNode fld)) continue;
						if (!fld.owner.equals(className)) continue;
						if (fld.name.equals(f)) continue;
						if (!fields.contains(fld.name)) continue;
						int op = fld.getOpcode();
						if (op == Opcodes.GETFIELD || op == Opcodes.GETSTATIC) {
							d.add(fld.name);
						}
					}
				}
			}
			deps.put(f, d);
		}

		List<String> order    = new ArrayList<>(fields.size());
		Set<String>  visited  = new HashSet<>();
		Set<String>  visiting = new HashSet<>();
		for (String f : fields) {
			if (!topoVisit(f, deps, visited, visiting, order)) return null;
		}
		return order;
	}

	/**
	 * 找出参与依赖环的字段集合（强连通分量里 size > 1 的成员，外加自环）。
	 *
	 * <p>用于把成环拒绝<b>收窄</b>到环成员本身：整组拒绝会连带拒掉与环无关的字段
	 * （安全但白白损失热更覆盖面）。收窄后由闭包负责把拒绝传播给依赖环成员的字段 ——
	 * 因此安全性不依赖这个函数，它只决定"少拒了多少"。</p>
	 *
	 * <p>实现是 Tarjan 迭代版（避免深依赖链上的栈溢出）。</p>
	 * @return 环成员集合；无环返回空集
	 */
	private static Set<String> cycleMembers(
	 Set<String> fields, Map<String, List<FieldExtract>> extracts, String className) {

		Map<String, Set<String>> deps = dependencyGraph(fields, extracts, className);

		Map<String, Integer> index   = new HashMap<>();
		Map<String, Integer> lowlink = new HashMap<>();
		Set<String>          onStack = new LinkedHashSet<>();
		Deque<String>        stack   = new ArrayDeque<>();
		Set<String>          members = new LinkedHashSet<>();
		int[]                counter = {0};

		for (String root : fields) {
			if (index.containsKey(root)) continue;

			// 迭代式 Tarjan：显式栈保存 (节点, 待访问邻居迭代器)
			Deque<String>           nodeStack = new ArrayDeque<>();
			Deque<Iterator<String>> iterStack = new ArrayDeque<>();

			index.put(root, counter[0]);
			lowlink.put(root, counter[0]);
			counter[0]++;
			stack.push(root);
			onStack.add(root);
			nodeStack.push(root);
			iterStack.push(deps.getOrDefault(root, Set.of()).iterator());

			while (!nodeStack.isEmpty()) {
				String           v  = nodeStack.peek();
				Iterator<String> it = iterStack.peek();

				if (it.hasNext()) {
					String w = it.next();
					if (!index.containsKey(w)) {
						index.put(w, counter[0]);
						lowlink.put(w, counter[0]);
						counter[0]++;
						stack.push(w);
						onStack.add(w);
						nodeStack.push(w);
						iterStack.push(deps.getOrDefault(w, Set.of()).iterator());
					} else if (onStack.contains(w)) {
						lowlink.put(v, Math.min(lowlink.get(v), index.get(w)));
					}
					continue;
				}

				// v 的邻居已访问完：结算
				nodeStack.pop();
				iterStack.pop();
				if (!nodeStack.isEmpty()) {
					String parent = nodeStack.peek();
					lowlink.put(parent, Math.min(lowlink.get(parent), lowlink.get(v)));
				}

				if (lowlink.get(v).equals(index.get(v))) {
					// v 是一个 SCC 的根：弹出该分量
					Set<String> component = new LinkedHashSet<>();
					String      w;
					do {
						w = stack.pop();
						onStack.remove(w);
						component.add(w);
					} while (!w.equals(v));

					// size > 1 即真环；size == 1 时只有自环才算（自读已在建图时排除）
					if (component.size() > 1) members.addAll(component);
				}
			}
		}
		return members;
	}

	/** 把环成员集合渲染成可读文案（按字段名排序，保证稳定可断言）。 */
	private static String describeMembers(Set<String> members) {
		List<String> sorted = new ArrayList<>(members);
		Collections.sort(sorted);
		return String.join(", ", sorted);
	}

	/** 依赖图：字段 -> 它在本轮受补集合内读到的其它字段（与拓扑排序同一口径）。 */
	private static Map<String, Set<String>> dependencyGraph(
	 Set<String> fields, Map<String, List<FieldExtract>> extracts, String className) {

		Map<String, Set<String>> deps = new LinkedHashMap<>();
		for (String f : fields) {
			Set<String>        d      = new LinkedHashSet<>();
			List<FieldExtract> feList = extracts.get(f);
			if (feList != null) {
				for (FieldExtract fe : feList) {
					for (AbstractInsnNode n : fe.instructions()) {
						if (!(n instanceof FieldInsnNode fld)) continue;
						if (!fld.owner.equals(className)) continue;
						if (fld.name.equals(f)) continue;
						if (!fields.contains(fld.name)) continue;
						int op = fld.getOpcode();
						if (op == Opcodes.GETFIELD || op == Opcodes.GETSTATIC) d.add(fld.name);
					}
				}
			}
			deps.put(f, d);
		}
		return deps;
	}

	/**
	 * 出口后置闭合校验（{@code docs/initfix/02-closure-and-ledger.md} §1 的主防线）。
	 *
	 * <p>断言：最终放行的每个字段，其切片读到的<b>本轮受补</b>字段也必须已放行。
	 * 它不依赖前置阶段的顺序是否正确，因此对"成环后依赖方不重判"（跨组漏网）、
	 * "静态依赖边缺失"以及将来同类的漏网一律有效。</p>
	 *
	 * <p>判定基准 = {@code depInstance ∪ depStatic} 再扣掉 T0 零值字段：零值字段存量值
	 * 本来就是默认值，读它不算读"未补的值"。<b>必须是并集</b> —— 只传实例侧会把切片里的
	 * GETSTATIC 整个漏掉，而"实例字段读静态字段"恰恰是跨组漏网的主要形态：
	 * 曾经只传 {@code depInstance}，导致这道校验对它的目标场景完全失明
	 * （已由"关闭闭包传播"的安全探针实测暴露）。</p>
	 * @return 违规描述；闭合则返回 {@code null}
	 */
	private static String closureViolation(
	 Set<String> acceptedInstance, Set<String> acceptedStatic,
	 Map<String, List<FieldExtract>> instanceExtracts,
	 Map<String, List<FieldExtract>> staticExtracts,
	 Set<String> depInstance, Set<String> depStatic, String className) {

		// 跨组依赖必须可见：实例切片可以读静态字段，静态 <clinit> 也可以读实例字段吗？
		// 后者不需要（<clinit> 无 this），但统一用并集不会有副作用 ——
		// dependencyOf 内部按 owner 过滤，取并集只是让两侧的读取都不被漏掉。
		Set<String> depAll = new LinkedHashSet<>(depInstance);
		depAll.addAll(depStatic);

		for (String f : acceptedInstance) {
			for (String dep : dependencyOf(f, instanceExtracts, className, depAll)) {
				// 依赖可以在实例侧或静态侧被放行（跨组依赖是合法的）
				if (!acceptedInstance.contains(dep) && !acceptedStatic.contains(dep)) {
					return "instance field '" + f + "' reads '" + dep
					       + "' which is not patched (and not zero-value equivalent)";
				}
			}
		}

		for (String f : acceptedStatic) {
			for (String dep : dependencyOf(f, staticExtracts, className, depAll)) {
				if (!acceptedInstance.contains(dep) && !acceptedStatic.contains(dep)) {
					return "static field '" + f + "' reads '" + dep
					       + "' which is not patched (and not zero-value equivalent)";
				}
			}
		}
		return null;
	}

	private static boolean topoVisit(
	 String f,
	 Map<String, Set<String>> deps,
	 Set<String> visited,
	 Set<String> visiting,
	 List<String> order) {

		if (visited.contains(f)) return true;
		if (!visiting.add(f)) return false;
		for (String dep : deps.getOrDefault(f, Set.of())) {
			if (!topoVisit(dep, deps, visited, visiting, order)) return false;
		}
		visiting.remove(f);
		visited.add(f);
		order.add(f);
		return true;
	}

	// ==================== docs/initfix/03-runtime-driver.md §4 存量重置扩展：注解扫描 ====================

	/**
	 * 扫描带 {@code @RemoldReinit} 的字段，解析其写入协议与实例作用域。
	 *
	 * <p>只做<b>描述符字符串匹配</b>，不加载注解类 —— 与 {@code docs/initfix/01-safety-gate.md} §5 的离线层级接口同一原则：
	 * 在 transform 线程上 {@code Class.forName} 注解类型可能触发类加载死锁。
	 * 元素缺省时按注解声明取默认值：{@code mode = OVERWRITE}、{@code scope = INCLUDE_SUBCLASSES}。</p>
	 */
	private static Map<String, ReinitField> scanReinitFields(ClassNode newClass) {
		Map<String, ReinitField> out = new LinkedHashMap<>();
		for (FieldNode f : newClass.fields) {
			AnnotationNode reinit = findReinit(f);
			if (reinit == null) continue;
			out.put(f.name, new ReinitField(readOverwriteMode(reinit),
			 readSelfOnlyScope(reinit), hasScopeElement(reinit)));
		}
		return out;
	}

	private static AnnotationNode findReinit(FieldNode f) {
		AnnotationNode found = findReinit(f.visibleAnnotations);
		return found != null ? found : findReinit(f.invisibleAnnotations);
	}

	private static AnnotationNode findReinit(List<AnnotationNode> annotations) {
		if (annotations == null) return null;
		for (AnnotationNode a : annotations) {
			if (isReinitDesc(a.desc)) return a;
		}
		return null;
	}

	/**
	 * 读 {@code mode} 元素。ASM 把注解里的枚举值表示成 {@code String[]{描述符, 常量名}}。
	 * @return true 表示 {@code OVERWRITE}（默认值也是它）
	 */
	private static boolean readOverwriteMode(AnnotationNode a) {
		if (a.values == null) return true;
		for (int i = 0; i + 1 < a.values.size(); i += 2) {
			if (!"mode".equals(a.values.get(i))) continue;
			Object v = a.values.get(i + 1);
			if (v instanceof String[] enumValue && enumValue.length == 2
			    && isReinitModeDesc(enumValue[0])) {
				return !"CONDITIONAL".equals(enumValue[1]);
			}
			if (v instanceof String[] other) {
				log("Unexpected @RemoldReinit mode representation: "
				    + Arrays.toString(other) + "; falling back to OVERWRITE");
			}
			return true;
		}
		return true;
	}

	/**
	 * 读 {@code scope} 元素（{@code docs/initfix/03-runtime-driver.md} §4）。
	 *
	 * <p>ASM 把注解里的枚举值表示成 {@code String[]{描述符, 常量名}}。缺省、无法识别或形态异常时
	 * 一律退回默认的 {@code INCLUDE_SUBCLASSES}：那是"覆盖子类实例"的既有行为，
	 * 也是更保守的取法（漏掉子类实例意味着用户要求的重置没发生）。</p>
	 *
	 * @return true 表示 {@code SELF}（只重置恰好等于声明类的实例）
	 */
	private static boolean readSelfOnlyScope(AnnotationNode a) {
		if (a.values == null) return false;
		for (int i = 0; i + 1 < a.values.size(); i += 2) {
			if (!"scope".equals(a.values.get(i))) continue;
			Object v = a.values.get(i + 1);
			if (v instanceof String[] enumValue && enumValue.length == 2
			    && isReinitScopeDesc(enumValue[0])) {
				return "SELF".equals(enumValue[1]);
			}
			if (v instanceof String[] other) {
				log("Unexpected @RemoldReinit scope representation: "
				    + Arrays.toString(other) + "; falling back to INCLUDE_SUBCLASSES");
			}
			return false;
		}
		return false;
	}

	/** 注解里是否<b>显式</b>写了 {@code scope} 元素（ASM 只序列化显式给出的元素，默认值不出现）。 */
	private static boolean hasScopeElement(AnnotationNode a) {
		if (a.values == null) return false;
		for (int i = 0; i + 1 < a.values.size(); i += 2) {
			if ("scope".equals(a.values.get(i))) return true;
		}
		return false;
	}

	/**
	 * 轻量探测：新字节码里是否存在 {@code @RemoldReinit} 字段注解。
	 *
	 * <p>用途是 {@link #transform} 的快速路径：没有任何新增字段时它本来会直接返回，
	 * 但"改了已有字段初值 + 标注解"恰恰是没有任何新增字段的形态，必须放行到
	 * {@link #buildPatch}。这里跳过全部方法字节码，只读字段表与注解。</p>
	 */
	private static boolean hasReinitAnnotation(byte[] bytes) {
		final boolean[] found = {false};
		try {
			new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public FieldVisitor visitField(int access, String name, String desc,
				                               String signature, Object value) {
					return new FieldVisitor(Opcodes.ASM9) {
						@Override
						public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
							if (isReinitDesc(adesc)) found[0] = true;
							return null;
						}
					};
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (Throwable ignored) {
			// 读不了就当没有注解：与"宁可不补"一致
		}
		return found[0];
	}

	/** 分析级台账更新：被拒 → 记入（下轮重试）；放行/T0 零值等价 → 出账。 */
	private static void ledgerUpdateFromReport(Class<?> host, PatchReport report) {
		if (report == null) return;
		for (Map.Entry<String, FieldDecision> e : report.instanceFields().entrySet()) {
			ledgerApply(host, e.getKey(), e.getValue());
		}
		for (Map.Entry<String, FieldDecision> e : report.staticFields().entrySet()) {
			ledgerApply(host, e.getKey(), e.getValue());
		}
	}

	/**
	 * 拒绝告警的唯一出口：把 {@link PatchReport} 里全部 {@link FieldStatus#REJECTED}
	 * 决策按原因聚合后逐条 {@code warn}。
	 *
	 * <p>存在的理由：提取期的拒绝本来就发 warn（{@code extractFieldInits}），但闭包阶段
	 * 的拒绝（后续加工、依赖、成环、根构造器覆盖不全、指纹不一致）历史上只调
	 * {@code log(...)} —— 而 {@code log} 是 {@code DEBUG} 门控的，默认环境<b>什么都不打印</b>。
	 * 于是同一种"拒绝补丁"的结论，两个阶段对用户的可见性完全不同。</p>
	 *
	 * <p>把出口收敛到一处后，"哪些拒绝该吵醒用户"不再依赖各阶段各自的日志级别；
	 * 每条原因只发一条 warn，同一原因下多个字段合并列出，避免逐字段刷屏。</p>
	 */
	private static void warnRejectedFields(String className, PatchReport report) {
		if (report == null) return;

		// 原因 -> 字段名，保持首次出现顺序；instance 在前、static 在后。
		Map<String, List<String>> byReason = new LinkedHashMap<>();
		collectRejected(report.instanceFields(), "instance", byReason);
		collectRejected(report.staticFields(), "static", byReason);

		for (Map.Entry<String, List<String>> e : byReason.entrySet()) {
			RemoldAgent.warn("Field init patch refused for "
			                  + (e.getValue().size() == 1
			 ? e.getValue().get(0)
			 : e.getValue().size() + " fields of " + className)
			                  + " in " + className + ": " + e.getKey());
		}
	}

	private static void collectRejected(
	 Map<String, FieldDecision> decisions, String kind, Map<String, List<String>> out) {
		if (decisions == null) return;
		for (Map.Entry<String, FieldDecision> e : decisions.entrySet()) {
			FieldDecision d = e.getValue();
			if (d == null || d.status() != FieldStatus.REJECTED) continue;
			String reason = d.reason() == null ? "rejected" : d.reason();
			out.computeIfAbsent(reason, k -> new ArrayList<>())
			 .add(kind + " " + e.getKey());
		}
	}

	private static void ledgerApply(Class<?> host, String fieldName, FieldDecision decision) {
		if (decision == null) return;
		if (decision.status() == FieldStatus.REJECTED) {
			ledgerPut(host, fieldName, decision.reason());
		} else {
			// ACCEPTED：补丁已生成（真正写没写成功由 applyPatch 决定）
			// NOTHING_TO_PATCH：本来就不需要补 —— 两者都不该留在台账里
			ledgerRemove(host, fieldName);
		}
	}


	// ==================== 后续加工检查 ====================

	private static String subsequentProcessingReason(
	 String fieldName,
	 Set<String> acceptedFields,
	 Map<String, List<FieldExtract>> allExtracts,
	 List<MethodNode> methods,
	 String className,
	 boolean isStatic,
	 FieldNode fieldNode) {

		if (fieldNode == null) return null;

		Set<AbstractInsnNode> acceptedInsns = new HashSet<>();
		for (String g : acceptedFields) {
			List<FieldExtract> feList = allExtracts.get(g);
			if (feList == null) continue;
			for (FieldExtract fe : feList) {
				acceptedInsns.addAll(fe.originalInsns());
			}
		}

		boolean fieldIsPrimitive = InitFixSafetyGate.isPrimitiveDesc(fieldNode.desc);
		boolean fieldIsImmutable = InitFixSafetyGate.isImmutableType(fieldNode.desc);

		int putOp = isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD;
		int getOp = isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD;

		for (MethodNode m : methods) {
			for (AbstractInsnNode n : m.instructions) {
				if (!(n instanceof FieldInsnNode f)) continue;
				if (!f.owner.equals(className) || !f.name.equals(fieldName)) continue;

				if (f.getOpcode() == putOp) {
					if (!acceptedInsns.contains(n)) {
						return "field written outside its extraction in "
						       + className + "." + m.name + " at index "
						       + m.instructions.indexOf(n);
					}
				} else if (f.getOpcode() == getOp) {
					if (fieldIsPrimitive || fieldIsImmutable) continue;
					if (!acceptedInsns.contains(n)) {
						String where = className + "." + m.name + " at index "
						               + m.instructions.indexOf(n);
						if (InitFixSafetyGate.isThreadAffineType(fieldNode.desc)) {
							// 按线程的值类型单独说清楚：补丁在热更线程上单线程执行，
							// 构造器里对它的使用无法重放，重算只会得到另一条线程的副本。
							return "thread-affine field " + fieldNode.desc + " is used outside its "
							       + "extraction in " + where
							       + " (patch runs single-threaded on the hotswap thread; "
							       + "the constructor's use of it cannot be replayed)";
						}
						return "field read outside any accepted extraction in " + where;
					}
				}
			}
		}
		return null;
	}

	// ==================== docs/initfix/03-runtime-driver.md §4 注解豁免的告警 ====================

	/**
	 * {@code @RemoldReinit} 豁免了"构造器里用过该字段"这条门时，把风险显式喊出来。
	 *
	 * <p>豁免本身是用户显式声明的（{@code docs/initfix/03-runtime-driver.md} §4），但代价必须可见：</p>
	 * <ul>
	 *   <li>补丁只重建<b>字段初始化式</b>，构造器里对它的其它用法（例如
	 *       {@code sb.get().append("aass")}）<b>不会</b>重放 —— 存量实例与正常构造的
	 *       实例在这个点上必然分叉；</li>
	 *   <li>补丁在<b>热更线程上单线程</b>执行。对 {@code ThreadLocal} 这类按线程的值，
	 *       构造器当时是在应用线程上跑的，per-thread 副本不是同一份；纯分配
	 *       （{@code withInitial}）在哪个线程做都一样，但任何"预置内容"都会丢。</li>
	 * </ul>
	 * <p>需要按实例、按正确线程补齐时，用 {@link remold.annotation.OnReload}
	 * （跑在 {@code Core.app} 的应用线程上、逐实例调用）。</p>
	 * @param warned 去重集合，避免 while 循环里重复告警
	 * @return 报告里可见的告警文本
	 */
	private static String warnExemptedReinit(
	 String className, String fieldName, FieldNode fieldNode, String gateReason,
	 boolean isStatic, Set<String> warned) {

		String warning = exemptionWarning(fieldNode, gateReason);
		if (warned.add(fieldName)) {
			RemoldAgent.warn("[InitFix] @RemoldReinit waived safety gate for " + className + "."
			                  + fieldName + " (" + (isStatic ? "static" : "instance") + "): "
			                  + gateReason
			                  + "; patch only reconstructs field initializer and executes on hotswap thread, "
			                  + "constructor usage will not be replayed");
		}
		return warning;
	}

	/** 报告里那条告警的文本（按类型锐化）。 */
	private static String exemptionWarning(FieldNode fieldNode, String gateReason) {
		StringBuilder sb = new StringBuilder();
		sb.append("@RemoldReinit waived gate (").append(gateReason).append("): "
		                                                                  + "patch only reconstructs initializer, constructor usage will not be replayed");
		if (fieldNode != null && InitFixSafetyGate.isThreadAffineType(fieldNode.desc)) {
			sb.append("; field has thread-affine type (").append(fieldNode.desc)
			 .append("), patch executes single-threaded on hotswap thread, per-thread copy cannot be restored, "
			         + "use @OnReload if per-instance/thread replenishment is needed");
		}
		return sb.toString();
	}

	// ==================== 依赖闭包 ====================

	private static String depReason(
	 String self,
	 List<FieldExtract> feList,
	 String className,
	 Set<String> acceptedInstance,
	 Set<String> acceptedStatic,
	 Set<String> allNewInstance,
	 Set<String> allNewStatic) {

		if (feList == null) return null;

		for (FieldExtract fe : feList) {
			for (AbstractInsnNode n : fe.instructions()) {
				if (!(n instanceof FieldInsnNode fld)) continue;
				if (!fld.owner.equals(className)) continue;

				int op = fld.getOpcode();
				if (op == Opcodes.GETFIELD
				    && allNewInstance != null
				    && acceptedInstance != null
				    && allNewInstance.contains(fld.name)
				    && !fld.name.equals(self)
				    && !acceptedInstance.contains(fld.name)) {
					return "reads new instance field '" + fld.name
					       + "' which is not patched";
				}
				if (op == Opcodes.GETSTATIC
				    && allNewStatic != null
				    && acceptedStatic != null
				    && allNewStatic.contains(fld.name)
				    && !fld.name.equals(self)
				    && !acceptedStatic.contains(fld.name)) {
					return "reads new static field '" + fld.name
					       + "' which is not patched";
				}
			}
		}
		return null;
	}

	// ==================== 指令指纹 ====================

	private static String fingerprint(List<AbstractInsnNode> insns) {
		StringBuilder sb = new StringBuilder();
		for (AbstractInsnNode n : insns) {
			if (n.getOpcode() == -1) continue;
			sb.append(n.getOpcode()).append(':').append(describe(n)).append(';');
		}
		return sb.toString();
	}

	private static String describe(AbstractInsnNode n) {
		if (n instanceof FieldInsnNode f) { return f.owner + "." + f.name + ":" + f.desc; }
		if (n instanceof MethodInsnNode m) { return m.owner + "." + m.name + m.desc + (m.itf ? ":itf" : ""); }
		if (n instanceof VarInsnNode v) { return "v" + v.var; }
		if (n instanceof LdcInsnNode l) { return "ldc(" + describeLdcConstant(l.cst) + ")"; }
		if (n instanceof TypeInsnNode t) { return t.desc; }
		if (n instanceof IntInsnNode in) { return "int" + in.operand; }
		if (n instanceof MultiANewArrayInsnNode m) { return "manaa:" + m.desc + ":" + m.dims; }
		if (n instanceof InvokeDynamicInsnNode indy) {
			StringBuilder sb = new StringBuilder("indy:")
			 .append(indy.name).append(indy.desc)
			 .append(":bsm=").append(describeBsmArg(indy.bsm));
			if (indy.bsmArgs != null) {
				for (Object a : indy.bsmArgs) {
					sb.append("|a=").append(describeBsmArg(a));
				}
			}
			return sb.toString();
		}
		if (n instanceof IincInsnNode inc) { return "iinc" + inc.var + "+" + inc.incr; }
		return "";
	}

	private static String describeLdcConstant(Object cst) {
		if (cst instanceof Type t) return t.getDescriptor();
		if (cst instanceof Handle h) return describeBsmArg(h);
		if (cst instanceof ConstantDynamic cd) return describeConstantDynamic(cd);
		return String.valueOf(cst);
	}

	private static String describeConstantDynamic(ConstantDynamic cd) {
		StringBuilder sb = new StringBuilder("cdy:")
		 .append(cd.getName()).append(cd.getDescriptor())
		 .append(":bsm=").append(describeBsmArg(cd.getBootstrapMethod()));
		int n = cd.getBootstrapMethodArgumentCount();
		for (int i = 0; i < n; i++) {
			sb.append("|a=").append(describeBsmArg(cd.getBootstrapMethodArgument(i)));
		}
		return sb.toString();
	}

	private static String describeBsmArg(Object a) {
		if (a == null) return "null";
		if (a instanceof Handle h) {
			return "H[" + h.getTag() + ":"
			       + h.getOwner() + "." + h.getName() + h.getDesc()
			       + (h.isInterface() ? ":itf" : "") + "]";
		}
		if (a instanceof Type t) {
			return "T[" + t.getDescriptor() + "]";
		}
		if (a instanceof ConstantDynamic cd) {
			return describeConstantDynamic(cd);
		}
		if (a.getClass().isArray()) {
			int           len = Array.getLength(a);
			StringBuilder sb  = new StringBuilder("[");
			for (int i = 0; i < len; i++) {
				if (i > 0) sb.append(',');
				sb.append(describeBsmArg(Array.get(a, i)));
			}
			return sb.append(']').toString();
		}
		return String.valueOf(a);
	}

	private static String fingerprintMismatchReason(List<FieldExtract> extracts) {
		if (extracts.size() < 2) return null;
		String fp = fingerprint(extracts.get(0).instructions());
		for (int k = 1; k < extracts.size(); k++) {
			if (!fp.equals(fingerprint(extracts.get(k).instructions()))) {
				return "different initialization expressions across constructors";
			}
		}
		return null;
	}

	// 兼容 InitFixOracle 对 A+ 窄例外的反射直调守卫
	private static boolean allowedStringCoercion(MethodInsnNode m, InsnList insns,
	                                             Frame<SourceValue>[] frames,
	                                             Set<AbstractInsnNode> collected) {
		return InitFixSafetyGate.allowedStringCoercion(m, insns, frames, collected);
	}

	public static final int BATCH_HEAP_SCAN_MIN_TARGETS = 8;

	public static void afterRedefineExternal(Class<?> clazz) {
		InitFixRuntimeDriver.afterRedefineExternal(clazz);
	}

	public static void afterRedefine(Class<?> clazz) {
		InitFixRuntimeDriver.afterRedefine(clazz);
	}

	public static void afterRedefineFailed(Class<?> clazz) {
		InitFixRuntimeDriver.afterRedefineFailed(clazz);
	}

	/**
	 * 批次门整类拒绝时调用：丢掉该类的暂存补丁，但<b>不</b>把它记成"redefine 失败"。
	 * 见 {@link InitFixRuntimeDriver#discardPending(Class)}。
	 */
	public static void discardPending(Class<?> clazz) {
		InitFixRuntimeDriver.discardPending(clazz);
	}

	static String reinitHint(
	 String className, PatchPlan plan, Set<String> failedFields,
	 Set<String> skippedFields, int instanceTargets, int selfTargets) {
		return InitFixRuntimeDriver.reinitHint(className, plan, failedFields, skippedFields, instanceTargets, selfTargets);
	}

	public static boolean scopeMatches(Class<?> host, Object instance, RemoldReinit.Scope scope) {
		return InitFixRuntimeDriver.scopeMatches(host, instance, scope);
	}

	record BoundInstanceTask(String fieldName, Set<String> dependencies, MethodHandle handle,
	                         MethodHandle batchHandle, boolean selfOnly) { }

	record BoundFusedBatchGroup(MethodHandle handle, int[] taskIndices) { }

	static PatchPlan pendingPlanForTest(Class<?> clazz) {
		return InitFixRuntimeDriver.pendingPlanForTest(clazz);
	}

	static byte[] pendingBytesForTest(Class<?> clazz) {
		return InitFixRuntimeDriver.pendingBytesForTest(clazz);
	}

	static void runIndependentInstanceTasks(
	 Class<?> host, Object[] targets, BoundInstanceTask[] tasks,
	 Set<String> failedFields, Set<String> skippedFields, Map<String, String> failureReasons) {
		InitFixRuntimeDriver.runIndependentInstanceTasks(host, targets, tasks, failedFields, skippedFields, failureReasons);
	}

	static void runIndependentInstanceTasks(
	 Class<?> host, Object[] targets, BoundInstanceTask[] tasks, BoundFusedBatchGroup[] fusedGroups,
	 Set<String> failedFields, Set<String> skippedFields, Map<String, String> failureReasons) {
		InitFixRuntimeDriver.runIndependentInstanceTasks(host, targets, tasks, fusedGroups,
		 failedFields, skippedFields, failureReasons);
	}

	public static boolean batchHeapScanEnabled() {
		return InitFixRuntimeDriver.batchHeapScanEnabled();
	}

	public static void setBatchHeapScanEnabled(boolean enabled) {
		InitFixRuntimeDriver.setBatchHeapScanEnabled(enabled);
	}

	public static void beginBatchInstanceCollection(Collection<Class<?>> hosts) {
		InitFixRuntimeDriver.beginBatchInstanceCollection(hosts);
	}

	public static void endBatchInstanceCollection() {
		InitFixRuntimeDriver.endBatchInstanceCollection();
	}

	static Object[] compactNonNull(Object[] raw) {
		return InitFixRuntimeDriver.compactNonNull(raw);
	}

	public static boolean isInitialized(Class<?> clazz) {
		return InitFixRuntimeDriver.isInitialized(clazz);
	}

}
