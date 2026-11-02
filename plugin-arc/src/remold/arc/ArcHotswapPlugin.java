package remold.arc;

import remold.HotswapPlugin;
import remold.profiler.GlTimerInjector;
import remold.ref.UpdateRef;
import remold.uihook.CellPropertyRef;

import java.lang.instrument.Instrumentation;

/**
 * Arc / Mindustry 热重载扩展插件。
 * 负责注册 UI 熔断断路器、透明帧断言、批次重构监听器以及 GL 定时器探针。
 */
public class ArcHotswapPlugin implements HotswapPlugin {

	@Override
	public String getName() {
		return "arc";
	}

	@Override
	public void onAgentInit(Instrumentation inst) {
		// 触碰 UpdateRef 与 CellPropertyRef，确保其静态块被执行（向 RemoldAgent 显式注册）
		try {
			Class.forName(UpdateRef.class.getName());
			Class.forName(CellPropertyRef.class.getName());
			remold.LambdaRef.init();
		} catch (Throwable ignored) {
		}

		// 注册 Arc Json 实例级缓存驱逐器：Arc 专属知识只存在于本插件，核心引擎不持有
		try {
			remold.RemoldAgent.registerCacheEvictor(ArcJsonEvictor.INSTANCE);
		} catch (Throwable ignored) { }

		// Scl.scl() 是全局 UI 缩放系数：启动时按设置算一次，之后不再变（改设置会要求重启）。
		// 登记为「运行时常量」，让 InitFix 的效应审查与捕获桥的常量合成能把它当常量来源——
		// 桥里重放这次调用，而不是编一个值。
		// Scl.scl(float) = v * Scl.scl() 是它的带参形态：纯函数，给定实参则结果恒定，所以
		// 也登记（桥只在实参全是编译期常量时重放它）。
		// （Arc 专属知识，所以登记放在本插件，core 不认识它。）
		try {
			remold.RuntimeConstants.register("arc/scene/ui/layout/Scl", "scl", "()F");
			remold.RuntimeConstants.register("arc/scene/ui/layout/Scl", "scl", "(F)F");
		} catch (Throwable ignored) { }

		// 自动绑定 Arc 的主线程事件循环调度器
		try {
			remold.RemoldAgent.setMainThreadDispatcher(task -> {
				if (task != null) {
					try {
						if (arc.Core.app != null) {
							arc.Core.app.post(task);
							return;
						}
					} catch (Throwable ignored) { }
					task.run();
				}
			});
		} catch (Throwable ignored) { }

		// 挂载 GlTimerInjector
		if (inst != null) {
			try {
				GlTimerInjector injector = new GlTimerInjector();
				inst.addTransformer(injector, true);
				injector.load();
			} catch (Throwable ignored) {
			}
		}
	}

	@Override
	public void onAssetsChanged(java.util.Set<java.nio.file.Path> changedAssets) {
		ArcAssetReloader.onAssetsChanged(changedAssets);
	}
}
