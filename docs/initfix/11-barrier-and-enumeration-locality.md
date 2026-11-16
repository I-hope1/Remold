# 11 实例补丁回填的访存局部性：实验记录与证据分级

> **文档类型**：实测记录（一次性读数/证据；实现状态以 [`docs/status.md`](../status.md) 为准）

> 状态：实验记录（2026-11）。不是设计文档；机制结论按"强证据 / 推断 / 未解释 / 部分解释"分级。
> 目的：解释「2M 实例热更时 initFix 的 `inst` 段为什么对堆遍历顺序极度敏感（~0.9 s vs ~4.9 s）」，
> 并记录实例枚举改为计数排序后的实测收益与桶窗口的选型依据。

## 1. 环境

- JDK：JBR 25，`openjdk 25.0.5.0.3-internal`（build 2026-10-24，强定义 DCEVM）。
- 机器：Linux aarch64，**KVM guest**，Neoverse-N1，2 vCPU，11 GB RAM。
  客户机内 sysfs 未暴露 L3/SLC（`/sys/devices/system/cpu/cpu0/cache` 只有 L1/L2），**末级缓存大小无法确认**。
- GC：G1（DCEVM 只支持 Serial/G1），2M 实例 `-Xms1g -Xmx3g`，4M 实例 `-Xms2g -Xmx6g`。
- `perf` 不可用：`kernel.perf_event_permited...` 实为 `perf_event_paranoid=4` 且无 root，
  因此 dTLB/LLC 计数器、指令级剖析都拿不到；下述机制结论均为对照实验推断。
- 测量：Remold `-Dremold.perfLog=true` 的 `[Lib] getInstances` 分项与
  `[InitFix] applyPatch ... [collect, ..., inst, post]`；另用 `/proc/<pid>/task/*/stat`
  按线程名采样 utime/stime/minflt。

## 2. 关键观测

### 2.1 `inst` 对访问顺序极度敏感（2M）

| 版本 | 首批 inst | 稳态 inst |
|---|---|---|
| 无排序（按 JVMTI 返回顺序） | 4.7–4.9 s | 3.0–3.4 s |
| 桶/计数排序（本次实现） | 0.82–0.94 s | 0.46–0.63 s |

### 2.2 对照实验：同一慢配置，只改「写入值类型 / GC / 堆参数」

（2M，G1 默认，无排序，各 3 次；null 组名 1 个字段、常量/int 组 2 个字段，按字段归一约 **14×**）

| 写入 | inst | 说明 |
|---|---|---|
| `null` | **168 / 181 / 166 ms** | 同一条引用 CAS 循环，但新值为 null |
| 共享常量 String | **4737 / 4875 / 4809 ms** | 新值为 interned 常量（全部实例同一个对象） |
| `int` | 356 / 465 / 389 ms | 无引用屏障 |

其它矩阵（无排序、引用常量，2M，首批 inst）：

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

- `-Xlog:gc*` 时间戳：`inst` 窗口内 **0 次 GC 暂停**（交换前 Heap Inspection full GC 在窗口之前）。
- 窗口内线程 CPU（采样）：`HotSwap-Schedul` 2030 ms user / 0 sys / 63 minflt；
  4 个压测 worker 各约 2 s（压测设计为自旋线程）；**`G1 Refine#0` 10 ms、`G1 Conc#0` 0 ms**。
- `gc+refine` 实时计数：窗口内 pending 脏卡仅 **+9216**（= 36 × `G1UpdateBufferSize`=256），
  `wanted=0`；`dirtied: 10455` 来自启动期 GC(0)，与补丁循环无关。
- 结论：**不是** GC 暂停、refinement 线程抢核、页缺页/内核路径；CPU 时间花在补丁线程自身的用户态指令。

## 3. 证据分级

### 3.1 强证据：非 null 引用写的 post-barrier 路径是主因

同一 CAS 循环、同一乱序数组、同一堆状态下，把写入值换成 `null` 或把字段换成 `int`，`inst` 从 4.7–4.9 s
降到 0.17–0.47 s；refinement 线程空闲、暂停为零、缺页为零。能同时解释这三组对照的只有
「非 null 引用写的写屏障路径」。

### 3.2 推断：`dmb` 逐元素串行化 miss（KVM stage-2 放大）

aarch64 G1 post barrier 在「是否已脏」判断之前有 StoreLoad 栅栏，故即使卡已脏，每次写仍付一次
`dmb`；栅栏之后的随机 miss 无法相互重叠。桶排序/顺序访问、SerialGC（卡标记为普通 store）、
32 MB region（同 region 过滤命中率更高）都指向该机制。**未直接测量**（perf 不可用），
且 KVM 嵌套页表使单次 miss 更贵，量级缺口只能这样解释。

