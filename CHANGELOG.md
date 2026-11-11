# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- 重定义访问权限防线（四层）：`core/src/remold/ref/AccessAlign.java` 以 JVM 已加载运行时字节码为基线，
  对重织入产物做**访问权限单调对齐**（public 不得退化、protected 不得退化、基线已剥离的 `ACC_FINAL`
  必须同步剥离），并在送入 `redefineClasses` 前做 fail-closed 自检门；Fabric 侧新增 Mixin 前置变换重放
  （`FabricTransformer` 的 `PackageAccessFixer` / `AccessWidener`，`plugin-fabric` +
  `plugin-mixin readOriginalBytes`）；`RemoldAgent` 对 `InternalError`/`VerifyError` 记入本会话黑名单，
  `[Mixin]` 日志不再把 JVM 级失败误归因为 LayoutGate。
  动机：Mixin 目标从原始 jar 重织入时丢失加载器在 Mixin 之前做的权限提升，112 个可访问成员退回
  `protected`，JBR 增强重定义连带 link/verify 子类时触发
  `VerifyError: Bad access to protected data in invokevirtual` → `JVMTI_ERROR_INTERNAL`。
  验证：`AccessAlignTest` 5/0（含真实 JVM 跨包 `VerifyError` 红基线 + 对齐后绿）；变异检查关闭对齐逻辑后
  3/5 变红、恢复后复绿。
- Mixin 生成成员名/顺序基线对齐：`core/src/remold/ref/MixinMemberAlign.java` 把
  `handler$/redirect$/wrapOperation$/localvar$` 等 Mixin 合成成员按 `(前缀, $z<id>$ 之后的尾名, 描述符)`
  映射回已加载基线名，并按基线顺序重排方法/字段（ASM `ClassRemapper` 同步改写调用点与
  `InvokeDynamicInsnNode.bsmArgs` 中的 `Handle`）。真机采样证明同一 Mixin 会话内 unique id 稳定，
  该层在本次场景为防御性 no-op；`MixinMemberAlignTest` 6/0（含真实捕获字节回归），变异关闭改名/排序后
  分别 2/2 变红。
- 同名类多副本选择：`MixinHotSwap` 发现同一 dot 名在多个 ClassLoader 各定义一份时，优先选择宿主加载器
  （Knot/ModLauncher）的副本，而不是 Agent/System 加载器可能提前定义的裸副本；并在日志里点名全部副本
  加载器。真机实测这是 `LivingEntity` 重定义失败的真正根因：此前两轮选中的是 `AppClassLoader` 的裸副本
  （无 Mixin 织入、无 `PackageAccessFixer`），JBR 报 `added interface HonkaiEntityAccess` 并最终
  `VerifyError: Bad access to protected data in invokevirtual` → 裸 `InternalError`。
- 内部重定义期间对“受影响的子类”原样放行：`AnnotationTransformer` 在 `INTERNAL_CONTEXT` 下收到非本次目标
  的 ClassFileLoadHook 时直接返回 `null`，不再二次走 Mixin 桥/布局门（`-Dremold.agent.internal_passthrough`
  可关闭用于取证）。
- JBR 裸 `InternalError` 透视：`RemoldAgent` 捕获 `InternalError`/`VerifyError` 后经 DiagnosticCommand
  `vmInfo` 抓取 HotSpot `Internal exceptions (N events)` 缓冲最后 160 行写入日志（`[VM-INFO]`）。
- 真机闭环（patch 后模板工程实测）：`LivingEntityErosionMixin` 连续两次热更均
  `[DYNAMIC-REDEFINE] Successfully redefined net.minecraft.entity.LivingEntity`；无 `[VM-REDEFINE-POISON]`、
  无 `VerifyError`/`InternalError`、之后持续运行（tick 增长、60fps）无 C1 崩溃。日志明确：
  `Multiple loaded copies ... KnotClassLoader@42f93a98 vs AppClassLoader@3fee733d; using KnotClassLoader`。
- 同名类副本仲裁全仓收口：新增 `core/src/remold/ClassCopySelector.java`（bootstrap/System-Agent/宿主
  三级 rank + 子加载器关系 + 拒绝抛弃式预测副本），统一替换 `RemoldAgent.loadClassSnap`、
  `MixinHotSwap`、`MixinInnerClassHotSwap`、`MountManager` 包映射、`DynamicProfilerAPI` 的候选选择。
- 基线缓存防裸副本覆盖：`AnnotationTransformer` 的首载写入改走 `RemoldAgent.updateInitialBaseline`，
  按加载器 rank 仲裁（低权威副本拒绝、高权威可替换、manifest 对齐产物强制、ephemeral 永不入缓存），
  并在发现同名二次定义时打印带 40 帧调用栈的 `[DUP-LOAD]`。
- 源头切断（真机 `[DUP-LOAD]` 40 帧栈定位）：`HostReadinessProbe` 的默认类查找口不再使用
  `Class.forName(..., getSystemClassLoader())`，改为只从 `RemoldAgent.loadedClassesMap`（宿主快照）取；
  此前 `staticInstance -> getDeclaredMethod` 会把 `MinecraftServer`/`MinecraftClient` 及其签名类型
  （`LivingEntity`、`Attackable` 等）全部定义为 `AppClassLoader` 裸副本。
