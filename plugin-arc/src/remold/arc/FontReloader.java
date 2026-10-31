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
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Arc / Mindustry 字体原位动态重载器。
 *
 * <p>采用 In-Place Mutation（原位覆写）架构：
 * 绝不替换全局 {@link Fonts#def} 实例对象，规避现存全部 {@code Label}、{@code TextButton}、
 * {@code Dialog} 等 UI 控件中 {@code FontCache} 强引用失效的问题；亦不销毁或置空全局 {@code UI.packer}，
 * 保护其 Page 0 共享的核心 UI 切片纹理。
 * 仅重置目标字体数据的字形哈希表并调用 {@code generator.generateData(param, data)}，
 * 配合 {@link Fonts#loadContentIcons()} 与 {@code Core.scene.root.invalidateHierarchy()}
 * 实现全局 UI 字体瞬间自适应热刷新。</p>
 *
 * <p>字体文件到字体槽位的路由不能依赖“永远重载 {@link Fonts#def}”：
 * {@code font.woff} 同时供 {@code def}/{@code outline} 使用，{@code icon.ttf} 同时供
 * {@code icon}/{@code iconLarge} 使用，而 {@code monospace}/{@code logic}/{@code tech}
 * 各自独立。命名不匹配时再退回按 generator 记录的文件路径匹配，以覆盖 {@code font_jp} 等
 * override 数据。</p>
 */
public final class FontReloader {
	private static Field parameterField;
	private static Field generatorField;
	private static Field generatorNameField;
	private static Field dirtyField;
	private static Field glyphsSeqField;
	private static Field superGlyphsField;
	private static Field overrideField;

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
			overrideField = FreeTypeFontData.class.getDeclaredField("override");
			overrideField.setAccessible(true);
		} catch (Throwable ignored) {
		}
		try {
			superGlyphsField = FontData.class.getDeclaredField("glyphs");
			superGlyphsField.setAccessible(true);
		} catch (Throwable ignored) {
		}
		try {
			generatorNameField = FreeTypeFontGenerator.class.getDeclaredField("name");
			generatorNameField.setAccessible(true);
		} catch (Throwable ignored) {
		}
	}

	private FontReloader() {}

	/**
	 * 重载与指定字体文件对应的字体数据。
	 *
	 * @param fontPath 字体文件路径；为 {@code null} 时用各字体现有 generator 重新计算
	 * @return 是否至少重载了一个字体数据
	 */
	public static boolean reloadFont(Path fontPath) {
		try {
			if (Fonts.def == null) return false;
			if (parameterField == null || generatorField == null || dirtyField == null) return false;
			if (fontPath != null && !Files.isRegularFile(fontPath)) return false;

			List<FontEntry> entries = collectEntries();
			if (entries.isEmpty()) return false;

			Set<FontEntry> targets = selectTargets(entries, fontPath);
			if (targets.isEmpty()) {
				Log.warn("[Remold-Arc] No loaded font matched @; a restart is required", fontPath);
				return false;
			}

			int reloaded = 0;
			for (FontEntry entry : targets) {
				if (reloadData(entry, fontPath)) reloaded++;
			}
			if (reloaded == 0) return false;

			// override 数据换源后，持有它的字体必须丢弃已复制的字形，才能按新 override 重新取字。
			for (FontEntry entry : targets) {
				if (entry.parent != null) {
					clearGlyphs(entry.parent.data);
					dirtyField.set(entry.parent.data, true);
					if (entry.parent.font.getCache() != null) entry.parent.font.getCache().clear();
				}
			}
			for (FontEntry entry : entries) {
				if (entry.parent == null && entry.font.getCache() != null) {
					entry.font.getCache().clear();
				}
			}

			try {
				Fonts.loadContentIcons();
			} catch (Throwable t) {
				Log.debug("[Remold-Arc] Font reloaded, but content icon re-registration failed: @", t.toString());
			}

			if (Core.scene != null && Core.scene.root != null) {
				Core.scene.root.invalidateHierarchy();
			}

			Log.info("[Remold-Arc] Successfully in-place reloaded font: @ (@ slot(s))",
				fontPath != null ? fontPath.getFileName() : "all", reloaded);
			return true;
		} catch (Throwable t) {
			Log.err("[Remold-Arc] Failed to reload font", t);
			return false;
		}
	}

	/**
	 * 标准字体文件名到 {@link Fonts} 槽位的映射。
	 * 返回 {@code null} 表示不是内置标准字体名，交由 generator 路径匹配处理。
	 */
	public static String[] standardSlots(Path fontPath) {
		if (fontPath == null || fontPath.getFileName() == null) return null;
		String name = fontPath.getFileName().toString().toLowerCase(Locale.ROOT);
		return switch (name) {
			case "font.woff" -> new String[]{"def", "outline"};
			case "monospace.woff" -> new String[]{"monospace"};
			case "icon.ttf" -> new String[]{"icon", "iconLarge"};
			case "logic.ttf" -> new String[]{"logic"};
			case "tech.ttf" -> new String[]{"tech"};
			default -> null;
		};
	}

	private static Set<FontEntry> selectTargets(List<FontEntry> entries, Path fontPath) {
		Set<FontEntry> targets = new LinkedHashSet<>();
		if (fontPath == null) {
			targets.addAll(entries);
			return targets;
		}

		String[] slots = standardSlots(fontPath);
		if (slots != null) {
			for (String slot : slots) {
				for (FontEntry entry : entries) {
					if (entry.parent == null && slot.equals(entry.slot)) {
						targets.add(entry);
					}
				}
			}
		}

		if (targets.isEmpty()) {
			for (FontEntry entry : entries) {
				if (generatorMatches(entry, fontPath)) targets.add(entry);
			}
		}
		return targets;
	}

	private static boolean generatorMatches(FontEntry entry, Path fontPath) {
		if (generatorNameField == null) return false;
		try {
			FreeTypeFontGenerator gen = (FreeTypeFontGenerator) generatorField.get(entry.data);
			if (gen == null) return false;
			String name = (String) generatorNameField.get(gen);
			if (name == null || name.isEmpty()) return false;
			String normalized = Paths.get(name).toAbsolutePath().normalize().toString().replace('\\', '/');
			return normalized.equals(fontKey(fontPath));
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static String fontKey(Path fontPath) {
		String path = fontPath.toAbsolutePath().normalize().toString().replace('\\', '/');
		int slash = path.lastIndexOf('/');
		int dot = path.lastIndexOf('.');
		return dot > slash ? path.substring(0, dot) : path;
	}

	private static List<FontEntry> collectEntries() {
		List<FontEntry> entries = new ArrayList<>();
		for (Field field : Fonts.class.getFields()) {
			if (!Modifier.isStatic(field.getModifiers()) || !Font.class.isAssignableFrom(field.getType())) continue;
			try {
				Font font = (Font) field.get(null);
				if (font == null) continue;
				FontData data = font.getData();
				if (!(data instanceof FreeTypeFontData freeTypeData)) continue;
				FontEntry entry = new FontEntry(font, freeTypeData, null, field.getName());
				entries.add(entry);
				collectOverrides(entry, entries);
			} catch (Throwable ignored) {
			}
		}
		return entries;
	}

	private static void collectOverrides(FontEntry parent, List<FontEntry> entries) {
		if (overrideField == null) return;
		try {
			Object override = overrideField.get(parent.data);
			if (override instanceof FreeTypeFontData data && data != parent.data) {
				FontEntry entry = new FontEntry(parent.font, data, parent, null);
				entries.add(entry);
				collectOverrides(entry, entries);
			}
		} catch (Throwable ignored) {
		}
	}

	private static boolean reloadData(FontEntry entry, Path fontPath) {
		try {
			FreeTypeFontParameter parameter = (FreeTypeFontParameter) parameterField.get(entry.data);
			if (parameter == null) return false;

			FreeTypeFontGenerator previous = (FreeTypeFontGenerator) generatorField.get(entry.data);
			FreeTypeFontGenerator generator = previous;
			if (fontPath != null) {
				generator = new FreeTypeFontGenerator(new Fi(fontPath.toFile()));
				generatorField.set(entry.data, generator);
			}
			if (generator == null) return false;

			clearGlyphs(entry.data);
			generator.generateData(parameter, entry.data);
			dirtyField.set(entry.data, true);

			if (entry.parent == null && entry.font == Fonts.tech) {
				// 与 Fonts.loadDefaultFont() 的加载后调整保持一致，避免每次重载叠加。
				entry.data.down *= 1.5f;
			}

			if (previous != null && previous != generator) {
				previous.dispose();
			}
			return true;
		} catch (Throwable t) {
			Log.err("[Remold-Arc] Failed to reload font data " + entry.data + " from " + fontPath, t);
			return false;
		}
	}

	private static void clearGlyphs(FreeTypeFontData data) throws IllegalAccessException {
		if (superGlyphsField != null) {
			Glyph[][] glyphs = (Glyph[][]) superGlyphsField.get(data);
			if (glyphs != null) Arrays.fill(glyphs, null);
		}
		if (glyphsSeqField != null) {
			Seq<?> glyphSeq = (Seq<?>) glyphsSeqField.get(data);
			if (glyphSeq != null) glyphSeq.clear();
		}
	}

	private static final class FontEntry {
		final Font font;
		final FreeTypeFontData data;
		final FontEntry parent;
		final String slot;

		FontEntry(Font font, FreeTypeFontData data, FontEntry parent, String slot) {
			this.font = font;
			this.data = data;
			this.parent = parent;
			this.slot = slot;
		}
	}
}
