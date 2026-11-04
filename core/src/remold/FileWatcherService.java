package remold;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static remold.RemoldAgent.DEBUG;
import static remold.RemoldAgent.activeWatchDirs;
import static remold.RemoldAgent.activeWatchJars;
import static remold.RemoldAgent.error;
import static remold.RemoldAgent.handleAssetChange;
import static remold.RemoldAgent.handleFileChange;
import static remold.RemoldAgent.handleJarChange;
import static remold.RemoldAgent.info;
import static remold.RemoldAgent.isAssetFile;
import static remold.RemoldAgent.log;


/**
 * 文件系统侧：为每个监控目录 / jar 起一个 {@link WatchService} 线程，把事件转成"入队"调用。
 *
 * <p><b>本类不持有防抖队列</b>：监听到的事件一律交给
 * {@link RemoldAgent#handleFileChange} / {@link RemoldAgent#handleJarChange} /
 * {@link RemoldAgent#handleAssetChange} 入队，由防抖调度器决定何时真正热更。这样"发现变化"与
 * "决定何时处理"分离，监听线程不会被一轮长热更阻塞。</p>
 *
 * <p><b>jar 模式只盯目标文件</b>：直接传入的 jar（{@code activeWatchJars}）监听其父目录，
 * 但只响应该文件自身的事件——同目录里其它文件的变动不属于这个监控目标。</p>
 *
 * <p><b>为什么独立成类</b>：监控线程是长生命周期后台资源（注册关系、中断、关闭），
 * 与热更流水线的生命周期完全不同。混在 {@link RemoldAgent} 里时，"谁负责中断旧 watcher"
 * 只能靠读整段重启逻辑才能确定。</p>
 */
final class FileWatcherService {

	private FileWatcherService() { }

	private static final List<WatcherThread> activeWatchers = new CopyOnWriteArrayList<>();

	/** 中断并重建全部监听线程（路径集合变化时调用；JDK 的注册关系不可迁移）。 */
	static void restart() {
		activeWatchers.forEach(Thread::interrupt);
		activeWatchers.clear();

		for (Path dir : activeWatchDirs) {
			try {
				WatcherThread watcher = new WatcherThread(dir, null);
				watcher.setDaemon(true);
				watcher.start();
				activeWatchers.add(watcher);
			} catch (IOException e) {
				error("Failed to start watcher for: " + dir, e);
			}
		}

		// 直接传入的 jar/zip：监控其父目录，但只响应该文件本身的事件
		for (Path jar : activeWatchJars) {
			Path parentDir = jar.getParent();
			if (parentDir == null) {
				error("Cannot determine parent directory for jar: " + jar);
				continue;
			}
			try {
				WatcherThread watcher = new WatcherThread(parentDir, jar);
				watcher.setDaemon(true);
				watcher.start();
				activeWatchers.add(watcher);
				info("[Watch] Watching jar: " + jar);
			} catch (IOException e) {
				error("Failed to start watcher for jar: " + jar, e);
			}
		}
	}

	/** 文件监控线程，这个类的设计本身就是可复用的 */
	private static class WatcherThread extends Thread {
		private final Path         root;
		private final Path         specificJar; // 非 null 时只响应该文件的事件（直接传入的 jar 模式）
		private final WatchService watchService;

		WatcherThread(Path root, Path specificJar) throws IOException {
			super("HotSwap-FileWatcher-" + root.getFileName()
			      + (specificJar != null ? "[" + specificJar.getFileName() + "]" : ""));
			this.root = root;
			this.specificJar = specificJar != null ? specificJar.toAbsolutePath() : null;
			this.watchService = FileSystems.getDefault().newWatchService();
		}

		@Override
		public void run() {
			if (DEBUG) log("[Watch] File watcher started for: " + root);
			try {
				registerAll(root);
				while (!Thread.currentThread().isInterrupted()) {
					WatchKey key          = watchService.take();
					Path     triggeredDir = (Path) key.watchable();

					for (WatchEvent<?> event : key.pollEvents()) {
						if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

						Path context  = (Path) event.context();
						Path fullPath = triggeredDir.resolve(context);

						if (RemoldAgent.isWatchPathIgnored(fullPath)) {
							continue;
						}

						// specificJar 模式：只响应目标 jar 文件本身，忽略其他一切
						if (specificJar != null) {
							if (fullPath.toAbsolutePath().equals(specificJar)) {
								handleJarChange(fullPath);
							}
							continue;
						}

						// 处理普通类与资源文件变化
						String name = fullPath.toString();
						if (name.endsWith(".class")) {
							handleFileChange(fullPath);
						} else if (isAssetFile(fullPath)) {
							handleAssetChange(fullPath);
						}

						// 处理 jar / zip 包变化（仅监控 root 一级，子目录不处理）
						if ((name.endsWith(".jar") || name.endsWith(".zip")) && triggeredDir.equals(root)) {
							handleJarChange(fullPath);
						}

						// 处理新目录创建
						if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath)) {
							if (DEBUG) log("[WATCH] New directory detected: " + fullPath);

							registerAll(fullPath);

							// 立即扫描该目录下现有的 .class 与资源文件（jar/zip 仅在 root 一级处理，新子目录不扫）
							try (Stream<Path> subFiles = Files.walk(fullPath)) {
								subFiles.filter(Files::isRegularFile).forEach(p -> {
									String pName = p.toString();
									if (pName.endsWith(".class")) {
										if (DEBUG) log("[WATCH] Found existing file in new dir: " + p);
										handleFileChange(p);
									} else if (isAssetFile(p)) {
										if (DEBUG) log("[WATCH] Found existing asset in new dir: " + p);
										handleAssetChange(p);
									}
								});
							} catch (IOException e) {
								error("Failed to scan new directory: " + fullPath, e);
							}
						}
					}
					if (!key.reset()) {
						if (DEBUG) log("WatchKey no longer valid: " + triggeredDir);
					}
				}
			} catch (IOException e) {
				error("File watcher encountered an error in " + getName(), e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (UncheckedIOException e) {
				error("File watcher encountered an unchecked IO error", e);
			} finally {
				if (DEBUG) log("[Watch] File watcher stopped for: " + root);
				try {
					watchService.close();
				} catch (IOException e) {
					error("Error closing watch service for " + root, e);
				}
			}
		}

		private void registerAll(Path startDir) throws IOException {
			if (!Files.exists(startDir)) {
				if (DEBUG) log("Skipping registration for non-existent directory: " + startDir);
				return;
			}
			try (Stream<Path> stream = Files.walk(startDir)) {
				stream.filter(Files::isDirectory).forEach(dir -> {
					try {
						dir.register(watchService,
						 StandardWatchEventKinds.ENTRY_CREATE,
						 StandardWatchEventKinds.ENTRY_MODIFY);
					} catch (IOException e) {
						if (DEBUG) log("Failed to register directory: " + dir);
					}
				});
			}
		}
	}
}
