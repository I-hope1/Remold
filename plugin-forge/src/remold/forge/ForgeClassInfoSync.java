package remold.forge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import remold.RemoldAgent;

/**
 * Mixin 结构元数据（{@code ClassInfo}）的 Forge / NeoForge 侧成员同步。
 *
 * <p><b>为什么需要</b>：{@code MixinInfo.reloadMixin} 把新状态建在<b>旧</b> {@code ClassInfo}
 * 上（{@code Reloaded} 复用 {@code previous.getClassInfo()}）。新字节码里<b>新增的字段/方法</b>
 * 因此不在这份元数据里，而 reload 的校验阶段（{@code MixinInfo.State.validate} →
 * {@code MixinPreProcessorStandard.conform}）会拿新字节码里的 {@code @Inject} handler 到这份元数据
 * 里 {@code findMethod}；查不到即抛 {@code Conform error ...}，把一次本可热更的改动降级成
 * 「请重启客户端」。随后 {@code attachFields} 处理新增 {@code @Unique} 字段时同样
 * {@code findField(...) == null}，再 {@code remapTo(...)} 直接 NPE。</p>
 *
 * <p><b>做法</b>：把新字节码里多出来的成员补进同一个 {@code ClassInfo} 实例，
 * 并且必须在 {@code IMixinTransformer.reload} <b>之前</b>完成——reload 自身的校验阶段就会查这份
 * 元数据。只补不减：保留对象标识，不替换 Mixin 内部的 final 引用，也不重建 Mixin 配置；补入成员
 * 与 Mixin 构造期首次解析它们时的形态一致（字段 {@code injected=true}，方法经
 * {@code ClassInfo.addMethod}）。</p>
 *
 * <p><b>为什么不能直接复用 {@code plugin-mixin} 的同名实现</b>：Fabric 侧那份以
 * {@code IMixinTransformer} / ASM {@code ClassNode} 为编译期类型；而在 Forge / NeoForge 里
 * Mixin 与 ASM 都由 ModLauncher 的独立 ClassLoader 加载，插件侧只能拿到 {@code Object}。
 * 更关键的是<b>类型签名必须按宿主那一份 ClassLoader 去解析</b>：{@code ClassInfo.findField(FieldNode)}
 * 的形参类型是那个 ClassLoader 里的 {@code FieldNode}，用 Agent 侧同名类型做 {@code getMethod}
 * 会直接 {@code NoSuchMethodException}。因此本类的全部成员查询都按「运行时对象的实际类型」推导航，
 * 类名到 {@code ClassInfo$Field} 的查找也走 {@code ClassInfo} 自己的 ClassLoader。</p>
 *
 * <p><b>失败回滚</b>：{@link #sync} 返回本次补入的成员句柄；reload 被拒绝（目标类/接口/优先级被改，
 * 或者校验仍然不过）时调用 {@link #rollback} 摘掉本次补入项，避免一份未被接受的模板污染
 * {@code ClassInfo}、影响后续织入。半程失败（补了字段、方法阶段抛错）同样回滚。</p>
 *
 * <p><b>线程</b>：Mixin 的 apply 阶段以 {@code MixinProcessor} 实例为锁；本类在同一把锁内修改成员
 * 集合，避免与正在进行的织入并发读写。</p>
 *
 * <p><b>退化</b>：取不到 processor / 找不到对应 {@code MixinInfo} / 反射签名对不上时只记一条
 * {@code warn}（或 DEBUG 日志）并返回空句柄，<b>不打断</b> reload 主路径——热更失败应当由 Mixin
 * 自己拒绝并提示重启，而不是被元数据同步这一步拦住。</p>
 */
final class ForgeClassInfoSync {

	/**
	 * {@code ClassInfo.INCLUDE_ALL | ClassInfo.INCLUDE_INITIALISERS}：与 {@code MixinInfo}
	 * 首次解析成员时的查询口径一致（{@code ACC_PRIVATE | ACC_STATIC | 0x40000}）。
	 *
	 * <p>写成字面量而不是读 {@code ClassInfo} 的静态常量，是因为本类不依赖 Mixin 的编译期类型；
	 * 这三个取值来自 Mixin 0.8.x 的 {@code ClassInfo} 常量定义。</p>
	 */
	private static final int INCLUDE_ALL_INITIALISERS = 0x0002 | 0x0008 | 0x40000;

