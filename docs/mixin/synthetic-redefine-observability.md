# 合成类重定义的观测契约（基线 / 过滤口径 / 字段表拆分 / 影子 Class）

本文只定义**可审计的观测契约**：哪些数字必须可点名、键结构必须带什么维度、字段表变化必须怎么分类、
产品侧哪些缓存会跨轮次持有 `Class`。实测读数与合流证据不写在这里（见 `docs/status.md` 与战报）。

## 1. 三个分母与两个可见口径

`MixinClassBaseline`（`plugin-mixin/src/remold/mixin/MixinClassBaseline.java`）是 premain 期的
`defineClass` 录口。战报只看 `seen=… recorded=… missed=…` 三个数，容易读串，因此每个数都必须能落到
可核对的明细上：

| 口径 | 计数事件 | 明细来源 |
|:--|:--|:--|
| `seen` | 录口能键控的 `defineClass` 提交（已排除 bootstrap、hidden、agent 自身 `remold/`） | —— |
| `recorded` | 真正落账的 `(加载器身份, 点分名)` 键数（只留候选） | —— |
| `missed` | 基线**查找**未命中次数（闸 4 拒绝一次计一次） | `missDetailsSummary()`：类全称 + 加载器标签 + 原因 |
| 过滤数 | `seen` 中被候选过滤的提交 | `filterSummary()`：规则 + 计数 + 示例内部名 |
| 候选提交数 | 进入抄字节分支的提交 | `recordAttempts()`：`attempts - recorded` = 同名重复提交数 |

**恒等式**：`seen == filtered + attempts`（每次提交只走两条路之一）；
`seen - recorded == filtered + (attempts - recorded)`，所以 `seen - recorded` **不是**"录口失败数"。

**过滤规则**（`filterRule()`）：内部名不含 `$` 的 `defineClass` 提交不落账——只有合成/匿名/内部类
（`<$Target>$Anonymous$<hash>`、`$1`、`$Inner`）会被 `redefineSyntheticSafely` 问到，全量抄整个依赖树
只会白占内存。示例类名随摘要一起输出，规则因此可以拿真实输入自证。

**未命中原因**只有两种机械判据（`recordMiss`）：

- `recorded under N other loader(s); this loader never defined it` —— 该名字在别的加载器名下录到过：
  基线属于另一个加载器，本加载器的查询必须未命中（这正是"同名不同加载器不互相冒充"的正向证据）；
- `no defineClass submission for this name on any loader` —— 任何加载器都没录到：类在 agent 注册前
  就已定义，或录口未安装。

点名清单只存字符串（类名 + `加载器类名@身份哈希`），**不持 `ClassLoader` 强引用**：观测不能反过来
延长加载器寿命。加载器标签的身份哈希与 `BASELINES` / `APPLIED` 两张表的弱键语义同源。

## 2. 两张基线表的键结构

| 表 | 键 | 值 | 语义 |
|:--|:--|:--|:--|
| `MixinClassBaseline.BASELINES`（R0） | `(ClassLoader 实例身份, 点分名)` | `byte[]` 定义字节副本 | premain 录口看到、JVM 最终定义的那份 |
| `MixinClassBaseline.APPLIED`（R1+） | `(ClassLoader 实例身份, 点分名)` | `byte[]` 已生效字节副本 | **成功** `redefineClasses` 之后的字节；失败不落账 |

两张表都共享同一套加载器身份语义：`WeakHashMap<ClassLoader, Map<String, byte[]>>`，加载器被回收时
它名下的基线一起消失。闸 4 的解析顺序是 **APPLIED 优先 → BASELINES**，因为成功 redefine 之后 JVM 里的
那份是 R1，录口里仍是 R0，否则第二次热更会拿自己的第一版字节当基线。

`bootstrap`（`loader == null`）在有上永远视为未命中，也不落 `APPLIED`：闸 4 对 bootstrap 没有可用的
基线判据（合成类也不会来自 bootstrap）。

`remold.RemoldAgent#bytecodeCache`（`String → byte[]`，只有点分名一条索引）**不是**基线来源，只在
`isUnchanged` 的"无实质变更短路"里当启发式兜底：它证明不了"这份字节属于这个加载器"。

## 3. 字段表变化的四类标签（逐次拆分口径）

合成类每次重定义相对**运行期已加载那一版**的字段表变化必须自报一行，否则只能从
`retained N tombstone field(s)` / `renamed N same-name field(s)` 反推，而这两行都不出现时无法区分
"追加新字段"与"字段表原样不变"：

