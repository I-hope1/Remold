# 代际字段台账（B2）：`FieldLedger` / `REINIT_APPLIED` 的按代存储与 CAS 对账

> **文档类型**：设计规格（交付状态与验收计数以 [`docs/status.md`](../status.md) 为准）
> 上游规格：[`01-bytecode-changeset-vcs.md`](01-bytecode-changeset-vcs.md) §2.1.1～§2.1.3 / §3.6 / §3.8 / §4.1 / §5.2 / §5.4，本文件只做扩展；冲突处以 `01` 为准。

## 0. 边界

| 维度 | 内容 |
|:--|:--|
| B2 负责 | `FieldLedger` / `REINIT_APPLIED` 的**按代存储形状**；`Changeset.replayFacts` 在台账侧的**代际索引**；`InitializerReplay` / `ApplyInitializerReplay` 消费跨代事实时的**判定规则**；回退后的 **CAS 期望值对账** |
| B2 不负责 | 回退**执行**（正向 revert 的提交、`revertedToSeq` 的产生、`NOT_REVERTABLE` 判定、redefine 编排 —— 属 B1）；堆值回滚（§4.1：实例/静态字段值、已写入值、用户运行期修改都是**堆事实**，不可由字节恢复）；`replayFacts` 的记录格式与计算时机（16 §3.8 已固定）；既有安全门与判决分层的任何放宽（16 §5.2） |
| 硬前提 | `PENDING` 弱键不变量（`synchronizedMap(WeakHashMap<Class<?>, PendingPatch>)`、`PENDING_TTL_NANOS`、值不持 `Class<?>`）不变；`LEDGER` 职责仍是"只负责不遗忘，不负责放行"（`docs/initfix/02-closure-and-ledger.md` §2）；写入协议不变（默认 `KIND_CONDITIONAL`，仅 `OVERWRITE` 用 `KIND_FORCE`，`float`/`double` 走 raw bits） |

---

## 1. 三个存储的分工：两处真值 + 一处派生索引

| 存储 | 今日形状（`core/src/remold/ref/InitFix.java`） | 代际职责 | 真值性 |
|:--|:--|:--|:--|
| 未闭环台账 `LEDGER` | `Map<Class<?>, Map<String, String>>`，值 = 字段名 → 原因；`getUnpatchedFields` 返回 `Set<UnpatchedField(fieldName, reason)>` | 六种记账情形（分析期被拒 / 运行期异常 / 依赖跳过 / redefine 失败 / `buildPatch` 抛异常 / TTL 清扫）与代无关，条目**不携带代坐标** | 真值：字段已物理存在却没补上，只有本进程记得 |
| 已应用记录 `REINIT_APPLIED` | `Map<Class<?>, Map<String, String>>`，值 = 字段名 → `reinitRecordKey` 产出的 `"模式\|切片指纹"`（切片不封闭时为 `null`，表示"判不了 → 每轮照常重置"） | 每条记录带**写入代** `(seq, id)` 与写入结果 | 真值：写过什么，只有本进程记得 |
| 代际事实索引 | 无（`captureReplayFacts` / `captureReinitFacts` 把事实直接塞进 `BatchContext.replayFacts`，随 `commitVcsBatch` 固化） | `(scope, 类, 字段) → 有序 GenRef 列表`，`GenRef = (seq, changesetId)`，指向 `Changeset.replayFacts` 里的条目 | **派生**：真值是 `Changeset.replayFacts`；索引丢失可重建，事实丢失只能拒绝 |

索引存在的唯一理由是省掉每次写入前的链扫描；它的任何缺失**不得**改变判定结果，只能改变成本（§2.3）。

## 2. 键形状与生命周期

### 2.1 键

```
LedgerKey := (long scope, String className /*内部名 a/b/C*/, String field)
GenRef    := (long seq, String changesetId)
```