- `[VM-INFO]` 改为输出完整 `Internal exceptions` 区块（直到下一个区块头），不再被后续
  `InternalError` 事件顶出窗口。
- 真机终验（最终代码、最终 jar）：连续两次 `LivingEntity` 热更成功，`[DUP-LOAD]` = 0，
  无 `[VM-REDEFINE-POISON]`/`VerifyError`/`InternalError`/`Rolling back`，游戏持续运行无 C1 崩溃。
  单测：`ClassCopySelector`/缓存仲裁/就绪快照/成员对齐/受影响的子类放行全部绿；变异检查全部 killed。

### Fixed
- `[ANON_ALIGN]` 的 Tier 4.5 日志不再把“扫描可见的活实例为 0”表述成“实例已死”，也不再把
  “实例数为 0”当作配对安全性的依据。此前该行写作 `deterministic-dead-pair (0 live instances)`，
  而真实客户端里字段持有的实例仍然存活并继续执行（`fieldAnon v2/v3`），文案与事实不符。现在该行
  改为 `deterministic-zero-scan-pair (scan-visible live instances: 0)`，并显式说明：
  ① 判据只是“本轮 JVMTI 堆扫描对这些旧候选没有可见的存活实例”，扫描结果不能反证不可见实例不存在；
  ② 放行的另外一条依据是配对双方布局完全兼容；③ 这既不是结构唯一匹配，也不是语义安全结论，
  只能当作审计线索。宿主字段可能持有时，日志会点名 `proven`（精确描述符/赋值流）与 `candidate`
  （父类型/接口）两种强度的持有者字段，其中 `proven` 持有者会把该行升级为 `warn`；`AlignmentStats`
  的计数字段 `deterministicDeadPairs` 更名为 `deterministicZeroScanPairs`（旧名字段保留为
  `@Deprecated` 同步别名，维持 0.2.6 下游二进制兼容）。同一份
  liveness 判据派生的 `[ANON-LAYOUT]` / `[LAYOUT-WAIVE]` 放行文案与歧义诊断（`scanVisibleLive=`/
  `scanVisibleLiveOld=`）同步改用“扫描可见”措辞，`RemoldAgent.hasLiveInstances` 的 Javadoc 补上
  “false = 本轮扫描没看见，不是实例不存在的证明”的语义边界。Tier 4.5 的判定逻辑与行为不变（仍是全组扫描可见零存活 + 布局 100% 兼容）；
  没有把“存在持有者字段”并入 liveness 判据——那会把 0.2.6 已消解的字段持有场景重新推回孤儿化，
  而正确做法是让日志把“扫描可见”与“字段可能持有”两个事实同时摆出来。
  验证：`AnonClassAmbiguityTest` 17/0（新增 4 个文案/持有者用例，含“无持有字段不得凭空点名”的负向对照；
  变异检查：把日志文案改回旧词后 4 条断言变红，恢复后复绿）。

## [0.2.7] - 2026-11-10

### Fixed
- **合成类存量实例（liveness）三档闸门**：真机 P0 复跑证明「字段表只增不减」不足以避开 JBR
  `ModifyInstances`——`fieldTableChanged=1` 的原地 redefine 仍把无关类 `LootContextPredicate`
  的 `combinedCondition` 冲成 `null`（230/244）。补上缺失维度：`MixinInnerClassHotSwap.syntheticLive`
  在每次合成类重定义前数堆上存量实例（`raw` = `LibTool.getInstances` 堆遍历，含不可达但未被 GC
  物理回收的临时实例；字段表变化且 `raw>0` 时一次 `System.gc()` 后再取 `reachable`），并打点
  `live{raw=… reachable=…} fieldTableChanged=… loaded=[…] fresh=[…]`。闸门分三档：字段表未变
  → 一律放行；字段表变化 + 有存活实例 → 拒绝并 `blockHost`（旧实例必成半残 0/null 且迁移污染无关堆）；
  字段表变化 + 无存活实例 → 放行（真机待裁定）；探针不可用 → 拒绝（fail-closed）。
  验证：`MixinSyntheticLivenessTest` 4/4（含产品探针真堆对照：强引用 `reachable≥1`、丢引用 + GC 后
  `reachable==0`）；变异 3/3 击杀（反转 liveness 判据 / 去掉宿主 Abort / 关掉整条闸门）。
- **合成类基线读取源（假接线 ①）**：`MixinInnerClassHotSwap` 的 `retainConstructors` 原先读
  `GENERATED`（本进程生成过的覆盖层）当基线，而 R0 启动期加载的合成类在 R1 首次热更时该覆盖层为空，
  于是旧 `<init>` 一个都保留不下来，闸 2 反而把这次重定义拒掉（真机 `executed=0, gateRejected=1`，
  随后 `NoSuchMethodError: <Target>$Anonymous$<hash>.<init>` 崩服）。改为 `baselineBytesForLoaded`：
  本加载器已生效字节 → premain 录口 `(loader 身份, 点分名)` → 最后才退回覆盖层；该读取不计 `missed`
  （分母口径是"闸 4 一次拒绝计一次"）。
