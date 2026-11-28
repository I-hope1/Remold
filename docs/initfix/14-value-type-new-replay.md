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

## 1. 术语

- `oldExpr` / `newExpr`：改动前 / 后的字段初值表达式，均为直线 `new T(c...)`。
- `rebuildOld()`：按 `oldExpr` 在补丁期重建旧值（走旧构造器）。
- `buildNew()`：按 `newExpr` 构造新值。
- 目标字段：承载 `T` 引用的既有字段（实例或静态）。
- 值等价：`valueEquals(cur, rebuildOld())`，用 `T` 的生成 `equals` 判定。

## 2. 准入判据

自动重放必须**全部**满足以下判据；任一不满足即 fail-closed（拒绝并上报），不得降级为
无条件覆写。

### A1 直线构造与可重建参数

- `oldExpr`、`newExpr` 都是直线 `new T(c...)`，单层、无分支、无异常表；
- 每个实参都可从旧/新字节码的常量池重新构造：基本类型 / `String` 编译期常量，
  以及它们的装箱形态；`float`/`double` 按原始位模式处理；
- `T.<init>` 与 `super()` 链的字节码在批内可读（旧字节来自 `oldClass`，新字节来自候选定义）。

> 参数来自构造器参数、字段读取、方法调用返回值等不可重建形态时拒绝。

### A2 构造链在批次内稳定

`T`、`T` 的各级 `super()` 构造链、以及 A1 验证闭包内被调用的类，任一出现在本批
redefine 候选（字节码变化 / 结构变化 / 重放写目标）集合中时，**拒绝**自动重放。

理由：`rebuildOld()` 在 redefine 之后执行，跑的是**新**的 `<init>`；构造链一旦变化，
重建出的"旧值"不可信，此时条件 CAS 的判等前提失效。该场景不允许自动退化为
"强制重放 / OVERWRITE"——那会无差别覆盖运行期改过的值。用户若确需无条件覆盖，走显式
`@RemoldReinit(OVERWRITE)` 并自行承担旧值不可信的风险。

### A3 值等价可证明纯

MVP 只接受以下形态（`T` 为被构造类型）：

1. Java `record`，且**全部组件**为基本类型或 `String`；
2. Kotlin data class，且**全部主构造属性**为基本类型或 `String`；
3. 显式 `@RemoldReinit(OVERWRITE)` 通道（不依赖 `equals` 判等，仅受 A4/A5 约束）。

其它形态拒绝（`VALUE-REPLAY-EQ-NOT-PURE`）。该限制同时避开了"生成 `equals` 递归调用组件
`equals`"带来的指纹闭包问题：组件限制在基本类型 / `String` 后，生成 `equals` 的行为只由
`T` 自身的 `<init>`/`equals` 字节码决定，无需递归组件类型指纹。

### A4 目标字段类型与宿主限制

实例字段：

- 声明类不得为 `record` 或 hidden class。record 组件的 final 实例字段会被 JIT 按常量
  接收者折叠（C1/C2 均实测），hidden class 不可 redefine；其余普通类的 final 实例字段
  可写（实测替换后已编译读者可见）。
- 字段必须是引用类型；基本类型字段不属于本通道。

静态字段：

- 仅允许 `static final` 且运行时初始化的引用字段（非 `ConstantValue`）；
- 必须 `isEnhancedHotswapEnabled()` 为真，否则拒绝
  （`VALUE-REPLAY-STATIC-FINAL-NEEDS-ENHANCED`）：未启用
  `-XX:+AllowEnhancedClassRedefinition` 时，阶段 C 重定义宿主不会 deopt 跨类折叠读者，
  写入后读者仍读旧值。
- 非 final 静态字段不需要 enhanced 前提（JIT 不折叠，实测读者可见），但仍受 A1~A3/A5 约束。

### A5 多根构造器共识

沿用 InitFix 既有判据：目标字段必须在全部 root 构造器中以**相同**的 `oldExpr` 初始化；
否则拒绝。多根不一致时旧值重建没有唯一答案。

### A6 存活实例

实例重放沿用堆遍历 + scope 语义；scope 内无存活实例时按 no-op 处理，不报失败。

## 3. 写入协议（条件 CAS，三态落账）

对每个存活实例：

```
W1 cur = getReferenceVolatile(field)
W2 old = rebuildOld()                 // 失败 → failed
W3 if (!valueEquals(cur, old)) → skipped    // 运行期已改值，保留现状
W4 new = buildNew()                   // 仅 precheck 通过后构造
W5 CAS(field, cur, new)               // 有界重试 N=3
W6 仅 CAS 成功 → applied
```

- **并发语义**：CAS 的 expected 是 W1 刚读到的**引用身份**，不是 `rebuildOld()` 的返回值；
  并发线程换入"等值但不同引用"的对象时，最多重判一次后跳过。`new` 可能在竞争后丢弃。
- **三态**：
  - `written/applied`：写入成功，记录 `(initializerFingerprint, mode)`；
  - `skipped`：W3 不等（运行期值被改过，按设计保留），**不记 applied**，日志按初值指纹去重；
  - `failed`：CAS 重试用尽、`rebuildOld()`/`buildNew()` 异常、参数不可重建等，**不记 applied**，
    上报并受既有 per-field 失败预算约束。
