package remold.arc;

import arc.Core;
import arc.util.Log;

import java.nio.file.Path;
import java.util.Set;

/**
 * Arc / Mindustry 统一资源重载分发协调器。
 *
 * <p>响应 RemoldAgent 分发的 {@link remold.HotswapPlugin#onAssetsChanged(Set)} 事件，
 * 识别贴图、字体、多语言等不同类型资源，并统一调度至 Arc GL 主线程（{@link Core#app}）
 * 执行安全的原位热更新。</p>
 */
public final class ArcAssetReloader {
	private ArcAssetReloader() {}

	/**
	 * 统一响应资源变动集合。
	 *
	 * @param changedAssets 变动的资源文件路径集合
	 */
	public static void onAssetsChanged(Set<Path> changedAssets) {
		if (changedAssets == null || changedAssets.isEmpty()) return;

		Runnable reloadTask = () -> {
			for (Path path : changedAssets) {
				if (path == null) continue;
				try {
					Path fileName = path.getFileName();
					if (fileName == null) continue;
					String name = fileName.toString().toLowerCase();

					if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
						TextureReloader.reloadTexture(path);
					} else if (name.endsWith(".woff") || name.endsWith(".ttf") || name.endsWith(".fnt")) {
						FontReloader.reloadFont(path);
					} else if (name.endsWith(".properties")) {
						BundleReloader.reloadBundle(path);
					}
				} catch (Throwable t) {
					Log.err("[Remold-Arc] Error handling asset change for " + path, t);
				}
			}
		};

		if (Core.app != null) {
			Core.app.post(reloadTask);
		} else {
			reloadTask.run();
		}
	}
}
