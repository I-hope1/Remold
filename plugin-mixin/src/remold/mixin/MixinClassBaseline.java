package remold.mixin;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.HashMap;
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
 */
final class MixinClassBaseline {

	/** 分母计数（战报口径）：看到的 defineClass 提交数 / 实际落账的键数 / 查询未命中数。 */
	private static final AtomicInteger SEEN = new AtomicInteger();
	private static final AtomicInteger MISSED = new AtomicInteger();

	/**
	 * (加载器 → (点分类名 → 定义字节))。
	 *
	 * <p>弱键：以类加载器为键的内部表不得延长加载器生命周期（与项目对 Class 键表的纪律一致）；
	 * 加载器被回收后它名下的基线也一起消失——那正是"这份基线不再有意义"的时刻。</p>
	 */
	private static final Map<ClassLoader, Map<String, byte[]>> BASELINES =
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
	 * <p>未命中时计入 {@code missed}（这是闸 4 的分母）。返回的数组是录口内部副本，
	 * <b>调用方不得修改</b>。</p>
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
		MISSED.incrementAndGet();
		return null;
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

	/** 单测隔离：清空台账与计数器（不动安装状态：安装是进程级一次性动作）。 */
	static void clearForTest() {
		synchronized (BASELINES) {
			BASELINES.clear();
		}
		SEEN.set(0);
		MISSED.set(0);
	}

	/** 三个分母的不可变快照。 */
	static final class Metrics {
		/** 录口看到的 defineClass 提交数。 */
		final int seen;
		/** 实际落账的 (加载器, 类名) 键数。 */
		final int recorded;
		/** 查询未命中数 = 因"没有基线"被闸拒绝的次数。 */
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
				return null;
			}
			record(loader, className.replace('/', '.'), classfileBuffer);
			return null;   // 只记录，绝不改字节
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
