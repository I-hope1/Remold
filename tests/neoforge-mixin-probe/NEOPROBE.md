# NeoForge 21.1 · 原生 `[[mixins]]` 注册路径的 Mixin 热更 E2E 探针

本目录回答一个问题：在 **NeoForge 21.1.x 的 dev 运行时（ModDevGradle `runServer`，MC 1.21.1）** 上，
当 Mixin 配置由 **加载器自己**（`META-INF/neoforge.mods.toml` 的 `[[mixins]] config="neprobe.mixins.json"`）
注册时，把一份 Mixin 模板热更后，新增的 `@Inject` handler / 新增 `@Unique` 字段能不能真正落到目标类上，
以及原有成员会不会被悄悄丢掉。

它是 Forge 侧同名探针（`tests/fabric-mixin-probe/forge-classinfo/`）的 NeoForge 对照，但**注册路径不同**：
Forge 那份用 `MixinEnvironment.addConfiguration` 手动注册，本探针**禁止**手动注册（战役不变量 N3），
日志里出现 `MixinEnvironment::addConfiguration` 即判 `INVALID`。

## 1. 探针做了什么

| 阶段 | 真实动作 | 观测量（业务终局） |
|:--|:--|:--|
| 基线 `NP-BASE` | 无（Mixin v1 已由加载器织入） | 一次 `onReload()` 调用让模板 1 的 handler 恰好 +1、`@Unique uniqueA` 读到 123、`onReload()` 仍返回源码值 `T0` |
| 阶段 1 `NP-M1` | 模板 1 真实 `javac` 出**新增 `@Inject` handler** `npInject2`，文件监听 → 模板 reload → 目标重织入 | 一次调用让 `npInject2` 的计数 +1 |
| 阶段 2 `NP-M2` | 模板 2（同目标的第二份 Mixin）真实 `javac` 出**新增 `@Unique` 字段** `ciAdded = 456`，reload → 目标重织入 | 一次调用把 `ciAdded` 读回 456 |
| 保留 `NP-PRESERVE-1/2` | 上面两次热更之后 | 原有 handler 仍**每次调用恰好触发一次**；原有 `@Unique` 字段仍为 123 |

所有断言都落在**每次调用的增量**（delta）与存活实例上的**字段实读值**，不是累计计数器：若重织入把
织入成员悄悄丢掉，累计值仍然“看起来还在”，增量会立刻归零。目标类 `neprobe.ProbeTarget` 从不被修改，
变化只发生在 Mixin 模板上，因此观测到的差异只能来自重织入路径。

观测通道是目标类自己的 `static volatile` 字段（同 ClassLoader 直接读），不通过反射元数据，也不用
“日志出现某行”代替业务终局。

## 2. `Verdict.java`：把「发生了什么」与「是否生效」分开

探针只输出事实（`[NP] CHECK <id> PASS|FAIL <detail>`）；**归因**由 `Verdict.java` 判定，它额外要求
驱动记录每个阶段的 reload 事实：

```
[NP-DRIVER] phase=stage1 reload=success|rejected|weave-failed|not-attempted
[NP-DRIVER] phase=stage2 reload=...
```

- `not-attempted` / 缺失 ⇒ `INVALID`：**“什么都没发生”永远不许被读成红**。
- 某阶段 `rejected`/`weave-failed` 但对应成员检查 PASS，或某阶段 `success` 但对应检查 FAIL
  ⇒ `INVALID`（自相矛盾）。这条规则专门击杀“断言被改成永远 PASS”的弱探针。
- **降级信号** ⇒ `INVALID`（不变量 N2）：日志出现 `Failed to sync Mixin class metadata`、
  `has no new members`（预期应新增时）、`InaccessibleObjectException`、`does not implement HotswapPlugin`。
- **手动注册违规** ⇒ `INVALID`（不变量 N3）：`MixinEnvironment::addConfiguration` / `Mixins::addConfiguration`。
- 其余情况：所有成员检查通过 ⇒ `GREEN`，否则 `RED` 并列出失败检查 id。

退出码：`0` GREEN / `1` RED / `2` INVALID。

## 3. 验收命令

```bash
bash tests/neoforge-mixin-probe/verify-evidence.sh
# Windows cmd 里 `bash` 可能解析到 WSL（拿不到 Windows JDK），用不依赖 PATH 的入口：
tests\neoforge-mixin-probe\verify-evidence.cmd
```

