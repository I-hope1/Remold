# sourceBytecodeCache 生命周期设计稿（第二轮）

本文只定义设计，不描述实现状态。目标：把 `sourceBytecodeCache` 从“每轮先写、失败不回滚”的近似缓存，改成**按类提交、失败可回滚**的“最近一次实际生效的源码基线”。

## 1. 现状与问题

`sourceBytecodeCache` 目前有三个消费者，它们都把它当作“上一轮实际生效的源码字节”：

1. `AnonClassAligner` 的 `oldAnon` / `oldResolver`；
2. `LambdaAligner` 的旧侧基线；
3. `AnnotationTransformer.isNoOpExternalRedefine`（外部重定义的 no-op 判定）；
4. Mixin 重织入读取“原始源码字节”。

但 `processChanges` 在**匿名类对齐之后、布局门与 `redefineClasses` 之前**就整批 `put`（`RemoldAgent.processChanges` 的“记录源码级对齐基线”段），没有失败回滚。于是失败/被拒/回滚的轮次会把“JVM 从未加载过的版本”写进缓存，下一轮对齐/no-op 判定会把它当旧侧。

`inventedSlots` 已经采用“只在 redefine 成功后落账”的生命周期；`sourceBytecodeCache` 需要同一纪律。

## 2. 设计目标

- 缓存中永远只放**实际生效**的源码字节（或明确标记为“本轮待提交”的临时值）；
- 任何失败路径都能回到上一版；
- 不改变 `bytecodeCache`（运行时字节）的语义；
- 不引入新的强引用/Class 键；
- 失败路径可观测（日志/台账）。

## 3. 数据结构

建议引入一个按轮次的提交对象，而不是直接写全局 map：

```text
SourceRound
  staged: Map<String, byte[]>      // 本轮准备写入的源码字节（对齐后、织入前）
  previous: Map<String, byte[]>    // 本轮开始前 sourceBytecodeCache 的值（仅 touched key）
  committed: Set<String>           // 本轮实际生效的类名
```

- `stage(key, bytes)`：只写 `staged`，不碰全局缓存；同一 key 重复 stage 以最后一次为准。
- `commit(Set<String> applied)`：对 `applied` 中的 key 写全局缓存；`previous` 保留用于诊断。
- `rollback()`：不写全局缓存；对已写入的 key 恢复 `previous`（如果实现选择先写后回滚）。
- 未 stage 的 key 不动。

**更推荐“先 staged、后 commit”**，而不是“先写全局、失败再恢复”：后者在并发读（对齐/外部重定义）窗口里仍可能读到未生效值；前者从根上避免。

## 4. 四条失败路径与 rollback

### 4.1 批量 `redefineClasses` 失败，逐类兜底

- 批量失败：按 JVM 语义没有任何类生效；`SourceRound` 仍保持 staged，不 commit。
- 逐类重试：
  - 成功类加入 `applied`；
  - 失败类不加入，保持旧 source；
  - `InitFix` 补丁按既有生命周期（成功类保留，失败类弹出）。
- 轮末 `commit(applied)`；`applied` 之外的 staged 条目丢弃。

### 4.2 单类 `redefineClasses` 失败

- 该类不加入 `applied`；
- 不写 `sourceBytecodeCache`；
- 与 4.1 共用同一 `SourceRound`，不单独回滚整个批次。

### 4.3 对齐事务 rollback（`HOTSWAP-PARTIAL`）

- `AlignmentTransaction.rollback(true)` 会把旧匿名类字节钉在 `pendingAlignedClasses`；`sourceBytecodeCache` 必须同步：
  - 宿主若已逐类成功：宿主的新 source 可提交（它确实生效）；
  - 未成功的匿名类：不提交，保留旧 source；
  - 不能因为宿主成功就把整组新 source 提交。
- 因此 `commit` 的粒度必须是**类名集合**，不能按“事务组”整体提交。

### 4.4 重定义前的拒绝 / 跳过

包括：

- `hierarchyChanged` 的 `continue`；
- `LayoutGate` / `LocalClassGuard` / `RemoldMemberScanner` 拒绝；
- 黑名单、不可修改类、`isNonLoadableClass` 跳过；
- `REDEFINE_MODE.inject` 失败（新类注入失败）；
- `REDEFINE_MODE.lazy_load`（类尚未真正加载）。

这些类都不进入 `applied`，不提交 source。特别是 `lazy_load`：只挂载路径，等真正加载/重定义成功后再提交。

## 5. Rollback 算法

```text
round = new SourceRound()
for each class in batch:
    round.stage(className, alignedSourceBytes)

applied = applyRedefinitions(...)   // 现有返回值：successful
round.commit(applied)

// 若某条路径需要整体回滚（异常、提前 return）：
round.rollback()                    // 全局缓存未被污染，无需恢复
```

如果实现暂时保留“先写全局”的写法，则必须在 `try/finally` 中：

```text
for (key : touched) previous.put(key, sourceBytecodeCache.get(key))
... 现有流程 ...
finally {
    for (key : touched)
        if (!applied.contains(key)) restore(key, previous.get(key));
}
```

但推荐 staged 方案，避免外部重定义/对齐线程在窗口内读到未生效值。

## 6. 与其它机制的关系

- `bytecodeCache`：仍只在 redefine 成功后更新；`sourceBytecodeCache` 的提交集合应与它一致（外部/动态路径另有自己的提交点）。
- `pendingAlignedClasses`：rollback 时钉旧字节；source 不提交未成功类，二者方向一致。
- `inventedSlots`：同样只在成功集合上 commit；可复用同一个 `applied` 集合。
- `InitFix`：`transform` 在 redefine 前读取 source 做旧侧分析；它应读“上一轮已提交”的 source，而不是本轮 staged；因此 staged 不能直接写全局缓存。
- Mixin：`readOriginalBytes` 应读“最近一次实际生效的源码”；如果本轮尚未 commit，它读旧值是正确的。

## 7. 验收

必须覆盖四条失败路径 + 一条正常路径：

1. 批量失败 → 逐类部分成功：成功类 source 更新，失败类保持旧 source；
2. 单类失败：该类 source 不变；
3. 事务 rollback：宿主成功、匿名类失败时，只有宿主 source 更新；
4. 布局门拒绝：该类 source 不变；
5. 全成功：所有类 source 更新。

断言应落在下一轮的实际行为，而不是缓存内部值：

- 下一轮对齐的 `oldAnon` 是旧字节；
- 外部重定义 no-op 判定使用已生效版本；
- Mixin 重织入读到的原始字节是旧版本；
- 失败类重新保存后仍能被判定为 changed（不吞重试）。

## 8. 非目标

- 不改变 `bytecodeCache` 的提交时机；
- 不改变 `pendingAlignedClasses` 的 pin 语义；
- 不引入按 ClassLoader 分槽的 source cache（留待多加载器专题）。
