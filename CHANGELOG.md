# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-11-02

### Added
- **Minecraft Fabric & SpongePowered Mixin 生态支持**:
  - 新增扩展插件 `:plugin-mixin`（`remold-plugin-mixin.jar`），支持 `@Mixin` 目标类重织入与级联重定义；
  - 新增开箱即用的 Minecraft Fabric 模版 `minecraft-fabric-template`，支持 Loom 自动注入与热重载；
  - 运行时标准 JVM 能力探测（`EnhancedHotswapGate`），在非增强 JVM 下自动摘批降级，避免崩溃。
- **Arc 全套资源运行时热重载 (`:plugin-arc`)**:
  - 贴图同尺寸原位直刷（Tier 1）与运行期新增贴图动态注册；
  - 字体（woff/ttf）按文件名精确路由至 UI 槽位；
  - 多语言国际化 Bundle 按 locale 父链路由并安全回写 `UnlockableContent` 内容缓存；
  - 音效与音乐原位重载（句柄复用、内存释放与平滑播放恢复）；
  - 内置着色器原位重新编译回写。
- **成员删除读者分析与分派安全门 (`RemovedMemberReaders`)**:
  - 实现基于 JVMS 5.4.3.2 向上解析与 JVMS 5.4.6 向下分派的批次外读者分析纯函数；
  - 精确区分未决悬空（`NoSuchFieldError`/`NoSuchMethodError`）、抽象方法缺失（`AbstractMethodError`）、静态性翻转（ICCE）与合法回退（Override 回退或父类同名字段接住）；
  - 扫描未完成时安全熔断，阻断陈旧代码破坏；
  - 编译期常量（`ConstantValue`）安全放行。
- **物理内存布局预测器与漂移安全门 (`LayoutPredictor` / `SuperclassDriftGate`)**:
  - `LayoutPredictor` 通过独立抛弃式 ClassLoader 事前干净加载预测物理布局（实测与 DCEVM 布局逐字节 100% 一致）；
  - `SuperclassDriftGate` 检测并阻断 `LAYOUT_DRIFT`、`SUPERCLASS_FIELD_DRIFT` 与 `DELETED_FIELD_OFFSET_CONFLICT`；
  - 预注入整条非 JDK 祖先链，防御 package-private 运行时包隔离访问异常；
  - 访问器自愈实测证明（`Field.get` 与 `MethodHandle` 自动自愈，仅拦截固化偏移的 `VarHandle` 与 `Unsafe`）。
- **第三方实例级缓存驱逐 SPI (`ReflectionCacheManager`)**:
  - 提供 `ReflectionCacheEvictor` SPI，在类重定义成功后自动驱逐实例级元数据缓存；
  - 内置 Jackson `ObjectMapper`、Gson `Gson` 与 Arc `Json` 实例级驱逐器。
- **Lambda 表达式增强对齐与单向捕获桥 (`LambdaAligner`)**:
  - 新增单向捕获桥，支持安全删除捕获变量；
  - 支持新增捕获的纯源重放（字面量常量、`static final`、`final` 实例字段、可折叠算式、构造器参数经 final 字段）；
  - 引入运行时常量访问器登记表（`RuntimeConstants`），支持 `Scl.scl()` 及带参形态 `Scl.scl(float)`；
  - 引入 LVT 形参名定捕获身份，拦截同签名换序的静默错值；
  - 骨架指纹与 consumer 偏好拆开同形 lambda，消除多轮静默串位。
- **字段描述符类型变更热重载 (`LayoutGate` / `@RemoldReinit`)**:
  - 当字段类型发生变化（oldDesc != newDesc）且批次外无悬空读者时，在堆上无实例或标有 `@RemoldReinit` 时直接放行；
  - 自动清空旧类型 `REINIT_APPLIED` 指纹缓存，重定义后由 InitFix 安全写入新类型的初始值；若有实例未标 `@RemoldReinit` 则给出明确引导文案。
- **构建与测试体验提速与开发者引导**:
  - 脚本与 Gradle 插件自动为 `JavaCompile` 注入全量调试符号（`debug = true`, `source,lines,vars`），为 LVT 形参捕获与参数回溯提供高精度符号支撑；
  - 为子模块增设本地 JitPack 兼容发布通道（`com.github.I-hope1.Remold:*:v<ver>`），支持下游模版在尚未发布远程 Tag 时无缝本地联调；
  - 为 Minecraft Fabric、Mindustry 及示例工程补充系统化的「动手试试」系列引导教程注释；
  - 新增常驻 Javac 守护进程 (`JavacDaemon`)，测试夹具编译提速 60x（2370ms → 35ms）；
  - 新增 Gradle 插件封装模块 `:gradle-plugin`（`io.github.i-hope1.remold`）；
  - 新增 GitHub Tag 自动化 Release 工作流（`.github/workflows/release.yml`）；
  - 测试套件扩充至 397 个 JUnit 测试，持续守卫架构不变量。

### Changed
- 基础许可证切换为 Apache-2.0（插件模块保持各自生态兼容许可）；
- ASM 统一升级至 9.11.1，单一真相源配置于 `gradle.properties`；
- 存活实例枚举后置到重定义之后，彻底关闭枚举时刻与重定义时刻之间的并发实例化缝隙；
- `@RemoldReinit` 增设生效点名日志，收紧重置时机至初值表达式变动；
- Arc Json 缓存驱逐器移入 `:plugin-arc`，核心引擎不再反向持有 Arc 外部知识。

## [0.1.3] - 2026-10-24
- 修复 `@OnReload` 回调早于 InitFix 补丁的时序颠倒缺陷；
- 修复 IDEA 运行配置中 Agent 与插件 Jar 路径统一落到 `.gradle/remold/`；
- 识别并明确提示同一编辑被反复拒绝的循环；
- 补充 Mindustry PNG 资源热更与 Fabric 模版演示。

## [0.1.2] - 2026-10-23
- 完善模版配置与发布流程收敛。

## [0.1.1] - 2026-10-23
- 将 `remoldVersion` 收敛至 `gradle.properties` 作为单一真相源。

## [0.1.0] - 2026-10-23
- 初始版本发布：InitFix 存量初始化与匿名类拓扑对齐引擎。
