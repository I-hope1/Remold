# InitFix 开发规则

InitFix：热更新（Redefine）后，为**新增字段**初始化存量实例和静态环境的系统。
修改 InitFix 相关代码前先读本文件；细节按文末索引按需读取。

## 1. 作用域

- 只处理本次 Redefine **新增声明的字段**（存量实例 + 静态）。
- 禁止实现：修改已有字段初值、删除字段、修改方法签名、动态修改字段描述符类型。
  - 删除字段在**具名类**上被重定义层拒绝（`REMOVED_FIELD`），但在**匿名类合成字段**上放行（`LayoutGate.check` 判 `COMPATIBLE`）——两层的判据都是"**批次外读者为空**"（批次内扫新字节码、批次外扫当前字节码），匿名类可证、具名类**尚未实现**这个证明（做法见 `docs/initfix/07-p05-probe-results.md` §13.6）。注意不能简化成"有删除就拒"——删 private 字段与正常连带重编的批次都是安全的。注意**有存活实例时被拒的是删字段的类本身**（diff 里就有 `- x`），它连同匿名类被移出本批、保持旧字节码，所以"读者在批次外"轮不到发生；**无存活实例时会被 `WAIVE` 放行**，而那条理由对删除不成立（`docs/status.md` 风险 12）。实测后果见 `docs/initfix/07-p05-probe-results.md` §13：布局不压缩、废弃槽位不复用，失败形态是批次外读者抛 `NoSuchFieldError`（`LinkageError`），不是读到脏数据。
- 唯一例外：字段标 `@RemoldReinit(mode = OVERWRITE)` 时，才允许覆写存量值。
- Android ART 与 `$extras` 扩展槽属于独立子项目（Engine II），不在此处理。
- 运行时前提：
  - JBR 17/21+：`Lookup.defineHiddenClass`（NESTMATE）+ `jdk.internal.misc.Unsafe`
  - DCEVM/TravaJDK 11：`Unsafe.defineAnonymousClass` + `sun.misc.Unsafe`（产物为 v55，JDK 8 无法加载）

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
- `Object.toString` 的例外只允许 Kotlin `trim`/`trimStart`/`trimEnd` 展开形态（条件见 `docs/initfix/01-safety-gate.md` §2.1）。`Object.hashCode` 不得放宽；不得新增"任意静态方法"作为放行来源。
- 判定逻辑取不到来源信息（`frames` 为 null 等）时必须拒绝（fail-closed），不要照抄 `builderMutatorReason` 里 `frames == null` 即放行的写法。
- 新增拒绝路径时，必须经 `InitFix.transform` 的统一出口发 `warn`。不要只调用 `log(...)`，它受 `DEBUG` 门控，默认不输出。
- 台账（`FieldLedger`）只负责"不遗忘"，不负责"放行"。台账字段回流后仍走同一套安全门。

**写入**
- 默认用条件 CAS（`KIND_CONDITIONAL`）。仅 `OVERWRITE` 使用 `KIND_FORCE`。
- `float`/`double` 一律走 raw bits（`compareAndSetInt/Long`），不要按名字探测 `compareAndSetFloat/Double`。

**分析期**
- 层级、修饰符、Nest 查询走 `ClassHierarchyOracle`，不得使用 `Class.forName` 或反射。
- 字段名（`String`）是唯一 Key，不支持同名异型字段。

**数据结构与兼容**
- 以 `Class` 为键的内部表用弱引用，值里不得反向持有 `Class`。不要换成 `java.lang.ClassValue`。
- 本模块保留 Android/ART 路径。引用 ART 低版本不存在的新 API 必须用反射探测，不得直接写进静态字段类型。

**外部引擎查验**
- 项目根目录下的 `.sources/` 是本地只读的外部引擎源码符号镜像（已 gitignore，例如 `.sources/mindustry/` 指向真实的 Mindustry/Arc 源码）。
- 核验外部框架（Mindustry / Arc / Fabric / Mixin / ASM 等）的内部机制时——类层级、字段修饰符、泛型签名、生命周期、注解参数语义——**必须先检索 `.sources/` 对应源码**，严禁凭经验或模糊记忆猜测；`.sources/` 未覆盖的部分再查上游仓库或已发布 jar 的源码包。
- `.sources/` 只读：不修改、不提交、不作为构建输入。

**状态管理规则**
- 状态信息唯一真相源：详细状态见 `docs/status.md`，高层高价值概括见本文件第 5 节。
- **严禁在 Javadoc、代码注释以及各 `docs/` 设计文档中重新混入实现状态信息**（如 `✅`、`[已实现]` 等易腐标记）。设计文档与 Javadoc 仅负责定义客观的设计规格、原理与交互契约；直接重复处一律转为链接。

