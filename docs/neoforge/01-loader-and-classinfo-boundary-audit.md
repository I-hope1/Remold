# NeoForge 链路与 ClassInfo 同步边界审计（Batch A · 只读）

> **文档类型**：审计记录（一次性读数/证据；实现状态以 [`docs/status.md`](../status.md) 为准）

> 本文件是**审计事实记录**，不是设计规格：它回答「当前代码在两代 NeoForge 上分别成立到什么程度」，
> 并给出可证伪的实验项。实现状态与验收证据统一登记在 [`docs/status.md`](../status.md)。

## 0. 方法与事实来源

只读审计，不启动任何 Minecraft 进程（运行期实证在 Batch B）。所有外部框架事实都取自可复核的产物：

| 来源 | 用途 |
|:--|:--|
| `neoforge-21.1.265-installer.jar` → `version.json`（`maven.neoforged.net`） | NeoForge 21.1 的库清单、JVM 模块参数、main class |
| `neoforge-26.1.2.126-installer.jar` → `version.json` | 新一代 NeoForge 的同一组事实（代际边界） |
| `net.fabricmc:sponge-mixin:0.16.4+mixin.0.8.7` jar（`maven.fabricmc.net`） | `module-info` 的 exports/opens、`META-INF/services`、反射面（`javap`） |
| `FancyModLoader` 源码 branch `1.21.1`：`loader/.../moddiscovery/ModFileParser.java`、`loader/.../loading/mixin/DeferredMixinConfigRegistration.java` | `[[mixins]]` 的解析与注册路径 |
| `neoforge-21.1.265-universal.jar` | `net.neoforged.neoforge.common.NeoForge` 等类是否存在 |
| 本仓库 `plugin-forge/src/`、`core/src/remold/RemoldAgent.java` | 代码面假设（行号见下表） |

## 1. 结论摘要

1. **「Forge 与 NeoForge 共用同一代码路径」只能算部分成立**：21.1 与 Forge 1.21.1 共用 ModLauncher
   （NeoForge 21.1.265 用 `cpw.mods:modlauncher:11.0.5` + `bootstraplauncher:2.0.2`），但 Mixin 供应商不同
   （`net.fabricmc:sponge-mixin:0.16.4+mixin.0.8.7`，Forge 侧是上游 `org.spongepowered:mixin`）。
   反射面已逐项 `javap` 核对**全部存在**，但存在 **JPMS 深反射** 与 `reloadMixin` 行为两项必须在真机证实的假设。
2. **`[[mixins]]` 原生注册在 NeoForge 21.1 受 FML 支持**：`ModFileParser.getMixinConfigs` 读取
   `[[mixins]].config`（可选 `requiredMods`），`DeferredMixinConfigRegistration` 在启动早期调用
   `Mixins.addConfiguration` 完成注册。因此 Batch B 的 N3（不走手动 `MixinEnvironment.addConfiguration`）可行。
3. **代际断裂是产物级事实，不是推测**：NeoForge 26.1.2.126 的库清单里 `cpw.mods` 为 **0 个**，main class 是
   `net.neoforged.fml.startup.Client`，`net.neoforged.fancymodloader:loader:11.0.15` —— 即 FML 10+ 独立加载器，
   不再有 ModLauncher。**当前 `plugin-forge` 的所有跨 ClassLoader 逻辑都是按 ModLauncher 语义写的，不能外推到该代际。**
4. **JPMS 深反射是待证实的高风险点**：sponge-mixin 的 `module-info` 只有一个 `opens`，且
   `org.spongepowered.asm.mixin.transformer` 只 opens 给 `com.google.gson`/`gson`，**既未 export 也未 opens 给
   ALL-UNNAMED**。21.1 的启动参数 `-p`（module path）只包含 bootstraplauncher / securejarhandler / ASM / JarJarFileSystems，
   sponge-mixin 出现在 libraries（classpath 侧），**推测**它落在 unnamed module（`setAccessible` 可用）；
   但这是推测，Batch B 必须把 `InaccessibleObjectException` 作为显式探针断言。
5. **ClassInfo 同步可以收敛为单一实现**：把「按宿主对象自身 ClassLoader 反射解析成员」的状态机下沉到
   `core` 的反射引擎、两侧只留薄适配，成本约 200 行引擎 + 80 行适配 + 1 个新增判定力用例（详见 §5）。

## 2. 表 A：`plugin-forge` 代码面 × 两代 NeoForge 真值表

判定口径：**兼容**＝代码假设与目标运行时的产物事实一致；**需改**＝必须改代码/参数；**未验证**＝产物层无矛盾但缺运行期证据。

