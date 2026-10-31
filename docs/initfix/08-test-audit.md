# 08 测试判定力审计（"从未红过"的用例）

适用：新增或修改 `InitFix` 枚举补齐、安全门、JVMTI 堆遍历相关测试时阅读。

本文记录一次**限定范围**的判定力审计：不扫全部用例，只抽查三类高风险测试——
依赖环境条件才能触发的、断言"没发生"的、靠日志或副作用间接判定的。
方法统一为**变异检查**：注入一处破坏，看对应用例是否变红；变绿即为空转嫌疑。

审计结论写在这里而不是 `docs/status.md`，因为这是**一次性证据**而非长期状态；
状态表只登记由审计产生的缺口。

## 1. 审计范围

| 用例 | 关注点 | 高风险类别 |
|:--|:--|:--|
| `InitFixSnapshotTimingTest` | 存活实例枚举补齐 | 依赖 native 堆遍历 |
| `initFixOracle`（48 场景 / 318 断言） | 切片安全门 | 断言"拒绝"这类未发生事件 |
| `LayoutGateWiringTest` | 布局门接线（`decideLayout`） | 纯函数，易写成自证 |
| `FieldDescriptorMaskTest` | 布局门规则表（`LayoutGate.check`） | 同上 |
| `LocalClassGuardTest` | 局部类位移止血 | 断言"拒绝" |
| `HashSemanticsTest` | 新增哈希语义门 | 断言"检出" |
| `ConstructorTailInjectorTest` / `StageASkeletonTest` | 两阶段组件（已冻结） | 纯字节码变换 |
| `remold.LibToolTest` | JVMTI 堆遍历 | 依赖 native、断言"包含" |
| `CompeteDeleteTest` / `LocalClassNameDriftTest` | 匿名类对齐 | 断言"未发生" |

纯字节码解析/常量池类单测（`NameIndexTest`、`SwitchMapAlignTest`、`MethodRefForwardTest` 等）
按约定不在本次范围——它们的断言是精确值比较，结构上不可能空转。

## 2. 变异检查结果

每次只改一处，跑完立即还原，`git status` 收尾必须为空。

| # | 注入的破坏 | 运行的用例 | 结果 |
|:--|:--|:--|:--|
| A | `InitFix.applyPatch` 里 `alive = collectInstancesForPatch(host)` 改成空表 | `InitFixSnapshotTimingTest` | **红**（`instanceCreatedAfterSnapshotIsStillPatched`） |
| B | `LayoutGate.check` 首行恒返回 `COMPATIBLE` | `FieldDescriptorMaskTest` | **红**（`namedInnerClassRefNotMasked`） |
| C | `LayoutGate.checkChangedFields` 首行恒返回 `COMPATIBLE` | `LayoutGateWiringTest` | **红，7/7** |
| D | `LocalClassGuard.collisions` 恒返回空表 | `LocalClassGuardTest` | **红，5 条** |
| E | `InitFix.checkSafe` 首行恒返回 `null`（永不拒绝） | `initFixOracle` | **红，124/318 断言失败** |

两点值得记下来：

1. **变异 B 最初跑的是 `LayoutGateWiringTest`，结果全绿。** 这不是用例空转，而是两者守的不是同一个函数：
   `decideLayout` 走 `checkChangedFields`（`changedFields` 字符串协议），`LayoutGate.check` 走字段列表。
   前者由 `LayoutGateWiringTest` + 变异 C 覆盖，后者由 `FieldDescriptorMaskTest` + `layoutGateAssert` 覆盖。
   教训：**变异必须打在用例真正依赖的那个函数上**，打偏了会误判成"用例无判定力"。
2. 变异 E 打掉安全门后 `initFixOracle` 失败 124/318，说明"拒绝"这类否定断言并非空转——
   它们会随安全门一起失效。

## 3. 发现的缺口

### 3.1 `LibTool.getInstances` 的匹配范围无测试（已补）

原 `heapScanFindsLiveInstances` 只断言 `length >= 2` 且返回数组包含 d1/d2，属于**下界**断言：
它能发现"少返回"，但发现不了"多返回"或"匹配范围偏移"。而匹配范围直接决定补丁循环会写到哪些对象上：

- 若父类查询**不含**子类实例 → 子类实例的新增字段永久停在默认值（漏补）；
- 若子类查询**含**父类实例 → 补丁会写进一个根本没被重定义的类（`WrongMethodTypeException` 或静默错写）。

新增 `heapScanIncludesSubclassesButNotSuperclasses` 双向断言，实测通过：
`getInstances(P)` 含子类实例，`getInstances(C)` 不含父类实例。

这条同时把「单次堆遍历按类分发」那个待办方案的语义固定下来了——它必须复现这个**不对称**，
而不是简单地"按 klass 精确匹配"。

**局限**：该断言的方向性来自 native 实现，本地无法注入破坏（不能改 C++ 后重编），
因此没有拿到"去触发"的红。它属于**断言了终局状态**的守卫，不是经过红绿验证的守卫。

### 3.2 记录但未修：`heapScanFindsLiveInstances` 的下界断言

`assertTrue(instances.length >= 2)` 保留原样。改成 `== 2` 会与"堆里可能有其它测试遗留实例"耦合，
而判定力已由 `contains(d1)`/`contains(d2)` 与 3.1 的双向断言承担，收益不足。

## 4. 不采纳的方向

**JIT 断言后果（哨兵值校验）**：原计划用"旧字段哨兵未被破坏"来断言陈旧分配点真的被执行过。
但本项目无法给 DCEVM 注入 bug，该断言永远见不到红；它只能算"断言了后果的探针"，不具判定力。
`07-p05-probe-results.md` §11 已记录 JIT 场景的对照组证伪结论，此项**挂起，不再投入**。

## 5. 结论

抽查的 5 条机制全部拿到红，无空转用例。审计产出一个真实缺口（3.1），已补测试；
产出一个待办方案的语义约束（子类不对称）。四项待办（JIT 两种对照变体、JIT 断言后果、
从未红过的测试审计、STW 内外拆分）就此闭环。

## 6. 补记：`LambdaAligner` 侧的计数型用例（范围外的第二批）

第 1 节的范围只覆盖 InitFix 一侧。全量扫 41 个 JUnit 类的 318 个用例后，`LambdaAligner`
一侧另有 3 个用例的**全部断言都落在内部计数器上**，另 1 处恒真断言。按"计数器不是终局"
的判据，它们在被测代码返回错误字节码时仍可全绿。

