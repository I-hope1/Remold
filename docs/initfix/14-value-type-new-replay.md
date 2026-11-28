# 值类型 `new` 初值重放（设计规格）

> **文档类型**：设计规格（只写规格/决策；实现状态以 [`docs/status.md`](../status.md) 为准）

本文定义 InitFix 的一条扩展通道：宿主既有字段的初值是 `new T(<编译期常量参数>)`，
源码只改构造参数（如 `new Vec2(4f, 4f)` → `new Vec2(4f, 12f)`）时，如何把**存量持有的
旧 `T` 实例**替换为新 `T` 实例，且不覆盖运行期被业务改过的值。

相邻能力边界：新增字段补零见 [`03-runtime-driver.md`](03-runtime-driver.md)；
既有字段显式覆写见 `@RemoldReinit(OVERWRITE)`；匿名类继承字段重放见
[`12-L1-existing-initializer-replay.md`](12-L1-existing-initializer-replay.md)；
Kotlin `apply {}` 静态持有者重放见 [`13-kotlin-apply-replay.md`](13-kotlin-apply-replay.md)。
本文只定义判据、写入协议与 JIT 边界，不含实现状态。

## 1. 术语与触发条件

- `oldExpr` / `newExpr`：改动前 / 后的字段初值表达式，均为直线 `new T(c...)`。
- `rebuildOld()`：按 `oldExpr` 重建旧值。实现上调用**当前** `T.<init>`；A2 保证 `T` 的
  构造链在本批未变化，因此当前 `<init>` 与 `oldExpr` 的语义一致（不存在"从旧 class 合成
  影子构造器"的变体）。
- `buildNew()`：按 `newExpr` 构造新值。
- 目标字段：承载 `T` 引用的既有字段（实例或静态）。
- 值等价：`Objects.equals(cur, rebuildOld())`；`T` 的生成 `equals` 由 A3 门限保证纯。

**触发条件（A0）**：本通道只在 `oldExpr` 与 `newExpr` 的**初值切片指纹不同**时触发。
初值指纹相同（典型为只改了宿主其它方法）时不进入重放；§4 的构造链指纹变化只用于让
applied 记录失效/重算，**不单独触发写入**。failed 不构成该判据的例外：不自动重试，
需下一次初值变化或显式 force 才重新进入。

## 2. 准入判据

自动重放必须**全部**满足以下判据；任一不满足即 fail-closed（拒绝并上报），不得降级为
无条件覆写。

### A1 直线构造与可重建参数

- `oldExpr`、`newExpr` 都是直线 `new T(c...)`：单层、无分支、无异常表，指令形态为
  `INVOKESPECIAL T.<init>(...)`；
- 每个实参都可从旧/新字节码的常量池重新构造：基本类型 / `String` 编译期常量，以及它们的
  装箱形态；`float`/`double` 按原始位模式处理；
- `T.<init>` 与 `super()` 链的字节码在批内可读（旧字节来自 `oldClass`，新字节来自候选定义）；
- **不支持 Kotlin 默认参数合成构造器**（`$default` 静态包装 + `DefaultConstructorMarker`
  + 位掩码常量）：出现 `INVOKESTATIC T.$default` 或 `<init>` 尾参为
  `DefaultConstructorMarker` 时按 `VALUE-REPLAY-EXPR-NOT-CONSTANT` 拒绝，MVP 不展开。

> 参数来自构造器参数、字段读取、方法调用返回值等不可重建形态时拒绝。

### A2 构造链在批次内稳定

`T`、`T` 的各级 `super()` 构造链、以及 A1/A3 验证闭包内被调用的类，任一出现在本批
redefine 候选（字节码变化 / 结构变化 / 重放写目标）集合中时，**拒绝**自动重放。

理由：`rebuildOld()` 在 redefine 之后执行，跑的是**新**的 `<init>`；构造链一旦变化，
重建出的"旧值"不可信，此时条件 CAS 的判等前提失效。该场景不允许自动退化为
"强制重放 / OVERWRITE"——那会无差别覆盖运行期改过的值。显式 `@RemoldReinit(OVERWRITE)`
是独立逃生口（见 §8），是否无条件覆盖由用户决定，不经过本规格的自动判等。

