package remold;

import remold.profiler.DynamicProfilerAPI;
import remold.ref.HotswapBridge;
import remold.util.ClassIdentity;
import remold.util.LibTool;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.instrument.ClassFileTransformer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static remold.MountManager.*;

import remold.RemoldAgent.CacheWarmingTransformer;
import remold.RemoldAgent.RedefineMode;

import static remold.RemoldAgent.ANON_ALIGN;
import static remold.RemoldAgent.ANON_DEBUG;
import static remold.RemoldAgent.ANON_LAYOUT_GATE;
import static remold.RemoldAgent.ANON_STRICT;
import static remold.RemoldAgent.ASSET_SHAKE_MS;
import static remold.RemoldAgent.BUILD_TRIGGER_FILE;
import static remold.RemoldAgent.DEBOUNCE;
import static remold.RemoldAgent.DEBUG;
import static remold.RemoldAgent.DIFF_LOG;
import static remold.RemoldAgent.ENABLE_HOTSWAP_EVENT;
import static remold.RemoldAgent.FILE_SHAKE_MS;
import static remold.RemoldAgent.FORCE_REINIT;
import static remold.RemoldAgent.FORCE_STATIC_LAMBDA;
import static remold.RemoldAgent.HOTSWAP_BLACKLIST;
import static remold.RemoldAgent.HOTSWAP_LOCK;
import static remold.RemoldAgent.INIT_FIX;
import static remold.RemoldAgent.INTERNAL_AFFECTED_PASSTHROUGH;
import static remold.RemoldAgent.LAMBDA_ALIGN;
import static remold.RemoldAgent.LAMBDA_STRICT;
import static remold.RemoldAgent.LAYOUT_GATE;
import static remold.RemoldAgent.LOCAL_CLASS_GUARD;
import static remold.RemoldAgent.MIXIN_MEMBER_ALIGN;
import static remold.RemoldAgent.PERF_LOG;
import static remold.RemoldAgent.REDEFINE_MODE;
import static remold.RemoldAgent.RETRANSFORM_LOADED;
import static remold.RemoldAgent.SOURCE_RUNTIME;
import static remold.RemoldAgent.TRAILING_OBSERVERS;
import static remold.RemoldAgent.TRAILING_OBSERVERS_ARMED;
import static remold.RemoldAgent.TWO_PHASE;
import static remold.RemoldAgent.UI_HOOK;
import static remold.RemoldAgent.activeWatchDirs;
import static remold.RemoldAgent.activeWatchJars;
import static remold.RemoldAgent.appendSelfToBootstrapSearch;
import static remold.RemoldAgent.boolProp;
import static remold.RemoldAgent.degradationReason;
import static remold.RemoldAgent.degradeForMissingEnhancedRedefinition;
import static remold.RemoldAgent.error;
import static remold.RemoldAgent.info;
import static remold.RemoldAgent.inst;
import static remold.RemoldAgent.intProp;
import static remold.RemoldAgent.intPropAsset;
import static remold.RemoldAgent.isEnhancedHotswapEnabled;
import static remold.RemoldAgent.log;
import static remold.RemoldAgent.plugins;
import static remold.RemoldAgent.registerPlugin;
import static remold.RemoldAgent.retransformLoaded;
import static remold.RemoldAgent.transformer;
import static remold.RemoldAgent.triggerPluginInit;
import static remold.RemoldAgent.warn;

/**
 * Agent 启动引导、监控路径与配置初始化及插件发现装载器（从 {@link RemoldAgent} 拆出）。
 */
final class RemoldAgentBootstrap {

	private RemoldAgentBootstrap() {}

	static void init(String agentArgs, boolean reinit) {
		init(SOURCE_RUNTIME, agentArgs, reinit);
	}

