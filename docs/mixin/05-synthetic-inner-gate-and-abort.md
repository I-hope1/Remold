# 05 · 合成匿名类：单出口闸门、事务共退与自愈账本

> **文档类型**：设计规格（只写规格/决策；实现状态以 [`docs/status.md`](../status.md) 为准）

> 作用对象：Mixin 织入生成的 `<Target>$Anonymous$<hash>` 合成内部类（磁盘上没有 `.class`，
> 名字由 `InnerClassGenerator.getUniqueReference` 生成，跨 reload 稳定）。

## 1. 基线录口（闸 4 的唯一来源）

`MixinClassBaseline` 在 premain 期注册一个**尾部** `ClassFileTransformer`（在所有会改字节的 transformer 之后，
因此录到的是 JVM 最终定义的那份），键 = **(加载器实例身份, 点分名)**，值 = 定义字节副本；恒返回 `null`，
永不参与改写链。

- 只留候选：内部名含 `$` 的提交才落账（分母拆解见下）；
- **失败的重定义不落账**：`recordApplied` 只在 `redefineClasses` **成功之后**调用，被拒候选绝不写入
  任何"已生效"记账表（`APPLIED` / `LOADED`），否则后续比对基线被污染；
- 三个分母互不可推：`seen`（看到的 defineClass 提交，含被过滤名）/ `recorded`（真正落账的键）/
  `missed`（基线查找未命中 = 因"没有基线"被拒的次数，一次拒绝计一次）。

## 2. 单出口与闸门顺序

所有合成类重定义**只能**经 `MixinInnerClassHotSwap.redefineSyntheticSafely(inst, live, name, bytes, host)`，
两条调用路径（`refeedForTarget` 预重定义、`apply()` 内部类相位）共用它，计数与日志都在出口内。

闸门顺序（**顺序即语义**）：

| # | 闸 | 判据 | 计数 |
|:--|:--|:--|:--|
| 1 | 成员 schema | `live.getDeclaredFields()` 与候选字段表（`name:desc:isStatic:isSynthetic`）不一致 | `fieldTableChanged++`，拒绝 |
| 2 | 构造器集合 | 已加载的每个 `<init>` 描述符必须仍在候选里 | 拒绝 |
| 3 | 字段表缩容 | 已加载字段必须全部仍在候选里 | 拒绝 |
| 4 | 基线存在 | `(loader, name)` 有 premain 录口或已生效字节 | 拒绝并提示重启 |

**闸 1 必须在最前**：改捕获列表会**同时**改字段表与 `<init>` 描述符，若先撞闸 2，`fieldTableChanged`
永远不计数，真机只能看到 `gateRejected` 而看不到 schema 变化。

> `[观测]` 真机裁定：已加载合成类的**任何**成员 schema 变化一律拒绝。`live{raw=0}`（堆上 0 实例）时
> 仍然 `newlyNull=230`，故实例数不参与判决；`live{raw}` 只作诊断读数。

## 3. 事务共退：`BytecodeTransformerBridge.Abort`

合成类被拒时，宿主目标类**必须一起停**：否则宿主已换成"调用新 `<init>`"的字节、合成类还是旧的，
下一 tick 就 `NoSuchMethodError`。

- `MixinReWeaver.reweave` 在入口与织入后各查一次 `BLOCKED`，命中即抛 `Abort`；
- `RemoldAgent.applyTransformerBridges` 原样上抛（不得吞成"沿用上一个字节"）；
- `redefineDynamic` 捕获后**不调用** `redefineClasses`（既不能送新织入字节，也不能送未织入原始字节——
  后者会抹掉 Mixin 钩子）；批量入口把该类移出本批；
- `MixinHotSwap.flushPendingTargets` 每轮清 `BLOCKED` 重新评估（自愈前提），拒绝时打
  `was NOT redefined … metrics: …`。

## 4. 自愈账本：`SyntheticAbortLedger`

抑制签名 = **这次要送进 JVM 的候选字节**的哈希，键 = 合成类点分名。

| 情形 | 行为 |
|:--|:--|
| 同一份候选字节再次被问（同轮多入口 / 后台重触发） | 命中抑制：不累加 `gateRejected`/`fieldTableChanged`，不 redefine，宿主保持 blocked |
| 候选字节变了（用户改了捕获结构） | 签名不同 → 重新评估 → 再拒（正常计一次账） |
| 候选还原到与 `live` 字段表一致（只改方法体） | `fieldTableChanged=false` → 放行 `redefineClasses`，并清账 |

**反例（真机踩过）**：签名若取宿主目标类的 `originalBytes`，该字节恒定不变，于是 R4 还原后仍被判为
"同一份被拒候选"而永久锁死，同会话无法自愈。

## 5. 判据清单

1. 合成类重定义只有一个出口，计数/日志/闸门都在出口内；
2. 闸 1（成员 schema）必须排在所有闸之前；
3. 被拒候选不写任何"已生效"记账表；
4. 合成类被拒必须连坐宿主（`Abort`），不得半应用；
5. 抑制签名必须锚候选字节，且放行时清账。
