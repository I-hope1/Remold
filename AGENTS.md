# Remold 开发规则

HotSwap / InitFix 核心开发规范。所有规则与不变量在本文件定义，完整代码索引见 [`docs/code-map.md`](docs/code-map.md)，探针命令与变异检查清单见 [`docs/testing-guide.md`](docs/testing-guide.md)，实现状态唯一真相源见 [`docs/status.md`](docs/status.md)。

## 1. 作用域

- **InitFix**：只处理本次 Redefine **新增声明的字段**（存量实例 + 静态）。禁止实现：修改已有字段初值（`OVERWRITE` / 显式重放通道除外）、删除字段、修改方法签名、动态修改字段描述符类型。
  - **字段删除**：具名类走重定义层，匿名类合成字段走 `LayoutGate.check`。判据为"批次外读者为空"（批次内扫新字节码、批次外扫当前字节码，即 `RemovedMemberReaders` + `decideLayout`），不能简化成"有删除就拒"；有存活实例且无读者时 `WAIVE_NO_READERS` 放行；**扫描未完成时一律拒绝，不退回存活实例判据**。静态字段共用同一判据但**无 liveness 兜底**；编译期常量（`ConstantValue`）放行；合法回退（同静态性父类/接口字段）不算读者，只阻断 `hits`/`abstractMissing`/静态性翻转 ICCE。详情见 `docs/initfix/07-p05-probe-results.md` §13、§16。
  - **方法删除**：与字段规则不同——实例方法还涉及 JVMS 5.4.6 向下分派（owner 可为 `A` 的任何祖先）。只阻断 `NoSuchMethodError` 与 `AbstractMethodError`，**合法回退到祖先实现放行**。扫描未完成时一律拒绝（无 liveness 兜底）。详情见 `docs/initfix/07-p05-probe-results.md` §15。
- **例外与显式重放通道**：
  - `@RemoldReinit(mode = OVERWRITE)`：允许覆写存量值。
  - **L1 存量初值重放**（默认关闭，`-Dremold.agent.init_drift.parents=<fqcn,...>`）：白名单父类匿名子类 + 非 final 基本类型 + 编译期常量旧/新初值，按"旧初值条件 CAS"写匿名类自身实例（当前值 ≠ 旧初值跳过）；不写 `FieldLedger`，`@RemoldReinit` 优先。规格：`docs/initfix/12-L1-existing-initializer-replay.md`。
  - **A 档 Kotlin `apply {}` 静态持有者重放**（默认关闭，`-Dremold.agent.init_drift.apply_holders=<fqcn,...>`）：仅处理白名单 `object`/顶层 `val` 类直线 `<clinit>` 中的唯一 `NEW` + 唯一 `PUTSTATIC dest`（基本类型/非 final/非 synthetic/非 `@RemoldReinit`、旧/新值均为编译期常量且不同），按 `GETSTATIC dest` 取对象并旧初值条件 CAS；不写 `FieldLedger`、不改 `patchGenerated`。规格：`docs/initfix/13-kotlin-apply-replay.md`。
- Android ART 与 `$extras` 扩展槽属于独立子项目（Engine II，`docs/HOTSWAP.md`），不在此处理。
- 运行时前提：JBR 17/21+（`Lookup.defineHiddenClass` NESTMATE + `jdk.internal.misc.Unsafe`）；DCEVM/TravaJDK 11（`Unsafe.defineAnonymousClass` + `sun.misc.Unsafe`；产物 v55，JDK 8 无法加载）。

## 2. 五条不变量（任何改动都不得破坏）