- `scope` **必须**取 `AlignmentMetaStore.INSTANCE.loaderScopeId(loader)`，与 `Changeset.loaderScope` 同一套单调代号（`RemoldAgent.commitVcsBatch` 用的是同一个调用）。禁止另建一套 loader 编号：两套编号会让台账与 Changeset 链对不上号，且不会报错。
- `className` 用**内部名**（16 §2.4：相等性只看 `(id, internalName)`，不得让点分名与内部名并存为两个 key）。而 `ReplayFact.ownerClass` 是**点分名**（`InitFix.dotted`），`captureReinitFacts` 直接取 `host.getName()`。规范化只能在**一个**函数里做（`internal → dotted` 用于 join），禁止两边各写一次 `replace`。
- `field` 是唯一字段 Key，不支持同名异型（AGENTS §3）；`ReplayFact.desc` 只作**校验位**：非空且与待写字段描述符不等即判失效；`REINIT` 档的 `desc` 恒为 `""`（`captureReinitFacts` 传空串），因此 `REINIT` 事实只按 `(ownerClass, field)` join，**不得**要求 `desc` 匹配。
- `A` 档的 `ownerClass` 是**目标类型** `T`（不是持有者类），键必须落在 `T` 上；`provenance = dest + ":" + destDesc` 只作附加校验，`dest` 是否存在属运行期结果（16 §3.8 末条），不进事实。

### 2.2 代坐标：用 `seq`，`id` 作校验位

| 候选 | 结论 | 理由 |
|:--|:--|:--|
| `Changeset.id` 作主键 | 否 | `ChangesetLog` 里与寻址相关的只有 `head` / `bySeq` / `chain` / `size`，**没有 `byId`**。用 `id` 寻址等于每次写入都要遍历所有 scope 的链。且 `id` 由 `(parentId, seq, 内容)` 决定，同一份内容在不同 `seq` 上得到不同 `id`（16 §2.1.1），拿它当"内容相等"用会读错 |
| `seq` 作主键 | 是 | `seq` 是唯一可窗口寻址的坐标（`bySeq`），且 16 §2.1.1 承诺同 scope 内单调不复用 |
| `id` 作校验位 | 是 | 必须同时存 `changesetId`：命中要求 `bySeq(scope, seq).id().equals(genRef.changesetId)`。`id` 预像含 `parentId` / `seq` / 内容（B1 起另含 `revertedToSeq`），能识破"seq 相同但链被重建 / 内容被替换"的别名情形，也能区分同一内容上的回退代与非回退代；只存 `seq` 时这类错配无信号 |

保护的不变量：**同一条台账记录引用的事实，必定来自它登记的那一代**；`seq` 提供定点寻址，`id` 提供身份绑定，缺一不可。

### 2.3 两类保留：未闭环债务 vs 历史索引

| 类别 | 保留规则 | 代坐标 | 被淘汰时的行为 |
|:--|:--|:--|:--|
| **未闭环债务**（`LEDGER` 里的条目，即 `getUnpatchedFields` 能看到的那些） | 只随**类存活**消失（弱键）与**闭环**出账；**不随窗口淘汰** | 无 | 不适用。代际窗口不能决定"这个字段还要不要补"——那是 `docs/initfix/02` §2 的六种记账情形，与代无关 |
| **历史事实索引**（`GenRef` 列表 + `REINIT_APPLIED` 的按代记录） | 跟随 `ChangesetLog.window()`，默认 `32`，`-Dremold.vcs.changeset_window` 覆盖，下限 1 | 有 | 索引条目被淘汰 → 退化为"扫描 `chain(scope)` 降序找事实"（成本，不是判定）；`Changeset` 本身出窗 → 事实**不可重建**（16 §3.8：`replayFacts` 不能由 `Raw + Meta` 重建）→ 判定为 `MISS_WINDOW`，拒绝并 warn |

- **不新增窗口旋钮**：台账窗口必须直接由 `ChangesetLog.window()` 决定。两个窗口一定发散成"类字节在单类历史上限内、对应 Changeset 已出窗"的错配——这正是 16 §2.1.1 取 `8 × 4 = 32` 要避免的形态。
- `REINIT_APPLIED` 的按代记录**必须**随窗口一起淘汰：留着超窗口的"已应用"记录，等于给回退后的 CAS 提供一个无法验证的期望值。
- 闭环（字段补上 / 判定无需补 / 字段已消失）时，`LEDGER`、`REINIT_APPLIED`、索引三者同时出账；字段从新版本消失时的出账口径与今日一致（`ledgerRemove` + `reinitAppliedRemove`）。