- **宿主事务原子中止（假接线 ②）**：`BLOCKED` 原先只打日志——`reweave` 仍把已织入新 `<init>` 调用点的
  字节返回给 `redefineDynamic`，宿主照样被重定义成半残状态。新增 `BytecodeTransformerBridge.Abort`
  作为「本轮放弃本类重定义」的唯一信号：`applyTransformerBridges` 原样上抛、`redefineDynamic` 捕获后
  **不调** `redefineClasses`、批量入口把该类移出本批；`MixinReWeaver` 在入口与织入后各拦一次；
  `MixinHotSwap.flushPendingTargets` 每轮清理/重试并给出「宿主保持旧字节」的专属日志（不再误引向
  LayoutGate）。顺带修正 `BLOCKED` 的键：`apply()` 路径原先用 Mixin 模板名，现取
  `InnerClassInfo.getTargetName()`。
  验证：`MixinHotSwapWiringTest` 2/0 + `BridgeAbortTransactionTest` 2/0；变异 3/3 击杀
  （基线读回覆盖层 / 关掉 `BLOCKED` 拦截 / 吞掉 Abort 照常重定义）。

### Added
- **新 Mixin 运行期登记字节通道**：`MixinClassByteSources`（已登记内存字节 → 全部 watch 目录最新磁盘字节 →
  上次批次源码缓存）+ `MixinNewMixinBytes`（在 `MixinConfig.prepare()` **之前**把 Mixin 与 `$N` 内部类字节
  灌进 `IClassBytecodeProvider` 覆盖层与 core `BytecodeSources`，并驱逐上游 `ClassInfo` 负缓存）；
  `MixinBytecodeOverlay.applyToConfigs()` 把每个 `MixinConfig` 启动期捕获的 `IMixinService` 也换成服务代理
  ——纯 IDEA 编译（只落 `out/production/classes`）的新 Mixin 不再需要双写输出目录或重启。
- **子包相对名支持**：`honkai.XxxMixin` / `client.hud.YyyMixin` 这类多层相对名可登记、可墓碑自愈。
- **配置先到 / 字节后到的待决接力**：`MixinConfigDiffer.pendingUnresolvedMixins` + `retryPending`，先保存
  `mixins.json` 只记待决，随后 `.class` 落地即自动完成登记与目标重织入（TTL + 重试上限，名字被删即放弃）。
- **`ReloadMixinInfo` 结构契约突破**：`MixinReloadPolicy` 对齐 `priority`（final 字段）与 `interfaces`
  比较基线并支持失败回滚；`MixinRetarget` 在 `reload` 前就地迁移 `targets` 元数据与四处索引（失败快照回滚）；
  `MixinHotSwap` 对 `re-entrant lock` 瞬态做有界退避重试；`ClassDiffUtil.mustRejectHierarchyChange`
  把「父类变更」与「接口增删」分开判决（仅后者在 JBR/DCEVM 放行）。

### Fixed
- **合成匿名类重建/重定义顺序**：`MixinInnerClassHotSwap.refeedForTarget` 不再「已回灌就跳过」，
  每次都按**当前** Mixin 模板重新生成合成内部类字节；若已加载类的字节或成员 schema 与新生成为不一致，
  在**目标类 redefine 之前**就地重定义（`redefineDynamic` 的桥接阶段在 `redefineClasses` 之前），
  关闭「目标已换新 `<init>` 描述符、合成匿名类还是旧构造器」的窗口（真机 `NoSuchMethodError:
  <Target>$Anonymous$<hash>.<init>(...)`）。
- **合成类 schema 变更放行**：`apply` 的成员 schema 门在 JBR/DCEVM 上放行（构造器描述符随
  Mixin→Target 映射变化是结构热更的正常形态）；标准 HotSpot 保持 fail-closed 拒绝并点名。
- **内部类新字节优先**：`registerInnerClassBytes` 优先使用本轮覆盖层里显式登记的新字节，
  不再被磁盘/源码缓存里的旧版本覆盖（真机复现：改了匿名类体却仍是旧 schema）。
- **生成方法墓碑桩保留（参考 `LambdaAligner` 的孤儿方法策略）**：`MixinMemberAlign` 在把生成成员名/顺序
  对齐回基线后，对「基线存在、重织入产物已消失」的 Mixin 生成方法（`handler$*`/`redirect$*`/
  `wrapOperation$*`/`modify$*`/`localvar$*`/`args$*`/`md$*`）补最小合法空操作桩（void→`RETURN`；
  返回类型与某入参相同→原样返回该入参；其余→零值），保持同名/同描述符/同访问标志/同方法表位置。
  JBR/DCEVM 在 safepoint 重定义后，**已在调用栈上的旧栈帧**继续执行旧字节码并调用旧 `handler$*`，
  墓碑桩让其无害返回，物理根除 `NoSuchMethodError: handler$*`（retarget 卸载旧目标时高发）。
  业务方法不复活（只处理合成成员）。
