# 11 实例补丁回填的访存局部性：实验记录与证据分级

> **文档类型**：实测记录（一次性读数/证据；实现状态以 [`docs/status.md`](../status.md) 为准）

本文件记录「2M/4M 实例热更时 initFix 的 `inst` 段为什么对堆遍历顺序极度敏感（~0.9 s vs ~4.9 s）」的
实验、证据分级，以及把实例枚举改成计数排序后的实测收益与桶窗口选型的依据。

## 1. 环境

- JDK：JBR 25，`openjdk 25.0.5.0.3-internal`（build 2026-10-24，强定义 DCEVM）。
- 机器：Linux aarch64，**KVM guest**，Neoverse-N1，2 vCPU，11 GB RAM。
  客户机 sysfs 未暴露 L3/SLC（只有 L1/L2），**末级缓存容量无法确认**。
- GC：G1（DCEVM 只支持 Serial/G1）；2M 实例 `-Xms1g -Xmx3g`，4M `-Xms2g -Xmx6g`。
- `perf` 不可用（`perf_event_paranoid=4`、无 root），dTLB/LLC 计数器与指令级剖析拿不到；
  机制结论均为对照实验推断。
- 测量：`-Dremold.perfLog=true` 的 `[Lib] getInstances` 分项与
  `[InitFix] applyPatch ... [collect, ..., inst, post]`；另用 `/proc/<pid>/task/*/stat`
  按线程名采样 utime/stime/minflt；`-Xlog:gc*` 用于暂停归属。

## 2. 关键观测

### 2.1 `inst` 对访问顺序极度敏感（2M，n=6）

| 版本 | 首批 inst | 稳态 inst |
|---|---|---|
| 无排序（按 JVMTI 返回顺序） | 4.7–4.9 s | 3.0–3.4 s |
| 桶/计数排序 | 0.83–0.98 s | 0.46–0.63 s |

### 2.2 写入值类型对照（2M，G1 默认、无排序、各 3 次）

null 组只有 1 个待补字段，常量/int 组 2 个；按字段归一后差约 **14×**。

| 写入 | inst（首批） |
|---|---|
| `null` | 168 / 181 / 166 ms |
| 共享常量 String | 4737 / 4875 / 4809 ms |
| `int` | 356 / 465 / 389 ms |

其它配置（2M，无排序，引用常量，首批 inst）：

| 配置 | inst |
|---|---|
| SerialGC | 541 / 306 ms |
| G1 + `-XX:G1HeapRegionSize=32m` | 556 / 1461 ms |
| G1 + `-Xms3g -Xmx3g`（预提交） | 581 / 277 ms |
| G1 + `-XX:NewSize=1g -XX:MaxNewSize=1g`（Xms1g） | 514 / 296 ms |
| G1 + `-XX:ConcGCThreads=1` | 5792 / 3580 ms（无效） |
| G1 + `-XX:+UseTransparentHugePages` | 2960 / 4630 ms（−4~10%） |
| G1 + `-Xms3g` + 交换前 `System.gc()` | **669 / 295 ms（未解释，见 §3.3）** |

### 2.3 并发/GC 侧证据（慢配置、无排序、2M）

- `inst` 窗口内 **0 次 GC 暂停**（交换前的 Heap Inspection full GC 在窗口之前）。
- 窗口内线程 CPU：`HotSwap-Schedul` 2030 ms user / 0 sys / 63 minflt；
  4 个压测 worker 各约 2 s（压测设计为自旋线程）；**`G1 Refine#0` 10 ms、`G1 Conc#0` 0 ms**。
- `gc+refine` 实时计数：窗口内 pending 脏卡仅 **+9216**（= 36 × `G1UpdateBufferSize`=256，后者已核对
  JDK 25 product 默认值），`wanted=0`；`dirtied: 10455` 来自启动期 GC(0)，与补丁循环无关。

## 3. 证据分级

### 3.1 强证据：非 null 引用写的 post-barrier 路径是主因

同一 CAS 循环、同一乱序数组、同一堆状态下，写入值换成 `null`（168–181 ms）或字段换成 `int`
（356–465 ms），`inst` 从 4.7–4.9 s 掉到 0.17–0.47 s；同时 refinement 线程空闲、暂停为零、缺页为零。
能同时解释三组对照的只有「非 null 引用写的写屏障路径」。

### 3.2 推断：`dmb` 逐元素串行化 miss（KVM stage-2 放大）

aarch64 G1 post barrier 在「卡是否已脏」判断之前有 StoreLoad 栅栏，故即使卡已脏，每次写仍付一次
`dmb`；栅栏之后的随机 miss 无法相互重叠。顺序访问/桶排序、SerialGC（卡标记为普通 store）、
32 MB region（filter 命中率更高）、THP 无效（与页无关）都指向该机制。**未直接测量**；
KVM 嵌套页表使单次 miss 更贵，量级缺口只能这样解释。

