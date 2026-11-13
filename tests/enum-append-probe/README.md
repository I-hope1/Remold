# 枚举追加常量的真机探针（enum-append-probe）

本探针回答一个问题：**已初始化的 `enum` 热更追加常量后，能不能在运行期把新常量"补"成真的
（`values()` / `valueOf()` / `EnumMap` / `EnumSet` / `switch` 全部看到它）？**

它**不挂 `check`**，需要 DCEVM 能力与一个真实 JDK 8 javac，只用于取证与回归。结论与逐条读数见
[`docs/topology/10-enum-constant-append-infeasibility.md`](../../docs/topology/10-enum-constant-append-infeasibility.md)；
实现状态见 [`docs/status.md`](../../docs/status.md)。

## 为什么需要它

布局门对"枚举常量集合变化"是**拒绝**策略（`ENUM_CONSTANT_SET_CHANGED`）。支持"追加常量"看似只要
补 `$VALUES` + 清 `java.lang.Class` 的枚举缓存；本探针用真实 `Instrumentation.redefineClasses`
逐条测量这条方案，结论是**必要但不充分**：冷路径能修好，但已 JIT 编译的读者、已初始化的
`$SwitchMap$` 持有类、存量 `EnumMap`/`EnumSet` 实例都修不回来。拒绝原因与设计文档引用这些读数，
所以读数必须可复现。

## 环境

| 变量 | 用途 | 默认值（本仓库开发机） |
|:--|:--|:--|
| `JBR21_HOME` | 有增强重定义能力的 JDK 21（JBR），跑 `-XX:+AllowEnhancedClassRedefinition` | `F:/files/java/jdks/jbrsdk_jcef-21.0.9` |
| `JDK8_HOME` | 真实 JDK 8 `javac`，产出 `<clinit>` 内联构造 `$VALUES` 的旧字节码形态 | `F:/files/java/jdks/jdk-1.8` |

脚本按 `uname` 自动选择 `bin/javac(.exe)` 与 classpath 分隔符，Windows / Linux / macOS 均可运行；
`JDK8_HOME` 必须是**真的 JDK 8**——javac 21 的 `--release 8` 仍会生成合成 `$values()`，测不到旧形态。

## 运行

```bash
bash tests/enum-append-probe/build.sh   # 编 v1/v2 字节码（javac21 + javac8 两套）、probe、agent jar
bash tests/enum-append-probe/run.sh     # 每个场景独立 JVM（redefine 不可回滚），共 42 次
```

原始读数落在 `tests/enum-append-probe/b/logs/all.log`（`b/` 已 gitignore）。每次运行只输出
`OBS <key> = <reading>`，按 key 过滤即可：

```bash
grep -E 'OBS (repair|baseline|jit|switch|cache|hazard)' tests/enum-append-probe/b/logs/all.log
```

## 关键读数（一次完整运行的代表值）

| 场景 | 观测 | 读数 |
|:--|:--|:--|
| `baseline`（不修复） | 新增常量字段 / `values()` / `valueOf` | 字段可解析但为 `null`；`values()` 仍 2 项；`valueOf("BLUE")` 抛 `IllegalArgumentException` |
| `repair`（重写 `$VALUES` + 清两个 Class 缓存） | `values()` / `valueOf` / 新建集合 / 反序列化 | 全部看到 `BLUE`（冷路径成立） |
| `race`（redefine→补丁窗口内有消费者） | 只清一个缓存 | `valueOf` 仍 IAE、`getEnumConstants` 仍 2、`EnumMap` 抛 `AIOOBE` —— 两个缓存都必须清，且要在 `$VALUES` 重写之后 |
| `jit-spin` | 预热 1e6 次后修复，读 `values().length` | 编译器内联的读者仍读到 2；`-Xint` 读到 3；再次 redefine 触发 deopt 后才变 3 |
| `repair-resize` / `swv2-*` | 已初始化的 `$SwitchMap$Color` 持有类 | `switch(BLUE)` 抛 `AIOOBE: Index 2 out of bounds for length 2`；把数组扩到 3 后不抛但落 `default` |
| `swmap-rebuild` / `swmap-rebuild-hot` | 重定义消费者 + 重建 `$SwitchMap$` 表 | 只重定义消费者（新 case 已在字节码里）仍 `AIOOBE`——holder `<clinit>` 不重跑；把 holder 的 `static final int[]` 换成新映射后 `sw(BLUE)=3`；已编译消费者换表后单次调用即读到新表 |
| `heap-repair` | 存量 `EnumMap`/`EnumSet` 逐实例换内部数组 | 换前 `put(BLUE)` 与迭代抛 `AIOOBE`、`complementOf` 少 1；换后同一实例的 `put/get/entrySet/equals/序列化` 与 `EnumSet` 迭代/`toArray`/`complementOf`/`remove` 全部正确 |
| `repair`（存量集合） | 追加前已存在的 `EnumMap` / `EnumSet` | `EnumMap.put/get(BLUE)` 抛 `AIOOBE`；`EnumSet` 的 `add/contains` 正常但迭代/`toArray` 抛 `AIOOBE`、`complementOf` 少算一个 |
| `mirror` | 静态字段里持有的旧 `Class`（影子副本） | `valueOf(shadow,"BLUE")` IAE、`isInstance(blue)=false`、`new EnumMap(shadow).put(blue)` 抛 `CCE` |

