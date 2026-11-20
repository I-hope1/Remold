# 活体对齐的 Raw ↔ Raw 输入与 Eff_prev 继承契约

> **文档类型**：设计规格（只写规格/契约；当前实现状态与验收证据以 [`docs/status.md`](../status.md) 为准）

> 本文件定义 `LambdaAligner.align` 在**活体热更路径**上的输入选择契约：什么时候可以把"上一代
> 待对齐产物（`R_prev`）"而不是"上一代进 JVM 的产物（`E_prev`）"送给对齐器，以及在这种情况下
> 幽灵桩、保留体、捕获桥从哪里继承。对齐算法本身的配对判据、捕获桥构造、孤儿桩形态见
> [`../topology/01-invariants-and-remapping.md`](../topology/01-invariants-and-remapping.md)～[`../lambda/02-orphan-body-retention.md`](../lambda/02-orphan-body-retention.md)；
> 代际元数据仓与离线回放包见 [`01-bytecode-changeset-vcs.md`](01-bytecode-changeset-vcs.md)；
> 结论发布见 [`../lambda/03-publication-contract.md`](../lambda/03-publication-contract.md)。

## 0. 术语

| 术语 | 定义 | 运行期来源 |
|:--|:--|:--|
| `R_disk` | 磁盘原始 `.class` 字节，未经任何变换 | 读盘 / `sourceBytecodeCache` 写入值 |
| `R_new` | 本轮**待对齐产物**：`R_disk` 经前置确定性变换（`forceStaticLambdas`、织入桥、访问权限单调对齐、局部类/布局门的前置改写）之后、进入 `align()` 之前的字节 | 调用方传入的 `newBytes` |
| `R_prev` | 上一代的 `R_new`（同一条变换链在上一轮的产物） | 上一代元数据记录 + 上一轮 `R_disk` 复原 |
| `E_prev` | 上一代真正喂给 `redefineClasses` 的字节 | `bytecodeCache[类]` |
| `E_new` | 本轮 `align()` 产出、随后交给 `redefineClasses` 的字节 | `align()` 返回值 |
| `Meta` | 逐代旁路元数据（三枚哈希 + 自造名 + 幽灵生前指纹 + 方法映射 + 桥数量） | `AlignmentMetaStore` head 条目 |
| 继承集 | `E_prev` 里存在、`R_prev` 里**必然不存在**的成员：幽灵桩、保留体、捕获桥 | 由 `E_prev` 字节枚举，`Meta` 校正 |

**关键区分**：`R_new ≠ R_disk`（前置变换会改字节），`E_prev ≠ R_prev`（对齐会再加自造名、幽灵桩、
保留体、捕获桥）。这三者的哈希口径不同，任何闸门都不得交叉比较（见 §3.2）。

## 1. 现状契约盘点（活体批次路径）

| 阶段 | 现行输入 | 依赖哪份字节 | 历史污染面 |
|:--|:--|:--|:--|
| 匹配（Step 1 / Pass A / Pass B / 宿主序列锚点） | `alignOld`（= `E_prev` 归一化后）与 `R_new` | `E_prev` | 自造名占位、幽灵桩、保留体、桥共同构成"旧侧方法表" |
| 自造名取证 | 参数 `inventedOldNames`（默认来自 `RemoldAgent.inventedSlots`），`META_SOURCE` 打开时改读 `Meta.inventedNames` | `Meta` 或调用方状态 | 自造名若丢失，下一轮"同名优先"会把新 lambda 绑到别人的名字上 |
| 幽灵生前指纹 | `Meta.ghostHashes`，缺失时读桩体 `LDC <long>; POP2` 标记 | `Meta` / `E_prev` 字节 | 无标记的转发桩读回 `0`，只能靠描述符兜底 |
| 孤儿判定 | `E_prev` 里全部 `renameable` 合成方法键 − 本轮 `presentKeys` | `E_prev` | 幽灵桩本身是孤儿，靠这条被"再注入一次" |
| 保留体存活重验 | `OrphanBodyRetention.rejectionReason(prev, next, 旧体, plannedKeys)` | `E_prev`（带帧重解析） | 旧体若调用了本轮已删成员必须降级为空壳 |
| 捕获桥 | `stepCaptureBridge(oldCn=E_prev, alignedCn, ...)`，桥按"老键 → 新方法最终名"重写 | `E_prev` | 上一轮的桥是"已经缩水的签名"，若与源码混看会被认成新证据 |
| 命名池 | `usedOldNames` / `oldNameDescSet`（均来自旧侧方法表）+ `existingNewNames` | `E_prev` | 自造名、幽灵名必须都在池里，否则 fresh 名会撞车 |
| 提交 | `Meta.commit(loader, 类, preAlign=R_new, alignOutput=E_new, jvmEffective, ...)` | — | 只有整批全绿才提交；`-Dremold.vcs.enabled` 关闭时整条旁路不写入 |

