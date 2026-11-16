# Lambda 对齐结论的发布与按类检索契约

> **文档类型**：设计规格（只写规格/契约；当前实现状态与验收证据以 [`docs/status.md`](../status.md) 为准）

> 本文件定义 `LambdaAligner` 每轮对齐结论的**发布形式**，以及"批次结束后才读结论"的消费者
> （`CellPropertyRef.afterRedefineBatch`）的取用与降级规则。对齐算法本身（配对判据、捕获桥、孤儿桩）
> 见 [`01-invariants-and-remapping.md`](01-invariants-and-remapping.md)～[`11-orphan-lambda-body-retention.md`](11-orphan-lambda-body-retention.md)。

## 1. 问题：逐类对齐 + 批末消费

热更批次里每个类各自调用一次 `LambdaAligner.align(oldBytes, newBytes, ...)`，而批次监听器
（`HotswapBatchListener.afterInternalBatch` → `CellPropertyRef.afterRedefineBatch`）在**整批类都重定义完成之后**
才执行一次。若消费者只读"最近一次对齐结果"，它能看到的只有**最后一个被对齐的类**。

因此发布契约必须区分两种读取时机：

| 读取时机 | 允许的入口 | 理由 |
|:--|:--|:--|
| `align()` 返回后、同一线程立即读 | `LAST_RESULT`（全局单槽） | 单次 volatile 发布，读者拿到的是本轮完整快照；`inventedNames` 必须在此时落账 |
| 批次结束之后（可能隔着若干类的对齐） | `lastResultFor(内部名)`（按类登记） | 单槽此刻描述的是别的类；按类寻址才能取回本类那一轮的结论 |

> 契约：**跨类批次里禁止用 `LAST_RESULT` 做映射决定**。类名比较（`alignedClass == 目标类`）不能补救——
> 批内前序类与单槽的类名必然不等，结果是静默退回相似度猜测。

## 2. 发布点与语义

每次 `align()` 调用只按以下顺序发布，且**每个类每轮都有且只有一条最终结论**：

1. **入口清空**：解析新字节的内部名（不可解析则不记账），以空结果覆盖该类旧快照。
   空结果 = "本轮没有结论"（`methodMapping == null`），**不等于**"该类没有 lambda"。
2. **成功覆盖**：对齐产出映射后发布完整快照（统计、映射、匿名类哈希、自造名、缺失 LVT 集合）。
3. **失败覆盖**：异常降级路径同样发布空结果；`LambdaAlignmentStrictException` 先按第 1 步清空再抛出。

快照对象在构造后不可变（`AlignmentResult` 的集合字段做防御性拷贝），发布是单次引用写入。
`LAST_RESULT` 与按类表在同一次调用里写入；读者不得把两者混读成"一轮结果"。

### 2.1 有界保留

按类登记表是 **LRU、上限 128 个类**：只保留"最近被对齐过的类"，淘汰不改变 `LAST_RESULT`。
热更批次的类数远小于该上限；淘汰只可能发生在长期运行且持续对齐大量不同类的进程里，
届时消费者对该类退化为"无结论"（见 §3 降级），不会读到错误的类。

## 3. 消费者契约

批次回调读取映射时必须：

1. **按类检索**：`lastResultFor(目标类内部名)`；
2. **兼容回退**：仅当按类登记缺失时，才允许读 `LAST_RESULT`，且**必须**满足
   `LAST_RESULT.alignedClass == 目标类内部名`——类名相等即同一个快照，不会借到别的类的映射；
3. **只取本模型存在的方法**：`oldKey ∈ oldModel` 且 `newKey ∈ newModel` 才写入映射，
   防止跨轮同名键误配；
4. **缺失即降级**：取不到结论时退回既有的相似度兜底（阈值 0.6），
   **禁止**借用其他类的映射，也禁止把"无结论"当成"没有 lambda"而删除站点。

`afterRedefineBatch` 逐类回调，因此每个类都必须命中自己的快照；批内前序类不得回落到相似度，
除非该类本轮确实没有产出映射（align 失败/早退）。

## 4. 并发与内存

- 单槽发布是一次 `volatile` 写；按类表读写在同一把监视器锁内。
- 登记表不持有 `Class` 或 `ClassLoader`，键是内部名字符串，值为不可变快照。
- 登记表不参与任何"缓存命中即跳过对齐"的判定，只服务读取。

## 5. 验证锚点

- `tests/src/CellLambdaBatchAlignTest.java`：
  - 批内 A、B 依次对齐后，`matchMethods` 对 A 仍取到 A 自己的映射（相似度兜底被构造成 0 分）；
  - 未对齐过的类不会借用任何已登记快照；
  - 早退/失败轮会覆盖该类的旧快照（旧映射不再可取）。
- 判定力（变异）：把 `lastResultFor` 退化为读 `LAST_RESULT`、或去掉消费者的按类检索，
  上述用例必须变红。

## 6. 相关文档

- 状态与残余风险：[`docs/status.md`](../status.md)（风险 22 记录 `CellPropertyRef` 程序分析与执行手段的深度边界）。
- 批次生命周期与监听器顺序：`RemoldAgent.notifyBatchListeners`、`api/src/remold/HotswapBatchListener.java`。
- UI 属性追踪的消费者侧：[`plugin-arc/src/remold/uihook/CellPropertyRef.java`](../../plugin-arc/src/remold/uihook/CellPropertyRef.java)。
