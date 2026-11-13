# Lambda 捕获桥（单向）

> **文档类型**：设计规格（只写规格/决策；实现状态以 [`docs/status.md`](../status.md) 为准）

> **实现状态与验收证据**：本文件只定义规格与判据。当前落地情况、测试矩阵与已知边界见
> [`docs/status.md`](../status.md) 的管辖范围声明与 `LambdaBridgeAlignTest` / `LambdaBridgeProbe`。

---

## 1. 问题

lambda 实例是 `LambdaMetafactory` 在首次调用时现场 spin 出来的隐藏类，它<b>不复制</b>方法体，
而是在常量池里钉一条 `Methodref Host.lambda$f$0(<旧形参表>)V`，每次调用转发回宿主类。

删掉一个捕获变量后，javac 会**复用同一个编号**、把描述符缩水：
`lambda$f$0(Ljava/lang/String;I)V` → `lambda$f$0(I)V`。

`LambdaBridgeProbe` 实测（JBR 21）：redefine 之后老实例再调用，抛

```
NoSuchMethodError: 'void lam.Host.lambda$install$0(java.lang.String, int)'
```

报错文本里带的是**旧描述符**，说明解析键确实是 `name + descriptor`（JVMS 5.4.3.2），
VM 不会按名字错配到同名的新方法上；且异常抛在调用点之前，旧方法体一次都没跑。
对照组（捕获表不动、只改方法体）照常工作。

对齐器此前只能给这个旧键注入幽灵桩——对 `void` 回调而言就是"点了没反应"。
捕获桥把这种静默降级换成一次真正的转发。

---

## 2. 边界：删除 + 「可证明的新增」

**删除方向可证**：新描述符的形参表是旧形参表的<b>子序列</b>，被删掉的槽位在新方法体里
物理上不存在（验证器保证读不到）。桥做的是"丢弃一个新代码永远不会读的值"——不是伪造值，
也没有任何写入。

**新增方向分两种**：

- **纯源重放（支持）**：JLS §15.27.2 保证被捕获的局部变量在宿主方法里只有**唯一一处**
  `xSTORE`。那一处写入的"生产者"若只由下面三种之一构成，桥就能在调用时 100% 重放同一个值，
  不是编值：

  | 生产者 | 桥里重放 | 前提 |
  |:--|:--|:--|
  | 字面量常量（`ICONST_*` / `BIPUSH` / `SIPUSH` / `LDC` / `ACONST_NULL`） | 同一个常量 | 恒成立 |
  | `GETSTATIC f` | `GETSTATIC f` | `f` 带 `ACC_FINAL` |
  | `ALOAD 0; GETFIELD f` | `ALOAD 0; GETFIELD f` | `f` 带 `ACC_FINAL`，且桥自身持有 `this` |
  | **纯算式**（算术 / 位运算 / 类型转换，叶子都能折成常量） | **折成的那一个常量** | 整段能在编译期求值；深度 ≤ 4 |
  | **构造器参数 → `final` 字段**（`this.f = cfg`） | `ALOAD 0; GETFIELD f` | 宿主是 `<init>`，`f` 带 `ACC_FINAL`，且桥自身持有 `this` |
  | **已登记的运行时常量访问器**（`Scl.scl()` / `Scl.scl(float)`） | 重放同一条 `INVOKESTATIC`（带参形态先把折出来的实参压回去） | 由插件用 `RuntimeConstants.register` 显式登记；带参形态要求实参全是编译期常量 |

  **为什么"运行时常量"要显式登记**：`Scl.scl()` 这类"启动算一次、之后不变"的值，从字节码里
  看不出稳定性——那是运行期性质，只能由知道那个 API 的人断言。所以 core 只提供登记口，
  Arc 专属知识放在 `plugin-arc`（`ArcHotswapPlugin.onAgentInit` 里登记
  `arc/scene/ui/layout/Scl.scl()F` 与 `(F)F`），两个引擎（`InitFix` 的效应审查、捕获桥的
  常量合成）共用同一张表。**登记即承诺**：无参形态承诺"整个运行期返回同一个值"；带参形态
  （`scl(v) = v * scl()`）承诺"**纯函数**——实参相同则结果相同，且无副作用"。违反承诺的后果
  是"老对象看到当前值而不是当年快照"，一次可观测的行为变化。

  **带参形态为什么要求实参是常量**：桥只能重放"已知输入"。`Scl.scl(16f)` 的字节码是
  `ldc 16.0f; invokestatic scl(F)F` 两条指令，桥必须把 `16.0f` 折出来再压回去；若实参来自
  宿主形参（`Scl.scl(hostParam)`），那个值在桥执行时已经不存在，只能拒绝。

  **为什么"纯算式"是折成常量而不是把原指令搬进桥**：搬序列要同时保证栈形状、类型、操作码
  全对，任何一处错位都是验证期失败或静默错值；折叠只有一种失败模式——算不出来就放弃。
  这也正是 javac 自己对常量表达式做的事。求值语义与 JVM 逐位一致（`int`/`long` 环绕、
  `float`/`double` 的 IEEE 754，Java 17 起一律 strictfp），所以"用 Java 算一遍"是等价而非近似。

  **为什么构造器参数要经字段**：形参的值由调用方给，方法体里没有"它从哪来"。唯一例外是
  构造器把它存进了不可变字段——`final` 字段由 JVM 保证只在构造器里写一次，所以"构造完成后
  读它"与"构造时那个参数"必然相同。这正是 `InitFix.scanParamFields` 的条件 A。

  **为什么要求 `final`**：桥在**调用时**求值，而 lambda 在**创建时**捕获。非 final 字段
  可能在两者之间被改写，读它就等于编一个"看起来像"的值。`final` 保证引用/值之后不可能再变，
  于是"调用时读"与"创建时读"必然相同。（与 `InitFix` 的源字段不可变性证明同一条理由。）

- **其余一律拒绝**：宿主形参（值由调用方给，方法体里没有"它从哪来"）、算式 / 方法调用
  （依赖创建时的栈帧）、非 final 字段、继承来的字段（看不到定义）。这些情况下桥只能编
  `0`/`null` 填进去，而新方法体很可能真读它——静默写入假值。

**合成只在"两侧名字齐全"时尝试**（都带 `-g`）。没有名字就无法区分"这一位是新增的"和
"老变量换了名字"，合成会退化成猜值。名字齐全时先把仍然存在的老捕获按名字对上（沿用老实例
存下来的值），剩下的才重放——所以"删一个 + 加一个"的混合编辑也能处理。

