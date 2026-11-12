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
bash tests/enum-append-probe/run.sh     # 每个场景独立 JVM（redefine 不可回滚），共 30 次
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
| `repair`（存量集合） | 追加前已存在的 `EnumMap` / `EnumSet` | `EnumMap.put/get(BLUE)` 抛 `AIOOBE`；`EnumSet` 的 `add/contains` 正常但迭代/`toArray` 抛 `AIOOBE`、`complementOf` 少算一个 |
| `mirror` | 静态字段里持有的旧 `Class`（影子副本） | `valueOf(shadow,"BLUE")` IAE、`isInstance(blue)=false`、`new EnumMap(shadow).put(blue)` 抛 `CCE` |

`-Xint` 对照与 `-XX:CompileCommand=exclude,...` 对照在 `run.sh` 里显式列出，用来区分"缓存没清"与
"JIT 折叠"——两者读数不同，不能用一个信号解释另一个。

## 边界

- 探针只覆盖 JBR 21.0.9 + C2；其他 JVM / Graal 未测。
- "已缓存 `values()` 数组副本"与"懒加载的旧代码"两类残留无法在本探针里消除，属于任何热更都存在的
  陈旧副本问题，不在被测方案的能力范围内。
- 探针用 `Unsafe.allocateInstance` 物化新常量（反射 `Constructor.newInstance` 对枚举被 JDK 显式禁止）；
  Remold 的实际补丁走 NESTMATE 隐藏类里的 `invokespecial <init>`，能保留逐常量构造状态，见
  `docs/topology/10-enum-constant-append-infeasibility.md` §5。
