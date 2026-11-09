package remold.reflect.mixin;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 一次 Mixin 配置差异 / 新 Mixin 登记所需的宿主适配器。
 *
 * <p>core 不直接依赖 Mixin 或任何加载器：织入器、ClassNode 由宿主侧以 {@code Object} 传入；
 * 配置新增时读取类字节、资源文件由 {@code classNodeLoader} 提供；目标类重织入由
 * {@code targetScheduler} 回调宿主插件完成。</p>
 *
 * <p><b>抑制器为什么是 {@link Function} / {@link Consumer} 而不是专用接口</b>：抑制器是宿主插件
 * 提供的“可选能力”。插件 jar 与 core jar 版本错位时，任何“插件硬链接的 core 类型”都会在类链接期
 * 变成 {@code NoClassDefFoundError}，并把成功/失败两条分支一起打死。这里只暴露 JDK 类型，
 * 插件只需传方法引用，不 {@code implements} 任何 core 类型，错位时最坏退化为“抑制不可用”
 * 这条可诊断的降级路径。</p>
 *
 * <p><b>契约（fail-closed）</b>：{@code suppress} 返回的 token 必须在 {@code finally} 中交给
 * {@code restore}。抑制器缺失（{@code suppress}/{@code restore} 任一为 {@code null}）、返回
 * {@code null} 或抛异常，都表示“本次无法抑制”：调用方必须放弃登记/准备，并输出指向性诊断，
 * 不得装作成功。{@code loaderHook} 只用于诊断文案（说明是哪个加载器的哪个检查点）。</p>
 */
public final class MixinConfigContext {

	private final Object transformer;
	private final String side;
	private final Function<String, Object> classNodeLoader;
	private final Function<Collection<String>, Object> suppress;
	private final Consumer<Object> restore;
	private final String loaderHook;
	private final Consumer<List<String>> targetScheduler;

	/**
	 * @param transformer     当前生效的 Mixin 织入器（不透明对象）
	 * @param side            {@code CLIENT} / {@code SERVER} / {@code null}（未知时只解析 common）
	 * @param classNodeLoader 点分类名 → 宿主侧 ASM ClassNode；读不到返回 {@code null}
	 * @param suppress        “目标类已加载”检查抑制函数；{@code null} 表示本加载器不提供该能力
	 * @param restore         抑制令牌恢复函数；与 {@code suppress} 必须成对出现
	 * @param loaderHook      诊断用：加载器的检查点描述，可为 {@code null}
	 * @param targetScheduler 接收需要重织入的点分隔目标类名
	 */
	public MixinConfigContext(Object transformer, String side,
			Function<String, Object> classNodeLoader,
			Function<Collection<String>, Object> suppress,
			Consumer<Object> restore,
			String loaderHook,
			Consumer<List<String>> targetScheduler) {
		this.transformer = transformer;
		this.side = side;
		this.classNodeLoader = classNodeLoader;
		this.suppress = suppress;
		this.restore = restore;
		this.loaderHook = loaderHook;
		this.targetScheduler = targetScheduler;
	}

	/** 不需要诊断文案的简写。 */
	public MixinConfigContext(Object transformer, String side,
			Function<String, Object> classNodeLoader,
			Function<Collection<String>, Object> suppress,
			Consumer<Object> restore,
			Consumer<List<String>> targetScheduler) {
		this(transformer, side, classNodeLoader, suppress, restore, null, targetScheduler);
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

	public Function<Collection<String>, Object> suppress() {
		return suppress;
	}

	public Consumer<Object> restore() {
		return restore;
	}

	public String loaderHook() {
		return loaderHook;
	}

	public Consumer<List<String>> targetScheduler() {
		return targetScheduler;
	}
}
