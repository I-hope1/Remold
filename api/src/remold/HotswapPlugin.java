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

	/** 配置重载回调 */
	default void onConfigReload() {}

	/**
	 * 资源文件变动回调。
	 * 当监控路径中的资源文件（如贴图、多语言、音频等）变动并经过防抖后被调用。
	 *
	 * @param changedAssets 变动的资源文件路径集合
	 */
	default void onAssetsChanged(Set<Path> changedAssets) {}
}
