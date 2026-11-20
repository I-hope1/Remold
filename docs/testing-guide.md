# Remold 测试、探针与变异检查手册 (Testing & Probe Guide)

> **文档类型**：索引（探针任务命令、前提条件与变异检查清单；实现状态以 [`docs/status.md`](status.md) 为准）

核心测试铁律与不变量定义在 [`AGENTS.md`](../AGENTS.md) §6；本文件索引按改动域触发的专项探针、基准与变异检查要求。

## 1. 常驻门禁与核心套件

| 任务 | 挂载 `check` | 用途与注意点 |
|:--|:--:|:--|
| `./gradlew :tests:initFixOracle` | 是 | InitFix 差分 Oracle（纯 JVM 0 框架依赖）。Kotlin `ktfixV1`/`ktfixV2` 目录通过 `-Dremold.ktfix.v1/v2` 传入，**绝不能**进入任何 classpath；迭代期先跑它，收尾跑 `./gradlew check` |
| `./gradlew :tests:test` | 是 | 标准 JUnit 套件（含多版本 javac 现编夹具，入口用 `-Dhstest.javac8/11/17/21/25`）。测试数下限由 `tests/build.gradle` 的 `hstestMinTests` + `verifyTestCount` 守卫（总数 ≥ 基线且 `skipped == 0`） |
| `./gradlew :tests:verifyDocStatusMarkers` | 是 | 检查 `docs/{initfix,topology,offset,ecosystem}` 与各模块 `*/src` 无违规状态标记 |
| `./gradlew :tests:verifyStatusSingleSource` | 是 | 检查 `docs/**` 文档类型横幅与单一真相源边界（主源码不得出现交付状态散文） |
| `./gradlew :tests:verifyLogLanguage` | 是 | 检查主源码字符串字面量无 CJK/全角标点 |
| `.github/workflows/ci.yml` | CI | `setup-java` 8/11/17/21 + runner 25（`hstest.requireJdks=true`）+ `test-dcevm`（JBR 21 跑 `liveDcevmTest`）+ `p05-probe`（JBR 17/21）+ `build-native` |

## 2. 按改动域触发的专项探针与变异检查

以下探针多数需要 DCEVM（`-Pp05.jbr=<DCEVM JDK 目录>` 或环境变量 `JBR_HOME`）或真实模组容器，默认**不挂 `check`**，改动对应机制时必须按表运行：

| 改动域 | 必跑命令 / 探针路径 | 必须执行的变异检查 / 关键约束 |
|:--|:--|:--|
| L1 存量初值重放 | `:tests:initFixOracle` + `InitializerReplayTest` + `./gradlew :tests:doubleBraceProbe`（需 DCEVM） | 白名单放宽必须使非白名单用例变红；expected-CAS 改 `KIND_FORCE` 必须使"运行期改过值"用例变红 |
| A 档 Kotlin `apply` 重放 | `:tests:test --tests ApplyInitializerReplayTest` + `./gradlew :tests:kotlinApplyProbe -Pp05.jbr=<jbr>` + `:tests:doubleBraceProbe` | 白名单放宽必须使非白名单用例变红；expected-CAS 改 `KIND_FORCE` 必须使"运行期改值"用例变红 |
| P0.5 前置微验证 | `./gradlew :tests:p05Probe -Pp05.jbr=<jbr>` | 刻意用最小 agent `P05Agent`（绕开 `LayoutGate`/`InitFix`）实测 JVM 原语行为 |
| 缓存预热顺序 | `./gradlew :tests:cacheWarmingProbe`（只需 `-javaagent`） | 改动 transformer 顺序或 `canRetransform` 后必跑：实测 `false` 组先于 `true` 组执行，且缓存写入发生在对齐/织入之后 |
| Forge ClassInfo 同步 | `tests/fabric-mixin-probe/forge-classinfo/`（`bash run-forge-classinfo.sh`，Windows 用 `verify-evidence.cmd`） | 改动 `ForgeClassInfoSync` / `ForgeMixinHotSwap.reload` 时序前必跑 |
| NeoForge Mixin 热更 | `tests/neoforge-mixin-probe/`（Windows 用 `verify-evidence.cmd`） | 改动 `ClassInfoMemberSync` / `ForgeMixinHotSwap.reload` 时序或 NeoForge 识别逻辑前必跑 |
| 读者扫描成本 | `./gradlew :tests:readerScanProbe`（需 `-javaagent`，无需 DCEVM） | 改动 `RemovedMemberReaders.refs` 后必跑：断言常量池扫描是方法体精扫超集，且缓存开/关结果一致 |
| 派生数据缓存（PR2） | `./gradlew :tests:test --tests DerivedDataEquivalenceTest` + `./gradlew :tests:derivedDataBench` | 缓存键去掉任一维度（方法/blob）必须使 `DerivedDataEquivalenceTest` 变红；benchmark 同轮逐轮交替报 min/median/max |
| 构建完成 marker | `./gradlew :tests:buildTriggerProbe`（`-Pp05.jbr=<jbr>` 可选） | 7 项变异：去 sticky latch、后续 marker 重扫、关陈旧/未来校验、关 mtime 过滤、空扫描仍置 due、去 `classes.doLast` 接线、`buildTrigger=false` 仍注入 |
| 两阶段 Redefine 编排 | `./gradlew :tests:twoPhaseProbe -Pp05.jbr=<jbr>` + `tests/static-rewrite-probe/`（`build.sh` / `run.sh`） | 改动 `applyTwoPhase` / `redefineStageA` / `requiresTwoPhase` / `buildWithLiveConstructors` 前先跑：50k 实例 + 并发读，开两阶段 0 NPE、关两阶段稳定 NPE |
| 偏移稳定性与漂移 | `./gradlew :tests:offsetStabilityProbe -Pp05.jbr=<jbr>` | 实测单类布局、父类漂移、Updater 破坏与 C2 逆优化 |
| Lambda 对齐压力与捕获桥 | `LambdaAlignTortureTest`、`LambdaBridgeAlignTest`、`:tests:lambdaBridgeProbe -Pp05.jbr=<jbr>` | 前插/删除/重排与捕获桥运行时失败形态 |
| 批次堆扫描生命周期 | `BatchScanLocalRefLifetimeTest`、`BatchHeapScanEquivalenceTest`、`BatchLivenessPrefetchTest`、`:tests:initFixBatchScanProbe` | 必须守住 `GetObjectsWithTags` 局部帧弹出 UAF 与 `K` 次→`1` 次遍历计数 |
| 代际 VCS 与台账（PR1/PR3/B2） | `:tests:vcsPackProbe`、`:tests:vcsTwoPhaseProbe`、`:tests:vcsRevertProbe`、`:tests:vcsRevertSyncProbe`、`:tests:vcsA14Probe`、`:tests:vcsLedgerGcProbe` | 含真实 DCEVM 回退与 GC/堆占用基线读数 |

## 3. 日志与耗时统计口径约定

- 耗时类探针固定报 **min / median / max** 三数；结论须同时对 min 与 median 成立（否则标「口径敏感」），不得事后挑统计量。
- 探针日志默认输出到 `build/p05/`（带 `-Xlog:safepoint` `uptimenanos`、`-Xlog:gc*`、`-Xlog:jit+compilation=debug`）：引用 safepoint 时间戳时基准取**窗口开始**（`时间戳 − Total`）；unified logging 有缓冲，分析须在进程结束后进行。
- `float`/`double` 条件 CAS 修改后必须在真实 JDK 8 上补充验证（`initFixOracle` 运行在 JDK 25，区分不出 `compareAndSetInt/Long` raw-bits 与错误探针的差异）。