### 2.4 弱键与 loader 卸载

- 台账的 scope 表是 loader 弱键槽：`WeakHashMap<ClassLoader, ScopeSlot>`，`bootstrapSlot`（scope 0）不走弱表。loader 被回收 → 槽不可达 → 该 scope 名下未闭环债务、已应用记录、事实索引**整体消失**，不需要 `ReferenceQueue` 手工清扫。
- 全部访问（含 `size()` / 遍历 / 快照）都在同一对象监视器内完成：`WeakHashMap.get()` 内部会 `expungeStaleEntries()` 改表结构，多线程只读也会崩（`AlignmentMetaStore` 已因同一原因串行化）。
- 值内**不得**出现 `Class` / `ClassLoader` / `ClassNode` / 字节数组：只允许字符串、枚举、装箱基本类型、以及 `GenRef` 这类纯标量记录。读侧返回快照拷贝（今日 `ledgerSnapshot` / `getUnpatchedFields` 已是此口径）。
- `ChangesetLog` 的链按 `long` 归档、与 loader 生命周期**不联动**（只按窗口淘汰）。因此**不得**用"链上还有这个 scope"推断 loader 还活着；存活判定只认台账自己的槽。

## 3. 回退后的 CAS 对账

### 3.1 今日两个期望来源（对账只改第二个）

| 写入通道 | 比较侧（期望） | 依据 |
|:--|:--|:--|
| 新增字段补丁 | 类型零值 / `null`（**按位判零**：`-0.0f` 位模式非零，被视为"已有值"而跳过），走 `KIND_CONDITIONAL` | 新增字段在旧字节码里不存在，零值即为"未写过" |
| `L1` 存量初值重放 / `A` 档 `apply {}` 重放 | 旧初值常量，走 `KIND_CONDITIONAL_EXPECTED`（期望是调用点携带的常量） | `InitializerReplay.ReplayField.expected` / `ApplyInitializerReplay.ReplayField.expected`，由 `InitializerReplay.plan` / `ApplyInitializerReplay.plan` 从**当轮** `ClassDiff` 得出 |

代际上下文只影响第二行：一旦发生回退，当轮 `ClassDiff` 的两侧不再是"JVM 里实际是什么"的可信描述。

### 3.2 目标代：`target`，不是 `N`

B1 在 HEAD 之上新建一代：`seq = N + 1`、`parent = HEAD`、`revertedToSeq = target`（该字段由 B1 定义；本文件只约定语义：`0` 表示非回退代，`seq` 从 1 起，故 `0` 无歧义）。B2 的一切期望值取样点固定为 `target`：

- `target` = 回退代自己记录的 `revertedToSeq`；回退代本身若也是回退代，沿 `revertedToSeq` 单跳解析到具体目标（**不做多跳传递闭包**，避免把"回退的回退"误解成"回到未来"）。
- `seq = N` 是**被回退掉的污染代**：它的 `replayFacts` 描述的是"已经被撤销的初值迁移"。拿它当期望，会在实例仍持有污染代写入值时判"无需写"，把回退变成静默 no-op。

### 3.3 确定性查找

给定待写 `(scope, C, field, desc)` 与目标代 `target`，取 `f0` = **最大 `g ≤ target` 且 `bySeq(scope, g).replayFacts` 含匹配 `(C, field[, desc])` 的事实**；`f0` 可用当且仅当以下三条全过：

1. **身份**：`bySeq(scope, g).id()` 与该事实登记时的 `changesetId` 相等（§2.2 校验位）。
2. **相邻性**：`C` 的 `entries[C].rawHash` 在 `[g, target]` 区间内**恒定**——即对每个 `h ∈ (g, target]`，或 `C ∉ h.entries`，或 `h.entries[C].rawHash == g.entries[C].rawHash`。理由：16 §3.8 明确"非常量初始化不猜、不产出记录"，因此"最近有事实"不等于"事实还成立"；`rawHash` 恒定才等价于"这段区间里这条字段初值一个字没动"。
3. **形态**：`f0.literalKind ≠ NONE`，且 `oldLiteral` / `newLiteral` 都是该 `literalKind` 的规范形态（§4-R5）。

