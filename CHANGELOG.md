# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Single Mixin `ClassInfo` member-sync engine `core/src/remold/reflect/ClassInfoMemberSync.java`:
  both plugin-mixin (Fabric) and plugin-forge (Forge/NeoForge) are now thin adapters. The engine builds
  its rollback handle before mutating the member sets, so a half-way failure rolls back on both sides
  (the Fabric adapter previously leaked the members it had already added).
- `tests/neoforge-mixin-probe/`: real NeoForge 21.1 dedicated-server E2E (`ModDevGradle runServer`,
  MC 1.21.1) whose Mixin config is registered by the loader itself via the native
  `META-INF/neoforge.mods.toml` `[[mixins]]` path - no manual `MixinEnvironment.addConfiguration`.
- JPMS diagnostics: when the host ships Mixin as a named module without an opens edge, the sync path
  and the re-weave bridge now report the exact `--add-opens org.spongepowered.mixin/...=ALL-UNNAMED`
  argument instead of a bare reflective failure.

### Fixed
- Fabric `MixinClassInfoSync`: a failure between the field phase and the method phase leaked the
  already-added members (the rollback handle was built only after both phases succeeded). The shared
  engine now rolls back what was already added; covered by `ClassInfoMemberSyncTest`.
- `plugin-forge` `ForgeClassInfoSync`: Mixin `ClassInfo` member sync across ModLauncher's
  ClassLoader boundary. Newly declared `@Mixin` fields/methods are added to the same `ClassInfo`
  instance **before** `IMixinTransformer.reload` and rolled back when reload is rejected; every
  member lookup is resolved against the host ClassLoader's own `FieldNode`/`MethodNode` types, so a
  stale agent-side ASM copy cannot be matched.
- `tests/fabric-mixin-probe/forge-classinfo/`: real Forge 1.21.1 dedicated-server E2E for the above
  (stage 1 adds an `@Inject` handler method, stage 2 adds a `@Unique` field), with a witness-based
  `Verdict` whose discriminating power is pinned by red / green / not-attempted / self-contradictory
  fixtures (`verify-evidence.sh`).

### Fixed
- Forge/NeoForge: adding an `@Inject` handler method or a `@Unique` field to an existing `@Mixin`
  template was rejected at reload (`Conform error ... please restart client`, followed by a
  `ClassInfo$Method.isUnique()` NPE) because `MixinInfo.reloadMixin` reuses the **pre-reload**
  `ClassInfo` and the new members were invisible to its conform check. Forge/NeoForge now hot-swap
  those changes instead of demanding a restart; Fabric parity with `MixinClassInfoSync`.

## [0.2.4] - 2026-11-07

### Added
- `@RemoldReinit` `scope` (`SELF` / `INCLUDE_SUBCLASSES`, default preserves current behaviour):
  `SELF` reinitializes only exact declaring-class instances, with canonical host `Class`
  normalization so JBR shadow-`Class` rewriting cannot corrupt the filter after a structural
  redefine; static-field scope is ignored with a `[REINIT-SCOPE]` warn.
- `plugin-forge` `ForgeMixinHotSwap`: reflectively reload changed `@Mixin` templates and cascade
  re-weaving + `redefineDynamic` to loaded targets (Forge/ModLauncher).
- Seconds-scale verification probes: multi-mixin same-target (Fabric + Forge), `@Inject`
  self-evolution (cancellable / target-method switch / add-remove handler), `sourceBytecodeCache`
  real-path matrix, `LayoutGate` reject -> next-round recover E2E, scope-reinit, shadow-Class
  identity; `probeAll` aggregate task (not wired into `check`).

### Fixed
- `plugin-mixin`: added/renamed `@Inject` handler methods were rejected by `reloadMixin`'s conform
  check because `MixinClassInfoSync` ran after reload; sync now runs before reload with rollback
  when reload is rejected.
- `plugin-forge`: changed Mixin templates were `defineClass`'d while `MixinInfo` stayed stale, so
  target `@Inject` bodies never updated.
- `core` profiler: shared mutable transformer state caused reentrant `classReader` clobbering
  (`NullPointerException` / intermittent `ClassFormatError`); `transform` now uses per-call locals.
- `core` profiler/LibTool: stale static-field `Class` references (JBR shadow `Class`) are
  canonicalized at API boundaries; `toggleEntityProbe` catches `Throwable` and rolls back.
- `core` layout: type-change reject summaries no longer claim the reader scan failed.
- `plugin-arc`: `Injector` used the package-private `RemoldAgent.inst` across loaders; now uses
  public `RemoldAgent.getInst()`.

