package remold.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import remold.RemoldAgent;

/**
 * 运行期新增 Mixin 类的兼容层。
 *
 * <p><b>为什么需要它</b>：Mixin 的 {@code MixinConfig} 在启动时把 JSON 里的 {@code mixins}
 * 列表解析成 {@code MixinInfo} 并缓存。运行期追加一个全新的 Mixin 类时，
 * {@code IMixinTransformer.reload} 只会在该缓存里查找，找不到就返回空目标列表——这不是
 * “目标类没加载”，而是“Mixin 还没被登记”。本类通过 Mixin 0.8.7 的内部结构把新类补进
 * 已注册的 {@code MixinConfig}，再交给调用方对目标类做一次常规重织入。</p>
 *
 * <p><b>边界与失败策略</b>：只使用反射，字段/方法签名对不上时返回空列表并打印一条可操作
 * 的 warn（提示重启），绝不吞掉到“看起来成功”。补登记后先 {@code MixinInfo.validate()}
 * 校验结构契约，校验失败会回滚登记，避免把坏 Mixin 留在配置里影响后续类加载。</p>
 *
 * <p><b>已知限制</b>：这是 Mixin 内部 API 的兼容层，不是 Mixin 公开契约；Mixin 升级后
 * 字段改名会让本类退化为“提示重启”。因此它只是热重载的开发期便利，生产环境仍建议重启。</p>
 */
final class NewMixinRegistrar {

	private static final AtomicBoolean REPORTED_UNSUPPORTED = new AtomicBoolean();

	private NewMixinRegistrar() {
	}

	/**
	 * 尝试把 {@code dotClassName} 登记到其所属的已注册 Mixin 配置。
	 *
	 * @param transformer  当前生效的 Mixin 织入器
	 * @param dotClassName 新 Mixin 的点分类名
	 * @param node         新 Mixin 的完整 ClassNode（已解析方法体）
	 * @return 目标类的内部名列表；登记失败或该 Mixin 已存在时返回空列表
	 */
	static List<String> tryRegister(IMixinTransformer transformer, String dotClassName, ClassNode node) {
		try {
			return tryRegister0(transformer, dotClassName, node);
		} catch (Throwable t) {
			reportUnsupported(t);
			return Collections.emptyList();
		}
	}