### 3.3 未解释：`Xms3g` + 预 GC 仍快

`-Xlog:gc+heap=debug` 确认交换前 2M 实例已在 **old**（`young 0`、`Old regions 33`、region 2 MB），
但 `inst` 仍只有 669/295 ms。该行与「old gen 走屏障慢路径」的朴素模型冲突，目前无机制解释。

### 3.4 部分解释：`collect` 随 tag 数增长

JDK 25 `JvmtiTagMap::get_objects_with_tags` 的 `TagObjectCollector::do_entry` 对**每个已打标对象**
线性扫描传入 tag 列表，复杂度 O(对象数 × tag 数)；但实测增量递减（2M 下 collect 约
1.1 / 1.32 / 1.51 / 1.60 s 对应 1/4/8/16 桶，每 tag 增量约 70/48/11 ms），线性扫描只解释一部分。

## 4. 实例枚举改为计数排序

原实现（`ddbf34e`）是「打桶 tag → GetObjectsWithTags → malloc 排序数组 → 二次回填」。
本分支改为：打 tag 时 `tag = base + min(visit_seq >> kEnumBucketShift, cap-1)` 并统计每桶数量；
一次查询取回句柄与桶号；**按桶前缀计数直接落位**——结果数组按堆遍历顺序（≈ region 顺序）排列，
省掉 16 MB 中间数组与一次搬运。

> 收益拆分：整批 7.7 s → 4.6 s 的大头来自**恢复桶排序**（相对 fc41b37 的无排序版本）；
> 计数排序相对旧桶排序（ddbf34e）再省约 0.55 s（2M 首批 5180 → 4599 ms，约 11%）。

2M（n=6，交替执行，p50 [min–max]；此表为窗口 2^17 时代的数据，仅用于对比旧桶排序与计数排序本身）：

| 指标 | 旧桶排序 | 计数排序（window 2^17） |
|---|---|---|
| 枚举 total | 4229 [3976–4344] ms | 3693 [3478–3877] ms |
| fill | 1263 ms | 755 ms |
| collect | 1561 ms | 1600 ms |
| 首批 applyPatch | 5180 [5039–5318] ms | 4638 [4434–4880] ms |
| 稳态 applyPatch | 4536 [4480–4576] ms | 3977 [3794–4354] ms |
| 首批 / 稳态 inst | 883 / 530 ms | 894 / 545 ms |

### 4.1 桶窗口：拐点实测

**固定桶数实现用的是固定 shift + 末桶钳制**：shift 17/18/19 对应 16/8/4 桶，桶号 = `seq >> shift`，
超出上限的实例全部钳进最后一桶。因此 4M 下固定 16 桶的末桶装了约 200 万对象（该桶内未排序），
那组数字**不能**当作窗口大小效应；窗口效应要用「等窗口、无钳制」的变体测。

**等窗口、无钳制**的 4M 实验（shift 17/18/19/20/21 × cap 1024，n=3；测试对象约 32–48 B）：

| 4M | 2^17（31 桶） | 2^18（16 桶） | 2^19（8 桶） | 2^20（4 桶） | 2^21（2 桶） |
|---|---|---|---|---|---|
| 首批 inst | 1768 | 1729 | 1858 | **7056** | 10307 |
| 稳态 inst | 1078 | 1129 | 1095 | **5942** | 6254 |
| collect | 3636 | 3276 | 3156 | 2787 | 2475 |
| 首批 applyPatch | 10244 | 9993 | 9892 | 14851 | 17696 |
| 稳态 applyPatch | 9788 | 9012 | 8615 | 13326 | 13170 |

结论：窗口在 2^17–2^19 个对象内 `inst` 持平（±6%），到 **2^20 出现明显拐点**（4M 稳态 1095→5942 ms）；
collect 随桶数单调增长。因此窗口应取**拐点下方并留余量**：最终取 2^18（约为拐点已测上沿 2^19 的 1/2）。

**对象大小对照**（2M，shift 19 对象窗口 vs 按字节窗口，n=3）：

| 2M | 稳态 inst | 说明 |
|---|---|---|
| 普通对象（32–48 B），对象窗口 2^19 | 572 ms | 窗口 ≈ 21 MB |
| 胖对象（约 128 B），对象窗口 2^19 | 902 ms | 同样 52 万对象，但 ≈ 64 MB |
| 胖对象，按字节窗口 16 MB（≈13 万对象） | 893 ms | **没有回到普通对象的水平** |

即大对象变慢主要是**逐元素成本**上升（每个对象跨两条 cache line，两个字段各引一次 miss），
而不是“窗口字节数超过拐点”；按字节分桶在这个对照里没有收益，所以最终仍按对象数分桶。
这也说明：拐点数值只在 32–48 B 对象上直接测过，不能外推成“字节容量”结论——文档不写缓存容量推导。