> **语义说明**：若一次编辑实际上是"把变量 `a` 换成 `b`"（旧名消失、新名出现），按名匹配会
> 判成"删 a + 加 b"，桥把 `b` 的当前值喂给新方法体。这**不是**保留旧语义——旧体读 `a`、
> 新体读 `b`，两者本就没有共同的可保留状态。相对"幽灵桩（void 回调静默 no-op）"这仍是
> 更好的结果，但它是一次可观测的行为变化，日志里会打 `BRIDGE_SYNTH` 事件。

**明确不覆盖**（各有专门机制或本来就是缺口，不要在这里兜）：

| 形态 | 处理 |
|:--|:--|
| 捕获变量**重排**（`a + b` → `b + a`） | 支持。javac 按首次引用排序，重排会改描述符；映射由槽位证明（§3.1） |
| `static` 位翻转（`this` 隐式捕获 ↔ 显式形参） | 交给 `normalizeStaticShape`；两轴同时变则拒绝 |
| 功能接口 / SAM 变更 | 拒绝。老实例对象本身还绑在旧接口上，桥接 impl 救不了 |
| 宿主本身是 lambda（`hostKey` 以 `L:` 开头） | 不放松宿主门。父的票据哈希变了说明父自己也没配上，此时谈"同一个宿主"只是猜 |
| 目标方法被改名 | 支持：桥每轮重生成，目标取本轮配对结果 |
| 目标方法消失 | 退回幽灵桩 |
| 桥的桥（连续两轮各删一个捕获） | 支持：识别后按本轮配对重新指向，链式不累积 |
| 零捕获 → 新增捕获 | 拒绝。老实例完全没有状态，谈"同一个 lambda"没有任何依据（`LambdaAlignTortureTest` 的"删掉中间层"就是靠这条不被误接成桥） |

---

## 3. 判定：三道门 + 布局自检

对每一个"没配上对、且新类里没有同键"的老 lambda，在<b>未配对的新 lambda</b> 中找候选：

| 门 | 判据 | 不成立时 |
|:--|:--|:--|
| **SAM 不变** | 工厂描述符返回类型（功能接口）、`bsmArgs[0]`（擦除 SAM）、`bsmArgs[2]`（实例化 SAM）三者相等 | 拒绝 |
| **宿主相同** | `M:` 键只比<b>方法名</b>（被删的捕获往往就是宿主形参，宿主描述符必然跟着变）；`L:` 键要求整个键相等 | 拒绝 |
| **映射唯一** | 每个新形参在旧捕获表里<b>恰好</b>命中一个同身份位置，且整体内射（见 §3.1 三级来源） | 拒绝 |

外加两条前置：

- **布局自检**：`捕获表 ++ 实例化 SAM 形参` 必须<b>恰好等于</b> impl 描述符。这条等式让
  "捕获值前置、SAM 形参在后"从假设变成每例现验。注意必须用 `bsmArgs[2]`（实例化签名）
  而不是 `bsmArgs[0]`：泛型接口的 SAM 是擦除后的，`Consumer<String>` 的 `bsmArgs[0]`
  是 `(Ljava/lang/Object;)V`，拼不出 impl 描述符。
- **1:1 唯一**：一个老键命中多个候选 → 拒绝；一个候选被多个老键命中 → 全部拒绝。

**内射是硬要求**：两个新形参共用一个旧值，意味着它们是两个不同的变量，值不一定相同；
喂同一个值就是静默错值。所以"重复捕获"不支持（`(A) → (A,A)` 判否）。

### 3.1 映射的三级来源：名字 > 形参槽 > 类型

| 级别 | 前提 | 依据 |
|:--|:--|:--|
| **形参名**（最硬） | 两侧都带 `-g`（合成方法形参槽有 `LocalVariableTable`） | 源码变量名就是身份。名字**不随宿主局部槽位移**、也不随宿主签名变化失效 |
| **宿主形参槽** | 宿主描述符完全一致，且**所有捕获都来自宿主形参** | 形参槽在描述符不变时稳定；槽号相同 ⇒ 同一个变量 |
| **类型唯一**（兜底） | 上述都不成立 | 每个新形参在旧捕获表里恰好一个同类型位置 |

**为什么局部变量槽不能当证明**：宿主方法里删掉一个前序局部变量，后面所有局部槽整体前移。
`int a=1; int b=2;` 里 `b` 在槽 1，删掉 `a` 之后 `b` 到了槽 0——此时"新槽 0 == 旧槽 0"
会把 `a` 的值喂给 `b`。所以槽位证明只对形参成立，局部变量一律退回类型唯一性。

**空捕获表不建桥**：两侧都空时任何"证明"都是空洞的（映射算出来是个空数组，证明通过却什么
也没证），而且这类 lambda 没有实参可搬。这条必须放在按名字映射**之前**——名字映射对 0 个
捕获会返回一个合法的空数组，正好绕过等长闸。

**捕获槽号怎么来**：javac 在 indy 之前按捕获顺序把实参压栈，所以从 indy 往前数
`捕获数` 条真实指令就是它们的来源，每条必须是<b>直接的局部变量加载</b>。实参若是临时值
（`() -> use(make(a))` 会先算出临时槽再捕获），槽号没有跨版本身份，回溯判失败、退回推断。

**接收者槽统一记作 `$this`**。它不是源码变量名，而是一个"两侧必然相等"的占位符。归一它
是因为"捕获到的外层 `this`"有**三种**字节码来源，留下的 `LocalVariableTable` 各不相同：

| 来源 | 槽 0 的 LVT | 读出来 |
|:--|:--|:--|
| 实例方法被改成静态（`needConversionToStatic`） | javac 原本写了 `this`，改写不动 LVT | `"this"` |
| 零捕获静态 lambda 被前置 receiver（`needForceCaptureThis`） | 槽是新增的，**没有 LVT 条目** | `null` |
| **kotlinc 原生产物**（Indy lambda 天生 `ACC_STATIC`） | kotlinc 对外层 this 的一贯写法 | `"this$0"` |

三者指同一个东西。不归一的话，"任一位缺名字就整体放弃按名映射"这条严格判据会把后两种
判成"没证据"；而同一个 lambda 在不同来源之间切换时，`"this"` / `null` / `"this$0"` 还会让
两侧对不上。kotlinc 那条尤其容易踩：老侧是 javac + `forceStatic`（`"this"`）、新侧是 kotlinc
原生产物（`"this$0"`），按名映射直接失配，退回类型推断后撞上两个同类型捕获的歧义。

kotlinc 的实测形态（`kotlin-compiler-embeddable` 2.1.20，`-jvm-target 21`）：

```text
private static final void d$lambda$0(K, int);        // flags: ACC_PRIVATE|STATIC|FINAL
    LocalVariableTable:
        0      11     0 this$0   LK;
        0      11     1    $k   I
```

**判据分两档，结构优先**：

