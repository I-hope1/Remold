# 07 P0.5 前置微验证实测数据

本文件记录 P0.5 三项前置验证的**实测方法与原始数据**，供两阶段 Schema-First 重定义（`04-target-design.md` §1）与构造器尾部插桩（`04-target-design.md` §4）的可行性判断使用。

实现状态与结论摘要以 `docs/status.md` 为准；本文件只承载可复现的实验事实。

## 1. 验证目标

`04-target-design.md` §6 定义 P0.5 为 P1 两阶段协议的门槛，三项分别为：

1. 在 JBR 17/21 上实测连续两次 Schema Redefine（阶段 A → 阶段 C）的可行性与 GC 停顿。
2. 验证阶段 A 预注入全新私有方法、Lambda、内部类后，伴生类在阶段 B 链接调用的合法性与稳定性。
3. 压测基于 `IterateOverInstancesOfClass` 的 Native 代码在百万级对象下的耗时与内存开销。

## 2. 探针与运行方式

| 项 | 位置 |
|:--|:--|
| 探针主类 | `tests/src/P05Probe.java` |
| 最小 agent | `tests/src/P05Agent.java`（仅暴露 `Instrumentation`，不装任何 transformer） |
| Gradle 任务 | `tests/build.gradle` 的 `p05Probe`（不挂 `check`，需要 DCEVM 能力） |

```bash
./gradlew :tests:p05Probe -Pp05.jbr=<dcevm-jdk-home>
```

**为什么用最小 agent 而不是 `remold-agent.jar`**：本验证要测的是 JVM/DCEVM 的原生能力。挂真实 agent 会让 `LayoutGate`、`InitFix` 与 Mindustry 钩子介入被测的重定义（实测中确实出现过 `[EXTERNAL-HOTSWAP] Layout change rejected by LayoutGate` 与 InitFix 的字段补丁告警），从而污染测量并混淆失败归因。

**夹具编译**：探针用 `javac` 现场编译三个版本到**各自独立的输出目录**，再用一次性 `URLClassLoader` 加载 V1、通过 `Instrumentation.redefineClasses` 依次推入 V2（阶段 A）与 V3（阶段 C）。每个版本独立目录是必需的：共用输出目录会让后一次编译覆盖前一次产物，导致「加载到的其实是最新版本」，测不到真实的跨版本重定义。

## 3. 测量环境

| 项 | 值 |
|:--|:--|
| 运行时 | JetBrains Runtime 21.0.9+1-b1163.86 (JBR 21，DCEVM) |
| 参数 | `-XX:+AllowEnhancedClassRedefinition -javaagent:p05-agent.jar -Xmx1g` |
| GC | JBR 21 默认（G1） |
| 平台 | Windows 11 x64 |
| 测量方式 | `System.nanoTime()` 包裹单次 `redefineClasses`；GC 计数与耗时取 `GarbageCollectorMXBean` 前后差值 |

## 4. M1：连续两次 Schema Redefine

夹具形状：

- V1：`a = 1`，`hi()` 返回 `"v1:" + a`，`marker()` 返回 1。
- V2（阶段 A 骨架）：新增字段 `b = 42`、新增私有实例方法 `nf()`、新增私有静态方法 `ns()`、新增 lambda `mk()`；**旧方法体一行不改**。
- V3（阶段 C）：方法体改为读新字段，`marker()` 改为 2。

### 4.1 M1a：阶段 A（改 schema）→ 阶段 C（仅改方法体）

| 阶段 | n | min | p50 | p95 | max | avg | GC |
|:--|:-:|--:|--:|--:|--:|--:|:--|
| 阶段 A（新增字段 + 新成员） | 8 | 16066 µs | 30870 µs | 37822 µs | 37822 µs | 29003 µs | 8 次 / 39 ms |
| 阶段 C（仅方法体） | 8 | 24479 µs | 27299 µs | 35163 µs | 35163 µs | 27731 µs | 0 次 / 0 ms |

语义断言（全部通过）：

- 阶段 A 之后，存量实例的新字段读 `0`，阶段 A 之后新建的实例读 `42`。
- 阶段 A 期间旧方法体仍然只读旧字段（`"v1:1"`），不触碰新字段。
- 阶段 C 之后，存量实例读 `"v2:1,0"`（新字段仍是零值，待 InitFix 补），新建实例读 `"v2:1,42"`。

### 4.2 M1b：连续两次 Schema 变更（加字段 b，再加字段 c）

| 阶段 | n | min | p50 | p95 | max | avg |
|:--|:-:|--:|--:|--:|--:|--:|
| 第一次（加 b） | 6 | 23948 µs | 35096 µs | 39881 µs | 39881 µs | 33528 µs |
| 第二次（加 c） | 6 | 22050 µs | 29453 µs | 32052 µs | 32052 µs | 28172 µs |

连续两次 schema 变化均被运行时接受，第二次之后 `b = 42`、`c = 99` 同时可读且已按初始化式赋值。

## 5. M2：阶段 A 预注入成员的伴生类链接

伴生类由 ASM 现场生成（`p05/Companion`），等价于：

```java
public static String call(Object o) { return ((Subj) o).nf() + ":" + Subj.ns(); }
```

定义方式与 `InitFix` 完全一致：`Reflect.defineHiddenClass(host, bytes)`（内部走 `IMPL_LOOKUP` 的 `privateLookupIn` + `NESTMATE`）。

| 断言 | 结果 |
|:--|:--|
| 阶段 A **之前**，同一伴生类无法链接 | 通过（`NoSuchMethodError: 'java.lang.String p05.Subj.nf()'`） |
| 阶段 A **之后**，伴生类可链接新私有实例方法与私有静态方法 | 通过（返回 `"nf:7"`） |
| 阶段 A 之后，新 lambda 调用点可解析并执行 | 通过 |
| 重复定义同一伴生类保持稳定 | 通过 |

负向对照（阶段 A 之前必然失败）是这条断言的先红后绿证据：它排除了「伴生类其实没链接到新成员、只是碰巧没抛错」的可能。

## 6. M3：`LibTool.getInstances` 规模压测

对象为探针内的 `P05Probe$HeapSubj`（1 个 `int` 字段），全部由探针自身强引用持有以保证存活。

| 存活实例数 | 首次调用 | 重复调用 | 返回数组堆占用 |
|--:|--:|--:|--:|
| 0 | 1505 µs | 820 µs | 0 KiB |
| 100 000 | 47 649 µs | 47 554 µs | 465 KiB |
| 500 000 | 400 807 µs | 376 363 µs | 2048 KiB |
| 1 000 000 | 866 052 µs | 882 858 µs | 4096 KiB |

- 扫描耗时随实例数线性增长，约 **0.87 µs/对象**（500k→1M 区间约 0.9 µs/对象）。
- 除返回数组本身（1M 时 4 MiB，即 4 B/引用）外，扫描过程没有额外的 Java 堆分配；`n = 0` 时的 1.5 ms 是 JNI/Tag 通道的固定开销。
- 重复调用无缓存效应，耗时与首次持平。

## 7. 对路线图的影响

1. **两阶段协议在 JVM 层面成立**：连续两次重定义（含连续两次 schema 变化）都被接受，且阶段 A 的「新成员就位、旧逻辑不读新字段」前提可被验证。设计规格不需要为此改动。
2. **单次 redefine 的固定开销约 25~35 ms，与是否改 schema 基本无关**（阶段 A 与阶段 C 的 p50 分别是 30.9 ms 与 27.3 ms）。因此两阶段的代价是**两次 STW 抖动，合计约 55~70 ms**，而不是「阶段 C 免费」。这决定了落地点必须选在加载屏/暂停点，而不是任意游戏帧内。
3. **schema 变更平均触发 1 次 GC（本机约 5 ms/次），纯方法体变更 0 次**。两阶段的 GC 成本主要落在阶段 A。
4. **阶段 B 的全堆扫描是「秒级」而非「毫秒级」**：百万级对象约 0.87 s。这正是构造器尾部插桩要把「全量扫描」换成「增量补齐」的直接理由；也意味着在堆规模大的进程里，阶段 B 的耗时主要由存活对象数决定，与类数无关。
5. **伴生类链接前提成立**：阶段 A 预注入的私有实例方法、私有静态方法与新 lambda，都能被 `NESTMATE` 伴生类正常链接。阶段 B 不需要为了链接而克隆方法体。

## 8. 未覆盖与后续

- **JBR 17**：本机无 JBR 17 运行时，表中数据仅来自 JBR 21。`.github/workflows/ci.yml` 的 `p05-probe` 任务已按 JBR 17/21 双版本矩阵接入，17 的数据由 CI 产出。
- **单机单次采样**：数据来自一台 Windows 机器，未做多轮 JIT 预热隔离与 Linux 对照；redefine 耗时含 safepoint 等待，波动区间较大（min 16 ms ~ max 38 ms），结论只取量级。
- **未测**：`-Xmx` 变化对 redefine 耗时的影响、百万级对象下的 STW 时长、并发业务线程在 redefine 期间的行为。

## 9. 附：两阶段编排端到端实测（`TwoPhaseProbe`）

P0.5 只验证了 JVM 的裸能力，没有验证「三件套串起来」能否跑通。`TwoPhaseProbe` 在接入主流程之前，
用纯函数三件套把 `阶段 A → redefine → 阶段 B 补丁 → 阶段 C redefine` 跑一遍。