1. **未初始化守卫**：`UNSAFE.shouldBeInitialized(clazz) == true` 时直接跳过修补。
2. **`PENDING` 弱键**：`synchronizedMap(WeakHashMap<Class<?>, PendingPatch>)`。`PendingPatch` 不得持有 `Class<?>` 强引用；保留 5 分钟 TTL 清扫（`PENDING_TTL_NANOS`）。
3. **`PatchReport` 解耦**：只存类名字符串和不可变枚举，不得持有 Class 或 ClassLoader。
4. **`LinkageError` 熔断**：补丁抛 `LinkageError` 立即中止当前类。唯一例外：`BootstrapMethodError` 且 `getCause()` **不是** `LinkageError`，视为单字段链接失败，只隔离该字段。判定保持窄。
5. **`afterRedefineFailed`**：Redefine 失败时立即注销并弹出暂存补丁。

## 3. 编码规则

**安全优先**
- 拿不准就拒绝。宁可漏补，不可静默写入过期值。
- 切片（含递归调用闭包）禁止调用"已存在但本次被修改"的方法。
- `Object.toString` 例外只允许 Kotlin `trim`/`trimStart`/`trimEnd` 展开形态（`docs/initfix/01-safety-gate.md` §2.1）。`Object.hashCode` 不得放宽；不得新增"任意静态方法"放行来源。
- 判定取不到来源信息（`frames == null` 等）时必须 fail-closed，不要照抄 `builderMutatorReason` 里 `frames == null` 即放行的写法。
- 新增拒绝路径必须经 `InitFix.transform` 统一出口发 `warn`；禁止只调受 `DEBUG` 门控的 `log(...)`。
- `FieldLedger` 只负责"不遗忘"，不负责"放行"；回流字段仍走同一套安全门。

**日志语言（强制）**
- 运行期日志、拒绝/告警提示与用户可见字符串**一律英文**（含多行拼接的每一段），不得出现中文或全角标点；中文仅限注释、Javadoc 与 `docs/`（`tests/src` 建议英文）。
- 守卫：`./gradlew :tests:verifyLogLanguage`（挂 `check`），改动日志后先跑它。

**写入 / 分析期 / 数据结构**
- 默认条件 CAS（`KIND_CONDITIONAL`）；仅 `OVERWRITE` 用 `KIND_FORCE`。`float`/`double` 一律走 raw bits（`compareAndSetInt/Long`），禁止按名探测 `compareAndSetFloat/Double`。
- 分析期层级、修饰符、Nest 查询走 `ClassHierarchyOracle`，禁止 `Class.forName` 或反射；字段名（`String`）是唯一 Key，不支持同名异型字段。
- 以 `Class` 为键的内部表用弱引用，值不得反向持 `Class`，勿换 `java.lang.ClassValue`。保留 Android/ART 路径，新 API 必须反射探测。

**外部引擎查验**
- `.sources/` 为本地只读外部引擎源码镜像（如 `.sources/mindustry/`），已 gitignore；核验 Mindustry / Arc / Fabric / Mixin / ASM 内部机制时**先检索 `.sources/`**（未覆盖再查上游源码包），严禁凭经验猜测；禁止修改或提交 `.sources/`。

**状态管理规则（强制）**
- 唯一真相源：[`docs/status.md`](docs/status.md)。本文件与其它 `docs/` 规格文档不登记实现状态、测试计数、验收证据。
- `docs/**`（`status.md`、`releases/`、`acceptance/` 除外）前 10 行必须有 `文档类型：<设计规格|实测记录|审计记录|决策记录|独立子项目规格|索引>`。设计规格与独立子项目规格禁止交付状态散文与 `✅/⬜/🔶/🟣/❌`；决策记录禁止状态标记；实测/审计记录只写读数且横幅必须点名 `docs/status.md`。
- 守卫：`./gradlew :tests:verifyStatusSingleSource` 与 `:tests:verifyDocStatusMarkers`（均挂 `check`）。
- 代码注释与 Javadoc 只写契约与边界（写"不在当前范围"并指向 `docs/status.md`），不写交付状态词；缺陷现场与根因只进 `CHANGELOG.md`，`docs/status.md` §1～§7 只登记稳定机制状态与残余风险。

