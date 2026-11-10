package remold.reflect.mixin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 「{@code @Mixin(targets=...)} 变更」的差集卸载 + 重新挂载（纯反射）。
 *
 * <p><b>为什么不能走 reload</b>：上游 {@code MixinInfo.Reloaded} 直接抛
 * {@code Cannot change target classes}。即使绕过该判据，{@code MixinInfo.targetClasses} /
 * {@code targetClassNames} 也是构造期写入的 {@code final} 结构，{@code mixinMapping}、
 * {@code unhandledTargets}、{@code globalMixinList} 与目标 {@code ClassInfo.mixins} 四处元数据
 * 同样停留在旧目标上——单靠一次 reload 无法把 Mixin 从旧目标迁到新目标。</p>
 *
 * <p><b>做法</b>：把旧 {@code MixinInfo} 从配置的全部索引里摘除（等价于“配置里删掉这个 Mixin”），
 * 再用 {@link NewMixinRegistrar} 按新字节码重新登记——新 {@code MixinInfo} 会依据新的
 * {@code @Mixin} 注解重建目标集合与映射，于是差集自然成立：</p>
 * <ul>
 *   <li>旧目标 − 新目标：映射已摘除，宿主重织入时不再命中该 Mixin → 注入产物随重织入消失；</li>
 *   <li>新目标 − 旧目标：新登记已把 {@code MixinInfo} 挂进目标映射 → 重织入时注入生效；</li>
 *   <li>交集：仍按新字节码重织入。</li>
 * </ul>
 *
 * <p><b>失败回滚</b>：重新登记失败时把旧 {@code MixinInfo} 原样放回它被摘除的每个索引位置
 * （列表尾、映射列表、未处理目标、全局集合、目标 {@code ClassInfo.mixins}），调用方据此回落原生
 * reload 路径，得到既有的「需重启」诊断。绝不留下“已从配置摘除但没登记回来”的半状态。</p>
 *
 * <p><b>登记前提</b>：新字节码必须已经能被 Mixin 的字节码提供方读到（调用方负责把新字节登记进
 * 覆盖层并驱逐 {@code ClassInfo} 缓存），否则 {@code prepare()} 会读到旧字节或抛
 * {@code ClassNotFoundException}。</p>
 */
public final class MixinRetarget {

	private MixinRetarget() {
	}

	/**
	 * 按新字节码重建该 Mixin 的目标映射。
	 *
	 * @param transformer  当前生效的 Mixin 织入器
	 * @param dotClassName 被热更的 Mixin 点分类名
	 * @param classNode    新字节码的 ASM ClassNode（不透明对象）
	 * @param suppress     “目标类已加载”检查抑制函数（{@code null} = 本加载器不提供）
	 * @param restore      抑制令牌恢复函数（{@code null} = 本加载器不提供）
	 * @param loaderHook   诊断用：加载器检查点描述，可为 {@code null}
	 * @return 需要重织入的目标类点分名（旧目标 ∪ 新目标）；重建失败返回 {@code null}
	 */
	public static List<String> apply(Object transformer, String dotClassName, Object classNode,
			Function<Collection<String>, Object> suppress, Consumer<Object> restore, String loaderHook) {
		if (transformer == null || dotClassName == null || classNode == null) {
			return null;
		}
		Object processor = MixinReflection.readField(transformer, "processor");
		if (processor == null) {
			return null;
		}
		Object configsObject = MixinReflection.readField(processor, "configs");
		if (!(configsObject instanceof List<?> configs)) {
			return null;
		}
		Object config = NewMixinRegistrar.findOwningConfig(configs, dotClassName);
		if (config == null) {
			return null;
		}
		Object info = NewMixinRegistrar.findMixinInfo(config, dotClassName);
		if (info == null) {
			return null;
		}

		Detached detached = detach(config, info, dotClassName);
		if (detached == null) {
			return null;
		}
		List<String> registered;
		try {
			registered = NewMixinRegistrar.tryRegister(transformer, dotClassName, classNode,
				suppress, restore, loaderHook);
		} catch (Throwable t) {
			detached.restore();
			return null;
		}
		if (registered == null || registered.isEmpty()) {
			detached.restore();
			return null;
		}
		Set<String> affected = new LinkedHashSet<>(detached.oldTargets);
		for (String target : registered) {
			if (target != null) {
				affected.add(target.replace('/', '.'));
			}
		}
		return new ArrayList<>(affected);
	}

	// ------------------------------------------------------------------ 摘除

	/** 摘除时的原始位置快照，用于失败回滚。 */
	private static final class Detached {

		private final Object config;
		private final Object info;
		private final String dotClassName;
		private final String simpleName;
		private final List<String> oldTargets;
		private final Map<String, List<Object>> mappingLists = new LinkedHashMap<>();
		private final Set<String> unhandled = new LinkedHashSet<>();
		private final Map<Object, Set<Object>> classInfoMixins = new LinkedHashMap<>();
		private final boolean preparedBefore;
		private final boolean globalListed;

		private Detached(Object config, Object info, String dotClassName, String simpleName,
				List<String> oldTargets, boolean preparedBefore, boolean globalListed) {
			this.config = config;
			this.info = info;
			this.dotClassName = dotClassName;
			this.simpleName = simpleName;
			this.oldTargets = oldTargets;
			this.preparedBefore = preparedBefore;
			this.globalListed = globalListed;
		}

