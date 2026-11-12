# 08 · 架构决策、Lambda vs 匿名类本质对比与实证台账

## 1. 决策：Lambda 能做捕获桥，匿名类不能

| 维度 | Lambda（隐藏类） | 匿名类（`Target$Anonymous$<hash>`） |
|:--|:--|:--|
| 状态 | 捕获变量存在隐藏类实例字段里 | 捕获变量 = 合成字段（`this$0` / `val$*`），与实现同体 |
| 实现 | 方法体转发到宿主的 `lambda$…` 静态方法 | 方法体就在匿名类里 |
| 捕获列表变化 | 可把新捕获"桥接"进旧实例：`LambdaAligner.stepCaptureBridge` 从可达来源**重算**新值（常量 / final 字段 / 可折叠算式 / 构造器参数） | 旧实例没有新字段的初值，JVM 迁移后只能给 `0/null`；无来源可重算 |
| 结论 | 允许"删除 + 可证明新增"的捕获变化 | **禁止**已加载匿名类的捕获字段变化 |

`[观测]` C3 半残实例：2 字段→4 字段扩容并保留旧 2 参 `<init>` 后，用旧构造器建的实例调新方法体读新引用字段
→ `NullPointerException: Cannot invoke "Object.toString()" because "this.val$b" is null`；用新构造器则正常。

## 2. 决策：放弃"原地字段扩容 / 重命名追加"，删除产品路径 `System.gc()`

真机证据链（同一客户端、同一类 `LootContextPredicate.combinedCondition`、同一序列）：

| # | 条件 | 读数 | 结论 |
|:--|:--|:--|:--|
| 1 | 合成类字段表变化，堆上**有**存活实例 | `[HEAPWATCH-DIFF] null{before=0 after=230 newlyNull=230}`，双通道 `agree=1220 / conflict=0` | `[观测]` 污染发生 |
| 2 | 字段表变化，`live{raw=4 reachable=0}`（GC 后 0 可达） | 放行后同样 `newlyNull=230` 并崩服 | `[观测]` 与可达实例数无关 |
| 3 | 字段表变化，`live{raw=0 reachable=0}`（堆上 0 实例） | 仍 `newlyNull=230` | `[观测]` 触发条件 = **成员 schema 与已加载版本不一致** |
| 4 | 只改方法体（`fieldTableChanged=false`） | 17~25 切面 `nullCount=0 / newlyNull=0`，双通道 `agree=6100 / conflict=0` | `[观测]` 方法体热更安全 |

因此：**已加载合成匿名类的任何成员 schema 变化一律拒绝**（见 [`05-synthetic-inner-gate-and-abort.md`](05-synthetic-inner-gate-and-abort.md)），
并删除产品路径的 `System.gc()`——判决不再依赖实例数，而在游戏进程里制造 STW 没有任何收益；
`live{raw}` 降级为纯诊断计数（`-1` = 探针不可用）。

**值类型直方图归因**（`[观测]`，来自场景 1 的日志原文）：

```
before: valueTypes{EntityPropertiesLootCondition=220  Predicate$$Lambda/0x…=12
                   BlockStatePropertyLootCondition=9  Util$$Lambda/0x…=2  AnyOfLootCondition=1}
after : valueTypes{null=230  Predicate$$Lambda/0x…=12  Util$$Lambda/0x…=2}
```

`230 = 220 + 9 + 1`（全部具名 Record 类值），`14 = 12 + 2`（全部隐藏类 Lambda 值幸存），`244 = 230 + 14`。
即：被抹的是 Record 值，幸存的是 Lambda 值。
`[推断]` 这些实例由 `KnotClassLoader` 加载、`Util.allOf` 单元素分支相关——未直接观测，不作为结论使用。
`[推断]` 底层机理为 JBR/DCEVM `ModifyInstances`（未直接观测其内部状态）。

## 3. 决策：`BytecodeTransformerBridge.Abort` 而不是"降级返回字节"

桥接器只有两种"降级返回"，两种都是错的：返回**织入后**字节 → 宿主调用合成类不存在的新 `<init>`（`NoSuchMethodError`）；
返回**未织入**原始字节 → 抹掉 Mixin 钩子（washout）。唯一正确动作是**不重定义**，因此需要一个显式的
中止信号（见 05 §3）。`[观测]` 真机情况 1：首轮 `gateRejected=1` + 宿主 `was NOT redefined` + `newlyNull=0` + 不崩。

