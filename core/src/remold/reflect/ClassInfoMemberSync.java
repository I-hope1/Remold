package remold.reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import remold.RemoldAgent;

/**
 * Mixin 结构元数据（{@code ClassInfo}）的成员同步引擎：把新字节码里新增的字段/方法补进
 * {@code MixinInfo} 复用的那份 {@code ClassInfo}，并提供可回滚句柄。
 *
 * <p><b>唯一实现来源</b>：Fabric（{@code plugin-mixin} {@code MixinClassInfoSync}）与
 * Forge/NeoForge（{@code plugin-forge} {@code ForgeClassInfoSync}）都只是本类的薄适配：它们各自
 * 把宿主类型擦成 {@code Object} 并保留自己的日志前缀，算法、失败语义与回滚语义全部在这里。
 * 引擎不引用 {@code org.spongepowered.*} 或 ASM 类型，因此可以在任何 ClassLoader 拓扑下工作。</p>
 *
 * <p><b>为什么必须在 reload 之前同步</b>：{@code MixinInfo.reloadMixin} 把新状态建在<b>旧</b>
 * {@code ClassInfo} 上（{@code Reloaded} 复用 {@code previous.getClassInfo()}）。新字节码里新增的
 * 字段/方法因此不在这份元数据里，而 reload 的校验阶段
 * （{@code MixinInfo.State.validate} → {@code MixinPreProcessorStandard.conform}）会拿新字节码里的
 * {@code @Inject} handler 去这份元数据里 {@code findMethod}；查不到即抛
 * {@code Conform error ...}。之后 {@code attachFields} 处理新增 {@code @Unique} 字段时同样
 * {@code findField(...) == null}，再 {@code remapTo(...)} 直接 NPE。</p>
 *
 * <p><b>类型解析必须跟随宿主</b>：{@code ClassInfo.findField(FieldNode)} 等的形参类型来自
 * Mixin 所在 ClassLoader；用调用方自己的 ASM 类型做签名匹配会直接 {@code NoSuchMethodException}。
 * 因此本类的所有成员方法/构造器都按「运行时对象的实际 Class」推导，{@code ClassInfo$Field} 也按
 * {@code ClassInfo} 自己的 ClassLoader 解析。</p>
 *
 * <p><b>失败语义</b>：任何失败（缺 {@code MixinInfo}、签名错配、半程异常）都只记日志并返回空句柄，
 * 绝不阻断调用方的 reload 主路径；半程失败<b>必须</b>回滚已补入的成员（{@link #rollback} 用同一个
 * 可变列表，因为 {@link #sync} 在第一次改写成员集合之前就把它们挂到句柄上）。</p>
 *
 * <p><b>线程</b>：Mixin 的 apply 阶段以 {@code MixinProcessor} 实例为锁；本类在同一把锁内修改
 * 成员集合，避免与正在进行的织入并发读写。</p>
 */
public final class ClassInfoMemberSync {

	/**
	 * {@code ClassInfo.INCLUDE_ALL | ClassInfo.INCLUDE_INITIALISERS}：与 {@code MixinInfo} 首次解析
	 * 成员时的查询口径一致（{@code ACC_PRIVATE | ACC_STATIC | 0x40000}）。取值来自 Mixin 0.8.x 的
	 * {@code ClassInfo} 常量定义；写成字面量是因为本类不依赖 Mixin 的编译期类型。
	 */
	private static final int INCLUDE_ALL_INITIALISERS = 0x0002 | 0x0008 | 0x40000;

	/**
	 * 命名模块宿主（NeoForge 21.1+ 的 sponge-mixin 是 JPMS 模块）缺 opens 时的补救参数。
	 *
	 * <p>sponge-mixin 的 {@code module-info} 只把 {@code org.spongepowered.asm.mixin.transformer}
	 * opens 给 {@code com.google.gson}/{@code gson}，既不 export 也不 opens 给 ALL-UNNAMED；
	 * 该包里的 {@code ClassInfo}/{@code MixinProcessor}/{@code MixinConfig#mixins} 都需要深反射。</p>
	 */
	private static final String ADD_OPENS_HINT =
		"--add-opens org.spongepowered.mixin/org.spongepowered.asm.mixin.transformer=ALL-UNNAMED";

	private ClassInfoMemberSync() {
	}

	/** 本次同步补入的成员句柄，供 reload 被拒绝时回滚。 */
	public static final class Token {
		private static final Token EMPTY = new Token(null, null, Collections.emptyList(), Collections.emptyList());

		private final Object processor;
		private final Object classInfo;
		private final List<Object> addedFields;
		private final List<Object> addedMethods;

		private Token(Object processor, Object classInfo, List<Object> addedFields, List<Object> addedMethods) {
			this.processor = processor;
			this.classInfo = classInfo;
			this.addedFields = addedFields;
			this.addedMethods = addedMethods;
		}

		/** 本次同步是否什么都没补入（含失败退化为空的句柄）。 */
		public boolean isEmpty() {
			return this.classInfo == null || (this.addedFields.isEmpty() && this.addedMethods.isEmpty());
		}

		/** 补入的字段数（用于日志/测试；不暴露成员句柄）。 */
		public int addedFieldCount() {
			return this.addedFields.size();
		}

		/** 补入的方法数（用于日志/测试；不暴露成员句柄）。 */
		public int addedMethodCount() {
			return this.addedMethods.size();
		}
	}

	/** 空句柄：调用方在「未同步」路径上可以直接返回它。 */
	public static Token emptyToken() {
		return Token.EMPTY;
	}

