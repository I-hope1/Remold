package remold;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import remold.util.CRC64;

import static remold.RemoldAgent.DEBUG;
import static remold.RemoldAgent.activeWatchDirs;
import static remold.RemoldAgent.activeWatchJars;
import static remold.RemoldAgent.error;
import static remold.RemoldAgent.info;
import static remold.RemoldAgent.isAssetFile;
import static remold.RemoldAgent.log;
import static remold.RemoldAgent.pendingAssetChanges;
import static remold.RemoldAgent.pendingChanges;

/**
 * jar / zip 热更新的落盘侧：解压到固定临时目录，并维护"临时 .class 路径 → 来源 jar"的映射。
 *
 * <p><b>为什么临时目录按 jar 路径 hash 固定</b>：解压产物要作为挂载点交给 ClassLoader
 * （见 {@link MountManager}），路径每次变动都会让 ClassLoader 重新解析、也让旧条目永久残留。
 * 固定路径 + 每次先清空，既稳定又不会让 tmpdir 无限膨胀。</p>
 *
 * <p><b>为什么映射表跟着本类走</b>：{@link #tmpToJar()} 记录"这个临时 .class 来自哪个 jar"，
 * 是热更流水线判断"路径该还原成哪个 watchDir 虚拟路径"的唯一依据。它只被解压写入、被流水线读取，
 * 不属于流水线自身的状态。</p>
 */
final class JarExtractor {

	private JarExtractor() { }

	/** 临时解压出的 .class 路径 → 来源 jar 路径，用于 ClassLoader 推断。 */
	private static final Map<Path, Path> tmpToJar = new ConcurrentHashMap<>();

	/** 临时 .class 路径 → 来源 jar 路径的实时视图（写入方只有本类，读取方请勿修改）。 */
	static Map<Path, Path> tmpToJar() {
		return tmpToJar;
	}

	/**
	 * 将 jar/zip 内所有 .class 解压到固定临时目录（基于 jar 路径 hash 保持稳定），
	 * 每次更新前先清空旧内容，避免 tmpdir 无限膨胀。
	 * 解压结果批量加入 {@code pendingChanges}，并维护 {@link #tmpToJar()} 映射。
	 */
	static void extract(Path jarPath) {
		try {
			// 固定路径：<tmpdir>/remold-hotswap/<stem>-<jarPathHash>
			String stem = jarPath.getFileName().toString().replaceAll("[^a-zA-Z0-9_-]", "_");
			Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "remold-hotswap",
			 stem + "-" + Long.toHexString(CRC64.hashString(jarPath.toString())));

			// 清空旧内容（稳定路径，不删目录本身）
			if (Files.exists(tmpDir)) {
				try (Stream<Path> s = Files.walk(tmpDir)) {
					s.sorted(Comparator.reverseOrder())
					 .filter(p -> !p.equals(tmpDir))
					 .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
				}
			}
			Files.createDirectories(tmpDir);

			// 清理该 jar 上一次留下的 tmpToJar 条目，防止 map 无限增长
			tmpToJar.entrySet().removeIf(e -> e.getValue().equals(jarPath));

			List<Path> extracted = new ArrayList<>();
			List<Path> extractedAssets = new ArrayList<>();
			try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(jarPath)))) {
				ZipEntry entry;
				while ((entry = zis.getNextEntry()) != null) {
					try {
						if (!entry.isDirectory()) {
							String entryName = entry.getName();
							if (entryName.endsWith(".class")) {
								Path outPath = tmpDir.resolve(entryName.replace('/', File.separatorChar));
								Files.createDirectories(outPath.getParent());
								Files.write(outPath, zis.readAllBytes());
								tmpToJar.put(outPath, jarPath);
								extracted.add(outPath);
							} else {
								Path entryPath = Paths.get(entryName.replace('/', File.separatorChar));
								if (isAssetFile(entryPath)) {
									Path outPath = tmpDir.resolve(entryName.replace('/', File.separatorChar));
									Files.createDirectories(outPath.getParent());
									Files.write(outPath, zis.readAllBytes());
									extractedAssets.add(outPath);
								}
							}
						}
					} finally {
						zis.closeEntry();
					}
				}
			}

			if (extracted.isEmpty() && extractedAssets.isEmpty()) {
				if (DEBUG) log("[JAR] No relevant entries in: " + jarPath);
				return;
			}
			if (!extracted.isEmpty()) {
				info("[JAR] Extracted " + extracted.size() + " classes from " + jarPath.getFileName() + " → " + tmpDir);
			}
			if (!extractedAssets.isEmpty()) {
				info("[JAR] Extracted " + extractedAssets.size() + " assets from " + jarPath.getFileName() + " → " + tmpDir);
			}
			// 批量加入，只锁一次
			synchronized (pendingChanges) {
				if (!extracted.isEmpty()) pendingChanges.addAll(extracted);
				if (!extractedAssets.isEmpty()) pendingAssetChanges.addAll(extractedAssets);
			}
		} catch (IOException e) {
			error("Failed to extract jar/zip: " + jarPath, e);
		}
	}

	/**
	 * 将临时 .class 路径映射回 watchDir 内的虚拟路径，
	 * 供 findTargetClassLoader / mountForClass 正确推断 ClassLoader。
	 */
	static Path resolveWatchDirPath(Path jarPath, String className) {
		// 情况一：jar 在某个 watchDir 目录下（目录模式）
		for (Path watchDir : activeWatchDirs) {
			if (jarPath.startsWith(watchDir)) {
				return watchDir.resolve(className.replace('.', File.separatorChar) + ".class");
			}
		}
		// 情况二：jar 本身就是直接传入的 activeWatchJar。
		// 此时没有对应的 watchDir，不需要虚拟路径，返回 null 让调用方
		// 退化到 findTargetClassLoader(className) 纯包名推断即可。
		if (activeWatchJars.contains(jarPath)) {
			return null;
		}
		error("[WARN] resolveWatchDirPath: jar not under any watchDir or watchJar: " + jarPath);
		return null;
	}
}
