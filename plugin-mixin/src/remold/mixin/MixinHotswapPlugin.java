package remold.mixin;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Set;

import remold.HotswapPlugin;
import remold.RemoldAgent;
import remold.reflect.mixin.MixinTargetIndex;

/**
 * SpongePowered Mixin 生态热重载插件。
 *
 * <p>向 Remold 注册 {@link MixinBytecodeBridge}：热重定义前把源码级字节码重新交给 Mixin 织入，
 * 从而在热更后保留 {@code @Inject} / {@code @Unique} / {@code @Shadow} 等生效产物；
 * 改动的若是 Mixin 类本身，则刷新 Mixin 运行时状态并级联重织入其目标类。</p>
 *
 * <p>插件由 Agent 通过系统 ClassLoader 加载，因此直接依赖 {@code api} 的 SPI 契约，
 * 不存在反射与动态代理。运行时需要的 {@code org.spongepowered.asm.*} 由宿主提供。</p>
 */
public class MixinHotswapPlugin implements HotswapPlugin {

	@Override
	public String getName() {
		return "mixin";
	}

	@Override
	public void onAssetsChanged(Set<Path> changedAssets) {
		MixinConfigDiff.onAssetsChanged(changedAssets);
		refreshTargetIndex();
	}

	/**
	 * 整表重推一次 Mixin 目标索引。
	 *
	 * <p>时机都在宿主/批次侧的稳定点：配置重载、资源变更之后。绝不在类加载路径上调用——
	 * 那里反向去拿 processor 锁会与 Mixin 自身持锁触发的类加载形成锁反转。</p>
	 */
	/** 启动期刷新：拿不到配置是常态，失败不告警。 */
	private static void refreshTargetIndexQuietly() {
		MixinTargetPublisher.publishNow();
	}

	private static void refreshTargetIndex() {
		boolean published = MixinTargetPublisher.publishNow();
		MixinTargetPublisher.warnIfStillUnknown();
		if (RemoldAgent.DEBUG) {
			RemoldAgent.log("[Mixin] Target index refresh: " + MixinTargetPublisher.describe(published));
		}
	}

	@Override
	public void onConfigReload() {
		refreshTargetIndex();
	}

	/**
	 * 批次就绪门：顺带把目标索引补上（惰性发布）。
	 *
	 * <p>为什么必须挂在这里而不是只靠配置回调：正常开发流程里开发者只改 {@code .java}，
	 * {@code mixins.json} 不动，于是 {@code onConfigReload}/{@code onAssetsChanged} 在 Mixin 装配完成后
	 * 一次都不会触发，索引会一直停在 {@code UNKNOWN}，出生点补丁对 Mixin 目标的判定就永远 fail-closed。</p>
	 *
	 * <p>时机是安全的：{@link remold.AgentWatchCoordinator} 在每轮防抖到期、进入批次之前调用本方法，
	 * 此时<b>不在</b> {@code ClassFileLoadHook} 内，也没有持 Mixin 的 processor 锁；
	 * 而且它严格早于任何 {@code PendingLoadPatch.offerAny}。发布成功后本方法只剩一次 volatile 读。</p>
	 */
	@Override
	public boolean isHostReady() {
		if (!MixinTargetIndex.available()) {
			boolean published = MixinTargetPublisher.publishNow();
			if (published && RemoldAgent.DEBUG) {
				RemoldAgent.log("[Mixin] Target index published at host-ready: "
					+ MixinTargetPublisher.describe(true));
			}
		}
		return true;
	}

	/**
	 * premain 极早期窗口：把 defineClass 基线录口交出去（不变量 C2）。
	 *
	 * <p>必须早于宿主加载任何游戏类，否则合成内部类的基线永远抓不到，那条路径只剩
	 * "拒绝并要求重启"。真正的 {@code addTransformer} 由 core 延迟到所有 rewriter 之后执行，
	 * 因此录到的是 JVM 最终定义的那份字节。</p>
	 */
	@Override
	public void onPremain(String agentArgs) {
		MixinClassBaseline.install();
	}

	@Override
	public void onAgentInit(Instrumentation inst) {
		// agentmain（运行期 attach）路径没有 onPremain 窗口：同样幂等安装一次。
		MixinClassBaseline.install();
		// 早期窗口里 processor 往往还没装配：允许失败，索引保持 UNKNOWN（绝不当作"不是目标"）。
		// 这里刻意不告警：正常启动都会命中这条路径，告警会变成噪音。
		refreshTargetIndexQuietly();
		try {
			RemoldAgent.addTransformerBridge(new MixinBytecodeBridge());
			RemoldAgent.info("[Mixin] Re-weaving bridge registered (plugin-mixin)");
			RemoldAgent.info("[Mixin] Config-diff support active (new Mixin classes and mixins.json removal/readd)");
			RemoldAgent.info("[Mixin] premain defineClass baseline recorder: " + MixinClassBaseline.metricsSummary());
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] Failed to register re-weaving bridge; proceeding without Mixin support: " + t);
		}
	}
}