	private ForgeClassInfoSync() {
	}

	/** 本次同步补入的成员句柄，供 reload 失败时回滚。 */
	static final class SyncToken {
		static final SyncToken EMPTY = new SyncToken(null, null, Collections.emptyList(), Collections.emptyList());

		private final Object processor;
		private final Object classInfo;
		private final List<Object> addedFields;
		private final List<Object> addedMethods;

		private SyncToken(Object processor, Object classInfo, List<Object> addedFields, List<Object> addedMethods) {
			this.processor = processor;
			this.classInfo = classInfo;
			this.addedFields = addedFields;
			this.addedMethods = addedMethods;
		}

		boolean isEmpty() {
			return this.classInfo == null || (this.addedFields.isEmpty() && this.addedMethods.isEmpty());
		}
	}

	/**
	 * 把 {@code classNode} 里新增的字段/方法补进该 Mixin 的 {@code ClassInfo}。
	 *
	 * <p>必须在 {@code IMixinTransformer.reload(...)} <b>之前</b>调用：reload 的校验阶段会拿新
	 * 字节码里的注入器方法到这份 {@code ClassInfo} 里查 Method 句柄，查不到即判定结构不合格。</p>
	 *
	 * @param transformer   当前生效的 Mixin 织入器（跨 ClassLoader 取得的 {@code Object}）
	 * @param mixinClassName Mixin 的点分类名
	 * @param classNode     新字节码完整解析后的 {@code ClassNode}（必须由 transformer 所在
	 *                      ClassLoader 的 ASM 构造，否则形参类型对不上）
	 * @return 补入成员的回滚句柄；未找到 {@code MixinInfo} 或同步失败时返回空句柄
	 */
	static SyncToken sync(Object transformer, String mixinClassName, Object classNode) {
		if (transformer == null || classNode == null || mixinClassName == null || mixinClassName.isEmpty()) {
			return SyncToken.EMPTY;
		}
		SyncToken token = SyncToken.EMPTY;
		try {
			Object processor = readField(transformer, "processor");
			if (processor == null) {
				return SyncToken.EMPTY;
			}
			synchronized (processor) {
				Object mixinInfo = findMixinInfo(processor, mixinClassName);
				if (mixinInfo == null) {
					if (RemoldAgent.DEBUG) {
						RemoldAgent.log("[Forge/NeoForge] Mixin class-info sync: MixinInfo not found for "
							+ mixinClassName);
					}
					return SyncToken.EMPTY;
				}
				Object classInfo = invokeNoArg(mixinInfo, "getClassInfo");
				if (classInfo == null) {
					return SyncToken.EMPTY;
				}
				List<Object> addedFields = new ArrayList<>();
				List<Object> addedMethods = new ArrayList<>();
				// token 在改写成员集合之前就持有两个（可变的）列表：半程抛错时 catch 能撤销已补入项。
				token = new SyncToken(processor, classInfo, addedFields, addedMethods);
				addMissingFields(classInfo, classNode, addedFields);
				addMissingMethods(classInfo, classNode, addedMethods);
				if (!addedFields.isEmpty() || !addedMethods.isEmpty()) {
					RemoldAgent.info("[Forge/NeoForge] Mixin class-info sync: " + mixinClassName
						+ " addedFields=" + addedFields.size() + ", addedMethods=" + addedMethods.size());
				} else if (RemoldAgent.DEBUG) {
					RemoldAgent.log("[Forge/NeoForge] Mixin class-info sync: " + mixinClassName
						+ " has no new members");
				}
			}
		} catch (Throwable t) {
			// 同步到一半失败时先撤销已补入项，保持元数据与被接受的旧模板一致；reload 主路径继续。
			rollback(token);
			RemoldAgent.warn("[Forge/NeoForge] Failed to sync Mixin class metadata for " + mixinClassName
				+ "; a newly added field/method may not hot-swap: " + t);
			return SyncToken.EMPTY;
		}
		return token;
	}

