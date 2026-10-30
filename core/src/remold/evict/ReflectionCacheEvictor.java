package remold.evict;

import java.util.Set;

/**
 * 反射与序列化框架实例级缓存驱逐器 SPI（S4）。
 *
 * <p>在类重定义成功后被调用，用于清除第三方框架（如 Jackson、Gson、Arc Json 等）
 * 内部针对被重定义类的字段/类型元数据缓存，使新增字段与类型变化对序列化器可见。</p>
 */
public interface ReflectionCacheEvictor {

	/**
	 * 驱逐器唯一标识名（如 "ArcJson", "Jackson", "Gson"）。
	 */
	String getName();

	/**
	 * 对指定的重定义成功类集合执行缓存驱逐。
	 *
	 * @param redefinedClasses 本次重定义成功的类集合
	 * @return 实际执行驱逐的缓存实例数或条目数
	 */
	int evict(Set<Class<?>> redefinedClasses);
}
