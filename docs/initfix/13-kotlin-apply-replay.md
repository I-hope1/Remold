# A 档：Kotlin `apply {}` 静态持有者初值重放（设计规格）

本文定义 InitFix 的 **A 档**能力：Kotlin `object` / 顶层 `val` 静态持有者上的
`val dest = T().apply { field = 常量 }` 初值变化，在可证明安全时重放到**存量对象**。

本文只定义设计规格、判据与写入协议，不含实现状态；状态与验收读数统一登记在
[`docs/status.md`](status.md)。L1（匿名类双括号写继承字段）见
[`12-L1-existing-initializer-replay.md`](12-L1-existing-initializer-replay.md)；
L0 只检测不重放，见 [`10-initializer-change-detection.md`](10-initializer-change-detection.md)。

## 1. 问题与分层

Kotlin 的 `apply` 是 inline 函数，`object` / 顶层 `val` 的初始化整体落在持有者类的
`<clinit>` 里：

```kotlin
// JTurret：Java 类，public int health = -1（Mindustry Block.health 同形态）
object Blocks { val duo = JTurret("duo").apply { health = 250 } }
```

热更只重定义类字节码，**不会重跑 `<clinit>`**。于是：

- `Blocks.duo` 仍指向旧 `<clinit>` 创建的那一个对象，`health` 停在旧常量；
- 之后新建的实例（若有）走新 `<clinit>`，得到新常量；
- 旧对象与新语义分叉，但 L0 完全看不到：`InitializerDrift.detect` 只扫描 `<init>` 的实例字段
  写入，静态字段在 `fields()` 里被过滤，`<clinit>` 不被扫描（见 §2.3 实测）。

A 档补上这一层：**只重放“字段写入表达式切片”**，不重放构造器、不重跑 `<clinit>`。

| 层 | 行为 | 默认 |
|:--|:--|:--|
| L0 | 检测 `<init>` 里已有实例字段的初值表达式变化，warn，不写 | 开 |
| L1 | 匿名类双括号写**继承字段**的存量实例重放（父类白名单） | 关 |
| A 档（本文） | `object` / 顶层 `val` 持有者 `<clinit>` 里 `T().apply{...}` 的存量对象重放（持有者白名单） | 关 |
| B 档 | 具名类属性 `class H { val v = T().apply{...} }`（写在 `H.<init>`） | 不在本文范围 |
| C 档 | 方法/局部 `fun make() = T().apply{...}`（写在方法体） | 不在本文范围 |

## 2. 字节码事实（kotlinc 2.3.0 / javac 21 实测）

### 2.1 四种 Kotlin 形态

夹具（`Fixture.kt`，`JTurret` 是带 `public int health = -1` 的 Java 类）：

```kotlin
class Turret(var health: Int = -1)                                   // 1: Kotlin var -> setter 调用
object KJavaBlocks { val duo = JTurret("duo").apply { health = 90 } } // A: object 持有者
val topLevel: JTurret = JTurret("top").apply { health = 100 }         // A: 顶层 val 持有者
class HolderProp { val duo = JTurret("prop").apply { health = 100 } } // B: 具名类属性
fun make(): JTurret = JTurret("fun").apply { health = 7 }             // C: 方法体
```

`javap -c -p` 关键读数：

| 形态 | 写入位置 | 写入指令 | Fieldref owner |
|:--|:--|:--|:--|
| `class Turret(var health)` | `Turret.setHealth` | `invokevirtual` | `Turret` |
| `object KJavaBlocks` | `KJavaBlocks.<clinit>` | `putfield` | `JTurret`（Java public 字段） |
| 顶层 `val topLevel` | `HolderFileKt.<clinit>` | `putfield` | `JTurret` |
| `class HolderProp` | `HolderProp.<init>` | `putfield` | `JTurret` |
| `fun make()` | `HolderFileKt.make` | `putfield` | `JTurret` |

`object` 持有者的 `<clinit>` 原文（v1 / v2 只差常量 `90` / `100`）：

```text
 0: new           #2   // class KJavaBlocks
 3: dup
 4: invokespecial #19  // Method "<init>":()V
 7: putstatic     #22  // Field INSTANCE:LKJavaBlocks;
10: new           #24  // class JTurret
13: dup
14: ldc           #25  // String duo
16: invokespecial #28  // Method JTurret."<init>":(Ljava/lang/String;)V
19: astore_0
20: aload_0
21: astore_1
22: iconst_0
23: istore_2
24: aload_1
25: sipush        90    // v2: bipush 100
27: putfield      #32  // Field JTurret.health:I
30: aload_0
31: putstatic     #17  // Field duo:LJTurret;
34: return
```

要点：