## [0.2.3] - 2026-11-06

### Fixed
- **Mixin 目标基线被二次织入污染（热更静默失效）**：外部重定义 / 基线捕获通道把 JVM 已织入的
  `classfileBuffer` 再交给 Mixin 桥，追加 hash 混淆的重复 `@Unique` 字段；这些字段写进基线缓存后，
  下一轮真实热更会被 LayoutGate 判成 `field(s) removed` 并整类拒绝（日志只有拒绝，编辑不生效）。
  基线捕获路径现在直接采用 JVM 输入字节，不再重织入。
- **实体探针开关污染热更基线**：`DynamicProfilerAPI.toggleEntityProbe` 的重转换走外部重定义通道，
  对已织入的 Mixin 目标同样二次织入。该重转换现在运行在 `RemoldAgent.enterInternalRetransform` 内：
  探针仍正常插桩/去插桩，但 `AnnotationTransformer` 不再改缓存；`exitInternalRedefine` 在 `finally`。
- **`redefineDynamic` 无基线时跳过布局门与 InitFix**：基线按
  `bytecodeCache → fetchOriginalBytecode → sourceBytecodeCache` 回退，三者全缺时 fail-closed；
  运行期字节等于基线的 no-op 分支（返回 `true` 但并未重定义）改为默认可见日志。
- **重试台账与注入冲突误判**：per-class 重试台账移出 redefine 的 `catch`，避免后处理失败被记成
  `REDEFINE-FAILED`；`injectNewClass` 遇到 `LinkageError` 时先校验已加载字节，冲突不再被当成成功。
- **源码基线未回滚 / 指纹口径错误**：未实际生效的类回滚 `sourceBytecodeCache`；磁盘指纹只记
  `Files.readAllBytes` 的原始字节，不含对齐/织入产物。
- **偏移漂移漏检与误报**：检测子孙类继承字段的漂移；漂移绑定按 `declaringClass#field` 去重，
  同名遮蔽字段不再互相顶掉；`LayoutPredictor` 归一化点分/斜杠类名。
- **L0 告警口径**：只在真正重定义成功的类上运行；删除 `@OnReload` 建议；聚合超过 5 个类时跳过
  实例枚举；继承字段的父类 `@RemoldReinit` 单独告警。
- **Fabric 插件 agent 参数拆分**：只按平台路径分隔符拆分，补跨平台覆盖。

### Added
- **L0 存量字段初值变更检测**：`InitializerDrift` 对本次重定义类的每个已有字段做 `<init>` 切片比对，
  检测"字段还在但初值变了"（`INIT-DRIFT`）；含实例门、继承字段措辞、聚合告警与
  `INSTANCE_ENUMERATIONS` 诊断。
- **真实环境端到端探针与证据**（`tests/fabric-mixin-probe/`）：
  - Fabric 1.21.1 主路径 C3：真实 `KnotServer` + 真实 `-javaagent`，加方法 / 加字段 / 连续两轮
    no-op / 修改 Mixin 类本身（`MixinHotSwap`），零 `INIT-DRIFT`；
  - 实体探针 C1+C2 联合复现：修复前基线出现重复 `@Unique` 字段且下一轮被 LayoutGate 拒绝，
    修复后基线不变、热更成功；
  - Forge 1.21.1（ModLauncher / ForgeGradle 7）端到端：目标类同时是真实 Mixin 目标，
    经 `plugin-forge` 跨 ClassLoader 重织入后 `@Unique`/`@Inject` 保留、InitFix 补字段成功。
- **新增回归测试**：Mixin 桥接调用计数（基线捕获/内部重转换不得调用桥、真实源码变更必须调用）、
  实体探针内部重转换上下文、L0 初值漂移、偏移继承漂移、`redefineDynamic` no-op 与 fail-closed 等。

### Changed
- 测试基线：JUnit 446 项（0 skipped / 0 failures），`fullCheck` 门禁不变；新增断言型探针
  `entityProbeToggleProbe` 等，探针与证据统一收在 `tests/fabric-mixin-probe/`。

## [0.2.2] - 2026-11-05

### Fixed
- **热更目标被布局预测副本顶掉（"加了字段却不生效"的真因）**：`LayoutPredictor` 用抛弃式加载器
  （以真实加载器为 parent、child-first）独立加载新字节码预测物理布局，而 `loadClassSnap` 按"最子层优先"
  选同名类，于是预测副本会盖掉活类。此后每一轮热更都在 redefine 一个从不初始化、也没有任何实例的死副本：
  日志照样打 `HotSwap successful`，但 InitFix 因"类未初始化"跳过补丁、`@OnReload` 也扫不到。
  现在预测副本（`LayoutPredictor.isEphemeralLoader`）不参与同名类仲裁，也不进入 `MountManager` 的包→加载器映射。
