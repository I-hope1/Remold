# 构建完成 marker：提前触发与兜底取消的契约

> **文档类型**：设计规格（只写规格与契约；当前实现状态、验收证据与残余风险以 [`docs/status.md`](../status.md) 为准）

本文件定义"构建完成信号"（build-trigger marker）的写入格式、消费规则、与 `fileShakeMs` 兜底窗口的关系，
以及可观测的边界。它不定义任何实现进度；探针读数见 `15-build-trigger-probe.md`。

## 1. 术语与范围

| 术语 | 含义 |
|:--|:--|
| 兜底窗口 | `-Dremold.agent.file_shake_ms`（默认 `800`，Gradle DSL `fileShakeMs`）的 trailing-edge 静默窗口；没有 marker 时唯一的触发依据 |
| marker | 构建工具在编译任务完成后原子写入的普通文本文件；路径由 `-Dremold.agent.build_trigger_file` 给出 |
| 快路径 | 收到有效 marker 后，不等兜底窗口到期，立即把当前变更作为一批处理 |
| 迟到事件 | marker 已被消费之后，才由文件 watcher 线程入队的同一次构建产生的事件 |

范围：只处理 **Class/jar 流**。资源/资产流（`assetShakeMs`）不在范围内，marker 不改变其节奏。

## 2. marker 契约

### 2.1 启用方式

```
-Dremold.agent.build_trigger_file=<绝对路径>   # 出现即启用；缺省则不启用
```

Gradle 插件侧的对应开关是 `remold { buildTrigger = <Boolean> }`（`null` 视为 `true`）。`false` 时插件既不写
marker，也不下发该 JVM 参数。手动启动（无插件）的用户不设置该参数即可保持旧行为。

### 2.2 写入方与写入时机

- v1 只由 **`classes` 任务**的 `doLast` 写 marker；`doLast` 在该任务的全部依赖（含各语言编译任务）完成后执行，
  因此磁盘上的 `.class` 已完整落盘。
- `jar` / `remapJar` **不写 marker**：开发期热更监听的是 class 输出目录，jar 产物不是该批的输入；jar 启动模式的
  marker 语义（产物经 `deployArtifact` 复制后的完成点）不在 v1 范围。一次构建最多一个 marker。
- 同一构建内 `classes` 只执行一次；`build_id` 由任务名 + 结束毫秒 + 随机后缀组成，保证不同构建不重复。
- 写入必须原子：同目录临时文件 + `ATOMIC_MOVE`（不支持时退回 `REPLACE_EXISTING`）。写失败只告警，不使构建失败。
- 配置缓存：`doLast` action 只允许捕获 `File` 与 `String`，不得捕获 `Project`、`Task` 或扩展对象。

### 2.3 格式

UTF-8/ASCII 文本，限读 8 KiB：

```
remold-build-trigger v1
build_end_millis=<epoch millis>
build_id=<task>-<epoch millis>-<suffix>
task=classes
```

| 字段 | 必填 | 语义 |
|:--|:--|:--|
| 首行 | 是 | 固定 `remold-build-trigger v1`；不符即拒绝 |
| `build_end_millis` | 是 | 写入时刻的挂钟毫秒（`System.currentTimeMillis()` 域）；用于扫描下界、陈旧/未来判定与去重 |
| `build_id` | 建议 | 去重键；缺省时退化为按 `build_end_millis` 去重 |
| `task` | 否 | 诊断用任务名 |

### 2.4 接受与拒绝规则

1. 文件不可读、超过 8 KiB、首行不符、`build_end_millis` 缺失或不可解析 → 拒绝（warn，附原因）。
2. `build_end_millis < agentStartMillis - 5000` → 视为**陈旧**拒绝；`> now + 5000` → 视为**未来**拒绝。
   warn 必须同时给出差值（`delta=<ms>`），便于识别容器/远程挂载的时钟偏差。
3. 同一 `build_id`（或 `build_id` 缺省时的同一 `build_end_millis`）只处理一次。
4. **无效 marker 不更新**任何已处理状态（`lastProcessedBuildEndMillis` / `lastProcessedBuildId`）；只有被接受的
   marker 才更新，且更新发生在入队/触发判定之后。

### 2.5 扫描下界

```
since = lastProcessedBuildEndMillis > 0
        ? lastProcessedBuildEndMillis          // 后续 marker：严格下界
        : agentStartMillis - 2000ms            // 首个 marker：带余量
```

