# 人工验收清单（模板场景 1–8，真实 Fabric 1.21.1 客户端）

> 这是**待用户确认**的人工验收模板：场景、步骤与预期终局已经固定，**「结果（构建）」列一律留空**，
> 由用户在真实客户端上按同一份清单填写。自动化证据见文末「证据索引」，但自动化探针只承担隔离与回归，
> 不替代真实客户端验收（见 [`AGENTS.md`](../../AGENTS.md) §8.5）。
>
> - 状态登记：[`docs/status.md`](../status.md)「用户人工验收（模板场景 1–8）」行
> - Forge / NeoForge 列保持「未验证」，本轮不登记人工结论
> - 构建列格式建议：`<remold 版本> / <模组构建号或 commit> / <日期>`；失败时附服务端或客户端日志片段

## 环境前置

| 项 | 要求 |
|:--|:--|
| 客户端 | Minecraft 1.21.1 + Fabric Loader + Fabric API（与 `tests/fabric-mixin-probe/c3/` 探针同构） |
| JVM | JBR 21（`-XX:+AllowEnhancedClassRedefinition`；Remold 启动日志应打印 `Structural HotSwap Supported: true`） |
| Agent | remold agent jar + `plugin-mixin`（Mixin 场景另需测试模组，含 `@Mixin` / `@Unique` / `@Inject`） |
| 观察面 | 游戏内行为、客户端日志中的 `[LAYOUT-REJECT]` / `HotSwap successful` / `Instance field init patch ... done` 行 |

## 场景表

| # | 场景 | 操作步骤 | 预期终局（业务可观测） | 结果（构建） | 备注 |
|:-:|:--|:--|:--|:--|:--|
| 1 | 方法体热更 | 修改一个已加载类的方法体（不改字段/方法签名），保存触发 | 下一帧即运行新逻辑；日志出现 `HotSwap successful`，无 `LAYOUT-REJECT`、无 `LinkageError` | | 基础路径 |
| 2 | 新增实例字段（存量实例） | 给已加载类新增一个有初值的实例字段，并在方法里读取它 | 已有实例读到新字段初值（非 0）；日志出现 `Instance field init patch ... done`，`failedFields=[]` | | InitFix 主路径 |
| 3 | 新增静态字段 / `@RemoldReinit(OVERWRITE)` | 新增静态字段并在方法中读取；对已有字段加 `@RemoldReinit` 后改初值 | 静态初值生效；被 `OVERWRITE` 的字段在存量实例上按新初值重置 | | 覆写语义 |
| 4 | Mixin 目标类热更（改 handler） | 改测试模组 Mixin 的 `@Inject` handler 方法体，保存触发 | 目标类被重新织入；注入点新行为生效；`@Unique` 字段值保留；无重复注入字段 | | 目标级联 |
| 5 | Mixin 类自身热更（加方法/字段） | 给 `@Mixin` 类新增一个 `@Inject` handler 方法（或 `@Unique` 字段），触发 reload | reload 不被 conform 校验拒绝（无 `please restart client`）；目标类 handler 集合同步更新、新行为生效 | | `MixinClassInfoSync` |
| 6 | 非法布局变更 → 拒绝 → 合法变更恢复 | 先删除一个仍被外部类读取的字段（或把字段类型改掉但不加 `@RemoldReinit`）保存；**不重启**，再改回合法变更（新增字段 + 改方法体）保存 | 第一轮：`[LAYOUT-REJECT]`，运行态仍是旧代码、无半应用；第二轮：立即 `HotSwap successful`，新增字段在存量对象上正确初始化，拒绝记录不阻塞下一轮 | | 对应 `LayoutRejectRecoverProbe` |
| 7 | 匿名类/局部类与 Lambda 拓扑变更 | 在类内前插/删除匿名类、调整 lambda 顺序后保存 | 旧调用点不抛 `NoSuchMethodError`；匿名类按语义配对，行为与全量重启一致 | | 拓扑对齐 |
| 8 | 反射/序列化缓存驱逐 | 热更一个被 Jackson/Gson（或 Arc `Json`）序列化的类后继续读写 | 序列化/反序列化使用新字段集合，不出现旧缓存导致的丢字段/未知字段 | | 缓存 SPI |

## 记录规则

- 每个场景必须记录**构建号/commit**与**日期**；「结果（构建）」列留空即表示尚未确认。
- 判定以业务终局为准：游戏内读写结果、日志中的成功/拒绝行与补丁完成行；不以「没有报错」代替。
- 出现失败时保留完整日志与复现序列，必要时用 `tests/fabric-mixin-probe/` 下的同类探针缩小范围。
- 场景 6 的自动化对照已覆盖「有存活实例才拒绝、无实例则 WAIVE」；人工验收只确认真实客户端上的端到端表现。

## 证据索引

### 本轮新增（item 5：缓存矩阵 + 布局拒绝恢复）

| 证据 | 覆盖 | 结果 |
|:--|:--|:--|
| [`tests/fabric-mixin-probe/evidence/cache-layout-recovery.txt`](../../tests/fabric-mixin-probe/evidence/cache-layout-recovery.txt) | 原始输出 + 变异检查 + 拒绝/恢复日志 + 缺陷修复红绿 | 通过 |
| `tests/src/CacheSourceMatrixProbe.java`（任务 `:tests:cacheSourceMatrixProbe`） | `sourceBytecodeCache` 真实路径矩阵：首载写入 / 普通重定义 / 混合织入保留原始字节 / 多 ClassLoader 不互相覆盖 | 23/0 |
| `tests/src/SourceBytecodeCacheMatrixTest.java`（`./gradlew :tests:test`） | 同上矩阵的 JUnit 快速半（无需 JBR） | 通过 |
| `tests/src/LayoutRejectRecoverProbe.java`（任务 `:tests:layoutRejectRecoverProbe`） | `LAYOUT-REJECT` 后存活实例状态 / 类定义 / 双基线 / pending / 台账均未污染；同轮对照证明拒绝由存活实例导致；下一轮合法新增字段立即生效 | 41/0 |
| `tests/src/remold/HotswapSkipSummaryTest.java` | 拒绝摘要不得虚报「reader scan could not complete」（缺陷修复的回归断言） | 通过 |

### 既有自动化证据（真实 Fabric/Forge 探针）

| 证据 | 覆盖 |
|:--|:--|
| [`c3-server-e2e.txt`](../../tests/fabric-mixin-probe/evidence/c3-server-e2e.txt) | 真实 Fabric 1.21.1 服务端主路径（含 Mixin 类自身热更） |
| [`inject-evolution.txt`](../../tests/fabric-mixin-probe/evidence/inject-evolution.txt) | `@Inject` handler 自演化三场景（真实 KnotServer） |
| [`multi-mixin-fabric.txt`](../../tests/fabric-mixin-probe/evidence/multi-mixin-fabric.txt) | Fabric 多 Mixin 同目标五阶段 |
| [`c5-forge-e2e.txt`](../../tests/fabric-mixin-probe/evidence/c5-forge-e2e.txt)、[`multi-mixin-forge.txt`](../../tests/fabric-mixin-probe/evidence/multi-mixin-forge.txt) | Forge 1.21.1（ModLauncher）路径；**人工验收列仍为未验证** |
| [`c2-exitcode.txt`](../../tests/fabric-mixin-probe/evidence/c2-exitcode.txt) | Mixin 目标基线二次织入缺陷的 exit-code 断言 |
