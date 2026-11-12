package remold.fabric;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import remold.HotswapPlugin;
import remold.RemoldAgent;

/**
 * Minecraft 系（Fabric / Quilt 及各 Loom 变体）开发运行期集成。
 *
 * <p><b>本插件负责三件"引擎不该知道"的事</b>：</p>
 * <ol>
 *   <li>{@link #onPremain} —— 在宿主类加载器初始化之前补齐 {@code fabric.classPathGroups}。
 *       组未声明时，散装输出目录会被当成互不可见的独立来源，热更字节码对已加载宿主不可见，
 *       表现为<b>热更静默不生效</b>；窗口只有 premain。</li>
 *   <li>{@link #onWatchPathsChanged} —— 巡检运行目录里的残留构建 JAR（与当前工程同 modId），
 *       那会造成双源加载、命名空间断裂与旧字节码回灌。</li>
 *   <li>{@link #isWatchPathIgnored} —— 把 {@code mods} 目录排除出监听，避免监听风暴。</li>
 * </ol>
 *
 * <p>另有 {@link #isHostReady()}：把 Minecraft 的生命周期就绪信号（主循环是否已进入首个
 * tick）翻译成引擎的启动期门闩投票。判据与失败语义见 {@link HostReadinessProbe}——
 * <b>取不到信号时必须回退到"已就绪"</b>，否则映射对不上就等于热更永久不工作。</p>
 *
 * <p><b>为什么放在插件而不是 core</b>：上述四件事都建立在具体加载器/游戏的生命周期、目录与
 * 属性约定上。引擎只提供通用窗口（premain / 路径变更 / 排除投票 / 就绪投票），加载器知识随
 * 插件发布与下线；Mindustry / Arc 等文件系统驱动生态不会因为"引擎里写死了 Minecraft 的
 * 目录规则或就绪判据"而受影响。</p>
 *
 * <p><b>零配置性</b>：Gradle 侧检测到 Loom 系插件时会自动挂载本插件
 * （{@code -Premold.autoFabric=false} 可关闭），因此"开箱即用"与"分层干净"不冲突。
 * 直接用 {@code -javaagent} 启动且未配置 {@code remold.plugin.jars} 时不会加载本插件，
 * 此时不注入 class path group、也不做 {@code mods} 目录排除与就绪投票。</p>
 */
public final class FabricHotswapPlugin implements HotswapPlugin {

	private final HostReadinessProbe readinessProbe;

	/** 就绪单向：首次判定就绪后不再探测（也不回退），避免每 tick 打日志与重复反射。 */
	private volatile boolean hostReady;

	/** "就绪/回退"各只打一条日志的守卫。 */
	private final AtomicBoolean readinessLogged = new AtomicBoolean(false);

	/** 生产构造：按反射探测真实 Minecraft 宿主（不引入任何编译期 MC 依赖）。 */
	public FabricHotswapPlugin() {
		this(new HostReadinessProbe());
	}

	/**
	 * @param readinessProbe 就绪探测；{@code null} 等价于生产默认。测试/嵌入方可注入替身，
	 *                       不需要真实 Minecraft 就能验证门闩语义。
	 */
	public FabricHotswapPlugin(HostReadinessProbe readinessProbe) {
		this.readinessProbe = readinessProbe == null ? new HostReadinessProbe() : readinessProbe;
	}

	@Override
	public String getName() {
		return "fabric";
	}

	@Override
	public void onPremain(String agentArgs) {
		FabricClassPathGroups.apply(agentArgs);
	}

	@Override
	public void onAgentInit(Instrumentation inst) {
		RemoldAgent.info("[Fabric] Dev-runtime integration active (class path groups + mods guard)");
		registerSourceTransformReplay();
	}

