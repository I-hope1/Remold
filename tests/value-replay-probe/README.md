# 值类型 `new` 初值重放 E2E 探针（value-replay-probe）

本探针用**真实 JVM + 真实 `Instrumentation.redefineClasses` + 生产 `InitFix.transform/afterRedefine`
链路**验证 `docs/initfix/14-value-type-new-replay.md` 的运行时行为，回答四个问题：

1. 宿主既有实例字段的初值从 `new Vec(4,4)` 改成 `new Vec(4,12)` 后，**已 JIT 编译的读者**能否在
   存量实例上看到新值？两个存量实例是否各拿到独立的新对象（W3 不共享身份）？
2. 运行期被业务改过的值（`Unsafe` 替换）是否被 W2 保留（skipped）、报告为
   `NOTHING_TO_PATCH` + `skipped:`，且**不**写 applied 记录（下一轮仍会重新规划）？
3. 开关默认关闭时行为是否与旧版一致（属性关闭 → 无任务、无写入 = 负基线）？
4. `static final` 目标在 enhanced JBR 上是否按两阶段（阶段 A → 补丁 → 阶段 C）让已编译读者可见，
   在普通 JBR（无 `-XX:+AllowEnhancedClassRedefinition`）上是否按 A4 拒绝（
   `VALUE-REPLAY-STATIC-FINAL-NEEDS-ENHANCED`，不写入）？

## 运行

前置：先构建 agent jar（提供 `remold.ref.InitFix` / `StageASkeleton`）。

```bash
# 1) agent jar（仓库根目录）
./gradlew :core:shadowJar          # 或本沙箱里带 GRADLE_USER_HOME/--offline 的那条命令

# 2) 夹具 + 探针 agent
JAR_TOOL=/home/ubuntu/.jdks/ms-25.0.4.1/bin/jar bash tests/value-replay-probe/build.sh

# 3) 两套 JBR 各跑 5 个场景（每个场景一个 JVM）
bash tests/value-replay-probe/run.sh
grep '^OBS' tests/value-replay-probe/b/logs/all.log
```

环境变量：

| 变量 | 默认 | 说明 |
|:--|:--|:--|
| `JBR_HOME` | `/opt/idea/jbr`（JBR-21.0.8） | 构建用 javac + 第一套运行 JDK；需支持 `-XX:+AllowEnhancedClassRedefinition` |
| `JBR25_HOME` | `/home/ubuntu/deopt-study/jbr/ext25`（JBR-25.0.4.1） | 第二套运行 JDK；目录缺失时 run.sh 打印 SKIP 但不失败 |
| `JAR_TOOL` | 自动探测（`$JBR_HOME/bin/jar` → `/home/ubuntu/.jdks/ms-25.0.4.1/bin/jar` → PATH） | JBR SDK **不带 `jar`/`javap`**，打包探针 agent 必须借完整 JDK 的工具链 |

`run.sh` 对每套 JDK 执行：`instance`、`skip`、`off`（普通 JVM，属性开/关）与
`static`（enhanced）、`static-plain`（普通 JVM）。任一场景断言失败 → 进程非零退出 → run.sh 失败。

## 夹具

| 文件 | 内容 |
|:--|:--|
| `src/v1/probe/Vec.java` | `public record Vec(int x, int y)`（v1/v2 共用，字节不变） |
| `src/v1/probe/Host.java` | `public final Vec v = new Vec(4, 4);` + `v()` / `stamp()` |
| `src/v2/probe/Host.java` | 同类同名，初值改成 `new Vec(4, 12)` |
| `src/staticv1/probe/StaticHost.java` | `public static final Vec SV = new Vec(4, 4);`（运行期初始化，无 `ConstantValue`） |
| `src/staticv2/probe/StaticHost.java` | `SV = new Vec(4, 12)` |
| `src/reader/probe/Reader.java` | 热读者：`read(Host)` 读 `Host.v.y()`；`readStatic()` 读 `StaticHost.SV.y()` |

v2 改的是构造参数第二个实参，所以 `y` 是必须可见变化的组件（`x` 保持 4 作为"未改组件"对照）。
运行时 classpath 只放 v1 产物（`b/v1`、`b/staticv1`），v2 只以**文件字节**喂给 `ClassDiffUtil.diff`
与 `InitFix.transform`，随后真 `redefineClasses`。

## 场景