| # | 位置 | 机制 / 假设 | NeoForge 21.1（ModLauncher 11.0.5 + sponge-mixin 0.16.4） | NeoForge 26.x（FML 10+，无 ModLauncher） |
|:-:|:--|:--|:--|:--|
| A1 | `ModLauncherMixinBridge.java:36-65` | 对非 Mixin 类调用 `transformClassBytes(String,String,byte[])` 重织入 | 兼容（方法在 `IMixinTransformer` 上存在，`javap` 已核） | **未验证**：无 ModLauncher 桥，本桥是否被调用取决于 Remold 的 retransform 路径；跨 ClassLoader 定位逻辑不适用 |
| A2 | `ModLauncherMixinBridge.java:68-120` | 依次用 preferred loader / `inst.getAllLoadedClasses()` 找 `MixinEnvironment`，再 `getCurrentEnvironment().getActiveTransformer()` | 兼容（`MixinEnvironment` 与 `getActiveTransformer` 在 sponge-mixin 里存在，`javap` 已核）；active transformer 是否已由 `MixinServiceModLauncher` 设置属**未验证** | **未验证 / 大概率不适用**：FML 10 的 mixin 服务与模块层不同 |
| A3 | `ModLauncherMixinBridge.java:106` | 反射取 `transformClassBytes` 的 `Method` 并 `setAccessible(true)` | 若 `IMixinTransformer` 所在包未 export/opens（§1.4），此处即可抛 `InaccessibleObjectException` → 见 B1 | 未验证 |
| A4 | `ForgeMixinHotSwap.java:110-156` | reload 前同步 ClassInfo；按 `transformer.getClass().getClassLoader()` 取 ASM 建 `ClassNode`；`reload(String, ClassNode)` | 兼容（`MixinTransformer.reload` 存在）；`MixinInfo.reloadMixin` 复用旧 `ClassInfo` 的行为**未验证**（同源 0.8.7 merge，推测一致） | 未验证 |
| A5 | `ForgeMixinHotSwap.java:158-186` | `findReload` 用 `getMethods()` 遍历找 `reload(String, ClassNode)` | 兼容 | 未验证 |
| A6 | `ForgeMixinHotSwap.java:188-233` | 目标类重织入走 `RemoldAgent.redefineDynamic`，原始字节来自磁盘/资源 | 与加载器无关，兼容 | 兼容（但触发源不同） |
| A7 | `ForgeClassInfoSync.java:96-155` | 读 `transformer.processor`（`MixinTransformer.processor: MixinProcessor`） | **未验证（关键）**：`MixinTransformer` 字段名在 sponge-mixin 里仍为 `processor`（`javap` 已核），但深反射需 opens（§1.4） | 未验证 |
| A8 | `ForgeClassInfoSync.java:174-193` | 读 `MixinProcessor.configs` → 每个 `MixinConfig.mixins` → `MixinInfo.getClassName/getClassInfo` | `javap` 面存在；若 FML 用 `DeferredMixinConfigRegistration` 在启动后清空/重建配置列表，需在真机上看 `configs` 是否包含目标 Mixin | 未验证 |
| A9 | `ForgeClassInfoSync.java:196-220` | `ClassInfo.findField(FieldNode)` + `ClassInfo$Field(ClassInfo,FieldNode,boolean)` | `javap` 已核存在（含 `public` 三参构造器）；深反射需 opens | 未验证 |
| A10 | `ForgeClassInfoSync.java:222-241` | `ClassInfo.findMethod(MethodNode,int)` + `addMethod(MethodNode)`（包私有） | `javap` 已核存在（`void addMethod(MethodNode)` 包私有）；深反射需 opens | 未验证 |
| A11 | `ForgeClassInfoSync.java:265-297` | 按「运行时对象实际 Class」匹配形参类型（跨 ClassLoader 关键设计） | 兼容，且是对 JPMS/双份 ASM 的正确应对 | 兼容 |
| A12 | `ForgeRuntimeDetector.java:13-16` | `isNeoForge` 用 `net.neoforged.bus.api.IEventBus` / `net.neoforged.fml.ModList` | 兼容：21.1 库含 `net.neoforged:bus:8.0.5` 与 `fancymodloader:loader:4.0.45`（含 `ModList`） | **未验证**：FML 10 是否仍暴露同名 `ModList` 需另核（不影响 Mixin 同步主路径） |
| A13 | `ForgeRuntimeDetector.java:19-22` | `isForge` 用 `net.minecraftforge.*` | 兼容（NeoForge 下应返回 false） | 未验证 |
| A14 | `ForgeEventBusRefresher.java:115,125` | `net.neoforged.neoforge.common.NeoForge` / `net.minecraftforge.common.MinecraftForge` | 兼容：`NeoForge.class` 在 `neoforge-21.1.265-universal.jar` 中存在 | **未验证** |
| A15 | `RemoldAgent.java:1197-1310` + `plugin-forge/src/META-INF/services/remold.HotswapPlugin` | 插件激活：SPI 文件含 `ForgeHotswapPlugin` 与 `NeoForgeHotswapPlugin`；`-Dremold.plugins=forge|neoforge` 过滤；`initShared` 幂等 | 兼容（`neoforge` 名称可直接使用；只给 `forge` 也能工作） | 兼容（与加载器无关） |
| A16 | `plugin-forge/src/remold/forge/` 全局 | 是否硬编码 `META-INF/mods.toml` / manifest `MixinConfigs` / `LaunchTarget` 名 | **无**：代码不读取任何 mod 清单属性（`grep` 结论），注册路径完全由加载器负责 → N3 只在**探针**侧要求 | 无 |

