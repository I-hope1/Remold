package remold;

import remold.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 *         <li>若由普通业务代码发起（未受熔断保护）：静默降级返回类型默认值（0 / null / false），绝不引发程序崩溃。</li>
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
 * <p><b>已知限制：改变嵌套拓扑的编辑会丢失身份。</b>
 * {@link #sameNestingLevel} 在 Step 1/2 全程是<b>硬否决</b>：形状不等的候选永远不配对。
 * 因此<b>任何改变嵌套拓扑的编辑</b>——在现有 lambda 体内新增或删除一层内层 lambda——
 * 都会让该 lambda 的 shape 变化，从而失去身份：旧名被幽灵化、新方法去阶段二拿新名。
 * 这是"安全优先于召回"的自觉取舍（形状混淆会让父子错配、语义静默对调，代价更大），
 * 但这类编辑在日常开发中很常见，因此明确记录在此。</p>
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

	public static boolean isMutationActive(String mutationKey) {
		String m = ACTIVE_MUTATION;
		return m != null && m.equalsIgnoreCase(mutationKey);
	}

	/** 测试用显式事件记录条目（用于按方法名精确归因，避免同轮搭便车误判）。 */
	public static final class AlignmentEvent {
		public final String kind;       // "POS_MISMATCH", "CONSUMER_MISMATCH", "ANON_COLLISION", "HOMOMORPHIC_PRUNING", "GHOST"
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
			       + (prunedPairs > 0 ? ", pruned=" + prunedPairs : "") + "}";
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

			public AlignmentResult build() { return new AlignmentResult(this); }
		}

		@Override
		public String toString() {
			return "AlignmentResult{" + alignedClass
			       + ", old=" + oldLen + "B/" + Long.toHexString(oldHash)
			       + ", new=" + newLen + "B/" + Long.toHexString(newHash)
			       + ", bridges=" + bridgeCount
			       + ", stats=" + stats + "}";
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
	 */
	public static volatile AlignmentResult LAST_RESULT;

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
	private static int[] groupOrder(LongObjectMap<?> groups) {
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
		 *   <li>若由普通业务代码发起（未受熔断保护）：经 {@link RemoldAgent#warn} 打印警告并返回默认值（0 / null / false），
		 *       <b>不抛出异常</b>（指幽灵自身不抛；返回的 null / 0 在下游仍可能触发 NPE，需调用方自行判空）。</li>
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

	private static volatile OrphanPolicy orphanPolicy = OrphanPolicy.SMART_ADAPTIVE;

	public static void setOrphanPolicy(OrphanPolicy policy) {
		orphanPolicy = Objects.requireNonNull(policy);
	}

	public static OrphanPolicy getOrphanPolicy() {
		return orphanPolicy;
	}

	/**
	 * 清空已记录的孤儿 Lambda 日志去重缓存。
	 */
	public static void clearLoggedOrphans() {
		LOGGED_ORPHANS.clear();
		LOGGED_FORWARDS.clear();
	}

	public static int getLoggedOrphanCount() {
		return LOGGED_ORPHANS.size();
	}

	public static int getLoggedForwardCount() {
		return LOGGED_FORWARDS.size();
	}

	public static boolean isOrphanLogged(String location) {
		return LOGGED_ORPHANS.contains(location);
	}

	public static boolean isForwardLogged(String location) {
		return LOGGED_FORWARDS.contains(location);
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
		final Map<String, BridgeSpec> bridges = new HashMap<>(16);

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
			oldGroups.clear();
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
	public static byte[] align(byte[] oldBytes, byte[] newBytes,
	                           java.util.function.Function<String, byte[]> oldBytecodeResolver,
	                           java.util.function.Function<String, byte[]> newBytecodeResolver,
	                           java.util.Set<String> inventedOldNames) {
		int  oldLen  = oldBytes == null ? -1 : oldBytes.length;
		int  newLen  = newBytes == null ? -1 : newBytes.length;
		long oldHash = oldBytes == null ? 0 : java.util.Arrays.hashCode(oldBytes);
		long newHash = newBytes == null ? 0 : java.util.Arrays.hashCode(newBytes);
		// 先发布空结果：下面有"旧字节为空直接返回"的早退路径，不发布会让调用方读到上一轮的值，
		// 把 A 类的自造名落账到 B 类头上。
		LAST_RESULT = AlignmentResult.empty(oldLen, newLen, oldHash, newHash);
		if (oldBytes == null || oldBytes.length == 0) return newBytes;

		MatchContext ctx = CONTEXT.get();
		try {
			ctx.reset();
			ctx.inventedOldNames = inventedOldNames != null ? inventedOldNames : java.util.Set.of();
			ctx.newlyInvented.clear();
			// 只读一遍取形参名：名字是"同一个变量"最硬的证据（槽号会随前序局部变量增删而位移）。
			// 未带 -g 时两张表为空，后续判据自动退回槽号/类型。
			ctx.oldParamNames = readLambdaParamNames(oldBytes);
			ctx.newParamNames = readLambdaParamNames(newBytes);

			// 一次读取即拿到 ClassNode：oldCn 会被 resurrectOrphanedLambdas 复用，
			// newCn 会在“无重命名”路径下直接提供 presentKeys。
			ClassNode oldCn = scan(oldBytes, ctx, true, oldBytecodeResolver);
			ClassNode newCn = scan(newBytes, ctx, false, newBytecodeResolver);
			if (!Objects.equals(oldCn.name, newCn.name)) {
				throw new IllegalArgumentException(
				 "New class name does not match old class name: " + newCn.name + " != " + oldCn.name);
			}
			ctx.currentClass = oldCn.name;

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
					RemoldAgent.warn("[LambdaAligner] settled 循环达到上限(64)仍未收敛 " + ctx.currentClass);
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
				alignedCn = applyTransform(newBytes, ctx);
				alignedBytes = writeClass(alignedCn);
			}

			// 从 alignedCn 直接收集 presentKeys，避免 resurrectOrphanedLambdas 再读一遍
			Set<String> presentKeys = collectMethodKeys(alignedCn);
			Map<String, Integer> newMethodRefs = countHandleTargets(alignedCn);
			Map<String, Integer> oldMethodRefs = countHandleTargets(oldCn);

			// 【阶段三】捕获桥：删掉捕获变量的 lambda，用"老签名 → 新方法"的适配器兜住老实例。
			//
			// 必须放在这里：阶段二可能给未配对的新方法分配避障名，桥要指向的是**最终**名字；
			// 同时 presentKeys 已经就绪，可以据此判断"哪些老键本来会变成幽灵"。
			stepCaptureBridge(oldCn, alignedCn, ctx, presentKeys);

			// 后置廉价校验：确保本地 indy 句柄引用的目标方法实际存在
			validateIndyTargets(alignedCn, presentKeys);

			AlignmentStats stats = new AlignmentStats(ctx.step1Pairs, ctx.passAPairs, ctx.passBPairs,
			                                         ctx.step1AnchorPairs, ctx.hashMismatchPairs,
			                                         ctx.consumerMismatchPairs,
			                                         ctx.consumerMismatchWarnPairs, ctx.consumerMismatchDebugPairs,
			                                         ctx.prunedPairs);

			Map<String, String> methodMapping = new HashMap<>();
			for (int idx = newGroups.nextEntry(-1); idx != -1; idx = newGroups.nextEntry(idx)) {
				List<SyntheticInfo> g = newGroups.valueAt(idx);
				if (g == null) continue;
				for (SyntheticInfo ni : g) {
					if (ni.matchedWith != null) {
						String oldKey = ni.matchedWith.name + ":" + ni.matchedWith.desc;
						String newKey = ni.name + ":" + ni.desc;
						methodMapping.put(oldKey, newKey);
					}
				}
			}

			// 传入 oldCn（已解析过一次）—— 避免 resurrectOrphanedLambdas 二次读 oldBytes
			byte[] result = resurrectOrphanedLambdas(oldCn, alignedBytes, presentKeys, oldMethodRefs, newMethodRefs, ctx);
			// 自造名必须从**最终产物**统计：复活阶段会往类里加回幽灵方法，
			// 只在 alignedCn 上扫会漏掉它们（幽灵仍占着那个名字，下一轮照样会被误当 javac 原名）。
			Set<String> invented = collectInventedNames(result, ctx);

			// 唯一的发布点：一次 volatile 写，读者不会读到跨轮拼接。
			LAST_RESULT = AlignmentResult.builder()
			                             .stats(stats)
			                             .inventedNames(invented)
			                             .input(oldLen, newLen, oldHash, newHash)
			                             .anonHashes(new HashMap<>(ctx.oldAnonHashes),
			                                         new HashMap<>(ctx.newAnonHashes))
			                             .methodMapping(methodMapping)
			                             .alignedClass(oldCn.name)
			                             .bridgeCount(ctx.bridges.size())
			                             .build();
			return result;
		} catch (Exception | LinkageError | StackOverflowError e) {
			// 失败也发布：否则调用方会读到上一轮的旧快照，把两轮结果拼在一起。
			LAST_RESULT = AlignmentResult.empty(oldLen, newLen, oldHash, newHash);
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
				ctx.usedOldNames.remove(oi.name);
				totalUndone++;
				RemoldAgent.warn("[LambdaAligner] 引用一致性被违反，撤销配对：" + ni.name
				                  + " 的子 " + ni.children + " 与 " + oi.name + " 的子 " + oi.children
				                  + " 不一致（静默错位已降级为熔断）");
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
					          + "(childMatched=false, 旧侧候选数=" + lc + ")"
					          + " parentHash=" + ni.hash + " parentSem=" + ni.semanticHash
					          + " parentCand=" + cand);
				} else {
					dbg(() -> "NO-CANDIDATE " + ni.name
					          + " hash=" + ni.hash + " sem=" + ni.semanticHash
					          + " 旧侧候选数=" + cand);
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
				if (!oi.matched && oi.renameable && !oi.ghost && !ctx.usedOldNames.contains(oi.name)) {
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

	/** 配对并登记：把 {@code ni} 改名为 {@code oi} 的名字。 */
	private static void pair(MatchContext ctx, SyntheticInfo ni, SyntheticInfo oi, String pass) {
		dbg(() -> "[" + pass + "] PAIR " + ni.name + "(shape=" + ni.shape + " depth=" + ni.upDepth + ")"
		          + " -> " + oi.name + "(shape=" + oi.shape + " depth=" + oi.upDepth + ")");
		recordRename(ctx, ni, oi.name);
		ni.matchedWith = oi;
		ni.matched = true;
		oi.matched = true;
		ctx.usedOldNames.add(oi.name);
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
				dbg(() -> "消费者特征变更（同名唯一良性重构）" + ctx.currentClass
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
				if (ctx.usedOldNames.contains(oi.name)) continue;
				if (oi.upDepth != ni.upDepth) continue;   // 深度必须相等
				if (!sameNestingLevel(ni, oi)) continue;  // 形状必须相等
				if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
				if (!calleesPairTo(ni, oi)) continue;     // 必须满足结构证据（后代修改时的关键防护）
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
				warnPositionalMismatch(ctx.currentClass, bestOld, ni.name, ni.desc);
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
				if (ctx.usedOldNames.contains(oi.name)) continue;
				if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
				if (!sameNestingLevel(ni, oi)) continue;
				if (!calleesPairTo(ni, oi)) continue;
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
					if (ctx.usedOldNames.contains(oi.name)) continue;
					if (!isSignatureCompatible(ctx.currentClass, oi, ni)) continue;
					if (!sameNestingLevel(ni, oi)) continue;
					if (!calleesPairTo(ni, oi)) continue;
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
				warnPositionalMismatch(ctx.currentClass, bestOld, ni.name, ni.desc);
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
			if (ctx.usedOldNames.contains(oi.name)) continue;
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
				if (ctx.usedOldNames.contains(oi.name)) continue;   // 旧名只能被占一次
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
			if (ctx.usedOldNames.contains(oi.name)) continue;   // 旧名只能被占一次
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
		clearLoggedOrphans();
	}

	/** 测试辅助：获取当前已触发的去重警告总数。 */
	public static int getWarningCount() {
		return WARNED_REALIGNMENTS.size();
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
	private static void warnPositionalMismatch(String owner, SyntheticInfo oi, String newName, String newDesc) {
		String key = owner + "#" + newName + "<-" + oi.name;
		if (!WARNED_REALIGNMENTS.add(key)) return;

		TEST_EVENTS.add(new AlignmentEvent("POS_MISMATCH", oi.name, newName, owner + "#" + newName + newDesc + "<-" + oi.name + oi.desc));
		RemoldAgent.warn("[LambdaAligner] 顺序回退配对但方法体不一致 " + owner
		                  + "：旧 " + oi.name + oi.desc + " <- 新 " + newName + newDesc
		                  + "。同一逻辑组内存在多个同形 lambda 时，插入/删除会使其序号整体位移，"
		                  + "此时旧名字会被交给一个语义不同的方法：老 CallSite **不会抛异常**，"
		                  + "而是安静地执行新方法体（实测确认：deep2 变体乙里旧 $2 承载了 doB2 的语义）。"
		                  + "建议把每个 lambda 放进独立的私有方法，让身份由结构而非位置决定。");
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
		RemoldAgent.warn("[LambdaAligner] 消费者特征异化配对 " + owner
		                  + "：旧 " + oi.name + oi.desc + " [" + oldConsumer + "] <- 新 "
		                  + newName + newDesc + " [" + newConsumer + "]。"
		                  + "配对双方调用者特征不一致，老 CallSite 可能绑至不同消费者上下文。");
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
		RemoldAgent.warn("[LambdaAligner] 匿名类归一化导致同组同指纹配对存在歧义 " + owner
		                  + "：旧 " + oi.name + oi.desc + " <- 新 " + newName + newDesc
		                  + "。同组内存在多个同形且包含匿名类的 lambda（同指纹），若发生新增、删除或重排可能导致方法语义错位。"
		                  + "建议将匿名类提取为具名内部类或独立方法以确保语义稳定。");
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
					RemoldAgent.warn("[LambdaAligner] 宿主序列存在多个同构 Lambda 候选且发生缩减 " + ctx.currentClass
					                  + " (宿主: " + hostKey + ")：存量 CallSite 可能因同构裁剪被幽灵化。建议将关键业务回调提取为独立方法。");
				}
				break;
			}
		}
	}
	//endregion

	//region 字节码转换 + 扫描

	/**
	 * 应用转换规则到字节码，返回 remapped 后的 {@link ClassNode}。
	 *
	 * <p>为什么返回 {@code ClassNode} 而不是 {@code byte[]}：让调用方在写出之前
	 * 有机会直接从同一份节点上收集 {@code name + desc} 集合（供
	 * {@link #resurrectOrphanedLambdas} 判断孤儿），避免重复解析 newBytes。</p>
	 *
	 * <p>本方法使用 {@code ClassReader(bytes).accept(cn, 0)} 完整读取，
	 * 因为要写出合法的 Class 文件必须保留 StackMapTable 帧数据。</p>
	 *
	 * <p>整体流程：</p>
	 * <ol>
	 *   <li>读入 {@link ClassNode}。</li>
	 *   <li>通过 {@link ClassRemapper} 应用方法名与字符串常量的重命名。</li>
	 *   <li>对 {@code $deserializeLambda$} 做 {@code lookupswitch} 的 key 后处理。</li>
	 *   <li>返回 remapped 后的节点，由调用方写出。</li>
	 * </ol>
	 */
	private static ClassNode applyTransform(byte[] bytes, MatchContext ctx) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);

		ClassNode remapped = new ClassNode();
		Remapper remapper = new Remapper(Opcodes.ASM9) {
			@Override
			public String mapMethodName(String owner, String name, String desc) {
				if (owner.equals(ctx.currentClass)) {
					String nn = ctx.renameMap.get(name + desc);
					if (nn != null) return nn;
				}
				return name;
			}

			@Override
			public Object mapValue(Object value) {
				if (value instanceof String s && MethodFingerprinter.isSyntheticName(s)) {
					// 同名歧义时不动字符串常量，避免误改
					if (!ctx.ambiguousNames.contains(s)) {
						String nn = ctx.renameBySimpleName.get(s);
						if (nn != null) return nn;
					}
				}
				return super.mapValue(value);
			}
		};
		cn.accept(new ClassRemapper(remapped, remapper));

		// 后处理：$deserializeLambda$ 里的 lookupswitch hash key 必须跟着字符串常量一起更新
		for (MethodNode mn : remapped.methods) {
			if ("$deserializeLambda$".equals(mn.name)) {
				fixDeserializeSwitch(mn);
			}
		}
		return remapped;
	}

	/** 把 {@link ClassNode} 序列化为字节码。 */
	private static byte[] writeClass(ClassNode cn) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/** 收集一个类里所有方法的 {@code name + desc}，并校验方法键唯一性（防重复定义引发 ClassFormatError）。 */
	private static Set<String> collectMethodKeys(ClassNode cn) {
		Set<String> keys = new HashSet<>(cn.methods.size() * 2);
		for (MethodNode mn : cn.methods) {
			if (!keys.add(mn.name + mn.desc)) {
				throw new IllegalStateException("Duplicate method after align: " + mn.name + mn.desc);
			}
		}
		return keys;
	}

	/** 后置廉价结构校验：确保类内所有指向本类的 invokedynamic 目标方法实际存在。 */
	private static void validateIndyTargets(ClassNode cn, Set<String> presentKeys) {
		for (MethodNode mn : cn.methods) {
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof InvokeDynamicInsnNode indy) {
					if (indy.bsmArgs != null && indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle h) {
						if (cn.name.equals(h.getOwner()) && MethodFingerprinter.isSyntheticName(h.getName())) {
							String key = h.getName() + h.getDesc();
							if (!presentKeys.contains(key)) {
								throw new IllegalStateException("Missing indy target method: " + key + " in " + cn.name);
							}
						}
					}
				}
			}
		}
	}

	/**
	 * 重建 {@code $deserializeLambda$} 中 {@code String.hashCode()} 分派的
	 * {@code lookupswitch} 键值。
	 *
	 * <p>javac 生成的模式：</p>
	 * <pre>
	 *   s.hashCode() -> LOOKUPSWITCH
	 *     Label_1: LDC "lambda$foo$1"; s.equals(...); ...
	 *     Label_2: LDC "lambda$foo$2"; s.equals(...); ...
	 * </pre>
	 *
	 * <p>重命名器会改写 LDC 字符串，但 int 型 key 是常量，不会被自动更新，
	 * 因此必须重新计算并按升序重排。</p>
	 *
	 * <p><b>容错策略</b>：某个 case 块内找不到“符合合成名特征的 implMethodName
	 * 字面量”时，<b>沿用该 case 的原 key</b>，而不是放弃整个 switch 修复。
	 * 这保证了“同时包含方法引用（如 {@code "trim"}）和 lambda 的类”也能被
	 * 正确对齐——方法引用名不会被 Remapper 改写，其原 key 天然正确。</p>
	 *
	 * <p>重算后若 key 之间发生碰撞，才放弃整个 switch 的改写，并打一条警告。
	 * 不抛异常的原因：与其让整次热更失败，不如保留原 switch（对没被改名的
	 * lambda 仍然可用）。</p>
	 */
	private static void fixDeserializeSwitch(MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions.toArray()) {
			if (!(n instanceof LookupSwitchInsnNode sw)) continue;
			if (!isStringHashSwitch(sw)) continue;

			// 边界：本 switch 的所有 case label 以及 default label
			Set<LabelNode> boundaries = new HashSet<>(sw.labels);
			if (sw.dflt != null) boundaries.add(sw.dflt);

			int   size    = sw.labels.size();
			int[] newKeys = new int[size];
			for (int i = 0; i < size; i++) {
				String s = uniqueStringLdcInBlock(sw.labels.get(i), boundaries);
				if (s != null) {
					newKeys[i] = s.hashCode();
				} else {
					// 块内没有可识别的合成方法名 —— 沿用原 key。
					// 常见于：方法引用（"trim" / "doWork"）或描述符（"()V"）之类
					// 不会被 Remapper 改写的字符串，其 hash 保持不变，原 key 天然有效。
					newKeys[i] = sw.keys.get(i);
				}
			}

			// 碰撞检测：重算后的 key 不允许重复
			Set<Integer> seen = new HashSet<>();
			boolean      ok   = true;
			for (int k : newKeys) {
				if (!seen.add(k)) {
					ok = false;
					break;
				}
			}
			if (!ok) {
				warnSkipSwitch(mn, "重算后出现 hashCode 碰撞");
				continue;
			}

			// 按新 key 升序重排 labels（lookupswitch 的 key 必须升序）
			Integer[] order = new Integer[size];
			for (int i = 0; i < size; i++) order[i] = i;
			final int[] nk = newKeys;
			Arrays.sort(order, Comparator.comparingInt(i -> nk[i]));

			List<LabelNode> newLabels  = new ArrayList<>(size);
			List<Integer>   newKeyList = new ArrayList<>(size);
			for (int i : order) {
				newLabels.add(sw.labels.get(i));
				newKeyList.add(newKeys[i]);
			}
			sw.labels = newLabels;
			sw.keys = newKeyList;
		}
	}

	/**
	 * 判断一个 {@link LookupSwitchInsnNode} 是否是 {@code String.hashCode()} 分派。
	 * <p>启发式规则：其前一条有效指令为 {@code java/lang/String.hashCode()I}。</p>
	 */
	private static boolean isStringHashSwitch(LookupSwitchInsnNode sw) {
		AbstractInsnNode p = sw.getPrevious();
		while (p != null && (p.getOpcode() < 0 || p.getOpcode() == Opcodes.NOP)) {
			p = p.getPrevious();
		}
		if (!(p instanceof MethodInsnNode mi)) return false;
		return "java/lang/String".equals(mi.owner)
		       && "hashCode".equals(mi.name)
		       && "()I".equals(mi.desc);
	}

	/**
	 * 从 case 块中提取“implMethodName 比较用的”字符串常量。
	 *
	 * <p><b>定位规则</b>：扫描块内所有 {@code java/lang/String.equals} 调用，
	 * 只看与它关联的 LDC 参数（见下），且该字符串必须满足
	 * {@link MethodFingerprinter#isSyntheticName}。这会过滤掉：</p>
	 * <ul>
	 *   <li>ECJ 单 switch 布局下 {@code getFunctionalInterfaceClass} /
	 *       {@code getImplMethodSignature} 比较用的 {@code "com/foo/Bar"}、
	 *       {@code "()V"} 等字符串；</li>
	 *   <li>方法引用名（如 {@code "trim"}）—— 它们不会被 Remapper 改写，
	 *       原 key 天然正确，无需重算。</li>
	 * </ul>
	 *
	 * <p><b>两种字节码形态</b>都要覆盖：</p>
	 * <ol>
	 *   <li><b>标准形式</b>{@code s.equals("const")}：栈序 {@code [ALOAD, LDC]}，
	 *       则 equals 的直接前驱就是 LDC。</li>
	 *   <li><b>反向形式</b>{@code "const".equals(s)}：栈序 {@code [LDC, ALOAD]}，
	 *       则 equals 的前驱是 ALOAD，需要再往前一步才是 LDC。
	 *       某些字节码优化器或非标准编译产出会出现这种形态。</li>
	 * </ol>
	 *
	 * <p>注意 ASM 在读取时会把 {@code aload_0}~{@code aload_3} 这类无操作数形式
	 * 归一化为 {@code visitVarInsn(ALOAD, n)}，因此只需判断
	 * {@code VarInsnNode} 且 {@code getOpcode() == Opcodes.ALOAD} 即可覆盖全部
	 * 变量加载变体。</p>
	 *
	 * <p>块内若出现两个 <b>不同</b> 的合成名字符串（hash 碰撞导致的合并 case），
	 * 返回 {@code null} 由调用方沿用原 key；只出现一个合成名时返回它。</p>
	 *
	 * <p><b>终止条件</b>：</p>
	 * <ol>
	 *   <li>遇到 {@code boundaries} 中任一标签（本 switch 的其它 case 或 default）。</li>
	 *   <li>遇到无条件跳转或返回指令 —— 当前 case 逻辑已终结，后续指令属于其它
	 *       基本块，不应计入。若缺此截断，“最后一个 case”会一路穿透到
	 *       {@code $deserializeLambda$} 尾部的 LDC，使统计结果错误。</li>
	 *   <li>指令流自然结束。</li>
	 * </ol>
	 */
	private static String uniqueStringLdcInBlock(LabelNode start, Set<LabelNode> boundaries) {
		String found = null;
		for (AbstractInsnNode n = start.getNext(); n != null; n = n.getNext()) {
			if (n instanceof LabelNode l && boundaries.contains(l)) break;

			if (n instanceof MethodInsnNode mi
			    && "java/lang/String".equals(mi.owner)
			    && "equals".equals(mi.name)
			    && "(Ljava/lang/Object;)Z".equals(mi.desc)) {

				AbstractInsnNode prev = previousRealInsn(mi);

				// 反向形式 "const".equals(s)：前一条是 ALOAD，再往前才是 LDC。
				// 标准形式 s.equals("const")：前一条就是 LDC，不做这一跳。
				// ASM 会把 aload_0~aload_3 归一化为 VarInsnNode(ALOAD, n)，
				// 所以只需判断 opcode == ALOAD 就能覆盖全部变体。
				if (prev instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD) {
					prev = previousRealInsn(prev);
				}

				if (prev instanceof LdcInsnNode ldc
				    && ldc.cst instanceof String s
				    && MethodFingerprinter.isSyntheticName(s)) {
					if (found == null) {
						found = s;
					} else if (!found.equals(s)) {
						// 块内出现两个不同的合成名（hash 碰撞合并 case），
						// 保守返回 null，由调用方沿用原 key。
						return null;
					}
				}
			}

			// 遇到无条件跳转或返回指令：当前 case 逻辑已终结，立即截断
			int op = n.getOpcode();
			if (op == Opcodes.GOTO
			    || op == Opcodes.RETURN
			    || op == Opcodes.IRETURN || op == Opcodes.LRETURN
			    || op == Opcodes.FRETURN || op == Opcodes.DRETURN
			    || op == Opcodes.ARETURN
			    || op == Opcodes.ATHROW) {
				break;
			}
		}
		return found;
	}

	/**
	 * 跳过 {@link FrameNode} / {@link LabelNode} / {@link LineNumberNode}
	 * 以及插桩工具插入的 {@code NOP}，返回前一条真正的指令节点；
	 * 没有则返回 {@code null}。
	 *
	 * <p>与 {@link #isStringHashSwitch} 内部的过滤保持对称：都同时跳过
	 * “伪节点（opcode &lt; 0）”和 {@code NOP}。</p>
	 */
	private static AbstractInsnNode previousRealInsn(AbstractInsnNode n) {
		AbstractInsnNode p = n.getPrevious();
		while (p != null && (p.getOpcode() < 0 || p.getOpcode() == Opcodes.NOP)) {
			p = p.getPrevious();
		}
		return p;
	}

	/** 放弃修复时留痕，避免“静默失效”。 */
	private static void warnSkipSwitch(MethodNode mn, String reason) {
		RemoldAgent.info("[LambdaAligner] 跳过 $deserializeLambda$ 的 switch 修复（"
		                  + mn.name + mn.desc + "）：" + reason);
	}

	/**
	 * 扫描字节码并收集合成方法信息，返回 {@link ClassNode}。
	 *
	 * <p><b>无条件</b>把所有方法（含构造函数、静态块、桥接、普通业务方法、
	 * 合成方法）的 {@code name}/{@code name+desc} 分别登记到
	 * {@link MatchContext#existingNewNames} / {@link MatchContext#oldNameDescSet}，
	 * 作为 fresh name 的避障集，避免生成的新名与类中已有的普通方法签名碰撞
	 * （会导致 {@code ClassFormatError: Duplicate method name&signature}）。</p>
	 *
	 * <p>随后跳过：构造函数、静态初始化块、桥接方法，以及
	 * {@link MethodFingerprinter#isExcluded} 列出的方法（如
	 * {@code $deserializeLambda$}）。</p>
	 *
	 * <p>返回的 {@link ClassNode} 使用 {@code SKIP_DEBUG | SKIP_FRAMES} 读取，
	 * 因此<b>不能直接写回字节码</b>——它被用于：
	 * （a）取 {@code name}；（b）在 {@code renameMap} 为空时供调用方收集
	 * {@code presentKeys}（只需 {@code name + desc}）；
	 * （c）供 {@link #resurrectOrphanedLambdas} 提取孤儿方法的签名头，
	 * 避免再次读取 {@code oldBytes}。</p>
	 * @param bytes 要扫描的字节码
	 * @param ctx   匹配上下文
	 * @param isOld 是否为旧版本
	 * @return 该类的 {@link ClassNode}
	 */
	/**
	 * 收集方法体内直接引用的属于当前宿主类的匿名类内部名。
	 */
	private static Set<String> referencedAnonClasses(MethodNode mn, String hostClassName) {
		Set<String> set = null;
		for (AbstractInsnNode insn : mn.instructions) {
			String owner = null;
			if (insn instanceof TypeInsnNode tin) {
				owner = tin.desc;
			} else if (insn instanceof MethodInsnNode min) {
				owner = min.owner;
			} else if (insn instanceof FieldInsnNode fin) {
				owner = fin.owner;
			} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type t) {
				if (t.getSort() == Type.OBJECT) owner = t.getInternalName();
			}
			if (owner != null && isAnonClassOf(owner, hostClassName)) {
				if (set == null) set = new HashSet<>(4);
				set.add(owner);
			}
		}
		return set == null ? Collections.emptySet() : set;
	}

	private static boolean isAnonClassOf(String className, String hostClassName) {
		if (className == null) return false;
		if (className.startsWith("[")) {
			int idx = className.lastIndexOf('[');
			if (idx + 2 < className.length() && className.charAt(idx + 1) == 'L' && className.endsWith(";")) {
				className = className.substring(idx + 2, className.length() - 1);
			}
		}
		if (!className.startsWith(hostClassName + "$")) return false;
		String suffix = className.substring(hostClassName.length() + 1);
		return MethodFingerprinter.isUnstableNestedSuffix(suffix);
	}

	/**
	 * 基于 ASM {@link Analyzer} 和 {@link SourceInterpreter} 进行反向数据流栈分析，
	 * 精确提取方法内所有 Lambda 实例化指令（invokedynamic / method call）的操作数栈消费目标。
	 *
	 * <p>相较于单纯向后线性扫描，本方法具备完全的栈感知能力，能免疫中间指令及参数前置计算的干扰：
	 * <ul>
	 *   <li>{@code foo(() -> a, () -> b)}：两个 indy 均能准确识别为同一 {@code CALL:...foo} 的入参；</li>
	 *   <li>{@code a(() -> x, 5)}：indy 之后的常量加载指令不会干扰消费者判定；</li>
	 *   <li>{@code a(() -> x, bar())}：作为参数的嵌套调用 {@code bar()} 不会被误判为消费端；</li>
	 *   <li>三元表达式 / 分支合并（多生产者）：返回空证据，严守 Fail-Closed 拒绝猜测；</li>
	 *   <li>排除本地变量存储（{@code ASTORE}）作为特征，防止前插局部变量导致槽位位移误判。</li>
	 * </ul>
	 * </p>
	 */
	private static Map<AbstractInsnNode, String> findConsumers(String owner, MethodNode mn) {
		if (isMutationActive("4")) return Collections.emptyMap();
		Map<AbstractInsnNode, String> map = new HashMap<>();
		if (mn.instructions == null || mn.instructions.size() == 0) return map;
		Frame<SourceValue>[] frames;
		try {
			Analyzer<SourceValue> analyzer = new Analyzer<>(new SourceInterpreter());
			frames = analyzer.analyze(owner, mn);
		} catch (AnalyzerException | RuntimeException t) {
			// 分析失败时保守降级，返回空映射
			return Collections.emptyMap();
		}
		if (frames == null) return Collections.emptyMap();

		AbstractInsnNode[] insns = mn.instructions.toArray();
		for (int i = 0; i < insns.length; i++) {
			AbstractInsnNode insn = insns[i];
			Frame<SourceValue> frame = frames[i];
			if (frame == null) continue;

			String consumerTag = null;
			int consumedCount = 0;

			if (insn instanceof MethodInsnNode min) {
				Type[] args = Type.getArgumentTypes(min.desc);
				consumedCount = args.length + (min.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
				consumerTag = "CALL:" + min.owner + "." + min.name + min.desc;
			} else if (insn instanceof InvokeDynamicInsnNode indy) {
				Type[] args = Type.getArgumentTypes(indy.desc);
				consumedCount = args.length;
				consumerTag = "INDY:" + indy.name + indy.desc;
			} else if (insn instanceof FieldInsnNode fin) {
				if (fin.getOpcode() == Opcodes.PUTFIELD) {
					consumedCount = 2; // objectref + value
					consumerTag = "PUT:" + fin.owner + "." + fin.name + fin.desc;
				} else if (fin.getOpcode() == Opcodes.PUTSTATIC) {
					consumedCount = 1; // value
					consumerTag = "PUT:" + fin.owner + "." + fin.name + fin.desc;
				}
			} else if (insn.getOpcode() == Opcodes.ARETURN) {
				consumedCount = 1;
				consumerTag = "RETURN";
			} else if (insn.getOpcode() == Opcodes.ATHROW) {
				consumedCount = 1;
				consumerTag = "THROW";
			}

			if (consumerTag != null && consumedCount > 0) {
				int stackSize = frame.getStackSize();
				int startSlot = Math.max(0, stackSize - consumedCount);
				for (int slot = startSlot; slot < stackSize; slot++) {
					SourceValue val = frame.getStack(slot);
					if (val == null || val.insns == null) continue;
					if (val.insns.size() == 1) {
						AbstractInsnNode producer = val.insns.iterator().next();
						if (!map.containsKey(producer)) {
							map.put(producer, consumerTag);
						} else if (!consumerTag.equals(map.get(producer))) {
							map.put(producer, ""); // 冲突歧义置空
						}
					} else {
						// 多生产者（三元表达式/分支合并等）：标记歧义，拒绝猜测
						for (AbstractInsnNode producer : val.insns) {
							map.put(producer, "");
						}
					}
				}
			}
		}
		return map;
	}

	private static ClassNode scan(byte[] bytes, MatchContext ctx, boolean isOld,
	                              java.util.function.Function<String, byte[]> resolver) {
		ClassNode cn = new ClassNode();
		// SKIP_DEBUG：不解析行号 / 局部变量表；SKIP_FRAMES：不解析 StackMapTable。
		// 二者对逻辑指纹都没有贡献，跳过可减少内存与解析时间。
		new ClassReader(bytes).accept(cn,
		 ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

		// 预计算方法体引用的匿名类内容哈希，供 MethodFingerprinter 只查表，避免单例重入与重复计算
		Map<String, Long> anonHashes = isOld ? ctx.oldAnonHashes : ctx.newAnonHashes;
		if (resolver != null) {
			Set<String> visiting = new HashSet<>();
			for (MethodNode mn : cn.methods) {
				for (String anon : referencedAnonClasses(mn, cn.name)) {
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
			fp.setValidLabels(collectValidLabels(mn));
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
			info.ghost = isGhostMethod(mn);
			if (info.ghost) info.ghostOriginalHash = readGhostOriginalHash(mn);
			// 上一轮注入的捕获桥：键在新版本里天然没有对应物，必须在幽灵化之前认出来，
			// 否则第一轮的成果会在第二轮被自己抹掉（见 stepCaptureBridge）。
			info.bridge = isBridgeMethod(mn);
			info.skeletonHash = skeletonHash(mn);
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
				hostKey = "L:" + info.hash + (!isMutationActive("1") && info.consumer != null && !info.consumer.isEmpty() ? ":" + info.consumer : "");
			} else {
				hostKey = "M:" + mn.name + mn.desc;
			}

			Map<AbstractInsnNode, String> consumers = findConsumers(cn.name, mn);
			List<SyntheticInfo> seq = null;
			for (AbstractInsnNode n : mn.instructions) {
				String callee = null;
				if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
				    && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
					callee = h.getName();
					recordLambdaShape(nameIndex.get(callee), i, h, mn,
					                  isOld ? ctx.oldParamNames : ctx.newParamNames);
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
			SyntheticInfo info = findByKey(groups, mn.name, mn.desc);
			if (info == null || !info.renameable || info.ghost || info.bridge) continue;
			// 孤立/死代码不分配正向 callOrder，保持 -1 以标记不可达
			String hostKey = "L:" + info.hash + (!isMutationActive("1") && info.consumer != null && !info.consumer.isEmpty() ? ":" + info.consumer : "");
			Map<AbstractInsnNode, String> consumers = findConsumers(cn.name, mn);
			List<SyntheticInfo> seq = null;
			for (AbstractInsnNode n : mn.instructions) {
				String callee = null;
				if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
				    && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
					callee = h.getName();
					recordLambdaShape(nameIndex.get(callee), i, h, mn,
					                  isOld ? ctx.oldParamNames : ctx.newParamNames);
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
				RemoldAgent.warn("[LambdaAligner] 子树形状在 " + (isOld ? "old" : "new")
				                  + " 侧 64 轮未收敛（存在环或异常引用），沿用当前值；未定稿者保持 null");
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
					RemoldAgent.warn("[LambdaAligner] 上行深度在 " + (isOld ? "old" : "new")
					                  + " 侧 64 轮未收敛（可能存在环），未定稿者保持 -1");
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
				RemoldAgent.warn("[LambdaAligner] 递归语义指纹在 " + className
				                  + " 上 64 轮未收敛，沿用当前值继续（结果可能不够精确，但不影响可用性）");
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

	/**
	 * 只读一遍字节码，取合成方法<b>形参槽</b>的 {@code LocalVariableTable} 名字。
	 *
	 * <p>为什么单开一趟而不是把 {@code scan} 的 {@code SKIP_DEBUG} 去掉：{@code scan} 要读
	 * 指令流算指纹，多带调试信息会拖慢主路径，也会让指纹受 {@code -g} 设置影响。这里只关心
	 * 名字，非合成方法直接 {@code return null}，连 {@code Code} 都不展开。</p>
	 *
	 * <p>名字是比槽号更硬的证据：宿主方法里增删前序局部变量会让槽号整体位移，名字不会。
	 * 未带 {@code -g} 编译时整张表为空，调用方退回槽号/类型判据。</p>
	 *
	 * <p><b>静态方法的首形参可能是被前置的接收者</b>：{@code forceStaticLambdas} 会把实例方法
	 * 改成静态方法并把 {@code this} 变成显式首形参，而那个槽是<b>新加的、没有 LVT 条目</b>的，
	 * 读出来是 {@code null}。这里统一记成 {@link #RECEIVER_NAME}——否则"任一位缺名字就整体
	 * 放弃按名映射"那条严格判据会把这类 lambda 全部判成没证据。</p>
	 *
	 * @return {@code name + desc -> 形参名字数组}（按形参顺序；实例方法不含接收者）
	 */
	public static Map<String, String[]> readLambdaParamNames(byte[] bytes) {
		Map<String, String[]> out = new HashMap<>();
		if (bytes == null || bytes.length == 0) return out;
		ClassReader cr = new ClassReader(bytes);
		final String owner = cr.getClassName();
		cr.accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int acc, String name, String desc,
			                                 String sig, String[] ex) {
				if (!MethodFingerprinter.isSyntheticName(name) || name.startsWith("access$")) return null;
				boolean isStatic = (acc & Opcodes.ACC_STATIC) != 0;
				Type[] args = Type.getArgumentTypes(desc);
				int[] slotOf = new int[args.length];
				int s = isStatic ? 0 : 1;
				for (int i = 0; i < args.length; i++) {
					slotOf[i] = s;
					s += args[i].getSize();
				}
				int paramEnd = s;
				String[] names = new String[args.length];
				out.put(name + desc, names);
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitLocalVariable(String n, String d, String sg,
					                               Label st, Label en, int idx) {
						if (idx >= paramEnd) return;              // 只要形参，丢弃方法体局部变量
						for (int i = 0; i < slotOf.length; i++) {
							if (slotOf[i] == idx && args[i].getDescriptor().equals(d) && names[i] == null) {
								names[i] = n;
							}
						}
					}

					@Override
					public void visitEnd() {
						// 静态方法的首形参可能是 forceStaticLambdas 前置的接收者：槽是新增的，
						// 没有 LVT 条目，读出来是 null；若它本来就是实例方法、只是被改成静态，
						// LVT 里留着 javac 写的 "this"。两者指同一个东西，统一记成 RECEIVER_NAME，
						// 否则同一个 lambda 在两种来源之间切换会因 "this" vs null 对不上而丢掉按名映射。
						// 判据是"类型是本类"而不是"名字为空"——真正的同名局部变量会带自己的名字。
						if (isStatic && names.length > 0
						    && (names[0] == null || "this".equals(names[0]))
						    && isReceiverType(args[0].getDescriptor(), owner)) {
							names[0] = RECEIVER_NAME;
						}
						super.visitEnd();
					}
				};
			}
		}, ClassReader.SKIP_FRAMES);
		return out;
	}

	/**
	 * 接收者在捕获表里的统一名字。
	 *
	 * <p>它<b>不是</b>源码里的变量名，只是一个"两侧必然相等"的占位符。归一它的理由是
	 * {@code forceStaticLambdas} 让接收者有<b>两种</b>字节码来源，而这两种来源留下的
	 * {@code LocalVariableTable} 不一样：</p>
	 * <ul>
	 *   <li>实例方法被改成静态：javac 原本给槽 0 写了 {@code "this"}，改写不动 LVT，读出来是 {@code "this"}；</li>
	 *   <li>零捕获静态 lambda 被前置 receiver：槽 0 是新增的，<b>没有 LVT 条目</b>，读出来是 {@code null}。</li>
	 * </ul>
	 * <p>两者指的是同一个东西。不归一的话，同一个 lambda 在两种来源之间切换就会出现
	 * {@code "this"} vs {@code null} 的名字不匹配，而"任一位缺名字就整体放弃按名映射"这条
	 * 严格判据会把它判成"没证据"，白白退回槽号/类型推断。</p>
	 *
	 * <p>用 {@code $} 开头是为了不可能与 Java 标识符冲突（{@code $this} 不是合法变量名）。</p>
	 */
	public static final String RECEIVER_NAME = "$this";

	/**
	 * 把合成方法的形参名裁成"捕获名"：前 {@code captured - offset} 项即捕获变量
	 * （实例 lambda 的形参表不含接收者，故 {@code captured[0]} 由这里补上）。
	 *
	 * <p>接收者槽一律记作 {@link #RECEIVER_NAME}；静态方法里那个"被前置的接收者"
	 * 已经由 {@link #readLambdaParamNames} 归一好了，这里只需处理实例 lambda 的隐式接收者。</p>
	 */
	private static List<String> captureNamesOf(String[] paramNames, List<String> captured, boolean isStatic) {
		if (paramNames == null) return null;
		int offset = isStatic ? 0 : 1;
		int n = captured.size() - offset;
		if (n < 0 || paramNames.length < n) return null;
		List<String> out = new ArrayList<>(captured.size());
		if (!isStatic) out.add(RECEIVER_NAME);                     // 接收者没有源码名
		for (int i = 0; i < n; i++) out.add(paramNames[i]);
		return out;
	}

	/** 该描述符是不是"当前类自己的引用"（{@code forceStaticLambdas} 前置 receiver 的形态）。 */
	private static boolean isReceiverType(String desc, String owner) {
		return owner != null && desc != null && desc.equals("L" + owner + ";");
	}

	/**
	 * 把 indy 调用点上的 lambda 形状（捕获表 / 功能接口 / SAM 签名）记到目标合成方法上。
	 *
	 * <p><b>必须再比一次描述符</b>：{@code nameIndex} 是按名字索引的，同名不同描述符的重载
	 * （JDK 8 的 {@code lambda$null$0(II)} 与 {@code lambda$null$0(I)}）会互相覆盖。
	 * 不比的话会把 A 的捕获表贴到 B 上，桥接的参数映射会直接错位——那是静默错值。</p>
	 *
	 * <p>只取<b>先见到的</b>那个调用点：同一个合成方法被多处 indy 引用时，各处的捕获表
	 * 必然一致（它就是方法签名的一部分），不一致只可能来自索引错位，此时保留第一份。</p>
	 */
	private static void recordLambdaShape(SyntheticInfo info, InvokeDynamicInsnNode indy, Handle impl,
	                                      MethodNode host, Map<String, String[]> paramNames) {
		if (info == null || info.captured != null) return;
		if (!info.desc.equals(impl.getDesc())) return;
		if (indy.bsmArgs.length < 3) return;
		if (!(indy.bsmArgs[0] instanceof Type sam) || !(indy.bsmArgs[2] instanceof Type inst)) return;
		Type[] caps = Type.getArgumentTypes(indy.desc);
		List<String> captured = new ArrayList<>(caps.length);
		for (Type t : caps) captured.add(t.getDescriptor());
		info.captured = captured;
		info.fiType = Type.getReturnType(indy.desc).getInternalName();
		info.samSig = sam.getDescriptor();
		info.instSig = inst.getDescriptor();
		info.captureSlots = captureSlotsOf(indy, caps);
		info.captureNames = captureNamesOf(paramNames.get(info.name + info.desc), captured,
			info.isStatic());
		info.hostParamEndSlot = paramEndSlot(host);
		info.hostMethod = host;
	}

	/** 宿主方法形参槽的结束位置（第一个局部变量所在的槽）。 */
	private static int paramEndSlot(MethodNode host) {
		int s = (host.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
		for (Type t : Type.getArgumentTypes(host.desc)) s += t.getSize();
		return s;
	}

	/**
	 * 回溯每个捕获实参来自宿主方法的哪个局部变量槽。
	 *
	 * <p>javac 在 indy 之前按捕获顺序把实参压栈，所以从 indy 往前数 {@code captured.length}
	 * 条真实指令就是它们的来源。只认<b>直接的局部变量加载</b>：实参若是临时值
	 * （{@code () -> use(make(a))} 会先算出一个临时槽再捕获），槽号没有跨版本的身份，
	 * 回溯一律判失败——宁可退回类型推断，也不能拿一个随机槽号去"证明"映射。</p>
	 *
	 * @return 与 {@code captured} 对齐的槽号；无法回溯时 {@code null}
	 */
	private static List<Integer> captureSlotsOf(InvokeDynamicInsnNode indy, Type[] captured) {
		int n = captured.length;
		if (n == 0) return List.of();
		int[] slots = new int[n];
		AbstractInsnNode cur = indy.getPrevious();
		for (int k = n - 1; k >= 0; k--) {
			while (cur != null && cur.getOpcode() < 0) cur = cur.getPrevious();   // 跳过 Label/LineNumber
			if (!(cur instanceof VarInsnNode v)) return null;
			if (!loadMatches(v.getOpcode(), captured[k])) return null;
			slots[k] = v.var;
			cur = cur.getPrevious();
		}
		List<Integer> out = new ArrayList<>(n);
		for (int s : slots) out.add(s);
		return out;
	}

	/** 加载指令与类型是否匹配（用于校验回溯没有错位）。 */
	private static boolean loadMatches(int op, Type t) {
		switch (t.getSort()) {
			case Type.BOOLEAN: case Type.CHAR: case Type.BYTE: case Type.SHORT: case Type.INT:
				return op == Opcodes.ILOAD;
			case Type.LONG:   return op == Opcodes.LLOAD;
			case Type.FLOAT:  return op == Opcodes.FLOAD;
			case Type.DOUBLE: return op == Opcodes.DLOAD;
			case Type.OBJECT: case Type.ARRAY: return op == Opcodes.ALOAD;
			default: return false;
		}
	}

	/** 在已扫描的旧/新分组里按名字找 SyntheticInfo（供子指纹计算使用）。 */
	private static SyntheticInfo infoByName(MatchContext ctx, boolean isOld, String name) {		// O(1) 查索引。早先是"遍历所有 group 的所有成员"的双重循环，而本方法被
		// **三个 64 轮定稿循环**按"每个方法 × 每个子"调用，最坏合计 O(64·N²)。
		// 见 MatchContext.oldNameIndex / newNameIndex 的说明。
		return (isOld ? ctx.oldNameIndex : ctx.newNameIndex).get(name);
	}

	/**
	 * 收集逻辑相关的 Label：只包含被跳转指令、switch、异常表引用的 Label。
	 *
	 * <p>排除纯调试标签（行号锚点、局部变量作用域边界），这些标签在不同
	 * {@code -g} 设置下数量与位置都会变，不应参与指纹。</p>
	 *
	 * <p>返回的 {@link Label} 实例与 {@link MethodNode#accept(MethodVisitor)}
	 * 回放时传入的是同一对象，可直接用作集合元素。</p>
	 */
	private static Set<Label> collectValidLabels(MethodNode mn) {
		Set<Label> valid = new HashSet<>();
		for (AbstractInsnNode insn : mn.instructions) {
			if (insn instanceof JumpInsnNode j) {
				valid.add(j.label.getLabel());
			} else if (insn instanceof TableSwitchInsnNode t) {
				valid.add(t.dflt.getLabel());
				for (LabelNode l : t.labels) valid.add(l.getLabel());
			} else if (insn instanceof LookupSwitchInsnNode l) {
				valid.add(l.dflt.getLabel());
				for (LabelNode ln : l.labels) valid.add(ln.getLabel());
			}
		}
		if (mn.tryCatchBlocks != null) {
			for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
				valid.add(tcb.start.getLabel());
				valid.add(tcb.end.getLabel());
				valid.add(tcb.handler.getLabel());
			}
		}
		return valid;
	}

	/**
	 * 将被删除的 lambda 以“空方法体”的形式复活注入到新字节码中，
	 * 防止被缓存的 {@code CallSite} 抛出 {@link NoSuchMethodError}。
	 *
	 * <p>复用调用方已经解析好的 {@code oldCn} 提取孤儿方法的签名头，
	 * 避免再次读取 {@code oldBytes}。</p>
	 *
	 * <p>{@code oldCn} 是用 {@code SKIP_DEBUG | SKIP_FRAMES} 读入的，
	 * 但注入空壳只用到 {@code access} / {@code name} / {@code desc} /
	 * {@code signature} / {@code exceptions} 这些方法头字段——它们不受
	 * {@code SKIP_*} 影响。方法体（Code 属性）由 {@link #injectDummyBody}
	 * 重新生成，不会拷贝 {@code oldCn} 中的原始指令。</p>
	 * @param oldCn       已解析的旧类节点（由 {@code align} 中的 {@code scan} 提供）
	 * @param newBytes    经过重命名处理后的新版本字节码
	 * @param presentKeys 新版本里实际存在的所有方法键（{@code name + desc}）
	 * @param ctx         当前匹配上下文
	 * @return 注入幽灵方法后的最终字节码；若无孤儿则原样返回 {@code newBytes}
	 */
	/**
	 * 统计类中所有通过 {@code invokedynamic} 引导参数直接引用的方法句柄目标出现频次。
	 * 键格式形如：{@code "com/example/Foo.save()V"}（不含 Handle tag，兼容跨 JDK 版本与私有调用模式差异）。
	 */
	private static Map<String, Integer> countHandleTargets(ClassNode cn) {
		Map<String, Integer> counts = new HashMap<>();
		if (cn == null || cn.methods == null) return counts;
		for (MethodNode mn : cn.methods) {
			if (mn.instructions == null) continue;
			for (AbstractInsnNode i : mn.instructions) {
				if (i instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
					for (Object a : indy.bsmArgs) {
						if (a instanceof Handle h) {
							String key = h.getOwner() + "." + h.getName() + h.getDesc();
							counts.put(key, counts.getOrDefault(key, 0) + 1);
						}
					}
				}
			}
		}
		return counts;
	}

	/** 纯委托调用信息：包含被调用的目标方法节点、转发所需的主体指令序列以及上一轮是否已是转发桩。 */
	private record Delegate(MethodInsnNode target, List<AbstractInsnNode> body, boolean wasForwarding) {}

	/** 包装类内部名集合（用于装箱/拆箱适配识别）。兼容 Java 8 编译。 */
	private static final Set<String> WRAPPERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		"java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte",
		"java/lang/Character", "java/lang/Boolean", "java/lang/Float", "java/lang/Double")));

	private static boolean isPrimitive(Type t) {
		return t.getSort() >= Type.BOOLEAN && t.getSort() <= Type.DOUBLE;
	}

	private static boolean isBoxing(MethodInsnNode m) {
		if (m.getOpcode() != Opcodes.INVOKESTATIC || !m.name.equals("valueOf")
		    || !WRAPPERS.contains(m.owner)) return false;
		Type[] args = Type.getArgumentTypes(m.desc);
		Type ret = Type.getReturnType(m.desc);
		return args.length == 1 && isPrimitive(args[0])
		       && ret.getSort() == Type.OBJECT && m.owner.equals(ret.getInternalName());
	}

	private static boolean isUnboxing(MethodInsnNode m) {
		return m.getOpcode() == Opcodes.INVOKEVIRTUAL && WRAPPERS.contains(m.owner)
		       && m.name.endsWith("Value") && m.desc.startsWith("()")
		       && isPrimitive(Type.getReturnType(m.desc));
	}

	private static boolean isWidening(int op) {
		return op == Opcodes.I2L || op == Opcodes.I2F || op == Opcodes.I2D
		       || op == Opcodes.L2F || op == Opcodes.L2D || op == Opcodes.F2D;
	}

	/**
	 * 判定方法节点是否为严格的“纯委托或标准适配委托”实现。
	 *
	 * <p>支持由 LambdaMetafactory 自动完成的无分支标准适配：装箱、拆箱、基本类型加宽、丢弃返回值 (POP/POP2)、CHECKCAST。</p>
	 * <p>若上一轮已为此方法注入过幽灵桩（前缀为 {@code LDC loc; ICONST_0/1; INVOKESTATIC onOrphanInvoked}），
	 * 会自动跳过该固定前缀，并仅提取后续的委托主体指令，避免多轮热更时前缀重复调用或退化为空壳。</p>
	 */
	private static Delegate getPureDelegate(MethodNode mn) {
		if (mn.instructions == null || mn.instructions.size() == 0) return null;
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode i : mn.instructions) {
			if (i.getOpcode() >= 0) real.add(i);
		}

		// 先剥掉"生前指纹"标记：LDC <long>; POP2。它是 injectDummyBody 加的，不是桩体语义的一部分。
		// 不剥掉的话，下面的转发前缀检测会把它当成 LDC loc，整段判定失效（转发桩退化成空壳）。
		if (real.size() >= 2
		    && real.get(0) instanceof LdcInsnNode marker
		    && marker.cst instanceof Long
		    && real.get(1).getOpcode() == Opcodes.POP2) {
			real = new ArrayList<>(real.subList(2, real.size()));
		}

		// 跳过上一轮转发桩的固定前缀：LDC loc; ICONST_0/1; INVOKESTATIC onOrphanInvoked
		int start = 0;
		boolean wasForwarding = false;
		if (real.size() >= 3
		    && real.get(0) instanceof LdcInsnNode
		    && (real.get(1).getOpcode() == Opcodes.ICONST_0 || real.get(1).getOpcode() == Opcodes.ICONST_1)
		    && real.get(2) instanceof MethodInsnNode p
		    && p.owner.equals(Type.getInternalName(LambdaAligner.class))
		    && p.name.equals("onOrphanInvoked")) {
			start = 3;
			wasForwarding = (real.get(1).getOpcode() == Opcodes.ICONST_1);
		} else if (real.size() >= 2
		           && real.get(0) instanceof LdcInsnNode
		           && real.get(1) instanceof MethodInsnNode p
		           && p.owner.equals(Type.getInternalName(LambdaAligner.class))
		           && p.name.equals("onOrphanInvoked")) {
			start = 2;
		}
		List<AbstractInsnNode> body = real.subList(start, real.size());

		boolean voidRet = Type.getReturnType(mn.desc).getSort() == Type.VOID;
		int expectSlot = 0;
		int endSlot = Type.getArgumentsAndReturnSizes(mn.desc) >> 2;   // 参数槽总数（含 this 的 +1）
		if ((mn.access & Opcodes.ACC_STATIC) != 0) endSlot -= 1;

		MethodInsnNode core = null, onlyInvoke = null;
		int invokes = 0;

		for (AbstractInsnNode insn : body) {
			int op = insn.getOpcode();
			if (insn instanceof VarInsnNode v && op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) {
				if (v.var != expectSlot) return null;                    // 必须按槽位顺序加载
				expectSlot += (op == Opcodes.LLOAD || op == Opcodes.DLOAD) ? 2 : 1;
			} else if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
				// ok
			} else if (isWidening(op) || (insn instanceof TypeInsnNode && op == Opcodes.CHECKCAST)) {
				// ok
			} else if ((op == Opcodes.POP || op == Opcodes.POP2) && voidRet) {
				// ok：Runnable r = this::a 丢弃返回值
			} else if (insn instanceof MethodInsnNode m) {
				invokes++;
				onlyInvoke = m;
				if (!isBoxing(m) && !isUnboxing(m)) {
					if (core != null) return null;
					core = m;
				}
			} else {
				return null;
			}
		}
		if (core == null && invokes == 1) core = onlyInvoke;             // 如 IntFunction<Integer> ← Integer::valueOf
		if (core == null || expectSlot != endSlot) return null;          // 每个形参恰好加载一次
		return new Delegate(core, body, wasForwarding);
	}

	// ------------------------------------------------------------------------------------------------
	// 捕获桥（单向：只支持"删除捕获变量"）
	// ------------------------------------------------------------------------------------------------

	/**
	 * 桥接规格：老键（名字+描述符）→ 本轮目标方法 + 参数映射。
	 *
	 * <p>桥<b>不参与改名</b>：新方法保留自己的名字，桥占用老键。两者的描述符必然不同
	 * （捕获表缩水了），可以共存。这样老隐藏类常量池里那条 {@code (name, 旧desc)} 引用
	 * 仍然解析得到，而它指向的桥把参数丢给新方法。</p>
	 */
	static final class BridgeSpec {
		final String  oldDesc;
		final String  targetName;
		final String  targetDesc;
		/**
		 * 新捕获表每一位的取值来源，长度恒等于<b>新</b>捕获表长度（下标 {@code j} 对应新捕获 {@code j}）。
		 *
		 * <p>实例桥的接收者（捕获 0）也在里面，虽然它在桥体里是单独 {@code aload_0} 压的。</p>
		 */
		final ParamSource[] sources;
		/**
		 * 桥方法自身是不是静态的（由<b>老</b>方法决定）。
		 *
		 * <p>老隐藏类按老方法的形态调用它：老方法是实例方法，它就得是实例方法、槽 0 是接收者。
		 * 这一位只影响"桥怎么取自己的实参"，与"目标怎么被调"无关。</p>
		 */
		final boolean isStatic;
		/**
		 * <b>目标</b>方法是不是静态的（由<b>新</b>方法决定）。
		 *
		 * <p>与 {@link #isStatic} 必须分开：{@code forceStaticLambdas} 关闭时，"删掉 this 引用"
		 * 会让合成方法从实例变静态，而桥仍得是实例方法（老调用点按 {@code INVOKESPECIAL} 调它）。
		 * 把两者混成一位，就会写出"实例桥去 {@code INVOKESTATIC} 一个要接收者的目标"或
		 * 反之的非法字节码。</p>
		 */
		final boolean targetStatic;
		final String  location;

		BridgeSpec(String oldDesc, String targetName, String targetDesc,
		           ParamSource[] sources, boolean isStatic, boolean targetStatic, String location) {
			this.oldDesc = oldDesc;
			this.targetName = targetName;
			this.targetDesc = targetDesc;
			this.sources = sources;
			this.isStatic = isStatic;
			this.targetStatic = targetStatic;
			this.location = location;
		}
	}

	/**
	 * 桥体里"新捕获表第 j 项"的取值来源。
	 *
	 * <p>只有两种可能：要么从老实例已存下来的捕获里搬（{@link #OLD}），要么在桥里
	 * <b>现场重放</b>一个可证明的纯源（{@link #CONST}/{@link #GETSTATIC}/{@link #GETFIELD}）。
	 * 后者只用于"新增捕获"——老实例里根本没有那个值，而新方法体必然读它。</p>
	 *
	 * <p>"纯源"的判据是：这段表达式在桥执行时的求值结果，与它在 lambda 创建时被捕获的值
	 * <b>必然相同</b>。常量恒成立；字段读只在字段 {@code final} 时成立（读到的引用/值
	 * 之后不可能再变）。非 final 的字段读、方法调用、任何依赖局部变量的算式一律拒绝——
	 * 那等于编一个值，属于"静默写入假值"。</p>
	 */
	static final class ParamSource {
		static final int OLD = 0, CONST = 1, GETSTATIC = 2, GETFIELD = 3;

		final int    kind;
		/** {@link #OLD}：老捕获表下标。 */
		final int    oldIndex;
		/** {@link #CONST}：Integer / Long / Float / Double / String，或 null。 */
		final Object constant;
		/** {@link #GETSTATIC} / {@link #GETFIELD} 的字段三元组。 */
		final String owner, name, desc;

		private ParamSource(int kind, int oldIndex, Object constant,
		                    String owner, String name, String desc) {
			this.kind = kind;
			this.oldIndex = oldIndex;
			this.constant = constant;
			this.owner = owner;
			this.name = name;
			this.desc = desc;
		}

		static ParamSource old(int oldIndex) {
			return new ParamSource(OLD, oldIndex, null, null, null, null);
		}

		static ParamSource constant(Object value) {
			return new ParamSource(CONST, -1, value, null, null, null);
		}

		static ParamSource getStatic(String owner, String name, String desc) {
			return new ParamSource(GETSTATIC, -1, null, owner, name, desc);
		}

		static ParamSource getField(String owner, String name, String desc) {
			return new ParamSource(GETFIELD, -1, null, owner, name, desc);
		}

		/** 这个来源产出的值是不是正好是 {@code want} 类型（写错类型会在验证期直接失败）。 */
		boolean produces(Type want) {
			switch (kind) {
				case OLD:
					return true;                                  // 由按名匹配时的"同类型"检查保证
				case CONST:
					// null 可以喂给任意引用类型（ACONST_NULL 与目标类型无关）。
					if (constant == null) {
						return want.getSort() == Type.OBJECT || want.getSort() == Type.ARRAY;
					}
					return constantType().equals(want);
				default:
					return Type.getType(desc).equals(want);
			}
		}

		private Type constantType() {
			if (constant == null) return Type.getType("Ljava/lang/Object;");
			if (constant instanceof Integer) return Type.INT_TYPE;
			if (constant instanceof Long) return Type.LONG_TYPE;
			if (constant instanceof Float) return Type.FLOAT_TYPE;
			if (constant instanceof Double) return Type.DOUBLE_TYPE;
			if (constant instanceof String) return Type.getType("Ljava/lang/String;");
			return Type.VOID_TYPE;                                // 不可重放的常量
		}

		/** 供日志/事件使用：一眼看出这一位是"搬"还是"重放"。 */
		String describe() {
			switch (kind) {
				case OLD:      return "old[" + oldIndex + "]";
				case CONST:    return "const(" + constant + ")";
				case GETSTATIC: return "static " + owner + "." + name;
				default:        return "field " + owner + "." + name;
			}
		}
	}

	/** 上一轮注入的桥体解析结果：目标调用 + 每一位新捕获的来源。 */
	private record BridgeDelegate(MethodInsnNode target, ParamSource[] sources, boolean targetStatic) {}

	/**
	 * 捕获桥：把"这一轮删掉了捕获变量"的老 lambda 与它的新方法接起来。
	 *
	 * <p><b>为什么只做删除方向</b>：新描述符的形参表是老形参表的子序列，被删掉的那个槽位
	 * 在新方法体里<b>物理上不存在</b>（验证器保证读不到），所以桥做的是"丢弃一个新代码
	 * 永远不会读的值"——不是伪造值，也没有任何写入。反方向（新增捕获）必须替老实例编一个
	 * {@code 0}/{@code null} 填进去，而新方法体很可能真读它，那就是静默写入假值。</p>
	 *
	 * <p><b>三道门</b>（任一不成立就退回幽灵桩，不猜）：</p>
	 * <ol>
	 *   <li><b>SAM 不变</b>：功能接口、{@code samMethodType}、{@code instantiatedMethodType}
	 *       三者都必须相等。否则是"功能接口变了"（{@code BiFunction} → {@code Function}），
	 *       老实例对象本身还绑在旧接口上，桥接 impl 救不了。</li>
	 *   <li><b>宿主相同</b>：同一个 {@code hostKey}，否则"同一个 lambda"只是猜。</li>
	 *   <li><b>映射唯一</b>：新捕获表必须是老捕获表的<b>唯一</b>子序列嵌入。重复类型
	 *       （{@code [int,int] → [int]}）有歧义，直接拒绝。</li>
	 * </ol>
	 *
	 * <p>另有一条<b>布局自检</b>：{@code 捕获表 ++ 实例化 SAM 形参} 必须恰好等于 impl 描述符。
	 * 这条等式不成立就说明我们对这个调用点的参数布局理解有误，一律不建桥。</p>
	 *
	 * <p><b>第二轮的坑</b>：桥的键在新版本里天然没有对应物，若不在这里重新认领，它会被
	 * 当成"又被删了一次"重新幽灵化。所以先认桥（{@link #getBridgeDelegate}），把它上一轮
	 * 的目标顺着本轮的配对关系挪到新名字上，再重生成桥体——而不是"识别后沿用旧体"，
	 * 那样 {@code ctx.renameMap} 一改名桥就指向空气。</p>
	 */
	private static void stepCaptureBridge(ClassNode oldCn, ClassNode newCn, MatchContext ctx,
	                                      Set<String> presentKeys) {
		List<SyntheticInfo> allOld = new ArrayList<>();
		for (int gi = ctx.oldGroups.nextEntry(-1); gi != -1; gi = ctx.oldGroups.nextEntry(gi)) {
			List<SyntheticInfo> g = ctx.oldGroups.valueAt(gi);
			if (g == null) continue;
			allOld.addAll(g);
		}
		List<SyntheticInfo> unpairedNew = new ArrayList<>();
		for (int ni = ctx.newGroups.nextEntry(-1); ni != -1; ni = ctx.newGroups.nextEntry(ni)) {
			List<SyntheticInfo> g = ctx.newGroups.valueAt(ni);
			if (g == null) continue;
			for (SyntheticInfo n : g) {
				if (n.matched || n.ghost || !n.renameable) continue;
				if (n.captured == null) continue;
				unpairedNew.add(n);
			}
		}

		// ---- 1. 第一轮：老 lambda 删了捕获，且新类里没有同键（本来会变成幽灵） ----
		Map<String, SyntheticInfo> claim = new HashMap<>();      // oldKey -> 唯一候选
		Map<String, Integer>      claims = new HashMap<>();      // newKey -> 被几个老键抢
		for (SyntheticInfo oi : allOld) {
			if (oi.matched || oi.ghost || oi.bridge || !oi.renameable) continue;
			if (presentKeys.contains(oi.name + oi.desc)) continue;
			if (oi.captured == null) continue;
			SyntheticInfo only = null;
			int hits = 0;
			for (SyntheticInfo ni : unpairedNew) {
				if (planBridge(oi, ni, newCn) == null) continue;
				only = ni;
				if (++hits > 1) break;
			}
			if (hits != 1) continue;                              // 零个或歧义：拒绝
			String oldKey = oi.name + oi.desc;
			String newKey = only.name + only.desc;
			claim.put(oldKey, only);
			claims.merge(newKey, 1, Integer::sum);
		}
		for (Map.Entry<String, SyntheticInfo> e : claim.entrySet()) {
			SyntheticInfo ni = e.getValue();
			if (claims.getOrDefault(ni.name + ni.desc, 0) != 1) continue;   // 一个目标被多抢：全部拒绝
			SyntheticInfo oi = findOldByKey(ctx, e.getKey());
			if (oi == null) continue;
			ParamSource[] plan = planBridge(oi, ni, newCn);
			if (plan == null) continue;
			String targetName = finalName(ctx, ni);
			String target = targetName + ni.desc;
			ctx.bridges.put(e.getKey(), new BridgeSpec(oi.desc, targetName, ni.desc, plan,
				oi.isStatic(), ni.isStatic(),
				locationOf(ctx.currentClass, oi.name, oi.desc) + " -> " + target));
			// 有合成位就单独留痕：这是一次"桥替老实例重新造了一个值"，属于可观测的行为变化
			// （不是简单搬运），排查时最想知道的就是"哪个位置、从哪来"。
			StringBuilder synth = new StringBuilder();
			for (int j = 0; j < plan.length; j++) {
				if (plan[j].kind == ParamSource.OLD) continue;
				if (synth.length() > 0) synth.append(", ");
				synth.append("new[").append(j).append("]=").append(plan[j].describe());
			}
			if (synth.length() > 0) {
				TEST_EVENTS.add(new AlignmentEvent("BRIDGE_SYNTH", oi.name, ni.name,
					ctx.currentClass + "#" + e.getKey() + " " + synth));
				RemoldAgent.info("[LambdaAligner] 捕获桥重放新增捕获 " + ctx.currentClass + "#"
				                 + e.getKey() + "：" + synth);
			}
			TEST_EVENTS.add(new AlignmentEvent("BRIDGE", oi.name, ni.name,
				ctx.currentClass + "#" + e.getKey() + " -> " + target));
			RemoldAgent.info("[LambdaAligner] 捕获桥 " + ctx.currentClass + "#" + e.getKey()
			                 + " -> " + target + "（捕获表 " + oi.captured.size() + " → "
			                 + ni.captured.size() + "，旧实例继续可用）");
		}

		// ---- 2. 第二轮：上一轮建好的桥，把目标挪到本轮的名字上并重生成 ----
		for (SyntheticInfo oi : allOld) {
			if (!oi.bridge) continue;
			String oldKey = oi.name + oi.desc;
			if (presentKeys.contains(oldKey)) continue;
			MethodNode mn = findMethod(oldCn, oi.name, oi.desc);
			if (mn == null) continue;
			BridgeDelegate bd = getBridgeDelegate(mn);
			if (bd == null) {
				dbg(() -> "BRIDGE unrecognized body: " + oldKey);
				continue;                                          // 认不出：让它按幽灵处理
			}
			SyntheticInfo prevTarget = findOldByKey(ctx, bd.target.name + bd.target.desc);
			if (prevTarget == null) {
				dbg(() -> "BRIDGE target vanished: " + oldKey + " -> " + bd.target.name + bd.target.desc);
				continue;
			}
			// 配对关系只记在新侧（pair 里 ni.matchedWith = oi），反向要自己找。
			SyntheticInfo now = findNewForOld(ctx, prevTarget);
			if (now == null) {
				dbg(() -> "BRIDGE target unpaired: " + oldKey + " -> " + bd.target.name + bd.target.desc);
				continue;                                          // 目标真没了
			}
			if (now.ghost || !now.renameable) continue;
			if (presentKeys.contains(oldKey)) continue;
			// 目标的静态位若与上一轮不同，上一轮那份"搬哪些实参"的记录就不再成立
			// （比如它又用回了 this，目标从静态变回实例，而桥当时把接收者丢掉了）。
			// 这种情况不猜，退回幽灵桩。
			if (bd.targetStatic() != now.isStatic()) {
				dbg(() -> "BRIDGE target staticness changed: " + oldKey);
				continue;
			}
			// 上一轮记下的"每一位从哪来"必须正好覆盖本轮目标形参，否则重放会少压/多压实参。
			if (bd.sources().length != now.captured.size()) {
				dbg(() -> "BRIDGE source plan size mismatch: " + oldKey
					+ " plan=" + bd.sources().length + " want=" + now.captured.size());
				continue;
			}
			String targetName = finalName(ctx, now);
			String target = targetName + now.desc;
			ctx.bridges.put(oldKey, new BridgeSpec(oi.desc, targetName, now.desc, bd.sources(),
				oi.isStatic(), now.isStatic(),
				locationOf(ctx.currentClass, oi.name, oi.desc) + " -> " + target));
			TEST_EVENTS.add(new AlignmentEvent("BRIDGE_REPOINT", oi.name, now.name,
				ctx.currentClass + "#" + oldKey + " -> " + target));
		}
	}

	/**
	 * 该方法在本轮产物里的<b>最终</b>名字。
	 *
	 * <p>阶段二可能给未配对的新方法分配避障名，而改名只记在 {@code ctx.renameMap} 里、
	 * {@link SyntheticInfo#name} 保持编译期原名。桥的目标必须用最终名字——否则会指回自己
	 * （键相同就是无限递归）。</p>
	 */
	private static String finalName(MatchContext ctx, SyntheticInfo info) {
		String renamed = ctx.renameMap.get(info.name + info.desc);
		return renamed != null ? renamed : info.name;
	}

	/**
	 * 判定能否建桥，并给出"新形参 → 老形参"的映射。
	 *
	 * <p>返回 {@code null} 表示不建桥。映射按可靠性分三级：</p>
	 * <ol>
	 *   <li><b>形参名</b>（最硬）：两侧都带 {@code -g} 时，源码里的变量名直接就是身份。
	 *       名字不随宿主方法增删局部变量而变，所以宿主签名变了也照样能定映射；</li>
	 *   <li><b>宿主形参槽号</b>：只对"捕获来自宿主形参"成立——形参槽在宿主描述符不变时稳定，
	 *       而<b>局部变量槽会随前序局部增删整体位移</b>，槽号相等不构成证明；</li>
	 *   <li><b>类型唯一性</b>（兜底）：每个新形参在旧捕获表里恰好一个同类型位置。</li>
	 * </ol>
	 *
	 * <p>三级都必须满足"每个新形参恰好一个来源 + 整体内射"。内射是硬要求：两个新形参
	 * 共用一个旧值意味着它们是<b>两个不同的变量</b>，值不一定相同，喂同一个值是静默错值。</p>
	 */
	private static ParamSource[] planBridge(SyntheticInfo oi, SyntheticInfo ni, ClassNode newCn) {
		if (!bridgeGatesHold(oi, ni)) return null;
		int[] legacy = legacyMapping(oi, ni);
		if (legacy != null) {
			ParamSource[] plan = new ParamSource[legacy.length];
			for (int j = 0; j < legacy.length; j++) plan[j] = ParamSource.old(legacy[j]);
			return plan;
		}
		return planWithSynthesis(oi, ni, newCn);
	}

	/**
	 * 建桥的硬前提。任一不成立就退回幽灵桩（行为与引入本机制前完全一致）。
	 */
	private static boolean bridgeGatesHold(SyntheticInfo oi, SyntheticInfo ni) {
		if (oi.captured == null || ni.captured == null) return false;
		// 方向限制：
		//  - 实例 → 静态：允许。这正是"删掉 this 引用"的形态（新方法体物理上读不到接收者），
		//    桥丢弃接收者转发即可，没有伪造值。
		//  - 静态 → 实例：拒绝。那等于"新增一个接收者捕获"，老实例里没有这个值，
		//    桥只能编 null 塞进去，而新体必然读它（否则 javac 不会把它写成实例方法）。
		if (oi.isStatic() && !ni.isStatic()) return false;
		if (!sameHost(oi, ni)) return false;
		if (!Objects.equals(oi.fiType, ni.fiType)) return false;
		if (!Objects.equals(oi.samSig, ni.samSig)) return false;
		if (!Objects.equals(oi.instSig, ni.instSig)) return false;
		if (!layoutHolds(oi) || !layoutHolds(ni)) return false;
		// 空捕获表下任何"证明"都是空洞的：两侧都空，映射算出来是个空数组，证明通过却什么也没证。
		// 这类 lambda 没有实参可搬，建桥没有任何意义，一律不建（必须放在按名字映射之前——
		// 名字映射对 0 个捕获会返回一个合法的空数组，正好绕过等长闸）。
		//
		// 这条也挡住了"零捕获 → 新增捕获"：老实例完全没有状态，谈"同一个 lambda"没有任何依据
		// （LambdaAlignTortureTest 的"删掉中间层"就是靠这条不被误接成桥）。
		return !oi.captured.isEmpty();
	}

	/**
	 * 纯"搬值"的映射：新捕获表里的每一项都来自一个老捕获。
	 *
	 * <p>覆盖删除 / 重排 / 子序列，<b>不允许</b>新增。返回 {@code null} 表示这一路走不通，
	 * 由 {@link #planWithSynthesis} 尝试对多出来的那几位做纯源重放。</p>
	 */
	private static int[] legacyMapping(SyntheticInfo oi, SyntheticInfo ni) {
		if (ni.captured.size() > oi.captured.size()) return null;

		int[] byName = mappingByNames(oi, ni);
		if (byName != null) return byName;

		// 槽号可比的前提是宿主描述符完全一致，否则槽号整体位移。
		int[] bySlot = null;
		if (oi.hostKey != null && oi.hostKey.equals(ni.hostKey)) {
			bySlot = mappingByHostSlots(oi, ni);
		}
		// 捕获数"不变"只可能是重排，而重排本身不构成"这两个是同一个 lambda"的证据：
		// 同一个宿主里换个体、换个返回值的 lambda 也会落在同一 SAM 上。所以等长时
		// 必须有证明（名字或形参槽），且不能是空捕获表（空表下证明是空洞的）。
		// 变少则不同：少了一个捕获本身就是要桥接的那类编辑，允许退回类型推断。
		if (ni.captured.size() == oi.captured.size()) {
			if (ni.captured.isEmpty()) return null;
			return bySlot;
		}
		return bySlot != null ? bySlot : mappingByType(oi, ni);
	}

	/**
	 * 新增捕获的<b>纯源重放</b>：把宿主方法里"这个变量从哪来"回溯出来，在桥里重放一遍。
	 *
	 * <p>只在<b>两侧名字齐全</b>时尝试。没有名字就无法区分"这一位是新增的"和"老变量换了名字"，
	 * 合成会退化成猜值。名字齐全时先把仍然存在的老捕获按名字对上（沿用老实例存下来的值），
	 * 剩下的新捕获才允许重放——所以"删一个 + 加一个"这种混合编辑也能处理。</p>
	 *
	 * <p>重放的三类纯源见 {@link ParamSource}：常量、{@code final} 静态字段、{@code final}
	 * 实例字段（后者要求桥本身持有 {@code this}）。其余一律拒绝：非 final 字段会在创建之后
	 * 被改写，读它等于编一个"看起来像"的值。</p>
	 */
	private static ParamSource[] planWithSynthesis(SyntheticInfo oi, SyntheticInfo ni, ClassNode newCn) {
		List<String> on = oi.captureNames, nn = ni.captureNames;
		if (on == null || nn == null) return null;
		int n = oi.captured.size(), m = ni.captured.size();
		if (on.size() != n || nn.size() != m) return null;
		if (ni.hostMethod == null || ni.captureSlots == null) return null;
		if (ni.captureSlots.size() != m) return null;
		int of = oi.firstCapture(), nf = ni.firstCapture();
		if (m <= nf) return null;

		ParamSource[] plan = new ParamSource[m];
		boolean[] used = new boolean[n];
		if (of == 1 && nf == 1) {
			plan[0] = ParamSource.old(0);                          // 接收者 ↔ 接收者
			used[0] = true;
		}

		boolean synthesized = false;
		for (int j = nf; j < m; j++) {
			String nm = nn.get(j);
			if (nm == null) return null;
			int only = -1;
			for (int i = of; i < n; i++) {
				if (used[i] || !nm.equals(on.get(i))) continue;
				if (!oi.captured.get(i).equals(ni.captured.get(j))) return null;   // 同名换型：矛盾
				if (only >= 0) return null;                                       // 同名多处：歧义
				only = i;
			}
			if (only >= 0) {
				plan[j] = ParamSource.old(only);
				used[only] = true;
				continue;
			}
			ParamSource src = synthesize(ni.hostMethod, ni.captureSlots.get(j),
				ni.hostParamEndSlot, !oi.isStatic(), newCn, ni.captured.get(j));
			if (src == null) return null;
			plan[j] = src;
			synthesized = true;
		}
		// 一位都没合成，说明 legacy 路径失败是别的原因（矛盾/歧义），不要在这里兜。
		if (!synthesized) return null;

		// 每一位的产出类型必须与目标形参逐位相等：写错类型在验证期直接失败，宁可退回幽灵桩。
		Type[] want = Type.getArgumentTypes(ni.desc);
		for (int j = nf; j < m; j++) {
			if (!plan[j].produces(want[j - nf])) return null;
		}
		return plan;
	}

	/**
	 * 回溯"宿主方法里槽 {@code slot} 的值从哪来"，把它变成一个可在桥里重放的纯源。
	 *
	 * <p>依据 JLS §15.27.2：被捕获的局部变量是 effectively final，所以在宿主方法里对该槽
	 * 只有<b>唯一一处</b> {@code xSTORE}。多于一处说明槽被别的变量复用了，唯一性不成立，拒绝。</p>
	 *
	 * <p>只认"紧邻 store 的一到两条指令"：常量、{@code GETSTATIC}、{@code ALOAD 0; GETFIELD}。
	 * 任何算式、方法调用、读别的局部变量都不认——那些值依赖创建时的栈帧，桥执行时已经不存在。</p>
	 *
	 * @param bridgeHasThis 桥自身是不是实例方法（{@code this.field} 需要槽 0 的接收者）
	 */
	private static ParamSource synthesize(MethodNode host, int slot, int hostParamEndSlot,
	                                      boolean bridgeHasThis, ClassNode newCn, String wantDesc) {
		if (host == null || slot < 0) return null;
		List<AbstractInsnNode> real = realInsns(host);
		if (real == null) return null;

		// 宿主形参：值由调用方给，方法体里没有"它从哪来"——唯一例外是构造器把它存进了
		// 不可变字段（`this.cfg = cfg`），那样桥可以靠 `ALOAD 0; GETFIELD` 把它取回来。
		if (hostParamEndSlot >= 0 && slot < hostParamEndSlot) {
			return paramViaField(host, real, slot, wantDesc, bridgeHasThis, newCn);
		}

		int storeAt = storeOf(real, slot);
		if (storeAt < 1) return null;

		AbstractInsnNode prev = real.get(storeAt - 1);
		// 1) 常量：值恒定，重放恒等价（`int margin = 10` 这类）。
		if (isConstPush(prev)) {
			Object c = constValue(prev);
			if (c == NOT_A_CONSTANT) return null;
			return ParamSource.constant(c);
		}
		// 2) 静态字段：只有 final 才保证"桥读到的还是创建时那个值"。
		if (prev instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC) {
			return isFinalField(newCn, f) ? ParamSource.getStatic(f.owner, f.name, f.desc) : null;
		}
		// 3) 当前实例的字段：ALOAD 0; GETFIELD。桥必须自己持有 this，否则无从取接收者。
		if (bridgeHasThis && storeAt >= 2
		    && real.get(storeAt - 2) instanceof VarInsnNode a
		    && a.getOpcode() == Opcodes.ALOAD && a.var == 0
		    && prev instanceof FieldInsnNode g && g.getOpcode() == Opcodes.GETFIELD) {
			return isFinalField(newCn, g) ? ParamSource.getField(g.owner, g.name, g.desc) : null;
		}
		// 4) 纯算式：整段在编译期算成一个常量（`float scaled = pad * 2;` 这类）。
		//    只认算术/位运算/类型转换，且叶子必须都能折成常量；任何一条不满足就整体放弃。
		Object folded = foldSlot(host, real, slot, 0, new HashSet<>());
		return folded == NOT_A_CONSTANT ? null : ParamSource.constant(folded);
	}

	/**
	 * 构造器参数 → 不可变字段：{@code this.f = cfg} 之后，桥用 {@code ALOAD 0; GETFIELD f}
	 * 就能把"当年那个构造器参数"的值取回来。
	 *
	 * <p>为什么只需要证明字段 {@code final}：JVM 保证 {@code final} 字段只在构造器里被写一次，
	 * 所以"构造完成后读它"与"构造时参数的值"必然相同。这也正是 {@code InitFix.scanParamFields}
	 * 的条件 A——比它的条件 B（非 final 但 private + 全 Nest 单处 PUTFIELD）更严，但不需要
	 * 扫 Nest。</p>
	 *
	 * <p><b>前提是桥自己持有 {@code this}</b>：静态桥没有接收者，拿不到那个实例。此时不猜。</p>
	 */
	private static ParamSource paramViaField(MethodNode host, List<AbstractInsnNode> real, int slot,
	                                         String wantDesc, boolean bridgeHasThis, ClassNode newCn) {
		if (!"<init>".equals(host.name) || !bridgeHasThis) return null;
		String paramDesc = paramDescAt(host, slot);
		if (paramDesc == null || !paramDesc.equals(wantDesc)) return null;

		FieldInsnNode only = null;
		for (int i = 0; i + 2 < real.size(); i++) {
			AbstractInsnNode a = real.get(i), b = real.get(i + 1), c = real.get(i + 2);
			if (!(a instanceof VarInsnNode va) || va.getOpcode() != Opcodes.ALOAD || va.var != 0) continue;
			if (!(b instanceof VarInsnNode vb) || vb.var != slot || !isLoadOpcode(vb.getOpcode())) continue;
			if (!(c instanceof FieldInsnNode fc) || fc.getOpcode() != Opcodes.PUTFIELD) continue;
			if (!fc.desc.equals(paramDesc)) continue;
			// 同一个参数存进了多个字段：哪个才是"那个值"不再唯一，不猜。
			if (only != null && !(only.name.equals(fc.name) && only.desc.equals(fc.desc))) return null;
			only = fc;
		}
		if (only == null || !isFinalField(newCn, only)) return null;
		return ParamSource.getField(only.owner, only.name, only.desc);
	}

	/** 宿主方法第 {@code slot} 个形参的描述符；不是形参则 {@code null}。 */
	private static String paramDescAt(MethodNode host, int slot) {
		int s = (host.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
		for (Type t : Type.getArgumentTypes(host.desc)) {
			if (s == slot) return t.getDescriptor();
			s += t.getSize();
		}
		return null;
	}

	/** 该槽在方法体里唯一一处 {@code xSTORE} 的下标；不是唯一一处则 {@code -1}。 */
	private static int storeOf(List<AbstractInsnNode> real, int slot) {
		int at = -1, n = 0;
		for (int i = 0; i < real.size(); i++) {
			AbstractInsnNode x = real.get(i);
			if (x instanceof VarInsnNode v && isStoreOpcode(v.getOpcode()) && v.var == slot) {
				n++;
				at = i;
			}
		}
		return n == 1 ? at : -1;
	}

	/** 折叠深度上限：链式局部变量最多跟四层，再深就放弃（宁可漏补）。 */
	private static final int FOLD_DEPTH = 4;

	/**
	 * 把"槽 {@code slot} 那唯一一处 store 的生产者"在编译期算成一个常量。
	 *
	 * <p>为什么是"算成常量"而不是"把原指令序列搬进桥"：搬序列要保证栈形状、类型、操作码
	 * 全部一致，任何一处错位都是验证期失败或静默错值；而算成一个常量只有一种失败模式——
	 * 算不出来就返回 {@link #NOT_A_CONSTANT}，调用方退回幽灵桩。这也正是 javac 自己会对
	 * 常量表达式做的事。</p>
	 *
	 * <p>求值语义必须与 JVM 一致：`int`/`long` 的环绕、`float`/`double` 的 IEEE 754（Java 17
	 * 起一律 strictfp）都与字节码逐位相同，所以"用 Java 算一遍"是等价的，不是近似。</p>
	 */
	private static Object foldSlot(MethodNode host, List<AbstractInsnNode> real, int slot,
	                               int depth, Set<Integer> seen) {
		if (depth > FOLD_DEPTH || !seen.add(slot)) return NOT_A_CONSTANT;
		int storeAt = storeOf(real, slot);
		if (storeAt < 1) return NOT_A_CONSTANT;
		return foldRange(host, real, storeAt, depth, seen);
	}

	/**
	 * 折叠"紧邻 {@code storeAt} 之前的那段表达式"。
	 *
	 * <p>先用反向栈高度求出表达式起点（{@code need = need - pushed + consumed}），再正向
	 * 在一条小栈上求值。反向定界保证只碰这一段，不会把前面的语句卷进来。</p>
	 */
	private static Object foldRange(MethodNode host, List<AbstractInsnNode> real, int storeAt,
	                                int depth, Set<Integer> seen) {
		int need = 1, start = storeAt - 1;
		while (need > 0) {
			if (start < 0) return NOT_A_CONSTANT;
			int[] eff = stackEffect(real.get(start));
			if (eff == null) return NOT_A_CONSTANT;              // 不认识的指令：整段放弃
			need = need - eff[0] + eff[1];
			start--;
		}
		start++;
		ArrayDeque<Object> st = new ArrayDeque<>();
		for (int i = start; i < storeAt; i++) {
			if (!evalOne(host, real, real.get(i), st, depth, seen)) return NOT_A_CONSTANT;
		}
		return st.size() == 1 ? st.pop() : NOT_A_CONSTANT;
	}

	private static boolean evalOne(MethodNode host, List<AbstractInsnNode> real, AbstractInsnNode n,
	                               ArrayDeque<Object> st, int depth, Set<Integer> seen) {
		int op = n.getOpcode();
		if (isConstPush(n)) {
			Object c = constValue(n);
			if (c == NOT_A_CONSTANT) return false;
			st.push(c);
			return true;
		}
		if (n instanceof VarInsnNode v && isLoadOpcode(op)) {
			if (op == Opcodes.ALOAD) return false;               // 引用型局部变量：不在折叠范围
			Object c = foldSlot(host, real, v.var, depth + 1, seen);
			if (c == NOT_A_CONSTANT) return false;
			st.push(c);
			return true;
		}
		try {
			return evalOp(op, st);
		} catch (ArithmeticException e) {
			return false;                                        // 除零：原代码创建时就会抛，不重放
		}
	}

	/** 一段指令的栈效应 {@code {pushed, consumed}}；不在白名单内返回 {@code null}。 */
	private static int[] stackEffect(AbstractInsnNode n) {
		int op = n.getOpcode();
		if (isConstPush(n)) return new int[]{1, 0};
		if (n instanceof VarInsnNode) {
			if (isLoadOpcode(op)) return new int[]{1, 0};
			if (isStoreOpcode(op)) return new int[]{0, 1};
			return null;
		}
		if (isBinaryOp(op)) return new int[]{1, 2};
		if (isUnaryOp(op)) return new int[]{1, 1};
		return null;
	}

	private static boolean isLoadOpcode(int op) {
		return op >= Opcodes.ILOAD && op <= Opcodes.ALOAD;
	}

	private static boolean isBinaryOp(int op) {
		switch (op) {
			case Opcodes.IADD: case Opcodes.ISUB: case Opcodes.IMUL: case Opcodes.IDIV:
			case Opcodes.IREM: case Opcodes.ISHL: case Opcodes.ISHR: case Opcodes.IUSHR:
			case Opcodes.IAND: case Opcodes.IOR:  case Opcodes.IXOR:
			case Opcodes.LADD: case Opcodes.LSUB: case Opcodes.LMUL: case Opcodes.LDIV:
			case Opcodes.LREM: case Opcodes.LSHL: case Opcodes.LSHR: case Opcodes.LUSHR:
			case Opcodes.LAND: case Opcodes.LOR:  case Opcodes.LXOR:
			case Opcodes.FADD: case Opcodes.FSUB: case Opcodes.FMUL: case Opcodes.FDIV:
			case Opcodes.FREM:
			case Opcodes.DADD: case Opcodes.DSUB: case Opcodes.DMUL: case Opcodes.DDIV:
			case Opcodes.DREM:
				return true;
			default:
				return false;
		}
	}

	private static boolean isUnaryOp(int op) {
		switch (op) {
			case Opcodes.INEG: case Opcodes.LNEG: case Opcodes.FNEG: case Opcodes.DNEG:
			case Opcodes.I2L: case Opcodes.I2F: case Opcodes.I2D:
			case Opcodes.L2I: case Opcodes.L2F: case Opcodes.L2D:
			case Opcodes.F2I: case Opcodes.F2L: case Opcodes.F2D:
			case Opcodes.D2I: case Opcodes.D2L: case Opcodes.D2F:
			case Opcodes.I2B: case Opcodes.I2C: case Opcodes.I2S:
				return true;
			default:
				return false;
		}
	}

	/** 在 {@code st} 上执行一条算术/转换指令；类型不匹配或栈不够时返回 {@code false}。 */
	private static boolean evalOp(int op, ArrayDeque<Object> st) {
		// ---- int 二元 ----
		if (op >= Opcodes.IADD && op <= Opcodes.IXOR) {
			Integer b = popInt(st), a = popInt(st);
			if (a == null || b == null) return false;
			int x = a, y = b;
			switch (op) {
				case Opcodes.IADD: st.push(x + y); return true;
				case Opcodes.ISUB: st.push(x - y); return true;
				case Opcodes.IMUL: st.push(x * y); return true;
				case Opcodes.IDIV: st.push(x / y); return true;
				case Opcodes.IREM: st.push(x % y); return true;
				case Opcodes.ISHL: st.push(x << y); return true;
				case Opcodes.ISHR: st.push(x >> y); return true;
				case Opcodes.IUSHR: st.push(x >>> y); return true;
				case Opcodes.IAND: st.push(x & y); return true;
				case Opcodes.IOR: st.push(x | y); return true;
				case Opcodes.IXOR: st.push(x ^ y); return true;
				default: return false;
			}
		}
		// ---- long 二元（移位量是 int） ----
		if (op >= Opcodes.LADD && op <= Opcodes.LXOR) {
			Long b = popLong(st), a = popLong(st);
			if (a == null || b == null) return false;
			long x = a, y = b;
			switch (op) {
				case Opcodes.LADD: st.push(x + y); return true;
				case Opcodes.LSUB: st.push(x - y); return true;
				case Opcodes.LMUL: st.push(x * y); return true;
				case Opcodes.LDIV: st.push(x / y); return true;
				case Opcodes.LREM: st.push(x % y); return true;
				case Opcodes.LSHL: st.push(x << (int) y); return true;
				case Opcodes.LSHR: st.push(x >> (int) y); return true;
				case Opcodes.LUSHR: st.push(x >>> (int) y); return true;
				case Opcodes.LAND: st.push(x & y); return true;
				case Opcodes.LOR: st.push(x | y); return true;
				case Opcodes.LXOR: st.push(x ^ y); return true;
				default: return false;
			}
		}
		// ---- float 二元 ----
		if (op >= Opcodes.FADD && op <= Opcodes.FREM) {
			Float b = popFloat(st), a = popFloat(st);
			if (a == null || b == null) return false;
			float x = a, y = b;
			switch (op) {
				case Opcodes.FADD: st.push(x + y); return true;
				case Opcodes.FSUB: st.push(x - y); return true;
				case Opcodes.FMUL: st.push(x * y); return true;
				case Opcodes.FDIV: st.push(x / y); return true;
				case Opcodes.FREM: st.push(x % y); return true;
				default: return false;
			}
		}
		// ---- double 二元 ----
		if (op >= Opcodes.DADD && op <= Opcodes.DREM) {
			Double b = popDouble(st), a = popDouble(st);
			if (a == null || b == null) return false;
			double x = a, y = b;
			switch (op) {
				case Opcodes.DADD: st.push(x + y); return true;
				case Opcodes.DSUB: st.push(x - y); return true;
				case Opcodes.DMUL: st.push(x * y); return true;
				case Opcodes.DDIV: st.push(x / y); return true;
				case Opcodes.DREM: st.push(x % y); return true;
				default: return false;
			}
		}
		// ---- 一元：取负与类型转换 ----
		switch (op) {
			case Opcodes.INEG: { Integer a = popInt(st); if (a == null) return false; st.push(-a); return true; }
			case Opcodes.LNEG: { Long a = popLong(st); if (a == null) return false; st.push(-a); return true; }
			case Opcodes.FNEG: { Float a = popFloat(st); if (a == null) return false; st.push(-a); return true; }
			case Opcodes.DNEG: { Double a = popDouble(st); if (a == null) return false; st.push(-a); return true; }
			case Opcodes.I2L: { Integer a = popInt(st); if (a == null) return false; st.push((long) a); return true; }
			case Opcodes.I2F: { Integer a = popInt(st); if (a == null) return false; st.push((float) a); return true; }
			case Opcodes.I2D: { Integer a = popInt(st); if (a == null) return false; st.push((double) a); return true; }
			case Opcodes.L2I: { Long a = popLong(st); if (a == null) return false; st.push((int) (long) a); return true; }
			case Opcodes.L2F: { Long a = popLong(st); if (a == null) return false; st.push((float) (long) a); return true; }
			case Opcodes.L2D: { Long a = popLong(st); if (a == null) return false; st.push((double) (long) a); return true; }
			case Opcodes.F2I: { Float a = popFloat(st); if (a == null) return false; st.push((int) (float) a); return true; }
			case Opcodes.F2L: { Float a = popFloat(st); if (a == null) return false; st.push((long) (float) a); return true; }
			case Opcodes.F2D: { Float a = popFloat(st); if (a == null) return false; st.push((double) (float) a); return true; }
			case Opcodes.D2I: { Double a = popDouble(st); if (a == null) return false; st.push((int) (double) a); return true; }
			case Opcodes.D2L: { Double a = popDouble(st); if (a == null) return false; st.push((long) (double) a); return true; }
			case Opcodes.D2F: { Double a = popDouble(st); if (a == null) return false; st.push((float) (double) a); return true; }
			case Opcodes.I2B: { Integer a = popInt(st); if (a == null) return false; st.push((int) (byte) (int) a); return true; }
			case Opcodes.I2C: { Integer a = popInt(st); if (a == null) return false; st.push((int) (char) (int) a); return true; }
			case Opcodes.I2S: { Integer a = popInt(st); if (a == null) return false; st.push((int) (short) (int) a); return true; }
			default: return false;
		}
	}

	private static Integer popInt(ArrayDeque<Object> st) {
		Object o = st.poll();
		return o instanceof Integer ? (Integer) o : null;
	}

	private static Long popLong(ArrayDeque<Object> st) {
		Object o = st.poll();
		return o instanceof Long ? (Long) o : null;
	}

	private static Float popFloat(ArrayDeque<Object> st) {
		Object o = st.poll();
		return o instanceof Float ? (Float) o : null;
	}

	private static Double popDouble(ArrayDeque<Object> st) {
		Object o = st.poll();
		return o instanceof Double ? (Double) o : null;
	}

	/** 字段必须在本类里声明且带 {@code ACC_FINAL}；继承来的字段看不到定义，不猜。 */
	private static boolean isFinalField(ClassNode cn, FieldInsnNode f) {
		if (cn == null || f == null || !cn.name.equals(f.owner)) return false;
		for (FieldNode fn : cn.fields) {
			if (fn.name.equals(f.name) && fn.desc.equals(f.desc)) {
				return (fn.access & Opcodes.ACC_FINAL) != 0;
			}
		}
		return false;
	}

	private static boolean isStoreOpcode(int op) {
		return op == Opcodes.ISTORE || op == Opcodes.LSTORE || op == Opcodes.FSTORE
		    || op == Opcodes.DSTORE || op == Opcodes.ASTORE;
	}

	/** 单条指令就能把值压栈的"常量"形态（不含 {@code GETSTATIC} 等读状态的）。 */
	private static boolean isConstPush(AbstractInsnNode n) {
		int op = n.getOpcode();
		return op == Opcodes.ACONST_NULL
		    || (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5)
		    || op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1
		    || (op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2)
		    || op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1
		    || op == Opcodes.BIPUSH || op == Opcodes.SIPUSH
		    || n instanceof LdcInsnNode;
	}

	/** "不是常量"的哨兵——{@code ACONST_NULL} 的合法取值是 {@code null}，不能拿它当失败信号。 */
	private static final Object NOT_A_CONSTANT = new Object();

	private static Object constValue(AbstractInsnNode n) {
		int op = n.getOpcode();
		if (op == Opcodes.ACONST_NULL) return null;
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
		if (op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1) return (long) (op - Opcodes.LCONST_0);
		if (op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2) return (float) (op - Opcodes.FCONST_0);
		if (op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1) return (double) (op - Opcodes.DCONST_0);
		if (n instanceof IntInsnNode i) return i.operand;
		if (n instanceof LdcInsnNode l) {
			Object c = l.cst;
			// Type / Handle / ConstantDynamic 不是"值"，重放它们没有意义。
			if (c instanceof Integer || c instanceof Long || c instanceof Float
			    || c instanceof Double || c instanceof String) return c;
		}
		return NOT_A_CONSTANT;
	}

	/**
	 * 按源码变量名建立映射（两侧都必须带 {@code -g}）。
	 *
	 * <p>名字是"同一个变量"最硬的证据：它不随宿主方法里增删局部变量而位移，也不随宿主签名
	 * 变化而失效——这正是槽号做不到的两种情形。</p>
	 *
	 * <p>要求严格：只要有一个捕获位缺名字就整体放弃（不能"有的按名、有的按猜"）；
	 * 同名换型视为矛盾直接拒绝。实例 lambda 的第 0 位是接收者，两侧固定对齐。</p>
	 */
	private static int[] mappingByNames(SyntheticInfo oi, SyntheticInfo ni) {
		List<String> on = oi.captureNames, nn = ni.captureNames;
		if (on == null || nn == null) return null;
		int n = oi.captured.size(), m = ni.captured.size();
		if (on.size() != n || nn.size() != m) return null;
		if (m > n) return null;
		int[] map = new int[m];
		boolean[] used = new boolean[n];
		// 两侧各自跳过接收者：静态位可以不同（实例 → 静态是"删掉 this"），此时新侧没有接收者。
		int of = oi.firstCapture(), nf = ni.firstCapture();
		if (of == 1 && nf == 1) {
			map[0] = 0;                                            // 接收者 ↔ 接收者
			used[0] = true;
		}
		for (int j = nf; j < m; j++) {
			String name = nn.get(j);
			if (name == null) return null;
			int only = -1;
			for (int i = of; i < n; i++) {
				if (!name.equals(on.get(i))) continue;
				if (!oi.captured.get(i).equals(ni.captured.get(j))) return null;   // 同名换型：矛盾
				if (only >= 0) return null;
				only = i;
			}
			if (only < 0 || used[only]) return null;
			used[only] = true;
			map[j] = only;
		}
		return map;
	}

	/**
	 * 按<b>宿主形参</b>槽号证明映射（宿主描述符必须完全一致）。
	 *
	 * <p>只认宿主形参：局部变量槽会随前序局部变量的增删整体位移，槽号相等说明不了是同一个
	 * 变量。任一捕获来自宿主局部变量时整体放弃，退回类型唯一性。</p>
	 *
	 * @return {@code 新形参下标 → 老形参下标}；不可用或无解时 {@code null}
	 */
	private static int[] mappingByHostSlots(SyntheticInfo oi, SyntheticInfo ni) {
		if (oi.captureSlots == null || ni.captureSlots == null) return null;
		if (oi.captureSlots.size() != oi.captured.size()) return null;
		if (ni.captureSlots.size() != ni.captured.size()) return null;
		if (oi.hostParamEndSlot < 0 || ni.hostParamEndSlot < 0) return null;
		// 只认宿主形参：局部变量槽会随前序局部变量的增删整体位移，槽号相等说明不了是同一个变量。
		for (int s : oi.captureSlots) if (s >= oi.hostParamEndSlot) return null;
		for (int s : ni.captureSlots) if (s >= ni.hostParamEndSlot) return null;
		int n = oi.captured.size(), m = ni.captured.size();
		if (m > n) return null;
		int[] map = new int[m];
		boolean[] used = new boolean[n];
		int of = oi.firstCapture(), nf = ni.firstCapture();
		if (of == 1 && nf == 1) {
			// 接收者恒在槽 0（javac 把 aload_0 放在捕获序列最前）。回溯不到槽 0 说明
			// 我们对这个调用点的布局理解有误，不能拿"槽号 0 恰好相同"糊过去。
			if (oi.captureSlots.get(0) != 0 || ni.captureSlots.get(0) != 0) return null;
			map[0] = 0;
			used[0] = true;
		}
		for (int j = nf; j < m; j++) {
			int only = -1;
			for (int i = of; i < n; i++) {
				if (!oi.captureSlots.get(i).equals(ni.captureSlots.get(j))) continue;
				if (!oi.captured.get(i).equals(ni.captured.get(j))) return null;   // 同槽换型：矛盾
				if (only >= 0) return null;
				only = i;
			}
			if (only < 0 || used[only]) return null;
			used[only] = true;
			map[j] = only;
		}
		return map;
	}

	/**
	 * 按类型唯一性推断映射（退路）。每个新形参必须在旧捕获表里<b>恰好</b>命中一个同类型位置。
	 *
	 * @return {@code 新形参下标 → 老形参下标}；有歧义或无解时 {@code null}
	 */
	private static int[] mappingByType(SyntheticInfo oi, SyntheticInfo ni) {
		List<String> oldL = oi.captured, newL = ni.captured;
		int n = oldL.size(), m = newL.size();
		if (m > n) return null;
		int[] map = new int[m];
		boolean[] used = new boolean[n];
		int of = oi.firstCapture(), nf = ni.firstCapture();
		if (of == 1 && nf == 1) {
			map[0] = 0;                                            // 接收者 ↔ 接收者
			used[0] = true;
		}
		for (int j = nf; j < m; j++) {
			int only = -1;
			for (int i = of; i < n; i++) {
				if (!oldL.get(i).equals(newL.get(j))) continue;
				if (only >= 0) return null;               // 同类型多个候选：歧义
				only = i;
			}
			if (only < 0 || used[only]) return null;
			used[only] = true;
			map[j] = only;
		}
		return map;
	}

	/**
	 * 布局自检：{@code 捕获表 ++ 实例化 SAM 形参} 必须恰好等于 impl 描述符。
	 *
	 * <p>成立就说明"捕获值前置、SAM 形参在后"不是假设而是每例现验的；不成立说明这个
	 * 调用点的布局另有蹊跷（编译器版本差异、或根本不是 LambdaMetafactory 产生的），
	 * 此时任何参数映射都是猜的。</p>
	 *
	 * <p><b>实例 lambda 的捕获表含接收者</b>：{@code () -> use(this.x)} 编译成
	 * {@code aload_0; indy (LC;)LRunnable;}，而 impl 是 {@code private void lambda$f$0()}
	 * ——接收者不在描述符里。所以非 static 时必须先剥掉第一个捕获再拼。</p>
	 */
	private static boolean layoutHolds(SyntheticInfo info) {
		if (info.captured == null || info.instSig == null) return false;
		List<String> caps = info.captured;
		if (!info.isStatic()) {
			if (caps.isEmpty()) return false;
			caps = caps.subList(1, caps.size());
		}
		StringBuilder sb = new StringBuilder("(");
		for (String c : caps) sb.append(c);
		for (Type t : Type.getArgumentTypes(info.instSig)) sb.append(t.getDescriptor());
		sb.append(')').append(Type.getReturnType(info.instSig).getDescriptor());
		return sb.toString().equals(info.desc);
	}

	/**
	 * 宿主门：是不是"在同一个地方创建的那个 lambda"。
	 *
	 * <p>{@code M:} 键只比<b>方法名</b>。原因是<b>被删掉的捕获变量往往就是宿主方法的形参</b>
	 * ——{@code void f(String s, int k)} 改成 {@code void f(int k)} 时，宿主描述符必然跟着变。
	 * 若要求整个 {@code hostKey} 相等，最常见的那个场景反而永远建不出桥。</p>
	 *
	 * <p>{@code L:} 键（宿主本身是个 lambda）不放松：父 lambda 的票据哈希变了，说明父自己
	 * 也没配对成功，此时谈"同一个宿主"只是猜。</p>
	 *
	 * <p><b>代价</b>：同名重载的宿主（{@code f(I)} 与 {@code f(II)} 同时存在）会共用宿主门，
	 * 只能靠 SAM 门、子序列门与"1:1 唯一"三道约束去消歧；仍有歧义时两侧都退回幽灵桩。</p>
	 */
	private static boolean sameHost(SyntheticInfo a, SyntheticInfo b) {
		if (a.hostKey == null || b.hostKey == null) return false;
		if (a.hostKey.equals(b.hostKey)) return true;
		if (!a.hostKey.startsWith("M:") || !b.hostKey.startsWith("M:")) return false;
		return hostName(a.hostKey).equals(hostName(b.hostKey));
	}

	/** {@code "M:name+desc"} → {@code "M:name"}。 */
	private static String hostName(String hostKey) {
		int paren = hostKey.indexOf('(');
		return paren < 0 ? hostKey : hostKey.substring(0, paren);
	}

	/** 桥体：{@code [标记] [aload_0] 加载保留下来的老形参 → 调目标 → 返回}。 */
	private static void injectBridgeBody(MethodVisitor mv, String className, boolean itf, BridgeSpec spec) {
		mv.visitCode();
		// 标记 + 日志。刻意走独立入口而不是 onOrphanInvoked：桥不是死孤儿，见 onBridgeInvoked。
		mv.visitLdcInsn(spec.location);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(LambdaAligner.class),
		                   "onBridgeInvoked", "(Ljava/lang/String;)V", false);
		// 目标要接收者时先把它压到栈底。此时桥必为实例方法（"静态 → 实例"已被
		// bridgeMapping 拒绝，那等于凭空造一个接收者值），槽 0 就是接收者。
		if (!spec.targetStatic) mv.visitVarInsn(Opcodes.ALOAD, 0);

		Type[] oldArgs = Type.getArgumentTypes(spec.oldDesc);
		int[]  slotOf  = new int[oldArgs.length];
		int    slot    = spec.isStatic ? 0 : 1;
		for (int i = 0; i < oldArgs.length; i++) {
			slotOf[i] = slot;
			slot += oldArgs[i].getSize();
		}
		// 捕获表下标 → 描述符形参下标。两侧的"第一个非接收者捕获"下标可以不同：
		// 实例 → 静态时老侧有接收者（oldFirst = 1）、新侧没有（newFirst = 0），
		// 老描述符的形参也从老侧的接收者之后开始数。
		int oldFirst = spec.isStatic ? 0 : 1;
		int newFirst = spec.targetStatic ? 0 : 1;
		for (int j = newFirst; j < spec.sources.length; j++) {
			ParamSource s = spec.sources[j];
			switch (s.kind) {
				case ParamSource.OLD: {
					int argIdx = s.oldIndex - oldFirst;
					if (argIdx < 0 || argIdx >= oldArgs.length) {
						throw new IllegalStateException("bridge param mapping out of range: cap=" + s.oldIndex
							+ " desc=" + spec.oldDesc);
					}
					mv.visitVarInsn(oldArgs[argIdx].getOpcode(Opcodes.ILOAD), slotOf[argIdx]);
					break;
				}
				case ParamSource.CONST:
					if (s.constant == null) mv.visitInsn(Opcodes.ACONST_NULL);
					else mv.visitLdcInsn(s.constant);
					break;
				case ParamSource.GETSTATIC:
					mv.visitFieldInsn(Opcodes.GETSTATIC, s.owner, s.name, s.desc);
					break;
				default:                                          // GETFIELD：接收者就是桥自己的 this
					mv.visitVarInsn(Opcodes.ALOAD, 0);
					mv.visitFieldInsn(Opcodes.GETFIELD, s.owner, s.name, s.desc);
					break;
			}
		}
		// itf 必须跟着宿主走：接口里的合成方法在常量池里是 InterfaceMethodref，
		// 写成 false 会得到一条指向 Methodref 的调用指令，校验/解析直接失败。
		// 目标若是实例方法，只能走 INVOKESPECIAL：它一定是本类里的 private 合成方法，
		// 而 INVOKEVIRTUAL 对 private 方法在 nestmate 之前是非法/不可解析的。
		mv.visitMethodInsn(spec.targetStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
		                   className, spec.targetName, spec.targetDesc, itf);
		mv.visitInsn(Type.getReturnType(spec.targetDesc).getOpcode(Opcodes.IRETURN));
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/** 该方法的开头是不是桥体标记（{@code LDC loc; INVOKESTATIC onBridgeInvoked}）。 */
	private static boolean isBridgeMethod(MethodNode mn) {
		List<AbstractInsnNode> real = realInsns(mn);
		if (real == null) return false;
		real = stripGhostHash(real);
		return real.size() >= 2
		       && real.get(0) instanceof LdcInsnNode
		       && real.get(1) instanceof MethodInsnNode p
		       && p.owner.equals(Type.getInternalName(LambdaAligner.class))
		       && p.name.equals("onBridgeInvoked");
	}

	/**
	 * 解析上一轮注入的桥体：跳过标记前缀，按顺序收集被加载的老形参，最后那条调用就是目标。
	 *
	 * <p>与 {@link #getPureDelegate} 的关键区别：<b>允许跳过形参槽位</b>。桥本来就是
	 * "少加载几个形参"的，若沿用那条"每个形参必须按槽位顺序恰好加载一次"的严格判据，
	 * 跳槽位的桥在下一轮会判不出 delegate，直接退化成死幽灵——第一轮的成果当场作废。</p>
	 *
	 * <p>这条放宽只作用于带桥标记的方法，不影响 {@code getPureDelegate} 对普通 lambda
	 * 的严格性（那份严格性是 {@code wasForwarding} 可信的前提）。</p>
	 */
	private static BridgeDelegate getBridgeDelegate(MethodNode mn) {
		List<AbstractInsnNode> real = realInsns(mn);
		if (real == null) return null;
		real = stripGhostHash(real);
		if (real.size() < 4) return null;
		if (!(real.get(0) instanceof LdcInsnNode)
		    || !(real.get(1) instanceof MethodInsnNode p)
		    || !p.owner.equals(Type.getInternalName(LambdaAligner.class))
		    || !p.name.equals("onBridgeInvoked")) {
			return null;
		}
		int i = 2;
		boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
		// 接收者压栈是可选的：目标若是静态方法（"删掉 this" 的桥），桥体不会压它。
		// 不能像旧版那样无条件要求 aload_0 —— 那会让这类桥在第二轮认不出来，
		// 白白退化成死幽灵。判据放在最后与目标调用指令一起交叉验证。
		boolean receiverPushed = false;
		if (!isStatic && i < real.size()
		    && real.get(i) instanceof VarInsnNode v0
		    && v0.getOpcode() == Opcodes.ALOAD && v0.var == 0
		    // 紧跟 GETFIELD 的 ALOAD 0 是"读 this 字段"的合成位，不是接收者压栈
		    && !(i + 1 < real.size()
		         && real.get(i + 1) instanceof FieldInsnNode gf0 && gf0.getOpcode() == Opcodes.GETFIELD)) {
			receiverPushed = true;
			i++;
		}
		// 形参槽位 -> 形参下标
		Type[] args = Type.getArgumentTypes(mn.desc);
		Map<Integer, Integer> paramAtSlot = new HashMap<>();
		int slot = isStatic ? 0 : 1;
		for (int k = 0; k < args.length; k++) {
			paramAtSlot.put(slot, k);
			slot += args[k].getSize();
		}

		List<ParamSource> loaded = new ArrayList<>();
		MethodInsnNode core = null;
		// 桥体把"捕获表下标"记下来（不是描述符形参下标）：实例方法的捕获表第 0 项是接收者，
		// 两种下标差 1，混用会让第二轮按错位的下标搬实参。
		int firstCap = isStatic ? 0 : 1;
		for (; i < real.size(); i++) {
			AbstractInsnNode insn = real.get(i);
			int op = insn.getOpcode();
			if (insn instanceof VarInsnNode v && op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) {
				if (op == Opcodes.ALOAD && v.var == 0 && i + 1 < real.size()
				    && real.get(i + 1) instanceof FieldInsnNode gf && gf.getOpcode() == Opcodes.GETFIELD) {
					loaded.add(ParamSource.getField(gf.owner, gf.name, gf.desc));
					i++;
					continue;
				}
				Integer pi = paramAtSlot.get(v.var);
				if (pi == null) return null;                      // 加载了非形参槽位：不是我们的桥
				int capIdx = pi + firstCap;
				// 只查重、不要求槽号递增：换序桥就是<b>故意</b>倒着压栈的（b 然后 a），
				// 旧的"必须递增"判据会把它判成不认识，第二轮直接幽灵化。
				for (ParamSource s : loaded) {
					if (s.kind == ParamSource.OLD && s.oldIndex == capIdx) return null;
				}
				loaded.add(ParamSource.old(capIdx));
			} else if (isConstPush(insn)) {
				Object c = constValue(insn);
				if (c == NOT_A_CONSTANT) return null;
				loaded.add(ParamSource.constant(c));
			} else if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC) {
				loaded.add(ParamSource.getStatic(f.owner, f.name, f.desc));
			} else if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
				// ok
			} else if (insn instanceof MethodInsnNode m) {
				if (core != null) return null;                    // 只能有一条核心调用
				core = m;
			} else {
				return null;
			}
		}
		if (core == null) return null;
		boolean targetStatic = core.getOpcode() == Opcodes.INVOKESTATIC;
		// 交叉验证：实例桥必压接收者、且只在目标也是实例方法时才压；静态桥反之。
		if (receiverPushed == targetStatic) return null;
		// 对齐 {@link BridgeSpec#sources} 的契约（长度 == 新捕获表长度）：
		// 接收者被单独压栈、没进 loaded，这里补回捕获下标 0（实例方法的接收者恒为捕获 0）。
		if (receiverPushed) loaded.add(0, ParamSource.old(0));
		return new BridgeDelegate(core, loaded.toArray(new ParamSource[0]), targetStatic);
	}

	/** 全部真实指令（跳过 {@code opcode < 0} 的伪指令）；空方法返回 {@code null}。 */
	private static List<AbstractInsnNode> realInsns(MethodNode mn) {
		if (mn.instructions == null || mn.instructions.size() == 0) return null;
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode i : mn.instructions) {
			if (i.getOpcode() >= 0) real.add(i);
		}
		return real;
	}

	/** 剥掉"生前指纹"标记 {@code LDC <long>; POP2}。 */
	private static List<AbstractInsnNode> stripGhostHash(List<AbstractInsnNode> real) {
		if (real.size() >= 2
		    && real.get(0) instanceof LdcInsnNode marker
		    && marker.cst instanceof Long
		    && real.get(1).getOpcode() == Opcodes.POP2) {
			return new ArrayList<>(real.subList(2, real.size()));
		}
		return real;
	}

	private static SyntheticInfo findOldByKey(MatchContext ctx, String key) {
		for (int gi = ctx.oldGroups.nextEntry(-1); gi != -1; gi = ctx.oldGroups.nextEntry(gi)) {
			List<SyntheticInfo> g = ctx.oldGroups.valueAt(gi);
			if (g == null) continue;
			for (SyntheticInfo oi : g) {
				if ((oi.name + oi.desc).equals(key)) return oi;
			}
		}
		return null;
	}

	/**
	 * 反查：这个旧方法在本轮被配给了哪个新方法。
	 *
	 * <p>{@link #pair} 只写 {@code ni.matchedWith = oi}（新侧单向指针），旧侧的
	 * {@code matchedWith} 始终是 {@code null}，所以从旧找新必须扫新侧。</p>
	 */
	private static SyntheticInfo findNewForOld(MatchContext ctx, SyntheticInfo oi) {
		if (oi == null || !oi.matched) return null;
		for (int gi = ctx.newGroups.nextEntry(-1); gi != -1; gi = ctx.newGroups.nextEntry(gi)) {
			List<SyntheticInfo> g = ctx.newGroups.valueAt(gi);
			if (g == null) continue;
			for (SyntheticInfo ni : g) {
				if (ni.matchedWith == oi) return ni;
			}
		}
		return null;
	}

	/** 按 {@code name + desc} 精确查（{@code nameIndex} 按名字索引，同名异描述符时会取错）。 */
	private static SyntheticInfo findByKey(LongObjectMap<List<SyntheticInfo>> groups, String name, String desc) {		for (int gi = groups.nextEntry(-1); gi != -1; gi = groups.nextEntry(gi)) {
			List<SyntheticInfo> g = groups.valueAt(gi);
			if (g == null) continue;
			for (SyntheticInfo i : g) {
				if (i.name.equals(name) && i.desc.equals(desc)) return i;
			}
		}
		return null;
	}

	private static MethodNode findMethod(ClassNode cn, String name, String desc) {
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
		}
		return null;
	}

	/**
	 * 捕获桥被调用时进入（由注入的桥体字节码调用）。
	 *
	 * <p><b>刻意不查熔断器</b>：{@link #onOrphanInvoked} 在 {@code SMART_ADAPTIVE} 下先判
	 * {@code isOrphanBreakerTriggered()} 再抛 {@link NoSuchMethodError}，而桥按构造就
	 * <b>不是</b>死孤儿——它的目标方法存在、且被三道门证明过兼容。若复用那个入口，
	 * 挂在 UI / 定时器熔断器上的回调会在转发之前被直接炸断，桥等于白建。</p>
	 */
	public static void onBridgeInvoked(String location) {
		if (LOGGED_BRIDGES.add(location)) {
			RemoldAgent.info("[LambdaAligner] bridged lambda invoked: " + location
			                 + " (capture list shrank; forwarding to the new body)");
		}
	}

	private static final Set<String> LOGGED_BRIDGES =
	 Collections.newSetFromMap(new ConcurrentHashMap<>());

	/**
	 * 将被删除的 lambda 以“空方法体或透明转发桩”的形式复活注入到新字节码中，
	 * 防止被缓存的 {@code CallSite} 抛出 {@link NoSuchMethodError}。
	 *
	 * <p>复用调用方已经解析好的 {@code oldCn} 提取孤儿方法的签名头，
	 * 避免再次读取 {@code oldBytes}。</p>
	 *
	 * <p>{@code oldCn} 是用 {@code SKIP_DEBUG | SKIP_FRAMES} 读入的，
	 * 但注入空壳只用到 {@code access} / {@code name} / {@code desc} /
	 * {@code signature} / {@code exceptions} 这些方法头字段——它们不受
	 * {@code SKIP_*} 影响。方法体（Code 属性）由 {@link #injectDummyBody}
	 * 重新生成，不会拷贝 {@code oldCn} 中的原始指令。</p>
	 * @param oldCn         已解析的旧类节点（由 {@code align} 中的 {@code scan} 提供）
	 * @param newBytes      经过重命名处理后的新版本字节码
	 * @param presentKeys   新版本里实际存在的所有方法键（{@code name + desc}）
	 * @param oldMethodRefs 旧版本中所有 indy 句柄引用的目标方法出现频次
	 * @param newMethodRefs 新版本中所有 indy 句柄引用的目标方法出现频次
	 * @param ctx           当前匹配上下文
	 * @return 注入幽灵方法后的最终字节码；若无孤儿则原样返回 {@code newBytes}
	 */
	private static byte[] resurrectOrphanedLambdas(ClassNode oldCn, byte[] newBytes,
	                                               Set<String> presentKeys,
	                                               Map<String, Integer> oldMethodRefs,
	                                               Map<String, Integer> newMethodRefs,
	                                               MatchContext ctx) {
		// 1. 旧类里的合成方法键集合，差集就是孤儿
		Set<String> orphanedKeys = new HashSet<>();
		var         oldGroups    = ctx.oldGroups;
		for (int idx = oldGroups.nextEntry(-1); idx != -1; idx = oldGroups.nextEntry(idx)) {
			List<SyntheticInfo> oldGroup = oldGroups.valueAt(idx);
			if (oldGroup == null) continue;
			for (SyntheticInfo oi : oldGroup) {
				if (!oi.renameable) continue; // 关键修复：只对真正可改名的 Lambda 系方法幽灵化，普通合成方法（如 access$/$default）让其自然缺失
				String key = oi.name + oi.desc;
				if (!presentKeys.contains(key)) orphanedKeys.add(key);
			}
		}
		if (orphanedKeys.isEmpty()) return newBytes;

		// 2. 从已解析的 oldCn 中取遗弃方法的签名头（不再二次读 oldBytes）
		List<MethodNode> toInject = new ArrayList<>();
		for (MethodNode mn : oldCn.methods) {
			String key = mn.name + mn.desc;
			if (!orphanedKeys.contains(key)) continue;
			toInject.add(mn);
		}
		if (toInject.isEmpty()) return newBytes;

		// 可观测性：幽灵化是"静默"发生在对齐期的。把这行与
		// warnPositionalMismatch 放在一起看，就能认出"删除 + 改体同时发生"这一形态
		StringBuilder ghosted = new StringBuilder();
		int bridgeCount = 0;
		for (MethodNode mn : toInject) {
			if (ctx.bridges.containsKey(mn.name + mn.desc)) {
				bridgeCount++;
				continue;
			}
			if (ghosted.length() > 0) ghosted.append(", ");
			ghosted.append(mn.name);
		}
		RemoldAgent.info("[LambdaAligner] " + oldCn.name + " 幽灵化 " + (toInject.size() - bridgeCount)
		                  + " 个方法（老 CallSite 将走熔断/降级）：" + ghosted
		                  + (bridgeCount > 0 ? "；另建 " + bridgeCount + " 座捕获桥（转发给新方法）" : ""));

		// 3. 以空壳或转发桩形式追加到新类末尾
		ClassReader cr = new ClassReader(newBytes);
		// 关键：不用 COMPUTE_FRAMES，避免 getCommonSuperClass 触发目标类加载
		ClassWriter  cw        = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
		final String className = cr.getClassName();

		// 生前指纹必须按 name+desc 精确取：幽灵可能**同名不同描述符**
		// （JDK 8 的 lambda$null$0(II) 与 lambda$null$0(I) 就是这样），
		// 而 infoByName 只按名字索引，取到哪一个取决于索引写入顺序 —— 会让标记值在轮次间抖动，
		// 破坏"第二轮产物与第一轮逐字节相同"这类不变量。
		final java.util.Map<String, Long> ghostHashByKey = new java.util.HashMap<>();
		for (int gi = ctx.oldGroups.nextEntry(-1); gi != -1; gi = ctx.oldGroups.nextEntry(gi)) {
			List<SyntheticInfo> g = ctx.oldGroups.valueAt(gi);
			if (g == null) continue;
			for (SyntheticInfo oi : g) {
				ghostHashByKey.put(oi.name + oi.desc, oi.ghost ? oi.ghostOriginalHash : oi.hash);
			}
		}

		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public void visitEnd() {
				for (MethodNode mn : toInject) {
					// 空壳方法必须带 Code 属性，强制清掉 abstract / native
					int access = mn.access & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
					MethodVisitor dummy = super.visitMethod(
					 access, mn.name, mn.desc, mn.signature,
					 mn.exceptions == null ? null : mn.exceptions.toArray(new String[0]));
					if (dummy != null) {
						// 捕获桥优先：这个老键不是"死了"，只是参数表缩水，转发给新方法即可。
						BridgeSpec bridge = ctx.bridges.get(mn.name + mn.desc);
						if (bridge != null) {
							TEST_EVENTS.add(new AlignmentEvent("BRIDGE_INJECT", mn.name, bridge.targetName,
							 className + "#" + mn.name + mn.desc));
							injectBridgeBody(dummy, className,
							 (oldCn.access & Opcodes.ACC_INTERFACE) != 0, bridge);
							continue;
						}
						Delegate delegate = getPureDelegate(mn);
						boolean canForward = false;
						if (delegate != null) {
							String targetKey = delegate.target.owner + "." + delegate.target.name + delegate.target.desc;
							int newCount = newMethodRefs.getOrDefault(targetKey, 0);
							int oldCount = oldMethodRefs.getOrDefault(targetKey, 0);
							// 门禁判定：若上一轮已是转发桩，则只要新版本仍有该方法引用即继承转发；
							// 若为首次退役为孤儿，则必须满足新版本对该方法引用的句柄数量净增（newCount > oldCount）。
							canForward = delegate.wasForwarding ? (newCount > 0) : (newCount > oldCount);
						}
						TEST_EVENTS.add(new AlignmentEvent("GHOST", mn.name, null, className + "#" + mn.name + mn.desc));
						injectDummyBody(dummy, className, mn.name, mn.desc, canForward ? delegate : null,
						 ghostHashByKey.getOrDefault(mn.name + mn.desc, 0L));
					}
				}
				super.visitEnd();
			}
		};
		cr.accept(cv, 0);
		return cw.toByteArray();
	}

	/**
	 * 生成空壳 Lambda 方法体或方法引用转发桩的指令流。
	 * <p>
	 * <b>关键设计（100% 线性无跳转分支，彻底杜绝 VerifyError）</b>：<br>
	 * 字节码中完全不使用任何 {@code IFEQ} / {@code GOTO} 等分支跳转指令和 {@link Label}。
	 * 所有的策略判定、孤儿熔断栈探测、异常抛出与日志去重均下沉到纯 Java 静态方法
	 * {@link #onOrphanInvoked(String, boolean)} 中执行。<br>
	 * <ul>
	 *   <li>若为熔断器保护的调用：Java 静态方法直接抛出 {@link NoSuchMethodError} 展开栈中断执行；</li>
	 *   <li>若为普通业务调用：若命中方法引用委托则透明转发；若非委托则线性返回类型默认值（0 / null / false）。</li>
	 * </ul>
	 * 从而在各版本 JVM 下无需 StackMapTable 即可 100% 通过类验证。
	 * </p>
	 */
	private static void injectDummyBody(MethodVisitor mv, String className, String name, String desc,
	                                    Delegate delegate, long originalHash) {
		mv.visitCode();
		boolean willForward = delegate != null;

		// 0. 生前指纹标记：LDC <long>; POP2。
		//
		// 桩体一注入，这个方法的指纹就变成空壳的指纹，再也认不出"原封不动加回来"的同一个
		// lambda。把生前指纹钉在桩体里（跟着字节码走，不依赖任何外部内存），下一轮扫描时
		// 由 readGhostOriginalHash 读回来，供 stepGhostRevive 认领。
		// 放在最前面且紧跟 POP2，保证不影响后续指令的栈平衡；getPureDelegate 的转发前缀
		// 检测会跳过这 2 条指令。
		if (originalHash != 0L) {
			mv.visitLdcInsn(originalHash);
			mv.visitInsn(Opcodes.POP2);
		}

		// 1. 调用纯 Java 静态方法：统一处理策略分发、熔断栈探测及日志去重。
		//
		// 传入拼好的 location 常量与 willForward 标志（单个 LDC + ICONST）：
		// 运行时热路径上不分配任何 String，集合查找是 O(1) 哈希查找。
		mv.visitLdcInsn(locationOf(className, name, desc));
		mv.visitInsn(willForward ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC,
		 Type.getInternalName(LambdaAligner.class),
		 "onOrphanInvoked", "(Ljava/lang/String;Z)V", false);

		// 2. 若可透明转发，则回放委托方法体指令流；否则 100% 线性无分支返回类型默认值
		if (willForward) {
			for (AbstractInsnNode insn : delegate.body) {
				insn.accept(mv);
			}
		} else {
			injectDefaultReturnValue(mv, Type.getReturnType(desc), desc);
		}
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/** location 文本：{@code com.example.Foo#lambda$build$0()V}。注入期算一次，写进字节码常量。 */
	private static String locationOf(String className, String name, String desc) {
		if (className == null || className.isEmpty()) return name + desc;
		return className.replace('/', '.') + '#' + name + desc;
	}

	private static void injectDefaultReturnValue(MethodVisitor mv, Type returnType, String desc) {
		switch (returnType.getSort()) {
			case Type.VOID:
				mv.visitInsn(Opcodes.RETURN);
				break;
			case Type.BOOLEAN:
			case Type.CHAR:
			case Type.BYTE:
			case Type.SHORT:
			case Type.INT:
				mv.visitInsn(Opcodes.ICONST_0);
				mv.visitInsn(Opcodes.IRETURN);
				break;
			case Type.FLOAT:
				mv.visitInsn(Opcodes.FCONST_0);
				mv.visitInsn(Opcodes.FRETURN);
				break;
			case Type.LONG:
				mv.visitInsn(Opcodes.LCONST_0);
				mv.visitInsn(Opcodes.LRETURN);
				break;
			case Type.DOUBLE:
				mv.visitInsn(Opcodes.DCONST_0);
				mv.visitInsn(Opcodes.DRETURN);
				break;
			case Type.OBJECT:
			case Type.ARRAY:
				mv.visitInsn(Opcodes.ACONST_NULL);
				mv.visitInsn(Opcodes.ARETURN);
				break;
			default:
				throw new IllegalStateException("Unhandled return type parsing: " + desc);
		}
	}

	private static final List<OrphanBreakerPredicate>    ORPHAN_BREAKERS       = new CopyOnWriteArrayList<>();
	private static final List<TransparentFramePredicate> TRANSPARENT_PREDICATES = new CopyOnWriteArrayList<>();

	public static void addOrphanBreaker(OrphanBreakerPredicate predicate) {
		if (predicate != null) ORPHAN_BREAKERS.add(predicate);
	}

	public static void removeOrphanBreaker(OrphanBreakerPredicate predicate) {
		ORPHAN_BREAKERS.remove(predicate);
	}

	public static void addTransparentFramePredicate(TransparentFramePredicate predicate) {
		if (predicate != null) TRANSPARENT_PREDICATES.add(predicate);
	}

	public static void removeTransparentFramePredicate(TransparentFramePredicate predicate) {
		TRANSPARENT_PREDICATES.remove(predicate);
	}

	/**
	 * 已打印过日志的 location（并发安全）。
	 */
	private static final Set<String> LOGGED_ORPHANS =
	 Collections.newSetFromMap(new ConcurrentHashMap<>());
	private static final Set<String> LOGGED_FORWARDS =
	 Collections.newSetFromMap(new ConcurrentHashMap<>());

	/** 日志去重。{@code add} 返回 true 表示本次是首次，才打印。 */
	private static void logOrphanOnce(String location) {
		if (LOGGED_ORPHANS.add(location)) {
			RemoldAgent.warn("[LambdaAligner] orphaned lambda invoked: " + location
			                  + " (subsequent invocations will be muted)");
		}
	}

	private static void logForwardOnce(String location) {
		if (LOGGED_FORWARDS.add(location)) {
			RemoldAgent.info("[LambdaAligner] orphaned lambda forwarded to method ref: " + location
			                  + " (ghost, not reclaimed; subsequent invocations will be muted)");
		}
	}

	/**
	 * 当热重载中被删除/孤立的空壳 Lambda 方法被调用时由生成的字节码调用。
	 * @param location 形如 {@code com.example.Foo#lambda$build$0()V} 的常量
	 */
	public static void onOrphanInvoked(String location) {
		onOrphanInvoked(location, false);
	}

	/**
	 * 当热重载中被删除/孤立的 Lambda 方法被调用时由生成的字节码调用。
	 * @param location    形如 {@code com.example.Foo#lambda$build$0()V} 的常量
	 * @param willForward 是否将继续执行转发到方法引用的逻辑
	 */
	public static void onOrphanInvoked(String location, boolean willForward) {
		OrphanPolicy policy = orphanPolicy;

		if (policy == OrphanPolicy.SMART_ADAPTIVE) {
			if (isOrphanBreakerTriggered()) {
				throw new NoSuchMethodError("Lambda removed by hot swap: " + location);
			}
			// 普通业务调用：去重后打印日志
			if (willForward) {
				logForwardOnce(location);
			} else {
				logOrphanOnce(location);
			}
			return;
		}

		if (policy == OrphanPolicy.THROW_NO_SUCH_METHOD) {
			throw new NoSuchMethodError("Lambda removed by hot swap: " + location);
		}

		if (policy == OrphanPolicy.THROW) {
			throw new IllegalStateException("Lambda removed by hot swap: " + location);
		}

		if (policy == OrphanPolicy.LOG_AND_RETURN_DEFAULT) {
			if (willForward) {
				logForwardOnce(location);
			} else {
				logOrphanOnce(location);
			}
			return;
		}

		// OrphanPolicy.SILENT: 静默返回
	}

	/**
	 * 判断当前调用是否由已注册的孤儿熔断器（{@link OrphanBreakerPredicate}）发起（决定是否熔断）。
	 *
	 * <p><b>判据是"幽灵桩的直接调用者是否匹配注册的熔断器"</b>，而不是"调用栈上某处是否有
	 * 匹配项"：后者会被无关的同栈帧干扰（例如"业务代码 → 框架分发器 → 另一个 lambda"
	 * 这种情形，那个 lambda 其实不是受保护回调）。</p>
	 *
	 * <p><b>为什么不再用 {@code limit(16)} 扫描</b>：那是<b>深度相关</b>的判据 ——
	 * 回调链路上只要夹了较深的用户代码或框架帧（例如嵌套的
	 * {@code Time.run(10, () -> Time.run(5, () -> ...))}），熔断帧就会被截掉，
	 * 于是误判为"非熔断帧"，<b>熔断静默失效</b>。改为"定位幽灵桩入口后取 +2 帧"
	 * 与深度无关：栈有多深都能看到直接调用者。</p>
	 *
	 * <p>降级路径（无 StackWalker 的环境，如 Android/desugar）同样按"定位 {@code onOrphanInvoked}，
	 * 再跳过透明帧取调用者"处理，语义与 StackWalker 路径一致。
	 * 桌面 JVM 上该分支实际不可达（本类最低要求 Java 16+，而 StackWalker 自 Java 9 起可用），
	 * 保留它是为了 Android 等没有 StackWalker 的运行时。</p>
	 * @return 若直接调用者匹配已注册的熔断器则返回 true
	 */
	public static boolean isOrphanBreakerTriggered() {
		try {
			if (StackWalkerHolder.IS_SUPPORTED) {
				return StackWalkerHolder.checkBreaker();
			}
		} catch (Throwable ignored) { }

		try {
			StackTraceElement[] t     = new Throwable().getStackTrace();
			int lastOrphan = -1;
			for (int i = 0; i < t.length; i++) {
				if (SELF.equals(t[i].getClassName()) && "onOrphanInvoked".equals(t[i].getMethodName())) {
					lastOrphan = i;
				} else if (lastOrphan >= 0) {
					break;
				}
			}
			if (lastOrphan < 0) return false;
			// 与 StackWalker 路径同构：桩之后跳过透明帧，取第一个真实调用者。
			int skip  = lastOrphan + 2;   // 跳过最外层 onOrphanInvoked 自身与桩
			int limit = Math.min(t.length, skip + MAX_TRANSPARENT_FRAMES + 1);
			while (skip < limit && isTransparentFrame(t[skip].getClassName())) skip++;
			if (skip >= t.length) return false;
			return shouldBreakOrphan(t[skip].getClassName(), t[skip].getMethodName());
		} catch (Throwable ignored) { }
		return false;
	}

	/**
	 * 兼容旧代码与历史测试的别名方法。
	 * @deprecated 请使用 {@link #isOrphanBreakerTriggered()}
	 */
	@Deprecated
	public static boolean isCalledByUpdateRef() {
		return isOrphanBreakerTriggered();
	}

	/** 本类的全限定名，用于在栈帧里定位幽灵桩入口。 */
	private static final String SELF = LambdaAligner.class.getName();

	/**
	 * 透明帧扫描的深度上限。
	 *
	 * <p>穿透代理/隐藏帧时给一个上界，避免在"整条栈全是透明帧"的病态情况下无界扫描。
	 * 正常回调链路上透明帧屈指可数（代理 1 + handler 1 + invoke 1 + 少量 MethodHandle 帧），
	 * 64 远超实际需要，同时仍与栈的绝对深度无关。</p>
	 */
	private static final int MAX_TRANSPARENT_FRAMES = 64;

	/**
	 * 透明帧：桩与真实调用者之间由 {@code invokedynamic} 生成的代理类帧或注册的包装代理帧。
	 *
	 * <p>必须穿透，否则"固定 +2"在两种环境下会静默失效，而后果正是本机制要消灭的
	 * （熔断不触发）：</p>
	 * <ul>
	 *   <li>开了 {@code -XX:+ShowHiddenFrames} 时，{@code Foo$$Lambda/0x...} 会出现在栈里；</li>
	 *   <li>Android/desugar 之后的 {@code -$$Lambda$} 类不是隐藏类，在
	 *       {@code getStackTrace} 里本来就是可见帧。</li>
	 * </ul>
	 *
	 * <p>此外，UI 框架（如代理层）会用 {@link java.lang.reflect.Proxy}
	 * 把回调参数包成动态代理：幽灵桩与真正的调用者之间会夹着 <b>代理类帧</b>（JDK 9+ 的
	 * {@code jdk.proxyN.$ProxyNN}、JDK 8 的 {@code com.sun.proxy.$ProxyNN}）、
	 * <b>代理 handler 帧</b> 与 <b>方法句柄帧</b>（{@code java.lang.invoke.*}，仅 ShowHiddenFrames 下可见）。
	 * 这些同样通过已注册的 {@link TransparentFramePredicate} 穿透，否则熔断对"被代理的回调"永远不触发。</p>
	 */
	private static boolean isTransparentFrame(String className) {
		if (className == null || className.isEmpty()) return false;
		// 刻意只用**类名**（不取 Class 对象），因此无需 RETAIN_CLASS_REFERENCE，
		// 也避免为判断而触发类加载。
		if (className.contains("$$Lambda")
		       || className.contains("$$$Lambda")
		       || className.startsWith("jdk.internal.reflect.")
		       || className.startsWith("jdk.proxy")           // JDK 9+ 动态代理 jdk.proxyN.$ProxyNN
		       || className.startsWith("com.sun.proxy.")      // JDK 8 动态代理
		       || className.startsWith("java.lang.reflect.")  // Proxy 调度
		       || className.startsWith("java.lang.invoke.")) { // MethodHandle 隐藏帧
			return true;
		}
		for (TransparentFramePredicate predicate : TRANSPARENT_PREDICATES) {
			try {
				if (predicate.isTransparent(className)) return true;
			} catch (Throwable ignored) { }
		}
		return false;
	}

	/**
	 * 该帧是否匹配已注册的孤儿熔断器。
	 */
	private static boolean shouldBreakOrphan(String className, String methodName) {
		if (className == null || methodName == null) return false;
		for (OrphanBreakerPredicate breaker : ORPHAN_BREAKERS) {
			try {
				if (breaker.shouldBreak(className, methodName)) return true;
			} catch (Throwable ignored) { }
		}
		return false;
	}

	/**
	 * 延迟初始化 Holder 类，避免在缺少 StackWalker 的低版本环境中触发 ClassNotFoundException / NoClassDefFoundError。
	 */
	private static class StackWalkerHolder {
		static final boolean     IS_SUPPORTED;
		static final StackWalker WALKER;

		static {
			boolean     supported = false;
			StackWalker walker    = null;
			try {
				walker = StackWalker.getInstance();
				supported = true;
			} catch (Throwable ignored) { }
			IS_SUPPORTED = supported;
			WALKER = walker;
		}

		static boolean checkBreaker() {
			if (!IS_SUPPORTED || WALKER == null) return false;
			return Boolean.TRUE.equals(WALKER.walk(s -> s
			 .dropWhile(f -> !isOrphanEntry(f))        // 定位幽灵桩入口
			 .dropWhile(f -> isOrphanEntry(f))         // 跳过所有 onOrphanInvoked 帧（兼顾 1 参和 2 参重载）
			 .skip(1)                                  // 跳过桩自身
			 .limit(MAX_TRANSPARENT_FRAMES + 1)        // 透明帧扫描深度上界
			 .filter(f -> !isTransparentFrame(f.getClassName()))  // 穿透代理/隐藏帧
			 .findFirst()
			 .map(f -> shouldBreakOrphan(f.getClassName(), f.getMethodName()))
			 .orElse(false)));
		}

		/** 该帧是否为幽灵桩入口（LambdaAligner 的 {@code onOrphanInvoked}）。 */
		private static boolean isOrphanEntry(StackWalker.StackFrame f) {
			return SELF.equals(f.getClassName()) && "onOrphanInvoked".equals(f.getMethodName());
		}
	}
	//endregion

	//region 辅助方法

	/**
	 * 识别"幽灵空壳"方法：上一轮为兜住被删除 lambda 的老 {@code CallSite} 而注入的空方法。
	 *
	 * <p>它调用 {@link #onOrphanInvoked} 来完成策略分发，所以只凭这一个特征就能认出，
	 * 不需要自定义 attribute。识别出的方法会：
	 * <ul>
	 *   <li><b>保留</b>在 {@link MatchContext#oldGroups} 里 —— 孤儿计算要靠它继续复现注入，
	 *       否则下一轮它就从类里消失了，老 {@code CallSite} 会变成 {@link NoSuchMethodError} 之外的
	 *       更糟形态；</li>
	 *   <li><b>不参与任何匹配</b>（见 {@link SyntheticInfo#ghost}）—— 它的名字是死名字，
	 *       只能被兜住，不能被某个新 lambda "认领"，否则会把本该活着的 lambda 挤到别的名字上。</li>
	 * </ul>
	 *
	 * <p>实测动机（{@code scratch/hstest/del/}）：V1 两个 lambda、V2 删掉第一个（留下幽灵
	 * {@code $0}）、V3 改剩下那个的方法体。修复前 V3 会对齐成
	 * {@code $0=[新体]}、{@code $1=幽灵} —— 活着的 lambda 丢了它自己的名字 {@code $1}，
	 * 持有 {@code $1} 的调用点转而执行新代码。幽灵退出匹配后，它会稳定保持在
	 * {@code $1} 这个名字下继续当空壳。</p>
	 */
	private static boolean isGhostMethod(MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode mi
			    && mi.owner.equals(Type.getInternalName(LambdaAligner.class))
			    && mi.name.equals("onOrphanInvoked")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 读出幽灵桩体开头的"生前指纹"标记（{@code LDC <long>; POP2}）。
	 *
	 * <p>标记必须放在方法体最前面，且只认前 3 条指令内出现的 long 常量——避免把业务方法里
	 * 偶然出现的 long 常量误当成标记。返回 0 表示没有标记（旧版本注入的桩、或转发桩）。</p>
	 */
	private static long readGhostOriginalHash(MethodNode mn) {
		int steps = 0;
		for (AbstractInsnNode n = mn.instructions.getFirst(); n != null && steps < 3; n = n.getNext(), steps++) {
			if (n instanceof LdcInsnNode ldc && ldc.cst instanceof Long v) return v;
		}
		return 0L;
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
						if (ctx.usedOldNames.contains(oi.name)) continue;
						pair(ctx, ni, oi, "GHOST_REVIVE");
						TEST_EVENTS.add(new AlignmentEvent("GHOST_REVIVE", oi.name, ni.name,
							ctx.currentClass + "#" + ni.name + ni.desc + " 复活回 " + oi.name));
						RemoldAgent.info("[LambdaAligner] 幽灵复活 " + ctx.currentClass + "#"
							+ oi.name + oi.desc + "（原封不动加回来，指纹一致）");
						break;
					}
					if (ni.matched) break;
				}
			}
		}
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
		       && oi.upDepth >= 0 && !ctx.usedOldNames.contains(oi.name);
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