```bash
./gradlew :tests:twoPhaseProbe -Pp05.jbr=<dcevm-jdk-home>
```

跑两个场景：一个带构造器尾部插桩，一个不带（对照组，用来证明缝隙真实存在）。**18/18 断言通过**。

| 观察点 | 带插桩 | 对照（不插桩） |
|:--|:--|:--|
| 阶段 A 之后旧方法体 | 仍是 `v1:1` | 仍是 `v1:1` |
| 阶段 A 之后存量实例的新字段 | `0` | `0` |
| 阶段 B 之后存量实例 | 被补成 `42` | 被补成 `42` |
| **枚举之后、阶段 C 之前**新建的实例 | `42` | **`0`** |
| 阶段 C 之后该实例的方法返回值 | `v2:1,42` | **`v2:1,0`（永久过期）** |

耗时（JBR 21，单类，1 个实例）：`build` 0.9~35 ms、阶段 A redefine 17~37 ms、阶段 B 补丁 7 ms（首调 51 ms）、
阶段 C redefine 31~35 ms。

### 两个必须在驱动里守住的前提

1. **分析用阶段 C 字节码，redefine 用阶段 A 字节码**。阶段 A 的 `<init>` 已被换回旧体，里面没有新字段的
   初始化式；拿阶段 A 去抽切片只会得到「没有安全的初始化表达式」，字段直接落进台账。
2. **`RemoldAgent.bytecodeCache` 必须指向阶段 C**。层级 Oracle 优先从缓存读宿主字节码；缓存里若是
   加载器上的旧版本，就找不到新字段，字段访问会被判成「无法确定 protected 状态」而整批拒绝
   （实测踩到过：`cannot determine protected status of field access two/Subj.b:I`）。

### 未覆盖

- **中止回滚**：阶段 B 金丝雀失败后如何干净退回阶段 A，尚未设计验证。
- **多类批次**：探针只跑单类；跨类依赖与批次原子性不在本次范围。
- **真实业务线程并发**：探针在单线程里制造「枚举后新建」，未覆盖多线程分配的竞争。
- **标准 HotSpot**：M6 只在 JBR 21 / Windows 上验证。JVMTI 堆遍历是 HotSpot 共享代码，标准 HotSpot 很可能同样是 STW，但**未测**。

## 10. 附：读穿窗口的成本拆分（`P05Probe` M4）

「快照前置 vs 后置」的取舍取决于窗口里各段各占多少，所以把 `applyPatch` 拆成两半实测：

| 存活实例 | `applyPatch` 总计 | 枚举（`LibTool.getInstances`） | CAS 循环（驱动内部计时） | 弱引用往返 + 伴生类定义（残差） |
|--:|--:|--:|--:|--:|
| 100 000 | 183 ms | 96 ms | 33 ms | 54 ms |
| 1 000 000 | 1551 ms | 1079 ms | **83 ms** | 389 ms |

三条结论：

1. **条件 CAS 循环本身很便宜**：1M 实例 83 ms（约 83 ns/实例）。窗口的大头不是写入，是枚举。
2. **弱引用往返是纯开销**：快照要包成 `WeakReference` 再解引用，1M 实例约 389 ms（占窗口 25%）。后置路径里枚举与补丁背靠背，不存在"跨越 redefine 的长持有"，这段包装没有收益。
3. **前置与后置的差距是约 3.3 倍，不是数量级**：前置的窗口 ≈ 弱引用往返 + CAS ≈ 472 ms；后置 ≈ 枚举 + 弱引用往返 + CAS ≈ 1551 ms（1M 实例）。

因此「前置即时补 + 后置异步补扫」的组合方案收益是 1551 ms → 472 ms（若后置路径同时去掉弱引用包装，两边的残差都还能再降）。但要注意：两阶段协议一旦落地，阶段 A 的旧方法体本来就不读新字段，这段窗口会被直接关掉——组合方案的价值只在单次重定义路径上。

### 10.1 枚举成本到底跟什么走（M5）

1M 实例是**上限场景**，真实堆里目标类的实例数通常少得多。所以要分清：成本跟"目标实例数"走，还是跟"整个堆"走？

| 目标实例 | 无关对象 | 枚举耗时 |
|--:|--:|--:|
| 10 000 | 0 | 11.4 ms |
| 100 000 | 0 | 29.3 ms |
| 1 000 000 | 0 | 941.7 ms |
| **100** | **1 000 000** | **8.0 ms** |

结论：枚举成本是**两项之和**——

- **全堆遍历的固定项**：无关对象约 **8 ns/个**（100 万个 byte[] 只花 8 ms）；
- **每个匹配实例的处理项**：约 **0.3~1 µs/个**（打 tag + 物化进返回数组），1M 匹配实例时的数字还被大数组分配与 GC 抬高。

所以现实场景（堆里几百万对象、目标类只有几百个实例）的窗口在**几十毫秒**量级，而不是秒级。秒级只出现在"目标类自身有上百万实例"的极端情况。

**但有一个随批次线性放大的项**：`getInstances` 是**按类**调用的，批次里 N 个待补类就是 N 次全堆遍历。5M 对象的堆上，10 个类 ≈ 10 × 40 ms。要消掉它得用 `IterateThroughHeap` 一次走完、按类分发。

**超线性确实存在，但成因未定**：每档重复 5 次（M3）：

| 实例数 | min | median | max | 单实例（median） |
|--:|--:|--:|--:|--:|
| 100 000 | 28.2 ms | 47.5 ms | 49.6 ms | 0.48 µs |
| 500 000 | 363 ms | 373 ms | 380 ms | 0.75 µs |
| 1 000 000 | 866 ms | 884 ms | 936 ms | 0.88 µs |

按 **min** 口径，100k→1M 是 **10 倍实例、30.7 倍耗时**（0.28 → 0.88 µs/实例）；按 **median** 口径是
**18.6 倍**（47.5 → 884 ms）。两种口径都落在超线性区间，定性结论成立，但**倍数对口径敏感**，不应只报其中一个。

100k 档的分布（min 28.2 / median 47.5 / max 49.6）不是"噪声叠在真值上"，更像**五次里一次快、四次慢**
的双峰——此时 min 可能是离群那一次，拿它做分母会把倍数放大。双峰本身尚未解释（候选：堆自适应调整、
上一轮残留状态、首轮 GC 状态不同）。

此前写的「温和增长、不是超线性」基于中位数，而 100k 档中位数被预热抬高，**已撤回**。

**成因未定，定因方法已明确**（尚未执行）：用已有的 safepoint 日志把每次调用的
`HeapIterateOperation` 耗时与总墙钟相减——若超线性出现在 **STW 段内**，说明是 tag map 扩容或遍历本身；
若出现在 **STW 段外**，说明是结果数组分配与 GC（1M 引用的数组在 G1 下是 humongous 分配，可顺手核对
GC 日志里的 humongous 记录）。

同轮运行开 `-Xlog:gc*` 记录到 GC 共 50 次停顿、合计 392 ms、最长 86 ms（覆盖整个探针，无法与单次枚举精确对齐）。

**STW 内外拆分**（M3 的 5 个样本，运行结束后用 safepoint 日志对齐）：

| 实例数 | 墙钟中位数 | 其中 STW | 墙钟外 | STW 占比 |
|--:|--:|--:|--:|--:|
| 0 | 598 µs | 503 µs | 95 µs | 84% |
| 100 000 | 94.4 ms | 30.4 ms | 64.0 ms | 32% |
| 500 000 | 619 ms | 178 ms | 441 ms | 29% |
| 1 000 000 | 1393 ms | 373 ms | **1020 ms** | **27%** |

结论：**超线性主要发生在 STW 段外**（1M 档 73% 的墙钟不在停世界里）。GC 日志里有 humongous 记录但
区域数很少（`Humongous regions: 1->1`），整轮 GC 停顿 568 ms / 64 次——不足以解释 1M 档那 1.0 s。
所以候选原因不是 GC 停顿，更可能是**结果数组物化与 JVMTI 局部引用处理本身**；这是推断，未验证。

**注意**：这一轮的墙钟（1M 中位数 1.39 s）明显高于早先那轮（884 ms），因为本轮的 `-Xlog:jit+compilation=debug`
非常啰嗦、拖慢了执行。**跨轮次数字不可比**，只有同一轮内的 STW 占比可用。

**单遍历要用两个指标衡量**：现有方案是 N 次短停顿（每类一次，约 8 ns/无关对象 + 匹配项成本）；单遍历把它换成**一次长停顿**（每个对象都要进 native 回调，没有 VM 内的 klass 过滤）。两者的取舍不是单一维度：

- **总停顿 / 总暴露时间**：单遍历可能更好；
- **最大单次停顿**：单遍历可能明显更差——对延迟敏感的场景，一次几百毫秒的停顿可能比 N 次几十毫秒更难接受。

因此 spike 必须同时记录总耗时与 safepoint 日志里的**最大单次停顿**，只看总耗时会得出相反结论。

### 10.2 枚举期间有一段 STW（M6）

一个自然的假设是：JVMTI 堆遍历在 safepoint 里执行、业务线程全停。要验证它，**只看总吞吐是不够的**——67% 的心跳速率既可能是「全程并发 + 争抢」，也可能是「一段 STW 把平均拉低」。所以同时测**相邻两次自增的最大间隔**，并用 `-Xlog:safepoint` 交叉验证。

1M 匹配实例、枚举耗时 857 ms：