### 6.1 改动

| 用例 | 原断言 | 补充的终局断言 |
|:--|:--|:--|
| `PassBTest#passBCounterIsReachable` | `passBPairs > 0`、`passAPairs == 0` | 老 callsite `lambda$build$0()V` 仍是活方法、执行新语义 `act2()` 且不再调 `act1()`；`build() -> 容器 -> 叶子` 链完整；零幽灵；产物 name+desc 唯一 |
| `NameIndexTest#secondRoundUsesNameIndexWithoutGhostCollision` | `step1Pairs == 2`、`passAPairs == 0` | 第一轮产物含"同名活方法 + 同名幽灵"基线；第二轮产物与第一轮**逐字节相同**；活叶子非幽灵且体未被换掉；幽灵恰好 1 个；产物 name+desc 唯一 |
| `HostAnchorTest#parentEditedAndStatementDeletedDegradation` | `anchorPairs == 0`、`hashMismatchPairs > 0` | 被删语句的旧父级 `$3` / 旧叶子 `$2` 各留一个孤儿桩且不再执行业务体；新容器执行 `x2_mod()` 而非旧的 `x1()`；容器 indy 仍指向叶子；产物 name+desc 唯一 |
| `InterfaceLambdaMarkerTest#transformsAndLinksInterfaceLambdas` | 末尾 `assertTrue(true, ...)` | 改为统计 `consume()` 实际执行次数，断言两个 lambda 各执行一次（夹具新增 `consumed` 计数器） |

### 6.2 变异检查

注入点统一在 `LambdaAligner.align` 的最终返回处：把 `return resurrectOrphanedLambdas(...)`
换成 `return newBytes`——**统计照记，但对齐结果丢弃**。这正是"计数器全绿、产物全错"的形态。

| # | 注入的破坏 | 结果 |
|:--|:--|:--|
| F | `align` 丢弃对齐结果（统计照记） | `HostAnchorTest` **红**（幽灵数为 0）、`NameIndexTest` **红**（基线幽灵数为 0） |
| F' | 同上，但只跑 `PassBTest` | **绿** —— 见 6.3 |
| G | `LambdaFixture.consume` 改成空实现（lambda 体不执行） | `InterfaceLambdaMarkerTest` **红**（`consume` 计数停在 0） |

### 6.3 变异 F' 暴露的夹具缺口：`passB` 是一次恒等配对

`passB` 夹具（v1 单个叶子 `() -> act1()`，v2 包一层并改成 `() -> act2()`）下，javac 的
lambda 编号是**后序**的：容器先被包住、叶子最后编号，因此 v2 的新叶子本来就落在旧叶子的
槽位 `lambda$build$0` 上。实测对齐产物与 v2 **逐字节相同**，`passBPairs` 记到的是一次
"同名 → 同名"的恒等配对（`renameMap` 为空）。

后果：该用例的终局断言只能钉住"终局形态正确"（错误改名会红），**不能**证明"Pass B 真的
完成了一次改名"；`align` 完全不做对齐时它也全绿。要覆盖后者，需要另建一个"新叶子落在
不同槽位"的夹具。该限制已写进 `PassBTest` 的类 Javadoc。

### 6.4 结论

三条计数型用例补上终局断言后，两条（`HostAnchorTest`、`NameIndexTest`）在变异 F 下变红，
拿到判定力；`PassBTest` 受夹具形状限制仍只有计数器意义，缺口登记在 6.3。
`InterfaceLambdaMarkerTest` 的恒真断言换成"执行次数"断言后，变异 G 下变红。

## 7. 补记：`LambdaAlignTortureTest`（旧 callsite 归属的自动断言）

### 7.1 为什么不需要真 reload

老 callsite 是按**方法名**绑定到合成方法上的：重定义之后，旧实例再调用 `lambda$build$N`
时命中的是新类里的同名方法。所以"编号错位"的故障形态就是"同一个名字指向了别人的 body"，
这纯字节码可判：

1. 从旧类解析每个 `lambda$*` 的逻辑身份（体内 tag 常量 `"A:v1"` → `A`，字符串拼接的
   recipe `"B:v1:\u0001"` 同样取 `B`；容器 lambda 用"包着哪个 tag"即 `via:D` 定身份）；
2. 在 `LambdaAligner.align` 的产物里按**同名同描述符**取身份；
3. 两者必须相等。版本号不参与比较——改 body 合法，改名字才不合法。

### 7.2 矩阵与新增覆盖

| 变体 | 改动 | 期望 |
|:--|:--|:--|
| V2 | 只改 body | 名字不动，身份不变（基线） |
| V3 | `build()` 最前面插入新 lambda | 编号整体后移，产物必须把每个旧名字还原到原逻辑 |
| V4 | 删掉 B、交换 C/D 顺序 | 被删的 B 必须降级成孤儿桩；C/D 必须换回来 |

每个变体跑 javac 8/17/21（嵌套 lambda 在 JDK 8 叫 `lambda$null$N`，命名规则不同）。

两个此前**没有任何测试碰过**的载体也在矩阵里：静态初始化 `lambda$static$N` 与字段初始化
`lambda$new$N`（`extractLogicalName` 剥掉末尾数字后自然成组，但一直没被验证）。

### 7.3 变异证据

| # | 注入的破坏 | 结果 |
|:--|:--|:--|
| H | `align` 丢弃对齐结果（`return newBytes`） | **红**：`JDK8 V3(前插 Z)：lambda$build$9(...) 在产物里消失了` |
| H' | 内置变异 `remold.mutation=C`（决胜键改回方法表下标） | **绿** —— 该夹具下没有触发决胜键的分支 |

H 打在"名字消失"那条断言上。为了证明"名字还在但 body 换了"那条断言也有牙，
另加自检用例 `checkerDetectsSwappedBodies`：把产物里两个同描述符、不同身份的 lambda
名字对调，`checkAligned` 必须抛 `AssertionError` 且消息落在"换了 body"那条上。
这条自检同时挡住"身份解析退化成 `null == null` 恒绿"的风险（另有 `identified >= N` 下界兜底）。

### 7.4 局限

- 它**不替代**真 reload：真实 redefine 下"旧实例 → 新 body"的完整链路（含 JIT 陈旧分配点、
  `$deserializeLambda$` 反序列化）仍需 `LiveDcevmTest` 那一层。序列化往返目前仍是全仓库
  零覆盖（`ShiftTest` / `UpDepthTest` 碰到 `$deserializeLambda$` 主动跳过）。
