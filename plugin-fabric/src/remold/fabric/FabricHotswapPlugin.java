package remold.fabric;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Set;

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
 * <p><b>为什么放在插件而不是 core</b>：三件事都建立在具体加载器的目录与属性约定上。引擎只提供
 * 通用窗口（premain / 路径变更 / 排除投票），加载器知识随插件发布与下线；Mindustry / Arc 等
 * 文件系统驱动生态不会因为"引擎里写死了 Minecraft 的目录规则"而受影响。</p>
 *
 * <p><b>零配置性</b>：Gradle 侧检测到 Loom 系插件时会自动挂载本插件
 * （{@code -Premold.autoFabric=false} 可关闭），因此"开箱即用"与"分层干净"不冲突。
 * 直接用 {@code -javaagent} 启动且未配置 {@code remold.plugin.jars} 时不会加载本插件，
 * 此时不注入 class path group、也不做 {@code mods} 目录排除。</p>
 */
public final class FabricHotswapPlugin implements HotswapPlugin {

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
	}

	@Override
	public void onWatchPathsChanged(Set<Path> watchDirs, Set<Path> watchJars) {
		ModJarInspector.checkStaleModJars(watchDirs);
	}

	@Override
	public boolean isWatchPathIgnored(Path path) {
		return DevWatchExclusions.isIgnored(path);
	}
}