**数据汇报铁律**
- 严禁使用经验估计、未验证的机制前提或跨轮次/异口径的拼接数据下结论。
- 输出任何性能结论前，必须显式确认：(1) 数据源于实测而非推算；(2) 对比序列属于同一轮次与同一统计口径；(3) 延迟与耗时类结论具备分布支撑而非单一聚合指标。
- 若缺失上述条件，必须先声明数据局限，禁止直接导出结论。

## 4. 代码地图

| 关注点 | 位置 |
|:--|:--|
| 入口与主流程 | `InitFix.transform` / `buildPatch` / `applyPatch` |
| 提取与安全门 | `extractFieldInits`、`checkSafe`、`effectReason`、`blacklistedCallReason`、`builderMutatorReason` |
| 参数回溯 | `scanParamFields`、`sourceFieldNotImmutableReason`、`InitFix.NestView` |
| 离线层级 | `ClassHierarchyOracle` / `HierarchyTreeOracle`，底层 `AnnotationTransformer.HierarchyTree` |
| 台账 | `InitFix.LEDGER`、`getUnpatchedFields` |
| 逐实例驱动 | `runDependentInstanceTasks` / `runIndependentInstanceTasks`、`InstanceFailureBudget` |
| 写入桥 | `HotswapBridge`（`KIND_CONDITIONAL` / `KIND_FORCE`） |
| 构造器尾部插桩 | `ConstructorTailInjector`（`core/src/remold/ref/`，纯函数：根构造器 `RETURN` 前注入字段切片） |
| 哈希语义门控 | `HashSemantics`（`core/src/remold/ref/`，本类内调用图扫 `hashCode`/`equals`/`compareTo` 及其可达方法是否读新增实例字段，只告警不拒绝） |
| 阶段 A 骨架合成 | `StageASkeleton`（`core/src/remold/ref/`，纯函数：新结构 + 旧执行体，成员按旧 ∪ 新） |
| 注解 | `remold.annotation.RemoldReinit` |
| 合成字段过滤 | `ClassDiffUtil.isInternalMarkerField` |
| 堆实例检索（Native） | `LibTool.getInstances` |
| 匿名类与局部类安全门 | `AnonClassAligner`、`LayoutGate`、`LocalClassGuard` |
| `Object.toString` 例外 | `InitFix.allowedStringCoercion`、`isInertProducer`、`isIntermediateObjectToString`（`effectReason` 在白名单之后、黑名单之前调用） |
| 测试夹具（Java） | `tests/src/InitFixOracle.java`（内存 `javac`） |
| 测试夹具（Kotlin） | `tests/ktfix/v1/`、`v2/` 下的 `.kt`，由 `ktfixV1`/`ktfixV2` source set 编译 |
| P0.5 前置验证探针 | `tests/src/P05Probe.java`（配套最小 agent `P05Agent.java`，`./gradlew :tests:p05Probe -Pp05.jbr=<dcevm-jdk>`） |
| 两阶段编排探针 | `tests/src/TwoPhaseProbe.java`（`./gradlew :tests:twoPhaseProbe -Pp05.jbr=<dcevm-jdk>`，串起 A → B → C） |
| 测试判定力审计记录 | `docs/initfix/08-test-audit.md`（变异检查证据；审计产生的缺口与结论，一次性证据不放状态表） |
| `@HotswapInit` 设计契约 | `docs/initfix/09-hotswapinit-design.md`（T4 逃生口的豁免边界、签名与调用基数、重复触发、异常边界、用例清单） |
| 外部引擎源码索引 | `.sources/`（如 `.sources/mindustry/` 指向本地 Mindustry/Arc 真实源码；只读参考，已 gitignore） |
| Mixin 生态扩展插件 | `plugin-mixin/`（`remold.mixin.MixinHotswapPlugin`、`MixinReWeaver`、`MixinHotSwap`） |
| Gradle 插件坐标 | `gradle-plugin/`（`java-gradle-plugin`；把仓库根 `remold.gradle(.kts)` 打进 jar 后展开应用，供 JitPack 的 `plugins { id("io.github.i-hope1.remold") }` 引入；脚本仍是唯一实现，勿在此另写一套） |

## 5. 实现状态概要（详细参见 `docs/status.md`）

详细逐项状态表、落地位置与验收证据参见 [`docs/status.md`](docs/status.md)。本表仅提供核心机制的高价值概括：

