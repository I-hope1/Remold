package remold.fabric;

import java.nio.file.Path;

/**
 * 开发运行目录的监听排除规则（Minecraft 系）。
 *
 * <p><b>规则</b>：任何路径段为 {@code mods} 的文件不参与热更监听。开发期模组由散装类路径提供，
 * {@code run/mods} 下的 JAR 属于"上一次误打包的残留"，监听它只会造成监听风暴与旧字节码回灌
 * （规范见 {@code docs/ecosystem/01-mod-artifact-and-class-isolation.md} §4.4 第 4 条）。</p>
 *
 * <p><b>这是加载器专有假设</b>：Mindustry / Arc 的模组本来就放在 {@code mods/} 下并且需要热更，
 * 该规则对它们不成立。因此规则集中在本类而不是散落在监听线程与启动扫描里：
 * 要支持"文件系统驱动生态"的 {@code mods} 目录监听时，只改这一处，不影响 Minecraft 的防风暴行为。</p>
 */
public final class DevWatchExclusions {

	private DevWatchExclusions() { }

	/** 该路径是否属于"开发期不应监听"的目录（当前仅 Minecraft 的 {@code mods} 段，{@code run/mods} 已包含在内）。 */
	public static boolean isIgnored(Path path) {
		if (path == null) return false;
		String norm = path.toString().replace('\\', '/');
		return norm.contains("/mods/");
	}
}
