package remold.mixin;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import remold.RemoldAgent;

/**
 * 运行期 Mixin 重织入器。
 *
 * <p>把「源码级编译产物」重新交给当前生效的 Mixin 织入器处理，得到与首次类加载等价的
 * 「生效字节码」。Remold 在类对齐之后、布局安全门（LayoutGate）与 {@code redefineClasses}
 * 之前调用本类，因此热更后的字节码仍然带有 Mixin 注入的钩子方法、{@code @Unique} 字段与
 * 合成成员，不会在重定义中被抹掉。</p>
 *
 * <p>织入器实例取自 {@link MixinEnvironment#getActiveTransformer()}：Mixin 在构造自己的
 * transformer 时会把它登记为当前环境的 active transformer。取不到实例时（未启用 Mixin、
 * 或 Mixin 与插件不在同一个 ClassLoader 中）本类退化为恒等变换，不抛异常、不改变入参。</p>
 *
 * <p>约定：目标类没有被任何 Mixin 命中时，Mixin 的 {@code transformClassBytes} 原样返回
 * 入参数组；Remold 依据数组同一性判断「未被改动」，因此非目标类不产生额外拷贝。</p>
 */
public final class MixinReWeaver {

	private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static volatile IMixinTransformer transformer;

	private MixinReWeaver() {
	}

	/**
	 * 对源码级字节码执行 Mixin 重织入。
	 *
	 * @param dotClassName   点分隔类名（如 {@code com.example.item.RemoldWandItem}）
	 * @param sourceBytecode 尚未织入的源码级字节码
	 * @return 重织入后的字节码；无 Mixin 命中或织入失败时返回入参本身
	 */
	public static byte[] reweave(String dotClassName, byte[] sourceBytecode) {
		if (dotClassName == null || sourceBytecode == null) {
			return sourceBytecode;
		}
		IMixinTransformer weaver = activeTransformer();
		if (weaver == null) {
			return sourceBytecode;
		}
		try {
			byte[] woven = weaver.transformClassBytes(dotClassName, dotClassName, sourceBytecode);
			return woven != null ? woven : sourceBytecode;
		} catch (Throwable t) {
			// 织入失败时回退源码字节码：交给 LayoutGate / JVM 拒绝，而不是带着半成品重定义。
			reportOnce(dotClassName, "Mixin 重织入失败，已回退源码字节码", t);
			return sourceBytecode;
		}
	}

	/** 解析并缓存当前生效的 Mixin 织入器；未启用 Mixin 时返回 {@code null}。 */
	static IMixinTransformer activeTransformer() {
		IMixinTransformer cached = transformer;
		if (cached != null) {
			return cached;
		}
		try {
			Object active = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
			if (!(active instanceof IMixinTransformer)) {
				active = MixinEnvironment.getDefaultEnvironment().getActiveTransformer();
			}
			if (active instanceof IMixinTransformer) {
				transformer = (IMixinTransformer) active;
			}
		} catch (Throwable t) {
			reportOnce("<resolver>", "无法获取 Mixin 织入器，本次运行不做重织入", t);
		}
		return transformer;
	}

	private static void reportOnce(String key, String message, Throwable t) {
		if (REPORTED.add(key)) {
			RemoldAgent.warn(message + ": " + key + " (" + t + ")");
		}
	}
}