### A3 值等价、构造纯度与类型限制

MVP 只接受以下形态（`T` 为被构造类型）：

1. Java `record`：全部组件为基本类型或 `String`，**未覆写 `equals`/`hashCode`**（这两个方法
   必须等于 javac 生成形态，按字节码模式校验，不看源码）；
2. Kotlin data class：全部主构造属性为基本类型或 `String`，且全为 `val`（final 字段）；
   **无额外类体实例字段**（含委托属性 backing field）；**未覆写 `equals`/`hashCode`**；
   直接超类为 `Object` 或仅含接口（不继承带实例字段/自定义 `equals` 的超类）；
显式 `@RemoldReinit(OVERWRITE)` 是不走本通道的独立逃生口，不受本节形态/纯度门约束
（见 §8）。

**构造纯度门**（`T.<init>` 与 `super()` 链，含 Java compact constructor / Kotlin `init {}`
与类体属性初始化编译进 `<init>` 的代码）只允许：

- 读构造器参数、写 `this` 的实例字段、装箱/拆箱、算术与常量读取；
- 读取 `ConstantValue` 编译期常量（`GETSTATIC` 仅限于此形态）；
- 参数校验与抛出 `IllegalArgumentException`/`NullPointerException` 一类校验异常；
- 显式白名单内的纯调用（如 `Objects.requireNonNull`；白名单由安全门维护，不在本文扩散）。

禁止：

- `PUTSTATIC`，写非 `this` 对象的实例字段；
- 调用非白名单方法（含 `Registry.add(this)`、日志、I/O、随机数、时间）；
- `NEW` 其它对象（装箱缓存等由白名单单独处理）；
- 任何可观测副作用。

违反 → `VALUE-REPLAY-CTOR-IMPURE`。自定义 `equals`/`hashCode` → `VALUE-REPLAY-EQ-CUSTOM`。

`T` 与 super 链的 `<clinit>` 同样不得有副作用（只允许空 `<clinit>` 或 `ConstantValue`
字段初始化）；否则首次 `new T` 可能触发可观测初始化。

> MVP 不做递归组件指纹：A3 把组件限制在基本类型 / `String` 后，生成 `equals` 的行为只由
> `T` 自身的 `<init>`/`equals` 字节码决定，无需递归组件类型指纹。

### A4 目标字段类型与宿主限制

实例字段：

- 声明类不得为 `record` 或 hidden class。record 组件的 final 实例字段会被 JIT 按常量
  接收者折叠（C1/C2 均实测），hidden class 不可 redefine；其余普通类的 final 实例字段
  可写（实测替换后已编译读者可见，前提见 §5 脚注）。
- 字段必须是引用类型；基本类型字段不属于本通道。

静态字段：

- 仅允许 `static final` 且运行时初始化的引用字段（非 `ConstantValue`）；
- 必须 `isEnhancedHotswapEnabled()` 为真，否则拒绝
  （`VALUE-REPLAY-STATIC-FINAL-NEEDS-ENHANCED`）：未启用
  `-XX:+AllowEnhancedClassRedefinition` 时，阶段 C 重定义宿主不会 deopt 跨类折叠读者，
  写入后读者仍读旧值；
- 适用不变量 1：宿主类必须已完成初始化（`UNSAFE.shouldBeInitialized(host) == true` 即宿主尚未完成
  初始化，直接跳过/拒绝），不得因补丁触发 `<clinit>`；
- 非 final 静态字段不需要 enhanced 前提（JIT 不折叠，实测读者可见），但仍受 A1~A3/A5 约束。

### A5 多根构造器共识

沿用 InitFix 既有判据：目标字段必须在全部 root 构造器中以**相同**的 `oldExpr` 初始化；
否则拒绝。多根不一致时旧值重建没有唯一答案。

### A6 存活实例

实例重放沿用堆遍历 + scope 语义；scope 内无存活实例时按 no-op 处理，不报失败。

