package remold.reflect.mixin;

import java.util.Collection;

/**
 * Mixin 的“目标类已加载”检查抑制器。
 *
 * <p>Mixin 在构造 {@code MixinInfo} 时会检查目标是否已加载；运行期新增 Mixin 必须在这个极窄
 * 窗口内把本次目标报告为“未加载”，构造完成后立即恢复。不同加载器的检查点不同：</p>
 * <ul>
 *   <li>Fabric：{@code FabricLauncherBase.launcher.isClassLoaded}；</li>
 *   <li>Forge / NeoForge：{@code MixinServiceModLauncher} 的
 *       {@code ModLauncherClassTracker.loadedClasses} 集合。</li>
 * </ul>
 *
 * <p><b>契约</b>：{@link #suppress(Collection)} 返回的 token 必须在 {@code finally} 中交给
 * {@link #restore(Object)}；实现方必须保证即使 {@code prepare} 抛异常也能完整恢复。抑制失败
 * 返回 {@code null}，调用方必须视为 fail-closed（提示重启，而不是装作成功）。</p>
 */
public interface LoadedTargetSuppressor {

	/**
	 * 在极窄窗口内抑制“目标类已加载”检查。
	 *
	 * @param dottedTargets 点分隔目标类名
	 * @return 恢复 token；无法抑制时返回 {@code null}
	 * @throws Exception 抑制过程中发生不可恢复的反射错误
	 */
	Object suppress(Collection<String> dottedTargets) throws Exception;

	/**
	 * 恢复 {@link #suppress(Collection)} 造成的临时状态。必须幂等；任何异常都应被实现方
	 * 记录后吞掉，不能掩盖主流程的原始异常。
	 */
	void restore(Object token) throws Exception;
}
