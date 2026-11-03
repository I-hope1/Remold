package remold.forge;

import java.lang.instrument.Instrumentation;

import remold.HotswapPlugin;

/**
 * NeoForge 专属名称标识插件，共享 {@link ForgeHotswapPlugin} 的底层自适应能力。
 */
public class NeoForgeHotswapPlugin implements HotswapPlugin {

	@Override
	public String getName() {
		return "neoforge";
	}

	@Override
	public void onAgentInit(Instrumentation inst) {
		ForgeHotswapPlugin.initShared(inst, getName());
	}
}