- 身份解析依赖"每个 lambda 体内有 tag 常量"。没有 tag 的 lambda（本夹具里两个同体
  `"same"`）只能断言"名字还在、没被幽灵化"，无法区分归属。

## 8. 第二批定点用例与一条已知缺口

### 8.1 先把判据的语义钉死（此前文档没有集中写）

| 概念 | 实际定义（源码位置） |
|:--|:--|
| `upDepth` | 该 lambda 被多少层 lambda **引用**：被普通方法引用 = 0；只被 lambda 引用 = 1 + max(引用者深度)。由反向 indy 引用关系迭代定稿，`-1` 是未定稿哨兵（`LambdaAligner` 计算块） |
| `shape` | **不是捕获参数**，而是子树形状：`"(" + 排序后的子 shape + ")"`，叶子为 `"()"`。只由 indy 引用拓扑决定，与方法体内容无关（`sameNestingLevel`） |
| 分组 | `compositeHash(logicalName, normalizedDesc)`；`logicalName` = 方法名剥掉所有紧跟在 `$` 后的数字（`lambda$g1$12` → `lambda$g1$`）；`normalizedDesc` 会把实例方法的 `this` 显式前置成首参 |
| `L:hash` 锚点 | `"L:" + 方法体指纹 + ":" + 消费者`，**不是**"内容哈希生成的稳定名字"；宿主是普通方法时键是 `"M:方法名+描述符"`，所以**改宿主方法体不影响锚点**，改宿主方法名才会整组换键 |
| 决胜键 | ① 从根方法可达（`callOrder >= 0`）优先于不可达；② 均可达按 BFS 发现序（根方法名+描述符升序、指令流序）小者胜；③ 均不可达按方法表下标兜底（`isBetterCandidate`） |
| Step 1 证据 | 同组同 hash + 同名/同指纹；跨组认领只靠指纹。**Pass A / Pass B 不校验指纹**，只校验签名、子树形状与子归属 |

由此纠正三个常见误解：`shape` 与捕获无关；`L:hash` 不含宿主方法体；三个"只差常量"的 lambda
**指纹不同**（`MethodFingerprinter` 把 `LDC` 常量算进指纹），所以它们不是平局。

### 8.2 新增用例（`LambdaAlignTortureTest`，第二族夹具 `Stress`）

| 用例 | 变异 | 结果 |
|:--|:--|:--|
| S1-a | `g1` 最前面插入新 lambda，其余不动 | 通过：每个旧名字仍绑在自己的逻辑 lambda 上 |
| RS-p | 只删 A2 | 通过：A2 的槽位留孤儿桩 |
| XG-a | 宿主 `g4` 改名 `g4x`，body 不变 | 通过：整组换键后仍被指纹认领回原槽位 |
| PA-a | B1 被新 lambda 包一层（upDepth 0→1），描述符不变 | 通过：Pass B 兜底，老名字仍指向 B1 |
| 确定性 | 同一对输入对齐 10 次 | 通过：逐字节一致 |
| 计数账 | `step1 + passA + passB + 幽灵数 == 旧 lambda 数` | 通过；同时断言 `hashMismatchPairs <= 配对总数`（它是叠加标记，不是独立一趟） |

矩阵跑 javac 8 与 21；变体只改结构、**不动 tag 版本**，把"结构编辑"与"body 编辑"两种变量隔离开。

### 8.3 已知缺口：删掉一个 lambda 的同时新增同描述符的 lambda

`RS-a`（删 A2 + 追加同描述符的 A9）在 JDK 8 与 21 上都复现同一形态：**A2 的槽位没有留下孤儿桩，
被别的 lambda 顶替**。老 callsite 于是静默跑进别人的 body，且 `warnPositionalMismatch` 不响
——它的判据是 `ni.name != bestOld.name`，这里恰好同名。

成因：Pass A / Pass B 不校验指纹（兜底对齐的设计前提），删一个、加一个之后，新方法在旧组里
按位置找到了形状相同的旧方法，"名字没变"被当成"原地更新"。

用例 `deletedSlotCanBeReusedByNewLambda` 以 `@Tag("known-limitation")` **钉住当前实际行为**，
不是期望行为；修好之后该用例必须变红并改成正确断言。

### 8.4 同一根因的第二例：把叶子包进新容器（sh_c）

`P0 = () -> "P0:v1"`（depth 0、shape `()`、`()String`）改成
`P0 = () -> { c = () -> "P0c:v2"; return "P0:v2:" + c.get(); }`。

**javap 核对**：V2 的两个方法**都是** `()Ljava/lang/String;`（outer 体是 `return "P0:v2:" + c.get()`，
返回 String）。所以旧槽位 `lambda$build$0()String` **可以**承载新 outer，理想结果
`P0:v2:P0c:v2` 物理可行。

实测产物：

```
旧： lambda$build$0()String = P0
产物：lambda$build$0()String = P0c     ← 旧实例会输出 "P0c:v2"，而不是 "P0:v2:P0c:v2"
```

选错的机制：Pass B 放宽深度但**保留 shape**——新 outer 的 shape 是 `(())`，与旧 P0 的 `()`
不等被淘汰；新 inner 的 shape 是 `()`，相等，于是被选中。本来有一个现成的区分信号被丢掉了：
旧 P0 与新 outer 的 `hostKey` 都是 `M:build(...)`（同一个宿主方法），新 inner 的 `hostKey` 是
`L:<outer 指纹>:...`（宿主是 lambda）。

另外**一条告警都没有**：javac 后序编号让新 inner 恰好也叫 `lambda$build$0`，与旧 P0 同名，
`warnPositionalMismatch` 的 `ni.name != bestOld.name` 不成立。

### 8.5 裁定：这是**选错**，不是类型系统逼的

- 8.4 的旧槽位本来可以承载新 outer（同描述符），理想值物理可行，所以这不是"只能二选一"的降级，
  而是 Pass B 在"shape 相等"与"宿主相同"之间选错了优先级的**配对错误**。
- 修正方向（不建议和告警合并改）：Pass B 里让"同 `hostKey`、同组、同描述符、shape 不等"的候选
  优先于"shape 相等、但 `hostKey` 前缀翻转（`M:` ↔ `L:`）"的候选。
- 告警的触发条件也不该用"`sameSemantics` 为假"——指纹含 `LDC` 常量，最普通的
  `"A1:v1"`→`"A1:v2"` 就会满足，会大面积误报。更准的信号是**`hostKey` 前缀翻转**：
  sh_c 满足（`M:` → `L:`），UD-a 的 innermost 两侧都是 `L:` 不满足，普通常量替换两侧都是
  `M:` 也不满足。

