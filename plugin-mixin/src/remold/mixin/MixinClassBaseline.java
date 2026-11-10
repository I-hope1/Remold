package remold.mixin;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import remold.RemoldAgent;

/**
 * premain 期 {@code defineClass} 基线录口（不变量 C2）。
 *
 * <p><b>为什么需要它</b>：合成内部类（Mixin 的 {@code Target$Anonymous$<hash>}）磁盘上没有
 * {@code .class}，也不属于工程自身位置，因此 {@link RemoldAgent#bytecodeCache} 里没有它们的字节。
 * 合成类重定义闸只能区分"类在 agent 注册前就已加载"与"注册后才加载但没缓存"这两件事——
 * 没有本录口时它只能一律拒绝（真实 Knot 日志里表现为合成类永远不重定义），或者退化成用
 * 点分名缓存放行（同名不同加载器互相冒充，等于静默降级）。</p>
 *
 * <p><b>只记录，不改字节</b>：{@link Recorder#transform} 恒返回 {@code null}，永不参与改写链；
 * 注册点由 {@link RemoldAgent#addTrailingLoadObserver} 保证在所有 rewriter 之后
 * （含 Knot/Mixin 变换），因此录到的就是 JVM 最终定义的那份字节。</p>
 *
 * <p><b>键 = (加载器标识, 类名)</b>：加载器按<b>实例身份</b>做键（弱引用，不延长加载器寿命），
 * 同一个类名被两个加载器各定义一份时不会互相冒充。</p>
 *
 * <p><b>只留候选</b>：只抄名字里带 {@code '$'} 的类（合成/匿名/内部类——唯一会被
 * {@code redefineSyntheticSafely} 问到的形态）。全量抄写会把整个客户端依赖树的字节都留在内存里，
 * 而本录口的用途只需要覆盖候选。</p>
 *
 * <p><b>两个分母必须可点名</b>（不变量 C2）：{@code missed} 不能只是一个数字——每次未命中都记
 * 类全称 + 加载器标签 + 原因（{@link #missDetailsSummary()}）；{@code seen - recorded} 也不能只当
 * "失败数"——被过滤的提交单独计数并留示例（{@link #filterSummary()}，规则见 {@link #filterRule()}）。
 * 两者都只存字符串，不持 {@code ClassLoader} 强引用。</p>
 */
final class MixinClassBaseline {

	/** 分母计数（战报口径）：看到的 defineClass 提交数 / 实际落账的键数 / 查询未命中数。 */
	private static final AtomicInteger SEEN = new AtomicInteger();
	private static final AtomicInteger MISSED = new AtomicInteger();

	/**
	 * 口径拆解：{@code SEEN} 的每一次提交只走两条路之一——被候选过滤（内部名不含 {@code '$'}）或进入抄字节分支。
	 *
	 * <p>{@code seen - recorded} 因此 <b>不是</b>"录口失败数"：它是被候选过滤掉的提交数，加上
	 * 同名重复定义带来的重复提交数（{@code ATTEMPTS - recorded}）。两个分量各自计数，避免战报把
	 * 口径读串。</p>
	 */
	private static final AtomicInteger FILTERED = new AtomicInteger();
	private static final AtomicInteger ATTEMPTS = new AtomicInteger();

	/** 被过滤提交的示例类名（内部名原文，上限 {@value #FILTERED_EXAMPLE_CAP} 个）：只用于让人核对规则。 */
	private static final int FILTERED_EXAMPLE_CAP = 3;
	private static final List<String> FILTERED_EXAMPLES = new ArrayList<>();

	/**
	 * 未命中查找的点名清单（类全称 + 加载器标签 + 原因），上限 {@value #MISS_DETAIL_CAP} 条。
	 *
	 * <p>只存字符串，不持 {@code ClassLoader}：点名是给日志/战报读的，不能反过来延长加载器寿命。</p>
	 */
	private static final int MISS_DETAIL_CAP = 8;
	private static final ArrayDeque<String> MISS_DETAILS = new ArrayDeque<>();

	/**
	 * (加载器 → (点分类名 → 定义字节))。
	 *
	 * <p>弱键：以类加载器为键的内部表不得延长加载器生命周期（与项目对 Class 键表的纪律一致）；
	 * 加载器被回收后它名下的基线也一起消失——那正是"这份基线不再有意义"的时刻。</p>
	 */
	private static final Map<ClassLoader, Map<String, byte[]>> BASELINES =
		Collections.synchronizedMap(new WeakHashMap<ClassLoader, Map<String, byte[]>>());