`-Xint` 对照与 `-XX:CompileCommand=exclude,...` 对照在 `run.sh` 里显式列出，用来区分"缓存没清"与
"JIT 折叠"——两者读数不同，不能用一个信号解释另一个。

### 直接 deopt 与 `$VALUES` 内联（S5d / S6 场景）

问题：`$VALUES` 会不会被 JIT 当成常量折叠？能不能直接改它、直接 deopt 读者让新常量可见？
JBR 21.0.9 实测（`jit-inline` / `jit-values-write` / `deopt-*` / `jit-spin-reader-deopt`）：

| 观测 | 读数 |
|:--|:--|
| `-XX:+PrintInlining` | `probe.Probe::hotLen` 内联 `test.Color::values (10 bytes) inline`——内联的是 `values()`；`$VALUES` 是普通 `getstatic` |
| 预热后 Unsafe 把 `$VALUES` 换成 4 元素数组（不 deopt） | 热读者仍 3、`Color.values().length` 仍 3（`OBS vwrite.hotLen.sample*`） |
| 仅对读者类 `redefineClasses(Probe, 同字节)` | 立刻变 4（`OBS vdeopt.hotLen.sample* = 4`）；`retransformClasses(Color)` 也 ok |
| 真机热更：v1(2 常量) 热读者/spinner | 保持 2；只 deopt 读者类（不再 redefine 枚举）后 `hotLen=3`、spinner `[2,3]` |
| 改写 `static final` 引用字段（BLUE→GREEN）后对宿主类再 redefine 一次 | 热读者（含直接 `GETSTATIC` 的 `BlueReader.read`）由 stale 的 BLUE 立刻变 GREEN（`OBS sc.afterStageC.*`）——阶段 C 是写入后的 deopt 屏障 |
| 可写性 | Unsafe `staticFieldBase/Offset` 可写；`Field.set` 不可；`VarHandle.set` 抛 `UnsupportedOperationException` |

结论：`$VALUES` **不是** ConstantValue 常量折叠，旧读数是已编译代码里被提升的 `getstatic` / 内联
`values()`；直接 deopt 读者确实能刷新。但这不改变"追加常量仍拒绝"的结论——`$SwitchMap$` 持有类、
存量 `EnumMap`/`EnumSet` 不随 deopt 修复，且必须覆盖全部已加载读者，遗漏即静默旧值。
逐条读数与设计结论见 [`docs/topology/10-enum-constant-append-infeasibility.md`](../../docs/topology/10-enum-constant-append-infeasibility.md) §3.4.1。

注：`deopt-*`（S8a~S8e）是**静默路径控制组**——在没有并发热读者的 JVM 里，redefine 本身已经把
已编译读者 deopt 掉（读数直接为 3），因此它们不区分 deopt 手段；真正有区分力的读数是
`jit-values-write`（纯写字段不 redefine：3 → 读者类 deopt 后 4）与 `jit-spin-reader-deopt`
（真机热更：2 → 读者类 deopt 后 3）。

### `$SwitchMap$` 重建与存量集合（S4d / S4e / S9）

`repair-resize` 只证明"扩容不补 case"。补测把两件事分开：

| 观测 | 读数 |
|:--|:--|
| 消费者换成带 `case BLUE` 的 v2 字节（同类名、同一 holder） | 表仍是 `[1, 2]`，`switch(BLUE)` 继续 `AIOOBE`——holder `<clinit>` 不重跑 |
| 把 holder 的 `static final int[]` 换成新映射 `[1, 2, 3]` | `sw(RED)=1`、`sw(GREEN)=2`、`sw(BLUE)=3`——表可写，缺的是从新 `<clinit>` 推导映射 |
| 预热 1e6 次（tier 4 编译）后换表 | 单次调用即读到新表（表写成 `[1, 2, 1]`，读数 1）；换表瞬间正在运行的已编译循环**未测** |
| 存量 `EnumMap`：换 `keyUniverse`+`vals` | 换前 `put(BLUE)` `AIOOBE`；换后 `put/get/entrySet/keySet/values/equals/序列化` 全对（同一实例） |
| 存量 `EnumSet`：只换 `universe` | 换前 `add` 成功但迭代/`toArray` `AIOOBE`、`complementOf` 少 1；换后迭代/`toArray`/`complementOf`/`remove` 全对 |

边界：遍历堆找实例本身（`JVMTI IterateOverInstancesOfClass`）不在本探针内；redefine→遍历窗口内新建的实例、
换数组瞬间并发写、`EnumSet` 跨 64 常量边界（`RegularEnumSet`→`JumboEnumSet`）都未覆盖，不能据这些读数
声称"枚举追加已可热修"。

## 边界

- 探针只覆盖 JBR 21.0.9 + C2；其他 JVM / Graal 未测。
- "已缓存 `values()` 数组副本"与"懒加载的旧代码"两类残留无法在本探针里消除，属于任何热更都存在的
  陈旧副本问题，不在被测方案的能力范围内。
- 探针用 `Unsafe.allocateInstance` 物化新常量（反射 `Constructor.newInstance` 对枚举被 JDK 显式禁止）；
  Remold 的实际补丁走 NESTMATE 隐藏类里的 `invokespecial <init>`，能保留逐常量构造状态，见
  `docs/topology/10-enum-constant-append-infeasibility.md` §5。