## 3. 表 B：运行时事实对照（产物级）

| 事实 | NeoForge 21.1.265 | NeoForge 26.1.2.126 |
|:--|:--|:--|
| main class | `cpw.mods.bootstraplauncher.BootstrapLauncher` | `net.neoforged.fml.startup.Client` |
| 加载器库 | `cpw.mods:modlauncher:11.0.5`、`cpw.mods:securejarhandler:3.0.8`、`cpw.mods:bootstraplauncher:2.0.2` | **无 `cpw.mods:*`** |
| FML | `net.neoforged.fancymodloader:loader:4.0.45` | `net.neoforged.fancymodloader:loader:11.0.15` |
| Mixin | `net.fabricmc:sponge-mixin:0.16.4+mixin.0.8.7` | `net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7` |
| 模块路径（`-p`）内容 | bootstraplauncher / securejarhandler / asm 家族 / JarJarFileSystems（**不含 sponge-mixin**） | 需单独核对（本轮未查） |
| sponge-mixin 的 JPMS 状态 | 有 `module-info`（模块名 `org.spongepowered.mixin`），`transformer` 包只 opens 给 gson | 同族，版本 0.17.4 |
| `[[mixins]]`（`neoforge.mods.toml`） | 支持（`ModFileParser.getMixinConfigs` → `DeferredMixinConfigRegistration`） | 未核对（FML 11 分支） |
| 反射面：`MixinTransformer.reload` / `processor` / `ClassInfo.findField/findMethod/addMethod` / `ClassInfo$Field(ClassInfo,FieldNode,boolean)` / `MixinEnvironment.getActiveTransformer` | 全部存在（`javap` 已核） | 未核对（同源 0.8.7 merge，推测一致） |

## 4. 断点清单（按阻塞级别）

| id | 级别 | 断点 | 判定方法（可证伪） | 处置预案 |
|:--|:--|:--|:--|:--|
| B1 | 高 | **JPMS 深反射**：若 sponge-mixin 以 named module 加载，`setAccessible` 会被 `InaccessibleObjectException` 拒绝（transformer 包未 opens 给 ALL-UNNAMED） | NeoForge 探针日志里搜 `InaccessibleObjectException`；或 `Module.isNamed()` / `getLayer()` 打印 | 1) 产品侧把该异常转成明确诊断（提示加 `--add-opens org.spongepowered.mixin/org.spongepowered.asm.mixin.transformer=ALL-UNNAMED`）；2) dev 运行参数补该 `--add-opens`；3) 文档写明这是 JVM 参数要求而非代码 bug |
| B2 | 中 | `getCurrentEnvironment().getActiveTransformer()` 在 sponge-mixin + ModLauncher 下是否返回 `MixinTransformer`（而不是 `null` 或包装类型） | 探针打印 transformer 实际类名（`[Forge/NeoForge] Successfully acquired Mixin transformer ...` 行） | 回退到 `MixinEnvironment.getDefaultEnvironment()`（现有代码已做） |
| B3 | 中 | `MixinInfo.reloadMixin` 是否仍复用 reload 前的 `ClassInfo`（Fabric 上已验证） | 红基线的失败形态：应先出现 `Conform error ...` / `ClassInfo$Method.isUnique() ... method is null`，而非别的异常 | 若行为不同，按真实失败形态调整同步时机（仍必须在 reload 前） |
| B4 | 中 | `MixinProcessor.configs` 是否包含探针的 Mixin（FML 的 `DeferredMixinConfigRegistration` 在启动早期注册） | 探针断言同步行出现（`Mixin class-info sync: <mixin> addedFields/addedMethods`） | 若缺失，检查是否需要在 `MixinProcessor` 上触发一次配置选择（Fabric 侧有 `ensureConfigsSelected` 的等价机制，Forge 侧暂缺） |
| B5 | 低 | `ForgeEventBusRefresher` 的 NeoForge 类名（`net.neoforged.neoforge.common.NeoForge`） | 21.1 产物已含该类（1 个 class，`unzip -l` 已核）；运行期是否可用属附带项 | 与 Mixin 同步解耦，不在本轮主路径 |
| B6 | 信息 | 手动 `MixinEnvironment.addConfiguration` 在 NeoForge 上仍然可用（探针不允许只走它） | 探针必须走 `[[mixins]]`；若同时在日志里看到手动注册行 → 记为违规 | 判定器显式禁止 |
| B7 | 信息 | 26.x 代际无 ModLauncher：现桥接逻辑不适用 | 产物级已证（§3）；运行期未验证 | 文档明示「未覆盖」，不修不猜 |