通过后使用 `f0` 的 **同一对** `(oldLiteral, newLiteral)`：`oldLiteral` 作比较侧，`newLiteral` 作写入值。禁止跨事实混搭两侧——混搭会在中间代被用户改过时静默写错，且用例无法区分"混搭"与"正确"。

判定结果枚举（负向用例按此逐条打红）：

| `Resolution` | 触发条件 | 行为 |
|:--|:--|:--|
| `EXACT` | 三条全过且 `g == target` | 按 `(oldLiteral → newLiteral)` 写 |
| `CHAINED` | 三条全过且 `g < target` | 同上；`fromSeq = g` 记入 `PatchReport` |
| `MISS_NO_FACT` | `[1, target]` 里没有任何 `(C, field)` 事实 | 不写；字段记入未闭环台账；warn |
| `MISS_WINDOW` | 事实存在但所在代已出窗 / `id` 不符 | 不写；warn 一条点名 `seq`；**不**回退到"最近的活代"顶替 |
| `REFUSED_DISCONTINUOUS` | 身份或相邻性不过 | 不写；warn；`REINIT_APPLIED` 该字段作废 |
| `REFUSED_NONE_LITERAL` | `literalKind == NONE` 或形态不匹配 | §4-R4 |

**未写入的代不贡献期望（R3 的日常形态）**：一条事实只说明"那一代的理论初值是这些"，不说明"堆上现在就是它"。
若某个字段已经欠着未闭环债务、而台账里没有任何"确实写成功"的记录，那么 HEAD 那一代的事实只是理论值——
用它的 `newLiteral` 当写入值会把上一轮的旧初值写回堆里（看着成功、实际倒退）。因此消费侧在
`!revertContext && isUnclosed && 无确认写入` 时一律 fail-closed：不写、不取期望、债务保留，
直到 `@RemoldReinit(OVERWRITE)` 或显式重建真正写出为止（"确认写入"的唯一可信来源是运行期落账的
`REINIT_APPLIED` 行，且该行必须属于**待对账那一代**：回退会保留更早的代，若按"历史上任何一代写过"
判定，旧代记录就会给一条从未写成的事实背书）。

**期望来源唯一**：比较侧只取通过上述三条校验的那条事实的 `oldLiteral`，写入侧取**同一对**事实的 `newLiteral`（`EXACT` / `CHAINED` 同口径）。不设第二个期望来源——`REINIT_APPLIED` 里"由引擎写入"的记录值、污染代 `seq = N` 的 `newLiteral`、类型默认值都不是期望（D12）。理由：引擎在污染代写过的值与用户在运行期改过的值在堆上无法区分（16 §4.1），扩充来源等于把后者当成"未改过"覆盖，并把回退变成静默 no-op。

由此得到回退后的 **fail-closed 后果**（负向判据见 §6-A5 / §6-A14）：实例若仍持有污染代写入的值，它按 §4-R5 与 `target.oldLiteral` 不等 → CAS 不命中 → 不写、不改写、不记"已应用"——**fail-closed 进 `PENDING`**：该字段仍是未完成项（`PatchReport.applyReplayFields` 的该条结果为 skipped，`REINIT_APPLIED` 不出"已应用"记录，`[g, target]` 区间事实不因这次跳过被改写），下一轮重新进候选。宁可这一轮少写一次，也不把无法验证的值当成"已改回"（AGENTS §3 安全优先）。

### 3.4 `REINIT_APPLIED` 按代与作废

- 记录形状：`(scope, C, field) → (seq, changesetId, key = reinitRecordKey 的 "模式|切片指纹", result)`；`result` 只记运行期结果（写入数 / 跳过数 / 拒绝原因枚举），不记字面量以外的运行期对象。
- `key == null`（切片不封闭，`reinitRecordKey` 返回 `null`）表示"判不了初值表达式是否变过" → 该字段**不得**跨代跳过，每代照常重置。
- 回退提交（`revertedToSeq = target`）落地时，对被回退代 `seq > target` 的 `REINIT_APPLIED` 条目**整体作废**（`reinitAppliedClear` 同口径），而不是改写：宁可多写一次，不可静默留下过期值（AGENTS §3 安全优先）。
- 今日已有作废口保持不变：`reinitAppliedClear`（补丁没生效）、`pruneReinitRecords`（注解被摘掉）、字段消失时的 `reinitAppliedRemove`。