**代码风格 & 数据汇报**
- **禁止在代码正文写 FQCN**（一律 `import` 后用简单名；注释/文档/自包含测试夹具源码字符串除外）。
- **数据汇报铁律**：禁止用经验估计或跨轮次/异口径拼接数据下结论；性能结论须满足：(1) 源于实测；(2) 同轮次同口径对比；(3) 耗时具备分布支撑（min/median/max）。缺失则先声明局限，禁止直接下结论。

## 4. 代码地图与子系统索引

完整 60+ 项类级代码地图见 [`docs/code-map.md`](docs/code-map.md)：

| 子系统 | 核心包 / 位置 | 索引与规格 |
|:--|:--|:--|
| InitFix 存量初始化与代际台账 | `core/src/remold/ref/`、`core/src/remold/vcs/Generational*` | `docs/code-map.md` §1、`docs/initfix/` |
| 两阶段 Redefine、JVMTI 批扫描与布局门 | `RemoldAgent`、`LibTool`、`LayoutGate`、`RemovedMemberReaders`、`StageASkeleton` | `docs/code-map.md` §2、`docs/initfix/04`·`05` |
| Agent 调度、防抖、Trigger 与看门狗 | `core/src/remold/`（`DebounceWindow`、`BuildTrigger*`、`StartupGate`、`BatchWatchdog`、`BytecodeSources`） | `docs/code-map.md` §3、`docs/runtime/01` |
| 字节码 VCS、派生缓存与 Raw 对齐 | `core/src/remold/vcs/`、`LambdaAligner`、`OrphanBodyRetention` | `docs/code-map.md` §4、`docs/vcs/01`·`03`·`05` |
| 加载器插件、Mixin、偏移与 Gradle 插件 | `plugin-{fabric,mixin,forge,arc}/`、`core/.../offset/`·`evict/`·`reflect/mixin/`、`gradle-plugin/` | `docs/code-map.md` §5、`docs/mixin/`、`docs/offset/` |
| 测试夹具、外部源码与专项探针 | `tests/src/`、`tests/ktfix/`、`.sources/` | `docs/code-map.md` §6、`docs/testing-guide.md` |

## 5. 实现状态

本文件**不登记任何实现状态**。逐项状态、落地位置、验收证据、已知风险与技术债见 [`docs/status.md`](docs/status.md)；历史战役实证战报见 [`docs/battles/`](docs/battles/README.md)；逐版发布历史见 [`CHANGELOG.md`](CHANGELOG.md) 与 [`docs/releases/`](docs/releases/)。

## 6. 测试要求

**【测试与验证防空转铁律】** 严禁将"通过的测试"等同于"机制已验证"。所有有效性断言须满足三要素：
1. **前置基线确认**：触发变更前先断言目标处于预期生效态，排除"原本即空"的假阳性；
2. **因果链终局断言**：落在业务可观测最后一环（实际读写结果/状态终局），禁止用调用成功/日志/计数器等代理信号替代；
3. **严格时间窗约束**：状态转换与失效绑定 $[T_0, T_0 + \Delta t]$，区分"主动生效"与"TTL 过期"。

写完测试必须自问：**若注释掉核心生效代码，该断言是否必挂？** 并配齐两种对照（缺一不可；确无特异性对照须在注释写明原因）：
- **去触发**：去掉触发动作（如 `redefineClasses`），断言必红；
- **换无关输入**：换成无关同类操作，断言必不成立。

