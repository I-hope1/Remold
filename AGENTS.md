# InitFix 开发规则

InitFix：热更新（Redefine）后，为**新增字段**初始化存量实例和静态环境的系统。
修改 InitFix 相关代码前先读本文件；细节见第 7 节按需文档索引。

## 1. 作用域

- 只处理本次 Redefine **新增声明的字段**（存量实例 + 静态）。
- 禁止实现：修改已有字段初值、删除字段、修改方法签名、动态修改字段描述符类型。
  - 删除字段：具名类走重定义层，匿名类合成字段走 `LayoutGate.check`（判 `COMPATIBLE`）。两层判据都是"**批次外读者为空**"（批次内扫新字节码、批次外扫当前字节码），已实现并接线（`RemovedMemberReaders` + `decideLayout`），做法见 `docs/initfix/07-p05-probe-results.md` §13.6。不能简化成"有删除就拒"——删 private 字段与正常连带重编的批次都是安全的。有存活实例时若无读者也放行（`WAIVE_NO_READERS`）；扫描未完成则退回存活实例判据，因为那条理由对删除不成立（`docs/status.md` 风险 12）。实测后果见 §13：字节码语义下实例迁移保全未删字段值、废弃槽位不复用，字节码读者失败形态是抛 `NoSuchFieldError`（`LinkageError`）；底层物理偏移紧缩（`OffsetStabilityProbe` A4-2），有缓存物理偏移者会错读相邻字段。
    - **静态字段**走同一判据，但**没有 liveness 兜底**：静态值不随实例存在，扫描未完成时只能拒绝。失败形态实测三种（§16.2）：`NoSuchFieldError`、被父类/父接口同名字段**接住**、落到父类实例字段上抛 `IncompatibleClassChangeError`。
    - **只拦"会抛错"的**：`hits`（`NoSuchFieldError`/`NoSuchMethodError`）、`abstractMissing`（`AbstractMethodError`）、**静态性翻转**（ICCE）。**合法回退不算读者**——删掉子类 Override 方法或遮蔽字段后回退到父类实现 / **同静态性**的父类字段，零异常、读者源码不用改、全量重编后字节码仍对得上，只记日志（§16.4）。
    - **编译期常量**（`ConstantValue`，JLS §13.4.9，基本类型与 `String` 的 `static final`）**放行**：内联的读者运行期根本不解析该字段（零异常），未内联的读者常量池里仍有 `Fieldref`，会被 `hits` 正常抓到。边界不在 `final` 而在 `ConstantValue`：运行期初始化的 `static final` 与引用类型照常走 `Fieldref`（§16.1）。
  - 删除方法：**与字段不是同一套查找规则**。字段只有向上解析（JVMS 5.4.3.2）；实例方法还有向下分派（JVMS 5.4.6），因此引用 owner 可以是 `A` 的**任何祖先**（`InterfaceMethodref I.m` / `Methodref Super.m`），只按 `declaring == A` 归属会整类漏掉。失败形态有三种：`NoSuchMethodError`（整条链都没有同签名方法）、`AbstractMethodError`（只有接口声明、没有实现）、**回退到祖先实现**（连 `Methodref A.m` 本身都不抛错）。前两种拒绝，**第三种放行**——那是删掉 Override 的本意（§16.4）。判据与字段共用"批次外读者为空"，但**扫描未完成时只能拒绝**：方法删除没有存活实例判据可以退，它的影响与"当前有没有实例"正交。实测与判据见 `docs/initfix/07-p05-probe-results.md` §15。
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
| Agent 协作模块（`core/src/remold/`） | `EnvironmentProbe`（增强重定义能力探测与降级）、`LayoutDecisionEngine`（布局门纯判决）、`RemovedMemberScanner`（被删成员的批次外读者扫描）、`JarExtractor`（jar 解压与临时挂载映射）、`WatchPathRegistry`（监控路径分槽与聚合）、`FileWatcherService`（WatchService 监听线程）；编排与流水线仍在 `RemoldAgent` |
| 加载器集成插件（`plugin-fabric/`） | `FabricHotswapPlugin` 实现 `HotswapPlugin` 的三个通用窗口：`onPremain`（`FabricClassPathGroups`，必须在插件加载后、`init` 前执行）、`onWatchPathsChanged`（`ModJarInspector` 巡检 `run/mods` 残留制品）、`isWatchPathIgnored`（`DevWatchExclusions` 排除 `mods` 目录）；Gradle 侧 `autoFabric` 自动挂载。core 不出现任何加载器专属目录或属性约定 |
| 写入桥 | `HotswapBridge`（`KIND_CONDITIONAL` / `KIND_FORCE`） |
| 构造器尾部插桩 | `ConstructorTailInjector`（`core/src/remold/ref/`，纯函数：根构造器 `RETURN` 前注入字段切片） |
| 哈希语义门控 | `HashSemantics`（`core/src/remold/ref/`，本类内调用图扫 `hashCode`/`equals`/`compareTo` 及其可达方法是否读新增实例字段，只告警不拒绝） |
| 删除成员的读者分析 | `RemovedMemberReaders`（`core/src/remold/ref/`，纯函数：字段按 JVMS 5.4.3.2、方法按 5.4.3.3 + **5.4.6 向下分派**判"改动后引用会怎样"，输出悬空 / 抽象方法缺失 / 分派转移 / 判不了 / 字段改指向五档） |
| 读者扫描成本探针 | `tests/src/ReaderScanProbe.java`（`./gradlew :tests:readerScanProbe`，不需要 DCEVM）：常量池扫描 vs 方法体精扫、三档扫描范围的真实成本 |
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
| lambda 对齐压力测试 | `tests/src/LambdaAlignTortureTest.java`（旧 callsite 名字 → body 归属的自动断言；JDK 8/17/21 × 前插/删除/重排，覆盖 `lambda$static$N`/`lambda$new$N`；含判定力自检） |
| 捕获桥（删除 + 可证明的新增：常量 / final 字段 / 可折叠算式 / 构造器参数经 final 字段 / 运行时常量） | `LambdaAligner.stepCaptureBridge` / `getBridgeDelegate` / `onBridgeInvoked`；接收者归一在 `captureNamesOf`（槽号结构优先，名字 `null`/`this`/`this$0` 只兜底回溯失败），`readLambdaParamNames` 只报告 LVT 原文；规格见 `docs/topology/09-lambda-capture-bridge.md` |
| 运行时常量访问器登记表 | `remold.RuntimeConstants`（core 只提供登记口；`plugin-arc` 的 `ArcHotswapPlugin.onAgentInit` 登记 Arc 专属的 `Scl.scl()`）；被 `InitFix` 效应审查与捕获桥常量合成共用 |
| 捕获桥判定力 | `tests/src/LambdaBridgeAlignTest.java`（对齐期，无需 DCEVM）；`tests/src/LambdaBridgeProbe.java`（`./gradlew :tests:lambdaBridgeProbe -Pp05.jbr=<dcevm-jdk>`，实测运行时失败形态） |
| 常驻 javac（测试提速） | `tests/src/JavacDaemon.java`（客户端）+ `tests/javacdaemon/JavacDaemonServer.java`（服务端，`--release 8`，任务 `javacDaemonServer`）；每个 JDK 一个进程、复用 `JavaCompiler`/`FileManager`，失败自动回退外部 javac；`-Phstest.noJavacDaemon` 可强制回退 |
| `@HotswapInit` 设计契约 | `docs/initfix/09-hotswapinit-design.md`（T4 逃生口的豁免边界、签名与调用基数、重复触发、异常边界、用例清单） |
| 外部引擎源码索引 | `.sources/`（如 `.sources/mindustry/` 指向本地 Mindustry/Arc 真实源码；只读参考，已 gitignore） |
| Mixin 生态扩展插件 | `plugin-mixin/`（`remold.mixin.MixinHotswapPlugin`、`MixinReWeaver`、`MixinHotSwap`） |
| Forge 与 NeoForge 扩展插件 | `plugin-forge/`（`remold.forge.ForgeHotswapPlugin`、`NeoForgeHotswapPlugin`、`ModLauncherMixinBridge`、`ForgeEventBusRefresher`、`ForgeMixinHotSwap`（Mixin 模板热更 + 目标级联）、`ForgeClassInfoSync`（薄适配，委托 core 引擎）） |
| ClassInfo 成员同步单一引擎 | `core/src/remold/reflect/ClassInfoMemberSync.java`（反射-only，跨 ClassLoader 按宿主类型解析；Fabric `MixinClassInfoSync` 与 Forge/NeoForge `ForgeClassInfoSync` 都只是类型擦除 + 日志前缀的薄适配） |
| 偏移量与访问器扫描器 | `OffsetScanner`（`core/src/remold/offset/`，常量池预过滤 + 多轮过程间数据流分析，识别 `objectFieldOffset`/`newUpdater`/`findVarHandle` 产生源） |
| 偏移绑定索引与漂移安全门 | `OffsetBindingIndex` / `SuperclassDriftGate`（`core/src/remold/offset/`，父类新增实例字段时检测子孙类全部实例字段是否被缓存，结合 `LibTool` 堆存活探测，阻断漂移） |
| 布局预测器 | `LayoutPredictor`（`core/src/remold/offset/`，抛弃式加载器干净加载预测物理偏移，协同注入非 JDK 祖先链防御 package-private 运行时包隔离） |
| 反射与序列化缓存驱逐 | `ReflectionCacheManager`（`core/src/remold/evict/`，实例级缓存清除 SPI，内置 Jackson/Gson）；Arc `Json` 驱逐器在 `plugin-arc/src/remold/arc/ArcJsonEvictor`，由插件注册 |
| 偏移量稳定性与漂移探针 | `tests/src/OffsetStabilityProbe.java`（`./gradlew :tests:offsetStabilityProbe -Pp05.jbr=<dcevm-jdk>`，实测单类稳定性、父类漂移、Updater 破坏与 C2 逆优化） |
| Gradle 插件坐标 | `gradle-plugin/`（`java-gradle-plugin`，坐标 `io.github.i-hope1.remold`；二进制插件实现 `RemoldPlugin` + `RemoldExtension` 类型安全 DSL + 配置缓存，同时将仓库根 `remold.gradle(.kts)` 打包进 jar 作为脚本资源） |

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
| 物理偏移漂移安全门与布局预测器 | ✅ | 抛弃式加载器事前预测物理布局；`SuperclassDriftGate` 结合堆存活阻断漂移；预注入祖先链防御包级私有隔离 |
| 反射与序列化实例缓存驱逐 | ✅ | Arc Json、Jackson、Gson 实例级清空 SPI，重定义成功后自动触发 |
| Arc 资源热更新（贴图/字体/语言包/音频/着色器） | 🔶 | 贴图同尺寸原位直刷；尺寸变化与运行期新增贴图各有已知边界；语言包重载会同步回写内容缓存字段；字体按文件路由到对应槽位；音效与音乐原位重载；内置着色器按文件映射原位重编，模组着色器/content JSON/图集明确提示需重启 |
| Mixin 生态插件 | 🔶 | 重织入与目标级联已落地；端到端仍需真实 Fabric 客户端验证 |
| Forge / NeoForge 生态插件 | 🔶 | ModLauncher 跨 ClassLoader Mixin 重织入与 EventBus 监听器注销重挂已落地；端到端待真实客户端验证 |

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
- 缓存填充验证：`./gradlew :tests:cacheWarmingProbe`（只需 `-javaagent`）。改动 transformer 注册顺序或`canRetransform` 标志后必须跑它：它实测"`canRetransform=false` 组整组先于 `true` 组执行"这条规则，并断言 `bytecodeCache` 里存的是对齐/织入**之后**的字节。
- Forge ClassInfo 成员同步 E2E：`tests/fabric-mixin-probe/forge-classinfo/`（真实 Forge 1.21.1 `runServer`，红基线 + 修复后绿跑；`bash run-forge-classinfo.sh` 复跑，Windows 下用 `verify-evidence.cmd` 做验收；同样不挂 `check`）。改动 `ForgeClassInfoSync` / `ForgeMixinHotSwap.reload` 时序前必须先跑它。
- NeoForge Mixin 热更 E2E：`tests/neoforge-mixin-probe/`（真实 NeoForge 21.1 dev `runServer`，Mixin 配置走加载器原生 `META-INF/neoforge.mods.toml` `[[mixins]]`；红基线=禁用同步的 plugin jar，绿跑=当前实现；Windows 用 `verify-evidence.cmd` 验收；不挂 `check`）。改动 `ClassInfoMemberSync` / `ForgeMixinHotSwap.reload` 时序或 NeoForge 识别逻辑前必须先跑它。
- 读者扫描成本验证：`./gradlew :tests:readerScanProbe`（只需 `-javaagent` 提供 Instrumentation，不需要 DCEVM）。改动 `RemovedMemberReaders.refs` 后必须先跑它：其中一条断言要求"常量池扫描是方法体精扫的超集"。
- 两阶段编排验证：`./gradlew :tests:twoPhaseProbe -Pp05.jbr=<DCEVM JDK 目录>`，把 `StageASkeleton` / `ConstructorTailInjector` / `InitFix` 串成 `A → redefine → B → C → redefine` 跑一遍（同样不挂 `check`）。改动两阶段相关代码后必须先跑它，再考虑动 `RemoldAgent`。
- 偏移量稳定性与漂移实测：`./gradlew :tests:offsetStabilityProbe -Pp05.jbr=<DCEVM JDK 目录>`（同样不挂 `check`），实测单类稳定性（空洞填补、类型重排）、父类字段漂移步长、Updater 错位破坏与 C2 常量折叠使用方逆优化。
- 耗时类探针固定报 **min / median / max** 三个数；结论必须同时对 min 与 median 成立，只对其中一个成立时标注「口径敏感」，不得只报有利的那个。统计量不得在看到数据之后才选。
- 探针日志默认带 `-Xlog:safepoint`（`uptimenanos` 装饰符）、`-Xlog:gc*`、`-Xlog:jit+compilation=debug`，落在 `build/p05/`。断言里引用日志时间戳时，基准必须取 safepoint 窗口的**开始**（`时间戳 − Total`）——日志行是在 safepoint 结束时打印的；unified logging 有缓冲，日志分析要放在运行结束后做。
- `test` 的测试数下限由 `tests/build.gradle` 的 `hstestMinTests` 守卫（当前 405，以实际输出为准；`verifyTestCount` 读 XML 断言总数 ≥ 基线且 `skipped == 0`）：删除/禁用/跳过测试会让 `check` 变红，上调基线要有意识地改这一行。
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