1. 能拿到捕获的宿主槽号时，以槽号为准——接收者的来源恒为宿主**实例方法**里的 `aload 0`，
   所以 `captureSlots[0] == 0 && !hostIsStatic` 就是证明，不看名字。
2. 回溯失败（生产者不是直接的局部变量加载）时才退回名字：`null` / `"this"` / `"this$0"`。

结构证据**证伪**时（槽号不是 0）名字即使是 `"this$0"` 也不得归一：`this$0` 是**合法的 Java
标识符**，源码里真能写 `void d(B this$0)`，那是另一个对象。把它当成接收者，桥就会把接收者
喂给一个本该是那个变量的位置——静默错值。所以提取器（`readLambdaParamNames`）只归一
`null` 与 `"this"`（前者无歧义、后者是关键字），`"this$0"` 留给有槽位信息的那一层处理。

判据用**"首形参类型 == 当前类"**而不是"名字为空"：真正的同名局部变量
（`void observe(Foo other)` 里类型恰好是本类的 `other`）会带自己的名字，不能被误当成接收者。

> 顺带纠正一处曾写在设计里的错误：`needForceCaptureThis` 只对**零捕获**的静态 lambda 生效
> （它要求 `indy.desc` 的参数个数为 0），所以 `int a=1, b=2; () -> a+b` 这种**带捕获**的
> lambda 不会被前置 receiver，`[null, "a", "b"]` 这个具体形态并不会从这条路径产生。归一
> 仍然是必要的——真实形态是上表第二行（`[null]`），以及它与第一行、第三行之间的不一致。

### 3.2 同签名的捕获换序必须在配对层拦住

`(II)V → (II)V` 这种"描述符完全没变、只是捕获顺序换了"的编辑，是所有判据里最危险的：

- 旧隐藏类的常量池条目 `(name, (II)V)` 直接解析到新方法，**参数按旧顺序压栈**；
- 新方法体按**新顺序**解读这两个参数。

没有任何异常，只是两个值被静默交换。这类形态过不了"删除/新增"两道门（捕获数没变），
所以必须在**配对阶段**就拒绝：`isSignatureCompatible` 在描述符相等时额外要求
"捕获顺序未被置换"。判据只认"身份集合相同、顺序不同"——变量整体改名时集合不同，属于
"同一位置换了变量"，原地配对仍然正确，不拦。

拒绝配对之后走的是既有链路：阶段二给新方法分配避障名（`oldNameDescSet` 里已有该键 ⇒
冲突 ⇒ 改名），旧键因此变成孤儿，捕获桥按新顺序重排实参。

### 3.3 实例 ↔ 静态：允许一个方向，且要拆成两位

`forceStaticLambdas` **关闭**时，"删掉 `this` 引用"会让合成方法从实例变静态：

```text
V1: public void d(int k) { r = () -> LAST = "" + k + this; }
      private void        lambda$d$0(int)   indy (LB;I)   ← 捕获 [this, k]
V2: public void d(int k) { r = () -> LAST = "v2:" + k; }
      private static void lambda$d$0(int)   indy (I)      ← 捕获 [k]
```

两者的 **name + desc 完全相同**（`lambda$d$0(I)V`），只差 `ACC_STATIC`。由此有两件事必须
分开处理：

**方向只允许"实例 → 静态"**。这是"删掉接收者捕获"，新方法体物理上读不到它，桥丢弃即可。
反方向（静态 → 实例）等于**新增**一个接收者捕获，老实例里没有这个值，一律拒绝。

**桥的形态与目标的形态是两位**，不能共用一个 `isStatic`：

| 位 | 由谁决定 | 影响 |
|:--|:--|:--|
| `isStatic`（桥自身） | **老**方法 | 老隐藏类按老形态调用它。老方法是实例方法，桥就必须是实例方法（槽 0 = 接收者） |
| `targetStatic`（目标） | **新**方法 | 决定转发指令：静态 `INVOKESTATIC`（不压接收者）；实例 `INVOKESPECIAL`（先 `aload_0`） |

混成一位就会写出"实例桥去 `INVOKESTATIC` 一个要接收者的目标"，或者反过来压了一个多余的
接收者。为什么实例目标用 `INVOKESPECIAL` 而不是 `INVOKEVIRTUAL`：目标一定是本类里的
`private` 合成方法。

**同名同描述符的碰撞**由既有改名链路解决，但**顺序很关键**：配对阶段 `isSignatureCompatible`
先因为静态位不同而拒绝配对 → 阶段二发现 `oldNameDescSet` 里已有该键 ⇒ 冲突 ⇒ 给新方法
分配避障名 → `presentKeys` 里不再有老键 ⇒ 老键变成孤儿 ⇒ 才轮得到桥。若顺序反了（先建桥
再改名），桥与目标会撞在同一个 `name + desc` 上，产物直接 `ClassFormatError`。

**第二轮若目标静态位翻转，退回幽灵桩**：桥体里"搬哪些实参"是按上一轮目标的形态定的。
目标从静态变回实例（比如它又用回了 `this`）时，那份记录就不成立——照着重放会少压一个实参，
验证期直接失败。这条路径不猜，直接降级（`bd.targetStatic != now.isStatic()`）。

任一条不成立，退回原有的幽灵桩路径（行为与引入本机制前完全一致）。

---

## 4. 产物形态

桥**不参与改名**：新方法保留自己的名字，桥占用旧键。两者描述符必然不同，可以共存
（javac 复用编号时两者同名不同描述符，这也是合法的）。

```text
lambda$f$0(Ljava/lang/String;I)V      ← 桥（旧键，转发）
    LDC        "Host#lambda$f$0(Ljava/lang/String;I)V -> lambda$f$0(I)V"
    INVOKESTATIC remold/LambdaAligner.onBridgeInvoked(Ljava/lang/String;)V
    ILOAD      1                      ← 只加载活下来的那个形参（槽 0 的 String 被丢弃）
    INVOKESTATIC Host.lambda$f$0(I)V  ← 目标；实例方法用 INVOKESPECIAL
    RETURN

lambda$f$0(I)V                        ← 新方法体，原样保留
```

**实例 lambda**（`() -> use(this.x)`）多一个细节：工厂描述符里带接收者
（`aload_0; indy (LC;)LRunnable;`），而 impl 是实例方法、描述符里不含它。所以布局自检要
先剥掉捕获表的第一项；桥跟着做实例方法，先 `aload_0` 再压形参，转发用 `INVOKESPECIAL`。

```text
lambda$d$0(Ljava/lang/String;I)V      ← 桥（实例方法，槽 0 是 this）
    LDC loc; INVOKESTATIC onBridgeInvoked
    ALOAD      0                      ← 接收者
    ILOAD      2                      ← 活下来的 int（实例方法形参从槽 1 起）
    INVOKESPECIAL Host.lambda$d$0(I)V
    RETURN
```