| 指标 | 值 |
|:--|:--|
| 心跳吞吐 | 2332 万次（自由速率 3457 万，约 **67%**） |
| 相邻自增最大间隔 | **202 ms** |
| safepoint 日志 | `HeapIterateOperation` 单次 **216~242 ms**，全程序 25 次共 2162 ms |

注意 25 次是**整个探针进程**的汇总（M3/M4/M5 都在枚举），不是那一次 1M 枚举的独占数据；单次最大值 241.7 ms 与心跳最大间隔 202 ms 来自不同次调用，量级一致。

结论：**遍历段确实是 STW**。吞吐只有 67% 是因为「一段约 240 ms 的停世界 + 其余阶段并发」，而不是全程争抢。因此：

- 每次 `getInstances` 调用 = **一次 safepoint**，1M 匹配实例时约 240 ms 的业务停顿；
- 批次里 N 个待补类 = **N 次 STW**——这才是「单次遍历按类分发」的真正收益（省停顿次数与总停顿），不是省带宽；
- 风险 9 的暴露时间应当**扣掉 STW 段**：停世界期间业务线程读不到未补字段。

顺带记录：同一次运行的 safepoint 日志里 `RedefineClasses` 共 29 次、总计 374 ms（均值约 13 ms，最大 22.4 ms）。比 §4 用墙钟测的 25~35 ms 低——墙钟里含了**等待 safepoint** 的时间，VM 操作本身更快。

**边界**：仅 JBR 21 / Windows 实测；标准 HotSpot 未测。

## 11. 附：JIT 预热的分配点（`TwoPhaseProbe` JIT 场景）

比「构造中途被 redefine」更接近真实风险的，是**已编译的分配点**：`B.make()` 预热到 JIT 内联了
`new A()`，而那时 A 还是旧大小。若重定义的依赖失效处理有缺口，这条编译后的路径会继续分配旧尺寸对象，
新增字段就会落到对象之外——是内存损坏，而不是读到一个错值。

场景：预热 60 万次短调用 `C.loop(2)`（循环体内 `sink[i & 15] = B.make()`，对象写进静态数组保证逃逸，
分配点不会被标量替换掉）→ redefine A（新增 `int b = 42`）→ 再调用 20 万次。

为排除空转，用例读 `-Xlog:jit+compilation=debug` 与 `-Xlog:safepoint`，在**运行结束后**做后置分析，
并且按 **cid** 对齐而不是只数行数：

- 重定义前存在 level-4 编译（取它最后一次的 cid）；
- **同一个 cid** 在 `RedefineClasses` 窗口内被标记 `made not entrant`（实测 1 次，窗口
  `[13971090900, 13993153300] ns`）。

**对照组做了两种变体**（`D` 分别只改方法体、以及新增字段）：两种情况下 `C.loop` 的 cid **都在窗口内被失效**
（各 1 次）。所以信号与"是否结构性变化"无关——任何 `redefineClasses` 调用都会让无关类的已编译代码失效。

这本身是一项成本：**每次热更后，进程里已编译的代码可能被大范围回退**，出现预热回落。实测单次 redefine
墙钟 body-only 16.4 ms / structural 75.5 ms（各一次采样，不同类，不可直接比较，仅记录量级）。

**结论：行为正确，但因果未能证实。** 所有对象仍是 A 的实例、`hi()` 返回 `v2:1,42`、新字段可读；
然而**对照组证伪了这条证据链**——redefine 一个无关类 `D`，同一个 `C.loop` 的 cid 同样在窗口内被失效
（实测 1 次）。也就是说「窗口内出现 made not entrant」这个信号**不特异**，无法归因到 A 的重定义；
它可能来自重定义触发的全局/后台失效，也可能来自编译器线程。

进一步说：即使把 `redefineClasses` 整个注释掉，这个用例的可观测断言（对象正确、字段可读）**依然会通过**
——因为逃逸分析、正确失效、以及"本来就没问题"三种情况给出同一个观测结果。按「防空转铁律」的自问，
这条用例当前**不具备判定力**，只能作为"没有观测到损坏"的记录，不能作为"机制已验证"的证据。

**构造这个场景踩了三个坑**（都会导致用例空转通过）：

1. **不能经 MethodHandle 调 `make()`**：它被内联进 MH 适配器，自己永远停在 C1(level 2)，C2 内联分配点
   的场景根本没建立。
2. **不能用一次长循环**：只有方法级调用计数器才驱动整方法 C2 编译；一次长循环只产生 **OSR 版本**，
   而 OSR 版本会被 `unstable_if` 反复淘汰（deopt 日志实测 `cid=1770 osr level=4 jit.C.loop ... unstable_if`），
   等到重定义时又没有活版本可失效。改成大量短调用。
3. **不能拿 safepoint 日志行的时间戳当基准**：日志行在 safepoint **结束时**打印，而失效事件发生在窗口内
   （`Total` 那一段）。基准必须是 `时间戳 − Total`。另外 unified logging 有缓冲，运行中读文件会读到
   滞后内容，所以断言放在**最后**统一做。

**未覆盖**：latch 变体见 §12。

## 12. 在途旧构造器帧（`TwoPhaseProbe` latch 场景）

一个线程停在**旧版** `<init>` 中途时类被重定义。真正危险的不是「新字段被覆盖」——旧字节码根本叫不出
这个名字——而是**旧方法体继续写已有字段时用的偏移**：若 DCEVM 不修正在途帧，这些写会落到错误槽位，
静默破坏相邻字段。

夹具：`A.<init>` 先写 `a = 1`，然后 `ENTERED.countDown()` 并阻塞在 `RELEASE.await()`，放行后写 `b`、`c`。
v2 新增 `int d = 42`，并把 `b` 的写入值从 **1 改成 2**——「到底跑的是哪版字节码」因此可观测。

**两条防空转断言先立住**（否则「没损坏」在没触发场景时同样成立）：

- redefine 前：线程栈里确实有 `latch.A.<init>` 帧；
- redefine 返回后：同一帧仍在 `<init>` 里（实测重定义耗时 51 ms，全程在途）。

**结果（11/11 通过）**：

| 断言 | 实测 | 说明 |
|:--|:--|:--|
| 在途对象的 `b` | **1** | 旧字节码写的是 1（新版写 2）——在途帧确实继续执行 v1 |
| 在途对象的 `c` | 3 | 阻塞后写入的字段完好，**没有偏移错乱** |
| 在途对象的 `d` | 0 | 旧构造器不碰新字段；本探针不装补丁 |
| 新对象的 `b` / `d` | 2 / 42 | 重定义后新建的对象走新字节码 |

结论：**DCEVM 在重定义内部正确处理了在途帧的字段偏移**。此前「分析认为安全」的推理现在有实测支撑。

**已知边界（未覆盖，附触发条件）**：① **父类新增字段使子类字段整体后移**——本用例只在同一个类里新增字段；若新增字段声明在父类、而正在构造的是子类，在途旧帧写子类字段的偏移会整体后移，需要单独验证。② **引用类型与 `long`**——本次只测了 `int`；引用字段写错槽位会破坏堆引用（比 `int` 错位严重得多），`long` 涉及两槽对齐。遇到"子类构造中途热更父类"的场景时，先按本条边界判断。

## 13. 具名类删除字段的真实后果（`P05Probe` M7）

`08-decisions.md` 附录 A 只测了"删字段被接受、其它字段值保留"，没有分离"批次外的读者会怎样"。
M7 就是那个分离实验：`A` 有 `x,y,z` 三个字段，`B`（**不在批次内**）编译期直接读 `A.x`，
`C extends A` 也有一个字段 `w`（同样不在批次内）。把 `A` 重定义为删掉 `x`。

### 13.1 实测结果

| 观察点 | 实测 |
|:--|:--|
| `redefineClasses` 删除一个有存活实例的具名字段 | **接受**（约 30 ms） |
| 批次外读者，常量池**已解析**（重定义前执行过 `B.readX`） | `NoSuchFieldError: Class rem.A does not have member field 'int x'` |
| 批次外读者，常量池**未解析**（重定义前从未执行） | 同上，`NoSuchFieldError` |
| 存活实例的其它字段 `y` / `z` | `22` / `33`，**未受影响** |
| 重定义后**新建**实例的 `y` / `z` | `22` / `33`，**与旧实例一致** |
| 子类 `C`（未在批次内）的 `w` | `44` 保持；`writeW(99)` 正常；父类 `y`/`z` 未被破坏 |
| 删 `x` 后再新增字段 `q`，存活实例上的 `q` | **`0`**（槽位追加，**不复用**废弃槽） |
| 同上，新建实例上的 `q` | `77`（走新构造器） |

### 13.2 结论：失败是确定性的，不是"不可预期"

- **布局不压缩**：删掉字段后，其余字段偏移不变（新老实例的 `y`/`z` 都是 22/33）。所以既没有"读到相邻字段"，也没有"子类偏移整体前移"。
- **废弃槽位不复用**：删 `x` 再加 `q`，`q` 落在**追加**的位置，存活实例上读到 `0`。
  这意味着 `InitFix` 的条件 CAS 前提（新增字段在存活实例上是类型默认值）在这里**仍然成立**。
- **错误是响亮的**：任何批次外读者都会抛 `NoSuchFieldError`，不会静默返回旧值。这与"静默读到零值"的直觉相反。

### 13.3 那为什么仍然拒绝：门拦的是 `A` 自己