| 模式 | 属性 | enhanced | 顺序 / 断言要点 |
|:--|:--:|:--:|:--|
| `instance` | on | 否 | v1 定义宿主 → 造 2 个实例 → 预热读者到 C2 → `transform(v1→v2 差分)` → `redefineClasses(v2)` → `afterRedefine`；两实例都变 `Vec(4,12)` 且互不相同的对象；读者读 12；`PatchReport.valueReplayFields.v=ACCEPTED`；`InitFixStats.applied=1`；第二轮同差分被 applied 记录挡下（不重放） |
| `skip` | on | 否 | 先 `Unsafe` 把实例的 `v` 换成 `Vec(9,9)` 再打补丁；W2 保留原引用（`skipped`）；报告 `NOTHING_TO_PATCH` + `skipped:` 原因；`stats.skipped=1`/`applied=0`；第二轮**仍重新规划**（skipped 不记 applied）且仍保留运行期值 |
| `off` | **off** | 否 | 同一链路但属性关闭：无 value-replay 决策、无 pending 补丁、`redefine/afterRedefine` 后实例仍是原 `Vec(4,4)`、读者仍 4。这是**负基线**：若通道无视开关常开，本例必红 |
| `static` | on | **是** | 生产两阶段顺序：`transform` → `StageASkeleton.buildWithLiveConstructors(v1,v2)` → `redefineClasses(stageA)` → 窗口期读者仍 4 → `afterRedefine`（写入 `Vec(4,12)`）→ `redefineClasses(v2)`（阶段 C）；阶段 C 前已编译读者 stale=4，阶段 C 后读者=12；`stats.applied=1` |
| `static-plain` | on | 否 | 普通 JBR：A4 拒绝 `VALUE-REPLAY-STATIC-FINAL-NEEDS-ENHANCED`（同时进 `valueReplayFields` 与统一 warn 出口）、无待补补丁、`redefine` 后静态值仍是原 `Vec(4,4)`、读者仍 4；`stats.rejected=1` |

两阶段顺序说明：生产链路是 `transform`（分析）→ 阶段 A redefine → 阶段 B 补丁（`afterRedefine`）
→ 阶段 C redefine（见 `DynamicRedefineExecutor` / `TwoPhaseCoordinator`）。探针完全按该顺序复刻，
`InitFix.requiresTwoPhase(host)` 必须为 `true`（静态值类型重放已接入 read-through 门）。

## 读数（本机实测，一次运行的代表值）

两套 JBR 的 5 个场景全部退出码 0；OBS 读数如下（`jbr21`=`/opt/idea/jbr` 21.0.8，
`jbr25`=`/home/ubuntu/deopt-study/jbr/ext25` 25.0.4.1）。

### `instance`（两个存量实例）

| 观测 | jbr21 | jbr25 |
|:--|:--|:--|
| `baseline.h1.v` / `baseline.h2.v` | `Vec[4,4]` / `Vec[4,4]` | 同 |
| `baseline.reader.h1/h2`（C2 后） | 4 / 4 | 4 / 4 |
| `window.reader`（redefine 后、补丁前） | 4 | 4 |
| `afterPatch.h1.v` / `h2.v` | `Vec[4,12]` / `Vec[4,12]` | 同 |
| 两个新值身份 | **不同对象**（`new1 != new2`，且都 `!=` 旧值） | 同 |
| `afterPatch.reader.h1/h2`（C2 读者） | 12 / 12（5 次采样均 12） | 12 / 12 |
| `PatchReport.valueReplayFields.v` | `ACCEPTED` | `ACCEPTED` |
| `InitFixStats.applied/skipped/failed/rejected` | 1 / 0 / 0 / 0 | 1 / 0 / 0 / 0 |
| 第二轮同差分 `valueReplayFields` | `{}`（applied 记录挡下） | `{}` |

### `skip`（运行期改值）

| 观测 | jbr21 | jbr25 |
|:--|:--|:--|
| `baseline.v` / `baseline.reader` | `Vec[9,9]` / 9 | 同 |
| `afterPatch.v` | `Vec[9,9]`（同一引用，W2 保留） | 同 |
| `report.reason` | `skipped: every live instance no longer equals the rebuilt old initializer...` | 同 |
| `PatchReport.valueReplayFields.v` | `NOTHING_TO_PATCH` | 同 |
| `stats.applied/skipped/failed/rejected` | 0 / 1 / 0 / 0 | 同 |
| 第二轮 `valueReplayFields.v` | `ACCEPTED`（skipped 不记 applied） | 同 |
| 第二轮后 `v` | 仍 `Vec[9,9]` | 同 |

### `off`（属性关闭负基线）

| 观测 | jbr21 | jbr25 |
|:--|:--|:--|
| `plan.valueReplay` | `{}` | `{}` |
| `pendingPlan` | `null` | `null` |
| `afterPatch.v` / `afterPatch.reader` | `Vec[4,4]` / 4 | 同 |
| 四个 `InitFixStats` 计数 | 0 / 0 / 0 / 0 | 同 |

### `static`（enhanced 两阶段，静态 final）