四段互相独立的检查（全部离线、确定性）：

1. **布局与静态不变量**：文件齐全、驱动脚本语法、stage 源码确实新增了对应成员、基线模板里**没有**
   stage 标记、`neoforge.mods.toml` 含 `[[mixins]]` 且 `config="neprobe.mixins.json"`、
   **探针树里没有任何源码调用 `addConfiguration` / `MixinEnvironment`**（N3 的静态守卫）。
2. **判定力（反空转）**：`fixtures/` 的 6 个合成样本分别要求
   `RED{NP-M1,NP-M2}` / `GREEN` / `INVALID(PRECOND-RELOAD)` / `INVALID(SELF-CONTRADICTION)` /
   `INVALID(DEGRADE=…)` / `INVALID(MANUAL-MIXIN-REGISTRATION)`。断言一旦不再区分“同步了”与“没同步”，
   这一步立刻变红。
3. **证据**：`evidence/*.txt` 按文件名契约复核（`*-baseline-red*` 必须 RED 且两阶段都被观测为 reload 尝试、
   且用的必须是 **sync 被禁用**的 plugin jar；`*-after-fix*` 必须 GREEN、两阶段 reload 成功、且日志里
   **正向**出现两行 `Mixin class-info sync: … addedFields/addedMethods=`），并检查 jar sha256 溯源、
   NeoForge 版本行、以及“无违规行 / 无降级行”。
4. **可选真机复跑**：`NP_E2E=1 bash verify-evidence.sh`（会重新拉起真实 NeoForge dev 服务端）。

## 4. 复跑真机 E2E

```bash
# 绿腿（当前 plugin-forge）
bash tests/neoforge-mixin-probe/run-neoforge.sh
# 红基线（ClassInfo 成员同步被禁用的一份 plugin jar 副本）
NP_RED_BASELINE=1 bash tests/neoforge-mixin-probe/run-neoforge.sh
```

参数（全部可覆盖）：

| 变量 | 缺省 | 作用 |
|:--|:--|:--|
| `NP_JBR` | `C:/Users/ASUS/.jdks/JBR-21` | JDK 21（NeoForge 21.1 需要 Java 21 运行） |
| `NP_AGENT_JAR` | `<repo>/core/build/libs/remold-agent.jar` | `-javaagent` 的 agent |
| `NP_PLUGIN_JAR` | `plugin-forge/build/libs/` 里 **mtime 最新**的 `remold-plugin-forge-*.jar`（排除 `-sources`） | 插件 jar |
| `NP_MDK_DIR` | `<repo>/build/neoforge-probe/mdk` | ModDevGradle 工作区（每次由 `mdk/` + `mdk-overlay/` 重新物化） |
| `NP_SKIP_BUILD` | `0` | `1` = 不重建 agent/plugin（`JAVA_HOME="$NP_JBR" ./gradlew --offline -q :core:shadowJar :plugin-forge:jar`） |
| `NP_OFFLINE` | `0` | `1` = 给 Gradle 加 `--offline`（**首次必须联网**，见 §6） |
| `NP_ADD_OPENS` | `0` | `1` = 给 dev 运行加 `--add-opens org.spongepowered.mixin/org.spongepowered.asm.mixin.transformer=ALL-UNNAMED`（本机实测**不需要**，见 §5） |
| `NP_RED_BASELINE` | `0` | `1` = 先把 `plugin-forge/src/remold/forge/ForgeClassInfoSync.java` 拷进 scratch，把 `sync(...)` 首行换成 `return SyncToken.EMPTY;`，单文件 `javac` 后覆盖进 plugin jar 副本（`remold-plugin-forge-nosync.jar`），用该副本跑 |

驱动实际做的事：物化 MDK →（红基线时先造 nosync jar）→ 编译探针 mod → `javap` 断言基线 class 标记 →
`sleep` 喂 stdin 启动 `runServer` → 等 `Done (` → 落 `probe.start` → 等基线织入 → 逐阶段 `javac` +
`javap` 断言 stage 标记 + 落触发文件 → 等测量 → 记录阶段归因 → 写证据并跑 `Verdict`。

**stage 标记断言**：`javap -v -p` 断言 stage 1 的 class 含 `npInject2`、stage 2 含 `ciAdded`，且基线
class **不含**这些标记。目的：Gradle up-to-date / stale compile 静默把上一阶段的字节码喂进服务端，
会让某阶段“因为错误的原因变绿”。