	/**
	 * 撤销 {@link #sync} 补入的成员（仅用于 reload 被拒绝后恢复旧元数据）。
	 *
	 * @param token {@link #sync} 的返回值，可为 {@code null}
	 */
	static void rollback(SyncToken token) {
		if (token == null || token.isEmpty()) {
			return;
		}
		try {
			Object classInfo = token.classInfo;
			synchronized (token.processor == null ? token : token.processor) {
				for (Object field : token.addedFields) {
					removeFromSet(classInfo, "fields", field);
				}
				for (Object method : token.addedMethods) {
					if (!removeFromSet(classInfo, "methods", method)) {
						removeFromSet(classInfo, "initialisers", method);
					}
				}
			}
			if (RemoldAgent.DEBUG) {
				RemoldAgent.log("[Forge/NeoForge] Mixin class-info sync rolled back: fields="
					+ token.addedFields.size() + ", methods=" + token.addedMethods.size());
			}
		} catch (Throwable t) {
			RemoldAgent.warn("[Forge/NeoForge] Failed to roll back Mixin class metadata after a rejected reload: " + t);
		}
	}

	/** 按点分类名在 processor 的 Mixin 配置里定位 {@code MixinInfo}（内部名与点分名都认）。 */
	private static Object findMixinInfo(Object processor, String mixinClassName) throws Exception {
		Object configs = readField(processor, "configs");
		if (!(configs instanceof List)) {
			return null;
		}
		String wanted = mixinClassName.replace('/', '.');
		for (Object config : (List<?>) configs) {
			Object mixins = readField(config, "mixins");
			if (!(mixins instanceof List)) {
				continue;
			}
			for (Object mixin : (List<?>) mixins) {
				Object name = invokeNoArg(mixin, "getClassName");
				if (name instanceof String && wanted.equals(((String) name).replace('/', '.'))) {
					return mixin;
				}
			}
		}
		return null;
	}

	/** 把 {@code classNode} 里新增的字段补进 {@code ClassInfo.fields}，成员句柄写入 {@code addedOut}。 */
	private static void addMissingFields(Object classInfo, Object classNode, List<Object> addedOut) throws Exception {
		List<?> nodes = members(classNode, "fields");
		if (nodes.isEmpty()) {
			return;
		}
		Object fieldsValue = readField(classInfo, "fields");
		if (!(fieldsValue instanceof Set)) {
			return;
		}
		Set<Object> fields = castSet(fieldsValue);
		Class<?> classInfoType = classInfo.getClass();
		Class<?> fieldNodeType = nodes.get(0).getClass();
		Method findField = memberMethod(classInfoType, "findField", fieldNodeType);
		Constructor<?> fieldConstructor = memberConstructor(classInfoType, fieldNodeType);
		for (Object fieldNode : nodes) {
			if (findField.invoke(classInfo, fieldNode) != null) {
				continue;
			}
			Object field = fieldConstructor.newInstance(classInfo, fieldNode, Boolean.TRUE);
			if (fields.add(field)) {
				addedOut.add(field);
			}
		}
	}

	/** 把 {@code classNode} 里新增的方法（含初始化器）经 {@code ClassInfo.addMethod} 补进去。 */
	private static void addMissingMethods(Object classInfo, Object classNode, List<Object> addedOut) throws Exception {
		List<?> nodes = members(classNode, "methods");
		if (nodes.isEmpty()) {
			return;
		}
		Class<?> classInfoType = classInfo.getClass();
		Class<?> methodNodeType = nodes.get(0).getClass();
		Method findMethod = memberMethod(classInfoType, "findMethod", methodNodeType, int.class);
		Method addMethod = memberMethod(classInfoType, "addMethod", methodNodeType);
		for (Object methodNode : nodes) {
			if (findMethod.invoke(classInfo, methodNode, INCLUDE_ALL_INITIALISERS) != null) {
				continue;
			}
			addMethod.invoke(classInfo, methodNode);
			// addMethod 在 ClassInfo 内部构造 Method 句柄；再查一次取回该句柄，供失败回滚移除。
			Object member = findMethod.invoke(classInfo, methodNode, INCLUDE_ALL_INITIALISERS);
			if (member != null) {
				addedOut.add(member);
			}
		}
	}