### 8.6 第三例：重命名捕获的局部变量（gp_e）

可序列化 lambda 的 `lambda$m$<hex>$N` 里 hex 由合成方法签名（含 javac 推导的参数名）算出，
重命名捕获变量会改 hex → `logicalName` 变 → 掉出原组。实测（javac 21）：

```
旧： lambda$m$b48b8a2c$1(Ljava/lang/String;)Ljava/lang/String; = G1
产物：lambda$m$b48b8a2c$1(Ljava/lang/String;)Ljava/lang/String; = G1   ← 跨组指纹认领把它拉回了原槽位
```

用例 `renamingCapturedLocalKeepsSlotViaCrossGroupClaim` 固化这条路径，并先断言两侧 hex 确实不同
（否则用例测不到东西）。

## 10. 真实 redefine 实测（首次拿到运行期证据）

夹具来自外部用例包 `lambda-align-seq`，用 JBR-21 + `remold-agent.jar` + `-XX:+AllowEnhancedClassRedefinition`
跑真 redefine：覆盖 `T.class` 触发文件监听，`Run` 持有 V1 的旧实例并在 reload 后重新调用。

### 10.1 sh_c：旧实例确实跑进了新 inner

```
== OLD instances (after reload)   [T.version=V2]
  P0    P0c:v2                       OBSERVE (ideal: P0:v2:P0c:v2)
== FRESH instances   [T.version=V2]
  P0    P0:v2:P0c:v2                 ok
```

与字节码层的预测完全一致：旧实例丢掉外层逻辑，直接返回内层的值。**这是运行期确认，不再是推断。**

### 10.2 ud_a：最内层正确，外层两个都抛 NoSuchMethodError

```
== OLD instances (after reload)   [T.version=V2]
  P2    THROW java.lang.NoSuchMethodError: 'java.lang.String T.lambda$build$2(java.util.Map)'
  P2m   THROW java.lang.NoSuchMethodError: 'java.util.function.Supplier T.lambda$build$1(java.util.Map)'
  P2ii  P2ii:v2                      ok
```

两点：

- 最内层 `P2ii` 保住槽位，与预期一致。
- 旧 outer / 旧 middle **抛 `NoSuchMethodError` 而不是命中孤儿桩**。javap 核对：V1 是
  `$2(Map)String`、`$1(Map)Supplier`、`$0()String`，V2 只剩 `$1(Map)String`、`$0()String`；
  产物里（`synthetic:` 一行）也只有这两个方法——**没有桩**。而直接调用 `LambdaAligner.align`
  的字节码夹具里是**有**桩的（见 8.4）。两条路径不一致，需要单独查（怀疑 agent 侧对
  schema-change 轮次做了裁剪），在查清之前不要把"一定留桩"当成运行期保证。

### 10.3 ak_e_ctrl：同指纹 T1/T2 未错配

```
== OLD instances (after reload)   [T.version=V2]
  T1    T1:v1:L8                     ok
  T2    T2:v2:L9                     ok
```

注意这一轮 `[DIFF]` 里只有 `lambda$build$1` 被 Modified、没有 Added——**没有任何重编号**，
所以它只证明了"没串"，没证明"重排后还能对"。

### 10.4 复现这套 E2E 的三个坑（Windows）

1. `run_case.sh` 用 `mkfifo` 喂 stdin，Git Bash 的 FIFO 在原生 `java.exe` 里读会报"句柄无效"。
   改成普通管道 + 后台定时 `cp` 即可，不需要人手。
2. agent 的 premain 需要 `--add-exports java.base/jdk.internal.loader=ALL-UNNAMED`
   （否则 `MountManager.<clinit>` 抛 `IllegalAccessError`），`jdk.internal.misc` 同理。
3. `-javaagent:jar=<watchDir>` 里的路径必须是 **Windows 形式**；传 `/tmp/...` 会被解析成
   `F:\tmp\...`，agent 直接报 "Skipping invalid watch path"。

### 10.5 根因：`align` 收到的"旧"字节码就是新字节码

在 `align` 入口与 `redefineClasses` 前各落一份字节码（临时诊断，已还原），实测：

```
[LambdaAligner] ENTER old=2586B new=2586B
enter-old == V2/T.class        （逐字节相同）
V1/T.class = 2831B
```

而直接调 `LambdaAligner.align(V1, V2)` 的产物是 **2875B、4 个方法（含 2 个幽灵桩）**；
agent 路径下 `align` 返回 **2586B、与 V2 逐字节相同**。

也就是说：**运行期"旧实例跑错"的根因不是"对齐选错了候选"，而是"对齐拿到 old==new、什么都没做"。**
sh_c 的 `P0c:v2`、ud_a 的 `NoSuchMethodError`，都是"直接把 v2 原样字节交给 redefine"的结果。

代码线索：`RemoldAgent.java:1682` 取 `oldSrc = sourceBytecodeCache.get(className)`，
而 `sourceBytecodeCache` 在 1537 行被"本轮新字节"写入；若该写入先于 lambda 对齐发生，
旧基线就被新字节覆盖。**调用顺序尚未确认**，这是下一步要钉的点。

对前面几节的影响：

- 8.4 / 8.5 的"Pass B 选错候选"是对**字节码层夹具（真 V1 → V2）**的结论，仍然有效；
  但它**不能用来解释运行期现象**——在 old==new 的前提下 Pass B 根本没被调用。
- 10.2 里"运行期没有桩"同理：不是桩被裁掉，而是压根没走到生成桩的代码。
- "认领不上就留桩"仍然只是字节码层的性质，运行期保证要等这个 bug 修完才能谈。

**10.1~10.3 的运行期结果应重新定性为"没有对齐的基线"**：sh_c 的 `P0c:v2` 是纯 redefine
按 javac 编号落槽的结果；ud_a 的 `NoSuchMethodError` 是纯 redefine 删方法后的自然结果；
ak_e_ctrl 的"没错配"什么也没证明。它们现在的用途是：修好之后重跑，差异就是对齐器真正带来的改变。

### 10.6 顺序确认与修复

在写入点与读取点各打一条日志（带 `identityHashCode`），一次 E2E 就钉死了顺序：

```
[BASE-WRITE] T id=1711979227 len=2586
[BASE-READ]  T oldSrc=id=1711979227 len=2586 equalsNew=true oldBytecode=id=2129401193 len=2831
```