结论：现行为是**单跑道 `E_prev ↔ R_new`**。`Meta` 只承担"自造名与幽灵指纹的更可靠来源"，
不改变对齐输入——这一点与 [`01-bytecode-changeset-vcs.md`](01-bytecode-changeset-vcs.md) §2.5 第 3 条
"活体路径是 `Raw ↔ Raw`"的长期目标不一致，本文件即该条的活体落地规格。

## 2. 为什么 `E_prev ↔ R_new` 会失准

`E_prev` 是"为了保住老 `CallSite` 而做的全部决定"的累积结果，它比源码多出三类成员；把这些成员
当成"源码侧证据"参与配对，就在两侧引入了不对称：

1. **自造名占位**：上一轮给新 lambda 分配的避障名不是 javac 编号。它留在 `E_prev` 里，会让本轮
   同名 javac 编号的 lambda 在"同名优先"阶段被误认（静默错配）。
2. **幽灵桩与保留体**：它们把"上次删掉的方法"重新塞回旧侧方法表。配对器需要显式排除它们
   （各自的 `ghost` / 标记位），任何一次遗漏都让它们成为候选。
3. **捕获桥**：桥是"老签名（宽捕获）→ 新方法（窄捕获）"的适配器，其签名在源码里根本不存在。
   下一轮若把桥当普通老 lambda 读，就会把"上次的适配决定"错当成"这次源码的捕获表"。

失准形态都是**静默**的：不抛异常，老 `CallSite` 拿到一个语义不同的方法体。因此本规格不采用
"缩小误差"的路线，而是把两条跑道显式分开：**配对只看源码侧（`R_prev ↔ R_new`），
保命只看生效侧（`E_prev → E_new` 继承）**。

## 3. 目标契约：双跑道与准入闸门

### 3.1 两条跑道

| 跑道 | 触发条件 | 旧侧输入 | 继承来源 |
|:--|:--|:--|:--|
| **Run R**（Raw ↔ Raw） | §3.2 三道闸门全过，且灰度开关命中该类 | `R_prev` | `E_prev`（字节枚举）+ `Meta`（身份校正） |
| **Run E**（兼容跑道） | 闸门任一不满足，或灰度开关未命中 | `E_prev` | 无需独立继承（`E_prev` 自带） |

Run E 的语义必须与当前实现**逐字节等价**：这正是老契约零退化的定义（R2）。两条跑道的输出允许
不同（Run R 在污染场景下更准），但在"`E_prev` 无继承集成员"的场景下必须**逐字节相等**——这是
判定"Run R 没有额外副作用"的唯一可断言口径。

### 3.2 准入闸门（全部满足才允许走 Run R）

- **G1 代际身份**：`Meta.headMatching(loader, 内部名, hash(E_prev_used))` 非空，其中
  `E_prev_used` 是**本轮实际要用的旧侧字节**（即调用方算出的 `alignOld`）。
  哈希不符即视为"元数据不是这一代"，禁止用近邻代顶替。
