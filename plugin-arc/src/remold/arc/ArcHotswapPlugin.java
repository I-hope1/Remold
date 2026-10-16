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
}