## 3. 写入协议（条件 CAS，三态落账）

对每个目标字段：

```
W0 old = rebuildOld()                 // 每字段一次，遍历实例前
for each 存活实例 i:
    W1 cur = getReferenceVolatile(field_i)
    W2 if (!Objects.equals(cur, old)) → skipped_i   // cur==null 同样走 skipped
    W3 new_i = buildNew()                          // 每实例独立构造，不共享引用身份
    W4 CAS(field_i, cur, new_i)                    // 失败 → 该实例从 W1 重新开始
```

- **W0 提升**：`oldExpr` 全是编译期常量且 `T` 纯净，`rebuildOld()` 对同字段所有实例相同，
  因此每字段重建一次；`buildNew()` 必须留在实例循环内，保证每个实例拿到独立引用。
- **W2 空安全**：判等必须是 `Objects.equals(cur, old)`。`cur == null`（运行期被业务置空，
  非 final 字段可能出现）视为"运行期已改值" → `skipped`，绝不 NPE 掉进 `failed`。
- **W4 重试语义**：CAS 失败时**单实例从 W1 重新开始**（重新读 `cur`、重新执行 W2 判等），
  有界重试 N=3；重试期间复用该实例已构造的 `new_i`，不重复 `buildNew()`。禁止原地只重试
  CAS——那会绕过并发修改后的判等检查。
- **三态**：
  - `written/applied`：CAS 成功，记录 `(initializerFingerprint, mode)`；
  - `skipped`：W2 不等（运行期值被改过，按设计保留），**不记 applied**，日志按初值指纹去重；
  - `failed`：重试用尽、`rebuildOld()`/`buildNew()` 异常、参数不可重建、构造纯度门失败等，
    **不记 applied、也不自动重试**：A0 严格，初值指纹不变不触发；CAS 持续竞争极罕见，
    而确定性异常重试没有意义。failed 必须进入批次报告（§8）并按初值指纹去重告警，
    下一次初值变化或显式 force 才重新进入。
- 绝不能用 applied 记录掩盖 skipped/failed；failed 的可见性由 §8 的批次报告兜底。
- 静态字段的写入同样走 W1~W4；`static final` 的可见性额外依赖 A4 的 enhanced 门。

## 4. 指纹与代际

自动重放的 applied 键由三部分组成：

1. **初值切片指纹**：沿用既有派生（常量池 + 直线指令序列 + 实参位模式）；
2. **构造链指纹**：`T.<init>` + `super()` 链 + A1/A3 验证闭包内方法的规范化字节码哈希；
3. **`equals` 稳定性**：`T.equals`/`hashCode` 的规范化字节码哈希（A3 限制下无需递归组件指纹）。

**规范化规则**：哈希只覆盖常量池中的语义常量 + 方法体指令 + 异常表 + `StackMapTable`；
**剥离调试属性**（`LineNumberTable`、`LocalVariableTable`/`LocalVariableTypeTable`、
`SourceFile`、`SourceDebugExtension`、`MethodParameters`）。仅加空行/注释/改变量名不得
改变指纹。

**交互规则**：

- 构造链指纹变化 ⇒ 既有 applied 记录失效；若本轮初值指纹**未变**（A0 不触发），不执行
  写入，只让记录失效并在下一次初值真正变化时使用新指纹；
- 构造链指纹变化**不得**单独触发"对未改初值的字段重放"：此时 `cur` 已是上一轮的新值，
  `rebuildOld()` 跑的是新构造器，重放要么被 W2 判成 skipped，要么等价于无意义地换一个
  等值新对象；
- `@RemoldReinit(OVERWRITE)` 与既有规则一致：注解优先于自动重放；open 表达式按既有约定
  每轮重算。该显式通道不进入本节指纹判据（不受 A2 影响），见 §8。

## 5. JIT 可见性与 deopt 边界

以下为探针实测边界（方法：`-javaagent` 取 `Instrumentation`，同字节 `redefineClasses`
触发 deopt；`-XX:+PrintCompilation` 确认读者处于 C1/C2 编译态；`-Xint` 作对照）：

