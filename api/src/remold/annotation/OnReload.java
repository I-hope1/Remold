package remold.annotation;

import java.lang.annotation.*;

/**
 * 热重载生命周期回调：<b>对本类存量实例</b>的回调。
 *
 * <p>它<b>不是</b>"模组/应用收到热更通知"的全局事件。这是本注解最常被误读的地方，两者的差别在
 * 有多个实例或没有实例时立刻暴露（见下文"它不是什么"）。</p>
 *
 * <h2>它调用在谁身上</h2>
 * <ul>
 *   <li><b>实例方法</b>（非 {@code static}）：在堆里检索<b>本类当前存活的实例</b>，逐个无参调用一次
 *       （优先 {@code LibTool.getInstances} 的 JVMTI 堆遍历，降级走 {@code InstanceTracker}）。
 *       一个实例都没有时<b>一次都不会调用</b> —— 没有"至少回调一次"的保证。</li>
 *   <li><b>静态方法</b>（{@code static}）：每轮热更调用一次，没有接收者，与实例数量无关。</li>
 * </ul>
 *
 * <h2>它不是什么</h2>
 * <p>它不是"热更完成"的广播：没有事件总线、不携带"哪个类被改了"的上下文、也不保证"每轮热更都会
 * 有人收到"。写在 Mod / 应用主类的实例方法上之所以<b>看起来</b>像"模组收到通知"，只是因为该主类在
 * 进程里通常<b>恰好只有一个实例</b>；把同一个注解挪到有一千个实例的实体类上，回调就会被调用一千次；
 * 挪到纯静态工具类上（零实例）则一次都不会触发。</p>
 *
 * <p>触发条件是"<b>该类本轮真的被重定义成功</b>"。类没进本批、被布局门 / 对齐器 / 结构门拒绝、
 * 或 redefine 失败时，回调<b>不会</b>发生 —— 热更摘要里的"跳过了谁"因此直接决定了回调是否会跑。</p>
 *
 * <h2>实例集合的两个细节</h2>
 * <ul>
 *   <li><b>包含子类实例</b>：底层按 klass 匹配（JVMTI {@code IterateOverInstancesOfClass}），
 *       查 {@code A} 会连同 {@code B extends A} 的实例一起返回。只想处理本类时自行判
 *       {@code obj.getClass() == A.class}。</li>
 *   <li><b>包含不可达但尚未被 GC 回收的对象</b>：堆遍历看到的是"还没被回收"，不等于"逻辑上还活着"。
 *       在回调里做"注册监听器 / 挂进全局表 / 启动定时任务"这类动作，可能把已经死亡的对象重新拉活。</li>
 * </ul>
 *
 * <h2>方法规则</h2>
 * <ul>
 *   <li>必须无参；返回值被忽略。</li>
 *   <li>可以是 {@code private}（管线会 {@code setAccessible(true)}）。只扫<b>本类声明的</b>方法
 *       （{@code getDeclaredMethods}）：继承来的 {@code @OnReload} 不会因子类被重定义而触发。</li>
 *   <li>同一个类上可以有多个 {@code @OnReload} 方法，全部都会被调用；总调用次数 =
 *       实例数 × 注解方法数。声明顺序不保证（{@code getDeclaredMethods} 未规定顺序）。</li>
 *   <li>异常被捕获并记录，不会中断热更流程，也不影响同批其它类 / 其它实例的回调。</li>
 * </ul>
 *
 * <h2>执行时机与线程</h2>
 * <p>在该类的 {@code InitFix} 补丁（静态字段 + 存量实例新增字段）<b>全部就位之后</b>执行，因此回调
 * 读到的新增字段已是初值，不会读到零值。调用经
 * {@code RemoldAgent.setMainThreadDispatcher(...)} 注册的分发器投递；未注册分发器时默认同步执行在
 * 热更线程上。若回调涉及 UI 线程（如 Arc/Mindustry 主循环线程）的渲染组件重建，仍需在回调内部
 * 显式派发至主线程（例如 {@code Core.app.post(...)}）。</p>
 *
 * <h2>与 {@link RemoldReinit} 的分工</h2>
 * <p>{@code @RemoldReinit} 决定"存量实例里的字段值怎么补"，在热更管线内部完成、不需要用户写代码；
 * 本注解是"补完之后再跑一段你自己的逻辑"，用于字段补不了的场景（重建缓存、重绑监听器、重算派生状态）。
 * 两者可以同时使用。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnReload {
}
