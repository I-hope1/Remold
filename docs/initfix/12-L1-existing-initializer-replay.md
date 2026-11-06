# L1：已有字段初始化表达式重放（设计稿）

本文只定义设计，不含实现代码。L0 只检测并 warn；L1 讨论“在可证明安全时，把已有字段的初值变化也重放到存量实例”。

## 1. 与 L0 / `@RemoldReinit` 的关系

| 层 | 行为 | 默认 |
|:--|:--|:--|
| L0 | 检测已有字段初值变化，warn，不写 | 开 |
| L1 | 对满足安全条件的字段生成补丁，写存量实例 | **关**（显式开启） |
| `@RemoldReinit` | 单字段显式豁免，已有写入协议 | 按注解 |

L1 不是“自动重放构造器”，只重放**字段写入表达式切片**；构造器里的其它语句、`super()`、日志、校验都不执行。

## 2. 候选集

在 L0 的检测结果上，L1 只接受：

- 字段在新旧两版都存在，`name+desc` 相同，非 `ACC_SYNTHETIC`，非 `val$`/`this$0`；
- 切片变化，且新切片能通过现有 `extractFieldInits` + `checkSafe`；
- 切片是**封闭表达式**（常量/算术/类型转换/栈操作；不含跨类调用、GETSTATIC、GETFIELD 外部状态）；
- 字段没有 `@RemoldReinit`（避免与显式路径重复）；
- 字段不是 `final`（final 只能走 `@RemoldReinit(OVERWRITE)` 的 Unsafe 通道）。

继承字段（双括号写父类字段）单独处理：补丁写入 owner 是父类，但执行目标是子类实例；必须确认字段可访问/可用 Unsafe，且不能把父类所有子类实例误写。第一版 L1 只支持**宿主自己声明**的字段；继承字段仍由 L0 warn + `@RemoldReinit`。

## 3. 写入协议

- 默认 `KIND_CONDITIONAL`：仅当存量值等于旧初值（或零值）时写入，避免覆盖运行期已修改的状态；
- 提供 `OVERWRITE` 仅作为显式选项，不复用 `@RemoldReinit` 的字段级语义；
- 每个字段独立决策；一个字段失败不影响其它字段；
- 失败进 `PatchReport`，不写 `FieldLedger`（L1 是显式开启的尽力重放，不是“本应补”的欠账）。

## 4. 安全门

L1 必须复用 InitFix 的切片安全门，不新增旁路：

- 切片调用“本次被修改的方法” → 拒绝；
- `GETSTATIC` 外部状态 / 黑名单调用 → 拒绝；
- 多根构造器切片不一致 → 拒绝；
- 字段被构造器读过/别处写过 → 默认拒绝，除非 `OVERWRITE`；
- `LinkageError` → 按现有熔断处理。

## 5. 开关与输出

建议：

```text
-Dremold.agent.init_drift=warn|replay|off
```

- `warn`：L0 行为（默认）；
- `replay`：L0 + L1 候选补丁；
- `off`：关闭检测与重放。

输出：

- L0：`[INIT-DRIFT]` 每类每轮一条；
- L1：`[INIT-REPLAY]` 点名字段 + 写入/跳过原因；不改变默认 `patchGenerated` 语义，另设 `replayGenerated` 诊断。

## 6. 性能

- 只对本轮 changed 类、只对 L0 命中的字段做补丁生成；
- 复用 L0 已解析的旧/新 `ClassNode`，不重复 parse；
- `Blocks.java` 级别（数百匿名类）按“命中字段数”而非类数增长；需用 L0 性能探针同一口径补测 L1，报告 min/median/max。

## 7. 非目标

- 不重放构造器；
- 不自动处理继承字段（第一版）；
- 不替代 `@RemoldReinit` / `@OnReload`；
- 不在默认配置下改变存量数据。

## 8. 验收

1. B/B2-no-reinit：默认仍 warn + 旧值不变；
2. 开启 `replay` 后，封闭常量切片字段被写为新值，且 `KIND_CONDITIONAL` 不覆盖运行期改过的值；
3. 开放表达式/构造器依赖/多根不一致仍拒绝并 warn；
4. L0 的 `warn` 与 L1 的 `replay` 不重复计数；
5. 性能探针给出 L1 的 min/median/max，且与 L0 同轮同口径。
