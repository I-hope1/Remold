# 现代 Minecraft Fabric 模组模板 (集成 Remold 零重启热重载)

本模板基于官方 **Fabric Example Mod**（Minecraft 1.21.1 + Java 21 + Fabric Loom 1.17），并已深度预集成 **Remold 热重载自动化引擎**。

让 Minecraft 模组开发者彻底告别“改一行代码就得重启客户端、忍受 1~2 分钟漫长黑屏加载”的折磨，实现 **`Ctrl + Shift + F9` 秒级原位热更**！

---

## 30 秒快速上手体验

### 1. 启动 Minecraft 开发客户端
在 IntelliJ IDEA 中：
1. 打开本项目后等待 Gradle 同步完成；
2. 直接在 IDEA 右上角运行配置中选择 **`Run with Remold`**（或 Loom 自动就绪的 `Minecraft Client`），点击绿色运行按钮即可启动游戏客户端！
   - 也可以在终端直接执行：
     ```bash
     ./gradlew runClient
     ```
3. Remold 脚本插件已自动为 Loom 的 `client` 运行配置注入热重载参数（`-XX:+IgnoreUnrecognizedVMOptions -XX:+AllowEnhancedClassRedefinition` 与 `-javaagent` 探针）。

   这两个参数在不同 JDK 上的效果**并不相同**，请按你的运行环境理解：

   | 运行环境 | 方法体热更 | 新增字段 / 新增方法 / 改签名 |
   | :--- | :-: | :--- |
   | **带 DCEVM 的 JBR 21**（推荐） | ✅ | ✅ 由 InitFix 为存量实例补初值 |
   | 标准 JDK 21+（Microsoft OpenJDK、Temurin、Oracle JDK…） | ✅ | ❌ 明确拒绝该类，需重启 |

   `AllowEnhancedClassRedefinition` 是 DCEVM 系专有参数。标准 JDK 单独加它会直接启动失败，靠前面的 `IgnoreUnrecognizedVMOptions` 才能被**静默忽略**——参数被忽略不等于能力存在。Remold 的判定依据是运行时厂商标识加上该 flag 的实际状态，而不是命令行文本（标准 JDK 也会把它原样回显到 `getInputArguments()`）。命中拒绝时会打印 `REJECTED: <类名> has field/method structure changes but this JVM has no enhanced class redefinition`，同批次里只改方法体的类照常热更。

   **用哪个 JVM 跑**：`runClient` 走 Loom 的 JavaExec，用的是**运行 Gradle 的那个 JVM**；`runRemold` / `startRemold` / `generateRemoldScript` 生成的启动器同样默认取这个 JVM（`java.home`），**不再读 `JAVA_HOME` / PATH 里的 `java`**。所以只要把 IDEA 的 *Settings → Build Tools → Gradle → Gradle JVM* 设成 `项目 SDK: jbrsdk-21`（带 DCEVM），构建和游戏就都跑在这个 JBR 上。要用另一个 JBR，显式指定 `remold { jbrPath = "..." }` 或 `-Premold.jbr=...`。

### 2. 体验秒级原位热重载（无需退出世界）
进入游戏中的单人测试世界，输入指令获取演示物品：
```text
/give @s example_mod:remold_wand
```
拿着魔法棒在空中右键，屏幕会弹出绿色提示文字：`[Remold] Hello from Remold Fabric Hot Reload!`。
同时控制台会打印 `[Remold] mixin hook alive: use() 第 N 次调用`——这行来自 `RemoldWandItemMixin`，说明 Mixin 钩子正处于生效状态，可以拿它对照后面的热更行为。

