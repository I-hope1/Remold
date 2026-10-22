# Remold 演示与最佳实践指南 (Example & Best Practices)

本项目演示了 **Remold** 在两类核心场景下的极简接入与开发工作流：
1. **模式一：独立应用热重载（Application Mode）** —— 标准 CLI / 服务端 Java 程序；
2. **模式二：游戏模组热重载（Game Mod / JAR Mode，以 Mindustry 为例）** —— 纯净启动外部游戏本体，通过 `dependsOn` 与 `jar` 配置彻底解决类加载器（ClassLoader）双重加载冲突。

---

## 1. 快速上手：模式一（独立应用 3 步体验）

工程自带了一个持续运行的计数器演示应用（`example.Main` 与 `PlayerService`）。

### 第 1 步：启动应用
在项目根目录运行（需 JBR 环境）：
```bash
./gradlew :example:runRemold
```
控制台将输出启动信息，并且每隔 1 秒打印一次 `PlayerService` 的状态（含 `score` 累加值与状态标题）。

### 第 2 步：修改代码
保持应用在后台或终端 1 运行，打开 `example/src/main/java/example/PlayerService.java`：
- 修改 `tick()` 中的打印文本；
- 给类新增一个字段或更改初值；
- 在 `@OnReload` 回调中增加日志。

### 第 3 步：触发编译
在另一个终端运行：
```bash
./gradlew :example:compileJava
```
*(或者在 IntelliJ IDEA 中对当前文件按快捷键 `Ctrl + Shift + F9` 重新编译)*

**现象**：应用立刻完成原位热更，`@OnReload` 回调执行，新逻辑秒级生效，且已有的 `score` 存量状态完全保留！

---

## 2. 模式二：游戏模组开发最佳实践（以 Mindustry / MDT 为例）

### 模组开发的经典痛点与避坑
如果使用传统的 `runtimeOnly` 将模组和游戏本体塞进同一个 Classpath，会导致 **类加载器双重加载**：
1. 系统类加载器（AppClassLoader）把模组加载了一次；
2. 游戏内的 `ModClassLoader` 又把模组加载了一次；
3. 导致内存中出现两个互不相等的同名类，产生 `ClassCastException` 崩溃与交叉引用报错。

### Remold 的纯净解决方案：`jar` 启动 + `dependsOn`

Remold 提供了对可执行 JAR 的直接托管支持。无需配置 `mainClass`（插件自动从目标 JAR 的 `MANIFEST.MF` 读取），并将 Classpath 严格隔离到仅包含游戏 JAR，模组完全由游戏的 `ModClassLoader` 纯净加载：

在模组的 `build.gradle` 中配置：

```groovy
// 引入 Remold 自动化脚本插件
apply from: 'https://raw.githubusercontent.com/I-hope1/Remold/main/remold.gradle'

remold {
    // 1. 指定目标运行的游戏 JAR 路径（例如本地的 Mindustry.jar）
    jar = "D:/Mindustry/Mindustry.jar"
    
    // 2. mainClass 留空即可！Remold 会自动从 Mindustry.jar 中识别 DesktopLauncher
    
    // 3. 可选：传递给启动游戏的主参数
    // args = ["-loadmods"]

    // 4. 前置依赖：启动前自动执行当前模组的 jar 打包或部署任务
    dependsOn 'jar'
}

// 模组自身仅声明编译期依赖，坚决不使用 runtimeOnly 污染系统类加载器
dependencies {
    compileOnly "com.github.Anuken.Mindustry:core:v160.5"
}
```

#### 一键启动与热更循环：
1. 运行 `./gradlew runRemold`：
   - 插件会自动先执行 `jar` 打包当前模组；
   - 自动拉取并挂载 `remold-agent.jar`；
   - 干净拉起 `Mindustry.jar` 进入游戏；
2. 游戏保持运行，开发期间直接在 IDEA 里改代码并按 `Ctrl + Shift + F9` 编译；
3. Remold 在后台监听到 `build/classes/java/main` 变动，**直接在游戏内的 `ModClassLoader` 槽位上执行 Native 原位热替换**，零类加载器冲突，体验无感秒级刷新！

---

## 3. 模式三：完全不改 Gradle 的 IDEA 原生启动

如果更习惯在 IDEA 中直接配置 Run Configuration（例如直接点绿色三角形启动游戏）：
1. 顶部打开 `Run` -> `Edit Configurations...`；
2. 新建或选中 JAR Application（指向你的 `Mindustry.jar`）；
3. 在 **VM Options** 填入（填入真实绝对路径）：
   ```text
   -XX:+AllowEnhancedClassRedefinition
   -javaagent:D:/tools/remold-agent.jar=E:/YourMod/build/classes/java/main
   --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
   --enable-native-access=ALL-UNNAMED
   ```
4. 启动游戏后，同样改完代码按 `Ctrl + Shift + F9` 即刻生效。