写和读是**同一个数组实例**，而且写在前——`sourceBytecodeCache` 被本轮新字节覆盖后才被当作
"旧基线"读回来。`git log -L` 显示这段写入自首个提交就在，不是回归。

修法（`RemoldAgent.processChanges`）：

1. **基线改用 `bytecodeCache`**（上一轮**成功 redefine** 后由 `tx.commit()` 推进的那份），
   源码缓存只作 fallback。这同时满足"缓存只在成功后前进"——失败走 `rollback`，`bytecodeCache`
   不动。
2. 写 `sourceBytecodeCache` 前先快照旧值 `prevSourceBytes`，供 `oldResolver` 使用。
3. 加一条响亮失败：`alignOld` 与 `newBytecode` 逐字节相同时 warn
   "baseline == new bytes … alignment had nothing to align"，这类问题以后不会再静默。

### 10.7 修复后的实测（同一批夹具）

| 用例 | 修复前 | 修复后 |
|:--|:--|:--|
| ud_a | 旧 outer/middle 抛 `NoSuchMethodError`，产物只有 2 个方法 | 产物 4 个方法（含 2 个桩）；旧实例命中桩，日志 `orphaned lambda invoked: T#lambda$build$2(...)`，返回 null；`P2ii` 仍 ok |
| sh_c | `P0c:v2`（纯 redefine） | 仍 `P0c:v2`，但这次是**对齐器的决定**，与字节码层结论一致 |
| rs_revive 第 2 轮（删 A2 后加回） | A2 串成 `A3:v1`、A3 抛 `NoSuchMethodError` | A1/A3 正确；A2 命中桩返回 null |

`rs_revive` 第 2 轮证明了基线必须用运行期那份：源码基线不含上一轮注入的桩，与 JVM 里的真实形态脱节。

### 10.8 遗留：RS-b（删了再加回来）不会复活旧槽位

`rs_revive` 的 V3 源码等于 V1，理想结果是旧 A2 恢复 `A2:v1`。实测：新 A2 拿了**新槽位** `$3`，
旧槽位 `$2` 仍是桩，旧实例返回 null。

原因：`acceptCandidate` 明确排除幽灵（`oi.ghost → false`），幽灵不参与任何匹配趟；
而"锚点复活"路径没有把这个新 lambda 认领回幽灵的槽位。这条是**未实现**，不是回归。

### 10.9 回归探针：`alignBaselineProbe`（覆盖 agent 调用路径）

`LambdaAligner.align` 有大量单测，但"agent 传进来的是什么"一直没覆盖——这正是 339 个用例
全绿却漏掉 10.5 的原因。补 `tests/src/AlignBaselineProbe.java` + `:tests:alignBaselineProbe`
（JavaExec，带真 agent 与 `-XX:+AllowEnhancedClassRedefinition`，需要 `-Pp05.jbr=`）。

探针自己覆盖 `live/T.class` 跑两轮（v1 → v2 删 A2 → v3 加回 A2），用
`LambdaAligner.LAST_ALIGN_OLD_HASH` 断言：

| 断言 | 目的 |
|:--|:--|
| 第 1 轮 old == 已加载的 v1 | 基线确实是"JVM 里那份" |
| 第 1 轮 old != new | 正是 10.5 那个 bug 的形态 |
| 第 2 轮 old != v2 源码 | 源码缓存不得被当作基线（它不含上一轮的孤儿桩） |
| 第 2 轮 old != new | 同上 |

首轮实跑四条全过。探针**没有挂进 `check`**（需要 JBR，与 `p05Probe`/`twoPhaseProbe` 同类）。

### 10.10 探针顺带暴露：多轮序列里**未被删除的** lambda 也会丢槽位

同一轮 v3 里，旧 A1 实例返回的是 `A2:v3`（探针输出 `v3 held A1 = A2:v3`）。
A1 从头到尾没被删过，它的槽位却被新 A2 占了；日志里同时出现两条
"顺序回退配对但方法体不一致"的 warn（旧 `$0` ← 新 `$1`、旧 `$1` ← 新 `$2`）。

也就是说 10.8 的"复活未实现"只是表象，更严重的是**位置回退在多轮下会把仍在的 lambda 挤走**。
这条已由探针稳定复现，待修；优先级高于 RS-b 的复活语义。

### 10.11 批量 E2E 结果（修复后，对齐确实在跑）

全部在 JBR-21 + `remold-agent.jar` 下跑，夹具来自 `lambda-align-seq`（11 个用例）。

| 用例 | 结果 | 说明 |
|:--|:--|:--|
| `rs_del` | A1/A3 正确；A2 → `null`（桩） | 理想是 `A2:v1`，即旧 body 保留；当前是桩 |
| `rs_cycle` | **4 次 FAIL**：每个"满"版本 A2 都是 `null`；**方法数单调增长 3 → 5 → 5 → 6 → 7** | 复活未实现 + 幽灵槽位不回收，删/加循环下方法表无界增长 |
| `ak_e_swap` | 全 `ideal` | 同指纹换序没有串 |
| `gp_e` | `ideal`（槽位名保持 `lambda$build$220b63a5$1`，行号跟到新位置） | 跨组指纹认领在运行期生效 |
| `sg_a` | S1 → `null`（OBSERVE，理想 `S1:v1:1`） | 签名不兼容时旧实例被幽灵化 |
| `sg_b`（开关 false） | S1 → `null`（OBSERVE） | — |
| `sg_b`（开关 true） | S1 → `null`（OBSERVE）；V1 里两个方法都是 `[s]`，force-static 生效 | 开关本身工作正常 |
| `ud_b` | `W` 得 ideal（`X:diff`）；`Wi`/`X` 为观察值 | 共享槽位一对二，物理上只能满足一个 |

三条缺陷按严重程度：

1. **`rs_cycle`：幽灵槽位不回收 + 复活未实现**（多轮必现，方法表无界增长）。
2. **多轮 + body 变化时，未删除的 lambda 也会丢槽位**（10.10 探针：`v3 held A1 = A2:v3`）。
   触发条件是"每轮 body 都变"导致指纹全变、只能走位置回退。
3. **签名不兼容时旧实例被幽灵化**（`sg_a`/`sg_b`）。理想值要求"旧 body 保留"，而当前实现
   只做改名、不保留旧 body，所以这个理想值在现有语义下不可达——属于**需要裁定的策略**，
   不是纯 bug。