| 形态 | 未 enhanced（stock 21/25、JBR 21/25 无 flag） | enhanced（JBR 21.0.8 / 21.0.11 / 25.0.4.1） |
|:--|:--|:--|
| 静态 `static final T` 被重写，跨类已折叠读者 | 重定义宿主后仍 stale | 重定义宿主后读者可见 |
| record 宿主的 final 实例组件被替换 | 常量接收者下 stale（C1/C2 均如此），动态接收者可见 | 重定义宿主后可见；重定义 record 类亦可救内联访问器的读者 |
| 非 final 静态字段被替换 | 读者可见（不折叠） | 读者可见 |

边界结论：

- 静态目标绑定 enhanced 是**硬前提**，不能指望阶段 C 在普通运行时兜底；
- record 的 final 实例字段只能在"常量接收者"成立时折叠，但源码无法可靠排除该形态，
  因此 A4 对 record/hidden 宿主一律拒绝；
- 普通类的 final 实例字段不参与该折叠，实例通道不需要 deopt 屏障；
- `static final` 编译期常量（`ConstantValue`）读者已内联字面量，任何 deopt 都救不回，
  不属于本通道（继续由既有门拒绝）。

> **折叠前提脚注**：表中"普通类 final 实例字段不折叠"成立于默认 JVM 参数、非 JDK 受信任包、
> 非 `@Stable` 字段；若运行环境开启 `-XX:+TrustFinalNonStaticFields` 一类 final-field 信任
> 实验参数，或字段属于 `java.*`/`jdk.*` 受信任包、标注 `@Stable`，折叠可能发生。此形态下
> 实例通道的可见性断言必须保留，且目标字段所属类不得是上述受信任形态。

## 6. 拒绝理由与日志契约

运行期日志与用户可见文案一律英文（`verifyLogLanguage`）。判决码：

| 判决码 | 触发 |
|:--|:--|
| `VALUE-REPLAY-EXPR-NOT-CONSTANT` | A1 参数不可重建 / Kotlin 默认参数合成构造器 |
| `VALUE-REPLAY-CONSTRUCTED-CHANGED` | A2 构造链在本批变化 |
| `VALUE-REPLAY-EQ-NOT-PURE` | A3 类型形态不满足：非 record/data class、复合组件、Kotlin `var`、类体额外实例字段、带状态超类 |
| `VALUE-REPLAY-EQ-CUSTOM` | A3 自定义 `equals`/`hashCode` |
| `VALUE-REPLAY-CTOR-IMPURE` | A3 构造纯度门失败 / `<clinit>` 有副作用 |
| `VALUE-REPLAY-HOST-RECORD` | A4 实例字段声明类为 record/hidden |
| `VALUE-REPLAY-STATIC-FINAL-NEEDS-ENHANCED` | A4 静态 final 目标但无 enhanced |
| `VALUE-REPLAY-CAS-EXHAUSTED` | W4 有界重试用尽 |

**迁移说明**：本通道收窄前，非 enhanced 运行时也可能对既有 `static final` 引用字段执行
`@RemoldReinit` 覆写；收窄后该形态 fail-closed。需要保留能力的用户应启用
`-XX:+AllowEnhancedClassRedefinition`，或把目标改为实例字段 / 非 final 静态字段。
非 final 静态字段路径行为不变。

## 7. 测试要求

### 正向

- `record` / Kotlin data class `new Vec2(4,4)` → `new Vec2(4,12)`，存量实例被替换为新值；
- 运行期已被业务改过的值保持不变（W2 skipped），且不被记入 applied；非 final 字段被置
  `null` 同样 skipped 而非 failed；
- `@RemoldReinit(OVERWRITE)` 显式覆盖路径不受 W2 限制；
- 静态 final 目标在 enhanced JBR 上替换后，**已编译读者**读到新值。

### 负向（必须变红）