## 3.5 前提：`-g`（`LocalVariableTable`）与捕获顺序判定

Lambda 捕获顺序的判定有一条**编译配置前提**，不写清就会被误读成"对齐算法不行"。

| 编译配置 | v1 `() -> a+"/"+b` → v2 `() -> b+"/"+a`（签名不变，只换捕获顺序） | 原因 |
|:--|:--|:--|
| 无 `-g`（缺 LVT），捕获来自**局部变量** | 静默保持旧行为（stored=`A/B`，fresh=`B/A`） | `isPermutedPair` 拿不到捕获身份：没有参数名，局部槽号在旧/新字节之间不可比 |
| 有 `-g`（含 `vars`） | 正确换序（`B/A`）：拒绝原地配对 → 改名 + `Capture bridge (captures: 2 -> 2)` 重排实参转发 | LVT 提供参数名，`captureIdentity` 名字优先 |
| 无 `-g`，捕获来自**宿主形参** | 正确换序 | 宿主形参槽号（`#0/#1`）本身就能证明身份，不依赖 `-g` |

**为什么"无 `-g` 时无法判定"而不是算法不够好**（用户样例，同一宿主方法）：

| 版本 | 合成方法体 | indy 捕获槽序列 | `-g` 下 LVT 参数名 |
|:--|:--|:--|:--|
| v1 `a="A"; b="B"; () -> a+"/"+b` | `aload_0, aload_1, concat` | `[0,1]` | `a, b` |
| v2a 仅调换**声明顺序**（lambda 未改） | 同上（完全相同） | `[1,0]` | `a, b` |
| v2b lambda 改成 `b+"/"+a` | 同上（完全相同） | `[1,0]` | `b, a` |

无 `-g` 时 v2a 与 v2b 对对齐器是**同一信号**（方法体相同、槽序列相同、无名字），任何算法都区分不了。
所以正确处置不是"更聪明地对齐"，而是**在缺少判定信息时告警或拒绝**（`docs/status.md` 5.5 缺陷 4 仍开放）。

更硬的一层：`-g` 还**阻止 javac 在编译期把区别抹掉**。同一类里 `f(a,b)=() -> a+"/"+b` 与
`g(a,b)=() -> b+"/"+a`，`-g:none` 下 javac 把两者**去重合并成同一个合成方法**（`lambda$f$0`），
两个调用点共用同一个 `BootstrapMethod`；`-g` 下保留两个方法（LVT 参数名不同，不去重）。
端到端后果：v2 把 g 改成 `b+"/"+a+"!"` 后，有 `-g` 时旧 g 实例跟随新体，无 `-g` 时旧实例停在旧值且无 WARN
（旧 g 的 `CallSite` 指向的共享方法名，在新类里对应的是 f 的体）。

**判据适用前提**：本节的判定力以 `LocalVariableTable` 存在为前提。夹具与用户工程的编译档位必须一致
（Gradle 默认 `source,lines,vars`）；刻意用 `-g:none` 编译的探针（如只关心字节内容的基线录口探针）
不能用于验证捕获顺序。各模块当前的编译档位与例外登记在 [`docs/status.md`](../status.md)。

## 4. 未闭合与待办

- **事实③**（3 参 `<init>` 存在却抛 `NoSuchMethodError`）：**不再追查、未闭合**。记录见
  [`docs/status.md`](../status.md)。
- **P2 `[假设]`**：`schemaHash` 命名新合成类——schema 变化时用 `defineClass` 加载**新类名**，宿主只做
  body-only redefine。预注册判据：新名类加载 + 宿主 body-only redefine 下 `newlyNull=0`。未实现、未验证。
- **F6 四类拆分**（追加/缩容/改类型/不变）：旧版日志缺字段表行，仅 `executed=12/12`、`跳过未观测`、
  `拒绝=0` 可分类；新版已补 `loaded=/fresh=/flags` 行，待下一版真机四类拆分。

## 5. 判据清单

1. 任何"捕获结构变化"的放行都必须给出**值的来源**（Lambda 桥可以，匿名类不行）；
2. 判决只依赖不可变输入（成员 schema、候选字节），不依赖可变堆状态；
3. 桥接器拒绝必须走显式中止信号，不得用返回字节表达；
4. 未闭合项必须显式登记，不得被后续战报当成已解决。

