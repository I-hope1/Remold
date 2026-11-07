package remold.mixin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import remold.RemoldAgent;

/**
 * Mixin 热重载的结构元数据同步。
 *
 * <p><b>为什么需要</b>：{@code MixinInfo.reloadMixin} 会把新状态建在
 * <b>旧</b> {@code ClassInfo} 上（{@code Reloaded} 复用 {@code previous.getClassInfo()}）。
 * 因此新 Mixin 字节码里<b>新增的字段/方法</b>不会出现在这份元数据里，而
 * {@code MixinInfo.State.validate} 会拿新字节码逐个方法到这份元数据里
 * {@code findMethod} 做注入器一致性检查（{@code MixinPreProcessorStandard.conform}）。
 * 新增或改名的 {@code @Inject} handler 方法查不到时，reload 直接抛
 * {@code Conform error ...}，把一次本可热更的改动降级成「请重启客户端」。
 * 之后 {@code attachFields} 对新增字段同样会 {@code findField(...) == null}，
 * 随后 {@code field.remapTo(...)} 触发 NPE。</p>
 *
 * <p><b>做法</b>：把新字节码里多出来的成员补进同一个 {@code ClassInfo} 实例，
 * 并且必须在 {@code reload} <b>之前</b>完成，因为 reload 自身的校验阶段就会查这份元数据。
 * 只补不减：保留对象标识，不替换 Mixin 内部的 final 引用，也不重建 Mixin 配置。
 * 补入的字段/方法带 {@code injected=true}，与 Mixin 构造函数首次解析这些成员时的形态一致。</p>
 *
 * <p><b>失败回滚</b>：{@link #sync} 返回本次补入的成员句柄；reload 被拒绝（例如目标类/
 * 优先级被改）时调用 {@link #rollback} 把补入项摘掉，避免一份未被接受的模板污染
 * {@code ClassInfo}，影响后续织入。</p>
 *
 * <p><b>线程</b>：Mixin 的 apply 阶段以 {@code MixinProcessor} 实例为锁；本类在
 * 同一把锁内修改成员集合，避免与正在进行的织入并发读写。</p>
 */
final class MixinClassInfoSync {

	private static final int INCLUDE_ALL_INITIALISERS =
		Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | 0x40000; // ClassInfo.INCLUDE_ALL | INCLUDE_INITIALISERS

