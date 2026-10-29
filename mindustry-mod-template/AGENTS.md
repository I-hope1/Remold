# AGENTS.md — Mindustry 模组模板

本文件约束在本模板目录内工作的编码代理。模板只演示两件事：Remold 的代码热重载，以及
`assets/sprites/` 下 PNG 的贴图热更新；全部代码集中在 `src/example/ExampleJavaMod.java`。

## 1. 用 IDEA Debugger 验证游戏内逻辑

> 当需要验证复杂游戏内逻辑时，切勿在 IDEA Debugger 的 `evaluate_expression` 中直接编写包含
> `arc.*` / `mindustry.*` 的复杂 Lambda（会因求值器类加载器隔离而编译失败）。请直接在模组类中
> 编写一个临时静态自检方法（或挂 `@OnReload`），执行 `compileJava` 交由 Remold 在 1~2 秒内热注入，
> 随后仅用 `evaluate_expression` 发起一行简单的静态方法调用（如 `ExampleJavaMod.runSelfTest()`），
> 验证通过后再将该临时方法删除并热更新。

### 1.1 为什么会失败

`evaluate_expression` 的求值类由调试器在独立的编译单元里生成并加载，它看不到模组自身的
ClassLoader，因此 lambda 体内一引用 `arc.*`、`mindustry.*` 或模组自己的类型就编译不过。
不含外部类型的简单表达式（字段读取、纯算术）不受影响。

### 1.2 推荐流程

1. 在 `ExampleJavaMod` 里加一个 `public static void runSelfTest()`，把要验证的调用与断言都写在里面，
   用 `Log.info` 输出结构化结果（例如 `[SELFTEST] key=value`），便于回读与比对；
2. 执行 `./gradlew compileJava`（或 IDEA 的 `Ctrl + Shift + F9`），Remold 在 1~2 秒内热注入，
   控制台打出 `HotSwap successful: N classes redefined`；
3. 在 `evaluate_expression` 里只写一行：`example.ExampleJavaMod.runSelfTest()`；
4. 验证通过后删除该临时方法，再热更一次 —— 删除本身也是热更链路的一部分，
   `-Removed: [...]` 会出现在 `HotSwap successful` 之前。

### 1.3 不想手动触发时

给静态方法挂 `@OnReload`（`remold.annotation.OnReload`），Remold 每轮热更后自动调用一次。
注意 `@OnReload` 的语义是「对本类存活实例回调」：实例方法按存活实例逐个调用，**静态方法才是每轮一次**；
本模板的 `Mod` 实例在进程里恰好只有一个，所以写在实例方法上看起来像「模组收到热更通知」。
完整契约见 `OnReload` 的 Javadoc。

### 1.4 求值所在的线程

求值发生在被挂起的那个线程上。断点挂在游戏主线程时求值才等价于在主线程执行；
不要从调试器自己的线程直接触碰逻辑或渲染状态。

### 1.5 收尾

自检方法是**一次性脚手架**，验证完即删，不要留在模板里，也不要提交进历史。

---

Remold 本体（`../AGENTS.md`）另有全局编码规则：安全门优先、失败即拒绝、拒绝路径必须经统一出口发 `warn`、
状态信息只在 `docs/status.md` 维护。修改 agent 侧代码前先读它。