### 3.5 消费侧的判决形状（`GenerationalReplayGate`）

`resolve()` 的结论由 `core/src/remold/vcs/GenerationalReplayGate.java` 翻成"这一轮这条字段还写不写、
比较侧用什么期望、写入侧写什么值"。它是**纯函数**（只吃一个 `Resolved` + 字段描述符），因此可以在
纯 JVM 单测里逐条打红；调用方（`InitFix.gateGenerationalReplay`）只负责取坐标、落账与改写发射。

| 结论 | 触发 | 消费侧动作 |
|:--|:--|:--|
| `OBLIVIOUS` | `MISS_NO_FACT`（索引里没有这条键的代际证据） | 沿用当轮 `ClassDiff` 的一对初值，与引入代际对账前逐字节一致。**索引缺失只改变成本、不改变判定**（§2.3），所以"没有事实"不是拒绝 |
| `FACT` | `EXACT` / `CHAINED`，且形态、口径、描述符全部过关 | 比较侧 = 事实的 `oldLiteral`，写入侧 = **同一条**事实的 `newLiteral`（D6）；`InitFix` 用两者重建 `KIND_CONDITIONAL_EXPECTED` 的 bootstrap 常量与压栈常量 |
| `REFUSED` | `MISS_WINDOW` / `REFUSED_DISCONTINUOUS` / `REFUSED_NONE_LITERAL` / 口径（`algoVersion`）不同 / `kind` 与描述符不符 / 字面量非规范形 | 该字段退出重放候选：`PatchReport.replayFields`（或 `applyReplayFields`）记 `rejected: <原因>`，走既有 warn 出口点名字段；债务落 `markUnclosed` |

三条边界：

- **只在回退上下文里改判**。当轮 `ClassDiff` 的两侧只有在"堆上的值可能是被回退掉那代写的"时才不可信；
  正向代里 old 侧就是当前字节的初值，本身就正确。因此判据是：HEAD 本身是回退代（`revertedToSeq > 0`），
  或这条键还欠着未闭环债务。少了这一条，正向重放会被代际事实顶成"期望晚一代"，首次漂移之后每轮都判不命中
  （实测形态：`PatchReport` 连续 skipped、堆值停滞）。
- **`L1` 的台账键是字段的声明类，不是被热更的匿名宿主**。`l1ReplayFact` 的 `ownerClass` 取
  `InitializerReplay.ReplayField.declaringClass`（16 §3.8：`ownerClass` = 声明类），而 `L1` 只重放
  **继承**字段——写入切片在匿名子类的实例初始化块里，字段却在父类。消费侧查账必须用同一个类，否则永远查不到；
  运行期的记账（CAS 未命中 / OVERWRITE 闭环）也必须用同一个键，因此该 owner 随 `PendingPatch` 传到记账点。
- **相邻性按声明类判，因此 `L1` 需要声明类在事实那一代里有条目**。`rawHash` 恒定判据只看
  `entries[C]`；若声明类在整段区间里从未出现在任何代的 `entries` 里，`isAdjacent` 无法证明"事实仍成立"
  → `REFUSED_DISCONTINUOUS`（fail-closed，与 §2.3 的"判不了就拒绝"一致，不是"回落到最近活代"）。

## 4. 跨代 `replayFacts` 消费规则

