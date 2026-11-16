package remold;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
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
import static remold.RemoldAgent.warn;


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

	//region Build-trigger marker watcher

	private static final Object triggerLock = new Object();
	private static volatile TriggerWatcher triggerWatcher;
	private static volatile Path triggerFile;
	private static final WatchEvent.Modifier[] EMPTY_MODIFIERS = new WatchEvent.Modifier[0];

	/**
	 * 启动/替换"构建完成 marker"的专用 watcher。
	 *
	 * <p>它与普通 class watcher 完全分离：不参与 {@link #restart()} 的路径集合重建，watch 路径变化
	 * 不会杀掉它；marker 父目录被删除（如 {@code clean}）时自行重试注册。</p>
	 *
	 * @param file marker 文件绝对路径；{@code null} 等价于停止
	 */
	static void startBuildTriggerWatcher(Path file) {
		Path normalized = file == null ? null : file.toAbsolutePath().normalize();
		synchronized (triggerLock) {
			if (normalized == null) {
				stopBuildTriggerWatcherLocked();
				return;
			}
			if (triggerWatcher != null && triggerWatcher.isAlive() && normalized.equals(triggerFile)) {
				return;
			}
			stopBuildTriggerWatcherLocked();
			try {
				TriggerWatcher watcher = new TriggerWatcher(normalized);
				watcher.setDaemon(true);
				watcher.setName("HotSwap-BuildTrigger-" + normalized.getFileName());
				watcher.start();
				triggerWatcher = watcher;
				triggerFile = normalized;
				info("[BUILD-TRIGGER] Watching marker file: " + normalized);
			} catch (IOException e) {
				error("[BUILD-TRIGGER] Failed to start marker watcher for: " + normalized, e);
			}
		}
	}

	/** 停止 marker watcher（无配置 / agent 关闭时）。 */
	static void stopBuildTriggerWatcher() {
		synchronized (triggerLock) {
			stopBuildTriggerWatcherLocked();
		}
	}

	private static void stopBuildTriggerWatcherLocked() {
		TriggerWatcher watcher = triggerWatcher;
		triggerWatcher = null;
		triggerFile = null;
		if (watcher != null) watcher.interrupt();
	}

	/**
	 * macOS 上 {@link WatchService} 的默认轮询灵敏度可能把 marker 到达延迟放大到秒级；
	 * 尽力使用 {@code com.sun.nio.file.SensitivityWatchEventModifier.HIGH}（反射探测）。
	 * 取不到就退回默认注册——失败开放，不因为平台修饰符不可用而不启动快路径。
	 */
	private static WatchEvent.Modifier[] highSensitivityModifiers() {
		String os = System.getProperty("os.name", "").toLowerCase();
		if (!os.contains("mac")) return EMPTY_MODIFIERS;
		try {
			Class<?> cls = Class.forName("com.sun.nio.file.SensitivityWatchEventModifier");
			Object high = cls.getField("HIGH").get(null);
			return new WatchEvent.Modifier[]{(WatchEvent.Modifier) high};
		} catch (Throwable t) {
			if (DEBUG) log("[BUILD-TRIGGER] macOS high-sensitivity watch modifier unavailable: " + t);
			return EMPTY_MODIFIERS;
		}
	}

	/**
	 * marker 专用 watcher：只认目标文件本身，其余事件一概忽略。
	 *
	 * <p>父目录不存在（首次构建前、{@code clean} 之后）时按约 1s 重试创建与注册；注册失败只告警一次，
	 * 不影响普通 class watcher。</p>
	 */
	private static final class TriggerWatcher extends Thread {
		private final Path target;

		TriggerWatcher(Path target) throws IOException {
			super("HotSwap-BuildTrigger-" + target.getFileName());
			this.target = target;
		}

		@Override
		public void run() {
			boolean warned = false;
			while (!Thread.currentThread().isInterrupted()) {
				WatchService watchService = null;
				boolean retry = false;
				try {
					Path dir = target.getParent();
					if (dir == null) {
						error("[BUILD-TRIGGER] Cannot determine parent directory of marker: " + target);
						return;
					}
					if (!Files.isDirectory(dir)) {
						Files.createDirectories(dir);
					}
					watchService = FileSystems.getDefault().newWatchService();
					WatchEvent.Kind<?>[] kinds = new WatchEvent.Kind<?>[]{
						StandardWatchEventKinds.ENTRY_CREATE,
						StandardWatchEventKinds.ENTRY_MODIFY
					};
					try {
						dir.register(watchService, kinds, highSensitivityModifiers());
					} catch (UnsupportedOperationException unsupported) {
						// 平台/文件系统不接受该修饰符：退回默认注册（失败开放，不放弃快路径）。
						dir.register(watchService, kinds);
					}
					warned = false;

					while (!Thread.currentThread().isInterrupted()) {
						WatchKey key;
						try {
							key = watchService.take();
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							return;
						} catch (ClosedWatchServiceException e) {
							retry = true;
							break;
						}
						Path watchedDir = (Path) key.watchable();
						for (WatchEvent<?> event : key.pollEvents()) {
							if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
							Path full = watchedDir.resolve((Path) event.context()).toAbsolutePath().normalize();
							if (!full.equals(target)) continue;
							try {
								RemoldAgent.handleBuildTriggerFile(full);
							} catch (Throwable t) {
								error("[BUILD-TRIGGER] Failed to process marker: " + full, t);
							}
						}
						if (!key.reset()) {
							retry = true;
							break;
						}
					}
					if (!retry) return;
				} catch (ClosedWatchServiceException e) {
					// 服务被关闭：按目录重建处理
				} catch (IOException | SecurityException e) {
					if (!warned) {
						warn("[BUILD-TRIGGER] Marker watcher unavailable at " + target
						     + " (" + e + "); retrying every 1s");
						warned = true;
					}
				} finally {
					if (watchService != null) {
						try {
							watchService.close();
						} catch (IOException ignored) {
						}
					}
				}
				try {
					Thread.sleep(1000L);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}
	//endregion

	/** 文件监控线程，这个类的设计本身就是可复用的 */
	private static class WatcherThread extends Thread {		private final Path         root;
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
