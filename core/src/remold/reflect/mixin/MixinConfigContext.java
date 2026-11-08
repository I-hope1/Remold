package remold.reflect.mixin;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 一次 Mixin 配置差异 / 新 Mixin 登记所需的宿主适配器。
 *
 * <p>core 不直接依赖 Mixin 或任何加载器：织入器、ClassNode 由宿主侧以 {@code Object} 传入；
 * 配置新增时读取类字节、资源文件由 {@code classNodeLoader} 提供；目标类重织入由
 * {@code targetScheduler} 回调宿主插件完成。</p>
 */
public final class MixinConfigContext {

	private final Object transformer;
	private final String side;
	private final Function<String, Object> classNodeLoader;
	private final LoadedTargetSuppressor loadedTargetSuppressor;
	private final Consumer<List<String>> targetScheduler;

	/**
	 * @param transformer           当前生效的 Mixin 织入器（不透明对象）
	 * @param side                  {@code CLIENT} / {@code SERVER} / {@code null}（未知时只解析 common）
	 * @param classNodeLoader       点分类名 → 宿主侧 ASM ClassNode；读不到返回 {@code null}
	 * @param loadedTargetSuppressor 目标已加载检查抑制器
	 * @param targetScheduler       接收需要重织入的点分隔目标类名
	 */
	public MixinConfigContext(Object transformer, String side,
			Function<String, Object> classNodeLoader,
			LoadedTargetSuppressor loadedTargetSuppressor,
			Consumer<List<String>> targetScheduler) {
		this.transformer = transformer;
		this.side = side;
		this.classNodeLoader = classNodeLoader;
		this.loadedTargetSuppressor = loadedTargetSuppressor;
		this.targetScheduler = targetScheduler;
	}

	public Object transformer() {
		return transformer;
	}

	public String side() {
		return side;
	}

	public Function<String, Object> classNodeLoader() {
		return classNodeLoader;
	}

	public LoadedTargetSuppressor loadedTargetSuppressor() {
		return loadedTargetSuppressor;
	}

	public Consumer<List<String>> targetScheduler() {
		return targetScheduler;
	}
}
