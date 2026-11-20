# Remold 代码地图 (Code Map)

> **文档类型**：索引（全模块源码/探针/规格坐标索引；实现状态以 [`docs/status.md`](status.md) 为准）

本文件承载从 [`AGENTS.md`](../AGENTS.md) 拆出的全量代码地图，按子系统归类。

## 1. InitFix 与存量初始化

| 关注点 | 位置 |
|:--|:--|
| 入口、补丁发射与运行驱动 | `InitFix.transform` / `buildPatch`（编排与公共委托）、`InitFixPatchEmitter`（`rewriteFieldSlice`、`emitBatchMethod` / `emitFusedBatchMethod`、`rewriteProtectedAccesses`）、`InitFixRuntimeDriver`（`afterRedefine*`、`applyPatch`、生命周期与批量应用） |
| 切片提取与安全门 | `InitFixSliceExtractor`（`AliasInterpreter`、`extractFieldInits`、`checkSafe`、`isRootConstructor`）、`InitFixSafetyGate`（`NestView`、T0/P0 效应检查、`blacklistedCallReason`、`builderMutatorReason`） |
| L1 存量初值重放（白名单父类匿名子类 + 常量旧值） | `core/src/remold/ref/InitializerReplay.java`（纯函数候选/声明类解析）；`HotswapBridge.KIND_CONDITIONAL_EXPECTED`（旧初值 CAS） |
| A 档 Kotlin `apply {}` 静态持有者重放（白名单持有者 `<clinit>` + 唯一 provenance） | `core/src/remold/ref/ApplyInitializerReplay.java`（纯分析：`<clinit>` 直线抽象解释、局部变量副本溯源、唯一 `NEW`/唯一 `PUTSTATIC dest`、目标字段安全门）；`InitFix` / `InitFixRuntimeDriver`（`PatchPlan.applyFields` + `applyReplay$N` 补丁方法 + `PatchReport.applyReplayFields`）；`HotswapBridge.KIND_CONDITIONAL_EXPECTED` 的链接期字段复核 |
| 参数回溯 | `InitFixSafetyGate.scanParamFields`、`sourceFieldNotImmutableReason`、`InitFixSafetyGate.NestView` |
| 离线层级 | `ClassHierarchyOracle` / `HierarchyTreeOracle`，底层 `AnnotationTransformer.HierarchyTree` |
| 台账 | `InitFix.LEDGER`、`getUnpatchedFields` |
| 跨类批次字段依赖收敛与拓扑执行（PR4-A） | `InitFix.beginBatchBytecodeView` / `setBatchDefinitions` / `finishBatchTransforms` / `convergeBatch` / `orderBatchHosts`、`InitFixBatchGraph` |
| 加载前沿与出生水位线 | `core/src/remold/vcs/LoadFrontier.java`（单调水位线、有界出生队列、`conservativeOnly()`、`flush`/`flushBeforeCommit`） |
| 出生点补丁与三态目标索引 | `core/src/remold/ref/PendingLoadPatch.java`、`core/src/remold/reflect/mixin/MixinTargetIndex.java`、`plugin-mixin/src/remold/mixin/MixinTargetPublisher.java` |
| 两级保留与作用域判决时间戳 | `core/src/remold/vcs/GenerationalFieldLedger.java`（`pruneToWindow` / `forgottenWriters`）、`core/src/remold/vcs/ScopeVerdicts.java` |
| 工作区 Raw 按需索引 | `core/src/remold/WorkspaceRawIndex.java`（懒查询 + 批内复用 + 找不到/歧义 fail-closed），与 `InitFix.beginBatchBytecodeView` / `endBatchBytecodeView` 同寿 |
| 类名形态转换单一入口 | `core/src/remold/util/Names.java`（守卫 `tests/src/remold/util/NameConversionGuardTest.java` + `name-conversion-baseline.txt`） |
| 动态代提交 | `core/src/remold/DynamicVcsCommit.java`（`roundId = "dynamic:<fqcn>"`，三种状态转移、字节四元组映射） |
| 代际字段台账与回退重放门控（B2） | `core/src/remold/vcs/GenerationalFieldLedger.java`、`core/src/remold/vcs/GenerationalReplayGate.java`、`InitFix.gateGenerationalReplay` / `recordReplayOutcome` / `releaseRemovedFieldDebt` |
| 逐实例驱动 | `InitFixRuntimeDriver`（`runDependentInstanceTasks` / `runIndependentInstanceTasks`、`InstanceFailureBudget`、`InitFix` 保留包内/公开委托） |
| 写入桥 | `HotswapBridge`（`KIND_CONDITIONAL` / `KIND_CONDITIONAL_EXPECTED` / `KIND_FORCE`） |
| `Object.toString` 例外 | `InitFixSafetyGate.allowedStringCoercion`（`InitFix` 保留反射转发口）、`isInertProducer`、`isIntermediateObjectToString`（`effectReason` 在白名单之后、黑名单之前调用） |
| 哈希语义门控 | `HashSemantics`（`core/src/remold/ref/`，本类内调用图扫 `hashCode`/`equals`/`compareTo` 及其可达方法是否读新增实例字段，只告警不拒绝） |
| `@HotswapInit` 设计契约 | `docs/initfix/09-hotswapinit-design.md`（T4 逃生口契约） |
| 注解与过滤 | `remold.annotation.RemoldReinit`、`ClassDiffUtil.isInternalMarkerField` |

