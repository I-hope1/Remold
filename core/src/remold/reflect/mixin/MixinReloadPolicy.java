package remold.reflect.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import remold.Reflect;

/**
 * {@code MixinInfo.reloadMixin()} 的前置策略层（纯反射，无 Mixin / 加载器编译期依赖）。
 *
 * <p><b>为什么需要</b>：上游 {@code MixinInfo.Reloaded.validateChanges} 会把四类元数据变更直接判死
 * （{@code Cannot change mixin priority / target classes / interfaces / soft interfaces}）。
 * 这四条判据写于标准 HotSpot 时代，只机械比对元数据，不考虑宿主是否具备重织入能力。本类在调用
 * {@code reload} <b>之前</b>把「可安全接受的变更」先对齐到上游的比较基线，使 reload 自身的
 * 校验与状态构建照常完成：</p>
 *
 * <ul>
 *   <li><b>priority</b>：优先级在 JVM 字节码层零体现，只参与 {@code MixinInfo.compareTo}
 *       （织入期 {@code TreeSet<MixinInfo>} 的排序）。把 {@code MixinInfo.priority} 对齐到新值后，
 *       reload 的 {@code readPriority(...) != getPriority()} 判据自然成立，随后重织入即按新顺序排序。
 *       该字段是 {@code final int}，因此必须走 {@link Reflect#UNSAFE} 的字段写入。</li>
 *   <li><b>interfaces</b>：{@code Reloaded} 复用<b>旧</b> {@code ClassInfo}，其比较基线是旧 state 的
 *       {@code interfaces} 集合。仅当宿主具备增强重定义能力（JBR/DCEVM）时才把旧 state 与旧
 *       {@code ClassInfo} 的接口表同步到新集合；标准 HotSpot 上不做任何对齐，保持上游拒绝。</li>
 * </ul>
 *
 * <p><b>targets 不在这里处理</b>：目标集合变化需要同步 {@code mixinMapping} /
 * {@code unhandledTargets} / {@code globalMixinList} / 目标 {@code ClassInfo.mixins} 四处元数据，
 * 单靠 reload 无法完成（{@code MixinInfo.targetClasses} 是构造期写入的）。该场景走
 * {@link MixinRetarget} 的差集卸载 + 重新挂载。</p>
 *
 * <p><b>失败语义</b>：任何一步做不到（拿不到字段/方法、Unsafe 不可用、结构不符）都返回
 * {@link Plan#usable()} 为 {@code false} 的计划，调用方回落到原生 reload 路径——那里会给出
 * 既有的「需重启」诊断，绝不会因为本类静默放行一个未对齐的状态。</p>
 */
public final class MixinReloadPolicy {

	/** 一次 reload 前读出的新元数据，以及已经完成的预对齐动作。 */
	public static final class Plan {

		private final String failure;
		private final int oldPriority;
		private final int newPriority;
		private final boolean priorityPreAligned;
		private final List<String> oldTargets;
		private final List<String> newTargets;
		private final Set<String> oldInterfaces;
		private final Set<String> newInterfaces;
		private final boolean interfacePreAligned;

		private Plan(String failure, int oldPriority, int newPriority, boolean priorityPreAligned,
				List<String> oldTargets, List<String> newTargets,
				Set<String> oldInterfaces, Set<String> newInterfaces, boolean interfacePreAligned) {
			this.failure = failure;
			this.oldPriority = oldPriority;
			this.newPriority = newPriority;
			this.priorityPreAligned = priorityPreAligned;
			this.oldTargets = oldTargets == null ? List.of() : List.copyOf(oldTargets);
			this.newTargets = newTargets == null ? List.of() : List.copyOf(newTargets);
			this.oldInterfaces = oldInterfaces == null ? Set.of() : Set.copyOf(oldInterfaces);
			this.newInterfaces = newInterfaces == null ? Set.of() : Set.copyOf(newInterfaces);
			this.interfacePreAligned = interfacePreAligned;
		}

		private static Plan unusable(String reason) {
			return new Plan(reason, 0, 0, false, List.of(), List.of(), Set.of(), Set.of(), false);
		}

		/** 本计划是否可用；{@code false} 时调用方必须回落原生 reload 路径。 */
		public boolean usable() {
			return this.failure == null;
		}

		/** 不可用原因（仅诊断用）。 */
		public String failure() {
			return this.failure;
		}

		public boolean priorityChanged() {
			return usable() && this.oldPriority != this.newPriority;
		}

		public boolean priorityPreAligned() {
			return this.priorityPreAligned;
		}

