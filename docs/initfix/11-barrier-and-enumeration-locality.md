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

2M（n=6，交替执行，p50 [min–max]）：

| 指标 | 旧桶排序 | 计数排序（window 2^17） |
|---|---|---|
| 枚举 total | 4229 [3976–4344] ms | 3693 [3478–3877] ms |
| fill | 1263 ms | 755 ms |
| collect | 1561 ms | 1600 ms |
| 首批 applyPatch | 5180 [5039–5318] ms | 4638 [4434–4880] ms |
| 稳态 applyPatch | 4536 [4480–4576] ms | 3977 [3794–4354] ms |
| 首批 / 稳态 inst | 883 / 530 ms | 894 / 545 ms |

### 4.1 桶窗口：先排除一个错误解释

**固定桶数实现用的是固定 shift + 末桶钳制**：shift 17/18/19 对应 16/8/4 桶，桶号 = `seq >> shift`，
超出上限的实例全部钳进最后一桶。因此 4M 下固定 16 桶的末桶装了约 200 万对象（该桶内未排序），
4M 的差距来自“一大块没排序”，**不能**作为窗口大小效应的证据（2M 下 4/8/16 桶都无钳制，
窗口分别约 52 万/26 万/13 万，inst 基本一致，896/916/894 ms 首批）。

**等窗口、无钳制**的 4M 实验（shift 17/18/19 × cap 1024，n=3）：

| 4M | 31 桶（窗口 2^17） | 16 桶（2^18） | 8 桶（2^19） |
|---|---|---|---|
| 首批 inst | 1768 [1678–1832] | 1729 [1604–1760] | 1858 [1661–1869] |
| 稳态 inst | 1078 [1076–1250] | 1129 [1055–1321] | 1095 [1071–1130] |
| collect | 3636 | 3276 | 3156 |
| 首批 applyPatch | 10244 [10177–10620] | 9993 [9764–10294] | **9892 [9831–9962]** |
| 稳态 applyPatch | 9788 [8988–9874] | 9012 [8745–10406] | **8615 [8433–8701]** |

结论：在 2^17–2^19 范围内 **`inst` 对窗口不敏感**（±6%），而 collect 随桶数增长；
因此窗口应当取**较大值以减少 tag 数**，而不是按缓存容量推导。先前“窗口放得进末级缓存”的推断
**已被这组数据降级/撤回**（本机也无法确证 L3 容量）。

### 4.2 最终选型

`kEnumBucketShift = 19`（窗口 2^19 = 524,288 实例，≈16–24 MB 对象量）、`kEnumBucketCap = 64`
（超过 64×窗口≈3,350 万实例后尾部进入最后一桶，属优雅降级）。窗口是**经验选择**，不是缓存容量推导。

- 2M → 4 桶（n=2）：首批/稳态 applyPatch **4488 / 3768 ms**，inst **979 / 572 ms**，
  枚举 3435 ms（collect 1261、fill 798）。
- 4M → 8 桶（n=2）：首批/稳态 applyPatch **9550 / 8571 ms**，inst **1850 / 1125 ms**，
  枚举 7639 ms（collect 2987、fill 1564）。

每元素成本近似线性：4M 稳态 inst 1125 ms / 2M 稳态 572 ms ≈ **1.97×**（实例数 2×）。

桶规划被抽成纯函数（native `enumBucketCount` + Java 镜像兜底），上限路径由
`EnumBucketPlanTest` 覆盖（2M→4、4M→8、64×窗口→64、超上限仍为 64）。

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

`ci.yml` 原先的 `build-jars` 与 `build-native` 并行且无 `needs`，打包的是仓库里已提交的旧二进制；
首版分支 CI 产物 2M `inst` 仍是 4843/3047 ms（旧库）。本 PR 已给 `build-jars` 加
`needs: build-native` 并消费其 `native-libraries` artifact，使 CI 产物必定包含本次 `tool.cpp` 改动。

本地验证（zig 产物，md5 `e241fc…`）在 2M 上曾复核为 inst 868/549 ms；最终选型（window 2^19、cap 64）
在 CI 产物上的复核结果以 PR 描述为准。

待办：macOS dylib 与 Windows dll 不在本工作流构建，`tool.cpp` 改动需等 release 流水线重编；
接口未变，旧库平台上应回落到旧行为（`LibToolTest` 在该平台仍应通过）。

## 8. 限制

- 单机 KVM guest（Neoverse-N1、2 vCPU），绝对值不可外推；L3 容量未知。
- `perf` 不可用，`dmb`/miss 机制为推断而非计数验证。
- 4M 各档 n=2–3，4M 数据仅用于相对比较。
