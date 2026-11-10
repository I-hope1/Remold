# Mixin 合成对齐器收敛：真机多轮往返压测规程

本文件是**验证规程**（不含实现状态）。状态与证据见 `docs/status.md` 与战役战报。

## 1. 被测对象

| 组件 | 职责 |
|:--|:--|
| `core/src/remold/ref/MixinMemberAlign` | 重织入产物中 Mixin 生成方法（`handler$*`/`redirect$*`/`wrapOperation$*`/`modify$*`/`localvar$*`/`args$*`/`md$*`）的名称/顺序对齐回基线；基线有、产物已消失者补**墓碑桩**（同名/同描述符/同访问标志/同方法表位置；void→`RETURN`，返回类型与某入参相同→返回该入参，其余→零值） |
| `plugin-mixin` `MixinInnerClassHotSwap` | 合成匿名类 `<Target>$Anonymous$<hash>` 的重建与重定义；`LOADED` 记账 + `isUnchanged` 做**无实质变更短路**（字节一致则跳过 `redefineClasses`） |
| `core/src/remold/reflect/mixin/MixinReloadPolicy` | `priority`/`targets`/`interfaces` 三处元数据预对齐与回滚；接口表 `+I → -I` 双向同步 |

## 2. 客户端压测序列（同一客户端不重启，连续 ≥4 轮）

对含匿名内部类的 `HerrscherEntityMixin` 每轮依次：

1. **R1** `@Mixin(priority = 1500)`（原 1000）→ 保存 `.class`；
2. **R2** 加 `implements ProbeDuck`（标记接口）→ 保存；
3. **R3** `@Mixin(targets = {"ZombieEntity", "SkeletonEntity"})` → 保存；
4. **R4** 全量还原（priority 1000、去掉 `ProbeDuck`、targets 回 `HerrscherEntity`）。

每轮结束后让目标实体 tick 至少一次（触发注入体里的匿名类实例化）。

## 3. 通过判据（逐条在日志里核对）

| 判据 | 日志锚点 |
|:--|:--|
| 墓碑桩保留 | `[MIXIN-ALIGN] <class>: retained N tombstone stub(s) ...`（R3 卸载旧目标时必现） |
| 在途栈帧不炸 | 无 `NoSuchMethodError: ... handler$*` / `...$Anonymous$...<init>` |
| 无变更短路 | 还原轮不应出现 `synthetic inner class ... redefined BEFORE its target redefine`（字节未变时应跳过） |
| 结构窗口关闭 | 出现重定义时，顺序为 `synthetic inner class ... redefined BEFORE its target redefine` → `Successfully redefined <target>` |
| 接口表双向 | R2 出现 `interface table updated ... +[ProbeDuck]`，R4 出现 `-[ProbeDuck]` |
| 无连带 NPE | 无 `NullPointerException: ... combinedCondition is null`（`LootContextPredicate`） |

## 3.1 堆实例哨兵（`-Dremold.probe.heapwatch=true`）

- `-Dremold.probe.heapwatch.class=<点分类名>`、`-Dremold.probe.heapwatch.field=<字段名>`。
- 目标类**按已加载类表解析**（`Instrumentation.getAllLoadedClasses` + 宿主权威副本选择），
  **不使用 system classloader**——Knot / ModLauncher 把游戏类装在自己的加载器里，system 加载器看不见。
  日志会打印 `loader=loadedClasses/net.fabricmc.loader.impl.launch.knot.KnotClassLoader` 之类的解析途径，
  若解析失败只 warn 一次并点名已尝试的途径。
- 三个切面：`before-redefine` / `after-redefine` / `after-field-patch`，每个切面输出
  `total=N nullCount=M firstNull{identity=… conditions=…}`。

## 4. 本仓库内的等价回归（不需要客户端）

`.battle/newmixin-probe`（Fabric dedicated server + JBR 21 DCEVM）内置同一 R1~R4 序列，可在同一 JVM
连续跑 4 轮，并在每轮的结构重定义窗口内用两条并发线程持续调用注入方法；`RESULT PASS` 表示
4/4 轮无 `NoSuchMethodError`、无窗口异常。该探针**不覆盖**客户端累积状态下的 `LootContextPredicate` NPE，
后者只能由真机客户端复验。
