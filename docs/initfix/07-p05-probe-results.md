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