**"删掉 this"的桥**（实例 → 静态，见 §3.3）则没有 `aload_0`：

```text
lambda$d$0(I)V                        ← 桥（仍是实例方法，槽 0 是 this）
    LDC loc; INVOKESTATIC onBridgeInvoked
    ILOAD      1                      ← 只搬 k；接收者被丢弃
    INVOKESTATIC Host.lambda$d$1(I)V  ← 目标被改成避障名（否则与桥同键）
    RETURN
```

所以解析旧桥体时**不能要求它以 `aload_0` 开头**：接收者压栈是可选的，取决于目标是否实例
方法。判据改为"压没压接收者"与"核心调用是不是 `INVOKESTATIC`"必须**相反**，两者交叉验证。

**"新增捕获"的桥**（纯源重放，见 §2）多一条"现场造值"的指令，位置按新方法的形参顺序：

```text
lambda$f$0(Ljava/lang/String;I)V      ← 桥（老键，只捕获 s 与 k）
    LDC loc; INVOKESTATIC onBridgeInvoked
    ALOAD      0                      ← s
    ILOAD      1                      ← k
    BIPUSH     10                     ← 新增的 margin：重放宿主里那唯一一处 istore 的常量
    INVOKESTATIC Host.lambda$f$0(Ljava/lang/String;II)V
    RETURN
```

所以解析旧桥体时要认得三种"压值"形态：形参加载、常量、`GETSTATIC`、以及
`ALOAD 0; GETFIELD`（后者要与"接收者压栈"区分开——判据是 `ALOAD 0` 后面是否紧跟 `GETFIELD`）。

### 4.1 尾部 SAM 实参：桥必须原样转发

桥自己的描述符就是**老 lambda 方法**的描述符，形参序恒为
`[老捕获…] + [SAM 形参…]`（实例方法再加槽 0 的接收者）。捕获按 `sources` 搬或现场重放；
**SAM 形参是调用方传进来的实参**，不属于捕获表，必须按位置原样压给目标：

```text
lambda$make$0(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;  ← 桥（老键：捕获 s、k + SAM 形参 x）
    LDC loc; INVOKESTATIC onBridgeInvoked
    ILOAD      1        ← 活下来的捕获 k（槽 1）
    ALOAD      2        ← 尾部 SAM 实参 x（槽 2）
    INVOKESTATIC Host.lambda$make$0(ILjava/lang/String;)Ljava/lang/String;
    ARETURN
```

三条硬性约定：

1. 尾部个数由目标描述符反推：`samArgs = targetArgs − (sources.length − newFirst)`，
   并逐个比对老/目标描述符的尾部类型（必须相等，判据见 `samTailMatches`）。
2. 多轮之间**不复用**尾部：第二轮从旧桥体解析出来的 `sources` 天然带着尾部（它同样是
   "形参加载"），必须截断到捕获表长度再交给 `BridgeSpec`，由生成期按本轮描述符重新压。
3. 漏压的失效形态不是 `VerifyError`：重定义路径不跑字节码校验器，目标会读到 `null`
   （探针实测 NPE）或 JIT 下的垃圾引用（真机表现为 native
   `EXCEPTION_ACCESS_VIOLATION`，崩溃点随实参用途落在 `LinkResolver` / `method_at_vtable` /
   `StringConcatHelper::stringOf` 等）。因此零形参 SAM 之外的桥必须带这项转发，且要有
   端到端用例（对齐产物定义后按老描述符调用，断言业务返回值）。

### 4.2 `sameHost` 的证据必须带宿主链

桥的第一道门是"同一宿主"。判定用的是 `hostKey`：普通宿主为 `M:name+desc`，lambda 宿主为
`L:<模板哈希>`。**只放哈希不够**——模板哈希会屏蔽字面量，于是"两个不同宿主里仅字面量不同"的
lambda 拿到同一个 `L:` 键，它们的内层 lambda 便被判成"同宿主"，跨宿主桥接成立（S14 现场形态：
`makeInstToStatic` 与 `makeStaticToInst` 交换 this 形状，内层 Supplier 被接到另一宿主的新体，
retained 输出 `s2i=noThis:y-v2`）。

因此 lambda 宿主的键必须是**链式**的：`L:<模板哈希>@<它自己的宿主链>`（递归到 `M:` 为止）。
键里带上宿主链后，跨宿主的内层 lambda 拿不到同宿主证据，只能 fail-closed 退回幽灵/保留旧体。
宿主序列锚点也随之按链分组，不会跨宿主借顺序证据。

无分支、无 `Label`，因此不需要 `StackMapTable`，与既有空壳桩的设计一致。

### 4.3 多候选歧义：只用"长度一致"的宿主序号消歧

捕获桥的候选是"同一宿主下、同接口、支持捕获缩减的未匹配新 lambda"。当同一个宿主里有**多条同形**
lambda（例如 `buildSwitch` 里两条 `(I)String` 同时丢掉 int 捕获）时，`planBridge` 对每个候选都会
成功，歧义无法靠类型映射消解，`hits != 1` 一律拒绝——这会把本可桥接的旧实例退化成幽灵/保留旧体
（S45 形态：`swR:3-v1` 停在 V1，而新逻辑本可经桥生效）。

允许的消歧只有一种：**同宿主的新旧调用序列长度一致**。长度一致 ⇒ 本轮没有插入/删除，调用序号就是
可靠证据，按 `oldSeq.indexOf(oi)` 取 `newSeq` 同序号目标；只对普通方法宿主（`M:`）生效，链式 lambda 宿主
（`L:`）不参与。长度不一致（S40 的"中间插入"）**仍然拒绝**位置配对——那种情况下位置会整体位移，
按序号配对正是"旧调用点绑到错误语义"的来源。

无分支、无 `Label`，因此不需要 `StackMapTable`，与既有空壳桩的设计一致。

---

## 5. 多轮身份（最容易踩的坑）

桥的键在新版本里<b>天然没有对应物</b>，若下一轮认不出它，它会被当成"又被删了一次"重新
幽灵化——第一轮的成果当场作废。所以：

1. **识别**：`isBridgeMethod` 看开头两条指令（`LDC` + `INVOKESTATIC onBridgeInvoked`）。
   刻意用独立的方法名，与幽灵桩的两条前缀形态（3 指令带 `ICONST`、2 指令 1 参重载）
   天然不冲突。