- **后处理阶段失败不再静默**：`triggerHotswapWith0` 由调度器以 `Future` 承载，其中抛出的异常会被完全吞掉，
  而 InitFix 补丁 / `@OnReload` 回调 / 缓存驱逐都排在 `redefineClasses` 成功之后。现在该入口兜 `Throwable`
  并显式报告；DEBUG 下另有 `[POST]` 首尾两行标示后处理是否进入与结束。

### Changed
- **Gradle Plugin Portal 退回后补齐英文文档**：`v0.2.1` 的 `publishPlugins` 已进入人工审核，被判定
  "lacks documentation in English" 并取消该次提交（官方邮件明确可复用同一版本号）。此前 `gradlePlugin.website`
  指向仓库根，而根 README 以中文为主，审核看到的就是中文。现在新增英文插件文档 `gradle-plugin/README.md`
  （坐标、DSL 选项、任务清单、环境要求），`website` 指向该文档，并在 `README.md` 顶部加英文摘要 + 指向
  `README_EN.md` / 插件文档的链接；重新 `publishPlugins` 后再由审核结果定论。
- **Gradle 脚本插件瘦身为薄壳**：`remold.gradle` / `remold.gradle.kts` 从 1,031 / 1,120 行的完整实现缩到 ~40 行，
  只负责把二进制插件 `io.github.i-hope1.remold` 挂到工程上；解析、任务注册与参数拼装全部收敛到 `:gradle-plugin`
  （此前是脚本 + 插件两套并行实现，且 README 与打包注释声称"只有一份"，与事实相反）。
  插件来源优先 `-Premold.pluginJar` / `REMOLD_PLUGIN_JAR`，否则按版本取 JitPack 坐标。
- **`RemoldExtension` 补齐脚本语法面**：`plugins(Object...)` 支持 `plugins(['arc'])` 展平、新增 `setPlugins`、
  `watchDir(Object)` 接受路径字符串；resolver 用 `project.file(...)` 归一字符串形式的 `watchDirs`。
- `gradle-plugin` 的 `processResources` 注释改为"随 jar 附带薄壳副本"，不再声称脚本是共享实现。
- **加载器集成从 core 迁出（`plugin-fabric`）**：`fabric.classPathGroups` 注入、`run/mods` 残留制品巡检与 `mods` 目录监听排除不再由引擎核心承担，改由 `remold-plugin-fabric` 通过 `HotswapPlugin` 的通用窗口实现；`core` 不再出现任何加载器专属的目录或属性约定。
- **`RemoldAgent` 拆分**：布局门判决、被删成员读者扫描、监控路径记账、监听线程、jar 解压、环境能力探测分别搬到 `LayoutDecisionEngine` / `RemovedMemberScanner` / `WatchPathRegistry` / `FileWatcherService` / `JarExtractor` / `EnvironmentProbe`；公共 API 与全部调用方保持不变。

### Added
- **残留构建 JAR 从"告警"升级为"隔离"**：`plugin-fabric` 在 premain 阶段把 `run/mods` 中与本工程同 modId 的 JAR 就地改名为
  `.jar.remold-disabled`（只改名不删除）。原因：同 modId 的打包 JAR 会让加载器把它当作开发中模组的候选容器，
  实际可能运行 JAR 里的旧字节码而忽略 `build/classes`，且不报错——仅告警无法阻止这种"改了没反应"。
  只处理同 modId 制品；改名失败退回告警；`-Dremold.agent.mods_quarantine=false` 关闭。
- `HotswapPlugin` 新增三个默认方法：`onPremain(String agentArgs)`（插件发现后、`init` 前）、`onWatchPathsChanged(Set<Path>, Set<Path>)`、`isWatchPathIgnored(Path)`（监听排除投票）。
- 新增模块 `:plugin-fabric`（`remold-plugin-fabric.jar`），Gradle 侧检测到 Loom 系生态时自动挂载（`-Premold.autoFabric=false` 可关闭）。
- 新增 `tests/src/remold/PluginWatchPathTest`：锁住"core 无加载器专属排除规则 / 插件投票被采纳 / 插件抛异常不中断路径判定"。

## [0.2.1] - 2026-11-03

