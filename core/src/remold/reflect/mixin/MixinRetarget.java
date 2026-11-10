package remold.reflect.mixin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
 * <p><b>为什么不能只靠 reload</b>：上游 {@code MixinInfo.Reloaded} 直接抛
 * {@code Cannot change target classes}。但这里<b>不能</b>走“摘除 + 重新登记”的路线：
 * {@code MixinInfo} 构造器会通过 {@code MixinConfig} 启动时捕获的 {@code IMixinService}
 * 重新读一次字节码（<b>不是</b> Remold 的覆盖层），读到的仍是磁盘上的旧模板；旧模板声明的旧目标
 * 早已加载，于是直接抛 {@code MixinTargetAlreadyLoadedException}（真机实测，见
 * {@code docs/status.md} 的 Mixin 热更边界）。</p>
 *
 * <p><b>做法</b>：在调用 {@code reload} <b>之前</b>把 {@code MixinInfo} 的目标元数据与配置索引
 * 迁到新目标，使 {@code Reloaded.validateChanges} 的
 * {@code readTargetClasses(...).equals(this.targetClasses)} 判据自然成立；随后照常 reload，
 * 由 Mixin 自己重建状态并返回目标列表。</p>
 *
 * <ul>
 *   <li>{@code MixinInfo.targetClasses / targetClassNames / declaredTargets}：内容替换为新目标；</li>
 *   <li>{@code config.mixinMapping / unhandledTargets}：旧目标摘除、新目标挂入；</li>
 *   <li>旧目标 {@code ClassInfo.mixins}：移除该 {@code MixinInfo}（新目标的条目由 reload 的
 *       {@code readTargetClasses} 自行补入）。</li>
 * </ul>
 *
 * <p><b>回滚</b>：{@link #apply} 返回的快照必须在 reload 失败时交给 {@link #rollback} 还原，
 * 否则配置索引会停留在“已迁到新目标、但状态仍是旧目标”的半更新状态。</p>
 */
public final class MixinRetarget {

	private MixinRetarget() {
	}

	/** 一次目标迁移的快照：{@code affected} 需要重织入（旧目标 ∪ 新目标）。 */
	public static final class Result {

		private final List<String> affected;
		private final List<String> oldTargets;
		private final List<String> newTargets;
		private final Object config;
		private final Object info;
		private final List<Object> oldTargetClasses;
		private final List<Object> oldTargetClassNames;
		private final List<Object> oldDeclaredTargets;
		private final Map<String, List<Object>> removedMapping;
		private final Set<String> removedUnhandled;
		private final Map<Object, Set<Object>> removedClassInfoMixins;

		private Result(List<String> affected, List<String> oldTargets, List<String> newTargets,
				Object config, Object info, List<Object> oldTargetClasses, List<Object> oldTargetClassNames,
				List<Object> oldDeclaredTargets, Map<String, List<Object>> removedMapping,
				Set<String> removedUnhandled, Map<Object, Set<Object>> removedClassInfoMixins) {
			this.affected = affected;
			this.oldTargets = oldTargets;
			this.newTargets = newTargets;
			this.config = config;
			this.info = info;
			this.oldTargetClasses = oldTargetClasses;
			this.oldTargetClassNames = oldTargetClassNames;
			this.oldDeclaredTargets = oldDeclaredTargets;
			this.removedMapping = removedMapping;
			this.removedUnhandled = removedUnhandled;
			this.removedClassInfoMixins = removedClassInfoMixins;
		}

		/** 需要重织入的目标类（点分名，旧 ∪ 新）。 */
		public List<String> affected() {
			return this.affected;
		}

		public List<String> oldTargets() {
			return this.oldTargets;
		}

		public List<String> newTargets() {
			return this.newTargets;
		}

		public String describe() {
			return this.oldTargets + " -> " + this.newTargets;
		}
	}

	/**
	 * 把该 Mixin 的目标元数据与配置索引迁到新目标。
	 *
	 * <p>调用方必须在返回非 {@code null} 后立即执行 {@code transformer.reload(dotClassName, node)}：
	 * reload 会重建状态并把新目标写回 {@code mixinMapping}。reload 失败时必须调用
	 * {@link #rollback}。</p>
	 *
	 * @return 迁移结果（含需要重织入的目标）；无法迁移（未登记、新目标解析不到、目标集合未变）返回 {@code null}
	 */
	public static Result apply(Object transformer, String dotClassName, Object classNode,
			Function<Collection<String>, Object> suppress, Consumer<Object> restore, String loaderHook) {
		return apply(transformer, dotClassName, classNode, suppress, restore, loaderHook, null);
	}

	/**
	 * 同上，但允许注入 {@code ClassInfo} 解析器（单测用：真实 {@code ClassInfo.forName} 需要
	 * Mixin 服务与目标字节，普通 JUnit 里拿不到）。
	 *
	 * @param classInfoResolver 接收内部名、返回 {@code ClassInfo}（或 {@code null}）；{@code null} 表示走真实反射
	 */
	static Result apply(Object transformer, String dotClassName, Object classNode,
			Function<Collection<String>, Object> suppress, Consumer<Object> restore, String loaderHook,
			Function<String, Object> classInfoResolver) {
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
		try {
			List<String> oldTargets = dotted(MixinReflection.invoke(info, "getTargetClasses"));
			List<String> newTargets = dotted(NewMixinRegistrar.declaredTargets(classNode));
			if (newTargets.isEmpty() || new LinkedHashSet<>(oldTargets).equals(new LinkedHashSet<>(newTargets))) {
				return null;
			}
			List<Object> newTargetClasses = new ArrayList<>(newTargets.size());
			for (String target : newTargets) {
				Object classInfo = resolveClassInfo(info, classInfoResolver, target.replace('.', '/'));
				if (classInfo == null) {
					// 新目标解析不到（拼错 / 字节不可读）：交给原生 reload 给出明确拒绝。
					return null;
				}
				newTargetClasses.add(classInfo);
			}

			List<Object> oldTargetClasses = snapshot(info, "targetClasses");
			List<Object> oldTargetClassNames = snapshot(info, "targetClassNames");
			List<Object> oldDeclaredTargets = snapshot(info, "declaredTargets");

			Map<String, List<Object>> removedMapping = new LinkedHashMap<>();
			Set<String> removedUnhandled = new LinkedHashSet<>();
			Map<Object, Set<Object>> removedClassInfoMixins = new LinkedHashMap<>();

			// 1) MixinInfo 的目标元数据：内容替换（字段是 final 容器，只能改内容）
			replace(info, "targetClasses", newTargetClasses);
			List<Object> internalNames = new ArrayList<>(newTargets.size());
			for (String target : newTargets) {
				internalNames.add(target.replace('.', '/'));
			}
			replace(info, "targetClassNames", internalNames);
			replaceDeclaredTargets(info, newTargets);

			// 2) 配置索引：旧目标摘除
			Object mapping = MixinReflection.readField(config, "mixinMapping");
			if (mapping instanceof Map<?, ?> map) {
				Iterator<? extends Map.Entry<?, ?>> entries = map.entrySet().iterator();
				while (entries.hasNext()) {
					Map.Entry<?, ?> entry = entries.next();
					if (!(entry.getValue() instanceof List<?> list)) {
						continue;
					}
					@SuppressWarnings("unchecked")
					List<Object> values = (List<Object>) list;
					if (!values.contains(info)) {
						continue;
					}
					removedMapping.put(String.valueOf(entry.getKey()), new ArrayList<>(values));
					values.remove(info);
					if (values.isEmpty()) {
						entries.remove();
					}
				}
			}
			Object unhandledObject = MixinReflection.readField(config, "unhandledTargets");
			if (unhandledObject instanceof Set<?> set) {
				@SuppressWarnings("unchecked")
				Set<Object> unhandled = (Set<Object>) set;
				for (String target : oldTargets) {
					if (unhandled.remove(target)) {
						removedUnhandled.add(target);
					}
				}
			}
			for (String target : oldTargets) {
				Object classInfo = resolveClassInfo(info, classInfoResolver, target.replace('.', '/'));
				if (classInfo == null) {
					continue;
				}
				Object mixins = MixinReflection.readField(classInfo, "mixins");
				if (mixins instanceof Set<?> set && ((Set<?>) set).contains(info)) {
					@SuppressWarnings("unchecked")
					Set<Object> mixinSet = (Set<Object>) set;
					mixinSet.remove(info);
					removedClassInfoMixins.put(classInfo, Set.of(info));
				}
			}

			// 3) 配置索引：新目标挂入（reload 的 readTargetClasses 只补 ClassInfo.mixins）
			for (String target : newTargets) {
				List<Object> list = null;
				if (mapping instanceof Map<?, ?> map) {
					Object existing = map.get(target);
					if (existing instanceof List<?> existingList) {
						@SuppressWarnings("unchecked")
						List<Object> typed = (List<Object>) existingList;
						list = typed;
					} else {
						list = new ArrayList<>();
						@SuppressWarnings("unchecked")
						Map<Object, Object> raw = (Map<Object, Object>) map;
						raw.put(target, list);
					}
				}
				if (list != null && !list.contains(info)) {
					list.add(info);
				}
				if (unhandledObject instanceof Set<?> set) {
					@SuppressWarnings("unchecked")
					Set<Object> unhandled = (Set<Object>) set;
					unhandled.add(target);
				}
			}

			Set<String> affected = new LinkedHashSet<>(oldTargets);
			affected.addAll(newTargets);
			return new Result(new ArrayList<>(affected), oldTargets, newTargets, config, info,
				oldTargetClasses, oldTargetClassNames, oldDeclaredTargets, removedMapping,
				removedUnhandled, removedClassInfoMixins);
		} catch (Throwable t) {
			return null;
		}
	}

	/** reload 失败时把目标元数据与配置索引还原到迁移前。 */
	public static void rollback(Object transformer, String dotClassName, Result result) {
		if (result == null) {
			return;
		}
		try {
			replace(result.info, "targetClasses", result.oldTargetClasses);
			replace(result.info, "targetClassNames", result.oldTargetClassNames);
			replace(result.info, "declaredTargets", result.oldDeclaredTargets);

			Object mapping = MixinReflection.readField(result.config, "mixinMapping");
			if (mapping instanceof Map<?, ?> map) {
				@SuppressWarnings("unchecked")
				Map<Object, Object> raw = (Map<Object, Object>) map;
				for (String newTarget : result.newTargets) {
					Object existing = raw.get(newTarget);
					if (existing instanceof List<?> list) {
						@SuppressWarnings("unchecked")
						List<Object> values = (List<Object>) list;
						values.remove(result.info);
						if (values.isEmpty()) {
							raw.remove(newTarget);
						}
					}
				}
				for (Map.Entry<String, List<Object>> entry : result.removedMapping.entrySet()) {
					Object existing = raw.get(entry.getKey());
					List<Object> list;
					if (existing instanceof List<?> existingList) {
						@SuppressWarnings("unchecked")
						List<Object> typed = (List<Object>) existingList;
						list = typed;
					} else {
						list = new ArrayList<>();
						raw.put(entry.getKey(), list);
					}
					for (Object value : entry.getValue()) {
						if (!list.contains(value)) {
							list.add(value);
						}
					}
				}
			}
			Object unhandledObject = MixinReflection.readField(result.config, "unhandledTargets");
			if (unhandledObject instanceof Set<?> set) {
				@SuppressWarnings("unchecked")
				Set<Object> unhandled = (Set<Object>) set;
				for (String newTarget : result.newTargets) {
					unhandled.remove(newTarget);
				}
				unhandled.addAll(result.removedUnhandled);
			}
			for (Map.Entry<Object, Set<Object>> entry : result.removedClassInfoMixins.entrySet()) {
				Object mixins = MixinReflection.readField(entry.getKey(), "mixins");
				if (mixins instanceof Set<?> set) {
					@SuppressWarnings("unchecked")
					Set<Object> mixinSet = (Set<Object>) set;
					mixinSet.addAll(entry.getValue());
				}
			}
		} catch (Throwable ignored) {
			// 回滚是尽力而为；调用方已经给出“需重启”的诊断。
		}
	}

	// ------------------------------------------------------------------ 工具

	private static List<String> dotted(Object rawTargets) {
		if (!(rawTargets instanceof List<?> list)) {
			return List.of();
		}
		List<String> out = new ArrayList<>(list.size());
		for (Object target : list) {
			if (target == null) {
				continue;
			}
			String dotted = String.valueOf(target).replace('/', '.');
			if (!out.contains(dotted)) {
				out.add(dotted);
			}
		}
		return out;
	}

	private static List<Object> snapshot(Object target, String fieldName) {
		Object value = MixinReflection.readField(target, fieldName);
		if (value instanceof List<?> list) {
			return new ArrayList<>(list);
		}
		return new ArrayList<>();
	}

	@SuppressWarnings("unchecked")
	private static void replace(Object target, String fieldName, Collection<?> values) {
		Object value = MixinReflection.readField(target, fieldName);
		if (!(value instanceof List<?> list)) {
			return;
		}
		List<Object> typed = (List<Object>) list;
		typed.clear();
		typed.addAll(values);
	}

	/** 把 {@code MixinInfo.declaredTargets}（{@code List<DeclaredTarget>}）替换成新目标。 */
	private static void replaceDeclaredTargets(Object info, List<String> newTargets) throws Exception {
		Field field = MixinReflection.findField(info.getClass(), "declaredTargets");
		if (field == null) {
			return;
		}
		Object value = field.get(info);
		if (!(value instanceof List<?> list)) {
			return;
		}
		Class<?> declaredTargetType = Class.forName(
			"org.spongepowered.asm.mixin.transformer.MixinInfo$DeclaredTarget", true,
			info.getClass().getClassLoader());
		Constructor<?> constructor = declaredTargetType.getDeclaredConstructor(String.class, boolean.class);
		constructor.setAccessible(true);
		List<Object> rebuilt = new ArrayList<>(newTargets.size());
		for (String target : newTargets) {
			rebuilt.add(constructor.newInstance(target, Boolean.FALSE));
		}
		@SuppressWarnings("unchecked")
		List<Object> typed = (List<Object>) list;
		typed.clear();
		typed.addAll(rebuilt);
	}

	private static Object resolveClassInfo(Object info, Function<String, Object> resolver, String internalName) {
		return resolver != null ? resolver.apply(internalName) : classInfoFor(info, internalName);
	}

	/** 按内部名取上游 {@code ClassInfo}；取不到返回 {@code null}（不阻断迁移）。 */
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
