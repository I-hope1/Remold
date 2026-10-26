package remold.arc;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Pixmap;
import arc.graphics.Pixmaps;
import arc.graphics.Texture;
import arc.graphics.Texture.TextureFilter;
import arc.graphics.g2d.TextureAtlas;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.mod.Mods.LoadedMod;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Arc / Mindustry 贴图动态重载器。
 *
 * <p>支持三条路径：
 * <ul>
 *   <li><b>Tier 1 (In-Place 原位直刷)</b>：当新旧贴图尺寸完全一致时，通过 {@link arc.graphics.Texture#draw(Pixmap, int, int)}
 *       直调 {@code Gl.texSubImage2D} 原位写入显存，耗时 &lt; 5ms，零指针与零材质引用失效；</li>
 *   <li><b>Tier 2 (拓扑重构与元数据同步)</b>：当尺寸变动时，写入新像素并级联刷新图集 Drawable 缓存、
 *       地表网格、全局样式与单位裁剪半径；</li>
 *   <li><b>新增贴图 (Standalone 注册)</b>：图集里尚不存在该区域时，为它单独建一张 {@link Texture} 并
 *       {@link TextureAtlas#addRegion} 注册进 {@code Core.atlas}。区域名严格按 Mindustry 的打包规则推导
 *       （{@code sprites/} 加模组前缀、{@code sprites-override/} 不加），保证热更与重启后的名字一致；
 *       推导不出归属模组时拒绝注册（宁可漏加，不可写错名字）。</li>
 * </ul>
 * </p>
 */
public final class TextureReloader {
	private static Field drawablesField;

	/** 由本类创建的独立纹理：区域名 -> Texture。同名重建时据此释放旧纹理，避免泄漏。 */
	private static final ObjectMap<String, Texture> addedTextures = new ObjectMap<>();

	static {
		try {
			drawablesField = TextureAtlas.class.getDeclaredField("drawables");
			drawablesField.setAccessible(true);
		} catch (Throwable ignored) {
		}
	}

	private TextureReloader() {}

	/**
	 * 重载单个贴图文件。
	 *
	 * @param texturePath 贴图文件磁盘路径
	 * @return 是否重载成功
	 */
	public static boolean reloadTexture(Path texturePath) {
		if (texturePath == null || Core.atlas == null) return false;
		Fi fi = new Fi(texturePath.toFile());
		if (!fi.exists()) return false;

		long startTime = Time.millis();
		String baseName = spriteBaseName(fi.nameWithoutExtension());
		if (baseName.isEmpty()) return false;

		Pixmap pixmap;
		try {
			pixmap = new Pixmap(fi);
		} catch (Throwable t) {
			Log.err("[Remold-Arc] Failed to decode image for @: @", baseName, t.getMessage());
			return false;
		}

		AtlasRegion region = null;
		try {
			region = findMatchingRegion(baseName);
			if (region == null || region.texture == null) {
				// 图集里没有该区域：按“新增贴图”注册成独立纹理
				return registerNewSprite(texturePath, baseName, pixmap);
			}
			// Tier 1: 尺寸完全一致，执行原位直刷
			if (pixmap.width == region.width && pixmap.height == region.height) {
				if (Core.settings != null && Core.settings.getBool("linear", true)) {
					Pixmaps.bleed(pixmap, 2);
				}
				region.texture.draw(pixmap, region.getX(), region.getY());

				// 地表方块刷新
				invalidateFloorRenderer();

				// 单位 clipSize 刷新
				updateUnitClipSize(region);

				long elapsed = Time.millis() - startTime;
				Log.info("[Remold-Arc] In-place reloaded sprite '@' (@x@) in @ms", region.name, region.width, region.height, elapsed);
				return true;
			}

			// Tier 2: 尺寸发生变化，执行拓扑与缓存级联更新
			Log.info("[Remold-Arc] Sprite '@' dimensions changed (@x@ -> @x@), applying Tier 2 refresh",
				region.name, region.width, region.height, pixmap.width, pixmap.height);

			// 尽量原位写（如果原位容纳不下，由下游或图集刷新处理）
			if (Core.settings != null && Core.settings.getBool("linear", true)) {
				Pixmaps.bleed(pixmap, 2);
			}
			region.texture.draw(pixmap, region.getX(), region.getY());

			// 清理图集 drawable 单例缓存
			clearDrawablesCache();

			// 重新加载 UI 切片与全局样式
			reloadTexAndStyles();

			// 级联刷新 Content 图标与属性
			reloadContent();

			// 地表方块刷新
			invalidateFloorRenderer();

			// 单位 clipSize 刷新
			updateUnitClipSize(region);

			long elapsed = Time.millis() - startTime;
			Log.info("[Remold-Arc] Tier 2 reloaded sprite '@' in @ms", region.name, elapsed);
			return true;
		} catch (Throwable t) {
			Log.err("[Remold-Arc] Error applying sprite reload for " + (region != null ? region.name : baseName), t);
			return false;
		} finally {
			pixmap.dispose();
		}
	}

	/**
	 * 在当前图集中寻找与 baseName 最匹配的 AtlasRegion。
	 */
	public static AtlasRegion findMatchingRegion(String baseName) {
		if (Core.atlas == null || baseName == null || baseName.isEmpty()) return null;

		// 1. 精确名称查找
		if (Core.atlas.has(baseName)) {
			AtlasRegion r = Core.atlas.find(baseName);
			if (Core.atlas.isFound(r)) return r;
		}

		// 2. 携带 Mod 名称前缀查找（如 example-mod-copper-wall）
		if (Vars.mods != null) {
			for (LoadedMod mod : Vars.mods.list()) {
				if (mod.enabled()) {
					String candidate = mod.name + "-" + baseName;
					if (Core.atlas.has(candidate)) {
						AtlasRegion r = Core.atlas.find(candidate);
						if (Core.atlas.isFound(r)) return r;
					}
				}
			}
		}

		// 3. 扫描图集遍历后缀匹配
		try {
			for (AtlasRegion r : Core.atlas.getRegions()) {
				if (r != null && r.name != null) {
					if (r.name.equals(baseName) || r.name.endsWith("-" + baseName)) {
						if (Core.atlas.isFound(r)) return r;
					}
				}
			}
		} catch (Throwable ignored) {
		}

		return null;
	}

	// ==================== 新增贴图（Standalone 注册） ====================

	/**
	 * 为图集里尚不存在的贴图注册一个独立区域。
	 *
	 * <p>区域名必须与「重启后 Mindustry 打包出来的名字」一致，否则热更与冷启行为会分叉：</p>
	 * <ul>
	 *   <li>{@code sprites/}：{@code <模组内部名>-<文件名>}；</li>
	 *   <li>{@code sprites-override/}：直接用文件名（覆盖同名原版区域）。</li>
	 * </ul>
	 * <p>归属模组通过「同目录已存在的兄弟贴图」反查得出；查不出且启用了多个模组时拒绝注册。</p>
	 */
	private static boolean registerNewSprite(Path texturePath, String baseName, Pixmap pixmap) {
		String regionName = resolveRegionName(baseName, isOverrideSprite(texturePath),
			enabledModNames(), siblingSpriteBaseNames(texturePath), TextureReloader::regionExists);

		if (regionName == null) {
			Log.warn("[Remold-Arc] New sprite '@' skipped: cannot infer its mod prefix "
				+ "(multiple mods enabled and no sibling sprite matched). Restart the game to load it.", baseName);
			return false;
		}

		boolean existed = regionExists(regionName);
		if (existed && !addedTextures.containsKey(regionName)) {
			Log.warn("[Remold-Arc] New sprite '@' skipped: atlas region already exists and was not created by Remold.", regionName);
			return false;
		}

		// 独立纹理默认 clampToEdge，不需要像打包贴图那样做 bleed
		Texture texture = new Texture(pixmap);
		texture.setFilter(linearFiltering() ? TextureFilter.linear : TextureFilter.nearest);

		AtlasRegion region;
		Texture previous = addedTextures.get(regionName);
		if (existed) {
			// 同名重建：原地改既有 region 的纹理与 UV，保持对象引用稳定（已持有它的 UI 会自动跟随）
			region = Core.atlas.find(regionName);
			region.set(texture);
		} else {
			region = Core.atlas.addRegion(regionName, texture, 0, 0, texture.width, texture.height);
		}
		region.originalWidth = texture.width;
		region.originalHeight = texture.height;
		region.packedWidth = texture.width;
		region.packedHeight = texture.height;

		addedTextures.put(regionName, texture);
		// 丢掉该名字的 Drawable 缓存，避免拿到旧尺寸的 TextureRegionDrawable
		clearDrawable(regionName);

		if (previous != null && previous != texture) {
			// addRegion 会把纹理登记进 atlas.textures（atlas dispose 时统一释放），替换时必须自己摘掉并释放
			Core.atlas.getTextures().remove(previous);
			previous.dispose();
		}

		Log.info("[Remold-Arc] Registered new sprite '@' (@x@, standalone texture outside the packed atlas page)",
			regionName, texture.width, texture.height);
		return true;
	}

	/** 文件名（不含扩展名）-> 区域基名。与 Mindustry 打包规则一致：遇到多余的 “.” 就截断（如 {@code icon.9} -> {@code icon}）。 */
	static String spriteBaseName(String fileNameWithoutExtension) {
		if (fileNameWithoutExtension == null) return "";
		int dot = fileNameWithoutExtension.indexOf('.');
		return dot >= 0 ? fileNameWithoutExtension.substring(0, dot) : fileNameWithoutExtension;
	}

	/** {@code sprites-override/} 下的贴图不加模组前缀，直接覆盖同名区域。 */
	static boolean isOverrideSprite(Path path) {
		if (path == null) return false;
		for (Path part : path) {
			if (part != null && "sprites-override".equalsIgnoreCase(part.toString())) return true;
		}
		return false;
	}

	/**
	 * 纯函数：推导新贴图应注册的区域名。返回 {@code null} 表示无法确定归属模组，调用方应拒绝注册。
	 *
	 * @param enabledMods      已启用模组的内部名
	 * @param siblingBaseNames 同目录下已存在贴图的基名
	 * @param regionExists     判断某名字是否已存在于图集
	 */
	static String resolveRegionName(String baseName, boolean override, Seq<String> enabledMods,
									Seq<String> siblingBaseNames, Predicate<String> regionExists) {
		if (baseName == null || baseName.isEmpty()) return null;
		if (override) return baseName;
		String prefix = inferModPrefix(enabledMods, siblingBaseNames, regionExists);
		return prefix == null ? null : prefix + "-" + baseName;
	}

	/**
	 * 纯函数：用「同目录兄弟贴图」反查目录所属模组。
	 *
	 * <p>兄弟贴图 {@code foo} 若能以 {@code <模组名>-foo} 命中图集，说明该目录就是这个模组的 {@code sprites/}。
	 * 没有兄弟能命中时，仅在只启用一个模组的情况下才认为无歧义。</p>
	 */
	static String inferModPrefix(Seq<String> enabledMods, Seq<String> siblingBaseNames, Predicate<String> regionExists) {
		if (enabledMods == null || enabledMods.isEmpty()) return null;
		if (siblingBaseNames != null && regionExists != null) {
			for (String sibling : siblingBaseNames) {
				if (sibling == null || sibling.isEmpty()) continue;
				for (String mod : enabledMods) {
					if (mod != null && regionExists.test(mod + "-" + sibling)) return mod;
				}
			}
		}
		return enabledMods.size == 1 ? enabledMods.first() : null;
	}

	private static Seq<String> enabledModNames() {
		Seq<String> out = new Seq<>();
		if (Vars.mods == null) return out;
		for (LoadedMod mod : Vars.mods.list()) {
			if (mod != null && mod.enabled() && mod.name != null) out.add(mod.name);
		}
		return out;
	}

	private static Seq<String> siblingSpriteBaseNames(Path texturePath) {
		Seq<String> out = new Seq<>();
		Path parent = texturePath == null ? null : texturePath.getParent();
		if (parent == null) return out;

		String self = spriteBaseName(new Fi(texturePath.toFile()).nameWithoutExtension());
		Fi[] files = new Fi(parent.toFile()).list();
		if (files == null) return out;

		for (Fi file : files) {
			if (file == null || !file.extEquals("png")) continue;
			String name = spriteBaseName(file.nameWithoutExtension());
			if (!name.isEmpty() && !name.equals(self)) out.add(name);
		}
		return out;
	}

	private static boolean regionExists(String name) {
		return Core.atlas != null && name != null && Core.atlas.has(name);
	}

	private static boolean linearFiltering() {
		return Core.settings == null || Core.settings.getBool("linear", true);
	}

	private static void clearDrawable(String name) {
		try {
			if (Core.atlas != null && name != null) Core.atlas.getDrawables().remove(name);
		} catch (Throwable ignored) {
		}
	}

	private static void invalidateFloorRenderer() {
		try {
			if (Vars.renderer != null && Vars.renderer.blocks != null && Vars.renderer.blocks.floor != null) {
				Vars.renderer.blocks.floor.reload();
			}
		} catch (Throwable ignored) {
		}
	}

	private static void updateUnitClipSize(AtlasRegion region) {
		try {
			if (Vars.content != null) {
				Vars.content.units().each(u -> {
					if (u != null && (u.region == region || (u.name != null && u.name.equals(region.name)))) {
						u.clipSize = Math.max(region.width * 2f, u.clipSize);
					}
				});
			}
		} catch (Throwable ignored) {
		}
	}

	private static void clearDrawablesCache() {
		try {
			if (drawablesField != null && Core.atlas != null) {
				ObjectMap<?, ?> drawables = (ObjectMap<?, ?>) drawablesField.get(Core.atlas);
				if (drawables != null) {
					drawables.clear();
				}
			}
		} catch (Throwable ignored) {
		}
	}

	private static void reloadTexAndStyles() {
		try {
			mindustry.gen.Tex.load();
		} catch (Throwable ignored) {
		}
		try {
			mindustry.ui.Styles.load();
		} catch (Throwable ignored) {
		}
	}

	private static void reloadContent() {
		try {
			if (Vars.content != null) {
				Vars.content.each(c -> {
					if (c instanceof UnlockableContent) {
						UnlockableContent u = (UnlockableContent) c;
						try {
							u.load();
							u.loadIcon();
						} catch (Throwable ignored) {
						}
					}
				});
			}
		} catch (Throwable ignored) {
		}
	}
}
