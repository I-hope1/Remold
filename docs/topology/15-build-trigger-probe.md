# 构建完成 marker 探针实测记录

> **文档类型**：实测记录（只写读数与运行条件；实现状态、验收证据汇总以 [`docs/status.md`](../status.md) 为准）

契约见 [`14-build-trigger-mode.md`](14-build-trigger-mode.md)。本文件只记录
`./gradlew :tests:buildTriggerProbe` 的一次真实端到端运行读数。

## 1. 运行条件

| 项 | 值 |
|:--|:--|
| 平台 | Windows（本机开发环境） |
| JVM | `JAVA_HOME=F:\files\java\jdks\jbrsdk_jcef-25.0.2`（JBR 25，`-XX:+AllowEnhancedClassRedefinition` 可接受；未显式传 `-Pp05.jbr`） |
| 探针 | `tests/src/BuildTriggerProbe.java`，真实 `-javaagent` + 真实 `WatchService` + 真实 `redefineClasses` |
| 夹具 | `demo.Subject.value()` 仅改方法体（无新增字段/方法、无匿名类、无 lambda） |
| 兜底窗口 | `-Dremold.agent.file_shake_ms=800` |
| 轮次 | 快路径 20 轮；去触发对照 3 轮；每轮之间静默 60ms |
| 统计口径 | 同一进程、同一轮次；延迟 = `marker 写入完成` → `HotswapBatchListener.afterInternalBatch` 回调（含 redefine 与后处理） |

## 2. 读数

### 2.1 快路径（BT1，20 轮）

marker → 批次回调：

| min | median | max |
|--:|--:|--:|
| **57ms** | **97ms** | **325ms** |

原始样本（ms）：`146, 66, 57, 64, 68, 72, 103, 80, 144, 74, 84, 143, 107, 250, 91, 87, 103, 325, 127, 172`

### 2.2 去触发对照（BT2，3 轮，不写 marker）

编译完成 → 批次回调：

| min | median | max |
|--:|--:|--:|
| **883ms** | **887ms** | **3652ms** |

原始样本（ms）：`883, 3652, 887`。

- 该组 `max=3652ms` 的那一轮批次自身 `redefine=2855ms`（同一批 `total=2868ms`）；本探针夹具只有 1 个类，
  属于单轮 redefine 长尾，不是兜底窗口变长。min/median 与 800ms 窗口 + 常规 redefine 量级一致。
- 对照说明：同一编译、同一进程，只去掉 marker，批次就只能在兜底窗口到期后发生。

### 2.3 定性场景

| 场景 | 读数 |
|:--|:--|
| BT3 无变更 marker（先推进一次已处理点） | 0 次非空批次（批次计数 before=23, after=23），值不变 |
| BT4 取消兜底（窗口临时设 3000ms，事件先到、marker 150ms 后到） | marker→批次 **114ms**；随后 700ms 内无第二次非空批次 |
| BT5 构建中途（窗口 300ms，先落一批、再 marker 补余下） | 两次值均可见；marker 批次后 700ms 内无非空重复批次 |
| BT6 陈旧 marker（end = now − 60s） | 0 次批次；agent 日志 `stale marker (delta=-32007ms)` |

## 3. 本次读数能说明 / 不能说明

**能说明（已观测）**：在本机 JBR 25 上，对"仅改方法体"的单个类，marker 路径把
`marker→批次回调` 的 median 从 887ms（兜底）降到 97ms；marker 能取消已挂起的 3000ms 兜底；
无变更 marker 与陈旧 marker 不产生非空批次。

**不能说明（未验证）**：

- 未在 JBR 17/21 DCEVM 与 Linux/macOS 上复测；macOS 的高灵敏度 watcher 修饰符路径没有实测读数。
- 未覆盖多类/匿名类/lambda 批次；这类批次的 redefine 与对齐成本不属于本探针口径。
- 未做连续构建的长期稳定性统计（本文件只记录一轮 20 次）。
- 冷启动首轮（JIT 未预热）与后续轮次混在同一组样本里；未按预热状态分层。