这里必须纠正一个容易想歪的方向：**被拒的不是"某个读 `A.x` 的类"，而是 `A` 本身。**

`A` 的 diff 里就有 `- x`，`checkChangedFields` 判 `REMOVED_FIELD`。接下来取决于 `A` 有没有存活实例
（`RemoldAgent.decideLayout` 的三态出口）：

| 情形 | 动作 | 后果 |
|:--|:--|:--|
| `A` **有**存活实例（或存活状态 UNKNOWN） | `REJECT` + `dropFromBatch(A 及匿名类)` | `A` 保持旧字节码，字段还在、读者还在，`NoSuchFieldError` 无从发生 |
| `A` **无**存活实例且不涉及静态字段 | **`WAIVE`，放行** | `A` 真的被重定义、字段真的消失 |

所以"删字段行不行"的答案不是"删了会出错"，而是：**有实例时这条编辑被拒绝、不会生效；没实例时它会生效。**

无实例时放行的理由写在 `decideLayout` 里——"没有状态会被错读"。这条理由**只对新增字段成立**：
新增字段在无实例时确实无害（新实例走新构造器初始化正确）。但**删除字段的风险不在"已有实例被错读"，
而在"未来的实例被陈旧代码读"**，与当前有无实例无关。这一条已登记为风险 12（`docs/status.md`）。

拒绝（有实例时）的理由不是"会读到脏数据"，而是**一旦放行，错误会被推迟到不可控的时刻**：

1. 热更本身会**成功**，`redefineClasses` 返回正常；随后任何仍持有 `GETFIELD A.x` 的已加载类在**未来任意一次执行**到该读点时抛 `NoSuchFieldError`。抛点在业务线程、在热更事务之外——无法回滚，也无法归因到"刚才那次热更"。
2. `NoSuchFieldError` 是 `LinkageError`，正好落在不变量 4 的语义里：项目把 `LinkageError` 视为必须中止的信号，而不是可恢复的运行时异常。
3. **放行前需要证明"读者为空"，而这个证明现在没有做**。`LayoutGate` 只拿到字节码 diff，看不到"有多少已加载类引用了这个字段"。
   注意这里此前写成"拿不到"，**是错的**：`Instrumentation.getAllLoadedClasses()` 已在 `RemoldAgent` 里用于多处，
   `HierarchyTreeOracle.readClassBytes` 也已经有"按类名取字节码"的能力，所以"枚举已加载类 + 扫常量池里的
   `Fieldref A/x`"是**可做**的，只是尚未实现。若要做"安全删除"，门槛就是这一步。

**反向印证**：匿名类的合成捕获字段删除可以放行（`LayoutGate.check` 判 `COMPATIBLE`），正是因为它的读者集合被编译器约束在类自身内部，而类本身在批次内被重编——读者必为空的证明是**拿得到**的。

### 13.4 对既有文档的更正

`08-decisions.md` 附录 A 结论 3 原文"实测删字段虽保留其他字段，但旧代码调用或重定义后实例读取均会导致不可预期行为"——**"不可预期"不准确**。实测是确定性的 `NoSuchFieldError`，且布局完全保留。该处已按本节更正。

### 13.5 这个场景要成立，需要先绕过门、再叠加一个巧合

M7 的夹具（`B` 不改、继续读 `A.x`）**不是**正常的编辑—编译—热更流程能产生的状态，有两个独立原因：

1. **编译期就断了**：删掉 `A.x` 之后 `B.java` 里那句 `a.x` 编译不过，用户必然要一起改掉它；而改了 `B` 的字节码，`B` 就进入本轮批次，新字节码里没有 `GETFIELD A.x`。
2. **运行期的门大多也断了**（见 §13.3）：`A` 有存活实例时会被 `REMOVED_FIELD` 拒绝并移出本批。

所以要让删除**真的落地**，只有四种情形：

| 落地方式 | 说明 |
|:--|:--|
| `A` 无存活实例 → `WAIVE` | **默认模式下的合法路径**，不是绕过。理由见风险 12：这条理由只对新增字段成立 |
| `-Dremold.agent.layout_gate=warn` / `off` | 用户显式接受"存活实例可能读到零值/错值" |
| `HOTSWAP_BLACKLIST` 前缀命中 | 黑名单类在门检查前就 `continue`，完全不过门 |
| `LayoutAction.SKIP` | 取不到基线字节码或 diff 失败时，**仍然 redefine**（只 warn 不拦） |

只有在上述三种情况下，"读者在批次外"才有意义。而在那之上还需要一个成因：

| # | 成因 | 现实性 |
|:--|:--|:--|
| 1 | **预编译依赖**：某个 jar 在编译期引用了 `A.x`，无法重编 | 真实，取决于部署形态（如被别的模组/插件依赖的公开 API 类） |
| 2 | **批次撕裂**：bulk `redefineClasses` 失败后退回逐类模式，逐类循环里单类失败只打 `error("[FAIL] ...")` 然后继续；或读者类自己被 `REJECT` 从批次里丢掉（`dropped()` 只含宿主 + 匿名类，**不含依赖它的具名类**） | 代码里确实有这条路径，但需要叠加"`A` 被 warn/off/SKIP 放行、`B` 被拒/失败" |
| 3 | **增量编译未传播**：IDE「只编译当前文件」不重编 `B` | **弱**。任何会编译 `B` 的构建（含 Gradle 增量编译在 ABI 变化时重编依赖方）都会在 javac 阶段失败，根本到不了热更；只有"逐文件编译"这种绕开依赖分析的操作才会留下陈旧 `B.class` |
| 4 | **生成/注入的字节码**：ASM 直接拼出来的读点不经 javac 检查 | 弱。`plugin-mixin` 的注入代码本身是 Java 源码，通常仍被 javac 检查 |

结论：这个场景需要**两个独立条件**——删除真的落地（§13.5 表的四行之一），**并且**读者留在批次外。它不是"删字段就会踩到"。此前写成"批次撕裂在默认模式下可达"是不准确的：默认模式下有存活实例时 `A` 先被拦下，撕裂无从谈起；只有 `WAIVE` 那一行不需要用户改配置。

### 13.6 若要支持安全删除，门槛是什么

结论不是"永远不能删"，也不是"删了就拒"，而是"**批次外读者为空**"。这一条同时纠正了两种过粗的判据：

- **过严 ①**："有删除就拒"——会误杀两类安全删除（见 §13.6.1）；
- **过严 ②**："全局没有读者才放行"——本节的初版就是这么写的，它比正确答案更严，把"读者都在批次内"这种整批一致的重构也挡掉了。

正确判据（相对**批次**，而不是相对全局）：

对每个已加载类 `C`：

| `C` 的位置 | 扫哪份字节码 | 理由 |
|:--|:--|:--|
| `C ∈ 本批` | **新**字节码（`newBatchBytes`） | 批次落地后跑的是新字节码；若它仍引用该字段，说明改动本身不自洽 |
| `C ∉ 本批` | **当前**字节码（`bytecodeCache` / `fetchOriginalBytecode`） | 它不会被重定义，现在读什么，批次落地后还读什么 |

命中 `Fieldref O/x`（**`O ∈ {A} ∪ descendants(A)`**，不能只匹配 `A`；且必须按 JVMS 5.4.3.2 判"解析是否真的落在 `A` 上"——父类遮蔽与**接口字段截胡**都要处理，见 §13.6.2）→ 拒绝，并把**具体类名**写进报告；全无命中 → 放行。

#### 13.6.1 两条被"一刀切"误杀的安全删除

| 场景 | 为什么安全 | 一刀切的后果 |
|:--|:--|:--|
| **删 `private` 字段** | 读者被语言约束在 **nest** 内（Java 11+ 直接 `GETFIELD`，pre-11 经 `access$` 桥）。删字段时 nest 内任何读它的类都必须重编，否则 javac 报错 → 它们在批次内 | 把全部 private 字段删除误判为危险 |
| **正常连带重编的批次** | 读者被重编后**新字节码不再读**该字段。批次落地后不存在读者，与有无存活实例无关 | 把"删字段 + 改读者"这种最正常的重构整批拒掉 |

这也说明 `WAIVE` 的 liveness 代理对删除为什么错：它回答"**现在**有没有实例会被错读"，而删除要问的是"**批次落地后**还有没有代码会读"。两个问题正交——所以修法不是"删除不适用 `WAIVE`"，而是"删除改问另一个问题"（`docs/status.md` 风险 12）。

#### 13.6.2 `Fieldref` 的 owner 不是声明类，必须沿继承树展开

这是判据里最容易写漏的一处，且会直接导致**漏报**。

`CONSTANT_Fieldref_info` 的 `class_index` 记的是**编译期静态类型**（qualifying type），不是字段实际声明所在的类。实测（javac 21，`A { public int x; protected int p; }`，`Sub extends A`，`Reader` 两种静态类型各读一遍）：

```
static int viaSub(Sub s) { return s.x + s.p; }
    getfield  Field Sub.x:I
    getfield  Field Sub.p:I
static int viaA(A a)   { return a.x + a.p; }
    getfield  Field A.x:I
    getfield  Field A.p:I
static void writeViaSub(Sub s, int v) { s.x = v; }
    putfield  Field Sub.x:I
```

**同一个字段 `A.x`，owner 可以是 `A`，也可以是任意子类。** 所以删 `A.x` 时，匹配规则必须是：

```
owner ∈ {A} ∪ descendants(A)      ← 但要按下面的"遮蔽截断"裁剪
```

