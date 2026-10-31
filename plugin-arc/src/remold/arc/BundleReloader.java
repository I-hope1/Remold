package remold.arc;

import arc.Core;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.util.I18NBundle;
import arc.util.Log;
import arc.util.io.PropertiesUtils;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Arc / Mindustry 多语言资源包动态重载器。
 *
 * <p>监听并动态重载 {@code bundles/*.properties}。
 * 解析变动的 properties 文本并热合并至活跃的 {@link Core#bundle} 及父链（Parent Bundles）属性表中，
 * 同步刷新 {@link UnlockableContent} 构造期缓存的 {@code localizedName}/{@code description} 等字段，
 * 触发 {@code Core.scene.root.invalidateHierarchy()} 实现 UI 界面多语言文本实时更新，无需重启。</p>
 */
public final class BundleReloader {
	private BundleReloader() {}

	/**
	 * 重载多语言 Bundle 文件。
	 *
	 * @param bundlePath properties 文件磁盘路径
	 * @return 是否重载成功
	 */
	public static boolean reloadBundle(Path bundlePath) {
		if (bundlePath == null || Core.bundle == null) return false;
		Fi fi = new Fi(bundlePath.toFile());
		if (!fi.exists()) return false;

		try {
			I18NBundle target = resolveTargetBundle(fi.nameWithoutExtension());
			if (target == null) {
				Log.debug("[Remold-Arc] Ignoring non-applicable bundle file: @", bundlePath.getFileName());
				return false;
			}
			ObjectMap<String, String> properties = target.getProperties();
			if (properties == null) return false;

			// 在合并新属性前记录旧 bundle 的解析结果，用于区分"缓存自 bundle"与"程序化派生"的值。
			List<CachedLocalization> localization = snapshotLocalization();

			int beforeSize = properties.size;
			PropertiesUtils.load(properties, fi.reader("UTF-8"));
			int afterSize = properties.size;

			int refreshed = applyLocalization(localization);

			// 触发 UI 层次结构刷新以更新控件内显示的文本
			if (Core.scene != null && Core.scene.root != null) {
				Core.scene.root.invalidateHierarchy();
			}

			Log.info("[Remold-Arc] Successfully reloaded bundle: @ (properties count: @ -> @, refreshed fields: @)",
				bundlePath.getFileName(), beforeSize, afterSize, refreshed);
			return true;
		} catch (Throwable t) {
			Log.err("[Remold-Arc] Failed to reload bundle: " + bundlePath, t);
			return false;
		}
	}

	/**
	 * 按 {@code Mods.buildFiles} 的规则选出该文件应写入的 bundle：
	 * {@code bundle.properties} 只写入根（base）bundle，{@code bundle_<locale>.properties}
	 * 只写入 locale 字符串匹配的父链节点。避免把某个语言包写进其它语言层。
	 */
	private static I18NBundle resolveTargetBundle(String name) {
		if (name == null || !name.startsWith("bundle")) return null;

		String wanted;
		if (name.equals("bundle")) {
			wanted = "";
		} else if (name.startsWith("bundle_")) {
			wanted = name.substring("bundle_".length());
			if (wanted.isEmpty()) return null;
		} else {
			return null;
		}

		I18NBundle bundle = Core.bundle;
		while (bundle != null) {
			String locale = bundle.getLocale() == null ? null : bundle.getLocale().toString();
			if (wanted.isEmpty() ? (locale == null || locale.isEmpty()) : wanted.equals(locale)) {
				return bundle;
			}
			bundle = bundle.getParent();
		}
		return null;
	}

	/**
	 * 记录当前 bundle 对每个可解锁内容解析出的本地化值。
	 * 这些值代表"内容字段若未被程序化改写，应等于的值"。
	 */
	private static List<CachedLocalization> snapshotLocalization() {
		List<CachedLocalization> result = new ArrayList<>();
		if (Vars.content == null) return result;

		Vars.content.each(c -> {
			if (!(c instanceof UnlockableContent u)) return;
			try {
				String prefix = localizationPrefix(u);
				result.add(new CachedLocalization(u,
					Core.bundle.get(prefix + ".name", u.name),
					Core.bundle.getOrNull(prefix + ".description"),
					Core.bundle.getOrNull(prefix + ".details"),
					Core.bundle.getOrNull(prefix + ".credit")));
			} catch (Throwable ignored) {
			}
		});
		return result;
	}

	/**
	 * 将新 bundle 的解析结果写回内容字段。
	 * 仅当字段仍等于合并前的 bundle 解析值时才覆写，避免破坏 {@code OreBlock} 等由其它内容派生的名称。
	 *
	 * @return 实际发生更新的字段数
	 */
	private static int applyLocalization(List<CachedLocalization> snapshot) {
		int updated = 0;

		for (CachedLocalization cached : snapshot) {
			UnlockableContent u = cached.content;
			try {
				String prefix = localizationPrefix(u);
				String name = Core.bundle.get(prefix + ".name", u.name);
				String description = Core.bundle.getOrNull(prefix + ".description");
				String details = Core.bundle.getOrNull(prefix + ".details");
				String credit = Core.bundle.getOrNull(prefix + ".credit");

				if (Objects.equals(u.localizedName, cached.localizedName) && !Objects.equals(u.localizedName, name)) {
					u.localizedName = name;
					updated++;
				}
				if (Objects.equals(u.description, cached.description) && !Objects.equals(u.description, description)) {
					u.description = description;
					updated++;
				}
				if (Objects.equals(u.details, cached.details) && !Objects.equals(u.details, details)) {
					u.details = details;
					updated++;
				}
				if (Objects.equals(u.credit, cached.credit) && !Objects.equals(u.credit, credit)) {
					u.credit = credit;
					updated++;
				}
			} catch (Throwable ignored) {
			}
		}
		return updated;
	}

	private static String localizationPrefix(UnlockableContent content) {
		return content.getContentType() + "." + content.name;
	}

	private static final class CachedLocalization {
		final UnlockableContent content;
		final String localizedName;
		final String description;
		final String details;
		final String credit;

		CachedLocalization(UnlockableContent content, String localizedName, String description, String details, String credit) {
			this.content = content;
			this.localizedName = localizedName;
			this.description = description;
			this.details = details;
			this.credit = credit;
		}
	}
}
