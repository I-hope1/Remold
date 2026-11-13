# 既有静态字段重写的两阶段 A/B 探针（static-rewrite-probe）

回答一个问题：**重写既有静态字段（`@RemoldReinit` / 重放写任务）时，单阶段（redefine → patch）
与两阶段（Stage A → patch → Stage C）对已编译读者的可见性有差别吗？**

背景：补丁写内存不会通知 JIT 退优化。单阶段下，窗口期内重编译的读者可能把旧值提升进机器码，
patch 之后一直读到旧值；两阶段的阶段 C 会再 redefine 一次宿主类，把这类读者退优化。探针用
“运行时初始化的 `static final String` 引用字段”（无 `ConstantValue` 属性）验证这一点。

## 运行

```bash
bash tests/static-rewrite-probe/build.sh
bash tests/static-rewrite-probe/run.sh
grep '^OBS' tests/static-rewrite-probe/b/logs/all.log
```

环境变量：`JBR21_HOME`（默认 `F:/files/java/jdks/jbrsdk_jcef-21.0.9`）。构建依赖
`core/build/libs/remold-agent.jar`（提供 `StageASkeleton`，用于合成阶段 A 字节码；先跑
`./gradlew :core:shadowJar`）。

## 场景

| 模式 | 顺序 |
|:--|:--|
| `single` | 预热读者 → `redefineClasses(v2)` → 再预热（窗口期重编译，读旧值）→ Unsafe 写 `VAL="NEW"` → 采样 |
| `two` | 预热读者 → `redefineClasses(stageA)`（新结构 + 旧方法体）→ 再预热 → Unsafe 写 `VAL="NEW"` → `redefineClasses(v2)`（阶段 C）→ 采样 |

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

## 边界

- 只覆盖宿主**自身**静态字段的重写（`@RemoldReinit` / 重放写任务）；A 档 apply 重放写入的是
  **另一个类型**的字段，宿主阶段 C 的 deopt 覆盖不到那个类型，故不纳入同一判据。
- `static final` **编译期字面量常量**（`ConstantValue`）的读者已把字面量内联进 class 文件，
  deopt 无法刷新，不纳入（`@RemoldReinit` Javadoc 的“static final 编译期常量对批外读者无效”）。
- 只覆盖 JBR 21.0.9 + C2；其他 JVM / Graal 未测。
