# Remold Example Application

这是一个极简的 Remold 热重载演示应用，展示了：
- 如何在项目中引入 `remold.gradle` 脚本插件；
- 如何使用 `@OnReload` 生命周期回调；
- 如何在热更新时保留存量实例状态（`score`、`level` 不丢失）；
- 匿名类与新增字段的平滑热替换。

---

## 快速体验热重载 (3 步体验)

### 第 1 步：启动应用
确保你处于 JBR (JetBrains Runtime) 环境或已配置环境变量 `JBR_HOME`，在项目根目录运行：

```bash
./gradlew :example:runRemold
```
控制台将输出启动信息，并且每隔 1 秒打印一次 `PlayerService` 的状态。

### 第 2 步：修改代码
保持终端 1 运行不要关闭，打开 `example/src/main/java/example/PlayerService.java`：
- 修改 `tick()` 中的打印文本或倍率；
- 或者修改 `statusTitle` 的初值；
- 或者在 `@OnReload` 回调中增加日志。

### 第 3 步：触发编译
在另一个终端运行：
```bash
./gradlew :example:compileJava
```
*(或者在 IntelliJ IDEA 中对 `PlayerService.java` 按 `Ctrl + Shift + F9` 重新编译)*

回到终端 1，你将立即看到：
1. Agent 捕获到字节码更新并执行热替换；
2. 触发 `@OnReload` 回调；
3. 新逻辑立即生效，且已有对象的 `score` 状态依然保留！