`descendants` 取**已加载**的子类集合（未加载子类的读者属于 §13.6.4 的覆盖缺口）。索引查询因此不是一个 key，而是这组 key 的并集。

##### 展开不能只看"父类链"：接口字段会截胡

字段可以被**遮蔽**：子类自己声明同名字段后，`Sub/x` 解析到的是 `Sub.x`，不是 `A.x`。实测（`A{public int x}`、`Sub extends A{public int x}`、`SubSub extends Sub`）：

```
viaA(A a)        -> Field Hide$A.x:I
viaSub(Sub s)    -> Field Hide$Sub.x:I        ← 指向 Sub.x，不是 A.x
viaSubSub(SubSub s) -> Field Hide$SubSub.x:I  ← 同样解析到 Sub.x
viaSubCastA(Sub s)  -> Field Hide$A.x:I       ← 显式转成 A 才回到 A.x
```

但这还只是"父类链"的一半。**JVMS 5.4.3.2 的字段解析在父类之前先搜父接口**，所以接口里同名的字段会截胡。实测（`interface I { int x = 1; }`、`class A { public int x = 2; }`、`class Sub extends A implements I`，用 `MethodHandles.lookup().findStaticGetter(Sub.class, "x", int.class)` 走 JVM 解析）：

```
resolve (Sub, x, int) -> 命中 STATIC 字段，值 = 1        ← 接口 I.x 抢先，不是父类 A.x
resolve (A,   x, int) -> IllegalAccessException:
                         expected a static field: JvmsProbe$A.x/int/getField
```

对照那一行证明 `A.x` 是实例字段、解析确实是先落到 `I.x` 上（静态），而不是先落 `A.x` 再报静态性错误。

所以精确判据必须**照抄 JVMS 5.4.3.2，不能只写"沿父类链"**：

> `O` 计入，当且仅当对 `(O, name, desc)` 做字段解析，**结果落在 `A` 声明的那个字段上**。
> 解析顺序是：`O` 自身声明 → **`O` 的父接口（递归）** → `O` 的父类（递归）。

被接口截胡的 `Fieldref` **不算读者**（它不指向 `A.x`）；顺带它本身是个隐患——`getfield` 访问一个解析到静态字段的引用会抛 `IncompatibleClassChangeError`，属另一类问题。

**可达性**：javac 在"父类与接口同时有同名同描述字段"时会直接报"对 `x` 的引用不明确"（实测），所以这个形态通常**不由 javac 直接产出**。但它仍可能来自：其它编译器（ecj / kotlinc）、ASM / Mixin 生成的字节码，或**本轮热更恰好给接口加了同名字段**（读者是上一版编的，当时不歧义，删除后才被截胡）。因此判据按 JVMS 走，不按 javac 的行为简化。

截断还要按 **name + desc** 一起判——字段解析同时匹配名字和描述符；子类声明了同名但不同类型的 `x` 时，`Fieldref O/x:I` 仍会向上解析到 `A.x:I`，此时 `O` 必须计入。

##### 两个附带结论

- **扫常量池而不是扫指令**天然覆盖全部四种访问：`GETFIELD` / `PUTFIELD` / `GETSTATIC` / `PUTSTATIC` 引用的是同一个 `Fieldref` 常量，不必按 opcode 分叉。上面 `writeViaSub` 的 `putfield` 就是同一个 `#7` 常量。
- **private 字段不需要展开**。证据要分两段，缺一段就是循环论证：
  1. **不加强转根本编译不过**——JLS §6.6 / §8.3 规定 private 成员不被继承，`s.x`（`s` 是子类类型）在 nest 内也是错。实测 javac 报"x 在 A 中是 private 访问控制"。
  2. **一旦强转成 `A`，owner 必然是 `A`**——这一点与 private 无关，是强转本身把静态类型变成了 `A`：上面 `viaSubCastA` 用的是 **public** 字段，同样产出 `Field Hide$A.x:I`。
  两段合起来才是结论：private 字段的 owner **恒为 `A`**，展开与截断都可省掉。
  （此前这里写"实测 `((A) s).x` 产出 owner A，所以 private 恒为 A"——那是**循环论证**：强转已经把静态类型变成 `A` 了，换成 public 字段结果一模一样，证明不了 private 的任何特殊性。)

#### 13.6.3 实现分层与成本

1. **一次热更建一次索引**：遍历已加载类，常量池扫 `Fieldref`（pre-11 还要看 `Methodref` 的 `access$*`），得到 `(owner.name) → 读者类名集合`。之后每个被删成员查一次表（按 §13.6.2 展开 owner）。
2. **只在"本轮有成员被删"时才建索引**——删除是罕见事件，不拖慢常规热更。
3. **private 字段可以先走便宜路径**：`ClassHierarchyOracle.getNestMembers` 已经现成，取 `nest ∩ 批次外` 逐个扫；nest 通常只有几个类，比全量扫描便宜得多。且 private 的 owner **恒为 `A`**（§13.6.2），连继承树展开与遮蔽截断都省掉。非 private 字段再走全量索引。

#### 13.6.4 扫描范围：判据的完备性完全取决于它

这是本判据最容易含糊过去的地方，必须写死。`bytecodeCache` 的实际覆盖范围是**已加载且被 agent 处理过的类**
（`applyRedefineLayoutGate` 用 `fetchOriginalBytecode` 补已加载类、`applyRedefinitions` 成功后写入、
`AlignmentTransaction.commit()` 批量写入），它**不覆盖**未加载的工程类和 classpath 上的 jar。

| 扫描范围 | 覆盖 | 代价（实测，见 §14） |
|:--|:--|:--|
| 仅已加载类（`getAllLoadedClasses`） | **不完备**：未加载的陈旧读者会漏，首次加载执行时才炸 | 1165 类 / 6.5 MiB → **149 ms**（读 117 + 扫 31） |
| 已加载类 + **工程构建输出目录** | 覆盖工程内未加载类 | 另加 449 类 / 2.5 MiB → **9 ms**（字节在内存） |
| 已加载类 + 整个 classpath（含 jar） | 完备 | 10 567 + 27 262 类 / 165 MiB → **约 3.1 s**（读占 70~79%） |

**结论：取"仅已加载类"时，这个判据不完备，不完备就不该拿来给"放行"背书。**
它给出的不是安全，而是**虚假的安全感**——比现在一律拒绝更糟。所以实现时的最低档位应是
"已加载类 + 工程构建输出"（合计约 160 ms 量级），并在报告里明确"第三方 jar 中的陈旧读者仍可能漏"。
全量 classpath 虽然完备，但 3 秒的量级放在一次热更里不可接受，除非改成异步预热索引。

#### 13.6.5 pre-11 的合成访问器：实测结果比"归到风险 11"复杂

javac 8 给 nest 访问生成 `access$NNN` 桥。实测（javac 21 `--release 8`，两个 private 字段 `x`、`y`）：

```
v1:  private int x; private int y;
     static int access$000(A);   // getfield x
     static int access$100(A);   // getfield y
     Inner.readX -> A.access$000
     Inner.readY -> A.access$100

v2（删掉 x）:  private int y;
     static int access$000(A);   // getfield y   ← 名字与描述符都没变，只有方法体变了
     Inner.readY -> A.access$000
```

陈旧 v1 `Inner` 的两条调用，失败形态**不一样**：

| 调用 | 目标 | 结果 |
|:--|:--|:--|
| `readY` | `access$100` | `NoSuchMethodError`（桥被删 → 方法删除，归风险 11 足够） |
| `readX` | `access$000` | **静默返回 `y` 的值**——编号被 `y` 顶上，名字与描述符都不变，diff 里看不到"删除"，只看得到"方法体改了" |

所以"归到风险 11 就迎刃而解"只解决了一半，**漏掉的那一半恰恰是静默错读**——正是本项目最想防的那类。

要覆盖它，判据不能落在"成员被删"上，而要落在"**合成访问器的旧目标**"上：
`access$*` 的方法体里 `getfield`/`putfield`/`invoke*` 的目标变了，就等价于"旧目标被删"，
要对**旧目标**做一次批次外读者扫描。`ClassDiffUtil.modifiedBodyMethods` 能看出方法体变了，
但"方法体变了"本身是常态，不能一律拒绝。

由此还有一个版本分叉：major ≥ 55（Java 11+，nestmate）没有桥，直接 `GETFIELD`，扫 `Fieldref` 就够；
major < 55 才需要上面这套折算。

#### 13.6.6 其他边界

- 索引只覆盖**已加载**的类；之后从过期 jar 新加载的类仍可能引用它（见 §13.6.4 的档位取舍）。
- 成本未实测：一次热更做一遍"全部已加载类 + 常量池扫描"，量级与 `getAllLoadedClasses()` 的规模成正比。

### 13.7 未覆盖

