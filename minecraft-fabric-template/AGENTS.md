# AGENTS.md — Minecraft (Fabric) 模组模板

本文件约束在本模板目录内工作的编码代理。模板演示 Remold 的代码热重载、Mixin 重织入，
以及 `RemoldWandItem` 的示例逻辑；主入口是 `src/main/java/com/example/ExampleMod.java`。

## 1. 用 IDEA Debugger 验证游戏内逻辑

> 当需要验证复杂游戏内逻辑时，切勿在 IDEA Debugger 的 `evaluate_expression` 中直接编写包含
> `net.minecraft.*` / `net.fabricmc.*` 的复杂 Lambda（会因求值器类加载器隔离而编译失败）。请直接在模组类中
> 编写一个临时静态自检方法（或挂 `@OnReload`），执行 `compileJava` 交由 Remold 在 1~2 秒内热注入，
> 随后仅用 `evaluate_expression` 发起一行简单的静态方法调用（如 `ExampleMod.runSelfTest()`），
> 验证通过后再将该临时方法删除并热更新。

### 1.1 为什么会失败

`evaluate_expression` 的求值类由调试器在独立的编译单元里生成并加载，它看不到模组自身的
ClassLoader（在 Fabric 里还隔着 Knot / 映射层），因此 lambda 体内一引用 `net.minecraft.*`、
`net.fabricmc.*` 或模组自己的类型就编译不过。不含外部类型的简单表达式（字段读取、纯算术）不受影响。

### 1.2 推荐流程

1. 在 `ExampleMod` 里加一个 `public static void runSelfTest()`，把要验证的调用与断言都写在里面，
   用 `LOGGER.info` 输出结构化结果（例如 `[SELFTEST] key=value`），便于回读与比对；
2. 执行 `./gradlew compileJava`（或 IDEA 的 `Ctrl + Shift + F9`），Remold 在 1~2 秒内热注入，
   控制台打出 `HotSwap successful: N classes redefined`；
3. 在 `evaluate_expression` 里只写一行：`com.example.ExampleMod.runSelfTest()`；
4. 验证通过后删除该临时方法，再热更一次 —— 删除本身也是热更链路的一部分，
   `-Removed: [...]` 会出现在 `HotSwap successful` 之前。

### 1.3 不想手动触发时

给静态方法挂 `@OnReload`（`remold.annotation.OnReload`），Remold 每轮热更后自动调用一次。
注意 `@OnReload` 的语义是「对本类存活实例回调」：实例方法按存活实例逐个调用，**静态方法才是每轮一次**；
写在零实例的纯静态工具类上则一次都不会调用。完整契约见 `OnReload` 的 Javadoc。

### 1.4 与 Mixin 的关系

自检方法写在模组类里，不参与 Mixin 织入；若自检目标本身是 Mixin 注入点，改完需要靠
`plugin-mixin` 的重织入生效，验证前先确认控制台出现 `[MixinReload]` 相关日志，否则测到的是旧织入产物。

### 1.5 求值所在的线程

求值发生在被挂起的那个线程上。断点挂在客户端主线程（render thread）时求值才等价于在主线程执行；
不要从调试器自己的线程直接触碰世界或渲染状态。

### 1.6 收尾

自检方法是**一次性脚手架**，验证完即删，不要留在模板里，也不要提交进历史。

---

Remold 本体（`../AGENTS.md`）另有全局编码规则：安全门优先、失败即拒绝、拒绝路径必须经统一出口发 `warn`、
状态信息只在 `docs/status.md` 维护。修改 agent 侧代码前先读它。