1. 接收者 **不是** `aload_0`；Kotlin 先 `astore_0` 保存对象、`aload_0; astore_1` 复制出
   `$this$apply` 局部变量，`PUTFIELD` 的接收者是 `aload_1`。跟踪必须穿透局部变量副本，
   **相邻指令猜测会看错接收者**。
2. `apply` 内联不产生合成类（类列表只有 `FixtureKt` / `HolderProp` / `KJavaBlocks` / `Turret`）；
   没有额外 `Function0` lambda 对象。
3. `putstatic` 写入的也是同一个 `aload_0` 来源 —— 这就是“唯一 provenance”的字节码依据。
4. Kotlin `var` 走 `invokevirtual Turret.setHealth(I)V`，调用方没有 `PUTFIELD`，**不在 A 档范围**；
   A 档只覆盖目标字段是 **Java public 字段 / `@JvmField` 字段**的形态。
5. B 档写在 `H.<init>`、C 档写在方法体，均不满足“静态持有者 `<clinit>`”前提，**不在 A 档范围**。

### 2.2 Java 等价形态

javac 对同一语义（Java 静态初始化块）生成同构字节码（少了 Kotlin 的 apply 局部变量副本）：

```java
public class Blocks {
    public static final Turret duo;
    static { Turret t = new Turret("duo"); t.health = 90; duo = t; }
}
```

```text
 0: new           #7   // class ktap1/Turret
 3: dup
 4: ldc           #9   // String duo
 6: invokespecial #11  // Method ktap1/Turret."<init>":(Ljava/lang/String;)V
 9: astore_0
10: aload_0
11: bipush        90
13: putfield      #14  // Field ktap1/Turret.health:I
16: aload_0
17: putstatic     #18  // Field duo:Lktap1/Turret;
20: return
```

单元测试与 E2E 探针因此可以用 javac 夹具覆盖同一形态；Kotlin 真实字节的差异仅限
“局部变量副本数量”，候选分析对两者都按 §3.2 的局部变量溯源处理。

### 2.3 L0 对静态持有者的实测行为

`InitializerDrift.detect(oldBytes, newBytes)` 对上述 v1/v2 字节的实测：

| 类 | detect 结果 | 原因 |
|:--|:--|:--|
| `KJavaBlocks`（object 持有者） | `[]` | 静态字段在 `fields()` 被过滤；`<clinit>` 不在扫描范围 |
| `HolderFileKt`（顶层 val 持有者） | `[]` | 同上 |
| `HolderProp`（具名类属性，B 档） | `[health]` | 写在 `<init>`，作为“继承字段初值变化”被 L0 报告 |

结论：A 档是 L0 之外的独立盲区通道；A 档重放**不需要** L0 抑制记账（L0 不会为静态持有者
生成 `[INIT-DRIFT]`）。

## 3. 候选判定

### 3.1 开关与持有者白名单

```text
-Dremold.agent.init_drift.apply_holders=<持有者类 fqcn,...>
```

- 点号分隔、逗号分隔；空值 = A 档关闭。
- 只有 `newClass.name`（内部名）命中白名单的类才进入候选分析。
- 外部重定义通道（`AnnotationTransformer`）只有在
  `ApplyInitializerReplay.mightReplay(diff)` 为真时才补调 `InitFix.transform`；
  主路径 `RemoldAgent` 本来就会对每个 changed 类调用 `InitFix.transform`。

### 3.2 `<clinit>` 形态分析

对持有者的新旧 `<clinit>` 各做一次**直线抽象解释**（不依赖相邻指令启发式）：

- 拒绝 try/catch；拒绝任何跳转/分支/`ATHROW`/`monitor`/`RET` 等控制流或未建模指令；
  这些情况整类放弃并 warn（fail-closed）。
- 值域：`NEW` 分配点（唯一 provenance）或编译期常量（`ICONST`/`LCONST`/`FCONST`/`DCONST`/
  `BIPUSH`/`SIPUSH`/数值 `LDC`；数值转换指令折叠常量）。
- `ASTORE`/`ALOAD`（以及 `ISTORE`/`ILOAD` 等）视为值复制：Kotlin 的
  `astore_0; aload_0; astore_1; aload_1` 链因此仍指向同一个 `NEW` 站点。
- 允许的 `NEW` 用途只有三类：
  1. 该对象的 `<init>` 调用（`invokespecial <init>`）；
  2. `PUTFIELD` 的接收者（字段写入候选）；
  3. `PUTSTATIC holder.dest` 的值（唯一存储点）。
  其余使用（作为方法参数/接收者、存入别的字段、返回）都视为**逃逸**，该对象拒绝并 warn。
