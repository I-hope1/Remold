package remold.mixin;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

	/**
	 * 等待内部类热更的 Mixin：点分类名 → 需要重定义模板内部类的内部名集合。
	 *
	 * <p>内部类重定义**不在桥接线程上做**：桥接发生在批次中间（持有 HOTSWAP_LOCK，且批次尚未
	 * redefine 完），在那里对 Mixin 生成的合成类调 {@code redefineClasses} 会把服务器线程冻住
	 * （真实 KnotServer 探针实测：S1b 之后 tick 心跳全停）。改到与目标类重织入同一个 worker 线程、
	 * 批次之后执行。</p>
	 */
	private static final Map<String, Set<String>> PENDING_INNER = new LinkedHashMap<>();

	/** 上一次成功 reload 时该 Mixin 的源码级字节，用来跳过「同一份字节的重复 reload」。 */
	private static final Map<String, byte[]> LAST_RELOADED_BYTES = new java.util.concurrent.ConcurrentHashMap<>();

	/** 同一份 Mixin 字节在一个批次里会被问两次（模板本身 + 它的内部类），reload 只需一次。 */
	private static boolean alreadyReloaded(String dotClassName, byte[] newMixinBytes) {
		byte[] last = LAST_RELOADED_BYTES.get(dotClassName);
		return last != null && java.util.Arrays.equals(last, newMixinBytes);
	}

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
			//
			// 但「交回普通路径」不能把 Mixin 模板当成可加载类：模板一旦被 defineClass 进宿主
			// ClassLoader，就成了同名的假类，之后连重启前的状态都会被污染。异常分支上先按
			// 纯字节特征把模板登记为不可加载（不依赖任何 Mixin 类），再放弃处理。
			try {
				if (newMixinBytes != null && looksLikeMixin(newMixinBytes)) {
					RemoldAgent.markNonLoadableClass(dotClassName);
				}
			} catch (Throwable ignored) {
				// 连字节扫描都失败时不改变行为，只是无法再加这道保险。
			}
			if (REPORTED.add("<handler>")) {
				RemoldAgent.warn("[Mixin] Mixin hot-swap handler unavailable: " + t
					+ " (the class was left untouched; a Mixin template that could not be identified is"
					+ " never defined as a loadable class)");
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

		ClassNode node = new ClassNode();
		try {
			// 必须完整解析：@Inject 的 handler 方法体要从这里搬进目标类，跳过 code 会织出空方法。
			new ClassReader(newMixinBytes).accept(node, 0);
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] " + dotClassName + " has unreadable bytecode; it cannot be hot-swapped: " + t);
			return false;
		}

		// Mixin 的内部类（Outer$N）不是独立模板：它不在任何 mixin 配置里，也没有自己的目标类，
		// 按模板去 reload 只会拿到空目标并把真正的新字节丢掉。必须把字节交给 Mixin 的字节码
		// 提供方，再由外层 Mixin 重新 reload + 重织入（见 handleInnerClassChange）。
		if (MixinInnerClassSupport.isNestedClassName(MixinInnerClassSupport.internalName(dotClassName))
			&& !looksLikeMixin(newMixinBytes)) {
			return handleInnerClassChange(dotClassName, newMixinBytes);
		}

		// Mixin 只是织入模板，不是可加载类：登记后核心会跳过 defineClass / redefine，
		// 读者扫描也不会把它当成外部读者。
		RemoldAgent.markNonLoadableClass(dotClassName);
		IMixinTransformer transformer = MixinReWeaver.activeTransformer();
		if (transformer == null) {
			return true;
		}

		MixinBytecodeOverlay.install();
		ensureConfigsSelected(transformer);

		// 本模板自己的内部类（含本轮首次出现的匿名类）：先把新字节接进 Mixin 的字节码提供方，
		// 并清掉 ClassInfo 的负缓存。否则 reload 的校验阶段会拿
		// ClassNotFoundException 之后的 null 去调 isProbablyStatic()，直接崩成一句裸 NPE。
		List<String> innerNames =
			MixinInnerClassSupport.ownInnerClassNames(MixinInnerClassSupport.internalName(dotClassName), node);
		registerInnerClassBytes(dotClassName, innerNames);
		MixinBytecodeOverlay.evictClassInfoCache(innerNames);

		if (alreadyReloaded(dotClassName, newMixinBytes)) {
			// 模板字节没变（本轮只有它的内部类变了）：Mixin 状态无需 refresh，
			// 但内部类热更仍要按新的模板字节重做一次。
			scheduleInnerClassHotUpdate(dotClassName, innerNames);
			return true;
		}

		List<String> targets = Collections.emptyList();
		MixinClassInfoSync.SyncToken syncToken = MixinClassInfoSync.SyncToken.EMPTY;
		try {
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
			// reload 被拒绝时撤销 reload 前补入的成员，让旧元数据保持可用；
			// 同时收回覆盖层里的新字节，避免一份没被接受的模板长期遮蔽旧版本。
			MixinClassInfoSync.rollback(syncToken);
			MixinBytecodeOverlay.clear(innerNames);
			reportReloadFailure(dotClassName, t);
			return true;
		}

		if (targets.isEmpty()) {
			// 配置里已经显式删除的 Mixin：即使它的 .class 又因为全量编译被写了一次，
			// 也不能走“新增 Mixin”路径把它重新登记回来。
			if (MixinConfigDiff.isRemovedFromConfig(dotClassName)) {
				RemoldAgent.info("[Mixin] " + dotClassName + " was removed from the mixin config;"
					+ " ignoring its class change (re-add it to the config to enable it again)");
				return true;
			}
			// MixinConfig.reloadMixin 只在启动时解析出的 mixin 列表里查找；返回空说明该类
			// 不在任何已注册配置的 mixin 列表中——即运行期新增的 Mixin 类。优先尝试把新类
			// 补登记进配置；内部结构不匹配时退化为明确提示重启，而不是谎报成功。
			List<String> registered = NewMixinRegistrar.tryRegister(transformer, dotClassName, node);
			if (registered.isEmpty()) {
				RemoldAgent.warn("[Mixin] " + dotClassName + " is not registered in the startup Mixin config"
					+ " and runtime registration failed. Restart once so the config picks it up"
					+ " (editing an existing Mixin stays hot-swappable).");
				return true;
			}
			targets = normalize(registered);
		}

		// reload/登记成功后再记录批次报告：失败/被拒绝的模板不能进“变更列表”，
		// 也不能推进快照（否则下一次重试会拿错误基线算增量）。
		recordMixinReport(dotClassName, node);
		LAST_RELOADED_BYTES.put(dotClassName, newMixinBytes.clone());
		RemoldAgent.info("[Mixin] " + dotClassName + " reloaded; affected target(s): " + targets);
		scheduleInnerClassHotUpdate(dotClassName, innerNames);
		synchronized (PENDING_LOCK) {
			PENDING.addAll(targets);
		}
		WORKER.execute(MixinHotSwap::flushPendingTargets);
		return true;
	}

	/**
	 * 只改了 Mixin 内部类（{@code Outer$N}）时的入口：登记字节 + 让外层 Mixin 重跑一遍。
	 *
	 * <p>这条路径是 R5 的触发点：外层 Mixin 的字节一个都没变，批次里只有 {@code Outer$N.class}
	 * 变了。若不显式回到外层 Mixin，整轮热更会因为「宿主 target 字节没变」被静默判 no-op，
	 * 匿名类继续跑旧逻辑。</p>
	 */
	private static boolean handleInnerClassChange(String dotClassName, byte[] newMixinBytes) {
		RemoldAgent.markNonLoadableClass(dotClassName);
		MixinBytecodeOverlay.install();
		MixinBytecodeOverlay.register(dotClassName, newMixinBytes);
		String internal = MixinInnerClassSupport.internalName(dotClassName);
		MixinBytecodeOverlay.evictClassInfoCache(Collections.singletonList(internal));

		String outerInternal = MixinInnerClassSupport.outerInternalName(internal);
		if (outerInternal == null) {
			RemoldAgent.warn("[Mixin] " + dotClassName + " looks like an inner class but its enclosing type"
				+ " cannot be derived from the name; its new bytecode is registered but no Mixin reload was"
				+ " scheduled (restart the client if the change does not show up)");
			return true;
		}
		String outerDot = MixinInnerClassSupport.dotName(outerInternal);
		byte[] outerBytes = RemoldAgent.sourceBytecodeCache.get(outerDot);
		if (outerBytes == null) {
			outerBytes = RemoldAgent.findClassBytesOnDisk(outerDot);
		}
		if (outerBytes == null) {
			RemoldAgent.warn("[Mixin] " + dotClassName + " changed, but the enclosing Mixin " + outerDot
				+ " has no readable bytecode (neither the batch cache nor the watch directories have it)."
				+ " The anonymous class body cannot be re-generated until " + outerDot
				+ " is compiled again — touch that file or restart the client");
			return true;
		}
		if (!isMixin(outerDot, outerBytes)) {
			RemoldAgent.warn("[Mixin] " + dotClassName + " changed, but its enclosing type " + outerDot
				+ " is not a Mixin template; an inner class outside a Mixin is handled by the normal"
				+ " class-redefine path, not by the Mixin hot-swap");
			return true;
		}
		RemoldAgent.info("[Mixin] inner class " + dotClassName + " changed; re-running the enclosing Mixin "
			+ outerDot + " so the new anonymous-class body is re-generated");
		return handleIfMixin0(outerDot, outerBytes);
	}

	/**
	 * 把本模板内部类的最新字节登记到 Mixin 字节码提供方的覆盖层。
	 *
	 * <p>字节来源优先级：本轮批次的源码级字节（{@code sourceBytecodeCache}）→ 监控目录里的最新
	 * {@code .class}。前者在桥接阶段之前就已写入，因此与批次内类的处理顺序无关。</p>
	 */
	private static void registerInnerClassBytes(String mixinDotName, List<String> innerNames) {
		if (innerNames == null || innerNames.isEmpty()) {
			return;
		}
		List<String> registered = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (String internal : innerNames) {
			String dot = MixinInnerClassSupport.dotName(internal);
			byte[] bytes = RemoldAgent.sourceBytecodeCache.get(dot);
			if (bytes == null) {
				bytes = RemoldAgent.findClassBytesOnDisk(dot);
			}
			if (bytes == null) {
				missing.add(dot);
				continue;
			}
			MixinBytecodeOverlay.register(dot, bytes);
			registered.add(dot);
		}
		if (!registered.isEmpty()) {
			RemoldAgent.info("[Mixin] " + mixinDotName + ": inner class bytecode registered with the"
				+ " Mixin bytecode provider: " + registered);
		}
		if (!missing.isEmpty()) {
			RemoldAgent.warn("[Mixin] " + mixinDotName + ": inner class bytecode is unavailable for "
				+ missing + "; if this class is newly declared in the Mixin, a reload of it will fail with"
				+ " ClassNotFoundException until it is compiled into a watched output directory");
		}
	}

	/**
	 * reload 成功后把内部类的变化落到运行期合成类上，并如实报告结果。
	 *
	 * <p>目标类的织入产物不含匿名类方法体，所以「只改匿名类」时目标类的 redefine 必然是
	 * no-op。这里必须显式说明「变更已落在哪个内部类上」，否则日志里就只剩一句
	 * {@code [DYNAMIC-REDEFINE] no-op}，把「已经生效」读成「什么都没发生」。</p>
	 */
	private static void scheduleInnerClassHotUpdate(String mixinDotName, List<String> innerNames) {
		if (innerNames == null || innerNames.isEmpty()) {
			return;
		}
		synchronized (PENDING_LOCK) {
			PENDING_INNER.computeIfAbsent(mixinDotName, key -> new LinkedHashSet<>()).addAll(innerNames);
		}
		WORKER.execute(MixinHotSwap::flushPendingTargets);
	}

	private static void reportInnerClassHotUpdate(IMixinTransformer transformer, String mixinDotName,
			List<String> innerNames) {
		if (innerNames == null || innerNames.isEmpty()) {
			return;
		}
		MixinInnerClassHotSwap.Result result =
			MixinInnerClassHotSwap.apply(transformer, mixinDotName, innerNames);
		if (result.changed()) {
			RemoldAgent.info("[Mixin] inner class hot update applied: " + result.describeConformed()
				+ " redefined in place. A following [DYNAMIC-REDEFINE] \"no-op\" line for the target class is"
				+ " expected here: the target's own bytecode did not change — the behaviour change lives in the"
				+ " anonymous class(es) listed above.");
		}
		if (result.hasFailure()) {
			RemoldAgent.warn("[Mixin] inner class hot update incomplete for " + mixinDotName + ": "
				+ result.describeFailures());
		}
	}

	/** 把一组目标类排入重织入队列（供配置差异等外部入口复用）。 */
	static void scheduleTargets(List<String> targets) {
		if (targets == null || targets.isEmpty()) {
			return;
		}
		synchronized (PENDING_LOCK) {
			PENDING.addAll(targets);
		}
		WORKER.execute(MixinHotSwap::flushPendingTargets);
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
		Map<String, List<String>> innerBatch;
		synchronized (PENDING_LOCK) {
			if (PENDING.isEmpty() && PENDING_INNER.isEmpty()) {
				return;
			}
			batch = new ArrayList<>(PENDING);
			PENDING.clear();
			innerBatch = new LinkedHashMap<>();
			for (Map.Entry<String, Set<String>> entry : PENDING_INNER.entrySet()) {
				innerBatch.put(entry.getKey(), new ArrayList<>(entry.getValue()));
			}
			PENDING_INNER.clear();
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
			} else if (RemoldAgent.lastRedefineFailedAtVmLevel() || RemoldAgent.isVmRedefinitionPoisoned(name)) {
				// 归因纠偏：JVM 级失败（InternalError/VerifyError）与布局拒绝是两码事，
				// 把它引向 LayoutGate 会让用户去改字段，而真正该做的是立刻重启。
				RemoldAgent.warn("[Mixin] Target class " + name + " was re-woven but the redefine failed at JVM level"
					+ " (InternalError/VerifyError): JVM metadata may already be inconsistent — do NOT keep"
					+ " hot-swapping; RESTART the client. This is NOT a LayoutGate rejection.");
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

		// 目标类重织入之后再处理内部类：此时首次引入的内部类已经随目标织入完成登记，
		// 已加载的合成内部类则可以按新的模板字节重新生成并重定义。
		IMixinTransformer transformer = MixinReWeaver.activeTransformer();
		for (Map.Entry<String, List<String>> entry : innerBatch.entrySet()) {
			reportInnerClassHotUpdate(transformer, entry.getKey(), entry.getValue());
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
		byte[] chosen = RemoldAgent.findClassBytesOnDisk(type);
		if (chosen == null) {
			String resource = "/" + name.replace('.', '/') + ".class";
			try (InputStream in = type.getResourceAsStream(resource)) {
				if (in != null) chosen = in.readAllBytes();
			} catch (Throwable ignored) {
				// 落到 source cache 兜底
			}
		}
		if (chosen == null) chosen = RemoldAgent.getSourceBytecode(name);
		if (chosen == null) return null;
		// 第二层（源头补齐）：重放加载器在 Mixin 之前做的访问变换（Fabric dev 的
		// PackageAccessFixer、AccessWidener 等）。不重放就会以更窄的访问权限送回 JVM，
		// 让 JBR 增强重定义在 link/verify 子类时抛 VerifyError -> InternalError。
		return RemoldAgent.applySourceTransforms(name, chosen);
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
