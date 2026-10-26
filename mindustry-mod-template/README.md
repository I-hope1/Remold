# 现代 Mindustry Java 模组模板 (集成 Remold 零侵入热重载)

本模板基于 Mindustry 官方最新 **[Anuken/MindustryJavaModTemplate](https://github.com/Anuken/MindustryJavaModTemplate)**（Kotlin DSL `build.gradle.kts` + Java 17 + 官方 Ivy 依赖源），并已预集成 **Remold 热重载自动化引擎**。

让模组开发者告别“每次微调代码都要关游戏、重新编译、重启读档”的低效循环，实现 **Ctrl + Shift + F9 秒级游戏内原位热更**！

---

## 30 秒快速上手体验

### 1. 配置游戏路径
打开 [`gradle.properties`](gradle.properties)（或在 [`build.gradle.kts`](build.gradle.kts) 中），将 `remold.jar` 改为你本地的 `Mindustry.jar` 路径：

```properties
# 填入你本地的 Mindustry.jar 路径（Steam 版或 GitHub 下载版均可）
remold.jar=D:/Mindustry/Mindustry.jar

# 保持开启：首次启动前自动打包模组并复制到系统 Mindustry mods 目录
remold.autoDeploy=true

# 启用 Mindustry / Arc 框架扩展插件（UI 局部熔断保护、@HotRebuild 原位热重构、GL 渲染探针）
remold.plugins=arc
```

> [!NOTE]
> 模版默认跨平台自动识别系统的游戏模组目录（Windows: `%APPDATA%/Mindustry/mods`、macOS / Linux 均自适应）。若使用自定义目录，配置 `remold.deployDir=D:/YourModsDir` 即可。

### 2. 启动游戏

#### 方式一：IntelliJ IDEA 原生一键启动（强烈推荐 ⭐）
1. 修改完 `gradle.properties` 中的游戏路径后，用 IDEA 打开项目或执行构建，Remold 插件会自动在 `.idea/runConfigurations/` 下就绪便携的 **`Run with Remold`** 运行配置（亦可手动执行 `./gradlew generateIdeaRunConfig` 强制刷新）；
2. 在 IDEA 顶部工具栏直接选择 **`Run with Remold`**，点击绿色运行（或调试）按钮即可！
3. 启动前会自动打包并部署模组，且直接支持 IDEA 原生断点调试，零 Gradle Daemon 开销。

#### 方式二：终端命令行启动
直接在终端运行：
```bash
./gradlew runRemold
```
* 插件将**自动编译打包**当前模组；
* **自动将模组部署**到系统的游戏 `mods/` 目录（全平台跨平台自动识别 Windows/macOS/Linux）；
* 纯净启动 Mindustry，进入游戏后即可看到模组加载弹出的提示对话框！

### 3. 体验秒级热重载（无需重启）
保持游戏开着别关，在 IntelliJ IDEA 中打开 [`src/example/ExampleJavaMod.java`](src/example/ExampleJavaMod.java)：
1. 修改其中的 `message` 文本，或者新增任意变量和方法；
2. 按快捷键 **`Ctrl + Shift + F9`**（重新编译当前类）；
3. **见证奇迹**：Remold 在后台 0.5 秒内自动把内存中的存量类替换为新字节码，游戏内即刻生效！

### 4. 体验 PNG 资源热更新（贴图也能秒换）

模板内置了一张贴图 [`assets/sprites/remold-badge.png`](assets/sprites/remold-badge.png)。Mindustry 会把它注册成图集区域 `example-remold-mod-remold-badge`，进游戏后它会常驻在 **屏幕右侧边缘（竖直居中）**（同时也会出现在启动对话框里），作为观察窗口。

1. 游戏保持运行，直接编辑这张 PNG 并保存（或复制一张备用配色覆盖它）：
   ```bash
   # Windows
   copy demo\remold-badge-amber.png assets\sprites\remold-badge.png
   # macOS / Linux
   cp demo/remold-badge-amber.png assets/sprites/remold-badge.png
   ```
2. 切回游戏：角标立刻变成新图案，**不重启、不重新进存档、不重新部署**。

刷新路径由 `plugin-arc` 的 `TextureReloader` 决定：

| 情况 | 行为 |
|:--|:--|
| 改图且**尺寸不变** | Tier 1 原位直刷（`Gl.texSubImage2D`），零材质重建，开销最小 —— **推荐** |
| **尺寸改变** | 走 Tier 2（清理图集 Drawable 缓存、重载 `Tex`/`Styles`、刷新地表与内容图标），但图集区域的 UV/尺寸不会跟着更新，画面可能被拉伸或溢出；想换尺寸请重新部署模组 |
| **新增**一张全新贴图 | 运行期注册成**独立纹理**（`TextureAtlas.addRegion`）：`sprites/` 按 `<模组内部名>-<文件名>` 命名、`sprites-override/` 用裸名；推不出归属模组时拒绝并提示重启 |

因此示例贴图固定为 64×64；`demo/` 下备的两张同尺寸配色（`remold-badge-amber.png`、`remold-badge-violet.png`）直接覆盖过去即可看到变化，用任何图片编辑器改颜色/图案同样有效（保持 64×64）。

新增贴图的边界：

- 区域名严格按「重启后 Mindustry 会用的名字」推导，避免热更与冷启行为分叉；归属模组靠**同目录已存在的兄弟贴图**反查（某个模组前缀能让兄弟贴图命中图集），查不出且启用了多个模组时**拒绝注册**并提示重启；
- 独立纹理不在打包图集页里，不参与合批；同名重复新增会重建纹理并释放旧的；
- 只有「运行时按名字查」的代码能看到它（`Core.atlas.find("...")`）；加载期就抓过引用的地方（内容图标、`Tex.*`、样式）不会自动跟随，那类改动仍需重启。

> [!NOTE]
> 贴图热更新依赖 `gradle.properties` 中的 `remold.plugins=arc`（默认已开启）。Remold 核心只负责把 `assets/` 下的文件变动事件分发出去，真正的显存写入与图集缓存维护在 `plugin-arc` 里；发布版不含这套机制，`./gradlew jar` 产物与普通模组完全一致。

> [!IMPORTANT]
> **agent 与 `plugin-arc` 必须是含资源热更新能力的同一份构建**，否则改 PNG 会「完全没有反应」——连一条日志都不会有（事件在 agent 侧就被丢掉了）。排查方法：
> ```bash
> # 1) agent 是否有资源分发入口（应输出 notifyAssetChanges / isAssetFile）
> javap -p -cp remold-agent.jar remold.RemoldAgent | findstr Asset
> # 2) 插件是否含贴图重载器（应输出 remold/arc/TextureReloader.class）
> jar tf plugin-arc-0.1.3.jar | findstr TextureReloader
> ```
> 两者缺一，说明用的是旧发布包。本地联调时先 `./gradlew :plugin-arc:publishToMavenLocal` 重新发布插件（模板会优先取 mavenLocal 里的同名版本），并让模板指向仓库内构建的 `core/build/libs/remold-agent.jar`（`gradle.properties` 的 `remold.agentJar`，或直接把 jar 放到本项目的 `libs/remold-agent.jar`）。

---

## 常见疑问解答 (FAQ)

### Q: 发布给玩家时需要装 Remold 吗？
**完全不需要！**  
Remold 纯粹是开发期挂在 JVM 上的探针（JavaAgent）。打包发布给普通玩家时，直接运行标准的：
```bash
./gradlew jar
```
生成的模组 jar 包干干净净，没有任何第三方侵入，原版任何 Java / 移动端环境直接正常加载。

### Q: 会引起 Mindustry 经典的类加载器冲突吗？
**绝对不会！**  
本模板严格遵守 `compileOnly` 声明，模组 Class 绝不混入 JVM 启动类路径。游戏启动后由原版 `ModClassLoader` 纯净加载，Remold 在 JVM Native 层面原地替换字节码，从原理上 100% 杜绝类加载器双重加载与强转崩溃。