2. **解析**：`getBridgeDelegate` 允许<b>跳过形参槽位</b>（子序列加载）。不能复用
   `getPureDelegate`——它要求"每个形参按槽位顺序恰好加载一次"，跳槽位的桥在它眼里
   就是 `null`，直接退化成死幽灵。这条放宽<b>只作用于带桥标记的方法</b>，不影响
   `getPureDelegate` 对普通 lambda 的严格性（那份严格性是 `wasForwarding` 可信的前提）。
3. **重生成**：目标取本轮配对结果（`旧目标键 → findNewForOld → 新方法`），而不是沿用
   旧桥体。这样 `ctx.renameMap` 改名天然跟上，不需要额外维护映射。
4. **目标消失** → 不生成桥，退回幽灵桩。

由此派生出三条"桥不是 lambda 体"的推论，缺一条都会破坏配对：

- `putNameIndex`：桥与它指向的目标同名不同描述符（javac 复用编号），按名字索引时
  **必须让真 lambda 体压过桥**；
- `children`：桥不登记子 lambda。否则桥体内那条 `INVOKESTATIC` 会让目标平白多出一个
  永远配不上对的父，把 `hasUnmatchedChild` 卡死；
- 兜底遍历（leftovers）：跳过桥，并按 `name + desc` 精确解析（按名字会取到对方，
  把目标方法的 `hostKey` 改写成 `L:<桥的哈希>`）。

---

## 6. 熔断豁免

`onOrphanInvoked` 在 `SMART_ADAPTIVE` 下<b>先</b>判 `isOrphanBreakerTriggered()`、<b>后</b>看
`willForward`。桥若复用这个入口，挂在 UI / 定时器熔断器上的回调会在转发<b>之前</b>被直接
炸断，桥等于白建。

所以桥走独立入口 `onBridgeInvoked(String)`，<b>永不查询熔断器</b>：桥按构造就不是死孤儿
——它的目标方法存在，且被三道门证明过兼容。该入口只做去重日志。

> 附带记录：现有 `willForward` 转发桩同样会被熔断器先炸，看起来是独立缺陷（转发桩的目标
> 是活的，不该触发熔断）。改它要动 `MethodRefForwardTest` 已编码的语义，应单独立决策记录，
> 不要顺手塞进本机制。

---

## 7. 判定力

- `LambdaBridgeAlignTest`（对齐期，不需要 DCEVM，共 44 例）：
  - 正向：建桥 / 第二轮保持并重指向 / **纯重排**（`a+b` → `b+a`，桥按新顺序压栈）/
    **槽位证明消歧**（`(int,int) → (int)`，宿主描述符不变）/ **实例 lambda**（桥是实例方法、
    `INVOKESPECIAL` 转发、接收者不进描述符）/ 接口宿主（`itf=true`）/
    **局部变量名字证明**（删掉前序同类型局部后槽号位移，只有名字能定）/
    **同签名换序拦截**（描述符不变、捕获顺序反了 → 改名 + 桥，不原地配对）/
    **实例 → 静态**（删掉 `this`：桥仍是实例方法、目标静态、新方法被改成避障名）/
    **实例桥的第二轮**（捕获下标 vs 描述符形参下标差 1 的坑）/
    **换序桥的第二轮**（桥体故意倒序压栈，旧判据"槽号必须递增"会把它认成非桥）/
    **forceStatic 开启**（接收者归一成 `$this`；两个同类型捕获只能靠它消歧）/
    **kotlinc 兼容**（`this$0` 与 `$this` 归一：结构证据在时按槽号，回溯失败时按名字）/
    **新增捕获的纯源重放**（常量 / `static final` / `final` 实例字段 / 可折叠算式（含
    `long` 移位与 `float` 乘法）/ 构造器参数经 `final` 字段）及其**第二轮保活**；
  - 负向：新增捕获拒绝（宿主形参 / 算式不可折 / 非 final 字段 / 静态桥无接收者）/ 捕获类型变化拒绝 /
    重复类型歧义拒绝（宿主签名变了，槽号不可比）/ SAM 变化拒绝 / 目标消失退化为幽灵 /
    **真叫 `this$0` 的局部变量不得当成接收者**（槽号证伪）；
  - 熔断豁免对照。

**消融验证**（逐条撤掉修复，确认对应断言必挂，而不是"全绿即通过"）：

| 撤掉的修复 | 必挂的用例 | 挂点 |
|:--|:--|:--|
| 接收者归一成 `$this` | `forcedStaticReceiverWithNoLvtEntryIsNamedInsteadOfNull` | 读出来是 `null` |
| 同上（只归一 `null`、不归一 `"this"`） | `forcedStaticReceiverNameBreaksTheTieBetweenSameTypedCaptures` | 按名映射整体弃权 → 退化成幽灵桩 |
| 名字集合里去掉 `"this$0"` | `kotlincStyleReceiverNameWorksWhenCaptureSlotsAreUnknown` | 回溯失败后只剩名字，桥变幽灵桩（结构证据那一档仍能过，见下行） |
| 去掉结构证据、只按名字判接收者 | `realLocalNamedThisDollarZeroIsNotTreatedAsReceiver` | 真叫 `this$0` 的形参被归一成 `$this`，建桥数 0 → 1（把形参对象当接收者喂给新体） |
| 允许"实例 → 静态" | `droppingTheReceiverBridgesInstanceToStaticWithoutKeyCollision`、`secondRoundKeepsTheInstanceToStaticBridge` | 建桥数 0 / 桥变幽灵 |
| 桥体允许不压接收者 | `secondRoundKeepsTheInstanceToStaticBridge` | 第二轮认不出桥 |
| 解析旧桥体时按捕获下标记 | `secondRoundKeepsTheInstanceBridgeAndItsParamMapping`、`secondRoundKeepsTheInstanceToStaticBridge` | `ILOAD` 变成 `ALOAD`（去加载 String 槽） |
| 解析旧桥体时要求槽号递增 | `secondRoundKeepsTheReorderBridge` | 换序桥第二轮退化成幽灵桩 |
| 关闭纯源合成 | 4 条 `added*IsSynthesized` / `secondRoundKeepsTheSynthesizedConstant` | 建桥数 0 |
| 不校验字段 `final` | `addedNonFinalFieldCaptureIsRefused` | 非 final 字段被当成常量重放 |
| 解析旧桥体时不认常量位 | `secondRoundKeepsTheSynthesizedConstant` | 桥退化成幽灵桩 |
| 关掉常量折叠 | `addedComputedButFoldableCaptureIsSynthesized` | 可折叠算式被拒 |
| 关掉"构造器参数经字段" | `addedCaptureFromConstructorParamIsSynthesizedViaField` | 构造器参数被拒 |
| 二元运算按 opcode **区间**分组（而非逐类型列） | `addedLongShiftCaptureIsFolded`、`addedFloatMultiplyCaptureIsFolded` | 别的类型被 int 区间吞掉 → 整段放弃 |
| long 移位不特判（把移位量当 long 弹） | `addedLongShiftCaptureIsFolded` | 操作数类型不匹配 → 整段放弃 |

