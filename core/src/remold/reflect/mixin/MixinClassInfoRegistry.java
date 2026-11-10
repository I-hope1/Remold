package remold.reflect.mixin;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import remold.RemoldAgent;

/**
 * {@code ClassInfo.cache} 的「已注册 Mixin 条目」修复器（纯反射，无 Mixin 编译期依赖）。
 *
 * <p><b>为什么需要</b>：上游 {@code ClassInfo.forName(name)} 只查缓存，缓存未命中时用
 * {@code getBytecodeProvider().getClassNode(...)} 读出一个<b>普通</b> {@code ClassNode} 构造
 * {@code ClassInfo}——而 {@code ClassInfo.isMixin} 的判据是
 * {@code classNode instanceof MixinClassNode}，于是懒加载出来的 Mixin 元数据恒为
 * {@code isMixin == false}。{@code MixinTargetContext.transformSingleDescriptor} 正是按这个标志
 * 决定是否把描述符里的 Mixin 类型重映射成目标类型：</p>
 *
 * <pre>
 * ClassInfo typeInfo = ClassInfo.forName(type);
 * if (!typeInfo.isMixin() || typeInfo.isLoadable()) return desc;   // ← 漏点：原样保留 LHerrscherEntityMixin;
 * </pre>
 *
 * <p>Remold 的运行期登记/重载会驱逐 {@code ClassInfo.cache} 里 Mixin 的条目（为了读到新字节），
 * 若之后没有把 {@code MixinInfo.getClassInfo()}（由 {@code MixinClassNode} 构造，{@code isMixin == true}）
 * 放回缓存，下一次织入就会拿到普通 {@code ClassInfo}，匿名内部类 {@code this$0} 的构造器描述符便停留在
 * Mixin 类上——运行期抛 {@code NoSuchMethodError: <init>(SomeMixin, Target)}。</p>
 *
 * <p><b>做法</b>：遍历所有已注册 {@code MixinConfig.mixins}，把每个 {@code MixinInfo.getClassInfo()}
 * 按内部名写回 {@code ClassInfo.cache}，保证「缓存条目 ≡ MixinInfo.getClassInfo()」且
 * {@code isMixin == true}。幂等、O(已注册 Mixin 数)，在每次登记/重载成功后调用。</p>
 */
public final class MixinClassInfoRegistry {

	private MixinClassInfoRegistry() {
	}

	/**
	 * 把所有已注册 Mixin 的 {@code ClassInfo} 重新锚定到 {@code ClassInfo.cache}。
	 *
	 * @param transformer 当前生效的 Mixin 织入器（可为 {@code null}，此时返回 0）
	 * @return 实际写入/校正的条目数
	 */
	public static int reassert(Object transformer) {
		return reassert(transformer, null);
	}

	/**
	 * 同上，但允许注入缓存表（单测用：真实 {@code ClassInfo} 的静态初始化依赖 MixinService，
	 * 普通 JUnit 里不可用）。
	 *
	 * @param overrideCache 非空时用它替代 {@code ClassInfo.cache}
	 */
	static int reassert(Object transformer, Map<Object, Object> overrideCache) {
		if (transformer == null) {
			return 0;
		}
		try {
			Object processor = MixinReflection.readField(transformer, "processor");
			if (processor == null) {
				return 0;
			}
			Object configsObject = MixinReflection.readField(processor, "configs");
			if (!(configsObject instanceof List)) {
				return 0;
			}
			Map<?, ?> cache = overrideCache != null ? overrideCache : classInfoCache(processor);
			if (cache == null) {
				return 0;
			}
			int corrected = 0;
			synchronized (cache) {
				@SuppressWarnings("unchecked")
				Map<Object, Object> raw = (Map<Object, Object>) cache;
				for (Object config : (List<?>) configsObject) {
					Object mixinsObject = MixinReflection.readField(config, "mixins");
					if (!(mixinsObject instanceof List)) {
						continue;
					}
					for (Object info : (List<?>) mixinsObject) {
						Object className = MixinReflection.invoke(info, "getClassName");
						Object classInfo = MixinReflection.invoke(info, "getClassInfo");
						if (!(className instanceof String name) || classInfo == null) {
							continue;
						}
						String internal = name.replace('.', '/');
						if (raw.get(internal) == classInfo) {
							continue;
						}
						raw.put(internal, classInfo);
						corrected++;
						Object isMixin = MixinReflection.readField(classInfo, "isMixin");
						if (Boolean.FALSE.equals(isMixin)) {
							// 极端防御：MixinInfo.getClassInfo() 本应来自 MixinClassNode。真出现 false 时
							// 说明上游结构变了，必须点名，不能静默继续（描述符重映射会再次被跳过）。
							RemoldAgent.warn("[Mixin] ClassInfo for registered mixin " + name
								+ " reports isMixin=false; Mixin->Target descriptor remapping may be skipped."
								+ " This Mixin version is not supported for structural hot-swap.");
						}
					}
				}
			}
			return corrected;
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] Failed to re-anchor registered mixin ClassInfo entries: "
				+ MixinReflection.describe(t));
			return 0;
		}
	}

	/** 取上游 {@code ClassInfo.cache}（静态字段，0.8.5 / 0.8.7 同名）。 */
	private static Map<?, ?> classInfoCache(Object processor) {
		try {
			ClassLoader loader = processor.getClass().getClassLoader();
			Class<?> classInfo = Class.forName("org.spongepowered.asm.mixin.transformer.ClassInfo", true, loader);
			Field field = MixinReflection.findField(classInfo, "cache");
			if (field == null) {
				return null;
			}
			Object value = field.get(null);
			return value instanceof Map<?, ?> map ? map : null;
		} catch (Throwable t) {
			return null;
		}
	}
}