### Added
- **Minecraft Forge & NeoForge 统一自适应插件 (`:plugin-forge`)**:
  - 新增扩展插件 `:plugin-forge`（`remold-plugin-forge.jar`），提供 `remold.HotswapPlugin` SPI 实现，支持 `forge` 与 `neoforge` 双别名挂载；
  - 跨 ClassLoader 穿透重织入（`ModLauncherMixinBridge`）：解决 ModLauncher / NeoForge 模块化类加载器与 Agent 间的 ClassLoader 隔离，穿透执行 Mixin 增量重织入；
  - 事件总线自动刷新（`ForgeEventBusRefresher`）：监听重定义批次，自动识别 `@SubscribeEvent` / `@EventBusSubscriber` 的监听器增删，并在 `EVENT_BUS` 上自动注销与重新注册；
  - Gradle 脚本（`remold.gradle`/`remold.gradle.kts`）自动识别 Forge/NeoForge 生态（`autoForge`），并自动向 ModDevGradle（`runs.client`）与 ForgeGradle（`minecraft.runs.client`）注入热重载探针参数；
  - GitHub Release CI 自动化打包并发布 `remold-plugin-forge.jar`。
- **Fabric Loom 原生 Application 启动与守护进程解绑**:
  - 模板默认配置 `loom.runs.configureEach { preferGradleTask = false }`，解除游戏运行对 Gradle 守护进程（Daemon）的独占锁定，转为原生 Application 启动；
  - 自动化同步 `Minecraft Client` 与 `Minecraft Server` 的 BeforeRunTask 为 `deployArtifact`，运行前自动将最新构建产物同步至 `run/mods`；
  - 优化 `Minecraft_Client.xml` 与 `Minecraft_Server.xml` 的 JVM 参数同步机制，使用 `Matcher.quoteReplacement` 原位更新 `-javaagent`，杜绝重复追加与 regex 符号转义损坏。
- **IDE 原生编译目录实时监控 (`out/production`)**:
  - 脚本层自动探测并创建工程根目录下的 `out/production`，纳入 `-javaagent` 监控目录表；
  - `RemoldAgent` 增强：显式指定未建的 `out/production` 时自动创建，当监控包含 `build/classes/...` 时自动向上查找到项目根目录探查并挂载 `out/production`；
  - 在 IDEA 中通过单文件编译快捷键（`Ctrl + Shift + F9`）编译的 class 改动能直接在 200ms 内触发热重载，无需等待 Gradle 完整构建。
- **测试套件与守卫**:
  - 新增 `ForgePluginTest` 与 `WatchPathTest`，测试基线提升至 403 个 JUnit 测试；
  - 规范状态标记扫描 (`verifyDocStatusMarkers`) 将 `plugin-forge/src` 纳入守卫范围，全量代码与客观设计文档 100% 保持无易腐标记。

## [0.2.0] - 2026-11-02

### Added
- **Minecraft Fabric & SpongePowered Mixin 生态支持**:
  - 新增扩展插件 `:plugin-mixin`（`remold-plugin-mixin.jar`），支持 `@Mixin` 目标类重织入与级联重定义；
  - 新增开箱即用的 Minecraft Fabric 模版 `minecraft-fabric-template`，支持 Loom 自动注入与热重载；
  - 运行时标准 JVM 能力探测（`EnhancedHotswapGate`），在非增强 JVM 下自动摘批降级，避免崩溃。
- **Arc 全套资源运行时热重载 (`:plugin-arc`)**:
  - 贴图同尺寸原位直刷（Tier 1）与运行期新增贴图动态注册；
  - 字体（woff/ttf）按文件名精确路由至 UI 槽位；
  - 多语言国际化 Bundle 按 locale 父链路由并安全回写 `UnlockableContent` 内容缓存；
  - 音效与音乐原位重载（句柄复用、内存释放与平滑播放恢复）；
  - 内置着色器原位重新编译回写。
- **成员删除读者分析与分派安全门 (`RemovedMemberReaders`)**:
  - 实现基于 JVMS 5.4.3.2 向上解析与 JVMS 5.4.6 向下分派的批次外读者分析纯函数；
  - 精确区分未决悬空（`NoSuchFieldError`/`NoSuchMethodError`）、抽象方法缺失（`AbstractMethodError`）、静态性翻转（ICCE）与合法回退（Override 回退或父类同名字段接住）；
  - 扫描未完成时安全熔断，阻断陈旧代码破坏；
  - 编译期常量（`ConstantValue`）安全放行。
