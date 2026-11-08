# 1.21.4 客户端 gametest 探针（Remold）

本目录是一个**独立 Gradle 工程**，只承担一件事：在 **Minecraft 1.21.4** 的真实客户端里跑
`fabric-client-gametest-api-v1`，验证 `remold_wand` 的「物品注册 / 模型渲染 / 真实右键」链路。

它**不是**主模板。仓库主模板 `minecraft-fabric-template/` 的基准版本是 **1.21.1**；1.21.1 的
fabric-api 不含 `fabric-client-gametest-api-v1`，所以这条客户端 gametest 能力只能留在 1.21.4 上，
作为独立探针保存，而不是让整个模板为了它停在 1.21.4。

## 与主模板的关系

| | `minecraft-fabric-template/` | `tests/fabric-gametest-1.21.4/` |
|:--|:--|:--|
| MC 基准 | **1.21.1**（yarn 1.21.1+build.3 / loader 0.16.9 / fabric-api 0.105.0+1.21.1） | 1.21.4（yarn 1.21.4+build.8 / loader 0.16.14 / fabric-api 0.119.4+1.21.4） |
| 定位 | 模组作者直接复制使用的模板 | 仅验证 Fabric 客户端 gametest 链路 |
| HotSwap Toast | ✅ 有（`src/main/java/com/example/client/`） | ❌ 无（探针不测 Toast，client 入口点已移除） |
| 客户端 gametest | ❌ 1.21.1 fabric-api 无该模块 | ✅ `./gradlew runClientGameTest` |

本探针的 `src/main/java/com/example/` 与 `src/gametest/` 内容由主模板 1.21.4 时期（commit `4d459ff`）
的 tracked 文件原样复制而来，仅做以下裁剪：

- 删除 `src/main/java/com/example/client/`（Toast 客户端入口与渲染代码）；
- 删除 `src/test/`（Toast 纯逻辑单测）及 `build.gradle.kts` 中的 JUnit 依赖；
- `fabric.mod.json` 去掉 `client` 入口点；
- `settings.gradle.kts` 的 `rootProject.name` 改为 `fabric-gametest-1.21.4`。

`build.gradle.kts` 中的 gametest 配置、`src/gametest/` 源码、截图模板与 1.21.4 资源保持原样。

## 运行

### 前置条件

- **JBR 21**（本探针与主模板一样要求 Gradle JVM = 21；Loom 固定在 1.17.21，1.18+ 需要 JVM 25）。
- 可用的图形环境：客户端 gametest 会真的启动 Minecraft 客户端窗口，无显示环境（headless）无法运行。

### 命令

```bash
# 在仓库根目录
cd tests/fabric-gametest-1.21.4

# 首次会解析 1.21.4 的 Loom/Minecraft/yarn/fabric-api 依赖（联网）
./gradlew compileJava        # 只验证源码/资源能编译
./gradlew runClientGameTest  # 真客户端 gametest：建测试世界 → 热栏截图比对 → 真实右键 → 断言聊天栏
```

`build.gradle.kts` 通过 `configure<FabricApiExtension> { configureTests { ... } }` 生成 `gametest`
源集与 `clientGameTest` 运行配置，并自动接受测试运行目录的 EULA；Remold Gradle 插件会向该运行配置
注入 `-javaagent` 与 DCEVM 参数。

### 覆盖的断言

- 物品 `example_mod:remold_wand` 已注册（1.21.4 依赖 `assets/example_mod/items/remold_wand.json` 物品定义）；
- 热栏里模型能渲染 —— 截图与 `src/gametest/resources/templates/wand_hotbar.png` 比对；
- 真实右键触发 `use()` —— 聊天栏出现 `[Remold]` 消息。

## 已知限制

- 这是 1.21.4 特有的验证资产，**不随主模板发布**，也不在根 `./gradlew check` 内（它是独立 Gradle 工程，没有加入根 `settings.gradle`）。
- 无显示环境下只能验证 `compileJava`，`runClientGameTest` 需要在本机图形会话中手动执行。
- 探针不包含 HotSwap Toast，因此不能用它验证 Toast 卡片；Toast 的手动验证步骤见主模板 README。
- 1.21.1 上不存在等价的客户端 gametest 能力（`fabric-client-gametest-api-v1` 从 1.21.4 起提供）。

## 许可协议

本探针沿用主模板的 [MIT](LICENSE) 协议。
