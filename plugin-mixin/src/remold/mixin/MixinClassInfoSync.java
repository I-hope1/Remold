package remold.mixin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * 因此新 Mixin 字节码里<b>新增的字段/方法</b>不会出现在这份元数据里。
 * 之后 {@code MixinPreProcessorStandard.attachFields} 对新增字段调用
 * {@code getClassInfo().findField(...)} 得到 {@code null}，随后
 * {@code field.remapTo(...)} 触发 NPE，整次重织入（以及目标类的热更）失败。</p>
 *
 * <p><b>做法</b>：在 {@code reload} 之后，把新字节码里多出来的成员补进同一个
 * {@code ClassInfo} 实例。只补不减：保留对象标识，不替换 Mixin 内部的 final 引用，
 * 也不重建 Mixin 配置。补入的字段/方法带 {@code injected=true}，与 Mixin 构造函数
 * 首次解析这些成员时的形态一致。</p>
 *
 * <p><b>线程</b>：Mixin 的 apply 阶段以 {@code MixinProcessor} 实例为锁；本类在
 * 同一把锁内修改成员集合，避免与正在进行的织入并发读写。</p>
 */
final class MixinClassInfoSync {

	private static final int INCLUDE_ALL_INITIALISERS =
		Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | 0x40000; // ClassInfo.INCLUDE_ALL | INCLUDE_INITIALISERS

	private MixinClassInfoSync() {
	}

	/**
	 * 把 {@code newBytes} 中新增的字段/方法补进该 Mixin 的 {@code ClassInfo}。
	 *
	 * @param transformer 当前生效的 Mixin 织入器
	 * @param mixinClassName Mixin 的点分类名
	 * @param newBytes 最新的 Mixin 源码级字节码（完整解析的 ClassNode）
	 */
	static void sync(IMixinTransformer transformer, String mixinClassName, ClassNode newBytes) {
		if (newBytes == null) {
			return;
		}
		try {
			Object processor = readField(transformer, "processor");
			if (processor == null) {
				return;
			}
			synchronized (processor) {
				Object mixinInfo = findMixinInfo(processor, mixinClassName);
				if (mixinInfo == null) {
					if (RemoldAgent.DEBUG) {
						RemoldAgent.log("[Mixin] class-info sync: MixinInfo not found for " + mixinClassName);
					}
					return;
				}
				Object classInfo = invokeNoArg(mixinInfo, "getClassInfo");
				if (classInfo == null) {
					return;
				}
				int addedFields = addMissingFields(classInfo, newBytes);
				int addedMethods = addMissingMethods(classInfo, newBytes);
				if (RemoldAgent.DEBUG) {
					RemoldAgent.log("[Mixin] class-info sync: " + mixinClassName
						+ " addedFields=" + addedFields + ", addedMethods=" + addedMethods);
				}
			}
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] Failed to sync Mixin class metadata for " + mixinClassName
				+ "; a newly added field/method may not hot-swap: " + t);
		}
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

	private static int addMissingFields(Object classInfo, ClassNode node) throws Exception {
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
				added++;
			}
		}
		return added;
	}

	private static int addMissingMethods(Object classInfo, ClassNode node) throws Exception {
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
			added++;
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