## 2. 两阶段 Redefine、JVMTI 堆扫描与布局门控

| 关注点 | 位置 |
|:--|:--|
| 两阶段读穿保护（内部批次路径 + 动态单类入口） | `TwoPhaseCoordinator`（`RemoldAgent.applyTwoPhase` / `redefineStageA` / `applyRedefinitions` 委托，A→B→C 编排、整组回滚、`isInitialized` 二次检查）；`RemoldAgent.redefineDynamic` 消费 `InitFix.requiresTwoPhase`，在调用内部同步跑 A→补丁→C（阶段 C 复用现成 `runtimeBytes`，失败按 VM 级/非 VM 级分流）；`StageASkeleton.buildWithLiveConstructors`（阶段 A 保留新构造器）；`InitFix.requiresTwoPhase`（只有真正会写的零值暴露字段才触发）；开关 `-Dremold.agent.two_phase=false` |
| 阶段 A 骨架合成 | `StageASkeleton`（`core/src/remold/ref/`，纯函数：新结构 + 旧执行体，成员按旧 ∪ 新） |
| 构造器尾部插桩（保留组件，当前不接线） | `ConstructorTailInjector`（`core/src/remold/ref/`，纯函数：根构造器 `RETURN` 前注入字段切片） |
| 堆实例检索（Native） | `LibTool.getInstances` |
| 批次堆遍历（同批 K 类收敛为 1 次全堆遍历） | `LibTool.getInstancesBatch` / `hasLiveInstancesBatch`（native `nGetInstancesBatch` / `nHasLiveInstancesBatch` / `nHeapWalkCount`；`core/src/remold/util/LibTool.java`：`BATCH_TARGET_LIMIT=16` 分块、`BATCH_LIVENESS_TARGET_LIMIT=64` 存在性探测不物化对象、任一归属/槽位异常整体退回逐类路径）；接线 `InitFix.beginBatchInstanceCollection` / `takeBatchInstances`、`RemoldAgent.setLivenessBatch` / `prefetchBatchLiveness` 与逐实例驱动消费；规格 `docs/initfix/05-jvmti-heap.md` |
| JNI 局部引用生命周期回归锁 | `tests/src/BatchScanLocalRefLifetimeTest.java` + `tests/src/BatchHeapScanEquivalenceTest.java` + `tests/src/remold/BatchLivenessPrefetchTest.java` |
| 删除成员的读者分析 | `RemovedMemberReaders`（`core/src/remold/ref/`，纯函数：字段按 JVMS 5.4.3.2、方法按 5.4.3.3 + 5.4.6 向下分派判"改动后引用会怎样"，输出悬空 / 抽象方法缺失 / 分派转移 / 判不了 / 字段改指向五档） |
| 匿名类与局部类安全门 | `AnonClassAligner`、`LayoutGate`、`LocalClassGuard`、`BatchLayoutCoordinator`（`applyLocalClassGuard`、`applyRedefineLayoutGate`、`dropEnum*`、`OffsetScanner` 偏移扫描索引） |

## 3. Agent 基础设施、防抖、Trigger 与启动门闩

