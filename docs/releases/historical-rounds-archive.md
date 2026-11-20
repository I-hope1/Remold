# 历史轮次修复与实证归档 (§0.1 – §0.17)

> **归档说明**：本文件收纳从 [`docs/status.md`](../status.md) 拆出的逐轮历史修复记录（`§0.1 – §0.17`）。当前机制实现状态、残余风险与路线图以 [`docs/status.md`](../status.md) 为唯一真相源；逐版本变更明细见 [`CHANGELOG.md`](../../CHANGELOG.md)；各战役实证战报见 [`docs/battles/`](../battles/README.md)。

---

## 0.1 v0.2.2 修复记录（已发布，2026-11-05）

本轮针对“动手试试 6”在真实 Fabric 1.21.1 客户端暴露的 Mixin 热更链路缺陷完成修复。以下条目为**已观测**证据：
真实客户端日志 + 自动化回归；未能自动化的端到端部分在“验收证据”中单独标注。

| 修复 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| Mixin 新增 `@Unique` 字段触发的 `ClassInfo` 陈旧 NPE | ✅ | `plugin-mixin` `MixinClassInfoSync`；`MixinHotSwap.handleIfMixin` | `MixinClassInfoSyncTest`（先断言旧元数据找不到新字段，同步后可找到；变异检查：禁用补成员即变红）；真实客户端：`class-info sync addedFields=1` → 重织入 → redefine 成功 |
| 目标类原始字节码被“已织入产物”污染 | ✅ | `plugin-mixin` `MixinHotSwap.readOriginalBytes`（磁盘原始字节优先） | `MixinRawSourcePriorityTest`（污染 `sourceBytecodeCache` 后仍读回磁盘字节；变异检查：恢复旧优先级即变红）；真实客户端 `bridge in sourceBytes=4000`（不再出现 5103） |
| Mixin 模板类被当新类 `defineClass` | ✅ | `core` `RemoldAgent.markNonLoadableClass` / `injectNewClass` / `loadClassSnap`；`RemovedMemberScanner` | `NonLoadableClassTest` 两个出口（快照排除、注入短路；变异检查分别变红）；真实客户端不再出现 `[NEW] com.example.mixin...` / `[INJECTED] ... into KnotClassLoader` |
| 动态单类重定义删除字段被布局门误拒 | ✅ | `core` `RemoldAgent.decideDynamicLayout` + `RemovedMemberScanner` | `DynamicGateWiringTest`（target 必须在 inBatch；变异检查：把 target 移出批次即变红）、`DynamicLayoutWaiverTest`（无读者 WAIVE / 有读者 REJECT / 未扫描 REJECT；变异检查：WAIVE→REJECT 即变红）；真实客户端 `[LAYOUT-WAIVE] ... no out-of-batch reader` → redefine 成功 |
| 失败提示误导用户更换 DCEVM JDK | ✅ | `plugin-mixin` `MixinHotSwap.flushPendingTargets` | `isEnhancedHotswapEnabled() == true` 时不再提示“requires JBR with DCEVM”，改为指向 `[LAYOUT-REJECT]` / `[DYNAMIC-REDEFINE]`；真实客户端本轮为成功路径，失败分支待下一次拒绝场景验证 |

> 端到端边界：Fabric 1.21.1 的真实客户端验收为人工执行，CI 尚无自动 Fabric 客户端 E2E；上述单测覆盖的是判决/扫描/反射契约与接线分支。

---

## 0.2 v0.2.3 修复记录（已发布，2026-11-06）

本版包含三类改动：重试台账与注入冲突处理、真实 Fabric Mixin 探针、Mixin 主路径修复与实体探针
（含存量字段初值可见性与反射缓存清理两项）；`main` 与已测集成分支 `integration/abc` 同一提交。以下条目均以自动化回归 + 真实环境
探针为验收证据，未自动化的用户端到端验收在下一轮单独登记。

| 修复 / 能力 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| Mixin 目标基线二次织入（重复 `@Unique` 字段导致后续热更被 LayoutGate 拒绝） | ✅ | `AnnotationTransformer.transform` 外部重定义通道 | `MixinProbe` exit-code 断言：修复前 `REMOVED_FIELD`/exit 3，修复后 `RESULT PASS`/exit 0（`tests/fabric-mixin-probe/evidence/c2-exitcode.txt`） |
| 实体探针开关污染热更基线 | ✅ | `DynamicProfilerAPI.toggleEntityProbe`（内部重转换上下文） | C1+C2 联合复现：修复前 5683 B 重复字段 + `LAYOUT-REJECT`，修复后 5482 B 不变 + `HotSwap successful`；`EntityProbeToggleProbe` 5/5 |
| `redefineDynamic` 无基线回退 / no-op 语义可见 | ✅ | `RemoldAgent.redefineDynamic` | `RedefineDynamicNoBaselineProbe` 13/0；no-op 不触发批次回调且打印显式日志 |
| 重试台账 / 注入冲突 / 源码基线回滚 / 原始字节指纹 | ✅ | `RemoldAgent` | `RetryBookkeepingProbe` 15/0、`InjectConflictProbe` 8/0、`FingerprintDiskHashProbe` 18/0 |
| L0 存量字段初值变更检测（含实例门、继承措辞、聚合、诊断） | ✅ | `remold.ref.InitializerDrift` | `InitializerDriftTest` + `DoubleBraceProbe` L0 断言（80/0） |
| 偏移漂移：继承字段检测、遮蔽去重、点分类名归一 | ✅ | `LayoutPredictor` / `SuperclassDriftGate` 绑定索引 | `LayoutPredictorTest` + `bdf245b` 用例 |
| 多 Mixin 同目标冲突（Fabric + Forge，两个独立 mixin config） | ✅ | `tests/fabric-mixin-probe/mm/`、`tests/fabric-mixin-probe/mm-forge/` | 目标类 `MultiTarget` + `multi_a`(priority 900)/`multi_b`(1100) 两个 config 各带 `@Unique` + `@Inject`。**Fabric** 5 阶段 `RESULT PASS`：baseline 双 handler 各一次且顺序 A,B；仅热更 MixinA 后 B 仍存活一次、顺序不变、`@Unique` 值保留、无 `LAYOUT-REJECT`；目标类热更后三者同时生效；同批次热更两个 Mixin 均生效；同名 `@Unique` 字段稳定为 `[uniqueA, sharedNameTag, uniqueB, fdbb776f$example_mod$sharedNameTag$0]`、无重复字段（Fabric sponge-mixin 以 `sessionId$modId$name$0` 形态消歧）。**Forge** 同构探针 5 阶段 `RESULT PASS`：FIELDS 在 baseline 与 2/3/4/5 恒为 `[uniqueA, sharedNameTag, uniqueB]`（上游 Mixin 0.8.5 合并同名 `@Unique`，不拆分、不重复）、`uniqueA=111`/`uniqueB=222` 全程保留、双 handler 各一次且顺序 A,B、无 `LAYOUT-REJECT`；仅热更 MixinA 后 A 升 v2 且 B 仍 v1；目标类热更后 T1 + A v2 + B v1；同批热更两 Mixin 后 A v3 + B v2。证据 `evidence/multi-mixin-fabric.txt`、`evidence/multi-mixin-forge.txt`（含变异检查与去触发对照） |
| 真实 Fabric 1.21.1 主路径（含 Mixin 类自身热更） | ✅ | 探针：`tests/fabric-mixin-probe/C3Probe.java` | `RESULT PASS`、零 `INIT-DRIFT`（`evidence/c3-server-e2e.txt`） |
| Forge 1.21.1（ModLauncher）Mixin 热更重织入 | 🔶 | 探针：`tests/fabric-mixin-probe/c5/`、`tests/fabric-mixin-probe/mm-forge/`、`tests/fabric-mixin-probe/forge-classinfo/` | 最小 MDK 探针（服务端 dev run、official mappings、Mixin 配置经 `MixinEnvironment.addConfiguration` 手动注册）下 `RESULT PASS`：`@Unique` 非初值 999 保留、`@Inject` 存活、InitFix 补字段成功（`evidence/c5-forge-e2e.txt`）。`plugin-forge` `ForgeMixinHotSwap` 补上 Mixin 类自身热更：反射调用 `IMixinTransformer.reload` 刷新运行期状态并级联重织入目标类（此前 Mixin 模板会被误当新类 `defineClass`，目标类 handler 不更新）；多 Mixin 同目标探针 5 阶段 `RESULT PASS`（`evidence/multi-mixin-forge.txt`）。**Mixin 新增字段/方法**：`ForgeClassInfoSync` 在 `reload` 前把新成员补进旧 `ClassInfo`、拒绝时回滚，真实 Forge 1.21.1 红→绿（`forge-classinfo/evidence/forge-1.21.1-{baseline,after-fix}.txt`，详见 §0.3）。**NeoForge 21.1.265** 已在真实 dev 服务端（原生 `[[mixins]]` 注册）红→绿，见 §0.4；**未验证**：生产 jar 的 manifest `MixinConfigs` 声明路径（FG7 dev 运行时无 jar manifest，探针改为手动 `MixinEnvironment.addConfiguration`）、真实模组/客户端、生产 refmap、NeoForge 生产 installer 启动路径、NeoForge 26.x（FML 10+）；实体探针的共享状态/影子 Class 问题已修复（Forge 稳态 `counter=1`，见风险 19） |
| Mixin 新增/改名 `@Inject` handler 方法在 reload 校验阶段被拒（`Conform error ... please restart client`） | ✅ | `plugin-mixin` `MixinHotSwap.handleIfMixin` + `MixinClassInfoSync`（reload 前同步成员，拒绝时回滚） | 修复前：真实 KnotServer 上场景 B/C `RESULT FAIL`（`Conform injector evoB$two` / `evoCHookTwo`，原始记录见 `evidence/inject-evolution.txt` §4）；修复后三场景 `RESULT PASS`（§1）；变异/对照：撤销 reload 前置同步 → B/C 变红（§3），`cancellable` 回退 → 场景 A 断言变红（§2）；`MixinClassInfoSyncTest`（修复当时 3/3，SSOT 收敛后随适配器降级为 2/2） |
| `sourceBytecodeCache` 契约矩阵（首载 / 普通重定义 / 织入保留原始字节 / 多 ClassLoader 不互相覆盖） | ✅ | `AnnotationTransformer.transform` 接线 | `CacheSourceMatrixProbe` 23/0（真实 agent + 真实 redefine）、`SourceBytecodeCacheMatrixTest`（JUnit 半，无需 JBR）；变异检查：去掉首载写入 → 4 条变红，`putIfAbsent`→`put` → 2 条变红（`tests/fabric-mixin-probe/evidence/cache-layout-recovery.txt`） |
| `LAYOUT-REJECT` → 次轮恢复端到端（拒绝不污染存活实例/类定义/双基线/pending/台账，下一轮合法结构变更立即生效） | ✅ | `RemoldAgent.processChanges` 布局门接线 + `LayoutDecisionEngine` | `LayoutRejectRecoverProbe` 41/0（JBR 21 / 秒级）；对照：同样类型变更在零存活实例类上走 `LAYOUT-WAIVE` 正常重定义；变异：删掉存活实例拒绝 → 5 条变红（同上证据文件） |
| 拒绝摘要误报 “reader scan could not complete”（类型变更/漂移的拒绝与读者扫描无关） | ✅ | `LayoutDecisionEngine.skipVerdictForLayout` | 修复前 `HotswapSkipSummaryTest#layoutRejectLineForTypeChangeDoesNotClaimTheReaderScanFailed` 红；修复后绿（同上证据文件） |
| 用户人工验收（模板场景 1–8，真实 Fabric 1.21.1 客户端） | ⬜ | — | 清单与结果列已登记：[docs/acceptance/01-manual-checklist.md](../acceptance/01-manual-checklist.md)；各场景「结果（构建）」列留空待用户确认；Forge 列保持“未验证” |