	private static List<String> tryRegister0(IMixinTransformer transformer, String dotClassName, ClassNode node) throws Exception {
		Object processor = readField(transformer, "processor");
		if (processor == null) {
			reportUnsupported(new IllegalStateException("MixinTransformer.processor not found"));
			return Collections.emptyList();
		}

		// 与 MixinProcessor.applyMixins 的同步口径保持一致：登记期间不让目标类变换并发读取配置。
		synchronized (processor) {
			Object configsObject = readField(processor, "configs");
			if (!(configsObject instanceof List<?> configs)) {
				reportUnsupported(new IllegalStateException("MixinProcessor.configs not found"));
				return Collections.emptyList();
			}

			Object config = findOwningConfig(configs, dotClassName);
			if (config == null) {
				return Collections.emptyList();
			}
			String mixinPackage = (String) invoke(config, "getMixinPackage");
			if (mixinPackage == null || !dotClassName.startsWith(mixinPackage)) {
				return Collections.emptyList();
			}
			String simpleName = dotClassName.substring(mixinPackage.length());
			if (simpleName.isEmpty() || simpleName.indexOf('.') >= 0) {
				return Collections.emptyList();
			}

			// MixinInfo 的构造器在目标类已加载时会直接抛 MixinTargetAlreadyLoadedException：
			// Mixin 的设计前提是“目标加载前就登记好”。所以这里先探测目标是否已加载，
			// 已加载就明确拒绝（而不是抛到上层变成难懂的 InvocationTargetException）。
			List<String> declaredTargets = declaredTargets(node);
			if (declaredTargets.isEmpty()) {
				return Collections.emptyList();
			}
			List<String> loadedTargets = loadedTargets(declaredTargets);

			// 已经登记过的类不在这里处理：正常的 reload 路径会返回非空目标列表。
			if (findMixinInfo(config, dotClassName) != null) {
				return Collections.emptyList();
			}
			@SuppressWarnings("unchecked")
			List<String> mixinClasses = (List<String>) readField(config, "mixinClasses");
			if (mixinClasses == null) {
				reportUnsupported(new IllegalStateException("MixinConfig.mixinClasses not found"));
				return Collections.emptyList();
			}
			mixinClasses.add(simpleName);

			Object extensions = readField(processor, "extensions");
			Method prepare = findMethod(config.getClass(), "prepare", 1);
			if (extensions == null || prepare == null || prepare.getParameterCount() != 1) {
				removeMixinClass(mixinClasses, simpleName);
				reportUnsupported(new IllegalStateException("MixinConfig.prepare(Extensions) not found"));
				return Collections.emptyList();
			}

			// MixinInfo 构造器在目标已加载时会拒绝；Fabric 上用极窄窗口的 launcher 代理把
			// 本次目标临时报告为“未加载”，构造完成后立刻恢复。别的加载器拿不到代理时 fail-closed。
			Object launcherToken = FabricLauncherSpoof.suppress(loadedTargets);
			if (!loadedTargets.isEmpty() && launcherToken == null) {
				removeMixinClass(mixinClasses, simpleName);
				RemoldAgent.warn("[Mixin] New mixin " + dotClassName + " targets already-loaded class "
					+ loadedTargets + ", and this loader does not expose Fabric's class tracker to"
					+ " suppress the check. Restart once to apply it (editing an existing Mixin stays"
					+ " hot-swappable).");
				return Collections.emptyList();
			}
			try {
				// prepare 只在首次解析配置时执行；强制置回未准备状态，让新增的 simpleName 被解析。
				Field prepared = findField(config.getClass(), "prepared");
				if (prepared != null) {
					prepared.setAccessible(true);
					prepared.setBoolean(config, false);
				}
				prepare.setAccessible(true);
				prepare.invoke(config, extensions);
			} catch (Throwable t) {
				removeMixinClass(mixinClasses, simpleName);
				rollback(config, dotClassName);
				throw t;
			} finally {
				FabricLauncherSpoof.restore(launcherToken);
			}

			Object info = findMixinInfo(config, dotClassName);
			if (info == null) {
				removeMixinClass(mixinClasses, simpleName);
				rollback(config, dotClassName);
				return Collections.emptyList();
			}

			// prepareMixins 只创建 MixinInfo，不跑 validate；不校验的状态直接交给织入器会
			// 在应用期抛出更难定位的异常，所以这里显式走一次结构契约校验。
			Method validate = findMethod(info.getClass(), "validate");
			if (validate == null) {
				removeMixinClass(mixinClasses, simpleName);
				rollback(config, dotClassName);
				reportUnsupported(new IllegalStateException("MixinInfo.validate() not found"));
				return Collections.emptyList();
			}
			try {
				validate.setAccessible(true);
				validate.invoke(info);
			} catch (Throwable t) {
				removeMixinClass(mixinClasses, simpleName);
				rollback(config, dotClassName);
				throw t;
			}

			if (RemoldAgent.DEBUG) {
				Object mapping = readField(config, "mixinMapping");
				RemoldAgent.log("[Mixin] new-mixin debug: className=" + invoke(info, "getClassName")
					+ " mappingKeys=" + (mapping instanceof java.util.Map<?, ?> map ? map.keySet() : "?")
					+ " mixinCount=" + (((List<?>) readField(config, "mixins")) == null
						? "?" : ((List<?>) readField(config, "mixins")).size()));
			}

			Object rawTargets = invoke(info, "getTargetClasses");
			if (!(rawTargets instanceof List<?> list) || list.isEmpty()) {
				removeMixinClass(mixinClasses, simpleName);
				rollback(config, dotClassName);
				RemoldAgent.warn("[Mixin] New mixin " + dotClassName
					+ " was parsed but declares no resolvable target class; restart required if this persists");
				return Collections.emptyList();
			}
			List<String> targets = new ArrayList<>(list.size());
			for (Object target : list) {
				if (target != null) {
					targets.add(String.valueOf(target));
				}
			}
			RemoldAgent.info("[Mixin] New mixin " + dotClassName + " registered into config "
				+ invoke(config, "getName") + " (targets=" + targets + ")");
			return targets;
		}
	}

