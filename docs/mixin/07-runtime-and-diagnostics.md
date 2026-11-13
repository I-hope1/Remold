# 07 · 运行期门闩、看门狗与双通道堆哨兵

> **文档类型**：设计规格（只写规格/决策；实现状态以 [`docs/status.md`](../status.md) 为准）

## 1. 启动门闩：`StartupGate` + `HostReadinessProbe`

- `core/src/remold/StartupGate.java`：纯状态机 `PENDING → READY`，`-Dremold.agent.ready_timeout_ms` 控制超时；
  超时后 fail-open（放行并 warn），避免"永不就绪"把用户卡死。
- `plugin-fabric` 的 `HostReadinessProbe` 按 MC 主循环 tick 判定宿主就绪；探测不到即 fail-open。
- core 侧**不含**任何加载器/游戏专属判断：就绪与否由 `api` 的 `HotswapPlugin.isHostReady()` 投票。
- **只认 `loadedClassesMap` 的宿主快照**判定类是否已加载，严禁回退 `SystemClassLoader`
  （回退会亲手定义裸副本，直接触发 §01 的 M1/M2 事故）。

## 2. 批次看门狗：`BatchWatchdog`

`enter/exit/current` 作用域 API + `armedWatchdogs()` 不变量；`-Dremold.agent.batch_stall_ms` 默认 15000。
超时打 `[HOTSWAP-STALL]` 并对**同一批次只 dump 一次** `ThreadMXBean.dumpAllThreads(true, true)`。
挂点是 `RemoldAgent.processChanges`（覆盖防抖与手动批次）与 `retransformLoaded`，漏斗在 `triggerDueHotswap`。
目的：把"卡住"与"在跑"从日志上一眼分开，而不是靠猜。

## 3. JVM 级失败透视：`[VM-INFO]`

`RemoldAgent` 捕获 `InternalError` / `VerifyError` 后，经 DiagnosticCommand `vmInfo` 抓 HotSpot
`Internal exceptions (N events)` 缓冲的最后 160 行写进日志。JBR 的裸 `InternalError` 因此可读。

## 4. 双通道堆哨兵：`HeapFieldWatch`

默认关闭（`-Dremold.probe.heapwatch=true`，`-Dremold.probe.heapwatch.class=…`、`.field=…`），在
`redefineClasses` 三切面（`before-redefine` / `after-redefine` / `after-field-patch`）与合成类两切面
（`before/after-synthetic-redefine`）各取一次快照。

**通道设计（为什么必须两条）**：

| 通道 | 实现 | 失效形态 |
|:--|:--|:--|
| 主 | `LibTool.getInstances(clazz)`（JVMTI 堆遍历）+ 反射读字段 | 反射读可能被 JBR "影子 Class" 影响 |
| 副 | 字段 `VarHandle` / getter 读 | 字段不可访问时 `unavailable` |

每条切面都按 `name + loader` **重新解析**目标 `Class`（`ClassCopySelector.pickNamed`），
不复用上一轮的 `Class` / `Field` 句柄——JBR 每次重定义会把静态字段里的 `Class` 引用换回旧版本，
缓存句柄会读到旧字段表，表现为"假阴性 nullCount"。

**差分恒等式（每切面自检，破则 warn）**：

```
same + created = afterTotal        same + collected = beforeTotal
persisted + vanished = beforeNull  newlyNull = afterNull - persisted
```

另打值类型直方图 `valueTypes{null=… 类名=…}`：它把"哪些值被抹"从计数变成可归因的分类。

## 5. 判据清单

1. 就绪判定必须可投票、可超时、fail-open；core 不出现加载器专属判断；
2. 看门狗每批只 dump 一次，dump 必须带锁信息；
3. 哨兵每条切面必须重解析类句柄，且必须两条通道；
4. 差分恒等式必须内建自检，不得只报聚合数字。