| 观测 | jbr21 | jbr25 |
|:--|:--|:--|
| `enhancedHotswap` | `true` | `true` |
| `baseline.SV` / `baseline.reader`（C2 后） | `Vec[4,4]` / 4 | 同 |
| `plan.twoPhaseRequired` | **`true`** | **`true`** |
| `window.reader`（阶段 A 后、补丁前） | 4 | 4 |
| `afterPatch.SV` | `Vec[4,12]` | `Vec[4,12]` |
| `afterPatch.readerBeforeStageC` | **4（stale，证明没有阶段 C 时读者不刷新）** | **4（stale）** |
| `afterStageC.reader` | **12**（5 次采样均 12） | **12** |
| `stats.applied/skipped/failed/rejected` | 1 / 0 / 0 / 0 | 1 / 0 / 0 / 0 |

### `static-plain`（A4 拒绝）

| 观测 | jbr21 | jbr25 |
|:--|:--|:--|
| `enhancedHotswap` | `false` | `false` |
| `plan.reason` | `VALUE-REPLAY-STATIC-FINAL-NEEDS-ENHANCED: static final reference target requires enhanced hotswap` | 同 |
| `PatchReport.valueReplayFields.SV` | `REJECTED` | `REJECTED` |
| `plan.warnVisible`（统一 warn 出口） | `true` | `true` |
| `pendingPlan` | `null` | `null` |
| `afterPatch.SV` / `afterPatch.reader` | `Vec[4,4]` / 4（未写入） | 同 |
| `stats.rejected` | 1 | 1 |

## JIT 证据（unified JIT log / PrintCompilation 同源行）

每个 JVM 以 `-Xlog:jit+compilation=debug:file=b/logs/jit-<label>.log` 启动（这条日志的
方法与层级行与 `-XX:+PrintCompilation` 同源；单独落文件是为了不与 `OBS` stdout 交错，保证
证据行可 grep）。探针在写入**之前**轮询该日志，直到目标读者出现 **C2（tier 4）** 编译行
（30s 超时即失败），并把该行原文写进 `OBS <mode>.warm.readerCompile.c1/c2`；`run.sh` 再把
每个 JVM 的读者编译行以 `JIT-EVIDENCE <label>:` 抄进运行日志。代表读数：

```
jbr21 instance: c1 = 594  2  probe.Reader::read (8 bytes)   c2 = 595  4  probe.Reader::read (8 bytes)
jbr21 static  : c1 = 587  2  probe.Reader::readStatic (7 bytes)  c2 = 588  4  probe.Reader::readStatic (7 bytes)
jbr25 instance: c1 = 445  2  probe.Reader::read (8 bytes)   c2 = 446  4  probe.Reader::read (8 bytes)
jbr25 static  : c1 = 427  2  probe.Reader::readStatic (7 bytes)  c2 = 428  4  probe.Reader::readStatic (7 bytes)
```

`run.sh` 还对每次运行的 stdout 做**顺序校验**：`warm.readerCompile.c2`（携带 JIT 日志原文）
必须出现在 `OBS <mode>.redefine = ok`（static 为 `OBS static.redefineStageA = ok`）**之前**，
否则判失败。这样"读者已经 C2 编译"由 JVM 自己的 JIT 日志直接证明，"先编译后写入"由探针的
轮询顺序保证，而不是"预热若干次后假定已编译"。

## 因果对照与防假阳性

- **去触发对照**：`off` 场景同一条 `transform → redefine → afterRedefine` 链路，只把开关关掉，
  实例/读者必须保持旧值 —— 证明 `instance` 的 12 不是 redefine 或夹具自身造成的。
- **换无关输入对照**：`skip` 场景把值换成 `Vec(9,9)`，W2 必须保留且报告 skipped —— 证明写入
  不是无条件覆盖。
- **半程基线**：`instance.window.reader` / `static.window.reader` 在补丁前必须读到旧值；`static`
  场景还额外观测到阶段 C 前已编译读者 stale=4、阶段 C 后=12，把"阶段 C 是 deopt 屏障"变成
  可观测的 A/B 读数。
- **负基线**：`static-plain` 证明非 enhanced 运行时 A4 失败关闭（拒绝 + 不写）。

## 边界

- 只覆盖 JBR 21.0.8 与 JBR 25.0.4.1（两套 enhanced 构建）；未覆盖 stock HotSpot / Graal / DCEVM。
- 每个场景独立 JVM，不跨场景共享已 redefine 的类。
- `static` 场景在 `transform` 前把 v2 字节放进 `RemoldAgent.bytecodeCache`（与生产"新字节在缓存"
  同口径）；实例/静态目标都是普通类（非 record 宿主、非 hidden 类）。
- 探针不修改 core；若读数与规格不符，按"核心缺陷上报"处理，不在探针里绕过。