| # | 规则 | 判据（全部只读已记录的字符串） |
|:--|:--|:--|
| **R1** | 跳过代只允许在 `<clinit>`/`<init>` 初值**逐代可链**时进行 | 对 `(C, f)` 从 `g` 到 `g'`：或 `rawHash` 在 `[g, g']` 恒定（§3.3 第 2 条），或每代 `h ∈ (g, g']` 都有事实且 `fact(h).oldLiteral == fact(h-1).newLiteral` 字节精确。任一不满足即禁止跳读 |
| **R2** | `REINIT` 档字段禁止跨代跳读 | 该档 `literalKind = NONE`、两侧字面量为 `null`（16 §3.8）。无字面量就没有"初值是否变过"的证据；它的语义由 `scope` / `mode` / `provenance` 承载，跨代无法比较 |
| **R3** | `PENDING` 代只贡献写入候选，不贡献期望 | `PENDING` 的键是 `Class<?>` 弱键、无代坐标（不变量 2 不变）；字段被挂起那一代的 `newLiteral` 是**理论**初值，不是堆上实际值。跨过它时：期望取该代之前最近一个**有事实且有写入结果**的代；**禁止**把类型默认值（`null` / `0` / `false`）当 `oldLiteral`——默认值不是初值 |
| **R4** | `REINIT` 档的拒绝口径 | 同一字段既有 `REINIT` 事实又有 `L1`/`A` 事实时，`REINIT` 优先（AGENTS §1）；重放必须拒绝并走 `InitFix.transform` 的统一 warn 出口，措辞沿用既有形态（`declaring field is annotated @RemoldReinit, the explicit path wins; not replayed`）。**不得**用"两侧字面量都是 `null` → 视为无变化 → 跳过"这种推断；**不得**为 `REINIT` 合成字面量 |
| **R6** | 消费侧只解析**规范形态**的字面量，解析不了即拒绝 | `FLOAT_BITS`/`DOUBLE_BITS` 只接受 `0x%08x`/`0x%016x`；`Z/B/S/C` 只接受 `literalOf` 产出的规范文本（`true/false`、十进制、单字符）。解析失败返回"判不了"，调用方必须 fail-closed——**不得**回退到十进制反推或就地补位模式（那等于伪造事实）。跨口径（`algoVersion` 不同）的记录同样在这一层被拒，不只依赖存储层的 `record()` 拒收 |
| **R5** | 字面量比较 = `(literalKind, 规范文本)` 字节精确相等 | `INT`/`LONG` 十进制；`FLOAT_BITS` = `0x%08x`、`DOUBLE_BITS` = `0x%016x` 的**原始位模式**（`literalOf` 的形态）；`STRING` = 转义 UTF-8 + 字节长度；`null` 记文本 `null`。`kind` 不同即不等（`INT 1` ≠ `LONG 1`）；`-0.0f`（`0x80000000`）≠ `0.0f`（`0x00000000`）；NaN 按位区分。禁止 `parseFloat` 回读、禁止数值容差、禁止 `0x…` 与十进制混比 |

R5 的形态前置项（需 Lead 复核）：`captureReplayFacts` 对 `L1` 的 **new 侧**调用的是 `literalOf(null, field.newText(), desc)`，`value == null` 时直接回落到 `newText`（`String.valueOf(naturalNew)` 的十进制渲染），因此 `L1` 的 `float`/`double` 事实会出现"`oldLiteral` 是位模式十六进制、`newLiteral` 是十进制"的混合形态；`A` 档的 new 侧传的是 `newRaw`，不受影响。按 R5，这类记录判为形态不匹配（`REFUSED_NONE_LITERAL`）并拒绝跨代对账，**不得**在台账侧自行把十进制补成位模式（那等于伪造事实）。是否给 `ReplayFact` 的 `L1` new 侧补定型值，属 16 §3.8 格式变更，不在本文件授权范围内。

---

## 5. 决策 → 理由