- **基线字段墓碑保留（Tombstone Field Retention）**：`MixinMemberAlign` 对「基线存在、重织入产物已消失」
  的字段保留同名/同描述符/同访问标志与基线槽位。Mixin `@Unique` 字段在 `retarget` 移走时不再从目标类
  字段表消失、迁回时也不再当新字段加回，**实例内存布局 R1~R4 全程恒定**，消除 JBR/DCEVM 的
  `ModifyInstances` 缩容/扩容与重复 `Applying instance field init patch`（真机 `LootContextPredicate.combinedCondition`
  被置 null 的肇事窗口）。
- **合成匿名类字段 Schema 锚定（禁止字段缩容）**：`plugin-mixin` `MixinSyntheticSchema` 以**已加载**合成类的
  字段表为锚，重生成产物缺哪个捕获字段就按原槽位补同名/同描述符墓碑字段（`ACC_SYNTHETIC`，无读者）；
  类型漂移（同名不同描述符）fail-closed 拒绝重定义。字段表恒等 → JBR/DCEVM 只做方法体替换
  （`ModifyMethodBodies`），**不触发 `ModifyInstances` 全堆重排**（真机 `combinedCondition` 被冲 null 的根因）。
  `MixinInnerClassHotSwap` 两条重定义路径（目标类前预重定义 / 兜底重定义）都接入该守门，并新增
  `[HEAPWATCH] before-synthetic-redefine` / `after-synthetic-redefine` 切面。
- **合成匿名类单调并集（同名异型字段重命名追加 + `<init>` 重载墓碑 + 事务一致性）**：
  `MixinSyntheticSchema.renameCollisions` 对同名异型的捕获字段按 `name$remold$<descHash>` 确定性重命名并
  改写类内 `GETFIELD/PUTFIELD`（基线槽位原封不动、新字段追加）；`retainConstructors` 从 `LOADED` 旧字节
  复制基线 `<init>`，新旧构造器重载并存；`MixinReWeaver` 在合成类无法安全落地时**中止宿主目标重定义**
  （规则 3，不产生"被调方拒绝、宿主已重定义"的分裂状态）。字段表因此只增不减、且永不缩容/漂移。
- **重定义三切面堆实例哨兵**：`remold.ref.HeapFieldWatch`（`-Dremold.probe.heapwatch=true`，
  `-Dremold.probe.heapwatch.class=...`、`-Dremold.probe.heapwatch.field=...`）在 `redefineClasses` 前、
  返回后、字段初始化补丁完成后枚举目标类实例并报告 `nullCount` 与首例身份；只读、默认关闭。
- **合成匿名类无实质变更短路（参考 `AnonClassAligner` 的 no-op 语义）**：`MixinInnerClassHotSwap` 记账
  已成功重定义的合成类字节，重新生成的字节与运行期已加载字节完全一致时跳过 `redefineClasses`
  （预重定义与兜底重定义两条路径都短路），避免 JBR/DCEVM 无谓的堆实例重写窗口。
- **`ClassInfo.cache` 锚回**：`MixinClassInfoRegistry.reassert` 在每次登记/重载成功后把
  `MixinInfo.getClassInfo()` 写回缓存，修复 `priority → interfaces → targets A→B→A` 往返后
  `MixinTargetContext.transformSingleDescriptor` 跳过 Mixin→Target 重映射、匿名内部类 `this$0`
  构造器签名停留在 Mixin 类上导致的 `NoSuchMethodError: <init>(SomeMixin, Target)`。

### Verification
- 单测：`MixinClassByteSourcesTest` 7、`MixinConfigDiffLoadTest` 2、`MixinNewMixinBytesTest` 3、
  `NewMixinRegistrarTest` 8、`MixinConfigDifferTest` 4、`MixinReloadPolicyTest` 8、`MixinRetargetTest` 5、
  `MixinConfigDifferPendingTest` 4、`ClassDiffHierarchyTest` 5、`MixinClassInfoRegistryTest` 4。
- 变异：新 Mixin 字节通道 7/7 + revert killed；priority/targets/interfaces 3/3 killed；待决接力 3/3 killed；
  ClassInfo 锚定 1/1 killed。
- 真机（Fabric dedicated server + JBR 21.0.9 DCEVM，agent/plugin 由本版本构建）：B3（`.class` 只在监控目录）+
  B5（子包相对名）登记成功；`priority` 1000→1500 生效；`implements` 标记接口在目标实例生效；
  `targets [A]→[B]→[A]` 双向迁移成功；含匿名内部类的 Mixin 在整条往返序列后不再抛 `NoSuchMethodError`。
- 真机（同构建，注入体内含匿名类 + 两条并发锤线程）：`priority → implements → targets A→B→A` 全往返期间
  持续调用注入方法，`hammer-failure=null` / `window-failure=null` / `anon-ctor-roundtrip ok=true` → `RESULT PASS`。
- 真机连续 4 轮（同一 JVM 不重启）：每轮 `priority → implements → targets A→B→A` 全程并发锤击，4/4 轮
  `hammer-failure=null` / `window-failure=null` / `anon-ctor-roundtrip ok=true`，每轮日志均出现
  `[MIXIN-ALIGN] retained N tombstone stub(s)`；0 次 `NoSuchMethodError`、0 次 `combinedCondition` NPE。

