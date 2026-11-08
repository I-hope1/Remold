package remold;

import java.lang.instrument.ClassDefinition;
import java.util.List;

/**
 * 热重载批次生命周期监听器。
 *
 * <p><b>语义规范：</b></p>
 * <ul>
 *   <li><b>内部管线批次（{@link #afterInternalBatch}）</b>：
 *       经由 Agent 自身文件监控或主动重载管线触发。
 *       此时所有类的字节码均已经过桥接转换、拓扑对齐、安全门（LayoutGate）检测，
 *       并已由 JVM 批量重定义成功且执行了 InitFix 存量初始化。
 *       UI 重建、容器刷新等重量级操作应当且仅当在此方法中进行<b>单批统一处理</b>，
 *       坚决避免在单类回调中多次刷新导致界面闪烁与卡顿。</li>
 *   <li><b>外部检测重定义（{@link #onExternalClassRedefined}）</b>：
 *       由外部调试器（如 IntelliJ IDEA JDWP）或第三方 Agent 直接重定义触发。
 *       由于 JVM 原生 JDWP 协议不提供完成回调，此方法是在底层 {@code transform} 拦截织入后，
 *       由异步验证调度器确认目标类在 JVM 堆中已实际就绪后延迟触发。</li>
 * </ul>
 */
public interface HotswapBatchListener {

	/**
	 * 内部主动批次（modtools 文件变更 / 手动重载）重定义全部生效后的回调。
	 *
	 * @param redefinedClasses 本批成功重定义的类定义列表
	 */
	default void afterInternalBatch(List<ClassDefinition> redefinedClasses) {}

	/**
	 * 内部批次的<b>结构化报告</b>回调。
	 *
	 * <p>与 {@link #afterInternalBatch(List)} 并列发布，不是它的替代：既有监听器继续收到后者，
	 * 行为不变。本方法额外提供批次耗时、Mixin 模板增量与 InitFix 修补规模，
	 * 便于 UI / 日志做一次性汇总，而不必自己拼装。</p>
	 *
	 * <p><b>触发条件</b>：批次产生了可观测活动（成功重定义了类，或只改了 Mixin 模板，
	 * 或 InitFix 修补了存量实例）。仅“扫描到文件但没有类生效”的空批次不会触发。
	 * 该回调可能在没有 {@code ClassDefinition} 的批次上触发，因此不能用
	 * {@link #afterInternalBatch(List)} 的“列表非空”作替代判据。</p>
	 *
	 * <p><b>兼容性</b>：新增的是 {@code default} 方法，已在旧版本编译的监听器实现无需修改；
	 * 旧 Agent 不认识本方法，自然也不会调用它。</p>
	 *
	 * @param report 本批的可观测结果快照，非 {@code null}
	 */
	default void afterInternalBatchReport(HotswapBatchReport report) {}

	/**
	 * 外部（IDE JDWP / 第三方 Agent）重定义生效检测回调。
	 * 由于 JVM JDWP 不提供完成通知，本回调由异步验证调度器在复核类已生效后触发。
	 *
	 * @param clazz      生效的类
	 * @param wovenBytes 实际在 JVM 运行的字节码
	 */
	default void onExternalClassRedefined(Class<?> clazz, byte[] wovenBytes) {}
}