	static void init(String source, String agentArgs, boolean reinit) {
		// 整体持锁：本方法包含 retransformLoaded / initializeAgentState / FileWatcherService.restart，
		// 而旧 watcher 排进 scheduler 的任务可能尚未跑完。可重入，末尾 triggerHotswap() 嵌套获取无碍。
		HOTSWAP_LOCK.lock();
		try {
			init0(source, agentArgs, reinit);
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	private static void init0(String source, String agentArgs, boolean reinit) {
		initConfig();
		if (inst != null) {
			appendSelfToBootstrapSearch(inst);
		}
		LibTool.tryInit();

		initTransformer();
		var loadedClasses = inst != null ? inst.getAllLoadedClasses() : new Class<?>[0];
		refreshPackageLoaders(loadedClasses);

		if (RETRANSFORM_LOADED && inst != null) {
			retransformLoaded(loadedClasses);
		}

		for (HotswapPlugin plugin : plugins) {
			triggerPluginInit(plugin);
		}

		if (source == null || source.trim().isEmpty()) {
			source = SOURCE_RUNTIME;
		}

		Set<Path> newWatchDirs = new HashSet<>();
		Set<Path> newWatchJars = new HashSet<>();
		if (agentArgs != null && !agentArgs.trim().isEmpty()) {
			WatchPathRegistry.parse(agentArgs, newWatchDirs, newWatchJars);
		}

		if (newWatchDirs.isEmpty() && newWatchJars.isEmpty()) {
			if (activeWatchDirs.isEmpty() && activeWatchJars.isEmpty()) {
				error("No valid watch paths provided.");
			}
			return;
		}

		WatchPathRegistry.set(source, newWatchDirs, newWatchJars);
	}

	/**
	 * 设置指定来源的监控路径（支持分号或冒号分隔的目录或 Jar 路径）。
	 * 若 paths 为空，则移除该来源对应的监控路径。
	 *
	 * <p>锁纪律：路径变更会重建监听器并重扫已加载类，必须与热更互斥，因此统一在此持
	 * {@link RemoldAgent#HOTSWAP_LOCK}；分槽记账与聚合见 {@link WatchPathRegistry}。</p>
	 *
	 * @param source 来源标识符（如 "cli", "mod-tools", "runtime" 等）
	 * @param paths  待监控路径（支持分号或冒号分隔）
	 */
	static void setWatchPaths(String source, String paths) {
		HOTSWAP_LOCK.lock();
		try {
			if (source == null || source.trim().isEmpty()) {
				source = SOURCE_RUNTIME;
			}
			if (paths == null || paths.trim().isEmpty()) {
				WatchPathRegistry.remove(source);
				return;
			}
			Set<Path> dirs = new HashSet<>();
			Set<Path> jars = new HashSet<>();
			WatchPathRegistry.parse(paths, dirs, jars);
			WatchPathRegistry.set(source, dirs, jars);
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	/**
	 * 设置指定来源的目录与 Jar 路径集合。
	 */
	static void setWatchPaths(String source, Collection<Path> dirs, Collection<Path> jars) {
		HOTSWAP_LOCK.lock();
		try {
			if (source == null || source.trim().isEmpty()) {
				source = SOURCE_RUNTIME;
			}
			WatchPathRegistry.set(source, dirs != null ? new HashSet<>(dirs) : Collections.emptySet(),
			                      jars != null ? new HashSet<>(jars) : Collections.emptySet());
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	/**
	 * 向指定来源追加监控路径。
	 */
	static void addWatchPaths(String source, String paths) {
		HOTSWAP_LOCK.lock();
		try {
			if (source == null || source.trim().isEmpty()) {
				source = SOURCE_RUNTIME;
			}
			if (paths == null || paths.trim().isEmpty()) return;
			Set<Path> dirs = new HashSet<>(getWatchDirs(source));
			Set<Path> jars = new HashSet<>(getWatchJars(source));
			WatchPathRegistry.parse(paths, dirs, jars);
			WatchPathRegistry.set(source, dirs, jars);
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	/**
	 * 移除指定来源的全部监控路径。
	 */
	static void removeWatchSource(String source) {
		HOTSWAP_LOCK.lock();
		try {
			if (source == null) return;
			WatchPathRegistry.remove(source);
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	/** 获取指定来源的监控目录集合（只读不可变副本） */
	static Set<Path> getWatchDirs(String source) {
		return WatchPathRegistry.dirs(source);
	}

	/** 获取指定来源的监控 Jar 集合（只读不可变副本） */
	static Set<Path> getWatchJars(String source) {
		return WatchPathRegistry.jars(source);
	}

	/** 获取当前所有来源的监控目录映射（只读不可变副本） */
	static Map<String, Set<Path>> getAllWatchDirSources() {
		return WatchPathRegistry.allDirSources();
	}

	/** 获取当前所有来源的监控 Jar 映射（只读不可变副本） */
	static Map<String, Set<Path>> getAllWatchJarSources() {
		return WatchPathRegistry.allJarSources();
	}

	static void initTransformer() {
		if (transformer == null) {
			transformer = new AnnotationTransformer();
			inst.addTransformer(transformer, true);
			// 必须也是 canRetransform = true：JVM 先跑完**所有** canRetransform=false 的 transformer，
			// 再跑 canRetransform=true 的那组（注册顺序只在组内生效）。若这里传 false，
			// 首次加载时本 transformer 会先于 AnnotationTransformer 执行，拿到的是对齐/织入**之前**
			// 的字节——缓存就存错了版本。同组内按注册顺序执行，所以它仍然排在 AnnotationTransformer 之后。
			inst.addTransformer(new CacheWarmingTransformer(), true);
			if (isEnhancedHotswapEnabled()) {
				try {
					DynamicProfilerAPI.init();
				} catch (Throwable ignored) { }
			} else {
				info("Skipped dynamic profiler injector because structural hotswap is unsupported.");
			}
			// 观测器最后挂（含 profiler 注入之后）：它们拿到的就是 JVM 最终定义的字节。
			synchronized (TRAILING_OBSERVERS) {
				for (ClassFileTransformer observer : TRAILING_OBSERVERS) {
					registerTrailingObserver(observer);
				}
				TRAILING_OBSERVERS_ARMED = true;
			}
			startBackgroundWarmup();
		}
	}

	private static final AtomicBoolean WARMUP_STARTED = new AtomicBoolean();

	private static void startBackgroundWarmup() {
		if (!INIT_FIX) return;
		if ("false".equalsIgnoreCase(System.getProperty("remold.agent.warmup"))) return;
		if (!WARMUP_STARTED.compareAndSet(false, true)) return;
		Thread t = new Thread(() -> {
			try {
				ClassIdentity.canonicalize(HotswapBridge.class);
				HotswapBridge.warmUp();
			} catch (Throwable ignored) { }
		}, "Remold-Warmup");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	/**
	 * 登记一个"加载后观测器"：只读加载期字节、不改任何字节的 transformer。
	 *
	 * <p><b>为什么需要延迟注册</b>：JVM 先整组跑 {@code canRetransform=false} 的 transformer，
	 * 再按注册顺序跑 {@code canRetransform=true} 的那组。观测器若在 {@link #initTransformer()} 之前
	 * 挂上（插件 {@code onPremain} 正是这么早的窗口），拿到的是对齐/织入<b>之前</b>的字节；
	 * 因此本方法只登记，真正的 {@code addTransformer} 落在 {@code initTransformer} 末尾。
	 * {@code initTransformer} 之后再登记则立即挂上（幂等：同一实例只挂一次）。</p>
	 *
	 * <p><b>边界</b>：观测器只覆盖 Remold 自身与 profiler 之后注册的 transformer；此后别的插件
	 * 若再挂 rewriter，它对字节的改写不在观测视野内。</p>
	 *
	 * @param observer 观测器；{@code null} 忽略
	 */
	static void addTrailingLoadObserver(ClassFileTransformer observer) {
		if (observer == null) {
			return;
		}
		synchronized (TRAILING_OBSERVERS) {
			if (!TRAILING_OBSERVERS.add(observer)) {
				return;
			}
			if (!TRAILING_OBSERVERS_ARMED || inst == null) {
				return;   // 精确落点：initTransformer 末尾统一挂
			}
			registerTrailingObserver(observer);
		}
	}

	private static void registerTrailingObserver(ClassFileTransformer observer) {
		try {
			inst.addTransformer(observer, true);
		} catch (Throwable t) {
			warn("[Agent] Failed to register a trailing load observer; its view of the defined bytes is"
				+ " lost (callers must fail-closed instead of degrading silently): " + t);
		}
	}


	/** CodeSource 位置 → 是否属于工程自身。按位置字符串缓存，避免每个类都解析一次 URI。 */
	private static final Map<String, Boolean> projectLocations = new ConcurrentHashMap<>();

	/**
	 * 判定一个保护域的位置是否属于"工程自身"。
	 *
	 * <p>先认 agent 正在监听的目录与 jar（{@code watchDirSources} / {@code watchJarSources} /
	 * {@code activeWatchJars}）——那才是工程的边界，mod 以 jar 形式分发时也成立。
	 * 监听配置为空时退回"file: 目录"判据：jar 里的类按依赖处理，不属于工程自身。</p>
	 */
	static boolean isProjectLocation(ProtectionDomain pd) {
		if (pd == null) return false;
		CodeSource cs = pd.getCodeSource();
		if (cs == null || cs.getLocation() == null) return false;
		String key = cs.getLocation().toString();
		Boolean cached = projectLocations.get(key);
		if (cached != null) return cached;

		boolean project = false;
		try {
			URI uri = cs.getLocation().toURI();
			Path location = Path.of(uri).toAbsolutePath().normalize();
			for (Set<Path> dirs : WatchPathRegistry.dirSources().values()) {
				for (Path dir : dirs) {
					if (location.startsWith(dir.toAbsolutePath().normalize())) {
						project = true;
						break;
					}
				}
				if (project) break;
			}
			if (!project) {
				for (Path jar : activeWatchJars) {
					if (location.equals(jar.toAbsolutePath().normalize())) {
						project = true;
						break;
					}
				}
			}
			if (!project) {
				for (Set<Path> jars : WatchPathRegistry.jarSources().values()) {
					for (Path jar : jars) {
						if (location.equals(jar.toAbsolutePath().normalize())) {
							project = true;
							break;
						}
					}
					if (project) break;
				}
			}
			if (!project && WatchPathRegistry.dirSources().isEmpty() && activeWatchJars.isEmpty()) {
				project = "file".equals(uri.getScheme()) && Files.isDirectory(location);
			}
		} catch (Throwable ignored) {
			project = false;
		}
		projectLocations.put(key, project);
		return project;
	}

	static void initConfig() {
		info("DEBUG: " + DEBUG);
		REDEFINE_MODE = RedefineMode.valueOfFail(System.getProperty("remold.agent.redefine_mode", "inject"), RedefineMode.inject);
		info("Redefine Mode: " + REDEFINE_MODE);
		String bl = System.getProperty("remold.agent.hotswap_blacklist", "").trim();
		HOTSWAP_BLACKLIST = bl.isEmpty() ? new String[0] : bl.split(",");
		info("Injection Blacklist: " + String.join(",", HOTSWAP_BLACKLIST));
		INIT_FIX = boolProp("remold.agent.init_fix", "remold.initFix", true);
		TWO_PHASE = boolProp("remold.agent.two_phase", "remold.twoPhase", true);
		MIXIN_MEMBER_ALIGN = boolProp("remold.agent.mixin_member_align", "remold.mixinMemberAlign", true);
		INTERNAL_AFFECTED_PASSTHROUGH = boolProp("remold.agent.internal_passthrough", "remold.internalPassthrough", true);
		FORCE_STATIC_LAMBDA = boolProp("remold.agent.force_static_lambda", "remold.agent.force_static_lambdas", false);
		for (String degradedSwitch : degradeForMissingEnhancedRedefinition(isEnhancedHotswapEnabled())) {
			warn("DEGRADED: " + degradedSwitch + " forced OFF. " + degradationReason(degradedSwitch));
		}
		info("InitFix: " + INIT_FIX);
		info("Two-phase redefine: " + TWO_PHASE);
		info("Force Static Lambda: " + FORCE_STATIC_LAMBDA);
		ENABLE_HOTSWAP_EVENT = Boolean.parseBoolean(System.getProperty("remold.agent.hotswap_event", "false"));
		info("HotSwap Event: " + ENABLE_HOTSWAP_EVENT);
		FORCE_REINIT = Boolean.parseBoolean(System.getProperty("remold.agent.force_reinit", "false"));
		if (FORCE_REINIT) {
			info("Force Reinit: ON — @RemoldReinit will ignore the initializer fingerprint and replay"
			     + " the declaring class's initializer slice this session.");
		}
		LAMBDA_ALIGN = boolProp("remold.agent.lambda_align", "remold.lambdaAlign.enabled", true);
		LAMBDA_STRICT = boolProp("remold.agent.lambda_strict", "remold.lambdaAlign.strict", false);
		info("Lambda Align: " + LAMBDA_ALIGN + " (strict=" + LAMBDA_STRICT + ")");
		if (LAMBDA_ALIGN) {
			info("Lambda Alignment ENABLED. Recompilation with '-g' (LocalVariableTable) is required for reliable lambda capture reordering detection.");
		}
		FILE_SHAKE_MS = intProp("remold.agent.file_shake_ms", "remold.fileShakeMs", FILE_SHAKE_MS);
		ASSET_SHAKE_MS = intPropAsset("remold.agent.asset_shake_ms", "remold.assetShakeMs", ASSET_SHAKE_MS);
		DEBOUNCE.setWindows(FILE_SHAKE_MS, ASSET_SHAKE_MS);
		info("File Shake MS: " + FILE_SHAKE_MS);
		info("Asset Shake MS: " + ASSET_SHAKE_MS);
		PERF_LOG = boolProp("remold.agent.perf_log", "remold.perfLog", false);
		DIFF_LOG = boolProp("remold.agent.diff_log", "remold.diffLog", true);
		info("Perf Detail Log: " + PERF_LOG + " (Diff Log: " + DIFF_LOG + ")");
		UI_HOOK = Boolean.parseBoolean(System.getProperty("remold.agent.ui_hook", "false"));
		info("UI Hook: " + UI_HOOK);
		boolean cellPropertyRef = Boolean.parseBoolean(System.getProperty("remold.agent.cell_property_ref", "false"));
		info("Cell Property Ref: " + cellPropertyRef);
		loadConfiguredPlugins();
		for (HotswapPlugin plugin : plugins) {
			try {
				plugin.onConfigReload();
			} catch (Throwable t) {
				error("[HotswapPlugin] Error reloading plugin config: " + plugin.getName(), t);
			}
		}
		info("Anon Align: " + ANON_ALIGN + " (strict=" + ANON_STRICT + ", debug=" + ANON_DEBUG
		     + ", maxPerHost=" + AnonClassAligner.MAX_ANON_PER_HOST
		     + ", timeoutMs=" + AnonClassAligner.ALIGN_TIMEOUT_MS
		     + ", layoutGate=" + ANON_LAYOUT_GATE + ", redefineLayoutGate=" + LAYOUT_GATE
		     + ", localClassGuard=" + LOCAL_CLASS_GUARD + ")");
		if (ANON_ALIGN) {
			info("Anonymous Class Alignment ENABLED. Ambiguity is resolved conservatively (never by class name).");
		} else {
			info("Anonymous Class Alignment DISABLED. Host classes containing anonymous classes will be rejected as a whole.");
		}
		info("Structural HotSwap Supported: " + isEnhancedHotswapEnabled());
		configureBuildTrigger();
	}

	/**
	 * 构建完成 marker 的配置接线：解析路径并启动/停止专用 watcher。
	 *
	 * <p>marker 是<b>叠加</b>的快路径：未配置时只保留 {@link RemoldAgent#FILE_SHAKE_MS} 兜底，行为与旧版本一致。
	 * watcher 只在 agent 已挂载（{@code inst != null}）时启动，避免单测里 {@code initConfig()}
	 * 拉起真实文件监听线程。</p>
	 */
	static void configureBuildTrigger() {
		Path configured = AgentWatchCoordinator.parseBuildTriggerFile(
			System.getProperty("remold.agent.build_trigger_file"),
			System.getProperty("remold.buildTriggerFile"));
		if (configured == null) {
			BUILD_TRIGGER_FILE = null;
			if (inst != null) FileWatcherService.stopBuildTriggerWatcher();
			if (DEBUG) log("[BUILD-TRIGGER] Disabled (no marker file configured)");
			return;
		}
		BUILD_TRIGGER_FILE = configured;
		info("[BUILD-TRIGGER] Marker file: " + configured
		     + " (file shake " + FILE_SHAKE_MS + "ms remains the fallback for everything else)");
		if (inst != null) {
			FileWatcherService.startBuildTriggerWatcher(configured);
		} else if (DEBUG) {
			log("[BUILD-TRIGGER] Marker watcher not started: agent instrumentation is not attached");
		}
	}

	/**
	 * 插件只发现一次：{@link #bootstrapPlugins} 会在 premain 首段先跑，之后 {@link #initConfig} 再调
	 * 到这里时应直接返回，否则每次 {@code init()} 都会重新实例化一遍插件（注册口按类名去重，
	 * 新实例会被丢弃，白付构造开销与副作用）。
	 */
	private static final AtomicBoolean PLUGINS_LOADED =
	 new AtomicBoolean();

	/**
	 * 极早插件引导：完成插件发现并触发 {@link HotswapPlugin#onPremain}。
	 *
	 * <p><b>为什么必须早于 {@code init}</b>：有些运行期集成只有在宿主类加载器初始化之前设置才生效
	 * （例如按 class path group 模拟模组隔离的启动器）。这类知识属于具体加载器，因此放在插件里，
	 * 由引擎在 premain 首段统一提供窗口。</p>
	 *
	 * @param agentArgs 原始 agent 参数串，可为 {@code null}
	 */
	static void bootstrapPlugins(String agentArgs) {
		loadConfiguredPlugins();
		for (HotswapPlugin plugin : plugins) {
			try {
				plugin.onPremain(agentArgs);
			} catch (Throwable t) {
				error("[HotswapPlugin] Error in onPremain for " + plugin.getName(), t);
			}
		}
	}

	private static void loadConfiguredPlugins() {
		if (inst == null) return;
		if (!PLUGINS_LOADED.compareAndSet(false, true)) return;

		// 1. 读取请求启用的插件名称/过滤集合（若未配置或为 * / all，则默认加载发现的所有插件）
		String pluginsProp = System.getProperty("remold.plugins");
		Set<String> requestedPlugins = null;
		if (pluginsProp != null && !pluginsProp.trim().isEmpty() && !pluginsProp.trim().equals("*") && !pluginsProp.trim().equalsIgnoreCase("all")) {
			requestedPlugins = new HashSet<>();
			for (String name : pluginsProp.split("[,;]")) {
				String trimmed = name.trim().toLowerCase();
				if (!trimmed.isEmpty()) requestedPlugins.add(trimmed);
			}
		}

		List<JarFile> pluginJars = new ArrayList<>();

		// 2. 读取并通过 Instrumentation 追加外部插件 JAR 到系统类加载器搜索路径
		String pluginJarsProp = System.getProperty("remold.plugin.jars");
		if (pluginJarsProp != null && !pluginJarsProp.trim().isEmpty()) {
			String[] jarPaths = pluginJarsProp.split("[;," + File.pathSeparator + "]");
			for (String rawPath : jarPaths) {
				String path = rawPath.trim();
				if (path.isEmpty()) continue;
				File jarFile = new File(path);
				if (jarFile.exists()) {
					try {
						JarFile jf = new JarFile(jarFile);
						inst.appendToSystemClassLoaderSearch(jf);
						pluginJars.add(jf);
						info("[Plugin] Appended plugin JAR to system search: " + jarFile.getAbsolutePath());
					} catch (Throwable t) {
						error("[Plugin] Failed to append plugin JAR: " + path, t);
					}
				} else {
					warn("[Plugin] Specified plugin JAR does not exist: " + path);
				}
			}
		}

		// 3. 从外部插件 JAR 的元数据中动态解析插件类配置 (SPI services 或 Manifest 声明，无任何名称硬编码)
		Set<String> candidateClassNames = new LinkedHashSet<>();
		for (JarFile jf : pluginJars) {
			// A. 读取 META-INF/services/remold.HotswapPlugin 服务发现配置
			try {
				ZipEntry serviceEntry = jf.getEntry("META-INF/services/remold.HotswapPlugin");
				if (serviceEntry != null) {
					try (BufferedReader reader = new BufferedReader(new InputStreamReader(jf.getInputStream(serviceEntry), StandardCharsets.UTF_8))) {
						String line;
						while ((line = reader.readLine()) != null) {
							line = line.trim();
							if (!line.isEmpty() && !line.startsWith("#")) {
								candidateClassNames.add(line);
							}
						}
					}
				}
			} catch (Throwable t) {
				warn("[Plugin] Failed to read SPI services descriptor from " + jf.getName() + ": " + t.getMessage());
			}

			// B. 读取 Manifest 中的 Remold-Plugin-Class 或 Plugin-Class 声明
			try {
				Manifest manifest = jf.getManifest();
				if (manifest != null) {
					String pluginAttr = manifest.getMainAttributes().getValue("Remold-Plugin-Class");
					if (pluginAttr == null) {
						pluginAttr = manifest.getMainAttributes().getValue("Plugin-Class");
					}
					if (pluginAttr != null && !pluginAttr.trim().isEmpty()) {
						for (String cls : pluginAttr.split("[,;]")) {
							String trimmed = cls.trim();
							if (!trimmed.isEmpty()) candidateClassNames.add(trimmed);
						}
					}
				}
			} catch (Throwable t) {
				warn("[Plugin] Failed to read Manifest from " + jf.getName() + ": " + t.getMessage());
			}
		}

		// 4. 实例化并通过插件自声明的名称 (plugin.getName()) 进行匹配注册
		for (String className : candidateClassNames) {
			try {
				Class<?> cls = Class.forName(className, true, ClassLoader.getSystemClassLoader());
				if (HotswapPlugin.class.isAssignableFrom(cls)) {
					HotswapPlugin p = (HotswapPlugin) cls.getDeclaredConstructor().newInstance();
					String pluginName = p.getName() != null ? p.getName().trim().toLowerCase() : "";
					String classLower = className.toLowerCase();
					if (requestedPlugins == null || requestedPlugins.contains(pluginName) || requestedPlugins.contains(classLower)) {
						registerPlugin(p);
						info("[Plugin] Automatically loaded and registered plugin: " + p.getName() + " (" + className + ")");
					}
				} else {
					warn("[Plugin] Class " + className + " does not implement HotswapPlugin");
				}
			} catch (Throwable t) {
				error("[Plugin] Failed to instantiate plugin class '" + className + "': " + t.getMessage(), t);
			}
		}

		// 5. 通过标准 ServiceLoader 探测系统类加载器中已注册的 HotswapPlugin 服务实现
		try {
			ServiceLoader<HotswapPlugin> loader = ServiceLoader.load(HotswapPlugin.class, ClassLoader.getSystemClassLoader());
			for (HotswapPlugin p : loader) {
				String pluginName = p.getName() != null ? p.getName().trim().toLowerCase() : "";
				String classLower = p.getClass().getName().toLowerCase();
				if (requestedPlugins == null || requestedPlugins.contains(pluginName) || requestedPlugins.contains(classLower)) {
					registerPlugin(p);
				}
			}
		} catch (Throwable ignored) { }

		// 6. 如果用户显式传入了类名（包含 package 点号且尚未被注册），尝试直接反射加载
		if (requestedPlugins != null) {
			for (String requested : requestedPlugins) {
				if (requested.contains(".")) {
					boolean alreadyRegistered = false;
					for (HotswapPlugin p : plugins) {
						if (p.getClass().getName().equalsIgnoreCase(requested)) {
							alreadyRegistered = true;
							break;
						}
					}
					if (!alreadyRegistered) {
						try {
							Class<?> cls = Class.forName(requested, true, ClassLoader.getSystemClassLoader());
							if (HotswapPlugin.class.isAssignableFrom(cls)) {
								HotswapPlugin p = (HotswapPlugin) cls.getDeclaredConstructor().newInstance();
								registerPlugin(p);
								info("[Plugin] Loaded explicit plugin class: " + p.getName() + " (" + requested + ")");
							}
						} catch (Throwable t) {
							error("[Plugin] Failed to load explicit plugin class '" + requested + "': " + t.getMessage(), t);
						}
					}
				}
			}
		}
	}
}
