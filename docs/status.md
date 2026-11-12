# HotSwap 模块实现状态总表 (Implementation Status & Audit)

> **状态信息管理规则（强制）**：  
> 本文件为 HotSwap 全模块实现状态、技术债务与验收审计的 **唯一真相源 (Single Source of Truth)**（设计规则与代码地图见 [`AGENTS.md`](../AGENTS.md)，本文件为唯一状态载体）。  
> **严禁在 Javadoc、代码注释以及 `docs/` 设计规范文档中重新混入易腐的实现状态信息**（如 `✅`、`[已实现]`、`[未实现]` 等，由 CI 任务 `verifyDocStatusMarkers` 自动化守卫）。  
> 各设计规范文档仅负责定义客观的架构规格、设计原理与交互契约；实现状态若有变更，统一在此登记与审计。

| 审计元数据项 | 内容与规格说明 |
|:---|:---|
| **基准提交 (Baseline Commit)** | `main` @ `886b39a`（`v0.2.7` 已发布，tag `0852506`，其代码基线 `db65bf0`；上一发布 `v0.2.6` tag `2159d21`）。v0.2.7 内容：Mixin 合成匿名类成员 schema 闸门 + 事务共退 + 自愈账本、`ReloadMixinInfo` 三大结构契约、新 Mixin 运行期准入、访问权限单调对齐、JDK 内部包自举（免 `--add-exports`/`--add-opens`）、`docs/mixin/01..08` 专题文档（逐项见 §0.11～§0.14） |
| **发布说明** | [`releases/v0.2.7`](https://github.com/I-hope1/Remold/releases/tag/v0.2.7)（正文随 release 发布；历史档见 `releases/`） |
| **最后更新时间** | 2026-11-11（v0.2.7 封版：合成类 schema 闸门/事务共退/自愈账本；模块打开改为 agent 自举；夹具恢复全量 `-g`；`docs/mixin/01..08` 落地并完成工程日志剥离） |
| **审计负责人 / 角色** | HotSwap 架构守卫小组 & 自动化回归验证套件 |
| **更新触发条件** | 任何合入包含特性落地、架构偏离、安全门调整、测试基线变更或发现已知缺陷/残余风险的 PR/commit 时，必须同步原子化更新本文件。提交时由作者/维护者更新基准哈希。 |
| **管辖范围与边界声明** | 本状态表权威管辖：<br>1. **匿名类拓扑对齐与安全门 (Anonymous Class Alignment & Safety Gates)**（[`docs/topology/`](topology/)）<br>2. **存量初始化修复器 (InitFix / Legacy Instance & Static Reinitialization)**（[`docs/initfix/`](initfix/)）<br>3. **物理内存偏移稳定性与第三方缓存驱逐 (Physical Memory Offset Stability & Reflection Cache Eviction)**（[`docs/offset/`](offset/)）<br>*边界与后续治理声明*：同属字节码重映射体系的 `LambdaAligner`（Lambda 表达式对齐器）以及 Mindustry UI 胶水层（`CellPropertyRef` / 动态参数表达式指纹），其实现状态当前由各自单元测试覆盖（`CellHelperTest` 35 个用例持续全绿、`LambdaAlignerTest` 等）；它们的详细状态审计矩阵尚未并入本总表，计划在下一轮治理中统一建立验收标准并纳入。 |

---


## §0.11 Mixin 热更子系统专题文档索引

本战役把 Mixin 热更与对齐子系统的机制、不变量与实证台账体系化为 `docs/mixin/01..08`（对标 `docs/topology/`）：

| # | 文档 | 内容 |
|:--|:--|:--|
| 01 | [`mixin/01-invariants-and-classloader-authority.md`](mixin/01-invariants-and-classloader-authority.md) | 类加载器权威仲裁 / 基线防覆盖 / 访问权限单调不降级 / `ClassInfo.cache` 锚回 |
| 02 | [`mixin/02-admission-and-bytecode-overlay.md`](mixin/02-admission-and-bytecode-overlay.md) | 新 Mixin 准入、四级字节来源、覆盖层与 `config.service` 替换、待决接力 |
| 03 | [`mixin/03-reweave-and-member-alignment.md`](mixin/03-reweave-and-member-alignment.md) | 级联重织入管线、`refeedForTarget`、成员名/顺序回映、方法桩与字段墓碑保留 |
| 04 | [`mixin/04-structural-reload-and-retarget.md`](mixin/04-structural-reload-and-retarget.md) | `priority` / `targets` / `interfaces` 三大结构契约的突破与回滚 |
| 05 | [`mixin/05-synthetic-inner-gate-and-abort.md`](mixin/05-synthetic-inner-gate-and-abort.md) | 合成类单出口闸门、事务共退、自愈账本 |
| 06 | [`mixin/06-walkthrough.md`](mixin/06-walkthrough.md) | 四个真实闭环场景的字节流与读数 |
| 07 | [`mixin/07-runtime-and-diagnostics.md`](mixin/07-runtime-and-diagnostics.md) | 启动门闩、批次看门狗、`[VM-INFO]`、双通道堆哨兵 |
| 08 | [`mixin/08-decisions-and-empirical-ledger.md`](mixin/08-decisions-and-empirical-ledger.md) | Lambda vs 匿名类、放弃原地扩容的实证链、未闭合项与 P2 |

> 原先零散的 `mixin/aligner-convergence-verification.md` 与 `mixin/synthetic-redefine-observability.md`
> 已并入上述编号文档（内容按主题拆入 03 / 05 / 08），不再单独维护。

## §0.13 JDK 内部包自举（免 `--add-exports` / `--add-opens`）

| 项 | 内容 |
|:--|:--|
| 机制 | `core/src/remold/ModuleOpener.openForAgent(inst)`：premain/agentmain 第一行 + `RemoldAgent.triggerPluginInit` 各调一次。① `Instrumentation.redefineModule` 把 `java.base` 的 9 个包 export+open 给当前可达的 unnamed module；② `Reflect.IMPL_LOOKUP` 调包私有 `Module.implAddExports/implAddOpens`，目标 `Module.EVERYONE_MODULE`，覆盖之后才创建的加载器 |
| 必须两条路的理由 | `redefineModule` 的 `extraOpens` 只接受"调用时已存在"的模块集合；Knot / ModLauncher / 插件加载器都在 premain 之后创建 |
| 对照实验（JBR-21，均不带 `--add-exports`） | 无 agent：`InaccessibleObjectException`；仅 `redefineModule`：系统加载器 ok、premain 后新建加载器 FAIL；+ `EVERYONE_MODULE`：两者均 ok |
| 未覆盖 | `--enable-native-access=ALL-UNNAMED`（Java 24+ 受限本地方法授权）仍需保留，`redefineModule` 无法替代；Gradle 插件只注入这一条 |
| 降级 | 任一步失败只 warn，用户仍可手写 `--add-exports`/`--add-opens` |

## §0.14 夹具与探针的 `-g`（`LocalVariableTable`）档位

| 编译对象 | 档位 | 备注 |
|:--|:--|:--|
| `:tests` SemAssert 夹具（`hstestFx_*`，`tests/build.gradle`） | `source,lines,vars`（全量 `-g`） | 与用户工程 Gradle 默认一致；收窄会丢掉 LVT，使 lambda 捕获身份不可判定 |
| 其它模块 / 两个模板 | Gradle 默认（全量 `-g`） | 未显式设置 |
| 内存/守护 javac 夹具（`InitFixOracle`、`LambdaBridgeAlignTest`、`LambdaForceBaselineTest` 等） | 显式 `-g` | 见各用例 |
| `PremainBaselineProbe` | `-g:none`（刻意） | 只关心字节内容，不可用于验证捕获顺序 |

判定力前提与语义见 [`mixin/08-decisions-and-empirical-ledger.md`](mixin/08-decisions-and-empirical-ledger.md) §3.5。

## §0.12 v0.2.7 封版终验读数（前线真机，同一会话未重启）

实测 制品：agent `7778f4ae0ad4b06de886d22e36315985` / plugin-mixin `51b965ea3dfd50bf8392e4043c304a2a`（HEAD `db65bf0`）。

| 路径 | 轮次 | 读数 |
|:--|:--|:--|
| 拒绝 | A1~A5 | `fieldTableChanged` `1→2→3→4→5`（每轮 +1）、`gateRejected` `1→2→3→4→5`（每轮 +1）、`executed=0`、宿主均 `was NOT redefined`（带 `metrics:`）、`newlyNull=0` |
| 拒绝去重 | Adup | 同字节重发增量 `0/0`（不重复记账、不重复执行） |
| 自愈放行 | B1~B5 | 每轮 `redefined in place; metrics:`，`executed` `1→2→3→4→5`（每轮 +1），`fieldTableChanged`/`gateRejected` 增量 0；`v1-restored`…`v5-restored` 全部运行态生效；25 切面 `nullCount=0 / newlyNull=0`，双通道 `agree=6100 / conflict=0 / unavailable=0`，0 崩溃 |

熔断未触发 → **发布冻结解除，封版 0.2.7**。

## 0. 状态治理与规范文档清理进度 (Status Governance & Documentation Cleansing)

为根治文档易腐性与状态漂移，本项目推行“规范规格与实现状态物理分离”治理方针。当前治理基线如下：

1. **唯一真相源隔离**：`docs/status.md` 作为全模块实现状态的唯一受权载体，彻底解除规范文档与代码注释维护实现进度的负担。
2. **双重验收验证机制**：
   - **自动化机器扫描 (`verifyDocStatusMarkers`)**：纳入主构建验证流程（`./gradlew check` 强制依赖），递归扫描 `docs/initfix/`、`docs/topology/`、`docs/offset/`、`docs/ecosystem/`、`core/src/`、`api/src/`、`plugin-arc/src/`、`plugin-mixin/src/`、`plugin-forge/src/`、`panama/src/`，严格拦截标记 `/[✅❌⬜🔶🟣]|\[(已实现|未实现|部分实现|已偏离)/`。当前状态：**100% 达标全绿**（v0.2.6 发版验证：`verifyDocStatusMarkers` 全绿）。
   - **规范性内容抽检审计**：禁止规范正文出现“目前已支持”、“当前版本尚未实现”等时态陈述，时态陈述一律归入 `status.md`。首轮治理覆盖 15 篇客观设计文档（`docs/topology/` 8 篇、`docs/initfix/` 6 篇、`docs/offset/` 1 篇），人工抽检合格率 100%；此后新增篇目（当前 `docs/topology/` 9 篇、`docs/initfix/` 13 篇、`docs/offset/` 2 篇）沿用同一客观化规则，并由 `verifyDocStatusMarkers` 持续守卫。
3. **设计规范文档客观化清理清单（首轮治理）**：
   - [`docs/topology/01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) ～ [`08-decisions.md`](topology/08-decisions.md)（共 8 篇）：全部重构为纯架构规格、约束契约与客观原理，易腐进度全部剥离，头部统一指引至 `status.md`。
   - [`docs/initfix/01-safety-gate.md`](initfix/01-safety-gate.md) ～ [`06-decisions.md`](initfix/06-decisions.md)（共 6 篇）：全部完成规格客观化改造，彻底消除进度标记。
   - [`docs/offset/01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md)（首轮 1 篇）：定义物理偏移稳定性与第三方缓存驱逐契约，客观化规格无状态标记；后续新增 [`02-inherited-field-drift-fix.md`](offset/02-inherited-field-drift-fix.md) 记录继承字段漂移修复设计，同一客观化规则适用。
   - [`AGENTS.md`](../AGENTS.md)：仅保留规则、五条不变量、代码地图与测试要求；实现状态、测试计数与验收证据全部外链至本文件。

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

本轮由批次 A（P3 / L0）、B（真实 Fabric Mixin 探针）、C（Mixin 主路径修复 / 实体探针 / C4 / C5）
合并而来；`main` 与已测集成分支 `integration/abc` 同一提交。以下条目均以自动化回归 + 真实环境
探针为验收证据，未自动化的用户端到端验收在下一轮单独登记。

| 修复 / 能力 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| Mixin 目标基线二次织入（重复 `@Unique` 字段导致后续热更被 LayoutGate 拒绝） | ✅ | `AnnotationTransformer.transform` 外部重定义通道 | `MixinProbe` exit-code 断言：修复前 `REMOVED_FIELD`/exit 3，修复后 `RESULT PASS`/exit 0（`tests/fabric-mixin-probe/evidence/c2-exitcode.txt`） |
| 实体探针开关污染热更基线 | ✅ | `DynamicProfilerAPI.toggleEntityProbe`（内部重转换上下文） | C1+C2 联合复现：修复前 5683 B 重复字段 + `LAYOUT-REJECT`，修复后 5482 B 不变 + `HotSwap successful`；`EntityProbeToggleProbe` 5/5 |
| `redefineDynamic` 无基线回退 / no-op 语义可见 | ✅ | `RemoldAgent.redefineDynamic` | `RedefineDynamicNoBaselineProbe` 13/0；no-op 不触发批次回调且打印显式日志 |
| 重试台账 / 注入冲突 / 源码基线回滚 / 原始字节指纹 | ✅ | `RemoldAgent`（A 批次 P3） | `RetryBookkeepingProbe` 15/0、`InjectConflictProbe` 8/0、`FingerprintDiskHashProbe` 18/0 |
| L0 存量字段初值变更检测（含实例门、继承措辞、聚合、诊断） | ✅ | `remold.ref.InitializerDrift` | `InitializerDriftTest` + `DoubleBraceProbe` L0 断言（80/0） |
| 偏移漂移：继承字段检测、遮蔽去重、点分类名归一 | ✅ | `LayoutPredictor` / `SuperclassDriftGate` 绑定索引 | `LayoutPredictorTest` + `bdf245b` 用例 |
| 多 Mixin 同目标冲突（Fabric + Forge，两个独立 mixin config） | ✅ | `tests/fabric-mixin-probe/mm/`、`tests/fabric-mixin-probe/mm-forge/` | 目标类 `MultiTarget` + `multi_a`(priority 900)/`multi_b`(1100) 两个 config 各带 `@Unique` + `@Inject`。**Fabric** 5 阶段 `RESULT PASS`：baseline 双 handler 各一次且顺序 A,B；仅热更 MixinA 后 B 仍存活一次、顺序不变、`@Unique` 值保留、无 `LAYOUT-REJECT`；目标类热更后三者同时生效；同批次热更两个 Mixin 均生效；同名 `@Unique` 字段稳定为 `[uniqueA, sharedNameTag, uniqueB, fdbb776f$example_mod$sharedNameTag$0]`、无重复字段（Fabric sponge-mixin 以 `sessionId$modId$name$0` 形态消歧）。**Forge** 同构探针 5 阶段 `RESULT PASS`：FIELDS 在 baseline 与 2/3/4/5 恒为 `[uniqueA, sharedNameTag, uniqueB]`（上游 Mixin 0.8.5 合并同名 `@Unique`，不拆分、不重复）、`uniqueA=111`/`uniqueB=222` 全程保留、双 handler 各一次且顺序 A,B、无 `LAYOUT-REJECT`；仅热更 MixinA 后 A 升 v2 且 B 仍 v1；目标类热更后 T1 + A v2 + B v1；同批热更两 Mixin 后 A v3 + B v2。证据 `evidence/multi-mixin-fabric.txt`、`evidence/multi-mixin-forge.txt`（含变异检查与去触发对照） |
| 真实 Fabric 1.21.1 主路径（含 Mixin 类自身热更） | ✅ | 探针：`tests/fabric-mixin-probe/C3Probe.java` | `RESULT PASS`、零 `INIT-DRIFT`（`evidence/c3-server-e2e.txt`） |
| Forge 1.21.1（ModLauncher）Mixin 热更重织入 | 🔶 | 探针：`tests/fabric-mixin-probe/c5/`、`tests/fabric-mixin-probe/mm-forge/`、`tests/fabric-mixin-probe/forge-classinfo/` | 最小 MDK 探针（服务端 dev run、official mappings、Mixin 配置经 `MixinEnvironment.addConfiguration` 手动注册）下 `RESULT PASS`：`@Unique` 非初值 999 保留、`@Inject` 存活、InitFix 补字段成功（`evidence/c5-forge-e2e.txt`）。`plugin-forge` `ForgeMixinHotSwap` 补上 Mixin 类自身热更：反射调用 `IMixinTransformer.reload` 刷新运行期状态并级联重织入目标类（此前 Mixin 模板会被误当新类 `defineClass`，目标类 handler 不更新）；多 Mixin 同目标探针 5 阶段 `RESULT PASS`（`evidence/multi-mixin-forge.txt`）。**Mixin 新增字段/方法**：`ForgeClassInfoSync` 在 `reload` 前把新成员补进旧 `ClassInfo`、拒绝时回滚，真实 Forge 1.21.1 红→绿（`forge-classinfo/evidence/forge-1.21.1-{baseline,after-fix}.txt`，详见 §0.3）。**NeoForge 21.1.265** 已在真实 dev 服务端（原生 `[[mixins]]` 注册）红→绿，见 §0.4；**未验证**：生产 jar 的 manifest `MixinConfigs` 声明路径（FG7 dev 运行时无 jar manifest，探针改为手动 `MixinEnvironment.addConfiguration`）、真实模组/客户端、生产 refmap、NeoForge 生产 installer 启动路径、NeoForge 26.x（FML 10+）；实体探针的共享状态/影子 Class 问题已修复（Forge 稳态 `counter=1`，见风险 19） |
| Mixin 新增/改名 `@Inject` handler 方法在 reload 校验阶段被拒（`Conform error ... please restart client`） | ✅ | `plugin-mixin` `MixinHotSwap.handleIfMixin` + `MixinClassInfoSync`（reload 前同步成员，拒绝时回滚） | 修复前：真实 KnotServer 上场景 B/C `RESULT FAIL`（`Conform injector evoB$two` / `evoCHookTwo`，原始记录见 `evidence/inject-evolution.txt` §4）；修复后三场景 `RESULT PASS`（§1）；变异/对照：撤销 reload 前置同步 → B/C 变红（§3），`cancellable` 回退 → 场景 A 断言变红（§2）；`MixinClassInfoSyncTest`（修复当时 3/3，SSOT 收敛后随适配器降级为 2/2） |
| `sourceBytecodeCache` 契约矩阵（首载 / 普通重定义 / 织入保留原始字节 / 多 ClassLoader 不互相覆盖） | ✅ | `AnnotationTransformer.transform` 接线 | `CacheSourceMatrixProbe` 23/0（真实 agent + 真实 redefine）、`SourceBytecodeCacheMatrixTest`（JUnit 半，无需 JBR）；变异检查：去掉首载写入 → 4 条变红，`putIfAbsent`→`put` → 2 条变红（`tests/fabric-mixin-probe/evidence/cache-layout-recovery.txt`） |
| `LAYOUT-REJECT` → 次轮恢复端到端（拒绝不污染存活实例/类定义/双基线/pending/台账，下一轮合法结构变更立即生效） | ✅ | `RemoldAgent.processChanges` 布局门接线 + `LayoutDecisionEngine` | `LayoutRejectRecoverProbe` 41/0（JBR 21 / 秒级）；对照：同样类型变更在零存活实例类上走 `LAYOUT-WAIVE` 正常重定义；变异：删掉存活实例拒绝 → 5 条变红（同上证据文件） |
| 拒绝摘要误报 “reader scan could not complete”（类型变更/漂移的拒绝与读者扫描无关） | ✅ | `LayoutDecisionEngine.skipVerdictForLayout` | 修复前 `HotswapSkipSummaryTest#layoutRejectLineForTypeChangeDoesNotClaimTheReaderScanFailed` 红；修复后绿（同上证据文件） |
| 用户人工验收（模板场景 1–8，真实 Fabric 1.21.1 客户端） | ⬜ | — | 清单与结果列已登记：[docs/acceptance/01-manual-checklist.md](acceptance/01-manual-checklist.md)；各场景「结果（构建）」列留空待用户确认；Forge 列保持“未验证” |

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
| Forge/NeoForge：给已有 `@Mixin` 模板新增 `@Inject` handler 方法 / 新增 `@Unique` 字段时，`reloadMixin` 校验失败（`Conform error ... please restart client`，随后 `ClassInfo$Method.isUnique()` NPE），结构性改动被迫降级为重启 | ✅ | `plugin-forge` `ForgeClassInfoSync`（跨 ClassLoader 反射成员同步 + 回滚）；`ForgeMixinHotSwap.reload`（reload 前同步，拒绝时回滚） | 单测 `ForgeClassInfoSyncTest` 4/4：reload 调用当时新成员已可解析、拒绝后成员集合回到基线、Agent 侧 `ClassNode` 签名错配时零改动降级、缺 `MixinInfo` 不阻断 reload；变异检查：反向应用实现 diff → 用例变红（`.battle` 证据 变异检查记录，已恢复）。真实 Forge 1.21.1 E2E：红基线 `VERDICT RED`（stage1 reload 被拒：`Conform error ... isUnique() ... method is null`；新 handler 从不执行、新 `@Unique` 字段不可见）→ 修复后 `VERDICT GREEN`（新增 handler 每调用 +1、新增 `@Unique` 读到初值 456、既有 handler/`@Unique` 保持）；证据 `tests/fabric-mixin-probe/forge-classinfo/evidence/forge-1.21.1-{baseline,after-fix}.txt`，判定器自证 `verify-evidence.sh`（4 个合成样本区分 RED / GREEN / INVALID） |
| NeoForge 端到端 | ✅ | 与 Forge 共用 `ModLauncherMixinBridge` / `ForgeMixinHotSwap` / `ForgeClassInfoSync` | **已观测**：NeoForge 21.1.265 dev `runServer`（ModDevGradle，原生 `META-INF/neoforge.mods.toml [[mixins]]` 注册）红基线 `VERDICT RED` → 修复后 `VERDICT GREEN`，业务终局为新 handler 每调用 +1、新 `@Unique` 读到初值 456；详见 §0.4 与 `tests/neoforge-mixin-probe/evidence/`。**未验证**：生产 installer 启动路径与 NeoForge 26.x（FML 10+）代际，见 §4.1 候选清单 |


## 0.4 v0.2.5 ClassInfo 同步收敛与三端实证（已发布，2026-11-07 ~ 2026-11-08）

| 修复 / 变更 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| ClassInfo 成员同步的**双实现**（Fabric `MixinClassInfoSync` / Forge `ForgeClassInfoSync` 各自一套算法）收敛为单一引擎；两侧只留薄适配 | ✅ | `core/src/remold/reflect/ClassInfoMemberSync.java`（引擎）；`plugin-mixin/src/remold/mixin/MixinClassInfoSync.java`、`plugin-forge/src/remold/forge/ForgeClassInfoSync.java`（薄适配） | 引擎契约 `ClassInfoMemberSyncTest` 5/5（含**新增**「半程失败回滚」用例——旧 Fabric 实现在该用例下会残留已补入成员）；Fabric 适配器降级 `MixinClassInfoSyncTest` 2/2；Forge 跨 ClassLoader 接线 `ForgeClassInfoSyncTest` 4/4；全量 JUnit 469 项 0 skipped；变异检查：反向应用实现 diff → 用例变红（变异检查记录，已恢复）；**真实 Forge 1.21.1 回归**：重构后同一 E2E `VERDICT GREEN`（`tests/fabric-mixin-probe/forge-classinfo/evidence/forge-1.21.1-after-ssot-refactor.txt`） |
| NeoForge 21.1（ModLauncher 11.0.5 + sponge-mixin 0.16.4）Mixin 模板热更：新增 `@Inject` handler / 新增 `@Unique` 字段，**走加载器原生 `[[mixins]]` 注册** | ✅ | `plugin-forge` 现有 `ForgeMixinHotSwap` / `ForgeClassInfoSync`（薄适配）；探针 `tests/neoforge-mixin-probe/` | NeoForge **21.1.265**（MC 1.21.1，ModDevGradle 2.0.148，Gradle 8.12，JBR 21）：红基线（禁用同步的 plugin jar）`VERDICT RED`——stage1 reload 被拒 `Conform error ... ClassInfo$Method.isUnique() ... "method" is null`，stage2 `re-weaving failed`；修复后 `VERDICT GREEN`——日志正向出现 `Mixin class-info sync: ... addedMethods=1`（stage1）与 `addedFields=1`（stage2），业务终局：新 handler 每调用 +1、新 `@Unique` 读到初值 456、既有成员保持；`mixin_registration=neoForge-native META-INF/neoforge.mods.toml [[mixins]]`，全程无 `MixinEnvironment::addConfiguration` 手动注册；判定器 6 个合成 fixture（含 degraded / manual-registration 违规样本）自证判定力，验收 `verify-evidence.cmd` exit 0；变异检查：回退 `Verdict` 判定逻辑 → 验收 exit 1（变异检查记录）。证据 `tests/neoforge-mixin-probe/evidence/neoforge-21.1-{baseline-red,after-fix}.txt` |
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
| 两阶段重定义（内部批次路径）：本批存在「新增/类型变更字段」进入补丁计划时，先提交阶段 A（新结构 + 旧方法体，**构造器/`<clinit>` 保留新体**），补丁完成后再提交阶段 C 的新方法体；纯方法体热更仍走单次重定义快路径。默认开启，`-Dremold.agent.two_phase=false` 可回到旧行为。 | ✅ | `RemoldAgent.applyTwoPhase` / `redefineStageA`（阶段编排与整组回滚）；`InitFix.requiresTwoPhase`（只认真正会写的零值暴露字段）；`StageASkeleton.buildWithLiveConstructors`（构造器保留新体，替代 `ConstructorTailInjector` 插桩） | 真实 `processChanges` 端到端 `TwoPhaseProbe` **72/0**（JBR 21）：`two-phase ON` 时并发读线程在 50k 存量实例把阶段 B 窗口拉宽的情况下零 NPE，且依次看到旧结果与新结果；`two-phase OFF` 对照在同一夹具上稳定观测到 `NullPointerException`（红基线），证明读穿窗口真实存在且被两阶段关闭；`TwoPhaseGuardTest` 5/0（新增实例字段/新增静态字段 → true；纯方法体/`@RemoldReinit`/零值等价 → false）；`StageASkeletonTest` 11/0（含窗口实例由新构造器初始化、旧体不回退到新字段的正反例）；全量 JUnit **597/0 skipped**（v0.2.6 发版验证，`./gradlew check`）；`:tests:initFixOracle` `ALL ASSERTIONS PASSED`；`layoutRejectRecoverProbe` 41/0、`doubleBraceProbe` 104/0。**残余边界**：外部 IDE/JDWP 重定义通道与 `redefineDynamic` 单类通道仍走单阶段（读穿窗口仍在，见风险 9）；被安全门拒绝、根本没生成补丁的字段两阶段无法代偿（保持“重启”语义）；阶段 B 完成前，新构造器读取本类新增**静态**字段或同批其它类未补齐字段仍是残余窗口；两次 redefine 会带来双倍 JIT 逆优化（`docs/initfix/07-p05-probe-results.md` §11）。 |

---

## 0.8 v0.2.6：Mixin 匿名/内部类热更闭环（v0.2.6 已发布，2026-11-09）

真实 Fabric 1.21.1 客户端用「普通类 vs Mixin 内」的匿名类 + Lambda 对照试验（R1~R5）暴露三段断层。本节按**已观测**口径登记；仍未闭环或未验证的项已并入 §0.10 的 v0.2.6 统一残余风险清单，不在此重复。

| 修复 / 变更 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| 热更管线自身组件在类链接期抛 `NoClassDefFoundError`（客户端原文 `[Mixin] Mixin hot-swap handler unavailable: NoClassDefFoundError: LoadedTargetSuppressor`），使 Mixin handler 的成功与失败分支同时失效 | ✅ | `core/src/remold/reflect/mixin/MixinConfigContext`（抑制器改为 `Function<Collection<String>,Object>` + `Consumer<Object>`；删除 `LoadedTargetSuppressor`）；`plugin-mixin` `MixinConfigDiff`/`NewMixinRegistrar`、`plugin-forge` `ForgeMixinConfigDiff`/`ModLauncherClassTrackerSpoof` 只传方法引用 | **已观测**：`MixinPluginClassLinkageTest` 机械扫描已编译插件类常量池，断言零引用已废除的 core 类型且不 `implements`/`extends` 任何 core mixin 类型（正向对照 + 变异：恢复硬链接 → 精确点名 `plugin-mixin/remold/mixin/MixinConfigDiff.class` 变红）；`NewMixinRegistrarFailClosedTest` 覆盖「抑制器缺失/返回 null/抛异常 → 放弃登记并输出定向诊断、异常不外泄」。**已知残余**：plugin/core 版本错位仍会退化为 `NoSuchMethodError`（`MixinConfigContext` 构造签名不匹配），该路径由 `RemoldAgent.notifyAssetChanges` 的逐插件 `catch` 兜住并记 error；两侧必须同版本发布 |
| Mixin 注入方法内**首次**新增匿名类 → reload 崩溃：`Error loading class: Outer$1 (ClassNotFoundException)` 随后 `Cannot invoke "ClassInfo.isProbablyStatic()" because "innerClass" is null` | ✅ | `plugin-mixin` `MixinBytecodeOverlay`（安装 `IMixinService` 代理，只对显式登记的内部名提供字节，其余回落宿主 provider）、`MixinHotSwap.handleIfMixin`（reload 前登记内部类字节并驱逐上游 `ClassInfo.cache` 负缓存） | **已观测（单测/字节码级）**：`MixinInnerClassHotSwapTest` 7 用例（归属判定、嵌套名推导、`lookup` 点分/内部名归一、负缓存驱逐按 `containsKey` 计数）；3 个人工变异（去掉 schema 门 / 去掉 passthrough 过滤 / 去掉名字归一）全部击杀并恢复复绿（实测记录、实测记录、实测记录）。**上游机制依据**（sponge-mixin 0.15.5+mixin.0.8.7 源码包）：`ClassInfo.forName` 走 `MixinService.getService().getBytecodeProvider()`，失败把 `null` 写进 `ClassInfo.cache` 且永不重试。**真实 Fabric 1.21.1 KnotServer E2E**（`tests/fabric-mixin-probe/anon/`，mod 走 `run/mods` jar 加载、Gradle 输出不在运行期 classpath）：修复前 S1 `poke` 停在 `hook-s0`，日志为 `Error loading class: ...AnonTargetMixin$1 (ClassNotFoundException)` + `innerClass is null`（RED）；修复后 S1 `poke=anon-v1`、`RESULT: GREEN`（证据 `evidence/anon-r3r5-{red,green}.txt`） |
| 只改已加载匿名类体 → 目标类重织入静默 `[DYNAMIC-REDEFINE] no-op`、匿名类仍跑旧版 | ✅ | `plugin-mixin` `MixinInnerClassSupport`（内部类归属/嵌套名纯函数）、`MixinInnerClassHotSwap`（按 `InnerClassGenerator` 稳定唯一名重新生成合成内部类并 `redefineClasses`）、`MixinHotSwap.handleInnerClassChange`（只改 `Outer$N` 时回到外层 Mixin 重跑） | **已观测（单测/字节码级）**：成员 schema 判定区分「只改方法体」与「增删字段/方法/构造器」，后者给出点名成员的指向性诊断并拒绝重定义（不借 DCEVM 绕过布局门）；`handleInnerClassChange` 在只改 `Outer$N` 时把字节登记进覆盖层并回到外层 Mixin。**上游机制依据**：`InnerClassGenerator.getUniqueReference` 把 `Outer$1` 映射为 `Target$Anonymous$<uuid>`，`isLoaded()` = `loadCounter>0`，已加载的合成内部类不会被重新生成。**真实 E2E**：修复前 S2 外层字节 sha256 前后相同、仅 `$1` 变体，`poke` 仍 `anon-v1` 且日志为 `[DYNAMIC-REDEFINE] no-op`（RED）；修复后 S2 `poke=anon-v2`（GREEN），S3 去触发对照无重定义、值稳定 |

> **本轮观测存档（仍开放项已并入 §0.10 统一清单，不在此重复）**：①「批次落在启动期挂死」同族——探针在 MC 启动期（`Done (` 之前）落批次时曾出现 JVM 挂死（probe 线程无 tick）；本轮把「合成内部类重定义」从桥接线程移到批次之后的 worker 线程，消除了同一族挂死（桥接线程内 `redefineClasses` 会让 KnotServer 线程停摆，实测：S1b 之后 tick 全停）。启动期窗口本身未单独隔离，探针改为等 `Done (` 后再触发；窗口保护见 §0.9/§0.10 的启动门闩与批次看门狗。② 同一批次里模板本身与它的内部类都会被问一次，模板第一次 reload 后按字节相等跳过第二次（`alreadyReloaded`），因此内部类热更只做一次；若两者字节都变，仍会按「重新登记内部类 + 一次 reload」处理（良性幂等）。
>
> 仍开放的残余（plugin/core 版本错位、覆盖层登记项生存期、匿名类成员结构变化需重启、真实客户端复跑）见 §0.10 统一清单第 6～9 条。

## 0.9 v0.2.6：ANON_ALIGN 同形歧义 / 合成类元数据 / 启动期门闩（v0.2.6 已发布，2026-11-09）

真实 Fabric 1.21.1 KnotServer 探针（`tests/fabric-mixin-probe/anon/`）扩展到 S0~S7 后暴露的 3 个深水区问题。按**已观测**口径登记；本轮五条残余风险中 1/2/3/5 已在 §0.10 收口，第 4 条并入 §0.10 统一清单（编号台账见 §0.10 开头）。

| 修复 / 变更 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| ANON_ALIGN 同形匿名类 N-to-1 歧义：同一方法内两个同接口同结构的匿名类让 Tier 3/4 双向唯一失效，编辑被迫孤儿化（客户端 R2b：`topology-equal candidates 2 -> 2, topology-decided 0`），字段持有的存活实例继续跑旧码 | ✅ | `core/src/remold/AnonClassAligner.java`（Tier 3.5 体相似度、Tier 4.5 liveness 兜底、点名诊断、`findHoldingFields`） | **单测**：`AnonClassAmbiguityTest` 17/17（重编号哈希不变、体相似度口径、2×2 歧义兜底、存活阻断、字段持有者点名；配对数按 `scanVisibleLive=` 口径诊断；§0.11 新增 4 例文案/持有者审计用例）；**变异**：三道人工变异全部击杀并复绿（实测记录 liveness 兜底、实测记录 身份语义、实测记录 字面量入 token）；**真实 KnotServer**：S6 `s6a=s6-v1 → s6b=s6-v2` GREEN（重编号的同形匿名类被配对并原地重定义，字段持有的存活实例同步更新），S4 `held-v1 → held-v2` GREEN。**按裁决刻意保留的分支**：两侧都只改字面量（体相似度打平）+ 旧类有存活实例 → 4.5 拒不动手，孤儿化并输出点名诊断（旧类名 + `scanVisibleLive=1` + 可能持有字段 + `@RemoldReinit` 逃生舱），由 `liveInstancesBlockTheFallback` 守住。strict 模式语义未动 |
| Mixin 生成合成类 `Target$Anonymous$<uuid>` 的元数据不可达：`HierarchyTreeOracle.readClassBytes` 只查 `bytecodeCache` 与 classpath 资源，两者皆空 → `Nest scan incomplete`（Nest 判定降级）且 `InitFix` 构造器 protected 可见性判定返回 null → `Field init patch refused ... cannot determine protected status of constructor` | ✅ | `core/src/remold/BytecodeSources.java`（新，登记口）；`HierarchyTreeOracle.readClassBytes`（接线）；`InitFix`（三处拒绝 + Nest 告警的归因文案）；`plugin-mixin` `MixinInnerClassHotSwap`（生成成功后回灌） | **已观测（单测/字节码级）**：`NestBytecodeSourcesTest` 10/0（登记前 `nest member metadata unavailable` → 登记后成功；unregister 回落；两种构造器可见性判定；plugin 回灌路径）；**变异**：去掉 `BytecodeSources.lookup` 接线 killed（4 条行为性断言红）、去掉 plugin 回灌 killed（回灌用例红）；真实探针里 `Nest scan incomplete` 与 `Field init patch refused` 两条告警**已带上归因文案**（点名"织入期生成的合成内部类，需要 `BytecodeSources` 回灌"）。**后续收口**：S7 的 RED 已由 §0.10 的 `refeedForTarget`（提前生成 + 回灌）关闭，S7 `null → s7-v1`；`BytecodeSources` 常驻内存无上限仍在 §0.10 统一清单 |
| MC 启动期落批次导致 JVM 挂死（`Done (` 之前类加载 + Mixin 转换高频并发） | 🔶 | `core/src/remold/StartupGate.java`、`core/src/remold/BatchWatchdog.java`、`core/src/remold/RemoldAgent.triggerDueHotswap` 漏斗接线、`api/src/remold/HotswapPlugin.isHostReady()` | **已观测（单测）**：`remold.startup.*` 19 例（该轮读数；收口后为 30 例、见 §0.10；门闩状态机、未就绪不落批次、就绪后触发一次、看门狗超阈值才 dump 且同批次只一次、`isHostReady` 默认 true 时行为不变）；**变异**：反打 `RemoldAgent` 修复 diff killed（5 条 wiring 断言红），另两次外科变异（门闩恒真 → 4 红、看门狗去 once → 1 红）均已恢复。**后续进展**：`plugin-fabric` 已实现 `HostReadinessProbe`（见 §0.10，真机未验证）；`plugin-forge` 侧仍未实现，实际游戏里门闩仍可能"首次触发即放开"；**精确挂死线程栈仍未抓到**——看门狗即为下一次复发的取证器 |
| 探针仪器扩展（S4 长生命周期字段持有 / S5 `@Unique private` 访问 / S6 1→2 重编号 / S7 字段初始化器构造器可见性） | ✅ | `tests/fabric-mixin-probe/anon/`（`run-anon.sh` + 6 个新 stage + `ANON_EXPECT` 模式 + 独立证据文件） | 真实 KnotServer 读数（基线代码）：S4 GREEN、S5 GREEN、S6 RED、S7 RED；合入 E 后 **S6 转 GREEN**；S3 去触发对照全程稳定。**实证回答**：`Nest scan incomplete` 期间 `@Unique private` 字段/方法访问**仍然正确**（S5 终局值一致）——说明该元数据缺失只影响 Remold 自身的安全门视图，不影响 JVM 侧 nest 访问控制。**结局**：S7 的红基线由 §0.10 的 `refeedForTarget` 关闭，S0~S7 现全绿（`run-anon.sh` exit 0 / `EXT_GREEN=1`） |

## 0.10 v0.2.6：收口轮 · S0~S7 全绿 / 持有者点名 / 门闩接线（v0.2.6 已发布，2026-11-09）

§0.9 的五条残余风险（1 S7 时序缺口、2 看门狗覆盖范围、3 门闩未接宿主、4 `BytecodeSources` 常驻内存无上限、5 字段持有者点名边界）中，1/2/3/5 在本轮收口；4 并入下方统一清单。§0.8 的覆盖层登记项生存期、plugin/core 版本错位、匿名类结构变化限制与真实客户端复跑同样并入该清单。

| 修复 / 变更 | 状态 | 代码位置 | 验收证据 |
|:---|:---:|:---|:---|
| **S7 时序缺口**（§0.9 残余风险 1）：`InitFix` 在目标类本次 redefine 内部就要读 `<Target>$Anonymous$<hash>.<init>` 的 protected 状态，而 Mixin 只在织入期登记、字节要等首次加载才生成 → 字段初始化 patch 被 fail-closed 拒绝 | ✅ | `plugin-mixin` `MixinInnerClassHotSwap.refeedForTarget`（目标织入后按 `InnerClassGenerator` 的稳定唯一名**提前生成**该目标的全部合成内部类并回灌 `BytecodeSources`）+ `MixinReWeaver.reweave` 接线 | **真实 KnotServer 全绿**：`run-anon.sh` **exit 0 / `EXT_GREEN=1`**，S7 `s7=null → s7=s7-v1`（S7c 对照稳定），S0~S6 全部保持；**变异**：回退 `MixinReWeaver` 接线 → 基线 exit 0 → 变异 exit 3（变异检查记录），恢复 OK。**未放宽任何安全门**：字节仍不可得时照旧 fail-closed；生成后把 `InnerClassInfo.loadCounter` 还原，避免"假已加载"让平台跳过真正的 defineClass |
| **字段持有者点名补全**（§0.9 残余风险 5）：精确描述符只能命中合成字段（`this$0`/`val$o`），宿主用户字段必然声明为父类型/接口（`Supplier HELD6`）→ 告警误报"无宿主字段持有" | ✅ | `core/src/remold/AnonClassAligner.java` `findHoldingFields` 三层退化：精确描述符(proven) → 赋值流 `NEW <anon>→<init>→PUTFIELD/PUTSTATIC`(proven，`putfield this$0/val$*` 不误报) → 直接父类型/接口描述符(candidate)；新增 4 参重载返回 `HoldingFields{proven, candidates}`，旧 `List<String>` 契约保持不变 | **单测**：`AnonClassAmbiguityTest` 13 例（接口类型持有必被点名、赋值流 proven、合成字段仍命中、无关系字段不得乱点名）；**变异 5/5 killed**（去候选兜底、去赋值流、去精确层、置空解析器、放宽为"任意引用字段"各自打中对应用例）；告警文案区分 `proven by assignment flow` 与 `candidate (supertype/interface)` |
| **看门狗入口覆盖**（§0.9 残余风险 2）：此前只包住防抖 Class 批次 | ✅ | `core/src/remold/BatchWatchdog.java`（新增 `enter/exit/current` 作用域 API：ThreadLocal + 深度计数，嵌套复用同一实例；`armedWatchdogs()` 守"一个批次最多一个 armed"）+ `RemoldAgent.processChanges` 的 `try/finally`；`retransformLoaded` 自挂作用域；`runClassBatch` 旧包裹移除 | `remold.startup.*` 30 例 0 失败；**变异 4/4 killed**（去 processChanges 挂点 → 4 红；`runClassBatch` 再独立 arm → 2 红；UNKNOWN→false → 3 红；`ticks>0` 恒 READY → 1 红） |
| **`plugin-fabric` 就绪门闩**（§0.9 残余风险 3） | 🔶 | `plugin-fabric` `HostReadinessProbe` + `FabricHotswapPlugin.isHostReady()`：服务端 `MinecraftServer.getServer()` + tick 计数、客户端 `getInstance` + `world/level` 非空 → READY；探测不到一律 UNKNOWN → **返回 true 并按已就绪处理**（只打一条 info），就绪单向缓存 | 单测用替身覆盖三种判定（`FabricHostReadinessTest`）；**未验证**：真实 KnotServer/客户端的访问器名（`getTicks`/`getTickCount`、`world`/`level`）无法在 `.sources/`（仅镜像 Mindustry/Arc）核验，真机未跑 |

**v0.2.6 统一残余风险清单（已观测 / 未验证分列）**

1. **`redefineDynamic`（单类 API 入口）仍未挂看门狗**：三条主路径（防抖/手动/`retransformLoaded`）已覆盖，该单类入口卡死仍无栈。
2. **就绪探测未经真机验证**：`plugin-fabric` 的 `HostReadinessProbe` 仅有单测替身（`FabricHostReadinessTest`），真实 KnotServer/客户端的访问器名（`getTicks`/`getTickCount`、`world`/`level`）无法在 `.sources/`（仅镜像 Mindustry/Arc）核验，真机未跑；`plugin-forge` 侧尚未实现 `isHostReady()`。fail-open 把杀伤面限制在"保护窗口可能比预期窄"（服务端实例构造前即 UNKNOWN→放行），不会饿死批次。
3. **`BytecodeSources` 常驻内存无上限**（沿用 §0.9 残余 4）：键集合不随 reload 增长（唯一名跨 reload 稳定），但无驱逐策略；回灌发生在 schema 复核之前，即"读得到"不等于"已生效"。
4. **持有者点名边界**（收口轮补全后的残余）：只扫 old/new 宿主节点的字段表与方法体；跨方法经参数传递不猜（宁可漏点名）；宿主继承字段若 javac 以父类为 owner 发射 `putfield` 不会命中；候选只看直接父类型，不递归层级。父类型/接口持有已由三层退化点名（`Supplier HELD6` 形态），但"任意引用字段"不点名为 proven。
5. **热更管线自身的证据仪器存在"假 PASS / 假击杀"风险**（本轮 worker 实测）：退出码 127（命令不存在）被记为 killed、cmd banner-only 输出被记为 PASS、Gradle UP-TO-DATE 的重复 stdout 哈希被当作新证据。当前由人工复核兜住，工具侧修法见战役台账；在这些修复前，本表所有"PASS/killed"均以**原始日志已人工复核**为前提。
6. **覆盖层登记项的生存期**：成功 reload 后内部类字节保持在覆盖层里（目标织入期间还需要它），后续同类变化会覆盖同名项；Mixin 模板内部类不会被 JVM 当普通类加载，因此长期驻留不会遮蔽真实类。
7. **plugin/core 版本错位**：抑制器已无 core 类型硬链接，但 `MixinConfigContext` 构造签名不匹配时仍会抛 `NoSuchMethodError`（由 `RemoldAgent.notifyAssetChanges` 的逐插件 catch 兜住并记 error），两侧必须同版本发布。
8. **匿名类成员结构变化（新增字段/方法）仍只能拒绝并提示重启**：未做结构化热更；`ForgeMixinHotSwap` 未接本轮内部类闭环。
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

## 状态图例与判定准则 (Legend & Criteria)

| 图例 | 状态定义            | 判定准则与要求                                                                                                                                |
|:----:|:--------------------|:----------------------------------------------------------------------------------------------------------------------------------------------|
|  ✅  | 已实现              | 生产代码落地 + 拥有对应的自动化测试/回归断言，并在主构建与 CI 中持续全绿。**包含设计意图内的保守边界**。                                      |
|  🔶  | 部分实现 / 存在残余 | 主路径已上线，但存在未覆盖的已知边缘漏洞、尚未修复的已知残余缝隙，或缺少关键自动化测试（行内必须明确注明缺什么）。                            |
|  🟣  | 有意偏离            | 为修复实际缺陷或守卫核心不变量，经架构论证明确否定原设想或推翻原设计规格。行内必须附带决策记录（[`docs/topology/08-decisions.md`](topology/08-decisions.md)）链接与具体原因。 |
|  ⬜  | 路线图规划 / 待办   | 设计规格已定义但当前尚未落地实现的代码项或测试项。                                                                                            |
|  ❌  | 已废除              | 历史上曾存在，但为守卫类身份与内存安全已被彻底物理拔除的机制。                                                                                |

### 判定边界与反例清单 (Criteria Boundaries & Negative List)

为防止未来维护者误用状态标记，特确立以下判据边界与反例：
- **✅ 反例**：不适用于“虽未报错但缺少关键正向/负向回归测试”的项；但**明确适用于**“主路径功能完整且明确界定有意保守边界（如 Tier 3 对无信息/同构平局的确定性拒绝）”的项（意图内的保守拒绝属于设计完成态，而非缺陷）。
- **🔶 反例**：**严禁将“设计意图内的保守拦截”误标为 🔶**（那是 ✅）；**严禁将“从未实现的新功能”误标为 🔶**（那是 ⬜ 路线图）。🔶 严格限定于“代码已上线运行，但存在已知未覆盖的边缘漏洞或未闭环的工程缝隙”。
- **🟣 反例**：**严禁将普通的“暂缓实现的待办规划”标为 🟣**（那是 ⬜）。🟣 严格限定于“经过充分论证，明确决定不采用某方案（或推翻原设计），因该方案已被证明会破坏系统核心不变量或引入严重隐患”。

---

## 1. 匿名类拓扑对齐与安全门 (Anonymous Class Alignment & Safety Gates)

设计规格文档：[`docs/topology/`](topology/) 系列文档  
架构决策记录：[`docs/topology/08-decisions.md`](topology/08-decisions.md)

| 机制 / 条目 | 状态 | 规范章节 | 落地位置 | 验收证据与决策依据 |
|:---|:---:|:---|:---|:---|
| javac 8/11/17/21/25 本机编译矩阵支持 | ✅ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §1 | `tests/build.gradle` (任务 `test`) | `AnonClassReproJUnitTest` / CI `.github/workflows/ci.yml`（Temurin 25 runner，5 套工具链现编夹具矩阵通过；注意 `SwitchMapAlignTest` 单独在 javac 21 验证） |
| 新 javac 加旧 -target 交叉编译矩阵 | ⬜ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §3 | 待配置测试矩阵 | 新版 javac 携带 `-target 8/11` 等交叉编译产物的命名与属性表现尚未系统性覆盖 |
| ECJ 编译器支持 | ⬜ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §4 | 暂未接入 ECJ 编译器 | 见 `07-layout-gate-and-risks.md` §4；ECJ 不生成 SwitchMap 类，EnclosingMethod 结构存在差异 |
| 未知 class major 显式拒绝 | ⬜ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §1 | `AnonClassAligner.align` 入口待拦截 | 路线图待办；遇到未知高版本 class major 时显式拒绝而非尝试解析，防止 ASM Crash |
| 类身份与实例状态保真不变量 (INV-Remap) | 🔶 | [`01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) §1 | `AnonClassAligner` | 彻底移除 Tier 5；未匹配旧类保留为孤儿，未匹配新类分配安全新名；**残余盲区**：局部类内部嵌套匿名类（如 `Foo$1Helper$1`）因含字母被 `isAnonymousClassName` 排除而原名直通，若局部类发生位移存在潜在槽位篡夺漏洞 |
| `renameMap` 与字节码改写 | ✅ | [`01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) §2 | `AnonClassAligner.remapClass` | ASM `ClassWriter(0)` + `SimpleRemapper`，不重算栈帧避免类加载死锁 |
| 全局保留名域与挂起避让 | ✅ | [`02-admission-and-reserved-names.md`](topology/02-admission-and-reserved-names.md) §2 | `AnonClassAligner.takenTargetNames` / `RemoldAgent.pendingAlignedClasses` | 覆盖第 1/2/3/4 类保留名（第 3 类为未参与对齐的排除类名，如静态嵌套类、非匿名局部类等显式占用的物理类名，防止重命名覆盖）；快照接入 `pendingAlignedClasses`；未加载类编号抬高为已知无害代价 |
| 字节码摘要与恒等映射短路 | ✅ | [`01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) §2.3 | `RemoldAgent.processChanges` | 新旧字节码相同直接跳过；`renameMap` 全恒等映射直接返回原字节码 |
| 全局独占锁互斥 | ✅ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §2 | `RemoldAgent.HOTSWAP_LOCK` | 热更新调度入口与事务处理覆盖全局锁，杜绝重入与并发竞态 |
| Tier 1: 内容哈希 + 宿主方法精确匹配 | ✅ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §1 | `AnonClassHasher` + `AnonClassAligner` | 双方哈希完全一致且同宿主方法，置信度最高 (0.99) |
| Tier 1.5: 内容相似度 | ⬜ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §1 | 待实现于 `AnonClassAligner` | 路线图规划；规划计算指令哈希交集率与字符串 Jaccard；用于根治拓扑无信息同构候选平局。**关联落地**：同形匿名类 N-to-1 歧义已由 Tier 3.5 体相似度 + Tier 4.5 liveness 兜底在 v0.2.6 消解（见 §0.9）；本行 Tier 1.5 的规划定位不变 |
| Tier 2: 全局唯一内容哈希匹配 | ✅ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §1 | `AnonClassAligner` | 仅全类唯一孤本采纳，严禁跨方法 minDiff |
| Tier 3: 结构签名 + 拓扑相等过滤 | ✅ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §2 | `AnonClassAligner.topologyEqual` | minDiff 已被拓扑相等过滤取代以杜绝前插错配；夹具 M（拓扑无信息）与夹具 L（2×2 同构）的保守拒绝属于客观设计意图（杜绝静默错配的必要代价，见 [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §2.3），非未完成功能；其边界收敛计划归入 §4.1“子类引用多重集独立正交维度” |
| 夹具 L KNOWN 保守代价收敛 | ⬜ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §2.3 | `AnonClassReproTest.known` | 夹具 L 当前作为 KNOWN 保护保守拒绝行为；未来需引入正交维度以在安全前提下恢复配对 |
| Tier 4: 松散结构 + 状态布局门包装 | 🔶 | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §1 / [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1 | `AnonClassAligner` + `LayoutGate` | 同宿主方法与基类/接口；禁止 minDiff；包装布局门防存活实例零值污染；**存在残余风险**：依赖 `normalizeEnclosingMethod` 的 scope 偶然不一致，若旧匿名类宿主方法同样无法反向追溯退化为 null，可能与非方法匿名类或 SwitchMap 误配（见风险 2） |
| Tier 5: 物理类名盲配 | ❌ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §1 | `AnonClassAligner` (已物理拔除) | 为守卫核心不变量已彻底移除，前 4 层未匹配直接判为新类/孤儿，杜绝槽位篡夺 |
| 嵌套匿名类描述符定向屏蔽与后置校验 | ✅ | [`01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) §2.2 | `MethodFingerprinter.maskDescriptor` + `AnonClassAligner.verifyFieldLayoutAfterRename` | 字段、方法与构造器描述符定向屏蔽外层纯数字序号，后置校验防止改名碰撞 |
| 非方法上下文作用域归约 | 🔶 | [`03-cascading-pipeline.md`](topology/03-cascading-pipeline.md) §2 | `AnonClassAligner.normalizeEnclosingMethod` | 无显式三态标签（`<initializer>`/`<clinit>`/`<init>`），靠 `orderIndex` 与结构签名区分（演进规划见 §4.1） |
| INV-1 自描述指纹与 INV-2 禁止互相递归 | ✅ | [`03-cascading-pipeline.md`](topology/03-cascading-pipeline.md) §4 | `AnonClassHasher` / `AnonClassAligner` | `AnonClassReproJUnitTest.s25_designInvariants`；常量池扫描与内容变异架构守卫，防止雪崩污染与循环调用 |
| 宿主级原子拒绝机制 | ✅ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §4 | `AlignmentRejectedException` / `RemoldAgent.rejectHostGroup` | strict 歧义、超限、超时或关闭时，宿主与派生类整组移出本轮并输出 `[HOTSWAP-REJECT]` |
| 性能安全硬闸门 | ✅ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §3 | `AnonClassAligner.MAX_ANON_PER_HOST` (128), `ALIGN_TIMEOUT_MS` (2000) | 数量超限或超时直接触发宿主组原子拒绝 |
| 嵌套深度硬上限 | 🔶 | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §4.1 | `RemoldAgent.ANON_STRICT` / `AnonClassAligner` 的 `maxLevel > 4` 判定 | strict 模式下 `maxLevel > 4` 抛 `AlignmentRejectedException` 拒绝宿主组；常规模式下仅告警；缺的是该熔断的负向夹具（见 §4.2「嵌套深度 strict 熔断缺少夹具」）。`AnonClassHasher.MAX_DEPTH` 是从不生效的历史常量，不在此列（见 §4.2 末注） |
| 事务生命周期与乐观预登记 | ✅ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §2 | `AlignmentTransaction.preRegister` | 重定义前预先登记 pending 注入项，消除类加载重入读取磁盘错位产物竞态 |
| 匿名类合成捕获字段布局门 | ✅ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1 | `LayoutGate.check` / `RemoldAgent.ANON_LAYOUT_GATE` | 阻断捕获字段增/改导致存活实例读取零值；默认模式 `reject` |
| 具名类与用户显式字段布局门 | ✅ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1 | `RemoldAgent.applyRedefineLayoutGate` / `LAYOUT_GATE` | 阻断用户字段删除、类型变更、静态性变更；默认模式 `reject`；纯新增放行交给 InitFix。**删除的判据是"批次外读者为空"**（不是"有删除就拒"，也不是"全局无读者"）：批次内的类扫新字节码、批次外的扫当前字节码，命中 `Fieldref` 才拒；无读者时以 `WAIVE_NO_READERS` 放行，扫描未完成时一律拒绝（§13.6、风险 12）。该判据对具名类与匿名类合成字段同样生效（匿名类的读者必在类内、类又必在批次内，天然可证），`LayoutGate.check` 只负责匿名类捕获字段那一段。要让删除真正落地，可先用 `warn`/`off` 观察，或把读者留在批次外（§13.5）；拆批/黑名单等手段同理。实测后果（JBR 21，[`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §13）：布局不压缩、废弃槽位不复用，批次外读者抛 `NoSuchFieldError`——是"错误被推迟到热更事务之外"，不是"读到脏数据" |
| 删除成员的批次外读者分析 | ✅ | [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §13.6、§15 | `core/src/remold/ref/RemovedMemberReaders` | 纯函数。字段按 JVMS 5.4.3.2（自身 → 父接口 → 父类）判"改动后引用是否悬空"；**实例方法额外走 JVMS 5.4.6 的向下分派**（归属方向相反：owner 可以是 `A` 的任何祖先），输出六档——悬空 / 抽象方法缺失 / **静态性翻转（ICCE）** / 判不了 / 分派回退 / 同静态性改指向；**只有前三档阻断**，后两档是合法回退，只记日志（§16.4）。`refs()` 为纯常量池扫描（tag 15 的 `reference_index` 必然回指 9/10/11，故天然覆盖指令与 `invokedynamic`/`LDC` 的句柄）。`RemovedMemberReadersTest` 23 用例、`LayoutGateWiringTest` 34 用例，6 处变异检查；`ReaderScanProbe` 验证"常量池扫描是方法体精扫的超集"，`P05Probe` M7/M8 给出失败形态实测。**接线**：`applyRedefineLayoutGate` 在批次循环前扫一次；`decideLayout` 对 `REMOVED_FIELD` 用 `WAIVE_NO_READERS` / `REJECT`，对"本轮删过方法"另设分支（**必须在 `compatible()` 早退之前**，否则只删方法的编辑永远不会被拦），扫描未完成时方法一律拒绝。扫描范围取"已加载的工程类（内存字节）+ 工程构建输出"，JDK 与第三方 jar 不在范围内；成本 434 类约 25 ms（§14.6）。缓存覆盖由 `CacheWarmingTransformer`（首次加载填充）+ 一次批量 retransform 补齐，并滤掉数组类/hidden class/不可重定义类。**缺口**：pre-11 合成访问器未折算；`invokespecial` 与 `invokevirtual` 在常量池里同 tag，按"可能分派"保守处理 |
| 标准 JVM 能力探测、结构变化摘批与降级开关 | ✅ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §1 | `RemoldAgent.probeEnhancedHotswap` / `mustSkipForMissingEnhancedRedefinition` | 厂商标识 + flag 文本双条件，杜绝 `-XX:+IgnoreUnrecognizedVMOptions` 回显造成的误判；结构变化类在无增强能力时摘出本批，不再拖垮同批纯方法体修改；`FORCE_STATIC_LAMBDA` 在无能力时被强制降级关闭并打 `DEGRADED` 日志（`degradeForMissingEnhancedRedefinition`；`INIT_FIX` 刻意不降级，否则会让直接调用 `InitFix.transform` 的路径静默空转）；`minecraft-fabric-template/README.md` 已按运行时能力分档说明，不再承诺“完美适配任意 JDK 21+”；`EnhancedHotswapGateTest` 9 用例，含变异检查（改回参数文本探测时 `probeMatchesRealVmOptionState` 变红） |
| 重定义层三态存活判定与静态字段门 | ✅ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1 | `RemoldAgent.decideLayout` | `COMPATIBLE` / `WAIVED` / `REJECTED`。**删除静态字段**与实例字段共用"批次外读者为空"，但扫描未完成时只能拒绝——静态值不随实例存在，liveness 兜不住（[`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §16）。**编译期常量（`ConstantValue`）放行（只记日志）**：javac 把值内联进读者的 `ldc`/`bipush`，这类读者常量池里没有 `Fieldref`，"无读者"不是安全证明而是没有信号；未内联的读者仍有 `Fieldref`，会被 `hits` 正常抓到（§16.1）。已内联的读者需全量重编才能看到新值，这属于 Java 常量的既有语义 |
| 同名局部类编号漂移止血门 | 🔶 | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §2 | `LocalClassGuard` / `RemoldAgent.LOCAL_CLASS_GUARD` | 识别同名局部类计数 >= 2 并将宿主整族移出本轮；**未覆盖“唯一简单名局部类内部的匿名类位移”形态**（见风险 5） |
| 同名局部类完整拓扑对齐 | ⬜ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §2 | 待实现于 `LocalClassGuard` | 路线图规划；规划准入与保留简单名改号；当前由止血门安全拦截 |
| 特性控制开关集合 | ✅ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §4 | `RemoldAgent` 配置表 | 覆盖 `anon_align`（false 为整组移出）、`anon_strict`、`anon_debug`、`anon_layout_gate`、`layout_gate`、`local_class_guard` |
| 蜕变测试准则 (Metamorphic Suite) | ✅ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §3 | `AnonClassReproJUnitTest.s19_cascadingTreeAndMetamorphicSuite` | 验证标记置换不变性、幂等性、尾部追加不变性与故障注入零静默错配 |
| **[偏离]** 保留内容哈希而不剥离子类引用 | 🟣 | [`08-decisions.md#d-anon-1`](topology/08-decisions.md#d-anon-1) | `AnonClassHasher` | 决策记录 [D-ANON-1](topology/08-decisions.md#d-anon-1)；剥离子类引用会导致同构无参 Runnable（Save/Delete）lambda 指纹相同，引发严重静默对调劫持 |
| **[偏离]** 不引入 `#ANON_COARSE` 粗粒度签名 | 🟣 | [`08-decisions.md#d-anon-2`](topology/08-decisions.md#d-anon-2) | `AnonClassHasher` | 决策记录 [D-ANON-2](topology/08-decisions.md#d-anon-2)；粗粒度签名对同构 Runnable 逐字节相同，使宿主指令流丧失区分度，直接复发 Save/Delete 对调 |
| **[偏离]** 不实现 SwitchMap 排除与原名直通 | 🟣 | [`08-decisions.md#d-anon-3`](topology/08-decisions.md#d-anon-3) | `AnonClassAligner` | 决策记录 [D-ANON-3](topology/08-decisions.md#d-anon-3)；验收证据 `SwitchMapAlignTest` (javac 21)；曾设计“将 SwitchMap 列入排除名录并原名直通”，但论证表明原名直通会在前插枚举时打开槽位篡夺漏洞，因而明确否决并推翻原排除直通设计；当前靠作用域差异与布局门安全拦截，残余机理见风险 2 |
| **[偏离]** 合成捕获字段计入哈希而非剥离 | 🟣 | [`08-decisions.md#d-anon-4`](topology/08-decisions.md#d-anon-4) | `AnonClassHasher` + `LayoutGate` | 决策记录 [D-ANON-4](topology/08-decisions.md#d-anon-4)；JBR 证实捕获字段变更使存活实例读零值；计入哈希以触发 Tier 4 布局门拦截，防止原地破坏 |
| **[偏离]** `access$` 编译器访问器保名不改名 | 🟣 | [`08-decisions.md#d-anon-5`](topology/08-decisions.md#d-anon-5) | `AnonClassAligner.remapClass` | 决策记录 [D-ANON-5](topology/08-decisions.md#d-anon-5)；跨类调用点无法同步修改，改名会导致 NoSuchMethodError；保名可区分不同访问器 |
| **[偏离]** 继承体系变更一律拒绝并提示重启 | 🟣 | [`08-decisions.md#d-anon-7`](topology/08-decisions.md#d-anon-7) | `RemoldAgent.processChanges` | 决策记录 [D-ANON-7](topology/08-decisions.md#d-anon-7)；与原规划“尝试支持接口变更”相反，父类/接口变动一律熔断以守卫全局类型体系安全 |

---

## 2. 存量初始化修复器 (InitFix)

设计规格文档：[`docs/initfix/`](initfix/) 系列文档  
架构决策记录：[`docs/initfix/06-decisions.md`](initfix/06-decisions.md)

| 机制 / 条目 | 状态 | 规范章节 | 落地位置 | 验收证据与决策依据 |
|:---|:---:|:---|:---|:---|
| 五条核心不变量 | ✅ | [`AGENTS.md`](../AGENTS.md) §2 | `InitFix` 主流程入口与熔断保护 | 未初始化守卫、PENDING 弱键 (5min TTL)、PatchReport 解耦、LinkageError 窄熔断、afterRedefineFailed 立即注销；`initFixOracle` 51 场景 / 350 断言持续守卫 |
| 离线元数据契约 (`ClassHierarchyOracle`) | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §5 | `ClassHierarchyOracle` / `HierarchyTreeOracle` | 全程无类加载；流式字节码扫描；`AnnotationTransformer.HierarchyTree` 底座支撑 |
| 待补字段台账 (`FieldLedger`) | ✅ | [`02-closure-and-ledger.md`](initfix/02-closure-and-ledger.md) §2 | `InitFix.LEDGER` (`FieldLedger`) | 覆盖六种记账情形（分析期被拒、运行期异常、依赖跳过、Redefine 失败、规划期异常、TTL 超时清扫）；`getUnpatchedFields` 状态回流 |
| T0 零值等价判决 | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §1 | `InitFix.checkSafe` / `NOTHING_TO_PATCH` | 按位比较排除 `-0.0f`/`NaN`；`NOTHING_TO_PATCH` 零开销放行不告警；`initFixOracle` 验证通过 |
| T1 编译期常量 | 🔶 | [`01-safety-gate.md`](initfix/01-safety-gate.md) §1 | `InitFix.extractFieldInits` | 静态 `ConstantValue` 走专用通道；字面量切片仍走常规直线提取 |
| T2 纯计算切片 | 🔶 | [`01-safety-gate.md`](initfix/01-safety-gate.md) §1 | `InitFix.extractFieldInits` / `AliasInterpreter` | 经 `AliasInterpreter` 逆向切片并通过效应安全门；`initFixOracle` 多场景覆盖 |
| T3 复杂/不安全拒绝 | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §1 | `InitFix.checkSafe` / `InitFix.transform` | 分支、try/catch、环境依赖、可变源字段，统一在 `transform` 发 warn 并记入台账；负向断言全面覆盖 |
| T4 显式逃生口 `@HotswapInit` | ⬜ | [`09-hotswapinit-design.md`](initfix/09-hotswapinit-design.md)（契约已定，`01-safety-gate.md` §1 为分层入口） | 设计草案已提交（待评审），**尚未实现**；实现落在 `InitFix.buildPatch` 的候选集段 | 让 T3 拒绝的初始化式（Kotlin `?.`/`?:`、读配置、跨类依赖）由用户提供值，不再永久停在类型默认值。契约要点：只换**值的来源**，不换作用域、不换写入协议；`@RemoldReinit`（扩展候选集/改写入协议）与 `@OnReload`（每次热更回调）各司其职，三者不重叠 |
| 最小效应检查（P0） | 🔶 | [`01-safety-gate.md`](initfix/01-safety-gate.md) §2 | `InitFix.effectReason` / `blacklistedCallReason` | 拦截 bit 3/6 及 bit 4/5 子集；白名单防误杀；Kotlin `trim` 家族 `Object.toString` 窄例外（变异测试守卫） |
| 8 位完整效应掩码 | ⬜ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §2.2 | 待实现于 `AliasInterpreter` | 目标设计路线图（PURE, READS_FINAL, ALLOC_PURE 等完整位图） |
| 参数回溯不可变证明 | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §3 | `InitFix.scanParamFields` / `InitFix.NestView` | 条件 A（final/val 字段）与条件 B（private + Nest 全单写证明）；`sourceFieldNotImmutableReason` 守卫 |
| 多根构造器共识 | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §4 | `InitFix.extractFieldInits` | 要求所有根构造器覆盖完整且参数替换后指令指纹 100% 一致；多构造器歧义安全拒绝 |
| 依赖闭包与 Tarjan SCC 成环检测 | ✅ | [`02-closure-and-ledger.md`](initfix/02-closure-and-ledger.md) §1 | `InitFix.buildPatch` | 成环检测并入闭包不动点计算，只拒绝环成员；出口后置 `depInstance ∪ depStatic` 闭合校验兜底 |
| 失败策略矩阵 | 🔶 | [`02-closure-and-ledger.md`](initfix/02-closure-and-ledger.md) §3 | `InitFix.runIndependentInstanceTasks` / `InstanceFailureBudget` | 已支持单字段失败跳过、依赖跳过、`LinkageError` 熔断；“放弃提交阶段 C”已随两阶段协议落地（`RemoldAgent.applyTwoPhase` / `redefineStageA`，见 §0.7） |
| 伴生补丁类装配 | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §1 | `InitFix.generatePatchClass` / `Lookup.defineHiddenClass` | 每字段生成独立静态直线方法；宿主 hidden nestmate 隔离异常与加载死锁 |
| 构造器尾部插桩器 | 🔶 | [`04-target-design.md`](initfix/04-target-design.md) §4 | `core/src/remold/ref/ConstructorTailInjector` | 纯函数字节码改写：在每个根构造器的每个 `RETURN` 前插入 `ALOAD 0; <切片>; PUTFIELD`；跳过 `this(...)` 委托构造器（避免沿委托链重复执行）与体内已自行赋值目标字段的构造器（构造器逻辑优先）；只插直线代码、写入侧仅 `COMPUTE_MAXS` 不重算栈帧。`ConstructorTailInjectorTest` 8 用例覆盖多根构造器、多返回路径、`this` 读取切片与覆盖保护，并做过变异检查。**接线状态**：两阶段契约下阶段 A 保留新构造器/`<clinit>`，窗口内新建实例由新构造器直接初始化，因此本组件**有意不接线**（单测保留，见 §4.1）；仅当将来要求阶段 A 的构造器也回退旧体时才重新需要 |
| 阶段 A 骨架合成器 | ✅ | [`04-target-design.md`](initfix/04-target-design.md) §1 | `core/src/remold/ref/StageASkeleton` | 以新类为骨架、把旧方法的执行体整体搬回（不深拷贝、不重映射 label，栈帧天然有效），实现「行为不变、只让新结构就位」；成员按**旧 ∪ 新**处理，新版本删掉但旧体仍在调用的方法必须留着休眠。`StageASkeletonTest` 11/0，其中 `everyPreexistingMethodKeepsItsOldBodyVerbatim` 做**指令指纹级**结构等价断言（中止回滚依赖阶段 A 行为完全惰性），另覆盖旧 `<clinit>`、被删方法保留、新字段声明、新方法预注入、与构造器插桩组合、窗口实例由新构造器初始化、旧体不回退到新字段的正反例；变异检查：把 `adoptBody` 短路（等价于「只保留新方法体」）会让 5 条用例变红。**接线**：已接入 `RemoldAgent.applyTwoPhase` / `redefineStageA`（阶段 A → B → C 编排 + 整组回滚），见 §0.7 |
| 逐实例补丁驱动与失败配额 | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §2 | `InitFix.runDependentInstanceTasks` / `InstanceFailureBudget` | 依赖失败按实例隔离；单字段每轮实例失败配额封顶 8 次（`MAX_INSTANCE_FAILURES_PER_FIELD`） |
| 存活实例枚举时机 | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §2 | `InitFix.applyPatch` / `collectInstancesForPatch` | 枚举推迟到重定义生效之后，不再预拍快照：此后不可能再产生旧布局实例，枚举天然完备；枚举期间新建的实例由新构造器初始化，条件 CAS 对它们是无害空操作。`InitFixSnapshotTimingTest` 先红后绿守卫（旧实现在此用例上确实为红） |
| 写入协议 (`HotswapBridge`) | 🔶 | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §3 | `HotswapBridge` (`KIND_CONDITIONAL` / `KIND_FORCE`) | 默认条件 CAS；float/double raw bits CAS；跳过计数汇总汇报；`KIND_FORCE` 强制写；**缺自动化 CI 差异验证**（当前 CI 为 Temurin 25，无法区分 raw-bits 与旧实现；JDK 8 raw-bits CAS 为手工验证，非持续自动化覆盖，见 §4.2「JDK 8 raw-bits CAS 自动化 CI 差异验证缺失」） |
| 存量覆写扩展 (`@RemoldReinit`) | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §4 | `remold.annotation.RemoldReinit` / `InitFix.buildPatch` / `InitFix.reinitHint` / `InitFix.isClosedSlice` | 豁免 T0 与后续加工门；不豁免切片安全门；支持 `CONDITIONAL` 与 `OVERWRITE`；生效时 `applyPatch` 输出 `[REINIT]` 点名字段与模式，未生效的标 `failed`/`skipped`/`no live instance`；重置时机为"初值表达式变了"，封闭表达式可跳过、开放表达式（跨类调用/他类静态字段）照常每轮重置 |
| 存量重置实例作用域 (`@RemoldReinit(scope = ...)`) | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §4 | `remold.annotation.RemoldReinit.Scope` / `InitFix.scopeMatches` / `InitFix.applyPatch` / `InitFix.buildPatch` | `SELF` 只重置运行时类**恰好等于**声明类的实例，子类/匿名子类实例保持原值；`INCLUDE_SUBCLASSES` 保持既有"含子类/匿名子类"语义且为默认值（新增注解元素带默认值，二进制兼容）；宿主先经 `ClassIdentity.canonicalize` 归一，JBR 结构性 redefine 产生的影子 Class 不会让 SELF 误判或让 `LibTool.getInstances` 返回 0；同一宿主的混合 scope 按字段过滤（新增字段仍覆盖子类实例）；静态字段显式 `scope` 被忽略并打一条 `[REINIT-SCOPE]` warn；`[REINIT]` 点名的 `no live instance` 按字段作用域判定（SELF 字段只在有本类实例时才算生效）。验收：`:tests:scopeReinitProbe` 35/35（annotation-only + 结构性 redefine + 影子 Class 场景；变异检查：移除精确类过滤后 6 条断言变红、恢复后复绿），`ScopeReinitTest` 9 用例；证据 [`tests/fabric-mixin-probe/evidence/scope-reinit.txt`](../tests/fabric-mixin-probe/evidence/scope-reinit.txt) |
| 合成标记字段过滤 | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §5 | `ClassDiffUtil.isInternalMarkerField` | 对称过滤 `ACC_SYNTHETIC` 与 `$remold$` 前缀字段 |
| JVMTI 多态堆实例检索 | ✅ | [`05-jvmti-heap.md`](initfix/05-jvmti-heap.md) | `LibTool.getInstances` / `InstanceTracker` | `LibTool.getInstances` C++ 底座；Tag 隔离与全局互斥；`InstanceTracker` 字节码回退 |

---

## 3. 物理内存偏移稳定性与第三方缓存驱逐 (Physical Memory Offset Stability & Reflection Cache Eviction)

设计规格文档：[`docs/offset/01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md)、[`docs/offset/02-inherited-field-drift-fix.md`](offset/02-inherited-field-drift-fix.md)  
实测探针源码：[`tests/src/OffsetStabilityProbe.java`](../tests/src/OffsetStabilityProbe.java)（JBR 21 DCEVM 47/47 断言通过）

| 机制 / 条目 | 状态 | 规范章节 | 落地位置 | 验收证据与决策依据 |
|:---|:---:|:---|:---|:---|
| 物理字段布局预测器 (`LayoutPredictor`) | ✅ | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §3.1 | `core/src/remold/offset/LayoutPredictor` | 独立 `EphemeralClassLoader` clean-load 预测物理偏移；Probe A3 实测预测值与重定义后实际物理布局逐字节 100% 一致；`LayoutPredictorTest` 覆盖单类填洞、引用顺移与 3 层继承体系 |
| 包级私有父类运行时包隔离防御 | ✅ | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §3.1 | `LayoutPredictor.populateNonJdkSuperclasses` | 预先提取整条非 JDK 祖先链注入同一 EphemeralClassLoader，杜绝跨 Loader 导致包级私有父类抛出 `IllegalAccessError`；`LayoutPredictorTest.testPackagePrivateSuperclassRuntimePackageAccess` 正反向对照通过 |
| 偏移量与访问器扫描器 (`OffsetScanner`) | ✅ | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §2 | `core/src/remold/offset/OffsetScanner` | 常量池预过滤（owner 命中率 < 2%）+ ASM Analyzer 多轮过程间数据流分析；识别 `Unsafe.objectFieldOffset`、`Atomic*FieldUpdater`、`VarHandle` 等产生源；`OffsetScannerTest` 21 个样例类与真实 JDK 类（CHM、AQS、ForkJoinPool 等）全覆盖 |
| 访问器自愈与受害者边界收敛 | ✅ | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §2 | `OffsetScanner` / `OffsetStabilityProbe` (Probe E/E2) | 实测证明 `Field.getInt()` 与 `MethodHandle` getter（`findGetter`/`unreflectGetter`）在 DCEVM 下自动自愈至新偏移，不作为漂移受害者；仅拦截不会自愈且错读相邻字段的 `VarHandle`，避免对 Jackson/Record 等现代框架造成误杀 |
| 物理偏移漂移安全门 (`SuperclassDriftGate`) | ✅ | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §3.2 | `core/src/remold/offset/SuperclassDriftGate` | 结果驱动型门控：对所有物理偏移发生漂移的字段比对 `OffsetBindingIndex`；阻断 `SUPERCLASS_FIELD_DRIFT` 与 `LAYOUT_DRIFT`；`OffsetStabilityProbe` Probe C2 与 `SuperclassDriftGateTest` 6 场景持续验证通过 |
| 删除字段物理槽位冲突门 (`DELETED_FIELD_OFFSET_CONFLICT`) | ✅ | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §1.2 | `SuperclassDriftGate.checkLayoutDrift` | Probe A4/A4-2 实测证实删除字段后物理槽位紧缩（旧偏移被相邻字段抢占），若存在旧物理偏移持有者则坚决阻断；`LayoutPredictorTest.testDeletedFieldConflictGate` 验证通过 |
| 预测未完成容错与严格模式 | ✅ | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §3.2 | `SuperclassDriftGate` / `RemoldAgent.OFFSET_STRICT` | 未复现子孙类记入 `unpredictedClasses`；`-Dremold.agent.offset_strict=true` 严格模式下强阻断；默认模式下若无缓存持有者则容错放行，有持有者坚决拦截；`LayoutPredictorTest.testIncompletePredictionAndStrictMode` 验证通过 |
| 盲区透明化结构日志 | ✅ | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §3.2 | `OffsetBindingIndex` / `SuperclassDriftGate` | 统计不可读类（`unreadableClasses`）、深度超限（`depthExceeded`）与未决动态持有者（`unresolvedHolders`），门控决策报告完整输出，杜绝静默假安全 |
| 第三方框架实例级缓存驱逐管理器 | ✅ | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §4 | `core/src/remold/evict/ReflectionCacheManager` | `ReflectionCacheEvictor` SPI 契约；接线 `RemoldAgent` 重定义成功出口；原生堆遍历（`LibTool.getInstances`）+ 弱引用显式注册双通道；核心只内置与引擎无关的 Jackson / Gson 驱逐器，引擎专属驱逐器由扩展插件经 `RemoldAgent.registerCacheEvictor` 注册；`ReflectionCacheManagerTest` 4 用例覆盖注册生命周期与异常隔离 |
| Arc `Json` 实例级缓存驱逐器 | ✅ | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §4.3 | `plugin-arc/src/remold/arc/ArcJsonEvictor` | 自动清除 `typeToFields`、`classToDefaultValues`、`classToSerializer` 针对被重定义类的元数据缓存，使新增字段立即可见；Arc 类型与字段名只存在于 `:plugin-arc`，由 `ArcHotswapPlugin.onAgentInit` 注册，核心不持有 Arc 知识；`ReflectionCacheManagerTest.testArcJsonInstanceEviction` 以真实 `arc.util.serialization.Json` 做终局断言与对照组（变异检查：注释掉 `map.remove` 该断言必挂） |
| Jackson `ObjectMapper` 实例级缓存驱逐器 | ✅ | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §4.3 | `ReflectionCacheManager.JacksonEvictor` | 全局 `TypeFactory.clearCache()` + 实例级 `_serializerCache` 与 `_cache` 刷新；彻底废除无效的 `Class.reflectionData` 反射侵入 |
| Gson `Gson` 实例级缓存驱逐器 | ✅ | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §4.3 | `ReflectionCacheManager.GsonEvictor` | 清除实例级 `typeTokenCache` 映射；`ReflectionCacheManagerTest.testGsonInstanceEviction` 验证通过 |

---

## 4. 演进路线与技术债务清单 (Roadmap & Technical Debt)

本节将尚未落地的目标设计规格与工程技术债务严格区分，分别建立优先级、前置依赖与验收目标。

### 4.1 目标能力演进规划 (Feature Roadmap & Architecture Evolution)

设计规格文档：[`docs/initfix/04-target-design.md`](initfix/04-target-design.md) 与各专题规范

> **优先级定义**：  
> - **P1 (High)**：下一个架构重构里程碑核心目标（阻断核心竞态、杜绝严重未就绪访问）；  
> - **P2 (Medium)**：近期演进计划，具备明确设计规格但无阻断性前置依赖，按批次推进；  
> - **P3 (Low / Long-term)**：长期探索与多编译器兼容矩阵扩展，视外部环境需求按需引入。

| 规划条目 | 优先级 | 规范章节 | 前置依赖 | 目标价值与关联问题 |
|:---|:---:|:---|:---|:---|
| **构造器尾部插桩** | P3 | [`04-target-design.md`](initfix/04-target-design.md) §4 | `InitFix` 离线切片提取与直线字节码生成器 (`AliasInterpreter`) | **当前契约下不再需要**：阶段 A 保留新构造器/`<clinit>`，窗口内新建的实例由新构造器直接完成初始化，不需要从切片反推「取值指令」。仅当将来要求阶段 A 的构造器也回退旧体（换取更强的中止语义）时才重新需要；`ConstructorTailInjector` 单测保留、不接线。 |
| **两阶段 Schema-First 重定义** | ✅（内部批次路径） | [`04-target-design.md`](initfix/04-target-design.md) §1 | 已接线：`RemoldAgent.applyTwoPhase` / `redefineStageA` + `InitFix.requiresTwoPhase` + `StageASkeleton.buildWithLiveConstructors` | 读穿窗口（风险 9）在内部批次路径上关闭，且**只在本批存在零值暴露字段时**触发，纯方法体热更不付第二次 redefine 的代价。代价仍然真实：两次 redefine 会带来连续两次全局 JIT 逆优化（`07-p05-probe-results.md` §11 的对照组）；残余通道见风险 9。验收：`TwoPhaseProbe` 72/0（含 50k 实例下的并发读线程红/绿对照）、`TwoPhaseGuardTest` 5/0、全量 JUnit 597/0 skipped（v0.2.6 发版验证，`./gradlew check`）。 |
| **PatchPlan 基线指纹校验** | P1 | [`04-target-design.md`](initfix/04-target-design.md) §2 | 离线分析器导出协议 | 校验 Target 当前内存字节码哈希与构建期基线一致性，防止类漂移热更造成严重字节码损坏 |
| **`@HotswapInit` T4 逃生口** | P1 | [`09-hotswapinit-design.md`](initfix/09-hotswapinit-design.md) §2～§4 | `InitFix.buildPatch` 候选集段；复用现有写入协议 | 让 T3 拒绝的初始化式（Kotlin `?.`/`?:`、读配置、跨类依赖）由用户提供值，不再永久停在类型默认值；只换值的来源，不换作用域与写入协议（与 `@RemoldReinit`、`@OnReload` 不重叠）。契约已定，实现未开始（§2 T4 行为 ⬜） |
| **非方法上下文显式三态作用域规范化** | P2 | [`03-cascading-pipeline.md`](topology/03-cascading-pipeline.md) §2 | ASM EnclosingMethod 属性增强解析 | 引入 `<initializer>`/`<clinit>`/`<init>` 显式三态标签，解决当前非方法作用域主要依靠 `orderIndex` 兜底导致的作用域偶发空指针错配（收敛风险 2） |
| **跨类批次拓扑排序** | P2 | [`04-target-design.md`](initfix/04-target-design.md) §5 | 跨类字段依赖图构建器 | 解决类 X 新增字段依赖类 Y 新增字段时的跨类多事务批次协调（当前仅支持单类内部拓扑排序） |
| **子类引用多重集独立正交维度** | P2 | [`08-decisions.md#d-anon-1`](topology/08-decisions.md#d-anon-1) | `AnonClassHasher` 哈希解耦 | 将子类引用建模为独立正交维度，解决 D-ANON-1 中子类改动对父类逐级向上传导的雪崩退化，收敛夹具 L/M 保守拒绝代价 |
| **Analyzer 独立库化** | P2 | [`04-target-design.md`](initfix/04-target-design.md) §3 | ASM 工具链解耦 | 分析器作为纯函数独立，支持在 Gradle 构建端或 PC 端离线分析，减小运行期探针开销与内存占用 |
| **增强重定义模式四个未覆盖盲区验证与防护** | P2 | [`08-decisions.md`](topology/08-decisions.md) 附录 A | 专用探针套件建设 | 覆盖 JIT 编译后内联代码对新布局的观察、`volatile`/`final` 复杂修饰符、继承体系跨层遮蔽、JBR 25 重复验证 |
| **JBR 运行时重塑与自重启引导器 (`Remold` / `remold-launcher`)** | P2 | 运行环境引导与启动规范 | `RemoldAgent.isEnhancedHotswapEnabled()` 探针 | 应用以普通 Java 启动时，由 Remold 探测 JVM 环境；若缺少增强重定义能力，自动下载/就绪 JBR 运行时，并通过 ProcessBuilder 蹦床机制带增强参数与热更核心重启宿主，实现开箱即用运行时重塑 |
| **ECJ 编译器矩阵支持** | P3 | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §4 | ECJ 构建环境夹具 | 针对 Eclipse 编译器的 switch table 实现（内部 `$SWITCH_TABLE$` 方法）与 EnclosingMethod 结构差异建立对齐与防护 |
| **新 javac 加旧 -target 交叉编译矩阵支持** | P3 | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §3 | CI 交叉编译任务配置 | 验证新版 javac 携带 `-target 8/11` 等产物的命名与属性表现 |
| **未知 class major 显式拒绝** | P3 | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §1 | 无 | 遇到高于当前 JVM 支持的 class major 版本时显式拒绝而非尝试解析，防止 ASM Crash |
| **槽位预留与侧表免漂移 (S2)** | P2 | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §3.3 | 基类字节码注入器 | 针对游戏高频基类预留 slot 或引入 `$extras` 侧表虚拟字段，实现基类新增字段物理布局完全零漂移 |
| **持有者内存原地覆写与使用方 C2 逆优化 (S3)** | P3 | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §3.4 | `OffsetBindingIndex.getReaders` | 利用 `staticFieldBase/Offset` 原地更新 static final 常量，并对 direct reader 执行 `retransformClasses` 触发 C2 evol_method 依赖链逆优化 |

#### 4.1.1 已完成项与候选清单 (Closed Items & Candidates)

| 条目 | 状态 | 优先级 | 落地位置 | 说明 |
|:---|:---:|:---:|:---|:---|
| ~~**匿名类 `{{ ... }}` / 存量字段初值重放（L1 第一版）**~~ | ✅ | — | `remold.ref.InitializerReplay` + `InitFix` + `HotswapBridge.KIND_CONDITIONAL_EXPECTED`；设计见 [`12-L1-existing-initializer-replay.md`](initfix/12-L1-existing-initializer-replay.md) | 默认关（`-Dremold.agent.init_drift.parents` 为空）；白名单父类匿名子类、非 final 基本类型、旧/新初值均为编译期常量时，按**旧初值条件 CAS** 写匿名类自身实例；运行期改过的值跳过；不写 `FieldLedger`；`@RemoldReinit` 优先。验收与变异检查见 §0.5。未覆盖：具名类已有字段、引用类型、非常量旧值、`final`，以及按匿名类计的堆枚举性能数据。 |
| ~~**Kotlin `apply {}` 静态持有者初值重放（A 档）**~~ | ✅ | — | `remold.ref.ApplyInitializerReplay` + `InitFix`（`applyFields`/`applyReplay$N`）+ `HotswapBridge.KIND_CONDITIONAL_EXPECTED`；设计见 [`13-kotlin-apply-replay.md`](initfix/13-kotlin-apply-replay.md) | 默认关（`-Dremold.agent.init_drift.apply_holders` 为空）；白名单持有者 `<clinit>` 直线形态、唯一 `NEW`/唯一 `PUTSTATIC dest`、目标字段基本类型/非 final/非 synthetic/非 `@RemoldReinit`、旧/新值均为编译期常量时，`GETSTATIC dest` 取回那一个对象后按**旧初值条件 CAS** 写；运行期改过的值跳过，dest null/类型不符 warn 跳过；不写 `FieldLedger`、不改 `patchGenerated`。验收与变异检查见 §0.6。未覆盖：Kotlin `var` setter、B 档（`<init>`）、C 档（方法体/局部）、引用类型、`final`、非常量、多静态字段共享同一对象。 |
| `probeAll` 聚合任务（Gradle 内置 JVM 探针，不进 `check`） | ✅ | — | `tests/build.gradle` | 显式 `./gradlew probeAll` 全绿：e2eProbe + DCEVM 套件（除 alignBaselineProbe，见任务说明）+ entity/shadow/class-identity/double-brace/retry/inject-conflict/fingerprint/redefineDynamic/pending-multi-loader/retransform-baseline/addField/initFix-fallback/initDrift-noise+perf 全部 PASS；`doLast` 列出外部容器探针脚本（Fabric C3/C5/mm、Forge C5/mm）及证据路径；外部脚本不在 probeAll 内启动。 |
| ~~**Forge/NeoForge `ClassInfo` 成员同步**~~ | ✅ | — | `plugin-forge` `ForgeClassInfoSync`；详见 §0.3 | 2026-11-07 完成 Forge 侧实现与真机 E2E；NeoForge 21.1 亦已红→绿（见下两行的已完成项与 §0.4） |
| ~~**NeoForge Mixin 端到端验证（MDK 探针）**~~ | ✅ | — | `tests/neoforge-mixin-probe/`；详见 §0.4 | NeoForge 21.1.265 dev `runServer` 红→绿（原生 `[[mixins]]`）；**生产 installer 启动路径与 26.x 代际仍开放**（见下两行） |
| **NeoForge 26.x / FML 10 代际适配** | ⬜ | P1 | `plugin-forge` `ModLauncherMixinBridge` / `ForgeMixinHotSwap`；FML 11.x 源码与 `net.neoforged.fml.startup.*` 入口 | NeoForge 26.x 已移除 ModLauncher（库清单无 `cpw.mods:*`，入口 `net.neoforged.fml.startup.Client`），现有跨 ClassLoader 定位逻辑不适用。验收：26.x dev `runServer` 上定位 Mixin 织入器、重织入、模板热更、`[[mixins]]` 注册各一条真机证据 |
| **NeoForge 生产 installer 启动路径验证** | ⬜ | P2 | 探针 | dev 运行（ModDevGradle 目录 classpath）已证；生产 installer（`unix_args.txt`、模块层/named module 归属可能不同）未证——JPMS `--add-opens` 需求必须在生产路径重测 |

> **架构指引（v0.2.5 发版备忘，仅文档）**：后续若在命名模块层（如 `MC-BOOTSTRAP`）真的触发模块封装限制，**优先**由 Agent 自身在运行期用 `Instrumentation.redefineModule(ClassInfo.class.getModule(), extraOpens, …)` 补 opens，把命令行 `--add-opens` 诊断降级为兜底手段；不要把它当作首要修复。落地前需要两个前提证据：(1) 目标 JVM（JBR 21/25）上 `redefineModule` 对该模块确实生效（本仓库已有的「运行期破壁」先例是 `PackageUnsealer` 经 `IMPL_LOOKUP`/VarHandle 解除 Package 密封，可作为同类手段的可行性参照，但不是 `redefineModule` 本身的证据）；(2) `redefineModule` 生效后 `ClassInfo` 深反射与 `reload` 全链路在真机上红→绿（不能只断言 `isOpen` 返回 true）。

### 4.2 技术债与死代码消除清单 (Tech Debt & Code Cleanup)

| 债务 / 清理项 | 优先级 | 影响位置 | 现状分析与消除方案 |
|:---|:---:|:---|:---|
| **`tier5Matches` 残余死字段清理** | P2 | `AnonClassAligner.AlignStats` / `recordTierMatch` | Tier 5（物理类名盲配）已彻底拔除，但统计结构体中仍残留 `tier5Matches` 字段、日志拼装与 `recordTierMatch` 的 `case 5` 分支；该分支**无任何调用者传入 5**，属可安全移除的死代码，需连同日志字段一并清理，避免误导维护者。 |
| **嵌套深度 strict 熔断缺少夹具** | P2 | `AnonClassAligner.align`（`maxLevel > 4`）/ `RemoldAgent.ANON_STRICT` | 该阈值是**诊断阈值而非能力边界**：非 strict 模式下仅 `warn`（层级推进本身与深度无关，真正的闸门是 `MAX_ANON_PER_HOST` 与 `ALIGN_TIMEOUT_MS`），strict 模式下抛 `AlignmentRejectedException`。缺的是「`maxLevel > 4` + strict → 宿主组原子拒绝」的负向夹具，判定逻辑本身无须改动。 |
| **JDK 8 raw-bits CAS 自动化 CI 差异验证缺失** | P2 | `.github/workflows/ci.yml` / `HotswapBridge` | 当前 CI 仅运行在 Temurin 25，无法区分 raw-bits 与旧实现；JDK 8 raw-bits CAS 为手工验证，非持续自动化覆盖；需在 CI 矩阵中增加 JDK 8 运行期任务以持续守护差异逻辑。 |
| **批次内按类重复全堆遍历** | P2 | `InitFix.collectInstancesForPatch` / `LibTool.getInstances` | 每个待补类各调用一次 `IterateOverInstancesOfClass`，而每次调用都是一次 safepoint（1M 匹配实例时 `HeapIterateOperation` 实测约 240 ms，见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §10.2）：批次里 N 个待补类 = N 次停世界。可用 `IterateThroughHeap` 一次走完、按类分发，把 N 次停顿压成 1 次。**但这是「N 次短停顿」换「1 次长停顿」**：单遍历没有 VM 内的 klass 过滤，每个对象都要进 native 回调，最大单次停顿大概率更差；对延迟敏感的场景未必划算。做之前必须同时测总停顿与最大单次停顿，并先统计真实批次类数 N（N 很小时很可能不值得）。**另有一条已固定的语义约束**：`getInstances(P)` 含子类实例、`getInstances(C)` 不含父类实例（`remold.LibToolTest#heapScanIncludesSubclassesButNotSuperclasses`，见 [`08-test-audit.md`](initfix/08-test-audit.md) §3.1），单遍历按类分发时必须复现这个**不对称**，而不是简单按 klass 精确匹配。 |
| **每次 redefine 会大范围失效已编译代码** | P2 | 实测见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §11 | 对照组实测：redefine 一个**无关类**（无论只改方法体还是新增字段），另一个类的 C2 版本同样在窗口内被 `made not entrant`。意味着每次热更后进程可能大范围回退到解释/C1 执行、出现预热回落。归因未定（可能是 DCEVM 的全局失效策略），但成本真实存在；需要真实场景测一次热更后的吞吐恢复曲线。该成本按 redefine **次数**计，因此两阶段的两次 redefine 会把回落代价翻倍（见 §4.1 两阶段条目）。 |
| **真实批次规模 N 未知** | P2 | `RemoldAgent`（`HotswapBatchListener` 已暴露批次边界）/ `InitFix.applyPatch`（已记 `count=N` 与 `elapsed`） | 「单遍历按类分发」是否值得，取决于真实热更的批次类数 N 与每类实例数，而这两个量目前只有合成数据。观测手段已具备：批次边界走 `HotswapBatchListener`，每类实例数与耗时由 `applyPatch` 的日志给出；需要的是**几天真实热更的样本**，而不是再加代码。 |

> 关于 `AnonClassHasher.MAX_DEPTH`：该类注释已明确其为**永不生效的历史遗留常量**（`hash` 不递归自调用，三处调用点全传 `depth = 0`，`DeepNestProbe` 实测 depth 8 仍能正确对齐）。保留它是为了不动公开签名 `hash(..., int depth)`，**不应再作为「嵌套深度上限」引用，也不应作为技术债重复登记**。

---

## 5. 已知风险、残余盲区与折衷清单 (Known Residual Risks & Limitations)

本节系统记录热更新基础设施中被评审确认、因工程代价或架构边界有意保留的已知残余风险，统一按六段式元结构规范登记：

### 风险 1：实例存活判定 TOCTOU 竞态窗口
- **风险描述与底层机制**：从堆遍历（`LibTool.getInstances`）判定“某类无存活实例”到真正执行 `redefineClasses` 生效之间，存在纳秒至微秒级的时间窗口。
- **触发条件**：应用工作线程在此窗口内并发执行 `new Foo()`。该新实例在重定义生效前分配，持有旧字段布局；重定义后访问新增字段将读取默认零值。
- **当前缓解措施**：
  1. 重定义全程持有 `HOTSWAP_LOCK` 全局互斥锁；
  2. 游戏通常在主循环安全点或调试暂停期触发热更；
  3. 若类已存在已知存活实例，直接进入 InitFix 逐实例补丁队列，不依赖“零实例豁免”。
- **根治与关闭条件**：落地两阶段重定义（内部批次路径已落地，见 §0.7）；构造器尾部插桩在当前两阶段契约下不再需要（见 §4.1）；或在 HotSwap 期间通过 JVM TI 暂停全部应用线程（代价过高，目前维持极窄竞态记录）。
- **关联设计规范**：[`docs/topology/07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1.3。
- **关联架构决策**：无（属并发系统已知 TOCTOU 折衷）。

### 风险 2：SwitchMap 类的偶然作用域安全
- **风险描述与底层机制**：编译器生成的枚举 switch 映射类（如 `Foo$1`）未被特殊排除且未原名直通。它之所以在实测中未与其它匿名类发生误配，是因为业务匿名类的宿主方法回退解析为 `<init>`，而 SwitchMap 无 `NEW` 点其 `outerMethod` 保持为 `null`，两侧 scope 不一致导致 Tier 4 匹配天然失败。
- **触发条件**：若旧代码中存在宿主方法同样无法追溯（退化为 `outerMethod == null`）、且基类同样为 `Object`、未实现接口的业务匿名类，两者可能在 Tier 4 被误判为同一作用域并发生错配。
- **当前缓解措施**：
  1. **通用规则的偶然防护**：Tier 4 通用的基类与接口完全一致校验规则，恰好覆盖了绝大多数 SwitchMap 误配路径（SwitchMap 基类为 Object 且无接口，而业务匿名类几乎均实现特定接口或抽象基类）。这不是专门为 SwitchMap 添加的规则，但客观上构成了首道防线；
  2. **合成捕获字段布局门拦截**：SwitchMap 类通常无捕获字段，若发生实例字段变动会被 `LayoutGate` 拦截；
  3. **专测夹具持续断言**：专用测试夹具 `SwitchMapAlignTest` 在 javac 21 真实产物下持续断言未发生篡夺。
- **根治与关闭条件**：引入 SwitchMap 特异性特征识别（`Synthetic` + 静态 `int[]` 字段 + 宿主类前缀），将其显式纳入保留名域并排斥在匿名类候选集之外；或推进 §4.1“非方法显式三态作用域规范化”。
- **关联设计规范**：[`docs/topology/04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §1。
- **关联架构决策**：[`docs/topology/08-decisions.md#d-anon-3`](topology/08-decisions.md#d-anon-3)。

### 风险 3：`access$NNN` 跨编译编号重排风险
- **风险描述与底层机制**：javac 编译内部类访问外部私有成员时会生成合成静态桥方法（如 `access$000`、`access$100`）。为防止外部类对合成方法的调用点失效并抛出 `NoSuchMethodError`，系统采取 `access$` 保名不改名策略（D-ANON-5）。
- **触发条件**：若代码修改导致外部类私有访问发生增删，javac 重新编译后合成访问器的编号可能发生置换（例如原来的 `access$000` 变成 `access$100`，新私有访问占用了 `access$000`），从而导致旧字节码调用错误的访问器方法。
- **当前缓解措施**：
  1. 现代 Java 11+（Nestmate 特性）已原生消除绝大多数 `access$` 桥方法；
  2. 宿主类与内部类在同一重定义批次中整体更新，消除跨版本调用不匹配。
- **根治与关闭条件**：
  - **短期/项目内可达闭环**：在维持 `access$` 保名的前提下，在 `AnonClassAligner`/`ClassDiffUtil` 引入“访问器编号 -> 被访问目标语义指纹（包含目标成员名与描述符）”映射，当无法证明新旧编号目标一致时，降级为宿主组原子拒绝，杜绝调用错配；
  - **长期/外部环境演进**：当未来上游运行环境与构建矩阵彻底剥离 Java 8 支持并全面提升至 Java 11+（Nestmate 原生无桥方法）时，该问题自然淘汰。
- **关联设计规范**：[`docs/topology/01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) §2。
- **关联架构决策**：[`docs/topology/08-decisions.md#d-anon-5`](topology/08-decisions.md#d-anon-5)。

### 风险 4：InitFix Kotlin 复杂行为结论的验证覆盖
- **风险描述与底层机制**：Kotlin 编译器的代码生成逻辑与 Java javac 存在显著差异（如属性委托、内联函数协变重整、伴生对象静态桥等）。
- **触发条件**：新增字段涉及复杂的 Kotlin 语法糖（例如委托属性 `by lazy`、包含外部局部捕获的内联 lambda 等）。
- **当前缓解措施**：
  1. Kotlin `object` 单例与 `trim`/`trimStart`/`trimEnd` 家族已在 `ktfixV1`/`ktfixV2` 独立 source set 中经过真实 `kotlinc` 产物编译与 `InitFixOracle` 运行期检验；
  2. 安全门默认安全优先（Fail-Closed）：识别不可变源字段失败或存在黑名单方法调用时坚决拒绝并记入台账。
- **根治与关闭条件**：构建覆盖 Kotlin 委托属性、协程状态机与内联高阶函数的全量真实 `kotlinc` 测试夹具套件。
- **关联设计规范**：[`docs/initfix/01-safety-gate.md`](initfix/01-safety-gate.md) §2.1。
- **关联架构决策**：无（属 Kotlin 编译器差异适配）。

### 风险 5：同名局部类止血门边界（唯一局部类内部嵌套匿名类位移）
- **风险描述与底层机制**：当前 `LocalClassGuard` 止血门依赖 `(owner, innerName)` 同名计数 >= 2 判定编号漂移并触发宿主整族熔断。
- **触发条件**：宿主方法内仅存在一个具名局部类（计数为 1，止血门不熔断），但该局部类内部包含多个嵌套匿名类；若该局部类自身在源码中发生位移，其内部嵌套匿名类因命名含字母被 `isAnonymousClassName` 排除而原名直通，可能导致局部类内部匿名类发生槽位篡夺。
- **当前缓解措施**：
  1. `LayoutGate` 能够阻断捕获字段或显式字段发生结构性变动的非法重定义；
  2. 日常编码中极少出现局部类内部深度嵌套匿名类且局部类频繁增删的形态。
- **根治与关闭条件**：落地路线图 §4.1“同名局部类完整拓扑对齐”，将局部类内部嵌套匿名类同样递归纳入拓扑对齐引擎。
- **关联设计规范**：[`docs/topology/07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §2.2。
- **关联架构决策**：无。

### 风险 6：并发实例化缝隙
- **风险描述与底层机制**：存活实例的枚举若发生在重定义**之前**，「枚举完成 → 重定义生效」之间新建的实例就不在集合里；重定义后这些实例的新增字段停在类型默认值，且因为它们不再出现在后续 Diff 中，永远不会被补。
- **触发条件**：热更新期间应用线程仍在分配对象（游戏主循环的常态）。
- **当前缓解措施**：**枚举已后置到重定义生效之后**（`InitFix.applyPatch` → `collectInstancesForPatch`，见 [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §2）。此后不可能再产生旧布局实例，枚举天然完备；枚举期间新建的实例由新构造器初始化，条件 CAS 对它们是无害的空操作。回归断言 `InitFixSnapshotTimingTest`（先红后绿）。
- **残余边界（已关闭）**：两阶段路径下阶段 A 保留新构造器/`<clinit>`（`StageASkeleton.buildWithLiveConstructors`），阶段 A 到阶段 C 之间新建的实例由新构造器直接完成初始化，不再停在默认值；`StageASkeletonTest` 的窗口实例用例（新构造器初始化、旧体不回退到新字段）守卫该点。构造器尾部插桩在当前契约下不再需要（见 §4.1）。
- **根治与关闭条件**：已由两阶段路径的「构造器保留新体」达成（[`04-target-design.md`](initfix/04-target-design.md) §1；原 §4 构造器尾部插桩方案在当前契约下不接线）；外部 IDE/JDWP 与 `redefineDynamic` 通道的同类窗口见风险 9。
- **关联设计规范**：[`docs/initfix/03-runtime-driver.md`](initfix/03-runtime-driver.md) §2、[`docs/initfix/04-target-design.md`](initfix/04-target-design.md) §4。
- **关联架构决策**：无。

### 风险 7：InstanceTracker 字节码回退可靠性局限
- **风险描述与底层机制**：在无 JVMTI native 环境（如 Android 或纯 Java agent 回退）下，系统退化为基于弱引用表与构造器插桩的 `InstanceTracker`。
- **触发条件**：垃圾回收频繁触发导致弱引用被清理，或者实例通过非受控通道（如 `Unsafe.allocateInstance`、反序列化、未插桩的类加载器）创建。
- **当前缓解措施**：
  1. 桌面端开发环境默认启用 C++ 底座 `LibTool.getInstances`，Native JVMTI 为绝对优先通道；
  2. 回退模式下触发警告，提示开发者存在实例漏检风险。
- **根治与关闭条件**：优先保障 Native JVMTI 运行期环境；为纯 Java 回退通道补充强弱引用分层感知与反序列化挂钩。
- **关联设计规范**：[`docs/initfix/05-jvmti-heap.md`](initfix/05-jvmti-heap.md)。
- **关联架构决策**：无。

### 风险 8：JBR 字段布局探针实验局限（已闭环收敛）
- **风险描述与底层机制**：增强类重定义下物理内存布局在 JIT 极端优化下的行为较为复杂；早前假设单类新增字段为纯尾部追加（H1）以及父类加字段不影响子类已被物理探针证伪。
- **触发条件**：新增字段填补空洞或基本类型聚类导致存量字段物理偏移漂移；JIT C2 激进常量折叠导致读取旧偏移；VarHandle 固化旧偏移错读相邻字段。
- **当前缓解措施**：
  1. `OffsetStabilityProbe`（47 项断言通过）全量实测了 DCEVM 字段布局规律、空洞填补、类型重排、继承体系漂移、C2 常量折叠以及访问器自愈边界；
  2. 落地 `LayoutPredictor`，在重定义前通过抛弃式加载器 clean-load 预测物理偏移（与 DCEVM 重定义后 100% 吻合）；
  3. 落地 `SuperclassDriftGate`，阻断存在偏移持有者的 `LAYOUT_DRIFT`、`SUPERCLASS_FIELD_DRIFT` 以及 `DELETED_FIELD_OFFSET_CONFLICT`；
  4. 落地 `populateNonJdkSuperclasses` 预注入整条非 JDK 祖先链，防御 package-private 运行时包隔离。
- **根治与关闭条件**：已通过 P0/P1 的 `LayoutPredictor` 与 `SuperclassDriftGate` 达成事前确定性拦截；未来探索 S2 槽位预留或 S3 使用方 C2 逆优化以进一步放宽阻断。
- **关联设计规范**：[`docs/offset/01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md)。
- **关联架构决策**：[`docs/topology/08-decisions.md`](topology/08-decisions.md) 附录 A。

### 风险 9：重定义生效到补丁完成之间的读穿窗口
- **风险描述与底层机制**：`redefineClasses` 返回后新方法体即可执行，而新增字段要等 `InitFix.applyPatch` 枚举完堆才写入。窗口内的读取拿到类型默认值（引用类型 `null`、数值类型 `0`），不抛异常也不打日志；引用类型的下游解引用直接 NPE（真实渲染/逻辑线程在第 4 行日志级别即可观察到）。
- **已关闭的路径**：内部批次路径（文件监听 → `processChanges`）已由两阶段 Schema-First 重定义关闭（见 §0.7、路线图 §4.1）。当本批存在真正会写的「新增/类型变更字段」时：阶段 A 提交新结构 + 旧方法体（构造器/`<clinit>` 保留新体），旧方法体在编译期不引用新增字段，因而窗口内不可能读穿；阶段 B 完成补丁后再提交阶段 C 的新方法体。只在本批存在零值暴露字段时触发，纯方法体热更不付出第二次 redefine 的代价。证据：`TwoPhaseProbe` 72/0 的 50k 实例并发读线程红/绿对照（关两阶段稳定 NPE，开两阶段零 NPE）已实测。
- **仍敞开的路径（残余）**：
  - **外部 IDE/JDWP 重定义通道**：JVM 先按新字节码完成重定义，Remold 只能在 `afterRedefineExternal` 里补，窗口仍在；
  - **`redefineDynamic` 单类通道**：同样先提交完整新字节码再补；
  - **补丁被安全门拒绝的字段**：没有补丁就没有可等待的值，两阶段无法代偿，语义仍是「重启」；
  - **阶段 B 完成前的残余读取**：新构造器若读取本类新增**静态**字段、或读取同批其它类尚未补齐的字段，仍可能拿到默认值（跨类拓扑排序尚未接线，见路线图）。
- **窗口量级**（仅对仍敞开的路径有参考意义；实测拆分见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §10）：
  - 枚举成本 = **无关对象约 8 ns/个**（全堆遍历固定项）+ **匹配实例约 0.3~1 µs/个**（打 tag 与物化数组）；
  - 极端场景（目标类自身 100 万实例）：`applyPatch` 约 1551 ms，其中 CAS 仅 83 ms、弱引用往返 389 ms；
  - 现实场景（堆里几百万对象、目标类只有几百个实例）：实测 100 个目标 + 100 万无关对象仅 8 ms，窗口在**几十毫秒**量级；
  - **随批次线性放大的项**：`getInstances` 按类调用，批次里 N 个待补类 = N 次全堆遍历；
  - **暴露时间要扣掉 STW 段**：实测（M6）每次 `getInstances` 是一次 safepoint，1M 匹配实例时 `HeapIterateOperation` 单次 216~242 ms；停世界期间业务线程读不到未补字段。心跳吞吐只有自由速率的 67%，是「一段 STW + 其余阶段并发」的混合，不是全程争抢。
- **触发条件**：窗口内有线程执行读取新增字段的代码——本类新方法体、同批重定义的其它类、反射/序列化路径、任意业务线程。
- **缓解措施**：两阶段路径已由编排消除；残余路径上 `HOTSWAP_LOCK` 只互斥热更调度与事务处理、**不阻塞应用线程读**，因此不构成防护；T0 零值等价字段不受影响（默认值即正确值）；`PatchReport` 与条件 CAS 跳过计数可在事后定位“这次写了哪些字段”，但无法预防窗口内的读。
- **根治与关闭条件**：把两阶段编排接到外部重定义通道与 `redefineDynamic`（两者都受 JVM 先完成后置回调的约束，需要在 transformer 里先把新字节改为阶段 A 骨架、再在补丁完成后主动触发阶段 C），并补齐跨类批次拓扑排序。当前这两条属已知残余。
- **关联设计规范**：[`docs/initfix/04-target-design.md`](initfix/04-target-design.md) §1、[`docs/initfix/03-runtime-driver.md`](initfix/03-runtime-driver.md) §2。
- **关联架构决策**：无。

### 风险 10：新增字段参与 hashCode/equals 后哈希容器失效
- **风险描述与底层机制**：热更把新字段补进存量实例后，若 `hashCode()` / `equals()` 改为依赖该字段，已放进 `HashMap` / `HashSet` / `ConcurrentHashMap` 的实例其哈希值与桶位置不再匹配；`get` / `contains` / `remove` 会静默失效——不抛异常，只是查不到。补丁只负责写入字段值，不感知任何容器。
- **触发条件**：新增字段被 `hashCode()` 或 `equals()` 引用，且该类实例已被放入哈希容器。
- **当前缓解措施**：分析期静态检测 —— `HashSemantics.findDependencies` 在**本类内建调用图**（从 `hashCode()` / `equals()` / `compareTo()` 出发，沿同类 `invoke*` 求可达方法集），命中对本轮新增实例字段的读取时，`InitFix.transform` 经统一出口发 `warn`（`[HASH-SEMANTICS]`）。建调用图是必要的：Lombok `@EqualsAndHashCode` 生成的代码优先调用 getter（字节码是 `invokevirtual getId()`），手写的也常转调私有 `key()` / `computeHash()`，只看直接 `GETFIELD` 会把这两类常见写法全部漏掉。`HashSemanticsTest` 11 用例（先红后绿）。
- **语义取舍**：当前**只告警不拒绝**。注意「拒绝补丁会让新代码读到 0」只在「拒绝的是补丁、重定义照常进行」时成立；若 reject 指拒绝整个热更，新代码根本不会上线，那是另一种取舍。**严格模式尚未实现**——它需要 `RemoldAgent` 提供整批中止入口（`InitFix.transform` 无法中止批次），属于驱动接入阶段的工作。
- **残余边界**：只沿**同类**调用（跨类调用不跟进，否则误报面会扩到整条依赖链）；getter 是否被覆写不区分，一律保守命中，宁可多告警。**两处已知漏报**：①**子类**——**触发条件**是「新增字段声明在父类 P、被热更的是 P、且某个已加载子类 C 的 `hashCode`/`equals`/`compareTo` 读取了该字段」；此时 `getfield` 的 owner 是 C，而 `transform` 只扫 P 自己的方法，会漏。覆盖办法是对被热更类的已加载子类各扫一遍（未做）。②**跨类读取**——`Key.hashCode()` 调 `inner.getId()`（`inner` 是被热更类的实例）扫不到，要建跨类调用图，成本高，暂列为已知边界不实现。
- **根治与关闭条件**：VM 层无法根治（容器由用户代码持有）。可行方向是在分析期扫描新类的 `hashCode()` / `equals()`，一旦发现读取本轮新增字段就**显式拒绝或强告警**，由用户重建容器或重启。
- **关联设计规范**：[`docs/initfix/01-safety-gate.md`](initfix/01-safety-gate.md) §2。
- **关联架构决策**：无。

### 风险 11：方法删除原本没有门禁，现已与字段删除共用"批次外读者为空"判据（已收口）
- **风险描述与底层机制**：`ClassDiffUtil` 会算出 `removedMethods`，但它只用于日志摘要（`toString`），原本**没有任何门消费它**，`LayoutGate` 只看字段。删掉一个方法后，批次外的调用者会在执行到调用点时出事。实测（[`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §15，`P05Probe` M8）表明失败形态有**三种**，比字段多一类：
  1. **`NoSuchMethodError`**——整条继承链都没有同签名方法（M8c）；
  2. **`AbstractMethodError`**——只有接口声明、没有实现（M8b）；
  3. **分派转移**——祖先有实现，**静默接住**，不抛任何异常（M8a：`viaI`/`viaSuper`/`viaA` 三条路径全部变成 `Super.m`）。
  第三种最危险，因为热更"成功"、日志干净、行为已经变了。
- **机制差异（与字段的根本区别）**：字段只有**向上解析**（JVMS 5.4.3.2），引用 owner 必须是被删类的后代；实例方法还有**向下分派**（JVMS 5.4.6），receiver 的实际类型决定执行谁，因此 owner 可以是 `A` 的**任何祖先**。实测 `viaI(I)` → `InterfaceMethodref I.m`、`viaSuper(Super)` → `Methodref Super.m`，**从不出现 `A.m`**——只按 `declaring == A` 归属的判据对它们完全失明。连 `Methodref A.m` 本身也不抛错：解析会继续向上找到 `Super.m`。
- **触发条件**：被删方法有批次外调用者。正常全量重建下不会发生（删方法必然让调用者一起改）。成因见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §13.5（预编译依赖、批次撕裂；"增量编译未传播"是弱成因）。
- **当前缓解措施**：`decideLayout` 对"本轮有方法被删"的类单独判，与字段共用"批次外读者为空"的证明：
  - 扫描完成且无 finding → 放行；
  - 有 `hits` / `abstractMissing` / **静态性翻转（ICCE）** → 拒绝，整组移出本批；
  - **`dispatchShifted`（回退到祖先实现或接口 default）不拒绝**，只记日志——那是删掉 Override 的
    本意，零异常，读者源码不用改（[`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §16.4）；
  - **扫描未完成 → 拒绝**（方法删除没有存活实例判据可以退，它的影响与"当前有没有实例"正交）。
  判据实现在 `RemovedMemberReaders`：实例方法走 `isSubtype` + `selectMethod`（JVMS 5.4.6，含 maximally-specific 规则），静态/private/`<init>` 沿用向上解析。接线点在 `RemoldAgent.decideLayout`，且**必须在 `compatible()` 早退之前**——本轮可能只删了方法、字段一个都没动。
- **已知边界**：扫描范围只覆盖工程内类（JDK 与第三方 jar 不在范围内，见风险 12）；`invokespecial` 的 `super.m()` 无法与 `invokevirtual` 区分（常量池都记 tag 10），按"可能分派"处理，方向偏保守；pre-11 的 `access$` 合成访问器未折算（编号顺移会造成静默错读，见风险 12 第二层机制）。
- **关联设计规范**：[`docs/topology/07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1。
- **关联架构决策**：无。

### 风险 12：`WAIVE` 的"无存活实例"判据原本对字段删除不成立（主判据已收口）
- **风险描述与底层机制**：`decideLayout` 在不兼容但 `liveness == NONE` 且不涉及静态字段时返回 `WAIVE`（放行），理由写在代码注释里——"没有状态会被错读；新实例走新构造器、初始化正确"。这条理由**只对新增字段成立**。删除字段的风险不是"已有实例被错读"，而是**未来的实例被陈旧代码读**：`A` 被放行重定义后字段消失，任何仍持有 `GETFIELD A.x` 的已加载类，在**之后**新建的 `A` 实例上执行时抛 `NoSuchFieldError`。这与热更时刻有没有存活实例无关。
- **触发条件（修复前）**：`A` 的字段被删除且热更时 `A` 无存活实例（旧逻辑 `WAIVE` 放行）→ 之后有批次外代码读取该字段。批次外代码的成因见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §13.5（预编译依赖、批次撕裂；"增量编译未传播"是弱成因）。
- **当前缓解措施**：`WAIVE` 分支不再适用于**删除**——`decideLayout` 对 `REMOVED_FIELD` 一律走"批次外读者为空"：证明无读者 → `WAIVE_NO_READERS`；有悬空 / 抽象方法缺失 / **静态性翻转** → `REJECT`。**扫描未完成时同样拒绝**（不退回 liveness）：liveness 回答的是"现在有没有实例会被错读"，而删除的风险是"之后新建的实例被陈旧代码读"，两者正交——退回 `WAIVE` 就等于把"没扫成"读成"没读者"。`WAIVE` 现在只留给**改类型 / 改静态性**。**同静态性的改指向（回退到父类同名字段）不算读者**，只记日志——旧字节码本来就能正确解析，全量重编也一样（§16.4）。编译期常量**放行**：内联的读者运行期不解析该字段，未内联的会被 `hits` 抓到（§16.1）。
- **第二层机制（pre-11 合成访问器，实测）**：javac 8 的 `access$NNN` 桥按成员序号命名，删掉一个 private 字段会让后面的成员**顶上前面的编号**。实测两个 private 字段 `x`/`y`：删 `x` 后 `access$000` 仍在、但方法体从 `getfield x` 变成 `getfield y`。于是陈旧读者两条调用的失败形态不同——调 `access$100` 得 `NoSuchMethodError`（桥被删，归风险 11 足够），调 `access$000` 则**静默拿到 `y` 的值**（名字与描述符都没变，diff 里看不到删除，只看得到"方法体改了"）。所以"归到风险 11"只覆盖一半，漏掉的正是静默错读。判据必须落在"**合成访问器的旧目标**"上：`access$*` 的方法体目标变了，等价于旧目标被删，要对旧目标做读者扫描。Java 11+ 的 nestmate 产物没有这层桥，直接 `GETFIELD`，不存在此问题。
- **根治与关闭条件**：`WAIVE` 的 liveness 代理对删除问错了问题——它回答"**现在**有没有实例会被错读"，而删除要问"**批次落地后**还有没有代码会读"。修法**不是**"`REMOVED_FIELD` 不适用 `WAIVE`"（那会误杀两类安全删除：删 `private` 字段的读者被约束在 nest 内、正常连带重编的批次里读者的新字节码不再读该字段），而是把删除的判据换成**批次外读者为空**：对每个已加载类，批次内的扫**新**字节码、批次外的扫**当前**字节码，命中 `Fieldref` 即拒绝。**匹配 owner 时必须沿继承树展开，并按 JVMS 5.4.3.2 判"解析是否真的落在 `A` 上"**——`Fieldref` 的 owner 是编译期静态类型，不是声明类，删 `A.x` 时读者可能写成 `Field Sub.x`（实测见 §13.6.2），只匹配 `A` 会漏报；但子类自己声明了同名同描述的 `x` 时 `Sub/x` 解析到 `Sub.x`，**父接口里同名的字段还会抢在父类之前命中**（实测：`Sub extends A implements I`，从 `Sub` 解析 `x:I` 落到静态 `I.x` 而非 `A.x`），这两种情况都必须排除，否则误报。private 字段例外：JLS §6.6 规定 private 不被继承，`s.x` 编译不过、必须强转成 `A`，而强转后 owner 恒为 `A`，展开与截断都可省。**注意这个判据的完备性完全取决于扫描范围**——只扫已加载类会漏掉未加载的陈旧读者，那种"放行"给的是虚假安全感，比一律拒绝更糟；最低档位应覆盖到工程构建输出目录。做法、扫描范围三档与成本见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §13.6。该判据**已接线**（`decideLayout` 对 `REMOVED_FIELD` 用 `WAIVE_NO_READERS` / `REJECT`；扫描未完成时一律 `REJECT`，不退回存活实例判据），扫描范围取"已加载工程类（内存字节）+ 工程构建输出"，成本见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §14.6。**仍敞开的部分**：扫描范围不含 JDK 与第三方 jar（那里的陈旧读者会漏，属于"不完备但成本可接受"的已知取舍）；pre-11 的 `access$` 合成访问器未折算。
- **关联设计规范**：[`docs/topology/07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1。
- **关联架构决策**：无。

### 风险 13：JBR 25 增强重定义对畸形 `ClassDefinition` 直接崩溃 VM
- **风险描述与底层机制**：`Instrumentation.redefineClasses` 收到畸形/非法 `ClassDefinition`（例如截断的 class 文件、二进制名与目标类不匹配的字节）时，JBR 25.0.2 + `-XX:+AllowEnhancedClassRedefinition` 不是抛 `LinkageError`，而是在 `VM_EnhancedRedefineClasses::do_topological_class_sorting` 触发 `EXCEPTION_ACCESS_VIOLATION`，整个 JVM 终止。`RemoldAgent.applyRedefinitions` 没有对传入字节做格式/类名校验，批量与逐类两条路径都会把字节直接交给 JVM。
- **触发条件**：`applyRedefinitions` 的批量 `redefineClasses` 或逐类兜底收到畸形 `ClassDefinition`。本轮实测两种构造均触发：截断字节（`new byte[]{0x00}`）与“合法 class 文件但二进制名不匹配”。批量与逐类都触发过。
- **已观测证据**：JBR 25.0.2（`jbrsdk_jcef-25.0.2`）两次崩溃，`hs_err` 文件为 `tests/hs_err_pid20676.log`、`tests/hs_err_pid15436.log`（另有一次 `tests/hs_err_pid19664.log`）；栈顶 `V [jvm.dll+0x5fda20] VM_EnhancedRedefineClasses::do_topological_class_sorting+0x270`。这些日志在记录后已删除，未提交。
- **影响**：开发进程直接死亡，且没有 Java 层异常可供上层捕获/回滚；与“补丁生命周期”类问题不同，这是 VM 级故障。
- **当前处置**：本轮不修。回归探针 `InitFixFallbackProbe` 改为在 `Instrumentation` 边界注入失败，避免把 VM 崩溃当作可重复的测试路径。
- **候选关闭方向**：在 `applyRedefinitions` 入口校验 `ClassDefinition` 的 class 名与字节可解析性；或把 redefine 调用隔离到可重启的辅助进程（代价高）。需先确认 JBR 25 的触发边界（是否只在批量排序阶段）。
- **关联设计规范**：无。
- **关联架构决策**：无。

### 风险 14：继承字段的偏移绑定在主路径已闭环，但预测失败后的兜底仍可能漏检
- **风险描述与底层机制**：偏移绑定可以指向**子类从父类继承来的字段**（例如 `Host$1.property` 实际声明在 `XXX`）。若父类新增实例字段，该字段物理偏移改变；漂移检测若只遍历子类**自身声明**的字段，`OffsetBindingIndex` 中的该绑定永远不会被查询，父类 redefine 会照常执行。
- **触发条件**：`OffsetBindingIndex` 中存在 `targetClass = 子类`、`targetField = 继承字段` 的绑定；父类新增实例字段并进入同一轮 redefine。
- **已观测证据（修复前）**：`DoubleBraceProbe` E2（JBR 25.0.2）：`XXX` 新增 `added` 前后，`Host$1.property` 的 `Unsafe.objectFieldOffset` 从 **16** 变为 **20**；`offsetAfter` 读 `property=1`，`offsetBefore` 读到新字段 `added=9`，本轮没有 `LAYOUT-REJECT`/`SUPERCLASS_FIELD_DRIFT`，`XXX` 仍被重定义。
- **修复与关闭证据（146c46c，2026-11-06）**：`LayoutPredictor` 的子孙扫描改为 **declared + inherited**（沿 `getSuperclass()` 收集实例字段），预测出的 `DriftingField` 归属子类、字段名保留原名，`SuperclassDriftGate.checkLayoutDrift` 据此命中绑定。`DoubleBraceProbe` E2 由"未检测"翻为真断言：`XXX` 被拒、偏移保持 16、新旧偏移都读 `property=1`；E2-own / E3 仍拒绝，E1（无绑定）仍放行并由 InitFix 补 `added=9`；新增 `LayoutPredictorTest.noBindingStillPasses` 守卫。修复设计见 [`02-inherited-field-drift-fix.md`](offset/02-inherited-field-drift-fix.md)。
- **残余边界**：预测器无法 clean-load 子孙类时，`checkSuperclassDrift` 的保守兜底仍只取 `getDeclaredInstanceFields`，继承字段绑定在该路径上依旧不可见；非严格模式下预测不完整的子孙类只按 `(类, "*")` 查未决动态持有者，不展开继承字段。需在严格模式（`-Dremold.agent.offset_strict=true`）下使用，或补正向回归后再宣称完全闭环。
- **关联设计规范**：[`docs/offset/01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §1.1、[`docs/offset/02-inherited-field-drift-fix.md`](offset/02-inherited-field-drift-fix.md)。
- **关联架构决策**：无。

### 风险 15：`pendingAlignedClasses` 只有类名键，多 ClassLoader 串用（P3）
- **风险描述与底层机制**：`AnnotationTransformer.pendingAlignedClasses` 是 `Map<String, byte[]>`，键只有类内部名/点分名，没有 ClassLoader 身份。`AlignmentTransaction.commit()` 对已加载的匿名类仍会保留 pending 条目；该类在宿主 Loader 中 redefine 时不会消费 pending（`classBeingRedefined != null`），条目会留到**另一个 ClassLoader 首次加载同名类**时被 `transform(..., classBeingRedefined=null, ...)` 消费。
- **触发条件**：Loader A 已加载 `Host$1`；对齐事务 commit（pending 留下）；A 的 `Host$1` redefine 成功；Loader B 首次加载同名 `Host$1`。
- **已观测证据**：`PendingAlignedMultiLoaderProbe`（提交 `3061b9c` 之后的修订版）：A 已加载 → commit → `inst.redefineClasses` 成功 → pending 仍在 → B 首次加载返回 `alignedMarker=true`，条目随后被 B 消费；断言标记为 `[KNOWN-BUG]`。
- **影响**：B 的类会拿到 A 宿主对齐后的字段布局/编号，可能与 B 的宿主结构不一致；P3（错误注入/错配，非必然崩溃）。
- **目标环境**：Forge + Mixin；以 Forge 实测的 ClassLoader 身份为准（Fabric 探针只能证明单 JVM 内的类名键冲突，不能替代 Forge 多 loader 实测）。
- **当前处置**：只记录，未修。
- **候选关闭方向**：pending 键改为 `(ClassLoader 身份, 类名)`，或 commit 时对已加载类不登记 pending；消费时校验 loader；补多 loader 回归。
- **关联设计规范**：`docs/topology/03-cascading-pipeline.md`。
- **关联架构决策**：无。

### 风险 16：`sourceBytecodeCache` 写入到回滚之间的并发窗口（P3）
- **风险描述与底层机制**：`processChanges` 不是 `synchronized`，`AnnotationTransformer.isNoOpExternalRedefine` / `oldBytecode` 读取 `sourceBytecodeCache` 时也不持有同一把锁。本轮源码在匿名类对齐后、门与 redefine 前就写入缓存；未生效类要等 `applyRedefinitions` 后才回滚。这段窗口内其他线程可能读到“尚未生效”的新源码。
- **触发条件**：`processChanges` 正在处理一批类（尤其对齐/布局门/重定义耗时较长）时，外部重定义通道或 `retransformLoaded()` 读取 `sourceBytecodeCache`。
- **后果**：外部重定义 no-op 判定可能把新源码当成已生效基线而跳过织入；旧侧基线可能取到未生效版本。是语义错判，不是内存竞争。
- **当前处置**：只记录，未升级为 staged；快照+回滚已缩短窗口，但未消除。证据：`SourceBytecodeCacheTest` 四路径 helper 测试、E3 端到端、变异检查（注释回滚调用仅 E3 变红）。
- **候选关闭方向**：按设计稿引入 `SourceRound` staged/commit，读取方只读已提交版本；或把 `processChanges` 与读取方统一到同一锁。
- **关联设计规范**：`docs/initfix/11-source-bytecode-cache.md` §9。
- **关联架构决策**：无。

### 风险 17：L0 不覆盖静态字段初值变化（P3）
- **风险描述**：`InitializerDrift` 只扫描 `<init>` 的实例字段 `PUTFIELD`；`<clinit>`/`PUTSTATIC` 不在 L0 范围。`static int s = 1` 改为 `2` 不会产生 `[INIT-DRIFT]`，存量静态值不会重置。
- **触发条件**：只改静态字段初值，且没有新增静态字段/`@RemoldReinit`。
- **当前处置**：设计上不覆盖（L0 只做实例字段可见性）；`InitializerDriftTest.staticFieldIsNotCovered` 明确断言为空，不实现静态检测。
- **候选方向**：L0 扩展 `<clinit>` 切片比较，或由 InitFix 静态补丁路径单独处理。
- **关联设计规范**：`docs/initfix/10-initializer-change-detection.md`。
- **关联架构决策**：无。

### 风险 18：L0 不覆盖 lambda 体变化（P3）
- **风险描述**：L0 只比较 `<init>` 中已有字段的写入切片；lambda 体变化（包括 lambda 前插导致的 `lambda$...$N` 重编号）不会产生 `[INIT-DRIFT]`。`InitializerDriftTest`/`L0-lambda` 端到端用例已验证：真实 `processChanges` 下 Host v1→v2、lambda 插入，`[INIT-DRIFT]` 新增 0。
- **触发条件**：只改 lambda 体或 lambda 顺序/编号，且没有已有字段初始化块变化。
- **当前处置**：设计上不覆盖（lambda 由 LambdaAligner 负责）；只记录，不实现。
- **候选方向**：如需可见性，单独比较 `invokedynamic` bootstrap 参数或 lambda 方法体；不要混入字段初值检测。
- **关联设计规范**：`docs/initfix/10-initializer-change-detection.md`。
- **关联架构决策**：无。

### 风险 19：实体探针在 Forge + Mixin 织入后的目标类上抛 `ClassFormatError`（v0.2.4 已修复）
- **风险描述与底层机制**：`DynamicProfilerAPI.toggleEntityProbe` 对已加载类做 `retransformClasses`。当目标类同时是 Forge/ModLauncher 下的 Mixin 目标时，JVM 交给 transformer 的 `classfileBuffer` 已经是织入后的字节；`ProfilerTransformer` 在其上插桩后，JVM 校验抛 `java.lang.ClassFormatError`（栈：`InstrumentationImpl.retransformClasses0` ← `DynamicProfilerAPI.toggleEntityProbe`）。同一探针在 Fabric 的非 Mixin 目标上正常（C1C2 探针）；Fabric + Mixin 目标上的计数器尚未验证。
- **触发条件**：Forge（ModLauncher）环境 + 目标类为 Mixin 目标 + 调用 `toggleEntityProbe`（实体探针开关）。
- **非确定性观测（重要）**：同一份探针代码在同一 Forge/Mixin 目标上，一次运行抛 `ClassFormatError`，另一次运行计数器正常（`counter=1`）。因此当前**不宣称**该路径已验证；二分矩阵需要重复运行才能定性。
- **后果**：异常穿到调用方；探针注册状态可能残留（`ProfilerTransformer.targetMethods` 已加入但重转换失败）。实体探针在 Forge Mixin 目标上不稳定。
- **处置状态**：v0.2.3 先登记为已知问题（非确定性）；根因与修复已于 v0.2.4 落地（见下方"根因"与"修复"两条）。**残余**：CFE 与状态覆盖的因果仍为推断（未抓到 CFE 瞬间的 dump），Fabric 四格二分未运行；因此不再宣称"任何 Forge/Mixin 目标都稳定"，只声明已复现路径被修复。
- **问题 B 根因（已观测，2026-11-06，含 1.5 项对照实验）**：JBR 21.0.9（DCEVM）的 `redefineClasses` 会把**静态字段里的 `Class` 引用**改写成另一个同名 `Class` 对象，局部变量、`instance.getClass()`、`Class.forName` 仍返回 canonical 对象。**1.5 项实测边界**（`tests/fabric-mixin-probe/evidence/class-identity-jbr21.txt`，探针 `:tests:classIdentityProbe`）：
  - **普通实例字段**与**数组元素**中的 `Class` 引用**不会**被改写（`instanceFieldSame=true`、`arraySame=true`）；
  - **`ConcurrentHashMap` / `HashSet` 的 key 不会**被改写：redefine 前用 canonical 插入、redefine 后不重新 put，`map.get(canonical)` 仍命中、`map.get(shadow)` 为 null、`set.contains(canonical)=true`；
  - 被改写出来的影子对象是**旧版本镜像**：`shadow.getDeclaredFields()` 只有 `[a]`（canonical 为 `[a,b]`），`LibTool.getInstances(shadow)` 返回 **0**（canonical 返回 1），`retransformClasses(shadow)` 静默返回、不抛异常；
  - 直接 `Instrumentation.redefineClasses`（不经过 Remold 任何代码）同样复现；结构性与仅方法体 redefine 都复现。
  结论：风险不是“所有堆引用”，而是**长生命周期静态字段里的 `Class` 引用**（以及据此传入 JVMTI / `retransformClasses` / 探针目标表的路径）。`Map<Class,...>` 若 key 来自 canonical `getClass()`/`Class.forName` 则跨 redefine 安全；若 key 来自静态字段则可能落成影子对象。C5 探针的 `static Class<?> target` 正是后者，这解释了 Forge 探针计数器“0/时好时坏”。
- **根因（已观测，第 2 项二分，2026-11-07）**：`ProfilerTransformer` 是**单实例**（`DynamicProfilerAPI.transformer`），`transform` 把 `classReader/classWriter/targetMethodNames` 存在实例字段里，并在入口 `resetState()`。`ClassWriter.getCommonSuperClass` 在 COMPUTE_FRAMES 期间可能**加载类**，触发同一实例的**重入** `transform`；重入把外层 `classReader` 清空 → `NullPointerException: this.classReader is null`。Forge/ModLauncher 二分日志在 `d5327fe` 与 `6091057`、Mixin 目标与普通目标上**都**复现该 NPE（`build/bisect/evidence/forge-*.log`），证明与 C1 修复、Mixin 织入无关；间歇性 `ClassFormatError` 与同一状态被并发/重入覆盖一致（**推断**：未单独抓到 CFE 瞬间的 dump，修复后不再复现）。转储 `ProfilerTransformer` 输出（`-Dremold.profiler.dump.dir`，`evidence/profiler-dump-jbr21.txt`）在修复后：in=1871B/out=2057B、ASM 重复成员扫描 `dupMembers=[]`、`javap -v` 解析成功、advice 调用 `recordEntry/recordExit` 存在。
- **修复（已落地，全量回归通过）**：① `transform` 改为**全部局部状态**（`classReader/classWriter/targetMethodNames/anyProfiled` 局部化），单实例可重入/并发安全；`targetMethods` 改 `ConcurrentHashMap`；② 新增 `remold.util.ClassIdentity.canonicalize(Class<?>)`，在 `DynamicProfilerAPI.toggleEntityProbe`、`ProfilerTransformer` 目标表读写、`LibTool.getInstances` 入口归一化静态字段影子 `Class`（影子对象 `getDeclaredFields` 是旧版本、`getInstances=0`、`retransform` 静默空转）；③ `toggleEntityProbe` 返回 `boolean`，捕获 `Throwable`，按调用前快照回滚：enable 失败恢复原注册（空则删表项）、disable 失败重新登记（探针字节仍在）；④ 目标匹配增加 name+loader 与 `HierarchyTree` 名称回退，覆盖“影子基类 + 子类”形态。
- **回归证据**：`:tests:shadowClassProbe` 9/9（红→绿：修复前 `LibTool.getInstances(shadow)=0`、disable 后计数继续增长；变异检查撤销 canonicalize → 红）；`EntityProbeFailureRollbackTest` 2/2（撤销回滚 → 红）；Forge C5 修复后 `ENTITY-PROBE counter=1`、`RESULT PASS`、无 NPE/ClassFormatError。**二分矩阵（已归档 `tests/fabric-mixin-probe/evidence/bisect/`）**：Forge plain/Mixin × `d5327fe`/`6091057` 四格稳态 toggle 全部 `counter=1`、无 `ClassFormatError`；构造期早期 toggle 复现 `ProfilerTransformer` 单例可变字段 NPE（`classReader`/`targetMethodNames` 为 null，并发类加载触发）。Fabric 四格 **未运行**（时间上限，`SUMMARY.md` 标注）；无 CFE 瞬间 dump，CFE 与 H2 的因果仍为推断。
- **关联设计规范**：`docs/initfix/03-runtime-driver.md` §4（`@RemoldReinit` 与实体探针均属显式 opt-in 通道）。
- **关联架构决策**：无。

## 6. 框架集成插件与资源热更新 (Framework Plugins & Asset Hot-Reload)

插件 SPI 契约：[`api/src/remold/HotswapPlugin.java`](../api/src/remold/HotswapPlugin.java)

| 机制 / 条目 | 状态 | 落地位置 | 验收证据与已知边界 |
|:---|:---:|:---|:---|
| 资源变动事件分发 | ✅ | `RemoldAgent.isAssetFile` / `notifyAssetChanges` / `WatcherThread` | 递归注册监视目录；按扩展名判定贴图/字体/语言包/音频/着色器/配置；过滤隐藏与临时文件；`AssetReloaderTest` 覆盖判定与分发 |
| 贴图原位直刷（同尺寸） | ✅ | `plugin-arc` `TextureReloader.reloadTexture` Tier 1 | `Gl.texSubImage2D` 写入既有 rect，零材质重建；linear 过滤下先 `Pixmaps.bleed`；级联刷新地表与单位 `clipSize` |
| 贴图尺寸变化（Tier 2） | 🔶 | `TextureReloader.reloadTexture` Tier 2 | 清图集 Drawable 缓存、重载 `Tex`/`Styles`、刷内容图标；**region 的 UV 与宽高不随之更新**，画面可能拉伸或溢出，换尺寸应重新部署 |
| 运行期新增贴图 | 🔶 | `TextureReloader.registerNewSprite` | 独立 `Texture` + `Core.atlas.addRegion`；区域名按「重启后 Mindustry 会用的名字」推导（`sprites/` 加模组前缀、`sprites-override/` 裸名），归属模组靠同目录兄弟贴图反查，推不出即拒绝；独立纹理不在图集页内、不参与合批；`NewSpriteNamingTest` 4 用例含变异检查 |
| 字体 / 语言包重载 | 🔶 | `plugin-arc` `FontReloader` / `BundleReloader` | 已接入 `ArcAssetReloader` 分发；`BundleReloader` 按 `Mods.buildFiles` 的 locale 规则只写入匹配的父链 bundle（base 文件写根层，`bundle_<locale>` 写对应语言层），合并属性后回写 `UnlockableContent` 的 `localizedName`/`description`/`details`/`credit`，仅覆写仍等于合并前 bundle 解析值的字段（保留 `OreBlock` 等程序化派生名）；`FontReloader` 按文件名把 `font.woff` 路由到 `def`/`outline`、`icon.ttf` 路由到 `icon`/`iconLarge`，`monospace`/`logic`/`tech` 各自独立，未知文件名回退到 generator 路径匹配（覆盖 `font_jp` override）；`AssetReloaderTest` 覆盖缓存刷新、locale 路由、派生名保护、槽位映射与缺失文件安全用例；字体重载仍缺真实 UI 自动化覆盖 |
| 音效原位重载 | 🔶 | `plugin-arc` `SoundReloader` | 同时扫描 `mindustry.gen.Sounds` 静态字段与 `FileTree.loadedSounds`，按 `Sound.file` 绝对路径匹配后对同一实例执行 `stop`/`dispose`/`load` 并恢复控制参数；未播放的 lazy sound 延迟到下次播放；无匹配时告警需重启；缺真实音频设备下的自动化覆盖 |
| 音乐原位重载 | 🔶 | `plugin-arc` `MusicReloader` | 扫描 `mindustry.gen.Musics` 与 `FileTree.loadedMusic`，按 `Music.file` 匹配；先停止并释放旧 handle，再删除 `Music.load` 的同名同长度缓存并重载，避免长度不变时命中旧内容；播放中的曲目重载后从头继续，无匹配时告警需重启；缺真实音频设备下的自动化覆盖 |
| 内置着色器原位重编 | 🔶 | `plugin-arc` `ShaderReloader` | 按文件到 `Shaders` 静态字段的绑定重新编译，把新 program/句柄/uniform/attribute 表回写到原对象，保留 `CacheLayer`、`PlanetMesh` 等既有引用；编译失败保留旧程序；未知文件与模组自定义着色器仍提示重启；缺真实 GL 设备下的自动化覆盖 |
| 不可热更资源提示 | ✅ | `ArcAssetReloader.classify` | 模组/未知着色器、content JSON/HJSON、图集、普通数据文件不再静默忽略，统一输出“需要重启”的 warn；`AssetReloaderTest` 覆盖分类路由 |
| Mixin 重织入（普通类） | ✅ | `plugin-mixin` `MixinReWeaver` | 重定义前把源码级字节码交回 Mixin 织入，非目标类按数组同一性跳过拷贝；真实 Fabric 1.21.1 客户端已验收（新增/删除 `@Unique` 字段、后续 target 编辑）；`MixinRawSourcePriorityTest` 守住原始字节来源；CI 无自动 Fabric 客户端 E2E |
| Mixin 共享反射引擎（Fabric / Forge / NeoForge 配置差异） | ✅ | `core/src/remold/reflect/mixin/`（`MixinConfigDiffer`、`NewMixinRegistrar`、`MixinConfigContext`、`MixinReflection`；目标加载抑制器以 `Function`/`Consumer` 跨边界传递，不设 core 侧接口）；`plugin-mixin` 薄适配；`plugin-forge` 的 `ForgeMixinConfigDiff` + `ModLauncherClassTrackerSpoof` | core 新包纯 JDK + 反射，不引入 Mixin / ModLauncher 编译期依赖；`*.mixins.json` 删除/重新加入、删除 tombstone、新增 Mixin 登记、目标重织入回调共用一份引擎。Fabric 侧沿用 `FabricLauncherSpoof`；Forge/NeoForge 侧在 `prepare()` 极窄窗口内从 `ModLauncherClassTracker.loadedClasses` 同步摘除目标名，`try/finally` 恢复，字段缺失/类型不符时 fail-closed 提示重启。**真实 Forge 1.21.1 + NeoForge 21.1 E2E**：先跑原有 ClassInfo 两阶段（GREEN），再删除 `ci.mixins.json`/`neprobe.mixins.json` 中的模板 → hook 停止；重新加入并新增 `ConfigNewMixin` → 原 handler 恢复 + 新 handler 生效；两份证据分别为 `tests/fabric-mixin-probe/forge-classinfo/evidence/forge-1.21.1-configdiff-after-fix.txt`、`tests/neoforge-mixin-probe/evidence/neoforge-21.1-configdiff-after-fix.txt`。`MixinConfigDifferTest` 3/3（删除/重加/截断配置）、`ModLauncherClassTrackerSpoofTest` 3/3（摘除恢复/缺字段 fail-closed/空目标）、原有 `NewMixinRegistrarTest` 6/6 + `MixinConfigDiffTest` 3/3；全量 `check` 520/0 skipped。**未验证**：Forge/NeoForge 客户端（仅 dedicated server）、Mixin 升级后的内部字段变动（fail-closed 到重启）。 |
| Mixin 类自身变更 → 目标级联重织入 | ✅ | `plugin-mixin` `MixinHotSwap` + `MixinClassInfoSync` | `reload` **前**把新增字段/方法补进其复用的 `ClassInfo`（`reload` 的 conform 校验按此元数据解析新 `@Inject` handler；拒绝时回滚），再对已加载目标类走 `redefineDynamic`；真实 Fabric 1.21.1 客户端已验收（新增/删除字段、`TitleScreen` 重织入）；真实 Fabric 1.21.1 服务端已验收 `@Inject` 自演化三场景（新增/改目标方法/增删 handler，`tests/fabric-mixin-probe/evo/` + `evidence/inject-evolution.txt`）；`MixinClassInfoSyncTest`（Fabric 适配器降级与空令牌回滚，2/2）/ `DynamicLayoutWaiverTest`；`@Mixin` 模板类不再进入 `loadedClassesMap`（`NonLoadableClassTest`）。**新增 Mixin 类运行期登记（实验性）**：`MixinHotSwap` 在 `reload` 返回空目标时调用 `NewMixinRegistrar`，反射把新类补进 `MixinConfig.mixinClasses` 并执行 `MixinConfig.prepare` + `MixinInfo.validate()`；目标已加载时在 Fabric 上通过 `FabricLauncherSpoof` 把 `isClassLoaded(target)` 在 `prepare` 的极短窗口内临时置 false，构造完立即恢复，之后走 `redefineDynamic` 重织入。校验失败回滚；内部字段不匹配时 fail-closed 提示重启。`NewMixinRegistrarTest` 6/6（包匹配、只登记一次、校验失败完整回滚、缺 processor 降级、Mixin 内部结构契约）；`MixinConfigDiffTest` 3/3（数组解析、缺 key、截断配置 fail-closed）。**Mixin 配置差异（实验性）**：`onAssetsChanged` 收到 `*.mixins.json` 变更后，`MixinConfigDiff` 对比当前侧（common/client/server）声明；删除项从 `MixinConfig.mixins`/`mixinMapping`/全局去重集合移除并重织入目标，新增项复用 `NewMixinRegistrar` 登记。**真实 Fabric 1.21.1 KnotServer E2E**：目标未加载 → 登记后首次加载即织入（hook 计数 1，返回值保持）；目标已加载 → 登记 + 重织入后 hook 计数 1；从 config 删除 `RemovableMixin` → handler 方法消失、hook 停止；删除后再次热更该 Mixin 的 `.class` 也不会被重新登记；重新加入 → hook 恢复；证据 `tests/fabric-mixin-probe/evidence/newmixin-runtime-20261108.txt`。**真实客户端已观测**：IDE 客户端（KnotClient/JBR 21.0.9）两次删除/重新加入 `CreepMixin`，每次都出现 `Config diff` + `redefine`，重新加入后 hook 恢复；证据 `tests/fabric-mixin-probe/evidence/newmixin-client-20261108.txt`。**Forge / NeoForge 的配置差异见下方共享反射引擎行**。**未验证**：Mixin 升级后的内部字段变动（会自动退化为“需重启”）；客户端日志未覆盖“删除后立即刷苦力怕”的 hook 终局断言（该断言由 KnotServer 探针覆盖）。 |
| Forge / NeoForge 跨 ClassLoader Mixin 重织入 | 🔶 | `plugin-forge` `ModLauncherMixinBridge`、`ForgeMixinHotSwap` | 通过 `BytecodeTransformerBridge` 穿透 ModLauncher / NeoForge 类加载器反射定位 `IMixinTransformer` 织入重定义字节码；`ForgeMixinHotSwap` 处理 Mixin 模板类自身热更（`IMixinTransformer.reload` 刷新状态 + 级联重织入目标类），解决 Forge 与 Agent 间 ClassLoader 隔离；真实 Forge 1.21.1 与 NeoForge 21.1 dev 服务端已红→绿（§0.3/§0.4），**真实客户端与 NeoForge 生产 installer / 26.x 仍待验证** |
| Forge / NeoForge EventBus 监听器刷新 | 🔶 | `plugin-forge` `ForgeEventBusRefresher` | 监听批次完成事件，识别新增/移除的 `@SubscribeEvent` / `@EventBusSubscriber` 并自动在 `EVENT_BUS` 注销与重新登记；端到端需真实 Forge / NeoForge 客户端验证 |
| 标准 JVM 能力探测与降级 | ✅ | `RemoldAgent.probeEnhancedHotswap` / `mustSkipForMissingEnhancedRedefinition` / `degradeForMissingEnhancedRedefinition` | 厂商身份 + flag 文本双条件判定；结构变化类在无增强能力时摘批；`FORCE_STATIC_LAMBDA` 强制降级、`INIT_FIX` 刻意不降级；`EnhancedHotswapGateTest` 9 用例含变异检查 |

| 合成匿名类单调并集（v0.2.7 战役：故障 B 第三治本点） | ✅ | `MixinSyntheticSchema.renameCollisions`（同名异型字段重命名追加 + GETFIELD/PUTFIELD 改写）、`retainConstructors`（`<init>` 重载墓碑并集）、`MixinInnerClassHotSwap` 规则 3（`BLOCKED` + `MixinReWeaver` 中止宿主重定义） | **已观测**：`MixinSyntheticSchemaTest` 5 用例（缩容补齐/同 schema 不补/同名异型重命名+访问点改写/基线 `<init>` 保留/`schemaHash`）；真机（Fabric server + JBR 21.0.9 DCEVM）5 轮 R1~R4 `field-table-stable=true`、`RESULT PASS`。**边界**：客户端 `probeLogging=true` 下 R1 的 `(this,int,Entity)` 与 R4 的 `(this,Target)` 真实往返仍需前线复验；`AnonClassAligner` 侧的同名异型字段规则尚未接入（其配对以字段布局门为准） |
| 抑制签名锚点修正 + 清 GC/死代码（v0.2.7 战役：真机同会话锁死 Bug） | ✅ | `MixinInnerClassHotSwap.redefineSyntheticSafely`（抑制签名 = 候选字节 `Arrays.hashCode(bytes)`，键 = 合成类名；同一份字节 `stays aborted` 抑制、异签名重新评估、放行即清账）、`MixinHotSwap.flushPendingTargets`（每轮清 `BLOCKED` 重新评估，不再用宿主 `originalBytes` 做前置跳过）、`syntheticLive(live)`（raw-only 诊断，产品路径无 `System.gc()`）、`MixinSyntheticSchema`（删除已不可达的 `padFields`/`renameCollisions`/`rewriteFieldAccess`/`retainConstructors`/`canRedefineWithoutInstanceRewrite`/`describeFieldTableChange`/`appendedFields`） | **已观测**：真机同会话 R1→R4 曾因签名取宿主恒定 `originalBytes`（`837190382`）把 R2/R3/R4 全部永久跳过。单测 `MixinSyntheticRetryAndFlagsTest` 3 例按真实数据流（宿主字节恒定：R1 拒 → R1 同字节抑制 → R2 异签名再拒 → R4 还原放行）、`MixinSyntheticSchemaTest` 4 例（两套视图 + static/synthetic 翻转 + schemaHash）、`MixinSyntheticLivenessTest` 4 例、`MixinHotSwapWiringTest` 2 例、`BridgeAbortTransactionTest` 2 例、`MixinSyntheticGateTest` 8 例；全量 `:tests:test` 718 用例 / 0 失败 / 1 skipped + `initFixOracle` ALL PASSED。**变异 2/3 击杀**：签名改回恒定输入（R4 锁死）killed；去掉 flags 比较 killed；「抑制永不开火」**survived 且判定为等价变异**——闸门已无条件拒绝 schema 变化，抑制只改变日志措辞、不改变是否 redefine，故该台账当前是诊断设施，真正的强制点在闸门。**P2 待验证假设**：`schemaHash` 命名新合成类（`defineClass` 加载新类 + 宿主仅 body-only redefine）预注册判据 = 新名类加载 + 宿主 body-only redefine 下 `newlyNull=0`；未实现、未验证 |
| 合成类成员 schema 变化一律拒绝 + 禁止同批重试 + 字段标志位（v0.2.7 战役：真机终局裁定） | ✅ | `plugin-mixin` `MixinInnerClassHotSwap.redefineSyntheticSafely`（`fieldTableChanged` 为真一律 `gateRejected=1` + `blockHost` → `BytecodeTransformerBridge.Abort` 宿主原子共退；`live{raw,reachable}` 降级为诊断读数）、`SyntheticAbortLedger`（宿主 → 被拒候选签名；`MixinHotSwap.flushPendingTargets` 在 `redefineDynamic` 前按候选字节签名跳过同一份候选，候选变了才清 `BLOCKED` 重新评估，成功落地即清账）、`MixinSyntheticSchema.loadedFieldSignatures` / `nodeFieldSignatures`（把 `isStatic` / `isSynthetic` 纳入 schema 比较） | **已观测**：真机终局两分支（HEAD `ac20e60b`）——情况 1 `live{raw=4 reachable=4}` 首轮 `gateRejected=1` + 宿主不重定义、不崩、`newlyNull=0`，但同轮自动重试读到 `live{raw=0 reachable=0}` 后放行即崩；情况 2 `raw=4/reachable=0` 与 `raw=0/reachable=0` 放行后立刻 `newlyNull=230`。→ 成员 schema 变化的污染与实例数无关，故改为无条件拒绝。单测：`MixinSyntheticLivenessTest` 4（B 改为「零可达实例也必须拒绝」）+ `MixinSyntheticRetryAndFlagsTest` 3（R1 拒且不重试 → R4 还原捕获字段后放行；static 翻转算 schema 变化；ledger 只抑制同一份候选）+ `MixinHotSwapWiringTest` 2 + `BridgeAbortTransactionTest` 2 + `MixinSyntheticGateTest` 8 = 19/19 绿；全量 `:tests:test` 720 用例 / 0 失败 / 1 skipped + `initFixOracle` ALL PASSED。**变异 3/3 击杀**（改回「变化+零可达实例→放行」/ ledger 永不抑制 / 去掉标志位比较）。**JBR-21 实测**（`jbrsdk_jcef-21.0.9` + `AllowEnhancedClassRedefinition`）：`RESULT PASS cases=4 pass=4 skip=0 enhanced=true`——P1 只改方法体 → 存量实例读 `v2:7:payload`；P2 缩容被拒；P3 增强宿主缩容负对照；P4a `live{raw=1 reachable=1}` 拒、P4b `reachable=0` 仍拒且类未被改。**边界**：`AnonClassAligner` 的 Tier 4 门仍是「拒绝只不配对、宿主继续」，与我们的宿主原子共退不同（见其研究结论）；`<Target>$Anonymous$<hash>` 换捕获列表仍需重启或改 `@RemoldReinit` / lambda |
| 合成类存量实例（liveness）读数 + 三档闸门（v0.2.7 战役：P0 第二轮） | ✅ | `plugin-mixin` `MixinInnerClassHotSwap.syntheticLive(live, fieldTableChanged)`（raw = `LibTool.getInstances` 堆遍历全部实例，含不可达但未被 GC 物理回收的临时实例；字段表变化且 raw>0 时一次 `System.gc()` 后再取 reachable；探针不可用 = `-1` → fail-closed）、`setInstanceProbeForTest`（单测替换口）、`probeInstanceCount`；闸门接线在单一出口 `redefineSyntheticSafely`：`fieldTableChanged==0` 一律放行 / `>0 && reachable>0` 拒绝 + `blockHost` / `>0 && reachable==0` 放行 / 探针不可用拒绝；`FIELD_CHANGED` 改为「字段表是否变化」（拒绝时也计数） | **已观测（单测 + 产品探针真堆）**：`MixinSyntheticLivenessTest` 4/4（A `live{raw=1 reachable=1}` + 字段表变化 → `executed=0/gateRejected=1/fieldTableChanged=1`、宿主 `Abort`、合成类+宿主 `redefineClasses` 合计 0；A 对照丢引用后同一候选放行；B `live{raw=2 reachable=0}` → 放行且产出字节含 2 参与 4 参 `<init>`；C `live{raw=1 reachable=1}` + 仅改方法体 → 放行、`fieldTableChanged=0`、产出字节答 `v2:captured`；产品探针真堆对照：强引用实例 `reachable≥1`，丢引用 + GC 后 `reachable==0`）。**变异 3/3 击杀**：反转 `reachable>0` 判据（用例 B 红）、拒绝分支去掉 `blockHost`（用例 A `:98` 红）、整条 liveness 闸门关掉（用例 A `:92` 红）。全量 `:tests:test` 717 用例 / 0 失败 / 0 error / 1 skipped + `initFixOracle` ALL PASSED。**真机状态**：`fieldTableChanged>0 && reachable==0` 分支「放行」是**待真机裁定**的临时取值——若真机在堆上 0 实例时仍 `newlyNull>0`，必须并入拒绝；`System.gc()` 只是提示，`-XX:+DisableExplicitGC` 下 reachable 仍等于 raw，判据落到「有实例 → 拒绝」一侧 |
| 合成类基线读取源 + 宿主事务原子中止（v0.2.7 战役：真机 P0 首轮暴露的两处假接线） | ✅ | `plugin-mixin` `MixinInnerClassHotSwap.baselineBytesForLoaded`（基线链：本加载器已生效字节 → premain 录口 `(loader 身份, 点分名)` → 最后才退回 `GENERATED` 覆盖层；该读取**不计** `missed`）、`hostDotNameOf`（`BLOCKED` 键取 `InnerClassInfo.getTargetName()`，不再用 Mixin 模板名）、`MixinReWeaver.reweave`（入口与织入后各拦一次）、`MixinHotSwap.flushPendingTargets`（每轮清理/重试 + 专属「宿主保持旧字节」日志）；`api` `BytecodeTransformerBridge.Abort`（新）+ `RemoldAgent.applyTransformerBridges`（原样上抛）/`redefineDynamic`（捕获后不调 `redefineClasses`）/`processChanges`（该类移出本批） | **已观测（单测/字节码级）**：`MixinHotSwapWiringTest` 2 用例（①仅 `(loader,name)` 录口有基线、覆盖层为空时 `retained 1 baseline <init>` 且 `executed=1 / fieldChanged=1 / gateRejected=0`，产出字节同时含 2 参与 4 参 `<init>`；②闸拒后合成类与宿主 `redefineClasses` 合计 **0** 次，`redefineDynamic` 返回 false，清标记后同一调用不再中止）+ `BridgeAbortTransactionTest` 2 用例（Abort 穿透桥循环且消息保真；普通 `RuntimeException` 仍按旧契约回退字节）。**变异 3/3 击杀**：基线读回 `GENERATED`（用例①红 `MixinHotSwapWiringTest.java:97`）、关掉 `reweave` 的 `BLOCKED` 拦截（用例②红 `:159`）、吞掉 Abort 用未织入字节照常重定义（`BridgeAbortTransactionTest:90` 红）；另记一条**等价变异**（把中止改成 `throw abort` → 外层 `catch(Throwable)` 仍 `return false`，不可观测，故不计击杀）。全量 `:tests:test` 713 用例 / 0 失败 / 0 error / 1 skipped（既有 Assumption：`capturedLivingEntityBaselineIsFullyRestored` 需仓库外夹具）+ `initFixOracle` ALL PASSED。**真机状态**：两处假接线由真机 P0 首轮（`executed=0, gateRejected=1` 随后 `NoSuchMethodError` 崩服）暴露，**修复后真机复跑尚未进行**——按 §8.6 只能表述为「已定位并接线修复」，不得表述为「已修复」 |
| 合成匿名类字段 Schema 锚定（v0.2.7 战役：故障 B 第二治本点） | ✅ | `plugin-mixin/src/remold/mixin/MixinSyntheticSchema`（`padFields` 按已加载字段表补墓碑字段并保持原槽位；`canRedefineWithoutInstanceRewrite` 拒绝类型漂移；`schemaHash` 供诊断/未来命名）、`MixinInnerClassHotSwap` 两条重定义路径接线 + `HeapFieldWatch` 的 `before/after-synthetic-redefine` 切面 | **已观测**：单测 `MixinSyntheticSchemaTest` 4 用例（缩容补齐且字段表恒等/同 schema 不补/类型漂移拒绝/schemaHash 稳定与区分）；手写变异（`padFields` 关掉重排 → 用例红，恢复 → 绿）。真机（Fabric server + JBR 21.0.9 DCEVM，Mixin 含 `@Unique` 字段）同一 JVM 连续 5 轮 R1~R4：`field-table-stable=true`、`RESULT PASS`，`[MIXIN-ALIGN] retained 1 tombstone field(s)` 每轮出现。**边界**：`<Target>$Anonymous$<hash>` 的**命名哈希**仍由上游 `InnerClassGenerator` 的随机 UUID 决定（`InnerClassInfo.name` 为 final 且被注册进宿主合成类表），Schema 限定命名需要新增"新类名定义通道"；当前以"字段表锚定"达到同等效果（不产生跨 Schema redefine）。客户端 `combinedCondition` NPE 的终验仍需前线 5 轮 + `nullCount=0/244` |
| Mixin 字段墓碑保留 + 堆实例哨兵（v0.2.7 战役：故障 B 治本） | ✅ | `core/src/remold/ref/MixinMemberAlign`（基线字段墓碑保留：`@Unique` 字段消失时保留原字段，字段表槽位不变）、`core/src/remold/ref/HeapFieldWatch`（三切面堆实例字段哨兵，`-Dremold.probe.heapwatch=true`）、`RemoldAgent.redefineDynamic` 三切面接线 | **已观测**：单测 `MixinMemberAlignTest`（`removedUniqueFieldIsRetainedAsTombstone` / `fieldTableStaysConstantWhenNothingWasDropped`）+ 变异 1/1 killed（关掉字段补桩 → 用例红）；真机（Fabric server + JBR 21.0.9 DCEVM，Mixin 含 `@Unique` 字段）同一 JVM 连续 5 轮 R1~R4（无 tick freeze、无人工等待）：每轮 `field-table-stable=true`、`[MIXIN-ALIGN] retained 1 tombstone field(s)`、三切面 `[HEAPWATCH] nullCount=0` → `RESULT PASS`。**边界**：客户端 `LootContextPredicate.combinedCondition` NPE 只能在真机客户端复验；哨兵已内置，开启方式见 `docs/mixin/aligner-convergence-verification.md` |
| Mixin 合成对齐器收敛（v0.2.7 战役：墓碑桩保留 / 无变更短路 / 接口表双向同步） | ✅ | `core/src/remold/ref/MixinMemberAlign`（墓碑桩保留：基线生成方法消失时补最小空操作桩，同名/同描述符/同访问标志/同方法表位置；参考 `LambdaAligner` 孤儿方法策略）、`plugin-mixin` `MixinInnerClassHotSwap`（`LOADED` 记账 + `isUnchanged`：字节一致时跳过 `redefineClasses`，参考 `AnonClassAligner` no-op 语义）、`MixinReloadPolicy`（`+I → -I` 接口表精确还原） | **已观测**：单测 `MixinMemberAlignTest`（墓碑保留 + 业务方法不复活）、`MixinInnerClassHotSwapTest`（同字节短路/未知类不短路）、`MixinReloadPolicyTest`（`interfaceRemovalIsAlignedOnAnEnhancedHost`）；变异 A 1/1 killed、B 1/1 killed（短路恒失效被击杀）。真机（Fabric server + JBR 21.0.9 DCEVM）：同一 JVM 不重启连续 4 轮 R1~R4，4/4 `hammer-failure=null`/`window-failure=null`/`anon-ctor-roundtrip ok=true`，每轮 `[MIXIN-ALIGN] retained 1 tombstone stub(s)`，0 次 `handler$*` NoSuchMethodError、0 次 `LootContextPredicate.combinedCondition` NPE（`RESULT PASS`）。**边界**：客户端侧（`minecraft-fabric-template` 真机客户端，12 mixin + 累积状态）4 轮压测仍需前线复跑确认；故障 B 的 NPE 只在客户端累积状态复现，本仓库合成探针无该场景 |
| 合成匿名类重建/重定义顺序（v0.2.7 战役 P5） | ✅ | `plugin-mixin` `MixinInnerClassHotSwap.refeedForTarget`（每次按当前模板重新生成；已加载且字节/schema 变化时在**目标类 redefine 之前**就地重定义）、`apply`（JBR/DCEVM 上放行合成类 schema 变更）、`MixinHotSwap.registerInnerClassBytes`（本轮覆盖层字节优先于磁盘） | **已观测**（真机 Fabric server + JBR 21.0.9 DCEVM）：注入体内含匿名类的 Mixin 走 `priority → implements → targets A→B→A` 全往返，期间两条并发锤线程持续调用注入方法，`hammer-failure=null` / `window-failure=null` / `anon-ctor-roundtrip ok=true`（`RESULT PASS`）。修复前同一形态在还原阶段抛 `NoSuchMethodError: <Target>$Anonymous$<hash>.<init>(...)`（调用点已换新描述符、合成类仍是旧构造器）。单测 `MixinInnerClassHotSwapTest` 全绿 + 变异 1/1 killed（新增方法不计入 schema 差异）。**边界**：标准 HotSpot 无法 redefine 合成类成员表（只能重启）；接口移除仍无本地实证；Forge/NeoForge 无 provider 覆盖层 |
| Mixin→Target 描述符重映射（ClassInfo 锚定，v0.2.7 战役 P4） | ✅ | `core/src/remold/reflect/mixin/MixinClassInfoRegistry`（`reassert(transformer)`：遍历已注册 `MixinConfig.mixins`，把 `MixinInfo.getClassInfo()` 按内部名写回 `ClassInfo.cache`）；调用点：`MixinConfigDiffer.registerAddedMixin` 与 `MixinHotSwap` 重载/登记成功处 | **已观测**（真机 Fabric server + JBR 21.0.9 DCEVM）：含匿名内部类的 Mixin 依次执行 `priority 1000→1500` → `targets [A]→[B]` → `implements DuckAccess` → `targets [B]→[A]` 后，目标实例调用注入方法不再抛 `NoSuchMethodError`（`anon-ctor-roundtrip ok=true`、`RESULT PASS`）；修复前同一序列在 targets 阶段即抛 `IllegalClassLoadError`（匿名类引用未重映射）。单测 `MixinClassInfoRegistryTest` 4 用例（锚定/补缺/不臆造/空注册表）+ 变异 1/1 killed（把锚定写入改成 null 后用例变红）。根因：`ClassInfo.forName` 缓存未命中时用普通 `ClassNode` 构造 `ClassInfo`（`isMixin == false`），`MixinTargetContext.transformSingleDescriptor` 因此跳过 Mixin→Target 重映射，匿名内部类 `this$0` 的构造器签名停留在 Mixin 类上。**边界**：接口移除仍无本地实证；Forge 侧无 provider 覆盖层；上游字段改名时退化为「需重启」诊断 |
| Mixin 结构契约突破（v0.2.6 战役：新 Mixin 字节通道 / `ReloadMixinInfo` 限制 / 配置先到字节后到） | ✅ | `core/src/remold/reflect/mixin/MixinClassByteSources`（字节通道链）、`MixinReloadPolicy`（priority 最终字段对齐 + 接口表预对齐/回滚）、`MixinRetarget`（targets reload 前就地迁移 + 快照回滚）；`plugin-mixin` `MixinNewMixinBytes`（覆盖层 + `BytecodeSources` 预登记）、`MixinBytecodeOverlay.applyToConfigs`（MixinConfig 捕获的服务引用也走代理）、`MixinHotSwap.reloadWithRetry`（`re-entrant lock` 瞬态有界退避）；`MixinConfigDiffer` 待决集 `pendingUnresolvedMixins` + `retryPending` | **已观测**（真机 Fabric dedicated server + JBR 21.0.9 DCEVM，agent/plugin 由本分支构建）：① 新 Mixin 的 `.class` **只存在于监控目录**（`Class.forName` 抛 CNFE）且以子包相对名 `b3.OutOnlyMixin` 列入 `mixins.json` → 被自动登记并挂到目标映射（`registered-with-target=true`）；② `@Mixin(priority)` 1000→1500 生效且 hook 仍触发；③ `@Mixin(targets)` A→B 迁移后 `fire(B)` 命中注入、`fire(A)` 不再命中；④ `implements` 标记接口在目标实例上生效（`duck-interface-on-target=true`）。单测：`MixinClassByteSourcesTest` 7、`MixinConfigDiffLoadTest` 2、`MixinNewMixinBytesTest` 3、`NewMixinRegistrarTest` 8、`MixinConfigDifferTest` 4、`MixinReloadPolicyTest` 8、`MixinRetargetTest` 5、`MixinConfigDifferPendingTest` 4、`ClassDiffHierarchyTest` 5；变异：P1 logic 7/7 + revert killed，P2/P3 各 3/3 killed（含“接口判决恒拒绝”“priority 不真正对齐”“affected 丢旧目标”“待决不记录/不消费/不校验配置”）。**已知边界**：① 接口**移除**没有本地实证（只在 DCEVM 上放行并 warn，ClassInfo 接口表只增不删）；② Forge/NeoForge 无 `IClassBytecodeProvider` 覆盖层，带 `$N` 内部类的新 Mixin 在 Forge 上仍可能需要一次重启（Javadoc 已写明，未假装覆盖）；③ 合成探针（预加载目标 + ASM 现场生成 Mixin）在目标重织入阶段触发 JVM `InternalError`，登记通道本身已验证，重织入终局需要“真实源码编译的 Mixin + IDEA 风格输出目录”探针再验；④ 子包相对名的墓碑自愈按“点分后缀”匹配（`MixinConfigDiffer.isRemovedFromConfig`） |

---

## 7. 工程化、构建与分发 (Engineering, Build & Distribution)

> 本节登记**制品产出与对外分发**链路的实现状态。此前的状态表只覆盖运行时机制，导致「仓库已上线、CI 已跑矩阵、JitPack 坐标已被模板消费」等既成事实长期被误记为待办。

| 条目 | 状态 | 落地位置 | 验收证据与已知边界 |
|:---|:---:|:---|:---|
| 独立仓库与远程 CI 矩阵 | ✅ | `.github/workflows/ci.yml` | 4 个 job：`test`（`setup-java` 装 Temurin 8/11/17/21 + runner 25，`-Dhstest.requireJdks=true` 与 `auto-download=false` 强制五套工具链真实存在，跑 `./gradlew fullCheck` = `check` + `e2eProbe`）、`test-dcevm`（JetBrains Runtime 21 真实 DCEVM 下跑 `:tests:liveDcevmTest`）、`p05-probe`（JBR 17/21 矩阵跑 `:tests:p05Probe`）、`build-native`（g++ 编 Linux x86_64 `.so`、NDK 编 Android arm64-v8a `.so`；Linux aarch64 的 `libtoolaarch64.so` 随仓库分发、与 Windows/macOS 产物同一模式，CI 只校验不重建：`AArch64`、`libc.so.6`+`libpthread.so.0`、glibc 基线 2.17、非 bionic，然后上传制品） |
| 模组生态隔离与构建产物防御 | ✅ | `docs/ecosystem/01-mod-artifact-and-class-isolation.md`、`gradle-plugin/.../RemoldConfigResolver.java`、`core/.../RemoldAgent.java` | 3D 环境探测（Loom / Forge / NeoForge / Quilt / Essential）阻断 `autoDeploy` 并警告；IDEA BeforeRunTask 收敛为 `Make`；Agent 启动根据元数据比对 ModID 极速巡检 `run/mods/*.jar` 并在控制台黄色高亮警告；文件监听忽略 `mods/` 下的 `.jar` 变更；单元测试全绿 |
| JitPack 对外分发 | ✅ | `jitpack.yml` + tag `v0.1.0`~`v0.2.6` | 对外坐标 `com.github.I-hope1.Remold:<module>:<tag>`（tag 形如 `v0.2.6`，即 `v<version>`；由 JitPack 按仓库名推导，与项目 `group` 是两个命名空间）；`jitpack.yml` 固定 `openjdk25` 并执行 `publishToMavenLocal -x test`；`README.md` §1.6、两个模组模板、`remold.gradle(.kts)` 薄壳的插件解析均消费该坐标；agent 解析在 `:gradle-plugin` 内完成 |
| Gradle 二进制插件 (`gradle-plugin`) | 🔶 | `gradle-plugin/`、`gradle-plugin/build.gradle`、`gradle-plugin/README.md` | 提供 `io.github.i-hope1.remold` 二进制插件，支持 Kotlin DSL 与 Groovy DSL 类型安全配置 `remold { ... }`、配置缓存与惰性解析；`RemoldPluginTest` 11/11、`RemoldScriptShimTest` 单元测试通过；JVM 参数注入覆盖 `client`、`server` 与 gametest 运行容器（Fabric Loom / ForgeGradle / NeoForge ModDevGradle，`./gradlew runServer`、`runClientGameTest` 同拿 `-javaagent`），IDEA Loom 同步覆盖 `Minecraft_Server.xml`（Application 风格 `VM_PARAMETERS` 与 Gradle 风格 `vmOptions`）；仓库根 `remold.gradle(.kts)` 已瘦身为薄壳（仅按 Class 应用本插件，实现在本模块），jar 内附带一份副本供离线取用。**Gradle Plugin Portal 尚未上线**：`publishPlugins` 已执行并进入人工审核，审核以「lacks documentation in English」退回、该次提交已被取消（官方邮件明确可复用同一版本号）；据此已补英文插件文档 `gradle-plugin/README.md`、把 `website` 指到该英文文档、并在 `README.md` 顶部加英文摘要，待重新 `publishPlugins` 后由审核结果定论。另：门户已对未声明 Gradle 特性兼容性（`compatibility { features { configurationCache } }`，需 plugin-publish ≥ 2.1.0）的发布告警并计划改为拒绝，当前仍是 1.3.1 |
| GitHub Release 发布物 | ✅ | `.github/workflows/release.yml` | Tag `v*` 触发全量前置验证矩阵（javac 8/11/17/21/25 测试、DCEVM 测试、P0.5 探针与 Linux x64/Android 原生库编译、Linux aarch64 制品 ABI 校验），全绿后自动打包 18 项 Release 资产并发布到 GitHub Releases，消除资产早于构建事故；支持 `workflow_dispatch` 手动触发与 `--clobber` 覆写更新 |
| Class / 资源防抖分离 | ✅ | `core/src/remold/DebounceWindow.java`（纯状态机）、`RemoldAgent.handleFileChange/handleJarChange/handleAssetChange`、`gradle-plugin` 的 `assetShakeMs` | Class/jar 事件每次重置独立静默窗口（默认 800ms）；资源/配置流独立窗口（默认 250ms），到期只 drain 资源队列，不提前带出 Class 批次；手动 `triggerHotswap()` 取消挂起调度、清空两条流并强制 drain 全部。`DebounceWindowTest` 6/6（无 sleep：事件重置、双流互不影响、nextDelay、clear）、`AssetShakeConfigTest` 1/1；全量 `check` 514/0 skipped。**未验证**：真实 FileWatcher 的 OS 事件节奏（单测只覆盖状态机，未测 Windows 文件系统时延分布） |
| 模组模板基线（1.21.1 主模板 + 客户端 HotSwap Toast） | ✅ | `minecraft-fabric-template/`（MC 1.21.1 / yarn 1.21.1+build.3 / loader 0.16.9 / fabric-api 0.105.0+1.21.1 / Loom 1.17.21；客户端入口 `com.example.client.ExampleModClient`） | JBR 21（`jbrsdk_jcef-21.0.9`）`./gradlew build --offline` **exit 0**：`HotSwapToastModelTest` **10 tests / 0 skipped / 0 failures**；产物 `build/libs/example_mod-1.0.0.jar` 内含 `com/example/client/*` 且 jar 内 `fabric.mod.json` 含 `client` 入口；Toast 按 1.21.1 的 `Toast.draw(DrawContext, ToastManager, long)` 适配（1.21.1 无 `Toast.update`，展示计时在 `draw` 内完成），结构化报告依赖新版 Agent；v0.2.6 已发布并带结构化批次报告，旧版 Agent 仍显示明确的降级卡片；**未验证**：卡片实际渲染（本环境无显示会话，仅观测到入口/监听器注册日志路径与模型单测），手动步骤见模板 README |
| 1.21.4 客户端 gametest 探针 | 🔶 | `tests/fabric-gametest-1.21.4/`（独立 Gradle 工程：MC 1.21.4 / yarn 1.21.4+build.8 / loader 0.16.14 / fabric-api 0.119.4+1.21.4 / Loom 1.17.21） | 由 1.21.4 模板的 tracked 文件裁剪而来（删除 Toast 的 `src/main/java/com/example/client/`、`src/test/` 与 `client` 入口点；保留 wand/Mixin/`src/gametest/` 与截图模板）；JBR 21 `./gradlew compileJava --offline` 与 `./gradlew compileGametestJava --offline` 均 **exit 0**；日志确认真实注入：`[Remold] Injected hot-reload agent parameters into Fabric Loom runClientGameTest!`；`configure<FabricApiExtension>{configureTests{...}}` 生成 `gametest` 源集 + `clientGameTest` 运行配置。**端到端 `runClientGameTest` 证据沿用 1.21.4 模板时期（`4d459ff`：真客户端建世界 → 热栏截图断言 → 真实右键聊天栏 `[Remold]`）；本次移动后未重跑**（无显示环境），不计为新增验证 |
| Maven Central 分发 | ⬜ | `build.gradle` 的 `group = 'io.github.i-hope1.remold'` | 仅 `README.md` 标注为「预留坐标」，未实际发布；走通需 Central Portal + GPG 签名 + `io.github.<user>` 命名空间验证。当前 `publishToMavenLocal` 落盘该 group 仅供本机联调兜底（`remold.gradle(.kts)` 的 m2 候选路径按文件探测，文件不存在即跳过） |
| mod-tools 消费侧切换与内部副本清理 | ⬜ | 本仓库之外（mod-tools 仓库） | 依赖坐标切换与仓内 core 副本删除均发生在 mod-tools 仓库，本仓库无法提供验收证据；其前置条件（JitPack 坐标可用）已满足 |

---

## 8. 前置验证与实测数据 (Pre-flight Measurements)

| 条目 | 状态 | 落地位置 | 验收证据与已知边界 |
|:---|:---:|:---|:---|
| P0.5 前置微验证（连续 Schema Redefine / 阶段 A 链接 / 堆遍历规模） | 🔶 | `tests/src/P05Probe.java`、`tests/src/P05Agent.java`、`tests/build.gradle`（任务 `p05Probe`）、`.github/workflows/ci.yml`（任务 `p05-probe`，JBR 17/21 矩阵） | JBR 21.0.9 本机 **63/63 断言通过**（M1~M9）：连续两次 schema 变化均被接受（阶段 A p50 30.9 ms、阶段 C p50 27.3 ms；schema 变更平均触发 1 次 GC ≈ 5 ms，纯方法体变更 0 次）；阶段 A 预注入的私有实例方法、私有静态方法与新 lambda 可被 nestmate 伴生类链接，并带「阶段 A 之前必抛 `NoSuchMethodError`」的负向对照；1M 实例堆遍历 0.87 s、结果数组 4 MiB；M7 分离实验（具名类删字段）见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §13；M8（方法删除：向上解析 vs 向下分派，三种失败形态）见 §15；M9（静态字段删除的三种形态 + 编译期常量内联 + `getfield`/`getstatic` 双向 ICCE）见 §16。**缺口**：JBR 17 腿（CI，JBR 17.0.14）已回填，**63/63 通过**，与 21 同结论；但 17 上没有 `deoptimization` 日志 tag（写进 `-Xlog` 会让 JVM 直接拒绝启动），M6 的 deopt 一路交叉验证证据在该运行时上不可得，由探针打印 `EVIDENCE-MISSING` 显式声明（是证据缺失，不是断言失败）。「1M 实例枚举」断言（M4；此前状态表误记为 M3）曾在 CI 上稳定失败，根因不是内存压力而是探针自身跨轮持有强引用：上一轮的 10 万实例被同一栈帧里的 `keep`/`got` 数组钉住，patch 驱动实测打出 `count=1100000`；已改为单轮独立栈帧 + 置空后 `gc`，并补「堆里无残留」前置断言（故断言数 61→63）。单机单次采样，未做 Linux 对照与 JIT 预热隔离。方法与原始数据见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) |
| 两阶段编排端到端验证 | 🔶 | `tests/src/TwoPhaseProbe.java`、`tests/build.gradle`（任务 `twoPhaseProbe`） | JBR 21 本机 **72/72 断言通过**：除原有的纯编排场景（`阶段 A → redefine → 阶段 B 补丁 → 阶段 C redefine`；不插桩窗口实例永久停在默认值的对照组；JIT 预热分配点与在途旧构造器帧，见 §9/§11/§12）外，新增 **生产接线场景**——真实 `RemoldAgent.processChanges` + 50k 存量实例 + 并发读线程：两阶段 ON 时读线程零 NPE 且依次看到旧/新结果，`-Dremold.agent.two_phase=false` 对照稳定观测到 `NullPointerException`（红基线），证明读穿窗口真实存在并被关闭。**缺口**：JBR 17 腿尚未重跑新增场景；未覆盖阶段 A 中止回滚后的重试、多类批次真实多线程分配竞争；safepoint 日志行的字段集合随版本变（17 没有 `Cleanup`/`Leaving`），窗口解析已按字段可用性降级（`At+Leaving` → `Total−Reaching`），两侧结论一致。见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §9、§11、§12 |
| 物理偏移稳定性与漂移探针 (OffsetStabilityProbe) | ✅ | `tests/src/OffsetStabilityProbe.java`、`tests/build.gradle`（任务 `offsetStabilityProbe`） | JBR 21.0.9 本机 **47/47 断言通过**（A1~A4 单类布局规律/空洞填补/类型重排、B1~B3 继承体系漂移步长、C1~C2 VarHandle 错位破坏、D1~D2 使用方 C2 逆优化与重编译恢复、E1~E2 Field 与 MethodHandle 访问器自愈边界）；与 `LayoutPredictor` 及 `SuperclassDriftGate` 形成事前预测阻断闭环 |

> **事实③（`db74eec` 中 3 参 `<init>` 存在却 `NoSuchMethodError`）**：**不再追查、未闭合**。
> **`newlyNull=230` 的口径**：实测 在该客户端、该类、该序列下，对已加载合成类做字段表变化的 `redefine`，
> 至少 3 次独立运行重复出现 `newlyNull=230`（双通道 `agree=1220 / conflict=0`）；底层 `ModifyInstances`
> 机理为 由代码分析可知（未直接观测）。
> **值类型直方图归因**：`230 个具名 Record 类值变 null、14 个隐藏类 Lambda 值幸存` 为 实测；
> “这些实例由 `KnotClassLoader` 加载”“`Util.allOf` 单元素分支”为 由代码分析可知。
> **P2 待验证假设（未实现）**：`schemaHash` 命名新合成类——schema 变化时用 `defineClass` 加载**新类名**，
> 宿主只做 body-only redefine；预注册判据：新名类加载 + 宿主 body-only redefine 下 `newlyNull=0`。

## §0.15 lambda 兜底配对改 fail-closed

| 项 | 状态 | 证据等级 |
|:--|:--|:--|
| L5 静默错绑（体内加内层 lambda → 旧实例跑成内层逻辑、无 WARN）已转为 `BODY_MISMATCH` WARN + 拒绝配对 + 旧名幽灵化 + 新名避障 | 已拦截 | 覆盖用例：`PassBTest`（语料 N=37 与嵌套 lambda 阳性对照）、`LineNumberDifferentialTest`（幽灵归因契约） |
| 方法体指纹归一化：常量按值、引用按 `owner+name+desc`、局部槽按首现序、标签按首引用序、可折叠算式 | 已实现 | 实测：语料 N=37 中 31 个类触发兜底门禁，评估 107 次、107 次进入 `bodyHash` 比对且均相等放行、拒绝 0 次，同源变换误报 0/107；另有人造重排 4 类各 n=1 |
| 信息论边界：删一个 lambda 并在同一次保存里新增一个 javac 恰好复用同编号、且宿主同名的 lambda，与原地更新在字节码层不可区分 | 未解决 | 由代码分析可知；缓解只有名字/编号漂移这类间接信号 |
| Pass B 覆盖面收窄：纯搬迁先被 Step 1 吃掉，Pass B 只覆盖"体等价但票不同" | 已登记 | 实测 |
| `stats.bodyMismatchRefusals` 是**逐趟计数**，不是拒绝次数 | 已登记 | 实测 |
| 槽位平移幽灵：`-g:none` + 槽位整体平移，语料 N=37 中 9 类出现幽灵、共 23 桩（= 8/30、22 桩 + `plugin-forge` 的 `ForgeMixinHotSwap` 1 类 1 桩） | 已结案：`gate=on/off` 两趟 37 行逐字节相同，机制为语料上 107 次门禁评估均放行（0 拒绝），关掉门禁同样放行，故输出相同 | 实测；成因"票据指纹计入 `var` 下标"为 由代码分析可知，未做隔离实验 |
| 允许幽灵参与匹配的变异体在门禁开启后不可被击杀 → 转门禁屏蔽集 + `D==0` 哨兵；哨兵与具名用例是**报警不是防线**，Pass B 两处仅靠门禁 | 已登记 | 由代码分析可知 |
| 语料范围 | N=37：`core/src`+`api/src`（30/258，合并编译）、`plugin-fabric/src`（3/7）、`plugin-mixin/src`（2/23）、`plugin-forge/src`（2/12） | 实测 |
| 语料缺口 | `plugin-arc/src`（Mindustry 编译期制品非纯 class 包，缺注解处理器，javac 解析即失败）、`tests/`、`gradle-plugin/` | 实测 |

- 合流点 `680029f`；权威全量读数（HEAD `b0679c8`，`--rerun-tasks`，35 tasks executed，166.2 s）：**732 tests / 0 failures / 0 errors / 0 skipped**。
- 结案记录：门禁评估次数已量化（`eval=107 / fpCmp=107 / fpAllow=107 / refused=0 / classes=31`）；嵌套 lambda 对照种子的阳性对照 `eval=18 fpCmp=18 hostKindRefused=18 classes=1`（指纹不等后由宿主形态翻转拒绝）。
- 候选 I2（槽位平移幽灵的隔离实验，验证 `var` 下标成因）**只登记不排期**，会动生产代码，须单开批次。
- **跨轮次告警去重问题**：去重键 `类名#BODY#新名<-旧名` 不含重定义轮次/类版本（由代码分析可知，代码阅读）；生产侧 `core/` 下 `clearLoggedWarnings()` 调用点为 0、`TEST_EVENTS` 生产消费者为 0（实测，grep 读数）；配对拒绝本身不受去重影响（由代码分析可知）。未修，单开批次。

## §0.16 参数注解 / 泛型签名漂移的告警点名

| 项 | 状态 | 证据等级 |
|:--|:--|:--|
| 参数注解值/类型/删除变化、字段与方法泛型签名变化 → 成员级 `[SIG-DRIFT]` WARN 点名 | 已实现 | 实测：740 用例绿（实测记录）+ 检测三处变异 3/3 killed（变异检查记录） |
| **类级泛型签名（`getGenericSuperclass`）不在检测范围**；对应运行时② 的"重新调用 `getDeclaredMethods()` 读取"仍读到旧值 → 合起来是"静默按旧值走" | 未检测，登记 类级泛型签名未检测 | 读数表显示类级泛型签名在两列仍读旧值 |
| 接线：`processChanges` 里的 `reportMemberMetadataDrift` 调用由 `:tests:metadataDriftWarnProbe` 把关（已接进 `:tests:check`） | 已接线 | 覆盖任务：`:tests:metadataDriftWarnProbe`（挂在 `:tests:check`，真实 `processChanges` 路径发出 `[SIG-DRIFT]` 告警） |
| 接线在 `:tests:test` 口径下**无分辨力**（该用例直接调用 `reportMemberMetadataDrift`，不覆盖调用点） | 已登记 | 该接线在 `:tests:test` 口径下不可分辨，由上述探针任务把关 |
| WARN 文案：只含类/成员名 + 变化种类 + "旧句柄读数不可靠、请勿继续使用" + "自持句柄不会被修复" | 已收敛 | 文案形态与断言位置见 `ParameterAnnotationDriftTest`（禁用标签全集断言）；源码 Javadoc 中两处否定句（`ReflectionCacheManager:134`、`RemoldAgent:2626`）为允许清单 |
| 驱逐范围扩展（`EvictionScope.WITH_DESCENDANTS`）未交付，停 `parked/k-eviction-scope`；缺"已注册缓存驱逐前后对照读数" | 停放 | 主干仅剩 `RemoldAgent:2611` 一行注释，已改为技术说明 |
| 未测 | JBR 8/11/17；Gson/Jackson 真实缓存路径 | — |

## §0.17 注解默认值（`AnnotationDefault`）漂移：fail-closed 拒绝

| 项 | 状态 | 说明 |
|:--|:--|:--|
| 注解默认值变化 / 移除 / 新增 → 默认拒绝（批量整轮共退、动态路径拒绝） | 已实现 | 判定入口 `ClassDiffUtil.describeAnnotationDefaultChange` + `RemoldAgent.annotationDefaultGate`；覆盖用例 `ClassDiffUtilTest`（10 条）、`AnnotationDefaultGateTest`（6 条） |
| 降级开关 `-Dremold.agent.annotation_default_change=allow` | 已实现 | 降级时输出指名告警（类名 + 成员名），不静默 |
| 三种运行时的影响范围（按运行时区分） | 实测 | 标准 HotSpot（JBR 25 / JBR 21）：默认值改动后应用级读到旧值，含重定义后新加载的类；JBR 21 DCEVM：仅重定义前已解析过注解的旧类读旧值 |
| 默认值移除在 DCEVM 下旧句柄 `getDefaultValue()` 读到 `SourceFile` 常量 | 未定位 | 三次独立进程均复现（同机同参数）；重定义后重新取句柄读到 `null`（正确）；成因未定位，已登记 |
| 默认值新增（无 → 有） | 默认拒绝 | 标准 HotSpot 下重定义后加载的裸消费者抛 `IncompleteAnnotationException`；DCEVM 下未见有害；按标准 HotSpot 行为定性，是否放宽另行评估 |
| 未覆盖 | — | 真机 Fabric/Knot 未复跑，读数为 JVM 级；批量"整轮共退"由单测接线覆盖（动态单类路径已端到端）；结论为已定位 + fail-closed 防护 |
