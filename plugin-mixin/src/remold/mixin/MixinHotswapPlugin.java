package remold.mixin;

import java.lang.instrument.Instrumentation;

import remold.HotswapPlugin;
import remold.RemoldAgent;

/**
 * SpongePowered Mixin 生态热重载插件。
 *
 * <p>向 Remold 注册 {@link MixinBytecodeBridge}：热重定义前把源码级字节码重新交给 Mixin 织入，
 * 从而在热更后保留 {@code @Inject} / {@code @Unique} / {@code @Shadow} 等生效产物；
 * 改动的若是 Mixin 类本身，则刷新 Mixin 运行时状态并级联重织入其目标类。</p>
 *
 * <p>插件由 Agent 通过系统 ClassLoader 加载，因此直接依赖 {@code api} 的 SPI 契约，
 * 不存在反射与动态代理。运行时需要的 {@code org.spongepowered.asm.*} 由宿主提供。</p>
 */
public class MixinHotswapPlugin implements HotswapPlugin {

	@Override
	public String getName() {
		return "mixin";
	}

	@Override
	public void onAgentInit(Instrumentation inst) {
		try {
			RemoldAgent.addTransformerBridge(new MixinBytecodeBridge());
			RemoldAgent.info("Mixin 重织入桥已注册（plugin-mixin）");
		} catch (Throwable t) {
			RemoldAgent.warn("Mixin 重织入桥注册失败，本次运行按未接入 Mixin 处理: " + t);
		}
	}
}
