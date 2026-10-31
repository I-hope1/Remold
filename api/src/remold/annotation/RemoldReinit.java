package remold.annotation;

import java.lang.annotation.*;

/**
 * 存量重置扩展（Opt-in Scope Extension，参考 {@code docs/initfix/03-runtime-driver.md} §4 与 {@code docs/initfix/01-safety-gate.md}）。
 *
 * <p>热更补丁的默认作用域是<b>只处理本次新增声明的字段</b>：已有字段即使改了初值，
 * 存量实例也不会被重置。给字段标上本注解，等于显式声明"这个字段允许覆写存量状态"，
 * 它因此进入本轮候选集，并按 {@link Mode} 决定写入协议。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * public class Counter {
 *     // 改了初值，并要求把存量实例也重置成新初值
 *     @RemoldReinit(mode = RemoldReinit.Mode.OVERWRITE)
 *     private int retries = 3;
 * }
 * }</pre>
 * <p>若 {@code import static remold.annotation.RemoldReinit.Mode.OVERWRITE;}，
 * 也可以写成简写 {@code @RemoldReinit(mode = OVERWRITE)}。</p>
 *
 * <h2>契约（实现对齐 {@code InitFix}）</h2>
 * <ul>
 *   <li><b>值的来源不变</b>：仍然从新字节码里该字段的 {@code PUTFIELD}/{@code PUTSTATIC}
 *       切片提取。所以"改初值 → 覆盖存量"成立，但也意味着切片本身必须能被安全提取。</li>
 *   <li><b>被豁免的门</b>（见 {@code docs/initfix/03-runtime-driver.md} §4）：
 *       <ul>
 *         <li>{@code T0 零值等价}（{@code docs/initfix/01-safety-gate.md} §1）：不再因"零值等价"被判 {@code NOTHING_TO_PATCH}
 *             —— 否则"把已有字段重置成 0/null"会被当成无需补丁；</li>
 *         <li>条件 CAS：{@link Mode#OVERWRITE} 走无条件写（
 *             {@code HotswapBridge.KIND_FORCE}），不再"仅当字段是类型默认值时才写"；</li>
 *         <li>后续加工检查：不再因为"构造器里读过/别处写过该字段"而拒绝
 *             —— 已有字段本来就会被各处读写，这条按定义不可能满足。
 *             <b>触发这条豁免时会打 warn 日志，并在 {@code PatchReport} 的
 *             {@code FieldDecision.warnings} 里标记</b>：补丁只重建字段初始化式，构造器里
 *             对它的其它用法不会被重放；且补丁在<b>热更线程上单线程</b>执行，对
 *             {@code ThreadLocal} 这类按线程的值，构造线程的 per-thread 副本无法还原。</li>
 *       </ul>
 *   </li>
 *   <li><b>不被豁免的门</b>：切片本身的安全门照旧（直线无分支、无局部变量依赖，以及
 *       {@code docs/initfix/01-safety-gate.md} §2 效应判定）。理由：这些门决定"补丁算出来的值是不是构造器会算的值"，
 *       覆写语义只决定"要不要写"，不能让一个读脏的值变正确。</li>
 *   <li><b>按线程的状态仍然无解</b>：{@code ThreadLocal.get()} 之类的读照样拒绝，
 *       构造器里对缓存的写入也不会重放（补丁在热更线程上执行）。要按实例、按正确线程
 *       补齐请用 {@link OnReload}。</li>
 *   <li><b>生效会点名</b>：注解字段真的被重置时，热更日志里会出现一行 {@code [REINIT]}，
 *       逐个列出 {@code 字段 (OVERWRITE|CONDITIONAL, static|instance)}；没兑现的字段在同一行里
 *       标出原因（{@code failed} / {@code skipped} / {@code no live instance}）。
 *       默认作用域只含本次新增字段，所以"这个字段为什么被改了"与"我改了初值为什么没生效"
 *       这两件事，靠这一行区分。</li>
 *   <li><b>重置时机是"初值表达式变了"，不是"类被 redefine 了"</b>：只改一个方法体的无关热更
 *       不会把存量值打回初值。判据是初值切片的指纹，且只对<b>封闭表达式</b>生效
 *       （{@code = "abc"}、常量算术这类值完全由自身字节码决定的形态）。</li>
 *   <li><b>初值读外部状态时每轮都重置</b>：{@code = ConfigLoader.getDefaultTag()}、
 *       {@code = Other.VALUE} 这类切片的字节码可以一个字都不变，而被调用方/被读字段
 *       恰在这一轮改了值 —— 宿主类的字节码里看不出来。这种情况无法证明"值没变"，
 *       因此照常重置（宁可多写一次，也不静默留下过期值）。想避免这种覆盖，
 *       把初值改成封闭形态，或用 {@link OnReload} 按需刷新。</li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RemoldReinit {

	Mode mode() default Mode.OVERWRITE;

	enum Mode {
		/**
		 * 维持与新增字段相同的条件 CAS 语义：仅在字段当前等于该类型默认值时才写入。
		 * <p>适合"字段已经是默认值、只想补上"的场景；对存量里已有非默认值的实例会自动跳过。</p>
		 */
		CONDITIONAL,

		/** 无条件覆写存量状态（{@code Unsafe} volatile 写，因此 final 字段同样适用）。 */
		OVERWRITE
	}
}