> **上游 Mixin 同名 `@Unique` 行为差异（已实测）**：Forge 1.21.1 自带上游 SpongePowered Mixin
> 0.8.7（`Env=SERVER Service=ModLauncher`）时，两个独立 mixin config 对同一目标声明**同名**
> `@Unique` 字段会被合并为**一个**目标字段——应用后 FIELDS 恒为 `[uniqueA, sharedNameTag, uniqueB]`，
> 两个 handler 读到同一个共享槽位。Fabric 侧的 sponge-mixin 分支则把同名 `@Unique` 消歧为
> `<sessionId>$<modId>$<name>$0`（Fabric 实测 FIELDS 含
> `fdbb776f$example_mod$sharedNameTag$0`）。两个探针的断言因此按加载器分别设计：Forge 断言
> "字段集合跨阶段恒等 + 不拆分 + 不重复 + 无 `LAYOUT-REJECT`"，不断言 Fabric 的 per-mixin 值隔离。

## 0.3 v0.2.5 修复记录（已发布，2026-11-07）

本轮针对 `v0.2.4` 已知限制「Forge 侧未实现 Mixin 新增字段/方法的 `ClassInfo` 成员同步」完成修复。
红基线、修复后绿跑与变异检查使用同一探针序列（真实 Forge 1.21.1 专用服务端，ForgeGradle 7
`runServer --offline`），未验证部分单独标注。

| 修复 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| Forge/NeoForge：给已有 `@Mixin` 模板新增 `@Inject` handler 方法 / 新增 `@Unique` 字段时，`reloadMixin` 校验失败（`Conform error ... please restart client`，随后 `ClassInfo$Method.isUnique()` NPE），结构性改动被迫降级为重启 | ✅ | `plugin-forge` `ForgeClassInfoSync`（跨 ClassLoader 反射成员同步 + 回滚）；`ForgeMixinHotSwap.reload`（reload 前同步，拒绝时回滚） | 单测 `ForgeClassInfoSyncTest` 4/4：reload 调用当时新成员已可解析、拒绝后成员集合回到基线、Agent 侧 `ClassNode` 签名错配时零改动降级、缺 `MixinInfo` 不阻断 reload；变异检查：反向应用实现 diff → 用例变红（已恢复）。真实 Forge 1.21.1 E2E：红基线 `VERDICT RED`（stage1 reload 被拒：`Conform error ... isUnique() ... method is null`；新 handler 从不执行、新 `@Unique` 字段不可见）→ 修复后 `VERDICT GREEN`（新增 handler 每调用 +1、新增 `@Unique` 读到初值 456、既有 handler/`@Unique` 保持）；证据 `tests/fabric-mixin-probe/forge-classinfo/evidence/forge-1.21.1-{baseline,after-fix}.txt`，判定器自证 `verify-evidence.sh`（4 个合成样本区分 RED / GREEN / INVALID） |
| NeoForge 端到端 | ✅ | 与 Forge 共用 `ModLauncherMixinBridge` / `ForgeMixinHotSwap` / `ForgeClassInfoSync` | **已观测**：NeoForge 21.1.265 dev `runServer`（ModDevGradle，原生 `META-INF/neoforge.mods.toml [[mixins]]` 注册）红基线 `VERDICT RED` → 修复后 `VERDICT GREEN`，业务终局为新 handler 每调用 +1、新 `@Unique` 读到初值 456；详见 §0.4 与 `tests/neoforge-mixin-probe/evidence/`。**未验证**：生产 installer 启动路径与 NeoForge 26.x（FML 10+）代际，见 §4.1 候选清单 |


## 0.4 v0.2.5 ClassInfo 同步收敛与三端实证（已发布，2026-11-07 ~ 2026-11-08）

| 修复 / 变更 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| ClassInfo 成员同步的**双实现**（Fabric `MixinClassInfoSync` / Forge `ForgeClassInfoSync` 各自一套算法）收敛为单一引擎；两侧只留薄适配 | ✅ | `core/src/remold/reflect/ClassInfoMemberSync.java`（引擎）；`plugin-mixin/src/remold/mixin/MixinClassInfoSync.java`、`plugin-forge/src/remold/forge/ForgeClassInfoSync.java`（薄适配） | 引擎契约 `ClassInfoMemberSyncTest` 5/5（含**新增**「半程失败回滚」用例——旧 Fabric 实现在该用例下会残留已补入成员）；Fabric 适配器降级 `MixinClassInfoSyncTest` 2/2；Forge 跨 ClassLoader 接线 `ForgeClassInfoSyncTest` 4/4；全量 JUnit 469 项 0 skipped；变异检查：反向应用实现 diff → 用例变红（已恢复）；**真实 Forge 1.21.1 回归**：重构后同一 E2E `VERDICT GREEN`（`tests/fabric-mixin-probe/forge-classinfo/evidence/forge-1.21.1-after-ssot-refactor.txt`） |
| NeoForge 21.1（ModLauncher 11.0.5 + sponge-mixin 0.16.4）Mixin 模板热更：新增 `@Inject` handler / 新增 `@Unique` 字段，**走加载器原生 `[[mixins]]` 注册** | ✅ | `plugin-forge` 现有 `ForgeMixinHotSwap` / `ForgeClassInfoSync`（薄适配）；探针 `tests/neoforge-mixin-probe/` | NeoForge **21.1.265**（MC 1.21.1，ModDevGradle 2.0.148，Gradle 8.12，JBR 21）：红基线（禁用同步的 plugin jar）`VERDICT RED`——stage1 reload 被拒 `Conform error ... ClassInfo$Method.isUnique() ... "method" is null`，stage2 `re-weaving failed`；修复后 `VERDICT GREEN`——日志正向出现 `Mixin class-info sync: ... addedMethods=1`（stage1）与 `addedFields=1`（stage2），业务终局：新 handler 每调用 +1、新 `@Unique` 读到初值 456、既有成员保持；`mixin_registration=neoForge-native META-INF/neoforge.mods.toml [[mixins]]`，全程无 `MixinEnvironment::addConfiguration` 手动注册；判定器 6 个合成 fixture（含 degraded / manual-registration 违规样本）自证判定力，验收 `verify-evidence.cmd` exit 0；变异检查：回退 `Verdict` 判定逻辑 → 验收 exit 1。证据 `tests/neoforge-mixin-probe/evidence/neoforge-21.1-{baseline-red,after-fix}.txt` |
| NeoForge 深反射的 JPMS 边界 | 🔶 | 产品侧诊断：`ClassInfoMemberSync` / `ModLauncherMixinBridge` 在访问被拒时打印精确 `--add-opens` 参数 | **已观测**：NeoForge 21.1 dev 运行时（`ModDevGradle runServer`）全程无 `InaccessibleObjectException`，`add_opens=0` 也能完成深反射（sponge-mixin 在 legacy classpath，不在 `-p` 模块路径）。**未验证**：生产 installer 启动路径（`unix_args.txt`）、NeoForge 26.x（FML 10+，无 ModLauncher）——那两处的模块归属必须重测，`--add-opens` 要求不得从 dev 运行外推 |
| Fabric 1.21.1（KnotServer）SSOT 重构后回归 | ✅ | `plugin-mixin` `MixinClassInfoSync`（薄适配）+ core 引擎 | 既有 `@Inject` 自演化探针**原样重跑**（未改探针）：`RESULT PASS failures=[]`；A2 取消标志演化、B2 handler 换目标方法、C2 **新增 `@Inject` handler**、C3 再移除该 handler 全部 PASS；日志出现 `[Mixin] Mixin class-info sync: ...EvoBMixin addedMethods=1` 与 `...EvoCMixin addedMethods=1`，无 `Conform error`；证据 `tests/fabric-mixin-probe/evidence/inject-evolution-after-ssot.txt`（agent sha256 `f6225c68…`、plugin sha256 `956b5a10…`） |
| 发版工具链：JitPack 坐标族与模板解析自洽 | ✅ | `gradle-plugin/build.gradle`（jitpack 发布补 `version = "v${version}"`）、`panama/build.gradle`（补 jitpack 发布）、两份模板 `settings.gradle.kts` 与 `remold.gradle(.kts)` shim（坐标统一为 `com.github.I-hope1.Remold:*:v<ver>`）、根 `build.gradle` 的 `verifyReleaseCoordinates` | v0.2.5 发版时暴露：`gradle-plugin` 的 jitpack 发布只有裸版本 `0.2.5`，而 shim/模板要 `v0.2.5`；同时发布侧组名大小写（`I-hope1`）与消费侧（`I-Hope1`）不一致，在 Gradle module metadata 校验下直接报 `bad group`。修复后 `publishToMavenLocal verifyReleaseCoordinates` 8/8 模块两套坐标齐全；两份模板 `--offline compileJava` PASS（Fabric 1m05s、Mindustry 3s）；`./gradlew check` 469 项 0 skipped。远端不受影响：JitPack 对 `0.2.5` 与 `v0.2.5` 均返回 200（已实测），已发布的 v0.2.5 资产无需重传 |
| NeoForge 26.x / FML 10 代际（无 ModLauncher） | ⬜ | — | 产物级已确认断裂（`neoforge-26.1.2.126` 库清单 `cpw.mods:*` 为 0、main class `net.neoforged.fml.startup.Client`）；当前跨 ClassLoader 桥按 ModLauncher 语义实现，**未适配、未验证**；候选清单已登记 |