**现在见证热重载奇迹**：
1. **修改代码**：保持游戏客户端开着别关，在 IDEA 中打开 [`src/main/java/com/example/item/RemoldWandItem.java`](src/main/java/com/example/item/RemoldWandItem.java)；
2. 将 `message` 变量修改为任意内容（比如 `"🎉 Remold 热重载成功！无需重启游戏！"`）；
3. **甚至可以新增字段**：例如新增一个 `public int clickCount = 0;` 并让右键时递增显示，Remold 的 **InitFix** 引擎会自动为游戏世界中已拿在手里的存量实例初始化该字段（此步骤需要带 DCEVM 的 JBR 21；标准 JDK 上会被明确拒绝并提示重启，见上文表格）；
4. 按快捷键 **`Ctrl + Shift + F9`**（重新编译当前类）；
5. 切换回游戏窗口，再次右键魔法棒——**新逻辑和新增字段立刻生效！** 控制台里的 `mixin hook alive` 计数会继续递增，说明热更后 Mixin 钩子没有被抹掉。
6. **再试一次改 Mixin 本身**：打开 [`src/main/java/com/example/mixin/RemoldWandItemMixin.java`](src/main/java/com/example/mixin/RemoldWandItemMixin.java)，把 `println` 的文案改掉，再按 `Ctrl + Shift + F9`。控制台会依次出现 `Mixin ... 已重新解析，受影响目标: [...]` 和 `目标类 ... 已用新 Mixin 重新织入并重定义`，下次右键就是新文案——这一步走的是「改 Mixin 类 → 重织入目标类」链路。

---

## 核心技术优势（相比普通 IDEA 热更）

| 场景 | 普通 JDWP 热更 (IDEA 默认) | Remold 增强热重载 |
| :--- | :--- | :--- |
| **新增声明字段** | ❌ 直接报错 `UnsupportedOperationException` | ✅ **InitFix 引擎原位初始化**存量与静态实例 |
| **修改 / 新增 Lambda** | ❌ 编号漂移导致 `NoSuchMethodError` 崩溃 | ✅ **LambdaAligner 智能对齐** CallSite 调用点 |
| **匿名内部类拓扑变更** | ❌ 类名篡夺或编号漂移 | ✅ **AnonClassAligner 4 阶拓扑对齐** |
| **Mixin 字节码增强** | ❌ Redefine 绕过 ClassLoader，注入的钩子被整段抹掉 | ✅ **重织入桥在 redefine 前把源码字节码重新交给 Mixin 织入** |

> 上表「Remold 增强热重载」一列的前提是**增强重定义能力可用**（带 DCEVM 的 JBR）。在标准 JDK 上，后三行（Lambda、匿名内部类、Mixin 重织入）仍可用，因为它们只改方法体；第一行「新增声明字段」会被明确拒绝并提示重启。

### Mixin 热更兼容（由 `plugin-mixin` 提供）

这部分能力**不在模组代码里**，由 Remold 官方插件 `plugin-mixin`（仓库根目录 `plugin-mixin/`）在 JVM 启动时挂载：

| 组件 | 职责 |
| :--- | :--- |
| `remold.mixin.MixinHotswapPlugin` | 实现 `HotswapPlugin`，`onAgentInit` 阶段把重织入桥注册进 `RemoldAgent` |
| `remold.mixin.MixinBytecodeBridge` | 实现 `api` 的 `BytecodeTransformerBridge`，按「Mixin 类 / 普通类」分流 |
| `remold.mixin.MixinReWeaver` | 从 `MixinEnvironment.getActiveTransformer()` 取运行期织入器，把源码级字节码重新交给 Mixin 处理 |
| `remold.mixin.MixinHotSwap` | 处理「改的是 Mixin 类本身」：刷新 Mixin 内部状态、找出受影响的目标类、调度目标类重织入 |

Remold 在**类对齐之后、布局安全门与 `redefineClasses` 之前**调用这条桥链，所以布局门和 JVM 看到的都是「重新织入后的完整结构」，`@Unique` 字段与 handler 方法不会被当成「被删除的成员」。

**自动挂载**：`remold.gradle(.kts)` 检测到 Fabric Loom 插件，或工程里声明了 `net.fabricmc` / `org.spongepowered` 依赖时，会自动把 `mixin` 加进 `-Dremold.plugins`，并依次从 `plugin-mixin/build/libs`、Gradle 缓存、JitPack、GitHub Release 解析插件 jar。模组侧零配置、零依赖。

> 本地开发 Remold 本体时，插件 jar 可能还没发布。此时用 `remold { pluginJar("../<remold 仓库>/plugin-mixin/build/libs/remold-plugin-mixin-0.1.3.jar") }` 显式指定，或先执行 `./gradlew :plugin-mixin:jar` 并把它拷到 `~/.gradle/caches/remold/`。