- 对象必须恰好有一个 `PUTSTATIC`（多于一个 = 多个静态字段共享同一对象 → 唯一 provenance
  不成立，拒绝）；不存储到任何静态字段 → 没有可取回的存量对象，静默跳过。
- 目标字段写入必须是 `PUTFIELD owner == T`（`T` = `NEW` 的类型）；owner 是父类等其他类型 →
  整对象拒绝。
- `PUTSTATIC` 的 owner 必须是**本持有者类**，且该静态字段必须**声明在持有者自身**
  （不沿父类解析），描述符必须精确等于 `LT;`。
- 同一 `<clinit>` 中多个 `T().apply{...}`（多个 dest）各自独立分析；不同对象互不影响。

### 3.3 旧/新对照

- 以 `dest` 静态字段名对齐新旧两版的对象；旧版没有该 dest、dest 描述符或对象类型变化 →
  warn 跳过。
- 以 `字段名 + 描述符` 对齐同一个对象上的 `PUTFIELD`：
  - 旧版没有该字段写入 → 存量值未知，无法给出条件，warn 跳过；
  - 新版不再写该字段 → “删除写入”不在重放范围，warn 跳过；
  - 旧/新值都必须是**编译期常量**，且按字段类型转换后的自然值必须不同。
- 目标字段元数据（`HierarchyTreeOracle`，全程不触发类加载）：
  - 必须是**基本类型**（`Z/B/C/S/I/J/F/D`）、非 `static`、非 `final`、非 `synthetic`；
  - 不是 `this$`/`val$` 捕获字段；不带 `@RemoldReinit`（显式注解路径优先）；
  - 必须能从字节码元数据解析到声明类。
- 同一 `dest` 上的多个字段写入各自独立判定；一个字段被拒不影响同对象其它字段。

## 4. 写入协议

### 4.1 补丁体

每个通过判定的 `(dest, T.field)` 生成**一个** `()V` 静态补丁方法：

```text
GETSTATIC holder.dest              // 取回 <clinit> 唯一存储的那一个存量对象
<新常量>                           // 按字段描述符压栈（Z/B/C/S/I 均以 int 压栈）
INVOKEDYNAMIC field (LT;F)V
    bootstrap = HotswapBridge.bootstrapExpected
    bsmArgs   = [KIND_CONDITIONAL_EXPECTED, PUTFIELD, T.class, holder.class, 旧常量]
RETURN
```

- `KIND_CONDITIONAL_EXPECTED` 在 `HotswapBridge` 里按 `T.field` 解析字段偏移，做
  “当前值 == 旧初值才写”的 Unsafe 条件 CAS；运行期被改过的值（当前值 ≠ 旧初值）**跳过**。
- `float`/`double` 走 raw bits 的 int/long CAS（与 InitFix 既有约定一致）。
- 写入目标字段由 `bootstrapExpected` 的 `owner` 参数决定（`T`），不是宿主持有者。
- 隐藏补丁类以**持有者类**为 host 定义（`NESTMATE`），因此可以直接 `GETSTATIC` 持有者的
  私有静态字段 `dest`；`dest` 是否 `final` 不影响（只读）。
- 链接期复核：目标字段的实际修饰符必须与调用点一致（`PUTFIELD` → 非 static），且不得是
  `final`；不一致时 indy 链接失败，按单字段隔离处理。该复核防的是“分析用的是 redefine
  之前的字节码、字段本轮被改成 `final`/`static`”的窗口。

### 4.2 运行期安全前置检查

补丁执行前（`InitFix.applyPatch`，重定义生效之后）对每个候选做一次对象检查：

1. 反射读取 `holder.dest`；
2. `null` → warn 跳过（不执行 CAS，避免 Unsafe 对 null base 的无意义写）；
3. 运行时类型不是 `T` 的实例 → warn 跳过（避免把 `T` 的字段偏移套到不匹配的对象上）；
4. 通过后才调用补丁方法。

`GETSTATIC` 仍是补丁方法内部取对象的唯一来源；前置检查只是安全门，不改变 provenance。

### 4.3 只执行一次

A 档任务是**静态任务**：重定义成功后整个补丁只执行一次，不枚举存活实例、不按实例重复；
`PatchPlan.hasStatic()` 把它计入，因此“没有存活实例”不会导致补丁被跳过。

## 5. 诊断、报告与台账

- 报告：`PatchReport.applyReplayFields`（独立于 `instanceFields`/`staticFields`/L1 的
  `replayFields`），键为 `dest -> T.字段`（如 `duo -> ktap1.Turret.health`），值为
  `replayed` 或 `rejected: 原因`。`PatchReport.applyReplayGenerated()` 独立判定。