```
[Mixin] synthetic inner class <name> field table vs loaded: <KIND>(appended=…, renamed=…, tombstone=…) (schemaHash=…)
```

`KIND` 由 `MixinSyntheticSchema.describeFieldTableChange` 给出，优先级固定（只影响单值标签，三项计数
都在括号里可复核）：

| KIND | 判据 | 含义 |
|:--|:--|:--|
| `TYPE_CHANGE` | `renamed > 0` | 同名异型捕获字段被重命名追加（基线槽位不动） |
| `WOULD_SHRINK` | `tombstone > 0` | 基线字段只能靠墓碑补齐，否则字段表缩容 |
| `APPEND_ONLY` | `appended > 0` | 只追加新字段（字段表增长） |
| `UNCHANGED` | 三者皆 0 | 字段表与已加载版本一致 |

`appended` 必须在 `padFields` **之前**取：补齐会给候选追加墓碑字段，之后就分不清"新字段"与"补墓碑"。

## 4. 影子 Class：跨轮次持有 `Class` 的缓存清单

JBR/DCEVM 的 `redefineClasses` 会把**静态字段里持有的 `Class` 引用**改写成旧版本的"影子"对象（同名、
同加载器、旧字段元数据）；堆实例字段、数组元素与 map/set 键不被改写。影子对象交给 JVMTI 查询
（`LibTool.getInstances`）会返回 0，交给 `retransformClasses` 会静默无效。因此任何跨轮次持有
`Class` 的缓存都必须在使用处按 `(类名, 加载器)` 重新取，或经 `ClassIdentity.canonicalize` 归一。

产品侧清单（值为 `Class<?>` / `Field` 的跨轮次缓存）：

| 位置 | 缓存 | 是否按 (loader,name) 重取 |
|:--|:--|:--|
| `core/src/remold/RemoldAgent.java:1789` | `loadedClassesMap`（`String → Class<?>`） | **否**，但整表按 JVM 全量快照重取：每批 `loadClassSnap`（`1884`-`1899`，调用点 `1933`）从 `Instrumentation.getAllLoadedClasses()` 重建并原子切换；键无加载器维度，同名多副本由 `isBetterLoadedClassCandidate` 选权威副本，来源 rank/描述记在 `CACHE_LOADER_RANK`（`1792`）/`CACHE_LOADER_NAME`（`1793`） |
| `core/src/remold/ref/HeapFieldWatch.java:92` | `resolved`（`volatile Class<?>`，解析后跨切面复用） | **是**：每次读数经 `currentClassVersion`（`354`-`366`）按 `(类名, 加载器)` 重新取；命中影子时就地刷新 `resolved` 并 warn `[HEAPWATCH] cached Class … was a stale shadow` |
| `core/src/remold/util/ClassIdentity.java:23`-`39` | 归一入口（非缓存） | 发现 `canonical != clazz` 打 `[SHADOW-CLASS]`；被 `LibTool.getInstances`（`core/src/remold/util/LibTool.java:140`）在每次查询前调用 |
| `core/src/remold/profiler/EntityKeyExtractor.java:22` | `fieldCache`（`Class<?> → FieldBundle`，含旧版本 `Field` 反射对象） | **否**：无驱逐；只在 profiler 启用时接线（非结构化热更宿主会整体跳过） |
| `core/src/remold/profiler/ProfilerTransformer.java:14` | `targetMethods`（`Class<?> → Set<String>`） | **是**：入表/查表前统一走 `ClassIdentity.canonicalize`（`17`-`42`） |
| `core/src/remold/ref/InitFix.java:185`/`193`/`208`/`228` | `PENDING`/`REPORTS`/`LEDGER`/`REINIT_APPLIED`（`Class` 键） | 入口处对宿主 `ClassIdentity.canonicalize`（`3612`、`4031`）；`PENDING` 按不变量用弱键 |
| `core/src/remold/ref/HotswapBridge.java:251`/`259`/`345` | 三张 `Class → MethodHandle` putter 表 | 键是**字段类型**（`int`/`long`/`Object` 等结构稳定类），不是宿主类 |

`RemoldAgent` 内以 `Field` 为值的跨轮次缓存：**0 个**（`OffsetScanner.Binding` 等只持字符串与枚举）。

真机判定口径：`[SHADOW-CLASS]` 与 `[HEAPWATCH] cached Class` 两条 warn 是本节的观测锚点——出现即说明
某处确有影子引用被归一；两条都没有时只能说"本轮未观测到影子命中"，不能推断"影子不可能出现"。