	private MixinClassInfoSync() {
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
	 * 把 {@code newBytes} 中新增的字段/方法补进该 Mixin 的 {@code ClassInfo}。
	 *
	 * <p>必须在 {@code transformer.reload(...)} <b>之前</b>调用：reload 的校验阶段会拿新
	 * 字节码里的注入器方法到这份 {@code ClassInfo} 里查 Method 句柄，查不到即判定结构不合格。</p>
	 *
	 * @param transformer 当前生效的 Mixin 织入器
	 * @param mixinClassName Mixin 的点分类名
	 * @param newBytes 最新的 Mixin 源码级字节码（完整解析的 ClassNode）
	 * @return 补入成员的回滚句柄；未找到 MixinInfo 或同步失败时返回空句柄
	 */
	static SyncToken sync(IMixinTransformer transformer, String mixinClassName, ClassNode newBytes) {
		if (newBytes == null) {
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
						RemoldAgent.log("[Mixin] class-info sync: MixinInfo not found for " + mixinClassName);
					}
					return SyncToken.EMPTY;
				}
				Object classInfo = invokeNoArg(mixinInfo, "getClassInfo");
				if (classInfo == null) {
					return SyncToken.EMPTY;
				}
				List<Object> addedFields = new ArrayList<>();
				List<Object> addedMethods = new ArrayList<>();
				addMissingFields(classInfo, newBytes, addedFields);
				addMissingMethods(classInfo, newBytes, addedMethods);
				token = new SyncToken(processor, classInfo, addedFields, addedMethods);
				if (RemoldAgent.DEBUG) {
					RemoldAgent.log("[Mixin] class-info sync: " + mixinClassName
						+ " addedFields=" + addedFields.size() + ", addedMethods=" + addedMethods.size());
				}
			}
		} catch (Throwable t) {
			// 同步到一半失败时先撤销已补入项，保持元数据与被接受的旧模板一致。
			rollback(token);
			RemoldAgent.warn("[Mixin] Failed to sync Mixin class metadata for " + mixinClassName
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
				RemoldAgent.log("[Mixin] class-info sync rolled back: fields=" + token.addedFields.size()
					+ ", methods=" + token.addedMethods.size());
			}
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] Failed to roll back Mixin class metadata after a rejected reload: " + t);
		}
	}

	private static boolean removeFromSet(Object classInfo, String setName, Object member) throws Exception {
		Object value = readField(classInfo, setName);
		if (value instanceof Set) {
			return ((Set<?>) value).remove(member);
		}
		return false;
	}

	private static Object findMixinInfo(Object processor, String mixinClassName) throws Exception {
		Object configs = readField(processor, "configs");
		if (!(configs instanceof List)) {
			return null;
		}
		for (Object config : (List<?>) configs) {
			Object mixins = readField(config, "mixins");
			if (!(mixins instanceof List)) {
				continue;
			}
			for (Object mixin : (List<?>) mixins) {
				Object name = invokeNoArg(mixin, "getClassName");
				if (name != null && name.equals(mixinClassName)) {
					return mixin;
				}
			}
		}
		return null;
	}

	private static int addMissingFields(Object classInfo, ClassNode node, List<Object> addedOut) throws Exception {
		Class<?> classInfoType = classInfo.getClass();
		Method findField = classInfoType.getMethod("findField", FieldNode.class);
		Constructor<?> fieldConstructor = Class.forName(classInfoType.getName() + "$Field",
			false, classInfoType.getClassLoader())
			.getDeclaredConstructor(classInfoType, FieldNode.class, boolean.class);
		fieldConstructor.setAccessible(true);
		Object fields = readField(classInfo, "fields");
		if (!(fields instanceof Set)) {
			return 0;
		}
		Set<Object> fieldSet = castSet(fields);
		int added = 0;
		for (FieldNode fieldNode : node.fields) {
			if (findField.invoke(classInfo, fieldNode) != null) {
				continue;
			}
			Object field = fieldConstructor.newInstance(classInfo, fieldNode, true);
			if (fieldSet.add(field)) {
				addedOut.add(field);
				added++;
			}
		}
		return added;
	}

	private static int addMissingMethods(Object classInfo, ClassNode node, List<Object> addedOut) throws Exception {
		Class<?> classInfoType = classInfo.getClass();
		Method findMethod = classInfoType.getMethod("findMethod", MethodNode.class, int.class);
		Method addMethod = classInfoType.getDeclaredMethod("addMethod", MethodNode.class);
		addMethod.setAccessible(true);
		int added = 0;
		for (MethodNode methodNode : node.methods) {
			if (findMethod.invoke(classInfo, methodNode, INCLUDE_ALL_INITIALISERS) != null) {
				continue;
			}
			addMethod.invoke(classInfo, methodNode);
			// addMethod 在 ClassInfo 内部构造 Method 句柄；再查一次取回该句柄，供失败回滚移除。
			Object member = findMethod.invoke(classInfo, methodNode, INCLUDE_ALL_INITIALISERS);
			if (member != null) {
				addedOut.add(member);
				added++;
			}
		}
		return added;
	}

	@SuppressWarnings("unchecked")
	private static Set<Object> castSet(Object value) {
		return (Set<Object>) value;
	}

	private static Object readField(Object target, String name) throws Exception {
		Field field = findField(target.getClass(), name);
		return field.get(target);
	}

	private static Object invokeNoArg(Object target, String name) throws Exception {
		Method method = findMethod(target.getClass(), name);
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
			}
		}
		throw new NoSuchFieldException(name);
	}

	private static Method findMethod(Class<?> type, String name) {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				Method method = current.getDeclaredMethod(name);
				method.setAccessible(true);
				return method;
			} catch (NoSuchMethodException ignored) {
			}
		}
		return null;
	}
}