- 日志：统一前缀 `[INIT-APPLY-REPLAY]`；warn 用于拒绝/失败/跳过原因，info 用于成功明细与
  本轮写入/跳过统计。统计键为 `T#字段`（多个 dest 指向同名类型字段时合并计数）。
- **不写 `FieldLedger`**：A 档是显式开启的尽力重放，不是“本应补而未补”的欠账；失败/跳过
  只在日志与统计里可见。
- **不改 `patchGenerated`**：只有 A 档任务时不把普通补丁标记为已生成。
- 运行期写入/跳过统计与 L1 的 `KIND_CONDITIONAL_EXPECTED` 统计共用底层计数表，但按
  owner 前缀拆分输出：L1 的 owner 是锚点类自身，A 档的 owner 是 `T`。

## 6. 安全门汇总与非目标

必须同时满足（缺一不可，任一失败即不写）：

1. 持有者类在白名单内；
2. 新旧 `<clinit>` 都是可分析的直线形态；
3. 对象由唯一 `NEW T` 产生、唯一存储到 `holder.dest`、无其它逃逸；
4. `dest` 声明在持有者自身、`static`、描述符精确 `LT;`；
5. 目标字段基本类型、非 `final`、非 `synthetic`、非 `@RemoldReinit`、可解析；
6. 旧/新值都是编译期常量且不同；
7. 运行期 `dest` 非 null 且类型确实是 `T`；
8. 运行期字段当前值仍等于旧初值（条件 CAS）。

非目标：

- 不重放构造器、不重跑 `<clinit>`、不重建对象（只改那一个存量对象的字段值）；
- 不处理 Kotlin `var` setter 形态（`invokevirtual`）、B 档（`H.<init>`）、C 档（方法体/局部）；
- 不处理引用类型目标字段、`final` 字段、非编译期常量初值；
- 不处理多个静态字段共享同一对象、目标对象在 `<clinit>` 内逃逸的形态；
- 不替代 L1 / `@RemoldReinit` / `@OnReload`；
- 默认关闭，不改变未开启时的任何存量数据。

残余风险（设计上已知、接受）：

- 外部重定义通道（IDE/外部 agent）的“重定义是否真的被 JVM 接受”没有独立信号；与 L1 一样，
  延迟后置任务会按补丁计划执行。若外部 redefine 被 JVM 拒绝，A 档仍可能写入新常量
  （数据变了、代码没变）。主路径（`RemoldAgent` 内部批次）只对 redefine 成功的类调用
  `afterRedefine`，不存在该窗口。
- 条件 CAS 的粒度是“整字段值等于旧初值”，不做对象级指纹；同一字段若有其它并发写者，CAS
  语义仍然安全（写失败即跳过）。

## 7. 验收

命令（DCEVM 参数由 Gradle 任务注入）：

```bash
./gradlew :tests:test --tests ApplyInitializerReplayTest   # 纯分析单测
./gradlew :tests:kotlinApplyProbe -Pp05.jbr=<DCEVM JDK>    # 真实 processChanges + redefine E2E
./gradlew :tests:initFixOracle                             # InitFix 既有回归
./gradlew :tests:doubleBraceProbe -Pp05.jbr=<DCEVM JDK>    # L1 回归（104/0）
./gradlew check                                            # 全量
```

分析单测覆盖：白名单命中 / 关闭 / 非白名单 / 常量相同 / 四种基本类型 / 旧值非常量 / 新值非常量 /
引用类型 / `final` / `dest` 描述符不匹配 / 一个对象多个 dest / Kotlin `var` setter 形态（方法调用逃逸）/
普通持有者无 apply 形态。

E2E 探针覆盖（真实 redefine）：

1. **重放**：存量对象 90 → 100，且仍是 `GETSTATIC dest` 取回的那一个对象（对象身份不变）；
2. **条件性**：运行期改成 42 的字段保持 42（expected-CAS 跳过）；
3. **无漂移**：初值相同的字段不重放、不进报告；
4. **子字类型**：`byte`/`short`/`char` 目标字段走子字 expected-CAS，7→9 / 300→400 / `'A'`→`'B'`；
5. **去触发对照**：去掉白名单 → 存量对象保持 90、无任何 A 档日志；
6. **null dest**：warn + 跳过，detached 旧对象不被写；
7. **台账/标记**：`FieldLedger` 为空、`patchGenerated=false`、L0 无 `[INIT-DRIFT]`。

变异检查（防空转）：

- 白名单判定恒真 → 非白名单单测与探针 AP2 必须变红；
- expected-CAS 改为 `KIND_FORCE`（无条件写）→ 运行期改值用例必须变红（42 → 100）；
- 恢复后必须重新全绿。
