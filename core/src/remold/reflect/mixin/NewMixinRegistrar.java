package remold.reflect.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import remold.RemoldAgent;

/**
 * 运行期新增 Mixin 类的登记器（纯反射，无 Mixin / 加载器编译期依赖）。
 *
 * <p>Mixin 的 {@code MixinConfig} 在启动时把 JSON 里的 mixins 列表解析成 {@code MixinInfo}。
 * 运行期追加一个全新 Mixin 时，{@code IMixinTransformer.reload} 只会在该缓存里查找，找不到
 * 就返回空目标列表。本类把新类补进已注册的 {@code MixinConfig}，再对目标类做常规重织入。</p>
 */
public final class NewMixinRegistrar {

	private static final AtomicBoolean REPORTED_UNSUPPORTED = new AtomicBoolean();

	private NewMixinRegistrar() {
	}

	/**
	 * 尝试把 {@code dotClassName} 登记到其所属的已注册 Mixin 配置。
	 *
	 * @param transformer 当前生效的 Mixin 织入器
	 * @param dotClassName 新 Mixin 的点分类名
	 * @param classNode   宿主的 ASM ClassNode（不透明对象）
	 * @param suppressor  目标已加载检查抑制器
	 * @return 目标类内部名列表；登记失败、已存在或已从配置删除时返回空列表
	 */
	public static List<String> tryRegister(Object transformer, String dotClassName, Object classNode,
			LoadedTargetSuppressor suppressor) {
		if (MixinConfigDiffer.isRemovedFromConfig(dotClassName)) {
			return Collections.emptyList();
		}
		try {
			return tryRegister0(transformer, dotClassName, classNode, suppressor);
		} catch (Throwable t) {
			reportUnsupported(t);
			return Collections.emptyList();
		}
	}

