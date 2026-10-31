# HotSwap 模块实现状态总表 (Implementation Status & Audit)

> **状态信息管理规则（强制）**：  
> 本文件为 HotSwap 全模块实现状态、技术债务与验收审计的 **唯一真相源 (Single Source of Truth)**（高层高价值概括见 [`AGENTS.md`](../AGENTS.md) §5）。  
> **严禁在 Javadoc、代码注释以及 `docs/` 设计规范文档中重新混入易腐的实现状态信息**（如 `✅`、`[已实现]`、`[未实现]` 等，由 CI 任务 `verifyDocStatusMarkers` 自动化守卫）。  
> 各设计规范文档仅负责定义客观的架构规格、设计原理与交互契约；实现状态若有变更，统一在此登记与审计。

| 审计元数据项 | 内容与规格说明 |
|:---|:---|
| **基准提交 (Baseline Commit)** | `675f6b2` (分支: `main`，本轮状态校准的起点) |
| **最后更新时间** | 2026-10-30 |
| **审计负责人 / 角色** | HotSwap 架构守卫小组 & 自动化回归验证套件 |
| **更新触发条件** | 任何合入包含特性落地、架构偏离、安全门调整、测试基线变更或发现已知缺陷/残余风险的 PR/commit 时，必须同步原子化更新本文件。提交时由作者/维护者更新基准哈希。 |
| **管辖范围与边界声明** | 本状态表权威管辖：<br>1. **匿名类拓扑对齐与安全门 (Anonymous Class Alignment & Safety Gates)**（[`docs/topology/`](topology/)）<br>2. **存量初始化修复器 (InitFix / Legacy Instance & Static Reinitialization)**（[`docs/initfix/`](initfix/)）<br>3. **物理内存偏移稳定性与第三方缓存驱逐 (Physical Memory Offset Stability & Reflection Cache Eviction)**（[`docs/offset/`](offset/)）<br>*边界与后续治理声明*：同属字节码重映射体系的 `LambdaAligner`（Lambda 表达式对齐器）以及 Mindustry UI 胶水层（`CellPropertyRef` / 动态参数表达式指纹），其实现状态当前由各自单元测试覆盖（`CellHelperTest` 6 场景持续全绿、`LambdaAlignerTest` 等）；它们的详细状态审计矩阵尚未并入本总表，计划在下一轮治理中统一建立验收标准并纳入。 |

---

## 0. 状态治理与规范文档清理进度 (Status Governance & Documentation Cleansing)

为根治文档易腐性与状态漂移，本项目推行“规范规格与实现状态物理分离”治理方针。当前治理基线如下：

1. **唯一真相源隔离**：`docs/status.md` 作为全模块实现状态的唯一受权载体，彻底解除规范文档与代码注释维护实现进度的负担。
2. **双重验收验证机制**：
   - **自动化机器扫描 (`verifyDocStatusMarkers`)**：纳入主构建验证流程（`./gradlew check` 强制依赖），递归扫描 `docs/initfix/`、`docs/topology/`、`docs/offset/`、`core/src/`、`api/src/`、`plugin-arc/src/`、`plugin-mixin/src/`、`panama/src/`，严格拦截标记 `/[✅❌⬜🔶🟣]|\[(已实现|未实现|部分实现|已偏离)/`。当前状态：**100% 达标全绿**。
   - **规范性内容抽检审计**：禁止规范正文出现“目前已支持”、“当前版本尚未实现”等时态陈述，时态陈述一律归入 `status.md`。全量 15 篇客观设计文档（`docs/topology/` 8 篇、`docs/initfix/` 6 篇、`docs/offset/` 1 篇）完成人工抽检审查，抽查合格率 100%。