### 物理内存偏移与第三方缓存驱逐 (P0 / P1)
| 修改内容 | 先读 |
|:--|:--|
| 物理偏移漂移判决、布局预测器、VarHandle 阻断、MethodHandle 自愈边界与缓存驱逐 SPI | `docs/offset/01-offset-and-reflection-cache.md` |

## 8. 排查纪律（证据与观测）

定位缺陷时的失效模式多数不是"推理不够"，而是**证据不成立**。以下七条按"先证据、后假设、再修复、再汇报"排列。

### 8.1 日志里"没有"某条记录，先查日志级别

`log(...)` 受 `DEBUG` 门控（默认关闭），只有 `warn` / `error` 默认可见。看不到某条记录时，先确认它是否会打，再推断"代码没执行"。同理，拒绝路径必须走 `warn`（§3）：否则"静默跳过"会被读成"没有发生"，而两者对应的修法完全不同。

反向同样成立：**"没有日志"只有在"该路径上所有分支都会打日志"时才算证据**。只要存在一条不打日志的早退分支，或者异常可能被上层吞掉，缺失就只能记作"未观测到"，不能推出"没执行"。要下这个结论，先把分支照亮。

### 8.2 先加观测，再做假设

能用一行打点区分"没进入 / 进入了但被跳过 / 进入了且生效"的，先加打点，别先造理论。观测应打**阶段首尾**（进入与结束各一行），使"中途异常退出"与"整段没执行"一眼可分。异常边界同理：被调度器 / `Future` 承载的入口必须有显式 `catch` 上报，否则失败与成功在日志上无法区分。