	/**
	 * 已生效字节（基线链 R1 及以后）：(加载器身份 → (点分类名 → 字节))。
	 *
	 * <p>与 {@link #BASELINES} 共用同一套加载器身份语义：<b>键是加载器实例（弱引用）</b>，
	 * 同名合成类被两个加载器各定义一份时不会互相冒充。值里不出现 {@code Class}，
	 * 与项目"以 Class 为键的表用弱引用、值不反向持 Class"的纪律一致。</p>
	 */
	private static final Map<ClassLoader, Map<String, byte[]>> APPLIED =
		Collections.synchronizedMap(new WeakHashMap<ClassLoader, Map<String, byte[]>>());

	/** 安装幂等（premain 与 agentmain 两条注册路径都会调用）。 */
	private static final AtomicBoolean INSTALLED = new AtomicBoolean();

	private static final ClassFileTransformer RECORDER = new Recorder();

	private MixinClassBaseline() {
	}

	/** 录口本体（供 {@link RemoldAgent#addTrailingLoadObserver} 与单测使用）。 */
	static ClassFileTransformer recorder() {
		return RECORDER;
	}

	/**
	 * 把录口交给 Remold：真正 {@code addTransformer} 发生在 {@link RemoldAgent#initTransformer()}
	 * 末尾，即所有会改字节的 transformer 之后。幂等；失败只 warn（闸会 fail-closed 要求重启）。
	 */
	static void install() {
		if (!INSTALLED.compareAndSet(false, true)) {
			return;
		}
		try {
			RemoldAgent.addTrailingLoadObserver(RECORDER);
		} catch (Throwable t) {
			INSTALLED.set(false);
			RemoldAgent.warn("[Mixin] could not install the premain baseline recorder; every synthetic"
				+ " redefine will be refused with a restart hint instead of degrading silently: " + t);
		}
	}

	/**
	 * 取 (加载器, 类名) 对应的定义字节。
	 *
	 * <p>这是<b>唯一</b>能证明"基线属于这个加载器"的来源：闸 4 只认它，不再回退到点分名的
	 * {@link RemoldAgent#bytecodeCache}（那条索引没有加载器维度，同名不同加载器会互相冒充）。</p>
	 *
	 * <p>未命中时计入 {@code missed}：<b>一次查找计一次</b>，与"拒绝了一个类"一一对应
	 * （闸 4 每次拒绝只查一次）。</p>
	 *
	 * @param loader     定义该类的加载器；{@code null}（bootstrap）永远视为未命中
	 * @param className  点分名或内部名皆可
	 * @return 基线字节，或 {@code null} = 没抓到
	 */
	static byte[] baselineOf(ClassLoader loader, String className) {
		String dot = className == null ? null : className.replace('/', '.');
		if (dot != null) {
			synchronized (BASELINES) {
				Map<String, byte[]> byName = BASELINES.get(loader);
				if (byName != null) {
					byte[] bytes = byName.get(dot);
					if (bytes != null) {
						return bytes;
					}
				}
			}
		}
		recordMiss(loader, dot);
		MISSED.incrementAndGet();
		return null;
	}

	/**
	 * 点名一条未命中：<b>类全称 + 加载器标签 + 原因</b>。
	 *
	 * <p>原因只有两种，都是可核对的机械判据：这个名字在<b>别的加载器</b>名下录到过（说明 R0 属于
	 * 另一个加载器，本加载器从未 defineClass 过它），还是<b>任何加载器</b>都没录到过（类在 agent
	 * 注册前就已定义，或录口未安装）。只存字符串，不持加载器引用。</p>
	 */
	private static void recordMiss(ClassLoader loader, String dot) {
		String name = dot == null ? "<null>" : dot;
		int otherLoaders = 0;
		synchronized (BASELINES) {
			for (Map.Entry<ClassLoader, Map<String, byte[]>> entry : BASELINES.entrySet()) {
				if (entry.getKey() != loader && entry.getValue().containsKey(name)) {
					otherLoaders++;
				}
			}
		}
		String reason = otherLoaders == 0
			? "no defineClass submission for this name on any loader"
			: "recorded under " + otherLoaders + " other loader(s); this loader never defined it";
		String detail = name + " loader=" + loaderLabel(loader) + " reason=" + reason;
		synchronized (MISS_DETAILS) {
			MISS_DETAILS.addLast(detail);
			while (MISS_DETAILS.size() > MISS_DETAIL_CAP) {
				MISS_DETAILS.removeFirst();
			}
		}
	}

	/**
	 * 加载器标签：类名 + 实例身份哈希。
	 *
	 * <p>身份哈希是<b>弱身份</b>：不与 {@code equals} 混用，也不延长加载器寿命，只用来让人/日志
	 * 区分两个同类名加载器。与 {@link #BASELINES}/{@link #APPLIED} 的弱键身份语义同源。</p>
	 */
	static String loaderLabel(ClassLoader loader) {
		return loader == null
			? "bootstrap"
			: loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
	}

