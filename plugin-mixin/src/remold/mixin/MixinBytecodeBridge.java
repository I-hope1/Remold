package remold.mixin;

import remold.BytecodeTransformerBridge;

/**
 * 注册到 Remold 桥链上的 Mixin 重织入桥，按类分流：
 *
 * <ul>
 *   <li><b>普通类</b>：交给 {@link MixinReWeaver} 重新织入，保住热更后仍然生效的 Mixin 钩子；</li>
 *   <li><b>Mixin 类</b>：交给 {@link MixinHotSwap} 刷新 Mixin 内部状态并调度目标类重织入，
 *       自身字节码原样返回（Mixin 类不会被 JVM 当普通类加载）。</li>
 * </ul>
 */
public final class MixinBytecodeBridge implements BytecodeTransformerBridge {

	private static final java.util.concurrent.atomic.AtomicBoolean REPORTED =
		new java.util.concurrent.atomic.AtomicBoolean();

	@Override
	public byte[] transform(ClassLoader loader, String dotClassName, Class<?> targetClass, byte[] sourceBytecode) {
		try {
			if (MixinHotSwap.handleIfMixin(dotClassName, sourceBytecode)) {
				return sourceBytecode;
			}
			return MixinReWeaver.reweave(dotClassName, sourceBytecode);
		} catch (Throwable t) {
			// Mixin 不在当前 ClassLoader（例如宿主未启用 Mixin）时，整条桥退化为不重织入。
			if (REPORTED.compareAndSet(false, true)) {
				remold.RemoldAgent.warn("Mixin 相关类不可用，本次运行不做重织入: " + t);
			}
			return sourceBytecode;
		}
	}
}
