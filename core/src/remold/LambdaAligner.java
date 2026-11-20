package remold;

import remold.vcs.AlignmentMetaStore;
import remold.vcs.DerivedDataCache;
import remold.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Lambda 表达式对齐工具类。
 *
 * <p>设计目标：优先保证“不崩溃”，尽可能保证“逻辑严丝合缝”。</p>
 *
 * <p>主要功能：</p>
 * <ol>
 *   <li>分析新旧字节码中的合成方法（synthetic methods）。</li>
 *   <li>通过哈希匹配和顺序对齐算法，建立方法名映射关系。</li>
 *   <li>重写新字节码中的方法名引用，包括 {@code $deserializeLambda$} 里
 *       {@code String.hashCode()} 分派的 {@code lookupswitch} 常量。</li>
 *   <li>解决热交换时因 Lambda 名称变化导致的 {@link NoSuchMethodError}。</li>
 *   <li><b>孤儿 Lambda 智能自适应分流（{@link OrphanPolicy#SMART_ADAPTIVE}）：</b><br>
 *       对于在新版本中被彻底删除的“孤儿方法”，生成带调用栈探测的空壳方法：
 *       <ul>
 *         <li>若直接调用者匹配注册的孤儿熔断器（{@link OrphanBreakerPredicate}，如 UI / 定时轮询 / 事件监听）：主动抛出 {@link NoSuchMethodError}，
 *             驱动调用方执行精准局部熔断（如注销回调），彻底根除高频刷屏空转；</li>
 *         <li>若由普通业务代码发起（未受熔断保护）：<b>非 {@code void}</b> 孤儿默认保留上一轮方法体
 *             （本类成员引用存活判据通过时），避免调用方拿到 {@code null}/0 后 NPE / 除零 / 数组越界；
 *             判据不通过则回退返回类型默认值。{@code void} 孤儿默认 no-op（删掉的副作用不再执行），
 *             可用 {@code -Dremold.agent.orphan_retain_void=true} 打开。契约见
 *             {@code docs/lambda/02-orphan-body-retention.md}。</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>调用约定（重要）：</b>{@link #align} 的 {@code oldBytes} 必须是
 * <i>上一轮对齐并注入幽灵方法之后、JVM 里实际生效的字节码</i>，而不是原始编译
 * 产物。若每次热更都拿新版原始 class 作基准，会出现“名字错位”（v2 里跑的是
 * 对齐后的名字，对齐基准却是 v2 编译出的名字），第三次热更会重新触发
 * {@link NoSuchMethodError}；同时上一轮注入的空壳不在原始产物里，也会被漏掉。</p>
 *
 * <p><b>运行环境前提（DCEVM / JBR）：</b>标准 JVM HotSwap（JVM TI 规范）严禁修改类的结构
 * （Schema Change，新增或删除方法与字段会无条件触发 {@link UnsupportedOperationException}）。
 * 本对齐器的全套机制（包括方法避障改名、幽灵桩方法补齐注入、新 Lambda 识别）<b>完全建立在
 * DCEVM 或 JetBrains Runtime (JBR) 等增强热重载虚拟机的支持之上</b>。
 * 在标准 JVM 环境下，任何涉及方法增删的类编辑重定义都会被底层 JVM 直接拒绝，
 * 无法通过字节码改写生效。</p>
 *
 * <p><b>失败降级：</b>整个 {@link #align} 过程被一层 try/catch 包住。若因输入
 * 不合法、ASM 内部异常等任何 {@code Exception} 导致对齐失败，会记录一条日志并
 * 返回原始 {@code newBytes}（降级为“不崩溃”）。这意味着热更成功但老 CallSite
 * 可能因名字未对齐而失效——这是与“不崩溃”的设计目标一致的取舍。</p>
 *
 * <p><b>已知限制：幽灵方法不参与复活认领（单向退休）。</b>
 * 幽灵方法（空壳）在扫描阶段被识别为 {@code ghost}，并被 Step 1 / Pass A / Pass B 等<b>常规匹配阶段</b>硬性排除，
 * 仅保留其名字在避障集以防止新方法重名冲突。
 * 但"删掉一个 Lambda 又原封不动加回来"是常见操作，因此额外有一条<b>只认指纹</b>的复活通道
 * （{@link #stepGhostRevive}）：注入桩体时把生前指纹写进 {@code LDC <long>; POP2} 标记，
 * 下一轮若某个新 lambda 的指纹与某幽灵的生前指纹完全一致且描述符相同，就认领回该槽位。
 * 方法体改过的 lambda 指纹不同，不会复活——宁可让它拿新槽位，也不做"多对多误配"。
 * 该通道由 {@code -Dremold.agent.ghost_revive=true} 开启，<b>默认关闭</b>：
 * 现有三条不变量（`LineNumberDifferentialTest` 的"幽灵单向退休"、`HostAnchorTest` 的
 * "V3 加回的新 L1 不认领旧桩名"、`NameIndexTest` 的"第二轮零改动"）都按"不复活"编码，
 * 默认开启会直接推翻它们。要改契约需同步改这三处。</p>
 *
 * <p><b>已知限制：转发桩仍是 ghost（不参与复活认领）。</b>
 * 当 Lambda 被改写为方法引用时（如 {@code () -> this.xxx()} 改为 {@code this::xxx}），
 * 对齐器会生成带 {@code willForward = true} 的透明转发桩，旧 CallSite 可继续无缝执行目标方法。
 * 但此转发桩在后续轮次中<b>仍然是 ghost</b>，不参与任何复活认领或新 Lambda 匹配（保持单向退休）。</p>
 *
 * <p><b>已知限制：方法引用透明转发只覆盖纯委托与标准适配。</b>
 * 仅当旧 Lambda 为纯委托或仅包含标准适配（由 LambdaMetafactory 自动完成的装箱、拆箱、基本类型加宽、丢弃返回值、CHECKCAST 转换），
 * 且新类中对应方法引用的 Handle 出现次数相较旧类产生净增（或上一轮已是转发桩且新类中仍存在该引用）时才触发转发。
 * 目标方法查找与 Handle 门禁不绑定特定调用指令或 Handle tag（兼容 JDK 8 时代的 {@code INVOKESPECIAL}/{@code REF_invokeSpecial} 与 JDK 11+ JEP 181 时代的 {@code INVOKEVIRTUAL}/{@code REF_invokeVirtual} 私有调用）。
 * 其他带有额外业务逻辑的转换或纯删除的回调仍然严格降级为空壳默认值（0 / null / false）。</p>
 *
 * <p><b>兜底配对是 fail-closed 的（信息不足就拒绝配对，不按位置猜）。</b>
 * Step 1 的指纹/锚点证据用尽之后，骨架趟、Pass A、Pass B 只剩 (签名, 深度, shape, 子归属)
 * 这些<b>与内容无关</b>的结构信息。删一个加一个、或者把叶子包进新容器时，位置信息会把
 * <b>别人的名字</b>交给一个语义不同的方法体：老 {@code CallSite} 不抛异常、静默跑错。
 * 因此这三趟在配对前都要过 {@link #fallbackPairingAllowed}：只有"方法体逐条等价
 * （{@link #bodyFingerprint}，常量池索引/局部槽位/可折叠算式已归一）"、"同名 + 同宿主形态"、
 * "消费端在宿主内有区分度"、"骨架全类唯一"、"子身份已定案"、"与已定案配对顺序一致"之一成立才放行；
 * 都不成立就 {@code BODY_MISMATCH} WARN + 拒绝配对：旧名走幽灵桩（老 {@code CallSite} 熔断、
 * 响亮失败），新方法去阶段二拿避障名。</p>
 *
 * <p><b>已知限制：改变嵌套拓扑的编辑会丢失身份。</b>
 * {@link #sameNestingLevel} 在 Step 1/2 全程是<b>硬否决</b>：形状不等的候选永远不配对。
 * 因此<b>任何改变嵌套拓扑的编辑</b>——在现有 lambda 体内新增或删除一层内层 lambda——
 * 都会让该 lambda 的 shape 变化，从而失去身份：旧名被幽灵化、新方法去阶段二拿新名。
 * 这是"安全优先于召回"的自觉取舍（形状混淆会让父子错配、语义静默对调，代价更大），
 * 但这类编辑在日常开发中很常见，因此明确记录在此。体内<b>新增</b>内层 lambda 时，
 * 旧叶子的槽位还可能被新内层按位置顶替（javac 后序编号让两者同名）——这条由
 * {@link #fallbackPairingAllowed} 拦下并要求"宿主形态翻转 ⇒ 必须有方法体证据"，
 * 实测见 {@code LambdaAlignTortureTest#l5AddingInnerLambdaRefusesToRebindOldSlot}。</p>
 *
 * <p><b>已知限制：过度归一化下的匿名类碰撞与位移错配。</b>
 * 当多个同签名的 Lambda 表达式各自实例化不同的匿名内部类（例如分别实现 {@code Runnable} 处理保存与删除），
 * 若其 Lambda 方法体字节码结构相同（仅引用的匿名类序号不同），由于指纹生成时将匿名类序号归一化为方法内相对 ID
 * （如 {@code #ANON_0#}），会导致这些不同的 Lambda 生成完全相同的语义哈希指纹。
 * 此时若在这些 Lambda 前方新增、删除或重排其它 Lambda，Step 1a 的“同哈希同名优先”策略会将移位后的新旧同名 Lambda
 * 错误地配对，从而引发业务语义的静默对调。
 * 对齐器在此类场景下会进行风险识别与告警，日常开发建议避免在同一类中编写仅通过匿名类实例区分且方法体同构的多个 Lambda。</p>
 *
 * <p><b>已知限制：父级方法体变更下的嵌套 Lambda 叶子哈希碰撞残余。</b>
 * 宿主调用序列锚点匹配（{@code alignByHostSequence}）依赖父级 Lambda 的票据哈希（{@code L:hash}）保持稳定。
 * 若在同一次代码保存中既修改了父级 Lambda 的方法体（导致新旧父级票据哈希不同，无法落入同一宿主锚点桶进行 LCS 对齐），
 * 同时存在前序语句删除引发的子叶子同名同哈希碰撞，叶子会退回到 Step 1a 的同名贪婪匹配，
 * 进而父级可能经 Pass A 逆向配对到已删除的旧父级并触发 {@code warnPositionalMismatch}。
 * 日常开发建议避免在单次保存中同时进行“外层逻辑重写”与“多层同构叶子增删”。</p>
 *
 * <p><b>可见性假设：</b>注入的幽灵桩通过 {@code INVOKESTATIC} 调用
 * {@link #onOrphanInvoked(String)}，因此目标类的类加载器必须能看到 {@code remold.LambdaAligner}。
 * 父委派正常时成立。</p>
 * @see OrphanBreakerPredicate
 * @see TransparentFramePredicate
 * @see remold.RemoldAgent
 */
public class LambdaAligner {

	/**
	 * 活体对齐的基线选择开关（Run R / Run E），**默认开启**。
	 *
	 * <p>开启：对齐期以 {@link AlignmentMetaStore} 的逐代元数据为准，配对基线用上一代待对齐字节
	 * （{@code R_prev}），幽灵桩 / 保留体 / 捕获桥仍从上一轮生效字节（{@code E_prev}）继承；
	 * 元数据缺失或整类闸门不过时**整类**回退旧路径（{@code Run E}），不会部分继承。
	 * 关闭：完全回到"拿上一轮生效字节当配对基线"的旧路径，行为与历史版本一致。</p>
	 *
	 * <p>属性优先级（越靠前越优先）：{@code -Dremold.agent.lambda_raw_alignment=false} 急停 →
	 * 兼容旧 Property {@code -Dremold.poc.meta_source}=true|false → 默认 {@code true}。
	 * 规格见 {@code docs/vcs/03-live-raw-to-raw-alignment.md}。</p>
	 */
	public static volatile boolean META_SOURCE = rawAlignmentDefault();

	/**
	 * 解析 {@link #META_SOURCE} 的默认值。
	 *
	 * <p>急停开关 {@code remold.agent.lambda_raw_alignment=false} 必须**先于**旧 Property 判定：
	 * 转正后若有人在旧文档里照抄 {@code -Dremold.poc.meta_source=true}，急停仍要生效——
	 * 否则"一条命令关掉"的承诺就不成立。</p>
	 */
	private static boolean rawAlignmentDefault() {
		String emergency = System.getProperty("remold.agent.lambda_raw_alignment");
		if (emergency != null) return !"false".equalsIgnoreCase(emergency.trim());
		String legacy = System.getProperty("remold.poc.meta_source");
		if (legacy != null) return Boolean.parseBoolean(legacy.trim());
		return true;
	}

	static {
		// 兼容口：不显式传 Raw→Eff 键映射的旧 9 参 AlignmentMetaStore.commit 调用方
		// （测试 / 离线回放：align 之后立刻 commit）从这里取"该类最近一次对齐发布的翻译表"。
		// 生产路径由 RemoldAgent 显式传值，不依赖该口——所以这里只是把已有的对齐结论转手，
		// 不引入"align 写元数据"的语义。
		AlignmentMetaStore.setTranslationLookup(name -> {
			AlignmentResult r = lastResultFor(name);
			return r == null ? Map.of() : r.rawToEffKeys;
		});
	}

	public static final ThreadLocal<MatchContext> CONTEXT = ThreadLocal.withInitial(MatchContext::new);

	/**
	 * <b>测试钩子</b>：反转分组遍历顺序，用于检测"结果依赖遍历顺序"的缺陷。
	 *
	 * <p>本对齐器的结果**不应**依赖 {@code LongObjectMap} 的遍历顺序（那个顺序对同一输入
	 * 是确定的，但会随输入变化而改变，因此不能作为正确性的依赖）。开启后所有分组遍历
	 * 反向进行；若结果与正序不同，就说明存在隐性的顺序依赖。</p>
	 *
	 * <p>生产环境保持 {@code false}。</p>
	 */
	public static volatile boolean TEST_REVERSE_GROUP_ORDER = false;

	/**
	 * 设计级变异测试开关（通过 -Dremold.mutation=... 或测试代码动态注入）：
	 * <ul>
	 *   <li>"A": 屏蔽 alignByHostSequence</li>
	 *   <li>"B": 去掉 canPair 中的消费者否决</li>
	 *   <li>"D": 允许幽灵参与后续轮次匹配</li>
	 *   <li>"1": hostKey 剔除消费者特征，仅保留哈希</li>
	 *   <li>"4": findConsumers 恒返回空映射</li>
	 *   <li>"C": 决胜键改回编译器方法表下标 methodIndex</li>
	 * </ul>
	 */
	public static volatile String ACTIVE_MUTATION = System.getProperty("remold.mutation", "");

	/**
	 * 本轮配对实际采信的旧侧基线（规格 §3.1 的跑道读数）。
	 *
	 * <p>只作诊断：产物字节不依赖这个读数，但"这一轮到底走的是哪条跑道、为什么退回"必须能被断言，
	 * 而不是只能靠日志猜。</p>
	 */
	public enum BaselineTree {
		/** Run R：旧侧 = 上一代待对齐产物（{@code R_prev}），继承集来自 {@code E_prev}。 */
		RAW_PREV,
		/** Run E：旧侧 = 上一代进 JVM 的产物（{@code E_prev}），即兼容跑道。 */
		EFF_FALLBACK,
		/** 本轮没有配对结论（旧字节缺失 / 对齐失败 / 提前返回）。 */
		NONE
	}

	/** Run R 的准入闸门不通过的原因（见规格 §3.2 的 G1/G2/G3）。 */
	public enum RunRGate {
		/** 闸门全过，走 Run R。 */
		PASSED,
		/** 灰度开关 {@link #META_SOURCE} 关闭。 */
		META_OFF,
		/** 元数据仓没有该类的当前代（G1：代际身份不成立）。 */
		NO_ENTRY,
		/** 该代没留下 {@code R_prev} 字节（blob 被预算丢弃 / 旧条目）。 */
		NO_RAW_PREV,
		/** {@code R_prev} 与元数据记录的 {@code preAlign} 指纹不符（G2：源码侧配对不可证）。 */
		RAW_PREV_MISMATCH,
		/** {@code R_prev → E_prev} 键映射不完整，无法确定活体在生效侧的名字（G2）。 */
		TRANSLATION_INCOMPLETE,
		/** {@code E_prev} 不可解析 / 继承集无法枚举（G3）。 */
		EFF_UNREADABLE
	}

	public static boolean isMutationActive(String mutationKey) {
		String m = ACTIVE_MUTATION;
		return m != null && m.equalsIgnoreCase(mutationKey);
	}

	/** 测试用显式事件记录条目（用于按方法名精确归因，避免同轮搭便车误判）。 */
	public static final class AlignmentEvent {
		public final String kind;       // "POS_MISMATCH", "CONSUMER_MISMATCH", "ANON_COLLISION", "HOMOMORPHIC_PRUNING", "GHOST", "BODY_MISMATCH"
		public final String oldName;
		public final String newName;
		public final String detail;

		public AlignmentEvent(String kind, String oldName, String newName, String detail) {
			this.kind = kind;
			this.oldName = oldName;
			this.newName = newName;
			this.detail = detail;
		}

		public boolean involves(String name) {
			if (name == null) return false;
			return name.equals(oldName) || name.equals(newName);
		}

		@Override
		public String toString() {
			return "AlignmentEvent{" + kind + ", old=" + oldName + ", new=" + newName + ", " + detail + "}";
		}
	}

	public static final List<AlignmentEvent> TEST_EVENTS = new CopyOnWriteArrayList<>();

	/**
	 * 最近一次 align 的配对来源统计（由证据驱动的指纹配对 vs A/B 趟降级配对）。
	 *
	 * <p><b>警告：全进程共享静态变量，仅供单线程测试断言使用，生产业务逻辑严禁引用。</b></p>
	 */
	public static final class AlignmentStats {
		/** 通过同组 Step 1a、跨组指纹或宿主序列锚点证据完成配对的数量。 */
		public final int step1Pairs;
		/** 通过 Step 2 A 趟（同上行深度 + 同 shape）完成配对的数量。 */
		public final int passAPairs;
		/** 通过 Step 2 B 趟（放宽条件兜底）完成配对的数量。 */
		public final int passBPairs;
		/** 通过宿主序列 LCS 锚点匹配完成配对的数量。 */
		public final int anchorPairs;
		/** 配对双方票据指纹不相等（hash 发生变更，换主人）的配对数量。 */
		public final int hashMismatchPairs;
		/** 配对双方存在显式且不一致的消费者特征（消费者异化）的总配对数量。 */
		public final int consumerMismatchPairs;
		/** 触发 WARN 级别（换名或桶内存在同 hash 歧义）的消费者错配数量。 */
		public final int consumerMismatchWarnPairs;
		/** 降级为 DEBUG 级别（同名且桶内唯一）的消费者变更数量。 */
		public final int consumerMismatchDebugPairs;
		/** 触发同构缩减告警的数量。 */
		public final int prunedPairs;
		/** 因"方法体不等价且无独立身份证据"被拒绝配对的次数（{@link #bodyMismatchRefusals} 的对外读数）。 */
		public final int bodyMismatchRefusals;

		public AlignmentStats(int step1Pairs, int passAPairs, int passBPairs) {
			this(step1Pairs, passAPairs, passBPairs, 0, 0, 0, 0, 0, 0);
		}

		public AlignmentStats(int step1Pairs, int passAPairs, int passBPairs, int anchorPairs, int hashMismatchPairs) {
			this(step1Pairs, passAPairs, passBPairs, anchorPairs, hashMismatchPairs, 0, 0, 0, 0);
		}

		public AlignmentStats(int step1Pairs, int passAPairs, int passBPairs, int anchorPairs, int hashMismatchPairs, int consumerMismatchPairs) {
			this(step1Pairs, passAPairs, passBPairs, anchorPairs, hashMismatchPairs, consumerMismatchPairs, consumerMismatchPairs, 0, 0);
		}

		public AlignmentStats(int step1Pairs, int passAPairs, int passBPairs, int anchorPairs, int hashMismatchPairs,
		                      int consumerMismatchPairs, int consumerMismatchWarnPairs, int consumerMismatchDebugPairs) {
			this(step1Pairs, passAPairs, passBPairs, anchorPairs, hashMismatchPairs, consumerMismatchPairs, consumerMismatchWarnPairs, consumerMismatchDebugPairs, 0);
		}

		public AlignmentStats(int step1Pairs, int passAPairs, int passBPairs, int anchorPairs, int hashMismatchPairs,
		                      int consumerMismatchPairs, int consumerMismatchWarnPairs, int consumerMismatchDebugPairs, int prunedPairs) {
			this(step1Pairs, passAPairs, passBPairs, anchorPairs, hashMismatchPairs,
			     consumerMismatchPairs, consumerMismatchWarnPairs, consumerMismatchDebugPairs, prunedPairs, 0);
		}

		public AlignmentStats(int step1Pairs, int passAPairs, int passBPairs, int anchorPairs, int hashMismatchPairs,
		                      int consumerMismatchPairs, int consumerMismatchWarnPairs, int consumerMismatchDebugPairs,
		                      int prunedPairs, int bodyMismatchRefusals) {
			this.bodyMismatchRefusals = bodyMismatchRefusals;
			this.step1Pairs = step1Pairs;
			this.passAPairs = passAPairs;
			this.passBPairs = passBPairs;
			this.anchorPairs = anchorPairs;
			this.hashMismatchPairs = hashMismatchPairs;
			this.consumerMismatchPairs = consumerMismatchPairs;
			this.consumerMismatchWarnPairs = consumerMismatchWarnPairs;
			this.consumerMismatchDebugPairs = consumerMismatchDebugPairs;
			this.prunedPairs = prunedPairs;
		}

		@Override
		public String toString() {
			return "Stats{step1=" + step1Pairs + ", passA=" + passAPairs + ", passB=" + passBPairs
			       + (anchorPairs > 0 ? ", anchor=" + anchorPairs : "")
			       + (hashMismatchPairs > 0 ? ", hashMismatch=" + hashMismatchPairs : "")
			       + (consumerMismatchPairs > 0 ? ", consumerMismatch=" + consumerMismatchPairs
			          + " (warn=" + consumerMismatchWarnPairs + ", debug=" + consumerMismatchDebugPairs + ")" : "")
			       + (prunedPairs > 0 ? ", pruned=" + prunedPairs : "")
			       + (bodyMismatchRefusals > 0 ? ", bodyMismatchRefused=" + bodyMismatchRefusals : "") + "}";
		}
	}

	/**
	 * 一次 {@link #align} 的结果快照。
	 *
	 * <p><b>为什么收成一个对象</b>：早先这些信息散在十个 {@code volatile} 静态字段里，读者
	 * 逐个读，读到的可能是<b>不同轮次</b>的拼接——{@code CellPropertyRef} 就同时读
	 * {@link #methodMapping} 与 {@link #alignedClass} 并比较两者是否自洽，两个独立字段之间
	 * 没有任何顺序保证，热更期间一旦插进另一轮对齐，比较的就是跨轮的结论。
	 * 收成不可变对象后只有<b>一次</b> volatile 读，快照天然一致。</p>
	 *
	 * <p>空值语义分两种，不要混：{@link #inventedNames} 永远非 {@code null}（空集即"没有自造名"，
	 * 调用方会直接把它喂回下一轮）；而 {@link #stats}、{@link #oldAnonHashes}、
	 * {@link #newAnonHashes}、{@link #methodMapping}、{@link #alignedClass} 在
	 * <b>本轮没有产出结论</b>时保持 {@code null}——"没算"和"算出来是空"是两件事，
	 * 后者用空集合表达，前者必须能被区分。</p>
	 */
	public static final class AlignmentResult {
		/** 配对统计；{@code null} 表示本轮对齐失败（或压根没跑）。 */
		public final AlignmentStats stats;
		/** 产物里仍然存在的"由对齐器自造的名字"。非 null。 */
		public final Set<String> inventedNames;
		/** 输入快照：旧/新字节的长度与内容哈希。 */
		public final int  oldLen, newLen;
		public final long oldHash, newHash;
		/** 旧/新两侧的匿名类内容哈希；未解析时为 null。 */
		public final Map<String, Long> oldAnonHashes, newAnonHashes;
		/** {@code oldName:oldDesc -> newName:newDesc}；无映射时为 null。 */
		public final Map<String, String> methodMapping;
		/** 本轮对齐的类名（内部名）；未对齐时为 null。 */
		public final String alignedClass;
		/** 本轮生成的捕获桥数量。 */
		public final int bridgeCount;
		/** 包含捕获但缺失 LocalVariableTable 的合成 lambda 方法集合；无缺失时为空集合。 */
		public final Set<String> missingLvtLambdas;

		/**
		 * PoC：本轮读取的上一代元数据代序号。
		 *
		 * <p>{@code -1} 表示未走 meta 读取（开关关闭或元数据缺失、退回字节码标记）。
		 * 它把"是否真的读了 meta"从日志/计数器这类相关性信号变成结果对象上的可断言读数。</p>
		 */
		public final int metaGeneration;

		/**
		 * PoC：本轮对齐实际采信的"上一轮自造名"集合。
		 *
		 * <p>它是调用方旧契约（参数 {@code inventedOldNames}）与 meta 新契约的共同落点：
		 * 两路等价要求这一集合逐轮相同，因此它把"meta 到底供了什么"变成可断言读数，
		 * 而不是只看产物字节是否恰好相同。</p>
		 */
		public final Set<String> suppliedInventedNames;

		/** 本轮配对采信的旧侧基线（{@link BaselineTree}）；没有结论时为 {@link BaselineTree#NONE}。 */
		public final BaselineTree baselineTree;

		/** Run R 准入闸门的判定结果；{@link RunRGate#PASSED} 表示本轮确实走了 Raw ↔ Raw。 */
		public final RunRGate runRGate;

		/**
		 * 本轮"证据不足、按位置/顺序兜底配对"的次数。
		 *
		 * <p>对应产物里 {@code POS_MISMATCH} 事件的条数（同样受 log-once 去重约束）。它是
		 * "配对是否由证据驱动"的可断言读数：Raw ↔ Raw 有槽位身份时应当为 {@code 0}。</p>
		 */
		public final int positionalFallbacks;

		/**
		 * 本轮 {@code Raw} 方法键 → 生效侧方法键（{@code name:desc}）的翻译表。
		 *
		 * <p>下一轮 Run R 靠它把"Raw 槽位身份"落到"老 CallSite 认的名字"上；空表表示本轮
		 * 没有可用结论（早退/失败）。</p>
		 */
		public final Map<String, String> rawToEffKeys;

		private AlignmentResult(Builder b) {
			this.stats = b.stats;
			this.inventedNames = b.inventedNames == null ? Set.of() : Set.copyOf(b.inventedNames);
			this.oldLen = b.oldLen;
			this.newLen = b.newLen;
			this.oldHash = b.oldHash;
			this.newHash = b.newHash;
			this.oldAnonHashes = b.oldAnonHashes;
			this.newAnonHashes = b.newAnonHashes;
			this.methodMapping = b.methodMapping;
			this.alignedClass = b.alignedClass;
			this.bridgeCount = b.bridgeCount;
			this.missingLvtLambdas = b.missingLvtLambdas == null ? Set.of() : Set.copyOf(b.missingLvtLambdas);
			this.metaGeneration = b.metaGeneration;
			this.suppliedInventedNames = b.suppliedInventedNames == null
				? Set.of() : Set.copyOf(b.suppliedInventedNames);
			this.baselineTree = b.baselineTree == null ? BaselineTree.NONE : b.baselineTree;
			this.runRGate = b.runRGate == null ? RunRGate.META_OFF : b.runRGate;
			this.positionalFallbacks = b.positionalFallbacks;
			this.rawToEffKeys = b.rawToEffKeys == null ? Map.of() : Map.copyOf(b.rawToEffKeys);
		}

		public static Builder builder() {
			return new Builder();
		}

		/**
		 * 只带输入快照的结果：{@code align} 提前返回（旧字节为空）或失败时用。
		 *
		 * <p>{@link #stats} 为 {@code null}，调用方据此区分"没跑完"与"跑了但没配到"。
		 * 两种情况在这里不细分——从调用方视角它们都意味着"本轮没有可用的对齐结论"。</p>
		 */
		static AlignmentResult empty(int oldLen, int newLen, long oldHash, long newHash) {
			return builder().input(oldLen, newLen, oldHash, newHash).build();
		}

		public static final class Builder {
			private AlignmentStats stats;
			private Set<String> inventedNames;
			private int  oldLen = -1, newLen = -1;
			private long oldHash, newHash;
			private Map<String, Long> oldAnonHashes, newAnonHashes;
			private Map<String, String> methodMapping;
			private String alignedClass;
			private int bridgeCount;
			private int metaGeneration = -1;
			private Set<String> suppliedInventedNames;
			private Set<String> missingLvtLambdas;
			private BaselineTree baselineTree;
			private RunRGate runRGate;
			private int positionalFallbacks;
			private Map<String, String> rawToEffKeys;

			public Builder stats(AlignmentStats v)            { this.stats = v; return this; }
			public Builder inventedNames(Set<String> v)       { this.inventedNames = v; return this; }
			public Builder input(int ol, int nl, long oh, long nh) {
				this.oldLen = ol; this.newLen = nl; this.oldHash = oh; this.newHash = nh; return this;
			}
			public Builder anonHashes(Map<String, Long> old, Map<String, Long> neu) {
				this.oldAnonHashes = old; this.newAnonHashes = neu; return this;
			}
			public Builder methodMapping(Map<String, String> v) { this.methodMapping = v; return this; }
			public Builder alignedClass(String v)               { this.alignedClass = v; return this; }
			public Builder bridgeCount(int v)                   { this.bridgeCount = v; return this; }
			public Builder missingLvtLambdas(Set<String> v)     { this.missingLvtLambdas = v; return this; }
			public Builder metaGeneration(int v)               { this.metaGeneration = v; return this; }
			public Builder suppliedInventedNames(Set<String> v) { this.suppliedInventedNames = v; return this; }
			public Builder baselineTree(BaselineTree v)         { this.baselineTree = v; return this; }
			public Builder runRGate(RunRGate v)                 { this.runRGate = v; return this; }
			public Builder positionalFallbacks(int v)           { this.positionalFallbacks = v; return this; }
			public Builder rawToEffKeys(Map<String, String> v)  { this.rawToEffKeys = v; return this; }

			public AlignmentResult build() { return new AlignmentResult(this); }
		}

		@Override
		public String toString() {
			return "AlignmentResult{" + alignedClass
			       + ", old=" + oldLen + "B/" + Long.toHexString(oldHash)
			       + ", new=" + newLen + "B/" + Long.toHexString(newHash)
			       + ", bridges=" + bridgeCount
			       + ", missingLvt=" + missingLvtLambdas.size()
			       + ", stats=" + stats + "}";
		}
	}

	/**
	 * 当开启 {@code LAMBDA_STRICT} 时，若检测到含捕获 lambda 的类缺少 {@code LocalVariableTable}，
	 * 抛出此异常，由 Agent 层捕获并拦截该类的重定义（回滚当前事务并跳过），防止未定义行为。
	 */
	public static class LambdaAlignmentStrictException extends IllegalStateException {
		public final String className;
		public final String reason;

		public LambdaAlignmentStrictException(String className, String reason) {
			super("[LAMBDA-STRICT-REJECT] " + className + ": " + reason);
			this.className = className;
			this.reason = reason;
		}
	}

	/**
	 * 最近一次 {@link #align} 的结果。<b>每次对齐只写一次</b>（单次 volatile 发布），
	 * 读者拿到的永远是同一轮的完整快照。
	 *
	 * <p>调用方（agent）必须把 {@link AlignmentResult#inventedNames} 存起来，并在下一轮作为
	 * {@code inventedOldNames} 传回去。不传的后果见 {@link #align(byte[], byte[],
	 * java.util.function.Function, java.util.function.Function, java.util.Set)} 的说明：
	 * 自造名会被误当成 javac 原名，让"同名优先"把老 CallSite 绑到别的 lambda 上。</p>
	 *
	 * <p>{@code null} 表示"本轮还没跑过"（进程刚起，或测试显式清空以检测是否记录）。</p>
	 *
	 * <p><b>只有"align 返回后同线程立即读"才允许用它</b>。批次结束之后（例如
	 * {@code CellPropertyRef.afterRedefineBatch}）必须改用 {@link #lastResultFor(String)}：
	 * 单槽在那一刻只剩最后一个被对齐的类，批内前序类会静默取不到自己的映射。</p>
	 */
	public static volatile AlignmentResult LAST_RESULT;

	/**
	 * 按类保留的最近一次对齐快照。
	 *
	 * <p><b>为什么不能只看 {@link #LAST_RESULT}</b>：{@code align} 是逐类调用的，而
	 * {@code CellPropertyRef.afterRedefineBatch} 在整批类都重定义完以后才跑一次。单槽在那一刻
	 * 只剩"最后一个被对齐的类"，批内前序类拿它比较类名必然失败，于是静默退回相似度猜测。
	 * 按类寻址让每个类都能取回自己那一轮的结论；{@link #LAST_RESULT} 继续服务"align 返回后
	 * 同线程立即读取"的既有调用方。</p>
	 *
	 * <p>有界：最多保留 {@value #MAX_RETAINED_RESULTS} 个类，按访问序淘汰最旧项。对齐失败或
	 * 早退时用空结果覆盖该类的旧快照，避免读者拿到上一轮的映射。</p>
	 */
	private static final int MAX_RETAINED_RESULTS = 128;
	private static final Map<String, AlignmentResult> LAST_RESULTS =
	 new LinkedHashMap<>(16, 0.75f, true) {
		 @Override
		 protected boolean removeEldestEntry(Map.Entry<String, AlignmentResult> eldest) {
			 return size() > MAX_RETAINED_RESULTS;
		 }
	 };

	/**
	 * 取某个类最近一次对齐的结论（内部名，如 {@code com/example/Foo}）。
	 *
	 * <p>{@code null} 表示本进程还没有对齐过这个类；返回对象但 {@link AlignmentResult#methodMapping}
	 * 为 {@code null} 表示最近一轮没有产出映射（失败或早退）。调用方不得把 {@code null} 当作
	 * "没有 lambda"，只能当作"没有可用结论"。</p>
	 */
	public static AlignmentResult lastResultFor(String slashName) {
		if (slashName == null) return null;
		synchronized (LAST_RESULTS) {
			return LAST_RESULTS.get(slashName);
		}
	}

	/**
	 * 单次发布：写全局单槽 + 按类记账。
	 *
	 * @param slashName 内部名；{@code null} 表示字节不可解析（只写全局单槽，不做按类记账）
	 */
	private static void publishResult(String slashName, AlignmentResult result) {
		LAST_RESULT = result;
		if (slashName == null) return;
		synchronized (LAST_RESULTS) {
			LAST_RESULTS.put(slashName, result);
		}
	}

	/** 解析内部名；字节不可解析时返回 {@code null}（记账失败不能盖过对齐本身的失败路径）。 */
	private static String classNameOf(byte[] bytes) {
		if (bytes == null || bytes.length < 10) return null;
		try {
			return new ClassReader(bytes).getClassName();
		} catch (Throwable ignored) {
			return null;
		}
	}

	/**
	 * 读方法表（带方法体、剥离调试信息）：Run R 的继承集枚举只需要结构，但保留旧体搬运与桥体
	 * 解析要读指令，所以这里与 {@code scan} 同口径读入。
	 */
	private static ClassNode readMethodTable(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return cn;
	}

	/** 一份字节里全部方法键（{@code name + desc}），用作命名池与冲突判定。 */
	private static Set<String> effPrevKeys(ClassNode cn) {
		if (cn == null) return Set.of();
		Set<String> out = new LinkedHashSet<>();
		for (MethodNode mn : cn.methods) out.add(mn.name + mn.desc);
		return out;
	}

	/**
	 * Run R 的 G2-b：翻译表必须覆盖 {@code R_prev} 里<b>全部</b> lambda 槽位。
	 *
	 * <p>只覆盖一部分是不可接受的中间态：没被覆盖的活体只能退回 Raw 名，而这个名字在本代
	 * 可能已经属于别的 lambda（或干脆不存在），老 {@code CallSite} 会静默解析到错误的方法体上。
	 * 宁可整类退回兼容跑道，也不做部分继承。</p>
	 */
	private static boolean translationCovers(byte[] rawPrevBytes, Map<String, String> rawToEff) {
		if (rawPrevBytes == null || rawToEff == null || rawToEff.isEmpty()) return false;
		ClassNode cn = readMethodTable(rawPrevBytes);
		for (MethodNode mn : cn.methods) {
			if (mn.name.startsWith("<") || mn.name.startsWith("access$")) continue;
			if (!MethodFingerprinter.isSyntheticName(mn.name)) continue;
			String key = mn.name + ':' + mn.desc;
			if (!rawToEff.containsKey(key)) {
				dbg(() -> "RUN-R gate: translation table lacks a slot for " + key
				          + " (the effective-side name of this lambda is unknown this round)");
				return false;
			}
		}
		return true;
	}

	/** 每类只打一次 Run R 闸门退回说明（按轮次刷屏会把真信号淹掉）。 */
	private static final Set<String> LOGGED_RUNR_GATE = ConcurrentHashMap.newKeySet();

	/**
	 * Run R 闸门未过时的可观测降级说明。
	 *
	 * <p>规格 §6 要求"回退必须可观测"：只开灰度没开元数据记录、blob 被预算丢弃、代际不一致
	 * 这些情况都表现为"开关开了却没效果"，必须有一条能直接对上的 warn。</p>
	 */
	private static void warnRunRFallbackOnce(String metaClass, RunRGate gate) {
		// 灰度开关关闭是常态（默认值），不需要告警；其余都是"用户以为会生效却没生效"的情形。
		if (gate == RunRGate.META_OFF || gate == RunRGate.NO_ENTRY) return;
		String key = metaClass + "|" + gate;
		if (!LOGGED_RUNR_GATE.add(key)) return;
		RemoldAgent.warn("[LambdaAligner] Raw<->Raw alignment unavailable for "
			+ (metaClass == null ? "<unknown>" : metaClass.replace('/', '.')) + ": " + gate
			+ ". This round falls back to the effective-bytes baseline (existing behaviour)."
			+ (gate == RunRGate.NO_RAW_PREV
			   ? " The previous pre-alignment bytes were not retained (see -Dremold.vcs.meta_blob_bytes)."
			   : ""));
	}

	/**
	 * 诊断开关：打开后打印配对决策的细节（谁在哪个阶段拿了哪个旧名字）。
	 *
	 * <p><b>为什么做成常设开关</b>：排查本对齐器的顺序问题时，临时插日志打印是必需的；但临时探针有两个反复出现的代价 ——
	 * 忘了移除（污染生产日志），以及 <b>改了没生效却察觉不到</b>
	 * （曾因 hstest 加载陈旧 jar，数轮结论都建立在旧字节码上）。
	 * 做成开关后，排查只需设属性/环境变量，<b>不必改代码</b>。</p>
	 *
	 * <p>开启方式（任一）：</p>
	 * <pre>
	 *   -Dremold.lambdaAligner.debug=true          系统属性
	 *   REMOLD_LAMBDA_ALIGNER_DEBUG=1              环境变量
	 *   LambdaAligner.DEBUG = true               代码/测试直接赋值
	 * </pre>
	 *
	 * <p>生产环境默认关闭。</p>
	 */
	public static volatile boolean DEBUG = initDebug();

	private static boolean initDebug() {
		String p = System.getProperty("remold.lambdaAligner.debug");
		if (p != null) return Boolean.parseBoolean(p) || "1".equals(p);
		String e = System.getenv("REMOLD_LAMBDA_ALIGNER_DEBUG");
		return Boolean.parseBoolean(e) || "1".equals(e);
	}

	/** 诊断输出：仅在 {@link #DEBUG} 打开时打印，统一前缀便于 grep。 */
	static void dbg(Supplier<String> msg) {
		if (DEBUG) RemoldAgent.info("[LambdaAligner] " + msg.get());
	}

	/** 按当前测试钩子决定的方向，在 {@code groups} 上迭代（返回下标序列）。 */
	static int[] groupOrder(LongObjectMap<?> groups) {
		int n = 0;
		for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) n++;
		int[] order = new int[n];
		int   i     = 0;
		for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) order[i++] = idx;
		if (TEST_REVERSE_GROUP_ORDER) {
			for (int l = 0, r = n - 1; l < r; l++, r--) {
				int t = order[l];
				order[l] = order[r];
				order[r] = t;
			}
		}
		return order;
	}

	/**
	 * 孤儿 lambda 被复活为空壳时的处理策略。
	 */
	public enum OrphanPolicy {
		/**
		 * 智能自适应策略（<b>推荐且默认</b>）：
		 * <ul>
		 *   <li>若由注册的孤儿熔断器（{@link OrphanBreakerPredicate}）发起（UI / 定时轮询 / 事件回调）：抛出 {@link NoSuchMethodError}，
		 *       触发精准局部熔断，彻底切断高频空转与死循环刷屏；</li>
		 *   <li>若由普通业务代码发起（未受熔断保护）：经 {@link RemoldAgent#warn} 打印首次执行告警；
		 *       非 {@code void} 孤儿在成员引用存活判据通过时<b>执行上一轮方法体</b>（三参
		 *       {@link #onOrphanInvoked(String, boolean, boolean)} 标记），否则返回默认值
		 *       （0 / null / false）；{@code void} 孤儿默认 no-op。见
		 *       {@code docs/lambda/02-orphan-body-retention.md}。</li>
		 * </ul>
		 */
		SMART_ADAPTIVE,
		/** 经 {@link RemoldAgent#warn} 打印一条日志，并返回默认值（0 / null / false）。 */
		LOG_AND_RETURN_DEFAULT,
		/** 无条件抛出 {@link NoSuchMethodError}。 */
		THROW_NO_SUCH_METHOD,
		/** 抛出 {@link IllegalStateException}，便于显式暴露问题。 */
		THROW,
		/** 静默返回默认值。 */
		SILENT
	}

	public static void setOrphanPolicy(OrphanPolicy policy) {
		LambdaOrphanEmitter.setOrphanPolicy(policy);
	}

	public static OrphanPolicy getOrphanPolicy() {
		return LambdaOrphanEmitter.getOrphanPolicy();
	}

	//region 孤儿 Lambda 方法体保留（docs/lambda/02-orphan-body-retention.md）

	/** 测试/嵌入方覆盖开关；不改系统属性，回到默认值请再调用一次传 {@code true/false}。 */
	public static void setOrphanBodyRetention(boolean nonVoidEnabled, boolean voidEnabled) {
		LambdaOrphanEmitter.setOrphanBodyRetention(nonVoidEnabled, voidEnabled);
	}

	public static boolean isOrphanBodyRetainedForNonVoid() {
		return LambdaOrphanEmitter.isOrphanBodyRetainedForNonVoid();
	}

	public static boolean isOrphanBodyRetainedForVoid() {
		return LambdaOrphanEmitter.isOrphanBodyRetainedForVoid();
	}
	//endregion

	/**
	 * 清空已记录的孤儿 Lambda 日志去重缓存。
	 */
	public static void clearLoggedOrphans() {
		LambdaOrphanEmitter.clearLoggedOrphans();
	}

	public static int getLoggedOrphanCount() {
		return LambdaOrphanEmitter.getLoggedOrphanCount();
	}

	public static int getLoggedForwardCount() {
		return LambdaOrphanEmitter.getLoggedForwardCount();
	}

	public static boolean isOrphanLogged(String location) {
		return LambdaOrphanEmitter.isOrphanLogged(location);
	}

	public static boolean isForwardLogged(String location) {
		return LambdaOrphanEmitter.isForwardLogged(location);
	}

	//region 匹配上下文

	/**
	 * 匹配上下文，存储当前线程的匹配状态。
	 * <p>由 {@link #CONTEXT} 通过 ThreadLocal 持有，在 {@link #align} 的 finally 中清理。</p>
	 */
	public static class MatchContext {
		/**
		 * {@code name + desc -> 新方法名}。
		 * <p>以 desc 为键的一部分，避免同名不同描述符（重载）被一起改名。</p>
		 */
		final Map<String, String> renameMap = new HashMap<>(64);

		/**
		 * {@code name -> 新方法名}，供 {@code mapValue} 改写字符串常量时使用。
		 * <p>仅当该简单名在整个类中见证了唯一且不同于自身的目标时才写入；
		 * 一旦出现歧义，会被移出并记入 {@link #ambiguousNames}。</p>
		 */
		final Map<String, String> renameBySimpleName = new HashMap<>(64);

		/**
		 * 见证表：{@code simpleName -> 唯一见证到的目标名}。
		 * <p>与 {@link #renameBySimpleName} 的区别：这里始终登记，即使
		 * “目标名 == 原名”（保名）。这样“一改一同”的歧义场景才能被识别。</p>
		 */
		final Map<String, String> simpleNameWitness = new HashMap<>(64);

		/**
		 * 出现歧义的简单名集合。
		 * <p>这些名字在 {@code mapValue} 中不再改写字符串常量，宁可保持原样。</p>
		 */
		final Set<String> ambiguousNames = new HashSet<>(16);

		/** 已被占用的旧方法名集合，用于生成 fresh name 时避免碰撞。 */
		final Set<String> usedOldNames = new HashSet<>(64);

		/**
		 * 已被占用配对的 Raw 侧槽位名。
		 *
		 * <p>与 {@link #usedOldNames}（产物名字空间）分开：Run R 下两者是不同命名空间，
		 * Run E 下二者恒等。</p>
		 */
		final Set<String> usedRawSlots = new HashSet<>(64);

		/**
		 * 上一轮由对齐器自造的 lambda 方法名（仍在旧类里）。
		 *
		 * <p>这些名字<b>不是</b> javac 会产出的编号，因此不能作为"同名 = 同一个 lambda"
		 * 的证据。见 {@link #align(byte[], byte[], java.util.function.Function,
		 * java.util.function.Function, java.util.Set)}。</p>
		 */
		java.util.Set<String> inventedOldNames = java.util.Set.of();

		/** 本轮新分配的自造名，用于计算 {@link AlignmentResult#inventedNames}。 */
		final Set<String> newlyInvented = new HashSet<>(8);

		/**
		 * 新类里<b>所有</b>方法的名字（含构造函数、静态块、桥接、普通业务方法、
		 * 合成方法），作为 fresh name 的避障集。由 {@code scan} 无条件填充。</p>
		 */
		final Set<String> existingNewNames = new HashSet<>(64);

		/**
		 * 旧类里<b>所有</b>方法的 {@code name + desc} 集合。既用于冲突检测，
		 * 也防止 fresh name 撞到上一轮注入的幽灵方法。由 {@code scan} 无条件填充。</p>
		 */
		final Set<String> oldNameDescSet = new HashSet<>(64);

		/**
		 * 老键（{@code name + desc}）→ 本轮要重新生成的捕获桥。
		 *
		 * <p>桥是"老名字+老描述符 → 新方法"的适配器。它<b>不参与改名</b>：新方法保留自己的
		 * 名字，桥占用老键，两者共存（老键的 desc 与新方法的 desc 必然不同，不会撞车）。
		 * 见 {@link #stepCaptureBridge}。</p>
		 */
		final Map<String, LambdaCaptureBridge.BridgeSpec> bridges = new HashMap<>(16);

		/**
		 * 两侧合成方法的形参名（{@code name+desc -> 形参名数组}），由
		 * {@link #readLambdaParamNames} 在 {@link #align} 入口一次性读出。
		 *
		 * <p>只用于捕获变量的<b>身份</b>判定，不参与指纹；未带 {@code -g} 时为空表。</p>
		 */
		Map<String, String[]> oldParamNames = java.util.Collections.emptyMap();
		Map<String, String[]> newParamNames = java.util.Collections.emptyMap();

		final MethodFingerprinter fingerprinter = MethodFingerprinter.CONTEXT.get();

		/** 当前处理的类名（内部名，形如 {@code com/example/Foo}）。 */
		String currentClass;

		/** 本轮生效的上一代元数据；{@code null} 表示 meta 缺失，读侧退回字节码标记。 */
		AlignmentMetaStore.Entry oldMeta;

		/**
		 * Run R（{@code R_prev ↔ R_new}）是否生效。
		 *
		 * <p>{@code false} = 兼容跑道 Run E：旧侧就是 {@code E_prev}，配对与继承不分家。
		 * {@code true} 时配对只看 {@code R_prev}，"幽灵桩 / 保留体 / 捕获桥"改从 {@link #effPrevCn}
		 * 枚举继承，且产物名字经 {@link #rawKeyToEffKey} 落到生效侧。</p>
		 */
		boolean runR;

		/**
		 * Run R：本轮 {@code R_prev} 方法键（{@code name:desc}）→ 上一代生效侧方法键。
		 *
		 * <p>由上一代元数据提供；不完整即视为"源码侧配对不可证"，整类退回 Run E（见
		 * {@link RunRGate#TRANSLATION_INCOMPLETE}）。</p>
		 */
		Map<String, String> rawKeyToEffKey = Map.of();

		/**
		 * Run R：{@code E_prev} 的结构快照，<b>只用于继承集</b>（幽灵 / 保留体 / 桥 / 命名池），
		 * 绝不参与配对——那是 {@code R_prev} 的职责。
		 */
		ClassNode effPrevCn;

		/** Run R：{@code E_prev} 字节（保留体存活重验与旧体搬运的取值来源）。 */
		byte[] effPrevBytes;

		/** 本轮"按位置兜底配对"的次数（诊断读数，见 {@link AlignmentResult#positionalFallbacks}）。 */
		int positionalFallbacks;

		/** Run R 闸门判定（诊断读数）。 */
		RunRGate runRGate = RunRGate.META_OFF;

		final LongObjectMap<List<SyntheticInfo>> oldGroups = new LongObjectMap<>(128);
		final LongObjectMap<List<SyntheticInfo>> newGroups = new LongObjectMap<>(128);

		/**
		 * 新类里 {@code lambda 方法名 -> 信息} 的索引，供 {@code hasUnmatchedChild}
		 * 判断"我的子 lambda 是否还没落定"。由 {@code scan} 填充。
		 */
		final Map<String, SyntheticInfo> childIndex = new HashMap<>(32);

		/**
		 * 名字 → SyntheticInfo 的完整索引（两侧各一份）。
		 *
		 * <p>替代 {@code infoByName} 的线性查找：后者要遍历所有 group 的所有成员
		 * （双重循环），而它被**三个 64 轮定稿循环**按"每个方法 × 每个子"调用，
		 * 单轮就是 O(N·children·N)，最坏合计 O(64·N²)。有了索引即为 O(1)。</p>
		 *
		 * <p><b>填充时机</b>：必须在 {@code scan} 建完该侧**全部** SyntheticInfo 之后，
		 * 否则会查到 null（幽灵重注入也会改变方法表）。</p>
		 */
		final Map<String, SyntheticInfo> oldNameIndex  = new HashMap<>(64);
		final Map<String, SyntheticInfo> newNameIndex  = new HashMap<>(64);
		final Map<String, Long>          oldAnonHashes = new HashMap<>(16);
		final Map<String, Long>          newAnonHashes = new HashMap<>(16);

		/**
		 * 宿主调用序列：hostKey -> 按 indy 出现顺序的 lambda 目标列表（新旧各一份）。
		 * hostKey 格式：普通方法为 "M:" + name + desc；lambda 合成方法为 "L:" + info.hash。
		 */
		final Map<String, List<SyntheticInfo>> oldHostSeq = new LinkedHashMap<>(64);
		final Map<String, List<SyntheticInfo>> newHostSeq = new LinkedHashMap<>(64);

		int step1Pairs;
		int passAPairs;
		int passBPairs;
		int step1AnchorPairs;
		int hashMismatchPairs;
		int consumerMismatchPairs;
		int consumerMismatchWarnPairs;
		int consumerMismatchDebugPairs;
		int prunedPairs;
		/** 兜底趟因"方法体不等价且无独立身份证据"拒绝配对的次数。 */
		int bodyMismatchRefusals;

		/**
		 * 重置上下文状态，为下一次匹配做准备。
		 * <p>由 {@link #align} 在入口与 finally 中各调用一次。</p>
		 */
		void reset() {
			renameMap.clear();
			renameBySimpleName.clear();
			simpleNameWitness.clear();
			ambiguousNames.clear();
			usedOldNames.clear();
			usedRawSlots.clear();
			inventedOldNames = java.util.Set.of();
			newlyInvented.clear();
			existingNewNames.clear();
			oldNameDescSet.clear();
			bridges.clear();
			oldParamNames = java.util.Collections.emptyMap();
			newParamNames = java.util.Collections.emptyMap();
			oldAnonHashes.clear();
			newAnonHashes.clear();
			fingerprinter.reset();
			currentClass = null;
			oldMeta = null;
			runR = false;
			rawKeyToEffKey = Map.of();
			effPrevCn = null;
			effPrevBytes = null;
			positionalFallbacks = 0;
			runRGate = RunRGate.META_OFF;			oldGroups.clear();
			newGroups.clear();
			childIndex.clear();
			oldNameIndex.clear();
			newNameIndex.clear();
			oldHostSeq.clear();
			newHostSeq.clear();
			step1Pairs = 0;
			passAPairs = 0;
			passBPairs = 0;
			step1AnchorPairs = 0;
			hashMismatchPairs = 0;
			consumerMismatchPairs = 0;
			consumerMismatchWarnPairs = 0;
			consumerMismatchDebugPairs = 0;
			prunedPairs = 0;
			bodyMismatchRefusals = 0;
		}

		/**
		 * 配对成功时要写进产物的名字。
		 *
		 * <p>Run E 直接用旧侧原名（旧侧就是 {@code E_prev}，名字即身份）。Run R 的旧侧是
		 * {@code R_prev}，它的名字只是"上一代源码槽位"，必须经 {@link #rawKeyToEffKey} 翻译成
		 * 上一代生效侧的名字——老 {@code CallSite} 认的是那个名字。</p>
		 */
		String outNameFor(SyntheticInfo oi) {
			if (!runR) return oi.name;
			String effKey = rawKeyToEffKey.get(keyOf(oi));
			if (effKey == null) return oi.name;
			return nameOfKey(effKey);
		}

		/**
		 * 旧方法在<b>生效侧</b>的方法键，口径与产物内部一致（{@code name + desc}，无分隔符）。
		 * Run E 即它自己的键。桥键、{@code presentKeys}、命名池都用这个口径。
		 */
		String effKeyFor(SyntheticInfo oi) {
			if (!runR) return oi.name + oi.desc;
			String effKey = rawKeyToEffKey.get(keyOf(oi));
			if (effKey == null) return oi.name + oi.desc;
			int c = effKey.indexOf(':');
			return c < 0 ? effKey : effKey.substring(0, c) + effKey.substring(c + 1);
		}

		/** 生效侧键的对外口径（{@code name:desc}，与 {@code methodMapping} 一致）。 */
		String effKeyColon(SyntheticInfo oi) {
			String own = keyOf(oi);
			if (!runR) return own;
			String effKey = rawKeyToEffKey.get(own);
			return effKey == null ? own : effKey;
		}

		/** 翻译表与 {@code methodMapping} 统一用 {@code name:desc} 口径。 */
		static String keyOf(SyntheticInfo info) {
			return info.name + ':' + info.desc;
		}

		/**
		 * 该旧方法是否已被占用。
		 *
		 * <p>两个命名空间必须分开：{@code usedOldNames} 记的是"产物里那个名字"，{@code usedRawSlots}
		 * 记的是"这个旧槽位已经配过谁"。Run R 下 Raw 名与生效侧名是两套名字，混在一个扁平集合里会
		 * 互相顶掉（实测：a 占住生效侧 {@code $0} 与 Raw {@code $1} 之后，Raw 侧 {@code $1} 的 b 被误判
		 * 成"名字已被占用"而丢掉配对，老 {@code $1} 直接退成幽灵）。</p>
		 */
		boolean isOldNameClaimed(SyntheticInfo oi) {
			if (usedRawSlots.contains(oi.name)) return true;
			return usedOldNames.contains(outNameFor(oi));
		}

		/** {@code name:desc} 里的名字部分（描述符里不会出现冒号，回退到原名键时也安全）。 */
		static String nameOfKey(String key) {
			if (key == null) return null;
			int c = key.indexOf(':');
			return c < 0 ? key : key.substring(0, c);
		}
	}
	//endregion

	//region 主要 API

	/**
	 * 对齐 Lambda 表达式的主入口方法。
	 * @param oldBytes <b>上一轮对齐后 JVM 里实际生效的字节码</b>（见类级 javadoc 的调用约定）
	 * @param newBytes 本次新编译出的字节码
	 * @return 对齐后的字节码；若没有任何重命名且无孤儿方法需要复活，返回原始 {@code newBytes}；
	 * 若整个流程抛异常，也会记录日志后返回原始 {@code newBytes}（降级不崩溃）
	 */
	public static byte[] align(byte[] oldBytes, byte[] newBytes) {
		return align(oldBytes, newBytes, null, null);
	}

	/**
	 * 对齐 Lambda 表达式的主入口方法（含匿名类字节码解析器）。
	 * @param oldBytes            上一轮实际生效的字节码
	 * @param newBytes            本次新编译出的字节码
	 * @param oldBytecodeResolver 旧侧字节码解析器（传入类内部名，返回对应字节码；可为 null）
	 * @param newBytecodeResolver 新侧字节码解析器（传入类内部名，返回对应字节码；可为 null）
	 * @return 对齐后的字节码
	 */
	public static byte[] align(byte[] oldBytes, byte[] newBytes,
	                           java.util.function.Function<String, byte[]> oldBytecodeResolver,
	                           java.util.function.Function<String, byte[]> newBytecodeResolver) {
		return align(oldBytes, newBytes, oldBytecodeResolver, newBytecodeResolver, java.util.Set.of());
	}

	/**
	 * 对齐主入口（带"上一轮由本对齐器自造的名字"集合）。
	 *
	 * <p><b>为什么需要这个参数</b>：对齐会给"在旧类里找不到对应物"的新 lambda 分配一个
	 * 避障用的新槽位名（例如旧类占用了 {@code $0..$2}，新 lambda 就拿 {@code $4}）。
	 * 这个 {@code $4} 不是 javac 会产出的名字，但对齐完成后它就**留在类里**了。
	 * 下一轮 javac 重新从 0 编号时，某个真正的新 lambda 完全可能正好也叫 {@code $4}——
	 * 此时"同名优先"会把旧 {@code $4}（其实是上一轮另一个 lambda）当成它的对应物，
	 * 于是老 CallSite 被绑到一个语义不同的方法体上：<b>不抛异常、静默跑错</b>。
	 *
	 * <p>把自造名传进来后，"同名"这条证据只在旧名字确实是 javac 原名时才被采信，
	 * 自造名一律退回按顺序配对（顺序配对在这种情况下是正确的：javac 按源序降序发射
	 * 合成方法，前置插入只会让整体位移，不会打乱相对次序）。</p>
	 *
	 * @param inventedOldNames 上一轮由对齐器自造、且仍在旧类里的 lambda 方法名；可为 null
	 */
	public static boolean hasPotentialLambdas(byte[] bytes) {
		if (bytes == null || bytes.length < 8) return false;
		int len = bytes.length;
		for (int i = 0; i <= len - 7; i++) {
			byte b = bytes[i];
			if (b == 'l') { // "lambda$"
				if (bytes[i + 1] == 'a' && bytes[i + 2] == 'm' && bytes[i + 3] == 'b'
				    && bytes[i + 4] == 'd' && bytes[i + 5] == 'a' && bytes[i + 6] == '$') {
					return true;
				}
			} else if (b == '$') { // "$lambda" or "$anonfun$"
				if (bytes[i + 1] == 'l' && bytes[i + 2] == 'a' && bytes[i + 3] == 'm'
				    && bytes[i + 4] == 'b' && bytes[i + 5] == 'd' && bytes[i + 6] == 'a') {
					return true;
				}
				if (i <= len - 9
				    && bytes[i + 1] == 'a' && bytes[i + 2] == 'n' && bytes[i + 3] == 'o'
				    && bytes[i + 4] == 'n' && bytes[i + 5] == 'f' && bytes[i + 6] == 'u'
				    && bytes[i + 7] == 'n' && bytes[i + 8] == '$') {
					return true;
				}
			}
		}
		return false;
	}

	public static byte[] align(byte[] oldBytes, byte[] newBytes,
	                           java.util.function.Function<String, byte[]> oldBytecodeResolver,
	                           java.util.function.Function<String, byte[]> newBytecodeResolver,
	                           java.util.Set<String> inventedOldNames) {
		return align(oldBytes, newBytes, oldBytecodeResolver, newBytecodeResolver, inventedOldNames, null);
	}

	/**
	 * 对齐主入口（带 loader 维度）。
	 *
	 * <p>loader 只用于把同名类在不同类加载器下的元数据分开（见 {@link AlignmentMetaStore}）；
	 * 传 {@code null} 表示"无 loader 维度"（引导类 / 直接调用场景）。</p>
	 */
	public static byte[] align(byte[] oldBytes, byte[] newBytes,
	                           java.util.function.Function<String, byte[]> oldBytecodeResolver,
	                           java.util.function.Function<String, byte[]> newBytecodeResolver,
	                           java.util.Set<String> inventedOldNames,
	                           ClassLoader loader) {
		int  oldLen  = oldBytes == null ? -1 : oldBytes.length;
		int  newLen  = newBytes == null ? -1 : newBytes.length;
		long oldHash = oldBytes == null ? 0 : java.util.Arrays.hashCode(oldBytes);
		long newHash = newBytes == null ? 0 : java.util.Arrays.hashCode(newBytes);
		// PoC：meta 为准。只有"当前代产出指纹 == 本轮 oldBytes 指纹"时才采信元数据；
		// 否则退回字节码标记（fail-closed：宁可读标记，也不拿错代指纹去配幽灵）。
		final boolean metaOn = META_SOURCE;
		AlignmentMetaStore.Entry oldMeta = null;
		String metaClass = classNameOf(newBytes);
		if (metaOn && oldBytes != null && oldBytes.length > 0 && metaClass != null) {
			// 代际身份用 SHA-256 定案（规格 §3.2 G1，禁止把 32 位哈希当树身份）；32 位只在
			// headMatching 内部做快速预筛。精判不成立就是"不是这一代"，不许拿近邻代顶替。
			oldMeta = AlignmentMetaStore.INSTANCE.headMatching(loader, metaClass, oldBytes);
			if (oldMeta != null) inventedOldNames = oldMeta.inventedNames;
		}
		// 先发布空结果：下面有"旧字节为空直接返回"的早退路径，不发布会让调用方读到上一轮的值，
		// 把 A 类的自造名落账到 B 类头上。按类记账同理：本轮没有结论时必须覆盖该类的旧快照。
		String alignKey = classNameOf(newBytes);
		publishResult(alignKey, AlignmentResult.empty(oldLen, newLen, oldHash, newHash));
		if (oldBytes == null || oldBytes.length == 0) return newBytes;

		// ---- Run R 准入（规格 §3.2）：三道闸门全过才允许换旧侧，任一不成立整类退回 Run E ----
		RunRGate gate;
		byte[] rawPrevBytes = null;
		Map<String, String> rawToEff = Map.of();
		ClassNode effPrevCn = null;
		if (!metaOn) {
			gate = RunRGate.META_OFF;
		} else if (oldMeta == null) {
			gate = RunRGate.NO_ENTRY;
		} else if (oldMeta.rawPrevBytes() == null) {
			// 元数据这一代没有留下 R_prev（预算丢弃 / 旧条目 / 从未记录）：继承来源缺失，不能走 Run R。
			gate = RunRGate.NO_RAW_PREV;
		} else {
			rawPrevBytes = oldMeta.rawPrevBytes();
			// G2-a：源码侧配对可证——blob 必须正是元数据记录的那一份（fail-closed，不用"看起来像"顶替）。
			String rawSha = AlignmentMetaStore.sha256Hex(rawPrevBytes);
			if (!rawSha.equals(oldMeta.preAlignSha256)
			    || !AlignmentMetaStore.sha256Hex(oldBytes).equals(oldMeta.jvmEffSha256)
			        && !AlignmentMetaStore.sha256Hex(oldBytes).equals(oldMeta.alignOutputSha256)) {
				gate = RunRGate.RAW_PREV_MISMATCH;
			} else if (!Objects.equals(classNameOf(rawPrevBytes), metaClass)) {
				gate = RunRGate.RAW_PREV_MISMATCH;
			} else if (oldMeta.rawToEffKeys.isEmpty()) {
				// G2-b：没有 Raw → Eff 翻译表就无法决定"活体该叫哪个生效侧名字"。
				gate = RunRGate.TRANSLATION_INCOMPLETE;
			} else {
				// G3：继承集必须可枚举——E_prev 可解析，且翻译表覆盖 R_prev 的全部 lambda 槽位
				// （部分翻译会让某些活体退回 Raw 名、把老 CallSite 变成幽灵：禁止部分继承）。
				try {
					effPrevCn = readMethodTable(oldBytes);
				} catch (Throwable t) {
					effPrevCn = null;
				}
				if (effPrevCn == null || !Objects.equals(effPrevCn.name, metaClass)) {
					gate = RunRGate.EFF_UNREADABLE;
				} else if (!translationCovers(rawPrevBytes, oldMeta.rawToEffKeys)) {
					gate = RunRGate.TRANSLATION_INCOMPLETE;
				} else {
					gate = RunRGate.PASSED;
					rawToEff = oldMeta.rawToEffKeys;
				}
			}
		}
		if (gate != RunRGate.PASSED) {
			warnRunRFallbackOnce(metaClass, gate);
			effPrevCn = null;
			rawPrevBytes = null;
			rawToEff = Map.of();
		}

		MatchContext ctx = CONTEXT.get();
		try {
			ctx.reset();
			ctx.inventedOldNames = inventedOldNames != null ? inventedOldNames : java.util.Set.of();
			ctx.oldMeta = oldMeta;
			ctx.runR = gate == RunRGate.PASSED;
			ctx.rawKeyToEffKey = rawToEff;
			ctx.effPrevCn = effPrevCn;
			ctx.effPrevBytes = ctx.runR ? oldBytes : null;
			ctx.runRGate = gate;
			ctx.newlyInvented.clear();
			// 与类节点同遍读取形参名：名字是"同一个变量"最硬的证据（槽号会随前序局部变量增删而位移）。
			// 未带 -g 时两张表为空，后续判据自动退回槽号/类型；同时剥离行号与 LVT 节点，等价于 SKIP_DEBUG。
			Map<String, String[]> oldParamNames = new HashMap<>();
			Map<String, String[]> newParamNames = new HashMap<>();
			ctx.oldParamNames = oldParamNames;
			ctx.newParamNames = newParamNames;

			// 一次读取即拿到 ClassNode 与 LVT 形参名：oldCn 会被 resurrectOrphanedLambdas 复用，
			// newCn 会在“无重命名”路径下直接提供 presentKeys。
			//
			// Run R：配对基线换成 R_prev（上一代待对齐产物）。它与 E_prev 的区别见规格
			// docs/vcs/03-live-raw-to-raw-alignment.md —— R_prev 只含 javac 槽位身份，
			// 没有自造名/幽灵/桥；E_prev 只用来枚举继承集（ctx.effPrevCn）。
			ClassNode oldCn = scan(ctx.runR ? rawPrevBytes : oldBytes, ctx, true, oldBytecodeResolver, oldParamNames);
			ClassNode newCn = scan(newBytes, ctx, false, newBytecodeResolver, newParamNames);
			if (!Objects.equals(oldCn.name, newCn.name)) {
				throw new IllegalArgumentException(
				 "New class name does not match old class name: " + newCn.name + " != " + oldCn.name);
			}
			ctx.currentClass = oldCn.name;
			if (ctx.runR) {
				// 命名池 = R_prev 方法键 ∪ E_prev 方法键（只增不减）：fresh 名一旦撞上生效侧的幽灵 /
				// 桥 / 自造名槽位，产物里就会出现两个同名键或抢走幽灵槽位，直接破坏"幽灵单向退休"。
				ctx.oldNameDescSet.addAll(effPrevKeys(ctx.effPrevCn));
			}

			List<String> missingLvtNew = LambdaBytecodeScanner.findCapturedLambdasWithoutLvt(ctx.newGroups, newCn.name);
			List<String> missingLvtOld = LambdaBytecodeScanner.findCapturedLambdasWithoutLvt(ctx.oldGroups, oldCn.name);
			Set<String> affectedLvt = new LinkedHashSet<>(missingLvtNew);
			affectedLvt.addAll(missingLvtOld);
			if (!affectedLvt.isEmpty()) {
				RemoldAgent.warn("[LambdaAligner] Class '" + oldCn.name.replace('/', '.')
					+ "' contains captured lambda(s) compiled without debug information (LocalVariableTable missing): "
					+ affectedLvt + ". Recompilation with '-g' is strongly recommended; without '-g', capture reordering"
					+ " cannot be reliably detected and javac may merge inverse lambdas into a single synthetic method.");
				if (RemoldAgent.LAMBDA_STRICT) {
					throw new LambdaAlignmentStrictException(oldCn.name.replace('/', '.'),
						"contains captured lambda(s) missing LocalVariableTable: " + affectedLvt);
				}
			}

			LongObjectMap<List<SyntheticInfo>> newGroups = ctx.newGroups;

			// 【阶段一】多轮迭代匹配（含 settled 定稿机制）：
			//
			// 解决"祖先链被 hasUnmatchedChild 死锁"问题：
			// 当子 lambda 因描述符变动等原因在旧侧完全没有候选时，它在所有趟里都无法配对（matched == false）。
			// 若只看 matched，父 lambda 的 hasUnmatchedChild 会在所有趟中永远返回 true，导致父及其整条祖先链
			// 全部被挡住，最终在阶段二被迫全部拿 fresh name、旧名全幽灵化（即使祖先方法体与结构完全没变）。
			//
			// settled 机制将"未落定"与"确定无候选"分开：
			// 跑完全部趟（Step 1 -> Pass A -> Pass B）后，基于快照收集本轮应标为 settled 的方法：
			// 必须一轮只推进一层，不能在遍历中边查边标，否则在同一轮里叶子先被标为 settled 后，
			// 排在其后的中层会立刻被判断为 !hasUnmatchedChild 并一并标为 settled，
			// 导致中层尚未在 matchAllPasses 中尝试配对就被提前判定为无归宿。
			// 被标记的方法仅代表"不再阻塞父"，在后续轮次中若候选解锁仍可正常配对。
			int settleRound = 0;
			while (true) {
				matchAllPasses(ctx);

				List<SyntheticInfo> toSettle = new ArrayList<>();
				for (int idx : groupOrder(newGroups)) {
					List<SyntheticInfo> g = newGroups.valueAt(idx);
					if (g == null) continue;
					for (SyntheticInfo ni : g) {
						if (!ni.matched && !ni.settled && !ni.ghost && !hasUnmatchedChild(ctx, ni)) {
							toSettle.add(ni);
						}
					}
				}

				if (toSettle.isEmpty()) break;

				for (SyntheticInfo ni : toSettle) {
					ni.settled = true;
					dbg(() -> "SETTLED " + ni.name + " desc=" + ni.desc);
				}

				if (++settleRound >= 64) {
					RemoldAgent.warn("[LambdaAligner] settled loop reached limit (64) without converging for " + ctx.currentClass);
					break;
				}
			}

			// 【阶段一·末】幽灵复活：删掉又原样加回来的 lambda 认领回生前槽位。
			// 必须在阶段二之前跑，否则新方法先拿到避障名，就再也回不去了。
			stepGhostRevive(ctx);

			// 【阶段一·校验】配对后的引用一致性校验（收口防线）。
			//
			// 不变量：配对成功的 (新, 旧) 必须"子归属一致" —— 新方法体内的每个子 lambda
			// 最终所属的旧名字，必须与旧方法体引用的那些名字吻合。违反它意味着
			// "父保住名字、子丢了名字"这类静默错位：老 CallSite 拿到了父，
			// 但父内部指向的已经换人。
			//
			// 曾用"shape 是否相等"做这道校验，但那是**恒真**的：shape 在 scan 里定稿后不再变，
			// 而所有配对路径都已强制 sameNestingLevel（shape 相等），因此它永不触发，
			// 只提供虚假的安全感。改为引用归属后是独立信息。
			//
			// 与当初那个 save3 形状 bug 的关系：那个 bug 是**两侧算成同一个错值**，
			// 任何等值校验都放行；它已被两处修复解决（shape 迭代到定稿、哨兵改用 null），
			// 见 scan 末尾对子树形状的定点迭代。本校验防的是另一类失败（子归属错位）。
			verifyRefConsistency(ctx);

			// 【阶段二前】诊断摘要：只在 DEBUG 下，且每个未配对方法**只打一行**。
			//
			// 为什么不直接在 hasUnmatchedChild 里 dbg：它在 Step 1a、跨组、A 趟、B 趟、
			// 外层 do-while 里都会被调用，逐次打印会把真正的信号淹掉。这里在**全部趟收敛后**
			// 汇总一次，于是"中层被叶子挡住 / 叶子本身无候选"是两行直接证据。
			dbgBlockedSummary(ctx);

			// 【阶段二】未匹配的新方法统一处理
			int freshId = 0;
			for (int idx : groupOrder(newGroups)) {
				List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
				if (newGroup == null) continue;

				for (SyntheticInfo ni : newGroup) {
					if (ni.matched || !ni.renameable) continue;

					String key = ni.name + ni.desc;
					boolean conflict = ctx.usedOldNames.contains(ni.name)
					                   || ctx.oldNameDescSet.contains(key);
					if (conflict) {
						String freshName;
						do {
							freshName = ni.logicalName + (freshId++);
						} while (ctx.usedOldNames.contains(freshName)
						         || ctx.existingNewNames.contains(freshName)
						         || ctx.oldNameDescSet.contains(freshName + ni.desc));

						recordRename(ctx, ni, freshName);
						ctx.usedOldNames.add(freshName);
						// 这个名字不是 javac 会产出的编号，登记为"自造"，
						// 下一轮不许把它当"同名 = 同一个 lambda"的证据。
						ctx.newlyInvented.add(freshName);
					} else {
						// 无冲突：保持原名，但同样要留下见证
						recordRename(ctx, ni, ni.name);
						ctx.usedOldNames.add(ni.name);
					}
				}
			}

			// 【阶段二后】双保险：扫描"见证过的简单名 vs 当前未改名方法"的潜在冲突
			detectResidualAmbiguity(ctx);

			// 应用重命名规则
			byte[]    alignedBytes;
			ClassNode alignedCn;
			if (ctx.renameMap.isEmpty()) {
				// 无重命名：直接复用 scan 阶段已经建好的 ClassNode
				alignedBytes = newBytes;
				alignedCn = newCn;
			} else {
				alignedCn = LambdaBytecodeScanner.applyTransform(newBytes, ctx);
				alignedBytes = LambdaBytecodeScanner.writeClass(alignedCn);
			}

			// 从 alignedCn 直接收集 presentKeys，避免 resurrectOrphanedLambdas 再读一遍
			Set<String> presentKeys = LambdaBytecodeScanner.collectMethodKeys(alignedCn);
			Map<String, Integer> newMethodRefs = LambdaBytecodeScanner.countHandleTargets(alignedCn);
			// 继承集的来源永远是"上一代真正进 JVM 的字节"（Run E 时 oldCn 就是它）。
			final ClassNode inheritCn = ctx.runR ? ctx.effPrevCn : oldCn;
			final byte[] inheritBytes = ctx.runR ? ctx.effPrevBytes : oldBytes;
			Map<String, Integer> oldMethodRefs = LambdaBytecodeScanner.countHandleTargets(inheritCn);

			// 【阶段三】捕获桥：删掉捕获变量的 lambda，用"老签名 → 新方法"的适配器兜住老实例。
			//
			// 必须放在这里：阶段二可能给未配对的新方法分配避障名，桥要指向的是**最终**名字；
			// 同时 presentKeys 已经就绪，可以据此判断"哪些老键本来会变成幽灵"。
			LambdaCaptureBridge.stepCaptureBridge(oldCn, alignedCn, ctx, presentKeys);

			// 后置廉价校验：确保本地 indy 句柄引用的目标方法实际存在
			LambdaBytecodeScanner.validateIndyTargets(alignedCn, presentKeys);

			AlignmentStats stats = new AlignmentStats(ctx.step1Pairs, ctx.passAPairs, ctx.passBPairs,
			                                         ctx.step1AnchorPairs, ctx.hashMismatchPairs,
			                                         ctx.consumerMismatchPairs,
			                                         ctx.consumerMismatchWarnPairs, ctx.consumerMismatchDebugPairs,
			                                         ctx.prunedPairs, ctx.bodyMismatchRefusals);

			Map<String, String> methodMapping = new HashMap<>();
			// Raw 方法键 → 生效侧方法键：下一轮 Run R 的"槽位身份 ↔ 老 CallSite 名字"翻译表。
			// 覆盖**全部** lambda 槽位（配对上的 + 拿避障名的），缺一项就意味着那个活体无法落到
			// 自己的生效侧名字上——那正是 Run R 的准入前提（G2/G3），所以这里必须完整。
			Map<String, String> rawToEffKeys = new HashMap<>();
			for (int idx = newGroups.nextEntry(-1); idx != -1; idx = newGroups.nextEntry(idx)) {
				List<SyntheticInfo> g = newGroups.valueAt(idx);
				if (g == null) continue;
				for (SyntheticInfo ni : g) {
					if (!ni.renameable) continue;
					String rawKey = ni.name + ":" + ni.desc;
					String effKey = LambdaCaptureBridge.finalName(ctx, ni) + ":" + ni.desc;
					rawToEffKeys.put(rawKey, effKey);
					if (ni.matchedWith != null) {
						// 映射的旧键口径：Run R 用生效侧键，Run E 沿用旧侧原名键。
						String oldKey = ctx.effKeyColon(ni.matchedWith);
						methodMapping.put(oldKey, effKey);
					}
				}
			}

			// 传入 inheritCn（已解析过一次）—— 避免 resurrectOrphanedLambdas 二次读同一份字节
			byte[] result = LambdaOrphanEmitter.resurrectOrphanedLambdas(inheritCn, inheritBytes, alignedBytes, presentKeys, oldMethodRefs, newMethodRefs, ctx);
			// 自造名必须从**最终产物**统计：复活阶段会往类里加回幽灵方法，
			// 只在 alignedCn 上扫会漏掉它们（幽灵仍占着那个名字，下一轮照样会被误当 javac 原名）。
			Set<String> invented = collectInventedNames(result, ctx);

			// 唯一的发布点：一次 volatile 写（全局单槽）+ 按类记账，读者不会读到跨轮拼接。
			AlignmentResult snapshot = AlignmentResult.builder()
			                             .stats(stats)
			                             .inventedNames(invented)
			                             .input(oldLen, newLen, oldHash, newHash)
			                             .anonHashes(new HashMap<>(ctx.oldAnonHashes),
			                                         new HashMap<>(ctx.newAnonHashes))
			                             .methodMapping(methodMapping)
			                             .alignedClass(oldCn.name)
			                             .bridgeCount(ctx.bridges.size())
			                             .missingLvtLambdas(affectedLvt)
			                             .metaGeneration(ctx.oldMeta == null ? -1 : ctx.oldMeta.generation)
			                             .suppliedInventedNames(ctx.inventedOldNames)
			                             .baselineTree(ctx.runR ? BaselineTree.RAW_PREV : BaselineTree.EFF_FALLBACK)
			                             .runRGate(ctx.runRGate)
			                             .positionalFallbacks(ctx.positionalFallbacks)
			                             .rawToEffKeys(rawToEffKeys)
			                             .build();
			publishResult(alignKey != null ? alignKey : oldCn.name, snapshot);
			return result;
		} catch (Exception | LinkageError | StackOverflowError e) {
			if (e instanceof LambdaAlignmentStrictException) {
				throw (LambdaAlignmentStrictException) e;
			}
			// 失败也发布：否则调用方会读到上一轮的旧快照，把两轮结果拼在一起。
			publishResult(alignKey, AlignmentResult.empty(oldLen, newLen, oldHash, newHash));
			// 降级：不崩溃，返回原始字节码。捕获 Exception 与 LinkageError、StackOverflowError 等。
			// 默认只记一行（热更失败不该刷屏）；DEBUG 下走 Logger 的 (msg, Throwable)
			// 重载打完整堆栈 —— 而不是直接 printStackTrace，保持输出统一经日志系统。
			if (DEBUG) {
				RemoldAgent.error("[LambdaAligner] align failed, fallback to newBytes", e);
			} else {
				RemoldAgent.info("[LambdaAligner] align failed, fallback to newBytes: " + e);
			}
			return newBytes;
		} finally {
			ctx.reset();
		}
	}

	/**
	 * 记录 {@code ni} 需要从 {@code ni.name} 重命名为 {@code newName}。
	 *
	 * <p>无论是否发生改名，都会通过 {@link #witnessSimpleName} 留下见证：
	 * 同一 simpleName 一旦见证了 ≥2 个不同目标（含“保持不变”这种目标），
	 * 就会被标记为歧义，从而禁止 {@code mapValue} 改写字符串常量，
	 * 避免把本应保持原样的 {@code "lambda$foo$0"} 误改成 {@code "lambda$foo$1"}。</p>
	 */
	private static void recordRename(MatchContext ctx, SyntheticInfo ni, String newName) {
		if (!ni.renameable) return;

		// 关键：先见证 simpleName，再考虑是否写入 renameMap
		witnessSimpleName(ctx, ni.name, newName);

		if (!ni.name.equals(newName)) {
			ctx.renameMap.put(ni.name + ni.desc, newName);
		}
	}

	/**
	 * 见证 simpleName 的目标归宿。
	 *
	 * <p>语义：同一 simpleName 在整个类里必须只对应唯一目标，否则无法安全地
	 * 从字符串常量反查新名字。首次见证时若 {@code target} 与 {@code simpleName}
	 * 不同，则登记到 {@link MatchContext#renameBySimpleName}；此后若出现不同
	 * 目标（包括“保持原名”这一目标本身与别的目标冲突），立即撤回登记并记入
	 * {@link MatchContext#ambiguousNames}。</p>
	 */
	private static void witnessSimpleName(MatchContext ctx, String simpleName, String target) {
		if (ctx.ambiguousNames.contains(simpleName)) return;

		String prev = ctx.simpleNameWitness.get(simpleName);
		if (prev == null) {
			ctx.simpleNameWitness.put(simpleName, target);
			if (!simpleName.equals(target)) {
				ctx.renameBySimpleName.put(simpleName, target);
			}
			// simpleName.equals(target) 时无需登记 renameBySimpleName：
			// mapValue 查不到就相当于保持原名，效果一致。
		} else if (!prev.equals(target)) {
			// 出现第二个目标 -> 歧义，撤回此前的 name-only 映射
			ctx.simpleNameWitness.remove(simpleName);
			ctx.renameBySimpleName.remove(simpleName);
			ctx.ambiguousNames.add(simpleName);
		}
	}

	/**
	 * 双保险：扫描“当前未改名的可重命名方法”，若其 simpleName 此前在
	 * {@link MatchContext#renameBySimpleName} 里留下过别名见证，则说明该名字
	 * 在同一类里既有“保持原名”又有“改到别处”的用法 —— 记入歧义并撤回别名映射。
	 *
	 * <p>多数场景下 {@link #witnessSimpleName} 已能在写入时捕获；此处作为
	 * 兜底，防御未来算法调整引入的漏网情况。</p>
	 *
	 * <p><b>覆盖范围与边界</b>：本方法只扫描 {@link MatchContext#newGroups} 中的
	 * 方法——也就是 {@code scan} 阶段被识别为合成方法（{@code ACC_SYNTHETIC}
	 * 或名字命中 {@link MethodFingerprinter#isSyntheticName}）的那些。以下情形
	 * <b>不在覆盖范围内</b>：</p>
	 * <ul>
	 *   <li>名字只作为字符串常量出现在 {@code $deserializeLambda$} 或业务代码中，
	 *       但类里并无对应方法——例如被反射调用、或在别处拼接出来的 lambda 名。</li>
	 *   <li>名字出现在注解值、类常量池等处，但不对应本类的方法定义。</li>
	 * </ul>
	 *
	 * <p>这些边界情况下，{@link MatchContext#renameBySimpleName} 里的别名映射会保留到最后，
	 * 可能造成 {@code mapValue} 把某个字符串常量改成一个实际上并不存在于本类的
	 * 方法名。因为对应字符串无法通过常规方式被 {@code SerializedLambda} 反查
	 * （{@code getImplMethodName} 只会返回真实存在的方法名），触发概率极低；
	 * 但若此类字符串在业务逻辑里被当作与 lambda 有关的名字使用，需自行评估风险。
	 * 真要彻底闭合，需要在 {@code mapValue} 里对每个字符串做“本类中是否存在对应
	 * 名字”的二次校验，代价是额外一遍扫描，与收益不匹配，暂不实现。</p>
	 */
	private static void detectResidualAmbiguity(MatchContext ctx) {
		var newGroups = ctx.newGroups;
		for (int idx = newGroups.nextEntry(-1); idx != -1; idx = newGroups.nextEntry(idx)) {
			List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
			if (newGroup == null) continue;

			for (SyntheticInfo ni : newGroup) {
				if (!ni.renameable) continue;
				if (ctx.renameMap.containsKey(ni.name + ni.desc)) continue; // 已改名
				if (ctx.ambiguousNames.contains(ni.name)) continue;
				if (ctx.renameBySimpleName.remove(ni.name) != null) {
					ctx.simpleNameWitness.remove(ni.name);
					ctx.ambiguousNames.add(ni.name);
				}
			}
		}
	}

	/**
	 * 收集方法体里<b>直接引用</b>的本类 lambda 系方法名（本方法的"子"lambda）。
	 *
	 * <p>数据来源两处，都指向方法本身而非名字：invokedynamic 的 implMethod 句柄
	 * （内层 lambda 的定义），以及直接的方法调用（lambda 体里显式调用某个合成方法）。
	 * 只收集本类的、命中 lambda 系命名模式的那些。</p>
	 */
	private static List<String> collectChildLambdaNames(MethodNode mn, String owner) {
		List<String> children = null;
		for (AbstractInsnNode n : mn.instructions) {
			String callee = null;
			if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			    && i.bsmArgs[1] instanceof Handle h && owner.equals(h.getOwner())) {
				callee = h.getName();
			} else if (n instanceof MethodInsnNode m && owner.equals(m.owner)) {
				callee = m.name;
			}
			if (callee == null || !MethodFingerprinter.isSyntheticName(callee)) continue;
			if (children == null) children = new ArrayList<>(4);
			if (!children.contains(callee)) children.add(callee);   // 去重但保持出现顺序
		}
		return children == null ? Collections.emptyList() : children;
	}

	/**
	 * 当双方都有子 lambda 时，检查"子的配对对象"是否与旧方法的子逐个吻合。
	 *
	 * <p>为什么需要：{@link #sameSemantics} 是递归语义指纹，后代一旦被编辑，祖先的语义
	 * 指纹也会变，于是祖先拿不到任何候选（实测 save3 的 V2→V3：中层/外层因此被幽灵化，
	 * 子被改体是 update 期最常见的动作）。子树等价这条证据在"后代被改"时必然失效，
	 * 此时退而求其次用**结构证据**：子已经落定（{@link #hasUnmatchedChild} 保证），
	 * 顺着"新子被配给了哪个旧方法"看，只有"子正好是那些旧方法"的旧候选才有资格当选。
	 * 逐层传递，不需要知道嵌套深度。</p>
	 *
	 * <p>只在双方都有子、且子数量一致时启用（结构不符已由 {@link #sameNestingLevel}
	 * 否决）；子尚未落定或信息不足时返回 true，不额外限制。</p>
	 */
	private static boolean calleesPairTo(SyntheticInfo ni, SyntheticInfo oi) {
		if (ni.children.isEmpty() || oi.children.isEmpty()) return true;
		if (ni.children.size() != oi.children.size()) return false;
		for (SyntheticInfo ci : ni.childInfos) {
			if (ci == null) continue;                  // 信息不足，跳过该子
			// 关键修复：采用集合归属校验（与 sameChildOwnership 语义统一，消除方法体内同级子 lambda 颠倒顺序引发的误杀）
			if (ci.matchedWith != null) {
				if (!oi.children.contains(ci.matchedWith.name)) return false;
			}
		}
		return true;
	}

	/**
	 * 该新方法是否还有"尚未落定"的子 lambda（子仍是未匹配状态）。
	 *
	 * <p><b>为什么父必须等子</b>：lambda 可以嵌套，而指纹里内层 lambda 的名字被
	 * {@code #SYNTHETIC_METHOD#} 屏蔽，于是"父"与"子"在指纹上可能完全等价 ——
	 * 例如 {@code run(() -> Time.run(10, () -> doA()))}，父的体就是"求值一个
	 * {@code Time.run(10, 子)}"，与子自身同构。此时若让子先被别处抢走名字，
	 * 父的方法体里那句指向子的引用会被重映射到别人的名字上：父保住了名字、子却丢了，
	 * 语义静默对调。</p>
	 *
	 * <p>实测复现与决策轨迹见 {@code scratch/hstest/swap2/}。判断只看"子是否已 matched"，
	 * 因为匹配是单调的：一旦子落定就不会再变，父可以安全地基于它做决定。</p>
	 */
	private static boolean hasUnmatchedChild(MatchContext ctx, SyntheticInfo ni) {
		if (ni.children.isEmpty()) return false;
		for (String child : ni.children) {
			SyntheticInfo ci = ctx.childIndex.get(child);
			if (ci != null && !ci.matched && !ci.settled) {
				// 这是"祖先链被死锁"那类问题的现场证据：父被未落定的子挡住。
				// 若子已确认无候选被标为 settled，则不再阻挡父，允许父保住名字。
				dbg(() -> "SKIP(parent has unmatched child) parent=" + ni.name
				          + " child=" + child + " (childMatched=false, settled=false)");
				return true;
			}
		}
		return false;
	}

	/**
	 * 配对后的<b>引用一致性</b>校验（对已有不变量的收口，非启发式）。
	 *
	 * <p>不变量：若新方法 {@code ni} 与旧方法 {@code oi} 配成一对，则 {@code ni} 体内的
	 * 每个子 lambda，其<b>最终归属</b>必须与 {@code oi} 体内引用的那些旧名字一致 ——
	 * 否则就是"父保住了名字、子丢了名字"那类静默错位：老 CallSite 拿到了父，
	 * 但父内部指向的已经换人。</p>
	 *
	 * <p><b>为什么不用 shape 校验</b>：`shape` 在 {@code scan} 里算完就不再变，而所有配对路径
	 * 都已强制 {@link #sameNestingLevel}（即 shape 相等），因此"检查 shape 是否相等"恒真、
	 * 永不触发，只会给人虚假的安全感。本校验改用<b>引用归属</b>，是独立信息。</p>
	 *
	 * <p>违反时撤销该配对：旧方法走幽灵、新方法走阶段二拿避障名，把静默错位降级为显式熔断。</p>
	 * @return 撤销的配对数
	 */
	private static int verifyRefConsistency(MatchContext ctx) {
		int totalUndone = 0;
		// 两阶段固定点收敛：消除检查顺序带来的撤销差异（Bug 3 修复）
		while (true) {
			List<SyntheticInfo> toRevoke = new ArrayList<>();
			for (int idx : groupOrder(ctx.newGroups)) {
				List<SyntheticInfo> newGroup = ctx.newGroups.valueAt(idx);
				if (newGroup == null) continue;
				for (SyntheticInfo ni : newGroup) {
					SyntheticInfo oi = ni.matchedWith;
					if (oi == null) continue;
					if (!sameChildOwnership(ctx, ni, oi)) {
						toRevoke.add(ni);
					}
				}
			}

			if (toRevoke.isEmpty()) break;

			for (SyntheticInfo ni : toRevoke) {
				SyntheticInfo oi = ni.matchedWith;
				if (oi == null) continue;

				// 撤销：清掉改名登记与双方的匹配状态。
				// 注意**不回滚** simpleNameWitness / renameBySimpleName：那两张表记录的是
				// "同一 simpleName 的唯一目标"，撤销配对并不会抹掉"曾经见证过"这一事实。
				// 后果偏保守（该名字仍可能被判已使用/歧义），不会产生错误映射。
				// 不尝试回滚的原因：witnessSimpleName 未记录"本次调用是否插入/改写了该条目"，
				// 盲目 remove 会把别的配对写入的见证一并删掉，反而制造不一致。
				if (ctx.renameMap.get(ni.name + ni.desc) != null) {
					ctx.renameMap.remove(ni.name + ni.desc);
				}
				ni.matchedWith = null;
				ni.matched = false;
				oi.matched = false;
				ctx.usedOldNames.remove(ctx.outNameFor(oi));
				ctx.usedRawSlots.remove(oi.name);
				totalUndone++;
				RemoldAgent.warn("[LambdaAligner] Reference consistency violated; reverted pairing: " + ni.name
				                  + " children " + ni.children + " inconsistent with " + oi.name + " children " + oi.children
				                  + " (silent misalignment downgraded to fuse)");
			}
		}
		return totalUndone;
	}

	/**
	 * DEBUG 诊断摘要：在全部趟收敛后、阶段二之前，对每个**仍未配对的可改名新方法**打印一行，
	 * 区分两种完全不同的处境。
	 *
	 * <ul>
	 *   <li>{@code BLOCKED parent=X by child=Y} —— 子未落定，父被 {@link #hasUnmatchedChild}
	 *       挡住。若该子最终无候选，父会在所有趟里一直被挡住，与子一起走幽灵。</li>
	 *   <li>{@code NO-CANDIDATE X} —— 没有被挡，是自己找不到合适旧方法。</li>
	 * </ul>
	 *
	 * <p><b>候选数是近似计数</b>：只按 {@link #isSignatureCompatible} + {@link #sameNestingLevel}
	 * 计，<b>不调用 {@code acceptCandidate}</b> —— 后者要求 {@code sameSemantics}，而后代被编辑时
	 * 祖先的语义指纹必然变化，用它计数会把"结构上可用"的候选误算成 0，反而掩盖真相。</p>
	 */
	private static void dbgBlockedSummary(MatchContext ctx) {
		if (!DEBUG) return;
		for (int idx : groupOrder(ctx.newGroups)) {
			List<SyntheticInfo> g = ctx.newGroups.valueAt(idx);
			if (g == null) continue;
			for (SyntheticInfo ni : g) {
				if (ni.matched || !ni.renameable || ni.ghost) continue;

				String blockedBy = null;
				for (String c : ni.children) {
					SyntheticInfo ci = ctx.childIndex.get(c);
					if (ci != null && !ci.matched && !ci.settled) {
						blockedBy = c;
						break;
					}
				}
				long cand = countCompatCandidates(ctx, ni);
				long leafCand = blockedBy != null ? countCompatCandidates(ctx,
				 ctx.childIndex.get(blockedBy)) : -1;

				if (blockedBy != null) {
					String b  = blockedBy;
					long   lc = leafCand;
					dbg(() -> "BLOCKED parent=" + ni.name + " by child=" + b
					          + "(childMatched=false, oldCandidates=" + lc + ")"
					          + " parentHash=" + ni.hash + " parentSem=" + ni.semanticHash
					          + " parentCand=" + cand);
				} else {
					dbg(() -> "NO-CANDIDATE " + ni.name
					          + " hash=" + ni.hash + " sem=" + ni.semanticHash
					          + " oldCandidates=" + cand);
				}
			}
		}
	}

	/** 旧侧与 {@code ni} 结构上兼容（签名 + 层级）的未匹配候选数，仅供诊断。 */
	private static long countCompatCandidates(MatchContext ctx, SyntheticInfo ni) {
		if (ni == null) return -1;
		long n = 0;
		for (int idx : groupOrder(ctx.oldGroups)) {
			List<SyntheticInfo> g = ctx.oldGroups.valueAt(idx);
			if (g == null) continue;
			for (SyntheticInfo oi : g) {
				if (oi.matched || oi.ghost || !oi.renameable) continue;
				if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
				if (!sameNestingLevel(ni, oi)) continue;
				n++;
			}
		}
		return n;
	}

	/**
	 * {@code ni} 体内**已配对**的子 lambda，其归属是否与 {@code oi} 引用的旧名字一致。
	 *
	 * <p>只比较**已配对**的子：未配对的子还没有最终名字，用它的编译名去比旧名是无效推断
	 * （同一次保存里前面插入一个 lambda 会让编译名整体位移，一个仅仅签名变了、尚未配对的
	 * 子，编译名就不等于旧名），据此撤销会把**合法的父配对**误撤成幽灵。</p>
	 *
	 * <p>同时这也把两类结果区分开：
	 * "子丢了名字"（走幽灵/熔断）是**设计内的降级**，不判违规；
	 * "子被换成了别人"（已配对的子归到了 {@code oi.children} 之外的名字）才是**静默错位**。
	 * 只有后者需要撤销。</p>
	 *
	 * <p>不比较未配对子还顺带消除了对遍历顺序的依赖：撤销某个配对会改变其祖先看到的
	 * 归属关系，若把未配对子也算进来，结果就取决于先处理谁 —— 这与
	 * {@code TEST_REVERSE_GROUP_ORDER} 想守住的性质相悖。</p>
	 */
	private static boolean sameChildOwnership(MatchContext ctx, SyntheticInfo ni, SyntheticInfo oi) {
		for (String c : ni.children) {
			SyntheticInfo ci = infoByName(ctx, false, c);
			if (ci == null) continue;                          // 信息不足：跳过该子
			if (ci.matchedWith == null) continue;              // 未配对：无从判断，跳过
			// 已配对的子必须归到对方引用过的旧名字上
			if (!oi.children.contains(ci.matchedWith.name)) return false;
		}
		return true;
	}

	/**
	 * 判断新方法 {@code ni} 与旧方法 {@code oi} 是否处在同一层级（都是叶子，或两者的
	 * 子 lambda 指纹集合一致）。
	 *
	 * <p><b>为什么需要它</b>：嵌套 lambda 下"父"与"子"可能指纹完全相同 —— 父的体就是
	 * "求值一个 {@code Time.run(10, 子)}"，与子自身同构，而子的名字又被
	 * {@code #SYNTHETIC_METHOD#} 屏蔽。此时若只比指纹，父会配到子（或反之），
	 * 造成"父保住名字、子丢了名字"的语义静默对调。</p>
	 *
	 * <p>区分办法不靠推断嵌套深度，而是比<b>子树形状</b>（{@link SyntheticInfo#shape}）：
	 * 形状只由 indy 引用拓扑决定，父有子、子是叶子，两者必然不同。
	 * 指纹相同但层级不同的候选会被否决。</p>
	 *
	 * <p>实测场景见 {@code scratch/hstest/swap2/}（删除变体）：新旧外层同 hash、
	 * 新旧内层也同 hash，仅凭 hash 无法区分谁是谁。</p>
	 */
	private static boolean sameNestingLevel(SyntheticInfo ni, SyntheticInfo oi) {
		// 比**子树形状**：只由 indy 引用拓扑决定，与方法体内容无关。
		//
		// 演进过程（两个方向都踩过）：
		//   • 早先比 childHashes 的**值** ⇒ 后代被编辑时祖先形状被误判（实测 save3 的
		//     单轮 V2→V3，整条祖先链被幽灵化）；
		//   • 改成只比"有无子 + 子数量"⇒ 外层与中层结构等价（都是 1 个子），互换无人拦
		//     （实测 save3 两轮序列：$3 与 $4 互换，老回调静默改了语义）。
		// 形状同时解决两者：与内容无关、但能区分外层/中层/叶子。
		return ni.shape.equals(oi.shape);
	}

	/**
	 * 执行阶段一的全部匹配趟（Step 1 证据匹配 -> Step 2 A 趟形状深度匹配 -> Step 2 B 趟顺序兜底）。
	 * @return 本轮是否至少产生了一次新配对
	 */
	private static boolean matchAllPasses(MatchContext ctx) {
		LongObjectMap<List<SyntheticInfo>> oldGroups     = ctx.oldGroups;
		LongObjectMap<List<SyntheticInfo>> newGroups     = ctx.newGroups;
		boolean                            anyProgressed = false;

		// 1. Step 1（宿主序列锚点 + 同组同 hash + 跨组指纹，证据匹配，迭代至收敛）
		boolean progressed;
		do {
			progressed = false;
			// 1a. 宿主序列锚点匹配：按普通方法 (name+desc) 或 lambda 票据指纹 (hash) 做 LCS 对齐，
			// 在叶子与多候选同 hash 桶内进行结构消歧，防止误配向上传导
			progressed |= alignByHostSequence(ctx);

			for (int idx : groupOrder(newGroups)) {
				List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
				List<SyntheticInfo> oldGroup = oldGroups.get(newGroups.keyAt(idx));
				if (newGroup == null || oldGroup == null) continue;
				progressed |= step1a(ctx, newGroup, oldGroup);
			}
			progressed |= matchByFingerprintAcrossGroups(ctx);
			anyProgressed |= progressed;
		} while (progressed);

		// 2. 骨架趟：同组内骨架（忽略字面量）在两侧都唯一的对。放在 Pass A 之前，
		//    让"只改了常量"的编辑先按指令结构锁定，而不是退化成 (深度, shape) 的盲盒。
		boolean skelProgressed;
		do {
			skelProgressed = false;
			for (int idx : groupOrder(newGroups)) {
				List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
				List<SyntheticInfo> oldGroup = oldGroups.get(newGroups.keyAt(idx));
				if (newGroup == null || oldGroup == null) continue;
				skelProgressed |= step2SkeletonPass(ctx, newGroup, oldGroup);
			}
			anyProgressed |= skelProgressed;
		} while (skelProgressed);

		// 3. A 趟：全类范围配对"上行深度 + shape 都相等"的对（迭代至收敛）
		boolean aProgressed;
		do {
			aProgressed = false;
			for (int idx : groupOrder(newGroups)) {
				List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
				List<SyntheticInfo> oldGroup = oldGroups.get(newGroups.keyAt(idx));
				if (newGroup == null || oldGroup == null) continue;
				aProgressed |= step2PassA(ctx, newGroup, oldGroup);
			}
			anyProgressed |= aProgressed;
		} while (aProgressed);

		// 3. B 趟：顺序回退兜底（迭代至收敛）
		boolean step2Progressed;
		do {
			step2Progressed = false;
			for (int idx : groupOrder(newGroups)) {
				List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
				List<SyntheticInfo> oldGroup = oldGroups.get(newGroups.keyAt(idx));
				if (newGroup == null || oldGroup == null) continue;
				step2Progressed |= step2(ctx, newGroup, oldGroup);
			}
			anyProgressed |= step2Progressed;
		} while (step2Progressed);

		return anyProgressed;
	}

	/**
	 * 候选是否满足宿主序列对齐条件。
	 *
	 * <p>包含消费者指令守卫（{@code ni.consumer.equals(oi.consumer)}）：
	 * 这是一个<b>安全优先于召回率</b>（safety-over-recall）的权衡设计。其作用域严格限制在
	 * {@link #alignByHostSequence}（锚点对齐）内部，避免在同一个锚点序列中发生消费者不匹配的错位配对；
	 * 后续的 Step 1a、Pass A 以及 Step 2 等全局及保底对齐步骤<b>不受此守卫阻断</b>，即使消费者发生变更
	 * （如从赋值给字段重构为入参传递），Lambda 仍然能通过后续阶段完成对齐。</p>
	 */
	private static boolean canPair(MatchContext ctx, SyntheticInfo ni, SyntheticInfo oi) {
		if (ni.matched || !ni.renameable) return false;
		if (hasUnmatchedChild(ctx, ni)) return false;
		if (!isMutationActive("B") && ni.consumer != null && oi.consumer != null && !ni.consumer.isEmpty() && !oi.consumer.isEmpty()) {
			if (!ni.consumer.equals(oi.consumer)) return false;
		}
		return acceptCandidate(ctx, ni, oi);
	}

	/**
	 * 基于宿主调用序列（普通方法或 lambda 票据指纹）对候选 lambda 做 LCS 对齐。
	 *
	 * <p>比叶子自身指纹更稳的是“被谁引用、在引用者里排第几”：
	 * <ul>
	 *   <li>引用者是普通方法（如 {@code build()}）：{@code name + desc} 在新旧两侧不改名，是现成的稳定锚点；</li>
	 *   <li>引用者是 lambda：用其票据指纹（{@code hash}，子名已被屏蔽故子被改名时不变）为锚点，
	 *       无需等待父子配对结果，打破自底向上与自顶向下的循环等待。</li>
	 * </ul>
	 * 在每个锚点组内，对按 indy 顺序出现的 lambda 序列执行 LCS 最长公共子序列匹配，
	 * 消除同 hash 桶内在增删元素后的同名/首选错位向上传导缺陷。</p>
	 *
	 * @return 本趟是否至少产生了新配对
	 */
	private static boolean alignByHostSequence(MatchContext ctx) {
		if (isMutationActive("A")) return false;
		boolean progressed = false;
		for (Map.Entry<String, List<SyntheticInfo>> e : ctx.newHostSeq.entrySet()) {
			List<SyntheticInfo> ns = e.getValue();
			List<SyntheticInfo> os = ctx.oldHostSeq.get(e.getKey());
			if (os == null || os.isEmpty()) continue;

			// 快速剪枝：若 ns 中已无未配对候选，或 os 中已无可用的未配对项，直接跳过本桶
			boolean hasUnmatchedNew = false;
			for (SyntheticInfo ni : ns) {
				if (!ni.matched && ni.renameable) {
					hasUnmatchedNew = true;
					break;
				}
			}
			if (!hasUnmatchedNew) continue;

			boolean hasUnmatchedOld = false;
			for (SyntheticInfo oi : os) {
				if (!oi.matched && oi.renameable && !oi.ghost && !ctx.isOldNameClaimed(oi)) {
					hasUnmatchedOld = true;
					break;
				}
			}
			if (!hasUnmatchedOld) continue;

			int n = ns.size(), m = os.size();
			if (n < m) {
				checkAndWarnHomomorphicPruning(ctx, e.getKey(), ns, os);
			}
			int[][] dp = new int[n + 1][m + 1];
			for (int i = n - 1; i >= 0; i--) {
				for (int j = m - 1; j >= 0; j--) {
					dp[i][j] = canPair(ctx, ns.get(i), os.get(j))
					           ? dp[i + 1][j + 1] + 1
					           : Math.max(dp[i + 1][j], dp[i][j + 1]);
				}
			}
			for (int i = 0, j = 0; i < n && j < m; ) {
				SyntheticInfo ni = ns.get(i);
				SyntheticInfo oi = os.get(j);
				if (canPair(ctx, ni, oi) && dp[i][j] == dp[i + 1][j + 1] + 1) {
					pair(ctx, ni, oi, "STEP1_ANCHOR");
					ctx.step1Pairs++;
					ctx.step1AnchorPairs++;
					progressed = true;
					i++;
					j++;
				} else if (dp[i + 1][j] >= dp[i][j + 1]) {
					i++;
				} else {
					j++;
				}
			}
		}
		return progressed;
	}

	/**
	 * Step 1a：同组、同 hash、同名 —— 证据最强的一档。
	 * @return 本趟是否至少配对了一个方法（供层级迭代判断是否需要再跑一轮）
	 */
	private static boolean step1a(MatchContext ctx, List<SyntheticInfo> newGroup, List<SyntheticInfo> oldGroup) {
		boolean progressed = false;

		// 第一优先：同组、同 hash、**同名**（证据最强）
		//
		// 这里的"同名"不需要额外区分自造名：{@code acceptCandidate} 已经要求
		// {@code sameSemantics}——同名 + 同语义 ⇒ 就是同一个 lambda，不存在"编号巧合"。
		// 真正需要设防的是 Pass A / Pass B 的"同名优先"：那两处只按 (深度, shape) 收窄，
		// 不校验语义，所以走 {@link #isSameNameEvidence}。
		for (SyntheticInfo ni : newGroup) {
			if (!ni.renameable || ni.matched) continue;
			if (hasUnmatchedChild(ctx, ni)) continue;
			for (SyntheticInfo oi : oldGroup) {
				if (!acceptCandidate(ctx, ni, oi)) continue;
				if (!oi.name.equals(ni.name)) continue;
				if (isAmbiguousAnonCollision(newGroup, oldGroup, ni, oi)) {
					warnAnonMaskedCollision(ctx.currentClass, oi, ni.name, ni.desc);
				}
				pair(ctx, ni, oi, "STEP1");
				ctx.step1Pairs++;
				progressed = true;
				break;
			}
		}

		// 第二优先：同组、同 hash，**不限名字** —— 负责"方法体一字未改、但编译期序号变了"。
		// 之所以能安全地不限名字，是因为加了 hasUnmatchedChild 与 sameNestingLevel 两道护栏：
		// 父与子可能指纹相同（父的体就是"求值一个子"），缺任何一道都会造成
		// "父保住名字、子丢了名字"的静默对调。实测复现见 scratch/hstest/swap2/。
		for (SyntheticInfo ni : newGroup) {
			if (!ni.renameable || ni.matched) continue;
			if (hasUnmatchedChild(ctx, ni)) continue;
			for (SyntheticInfo oi : oldGroup) {
				if (!acceptCandidate(ctx, ni, oi)) continue;
				if (isAmbiguousAnonCollision(newGroup, oldGroup, ni, oi)) {
					warnAnonMaskedCollision(ctx.currentClass, oi, ni.name, ni.desc);
				}
				pair(ctx, ni, oi, "STEP1");
				ctx.step1Pairs++;
				progressed = true;
				break;
			}
		}

		return progressed;
	}

	/** 候选是否可接受：未匹配、非幽灵、指纹与签名一致、且处于同一嵌套层级。 */
	private static boolean acceptCandidate(MatchContext ctx, SyntheticInfo ni, SyntheticInfo oi) {
		if (oi.matched || (!isMutationActive("D") && oi.ghost)) return false;
		// 旧候选也必须可改名 —— 与 ni 上的限制对称。
		// scan 把 access$ / Kotlin $annotations 这类设为非 renameable，用意是不让它们参与改名；
		// 若这里不查，一个空体新 lambda 仍可能认领空体旧 $annotations 的名字。
		if (!oi.renameable) return false;
		// 一个旧简单名只能被一个方法占用（renameBySimpleName 与避障都依赖这一约束）；
		// step2PassA/step2/stepGhostRevive 都查了，Step 1 与宿主序列锚点也必须对称地查。
		if (ctx.isOldNameClaimed(oi)) return false;
		// 用递归语义指纹而非票据指纹：票据指纹屏蔽了子 lambda 的名字，
		// 使"整棵树只差最深处叶子"的方法互相撞车（三层嵌套即如此）。
		// 语义指纹一致 ⇔ 整棵子树等价，因此不会把两个不同的 lambda 配成一对。
		if (!sameSemantics(ni, oi)) return false;
		if (!isSignatureCompatible(ctx.currentClass, oi, ni)) return false;
		if (!sameNestingLevel(ni, oi)) return false;   // 否决：父不得配子
		return calleesPairTo(ni, oi);                  // 正向：子必须配到对方的子
	}

	/** 两个方法的递归语义指纹是否一致（未定稿时退回票据指纹）。 */
	private static boolean sameSemantics(SyntheticInfo a, SyntheticInfo b) {
		long sa = a.semanticHash != 0 ? a.semanticHash : a.hash;
		long sb = b.semanticHash != 0 ? b.semanticHash : b.hash;
		return sa == sb;
	}

	/** 配对并登记：把 {@code ni} 改名为 {@code oi} 的名字（Run R 落生效侧名字）。 */
	private static void pair(MatchContext ctx, SyntheticInfo ni, SyntheticInfo oi, String pass) {
		dbg(() -> "[" + pass + "] PAIR " + ni.name + "(shape=" + ni.shape + " depth=" + ni.upDepth + ")"
		          + " -> " + oi.name + "(shape=" + oi.shape + " depth=" + oi.upDepth + ")"
		          + (ctx.runR ? " effName=" + ctx.outNameFor(oi) : ""));
		String outName = ctx.outNameFor(oi);
		recordRename(ctx, ni, outName);
		ni.matchedWith = oi;
		ni.matched = true;
		oi.matched = true;
		// 认领登记：产物名字空间与 Raw 槽位空间各记一份（Run E 下二者恒等）。
		ctx.usedOldNames.add(outName);
		ctx.usedRawSlots.add(oi.name);
		if (ni.hash != oi.hash) {
			ctx.hashMismatchPairs++;
			TEST_EVENTS.add(new AlignmentEvent("HASH_MISMATCH", oi.name, ni.name, "hash " + oi.hash + "->" + ni.hash));
		}
		if (ni.consumer != null && oi.consumer != null && !ni.consumer.isEmpty() && !oi.consumer.isEmpty()
		    && !ni.consumer.equals(oi.consumer)) {
			ctx.consumerMismatchPairs++;
			// 消费者信号分级（避免对辅助函数重命名的良性编辑产生告警噪音）：
			// 1. 换名（ni.name != oi.name）：名字换了主人，高风险，必须升级为 WARN；
			// 2. 同名但同组内存在多个同 hash 候选（多候选歧义）：升级为 WARN；
			// 3. 同名且组内唯一同 hash 候选（良性消费端重构，如 log(...) -> trace(...)）：降级为 DEBUG 日志记录。
			boolean nameChanged = !ni.name.equals(oi.name);
			boolean multipleHashCandidates = hasMultipleHashCandidates(ctx, ni.hash);
			if (nameChanged || multipleHashCandidates) {
				ctx.consumerMismatchWarnPairs++;
				warnConsumerMismatch(ctx.currentClass, oi, ni.name, ni.desc, oi.consumer, ni.consumer);
			} else {
				ctx.consumerMismatchDebugPairs++;
				dbg(() -> "Consumer signature changed (benign refactor with unique name) " + ctx.currentClass
				          + ": " + oi.name + " [" + oi.consumer + "] -> [" + ni.consumer + "]");
			}
		}
	}

	/**
	 * 从**最终产物字节码**里统计"仍然存在的自造名"。
	 *
	 * <p>必须在复活之后调用：{@code resurrectOrphanedLambdas} 会往类里加回幽灵方法，
	 * 其中可能就有上一轮的自造名。若只在重命名后的 {@code alignedCn} 上扫，这些幽灵会被漏掉，
	 * 下一轮它们占着的名字又会被当成 javac 原名。</p>
	 */
	private static java.util.Set<String> collectInventedNames(byte[] finalBytes, MatchContext ctx) {
		if (ctx.newlyInvented.isEmpty() && ctx.inventedOldNames.isEmpty()) return java.util.Set.of();

		Set<String> presentNames = new HashSet<>();
		try {
			ClassNode cn = new ClassNode();
			new ClassReader(finalBytes).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
			for (MethodNode mn : cn.methods) presentNames.add(mn.name);
		} catch (Exception e) {
			// 解析失败就保守地把两批都报上去：多报只会让下一轮少用一次"同名"证据，
			// 少报则可能让老 CallSite 静默跑错，两者代价不对称。
			presentNames.addAll(ctx.newlyInvented);
			presentNames.addAll(ctx.inventedOldNames);
		}

		Set<String> stillInvented = new HashSet<>(ctx.newlyInvented);
		for (String name : ctx.inventedOldNames) {
			if (presentNames.contains(name)) stillInvented.add(name);
		}
		stillInvented.retainAll(presentNames);
		return java.util.Collections.unmodifiableSet(stillInvented);
	}

	/**
	 * "同名"能不能当证据用。
	 *
	 * <p>同名的前提是<b>两侧名字都出自 javac</b>。旧侧如果是上一轮对齐器自造的避障名
	 * （见 {@link #align(byte[], byte[], java.util.function.Function,
	 * java.util.function.Function, java.util.Set)}），那这个"同名"可能纯属巧合：javac 这一轮
	 * 从头编号，某个真正的新 lambda 完全可能正好落在这个号上。此时按同名配对会把老
	 * CallSite 绑到别的 lambda 体上——不抛异常、静默跑错。</p>
	 *
	 * <p><b>自造名不是一律作废，而是要求指纹佐证</b>：方法体逐字节相同 ⇒ 就是同一个 lambda
	 * （这种情况本来多半已在 Step 1a 被同组同语义配掉，这里只是兜底）。只有"同名但体不同"
	 * 才退回按顺序配对——javac 按源序发射合成方法，插入/删除只让整体位移、不打乱相对次序。</p>
	 */
	private static boolean isSameNameEvidence(MatchContext ctx, SyntheticInfo oi, SyntheticInfo ni) {
		if (!oi.name.equals(ni.name)) return false;
		if (!ctx.inventedOldNames.contains(oi.name)) return true;
		return sameSemantics(ni, oi);
	}

	/**
	 * Step 2 的 **A 趟**：只配"上行深度 + shape 都相等"的对。
	 *
	 * <p>必须在**全类**上先跑完 A 趟，再进 B 趟（见调用处的说明）—— 混在同一个单组循环里
	 * 不够：没有同深度候选的新方法会立刻落到 B 趟，凭先到先得抢走本该属于别人的旧名字。</p>
	 *
	 * <p>只收窄、不做硬否决：B 趟仍会兜底，因此"把叶子用新 lambda 包一层"这类合法编辑
	 * （深度变了）不会因此丢名字、被熔断。</p>
	 *
	 * <p>深度与 shape 都由两侧各自的 indy 关系算出、与匹配状态无关，所以这里对叶子可直接
	 * 判定，不与 {@link #hasUnmatchedChild} 互等。</p>
	 * @return 本趟是否至少配成一对
	 */
	private static boolean step2PassA(MatchContext ctx, List<SyntheticInfo> newGroup,
	                                  List<SyntheticInfo> oldGroup) {
		boolean progressed = false;
		for (SyntheticInfo ni : newGroup) {
			if (ni.matched || !ni.renameable) continue;
			if (ni.upDepth < 0) continue;                 // 未定稿：不参与
			if (hasUnmatchedChild(ctx, ni)) continue;

			SyntheticInfo bestOld = null;
			for (SyntheticInfo oi : oldGroup) {
				if (oi.matched || oi.ghost) continue;
				if (!oi.renameable) continue;             // 与 ni 上的限制对称
				if (ctx.isOldNameClaimed(oi)) continue;
				if (oi.upDepth != ni.upDepth) continue;   // 深度必须相等
				if (!sameNestingLevel(ni, oi)) continue;  // 形状必须相等
				if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
				if (!calleesPairTo(ni, oi)) continue;     // 必须满足结构证据（后代修改时的关键防护）
				if (!fallbackPairingAllowed(ctx, ni, oi)) continue;
				if (isSameNameEvidence(ctx, oi, ni)) {
					bestOld = oi;
					break;
				}   // 同名者优先（自造名不算证据）
				if (bestOld == null) {
					bestOld = oi;
				} else if (hasSameExplicitConsumer(ni, oi) && !hasSameExplicitConsumer(ni, bestOld)) {
					// 没有同名证据时，优先选"消费端指令相同"的候选：
					// 同一宿主方法里 btnSave(...) / btnDelete(...) 的 lambda 形状完全一样，
					// 只有 consumer 能把它们分开。仅作偏好，不构成硬约束（消费点本身可能被改）。
					bestOld = oi;
				}
			}
			if (bestOld == null) continue;

			dbg(() -> "A-PASS ni=" + ni.name + " depth=" + ni.upDepth + " shape=" + ni.shape
			          + " oldCandidates=" + oldGroup.stream()
			           .map(o -> o.name + ":d" + o.upDepth + ":" + o.shape
			                     + (o.matched ? ":M" : "") + (o.ghost ? ":G" : "")).toList());
			if (!ni.name.equals(bestOld.name) && ni.hash != bestOld.hash) {
				warnPositionalMismatch(ctx, bestOld, ni.name, ni.desc);
			}
			pair(ctx, ni, bestOld, "PASS_A");
			ctx.passAPairs++;
			progressed = true;
		}
		return progressed;
	}

	/**
	 * Step 2：顺序回退 —— 只处理"指纹也对不上、仍无归宿"的新方法（B 趟）。
	 *
	 * <p>A 趟（{@link #step2PassA}）已先在**全类**上跑完并配掉"深度 + shape 都相等"的对，
	 * 这里再用放宽的候选条件兜底。两趟分开的原因见 {@link #step2PassA}。</p>
	 * @return 本趟是否至少配对了一个方法
	 */
	private static boolean step2(MatchContext ctx, List<SyntheticInfo> newGroup, List<SyntheticInfo> oldGroup) {
		boolean progressed = false;
		for (SyntheticInfo ni : newGroup) {
			if (ni.matched || !ni.renameable) continue;
			if (hasUnmatchedChild(ctx, ni)) continue;

			SyntheticInfo bestOld = null;

			// 第一优先级：组内同名且签名逻辑等价，且子结构吻合
			//
			// 两道约束缺一不可：
			//   • oi.matched    —— 旧方法只能被用一次；
			//   • usedOldNames  —— 旧**名字**只能被占一次。
			// 第二道是必需的：不同的新方法可以各自选中同一个未被 matched 的旧方法
			// （同一个 oldGroup 里按位置找，很容易撞到同一个候选），于是同一个旧名字
			// 会被登记给两个不同描述符的新方法，renameMap 里一个键覆盖另一个，
			// 结果新方法内部对被改名的子的引用指向了别处。实测见 scratch/hstest/deep2/ 变体乙：
			// 新的叶子占了旧 $2，而新外层内部引用的 $2 实际指向新的中层。
			for (SyntheticInfo oi : oldGroup) {
				if (oi.matched || (!isMutationActive("D") && oi.ghost)) continue;
				if (!oi.renameable) continue;   // 与 ni 上的限制对称
				if (ctx.isOldNameClaimed(oi)) continue;
				if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
				if (!sameNestingLevel(ni, oi)) continue;
				if (!calleesPairTo(ni, oi)) continue;
				if (!fallbackPairingAllowed(ctx, ni, oi)) continue;
				if (isSameNameEvidence(ctx, oi, ni)) {
					bestOld = oi;
					break;
				}
			}

			// 第二优先级：签名逻辑等价且子结构吻合的未匹配旧方法
			if (bestOld == null) {
				for (SyntheticInfo oi : oldGroup) {
					if (oi.matched || (!isMutationActive("D") && oi.ghost)) continue;
					if (!oi.renameable) continue;   // 与 ni 上的限制对称
					if (ctx.isOldNameClaimed(oi)) continue;
					if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
					if (!sameNestingLevel(ni, oi)) continue;
					if (!calleesPairTo(ni, oi)) continue;
					if (!fallbackPairingAllowed(ctx, ni, oi)) continue;
					bestOld = oi;
					break;
				}
			}

			if (bestOld == null) continue;

			// （a）底线守护：真正的危险信号是"名字换了主人"，而不是"方法体变了"。
			//
			// 为什么用名字而不是指纹：
			//   • 名字没变（ni.name == bestOld.name）⇒ 老 CallSite 依然指向它原本
			//     要调的那个方法，无论内部怎么改都是原地更新，不构成故障。
			//     典型：容器 lambda（体内挂着内层 lambda）——改一个内层 lambda
			//     会连带改变外层的槽位布局，使外层指纹必然变化，但外层名字保住了，
			//     老 CallSite 执行的就是"新容器 + 已重映射的内层"，行为正确。
			//     若按指纹报警，这种正常编辑会天天刷屏，把真信号淹掉。
			//   • 名字变了 ⇒ 组内同形候选之间发生了错位让位，老 CallSite 被绑到了
			//     另一个方法体上——这才是需要人去看的情况。
			//
			// 判据不含"是不是容器"的推断：容器特征（体内含指向本类合成方法的
			// invokedynamic）虽然能识别，但"名字是否保住"本身就是充分且更严的证据，
			// 无需额外遍历指令。
			if (!ni.name.equals(bestOld.name)) {
				warnPositionalMismatch(ctx, bestOld, ni.name, ni.desc);
			}
			pair(ctx, ni, bestOld, "PASS_B");
			ctx.passBPairs++;
			progressed = true;
		}
		return progressed;
	}

	/**
	 * 跨逻辑组的指纹匹配：把"方法体一字未改、但逻辑组变了"的 lambda 认领回旧名。
	 *
	 * <p>这是对 {@link #align} 中 Step 2 顺序回退的根本性补强。逻辑组的分组键是
	 * {@code compositeHash(logicalName, normalizedDesc)}，而 {@code logicalName} 来自
	 * <b>承载 lambda 的那个方法名</b>。因此下面两种完全无害的操作会打散分组：</p>
	 * <ul>
	 *   <li>在承载方法开头插入一个新的 lambda —— 后续 lambda 的编译期序号整体推移，
	 *       组内出现"多个同形候选"，Step 2 按顺序配对 → <b>方法体对调</b>；</li>
	 *   <li>把 lambda 挪到另一个方法（或给承载方法改名）—— 逻辑组直接变了，
	 *       即使方法体一个字节都没动也匹配不上。</li>
	 * </ul>
	 *
	 * <p>本方法在进入顺序回退之前，先做一次全类扫描：新方法若与某个未匹配的旧方法
	 * <b>指纹完全相同</b>，直接认领其旧名。指纹只由方法体指令序列决定（行号、局部
	 * 变量表都被 {@link MethodFingerprinter} 忽略），插入新 lambda 对它没有任何影响，
	 * 顺序依赖由此被切断。</p>
	 *
	 * <p><b>为什么安全</b>：指纹相同意味着方法体逐条指令等价，认领旧名只是让老
	 * {@code CallSite} 与它原本要调用的方法体重新对上，不改变任何行为。指纹不同则
	 * 完全不参与匹配（缺口仍由顺序回退处理，并触发 {@link #warnPositionalMismatch}）。
	 * 因为只认领"指纹吻合"的旧方法，也不存在"劫持别人名字"的风险。</p>
	 *
	 * <p><b>选择顺序</b>：先在<b>同组</b>里找指纹相同的未匹配旧方法（最大限度保留
	 * 原有配对倾向），再退到全类范围。全类范围内遇到多个同指纹候选时，优先同名，
	 * 否则取分组遍历顺序里的第一个。</p>
	 *
	 * <p><b>能力边界（实测结论，避免过度承诺）</b>：本方法只能区分“方法体不同”的 lambda。
	 * 指纹由方法体的指令序列决定，因此：</p>
	 * <ul>
	 *   <li>{@code t.button("新建", () -> create());} 与 {@code () -> save();} —— 被调方法名
	 *       参与指纹（{@link MethodFingerprinter#visitMethodInsn} 会把 name 计入），
	 *       三个按钮的指纹互不相同，插入后可被正确认领；</li>
	 *   <li>三个 lambda 体<b>逐字节相同</b>（例如都只写 {@code () -> create()}）时，
	 *       指纹只有一个值，本方法无法区分谁是谁 —— 但此时“谁是谁”在类文件里本来就
	 *       不存在答案，且方法体相同意味着对调也不改变行为，因此不构成故障；</li>
	 *   <li>{@code this::create} 这种<b>方法引用</b>根本不产生 synthetic lambda 方法
	 *       （字节码里是直接指向 {@code create} 的方法句柄），因此不在本对齐器的处理范围内，
	 *       也就谈不上“认领名字”。它只会作为普通方法增删出现在 DIFF 里。</li>
	 * </ul>
	 *
	 * <p>因此真正的风险区间是“多个 lambda 体相似但不相同、且承载同一个方法”，
	 * 这时才会出现对调；该情形由 {@link #warnPositionalMismatch} 留痕。</p>
	 */
	private static boolean matchByFingerprintAcrossGroups(MatchContext ctx) {
		boolean progressed = false;
		var     newGroups  = ctx.newGroups;
		var     oldGroups  = ctx.oldGroups;

		for (int idx : groupOrder(newGroups)) {
			List<SyntheticInfo> newGroup = newGroups.valueAt(idx);
			if (newGroup == null) continue;

			for (SyntheticInfo ni : newGroup) {
				if (ni.matched || !ni.renameable) continue;
				if (hasUnmatchedChild(ctx, ni)) continue;

				SyntheticInfo bestOld = null;

				// 第一优先级：同组内指纹相同（保持 Step 1 之外的原配对倾向）
				List<SyntheticInfo> sameGroup = oldGroups.get(newGroups.keyAt(idx));
				if (sameGroup != null) {
					bestOld = firstFingerprintMatch(ctx, sameGroup, ni, true);
				}
				// 第二优先级：全类范围内指纹相同
				if (bestOld == null) {
					// 2a. 先扫描全类是否恰好有同名同指纹候选（同名最高证据）
					for (int k : groupOrder(oldGroups)) {
						List<SyntheticInfo> g = oldGroups.valueAt(k);
						if (g == null || g == sameGroup) continue;
						SyntheticInfo oi = findSameNameFingerprintMatch(ctx, g, ni);
						if (oi != null) {
							bestOld = oi;
							break;
						}
					}
					// 2b. 若无同名候选，按调用发现顺序（可达优先按 callOrder，不可达按 methodIndex）确定性决胜，消除编译器方法表物理顺序依赖
					if (bestOld == null) {
						for (int k : groupOrder(oldGroups)) {
							List<SyntheticInfo> g = oldGroups.valueAt(k);
							if (g == null || g == sameGroup) continue;
							SyntheticInfo oi = firstFingerprintMatch(ctx, g, ni, false);
							if (oi != null && isBetterCandidate(oi, bestOld)) {
								bestOld = oi;
							}
						}
					}
				}
				if (bestOld == null) continue;

				pair(ctx, ni, bestOld, "STEP1_CROSS");
				ctx.step1Pairs++;
				progressed = true;
			}
		}
		return progressed;
	}

	/**
	 * 候选旧方法择优判决（分层决胜规范约定）：
	 *
	 * <p><b>定位与设计约定：</b>
	 * 跨组或组内存在多个同指纹旧候选时的确定性决胜规则。当且仅当多个候选在语义指纹、签名与嵌套形状
	 * 完全等价且无同名强证据时，由本方法提供确定性决胜。
	 * 本决胜顺序属于对齐算法的<b>内部确定性规范约定（Convention）</b>，而非业务行为推导：
	 * 目的在于彻底消除编译器生成方法表物理顺序（{@code cn.methods}）随机扰动对配对结果的偶发影响。
	 * </p>
	 *
	 * <ol>
	 *   <li><b>可达性分层</b>：从根方法可达的候选（{@code callOrder >= 0}）无条件优先于不可达候选（死代码 / 孤立方法）；</li>
	 *   <li><b>均可达决胜</b>：按 BFS 规范调用发现序（{@code callOrder}，根方法字母序 + 指令流序）小的胜出；</li>
	 *   <li><b>均不可达兜底</b>：按编译器字节码方法表下标（{@code methodIndex}）小的稳定兜底胜出。</li>
	 * </ol>
	 */
	private static boolean isBetterCandidate(SyntheticInfo candidate, SyntheticInfo currentBest) {
		if (currentBest == null) return true;
		if (isMutationActive("C")) {
			return candidate.methodIndex < currentBest.methodIndex;
		}
		boolean candReachable = candidate.callOrder >= 0;
		boolean bestReachable = currentBest.callOrder >= 0;
		if (candReachable != bestReachable) return candReachable;
		if (candReachable) return candidate.callOrder < currentBest.callOrder;
		return candidate.methodIndex < currentBest.methodIndex;
	}

	/** 测试辅助：供回归测试直接验证择优决策分层不变性。 */
	public static boolean testIsBetterCandidate(int candCallOrder, int candMethodIndex, int bestCallOrder, int bestMethodIndex) {
		SyntheticInfo c = new SyntheticInfo("", "", 0, 0, "", true);
		c.callOrder = candCallOrder;
		c.methodIndex = candMethodIndex;
		SyntheticInfo b = new SyntheticInfo("", "", 0, 0, "", true);
		b.callOrder = bestCallOrder;
		b.methodIndex = bestMethodIndex;
		return isBetterCandidate(c, b);
	}

	/** 在 {@code group} 中查找同名且指纹相同的旧方法候选。 */
	private static SyntheticInfo findSameNameFingerprintMatch(MatchContext ctx, List<SyntheticInfo> group,
	                                                          SyntheticInfo ni) {
		if (group == null) return null;
		String owner = ctx.currentClass;
		for (SyntheticInfo oi : group) {
			if (oi.matched || oi.ghost) continue;
			if (!oi.renameable) continue;
			if (ctx.isOldNameClaimed(oi)) continue;
			if (!oi.name.equals(ni.name)) continue;
			if (!sameSemantics(ni, oi)) continue;
			if (!isSignatureCompatible(owner, oi, ni)) continue;
			if (!sameNestingLevel(ni, oi)) continue;
			if (!calleesPairTo(ni, oi)) continue;
			return oi;
		}
		return null;
	}

	/**
	 * 在 {@code group} 中找指纹相同且未被匹配的最优旧方法。
	 * @param preferSameName 是否优先命中与 {@code ni} 同名的候选（同名意味着
	 *                       "编译期序号都没变"，是更强的证据）
	 * @return 命中的旧方法信息；没有则返回 {@code null}
	 */
	private static SyntheticInfo firstFingerprintMatch(MatchContext ctx, List<SyntheticInfo> group,
	                                                   SyntheticInfo ni, boolean preferSameName) {
		if (group == null) return null;
		String owner = ctx.currentClass;

		if (preferSameName) {
			for (SyntheticInfo oi : group) {
				if (oi.matched || oi.ghost) continue;
				if (!oi.renameable) continue;                       // 与 ni 上的限制对称
				if (ctx.isOldNameClaimed(oi)) continue;   // 旧名只能被占一次
				if (!sameSemantics(ni, oi)) continue;
				if (!isSignatureCompatible(owner, oi, ni)) continue;
				if (!sameNestingLevel(ni, oi)) continue;
				if (!calleesPairTo(ni, oi)) continue;
				if (oi.name.equals(ni.name)) return oi;
			}
		}
		SyntheticInfo best = null;
		for (SyntheticInfo oi : group) {
			if (oi.matched || oi.ghost) continue;
			if (!oi.renameable) continue;                       // 与 ni 上的限制对称
			if (ctx.isOldNameClaimed(oi)) continue;   // 旧名只能被占一次
			if (!sameSemantics(ni, oi)) continue;
			if (!isSignatureCompatible(owner, oi, ni)) continue;
			if (!sameNestingLevel(ni, oi)) continue;
			if (!calleesPairTo(ni, oi)) continue;
			if (isBetterCandidate(oi, best)) {
				best = oi;
			}
		}
		return best;
	}

	/** 顺序回退告警去重缓存：同一处错配只报一次。 */
	private static final Set<String> WARNED_REALIGNMENTS =
	 Collections.newSetFromMap(new ConcurrentHashMap<>());

	/**
	 * 清空"只报一次"的去重缓存（{@link #WARNED_REALIGNMENTS} 与
	 * {@link #LOGGED_ORPHANS}）。
	 *
	 * <p><b>当前未被调用</b>（按项目决定保留为可选 API）。因此两个去重集合
	 * <b>在本进程内只增不减</b>，带来两个已知后果：</p>
	 * <ul>
	 *   <li>某个 lambda 在 V2 被删除、V3 又被加回时，其孤儿日志不会再次打印；</li>
	 *   <li>集合大小随"历史上出现过的不同 location 数"单调增长（量级通常很小）。</li>
	 * </ul>
	 *
	 * <p>若要接入热更流程，应在每轮 transform 开始时调用一次，使"每轮都能重新报告"
	 * 且避免无界增长。</p>
	 */
	public static void clearLoggedWarnings() {
		WARNED_REALIGNMENTS.clear();
		TEST_EVENTS.clear();
		LOGGED_RUNR_GATE.clear();
		clearLoggedOrphans();
	}

	/** 测试辅助：获取当前已触发的去重警告总数。 */
	public static int getWarningCount() {
		return WARNED_REALIGNMENTS.size();
	}

	/**
	 * 兜底趟的<b>配对准入判决</b>（fail-closed）：信息不足时拒绝配对，而不是按顺序猜。
	 *
	 * <p>骨架趟 / Pass A / Pass B 都只按 (签名, 深度, shape, 子归属) 收窄候选，<b>不看方法体</b>。
	 * 一旦同组里出现"删一个、加一个"，或者把叶子包进新容器，位置信息就会把<b>别人的名字</b>
	 * 交给一个语义不同的方法体：老 CallSite 不抛异常、静默跑错。此前只在"名字变了"时告警，
	 * 同名错绑完全无声。</p>
	 *
	 * <p>判据按证据强度分六档，任何一档成立才放行；<b>宿主形态翻转一票否决</b>（没有方法体证据时）；
	 * 都没有就 WARN + 拒绝配对，让旧名走幽灵桩（老 CallSite 熔断、响亮失败），新方法去阶段二拿避障名：</p>
	 *
	 * <ol>
	 *   <li><b>逐条等价</b>：递归语义指纹相同，或 {@link #bodyFingerprint} 相同
	 *       （常量池索引 / 局部槽位 / 可折叠算式已归一）。这是<b>证明</b>，与名字、位置无关；</li>
	 *   <li><b>身份锚定</b>：同名 + 同宿主形态（{@code M:}/{@code L:}）。javac 的合成方法名在同一
	 *       宿主方法内就是"槽位身份"，宿主形态不变意味着这个名字没被搬到别人的嵌套层级下——
	 *       此时方法体怎么改都还是<b>原地更新</b>，老 CallSite 拿到的仍是它自己那只 lambda；</li>
	 *   <li><b>消费端有区分度</b>：两侧 consumer 相同，且该宿主方法里的 lambda 消费端<b>不止一种</b>
	 *       （{@code btnSave} / {@code btnDelete} 这种）。消费端在宿主内唯一时没有区分度
	 *       （一堆 lambda 都交给同一个 {@code reg(...)}），不能当证据；</li>
	 *   <li><b>骨架全类唯一</b>：{@link #skeletonHash} 相同且各自类里只有一个 lambda 拥有该骨架
	 *       （见 {@link #isSkeletonGloballyUnique}）。{@code () -> "常量"} 这类骨架会重复，不算证据；</li>
	 *   <li><b>子身份已定案</b>：容器 lambda 的身份由"它包着谁"决定（见
	 *       {@link #isChildIdentityEstablished}）——容器体本身会随内层名字变化，没有区分度；</li>
	 *   <li><b>顺序一致</b>：与同一宿主序列里已定案的配对保持同序（见 {@link #isOrderConsistent}）。
	 *       它把"插入/删除让编号整体位移"（安全）与"两个回调相对次序被换/跨宿主搬家"（危险）分开。</li>
	 * </ol>
	 *
	 * <p><b>残余边界（诚实登记）</b>：删掉一个 lambda、同一次保存里又加一个"javac 恰好给它同一个
	 * 编号（同宿主、同名）"的 lambda，与真正的原地更新在字节码层<b>完全同形</b>，没有规则能区分。
	 * 该形态仍然会被配对，属于信息论边界；能区分的形态（换名、宿主形态翻转、消费端可区分）已全部拦下。</p>
	 */
	private static boolean fallbackPairingAllowed(MatchContext ctx, SyntheticInfo ni, SyntheticInfo oi) {
		if (sameSemantics(ni, oi)) return true;
		if (ni.bodyHash != 0L && ni.bodyHash == oi.bodyHash) return true;

		// 宿主形态翻转（普通方法 ↔ lambda）是"这个 lambda 换了嵌套层次"的直接证据：
		// 把叶子包进新容器时（体内新增内层 lambda），外层的子孙会把旧叶子的名字顶掉，
		// 而两者宿主前缀必然一 M 一 L。没有方法体证据时一律拒绝（L5）。
		if (!hostKind(ni).equals(hostKind(oi))) {
			warnBodyMismatch(ctx, oi, ni);
			return false;
		}
		if (ni.name.equals(oi.name)) return true;
		if (hasDiscriminatingConsumer(ctx, ni, oi)) return true;
		if (isSkeletonGloballyUnique(ctx, ni, oi)) return true;
		if (isChildIdentityEstablished(ni, oi)) return true;
		if (isOrderConsistent(ctx, ni, oi)) return true;

		warnBodyMismatch(ctx, oi, ni);
		return false;
	}

	/**
	 * <b>骨架在全类范围内唯一</b>证据：两侧方法体"只差字面量"（{@link #skeletonHash} 相等），
	 * 且该骨架在各自类里都<b>只有一个</b> lambda 拥有。
	 *
	 * <p>唯一性是关键：{@code () -> "常量"} 这种骨架在任何"一堆返回常量的 lambda"里都会重复，
	 * 那时骨架相等不指向任何具体身份（"删掉 A2、再加一个 A9"正是这种形态）。
	 * 而"容器 lambda"、"循环 lambda"这类结构骨架往往全类唯一，此时骨架相等就是强证据。</p>
	 */
	private static boolean isSkeletonGloballyUnique(MatchContext ctx, SyntheticInfo ni, SyntheticInfo oi) {
		if (ni.skeletonHash == 0L || ni.skeletonHash != oi.skeletonHash) return false;
		return skeletonCount(ctx.newGroups, ni.skeletonHash) == 1
		       && skeletonCount(ctx.oldGroups, oi.skeletonHash) == 1;
	}

	/** 某一侧"拥有该骨架的 lambda 数"（匹配与否都算，幽灵与不可改名者不算）。 */
	private static int skeletonCount(LongObjectMap<List<SyntheticInfo>> groups, long skeleton) {
		int count = 0;
		for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) {
			List<SyntheticInfo> g = groups.valueAt(idx);
			if (g == null) continue;
			for (SyntheticInfo info : g) {
				if (!info.renameable || info.ghost) continue;
				if (info.skeletonHash == skeleton) count++;
			}
		}
		return count;
	}

	/**
	 * <b>子身份建立</b>证据：容器 lambda 的身份由它包着谁决定。
	 *
	 * <p>容器自身的方法体随内层 lambda 的 javac 名字变化而变化（indy 的 implMethod 句柄名参与
	 * {@link #skeletonHash}），因此"容器体改了吗"几乎恒为真、没有区分度。真正稳的是
	 * <b>子已经落定到哪个旧方法</b>：若新容器的每个子都配到了旧容器的子，则两个容器描述的是
	 * 同一段嵌套结构（{@link #calleesPairTo} 已保证子未落定时不作限制，这里把它升级为证据）。</p>
	 */
	private static boolean isChildIdentityEstablished(SyntheticInfo ni, SyntheticInfo oi) {
		if (ni.children.isEmpty() || oi.children.isEmpty()) return false;
		if (ni.children.size() != oi.children.size()) return false;
		for (SyntheticInfo ci : ni.childInfos) {
			if (ci == null || ci.matchedWith == null) return false;
			if (!oi.children.contains(ci.matchedWith.name)) return false;
		}
		return true;
	}

	/**
	 * <b>顺序一致性</b>证据：本候选与同一宿主调用序列里<b>已定案</b>的配对保持同序。
	 *
	 * <p>这是位置兜底唯一站得住的前提。javac 按源序发射合成方法，"插入/删除一个 lambda"只会让
	 * 后续编号整体位移，<b>不打乱相对次序</b>。因此"旧序列里排在 A 与 B 之间的 lambda，在新序列里
	 * 也必须排在 A、B 的对应物之间"。反过来，一旦出现<b>交叉</b>（旧两侧相对次序相反），位置信息
	 * 就不再指向同一个 lambda —— 那是"删一个、加一个"的形态，必须拒绝。</p>
	 *
	 * <p>两侧宿主不是同一个序列（{@code hostKey} 不等，例如普通方法 → lambda）时同样没有顺序证据，
	 * 一律拒绝：那种情形就是"把叶子包进新容器"，它的 javac 编号可能恰好与旧叶子撞名。</p>
	 *
	 * <p>判定只与<b>已定案</b>的配对比较（Step 1 的指纹配对先跑完，因此锚点已在位），
	 * 且要求两侧 {@code hostOrdinal} 都有值；取不到序号的候选没有这份证据。</p>
	 */
	private static boolean isOrderConsistent(MatchContext ctx, SyntheticInfo ni, SyntheticInfo oi) {
		if (ni.hostKey == null || oi.hostKey == null) return false;
		if (!ni.hostKey.equals(oi.hostKey)) return false;
		if (ni.hostOrdinal < 0 || oi.hostOrdinal < 0) return false;

		LongObjectMap<List<SyntheticInfo>> groups = ctx.newGroups;
		for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) {
			List<SyntheticInfo> group = groups.valueAt(idx);
			if (group == null) continue;
			for (SyntheticInfo other : group) {
				SyntheticInfo otherOld = other.matchedWith;
				if (otherOld == null || other == ni) continue;
				if (other.hostOrdinal < 0 || otherOld.hostOrdinal < 0) continue;
				if (!ni.hostKey.equals(other.hostKey) || !ni.hostKey.equals(otherOld.hostKey)) continue;
				int dNew = Integer.compare(ni.hostOrdinal, other.hostOrdinal);
				int dOld = Integer.compare(oi.hostOrdinal, otherOld.hostOrdinal);
				if (dNew != 0 && dOld != 0 && (dNew > 0) != (dOld > 0)) return false;
			}
		}
		return true;
	}

	/**
	 * 宿主形态：{@code M}（普通方法宿主）或 {@code L}（lambda 宿主）。
	 *
	 * <p>把叶子包进新容器时，新叶子的宿主从"普通方法"变成了"外层 lambda"，前缀翻转是
	 * "这个 lambda 换了嵌套位置"最廉价也最可靠的信号（见 {@code docs/initfix/08-test-audit.md} §8.5）。
	 * 取不到宿主时返回 {@code ?}，两侧都是 {@code ?} 才算同形态。</p>
	 */
	private static String hostKind(SyntheticInfo info) {
		String key = info.hostKey;
		if (key == null) return "?";
		if (key.startsWith("M:")) return "M";
		if (key.startsWith("L:")) return "L";
		return "?";
	}

	/**
	 * 消费端是否提供了<b>有区分度</b>的身份证据。
	 *
	 * <p>两侧 consumer 相同还不够：同一个宿主方法里所有 lambda 都交给 {@code reg(...)} 时，
	 * "消费端相同"对每个候选都成立，等于没有信息。只有当该宿主的 lambda 出现<b>≥2 种</b>消费端时，
	 * 等值才真的指向"同一个调用点"。</p>
	 */
	private static boolean hasDiscriminatingConsumer(MatchContext ctx, SyntheticInfo ni, SyntheticInfo oi) {
		if (!hasSameExplicitConsumer(ni, oi)) return false;
		List<SyntheticInfo> host = oi.hostKey == null ? null : ctx.oldHostSeq.get(oi.hostKey);
		if (host == null) return false;
		String first = null;
		for (SyntheticInfo info : host) {
			String c = info.consumer;
			if (c == null || c.isEmpty()) continue;
			if (first == null) first = c;
			else if (!first.equals(c)) return true;
		}
		return false;
	}

	/**
	 * "信息不足却要换主人"的留痕：拒绝配对时打一条 WARN。
	 *
	 * <p>与 {@link #warnPositionalMismatch} 的分工：那条只覆盖"名字变了"，同名错绑（把叶子包进
	 * 新容器时 javac 后序编号恰好撞名）它抓不到。本条按"方法体不等价且没有独立身份证据"触发，
	 * 覆盖名字变与名字不变两种形态。</p>
	 *
	 * <p>去重键为 {@code 类名 + #BODY# + 新方法名 + <- + 旧方法名}，同一处错绑只打印一次。</p>
	 */
	private static void warnBodyMismatch(MatchContext ctx, SyntheticInfo oi, SyntheticInfo ni) {
		ctx.bodyMismatchRefusals++;
		String key = ctx.currentClass + "#BODY#" + ni.name + "<-" + oi.name;
		if (!WARNED_REALIGNMENTS.add(key)) return;

		TEST_EVENTS.add(new AlignmentEvent("BODY_MISMATCH", oi.name, ni.name,
			ctx.currentClass + "#" + ni.name + ni.desc + "<-" + oi.name + oi.desc
			+ " host " + oi.hostKey + "->" + ni.hostKey));
		RemoldAgent.warn("[LambdaAligner] Refused to pair bodies that are not provably equivalent in "
		                  + ctx.currentClass + ": old " + oi.name + oi.desc + " (host " + oi.hostKey
		                  + ", body " + Long.toHexString(oi.bodyHash) + ") <- new " + ni.name + ni.desc
		                  + " (host " + ni.hostKey + ", body " + Long.toHexString(ni.bodyHash) + "). "
		                  + "The old name is not provably the same lambda: names differ (or the host kind flipped) and the method bodies differ beyond "
		                  + "constant-pool indices, local slot indices or foldable arithmetic. The pairing is skipped: the old name is ghosted "
		                  + "(loud fuse) and the new lambda takes a fresh slot. Consider placing each lambda in a distinct private method "
		                  + "to bind identity structurally rather than positionally.");
	}

	/**
	 * 底线守护：顺序回退把"旧名字交给了另一个新方法"时留痕。
	 *
	 * <p>这条告警对应最难排查的一类故障：<b>行为悄悄改变而不抛异常</b>。当同一逻辑组里
	 * 出现多个"归一化描述符完全相同"的 lambda（典型成因：把若干 lambda 都写在一个方法里，
	 * 且它们的捕获列表形状一致）时，{@link #align} 的顺序回退只能按组内出现顺序配对。
	 * 此时若在承载方法开头插入一个新的 lambda，后续 lambda 的整体位移会让两个回调
	 * <b>互相对调</b>，老 {@code CallSite} 去执行另一个回调的方法体。</p>
	 *
	 * <p><b>触发条件刻意收窄为"名字换了主人"</b>（{@code ni.name != bestOld.name}）。
	 * 仅仅"方法体变了"不足以告警：绝大多数编辑（含容器 lambda 内的内层改动）都会让指纹变化，
	 * 但只要名字保住，老 {@code CallSite} 依然是原地更新、行为正确。按指纹报警会让正常
	 * 编辑天天刷屏，真正的信号反而被淹掉。名字换手才是"老 CallSite 被绑到别的方法体上"
	 * 的确证。</p>
	 *
	 * <p>不阻断配对的原因："只改了方法体"是最常见的开发动作，而它本来就该保住名字；
	 * 因此这里只报告。<b>根治办法</b>是把身份交给结构而不是位置：让每个 lambda 有
	 * 独立的承载方法（例如各自一个私有方法），逻辑组自然隔离，顺序回退不再参与。</p>
	 *
	 * <p>去重键为 {@code 类名 + 新方法名 + 旧方法名}，同一处错配只打印一次。</p>
	 */
	private static void warnPositionalMismatch(MatchContext ctx, SyntheticInfo oi, String newName, String newDesc) {
		// 计数与告警去重解耦：同一处错配只打一条 warn，但"这一轮发生了多少次位置兜底"是
		// 配对质量的读数（Run R 应当为 0），不能被 log-once 吞掉。
		if (ctx != null) ctx.positionalFallbacks++;
		String owner = ctx == null ? null : ctx.currentClass;
		String key = owner + "#" + newName + "<-" + oi.name;
		if (!WARNED_REALIGNMENTS.add(key)) return;

		TEST_EVENTS.add(new AlignmentEvent("POS_MISMATCH", oi.name, newName, owner + "#" + newName + newDesc + "<-" + oi.name + oi.desc));
		RemoldAgent.warn("[LambdaAligner] Fallback order pairing with inconsistent body in " + owner
		                  + ": old " + oi.name + oi.desc + " <- new " + newName + newDesc
		                  + ". When multiple isomorphic lambdas exist in the same logical group, insertions/deletions shift indices, "
		                  + "causing old names to bind to different semantics. Old CallSites will execute the new body quietly instead of throwing exceptions. "
		                  + "Consider placing each lambda in a distinct private method to bind identity structurally rather than positionally.");

	}

	/**
	 * 消费者特征异化告警：配对双方具有明确且不同的数据流消费者特征时留痕。
	 *
	 * <p>例如旧 Lambda 由 {@code a(...)} 消费，新 Lambda 由 {@code b2(...)} 消费，
	 * 因上游锚点失效退化至 Step 1a 同名贪婪匹配。虽然方法体可能相同，但调用者上下文
	 * 已发生显著异化，在此记录显式告警以消除静默错配盲区。</p>
	 */
	private static void warnConsumerMismatch(String owner, SyntheticInfo oi, String newName, String newDesc,
	                                         String oldConsumer, String newConsumer) {
		String key = owner + "#CONSUMER#" + newName + "<-" + oi.name;
		if (!WARNED_REALIGNMENTS.add(key)) return;

		TEST_EVENTS.add(new AlignmentEvent("CONSUMER_MISMATCH", oi.name, newName, oldConsumer + "->" + newConsumer));
		RemoldAgent.warn("[LambdaAligner] Consumer profile diverged pairing in " + owner
		                  + ": old " + oi.name + oi.desc + " [" + oldConsumer + "] <- new "
		                  + newName + newDesc + " [" + newConsumer + "]. "
		                  + "Caller consumer profiles diverge; old CallSite may bind to different consumer context.");
	}

	private static boolean hasMultipleHashCandidates(MatchContext ctx, long hash) {
		int count = 0;
		for (int idx = ctx.oldGroups.nextEntry(-1); idx != -1; idx = ctx.oldGroups.nextEntry(idx)) {
			List<SyntheticInfo> g = ctx.oldGroups.valueAt(idx);
			if (g == null) continue;
			for (SyntheticInfo oi : g) {
				if (oi.hash == hash && oi.renameable && !oi.ghost) {
					if (++count >= 2) return true;
				}
			}
		}
		return false;
	}

	private static boolean hasMultipleAnonMaskedWithSameHash(List<SyntheticInfo> list, long hash) {
		if (list == null) return false;
		int count = 0;
		for (SyntheticInfo info : list) {
			if (info.hash == hash && info.anonMasked) {
				if (++count >= 2) return true;
			}
		}
		return false;
	}

	private static boolean isAmbiguousAnonCollision(List<SyntheticInfo> newGroup, List<SyntheticInfo> oldGroup,
	                                                SyntheticInfo ni, SyntheticInfo oi) {
		if (!ni.anonMasked && !oi.anonMasked) return false;
		return hasMultipleAnonMaskedWithSameHash(newGroup, ni.hash)
		       || hasMultipleAnonMaskedWithSameHash(oldGroup, oi.hash);
	}

	/**
	 * 匿名类归一化碰撞风险告警。
	 *
	 * <p>当同组内有 ≥2 个同 hash 且对匿名类进行了归一化屏蔽的候选时触发。
	 * 多个 lambda 仅通过实例化的匿名内部类区分时，指纹会被过度归一化为相同值，
	 * 若发生新增、删除或重排，可能因同名优先或顺序位移导致方法语义错位。</p>
	 */
	private static void warnAnonMaskedCollision(String owner, SyntheticInfo oi, String newName, String newDesc) {
		String key = owner + "#ANON#" + newName + "<-" + oi.name;
		if (!WARNED_REALIGNMENTS.add(key)) return;

		TEST_EVENTS.add(new AlignmentEvent("ANON_COLLISION", oi.name, newName, owner + "#" + newName + newDesc + "<-" + oi.name + oi.desc));
		RemoldAgent.warn("[LambdaAligner] Anon class normalization caused ambiguous pairing for same-fingerprint group in " + owner
		                  + ": old " + oi.name + oi.desc + " <- new " + newName + newDesc
		                  + ". Multiple isomorphic lambdas containing anonymous classes share fingerprints; additions, deletions, or reordering may shift semantics. "
		                  + "Consider extracting anonymous classes to named inner classes or standalone methods.");
	}

	/** 宿主序列存在多个同构候选且发生数量缩减时的告警。 */
	private static void checkAndWarnHomomorphicPruning(MatchContext ctx, String hostKey,
	                                                  List<SyntheticInfo> ns, List<SyntheticInfo> os) {
		Map<Long, Integer> hashCounts = new HashMap<>();
		for (SyntheticInfo oi : os) {
			if (oi.renameable && !oi.ghost) {
				hashCounts.merge(oi.hash, 1, Integer::sum);
			}
		}
		for (Map.Entry<Long, Integer> entry : hashCounts.entrySet()) {
			if (entry.getValue() > 1) {
				ctx.prunedPairs++;
				String key = ctx.currentClass + "#" + hostKey + "#" + entry.getKey();
				if (WARNED_REALIGNMENTS.add(key)) {
					TEST_EVENTS.add(new AlignmentEvent("HOMOMORPHIC_PRUNING", null, null, hostKey + "#hash=" + entry.getKey()));
					RemoldAgent.warn("[LambdaAligner] Multiple isomorphic lambda candidates pruned in " + ctx.currentClass
					                  + " (host: " + hostKey + "): surviving CallSites may be ghosted. Consider extracting critical callbacks into standalone methods.");
				}
				break;
			}
		}
	}
	//endregion

	//region 字节码转换 + 扫描

	/**
	 * 按 blob 缓存的"方法 → 每条指令的消费者标记"索引（Per-Blob Derived Data）。
	 *
	 * <p><b>为什么缓存这一项</b>：{@link #findConsumers} 用 ASM {@code Analyzer} 做反向数据流分析，
	 * 是 {@code align()} 里唯一的重量级派生（占对齐耗时的绝大部分，读数见
	 * {@code docs/vcs/01-bytecode-changeset-vcs.md} §3.7）。它的输出只由「这份 blob 的这个方法」决定，
	 * 与轮次、loader、{@code inventedOldNames}、resolver 都无关，因此可以跨轮复用。</p>
	 *
	 * <p><b>为什么键里必须带方法、值里必须带指令序号</b>：{@link #findConsumers} 返回值的键是
	 * {@code AbstractInsnNode}——那是<b>本轮</b>解析出来的可变节点，不能跨轮持有。所以缓存值改存
	 * "按指令流下标对齐的标记表"，命中后用本轮新解析出的节点按下标重建映射。方法维度不能省：
	 * 同一份 blob 里不同方法的标记表完全不同，漏掉方法维度会让标记整表错位。</p>
	 */
	public static final String CONSUMER_CACHE_KIND = "alignConsumers";


	private static ClassNode scan(byte[] bytes, MatchContext ctx, boolean isOld,
	                              Function<String, byte[]> resolver,
	                              Map<String, String[]> paramNamesOut) {
		// 同一份 blob 在本次派生里会被查多次（匿名类索引、消费者数据流），键只算一次。
		String blobKey = DerivedDataCache.blobKey(bytes);
		ClassNode cn = new ClassNode();
		// SKIP_FRAMES 不解析 StackMapTable；visitSource/visitLineNumber/visitLocalVariable
		// 被剥离以保持与 SKIP_DEBUG 相同的 ClassNode 内容，同时在同一遍中收集 lambda 形参名。
		ClassReader cr = new ClassReader(bytes);
		final String owner = cr.getClassName();
		cr.accept(new ClassVisitor(Opcodes.ASM9, cn) {
			@Override
			public void visitSource(String source, String debug) {
				// 与 SKIP_DEBUG 一致，跳过源文件名与调试扩展，避免填充无关字段
			}

			@Override
			public MethodVisitor visitMethod(int acc, String name, String desc,
			                                 String sig, String[] ex) {
				MethodVisitor mv = super.visitMethod(acc, name, desc, sig, ex);
				return LambdaBytecodeScanner.wrapForParamNames(mv, owner, acc, name, desc, paramNamesOut, true);
			}
		}, ClassReader.SKIP_FRAMES);

		// 预计算方法体引用的匿名类内容哈希，供 MethodFingerprinter 只查表，避免单例重入与重复计算
		Map<String, Long> anonHashes = isOld ? ctx.oldAnonHashes : ctx.newAnonHashes;
		if (resolver != null) {
			Set<String> visiting = new HashSet<>();
			Map<String, Set<String>> anonRefs = LambdaBytecodeScanner.deriveAnonRefs(blobKey, cn);
			for (MethodNode mn : cn.methods) {
				Set<String> refs = anonRefs.get(mn.name + mn.desc);
				if (refs == null) continue;
				for (String anon : refs) {
					String canonical = anon.replace('.', '/');
					if (!anonHashes.containsKey(canonical)) {
						Long h = AnonClassHasher.hash(canonical, null, cn.name, resolver, anonHashes, visiting, 0);
						if (h != null) {
							anonHashes.put(canonical, h);
						} else {
							dbg(() -> "Referenced anonymous class " + canonical + " could not be resolved by resolver. Fingerprint falls back to #ANON_n#.");
						}
					}
				}
			}
		}

		for (int mIdx = 0; mIdx < cn.methods.size(); mIdx++) {
			MethodNode mn = cn.methods.get(mIdx);
			// —— 无条件登记到避障集：让 fresh name 不会撞到类中任何已有方法 ——
			if (isOld) { ctx.oldNameDescSet.add(mn.name + mn.desc); } else ctx.existingNewNames.add(mn.name);

			if (mn.name.startsWith("<")) continue;
			if ((mn.access & Opcodes.ACC_BRIDGE) != 0) continue;
			if (MethodFingerprinter.isExcluded(mn.name)) continue;

			boolean isSynthetic    = (mn.access & Opcodes.ACC_SYNTHETIC) != 0;
			boolean matchesPattern = MethodFingerprinter.isSyntheticName(mn.name);
			if (!isSynthetic && !matchesPattern) continue;

			MethodFingerprinter fp = ctx.fingerprinter;
			fp.reset();
			fp.setContext(cn.name);
			fp.setValidLabels(LambdaBytecodeScanner.collectValidLabels(mn));
			fp.setAnonHashes(anonHashes);

			// 回放指令流；传入的 Label 与 collectValidLabels 取出的实例一致
			mn.accept(fp);

			String logicalName = extractLogicalName(mn.name);
			// access$ 是跨类引用，本对齐器只做“保名不改名”。
			//
			// 这里进一步收窄为"必须命中 lambda 系名字模式"，而不只是"带 ACC_SYNTHETIC"。
			// 原因：本对齐器改名字时只重写**本类内部**对它的引用（见 applyTransform 的
			// mapMethodName），别的类里对该方法的调用不会跟着改。而 ACC_SYNTHETIC 覆盖的
			// 远不止 lambda —— Kotlin 的 foo$default / getX$annotations、编译器生成的各种
			// 桥接桩都带这个标志，它们经常体相同（例如一堆空体的 $annotations）。
			// 一旦让它们参与匹配，跨组指纹匹配就会把"新增的那个同体合成方法"改名成
			// "被删的那个的名字"，而外部调用点仍指向新名字 —— 直接 NoSuchMethodError。
			// 实测复现见 scratch/hstest/kt/：getBaz$annotations 被改成了 getFoo$annotations。
			//
			// 收窄后这些方法只登记进避障集（scan 开头无条件登记 name / name+desc），
			// 阶段二生成避障名时仍会避开它们，但不再参与任何匹配与改名。
			boolean renameable = matchesPattern && !mn.name.startsWith("access$");
			SyntheticInfo info = new SyntheticInfo(
			 mn.name, mn.desc, mn.access, fp.getHash(), logicalName, renameable);
			info.methodIndex = mIdx;
			info.anonMasked = fp.hasMaskedAnon();
			// 幽灵空壳（上一轮为兜住老 CallSite 而注入的空方法）标记为"不参与匹配"：
			// 它必须留在 oldGroups 里供孤儿计算复现，但名字已经是"死名字"，
			// 不能再被当成某个新 lambda 的目标 —— 否则会把它挤到别的名字上去。
			info.ghost = LambdaOrphanEmitter.isGhostMethod(mn);
			if (info.ghost) {
				// meta 命中就读 meta；缺失（首次热更、历史被淘汰、attach 前已加载的类）
				// 才退回桩体里的 LDC 标记。
				Long metaHash = (isOld && ctx.oldMeta != null)
					? ctx.oldMeta.ghostHashes.get(mn.name + mn.desc) : null;
				info.ghostOriginalHash = metaHash != null ? metaHash : LambdaOrphanEmitter.readGhostOriginalHash(mn);
			}
			// 上一轮注入的捕获桥：键在新版本里天然没有对应物，必须在幽灵化之前认出来，
			// 否则第一轮的成果会在第二轮被自己抹掉（见 stepCaptureBridge）。
			info.bridge = LambdaCaptureBridge.isBridgeMethod(mn);
			info.skeletonHash = skeletonHash(mn);
			info.bodyHash = LambdaBodyFingerprint.bodyFingerprint(mn, cn.name);
			// 桥不是 lambda 体：它体内那条 INVOKESTATIC 指向目标方法，若照常登记成"子 lambda"，
			// 目标就会平白多出一个（永远配不上对的）父，把 hasUnmatchedChild 卡死。
			info.children = info.bridge ? Collections.emptyList() : collectChildLambdaNames(mn, cn.name);
			if (!isOld) putNameIndex(ctx.childIndex, info);
			// 完整名字索引（两侧各一份），供 infoByName 做 O(1) 查找。
			// 这里就地填充即可：scan 的循环体已经走完该名字对应的 SyntheticInfo 构建。
			putNameIndex(isOld ? ctx.oldNameIndex : ctx.newNameIndex, info);
			groupByLogic(isOld ? ctx.oldGroups : ctx.newGroups, info, cn.name);
		}

		// 子指纹必须在整类扫描完之后再算：子方法的 SyntheticInfo 要先存在。
		LongObjectMap<List<SyntheticInfo>> groups = isOld ? ctx.oldGroups : ctx.newGroups;
		for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) {
			List<SyntheticInfo> g = groups.valueAt(idx);
			if (g == null) continue;
			for (SyntheticInfo info : g) {
				if (info.children.isEmpty()) continue;

				// 新类侧额外登记"子名 -> SyntheticInfo"，供 calleesPairTo 查"子配给了谁"
				if (!isOld) {
					List<SyntheticInfo> cis = new ArrayList<>(info.children.size());
					for (String c : info.children) cis.add(ctx.childIndex.get(c));
					info.childInfos = cis;
				}
			}
		}

		// 收集按 indy / call 出现顺序的宿主调用序列（新旧各一份）。
		// 普通方法以 "M:" + name + desc 为锚点；
		// lambda 合成方法以 "L:" + info.hash + (info.consumer != null && !info.consumer.isEmpty() ? ":" + info.consumer : "") 为锚点。
		// 关键：采用从非合成根方法出发的广度优先（BFS）遍历，彻底摆脱 class 文件方法表物理顺序（cn.methods）对同 hash 候选遍历序的干扰。
		// 注意：跨宿主根方法（不同业务方法之间）的遍历顺序约定为根方法签名字母序（m.name + m.desc），
		// 这是确定性的规范约定（Convention），而非源文件书写物理序。宿主方法内部的 Lambda 序列严格遵循指令流调用序。
		Map<String, List<SyntheticInfo>> hostSeq   = isOld ? ctx.oldHostSeq : ctx.newHostSeq;
		Map<String, SyntheticInfo>       nameIndex = isOld ? ctx.oldNameIndex : ctx.newNameIndex;

		Map<String, MethodNode> methodNodes = new HashMap<>(cn.methods.size() * 2);
		List<MethodNode> roots = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			methodNodes.put(mn.name + mn.desc, mn);
			if (MethodFingerprinter.isExcluded(mn.name)) continue;
			boolean isSynthetic    = (mn.access & Opcodes.ACC_SYNTHETIC) != 0;
			boolean matchesPattern = MethodFingerprinter.isSyntheticName(mn.name);
			// 确保普通业务方法以及构造函数/静态初始化块（<init>/<clinit>，字段初始化 Lambda 所在地）始终作为根起点
			if (mn.name.startsWith("<") || (!isSynthetic && !matchesPattern)) {
				roots.add(mn);
			}
		}
		// 跨宿主根方法按规范签名升序排序，保证跨编译器/平台遍历顺序完全确定
		roots.sort(Comparator.comparing(m -> m.name + m.desc));

		Queue<MethodNode> queue = new ArrayDeque<>(roots);
		Set<String> enqueued = new HashSet<>(cn.methods.size() * 2);
		for (MethodNode r : roots) {
			enqueued.add(r.name + r.desc);
		}

		int callCounter = 0;
		while (!queue.isEmpty()) {
			MethodNode mn = queue.poll();
			String hostKey;
			boolean isSynthetic    = (mn.access & Opcodes.ACC_SYNTHETIC) != 0;
			boolean matchesPattern = MethodFingerprinter.isSyntheticName(mn.name);
			if (isSynthetic || matchesPattern) {
				SyntheticInfo info = nameIndex.get(mn.name);
				if (info == null || !info.renameable || info.ghost) continue;
				// 链式宿主键：L:<本 lambda 的模板哈希>@<它自己的宿主链>。只放哈希不够——
				// 模板哈希屏蔽字面量，两个**不同宿主**里"仅字面量不同"的 lambda 会得到同一个键，
				// 于是它们的内层 lambda 在 sameHost() 里拿到"同宿主"证据、被跨宿主桥接（S14：
				// i2s 与 s2i 交换 this 形状时，内层 supplier 被接到另一宿主的新体，静默错值）。
				// 把宿主链并进键里，跨宿主的内层 lambda 就再也拿不到同宿主证据，只能 fail-closed
				// 退回幽灵/保留旧体。
				String up = info.hostKey;
				hostKey = "L:" + info.hash + (up == null ? "" : "@" + up)
					+ (!isMutationActive("1") && info.consumer != null && !info.consumer.isEmpty() ? ":" + info.consumer : "");
			} else {
				hostKey = "M:" + mn.name + mn.desc;
			}

			Map<AbstractInsnNode, String> consumers = LambdaBytecodeScanner.consumersOf(blobKey, cn, mn);
			List<SyntheticInfo> seq = null;
			for (AbstractInsnNode n : mn.instructions) {
				String callee = null;
				if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
				    && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
					callee = h.getName();
					LambdaBytecodeScanner.recordLambdaShape(nameIndex.get(callee), i, h, mn,
					                  isOld ? ctx.oldParamNames : ctx.newParamNames, cn.name);
				} else if (n instanceof MethodInsnNode m && cn.name.equals(m.owner)) {
					callee = m.name;
				}
				if (callee == null || !MethodFingerprinter.isSyntheticName(callee)) continue;
				String consumer = consumers.getOrDefault(n, "");
				SyntheticInfo target = nameIndex.get(callee);
				if (target != null && target.renameable && !target.ghost) {
					if (target.consumer == null && !consumer.isEmpty()) {
						target.consumer = consumer;
					}
					if (target.callOrder < 0) {
						target.callOrder = callCounter++;
					}
					if (seq == null) seq = hostSeq.computeIfAbsent(hostKey, k -> new ArrayList<>());
					if (!seq.contains(target)) {
						target.hostKey = hostKey;
						target.hostOrdinal = seq.size();
						seq.add(target);
					}
					if (enqueued.add(target.name + target.desc)) {
						MethodNode targetMn = methodNodes.get(target.name + target.desc);
						if (targetMn != null) {
							queue.add(targetMn);
						}
					}
				}
			}
		}

		// 兜底：处理未从根方法触达的孤立合成方法（如未被调用的死代码）
		List<MethodNode> leftovers = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (MethodFingerprinter.isExcluded(mn.name)) continue;
			if (!enqueued.contains(mn.name + mn.desc)) {
				leftovers.add(mn);
			}
		}
		leftovers.sort(Comparator.comparing(m -> m.name + m.desc));
		for (MethodNode mn : leftovers) {
			// 必须按 name+desc 精确取：桥与它指向的目标<b>同名不同描述符</b>（javac 复用编号），
			// 按名字索引会取到对方，把目标方法的 hostKey 改写成 "L:<桥的哈希>"。
			SyntheticInfo info = LambdaCaptureBridge.findByKey(groups, mn.name, mn.desc);
			if (info == null || !info.renameable || info.ghost || info.bridge) continue;
			// 孤立/死代码不分配正向 callOrder，保持 -1 以标记不可达
			String hostKey = "L:" + info.hash + (!isMutationActive("1") && info.consumer != null && !info.consumer.isEmpty() ? ":" + info.consumer : "");
			Map<AbstractInsnNode, String> consumers = LambdaBytecodeScanner.consumersOf(blobKey, cn, mn);
			List<SyntheticInfo> seq = null;
			for (AbstractInsnNode n : mn.instructions) {
				String callee = null;
				if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
				    && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
					callee = h.getName();
					LambdaBytecodeScanner.recordLambdaShape(nameIndex.get(callee), i, h, mn,
					                  isOld ? ctx.oldParamNames : ctx.newParamNames, cn.name);
				} else if (n instanceof MethodInsnNode m && cn.name.equals(m.owner)) {
					callee = m.name;
				}
				if (callee == null || !MethodFingerprinter.isSyntheticName(callee)) continue;
				String consumer = consumers.getOrDefault(n, "");
				SyntheticInfo target = nameIndex.get(callee);
				if (target != null && target.renameable && !target.ghost) {
					if (target.consumer == null && !consumer.isEmpty()) {
						target.consumer = consumer;
					}
					// 孤立调用同样保持 callOrder 为 -1
					if (seq == null) seq = hostSeq.computeIfAbsent(hostKey, k -> new ArrayList<>());
					if (!seq.contains(target)) {
						target.hostKey = hostKey;
						target.hostOrdinal = seq.size();
						seq.add(target);
					}
				}
			}
		}

		// 子树形状：由子形状串接而成（与内容无关），迭代到定稿。
		//
		// groups 的遍历顺序不确定，父可能先于子被处理；此时**读到未定稿的子就跳过本轮**，
		// 绝不用哨兵值冒充叶子。收敛判据是"没有未定稿 且 无变化"。
		//
		// 性能：串接缓冲在**循环外**分配一次、每轮复用，代替原先每轮每方法的
		// `new ArrayList` + `String.join`。
		//
		// 说明：子形状**必须排序**后才能比较（否则同一形状因子顺序不同而被判为不同，
		// 收敛判据失效）；因此排序无法跳过，能省的是"串接与 String 生成"——
		// 与既有值相同就**不生成 String**。
		//
		// 用 StringBuilder 而非 LookupKey：这里每次都要先 append 再比较，
		// LookupKey 的缓存 hash 必然失效、用不上；它唯一多出的能力
		//（equals 支持 CharSequence 比较）由 {@link String#contentEquals(CharSequence)} 提供。
		// LookupKey 适合"稳定 key 反复查哈希表"的场景，不是这里。
		StringBuilder shapeBuf = new StringBuilder(64);
		for (int round = 0; round < 64; round++) {
			boolean changed = false, pending = false;
			for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) {
				List<SyntheticInfo> g = groups.valueAt(idx);
				if (g == null) continue;
				for (SyntheticInfo info : g) {
					List<String> shapes = new ArrayList<>(info.children.size());
					boolean      ready  = true;
					for (String c : info.children) {
						SyntheticInfo ci = infoByName(ctx, isOld, c);
						if (ci == null || ci.ghost) {
							shapes.add("()");
							continue;
						}   // 幽灵 = 无子
						if (ci.shape == null) {
							ready = false;
							break;
						}               // 子未定稿
						shapes.add(ci.shape);
					}
					if (!ready) {
						pending = true;
						continue;
					}
					Collections.sort(shapes);

					shapeBuf.setLength(0);
					shapeBuf.append('(');
					for (int i = 0; i < shapes.size(); i++) shapeBuf.append(shapes.get(i));
					shapeBuf.append(')');

					// 轻量短路：与既有值相同则不生成 String。
					// contentEquals 在 String 一侧调用，参数是 CharSequence（StringBuilder 满足）。
					if (info.shape != null && info.shape.length() == shapeBuf.length()
					    && info.shape.contentEquals(shapeBuf)) {
						continue;
					}
					info.shape = shapeBuf.toString();
					changed = true;
				}
			}
			if (!changed && !pending) break;
			if (round == 63) {
				// 明确区分"没算完"与"算完了"：这里留下的是**未定稿**（形状可能为 null），
				// 而不是一个看似合法的错值。
				RemoldAgent.warn("[LambdaAligner] Subtree shape in " + (isOld ? "old" : "new")
				                  + " side did not converge in 64 rounds (cycle or abnormal reference); retaining current values");
			}
		}
		// 仍为 null 的（理论上只在成环时出现）退化为叶子形状，保证后续比较不 NPE。
		for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) {
			List<SyntheticInfo> g = groups.valueAt(idx);
			if (g == null) continue;
			for (SyntheticInfo info : g) if (info.shape == null) info.shape = "()";
		}

		// 上行深度：该 lambda 被多少层 lambda 嵌套引用。
		//
		// 由**反向** indy 引用关系算出（谁的体内引用了谁），迭代到定稿。
		//   • 被非 lambda 方法（如 build()）引用 -> 深度 0；
		//   • 只被 lambda 引用 -> 1 + 那些引用者的深度取最大；
		//   • 幽灵**不得当引用者**（它们是空壳，没有真实语义），也不参与。
		//
		// 与 shape 一样，这是纯结构信息、与方法体内容无关，因此"编辑叶子体"不影响它。
		// 哨兵 -1 = 未定稿；0 是合法值，绝不用作哨兵。
		{
			// 反向索引：target -> referrers（仅本侧、排除幽灵）
			Map<String, List<String>> referrers = new HashMap<>();
			for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) {
				List<SyntheticInfo> g = groups.valueAt(idx);
				if (g == null) continue;
				for (SyntheticInfo info : g) {
					if (info.ghost) continue;                       // 幽灵不是引用者
					for (String c : info.children) {
						referrers.computeIfAbsent(c, k -> new ArrayList<>()).add(info.name);
					}
				}
			}
			for (int round = 0; round < 64; round++) {
				boolean changed = false, pending = false;
				for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) {
					List<SyntheticInfo> g = groups.valueAt(idx);
					if (g == null) continue;
					for (SyntheticInfo info : g) {
						if (info.ghost) continue;
						List<String> rs = referrers.get(info.name);
						int          nd;
						if (rs == null || rs.isEmpty()) {
							// 没有任何 lambda 引用它：若它本身是 lambda 合成方法，
							// 说明被普通方法（build()）直接引用 -> 深度 0。
							nd = 0;
						} else {
							int     max        = -1;
							boolean pendingRef = false;
							for (String r : rs) {
								SyntheticInfo ri = infoByName(ctx, isOld, r);
								if (ri == null) {
									pendingRef = true;
									continue;
								}
								if (ri.upDepth < 0) {
									pendingRef = true;
									continue;
								}
								if (ri.upDepth > max) max = ri.upDepth;
							}
							if (pendingRef) {
								pending = true;
								continue;
							}   // 引用者未定稿 -> 本轮跳过
							nd = max + 1;
						}
						if (nd != info.upDepth) {
							info.upDepth = nd;
							changed = true;
						}
					}
				}
				// 与形状定点计算一致：收敛判据是"没有未定稿 且 无变化"。
				// 原先只判 changed，于是"引用者未定稿"（成环等）时第一轮即静默 break，
				// upDepth 停在 -1，而那行告警**永远打不出来** —— 未收敛被完全静默。
				if (!changed && !pending) break;
				if (round == 63) {
					RemoldAgent.warn("[LambdaAligner] Ascendant depth in " + (isOld ? "old" : "new")
					                  + " side did not converge in 64 rounds (possible cycle); unfinalized nodes keep -1");
				}
			}
		}

		computeSemanticHashes(ctx, groups, isOld, cn.name);

		return cn;
	}

	/**
	 * 递归语义指纹：{@link SyntheticInfo#hash} 再逐层折入子的语义指纹。每轮至少定稿一层，
	 * 最多嵌套深度轮即收敛（循环次数上限只是防御）。
	 *
	 * <p>{@code 0} 表示未定稿，而 {@code 0} 不可能是合法指纹值（CRC64 结果），
	 * 因此不存在"哨兵与合法值撞车"的问题。</p>
	 */
	private static void computeSemanticHashes(MatchContext ctx,
	                                          LongObjectMap<List<SyntheticInfo>> groups,
	                                          boolean isOld, String className) {
		for (int round = 0; round < 64; round++) {
			boolean changed = false;
			for (int idx = groups.nextEntry(-1); idx != -1; idx = groups.nextEntry(idx)) {
				List<SyntheticInfo> g = groups.valueAt(idx);
				if (g == null) continue;
				for (SyntheticInfo info : g) {
					long sem = info.hash;
					for (String c : info.children) {
						SyntheticInfo ci = infoByName(ctx, isOld, c);
						if (ci == null) continue;
						// 幽灵是空壳，**没有语义**：不能把它的 hash 折进父的语义指纹。
						// 折进去的后果（实测，save3 两轮序列）：旧的父拿到一个无意义的语义指纹，
						// 于是能与"本不该配"的新方法碰巧等价，外层/中层被静默错绑。
						// 幽灵仍留在 groups 里供孤儿计算复现，但语义指纹计算把它当"无此子"。
						if (ci.ghost) continue;
						long cs = ci.semanticHash != 0 ? ci.semanticHash : ci.hash;
						if (ci.semanticHash == 0 && !ci.children.isEmpty()) {
							sem = 0;
							break;
						}
						sem = Utils.compositeHash(Long.toString(sem), Long.toString(cs));
					}
					if (sem != 0 && sem != info.semanticHash) {
						info.semanticHash = sem;
						changed = true;
					}
				}
			}
			if (!changed) break;
			if (round == 63) {
				// 跑满上限仍未收敛：降级为"沿用当前值并留痕"，绝不抛异常。
				// 正常 javac 产物不会成环（lambda 的构造关系是 DAG），这里只是防御：
				// 真出现环时，宁可让少数方法按当前（可能不完整的）语义指纹参与匹配，
				// 也不能让整次热更失败。
				RemoldAgent.warn("[LambdaAligner] Recursive semantic fingerprint did not converge in 64 rounds for " + className
				                  + "; proceeding with current values");
			}
		}
	}

	/**
	 * 向名字索引中登记 SyntheticInfo。
	 *
	 * <p><b>活方法优先于幽灵</b>：同名不同描述符的幽灵与活方法共存时（如上一轮对齐注入的幽灵），
	 * 活方法必须优先占住名字索引，不能被后追加的幽灵覆盖；若幽灵先被扫描到，后来的活方法也必须覆盖它。
	 * 这保证 {@link #infoByName} 与子引用解析始终拿到真正的活方法，避免语义指纹计算漏折入子节点。</p>
	 *
	 * <p>两个都是活方法的同名不同描述符：仍后写覆盖，并在 DEBUG 下留痕。</p>
	 */
	private static void putNameIndex(Map<String, SyntheticInfo> index, SyntheticInfo info) {
		SyntheticInfo prev = index.get(info.name);
		// 桥与它指向的目标同名不同描述符（javac 复用编号）。索引按名字键，谁最后写谁赢，
		// 所以必须让"真 lambda 体"压过桥：桥是适配器，不是任何人的匹配目标。
		boolean infoSecondary = info.ghost || info.bridge;
		boolean prevSecondary = prev == null || prev.ghost || prev.bridge;
		if (prev == null || (prevSecondary && !infoSecondary)) {
			// 新写入，或已有幽灵/桥、后遇到活方法：活方法覆盖
			index.put(info.name, info);
		} else if (!infoSecondary) {
			// 两个都是活方法：后写覆盖，并在同名不同 desc 时留痕
			if (!prev.desc.equals(info.desc)) {
				dbg(() -> "COLLISION: same-name live methods with different desc: " + info.name
				          + " " + prev.desc + " vs " + info.desc);
			}
			index.put(info.name, info);
		}
		// 若已有活方法且当前为幽灵/桥：保留活方法，静默忽略
	}

	public static Map<String, String[]> readLambdaParamNames(byte[] bytes) {
		return LambdaBytecodeScanner.readLambdaParamNames(bytes);
	}

	/**
	 * 接收者在捕获表里的统一名字。
	 *
	 * <p>它<b>不是</b>源码里的变量名，只是一个"两侧必然相等"的占位符。归一它的理由是
	 * 同一个"捕获到的外层 {@code this}"在不同来源下留下的 {@code LocalVariableTable}
	 * 不一样，而"任一位缺名字就整体放弃按名映射"这条严格判据会把它们判成"没证据"：</p>
	 * <ul>
	 *   <li>实例方法被 {@code forceStaticLambdas} 改成静态：javac 原本给槽 0 写了
	 *       {@code "this"}，改写不动 LVT，读出来是 {@code "this"}；</li>
	 *   <li>零捕获静态 lambda 被前置 receiver：槽 0 是新增的，<b>没有 LVT 条目</b>，读出来是 {@code null}；</li>
	 *   <li><b>kotlinc 原生产物</b>：Indy lambda 天生就是 {@code ACC_STATIC}，槽 0 的 LVT 名字是
	 *       {@code "this$0"}（kotlinc 对外层 this 的一贯写法，与它给内部类合成字段的命名同源）。
	 *       实测（kotlin-compiler-embeddable 2.1.20，{@code -jvm-target 21}）：
	 *       <pre>
	 *   private static final void d$lambda$0(K, int);        // flags: ACC_PRIVATE|STATIC|FINAL
	 *       LocalVariableTable:
	 *           0      11     0 this$0   LK;                 // ← 不是 "this"
	 *           0      11     1    $k   I
	 *       </pre></li>
	 * </ul>
	 *
	 * <p>归一本身是<b>启发式</b>：{@code this$0} 是合法的 Java 标识符（源码里真能写
	 * {@code void d(Foo this$0)}），光看名字分不清"接收者"与"恰好这么命名的局部变量"。
	 * 所以真正的判据在 {@link #captureNamesOf}：能拿到宿主槽号时以槽号为准（接收者的来源恒为
	 * 宿主实例方法的 {@code aload 0}），名字只在回溯失败时兜底。</p>
	 */
	public static final String RECEIVER_NAME = "$this";


	/** 在已扫描的旧/新分组里按名字找 SyntheticInfo（供子指纹计算使用）。 */
	static SyntheticInfo infoByName(MatchContext ctx, boolean isOld, String name) {		// O(1) 查索引。早先是"遍历所有 group 的所有成员"的双重循环，而本方法被
		// **三个 64 轮定稿循环**按"每个方法 × 每个子"调用，最坏合计 O(64·N²)。
		// 见 MatchContext.oldNameIndex / newNameIndex 的说明。
		return (isOld ? ctx.oldNameIndex : ctx.newNameIndex).get(name);
	}

	public static void onBridgeInvoked(String location) {
		LambdaCaptureBridge.onBridgeInvoked(location);
	}

	public static void addOrphanBreaker(OrphanBreakerPredicate predicate) {
		LambdaOrphanEmitter.addOrphanBreaker(predicate);
	}

	public static void removeOrphanBreaker(OrphanBreakerPredicate predicate) {
		LambdaOrphanEmitter.removeOrphanBreaker(predicate);
	}

	public static void addTransparentFramePredicate(TransparentFramePredicate predicate) {
		LambdaOrphanEmitter.addTransparentFramePredicate(predicate);
	}

	public static void removeTransparentFramePredicate(TransparentFramePredicate predicate) {
		LambdaOrphanEmitter.removeTransparentFramePredicate(predicate);
	}

	/**
	 * 当热重载中被删除/孤立的空壳 Lambda 方法被调用时由生成的字节码调用。
	 * @param location 形如 {@code com.example.Foo#lambda$build$0()V} 的常量
	 */
	public static void onOrphanInvoked(String location) {
		LambdaOrphanEmitter.onOrphanInvoked(location);
	}

	/**
	 * 当热重载中被删除/孤立的 Lambda 方法被调用时由生成的字节码调用。
	 * @param location    形如 {@code com.example.Foo#lambda$build$0()V} 的常量
	 * @param willForward 是否将继续执行转发到方法引用的逻辑
	 */
	public static void onOrphanInvoked(String location, boolean willForward) {
		LambdaOrphanEmitter.onOrphanInvoked(location, willForward);
	}

	/**
	 * 当热重载中被删除/孤立的 Lambda 方法被调用时由生成的字节码调用
	 * （保留旧方法体的幽灵走这个三参入口）。
	 */
	public static void onOrphanInvoked(String location, boolean willForward, boolean willRunPreviousBody) {
		LambdaOrphanEmitter.onOrphanInvoked(location, willForward, willRunPreviousBody);
	}

	/**
	 * 判断当前调用是否由已注册的孤儿熔断器（{@link OrphanBreakerPredicate}）发起（决定是否熔断）。
	 */
	public static boolean isOrphanBreakerTriggered() {
		return LambdaOrphanEmitter.isOrphanBreakerTriggered();
	}

	/**
	 * 兼容旧代码与历史测试的别名方法。
	 * @deprecated 请使用 {@link #isOrphanBreakerTriggered()}
	 */
	@Deprecated
	public static boolean isCalledByUpdateRef() {
		return LambdaOrphanEmitter.isOrphanBreakerTriggered();
	}

	/** 反射契约保留入口（供测试探针 {@code GhostProbe} 调用）。 */
	@SuppressWarnings("unused")
	private static boolean isTransparentFrame(String className) {
		return LambdaOrphanEmitter.isTransparentFrame(className);
	}

	/**
	 * 扫描一份"实际进入 JVM 的字节码"里全部幽灵桩的生前指纹。
	 */
	public static Map<String, Long> scanGhostOriginHashes(byte[] effectiveBytes) {
		return LambdaOrphanEmitter.scanGhostOriginHashes(effectiveBytes);
	}

	/**
	 * 幽灵复活：把"原封不动加回来"的 lambda 认领回它生前的槽位。
	 *
	 * <p>为什么需要独立一趟：{@link #acceptCandidate} 明确排除幽灵（幽灵是"死名字"，不能当
	 * 普通匹配目标）。但"删掉一个 lambda 再原样加回来"是常见操作，此时新 lambda 在旧类里
	 * 只有幽灵这一个对应物——指纹一致（生前指纹存在桩体标记里）+ 描述符相同，就是确证。
	 * 认领后新方法占用幽灵的名字，{@code presentKeys} 里就有了它，幽灵不会再被注入。</p>
	 *
	 * <p>只在<b>指纹完全一致</b>时复活：方法体改过的 lambda 不算"同一个"，宁可让它拿新槽位。</p>
	 */
	private static void stepGhostRevive(MatchContext ctx) {
		if (!Boolean.getBoolean("remold.agent.ghost_revive")) return;
		for (int gi = ctx.newGroups.nextEntry(-1); gi != -1; gi = ctx.newGroups.nextEntry(gi)) {
			List<SyntheticInfo> newGroup = ctx.newGroups.valueAt(gi);
			if (newGroup == null) continue;
			for (SyntheticInfo ni : newGroup) {
				if (ni.matched || !ni.renameable) continue;
				for (int oj = ctx.oldGroups.nextEntry(-1); oj != -1; oj = ctx.oldGroups.nextEntry(oj)) {
					List<SyntheticInfo> oldGroup = ctx.oldGroups.valueAt(oj);
					if (oldGroup == null) continue;
					for (SyntheticInfo oi : oldGroup) {
						if (!oi.ghost || oi.matched) continue;
						if (oi.ghostOriginalHash == 0L) continue;          // 无标记：不猜
						if (oi.ghostOriginalHash != ni.hash) continue;      // 生前指纹必须一致
						if (!oi.desc.equals(ni.desc)) continue;             // 形状必须完全一致
						if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
						if (ctx.isOldNameClaimed(oi)) continue;
						pair(ctx, ni, oi, "GHOST_REVIVE");
						TEST_EVENTS.add(new AlignmentEvent("GHOST_REVIVE", oi.name, ni.name,
							ctx.currentClass + "#" + ni.name + ni.desc + " revived to " + oi.name));
						RemoldAgent.info("[LambdaAligner] Ghost revived: " + ctx.currentClass + "#"
							+ oi.name + oi.desc + " (restored verbatim with identical fingerprint)");
						break;
					}
					if (ni.matched) break;
				}
			}
		}
	}

	/**
	 * 方法体"逐条等价"指纹：{@link #fallbackPairingAllowed} 的<b>等价证明</b>判据。
	 *
	 * <p>兜底趟（骨架 / Pass A / Pass B）没有指纹证据，只能靠位置猜。猜之前必须回答
	 * "这两个方法体是不是同一个逻辑体，只被净化/改写掉了表示差异"。本方法给出这个回答：
	 * 三处归一化，缺任何一处都会把<b>同一个方法体</b>误判成不同，进而制造假拒绝：</p>
	 *
	 * <ol>
	 *   <li><b>常量池索引</b>：常量只按值编码（{@code ldc 1000} / {@code sipush 1000} /
	 *       {@code iconst_*} 是同一个常量）。常量池编号本身不参与 —— 它随同批其它字面量增删
	 *       而整体位移。</li>
	 *   <li><b>局部变量槽位</b>：按"首次出现顺序"重编号。捕获表增删会让槽号整体位移，
	 *       原样比对会把"同一个体、槽号平移"判成不同。</li>
	 *   <li><b>可折叠算式</b>：{@code 2 * 3} 与 {@code 6} 折叠成同一个常量（{@code int} /
	 *       {@code long} 的四则、位运算、{@code i2l}/{@code l2i}、取负）。</li>
	 * </ol>
	 *
	 * <p><b>与 {@link #skeletonHash} 的分工</b>：骨架把字面量<b>值</b>也抹掉，因此"只改常量"
	 * 的两个方法骨架相同 —— 骨架只回答"指令结构是否同形"。本方法保留常量值，因此
	 * {@code "A2:v1"} 与 {@code "A9:v1"} <b>不同</b>：值变了就是行为变了，不能当等价证明。</p>
	 *
	 * <p><b>已知边界（实测见 {@code LambdaAlignTortureTest}）</b>：槽位按首次出现顺序归一化，
	 * 因此两个"访问顺序相反但取值不同"的方法体可能被归一成同一串 token（例如
	 * {@code {load a; load b}} 与 {@code {load b; load a}} 都编成 {@code 1,2}）。
	 * 这条边界与 {@code -g:none} 下槽号不可信同源，属于自觉取舍：宁可把"结构同形"放宽，
	 * 也不把"只是槽号位移"的原地编辑误判成重绑。</p>
	 */
	/**
	 * 测试钩子：对给定类字节里 {@code name+desc} 的方法计算体指纹（取不到返回 0）。
	 * 仅用于单测，生产代码不得引用。
	 */
	public static long bodyFingerprintForTest(byte[] classBytes, String name, String desc) {
		return LambdaBodyFingerprint.bodyFingerprintForTest(classBytes, name, desc);
	}

	/**
	 * 测试钩子：把两个常量 token 交给 {@link LambdaBodyFingerprint} 折叠，返回折叠结果的位模式；
	 * 不可折叠返回 {@code null}。仅用于单测，生产代码不得引用。
	 */
	public static Long foldBinaryForTest(int aKind, long aBits, int bKind, long bBits, int op) {
		return LambdaBodyFingerprint.foldBinaryForTest(aKind, aBits, bKind, bBits, op);
	}

	/**
	 * 忽略字面量常量的"指令骨架"指纹。
	 *
	 * <p>与 {@link MethodFingerprinter} 的区别只有一条：**字面量只记类型、不记值**。
	 * 于是"把 {@code "A1:v1"} 改成 {@code "A1:v2"}"不会改变骨架，而"50 行循环"与"2 行字符串拼接"
	 * 骨架必然不同。局部变量槽位下标与跳转目标也不参与（它们会随捕获列表变化而位移）。</p>
	 *
	 * <p>用途仅限 {@link #step2SkeletonPass} 的保守配对：要求该骨架在新旧两侧都**只出现一次**，
	 * 因此不会把"两个同骨架 lambda"硬配在一起。</p>
	 */
	private static long skeletonHash(MethodNode mn) {
		if (mn.instructions == null) return 0L;
		long h = 1125899906842597L;
		for (AbstractInsnNode insn : mn.instructions) {
			int op = insn.getOpcode();
			if (op < 0) continue;                       // Label / LineNumber / Frame
			h = h * 31 + op;

			if (insn instanceof MethodInsnNode m) {
				h = h * 31 + m.owner.hashCode();
				h = h * 31 + m.name.hashCode();
				h = h * 31 + m.desc.hashCode();
			} else if (insn instanceof FieldInsnNode f) {
				h = h * 31 + f.owner.hashCode();
				h = h * 31 + f.name.hashCode();
				h = h * 31 + f.desc.hashCode();
			} else if (insn instanceof TypeInsnNode t) {
				h = h * 31 + t.desc.hashCode();
			} else if (insn instanceof InvokeDynamicInsnNode indy) {
				h = h * 31 + indy.name.hashCode();
				h = h * 31 + indy.desc.hashCode();
				if (indy.bsm != null) {
					h = h * 31 + indy.bsm.getOwner().hashCode();
					h = h * 31 + indy.bsm.getName().hashCode();
				}
				if (indy.bsmArgs != null) {
					// bsmArgs 里既有字符串常量（recipe / 方法名），也有 Handle / Type。
					// 字符串只记类型，Handle 记完整目标（它是结构信息）。
					for (Object a : indy.bsmArgs) {
						if (a instanceof Handle hd) {
							h = h * 31 + hd.getOwner().hashCode();
							h = h * 31 + hd.getName().hashCode();
							h = h * 31 + hd.getDesc().hashCode();
						} else {
							h = h * 31 + literalTypeTag(a);
						}
					}
				}
			} else if (insn instanceof LdcInsnNode ldc) {
				h = h * 31 + literalTypeTag(ldc.cst);
			} else if (insn instanceof IntInsnNode ii) {
				// NEWARRAY 的 operand 是数组类型（结构信息）；BIPUSH / SIPUSH 是字面量，忽略。
				if (op == Opcodes.NEWARRAY) h = h * 31 + ii.operand;
			}
			// VarInsnNode / IincInsnNode 的槽位下标忽略：捕获列表一变，槽位就整体位移。
		}
		return h;
	}

	/** 字面量的"类型标签"，用于骨架指纹（不暴露具体值）。 */
	private static int literalTypeTag(Object cst) {
		if (cst == null) return 0;
		if (cst instanceof String) return 1;
		if (cst instanceof Integer) return 2;
		if (cst instanceof Long) return 3;
		if (cst instanceof Float) return 4;
		if (cst instanceof Double) return 5;
		if (cst instanceof Type) return 6;
		if (cst instanceof Handle) return 7;
		return 8;
	}

	/**
	 * 骨架配对：同组内、骨架在**两侧都唯一**时才配对。
	 *
	 * <p>这是"只改常量"那一类编辑的正解：普通指纹失效后，Pass A/B 只剩 (深度, shape)，
	 * 同方法里的多个 lambda 会退化成盲盒。骨架指纹保留指令结构，能把这些盲盒拆开。</p>
	 *
	 * <p><b>保守条件</b>：该骨架在新侧未匹配方法里只出现一次、在旧侧未匹配方法里也只出现一次。
	 * 只要有一侧出现两次，就说明骨架没有区分度（例如三个都只做字符串拼接的 lambda），
	 * 此时宁可不配，交给后面的 Pass A/B 按顺序兜底——避免引入新的错配。</p>
	 */
	private static boolean step2SkeletonPass(MatchContext ctx, List<SyntheticInfo> newGroup,
	                                         List<SyntheticInfo> oldGroup) {
		Map<Long, Integer> newCount = new HashMap<>();
		Map<Long, Integer> oldCount = new HashMap<>();
		for (SyntheticInfo ni : newGroup) {
			if (!skeletonEligibleNew(ni)) continue;
			newCount.merge(ni.skeletonHash, 1, Integer::sum);
		}
		if (newCount.isEmpty()) return false;
		for (SyntheticInfo oi : oldGroup) {
			if (!skeletonEligibleOld(ctx, oi)) continue;
			oldCount.merge(oi.skeletonHash, 1, Integer::sum);
		}

		boolean progressed = false;
		for (SyntheticInfo ni : newGroup) {
			if (!skeletonEligibleNew(ni)) continue;
			if (newCount.getOrDefault(ni.skeletonHash, 0) != 1) continue;   // 新侧必须唯一
			if (oldCount.getOrDefault(ni.skeletonHash, 0) != 1) continue;   // 旧侧必须唯一

			for (SyntheticInfo oi : oldGroup) {
				if (!skeletonEligibleOld(ctx, oi)) continue;
				if (oi.skeletonHash != ni.skeletonHash) continue;
				if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
				if (!sameNestingLevel(ni, oi)) continue;
				if (!calleesPairTo(ni, oi)) continue;
				// 骨架相同只说明"指令结构同形、字面量可以不同"——那是**结构**证据，不是身份证据。
				// "删掉 A2、再加一个同骨架的 A9"与"把 A2 的字面量改了"在骨架层完全同形，
				// 只有当三档证据之一成立时才能把这门婚事定下来（见 fallbackPairingAllowed）。
				if (!fallbackPairingAllowed(ctx, ni, oi)) continue;
				pair(ctx, ni, oi, "SKELETON");
				progressed = true;
				break;
			}
		}
		return progressed;
	}

	private static boolean skeletonEligibleNew(SyntheticInfo ni) {
		return !ni.matched && ni.renameable && ni.upDepth >= 0;
	}

	private static boolean skeletonEligibleOld(MatchContext ctx, SyntheticInfo oi) {
		return !oi.matched && !oi.ghost && oi.renameable
		       && oi.upDepth >= 0 && !ctx.isOldNameClaimed(oi);
	}

	/**
	 * 两侧都有明确且相同的 consumer（消费端指令特征）——同一调用点的最强证据。
	 *
	 * <p>{@code consumer} 形如 {@code CALL:Owner.btnSave(Ljava/util/function/Supplier;)V}，
	 * 描述"这个 lambda 被传给谁"。同一宿主方法里的 {@code btnSave(...)} 与 {@code btnDelete(...)}
	 * 因此可以区分；它和宿主方法名（组 key 的一部分）是两回事。</p>
	 */
	private static boolean hasSameExplicitConsumer(SyntheticInfo ni, SyntheticInfo oi) {
		return ni.consumer != null && !ni.consumer.isEmpty()
		       && oi.consumer != null && !oi.consumer.isEmpty()
		       && ni.consumer.equals(oi.consumer);
	}

	/**
	 * 判断两个合成方法的签名是否<b>逻辑等价</b>。
	 *
	 * <p>背景：同一段 lambda 体，在不同编译/变换轮次里可能以两种形态出现——</p>
	 * <ul>
	 *   <li><b>实例方法</b>：{@code private void lambda$build$19(ScrollPane, Floatc, Cell)}，
	 *       {@code this} 是隐式的接收者；</li>
	 *   <li><b>静态方法</b>：{@code private static void lambda$build$19(Tester, ScrollPane, Floatc, Cell)}，
	 *       {@code this} 变成显式的首参数（{@link AnnotationTransformer#forceStaticLambdas} 的产物）。</li>
	 * </ul>
	 *
	 * <p>两者描述的<b>是同一件事</b>。若按“{@code static} 位必须相同 + 描述符必须逐字符相同”
	 * 去比对，就会把同一个 lambda 判成“老方法消失、新方法新增”，进而在阶段二生成
	 * {@code lambda$build$21} 这类避障名，让 JVM 里仍持有老 {@code CallSite} 的 UI 监听器
	 * 抛出 {@link NoSuchMethodError}。</p>
	 *
	 * <p>因此这里把实例方法的隐式 {@code this} 前置成显式首参数后，再逐字符比对。注意比对
	 * 使用的是<b>带 owner 的完整参数类型</b>，所以只有当静态版本的首参数恰好就是
	 * {@code L<当前类>;}（即 {@code forceStaticLambdas} 的既定形态）时才判定等价，
	 * 不会把“首参数恰好是别的类”的错误签名误判为等价。</p>
	 *
	 * <p><b>边界（诚实说明）</b>：本方法只抹平 {@code static} 位与 {@code this} 形态的差异，
	 * 不抹平“捕获列表本身发生变化”导致的描述符差异（例如新版本少捕获了一个局部变量）。
	 * 那种情况需要真正的方法适配器（老签名 → 新签名的桥接转发），属于独立议题；
	 * 本方法至少保证“名字被保住”，不再退化成避障名。</p>
	 * @param owner 当前类的内部名（形如 {@code com/example/Foo}）
	 * @param a     参与比对的方法信息
	 * @param b     参与比对的方法信息
	 * @return 两者签名逻辑等价时返回 {@code true}
	 */
	private static boolean isSignatureCompatible(String owner, SyntheticInfo a, SyntheticInfo b) {
		boolean sa = a.isStatic();
		boolean sb = b.isStatic();
		if (sa == sb) {
			if (!a.desc.equals(b.desc)) return false;
			// 同签名但捕获顺序被换过：原地配对会让老实例按<b>旧顺序</b>喂新方法体，而新体按
			// <b>新顺序</b>解读 —— 静默错值。必须让它们"配不上"，由阶段二改名 + 捕获桥重排实参。
			return !isPermutedPair(a, b);
		}
		if (owner == null) return false;
		return normalizeStaticShape(owner, sa, a.desc).equals(normalizeStaticShape(owner, sb, b.desc));
	}

	/**
	 * 同签名的两个 lambda，捕获顺序是否被换过。
	 *
	 * <p>只认"身份集合相同、顺序不同"这一种形态：身份优先取源码变量名，取不到时退回宿主形参
	 * 槽号（同样要求全部来自形参）。变量整体改名时集合不同，那是"同一位置换了变量"，原地配对
	 * 仍然正确，不拦。</p>
	 */
	private static boolean isPermutedPair(SyntheticInfo a, SyntheticInfo b) {
		List<String> ia = captureIdentity(a), ib = captureIdentity(b);
		if (ia == null || ib == null || ia.size() != ib.size()) return false;
		if (ia.equals(ib)) return false;                        // 顺序一致
		List<String> sa = new ArrayList<>(ia), sb = new ArrayList<>(ib);
		Collections.sort(sa);
		Collections.sort(sb);
		return sa.equals(sb);                                   // 集合相同 + 顺序不同 ⇒ 纯换序
	}

	/** 捕获位的"身份"序列（名字优先，其次宿主形参槽号）；取不到返回 {@code null}。 */
	private static List<String> captureIdentity(SyntheticInfo info) {
		int first = info.isStatic() ? 0 : 1;
		if (info.captured == null || info.captured.size() <= first) return null;
		List<String> names = info.captureNames;
		if (names != null && names.size() == info.captured.size()) {
			List<String> byName = new ArrayList<>();
			boolean complete = true;
			for (int i = first; i < names.size(); i++) {
				String n = names.get(i);
				if (n == null) { complete = false; break; }
				byName.add(n);
			}
			if (complete) return byName;
		}
		if (info.captureSlots == null || info.captureSlots.size() != info.captured.size()) return null;
		if (info.hostParamEndSlot < 0) return null;
		List<String> bySlot = new ArrayList<>();
		for (int i = first; i < info.captureSlots.size(); i++) {
			int s = info.captureSlots.get(i);
			if (s >= info.hostParamEndSlot) return null;        // 局部槽不可比
			bySlot.add("#" + s);
		}
		return bySlot;
	}

	/**
	 * 把描述符归一化到“{@code this} 显式前置”的统一形态：
	 * 实例方法的 {@code (X)Y} → {@code (L<owner>;X)Y}，静态方法原样返回。
	 *
	 * <p>与 {@link AnnotationTransformer#forceStaticLambdas} 生成的描述符形态严格一致，
	 * 以便分组（{@link #groupByLogic}）与匹配（{@link #isSignatureCompatible}）使用同一个口径。</p>
	 */
	private static String normalizeStaticShape(String owner, boolean isStatic, String desc) {
		if (isStatic || owner == null || desc == null || desc.isEmpty() || desc.charAt(0) != '(') return desc;
		return "(L" + owner + ";" + desc.substring(1);
	}

	/**
	 * 从方法名中提取逻辑名称，用于分组匹配。
	 *
	 * <p>滑动去除字符串中所有“紧跟在 {@code $} 符号后面的纯数字”：</p>
	 * <pre>
	 * lambda$foo$1$bar$2         -> lambda$foo$$bar$
	 * build$lambda$26$lambda$25  -> build$lambda$$lambda$
	 * $deserializeLambda$        -> $deserializeLambda$
	 * access$100                 -> access$100（不归一化，见下）
	 * </pre>
	 *
	 * <p>{@code access$} 前缀直接返回原名：它是跨类引用，本对齐器对其采用
	 * {@code renameable=false} 的“保名不改名”策略。需要诚实说明的是：
	 * 保名只是不让情况变得更糟，<b>并不能真正解决跨类调用错位</b>
	 * —— javac 的 {@code access$NNN} 编号在两次编译间可能指向不同的成员。
	 * 这是本工具的<b>已知限制</b>；Java 11+ 的 nestmate 场景下该问题基本不存在，
	 * Kotlin/Scala 生成物请自行评估。</p>
	 */
	private static String extractLogicalName(String name) {
		if (name.startsWith("access$")) return name;

		StringBuilder sb    = new StringBuilder(name.length());
		char[]        chars = name.toCharArray();
		int           i     = 0;
		while (i < chars.length) {
			char c = chars[i];
			sb.append(c);
			if (c == '$') {
				int j = i + 1;
				while (j < chars.length && Character.isDigit(chars[j])) j++;
				if (j > i + 1) i = j - 1;
			}
			i++;
		}
		return sb.toString();
	}

	/**
	 * 按逻辑名称和描述符对合成方法进行分组。
	 *
	 * <p>分组键为 {@code compositeHash(logicalName, normalizedDesc)}，保证“嵌套 lambda 序号
	 * 整体位移”不会拆散同一逻辑组。</p>
	 *
	 * <p><b>为什么用归一化描述符</b>：分组是阶段一匹配的前置条件。若直接拿原始描述符做键，
	 * “老版本是静态方法（{@code this} 显式首参数）、新版本是实例方法（{@code this} 隐式）”
	 * 这一对<b>根本不会落进同一个组</b>，{@link #isSignatureCompatible} 再宽容也没有机会执行。
	 * 归一化后两者同键，阶段一即可识别为同一 lambda，避免生成 {@code lambda$build$21}
	 * 这类避障名。<b>注意</b>：分组是“放宽”，真正的取舍仍由
	 * {@link #isSignatureCompatible} 决定：它比对带 owner 的完整参数类型，因此不会因为
	 * 放宽分组而把不同逻辑的 lambda 凑成一对。</p>
	 *
	 * <p>分组键里同时含 {@code logicalName}，所以不同 lambda 体（不同逻辑名）天然隔离；
	 * 描述符里含完整参数类型，所以同名但参数类型不同的重载也不会互相污染。</p>
	 */
	private static void groupByLogic(
	 LongObjectMap<List<SyntheticInfo>> target, SyntheticInfo info, String owner) {
		String              normalizedDesc = normalizeStaticShape(owner, info.isStatic(), info.desc);
		long                key            = Utils.compositeHash(info.logicalName, normalizedDesc);
		List<SyntheticInfo> list           = target.get(key);
		if (list == null) {
			list = new ArrayList<>(8);
			target.put(key, list);
		}
		list.add(info);
	}
	//endregion

	//region 数据结构

	/**
	 * 合成方法的信息载体。
	 * <p>只在 {@link MatchContext} 生命周期内有效，{@code align} 结束后不再引用。</p>
	 */
	static class SyntheticInfo {
		/** 方法名。 */
		String  name;
		/** 方法描述符。 */
		String  desc;
		/** 逻辑名称（已归一化），用于分组。 */
		String  logicalName;
		/** 访问修饰符。 */
		int     access;
		/** 指纹哈希值。 */
		long    hash;
		/** 是否已被匹配。 */
		boolean matched;
		/**
		 * 当该方法在所有趟中均未找到任何匹配候选时，被标为 settled。
		 * 其父方法在 {@link #hasUnmatchedChild} 中不再被该子方法阻挡（跳过未匹配且 settled 的子）。
		 */
		boolean settled;
		/** 是否参与重命名（{@code access$} 系列为 false）。 */
		boolean renameable;
		/**
		 * 是否"只用于参与孤儿计算、不参与任何匹配"。
		 * <p>用于上一轮注入的<b>幽灵空壳</b>：它的名字是"死名字"，只能用来兜住老
		 * {@code CallSite}，绝不该再被当成某个新 lambda 的匹配目标。</p>
		 */
		boolean ghost;

		/**
		 * 该 lambda 在 indy 工厂描述符里的<b>捕获表</b>（按声明顺序的类型描述符，例如
		 * {@code ["Ljava/lang/String;", "I"]}）。
		 *
		 * <p>{@code null} 表示"没有任何 indy 调用点指向它"——上一轮注入的幽灵桩/桥、
		 * 或从未被遍历到的死代码。捕获桥以 {@code null} 为拒绝条件：看不到捕获表就无法
		 * 证明参数布局，只能按老路幽灵化。</p>
		 */
		List<String> captured;

		/** indy 工厂描述符的返回类型（功能接口的内部名）。 */
		String fiType;

		/**
		 * 每个捕获实参在<b>宿主方法</b>里的局部变量槽号，与 {@link #captured} 下标对齐。
		 *
		 * <p>javac 把捕获按<b>首次引用顺序</b>排列，实参就是 indy 前面那串 {@code xLOAD}；
		 * 回溯出来的槽号是"同一个变量"的直接证据，比"类型相同"硬得多。两侧宿主描述符
		 * 完全一致时槽号可直接比较，于是映射能从"按类型猜"升级为"按槽位证"，连
		 * "两个 int 里到底是哪一个"这种类型层面无解的歧义也能定下来。</p>
		 *
		 * <p>{@code null} 表示回溯失败（实参是临时值、或加载序列不规整），此时退回类型推断。</p>
		 */
		List<Integer> captureSlots;

		/**
		 * 捕获变量在<b>源码里的名字</b>，与 {@link #captured} 下标对齐；实例 lambda 的第 0 位是
		 * 接收者，固定为 {@code null}。
		 *
		 * <p>来自合成方法形参槽的 {@code LocalVariableTable}（见 {@link #readLambdaParamNames}）。
		 * 名字比槽号更硬：槽号会随宿主方法里增删前序局部变量而整体位移，名字不会。
		 * 只在编译带 {@code -g} 时才有，没有就退回槽号/类型。</p>
		 */
		List<String> captureNames;

		/**
		 * 宿主方法的形参槽结束位置（第一个局部变量的槽号）。
		 *
		 * <p>用来区分"这个捕获来自宿主形参"还是"来自宿主局部变量"：形参槽在宿主描述符不变时
		 * 是稳定的，局部变量槽不是——删掉一个前序局部变量，后面所有局部槽整体前移。
		 * 所以槽位证明只对形参成立（见 {@code mappingByHostSlots}）。</p>
		 */
		int hostParamEndSlot = -1;

		/**
		 * 创建这个 lambda 的宿主方法节点（{@code hostKey} 指向的那个）。
		 *
		 * <p>新增捕获的合成要回扫它：JLS §15.27.2 保证被捕获的局部变量是 effectively final，
		 * 所以在宿主方法里对该槽只有<b>唯一一处</b> {@code xSTORE}，从那一处往前看就是
		 * "这个值从哪来"。拿不到宿主节点就无法回溯，只能拒绝合成。</p>
		 */
		MethodNode hostMethod;

		/** {@code bsmArgs[0]}：SAM 的<b>擦除</b>签名。 */
		String samSig;

		/**
		 * {@code bsmArgs[2]}：SAM 的<b>实例化</b>签名。
		 *
		 * <p>重建 impl 描述符必须用它而不是 {@link #samSig}：泛型功能接口的 SAM 是擦除后的
		 * （{@code Consumer<String>} 的 samSig 是 {@code (Ljava/lang/Object;)V}），
		 * 只有实例化签名才和 impl 方法的形参类型对得上。</p>
		 */
		String instSig;

		/**
		 * 本方法是上一轮注入的<b>捕获桥</b>，不是真 lambda 体。
		 *
		 * <p>桥的键（旧 name+desc）在新版本里天然没有对应物。若下一轮认不出它，它会被
		 * 当成"又被删了一次"重新幽灵化，第一轮的成果当场作废。</p>
		 */
		boolean bridge;

		/**
		 * 幽灵桩"生前"的方法体指纹（0 = 未知）。
		 *
		 * <p>桩体会被换成 {@code onOrphanInvoked} 空壳，它自己的 {@link #hash} 就变成了空壳的指纹，
		 * 再也认不出"原封不动加回来"的同一个 lambda。所以注入桩体时把生前指纹写进桩体的
		 * {@code LDC <long>; POP2} 标记里，下一轮扫描时读回来，供 {@code GHOST_REVIVE} 认领。</p>
		 */
		long ghostOriginalHash;

		/**
		 * 忽略字面量常量的"指令骨架"指纹。
		 *
		 * <p>普通指纹含 {@code LDC} 常量，所以"只改了一个字符的常量"就会让指纹全失效，
		 * 方法掉进 Pass A/B 后只剩 (深度, shape) 可用——而这两项对同一方法里的多个 lambda
		 * 往往完全相同。骨架指纹保留操作码序列、调用目标、类型，只把字面量换成"类型标签"，
		 * 于是 {@code 50 行循环 + "v2"} 与 {@code 2 行 + "v2"} 依然可区分。</p>
		 */
		long skeletonHash;

		/**
		 * 方法体<b>逐条等价</b>指纹（常量池索引无关、局部槽位归一、常量可折叠）。
		 *
		 * <p>兜底趟（骨架 / Pass A / Pass B）在把旧名字交给一个候选之前，必须能<b>证明</b>
		 * 两侧是同一个逻辑体，否则就是"名字换了主人"——老 CallSite 静默执行别人的方法体。
		 * 本指纹提供这份证明（见 {@link #bodyFingerprint}）：等值 ⇒ 可以配对；
		 * 不等值 ⇒ 只有"名字 + 宿主形态"这类独立身份证据才能兜住，否则 WARN + 拒绝配对。</p>
		 *
		 * <p>{@code 0} 表示未知（方法体缺失）。</p>
		 */
		long bodyHash;

		/**
		 * 该方法体里<b>直接引用</b>的本类 lambda 系方法名集合（内层 lambda）。
		 *
		 * <p>用于把匹配排成"由下往上"：父 lambda 必须等子 lambda 落定后再配对，
		 * 否则子被改名会让父的方法体指向别处（详见 {@link #hasUnmatchedChild} 的说明）。</p>
		 */
		List<String> children = Collections.emptyList();

		/** {@link #children} 对应的新类 SyntheticInfo（下标对齐；仅新类侧填充）。 */
		List<SyntheticInfo> childInfos = Collections.emptyList();

		/** 与哪个旧方法配成了一对（未匹配时为 null）。父层据此过滤候选。 */
		SyntheticInfo matchedWith;

		/**
		 * 递归语义指纹：{@link #hash} 再逐层折入每个子 lambda 的语义指纹。
		 *
		 * <p>{@link #hash}（票据指纹）会把子 lambda 的名字屏蔽成 {@code #SYNTHETIC_METHOD#}，
		 * 因此"整棵子树只差最深处叶子"的两个 lambda 会得到相同的 {@code hash} ——
		 * 三层嵌套正是如此：两个中层同 hash、两个外层同 hash，据此**无法区分谁是谁**
		 * （否决与正向选择同时失效）。实测见 scratch/hstest/deep/。</p>
		 *
		 * <p>把子的语义指纹折进来后，差异会沿树<b>向上传播</b>：叶子不同 ⇒ 中层语义指纹不同
		 * ⇒ 外层语义指纹也不同。匹配因此不需要知道深度、也不需要单独的正向选择。</p>
		 *
		 * <p>{@code 0} 表示尚未定稿（子还没算完）。叶子没有子，语义指纹就等于票据指纹。</p>
		 */
		long semanticHash;

		/**
		 * 子树**形状**：只由 indy 引用拓扑决定，与方法体内容无关。
		 *
		 * <pre>
		 *   shape(m) = "(" + 排序后的 shape(子) 串接 + ")"
		 *   叶子 = "()"      中层 = "(())"      外层 = "((()))"
		 * </pre>
		 *
		 * <p>用途（实测问题）：{@link #sameNestingLevel} 早先只比"有无子 + 子数量"，
		 * 于是<b>外层与中层结构等价</b>（都是 1 个子），互换时无人拦截 —— save3 的
		 * 两轮序列里 `$3`（旧外层）与 `$4`（旧中层）真的互换了，持有 `$3` 的老回调
		 * 从此执行中层的逻辑，延迟与嵌套层数都变了却不报错。</p>
		 *
		 * <p>形状与内容无关，所以"编辑叶子方法体"不会改变它（不会重现上一轮那个
		 * "祖先因后代被编辑而失去证据"的问题），但它能区分外层与中层。</p>
		 *
		 * <p>{@code null} 表示<b>尚未定稿</b>。</p>
		 *
		 * <p><b>为什么用 null 而不是 "()"</b>：{@code "()"} 本身就是叶子的合法形状。
		 * 若用它兼作"未算完"的哨兵，父读到未定稿的子时无法与"真叶子"区分，会算出一个
		 * <b>看似合法实则错误</b>的值 —— 这正是 save3 两轮互换那个 bug 的类别：
		 * 新旧两侧同错，任何等值校验都说不出话。用 null 之后，"未算完"与"叶子"永远可分。</p>
		 */
		String shape;

		/**
		 * 上行深度：该 lambda 被多少层 lambda 嵌套引用。
		 *
		 * <p>{@code build()} 里直接写的 {@code run(() -> x)} 深度为 <b>0</b>；
		 * 若它又被另一层 lambda 包着，则深度为 1，依此类推。</p>
		 *
		 * <p><b>哨兵必须用 -1（未定稿），不能用 0</b> —— 0 是合法值，用它兼作哨兵会重演
		 * {@link #shape} 那个坑：父读到未定稿的值却当成合法值用了。</p>
		 *
		 * <p>用途：两个都没有指纹证据的新叶子争抢同一个旧叶子名时（实测见
		 * {@code scratch/hstest/UpDepthTest}），"被谁引用"是唯一可用的结构信号 ——
		 * 一个由 {@code build()} 直接引用（深度 0），另一个被嵌套链引用（深度 2）。
		 * 它是纯结构信息，编辑方法体不会改变它，与 {@link #shape} 对称。</p>
		 */
		int upDepth = -1;

		/** 指纹计算中是否对匿名类进行了归一化屏蔽（#ANON_relId#）。 */
		boolean anonMasked = false;

		/** 所在宿主方法的调用序列键（"M:name+desc" 或 "L:hash"）。 */
		String hostKey;
		/** 在该宿主方法中的出现序号（0-indexed）。 */
		int    hostOrdinal = -1;
		/** 在 class 文件方法表中的下标，用于保持编译器位置序。 */
		int    methodIndex = -1;
		/** 实例化该 Lambda 后的消费指令（如 "CALL:pkg/Cls.method(Ljava/lang/Runnable;)V"）。 */
		String consumer;
		/** 从根方法出发的 BFS 发现序号（源码调用序）。 */
		int    callOrder = -1;

		SyntheticInfo(String name, String desc, int access, long hash, String logicalName, boolean renameable) {
			this.name = name;
			this.desc = desc;
			this.access = access;
			this.hash = hash;
			this.logicalName = logicalName;
			this.renameable = renameable;
			this.matched = false;
			this.settled = false;
			this.ghost = false;
		}

		boolean isStatic() {
			return (access & Opcodes.ACC_STATIC) != 0;
		}

		/**
		 * 捕获表里第一个<b>非接收者</b>捕获的下标。
		 *
		 * <p>实例 lambda 的捕获表第 0 项是接收者（工厂描述符里是 {@code LC;}，impl 描述符里
		 * 没有它）；静态 lambda 没有这一项。所有"按捕获表搬实参"的逻辑都必须用这个下标
		 * 而不是硬编码 0/1——{@code forceStaticLambdas} 会把实例方法改写成静态方法并把接收者
		 * 变成<b>显式首形参</b>，此时它既是静态方法、又仍有接收者槽。</p>
		 */
		int firstCapture() {
			return isStatic() ? 0 : 1;
		}
	}
	//endregion
}