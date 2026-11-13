# 枚举常量集合变更：为什么不做运行期修复（实测记录）

> 本文件记录**测量方法与读数**，供布局门的拒绝策略与设计文档引用。实现与验收状态见
> [`docs/status.md`](../status.md)；复现入口见 [`tests/enum-append-probe/`](../../tests/enum-append-probe/README.md)
> （独立探针，不挂 `check`）。

---

## 1. 问题

热更不会重跑已初始化类的 `<clinit>`，因此枚举常量集合变化（`ENUM_CONSTANT_SET_CHANGED`）在运行期
不会自动生效：追加的常量不被构造、`$VALUES` 不扩容，删除的常量仍留在旧 `$VALUES` 里。

一个自然的候选方案是**事后补**（本节称为"候选方案"）：

1. 用 `<clinit>` 切片或构造器把新增常量实例写进对应静态字段；
2. 重建并写回 `$VALUES`（较新的 javac 生成合成 `$values()`；旧 javac 与 ECJ 在 `<clinit>` 里内联构造）；
3. 清空 `java.lang.Class` 上的 `enumConstants` 与 `enumConstantDirectory` 两个缓存，迫使
   `values()`/`valueOf()`/`EnumMap`/`EnumSet` 重新加载。

本题要回答的是：**这个候选方案够不够**。测量结论是**必要但不充分**——冷路径能修好，但三类已存在的
消费方修不回来，且第一类是静默的。

---

## 2. 测量环境与方法

| 项 | 值 |
|:--|:--|
| JVM | JBR 21.0.9（`-XX:+AllowEnhancedClassRedefinition`），仅测 C2 |
| 重定义 | `Instrumentation.redefineClasses`，由 premain agent 持有 |
| 字节码形态 A | `javac 21`：合成 `private static Color[] $values()`，`<clinit>` 末尾 `invokestatic $values(); putstatic $VALUES` |
| 字节码形态 B | 真 `javac 1.8.0_332`：`$VALUES` 数组构造内联在 `<clinit>`（`anewarray` + `getstatic` + `aastore`） |
| 观测 | 每次运行输出 `OBS key = reading`；每个场景**独立 JVM**（redefine 不可回滚） |
| 对照 | `-Xint`（无 JIT）、`-XX:CompileCommand=exclude,probe/Probe::hotLen`、再次 `redefineClasses` 触发 deopt |
| 缓存预填充 | 修复前先调用 `values()`/`valueOf`/`getEnumConstants`/新建 `EnumMap`/`EnumSet`/switch，确认两缓存**非空**（`enumConstants` 长度 2、`enumConstantDirectory` size 2） |

两套字节码都跑同一组场景，`$VALUES` 重建分别走"反射调 `$values()`"与"手工 `Array.newInstance` + 拷贝"。

---

## 3. 读数

以下读数均为探针实际输出；未标注的机制解释一律是推断。

### 3.1 无修复基线（两种形态一致）

```
OBS baseline.after.mh.BLUE            = null
OBS baseline.after.values             = len=2 names=RED,GREEN,
OBS baseline.after.$VALUES.sameArray  = true
OBS baseline.after.valueOf.BLUE       = THREW IllegalArgumentException: No enum constant test.Color.BLUE
OBS baseline.after.getEnumConstants   = array(len=2)[RED#0,GREEN#1]
OBS baseline.switch.sw(blue)          = THREW AIOOBE: Index 2 out of bounds for length 2
```

新增常量字段在运行期类里**可以解析**（`GETSTATIC` 不抛错），但值是 `null`；`$VALUES` 引用未变。

### 3.2 候选方案在冷路径成立

```
OBS repair.$VALUES              = array(len=3)[RED#0,GREEN#1,BLUE#2]
OBS repair.values               = len=3 names=RED,GREEN,BLUE,
OBS repair.valueOf.BLUE         = enum[BLUE#2]
OBS repair.getEnumConstants     = array(len=3)[RED#0,GREEN#1,BLUE#2]
OBS repair.newEnumMap           = size=2 get(blue)=b
OBS repair.newEnumSet.allOf     = size=3 ; iterate=3 ; of(blue).size=1
OBS repair.serialize.blue       = back==blue:true
```

### 3.3 "清缓存"是必要条件，且有时序要求

静止 JVM（redefine 与补丁之间没有线程消费枚举）下，JBR 21 的 redefine 本身就把两个缓存置空了；
但真实应用必然有并发消费者，redefine→补丁窗口里 `valueOf`/`getEnumConstants` 会把缓存**重新填成
旧的 2 元素视图**。此时逐个清缓存的实测差异：

