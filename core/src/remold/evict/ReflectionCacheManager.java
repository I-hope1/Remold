package remold.evict;

import remold.RemoldAgent;
import remold.util.LibTool;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 反射与序列化框架实例级缓存驱逐管理器（S4）。
 *
 * <p>用于在重定义完成后清除各第三方框架（Jackson、Gson 等）内部针对被重定义类的元数据缓存，
 * 使新增字段与类型变化对序列化器立即可见。</p>
 *
 * <p>本类只持有与具体游戏引擎无关的通用实现；引擎专属驱逐器（如 Arc {@code Json}）
 * 由对应扩展插件通过 {@link #registerEvictor} 自行注册，核心不持有其类名与私有字段名。</p>
 */
public final class ReflectionCacheManager {

	private static final List<ReflectionCacheEvictor> evictors = new CopyOnWriteArrayList<>();

	// 注册的特定框架实例缓存（使用弱键集合，避免内存泄漏）
	private static final Set<Object> registeredObjectMappers = Collections.newSetFromMap(new WeakHashMap<>());
	private static final Set<Object> registeredGsons = Collections.newSetFromMap(new WeakHashMap<>());

	static {
		registerEvictor(new JacksonEvictor());
		registerEvictor(new GsonEvictor());
	}

	private ReflectionCacheManager() {}

	public static void registerEvictor(ReflectionCacheEvictor evictor) {
		if (evictor != null && !evictors.contains(evictor)) {
			evictors.add(evictor);
		}
	}

	public static void unregisterEvictor(ReflectionCacheEvictor evictor) {
		if (evictor != null) {
			evictors.remove(evictor);
		}
	}

	public static void registerObjectMapper(Object mapper) {
		if (mapper != null) {
			synchronized (registeredObjectMappers) {
				registeredObjectMappers.add(mapper);
			}
		}
	}

	public static void registerGson(Object gson) {
		if (gson != null) {
			synchronized (registeredGsons) {
				registeredGsons.add(gson);
			}
		}
	}

	/**
	 * 对指定的重定义类集合执行所有已注册框架的缓存驱逐。
	 *
	 * @param redefinedClasses 重定义成功的类集合
	 * @return 总驱逐条目/实例数
	 */
	public static int evictAll(Set<Class<?>> redefinedClasses) {
		if (redefinedClasses == null || redefinedClasses.isEmpty()) {
			return 0;
		}
		int totalEvicted = 0;
		for (ReflectionCacheEvictor evictor : evictors) {
			try {
				int count = evictor.evict(redefinedClasses);
				totalEvicted += count;
				if (count > 0 && RemoldAgent.DEBUG) {
					RemoldAgent.log("[CacheEvict] " + evictor.getName() + " evicted " + count + " item(s).");
				}
			} catch (Throwable t) {
				RemoldAgent.warn("[CacheEvict] Error running evictor " + evictor.getName() + ": " + t.getMessage());
			}
		}
		return totalEvicted;
	}

	private static Field findField(Class<?> c, String name) {
		for (Class<?> curr = c; curr != null && curr != Object.class; curr = curr.getSuperclass()) {
			try {
				return curr.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {}
		}
		return null;
	}

	/**
	 * Jackson {@code ObjectMapper} 实例级缓存与 {@code TypeFactory} 全局缓存驱逐器。
	 */
	public static final class JacksonEvictor implements ReflectionCacheEvictor {
		@Override
		public String getName() { return "Jackson"; }

		@Override
		public int evict(Set<Class<?>> redefinedClasses) {
			int count = 0;
			// 1. 全局 TypeFactory 缓存清理
			try {
				Class<?> tfClass = Class.forName("com.fasterxml.jackson.databind.type.TypeFactory");
				Method mDefault = tfClass.getMethod("defaultInstance");
				Object tf = mDefault.invoke(null);
				if (tf != null) {
					Method mClear = tfClass.getMethod("clearCache");
					mClear.invoke(tf);
					count++;
				}
			} catch (Throwable ignored) {}

			// 2. 实例级 SerializerCache 与 DeserializerCache 清理
			Set<Object> instances = new HashSet<>();
			synchronized (registeredObjectMappers) {
				instances.addAll(registeredObjectMappers);
			}

			try {
				Class<?> omClass = Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
				Object[] heapInstances = LibTool.getInstances(omClass);
				if (heapInstances != null) {
					Collections.addAll(instances, heapInstances);
				}
			} catch (Throwable ignored) {}

			for (Object om : instances) {
				if (om == null) continue;
				try {
					Class<?> omc = om.getClass();
					// _serializerProvider._serializerCache
					Field fProv = findField(omc, "_serializerProvider");
					if (fProv != null) {
						fProv.setAccessible(true);
						Object prov = fProv.get(om);
						if (prov != null) {
							Field fCache = findField(prov.getClass(), "_serializerCache");
							if (fCache != null) {
								fCache.setAccessible(true);
								Object sCache = fCache.get(prov);
								if (sCache != null) {
									Method mFlush = sCache.getClass().getMethod("flush");
									mFlush.invoke(sCache);
									count++;
								}
							}
						}
					}
					// _deserializationContext._cache
					Field fCtx = findField(omc, "_deserializationContext");
					if (fCtx != null) {
						fCtx.setAccessible(true);
						Object ctx = fCtx.get(om);
						if (ctx != null) {
							Field fCache = findField(ctx.getClass(), "_cache");
							if (fCache != null) {
								fCache.setAccessible(true);
								Object dCache = fCache.get(ctx);
								if (dCache != null) {
									Method mFlush = dCache.getClass().getMethod("flush");
									mFlush.invoke(dCache);
									count++;
								}
							}
						}
					}
				} catch (Throwable t) {
					if (RemoldAgent.DEBUG) RemoldAgent.log("[JacksonEvict] Failed on instance: " + t);
				}
			}
			return count;
		}
	}

	/**
	 * Gson {@code typeTokenCache} 缓存驱逐器。
	 */
	public static final class GsonEvictor implements ReflectionCacheEvictor {
		@Override
		public String getName() { return "Gson"; }

		@Override
		public int evict(Set<Class<?>> redefinedClasses) {
			int count = 0;
			Set<Object> instances = new HashSet<>();
			synchronized (registeredGsons) {
				instances.addAll(registeredGsons);
			}

			try {
				Class<?> gsonClass = Class.forName("com.google.gson.Gson");
				Object[] heapInstances = LibTool.getInstances(gsonClass);
				if (heapInstances != null) {
					Collections.addAll(instances, heapInstances);
				}
			} catch (Throwable ignored) {}

			for (Object gson : instances) {
				if (gson == null) continue;
				try {
					Field fCache = findField(gson.getClass(), "typeTokenCache");
					if (fCache != null) {
						fCache.setAccessible(true);
						Object cache = fCache.get(gson);
						if (cache instanceof Map<?, ?> m) {
							m.clear();
							count++;
						}
					}
				} catch (Throwable t) {
					if (RemoldAgent.DEBUG) RemoldAgent.log("[GsonEvict] Failed on instance: " + t);
				}
			}
			return count;
		}
	}
}