| 机制 / 领域 | 状态 | 关键说明 |
|:--|:-:|:--|
| 五条核心不变量 | ✅ | 全面守护，零破坏 |
| 匿名类拓扑对齐 (Tier 1~4) | 🔶 | 彻底废除 Tier 5；未匹配保留孤儿/新名；具名局部类内嵌套匿名类存在原名直通盲区 |
| 实例布局门与局部类止血门 | ✅ | `LayoutGate` 阻断字段变化；`LocalClassGuard` 阻断局部类位移 |
| InitFix 离线元数据 (`ClassHierarchyOracle`) | ✅ | 全程不触发类加载 |
| InitFix 待补台账 (`FieldLedger`) | ✅ | 六种异常与超时场景全覆盖 |
| T0 零值等价 / T3 显式拒绝 | ✅ | 零值零开销放行；危险切片安全门拒绝并在 `transform` 发 warn |
| T1 常量 / T2 纯计算切片 / 效应掩码 | 🔶 | 静态 `ConstantValue` 走专用通道；P0 效应黑白名单 + Kotlin `trim` 窄例外 |
| 参数回溯不可变证明 / 多根构造器共识 | ✅ | 条件 A（final）与条件 B（NestView 单写证明）；全根构造器指纹 100% 一致 |
| 伴生补丁类 / 逐实例驱动与失败配额 | ✅ | 独立静态方法隔离；按实例隔离；单字段失败配额封顶 8 次 |
| 条件 CAS 写入与跳过报告 | 🔶 | 默认条件 CAS；float/double 走 raw bits（JDK 8 raw bits 缺自动化 CI 差异验证）；跳过计数汇总报告 |
| `@RemoldReinit` 存量覆写 | ✅ | 豁免 T0 与后续加工检查；不豁免切片安全门 |
| Native JVMTI 堆遍历 (`LibTool`) | ✅ | C++ 底座；Tag 隔离与全局互斥；`InstanceTracker` 字节码回退 |
| 两阶段重定义 / 构造器插桩 / 跨类拓扑 | ⬜ | 路线图设计（见 `docs/initfix/04-target-design.md`） |
| 真实 kotlinc 产物验证 | 🔶 | `object` 单例与 `trim` 家族已验证；其它 Kotlin 行为仅经仿真验证 |
| Arc 资源热更新（贴图/字体/语言包） | 🔶 | 贴图同尺寸原位直刷；尺寸变化与运行期新增贴图各有已知边界；字体/语言包已接线但缺自动化覆盖 |
| Mixin 生态插件 | 🔶 | 重织入与目标级联已落地；端到端仍需真实 Fabric 客户端验证 |

## 6. 测试要求

**【测试与验证防空转铁律】** 严禁将"通过的测试"直接等同于"机制已验证"。所有有效性断言必须满足三要素：
1. **前置基线确认**：触发变更/失效前，必须先断言目标状态已处于预期的生效态，排除"原本即为空"的假阳性；
2. **因果链终局断言**：断言必须落在业务可观测的最后一环（实际读写结果/状态终局），严禁用调用成功、日志输出、内部计数器等相关性/代理信号代替终局结果；
3. **严格时间窗约束**：状态转换与失效必须绑定明确的时间戳窗口（$[T_0, T_0 + \Delta t]$），区分"机制主动生效"与"TTL 自然过期"。

写完测试后必须自问：**若注释掉核心生效代码，该断言是否必挂？**

**对照组（因果归因的前提）** —— 分两种，缺一不可：
- **去触发**：注释掉触发动作（如 `redefineClasses`），断言必须变红；
- **换无关输入**：把触发对象换成无关的同类操作（如 redefine 一个无关类），断言必须不成立。

两种对照都指向同一件事：断言必须能区分「机制做了这件事」与「别的原因也产生了同一个信号」。若某用例确实
没有自然的特异性对照，必须在用例注释里写明原因（例如"该断言只记录观测结果，不具备归因能力"），
不允许用形式化的对照凑数。