### 10.12 force-static 给已带 receiver 的静态 lambda 又加了一个 receiver（sg_b 根因）

`sg_b(true)` 里 S1 变成 `null`，此前被归为"签名不兼容，只能留桩"。**不是**——开 force-static 时
两侧归一化后都是静态 `(T)String`，本该配上。dump 出 `alignOld` 才看清：

```
V1 原始:   $0()Object（实例，隐式 this）   $1(T)Object（静态，源码里写了 self.base）
alignOld:  $0(T)Object                      $1(T,T)Object   ← 多了一个 receiver
```

`AnnotationTransformer.forceStaticLambdas` 的 `needForceCaptureThis` 分支只判断
"静态 + 引用点都在实例方法里"，**没判断它是不是已经带显式 receiver**。于是：

- 隐式 `this.x`（javac 发实例 `()`）→ 转成 `(T)`；
- 显式 `self.x`（javac 发静态 `(T)`）→ 又前置一个 `this` → `(T,T)`。

同一个 lambda 的两种等价写法得到两个不同描述符。`sg_b` 里 V1 的 S1 是前者、V2 的 S1 是后者，
于是旧 `$0(T)` 在新类里没有对应物 → 幽灵化 → 老 CallSite 静默返回 null。

**修法**：`needForceCaptureThis` 追加"零捕获"条件（`indy` 捕获参数个数为 0）。不变量变成
"每个 lambda 恰好一个 receiver"，与源码写法无关。修复后 `sg_b(true)` 的旧实例全部 `ok`。

### 10.13 多轮"前插 + 全量改常量"：自造名被当成 javac 原名（ed_ins 根因）

`ed_ins` 每轮在 `build()` 最前面插一个新 lambda 并改所有常量，旧实例在第 2、3 轮整体串位：

```
V3 轮:  A1 → Z1:v3    A2 → A1:v3    A3 → A2:v3      （3 条 WARN，但照样错）
```

根因不在"顺序回退"本身，而在**上一轮自造的名字被当成了 javac 原名**：

- 第 1 轮：旧类占着 `$0..$2`，新 lambda Z1 拿到避障槽位 `$4`；
- 第 2 轮：javac 从头编号，A3 恰好叫 `$4`；"同名优先"就把新 A3 配给了旧 `$4`（其实是 Z1），
  于是后续全部位移一格。

**修法**：`align` 新增 `inventedOldNames` 入参 + `LAST_INVENTED_NAMES` 出参；自造名不再作为
"同名 = 同一个 lambda"的证据（除非指纹也一致）。自造名集合从**最终产物**（含复活阶段注入的
幽灵）统计，并由 agent 在 redefine 成功后才落账（失败不回滚基线，也就不推进集合）。

修复后 `ed_ins` 四轮全绿（旧 A1/A2/A3 每轮都拿到自己的最新 body）。

### 10.14 残留：多出来的那个新 lambda 仍会把上一轮新引入的槽位顶掉（信息论边界）

`ed_ins` 第 2 轮的真实形状（javac 8/11/17/21 实测一致）：

```
old: [$2=A3 $1=A2 $0=A1 $4=Z1]       4 个旧槽
new: [$4=A3 $3=A2 $2=A1 $1=Z2 $0=Z1] 5 个新 lambda（前插了 Z2）
out: [$2=A3 $1=A2 $0=A1 $4=Z2 $5=Z1] ← $4 本该是 Z1
```

A1/A2/A3 靠"两边方法表头对齐"对上了（这是 javac 按源序降序发射合成方法的实现细节，
**不是语义保证**）；剩下的 1 个旧槽面对 2 个新候选，两者 body 都变了、名字都被重排过，
**没有任何字节码级证据**能区分"Z1 换了号"和"Z2 是新来的"。按顺序猜就是把 Z2 放进 Z1 的槽位：
第 1 轮创建、跨 reload 持有的 Z1 实例会安静地执行 Z2 的方法体。

也就是说 10.13 的修复**消除了 A1/A2/A3 的错配，但把错配挪到了上一轮新引入的槽位上**，
没有根治。用例 `surplusSlotGetsDisplacedByOrderHeuristic` 以 `known-limitation` 钉住现状。

正确方向是**拒绝猜**：候选不唯一（1 旧 × N 新 且无指纹佐证）时留孤儿桩，让老 CallSite
抛 `NoSuchMethodError`，而不是静默跑别人的体。这会改变 `ed_ins` 的期望值（从"每轮都对"
变成"要么对、要么响亮失败"）。

**最终裁定见 §10.15：不做这个改动，保留现状**（拒绝猜会让最常见的"追加 + 改常量"也炸，
而真实场景里 Step 1 的指纹匹配已经先兜住绝大部分情况）。

### 10.15 镜像缺陷：**末尾追加** + 改全部常量，旧槽整体位移一格

比前插更常见的编辑，实测（javac 21，`App` 夹具）：

```
v1 : [$2=A3 $1=A2 $0=A1]
v2 : [$3=B1 $2=A3 $1=A2 $0=A1]   ← 只追加 B1，A1/A2/A3 的 javac 编号根本没变
out: [$2=B1 $1=A3 $0=A2 $4=A1]   ← 三个旧槽全部往下挪一格
```

成因与 10.13 同一处：javac 按源序**降序**发射合成方法，末尾追加的 `B1` 反而排在方法表首位；
配对循环按方法表顺序跑，`B1` 第一个被处理，它没有同名旧方法，走"取第一个未匹配旧方法"的
兜底把 `$2(A3)` 抢走，随后多米诺式位移。

**这条与 10.14 是镜像的，且没有一条规则能同时修好两条**：

| 场景 | 关键一步 | 同名？ | 方法体同？ | 实际是否同一个 lambda |
|:--|:--|:--|:--|:--|
| 末尾追加 | `new $2(A3) ↔ old $2(A3)` | 是 | 否 | **是** |
| 前插+重编号 | `new $2(A2) ↔ old $2(A3)` | 是 | 否 | **否** |

两行的字节码特征完全相同（同名、描述符相同、体不同），只能靠"哪个才是真的"来判断，
而那个信息不在字节码里。因此任何基于顺序/同名的规则都只能照顾一个方向。

三条出路（需要裁定）：

1. **拒绝猜**（符合项目"不可静默写入过期值"）：候选不唯一时留孤儿桩 → 两种场景都变成
   响亮失败，老实例抛 `NoSuchMethodError`。代价是最常见的"追加 + 改常量"也会炸。