## 6. 注解默认值（`AnnotationDefault`）漂移：实证读数与判决

> 探针：`tests/src/AnnotationDefaultProbe.java`（复用批次 K 冻结框架的读数口径与三格矩阵：
> 旧句柄 warm/cold · 重定义后 `getDeclaredMethods()` · 重定义后首次解析 · 消费者终局）。
> 夹具：内存 `javac` 现编注解类型 `probe.dflt.Mark`（`String value() default "v1"`）+ 消费者
> `probe.fix.Consumer`（类与方法各一个 `@Mark`，不显式写成员值 → 只能取默认值）。
> 跑法：`<jdk>/bin/java [-XX:+AllowEnhancedClassRedefinition] -javaagent:tests/build/p05/p05-agent.jar
> -cp "tests/build/classes/java/main;core/build/libs/remold-agent.jar;<asm jars>" AnnotationDefaultProbe`。
> 三格身份由探针自己打印的 `structuralRedefine` 读数判定（不靠参数名推断）。

### 6.1 环境（每格 `java -version` 全文见证据日志）

| 格 | 运行时 | 启动参数 | 能力读数 |
|:--|:--|:--|:--|
| ① | JBR 25.0.2+1-b329.72（`jbrsdk_jcef-25.0.2`） | 仅 `-javaagent:p05-agent.jar` | `structuralRedefine=NO(field-added-rejected:UnsupportedOperationException)` |
| ② | JBR 21.0.9（`JBR-21`） | 仅 `-javaagent:p05-agent.jar` | `structuralRedefine=NO(...)` |
| ③ | JBR 21.0.9（`JBR-21`） | `-XX:+AllowEnhancedClassRedefinition` + `-javaagent` | `structuralRedefine=YES(field-added-accepted)` |

### 6.2 分格影响范围（必须分开读，不能合并成一个结论）

- **格① / 格②（标准 HotSpot）＝ 全应用级旧值**：默认值改掉后，**所有**注解实例读数都还是旧值——
  包括重定义**之后才加载**的新消费者类（C5）与"加载在重定义前但从未读过注解"的类（C6）。
  即旧默认值黏在注解类型上，新代码也拿不到新值。
- **格③（JBR 21 + `-XX:+AllowEnhancedClassRedefinition`）＝ 仅已解析过的旧类**：C5/C6 读到**新值**，
  只有重定义前已经解析过注解的旧类（C1~C4）继续返回旧值。
  （此读数窄于先前的推断"首次解析也读旧值"，属**部分不符**；指挥部已追认缺陷在三格都成立。）

### 6.3 场景 × 三组读数（格①/②/③）

默认值变化 `default-value-change`（`"v1"` → `"v2"`）：

| 格 | R1 旧句柄 warm / cold | R2 重新 `getDeclaredMethods()` | R3 首次解析 | C1 旧实例 / C2 新解析类注解 / C3 新解析方法注解 / C4 旧实例方法 | C5 重定义后新类 / C6 加载于前未读 |
|:--|:--|:--|:--|:--|:--|
| ① | v1 / v1 | **v2** | **v2** | v1 / v1 / v1 / v1 | v1 / v1 |
| ② | v1 / v1 | **v2** | **v2** | v1 / v1 / v1 / v1 | v1 / v1 |
| ③ | **v2 / v2** | **v2** | **v2** | v1 / v1 / v1 / v1 | **v2 / v2** |

默认值移除 `default-removed`（`"v1"` → 无默认值）：

| 格 | R1 warm / cold | R2 | R3 | C1~C4 | C5 / C6 |
|:--|:--|:--|:--|:--|:--|
| ① | v1 / v1 | null | null | 全 v1（静默） | v1 / v1 |
| ② | v1 / v1 | null | null | 全 v1（静默） | v1 / v1 |
| ③ | **`"Mark.java"` / `"Mark.java"`** | null | null | 全 v1 | `IncompleteAnnotationException` / 同 |

> 格③ 的 `"Mark.java"` 是旧 `Method` 句柄的 `getDefaultValue()` 读到了**别的属性**的常量
> （`SourceFile`），3/3 复现，同机同参数；R2/R3（重定义后重新取句柄）读到 `null`（正确）。