| # | 决策 | 理由 |
|:--|:--|:--|
| D1 | 代坐标 = `seq`，`id` 作校验位 | `bySeq` 是唯一的窗口内寻址；`id` 绑定 `(parent, seq, 内容)`，能识破 seq 别名而不增加查找成本 |
| D2 | `replayFacts` 真值留在 `Changeset`，台账只存索引 | 16 §3.8：事实不可由 `Raw + Meta` 重建，因此不能被二次推导顶替；索引可丢、可重建 |
| D3 | 未闭环条目不携带代坐标 | 六种记账情形与代正交；带代坐标会让"该字段还要不要补"被窗口淘汰误删，违反"不遗忘" |
| D4 | 台账不自建窗口旋钮，直接用 `ChangesetLog.window()` | 双窗口必然发散出"类字节还在、对应事实已出窗"的错配（16 §2.1.1 的 8×4 口径） |
| D5 | 回退后期望取 `target` 而非 `N` | `N` 是被撤销的污染代；用它做期望会让回退在"实例仍持污染值"时静默 no-op |
| D6 | 单条事实的 `oldLiteral`/`newLiteral` 必须成对取 | 跨事实混搭在中间代被用户改过时静默写错，且用例无法区分混搭与正确 |
| D7 | 相邻性用 `rawHash` 恒定 / 逐代链等式判定 | 16 §3.8：非常量初始化不产出事实，"最近有好事实"不蕴含"事实还成立" |
| D8 | `REINIT` 档不跨代跳读、不合成字面量 | `literalKind = NONE` 是"无信息"，不是"无变化"；把两者等同正是静默留过期值的典型形态 |
| D9 | `PENDING` 保持无代坐标；其所在代只贡献写入候选 | 默认值不是初值；未产生的写入不能进"已应用"，否则 CAS 会把零值当旧值 |
| D10 | 字面量按 kind + 规范文本字节精确比较 | `float`/`double` 的十进制回读会把 `-0.0`/`0.0`、不同 NaN payload 混同（16 §3.8） |
| D11 | 回退提交后 `seq > target` 的 `REINIT_APPLIED` 整体作废 | 无法验证的"已应用"记录会给下一轮提供错误期望；多写一次可恢复，静默过期不可恢复 |
| D12 | 回退后 CAS **只有单一期望来源**：`target` 的 `oldLiteral`；引擎自写值不作期望 | 引擎写过的值与用户运行期改过的值在堆上无法区分；多源期望会把后者当"未改过"覆盖，且"记录值可信"这一点无法机械校验。代价是实例持污染代写入值的字段整体判跳过（回退在堆侧不搬运这些值）——这是有意的取舍：不命中即 fail-closed 进 `PENDING`，宁可少写一次也不静默写错 |

## 6. 验收判据

单位置断言（未来测试套件的最小集合）。每条都必须有**负向对照**：改回被禁止的写法后该用例变红。

| # | 正向断言 | 负向对照（必须变红） |
|:--|:--|:--|
| A1 | 台账键 `(scope, 内部名, field)` 与 `Changeset.loaderScope` 对齐；点分名与内部名不产生两条记录 | 把 `dotted`/`internal` 转换各写一次 → 同名类被记两代 |
| A2 | 同 scope 内两条记录引用不同 `seq` 但相同 `changesetId` 时报身份错配 | 去掉 `id` 校验 → 链重建后仍命中 |
| A3 | 未闭环字段在窗口 `M` 满窗淘汰后仍在 `getUnpatchedFields` 里，且下一轮重新进候选集 | 让未闭环条目随窗口淘汰 → 字段被永久遗忘 |
| A4 | 事实所在代出窗后判 `MISS_WINDOW` 且不写、不顶替代用品 | 回落到"最近活代"事实 → 用错代初值仍写入 |
| A5 | 回退到 `target` 后 CAS 期望 == `target` 的 `oldLiteral`，写入 == `target` 的 `newLiteral` | **期望取自 `seq = N`（被回退掉的污染代）→ 必须变红**（本批次的核心负向）；把"引擎自写值"或 `REINIT_APPLIED` 记录值补进期望来源 → 同样变红（回退在堆侧静默 no-op，实例仍持污染值却判"无需写"） |
| A6 | 中间代里 `C` 的 `rawHash` 变过且该代无事实 → `REFUSED_DISCONTINUOUS`，不写 | 把相邻性判据放宽成"最近有事实即用" → 变红 |
| A7 | `INT 1` 与 `LONG 1`、`0x80000000` 与 `0.0f`、两种 NaN payload 逐对判不等 | 比较改用 `parseFloat` / `String` 去前导零 → 变红 |
| A8 | `L1` 的 `float`/`double` 事实在 new 侧为非位模式形态时判 `REFUSED_NONE_LITERAL` | 允许把十进制补成位模式 → 变红（等于伪造事实） |
| A9 | `REINIT` 档字段在存在跨代差异时仍拒绝跨代跳读，且重放被拒、warn 经 `InitFix.transform` 出口 | 把 `literalKind = NONE` 当"无变化"跳过 → 变红 |
| A10 | `PENDING` 代的 `newLiteral` 只出现在写入候选，不出现在期望；默认值不被当作 `oldLiteral` | 允许默认值充当期望 → 把用户改过的值当"未改过"覆盖 |
| A11 | 回退提交后 `seq > target` 的 `REINIT_APPLIED` 条目消失，下一轮重新走安全门 | 保留记录并改写成 `target` 的值 → 变红 |
| A12 | loader 被回收后该 scope 的未闭环条目、已应用记录、事实索引一并不可见（`WeakReference` 证明回收 + 读数归零） | 换成本 scope 内 `strongMap` → 回收后仍有幽灵条目 |
| A13 | `bootstrapSlot`（scope 0）在 `null` loader 下不 NPE，且不参与回收 | 把 `null` loader 丢进 `WeakHashMap` → NPE |
| A14 | 回退后实例仍持污染代 `newLiteral` 的字段：条件 CAS 的比较侧只接受 `target` 的 `oldLiteral`（§3.3），不命中即 fail-closed 进 `PENDING`——`PatchReport.applyReplayFields` 记 skipped、`REINIT_APPLIED` 不记"已应用"、不出闭环账 | 补进第二期望来源（引擎自写值）→ 变红：回退在堆侧静默 no-op，污染值被当成"已改回"；改用 `KIND_FORCE` 强写 → 变红：覆盖用户运行期修改 |

