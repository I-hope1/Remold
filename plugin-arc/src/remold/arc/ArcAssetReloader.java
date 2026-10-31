package remold.arc;

import arc.Core;
import arc.util.Log;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Arc / Mindustry 统一资源重载分发协调器。
 *
 * <p>响应 RemoldAgent 分发的 {@link remold.HotswapPlugin#onAssetsChanged(Set)} 事件，
 * 识别贴图、字体、多语言、音效等不同类型资源，并统一调度至 Arc GL 主线程（{@link Core#app}）
 * 执行安全的原位热更新。</p>
 *
 * <p>无法安全原位处理的资源（着色器、content JSON/HJSON、图集、音乐、普通数据文件）
 * 不静默忽略，而是明确记录“需要重启”，避免用户误以为修改已生效。</p>
 */
public final class ArcAssetReloader {
	private ArcAssetReloader() {}

	public enum AssetKind {
		TEXTURE,
		FONT,
		BUNDLE,
		SOUND,
		MUSIC,
		SHADER,
		CONTENT,
		ATLAS,
		DATA,
		OTHER
	}

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
					switch (classify(path)) {
						case TEXTURE -> TextureReloader.reloadTexture(path);
						case FONT -> FontReloader.reloadFont(path);
						case BUNDLE -> BundleReloader.reloadBundle(path);
						case SOUND -> SoundReloader.reloadSound(path);
						case MUSIC -> MusicReloader.reloadMusic(path);
						case SHADER -> ShaderReloader.reloadShader(path);
						case CONTENT -> warnRestart(path, "Content JSON/HJSON re-parsing");
						case ATLAS -> warnRestart(path, "Atlas rebuild");
						case DATA -> warnRestart(path, "This data file has no generic hot-reload handler");
						case OTHER -> {
						}
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

	/**
	 * 按 Mindustry 的资源目录与扩展名对变动文件分类。
	 * 公开以便测试覆盖分发路由。
	 */
	public static AssetKind classify(Path path) {
		if (path == null || path.getFileName() == null) return AssetKind.OTHER;
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);

		if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
			return AssetKind.TEXTURE;
		}
		if (name.endsWith(".woff") || name.endsWith(".ttf") || name.endsWith(".fnt")) {
			return AssetKind.FONT;
		}
		if (name.endsWith(".properties")) {
			return name.startsWith("bundle") ? AssetKind.BUNDLE : AssetKind.DATA;
		}
		if (name.endsWith(".ogg") || name.endsWith(".wav") || name.endsWith(".mp3")) {
			return hasSegment(path, "music") ? AssetKind.MUSIC : AssetKind.SOUND;
		}
		if (name.endsWith(".frag") || name.endsWith(".vert") || name.endsWith(".glsl")) {
			return AssetKind.SHADER;
		}
		if (name.endsWith(".json") || name.endsWith(".hjson")) {
			return AssetKind.CONTENT;
		}
		if (name.endsWith(".atlas")) {
			return AssetKind.ATLAS;
		}
		if (name.endsWith(".csv") || name.endsWith(".txt")) {
			return AssetKind.DATA;
		}
		return AssetKind.OTHER;
	}

	private static boolean hasSegment(Path path, String segment) {
		for (Path part : path) {
			if (part != null && segment.equalsIgnoreCase(part.toString())) return true;
		}
		return false;
	}

	private static void warnRestart(Path path, String what) {
		Log.warn("[Remold-Arc] @ is not supported for @; a restart is required", what, path.getFileName());
	}
}