### 8.3 未验证的测量不得用来改产品代码

探针、断言、反射查询都是测量工具，本身会坏。用某个观测手段下结论前，先做**强弱对照**：让机制必然生效与必然失效两种情形分别跑该观测。两种情形给出同一结果，说明它测不到被测对象，此时得到的"缺陷"只是测量假象。

实例：一次 schema 变更后的探针里，`Class.getField("added")` 抛 `NoSuchFieldException`，而同一实例在方法内读该字段拿到的是正确值——**异常与"字段是否真的存在"无关，成因至今未查清**。此处不要写任何未经验证的机制解释（例如"反射缓存没失效"：该说法与"JBR 每次重定义都会重置该缓存"的既有信息直接冲突，已被排除）。判定应落在业务终局：方法内读字段、真正调用结果，而不是反射元数据。**测量工具没被验证之前，不得据此修改产品代码。**

> 与 §3 / §6 的分工：本节要求的对照针对**测量工具本身**（它测得准不准）；§6 的"去触发 / 换无关输入"针对**被测机制**（信号是不是它造成的）；§3 的"数据汇报铁律"管结论口径与统计方法。三者不互相替代。

### 8.4 假设之前，先问能直接回答的事实

运行环境、构建版本、操作序列、复现步骤、开关取值，用户能一句话给出，比自己构造假设便宜。但"事实"与"假设"的分量不同：

