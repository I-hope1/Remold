package remold.forge;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import remold.RemoldAgent;

/**
 * Mixin 模板类自身被热更时的 Forge / NeoForge 支持。
 *
 * <p><b>为什么需要</b>：Mixin 类只是被 ModLauncher 的 Mixin 织入器读成字节码模板，不会被 JVM
 * 当作普通类加载。若热更时改了 Mixin 类，Forge 侧既没有可 redefine 的类，也没有任何东西去刷新
 * Mixin 运行期状态——目标类里的旧 {@code @Inject} 方法体因此永远不会更新。更糟的是，Remold 的
 * 常规批次会把「未加载」的 Mixin 类当成新类 {@code defineClass} 进宿主 ClassLoader，污染类身份。</p>
 *
 * <p><b>做法</b>（全部通过反射，插件不直接依赖 {@code org.spongepowered.asm.*}——它们由
 * ModLauncher 的独立 ClassLoader 加载，与 Agent 的系统 ClassLoader 隔离）：</p>
 * <ol>
 *   <li>把 Mixin 类登记为不可加载模板（{@link RemoldAgent#markNonLoadableClass}），核心随后跳过
 *       {@code defineClass} / {@code redefine} 与读者扫描；</li>
 *   <li>用 Mixin 织入器所在 ClassLoader 里的 ASM 构造 {@code ClassNode}，调用
 *       {@code IMixinTransformer.reload(name, classNode)} 刷新 Mixin 运行期状态；</li>
 *   <li>在独立单线程任务里，对 {@code reload} 返回的、已被 JVM 加载的目标类重新织入并重定义
 *       （{@link RemoldAgent#redefineDynamic} 会再走一遍桥链，因此目标类上叠加的其它 Mixin
 *       也会一并织入）。任务在 Remold 批次锁之外执行，不会插进批次中间改缓存。</li>
 * </ol>
 *
 * <p><b>边界</b>：Mixin 的结构契约（目标类 / 接口 / 内部类 / 优先级）改动会被 Mixin 以
 * {@code MixinReloadException} 拒绝，此时明确提示需要重启。Mixin 新增字段/方法时，Mixin 内部
 * {@code ClassInfo} 的成员同步不在本类的职责内（Fabric 侧由 {@code MixinClassInfoSync} 处理），
 * 因此这类改动不保证热更生效。</p>
 */
final class ForgeMixinHotSwap {

	/** 等待重织入的目标类（点分隔类名），按发现顺序去重。 */
	private static final Set<String> PENDING = new LinkedHashSet<>();