- **G2 源码侧配对**：候选 `R_prev` 必须能证明与上一代的 `R_new` 同源。判据按可用证据分级：
  - 首选：`hash(R_prev) == Meta.preAlignHash`（元数据直接记录过上一代 `R_new` 指纹）；
  - 次选：`hash(normalize(R_prev)) == Meta.preAlignHash`，其中 `normalize` 是与本轮
    `R_new` 同一条确定性前置变换链；
  - 源码变更识别（IDE 回退/Ctrl+Z）**只能**用 `R_disk` 侧哈希，不得用 `preAlignHash`
    （前置变换实现漂移会让它漂移，见 §9 的 F4）。
  任一候选都判不出同源，就走 Run E——不能拿"看起来像"的字节去配对。
- **G3 继承集可枚举**：`E_prev` 字节必须可读（能解析方法表并识别幽灵桩 / 保留体标记 / 桥），
  且 `Meta` 至少提供幽灵生前指纹表。缺任一项即整类退回 Run E。
  **禁止部分继承**：不存在"继承到了桥但没继承到幽灵"这种中间态。

闸门判定必须落在可断言的读数上（`AlignmentResult` 的既有模式：`metaGeneration`、
`suppliedInventedNames`），新增读数用于区分 `Run R` / `Run E` / `Run E(闸门未过)` 三种情况，
不允许只靠日志区分。

### 3.3 同代同源

一次 `align()` 调用里，`R_prev` / `E_prev` / `Meta` 必须来自**同一代**（同一 `generation`）。
任何一项来自邻代即视为闸门失败：混代的后果是幽灵指纹与保留体互相错位，且产物仍能进 JVM。

## 4. Run R 的流水线与继承点

### 4.1 阶段划分

| 阶段 | 输入 | 与 Run E 的差异 |
|:--|:--|:--|
| 一：多轮匹配 | `R_prev` ↔ `R_new` | 旧侧无幽灵桩/桥/自造名，`ghost` 排除逻辑退化为无操作 |
| 一末：幽灵复活（`stepGhostRevive`，开关默认关） | 幽灵指纹来自 `Meta.ghostHashes`，缺失时回落 `E_prev` 桩体标记 | 幽灵集合由继承集定义，不由 `R_prev` 定义 |
| 二：未配对新方法取避障名 | 命名池 = `R_prev` 全部方法名 ∪ `E_prev` 全部方法名（含 key）∪ `R_new` 已有名 | 池必须比 Run E 更大：幽灵名、桥名只存在于 `E_prev` |
| 三：捕获桥 | 计划基线是 `R_prev`（源码捕获表），不是 `E_prev` | 上一轮的桥不再被当老 lambda 参与规划 |
| 四：孤儿与继承注入 | 孤儿判定基准 = `R_prev` 键 ∪ `E_prev` 键 − 本轮 `presentKeys` | 三件套统一在这一阶段落地（见 §4.2） |
| 五：自造名统计与发布 | 最终产物 + `Meta.inventedNames` 并集 | 发布契约不变（[`13-...`](../lambda/03-publication-contract.md)） |

### 4.2 继承集落地规则（阶段四）

对 `E_prev` 里每个"`R_prev` 中不存在或本轮 `presentKeys` 中不存在"的候选键：

- **幽灵桩**（`isGhostMethod`）：若本轮产物已有同名键则跳过；否则按 `Meta.ghostHashes`
  （缺失时按桩体标记）**原样重注入**，写入的生前指纹必须与上一代一致——不一致意味着身份换人，
  按"无标记"处理并把该键降级为空壳。幽灵一旦是幽灵就永远是幽灵（单向退休），
  Run R 不得让它变回活体。
- **保留体**（`hasRetainedBodyMarker`）：必须重新跑 `OrphanBodyRetention.rejectionReason`
  存活重验，判据取 `E_prev`（旧体）与 `R_new`（本轮结构）。通过则整段搬运（含帧与标记），
  不通过则降级为空壳并记一条 warn——**不得**因为"上一轮搬过"就直接搬运。
- **捕获桥**（`isBridgeMethod`）：按 `Meta.methodMapping`（老键 → 新键）重新指向本轮产物里的
  目标方法；目标不存在则放弃桥、退化为空壳，并记 warn。重指向必须落在**本轮最终名字**上
  （阶段二的避障名之前不能定案），即"桥的目标解析"晚于阶段二、与阶段四同批。