### 改 Mixin 类本身（目标类重织入）

Mixin 类不会被 JVM 当普通类加载，它只是被读成字节码模板。所以「改了 Mixin 的注入代码」这件事，JVM 里既没有可 redefine 的类，Remold 的常规批次也不会去重织入它的目标类。`MixinHotSwap` 补的就是这段：

1. **识别**：常量池里出现 `@Mixin` 描述符的类按 Mixin 处理（便宜的前置过滤，非 Mixin 类不受影响）。
2. **刷新 Mixin 内部缓存**：`IMixinTransformer.reload(name, 新 ClassNode)`。Mixin 用它重建 `MixinInfo` 状态，并校验结构契约（目标类 / 接口 / 内部类 / 优先级）。校验不过会抛 `MixinReloadException`，此处明确提示「需重启」。
3. **找目标**：`reload` 返回受影响的目标类名，从中筛出**已被 JVM 加载**的。
4. **重织入 + 重定义**：从类路径读目标类的**原始**字节码（不是 JVM 里那份），交给 `RemoldAgent.redefineDynamic` —— 它会再走一遍重织入桥、布局安全门、`redefineClasses` 与 InitFix，因此目标类上叠加的其它 Mixin 也会一并织入。

重定义目标类跑在独立的单线程任务里；`redefineDynamic` 内部会抢 Remold 的 `HOTSWAP_LOCK`，所以即使当前批次还在跑，它也会等到批次结束再动手，不会插进批次中间改缓存。

| 改动 | 标准 JDK | DCEVM JBR |
| :--- | :-: | :-: |
| 改注入方法的**方法体**（如改 `println` 内容） | ✅ | ✅ |
| 改 `@At`、`method =`，且不增删目标类的方法 | ✅ | ✅ |
| **新增** `@Inject` / 新增 handler 方法 | ❌ 目标类结构变化被拒 | ✅ `[DCEVM] Structure change detected` |
| 改 `@At` 导致目标类方法增删 | ❌ | ✅ |
| `@Accessor` / `@Invoker`（只加方法） | ❌ | ✅ |
| 新增接口 / 改父类 | ❌ | ❌ Remold 的继承体系门直接拒（`hierarchyChanged`），DCEVM 也救不了 |
| 改 `targets =` / `@Mixin` 的接口、内部类、优先级 | ❌ | ❌ Mixin 的 reload 校验拒绝，需重启 |

也就是说：**用了 DCEVM 的 JBR 之后，只剩下两类真限制** —— Mixin 自己的结构契约（目标类列表 / 接口 / 内部类 / 优先级），以及 Remold 的继承体系门。方法层面的增删都不再是障碍。

**怎么确认 `@At` 真的换了**：看控制台里三行的先后。`RemoldWandItem.use()` 方法体首尾各打了一行标记，钩子打印夹在中间哪个位置，就是注入点的实际位置：

```
[src] use() begin      ← @At("HEAD") 时，钩子这行在这之前
[Remold] mixin hook alive: ...
[src] use() end        ← @At("TAIL") 时，钩子这行在这之后
```

注意别用「钩子打印 vs 聊天栏消息」的先后判断：钩子在 Server 线程执行，聊天消息由 Render 线程异步渲染，两者顺序跟注入点无关。

还有一点与热更机制无关：**已经执行过的逻辑不会回溯**。比如 `TitleScreen.init()` 早就跑完了，新代码要等下次打开标题界面才看得到。

**行为边界**（按现状如实记录）：

- 只对「被 Mixin 命中的目标类」生效。未命中时 Mixin 原样返回入参数组，Remold 按数组同一性判定「未改动」，不产生额外拷贝。
- 目标类若尚未加载，不做任何事——它下次加载时会直接用新的 Mixin 定义织入。
- 运行时动态生成、类路径里找不到 `.class` 的目标类会被跳过（读不到原始字节码，无法安全重织）。
- Mixin 类自身在 JVM 里不存在，Remold 的批次会把它当「新类」尝试注入，日志里可能多出一条 `[INJECTED]` 或定义失败提示，不影响功能。
- 关闭自动挂载：`-Premold.autoMixin=false`（插件 jar 仍可手动通过 `remold { plugins("mixin") }` 引入）。
- 跨 ClassLoader 的宿主（Mixin 与插件不在同一个 ClassLoader，例如部分 NeoForge/Forge 启动链）目前只能退化为不重织入，日志会提示 `无法获取 Mixin 织入器`。