A5 / A8 / A14 的实现对应物（本文件只定义判据，不登记状态）：

- `tests/src/remold/vcs/GenerationalReplayGateTest.java`：判决层的正/负例（含"没有事实不是拒绝"、非规范形态、跨口径、描述符不符、位模式解码）；
- `tests/src/GenerationalReplayWiringTest.java`：真实 `InitFix.transform` + 内存 `ChangesetLog`/台账（无 DCEVM），锁"回退上下文里被拒的候选不进补丁、报告点名、债务落台账"，并锁"正向代不被代际事实顶替"；
- `tests/src/VcsA14Probe.java`：真机链路（JBR 21 + 回退），断言 CAS 未命中 → `skipped` + warn + 台账债务 + 堆值不动，随后 `@RemoldReinit(OVERWRITE)` 写入并把债务闭环。

变异检查（与仓库既有口径一致）：新增/放宽任一条安全门，必须附带负向阻断断言；A5、A6、A7、A9、A11、A14 这六条各自对应一处实现变异，变异存活即视为测试判定力不足，不得以"其它用例已覆盖"解释。

---

## 7. 与上游规格的对应与未决项

| 上游条目 | 本文件的对应 |
|:--|:--|
| 16 §2.1.1 `seq` / 窗口 / 三级可用性 | §2.2、§2.3（窗口跟随，不复用；`REVERTABLE` 不达即 B1 报 `NOT_REVERTABLE`，台账不提供替代品） |
| 16 §2.1.3 `patchTiming` | §4-R3：`AFTER_COMMIT` 代的提交时刻字段尚未补，故该代不得提供"已应用"证据 |
| 16 §3.6 台账多代化、`REINIT_APPLIED` 按代 | §1、§3.4 |
| 16 §3.8 `replayFacts` 是事实、不可重建 | §2.3、§4-R5（含 L1 new 侧形态前置项） |
| 16 §4.1 堆事实不可由字节恢复 | §0 边界、§3 全节（回退不恢复实例/静态值，跳过必须落 `PatchReport`） |
| 16 §5.2 `InitFix` 契约（台账不负责放行） | §0、§4-R4（拒绝走统一 warn 出口） |
| 16 §5.4 跨 loader 不共享 ledger | §2.1、§2.4 |

未决项（需 Lead 裁量，均不影响上文规则的机械可判定性）：

1. R5 的 L1 new 侧定型缺口：扩 `Changeset.ReplayFact` 需回到 [`01-bytecode-changeset-vcs.md`](01-bytecode-changeset-vcs.md) §3.8 做格式评审；否则 `L1` 的 `float`/`double` 跨代对账长期停在"拒绝"档。
