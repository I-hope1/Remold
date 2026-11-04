package remold;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import static remold.RemoldAgent.BUILD_MODE;
import static remold.RemoldAgent.FILE_SHAKE_MS;
import static remold.RemoldAgent.activeWatchDirs;
import static remold.RemoldAgent.activeWatchJars;
import static remold.RemoldAgent.error;
import static remold.RemoldAgent.info;
import static remold.RemoldAgent.warn;

/**
 * 监控路径的注册表：按来源（{@code cli} / {@code runtime} / 插件自定义 id）分槽保存目录与 jar，
 * 并把所有来源聚合成"当前真正在监听"的集合。
 *
 * <p><b>为什么按来源分槽</b>：热更入口有多条 —— 启动参数、宿主的 {@code init(...)}、UI 手动添加的
 * 路径。它们各自增删自己的那一份，谁都不该覆盖别人。聚合结果
 * （{@link RemoldAgent#activeWatchDirs} / {@link RemoldAgent#activeWatchJars}）才是监听器与
 * 类加载推断真正读的唯一真相源。</p>
 *
 * <p><b>聚合变化会重启监听器</b>：路径集合变了就必须重建 {@code WatchService}
 * （JDK 的注册关系不可迁移），因此 {@link #syncAggregated()} 在集合变化时才做重启；
 * 集合未变则只触发一轮热更（可能是内容变了而路径没变）。</p>
 *
 * <p><b>为什么独立成类</b>：本类只做"分槽记账 + 聚合"，不持有监听线程、不持有防抖队列。
 * 原先这些字段与解析逻辑埋在 {@link RemoldAgent} 的类体里，与对齐、布局门、重定义事务混在一起，
 * 改一次路径解析要重读整条流水线。</p>
 */
final class WatchPathRegistry {

	private WatchPathRegistry() { }

	/** 来源分槽映射表：sourceId -> 该来源下的待监控目录集合 */
	private static final Map<String, Set<Path>> watchDirSources = new ConcurrentHashMap<>();
	/** 来源分槽映射表：sourceId -> 该来源下的待监控 Jar 集合 */
	private static final Map<String, Set<Path>> watchJarSources = new ConcurrentHashMap<>();

	/** 只读视图：供 {@link RemoldAgent#isProjectLocation} 判定"这个 CodeSource 是否属于工程自身"。 */
	static Map<String, Set<Path>> dirSources() {
		return watchDirSources;
	}

	static Map<String, Set<Path>> jarSources() {
		return watchJarSources;
	}

