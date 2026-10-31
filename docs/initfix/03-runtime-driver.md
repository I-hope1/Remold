# 03 伴生类、补丁驱动与写入协议

适用：修改补丁发射、`applyPatch`、`HotswapBridge`、`@RemoldReinit` 时阅读。实现状态参见 `docs/status.md` 与 `AGENTS.md`。

## 1. 伴生类（Patch Emitter）

- 每个放行字段生成独立静态直线方法：实例 `init$F(LHost;)V`，静态 `initStatic$F()V`。
- 伴生类 `Host$$HotswapPatch` 为宿主的 hidden nestmate。
- 方法边界即异常边界：单字段失败只影响该字段及其下游。不要把多个字段的切片串进同一个方法，否则一行异常会跳过其后所有字段。
- 独立方法也避免了类加载期 StackMapTable 帧计算死锁。

## 2. 驱动（`PatchDriver` / `InitFix.applyPatch`）

**枚举时机**
- 存活实例在**重定义生效之后**、补丁执行之前枚举，不预先拍快照。
- 理由：重定义之后不可能再产生旧布局实例，枚举天然完备；反之先拍快照会漏掉「快照之后、生效之前」新建的实例，其新增字段永久停在类型默认值。
- 枚举期间新建的实例由新构造器初始化，条件写对它们是无害的空操作。
- 回归断言：`InitFixSnapshotTimingTest`。

**补丁完成之前的读穿窗口**
- 本驱动的耗时**直接等于**读穿窗口的长度：`redefineClasses` 返回后新方法体即可执行，而字段要等本驱动跑完才写入。
- 窗口量级、当前缓解与关闭条件见 `docs/status.md` 风险 9；消除目标见 `04-target-design.md` §1。

**调度**
- 宿主侧逐字段驱动：`PatchPlan`/`FieldPatchTask` 按拓扑序执行。
- 获取 `MethodHandle` 时必须用 `asType` 适配为 `(Object)void`，再 `invokeExact`。
- 依赖失败的字段直接跳过并记失败。
- `LinkageError` 上抛熔断（单字段 indy 链接失败例外，见 `02-closure-and-ledger.md` §4）。

**按实例隔离**
- 每个 target 有局部 `failed`/`skipped` 集合，只有"本实例上确实失败的字段"才阻断本实例的下游。
- 局部集合只继承**静态侧**失败（静态字段全局唯一，失败时每个实例的下游都应跳过）。
- 全局 `failedFields`/`skippedFields` 仍是 `FieldLedger` 的输入，只做计数与记账，告警不按实例刷屏。

**失败配额（`InstanceFailureBudget`）**
- 确定性失败会让每个实例各失败一遍，日志与耗时放大 N 倍。
- 每字段每轮配额 `MAX_INSTANCE_FAILURES_PER_FIELD = 8`，用满后对后续实例放弃该字段。
- 放弃原因记为 `field failed 8 times, giving up for this round`，覆盖首次失败的原始异常文本。
- 配额不跨轮累积，下一轮重新给足。
- 回归断言：20 个实例全失败时，详细日志封顶 5 条，放弃原因可见，字段仍入台账。

## 3. 写入协议（`HotswapBridge`）

**条件写（`KIND_CONDITIONAL`，默认）**
- 解析物理偏移量，仅当内存值为类型默认零值时写入。
- `float`/`double`：用 `compareAndSetInt/Long` + `floatToRawIntBits`/`doubleToRawLongBits`（经 `filterArguments` 适配签名）。不要按名字探测 `compareAndSetFloat/Double`（JDK 8 没有，会退化成无条件写）。
- 与 T0 口径一致：比较位模式。`-0.0f`（`0x80000000`）和各类 NaN 视为"已有值"而跳过；只有真正的 `0.0f`/`0.0d` 才被写。

**跳过必须被报告**
- 条件写模板保留 CAS 的 boolean 结果，调用点用 `filterReturnValue` 接计数器（键 `owner#field`，值 `[written, skipped]`），`applyPatch` 结束后 drain 汇总。
- 跳过不是失败：不进台账，不参与失败告警。
- 首轮报 `warn`；台账重试轮降为 `info`（重试轮的跳过多半来自上一轮已补过的实例）。
- 无 CAS 语义的无条件回退（子字模拟、volatile put）统一返回 `true`，不产生跳过计数。

**强制写（`KIND_FORCE`）**
- 无条件 volatile 写，仅用于 `@RemoldReinit(mode = OVERWRITE)`。
- 必须经 `Unsafe`，不能用 `putfield`：`final` 字段只允许在声明类构造器里赋值，补丁是 hidden nestmate，直接 `putfield` 会在链接期抛 `IllegalAccessError`。因此 final 字段同样适用。