2. **选定方向做启发式**：按方法表顺序（照顾前插）或反序（照顾追加）。必然牺牲另一方向，
   且依赖 javac 发射顺序这个实现细节（8/11/17/21 实测一致，但无规范保证）。
3. **缩小逻辑组**：让每个 lambda 的组键带上更强的身份（例如宿主方法 + 源码行号），
   从根上把"多个同形 lambda 抢一个槽位"变成不可能。改动最大，但唯一能真正根治。

**裁定（2026-10-31）：走 (2)，保持当前方向（按方法表顺序，照顾前插），两条 known-limitation
用例保留、不改行为。** 理由：

- **行号破不了平局**（否定 (3) 的前提）：前插代码会让后续行号整体下移，新插入的 lambda
  恰好占掉旧 lambda 的行号；而行号的相对次序与现有方法表逆序 **100% 同构**，信息增量为 0。
- **真实场景里 Step 1 已兜底**：末尾追加新 lambda 时，同方法内原有的其它 lambda 极少在同一轮
  被全量改方法体，它们在 Step 1 就会按指纹精确锁死槽位，不会漏到 Step 2 触发位移。
- 因此这个镜像盲区只在"**同方法 + 同签名 + 同嵌套形状的所有 lambda 全量改方法体 + 同时增删
  lambda**"时才会命中，作为 known-limitation 钉住即可，不值得为它改动配对语义。

用例 `appendingLambdaShiftsEveryOldSlot` 与 `surplusSlotGetsDisplacedByOrderHeuristic`
以 `known-limitation` 钉住现状；它们记录的是**已接受**的限制，不是待办。

### 10.16 幽灵复活：删掉又原样加回来（缺口 3 + 缺口 4 一起解决）

原先幽灵是"单向退休"：桩体被换成 `onOrphanInvoked` 空壳后，**指纹变成空壳的指纹**，
下一轮再也认不出原主。所以 `rs_revive` 第 3 轮 FAIL（A2 → null），
`rs_cycle` 每删一次就多一个永不回收的桩，方法表 3 → 5 → 5 → 6 → 7 单调增长。

**破法**：注入桩体时把"生前指纹"写进桩体本身（`LDC <long>; POP2` 标记），下一轮扫描时读回来。

- 写：`injectDummyBody(..., originalHash)` 在方法体最前面发 `LDC long; POP2`；
  已是幽灵的沿用桩体里记着的值（否则每轮都被"空壳指纹"覆盖，永远认不出）。
- 读：`readGhostOriginalHash(mn)` 只认前 3 条指令内的 long 常量，避免误把业务常量当标记。
- 配：新增 `stepGhostRevive`，在阶段二之前跑，条件是**指纹完全一致 + 描述符相同**；
  认领后新方法占用幽灵名字，`presentKeys` 里就有了它，幽灵不再被注入。
- 副作用修复：`getPureDelegate` 先剥掉这 2 条标记指令，否则转发前缀检测会把标记的 LDC
  当成 `LDC loc`，转发桩退化成空壳。

**实测（JBR-21 真 redefine）**：

| 用例 | 修复前 | 修复后 |
|:--|:--|:--|
| `rs_revive` 第 3 轮 | A2 → `null`（FAIL） | A2 → `A2:v1`（ok），方法表回到 `[$0,$1,$2]` 无桩 |
| `rs_cycle` 9 版本 | 4 次 FAIL；方法数 3→5→5→6→7 | 0 FAIL；方法数**恒为 3**；每个"满"版本都复活 |

只在指纹一致时复活：方法体改过的 lambda 不算"同一个"，宁可拿新槽位——避免"多对多误配"。

用例 `deletedLambdaIsRevivedWhenAddedBackUnchanged` 覆盖字节码层，变异（关掉 `stepGhostRevive`）
后变红，失败消息正是修复前的形态：`$1(GHOST)` 留着、A2 拿了新槽位 `$3`。

**但这条能力默认关闭**（`-Dremold.agent.ghost_revive=true` 开启），原因是仓库里有**三条既有不变量
明确按"不复活"编码**，默认开启会直接推翻它们：

| 用例 | 编码的契约 |
|:--|:--|
| `LineNumberDifferentialTest` | 具名不变量「幽灵单向退休不变量被破坏！已退役幽灵方法 X 在 Round N 被违规复活！」 |
| `HostAnchorTest.multiRoundRedefinitionLifecycle` | "V3 加回的新 L1 不会认领旧幽灵桩名字" |
| `NameIndexTest` | "第二轮必须对第一轮产物零改动"（复活会改动字节码） |

所以这是**契约变更**而不是 bug 修复，需要单独裁定。开启后的实测（JBR-21）：

| 用例 | 默认（关） | 开启 |
|:--|:--|:--|
| `rs_revive` 第 3 轮 | A2 → `null`（FAIL） | A2 → `A2:v1`，方法表 `[$0,$1,$2]` 无桩 |
| `rs_cycle` 9 版本 | 4 次 FAIL；方法数 3→5→5→6→7 | 0 FAIL；方法数**恒为 3**；"满"版本全部复活 |

另一个必须记住的实现细节：生前指纹要按 **name+desc** 取，不能按名字取。JDK 8 会产出
`lambda$null$0(II)` 与 `lambda$null$0(I)` 这种**同名不同描述符**的幽灵，按名字索引取到哪一个
取决于写入顺序，标记值会在轮次间抖动，直接破坏"第二轮零改动"。


### 10.17 可序列化 lambda：`$deserializeLambda$` 的名字常量与 switch key

这条路径此前**全仓库零覆盖**（两个死探针碰到 `$deserializeLambda$` 都主动跳过）。
失败形态是运行期 `IllegalArgumentException: Invalid lambda deserialization`——老序列化流里的
`implMethodName` 指向一个已不存在的方法。

用例 `deserializeLambdaImplNamesFollowRenames` 钉两条不变量：

1. `$deserializeLambda$` 里每个 `lambda$` 形态的字符串常量，都必须指向产物里**真实存在**的方法；
2. 按 `implMethodName.hashCode()` 分派的那个 lookupswitch，每个 key 必须能在自己的 case 块里
   找到 hash 相同的字符串（改名后没重算 key 会落到 default）。

注意 javac 会生成**两个** lookupswitch（第一个按方法名 hash、第二个按序号），
必须精确识别前者，否则会误判成 key 不匹配。