- 用户陈述的**事实**（改的是哪一行、第三轮有没有动 lambda、这个版本会不会重置某状态）优先采信：那是一手信息，通常比从日志反推可靠。
- 用户提出的**假设**（"是不是某模块的类加载器导致的"）与任何假设等价，仍须证据验证。把它当结论接着往下推，会让整条排查线偏离。正确做法是先把假设里的事实部分收下，再单独验证因果部分。
- 例外：用户的**事实陈述**若与一手观测冲突（例如日志显示那一轮确实改动了 lambda），以观测为准，并把冲突明确告知用户——既不默默采信，也不默默否定。

### 8.5 合成探针不是真实环境的替身

探针只能复现它包含的成分。真实环境里多出来的东西——自定义类加载器、字节码增强、启动参数、依赖库——往往正是触发条件。因此：

- 探针跑绿而真机仍坏时，先列"探针缺了哪些成分"，不要宣布"无法复现"；
- 结论以真实环境日志为准，探针只承担**隔离**与**回归**；
- 版本对照用同一探针、同一序列（`git worktree add` 构建旧版本），差异才可归因。

### 8.6 定位不等于修完

修复必须闭环，三者缺一不可：

1. 用**原始复现序列**在真实环境端到端复跑，看到预期终局（不是"日志看起来对了"）；
2. 加回归测试并做**变异检查**：把修复改回去，用例必须变红；恢复后变绿；
3. 同套用例的对照保持绿，确认没有把正常路径一起改掉。

修复未在真实环境复跑前，只能表述为"已定位并加了防护"，不得表述为"已修复"。

### 8.7 结论里分开写"已观测"与"推断"

汇报时逐条标注证据强度，不要用同一种语气混着说：

- **已观测**：写明观测手段与读数（哪条日志、哪个断言、哪个测量、什么输入）。
- **推断**：写明它依赖的前提，以及**还差哪个实验**才能升级为观测。

推断不得使用"说明""证明""可见""显然"等断言性措辞。读者应当一眼看出哪句有证据、哪句还没有——否则推断会被下一轮当成事实继续引用。