- **静态字段删除**：机制上同样是 `GETSTATIC` 解析失败，但未实测；`LayoutGate` 对静态字段是强拒绝（不可 waive）。
- **引用类型字段删除**：本用例只测 `int`。
- **JBR 17 / 标准 HotSpot**：只在 JBR 21.0.9 上测过；标准 HotSpot 直接拒绝 schema 变更，无从对比。
- **读者扫描的成本**：§13.6 的判据未实现，也未测过"枚举已加载类 + 扫常量池"的耗时；扫描范围取哪一档（§13.6.4）尚未决策。
- **合成访问器的折算未实现**：§13.6.5 只给出判据（`access$*` 的目标变了就等价于旧目标被删），没有代码。
- **`access$` 编号规则的完整形态**：只测了"两个 int 字段、删前一个"这一种组合。javac 的编号里还编码了访问种类（读/写/调用），多字段、混合读写、字段与方法混合时编号如何分配**未逐一验证**。
- **接口方法删除的失败形态**：只观察到 `InterfaceMethodref`(11) 这个 tag 存在（实测 `#7 = InterfaceMethodref I.d:()I`），没有实测"删 default 方法后陈旧调用者抛什么"。机制上应是 `NoSuchMethodError`，未验证。
- **字段遮蔽的跨层形态**：只测了"子类遮蔽、孙类继承子类的 `x`"这一种。多级遮蔽、同名但不同类型（描述符不同）时 `Fieldref` 的解析结果**未逐一验证**。
- **接口字段截胡的运行期表现**：已由 M9f 覆盖（`Expected non-static field`），不再是缺口。

## 14. 读者扫描的成本（`ReaderScanProbe`）

§13.6.4 把"扫描范围"列为未决策项，理由是成本未测。本节补上实测，并把"纯常量池扫描 vs 方法体精扫"
的差距量化。

环境：JBR 25（`jbrsdk_jcef-25.0.2`）、Windows、`./gradlew :tests:readerScanProbe`、单机、文件缓存已热。
每档重复采样，报 min / median / max。

### 14.1 单类成本：常量池 vs 方法体

样本 400 个工程类，平均 6.0 KiB：

| 实现 | min | median | max |
|:--|--:|--:|--:|
| 纯常量池扫描 | 1.8 µs | **35.6 µs** | 16.2 ms |
| 方法体精扫（ASM tree API） | 2.8 µs | **119.8 µs** | 41.8 ms |

**中位加速 3.4×**；在 2000 个 JDK 类上的同口径对照是 **4.0×**（6.9 µs vs 27.9 µs）。

**这与"快 10~50 倍"的预期有差距，原因是对照基线**：这里的"方法体精扫"用 ASM tree API
（`ClassNode` + 指令链遍历），它本身已经不算慢；换成 `MethodVisitor` 流式访问差距只会更小。
max 列的毫秒级离群值是 JIT 预热与 GC，不影响中位。

**结论要按 median 读**：常量池扫描大约快 3~4 倍，不是数量级。真正的成本大头不在解析，而在**读字节**（见 14.2）。

### 14.2 三档扫描范围的真实成本

| 档位 | 类数 | 字节 | 总耗时（中位） | 其中读 | 其中扫描 |
|:--|--:|--:|--:|--:|--:|
| ① 仅已加载类 | 1165（可读） | 6.5 MiB | **149 ms** | 117 ms | 31 ms |
| ② + 工程构建输出（字节已在内存） | 449 | 2.5 MiB | — | 0 | **9 ms** |
| ③a + classpath 全部条目 | 10 567 | 38.8 MiB | **1074 ms** | 765 ms | 191 ms |
| ③b + JDK 模块 | 27 262 | 125.9 MiB | **2036 ms** | 1355 ms | 545 ms |

读占 70~79%——**扫描本身很快，慢的是把字节读进来**。

### 14.3 对 §13.6.4 档位决策的影响

- **③（全量 classpath）≈ 3.1 秒**（③a + ③b 中位之和），而且这还是在文件缓存已热、单次的情况下。
  这个量级放在一次热更里不可接受，除非改成异步预热索引。
- **①+② ≈ 160 ms**（已加载 149 ms + 工程输出 9 ms），量级可接受；其中 117 ms 是读已加载类的字节，
  而 `bytecodeCache` 里已有的类可以直接复用、不必再读，实际还会更低。
- 所以**推荐档位是 ①+②**：已加载类 + 工程构建输出。它覆盖了工程内全部类（含尚未加载的），
  漏的只有"第三方 jar 里编译期引用了被删成员"这一类——正是 §13.5 里那条"预编译依赖"成因。

**这条取舍要写进接线时的决策记录**：档位 ①+② 是"不完备但成本可接受"，③ 是"完备但约 3 秒"。
在拿到真实工程的规模数据之前，不应假设 ③ 可行。

### 14.4 一条可执行的正确定性断言

探针同时验证"常量池扫描是方法体精扫的**超集**"（即 tag 15 必然回指 9/10/11 那条推论）：
400 个工程类上 `missing=0`。

这条断言曾经抓到过一个真问题——**但问题在探针自己身上**：第一版的 `addHandle` 把
`H_INVOKESTATIC` 一律记成 `method`，而接口里的 static / private 方法的常量池条目是
`InterfaceMethodref`(11)，于是对不上。修法是按 `Handle.isInterface()` 区分。
记在这里是因为它说明**这条断言值得留着**：它能抓出"两种扫描口径不一致"。

### 14.5 未覆盖

- 只在 Windows / JBR 25 上测；Linux 与其它 JDK 未测。
- 文件缓存已热；冷启动读 jar 会明显更慢。
- 没有测"复用 `bytecodeCache`、跳过已缓存类"的实际收益（推断会显著降低读占比，未实测）。
- 没有测多线程并行扫描。

### 14.6 接线后的实际管线成本

接线（`applyRedefineLayoutGate` → `scanRemovedFieldReaders` → `RemovedMemberReaders.find`）的成本
与 14.2 不同：读者字节码来自 `bytecodeCache`（已在内存），**没有 IO**；真正花时间的是
`universe()`（ASM 结构解析）与 `find()`（常量池扫描 + 解析）。

434 个工程类：

| 阶段 | min | median | max |
|:--|--:|--:|--:|
| `universe()`（ASM describe） | 4.5 ms | **5.1 ms** | 10.8 ms |
| `find()`（常量池扫描 + 解析） | 10.1 ms | **10.8 ms** | 13.1 ms |

合计约 **16 ms**，约 37 µs/类。加上 14.2 里"走一遍工程构建输出补未加载类"的 9 ms，
本项目规模下**整条管线约 25 ms**。

外推（未实测）：3000 个工程类约 110 ms。这个量级放在一次热更里可以接受，
但要注意它只在**本轮有实例字段被删**时才触发（没有删除直接返回空表，零成本）。

**口径说明**：这里用磁盘字节代替 `bytecodeCache`，所以测到的是管线本身；真实接线里缓存命中时
连这部分读都没有，只会更低。

### 14.7 缓存覆盖：为什么需要一个"首次加载填充"的 transformer

接线时发现一个会让判据**永久失效**的缺口：`bytecodeCache` 原先只在热更/对齐过程中被动填充，
而读者扫描要求"已加载类读内存里当前生效的字节码"（§13.6.4 的字节源约束）。首轮热更时缓存几乎是空的
→ 扫描判不完整 → 退回存活实例判据。也就是说，**判据接上了线，但永远不会生效**。

修法是两条：

**① 首次加载时填缓存。** 注册一个独立的 `CacheWarmingTransformer`：

- 只在 `classBeingRedefined == null`（首次加载）时动作；
- 只认工程自身的位置（先按 agent 的 watch 目录/jar 判，监听配置为空时退回"`file:` 目录"判据——
  jar 里的类按依赖处理）；
- 跳过 hidden class（名字含 `/0x`）与 agent 自身（`remold/`）；
- **永远 `return null`**，不改字节、不参与任何决策。

**为什么不塞进 `AnnotationTransformer`**：后者对首次加载的类会做对齐替换
（`pendingAlignedClasses`），在它里面提前 `return null` 会跳过对齐。独立注册则不碰那条逻辑，
而且对加载流程零影响。

**注册标志必须是 `canRetransform = true`（这一条踩过坑）**：JVM 先跑完**所有**
`canRetransform=false` 的 transformer，再跑 `canRetransform=true` 的那一组，**注册顺序只在组内生效**。
所以若把本 transformer 注册成 `false`，首次加载时它会先于 `AnnotationTransformer` 执行，
拿到的正是对齐/织入**之前**的原始字节——缓存里存的就是错版本。改判据没用，得改注册标志。

**② 补漏网之鱼用一次批量 retransform，不逐类调。** Transformer 注册之前就已加载的类不会经过它；
`scanRemovedFieldReaders` 把这些类收成一个数组，**一次** `inst.retransformClasses(array)` 补齐
（一次 safepoint）。逐类调会触发 N 次 safepoint，这是不能接受的。

**③ 过滤数组类与 hidden class（否则 `uncached` 永远大于 0）。** JBR 25 下，工程类的数组
`[Lp.Foo;` 与 lambda 隐藏类 `p.Foo$$Lambda/0x…` 都会继承宿主的 CodeSource（工程构建目录），
但它们**永远不进 `ClassFileTransformer`**；不滤掉的话 `uncached` 恒为正，判据永久退回。
数组没有代码、hidden class 的引用由宿主类的常量池覆盖，跳过它们不损失覆盖。
另外跳过 `!inst.isModifiableClass(clazz)`——retransform 会直接抛 `UnmodifiableClassException`。

`CacheWarmingTransformerTest` 6 用例覆盖：首次加载入缓存、重定义通道不入、hidden class 跳过、
agent 自身跳过、非工程位置跳过、缓存的是原始引用。变异检查：去掉"区分首次加载"后
`doesNotCacheRedefinitionPath` 变红。

