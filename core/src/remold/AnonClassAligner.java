package remold;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 匿名内部类树状拓扑与对齐器 (Anonymous Class Aligner)。
 *
 * <p>设计文档参见 {@code docs/topology/}（索引见 {@code docs/ANONYMOUS_CLASS_TOPOLOGY_PLAN.md}）；实现状态参见 {@code docs/status.md} 与 {@code AGENTS.md}。</p>
 * <p>在 DCEVM / JBR 增强重定义环境下，匿名内部类编号按源码出现顺序生成（{@code Foo$1}, {@code Foo$2} ...）。
 * 当在前部插入、删除、重排匿名类时，编译产物的编号发生位移，导致 DCEVM 将 JVM 中已存活的旧实例
 * 物理迁移到内容完全不同的新类上，造成静默内存污染与方法篡改（Method Hijacking）。</p>
 *
 * <h2>核心不变量（Core Invariant，{@code docs/topology/01-invariants-and-remapping.md} §2）</h2>
 * <p><b>在 JVM 中已经存在存活实例的已加载类，其物理类名只能被源码上与之对应的新版本实现重定义，
 * 其既有存活实例的方法调用与字段状态必须维持预期的语义连续性，禁止被无关的新生类占用物理槽位。</b></p>
 *
 * <h2>四级置信度比对体系（Tiers 1 ~ 4）</h2>
 * <ol>
 *   <li><b>Tier 1 (内容哈希 + 宿主方法)</b>：{@link AnonClassHasher} 内容哈希与所在宿主方法均精确相同；</li>
 *   <li><b>Tier 2 (全局内容哈希)</b>：跨方法或初始化块中内容哈希精确唯一匹配（仅全类唯一孤本采纳，严禁跨方法 minDiff）；</li>
 *   <li><b>Tier 3 (结构签名 + 拓扑相等过滤)</b>：同宿主方法、同基类与接口、同字段与方法签名；
 *       在双向唯一之后、minDiff 之前引入<b>拓扑签名过滤</b>（{@code docs/topology/04-tiers-and-rejection.md} §2），应对方法体修改导致的哈希漂移；</li>
 *   <li><b>Tier 4 (松散结构 + 状态布局门)</b>：同宿主方法、同基类与接口类型；禁止 minDiff 盲猜；
 *       联动包装 {@link LayoutGate} 阻断合成捕获字段（{@code val$*}/{@code this$0}）不兼容导致的零值污染。</li>
 * </ol>
 * <p><b>彻底移除历史遗留的 Tier 5（按物理类名盲配）</b>：前 4 层未匹配的旧类一律判定为"孤儿旧类"
 * （进入 {@code orphanOldClasses}，在重定义时显式保留而不被覆盖）；未匹配的新类一律分配未占用的安全新编号，
 * 并通过 {@link AnnotationTransformer#pendingAlignedClasses} 在初次加载时拦截生效。</p>
 *
 * <h2>宿主组原子拒绝（Atomic Host-Group Rejection，{@code docs/topology/04-tiers-and-rejection.md} §4）</h2>
 * <p>当遇到多候选歧义（strict 模式）、嵌套层级超限（{@code depth > 4}）、匿名类超上限（{@link #MAX_ANON_PER_HOST}）
 * 或软超时（{@link #ALIGN_TIMEOUT_MS}）时，抛出 {@link AlignmentRejectedException}，
 * 热更管线将宿主类及其全部派生内部类作为一个原子单元<b>整组移出本轮重定义</b>，存活实例继续稳定运行旧逻辑。</p>
 *
 * <h2>运行时依赖与改写契约</h2>
 * <ul>
 *   <li>改写使用 ASM {@link ClassRemapper}，在重命名映射表中同步更新类指令流、
 *       {@code InnerClasses}、{@code EnclosingMethod}、{@code NestHost}/{@code NestMembers}，
 *       采用 {@code ClassWriter(0)} 避免类加载期栈图计算死锁。</li>
 *   <li>依赖 DCEVM / JBR 增强重定义运行时（{@code -XX:+AllowEnhancedClassRedefinition}）。</li>
 *   <li>官方夹具已对 Java 8 / 17 / 21（javac）进行严密回归验证。</li>
 * </ul>
 */
public final class AnonClassAligner {

	/** 测试钩子：强制反向遍历以验证顺序无关性 */
	public static boolean TEST_REVERSE_ORDER = false;

	/**
	 * 单个宿主类下匿名类数量的硬上限（{@code docs/topology/06-runtime-and-perf.md} §3）。超过即按 {@code docs/topology/04-tiers-and-rejection.md} §4 拒绝整个宿主组。
	 *
	 * <p><b>为什么不采用"告警并降级为不重命名"</b>：不对齐时，新编译产物的 {@code Foo$2} 与 JVM 中
	 * 已加载的旧 {@code Foo$2} 同名但语义不同，一旦进入重定义就正好是本模块要消灭的"存活实例
	 * 被无关新类占据物理槽位"。因此这里唯一安全的降级是**不对齐**（由调用方整体放弃该宿主组）。</p>
	 *
	 * <p>设为 {@link Integer#MAX_VALUE} 可关闭该上限（仅供诊断）。</p>
	 */
	public static int MAX_ANON_PER_HOST = 128;

	/**
	 * 对齐流程的软超时（毫秒，{@code docs/topology/06-runtime-and-perf.md} §3）。超时即按 {@code docs/topology/04-tiers-and-rejection.md} §4 拒绝该宿主组。
	 *
	 * <p>比对采用 elapsed 形式（{@code now - start}），因此天然不会溢出；
	 * 设为 {@link Long#MAX_VALUE} 可关闭超时；设为 {@code <= 0} 表示"无预算"，
	 * 在第一个检查点即确定性熔断（便于回归测试，不依赖墙上时钟分辨率）。</p>
	 */
	public static long ALIGN_TIMEOUT_MS = 2000L;

	/**
	 * 对齐被安全门拒绝（{@code docs/topology/04-tiers-and-rejection.md} §4）。
	 *
	 * <p>语义：<b>宿主类 + 其下属全部匿名类</b>作为一个原子单元整体放弃 —— 调用方不得把其中
	 * 任何一项送入本次重定义（否则宿主新字节码会引用到没被对齐的 {@code Foo$N}）。</p>
	 *
	 * <p>继承 {@link IllegalStateException} 是为了不破坏既有调用方与回归断言
	 * （{@code AnonClassReproTest} Scenario 19 就以 {@code IllegalStateException} 捕获后置校验失败）。</p>
	 */
	public static class AlignmentRejectedException extends IllegalStateException {
		/** 被拒绝的宿主类内部名 */
		public final String hostSlash;
		/** 拒绝原因（会进入 {@code [HOTSWAP-REJECT]} 告警） */
		public final String reason;

		public AlignmentRejectedException(String hostSlash, String reason) {
			super("[ANON_ALIGN_REJECT] " + hostSlash + ": " + reason);
			this.hostSlash = hostSlash;
			this.reason = reason;
		}
	}

	/** 是否已超出软超时窗口。 */
	private static boolean isTimedOut(long startNanos) {
		// 无预算（<= 0）时确定性熔断，而不是依赖"elapsed 是否已 >= 1ms"这种墙上时钟分辨率
		if (ALIGN_TIMEOUT_MS <= 0L) return true;
		return (System.nanoTime() - startNanos) / 1_000_000L > ALIGN_TIMEOUT_MS;
	}

	/** 超时检查点：命中即拒绝宿主组。 */
	private static void checkTimeout(String hostSlash, long startNanos) {
		if (isTimedOut(startNanos)) {
			throw new AlignmentRejectedException(hostSlash,
			 "alignment exceeded the " + ALIGN_TIMEOUT_MS + "ms soft timeout (docs/topology/06-runtime-and-perf.md §3)");
		}
	}

	/** 诊断日志（{@code docs/topology/06-runtime-and-perf.md} §4 {@code -Dremold.agent.anon_debug} / 别名 {@code -Dremold.anonAlign.debug}，或全局 DEBUG）。 */
	private static void dbg(String msg) {
		if (RemoldAgent.ANON_DEBUG || RemoldAgent.DEBUG) {
			RemoldAgent.info("[ANON_ALIGN] " + msg);
		}
	}

	public static class AlignmentStats {
		public int  tier1Matches;
		public int  tier2Matches;
		public int  tier3Matches;
		public int  tier4Matches;
		/**
		 * 布局门拒绝的配对数（{@code reject} 模式）。见 {@link LayoutGate} 与 {@code docs/topology/07-layout-gate-and-risks.md} §1。
		 *
		 * <p>"用了哪一层"的纪律同样适用：光看映射结果分不清"没配上"是因为歧义、
		 * 还是因为被布局门挡了。断言这个计数器才能钉住门确实生效。</p>
		 */
		public int  layoutGateRejected;
		/**
		 * 布局门放行的配对数（{@code warn} 模式，或布局不兼容但<b>本轮扫描未见存活实例</b>）。
		 *
		 * <p>{@code warn} 模式下照旧配对并原地重定义，只打告警；此计数让用户看到
		 * "本来会被拒绝的有几次"。扫描未见存活实例时放行 —— 新建实例会走新构造器，初始化正常
		 * （真机对照实验结论）；但“扫描没看见”不是“实例已死”的证明，见 Tier 4.5 的同一约定。</p>
		 */
		public int  layoutGateWaived;
		/** 布局门扫描存活实例的总耗时（毫秒），用于观察全堆遍历的开销。 */
		public long layoutGateScanMillis;
		public int  tier5Matches;
		public int  ambiguousMatches;
		/**
		 * 在**禁止 minDiff 仲裁的层**（Tier 2 / Tier 4）完成"双向唯一"配对后，仍然无法确定性区分的候选对数（{@code docs/topology/04-tiers-and-rejection.md} §4）。
		 *
		 * <p>统计口径：剩余新类中拥有 &gt;= 2 个剩余旧候选的个数（1-to-N），加上剩余旧类中拥有 &gt;= 2 个
		 * 剩余新候选的个数（N-to-1）。只要 &gt; 0 就说明该层存在真实歧义 —— 双向唯一配对是贪心且
		 * 完备的，凡能唯一确定的都已被取走，留下的必然是"多对多"。</p>
		 *
		 * <p>非严格模式下这些类退化为"新增/孤儿"；严格模式（{@code -Dremold.agent.anon_strict=true}）
		 * 下与 {@link #ambiguousMatches} 一起构成"缺乏唯一证据"的完整集合，任一非零即拒绝整个宿主组。</p>
		 */
		public int  ambiguousPairs;
		/**
		 * 由**拓扑判据**决定并采纳的配对数（{@code docs/topology/04-tiers-and-rejection.md} §2 Tier 3 拓扑过滤）。
		 *
		 * <p>刻意**不计入** {@link #tier3Matches}：后者表示"靠内容哈希/结构签名配上的"，
		 * 混在一起以后就分不清某个配对是内容配的还是拓扑配的。</p>
		 */
		public int  topologyMatches;
		/** 拓扑过滤前：剩余新类在剩余旧类中的候选总数（诊断用，配合 debug 日志定位拒绝来源） */
		public int  topologyCandidatesBefore;
		/** 拓扑过滤后：签名严格相等且仍在剩余集里的候选总数（诊断用） */
		public int  topologyCandidatesAfter;
		public int  newClasses;
		public int  orphanClasses;
		/**
		 * Tier 3.5「捕获签名 + 方法体结构相似度」采纳的配对数。
		 *
		 * <p>与 {@link #tier3Matches} 刻意分开：结构签名相等是<em>可证明</em>的同构，
		 * 而相似度是<em>带 margin 的证据</em>。混在一起会让"这一对到底靠什么配上的"不可审计。</p>
		 */
		public int  bodySimilarityMatches;
		/**
		 * Tier 4.5「扫描可见零存活」确定性兜底配对数。
		 *
		 * <p>每一对都必须在日志里带 {@code deterministic-zero-scan-pair (scan-visible live instances: 0)}
		 * 留痕，严禁与普通结构唯一匹配混为一谈；文案不得表述为“实例已死”或“安全”
		 * （{@code docs/topology/04-tiers-and-rejection.md} §2.4）。</p>
		 */
		public int  deterministicZeroScanPairs;
		/**
		 * @deprecated 0.2.6 的旧字段名，仅为下游二进制兼容保留；值与
		 * {@link #deterministicZeroScanPairs} 同步，勿在新代码里读取。
		 */
		@Deprecated
		public int  deterministicDeadPairs;
		/** 歧义组的可读诊断（点名新旧类名与各自**扫描可见**存活实例数），供宿主级告警原文引用。 */
		public java.util.List<String> ambiguousDetail = new java.util.ArrayList<>();

		@Override
		public String toString() {
			return "AlignmentStats[T1=" + tier1Matches + ", T2=" + tier2Matches +
			       ", T3=" + tier3Matches + ", T4=" + tier4Matches +
			       ", T5=" + tier5Matches + ", ambiguous=" + ambiguousMatches +
			       ", ambiguousPairs=" + ambiguousPairs +
			       ", topology=" + topologyMatches +
			       ", topoCandBefore=" + topologyCandidatesBefore +
			       ", topoCandAfter=" + topologyCandidatesAfter +
			       ", new=" + newClasses + ", orphans=" + orphanClasses +
		       ", bodySim=" + bodySimilarityMatches + ", zeroScanPair=" + deterministicZeroScanPairs + "]";
		}
	}

	public static class Result {
		/** 对齐重命名后的宿主类字节码（若输入了 hostBytes） */
		public final byte[]              alignedHostBytes;
		/** 对齐重命名后的所有新匿名类：目标对齐内部名 -> 对齐字节码 */
		public final Map<String, byte[]> alignedAnonClasses;
		/** 重命名映射表：原编译内部名 -> 目标对齐内部名 */
		public final Map<String, String> renameMap;
		/** 未被匹配上的旧匿名类（孤儿类内部名），应在 JVM 中保留不动不触发重定义 */
		public final Set<String>         orphanOldClasses;
		/** 对齐统计数据 */
		public final AlignmentStats      stats;

		public Result(
		 byte[] alignedHostBytes,
		 Map<String, byte[]> alignedAnonClasses,
		 Map<String, String> renameMap,
		 Set<String> orphanOldClasses) {
			this(alignedHostBytes, alignedAnonClasses, renameMap, orphanOldClasses, new AlignmentStats());
		}

		public Result(
		 byte[] alignedHostBytes,
		 Map<String, byte[]> alignedAnonClasses,
		 Map<String, String> renameMap,
		 Set<String> orphanOldClasses,
		 AlignmentStats stats) {
			this.alignedHostBytes = alignedHostBytes;
			this.alignedAnonClasses = Collections.unmodifiableMap(alignedAnonClasses);
			this.renameMap = Collections.unmodifiableMap(renameMap);
			this.orphanOldClasses = Collections.unmodifiableSet(orphanOldClasses);
			this.stats = stats != null ? stats : new AlignmentStats();
		}
	}

	private AnonClassAligner() { }

	/**
	 * 对齐匿名类并重写宿主与匿名类字节码。
	 * @param hostClassName  宿主外层类类名（支持点分或斜杠格式，如 {@code com/example/Foo}）
	 * @param newHostBytes   新编译的宿主类字节码（可为 null）
	 * @param oldAnonClasses 旧版本匿名类字节码表（内部名或点分名 -> 字节码）
	 * @param newAnonClasses 新编译的匿名类字节码表（内部名或点分名 -> 字节码）
	 * @return 对齐结果 {@link Result}
	 */
	public static Result align(
	 String hostClassName,
	 byte[] newHostBytes,
	 Map<String, byte[]> oldAnonClasses,
	 Map<String, byte[]> newAnonClasses) {
		return align(hostClassName, newHostBytes, oldAnonClasses, newAnonClasses,
		 Collections.<String>emptySet());
	}

	/**
	 * 对齐匿名类并重写宿主与匿名类字节码（带"已被占用编号"避让）。
	 *
	 * <p>{@code reserved} 是**已由本进程分配、但对应类还没被 JVM 加载**的目标内部名集合
	 * （例如上一批 {@code AlignmentTransaction} 提交进 {@code pendingAlignedClasses} 的
	 * {@code Foo$3}）。它们<b>不</b>参与 Tier 匹配 —— 挂起的类还没加载，不是"旧类"，也没有可供
	 * 比对的字节码；唯一作用是播种"已占用编号"集合，避免本轮新建类分配到同一个 {@code Foo$3}
	 * 而与挂起类撞车。</p>
	 *
	 * <p>调用方应传**快照**（{@code Set.copyOf(...)}）而不是并发容器的实时视图：视图会让分配结果
	 * 依赖挂起集合的时序变化，也会破坏 {@code TEST_REVERSE_ORDER} 的可确定性。</p>
	 *
	 * @param reserved 已占用（挂起）的目标内部名集合；{@code null} 等价于空集。名字可带点或斜杠，
	 *                 内部统一成斜杠内部名。
	 */
	public static Result align(
	 String hostClassName,
	 byte[] newHostBytes,
	 Map<String, byte[]> oldAnonClasses,
	 Map<String, byte[]> newAnonClasses,
	 Set<String> reserved) {
		return align(hostClassName, newHostBytes, oldAnonClasses, newAnonClasses,
		 null, null, null, reserved);
	}

	/**
	 * 对齐匿名类并重写宿主与匿名类字节码（带字节码解析器）。
	 *
	 * <p>实例判定取已注入的 {@link #hotswapAlignerHasLiveInstances}（默认保守：视为有实例）。</p>
	 */
	public static Result align(
	 String hostClassName,
	 byte[] newHostBytes,
	 Map<String, byte[]> oldAnonClasses,
	 Map<String, byte[]> newAnonClasses,
	 Function<String, byte[]> oldResolver,
	 Function<String, byte[]> newResolver) {
		return alignCascading(hostClassName, newHostBytes, oldAnonClasses, newAnonClasses,
		 oldResolver, newResolver, null);
	}

	/**
	 * 对齐匿名类并重写宿主与匿名类字节码（带字节码解析器 + 布局门实例判定）。
	 * @param hasLiveInstances 布局门用：给定点分类名，判断它是否还有存活实例。
	 *                         {@code null} 表示沿用已注入的判定（默认保守：视为有实例）。
	 *                         由调用方注入，使本类保持纯函数、不依赖 JVMTI。
	 */
	public static Result align(
	 String hostClassName,
	 byte[] newHostBytes,
	 Map<String, byte[]> oldAnonClasses,
	 Map<String, byte[]> newAnonClasses,
	 Function<String, byte[]> oldResolver,
	 Function<String, byte[]> newResolver,
	 Predicate<String> hasLiveInstances) {
		return align(hostClassName, newHostBytes, oldAnonClasses, newAnonClasses,
		 oldResolver, newResolver, hasLiveInstances, Collections.<String>emptySet());
	}

	/**
	 * 对齐匿名类并重写宿主与匿名类字节码（解析器 + 布局门实例判定 + 挂起编号避让）。
	 *
	 * @param reserved 已占用（挂起）的目标内部名集合，语义见上面的 {@code Set} 重载。
	 */
	public static Result align(
	 String hostClassName,
	 byte[] newHostBytes,
	 Map<String, byte[]> oldAnonClasses,
	 Map<String, byte[]> newAnonClasses,
	 Function<String, byte[]> oldResolver,
	 Function<String, byte[]> newResolver,
	 Predicate<String> hasLiveInstances,
	 Set<String> reserved) {
		return alignCascading(hostClassName, newHostBytes, oldAnonClasses, newAnonClasses,
		 oldResolver, newResolver, hasLiveInstances, reserved);
	}

	/**
	 * 布局门用的"是否有存活实例"判定；由 {@code RemoldAgent} 注入。
	 *
	 * <p>刻意做成可变静态而不是逐层传参：本类的调用链有 {@code alignCascading} →
	 * {@code matchHierarchical} → {@code matchTier} 多层，逐层加参数会污染每一层签名，
	 * 而这个判定是"环境能力"而非"本次调用的数据"。测试里直接设桩即可。</p>
	 */
	static Predicate<String> hotswapAlignerHasLiveInstances =
	 name -> true;   // 未注入时按"有实例"处理：保守，宁可少配对也不静默读零值

	/**
	 * 级联树匿名类对齐（Cascading Tree Anonymous Class Aligner）—— 不带布局门实例判定。
	 *
	 * <p>沿用已注入的 {@link #hotswapAlignerHasLiveInstances}。调用方若需要按本次热更
	 * 注入判定，用带 {@code Predicate} 的那个重载。</p>
	 */
	public static Result alignCascading(
	 String hostClassName,
	 byte[] newHostBytes,
	 Map<String, byte[]> oldAnonClasses,
	 Map<String, byte[]> newAnonClasses,
	 Function<String, byte[]> oldResolver,
	 Function<String, byte[]> newResolver) {
		return alignCascading(hostClassName, newHostBytes, oldAnonClasses, newAnonClasses,
		 oldResolver, newResolver, null);
	}

	/**
	 * 级联树匿名类对齐（Cascading Tree Anonymous Class Aligner）。
	 *
	 * <p>按 {@code $} 嵌套深度组织匿名类树形拓扑（Level 1 -> Level N），自顶向下逐层推进：
	 * <ul>
	 *   <li><b>Level 1</b>：宿主直接子匿名类（如 {@code Foo$1}, {@code Foo$2}），在宿主作用域内对齐；</li>
	 *   <li><b>Level > 1</b>：嵌套匿名类（如 {@code Foo$1$1}），候选作用域严格收敛于其父类对齐映射后的目标旧类所包含的子类集合，物理阻断跨树夺舍；</li>
	 *   <li><b>前缀派生</b>：未匹配新类的类名前缀强制继承其父类重映射后的目标名称，保证 JVM 内部类层级不被破坏；</li>
	 *   <li><b>严格校验</b>：对齐映射结果执行单射性与前缀不变量校验，不符合则阻断提交。</li>
	 * </ul>
	 * @param hasLiveInstances 布局门的实例判定；{@code null} 表示沿用已注入的判定。
	 */
	public static Result alignCascading(
	 String hostClassName,
	 byte[] newHostBytes,
	 Map<String, byte[]> oldAnonClasses,
	 Map<String, byte[]> newAnonClasses,
	 Function<String, byte[]> oldResolver,
	 Function<String, byte[]> newResolver,
	 Predicate<String> hasLiveInstances) {
		return alignCascading(hostClassName, newHostBytes, oldAnonClasses, newAnonClasses,
		 oldResolver, newResolver, hasLiveInstances, Collections.<String>emptySet());
	}

	/**
	 * 级联树匿名类对齐（Cascading Tree Anonymous Class Aligner）。
	 *
	 * <p>按 {@code $} 嵌套深度组织匿名类树形拓扑（Level 1 -> Level N），自顶向下逐层推进：
	 * <ul>
	 *   <li><b>Level 1</b>：宿主直接子匿名类（如 {@code Foo$1}, {@code Foo$2}），在宿主作用域内对齐；</li>
	 *   <li><b>Level > 1</b>：嵌套匿名类（如 {@code Foo$1$1}），候选作用域严格收敛于其父类对齐映射后的目标旧类所包含的子类集合，物理阻断跨树夺舍；</li>
	 *   <li><b>前缀派生</b>：未匹配新类的类名前缀强制继承其父类重映射后的目标名称，保证 JVM 内部类层级不被破坏；</li>
	 *   <li><b>严格校验</b>：对齐映射结果执行单射性与前缀不变量校验，不符合则阻断提交。</li>
	 * </ul>
	 * @param hasLiveInstances 布局门的实例判定；{@code null} 表示沿用已注入的判定。
	 * @param reserved 已占用（挂起）的目标内部名集合；只播种"已占用编号"，不参与匹配。
	 */
	public static Result alignCascading(
	 String hostClassName,
	 byte[] newHostBytes,
	 Map<String, byte[]> oldAnonClasses,
	 Map<String, byte[]> newAnonClasses,
	 Function<String, byte[]> oldResolver,
	 Function<String, byte[]> newResolver,
	 Predicate<String> hasLiveInstances,
	 Set<String> reserved) {
		if (hostClassName == null) {
			throw new IllegalArgumentException("hostClassName cannot be null");
		}
		// 布局门的实例判定：本次调用显式传入则用本次的，否则保留已注入的（测试桩/默认保守）。
		// 用局部变量 + 恢复，避免污染后续调用。
		Predicate<String> prevHasLive = hotswapAlignerHasLiveInstances;
		if (hasLiveInstances != null) hotswapAlignerHasLiveInstances = hasLiveInstances;
		try {
			final String hostSlash  = hostClassName.replace('.', '/');
			final long   startNanos = System.nanoTime();
			dbg("begin alignment for host " + hostSlash + " (maxPerHost=" + MAX_ANON_PER_HOST + ", timeoutMs=" + ALIGN_TIMEOUT_MS + ", strict=" + RemoldAgent.ANON_STRICT + ")");

			// 归一化输入 Map 为内部名
			Map<String, byte[]> normOld = normalizeMap(oldAnonClasses, hostSlash);
			Map<String, byte[]> normNew = normalizeMap(newAnonClasses, hostSlash);

			// 构造能够解析宿主类的安全 Resolver，防止默认 fallback 导致宿主方法追溯永远返回 null
			Function<String, byte[]> effectiveOldResolver = oldResolver != null ? oldResolver :
			 (name -> {
				 if (name.equals(hostSlash)) {
					 byte[] b = oldAnonClasses != null ? oldAnonClasses.get(hostSlash) : null;
					 if (b != null) return b;
					 return newHostBytes;
				 }
				 return normOld.get(name);
			 });

			Function<String, byte[]> effectiveNewResolver = newResolver != null ? newResolver :
			 (name -> name.equals(hostSlash) ? newHostBytes : normNew.get(name));

			// 单次解析宿主 ClassNode 供整个流程复用，杜绝多次构建 AST 导致的停顿与 GC 压力
			ClassNode oldHostNode = parseHostNode(hostSlash, effectiveOldResolver);
			ClassNode newHostNode = parseHostNode(hostSlash, effectiveNewResolver);

			// 解析新旧匿名类特征（接入严密准入分类器，并复用宿主节点）
			List<AnonInfo> oldInfos = parseInfos(hostSlash, oldHostNode, normOld, effectiveOldResolver);
			List<AnonInfo> newInfos = parseInfos(hostSlash, newHostNode, normNew, effectiveNewResolver);

			// docs/topology/06-runtime-and-perf.md §3 数量硬上限： pathological 输入（代码生成产物、巨型 switch 表达式）下
			// O(N^2) 对齐会无提示地变慢，因此这里设硬上限并整体拒绝，而不是"降级为不对齐"（见 MAX_ANON_PER_HOST javadoc）。
			int anonCount = Math.max(oldInfos.size(), newInfos.size());
			if (anonCount > MAX_ANON_PER_HOST) {
				throw new AlignmentRejectedException(hostSlash,
				 "anonymous class count " + anonCount + " exceeds MAX_ANON_PER_HOST=" + MAX_ANON_PER_HOST + " (docs/topology/06-runtime-and-perf.md §3)");
			}
			checkTimeout(hostSlash, startNanos);

			AlignmentStats stats = new AlignmentStats();

			// 旧匿名类内部名 → 自身 ClassNode（只取父类/接口，供"持有者字段"点名使用）。
			// 按需解析：调用方只在点名或 Tier 4.5 审计时才会真正 parse，纯函数不变。
			Function<String, ClassNode> anonNodeResolver = name -> anonNodeForHolding(oldInfos, name);

			// 按层级（depth of '$'）组织类信息
			Map<Integer, List<AnonInfo>> oldByLevel = new TreeMap<>();
			Map<Integer, List<AnonInfo>> newByLevel = new TreeMap<>();
			for (AnonInfo o : oldInfos) {
				oldByLevel.computeIfAbsent(getHierarchyLevel(hostSlash, o.name), k -> new ArrayList<>()).add(o);
			}
			for (AnonInfo n : newInfos) {
				newByLevel.computeIfAbsent(getHierarchyLevel(hostSlash, n.name), k -> new ArrayList<>()).add(n);
			}

			int maxLevel = 1;
			for (int l : oldByLevel.keySet()) maxLevel = Math.max(maxLevel, l);
			for (int l : newByLevel.keySet()) maxLevel = Math.max(maxLevel, l);
			if (maxLevel > 4) {
				String msg = "anonymous class nesting depth " + maxLevel + " > 4 detected";
				if (RemoldAgent.ANON_STRICT) {
					throw new AlignmentRejectedException(hostSlash, msg + "; strict mode rejects the host group (docs/topology/04-tiers-and-rejection.md §4)");
				}
				// 注意：这只是一个**诊断阈值**，不是能力边界 —— 层级推进本身与深度无关，
				// 真正的闸门是 MAX_ANON_PER_HOST（{@code docs/topology/06-runtime-and-perf.md} §3）与 ALIGN_TIMEOUT_MS（{@code docs/topology/06-runtime-and-perf.md} §3）。
				// 早期注释曾声称此处"内容哈希退化为 #ANON_relId#"，那是错的：AnonClassHasher.MAX_DEPTH 从不生效（见该类注释）。
				RemoldAgent.warn("[ANON_ALIGN] " + msg + ". Diagnostic threshold only"
				                  + " (cascade is depth-generic; the real guards are MAX_ANON_PER_HOST=" + MAX_ANON_PER_HOST
				                  + " and ALIGN_TIMEOUT_MS=" + ALIGN_TIMEOUT_MS + "ms).");
			}

			Map<AnonInfo, AnonInfo> matchedNewToOld  = new LinkedHashMap<>();
			Map<String, String>     renameMap        = new LinkedHashMap<>();
			Set<String>             takenTargetNames = new HashSet<>(normOld.keySet());
			// 挂起编号避让：reserved 只播种"已占用"集合，**绝不**加入匹配候选。
			//
			// 理由：挂起的类由上一批事务注册、还没被 JVM 加载 —— 它不在 JVM 里、没有旧字节码，
			// 因此不是"旧类"，没有资格参与 Tier 匹配（拿它当候选会把新类对上"空气"，并通过
			// rename 把字节码写到挂起类名下，覆盖掉那段还没生效的版本）。它唯一的语义是
			// "这个编号已经有人用了"，所以只影响下面两处新编号分配。
			//
			// 名字统一成斜杠内部名（调用方可能把 pendingAlignedClasses 的 slash/dot 两种键都传进来）。
			if (reserved != null) {
				for (String r : reserved) {
					if (r != null) takenTargetNames.add(r.replace('.', '/'));
				}
			}

			// 自顶向下逐层推进（Top-down Cascading Progression）
			for (int level = 1; level <= maxLevel; level++) {
				checkTimeout(hostSlash, startNanos);
				List<AnonInfo> oldLevelInfos = oldByLevel.getOrDefault(level, Collections.emptyList());
				List<AnonInfo> newLevelInfos = newByLevel.getOrDefault(level, Collections.emptyList());
				dbg("level " + level + ": old=" + oldLevelInfos.size() + ", new=" + newLevelInfos.size());

				if (level == 1) {
					// Level 1: 宿主直接子匿名类 (如 Foo$1, Foo$2)
					Map<AnonInfo, AnonInfo> l1Matches = matchHierarchical(oldLevelInfos, newLevelInfos, stats,
					 hostSlash, startNanos, oldHostNode, newHostNode, anonNodeResolver);
					for (Map.Entry<AnonInfo, AnonInfo> entry : l1Matches.entrySet()) {
						matchedNewToOld.put(entry.getKey(), entry.getValue());
						renameMap.put(entry.getKey().name, entry.getValue().name);
						dbg("  level 1 matched " + entry.getKey().name + " -> " + entry.getValue().name);
					}
					for (AnonInfo n : newLevelInfos) {
						if (!renameMap.containsKey(n.name)) {
							int    idx = 1;
							String candidate;
							do {
								candidate = hostSlash + "$" + idx++;
							} while (takenTargetNames.contains(candidate));
							takenTargetNames.add(candidate);
							renameMap.put(n.name, candidate);
							dbg("  level 1 unmatched " + n.name + " -> new number " + candidate);
						}
					}
				} else {
					// Level > 1: 嵌套匿名类 (如 Foo$1$1, Foo$2$1)
					// 作用域收敛：候选严格限制在已配对的父级类名所对应的旧子级范围内
					Map<String, List<AnonInfo>> newByParent = new LinkedHashMap<>();
					for (AnonInfo n : newLevelInfos) {
						newByParent.computeIfAbsent(getParentName(hostSlash, n.name), k -> new ArrayList<>()).add(n);
					}

					Map<String, List<AnonInfo>> oldByParent = new LinkedHashMap<>();
					for (AnonInfo o : oldLevelInfos) {
						oldByParent.computeIfAbsent(getParentName(hostSlash, o.name), k -> new ArrayList<>()).add(o);
					}

					for (Map.Entry<String, List<AnonInfo>> entry : newByParent.entrySet()) {
						String         newParent    = entry.getKey();
						List<AnonInfo> newChildren  = entry.getValue();
						String         targetParent = renameMap.get(newParent);
						if (targetParent == null) {
							targetParent = newParent;
						}
						dbg("  level " + level + " scope " + newParent + " -> " + targetParent + " (new=" + newChildren.size() + ")");

						List<AnonInfo>          oldCandidateChildren = oldByParent.getOrDefault(targetParent, Collections.emptyList());
						Map<AnonInfo, AnonInfo> childMatches         = matchHierarchical(oldCandidateChildren, newChildren, stats,
						 hostSlash, startNanos, oldHostNode, newHostNode, anonNodeResolver);
						for (Map.Entry<AnonInfo, AnonInfo> m : childMatches.entrySet()) {
							matchedNewToOld.put(m.getKey(), m.getValue());
							renameMap.put(m.getKey().name, m.getValue().name);
							dbg("  level " + level + " matched " + m.getKey().name + " -> " + m.getValue().name);
						}

						for (AnonInfo n : newChildren) {
							if (!renameMap.containsKey(n.name)) {
								int    idx = 1;
								String candidate;
								do {
									candidate = targetParent + "$" + idx++;
								} while (takenTargetNames.contains(candidate));
								takenTargetNames.add(candidate);
								renameMap.put(n.name, candidate);
								dbg("  level " + level + " unmatched " + n.name + " -> new number " + candidate);
							}
						}
					}
				}
			}

			// 收集孤儿类
			Set<String> matchedOldNames = new HashSet<>();
			for (AnonInfo o : matchedNewToOld.values()) {
				matchedOldNames.add(o.name);
			}
			Set<String> orphanOldClasses = new LinkedHashSet<>();
			for (AnonInfo o : oldInfos) {
				if (!matchedOldNames.contains(o.name)) {
					orphanOldClasses.add(o.name);
				}
			}

			stats.newClasses = newInfos.size() - matchedNewToOld.size();
			stats.orphanClasses = orphanOldClasses.size();
			dbg("level pass done: " + stats + ", orphans=" + orphanOldClasses);

			// 字段持有风险：扫宿主字段表，点名可能长期持有这些被孤儿化的匿名类实例的字段。
			// 告警里必须出现它，否则"需要重启"这句话无法变成可执行的处置动作
			// （字段持有 → 挂 @RemoldReinit；方法内每帧 new → 无需处置）。
			// 三种判据逐层退化：精确描述符 → 赋值流（NEW <anon> → <init> → PUTFIELD/PUTSTATIC）
			// → 父类型/接口描述符候选。匿名类的 superName/interfaces 只能从它自己的字节码拿到，
			// 所以这里按需解析（只在孤儿集非空、且前两层都没命中时才会被调用）。
			HoldingFields holdingFields = findHoldingFields(oldHostNode, newHostNode, orphanOldClasses,
			 anonNodeResolver);

			// docs/topology/04-tiers-and-rejection.md §4 严格模式：只要本轮存在**未被唯一证据证成**的候选配对，就熔断整个宿主组。
			//
			// 两个计数的含义（合起来才是"无法唯一证明"的完整集合）：
			//   • ambiguousMatches —— 历史上用于统计"允许 minDiff 的层"（Tier 1）的仲裁次数。
			//     Tier 3 的 minDiff 已被拓扑相等过滤取代，因此现在几乎恒为 0。
			//   • ambiguousPairs  —— 四层走完后，在最宽谓词（Tier 4）下**仍未唯一确定**的候选对数。
			//     非严格模式下这些类退化为"新增/孤儿"。
			//
			// strict 的定位是**安全门，不是匹配策略**：它不改变任何"双向唯一"配对（含拓扑判定）的结论。
			if (RemoldAgent.ANON_STRICT) {
				int unproven = stats.ambiguousMatches + stats.ambiguousPairs;
				if (unproven > 0) {
					throw new AlignmentRejectedException(hostSlash,
					 "strict mode: " + unproven + " candidate pair(s) lack unique evidence"
					 + " (minDiff-arbitrated=" + stats.ambiguousMatches + " at Tier 1,"
					 + " unresolved-after-bi-unique=" + stats.ambiguousPairs + " at Tier 2/4) (docs/topology/04-tiers-and-rejection.md §4)");
				}
			} else if (stats.ambiguousPairs > 0) {
				// ⚠️ 保守性损失必须**可见**：非严格模式下不仲裁意味着相关的旧匿名类保持为孤儿、
				// 其**存活实例继续跑旧逻辑**。若这里不打日志，用户看到热更"成功"却没有任何效果，
				// 会误以为已生效（曾评估的夹具 L：两个同构匿名类原地改体，两条编辑都不作用于存活实例）。
				//
				// 文案要求（docs/topology/04-tiers-and-rejection.md §4）：
				//   ① 点名宿主 + 冲突的旧匿名类（各自扫描可见存活实例数）；
				//   ② 点名可能持有这些实例的字段（"方法内每帧 new" 与 "字段长期持有" 处置完全不同）；
				//   ③ 在 RESTART 旁给出免重启逃生舱 @RemoldReinit。
				List<String> liveOrphans = new ArrayList<>();
				for (String orphan : orphanOldClasses) {
					if (hotswapAlignerHasLiveInstances.test(orphan.replace('/', '.'))) {
						liveOrphans.add(orphan);
					}
				}
				StringBuilder detail = new StringBuilder();
				if (!stats.ambiguousDetail.isEmpty()) {
					int limit = Math.min(stats.ambiguousDetail.size(), 6);
					detail.append(" Ambiguity detail: ");
					for (int i = 0; i < limit; i++) {
						if (i > 0) detail.append(" | ");
						detail.append(stats.ambiguousDetail.get(i));
					}
					if (stats.ambiguousDetail.size() > limit) {
						detail.append(" | ... (").append(stats.ambiguousDetail.size() - limit).append(" more)");
					}
				}
				RemoldAgent.warn("[ANON_ALIGN] Host " + hostSlash + ": " + stats.ambiguousPairs
				                  + " candidate pair(s) lack unique evidence; refused to guess"
				                  + " (topology-equal candidates " + stats.topologyCandidatesBefore
				                  + " -> " + stats.topologyCandidatesAfter + ", topology-decided " + stats.topologyMatches + ")."
				                  + " Orphan old anonymous classes: " + orphanOldClasses
				                  + (liveOrphans.isEmpty()
				                     ? " (no scan-visible live instances on record)"
				                     : " (with LIVE instances: " + liveOrphans + ")")
				                  + (holdingFields.isEmpty()
				                     ? " No host field holds them — the orphaned instances may only be `new`ed inside"
				                       + " methods, so the next call recreates them and the edit still takes effect there."
				                     : (holdingFields.proven.isEmpty()
				                        ? ""
				                        : " Holder field(s) (proven by assignment flow): " + holdingFields.proven + ".")
				                       + (holdingFields.candidates.isEmpty()
				                          ? ""
				                          : " Candidate holder field(s) (declared as the supertype/interface): "
				                            + holdingFields.candidates + "."))
				                  + " Their LIVE instances keep running the OLD code — RESTART is required for those edits"
				                  + " to affect existing instances, OR add @RemoldReinit to the holding field (and tweak its"
				                  + " initializer) to rebuild the instance without restarting."
				                  + detail);
			}

			// 后置严格校验 (Validation Invariants)：单射/前缀 + 改名后字段布局
			validateRenameMap(renameMap, hostSlash, matchedNewToOld);
			dbg("renameMap=" + renameMap);

			// 应用 ClassRemapper 重写所有新匿名类字节码
			Map<String, byte[]> alignedAnonClasses = new LinkedHashMap<>();
			for (AnonInfo n : newInfos) {
				String targetName = renameMap.get(n.name);
				byte[] remapped   = remapClass(n.bytecode, renameMap);
				alignedAnonClasses.put(targetName, remapped);
			}

			// 应用 ClassRemapper 重写宿主类字节码
			byte[] alignedHostBytes = newHostBytes != null ? remapClass(newHostBytes, renameMap) : null;

			return new Result(alignedHostBytes, alignedAnonClasses, renameMap, orphanOldClasses, stats);
		} finally {
			// 恢复调用前的实例判定，避免本次注入泄漏到后续调用
			hotswapAlignerHasLiveInstances = prevHasLive;
		}
	}

	/**
	 * 使用 ASM ClassRemapper 重写单个类的字节码。
	 */
	public static byte[] remapClass(byte[] classBytes, Map<String, String> renameMap) {
		if (classBytes == null || classBytes.length == 0 || renameMap == null || renameMap.isEmpty()) {
			return classBytes;
		}
		boolean hasNonIdentity = false;
		for (Map.Entry<String, String> entry : renameMap.entrySet()) {
			if (!entry.getKey().equals(entry.getValue())) {
				hasNonIdentity = true;
				break;
			}
		}
		if (!hasNonIdentity) {
			return classBytes;
		}

		ClassReader   cr       = new ClassReader(classBytes);
		ClassWriter   cw       = new ClassWriter(0);
		ClassRemapper remapper = new ClassRemapper(cw, new SimpleRemapper(renameMap));
		cr.accept(remapper, 0);
		return cw.toByteArray();
	}

	public static int getHierarchyLevel(String hostSlash, String anonSlash) {
		if (hostSlash == null || anonSlash == null || !anonSlash.startsWith(hostSlash + "$")) return 1;
		String suffix = anonSlash.substring(hostSlash.length() + 1);
		int    count  = 1;
		for (int i = 0; i < suffix.length(); i++) {
			if (suffix.charAt(i) == '$') count++;
		}
		return count;
	}

	public static String getParentName(String hostSlash, String anonSlash) {
		if (anonSlash == null || hostSlash == null) return hostSlash;
		int lastDollar = anonSlash.lastIndexOf('$');
		if (lastDollar <= hostSlash.length()) return hostSlash;
		return anonSlash.substring(0, lastDollar);
	}

	public static class EnclosingMethodInfo {
		public final String name;
		public final String desc;

		public EnclosingMethodInfo(String name, String desc) {
			this.name = name;
			this.desc = desc;
		}
	}

	/**
	 * 为本宿主构造"描述符定向屏蔽器"：把 {@code L本宿主$<纯数字>;} 归一为 {@code #ANON_k#}，
	 * 其余描述符原样返回（含 {@code val$a:I}、{@code Ljava/lang/String;} 以及具名内部类引用）。
	 *
	 * <p>每次调用返回**独立**的实例（relId 从 0 起）；调用方必须**每类一个** —— 同一实例跨多个
	 * 类复用会让 relId 编号串起来，两侧不对称。对齐器内部就是这么用的。</p>
	 */
	public static Function<String, String> anonymousDescMasker(String hostSlash) {
		MethodFingerprinter fp = new MethodFingerprinter();
		fp.setContext(hostSlash);
		return fp::maskDescriptor;
	}

	public static ClassNode parseHostNode(String hostSlash, Function<String, byte[]> resolver) {
		if (hostSlash == null || resolver == null) return null;
		try {
			byte[] bytes = resolver.apply(hostSlash);
			if (bytes == null) {
				bytes = resolver.apply(hostSlash.replace('/', '.'));
			}
			if (bytes == null) return null;
			ClassNode cn = new ClassNode();
			new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return cn;
		} catch (Throwable t) {
			return null;
		}
	}

	public static void validateRenameMap(Map<String, String> renameMap, String hostSlash) {
		validateRenameMap(renameMap, hostSlash, null);
	}

	/**
	 * 后置校验（{@code docs/topology/04-tiers-and-rejection.md} §4 同一拒绝通道）。除单射/前缀不变量外，若给了配对表，还对每对已配对的类做
	 * **改名后字段布局**校验：把新类的原始字段描述符过一遍最终 {@code renameMap}，必须与旧类同名字段
	 * 描述符相等，否则整组拒绝。
	 *
	 * <p>为什么需要它：Tier 4 的布局门为了容纳 {@code this$N} 位移，比较的是**屏蔽后**描述符，
	 * 这是个宽松近似 —— {@code val$x:LFoo$1; → LFoo$2;} 若 {@code Foo$2} 最终映射到别处，是真实
	 * 类型变更却会被判等放行。门的宽松近似不能变成静默放行，故由这条兜底。</p>
	 */
	public static void validateRenameMap(Map<String, String> renameMap, String hostSlash,
	                                     Map<AnonInfo, AnonInfo> matchedNewToOld) {
		if (renameMap == null || renameMap.isEmpty()) return;
		Set<String> seenTargets = new HashSet<>();
		for (Map.Entry<String, String> e : renameMap.entrySet()) {
			String src = e.getKey();
			String tgt = e.getValue();
			if (!seenTargets.add(tgt)) {
				throw new AlignmentRejectedException(hostSlash,
				 "non-injective mapping: multiple classes map to " + tgt + " [ANON_ALIGN_VALIDATION]");
			}
			String srcParent      = getParentName(hostSlash, src);
			String expectedPrefix = renameMap.getOrDefault(srcParent, srcParent);
			if (!tgt.startsWith(expectedPrefix + "$")) {
				throw new AlignmentRejectedException(hostSlash,
				 "prefix invariant violated for " + src + " -> " + tgt +
				 " (expected prefix: " + expectedPrefix + "$) [ANON_ALIGN_VALIDATION]");
			}
		}
		if (matchedNewToOld != null && !matchedNewToOld.isEmpty()) {
			org.objectweb.asm.commons.Remapper remapper =
			 new org.objectweb.asm.commons.SimpleRemapper(renameMap);
			for (Map.Entry<AnonInfo, AnonInfo> e : matchedNewToOld.entrySet()) {
				verifyFieldLayoutAfterRename(remapper, hostSlash, e.getKey(), e.getValue());
			}
		}
	}

	/** 改名后布局校验：新字段描述符经最终 renameMap 改写后，必须等于旧字段描述符。 */
	private static void verifyFieldLayoutAfterRename(org.objectweb.asm.commons.Remapper remapper,
	                                                 String hostSlash, AnonInfo n, AnonInfo o) {
		Map<String, String> newByName = new HashMap<>();
		for (LayoutGate.FieldInfo fi : n.rawFieldInfos) {
			newByName.put(fi.name(), remapper.mapDesc(fi.desc()));
		}
		for (LayoutGate.FieldInfo fi : o.rawFieldInfos) {
			String rewritten = newByName.get(fi.name());
			if (rewritten != null && !rewritten.equals(fi.desc())) {
				throw new AlignmentRejectedException(hostSlash,
				 "post-rename field layout mismatch on field '" + fi.name() + "': new '" + fi.desc()
				 + "' rewrites to '" + rewritten + "' but old is '" + fi.desc()
				 + "' — the field's anonymous-class type does not survive renaming, so this host group"
				 + " cannot be redefined safely. The host group is rejected; hot-swap again or restart"
				 + " the JVM for this edit to take effect [ANON_ALIGN_VALIDATION]");
			}
		}
	}

	/**
	 * 严格准入分类器：校验字节码是否为真正的匿名内部类。
	 * <p>综合 JVM 规范的 {@code InnerClasses} 属性（匿名类 innerName 必为 null）、
	 * {@code ACC_ENUM} 排除以及名称模式校验。</p>
	 */
	public static boolean isAnonymousClass(ClassNode cn, String hostClassName) {
		if (cn == null || hostClassName == null) return false;
		if ((cn.access & Opcodes.ACC_ENUM) != 0) return false;
		if (cn.innerClasses != null) {
			for (org.objectweb.asm.tree.InnerClassNode icn : cn.innerClasses) {
				if (cn.name.equals(icn.name)) {
					if (icn.innerName != null) {
						return false;
					}
					break;
				}
			}
		}
		return isAnonymousClassName(hostClassName, cn.name);
	}

	/** 字节版：解析失败一律按"不是匿名类"处理（解不开的字节也进不了对齐器，布局门仍会兜底）。 */
	public static boolean isAnonymousClass(byte[] bytes, String hostClassName) {
		if (bytes == null || hostClassName == null) return false;
		try {
			ClassNode cn = new ClassNode();
			new ClassReader(bytes).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return isAnonymousClass(cn, hostClassName);
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * 批次路由：某个磁盘类是否应作为「宿主类下属匿名类」进入宿主对齐组。
	 *
	 * <p>名字只做前置筛选，准入必须看字节。枚举常量体 {@code Op$1} 的名字与匿名类同形
	 * （{@code $\d+} 结尾），但它带 {@code ACC_ENUM}、由 {@code Op.<clinit>} 构造，是<b>独立类</b>——
	 * {@link #isAnonymousClass(ClassNode, String)} 明确排除。名字-only 判定会把它塞进宿主对齐组；
	 * 对齐器拒绝 {@code ACC_ENUM} 候选、不产出任何重命名/对齐字节，随后批次把它当"已位移的旧名"
	 * 丢掉，于是它**静默地不参与重定义**（S22：懒加载的枚举常量体停留在旧版本）。</p>
	 */
	public static boolean isBatchAnonClass(String className, byte[] bytes) {
		if (className == null || bytes == null) return false;
		String host = batchAnonHost(className);
		if (host == null || host.equals(className)) return false;
		if (!isAnonymousClassName(host, className)) return false;
		return isAnonymousClass(bytes, host);
	}

	/** 匿名类的直接宿主（去掉尾部 {@code $数字} 链）。调用方须先用 {@link #isBatchAnonClass} 确认它是真匿名类。 */
	public static String batchAnonHost(String className) {
		return className == null ? null : className.replaceAll("\\$\\d+(\\$\\d+)*$", "");
	}

	/**
	 * 纯函数：批次匿名类按**类自身**归属分组（一个类只归一个宿主）。
	 *
	 * <p>批次路由必须用这张表，不能拿"宿主前缀"去过滤整个批次：前者保证每个类只属于一个宿主，
	 * 后者会让某个宿主的对齐周期看到并移走别的宿主的类（跨宿主误归属 / 误移出）。</p>
	 */
	public static java.util.Map<String, java.util.Map<String, byte[]>> groupBatchAnons(
		java.util.Map<String, byte[]> changedClasses) {
		java.util.Map<String, java.util.Map<String, byte[]>> out = new java.util.LinkedHashMap<>();
		if (changedClasses == null) return out;
		for (java.util.Map.Entry<String, byte[]> e : changedClasses.entrySet()) {
			if (!isBatchAnonClass(e.getKey(), e.getValue())) continue;
			out.computeIfAbsent(batchAnonHost(e.getKey()), k -> new java.util.LinkedHashMap<>())
				.put(e.getKey(), e.getValue());
		}
		return out;
	}

	/**
	 * 判断一个类名是否属于宿主类的匿名内部类。
	 */
	public static boolean isAnonymousClassName(String hostClassName, String className) {
		if (hostClassName == null || className == null) return false;
		String host = hostClassName.replace('.', '/');
		String cls  = className.replace('.', '/');
		if (!cls.startsWith(host + "$")) return false;
		String suffix = cls.substring(host.length() + 1);
		if (suffix.isEmpty()) return false;
		for (int i = 0; i < suffix.length(); i++) {
			char ch = suffix.charAt(i);
			if (!Character.isDigit(ch) && ch != '$') {
				return false;
			}
		}
		return !suffix.contains("$$") && !suffix.startsWith("$") && !suffix.endsWith("$");
	}

	public static int parseIndex(String hostClassName, String className) {
		if (!isAnonymousClassName(hostClassName, className)) return -1;
		int lastDollar = className.lastIndexOf('$');
		if (lastDollar < 0 || lastDollar + 1 >= className.length()) return -1;
		try {
			return Integer.parseInt(className.substring(lastDollar + 1));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	//region Internal Matching Logic

	/**
	 * 拓扑签名（{@code docs/topology/04-tiers-and-rejection.md} §2「Tier 3 多候选」判据 / {@code docs/status.md} §1）。
	 *
	 * <p><b>这是与内容哈希正交的独立维度，严禁折进指纹</b> —— 否则会违反 INV-1（自描述指纹），
	 * 让"改一个后代"污染整条祖先链的哈希，`AnonClassReproTest` Scenario 25 会立刻变红。
	 * 它同样**不得**回调其它 aligner（守 INV-2），只由 {@link #computeTopologySignature} 从
	 * 单个 {@code ClassNode} + resolver 现算。</p>
	 *
	 * <p>字段刻意保持"粗粒度"：只回答"这个类创建了哪些孩子"，不做递归 topology hash ——
	 * 递归形态正是 INV-1 反对的东西。</p>
	 *
	 * <p>{@code null} 签名表示**未知**（例如某个子类字节码取不到），与"全部计数为 0"必须区分：
	 * 未知一律判"无信息"，不得当作与任何签名相等。</p>
	 */
	static final class TopologySignature {
		/** 直接匿名子类个数（按名字层级：其直接父类正是本类） */
		final int          anonChildren;
		/** 更深的匿名后代个数 */
		final int          anonDescendants;
		/** {@code invokedynamic} 数量，即 lambda 创建点数量 */
		final int          indySites;
		/** 合成 {@code lambda$...} 方法个数 */
		final int          lambdaMethods;
		/** 直接匿名子类的 kind 多重集（排序后）：super + 接口，类名已做匿名类屏蔽 */
		final List<String> childKinds;

		TopologySignature(int anonChildren, int anonDescendants, int indySites, int lambdaMethods,
		                  List<String> childKinds) {
			this.anonChildren = anonChildren;
			this.anonDescendants = anonDescendants;
			this.indySites = indySites;
			this.lambdaMethods = lambdaMethods;
			this.childKinds = Collections.unmodifiableList(childKinds);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof TopologySignature)) return false;
			TopologySignature t = (TopologySignature) o;
			return anonChildren == t.anonChildren
			       && anonDescendants == t.anonDescendants
			       && indySites == t.indySites
			       && lambdaMethods == t.lambdaMethods
			       && childKinds.equals(t.childKinds);
		}

		@Override
		public int hashCode() {
			return Objects.hash(anonChildren, anonDescendants, indySites, lambdaMethods, childKinds);
		}

		@Override
		public String toString() {
			return "(anonChild=" + anonChildren + ", anonDesc=" + anonDescendants
			       + ", indy=" + indySites + ", lambdaM=" + lambdaMethods + ", kinds=" + childKinds + ")";
		}
	}

	/**
	 * 从单个 ClassNode 现算拓扑签名。**任何子类字节码取不到即返回 {@code null}（未知）**，
	 * 绝不退化成 0 —— 否则夹具 M 那种"真的没有子节点"会被和"取不到"混为一谈。
	 */
	private static TopologySignature computeTopologySignature(ClassNode cn, String hostSlash,
	                                                          Function<String, byte[]> resolver) {
		if (cn == null || cn.methods == null) return null;
		int         anonChildren = 0, anonDescendants = 0, indySites = 0, lambdaMethods = 0;
		Set<String> childNames   = new LinkedHashSet<>();
		for (MethodNode mn : cn.methods) {
			if (mn.name != null && mn.name.startsWith("lambda$")) lambdaMethods++;
			if (mn.instructions == null) continue;
			for (org.objectweb.asm.tree.AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode) {
					indySites++;
					continue;
				}
				if (insn.getOpcode() != Opcodes.NEW || !(insn instanceof org.objectweb.asm.tree.TypeInsnNode)) continue;
				String target = ((org.objectweb.asm.tree.TypeInsnNode) insn).desc;
				if (target == null || !isAnonymousClassName(hostSlash, target)) continue;
				if (getParentName(hostSlash, target).equals(cn.name)) {
					anonChildren++;
					childNames.add(target);
				} else if (target.startsWith(cn.name + "$")) {
					anonDescendants++;
				}
			}
		}
		List<String> kinds = new ArrayList<>(childNames.size());
		for (String child : childNames) {
			byte[] cb = resolveBytes(resolver, child);
			if (cb == null) return null;                       // 未知 ≠ 0
			ClassNode cc = new ClassNode();
			try {
				new ClassReader(cb).accept(cc, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			} catch (Throwable t) {
				return null;
			}
			List<String> itf = cc.interfaces == null ? new ArrayList<>() : new ArrayList<>(cc.interfaces);
			Collections.sort(itf);
			StringBuilder sb = new StringBuilder(maskAnonRef(cc.superName, hostSlash));
			for (String i : itf) sb.append(';').append(maskAnonRef(i, hostSlash));
			kinds.add(sb.toString());
		}
		Collections.sort(kinds);
		return new TopologySignature(anonChildren, anonDescendants, indySites, lambdaMethods, kinds);
	}

	/** 与 {@code parseInfos} 相同的解析回退顺序（斜杠名 → 点分名）。 */
	private static byte[] resolveBytes(Function<String, byte[]> resolver, String internalName) {
		if (resolver == null) return null;
		byte[] b = resolver.apply(internalName);
		if (b == null) b = resolver.apply(internalName.replace('/', '.'));
		if (b == null) b = resolver.apply(internalName.replace('.', '/'));
		return b;
	}

	/**
	 * 把指向本宿主匿名类的内部名抹成**固定占位符** {@code #ANON#}。
	 *
	 * <p>与 {@code MethodFingerprinter.maskDescriptor} 同一条前缀规则，但这里刻意不用
	 * {@code #ANON_<relId>#}：relId 是按"遇到顺序"分配的实例状态，新旧两侧的分配顺序未必对应，
	 * 用它反而会引入比较不对称。本维度只需要屏蔽掉"会随位移改变"的编号。</p>
	 *
	 * <p>这条屏蔽是必须的，理由与 {@code docs/topology/03-cascading-pipeline.md} §2 那个缺陷同源：嵌套匿名类的父类/接口引用里嵌着会位移的名字，
	 * 不屏蔽就会把结构相同的两个类误判为拓扑不等。</p>
	 */
	private static String maskAnonRef(String internalName, String hostSlash) {
		if (internalName == null || hostSlash == null) return internalName;
		return isAnonymousClassName(hostSlash, internalName) ? "#ANON#" : internalName;
	}

	static class AnonInfo {
		final String                     name;
		final byte[]                     bytecode;
		final Long                       contentHash;
		final String                     superName;
		final List<String>               interfaces;
		final String                     outerMethod;
		final String                     outerMethodDesc;
		final List<String>               fields;
		final List<String>               methods;
		final int                        orderIndex;
		/** 拓扑签名；{@code null} 表示未知（不得当作与任何签名相等） */
		final TopologySignature          topology;
		/**
		 * 布局门的判定输入（{@code LayoutGate.of(...)} 的产物）。
		 *
		 * <p>为什么不复用 {@link #fields}：它是 {@code name:desc} 字符串，
		 * <b>不含访问标志</b>，因此看不出 static 性变化 —— 而那正是布局门必须单独比较的一档
		 * （见 {@code LayoutGate.Verdict.CHANGED_STATICNESS}）。</p>
		 */
		final List<LayoutGate.FieldInfo> fieldInfos;
		/**
		 * **未屏蔽**的字段表（原始描述符）。后置校验 {@code validateRenameMap} 用它：把新字段
		 * 描述符过一遍最终 {@code renameMap}，必须等于旧描述符 —— 屏蔽只负责让配对能发生，
		 * 真正"改名后布局确实相同"由这条兜底（否则 {@code val$x:LFoo$1; → LFoo$2;} 这种
		 * "屏蔽后判等、实际映射到别处"的真实类型变更会被静默放行）。
		 */
		final List<LayoutGate.FieldInfo> rawFieldInfos;

		AnonInfo(
		 String name,
		 byte[] bytecode,
		 Long contentHash,
		 String superName,
		 List<String> interfaces,
		 String outerMethod,
		 String outerMethodDesc,
		 List<String> fields,
		 List<String> methods,
		 int orderIndex,
		 TopologySignature topology,
		 List<LayoutGate.FieldInfo> fieldInfos,
		 List<LayoutGate.FieldInfo> rawFieldInfos) {
			this.name = name;
			this.bytecode = bytecode;
			this.contentHash = contentHash;
			this.superName = superName;
			this.interfaces = interfaces;
			this.outerMethod = outerMethod;
			this.outerMethodDesc = outerMethodDesc;
			this.fields = fields;
			this.methods = methods;
			this.orderIndex = orderIndex;
			this.topology = topology;
			this.fieldInfos = fieldInfos;
			this.rawFieldInfos = rawFieldInfos;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AnonInfo anonInfo = (AnonInfo) o;
			return Objects.equals(name, anonInfo.name);
		}

		@Override
		public int hashCode() {
			return name != null ? name.hashCode() : 0;
		}

		@Override
		public String toString() {
			return name + "(#hash=" + contentHash + ", outer=" + outerMethod + ", order=" + orderIndex + ")";
		}
	}

	private static Map<String, byte[]> normalizeMap(Map<String, byte[]> input, String hostSlash) {
		Map<String, byte[]> result = new LinkedHashMap<>();
		if (input == null) return result;
		for (Map.Entry<String, byte[]> entry : input.entrySet()) {
			String name = entry.getKey().replace('.', '/');
			if (isAnonymousClassName(hostSlash, name)) {
				result.put(name, entry.getValue());
			}
		}
		return result;
	}

	public static String normalizeEnclosingMethod(String outerMethod) {
		if (outerMethod == null) return null;
		// Kotlin lambda naming: foo$lambda$0 or foo$lambda-0
		int kLambdaIdx = outerMethod.indexOf("$lambda");
		if (kLambdaIdx > 0) {
			return outerMethod.substring(0, kLambdaIdx);
		}
		if (outerMethod.startsWith("lambda$")) {
			// 在 JDK 8 中，lambda 体内的匿名类其 EnclosingMethod 指向 lambda$foo$0，
			// 在 JDK 17/21 中则直接指向外层源码方法 foo。
			// 统一规约为源码方法名，抹除 lambda 编号位移与跨 JDK 差异。
			int lastDollar = outerMethod.lastIndexOf('$');
			if (lastDollar > 7) {
				String  suffix    = outerMethod.substring(lastDollar + 1);
				boolean allDigits = true;
				for (int i = 0; i < suffix.length(); i++) {
					if (!Character.isDigit(suffix.charAt(i))) {
						allDigits = false;
						break;
					}
				}
				if (allDigits) {
					return outerMethod.substring(7, lastDollar);
				}
			}
			return outerMethod.substring(7);
		}
		return outerMethod;
	}

	public static EnclosingMethodInfo resolveHostMethodForAnon(String hostSlash, ClassNode hostNode, String anonSlash) {
		if (hostNode == null || hostSlash == null || anonSlash == null || hostNode.methods == null) return null;
		try {
			List<MethodNode> instantiators = new ArrayList<>();
			for (MethodNode mn : hostNode.methods) {
				if (mn.instructions == null) continue;
				for (org.objectweb.asm.tree.AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getOpcode() == Opcodes.NEW && insn instanceof org.objectweb.asm.tree.TypeInsnNode) {
						org.objectweb.asm.tree.TypeInsnNode tin = (org.objectweb.asm.tree.TypeInsnNode) insn;
						if (anonSlash.equals(tin.desc)) {
							instantiators.add(mn);
							break;
						}
					}
				}
			}

			if (instantiators.isEmpty()) {
				RemoldAgent.log("[ANON_ALIGN] Could not find instantiator method in host " + hostSlash + " for anonymous class " + anonSlash);
				return null;
			}
			if (instantiators.size() > 1) {
				RemoldAgent.log("[ANON_ALIGN] Multiple instantiator methods found for " + anonSlash + ": " + instantiators.size() + " candidates. Picking first.");
			}

			MethodNode  current = instantiators.get(0);
			Set<String> visited = new HashSet<>();
			visited.add(current.name + ":" + current.desc);

			// 顺着 indy 和方法调用向上追溯调用链，直到定位到真正的源码宿主方法名与描述符（具备深度上限与死循环保护）
			int depth = 0;
			while (current != null && ++depth <= 32) {
				String norm = normalizeEnclosingMethod(current.name);
				if (norm != null && !norm.equals("null") && !norm.isEmpty() && !norm.startsWith("lambda$")) {
					return new EnclosingMethodInfo(norm, current.desc);
				}
				MethodNode caller = findCallerMethod(hostNode, current.name, current.desc, visited);
				if (caller == null) {
					if (norm != null && !norm.equals("null") && !norm.isEmpty()) {
						return new EnclosingMethodInfo(norm, current.desc);
					}
					break;
				}
				visited.add(caller.name + ":" + caller.desc);
				current = caller;
			}
			RemoldAgent.log("[ANON_ALIGN] Failed to trace call chain to source method for " + anonSlash + " (bottom=" + instantiators.get(0).name + ")");
		} catch (Throwable ignored) { }
		return null;
	}

	public static String resolveHostMethodForAnon(String hostSlash, String anonSlash, Function<String, byte[]> resolver) {
		ClassNode           hostNode = parseHostNode(hostSlash, resolver);
		EnclosingMethodInfo info     = resolveHostMethodForAnon(hostSlash, hostNode, anonSlash);
		return info != null ? info.name : null;
	}

	private static MethodNode findCallerMethod(ClassNode hostNode, String calleeMethodName, String calleeMethodDesc,
	                                           Set<String> visited) {
		for (MethodNode mn : hostNode.methods) {
			if (visited.contains(mn.name + ":" + mn.desc) || mn.instructions == null) continue;
			for (org.objectweb.asm.tree.AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) {
					for (Object bsmArg : indy.bsmArgs) {
						if (bsmArg instanceof org.objectweb.asm.Handle h) {
							if (hostNode.name.equals(h.getOwner()) && calleeMethodName.equals(h.getName())
							    && (calleeMethodDesc == null || calleeMethodDesc.equals(h.getDesc()))) {
								return mn;
							}
						}
					}
				} else if (insn instanceof org.objectweb.asm.tree.MethodInsnNode min) {
					if (hostNode.name.equals(min.owner) && calleeMethodName.equals(min.name)
					    && (calleeMethodDesc == null || calleeMethodDesc.equals(min.desc))) {
						return mn;
					}
				}
			}
		}
		return null;
	}

	private static List<AnonInfo> parseInfos(
	 String hostSlash,
	 ClassNode hostNode,
	 Map<String, byte[]> classes,
	 Function<String, byte[]> resolver) {
		List<AnonInfo>    list      = new ArrayList<>();
		Map<String, Long> hashCache = new HashMap<>();
		// 直接父类的 ClassNode 缓存。嵌套匿名类（Foo$1$1）的实例化点位于**它的直接父匿名类
		// Foo$1** 的方法里，而不是宿主 Foo 里 —— 拿宿主去扫嵌套层永远找不到实例化点
		// （scratch/hstest 探针 DeepNestProbe 实测：宿主扫描返回 null，父类扫描返回 work()V）。
		Map<String, ClassNode> parentNodeCache = new HashMap<>();

		for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
			String name  = entry.getKey();
			byte[] bytes = entry.getValue();
			if (bytes == null || bytes.length == 0) continue;

			ClassNode cn = new ClassNode();
			// 这里**不能**再 SKIP_CODE：拓扑签名需要读指令流（NEW / invokedynamic）。
			// 代价可控 —— 每个匿名类原先被 AnonClassHasher 完整解析一次，这里复用同一次结构解析。
			new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			if (!isAnonymousClass(cn, hostSlash)) continue;

			Long              hash     = AnonClassHasher.hash(name, bytes, hostSlash, resolver, hashCache, null, 0);
			TopologySignature topology = computeTopologySignature(cn, hostSlash, resolver);

			String       superName  = cn.superName != null ? cn.superName : "java/lang/Object";
			List<String> interfaces = cn.interfaces != null ? new ArrayList<>(cn.interfaces) : new ArrayList<>();
			Collections.sort(interfaces);

			String outerMethod     = normalizeEnclosingMethod(cn.outerMethod);
			String outerMethodDesc = cn.outerMethodDesc;
			// 针对 javac 8 的嵌套 lambda 缺陷（EnclosingMethod 生成虚拟的 lambda$null$0，
			// normalizeEnclosingMethod 会把它归约成字面量 "null"）：回退到字节码扫描，
			// 反查实例化点并沿调用链溯源到真实源码方法。
			//
			// 扫描上下文必须是**直接父类**（level 1 时父类即宿主），否则嵌套匿名类永远扫不到：
			//   Foo$1$1 的 NEW 指令在 Foo$1 里，不在 Foo 里。
			if (outerMethod == null || "null".equals(outerMethod)) {
				String scanSlash = getParentName(hostSlash, name);
				ClassNode scanNode = scanSlash.equals(hostSlash)
				 ? hostNode
				 : (resolver != null ? parentNodeCache.computeIfAbsent(scanSlash, k -> parseHostNode(k, resolver)) : null);
				if (scanNode != null) {
					EnclosingMethodInfo hostMethodInfo = resolveHostMethodForAnon(scanSlash, scanNode, name);
					if (hostMethodInfo != null) {
						outerMethod = hostMethodInfo.name;
						outerMethodDesc = hostMethodInfo.desc;
					}
				}
			}

			// 描述符必须屏蔽匿名类位移（this$N:LOuter$K;）——与哈希器同源。**字段与方法共用同一个
			// masker**，使同一 LOuter$K; 在 fields 与 methods 两处得到同一 relId（与 AnonClassHasher
			// 里"字段+方法共用一个 fp"的口径一致）。每类一个 fresh masker 保证 old/new 确定性一致。
			MethodFingerprinter descMasker = new MethodFingerprinter();
			descMasker.setContext(hostSlash);

			List<String> fields = new ArrayList<>();
			if (cn.fields != null) {
				for (FieldNode fn : cn.fields) {
					fields.add(fn.name + ":" + descMasker.maskDescriptor(fn.desc));
				}
				Collections.sort(fields);
			}

			List<String> methods = new ArrayList<>();
			if (cn.methods != null) {
				for (MethodNode mn : cn.methods) {
					// 排除合成方法
					if ((mn.access & Opcodes.ACC_SYNTHETIC) == 0) {
						// 嵌套匿名类的 <init> 形如 `<init>(LOuter$1;)V`，父类位移后描述符必变，
						// Tier 3 的结构签名也要屏蔽，否则内容变化时又会漏回 Tier 4。
						methods.add(mn.name + ":" + descMasker.maskDescriptor(mn.desc));
					}
				}
				Collections.sort(methods);
			}

			int orderIdx = parseIndex(hostSlash, name);
			list.add(new AnonInfo(
			 name, bytes, hash, superName, interfaces,
			 outerMethod, outerMethodDesc, fields, methods, orderIdx, topology,
			 LayoutGate.of(cn.fields, descMasker::maskDescriptor),
			 LayoutGate.of(cn.fields)
			));
		}

		// 按 orderIndex 排序
		list.sort(Comparator.comparingInt(a -> a.orderIndex));
		return list;
	}

	private static Map<AnonInfo, AnonInfo> matchHierarchical(
	 List<AnonInfo> oldList, List<AnonInfo> newList, AlignmentStats stats,
	 String hostSlash, long startNanos,
	 ClassNode oldHostNode, ClassNode newHostNode,
	 Function<String, ClassNode> anonNodeResolver) {
		Map<AnonInfo, AnonInfo> matchedNewToOld = new LinkedHashMap<>();
		List<AnonInfo>          oldToUse        = new ArrayList<>(oldList);
		List<AnonInfo>          newToUse        = new ArrayList<>(newList);
		if (TEST_REVERSE_ORDER) {
			Collections.reverse(oldToUse);
			Collections.reverse(newToUse);
		}

		Set<AnonInfo> remainingOld = new LinkedHashSet<>(oldToUse);
		Set<AnonInfo> remainingNew = new LinkedHashSet<>(newToUse);

		// Tier 1: 内容哈希精确相同 + 宿主方法相同 (允许 minDiff 仲裁，且强制要求 contentHash != null)
		matchTier(1, remainingNew, remainingOld, matchedNewToOld, stats, (n, o) ->
			n.contentHash != null
			&& Objects.equals(n.contentHash, o.contentHash)
			&& Objects.equals(n.outerMethod, o.outerMethod)
			&& Objects.equals(n.outerMethodDesc, o.outerMethodDesc),
		 true, false, hostSlash, startNanos
		);

		// Tier 2: 内容哈希全局精确相同 (禁止跨方法 minDiff，仅全类唯一孤本采纳，且强制要求 contentHash != null)
		matchTier(2, remainingNew, remainingOld, matchedNewToOld, stats, (n, o) ->
			n.contentHash != null
			&& Objects.equals(n.contentHash, o.contentHash),
		 false, false, hostSlash, startNanos
		);

		// Tier 3: 结构签名相同（应对修改方法体导致的哈希变化，允许 minDiff 仲裁）
		// 同宿主方法、同父类、同接口、同字段、同声明方法
		matchTier(3, remainingNew, remainingOld, matchedNewToOld, stats, (n, o) ->
			Objects.equals(n.outerMethod, o.outerMethod)
			&& Objects.equals(n.outerMethodDesc, o.outerMethodDesc)
			&& Objects.equals(n.superName, o.superName)
			&& Objects.equals(n.interfaces, o.interfaces)
			&& Objects.equals(n.fields, o.fields)
			&& Objects.equals(n.methods, o.methods),
		 true, true, hostSlash, startNanos
		);

		// Tier 4: 松散结构（同宿主方法 + 同基类与接口，禁止多候选 minDiff 盲猜，直接拒绝）
		//
		// 注意谓词是 Tier 3 的**真超集**（少了 fields/methods 两项），这条包含关系正是
		// "Tier 3 的拒绝不会被 Tier 4 绕过"的依据：双向唯一性对边数单调递减，同一剩余集上
		// Tier 3 非双向唯一 ⇒ 边更多的 Tier 4 也非双向唯一。夹具 M 的"零配对"断言守住它。
		MatchPredicate tier4Predicate = (n, o) ->
		 Objects.equals(n.outerMethod, o.outerMethod)
		 && Objects.equals(n.outerMethodDesc, o.outerMethodDesc)
		 && Objects.equals(n.superName, o.superName)
		 && Objects.equals(n.interfaces, o.interfaces);
		// ---- 实例状态布局门（docs/topology/07-layout-gate-and-risks.md §1 的精确变体）----
		//
		// 只作用在 Tier 4：Tier 1/2 的哈希含字段表、Tier 3 显式比较 fields，
		// 所以能跨字段布局配对的只有 Tier 4。门加在这里，Tier 1~3 的语义完全不动。
		//
		// 为什么必须在这一层挡：布局变化后**存活实例**读新字段得零值，而这类字段
		// （val$*/this$0，合成）被 ClassDiffUtil 过滤，InitFix 永远补不到。
		MatchPredicate gateWrapped = tier4Predicate;
		if (!LayoutGate.MODE_OFF.equals(RemoldAgent.ANON_LAYOUT_GATE)) {
			gateWrapped = (n, o) -> {
				if (!tier4Predicate.test(n, o)) return false;
				LayoutGate.Result res = LayoutGate.check(o.fieldInfos, n.fieldInfos);
				if (res.compatible()) return true;

				// 布局不兼容。下一步取决于"有没有存活实例需要保护"：
				//   • 无存活实例 → 放行（新建实例会走新构造器，初始化正常；真机实验结论）
				//   • 有存活实例 → reject 模式拒绝配对；warn 模式放行但强告警
				boolean hasLive = hotswapAlignerHasLiveInstances.test(o.name.replace('/', '.'));
				if (!hasLive) {
					stats.layoutGateWaived++;
					// 同一条 liveness 判据：只能表述为“扫描可见的活实例为 0”，不得表述成“实例已死”。
					RemoldAgent.warn("[ANON-LAYOUT] " + n.name + " -> " + o.name
					                  + ": incompatible layout but no scan-visible live instances; pairing anyway"
					                  + " (new instances initialize correctly). This is what the heap scan saw, not"
					                  + " proof that no instance exists. Reason: " + res.detail());
					return true;
				}
				if (LayoutGate.MODE_WARN.equals(RemoldAgent.ANON_LAYOUT_GATE)) {
					stats.layoutGateWaived++;
					RemoldAgent.warn("[ANON-LAYOUT] " + n.name + " -> " + o.name
					                  + ": incompatible layout with LIVE instances, pairing anyway because"
					                  + " remold.agent.anon_layout_gate=warn. Surviving instances will read 0 for"
					                  + " the affected field until they are recreated. Reason: " + res.detail());
					return true;
				}
				// reject：不配对。新类随后分配未占用编号，旧类成为孤儿并保留（docs/topology/01-invariants-and-remapping.md §2），
				// 存活实例继续跑旧逻辑 —— 安全但不再更新，所以必须让用户看得见。
				stats.layoutGateRejected++;
				RemoldAgent.warn("[ANON-LAYOUT] " + n.name + " -> " + o.name
				                  + ": REFUSED to pair (incompatible layout with live instances)."
				                  + " The old class is kept as an orphan; its LIVE instances keep running the OLD"
				                  + " code, so this edit will NOT take effect for them until they are recreated."
				                  + " Reason: " + res.detail());
				return false;
			};
		}
		matchTier(4, remainingNew, remainingOld, matchedNewToOld, stats, gateWrapped,
		 false, false, hostSlash, startNanos
		);

		// 注意：坚决移除旧版 Tier 5（按物理类名盲配）。若前 4 层均未匹配，
		// 表明该类为新增类或旧类已删除，严格作为孤儿类保留或新类生成新编号，杜绝内存篡夺。

		// ---- Tier 3.5：结构同形但方法体不同的"带证据消歧" ----
		//
		// Tier 3 只比字段表/方法签名，两个同接口同结构的匿名类会互相成为对方的候选，
		// 双向唯一因此失效（真实客户端 R2b：`topology-equal candidates 2 -> 2, topology-decided 0`）。
		// 这里在**方法体结构**上补一层证据：屏蔽匿名类编号后比较指令流相似度，
		// 只在"最优候选显著优于次优（严格 margin）且双向一致"时采纳 —— 不做距离最近式的盲猜。
		//
		// strict 模式刻意不参与：strict 的定位是"没有唯一证据就整组拒绝"，
		// 相似度对它是"证据不足"而不是"唯一证据"，保持原有拒绝语义（不变量：不放松既有安全门）。
		if (!RemoldAgent.ANON_STRICT && !remainingNew.isEmpty() && !remainingOld.isEmpty()) {
			matchByBodySimilarity(remainingNew, remainingOld, matchedNewToOld, stats,
			 tier4Predicate, hostSlash, startNanos);
		}

		// ---- Tier 4.5：扫描可见零存活的确定性兜底 ----
		//
		// 三道护栏缺一不可：
		//   ① 严格置后：只有 Tier 3.5 仍无法消歧才进入这里；
		//   ② 全组扫描可见零存活 + 布局 100% 兼容：歧义组内**每一个**旧候选在**本轮堆扫描**下
		//      都没有可见存活实例，且配对双方的父类/接口/字段表/方法签名完全一致、LayoutGate 判兼容；
		//   ③ 可观测：日志显式打 `deterministic-zero-scan-pair (scan-visible live instances: 0)`，
		//      不伪装成结构唯一匹配，也不把“扫描没看见”表述成“实例已死”
		//      （{@code docs/topology/04-tiers-and-rejection.md} §2.4）。
		//
		// 证据强度（别把它读强）：liveness 判定的语义是“本轮 JVMTI 堆扫描对这些旧类**没有可见实例**”，
		// 它**不是**“这些实例已经死亡”的证明 —— 扫描只能回答它看见了什么，不能反证不可见实例不存在；
		// 宿主字段也可能继续持有（或在扫描之后创建）这些类的实例。因此本层只能当作“确定性配对的可审计
		// 线索”，不是语义安全证明；日志文案必须照此表述，且被字段持有者必须点名。
		if (!RemoldAgent.ANON_STRICT && !remainingNew.isEmpty() && !remainingOld.isEmpty()) {
			matchZeroScanPairs(remainingNew, remainingOld, matchedNewToOld, stats,
			 tier4Predicate, hostSlash, startNanos, oldHostNode, newHostNode, anonNodeResolver);
		}

		// 四层全部走完后，在**最终剩余集**上用最宽谓词（Tier 4）统计一次"缺乏唯一证据"的候选对。
		//
		// 只数一次是刻意的：Tier 2/3/4 的剩余集是同一批对象，逐层各数一次会把同一批候选
		// 重复计入（实测 Tier 3+4 让夹具 M 报 2、夹具 L 报 8）。这里是 docs/topology/04-tiers-and-rejection.md §4 strict 熔断
		// 与诊断的唯一口径。
		if (stats != null && !remainingNew.isEmpty() && !remainingOld.isEmpty()) {
			Map<AnonInfo, List<AnonInfo>> n2o = new LinkedHashMap<>();
			Map<AnonInfo, List<AnonInfo>> o2n = new LinkedHashMap<>();
			for (AnonInfo n : remainingNew) {
				for (AnonInfo o : remainingOld) {
					if (tier4Predicate.test(n, o)) {
						n2o.computeIfAbsent(n, k -> new ArrayList<>()).add(o);
						o2n.computeIfAbsent(o, k -> new ArrayList<>()).add(n);
					}
				}
			}
			stats.ambiguousPairs += countAmbiguous(remainingNew, remainingOld, n2o, o2n);
			// 同时留下可读明细：点名每个仍被歧义缠住的旧类、它的新候选、以及本轮扫描是否看见存活实例。
			// 只记一次（与 ambiguousPairs 同口径），供宿主级告警原文引用。
			if (stats.ambiguousPairs > 0) {
				for (AnonInfo o : remainingOld) {
					List<AnonInfo> suitors = o2n.get(o);
					if (suitors == null || suitors.size() < 2) continue;
					boolean live = hotswapAlignerHasLiveInstances.test(o.name.replace('/', '.'));
					List<String> names = new ArrayList<>(suitors.size());
					for (AnonInfo s : suitors) names.add(s.name);
					stats.ambiguousDetail.add(o.name + " (scanVisibleLive=" + (live ? 1 : 0) + ", candidatesNew=" + names + ")");
				}
			}
		}

		return matchedNewToOld;
	}

	@FunctionalInterface
	private interface MatchPredicate {
		boolean test(AnonInfo n, AnonInfo o);
	}

	private static class CandidatePair {
		final AnonInfo n;
		final AnonInfo o;
		final int      diff;

		CandidatePair(AnonInfo n, AnonInfo o) {
			this.n = n;
			this.o = o;
			this.diff = Math.abs(n.orderIndex - o.orderIndex);
		}
	}

	private static final Comparator<CandidatePair> PAIR_COMPARATOR = (p1, p2) -> {
		int cmp = Integer.compare(p1.diff, p2.diff);
		if (cmp != 0) return cmp;
		cmp = Integer.compare(p1.n.orderIndex, p2.n.orderIndex);
		if (cmp != 0) return cmp;
		cmp = Integer.compare(p1.o.orderIndex, p2.o.orderIndex);
		if (cmp != 0) return cmp;
		cmp = p1.n.name.compareTo(p2.n.name);
		if (cmp != 0) return cmp;
		return p1.o.name.compareTo(p2.o.name);
	};

	private static void matchTier(
	 int tier,
	 Set<AnonInfo> remainingNew,
	 Set<AnonInfo> remainingOld,
	 Map<AnonInfo, AnonInfo> matchedNewToOld,
	 AlignmentStats stats,
	 MatchPredicate predicate,
	 boolean allowMinDiff,
	 boolean topologyFilter,
	 String hostSlash,
	 long startNanos) {
		if (remainingNew.isEmpty() || remainingOld.isEmpty()) return;

		Map<AnonInfo, List<AnonInfo>> newToOld = new LinkedHashMap<>();
		Map<AnonInfo, List<AnonInfo>> oldToNew = new LinkedHashMap<>();
		for (AnonInfo n : remainingNew) {
			// O(N^2) 的主体就在这里，超时检查点放在外层循环即可覆盖绝大部分耗时
			checkTimeout(hostSlash, startNanos);
			for (AnonInfo o : remainingOld) {
				if (predicate.test(n, o)) {
					newToOld.computeIfAbsent(n, k -> new ArrayList<>()).add(o);
					oldToNew.computeIfAbsent(o, k -> new ArrayList<>()).add(n);
				}
			}
		}

		if (newToOld.isEmpty()) return;

		// 第一趟：双向互为唯一候选（Bi-directional Unique Matching）
		// 消除单向唯一误判导致的遍历顺序依赖
		List<AnonInfo> uniqueNew = new ArrayList<>();
		List<AnonInfo> uniqueOld = new ArrayList<>();
		for (Map.Entry<AnonInfo, List<AnonInfo>> entry : newToOld.entrySet()) {
			AnonInfo       n       = entry.getKey();
			List<AnonInfo> oldList = entry.getValue();
			if (oldList.size() == 1) {
				AnonInfo       o       = oldList.get(0);
				List<AnonInfo> newList = oldToNew.get(o);
				if (newList != null && newList.size() == 1) {
					uniqueNew.add(n);
					uniqueOld.add(o);
				}
			}
		}

		for (int i = 0; i < uniqueNew.size(); i++) {
			AnonInfo n = uniqueNew.get(i);
			AnonInfo o = uniqueOld.get(i);
			matchedNewToOld.put(n, o);
			remainingOld.remove(o);
			remainingNew.remove(n);
			recordTierMatch(stats, tier);
			if (RemoldAgent.DEBUG || tier >= 4) {
				RemoldAgent.info("[ANON_MATCH] Tier " + tier + " bi-unique paired: " + n.name + " -> " + o.name);
			}
		}

		if (!allowMinDiff) {
			// 低置信层（Tier 4）不允许 minDiff 盲猜：剩余候选留给调用方统一统计（见 matchHierarchical 末尾）。
			return;
		}

		// ---- Tier 3：拓扑相等过滤取代 minDiff 仲裁（docs/topology/04-tiers-and-rejection.md §2 与 docs/status.md §1）----
		//
		// 为什么必须换：minDiff 用物理名序号，前插场景下"插在前面的新类"总以 diff=0 抢走旧身份
		// —— 这是确定性但语义错误的 tie-breaker（`DeepNestProbe` depth 1 即可复现）。
		// 为什么只在 Tier 3：Tier 1 的候选拥有**全等内容哈希**，选谁都不改变语义，minDiff 无害；
		// 冒然改它会扩大回归面（实测全套 Tier 1 minDiff 命中为 0）。
		//
		// 为什么不需要额外"否决集"来防 Tier 4 绕过：Tier 4 的谓词是 Tier 3 的真超集
		// （少了 fields/methods 两项），而双向唯一性对边数单调递减 —— 同一剩余集上 Tier 3 非双向唯一
		// ⇒ 边更多的 Tier 4 必然也非双向唯一。夹具 M 的"零配对"断言即是这条不变量的守卫。
		if (topologyFilter) {
			applyTopologyFilter(remainingNew, remainingOld, newToOld, matchedNewToOld, stats, hostSlash);
			// 过滤后仍未配对的候选：不再猜，按安全降级（新增/孤儿）处理。
			// 统计留给调用方（见 matchHierarchical 末尾），避免与 Tier 4 重复计数。
			return;
		}
		if (remainingNew.isEmpty() || remainingOld.isEmpty()) return;

		// 第二趟：存在 1-to-N 或 N-to-1 歧义候选，按 minDiff 绝对确定性仲裁（具备完全的顺序无关性）
		List<CandidatePair> conflictPairs = new ArrayList<>();
		for (AnonInfo n : remainingNew) {
			List<AnonInfo> oldList = newToOld.get(n);
			if (oldList == null) continue;
			for (AnonInfo o : oldList) {
				if (remainingOld.contains(o)) {
					conflictPairs.add(new CandidatePair(n, o));
				}
			}
		}

		if (conflictPairs.isEmpty()) return;
		conflictPairs.sort(PAIR_COMPARATOR);

		Set<AnonInfo> usedNew = new HashSet<>();
		Set<AnonInfo> usedOld = new HashSet<>();
		for (CandidatePair pair : conflictPairs) {
			if (usedNew.contains(pair.n) || usedOld.contains(pair.o)) continue;
			if (!remainingNew.contains(pair.n) || !remainingOld.contains(pair.o)) continue;

			usedNew.add(pair.n);
			usedOld.add(pair.o);
			matchedNewToOld.put(pair.n, pair.o);
			remainingNew.remove(pair.n);
			remainingOld.remove(pair.o);

			stats.ambiguousMatches++;
			recordTierMatch(stats, tier);
			System.err.println("[WARN-ANON] Ambiguous anonymous class match in Tier " + tier +
			                   " resolved by minDiff: " + pair.n.name + " -> " + pair.o.name +
			                   " (diff=" + pair.diff + ")");
			if (RemoldAgent.DEBUG || tier >= 4) {
				RemoldAgent.info("[ANON_MATCH] Tier " + tier + " fallback paired (diff=" + pair.diff + "): " + pair.n.name + " -> " + pair.o.name);
			}
		}
	}

	// ---------------------------------------------------------------- Tier 3.5：带证据的体相似度消歧

	/** Tier 3.5 采纳下限：最优候选的方法体结构相似度必须达到该值。 */
	private static final double BODY_MIN_SCORE = 0.80;

	/** Tier 3.5 严格 margin：最优与次优的相似度差必须达到该值，否则视为证据不足。 */
	private static final double BODY_MARGIN = 0.15;

	/** 指令数超过该阈值时用多重集重叠率替代 LCS，避免病理输入上的二次方开销。 */
	private static final int BODY_LCS_MAX_INSNS = 2000;

	/**
	 * Tier 3.5：在结构同形的候选集里，用方法体结构相似度做带 margin 的消歧。
	 *
	 * <p>为什么需要这一层：Tier 3 的结构签名（宿主方法 / 父类 / 接口 / 字段表 / 方法签名）
	 * 对「同接口同结构的两个匿名类」没有任何区分力，双向唯一因此失效，编辑被迫孤儿化
	 * （真实客户端 R2b 实测 {@code topology-equal candidates 2 -> 2, topology-decided 0}）。</p>
	 *
	 * <p>纪律：只采纳「最优显著优于次优且双向一致」的配对，绝不做距离最近式盲猜；
	 * 常量池字面量不进入 token（只改字符串时相似度接近 1），匿名类编号在 owner/desc 上
	 * 统一屏蔽（重编号不影响相似度）。strict 模式不参与本层：strict 的定位是「没有唯一证据
	 * 就整组拒绝」，相似度对它是「证据不足」而非「唯一证据」。</p>
	 */
	private static void matchByBodySimilarity(
	 Set<AnonInfo> remainingNew, Set<AnonInfo> remainingOld,
	 Map<AnonInfo, AnonInfo> matchedNewToOld, AlignmentStats stats,
	 MatchPredicate shape, String hostSlash, long startNanos) {

		Map<AnonInfo, Map<AnonInfo, Double>> scores = new LinkedHashMap<>();
		for (AnonInfo n : remainingNew) {
			checkTimeout(hostSlash, startNanos);
			Map<AnonInfo, Double> row = new LinkedHashMap<>();
			for (AnonInfo o : remainingOld) {
				if (!shape.test(n, o)) continue;
				row.put(o, bodySimilarity(o, n, hostSlash));
			}
			if (!row.isEmpty()) scores.put(n, row);
		}
		if (scores.isEmpty()) return;

		Map<AnonInfo, AnonInfo> bestOld   = new LinkedHashMap<>();
		Map<AnonInfo, Double>  bestVal   = new LinkedHashMap<>();
		Map<AnonInfo, Double>  secondVal = new LinkedHashMap<>();
		for (Map.Entry<AnonInfo, Map<AnonInfo, Double>> e : scores.entrySet()) {
			AnonInfo bo = null;
			double   b  = -1, s2 = -1;
			for (Map.Entry<AnonInfo, Double> c : e.getValue().entrySet()) {
				double sc = c.getValue();
				if (sc > b) {
					s2 = b;
					b = sc;
					bo = c.getKey();
				} else if (sc > s2) {
					s2 = sc;
				}
			}
			bestOld.put(e.getKey(), bo);
			bestVal.put(e.getKey(), b);
			secondVal.put(e.getKey(), s2);
		}

		for (Map.Entry<AnonInfo, AnonInfo> e : new LinkedHashMap<>(bestOld).entrySet()) {
			AnonInfo n = e.getKey();
			AnonInfo o = e.getValue();
			if (n == null || o == null) continue;
			if (!remainingNew.contains(n) || !remainingOld.contains(o)) continue;
			double b  = bestVal.get(n);
			double s2 = secondVal.get(n);
			if (b < BODY_MIN_SCORE) continue;
			if (s2 >= 0 && b - s2 < BODY_MARGIN) continue;

			// 反向一致：该旧类的最优新类必须是 n，且同样带 margin
			AnonInfo back = null;
			double   bb = -1, bs2 = -1;
			for (Map.Entry<AnonInfo, Map<AnonInfo, Double>> row : scores.entrySet()) {
				Double sc = row.getValue().get(o);
				if (sc == null) continue;
				if (sc > bb) {
					bs2 = bb;
					bb = sc;
					back = row.getKey();
				} else if (sc > bs2) {
					bs2 = sc;
				}
			}
			if (back != n) continue;
			if (bb < BODY_MIN_SCORE) continue;
			if (bs2 >= 0 && bb - bs2 < BODY_MARGIN) continue;

			matchedNewToOld.put(n, o);
			remainingNew.remove(n);
			remainingOld.remove(o);
			stats.bodySimilarityMatches++;
			RemoldAgent.info("[ANON_MATCH] Tier 3.5 body-similarity paired: " + n.name + " -> " + o.name
			 + " (score=" + fmtScore(b) + ", margin=" + fmtScore(s2 < 0 ? 1.0 : b - s2) + ")");
		}
	}

	private static String fmtScore(double v) {
		return String.format(java.util.Locale.ROOT, "%.3f", v);
	}

	/**
	 * 两个匿名类的方法体结构相似度（0..1）。
	 *
	 * <p>口径：按「方法名 + 屏蔽后的描述符」对齐方法；每个方法比较屏蔽后的指令流 token，
	 * 用 LCS 比（超长退化为多重集重叠率）；只在一侧出现的方法以 0 贡献计入分母，
	 * 因此新增/删除方法会实质降低相似度。字面量常量不入 token。</p>
	 */
	static double bodySimilarity(AnonInfo a, AnonInfo b, String hostSlash) {
		if (a == null || b == null) return 0.0;
		return bodySimilarity(a.bytecode, b.bytecode, hostSlash);
	}

	/**
	 * 字节码级入口：直接比较两份匿名类字节的方法体结构相似度。
	 *
	 * <p>与 Tier 3.5 内部使用同一实现，供判定力测试在**不依赖 javac 编号**的前提下
	 * 钉住"只改字面量 ≈ 1.0 / 结构不同 &lt; 0.8"的口径。</p>
	 */
	public static double bodySimilarity(byte[] aBytes, byte[] bBytes, String hostSlash) {
		ClassNode ca = parseBytes(aBytes);
		ClassNode cb = parseBytes(bBytes);
		if (ca == null || cb == null) return 0.0;
		Map<String, List<String>> ma = methodTokens(ca, hostSlash);
		Map<String, List<String>> mb = methodTokens(cb, hostSlash);
		if (ma.isEmpty() && mb.isEmpty()) return 1.0;

		double matchedWeight = 0.0;
		double totalWeight   = 0.0;
		for (Map.Entry<String, List<String>> e : ma.entrySet()) {
			List<String> ta = e.getValue();
			double w = Math.max(1, ta.size());
			totalWeight += w;
			List<String> tb = mb.get(e.getKey());
			if (tb == null) continue;
			matchedWeight += w * lcsRatio(ta, tb);
		}
		for (Map.Entry<String, List<String>> e : mb.entrySet()) {
			if (ma.containsKey(e.getKey())) continue;
			totalWeight += Math.max(1, e.getValue().size());
		}
		return totalWeight == 0 ? 1.0 : Math.max(0.0, Math.min(1.0, matchedWeight / totalWeight));
	}

	private static ClassNode parseBytes(byte[] bytes) {
		if (bytes == null || bytes.length == 0) return null;
		try {
			ClassNode cn = new ClassNode();
			new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return cn;
		} catch (Throwable t) {
			return null;
		}
	}

	/** 方法键（名字 + 屏蔽描述符）到屏蔽后指令 token 序列的映射。 */
	private static Map<String, List<String>> methodTokens(ClassNode cn, String hostSlash) {
		Map<String, List<String>> out = new LinkedHashMap<>();
		if (cn == null || cn.methods == null) return out;
		MethodFingerprinter masker = new MethodFingerprinter();
		masker.setContext(hostSlash);
		for (MethodNode mn : cn.methods) {
			if (mn.instructions == null) continue;
			List<String> tokens = new ArrayList<>();
			for (org.objectweb.asm.tree.AbstractInsnNode insn = mn.instructions.getFirst();
			     insn != null; insn = insn.getNext()) {
				tokens.add(bodyToken(insn, masker, hostSlash));
			}
			if (tokens.isEmpty()) continue;
			out.put(mn.name + ":" + masker.maskDescriptor(mn.desc), tokens);
		}
		return out;
	}

	/**
	 * 单个指令的 token。
	 *
	 * <p>只保留结构：操作码 + 被屏蔽的 owner/name/描述符；不把常量池字面量
	 * （{@code LDC} 的值、{@code BIPUSH} 的数值）纳入 token —— 否则「只改一个字符串」
	 * 会让相似度崩到 0，而那正是本层要识别的场景。</p>
	 */
	private static String bodyToken(org.objectweb.asm.tree.AbstractInsnNode insn,
	                                MethodFingerprinter masker, String hostSlash) {
		int op = insn.getOpcode();
		if (insn instanceof org.objectweb.asm.tree.MethodInsnNode m) {
			return "M" + op + ":" + maskAnonRef(m.owner, hostSlash) + "." + m.name
			 + ":" + masker.maskDescriptor(m.desc);
		}
		if (insn instanceof org.objectweb.asm.tree.FieldInsnNode f) {
			return "F" + op + ":" + maskAnonRef(f.owner, hostSlash) + "." + f.name
			 + ":" + masker.maskDescriptor(f.desc);
		}
		if (insn instanceof org.objectweb.asm.tree.TypeInsnNode t) {
			return "T" + op + ":" + maskAnonRef(t.desc, hostSlash);
		}
		if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) {
			return "I:" + indy.name + ":" + masker.maskDescriptor(indy.desc);
		}
		return "O" + op;
	}

	private static double lcsRatio(List<String> a, List<String> b) {
		int n = a.size(), m = b.size();
		if (n == 0 && m == 0) return 1.0;
		if (n == 0 || m == 0) return 0.0;
		if (n > BODY_LCS_MAX_INSNS || m > BODY_LCS_MAX_INSNS) {
			java.util.Map<String, Integer> counts = new java.util.HashMap<>();
			for (String t : a) counts.merge(t, 1, Integer::sum);
			int overlap = 0;
			for (String t : b) {
				Integer c = counts.get(t);
				if (c != null && c > 0) {
					overlap++;
					counts.put(t, c - 1);
				}
			}
			return (2.0 * overlap) / (n + m);
		}
		int[] prev = new int[m + 1];
		int[] cur  = new int[m + 1];
		for (int i = 1; i <= n; i++) {
			for (int j = 1; j <= m; j++) {
				cur[j] = a.get(i - 1).equals(b.get(j - 1))
				 ? prev[j - 1] + 1
				 : Math.max(prev[j], cur[j - 1]);
			}
			int[] tmp = prev;
			prev = cur;
			cur = tmp;
			java.util.Arrays.fill(cur, 0);
		}
		return (2.0 * prev[m]) / (n + m);
	}

	// ---------------------------------------------------------------- Tier 4.5：扫描可见零存活兜底

	/**
	 * Tier 4.5：当歧义组内每一个旧候选类在**本轮堆扫描**下都没有可见存活实例、且所有候选对的
	 * 父类/接口/字段表/方法签名完全一致并通过 {@link LayoutGate} 时，按 minDiff 做确定性配对。
	 *
	 * <p><b>证据强度（文案与注释都必须照此表述）</b>：这里的“零存活”是
	 * <b>“扫描可见的活实例为 0”</b>，不是“这些实例已经死亡”。扫描只能回答它看见了什么，
	 * 不能反证不可见实例不存在；宿主字段也可能继续持有（或在扫描之后创建）这些类的实例。
	 * 因此本层是“确定性配对的可审计线索”，不是语义安全证明；只要组内有任何一个旧候选被扫描
	 * 判定为有可见实例，本层就完全不介入（把决定权交回孤儿化 + 告警）。
	 * 日志必须显式打出 {@code deterministic-zero-scan-pair (scan-visible live instances: 0)}，
	 * 并在宿主字段可能持有时点名字段（{@code docs/topology/04-tiers-and-rejection.md} §2.4）。</p>
	 */
	private static void matchZeroScanPairs(
	 Set<AnonInfo> remainingNew, Set<AnonInfo> remainingOld,
	 Map<AnonInfo, AnonInfo> matchedNewToOld, AlignmentStats stats,
	 MatchPredicate shape, String hostSlash, long startNanos,
	 ClassNode oldHostNode, ClassNode newHostNode,
	 Function<String, ClassNode> anonNodeResolver) {

		// 身份语义（IdentityHashMap / identity set）是**必须**的：AnonInfo.equals 按类名比较，
		// 而新旧两侧的同名匿名类（Host$1 对 Host$1）在很多批次里同名 —— 用 LinkedHashMap
		// 会把两侧节点合并成一个，连通分量随即错乱（旧的被当成新的，组件恒为 1-1 而被跳过）。
		Map<AnonInfo, Set<AnonInfo>> adj = new java.util.IdentityHashMap<>();
		Set<AnonInfo> newSide = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		Set<AnonInfo> oldSide = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		newSide.addAll(remainingNew);
		oldSide.addAll(remainingOld);
		for (AnonInfo n : remainingNew) {
			checkTimeout(hostSlash, startNanos);
			for (AnonInfo o : remainingOld) {
				if (!shape.test(n, o)) continue;
				adj.computeIfAbsent(n, k -> new LinkedHashSet<>()).add(o);
				adj.computeIfAbsent(o, k -> new LinkedHashSet<>()).add(n);
			}
		}
		if (adj.isEmpty()) return;

		Set<AnonInfo> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		List<AnonInfo> seeds = new ArrayList<>(remainingNew);
		seeds.addAll(remainingOld);
		for (AnonInfo seed : seeds) {
			if (!adj.containsKey(seed) || !visited.add(seed)) continue;
			List<AnonInfo> comp = new ArrayList<>();
			java.util.Deque<AnonInfo> queue = new java.util.ArrayDeque<>();
			queue.add(seed);
			while (!queue.isEmpty()) {
				AnonInfo cur = queue.poll();
				comp.add(cur);
				for (AnonInfo next : adj.getOrDefault(cur, Collections.emptySet())) {
					if (visited.add(next)) queue.add(next);
				}
			}
			List<AnonInfo> olds = new ArrayList<>();
			List<AnonInfo> news = new ArrayList<>();
			for (AnonInfo c : comp) {
				// 侧别判定必须走身份集合，不能走 remainingOld/remainingNew 的 contains（同名类会串侧）。
				if (oldSide.contains(c)) olds.add(c);
				else if (newSide.contains(c)) news.add(c);
			}
			if (olds.isEmpty() || news.isEmpty()) continue;
			if (olds.size() == 1 && news.size() == 1) continue;

			// 护栏 ②-a：歧义组内每一个旧候选在**本轮堆扫描**下都不得有可见存活实例。
			// 判定的语义是“扫描可见的活实例为 0”，不是“实例已死”：只要扫描看见任何一个，本层就不介入
			// （保守方向；扫描看不见的实例是否真实存在，不由本判据回答）。
			List<String> liveOlds = new ArrayList<>();
			for (AnonInfo o : olds) {
				if (hotswapAlignerHasLiveInstances.test(o.name.replace('/', '.'))) liveOlds.add(o.name);
			}
			if (!liveOlds.isEmpty()) {
				if (stats != null) {
					stats.ambiguousDetail.add("old=" + namesOf(olds) + " new=" + namesOf(news)
					 + " scanVisibleLiveOld=" + liveOlds);
				}
				continue;
			}

			// 护栏 ②-b：所有候选对必须布局 100% 兼容（父类/接口/字段表/方法签名 + LayoutGate）
			boolean layoutOk = true;
			for (AnonInfo n : news) {
				for (AnonInfo o : olds) {
					if (!shape.test(n, o)) continue;
					if (!strictShapeEquals(n, o)
					    || !LayoutGate.check(o.fieldInfos, n.fieldInfos).compatible()) {
						layoutOk = false;
						break;
					}
				}
				if (!layoutOk) break;
			}
			if (!layoutOk) {
				if (stats != null) {
					stats.ambiguousDetail.add("old=" + namesOf(olds) + " new=" + namesOf(news)
					 + " layout-incompatible");
				}
				continue;
			}

			// 护栏 ③：确定性配对 + 显式留痕
			List<CandidatePair> pairs = new ArrayList<>();
			for (AnonInfo n : news) {
				for (AnonInfo o : olds) {
					if (shape.test(n, o)) pairs.add(new CandidatePair(n, o));
				}
			}
			pairs.sort(PAIR_COMPARATOR);
			Set<AnonInfo> usedNew = new HashSet<>();
			Set<AnonInfo> usedOld = new HashSet<>();
			for (CandidatePair p : pairs) {
				if (usedNew.contains(p.n) || usedOld.contains(p.o)) continue;
				usedNew.add(p.n);
				usedOld.add(p.o);
				matchedNewToOld.put(p.n, p.o);
				remainingNew.remove(p.n);
				remainingOld.remove(p.o);
				if (stats != null) {
					stats.deterministicZeroScanPairs++;
					stats.deterministicDeadPairs = stats.deterministicZeroScanPairs;   // 0.2.6 兼容别名
				}
				// 被配对旧类的持有者字段审计：字段持有的实例不会因为“扫描没看见”而消失。
				// 文案要求（docs/topology/04-tiers-and-rejection.md §2.4）：
				//   ① 写“扫描可见的活实例为 0”，不得写成“实例已死/安全”；
				//   ② 宿主字段可能持有时必须点名（proven 与 candidate 两种强度分开，不混说）。
				HoldingFields holders = findHoldingFields(oldHostNode, newHostNode,
				 Collections.singleton(p.o.name), anonNodeResolver);
				String line = "[ANON_ALIGN] deterministic-zero-scan-pair (scan-visible live instances: 0): "
				 + p.n.name + " -> " + p.o.name + " (minDiff=" + p.diff + ")"
				 + " — the ONLY basis for this pass is: the heap scan saw no live instances for these old"
				 + " candidates (scan-visible live instances = 0), and both sides' layouts are fully compatible."
				 + " Instances the scan could not see are NOT disproven by this line, so this is neither proof"
				 + " that the instances are dead, nor a unique-structural-match or semantic-safety conclusion."
				 + " If a field holds this anonymous class long-term, treat this line as an audit lead."
				 + zeroScanPairHolderNote(holders);
				// proven 持有者（精确描述符 / 赋值流）与“扫描零可见”直接冲突，升级为 warn 让人看见。
				if (holders.proven.isEmpty()) RemoldAgent.info(line);
				else RemoldAgent.warn(line);
			}
		}
	}

	/**
	 * Tier 4.5 日志里“宿主字段可能持有被配对旧类”的补充说明。
	 *
	 * <p>字段持有不会因为“扫描没看见实例”而消失：proven（精确描述符 / 赋值流）与
	 * candidate（父类型/接口）两种证据强度分开列，且不把任一强度说成“字段里现在一定有实例”。</p>
	 */
	private static String zeroScanPairHolderNote(HoldingFields holders) {
		if (holders == null || holders.isEmpty()) return "";
		StringBuilder sb = new StringBuilder();
		if (!holders.proven.isEmpty()) {
			sb.append(" Holder field(s) that receive this class (exact descriptor, or NEW -> <init> ->")
			  .append(" PUTFIELD/PUTSTATIC): ").append(holders.proven)
			  .append(" — such a field can keep an instance alive even when this scan reports none;")
			  .append(" verify it before treating the pairing as safe.");
		}
		if (!holders.candidates.isEmpty()) {
			sb.append(" Candidate holder field(s) (declared as its supertype/interface): ")
			  .append(holders.candidates)
			  .append(" — candidate strength only (the field may hold another implementation);")
			  .append(" a candidate is not proof of a live instance, and the scan's 0 is not proof either.");
		}
		return sb.toString();
	}

	/** Tier 3 的结构签名口径（父类/接口/字段表/方法签名全等）。 */
	private static boolean strictShapeEquals(AnonInfo n, AnonInfo o) {
		return Objects.equals(n.superName, o.superName)
		 && Objects.equals(n.interfaces, o.interfaces)
		 && Objects.equals(n.fields, o.fields)
		 && Objects.equals(n.methods, o.methods);
	}

	private static String namesOf(List<AnonInfo> infos) {
		List<String> names = new ArrayList<>(infos.size());
		for (AnonInfo i : infos) names.add(i.name);
		return names.toString();
	}

	/**
	 * 匿名类实例的宿主字段点名结果：**两种证据强度必须分开**（{@code docs/topology/04-tiers-and-rejection.md} §4 文案要求 ②），
	 * 因为处置动作不同 —— 已证实者挂 {@code @RemoldReinit} 后实例会被重建；候选只是"该字段的静态类型正好是
	 * 这个匿名类的父类型/接口"，同一个字段里也可能装着别的实现，点名是为了让人去核对，不构成结论。
	 */
	public static final class HoldingFields {

		/**
		 * 已证实的宿主字段，形如 {@code Host.field:Ldesc;}。两种来源：
		 * <ol>
		 *   <li>宿主方法体里的赋值流（{@code NEW <anon> → <init> → PUTFIELD/PUTSTATIC}）；</li>
		 *   <li>字段静态类型**精确等于**该匿名类（这种字段里除了它装不下别的类型，比"父类型候选"强一档，
		 *       同时也是上一版"精确描述符匹配"的保留路径）。</li>
		 * </ol>
		 */
		public final List<String> proven;

		/** 父类型/接口描述符候选：字段静态类型等于该匿名类的直接父类或所实现接口（全部列出，不挑一个）。 */
		public final List<String> candidates;

		HoldingFields(List<String> proven, List<String> candidates) {
			this.proven     = Collections.unmodifiableList(new ArrayList<>(proven));
			this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
		}

		/** 两种强度都为空 —— 只有这时才允许对用户说"没有宿主字段持有它们"。 */
		public boolean isEmpty() {
			return proven.isEmpty() && candidates.isEmpty();
		}

		/** 两种强度合并（兼容既有 {@code List<String>} 契约的调用方）。 */
		public List<String> all() {
			List<String> out = new ArrayList<>(proven);
			for (String c : candidates) addOnce(out, c);
			return out;
		}

		@Override public String toString() {
			return "proven=" + proven + ", candidates=" + candidates;
		}
	}

	/**
	 * 扫宿主/目标类字段表，点名"描述符里引用了这些被孤儿化匿名类"的字段（上一版语义，保持向后兼容）。
	 *
	 * <p>判据只看字段描述符里的 {@code L<anon>;} 片段：这正好覆盖 {@code Foo$1 fieldName;}
	 * 这种"字段长期持有匿名类实例"的形态。它决定了处置方式完全不同 —— 字段持有需要
	 * {@code @RemoldReinit}（或重启），方法内每帧 {@code new} 的实例调用即重建、无需处置。</p>
	 *
	 * <p>没有匿名类自身的字节码，就给不出"父类型/接口候选"；需要两种强度请用
	 * {@link #findHoldingFields(ClassNode, ClassNode, Collection, Function)}。</p>
	 */
	public static List<String> findHoldingFields(ClassNode oldHostNode, ClassNode newHostNode,
	                                     Collection<String> anonInternalNames) {
		return findHoldingFields(oldHostNode, newHostNode, anonInternalNames, null).all();
	}

	/**
	 * 点名可能长期持有被孤儿化匿名类实例的宿主字段，并区分两种证据强度。
	 *
	 * <p>三层判据，逐层退化（任一层命中就**不再**往下一层退，避免把已证实的事说成候选）：</p>
	 * <ol>
	 *   <li><b>精确描述符（proven）</b>：字段静态类型就是该匿名类；</li>
	 *   <li><b>赋值流（proven）</b>：类方法体里 {@code NEW <anon>} → {@code INVOKESPECIAL <anon>.<init>}
	 *       之后，这个实例被直接写进某个宿主字段（{@code PUTFIELD}/{@code PUTSTATIC}）。
	 *       赋值目标必须真在宿主字段表里、且 owner 是宿主 —— 匿名类构造器里的
	 *       {@code putfield this$0}（owner 是匿名类自身）因此不会被误报成宿主赋值。
	 *       <b>局部变量中转不猜</b>：窗口内一旦出现 {@code ASTORE}/{@code ALOAD}、第二个 {@code NEW}
	 *       或跳转，该实例点直接放弃（宁可漏点名，也不把"可能被赋过值"说成已证实）；</li>
	 *   <li><b>父类型/接口候选（candidates）</b>：前两层都没命中时，用宿主字段的静态描述符去匹配该匿名类的
	 *       直接父类或所实现接口（例如 {@code Supplier<String> held} 之于实现 {@code Supplier} 的匿名类）。
	 *       合成的父类型不递归展开（只看直接父类型），多个候选全部列出。超类型为
	 *       {@code java/lang/Object} 时不算候选（否则任何 {@code Object} 字段都会命中）。</li>
	 * </ol>
	 *
	 * <p>判据是纯函数：只读入参 ClassNode 的字段表与方法体，不依赖反射 / {@code Class.forName} / 全局状态。</p>
	 *
	 * @param anonResolver 匿名类内部名（也接受点分名）→ 该匿名类自身的 ClassNode；这里只用到
	 *                     {@code superName}/{@code interfaces}。传 {@code null} 时没有候选层。
	 */
	public static HoldingFields findHoldingFields(ClassNode oldHostNode, ClassNode newHostNode,
	                                     Collection<String> anonInternalNames,
	                                     Function<String, ClassNode> anonResolver) {
		List<String> proven     = new ArrayList<>();
		List<String> candidates = new ArrayList<>();
		if (anonInternalNames == null || anonInternalNames.isEmpty()) {
			return new HoldingFields(proven, candidates);
		}
		List<ClassNode> hosts = new ArrayList<>(2);
		for (ClassNode n : new ClassNode[]{oldHostNode, newHostNode}) {
			if (n != null && !hosts.contains(n)) hosts.add(n);
		}
		// 宿主字段名册：赋值流只认"宿主自己声明过的字段"，否则匿名类自身的合成字段（this$0/val$*）
		// 与宿主同名字段会互相串味。
		Map<String, String> hostFieldEntries = new LinkedHashMap<>();
		Set<String>         hostNames        = new LinkedHashSet<>();
		for (ClassNode host : hosts) {
			if (host.name != null) hostNames.add(host.name);
			if (host.fields == null) continue;
			for (FieldNode f : host.fields) {
				if (f.name == null || f.desc == null) continue;
				String key = f.name + ":" + f.desc;
				if (!hostFieldEntries.containsKey(key)) hostFieldEntries.put(key, entryOf(host.name, f));
			}
		}

		for (String raw : anonInternalNames) {
			if (raw == null) continue;
			String anon = raw.replace('.', '/');

			List<String> hits = descriptorExactFields(hosts, "L" + anon + ";");
			for (String hit : assignedHolderFields(hosts, anon, hostNames, hostFieldEntries)) addOnce(hits, hit);
			if (!hits.isEmpty()) {
				for (String hit : hits) addOnce(proven, hit);
				continue;
			}

			for (String hit : supertypeCandidateFields(anonNodeOf(anonResolver, anon), hosts)) {
				addOnce(candidates, hit);
			}
		}
		return new HoldingFields(proven, candidates);
	}

	/** 字段点名条目的统一格式：{@code Host.field:Ldesc;}。 */
	private static String entryOf(String owner, FieldNode field) {
		return owner + "." + field.name + ":" + field.desc;
	}

	private static void addOnce(List<String> out, String entry) {
		if (entry != null && !out.contains(entry)) out.add(entry);
	}

	/** 第 1 层：宿主字段的静态描述符里出现 {@code L<anon>;}（精确等于该匿名类的类型）。 */
	private static List<String> descriptorExactFields(List<ClassNode> hosts, String needle) {
		List<String> out = new ArrayList<>();
		for (ClassNode host : hosts) {
			if (host.fields == null) continue;
			for (FieldNode f : host.fields) {
				if (f.desc != null && f.desc.contains(needle)) addOnce(out, entryOf(host.name, f));
			}
		}
		return out;
	}

	/**
	 * 第 2 层：赋值流。扫宿主类全部方法体，找 {@code NEW <anon>} → {@code INVOKESPECIAL <anon>.<init>}
	 * → 紧随其后的宿主 {@code PUTFIELD}/{@code PUTSTATIC}，返回被赋值字段的条目。
	 */
	private static List<String> assignedHolderFields(List<ClassNode> hosts, String anon,
	                                                Set<String> hostNames,
	                                                Map<String, String> hostFieldEntries) {
		List<String> out = new ArrayList<>();
		for (ClassNode host : hosts) {
			if (host.methods == null) continue;
			for (MethodNode m : host.methods) {
				if (m.instructions == null) continue;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getOpcode() != Opcodes.NEW || !(insn instanceof TypeInsnNode)) continue;
					if (!anon.equals(((TypeInsnNode) insn).desc)) continue;
					addOnce(out, assignmentTargetOf(insn, anon, hostNames, hostFieldEntries));
				}
			}
		}
		return out;
	}

	/**
	 * 从一个 {@code NEW <anon>} 指令出发，判定它是否"立刻被写进宿主字段"。
	 *
	 * <p>窗口内允许的指令是 javac 发射的常规形态：{@code DUP}、构造器参数装载、{@code CHECKCAST}、
	 * 伪指令（Label/LineNumber/Frame）。{@code <init>} 之后只接受一条 {@code PUTFIELD}/{@code PUTSTATIC}，
	 * 且其 owner 必须是宿主、字段必须声明在宿主字段表里 —— 这两条把匿名类构造器里的
	 * {@code putfield this$0}（owner 是匿名类自身）排除在外。其余情形（{@code ASTORE}、再次
	 * {@code NEW}、跳转、别的方法调用）一律返回 {@code null}：那是局部变量或跨块中转，不猜。</p>
	 */
	private static String assignmentTargetOf(AbstractInsnNode newInsn, String anon,
	                                         Set<String> hostNames,
	                                         Map<String, String> hostFieldEntries) {
		boolean initSeen = false;
		int     budget   = 64;   // 指令预算：病态输入下不至于无界前行
		for (AbstractInsnNode p = newInsn.getNext(); p != null && budget-- > 0; p = p.getNext()) {
			int op = p.getOpcode();
			if (op < 0) continue;   // Label / LineNumber / FrameNode 等伪指令
			if (!initSeen) {
				if (op == Opcodes.INVOKESPECIAL && p instanceof MethodInsnNode) {
					MethodInsnNode mi = (MethodInsnNode) p;
					if (anon.equals(mi.owner) && "<init>".equals(mi.name)) {
						initSeen = true;
						continue;
					}
					return null;   // 别的方法调用 → 这个 NEW 不是"直接赋值"的那个实例点
				}
				if (isCtorOperandLoading(op)) continue;
				return null;
			}
			if (op == Opcodes.PUTFIELD || op == Opcodes.PUTSTATIC) {
				if (!(p instanceof FieldInsnNode)) return null;
				FieldInsnNode fi = (FieldInsnNode) p;
				if (fi.owner == null || !hostNames.contains(fi.owner)) return null;
				return hostFieldEntries.get(fi.name + ":" + fi.desc);
			}
			if (op == Opcodes.DUP || op == Opcodes.CHECKCAST || op == Opcodes.NOP) continue;
			return null;   // ASTORE/ALOAD/再次 NEW/跳转 → 局部变量或跨块中转，不猜
		}
		return null;
	}

	/** {@code <init>} 之前允许出现的操作数装载指令（构造器参数、接收者副本、常量）。 */
	private static boolean isCtorOperandLoading(int op) {
		switch (op) {
			case Opcodes.DUP: case Opcodes.DUP_X1: case Opcodes.DUP_X2:
			case Opcodes.NOP: case Opcodes.CHECKCAST: case Opcodes.ACONST_NULL:
			case Opcodes.ALOAD: case Opcodes.ILOAD: case Opcodes.LLOAD: case Opcodes.FLOAD: case Opcodes.DLOAD:
			case Opcodes.GETSTATIC: case Opcodes.GETFIELD: case Opcodes.LDC:
			case Opcodes.BIPUSH: case Opcodes.SIPUSH:
				return true;
			default:
				return op >= Opcodes.ICONST_M1 && op <= Opcodes.DCONST_1;
		}
	}

	/**
	 * 第 3 层：父类型/接口描述符候选。只看**直接**父类与**直接**实现接口（不递归展开层级，
	 * 也不展开父类的父类），{@code java/lang/Object} 不算候选（否则任何 {@code Object} 字段都会命中），
	 * 合成字段不算候选（挂不了 {@code @RemoldReinit}）。
	 */
	private static List<String> supertypeCandidateFields(ClassNode anonNode, List<ClassNode> hosts) {
		List<String> out = new ArrayList<>();
		if (anonNode == null) return out;
		Set<String> supertypes = new LinkedHashSet<>();
		if (anonNode.superName != null && !"java/lang/Object".equals(anonNode.superName)) {
			supertypes.add(anonNode.superName);
		}
		if (anonNode.interfaces != null) {
			for (String itf : anonNode.interfaces) {
				if (itf != null) supertypes.add(itf);
			}
		}
		if (supertypes.isEmpty()) return out;
		for (ClassNode host : hosts) {
			if (host.fields == null) continue;
			for (FieldNode f : host.fields) {
				if (f.desc == null || f.desc.length() < 3) continue;
				if (f.desc.charAt(0) != 'L' || f.desc.charAt(f.desc.length() - 1) != ';') continue;
				if ((f.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
				if (supertypes.contains(f.desc.substring(1, f.desc.length() - 1))) addOnce(out, entryOf(host.name, f));
			}
		}
		return out;
	}

	/** 用调用方给的解析器取匿名类自身节点（内部名优先，点分名兜底）；取不到就只是没有候选层。 */
	private static ClassNode anonNodeOf(Function<String, ClassNode> resolver, String anon) {
		if (resolver == null) return null;
		try {
			ClassNode node = resolver.apply(anon);
			if (node == null) node = resolver.apply(anon.replace('/', '.'));
			return node;
		} catch (Throwable t) {
			return null;   // 解析失败只影响"候选"这一层，绝不因此少点名已证实者
		}
	}

	/**
	 * 从已解析的旧匿名类信息里取该匿名类自身的 ClassNode（只读 {@code superName}/{@code interfaces}，
	 * 因此丢弃代码与调试信息即可）。对齐器在宿主级流程里没有保留匿名类的 ClassNode，孤儿集非空时按需重建。
	 */
	private static ClassNode anonNodeForHolding(List<AnonInfo> infos, String name) {
		if (infos == null || name == null) return null;
		String slash = name.replace('.', '/');
		for (AnonInfo info : infos) {
			if (info == null || !slash.equals(info.name) || info.bytecode == null || info.bytecode.length == 0) continue;
			try {
				ClassNode cn = new ClassNode();
				new ClassReader(info.bytecode).accept(cn,
				 ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				return cn;
			} catch (Throwable t) {
				return null;
			}
		}
		return null;
	}

	/**
	 * 统计"禁止 minDiff 的层"完成双向唯一配对后，仍无法确定性区分的候选对数量（{@code docs/topology/04-tiers-and-rejection.md} §4）。
	 *
	 * <p>口径：剩余新类中拥有 &gt;= 2 个剩余旧候选的个数（1-to-N），加上剩余旧类中拥有 &gt;= 2 个
	 * 剩余新候选的个数（N-to-1）。只要 &gt; 0 就说明该层存在真实歧义 —— 双向唯一配对是贪心且
	 * 完备的，凡能唯一确定的都已被取走，留下的必然是"多对多"。</p>
	 */
	private static int countAmbiguous(
	 Set<AnonInfo> remainingNew,
	 Set<AnonInfo> remainingOld,
	 Map<AnonInfo, List<AnonInfo>> newToOld,
	 Map<AnonInfo, List<AnonInfo>> oldToNew) {
		int count = 0;
		for (AnonInfo n : remainingNew) {
			List<AnonInfo> candidates = newToOld.get(n);
			if (candidates == null) continue;
			int live = 0;
			for (AnonInfo o : candidates) {
				if (remainingOld.contains(o)) live++;
			}
			if (live >= 2) count++;
		}
		for (AnonInfo o : remainingOld) {
			List<AnonInfo> candidates = oldToNew.get(o);
			if (candidates == null) continue;
			int live = 0;
			for (AnonInfo n : candidates) {
				if (remainingNew.contains(n)) live++;
			}
			if (live >= 2) count++;
		}
		return count;
	}

	/**
	 * Tier 3 的**拓扑相等过滤**（{@code docs/topology/04-tiers-and-rejection.md} §2）：在"双向唯一"之后、minDiff 之前插入的正交判据。
	 *
	 * <p>规则（刻意只做**相等**，不做距离 —— 距离会引入调参空间）：</p>
	 * <ol>
	 *   <li>对每个剩余新类，只保留"拓扑签名与旧类**严格相等**"的候选；</li>
	 *   <li>要求**双向唯一**：该旧类的存活候选里也只能有这一个新类（保持既有两趟制）；</li>
	 *   <li>反复取这样的互唯配对直到不动点；</li>
	 *   <li>剩下的（相等过滤后 0 个或 &gt;= 2 个候选，或签名未知）**一律不仲裁** —— 既不用
	 *       minDiff 猜，也不去猜"距离最近"。</li>
	 * </ol>
	 *
	 * <p>签名未知（子类字节码取不到）视为"不相等"，因此不会与任何候选配对 —— 这与"计数全为 0
	 * 且真的相等"是两种不同情形，后者仍可能配对（夹具 M 里两个候选都等于旧类的空拓扑，
	 * 于是过滤后剩 2 个 → 不仲裁）。</p>
	 */
	private static void applyTopologyFilter(
	 Set<AnonInfo> remainingNew,
	 Set<AnonInfo> remainingOld,
	 Map<AnonInfo, List<AnonInfo>> newToOld,
	 Map<AnonInfo, AnonInfo> matchedNewToOld,
	 AlignmentStats stats,
	 String hostSlash) {
		boolean progress  = true;
		boolean firstPass = true;
		while (progress) {
			progress = false;
			// 第一趟：每个剩余新类保留"拓扑严格相等且仍可用"的候选
			Map<AnonInfo, List<AnonInfo>> survivors = new LinkedHashMap<>();
			for (AnonInfo n : remainingNew) {
				List<AnonInfo> all       = newToOld.get(n);
				List<AnonInfo> keep      = new ArrayList<>();
				int            available = 0;
				if (all != null) {
					for (AnonInfo o : all) {
						if (!remainingOld.contains(o)) continue;
						available++;
						if (n.topology == null || o.topology == null) continue;   // 未知 ≠ 相等
						if (n.topology.equals(o.topology)) keep.add(o);
					}
				}
				survivors.put(n, keep);
				// 计数只在首轮做：诊断口径是"过滤前可用候选数 -> 过滤后候选数"
				if (firstPass && stats != null) {
					stats.topologyCandidatesBefore += available;
					stats.topologyCandidatesAfter += keep.size();
				}
				dbg("  tier3 topology filter: " + n.name + " topology=" + n.topology
				    + " candidates " + available + " -> " + keep.size());
			}
			firstPass = false;

			// 第二趟：先**收集**全部"双向唯一"的配对，再一次性应用。
			//
			// 两点理由：① 边遍历 remainingNew 边 remove 会抛 ConcurrentModificationException；
			// ② 逐对贪心应用会让结果依赖遍历顺序。而所有双唯配对彼此互斥（若 (n,o) 双唯，
			// 则不存在第二个 n' 的存活集含 o，也不存在 o' != o 在 n 的存活集里），
			// 因此一次性应用是幂等且与顺序无关的 —— 这正是"反序遍历映射必须一致"的前提。
			List<AnonInfo> winners  = new ArrayList<>();
			List<AnonInfo> partners = new ArrayList<>();
			for (Map.Entry<AnonInfo, List<AnonInfo>> e : survivors.entrySet()) {
				List<AnonInfo> keep = e.getValue();
				if (keep.size() != 1) continue;
				AnonInfo o       = keep.get(0);
				int      suitors = 0;
				for (List<AnonInfo> l : survivors.values()) {
					if (l.contains(o)) suitors++;
				}
				if (suitors != 1) continue;
				winners.add(e.getKey());
				partners.add(o);
			}
			for (int i = 0; i < winners.size(); i++) {
				AnonInfo n = winners.get(i);
				AnonInfo o = partners.get(i);
				matchedNewToOld.put(n, o);
				remainingNew.remove(n);
				remainingOld.remove(o);
				if (stats != null) stats.topologyMatches++;
				progress = true;
				if (RemoldAgent.DEBUG) {
					RemoldAgent.info("[ANON_MATCH] Tier 3 topology paired: " + n.name + " -> " + o.name
					                  + " " + n.topology);
				}
			}
		}
	}

	private static void recordTierMatch(AlignmentStats stats, int tier) {
		if (stats == null) return;
		switch (tier) {
			case 1:
				stats.tier1Matches++;
				break;
			case 2:
				stats.tier2Matches++;
				break;
			case 3:
				stats.tier3Matches++;
				break;
			case 4:
				stats.tier4Matches++;
				break;
			case 5:
				stats.tier5Matches++;
				break;
		}
	}

	//endregion
}