	private static List<String> tryRegister0(Object transformer, String dotClassName, Object classNode,
			LoadedTargetSuppressor suppressor) throws Exception {
		Object processor = MixinReflection.readField(transformer, "processor");
		if (processor == null) {
			reportUnsupported(new IllegalStateException("MixinTransformer.processor not found"));
			return Collections.emptyList();
		}

		// 与 MixinProcessor.applyMixins 的同步口径保持一致：登记期间不让目标类变换并发读取配置。
		synchronized (processor) {
			Object configsObject = MixinReflection.readField(processor, "configs");
			if (!(configsObject instanceof List<?> configs)) {
				reportUnsupported(new IllegalStateException("MixinProcessor.configs not found"));
				return Collections.emptyList();
			}

			Object config = findOwningConfig(configs, dotClassName);
			if (config == null) {
				return Collections.emptyList();
			}
			String mixinPackage = (String) MixinReflection.invoke(config, "getMixinPackage");
			if (mixinPackage == null || !dotClassName.startsWith(mixinPackage)) {
				return Collections.emptyList();
			}
			String simpleName = dotClassName.substring(mixinPackage.length());
			if (simpleName.isEmpty() || simpleName.indexOf('.') >= 0) {
				return Collections.emptyList();
			}

			List<String> declaredTargets = declaredTargets(classNode);
			if (declaredTargets.isEmpty()) {
				return Collections.emptyList();
			}
			List<String> loadedTargets = loadedTargets(transformer, declaredTargets);

			// 已经登记过的类不在这里处理：正常的 reload 路径会返回非空目标列表。
			if (findMixinInfo(config, dotClassName) != null) {
				return Collections.emptyList();
			}
			@SuppressWarnings("unchecked")
			List<String> mixinClasses = (List<String>) MixinReflection.readField(config, "mixinClasses");
			if (mixinClasses == null) {
				reportUnsupported(new IllegalStateException("MixinConfig.mixinClasses not found"));
				return Collections.emptyList();
			}
			mixinClasses.add(simpleName);

			Object extensions = MixinReflection.readField(processor, "extensions");
			Method prepare = MixinReflection.findMethod(config.getClass(), "prepare", 1);
			if (extensions == null || prepare == null) {
				removeMixinClass(mixinClasses, simpleName);
				reportUnsupported(new IllegalStateException("MixinConfig.prepare(Extensions) not found"));
				return Collections.emptyList();
			}

			Object launcherToken = null;
			boolean suppressed = false;
			try {
				if (!loadedTargets.isEmpty()) {
					try {
						launcherToken = suppressor == null ? null : suppressor.suppress(loadedTargets);
					} catch (Throwable suppressFailure) {
						removeMixinClass(mixinClasses, simpleName);
						rollback(config, dotClassName);
						throw suppressFailure;
					}
					if (launcherToken == null) {
						removeMixinClass(mixinClasses, simpleName);
						RemoldAgent.warn("[Mixin] New mixin " + dotClassName + " targets already-loaded class "
							+ loadedTargets + ", and this loader's class tracker could not be suppressed."
							+ " Restart once to apply it (editing an existing Mixin stays hot-swappable).");
						return Collections.emptyList();
					}
					suppressed = true;
				}

				// prepare 只在首次解析配置时执行；强制置回未准备状态，让新增的 simpleName 被解析。
				MixinReflection.writeBoolean(config, "prepared", false);
				prepare.setAccessible(true);
				prepare.invoke(config, extensions);
			} catch (Throwable t) {
				removeMixinClass(mixinClasses, simpleName);
				rollback(config, dotClassName);
				throw t;
			} finally {
				if (suppressed && suppressor != null) {
					try {
						suppressor.restore(launcherToken);
					} catch (Throwable restoreFailure) {
						RemoldAgent.warn("[Mixin] Failed to restore the loader's class tracker after"
							+ " registering " + dotClassName + ": " + MixinReflection.describe(restoreFailure));
					}
				}
			}

			Object info = findMixinInfo(config, dotClassName);
			if (info == null) {
				removeMixinClass(mixinClasses, simpleName);
				rollback(config, dotClassName);
				return Collections.emptyList();
			}

			Method validate = MixinReflection.findMethod(info.getClass(), "validate");
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

			Object rawTargets = MixinReflection.invoke(info, "getTargetClasses");
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
				+ MixinReflection.invoke(config, "getName") + " (targets=" + targets + ")");
			return targets;
		}
	}

	private static Object findOwningConfig(List<?> configs, String dotClassName) {
		Object best = null;
		int bestLength = -1;
		for (Object config : configs) {
			if (config == null) {
				continue;
			}
			try {
				String mixinPackage = (String) MixinReflection.invoke(config, "getMixinPackage");
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
		Object mixinsObject = MixinReflection.readField(config, "mixins");
		if (!(mixinsObject instanceof List<?> mixins)) {
			return null;
		}
		for (Object mixin : mixins) {
			try {
				Object name = MixinReflection.invoke(mixin, "getClassName");
				if (dotClassName.equals(name)) {
					return mixin;
				}
			} catch (Throwable ignored) {
			}
		}
		return null;
	}

	/** 校验失败或解析失败时撤销登记，避免坏 Mixin 影响后续类加载。 */
	private static void rollback(Object config, String dotClassName) {
		try {
			Object info = findMixinInfo(config, dotClassName);
			Object mixinsObject = MixinReflection.readField(config, "mixins");
			if (mixinsObject instanceof List<?> mixins && info != null) {
				mixins.remove(info);
			}
			Object mappingObject = MixinReflection.readField(config, "mixinMapping");
			if (mappingObject instanceof Map<?, ?> mapping) {
				for (Object value : mapping.values()) {
					if (value instanceof List<?> list) {
						list.remove(info);
					}
				}
				java.util.Iterator<? extends Map.Entry<?, ?>> entries = mapping.entrySet().iterator();
				while (entries.hasNext()) {
					Map.Entry<?, ?> entry = entries.next();
					if (entry.getValue() instanceof List<?> list && list.isEmpty()) {
						entries.remove();
					}
				}
			}
			Object unhandled = MixinReflection.readField(config, "unhandledTargets");
			if (unhandled instanceof Set<?> set && info != null) {
				Object targets = MixinReflection.invoke(info, "getTargetClasses");
				if (targets instanceof List<?> list) {
					for (Object target : list) {
						set.remove(String.valueOf(target).replace('/', '.'));
					}
				}
			}
			Object global = MixinReflection.readField(config, "globalMixinList");
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
	 * 从 {@code @Mixin} 注解读出目标类内部名。仅用反射读取 ASM 的
	 * {@code ClassNode.invisibleAnnotations} / {@code AnnotationNode.values}，不引入 ASM 依赖。
	 */
	static List<String> declaredTargets(Object classNode) {
		if (classNode == null) {
			return Collections.emptyList();
		}
		List<String> targets = new ArrayList<>();
		for (String field : List.of("invisibleAnnotations", "visibleAnnotations")) {
			Object annotations = MixinReflection.readField(classNode, field);
			if (!(annotations instanceof Collection<?> collection)) {
				continue;
			}
			for (Object annotation : collection) {
				Object desc = MixinReflection.readField(annotation, "desc");
				if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(desc)) {
					continue;
				}
				Object values = MixinReflection.readField(annotation, "values");
				if (!(values instanceof List<?> list)) {
					continue;
				}
				for (int i = 0; i + 1 < list.size(); i += 2) {
					Object key = list.get(i);
					if ("value".equals(key) || "targets".equals(key)) {
						addTarget(targets, list.get(i + 1));
					}
				}
			}
		}
		return targets;
	}

	private static void addTarget(List<String> targets, Object value) {
		if (value instanceof String string) {
			targets.add(string.replace('.', '/'));
			return;
		}
		if (value instanceof Collection<?> collection) {
			for (Object item : collection) {
				addTarget(targets, item);
			}
			return;
		}
		// ASM Type：不编译依赖，调用其 getInternalName()
		try {
			Method getInternalName = value == null ? null : value.getClass().getMethod("getInternalName");
			if (getInternalName != null) {
				targets.add(String.valueOf(getInternalName.invoke(value)));
			}
		} catch (Throwable ignored) {
		}
	}

	private static List<String> loadedTargets(Object transformer, List<String> targets) {
		List<String> loaded = new ArrayList<>();
		try {
			ClassLoader transformerLoader = transformer.getClass().getClassLoader();
			Class<?> serviceClass = Class.forName("org.spongepowered.asm.service.MixinService", true, transformerLoader);
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
			// 拿不到服务时让真实的 MixinInfo 构造器做最终判定。
		}
		return loaded;
	}

	private static void reportUnsupported(Throwable t) {
		if (REPORTED_UNSUPPORTED.compareAndSet(false, true)) {
			RemoldAgent.warn("[Mixin] Runtime registration of a new Mixin class failed on this"
				+ " Mixin version; restart once to apply it. Cause: " + MixinReflection.describe(t));
		}
	}
}