两个不变量各做过一次变异：关掉 `mapValue` 的名字改写 → 不变量 1 报"引用了不存在的方法名"；
关掉 `fixDeserializeSwitch` → 不变量 2 报"switch key 找不到对应字符串"。

**边界**：这只覆盖字节码层判据，不覆盖真 reload 后的反序列化往返（需要 JBR + agent）。

### 10.18 补上"只改常量"的断崖：骨架指纹 + consumer 偏好

此前 Step 1 的普通指纹含 `LDC` 常量，所以"改一个字符的常量"就让指纹全失效，方法掉进 Pass A/B；
而 Pass A/B 只用 (upDepth, shape, 签名, 子结构)，同一宿主方法里的多个同形 lambda 于是退化成盲盒，
只剩"方法表顺序"可猜。

补两块，都是**偏好**而非硬约束：

1. **骨架指纹 `skeletonHash`**：保留操作码序列、调用目标（owner+name+desc）、类型，
   把字面量换成"类型标签"（`String`/`Integer`/…），并忽略局部变量槽位与跳转目标。
   于是"50 行循环 + v2"与"2 行 + v2"骨架不同，而"只改常量"骨架不变。
   配对规则保守到"该骨架在新旧两侧**都只出现一次**"才配——一侧出现两次就说明没有区分度，
   交给 Pass A/B 按顺序兜底，不引入新的错配。
2. **consumer 偏好**（Pass A 内）：`consumer` 形如 `CALL:Owner.btnSave(...)`，描述"这个 lambda
   被传给谁"。同一宿主方法里的 `btnSave(...)` / `btnDelete(...)` 因此可区分——它和组 key 里的
   宿主方法名是两回事。仅在"没有同名证据"时作为次级偏好生效，不改变同名优先的既有语义。

两条各有独立用例，且都做过变异（关掉骨架趟 → 追加场景旧槽串位；关掉 consumer 偏好 →
同形 lambda 互换）：

| 用例 | 覆盖 | 变异后的失败消息 |
|:--|:--|:--|
| `skeletonDistinguishesStructurallyDifferentLambdas` | 循环 vs 常量 + 末尾追加 | `旧槽位 $1 原本承载 A2，产物里却是 null（B1 抢了槽）` |
| `consumerDistinguishesSameShapeLambdasInOneMethod` | 同方法内 `save`/`del` 两个同形 lambda，新旧相对顺序相反 | `旧槽位 $1 原本承载 D，产物里却是 S` |

注意 consumer 用例的夹具必须让**新旧两侧相对顺序相反**（v1 `save; del` → v2 `extra; del; save`），
否则"取第一个未匹配旧方法"恰好也是对的，用例就没有判定力——第一版夹具就是这样空转的。











## 9. javac 侧实测：去重条件与可序列化 lambda 命名

这两条直接决定夹具怎么编、组 key 稳不稳，此前文档里的说法不完整，这里以实测为准。

### 9.1 lambda 去重只在"不带 lines / vars 调试信息"时发生

同一份源码（两个方法返回完全相同的 lambda body），看合成方法个数：

| javac | 默认 | `-g` | `-g:none` | `-g:source` | `-g:lines` | `-g:vars` |
|:--|:--|:--|:--|:--|:--|:--|
| 8 | 2 | 2 | 2 | — | — | — |
| 11 | **1** | 2 | 1 | 1 | 2 | 2 |
| 17 | **1** | 2 | 1 | 1 | 2 | 2 |
| 21 | 2 | 2 | **1** | 1 | 2 | 2 |

结论：**阻断去重的是"显式请求 lines 或 vars"这个选项本身**，与产物里到底有没有
`LineNumberTable` 无关——JDK 17 默认产出的类里有 5 个 `LineNumberTable`，却照样去重；
`-g:lines` 才变 2 个方法。所以判据在 javac 的选项解析里，不在属性生成上。
JDK 8 在任何参数下都不去重。需要去重的夹具统一用 `-g:none`（不要用"默认"或"`-g`"，
它们在 11/17 与 21 上行为相反）。

### 9.2 可序列化 lambda 的 `lambda$m$<hex>$N`：hex 取决于局部命名上下文

以 `void m(String s) { SS x = () -> "x" + s; }` 为基线，逐项改动看 hex：

| 改动 | hex | 说明 |
|:--|:--|:--|
| 基线 | `735cc2d1` | — |
| 改捕获**值** | 不变 | 值在宿主方法的 indy 参数里，不进合成方法 |
| 重命名被捕获的局部变量 `s`→`t` | `735cc2f0` | **变** |
| 重命名赋值目标 `x`→`y` | `bd8e5c12` | **变** |
| 外层方法加一个参数 | `3641d936` | **变** |
| 重命名外层方法 `m`→`n` | `735cc2d1` | 不变（只有前缀变） |

hex 是"合成方法签名的哈希"，而这个签名包含 javac 内部推导出的参数名，所以**重命名局部变量
这类常见编辑会改变 hex**。`extractLogicalName` 只剥掉 hex 的前导数字（`735cc2d1` → `cc2d1`），
于是 `logicalName` 跟着变、lambda 掉出原组，只能靠跨组指纹认领。这是真实存在的风险面，
不是"描述符变了才换组"。

### 9.3 指纹含 body 内常量，但**不含捕获的实参值**

`MethodFingerprinter` 把 `LDC` 常量算进指纹，所以"只差 body 内常量"的两个 lambda 指纹不同；
但两个 lambda 若只有**捕获的实参**不同（body 逐字节相同），指纹完全相同——捕获值存在于宿主方法
的 indy 参数里，不在合成方法体内。这类 lambda 只能靠宿主内序列位置区分。

### 9.4 删除中间层的实测认领路径（UD-a）

夹具 `outer → middle → inner`，v2 删掉 middle（`() -> () -> x` 变成 `() -> x`）：

```
旧： $0=inner(P2ii)   $1=middle(shape (()))   $2=outer(shape ((())), tag P2)
产物：$0()String=P2ii  $1()String=P2（新 outer 拿了 middle 的名字、用自己的描述符）
      $2()String=GHOST（旧 outer 的原始键）
      $1()Supplier=GHOST（旧 middle 的原始键）
```

两条性质成立，已固化为断言：① 最内层保住原槽位（shape 相等、深度不等 → Pass B 认领）；
② 认领不上的旧层，其**原始 (名字, 描述符) 键**必须留孤儿桩，不被静默重定向。
注意"名字被复用"与"旧键留桩"可以同时成立——`$1()String` 与 `$1()Supplier` 是两个不同的键。