	/**
	 * 第二层（源头补齐）：把 Fabric Loader 在 Mixin 之前做的运行时变换原样重放一遍。
	 *
	 * <p>Fabric 正常加载时会经由 {@code FabricTransformer} 应用 AccessWidener，并在 dev
	 * （Yarn named）下应用 {@code PackageAccessFixer}——把 Minecraft 类的非 private 成员提升为
	 * public。Remold 对已加载 Mixin 目标是从原始 jar/磁盘字节重新织入的，不重放这层变换就会以
	 * <b>更窄的访问权限</b>送回 JVM：JBR 增强重定义会把子类一并 link/verify，跨包 protected 访问
	 * 当场 {@code VerifyError} -> {@code InternalError}。</p>
	 *
	 * <p>只用反射调用 Loader 自己的实现，避免 plugin-fabric 编译期依赖 loader 内部类；任何失败都
	 * 保持原字节并交给第一层的访问权限单调对齐兜底。</p>
	 */
	private static void registerSourceTransformReplay() {
		try {
			RemoldAgent.registerSourceTransform((name, bytes) -> {
				if (bytes == null) return null;
				try {
					ClassLoader cl = FabricHotswapPlugin.class.getClassLoader();
					Class<?> envTypeClass = Class.forName("net.fabricmc.api.EnvType", false, cl);
					Object envType = currentEnvironmentType(envTypeClass);
					if (envType == null) return bytes;
					Class<?> transformerClass = Class.forName(
						"net.fabricmc.loader.impl.transformer.FabricTransformer", false, cl);
					java.lang.reflect.Method transform = transformerClass.getMethod("transform",
						boolean.class, envTypeClass, String.class, byte[].class);
					boolean development = isDevelopmentEnvironment();
					Object out = transform.invoke(null, development, envType, name, bytes);
					return out instanceof byte[] && ((byte[]) out).length > 0 ? (byte[]) out : bytes;
				} catch (Throwable t) {
					return bytes;
				}
			});
		} catch (Throwable t) {
			RemoldAgent.warn("[Fabric] Source-transform replay unavailable; access alignment remains the guard: " + t);
		}
	}

	/**
	 * 是否开发环境。优先问 FabricLoader 本身（与 KnotClassLoader 传给 FabricTransformer 的语义一致），
	 * 取不到时回退 {@code fabric.development} 系统属性。错误的 dev 判定会让 {@code PackageAccessFixer}
	 * 不生效，重织入产物重新带回 protected 成员。
	 */
	private static boolean isDevelopmentEnvironment() {
		try {
			Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
			Object instance = loaderClass.getMethod("getInstance").invoke(null);
			Object dev = loaderClass.getMethod("isDevelopmentEnvironment").invoke(instance);
			if (dev instanceof Boolean) return (Boolean) dev;
		} catch (Throwable ignored) {
			// fall through to the system property
		}
		return Boolean.parseBoolean(System.getProperty("fabric.development", "false"));
	}

	/** 当前环境类型（CLIENT/SERVER）；取不到时回退 CLIENT（dev 客户端是主要场景）。 */
	private static Object currentEnvironmentType(Class<?> envTypeClass) {
		try {
			Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
			Object instance = loaderClass.getMethod("getInstance").invoke(null);
			return loaderClass.getMethod("getEnvironmentType").invoke(instance);
		} catch (Throwable t) {
			try {
				return envTypeClass.getField("CLIENT").get(null);
			} catch (Throwable ignored) {
				return null;
			}
		}
	}

	@Override
	public void onWatchPathsChanged(Set<Path> watchDirs, Set<Path> watchJars) {
		ModJarInspector.checkStaleModJars(watchDirs);
	}

	@Override
	public boolean isWatchPathIgnored(Path path) {
		return DevWatchExclusions.isIgnored(path);
	}

	/**
	 * 宿主是否已过启动期（主循环已跑起来）。
	 *
	 * <p><b>语义</b>：{@code false} 只由<b>明确的</b>未就绪信号产生（例如服务端实例已建但
	 * tick 计数仍为 {@code 0}）；取不到信号（{@link HostReadinessProbe.Verdict#UNKNOWN}）
	 * 一律回退到 {@code true} 并打一条 info，绝不因为"探测不到"把批次永久押住。</p>
	 *
	 * <p><b>单向</b>：一旦判定就绪就缓存下来，之后恒返回 {@code true}——启动期是一次性窗口，
	 * 运行期回退会让门闩变成随机噪声；日志也只在首次判定那次输出，不按重查节奏刷屏。</p>
	 */
	@Override
	public boolean isHostReady() {
		if (hostReady) return true;

		HostReadinessProbe.Verdict verdict;
		try {
			verdict = readinessProbe.probe();
		} catch (Throwable t) {
			// 探测抛异常等同于"取不到信号"：门闩会把异常当未就绪（fail-closed），
			// 那会让热更永久停摆，所以这里必须自己吞掉并回退。
			verdict = HostReadinessProbe.Verdict.UNKNOWN;
		}
		if (verdict == HostReadinessProbe.Verdict.NOT_READY) return false;

		hostReady = true;
		logReadinessOnce(verdict);
		return true;
	}

	private void logReadinessOnce(HostReadinessProbe.Verdict verdict) {
		if (!readinessLogged.compareAndSet(false, true)) return;
		if (verdict == HostReadinessProbe.Verdict.READY) {
			RemoldAgent.info("[Fabric] host reported ready (Minecraft main loop has ticked);"
			                 + " hot-swap batches are enabled");
		} else {
			RemoldAgent.info("[Fabric] no readiness signal observed; treating the host as ready"
			                 + " (startup gate stays open; batches may land in the startup storm)");
		}
	}
}