	/** 获取指定来源的监控目录集合（只读不可变副本） */
	static Set<Path> dirs(String source) {
		Set<Path> s = watchDirSources.get(source);
		return s == null ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(s));
	}

	/** 获取指定来源的监控 Jar 集合（只读不可变副本） */
	static Set<Path> jars(String source) {
		Set<Path> s = watchJarSources.get(source);
		return s == null ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(s));
	}

	/** 获取当前所有来源的监控目录映射（只读不可变副本） */
	static Map<String, Set<Path>> allDirSources() {
		Map<String, Set<Path>> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Set<Path>> e : watchDirSources.entrySet()) {
			copy.put(e.getKey(), Collections.unmodifiableSet(new HashSet<>(e.getValue())));
		}
		return Collections.unmodifiableMap(copy);
	}

	/** 获取当前所有来源的监控 Jar 映射（只读不可变副本） */
	static Map<String, Set<Path>> allJarSources() {
		Map<String, Set<Path>> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Set<Path>> e : watchJarSources.entrySet()) {
			copy.put(e.getKey(), Collections.unmodifiableSet(new HashSet<>(e.getValue())));
		}
		return Collections.unmodifiableMap(copy);
	}

	/**
	 * 解析 agentArgs 形式的路径串：{@code pathSeparator} 分隔的多条路径，外加若干内联开关
	 * （{@code file_shake_ms=} / {@code shake=} / {@code fileShakeMs=}、{@code build_mode=} / {@code buildMode=}）。
	 *
	 * <p>解析完还会在非 {@code gradle} 构建模式下自动补 IDEA 的 {@code out/production} 目录：
	 * 两个构建系统的输出目录不同，用户通常只给一个，另一个靠探测。</p>
	 */
	static void parse(String agentArgs, Set<Path> outDirs, Set<Path> outJars) {
		for (String s : agentArgs.split(File.pathSeparator)) {
			if (s.trim().isEmpty()) continue;
			String trimmed = s.trim();
			if (trimmed.startsWith("file_shake_ms=") || trimmed.startsWith("shake=") || trimmed.startsWith("fileShakeMs=")) {
				try {
					int val = Integer.parseInt(trimmed.substring(trimmed.indexOf('=') + 1).trim());
					if (val >= 0) {
						FILE_SHAKE_MS = val;
						info("File Shake MS configured via agentArgs: " + FILE_SHAKE_MS);
					}
				} catch (NumberFormatException e) {
					warn("Invalid file_shake_ms in agentArgs: " + trimmed);
				}
				continue;
			}
			if (trimmed.startsWith("build_mode=") || trimmed.startsWith("buildMode=")) {
				String mode = trimmed.substring(trimmed.indexOf('=') + 1).trim();
				if (!mode.isEmpty()) {
					BUILD_MODE = mode.toLowerCase();
					info("Build mode configured via agentArgs: " + BUILD_MODE);
				}
				continue;
			}
			Path   p  = Paths.get(trimmed).toAbsolutePath();
			String ps = p.toString();
			if (Files.isDirectory(p)) {
				outDirs.add(p);
			} else if ((ps.endsWith(".jar") || ps.endsWith(".zip")) && Files.isRegularFile(p)) {
				outJars.add(p);
			} else if (ps.endsWith("out" + File.separator + "production") || ps.endsWith("out/production") || ps.endsWith("out\\production")) {
				if ("gradle".equalsIgnoreCase(BUILD_MODE)) {
					continue;
				}
				try {
					Files.createDirectories(p);
					outDirs.add(p);
				} catch (Throwable ignored) {
					error("Skipping invalid watch path (not a directory or jar/zip): " + p);
				}
			} else {
				error("Skipping invalid watch path (not a directory or jar/zip): " + p);
			}
		}

		// 自动探测并补充 IDEA 的 out/production 目录（gradle 构建模式下不自动探测）
		if (!"gradle".equalsIgnoreCase(BUILD_MODE)) {
			for (Path dir : new HashSet<>(outDirs)) {
				Path current = dir;
				while (current != null && current.getParent() != null) {
					Path outProd = current.resolve("out").resolve("production");
					if (Files.isDirectory(outProd)) {
						outDirs.add(outProd.toAbsolutePath().normalize());
						break;
					}
					if (current.getFileName() != null && current.getFileName().toString().equals("build")) {
						Path rootOutProd = current.getParent().resolve("out").resolve("production");
						if (Files.isDirectory(rootOutProd)) {
							outDirs.add(rootOutProd.toAbsolutePath().normalize());
						}
						break;
					}
					current = current.getParent();
				}
			}
		}
	}

	/** 整体替换某个来源的目录与 jar 集合（空集合等价于移除该来源），随后重新聚合。 */
	static void set(String source, Set<Path> dirs, Set<Path> jars) {
		if (dirs == null || dirs.isEmpty()) {
			watchDirSources.remove(source);
		} else {
			watchDirSources.put(source, new CopyOnWriteArraySet<>(dirs));
		}
		if (jars == null || jars.isEmpty()) {
			watchJarSources.remove(source);
		} else {
			watchJarSources.put(source, new CopyOnWriteArraySet<>(jars));
		}
		syncAggregated();
	}

	/** 移除某个来源的全部监控路径，随后重新聚合。 */
	static void remove(String source) {
		watchDirSources.remove(source);
		watchJarSources.remove(source);
		syncAggregated();
	}

	/**
	 * 把分槽集合聚合成"当前真正在监听"的目录/jar 集合。
	 *
	 * <p>集合发生变化 → 重建监听器（并重新扫一遍已加载类，因为新目录里可能已有旧字节）；
	 * 集合未变 → 只触发一轮热更（路径没变，但内容可能变了）。</p>
	 */
	private static void syncAggregated() {
		Set<Path> aggregatedDirs = new HashSet<>();
		for (Set<Path> s : watchDirSources.values()) {
			aggregatedDirs.addAll(s);
		}
		Set<Path> aggregatedJars = new HashSet<>();
		for (Set<Path> s : watchJarSources.values()) {
			aggregatedJars.addAll(s);
		}

		if (!activeWatchDirs.equals(aggregatedDirs) || !activeWatchJars.equals(aggregatedJars)) {
			info("Watch paths updated across sources:" + formatLog());
			activeWatchDirs.clear();
			activeWatchDirs.addAll(aggregatedDirs);
			activeWatchJars.clear();
			activeWatchJars.addAll(aggregatedJars);
			RemoldAgent.notifyWatchPathsChanged();
			if (RemoldAgent.inst != null) {
				RemoldAgent.initializeAgentState(RemoldAgent.inst.getAllLoadedClasses());
				FileWatcherService.restart();
			}
		} else {
			if (RemoldAgent.inst != null) {
				RemoldAgent.triggerHotswap();
			}
		}
	}

	private static String formatLog() {
		StringBuilder sb = new StringBuilder();
		Set<String> allSources = new TreeSet<>();
		allSources.addAll(watchDirSources.keySet());
		allSources.addAll(watchJarSources.keySet());
		if (allSources.isEmpty()) {
			return " (none)";
		}
		for (String src : allSources) {
			Set<Path> d = watchDirSources.getOrDefault(src, Collections.emptySet());
			Set<Path> j = watchJarSources.getOrDefault(src, Collections.emptySet());
			sb.append("\n  - [").append(src).append("] dirs=").append(d).append(" jars=").append(j);
		}
		return sb.toString();
	}
}
