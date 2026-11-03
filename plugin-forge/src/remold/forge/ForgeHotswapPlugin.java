package remold.forge;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

import remold.HotswapPlugin;
import remold.RemoldAgent;

/**
 * Minecraft Forge 与 NeoForge 热重载扩展插件。
 *
 * <p>核心能力：</p>
 * <ol>
 *   <li>穿透 ModLauncher / ModuleLauncher 独立类加载器，打通跨 ClassLoader 的 Mixin 重织入桥；</li>
 *   <li>监听类重定义生命周期，自动重新注册 {@code @SubscribeEvent} 监听方法，使事件处理逻辑原位刷新；</li>
 *   <li>自适应探测 Forge (旧版/标准) 与 NeoForge (现代版) 命名空间与事件总线。</li>
 * </ol>
 */
public class ForgeHotswapPlugin implements HotswapPlugin {

	private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

	@Override
	public String getName() {
		return "forge";
	}

	@Override
	public void onAgentInit(Instrumentation inst) {
		initShared(inst, getName());
	}

	static void initShared(Instrumentation inst, String pluginName) {
		if (INITIALIZED.compareAndSet(false, true)) {
			try {
				RemoldAgent.addTransformerBridge(new ModLauncherMixinBridge(inst));
				RemoldAgent.addBatchListener(new ForgeEventBusRefresher(inst));
				RemoldAgent.info("[Forge] Forge & NeoForge hot-reload integration initialized (via plugin: " + pluginName + ")");
			} catch (Throwable t) {
				RemoldAgent.warn("[Forge] Failed to initialize Forge & NeoForge integration: " + t.getMessage());
			}
		}
	}
}
