# 活体 Raw ↔ Raw 对齐的真实规模读数

> **文档类型**：实测记录（只写读数与口径；机制状态与验收证据以 [`docs/status.md`](../status.md) 为准）

> 本文件记录 Run R（活体 `R_prev ↔ R_new` 对齐）在**真实规模语料**上的三项读数：`forceStaticLambdas`
> 命中类导致的闸门回退率、`Raw_prev` blob 预算与提交耗时、`runRGate` 与 `positionalFallbacks` 的分布。
> 规格见 [`18-live-raw-to-raw-alignment.md`](18-live-raw-to-raw-alignment.md)。
> 取数脚本：`tests/src/Pr3RealWorldReadingsProbe.java`（任务 `:tests:pr3RealWorldReadings`）。

## 1. 口径（先声明局限，再报数）

| 项 | 取值 |
|:--|:--|
| 代码版本 | `main @ a6ad2c8`（PR3 合流后） |
| 语料 | 本仓库五个模块的真实编译产物：`old` = `c998f3f` 的 `core/plugin-mixin/plugin-forge/plugin-arc/api` build/classes（415 类），`new` = `a6ad2c8` 同五模块（417 类）；按内部名稳定排序取前 **400** 类 |
| 开关 | 取数时 Run R 由 `-Dremold.poc.meta_source=true` 显式打开（当时默认关）；转正后默认开启，急停为 `-Dremold.agent.lambda_raw_alignment=false` |
| 执行方式 | Lead 串行单刷（不与任何构建/探针并发），单进程 |
| 统计量 | 计数类读数逐项一致（可复跑）；耗时报 min / median / p90 / max + 总计 |

**这不是游戏客户端语料**：本机没有可跑的 Minecraft/Mindustry 工程实例，语料换成本仓库自身的真实编译产物。
两者的共同点（真实 javac 产物、真实 `invokedynamic` 站点、真实两代源码编辑）覆盖了本文件要回答的三个问题；
差异点（游戏侧 lambda 密度更高、类更多）会影响命中率与预算余量的**绝对值**，见 §5 盲区。

## 2. M1：`forceStaticLambdas` 命中类是否导致闸门回退

| 读数 | 值 |
|:--|:--|
| 命中 `forceStaticLambdas`（字节被改写） | **7 / 400 = 1.8%**（占含 lambda 站点类的 11.3%） |
| 命中类的 `runRGate` 直方图 | **`PASSED` 7 / 7 = 100%**，`RAW_PREV_MISMATCH` **0** |
| 全语料 `runRGate` | `PASSED` 50（12.5%）、`TRANSLATION_INCOMPLETE` 350（87.5%）、`NO_RAW_PREV` 0、`RAW_PREV_MISMATCH` **0** |
| 限定"含 lambda 站点"的类 | `PASSED` 50 / 62（80.6%） |

**结论（已观测）**：在"同阶段写入 / 同阶段读取"的生产形态下，`forceStaticLambdas` 改写过字节的类
**不会**因为归一化而落 `RAW_PREV_MISMATCH`——因为 `preAlign` 快照取自归一化**之后**，两侧指纹同源。
因此 G1 **不需要**同时比对"转换前/转换后"双校验和；此前把它写成"已知会降级"是错的，已按读数更正。

**闸门判定力对照（已观测）**：反射式污染探针自己写入的 blob 后，25/25 类判 `RAW_PREV_MISMATCH` ——
说明上面的 0 不是"闸门不判"，而是"生产路径下不可达"。

## 3. M2：`Raw_prev` blob 预算与提交耗时

| 读数 | 默认预算（16 MiB / 每类 256 KiB） | 64 KiB 降级档 |
|:--|:--|:--|
| `blobBytes()` | 2,389,448 B（14.2%） | 59,308 B（90.5%） |
| `droppedBlobCount()` | **0** | **5** |
| `runRGate` | `PASSED` 50、`NO_RAW_PREV` 0 | `PASSED` 45、`NO_RAW_PREV` 5（1.3%） |
| `commit` 耗时 n=400 | 总计 **169.5 ms**，min 0.094 / median 0.303 / p90 0.645 / max 4.514 ms | 总计 118.1 ms，median 0.226 ms |
| `align` 耗时 n=400 | 总计 502.3 ms，median 0.344 ms | 总计 245.8 ms |

**结论（已观测）**：① 降级路径按设计发生且可观测（丢字节保哈希 → `NO_RAW_PREV` + warn，行为退回 Run E）；
② 平均每类 blob ≈ 5.97 KB，**16 MiB 约可容纳 2,700 类**（推断：按本语料的类体积分布外推，游戏工程类体积更大时
容量更小）；③ `commit` median 0.30 ms、p90 0.65 ms，400 类一批 ~170 ms——对热更批次属可接受量级。
**O(entries) 推断未被证实也未被推翻**：本任务没有做"受控条目数"微基准，只有"同一批内首末四分位耗时比"
（3.0×~10.0×，且不随条目数单调），不足以确认规模效应来源。