	private static final Object PENDING_LOCK = new Object();

	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "remold-forge-mixin-hotswap");
		thread.setDaemon(true);
		return thread;
	});

	private ForgeMixinHotSwap() {
	}

	/**
	 * 处理一个被修改的 Mixin 模板类。
	 *
	 * @param inst          Instrumentation，用于定位已加载的目标类
	 * @param transformer   已解析出的 Mixin 织入器（可为 {@code null}）
	 * @param dotClassName  Mixin 类的点分隔类名
	 * @param bytes         最新的源码级字节码
	 */
	static void handleIfMixin(InstrumentationHolder inst, Object transformer, String dotClassName, byte[] bytes) {
		RemoldAgent.markNonLoadableClass(dotClassName);
		if (transformer == null) {
			warnOnce(dotClassName, "[Forge/NeoForge] " + dotClassName
				+ " is a Mixin template whose class changed, but no active Mixin transformer could be"
				+ " resolved; its target classes were NOT re-woven (restart to apply this change)");
			return;
		}
		List<String> targets;
		try {
			targets = reload(transformer, dotClassName, bytes);
		} catch (Throwable t) {
			warnOnce(dotClassName, "[Forge/NeoForge] Failed to reload Mixin " + dotClassName
				+ "; restart the game to apply this change: " + describe(t));
			return;
		}
		if (targets.isEmpty()) {
			RemoldAgent.info("[Forge/NeoForge] Mixin " + dotClassName
				+ " reloaded (no active targets to re-weave)");
			return;
		}
		RemoldAgent.info("[Forge/NeoForge] Mixin " + dotClassName + " reloaded; affected target(s): " + targets);
		synchronized (PENDING_LOCK) {
			PENDING.addAll(targets);
		}
		WORKER.execute(() -> flushPendingTargets(inst));
	}

	/**
	 * 通过反射刷新 Mixin 运行期状态，返回受影响的目标类（点分隔）。
	 *
	 * <p>必须用 Mixin 织入器所在 ClassLoader 的 ASM 类型构造 {@code ClassNode}：Mixin 的
	 * {@code reload} 方法签名引用的是那个 ClassLoader 里的 {@code ClassNode}，用 Agent 侧的
	 * ASM 类型会在反射调用时直接抛 {@code IllegalArgumentException}。</p>
	 */
	private static List<String> reload(Object transformer, String dotClassName, byte[] bytes) throws Exception {
		ClassLoader mixinLoader = transformer.getClass().getClassLoader();
		Class<?> classReaderType = Class.forName("org.objectweb.asm.ClassReader", true, mixinLoader);
		Class<?> classVisitorType = Class.forName("org.objectweb.asm.ClassVisitor", true, mixinLoader);
		Class<?> classNodeType = Class.forName("org.objectweb.asm.tree.ClassNode", true, mixinLoader);

		Object node = classNodeType.getConstructor().newInstance();
		Object reader = classReaderType.getConstructor(byte[].class).newInstance((Object) bytes);
		classReaderType.getMethod("accept", classVisitorType, int.class).invoke(reader, node, 0);

		Method reload = findReload(transformer.getClass(), classNodeType);
		if (reload == null) {
			throw new NoSuchMethodException("reload(String, ClassNode) on " + transformer.getClass().getName());
		}
		reload.setAccessible(true);
		Object raw = reload.invoke(transformer, dotClassName, node);
		List<?> list = raw instanceof List ? (List<?>) raw : Collections.emptyList();
		if (list.isEmpty()) {
			// 部分 Mixin 版本的 MixinInfo 以内部名登记，点分类名匹配不到时再试一次。
			raw = reload.invoke(transformer, dotClassName.replace('.', '/'), node);
			list = raw instanceof List ? (List<?>) raw : Collections.emptyList();
		}
		return normalize(list);
	}

	private static Method findReload(Class<?> transformerType, Class<?> classNodeType) {
		for (Class<?> c = transformerType; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Method m : c.getMethods()) {
				if (!"reload".equals(m.getName()) || m.getParameterCount() != 2) continue;
				Class<?>[] types = m.getParameterTypes();
				if (types[0] == String.class && types[1].getName().equals(classNodeType.getName())) {
					return m;
				}
			}
		}
		return null;
	}

	/** 把 Mixin 返回的目标类名统一成点分形式（{@code getTargetClasses()} 返回内部名）。 */
	private static List<String> normalize(List<?> rawTargets) {
		if (rawTargets == null || rawTargets.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> normalized = new ArrayList<>(rawTargets.size());
		for (Object raw : rawTargets) {
			if (!(raw instanceof String) || ((String) raw).isEmpty()) continue;
			String dotted = ((String) raw).replace('/', '.');
			if (!normalized.contains(dotted)) {
				normalized.add(dotted);
			}
		}
		return normalized;
	}

	/** 逐个把受影响的目标类重织入并重定义。串行执行，天然与 Remold 的批次锁互斥。 */
	private static void flushPendingTargets(InstrumentationHolder inst) {
		List<String> batch;
		synchronized (PENDING_LOCK) {
			if (PENDING.isEmpty()) {
				return;
			}
			batch = new ArrayList<>(PENDING);
			PENDING.clear();
		}

		Map<String, Class<?>> loaded = new HashMap<>();
		Set<String> wanted = new HashSet<>(batch);
		for (Class<?> candidate : inst.allLoadedClasses()) {
			if (wanted.contains(candidate.getName())) {
				loaded.put(candidate.getName(), candidate);
			}
		}

		for (String name : batch) {
			Class<?> targetClass = loaded.get(name);
			if (targetClass == null) {
				// 未加载 ⇒ 下次加载时会直接用新的 Mixin 定义织入，无需热更。
				RemoldAgent.info("[Forge/NeoForge] Target class " + name
					+ " is not yet loaded; the updated Mixin will apply upon initial load");
				continue;
			}
			byte[] originalBytes = readOriginalBytes(targetClass);
			if (originalBytes == null) {
				RemoldAgent.warn("[Forge/NeoForge] Unable to read original bytecode for " + name
					+ "; skipping re-weaving (runtime-generated classes are not supported)");
				continue;
			}
			if (RemoldAgent.redefineDynamic(targetClass, originalBytes)) {
				RemoldAgent.info("[Forge/NeoForge] Target class " + name
					+ " re-woven with the updated Mixin and redefined");
			} else {
				RemoldAgent.warn("[Forge/NeoForge] Target class " + name
					+ " was re-woven but the redefine was refused; see the [LAYOUT-REJECT] /"
					+ " [DYNAMIC-REDEFINE] lines above");
			}
		}
	}

	/**
	 * 读取类的原始（未织入）字节码。必须用未织入的原始字节：JVM 当前持有的字节已经织入了旧
	 * Mixin，再织一遍会重复注入。
	 */
	private static byte[] readOriginalBytes(Class<?> type) {
		if (type == null) return null;
		byte[] disk = RemoldAgent.findClassBytesOnDisk(type);
		if (disk != null) return disk;
		String resource = "/" + type.getName().replace('.', '/') + ".class";
		try (InputStream in = type.getResourceAsStream(resource)) {
			if (in != null) return in.readAllBytes();
		} catch (Throwable ignored) {
			// 落到 source cache 兜底
		}
		return RemoldAgent.getSourceBytecode(type.getName());
	}

	private static void warnOnce(String key, String message) {
		if (REPORTED.add(key)) {
			RemoldAgent.warn(message);
		}
	}

	/** 把 Throwable 的完整 cause 链展开成一行一条，Mixin 的真因常在 {@code getCause()} 里。 */
	private static String describe(Throwable t) {
		StringBuilder sb = new StringBuilder();
		Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		Throwable cur = t;
		int depth = 0;
		while (cur != null && depth < 8 && seen.add(cur)) {
			if (depth > 0) sb.append("\n  Caused by: ");
			sb.append(cur.getClass().getName());
			if (cur.getMessage() != null) sb.append(": ").append(cur.getMessage());
			cur = cur.getCause();
			depth++;
		}
		return sb.toString();
	}

	/**
	 * 只暴露本类真正需要的 Instrumentation 能力。
	 *
	 * <p>用最小的函数式接口而不是直接持有 {@link java.lang.instrument.Instrumentation}，是为了让
	 * 「定位已加载类」这一步在测试里可替换，而不必真的挂一个 Java Agent。</p>
	 */
	interface InstrumentationHolder {
		Class<?>[] allLoadedClasses();
	}
}
