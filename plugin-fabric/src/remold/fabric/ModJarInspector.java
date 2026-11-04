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
 * <p><b>为什么独立成类</b>：本类只做"读文件、解析元数据、隔离残留制品"，不持有任何热更状态，也不参与
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
			boolean quarantine = quarantineEnabled();
			List<Path> candidateModsDirs = List.of(
				Paths.get("run", "mods"),
				Paths.get("mods"),
				Paths.get("run"),
				Paths.get(".")
			);
			java.util.Set<Path> visited = new java.util.HashSet<>();
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
				// 候选目录会互相折叠（run/mods 与 run→/mods 指向同一处），同一目录只扫一次，避免重复告警
				if (!visited.add(targetDir.toAbsolutePath().normalize())) continue;
				try (Stream<Path> stream = Files.list(targetDir)) {
					stream.filter(p -> {
						String s = p.getFileName().toString().toLowerCase(Locale.ROOT);
						return s.endsWith(".jar") || s.endsWith(".zip");
					}).forEach(jarPath -> {
						String staleModId = readModIdFromJar(jarPath);
						if (currentModId.equals(staleModId)) quarantineOrWarn(jarPath, currentModId, quarantine);
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

	/** 隔离后缀：不以 {@code .jar} 结尾，因此不会再被任何扫描 {@code *.jar} 的加载器拣到。 */
	static final String QUARANTINE_SUFFIX = ".remold-disabled";

	/** 关闭方式：{@code -Dremold.agent.mods_quarantine=false}（退回只告警）。 */
	private static boolean quarantineEnabled() {
		String v = System.getProperty("remold.agent.mods_quarantine");
		if (v == null) v = System.getProperty("remold.agent.modsQuarantine");
		return v == null || Boolean.parseBoolean(v.trim());
	}

	/**
	 * 命中"与当前工程同 modId 的残留构建 JAR"时的动作。
	 *
	 * <p><b>为什么是隔离而不是告警</b>：告警救不了实际行为。同一 modId 的打包 JAR 一旦留在
	 * {@code run/mods}，加载器就会把它当作这个开发中模组的候选容器，开发期跑起来的可能是
	 * <b>JAR 里的旧字节码</b>，散装类路径（{@code build/classes}）根本不生效——表现为"改了代码没反应"，
	 * 而且不报错。让那种候选不存在，比事后提醒可靠。</p>
	 *
	 * <p><b>只改名、不删除</b>：{@code foo.jar} → {@code foo.jar.remold-disabled}，文件仍在原地，
	 * 改回原名即可恢复；只处理同 modId 的制品，其它模组 JAR 一律不动。改名失败（占用、只读）时
	 * 退回告警，绝不因此中断启动。</p>
	 */
	static void quarantineOrWarn(Path jarPath, String currentModId, boolean quarantine) {
		String yellow = "[33m";
		String reset = "[0m";
		if (!quarantine) {
			warn(yellow + "Found stale build artifact in " + jarPath + " with identical modId '" + currentModId + "'.");
			warn("In-development runs directly use loose classes. A packaged JAR in run/mods can shadow them, so the OLD jar may be what actually runs! Please delete this JAR." + reset);
			return;
		}
		Path quarantined = quarantine(jarPath);
		if (quarantined != null) {
			warn(yellow + "Quarantined stale build artifact with identical modId '" + currentModId + "': "
			     + jarPath.getFileName() + " -> " + quarantined.getFileName()
			     + " (renamed, not deleted; rename it back to re-enable). In-development runs use loose"
			     + " classes, and a packaged JAR here would shadow them." + reset);
		} else {
			warn(yellow + "Found stale build artifact in " + jarPath + " with identical modId '" + currentModId
			     + "', but it could NOT be quarantined (locked or read-only). In-development runs use loose"
			     + " classes, and this JAR may shadow them - delete it manually." + reset);
		}
	}

	/**
	 * 把 JAR 改名到同目录的 {@code <name>.remold-disabled}（重名时追加时间戳）。
	 *
	 * @return 隔离后的路径；改名失败返回 {@code null}（调用方退回告警）
	 */
	static Path quarantine(Path jarPath) {
		try {
			Path target = jarPath.resolveSibling(jarPath.getFileName() + QUARANTINE_SUFFIX);
			if (Files.exists(target)) {
				target = jarPath.resolveSibling(jarPath.getFileName() + QUARANTINE_SUFFIX + "." + System.currentTimeMillis());
			}
			Files.move(jarPath, target);
			return target;
		} catch (Throwable t) {
			if (DEBUG) log("[CHECK-STALE] Could not quarantine " + jarPath + ": " + t);
			return null;
		}
	}
}