	/**
	 * 登记"已在 JVM 里生效"的合成类字节（基线链 R1 及以后）。
	 *
	 * <p>只在 {@code redefineClasses} <b>成功</b>之后调用；失败的重定义不落账。{@code loader == null}
	 * （bootstrap）不登记：闸 4 对 bootstrap 永远视为无基线，这里替它存一份只会制造假基线。</p>
	 */
	static void recordApplied(ClassLoader loader, String dottedName, byte[] bytes) {
		if (loader == null || dottedName == null || bytes == null) {
			return;
		}
		synchronized (APPLIED) {
			Map<String, byte[]> byName = APPLIED.get(loader);
			if (byName == null) {
				byName = new HashMap<>();
				APPLIED.put(loader, byName);
			}
			byName.put(dottedName, bytes.clone());
		}
	}

	/** 取该 (加载器, 点分名) 名下已生效的字节；{@code null} = 本加载器还没成功 redefine 过它。 */
	static byte[] appliedOf(ClassLoader loader, String dottedName) {
		if (loader == null || dottedName == null) {
			return null;
		}
		synchronized (APPLIED) {
			Map<String, byte[]> byName = APPLIED.get(loader);
			return byName == null ? null : byName.get(dottedName);
		}
	}

	/** 当前三个分母的快照。 */
	static Metrics metrics() {
		int recorded = 0;
		synchronized (BASELINES) {
			for (Map<String, byte[]> byName : BASELINES.values()) {
				recorded += byName.size();
			}
		}
		return new Metrics(SEEN.get(), recorded, MISSED.get());
	}

	/** 三个分母的单行摘要（进日志与战报）。 */
	static String metricsSummary() {
		Metrics m = metrics();
		return "seen=" + m.seen + " recorded=" + m.recorded + " missed=" + m.missed;
	}

	/** 候选过滤规则原文：只有内部名带 {@code '$'} 的提交会落账。 */
	static String filterRule() {
		return "defineClass submission whose internal name has no '$' is not a synthetic/inner candidate";
	}

	/** 被候选过滤的提交数（{@code seen - recorded} 的分量之一）。 */
	static int filteredCount() {
		return FILTERED.get();
	}

	/** 被过滤提交的示例内部名（上限 {@value #FILTERED_EXAMPLE_CAP} 个）：规则自证用。 */
	static List<String> filteredExamples() {
		synchronized (FILTERED_EXAMPLES) {
			return new ArrayList<>(FILTERED_EXAMPLES);
		}
	}

	/** 进入抄字节分支的提交数（{@code attempts - recorded} = 同名重复提交数）。 */
	static int recordAttempts() {
		return ATTEMPTS.get();
	}

	/** 未命中查找数（与 {@link #missDetailsSummary()} 的点名条数同口径）。 */
	static int missedCount() {
		return MISSED.get();
	}

	/** 最近未命中查找的点名清单：类全称 + 加载器标签 + 原因。 */
	static String missDetailsSummary() {
		synchronized (MISS_DETAILS) {
			return MISS_DETAILS.isEmpty() ? "<none>" : String.join(" | ", MISS_DETAILS);
		}
	}

	/**
	 * 最近一次未命中的点名（类全称 + 加载器标签 + 原因）：闸 4 把它带进拒绝原因，
	 * 这样真机日志不必等探针就能点名"是哪个类的哪份基线没抓到"。
	 */
	static String lastMissSummary() {
		synchronized (MISS_DETAILS) {
			return MISS_DETAILS.isEmpty() ? "<none>" : MISS_DETAILS.peekLast();
		}
	}

	/** 过滤口径摘要：规则 + 计数 + 示例（示例是内部名原文，便于对照规则自证）。 */
	static String filterSummary() {
		List<String> examples;
		synchronized (FILTERED_EXAMPLES) {
			examples = new ArrayList<>(FILTERED_EXAMPLES);
		}
		return "filtered=" + FILTERED.get() + " rule=\"" + filterRule() + "\" examples=" + examples
			+ " attempts=" + ATTEMPTS.get() + " recorded=" + metrics().recorded;
	}

	/** 单测隔离：清空台账与计数器（不动安装状态：安装是进程级一次性动作）。 */
	static void clearForTest() {
		synchronized (BASELINES) {
			BASELINES.clear();
		}
		synchronized (APPLIED) {
			APPLIED.clear();
		}
		synchronized (FILTERED_EXAMPLES) {
			FILTERED_EXAMPLES.clear();
		}
		synchronized (MISS_DETAILS) {
			MISS_DETAILS.clear();
		}
		SEEN.set(0);
		MISSED.set(0);
		FILTERED.set(0);
		ATTEMPTS.set(0);
	}