**触发文件**（`NP_ADD` 双写兜底）：探针轮询 `$CTRL/<stage>.go` 与 `$CTRL/run/<stage>.go`；驱动同时写到
`$CTRL/`、`$CTRL/run/`、`<MDK>/run/`、`<MDK>/run/run/` 四处，避免“项目根 vs 服务端工作目录”的口径差异。

一次完整运行约 2–3 分钟（NeoForm 产物已缓存时）。

## 5. 本机已核验事实（不是推测）

| 事实 | 证据 |
|:--|:--|
| JDK：`C:/Users/ASUS/.jdks/JBR-21`（`openjdk 21.0.9`）；JBR **不带** `jar`/`javap`（只有 `javac`/`jps`），驱动会回退到 PATH 里的完整 JDK | 运行时探测；证据头 `jbr=…` |
| Gradle：`GRADLE_USER_HOME=D:\data\.gradle`，本机缓存可用版本为 **8.12**（ModDevGradle 2.x 不支持 Gradle 9.x，根工程 wrapper 的 9.7.1 不能用于此 MDK） | `build/neoforge-probe/mdk` 的 wrapper 被固定为 8.12 |
| 插件：`net.neoforged.moddev` **2.0.148**（Gradle Plugin Portal 可解析），`neoForge { version = '21.1.265' }`，`runServer` 可用 | `mdk/build.gradle`；`gradle tasks` 输出 |
| 运行期栈：NeoForge 21.1.265 / MC 1.21.1 / `sponge-mixin-0.16.4+mixin.0.8.7` / ModLauncher 11.0.5 | 证据头 `neoforge=… mixin_jar=…`；`SpongePowered MIXIN Subsystem Version=0.8.7 … Service=ModLauncher Env=SERVER` |
| `[[mixins]]` 由加载器注册生效：目标类真的被织入（`NP-BASE` 读到 `uniqueA=123`、`onReload()` 返回 `T0`），且探针源码里没有任何注册调用、日志里没有 `addConfiguration` 行 | `evidence/neoforge-21.1-after-fix.txt`；`verify-evidence.sh` 第 1/3 段 |
| **不需要 `--add-opens`**：深反射成功（`Mixin class-info sync: … addedFields/addedMethods=` 行出现），全程无 `InaccessibleObjectException`，两次运行证据头都是 `add_opens=0` | 两份证据；`neoforge-21.1-after-fix.txt` 的 sync 行 |
| dev 运行的模块路径 `-p` 只含 bootstraplauncher / securejarhandler / asm 家族 / JarJarFileSystems；**sponge-mixin 在 legacy classpath 清单里**（`build/moddev/serverLegacyClasspath.txt`），不在命名模块路径上 | `mdk/build/moddev/serverRunVmArgs.txt`、`serverLegacyClasspath.txt`（scratch，未提交） |
| 红基线失败形态：stage 1 → `Conform error … ClassInfo$Method.isUnique() … method is null`（reload 被拒）；stage 2 → 模板 reload 被接受、但目标重织入失败并**回退源码字节码**，已织入成员被一起丢掉（`NP-PRESERVE-2` 变红） | `evidence/neoforge-21.1-baseline-red.txt` |

**关于 `--add-opens` 的边界（推断，不是观测）**：sponge-mixin 的 `module-info` 只把
`org.spongepowered.asm.mixin.transformer` opens 给 `com.google.gson`/`gson`，按 JPMS 规则，若这些类对
agent（unnamed module）以**命名模块**身份出现，`setAccessible` 就应抛 `InaccessibleObjectException`。
本 dev 启动实测没抛，且 sponge-mixin 不在 `-p` 上——两者一致的**推断**是：dev 运行把 sponge-mixin 放在
legacy classpath，agent 侧的深反射因此可用。日志里的 `MC-BOOTSTRAP/org.spongepowered.mixin@…` 是
SecureJarHandler 的模块标签，**不能**据此反推“命名模块已对 agent 开放”。要把这条推断升级为观测，还差
一个实验：另起一个诊断 agent，在服务端内打印 `ClassInfo.class.getModule().isNamed()` 与
`getLayer()`。**生产 installer 启动**（`unix_args.txt`，模块路径可能与 dev 不同）仍属未验证。

## 6. 若失败：确切失败点（本轮建环境时真实踩到的）

按出现顺序，附处置：