| 关注点 | 位置 |
|:--|:--|
| Agent 协作模块（`core/src/remold/`） | `EnvironmentProbe`（增强重定义能力探测与降级）、`LayoutDecisionEngine`（布局门纯判决）、`RemovedMemberScanner`（被删成员的批次外读者扫描）、`JarExtractor`（jar 解压与临时挂载映射）、`WatchPathRegistry`（监控路径分槽与聚合）、`FileWatcherService`（WatchService 监听线程）、`RemoldAgentBootstrap`（`init` / `initConfig` / 路径挂载 / 插件装载与预热）、`AgentWatchCoordinator`（文件/jar 受理、build-trigger 消费、防抖与启动门闩重查）、`BatchHotswapExecutor`（`processChangesBatch` 批次流水线、liveness 预探测、`@OnReload` 与字节读取）、`BatchLayoutCoordinator`（布局门/局部类门/枚举依赖与偏移扫描协调）、`DynamicRedefineExecutor`（`redefineDynamic` 无盘单类重定义）；公共入口、共享状态与反射契约保留在 `RemoldAgent` |
| 文件热更防抖状态机 | `core/src/remold/DebounceWindow.java`（纯状态机：Class/jar 与资源流各自 trailing-edge 静默窗口；`markClassDueNow` 为外部构建完成信号的 sticky due） |
| 构建完成 marker（build-trigger） | `core/src/remold/BuildTriggerMarker.java`（解析/拒绝/去重）、`BuildTriggerSweep.java`（按 mtime 收集最近变更）、`RemoldAgentBootstrap.configureBuildTrigger` / `AgentWatchCoordinator.handleBuildTriggerFile`、`FileWatcherService` 专用 trigger watcher；`gradle-plugin` `BuildTriggerWriter` + `RemoldPlugin` 的 `classes.doLast` + `RemoldConfigResolver` 注入 `-Dremold.agent.build_trigger_file`（DSL `buildTrigger`，默认 `true`）。设计：`docs/runtime/01-build-trigger-mode.md` |
| 启动就绪门闩与批次看门狗 | `core/src/remold/StartupGate.java`（`PENDING→READY`，`-Dremold.agent.ready_timeout_ms`）、`core/src/remold/BatchWatchdog.java`（`enter/exit/current` + `armedWatchdogs()` 不变量，`-Dremold.agent.batch_stall_ms` 默认 15000，`[HOTSWAP-STALL]` dump）；挂点 `RemoldAgent.processChanges` 与 `retransformLoaded`，漏斗 `triggerDueHotswap`；就绪投票 `api` `HotswapPlugin.isHostReady()`（`plugin-fabric` `HostReadinessProbe`） |
| 织入生成类的字节码登记口 | `core/src/remold/BytecodeSources.java`（多来源按顺序查询，无缓存；`lookupAll` 供调用方区分"唯一来源"与"来源矛盾"）；被 `WorkspaceRawIndex` 与 `HierarchyTreeOracle.readClassBytes` 消费；`plugin-mixin` `MixinInnerClassHotSwap` 回灌与 `refeedForTarget` 按需提前生成 |
| 运行时常量访问器与诊断门面 | `remold.RuntimeConstants`（core 登记口；`plugin-arc` `ArcHotswapPlugin.onAgentInit` 登记 `Scl.scl()`）、`core/src/remold/util/Diag.java`（默认全静默，`RemoldAgent` 静态接入 logger 与 `isDebugEnabled`/`isPerfEnabled`；目录级零依赖与全局去通配符由 `AnalysisBoundaryGuardTest` 守卫）、`RemoldAgent.getRuntimeBytecode` / `recordRuntimeBytecode`（`bytecodeCache` / `sourceBytecodeCache` 唯一收口访问器） |

## 4. 字节码 VCS、Derived Cache、Raw 对齐、Lambda 与孤儿桩

| 关注点 | 位置 |
|:--|:--|
| 逐代对齐元数据与 Changeset 链（PR1 / B1） | `core/src/remold/vcs/AlignmentMetaStore.java`、`Changeset.java`、`ChangesetLog.java`、`VcsCommitGate.java`、`BytecodePack.java`、`VcsAgentCoordinator`（`RemoldAgent.commitVcsBatch` / `revertTo` 协调）；开关 `-Dremold.vcs.enabled`、`-Dremold.vcs.revert`、`-Dremold.vcs.pack_dir`；规格 `docs/vcs/01-bytecode-changeset-vcs.md` |
| 按 blob 内容寻址的派生数据缓存（PR2） | `core/src/remold/vcs/DerivedDataCache.java`（键 `(kind, SHA-256(blob))`、双预算 LRU、值不可变；开关 `-Dremold.vcs.derived_cache`、`-Dremold.vcs.derived_cache_entries` / `_bytes`）；消费点：`LambdaBytecodeScanner.consumersOf` / `deriveConsumerTags` / `hasConsumerSite`、`LambdaBytecodeScanner.deriveAnonRefs`、`RemovedMemberReaders.describe` / `refs`。**禁止缓存 `align()` 整包结果**，规格 `docs/vcs/01-bytecode-changeset-vcs.md` §3.7 |
| 活体对齐 Raw ↔ Raw 与 `Eff_prev` 继承（PR3） | `LambdaAligner.align`（`META_SOURCE` 为 Run R 总闸）、`LambdaBodyFingerprint`（体指纹归一化与常量折叠）、`LambdaBytecodeScanner`（字节码转换、`fixDeserializeSwitch`、匿名类/消费者扫描、LVT 形参提取；`LambdaAligner.readLambdaParamNames` 保留委托）、`AlignmentMetaStore.headMatching`、`RemoldAgent.writePendingInvented` / `commitVcsBatch`；规格 `docs/vcs/03-live-raw-to-raw-alignment.md` |
| 孤儿 lambda 复活与方法体保留 | `LambdaOrphanEmitter`（`resurrectOrphanedLambdas`、`injectDummyBody`、转发/保留桩、熔断与幽灵指纹；`LambdaAligner` 保留 `onOrphanInvoked` / `isTransparentFrame` 等公共与反射委托） + `OrphanBodyRetention`（`core/src/remold/`，纯函数）；规格 `docs/lambda/02-orphan-body-retention.md` |
| 捕获桥（删除 + 可证明的新增） | `LambdaCaptureBridge`（`stepCaptureBridge` / `getBridgeDelegate` / `injectBridgeBody`，`LambdaAligner.onBridgeInvoked` 保留委托）；接收者归一在 `LambdaBytecodeScanner.captureNamesOf`，`readLambdaParamNames` 只报 LVT 原文；规格 `docs/lambda/01-capture-bridge.md` |