### 4.2 最终选型

`kEnumBucketShift = 18`（窗口 2^18 = 262,144 实例，普通对象约 8–12 MB）、`kEnumBucketCap = 64`
（约 1,677 万实例后尾部进入最后一桶；此时该桶内不再有序，**等于回到旧行为**，每元素成本约 4–5 倍，
不是“平滑降级”）。窗口是**经验选择**：为 4M 实测拐点（2^19–2^20 个对象）留 2 倍余量，不是缓存容量推导。

- 2M → 8 桶（n=6）：首批/稳态 applyPatch **4547 / 3894 ms**，inst **916 / 549 ms**，
  枚举 3533 ms（collect 1406、fill 768）。
- 4M → 16 桶（n=3）：首批/稳态 applyPatch **9993 / 9012 ms**，inst **1729 / 1129 ms**，
  枚举 8165 ms（collect 3276、fill 1641）。

每元素成本近似线性：4M 稳态 inst 1129 ms / 2M 稳态 549 ms ≈ **2.06×**（实例数 2×）。

桶规划被抽成纯函数（native `enumBucketCount` + Java 镜像兜底），上限路径由
`EnumBucketPlanTest` 覆盖（2M→8、4M→16、64×窗口→64、超上限仍 64）；测试在 native 符号可用时
还会断言 C++ 侧返回值与 Java 模型一致，符号缺失（旧库平台）时该用例 skip 而非假绿。

## 5. 现在的瓶颈结构（2M/4M）

- 2M：首批 applyPatch 4547 ms 中枚举 3533 ms（约 **78%**），inst 916 ms；
- 4M：9993 ms 中枚举 8165 ms（约 **82%**），inst 1729 ms。
- 枚举内部：`iterate`（打 tag）与 `cleanup`（清 tag）各一趟整堆遍历（约 0.65–1.4 s），
  `collect` 1.4–3.3 s（随 tag 数），`fill` 0.77–1.64 s。
- 因此 inst 已不是瓶颈，桶策略到此为止；后续要动的是 iterate/cleanup/collect，
  或在 JVMTI 之外换机制（例如 DCEVM 重定义时顺带初始化字段），不属于本 PR。

## 5. 现在的瓶颈结构（2M/4M）

- 2M：首批 applyPatch 4488 ms 中枚举 3435 ms（约 **77%**），inst 979 ms；
- 4M：9550 ms 中枚举 7639 ms（约 **80%**），inst 1850 ms。
- 枚举内部：`iterate`（打 tag）与 `cleanup`（清 tag）各约 0.67 s / 1.24 s，均为整趟堆遍历；
  `collect` 1.26–2.99 s（随 tag 数）；`fill` 0.8–1.56 s。
- 因此 inst 已不是瓶颈，桶策略到此为止；后续要动的是 iterate/cleanup/collect。

## 6. 复现

```bash
# 原生库（本地实验用 g++；提交/CI 由 zig 以 glibc 2.17 目标重编）
g++ -shared -fPIC -std=c++2b -O3 -I core/cpp/jni_posix \
    -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" \
    core/cpp/tool.cpp -o core/libs/libtoolaarch64.so
./gradlew :core:jar
# 2M：/tmp/remold-test/remold-bench.sh（GEN=gen_player_2m MAIN_PATCH=patch-main-2m -Xms1g -Xmx3g）
# 4M：MAIN_PATCH=patch-main-4m -Xms2g -Xmx6g
```

## 7. CI 产物与原生二进制

`ci.yml` 原先的 `build-jars` / `test` / `test-dcevm` 与 `build-native` 并行且无 `needs`：
打包与测试用的都是仓库里已提交的原生库，脚本改动 tool.cpp 而不刷新二进制时会出现
“测试绿了、产物是旧库”（首版分支 CI 产物 2M `inst` 仍是 4843/3047 ms，就是这个原因）。
本 PR 已让这三个 job 都 `needs: build-native` 并下载 `native-libraries` artifact，同时把 CI zig 产物
提交进 `core/libs/`（Linux x64/aarch64、Android arm64），保证 `:tests:test`、jar、release 三者同源。

本地/CI 产物在 2M、4M 上的复核结果与本地 g++ 构建在噪声内一致（见 PR 描述）。

待办：macOS dylib 与 Windows dll 不在本工作流构建，需 release 流水线重编；接口未变，
旧库平台上分桶规划回落到 Java 镜像（`EnumBucketPlanTest#nativePlanMatchesModel` 会 skip 而非假绿）。
## 8. 限制

- 单机 KVM guest（Neoverse-N1、2 vCPU），绝对值不可外推；L3 容量未知。
- `perf` 不可用，`dmb`/miss 机制为推断而非计数验证。
- 4M 各档 n=2–3，4M 数据仅用于相对比较。
