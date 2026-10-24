package remold.arc;

import arc.Core;
import arc.files.Fi;
import arc.freetype.FreeTypeFontGenerator;
import arc.freetype.FreeTypeFontGenerator.FreeTypeFontData;
import arc.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.Font.FontData;
import arc.graphics.g2d.Font.Glyph;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.ui.Fonts;

import java.lang.reflect.Field;
import java.nio.file.Path;

/**
 * Arc / Mindustry 字体原位动态重载器。
 *
 * <p>采用 In-Place Mutation（原位覆写）架构：
 * 绝不替换全局 {@link Fonts#def} 实例对象，规避现存全部 {@code Label}、{@code TextButton}、
 * {@code Dialog} 等 UI 控件中 {@code FontCache} 强引用失效的问题；亦不销毁或置空全局 {@code UI.packer}，
 * 保护其 Page 0 共享的核心 UI 切片纹理。
 * 仅重置字形哈希表并调用 {@code generator.generateData(param, data)}，配合 {@link Fonts#loadContentIcons()}
 * 与 {@code Core.scene.root.invalidateHierarchy()} 实现全局 UI 字体瞬间自适应热刷新。</p>
 */
public final class FontReloader {
	private static Field parameterField;
	private static Field generatorField;
	private static Field dirtyField;
	private static Field glyphsSeqField;
	private static Field superGlyphsField;

	static {
		try {
			parameterField = FreeTypeFontData.class.getDeclaredField("parameter");
			parameterField.setAccessible(true);
			generatorField = FreeTypeFontData.class.getDeclaredField("generator");
			generatorField.setAccessible(true);
			dirtyField = FreeTypeFontData.class.getDeclaredField("dirty");
			dirtyField.setAccessible(true);
			glyphsSeqField = FreeTypeFontData.class.getDeclaredField("glyphs");
			glyphsSeqField.setAccessible(true);
		} catch (Throwable ignored) {
		}
		try {
			superGlyphsField = FontData.class.getDeclaredField("glyphs");
			superGlyphsField.setAccessible(true);
		} catch (Throwable ignored) {
		}
	}

	private FontReloader() {}

	/**
	 * 重载主字体数据（或指定字体文件）。
	 *
	 * @param fontPath 字体文件路径（若为 null，则基于现有的 generator 重新计算）
	 * @return 是否重载成功
	 */
	public static boolean reloadFont(Path fontPath) {
		try {
			if (Fonts.def == null) return false;
			Font font = Fonts.def;
			FontData rawData = font.getData();
			if (!(rawData instanceof FreeTypeFontData)) {
				return false;
			}
			FreeTypeFontData data = (FreeTypeFontData) rawData;

			if (parameterField == null || generatorField == null || dirtyField == null) {
				return false;
			}

			FreeTypeFontParameter param = (FreeTypeFontParameter) parameterField.get(data);
			FreeTypeFontGenerator gen;

			if (fontPath != null && fontPath.toFile().exists()) {
				gen = new FreeTypeFontGenerator(new Fi(fontPath.toFile()));
				generatorField.set(data, gen);
			} else {
				gen = (FreeTypeFontGenerator) generatorField.get(data);
			}

			if (gen == null || param == null) return false;

			// 1. 清空已缓存的字形页与布局缓存
			if (superGlyphsField != null) {
				Glyph[][] superGlyphs = (Glyph[][]) superGlyphsField.get(data);
				if (superGlyphs != null) {
					for (int p = 0; p < superGlyphs.length; p++) {
						superGlyphs[p] = null;
					}
				}
			}
			if (glyphsSeqField != null) {
				Seq<?> gSeq = (Seq<?>) glyphsSeqField.get(data);
				if (gSeq != null) {
					gSeq.clear();
				}
			}
			if (font.getCache() != null) {
				font.getCache().clear();
			}
			if (Fonts.outline != null && Fonts.outline.getCache() != null) {
				Fonts.outline.getCache().clear();
			}

			// 2. 原位调用引擎内置的 generateData 重新光栅化度量
			gen.generateData(param, data);

			// 3. 标记 dirty 驱动纹理区域同步
			dirtyField.set(data, true);

			// 4. 重新注入方块与物品的富文本 Emoji 图标
			Fonts.loadContentIcons();

			// 5. 触发全局 UI 树边界与排版失效，下一帧自动重绘
			if (Core.scene != null && Core.scene.root != null) {
				Core.scene.root.invalidateHierarchy();
			}

			Log.info("[Remold-Arc] Successfully in-place reloaded font: @", fontPath != null ? fontPath.getFileName() : "Fonts.def");
			return true;
		} catch (Throwable t) {
			Log.err("[Remold-Arc] Failed to reload font", t);
			return false;
		}
	}
}
