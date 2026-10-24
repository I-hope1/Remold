package remold.arc;

import arc.Core;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.util.I18NBundle;
import arc.util.Log;
import arc.util.io.PropertiesUtils;

import java.nio.file.Path;

/**
 * Arc / Mindustry 多语言资源包动态重载器。
 *
 * <p>监听并动态重载 {@code bundles/*.properties}。
 * 解析变动的 properties 文本并热合并至活跃的 {@link Core#bundle} 及父链（Parent Bundles）属性表中，
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
			ObjectMap<String, String> properties = Core.bundle.getProperties();
			if (properties == null) return false;

			int beforeSize = properties.size;
			PropertiesUtils.load(properties, fi.reader("UTF-8"));
			int afterSize = properties.size;

			// 若存在父链 bundle，同步加载至对应的父 bundle
			I18NBundle parent = Core.bundle.getParent();
			while (parent != null) {
				if (parent.getProperties() != null) {
					PropertiesUtils.load(parent.getProperties(), fi.reader("UTF-8"));
				}
				parent = parent.getParent();
			}

			// 触发 UI 层次结构刷新以更新控件内显示的文本
			if (Core.scene != null && Core.scene.root != null) {
				Core.scene.root.invalidateHierarchy();
			}

			Log.info("[Remold-Arc] Successfully reloaded bundle: @ (properties count: @ -> @)",
				bundlePath.getFileName(), beforeSize, afterSize);
			return true;
		} catch (Throwable t) {
			Log.err("[Remold-Arc] Failed to reload bundle: " + bundlePath, t);
			return false;
		}
	}
}