	/**
	 * 单测隔离：只摘掉一个 (加载器, 类名) 键（计数器不动）。
	 *
	 * <p>用例各自用唯一类名播种，收尾只清理自己那一份，避免 {@link #clearForTest()} 把同类里
	 * 其他用例的播种一起抹掉。</p>
	 */
	static void forgetForTest(ClassLoader loader, String className) {
		String dot = className == null ? null : className.replace('/', '.');
		if (dot == null) {
			return;
		}
		synchronized (BASELINES) {
			Map<String, byte[]> byName = BASELINES.get(loader);
			if (byName != null) {
				byName.remove(dot);
				if (byName.isEmpty()) {
					BASELINES.remove(loader);
				}
			}
		}
	}

	/**
	 * 三个分母的不可变快照。
	 *
	 * <p><b>三个数各算一套口径，互不可由彼此推出</b>（战报只看这三个数，容易读串）：</p>
	 * <ul>
	 *   <li>{@code seen}：录口<b>看到</b>的 defineClass 提交数——只排除"根本不可能落账"的三类
	 *       （bootstrap {@code loader == null}、hidden class、agent 自身 {@code remold/}）。
	 *       被候选过滤拒掉的名字（不含 {@code '$'}）<b>仍然计入</b>；</li>
	 *   <li>{@code recorded}：真正落账的 (加载器, 类名) 键数——只留候选，所以
	 *       {@code seen - recorded} = 被候选过滤的提交数（{@link #filteredCount()}）+ 同名重复提交数
	 *       （{@link #recordAttempts()} - {@code recorded}），不是"录口失败数"；</li>
	 *   <li>{@code missed}：基线<b>查找</b>未命中数——闸 4 每拒绝一次计一次，与加载器数、
	 *       类数、{@code recorded} 都无关。</li>
	 * </ul>
	 */
	static final class Metrics {
		/** 录口看到的 defineClass 提交数（含被候选过滤的名字）。 */
		final int seen;
		/** 实际落账的 (加载器, 类名) 键数（只含候选名）。 */
		final int recorded;
		/** 基线查找未命中数 = 因"没有基线"被闸拒绝的次数。 */
		final int missed;

		Metrics(int seen, int recorded, int missed) {
			this.seen = seen;
			this.recorded = recorded;
			this.missed = missed;
		}

		@Override
		public String toString() {
			return "seen=" + this.seen + " recorded=" + this.recorded + " missed=" + this.missed;
		}
	}

	/** 录口：只抄字节，恒返回 {@code null}。 */
	private static final class Recorder implements ClassFileTransformer {
		@Override
		public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
				ProtectionDomain protectionDomain, byte[] classfileBuffer) {
			// redefine/retransform 不是 defineClass：它送来的是"当前生效字节"，覆盖基线会让闸拿到错误身份。
			if (classBeingRedefined != null) {
				return null;
			}
			// loader == null = bootstrap（JDK 自身），永远不会是合成类目标。
			if (loader == null || className == null || classfileBuffer == null) {
				return null;
			}
			// agent 自身与 hidden class 不进表（hidden 没有名字可查，agent 自身只会制造循环）。
			if (className.startsWith("remold/") || className.indexOf("/0x") >= 0) {
				return null;
			}
			SEEN.incrementAndGet();
			// 只留候选：合成/匿名/内部类的名字一定带 '$'（见类注释的"只留候选"）。
			if (className.indexOf('$') < 0) {
				FILTERED.incrementAndGet();
				noteFiltered(className);
				return null;
			}
			ATTEMPTS.incrementAndGet();
			record(loader, className.replace('/', '.'), classfileBuffer);
			return null;   // 只记录，绝不改字节
		}
	}

	/** 记下前若干个被过滤提交的内部名（规则自证用；只存名字，不存字节）。 */
	private static void noteFiltered(String internalName) {
		synchronized (FILTERED_EXAMPLES) {
			if (FILTERED_EXAMPLES.size() < FILTERED_EXAMPLE_CAP && !FILTERED_EXAMPLES.contains(internalName)) {
				FILTERED_EXAMPLES.add(internalName);
			}
		}
	}

	private static void record(ClassLoader loader, String dotName, byte[] bytes) {
		synchronized (BASELINES) {
			Map<String, byte[]> byName = BASELINES.get(loader);
			if (byName == null) {
				byName = new HashMap<>();
				BASELINES.put(loader, byName);
			}
			// 存副本：JVM 交给 transformer 的数组在返回 null 后仍可能被后续环节复用/改写。
			byName.put(dotName, bytes.clone());
		}
	}
}