> 最后两行是收尾清扫时发现的：JVM 的算术指令**按类型交错**编号（`iadd=96, ladd=97,
> fadd=98, dadd=99`），用 `op >= IADD && op <= IXOR` 分组会把 long/float/double 一起圈进
> int 分支，再拿 `popInt` 去弹 `Long` → 静默失效。改成逐类型列 opcode 后才有这两条用例。

- `LambdaBridgeProbe`（运行时，需要 DCEVM）：`./gradlew :tests:lambdaBridgeProbe -Pp05.jbr=<dcevm-jdk>`，
  实测老实例在删除/新增捕获后的真实失败形态，含"捕获表不动"的对照组与两个端到端场景
  （静态 lambda / 实例 lambda，redefine 后同一个实例跑的是新方法体）。当前 35/35。

---

## 8. 附录：JLS 与 javac 的实测对照

"只有显式 `final` 的常量变量才被内联"这条决定了新增捕获能否被桥重放，实测（javac 21）：

| 源码 | 字节码 | 合成方法 |
|:--|:--|:--|
| `int margin = 10;` | `bipush 10; istore_0; iload_0; indy (I)` | `lambda$a$0(int)` ← **是捕获** |
| `final int margin = 10;` | `bipush 10; istore_0;` 然后 `indy ()` | `lambda$b$1()` ← 内联，无捕获 |
| `int margin = p;`（effectively final，初值非常量） | `iload_0; istore_1; iload_1; indy (I)` | `lambda$c$2(int)` ← **是捕获** |

**"effectively final" ≠ "constant variable"**，这两条是不同条款、管不同的事：

- **effectively final**（JLS §4.12.4 末段 / §15.27.2）只回答"能不能被捕获"——初始化后从未再赋值即可。
  它<b>不</b>让变量变成常量。
- **constant variable**（JLS §4.12.4）要求**显式 `final`** + 基本类型或 `String` + **常量表达式**初值。
  只有这类才会被内联（§13.1）。`int x = 1;` 没写 `final`，不是常量变量，照常成为捕获。

这条区别正是"新增捕获可重放"的立足点：恰恰因为**没被内联**，宿主方法里才留着那唯一一处
`istore`，桥才有东西可回溯。写了 `final` 的反而根本不会成为捕获，桥永远看不到它。
| `String tag = "x";` | `ldc "x"; astore_0; aload_0; indy (LString;)` | `lambda$c$2(String)` ← **是捕获** |

其余两条与桥接实现直接相关的事实：

- **捕获顺序按首次引用**：`() -> a + b` 得 `(String,int)`，`() -> b + a` 得 `(int,String)`。
  所以"只换操作数顺序"也会改描述符——这正是重排必须支持的原因。
- **合成方法没有 `MethodParameters`**，但 `javac -g` 会给它发 `LocalVariableTable`
  （`lambda$a$0` 的 `slot 0` 名字就是 `margin`）。本对齐器读字节码时用 `SKIP_DEBUG`，
  拿不到这个名字，所以映射只能走槽号而不是变量名。

---

## 9. 附记：兜底配对门禁（`fallbackPairingAllowed` / `bodyFingerprint`）

> 本节是 **判据与边界的规格**，不登记实现状态与测试计数（那两项归 `docs/status.md`）。
> 下面出现的证据 ID 只是"这条判据被哪次实验压过"的指针，便于复现。

### 9.1 门禁在做什么

骨架趟 / Pass A / Pass B 都只按 (签名, 深度, shape, 子归属) 收窄候选，**不看方法体**。
同组里出现"删一个、加一个"或者"把叶子包进新容器"时，位置信息会把<b>别人的名字</b>交给一个
语义不同的方法体：老 `CallSite` 不抛异常、静默跑错。`fallbackPairingAllowed` 是配对前的准入判决：
六档证据（逐条等价 / 身份锚定 / 消费端有区分度 / 骨架全类唯一 / 子身份已定案 / 顺序一致）
<b>任一成立才放行</b>；都不成立就 `BODY_MISMATCH` WARN + 拒绝配对——旧名走幽灵桩（老
`CallSite` 响亮熔断），新方法去拿避障名。**宿主形态翻转一票否决**（`hostKind` 一侧 `M`、
一侧 `L`，或一侧取不到宿主记为 `?`）优先于"同名放行"，见 §9.5 第 G1 行。

`bodyFingerprint` 的归一化口径（比对前抹平，避免等价改写被误判为不等价）：
常量池索引按**值**归一；字段/方法引用按 `owner+name+desc` 归一；局部变量槽按**首次定义顺序**
重编号；分支标签按目标指令的相对序号归一；可折叠算式按折叠后的值归一。

### 9.2 能拦的 / 拦不了的

- **能拦的**：方法体在**抹掉上述四类位置信息之后**仍然不同，且六档证据都不成立 ⇒
  `BODY_MISMATCH` WARN + 拒绝配对。典型形态：删一个 lambda 又加一个**不同体**的（体内新增内层 lambda 的形态：把叶子
  包进新内层）；体不同且换了消费者；体不同且宿主形态翻转。
- **拦不了的（信息论边界，由代码分析可知）**：删掉一个 lambda、**同一次保存里又加一个**、且 javac 恰好
  给它**同一个编号**（同宿主、同名）—— 这与"原地更新"在字节码层**完全同形**，没有任何规则能区分。
  该形态仍会被配对。此结论的依据是判据本身的形状（不存在区分二者的可观测量），
  不是实测反例；把它列为推断而非实测。
- 另一层边界：`bodyFingerprint` 只能证明"体等价"，**不能证明"体属于同一个 lambda"**——体等价的
  两个 lambda 互换身份时它看不见。这类互换由"名字/宿主/顺序"三档证据兜，不是体指纹的职责。

### 9.3 Pass B 的覆盖面（比名字听起来窄）

"纯搬迁"（体等价、宿主形态不变）**先被 Step 1 吃掉**：Step 1 用递归语义指纹 + 票据指纹做
`acceptCandidate`，体一字未改的搬迁在那儿就配掉了。Pass B 只兜"**体等价、但票不同**"的那一档——
典型是常量等价改写（`BIPUSH 6` → `LDC 2; LDC 3; IMUL`）与 `-g`/`-g:none` 造成的票据指纹差异。
所以"Pass B 可达"的正面用例必须**刻意造出"票不同、体等价"**的输入，否则断言实际测的是 Step 1。