---

## 0.5 v0.2.6：L1 存量初值重放第一版（v0.2.6 已发布 2026-11-09；本轮 2026-11-08）

| 能力 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| L1 存量初值重放（匿名类 `{{ }}` 写继承字段；默认关 + 父类白名单 + 常量旧值 + 基本类型） | ✅ | `remold.ref.InitializerReplay`（候选/旧值/声明类解析）；`InitFix.buildPatch/applyPatch`；`HotswapBridge.KIND_CONDITIONAL_EXPECTED`（旧初值 CAS） | 默认关（白名单空）时 B/B2n 维持旧值不变；开启后 `DoubleBraceProbe` L1 断言：old 90→100、运行期改过（42）的实例跳过、非白名单/非恒定旧值/引用类型各一条负向、零 `FieldLedger`、不改 `patchGenerated`、重放后 L0 不重复告警；`InitializerReplayTest` 11/0；全量 JUnit 480/0 skipped；`DoubleBraceProbe` 104/0。变异检查：白名单判定改为恒真 → `L1n` 变红（`old.health` 100≠90）；expected-CAS 改为 FORCE → `L1` 条件跳过错杀（42→100）变红；均已恢复。**已知边界**：具名类已有字段、引用类型、非编译期常量旧值、`final` 字段不在第一版；按匿名类计的堆枚举性能数据未测。 |

---

## 0.6 v0.2.6：A 档 Kotlin `apply {}` 静态持有者初值重放（v0.2.6 已发布 2026-11-09；本轮 2026-11-08）

| 能力 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| A 档：`object` / 顶层 `val` 持有者 `<clinit>` 里 `T().apply { field = 编译期常量 }` 的存量对象重放（默认关 + 持有者白名单） | ✅ | `remold.ref.ApplyInitializerReplay`（纯分析：白名单、`<clinit>` 直线抽象解释、局部变量副本溯源、唯一 `NEW`/唯一 `PUTSTATIC dest`、目标字段安全门）；`InitFix.transform/buildPatch/applyPatch`（`PatchPlan.applyFields`、`applyReplay$N` 补丁方法、`PatchReport.applyReplayFields`、运行期 dest 前置检查）；`HotswapBridge.KIND_CONDITIONAL_EXPECTED`（旧初值 CAS + 链接期 static/final 复核）；`AnnotationTransformer` 外部通道触发 | 默认关（白名单空）时存量对象保持旧值、零 A 档日志；开启后 `KotlinApplyProbe` 34/0（JBR 21 真实 `processChanges` + redefine）：存量对象 90→100 且对象身份不变（`GETSTATIC dest` 取回的那一个）、运行期改过（42）的字段保持 42、初值相同的字段不重放也不进报告、非白名单去触发对照保持 90、byte/short/char 目标字段（子字 CAS 路径）7→9 / 300→400 / 'A'→'B'、null dest warn 跳过且 detached 旧对象不被写、`FieldLedger` 为空、`patchGenerated=false`、L0 零 `[INIT-DRIFT]`；`ApplyInitializerReplayTest` 13/0（白名单命中/关闭/非白名单/常量相同/四种基本类型/旧值非常量/新值非常量/引用类型/`final`/dest 描述符不匹配/一对象多 dest/Kotlin var setter 逃逸/普通持有者）；全量 JUnit **493/0 skipped**；`DoubleBraceProbe` **104/0**；`:tests:initFixOracle` `ALL ASSERTIONS PASSED`。**Kotlin 2.3.0 真实字节证据**：四形态 javap（`object`/顶层 val 在 `<clinit>`、`class` 属性在 `<init>`、函数在方法体、Kotlin `var` 走 `invokevirtual` setter、`apply` 内联不产生合成类）、与 javac 等价形态的对照、`InitializerDrift.detect` 对两种静态持有者实测返回空；记录于 `docs/initfix/13-kotlin-apply-replay.md` §2。**前置探针**：隐藏补丁类作为持有者 nestmate 可 `GETSTATIC` 私有 `static final dest`；`findField(T,...)+KIND_CONDITIONAL_EXPECTED` 正常写 90→100、错误 expected 不写。变异检查：白名单判定恒真（整段判定去掉）→ `nonWhitelistedHolderIsIgnored`/`emptyWhitelistMeansChannelOff` 2 单测失败 + 探针 AP2 变红（31/3）；expected-CAS 改为 `KIND_FORCE` → AP1「运行期改值 42」被写成 100 变红（32/2）；均已恢复并全绿。**已知边界**：Kotlin `var` setter（`invokevirtual`）、具名类属性（B 档）、方法体/局部（C 档）、引用类型字段、`final` 字段、非编译期常量、多静态字段共享同一 apply 对象不在范围；外部重定义通道（IDE/外部 agent）与 L1 一样没有独立的“redefine 是否被 JVM 接受”信号，若外部 redefine 被拒仍可能执行 A 档写入（主路径只对成功的类调用 `afterRedefine`，无此窗口）。 |

---

## 0.7 v0.2.6：两阶段 Schema-First 读穿保护（v0.2.6 已发布，2026-11-09）

| 能力 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| 两阶段重定义（内部批次路径）：本批存在「新增/类型变更字段」或既有静态字段重写（`@RemoldReinit`/重放写任务，排除 `ConstantValue`）进入补丁计划时，先提交阶段 A（新结构 + 旧方法体，**构造器/`<clinit>` 保留新体**），补丁完成后再提交阶段 C 的新方法体；纯方法体热更仍走单次重定义快路径。默认开启，`-Dremold.agent.two_phase=false` 可回到旧行为。 | ✅ | `RemoldAgent.applyTwoPhase` / `redefineStageA`（阶段编排与整组回滚）；`InitFix.requiresTwoPhase`（新增/类型变更字段 + 非 `ConstantValue` 的既有静态字段重写）；`StageASkeleton.buildWithLiveConstructors`（构造器保留新体，替代 `ConstructorTailInjector` 插桩） | 真实 `processChanges` 端到端 `TwoPhaseProbe` **72/0**（JBR 21）：`two-phase ON` 时并发读线程在 50k 存量实例把阶段 B 窗口拉宽的情况下零 NPE，且依次看到旧结果与新结果；`two-phase OFF` 对照在同一夹具上稳定观测到 `NullPointerException`（红基线），证明读穿窗口真实存在且被两阶段关闭；`TwoPhaseGuardTest` 7/0（新增实例字段/新增静态字段/既有静态字段重写 → true；纯方法体/实例字段 `@RemoldReinit`/`ConstantValue` 常量重写/零值等价 → false）；`tests/static-rewrite-probe/` A/B（单阶段 patch 后热读者保持 `OLD`、两阶段阶段 C 后变 `NEW`）；`BatchTwoPhaseStaticRewriteTest` 1/1（批次驱动两次 `redefineClasses` + 阶段 B 写入静态值）、`DynamicTwoPhaseRedefineTest` 3/3（动态入口同样顺序）；`StageASkeletonTest` 11/0（含窗口实例由新构造器初始化、旧体不回退到新字段的正反例）；全量 JUnit **597/0 skipped**（v0.2.6 发版验证，`./gradlew check`）；`:tests:initFixOracle` `ALL ASSERTIONS PASSED`；`layoutRejectRecoverProbe` 41/0、`doubleBraceProbe` 104/0。**动态单类入口（`redefineDynamic`）已接入**：`DynamicTwoPhaseRedefineTest` 2/2——新增带初值字段时两次 `redefineClasses`（Stage A 旧体 / Stage C 新体），补丁日志夹在两次调用之间；纯方法体变化仍单次；变异：强制 `twoPhase=false` → 用例红。**真机（JBR 21 DCEVM）**：`redefineDynamicNoBaselineProbe` 13/0，日志 `Successfully redefined rd.Host (two-phase: A -> patch -> C)`、`Applying instance field init patch ... enumerated=1`、存量实例 `b=42`，且批次监听器只触发一次；**未单独跑**：Mixin 目标类在真实客户端下的动态两阶段路径（该路径由插件调用，等价机制已由上面的探针覆盖）。**残余边界**：外部 IDE/JDWP 重定义通道仍走单阶段（读穿窗口仍在，见风险 9）；被安全门拒绝、根本没生成补丁的字段两阶段无法代偿（保持“重启”语义）；阶段 B 完成前，新构造器读取本类新增**静态**字段或同批其它类未补齐字段仍是残余窗口；两次 redefine 会带来双倍 JIT 逆优化（`docs/initfix/07-p05-probe-results.md` §11）。 |

---

## 0.8 v0.2.6：Mixin 匿名/内部类热更闭环（v0.2.6 已发布，2026-11-09）

真实 Fabric 1.21.1 客户端用「普通类 vs Mixin 内」的匿名类 + Lambda 对照试验（R1~R5）暴露三段断层。本节按**已观测**口径登记；仍未闭环或未验证的项已并入 §0.10 的 v0.2.6 统一残余风险清单，不在此重复。