		public int oldPriority() {
			return this.oldPriority;
		}

		public int newPriority() {
			return this.newPriority;
		}

		/** 声明的目标集合是否变化（点分名比较，顺序无关）。 */
		public boolean targetsChanged() {
			return usable() && !new LinkedHashSet<>(this.oldTargets).equals(new LinkedHashSet<>(this.newTargets));
		}

		public List<String> oldTargets() {
			return this.oldTargets;
		}

		public List<String> newTargets() {
			return this.newTargets;
		}

		/** 硬接口（mixin 自身 {@code implements}）是否变化。 */
		public boolean interfacesChanged() {
			return usable() && !this.oldInterfaces.equals(this.newInterfaces);
		}

		public boolean interfacePreAligned() {
			return this.interfacePreAligned;
		}

		public Set<String> addedInterfaces() {
			Set<String> added = new LinkedHashSet<>(this.newInterfaces);
			added.removeAll(this.oldInterfaces);
			return added;
		}

		public Set<String> removedInterfaces() {
			Set<String> removed = new LinkedHashSet<>(this.oldInterfaces);
			removed.removeAll(this.newInterfaces);
			return removed;
		}

		public String describe() {
			StringBuilder sb = new StringBuilder();
			if (priorityChanged()) {
				sb.append("priority ").append(this.oldPriority).append("->").append(this.newPriority)
					.append(priorityPreAligned() ? "" : " (NOT aligned)");
			}
			if (targetsChanged()) {
				if (sb.length() > 0) sb.append("; ");
				sb.append("targets ").append(this.oldTargets).append("->").append(this.newTargets);
			}
			if (interfacesChanged()) {
				if (sb.length() > 0) sb.append("; ");
				sb.append("interfaces +").append(addedInterfaces()).append(" -").append(removedInterfaces())
					.append(interfacePreAligned() ? "" : " (NOT aligned)");
			}
			return sb.length() == 0 ? "no metadata change" : sb.toString();
		}
	}

	private MixinReloadPolicy() {
	}

	/**
	 * 撤销 {@link #prepare} 做过的预对齐（仅用于 reload 被拒绝后的回滚）。
	 *
	 * <p>不撤销会让旧 state 声称“已经是新接口/新优先级”，而 {@code MixinInfo.state} 的方法体与
	 * 目标类织入产物仍是旧的：后续任何一次 reload 的比较基线都会错位。回滚是尽力而为，
	 * 失败只影响诊断（调用方本来就会给出“需重启”提示）。</p>
	 *
	 * @param transformer  当前生效的 Mixin 织入器
	 * @param dotClassName 被热更的 Mixin 点分类名
	 * @param plan         {@link #prepare} 返回的计划，可为 {@code null}
	 */
	public static void rollback(Object transformer, String dotClassName, Plan plan) {
		if (transformer == null || dotClassName == null || plan == null || !plan.usable()) {
			return;
		}
		try {
			Object processor = MixinReflection.readField(transformer, "processor");
			if (processor == null) {
				return;
			}
			Object configsObject = MixinReflection.readField(processor, "configs");
			if (!(configsObject instanceof List<?> configs)) {
				return;
			}
			Object config = NewMixinRegistrar.findOwningConfig(configs, dotClassName);
			if (config == null) {
				return;
			}
			Object info = NewMixinRegistrar.findMixinInfo(config, dotClassName);
			if (info == null) {
				return;
			}
			if (plan.priorityPreAligned()) {
				writePriority(info, plan.oldPriority());
			}
			if (plan.interfacePreAligned()) {
				restoreInterfaces(info, plan.oldInterfaces);
			}
		} catch (Throwable ignored) {
			// 回滚是尽力而为。
		}
	}