3. **设计规范文档客观化清理清单**：
   - [`docs/topology/01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) ～ [`08-decisions.md`](topology/08-decisions.md)（共 8 篇）：全部重构为纯架构规格、约束契约与客观原理，易腐进度全部剥离，头部统一指引至 `status.md`。
   - [`docs/initfix/01-safety-gate.md`](initfix/01-safety-gate.md) ～ [`06-decisions.md`](initfix/06-decisions.md)（共 6 篇）：全部完成规格客观化改造，彻底消除进度标记。
   - [`docs/offset/01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md)（共 1 篇）：定义物理偏移稳定性与第三方缓存驱逐契约，客观化规格无状态标记。
   - [`AGENTS.md`](../AGENTS.md)：高层导读章节精简，保留五条不变量与高价值概括，详细状态全部外链至 `status.md`。

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
| Tier 1.5: 内容相似度 | ⬜ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §1 | 待实现于 `AnonClassAligner` | 路线图规划；规划计算指令哈希交集率与字符串 Jaccard；用于根治拓扑无信息同构候选平局 |
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
| 嵌套深度硬上限 | 🔶 | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §4.1 | `RemoldAgent.ANON_STRICT` / `MAX_DEPTH` | strict 模式下 `depth > 4` 拒绝；常规模式下仅告警，`MAX_DEPTH` 死代码尚无独立夹具（列入技术债 3.2） |
| 事务生命周期与乐观预登记 | ✅ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §2 | `AlignmentTransaction.preRegister` | 重定义前预先登记 pending 注入项，消除类加载重入读取磁盘错位产物竞态 |
| 匿名类合成捕获字段布局门 | ✅ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1 | `LayoutGate.check` / `RemoldAgent.ANON_LAYOUT_GATE` | 阻断捕获字段增/改导致存活实例读取零值；默认模式 `reject` |
| 具名类与用户显式字段布局门 | ✅ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1 | `RemoldAgent.applyRedefineLayoutGate` / `LAYOUT_GATE` | 阻断用户字段删除、类型变更、静态性变更；默认模式 `reject`；纯新增放行交给 InitFix。**默认 `reject` 下，有存活实例时被拒的是删字段的类本身**（它的 diff 里就有 `- x`），该宿主连同匿名类被移出本批、保持旧字节码——所以"读者在批次外"轮不到发生；**但无存活实例时会被 `WAIVE` 放行**，而那条理由对删除不成立（风险 12）。要让删除落地还有另外三条路（`warn`/`off`/黑名单/`SKIP`），落地后还需叠加"读者留在批次外"的成因（§13.5）。**删除的判据是"批次外读者为空"**（不是"有删除就拒"，也不是"全局无读者"）：批次内的类扫新字节码、批次外的扫当前字节码，命中 `Fieldref` 才拒（§13.6）。具名类尚未实现这个证明，故有实例时一律拒绝；匿名类合成字段的读者必在类内、类又必在批次内，可证，故 `LayoutGate.check` 放行。实测后果（JBR 21，[`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §13）：布局不压缩、废弃槽位不复用，批次外读者抛 `NoSuchFieldError`——是"错误被推迟到热更事务之外"，不是"读到脏数据" |
| 删除成员的批次外读者分析 | ✅ | [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §13.6、§15 | `core/src/remold/ref/RemovedMemberReaders` | 纯函数。字段按 JVMS 5.4.3.2（自身 → 父接口 → 父类）判"改动后引用是否悬空"；**实例方法额外走 JVMS 5.4.6 的向下分派**（归属方向相反：owner 可以是 `A` 的任何祖先），输出六档——悬空 / 抽象方法缺失 / **静态性翻转（ICCE）** / 判不了 / 分派回退 / 同静态性改指向；**只有前三档阻断**，后两档是合法回退，只记日志（§16.4）。`refs()` 为纯常量池扫描（tag 15 的 `reference_index` 必然回指 9/10/11，故天然覆盖指令与 `invokedynamic`/`LDC` 的句柄）。`RemovedMemberReadersTest` 23 用例、`LayoutGateWiringTest` 23 用例，6 处变异检查；`ReaderScanProbe` 验证"常量池扫描是方法体精扫的超集"，`P05Probe` M7/M8 给出失败形态实测。**接线**：`applyRedefineLayoutGate` 在批次循环前扫一次；`decideLayout` 对 `REMOVED_FIELD` 用 `WAIVE_NO_READERS` / `REJECT`，对"本轮删过方法"另设分支（**必须在 `compatible()` 早退之前**，否则只删方法的编辑永远不会被拦），扫描未完成时方法一律拒绝。扫描范围取"已加载的工程类（内存字节）+ 工程构建输出"，JDK 与第三方 jar 不在范围内；成本 434 类约 25 ms（§14.6）。缓存覆盖由 `CacheWarmingTransformer`（首次加载填充）+ 一次批量 retransform 补齐，并滤掉数组类/hidden class/不可重定义类。**缺口**：pre-11 合成访问器未折算；`invokespecial` 与 `invokevirtual` 在常量池里同 tag，按"可能分派"保守处理 |
| 标准 JVM 能力探测、结构变化摘批与降级开关 | ✅ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §1 | `RemoldAgent.probeEnhancedHotswap` / `mustSkipForMissingEnhancedRedefinition` | 厂商标识 + flag 文本双条件，杜绝 `-XX:+IgnoreUnrecognizedVMOptions` 回显造成的误判；结构变化类在无增强能力时摘出本批，不再拖垮同批纯方法体修改；`FORCE_STATIC_LAMBDA` 在无能力时被强制降级关闭并打 `DEGRADED` 日志（`degradeForMissingEnhancedRedefinition`；`INIT_FIX` 刻意不降级，否则会让直接调用 `InitFix.transform` 的路径静默空转）；`minecraft-fabric-template/README.md` 已按运行时能力分档说明，不再承诺“完美适配任意 JDK 21+”；`EnhancedHotswapGateTest` 9 用例，含变异检查（改回参数文本探测时 `probeMatchesRealVmOptionState` 变红） |
| 重定义层三态存活判定与静态字段门 | ✅ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1 | `RemoldAgent.decideLayout` | `COMPATIBLE` / `WAIVED` / `REJECTED`。**删除静态字段**与实例字段共用"批次外读者为空"，但扫描未完成时只能拒绝——静态值不随实例存在，liveness 兜不住（[`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §16）。**编译期常量（`ConstantValue`）强拒绝**：javac 把值内联进读者的 `ldc`/`bipush`，读者常量池里没有 `Fieldref`，"无读者"不是安全证明而是没有信号（§16.1） |
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
| 五条核心不变量 | ✅ | [`AGENTS.md`](../AGENTS.md) §2 | `InitFix` 主流程入口与熔断保护 | 未初始化守卫、PENDING 弱键 (5min TTL)、PatchReport 解耦、LinkageError 窄熔断、afterRedefineFailed 立即注销；`initFixOracle` 48 场景持续守卫 |
| 离线元数据契约 (`ClassHierarchyOracle`) | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §5 | `ClassHierarchyOracle` / `HierarchyTreeOracle` | 全程无类加载；流式字节码扫描；`AnnotationTransformer.HierarchyTree` 底座支撑 |
| 待补字段台账 (`FieldLedger`) | ✅ | [`02-closure-and-ledger.md`](initfix/02-closure-and-ledger.md) §2 | `InitFix.LEDGER` (`FieldLedger`) | 覆盖六种记账情形（分析期被拒、运行期异常、依赖跳过、Redefine 失败、规划期异常、TTL 超时清扫）；`getUnpatchedFields` 状态回流 |
| T0 零值等价判决 | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §1 | `InitFix.checkSafe` / `NOTHING_TO_PATCH` | 按位比较排除 `-0.0f`/`NaN`；`NOTHING_TO_PATCH` 零开销放行不告警；`initFixOracle` 验证通过 |
| T1 编译期常量 | 🔶 | [`01-safety-gate.md`](initfix/01-safety-gate.md) §1 | `InitFix.extractFieldInits` | 静态 `ConstantValue` 走专用通道；字面量切片仍走常规直线提取 |
| T2 纯计算切片 | 🔶 | [`01-safety-gate.md`](initfix/01-safety-gate.md) §1 | `InitFix.extractFieldInits` / `AliasInterpreter` | 经 `AliasInterpreter` 逆向切片并通过效应安全门；`initFixOracle` 多场景覆盖 |
| T3 复杂/不安全拒绝 | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §1 | `InitFix.checkSafe` / `InitFix.transform` | 分支、try/catch、环境依赖、可变源字段，统一在 `transform` 发 warn 并记入台账；负向断言全面覆盖 |
| T4 显式逃生口 `@HotswapInit` | P1 | [`09-hotswapinit-design.md`](initfix/09-hotswapinit-design.md)（契约已定，`01-safety-gate.md` §1 为分层入口） | 设计草案已提交（待评审）；实现落在 `InitFix.buildPatch` 的候选集段 | 让 T3 拒绝的初始化式（Kotlin `?.`/`?:`、读配置、跨类依赖）由用户提供值，不再永久停在类型默认值。契约要点：只换**值的来源**，不换作用域、不换写入协议；`@RemoldReinit`（扩展候选集/改写入协议）与 `@OnReload`（每次热更回调）各司其职，三者不重叠 |
| 最小效应检查（P0） | 🔶 | [`01-safety-gate.md`](initfix/01-safety-gate.md) §2 | `InitFix.effectReason` / `blacklistedCallReason` | 拦截 bit 3/6 及 bit 4/5 子集；白名单防误杀；Kotlin `trim` 家族 `Object.toString` 窄例外（变异测试守卫） |
| 8 位完整效应掩码 | ⬜ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §2.2 | 待实现于 `AliasInterpreter` | 目标设计路线图（PURE, READS_FINAL, ALLOC_PURE 等完整位图） |
| 参数回溯不可变证明 | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §3 | `InitFix.scanParamFields` / `InitFix.NestView` | 条件 A（final/val 字段）与条件 B（private + Nest 全单写证明）；`sourceFieldNotImmutableReason` 守卫 |
| 多根构造器共识 | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §4 | `InitFix.extractFieldInits` | 要求所有根构造器覆盖完整且参数替换后指令指纹 100% 一致；多构造器歧义安全拒绝 |
| 依赖闭包与 Tarjan SCC 成环检测 | ✅ | [`02-closure-and-ledger.md`](initfix/02-closure-and-ledger.md) §1 | `InitFix.buildPatch` | 成环检测并入闭包不动点计算，只拒绝环成员；出口后置 `depInstance ∪ depStatic` 闭合校验兜底 |
| 失败策略矩阵 | 🔶 | [`02-closure-and-ledger.md`](initfix/02-closure-and-ledger.md) §3 | `InitFix.runIndependentInstanceTasks` / `InstanceFailureBudget` | 已支持单字段失败跳过、依赖跳过、`LinkageError` 熔断；“放弃提交阶段 C”依赖两阶段协议 |
| 伴生补丁类装配 | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §1 | `InitFix.generatePatchClass` / `Lookup.defineHiddenClass` | 每字段生成独立静态直线方法；宿主 hidden nestmate 隔离异常与加载死锁 |
| 构造器尾部插桩器 | 🔶 | [`04-target-design.md`](initfix/04-target-design.md) §4 | `core/src/remold/ref/ConstructorTailInjector` | 纯函数字节码改写：在每个根构造器的每个 `RETURN` 前插入 `ALOAD 0; <切片>; PUTFIELD`；跳过 `this(...)` 委托构造器（避免沿委托链重复执行）与体内已自行赋值目标字段的构造器（构造器逻辑优先）；只插直线代码、写入侧仅 `COMPUTE_MAXS` 不重算栈帧。`ConstructorTailInjectorTest` 8 用例覆盖多根构造器、多返回路径、`this` 读取切片与覆盖保护，并做过变异检查。**缺口**：尚未接入阶段 A 驱动，`InitFix` 还未把改写后的切片交给它 |
| 阶段 A 骨架合成器 | 🔶 | [`04-target-design.md`](initfix/04-target-design.md) §1 | `core/src/remold/ref/StageASkeleton` | 以新类为骨架、把旧方法的执行体整体搬回（不深拷贝、不重映射 label，栈帧天然有效），实现「行为不变、只让新结构就位」；成员按**旧 ∪ 新**处理，新版本删掉但旧体仍在调用的方法必须留着休眠。`StageASkeletonTest` 9 用例，其中 `everyPreexistingMethodKeepsItsOldBodyVerbatim` 做**指令指纹级**结构等价断言（中止回滚依赖阶段 A 行为完全惰性），另覆盖旧 `<clinit>`、被删方法保留、新字段声明、新方法预注入、与构造器插桩组合；变异检查：把 `adoptBody` 短路（等价于「只保留新方法体」）会让 5 条用例变红。**缺口**：尚未接入驱动，没有阶段 A → B → C 的编排与中止回滚 |
| 逐实例补丁驱动与失败配额 | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §2 | `InitFix.runDependentInstanceTasks` / `InstanceFailureBudget` | 依赖失败按实例隔离；单字段每轮实例失败配额封顶 8 次（`MAX_INSTANCE_FAILURES_PER_FIELD`） |
| 存活实例枚举时机 | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §2 | `InitFix.applyPatch` / `collectInstancesForPatch` | 枚举推迟到重定义生效之后，不再预拍快照：此后不可能再产生旧布局实例，枚举天然完备；枚举期间新建的实例由新构造器初始化，条件 CAS 对它们是无害空操作。`InitFixSnapshotTimingTest` 先红后绿守卫（旧实现在此用例上确实为红） |
| 写入协议 (`HotswapBridge`) | 🔶 | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §3 | `HotswapBridge` (`KIND_CONDITIONAL` / `KIND_FORCE`) | 默认条件 CAS；float/double raw bits CAS；跳过计数汇总汇报；`KIND_FORCE` 强制写；**缺自动化 CI 差异验证**（当前 CI 为 Temurin 25，无法区分 raw-bits 与旧实现；JDK 8 raw-bits CAS 为手工验证，非持续自动化覆盖，列入技术债 3.2） |
| 存量覆写扩展 (`@RemoldReinit`) | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §4 | `remold.annotation.RemoldReinit` / `InitFix.buildPatch` / `InitFix.reinitHint` / `InitFix.isClosedSlice` | 豁免 T0 与后续加工门；不豁免切片安全门；支持 `CONDITIONAL` 与 `OVERWRITE`；生效时 `applyPatch` 输出 `[REINIT]` 点名字段与模式，未生效的标 `failed`/`skipped`/`no live instance`；重置时机为"初值表达式变了"，封闭表达式可跳过、开放表达式（跨类调用/他类静态字段）照常每轮重置 |
| 合成标记字段过滤 | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §5 | `ClassDiffUtil.isInternalMarkerField` | 对称过滤 `ACC_SYNTHETIC` 与 `$remold$` 前缀字段 |
| JVMTI 多态堆实例检索 | ✅ | [`05-jvmti-heap.md`](initfix/05-jvmti-heap.md) | `LibTool.getInstances` / `InstanceTracker` | `LibTool.getInstances` C++ 底座；Tag 隔离与全局互斥；`InstanceTracker` 字节码回退 |

---

## 3. 物理内存偏移稳定性与第三方缓存驱逐 (Physical Memory Offset Stability & Reflection Cache Eviction)

设计规格文档：[`docs/offset/01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md)  
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
| 第三方框架实例级缓存驱逐管理器 | ✅ | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §4 | `core/src/remold/evict/ReflectionCacheManager` | `ReflectionCacheEvictor` SPI 契约；接线 `RemoldAgent` 重定义成功出口；原生堆遍历（`LibTool.getInstances`）+ 弱引用显式注册双通道；`ReflectionCacheManagerTest` 4 用例覆盖注册生命周期与异常隔离 |
| Arc `Json` 实例级缓存驱逐器 | ✅ | [`01-offset-and-reflection-cache.md`](offset/01-offset-and-reflection-cache.md) §4.3 | `ReflectionCacheManager.ArcJsonEvictor` | 自动清除 `typeToFields`、`classToDefaultValues`、`classToSerializer` 针对被重定义类的元数据缓存，使新增字段立即可见；`ReflectionCacheManagerTest.testArcJsonInstanceEviction` 先红后绿对照验证 |
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
| **构造器尾部插桩** | P1 | [`04-target-design.md`](initfix/04-target-design.md) §4 | `InitFix` 离线切片提取与直线字节码生成器 (`AliasInterpreter`) | 在阶段 A 构造器尾部注入切片，消除两阶段路径下「阶段 A → 阶段 C」之间由**旧构造器**创建的实例漏补（单次重定义路径的同类缝隙已由枚举后置关闭，见风险 6） |
| **两阶段 Schema-First 重定义** | P2 | [`04-target-design.md`](initfix/04-target-design.md) §1 | 组件已备：`StageASkeleton` / `ConstructorTailInjector` 单测覆盖后**原地冻结** | **暂缓接线**：漏补已由「枚举后置」单独关闭，两阶段仅剩读穿窗口收益，不值得让默认路径每次多付一次 redefine（墙钟 16~76 ms）。**另有一条此前未计入的代价**：`07-p05-probe-results.md` §11 的对照组证实，`redefineClasses` 会让**无关类**的已编译代码一并 `made not entrant`（无论只改方法体还是新增字段）。两阶段是「阶段 A 结构性 + 阶段 C 仅改方法体」两次 redefine，因此一次用户热更会造成**连续两次全局 JIT 逆优化**，预热回落加倍。此外还需引入跨阶段中止回滚与多类批次的状态机复杂度（单次 redefine 天然原子）。 |
| **PatchPlan 基线指纹校验** | P1 | [`04-target-design.md`](initfix/04-target-design.md) §2 | 离线分析器导出协议 | 校验 Target 当前内存字节码哈希与构建期基线一致性，防止类漂移热更造成严重字节码损坏 |
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

### 4.2 技术债与死代码消除清单 (Tech Debt & Code Cleanup)

| 债务 / 清理项 | 优先级 | 影响位置 | 现状分析与消除方案 |
|:---|:---:|:---|:---|
| **`tier5Matches` 残余死字段清理** | P2 | `AnonClassAligner.AlignStats` / `recordTierMatch` | Tier 5（物理类名盲配）已彻底拔除，但统计结构体中仍残留 `tier5Matches` 字段、日志拼装与 `recordTierMatch` 的 `case 5` 分支；该分支**无任何调用者传入 5**，属可安全移除的死代码，需连同日志字段一并清理，避免误导维护者。 |
| **嵌套深度 strict 熔断缺少夹具** | P2 | `AnonClassAligner.align`（`maxLevel > 4`）/ `RemoldAgent.ANON_STRICT` | 该阈值是**诊断阈值而非能力边界**：非 strict 模式下仅 `warn`（层级推进本身与深度无关，真正的闸门是 `MAX_ANON_PER_HOST` 与 `ALIGN_TIMEOUT_MS`），strict 模式下抛 `AlignmentRejectedException`。缺的是「`maxLevel > 4` + strict → 宿主组原子拒绝」的负向夹具，判定逻辑本身无须改动。 |
| **JDK 8 raw-bits CAS 自动化 CI 差异验证缺失** | P2 | `.github/workflows/ci.yml` / `HotswapBridge` | 当前 CI 仅运行在 Temurin 25，无法区分 raw-bits 与旧实现；JDK 8 raw-bits CAS 为手工验证，非持续自动化覆盖；需在 CI 矩阵中增加 JDK 8 运行期任务以持续守护差异逻辑。 |
| **Mixin 目标类原始字节码读取缺兜底** | P2 | `plugin-mixin` `MixinHotSwap` | 目标类重织入依赖 `Class.getResourceAsStream` 读原始字节码；Fabric dev 环境下若取不到（日志「读不到 ... 原始字节码」），当前无回退路径。缺的是 `IClassBytecodeProvider.getClassNode(name, false)` + ASM 回写兜底。 |
| **未加载的 `@Mixin` 类被当作新类注入** | P3 | `core` `RemoldAgent` 新类注入路径 | 未加载且带 `@Mixin` 的类会走新类注入并打印 `[INJECTED]`，功能无影响但属日志噪音与多余类定义；彻底消除需在注入路径做常量池级 `@Mixin` 识别后跳过。 |
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
- **根治与关闭条件**：落地路线图 §4.1“两阶段重定义与构造器尾部插桩”；或在 HotSwap 期间通过 JVM TI 暂停全部应用线程（代价过高，目前维持极窄竞态记录）。
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
- **残余边界**：两阶段重定义路径下，阶段 A 到阶段 C 之间由**旧构造器**创建的实例仍会停在默认值——那段缝隙要等构造器尾部插桩接入阶段 A 驱动才关闭。
- **根治与关闭条件**：两阶段路径落地构造器尾部插桩（[`04-target-design.md`](initfix/04-target-design.md) §4）。
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
- **风险描述与底层机制**：**消除目标已登记在路线图 §4.1「两阶段 Schema-First 重定义」，此处不重复描述机制**；本条只记录在两阶段接入驱动之前，这段窗口是**敞开的**，以及它当前的实际影响面。
- **当前敞开的程度**：读取新增字段的代码在窗口内拿到类型默认值（引用类型 `null`、数值类型 `0`），不抛异常也不打日志。窗口 = 枚举 + 弱引用往返 + 条件 CAS，实测拆分见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §10：
  - 枚举成本 = **无关对象约 8 ns/个**（全堆遍历固定项）+ **匹配实例约 0.3~1 µs/个**（打 tag 与物化数组）；
  - 极端场景（目标类自身 100 万实例）：`applyPatch` 约 1551 ms，其中 CAS 仅 83 ms、弱引用往返 389 ms；
  - 现实场景（堆里几百万对象、目标类只有几百个实例）：实测 100 个目标 + 100 万无关对象仅 8 ms，窗口在**几十毫秒**量级；
  - **随批次线性放大的项**：`getInstances` 按类调用，批次里 N 个待补类 = N 次全堆遍历。
  - **暴露时间要扣掉 STW 段**：实测（M6）每次 `getInstances` 是一次 safepoint，1M 匹配实例时 `HeapIterateOperation` 单次 216~242 ms；停世界期间业务线程读不到未补字段。心跳吞吐只有自由速率的 67%，是「一段 STW + 其余阶段并发」的混合，不是全程争抢。
- **触发条件**：窗口内有线程执行读取新增字段的代码——本类新方法体、同批重定义的其它类、反射/序列化路径、任意业务线程。
- **当前缓解措施**：
  1. 热更通常在主循环安全点或调试暂停期触发，窗口内几乎没有业务读；
  2. `HOTSWAP_LOCK` 只互斥热更调度与事务处理，**不阻塞应用线程读**，因此不构成防护；
  3. T0 零值等价字段不受影响（默认值即正确值），只有真正需要写入的字段暴露在窗口里；
  4. `PatchReport` 与条件 CAS 跳过计数可在事后定位"这次写了哪些字段"，但无法预防窗口内的读。
- **根治与关闭条件**：两阶段重定义接入驱动后关闭——该路线图条目已降为 P2、暂缓接线（见 §4.1），因此本条窗口在当前主线中**保持敞开**，属已知残余风险。`TwoPhaseProbe` 已在 JBR 21 上实测该路径：阶段 B 期间旧方法体仍返回旧值（`v1:1`），窗口内无读穿。
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

### 风险 11：方法删除没有任何门禁，与字段删除同类（已收口）
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
  判据实现在 `RemovedMemberReaders`：实例方法走 `isSubtype` + `selectMethod`（JVMS 5.4.6，含 maximally-specific 规则），静态/private/`<init>` 沿用向上解析。接线点在 `RemovedAgent.decideLayout`，且**必须在 `compatible()` 早退之前**——本轮可能只删了方法、字段一个都没动。
- **已知边界**：扫描范围只覆盖工程内类（JDK 与第三方 jar 不在范围内，见风险 12）；`invokespecial` 的 `super.m()` 无法与 `invokevirtual` 区分（常量池都记 tag 10），按"可能分派"处理，方向偏保守；pre-11 的 `access$` 合成访问器未折算（编号顺移会造成静默错读，见风险 12 第二层机制）。
- **关联设计规范**：[`docs/topology/07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1。
- **关联架构决策**：无。

### 风险 12：`WAIVE` 的"无存活实例"判据对字段删除不成立
- **风险描述与底层机制**：`decideLayout` 在不兼容但 `liveness == NONE` 且不涉及静态字段时返回 `WAIVE`（放行），理由写在代码注释里——"没有状态会被错读；新实例走新构造器、初始化正确"。这条理由**只对新增字段成立**。删除字段的风险不是"已有实例被错读"，而是**未来的实例被陈旧代码读**：`A` 被放行重定义后字段消失，任何仍持有 `GETFIELD A.x` 的已加载类，在**之后**新建的 `A` 实例上执行时抛 `NoSuchFieldError`。这与热更时刻有没有存活实例无关。
- **触发条件**：`A` 的字段被删除且热更时 `A` 无存活实例（`WAIVE` 放行）→ 之后有批次外代码读取该字段。批次外代码的成因见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §13.5（预编译依赖、批次撕裂；"增量编译未传播"是弱成因）。
- **当前缓解措施**：`WAIVE` 分支不再适用于**删除**——`decideLayout` 对 `REMOVED_FIELD` 一律走"批次外读者为空"：证明无读者 → `WAIVE_NO_READERS`；有悬空 / 抽象方法缺失 / **静态性翻转** → `REJECT`。**扫描未完成时同样拒绝**（不退回 liveness）：liveness 回答的是"现在有没有实例会被错读"，而删除的风险是"之后新建的实例被陈旧代码读"，两者正交——退回 `WAIVE` 就等于把"没扫成"读成"没读者"。`WAIVE` 现在只留给**改类型 / 改静态性**。**同静态性的改指向（回退到父类同名字段）不算读者**，只记日志——旧字节码本来就能正确解析，全量重编也一样（§16.4）。编译期常量**放行**：内联的读者运行期不解析该字段，未内联的会被 `hits` 抓到（§16.1）。
- **第二层机制（pre-11 合成访问器，实测）**：javac 8 的 `access$NNN` 桥按成员序号命名，删掉一个 private 字段会让后面的成员**顶上前面的编号**。实测两个 private 字段 `x`/`y`：删 `x` 后 `access$000` 仍在、但方法体从 `getfield x` 变成 `getfield y`。于是陈旧读者两条调用的失败形态不同——调 `access$100` 得 `NoSuchMethodError`（桥被删，归风险 11 足够），调 `access$000` 则**静默拿到 `y` 的值**（名字与描述符都没变，diff 里看不到删除，只看得到"方法体改了"）。所以"归到风险 11"只覆盖一半，漏掉的正是静默错读。判据必须落在"**合成访问器的旧目标**"上：`access$*` 的方法体目标变了，等价于旧目标被删，要对旧目标做读者扫描。Java 11+ 的 nestmate 产物没有这层桥，直接 `GETFIELD`，不存在此问题。
- **根治与关闭条件**：`WAIVE` 的 liveness 代理对删除问错了问题——它回答"**现在**有没有实例会被错读"，而删除要问"**批次落地后**还有没有代码会读"。修法**不是**"`REMOVED_FIELD` 不适用 `WAIVE`"（那会误杀两类安全删除：删 `private` 字段的读者被约束在 nest 内、正常连带重编的批次里读者的新字节码不再读该字段），而是把删除的判据换成**批次外读者为空**：对每个已加载类，批次内的扫**新**字节码、批次外的扫**当前**字节码，命中 `Fieldref` 即拒绝。**匹配 owner 时必须沿继承树展开，并按 JVMS 5.4.3.2 判"解析是否真的落在 `A` 上"**——`Fieldref` 的 owner 是编译期静态类型，不是声明类，删 `A.x` 时读者可能写成 `Field Sub.x`（实测见 §13.6.2），只匹配 `A` 会漏报；但子类自己声明了同名同描述的 `x` 时 `Sub/x` 解析到 `Sub.x`，**父接口里同名的字段还会抢在父类之前命中**（实测：`Sub extends A implements I`，从 `Sub` 解析 `x:I` 落到静态 `I.x` 而非 `A.x`），这两种情况都必须排除，否则误报。private 字段例外：JLS §6.6 规定 private 不被继承，`s.x` 编译不过、必须强转成 `A`，而强转后 owner 恒为 `A`，展开与截断都可省。**注意这个判据的完备性完全取决于扫描范围**——只扫已加载类会漏掉未加载的陈旧读者，那种"放行"给的是虚假安全感，比一律拒绝更糟；最低档位应覆盖到工程构建输出目录。做法、扫描范围三档与成本见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §13.6。该判据**已接线**（`decideLayout` 对 `REMOVED_FIELD` 用 `WAIVE_NO_READERS` / `REJECT`，扫描未完成时退回存活实例判据），扫描范围取"已加载工程类（内存字节）+ 工程构建输出"，成本见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §14.6。**仍敞开的部分**：扫描范围不含 JDK 与第三方 jar（那里的陈旧读者会漏，属于"不完备但成本可接受"的已知取舍）；pre-11 的 `access$` 合成访问器未折算。
- **关联设计规范**：[`docs/topology/07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1。
- **关联架构决策**：无。

---

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
| Mixin 重织入（普通类） | 🔶 | `plugin-mixin` `MixinReWeaver` | 重定义前把源码级字节码交回 Mixin 织入，非目标类按数组同一性跳过拷贝；端到端需真实 Fabric 客户端验证 |
| Mixin 类自身变更 → 目标级联重织入 | 🔶 | `plugin-mixin` `MixinHotSwap` | `IMixinTransformer.reload` 刷新 `MixinInfo` 后对已加载目标类走 `redefineDynamic`；结构性改动抛 `MixinReloadException` 时提示重启 |
| 标准 JVM 能力探测与降级 | ✅ | `RemoldAgent.probeEnhancedHotswap` / `mustSkipForMissingEnhancedRedefinition` / `degradeForMissingEnhancedRedefinition` | 厂商身份 + flag 文本双条件判定；结构变化类在无增强能力时摘批；`FORCE_STATIC_LAMBDA` 强制降级、`INIT_FIX` 刻意不降级；`EnhancedHotswapGateTest` 9 用例含变异检查 |

---

## 7. 工程化、构建与分发 (Engineering, Build & Distribution)

> 本节登记**制品产出与对外分发**链路的实现状态。此前的状态表只覆盖运行时机制，导致「仓库已上线、CI 已跑矩阵、JitPack 坐标已被模板消费」等既成事实长期被误记为待办。

| 条目 | 状态 | 落地位置 | 验收证据与已知边界 |
|:---|:---:|:---|:---|
| 独立仓库与远程 CI 矩阵 | ✅ | `.github/workflows/ci.yml` | 3 个 job：`test`（`setup-java` 装 Temurin 8/11/17/21 + runner 25，`-Dhstest.requireJdks=true` 与 `auto-download=false` 强制五套工具链真实存在，跑 `./gradlew check`）、`test-dcevm`（JetBrains Runtime 21 真实 DCEVM 下跑 `:tests:liveDcevmTest`）、`build-native`（g++ 编 Linux x86_64 `.so` 与 Android arm64-v8a `.so` 并上传制品） |
| JitPack 对外分发 | ✅ | `jitpack.yml` + tag `v0.1.0`~`v0.1.3` | 对外坐标 `com.github.I-hope1.Remold:<module>:<tag>`（由 JitPack 按仓库名推导，与项目 `group` 是两个命名空间）；`jitpack.yml` 固定 `openjdk25` 并执行 `publishToMavenLocal -x test`；`README.md` §6、两个模组模板、`remold.gradle(.kts)` 的 agent 与插件解析均消费该坐标 |
| GitHub Release 发布物 | 🔶 | 手工构建 + 手工上传资产 | `v0.1.0`~`v0.1.3` 四个 Release 均已发布，最新 `v0.1.3`（2026-10-24）含 `remold-agent-0.1.3.jar` / `remold-plugin-arc-0.1.3.jar`；**缺口**：`ci.yml` 无 release/publish job，发布物靠本地手工构建上传，曾发生「Release 资产早于功能代码构建」的事故，需要 tag 触发的自动化构建 + 资产上传 |
| Maven Central 分发 | ⬜ | `build.gradle` 的 `group = 'io.github.i-hope1.remold'` | 仅 `README.md` 标注为「预留坐标」，未实际发布；走通需 Central Portal + GPG 签名 + `io.github.<user>` 命名空间验证。当前 `publishToMavenLocal` 落盘该 group 仅供本机联调兜底（`remold.gradle(.kts)` 的 m2 候选路径按文件探测，文件不存在即跳过） |
| mod-tools 消费侧切换与内部副本清理 | ⬜ | 本仓库之外（mod-tools 仓库） | 依赖坐标切换与仓内 core 副本删除均发生在 mod-tools 仓库，本仓库无法提供验收证据；其前置条件（JitPack 坐标可用）已满足 |

---

## 8. 前置验证与实测数据 (Pre-flight Measurements)

| 条目 | 状态 | 落地位置 | 验收证据与已知边界 |
|:---|:---:|:---|:---|
| P0.5 前置微验证（连续 Schema Redefine / 阶段 A 链接 / 堆遍历规模） | 🔶 | `tests/src/P05Probe.java`、`tests/src/P05Agent.java`、`tests/build.gradle`（任务 `p05Probe`）、`.github/workflows/ci.yml`（任务 `p05-probe`，JBR 17/21 矩阵） | JBR 21.0.9 本机 **61/61 断言通过**（M1~M9）：连续两次 schema 变化均被接受（阶段 A p50 30.9 ms、阶段 C p50 27.3 ms；schema 变更平均触发 1 次 GC ≈ 5 ms，纯方法体变更 0 次）；阶段 A 预注入的私有实例方法、私有静态方法与新 lambda 可被 nestmate 伴生类链接，并带「阶段 A 之前必抛 `NoSuchMethodError`」的负向对照；1M 实例堆遍历 0.87 s、结果数组 4 MiB；M7 分离实验（具名类删字段）见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §13；M8（方法删除：向上解析 vs 向下分派，三种失败形态）见 §15；M9（静态字段删除的三种形态 + 编译期常量内联 + `getfield`/`getstatic` 双向 ICCE）见 §16。**缺口**：JBR 17 数据待 CI `p05-probe` 矩阵产出后回填；M3 的「1M 实例枚举」在内存压力下有**已知偶发失败**（同一轮内重跑即过，故该探针不挂 `check`）；单机单次采样，未做 Linux 对照与 JIT 预热隔离。方法与原始数据见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) |
| 两阶段编排端到端验证 | 🔶 | `tests/src/TwoPhaseProbe.java`、`tests/build.gradle`（任务 `twoPhaseProbe`） | JBR 21 本机 **39/39 断言通过**：`阶段 A → redefine → 阶段 B 补丁 → 阶段 C redefine` 全链路跑通（阶段 A 17~37 ms、阶段 B 7 ms/1 实例、阶段 C 31~35 ms）；对照组证明不插桩时「枚举之后、阶段 C 之前」新建的实例永久停在 `v2:1,0`；另含 **JIT 预热分配点**场景（对象写进静态数组保证逃逸、按 cid 对齐 safepoint 窗口）：行为正确，但**对照组证伪了因果**——redefine 无关类同样会让该 cid 失效，故此场景只记录"未观测到损坏"，**不作为机制已验证的证据**（见 `07-p05-probe-results.md` §11）。另含 **在途旧构造器帧**场景（线程阻塞在旧 `<init>` 中途时 redefine，两条防空转断言先证明帧确实在途、且新旧构造器写入可区分的值）：在途对象 `b=1`（旧字节码）、`c` 完好、新对象 `b=2`/`d=42`，证明 DCEVM 在重定义内部正确处理了在途帧的字段偏移（§12）。**缺口**：未覆盖中止回滚、多类批次与真实多线程分配竞争；探针在主流程之外，驱动尚未接入。见 [`07-p05-probe-results.md`](initfix/07-p05-probe-results.md) §9、§11、§12 |
| 物理偏移稳定性与漂移探针 (OffsetStabilityProbe) | ✅ | `tests/src/OffsetStabilityProbe.java`、`tests/build.gradle`（任务 `offsetStabilityProbe`） | JBR 21.0.9 本机 **47/47 断言通过**（A1~A4 单类布局规律/空洞填补/类型重排、B1~B3 继承体系漂移步长、C1~C2 VarHandle 错位破坏、D1~D2 使用方 C2 逆优化与重编译恢复、E1~E2 Field 与 MethodHandle 访问器自愈边界）；与 `LayoutPredictor` 及 `SuperclassDriftGate` 形成事前预测阻断闭环 |



