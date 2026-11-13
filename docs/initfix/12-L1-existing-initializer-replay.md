# L1：已有字段初始化表达式重放（设计稿）

> **文档类型**：设计规格（只写规格/决策；实现状态以 [`docs/status.md`](../status.md) 为准）

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

继承字段（双括号写父类字段）**必须纳入第一版**。

统计来源：`.sources/mindustry/mindustry/content/Blocks.java`（本地只读源码镜像；镜像内没有 git/版本元数据，无法给出 commit/tag，版本号未知）。脚本：

```bash
# 双括号初始化块总数
grep -c 'new .*() *{{' .sources/mindustry/mindustry/content/Blocks.java
# 五个常见字段的赋值总数（含普通构造器/方法，不只是双括号块）
for f in health size armor buildTime speed; do
  printf '%s ' "$f"; grep -c "$f *=" .sources/mindustry/mindustry/content/Blocks.java
done
```

实测：双括号块 **81** 个；`health=79`、`size=182`、`armor=13`、`buildTime=1`、`speed=29`，合计 304 处。`Block` 自身声明 `health`/`size`/`armor`/`buildTime`（`speed` 不属于 `Block`，可能来自其它类型），所以 304 不是“全部继承字段”，但主要形态确实是匿名 `Block` 子类写继承字段。排除继承字段等于不覆盖核心用例。做法：

- 补丁 owner 仍是声明类，但**执行实例集合必须是 `LibTool.getInstances(该匿名类)`**，不能是 `LibTool.getInstances(父类)`；后者会复现 B2 的批量误伤（未修改的匿名子类也被写成父类初值）。
- 字段可访问性：声明类字段若是 private，走 Unsafe（InitFix 已有 hidden nestmate/Unsafe 路径）；不因 private 放弃。
- 若 `LibTool.getInstances(匿名类)` 返回空，跳过并 warn，不退回父类枚举。

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

放行手段不用全局 `init_drift=replay`（B2 已证明全局重放会批量误伤），改为**按父类名的白名单**：

```text
-Dremold.agent.init_drift.parents=arc.struct.Block,arc.world.Block
```

- 只有白名单父类的匿名子类、且 `LibTool.getInstances(匿名类)` 精确枚举到的实例，才生成 L1 补丁；
- 白名单为空时 L1 关闭，L0 仍 warn；
- 全局 `init_drift=off` 只用于关闭检测，不用于放行重放。

输出：

- L0：`[INIT-DRIFT]` 每类每轮一条；
- L1：`[INIT-REPLAY]` 点名字段 + 写入/跳过原因；不改变默认 `patchGenerated` 语义，另设 `replayGenerated` 诊断。

## 6. 性能

- 只对本轮 changed 类、只对 L0 命中的字段做补丁生成；
- 复用 L0 已解析的旧/新 `ClassNode`，不重复 parse；
- `Blocks.java` 级别（数百匿名类）按“命中字段数”而非类数增长；需用 L0 性能探针同一口径补测 L1，报告 min/median/max。

## 7. 非目标

- 不重放构造器；
- 不在白名单父类之外的继承字段上自动重放；
- 不替代 `@RemoldReinit` / `@OnReload`；
- 不在默认配置下改变存量数据。

## 8. 验收

1. B/B2-no-reinit：默认仍 warn + 旧值不变；
2. 开启 `replay` 后，封闭常量切片字段被写为新值，且 `KIND_CONDITIONAL` 不覆盖运行期改过的值；
3. 开放表达式/构造器依赖/多根不一致仍拒绝并 warn；
4. L0 的 `warn` 与 L1 的 `replay` 不重复计数；
5. 性能探针给出 L1 的 min/median/max，且与 L0 同轮同口径。
