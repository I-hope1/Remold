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
```

> [!NOTE]
> 模版默认跨平台自动识别系统的游戏模组目录（Windows: `%APPDATA%/Mindustry/mods`、macOS / Linux 均自适应）。若使用自定义目录，配置 `remold.deployDir=D:/YourModsDir` 即可。

### 2. 启动游戏

#### 方式一：IntelliJ IDEA 原生一键启动（强烈推荐 ⭐）
1. 修改完 `gradle.properties` 中的游戏路径后，在终端运行一次配置生成（或在 IDEA 右侧 Gradle 面板双击该任务）：
   ```bash
   ./gradlew generateIdeaRunConfig
   ```
   插件会自动解析你的配置并在 `.idea/runConfigurations/` 下生成便携的 `Run with Remold` 运行配置。
2. 在 IDEA 顶部工具栏选择 **`Run with Remold`**，点击绿色运行（或调试）按钮即可！
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
