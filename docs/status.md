# HotSwap 模块实现状态总表 (Implementation Status & Audit)

> **状态信息管理规则（强制）**：  
> 本文件为 HotSwap 全模块实现状态、技术债务与验收审计的 **唯一真相源 (Single Source of Truth)**（设计规则与代码地图见 [`AGENTS.md`](../AGENTS.md)，本文件为唯一状态载体）。  
> **严禁在 Javadoc、代码注释以及 `docs/` 设计规范文档中重新混入易腐的实现状态信息**（如 `✅`、`[已实现]`、`[未实现]` 等，由 CI 任务 `verifyDocStatusMarkers` 自动化守卫）。  
> 各设计规范文档仅负责定义客观的架构规格、设计原理与交互契约；实现状态若有变更，统一在此登记与审计。

| 审计元数据项 | 内容与规格说明 |
|:---|:---|
| **基准提交 (Baseline Commit)** | `v0.2.8` tag `f18851e`（annotated → commit `43fc583`，`main` @ `43fc583`）；上一发布 `v0.2.7` tag `0852506`，其代码基线 `db65bf0`；再上一发布 `v0.2.6` tag `2159d21`。v0.2.8 内容：前线 P0 安全门收口（枚举顺序门被 Mixin 对齐器抢跑、枚举常量集合变更假成功、读者扫描被无关常量池污染、`@RemoldReinit` 契约）、运行期日志英文化 + `verifyLogLanguage`、gradle-plugin agent 版本对账 + `cleanRemoldCache`、孤儿 lambda 保留旧体、`redefineDynamic` 接入两阶段、Linux aarch64 原生库、发版坐标自检大小写敏感（逐项见 [`releases/v0.2.8.md`](releases/v0.2.8.md)） |
| **发布说明** | `v0.2.8` **已发布 2026-11-12**：[GitHub release](https://github.com/I-hope1/Remold/releases/tag/v0.2.8)（正文 [`releases/v0.2.8.md`](releases/v0.2.8.md)；JitPack 与 Gradle Plugin Portal 均已验证可解析）；上一发布 [`releases/v0.2.7`](https://github.com/I-hope1/Remold/releases/tag/v0.2.7)（历史档见 `releases/`） |
| **最后更新时间** | 2026-11-25（UI 元素源码视图 `UiSource` 调试入口，见 §6）；此前 2026-11-22（Kotlin 热更硬化战役 `battle-20261122151645-4c73`：宿主名后缀匿名类族改为整族 fail-closed 并修掉注入路径空键 NPE（新风险 32）；`@JvmField` / 静态字段初值不回填按设计边界登记（新风险 33）；此前 2026-11-21（v0.2.9 硬化线合入（PR #56）：redefine 入口合法性门禁（魔数/常量池/`major`/类名/ASM 全结构）与仓库级裸调用点守卫、`pending` 与 `loadedClassesMap` 按 `(scope, name)` 隔离、`sourceBytecodeCache` 按轮次 staged/commit；§5 拆为 5.1 活跃待修债务 / 5.2 已接受物理边界，风险 13/15/16 由「待修」改为「已落地 + 残余面」；`V3 S41` 登记为风险 31。此前 2026-11-21（发布链补齐 `plugin-mc-toast`：`release.yml` 与 `ci.yml` 的插件打包循环此前只含 `arc mixin forge fabric`，而 `RemoldConfigResolver` 已对 Fabric/Forge 默认自动加 `mc-toast`，导致下一版 release assets 缺该插件、仓库外用户落到 JitPack 兜底；同时把 `plugin-mc-toast` 登记进状态表与代码地图、清掉模板行里已被删除的 `com/example/client/*` 与 `HotSwapToastModelTest` 描述）。此前 2026-11-14（完成 **G0** 发布合规与真配置缓存改造 + `remold.attach(...)`：`gradle-plugin` 的 `com.gradle.plugin-publish` 升至 `2.2.1` 并声明 `compatibility { features { configurationCache = true } }`，重构 `RemoldPlugin` / 新增 `GenerateIdeaRunConfigTask`、`GenerateRemoldScriptTask`、`StartRemoldTask` 及延后本地同步，修复 `agentJar` 相对路径与构建输出目录配置期 `exists()` 失效，新增 `remold.attach(...)` 支持既有 `JavaExec` / `Test` / `TaskProvider` / 运行器注入；`validatePlugins` 与 TestKit `--configuration-cache` 全绿，§7 升为 `✅`，原 `§0.12`（现归档于 [`releases/historical-rounds-archive.md`](releases/historical-rounds-archive.md)）错链统一改指 [`releases/v0.2.8.md`](releases/v0.2.8.md)；此前 2026-11-13 为路线图重排：§4.1 合并"失效形态"排序、新增 §4.1.2 范围冻结与 §4.1.3 停车清单、§4.2 优先级重排、§7 门户口径纠偏、§5 风险 13/15/16/20 定级） |
| **审计负责人 / 角色** | HotSwap 架构守卫小组 & 自动化回归验证套件 |
| **更新触发条件** | 任何合入包含特性落地、架构偏离、安全门调整、测试基线变更或发现已知缺陷/残余风险的 PR/commit 时，必须同步原子化更新本文件。提交时由作者/维护者更新基准哈希。 |
| **管辖范围与边界声明** | 本状态表权威管辖：<br>1. **匿名类拓扑对齐与安全门 (Anonymous Class Alignment & Safety Gates)**（[`docs/topology/`](topology/)）<br>2. **存量初始化修复器 (InitFix / Legacy Instance & Static Reinitialization)**（[`docs/initfix/`](initfix/)）<br>3. **物理内存偏移稳定性与第三方缓存驱逐 (Physical Memory Offset Stability & Reflection Cache Eviction)**（[`docs/offset/`](offset/)）<br>*边界与后续治理声明*：同属字节码重映射体系的 `LambdaAligner`（Lambda 表达式对齐器）以及 Mindustry UI 胶水层（`CellPropertyRef` / 动态参数表达式指纹），其实现状态当前由各自单元测试覆盖（`CellHelperTest` 35 个用例持续全绿、`LambdaAlignerTest` 等）；它们的详细状态审计矩阵尚未并入本总表，计划在下一轮治理中统一建立验收标准并纳入。 |

---

## 0.0 唯一真相源契约（文档类型 × 可写内容）

| 文档类型 | 允许写什么 | 禁止写什么 |
|:---|:---|:---|
| **本文件 `docs/status.md`** | 机制/能力状态、验证证据、风险、路线图 | 缺陷修复记录（现场形态、根因、复现步骤只进 `CHANGELOG.md`；设计契约进规格文档） |
| **设计规格**（`docs/topology` · `initfix` · `offset` · `ecosystem` · `mixin`、`HOTSWAP.md`） | 客观规格、契约、判据、已知边界 | 交付状态散文（`已实现`/`未实现`/`待实现`/`已落地`/`已上线`/`待评审`/`已收口`/`未接线`…）与 `✅/⬜/🔶` 标记 |
| **实测记录 / 审计记录**（`initfix/07`、`initfix/08`、`runtime/04`、`neoforge/01`） | 一次性读数与证据（可腐化） | 当前实现状态断言；横幅必须点名本文件 |
| **决策记录**（`topology/08`、`mixin/08`、`initfix/06`） | 决策、否决理由、历史结论 | 状态标记 |
| `CHANGELOG.md` / `docs/releases/` / `docs/acceptance/` | 发布历史、验收清单结果、缺陷修复明细 | 当前状态（以本文件为准） |
| **代码 Javadoc / 注释** | 契约、边界、不变量、运行时语义 | 交付状态；写「不在当前范围」并指向本文件 |

**守卫**：`./gradlew :tests:verifyStatusSingleSource`（挂 `check`）——文档类型横幅缺失、设计规格/独立子项目规格出现状态散文或状态标记、记录类文档横幅未点名本文件、主源码出现交付状态散文，全部直接失败。

**§1～§7 登记边界**：只登记**机制/能力状态**（行 = 稳定的机制/能力，不是一次缺陷）；缺陷修复的现场形态、根因与复现步骤只进 `CHANGELOG.md`，设计契约进 `docs/` 规格，未闭环的残余写进 §5 风险或 §4.2 债务。

**历史归档边界**：§0.1～§0.17 为逐轮修复记录（历史归档），逐项明细以 [`CHANGELOG.md`](../CHANGELOG.md) 为准；本节不再接受新条目。

## 0. 状态治理与规范文档清理进度 (Status Governance & Documentation Cleansing)

为根治文档易腐性与状态漂移，本项目推行“规范规格与实现状态物理分离”治理方针。当前治理基线如下：

1. **唯一真相源隔离**：`docs/status.md` 作为全模块实现状态的唯一受权载体，彻底解除规范文档与代码注释维护实现进度的负担。
2. **双重验收验证机制**：
   - **自动化机器扫描 (`verifyDocStatusMarkers`)**：纳入主构建验证流程（`./gradlew check` 强制依赖），递归扫描 `docs/initfix/`、`docs/topology/`、`docs/offset/`、`docs/ecosystem/`、`core/src/`、`api/src/`、`plugin-arc/src/`、`plugin-mixin/src/`、`plugin-forge/src/`、`panama/src/`，严格拦截标记 `/[✅❌⬜🔶🟣]|\[(已实现|未实现|部分实现|已偏离)/`。当前状态：**100% 达标全绿**（v0.2.6 发版验证：`verifyDocStatusMarkers` 全绿）。
   - **规范性内容抽检审计**：禁止规范正文出现“目前已支持”、“当前版本尚未实现”等时态陈述，时态陈述一律归入 `status.md`。首轮治理覆盖 15 篇客观设计文档（`docs/topology/` 8 篇、`docs/initfix/` 6 篇、`docs/offset/` 1 篇），人工抽检合格率 100%；此后新增篇目（当前 `docs/topology/` 9 篇、`docs/initfix/` 13 篇、`docs/offset/` 2 篇）沿用同一客观化规则，并由 `verifyDocStatusMarkers` 持续守卫。
   - **文档类型横幅 + 单一真相源守卫（2026-11-13）**：`docs/**` 全部 40 篇文档补齐 `文档类型` 横幅（设计规格 / 实测记录 / 审计记录 / 决策记录 / 索引），并把「唯一真相源」从人工约定升级为机器守卫 `verifyStatusSingleSource`（挂 `check`）：横幅缺失、设计规格出现状态散文/标记、记录类横幅未点名本文件、主源码出现交付状态散文，均直接失败。边界表见 §0.0。
3. **设计规范文档客观化清理清单（首轮治理）**：
   - [`docs/topology/01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) ～ [`08-decisions.md`](topology/08-decisions.md)（共 8 篇）：全部重构为纯架构规格、约束契约与客观原理，易腐进度全部剥离，头部统一指引至 `status.md`。
   - [`docs/initfix/01-safety-gate.md`](initfix/01-safety-gate.md) ～ [`06-decisions.md`](initfix/06-decisions.md)（共 6 篇）：全部完成规格客观化改造，彻底消除进度标记。
   - [`docs/offset/01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md)（首轮 1 篇）：定义物理偏移稳定性与第三方缓存驱逐契约，客观化规格无状态标记；后续新增 [`02-inherited-field-drift-fix.md`](offset/02-inherited-field-drift-fix.md) 记录继承字段漂移修复设计，同一客观化规则适用。
   - [`AGENTS.md`](../AGENTS.md)：仅保留规则、五条不变量、代码地图与测试要求；实现状态、测试计数与验收证据全部外链至本文件。

---

## 0.1 历史轮次与外部归档索引

| 归档内容 | 位置 |
|:--|:--|
| 历史逐轮修复与终验记录（原 `§0.1 – §0.17`） | [`releases/historical-rounds-archive.md`](releases/historical-rounds-archive.md) |
| 各版本发布说明（`v0.2.2 – v0.2.8`） | [`releases/`](releases/) |
| 各战役结案战报与实证台账（33 篇） | [`battles/README.md`](battles/README.md) |
| Mixin 热更专题索引（原 `§0.11` 专题表） | [`docs/index.md`](index.md#26-mixin-%E7%83%AD%E6%9B%B4%E5%AD%90%E7%B3%BB%E7%BB%9F-mixin-hot-reload) / [`docs/mixin/`](mixin/01-invariants-and-classloader-authority.md) |
| 逐版本完整变更流水 | [`CHANGELOG.md`](../CHANGELOG.md) |

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
| 枚举常量声明顺序/集合安全门与 Switch 级联阻断 | ✅ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1 | `ClassDiffUtil` / `LayoutGate` / `LayoutDecisionEngine` / `RemoldAgent` / `MixinMemberAlign` | 拦截枚举常量重排/前插导致的 ordinal 漂移（`CHANGED_ENUM_ORDER` 拒绝并提示 revert 或 restart）；末尾追加/删除常量虽不改已有 ordinal，但 JVM 重定义不重跑 `<clinit>`，新增常量运行期恒 null、删除常量仍留在旧 `$VALUES`，同样拒绝（`ENUM_CONSTANT_SET_CHANGED`）；级联阻断本批中持有对应 `$SwitchMap$`/进行 switch 的调用类，以及直接引用变更常量的类，杜绝 switch 静默错位与延迟 `NoSuchFieldError`；**顺序判定之前不得有成员表重写**：`MixinMemberAlign` 只对 Mixin 生成成员生效，普通枚举的 `$VALUES` 等 JVM 合成字段不再触发对齐，且枚举类字段表不重排、被删常量不复活（契约见 [`mixin/03`](mixin/03-reweave-and-member-alignment.md) §2）；`EnumSwitchReorderGateTest`（9 用例，含"追加拒绝原因必须点名实测阻断项"的文案守卫）+ `EnumAlignmentGateWiringTest`（8 用例，含错位对齐的红测试与变异击杀）；**追加/删除常量不做运行期修复**：JBR 21 真机探针证明"重写 `$VALUES` + 清两个 `Class` 枚举缓存"必要但不充分（JIT 折叠 / `$SwitchMap$` 无法自动补新 case / 存量 `EnumMap`·`EnumSet` 无法原地扩容；后两项补测为"可修但有覆盖与竞态前提"），读数见 [`10-enum-constant-append-infeasibility.md`](runtime/04-enum-constant-append-infeasibility.md)、决策 [`D-ENUM-1`](topology/08-decisions.md#d-enum-1)，探针 `tests/enum-append-probe/` |
| Record 组件变更与 `@RemoldReinit` 初值可行性安全门 | ✅ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1 | `ClassDiffUtil` / `LayoutGate` / `LayoutDecisionEngine` / `InitFix.unextractableReinitFields` | 拦截 Record 类新增组件（`RECORD_COMPONENT_ADDED`，有存活实例坚决拒绝，无存活实例放行）；解决 `@RemoldReinit` 授权放行与初值提取切片脱节导致的存量实例静默清零问题：在授权 `WAIVE_REINIT` 前核验切片提取可行性，若为 Record 组件、构造器参数自赋值或无合法初值表达式则拒绝放行（`LAYOUT-REJECT`）；`RecordAndReinitExtractabilityGateTest`（5 用例，覆盖 Record 加组件有/无实例、Record 改类型标 reinit 拒绝、构造器自赋值标 reinit 拒绝、普通类合法初值标 reinit 放行，变异检查通过） |
| 同名局部类编号漂移止血门 | 🔶 | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §2 | `LocalClassGuard` / `RemoldAgent.LOCAL_CLASS_GUARD` | 识别同名局部类计数 >= 2 并将宿主整族移出本轮；**未覆盖“唯一简单名局部类内部的匿名类位移”形态**（见风险 5） |
| 同名局部类完整拓扑对齐 | ⬜ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §2 | 待实现于 `LocalClassGuard` | 路线图规划；规划准入与保留简单名改号；当前由止血门安全拦截 |
| 特性控制开关集合 | ✅ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §4 | `RemoldAgent` 配置表 | 覆盖 `anon_align`（false 为整组移出）、`anon_strict`、`anon_debug`、`anon_layout_gate`、`layout_gate`、`local_class_guard`、`force_reinit`（默认 false，`@RemoldReinit` 忽略指纹强制重放，见 [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §4） |
| 蜕变测试准则 (Metamorphic Suite) | ✅ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §3 | `AnonClassReproJUnitTest.s19_cascadingTreeAndMetamorphicSuite` | 验证标记置换不变性、幂等性、尾部追加不变性与故障注入零静默错配 |
| **[偏离]** 保留内容哈希而不剥离子类引用 | 🟣 | [`08-decisions.md#d-anon-1`](topology/08-decisions.md#d-anon-1) | `AnonClassHasher` | 决策记录 [D-ANON-1](topology/08-decisions.md#d-anon-1)；剥离子类引用会导致同构无参 Runnable（Save/Delete）lambda 指纹相同，引发严重静默对调劫持 |
| **[偏离]** 不引入 `#ANON_COARSE` 粗粒度签名 | 🟣 | [`08-decisions.md#d-anon-2`](topology/08-decisions.md#d-anon-2) | `AnonClassHasher` | 决策记录 [D-ANON-2](topology/08-decisions.md#d-anon-2)；粗粒度签名对同构 Runnable 逐字节相同，使宿主指令流丧失区分度，直接复发 Save/Delete 对调 |
| **[偏离]** 不实现 SwitchMap 排除与原名直通 | 🟣 | [`08-decisions.md#d-anon-3`](topology/08-decisions.md#d-anon-3) | `AnonClassAligner` | 决策记录 [D-ANON-3](topology/08-decisions.md#d-anon-3)；验收证据 `SwitchMapAlignTest` (javac 21)；曾设计“将 SwitchMap 列入排除名录并原名直通”，但论证表明原名直通会在前插枚举时打开槽位篡夺漏洞，因而明确否决并推翻原排除直通设计；当前靠作用域差异与布局门安全拦截，残余机理见风险 2 |
| **[偏离]** 合成捕获字段计入哈希而非剥离 | 🟣 | [`08-decisions.md#d-anon-4`](topology/08-decisions.md#d-anon-4) | `AnonClassHasher` + `LayoutGate` | 决策记录 [D-ANON-4](topology/08-decisions.md#d-anon-4)；JBR 证实捕获字段变更使存活实例读零值；计入哈希以触发 Tier 4 布局门拦截，防止原地破坏 |
| **[偏离]** `access$` 编译器访问器保名不改名 | 🟣 | [`08-decisions.md#d-anon-5`](topology/08-decisions.md#d-anon-5) | `AnonClassAligner.remapClass` | 决策记录 [D-ANON-5](topology/08-decisions.md#d-anon-5)；跨类调用点无法同步修改，改名会导致 NoSuchMethodError；保名可区分不同访问器 |
| **[偏离]** 父类变更一律拒绝并提示重启；**接口增删在增强重定义下放行** | 🟣 | [`08-decisions.md#d-anon-7`](topology/08-decisions.md#d-anon-7) | `RemoldAgent.processChanges` | 决策记录 [D-ANON-7](topology/08-decisions.md#d-anon-7)；父类变更无条件熔断（任何重定义模式都不支持）；**接口增删只在没有增强重定义时拒绝**（`ClassDiffUtil.mustRejectHierarchyChange` 的 `!enhanced` 分支），真机实测（`:tests:hierarchyChangeProbe`）：活类新增接口后存量实例 `instanceof` 即为 true、经新接口调用返回新实现，删除后回到 false，父类变更按名拒绝且类保持可用 |

---

### 1.1 字节码 Changeset 旁路记录（PR1）

设计规格：[`docs/vcs/01-bytecode-changeset-vcs.md`](vcs/01-bytecode-changeset-vcs.md)

| 机制 / 条目 | 状态 | 规范章节 | 落地位置 | 验收证据与决策依据 |
|:---|:---:|:---|:---|:---|
| 逐代元数据旁路记录（loader 弱键槽 + 单调 long 代号、head/history 两级） | ✅ | §2.3 / §2.4 | `core/src/remold/vcs/AlignmentMetaStore.java` | 单测 `AlignmentMetaEquivalenceTest` 6 例；默认 `remold.vcs.enabled=false`，对齐期只读、不参与决策 |
| 离线回放包导出（`-Dremold.vcs.pack_dir`） | ✅ | §5.3 / §7.4 | `core/src/remold/vcs/BytecodePack.java` | E2E `vcsPackProbe` 18/18（真实 agent + DCEVM redefine → 导出 → 逐字节重放 + 关闭记录对照） |
| 批次原子提交判据 | ✅ | §5.3 / §8.1 | `core/src/remold/vcs/VcsCommitGate.java` + `RemoldAgent.commitVcsBatch` | 单测 `VcsCommitGateTest` 5 例真值表；变异"部分生效也提交"→ 对应用例变红 |
| 两阶段只在 Stage C 记一代 | ✅ | §5.3 | `RemoldAgent.processChangesBatch` / `applyTwoPhase` | E2E `vcsTwoPhaseProbe`：真实两阶段批次下 stage A 计数器前进、head generation 精确 +1、存量实例新增字段补成 42；`LAMBDA_STRICT` 拒绝批次 generation 不变 |
| loader GC 后 head 清理的确定性用例 | ✅ | §2.3 | `AlignmentMetaStore.historyEntries()`（按存活槽实时重算） | `AlignmentMetaStoreBudgetTest`：①`WeakReference` 证明 loader 真被回收（store 不持强引用）；②回收后 `historyCount()==0`（无幽灵配额）；③新 loader 的条目不再被死 loader 的配额挤掉。GC 轮询用 `Assumptions` 兜底：JVM 未在窗口内回收时跳过而非假绿 |
| 提交刻度：每 scope 一条 `Changeset` 链（`seq` 单调不复用、`parent` 成链、跨 scope 共享 `roundId`） | ✅ | §2.1.1 / §2.1.2 / §2.1.3 / §3.8 / §6.3 | `core/src/remold/vcs/Changeset.java`、`core/src/remold/vcs/ChangesetLog.java`、`RemoldAgent.commitVcsBatch`、`InitFix.batchCommitManifest` / `batchReplayFacts` | 单测 `ChangesetTickTest` 14 例（aborted 轮次不动 head/seq、`parent` 链不断、同内容异 `seq` 得异 `id`、窗口淘汰、三级可用性与 `NOT_REVERTABLE` 拒绝、空边集仍推进、`manifest` / `replayFacts` 为独立字段、`patchTiming` 按代记录）+ `ReplayFactLiteralTest` 4 例（四档字面量定型，`float`/`double` 存原始位模式）；`VcsCommitGate` 仍只放行整批生效的轮次，aborted 只记诊断计数、不建链节点；窗口 `-Dremold.vcs.changeset_window` 默认 32，取 `8 × 4` 的理由是与 `HISTORY_PER_CLASS = 8` 对齐——3～4 个互依赖类轮流改时不会出现「类字节仍在单类历史上限内、对应 Changeset 先出窗」的错配；`manifest` 与 `replayFacts` 在提交点随 Changeset 一并固化；可用性三级 `ADDRESSABLE`/`REPLAYABLE`/`REVERTABLE` 显式承诺回退能力，达不到 `REVERTABLE` 即拒绝并报 `NOT_REVERTABLE`；`advisory.patchTiming` 记录单阶段 `AFTER_COMMIT` / 两阶段 `BEFORE_COMMIT` 的时序差异，`HEAD` 语义固定为「redefine 已提交」；变异：5 处击杀（默认窗口 32→16 → `windowDefaultsToThirtyTwo`；`NOT_REVERTABLE` 门改恒假 → `availabilityLadderAndNotRevertableRefusal`；meta 摘要去掉集合排序 → `metaDigestIsOrderInsensitiveAndContentSensitive`；id 去掉 `seq` 输入 → `seqIsPartOfTheIdEvenWithAnIdenticalParentAndContent`；窗口淘汰边界 +1 → `windowEvictsOldestAndKeepsHeadAndParent`） |
| `replayFacts` 两侧原始位模式口径（L1 新侧取自字节码常量） | ✅ | §3.8 | `InitializerReplay.ReplayField.newExpected`、`InitFix.l1ReplayFact`、`Changeset.currentAlgoVersion` | 单测 `ReplayFactsFormatTest` 5 例（`-0.0f` 新侧取位模式、NaN payload 不被渲染文本抹掉、十进制新侧可被 kind 判为非规范、整数档两侧仍十进制、`algoVersion` 以 `vcs=2` 标记口径变更）+ `ReplayFactLiteralTest` 4 例保持绿；此前 L1 新侧记十进制文本，浮点字段的多代重放会因 `-0.0`/NaN payload/十进制舍入失真而不可用 |
| 回退执行：区间回退计划、`Eff_0` 基线槽位、前向 revert 与工作头同步（B1） | ✅ | §2.1.1 / §4.1 / §6.3 | `ChangesetLog.revertPlan`·`appendForwardRevert`·`effBytes`、`AlignmentMetaStore.hasGeneration`·`restoreHead`、`RemoldAgent.revertTo` | 单测 `ChangesetRevertTest` 7 例（取 `seq ≤ target` 的最新 Eff、首次进链的类退回 `Eff_0`、字节缺失整体拒绝并点名缺类、目标代未知拒绝、前向 `seq = HEAD+1` 且 `parent` 不断链、计划确定性、区间并集覆盖）；E2E `vcsRevertProbe` 21/21（JBR 21 DCEVM）：两代提交后回退恢复到目标代生效字节、追加 `seq=3` 并记 `revertedToSeq=1`、工作头切回目标代（直接断言 `head.jvmEffSha256 == target.effHash`）、回退后下一次正常热更仍走通；开关关闭与目标代未知均报 `NOT_REVERTABLE` 且不动运行码与链；变异 5 处击杀（去掉 `Eff_0` 兜底、把目标代排除出取数窗口、前向 revert 改回退 `seq`、忽略 `-Dremold.vcs.revert` 开关、回退后不切工作头） |
| 回退执行（B2）：缓存同步 / `Eff_0` 目标显式分流 / 多 scope 原子 | ✅ | 20 §3.4、16 §2.1.1、21 §7 | `RemoldAgent.revertTo`、`ChangesetLog.RevertTarget.kind`、`AlignmentMetaStore.forgetGenerations` | E2E `vcsRevertSyncProbe` 36/0（JBR 21 DCEVM，真实 redefine）：回退后四张 agent 内存表（`bytecodeCache` / `sourceBytecodeCache` / `inventedSlots` / `fileDiskHashes`）对齐目标代——下一轮普通热更把「回退区间里被退掉的那个字段」重新加回时仍按新增字段初始化（存量实例读到 `2 + 5 = 7`，基线被污染时该值不会被写）、带 lambda 的类回退后再热更走通；`Eff_0` 目标走 `forgetGenerations` 而不是 `hasGeneration`/`restoreHead`（目标类在台账里不再有代际身份，下一轮 Run R 落 `NO_ENTRY` 退回 Run E）；两个 scope 时任一 scope 不可满足则整体拒绝——另一个 scope 的字节、工作头与链都不动（旧实现会先回退低 id scope 再抛错）。既有 `vcsRevertProbe` 21/21 与 `ChangesetRevertTest` 7 例保持绿 |
| 代际台账的保留边界（loader / `Class` 不被拖住） | ✅ | 20 §2.4 | `GenerationalFieldLedger`（loader 弱键槽 + 值只存标量） | 单测 `GenerationalFieldLedgerRetentionTest` 3 例：`WeakReference` 到 loader 在 `System.gc()` 后被清且槽计数下降，同时段强可达的对照 scope 行仍在（拒绝「台账本来就不记账」的假绿）；该 loader 定义的 `Class` 只留弱引用时同样被回收；窗口裁剪只丢历史索引、保留未闭环债务 |
| 代际字段台账（存储层）：`GenRef(seq,id)` 索引、两类保留策略、按代作废、非规范口径 fail-closed | ✅ | 20 §2 / §3.4 / §4-R5 | `core/src/remold/vcs/GenerationalFieldLedger.java` | 单测 `GenerationalFieldLedgerTest` 9 例（键=t(scope,内部名,field)+`GenRef`、`seq<=target` 取最新且不越代、scope 隔离、`vcs=1` 口径拒绝且不入索引、`FLOAT_BITS`/`DOUBLE_BITS` 配十进制即拒、规范位模式与整数档放行、窗口淘汰历史索引但**保留未闭环债务**、闭环后随窗口回收、回退后 `seq>target` 的事实与 `REINIT_APPLIED` 整体作废、值内不持 `Class`/`ClassLoader`/字节数组）；台账是**派生索引**：真值为各代 `Changeset.replayFacts`，索引丢失可重建、事实丢失只能拒绝 |
| 代际字段台账（对账语义 + 录制接线）：§3.3 确定性查找、回退代单跳解析、按代作废 | ✅ | 20 §3.2 / §3.3 / §3.4 | `GenerationalFieldLedger.resolve`、`RemoldAgent.recordLedgerFacts` / `revertTo` | 单测 `GenerationalFieldLedgerResolveTest` 7 例（目标代事实 `EXACT` 两侧同源、`rawHash` 恒定时 `CHAINED` 并报来源代、结构变动判 `REFUSED_DISCONTINUOUS` 且不带期望、无事实判 `MISS_NO_FACT`、`id` 不符判 `MISS_WINDOW` 且不顶替代用品、**A14**：回退代单跳解析到 target、期望取 target 的 `oldLiteral` 而非污染代的 `newLiteral`、作废后污染代事实不再可查）；接线：每代提交后逐条事实入索引并按窗口裁剪，前向回退后 `seq > target` 的事实与 `REINIT_APPLIED` 整体作废 |
| 未闭环字段的 fail-closed 对账（无确认写入则不取期望） | ✅ | 20 §4-R3 | `InitFix.gateGenerationalReplay` / `hasAppliedWrite`、`GenerationalFieldLedger.releaseDebt` | 单测 `GenerationalReplayWiringTest.unclosedFieldWithoutAConfirmedWriteRefusesToReconcile`（HEAD 非回退代 + 该键欠债 + 无 `written` 记录 → 候选被丢、报告 `rejected:` 点明原因与恢复路径、`replayGenerated=false`、债务保留）；`BootstrapLedgerSymmetryTest`（bootstrap 槽真被填）；`GenerationalFieldLedgerTest.debtIsReleasedWhenTheFieldIsGone`；变异：去掉该 fail-closed 判定 → 对应用例红 |
| 字段删除时的代际债务出账 + 确认写入按代判据 | ✅ | 20 §2.3 / §4-R3 | `InitFix.releaseRemovedFieldDebt`、`InitFix.hasAppliedWrite(…, generation)`、`GenerationalFieldLedger.releaseDebt` | 单测 `RemovedFieldDebtTest`（结构信号 `diff.changedFields` 的 `- name` 触发、按 `(loader, 声明类, 字段)` 精确释放、同 loader 下另一类同名字段的债务不受影响）+ `GenerationalReplayWiringTest.anOlderConfirmedWriteDoesNotVouchForALaterSkippedGeneration`（旧代的 `written` 记录不得为后续未写成的代背书，报告点明缺写的那一代）；此前的 `releaseDebt` 挂在 `pendingLedger` 重试分支上，L1/A 档债务走不到，且按字段名跨类匹配 |
| 代际对账接进重放决策（消费侧判决门）：`resolve()` 定 `KIND_CONDITIONAL_EXPECTED` 的期望与写入值、跳过落未闭环债务、`OVERWRITE` 闭环、旧口径/非规范事实 fail-closed | ✅ | 20 §3.3 / §3.5 / §4-R5·R6 / A5·A8·A14·G6·G7·G8 | `core/src/remold/vcs/GenerationalReplayGate.java`、`InitFix.gateGenerationalReplay` / `recordReplayOutcome` / `withReplayValue`、`GenerationalFieldLedger.Fact.algoVersion`·`Resolved` | 判决层单测 `GenerationalReplayGateTest` 12 例（目标代事实两侧同源、回退后只用 target 的一对、**无事实判 `OBLIVIOUS` 而不是拒绝**、`MISS_WINDOW`/`REFUSED_DISCONTINUOUS`/`REFUSED_NONE_LITERAL` 三档拒绝、跨口径与描述符不符各一例、`FLOAT_BITS` 位模式精确解码 `-0.0f`(0x80000000)/NaN payload、`Z/B/S/C` 折叠成整字载体、非规范字面量解不出来）；接线单测 `GenerationalReplayWiringTest` 4 例（真实 `InitFix.transform` + 内存 `ChangesetLog`/台账，无 DCEVM：回退上下文里被拒的候选**不进补丁**、`PatchReport.replayFields` 记 `rejected:` 且点名恢复路径、债务落台账；正向代**不被**代际事实顶替——否则期望晚一代导致每轮都不命中）；E2E `vcsA14Probe` 33/33（JBR 21 DCEVM + 真实回退）：回退后同字段再漂移 → 条件 CAS 不命中 → 报告记 `skipped`、warn 点名字段、堆值不动，`@RemoldReinit(OVERWRITE)` 写入后债务 `markClosed` 且报告出现 `recovered: overwrite`；同探针在 `-Dremold.vcs.eff_bytes=0` 下断言第 2 轮真的把 `-0.0f`（`floatToRawIntBits == 0x80000000`）与 `Z/B/S/C` 写进存活实例；变异 9 处击杀（无事实改判拒绝、去掉规范形态/口径/描述符三道门、整段不查台账、期望改回当轮 `ClassDiff`、跳过不落债务、`OVERWRITE` 不闭环、报告不更新运行期结果） |
| 代际台账的真实填充读数（30+30 轮，每轮一条 `L1` 事实，窗口 8） | ✅ | 21 §9 | `tests/src/VcsGcBaselineProbe.java`（`-DvcsGcProbe.ledger=true`）、`:tests:vcsLedgerGcProbe` | 读数与口径/边界见 21 §9：Retained Heap 16.08/16.14/16.17 MB（阶段一）与 16.25/16.26/16.28 MB（阶段二 `eff_bytes=0`），GC Pause 全 0（毫秒分辨率限制）；断言 271/271：30 轮带宽 ≤ 1 MB、前后半段中位差 ≤ 0.5 MB、`accepted` 事实每轮至少 +1（30 轮 5→34 / 35→64）、索引行数按窗口收敛在 8 行而不是按轮数增长、只被 `WeakReference` 持有的 loader 在同一轮 GC 里被回收而强持有的主 loader 仍留 8 行 |


### 1.1.1 活的 Raw↔Raw 对齐（PR3）

设计规格：[`docs/vcs/03-live-raw-to-raw-alignment.md`](vcs/03-live-raw-to-raw-alignment.md)

| 机制 / 条目 | 状态 | 规范章节 | 落地位置 | 验收证据与决策依据 |
|:---|:---:|:---|:---|:---|
| 活体对齐改走 `R_prev ↔ R_new`（Run R），`Eff_prev` 降级为继承来源 | ✅ | §2 / §3.2 | `LambdaAligner.align`（Run R 接线）、`AlignmentResult.baselineTree` / `runRGate` | 默认开启；急停 `-Dremold.agent.lambda_raw_alignment=false`（兼容旧 `-Dremold.poc.meta_source`）一条命令回到旧路径；元数据缺失/闸门不过时按类回退 Run E |
| Raw_prev 字节保管与预算降级（16 MiB 全局 / 每类 256 KiB，超预算丢字节保哈希） | ✅ | §5 | `AlignmentMetaStore`（`rawPrevBytes`、`blobBytes()`、`droppedBlobCount()`） | 降级后 `runRGate=NO_RAW_PREV` + 每类一条 warn，整类回退 Run E（fail-closed） |
| 三道整类准入闸门（G1 代际身份 / G2 源码侧配对 / G3 继承集可枚举） | ✅ | §3.2 | `LambdaAligner`（`RunRGate` 七档读数） | 任一不过即整类回退，禁止部分继承与近邻代顶替 |
| 幽灵桩 / 保留体 / 捕获桥从 `E_prev` 继承（含 `BRIDGE_REPOINT` 目标重指向与命名池占位） | ✅ | §5 | `LambdaAligner` 继承集枚举 + `OrphanBodyRetention` | `Pr3RawAlignmentScenarioTest`（Ctrl+Z 幽灵继承、桥转发桩）；变异：继承集不从 `E_prev` 枚举 → 两例红 |
| 活体路径 loader 传递（`headMatching` 命中正确 loader 槽） | ✅ | §2.3 | `RemoldAgent` → `LambdaAligner.align(…, loader)` | 缺 loader 时元数据永远取不到（只命中 bootstrap 槽），属 PR3 前置缺陷 |

---

### 1.2 字节码 Changeset 派生数据缓存（PR2）

设计规格：[`docs/vcs/01-bytecode-changeset-vcs.md`](vcs/01-bytecode-changeset-vcs.md) §3.7；
实测读数：[`docs/vcs/02-derived-data-cache-probe.md`](vcs/02-derived-data-cache-probe.md)

| 机制 / 条目 | 状态 | 规范章节 | 落地位置 | 验收证据与决策依据 |
|:---|:---:|:---|:---|:---|
| 内容寻址派生缓存（`(kind, SHA-256(blob))`、双预算 LRU、compute-then-publish） | ✅ | §3.2 / §3.7 | `core/src/remold/vcs/DerivedDataCache.java` | 单测 `DerivedDataCacheTest` 8 例：内容寻址命中/种类隔离/条目与字节预算（预算可覆盖）/关闭透传/预计算键与字节键共用条目/未知 blob `peek` 为空；默认 `remold.vcs.derived_cache=true`，关闭时零行为变化 |
| 对齐侧：按 `(blob, 方法 name+desc)` 缓存消费者数据流（`findConsumers` 的 ASM `Analyzer`） | ✅ | §3.7 / §3.5 | `LambdaAligner.consumersOf` / `deriveConsumerTags` / `hasConsumerSite` | 值按**指令流下标**对齐（命中后在本轮新解析出的节点上重建，长度不一致直接退回重算）；`ACTIVE_MUTATION` 非空时旁路缓存；**位点预判**：方法体内无"本类 lambda 调用位点"时既不跑数据流也不入池（实测每类 `align()` median 2.02→1.01 ms、条目 4654→495）；`DerivedDataEquivalenceTest` 6 例（开/关字节相等 + `AlignmentResult` 全字段相等 + 换序遍历 + 关闭不写条目 + 键维度/描述符维度 + 直接调用合成方法的位点） |
| 读者侧：按 blob 缓存类型世界与常量池引用 | ✅ | §3.7 / [`initfix/07`](initfix/07-p05-probe-results.md) §13.6 | `RemovedMemberReaders.describe` / `refs` | `DerivedDataEquivalenceTest.readerFactsCacheIsEquivalent`：`universe()`/`refs()` 开/关逐项相等、pre/post 两世界共用条目（命中数 = 类数）；`ReaderScanProbe` 新增开/关双档同轮读数 |
| InitFix 侧按 blob 缓存字节解析（`InitFix.NestView` 字段写索引 / `HierarchyTreeOracle.readClassBytes`） | ⬜ | §3.7 | 待测量后决定 | 本切片未取到 InitFix 侧读数：对齐侧消融显示"消费者数据流之外的全部派生"只占约 8%，据此先只缓存对齐侧与读者侧；InitFix 侧的解析量级尚未实测，不做无读数的实现 |
| 派生缓存粒度与预算的决定依据 | ✅ | §3.7 | `tests/src/DerivedDataBench.java` | 同轮 min/median/max：`findConsumers` 消融占对齐耗时 89～92%；最终实现下真实批次（旧侧命中）对齐 1.52×（k=8）/1.77×（整语料）、读者侧 2.4×～3.3×；条目预算小于一轮工作集时命中率退化为 0（读数与边界见 [`17-derived-data-cache-probe.md`](vcs/02-derived-data-cache-probe.md)） |

### 1.3 加载前沿与出生水位线（load frontier / birth watermark）

设计规格：[`docs/vcs/07-load-frontier-and-birth-watermark.md`](vcs/07-load-frontier-and-birth-watermark.md)；
实测读数：[`tests/fabric-mixin-probe/LF-PROBE-RESULTS.md`](../tests/fabric-mixin-probe/LF-PROBE-RESULTS.md)
（第二轮：运行期 config 变更 + 未声明类的负对照，见 [`LF-ROUND2-RESULTS.md`](../tests/fabric-mixin-probe/LF-ROUND2-RESULTS.md)）

| 机制 / 条目 | 状态 | 规范章节 | 落地位置 | 验收证据与决策依据 |
|:---|:---:|:---|:---|:---|
| 单调加载水位线 + 有界出生队列（标量三元组、`droppedBirths` / `unresolvedBirths`、`conservativeOnly()`） | ✅ | L2 / L3 | `core/src/remold/vcs/LoadFrontier.java` | `LoadFrontierWatermarkTest`：水位线单调、队列触限进入保守态、`flush` 只取一次、`flushBeforeCommit` 保证提交时计数=1 |
| 出生信息进 Manifest 与回放包（`births=prev..curr`、`algoVersion` 数值 ≥ 3 才参与前置像） | ✅ | L3 | `Changeset.Manifest` / `BytecodePack`（`FORMAT_VERSION=2`） | 旧格式包一律 `REFUSED_LEGACY_PACK`；`births` 缺失/畸形 `REFUSED_MALFORMED`；更高版本 `REFUSED_FUTURE_PACK` |
| 出生点补丁（`PendingLoadPatch`）：不被 Mixin/上游织入字节覆盖、只消费一次、点分/斜杠归一 | ✅ | L4 | `core/src/remold/ref/PendingLoadPatch.java`、`AnnotationTransformer` | `BirthPatchTest` 12 例（含 `AnnotationTransformer` 链路、`APPLIED/EQUAL/REFUSED_NON_PROJECT/REFUSED_WOVEN/REFUSED_MIXIN_TARGET/REFUSED_UNRESOLVED_PAYLOAD`、双向名字形态）；变异：去掉归一化 → 双向用例同时变红 |
| Mixin 目标索引三态（`TARGET` / `NOT_TARGET` / `UNKNOWN`）与发布时序 | ✅ | L6 | `core/src/remold/reflect/mixin/MixinTargetIndex.java`、`plugin-mixin/.../MixinTargetPublisher` | Fabric 1.21.1 实测：mod init 阶段 `available=false size=0`（32 个 config 已就绪）、`isHostReady()` 后 `size=162 stamp=1`；`mixinMapping`/`unhandledTargets` 为点分而 `getTargetClasses()` 为斜杠，归一后两源并集完整；`MixinTargetIndexTest` 含 2000 次发布并发回归（clear-then-fill 变异变红）；第二轮真机读数：运行期改写 `mixins.json` 后 `stamp` 0→3、`size` 0→163、目标 `UNKNOWN`→`TARGET`，未列入任何 config 的 `@Mixin` 类则 `stamp` 不变且目标恒为 `NOT_TARGET` |
| 两级保留：内存裁剪只把结论推向保守，遗忘必须可观测 | ✅ | L5 | `GenerationalFieldLedger.pruneToWindow` / `GenerationalReplayGate.decide(..., prunedFromLedger)` | 裁剪按 `class\u0001field` 计 `forgottenWriters` 并进 `counters()`；生产侧 `InitFix` 两个真实重放点都传裁剪标志，裁剪过的键即使解析成 `MISS_NO_FACT` 也被拒（理由串点名降级为 `OPAQUE_GUARDED`）；`LedgerRetentionTest` + `LedgerResetAndBootstrapSlotTest` |
| 作用域判决时间戳（空集放行可追责、失效可重判） | ✅ | L5 / L6 | `core/src/remold/vcs/ScopeVerdicts.java` | 判决记录 `(subject, watermark, conservativeAtStamp)`，按 loader 隔离含 bootstrap 槽；前沿推进/进入保守态/被裁剪三条判失效，被裁剪返回 `STALE` 而非 `UNKNOWN`；与 `GenerationalFieldLedger` 同源裁剪（`keepWatermark` 取链最老保留代的 `births().prev()`）；`ScopeVerdictsTest` 5 例 |
| 动态重定义自成一代（`dynamic:` 标记，三种状态转移） | ✅ | L0 | `core/src/remold/DynamicVcsCommit.java`、`DynamicRedefineExecutor` | 成功提交一代、失败记中止、no-op 不动 `seq`；字节四元组 `preAlign=source / alignOutput=jvm=runtime / effBefore=old`；`DynamicVcsCommitTest` 5 例 + 导出包 `entryCount=1` 用例；单类批次上下文在 `InitFix.transform` **之前**开启、最外层 `finally` 清理 |
| 偏移索引按加载前沿门控（空索引不再直接关闭漂移门） | ✅ | L2 / L6 | `BatchLayoutCoordinator.checkSuperclassDriftIfApplicable` / `rescanLoadedClassesForOffsets` | 只有 `LoadFrontier.watermark()` 相对上次判空推进过才重扫已加载工程类；`OffsetIndexFrontierRescanTest` 双向用例（推进一次、同水位线不重扫） |
| 工作区 Raw 按需索引（懒查询 + 批内复用 + fail-closed） | ✅ | L4 | `core/src/remold/WorkspaceRawIndex.java`、`BytecodeSources.lookupAll` | 找不到给 `null` + `misses`，多来源给出不同字节判 `ambiguous` 且不猜；批内缓存与 `InitFix` 批次上下文同寿、批末清空；`WorkspaceRawIndexTest` 7 例（含点分/斜杠共用一条缓存、批外不缓存、读数含耗时） |
| 名字形态转换单一入口 + 只减不增守卫 | ✅ | — | `core/src/remold/util/Names.java` | `NameConversionGuardTest` 以存量基线（逐文件计数）冻结现存手写转换：新增文件红、既有文件计数变多变红、基线条目已干净未删行也红 |

#### 1.3.1 Lambda 对齐的内容证据（callee overlap）

| 机制 / 条目 | 状态 | 规范章节 | 落地位置 | 验收证据与决策依据 |
|:---|:---:|:---|:---|:---|
| 被调集合作为跨代配对证据（仅"唯一严格最大正交集"介入，平票退回既有规则） | ✅ | [`docs/lambda/01-capture-bridge.md`](lambda/01-capture-bridge.md) §11 | `core/src/remold/LambdaAligner.java`（`SyntheticInfo.callees` / `uniqueStrictMaxOverlap` / `qualifiesAsEvidence`）、`Pass A` / `Pass B` | `CalleeOverlapEvidenceTest` 11 条（编辑 A 与镜像编辑 B 各选相反候选、平票/零交集/单候选不介入、单条工具调用不算证据、两条工具调用算证据、单条业务调用算证据、样板与 `toString` 边界、合成名不算内容）；`CompeteDeleteTest` 端到端：旧基线从"固化误绑"翻转为"固化已修复"，业务内容断言（`tagA`/`useA` 不得出现）排在结构断言之前 |
| 样板过滤与证据门槛 | ✅ | 同上 | `collectCallees` / `isBoilerplateCallee` / `qualifiesAsEvidence` | 显式列举 `Object.<init>`、`Objects.requireNonNull*`、`Intrinsics*`、装箱拆箱；`Object#toString` 刻意保留；**单条**共享调用须来自非通用库 owner（排除 `java/` `javax/` `jdk/` `sun/` `com/sun/` `kotlin/` `scala/`），**≥2 条**不论 owner |
| 唯一 `PUT:` 字段锚点（同名 `PUT:owner.field desc` 在两侧宿主序列里各只出现一次才成立；重复 `PUT` 不作锚点；两个唯一锚点指向不同字段直接拒绝配对） | ✅ | 同上 §11 | `LambdaAligner.uniquePutAnchor` / `putAnchorConflict` / `uniquePutAnchorPick`（`step2PassA`、`step2`、`fallbackPairingAllowed`、`acceptCandidate`）；`LambdaCaptureBridge.bridgeTargetByPutAnchor` | `PutAnchorPairingTest` 端到端（javac 现编字节对驱动 `align`，不看日志/计数器）：① 字段写位点在产物里仍绑定旧 CallSite 认的名字，且该名字承载原字段的 lambda 体——去掉 `step2PassA` 的锚点择优后同名证据会把写位点改绑到另一个 lambda 的旧槽，用例变红；② 唯一锚点指向不同字段且旧类没有对应字段写时拒绝配对（旧名走 `onOrphanInvoked` ghost、新体取避障名）——去掉 `fallbackPairingAllowed` 的冲突否决即变红；对照组把字段写换成方法实参（无 `PUT` 消费端）后锚点不成立、配对退回同名证据（`callConsumedSwapFallsBackToLegacyEvidence`）。边界：锚点只要求两侧 `hostKey` 相同（`M:`/`L:` 宿主一视同仁），不要求普通方法宿主；`LambdaCaptureBridge` 侧只在多候选歧义时优先于序号消歧，序号消歧的"两侧序列等长"前提不变 |
| disjoint-helper 否决（只对**换名** + 双方本类 private/instance helper 集合完全不相交；同名只在旧 helper 已转为 `this::helper` 方法引用时 ghost） | ✅ | 同上 §11 | `LambdaAligner.collectOwnHelpers` / `disjointSameClassHelpers` / `helperMovedToNewMethodRef`（`fallbackPairingAllowed`，位于 `isOrderConsistent` 之前） | `PutAnchorPairingTest` 端到端：换名的 `helperC` ↔ `helperB` 对被拒——旧名保留为 `onOrphanInvoked` ghost（老 CallSite 响亮熔断）、新体落避障名；同一 fixture 内"换名 + 两侧共享 `audit` helper"的对照仍正常配对（把该否决改成 `if (false)` 后前者的"旧名不许跑新体"断言变红，对照保持绿）。判据口径：`collectOwnHelpers` 的准入是 `private || !static`，因此 **public 实例方法也计入**该集合（刻意的保守：宁可漏配不可错配）；静态包内业务 helper 与外部库调用不计入（Pr3 形态不受影响）；**捕获桥边界（实测）**：ghost 掉的旧名若还带可搬的捕获（实例 lambda 的 `this`），`LambdaCaptureBridge` 会按"宿主相同 + SAM 相同 + 捕获表为该侧唯一子序列嵌入"把它重新指到新方法上（该前提不含语义等价证明），此时旧 CallSite 执行的仍是新体——零捕获（静态）lambda 不建桥，ghost 保持响亮熔断，这也是 `PutAnchorPairingTest` 用静态 lambda 造夹具的原因 |

**残余边界**：外部工程 84 条 Run R / Run E 对照不在本仓库，`V3 S41` 的真机复跑需在外部执行
`RemoldLambdaRegressionTest`（期望 `FAIL=0`）；本仓库锁的是规则的双向对称与一条端到端竞争删除场景。
**残余风险**：`stamp` 类的"次数语义"不适用于本机制；若将来把被调集合扩展到字段访问或常量引用，
需重新评估"单条即证据"的门槛。

**残余边界（详见 §5）**：两轮 Fabric 真机读数均为 **KnotServer 单侧**（无头 runner 无 X 显示，KnotClient 未测）；
`BytecodeSources` 目前只有 Mixin 内部类与新 Mixin 类两个内存来源，
普通工作区磁盘输出目录尚未登记，`WorkspaceRawIndex` 的实际覆盖面因此有限，普通工程类仍靠
`HierarchyTreeOracle` 的资源流兜底；`ScopeVerdicts` 的判决只在"有 Changeset 证据的那段历史"内保留。

---

## 2. 存量初始化修复器 (InitFix)

设计规格文档：[`docs/initfix/`](initfix/) 系列文档  
架构决策记录：[`docs/initfix/06-decisions.md`](initfix/06-decisions.md)

| 机制 / 条目 | 状态 | 规范章节 | 落地位置 | 验收证据与决策依据 |
|:---|:---:|:---|:---|:---|
| 五条核心不变量 | ✅ | [`AGENTS.md`](../AGENTS.md) §2 | `InitFix` 主流程入口与熔断保护 | 未初始化守卫、PENDING 弱键 (5min TTL)、PatchReport 解耦、LinkageError 窄熔断、afterRedefineFailed 立即注销；`initFixOracle` 51 场景 / 350 断言持续守卫 |
| 批次级单次堆遍历（同一批 K 个类的实例收集 / 存活预探测） | ✅ | [`05-jvmti-heap.md`](initfix/05-jvmti-heap.md) §「批次实例收集」 | `LibTool.getInstancesBatch` / `hasLiveInstancesBatch`（native `nGetInstancesBatch` / `nHasLiveInstancesBatch`）；`InitFix.beginBatchInstanceCollection` / `takeBatchInstances`；`RemoldAgent.setLivenessBatch` / `prefetchBatchLiveness` | `initFixBatchScanProbe`：`[0]` 最小复现（4 类×3 实例，单次/切块/重复三种形态，守局部帧生命周期）、`[1]` 真实活实例 `SELF` vs `INCLUDE_SUBCLASSES` 终局断言、`[2]` K=1/4/8/16/64 开/关读数（K=16 collect 145.0→52.4 ms、K=64 632.3→268.9 ms，逐轮交替 21 轮）、`[4]` 布局门 K=64 零实例类 64 次→1 次遍历、`[5]` 20 万无关对象大堆对照（K=64 批 357.9 ms vs 逐类 779.6 ms，结果逐项相等）；`BatchHeapScanEquivalenceTest` 等价门禁（含不在 targets 里的存活子类、分块遍历次数）；`BatchLivenessPrefetchTest` 带去触发对照；变异检查 3/3 击杀（丢子类→多态等价红；批收集退化逐类→遍历次数红；恢复修复前局部帧生命周期→探针 `[0]` 与大堆对照红）。**口径**：实例收集分块上限 16（检索 tag 预算）、存在性探测上限 64；K<8 不接合（一次批遍历 ≈9 倍单类遍历）；`core/libs/*.so` 未随本批重编，旧库走逐类降级 |
| 离线元数据契约 (`ClassHierarchyOracle`) | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §5 | `ClassHierarchyOracle` / `HierarchyTreeOracle` | 全程无类加载；流式字节码扫描；`AnnotationTransformer.HierarchyTree` 底座支撑 |
| 新增字段遮蔽祖先同名字段（DCEVM 迁移复制继承值） | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §3（写入协议）、[`01-safety-gate.md`](initfix/01-safety-gate.md) §1 | `InitFix.detectShadowedAddedFields` / `InitFix.synthesizeZeroValueShadowExtracts` / `ClassHierarchyOracle.findInheritedField` / `HotswapBridge.KIND_CONDITIONAL_ANCESTOR` | 分析期只用字节码元数据按**重定义前**层级定位祖先链上最近的同名同描述符声明（不含被重定义类自身；DCEVM 迁移复制的就是旧层级那条声明的值，按批次新字节码解析会让比较基准随补丁顺序漂移），运行期以该祖先字段在同实例上的读数作为条件 CAS 的期望值：**只有**等于祖先读数才按新初值回写，其余一律跳过并 warn（含槽位恰好等于类型默认值的情形——期望值从来不是类型默认值，那是普通条件写的口径）。**访问权限不是边界**：DCEVM 迁移复制不区分访问权限，`private`/跨包包内祖先照常采纳，运行期祖先读数走完整权限 lookup；只有静态性不符与不可解析的超类链 fail-closed 退回零值 CAS 并 warn，运行期 bootstrap 复核同一口径（链接失败只隔离该字段）。**T0 例外**：遮蔽新增字段不参与 T0 零值等价判定（存量槽位是继承值、不是类型默认值），“只声明不赋值”的形态补一份“压入类型零值 + 写字段”的合成切片，显式写回新初值（可能是 0）。**已知保守边界**：同一批 Stage B 里祖先字段若被 `@RemoldReinit` 先改写成新值，期望值与迁移副本对不上 → 保守跳过（漏补而非误写），刻意不改成强制写。非遮蔽新增字段语义不变。实测 JBR 21.0.8 + DCEVM `:tests:shadowFieldProbe`（三层夹具 `BaseG0 -> MidG1 -> LeafG2` + 匿名子类实例）：`mid.x=200`/`mid.bx=15`/`mid.mx=31`、祖先槽位 `10/10`、`leaf.x=300`、`leaf.lx=540`、`anon.lx=540`，下一轮 `v3m=46`/`v3l=586`/anon `586`；T0 组 `zero.x=0`/`bare.x=0`（祖先仍 10/10）；private 组 `sub.x=200`（私有槽位仍 10）；单测 `ShadowFieldInitFixTest` 22 例；变异检查见 `docs/battles/` 战报 |
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
| 跨类批次字段依赖收敛与拓扑执行（PR4-A） | ✅ | [`04-target-design.md`](initfix/04-target-design.md) §5 | `InitFix.beginBatchBytecodeView` / `setBatchDefinitions` / `finishBatchTransforms` / `convergeBatch` / `orderBatchHosts`；`InitFixBatchGraph`；`HierarchyTreeOracle.readClassBytes`；`RemoldAgent.processChangesBatch` | 同批最终对齐字节按 loader 隔离供离线层级/Nest/作用域分析，候选类缺字节即 fail-closed（不回退全局旧缓存）；跨类依赖按 `(Class, Field)` 构图，读本批新增/待补字段形成边，读旧字段与 T0 零值字段不成边，静态性与真实声明按描述符解析；拒绝按字段精确传播并在剔除后重跑本地闭包，宿主级环/矛盾交错保守拒绝且不影响独立字段；单阶段与两阶段 Stage B 均按宿主拓扑序执行（不拆同类融合循环）；运行期 prerequisite 失败只跳精确下游字段并各自记入 `FieldLedger`。验收：`CrossClassInitFixTest` 6 用例（loader 隔离、候选字节缺失 fail-closed、跨类拒绝连带类内下游、类内依赖不入跨类图）、`InitFixBatchGraphTest` 6 用例（含顺序稳定性与宿主完整性）、`crossClassInitFixProbe` 28/0（JBR 21 DCEVM；单阶段与两阶段各 14 项——逆序输入跨类静态初始化、运行期 prerequisite 失败只跳下游且台账点名前置字段、未初始化宿主静默跳过不牵连下游；两阶段的失败注入走阶段 A 保留的旧方法体）、`twoPhaseProbe` 72/0、`initFixOracle`、`check`；变异 9/17 击杀（战役台账 5/12；两轮评审修复中手工复现 4/5，均恢复原字节）。已击杀：禁用同批 overlay、打乱宿主顺序、去掉运行期失败 token 发布、去掉循环拒绝、去掉跨类边收集、去掉候选字节缺失 fail-closed、禁用跨类拒绝、把类内边灌进跨类图、未初始化宿主重新记账。存活 6/17 集中在三类：重复试探同一变异点、改到不可达 helper、`test_cmd` 未包含目标断言所在文件 |
| 失败策略矩阵 | 🔶 | [`02-closure-and-ledger.md`](initfix/02-closure-and-ledger.md) §3 | `InitFix.runIndependentInstanceTasks` / `InstanceFailureBudget` | 已支持单字段失败跳过、依赖跳过、`LinkageError` 熔断；“放弃提交阶段 C”已随两阶段协议落地（`RemoldAgent.applyTwoPhase` / `redefineStageA`，见 [历史归档 §0.7](releases/historical-rounds-archive.md)） |
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
> - **P1 (High)**：v0.2.9 必须收口——对应失效形态为"静默错值 / 进程级崩溃 / 环境完全不可用 / 有声拒绝需重启"；
> - **P2 (Medium)**：v0.3 及之后演进，具备明确设计规格或已可量化的工作量；
> - **P3 (Low / Long-term)**：长尾兼容与渐进增强，视外部环境或用户诉求按需引入；
> - **停车 / 关闭**：明确不做或条目作废，统一移入 §4.1.3，不再以 P2/P3 常驻误导维护者。
>
> **排序原则（2026-11-13 重排）**：
> 1. 按**失效形态**排，不按架构优雅度：静默错值 > VM 级崩溃 > 环境断代完全不可用 > 有声拒绝需重启 > 性能回落 > 清理；
> 2. 同档按**触发频率 × 用户群体**：Kotlin / Forge / NeoForge 主线用户优先；无用户证据的只做 spike，不做全量；
> 3. **观测先于优化**：缺真实读数（批次规模 N、redefine 后吞吐恢复曲线、降级会话数）的性能与体验改造不得进 P1；
> 4. **发布合规单独成门禁（G0）**：门户审核、坐标与文档口径不一致属发版阻断，不占功能槽位。

| 规划条目 | 优先级 | 版本槽位 | 规范章节 | 前置依赖 | 目标价值与关联问题 |
|:---|:---:|:---:|:---|:---|:---|
| **`@HotswapInit` T4 逃生口** | P1 | v0.2.9 ① | [`09-hotswapinit-design.md`](initfix/09-hotswapinit-design.md) §2~§4 | `InitFix.buildPatch` 候选集段；复用现有写协议 | 让 T3 拒绝的初始化式（Kotlin `?.`/`?:`、读配置、跨类依赖）由用户提供值，不再永久停在类型默认值；只换值来源，不换作用域与写协议。验收：三形态红→绿、T3 拒绝路径不放宽、变异检查、Kotlin 真产物夹具同批补 |
| **入口字节码合法性门禁** | P1 | v0.2.9 ② | §5 风险 13；[`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §1 | 无 | 在 `applyRedefinitions` 前校验 magic / class major / 二进制名 / 可解析性：把风险 13 的 VM 级 `EXCEPTION_ACCESS_VIOLATION` 降为可报告拒绝，并顺带闭环"未知 class major 显式拒绝"（原 P3）。必须带负向用例 |
| **死代码与夹具清理** | P1 | v0.2.9 ② | §4.2 | 无 | 移除 `AnonClassAligner` 的 `tier5Matches` 死字段与 `case 5` 分支；补 `maxLevel > 4 + strict` 负向夹具 |
| **PatchPlan 基线指纹校验** | P1 | v0.2.9 ③ | [`04-target-design.md`](initfix/04-target-design.md) §2 | 无（`baseClassHash` 在进程内自足） | Target 侧记录当前字节码哈希与分析期基线比对，不匹配即拒绝并重分析，防类漂移热更造成字节码损坏。**不依赖** Analyzer 库化（后者见 §4.1.3） |
| **风险 16：`sourceBytecodeCache` staged/commit** | P1 | v0.2.9 ③ | [`11-source-bytecode-cache.md`](initfix/11-source-bytecode-cache.md) §9；§5 风险 16 | 与指纹校验同主题，同一批次 | 外部 redefine / retransform 在"写入→回滚"窗口读到未生效字节 → 跳过织入或取旧基线（**语义错判、无声**）；改为按轮次 staged/commit，读方只读已提交版本 |
| **风险 15：pending 键加 ClassLoader 身份** | P1 | v0.2.9 ③ | [`03-cascading-pipeline.md`](topology/03-cascading-pipeline.md)；§5 风险 15 | 已有 `PendingAlignedMultiLoaderProbe` | 多 loader 串用对齐字节导致布局错配（现状 `[KNOWN-BUG]`）；键改为 `(loader 身份, 类名)` + 消费时校验 + 回归 |
| **`redefineDynamic` 看门狗 + `plugin-forge.isHostReady()`** | P1 | v0.2.9 ④ | [历史归档 §0.10](releases/historical-rounds-archive.md) 残余 1/2 | 无 | 单类 API 入口卡死没有 `[HOTSWAP-STALL]` 线程栈；Forge 缺就绪门闩（默认 fail-open）。两处都是几十行改动 |
| **真机验收：KnotServer/客户端 + 模板场景 1–8** | P1 | v0.2.9 ④ | [`acceptance/01-manual-checklist.md`](acceptance/01-manual-checklist.md) | 真实 Fabric 1.21.1 客户端（维护者执行） | `HostReadinessProbe` 的 `getTicks`/`world` 访问器名只有替身证据；人工验收结果列当前为空，是发布门槛 |
| **NeoForge 生产 installer 启动路径验证** | P1 | v0.2.9 ⑤ | §6 `plugin-forge` 行 | 探针 | dev（ModDevGradle 目录 classpath）已证；生产 installer（`unix_args.txt`、模块层归属）未证，JPMS `--add-opens` 需求必须在生产路径重测 |
| **观测包：批次 N + 吞吐曲线 + 降级会话数** | P1 | 持续（v0.2.9 起） | [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §10–§11 | `HotswapBatchListener` / `applyPatch` 日志 / `[VM-INFO]` | 决定"单遍历按类分发""deopt 回落对策""`remold-launcher` 是否升 P1"；只采集数据，不改产品代码 |
| **NeoForge 26.x / FML 10 代际适配** | P2 | v0.3（先 spike） | `plugin-forge`；原 §4.1.1 条目已并入本节 | FML 10 源码与 `net.neoforged.fml.startup.*` 入口 | 上游已移除 ModLauncher，现有跨 ClassLoader 桥不适用 = 该代际完全不可用；先量化工作量再决定是否全量（避免无用户证据的长期投入） |
| **`ForgeMixinHotSwap` 接入内部类热更闭环** | P2 | v0.3 | [历史归档 §0.10](releases/historical-rounds-archive.md) 残余 8；[`mixin/08`](mixin/08-decisions-and-empirical-ledger.md) | 已有 Fabric 侧闭环可参照 | 拉平 Forge 与 Fabric 的 Mixin 匿名/内部类能力（含覆盖层与事务共退） |
| **跨类批次拓扑排序** | ✅ | 已交付（见 §2 行「跨类批次字段依赖收敛与拓扑执行（PR4-A）」） | [`04-target-design.md`](initfix/04-target-design.md) §5 | 跨类字段依赖图构建器 | 类 X 新增字段被类 Y 初始化式依赖时的跨类事务顺序（含单类内部拓扑）；宿主级环与矛盾交错保守拒绝、独立字段仍可补；运行期 prerequisite 失败只跳精确下游 |
| **对齐盲区收敛包** | P2 | v0.3 | 风险 2/3/5；[`03-cascading-pipeline.md`](topology/03-cascading-pipeline.md) §2、[`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §1、[`09-lambda-capture-bridge.md`](lambda/01-capture-bridge.md) §6 | 无 | 同批收敛六处同源盲区：非方法上下文三态标签、同名局部类完整对齐、Tier 1.5 重叠评估（与 Tier 3.5 重复则撤销）、夹具 L 保守代价、`willForward` 转发桩被熔断器先炸、`BODY_MISMATCH` 去重键缺轮次 |
| **反射与元数据漂移补齐** | P2 | v0.3 | [历史归档 §0.16/§0.17](releases/historical-rounds-archive.md) / 风险 10 | 无 | Gson/Jackson 真实缓存驱逐前后对照、`EvictionScope.WITH_DESCENDANTS` 评估、类级泛型签名漂移告警、`HashSemantics` 子类读父类新增字段扫描、`AnnotationDefault` 移除读 `SourceFile` 成因定位 |
| **Mixin `schemaHash` 新合成类名方案** | P2 | v0.3（`[假设]` 先验） | [`mixin/08`](mixin/08-decisions-and-empirical-ledger.md) §4 | 无 | 捕获列表变化时用 `defineClass` 新类名 + 宿主 body-only redefine，验证 `newlyNull=0` 后打破"改 Mixin 匿名类捕获必须重启" |
| **增强重定义四盲区验证与防护** | P2 | v0.3 | [`08-decisions.md`](topology/08-decisions.md) 附录 A | 专用探针套件 | JIT 内联观察、`volatile`/`final` 复杂修饰符、继承跨层遮蔽、JBR 25 复验 |
| **Kotlin 真实产物夹具** | P2 | v0.3（T4 配套） | [`01-safety-gate.md`](initfix/01-safety-gate.md) §2.1；风险 4 | kotlinc 夹具 | 委托属性、协程状态机、内联高阶函数；当前 Kotlin 结论只经 Java 仿真验证 |
| **性能：deopt 吞吐回落对策**（批次单遍历全堆已于 §2 交付） | P2 | v0.3（条件：槽位 ⑩） | §4.2 | 吞吐恢复曲线实测 | 批次实例收集与存活预探测已于 §2 交付（`LibTool.getInstancesBatch`/`hasLiveInstancesBatch`）；保留项仅为观察每次 redefine 导致的 C2 失效恢复曲线 |
| **JBR 运行时自举启动器 (`remold-launcher`)** | P2 | v0.3（由降级会话数定） | 运行环境引导规范 | 增强能力探测 | 自动探测/就绪 JBR 并蹦床重启，砍掉接入门槛；无用户阻塞证据前不占 v0.2.9 槽位 |
| **8 位完整效应掩码** | P3 | T4 之后 | [`01-safety-gate.md`](initfix/01-safety-gate.md) §2.2 | `AliasInterpreter` | T3 误拒精度；T4 已提供合法逃生口，扩静态分析白名单的边际收益下降 |
| **子类引用多重集正交维度 + 夹具 L** | P3 | 见对齐盲区收敛包 | [`08-decisions.md#d-anon-1`](topology/08-decisions.md#d-anon-1) | `AnonClassHasher` 哈希解耦 | 解耦子类改动向父类逐级传导的雪崩退化 |
| **MC 1.21.4+ gametest 探针重跑** | P3 | 按需 | §7 | `tests/fabric-gametest-1.21.4/` | 新版本 MC 覆盖；当前主模板为 1.21.1 |
| **`plugin-arc` 资源热更残余 6 项** | P3 | 按用户反馈 | §6 | 无 | 贴图尺寸/运行期新增贴图/字体/音效/音乐/着色器；属画质级而非崩溃 |
| **`access$NNN` 编号语义指纹** | P3 | 见对齐盲区收敛包 | 风险 3（D-ANON-5） | 无 | Java 11+ Nestmate 已消除绝大多数桥方法，属长尾 |
| **`BytecodeSources` 驱逐上限** | P3 | 按需 | [历史归档 §0.10](releases/historical-rounds-archive.md) 残余 3 | 无 | 常驻内存无上限；键集合跨 reload 稳定但无驱逐策略 |
| **ECJ 编译器矩阵** | P3 | 仅取证 | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §4 | ECJ 夹具 | 无用户证据；先补 `$SWITCH_TABLE$`（惰性方法 + 缓存静态字段）形态实测半天 |
| **新 javac × 旧 `-target` 矩阵** | P3 | 按需 | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §3 | CI 任务 | 交叉编译产物命名与属性 |
| **伴生类 CAS 迁 `VarHandle`** | P3 | 等 JDK 21+ 下限 | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §3 | 运行时下限 | JEP 471 标准 API 取代 `Unsafe` 反射取偏移 |
| **Mixin 证据补齐（F6 四类拆分 / 事实③）** | P3 | 按需 | [`mixin/08`](mixin/08-decisions-and-empirical-ledger.md) §4/§6.6 | 真机四类样本 | 观测缺口，不影响判定 |

#### 4.1.1 已完成项与候选清单 (Closed Items & Candidates)

| 条目 | 状态 | 优先级 | 落地位置 | 说明 |
|:---|:---:|:---:|:---|:---|
| ~~**P0 安全门与差分基础**~~ | ✅ | — | `ClassDiffUtil` / `LayoutGate` / `InitFix` / `RemovedMemberReaders` | 04 §6 原先登记为"已完成"的 P0 批次：参数不可变校验、效应白/黑名单防御、`unsafeReason` 透传与 `NOTHING_TO_PATCH`、合成字段过滤、差分 Oracle 流水线。明细分散在 [`01-safety-gate.md`](initfix/01-safety-gate.md) 与本节各能力行；条目并入本节后，设计文档不再维护状态列。 |
| ~~**匿名类 `{{ ... }}` / 存量字段初值重放（L1 第一版）**~~ | ✅ | — | `remold.ref.InitializerReplay` + `InitFix` + `HotswapBridge.KIND_CONDITIONAL_EXPECTED`；设计见 [`12-L1-existing-initializer-replay.md`](initfix/12-L1-existing-initializer-replay.md) | 默认关（`-Dremold.agent.init_drift.parents` 为空）；白名单父类匿名子类、非 final 基本类型、旧/新初值均为编译期常量时，按**旧初值条件 CAS** 写匿名类自身实例；运行期改过的值跳过；不写 `FieldLedger`；`@RemoldReinit` 优先。验收与变异检查见 [历史归档 §0.5](releases/historical-rounds-archive.md)。未覆盖：具名类已有字段、引用类型、非常量旧值、`final`，以及按匿名类计的堆枚举性能数据。 |
| ~~**Kotlin `apply {}` 静态持有者初值重放（A 档）**~~ | ✅ | — | `remold.ref.ApplyInitializerReplay` + `InitFix`（`applyFields`/`applyReplay$N`）+ `HotswapBridge.KIND_CONDITIONAL_EXPECTED`；设计见 [`13-kotlin-apply-replay.md`](initfix/13-kotlin-apply-replay.md) | 默认关（`-Dremold.agent.init_drift.apply_holders` 为空）；白名单持有者 `<clinit>` 直线形态、唯一 `NEW`/唯一 `PUTSTATIC dest`、目标字段基本类型/非 final/非 synthetic/非 `@RemoldReinit`、旧/新值均为编译期常量时，`GETSTATIC dest` 取回那一个对象后按**旧初值条件 CAS** 写；运行期改过的值跳过，dest null/类型不符 warn 跳过；不写 `FieldLedger`、不改 `patchGenerated`。验收与变异检查见 [历史归档 §0.6](releases/historical-rounds-archive.md)。未覆盖：Kotlin `var` setter、B 档（`<init>`）、C 档（方法体/局部）、引用类型、`final`、非常量、多静态字段共享同一对象。 |
| `probeAll` 聚合任务（Gradle 内置 JVM 探针，不进 `check`） | ✅ | — | `tests/build.gradle` | 显式 `./gradlew probeAll` 全绿：e2eProbe + DCEVM 套件（除 alignBaselineProbe，见任务说明）+ entity/shadow/class-identity/double-brace/retry/inject-conflict/fingerprint/redefineDynamic/pending-multi-loader/retransform-baseline/addField/initFix-fallback/initDrift-noise+perf 全部 PASS；`doLast` 列出外部容器探针脚本（Fabric C3/C5/mm、Forge C5/mm）及证据路径；外部脚本不在 probeAll 内启动。 |
| ~~**Forge/NeoForge `ClassInfo` 成员同步**~~ | ✅ | — | `plugin-forge` `ForgeClassInfoSync`；详见 [历史归档 §0.3](releases/historical-rounds-archive.md) | 2026-11-07 完成 Forge 侧实现与真机 E2E；NeoForge 21.1 亦已红→绿（见下行的已完成项与 [历史归档 §0.4](releases/historical-rounds-archive.md)） |
| ~~**NeoForge Mixin 端到端验证（MDK 探针）**~~ | ✅ | — | `tests/neoforge-mixin-probe/`；详见 [历史归档 §0.4](releases/historical-rounds-archive.md) | NeoForge 21.1.265 dev `runServer` 红→绿（原生 `[[mixins]]`）；**生产 installer 启动路径与 26.x 代际仍开放**（已并入 §4.1 表，v0.2.9 ⑤ / v0.3） |

> **架构指引（v0.2.5 发版备忘，仅文档）**：后续若在命名模块层（如 `MC-BOOTSTRAP`）真的触发模块封装限制，**优先**由 Agent 自身在运行期用 `Instrumentation.redefineModule(ClassInfo.class.getModule(), extraOpens, …)` 补 opens，把命令行 `--add-opens` 诊断降级为兜底手段；不要把它当作首要修复。落地前需要两个前提证据：(1) 目标 JVM（JBR 21/25）上 `redefineModule` 对该模块确实生效（本仓库已有的「运行期破壁」先例是 `PackageUnsealer` 经 `IMPL_LOOKUP`/VarHandle 解除 Package 密封，可作为同类手段的可行性参照，但不是 `redefineModule` 本身的证据）；(2) `redefineModule` 生效后 `ClassInfo` 深反射与 `reload` 全链路在真机上红→绿（不能只断言 `isOpen` 返回 true）。

#### 4.1.2 v0.2.9 范围冻结与验收门禁

> 只冻结"这一版做什么"；条目细节见上表。**G0 是发布门禁，不占功能槽位**；其余槽位按顺序推进，前一项未过验收不并行开工。

| 槽位 | 内容 | 验收口径（fail-closed） |
|:---:|:---|:---|
| **G0** | 发布合规（**本地已收口**）：`com.gradle.plugin-publish` 升至 `2.2.1`（≥ 2.1.0）+ `compatibility { features { configurationCache = true } }` + 真配置缓存改造 + `remold.attach(...)` + 门户口径统一（§7 / [`releases/v0.2.8.md`](releases/v0.2.8.md) / `README.md`） | `validatePlugins` + TestKit `--configuration-cache` 全绿（已过）；下一次发版 `publishPlugins` 门户端无兼容性告警 |
| **①** | `@HotswapInit` T4 | 三形态红→绿、T3 拒绝路径不放宽、变异检查、Kotlin 真产物夹具 |
| **②** | 入口字节码门禁 + 死代码/夹具清理 | 风险 13 负向用例（畸形字节 → 拒绝而非崩 VM）+ `maxLevel > 4 + strict` 负向夹具 |
| **③** | 基线一致性三连（指纹 / 风险 16 / 风险 15） | 外部 redefine + 文件热更同一序列红/绿对照；多 loader 探针 |
| **④** | 真机闭环包（`redefineDynamic` 看门狗 + `plugin-forge.isHostReady()` + KnotServer/客户端 + 模板 1–8） | 单类入口能留下 `[HOTSWAP-STALL]` 栈；门闩真机读数；清单结果列填满 |
| **⑤** | NeoForge 生产 installer 验证 | 生产 installer 起服 + Mixin/ClassInfo 各一条真机证据 |
| **⑩** | 观测包（持续采集） | 日志/读数落盘；结论前先声明样本窗口与统计口径 |

#### 4.1.2.1 外部极限压测带回的待办 (From External Limit Stress-Test)

> 来源：外部工程（JBR 21 + 真实 agent jar，`-Dremold.vcs.enabled=true` / `-Dremold.poc.meta_source=true`）
> 的 38 项极限压测。这里只登记需要跟进的事项，不含实现状态描述。

| 条目 | 结论 | 说明 |
|:---|:---:|:---|
| 批实例切块越界（`nextBatchChunkEnd` 未钳 `total`） | 已修 | 剩余量落在 `(4096, 8192)` 时块尾越过数组末端，生成器只判 `localIndex < localTo`，导致每个实例抛 `ArrayIndexOutOfBoundsException`，连续撞满单字段失败配额后把已补成功的字段当成放弃；融合批退回 `fallbackFusedGroupFrom` 重复条件 CAS。回归锁 `InitFixChunkBoundaryTest`（含 12289/15000/16384 边界），去掉钳位后 3 条断言变红 |
| `V3 S41`：`super::baseM` 跨代幽灵保留旧体（实测 `base:b-v3`，期望 `base:b-v3:L3`） | 配对层已修，残余暂不修 | A/B 实测：Run R 下 `FAIL=1`，Run E（`-Dremold.agent.lambda_raw_alignment=false`）下 `FAIL=0` 且实测输出正确——**根因在 Run R 的配对侧**。因果链：V1→V2 时 Raw 与 Eff 槽位交叉（V2 Raw `$1` 接续 V1 活体 Eff `$0`、V2 Raw `$0` 改名 Eff `$4`）；V2→V3 时 Run R 用 **Raw 侧序号**配对，V2 Raw `$0`（活体在 Eff `$4`）抢走 V3 Raw `$0`（`superLam` 的 `L3` 版本），使占着活体 Eff `$0` 的 V2 Raw `$1` 无对象可配而幽灵化并保留 V2 旧体。修法方向：候选排序必须按**活体 Eff 身份**（经 `rawToEff` 翻译）而非 Raw 序号，且"会制造活体 Eff 空位"的配对必须让位给能保住活体的候选。**后续实测**（callee overlap 证据落地后，含无绑定方法引用形态）：V2→V3 配对已正确（`old lambda$makeAll$1 <- new lambda$makeAll$0`、`:L3` 保留），`FAIL=0`；残余差异落在捕获值/方法引用目标层面，无稳定跨代身份判据，口径见 §5.2 风险 31 |
| `NestView` 跨同文件兄弟类连带失效 | 待优化 | 同 Nest 里有一个嵌套类被 `LayoutGate` 拒绝时，兄弟类的 Nest 扫描因 `batchCandidateMissing` 整体退回 `incomplete`（fail-closed）。方向是安全的（不会错写），但存在误伤：被拒的类本轮<b>不会</b>被重定义，JVM 里跑的就是 `bytecodeCache` 里的旧字节，兄弟类读它"有没有写自己的私有字段"其实合法。需要区分"跨类依赖查询"与"未重定义兄弟类的 Nest 扫描" |
| 64 轮定点迭代上限（66 层嵌套） | 维持现状 | 63 层全绿、66 层打印 4 条 `did not converge in 64 rounds` 并安全退化（最外层幽灵化保留旧体），内层与新实例仍正确收敛 |
| 无 `-g` 下同类型局部捕获重排 | 维持现状 | 信息论边界：无 LVT 时无法区分同类型局部变量的换序，`strict=false` 下按既有语义处理；需要严防时开 `LAMBDA_STRICT` 或由插件强制 `-g` |

#### 4.1.3 停车与关闭清单 (Parked & Retired)

> 停车 = 明确不做，直到出现新的触发条件（用户诉求 / 上游断代 / 成本下降）；关闭 = 条目作废（设计契约已变或判据不成立）。

| 条目 | 结论 | 触发条件 / 理由 |
|:---|:---:|:---|
| **构造器尾部插桩**（`ConstructorTailInjector`） | 关闭 | 两阶段契约（阶段 A 保留新构造器/`<clinit>`）已取代该缝隙；条目作废，仅保留既有单测，不再作为路线图条目 |
| **枚举常量增删的运行期修复** | 停车 | 维持 `D-ENUM-1` 拒绝。补测（`runtime/04` §3.5.1/§3.6.1）证明"机制可做但不可闭环"：读者 deopt 必须覆盖全部已加载类、`Class` 影子副本无法穷举、redefine→遍历/换数组窗口的并发竞态、用户态按 ordinal 索引的数组无法枚举。重开条件见 §5 风险 20 |
| **Tier 1.5 内容相似度** | 待评估 | 与已落地的 Tier 3.5（方法体 token 相似度）＋骨架指纹高度重叠；并入 v0.3 对齐盲区收敛包评估，确认重复则撤销条目 |
| **批次单遍历全堆**（`IterateThroughHeap`） | 已移出（见 §2） | 已于 §2 交付 `LibTool.getInstancesBatch` / `hasLiveInstancesBatch`（K≥8 走批扫描，旧原生库或小批次退回逐类） |
| **外部 IDE-JDWP 通道接入两阶段** | 停车 | JDWP 协议没有第二次 redefine 触发点；`redefineDynamic` 已覆盖主工作流 |
| **Analyzer 独立库化** | 停车 | 构建端预分析出现真实需求再启；PatchPlan 指纹不依赖它（槽位 ③ 在进程内自足） |
| **槽位预留与侧表免漂移 (S2)** | 停车 | 成本 >> 收益；`LayoutPredictor` + `SuperclassDriftGate` 已事后零错阻断 |
| **通用 S3（持有者原地覆写 + C2 逆优化）** | 停车 | 阶段 C 的 deopt 屏障已是更便宜的等价手段（宿主既有静态字段重写已接入） |
| **JDK 8 raw-bits CAS CI 差异验证** | 关闭（改判据） | 运行期下限 JDK 11（DCEVM 产物 v55），JDK 8 只能跑 oracle、测不到产品路径；改为源码守卫（禁止按名探测 `compareAndSetFloat/Double`，一律 raw bits）+ 现有 JDK 矩阵 |
| **Maven Central 分发** | 停车 | JitPack 坐标可用；Central 需 GPG/Portal 验证，不阻塞使用 |
| **mod-tools 消费侧切换** | 停车 | 外部仓库事务，本仓无法提供验收证据 |
| **Android ART（Engine II）** | 独立轨道 | [`HOTSWAP.md`](HOTSWAP.md)；按 AGENTS.md 属独立子项目，不与桌面 JBR 模组线混排 |

### 4.2 技术债与死代码消除清单 (Tech Debt & Code Cleanup)

| 债务 / 清理项 | 优先级 | 影响位置 | 现状分析与消除方案 |
|:---|:---:|:---|:---|
| **`tier5Matches` 残余死字段清理** | P1 · v0.2.9 ② | `AnonClassAligner.AlignStats` / `recordTierMatch` | Tier 5（物理类名盲配）已彻底拔除，但统计结构体中仍残留 `tier5Matches` 字段、日志拼装与 `recordTierMatch` 的 `case 5` 分支；该分支**无任何调用者传入 5**，属可安全移除的死代码，需连同日志字段一并清理。与「嵌套深度 strict 熔断缺夹具」同批做（槽位 ②） |
| **嵌套深度 strict 熔断缺少夹具** | P1 · v0.2.9 ② | `AnonClassAligner.align`（`maxLevel > 4`）/ `RemoldAgent.ANON_STRICT` | 该阈值是**诊断阈值而非能力边界**（真正的闸门是 `MAX_ANON_PER_HOST` 与 `ALIGN_TIMEOUT_MS`）；缺的是「`maxLevel > 4` + strict → 宿主组原子拒绝」的负向夹具，判定逻辑本身无须改动 |
| **JDK 8 raw-bits CAS 自动化 CI 差异验证缺失** | 关闭（改判据） | `HotswapBridge` / `.github/workflows/ci.yml` | 运行期下限是 JDK 11（DCEVM 产物 v55），JDK 8 无法加载产品代码；改为源码守卫（禁止按名探测 `compareAndSetFloat/Double`）+ 现有 JDK 矩阵，见 §4.1.3 |
| **批次内按类重复全堆遍历（已收口）** | 已收口（见 §2） | `LibTool.getInstancesBatch` / `hasLiveInstancesBatch` | §2 已按分块上限（16/64）与 K≥8 阈值交付单次全堆遍历，保留 `remold.LibToolTest#heapScanIncludesSubclassesButNotSuperclasses` 子类不对称语义与旧原生库降级路径 |
| **每次 redefine 会大范围失效已编译代码** | P2（条件：槽位 ⑩） | 实测见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §11 | 对照组实测：redefine 一个**无关类**（无论只改方法体还是新增字段），另一个类的 C2 版本同样在窗口内被 `made not entrant` → 每次热更后进程可能大范围回退到解释/C1 执行、出现预热回落。归因未定（可能是 DCEVM 的全局失效策略），成本真实；需要真实场景测一次热更后的吞吐恢复曲线。该成本按 redefine **次数**计，两阶段的两次 redefine 会把回落代价翻倍 |
| **真实批次规模 N 未知** | P1 · 观测包（槽位 ⑩） | `RemoldAgent`（`HotswapBatchListener` 已暴露批次边界）/ `InitFix.applyPatch`（已记 `count=N` 与 `elapsed`） | 「单遍历按类分发」是否值得，取决于真实批次类数 N 与每类实例数，目前只有合成数据。观测手段已具备，需要的是**几天真实热更样本**，不是再加代码 |
| **连带保留子体（消除保留体混跑）** | P2（条件：出现"旧实例必须完全保留旧语义"的诉求） | `LambdaAligner` 幽灵注入 / `OrphanBodyRetention` | 旧体 indy 引用的嵌套 lambda 被配对更新时，当前策略是"保留旧体 + `warn` 点名混跑风险"（旧实例语义优先，不退回默认值桩）；彻底消除混跑需把子 lambda 也搬成幽灵、不参与本轮配对，属配对-保留联合决策；规格 [`11-orphan-lambda-body-retention.md`](lambda/02-orphan-body-retention.md) §3.4 |

> 关于 `AnonClassHasher.MAX_DEPTH`：该类注释已明确其为**永不生效的历史遗留常量**（`hash` 不递归自调用，三处调用点全传 `depth = 0`，`DeepNestProbe` 实测 depth 8 仍能正确对齐）。保留它是为了不动公开签名 `hash(..., int depth)`，**不应再作为「嵌套深度上限」引用，也不应作为技术债重复登记**。


---

## 5. 已知风险、残余盲区与折衷清单 (Known Residual Risks & Limitations)

本节系统记录热更新基础设施中被评审确认、因工程代价或架构边界有意保留的已知残余风险，统一按六段式元结构规范登记：

### 5.1 活跃待修债务

**分栏判据**：只问一件事——**能不能通过改 Remold 代码修掉**。能，且已有明确动作（补判据 / 补门禁 / 补扫描范围 / 补读数 / 补负例）的，归本栏；本栏的风险都还欠一个可由我们闭环的动作，不等于当前存在静默写入。风险 13 / 15 / 16 正在 v0.2.9 批次修复中，保持"待修"口径。

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

### 风险 7：InstanceTracker 字节码回退可靠性局限
- **风险描述与底层机制**：在无 JVMTI native 环境（如 Android 或纯 Java agent 回退）下，系统退化为基于弱引用表与构造器插桩的 `InstanceTracker`。
- **触发条件**：垃圾回收频繁触发导致弱引用被清理，或者实例通过非受控通道（如 `Unsafe.allocateInstance`、反序列化、未插桩的类加载器）创建。
- **当前缓解措施**：
  1. 桌面端开发环境默认启用 C++ 底座 `LibTool.getInstances`，Native JVMTI 为绝对优先通道；
  2. 回退模式下触发警告，提示开发者存在实例漏检风险。
- **根治与关闭条件**：优先保障 Native JVMTI 运行期环境；为纯 Java 回退通道补充强弱引用分层感知与反序列化挂钩。
- **关联设计规范**：[`docs/initfix/05-jvmti-heap.md`](initfix/05-jvmti-heap.md)。
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

### 风险 13：JBR 25 增强重定义对畸形 `ClassDefinition` 直接崩溃 VM（入口门禁已落地，残余见下）
- **风险描述与底层机制**：`Instrumentation.redefineClasses` 收到畸形/非法 `ClassDefinition`（例如截断的 class 文件、二进制名与目标类不匹配的字节）时，JBR 25.0.2 + `-XX:+AllowEnhancedClassRedefinition` 不是抛 `LinkageError`，而是在 `VM_EnhancedRedefineClasses::do_topological_class_sorting` 触发 `EXCEPTION_ACCESS_VIOLATION`，整个 JVM 终止。`RemoldAgent.applyRedefinitions` 没有对传入字节做格式/类名校验，批量与逐类两条路径都会把字节直接交给 JVM。
- **触发条件**：`applyRedefinitions` 的批量 `redefineClasses` 或逐类兜底收到畸形 `ClassDefinition`。本轮实测两种构造均触发：截断字节（`new byte[]{0x00}`）与“合法 class 文件但二进制名不匹配”。批量与逐类都触发过。
- **已观测证据**：JBR 25.0.2（`jbrsdk_jcef-25.0.2`）两次崩溃，`hs_err` 文件为 `tests/hs_err_pid20676.log`、`tests/hs_err_pid15436.log`（另有一次 `tests/hs_err_pid19664.log`）；栈顶 `V [jvm.dll+0x5fda20] VM_EnhancedRedefineClasses::do_topological_class_sorting+0x270`。这些日志在记录后已删除，未提交。
- **影响**：开发进程直接死亡，且没有 Java 层异常可供上层捕获/回滚；与“补丁生命周期”类问题不同，这是 VM 级故障。
- **当前处置**：**已修**（PR #56）。入口门禁 `RedefineEntryGate`：魔数 / 常量池逐项走到末尾 / `major <= Runtime.version().feature() + 44` / `this_class` 原始字节与目标类名（modified UTF-8 重编码）逐字节比较 / 再用 ASM `ClassReader` 做一次全结构解析；不过则逐类 warn 拒绝、不调 `redefineClasses`，并进 `RoundSkips` 台账。仓库内全部生产 `redefineClasses` 调用点已由 `RedefineEntryGateCoverageTest` 锁死（`plugin-arc` ×2 与 `plugin-mixin` ×1 按 `VALID_BY_CONSTRUCTION` 登记）。回归探针 `InitFixFallbackProbe` 仍在 `Instrumentation` 边界注入失败。
- **候选关闭方向**：在 `applyRedefinitions` 入口校验 `ClassDefinition` 的 class 名与字节可解析性；或把 redefine 调用隔离到可重启的辅助进程（代价高）。需先确认 JBR 25 的触发边界（是否只在批量排序阶段）。
- **优先级**：P1 · v0.2.9（§4.1 槽位 ② 的入口字节码合法性门禁：畸形字节 → 可报告拒绝，而不是进程死亡）。
- **残余面**：① 本机无 JBR 25，VM 崩溃形态未真机复现，门禁只在 Java 边界证明畸形字节进不去；② 被拒类不 pin 旧字节（该类从未进 JVM，磁盘畸形字节下次加载会响亮失败）；③ 截断点正好落在最后一个"未知属性 payload 内"时 ASM 按声明长度跳过。
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

### 风险 15：`pendingAlignedClasses` 只有类名键，多 ClassLoader 串用（P3 → P1 · v0.2.9 已落地，残余见下）
- **风险描述与底层机制**：`AnnotationTransformer.pendingAlignedClasses` 是 `Map<String, byte[]>`，键只有类内部名/点分名，没有 ClassLoader 身份。`AlignmentTransaction.commit()` 对已加载的匿名类仍会保留 pending 条目；该类在宿主 Loader 中 redefine 时不会消费 pending（`classBeingRedefined != null`），条目会留到**另一个 ClassLoader 首次加载同名类**时被 `transform(..., classBeingRedefined=null, ...)` 消费。
- **触发条件**：Loader A 已加载 `Host$1`；对齐事务 commit（pending 留下）；A 的 `Host$1` redefine 成功；Loader B 首次加载同名 `Host$1`。
- **已观测证据**：`PendingAlignedMultiLoaderProbe`（提交 `3061b9c` 之后的修订版）：A 已加载 → commit → `inst.redefineClasses` 成功 → pending 仍在 → B 首次加载返回 `alignedMarker=true`，条目随后被 B 消费；断言标记为 `[KNOWN-BUG]`。
- **影响**：B 的类会拿到 A 宿主对齐后的字段布局/编号，可能与 B 的宿主结构不一致；P3（错误注入/错配，非必然崩溃）。
- **目标环境**：Forge + Mixin；以 Forge 实测的 ClassLoader 身份为准（Fabric 探针只能证明单 JVM 内的类名键冲突，不能替代 Forge 多 loader 实测）。
- **当前处置**：**已修**（PR #56）。`AnnotationTransformer.pendingAlignedClasses`、`RemoldAgent.pendingInvented`、`INVENTED_SLOTS` 全部改为 `scope + '\0' + name` 键（scope 只由 `AlignmentMetaStore.loaderScopeId` 分配，`ANY_SCOPE` 哨兵先精确后兜底）；`loadedClassesMap` 改 `LoadedClassIndex` 按 `(scope, name)` 存，纯类名查询在同 rank 双副本时返回空并打 `[LOADED-NAME-AMBIGUOUS]`；生产写入走 `stageInventedNames(alignScope, …)`、回执 `commitInvented(scope, name)`。公开字段描述符保持不变（`ConcurrentHashMap` / `Map`），旧插件 jar 不会因描述符变化 `NoSuchFieldError`。
- **候选关闭方向**：pending 键改为 `(ClassLoader 身份, 类名)`，或 commit 时对已加载类不登记 pending；消费时校验 loader；补多 loader 回归。
- **残余面**：① VCS 回退路径仍按纯类名写 `inventedSlots`（写入时解析 scope）；② `loadedClassesMap` 的复合写（`putIfAbsent` / `merge` / `compute*`）没有 scope 维度，Javadoc 标注禁用；③ Forge 多 loader 的端到端实测仍是探针缺口。
- **关联设计规范**：`docs/topology/03-cascading-pipeline.md`。
- **关联架构决策**：无。

### 风险 16：`sourceBytecodeCache` 写入到回滚之间的并发窗口（P3 → P1 · v0.2.9 已落地，残余见下）
- **风险描述与底层机制**：`processChanges` 不是 `synchronized`，`AnnotationTransformer.isNoOpExternalRedefine` / `oldBytecode` 读取 `sourceBytecodeCache` 时也不持有同一把锁。本轮源码在匿名类对齐后、门与 redefine 前就写入缓存；未生效类要等 `applyRedefinitions` 后才回滚。这段窗口内其他线程可能读到“尚未生效”的新源码。
- **触发条件**：`processChanges` 正在处理一批类（尤其对齐/布局门/重定义耗时较长）时，外部重定义通道或 `retransformLoaded()` 读取 `sourceBytecodeCache`。
- **后果**：外部重定义 no-op 判定可能把新源码当成已生效基线而跳过织入；旧侧基线可能取到未生效版本。是语义错判，不是内存竞争。
- **当前处置**：**已修**（PR #56）。`sourceBytecodeCache` 改为按轮次 staged → commit：`SourceBytecodeStaging` 维护 `volatile` 不可变已提交快照，读方（`CommittedView`）只读已提交版本；提交在锁内构造新快照再整体替换、暂存先清空；回滚丢弃暂存不留半写条目；事务边界由 `BatchHotswapExecutor` 的轮次开启/收束掌握。证据：`SourceBytecodeCacheStageTest` 四条并发用例（真实线程 + `CountDownLatch`，断言落在字节内容）、既有 `SourceBytecodeCacheTest` 四路径、变异 2 击杀。
- **候选关闭方向**：按设计稿引入 `SourceRound` staged/commit，读取方只读已提交版本；或把 `processChanges` 与读取方统一到同一锁。
- **残余面**：① 原子性证据是间接的——击杀挂在"不可变快照拒绝就地写"，没有并发读线程观察到"旧批或新批、绝不撕裂"；② 多线程同时提交（两个 redefine 事务并发）未测；③ 非事务调用方（首次加载 / 外部重定义 / VCS 回退）仍立即写已提交视图。
- **关联设计规范**：`docs/initfix/11-source-bytecode-cache.md` §9。
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

### 风险 21：懒加载类的旧字节基线可能取到当前磁盘（真匿名类残余）

- **现象与范围**：一个类若在**批次之间**才被 JVM 懒加载（典型：枚举常量体、`@OnReload` 期间首次触发的匿名子类），
  它没有走过注入/重定义路径，`bytecodeCache` / `sourceBytecodeCache` 里都没有它的**旧**字节。批次预热时
  `fetchOriginalBytecode(Class)` 通过资源读盘拿到的是**当前磁盘**字节，于是对齐器看到的 old==new，差异被吞掉。
- **当前处置**：枚举常量体已按独立类直接重定义（字节路由，不依赖旧基线）；真匿名类若基线缺失，对齐不产出
  结果，批次把它移出时写 `ANON-NOT-ALIGNED` 跳过台账并发 `warn` —— 失败响亮，但该类本轮不热更。
- **修复方向**：加载期基线登记（`AnnotationTransformer` 对监控目录下新加载类登记 `(loader, 类名)` 与初始
  字节），无一轮延迟；需处理"新类 vs 重定义"的判定与重入。
- **关联设计规范**：[`02-admission-and-reserved-names.md`](topology/02-admission-and-reserved-names.md) §1.4。

### 风险 24：Run R 下三条「只看得到 R_prev」的口径缺口
- **风险描述与底层机制**：活体对齐切到 `R_prev ↔ R_new` 后，配对基线里不含幽灵桩、保留体与捕获桥（它们只存在于 `E_prev`），因此凡是以「旧侧字节里能找到的东西」为前提的判据，覆盖面在 Run R 下收窄到 `R_prev` 一侧。
- **触发条件**：① `stepGhostRevive`（默认关）在 Run R 下没有幽灵候选可用，等于 no-op；② 无 LVT 的捕获 lambda 形参名扫描只覆盖 `R_prev`，不覆盖 `E_prev` 侧；③ 字符串常量见证表看不到仅存在于 `E_prev` 的幽灵/桥名。
- **当前缓解措施**：① 幽灵集合改由继承集定义（`E_prev` 枚举 + `Meta.ghostHashes` 校正），复活判据不再依赖 `R_prev`；② 缺少 LVT 时按槽号/类型兜底并记 warn，`LAMBDA_STRICT` 门仍可把它升级为硬失败；③ 见证表缺项只降低配对证据强度，落 Run E 或位置兜底的路径均有读数（`positionalFallbacks`）。
- **残余影响**：上述三处在 Run R 下的证据强度低于 Run E；不产生静默写入，但可能让本可复活/本可精确配对的用例退化为兜底。
- **后续验证方式**：`Pr3RawAlignmentScenarioTest` 覆盖 Ctrl+Z 幽灵继承与桥转发；新增「仅 `E_prev` 可见的见证」用例时以 `positionalFallbacks`/`runRGate` 为终局判据。
- **决策依据**：[`18-live-raw-to-raw-alignment.md`](vcs/03-live-raw-to-raw-alignment.md) §5（继承集枚举）与 §6（fail-closed）。

### 风险 26：Raw_prev blob 记账为 O(条目数)
- **风险描述与底层机制**：全局字节预算的维持按条目扫描记账，每类提交一次。
- **触发条件**：单批次类数量很大（数百类以上）且每类都带 blob 时。
- **当前缓解措施**：提交发生在批次收尾而非热路径；每类上限 256 KiB 限制单条贡献，预算默认 16 MiB。
- **残余影响**：大批次提交期有一次性 O(n) 记账开销，未实测绝对耗时，属推断。
- **后续验证方式**：若大批次提交耗时成为瓶颈，改为增量记账（维护累计字节数 + 淘汰队列）。
- **决策依据**：[`18-live-raw-to-raw-alignment.md`](vcs/03-live-raw-to-raw-alignment.md) §5。

### 风险 27：`Raw_prev` blob 预算容量与提交耗时只有单语料读数
- **风险描述与底层机制**：`Raw_prev` blob 以字节形态进元数据，受 16 MiB 全局 / 每类 256 KiB 双预算约束；预算压力下丢字节保哈希，该类当轮回退 Run E。
- **实测读数**：真实两代语料 400 类满载 2,389,448 B（占 16 MiB 的 14.2%）、`droppedBlobCount=0`，平均每类 ≈5.97 KB；64 KiB 对照档掉 5 类（1.3%）并全部落 `NO_RAW_PREV`。`commit` 每类 median 0.30 ms / p90 0.65 ms，400 类一批约 170 ms。读数见 [`19-raw-to-raw-realworld-readings.md`](vcs/04-raw-to-raw-realworld-readings.md) §3。
- **触发条件**：单 classloader 内带 blob 的类数超过约 2,700（按本语料类体积外推，属推断）；游戏工程类体积更大时容量更小。
- **当前缓解措施**：超预算即丢字节保哈希（fail-closed 回退 Run E，每类一条 warn），不会静默用残缺基线配对。
- **残余影响**：超出容量的尾部类享受不到 Run R；`O(entries)` 记账未做受控微基准，只观测到同批内首末四分位耗时比 3.0×~10.0× 且不随条目数单调。
- **后续验证方式**：在真实工程上复测 `droppedBlobCount()`；如需确认记账复杂度，做"受控条目数"微基准（固定类体积、变条目数）。
- **决策依据**：[`18-live-raw-to-raw-alignment.md`](vcs/03-live-raw-to-raw-alignment.md) §5。

### 风险 28：`L1` 继承字段的代际对账依赖声明类在批次里
- **风险描述与底层机制**：`L1` 重放写的是匿名子类实例里的**继承**字段，而 `replayFacts` 的 `ownerClass`、台账键与相邻性判据（`rawHash` 在 `[g, target]` 恒定）都以**声明类**为键。声明类若从未出现在事实那一代的 `Changeset.entries` 里，`isAdjacent` 无法证明"事实仍成立"，消费侧判 `REFUSED_DISCONTINUOUS` 并把字段挂成未闭环债务；回归头再看，本可重放的字段被拒。
- **触发条件**：父类（声明类）字节不变、只有匿名子类的初值块在变的批次——这正是 `L1` 最常见的形态。
- **当前缓解措施**：按 fail-closed 拒绝（不回落"最近活代"事实），`PatchReport` 记 `rejected:` 并点名恢复路径；正向代不查台账（只在回退上下文或已欠债时查），所以这条缺口不会拖慢正常重放。
- **残余影响**：单靠 `L1` 无法把跨代对账用起来；`vcsLedgerGcProbe` 的夹具必须先让声明类在事实那一代里有条目（探针里每次漂移都重编父类）才能产生可对账的事实——也就是说真实工程里这条通道的实际覆盖面取决于"父类是否随同一次重编"。
- **后续验证方式**：给 `isAdjacent` 补一条"声明类缺失即视为不连续"的显式负例（现在由 `REFUSED_DISCONTINUOUS` 间接覆盖）；评估用 `Changeset.manifest` 的类级指纹替代 `entries` 存在性，把判据放宽到"该代确实录过这个类"。
- **决策依据**：[`20-generational-field-ledger.md`](vcs/05-generational-field-ledger.md) §3.5 / §4-R6。

### 风险 29：`@RemoldReinit(OVERWRITE)` 无法闭环被回退污染的继承字段
- **风险描述与底层机制**：回退后 `L1` 字段被挂在台账上未闭环，恢复要求显式重建或 `@RemoldReinit(OVERWRITE)`；但布局门对"`INCLUDE_SUBCLASSES` + 子类初始化器仍在写该继承字段"的注解直接 `LAYOUT-REJECT`（否则会用声明类的值覆盖子类专属初值，且子类赋值不在重放范围内），而 `scope = SELF` 只写宿主自身实例、够不到匿名子类实例。
- **触发条件**：`L1` 通道（匿名子类实例初始化块写继承字段）上的未闭环字段；声明类与子类都保持原形态。
- **当前缓解措施**：拒绝而不是放宽——`vcsA14Probe` 的恢复轮同时做两件事：给声明类打 `@RemoldReinit(OVERWRITE)` **并**让子类初始化块不再写该字段（"显式重建"的字面形态），此时布局门放行、写入真的落到存量实例、债务 `markClosed`、报告出现 `recovered: overwrite`。
- **残余影响**：只想改注解、不动子类初值的用户拿不到恢复路径，只能等下一次结构变化或重启；台账里该字段会一直保持未闭环（可见、不遗忘，但也不再自动重试）。
- **后续验证方式**：若要支持"子类初值不动也能恢复"，需要先设计"按子类分别取期望"的方案（当前 `KIND_CONDITIONAL_EXPECTED` 只有单一期望来源），并在 `ReinitScopeGate` 补对应负例。
- **决策依据**：[`20-generational-field-ledger.md`](vcs/05-generational-field-ledger.md) §3.5、`docs/initfix/12-L1-existing-initializer-replay.md`。

### 风险 32：Kotlin 宿主名后缀匿名类族一律 fail-closed（不做重路由）
- **风险描述与底层机制**：Kotlin 把匿名对象与它的 SAM 闭包命名为 `Host$方法名$1$1`、`Host$方法名$1$1$1`（`-Xsam-conversions=class` 下闭包是真实内部类；默认 indy `-Xsam-conversions=indy` 不产生这一族）。`AnonClassAligner.batchAnonHost` 只剥尾部纯数字链，`isAnonymousClassName` 也只接受纯数字链，于是这一整族被归到一个**不存在的宿主**（`Host$方法名`）名下：对齐时宿主字节永远取不到、匹配只能靠退化证据，末了把新匿名类重新编号注入成幽灵类名，而真宿主照旧按原名重定义。后果就是实测形态：宿主 v2 + 闭包 v1 的部分应用，以及一次注入路径上的 `NullPointerException: Cannot invoke "Object.hashCode()" because "key" is null`（`JarExtractor.tmpToJar()` 是 `ConcurrentHashMap`，注入类没有 `classToPath` 条目时把 `null` 当键传入）。现行为：宿主不可解析即判定为该族，把**真实祖先宿主 + 整族**移出本批并 `warn` 点名两侧宿主与跳过集合（判决码 `ANON-GHOST-HOST`）；`dropHostGroup` 认这一族，使幽灵宿主、歧义、布局门三条拒绝通道对"谁属于这一族"的回答一致；对齐器与事务不再登记无目标类名的条目（空名键不会再进 `bytecodeCache`）。
- **触发条件**：Kotlin 源码中修改、前插或增删匿名对象 / SAM 闭包，且该构建产出宿主名后缀类名；典型如在一个既有 `object : Table()` 前再插一个同超类匿名对象，或在 `label { ... }` 集合里增删一项。
- **当前缓解措施**：整族 fail-closed（旧版本继续运行，摘要行点名被跳过的类），并在同一轮里保证宿主与族成员**同进退**；`KotlinAnonFamilyDropTest` 锁住"族成员必须移出、具名内部类与无关类必须留下"；`kotlinClassModeProbe` 的四个场景（class / classv3 / prepend / control）在 `e2eProbe`（第 2 档）里持续断言"全 v1 或全 v2、无混合态、无 NPE、fresh 调用不链接失败"，`control` 场景是"没有过度拒绝"的负向对照。
- **根治与关闭条件**：为这一族建立可信的归属与身份判据（`EnclosingMethod` / `InnerClasses` 属性 + 方法段层级），使双向唯一配对时可安全重路由、不唯一时仍拒绝；判据落地前不做任何重路由。
- **残余盲区（实测口径）**：真机观测到的 `NoClassDefFoundError` 在仓库夹具中**未复现**——v1/v2 类集相同时崩在这里表现为部分应用（`classv3` 类集增长场景下关掉守卫则呈现混合态，探针仍判 FAIL）；即 NCDFE 分支只有真机读数。另：注入路径的空 `path` 守卫属纵深防御，现有场景击穿不到（变异存活已如实登记，见战役台账）。
- **关联设计规范**：[`docs/topology/04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §4、[`docs/topology/01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) §2。

### 5.2 已接受物理边界

**分栏判据**：同一个问题——**能不能通过改 Remold 代码修掉**——答案是"不能"或"明确不修"的，归本栏：成因在上游（JBR / DCEVM 行为、JDWP 协议）、语言或平台语义（JVM 规范、文件系统时间语义、无绑定方法引用没有稳定的跨代身份）、或修复代价已被否决（全线程暂停、完整抽象解释器、重写既有契约）。判不出归属的条目不硬塞进 5.1：按最近一次实测结论落到本栏并在小节里注明依据。

### 风险 1：实例存活判定 TOCTOU 竞态窗口
- **风险描述与底层机制**：从堆遍历（`LibTool.getInstances`）判定“某类无存活实例”到真正执行 `redefineClasses` 生效之间，存在纳秒至微秒级的时间窗口。
- **触发条件**：应用工作线程在此窗口内并发执行 `new Foo()`。该新实例在重定义生效前分配，持有旧字段布局；重定义后访问新增字段将读取默认零值。
- **当前缓解措施**：
  1. 重定义全程持有 `HOTSWAP_LOCK` 全局互斥锁；
  2. 游戏通常在主循环安全点或调试暂停期触发热更；
  3. 若类已存在已知存活实例，直接进入 InitFix 逐实例补丁队列，不依赖“零实例豁免”。
- **根治与关闭条件**：落地两阶段重定义（内部批次路径已落地，见 [历史归档 §0.7](releases/historical-rounds-archive.md)）；构造器尾部插桩在当前两阶段契约下不再需要（见 §4.1）；或在 HotSwap 期间通过 JVM TI 暂停全部应用线程（代价过高，目前维持极窄竞态记录）。
- **关联设计规范**：[`docs/topology/07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1.3。
- **关联架构决策**：无（属并发系统已知 TOCTOU 折衷）。

### 风险 6：并发实例化缝隙
- **风险描述与底层机制**：存活实例的枚举若发生在重定义**之前**，「枚举完成 → 重定义生效」之间新建的实例就不在集合里；重定义后这些实例的新增字段停在类型默认值，且因为它们不再出现在后续 Diff 中，永远不会被补。
- **触发条件**：热更新期间应用线程仍在分配对象（游戏主循环的常态）。
- **当前缓解措施**：**枚举已后置到重定义生效之后**（`InitFix.applyPatch` → `collectInstancesForPatch`，见 [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §2）。此后不可能再产生旧布局实例，枚举天然完备；枚举期间新建的实例由新构造器初始化，条件 CAS 对它们是无害的空操作。回归断言 `InitFixSnapshotTimingTest`（先红后绿）。
- **残余边界（已关闭）**：两阶段路径下阶段 A 保留新构造器/`<clinit>`（`StageASkeleton.buildWithLiveConstructors`），阶段 A 到阶段 C 之间新建的实例由新构造器直接完成初始化，不再停在默认值；`StageASkeletonTest` 的窗口实例用例（新构造器初始化、旧体不回退到新字段）守卫该点。构造器尾部插桩在当前契约下不再需要（见 §4.1）。
- **根治与关闭条件**：已由两阶段路径的「构造器保留新体」达成（[`04-target-design.md`](initfix/04-target-design.md) §1；原 §4 构造器尾部插桩方案在当前契约下不接线）；外部 IDE/JDWP 通道的同类窗口见风险 9。
- **关联设计规范**：[`docs/initfix/03-runtime-driver.md`](initfix/03-runtime-driver.md) §2、[`docs/initfix/04-target-design.md`](initfix/04-target-design.md) §4。
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
- **已关闭的路径**：内部批次路径（文件监听 → `processChanges`）已由两阶段 Schema-First 重定义关闭（见 [历史归档 §0.7](releases/historical-rounds-archive.md)、路线图 §4.1）。当本批存在真正会写的「新增/类型变更字段」时：阶段 A 提交新结构 + 旧方法体（构造器/`<clinit>` 保留新体），旧方法体在编译期不引用新增字段，因而窗口内不可能读穿；阶段 B 完成补丁后再提交阶段 C 的新方法体。只在本批存在零值暴露字段时触发，纯方法体热更不付出第二次 redefine 的代价。证据：`TwoPhaseProbe` 72/0 的 50k 实例并发读线程红/绿对照（关两阶段稳定 NPE，开两阶段零 NPE）已实测。
- **已关闭的路径（动态单类入口）**：`redefineDynamic` 消费 `InitFix.requiresTwoPhase`，在**调用内部同步**完成 A → 补丁 → C（阶段 C 直接提交现成的 `runtimeBytes`，不重跑 transformer 桥接/`InitFix.transform`），返回时 JVM 已是终态——公开 API 的返回语义不变，插件调用方在返回后看不到阶段 A 中间态。阶段 C 失败时按 VM 级/非 VM 级分流：VM 级标记毒化并停在阶段 A 提示重启；非 VM 级尝试回滚到基线字节并返回 `false`。证据：`DynamicTwoPhaseRedefineTest` 2/2（两次 redefine 的顺序与字节：Stage A 旧体 + 新字段、Stage C 新体；纯方法体仍单次），变异：强制 `twoPhase=false` → 用例红；真机（JBR 21 DCEVM）`redefineDynamicNoBaselineProbe` 13/0，日志显示 `two-phase: A -> patch -> C` 且存量实例 `b=42`。
- **仍敞开的路径（残余）**：
  - **外部 IDE/JDWP 重定义通道**：JVM 先按新字节码完成重定义，Remold 只能在 `afterRedefineExternal`
    里补，窗口仍在。要关窗口必须让 transformer 首次返回阶段 A 骨架、再在补丁完成后主动触发第二次
    redefine；JDWP 协议不提供这个触发点，该通道实际不可达（除非另起触发源）。
  - **补丁被安全门拒绝的字段**：没有补丁就没有可等待的值，两阶段无法代偿，语义仍是「重启」；
  - **阶段 B 完成前的残余读取**：新构造器若读取本类新增**静态**字段，仍可能拿到默认值。跨类部分已由
    跨类批次字段依赖收敛与宿主拓扑执行收口（见 §2 对应行）：同批其它类的 prerequisite 宿主先补、失败时
    精确下游被跳过并记账，但**已被拒绝**的下游字段仍无值可等，语义还是「重启」。
- **窗口量级**（仅对仍敞开的路径有参考意义；实测拆分见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §10）：
  - 枚举成本 = **无关对象约 8 ns/个**（全堆遍历固定项）+ **匹配实例约 0.3~1 µs/个**（打 tag 与物化数组）；
  - 极端场景（目标类自身 100 万实例）：`applyPatch` 约 1551 ms，其中 CAS 仅 83 ms、弱引用往返 389 ms；
  - 现实场景（堆里几百万对象、目标类只有几百个实例）：实测 100 个目标 + 100 万无关对象仅 8 ms，窗口在**几十毫秒**量级；
  - **随批次线性放大的项**：`getInstances` 按类调用，批次里 N 个待补类 = N 次全堆遍历；
  - **暴露时间要扣掉 STW 段**：实测（M6）每次 `getInstances` 是一次 safepoint，1M 匹配实例时 `HeapIterateOperation` 单次 216~242 ms；停世界期间业务线程读不到未补字段。心跳吞吐只有自由速率的 67%，是「一段 STW + 其余阶段并发」的混合，不是全程争抢。
- **触发条件**：窗口内有线程执行读取新增字段的代码——本类新方法体、同批重定义的其它类、反射/序列化路径、任意业务线程。
- **缓解措施**：两阶段路径已由编排消除；残余路径上 `HOTSWAP_LOCK` 只互斥热更调度与事务处理、**不阻塞应用线程读**，因此不构成防护；T0 零值等价字段不受影响（默认值即正确值）；`PatchReport` 与条件 CAS 跳过计数可在事后定位“这次写了哪些字段”，但无法预防窗口内的读。
- **根治与关闭条件**：只剩**外部 IDE/JDWP 重定义通道**——它受"JVM 先完成后置回调"约束，需要在 transformer 里先把新字节改为阶段 A 骨架、再在补丁完成后主动触发阶段 C（JDWP 无该触发点，需另起触发源）。跨类批次拓扑排序已收口（见 §2）；同批内仍无法代偿的是**被安全门拒绝**的字段与上述 JDWP 通道。
- **关联设计规范**：[`docs/initfix/04-target-design.md`](initfix/04-target-design.md) §1、[`docs/initfix/03-runtime-driver.md`](initfix/03-runtime-driver.md) §2。
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

### 风险 20：枚举常量集合变更无法在运行期修复（实测否决候选方案）
- **风险描述**：热更不重跑 `<clinit>`，追加的常量不被构造、`$VALUES` 不扩容，删除的常量仍留在旧 `$VALUES`。
  候选修复是"事后重写 `$VALUES` + 清空 `java.lang.Class` 的 `enumConstants`/`enumConstantDirectory`"。
- **实测结论（已观测，JBR 21）**：候选方案在冷路径成立（`values()`/`valueOf`/新建集合/反序列化都正确），
  两个缓存也必须**都**清且要在 `$VALUES` 之后清；但三类已存在的消费方需要补偿——已 JIT 编译的读者在
  deopt 前仍持有旧常量表；已初始化的 `$SwitchMap$`/`$SWITCH_TABLE$` 持有类无法自动补 case（扩容只消除
  AIOOBE，新 case 静默落 `default`），补测表明按新 holder `<clinit>` 映射重建表可行（`swmap-rebuild`）；
  存量 `EnumMap`/`EnumSet` 实例无法原地扩容（AIOOBE / 迭代陈旧），补测表明逐实例换 `keyUniverse`/`vals`/
  `universe` 可行（`heap-repair`），但需遍历堆全覆盖 + 窗口/并发防护，跨 64 常量边界不可；
  另有 `Class` 影子副本与懒加载旧代码不可闭环。读数与复现见
  [`runtime/04-enum-constant-append-infeasibility.md`](runtime/04-enum-constant-append-infeasibility.md)。
- **触发条件**：枚举类常量集合变化（新增或删除任意常量）。
- **当前处置**：维持 `ENUM_CONSTANT_SET_CHANGED` 拒绝 + 提示重启；拒绝原因点名 `$VALUES`/`$SwitchMap$`/
  `EnumMap`/JIT-deopt 四项阻断，避免用户误以为"只是 InitFix 没实现"。不做运行期补丁。
  `EnumSwitchReorderGateTest.appendedEnumConstantRejectionNamesTheMeasuredBlockers` 锁住该诊断文案。
- **重新评估条件**：三项能力（`$SwitchMap$` 重建、读者 deopt、存量集合逐实例换数组）经补测**均已具备**
  （`runtime/04` §3.5.1 / §3.6.1），但剩余阻断未闭环：必须 deopt **全部**已加载读者（遗漏即静默旧值）、
  `Class` 影子副本无法穷举、redefine→遍历/换数组窗口的并发竞态、用户态按 ordinal 索引的数组无法枚举。
  因此维持拒绝（§4.1.3 停车项）。重开条件改为：出现明确用户诉求**且**能保证失败响亮（拒绝日志/报告）+ 接受覆盖成本，
  只做 opt-in 实验通道。完整清单见该文档 §6。
- **关联设计规范**：`docs/runtime/04-enum-constant-append-infeasibility.md`。
- **关联架构决策**：[`D-ENUM-1`](topology/08-decisions.md#d-enum-1)。
- **关联代码**：`ClassDiffUtil` / `LayoutGate.ENUM_CONSTANT_SET_CHANGED` / `LayoutDecisionEngine.decide` /
  `RemoldAgent.dropEnumSwitchDependents` / `dropEnumConstantDependents`；探针 `tests/enum-append-probe/`。

### 风险 22：`CellPropertyRef` 的程序分析与执行手段深度边界（P2/P3 · 未闭环）

- **范围**：Mindustry UI 胶水层 `CellPropertyRef` 的静态链提取与属性重放。它不参与 InitFix 的判决，
  但决定"热更后 UI 属性链能否被正确重建"。跨类批次映射已按类寻址（契约见
  [`lambda/03-publication-contract.md`](lambda/03-publication-contract.md)）；
  下列边界仍敞开。
- **判定手段偏浅**：属性链提取用原生 `SourceInterpreter`，只接受 `SourceValue.insns.size() == 1` 的来源
  （`extractArgsDataflow`），遇到三元表达式/栈复制即返回 `null`，参数留待运行期 `slotCursor` 按调用序回填；
  提取阶段**不分析宿主方法的 `if`/循环控制流**，分支导致的调用序差异只能靠运行期观测对齐。
  失效形态：分支里同 `(method, desc)` 出现顺序相反时，参数可能被填到另一条链（静默错配，不抛异常）。
- **执行手段偏重**：`verifyBytecode` 用一次性 `ClassLoader.defineClass` 做 JVM 校验（会加载一份重复类，
  仅用于验证注入字节）；`applyChainUpdate` 每次用 `new Table()` 的 dry-run 判定可应用性；
  `LambdaInfo` 用 `Proxy.newProxyInstance` + `MethodHandle.invoke` 逐帧代理。三者都偏"运行期试跑"，
  而非静态效应判定，长期成本与底层精细度未做基准对比。
- **触发条件**：宿主方法含分支/三元/循环、且同签名属性调用在分支两侧顺序不同；或大量 cell 属性热更。
- **当前处置**：只登记，不重写。抽取失败/不可应用的链走既有的 unpatched 台账与 `DEBUG` 日志路径，
  不静默写入；跨类映射与缓存/回调生命周期已按上文收口。
- **重开条件**：出现可复现的 UI 属性错配现场（含最小字节码形态）时，先补控制流判据（分支内同签名站点
  顺序不一致 → 拒绝该链），再评估是否需要完整的抽象解释器。
- **关联代码**：`CellPropertyRef.extractFromMethod` / `extractArgsDataflow` / `verifyBytecode` /
  `dryRunProperties` / `makeLambda`。

### 风险 23：构建完成 marker 的触发边界（P3）

- **风险描述与底层机制**：marker 只能取消**尚未到期**的兜底 task；若一次构建时长超过 `fileShakeMs`
  （默认 800ms）且期间出现超过窗口的静默，编译中途的批次可能先跑一次。marker 到得晚时只补余下变更，
  已生效的类由磁盘指纹跳过——最终状态正确，但"构建期间完全不触发热更"不成立。
- **迟到事件**：marker 批次之后才被 watcher 线程送达的同批事件会重新挂一次兜底；该批次按磁盘指纹全部跳过，
  产生 0 次 redefine 的 no-op 批次（不触发 `@OnReload`/补丁/监听器回调），只带来扫描/读盘与日志成本。
- **扫描依赖文件系统时间**：`marker` 路径用 `mtime >= since` 挑选变更；保留时间戳的复制工具、网络挂载或
  mtime 精度异常的文件系统可能漏扫，此时事件路径仍保证最终一致（只是失去快路径）。
- **平台假设**：陈旧/未来判定（±5s）与"Gradle 与游戏 JVM 同机同时钟"假设在容器/远程挂载下可能不成立；
  被拒绝的 marker 只在日志里给出原因与 `delta`，不会自动关闭 marker——用户可设 `buildTrigger = false` 回到纯兜底。
- **未覆盖的验证面**：JBR 17/21 DCEVM、Linux、macOS 的高灵敏度 watcher 路径、多类/匿名类/lambda 批次、
  多次连续构建的长期稳定性。
- **关联设计规范**：`docs/runtime/01-build-trigger-mode.md`；读数：`docs/runtime/02-build-trigger-probe.md`。

### 风险 25：`forceStaticLambdas` 归一化与 Run R 的闸门关系（按实测更正）
- **风险描述与底层机制**：`normalizeAlignBaseline` / `forceStaticLambdas` 会在对齐前重写 lambda 调用位点；一度担心这类类的 `E_prev_used` 与 `Meta.jvmEffHash` 不同源，导致 G1 代际身份判不出而整类回退 Run E。
- **实测结论**：该担心不成立。`preAlign` 快照取自归一化**之后**，两侧指纹同源；真实两代语料 400 类里命中归一化的 7 类**全部 `PASSED`**，全语料 `RAW_PREV_MISMATCH` 为 0。读数见 [`19-raw-to-raw-realworld-readings.md`](vcs/04-raw-to-raw-realworld-readings.md) §2。
- **仍然成立的边界**：真正压低 Run R 接合率的是"没有本地 lambda 槽位"的类（只有外部方法引用）——它们判 `TRANSLATION_INCOMPLETE` 并回退；含 lambda 站点的类里接合率为 50/62（80.6%）。
- **当前缓解措施**：不需要额外缓解；G1 维持"只比 `E_prev` 指纹"的单校验和设计，不做转换前后双哈希。
- **残余影响**：无（该风险项按读数关闭）。若将来把 `preAlign` 快照挪到归一化之前，本结论立即失效，必须重跑 §2 的读数。
- **后续验证方式**：`bash gradlew :tests:pr3RealWorldReadings`（需两代语料时用 `-Ppr3.corpus.old/new`）。
- **决策依据**：[`18-live-raw-to-raw-alignment.md`](vcs/03-live-raw-to-raw-alignment.md) §3.2；读数 [`19-raw-to-raw-realworld-readings.md`](vcs/04-raw-to-raw-realworld-readings.md)。

### 风险 30：JBR DCEVM 同名遮蔽字段的槽位污染（上游，不可修）

- **风险描述与底层机制**：子类声明与父类同名同描述符的字段（字段遮蔽）后，下一轮**结构性**重定义
  （子类新增字段）会把最派生声明的值按字段名写进所有同名槽位——**重定义之前就存在的实例**上，
  父类槽位里躺着子类的值。纯 JBR 21.0.8 + `Instrumentation.redefineClasses`、零 Remold 代码即可复现；
  父类槽位内容本身被改写（方法体、外部 `getfield`、反射、`Unsafe` 直读四条路径一致）。
- **触发条件**：① 父子类有同名同描述符实例字段；② 本轮重定义为子类新增字段（无新增字段的重复重定义不触发）；
  ③ 实例在该轮重定义之前创建（重定义之后新建的实例正常）。改名去掉同名后不再发生。
- **当前缓解措施**：InitFix 对新增遮蔽字段仍按源码初值写子类槽位（[`docs/status.md`](status.md) §4、
  [`initfix/03-runtime-driver.md`](initfix/03-runtime-driver.md) §1），并在采纳遮蔽字段时打一条
  `[SHADOW-FIELD]` 告警，明确建议避免跨热更边界的同名遮蔽或重命名字段。**父类槽位不修**：
  成因在 relayout 而非补丁，「快照父槽再回填」属于修改已有字段值，按 [`AGENTS.md`](../AGENTS.md) §1
  需显式通道，当前未实现。
- **残余影响**：用户若坚持同名遮蔽，重定义后父类读取路径可能返回子类的值（静默错值），Remold 不阻断、
  只告警；表现与是否开启 InitFix 无关。
- **读数与复现**：[`docs/runtime/05-jbr-dcevm-shadowed-field-slot-pollution.md`](runtime/05-jbr-dcevm-shadowed-field-slot-pollution.md)；
  repro 工程 `tests/jbr-dcevm-shadowed-field/`（不含 Remold 代码，可直接作为上游 Issue 的最小用例）。
- **读基准的兜底面**：祖先声明取自重定义前层级；主路径是 runtime cache，cache 缺失时兜底走
  `WorkspaceRawIndex` / loader resource。**当 agent 晚挂载、祖先又恰好在本批次里**，loader resource
  可能已是新磁盘字节，此时基准可能按新版本解析——现有用例只覆盖 runtime cache 命中的路径。
  另外该 cache 以类名为全局键，继承风险 15 的多 loader 限制。
- **未覆盖**：其它 JBR 版本/平台、父加载器创建实例、Remold 两阶段 Stage A/C 顺序的模拟。

### 风险 31：V3 S41 无绑定方法引用跨代配对（已接受已知边界）

- **现象**：把 `String::toUpperCase` 改成 `String::trim` 这类**无绑定方法引用**（`String::method`，不锚在某个捕获实例上）后，跨代旧实例落 `KNOWN_GAP`（保留上一代的方法引用目标）。
- **实测读数**（外部工程 `RemoldLambdaRegressionTest`）：`actual=ret=[this:a-v3;base:b-v3:L3;I(c-v3);5; AB ]|fresh=[...;Ab]`；日志显示 V2→V3 配对**已正确**（`old lambda$makeAll$1 <- new lambda$makeAll$0`，`:L3` 保留），只 ghost 了转直接方法引用的那两个。
- **当前结论**：配对层面已修好（callee overlap 证据落地后 `FAIL=0`）；剩余差异是**捕获值 / 方法引用目标**层面的差异，不是配对错配。本轮分布 `PASS=69 / KNOWN-GAP=15 / FAIL=0`。
- **暂不修的理由**：无绑定方法引用没有稳定的跨代身份判据——同一个 `String::trim` 站点在两代之间既可能整体替换，也可能与相邻的 `String::toUpperCase` 互换，名字与描述符都不携带"这是同一个站点"的证据；硬配等于按名字猜身份，会把已修好的配对重新引入成静默错值（比 `KNOWN_GAP` 可见更糟）。
- **重开条件**：外部语料出现"配对正确但业务输出错误"的可复现现场，且能给出无绑定方法引用的跨代身份判据（例如站点字节位置 + 捕获差异可归因于源码编辑）；届时先在 `LambdaAligner` 补判据与负例，再改 `KNOWN_GAP` 口径。
- **关联设计规范**：[`docs/lambda/01-capture-bridge.md`](lambda/01-capture-bridge.md) §11；同口径见 §1.3.1 残余边界与 §4.1.2.1 `V3 S41` 行。

### 风险 33：静态字段 / `@JvmField` 初值改动不回填（设计边界）
- **风险描述与底层机制**：InitFix 只处理本次 Redefine **新增声明的字段**；修改既有字段的初值不在其作用域内（`AGENTS.md` §1）。因此 `@JvmField var renderDebug: Boolean = false` 改成 `true`、或 `object` 顶层 `@JvmField` 计数初值改动，`<clinit>` 虽然进 `DIFF`，已初始化的静态字段仍保持旧值。
- **触发条件**：只改既有静态字段 / `@JvmField` 字段的初值，不新增字段、不改结构。
- **当前缓解措施**：显式重放通道——`@RemoldReinit(mode = OVERWRITE)`；L1 存量初值重放（`-Dremold.agent.init_drift.parents=<fqcn,...>`，白名单父类匿名子类 + 非 final 基本类型 + 编译期常量）；A 档 Kotlin `apply {}` 静态持有者重放（`-Dremold.agent.init_drift.apply_holders=<fqcn,...>`）。
- **关闭条件**：明确不做——按架构边界接受，"改了默认值请重启"，避免把"静默覆盖用户运行期赋值"变成默认行为。
- **关联设计规范**：[`docs/initfix/12-L1-existing-initializer-replay.md`](initfix/12-L1-existing-initializer-replay.md)、[`docs/initfix/13-kotlin-apply-replay.md`](initfix/13-kotlin-apply-replay.md)。

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
| UI 元素源码视图（调试只读） | 🔶 | `plugin-arc` `remold.uihook.UiSource` / `SourceSnippet` / `CellPropertyRef.identityOf`·`sourceFileOf`·`cellOf` | `UiSource.of(cell)` 读取 `CellPropertyRef` 记录的创建站点（host 类/方法/行号）并渲染当前磁盘源码前后各 4 行（`of(cell, context)` 可调；`of(element)` 按元素反查受追踪 Cell）；片段带行号与 `>` 目标行标记；源码根取 `-Dremold.source_roots`（别名 `-Dremold.agent.source_roots`），未设置时探测工作目录的 `src/main/java`、`src/main/kotlin`；解析失败输出位置 + 原因、不抛异常；创建站点记录新增栈帧 `SourceFile` 名，`CellPropertyRef` 新增只读访问器；下游模组以 compileOnly 依赖 `plugin-arc` 调用。`UiSourceViewTest` 9 用例（上下文窗口/首尾夹取/目标行标记/源码根解析/类名回退/站点反查/未追踪提示）。**未验证**：真实 Mindustry 客户端内的取用路径（单测覆盖 Arc 场景图对象 + 桩源码文件） |
| Mixin 重织入（普通类） | ✅ | `plugin-mixin` `MixinReWeaver` | 重定义前把源码级字节码交回 Mixin 织入，非目标类按数组同一性跳过拷贝；真实 Fabric 1.21.1 客户端已验收（新增/删除 `@Unique` 字段、后续 target 编辑）；`MixinRawSourcePriorityTest` 守住原始字节来源；CI 无自动 Fabric 客户端 E2E |
| Mixin 共享反射引擎（Fabric / Forge / NeoForge 配置差异） | ✅ | `core/src/remold/reflect/mixin/`（`MixinConfigDiffer`、`NewMixinRegistrar`、`MixinConfigContext`、`MixinReflection`；目标加载抑制器以 `Function`/`Consumer` 跨边界传递，不设 core 侧接口）；`plugin-mixin` 薄适配；`plugin-forge` 的 `ForgeMixinConfigDiff` + `ModLauncherClassTrackerSpoof` | core 新包纯 JDK + 反射，不引入 Mixin / ModLauncher 编译期依赖；`*.mixins.json` 删除/重新加入、删除 tombstone、新增 Mixin 登记、目标重织入回调共用一份引擎。Fabric 侧沿用 `FabricLauncherSpoof`；Forge/NeoForge 侧在 `prepare()` 极窄窗口内从 `ModLauncherClassTracker.loadedClasses` 同步摘除目标名，`try/finally` 恢复，字段缺失/类型不符时 fail-closed 提示重启。**真实 Forge 1.21.1 + NeoForge 21.1 E2E**：先跑原有 ClassInfo 两阶段（GREEN），再删除 `ci.mixins.json`/`neprobe.mixins.json` 中的模板 → hook 停止；重新加入并新增 `ConfigNewMixin` → 原 handler 恢复 + 新 handler 生效；两份证据分别为 `tests/fabric-mixin-probe/forge-classinfo/evidence/forge-1.21.1-configdiff-after-fix.txt`、`tests/neoforge-mixin-probe/evidence/neoforge-21.1-configdiff-after-fix.txt`。`MixinConfigDifferTest` 3/3（删除/重加/截断配置）、`ModLauncherClassTrackerSpoofTest` 3/3（摘除恢复/缺字段 fail-closed/空目标）、原有 `NewMixinRegistrarTest` 6/6 + `MixinConfigDiffTest` 3/3；全量 `check` 520/0 skipped。**未验证**：Forge/NeoForge 客户端（仅 dedicated server）、Mixin 升级后的内部字段变动（fail-closed 到重启）。 |
| Mixin 类自身变更 → 目标级联重织入 | ✅ | `plugin-mixin` `MixinHotSwap` + `MixinClassInfoSync` | `reload` **前**把新增字段/方法补进其复用的 `ClassInfo`（`reload` 的 conform 校验按此元数据解析新 `@Inject` handler；拒绝时回滚），再对已加载目标类走 `redefineDynamic`；真实 Fabric 1.21.1 客户端已验收（新增/删除字段、`TitleScreen` 重织入）；真实 Fabric 1.21.1 服务端已验收 `@Inject` 自演化三场景（新增/改目标方法/增删 handler，`tests/fabric-mixin-probe/evo/` + `evidence/inject-evolution.txt`）；`MixinClassInfoSyncTest`（Fabric 适配器降级与空令牌回滚，2/2）/ `DynamicLayoutWaiverTest`；`@Mixin` 模板类不再进入 `loadedClassesMap`（`NonLoadableClassTest`）。**新增 Mixin 类运行期登记（实验性）**：`MixinHotSwap` 在 `reload` 返回空目标时调用 `NewMixinRegistrar`，反射把新类补进 `MixinConfig.mixinClasses` 并执行 `MixinConfig.prepare` + `MixinInfo.validate()`；目标已加载时在 Fabric 上通过 `FabricLauncherSpoof` 把 `isClassLoaded(target)` 在 `prepare` 的极短窗口内临时置 false，构造完立即恢复，之后走 `redefineDynamic` 重织入。校验失败回滚；内部字段不匹配时 fail-closed 提示重启。`NewMixinRegistrarTest` 6/6（包匹配、只登记一次、校验失败完整回滚、缺 processor 降级、Mixin 内部结构契约）；`MixinConfigDiffTest` 3/3（数组解析、缺 key、截断配置 fail-closed）。**Mixin 配置差异（实验性）**：`onAssetsChanged` 收到 `*.mixins.json` 变更后，`MixinConfigDiff` 对比当前侧（common/client/server）声明；删除项从 `MixinConfig.mixins`/`mixinMapping`/全局去重集合移除并重织入目标，新增项复用 `NewMixinRegistrar` 登记。**真实 Fabric 1.21.1 KnotServer E2E**：目标未加载 → 登记后首次加载即织入（hook 计数 1，返回值保持）；目标已加载 → 登记 + 重织入后 hook 计数 1；从 config 删除 `RemovableMixin` → handler 方法消失、hook 停止；删除后再次热更该 Mixin 的 `.class` 也不会被重新登记；重新加入 → hook 恢复；证据 `tests/fabric-mixin-probe/evidence/newmixin-runtime-20261108.txt`。**真实客户端已观测**：IDE 客户端（KnotClient/JBR 21.0.9）两次删除/重新加入 `CreepMixin`，每次都出现 `Config diff` + `redefine`，重新加入后 hook 恢复；证据 `tests/fabric-mixin-probe/evidence/newmixin-client-20261108.txt`。**Forge / NeoForge 的配置差异见下方共享反射引擎行**。**未验证**：Mixin 升级后的内部字段变动（会自动退化为“需重启”）；客户端日志未覆盖“删除后立即刷苦力怕”的 hook 终局断言（该断言由 KnotServer 探针覆盖）。 |
| Forge / NeoForge 跨 ClassLoader Mixin 重织入 | 🔶 | `plugin-forge` `ModLauncherMixinBridge`、`ForgeMixinHotSwap` | 通过 `BytecodeTransformerBridge` 穿透 ModLauncher / NeoForge 类加载器反射定位 `IMixinTransformer` 织入重定义字节码；`ForgeMixinHotSwap` 处理 Mixin 模板类自身热更（`IMixinTransformer.reload` 刷新状态 + 级联重织入目标类），解决 Forge 与 Agent 间 ClassLoader 隔离；真实 Forge 1.21.1 与 NeoForge 21.1 dev 服务端已红→绿（[历史归档 §0.3/§0.4](releases/historical-rounds-archive.md)），**真实客户端与 NeoForge 生产 installer / 26.x 仍待验证** |
| Forge / NeoForge EventBus 监听器刷新 | 🔶 | `plugin-forge` `ForgeEventBusRefresher` | 监听批次完成事件，识别新增/移除的 `@SubscribeEvent` / `@EventBusSubscriber` 并自动在 `EVENT_BUS` 注销与重新登记；端到端需真实 Forge / NeoForge 客户端验证 |
| 标准 JVM 能力探测与降级 | ✅ | `RemoldAgent.probeEnhancedHotswap` / `mustSkipForMissingEnhancedRedefinition` / `degradeForMissingEnhancedRedefinition` | 厂商身份 + flag 文本双条件判定；结构变化类在无增强能力时摘批；`FORCE_STATIC_LAMBDA` 强制降级、`INIT_FIX` 刻意不降级；`EnhancedHotswapGateTest` 9 用例含变异检查 |

| 合成匿名类单调并集（v0.2.7 战役：故障 B 第三治本点） | ✅ | `MixinSyntheticSchema.renameCollisions`（同名异型字段重命名追加 + GETFIELD/PUTFIELD 改写）、`retainConstructors`（`<init>` 重载墓碑并集）、`MixinInnerClassHotSwap` 规则 3（`BLOCKED` + `MixinReWeaver` 中止宿主重定义） | **已观测**：`MixinSyntheticSchemaTest` 5 用例（缩容补齐/同 schema 不补/同名异型重命名+访问点改写/基线 `<init>` 保留/`schemaHash`）；真机（Fabric server + JBR 21.0.9 DCEVM）5 轮 R1~R4 `field-table-stable=true`、`RESULT PASS`。**边界**：客户端 `probeLogging=true` 下 R1 的 `(this,int,Entity)` 与 R4 的 `(this,Target)` 真实往返仍需前线复验；`AnonClassAligner` 侧的同名异型字段规则尚未接入（其配对以字段布局门为准） |
| 抑制签名锚点修正 + 清 GC/死代码（v0.2.7 战役：真机同会话锁死 Bug） | ✅ | `MixinInnerClassHotSwap.redefineSyntheticSafely`（抑制签名 = 候选字节 `Arrays.hashCode(bytes)`，键 = 合成类名；同一份字节 `stays aborted` 抑制、异签名重新评估、放行即清账）、`MixinHotSwap.flushPendingTargets`（每轮清 `BLOCKED` 重新评估，不再用宿主 `originalBytes` 做前置跳过）、`syntheticLive(live)`（raw-only 诊断，产品路径无 `System.gc()`）、`MixinSyntheticSchema`（删除已不可达的 `padFields`/`renameCollisions`/`rewriteFieldAccess`/`retainConstructors`/`canRedefineWithoutInstanceRewrite`/`describeFieldTableChange`/`appendedFields`） | **已观测**：真机同会话 R1→R4 曾因签名取宿主恒定 `originalBytes`（`837190382`）把 R2/R3/R4 全部永久跳过。单测 `MixinSyntheticRetryAndFlagsTest` 3 例按真实数据流（宿主字节恒定：R1 拒 → R1 同字节抑制 → R2 异签名再拒 → R4 还原放行）、`MixinSyntheticSchemaTest` 4 例（两套视图 + static/synthetic 翻转 + schemaHash）、`MixinSyntheticLivenessTest` 4 例、`MixinHotSwapWiringTest` 2 例、`BridgeAbortTransactionTest` 2 例、`MixinSyntheticGateTest` 8 例；全量 `:tests:test` 718 用例 / 0 失败 / 1 skipped + `initFixOracle` ALL PASSED。**变异 2/3 击杀**：签名改回恒定输入（R4 锁死）killed；去掉 flags 比较 killed；「抑制永不开火」**survived 且判定为等价变异**——闸门已无条件拒绝 schema 变化，抑制只改变日志措辞、不改变是否 redefine，故该台账当前是诊断设施，真正的强制点在闸门。**待验证方向**：`schemaHash` 命名新合成类（`defineClass` 加载新类 + 宿主仅 body-only redefine）预注册判据 = 新名类加载 + 宿主 body-only redefine 下 `newlyNull=0`；未实现、未验证 |
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
| Gradle 二进制插件 (`gradle-plugin`) | ✅ | `gradle-plugin/`、`gradle-plugin/build.gradle`、`gradle-plugin/README.md` | 提供 `io.github.i-hope1.remold` 二进制插件，支持 Kotlin DSL 与 Groovy DSL 类型安全配置 `remold { ... }`、`remold.attach(...)`（直接注入既有 `JavaExec` / `Test` / `TaskProvider` / 运行器，不强制 `mainClass`/`jar`、去重 `-javaagent:` 并重置 JBR `javaLauncher`）与真配置缓存兼容（`GenerateIdeaRunConfigTask` / `GenerateRemoldScriptTask` / `StartRemoldTask` / `CleanRemoldCacheTask` + `whenReady` + 延后本地 agent 同步，`agentJar` 支持 `project.file` 相对路径）；`RemoldPluginTest` 13/13、`AttachAndConfigurationCacheTest` 4/4（TestKit `--configuration-cache` 复用 + 真 `JavaExec` 探针）、`RemoldScriptShimTest`、`CleanRemoldCacheTaskTest`、`RemoldAgentVersionTest` 全绿，`validatePlugins` 通过；JVM 参数注入覆盖 `client`、`server` 与 gametest 运行容器（Fabric Loom / ForgeGradle / NeoForge ModDevGradle），IDEA Loom 同步覆盖 `Minecraft_Server.xml`（Application 风格 `VM_PARAMETERS` 与 Gradle 风格 `vmOptions`）；仓库根 `remold.gradle(.kts)` 已瘦身为薄壳（仅按 Class 应用本插件，实现在本模块），jar 内附带一份副本供离线取用。**Gradle Plugin Portal 已上线**（v0.2.7 起发布；v0.2.8 已验证可解析，见顶部发版说明、[`releases/v0.2.8.md`](releases/v0.2.8.md) 与 `README.md`；英文文档与 `website` 已补齐）；**G0 合规已完成**：`com.gradle.plugin-publish` 已升至 `2.2.1` 并声明 `compatibility { features { configurationCache = true } }` |
| GitHub Release 发布物 | ✅ | `.github/workflows/release.yml` | Tag `v*` 触发全量前置验证矩阵（javac 8/11/17/21/25 测试、DCEVM 测试、P0.5 探针与 Linux x64/Android 原生库编译、Linux aarch64 制品 ABI 校验），全绿后自动打包 20 项 Release 资产（agent canonical+versioned、5 个插件各 canonical+versioned、gradle-plugin canonical+versioned、3 个原生库）并发布到 GitHub Releases，消除资产早于构建事故；支持 `workflow_dispatch` 手动触发与 `--clobber` 覆写更新 |
| Class / 资源防抖分离 | ✅ | `core/src/remold/DebounceWindow.java`（纯状态机）、`RemoldAgent.handleFileChange/handleJarChange/handleAssetChange`、`gradle-plugin` 的 `assetShakeMs` | Class/jar 事件每次重置独立静默窗口（默认 800ms）；资源/配置流独立窗口（默认 250ms），到期只 drain 资源队列，不提前带出 Class 批次；手动 `triggerHotswap()` 取消挂起调度、清空两条流并强制 drain 全部。`DebounceWindowTest` 6/6（无 sleep：事件重置、双流互不影响、nextDelay、clear）、`AssetShakeConfigTest` 1/1；全量 `check` 514/0 skipped。**未验证**：真实 FileWatcher 的 OS 事件节奏（单测只覆盖状态机，未测 Windows 文件系统时延分布） |
| 构建完成 marker（build-trigger 快路径） | ✅ | `core/src/remold/BuildTriggerMarker.java`（解析/拒绝/去重）、`core/src/remold/BuildTriggerSweep.java`（mtime 扫描）、`RemoldAgent.configureBuildTrigger` / `handleBuildTriggerFile`、`DebounceWindow.markClassDueNow`（sticky due）、`FileWatcherService` 专用 trigger watcher；`gradle-plugin` `BuildTriggerWriter` + `RemoldPlugin` 的 `classes.doLast` + `RemoldConfigResolver` 注入；设计 `docs/runtime/01-build-trigger-mode.md`，读数 `docs/runtime/02-build-trigger-probe.md` | **已观测（Windows + JBR 25.0.2，单进程单轮同口径）**：`./gradlew :tests:buildTriggerProbe` 63/0；marker→批次回调（含 redefine）min/median/max = 57/97/325ms（20 轮）；同一编译不写 marker 的对照 min/median = 883/887ms（3 轮；max 3652ms 那一轮的 `redefine=2855ms`，属单轮长尾，不是窗口变长）；取消兜底（窗口临时 3000ms）marker→批次 114ms，之后 700ms 内无第二次非空批次；陈旧 marker（−60s）0 批次；无变更 marker 0 次非空批次。单测 `BuildTriggerMarkerTest` 9/9、`BuildTriggerSweepTest` 7/7、`BuildTriggerTest` 7/7、`DebounceWindowTest` 7/7；插件 `BuildTriggerWriterTest` 3/3、`BuildTriggerTestKitTest` 3/3（含配置缓存复用轮仍写出有效 marker）、`RemoldPluginTest` 16/16。**变异 7/7 击杀**（sticky latch 关闭 / 后续 marker 重扫 / 陈旧+未来校验关闭 / 扫描 mtime 过滤关闭 / 空扫描仍置 due / `buildTrigger=false` 仍注入 / `classes.doLast` 接线删除）。**未验证/残余**：JBR 17/21 DCEVM、Linux、macOS 未复测；macOS 高灵敏度 watcher 修饰符无实测；构建时长 > 兜底窗口时编译中途批次仍拦不住；marker 之后到达的同批事件仍产生 no-op 批次（哈希全跳过，无 redefine/回调）；mtime 扫描依赖文件系统时间语义 |
| 模组模板基线（1.21.1 主模板） | ✅ | `minecraft-fabric-template/`（MC 1.21.1 / yarn 1.21.1+build.3 / loader 0.16.9 / fabric-api 0.105.0+1.21.1 / Loom 1.17.21） | 模板只保留最小可运行骨架；原先内嵌的客户端入口 `com/example/client/*` 与 `HotSwapToastModelTest` **已移出模板**，改由 [`plugin-mc-toast/`](../plugin-mc-toast/) 以零 MC 依赖的 Agent 侧插件承载（见下一行），因此模板不再需要 fabric 客户端入口点与 toast 单测；**未验证**：模板在客户端入口移除后未重跑 `./gradlew build --offline`（本环境无 MC 依赖缓存），此项回归留待下次模板改动时随构建一并确认 |
| 客户端 HotSwap Toast 插件（`plugin-mc-toast/`） | ✅ | `plugin-mc-toast/src/remold/mctoast/`（`McToastHotswapPlugin` / `McToastPresenter` / `ToastModel`）、`plugin-mc-toast/test/remold/mctoast/`（`McToastPresenterTest` / `ToastModelTest`）；Gradle 侧 `RemoldConfigResolver.autoMcToast` 对 Fabric/Forge 默认追加 `mc-toast`（`-Dremold.autoMcToast=false` 关闭） | 零 Minecraft 编译依赖：只依赖 `api`/`core`（`compileOnly`），运行期由 Agent `appendToSystemClassLoaderSearch` 加载，jar 内 manifest 声明 `Remold-Plugin-Class: remold.mctoast.McToastHotswapPlugin` 与 `Remold-Plugin-Name: mc-toast`；呈现层按 1.21.1 的 `Toast.draw(DrawContext, ToastManager, long)` 适配并在 `draw` 内计时，结构化批次报告缺失时降级为明确的降级卡片；`settings.gradle` 已 `include ':plugin-mc-toast'`，发布链随本次修复纳入 release/ci 资产。**证据**：`plugin-mc-toast` 单测随 `./gradlew check` 执行（`McToastPresenterTest` / `ToastModelTest`）；发布资产由 `Prepare Release Assets` 的插件循环产出 canonical 与 versioned 两份。**未验证**：卡片在真实客户端窗口内的渲染（本环境无显示会话），以及 Forge/NeoForge 侧的实际 Toast 呈现；`mc-toast` 的 jar 下载仍无校验和（供应链面，见 §5 风险） |
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
| 普通匿名类非合成字段形态矩阵（新增 / 改类型 / 缩容 × `reject`·`warn`） | ✅ | `tests/src/AnonFieldShapeProbe.java`、`tests/build.gradle`（任务 `anonFieldShapeProbe`）、[`runtime/06-anon-field-shape-drill.md`](runtime/06-anon-field-shape-drill.md)、`tests/anon-field-shape-drill/` | JBR-21.0.8 真机 7 格矩阵全绿：改类型在 `reject`/`warn`/`anonGate=warn` 三档都被拒——拦截来自匿名类侧两道门（`ANON-LAYOUT` 配对门 + **无档位**的后置换名布局校验 `ANON_ALIGN_VALIDATION`），故该形态到不了 JBR 的实例重排；新增（两阶段补值）与缩容（`WAIVE_NO_READERS`）按契约放行，旁观字段与未改动对照类零连坐污染（`newlyNull=0`）；「缩容无害」的适用边界只到「批次外读者为空 + 删尾字段」。热更摘要计数改由 `core/src/remold/RedefineSummary.java` 定义（`changed`/`applied`/`noop`/`skipped` 四数独立、按类去重、`applied==0` 禁打成功行、纯重扫静默），单测 `RedefineSummaryTest` 7 例 + 变异 2/2 击杀；两处零生效路径（空定义 / 全 no-op）共用同一出口，Kotlin 整族被拒全程 0 次成功行 |
| 新增字段初值不可提取 → 整类拒绝 + 两阶段 requested 对称豁免 | ✅ | `core/src/remold/BatchHotswapExecutor.java`（`dropUnexpressibleInitClasses`）、`core/src/remold/ref/InitFix.java`（`unexpressibleNewFieldInits` / `discardPending`）、`core/src/remold/TwoPhaseCoordinator.java`（`stageAConsistent`）、[`initfix/01-safety-gate.md`](initfix/01-safety-gate.md) §1.5 | 真机 `:tests:initFixUnexpressibleProbe` 26/26（R0 基线生效 → R1 整类拒绝：`[INITFIX-REFUSE]` + `INITFIX-UNEXPRESSIBLE-INIT` + 存量实例留旧版本 + 无成功行 → R2 对照：常量初值/无初值字段照常生效）；单测 `InitFixUnexpressibleInitTest` 5 例 + 变异 2/2 击杀（门恒空、把全部拒绝算作不可提取）；`StageARequestedScopeTest` 按对称豁免更新契约（反常豁免即红）。边界：初值来自构造器参数或无初值的字段仍是字段级跳过（默认零值即源码语义） |
| Lambda PASS_A 两段式 + 捕获桥 primitive↔boxed 边界 + INIT-DRIFT owner 判定 | ✅ | `core/src/remold/LambdaAligner.java`（`step2PassAAnchored` / `step2PassAFallback` / `legalPassAOld` / `pairPassA`）、`core/src/remold/LambdaCaptureBridge.java`（`layoutHolds` / `boxingEquivalentLayout` / `forwardedTailMatches`）、`core/src/remold/ref/InitializerDrift.java`（`writeSlices` / `superChain`）、[`lambda/01-capture-bridge.md`](lambda/01-capture-bridge.md) §2.1 | `LambdaAlignerPassAOrderTest`（两种声明顺序 + 确定性 + 无锚点回退对照）、`LambdaCaptureBridgeBoxingLayoutTest`（8 例）、`InitializerDriftOwnerTest`（4 例）+ 既有 `InitializerDriftTest` 全绿；变异 3/3 击杀（revert 两段式 → A1 红；layout 严格化 → 4 例红；去转发边界校验 → 装箱尾参红；恢复"非本类声明即继承" → 捕获盒两例红）；union `check` 全绿。边界：primitive↔boxed 只放宽布局自检，转发边界仍严格；祖先链不可解析时 INIT-DRIFT 静默 |
| @JvmStatic @OnReload 去重派发 + kotlinc 形态捕获桥端到端 | ✅ | `core/src/remold/BatchHotswapExecutor.java`（`RELOAD_INVOKED` / `forwarderTargetKey` / `implementationKey`）、`tests/src/KotlinShapeBridgeProbe.java`、`tests/src/OnReloadJvmStaticProbe.java` | 真机 `:tests:onReloadJvmStaticProbe` 8/8（R0 恰好 1 次，去重关掉实测 2 次；R1 真静态体 + 无关实例回调各 1 次）、`:tests:kotlinShapeBridgeProbe` 8/8（旧 lambda 经桥 8→11 + `BRIDGE` 事件断言；红色基线 2 条红）；变异 2/2 击杀；union `check` 全绿。边界：转发器判据只认「单条同名同描述符 INVOKE」，非转发器的静态回调照旧各跑一次 |
| Kotlin 匿名类身份解析（EnclosingMethod） | ✅ | `core/src/remold/AnonHostResolver.java`、`AnonClassAligner`（分组/层级/补配）、`BatchHotswapExecutor`（宿主派生）、`tests/src/KotlinAnonSequenceProbe.java` | 真机 kotlinc 2.3.0 夹具三轮序列 violations=0（改前 8 条）；变异 6/6 击杀；全量单测 1297 绿。边界：outerAnon 的集合工厂初值按设计拒绝整族热更；嵌套相邻声明的改名配对没有独立负向断言 |
| lazy_load 重编号匿名类与嵌套顺移 | ✅ | `BatchHotswapExecutor`（主动注入 + 加载器提示 + 改名传播 + 指纹豁免）、`RemoldAgent.injectNewClass`（loader 提示重载）、`tests/src/LazyLoadRenumberProbe.java` | 真机 JBR 21：`:tests:lazyLoadRenumberProbe` 9/9（宿主取到 v2、构造不抛、无 Stage A 回滚、存量与新实例的内层均跑新体）；`:tests:kotlinClassModeProbe` 4/4；三处变异各自复现 `NoClassDefFoundError`。边界：外层拿到**全新槽位**（旧外层被布局门拒绝）时不传播改名，老内层随老外层保留为孤儿 |
| 嵌套匿名类跨层准入（EnclosingMethod 别名） | ✅ | `AnonClassAligner.setEnclosingAliases` + `BatchHotswapExecutor`（按宿主组设定并清理）、`tests/src/remold/NestedKotlinShapeAlignTest.java` | 合成字节复现现场形态：host 是匿名类、子类在 `$innerObj$1` 一层（level 2），且新侧字节仍写着改名前的 `$4` 前缀时也能被消费（5 用例）；变异：屏蔽别名准入后两条用例转红。边界：别名只覆盖"本批已确定 target 的宿主"，跨批次残留的旧名字不认 |
| 匿名宿主的旧侧收集 + 属性自洽 | ✅ | `BatchHotswapExecutor.resolveOldAnonHost`、`AnonClassAligner.setHostRenames`、`tests/src/NestedAnonHostOldSideProbe.java` | 真机 JBR 21：探针 14/14 —— R1 纯方法体修改存量实例跟进、R2 外层顺移后仍配对、R3 捕获增长走 `[ANON-LAYOUT]` 拒绝而非静默重编号；变异：旧侧退回名字口径 4 条转红、字节重写不带宿主链改名 1 条转红。边界：旧类若连名字父链上都找不到真实祖先，仍按属性兜底 |
| 跨宿主复用（JVM 层可行性） | 🟡 | `tests/src/DcevmCrossHostProbe.java` | 真机 JBR 21.0.8 读数：①字段描述符 `LA;`→`LB;` 被 DCEVM 接受（删旧+加新）；**旧值保留这一条与 #74 差分读数冲突**（`HostRef.outer` 无人写入却是 `null`），待并排复现——回看夹具后已定位：探针 2 的旧值实际类是 `String`、新类型也是 `String`（**可赋值**），`HostRef` 是 `txh.Old` 值落 `txh.New` 字段（**不可赋值**），两条读数落在同一规则的两格，尚需矩阵确认；「值来源」的真实问题是类型错位而非缺失；②`NestHost`/`NestMembers` 可在同一轮改属，改后旧类名可访问新宿主私有成员。复用功能本身未实现，仍走「孤儿 + 注入」；缺口：运行期未在 JBR 25 上验证 |
| 探针证据口径（jar 新鲜度前置断言） | ✅ | `tests/src/AgentFreshness.java`（唯一入口），已接入 `DcevmCrossHostProbe` / `TypeChangedFieldResetProbe` / `NestedAnonHostOldSideProbe` | 每次探针运行先比对**实际加载的 agent 类**与 `core/build/classes/java/main` 产物的 SHA-256，并打印 `sha=/branch=/tree=`。实测读数：`RemoldAgent` `cd369cfb1f75`、`InitFix` `04aaa475b13e`、`ClassDiffUtil` `81738eb1bf20` 三者与构建产物一致。缺口：`FieldTypeMatrixProbe` 在 PR #76 分支上尚未合并，故未接入；不覆盖“探针吃旧 jar 但构建产物也旧”的场景 |
| 类型改变字段的补值 | ✅ | `InitFix.synthesizeTypeChangedResetExtracts` + `forceWriteFields` 无条件写、`tests/src/TypeChangedFieldResetProbe.java` | 真机 JBR 21：默认模式整轮 `[LAYOUT-REJECT]`（存量实例保持旧代码）；`warn` 强制放行时有可证明初值则无条件写新初值、无初值（构造器参数赋值形态）则清成 `null` 并告警。变异：去掉无条件写 → R2 两条转红；清零通道早退 → R3 告警断言转红。边界：静态字段的类型改变未覆盖 |
| 类型改变字段的补值 | ✅ | `ClassDiffUtil.typeChangedFields` + `InitFix` 三态告警（**只检测不写**）、`tests/src/FieldTypeMatrixProbe.java`、`tests/src/TypeChangedFieldResetProbe.java` | 规则（JBR 21 与 **25.0.2** 裸重定义读数一致）：DCEVM 按**旧值实际类**判定——可赋值保留原对象、不可赋值或旧值为 `null` 则槽位为 `null`、基本类型一律归零；三种读法（`getfield`/反射/`checkcast` 消费）一致，被丢弃的值在消费处抛 NPE 而非静默错对象。补丁语义：#73 的无条件写已回退，恢复"仅当槽位仍是类型默认值才写"——可赋值时保留旧状态，不可赋值时写新初值，无初值（`this$0` 形态）则拒绝并告警。变异：恢复无条件写 / 取消检测均转红；全量 check 1320 用例。缺口：完整流水线列只在两格抽验；`getInstances` 对"仍挂在别名类名下"实例的可见性未测 |

> **事实③（`db74eec` 中 3 参 `<init>` 存在却 `NoSuchMethodError`）**：**不再追查、未闭合**。
> **`newlyNull=230` 的口径**：在该客户端实测、该类、该序列下，对已加载合成类做字段表变化的 `redefine`，
> 至少 3 次独立运行重复出现 `newlyNull=230`（双通道 `agree=1220 / conflict=0`）；底层 `ModifyInstances`
> 机理系由代码分析得出（未直接观测）。
> **值类型直方图归因**：`230 个具名 Record 类值变 null、14 个隐藏类 Lambda 值幸存` 为实测结果；
> “这些实例由 `KnotClassLoader` 加载”“`Util.allOf` 单元素分支”系由代码分析得出。
> **待验证方向（未实现）**：`schemaHash` 命名新合成类——schema 变化时用 `defineClass` 加载**新类名**，
> 宿主只做 body-only redefine；预注册判据：新名类加载 + 宿主 body-only redefine 下 `newlyNull=0`。

