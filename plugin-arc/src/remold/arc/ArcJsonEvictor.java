package remold.arc;

import arc.struct.ObjectMap;
import arc.util.serialization.Json;
import remold.RemoldAgent;
import remold.evict.ReflectionCacheEvictor;
import remold.util.LibTool;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Arc {@link Json} 实例级字段元数据缓存驱逐器（S4，Arc 适配层）。
 *
 * <p>重定义成功后清除 {@code Json} 实例内针对被重定义类的字段元数据缓存，
 * 使新增字段对序列化器立即可见。</p>
 *
 * <p>本类属于 {@code :plugin-arc}：核心引擎只持有 {@link ReflectionCacheEvictor} SPI，
 * 不持有任何 Arc 类型、类名或私有字段名知识。类与字段均以 Arc 编译期类型引用，
 * 仅"私有字段名"仍是字符串契约，因此上游改字段名时在 {@link #findField} 处显式告警而非静默失效。</p>
 */
public final class ArcJsonEvictor implements ReflectionCacheEvictor {

	/** 单例：重复触发 {@code onAgentInit} 不会产生重复注册。 */
	public static final ArcJsonEvictor INSTANCE = new ArcJsonEvictor();

	/** 显式注册的实例（弱键集合，避免内存泄漏）。 */
	private static final Set<Json> registered = Collections.newSetFromMap(new WeakHashMap<>());

	private static final Field F_TYPE_TO_FIELDS = findField("typeToFields");
	private static final Field F_DEFAULT_VALUES = findField("classToDefaultValues");
	private static final Field F_SERIALIZERS = findField("classToSerializer");

	private ArcJsonEvictor() {}

	/** 显式注册通道：Native 堆遍历不可用时的确定性来源。 */
	public static void register(Json json) {
		if (json == null) return;
		synchronized (registered) {
			registered.add(json);
		}
	}

	public static void unregister(Json json) {
		if (json == null) return;
		synchronized (registered) {
			registered.remove(json);
		}
	}

	@Override
	public String getName() {
		return "ArcJson";
	}

	@Override
	public int evict(Set<Class<?>> redefinedClasses) {
		if (redefinedClasses == null || redefinedClasses.isEmpty()) return 0;
		// 三个字段全部不可读（Arc 版本不匹配）时直接放弃，避免每轮重定义都做一次无意义的全堆扫描。
		if (F_TYPE_TO_FIELDS == null && F_DEFAULT_VALUES == null && F_SERIALIZERS == null) return 0;

		Set<Json> instances = new HashSet<>();
		synchronized (registered) {
			instances.addAll(registered);
		}
		try {
			Object[] heapInstances = LibTool.getInstances(Json.class);
			if (heapInstances != null) {
				for (Object o : heapInstances) {
					if (o instanceof Json) instances.add((Json) o);
				}
			}
		} catch (Throwable ignored) {
			// Native 探针不可用：退回显式注册通道
		}

		int count = 0;
		for (Json json : instances) {
			if (json == null) continue;
			try {
				count += removeEntries(F_TYPE_TO_FIELDS, json, redefinedClasses);
				count += removeEntries(F_DEFAULT_VALUES, json, redefinedClasses);
				count += removeEntries(F_SERIALIZERS, json, redefinedClasses);
			} catch (Throwable t) {
				if (RemoldAgent.DEBUG) RemoldAgent.log("[ArcJsonEvict] Failed on instance: " + t);
			}
		}
		return count;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static int removeEntries(Field field, Json json, Set<Class<?>> classes) throws IllegalAccessException {
		if (field == null) return 0;
		Object value = field.get(json);
		if (!(value instanceof ObjectMap)) return 0;
		ObjectMap map = (ObjectMap) value;
		int removed = 0;
		for (Class<?> c : classes) {
			// classToDefaultValues 允许存放 null 值，不能用 remove 的返回值判定键是否存在
			if (map.containsKey(c)) {
				map.remove(c);
				removed++;
			}
		}
		return removed;
	}

	private static Field findField(String name) {
		try {
			Field f = Json.class.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (Throwable t) {
			RemoldAgent.warn("[ArcJsonEvict] Arc Json." + name + " 不可访问，该缓存通道失效: " + t);
			return null;
		}
	}
}
