package remold.mctoast;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

import remold.HotswapBatchListener;
import remold.HotswapBatchReport;
import remold.HotswapPlugin;
import remold.RemoldAgent;

/**
 * Minecraft 客户端热重载 Toast 插件入口。
 *
 * <p>在 Agent 初始化阶段向 {@link RemoldAgent} 注册批次监听器，收到有可观测活动的批次报告时，
 * 通过 {@link McToastPresenter} 在游戏右上角弹出或就地刷新 HotSwap 结果卡片。</p>
 *
 * <p>默认启用；可通过 {@code -Dremold.mc_toast=false} 在不拆插件包的前提下快速静音，
 * 纯服务端下会在找不到客户端主类时自动退化为无操作。</p>
 */
public final class McToastHotswapPlugin implements HotswapPlugin, HotswapBatchListener {

	/** 显式关闭运行期游戏内 Toast 的系统属性键。 */
	public static final String DISABLE_PROPERTY = "remold.mc_toast";

	private final McToastPresenter presenter;
	private final AtomicBoolean listenerRegistered = new AtomicBoolean(false);

	public McToastHotswapPlugin() {
		this(new McToastPresenter());
	}

	McToastHotswapPlugin(McToastPresenter presenter) {
		this.presenter = presenter == null ? new McToastPresenter() : presenter;
	}

	@Override
	public String getName() {
		return "mc-toast";
	}

	@Override
	public void onAgentInit(Instrumentation inst) {
		if (!isEnabled()) {
			RemoldAgent.info("[mc-toast] Disabled via -Dremold.mc_toast=false");
			return;
		}
		if (listenerRegistered.compareAndSet(false, true)) {
			RemoldAgent.addBatchListener(this);
			RemoldAgent.info("[mc-toast] Minecraft client hot-reload Toast active (-Dremold.mc_toast=false to disable)");
		}
	}

	@Override
	public void afterInternalBatchReport(HotswapBatchReport report) {
		if (report == null || !report.hasObservableActivity() || !isEnabled()) {
			return;
		}
		try {
			presenter.show(ToastModel.from(report));
		} catch (Throwable ignored) {
			// 卡片通知只作开发反馈，任何失败都不得影响热更批次收敛
		}
	}

	static boolean isEnabled() {
		return !"false".equalsIgnoreCase(System.getProperty(DISABLE_PROPERTY, "true").trim());
	}
}