- 绝不能用 applied 记录掩盖 skipped/failed，否则该字段在初值不变时永远不会再尝试。

静态字段的写入同样走 W1~W6；`static final` 的可见性额外依赖 A4 的 enhanced 门。

## 4. 指纹与代际

自动重放的 applied 键由三部分组成：

1. **初值切片指纹**：沿用既有派生（常量池 + 直线指令序列 + 实参位模式）；
2. **构造链指纹**：`T.<init>` + `super()` 链 + A1 验证闭包内方法字节码哈希。构造链任一变化
   ⇒ 即使初值指纹未变也必须重算/重放，不得按"初值未变"跳过；
3. **`equals` 稳定性**：`T.equals` 的方法字节码哈希（A3 限制下无需递归组件指纹）。

`@RemoldReinit(OVERWRITE)` 与既有规则一致：注解优先于自动重放；每次 redefine 轮次重置
open 表达式（沿用 InitFix 的"每轮重算"约定）。

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

## 6. 拒绝理由与日志契约

运行期日志与用户可见文案一律英文（`verifyLogLanguage`）。建议判决码：

| 判决码 | 触发 |
|:--|:--|
| `VALUE-REPLAY-EXPR-NOT-CONSTANT` | A1 参数不可重建 |
| `VALUE-REPLAY-CONSTRUCTED-CHANGED` | A2 构造链在本批变化 |
| `VALUE-REPLAY-EQ-NOT-PURE` | A3 非 record/data class 或组件超限 |
| `VALUE-REPLAY-HOST-RECORD` | A4 实例字段声明类为 record/hidden |
| `VALUE-REPLAY-STATIC-FINAL-NEEDS-ENHANCED` | A4 静态 final 目标但无 enhanced |
| `VALUE-REPLAY-CAS-EXHAUSTED` | W5 有界重试用尽 |

**迁移说明**：本通道收窄前，非 enhanced 运行时也可能对既有 `static final` 引用字段执行
`@RemoldReinit` 覆写；收窄后该形态 fail-closed。需要保留能力的用户应启用
`-XX:+AllowEnhancedClassRedefinition`，或把目标改为实例字段 / 非 final 静态字段。
非 final 静态字段路径行为不变。

## 7. 测试要求

### 正向

- `record` / Kotlin data class `new Vec2(4,4)` → `new Vec2(4,12)`，存量实例被替换为新值；
- 运行期已被业务改过的值保持不变（W3 skipped），且不被记入 applied；
- `@RemoldReinit(OVERWRITE)` 显式覆盖路径不受 W3 限制；
- 静态 final 目标在 enhanced JBR 上替换后，**已编译读者**读到新值。

### 负向（必须变红）

- A2：同批构造链变更（`T` 或 super `<init>` 字节变化）→ REJECTED，且不写入；
- A3：自定义 `equals` / 复合组件 → REJECTED；
- A4：record 宿主实例字段 → REJECTED；非 enhanced 静态 final → REJECTED；
- A1：实参来自构造器参数 → REJECTED；
- W5：CAS 竞争用尽 → failed，不记 applied，下一轮仍可重试；
- 可见性负基线：不触发 redefine 时，对静态 final 折叠读者断言 stale（证明测试确实能抓到问题）。

### 防 JIT 时序抖动

可见性断言不能"预热固定次数后假定已编译"。测试进程必须：

1. 以 `-XX:+PrintCompilation` 启动，循环预热直到日志出现目标读者方法（C1/C2 各一条腿），
   超时即失败；或用 `-Xbatch -XX:CompileCommand=compileonly,<Reader>::<method>` 固定编译；
2. 确认编译事件后再执行写入与断言；
3. 负向基线同样要求编译事件出现，否则"stale"可能只是没编译。

### 变异检查

- 移除 W3 判等直接写 → "运行期改值"场景必红；
- 移除构造链指纹 → "同批构造链变更"场景必红；
- 移除 A4 的 enhanced 门 → "非 enhanced 静态 final"场景必红。

## 8. 与现有机制的关系

- `@RemoldReinit(OVERWRITE)`：显式通道，绕过 W3，但保留 A4/A5；A2 的旧值不可信风险由用户承担；
- L1 / A 档：不交叉；本通道处理"对象引用级替换"，不写 `FieldLedger`（不是新增字段）；
- 失败/跳过只进 `PatchReport` 与日志，不落台账；静态 final 的 enhanced 门与
  `docs/status.md` 已登记的同源缺口共用同一判据；
- 两阶段：实例通道不因本通道新增 read-through 阶段；静态 final 通道沿用既有
  "既有静态字段重写" 的两阶段路径，并叠加 enhanced 门。

## 9. MVP 不做

- 字段类型变更、删除字段；
- hidden class 宿主、record 组件目标；
- 用户自定义 `equals` / 非基本类型、非 `String` 组件；
- 非常量实参、多语句构造、构造器内有副作用；
- 类名/引用别名的跨字段传播；
- 对"读者类枚举 + 逐个 deopt"的兜底（与 fail-closed 策略冲突，见
  [`docs/runtime/04-enum-constant-append-infeasibility.md`](../runtime/04-enum-constant-append-infeasibility.md) §3.4.1）。
