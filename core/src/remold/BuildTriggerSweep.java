package remold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 构建完成后的"最近变更"扫描（纯函数，无状态）。
 *
 * <p>marker 与文件系统事件是两条独立通道：marker 到达时，同一次构建产生的 {@code .class} 事件
 * 可能还没被 watcher 线程取出。只依赖事件会让快路径偶发空批次并退回兜底窗口；本类按
 * {@code mtime >= since} 扫描 watch 目录，把"本批包含谁"变成不依赖事件送达的确定事实。</p>
 *
 * <p><b>口径</b>：{@code .class} 递归收集；{@code .jar}/{@code .zip} 只取 watch 根目录直属文件
 * （与 {@link FileWatcherService} 的 jar 事件口径一致），再加上 {@code activeWatchJars} 直接传入的 jar。
 * 遵守 {@link RemoldAgent#isWatchPathIgnored(Path)} 的忽略谓词；重叠目录按绝对路径去重。</p>
 *
 * <p>本类只做过滤与收集，不读文件内容、不写任何队列；入队与触发由 {@link RemoldAgent} 负责。</p>
 */
final class BuildTriggerSweep {

	private BuildTriggerSweep() {}

	static final class Result {
		final List<Path> classes;
		final List<Path> jars;

		Result(List<Path> classes, List<Path> jars) {
			this.classes = Collections.unmodifiableList(classes);
			this.jars    = Collections.unmodifiableList(jars);
		}

		boolean isEmpty() {
			return classes.isEmpty() && jars.isEmpty();
		}

		int size() {
			return classes.size() + jars.size();
		}
	}

	/**
	 * 收集 {@code mtime >= sinceMillis} 的 Class/jar 文件。
	 *
	 * @param watchDirs  当前监听的目录集合
	 * @param directJars 直接传入的 jar 集合（{@code activeWatchJars}）
	 * @param sinceMillis 绝对毫秒下界（含）；由调用方按"上次处理点 / agent 启动点 − 余量"计算
	 * @param ignored     路径忽略谓词（插件扩展可能排除某些目录）
	 */
	static Result collect(Set<Path> watchDirs, Set<Path> directJars, long sinceMillis, Predicate<Path> ignored) {
		Set<Path> classes = new LinkedHashSet<>();
		Set<Path> jars = new LinkedHashSet<>();

		if (watchDirs != null) {
			for (Path dir : watchDirs) {
				if (dir == null) continue;
				Path root = dir.toAbsolutePath().normalize();
				if (!Files.isDirectory(root)) continue;
				try (Stream<Path> walk = Files.walk(root)) {
					walk.filter(Files::isRegularFile).forEach(path -> {
						try {
							Path abs = path.toAbsolutePath().normalize();
							if (ignored != null && ignored.test(abs)) return;
							String name = abs.getFileName() == null
							               ? "" : abs.getFileName().toString().toLowerCase();
							boolean isClass = name.endsWith(".class");
							boolean isArchive = name.endsWith(".jar") || name.endsWith(".zip");
							if (!isClass && !isArchive) return;
							if (isArchive) {
								// jar/zip 与 watcher 口径一致：只看 watch 根目录直属文件。
								Path parent = abs.getParent();
								if (parent == null || !parent.equals(root)) return;
							}
							if (!isRecent(abs, sinceMillis)) return;
							if (isClass) classes.add(abs); else jars.add(abs);
						} catch (IOException | SecurityException ignoredEx) {
							// 单个文件不可读/时间戳不可取：跳过，不中断整轮扫描。
						}
					});
				} catch (IOException | SecurityException ignoredDir) {
					// 目录已被删除（如 clean）：跳过该目录。
				}
			}
		}

		if (directJars != null) {
			for (Path jar : directJars) {
				if (jar == null) continue;
				try {
					Path abs = jar.toAbsolutePath().normalize();
					if (!Files.isRegularFile(abs)) continue;
					if (ignored != null && ignored.test(abs)) continue;
					if (!isRecent(abs, sinceMillis)) continue;
					jars.add(abs);
				} catch (IOException | SecurityException ignoredEx) {
					// 跳过。
				}
			}
		}

		return new Result(new ArrayList<>(classes), new ArrayList<>(jars));
	}

	private static boolean isRecent(Path path, long sinceMillis) throws IOException {
		FileTime time = Files.getLastModifiedTime(path);
		return time.toMillis() >= sinceMillis;
	}
}