默认值新增 `default-added`（无默认值 → `default "v1"`，指挥部裁定默认拒绝，标 `[推断]`）：
消费者在两侧都必须显式赋值才能编过，因此 C1~C4/C6 读显式值 `explicit`（对照）；
真正有区分力的读数是 C5（重定义之后才编译并加载的**裸 `@Mark`** 消费者）。

| 格 | R1 warm / cold | R2 | R3 | C5（裸 `@Mark`，重定义后加载） | 含义 |
|:--|:--|:--|:--|:--|:--|
| ① | null / null | **v1** | **v1** | `IncompleteAnnotationException` | 新代码也拿不到新默认值 → **有害** |
| ② | null / null | **v1** | **v1** | `IncompleteAnnotationException` | 同 ①，**有害** |
| ③ | null / null | **v1** | **v1** | **v1** | 新代码可用；旧类本就无法省略该成员 → 未见有害 |

对照组（去触发 / 灵敏度）：

- `control-identical-default`（只重定义、不改默认值）：三格全部读 v1，**没有**任何 v2 泄漏（去触发对照成立）；
- `sensitivity-explicit-value-change`（改消费者里**显式写死**的值）：三格 class-level 都读到 v2，
  证明读数链路能看见注解值变化（仅灵敏度对照，不构成默认值复现证据）；method-level 仍 v1
  （旧 `Method` 句柄缓存的注解读数，属另一形态，未纳入本批判定）。

### 6.4 判决与实现位置

- 默认值**变化 / 移除 / 新增**一律 fail-closed（默认拒绝）：`ClassDiffUtil.describeAnnotationDefaultChange`
  比对两侧同签名注解成员的 `AnnotationDefault`（基本类型/String/`Type`/enum/数组/嵌套注解深比较；
  两侧都不是注解类型则不判定；解析失败返回"不可判定"→ 调用方拒绝）；
  `RemoldAgent.annotationDefaultGate` 是两条路径共用的唯一判决入口；
  批量路径经 `handleAnnotationDefaultChangeInBatch` 拒绝并**整轮共退**（回滚对齐事务、清空本批待重定义
  集合/旧字节映射/重试候选、留 `ANNOTATION-DEFAULT-CHANGED` 判决并要求重启），
  动态路径在 `redefineDynamic` 前拒绝且不调用 `redefineClasses`。
- 降级通道 `-Dremold.agent.annotation_default_change=allow`（默认关闭）：放行时**必打**
  `[ANNOTATION-DEFAULT]` 警告并点名类与成员——不得静默。
  `[推断]`：即使显式放行，已加载的消费者类仍读旧默认值（批次 J 探针 gate 模式读数：
  `consumerBefore=v1 consumerAfter=v1`）。
- `default added` 为什么也拒绝：格①② 的 C5 抛 `IncompleteAnnotationException`（新代码依赖新默认值会炸），
  格③ 未见有害。`[推断]`：按最严格格（①②）定性更安全；是否对格③放宽由指挥部裁定。

### 6.5 复跑命令与证据

- 三格：见 6 开头跑法（`ev-20261112065151-6ca8` 格① / `ev-20261112065208-c5bb` 格② /
  `ev-20261112065225-cfcb` 格③，均含 `java -version` 全文与启动参数）。
- 门禁端到端（真 `remold-agent.jar` + `redefineDynamic`）：`AnnotationDefaultProbe gate`
  —— 默认拒绝 `ev-...`、显式放行 `ev-...`（见战报）。
- 单测：`./gradlew :tests:test --offline --tests=ClassDiffUtilTest --tests=AnnotationDefaultGateTest`
  （16 条；先红基线 `ev-20261112064258-0660` 10 条断言失败 / 0 编译错误 → 恢复后
  `ev-20261112064355-5f86` 16/16 绿）。

### 6.6 未闭合

- 真机（Fabric/Knot 客户端）未跑：本节全部读数来自 JVM 级探针，混合 Mixin 重织入后的注解类型变化未验。
- 格③ `getDefaultValue()` 读到 `SourceFile` 常量的成因未定位（只登记 3/3 复现，同机同参数）。
- 批量"整轮共退"的端到端（真实 `processChanges` 多类批次）未跑，当前由单测
  `AnnotationDefaultGateTest#batchRefusalRollsBackTheWholeBatchAndLeavesAVerdict` 覆盖接线，
  端到端仅有动态单类路径（`redefineDynamic`）。
