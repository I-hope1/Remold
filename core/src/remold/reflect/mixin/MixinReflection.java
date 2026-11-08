package remold.reflect.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 反射访问 Mixin 内部结构的公共工具。Mixin 由宿主加载器提供，Remold 不在编译期依赖
 * {@code org.spongepowered.*}，所有访问都必须经过这里，避免把宿主类型泄漏到 core。
 */
final class MixinReflection {

	private MixinReflection() {
	}

	static Object readField(Object target, String name) {
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

	static boolean writeBoolean(Object target, String name, boolean value) {
		if (target == null) {
			return false;
		}
		Field field = findField(target.getClass(), name);
		if (field == null) {
			return false;
		}
		try {
			field.setAccessible(true);
			field.setBoolean(target, value);
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	static Object invoke(Object target, String name) {
		return invoke(target, name, 0);
	}

	static Object invoke(Object target, String name, int parameterCount) {
		if (target == null) {
			return null;
		}
		Method method = findMethod(target.getClass(), name, parameterCount);
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

	static Object invoke(Object target, Method method, Object... args) throws Exception {
		method.setAccessible(true);
		return method.invoke(target, args);
	}

	static Field findField(Class<?> clazz, String name) {
		for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Field field = c.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
			}
		}
		return null;
	}

	static Method findMethod(Class<?> clazz, String name) {
		return findMethod(clazz, name, 0);
	}

	static Method findMethod(Class<?> clazz, String name, int parameterCount) {
		for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Method method : c.getDeclaredMethods()) {
				if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
					method.setAccessible(true);
					return method;
				}
			}
		}
		return null;
	}

	static String describe(Throwable t) {
		StringBuilder sb = new StringBuilder();
		java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		Throwable current = t;
		int depth = 0;
		while (current != null && depth < 8 && seen.add(current)) {
			sb.append(depth == 0 ? "" : "\n  Caused by: ")
				.append(current.getClass().getName());
			if (current.getMessage() != null) {
				sb.append(": ").append(current.getMessage());
			}
			current = current.getCause();
			depth++;
		}
		return sb.toString();
	}
}