- **物理内存布局预测器与漂移安全门 (`LayoutPredictor` / `SuperclassDriftGate`)**:
  - `LayoutPredictor` 通过独立抛弃式 ClassLoader 事前干净加载预测物理布局（实测与 DCEVM 布局逐字节 100% 一致）；
  - `SuperclassDriftGate` 检测并阻断 `LAYOUT_DRIFT`、`SUPERCLASS_FIELD_DRIFT` 与 `DELETED_FIELD_OFFSET_CONFLICT`；
  - 预注入整条非 JDK 祖先链，防御 package-private 运行时包隔离访问异常；
  - 访问器自愈实测证明（`Field.get` 与 `MethodHandle` 自动自愈，仅拦截固化偏移的 `VarHandle` 与 `Unsafe`）。
- **第三方实例级缓存驱逐 SPI (`ReflectionCacheManager`)**:
  - 提供 `ReflectionCacheEvictor` SPI，在类重定义成功后自动驱逐实例级元数据缓存；
  - 内置 Jackson `ObjectMapper`、Gson `Gson` 与 Arc `Json` 实例级驱逐器。
- **Lambda 表达式增强对齐与单向捕获桥 (`LambdaAligner`)**:
  - 新增单向捕获桥，支持安全删除捕获变量；
  - 支持新增捕获的纯源重放（字面量常量、`static final`、`final` 实例字段、可折叠算式、构造器参数经 final 字段）；
  - 引入运行时常量访问器登记表（`RuntimeConstants`），支持 `Scl.scl()` 及带参形态 `Scl.scl(float)`；
  - 引入 LVT 形参名定捕获身份，拦截同签名换序的静默错值；
  - 骨架指纹与 consumer 偏好拆开同形 lambda，消除多轮静默串位。
- **字段描述符类型变更热重载 (`LayoutGate` / `@RemoldReinit`)**:
  - 当字段类型发生变化（oldDesc != newDesc）且批次外无悬空读者时，在堆上无实例或标有 `@RemoldReinit` 时直接放行；
  - 自动清空旧类型 `REINIT_APPLIED` 指纹缓存，重定义后由 InitFix 安全写入新类型的初始值；若有实例未标 `@RemoldReinit` 则给出明确引导文案。
- **构建与测试体验提速与开发者引导**:
  - 脚本与 Gradle 插件自动为 `JavaCompile` 注入全量调试符号（`debug = true`, `source,lines,vars`），为 LVT 形参捕获与参数回溯提供高精度符号支撑；
  - 为子模块增设本地 JitPack 兼容发布通道（`com.github.I-hope1.Remold:*:v<ver>`），支持下游模版在尚未发布远程 Tag 时无缝本地联调；
  - 为 Minecraft Fabric、Mindustry 及示例工程补充系统化的「动手试试」系列引导教程注释；
  - 新增常驻 Javac 守护进程 (`JavacDaemon`)，测试夹具编译提速 60x（2370ms → 35ms）；
  - 新增 Gradle 插件封装模块 `:gradle-plugin`（`io.github.i-hope1.remold`）；
  - 新增 GitHub Tag 自动化 Release 工作流（`.github/workflows/release.yml`）；
  - 测试套件扩充至 397 个 JUnit 测试，持续守卫架构不变量。

### Changed
- 基础许可证切换为 Apache-2.0（插件模块保持各自生态兼容许可）；
- ASM 统一升级至 9.11.1，单一真相源配置于 `gradle.properties`；
- 存活实例枚举后置到重定义之后，彻底关闭枚举时刻与重定义时刻之间的并发实例化缝隙；
- `@RemoldReinit` 增设生效点名日志，收紧重置时机至初值表达式变动；
- Arc Json 缓存驱逐器移入 `:plugin-arc`，核心引擎不再反向持有 Arc 外部知识。

## [0.1.3] - 2026-10-24
- 修复 `@OnReload` 回调早于 InitFix 补丁的时序颠倒缺陷；
- 修复 IDEA 运行配置中 Agent 与插件 Jar 路径统一落到 `.gradle/remold/`；
- 识别并明确提示同一编辑被反复拒绝的循环；
- 补充 Mindustry PNG 资源热更与 Fabric 模版演示。

## [0.1.2] - 2026-10-23
- 完善模版配置与发布流程收敛。

## [0.1.1] - 2026-10-23
- 将 `remoldVersion` 收敛至 `gradle.properties` 作为单一真相源。

## [0.1.0] - 2026-10-23
- 初始版本发布：InitFix 存量初始化与匿名类拓扑对齐引擎。