- **普通被删 lambda**（`R_prev` 有、本轮无）：按现有孤儿路径处理（幽灵化 / 保留体 / 转发桩），
  判据与 Run E 一致。

顺序约束：桥的重指向晚于避障名分配；保留体存活重验早于桥注入（桥目标可能引用保留体成员）；
自造名统计最后做（继承会引入旧名）。

### 4.3 命名池扩充的必要性

Run R 的旧侧是"干净源码方法表"，但**避障池不是**：fresh 名如果撞上 `E_prev` 里的幽灵名/桥名，
产物里就会出现两个同名方法键或把幽灵的槽位抢走，直接破坏"幽灵单向退休"与"fresh 名绝不与历史
幽灵重合"两条既有不变量。因此池必须是三源并集，且**只增不减**。

## 5. 继承集枚举与元数据最小集

继承集按"`E_prev` 字节枚举 + `Meta` 校正"取得，不要求把成员清单写进元数据：

| 成员 | 枚举判据（`E_prev` 字节） | 校正来源（`Meta`） |
|:--|:--|:--|
| 幽灵桩 | 方法体首调 `LambdaAligner.onOrphanInvoked`（2 参形态） | `ghostHashes` 提供生前指纹 |
| 保留体 | 方法体首调 `onOrphanInvoked` 的 3 参形态（`(String,ZZ)V`） | `ghostHashes` 提供生前指纹 |
| 捕获桥 | 方法体为"参数重排 + 单条委托调用"的适配形态 | `methodMapping` 提供目标重指向 |

元数据最小集（Run R 的前提）：`jvmEffHash`（G1）、`preAlignHash`（G2）、`rawPrevBytes`（`R_prev`
本体）、`inventedNames`、`ghostHashes`、`methodMapping`、`generation`。**缺任一必需项即 Run E**；
`bridgeCount` 只作读数，不参与判定。若实现选择扩充元数据（例如记录保留体键集合、`R_disk` 哈希），
扩充字段必须可选：旧条目缺字段时仍能按上表枚举，不得因 schema 演进而拒绝整代。

`rawPrevBytes` 是唯一以字节形态进入元数据的字段（配对基线 `R_prev` 必须是真实字节，哈希无法复原
方法体），因此它必须有显式预算，且预算压力下的降级方向是**丢字节保哈希**：

- 全局字节预算 `-Dremold.vcs.meta_blob_bytes`（默认 16 MiB）+ 每类上限（默认 256 KiB）；
- 超出预算 → 该代 `rawPrevBytes` 置空、`preAlignHash` 与其余度量照存，次轮该类走 **Run E**，
  `runRGate = NO_RAW_PREV`，并每类记一条 warn（降级必须可观测，不得静默切树）；
- blob 字节必须与 `preAlignHash` 自洽：读回时校验 SHA-256，不符即按 `NO_RAW_PREV` 处理（防止
  外部改坏的内存/缓存残片被当作配对基线）。

指纹层级（三层，不得混用）：

| 用途 | 指纹 | 说明 |
|:--|:--|:--|
| G1 代际身份 | SHA-256(`E_prev_used`)，与 `jvmEffHash`/`alignOutputHash` 任一相等 | 问的是"这条元数据是不是我这一代的 E_prev"，只能比 `E_prev` 自己的指纹 |
| G2 树身份 / blob 完整性 | SHA-256(`preAlign`) | 认的是 Raw 代；用它当 G1 会让次轮永远取不到条目 |
| 快速预筛 | 32 位 `Arrays.hashCode` | **只允许**做过滤，不得作为任何树键（§2.2） |

## 6. 回退与 fail-closed 规则

Run R 的每一个前提缺失都**只允许整类回退**，不允许"尽力而为"：

| 缺失项 | 行为 |
|:--|:--|
| `Meta` 无条目 / G1 哈希不符 | Run E（`LDC <long>; POP2` 标记与调用方自造名兜底，行为同当前实现） |
| `R_prev` 候选无法判同源（G2 失败） | Run E，并对该类记一条 log-once 的说明（避免每轮刷屏） |
| `E_prev` 不可读 / 继承集无法枚举（G3 失败） | Run E |
| 灰度开关未命中该类 | Run E |
| `align()` 中途异常 | 既有降级路径不变：返回 `R_new`、发布空结果快照 |
| 批次最终失败 / 回滚 / `LAMBDA_STRICT` 拒绝 | 不写入任何代（提交点契约不变，见 §7） |