**集成探针 `CacheWarmingProbe`（`./gradlew :tests:cacheWarmingProbe`）** 把上面那条分组顺序规则
实测了一遍：注册一个 `canRetransform=false` 的前置观察者、一个 `true` 组的字节替换者（模拟
`pendingAlignedClasses`）、再注册被验证的 `CacheWarmingTransformer`（`true` 组，排在替换者之后），
然后加载一个 v1 类、由替换者换成 v2。结果：

- 前置观察者（`false` 组）看到的是 **v1** —— 证明"非可重转换组整组先跑"；
- `bytecodeCache` 里是 **v2** —— 证明被验证者拿到的是替换之后的最终字节。

**变异检查**：把探针里的注册改回 `false`，最后一条断言立刻变红（缓存里是 v1）——这正是修之前的
真实缺陷。7/7 通过。

## 15. 方法删除：向上解析与向下分派是两回事（`P05Probe` M8）

§13 的判据对字段是完备的，直接搬到方法上会**漏掉一大类读者**。原因不是实现细节，而是
JVMS 给两者定义了两套不同的查找规则。

### 15.1 引用长什么样

`javac` 把常量池里的 owner 写成**编译期静态类型**，不是声明类：

```
viaI(I i)        -> InterfaceMethodref mp/I.m:()V
viaSuper(Super s)-> Methodref          mp/Super.m:()V
viaA(A a)        -> Methodref          mp/A.m:()V
```

夹具是 `interface I { void m(); }`、`class Super { public void m() {} }`、
`class A extends Super implements I { public void m() {} }`。删掉 `A.m` 之后，前两条引用**从不出现**
`A.m`——只按 `declaring == A` 归属的判据对它们完全失明。

### 15.2 实测（JBR 21.0.9 + DCEVM）

| 场景 | 删除前 | 删除后 |
|:--|:--|:--|
| **M8a** `viaI(I)`（`A` 重写 `Super.m` 并实现 `I.m`） | `A.m` | **`Super.m`** |
| **M8a** `viaSuper(Super)` | `A.m` | **`Super.m`** |
| **M8a** `viaA(A)`（owner 就是 `A`） | `A.m` | **`Super.m`** |
| **M8b** `viaJ(J)`（`Only` 是接口方法的唯一实现） | `Only.n` | **`AbstractMethodError`** |
| **M8c** `call(Plain)`（无人重写） | `Plain.p` | **`NoSuchMethodError`** |

三条基线都先断言"删除前确实分派到被删方法"，否则后面的变化说明不了任何事。

### 15.3 关键发现：连 `owner == A` 的直接引用也不抛错

M8a 的第三行是最反直觉的一条。`Methodref A.m` 在 `A` 不再声明 `m` 之后，**解析会继续沿父类链向上**
（JVMS 5.4.3.3），于是落到 `Super.m`——调用成功，返回值变了，**一个异常都没有**。

这与字段不同：字段的 `GETFIELD A.x` 在 `A` 没有 `x` 且父类也没有时抛 `NoSuchFieldError`（§13 实测），
因为那条链上确实找不到。方法的"找不到"只有在**整条链都没有同签名方法**时才成立（M8c）。

所以方法删除的三种失败形态必须分开：

| 形态 | 触发条件 | 是否抛错 |
|:--|:--|:--|
| `NoSuchMethodError` | 整条链都没有同签名方法 | 是 |
| `AbstractMethodError` | 只有接口声明、没有实现 | 是（`Error`，不是 `LinkageError`） |
| **分派转移** | 祖先有实现，静默接住 | **否** |

第三种最危险：热更"成功"、日志干净、行为已经变了。

### 15.4 判据：归属方向要反

- **字段 / 静态方法 / private / `<init>`**：引用**向上**解析，owner 必须是被删成员的**后代**才会命中它。
  沿用 §13 的判据即可。
- **实例方法（参与虚分派）**：还要经过**向下**分派（JVMS 5.4.6），receiver 的实际类型决定执行谁。
  因此 owner 可以是 `A` 的**任何祖先**。归属条件变成"`A` 是 `owner` 的子类型"，再用
  **method selection** 在 `A` 上问"现在会执行哪个实现"。

**跨分支继承实现**是归属判定里最容易漏的一类：`class A { m() }` 自己**不**实现 `I`，是子类
`Sub extends A implements I` 靠**继承** `A.m` 满足 `I.m`。此时 `A` 与 `I` 没有直接子类型关系，
只判 `isSubtype(A, I)` 会漏掉——而 receiver 是 `Sub` 时，删掉 `A.m` 就让它失去了实现。
判据要额外找那个"桥"：同时是 `A` 与 `I` 后代的类型。只有 `refOwner` 是**接口**时才需要枚举
（若它是类，receiver 必须是它的子类型，而 `A` 不是，说明这条引用到不了 `A` 的方法）。
测试 `crossBranchInheritanceStillReachesTheDeletedImplementation` 守这一条——它先断言
`isSubtype(A, I) == false`，再断言删除仍被报告。

selection 与解析的另一个差别是 **maximally-specific**：接口层级里，更具体的声明会覆盖父接口的。
`interface Sub extends Base { int d(); }` 把 `Base` 的 `default d()` 重新声明为 abstract 之后，
`Base` 的 default **不再可用**——删掉类里的实现就是 `AbstractMethodError`，不是"回退到 default"。
只按"先遇到 default 就返回"会把它误判成分派转移（同样拒绝，但报告的原因完全错）。

### 15.5 接线：方法不经过 `LayoutGate`

`LayoutGate.checkChangedFields` 只解析 `changedFields`（字段），方法删除**没有任何门**。
接线时必须注意两点：

1. **必须在 `res.compatible()` 早退之前判**。本轮完全可能只删了方法、字段一个都没动——
   那时 `compatible()` 为 true，放在后面就永远走不到。
2. **扫描未完成时不能退回存活实例判据**。方法删除的影响与"当前有没有实例"正交（M8a 就是在
   有存活实例的情况下三条路径全部静默转移），所以 `scanned == false` 只能拒绝。

拒绝原因通过 `LayoutDecision.reason` 带出，否则日志会把它说成 "field layout incompatible"。

### 15.6 判定力

`RemovedMemberReadersTest` 新增 6 例、`LayoutGateWiringTest` 新增 6 例。变异检查各自只打红
对应用例（§16.4 调整过"回退算不算读者"的口径之后，这里的期望值也随之更新）：

| 注入的破坏 | 变红的用例 |
|:--|:--|
| `participatesInVirtualDispatch()` 恒 false（退化为只有向上解析） | `overriddenMethodRemovalFallsBackToSuperclass` / `soleInterfaceImplementation…` / `fallingBackToInterfaceDefaultIsNotBlocking` |
| 去掉 maximally-specific 筛选 | `subInterfaceRedeclaringDefaultAsAbstract…` |
| `hasReaders()` 把 `dispatchShifted` 算回去（防卫过当） | `overriddenMethodRemovalFallsBackToSuperclass` / `fallingBackToInterfaceDefaultIsNotBlocking` |
| `isAccessKindMismatch` 恒 false（不区分 ICCE） | `interfaceFieldHijackIsIncompatibleAccess` / 两条 `…LandingOn…ReportsIncompatibleChange` |
| 去掉 `decideLayout` 的方法分支 | `removedMethodWithOutOfBatchReader…` / `unscannedMethodRemoval…` |

### 15.7 未覆盖

- **`invokespecial` 的 `super.m()`**：`A` 里 `super.m()` 编译成 `Methodref Super.m` 且是 `invokespecial`。
  它不是**虚**分派，但也不是纯静态——HotSpot 的 `runtime_resolve_special_method` 会从
  `current_klass->super()` 重新做一次 `lookup_instance_method_in_klasses`（old-style super 查找），
  找不到就抛 `AbstractMethodError`。本类按 `kind` 无法区分 `invokevirtual` 与 `invokespecial`
  （常量池都记 tag 10），因此把它当成"可能分派"处理，方向偏保守（多报不会漏报）。
- **接口 default 方法删除后由类实现接住**：机制上应属分派转移，未单独实测。
- **多级遮蔽与重载**：只测了单层重写；重载（同名字不同描述符）由 `name:desc` 键天然隔离，未专门验证。
- **`AbstractMethodError` 在标准 HotSpot 上的形态**：只在 JBR 21 + DCEVM 上测过。
- **跨分支的桥接类型必须在扫描范围内**：`bridgingSubtype` 只在传给它的类型世界里找那个"同时是
  `A` 与 `I` 后代"的类。若 `Sub` 尚未加载且不在工程构建输出里，就找不到桥，这条会被漏判。
  这属于 §13.6.4 的扫描范围缺口，不是判据本身的缺陷。

## 16. 静态字段删除与编译期常量（`P05Probe` M9）

§13 只测了实例字段。静态字段的解析路径相同（JVMS 5.4.3.2 沿 owner 向上），但失败形态多一种：
落到**实例**字段上是 `IncompatibleClassChangeError`，而不是悬空或静默转移。更麻烦的是有一类
读者<b>根本不产生引用</b>，扫描完全看不到。

### 16.1 编译期常量：读者常量池里没有 Fieldref

`javac` 把 `static final` 的基本类型与 `String` 常量（有 `ConstantValue` 属性，JLS §13.4.9）
**直接内联**进读者的指令：