## 4. `@RemoldReinit`

- 注解：`remold.annotation.RemoldReinit`，`Mode = {CONDITIONAL, OVERWRITE}`。
- `OVERWRITE` 豁免：T0 判定、"构造器读过 / 别处写过"的后续加工检查。
- **不豁免**：切片安全门。覆写只决定"要不要写"，不能让读脏的值变正确。
- 触发豁免时打 `warn`，并写入 `PatchReport` 的 `FieldDecision.warnings`：构造器里的那部分用法不会被重放。
- 补丁只在热更线程上单线程执行，按线程的值要注意这一点。

**触发时机是"初值表达式变了"，不是"类被 redefine 了"**
- 注解字段每轮都会进候选集（它永远不会再出现在 Diff 里）。若不加约束，
  只改一个方法体的无关 redefine 也会把存量值打回初值 —— 热更改的是代码，不该动数据。
- 判据是切片指纹（`InitFix.reinitRecordKey`，模式 + `fingerprint`），与上次应用时记录的比较；
  记录存 `REINIT_APPLIED`（弱键 + 纯字符串，不反向持有 `Class`）。
- **只对封闭表达式生效**（`isClosedSlice`）：切片里只有常量、算术、类型转换、栈操作与最终写指令。
  此时"字节码不变 ⟹ 值不变"是定理，跳过安全。
- **开放表达式一律照常重置**：切片里出现 `INVOKESTATIC`/`INVOKEVIRTUAL`/`GETSTATIC`/`GETFIELD`/`NEW` 等外部读取。
  `private String tag = ConfigLoader.getDefaultTag();` 的切片一个字都没变，但 `ConfigLoader`
  的方法体可能恰在这一轮被改 —— 宿主类的字节码里完全看不出来。指纹相同**不等于**值相同，
  判不了就必须写：宁可多写一次，也不静默留下过期值（与 §3「拿不准就拒绝」同一取向）。
  他类静态字段（`Other.VALUE`）同理。
- **判不了的其他情形同样照常重置**：首轮（没有"上次"）、上次没写成（运行期失败/跳过会撤回记录，
  redefine 失败与待补过期会整类作废）、多根构造器切片口径不一致。
- **注解变化不在 `ClassDiff` 里**：它只比字段 `name+desc` 的增删（含 static 位翻转）与方法代码，
  注解、初值、`final`/`volatile` 都不参与；而"是否 redefine"由上游按**字节哈希**判定，
  所以只改一个注解也会走到 `transform`。由此产生一条必须自己兜住的路径：
  注解被摘掉那轮字段不进候选集，记录若留着，重新标注解且初值没改时会被判成"初值未变"而跳过
  （用户刚要求重置却什么都没发生）。因此每轮 `transform` 都要按新字节码的注解集合
  `pruneReinitRecords` 一次；记录键里也带上模式，`OVERWRITE ↔ CONDITIONAL` 的切换同样会重新生效。
- 日志：跳过时输出 `[REINIT] @RemoldReinit kept as-is on <类>（初值表达式自上次应用以来未变…）`；
  真正写入时输出 `[REINIT] @RemoldReinit applied to <类>: <字段> (<模式>, <static|instance>)`。

**已知限制：跨类初值依赖**
- 若被依赖的类变了、而宿主类本轮<b>没有</b>被 redefine，`InitFix` 根本不会对该类运行，
  存量实例保持旧值而新实例是新值（分叉）。这是"初值读外部状态"的固有限制，
  上面的跳过策略既不制造也不消除它；要收敛只能在宿主类上也做一次改动，或用 `@OnReload` 自行刷新。

**生效必须点名**：`applyPatch` 在实例阶段结束后输出一行 `[REINIT]` `info`，逐个列出靠注解进入候选集的字段
  （`field (OVERWRITE|CONDITIONAL, static|instance)`）。补丁日志只报"打了几个字段"，
  `Applying static field init patch ..., fields=1` 无法区分"新增字段"与"注解要求重置存量值"；
  未生效的字段在同一行里标注原因（`failed` / `skipped` / `no live instance`），
  一个注解字段都没兑现时整行改为 `did not take effect`。没有注解字段时不输出（保持静默）。
  文案由纯函数 `InitFix.reinitHint` 生成，`PatchPlan.reinitFields()` 是它唯一的数据来源
  （只收真正进了补丁的字段，被安全门拒绝的不点名）。

## 5. 合成字段过滤

- `ClassDiffUtil` 统一过滤携带 `ACC_SYNTHETIC` 或 `$remold$` 前缀的标记字段（如 `forceStaticLambdas` 生成的 `$remold$lambdasForced`），避免与业务新增字段混淆。