### 9.4 计数器口径：`bodyMismatchRefusals` 是**逐趟计数**，不是"拒绝个数"

同一个 (旧名, 新名) 对会在 A 趟、B 趟第一优先级、B 趟第二优先级、骨架趟里被**各判一次**，
`warnBodyMismatch` 每次都 `++`，但 WARN 文本按 `类名#BODY#新名<-旧名` 去重（只打一条）。
因此该计数的语义是"**判决次数**"，不是"被拒绝的配对数"，也不是"WARN 条数"。
生产侧不改名（改名要动对外读数），仅在此标注口径：任何"每类 ≤1 次"的推断都不成立。

### 9.5 幽灵信息的全部消费点（静态论证）

幽灵桩（体内只调 `onOrphanInvoked`）是"旧名字已退休、老 `CallSite` 必须响亮失败"的载体。
一旦它被当成**活方法**参与匹配，老名字就会被别人的方法体认领 —— 静默错绑。逐点核对：

| # | 消费点 | 作用 | 防线性质 |
|:--|:--|:--|:--|
| 消费点 1 | `scan`：`info.ghost` 的方法**不当宿主** | 幽灵体内的 indy 不产出宿主序列（宿主 `hostKey` 保持 `null`） | 独立；但由它产生的 `null` 宿主**交给 G1 否决**，这条链只在门禁里 |
| 消费点 2 | `scan`：幽灵**不作为 indy 目标**（`target.renameable && !target.ghost`） | 幽灵名不能被认领 | 独立（由代码分析可知） |
| 消费点 3 | `scan`：语义指纹不折入幽灵子（"幽灵无语义"） | 幽灵不污染父指纹 | 独立 |
| 消费点 4 | `putNameIndex`：活方法压过幽灵/桥 | 同名索引取到真体而不是桩 | 独立 |
| 消费点 5 | Step 1 `acceptCandidate` | 幽灵不参与指纹配对（放开幽灵跳过限制后可放开） | 放开幽灵跳过限制后，此处仍有独立防线：`sameSemantics` 仍要求指纹一致，而幽灵体是熔断桩、指纹与真实体不同 |
| 消费点 6 | Pass A `step2PassA` | 幽灵不参与 A 趟 | 独立：在该路径下跳过，即使放开幽灵跳过限制也不放开（由代码分析可知） |
| 消费点 7 | Pass B 两条循环 | 幽灵不参与 B 趟（放开幽灵跳过限制后可放开） | **仅靠门禁**：放开幽灵跳过限制后，拦得住它的只有 `fallbackPairingAllowed` |
| 消费点 8 | `isSkeletonGloballyUnique` / `skeletonEligibleOld` | 幽灵不算骨架候选 | 独立 |
| 消费点 9 | `hasMultipleHashCandidates` / `checkAndWarnHomomorphicPruning` | 只数活方法 | 独立 |
| 消费点 10 | 深度/形状/引用者计算 | 幽灵按"无子空壳"处理 | 独立 |
| 消费点 11 | `stepGhostRevive`（`-Dremold.agent.ghost_revive=true`，默认关） | 按**生前指纹**复活幽灵 | 独立通道：复活要求 `ghostOriginalHash == 新体指纹`，且**不经过**本门禁 |
| 门禁层 1 | `fallbackPairingAllowed` 的宿主形态否决（`hostKind` 一侧 `?`） | 取不到宿主 ⇒ 形态判不了 ⇒ **一票否决**，优先于"同名放行" | 独立防线（门禁自身的一层，不是"靠别的层兜"） |
| 门禁层 2 | `isOrderConsistent` 的 `hostKey == null` 早退 | 没有序号就没有顺序证据 | 独立（返回 `false` 即"不给证据"，不是放行） |


表中"独立"指**该消费点不依赖兜底门禁**。证据等级：由代码分析可知（代码阅读）；其中「幽灵不作为 indy 目标」一行由"放开次数为 0"的哨兵与
`nullOldHost…` 具名用例覆盖、Pass B 两条循环明确**仅靠门禁**（纵深少一层），这两行另有实测支撑（、
）。
**残余风险（必须登记，不能只说"被短路"）**："允许幽灵参与后续匹配"这一放宽开关覆盖的三处里，
Step 1 那处还有 `sameSemantics` 兜底，**Pass B 那两处只由门禁兜底**。
门禁把这两处短路之后，该放宽开关在门禁开启下不再可被击杀（门禁屏蔽变异体）——
**纵深防御少了一层**：一旦门禁被放宽或移除，Pass B 的两条循环就没有第二道防线。
缓解手段不是"再补一层代码"，而是把它变成**响亮的报警**：
`LineNumberDifferentialTest` 的 `mutationKillRateRegressionTest` 对该放宽开关断言击杀数 `== 0`
（失败信息直接要求"把它移回击杀集"），外加 `nullOldHostVetoedBeforeSameNameShortCircuit`
钉住 G1。两者都在门禁放宽时立刻变红（见 §9.7 的观测口径）。

### 9.6 语料范围与口径（禁止外推成全仓结论）

大样本误报断言的实测口径（`PassBTest#bodyFingerprintNeverRefusesSlotShiftedRepoLambdas`）：

- 变换：**同一份源码**分别用 `javac -g` 与 `javac -g:none` 编译，再把新侧的局部变量槽整体平移 3；
  等价性由"源码相同"保证，与体指纹无关。断言 `BODY_MISMATCH == 0`。
- 语料：`core/src` + `api/src`（一次编译，互相依赖）+ `plugin-fabric` + `plugin-mixin` + `plugin-forge`
  （各自单独编译；`org.spongepowered:mixin:0.8.5` 从本地 Gradle 缓存按坐标发现）。JDK 21。
- **显式缺口**：
  - `plugin-arc` **未纳入**。Mindustry 的编译期制品不是纯 class 包
    （`Anuken:Mindustry:v160.5` 内含 1264 个未编译的生成 `.java`，例如 `mindustry/io/TypeIO.java`
    依赖 `@TypeIOHandler` 生成的类型）；`javac` 会把 `arc`/`mindustry` 引用解析进这些源文件，
    随即以"找不到 `mindustry.annotations.Annotations`"/"找不到 `TypeIOHandler`"失败。
    补齐它们需要 Mindustry 自带的注解处理器产物，离线缓存中没有。
  - `tests/`、`gradle-plugin/`、`plugin-arc` 均不在语料里。
- 因此"仓库内所有含 lambda 的类"**尚未**被覆盖；N 是"上述模块的实测样本数"，
  **不得**写成全仓结论，也不得当成 `plugin-arc` 无害的证明。缓存缺失时探针会打印
  `[BodyFingerprintFP-GAP]` 行而不是静默缩小样本面。