**核心门禁与通用纪律**：
- `./gradlew :tests:initFixOracle`（挂 `check`，纯 JVM）：Kotlin 夹具目录由 `-Dremold.ktfix.v1/v2` 传入，**绝不能**进入任何 classpath。迭代先跑它，收尾跑 `./gradlew check`。
- `./gradlew :tests:test`（挂 `check`）：测试数下限由 `tests/build.gradle` 的 `hstestMinTests` + `verifyTestCount` 守卫（总数 ≥ 基线且 `skipped == 0`）。
- 新增或放宽安全门必须有负向阻断断言；修缺陷必须有先红后绿回归断言；新增/放宽安全门例外或关键修复必须做**变异检查**（故意改坏实现确认对应断言变红再恢复）。
- 新增测试注释/断言建议用英文；涉中文输出任务须带 UTF-8 JVM 参数；改 `float`/`double` CAS 后须在真实 JDK 8 补测（`initFixOracle` 跑在 JDK 25，区分不出两种实现）。
- 专项探针命令（L1 / A / P0.5 / Forge·NeoForge E2E / PR1~PR4 / TwoPhase 等）与变异清单详表见 [**`docs/testing-guide.md`**](docs/testing-guide.md)。

## 7. 按需文档

修改对应子系统前先读规格与索引（完整总目录见 [`docs/index.md`](docs/index.md) / [`docs/index_en.md`](docs/index_en.md)）：
- **InitFix**：`docs/initfix/` — `01-safety-gate.md`（分层/效应/参数回溯）、`02-closure-and-ledger.md`（闭包/成环/台账）、`03-runtime-driver.md`（补丁驱动/`@RemoldReinit`）、`04-target-design.md`（两阶段/跨类拓扑）、`05-jvmti-heap.md`（JVMTI 批扫描）、`06-decisions.md`、`07-p05-probe-results.md`、`09-hotswapinit-design.md`、`12-L1-existing-initializer-replay.md`、`13-kotlin-apply-replay.md`。
- **匿名类拓扑对齐**：`docs/topology/` — `01`~`08`（不变量/准入/流水线/Tier/开关/LayoutGate/决策 ADR）。
- **Lambda 对齐**：`docs/lambda/` — `01-capture-bridge.md`、`02-orphan-body-retention.md`、`03-publication-contract.md`。
- **字节码 VCS / 台账**：`docs/vcs/` — `01-bytecode-changeset-vcs.md`、`03-live-raw-to-raw-alignment.md`、`05-generational-field-ledger.md`（及对应实测 `02`/`04`/`06`）。
- **Agent 调度与运行时实测**：`docs/runtime/` — `01-build-trigger-mode.md`、`03-jbr-dcevm-jit-drain-tail-latency.md`、`04-enum-constant-append-infeasibility.md`。
- **偏移与缓存驱逐**：`docs/offset/01-offset-and-reflection-cache.md`。
- **Mixin 子系统**：`docs/mixin/01`~`08`（类加载权威/覆盖层/重织入/结构突破/合成类闸门/运行诊断/决策台账）。

## 8. 排查纪律（证据与观测）

按"先证据、后假设、再修复、再汇报"执行：
1. **日志"没有"先查级别**：`log(...)` 默认关闭，拒绝必走 `warn`；"无日志"仅当路径上全分支均打日志才作证据，否则只记"未观测到"。
2. **先加观测再假设**：用阶段首尾打点区分"未进入 / 进入跳过 / 生效"；异步入口须显式 `catch` 上报。
3. **未验证的测量不得改产品代码**：先对测量工具做强弱对照；以业务终局结果为准（勿仅凭反射元数据等间接手段下结论）。
4. **先问直接事实**：区分用户陈述的"事实"与"假设"——假设必须验证；若陈述与一手观测冲突，明确告知用户以观测为准。
5. **合成探针非真实环境替身**：探针绿而真机坏时先列"探针缺了哪些成分"，不宣布"无法复现"；版本对照用 `git worktree` 同探针同序列比较。
6. **定位 ≠ 修完**：闭环需 (1) 原始复现序列实测看到预期终局 + (2) 回归测试变异检查通过 + (3) 对照组保持绿；未在真实环境复跑前只表述为"已定位并加了防护"。
7. **结论分列"已观测"与"推断"**：已观测须写明手段/读数/输入；推断须写明前提与所缺实验，禁用"说明/证明/显然"等断言措辞。

## 9. 工具并发调用纪律

