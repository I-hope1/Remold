# Lambda 捕获桥（单向）

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
是因为 `forceStaticLambdas` 让接收者有**两种**字节码来源，两者留下的 `LocalVariableTable`
不一样：

| 来源 | 槽 0 的 LVT | 读出来 |
|:--|:--|:--|
| 实例方法被改成静态（`needConversionToStatic`） | javac 原本写了 `this`，改写不动 LVT | `"this"` |
| 零捕获静态 lambda 被前置 receiver（`needForceCaptureThis`） | 槽是新增的，**没有 LVT 条目** | `null` |

两者指同一个东西。不归一的话，"任一位缺名字就整体放弃按名映射"这条严格判据会把第二种
判成"没证据"；而同一个 lambda 在两种来源之间切换时，`"this"` vs `null` 还会让两侧对不上。

判据用**"首形参类型 == 当前类"**而不是"名字为空"：真正的同名局部变量
（`void observe(Foo other)` 里类型恰好是本类的 `other`）会带自己的名字，不能被误当成接收者。

> 顺带纠正一处曾写在设计里的错误：`needForceCaptureThis` 只对**零捕获**的静态 lambda 生效
> （它要求 `indy.desc` 的参数个数为 0），所以 `int a=1, b=2; () -> a+b` 这种**带捕获**的
> lambda 不会被前置 receiver，`[null, "a", "b"]` 这个具体形态并不会从这条路径产生。归一
> 仍然是必要的——真实形态是上表第二行（`[null]`），以及它与第一行之间的不一致。

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

- `LambdaBridgeAlignTest`（对齐期，不需要 DCEVM，共 35 例）：
  - 正向：建桥 / 第二轮保持并重指向 / **纯重排**（`a+b` → `b+a`，桥按新顺序压栈）/
    **槽位证明消歧**（`(int,int) → (int)`，宿主描述符不变）/ **实例 lambda**（桥是实例方法、
    `INVOKESPECIAL` 转发、接收者不进描述符）/ 接口宿主（`itf=true`）/
    **局部变量名字证明**（删掉前序同类型局部后槽号位移，只有名字能定）/
    **同签名换序拦截**（描述符不变、捕获顺序反了 → 改名 + 桥，不原地配对）/
    **实例 → 静态**（删掉 `this`：桥仍是实例方法、目标静态、新方法被改成避障名）/
    **实例桥的第二轮**（捕获下标 vs 描述符形参下标差 1 的坑）/
    **换序桥的第二轮**（桥体故意倒序压栈，旧判据"槽号必须递增"会把它认成非桥）/
    **forceStatic 开启**（接收者归一成 `$this`；两个同类型捕获只能靠它消歧）/
    **新增捕获的纯源重放**（常量 / `static final` / `final` 实例字段 / 可折叠算式（含
    `long` 移位与 `float` 乘法）/ 构造器参数经 `final` 字段）及其**第二轮保活**；
  - 负向：新增捕获拒绝（宿主形参 / 算式不可折 / 非 final 字段 / 静态桥无接收者）/ 捕获类型变化拒绝 /
    重复类型歧义拒绝（宿主签名变了，槽号不可比）/ SAM 变化拒绝 / 目标消失退化为幽灵；
  - 熔断豁免对照。

**消融验证**（逐条撤掉修复，确认对应断言必挂，而不是"全绿即通过"）：

| 撤掉的修复 | 必挂的用例 | 挂点 |
|:--|:--|:--|
| 接收者归一成 `$this` | `forcedStaticReceiverWithNoLvtEntryIsNamedInsteadOfNull` | 读出来是 `null` |
| 同上（只归一 `null`、不归一 `"this"`） | `forcedStaticReceiverNameBreaksTheTieBetweenSameTypedCaptures` | 按名映射整体弃权 → 退化成幽灵桩 |
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