回退必须是**静默安全**的：Run E 的产物与"没有元数据时"完全一致，因此"清空 `Meta` 后行为回到
旧跑道"本身就是一条可测的回退判据（R2 的兜底项）。

## 7. 灰度与提交语义

- **开关**：`META_SOURCE`（急停 `-Dremold.agent.lambda_raw_alignment=false`，兼容旧 Property
  `-Dremold.poc.meta_source`）**默认开启**；判定顺序为"急停 → 旧 Property → 默认 true"，
  因此照抄旧文档里的 `-Dremold.poc.meta_source=true` 不会覆盖急停。关闭后整类回到旧路径
  （`Run E`），行为与历史版本逐字节等价（R2）。真正的灰度粒度是**按类**的：三道闸门任一不过即该类
  回退，且回退原因以 `runRGate` 可观测。
  同一类在相邻两轮分别走不同跑道会让"第二轮零改动"这类不变量抖动。
- **元数据供给开关**：`Meta` 条目只在 `-Dremold.vcs.enabled=true` 时提交。因此 Run R 实际需要
  两个开关同时打开；只开灰度不开记录时必须落 Run E，并**首次**记一条 warn 说明"元数据源未开启"，
  不能静默假装走了 Run R。
- **提交语义不变**：提交点在"该类最终 `redefineClasses` 成功之后"，两阶段的阶段 A 不提交，
  整批任一回滚/拒绝即整批丢弃（[`01-bytecode-changeset-vcs.md`](01-bytecode-changeset-vcs.md) §5.3）。
  Run R 的产物与 Run E 的产物在提交时的字段口径完全相同：`preAlign=R_new`、`alignOutput=E_new`。
- **不变量**：`align()` 仍然只读 `Meta`，不写任何代；写入口仍由调用方在成功之后触发。

## 8. 不变量与判定锚点

| 编号 | 不变量 | 判定锚点 |
|:--|:--|:--|
| R1-a | 活体配对输入是 `R_prev ↔ R_new`；`E_prev` 不参与配对 | 构造"`E_prev` 含自造名+幽灵+桥、`R_prev` 干净"的三代序列：Run R 的映射等于"直接对齐两份源码产物"的映射 |
| R1-b | 继承集三件套从 `E_prev`/`Meta` 继承，不在 `R_prev` 里推导 | 三代序列的最终产物含与原桩相同的生前指纹、保留体存活重验仍执行、桥指向本轮最终名 |
| R1-c | 幽灵只单向退休，fresh 名不撞历史幽灵/桥名 | 既有 `LineNumberDifferentialTest` 与新增"池并集"用例同时绿 |
| R2-a | 老契约零退化 | `LineNumberDifferentialTest` / `HostAnchorTest` / `NameIndexTest` / `LambdaAlignTortureTest` / `AlignmentMetaEquivalenceTest` 全绿 |
| R2-b | 无 `Meta` 时无缝回退 | 清空 `Meta`（或关掉 `vcs.enabled`）后产物与 Run E 逐字节相同；标记兜底仍能认领幽灵 |
| R2-c | 无继承集成员时两条跑道等价 | `E_prev` 无幽灵/保留体/桥的自造名场景：Run R 与 Run E 产物逐字节相等 |
| R3 | 增量场景下 Run R 更准 | v1→v2（产生自造名/桥/幽灵）→v3（源头微调或 Ctrl+Z 回退）序列：Run E 失准、Run R 精准；关掉灰度该用例必须变红 |

## 9. 现状缺口（静态核对所得）

以下为阅读本批基线代码得到的事实，供实现批次（Run R 接线）作为前置项处理；每条的定位都是
文件级证据，不涉及行为推断：