---

## 常见疑问解答 (FAQ)

### Q: 发布模组给普通玩家需要带 Remold 吗？
**完全不需要！**  
Remold 纯粹是开发期挂载在 JVM 上的探针。打包发布给普通玩家时，直接运行标准构建命令：
```bash
./gradlew build
```
产出的 `build/libs/example_mod-1.0.0.jar` 是纯净的标准 Fabric 模组，普通玩家在官方客户端或各第三方启动器中均可正常使用。

### Q: 我改了 Mixin 里的注入代码，`Ctrl + Shift + F9` 之后为什么没变化？
先看控制台。三条日志分别对应三种情况：

- `Mixin xxx 已重新解析，受影响目标: [...]` + `目标类 xxx 已用新 Mixin 重新织入并重定义` → 已生效。若画面没变，多半是「已经执行过的逻辑不会回溯」（比如 `init()` 早就跑完了）。
- `属于结构性改动（目标类/接口/内部类/优先级变化），无法热更，请重启客户端` → Mixin 自己的 reload 校验拒绝了，只能重启。
- `目标类 xxx 重织入后未能重定义` → 目标类的方法/字段结构变了，需要带 DCEVM 的 JBR。

另外，改 `@At` 注入点后，新的注入要等那段代码**下次执行**才看得到。

### Q: 改了代码按 `Ctrl + Shift + F9` 完全没反应，连桥的日志都没有？
先确认**游戏里跑的是哪份代码**。本模板用 `remold.deployDir=./run/mods` 把构建产物部署到 `run/mods`，游戏加载的是**那个 jar**，不是 `build/classes/java/main`（后者只供 Remold 热更时读盘比对）。排查三步：

1. 看启动日志有没有 `[Remold] Mixin 重织入桥已注册（plugin-mixin）`。**没有**说明插件没挂上（见下一条）。
2. 看 `build/libs/example_mod-1.0.0.jar` 的大小：正常几十 KB、里面应有 `com/example/...`；只剩 `META-INF/MANIFEST.MF` 就是空 jar。
3. 看 `run/mods` 里有没有历史遗留的 `example_mod-*.jar`（尤其 `-dev.jar`）。Fabric 会加载它，新 jar 反而被忽略。

空 jar 的已知成因：**Fabric Loom 1.8.x 搭配 Gradle 9 会静默产出空 jar**（`remapJar` 任务成功、不报错、产物只有清单）。模板已把 Loom 固定在 `1.17.21`，请勿降回 1.8.x。

> Loom 版本与 JVM 是绑死的：`1.18.0+` 的插件变体要求 **JVM 25**，跑在 JBR 21 上会直接报 `Dependency requires at least JVM runtime version 25`；`1.19.x` 还额外要求 Gradle ≥ 9.8。本模板要在 **JBR 21（DCEVM）** 上跑，所以停在 `1.17.21`（要求 JVM 21 + Gradle ≥ 9.5）。升级 Loom 前先确认这两条。

### Q: 启动日志里没有 `（plugin-mixin）` 那一行？
说明 `plugin-mixin` 的 jar 没解析到。按顺序检查：

1. 构建输出里有没有 `[Remold] 未找到插件 remold-plugin-mixin 的 Jar 包` 的警告。
2. 本地开发 Remold 本体时，先在仓库根目录执行 `./gradlew :plugin-mixin:jar`，再用 `remold { pluginJar("../<remold 仓库>/plugin-mixin/build/libs/remold-plugin-mixin-0.1.3.jar") }` 指过去。
3. 正式使用时，需要该版本已发布带 `plugin-mixin` 的 Release / JitPack 制品。

### Q: 不挂 Remold 直接 `runClient`，会不会报错？
不会。模板代码不引用任何 Remold 类；Mixin 支持完全由 Agent 侧的插件提供，没挂 Agent 时模组行为与原生 Fabric 模组一致。