	/**
	 * 读出一次 reload 的新元数据，并按需完成 priority / interfaces 的预对齐。
	 *
	 * @param transformer          当前生效的 Mixin 织入器
	 * @param dotClassName         被热更的 Mixin 点分类名
	 * @param classNode            新字节码的 ASM ClassNode（不透明对象）
	 * @param allowInterfaceChange 宿主是否具备增强重定义能力（JBR/DCEVM）
	 * @return 计划；不可用时调用方回落原生 reload
	 */
	public static Plan prepare(Object transformer, String dotClassName, Object classNode,
			boolean allowInterfaceChange) {
		if (transformer == null || dotClassName == null || classNode == null) {
			return Plan.unusable("missing transformer / class name / class node");
		}
		try {
			Object processor = MixinReflection.readField(transformer, "processor");
			if (processor == null) {
				return Plan.unusable("MixinTransformer.processor not found");
			}
			Object configsObject = MixinReflection.readField(processor, "configs");
			if (!(configsObject instanceof List<?> configs)) {
				return Plan.unusable("MixinProcessor.configs not found");
			}
			Object config = NewMixinRegistrar.findOwningConfig(configs, dotClassName);
			if (config == null) {
				return Plan.unusable("no owning MixinConfig for " + dotClassName);
			}
			Object info = NewMixinRegistrar.findMixinInfo(config, dotClassName);
			if (info == null) {
				return Plan.unusable(dotClassName + " is not registered in its MixinConfig yet");
			}

			int oldPriority = asInt(MixinReflection.invoke(info, "getPriority"), 0);
			Integer declaredPriority = declaredPriority(classNode);
			int newPriority = declaredPriority != null
				? declaredPriority
				: asInt(MixinReflection.invoke(config, "getDefaultMixinPriority"), oldPriority);
			boolean priorityPreAligned = false;
			if (oldPriority != newPriority) {
				priorityPreAligned = writePriority(info, newPriority);
				if (!priorityPreAligned) {
					return Plan.unusable("cannot update the final MixinInfo.priority field");
				}
			}

			List<String> oldTargets = normalizeTargets(MixinReflection.invoke(info, "getTargetClasses"));
			List<String> newTargets = normalizeTargets(NewMixinRegistrar.declaredTargets(classNode));
			Set<String> oldInterfaces = currentInterfaces(info);
			Set<String> newInterfaces = declaredInterfaces(classNode);
			boolean interfacesChanged = !oldInterfaces.equals(newInterfaces);
			boolean interfacePreAligned = false;
			if (interfacesChanged && allowInterfaceChange) {
				interfacePreAligned = alignInterfaces(info, newInterfaces);
			}
			return new Plan(null, oldPriority, newPriority, priorityPreAligned,
				oldTargets, newTargets, oldInterfaces, newInterfaces, interfacePreAligned);
		} catch (Throwable t) {
			return Plan.unusable(MixinReflection.describe(t));
		}
	}

	// ------------------------------------------------------------------ 读取