1. **Gradle 版本**：ModDevGradle 2.x 与 Gradle 9.x 不兼容（根工程 wrapper 是 9.7.1）。驱动的 MDK
   wrapper 固定到本机已缓存的 **8.12**，不要去改根工程 wrapper。
2. **首次必须联网**：NeoForm 产物（`D:\data\.gradle\caches\neoformruntime`）+ MC 服务端/客户端 jar +
   **787 MiB 资源**都要下载并反编译 MC（首次 `compileJava` 约 **8 分钟**）。`NP_OFFLINE=1` 在缓存未齐时
   会以 `No cached version of io.netty:netty-transport-native-epoll:4.1.97.Final available for offline mode`
   失败——先跑一次联网，之后可离线。
3. **JBR 缺工具**：`JBR-21/bin` 没有 `jar`/`javap`；红基线重打包与 stage 标记断言都回退到 PATH 上的完整 JDK。
4. **资源输出目录**：`neoforge.mods.toml` 在 `build/resources/main`（`processResources`），不在
   `build/classes/java/main`；只 javac 不拷资源会让加载器看不到 mod（或探针 mod 不启动）。
5. **注入器描述符**：目标方法 `String onReload()` 非 void，handler 必须用
   `CallbackInfoReturnable<String>`；用 `CallbackInfo` 会被 Mixin 拒绝：
   `Invalid descriptor … CallbackInfoReturnable is required!`，目标类织入失败、探针 `HALT rc=4`。
6. **归因优先级**：桥可以在模板 reload 被接受（`Mixin X reloaded`）之后仍重织入失败并回退源码字节码。
   此时归因必须是 `weave-failed` 而不是 `success`——`classify()` 里目标级失败行要先于模板级接受行判断，
   否则红基线会被判成 `INVALID(SELF-CONTRADICTION)`。
7. **`@Unique` 初始值依赖 InitFix**：阶段 2 的 `ciAdded = 456` 是在**已存在实例**上读到的，靠的是
   Redefine 后 InitFix 的实例字段补丁（日志 `Applying instance field init patch … fields=1`）。关掉
   InitFix 时该断言会变成读 0，属于另一个机制，不要混判。

## 7. 已记录证据

| 文件 | 含义 |
|:--|:--|
| [`evidence/neoforge-21.1-baseline-red.txt`](evidence/neoforge-21.1-baseline-red.txt) | ClassInfo 成员同步**被禁用**（`sync()` 直接返回 `SyncToken.EMPTY`）时的真实运行：`RED reasons=NP-M1,NP-M2,NP-PRESERVE-2`，`reload={stage1=rejected, stage2=weave-failed}`。这是本战役要修的缺陷在 NeoForge 上的真实形态。 |
| [`evidence/neoforge-21.1-after-fix.txt`](evidence/neoforge-21.1-after-fix.txt) | 当前 `plugin-forge` 的真实运行：`GREEN`，两阶段 reload 均被接受，且日志正向出现 `addedMethods=1`（stage 1）/`addedFields=1`（stage 2）的同步行。 |

两份证据的 `probe_tree_sha256` 相同（同一份探针树产生），`agent_jar` sha256 相同，`plugin_jar` 只差
nosync 桩替换的那个 class。

## 8. 明确的边界（不要读成“NeoForge 端到端全绿”）

- 只覆盖 **dev 模式 `runServer`**（ModDevGradle classpath mod）与 **NeoForge 21.1.x**（ModLauncher 11 +
  FML 4 + sponge-mixin 0.16.4）。生产 installer 启动（`unix_args.txt`、模块路径口径不同）、FML 10+
  代际（NeoForge 26.x，无 ModLauncher）、真实客户端、生产 refmap 都不在覆盖范围。
- 只覆盖**新增**成员（新 `@Inject` handler、新 `@Unique` 字段）；删除/改名、修改既有字段初值、
  方法签名变更不在断言范围。
- 只验证 `-Dremold.plugins=forge` 这条激活路径（`plugin-forge` 的 jar manifest 只声明
  `remold.forge.ForgeHotswapPlugin`）；`NeoForgeHotswapPlugin`（名称 `neoforge`）这条别名路径未被本探针激活，
  两者共用 `initShared`，但“别名可被 `-Dremold.plugins=neoforge` 选中”本轮未验证。
- 探针不注入合成观测代码：观察通道是目标类的静态计数器 + Mixin handler 自身写入。