### 9.7 幽灵归因：槽位平移下的身份丢失（实测）

同一语料（`core/src` + `api/src`，N=30 个含 lambda 的类）、同一变换（`-g` / `-g:none` + 槽位平移 3），
分别跑**门禁关闭**（`fallbackPairingAllowed` 四处调用点短路）与**门禁开启**，逐类统计产物里的
幽灵桩数：**两趟完全相同**（30 个类 + 汇总共 31 行机器读数逐字节一致，差异 0 行），
其中 8/30 个类出现了幽灵（合计 22 个幽灵桩），门禁拒绝次数两趟都是 0。

结论（实测）：这批幽灵**不是门禁造成的**，属**既有行为**——"槽位平移导致身份丢失"。
成因的**推断**（由代码分析可知，尚未单独做实验隔离）：票据指纹计入局部变量 `var` 下标，`-g:none` +
槽位平移后既不同名也不撞指纹，更早的名字/序号对齐路径认不出来，旧名字于是退役为幽灵桩。
这条记录的含义只有一条：**它在门禁之前就存在，所以不构成本门禁的误报**；
它本身仍是身份召回能力的一个已知缺口。

## §10 槽位平移下的幽灵桩

`-g:none` 编译 + 槽位整体平移时，部分类的 lambda 会被判为"身份丢失"而生成幽灵桩替身。
在**下列范围**内实测：语料 N=37（`core/src`+`api/src` 合并编译 30 个含 lambda 类、
`plugin-fabric/src` 3、`plugin-mixin/src` 2、`plugin-forge/src` 2）、JDK 21 编译、变换为
"新侧 `-g:none` + 槽位整体平移"、单次对照实验 —— 其中 **9 类出现幽灵、共 23 个桩**
（`AnonClassAligner` 6、`ref/InitFix` 5、`LambdaAligner` 4、`RemoldAgent` 3，
`OffsetBindingIndex` / `ProfilerTransformer` / `HierarchyTreeOracle` / `HotswapBridge` /
`plugin-forge: remold/forge/ForgeMixinHotSwap` 各 1；其余 28 类为 0）。

**在本语料与变换下门禁未产生拒绝，因此这 23 桩不由门禁造成**：同一语料、同一变换下把门禁关闭再跑，逐字节比对两趟的 37 行
`[GHOSTATTR]` 读数 —— 行数相同、`diff` 退出码 0、差异行 0，两趟均为
`classesWithGhosts=9 totalGhosts=23 totalRefusals=0`。该零差异的前提由阳性对照证成：

**口径与实测读数**（`tests/tools/gate-counter.patch`，测试工具补丁，不进产品代码）：语料 31 个类触发兜底门禁，
`eval=107 / fpCmp=107 / fpAllow=107 / hostKindRefused=0 / fallbackRefused=0`，即 107 次评估均进入 `bodyHash`
比对并因指纹相等放行，同源变换误报 **0/107**。`嵌套 lambda 对照种子` 阳性对照：`eval=18 / fpCmp=18 / fpAllow=0 /
hostKindRefused=18`（指纹不等后由宿主形态翻转拒绝），证明计数器对"指纹不等"形态有分辨力。
`gate=on/off` 两趟幽灵数逐字节相同的机制即：语料上 0 次拒绝，关掉门禁同样放行。误报率外推仍限 4 类人造重排各 n=1。

**护栏与其局限**：语料用例与嵌套 lambda 阳性对照都断言 `[GATE-STATE]` 为开（`callSites>0`），门禁被关时显式红
（关态实测 `exit 1`，三个用例同时红）。该护栏只能发现"门禁调用被移除"这一形态；把 `fallbackPairingAllowed`
改成固定返回 true 时 `callSites` 仍为 4、护栏仍绿 —— 这一形态由嵌套 lambda 阳性对照的行为断言兜底（
固定返回 true 的变异下，`PassBTest` 的嵌套 lambda 阳性对照与「体不等时拒绝」两条用例同时失败）。

**已知口径（I3）**：`BODY_MISMATCH` 的 WARN 与 `TEST_EVENTS` 按 `类名#BODY#新名<-旧名` 去重，键里没有
重定义轮次或类版本（由代码分析可知）；`bodyMismatchRefusals` 的自增在该去重之前，配对拒绝与幽灵化
不受其影响（由代码分析可知）。生产侧 `core/` 下 `clearLoggedWarnings()` 调用点为 0、`TEST_EVENTS` 生产消费者为 0
（实测，grep 读数）。长驻 agent 内同一处错绑在后续轮次重复出现时 WARN 只打一次，登记为 跨轮次告警去重问题，未修。

- `[GATE-STATE]` 读的是**实际被加载的** `LambdaAligner` 字节（数 `fallbackPairingAllowed` 调用点并打
  `alignerSha256`），两趟分别 `bodyFingerprint=on callSites=4 sha256=858dd0ff…` 与
  `bodyFingerprint=off callSites=0 sha256=653c92bb…`（sha 不同 ⇒ 第二趟确实换了实现）；
- 同一嵌套 lambda 种子在两趟上的读数：

| 门禁 | `refusals` | `bodyMismatchEvents` | 旧槽位形态 |
|:--|--:|--:|:--|
| on | 9 | 1 | 幽灵化（`oldSlotGhost=true`，`oldSlotInnerPayload=false`） |
| off | 0 | 0 | 内层体静默顶替（`oldSlotInnerPayload=true`，即错绑形态） |

- 另一条独立证据：门禁**开着**时语料 `totalRefusals=0`，说明门禁在该语料上未产生拒绝（107 次评估均放行），
  与上面"两趟幽灵数相同"互为独立佐证。

成因：由代码分析可知，票据指纹计入 `var` 下标，`-g:none` 后失去调试信息、槽位平移即认不出；**未做隔离实验**。
这是用户可感知的可用性回退（lambda 失效），登记为已知限制；隔离实验列为候选批次（会动生产代码，单开批次）。
缺口（未纳入语料）：`plugin-arc/src`（Mindustry 编译期制品非纯 class 包，缺注解处理器，javac 解析即失败）、
`tests/`、`gradle-plugin/`。

## §9.6 门禁屏蔽变异体的哨兵是报警，不是防线

"允许幽灵参与后续匹配"这一放宽开关在门禁开启后无法被任何种子击杀，故转入屏蔽集并配 "幽灵跳过限制放开次数为 0" 的哨兵。
必须明确：**哨兵与 `nullOldHost…` 具名用例是"报警"，不是"防线"**——它们的作用是在门禁被放宽时立刻发出信号，
而不是补回被门禁短路掉的那层纵深防御。Pass B 的两处兜底目前仅由门禁兜底，纵深少一层这一事实不因哨兵而消除。