	/**
	 * 把 {@code classNode} 里新增的字段/方法补进该 Mixin 的 {@code ClassInfo}。
	 *
	 * <p>必须在 {@code IMixinTransformer.reload(...)} <b>之前</b>调用。</p>
	 *
	 * @param transformer    当前生效的 Mixin 织入器（跨 ClassLoader 取得的 {@code Object}）
	 * @param mixinClassName Mixin 的点分类名
	 * @param classNode      新字节码完整解析后的 {@code ClassNode}（必须由 transformer 所在
	 *                       ClassLoader 的 ASM 构造，否则形参类型对不上）
	 * @param logPrefix      调用方的日志前缀（如 {@code [Mixin]} / {@code [Forge/NeoForge]}）
	 * @return 补入成员的回滚句柄；未找到 {@code MixinInfo} 或同步失败时返回空句柄
	 */
	public static Token sync(Object transformer, String mixinClassName, Object classNode, String logPrefix) {
		String prefix = logPrefix == null ? "" : logPrefix;
		if (transformer == null || classNode == null || mixinClassName == null || mixinClassName.isEmpty()) {
			return Token.EMPTY;
		}
		Token token = Token.EMPTY;
		try {
			Object processor = readField(transformer, "processor");
			if (processor == null) {
				debug(prefix + " Mixin class-info sync: " + mixinClassName
					+ " has no MixinProcessor (transformer=" + transformer.getClass().getName() + ")");
				return Token.EMPTY;
			}
			synchronized (processor) {
				Object mixinInfo = findMixinInfo(processor, mixinClassName);
				if (mixinInfo == null) {
					debug(prefix + " Mixin class-info sync: MixinInfo not found for " + mixinClassName);
					return Token.EMPTY;
				}
				Object classInfo = invokeNoArg(mixinInfo, "getClassInfo");
				if (classInfo == null) {
					debug(prefix + " Mixin class-info sync: no ClassInfo for " + mixinClassName);
					return Token.EMPTY;
				}
				List<Object> addedFields = new ArrayList<>();
				List<Object> addedMethods = new ArrayList<>();
				// 句柄在改写成员集合之前就持有两个（可变的）列表：半程抛错时 catch 能撤销已补入项。
				token = new Token(processor, classInfo, addedFields, addedMethods);
				addMissingFields(classInfo, classNode, addedFields);
				addMissingMethods(classInfo, classNode, addedMethods);
				if (!addedFields.isEmpty() || !addedMethods.isEmpty()) {
					RemoldAgent.info(prefix + " Mixin class-info sync: " + mixinClassName
						+ " addedFields=" + addedFields.size() + ", addedMethods=" + addedMethods.size());
				} else {
					debug(prefix + " Mixin class-info sync: " + mixinClassName + " has no new members");
				}
			}
		} catch (Throwable t) {
			// 同步到一半失败时先撤销已补入项，保持元数据与被接受的旧模板一致；reload 主路径继续。
			rollback(token);
			RemoldAgent.warn(prefix + " Failed to sync Mixin class metadata for " + mixinClassName
				+ "; a newly added field/method may not hot-swap: " + describe(t));
			if (isReflectiveAccessDenied(t)) {
				RemoldAgent.warn(prefix + " Mixin class metadata is not reflectively accessible on this host"
					+ " (named module without an opens edge). Add the JVM argument " + ADD_OPENS_HINT
					+ " to the game launch (NeoForge 21.1+ ships sponge-mixin as a JPMS module).");
			}
			return Token.EMPTY;
		}
		return token;
	}

	/**
	 * 撤销 {@link #sync} 补入的成员（用于 reload 被拒绝后恢复旧元数据）。
	 *
	 * @param token {@link #sync} 的返回值，可为 {@code null}
	 */
	public static void rollback(Token token) {
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
			debug("Mixin class-info sync rolled back: fields=" + token.addedFields.size()
				+ ", methods=" + token.addedMethods.size());
		} catch (Throwable t) {
			RemoldAgent.warn("Failed to roll back Mixin class metadata after a rejected reload: " + describe(t));
		}
	}

	// ---------------------------------------------------------------------------------------------
	// internals
	// ---------------------------------------------------------------------------------------------

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
	 * 按「运行时实际类型」定位成员方法。
	 *
	 * <p>形参类型取自调用方传入对象的实际 Class，因此它会自动落在 {@code ClassInfo} 所在的那个
	 * ClassLoader 上；两侧不是同一份 ASM 时这里会抛 {@link NoSuchMethodException}，由
	 * {@link #sync} 降级处理（不打断 reload）。</p>
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

	private static void debug(String message) {
		if (RemoldAgent.DEBUG) {
			RemoldAgent.log(message);
		}
	}

	/** 判定异常链里是否有「反射访问被模块系统拒绝」的形态（含 JDK 8 的 IllegalAccessException）。 */
	private static boolean isReflectiveAccessDenied(Throwable t) {
		Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (Throwable cur = t; cur != null && seen.add(cur); cur = cur.getCause()) {
			String name = cur.getClass().getName();
			if ("java.lang.reflect.InaccessibleObjectException".equals(name)
				|| cur instanceof IllegalAccessException) {
				return true;
			}
		}
		return false;
	}

	/** 把 cause 链压成一行一条（Mixin 的真因常在 cause 里）。 */
	private static String describe(Throwable t) {
		StringBuilder sb = new StringBuilder();
		Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		int depth = 0;
		for (Throwable cur = t; cur != null && depth < 6 && seen.add(cur); cur = cur.getCause()) {
			if (depth > 0) {
				sb.append(" <- ");
			}
			sb.append(cur.getClass().getName());
			if (cur.getMessage() != null) {
				sb.append(": ").append(cur.getMessage());
			}
			depth++;
		}
		return sb.toString();
	}
}