| 清理方式 | `valueOf("BLUE")` | `getEnumConstants()` | 新建 `EnumMap` | 新建 `EnumSet` | 反序列化 |
|:--|:--|:--|:--|:--|:--|
| 只清 `enumConstants` | IAE | 3 | 正常 | 3 | `InvalidObjectException` |
| 只清 `enumConstantDirectory` | IAE（目录由陈旧 `enumConstants` 重建） | 2 | AIOOBE len=2 | 2 | `InvalidObjectException` |
| 两个都清 | 3 | 3 | 正常 | 3 | 成功 |

两个缓存必须在 `$VALUES` 重写**之后**一起清，且要能覆盖窗口内已被重新填充的值。

### 3.4 已 JIT 编译的读者看不到新常量（deopt 前）

预热 `values().length` 1e6 次（`PrintCompilation` 确认 `test.Color::values` 已 tier3/4 编译）后再
redefine + 修复：

```
OBS jit.after.repair.rawValuesLen      = 3     # Unsafe 直读：内存里确实是新数组
OBS jit.after.repair.valuesLen         = 2     # 同一 JVM 里 Color.values().length
OBS jit.after.repair.hotLen.sample0..4 = 2 2 2 2 2   # 三次重复均如此
OBS jit.after.repair.mhValuesLen       = 2     # 新建 MethodHandle 读 GETSTATIC 也是 2
OBS jit.after.forcedDeopt.hotLen       = 3     # 再次 redefine（触发 deopt）后立刻变 3
```

`-Xint` 下同一读点是 3。即：`Unsafe`/反射写 `static final` 字段对**已编译**读取方不可靠可见，
而热更恰恰总发生在真实运行的进程里。

### 3.4.1 追加测量：`$VALUES` 不是常量折叠，且可直接 deopt 读者

（探针新增场景 `deopt-*` / `jit-inline` / `jit-values-write` / `jit-spin-reader-deopt`；JBR 21.0.9）

- **内联的是 `values()`，不是数组内容**：`-XX:+PrintInlining` 显示 `probe.Probe::hotLen` 内联了
  `test.Color::values (10 bytes)`；`$VALUES` 是普通 `getstatic` 加载（不是 `ConstantValue`）。
- **纯写字段、不 redefine**：预热后把 `$VALUES` 换成 4 元素数组，热读者仍读 3、直接调用
  `Color.values().length` 也是 3；随后只对**读者类**做 `redefineClasses(Probe, 同字节)`，
  `hotLen` 立即变 4。→ 旧读数来自已编译代码里被提升的字段加载，不是编译期常量折叠。
- **直接 deopt 的公共 API 实测可用**：`redefineClasses(Reader, 同字节)` 与
  `retransformClasses(Reader/Enum)` 都成功；后者实测 `OBS vdeopt.retransformColor = ok`。
- **真机热更路径**：v1（2 常量）热读者/spinner 保持 2；只对读者类 deopt（不再 redefine 枚举）后
  `hotLen=3`、spinner 观察到 `[2, 3]`（`OBS spj.after.readerDeopt.*`）。
- **可写性**：`Unsafe.putObject(staticFieldBase/Offset)` 可写 `static final $VALUES`；
  `Field.set` / `VarHandle.set` 均不可（后者抛 `UnsupportedOperationException`）。
- **但这不等于"只差 deopt"**：`$SwitchMap$` 持有类与存量 `EnumMap`/`EnumSet`（§3.5/§3.6）不会
  因读者 deopt 修复；且必须枚举**全部**已加载读者类逐个 deopt，遗漏任何一个就是静默旧值，与
  fail-closed 策略冲突。结论（§4）不变。

### 3.4.2 阶段 C 作为写入后的 deopt 屏障（static final 引用写也成立）

探针场景 `stagec-static`（classpath 已含 3 个常量）：

- 预热 `BlueReader.read()`（直接 `GETSTATIC Color.BLUE`）与 `Probe.hotBlue()` 1e6 次；
- 用 Unsafe 把 `BLUE` 换成 `GREEN`：热读者仍读 `BLUE`（直读与内联路径均 stale）；
- 对宿主 `Color` 再做一次 `redefineClasses`（同 schema、同字节）：`hotBlue` 与 `BlueReader.read`
  立刻读到 `GREEN`。

即：**第二次 redefine 不只关闭"新方法体读未补字段"的窗口，也充当补丁写入后的 JIT deopt 屏障**。
本场景里读者只直接读静态字段（没有内联宿主方法），宿主 redefine 仍把它带进 deopt；因此
"跨类直接 `getstatic` 的读者不会跟着 deopt"这一担心在实测中不成立（至少对运行时初始化的
`static final` 引用字段成立）。