	/**
	 * 找到包名匹配最长、且尚未包含该 Mixin 的配置。Mixin 的 mixin 类只允许与配置处于
	 * 同一个包（{@code mixinPackage + simpleName}），因此按前缀匹配即可。
	 */
	private static Object findOwningConfig(List<?> configs, String dotClassName) {
		Object best = null;
		int bestLength = -1;
		for (Object config : configs) {
			if (config == null) {
				continue;
			}
			try {
				String mixinPackage = (String) invoke(config, "getMixinPackage");
				if (mixinPackage == null || mixinPackage.isEmpty()
					|| !dotClassName.startsWith(mixinPackage)) {
					continue;
				}
				int length = mixinPackage.length();
				if (length > bestLength) {
					best = config;
					bestLength = length;
				}
			} catch (Throwable ignored) {
			}
		}
		return best;
	}

	private static Object findMixinInfo(Object config, String dotClassName) {
		Object mixinsObject = readField(config, "mixins");
		if (!(mixinsObject instanceof List<?> mixins)) {
			return null;
		}
		for (Object mixin : mixins) {
			try {
				Object name = invoke(mixin, "getClassName");
				if (dotClassName.equals(name)) {
					return mixin;
				}
			} catch (Throwable ignored) {
			}
		}
		return null;
	}

	/**
	 * 校验失败或解析失败时撤销登记：从配置的已解析列表、目标映射、未处理目标集合、
	 * 简单类名列表以及 Mixin 的全局去重集合中移除，避免坏 Mixin 影响后续类加载。
	 */
	private static void rollback(Object config, String dotClassName) {
		try {
			Object info = findMixinInfo(config, dotClassName);
			Object mixinsObject = readField(config, "mixins");
			if (mixinsObject instanceof List<?> mixins && info != null) {
				mixins.remove(info);
			}
			Object mappingObject = readField(config, "mixinMapping");
			if (mappingObject instanceof Map<?, ?> mapping) {
				for (Object value : mapping.values()) {
					if (value instanceof List<?> list) {
						list.remove(info);
					}
				}
				// 空的目标映射条目也清掉，避免回滚后留下"目标存在但无 Mixin"的残留。
				java.util.Iterator<? extends Map.Entry<?, ?>> entries = mapping.entrySet().iterator();
				while (entries.hasNext()) {
					Map.Entry<?, ?> entry = entries.next();
					if (entry.getValue() instanceof List<?> list && list.isEmpty()) {
						entries.remove();
					}
				}
			}
			Object unhandled = readField(config, "unhandledTargets");
			if (unhandled instanceof Set<?> set && info != null) {
				Object targets = invoke(info, "getTargetClasses");
				if (targets instanceof List<?> list) {
					for (Object target : list) {
						set.remove(String.valueOf(target).replace('/', '.'));
					}
				}
			}
			Object global = readField(config, "globalMixinList");
			if (global instanceof Set<?> set) {
				set.remove(dotClassName);
			}
		} catch (Throwable ignored) {
			// 回滚是尽力而为；真正的失败原因已经由调用方 warn。
		}
	}

	private static void removeMixinClass(List<String> mixinClasses, String simpleName) {
		mixinClasses.remove(simpleName);
	}

