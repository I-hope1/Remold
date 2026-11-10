package remold.mixin;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Set;

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
	public void onAssetsChanged(Set<Path> changedAssets) {
		MixinConfigDiff.onAssetsChanged(changedAssets);
	}

	/**
	 * premain 极早期窗口：把 defineClass 基线录口交出去（不变量 C2）。
	 *
	 * <p>必须早于宿主加载任何游戏类，否则合成内部类的基线永远抓不到，那条路径只剩
	 * "拒绝并要求重启"。真正的 {@code addTransformer} 由 core 延迟到所有 rewriter 之后执行，
	 * 因此录到的是 JVM 最终定义的那份字节。</p>
	 */
	@Override
	public void onPremain(String agentArgs) {
		MixinClassBaseline.install();
	}

	@Override
	public void onAgentInit(Instrumentation inst) {
		// agentmain（运行期 attach）路径没有 onPremain 窗口：同样幂等安装一次。
		MixinClassBaseline.install();
		try {
			RemoldAgent.addTransformerBridge(new MixinBytecodeBridge());
			RemoldAgent.info("[Mixin] Re-weaving bridge registered (plugin-mixin)");
			RemoldAgent.info("[Mixin] Config-diff support active (new Mixin classes and mixins.json removal/readd)");
			RemoldAgent.info("[Mixin] premain defineClass baseline recorder: " + MixinClassBaseline.metricsSummary());
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] Failed to register re-weaving bridge; proceeding without Mixin support: " + t);
		}
	}
}
