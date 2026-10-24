# 现代 Minecraft Fabric 模组模板 (集成 Remold 零重启热重载)

本模板基于官方 **Fabric Example Mod**（Minecraft 1.21.1 + Java 21 + Fabric Loom 1.8），并已深度预集成 **Remold 热重载自动化引擎**。

让 Minecraft 模组开发者彻底告别“改一行代码就得重启客户端、忍受 1~2 分钟漫长黑屏加载”的折磨，实现 **`Ctrl + Shift + F9` 秒级原位热更**！

---

## 30 秒快速上手体验

### 1. 启动 Minecraft 开发客户端
在 IntelliJ IDEA 中：
1. 导入本项目后等待 Gradle 同步完成；
2. 运行 Gradle 任务生成客户端运行配置：
   ```bash
   ./gradlew loom:runClient
   # 或在 IDEA 右侧 Gradle 面板中双击 fabric -> runClient
   ```
3. Remold 脚本插件已自动为 Loom 的 `client` 运行配置注入热重载参数（`-XX:+AllowEnhancedClassRedefinition` 与 `-javaagent` 探针）。

### 2. 体验秒级原位热重载（无需退出世界）
进入游戏中的单人测试世界，输入指令获取演示物品：
```text
/give @s example_mod:remold_wand
```
拿着魔法棒在空中右键，屏幕会弹出绿色提示文字：`[Remold] Hello from Remold Fabric Hot Reload!`。

**现在见证热重载奇迹**：
1. **修改代码**：保持游戏客户端开着别关，在 IDEA 中打开 [`src/main/java/com/example/item/RemoldWandItem.java`](src/main/java/com/example/item/RemoldWandItem.java)；
2. 将 `message` 变量修改为任意内容（比如 `"🎉 Remold 热重载成功！无需重启游戏！"`）；
3. **甚至可以新增字段**：例如新增一个 `public int clickCount = 0;` 并让右键时递增显示，Remold 的 **InitFix** 引擎会自动为游戏世界中已拿在手里的存量实例初始化该字段；
4. 按快捷键 **`Ctrl + Shift + F9`**（重新编译当前类）；
5. 切换回游戏窗口，再次右键魔法棒——**新逻辑和新增字段立刻生效！**

---

## 核心技术优势（相比普通 IDEA 热更）

| 场景 | 普通 JDWP 热更 (IDEA 默认) | Remold 增强热重载 |
| :--- | :--- | :--- |
| **新增声明字段** | ❌ 直接报错 `UnsupportedOperationException` | ✅ **InitFix 引擎原位初始化**存量与静态实例 |
| **修改 / 新增 Lambda** | ❌ 编号漂移导致 `NoSuchMethodError` 崩溃 | ✅ **LambdaAligner 智能对齐** CallSite 调用点 |
| **匿名内部类拓扑变更** | ❌ 类名篡夺或编号漂移 | ✅ **AnonClassAligner 4 阶拓扑对齐** |
| **Mixin 字节码增强** | ❌ Redefine 绕过 ClassLoader 丢失注入 | ✅ **BytecodeTransformerBridge 自动重新织入** |

---

## 常见疑问解答 (FAQ)

### Q: 发布模组给普通玩家需要带 Remold 吗？
**完全不需要！**  
Remold 纯粹是开发期挂载在 JVM 上的探针。打包发布给普通玩家时，直接运行标准构建命令：
```bash
./gradlew build
```
产出的 `build/libs/example_mod-1.0.0.jar` 是纯净的标准 Fabric 模组，普通玩家在官方客户端或各第三方启动器中均可正常使用。