- 回归任务：`:tests:initFixOracle`（挂在 `check`，当前 48 场景 / 318 断言，以实际输出为准）。纯 JVM 运行环境，0 额外框架依赖。
- 必须通过 `./gradlew initFixOracle`（或 `./gradlew :tests:initFixOracle`）运行，不要直接 `java -cp`：Kotlin 夹具由 Gradle 先编译，输出目录经 `-Dremold.ktfix.v1/v2` 传入。
- Kotlin 夹具的 v1/v2 输出目录**绝不能**进入任何 classpath（同名类会静默只命中其一，造成假绿）。
- 迭代期间只跑 `./gradlew initFixOracle`，收尾时才跑一次 `./gradlew check`。
- 匿名类/对齐主题套件为标准 JUnit 测试：`./gradlew test`（或 `./gradlew :tests:test`，挂在 `check`）。入口用 `-Dhstest.javac8/11/17/21/25` 现编夹具，不再有 `expected-count.txt` 基线。
- P0.5 前置验证：`./gradlew :tests:p05Probe -Pp05.jbr=<DCEVM JDK 目录>`（或设 `JBR_HOME`）。**不挂 `check`**，因为需要 JBR 17/21 的 `-XX:+AllowEnhancedClassRedefinition`；CI 由 `p05-probe` 的 JBR 17/21 矩阵承担。探针刻意用最小 agent（`P05Agent`）而非 `remold-agent.jar`，避免 `LayoutGate`/`InitFix` 介入被测重定义。
- 两阶段编排验证：`./gradlew :tests:twoPhaseProbe -Pp05.jbr=<DCEVM JDK 目录>`，把 `StageASkeleton` / `ConstructorTailInjector` / `InitFix` 串成 `A → redefine → B → C → redefine` 跑一遍（同样不挂 `check`）。改动两阶段相关代码后必须先跑它，再考虑动 `RemoldAgent`。
- 耗时类探针固定报 **min / median / max** 三个数；结论必须同时对 min 与 median 成立，只对其中一个成立时标注「口径敏感」，不得只报有利的那个。统计量不得在看到数据之后才选。
- 探针日志默认带 `-Xlog:safepoint`（`uptimenanos` 装饰符）、`-Xlog:gc*`、`-Xlog:jit+compilation=debug`，落在 `build/p05/`。断言里引用日志时间戳时，基准必须取 safepoint 窗口的**开始**（`时间戳 − Total`）——日志行是在 safepoint 结束时打印的；unified logging 有缓冲，日志分析要放在运行结束后做。
- `test` 的测试数下限由 `tests/build.gradle` 的 `hstestMinTests` 守卫（当前 239，以实际输出为准；`verifyTestCount` 读 XML 断言总数 ≥ 基线且 `skipped == 0`）：删除/禁用/跳过测试会让 `check` 变红，上调基线要有意识地改这一行。
- CI：`.github/workflows/ci.yml`（`setup-java` 装 8/11/17/21 + runner JDK 25，`hstest.requireJdks=true`；以及 `test-dcevm` 任务在真实 JBR 21 DCEVM 下执行 `liveDcevmTest`）。首次 Linux 跑，Windows 假设（路径分隔符、GBK）可能暴露，按原因分类再处理。
- 新增或放宽安全门：必须有负向阻断断言。
- 修复缺陷：必须有先红后绿的回归断言。
- 新增或放宽安全门例外：除正向、负向用例外，必须做**变异检查**——故意放宽一处实现，确认对应用例会变红，再撤销。
- 新增测试注释和断言消息用英文，已有中文不改。涉及中文输出的任务需带 UTF-8 JVM 参数（`initFixOracle` 已配置）。
- 涉及 float/double CAS：必须在真实 JDK 8 上验证（oracle 跑在 JDK 25，区分不出两种实现）。

## 7. 按需文档

### InitFix 存量初始化
| 修改内容 | 先读 |
|:--|:--|
| 判决分层、效应检查、参数回溯、已知限制、单例与 Kotlin 字节码事实、测试夹具机制 | `docs/initfix/01-safety-gate.md` |
| 依赖闭包、成环、台账、失败策略 | `docs/initfix/02-closure-and-ledger.md` |
| 补丁驱动、写入协议、`@RemoldReinit` | `docs/initfix/03-runtime-driver.md` |
| 两阶段、基线指纹、构造器插桩、目标设计路线图 | `docs/initfix/04-target-design.md` |
| P0.5 前置验证的实测方法与原始数据 | `docs/initfix/07-p05-probe-results.md` |
| JVMTI 堆遍历 | `docs/initfix/05-jvmti-heap.md` |
| 为什么这么设计、历史缺陷 | `docs/initfix/06-decisions.md` |

### 匿名类拓扑对齐与安全门
| 修改内容 | 先读 |
|:--|:--|
| 核心不变量、改写范围契约、栈图处理 | `docs/topology/01-invariants-and-remapping.md` |
| 属性优先准入、SwitchMap 评估、保留名域与挂起避让 | `docs/topology/02-admission-and-reserved-names.md` |
| 层级交错流水线、Self Hash、调用链追溯、INV-1/INV-2 架构不变量 | `docs/topology/03-cascading-pipeline.md` |
| Tier 1~4 置信梯队、Tier 3 拓扑相等过滤、宿主级原子拒绝 | `docs/topology/04-tiers-and-rejection.md` |
| 典型场景端到端推演案例 | `docs/topology/05-walkthrough.md` |
| JBR-21/DCEVM 能力与限制、事务乐观预登记、数量与超时闸门、开关配置 | `docs/topology/06-runtime-and-perf.md` |
| 实例状态布局安全门（LayoutGate）、局部类编号漂移止血门（LocalClassGuard）、蜕变测试 | `docs/topology/07-layout-gate-and-risks.md` |
| 架构决策记录、偏离原因、JBR 8 组真机数据 | `docs/topology/08-decisions.md` |
