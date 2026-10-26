package remold.mixin;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import remold.RemoldAgent;

/**
 * Mixin 类自身被修改时的热更支持：让目标类用新的注入逻辑重新织一遍，再重定义目标类。
 *
 * <p>Mixin 类不会被 JVM 当成普通类加载，它只是被读成字节码模板。所以「改了 Mixin 类」这件事，
 * 在 JVM 里既没有可 redefine 的类，Remold 的常规批次也不会去重织入它的目标类。本类补的就是这段：</p>
 *
 * <ol>
 *   <li><b>识别</b>：常量池里出现 {@code @Mixin} 描述符的类，按 Mixin 类处理。</li>
 *   <li><b>刷新 Mixin 内部状态</b>：调用 {@code IMixinTransformer.reload(name, 新 ClassNode)}。
 *       Mixin 会用它重建 {@code MixinInfo} 状态，并校验结构契约（目标类、接口、内部类、优先级）。
 *       校验不过抛 {@code MixinReloadException}，此时明确提示需要重启。</li>
 *   <li><b>找目标</b>：{@code reload} 返回受影响的目标类名（内部名形式），归一化后筛出已被 JVM 加载的。</li>
 *   <li><b>重织入 + 重定义</b>：从类路径读目标类的<b>原始</b>字节码（不是 JVM 里那份），
 *       交给 {@link RemoldAgent#redefineDynamic} —— 它会再走一遍重织入桥、布局安全门与
 *       {@code redefineClasses}，因此目标类上叠加的其它 Mixin 也会一并织入。</li>
 * </ol>
 *
 * <p><b>执行时机</b>：重定义目标类放在独立的单线程任务里执行。{@code redefineDynamic} 内部会获取
 * Remold 的 {@code HOTSWAP_LOCK}，所以即使当前批次还在跑，该任务也会阻塞到批次结束再动手，
 * 不会插进批次中间改缓存。</p>
 */
public final class MixinHotSwap {

	/** {@code @Mixin} 注解描述符。它出现在常量池里就说明这个类可能是 Mixin。 */
	private static final byte[] MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;".getBytes(StandardCharsets.UTF_8);

	private static final Object PENDING_LOCK = new Object();

	/** 等待重织入的目标类（点分隔类名），按发现顺序去重。 */
	private static final Set<String> PENDING = new LinkedHashSet<>();

