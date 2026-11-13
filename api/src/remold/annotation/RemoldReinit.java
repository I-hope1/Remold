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
 *   <li><b>作用域（{@link #scope()}）</b>：声明这个字段的存量重置要覆盖哪些存活实例。
 *       默认 {@link Scope#INCLUDE_SUBCLASSES} 与既有行为一致（声明类及其全部子类/匿名子类实例）；
 *       {@link Scope#SELF} 只重置运行时类<b>恰好等于</b>声明类的实例，子类实例保持原值。
 *       字段声明在父类、而子类在初始化块里写了自己的值（或根本不该被重置）时用 {@code SELF}，
 *       避免"父类初值被写进全部子类实例"。静态字段只有一个槽位，作用域对它没有意义：
 *       显式写 {@code scope} 会被忽略并打一条 warn（见 {@code docs/initfix/03-runtime-driver.md} §4）。</li>
 * </ul>
 *
 * <h2>继承字段的安全门（{@code INCLUDE_SUBCLASSES} 的默认组合不再危险）</h2>
 * <p>{@code scope = INCLUDE_SUBCLASSES} + 字段声明在父类 + 某个子类在 {@code <init>} 里给它写了
 * 自己的值，是本注解最危险的组合：值来自<b>父类</b>初值切片，却会覆盖子类实例上<b>子类自己</b>
 * 写的值，且子类 {@code <init>} 的赋值不会被重放。布局门会在重定义前枚举<b>已登记</b>的子类型
 * （有实例的子类必已加载并登记），若发现子类初始化器写同一字段，直接拒绝本轮重定义并点名
 * 子类（{@code [REINIT-SCOPE]}），不会静默覆盖。显式 {@code scope = SELF} 时该门不适用。</p>
 *
 * <h2>强制重放（{@code -Dremold.agent.force_reinit=true}，默认关闭）</h2>
 * <p>正常语义是"初值表达式变了才重置"（封闭表达式的指纹未变则跳过）。该开关忽略指纹、本轮一律
 * 重放声明类的初值切片，用于"初值没改、但存量值被运行期改脏了"的应急回置。它<b>不</b>改变取值
 * 来源——值仍来自声明类，而不是实例运行时子类的初值；按实例运行时类取初值是另一条设计边界（不在当前范围，见
 * {@code docs/status.md}；设计讨论见 {@code docs/initfix/03-runtime-driver.md} §4）。</p>
 *
 * <h2>不支持的场景（Does not do）</h2>
 * <table>
 *   <tr><th>场景</th><th>实际行为 / 原因</th></tr>
 *   <tr><td>{@code static final} 编译期常量（基本类型 / {@code String}，带 {@code ConstantValue}）</td>
 *       <td><b>对批外读者无效</b>：javac 已把值内联进 {@code ldc}/{@code bipush}，{@code OVERWRITE}
 *           只改字段槽位，读者仍读旧常量。需全量重编才能看到新值（JLS §13.4.9 的既有语义）。</td></tr>
 *   <tr><td>Record 组件</td>
 *       <td>组件由规范构造器参数赋值，没有字段初始化表达式；类型变更 + 注解会在布局门前置核验时
 *           被拒绝（{@code unextractableReinitFields}），不是"跳过该字段"。</td></tr>
 *   <tr><td>{@code final} 实例字段</td>
 *       <td>{@code OVERWRITE} 走 {@code Unsafe} volatile 写，绕过 JMM final 冻结语义；多数场景可运行，
 *           但不作为规范内承诺（补丁是宿主 nestmate，普通 {@code putfield} 在链接期即被拒绝）。</td></tr>
 *   <tr><td>多字段原子性 / 构造器派生状态</td>
 *       <td>逐字段 volatile 写，字段之间没有事务，并发读者可能看到"半新半旧"；构造器里由该字段
 *           派生的其它字段不会被重算。本注解只是"重写若干字段槽位"，不是"把对象恢复到初始状态"。</td></tr>
 *   <tr><td>按实例运行时类取初值（父类字段 + 各子类不同初值）</td>
 *       <td>不支持：值来源固定为声明类初值切片。该形态用 {@code scope = SELF}、把字段/值放到子类
 *           自己声明，或用 {@link OnReload}。</td></tr>
 * </table>
 *
 * <h2>{@link Mode#CONDITIONAL} 的精确语义</h2>
 * <table>
 *   <tr><th>字段当前值</th><th>是否写入</th><th>说明</th></tr>
 *   <tr><td>引用类型为 {@code null}；数值为 {@code 0}；{@code boolean} 为 {@code false}；
 *           {@code char} 为 {@code '\u0000'}</td><td>写</td><td>与新增字段相同的条件 CAS，一次原子读-比较-写。</td></tr>
 *   <tr><td>{@code float}/{@code double} 的 <b>{@code -0.0}</b> / {@code NaN}</td><td>不写</td>
 *       <td>按 <b>raw bits</b> 判零：位模式非零即视为"已有值"（{@code NaN} 永远不会被条件写）。</td></tr>
 *   <tr><td>任何非默认值（含"运行期被改过"的旧初值）</td><td>不写</td>
 *       <td>只有被清成默认值的实例才会被写；想让已有非默认值也回到初值，用 {@link Mode#OVERWRITE}。</td></tr>
 *   <tr><td>{@code long}</td><td>写</td>
 *       <td>走 {@code Unsafe} CAS；JDK 8 的 {@code sun.misc.Unsafe} 无 {@code compareAndSwapLong} 以外
 *           的子字 CAS，布尔/字节/字符/短整型在该版本降级为 volatile 写（仍只在默认值时写）。</td></tr>
 * </table>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RemoldReinit {

	Mode mode() default Mode.OVERWRITE;

	/**
	 * 存量重置的实例作用域（{@code docs/initfix/03-runtime-driver.md} §4）。
	 *
	 * <p>默认保持既有行为，因此新增该元素不改变已有标注的语义。</p>
	 */
	Scope scope() default Scope.INCLUDE_SUBCLASSES;

	/** 实例作用域：一次重置要覆盖哪些存活实例。 */
	enum Scope {
		/**
		 * 只重置运行时类<b>恰好等于</b>声明类的实例。
		 *
		 * <p>子类/匿名子类实例保持原值。适用于字段声明在父类、但子类初始化块里有自己的赋值，
		 * 或子类实例不应被父类初值覆盖的场景。</p>
		 */
		SELF,

		/**
		 * 重置声明类及其全部子类/匿名子类的存活实例（默认）。
		 *
		 * <p>与"父类字段标 {@code @RemoldReinit}"的既有语义一致：所有存活实例统一写成声明类的初值，
		 * 子类初始化块里的赋值不会被重放。</p>
		 */
		INCLUDE_SUBCLASSES
	}

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