## 5. 框架插件、偏移稳定性、反射驱逐与 Gradle 工具链

| 关注点 | 位置 |
|:--|:--|
| 加载器集成插件（`plugin-fabric/`） | `FabricHotswapPlugin` 实现 `HotswapPlugin` 三个窗口：`onPremain`（`FabricClassPathGroups`）、`onWatchPathsChanged`（`ModJarInspector`）、`isWatchPathIgnored`（`DevWatchExclusions`）；Gradle 侧 `autoFabric` 自动挂载 |
| Mixin 共享反射引擎 | `core/src/remold/reflect/mixin/`（`MixinConfigDiffer`、`NewMixinRegistrar`、`MixinConfigContext`、`MixinReflection`、`MixinClassByteSources`、`MixinReloadPolicy`、`MixinRetarget`、`MixinClassInfoRegistry`、`ClassInfoMemberSync`）：纯 JDK + 反射，无 Mixin / ModLauncher 编译依赖；目标加载抑制器以 `Function<Collection<String>,Object>` + `Consumer<Object>` 跨边界传递 |
| Mixin 生态扩展插件 | `plugin-mixin/`（`MixinHotswapPlugin`、`MixinReWeaver`、`MixinHotSwap`、`FabricLauncherSpoof`、`MixinBytecodeOverlay`、`MixinInnerClassSupport`、`MixinInnerClassHotSwap`、`MixinSyntheticSchema`、`SyntheticAbortLedger`）；`plugin-forge/`（`ModLauncherMixinBridge`、`ForgeMixinConfigDiff`、`ModLauncherClassTrackerSpoof`） |
| Forge 与 NeoForge 扩展插件 | `plugin-forge/`（`ForgeHotswapPlugin`、`NeoForgeHotswapPlugin`、`ModLauncherMixinBridge`、`ForgeEventBusRefresher`、`ForgeMixinHotSwap`、`ForgeClassInfoSync`） |
| 偏移量与访问器扫描 / 绑定 / 预测 | `core/src/remold/offset/`：`OffsetScanner`、`OffsetBindingIndex`、`SuperclassDriftGate`、`LayoutPredictor` |
| 反射与序列化缓存驱逐 | `ReflectionCacheManager`（`core/src/remold/evict/`，内置 Jackson/Gson）；`ArcJsonEvictor`（`plugin-arc/src/remold/arc/`） |
| Gradle 插件坐标与对账 | `gradle-plugin/`（`io.github.i-hope1.remold`：`RemoldPlugin`、`RemoldExtension`、`RemoldConfigResolver`、`GenerateIdeaRunConfigTask`、`GenerateRemoldScriptTask`、`StartRemoldTask`、`CleanRemoldCacheTask`、`BuildTriggerWriter`，内置 `remold.gradle(.kts)` 薄壳与版本对账） |

## 6. 测试夹具、外部源码与探针入口

| 关注点 | 位置 |
|:--|:--|
| 测试夹具（Java / Kotlin） | Java：`tests/src/InitFixOracle.java`（内存 `javac`）；Kotlin：`tests/ktfix/v1/`、`v2/`（`ktfixV1`/`ktfixV2` source set） |
| 常驻 javac（测试提速） | `tests/src/JavacDaemon.java` + `tests/javacdaemon/JavacDaemonServer.java`（`--release 8`，任务 `javacDaemonServer`，`-Phstest.noJavacDaemon` 回退） |
| 外部引擎源码只读镜像 | `.sources/`（如 `.sources/mindustry/`；只读、已 gitignore） |
| 探针与变异检查目录 | 详表与命令见 [`docs/testing-guide.md`](testing-guide.md) |
| 测试判定力审计记录 | [`docs/initfix/08-test-audit.md`](initfix/08-test-audit.md) |
