package remold.fabric;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import remold.RemoldAgent;

import static remold.RemoldAgent.DEBUG;
import static remold.RemoldAgent.log;
import static remold.RemoldAgent.warn;

/**
 * 运行目录残留构建产物巡检：找出与当前工程同 modId 的已打包 JAR。
 *
 * <p><b>为什么要巡检</b>：Minecraft（Fabric / NeoForge / Forge）散装类开发时，工程用的是
 * {@code build/classes} 里的散装字节码，而 {@code run/mods/} 下若同时存在同一 modId 的已打包 JAR，
 * 会出现双源加载、命名空间断裂（Intermediary vs Named）、资源覆写与 {@code ClassNotFoundException}。
 * 这类故障表现为"改的代码不生效 / 类找不到"，但根因在构建产物，不在热更链路，
 * 预先点名比事后排查便宜得多。</p>
 *
 * <p><b>为什么独立成类</b>：本类只做"读文件、解析元数据、告警"，不持有任何热更状态，也不参与
 * 重定义决策。它原先挤在 {@link RemoldAgent} 里，与监听、对齐、布局门共享同一个类体，
 * 使一个纯文件巡检逻辑看起来像热更流水线的一部分。</p>
 *
 * <p><b>元数据覆盖面</b>：{@code fabric.mod.json} / {@code quilt.mod.json} 读 {@code id} 字段；
 * {@code META-INF/neoforge.mods.toml} / {@code META-INF/mods.toml} 读 {@code modId = ...}。
 * 解析刻意用正则而非引入 JSON/TOML 依赖：这里只取一个标量字段，容错优先。</p>
 */
public final class ModJarInspector {

	private ModJarInspector() { }

	/**
	 * 巡检运行目录下（如 {@code run/mods/} 或 {@code mods/}）是否存在与当前工程 modId 冲突的残留旧构建 JAR。
	 *
	 * <p>只看候选目录的直属 JAR/ZIP；递归扫描会在大型 {@code run/} 目录下反复遍历，收益极低。</p>
	 */
	public static void checkStaleModJars(Collection<Path> watchDirs) {
		try {
			String currentModId = detectCurrentProjectModId(watchDirs);
			if (currentModId == null || currentModId.isEmpty()) {
				return;
			}
			List<Path> candidateModsDirs = List.of(
				Paths.get("run", "mods"),
				Paths.get("mods"),
				Paths.get("run"),
				Paths.get(".")
			);
			for (Path modsDir : candidateModsDirs) {
				if (!Files.isDirectory(modsDir)) continue;
				Path targetDir = modsDir;
				if (modsDir.getFileName() != null && !modsDir.getFileName().toString().equals("mods")) {
					Path subMods = modsDir.resolve("mods");
					if (Files.isDirectory(subMods)) {
						targetDir = subMods;
					} else {
						continue;
					}
				}
				try (Stream<Path> stream = Files.list(targetDir)) {
					stream.filter(p -> {
						String s = p.getFileName().toString().toLowerCase(Locale.ROOT);
						return s.endsWith(".jar") || s.endsWith(".zip");
					}).forEach(jarPath -> {
						String staleModId = readModIdFromJar(jarPath);
						if (currentModId.equals(staleModId)) {
							String yellow = "\u001B[33m";
							String reset = "\u001B[0m";
							warn(yellow + "Found stale build artifact in " + jarPath + " with identical modId '" + currentModId + "'.");
							warn("In-development runs directly use loose classes. A packaged JAR in run/mods causes duplicate mod conflicts, namespace crashes, or ClassNotFoundException! Please delete this JAR." + reset);
						}
					});
				}
			}
		} catch (Throwable t) {
			if (DEBUG) log("[CHECK-STALE] Stale JAR check encountered: " + t);
		}
	}

	/** 当前工程的 modId：先看监听目录里的元数据文件，再退化到 agent 自身 classpath 上的元数据。 */
	private static String detectCurrentProjectModId(Collection<Path> watchDirs) {
		if (watchDirs == null) return null;
		for (Path dir : watchDirs) {
			if (!Files.isDirectory(dir)) continue;
			Path fabricJson = dir.resolve("fabric.mod.json");
			if (Files.isRegularFile(fabricJson)) {
				String id = extractJsonModId(fabricJson);
				if (id != null) return id;
			}
			Path quiltJson = dir.resolve("quilt.mod.json");
			if (Files.isRegularFile(quiltJson)) {
				String id = extractJsonModId(quiltJson);
				if (id != null) return id;
			}
			Path neoForgeToml = dir.resolve("META-INF").resolve("neoforge.mods.toml");
			if (Files.isRegularFile(neoForgeToml)) {
				String id = extractTomlModId(neoForgeToml);
				if (id != null) return id;
			}
			Path forgeToml = dir.resolve("META-INF").resolve("mods.toml");
			if (Files.isRegularFile(forgeToml)) {
				String id = extractTomlModId(forgeToml);
				if (id != null) return id;
			}
		}
		try {
			ClassLoader cl = RemoldAgent.class.getClassLoader();
			if (cl == null) cl = ClassLoader.getSystemClassLoader();
			if (cl != null) {
				try (InputStream in = cl.getResourceAsStream("fabric.mod.json")) {
					if (in != null) {
						String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
						String id = extractModIdFromJsonString(content);
						if (id != null) return id;
					}
				}
				try (InputStream in = cl.getResourceAsStream("META-INF/neoforge.mods.toml")) {
					if (in != null) {
						String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
						String id = extractModIdFromTomlString(content);
						if (id != null) return id;
					}
				}
				try (InputStream in = cl.getResourceAsStream("META-INF/mods.toml")) {
					if (in != null) {
						String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
						String id = extractModIdFromTomlString(content);
						if (id != null) return id;
					}
				}
			}
		} catch (Throwable ignored) { }
		return null;
	}

	private static String extractJsonModId(Path path) {
		try {
			String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			return extractModIdFromJsonString(content);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static String extractModIdFromJsonString(String json) {
		Pattern p = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
		Matcher m = p.matcher(json);
		if (m.find()) {
			return m.group(1).trim();
		}
		return null;
	}

	private static String extractTomlModId(Path path) {
		try {
			String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			return extractModIdFromTomlString(content);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static String extractModIdFromTomlString(String toml) {
		Pattern p = Pattern.compile("modId\\s*=\\s*[\"']([^\"']+)[\"']");
		Matcher m = p.matcher(toml);
		if (m.find()) {
			return m.group(1).trim();
		}
		return null;
	}

	private static String readModIdFromJar(Path jarPath) {
		try (ZipFile zf = new ZipFile(jarPath.toFile())) {
			ZipEntry entry = zf.getEntry("fabric.mod.json");
			if (entry == null) entry = zf.getEntry("quilt.mod.json");
			if (entry != null) {
				try (InputStream in = zf.getInputStream(entry)) {
					String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
					return extractModIdFromJsonString(json);
				}
			}
			ZipEntry tomlEntry = zf.getEntry("META-INF/neoforge.mods.toml");
			if (tomlEntry == null) tomlEntry = zf.getEntry("META-INF/mods.toml");
			if (tomlEntry != null) {
				try (InputStream in = zf.getInputStream(tomlEntry)) {
					String toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
					return extractModIdFromTomlString(toml);
				}
			}
		} catch (Throwable ignored) { }
		return null;
	}
}