### Known boundaries
- 接口**移除**无本地实证：只在 JBR/DCEVM 上放行并 warn，`ClassInfo` 接口表只增不删。
- Forge/NeoForge 无 `IClassBytecodeProvider` 覆盖层：带 `$N` 内部类的新 Mixin 仍可能需要一次重启。
- Mixin 升级导致上游内部字段改名时自动退化为「需重启」的 fail-closed 诊断。
- 合成内部类的成员 schema 变更在**标准 HotSpot** 上仍无法 redefine（JVM 限制），只能重启；JBR/DCEVM 上依赖增强重定义。

## [0.2.6] - 2026-11-09

### Added
- **L1 存量初值重放第一版（匿名类 `{{ ... }}` 双括号 / double-brace 初值写继承字段，默认关闭）**：`new XXX() {{ health = 250; }}`
  这类双括号初始化块改值时，存量匿名类实例按新**常量**初值重放。显式开启
  `-Dremold.agent.init_drift.parents=<父类名,...>`（白名单，空 = 关闭），且只处理
  非 final 基本类型、旧/新初值均为编译期常量、`PUTFIELD` owner 为匿名类自身的形态。
  写入走新增的 `HotswapBridge.KIND_CONDITIONAL_EXPECTED`：仅当实例当前值仍等于**旧初值**时
  才写，运行期改过的值（例如已受损单位的血量）不会被覆盖；实例集合按匿名类自身枚举，
  不会像父类 `@RemoldReinit` 那样批量重置所有子类实例；不写 `FieldLedger`、不改变
  `patchGenerated` 语义，重放成功的字段不再产生误导性的 `[INIT-DRIFT]` 告警。
  验收：`InitializerReplayTest` 11/0、`DoubleBraceProbe` 104/0（含条件跳过/非白名单/
  非恒定旧值/引用类型负向）、全量 JUnit 480/0 skipped；变异检查（白名单恒真、
  expected-CAS 改 FORCE）均按预期变红。设计见 `docs/initfix/12-L1-existing-initializer-replay.md`。
- **A 档 Kotlin `apply {}` 静态持有者初值重放（默认关闭）**：`object Blocks { val duo = Turret("duo").apply { health = 250 } }`
  这类 `object` / 顶层 `val` 持有者的内联 `apply` 写 Java `public` 字段，改常量时存量对象按新常量重放。
  显式开启 `-Dremold.agent.init_drift.apply_holders=<持有者类 fqcn,...>`（空 = 关闭）。分析只接受
  持有者 `<clinit>` 的直线形态：`NEW T; ...; ALOAD; <编译期常量>; PUTFIELD T.field; ...; ALOAD; PUTSTATIC holder.dest`，
  用局部变量副本溯源证明 `PUTFIELD` 的接收者来自唯一 `NEW` 站点、对象只存入唯一静态字段 `dest`（无其它逃逸）。
  写入协议：重定义生效后用 `GETSTATIC holder.dest` 取回那一个存量对象，再以新增的
  `KIND_CONDITIONAL_EXPECTED` 按旧初值条件 CAS 写目标类型 `T` 的字段——运行期改过的值跳过，
  `dest` 为 null/类型不符 warn 跳过，目标字段必须基本类型/非 final/非 synthetic/非 `@RemoldReinit`。
  不写 `FieldLedger`、不改变 `patchGenerated` 语义，独立报告 `PatchReport.applyReplayFields` 与
  `[INIT-APPLY-REPLAY]` 日志。Kotlin `var` setter（`invokevirtual`）、具名类属性（`<init>`，B 档）、
  方法体/局部（C 档）、引用类型字段不在范围。设计见 `docs/initfix/13-kotlin-apply-replay.md`。
- `api`: structured batch report — immutable `HotswapBatchReport` (batch elapsed time, the classes
  redefined in the batch, per-Mixin member deltas, InitFix patch scale) plus
  `HotswapBatchListener.afterInternalBatchReport()`. It is published alongside the existing
  `afterInternalBatch(List)` as a `default` method, so already-compiled listeners need no change.
  A `MixinChange` whose delta cannot be proven says so (`deltaKnown=false`) and the caller must not
  read its `0` as "nothing added"; `InitFixStats` only counts patches that actually completed.
  The data is collected per batch by the new `core` `BatchReportScope` and published only when the
  batch produced observable activity. `api` also gains `HotswapPlugin.isHostReady()` (default
  `true`) so a host can defer batches until it is ready.
- `minecraft-fabric-template`: client-side **HotSwap result toast** (`com.example.client.*`) shown
  after each hot-swap batch. It renders through 1.21.1's `Toast.draw(DrawContext, ToastManager, long)`
  (1.21.1 has no `Toast.update`), reads the structured batch report when the agent provides it
  (elapsed time / Mixin member deltas / InitFix stats), and degrades to an explicit "legacy agent"
  card on the published 0.2.5 agent instead of fabricating zeros. `HotSwapToastModelTest` covers the
  model formatting rules (unknown values, delta-known=false, three-line cap).