### 3.3 未解释：`Xms3g` + 预 GC 仍快

`-Xlog:gc+heap=debug` 确认交换前 2M 实例已在 **old**（`young 0`、`Old regions 33`、region 2 MB），
但 `inst` 仍只有 669/295 ms。该行与「old gen 走屏障慢路径」的朴素模型冲突，目前无机制解释，
不计入 §3.2 的证据。

### 3.4 部分解释：`collect` 随 tag 数增长

JDK 25 `JvmtiTagMap::get_objects_with_tags` 使用 `TagObjectCollector`：
对**每个已打标对象**线性扫描传入的 tag 列表（`for (int i = 0; i < _tag_count; i++)`）。
但实测 2M 下 collect ≈ 1.1 / 1.32 / 1.51 / 1.60 s（1 / 4 / 8 / 16 桶），
每 tag 增量约 70 / 48 / 11 ms，递减；线性扫描只解释一部分，其余增长来源未定位。

## 4. 实例枚举改成计数排序（本分支的改动）

原实现（`ddbf34e`）是「打桶 tag → GetObjectsWithTags → malloc 排序数组 → 二次回填」。
本分支改为：打 tag 时 `tag = base + (visit_seq >> 17)` 并统计每桶数量；一次查询取回句柄与桶号；
**按桶前缀计数直接落位**到结果数组——结果数组天然按堆遍历顺序（≈ region 顺序）排列，
省掉 16 MB 中间数组和一次搬运。

2M（n=6，交替执行，p50 [min–max]）：

| 指标 | 旧桶排序 | 计数排序 |
|---|---|---|
| 枚举 total | 4229 [3976–4344] ms | 3693 [3478–3877] ms |
| fill | 1263 ms | 755 ms |
| collect | 1561 ms | 1600 ms |
| 首批 applyPatch | 5180 [5039–5318] ms | 4638 [4434–4880] ms |
| 稳态 applyPatch | 4536 [4480–4576] ms | 3977 [3794–4354] ms |
| 首批 / 稳态 inst | 883 / 530 ms | 894 / 545 ms |

### 桶窗口：应按每桶大小，而不是固定桶数

固定 16 桶时，4M 实例会让末桶堆到约 200 万对象（≈ 64–96 MB），`inst` 回升到 3721 ms；
改为「窗口固定 2^17 实例（≈ 4–6 MB）、桶数 = ceil(N/2^17)、上限 1024」后：

| 实例数 | 固定 16 桶 | 固定 8 桶 | 固定 4 桶 | **窗口固定（本实现）** |
|---|---|---|---|---|
| 2M inst 首批 | 894 ms | 916 ms | 896 ms | **842 ms** |
| 2M inst 稳态 | 545 ms | 549 ms | 575 ms | 574 ms |
| 4M inst 首批 | 5653 ms | 6058 ms | 7242 ms | **1621 ms**（桶数 32） |
| 4M inst 稳态 | 3721 ms | 3946 ms | 4366 ms | **1052 ms** |
| 4M 枚举 total | 7706 ms | 7370 ms | 7240 ms | 8232 ms（collect 3653，32 tags） |

结论：2M 时 4/8/16 桶差异在噪声内（区间重叠），但 4M 时固定桶数末桶溢出会显著回退；
**按窗口大小分桶**在 2M/4M 都稳定，代价是 collect 随桶数（tag 数）增长。

## 5. 复现

```bash
# 构建（.so 本地 g++ 仅供实验；提交版由 CI 用 zig 以 glibc 2.17 目标重编）
g++ -shared -fPIC -std=c++2b -O3 -I core/cpp/jni_posix \
    -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" \
    core/cpp/tool.cpp -o core/libs/libtoolaarch64.so
./gradlew :core:jar
# 2M 压测：/tmp/remold-test/remold-bench.sh（GEN=gen_player_2m MAIN_PATCH=patch-main-2m -Xms1g -Xmx3g）
# 4M 压测：MAIN_PATCH=patch-main-4m -Xms2g -Xmx6g
```

## 6. 限制

- 单机 KVM guest（Neoverse-N1、2 vCPU）的绝对值不可外推；末级缓存大小未知。
- `perf` 不可用，`dmb`/miss 机制是推断而非计数验证。
- null/常量对照的字段数不同（1 vs 2），文中已按字段归一为约 14×。
