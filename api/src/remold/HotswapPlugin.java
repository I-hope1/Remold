package remold;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Set;

/**
 * 热重载插件契约。
 * 允许独立扩展模块（如 Arc / Mindustry 插件）接入 Agent 生命周期。
 */
public interface HotswapPlugin {

	/** 插件唯一标识名 */
	String getName();

	/**
	 * Agent 核心就绪回调。
	 *
	 * @param inst JVM Instrumentation 实例
	 */
	default void onAgentInit(Instrumentation inst) {}

	/**
	 * Premain 极早期回调：插件发现之后、{@link #onAgentInit} 与 Agent 主体初始化（{@code init}）之前触发。
	 *
	 * <p><b>存在的理由</b>：有些运行期集成必须在宿主类加载器初始化之前完成（例如按 class path group
	 * 模拟模组隔离的启动器，组未声明时热更会静默不生效）。这类配置既不能拖到 {@code onAgentInit}，
	 * 也不该让引擎核心认识具体加载器，因此由插件在这个窗口自行处理。</p>
	 *
	 * @param agentArgs 原始 agent 参数串（{@code -javaagent:...=<args>} 里的 {@code <args>}），可为 {@code null}
	 */
	default void onPremain(String agentArgs) {}

	/** 配置重载回调 */
	default void onConfigReload() {}

	/**
	 * 资源文件变动回调。
	 * 当监控路径中的资源文件（如贴图、多语言、音频等）变动并经过防抖后被调用。
	 *
	 * @param changedAssets 变动的资源文件路径集合
	 */
	default void onAssetsChanged(Set<Path> changedAssets) {}

	/**
	 * 监控路径集合变化回调（新增/移除来源、启动时首次装配都会触发）。
	 *
	 * <p>传入的是聚合后的只读快照，可直接用于"按当前工程根目录做一次性检查"这类初始化工作。</p>
	 *
	 * @param watchDirs 当前生效的监控目录
	 * @param watchJars 当前直接指定的 jar/zip
	 */
	default void onWatchPathsChanged(Set<Path> watchDirs, Set<Path> watchJars) {}

	/**
	 * 该路径是否应从热更监听中排除。
	 *
	 * <p>用于表达"某些目录在开发期不能监听"这类<b>加载器专有</b>约束（例如 Minecraft 开发环境的
	 * {@code mods} 目录：那里只会有误打包的残留制品，监听会造成监听风暴与旧字节码回灌）。
	 * 引擎核心不认识任何具体加载器，只按插件投票决定。</p>
	 *
	 * <p>实现必须廉价且无副作用：目录遍历与每个监听事件都会调用它。</p>
	 *
	 * @param path 待判定的路径
	 * @return {@code true} 表示忽略该路径
	 */
	default boolean isWatchPathIgnored(Path path) { return false; }
}