- Real Fabric 1.21.1 KnotServer anonymous-class probe extension
  (`tests/fabric-mixin-probe/anon/`): new S4 (long-lived field holder), S5 (`@Unique private` access),
  S6 (1→2 same-shape renumbering) and S7 (field-initializer constructor visibility) stages, an
  `ANON_EXPECT` mode and a de-trigger control per stage, each with its own evidence file. Recorded
  readings: S0~S7 all green, `EXT_GREEN=1`, exit 0
  (`anon/evidence/anon-ext-green.txt`, `anon-f2e2-all-green.txt`, `anon-final-all-green.md.txt`).

### Changed
- `plugin-mixin`: apply **Mixin config file diffs** at runtime. When a watched `*.mixins.json`
  loses (or re-adds) a mixin name, `onAssetsChanged` → `MixinConfigDiff` removes/registers the
  corresponding `MixinInfo` from the live `MixinConfig` and re-weaves the affected target classes,
  so deleting a mixin from the config drops its injected handler without a restart. Verified on a
  real Fabric 1.21.1 KnotServer: after removal the target class no longer has the handler method
  and the hook stops; a later class-file rewrite of the removed mixin is ignored instead of
  silently re-registering it; re-adding the name restores it (a re-add whose asset event was missed
  is re-checked on the next class rewrite, because the removed entry keeps its config path). Evidence:
  `tests/fabric-mixin-probe/evidence/newmixin-runtime-20261108.txt`. Client and non-Fabric
  loaders remain unverified.
- `plugin-mixin`: experimental runtime registration for a **brand-new Mixin class** that was
  not in the startup config's `mixins` list. When `IMixinTransformer.reload` returns no targets,
  `NewMixinRegistrar` reflectively appends the class to the owning `MixinConfig`, runs
  `prepare` + `MixinInfo.validate()`, then re-weaves the declared targets through the existing
  `redefineDynamic` path; validation failure rolls the registration back and tells the user to
  restart. On Fabric, an already-loaded target is handled by temporarily spoofing
  `FabricLauncher.isClassLoaded(target)` during `MixinConfig.prepare` (the narrow window in which
  `MixinInfo` rejects loaded targets), then restoring the launcher and re-weaving the target via
  `redefineDynamic`. Non-Fabric loaders and mismatched Mixin internals fall back to the explicit
  restart hint. Unit coverage: `NewMixinRegistrarTest` 6/6 (package match, idempotent
  registration, full rollback, missing-processor degradation, Mixin internals contract).
  Real Fabric 1.21.1 KnotServer E2E: an unloaded target picked up the new Mixin on first load
  (hook count 1, return value preserved); an already-loaded target was registered, re-woven and
  redefined, and its hook fired (hook count 1, return value preserved). Evidence:
  `tests/fabric-mixin-probe/evidence/newmixin-runtime-20261108.txt`.

- `minecraft-fabric-template`: the template baseline moves back from 1.21.4 to Minecraft **1.21.1**
  (yarn 1.21.1+build.3, loader 0.16.9, fabric-api 0.105.0+1.21.1); 1.21.4 had no compatibility
  advantage for 1.21.1 mod authors. The `ActionResult` / `Item.Settings.registryKey` migrations and
  the 1.21.4 item-definition assets are gone with it.
- The Fabric **client gametest** (`fabric-client-gametest-api-v1`, available from 1.21.4 only) is
  preserved as the standalone probe `tests/fabric-gametest-1.21.4/` instead of pinning the template
  to 1.21.4. It keeps the real-client assertions (item registration, hotbar model screenshot, real
  right-click chat message) and its own Gradle build; the Toast client package is removed there.

### Fixed
- **读穿 NPE（`redefineClasses` 返回后、`InitFix` 补丁完成前，新增字段仍是 `null`/`0`）**：内部批次路径
  （文件监听 → `processChanges`）改为两阶段 Schema-First 重定义。当本批确实要补「新增/类型变更字段」时，
  先提交**阶段 A**（新结构 + 旧方法体，构造器/`<clinit>` 保留新体），随后对存量实例执行 InitFix 补丁，
  最后提交**阶段 C** 的新方法体；窗口内的旧方法体在编译期不认识新增字段，读线程不会 NPE，阶段 A→C 之间
  新建的实例由新构造器直接初始化。纯方法体热更不触发两阶段（零额外 redefine）。默认开启，
  `-Dremold.agent.two_phase=false` 可回到旧行为；外部 IDE/JDWP 重定义与 `redefineDynamic` 通道仍未接线
  （见 `docs/status.md` 风险 9）。验收：`TwoPhaseProbe` 72/0（真实 `processChanges` + 50k 实例 + 并发读线程：
  关两阶段稳定 NPE、开两阶段零 NPE）、`TwoPhaseGuardTest` 5/0、全量 JUnit 527/0 skipped。
- `core` `remold.reflect.mixin`: extracted the Mixin config-diff / new-Mixin registration logic into a
  loader-neutral, reflection-only engine (`MixinConfigDiffer`, `NewMixinRegistrar`, `MixinConfigContext`).
  No Mixin/ModLauncher compile-time dependency in core; `plugin-mixin` (Fabric) and
  `plugin-forge` (Forge/NeoForge) are thin adapters.