## 5. `ClassInfo` 同步的 SSOT 收敛评估

**目标形态**：`core` 新增反射引擎 `remold.reflect.ClassInfoMemberSync`（`Object transformer/classNode` 入参，
不引用任何 `org.spongepowered.*` 或 ASM 类型），提供：

- `Token sync(Object transformer, String mixinClassName, Object classNode)`
- `void rollback(Token token)`
- `boolean isEmpty(Token token)`（或 `Token.isEmpty()`）

两侧只保留**薄适配**：`plugin-mixin` 的 `MixinClassInfoSync` 与 `plugin-forge` 的 `ForgeClassInfoSync`
各自把类型擦成 `Object` 后委托，日志前缀（`[Mixin]` / `[Forge/NeoForge]`）由适配层保留。

**为什么要统一（不是重复代码洁癖）**：两版算法目前已经出现**语义分叉**——`ForgeClassInfoSync` 先构造
`Token` 再逐项填充，因此半程失败可回滚；`MixinClassInfoSync` 在两次补入都成功后才构造 `Token`，
`catch` 里 `rollback(EMPTY)` 是空操作，**半程失败会残留已补入成员**。收敛时统一采用 Forge 语义，
并给 Fabric 侧补一条「半程失败回滚」判定力用例（当前没有该用例）。

**成本/风险**：

| 项 | 估计 | 说明 |
|:--|:--|:--|
| 引擎抽取 | ~200 行（净移动，无新逻辑） | 全部是反射与集合操作，无并发状态 |
| 适配层 | ~80 行（两侧合计） | 签名擦除 + 日志前缀 |
| 测试影响 | 既有 3+4 用例保持；+1 半程回滚用例 | `MixinClassInfoSyncTest` 目前通过反射调私有方法 `addMissingFields/addMissingMethods`，适配层保留同名委托即可零改动 |
| 架构成本 | 中低 | core 会多一类「外部元数据反射协议」；无 Mixin/ASM 编译依赖，与 `remold.Reflect` 同类定位 |
| 备选否决 | — | （a）`plugin-forge` 依赖 `plugin-mixin`：破坏插件独立分发，否决；（b）新建模块：为 200 行引入构建/发布负担，否决 |

**判定**：可行，建议在 Batch C 落地，并把「半程失败回滚」补成两侧共用语义。

## 6. Batch B 施工要求（由本审计导出）

1. **探针必须做正向确认，而不只是「没有报错」**：断言日志出现
   `Mixin class-info sync: <mixin> addedMethods=1`（stage 1）与 `addedFields=1`（stage 2），
   且**不出现** `Failed to sync Mixin class metadata`（降级）、`InaccessibleObjectException`、`not-attempted`。
2. **注册路径走 `neoforge.mods.toml` 的 `[[mixins]]`**；日志里若出现 `MixinEnvironment::addConfiguration`
   的手动注册行，判定器直接判违规（B6）。
3. **JVM 参数**：dev 运行若需要 `--add-opens org.spongepowered.mixin/org.spongepowered.asm.mixin.transformer=ALL-UNNAMED`
   才能深反射，必须把它写进探针脚本与产品文档（B1），并在证据里记录「加参数前/后」的差异。
4. **代际**：只跑 NeoForge 21.1.x；26.x 在 `docs/status.md` 明示未覆盖（B7）。

## 7. 验收

`docs/neoforge/verify-recon.sh` 断言本文件包含 §2 表 A 的全部 16 行、§3 的两代事实、§4 的 B1–B7 断点，
以及 §5 的收敛结论；缺一项即失败。