	/**
	 * 从 {@code @Mixin} 注解读出目标类内部名。{@code value} 是 {@link Type} 或 Type 列表，
	 * {@code targets} 是字符串或字符串列表。
	 */
	private static List<String> declaredTargets(ClassNode node) {
		if (node == null || node.invisibleAnnotations == null) {
			return Collections.emptyList();
		}
		List<String> targets = new ArrayList<>();
		for (AnnotationNode annotation : node.invisibleAnnotations) {
			if (annotation == null || !"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc)
				|| annotation.values == null) {
				continue;
			}
			for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
				Object key = annotation.values.get(i);
				Object value = annotation.values.get(i + 1);
				if ("value".equals(key)) {
					addTarget(targets, value);
				} else if ("targets".equals(key)) {
					addTarget(targets, value);
				}
			}
		}
		return targets;
	}

	private static void addTarget(List<String> targets, Object value) {
		if (value instanceof Type type) {
			targets.add(type.getInternalName());
		} else if (value instanceof String string) {
			targets.add(string.replace('.', '/'));
		} else if (value instanceof List<?> list) {
			for (Object item : list) {
				addTarget(targets, item);
			}
		}
	}

	/** 返回已加载的目标类（点分类名）；拿不到 Mixin 服务时返回空列表。 */
	private static List<String> loadedTargets(List<String> targets) {
		List<String> loaded = new ArrayList<>();
		try {
			Class<?> serviceClass = Class.forName("org.spongepowered.asm.service.MixinService");
			Object service = serviceClass.getMethod("getService").invoke(null);
			Object tracker = service.getClass().getMethod("getClassTracker").invoke(service);
			if (tracker == null) {
				return loaded;
			}
			Method isLoaded = tracker.getClass().getMethod("isClassLoaded", String.class);
			for (String target : targets) {
				String dotted = target.replace('/', '.');
				if (Boolean.TRUE.equals(isLoaded.invoke(tracker, dotted))) {
					loaded.add(dotted);
				}
			}
		} catch (Throwable ignored) {
			// 拿不到 Mixin 服务时让真实的 MixinInfo 构造器做最终判定。
		}
		return loaded;
	}

	private static void reportUnsupported(Throwable t) {
		if (REPORTED_UNSUPPORTED.compareAndSet(false, true)) {
			RemoldAgent.warn("[Mixin] Runtime registration of a new Mixin class failed on this"
				+ " Mixin version; restart once to apply it. Cause: " + describe(t));
		}
	}

	/** 展开反射包装的 InvocationTargetException，并把 cause 链的前几层栈打出来，避免只看到包装异常。 */
	private static String describe(Throwable t) {
		StringBuilder sb = new StringBuilder();
		Throwable current = t;
		int depth = 0;
		while (current != null && depth < 6) {
			sb.append(depth == 0 ? "" : " <- ").append(current.getClass().getName());
			if (current.getMessage() != null) {
				sb.append(": ").append(current.getMessage());
			}
			if (current.getCause() != null && current.getCause() != current) {
				current = current.getCause();
				depth++;
				continue;
			}
			StackTraceElement[] frames = current.getStackTrace();
			for (int i = 0; i < Math.min(frames.length, 10); i++) {
				sb.append("\n    at ").append(frames[i]);
			}
			break;
		}
		return sb.toString();
	}

	private static Object readField(Object target, String name) {
		if (target == null) {
			return null;
		}
		Field field = findField(target.getClass(), name);
		if (field == null) {
			return null;
		}
		try {
			field.setAccessible(true);
			return field.get(target);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Object invoke(Object target, String name) {
		if (target == null) {
			return null;
		}
		Method method = findMethod(target.getClass(), name);
		if (method == null) {
			return null;
		}
		try {
			method.setAccessible(true);
			return method.invoke(target);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Field findField(Class<?> clazz, String fieldName) {
		for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Field field = c.getDeclaredField(fieldName);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
			}
		}
		return null;
	}

	private static Method findMethod(Class<?> clazz, String methodName) {
		return findMethod(clazz, methodName, 0);
	}

	private static Method findMethod(Class<?> clazz, String methodName, int parameterCount) {
		for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Method method : c.getDeclaredMethods()) {
				if (method.getName().equals(methodName) && method.getParameterCount() == parameterCount) {
					method.setAccessible(true);
					return method;
				}
			}
		}
		return null;
	}
}