| 修复 / 变更 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| 热更管线自身组件在类链接期抛 `NoClassDefFoundError`（客户端原文 `[Mixin] Mixin hot-swap handler unavailable: NoClassDefFoundError: LoadedTargetSuppressor`），使 Mixin handler 的成功与失败分支同时失效 | ✅ | `core/src/remold/reflect/mixin/MixinConfigContext`（抑制器改为 `Function<Collection<String>,Object>` + `Consumer<Object>`；删除 `LoadedTargetSuppressor`）；`plugin-mixin` `MixinConfigDiff`/`NewMixinRegistrar`、`plugin-forge` `ForgeMixinConfigDiff`/`ModLauncherClassTrackerSpoof` 只传方法引用 | **已观测**：`MixinPluginClassLinkageTest` 机械扫描已编译插件类常量池，断言零引用已废除的 core 类型且不 `implements`/`extends` 任何 core mixin 类型（正向对照 + 变异：恢复硬链接 → 精确点名 `plugin-mixin/remold/mixin/MixinConfigDiff.class` 变红）；`NewMixinRegistrarFailClosedTest` 覆盖「抑制器缺失/返回 null/抛异常 → 放弃登记并输出定向诊断、异常不外泄」。**已知残余**：plugin/core 版本错位仍会退化为 `NoSuchMethodError`（`MixinConfigContext` 构造签名不匹配），该路径由 `RemoldAgent.notifyAssetChanges` 的逐插件 `catch` 兜住并记 error；两侧必须同版本发布 |
| Mixin 注入方法内**首次**新增匿名类 → reload 崩溃：`Error loading class: Outer$1 (ClassNotFoundException)` 随后 `Cannot invoke "ClassInfo.isProbablyStatic()" because "innerClass" is null` | ✅ | `plugin-mixin` `MixinBytecodeOverlay`（安装 `IMixinService` 代理，只对显式登记的内部名提供字节，其余回落宿主 provider）、`MixinHotSwap.handleIfMixin`（reload 前登记内部类字节并驱逐上游 `ClassInfo.cache` 负缓存） | **已观测（单测/字节码级）**：`MixinInnerClassHotSwapTest` 7 用例（归属判定、嵌套名推导、`lookup` 点分/内部名归一、负缓存驱逐按 `containsKey` 计数）；3 个人工变异（去掉 schema 门 / 去掉 passthrough 过滤 / 去掉名字归一）均被击杀，恢复后复绿。**上游机制依据**（sponge-mixin 0.15.5+mixin.0.8.7 源码包）：`ClassInfo.forName` 走 `MixinService.getService().getBytecodeProvider()`，失败把 `null` 写进 `ClassInfo.cache` 且永不重试。**真实 Fabric 1.21.1 KnotServer E2E**（`tests/fabric-mixin-probe/anon/`，mod 走 `run/mods` jar 加载、Gradle 输出不在运行期 classpath）：修复前 S1 `poke` 停在 `hook-s0`，日志为 `Error loading class: ...AnonTargetMixin$1 (ClassNotFoundException)` + `innerClass is null`（RED）；修复后 S1 `poke=anon-v1`、`RESULT: GREEN`（证据 `evidence/anon-r3r5-{red,green}.txt`） |
| 只改已加载匿名类体 → 目标类重织入静默 `[DYNAMIC-REDEFINE] no-op`、匿名类仍跑旧版 | ✅ | `plugin-mixin` `MixinInnerClassSupport`（内部类归属/嵌套名纯函数）、`MixinInnerClassHotSwap`（按 `InnerClassGenerator` 稳定唯一名重新生成合成内部类并 `redefineClasses`）、`MixinHotSwap.handleInnerClassChange`（只改 `Outer$N` 时回到外层 Mixin 重跑） | **已观测（单测/字节码级）**：成员 schema 判定区分「只改方法体」与「增删字段/方法/构造器」，后者给出点名成员的指向性诊断并拒绝重定义（不借 DCEVM 绕过布局门）；`handleInnerClassChange` 在只改 `Outer$N` 时把字节登记进覆盖层并回到外层 Mixin。**上游机制依据**：`InnerClassGenerator.getUniqueReference` 把 `Outer$1` 映射为 `Target$Anonymous$<uuid>`，`isLoaded()` = `loadCounter>0`，已加载的合成内部类不会被重新生成。**真实 E2E**：修复前 S2 外层字节 sha256 前后相同、仅 `$1` 变体，`poke` 仍 `anon-v1` 且日志为 `[DYNAMIC-REDEFINE] no-op`（RED）；修复后 S2 `poke=anon-v2`（GREEN），S3 去触发对照无重定义、值稳定 |

> **本轮观测存档（仍开放项已并入 §0.10 统一清单，不在此重复）**：①「批次落在启动期挂死」同族——探针在 MC 启动期（`Done (` 之前）落批次时曾出现 JVM 挂死（probe 线程无 tick）；本轮把「合成内部类重定义」从桥接线程移到批次之后的 worker 线程，消除了同一族挂死（桥接线程内 `redefineClasses` 会让 KnotServer 线程停摆，实测：S1b 之后 tick 全停）。启动期窗口本身未单独隔离，探针改为等 `Done (` 后再触发；窗口保护见 §0.9/§0.10 的启动门闩与批次看门狗。② 同一批次里模板本身与它的内部类都会被问一次，模板第一次 reload 后按字节相等跳过第二次（`alreadyReloaded`），因此内部类热更只做一次；若两者字节都变，仍会按「重新登记内部类 + 一次 reload」处理（良性幂等）。
>
> 仍开放的残余（plugin/core 版本错位、覆盖层登记项生存期、匿名类成员结构变化需重启、真实客户端复跑）见 §0.10 统一清单第 6～9 条。

## 0.9 v0.2.6：ANON_ALIGN 同形歧义 / 合成类元数据 / 启动期门闩（v0.2.6 已发布，2026-11-09）

真实 Fabric 1.21.1 KnotServer 探针（`tests/fabric-mixin-probe/anon/`）扩展到 S0~S7 后暴露的 3 个深水区问题。按**已观测**口径登记；本轮五条残余风险中 1/2/3/5 已在 §0.10 收口，第 4 条并入 §0.10 统一清单（编号台账见 §0.10 开头）。

| 修复 / 变更 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| ANON_ALIGN 同形匿名类 N-to-1 歧义：同一方法内两个同接口同结构的匿名类让 Tier 3/4 双向唯一失效，编辑被迫孤儿化（客户端 R2b：`topology-equal candidates 2 -> 2, topology-decided 0`），字段持有的存活实例继续跑旧码 | ✅ | `core/src/remold/AnonClassAligner.java`（Tier 3.5 体相似度、Tier 4.5 liveness 兜底、点名诊断、`findHoldingFields`） | **单测**：`AnonClassAmbiguityTest` 17/17（重编号哈希不变、体相似度口径、2×2 歧义兜底、存活阻断、字段持有者点名；配对数按 `scanVisibleLive=` 口径诊断；§0.11 新增 4 例文案/持有者审计用例）；**变异**：三道人工变异均被击杀并复绿（liveness 兜底、身份语义、字面量入 token 三处变异）；**真实 KnotServer**：S6 `s6a=s6-v1 → s6b=s6-v2` GREEN（重编号的同形匿名类被配对并原地重定义，字段持有的存活实例同步更新），S4 `held-v1 → held-v2` GREEN。**按裁决刻意保留的分支**：两侧都只改字面量（体相似度打平）+ 旧类有存活实例 → 4.5 拒不动手，孤儿化并输出点名诊断（旧类名 + `scanVisibleLive=1` + 可能持有字段 + `@RemoldReinit` 逃生舱），由 `liveInstancesBlockTheFallback` 守住。strict 模式语义未动 |
| Mixin 生成合成类 `Target$Anonymous$<uuid>` 的元数据不可达：`HierarchyTreeOracle.readClassBytes` 只查 `bytecodeCache` 与 classpath 资源，两者皆空 → `Nest scan incomplete`（Nest 判定降级）且 `InitFix` 构造器 protected 可见性判定返回 null → `Field init patch refused ... cannot determine protected status of constructor` | ✅ | `core/src/remold/BytecodeSources.java`（新，登记口）；`HierarchyTreeOracle.readClassBytes`（接线）；`InitFix`（三处拒绝 + Nest 告警的归因文案）；`plugin-mixin` `MixinInnerClassHotSwap`（生成成功后回灌） | **已观测（单测/字节码级）**：`NestBytecodeSourcesTest` 10/0（登记前 `nest member metadata unavailable` → 登记后成功；unregister 回落；两种构造器可见性判定；plugin 回灌路径）；**变异**：去掉 `BytecodeSources.lookup` 接线 killed（4 条行为性断言红）、去掉 plugin 回灌 killed（回灌用例红）；真实探针里 `Nest scan incomplete` 与 `Field init patch refused` 两条告警**已带上归因文案**（点名"织入期生成的合成内部类，需要 `BytecodeSources` 回灌"）。**后续收口**：S7 的 RED 已由 §0.10 的 `refeedForTarget`（提前生成 + 回灌）关闭，S7 `null → s7-v1`；`BytecodeSources` 常驻内存无上限仍在 §0.10 统一清单 |
| MC 启动期落批次导致 JVM 挂死（`Done (` 之前类加载 + Mixin 转换高频并发） | 🔶 | `core/src/remold/StartupGate.java`、`core/src/remold/BatchWatchdog.java`、`core/src/remold/RemoldAgent.triggerDueHotswap` 漏斗接线、`api/src/remold/HotswapPlugin.isHostReady()` | **已观测（单测）**：`remold.startup.*` 19 例（该轮读数；收口后为 30 例、见 §0.10；门闩状态机、未就绪不落批次、就绪后触发一次、看门狗超阈值才 dump 且同批次只一次、`isHostReady` 默认 true 时行为不变）；**变异**：反打 `RemoldAgent` 修复 diff killed（5 条 wiring 断言红），另两次外科变异（门闩恒真 → 4 红、看门狗去 once → 1 红）均已恢复。**后续进展**：`plugin-fabric` 已实现 `HostReadinessProbe`（见 §0.10，真机未验证）；`plugin-forge` 侧仍未实现，实际游戏里门闩仍可能"首次触发即放开"；**精确挂死线程栈仍未抓到**——看门狗即为下一次复发的取证器 |
| 探针仪器扩展（S4 长生命周期字段持有 / S5 `@Unique private` 访问 / S6 1→2 重编号 / S7 字段初始化器构造器可见性） | ✅ | `tests/fabric-mixin-probe/anon/`（`run-anon.sh` + 6 个新 stage + `ANON_EXPECT` 模式 + 独立证据文件） | 真实 KnotServer 读数（基线代码）：S4 GREEN、S5 GREEN、S6 RED、S7 RED；合入 E 后 **S6 转 GREEN**；S3 去触发对照全程稳定。**实证回答**：`Nest scan incomplete` 期间 `@Unique private` 字段/方法访问**仍然正确**（S5 终局值一致）——说明该元数据缺失只影响 Remold 自身的安全门视图，不影响 JVM 侧 nest 访问控制。**结局**：S7 的红基线由 §0.10 的 `refeedForTarget` 关闭，S0~S7 现全绿（`run-anon.sh` exit 0 / `EXT_GREEN=1`） |