- **批量发出 ≠ 并行执行**：同一消息的多个前台调用按序处理；真并行只能走后台任务 / 子代理 / 团队成员 / `workflow`。
- **按依赖选通道**：多路只读用同消息批量省往返；长跑构建/`gradlew`/探针用后台任务（禁前台批量跑重任务）；独立调研/实现用 subagent；大改动拆分用团队+共享任务板（写域不相交，Lead 复核 diff）；大规模扇出用 `workflow`。
- **禁忌**：有前后依赖不并发；并发分支副作用必须幂等且不共享写域；性能/耗时探针严禁与其他重任务并发，按单轮同口径单跑。

## 10. Git 分支与 Worktree 纪律

**三层分支模型（唯一口径）**：

```
main ──► feat/<大特性>            ← 唯一合流目标 & 唯一 PR 来源
              ├── feat/<战斗>     ← 单个战斗的本地分支
              ├── feat/<战斗>
              └── agent/batch-*   ← battle_dispatch 拉起的隔离 worktree
```

1. **`main` 只承接 PR**：不直接提交、不在其上开分支干活；主工作区尽量停 `main` 且保持干净，确保
   `git pull --ff-only` 永不阻塞。需要在主工作区干活时，切到 `feat/<大特性>` 或 `feat/<战斗>`，
   收尾后必须切回 `main`。
2. **`feat/<大特性>` 是唯一合流目标**：从 `main` 切出，承载一条特性线的全部战斗；它的存在周期就是
   这条特性线的周期。一次大特性可以有 N 个战斗，但它们都只往这里合，不互相合。
3. **`feat/<战斗>` 只做一件事**：从 `feat/<大特性>` 切出（不是从 `main`），完成后合回
   `feat/<大特性>`；**禁止**对它直接发 PR、**禁止**直接合 `main`。`battle_dispatch` 生成的
   `agent/batch-*` 属于同一层，同样只合大特性。
4. **战役初始化必须指定集成基线**：`battle_init(base_branch: "feat/<大特性>")`，于是 `battle_merge`
   天然合到大特性而不是 `main`；同一仓库并发多条特性线时，所有 `battle_*` 调用都要显式带 `campaign`。
5. **批次 worktree 隔离**：各 `agent/batch-*` 仅在各自 worktree 绝对路径内工作，写域不相交；联调、
   合流与**所有测速探针**由 Lead 串行单刷；关键修复留 `battle_probe` 与 `battle_mutation` 真实证据
   （存活如实写战报）。
6. **合流与结案**：仅通过验收的战斗才按序合回 `feat/<大特性>` 并跑 union 全量；`battle_audit` PASS 后
   由 `battle_seal` 将战报写入 [`docs/battles/`](docs/battles/README.md)（提交前缀 `docs(status): ...`）
   并自动清理临时 worktree（保留 `.battle/campaigns/<id>/`）。
7. **PR 只从 `feat/<大特性>` 发**：摘要把该特性线下所有战斗的成绩、退出码、读数与缺口一起交代
   （不贴大段源码与原始 log）→ 等远端 **8/8 CI** 全绿后合并 → 回主工作区 `git pull --ff-only`，
   再清理已合入的 `feat/<战斗>` 与 `agent/batch-*` 分支。
8. **审计在合流之前，合流在 PR 之前**：`battle_report` / `battle_audit` 必须在批次分支合回
   `feat/<大特性>` **之前**跑完。先合再审计会让"批次分支相对基线"变成空 diff，审计会判
   `NOOP_BRANCH`（不是没有提交，而是提交已经进了基线）。同理，PR 一旦合入 `main`，整条特性线的
   批次分支相对 `main` 都会变成 no-op，此时再审计必然被拒。
9. **失败往回修，不往前糊**：集成基线或 PR CI 上暴露的问题，回到对应 `feat/<战斗>` 修，重新走
   probe/mutation 与合流；禁止在 `feat/<大特性>` 或 `main` 上直接打补丁绕过证据链。