边界：`static final` **编译期常量**（`ConstantValue`，基本类型/String）的读者已把字面量内联进
class 文件，deopt 不会改回——这正是 `@RemoldReinit` Javadoc"static final 编译期常量对批外读者无效"
所标注的限制；deopt 只救运行时初始化的引用/非常量字段。

### 3.5 已初始化的 `$SwitchMap$` 持有类无法补 case

`switch-on-enum` 走 `$SwitchMap$Color`（javac 把该字段放在合成类 `X$1` 里，`static final int[]`，
长度取自旧 `values().length`）：

```
OBS baseline.switchMap            = holder=probe.SwV1$1 fieldFlags=[static final] len=2 vals=[1, 2]
OBS repair.hazard.switch.sw(blue) = THREW AIOOBE: Index 2 out of bounds for length 2
OBS repair-resize.switch.sw(blue) = default       # 扩到 len=3 后不抛错，但只能落 default
```

扩容只能消掉 AIOOBE，**补不出**新常量对应的 case：映射值是编译期写死的字面量，新 case 若在**已重新
定义但未重新初始化**的消费类里，旧数组里没有它的槽位 → 静默落 `default`。这恰好命中"加常量 + 加 case"
的主场景；而未重新定义的旧消费类本就不认识新常量，扩不扩容都一样。

另外，修复前就让新消费类首次初始化会 NPE（`Color.BLUE` 为 null）→ `ExceptionInInitializerError`，
该类此后永久 erroneous。

### 3.6 存量 `EnumMap` / `EnumSet` 实例无法扩容

```
OBS repair.hazard.preEnumMap.put(blue)     = THREW AIOOBE: Index 2 out of bounds for length 2
OBS repair.hazard.preEnumMap.get(blue)     = THREW AIOOBE: Index 2 out of bounds for length 2
OBS repair.hazard.preEnumSet.add(blue)     = added=true size=3 contains=true
OBS repair.hazard.preEnumSet.iterate       = THREW AIOOBE: Index 2 out of bounds for length 2
OBS repair.hazard.preEnumSet.toArray       = THREW AIOOBE: Index 2 out of bounds for length 2
OBS repair.hazard.preEnumSet.complementOf  = 1      # 期望 2（GREEN+BLUE）：静默少 1
```

`EnumMap` 的 `vals`/`keyUniverse` 按旧宇宙长度分配；`EnumSet` 的 bitmask 允许新位、但 `universe`
数组不允许。两者都无法在原对象上"扩容成新宇宙"——只能丢弃重建，而引用在业务手里。

### 3.7 `Class` 影子副本携带旧宇宙

JBR 结构性 redefine 会把静态字段里持有的旧 `Class` 引用改写成**影子**对象（与仓库既有风险同源）：
影子类携带旧的枚举宇宙，清 canonical 类的缓存对它无效。

```
OBS mirror.valueOf(shadow,"BLUE")     = THREW IllegalArgumentException
OBS mirror.isInstance(blue)           = false
OBS mirror.newEnumMap(shadow).put(blue) = THREW ClassCastException: class test.Color != class test.Color
```

### 3.8 其它实测事实

- 反射 `Constructor.newInstance` 对枚举被 JDK 显式禁止（`Cannot reflectively create enum objects`），
  探针改用 `Unsafe.allocateInstance` + 写 `Enum.name/ordinal`；Remold 的实际补丁走 NESTMATE 隐藏类里的
  `invokespecial <init>`，不经过 `Constructor`（见 §5）。
- DCEVM 能力：可新增 `<init>(Ljava/lang/String;I)V` 并由 `MethodHandle` 调用；常量特定类体
  （`Color$1`）可经 `Lookup.defineClass` 定义并物化，能进 `$VALUES`。即"结构性新增"本身不是限制项。

---

## 4. 结论：为什么维持拒绝

| 残留 | 是否可修 | 依据 |
|:--|:--|:--|
| 冷路径 `values()`/`valueOf`/新建集合/反序列化 | 可 | §3.2 |
| 两个 `Class` 缓存 | 可（必须两个都清且时序在 `$VALUES` 之后） | §3.3 |
| 已 JIT 编译的读者 | 只能靠 deopt（再次 redefine）；不稳定且代价高 | §3.4 |
| 已初始化的 `$SwitchMap$` 持有类 | 只能重建映射表（要解析新 `<clinit>` 的常量→label 映射）；扩容不补 case | §3.5 |
| 存量 `EnumMap`/`EnumSet` 实例 | 不可（无法原地扩容） | §3.6 |
| 影子 `Class` | 不可（无法穷举持有者） | §3.7 |
| 已缓存的 `values()` 数组副本、懒加载的旧代码 | 不可（任何热更都存在的陈旧副本） | §3.6 / §3.7 同类 |