		/** 把旧 {@code MixinInfo} 放回被摘除的每个索引位置（尽力而为，失败只影响诊断）。 */
		@SuppressWarnings("unchecked")
		private void restore() {
			try {
				Object mixinsObject = MixinReflection.readField(this.config, "mixins");
				if (mixinsObject instanceof List<?> mixins) {
					((List<Object>) mixins).add(this.info);
				}
				Object classes = MixinReflection.readField(this.config, "mixinClasses");
				if (classes instanceof List<?> mixinClasses) {
					((List<Object>) mixinClasses).add(this.simpleName);
				}
				Object mapping = MixinReflection.readField(this.config, "mixinMapping");
				if (mapping instanceof Map<?, ?> map) {
					Map<Object, Object> target = (Map<Object, Object>) map;
					for (Map.Entry<String, List<Object>> entry : this.mappingLists.entrySet()) {
						Object existing = target.get(entry.getKey());
						List<Object> list;
						if (existing instanceof List<?> existingList) {
							list = (List<Object>) existingList;
						} else {
							list = new ArrayList<>();
							target.put(entry.getKey(), list);
						}
						for (Object value : entry.getValue()) {
							if (!list.contains(value)) {
								list.add(value);
							}
						}
					}
				}
				Object unhandledObject = MixinReflection.readField(this.config, "unhandledTargets");
				if (unhandledObject instanceof Set<?> set) {
					((Set<Object>) set).addAll(this.unhandled);
				}
				if (this.globalListed) {
					Object global = MixinReflection.readField(this.config, "globalMixinList");
					if (global instanceof Set<?> set) {
						((Set<Object>) set).add(this.dotClassName);
					}
				}
				for (Map.Entry<Object, Set<Object>> entry : this.classInfoMixins.entrySet()) {
					Object mixins = MixinReflection.readField(entry.getKey(), "mixins");
					if (mixins instanceof Set<?> set) {
						((Set<Object>) set).addAll(entry.getValue());
					}
				}
				MixinReflection.writeBoolean(this.config, "prepared", this.preparedBefore);
			} catch (Throwable ignored) {
				// 回滚是尽力而为；调用方仍会回落原生 reload，并得到“需重启”的诊断。
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Detached detach(Object config, Object info, String dotClassName) {
		try {
			String mixinPackage = (String) MixinReflection.invoke(config, "getMixinPackage");
			if (mixinPackage == null || !dotClassName.startsWith(mixinPackage)) {
				return null;
			}
			String simpleName = dotClassName.substring(mixinPackage.length());
			List<String> oldTargets = new ArrayList<>();
			Object rawTargets = MixinReflection.invoke(info, "getTargetClasses");
			if (rawTargets instanceof List<?> list) {
				for (Object target : list) {
					if (target != null) {
						oldTargets.add(String.valueOf(target).replace('/', '.'));
					}
				}
			}
			if (oldTargets.isEmpty()) {
				return null;
			}
			Object global = MixinReflection.readField(config, "globalMixinList");
			boolean globalListed = global instanceof Set<?> set && set.contains(dotClassName);
			Object prepared = MixinReflection.readField(config, "prepared");
			boolean preparedBefore = Boolean.TRUE.equals(prepared);

			Detached detached = new Detached(config, info, dotClassName, simpleName, oldTargets,
				preparedBefore, globalListed);

			Object mixinsObject = MixinReflection.readField(config, "mixins");
			if (mixinsObject instanceof List<?> mixins) {
				((List<Object>) mixins).remove(info);
			}
			Object classes = MixinReflection.readField(config, "mixinClasses");
			if (classes instanceof List<?> mixinClasses) {
				((List<Object>) mixinClasses).remove(simpleName);
			}
			Object mapping = MixinReflection.readField(config, "mixinMapping");
			if (mapping instanceof Map<?, ?> map) {
				Map<Object, Object> target = (Map<Object, Object>) map;
				Iterator<? extends Map.Entry<?, ?>> entries = target.entrySet().iterator();
				while (entries.hasNext()) {
					Map.Entry<?, ?> entry = entries.next();
					if (!(entry.getValue() instanceof List<?> list)) {
						continue;
					}
					List<Object> values = (List<Object>) list;
					if (!values.contains(info)) {
						continue;
					}
					detached.mappingLists.put(String.valueOf(entry.getKey()), new ArrayList<>(values));
					values.remove(info);
					if (values.isEmpty()) {
						entries.remove();
					}
				}
			}
			Object unhandledObject = MixinReflection.readField(config, "unhandledTargets");
			if (unhandledObject instanceof Set<?> set) {
				Set<Object> unhandled = (Set<Object>) set;
				for (String target : oldTargets) {
					if (unhandled.remove(target)) {
						detached.unhandled.add(target);
					}
				}
			}
			for (String target : oldTargets) {
				Object classInfo = classInfoFor(info, target.replace('.', '/'));
				if (classInfo == null) {
					continue;
				}
				Object mixins = MixinReflection.readField(classInfo, "mixins");
				if (mixins instanceof Set<?> set && ((Set<Object>) set).contains(info)) {
					((Set<Object>) set).remove(info);
					detached.classInfoMixins.put(classInfo, Set.of(info));
				}
			}
			if (globalListed) {
				Object globalSet = MixinReflection.readField(config, "globalMixinList");
				if (globalSet instanceof Set<?> set) {
					((Set<Object>) set).remove(dotClassName);
				}
			}
			return detached;
		} catch (Throwable t) {
			return null;
		}
	}

	/** 按内部名取上游 {@code ClassInfo}；取不到返回 {@code null}（不阻断摘除）。 */
	private static Object classInfoFor(Object info, String internalName) {
		try {
			ClassLoader loader = info.getClass().getClassLoader();
			Class<?> classInfo = Class.forName("org.spongepowered.asm.mixin.transformer.ClassInfo", true, loader);
			return classInfo.getMethod("forName", String.class).invoke(null, internalName);
		} catch (Throwable t) {
			return null;
		}
	}
}