- 首个 marker 用 `agentStartMillis - 2000ms`：热更开始前已经构建并加载的类不会被重新处理；
  启动后到首个 marker 之间的改动会被覆盖；2s 余量吸收文件系统 mtime 精度（最差 1s）与挂钟边界。
- 后续 marker 用上一次被接受的结束点，**不再减余量**：否则每次连续构建都会把上一次构建已处理过的
  文件重新扫进来，产生一个纯 no-op 批次（无 redefine，但有扫描/读盘成本与日志噪音）。
- 被接受的 marker 即使没有扫描到任何文件，也要推进 `lastProcessedBuildEndMillis`，避免同一 marker 反复扫描。

### 2.6 扫描范围

- 遍历 `activeWatchDirs` 下的 `.class`（递归）与根级 `.jar`/`.zip`，加上 `activeWatchJars` 直接传入的 jar；
- 只取 `Files.getLastModifiedTime >= since` 的文件；遵守 `HotswapPlugin.isWatchPathIgnored`；
- 重叠 watch 目录按绝对路径去重；`.class` 进 Class 队列，jar/zip 进 jar 队列（由 `JarExtractor` 处理）；
- 条目数超过 5000 时打一条 warn，但不截断。

## 3. 消费流程与批次语义

```
marker 写入 → trigger watcher 收到事件
  → 解析/拒绝判定/去重
  → 扫描补入 Class 与 jar 队列（与已入队的 watcher 事件合并、按 Path 去重）
  → 若两个队列均为空：不标 due、不触发（debug 日志）
  → 否则：markClassDueNow()（sticky）→ reschedule → 取消已挂起的兜底 task
  → scheduler → 就绪门闩 → processChanges（既有路径：对齐、布局门、两阶段、InitFix、看门狗）
```

### 3.1 sticky due：迟到事件不得推迟已置位的到期

`DebounceWindow` 的 Class 流仍是 trailing-edge，但 marker 置位必须**粘住**：

- `markClassDueNow(nowNanos)`：`classPending = true`；`classDueLatched = true`；`classDeadlineNanos = nowNanos`；
- 在 `clearClass()`（批次被取走）之前，任何 `onClass(...)` **不得延后** deadline（取 `min`，或直接保持）；
- `clearClass()` / `clearAll()` 清除 latch；之后的新事件重新按兜底窗口计时；
- 资源流（`onAsset`）不受 latch 影响。

这条是"提前触发能否稳定达成"的关键：两个 watcher 线程没有顺序保证，marker 之后仍可能到达同批的 class 事件。

### 3.2 "取消兜底"的边界

- 能取消：marker 到达时**尚未到期**的兜底 task。marker 处理会 cancel 该 task 并立刻重挂 0 延迟触发。
- 不能取消：marker 到达前已经到期并跑过的兜底批次。若一次构建耗时超过 `fileShakeMs`，编译中途的静默可能
  已经产生一批；marker 只补余下的类，已生效的类由磁盘指纹跳过。要压制"构建进行中"的兜底需要 build-start
  信号，见 §8。
- 迟到事件：marker 批次之后到达的同批事件会重新挂一次兜底；该批次读取的字节与已生效字节相同，磁盘指纹比对
  全部跳过，**不产生 redefine、不触发 `@OnReload`/InitFix/批次监听器回调**。不按 mtime 抑制迟到事件：
  时间戳精度不足时可能吞掉紧随其后的真实保存。

### 3.3 事件顺序与 no-op 批次

正确性**不依赖** watcher 线程与 trigger 线程的先后顺序：marker 路径自带扫描，事件先到或后到都收敛到同一批
变更。顺序只影响"是否多出一次 no-op 批次"：

- marker 先到：扫描补全 → 快路径批次；随后到达的同批事件形成 no-op 批次；
- 事件先到：marker 把已入队事件直接取走 → 快路径批次；无 no-op 批次。

no-op 批次的可观测规则：`HotswapBatchListener` 的 `afterInternalBatch` 不回调（无成功重定义），
`afterInternalBatchReport` 仅在"有可观测活动"时回调，因此空批次不产生回调；批次仍会经过就绪门闩与看门狗，
但不改变任何类状态。日志必须能区分这两类批次（快路径批次与 no-op 批次分别有可检索前缀）。

## 4. 时钟、单位与平台

- `DebounceWindow` 的所有参数与 deadline 都在 `System.nanoTime()` 域（单调、与挂钟无关）；`build_end_millis`
  在挂钟毫秒域。两者只在 marker 处理时换算一次（`System.currentTimeMillis()` 仅用于日志与陈旧判定）。
  Javadoc 必须写明这一点，避免把挂钟毫秒传入 `markClassDueNow`。