	private static final Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "remold-mixin-hotswap");
		thread.setDaemon(true);
		return thread;
	});

	private MixinHotSwap() {
	}

	/**
	 * 若改动的是 Mixin 类，则刷新 Mixin 内部状态并调度目标类的重织入。
	 *
	 * @param dotClassName  点分隔类名
	 * @param newMixinBytes 该类最新的源码级字节码
	 * @return {@code true} 表示已按 Mixin 类处理（调用方不要再把它当普通类重织入）
	 */
	static boolean handleIfMixin(String dotClassName, byte[] newMixinBytes) {
		try {
			return handleIfMixin0(dotClassName, newMixinBytes);
		} catch (Throwable t) {
			// 连 Mixin 相关类都解析不了（例如宿主把 Mixin 放在另一个 ClassLoader）时，
			// 交回普通重织入路径，避免整条桥因单个类失败而中断。
			if (REPORTED.add("<handler>")) {
				RemoldAgent.warn("Mixin 热更处理不可用: " + t);
			}
			return false;
		}
	}

	private static boolean handleIfMixin0(String dotClassName, byte[] newMixinBytes) {
		if (dotClassName == null || newMixinBytes == null || !looksLikeMixin(newMixinBytes)) {
			return false;
		}
		IMixinTransformer transformer = MixinReWeaver.activeTransformer();
		if (transformer == null) {
			return false;
		}

		List<String> targets;
		try {
			// 必须完整解析：@Inject 的 handler 方法体要从这里搬进目标类，跳过 code 会织出空方法。
			ClassNode node = new ClassNode();
			new ClassReader(newMixinBytes).accept(node, 0);
			targets = normalize(transformer.reload(dotClassName, node));
		} catch (Throwable t) {
			reportReloadFailure(dotClassName, t);
			return true;
		}

		if (targets.isEmpty()) {
			// 不是 Mixin（或该 Mixin 没有目标）：交回普通重织入路径，避免误判导致类被跳过重织入。
			return false;
		}
		RemoldAgent.info("Mixin " + dotClassName + " 已重新解析，受影响目标: " + targets);
		synchronized (PENDING_LOCK) {
			PENDING.addAll(targets);
		}
		WORKER.execute(MixinHotSwap::flushPendingTargets);
		return true;
	}

	/**
	 * 把 Mixin 返回的目标类名统一成点分形式。
	 *
	 * <p>{@code MixinInfo.getTargetClasses()} 返回的是内部名（{@code com/example/Foo}），
	 * 而 {@link Class#getName()} 是点分形式，不归一化会让「已加载」判断永远落空。</p>
	 */
	private static List<String> normalize(List<String> rawTargets) {
		if (rawTargets == null || rawTargets.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> normalized = new ArrayList<>(rawTargets.size());
		for (String raw : rawTargets) {
			if (raw == null || raw.isEmpty()) {
				continue;
			}
			String dotted = raw.replace('/', '.');
			if (!normalized.contains(dotted)) {
				normalized.add(dotted);
			}
		}
		return normalized;
	}

	/** 逐个把受影响的目标类重织入并重定义。串行执行，天然与 Remold 的批次锁互斥。 */
	private static void flushPendingTargets() {
		List<String> batch;
		synchronized (PENDING_LOCK) {
			if (PENDING.isEmpty()) {
				return;
			}
			batch = new ArrayList<>(PENDING);
			PENDING.clear();
		}

		Instrumentation inst = RemoldAgent.getInstrumentation();
		if (inst == null) {
			RemoldAgent.warn("无法获取 Instrumentation，跳过 Mixin 目标类重织入");
			return;
		}

		Map<String, Class<?>> loaded = new HashMap<>();
		Set<String> wanted = new LinkedHashSet<>(batch);
		for (Class<?> candidate : inst.getAllLoadedClasses()) {
			if (wanted.contains(candidate.getName())) {
				loaded.put(candidate.getName(), candidate);
			}
		}

		for (String name : batch) {
			Class<?> targetClass = loaded.get(name);
			if (targetClass == null) {
				// 未加载 ⇒ 下次加载时会直接用新的 Mixin 定义织入，无需热更。
				RemoldAgent.info("目标类 " + name + " 尚未加载，下次加载自动应用新 Mixin");
				continue;
			}
			byte[] originalBytes = readOriginalBytes(targetClass);
			if (originalBytes == null) {
				RemoldAgent.warn("读不到 " + name + " 的原始字节码，跳过重织入（运行时生成的类不支持）");
				continue;
			}
			if (RemoldAgent.redefineDynamic(targetClass, originalBytes)) {
				RemoldAgent.info("目标类 " + name + " 已用新 Mixin 重新织入并重定义");
			} else {
				RemoldAgent.warn("目标类 " + name + " 重织入后未能重定义：结构变化需带 DCEVM 的 JBR，或该改动需重启客户端");
			}
		}
	}

	/**
	 * 读取类的原始（未织入）字节码。
	 *
	 * <p>必须用类路径里的那份：JVM 当前持有的字节码已经织入了旧 Mixin，再织一遍会重复注入。</p>
	 */
	private static byte[] readOriginalBytes(Class<?> type) {
		String resource = "/" + type.getName().replace('.', '/') + ".class";
		try (InputStream in = type.getResourceAsStream(resource)) {
			return in == null ? null : in.readAllBytes();
		} catch (Throwable t) {
			return null;
		}
	}

	/** 常量池里是否含 {@code @Mixin} 描述符。便宜的前置过滤，避免给每个类都做一次 ASM 解析。 */
	private static boolean looksLikeMixin(byte[] bytes) {
		int last = bytes.length - MIXIN_DESC.length;
		outer:
		for (int i = 0; i <= last; i++) {
			for (int j = 0; j < MIXIN_DESC.length; j++) {
				if (bytes[i + j] != MIXIN_DESC[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	/** Mixin 拒绝重载时给出「需重启」的明确提示，而不是一句泛化的异常。 */
	private static void reportReloadFailure(String dotClassName, Throwable t) {
		String reason = t.getMessage() != null ? t.getMessage() : t.toString();
		if (isStructuralRejection(t)) {
			RemoldAgent.warn("Mixin " + dotClassName
				+ " 属于结构性改动（目标类/接口/内部类/优先级变化），无法热更，请重启客户端：" + reason);
		} else {
			RemoldAgent.warn("Mixin " + dotClassName + " 重新解析失败，请重启客户端：" + reason);
		}
	}

	/**
	 * 判定是否为「结构契约被打破」。
	 *
	 * <p>用类名而非 {@code instanceof}：Fabric 的 Mixin 分支可能挪动/改名这个异常类，
	 * 直接引用会让 catch 块本身在运行时抛 {@link NoClassDefFoundError}。</p>
	 */
	private static boolean isStructuralRejection(Throwable t) {
		if ("MixinReloadException".equals(t.getClass().getSimpleName())) {
			return true;
		}
		String message = t.getMessage();
		return message != null && message.startsWith("Cannot change");
	}
}