## 4. M3：`positionalFallbacks` 与闸门分布

| 读数 | 值 |
|:--|:--|
| `positionalFallbacks` | 默认预算档：**0 / 400 类**；64 KiB 档：0 / 400 |
| 缺 LVT 的类 | 4 / 400（1.0%），其 `runRGate`：`PASSED` 2、`NO_RAW_PREV` 2 |
| 唯一出现位置兜底的一例 | 在另一轮"连环真实编辑"语料（58 类、两代真实源码编辑）的 **Run E** 侧，1 类 1 次；Run R 侧为 0（`fewerInRunR` 1 / `equal` 57 / `more` 0） |

**结论（已观测）**：在本语料上 Run R 没有引入额外的位置兜底；缺 LVT 不是 Run R 的主要退化源
（它只影响形参名证据强度）。**未观测**：`E_prev` 里存在上一轮注入的幽灵桩/保留体/捕获桥时的字符串见证缺失——
单轮模型造不出该状态，见 §5。

## 4.1 与生产链的逐项对账（探针 → `RemoldAgent`）

跑读数前逐项核对过"探针模型 vs 真实活体路径"的形态差异，结论是：**差异只会让 `RAW_PREV_MISMATCH` 更容易出现，
不会让它更容易消失**，所以 §2 的 0 在两个模型下都成立。

| 环节 | 真实 `RemoldAgent` 路径 | 探针主档（`R(rawBlob)`） | 探针对照档（`R(forced-both)`） |
|:--|:--|:--|:--|
| 新侧归一化 | `forceStaticLambdas(newBytecode)` 在 `preAlignBytes = newBytecode` **之前**执行 | 对齐输入用 `gen1Forced`，新侧用未归一化 `gen2` | 两侧都用归一化形态 |
| `preAlign` 快照 | 归一化**之后**的待对齐字节 | 记录**未归一化**的 `gen1` | 记录归一化后的 `gen1Forced` |
| 旧侧基线 | `normalizeAlignBaseline(oldBytecode)`（同样过 `forceStaticLambdas`） | 直接用 `gen1Forced`（等价于归一化后的生效侧） | 同左 |
| `alignOutput` vs `jvmEffective` | 两者**可以不同**：`align()` 之后 JVM 还会再跑一次 transformer（`InitFix` 打补丁、织入），`commit` 分别记 `alignOutput` 与 `def.getDefinitionClassFile()` | 两者都传 `gen1Forced`（同一份） | 同左 |
| G1 采信口径 | `jvmEffHash` **或** `alignOutputHash` 任一命中 | 只覆盖 `alignOutput` 这一路 | 同左 |

由此产生一条**仍然存在的建模缺口**（已计入 §5）：探针从未构造 `alignOutput ≠ jvmEffective` 的类，
即"`align()` 之后又被 `InitFix`/织入改写"的形态没有进入读数。该缺口只影响 G1 采信到哪一路哈希，
不影响"归一化是否导致回退"这个结论（`preAlign` 与 `jvmEff` 的先后关系在两个模型里都已覆盖）。

## 5. 盲区（探针测不到，不得据此下结论）

1. **单轮模型**：`E_prev` 里没有上一轮注入的幽灵桩 / 保留体 / 捕获桥，因此"见证缺失导致额外兜底"这一项
   在本轮**未被真正施加**，M3 的 0 不能外推到多轮连续热更。
2. `positionalFallbacks` 分布退化为 0 桶（仅 1 例），"缺 LVT vs 见证缺失"的分解无统计意义。
3. 语料非游戏客户端（见 §1），命中率/预算余量的绝对值需在真实工程上复测。
4. 耗时是单进程非独占、含 JIT 预热（`max` 有明显冷启动离群），只作量级参考，不用于跨版本比较。
5. `alignOutput ≠ jvmEffective` 的类（`align()` 之后又被 `InitFix` 打补丁或织入改写）未被构造，G1 的"双哈希任一命中"只覆盖了 `alignOutput` 一路；见 §4.1。
6. 探针对**每个类**都 `commit` + `align`，而生产只在 `hasPotentialLambdas` 时对齐；因此
   `TRANSLATION_INCOMPLETE` 的真实频率应低于本文件的 87.5%（该 87.5% 主要由"没有本地 lambda 槽位"的类贡献，
   这类类在生产里根本不会进入对齐）。
