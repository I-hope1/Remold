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

**本仓库的配置事实**（`[观测]`，`tests/build.gradle`）：

- `:tests` 的 SemAssert 夹具（`hstestFx_*`）曾显式收窄为 `-g:source,lines` → **缺 `vars`**，即整批夹具跑在
  "无调试信息"档，与真实用户工程（Gradle 默认 `source,lines,vars`）不一致；
- 现已改为全量 `source,lines,vars`，理由与上面这张表一致；
- 其它模块与两个模板从未显式设置 → Gradle 默认即全量 `-g`；
- 例外：`PremainBaselineProbe` 刻意用 `-g:none`（只关心字节内容），**不可用于验证捕获顺序**。

## 4. 未闭合与待办

- **事实③**（`db74eec` 中 3 参 `<init>` 存在却 `NoSuchMethodError`）：**不再追查、未闭合**。
- **P2 `[假设]`**：`schemaHash` 命名新合成类——schema 变化时用 `defineClass` 加载**新类名**，宿主只做
  body-only redefine。预注册判据：新名类加载 + 宿主 body-only redefine 下 `newlyNull=0`。未实现、未验证。
- **F6 四类拆分**（追加/缩容/改类型/不变）：旧版日志缺字段表行，仅 `executed=12/12`、`跳过未观测`、
  `拒绝=0` 可分类；新版已补 `loaded=/fresh=/flags` 行，待下一版真机四类拆分。

## 5. 判据清单

1. 任何"捕获结构变化"的放行都必须给出**值的来源**（Lambda 桥可以，匿名类不行）；
2. 判决只依赖不可变输入（成员 schema、候选字节），不依赖可变堆状态；
3. 桥接器拒绝必须走显式中止信号，不得用返回字节表达；
4. 未闭合项必须显式登记，不得被后续战报当成已解决。