## 0.10 v0.2.6：收口轮 · S0~S7 全绿 / 持有者点名 / 门闩接线（v0.2.6 已发布，2026-11-09）

§0.9 的五条残余风险（1 S7 时序缺口、2 看门狗覆盖范围、3 门闩未接宿主、4 `BytecodeSources` 常驻内存无上限、5 字段持有者点名边界）中，1/2/3/5 在本轮收口；4 并入下方统一清单。§0.8 的覆盖层登记项生存期、plugin/core 版本错位、匿名类结构变化限制与真实客户端复跑同样并入该清单。

| 修复 / 变更 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| **S7 时序缺口**（§0.9 残余风险 1）：`InitFix` 在目标类本次 redefine 内部就要读 `<Target>$Anonymous$<hash>.<init>` 的 protected 状态，而 Mixin 只在织入期登记、字节要等首次加载才生成 → 字段初始化 patch 被 fail-closed 拒绝 | ✅ | `plugin-mixin` `MixinInnerClassHotSwap.refeedForTarget`（目标织入后按 `InnerClassGenerator` 的稳定唯一名**提前生成**该目标的全部合成内部类并回灌 `BytecodeSources`）+ `MixinReWeaver.reweave` 接线 | **真实 KnotServer 全绿**：`run-anon.sh` **exit 0 / `EXT_GREEN=1`**，S7 `s7=null → s7=s7-v1`（S7c 对照稳定），S0~S6 全部保持；**变异**：回退 `MixinReWeaver` 接线 → 基线 exit 0 → 变异 exit 3，恢复 OK。**未放宽任何安全门**：字节仍不可得时照旧 fail-closed；生成后把 `InnerClassInfo.loadCounter` 还原，避免"假已加载"让平台跳过真正的 defineClass |
| **字段持有者点名补全**（§0.9 残余风险 5）：精确描述符只能命中合成字段（`this$0`/`val$o`），宿主用户字段必然声明为父类型/接口（`Supplier HELD6`）→ 告警误报"无宿主字段持有" | ✅ | `core/src/remold/AnonClassAligner.java` `findHoldingFields` 三层退化：精确描述符(proven) → 赋值流 `NEW <anon>→<init>→PUTFIELD/PUTSTATIC`(proven，`putfield this$0/val$*` 不误报) → 直接父类型/接口描述符(candidate)；新增 4 参重载返回 `HoldingFields{proven, candidates}`，旧 `List<String>` 契约保持不变 | **单测**：`AnonClassAmbiguityTest` 13 例（接口类型持有必被点名、赋值流 proven、合成字段仍命中、无关系字段不得乱点名）；**变异 5/5 killed**（去候选兜底、去赋值流、去精确层、置空解析器、放宽为"任意引用字段"各自打中对应用例）；告警文案区分 `proven by assignment flow` 与 `candidate (supertype/interface)` |
| **看门狗入口覆盖**（§0.9 残余风险 2）：此前只包住防抖 Class 批次 | ✅ | `core/src/remold/BatchWatchdog.java`（新增 `enter/exit/current` 作用域 API：ThreadLocal + 深度计数，嵌套复用同一实例；`armedWatchdogs()` 守"一个批次最多一个 armed"）+ `RemoldAgent.processChanges` 的 `try/finally`；`retransformLoaded` 自挂作用域；`runClassBatch` 旧包裹移除 | `remold.startup.*` 30 例 0 失败；**变异 4/4 killed**（去 processChanges 挂点 → 4 红；`runClassBatch` 再独立 arm → 2 红；UNKNOWN→false → 3 红；`ticks>0` 恒 READY → 1 红） |
| **`plugin-fabric` 就绪门闩**（§0.9 残余风险 3） | 🔶 | `plugin-fabric` `HostReadinessProbe` + `FabricHotswapPlugin.isHostReady()`：服务端 `MinecraftServer.getServer()` + tick 计数、客户端 `getInstance` + `world/level` 非空 → READY；探测不到一律 UNKNOWN → **返回 true 并按已就绪处理**（只打一条 info），就绪单向缓存 | 单测用替身覆盖三种判定（`FabricHostReadinessTest`）；**未验证**：真实 KnotServer/客户端的访问器名（`getTicks`/`getTickCount`、`world`/`level`）无法在 `.sources/`（仅镜像 Mindustry/Arc）核验，真机未跑 |

**v0.2.6 统一残余风险清单（已观测 / 未验证分列）**

1. **`redefineDynamic`（单类 API 入口）仍未挂看门狗**：三条主路径（防抖/手动/`retransformLoaded`）已覆盖，该单类入口卡死仍无栈。（§4.1 v0.2.9 槽位 ④）
2. **就绪探测未经真机验证**：`plugin-fabric` 的 `HostReadinessProbe` 仅有单测替身（`FabricHostReadinessTest`），真实 KnotServer/客户端的访问器名（`getTicks`/`getTickCount`、`world`/`level`）无法在 `.sources/`（仅镜像 Mindustry/Arc）核验，真机未跑；`plugin-forge` 侧尚未实现 `isHostReady()`。（§4.1 v0.2.9 槽位 ④）fail-open 把杀伤面限制在"保护窗口可能比预期窄"（服务端实例构造前即 UNKNOWN→放行），不会饿死批次。
3. **`BytecodeSources` 常驻内存无上限**（沿用 §0.9 残余 4）：键集合不随 reload 增长（唯一名跨 reload 稳定），但无驱逐策略；回灌发生在 schema 复核之前，即"读得到"不等于"已生效"。（§4.1 P3 已登记）
4. **持有者点名边界**（收口轮补全后的残余）：只扫 old/new 宿主节点的字段表与方法体；跨方法经参数传递不猜（宁可漏点名）；宿主继承字段若 javac 以父类为 owner 发射 `putfield` 不会命中；候选只看直接父类型，不递归层级。父类型/接口持有已由三层退化点名（`Supplier HELD6` 形态），但"任意引用字段"不点名为 proven。
5. **热更管线自身的证据仪器存在"假 PASS / 假击杀"风险**（本轮 worker 实测）：退出码 127（命令不存在）被记为 killed、cmd banner-only 输出被记为 PASS、Gradle UP-TO-DATE 的重复 stdout 哈希被当作新证据。当前由人工复核兜住，工具侧修法见战役台账；在这些修复前，本表所有"PASS/killed"均以**原始日志已人工复核**为前提。
6. **覆盖层登记项的生存期**：成功 reload 后内部类字节保持在覆盖层里（目标织入期间还需要它），后续同类变化会覆盖同名项；Mixin 模板内部类不会被 JVM 当普通类加载，因此长期驻留不会遮蔽真实类。
7. **plugin/core 版本错位**：抑制器已无 core 类型硬链接，但 `MixinConfigContext` 构造签名不匹配时仍会抛 `NoSuchMethodError`（由 `RemoldAgent.notifyAssetChanges` 的逐插件 catch 兜住并记 error），两侧必须同版本发布。
8. **匿名类成员结构变化（新增字段/方法）仍只能拒绝并提示重启**：未做结构化热更；`ForgeMixinHotSwap` 未接本轮内部类闭环。（§4.1 v0.3）
9. **未验证**：真实客户端（IDE + KnotClient）复跑本轮匿名类闭环；启动门闩的真实 tick 语义（同第 2 条）。

---

## 0.11 下一版本（未发布）：ANON_ALIGN Tier 4.5 文案纠偏 · “扫描可见”≠“实例已死”

真实客户端复跑 v0.2.6 时发现：`[ANON_ALIGN] deterministic-dead-pair (0 live instances)` 之后紧跟着
`fieldAnon v2/v3` 的输出 —— 字段持有的实例明明存活并在执行，文案却把“实例数为 0”写成配对安全性的
依据。本轮只修可观测文案与证据说明，**不改判定与接线行为**（Tier 4.5 仍是“全组扫描可见零存活 +
布局 100% 兼容”）。把“存在持有者字段”并入 liveness 判据不是本轮的修法：那会把 0.2.6 已消解的
字段持有场景重新推回孤儿化；正确做法是让日志同时摆出“扫描可见 0”与“字段可能持有”两个事实。

| 修复 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| Tier 4.5 把“扫描可见的活实例为 0”表述成“实例已死 / 0 live instances”，并把“实例数为 0”当作配对安全依据 → 用户误以为安全来自实例已死（客户端实测 `fieldAnon v2/v3` 紧随其后，字段持有实例仍在执行） | ✅ | `core/src/remold/AnonClassAligner.java`：日志改为 `deterministic-zero-scan-pair (scan-visible live instances: 0)`，并显式声明“扫描只能回答它看见了什么，不是实例已死/语义安全的证明，只能当审计线索”；`AlignmentStats.deterministicDeadPairs` → `deterministicZeroScanPairs`（`toString` 同步 `zeroScanPair=`；旧名保留为 `@Deprecated` 同步别名）；`matchDeadPairsByLiveness` → `matchZeroScanPairs`；日志按 `findHoldingFields` 点名 proven/candidate 持有者字段，proven 持有者把该行升级为 `warn`；同源 `[ANON-LAYOUT]` 放行文案与歧义诊断改用 `scanVisibleLive=` / `scanVisibleLiveOld=`；`RemoldAgent.hasLiveInstances` 的 Javadoc 补“false = 本轮扫描没看见”的语义边界，`[LAYOUT-WAIVE]` 两处文案同步改口径 | **单测**：`AnonClassAmbiguityTest` 17/0（新增 4 例：旧文案不得复现 + “扫描可见的活实例为 0”必现、接口类型持有者字段必点名、无持有字段不得凭空点名、proven 持有者升级 warn）；**变异**：把日志文案改回 `deterministic-dead-pair (0 live instances)` → 新增 4 例全部变红，恢复后复绿（原 `zeroScanVisibleInstancesAllowDeterministicPair` / `liveInstancesBlockTheFallback` 保持绿）；**未在真实客户端复跑**（本轮只改文案，判定行为未变） |
| 设计规范未定义 Tier 3.5 / Tier 4.5 及其日志证据契约 | ✅ | `docs/topology/04-tiers-and-rejection.md`（§1 梯队表新增两行、新增 §2.4 日志与证据契约）；`CHANGELOG.md` `[Unreleased]` | 规范正文不含实现状态标记（`verifyDocStatusMarkers` 守卫） |