- Mixin hot-swap no longer dies with `NoClassDefFoundError: LoadedTargetSuppressor`: the "target already
  loaded" suppressor now crosses the core boundary as plain JDK functions (`Function`/`Consumer`) instead of
  a core interface implemented by plugin classes, so a plugin/core version skew degrades to a diagnosable
  "suppression unavailable" path instead of a linkage error that kills both the success and failure branch.
- Anonymous-class alignment no longer orphans an edit just because a sibling anonymous class shares its shape: a body-structure similarity tier (anonymous-class references masked, literals excluded, strict margin, both-way unique) decides the R2b case, and only after that fails does an approved liveness fallback pair a whole ambiguity group whose old candidates all have zero live instances (logged as `deterministic-dead-pair (0 live instances)`). The orphan warning now names the old classes with their live-instance counts, the holder fields, and the `@RemoldReinit` escape hatch.
- A field whose initializer allocates a Mixin-generated anonymous class is now initialized on hot reload: the plugin pre-generates the synthetic inner classes of a freshly woven target (before InitFix's metadata scan) and feeds them into `BytecodeSources`, so `InitFix` can decide the constructor's protected status instead of refusing the patch. `loadCounter` is restored afterwards so the class is still treated as not-yet-loaded for the real defineClass.
- The orphan warning now names the real holder fields: proven by the assignment flow (`NEW <anon> -> <init> -> PUTFIELD`), or listed as candidates when the field is declared as the anonymous class's supertype/interface (a `Supplier` field holding a `Supplier` anonymous class used to be reported as "no host field holds them").
- A start-up readiness gate (`api` `HotswapPlugin.isHostReady()`, default `true`;
  `core` `StartupGate`, `-Dremold.agent.ready_timeout_ms`, default `0` = never release on time)
  defers batches until the host says it is ready, and `core` `BatchWatchdog` dumps all thread
  stacks **once per batch** with `[HOTSWAP-STALL]` when a batch exceeds
  `-Dremold.agent.batch_stall_ms` (default `15000`, floor `5000`). The watchdog now covers the
  debounce Class batch, the manual `triggerHotswap()` entry and `retransformLoaded`, through an
  `enter`/`exit` scoped API that allows at most one armed watchdog per batch.
- Weaving-generated synthetic classes can now feed their bytes into `remold.BytecodeSources`, so Nest scanning and `InitFix`'s constructor-visibility check can resolve them instead of failing closed with an unattributed warning.
- Mixin hot-swap now covers Mixin inner classes: a newly introduced anonymous class inside an `@Inject`
  handler no longer fails with `ClassNotFoundException` + `innerClass is null`, and editing only an
  already-loaded anonymous class body now redefines the generated `Target$Anonymous$<uuid>` class instead
  of ending in a silent `[DYNAMIC-REDEFINE] no-op`. `MixinBytecodeOverlay` installs an `IMixinService`
  proxy that serves bytes only for explicitly registered internal names (everything else falls through to
  the host provider) and evicts the upstream negative `ClassInfo` cache entries; `MixinInnerClassSupport`
  holds the attribution / nested-name pure functions; `MixinInnerClassHotSwap` regenerates the synthetic
  inner class by `InnerClassGenerator`'s stable unique name and redefines it (member-schema changes are
  rejected with a directed diagnostic instead of going around the layout gate); `handleInnerClassChange`
  routes an `Outer$N`-only edit back through the outer Mixin.
- Forge/NeoForge: `ModLauncherClassTrackerSpoof` temporarily removes target names from
  `ModLauncherClassTracker.loadedClasses` inside the `prepare()` window, restores them in `finally`, and
  fails closed when the tracker shape is unknown; `ForgeMixinConfigDiff` wires `onAssetsChanged` to the
  shared engine and `ForgeMixinHotSwap.scheduleTargets` re-weaves the affected targets.
- Real E2E: Forge 1.21.1 and NeoForge 21.1 dedicated servers now remove a Mixin name from
  `*.mixins.json` (hook stops), re-add it together with a brand-new Mixin (both hooks fire); evidence
  `tests/fabric-mixin-probe/forge-classinfo/evidence/forge-1.21.1-configdiff-after-fix.txt` and
  `tests/neoforge-mixin-probe/evidence/neoforge-21.1-configdiff-after-fix.txt`.
- `RemoldAgent`: split the single file debounce into two independent trailing-edge streams.
  Every `.class`/`.jar` event resets the **Class** quiet window (default lowered to **800 ms**);
  resource/config events use their own **250 ms** window and never postpone a Class batch.
  A pure `DebounceWindow` state machine (tested without sleeping) decides which stream is due;
  the scheduler only drains the due stream, and the manual `triggerHotswap()` entry still cancels
  the pending timer, clears both deadlines and force-drains all queues. Gradle DSL gained
  `assetShakeMs`; `README`/`README_EN` defaults updated.