	/** 读取 {@code ClassNode} 的成员列表（{@code fields} / {@code methods}）。 */
	private static List<?> members(Object classNode, String name) throws Exception {
		Object value = readField(classNode, name);
		return value instanceof List ? (List<?>) value : Collections.emptyList();
	}

	private static boolean removeFromSet(Object classInfo, String setName, Object member) throws Exception {
		Object value = readField(classInfo, setName);
		if (value instanceof Set) {
			return ((Set<?>) value).remove(member);
		}
		return false;
	}

	/**
	 * 按「运行时实际类型」定位单形参成员方法。
	 *
	 * <p>形参类型取自调用方传入对象的实际 Class，因此它会自动落在 {@code ClassInfo} 所在的那个
	 * ClassLoader 上；两侧不是同一份 ASM 时这里会抛 {@link NoSuchMethodException}，由 {@link #sync}
	 * 降级处理（不打断 reload）。</p>
	 */
	private static Method memberMethod(Class<?> owner, String name, Class<?> firstParamType) throws NoSuchMethodException {
		return memberMethod(owner, name, firstParamType, null);
	}

	private static Method memberMethod(Class<?> owner, String name, Class<?> firstParamType, Class<?> secondParamType)
		throws NoSuchMethodException {
		for (Class<?> current = owner; current != null && current != Object.class; current = current.getSuperclass()) {
			for (Method method : current.getDeclaredMethods()) {
				if (!name.equals(method.getName())) {
					continue;
				}
				Class<?>[] params = method.getParameterTypes();
				if (params.length != (secondParamType == null ? 1 : 2)) {
					continue;
				}
				if (!params[0].isAssignableFrom(firstParamType)) {
					continue;
				}
				if (secondParamType != null && params[1] != secondParamType) {
					continue;
				}
				method.setAccessible(true);
				return method;
			}
		}
		throw new NoSuchMethodException(owner.getName() + "." + name + "(...)");
	}

	/**
	 * 定位 {@code ClassInfo$Field} 的 {@code (ClassInfo, FieldNode, boolean)} 构造器。
	 *
	 * <p>用 {@code ClassInfo} 自己的 ClassLoader 解析嵌套类名：Forge / NeoForge 下 Mixin 位于
	 * ModLauncher 的独立 ClassLoader，Agent 侧的 {@code Class.forName} 看不到它。</p>
	 */
	private static Constructor<?> memberConstructor(Class<?> classInfoType, Class<?> fieldNodeType) throws Exception {
		Class<?> fieldType = Class.forName(classInfoType.getName() + "$Field", true,
			classInfoType.getClassLoader());
		for (Constructor<?> constructor : fieldType.getDeclaredConstructors()) {
			Class<?>[] params = constructor.getParameterTypes();
			if (params.length != 3) {
				continue;
			}
			if (params[0] != classInfoType || !params[1].isAssignableFrom(fieldNodeType) || params[2] != boolean.class) {
				continue;
			}
			constructor.setAccessible(true);
			return constructor;
		}
		throw new NoSuchMethodException(fieldType.getName() + "(ClassInfo, FieldNode, boolean)");
	}

	@SuppressWarnings("unchecked")
	private static Set<Object> castSet(Object value) {
		return (Set<Object>) value;
	}

	private static Object readField(Object target, String name) throws Exception {
		return findField(target.getClass(), name).get(target);
	}

	private static Object invokeNoArg(Object target, String name) throws Exception {
		Method method = findNoArgMethod(target.getClass(), name);
		if (method == null) {
			return null;
		}
		return method.invoke(target);
	}

	private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
				// 继续向父类找：Mixin 的部分字段声明在父类型里。
			}
		}
		throw new NoSuchFieldException(name);
	}

	private static Method findNoArgMethod(Class<?> type, String name) {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				Method method = current.getDeclaredMethod(name);
				method.setAccessible(true);
				return method;
			} catch (NoSuchMethodException ignored) {
				// 继续向父类找。
			}
		}
		return null;
	}
}
