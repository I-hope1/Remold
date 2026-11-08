package remold.forge;

import java.lang.instrument.Instrumentation;
import java.util.Set;
import java.nio.file.Path;
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
	private static volatile Instrumentation instrumentation;
	private static volatile ModLauncherMixinBridge mixinBridge;

	@Override
	public String getName() {
		return "forge";
	}

	@Override
	public void onAgentInit(Instrumentation inst) {
		initShared(inst, getName());
	}

	@Override
	public void onAssetsChanged(Set<Path> changedAssets) {
		ForgeMixinConfigDiff.onAssetsChanged(changedAssets,
			mixinBridge == null ? null : mixinBridge.activeTransformer(),
			instrumentation == null ? null : instrumentation::getAllLoadedClasses);
	}

	static Object activeTransformer() {
		return mixinBridge == null ? null : mixinBridge.activeTransformer();
	}

	static ForgeMixinHotSwap.InstrumentationHolder instrumentation() {
		return instrumentation == null ? null : instrumentation::getAllLoadedClasses;
	}

	static void initShared(Instrumentation inst, String pluginName) {
		if (INITIALIZED.compareAndSet(false, true)) {
			try {
				instrumentation = inst;
				ModLauncherMixinBridge bridge = new ModLauncherMixinBridge(inst);
				mixinBridge = bridge;
				RemoldAgent.addTransformerBridge(bridge);
				RemoldAgent.addBatchListener(new ForgeEventBusRefresher(inst));
				RemoldAgent.info("[Forge] Forge & NeoForge hot-reload integration initialized (via plugin: " + pluginName + ")");
			} catch (Throwable t) {
				RemoldAgent.warn("[Forge] Failed to initialize Forge & NeoForge integration: " + t.getMessage());
			}
		}
	}
}