- 陈旧/未来判定假设 Gradle 进程与游戏 JVM 同机同时钟。容器、WSL、网络挂载下该假设可能不成立；因此拒绝日志
  必须带差值，便于用户判断是否需要关闭 marker（`buildTrigger = false`）。
- macOS 上 `WatchService` 的默认轮询灵敏度可能明显放大 marker 的到达延迟。trigger watcher 应在平台为 macOS 时
  尽力使用高灵敏度修饰符（`com.sun.nio.file.SensitivityWatchEventModifier.HIGH`，通过反射探测；取不到则按默认
  注册，失败开放）。该修饰符只用于 trigger watcher，不改变普通 class watcher 的既有行为。

## 5. 开关、默认值与兼容性

- Gradle DSL `buildTrigger` 默认 `true`：新插件默认给所有启动形态（`runRemold`、`startRemold`/脚本、
  IDEA run config、Loom/Forge/NeoForge 注入、`attach`）追加一个 `-Dremold.agent.build_trigger_file=...`。
  这是**默认 JVM 参数的变化**，CHANGELOG 必须点名。
- 旧 agent + 新插件：未知 `-D` 被忽略，行为不变。
- 新 agent + 旧插件：没有该参数 → 不启动 trigger watcher，行为不变。
- 用户显式 `buildTrigger = false`：不写 marker、不下发参数，回到纯兜底。
- marker 与 `fileShakeMs` 相互独立：`fileShakeMs` 只决定"没有 marker 时等多久"，不决定 marker 是否生效。

## 6. 失败模式（实现必须覆盖）

| 场景 | 期望 |
|:--|:--|
| 无 trigger 文件 / 旧 agent | 兜底窗口行为不变；无新增日志 |
| marker 损坏、缺字段、陈旧、未来 | 单条英文 warn（含原因与差值），不处理；后续有效 marker 正常 |
| 同一 marker 多个 FS 事件、重复内容 | 按 `build_id` 去重，只处理一次 |
| marker 到达但无变更 | 不标 due、不触发、不自旋 |
| marker 先到、事件后到 | 扫描补全并立即触发；迟到事件只产生 no-op 批次 |
| 批次进行中 marker 到达 | 入队并按锁排队，当前批结束后立即处理 |
| 构建时长 > 兜底窗口 | 中途可能先跑一批（既有行为）；marker 补齐余下类，最终状态正确 |
| `clean` 删除 build 目录 | trigger watcher 重注册（约 1s 重试）；扫描仍能发现重建输出 |
| 构建失败（`doLast` 未执行） | 无 marker；已写部分 `.class` 走兜底（与既有行为一致） |
| 多个游戏 JVM 共用 build 目录 | 各自消费 marker；磁盘指纹去重保证幂等 |
| 非本地/保留 mtime 的文件系统 | 扫描可能漏；事件路径保证最终一致 |
| 配置缓存命中 | 第二轮构建仍必须写出有效 marker（见验收要求） |

## 7. 验收要求（细节见 `15-build-trigger-probe.md`）

- sticky due：`markClassDueNow` 后立即 `onClass` 断言仍 due（先写红再实现）。
- 取消兜底：事件已入队、兜底 task 已挂起时收到 marker，批次必须在兜底 deadline 之前开始；同轮同口径对照为
  "不写 marker 时批次在兜底 deadline 之后"。
- 配置缓存：第二次构建（缓存命中）必须断言 marker **被写出且内容有效**，不能只断言"不报错"。
- 快路径耗时：marker 写入 → 批次开始（N=20，报告 min/median/max，同轮同口径）。
- 变异检查至少覆盖：去掉 latch、去掉陈旧/未来校验、去掉扫描 mtime 过滤、空扫描也标 due、去掉 `classes.doLast`
  接线、`buildTrigger=false` 仍下发参数。

## 8. 非目标

- 不引入 `fileShakeMs = -1`，不改变其默认值与负数校验；
- 不做 build-start 信号 / 构建期间压制兜底（需要任务开始信号 + 超时恢复）；
- 不按 mtime 抑制迟到事件；
- 不加速资源/资产流；不做 IDE javac 直编的零延迟（需 IDE 插件或稳定写检测）；
- 不做 jar 启动模式的 marker、Gradle daemon 自监听、socket/HTTP 通道、marker 携带变更清单；
- 不做 Maven 等其它构建工具的适配实现（只提供 marker 文件契约）。
