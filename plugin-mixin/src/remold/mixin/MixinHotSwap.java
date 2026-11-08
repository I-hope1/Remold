package remold.mixin;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import remold.BatchReportScope;
import remold.HotswapBatchReport;
import remold.RemoldAgent;

/**
 * Mixin 类自身被修改时的热更支持：让目标类用新的注入逻辑重新织一遍，再重定义目标类。
 *
 * <p>Mixin 类不会被 JVM 当成普通类加载，它只是被读成字节码模板。所以「改了 Mixin 类」这件事，
 * 在 JVM 里既没有可 redefine 的类，Remold 的常规批次也不会去重织入它的目标类。本类补的就是这段：</p>
 *
 * <ol>
 *   <li><b>识别</b>：常量池里出现 {@code @Mixin} 描述符或包路径匹配已知 Mixin 配置的类，按 Mixin 类处理。</li>
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

	/** {@code @Unique} 字段注解描述符。 */
	private static final String UNIQUE_DESC = "Lorg/spongepowered/asm/mixin/Unique;";

	/** {@code @Inject} handler 注解描述符。 */
	private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";

	/** 上一次成功 reload 时的成员快照。只用于计算“相对上一次”的可证明增量。 */
	private static final Map<String, MixinSnapshot> SNAPSHOTS = new java.util.concurrent.ConcurrentHashMap<>();

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
	 * 判断指定类是否为 Mixin 模板类或位于已知 Mixin 包中。
	 *
	 * @param dotClassName 点分隔类名
	 * @param bytes        类的最新字节码
	 * @return {@code true} 表示属于 Mixin 类或 Mixin 包
	 */
	public static boolean isMixin(String dotClassName, byte[] bytes) {
		if (bytes != null && looksLikeMixin(bytes)) {
			return true;
		}
		if (dotClassName != null && isInMixinPackage(dotClassName)) {
			return true;
		}
		return false;
	}

	/**
	 * 若改动的是 Mixin 类，则刷新 Mixin 内部状态并调度目标类的重织入。
	 *
	 * @param dotClassName  点分隔类名
	 * @param newMixinBytes 该类最新的源码级字节码
	 * @return {@code true} 表示已按 Mixin 类处理（调用方不要再把它当普通类重织入）
	 */
	public static boolean handleIfMixin(String dotClassName, byte[] newMixinBytes) {
		try {
			return handleIfMixin0(dotClassName, newMixinBytes);
		} catch (Throwable t) {
			// 连 Mixin 相关类都解析不了（例如宿主把 Mixin 放在另一个 ClassLoader）时，
			// 交回普通重织入路径，避免整条桥因单个类失败而中断。
			if (REPORTED.add("<handler>")) {
				RemoldAgent.warn("[Mixin] Mixin hot-swap handler unavailable: " + t);
			}
			return false;
		}
	}

	private static boolean handleIfMixin0(String dotClassName, byte[] newMixinBytes) {
		if (dotClassName == null || newMixinBytes == null) {
			return false;
		}
		if (!isMixin(dotClassName, newMixinBytes)) {
			return false;
		}
		// Mixin 只是织入模板，不是可加载类：登记后核心会跳过 defineClass / redefine，
		// 读者扫描也不会把它当成外部读者。
		RemoldAgent.markNonLoadableClass(dotClassName);
		IMixinTransformer transformer = MixinReWeaver.activeTransformer();
		if (transformer == null) {
			return true;
		}

		ensureConfigsSelected(transformer);

		List<String> targets = Collections.emptyList();
		MixinClassInfoSync.SyncToken syncToken = MixinClassInfoSync.SyncToken.EMPTY;
		ClassNode node = new ClassNode();
		try {
			// 必须完整解析：@Inject 的 handler 方法体要从这里搬进目标类，跳过 code 会织出空方法。
			new ClassReader(newMixinBytes).accept(node, 0);
			// reload 的校验阶段（MixinInfo.State.validate → MixinPreProcessorStandard.conform）
			// 会拿新字节码里的 @Inject handler 去查 Mixin 的 ClassInfo；而 Reloaded 状态复用的是
			// reload 之前的旧 ClassInfo。新增/改名的 handler 必须先补进元数据再 reload，
			// 否则 reload 直接抛 "Conform error ... please restart client"，热更被迫降级为重启。
			syncToken = MixinClassInfoSync.sync(transformer, dotClassName, node);
			List<String> raw = transformer.reload(dotClassName, node);
			if (raw == null || raw.isEmpty()) {
				raw = transformer.reload(dotClassName.replace('.', '/'), node);
			}
			targets = normalize(raw);
		} catch (Throwable t) {
			// reload 被拒绝时撤销 reload 前补入的成员，让旧元数据保持可用。
			MixinClassInfoSync.rollback(syncToken);
			reportReloadFailure(dotClassName, t);
			return true;
		}

		// reload 成功后再记录批次报告：失败/被拒绝的模板不能进“变更列表”，
		// 也不能推进快照（否则下一次重试会拿错误基线算增量）。
		recordMixinReport(dotClassName, node);

		if (targets.isEmpty()) {
			// MixinConfig.reloadMixin 只在启动时解析出的 mixin 列表里查找；返回空说明该类
			// 不在任何已注册配置的 mixin 列表中——即运行期新增的 Mixin 类。Mixin 的配置
			// 列表不会随文件改动重读，因此新增 Mixin 无法热加；已有 Mixin 的修改不受影响。
			RemoldAgent.warn("[Mixin] " + dotClassName + " is not registered in the startup Mixin config:"
				+ " a brand-new Mixin class cannot be hot-added. Restart once so the config picks it up"
				+ " (editing an existing Mixin stays hot-swappable).");
			return true;
		}
		RemoldAgent.info("[Mixin] " + dotClassName + " reloaded; affected target(s): " + targets);
		synchronized (PENDING_LOCK) {
			PENDING.addAll(targets);
		}
		WORKER.execute(MixinHotSwap::flushPendingTargets);
		return true;
	}

	/**
	 * 检查类名是否位于已注册的 Mixin 配置所管辖的包下。
	 */
	public static boolean isInMixinPackage(String dotClassName) {
		if (dotClassName == null || dotClassName.isEmpty()) {
			return false;
		}
		if (checkConfigMap(dotClassName)) {
			return true;
		}
		IMixinTransformer transformer = MixinReWeaver.activeTransformer();
		if (transformer != null && checkProcessorConfigs(transformer, dotClassName)) {
			return true;
		}
		return false;
	}

	private static boolean checkConfigMap(String dotClassName) {
		try {
			Class<?> configClass = Class.forName("org.spongepowered.asm.mixin.transformer.Config");
			Field allConfigsField = findField(configClass, "allConfigs");
			if (allConfigsField != null) {
				allConfigsField.setAccessible(true);
				Object map = allConfigsField.get(null);
				if (map instanceof Map) {
					Map<?, ?> copy;
					synchronized (map) {
						copy = new HashMap<>((Map<?, ?>) map);
					}
					for (Object cfgObj : copy.values()) {
						if (cfgObj instanceof Config) {
							IMixinConfig mc = ((Config) cfgObj).getConfig();
							if (mc != null && matchesMixinPackage(mc.getMixinPackage(), dotClassName)) {
								return true;
							}
						}
					}
				}
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	private static boolean checkProcessorConfigs(IMixinTransformer transformer, String dotClassName) {
		try {
			Field procField = findField(transformer.getClass(), "processor");
			if (procField != null) {
				procField.setAccessible(true);
				Object processor = procField.get(transformer);
				if (processor != null) {
					Field configsField = findField(processor.getClass(), "configs");
					if (configsField != null) {
						configsField.setAccessible(true);
						Object list = configsField.get(processor);
						if (list instanceof List) {
							List<?> copy;
							synchronized (list) {
								copy = new ArrayList<>((List<?>) list);
							}
							for (Object cfg : copy) {
								if (cfg instanceof IMixinConfig) {
									IMixinConfig mc = (IMixinConfig) cfg;
									if (matchesMixinPackage(mc.getMixinPackage(), dotClassName)) {
										return true;
									}
								}
							}
						}
					}
				}
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	private static boolean matchesMixinPackage(String mixinPackage, String dotClassName) {
		if (mixinPackage == null || mixinPackage.isEmpty() || dotClassName == null) {
			return false;
		}
		String prefix = mixinPackage.endsWith(".") ? mixinPackage : mixinPackage + ".";
		return dotClassName.startsWith(prefix) || dotClassName.equals(mixinPackage);
	}

	private static void ensureConfigsSelected(IMixinTransformer transformer) {
		if (transformer == null) {
			return;
		}
		try {
			MixinEnvironment env = MixinEnvironment.getCurrentEnvironment();
			Field procField = findField(transformer.getClass(), "processor");
			if (procField != null) {
				procField.setAccessible(true);
				Object processor = procField.get(transformer);
				if (processor != null) {
					Method checkSelect = findMethod(processor.getClass(), "checkSelect", MixinEnvironment.class);
					if (checkSelect != null) {
						checkSelect.setAccessible(true);
						checkSelect.invoke(processor, env);
						return;
					}
				}
			}
		} catch (Throwable ignored) {
		}
		try {
			MixinEnvironment env = MixinEnvironment.getCurrentEnvironment();
			ClassNode dummyNode = new ClassNode();
			dummyNode.name = "remold/dummy/SelectTrigger";
			dummyNode.version = 52;
			dummyNode.superName = "java/lang/Object";
			transformer.transformClass(env, "remold.dummy.SelectTrigger", dummyNode);
		} catch (Throwable ignored) {
		}
	}

	private static Field findField(Class<?> clazz, String fieldName) {
		for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(fieldName);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException ignored) {
			}
		}
		return null;
	}

	private static Method findMethod(Class<?> clazz, String methodName, Class<?>... paramTypes) {
		for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Method m = c.getDeclaredMethod(methodName, paramTypes);
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException ignored) {
			}
		}
		return null;
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
			RemoldAgent.warn("[Mixin] Instrumentation unavailable; skipping target class re-weaving");
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
				RemoldAgent.info("[Mixin] Target class " + name + " is not yet loaded; new mixin will apply upon initial load");
				continue;
			}
			byte[] originalBytes = readOriginalBytes(targetClass);
			if (originalBytes == null) {
				RemoldAgent.warn("[Mixin] Unable to read original bytecode for " + name + "; skipping re-weaving (runtime-generated classes not supported)");
				continue;
			}
			if (RemoldAgent.redefineDynamic(targetClass, originalBytes)) {
				RemoldAgent.info("[Mixin] Target class " + name + " re-woven with updated mixin and redefined");
			} else if (RemoldAgent.isEnhancedHotswapEnabled()) {
				// 结构化热更已经开启：拒绝来自 Remold 的安全门或 JVM 本身，不是 JDK 能力不足。
				// 继续提示"换 DCEVM 的 JBR"会把用户引向错误方向。
				RemoldAgent.warn("[Mixin] Target class " + name + " was re-woven but the redefine was refused:"
					+ " this JVM already supports structural hot-swap (JBR/DCEVM active), so the blocker is"
					+ " Remold's LayoutGate or the JVM's redefine rules — see the [LAYOUT-REJECT] /"
					+ " [DYNAMIC-REDEFINE] log above. Fix the reported field change, or restart the client.");
			} else {
				RemoldAgent.warn("[Mixin] Target class " + name + " could not be redefined after re-weaving:"
					+ " structural changes require JBR with DCEVM, or restart the client");
			}
		}
	}

	/**
	 * 读取类的原始（未织入）字节码。
	 *
	 * <p>必须用未织入的原始字节码：JVM 当前持有的字节码已经织入了旧 Mixin，再织一遍会重复注入。
	 * 读取优先级：
	 * 1. {@link RemoldAgent#findClassBytesOnDisk(Class)}：当前工程监控目录中的最新 .class，
	 *    这是唯一保证「编译产物、未经任何 transformer」的来源；
	 * 2. {@link Class#getResourceAsStream(String)}：外部/原版只读类（如 Minecraft TitleScreen）
	 *    从宿主 ClassLoader 读取的原始资源；
	 * 3. {@link RemoldAgent#getSourceBytecode(String)}：动态生成类等没有磁盘/资源来源时的兜底。</p>
	 *
	 * <p><b>为什么 sourceBytecodeCache 排在最后</b>：首次加载时该缓存由
	 * {@code AnnotationTransformer} 用 {@code classfileBuffer} 填充，而 Fabric 的 Mixin
	 * transformer 可能已经先执行过——缓存里拿到的可能是<b>已织入</b>的字节。把它当源码再织一遍
	 * 会造成重复注入：{@code @Unique} 字段/处理器被追加第二份，运行期基线与后续重织入的字段名
	 * 对不上，下一轮 target 热更就会被 LayoutGate 判为「移除了 Mixin 字段」。
	 * 工程类优先取磁盘原始字节即可绕开这份被污染的缓存。</p>
	 */
	private static byte[] readOriginalBytes(Class<?> type) {
		if (type == null) return null;
		String name = type.getName();
		byte[] disk = RemoldAgent.findClassBytesOnDisk(type);
		if (disk != null) {
			return disk;
		}
		String resource = "/" + name.replace('.', '/') + ".class";
		try (InputStream in = type.getResourceAsStream(resource)) {
			if (in != null) return in.readAllBytes();
		} catch (Throwable ignored) {
			// 落到 source cache 兜底
		}
		return RemoldAgent.getSourceBytecode(name);
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

	/**
	 * 把本次成功 reload 的 Mixin 成员增量写进当前批次的报告。
	 *
	 * <p><b>增量口径</b>：与本进程上一次成功 reload 的同一 Mixin 比较。首次热更（没有旧快照）时
	 * {@code deltaKnown=false}，增量恒为 0，只上报新版本的总量；调用方必须按“增量未知”展示，
	 * 不能把 0 读成“没有新增”。</p>
	 *
	 * <p>这里是纯观测：任何异常都不得影响热更主链路，因此整段吞掉。</p>
	 */
	private static void recordMixinReport(String dotClassName, ClassNode node) {
		try {
			MixinSnapshot current = MixinSnapshot.of(node);
			MixinSnapshot previous = SNAPSHOTS.put(dotClassName, current);
			boolean deltaKnown = previous != null;

			List<String> addedUnique = deltaKnown
				? difference(current.uniqueFields, previous.uniqueFields) : Collections.emptyList();
			List<String> removedUnique = deltaKnown
				? difference(previous.uniqueFields, current.uniqueFields) : Collections.emptyList();
			List<String> addedInject = deltaKnown
				? difference(current.injectHandlers, previous.injectHandlers) : Collections.emptyList();
			List<String> removedInject = deltaKnown
				? difference(previous.injectHandlers, current.injectHandlers) : Collections.emptyList();

			BatchReportScope.recordMixinChange(new HotswapBatchReport.MixinChange(
				dotClassName, deltaKnown,
				addedUnique.size(), removedUnique.size(),
				addedInject.size(), removedInject.size(),
				current.uniqueFields.size(), current.injectHandlers.size(),
				addedUnique, addedInject));
		} catch (Throwable ignored) {
			// 观测失败不影响 reload 结果。
		}
	}

	/** {@code from} 中有、{@code other} 中没有的成员，保持声明顺序。 */
	private static List<String> difference(Set<String> from, Set<String> other) {
		List<String> out = new ArrayList<>();
		for (String name : from) {
			if (!other.contains(name)) {
				out.add(name);
			}
		}
		return out;
	}

	/** 一次版本里的 {@code @Unique} 字段与 {@code @Inject} handler 名字集合。 */
	private static final class MixinSnapshot {
		final Set<String> uniqueFields;
		final Set<String> injectHandlers;

		private MixinSnapshot(Set<String> uniqueFields, Set<String> injectHandlers) {
			this.uniqueFields = uniqueFields;
			this.injectHandlers = injectHandlers;
		}

		static MixinSnapshot of(ClassNode node) {
			Set<String> fields = new LinkedHashSet<>();
			Set<String> handlers = new LinkedHashSet<>();
			if (node.fields != null) {
				for (FieldNode field : node.fields) {
					if (hasAnnotation(field.visibleAnnotations, UNIQUE_DESC)
						|| hasAnnotation(field.invisibleAnnotations, UNIQUE_DESC)) {
						fields.add(field.name);
					}
				}
			}
			if (node.methods != null) {
				for (MethodNode method : node.methods) {
					if (hasAnnotation(method.visibleAnnotations, INJECT_DESC)
						|| hasAnnotation(method.invisibleAnnotations, INJECT_DESC)) {
						handlers.add(method.name);
					}
				}
			}
			return new MixinSnapshot(fields, handlers);
		}

		private static boolean hasAnnotation(List<AnnotationNode> annotations, String desc) {
			if (annotations == null) {
				return false;
			}
			for (AnnotationNode annotation : annotations) {
				if (desc.equals(annotation.desc)) {
					return true;
				}
			}
			return false;
		}
	}

	/** Mixin 拒绝重载时给出「需重启」的明确提示，而不是一句泛化的异常。 */
	private static void reportReloadFailure(String dotClassName, Throwable t) {
		String reason = t.getMessage() != null ? t.getMessage() : t.toString();
		if (isStructuralRejection(t)) {
			RemoldAgent.warn("[Mixin] " + dotClassName
				+ " has structural changes (targets/interfaces/inner classes/priority); hot-swap unavailable, please restart client: " + reason);
		} else {
			RemoldAgent.warn("[Mixin] Failed to reload " + dotClassName + ", please restart client: " + reason);
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
