package remold;

import remold.util.CRC64;
import remold.util.Utils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static remold.MountManager.*;
import static remold.RemoldAgent.*;

/**
 * 文件变化受理、构建完成标记处理、防抖与启动门闩触发协调器（从 {@link RemoldAgent} 拆出）。
 */
final class AgentWatchCoordinator {

	private AgentWatchCoordinator() {}

	static void initializeAgentState(Class<?>[] classes) {
		info("Scanning files...");
		// 直接传入的 jar/zip 文件：立即解压扫描
		for (Path jar : activeWatchJars) {
			String stem = jar.getFileName().toString().replaceAll("[^a-zA-Z0-9_-]", "_");
			Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "remold-hotswap",
			 stem + "-" + Long.toHexString(CRC64.hashString(jar.toString())));
			List<Path> outOfSync = new ArrayList<>();
			try {
				Files.createDirectories(tmpDir);
				JarExtractor.tmpToJar().entrySet().removeIf(e -> e.getValue().equals(jar));
				try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(jar)))) {
					ZipEntry entry;
					while ((entry = zis.getNextEntry()) != null) {
						try {
							if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
								byte[] diskBytes = zis.readAllBytes();
								String cName     = Utils.getClassNameASM(diskBytes);
								if (cName == null || isBlacklisted(cName)) continue;
								long diskHash = calculateHash(diskBytes);
								Path outPath  = tmpDir.resolve(entry.getName().replace('/', File.separatorChar));
								Files.createDirectories(outPath.getParent());
								Files.write(outPath, diskBytes);
								JarExtractor.tmpToJar().put(outPath, jar);
								byte[] memBytes = bytecodeCache.get(cName);
								if (memBytes != null && calculateHash(memBytes) != diskHash) {
									if (DEBUG) log("[INIT-SYNC] " + cName + " (direct jar) out of sync.");
									outOfSync.add(outPath);
								}
								synchronized (fileDiskHashes) {
									fileDiskHashes.put(CRC64.hashString(cName), diskHash);
								}
							}
						} catch (Exception ignored) {
						} finally {
							zis.closeEntry();
						}
					}
				}
				if (!outOfSync.isEmpty()) pendingChanges.addAll(outOfSync);
			} catch (IOException e) {
				error("Failed to scan direct jar: " + jar, e);
			}
		}
		Map<String, Path> latestClassPaths = new HashMap<>();
		Map<String, byte[]> latestClassBytes = new HashMap<>();
		for (Path root : activeWatchDirs) {
			try (Stream<Path> walk = Files.walk(root)) {
				walk.filter(p -> {
					String s = p.toString().replace('\\', '/');
					if (isWatchPathIgnored(p)) return false;
					// jar/zip 只取 root 直属子文件（depth == 1），.class 全量递归
					if (s.endsWith(".jar") || s.endsWith(".zip")) return p.getParent().equals(root);
					return s.endsWith(".class");
				}).forEach(path -> {
					String ps = path.toString();
					if (ps.endsWith(".jar") || ps.endsWith(".zip")) {
						// 对 jar/zip：解压后做 hash 对比，out-of-sync 的类批量加入 pendingChanges
						Path   absJar = path.toAbsolutePath();
						String stem   = absJar.getFileName().toString().replaceAll("[^a-zA-Z0-9_-]", "_");
						Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "remold-hotswap",
						 stem + "-" + Long.toHexString(CRC64.hashString(absJar.toString())));
						List<Path> outOfSync = new ArrayList<>();
						try {
							Files.createDirectories(tmpDir);
							try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(absJar)))) {
								ZipEntry entry;
								while ((entry = zis.getNextEntry()) != null) {
									try {
										if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
											byte[] diskBytes = zis.readAllBytes();
											String cName     = Utils.getClassNameASM(diskBytes);
											if (cName == null || isBlacklisted(cName)) continue;
											long diskHash = calculateHash(diskBytes);

											byte[] memBytes = bytecodeCache.get(cName);
											if (memBytes != null && calculateHash(memBytes) != diskHash) {
												if (DEBUG) log("[INIT-SYNC] " + cName + " (in jar) is out of sync. Reloading...");
												Path outPath = tmpDir.resolve(entry.getName().replace('/', File.separatorChar));
												Files.createDirectories(outPath.getParent());
												Files.write(outPath, diskBytes);
												JarExtractor.tmpToJar().put(outPath, absJar);
												outOfSync.add(outPath);
											}
											synchronized (fileDiskHashes) {
												fileDiskHashes.put(CRC64.hashString(cName), diskHash);
											}
										}
									} catch (Exception ignored) {
									} finally {
										zis.closeEntry();
									}
								}
							}
							// 批量加入，只锁一次
							if (!outOfSync.isEmpty()) pendingChanges.addAll(outOfSync);
						} catch (IOException ignored) { }
						return;
					}
					// 原始 .class 文件逻辑：按类名聚合，并取最后修改时间最新者
					try {
						byte[] diskBytes = Files.readAllBytes(path);
						String cName     = Utils.getClassNameASM(diskBytes);
						if (cName == null || isBlacklisted(cName)) return;

						Path existing = latestClassPaths.get(cName);
						if (existing != null) {
							try {
								if (Files.getLastModifiedTime(path).compareTo(Files.getLastModifiedTime(existing)) <= 0) {
									return;
								}
							} catch (Throwable ignored) {
							}
						}
						latestClassPaths.put(cName, path);
						latestClassBytes.put(cName, diskBytes);
					} catch (Exception ignored) { }
				});
			} catch (IOException ignored) { }
		}

		// 统一对最新版本的 .class 进行哈希比对与落账
		for (Map.Entry<String, Path> entry : latestClassPaths.entrySet()) {
			String cName = entry.getKey();
			Path   path  = entry.getValue();
			byte[] diskBytes = latestClassBytes.get(cName);
			if (diskBytes == null) continue;

			try {
				long diskHash = calculateHash(diskBytes);
				byte[] memBytes = bytecodeCache.get(cName);
				if (memBytes != null) {
					long memHash = calculateHash(memBytes);
					if (diskHash != memHash) {
						if (DEBUG) log("[INIT-SYNC] " + cName + " is out of sync. Reloading...");
						pendingChanges.add(path);
					}
				}
				synchronized (fileDiskHashes) {
					fileDiskHashes.put(CRC64.hashString(cName), diskHash);
				}
			} catch (Exception ignored) { }
		}
		// 入参 classes 是调用方在 FileWatcherService.restart 之前取的快照，此刻可能已过期
		// （旧 watcher 排进 scheduler 的任务期间可能又有类被加载）。改走 triggerHotswap()，
		// 由它在锁内重新取一份新鲜快照。
		if (!pendingChanges.isEmpty() || !pendingAssetChanges.isEmpty()) triggerHotswap();
	}

	/**
	 * 判断文件是否为资源文件（贴图、字体、多语言包、配置文件、音频、着色器等）。
	 * 过滤掉隐藏文件、临时文件及字节码/压缩包。
	 */
	static boolean isAssetFile(Path path) {
		if (path == null) return false;
		Path fileName = path.getFileName();
		if (fileName == null) return false;
		String name = fileName.toString().toLowerCase();
		if (name.startsWith(".") || name.endsWith(".tmp") || name.endsWith(".swp") || name.endsWith(".bak") || name.endsWith("~")) {
			return false;
		}
		if (name.endsWith(".class") || name.endsWith(".jar") || name.endsWith(".zip")) {
			return false;
		}
		return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
		    || name.endsWith(".properties")
		    || name.endsWith(".json") || name.endsWith(".hjson")
		    || name.endsWith(".ogg") || name.endsWith(".wav") || name.endsWith(".mp3")
		    || name.endsWith(".frag") || name.endsWith(".vert") || name.endsWith(".glsl")
		    || name.endsWith(".fnt") || name.endsWith(".ttf") || name.endsWith(".woff")
		    || name.endsWith(".atlas") || name.endsWith(".csv") || name.endsWith(".txt");
	}

	static void handleAssetChange(Path changedAsset) {
		if (DEBUG) log("[ASSET] Queued asset change: " + changedAsset);
		synchronized (pendingChanges) {
			pendingAssetChanges.add(changedAsset);
			DEBOUNCE.onAsset(System.nanoTime());
		}
		rescheduleDebounce();
	}

	static void handleFileChange(Path changedFile) {
		synchronized (pendingChanges) {
			pendingChanges.add(changedFile);
			DEBOUNCE.onClass(System.nanoTime());
		}
		rescheduleDebounce();
	}

	/**
	 * 处理 jar/zip 变化：与 .class 共用 Class 流的静默窗口，防止读到写了一半的 jar。
	 * 真正的解压在 Class 批次到期后由 {@link JarExtractor#extract} 完成。
	 */
	static void handleJarChange(Path jarPath) {
		if (DEBUG) log("[JAR] Queued for extraction: " + jarPath);
		synchronized (pendingChanges) {
			pendingJars.add(jarPath.toAbsolutePath());
			DEBOUNCE.onClass(System.nanoTime());
		}
		rescheduleDebounce();
	}

	//region Build-trigger marker handling

	/** 解析 {@code -Dremold.agent.build_trigger_file}；空值 / false / off 视为未配置。 */
	static Path parseBuildTriggerFile(String primary, String alias) {
		String raw = primary != null ? primary : alias;
		if (raw == null) return null;
		raw = raw.trim();
		if (raw.isEmpty() || "false".equalsIgnoreCase(raw) || "off".equalsIgnoreCase(raw)) return null;
		try {
			return Paths.get(raw).toAbsolutePath().normalize();
		} catch (Throwable t) {
			warn("[BUILD-TRIGGER] Invalid build_trigger_file (ignored): " + raw + " (" + t + ")");
			return null;
		}
	}

	/**
	 * 构建完成 marker 的处理入口（由 trigger watcher 线程调用）。
	 *
	 * <p><b>语义</b>（契约见 {@code docs/runtime/01-build-trigger-mode.md} §3）：</p>
	 * <ol>
	 *   <li>解析 / 拒绝 / 去重；无效 marker <b>不推进</b>任何已处理状态；</li>
	 *   <li>按 {@code mtime >= 上次处理点 − 余量} 扫描 watch 目录，与已入队的事件合并；</li>
	 *   <li>class/jar 队列均非空时调用 {@link DebounceWindow#markClassDueNow(long)}（sticky），
	 *       并重挂 0 延迟调度——该调用同时取消尚未到期的兜底 task；</li>
	 *   <li>队列都为空时不标 due、不触发（避免 0 延迟自旋）。</li>
	 * </ol>
	 *
	 * <p>本方法不获取 {@code HOTSWAP_LOCK}：它只操作防抖队列与调度器，真正执行批次的
	 * {@link #triggerDueHotswap()} 仍走既有锁路径。</p>
	 */
	static void handleBuildTriggerFile(Path changed) {
		Path expected = BUILD_TRIGGER_FILE;
		if (expected == null || changed == null) return;
		Path marker = changed.toAbsolutePath().normalize();
		if (!marker.equals(expected)) return;

		byte[] raw;
		try {
			if (Files.size(marker) > BuildTriggerMarker.MAX_BYTES) {
				warn("[BUILD-TRIGGER] Ignored marker " + marker + ": exceeds "
				     + BuildTriggerMarker.MAX_BYTES + " bytes");
				return;
			}
			raw = Files.readAllBytes(marker);
		} catch (IOException | SecurityException e) {
			warn("[BUILD-TRIGGER] Cannot read marker " + marker + ": " + e);
			return;
		}

		long nowMillis = System.currentTimeMillis();
		BuildTriggerMarker.Parsed parsed = BuildTriggerMarker.parse(raw, nowMillis, AGENT_START_MILLIS);
		if (!parsed.valid) {
			warn("[BUILD-TRIGGER] Ignored marker " + marker + ": " + parsed.rejectReason);
			return;
		}

		boolean duplicate = parsed.buildId != null
		                    ? parsed.buildId.equals(lastProcessedBuildId)
		                    : parsed.endMillis == lastProcessedBuildEndMillis;
		if (duplicate) {
			if (DEBUG) {
				log("[BUILD-TRIGGER] Duplicate marker ignored: build_id=" + parsed.buildId
				    + ", end=" + parsed.endMillis);
			}
			return;
		}

		// 首个 marker 用 agent 启动点作为下界（含余量吸收 mtime 精度）；之后的 marker 用上一次
		// 被接受的结束点作为严格下界——若再减余量，会把上一次构建已经处理过的文件重新扫进来，
		// 让每次连续构建都多出一个 no-op 批次。
		long since = lastProcessedBuildEndMillis > 0L
		             ? lastProcessedBuildEndMillis
		             : AGENT_START_MILLIS - BUILD_TRIGGER_LOOKBACK_MILLIS;
		BuildTriggerSweep.Result swept = BuildTriggerSweep.collect(
			activeWatchDirs, activeWatchJars, since, RemoldAgent::isWatchPathIgnored);
		if (swept.size() > BUILD_TRIGGER_SWEEP_WARN) {
			warn("[BUILD-TRIGGER] Sweep found " + swept.size() + " changed file(s) since " + since
			     + "ms; this batch may be large");
		}

		// 只有被接受的 marker 才推进去重键与扫描下界（无效 marker 已在上方返回）。
		lastProcessedBuildEndMillis = parsed.endMillis;
		lastProcessedBuildId = parsed.buildId;

		boolean hasWork;
		int pendingClassCount;
		int pendingJarCount;
		synchronized (pendingChanges) {
			pendingChanges.addAll(swept.classes);
			pendingJars.addAll(swept.jars);
			pendingClassCount = pendingChanges.size();
			pendingJarCount = pendingJars.size();
			hasWork = !pendingChanges.isEmpty() || !pendingJars.isEmpty();
			if (hasWork) {
				// sticky due：构建完成之后的迟到 class 事件不得把 deadline 推回兜底窗口。
				DEBOUNCE.markClassDueNow(System.nanoTime());
			}
		}

		if (!hasWork) {
			if (DEBUG) {
				log("[BUILD-TRIGGER] Marker accepted (build_id=" + parsed.buildId + ", task=" + parsed.task
				    + ", swept=" + swept.size() + ") but no pending class/jar changes; nothing to trigger");
			}
			return;
		}

		info("[BUILD-TRIGGER] Triggering immediately: build_id=" + parsed.buildId
		     + ", task=" + parsed.task
		     + ", end=" + parsed.endMillis
		     + ", lag=" + Math.max(0L, nowMillis - parsed.endMillis) + "ms"
		     + ", swept=" + swept.size() + " (classes=" + swept.classes.size()
		     + ", jars=" + swept.jars.size() + ")"
		     + ", pending=" + pendingClassCount + " class(es), " + pendingJarCount + " jar(s)"
		     + "; fallback window " + FILE_SHAKE_MS + "ms canceled for this batch");
		// cancel 尚未到期的兜底 task，并按 sticky due 立刻重挂（delay = 0）。
		rescheduleDebounce();
	}

	//endregion

	/** 按 {@link DebounceWindow} 的最早 deadline 重新挂调度。 */
	private static void rescheduleDebounce() {		synchronized (pendingChanges) {
			rescheduleDebounceLocked();
		}
	}

	private static void rescheduleDebounceLocked() {
		long delay = DEBOUNCE.nextDelayNanos(System.nanoTime());
		if (scheduledTask != null && !scheduledTask.isDone()) {
			scheduledTask.cancel(false);
		}
		if (delay < 0) {
			scheduledTask = null;
			return;
		}
		scheduledTask = RemoldAgent.scheduler.schedule(AgentWatchCoordinator::triggerDueHotswap, delay, TimeUnit.NANOSECONDS);
	}

	/**
	 * 启动就绪门闩判定：{@code true} = 允许本批进入批次执行。
	 *
	 * <p><b>就绪来源</b>（core 不认识任何加载器/游戏专属概念）：</p>
	 * <ul>
	 *   <li>插件投票：{@link HotswapPlugin#isHostReady()} 任一返回 {@code false} 即未就绪；</li>
	 *   <li>{@link StartupGate} 的 PENDING → READY 一次性变迁（全部插件就绪的首个观测点）；</li>
	 *   <li>可选超时放行：{@code -Dremold.agent.ready_timeout_ms}（默认 {@code 0} = 不放行），
	 *       只用于"宿主插件永远不报就绪"这种死等，且日志必须标明是超时放行。</li>
	 * </ul>
	 *
	 * <p>调用方必须持有 {@code HOTSWAP_LOCK}；本方法内部只进 {@code synchronized(pendingChanges)}
	 * （锁序正确：先全局锁、后队列锁）。</p>
	 */
	private static boolean startupGateAllowsBatch() {
		StartupGate gate = STARTUP_GATE;
		long       now   = System.nanoTime();
		String     blocker = firstPluginNotReady();

		if (blocker != null) {
			// 插件明确说未就绪（宿主仍在启动期）：门闩保持 PENDING，一个批次都不落。
			// 唯一的出口是显式配置的超时放行——默认 0 表示宁可押着，也不要在启动风暴里落批次。
			// 注意：门闩此前已经 READY（启动期已过）时 isReadyAt 也返回 true，但那种情况
			// **不是**超时放行，插件的否票仍然生效（fail-closed），否则运行期插件回退就失去意义。
			boolean gateOpen = gate.isReadyAt(now);
			if (gateOpen && gate.isTimedOutRelease()) {
				logGateReadyOnce(true, blocker, gate);
				return true;
			}
			int classPending;
			int assetPending;
			synchronized (pendingChanges) {
				classPending = pendingChanges.size() + pendingJars.size();
				assetPending = pendingAssetChanges.size();
			}
			if (classPending == 0 && assetPending == 0) {
				// 没有待处理变更：没什么可推迟的，不挂重查（下一次入队会重新挂防抖）。
				return false;
			}
			if (!gateBlockLogged) {
				gateBlockLogged = true;
				warn("[READY-GATE] hot-swap batches deferred: plugin '" + blocker + "' reports isHostReady()=false; "
				     + classPending + " class/jar change(s) and " + assetPending + " asset change(s) stay queued"
				     + (gate.getTimeoutMillis() > 0
				         ? " (ready_timeout_ms=" + gate.getTimeoutMillis() + ")"
				         : " (ready_timeout_ms=0: no timeout release)")
				     + (gateOpen ? " [startup gate already READY; the plugin veto is honored]" : "")
				     + ". Further deferrals of this same batch are not logged again.");
			}
			rescheduleStartupGateRetry();
			return false;
		}

		// 全部插件就绪：首个观测点把门闩翻到 READY，此后不再回退（启动期是一次性窗口）。
		if (gate.markReady()) {
			logGateReadyOnce(false, null, gate);
		}
		if (!gate.isReady()) {
			// 防御：门闩没开就不放行。
			rescheduleStartupGateRetry();
			return false;
		}
		return true;
	}

	/** 返回第一个明确报告未就绪的插件名；全部就绪返回 {@code null}。 */
	private static String firstPluginNotReady() {
		for (HotswapPlugin plugin : plugins) {
			try {
				if (!plugin.isHostReady()) return plugin.getName();
			} catch (Throwable t) {
				// fail-closed：判定不了就按未就绪处理。细节走 DEBUG 门控（避免按重查节奏刷屏），
				// 阻断日志（warn）已经点名是哪个插件，信息不丢。
				log("[READY-GATE] plugin " + plugin.getName()
				    + " threw from isHostReady(); treating it as not ready: " + t);
				return plugin.getName();
			}
		}
		return null;
	}

	/** 就绪状态变化只打一条：{@code PENDING → READY} 或超时放行各一次。 */
	private static void logGateReadyOnce(boolean byTimeout, String blocker, StartupGate gate) {
		if (gateReadyLogged) return;
		gateReadyLogged = true;
		// 新的一次就绪允许下一段阻断重新打点，否则运行期再次未就绪时会静默。
		gateBlockLogged = false;
		if (byTimeout) {
			warn("[READY-GATE] ready_timeout_ms=" + gate.getTimeoutMillis()
			     + " elapsed while plugin '" + blocker + "' still reports isHostReady()=false — "
			     + "releasing the startup gate by timeout; batches resume even though the host may still be booting.");
		} else {
			info("[READY-GATE] host reported ready (all plugins return isHostReady()=true) —"
			     + " class/asset batches are enabled");
		}
	}

	/**
	 * 门闩未放行时的重查挂载：固定间隔（不小于 {@link #GATE_RETRY_MIN_MS}），
	 * 绝不复用 {@link #rescheduleDebounce()}——防抖已到期时它的延迟是 0，会变成锁内自旋。
	 */
	private static void rescheduleStartupGateRetry() {
		long retryMillis = Math.max(GATE_RETRY_MIN_MS, FILE_SHAKE_MS);
		synchronized (pendingChanges) {
			if (scheduledTask != null && !scheduledTask.isDone()) {
				scheduledTask.cancel(false);
			}
			scheduledTask = RemoldAgent.scheduler.schedule(AgentWatchCoordinator::triggerDueHotswap, retryMillis, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * 防抖到期入口：只处理已经静默到期的流。资源流到期时绝不提前 drain 尚未静默的
	 * Class/jar 队列；Class 流到期时也只在同一个批次里处理 Class + jar。
	 *
	 * <p><b>唯一批次入口</b>：启动就绪门闩在这里生效——宿主未就绪时<b>只</b>把变更留在
	 * {@code pendingChanges} / {@code pendingAssetChanges} 里，绝不进入 {@link #runClassBatch}
	 * 或 {@link #processChanges}（后者硬性要求持有 {@code HOTSWAP_LOCK}）。</p>
	 */
	static void triggerDueHotswap() {
		if (inst == null) {
			return;
		}
		HOTSWAP_LOCK.lock();
		try {
			// 门闩在 drain 之前：未就绪时一条变更都不许被取走，否则"留在队列里"是空话。
			if (!startupGateAllowsBatch()) {
				return;
			}
			Set<Path> assets = null;
			Set<Path> jars = null;
			boolean classDue;
			boolean assetDue;
			synchronized (pendingChanges) {
				long now = System.nanoTime();
				classDue = DEBOUNCE.isClassDue(now)
					&& (!pendingChanges.isEmpty() || !pendingJars.isEmpty());
				assetDue = DEBOUNCE.isAssetDue(now) && !pendingAssetChanges.isEmpty();
				if (assetDue) {
					assets = new HashSet<>(pendingAssetChanges);
					pendingAssetChanges.clear();
					DEBOUNCE.clearAsset();
				}
				if (classDue) {
					jars = new HashSet<>(pendingJars);
					pendingJars.clear();
					// 先不清 Class pending：jar 解压可能向 pendingChanges 追加类文件，
					// 这些也是本批的一部分；最终 drain 放在解压之后。
				}
			}
			if (assets != null) {
				notifyAssetChanges(assets);
			}
			if (!classDue) {
				rescheduleDebounce();
			}
			if (classDue) {
				if (jars != null) {
					try {
						jars.forEach(JarExtractor::extract);
					} catch (Throwable t) {
						error("[HOTSWAP] Jar extraction failed; the current class batch will still be processed: " + t);
					}
				}
				Set<Path> changes;
				synchronized (pendingChanges) {
					changes = new HashSet<>(pendingChanges);
					pendingChanges.clear();
					// 解压期间若又有新的 jar 事件，留给下一批，不要把 Class 流标记为已处理。
					if (pendingJars.isEmpty()) {
						DEBOUNCE.clearClass();
					}
					rescheduleDebounceLocked();
				}
				runClassBatch(changes);
			}
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	/**
	 * 执行一批已经静默的 Class 变更；错误边界与手动触发保持一致。
	 *
	 * <p><b>看门狗不在这里</b>：{@link BatchWatchdog} 挂在 {@link #processChanges} 上，
	 * 这样手动 {@link #triggerHotswap()} 等入口也能覆盖。本方法<b>不得</b>再独立
	 * {@code arm} 一个看门狗——那会让同一批次出现两条 {@code [HOTSWAP-STALL]}
	 * （一次批次两次 dump = 前缀被训练成噪声）。这里只保留错误边界与批次统计的清理。</p>
	 */
	private static void runClassBatch(Set<Path> changes) {
		try {
			resetLiveInstanceCache();
			if (!changes.isEmpty()) {
				processChanges(changes, inst.getAllLoadedClasses());
			}
		} catch (Throwable t) {
			error("[HOTSWAP] Hot-swap pipeline aborted: redefine and post-processing are not atomic,"
			      + " so classes may have been redefined WITHOUT InitFix patches or @OnReload callbacks."
			      + " The next save re-runs from the current baseline; if it keeps failing, restart.", t);
		} finally {
			BatchReportScope.discard();
		}
	}

	static void triggerHotswapWith(Class<?>[] classes) {
		// 锁不变量：任何进入 processChanges 的路径都必须持有 HOTSWAP_LOCK。
		// 用抛异常而非 assert —— assert 默认关闭（需 -ea），而这条不变量一旦被破坏就是
		// 静默的并发数据竞争，必须无条件暴露。将来有人新增绕过锁的入口时会立刻在这里炸掉。
		if (!HOTSWAP_LOCK.isHeldByCurrentThread()) {
			throw new IllegalStateException(
			 "triggerHotswapWith called without HOTSWAP_LOCK on thread "
			 + Thread.currentThread().getName()
			 + " — all hot-swap entry points must acquire HOTSWAP_LOCK first");
		}

		// 并发探针：测试用，记录同时处于热更流程内的线程数及其历史峰值。
		// 不加锁时应观察到峰值 2（scheduler 一轮 + 按钮线程一轮），加锁后恒为 1。
		// getAndAccumulate 本身是原子的，无需再补一次 set。
		int concurrent = CONCURRENT_HOTSWAP.incrementAndGet();
		CONCURRENT_HOTSWAP_PEAK.getAndAccumulate(concurrent, Math::max);
		try {
			triggerHotswapWith0(classes);
		} finally {
			CONCURRENT_HOTSWAP.decrementAndGet();
		}
	}

	/**
	 * 一轮热更的统一错误边界。
	 *
	 * <p><b>为什么必须有</b>：本方法由防抖调度器以 {@code RemoldAgent.scheduler.schedule(RemoldAgent::triggerDueHotswap, ...)}
	 * 调用，抛出的一切都会被 {@link java.util.concurrent.Future} 静默吞掉——没有任何线程的
	 * uncaught handler 会看到它。而 {@code processChanges} 的后处理（InitFix 补丁、{@code @OnReload}
	 * 回调、缓存驱逐）排在 {@code redefineClasses} 成功之后：那里一旦抛异常，用户看到的就是
	 * 日志里"HotSwap successful"之后<b>再无下文</b>——新增字段停在 0、回调不触发，且毫无线索。</p>
	 *
	 * <p>所以这里兜住 {@link Throwable} 并显式 {@code error(...)}：宁可让这一轮失败得大声，
	 * 也不要让它失败得像成功。</p>
	 */
	private static void triggerHotswapWith0(Class<?>[] classes) {
		try {
			triggerHotswapWith0Unchecked(classes);
		} catch (Throwable t) {
			error("[HOTSWAP] Hot-swap pipeline aborted: redefine and post-processing are not atomic,"
			      + " so classes may have been redefined WITHOUT InitFix patches or @OnReload callbacks."
			      + " The next save re-runs from the current baseline; if it keeps failing, restart.", t);
		} finally {
			// 异常路径不会走到 processChanges 末尾的 finish；这里兜底清掉 ThreadLocal，
			// 防止下一轮批次把上一轮的半截统计混进来。
			BatchReportScope.discard();
		}
	}

	private static void triggerHotswapWith0Unchecked(Class<?>[] classes) {
		// 手动/强制入口：取消挂起的防抖调度并清空两条流的状态，立即处理全部队列。
		synchronized (pendingChanges) {
			if (scheduledTask != null && !scheduledTask.isDone()) {
				scheduledTask.cancel(false);
			}
			scheduledTask = null;
			DEBOUNCE.clearAll();
		}
		// 实例存活状况每轮都可能变（对象被创建/回收），缓存必须按轮清空
		resetLiveInstanceCache();
		// 先把所有待处理的 jar 解压（防抖已结束，文件写入完毕）
		Set<Path> jars;
		synchronized (pendingChanges) {
			jars = new HashSet<>(pendingJars);
			pendingJars.clear();
		}
		jars.forEach(JarExtractor::extract);

		Set<Path> changes;
		Set<Path> assetChanges;
		synchronized (pendingChanges) {
			if (pendingChanges.isEmpty() && pendingAssetChanges.isEmpty()) return;
			changes = new HashSet<>(pendingChanges);
			pendingChanges.clear();
			assetChanges = new HashSet<>(pendingAssetChanges);
			pendingAssetChanges.clear();
		}
		if (!assetChanges.isEmpty()) {
			notifyAssetChanges(assetChanges);
		}
		if (!changes.isEmpty()) {
			processChanges(changes, classes);
		}
	}

	/** 对外api，触发热更新 */
	static void triggerHotswap() {
		if (inst == null) return;
		HOTSWAP_LOCK.lock();
		try {
			// 快照必须在锁内取：排队等待期间 getAllLoadedClasses() 的结果会过期
			triggerHotswapWith(inst.getAllLoadedClasses());
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}
}
