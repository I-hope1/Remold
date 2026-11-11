# Remold

[![Release](https://img.shields.io/github/v/release/I-hope1/Remold?color=007ec6&logo=github&label=version)](https://github.com/I-hope1/Remold/releases)
[![JitPack](https://jitpack.io/v/I-hope1/Remold.svg)](https://jitpack.io/#I-hope1/Remold)
[![CI](https://img.shields.io/github/actions/workflow/status/I-hope1/Remold/ci.yml?branch=main&label=CI)](https://github.com/I-hope1/Remold/actions)
[![License](https://img.shields.io/badge/license-Apache--2.0-orange.svg)](LICENSE)

简体中文 | [English](README_EN.md) · [Gradle 插件文档](gradle-plugin/README.md) · [实现状态与边界 (status.md)](docs/status.md) · [Mixin 子系统文档](docs/mixin/01-invariants-and-classloader-authority.md)

Remold 是一个用于 JVM 热重载的 Java Agent。它监听编译输出目录（如 `build/classes`），在 `.class` 或资源文件变化后将新字节码原位替换进正在运行的 JVM，无需重启游戏或重进存档。目前支持 **Mindustry（Arc）** 与 **Minecraft（Fabric / Quilt / Forge / NeoForge）** 模组开发，仓库自带 [`mindustry-mod-template/`](mindustry-mod-template/README.md) 和 [`minecraft-fabric-template/`](minecraft-fabric-template/README.md) 两个工程模板。

## 演示

**Mindustry：修改 PNG 贴图原位刷新**（不重启、不重进存档）

![Mindustry PNG 贴图热更新演示](docs/media/mindustry-png-hot-reload.gif)

**Minecraft (Fabric)：修改 `message0` 字符串值 → `Ctrl+Shift+F9` → 游戏内右键立即显示新文案**

![Minecraft 热重载演示](docs/media/minecraft-hot-reload.gif)

**Minecraft (Fabric)：把 Mixin 注入点从 `@At("TAIL")` 改成 `@At("HEAD")`，目标类被重新织入**

![Minecraft Mixin 重织入演示](docs/media/minecraft-mixin-reweave.gif)

---

## 怎么跑起来

运行环境需要 **JetBrains Runtime (JBR 17/21/25+)** 并开启 `-XX:+AllowEnhancedClassRedefinition`（普通 JDK 下只能改方法体，结构改动会被自动跳过；Java 24+ 需加 `--enable-native-access=ALL-UNNAMED`）。

想先试效果可以直接跑仓库自带的演示模块（需预装 JDK 21+，首次运行会下载依赖）：

```bash
git clone https://github.com/I-hope1/Remold && cd Remold
./gradlew :example:runRemold
```

### 接入自己的项目

在 `build.gradle` 中引入脚本插件（注意 URL 必须锁具体版本的 tag，不要写 `main`，保证构建插件与 agent 版本一致）：

```groovy
def remoldRef = (findProperty('remold.version') ?: '0.2.7')
apply from: "https://raw.githubusercontent.com/I-hope1/Remold/v${remoldRef}/remold.gradle"

remold {
    // 默认读取 gradle.properties 中的 remold.version，自动下载并缓存对应版本的 remold-agent.jar
    // jbrPath = "/path/to/jbr" // 默认从环境变量 JBR_HOME 或系统属性 jbr.home 读取
    // fileShakeMs = 800        // .class 变动防抖窗口（毫秒，默认 800）
    // assetShakeMs = 250       // 资源/配置独立防抖窗口（毫秒，默认 250）
}
```

如果是 Kotlin DSL（`build.gradle.kts`），由于 `apply(from = ...)` 下 IDE 无法为脚本扩展生成类型安全访问器，建议通过 `buildscript` 引入二进制插件 `com.github.I-hope1.Remold:gradle-plugin:v<version>` 并 `apply(plugin = "io.github.i-hope1.remold")`，或者改用 `extra["remold.mainClass"] = "..."` 配置。离线环境可用 `-Premold.pluginJar=<path>` 指定本地插件 jar，详见 [`gradle-plugin/README.md`](gradle-plugin/README.md)。

插件会根据工程类型自动挂载扩展：
- **Fabric / Quilt 工程**：检测到 Loom 时自动挂载 `mixin` 与 `fabric` 插件（自动注入 `fabric.classPathGroups`，并将 `run/mods` 下同 modId 的残留构建 jar 改名为 `.remold-disabled` 隔离）；
- **Forge / NeoForge 工程**：检测到 ForgeGradle 或 ModDevGradle 时自动挂载 `forge` 插件（穿透 ModLauncher 重织入 Mixin，并在 `@SubscribeEvent` 增删时刷新 `EVENT_BUS`）；
- **Mindustry 工程**：在 `gradle.properties` 里加一行 `remold.plugins=arc` 即可开启 UI 布局比对与贴图/音频/着色器/bundle 热重载。

### 日常开发工作流（推荐使用 IDEA 增量编译）

不建议在前台跑着 `./gradlew runRemold` 的同时又用 Gradle 编译，这会让两个进程争抢 Gradle Daemon，每次编译多等十几秒。推荐做法：

1. 打开项目或执行 `./gradlew generateIdeaRunConfig`，IDEA 右上角会自动生成 **`Run with Remold`** 运行配置（Loom / Forge / NeoForge 工程也会自动把 `-javaagent` 参数同步进 `Minecraft Client`、`Minecraft Server` 与 `gameTest` 配置里）。
2. 在 IDEA 的 *Settings -> Build, Execution, Deployment -> Build Tools -> Gradle* 中，把 **`Build and run using`** 和 **`Run tests using`** 改为 **`IntelliJ IDEA`**。
3. 用 `Run with Remold`（或 `Minecraft Client`）启动游戏；改完代码直接按 **`Ctrl + Shift + F9`**（macOS 为 `Cmd + Shift + F9`），IDEA 内置 `javac` 约 0.1~0.2 秒写出单个 `.class`，等防抖窗口过后直接生效。

如果不通过 Gradle 插件、想手动在 IDEA 或 CLI 里配 VM 参数，核心参数只有两个（如需开 JDWP 远程调试，地址务必绑在 `127.0.0.1:5005`，不要绑 `0.0.0.0`）：

```text
-XX:+AllowEnhancedClassRedefinition
-javaagent:/path/to/remold-agent.jar=build/classes/java/main;build/classes/kotlin/main
```

> **不再需要 `--add-exports` / `--add-opens`。** agent 在 `premain` 的第一行就调用
> `Instrumentation.redefineModule`，把 `jdk.internal.misc` / `jdk.internal.reflect` /
> `jdk.internal.loader` / `jdk.internal.access` / `jdk.internal.vm` / `java.lang{,.invoke,.reflect}` /
> `sun.nio.ch` 这 9 个包对**所有 unnamed module**（agent 自身、系统加载器、线程上下文加载器）一次性
> export + open，效果等价于手写一串 `--add-exports …=ALL-UNNAMED` 与 `--add-opens …=ALL-UNNAMED`；
> 启动日志会打一行 `[Agent] opened 9 JDK-internal package(s) …`。旧参数继续传也不会冲突。
> Java 24+ 上如果启用了受限本地方法（JVMTI 原生库），仍需 `--enable-native-access=ALL-UNNAMED`
> ——这是 JVM 对"允许调用受限本地方法"的独立授权，`redefineModule` 无法替代。

### 系统属性开关

以下 JVM 系统属性可按需覆盖（Gradle 插件会自动透传对应的 DSL 项）：

| 系统属性 | 默认值 | 作用 |
|:--|:--|:--|
| `-Dremold.agent.two_phase` | `true` | 两阶段 Schema-First 重定义；`false` 回退单阶段 |
| `-Dremold.agent.init_drift.parents` | 空（关闭） | L1 双括号初值重放的父类白名单（逗号分隔） |
| `-Dremold.agent.init_drift.apply_holders` | 空（关闭） | A 档 Kotlin `object` / 顶层 `val` 持有者白名单（逗号分隔） |
| `-Dremold.agent.ready_timeout_ms` | `0` | 启动就绪门闩超时放行毫秒数；`0` 表示未就绪时不自动放行 |
| `-Dremold.agent.batch_stall_ms` | `15000` | 批次卡死看门狗阈值（下限 `5000`）；超时打印一次 `[HOTSWAP-STALL]` 全线程栈 |
| `-Dremold.agent.file_shake_ms` | `800` | Class/jar 变动防抖窗口（Gradle DSL：`fileShakeMs`） |
| `-Dremold.agent.asset_shake_ms` | `250` | 资源/配置变动防抖窗口，独立于 Class 窗口（Gradle DSL：`assetShakeMs`） |

其余安全门开关（`layout_gate`、`anon_align`、`anon_strict`、`local_class_guard`、`offset_strict` 等）见 [`AGENTS.md`](AGENTS.md) 与 [`docs/status.md`](docs/status.md)。

---

## 核心机制

只用 JBR 加 IDE 自带的 HotSwap 跑模组开发时，常会遇到新字段未初始化、Lambda 序号顺移串位、Mixin 钩子被冲掉等问题。Remold 接在文件监听和 `Instrumentation.redefineClasses` 之间做处理：

| 你遇到的 | Remold 的处理 |
|:--|:--|
| 改完代码必须重启才生效 | 监听 `build/classes`，编译后原位替换字节码与资源 |
| 加字段后老对象里是 `0` / `null` | 扫描堆上存活实例，按构造器切片补初值 |
| 热更瞬间新代码读到还没补上的新字段 | 先提交「新结构 + 旧方法体」，补完实例再切新方法体 |
| 增删 Lambda / 匿名类后事件或按钮串位 | 按结构指纹重映射 `lambda$*` 与 `$1` / `$2` 槽位 |
| 删掉一行监听 Lambda，主循环每帧报错 | 生成孤儿兜底桩，在框架分发点自动触发注销 |
| Mixin 钩子被冲掉，或改 Mixin 报错 | 重定义前重新织入目标类，同步 `ClassInfo` 并热更内部类 |
| 改 Mixin 匿名类的捕获变量后客户端偶发 NPE 崩服 | 已加载合成匿名类的成员 schema 变化一律拒绝，并让宿主目标类**一起停**（事务共退）；把捕获列表改回去后同一会话内自愈放行 |
| 删字段、改类型后怕把内存改坏 | 检查存活实例、批次外读者与物理偏移，不安全则拒绝并提示重启 |

具体实现上：
- **两阶段重定义与 `InitFix`**：当类存在新增字段时，阶段 A 先提交新字段布局与旧方法体（构造器保留新体），随后用内置的 JVMTI 原生库扫出存活实例，把从 `<init>` 逆向切出的初值通过隐藏伴生类以条件 CAS 写回（默认只补零值，不覆盖运行期改过的状态；开启白名单后也支持双括号初始化与 Kotlin `apply {}` 静态持有者的旧常量条件重放），阶段 C 再提交新方法体，从而关闭 `redefineClasses` 返回到补丁完成之间的并发读穿窗口。
- **Lambda 与匿名类对齐（`LambdaAligner` / `AnonClassAligner`）**：源码前部增删 Lambda 或匿名类会让 `javac` 生成的编号整体顺移，捕获状态（static/实例）变化还会触发 `IncompatibleClassChangeError`。Remold 解析 `invokedynamic` 引导参数、指令指纹与方法体相似度，将顺移的方法和匿名类名重映射回已绑定实例所在的旧槽位。
- **跨加载器 Mixin 闭环**：维护源码基线与生效基线，在普通类重定义前交回 Mixin 重织入；Mixin 模板自身增删 `@Inject` / `@Unique`、修改 `*.mixins.json`、新增 Mixin 类，或在 `@Inject` 方法内新增/编辑匿名内部类（`Target$Anonymous$<uuid>`）时，先通过 `ClassInfoMemberSync` 同步元数据并把合成类字节回灌给 `BytecodeSources`，再级联重定义目标类。
- **合成匿名类的单出口闸门与事务共退（`redefineSyntheticSafely` / `BytecodeTransformerBridge.Abort`）**：Mixin 生成的匿名类把捕获变量放在合成字段里，改捕获列表等于同时改字段表与 `<init>` 描述符。真机实测表明：在 JBR/DCEVM 上对**已加载**合成类做成员 schema 变化，即使堆上该类的实例数为 0，也会让 VM 重排实例并冲掉无关类的字段（实测一次抹掉 230/244 个 `LootContextPredicate.combinedCondition`）。因此这类改动一律拒绝并连坐宿主（宿主本轮不 `redefineClasses`），拒绝记账按**候选字节签名**去重、还原捕获列表后同会话自动清账放行。详见 [`docs/mixin/05-synthetic-inner-gate-and-abort.md`](docs/mixin/05-synthetic-inner-gate-and-abort.md)。
- **安全门（`LayoutGate` / `RemovedMemberReaders` / `SuperclassDriftGate`）**：删字段或方法时，按 JVMS 5.4.3.2 / 5.4.6 扫描批次外常量池读者；父类增删字段时，通过抛弃式类加载器预测子类物理偏移是否影响 `VarHandle`，并在重定义后自动驱逐 Gson / Jackson / Arc Json 的反射缓存。

### 文档索引

| 主题 | 文档 |
|:--|:--|
| Mixin 热更子系统（8 篇：类加载器权威 / 准入与覆盖层 / 重织入与成员对齐 / 结构契约 / 合成类闸门 / 走查 / 运行期诊断 / 决策台账） | [`docs/mixin/01..08`](docs/mixin/01-invariants-and-classloader-authority.md) |
| 匿名类拓扑对齐（准入、层级交错流水线、置信梯队、布局门、Lambda 捕获桥） | [`docs/topology/01..09`](docs/topology/01-invariants-and-remapping.md) |
| InitFix 安全门、闭包与台账、两阶段设计 | [`docs/initfix/01..13`](docs/initfix/01-safety-gate.md) |
| 物理偏移与第三方缓存驱逐 | [`docs/offset/01`](docs/offset/01-offset-and-reflection-cache.md) |
| 逐项实现状态、验收证据、已知风险（唯一真相源） | [`docs/status.md`](docs/status.md) |

---

## 注解与扩展 API

只用自动热重载时，业务代码不需要引入任何 Remold 依赖。如果要在代码里控制存量字段重置、写热更回调或接入 `HotswapBatchReport`，可以 `compileOnly` 引入 `:api`（将版本号替换为当前使用的 tag）：

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { compileOnly 'com.github.I-hope1.Remold:api:v0.2.7' }
```

```java
import remold.annotation.RemoldReinit;
import remold.annotation.OnReload;

public class PlayerService {
    // 默认：新增字段只在存量实例中该字段还是零值（0/null）时才补初值
    private String nickname = "DefaultName";

    // OVERWRITE：当这个字段的初值表达式被修改时，强制用 CAS 覆写存量实例里的旧值
    @RemoldReinit(mode = RemoldReinit.Mode.OVERWRITE)
    private int scoreVersion = 2;

    // 实例方法会在堆上每个存活实例上各调一次（没有存活实例则不调）；静态方法每轮调一次
    @OnReload
    private void onReload() {
        System.out.println("Reloaded: " + nickname);
    }
}
```

需要注意两个语义细节：
- `@OnReload` 是对**本类存活实例**的回调，不是“模组收到热更通知”；写在 Mod 主类上看起来像全局通知，只是因为主类刚好是单例。如果该类本轮重定义被安全门拒了，回调不会执行。
- `@RemoldReinit` 只有在**初值表达式本身发生变化**时才会重置存量值，只改普通方法体不会把字段打回初值；但如果初值调用了外部方法或读其他类的静态字段（无法静态证明返回值没变），则每轮都会重置。

---

## 和其他方案的区别

| 方案 | 它做什么 | 它不做什么 |
|:--|:--|:--|
| **裸 JBR (DCEVM) + MixinAgent** | 在 VM 层替换类结构与方法体，重织入基础 Mixin 修改 | 不给存量实例补新字段初值；不对齐顺移的 `lambda$*` 与 `$1`/`$2`；Mixin 新增 `@Inject` / `@Unique` 常因旧 `ClassInfo` 未同步被拒 |
| **HotswapAgent** | 面向 Spring / Hibernate / Tomcat 等企业容器做通用类与插件重载 | 未针对 SpongePowered Mixin 双基线重织入、ModLauncher 跨类加载器隔离、构造器逆向切片补初值及 Lambda 槽位重映射做专门适配 |
| **Hotwire**（仅 Fabric，源码未公开，参考 [`v0.6.0` 文档](https://github.com/dearIrene/hotwire-public)） | 面向玩家在游戏内动态开关/卸载模组（依赖 AI 辅助为上百个常用模组编写的清理逻辑）；普通 JVM 下拆出 `Sidecar` + `WeakMap` 模拟新成员并在方法入口插桩 `Tripwire`；JBR 下走原生重定义 | 据其公开文档，未做存量对象初值逆向切片回填与编译期 Lambda/匿名类序号对齐；状态不一致抛错时靠异常拦截弹窗并“软重启整个模组”（重跑初始化）恢复 |
| **NeoHotModInjector**（仅 NeoForge 客户端，参考[公开仓库](https://github.com/yiyuyan/NeoHotModInjector)） | 运行期将游戏类访问权限改为 `public`，经跳板转发（`TRAMPOLINE`）或特定版本 DLL 改内存标志（`NATIVE`）往客户端注入 `.jar` | 不支持模组卸载与注册表变更；不给已存在的实例补新字段初值（目标类若已实例化需在进世界前注入） |
| **Remold**（开源，支持 Fabric / Quilt / Forge / NeoForge / Mindustry） | 在 JBR 下原位替换字节码，两阶段重定义 + 构造器切片给老对象补初值，对齐漂移的 Lambda 与匿名类，跨加载器重织入 Mixin | 不做玩家端整包模组卸载，不插桩业务方法或用 `WeakMap` 模拟字段；结构变更依赖 JBR，遇到无法安全回填或存在存活读者的破坏性改动会拒绝重定义并提示重启 |

---

## 已知限制

哪些改动还是得重启，完整台账在 [`docs/status.md`](docs/status.md) 里有持续审计，主要边界如下：

1. **复杂字段初值切片**：构造器里带分支（`if`/`switch`）、循环、`try-catch` 或外部可变 Builder 的字段初始化不会自动回填，会被安全门拒绝并记入 `FieldLedger`。
2. **删改成员与继承变更**：修改父类/接口、修改字段类型、或者删字段/删方法时堆里还有存活实例（或批次外还有读者），会拒绝重定义。
3. **局部类深层嵌套与常量折叠**：具名局部类里面再套匿名类（如 `Foo$1Helper$1`）在特定位移下仍走原名直通；已经被 `javac` 内联或被 JIT C2 常量折叠的 `static final` 改值无法单靠写内存逆转。
4. **孤儿 Lambda 逃逸**：删掉的 Lambda 只有在注册了 `OrphanBreakerPredicate` 的框架分发点才能抛错触发注销，普通业务线程自己持有的 `CallSite` 只能降级返回零值。
5. **外部 JDWP 通道与微小窗口**：直接在 IDE 里点 "Reload Changed Classes"（走外部 JDWP）或调 `redefineDynamic` 仍是单阶段重定义，存在读穿窗口；JVMTI 堆快照到补丁落盘之间也有微小的并发新建对象窗口。
6. **NeoForge 26.x（FML 10+）与生产 installer**：NeoForge 21.1 已实测跑通，但移除了 ModLauncher 的 26.x 尚未适配；生产 installer 启动路径下的模块 `--add-opens` 需求未测。
7. **`plugin` 与 `core` 必须同版本发布**：插件与内核版本错位会退化为 `NoSuchMethodError`（有逐插件 `catch` 与定向日志，但不受支持）。
8. **Mixin 合成匿名类的成员结构变化一律拒绝**：`<Target>$Anonymous$<hash>` 的捕获字段/`<init>` 描述符一旦变化，无论堆上有无实例都会被拒绝，并连坐宿主目标类本轮不重定义（避免"宿主换新 `<init>`、合成类还是旧的"分裂态）。同一份候选不会自动重试；把捕获列表还原到与已加载类一致、只改方法体即可在同一会话内继续热更，否则需重启或改用 `@RemoldReinit` / lambda 承载状态。`ForgeMixinHotSwap` 尚未接入内部类热更闭环。
9. **真实客户端 GUI 验收仍为手动**：自动化容器探针目前覆盖专用服务端（Fabric KnotServer / Forge `runServer` / NeoForge `runServer`），模板场景 1–8 的客户端 GUI 验收清单见 [`docs/acceptance/01-manual-checklist.md`](docs/acceptance/01-manual-checklist.md)。

---

## 构建与开源协议

构建和测试需要 JDK 21+（CI 矩阵见 `.github/workflows/ci.yml`，包含 `javac` 8/11/17/21/25 编译夹具、JBR 21 `liveDcevmTest`、JBR 17/21 `p05Probe` 以及文档标记扫描 `verifyDocStatusMarkers`）：

```bash
./gradlew check       # 日常快检：JUnit 矩阵 + InitFix Oracle 断言 + 文档与测试数守卫
./gradlew fullCheck   # 完整检验：check + Mixin / JDWP 端到端探针
```

本项目采用分模块的多许可证策略：
- **核心引擎、Panama 扩展、API 与构建插件**（`:api`、`:core`、`:panama`、`:gradle-plugin`、`:example`、`:tests`）：采用 **[Apache License 2.0](LICENSE)** 协议开源；核心引擎 `:core` 不引用、不分发、也不持有任何 Mindustry / Arc 类名或字段名。
- **Mindustry / Arc 框架插件**（`:plugin-arc`）：采用 **[GPL-3.0](plugin-arc/LICENSE)** 协议开源（编译期 `compileOnly` 链接上游 GPL-3.0 的 Mindustry）。
- **Mixin 与 MC 加载器插件**（`:plugin-mixin`、`:plugin-forge`、`:plugin-fabric`）：采用 **[GPL-3.0](plugin-mixin/LICENSE)** 协议开源（对 SpongePowered Mixin、Fabric、ModLauncher 等上游依赖均为 `compileOnly`，不随产物打包分发）。
- **模组工程模板**（`mindustry-mod-template`、`minecraft-fabric-template`）：采用 **[MIT License](mindustry-mod-template/LICENSE)** 协议开源。