	/** {@code @Mixin(priority = ...)}；注解未写时返回 {@code null}（由配置默认值决定）。 */
	static Integer declaredPriority(Object classNode) {
		for (String field : List.of("invisibleAnnotations", "visibleAnnotations")) {
			Object annotations = MixinReflection.readField(classNode, field);
			if (!(annotations instanceof Collection<?> collection)) {
				continue;
			}
			for (Object annotation : collection) {
				if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(MixinReflection.readField(annotation, "desc"))) {
					continue;
				}
				Object values = MixinReflection.readField(annotation, "values");
				if (!(values instanceof List<?> list)) {
					continue;
				}
				for (int i = 0; i + 1 < list.size(); i += 2) {
					if ("priority".equals(list.get(i)) && list.get(i + 1) instanceof Integer value) {
						return value;
					}
				}
			}
		}
		return null;
	}

	/** 硬接口集合：Mixin 自身 {@code implements} 的接口内部名。 */
	static Set<String> declaredInterfaces(Object classNode) {
		Object interfaces = MixinReflection.readField(classNode, "interfaces");
		Set<String> out = new LinkedHashSet<>();
		if (interfaces instanceof Collection<?> collection) {
			for (Object item : collection) {
				if (item != null) {
					out.add(String.valueOf(item).replace('.', '/'));
				}
			}
		}
		return out;
	}

	/** 当前生效 state 的接口集合；取不到时返回空集合（宁可判为“变了”也不假装相同）。 */
	static Set<String> currentInterfaces(Object info) {
		Object state = MixinReflection.readField(info, "state");
		Object interfaces = MixinReflection.readField(state, "interfaces");
		Set<String> out = new LinkedHashSet<>();
		if (interfaces instanceof Collection<?> collection) {
			for (Object item : collection) {
				if (item != null) {
					out.add(String.valueOf(item).replace('.', '/'));
				}
			}
		}
		return out;
	}

	private static List<String> normalizeTargets(Object rawTargets) {
		if (!(rawTargets instanceof List<?> list)) {
			return Collections.emptyList();
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

	// ------------------------------------------------------------------ 预对齐

	/**
	 * 把 {@code MixinInfo.priority}（{@code final int}）写到新值。
	 *
	 * <p>先尝试普通反射写（非 final 的兼容分支），final 时走 {@link Reflect#UNSAFE}。
	 * 返回 {@code false} 表示无法写入——调用方必须回落原生 reload（也就是“优先级变更需重启”）。</p>
	 */
	@SuppressWarnings("removal")
	static boolean writePriority(Object info, int value) {
		Field field = MixinReflection.findField(info.getClass(), "priority");
		if (field == null) {
			return false;
		}
		try {
			if (!Modifier.isFinal(field.getModifiers())) {
				// 只有真走反射写入时才要求可访问性；final 分支只算 Unsafe 偏移，提前 setAccessible 会在
				// JPMS 强封装模块下抛 InaccessibleObjectException，把本来可用的 Unsafe 路径一起拖黑。
				field.setAccessible(true);
				field.setInt(info, value);
				return true;
			}
			if (Reflect.UNSAFE == null) {
				return false;
			}
			long offset = Reflect.UNSAFE.objectFieldOffset(field);
			Reflect.UNSAFE.putInt(info, offset, value);
			Reflect.UNSAFE.storeFence();
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	/**
	 * 把旧 state 与旧 {@code ClassInfo} 的接口表对齐到新集合，使 {@code Reloaded} 的比较判据成立。
	 *
	 * <p>新 state 仍由 reload 依据新字节码自行构建（接口集合来自新 {@code ClassNode}），这里改的只是
	 * 比较基线与元数据表，不改变 reload 的结果。</p>
	 */
	static boolean alignInterfaces(Object info, Set<String> newInterfaces) {
		Object state = MixinReflection.readField(info, "state");
		Object stateInterfaces = MixinReflection.readField(state, "interfaces");
		if (!(stateInterfaces instanceof Collection<?> collection)) {
			return false;
		}
		Object classInfo = MixinReflection.readField(info, "info");
		Object classInfoInterfaces = MixinReflection.readField(classInfo, "interfaces");
		if (!(classInfoInterfaces instanceof Collection<?> known)) {
			return false;
		}
		try {
			@SuppressWarnings("unchecked")
			Collection<String> stateTarget = (Collection<String>) collection;
			@SuppressWarnings("unchecked")
			Collection<String> classInfoTarget = (Collection<String>) known;
			Set<String> removed = new LinkedHashSet<>();
			for (Object item : new ArrayList<>(stateTarget)) {
				if (item != null) {
					removed.add(String.valueOf(item));
				}
			}
			removed.removeAll(newInterfaces);

			stateTarget.clear();
			stateTarget.addAll(newInterfaces);

			for (String added : newInterfaces) {
				if (classInfoTarget.contains(added)) {
					continue;
				}
				if (!invokeAddInterface(classInfo, added)) {
					return false;
				}
			}
			classInfoTarget.removeAll(removed);
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	/** 把旧 state / 旧 {@code ClassInfo} 的接口表还原到 {@code oldInterfaces}。 */
	static boolean restoreInterfaces(Object info, Set<String> oldInterfaces) {
		Object state = MixinReflection.readField(info, "state");
		Object stateInterfaces = MixinReflection.readField(state, "interfaces");
		if (!(stateInterfaces instanceof Collection<?> collection)) {
			return false;
		}
		Object classInfo = MixinReflection.readField(info, "info");
		Object classInfoInterfaces = MixinReflection.readField(classInfo, "interfaces");
		if (!(classInfoInterfaces instanceof Collection<?> known)) {
			return false;
		}
		try {
			@SuppressWarnings("unchecked")
			Collection<String> stateTarget = (Collection<String>) collection;
			@SuppressWarnings("unchecked")
			Collection<String> classInfoTarget = (Collection<String>) known;
			Set<String> removed = new LinkedHashSet<>();
			for (Object item : new ArrayList<>(stateTarget)) {
				if (item != null) {
					removed.add(String.valueOf(item));
				}
			}
			removed.removeAll(oldInterfaces);
			stateTarget.clear();
			stateTarget.addAll(oldInterfaces);
			classInfoTarget.removeAll(removed);
			for (String restored : oldInterfaces) {
				if (!classInfoTarget.contains(restored) && !invokeAddInterface(classInfo, restored)) {
					return false;
				}
			}
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static boolean invokeAddInterface(Object classInfo, String internalName) {		java.lang.reflect.Method method =
			MixinReflection.findMethod(classInfo.getClass(), "addInterface", 1);
		if (method == null) {
			return false;
		}
		try {
			MixinReflection.invoke(classInfo, method, internalName);
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static int asInt(Object value, int fallback) {
		return value instanceof Number number ? number.intValue() : fallback;
	}
}
