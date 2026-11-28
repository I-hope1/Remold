# 既有静态字段重写的两阶段 A/B 探针（static-rewrite-probe）

回答两个问题：

1. **重写既有静态字段（`@RemoldReinit` / 重放写任务）时，单阶段（redefine → patch）与两阶段
   （Stage A → patch → Stage C）对已编译读者的可见性有差别吗？**
2. **没有增强重定义能力（`-XX:+AllowEnhancedClassRedefinition`）时，既有 `static final` 字段的
   重写必须逐字段失败关闭吗？**

背景：补丁写内存不会通知 JIT 退优化。单阶段下，窗口期内重编译的读者可能把旧值提升进机器码，
patch 之后一直读到旧值；两阶段的阶段 C 会再 redefine 一次宿主类，把这类读者退优化。探针用
“运行时初始化的 `static final String` 引用字段”（无 `ConstantValue` 属性）验证这一点。同一类
读者折叠也意味着：普通 JVM 上重定义宿主只失效宿主自身的已编译方法，跨类读者仍读旧值，所以
`InitFix` 在没有增强重定义时必须把既有 `static final` 重写判为 REJECTED（探针的 `gate` 模式）。

## 运行

```bash
bash tests/static-rewrite-probe/build.sh
bash tests/static-rewrite-probe/run.sh
grep '^OBS' tests/static-rewrite-probe/b/logs/all.log
```

环境变量：`JBR21_HOME`（默认 `F:/files/java/jdks/jbrsdk_jcef-21.0.9`）。构建依赖
`core/build/libs/remold-agent.jar`（提供 `StageASkeleton` 与 `remold.ref.InitFix`；先跑
`./gradlew :core:shadowJar`）。JetBrains Runtime 发行版不带 `jar`/`javap`，打包探针 agent 时
用 `JAR_TOOL=<jdk>/bin/jar` 指定一个带完整 JDK 工具链的 JDK（`javap` 会从同一目录自动取）。

## 场景

| 模式 | 顺序 |
|:--|:--|
| `single` | 预热读者 → `redefineClasses(v2)` → 再预热（窗口期重编译，读旧值）→ Unsafe 写 `VAL="NEW"` → 采样 |
| `two` | 预热读者 → `redefineClasses(stageA)`（新结构 + 旧方法体）→ 再预热 → Unsafe 写 `VAL="NEW"` → `redefineClasses(v2)`（阶段 C）→ 采样 |
| `gate` | 用 v2 字节定义 `test.GateHolder` → 把 `MSG` 置回旧运行期值 → `InitFix.transform`（真实生产路径）→ 读 `PatchReport` → `afterRedefine` → 采样 `MSG` |

读者：`reader.Reader.read()` 直接 `GETSTATIC Holder.VAL`；`Reader.tag()` 调用宿主方法（可被内联）。
场外还检查反射直读与 `Holder.tag()` 的新旧方法体切换。

## 读数（JBR 21.0.9，一次运行的代表值）

| 观测 | `single` | `two` |
|:--|:--|:--|
| 阶段 A/首次 redefine 后 `tag()` | `v2`（新体立即可见） | `v1`（阶段 A 保留旧体） |
| 窗口期热读者 `read()` | `OLD` | `OLD` |
| Unsafe patch 后 `rawVal` | `NEW` | `NEW` |
| patch 后热读者 `read()`（5 次采样） | **`OLD`（stale）** | **`NEW`** |
| patch 后反射直读 | `OLD` | `NEW` |

即：单阶段在 patch 后没有第二次 redefine，已编译读者保持旧值；两阶段的阶段 C 作为 **补丁后的
deopt 屏障**，让读者重新编译并读到新值。结论支撑把“既有静态字段重写”纳入两阶段触发判据
（`InitFix.requiresReadThroughGuard`）。

## `gate` 读数（同一构建、同一 JBR 21.0.8，仅差 `-XX:+AllowEnhancedClassRedefinition`）

夹具：`test.GateHolder` 的 v1/v2 都是运行期初始化的 `static final StringBuilder MSG`
（无 `ConstantValue`），v2 用 `@RemoldReinit(mode = OVERWRITE)` 把初值从 `new StringBuilder("old")`
改成 `new StringBuilder("new")`。`transform` 前把静态字段置回 `stale`，作为“存量静态环境”的前置基线。

| 观测 | 普通 JBR（无 flag） | JBR + `-XX:+AllowEnhancedClassRedefinition` |
|:--|:--|:--|
| `isEnhancedHotswapEnabled()` | `false` | `true` |
| `PatchReport.staticFields().MSG.status()` | **`REJECTED`** | **`ACCEPTED`** |
| 拒绝原因 / 告警点名 flag | `rewriting an existing static final field requires ... -XX:+AllowEnhancedClassRedefinition ...` | — |
| `patchGenerated()` | `false`（无补丁任务） | `true` |
| `afterRedefine` 后 `MSG` | `stale`（未写入） | `new`（已改写） |

JBR 25.0.4.1（`ext25` 构建）同读数：plain `REJECTED` / `patchGenerated=false` / `afterApply=stale`；
enhanced `ACCEPTED` / `patchGenerated=true` / `afterApply=new`；`single`/`two` 的 A-B 读数与 21 一致
（`single` patch 后热读者 `OLD`，`two` 阶段 C 后 `NEW`）。

探针在断言不成立时抛异常并以非零码退出；`run.sh` 的 `run_gate` 会把非零码当失败并中止。

## 边界

- 只覆盖宿主**自身**静态字段的重写（`@RemoldReinit` / 重放写任务）；A 档 apply 重放写入的是
  **另一个类型**的字段，宿主阶段 C 的 deopt 覆盖不到那个类型，故不纳入同一判据。
- `static final` **编译期字面量常量**（`ConstantValue`）的读者已把字面量内联进 class 文件，
  deopt 无法刷新，不纳入（`@RemoldReinit` Javadoc 的“static final 编译期常量对批外读者无效”）。
- 只覆盖 JBR 21.0.9 + C2；其他 JVM / Graal 未测。