```
Holder.CONST (static final int = 42)   ->  bipush 42        （无 Fieldref）
Holder.TEXT  (static final String)     ->  ldc "hi"         （无 Fieldref）
Holder.LONG  (static final long = 7L)  ->  ldc2_w 7L        （无 Fieldref）
Holder.FLAG  (static final boolean)    ->  iconst_1         （无 Fieldref）

Holder.RUNTIME (static final int = compute())  ->  getstatic  Fieldref
Holder.REF     (static final Object)           ->  getstatic  Fieldref
Holder.plain   (static int)                    ->  getstatic  Fieldref
```

`Holder.class` 里只有前四个带 `ConstantValue` 属性，后三个没有。所以**边界不在 `final`，在
`ConstantValue`**——运行期初始化的 `static final` 与引用类型都照常走 `Fieldref`。

**这意味着"读者为空"这个结论对编译期常量是空洞的**：不是证明安全，而是<b>没有信号</b>。
批次撕裂下，陈旧读者会带着内联的旧值继续跑，热更"成功"、日志干净、行为已经变了，而且
**永远不会有异常**提醒你。

**但这不等于必须强拒。** 两个方向都不抛异常：已内联的读者运行期<b>根本不解析这个字段</b>；
未内联的读者常量池里仍有 `Fieldref`，会被 `hits` 正常抓到。而且"内联"本来就是 Java 常量的
既有语义——改常量值同样要全量重编才生效，这不是热更引入的风险。

所以门禁对常量字段<b>放行</b>，只把 `constantFields` 打进日志，提示"已内联的读者需要重编
才能看到新值"。`RemovedMemberReadersTest.compileTimeConstantsLeaveNoFieldrefInReaders`
把这条编译期事实固化成了断言（含"引用类型与普通 static 仍有 Fieldref"的对照）。

### 16.2 三种失败形态（实测，JBR 21.0.9 + DCEVM）

| 场景 | 删除前 | 删除后 |
|:--|:--|:--|
| **M9a** 没有别的声明 | `11` | `NoSuchFieldError: Class rem5.A does not have member field 'int s'` |
| **M9b** 父类有 `static s` | `11` | **`22`**（静默读到父类的值） |
| **M9c** 父接口有 `s`（接口常量） | `11` | **`33`**（静默转移，接口优先于父类） |
| **M9d** 父类只有**实例** `s` | `11` | `IncompatibleClassChangeError: Expected static field rem8.A.s` |
| **M9e** 删**实例** `x`，父类有 `static x` | `11` | `IncompatibleClassChangeError: Expected non-static field rem9.A.x` |
| **M9f** 删**实例** `x`，父接口有 `x` | `11` | `IncompatibleClassChangeError: Expected non-static field rem10.A.x` |

M9d / M9e 是同一个检查的两面（`is_static != fd.is_static()`），凑成双向闭环；M9f 证明
"接口字段"不是特例——它必然是 `public static final`，走的是同一条 ICCE，只是经由
"接口先于父类"的字段搜索路径命中。

M9b / M9c 是**合法回退**（同静态性）：删掉子类的遮蔽字段，本意就是回到父类/父接口的那个，
零异常、读者源码不用改，因此放行（§16.4）。M9d / M9e / M9f 是**静态性翻转**，必抛 ICCE，
必须拦。所以"接口字段截胡"要看方向：删**静态**字段落到接口常量上是回退（M9c，放行）；
删**实例**字段落到接口常量上是 ICCE（`interfaceFieldHijackIsIncompatibleAccess`，拦截）。
M9d 是新的一种：解析成功，但解析到的东西**静态性不对**。

### 16.3 判据：静态字段并入读者判据，删除不再有 liveness 兜底

- 静态字段删除与实例字段**共用**"批次外读者为空"：证明无读者就 `WAIVE_NO_READERS`。
- **任何字段删除在扫描未完成时都只能拒绝**，不退回存活实例判据——liveness 回答的是"现在有没有
  实例会被错读"，而删除的风险是"之后新建的实例 / 静态访问被陈旧代码读"，两者正交。
  这条最初只加在静态字段与方法上，**实例字段漏了**（`scanned == false` 时会落到 `WAIVE`，
  无实例就放行），而风险 12 的描述其实早已指出这个洞——补上后三条路径口径一致。
- 编译期常量单独拦（§16.1）。

### 16.4 只拦"会抛错"的：合法回退不算读者

初版把 `dispatchShifted` 与 `retargeted` 都算进 `hasReaders()`，理由是"静默读到别的值也是行为
变化"。那是**防卫过当**——它把两类完全不同的东西混在一起：

- **真错误**：`hits`（`NoSuchFieldError` / `NoSuchMethodError`）、`abstractMissing`
  （`AbstractMethodError`）、静态性翻转（`IncompatibleClassChangeError`）。这些即使全量重编、
  零批次撕裂也会炸，因为字节码本身就不自洽。
- **合法回退**：删掉子类 Override 方法 / 遮蔽字段后，读者解析到父类实现或**同静态性**的父类
  字段。旧字节码本来就能正确解析，全量重编后也仍然对得上，运行期**零异常**，读者源码不用改。
  这正是开发者删除的本意。

把后者算作读者会导致"只要工程里有人调过父类方法（甚至 `Object.toString()`），子类就永远删不掉
任何 Override"。

现在的划分：

```java
hasReaders() = !hits.isEmpty() || !abstractMissing.isEmpty() || !incompatibleAccess.isEmpty();
```

`retargeted` / `dispatchShifted` 仍然上报，只是不阻断；接线层把它们打进 `[LAYOUT-WAIVE]` 的
日志（"Fallback(s): N reference(s) now resolve to a supertype implementation or a same-kind
field"）。静态性翻转被单独拆成 `incompatibleAccess`，因为它是真 ICCE，必须继续拦。

### 16.5 成本

`find()` 因为要枚举后代做跨分支查找（§15.4），从 10.8 ms 涨到 20.5 ms。按被删成员缓存
后代集合后回落到 **17.4 ms**；`universe()` 10.8 ms。441 个工程类合计约 **28 ms**，
加"走一遍构建输出"9 ms，整条约 37 ms。仍然只在本轮有成员被删时触发。

### 16.6 未覆盖

- **`static final` 数组常量**：`static final int[] A = {1,2}` 没有 `ConstantValue`（数组不是
  常量表达式），走 `Fieldref`，但未单独实测。
- **`ConstantValue` 与 `InitFix` 的交互**：常量字段被删后存活实例没有槽位，这是另一条路径，未测。
- **枚举常量的 `$VALUES` / `values()`**：编译器生成的合成成员，被 `removedMembers` 的
  `$remold$` / synthetic 过滤规则覆盖到什么程度未验证。
- **M9 只测了 `int`**：`long`/引用类型的失败形态未逐一实测（机制上同为 `Fieldref` 解析）。

### 16.7 按 HotSpot `LinkResolver` 核对后修正的三处

拿到 `linkResolver.cpp` 的实现之后回头对判据，发现三处偏差（两处代码、一处文档）：

1. **`IncompatibleClassChangeError` 的条件是双向的**。`resolve_field` 里是
   `if (is_static != fd.is_static())`，两个方向都抛：`GETSTATIC` 落到实例字段上是
   `Expected static field`，`GETFIELD` 落到静态字段上是 `Expected non-static field`。
   原实现只处理了前者（`if (!m.isStatic()) return "静默读"`），把后者误报成"静默读到另一个值"。
   判据改成比较 `targetIsStatic != m.isStatic()`——引用的访问方式必然与被删字段的静态性一致
   （否则改动前就编译不过），所以用 `m.isStatic()` 代表访问方式。
   这条顺带更正了**接口字段截胡**的后果：接口字段都是 `static final`，所以 `Sub/x` 从实例字段
   改指向接口常量时是 **ICCE**，不是"静默读到 `I.x`"。M9d / M9e 已经把两个方向都跑通
   （`Expected static field` / `Expected non-static field`），M9f 证明接口来源走的是同一条检查。

2. **方法选择要跳过类链上的 `static` 方法**。`lookup_instance_method_in_klasses` 里有个
   `while (result != NULL && result->is_static() && ...)` 的循环，遇到 static 会继续向上——
   因为 static 方法不参与虚分派。原实现的 `selectMethod` 在类链上找到第一个声明就返回，
   会把 static 方法误当成"可执行实现"，把 `AbstractMethodError` 报成静默转移。
   `TypeInfo` 因此增加了 `staticMethods`。
   **注意**：这个形态用 Java 源码造不出来（同一层级里 instance 与 static 同签名非法），
   所以没有对应用例，属于"与 HotSpot 对齐的防御性修正"。

3. **`AbstractMethodError` 的两条触发路径**。`runtime_resolve_virtual_method` 里
   `selected_method.is_null()` 与 `selected_method->is_abstract()` 都走
   `throw_abstract_method_error`，消息格式正是 M8b 实测到的
   `Receiver class %s does not define or inherit an implementation of the resolved method ...`。
   判据里的 `NOT_FOUND` 与 `abstractOnly` 正好对应这两条，无需改动。

另外两处**核对后确认一致**、不必改的：

- 字段解析 `find_field` 走 JVMS 5.4.3.2（自身 → 接口 → 父类），与 `resolveField` 一致；
- 方法解析 `resolve_method` 是"类链（含 default_methods 表）→ 接口（skip_defaults）"，
  与 `resolveMethod` 一致。default 方法在类加载时由 `defaultMethods.cpp` 预先算好
  maximally-specific 集合，这解释了为什么"子接口把 default 重新声明为 abstract"会让父接口的
  default 消失——它不是运行期选择的结果，而是那张表里就没有它。