- A1：实参来自构造器参数；Kotlin 默认参数合成构造器（`$default` + `DefaultConstructorMarker`）；
- A2：同批构造链变更（`T` 或 super `<init>` 字节变化）→ REJECTED，且不写入；
- A3：自定义 `equals`/`hashCode`；复合组件；Kotlin `var` 主构造属性；类体额外实例字段；
  构造器含 `PUTSTATIC`/外部调用/其它对象写；`<clinit>` 有副作用；Java record compact
  constructor 注入副作用；
- A4：record 宿主实例字段；非 enhanced 静态 final；宿主未完成初始化；
- 触发：初值指纹未变、只有构造链变化 → 不写入（applied 记录失效但不重放）；
- W4：CAS 竞争用尽 → failed，不记 applied；并发修改后重试必须重新走 W1/W2；
- failed 可见性与不自动重试：同一初值指纹的下一轮不得重试；批次报告的 failed 计数/字段/原因存在；
- 可见性负基线：不触发 redefine 时，对静态 final 折叠读者断言 stale（证明测试确实能抓到问题）。

### 防 JIT 时序抖动

可见性断言不能"预热固定次数后假定已编译"。测试进程必须：

1. 以 `-XX:+PrintCompilation` 启动，循环预热直到日志出现目标读者方法（C1/C2 各一条腿），
   超时即失败；或用 `-Xbatch -XX:CompileCommand=compileonly,<Reader>::<method>` 固定编译；
2. 确认编译事件后再执行写入与断言；
3. 负向基线同样要求编译事件出现，否则"stale"可能只是没编译。

### 变异检查

- 移除 W2 判等直接写 → "运行期改值"场景必红；
- 移除 W4 重试时的"回到 W1/W2" → 并发修改场景必红；
- 移除构造链指纹 → "同批构造链变更"场景必红；
- 移除 A4 的 enhanced 门 → "非 enhanced 静态 final"场景必红；
- 移除 A3 构造纯度门 → "compact constructor 副作用"场景必红；
- 移除 W1/W2 空安全 → "cur == null"场景必红。

## 8. 与现有机制的关系

- `@RemoldReinit(OVERWRITE)`：显式逃生口，**不受本规格 A2/A3 约束**（只受既有 InitFix
  安全门 + A4/A5）；它不重建旧值（W2），因此不存在"旧值不可信"问题，覆盖决策由用户承担；
- L1 / A 档：不交叉；本通道处理"对象引用级替换"，不写 `FieldLedger`（不是新增字段）；
- 失败/跳过只进 `PatchReport` 与日志，不落台账；静态 final 的 enhanced 门与
  `docs/status.md` 已登记的同源缺口共用同一判据；
- 两阶段：实例通道不因本通道新增 read-through 阶段；静态 final 通道沿用既有
  "既有静态字段重写" 的两阶段路径，并叠加 enhanced 门；
- **批次报告信号（最小集）**：在 `api/src/remold/HotswapBatchReport.InitFixStats` 上**只做加法**
  暴露本通道的 `applied` / `skipped` / `failed` / `rejected` 四个计数，以及 `failed`/`rejected`
  的"字段标识 + 原因"列表；旧值、指纹等明细不暴露（core 内部 `PatchReport` 保留）。插件
  （缓存清理、UI 刷新、配置重绑）消费这些计数即可；failed 不自动重试，必须可见，否则会静默
  停在旧值。

## 9. MVP 不做

- 字段类型变更、删除字段；
- hidden class 宿主、record 组件目标；
- 用户自定义 `equals`/`hashCode`、非基本类型/非 `String` 组件、Kotlin `var` 主构造属性、
  类体实例字段、带状态超类；
- 非常量实参、多语句构造、构造器内含副作用的任何形态（含 compact constructor / `init {}`）；
- Kotlin 默认参数合成构造器；
- 类名/引用别名的跨字段传播；
- 对"读者类枚举 + 逐个 deopt"的兜底（与 fail-closed 策略冲突，见
  [`docs/runtime/04-enum-constant-append-infeasibility.md`](../runtime/04-enum-constant-append-infeasibility.md) §3.4.1）。
