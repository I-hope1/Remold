package com.example.client;

import com.example.ExampleMod;
import net.fabricmc.api.ClientModInitializer;

/**
 * 客户端入口：只负责注册 HotSwap Toast。专用服务器不会加载本入口点。
 *
 * <p>注册走 {@link HotSwapToastBridge} 的全反射路径，因此模板在未挂 Remold 的普通客户端上
 * 也能正常启动（只是不会弹卡片）。</p>
 */
public class ExampleModClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ExampleMod.LOGGER.info("[HotSwapToast] client entrypoint initialized");
		HotSwapToastBridge.install();
	}
}