---

## 0.12 下一版本（未发布）：热更边界治理与漏洞修复

本轮针对复杂边界实测中发现的潜在漏洞开展闭环治理，完成全链路防御与对照组变异击杀：

| 修复 / 能力 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| **枚举常量声明顺序安全门与 Switch 级联阻断**：枚举常量重排/前插导致 ordinal 漂移，现有存量实例保留原 ordinal 致使 switch-on-enum 和 EnumMap 静默走错分支；末尾追加/删除常量虽不改已有 ordinal，但热更不重跑 `<clinit>`——新增常量运行期恒 `null`、删除的常量仍留在旧 `$VALUES` 实例数组里，属于"热更成功但没生效"的假成功 | ✅ | `ClassDiffUtil.diff` 记录 `shiftedEnumConstants` / `addedEnumConstants` / `removedEnumConstants`（`CHANGED_ENUM_ORDER` / `ENUM_CONSTANT_SET_CHANGED`）；`LayoutDecisionEngine.decide` 拦截拒绝并提示 revert/restart；`RemoldAgent` 级联阻断同批 SwitchMap 依赖与直接引用变更常量的类 | **单测**：`EnumSwitchReorderGateTest`（9/9：前插拒绝、重排拒绝、追加拒绝、删除拒绝、枚举新增普通字段放行、方法体放行、SwitchMap 级联、追加拒绝原因点名实测阻断项（`$VALUES`/`$SwitchMap$`/`EnumMap`/JIT-deopt/restart））；`EnumAlignmentGateWiringTest`（8/8：重排/前插在 Mixin 成员对齐之后仍可见、追加拒绝、删除常量不被墓碑复活、`$VALUES`/`this$N`/`$SwitchMap$` 判据、Mixin 织入枚举仍对齐方法表）；**变异**：分别恢复宽判据 / 删除枚举字段顺序保护 → 对应用例精准变红；**真机否决候选修复方案**：JBR 21 探针 `tests/enum-append-probe/`（42 个独立 JVM 场景，javac21 与真 javac8 两种字节码形态；含 `swmap-rebuild`/`heap-repair` 补测）证明"事后重写 `$VALUES` + 清空 `Class` 两个枚举缓存"必要但不充分——已 JIT 编译的读者在 deopt 前仍读旧 `$VALUES`（`values()` 被内联、字段加载被提升；**非** `ConstantValue` 常量折叠，对读者类 `redefineClasses(同字节)`/`retransformClasses` 可刷新，但需覆盖全部读者）、已初始化的 `$SwitchMap$` 持有类无法自动补新 case（扩容只消除 AIOOBE、新 case 静默落 `default`；补测：按新 `<clinit>` 映射重建表可行，需重定义消费者 + 解析字节码）、存量 `EnumMap`/`EnumSet` 无法原地扩容（AIOOBE / 迭代陈旧；补测：逐实例换内部数组可行，但需遍历堆全覆盖 + 窗口/并发防护）；读数与结论见 [`topology/10`](../topology/10-enum-constant-append-infeasibility.md)，决策见 [`D-ENUM-1`](../topology/08-decisions.md#d-enum-1) |
| **枚举顺序门被 Mixin 成员对齐器抢跑（前线 P0-2）**：普通枚举的合成字段 `$VALUES` 被误判为 Mixin 生成成员，对齐器在 `ClassDiffUtil` 之前把字段表恢复成基线顺序，`CHANGED_ENUM_ORDER` 永远看不到重排/前插，真机日志只见 `[MIXIN-ALIGN] restored the baseline member order` | ✅ | `MixinMemberAlign.hasMixinGeneratedMembers` 排除 javac/JVM 合成字段（`$VALUES`/`this$N`/`$assertionsDisabled`/`$SwitchMap$*`/`$EnumMap$*`）；`align` 对枚举类不重排字段表、不把被删常量复活成墓碑字段（方法表照常对齐） | `EnumAlignmentGateWiringTest`（红测试先复现：3/8 失败）；变异：恢复 `$`+synthetic 宽判据 → 2 个判据用例变红；删除枚举字段顺序保护 → Mixin 枚举用例变红；契约登记于 `docs/mixin/03-reweave-and-member-alignment.md` §2 |
| **读者扫描被无关常量池引用污染（前线 P0-3c）**：删除工程构造器/方法时，同 `name:desc` 的 `java/util/ArrayList.<init>(I)V` 等 JDK 引用因类型世界缺失被判 `UNKNOWN`，整轮扫描被视为不完整 → 全量 fail-closed 误杀（同批重编的调用方也被拒） | ✅ | `RemovedMemberReaders.provablyUnrelated` 在解析前只跳过**可证明无关**的引用（构造器 owner 不等；platform 前缀 owner 对非 platform 成员，且仅限字段/static/private 的向上解析）；实例虚方法仍保留 resolve→inconclusive（前缀不能证明分派落点）；`LayoutDecisionEngine.describeFindings` 在拒绝日志点名前 3 个 reader 类 | **单测**：`UnrelatedRefReaderScanTest`（9/9，含同批构造器删除、混合"无关引用+真实陈旧读者"、虚方法 fail-closed 边界、拒绝日志点名）；`MemberStaticnessAndConstructorScanTest` 8/8；红测试先复现 6/8 失败；变异：禁用预过滤 → 5 红、放宽构造器证明 → 2 红、platform 证明缺 member 侧条件 → 1 红 |
| **`@RemoldReinit` 用户契约收口（死开关 / 继承作用域 / 触发原因 / 不支持清单）**：`-Dremold.agent.force_reinit` 只解析只打日志、无读取点（死配置）；默认 `INCLUDE_SUBCLASSES` + 父类字段 + 子类 `<init>` 写同字段会把父类初值覆盖到子类实例；触发规则只有隐式分支、日志不说明原因；Javadoc 未点名常量内联/Record/final/多字段非原子等不支持形态 | ✅ | `RemoldAgent.FORCE_REINIT` 接成真实逃生口（默认 `false`，忽略指纹强制重放）；`InitFix.reinitTriggerReason` + `[REINIT] trigger` 行点名 first/changed/open/forced；`InitFix.unsafeInheritedReinitFields` + 纯函数 `ReinitScopeGate` + `HierarchyTree.knownSubtypes`（已登记子类即"可能有实例"的完备集合）+ 布局门 `[REINIT-SCOPE]` 拒绝（`LayoutDecisionEngine.rejectUnsafeReinitScope`，批量与动态两条路径接线）；`LayoutDecisionEngine` 的不可提取拒绝改为列出全部字段与原因；`RemoldReinit` Javadoc 补继承门/force/CONDITIONAL 精确语义与"Does not do"表 | **单测**：`ReinitScopeGateTest`（5/5：子类初始化器写继承字段拒绝并点名、SELF 逃生、不写不误伤、读不到子类字节 fail-closed、静态字段忽略）、`remold.ref.ReinitContractTest`（4/4：new-field/forced/open/first/changed/unchanged）；变异：检测返回空 → 主用例红，scope 解析改为 INCLUDE → SELF 用例红，force 被忽略 → force 用例红；既有 `RecordAndReinitExtractabilityGateTest` / `InitFixOracle` 保持绿 |
| **运行期日志英文守卫**：主源码原有 17 个含中文的日志/用户可见字符串字面量（`[LAYOUT-REINIT]`、`[ANON_ALIGN]` 审计行、`[INIT-DRIFT]`、`[LAYOUT-REJECT]` 提示、`[Fabric]` 就绪回退等，分布 6 个文件），GBK 控制台/日志采集端会显示乱码 | ✅ | 全部译为英文（语义不变）；新增 `:tests:verifyLogLanguage` （挂 `check`）逐字符扫描主源码字符串字面量，命中 CJK/全角标点即失败；规则写入 `AGENTS.md` §3「日志语言（强制）」 | **守卫变异**：向 `Reflect.java` 注入 `"中文日志（fullwidth）；。"` → 任务变红并点名 `file:line`，恢复后绿；`AnonClassAmbiguityTest` / `InitializerDriftTest` / `FabricHostReadinessTest` 的旧中文文案断言同步改为英文 |
| **gradle-plugin 的 agent 版本对账**：本地 `remold-agent.jar` 与 `gradle.properties` 的 `remold.version` 不一致时，旧行为是静默使用本地 jar（运行期 `NoSuchMethodError` 版本错配） | ✅ | `core/build.gradle` 的 shadowJar manifest 写入 `Remold-Version`/`Implementation-Version`；`RemoldConfigResolver` 读取 manifest 并对账：自动发现的本地 jar 版本不匹配 → 按配置版本重新解析/下载并同步覆盖 `.gradle/remold/remold-agent.jar`；匹配则离线直用；未配置版本时采用 jar 版本；显式 `agentJar` 只告警；解析结果仍不匹配则 fail-closed；新增 `cleanRemoldCache` 任务：默认只清理项目级 `.gradle/remold` 与 `build/remold`，共享下载缓存 `caches/remold`（agent + 插件 jar）仅在 `--global` / `-Premold.cleanGlobalCache=true` 时删除（不挂 `clean`，避免每次 clean 触发重新下载） | **单测**：`gradle-plugin` 的 `RemoldAgentVersionTest`（manifest 读取/`v` 前缀比较/refresh 判定）、`CleanRemoldCacheTaskTest`（注册/不挂 clean/默认不删全局且无关文件保留/--global 才删全局/属性开关/幂等）；变异：`shouldRefreshAgent` 恒 false → 对应用例红；真机复用路径未跑（需网络/发布产物） |
| **Record 组件变更与 `@RemoldReinit` 初值可行性安全门**：Record 类新增不可变组件导致存量实例恒为 0/null 破坏不变式与等价性；Record 改类型或无初值构造器字段标注 `@RemoldReinit` 导致 DCEVM 重定义后槽位被静默置 null/0 发生数据丢失 | ✅ | `ClassDiffUtil.diff` 识别 Record 新增组件（`RECORD_COMPONENT_ADDED`）；`InitFix.unextractableReinitFields` 纯函数前置核验切片提取可行性；`LayoutDecisionEngine.decide` 对有存活实例的 Record 新增组件以及不可提取的 `@RemoldReinit` 字段坚决拒绝（`LAYOUT-REJECT`），合法初值普通类保持 `WAIVE_REINIT` 放行 | **单测**：`RecordAndReinitExtractabilityGateTest`（5/5，存活拒绝、无实例放行、Record 改类型标注 reinit 拒绝、变体构造器自赋值标注 reinit 拒绝、对照组普通类合法初值放行）；**变异**：注释掉 `unextractableReinitFields` 校验后测试精准变红（2 failed） |
| **方法与字段静态性翻转（static ↔ instance）读者拦截**：方法或字段 staticness 翻转时因同名同 desc 被误判为普通代码变动，批外调用方执行旧调用指令（`invokevirtual` 对应 static 方法，或 `invokestatic` 对应实例方法）运行期抛 `IncompatibleClassChangeError`（ICCE） | ✅ | `ClassDiffUtil.diff` 识别静态性翻转，旧方法进 `removedMethods`，新方法进 `addedMethods`；`RemovedMemberScanner` survivor 集合带静态性标记；`RemovedMemberReaders.find` 建立两级漏斗精扫（`hasMatchingStaleInstruction`），常量池初筛后对方法体指令精扫，捕获旧静态性指令并记入 `incompatibleAccess`，`LayoutDecisionEngine` 判 `LAYOUT-REJECT`；同批重编读者与类自身调用精准放行 | **单测**：`MemberStaticnessAndConstructorScanTest`（7/7 覆盖静态性翻转拦截、同批重编放行、字段双向静态性翻转拦截、删构造器拦截与无调用放行）；**变异**：在 `ClassDiffUtil` 中注释掉方法静态性翻转判断后测试精准变红（2 failed） |
| **删除构造器批外 `new` 读者拦截**：删掉无参构造器后重定义成功，批外调用方每次 `new Hot()` 运行期抛 `NoSuchMethodError: <init>()V not found` | ✅ | `RemovedMemberScanner.removedMembers` 不再跳过 `<init>`；`RemovedMemberReaders.resolveMethod` 针对 `<init>` 严格遵循 JVMS 5.4.3.3 不沿继承链向上查父类；批外存在 `Methodref Hot.<init>()` 读者时记入 `hits`，`LayoutDecisionEngine` 判 `LAYOUT-REJECT`；无批外读者放行 | **单测**：`MemberStaticnessAndConstructorScanTest.removedNoArgConstructorWithStaleCallerIsRejected` / `removedNoArgConstructorWithoutCallerPasses`；**变异**：跳过 `<init>` 则测试精准变红 |
| **缺 `-g` 时捕获 Lambda 换序不可判定告警与 Strict 模式拦截**：类未带 `-g`（缺少 `LocalVariableTable`）编译时，`captureIdentity` 无法获取形参变量名退回 null，导致 `isPermutedPair` 失效、旧实例静默沿用旧行为；javac 亦会将同类中捕获顺序相反、方法体相同的 lambda 彻底合并为同一个合成方法造成编译期信息丢失 | ✅ | `LambdaAligner.hasCapturedLambdaWithoutLvt` 结合 `hasCapturedVariables` 与 `getReceiverOffset` 精准判定捕获 lambda 是否缺失 LVT；`align` 入口输出明确 WARN 提示带 `-g` 重编，并在 `LAST_RESULT` 中登记 `missingLvtLambdas`；开启 strict 模式（`LAMBDA_STRICT`，支持 `-Dremold.agent.lambda_strict=true` / `-Dremold.lambdaAlign.strict=true`）时抛出 `LambdaAlignmentStrictException`；`RemoldAgent` 捕获后记录 `[HOTSWAP-REJECT]` / `skips` 并回滚事务摘除该类，安全保留旧版本运行；启动日志明确提示重编需 `-g`（替代笼统警告）；无捕获 lambda 与仅捕获 `this` 实例 lambda 精准放行 | **单测**：`LambdaLvtStrictGateTest`（6/6，覆盖基线确认、非 strict 模式记录与告警、strict 模式异常拦截、对照组带 `-g` 放行、对照组无捕获 lambda 放行、对照组仅捕获 `this` 放行）；**变异**：注释掉 `LAMBDA_STRICT` 抛出分支后测试精准变红（1 failed） |
| **`EnvironmentProbe` 命令行参数后置覆盖误判修复**：JBR 参数同时包含 `+` 和 `-` 时，原实现仅用 `contains("+Allow...")` 导致被后置 `-Allow...` 覆盖时发生误判 | ✅ | `EnvironmentProbe.isFlagEnabled` 纯函数，以命令行参数列表中最后一次出现的 flag 为准，并由 `RemoldAgent.isFlagEnabled` 暴露门面 | **单测**：`EnhancedHotswapGateTest.flagEnabledFollowsLastOccurrence`（覆盖无参数、只有+、只有-、先+后-、先-后+）；**变异**：将 `lastPlus > lastMinus` 改为 `lastPlus >= 0` 后测试精准变红（1 failed） |
| **孤儿 lambda 方法体保留（非 void 默认开启）**：删除 lambda 后幽灵桩返回类型默认值（`null/0`），调用方链式调用或拆箱直接 NPE / 除零 / 数组越界；一概跑旧逻辑又会让 `void` 孤儿继续执行用户删掉的副作用 | ✅ | `LambdaAligner.injectRetainedBody` + 三参 `onOrphanInvoked(String,boolean,boolean)`（保留体标记、log-once 告警）；纯判据 `core/src/remold/OrphanBodyRetention.java` 扫旧体指向**本类**的字段/方法/indy 句柄，成员已删或静态性翻转则拒绝并回退默认值桩；旧体用 `EXPAND_FRAMES` 重读、连 `StackMapTable`/异常表一起搬运（写入仍 `COMPUTE_MAXS`）；上一轮已是保留体时原样搬运（多轮不退化）；`readGhostOriginalHash` 收紧为"首指令 LDC(long)+紧跟 POP2"；开关 `-Dremold.agent.orphan_retain_body`（默认 true）/`-Dremold.agent.orphan_retain_void`（默认 false） | **单测**：`OrphanBodyRetentionTest` 6/6（非 void 保留体反射执行返回旧值；引用已删私有方法 → 回退默认值桩；void 默认 no-op、开关打开后副作用真实执行；多轮搬运仍保留；保留体仍被识别为幽灵且不进配对映射；开关关闭对照组）；**变异**：`keepPrevious=false` → 3 红、存活判据直接放行 → 1 红；既有 `LambdaAlignTortureTest`/`MethodRefForwardTest` 按新契约更新（`allIds` 排除幽灵、L5 幽灵保留旧体、方法引用门测试显式关闭保留以隔离被测门禁） |
| **`redefineDynamic` 接入两阶段（动态单类入口的读穿窗口关闭）**：Mixin 热更给目标类新增 `@Unique` 字段并在 `@Inject` 里立刻读取时走 `redefineDynamic`，此前单次提交会让并发 tick 线程在补丁落盘前读到 null/0 | ✅ | `RemoldAgent.redefineDynamic` 消费 `InitFix.requiresTwoPhase`：阶段 A 由 `StageASkeleton.buildWithLiveConstructors(oldBytes, runtimeBytes)` 合成 → 首次 `redefineClasses` → `InitFix.afterRedefine` 补存量实例 → 第二次 `redefineClasses(runtimeBytes)`；缓存随 A→C 推进；阶段 C 失败按 VM 级/非 VM 级分流（VM 级毒化+重启；非 VM 级回滚基线并返回 false）；阶段 A 合成失败降级单阶段并 WARN | **单测**：`DynamicTwoPhaseRedefineTest` 2/2（新增带初值字段 → 两次 redefine，Stage A 旧体/Stage C 新体、补丁日志夹在两次之间；纯方法体 → 单次）；**变异**：强制 `twoPhase=false` → 两阶段用例红；**真机（JBR 21 DCEVM）**：`redefineDynamicNoBaselineProbe` 13/0（日志 `two-phase: A -> patch -> C`、存量实例 `b=42`，批次监听器一次） |

---

## §0.12 v0.2.7 封版终验读数（前线真机，同一会话未重启）

制品：agent `7778f4ae0ad4b06de886d22e36315985` / plugin-mixin `51b965ea3dfd50bf8392e4043c304a2a`（HEAD `db65bf0`）。

| 路径 | 轮次 | 读数 |
|:--|:--|:--|
| 拒绝 | A1~A5 | `fieldTableChanged` `1→2→3→4→5`（每轮 +1）、`gateRejected` `1→2→3→4→5`（每轮 +1）、`executed=0`、宿主均 `was NOT redefined`（带 `metrics:`）、`newlyNull=0` |
| 拒绝去重 | Adup | 同字节重发增量 `0/0`（不重复记账、不重复执行） |
| 自愈放行 | B1~B5 | 每轮 `redefined in place; metrics:`，`executed` `1→2→3→4→5`（每轮 +1），`fieldTableChanged`/`gateRejected` 增量 0；`v1-restored`…`v5-restored` 全部运行态生效；25 切面 `nullCount=0 / newlyNull=0`，双通道 `agree=6100 / conflict=0 / unavailable=0`，0 崩溃 |

熔断未触发 → **发布冻结解除，封版 0.2.7**。

---

## §0.13 JDK 内部包自举（免 `--add-exports` / `--add-opens`）

| 项 | 内容 |
|:--|:--|
| 机制 | `core/src/remold/ModuleOpener.openForAgent(inst)`：premain/agentmain 第一行 + `RemoldAgent.triggerPluginInit` 各调一次。① `Instrumentation.redefineModule` 把 `java.base` 的 9 个包 export+open 给当前可达的 unnamed module；② `Reflect.IMPL_LOOKUP` 调包私有 `Module.implAddExports/implAddOpens`，目标 `Module.EVERYONE_MODULE`，覆盖之后才创建的加载器 |
| 必须两条路的理由 | `redefineModule` 的 `extraOpens` 只接受"调用时已存在"的模块集合；Knot / ModLauncher / 插件加载器都在 premain 之后创建 |
| 对照实验（JBR-21，均不带 `--add-exports`） | 无 agent：`InaccessibleObjectException`；仅 `redefineModule`：系统加载器 ok、premain 后新建加载器 FAIL；+ `EVERYONE_MODULE`：两者均 ok |
| 未覆盖 | `--enable-native-access=ALL-UNNAMED`（Java 24+ 受限本地方法授权）仍需保留，`redefineModule` 无法替代；Gradle 插件只注入这一条 |
| 降级 | 任一步失败只 warn，用户仍可手写 `--add-exports`/`--add-opens` |

---

## §0.14 夹具与探针的 `-g`（`LocalVariableTable`）档位

| 编译对象 | 档位 | 备注 |
|:--|:--|:--|
| `:tests` SemAssert 夹具（`hstestFx_*`，`tests/build.gradle`） | `source,lines,vars`（全量 `-g`） | 与用户工程 Gradle 默认一致；收窄会丢掉 LVT，使 lambda 捕获身份不可判定 |
| 其它模块 / 两个模板 | Gradle 默认（全量 `-g`） | 未显式设置 |
| 内存/守护 javac 夹具（`InitFixOracle`、`LambdaBridgeAlignTest`、`LambdaForceBaselineTest` 等） | 显式 `-g` | 见各用例 |
| `PremainBaselineProbe` | `-g:none`（刻意） | 只关心字节内容，不可用于验证捕获顺序 |

判定力前提与语义见 [`mixin/08-decisions-and-empirical-ledger.md`](../mixin/08-decisions-and-empirical-ledger.md) §3.5。

---

## §0.15 lambda 兜底配对改 fail-closed

| 项 | 状态 | 验证与说明 |
|:--|:--|:--|
| 体内新增嵌套 lambda 导致的静默错绑（旧实例跑成内层逻辑、无 WARN）已转为 `BODY_MISMATCH` WARN + 拒绝配对 + 旧名幽灵化 + 新名避障 | 已拦截 | 覆盖用例：`PassBTest`（语料 N=37 与嵌套 lambda 阳性对照）、`LineNumberDifferentialTest`（幽灵归因契约） |
| 方法体指纹归一化：常量按值、引用按 `owner+name+desc`、局部槽按首现序、标签按首引用序、可折叠算式 | 已实现 | 语料 N=37 中 31 个类触发兜底门禁，评估 107 次、107 次进入 `bodyHash` 比对且均相等放行、拒绝 0 次，同源变换误报 0/107；另有人造重排 4 类各 n=1 |
| 信息论边界：删一个 lambda 并在同一次保存里新增一个 javac 恰好复用同编号、且宿主同名的 lambda，与原地更新在字节码层不可区分 | 未解决 | 代码分析结论：缓解只有名字/编号漂移这类间接信号 |
| Pass B 覆盖面收窄：纯搬迁先被 Step 1 吃掉，Pass B 只覆盖"体等价但票不同" | 已登记 | 由配对统计观察得出 |
| `stats.bodyMismatchRefusals` 是**逐趟计数**，不是拒绝次数 | 已登记 | 由计数实现观察得出 |
| 槽位平移幽灵：`-g:none` + 槽位整体平移，语料 N=37 中 9 类出现幽灵、共 23 桩（= 8/30、22 桩 + `plugin-forge` 的 `ForgeMixinHotSwap` 1 类 1 桩） | 已结案：`gate=on/off` 两趟 37 行逐字节相同，机制为语料上 107 次门禁评估均放行（0 拒绝），关掉门禁同样放行，故输出相同 | 成因"票据指纹计入 `var` 下标"系代码分析结论，未做隔离实验 |
| 允许幽灵参与匹配的变异体在门禁开启后不可被击杀 → 转门禁屏蔽集 + "幽灵跳过限制放开次数为 0" 的哨兵；哨兵与具名用例是**报警不是防线**，Pass B 两处仅靠门禁 | 已登记 | 代码分析结论 |
| 语料范围 | N=37：`core/src`+`api/src`（30/258，合并编译）、`plugin-fabric/src`（3/7）、`plugin-mixin/src`（2/23）、`plugin-forge/src`（2/12） | 语料统计 |
| 语料缺口 | `plugin-arc/src`（Mindustry 编译期制品非纯 class 包，缺注解处理器，javac 解析即失败）、`tests/`、`gradle-plugin/` | 语料统计 |

- 合流点 `680029f`；权威全量读数（HEAD `b0679c8`，`--rerun-tasks`，35 tasks executed，166.2 s）：**732 tests / 0 failures / 0 errors / 0 skipped**。
- 结案记录：门禁评估次数已量化（`eval=107 / fpCmp=107 / fpAllow=107 / refused=0 / classes=31`）；嵌套 lambda 对照种子的阳性对照 `eval=18 fpCmp=18 hostKindRefused=18 classes=1`（指纹不等后由宿主形态翻转拒绝）。
- 后续候选（槽位平移幽灵的隔离实验，验证 `var` 下标成因）**只登记不排期**，会动生产代码，须单开批次。
- **跨轮次告警去重问题**：去重键 `类名#BODY#新名<-旧名` 不含重定义轮次/类版本（由代码分析可知，代码阅读）；生产侧 `core/` 下 `clearLoggedWarnings()` 调用点为 0、`TEST_EVENTS` 生产消费者为 0（实测，grep 读数）；配对拒绝本身不受去重影响（由代码分析可知）。未修，单开批次。

## §0.16 参数注解 / 泛型签名漂移的告警点名

| 项 | 状态 | 验证与说明 |
|:--|:--|:--|
| 参数注解值/类型/删除变化、字段与方法签名泛型变化 → 成员级 `[SIG-DRIFT]` 告警点名 | 已实现 | 覆盖用例：`remold.evict.ParameterAnnotationDriftTest`（漂移点名与去触发对照） |
| **类级泛型签名（`getGenericSuperclass`）不在检测范围**；标准 HotSpot 下"重新调用 `getDeclaredMethods()` 读取"仍读到旧值 → 合起来是"静默按旧值走" | 未检测，登记 类级泛型签名未检测 | 读数表显示类级泛型签名在两列仍读旧值 |
| 接线：`processChanges` 里的 `reportMemberMetadataDrift` 调用由 `:tests:metadataDriftWarnProbe` 把关（已接进 `:tests:check`） | 已接线 | 覆盖任务：`:tests:metadataDriftWarnProbe`（挂在 `:tests:check`，真实 `processChanges` 路径发出 `[SIG-DRIFT]` 告警） |
| 接线在 `:tests:test` 口径下**无分辨力**（该用例直接调用 `reportMemberMetadataDrift`，不覆盖调用点） | 已登记 | 该接线在 `:tests:test` 口径下不可分辨，由上述探针任务把关 |
| WARN 文案：只含类/成员名 + 变化种类 + "旧句柄读数不可靠、请勿继续使用" + "自持句柄不会被修复" | 已收敛 | 文案形态与断言位置见 `ParameterAnnotationDriftTest`（禁用标签全集断言）；源码 Javadoc 中两处否定句（`ReflectionCacheManager:134`、`RemoldAgent:2626`）为允许清单 |
| 驱逐范围扩展（`EvictionScope.WITH_DESCENDANTS`）未交付，停 `parked/k-eviction-scope`；缺"已注册缓存驱逐前后对照读数" | 停放 | 主干仅剩 `RemoldAgent:2611` 一行注释，已改为技术说明 |
| 未测 | JBR 8/11/17；Gson/Jackson 真实缓存路径 | — |

## §0.17 注解默认值（`AnnotationDefault`）漂移：fail-closed 拒绝

| 项 | 状态 | 验证与说明 |
|:--|:--|:--|
| 注解默认值变化 / 移除 / 新增 → 默认拒绝（批量整轮共退、动态路径拒绝） | 已实现 | 判定入口 `ClassDiffUtil.describeAnnotationDefaultChange` + `RemoldAgent.annotationDefaultGate`；覆盖用例 `ClassDiffUtilTest`（10 条）、`AnnotationDefaultGateTest`（6 条） |
| 降级开关 `-Dremold.agent.annotation_default_change=allow` | 已实现 | 降级时输出指名告警（类名 + 成员名），不静默 |
| 三种运行时的影响范围（按运行时区分） | 实测 | 标准 HotSpot（JBR 25 / JBR 21）：默认值改动后应用级读到旧值，含重定义后新加载的类；JBR 21 DCEVM：仅重定义前已解析过注解的旧类读旧值 |
| 默认值移除在 DCEVM 下旧句柄 `getDefaultValue()` 读到 `SourceFile` 常量 | 未定位 | 三次独立进程均复现（同机同参数）；重定义后重新取句柄读到 `null`（正确）；成因未定位，已登记 |
| 默认值新增（无 → 有） | 默认拒绝 | 标准 HotSpot 下重定义后加载的裸消费者抛 `IncompleteAnnotationException`；DCEVM 下未见有害；按标准 HotSpot 行为定性，是否放宽另行评估 |
| 未覆盖 | — | 真机 Fabric/Knot 未复跑，读数为 JVM 级；批量"整轮共退"由单测接线覆盖（动态单类路径已端到端）；结论为已定位 + fail-closed 防护 |