只要有一项不可修，放行就不是"部分生效"，而是**把错误从"热更没生效"变成"静默走错分支或延迟
AIOOBE"**。按"拿不准就拒绝"的判定准则，`ENUM_CONSTANT_SET_CHANGED` 维持拒绝，并在拒绝日志里点名
三类主要阻断（`$VALUES`、`$SwitchMap$`、`EnumMap`/`EnumSet`）与 JIT/deopt，避免用户误以为"只是
InitFix 没实现，值得自己补"。

决策记录见 [`08-decisions.md`](08-decisions.md) 的 `D-ENUM-1`。

---

## 5. 枚举构造器的字节码事实（`(Ljava/lang/String;I)V`）

源码级"无参"枚举构造器在字节码里不是 `()V`（`javap -p -s` 实测）：

| 源码 | 字节码描述符 |
|:--|:--|
| `enum E { A; E() {} }` | `<init>(Ljava/lang/String;I)V` |
| `enum E { A; E(int x) {} }` | `<init>(Ljava/lang/String;II)V` |

`javac` 会把 `name`/`ordinal` 两个合成参数隐式插到最前面，因此：

- **不能**把"无参构造器"等价成 `()V` 来查找；枚举上永远找不到 `()V`。
- InitFix 的切片提取**不依赖**这一点：它按 `<clinit>`/`<init>` 里的实际调用描述符工作。实测
  v1 `enum E { A(1), B(2); final int v; E(int v){this.v=v;} }` → v2 增加 `E(){this.v=0;}` 与常量 `C`
  时，`C` 的补丁被接受：`initStatic$C` = `new E; dup; ldc "C"; iconst_2; invokespecial
  E.<init>(Ljava/lang/String;I)V`（NESTMATE 隐藏类可调用 private 枚举构造器），逐常量构造状态保留。
- 新增构造器本身出现在 `ClassDiff.addedMethods`，布局门不判方法增减；无增强重定义能力时该类按
  "结构变化"整类摘批（`EnvironmentProbe.mustSkipForMissingEnhancedRedefinition`）。
- 唯一与 `(String,int)` 相关的**真实**残留：旧 javac 形态（本次实测 javac 8）在 `<clinit>` 内联构造 `$VALUES` 时会
  `getstatic C`，该读点落在 `C` 的写入切片之外，`subsequentProcessingReason` 会拒绝 `C`。这属于
  "追加常量"路径（当前被布局门拒绝，不会走到 InitFix），不是独立缺陷；若将来要支持追加，必须先
  放宽这一处（限定 `ACC_ENUM` 静态字段、`<clinit>` 内 `$VALUES` 构造区），见 §6。

---

## 6. 如果将来要支持追加常量

最小闭环需要同时具备（缺一不可）：

1. 布局门两个入口（批次 `applyRedefineLayoutGate` 与动态 `decideDynamicLayout`）共享同一个放行判据：
   仅增强重定义可用、仅追加（无删除、无顺序漂移）、切片可提取。
2. `$VALUES` 重建（有合成 `$values()` 时可反射调用；旧形态需手工拷贝 + 追加），并在之后清空
   `Class.enumConstants` 与 `enumConstantDirectory`（两者都要）。
3. 已初始化消费类的 `$SwitchMap$`/`$SWITCH_TABLE$` 重建：解析新 `<clinit>` 的"常量 → label"映射，
   用当前 `values()` 重新填表；仅扩容不补 case 会静默落 `default`。
4. 触发 deopt 让已编译读者看到新常量：实测可行手段是读者类 `redefineClasses(同字节)` /
   `retransformClasses`（见 §3.4.1）；但必须覆盖全部已加载读者，遗漏即静默旧值。
5. 存量 `EnumMap`/`EnumSet` 的判定：无法修 → 只能拒绝或显式降级为"尽力而为"，并让用户看见。
6. 影子 `Class` 与懒加载旧代码：当前无法闭环，必须写进"不支持"清单。

在 1~6 全部具备前，这条路径的收益（一个很少触发的枚举扩充场景）低于把错误变成静默错分支的风险。

---

## 7. 复现

```bash
bash tests/enum-append-probe/build.sh
bash tests/enum-append-probe/run.sh
grep '^OBS' tests/enum-append-probe/b/logs/all.log
```

环境变量、场景清单与关键读数见 [`tests/enum-append-probe/README.md`](../../tests/enum-append-probe/README.md)。