- Gradle plugin: the Remold agent JVM arguments were injected only into the loader `client` run
  container, so `./gradlew runServer` (Fabric Loom / ForgeGradle / NeoForge ModDevGradle) started
  without `-javaagent`, and Fabric gametest runs (`clientGameTest` / `gameTest`) also missed it.
  Client, dedicated-server and gametest runs now receive the same arguments; auto-deploy also
  wires `runServer`, and the IDEA Loom sync covers `Minecraft_Server.xml` as well as
  `Minecraft_Client.xml` (Application-style `VM_PARAMETERS` and Gradle-style `vmOptions`).
- Release tooling: the Gradle plugin's JitPack publication published the bare version (`0.2.5`),
  while the packaged `remold.gradle(.kts)` shims and both project templates request
  `com.github.I-hope1.Remold:gradle-plugin:v<version>` - so offline / `mavenLocal` resolution of the
  plugin dangled. The publication now uses the `v`-prefixed version like every other module;
  `panama` gained the JitPack publication it was missing; and all consumer coordinates were
  canonicalized to the real repository account casing (`com.github.I-hope1.Remold`), which also
  removes a Gradle module-metadata group-case mismatch that only appears when a `.module` file is
  present.
- New `verifyReleaseCoordinates` task (`./gradlew publishToMavenLocal verifyReleaseCoordinates`):
  asserts that every publishable module has both coordinate families
  (`io.github.i-hope1.remold:*:<ver>` and `com.github.I-Hope1.Remold:*:v<ver>`) under
  `~/.m2/repository`, so a missing/mismatched publication fails the release instead of silently
  dangling in the templates.

### Notes
- Release-window verification: `./gradlew check` → BUILD SUCCESSFUL, **JUnit 597 tests,
  0 skipped, 0 failures**, including `verifyTestCount` and `verifyDocStatusMarkers`. The per-feature
  bullets above quote the full-suite snapshot recorded when each feature landed (480 → 527); 597 is
  the release-window figure and is not mixed with those snapshots.
- The evidence harness itself has known false-PASS / false-kill modes (exit code 127 recorded as
  "killed", cmd banner-only output recorded as PASS, Gradle `UP-TO-DATE` duplicate stdout hashes
  treated as new evidence). Every "PASS"/"killed" claim in this release presumes human review of the
  raw log; the tool-side fixes are tracked in `docs/status.md` §0.10.

## [0.2.5] - 2026-11-07

### Added
- Forge/NeoForge: newly declared `@Mixin` members (an `@Inject` handler method, a `@Unique` field)
  now hot-swap instead of demanding a restart. `plugin-forge` adds them to the `ClassInfo` that
  `MixinInfo.reloadMixin` reuses **before** `IMixinTransformer.reload` and rolls them back when the
  reload is rejected; every member lookup is resolved against the host ClassLoader's own
  `FieldNode`/`MethodNode` types, so a stale agent-side ASM copy cannot be matched.
- `core` `remold.reflect.ClassInfoMemberSync`: one shared member-sync engine. `plugin-mixin`
  (Fabric) and `plugin-forge` (Forge/NeoForge) are now thin adapters (type erasure + log prefix);
  the engine builds its rollback handle before mutating the member sets, so a half-way failure rolls
  back on **both** sides.
- Real-container E2E probes for the above: `tests/fabric-mixin-probe/forge-classinfo/` (Forge
  1.21.1) and `tests/neoforge-mixin-probe/` (NeoForge 21.1, Mixin config registered through the
  loader's native `META-INF/neoforge.mods.toml` `[[mixins]]` path). Both use a witness-based
  `Verdict` whose discriminating power is pinned by synthetic fixtures (red / green / not-attempted /
  self-contradictory / degraded / manual-registration).
- JPMS diagnostics: when the host ships Mixin as a named module without an opens edge, the sync path
  and the re-weave bridge report the exact `--add-opens org.spongepowered.mixin/...=ALL-UNNAMED`
  argument instead of a bare reflective failure.

### Changed
- `plugin-mixin` `MixinClassInfoSync` and `plugin-forge` `ForgeClassInfoSync` are thin adapters over
  the shared engine (previously two independent implementations of the same algorithm).

### Fixed
- Fabric `MixinClassInfoSync`: a failure between the field phase and the method phase leaked the
  already-added members (the rollback handle was built only after both phases succeeded). Covered by
  `ClassInfoMemberSyncTest#halfWayFailureRollsBackWhatWasAlreadyAdded`.
- Forge/NeoForge: adding an `@Inject` handler or a `@Unique` field to an existing `@Mixin` was
  rejected at reload (`Conform error ... please restart client`, then a
  `ClassInfo$Method.isUnique()` NPE) because the new members were invisible to the conform check.

### Notes
- Verified end to end on Forge 1.21.1 and NeoForge **21.1.265** (ModLauncher 11.0.5,
  sponge-mixin 0.16.4) plus the Fabric 1.21.1 probe. **NeoForge 26.x / FML 10+** (ModLauncher
  removed) and the NeoForge **production installer** launch path are explicitly unverified; a
  `--add-opens` requirement may apply there and must be re-tested (see `docs/status.md` §0.4 and
  `docs/neoforge/01-loader-and-classinfo-boundary-audit.md`).

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