- **F1 loader 维度缺失**：活体批次的 `align(...)` 调用走 5 参重载，未传 `loader`
  （`core/src/remold/RemoldAgent.java` 中对 `LambdaAligner.align` 的调用处），而提交侧带真实
  `loader`（`commitVcsBatch`）。`headMatching(null, ...)` 只命中 bootstrap 槽，
  因此应用类加载器下的类在 `META_SOURCE` 打开时**取不到条目**——现象是"开关生效但读数不变"。
- **F2 元数据缺 `R_disk` 哈希**：`AlignmentMetaStore.Entry` 只有 `preAlignHash` /
  `alignOutputHash` / `jvmEffHash`，没有磁盘原始字节指纹。G2 的"首选"判据因此对
  `sourceBytecodeCache` 侧候选无效，只能靠"重放前置变换再比 `preAlignHash`"。
- **F3 Ctrl+Z 识别口径**：源码是否退回原版本只能按 `R_disk` 判（§3.2），当前记录不含该口径，
  故"回到旧版本"这一形态在 Run R 下仍需靠 `preAlignHash` 间接判断，且对前置变换实现漂移敏感。
- **F4 前置变换不可复原的类**：`R_new` 与 `R_disk` 之间夹着织入桥、访问权限单调对齐等
  依赖插件状态的变换；这些类在 Run R 下会稳定地 G2 失败并落 Run E。规格要求这是**可观测的
  降级**（读数 + log-once），不是静默。
- **F5 继承集元数据不足**：`Entry` 不含保留体键集合与桥目标表；本规格因此把继承集定义在
  `E_prev` 字节上（§5），`Meta` 只做校正。若实现改为纯元数据枚举，则必须同时补齐这三类字段。
- **F6 字符串常量见证表**：`witnessSimpleName` / `renameBySimpleName` 的证据只来自参与配对的
  旧侧。Run R 的旧侧不含幽灵名与桥名，这两类名字在字符串常量里的见证因此缺失；是否影响产物
  需由用例覆盖（属于 Run R 的行为差异面，不得假定等价）。
- **F7 桥目标解析时机**：`BridgeSpec.targetName` 是绝对名，继承继承时必须晚于避障名定案重解析，
  否则桥会指向上一代的名字（现象是老实例的适配器调不到本轮方法）。
- **F8 双开关耦合**：元数据只在 `-Dremold.vcs.enabled=true` 时提交，Run R 需要两个开关同时打开；
  该耦合必须写进用户可见配置说明，否则"开了灰度没效果"会被读成实现缺陷。

## 10. 风险与不在本规格范围

- **风险**：Run R 与 Run E 的产物差异只允许出现在"`E_prev` 有继承集成员"的场景；任何在
  "无污染场景"下出现的字节差异都视为 Run R 的副作用缺陷，必须先修再用它做对比证据。
- **风险**：三源命名池会让 fresh 名编号继续膨胀（池越大，可选号越靠后）。名称只增不减是既有
  不变量，不得为了紧凑回收历史名字。
- **不在本规格范围**：`InitFix` 的字段台账多代化、`Staging` 跨类原子提交（见
  [`01-bytecode-changeset-vcs.md`](01-bytecode-changeset-vcs.md) §8.4）、离线回放包格式、
  字节埋点的删除（埋点降级为 fallback，不删）。

## 11. 相关文档

- 配对判据与不变量：[`../topology/01-invariants-and-remapping.md`](../topology/01-invariants-and-remapping.md)、
  [`../topology/03-cascading-pipeline.md`](../topology/03-cascading-pipeline.md)、[`../topology/04-tiers-and-rejection.md`](../topology/04-tiers-and-rejection.md)
- 孤儿桩与保留体：[`../lambda/02-orphan-body-retention.md`](../lambda/02-orphan-body-retention.md)
- 捕获桥：[`../lambda/01-capture-bridge.md`](../lambda/01-capture-bridge.md)
- 结论发布与按类检索：[`../lambda/03-publication-contract.md`](../lambda/03-publication-contract.md)
- 代际元数据/回放包/提交点契约：[`01-bytecode-changeset-vcs.md`](01-bytecode-changeset-vcs.md)
