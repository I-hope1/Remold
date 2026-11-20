# 字节码 Changeset VCS：内容寻址的热更版本层规格

> **文档类型**：设计规格（只写规格与契约；当前实现状态、验收证据与残余风险以 [`docs/status.md`](../status.md) 为准）

本文件定义热更引擎内部的**字节码 Changeset VCS**：一个内容寻址、内存主导、嵌入式的版本层，
把"每一轮实际喂给 JVM 的字节 + 逐类元数据 + 跨类拓扑结论"组织成可寻址、可比较、可回放的代际。
它只规定数据模型、安全约束、与既有子系统的契约、原子提交边界、验收判据与渐进路线；
不记录任何交付进度，也不以性能结论为前提（性能口径见 §7.5）。

阅读顺序建议：先看 §0 边界与 §1 现状基线，再看 §2 数据模型与 §4 三项安全约束；
只关心落地顺序的读者可直接读 §8。

---

## 0. 定位与边界

### 0.1 一句话定位

字节码 Changeset VCS（内容寻址、内存主导、嵌入式）：在**同一个 JVM 内**，为每一轮热更建立一条只追加的
commit 链，每个 commit 记录该轮 Raw（磁盘原始字节）与 Effective（实际喂给 JVM 的字节）的内容哈希、
逐类元数据根与跨类派生结论根，并用本地 Ref 指向当前 HEAD。

它解决的是"**这一轮喂给 JVM 的到底是什么、上一轮是什么、回退时该重建什么**"这三个问题，
而不是"如何管理源码仓库"。

### 0.2 借鉴与明确不借鉴

本设计参照 Anthropic Scion 的 **Changeset WAL + Derived Data** 两层拆分：一层是不可变的变更集日志，
另一层是可由日志重建的派生数据。采纳其分层与内容寻址，**明确不采纳**：

| 维度 | Scion 式做法 | 本规格取舍 | 理由 |
|:--|:--|:--|:--|
| 部署形态 | 中心化服务端 + 客户端同步 | 嵌入式、单 JVM 内、无网络 | 热更发生在被调试/被运行的游戏进程里，没有服务端可以依赖 |
| 冲突处理 | 事后检测并修复/合并 | **事前 fail-closed 拦截** | 热更的冲突表现为 `LinkageError`/`AbstractMethodError`/错读相邻字段，一旦写进堆就无法事后合并 |
| 一致性协调 | 全局序号、租约、租约续期 | 每 loader scope 单调序号 + 单写者 | 单进程内只有一个热更批次在跑，不需要分布式协调 |
| 版本指针 | 服务端维护的 ref | **保留本地 Ref/指针** | 回退语义（§4.1）要求能在进程内直接按 Ref 找到"上一代该重放什么" |

### 0.3 明确不做（范围边界）

- **不是通用 Git 替代品**：不做分支、三方合并、cherry-pick、worktree/检出、网络传输、跨进程并发写。
- **不做事后冲突修复**：冲突必须在写入 JVM 之前拒绝，判据沿用既有安全门（`LayoutGate`、
  `RemovedMemberReaders`、`LAMBDA_STRICT`、静态性翻转、抽象方法缺失）。VCS 只负责把"这一轮判了什么、
  基于哪一代判的"记下来，不改变判据本身。
- **不做 JVM 堆倒流**：回退只恢复"字节与元数据的前向版本链"，不恢复实例字段值、静态值或已发生的
  InitFix 写入（§4.1）。
- **不改变 InitFix 作用域**：仍然只处理本次 Redefine 新增声明的字段（见仓库根 `AGENTS.md` §1）。
- **不覆盖 ART/Android 与 `$extras`**（Engine II 独立子项目）。
- **不覆盖资源/资产流**：`assetShakeMs` 路径与 Class/jar 流解耦，不进 Changeset。
- **不提供跨版本回放承诺**：回放包必须带算法与运行环境指纹，跨 JDK/ASM 版本不做逐字节等价承诺（§7.1）。

### 0.4 术语

| 术语 | 含义 |
|:--|:--|
| Changeset | 一轮热更的不可变变更集：parent 指针 + 该轮所有类的条目 + 三棵树的根哈希 |
| HEAD | 当前生效代的 Changeset 指针（每 loader scope 一条链） |
| Raw | 磁盘上的原始字节（未经对齐/织入/InitFix 修改） |
| Effective（Eff） | 该轮实际喂给 `redefineClasses` 的字节，即 Raw 经对齐、布局门、织入与 InitFix 计划后的产物 |
| Meta | 逐类元数据记录（自造名、方法映射、幽灵生前指纹、已应用字段、旧值 key 等），规范化序列化后按内容寻址 |
| Derived Data | 由 Raw + Meta 可重建的 Eff 树与派生结论；**可丢弃**、**必须可重建** |
| Ref | 本地命名指针（`HEAD`、`refs/rounds/<seq>`、`refs/pins/<name>`），不含服务端语义 |
| Staging | 一轮批次内的候选提交区；整批评估通过后才推进 HEAD |

---

## 1. 现状基线：为什么现有形态需要代际

本节只陈述代码里可核对的客观形态，作为规格的背景；各机制当前状态以 [`docs/status.md`](../status.md) 为准。

### 1.1 双树已经存在，但只有单代

`RemoldAgent` 里已经有两棵字节树：

- `bytecodeCache`：Eff 语义，存的是对齐/织入/InitFix 之后、**实际喂给 JVM** 的字节；
- `sourceBytecodeCache`：Raw 语义，存的是**磁盘原始字节**（批量路径上按类名登记）。

写入集中在单点：`RemoldAgent` 的 accept 分支（`bytecodeCache.put(...)` 与 `sourceBytecodeCache.put(...)`
相邻的那处），覆盖优先级由 `CACHE_LOADER_RANK` / `CACHE_LOADER_NAME` 旁路记录决定。

问题不在"有没有两棵树"，而在**树没有父指针、没有代际**：

- key 只有点分类名，**没有 loader 维度**；同名类被不同 ClassLoader 加载时只能靠 rank 覆盖，
  跨 loader 的元数据因此无法区分；
- 每次 `put` 原地覆盖上一轮字节，进程内没有任何地方能回答"第 N 轮喂给 JVM 的是什么"；
- 因此也无法回答"回退到第 N−1 轮时该重放什么"——这正是 §4.1 的前提。

### 1.2 逐类元数据是扁平的、无代际的

现有元数据分散在若干静态表里，都是"最近一次"语义：

| 位置 | 内容 | 现有形态 |
|:--|:--|:--|
| `RemoldAgent.inventedSlots` | 点分类名 → 该类的自造名集合 | 扁平 map，覆盖式 |
| `LambdaAligner.LAST_RESULTS` | 类名 → 最近一次 `AlignmentResult` | 有界（按访问序淘汰），**按"最近一次"而非按代** |
| `LambdaAligner.LAST_RESULT` | 单槽，供 `align` 返回后同线程立即读 | 单槽，只能在同线程同轮读 |
| `InitFix.LEDGER` | 待补字段台账（弱键、纯字符串值） | 单代集合，重启即失忆 |
| `InitFix.REINIT_APPLIED` | host 类 → 字段名 → 旧值 key | 单代，跨代会认错"旧值" |

`AlignmentResult` 本身已经携带完整的单轮快照：`oldLen/newLen/oldHash/newHash`、`inventedNames`、
`oldAnonHashes/newAnonHashes`、`methodMapping`、`alignedClass`、`bridgeCount`、`missingLvtLambdas` 与
`stats`。也就是说**单轮信息已经够用，缺的是把它按代存下来并与父代建立关系**。

### 1.3 幽灵生前指纹靠字节内埋点

孤儿 lambda/幽灵方法需要知道"这个桩生前的原始哈希"。当前做法是往方法体里写
`LDC <long>; POP2` 标记，下一轮由 `readGhostOriginalHash` 反解析，`stepGhostRevive` 据此认领；
保留体分派入口的描述符是 `RETAINED_MARKER_DESC = "(Ljava/lang/String;ZZ)V"`。

埋点的优点是**随字节走**：字节被 redefine 进 JVM 后，下一轮读回来的还是同一份，
不依赖任何进程内表。缺点是污染方法体、只能编码一个 `long`、不能表达多代历史，
且反解析必须处理"普通 `LDC long` 常量被误读成指纹"的边界。

### 1.4 四类能力缺口

综合 §1.1～§1.3，当前形态无法支持四类能力，而它们是本规格的动机：

1. **可审计**：第 N 轮真正生效的 Eff 字节与元数据是什么（出问题时要能复盘，而非"看当前 map"）；
2. **可复用**：跨轮重复的对齐/解码结果不必每轮重算（尤其是同一批类连续多轮只改一小部分时）；
3. **可原子**：一轮失败时整体丢弃候选，HEAD 不动、读侧看不到半代；
4. **可回放**：把一段 Changeset 导出，在离线进程里重放同一序列，align/transform 输出逐字节相等。

---

## 2. 数据模型

### 2.1 Changeset

一个 Changeset 是**不可变**的（提交后只读），结构如下：

| 字段 | 语义 |
|:--|:--|
| `id` | 内容寻址标识：由 parent id、按 ClassKey 排序的条目摘要、三棵树根哈希、算法版本共同决定 |
| `parent` | 上一代 Changeset id；首代为 `null` |
| `seq` | 同 loader scope 内单调递增的轮次号（从 1 开始，不复用） |
| `loaderScope` | 该 Changeset 所属的 ClassLoader 域（§2.4） |
| `rawRoot` / `effRoot` / `metaRoot` | 三棵树的根哈希（§2.2） |
| `entries` | 该轮参与热更的类：`ClassKey → (rawHash, effHash, metaHash, accepted)` |
| `baseRef` | 生成时 HEAD 指向的 Ref 名，便于诊断 |
| `buildId` | 可选的构建标识（与构建完成 marker 的 `build_id` 对齐，便于关联一次构建与一轮热更） |
| `algoVersion` | 派生算法版本（ASM/JDK 指纹 + Remold 版本 + 判据开关摘要），见 §3.2 |

约束：

- 只有 `accepted == true` 的条目才会进入 `entries`；**被拒绝（accept=false）的字节不得进入任何一棵树**，
  否则 HEAD 会与"JVM 里实际是什么"不一致（§4.4）。
- `seq` 与 `id` 一一对应；同一 parent 下不允许两个内容相同的 commit（重复写入应命中已有 id，幂等）。
- Changeset 不持有 `Class`、`ClassLoader`、`Method` 等运行时对象；只持有字符串、哈希、以及**弱**加载器身份
  （§2.4）。

### 2.1.1 提交刻度：`seq`、`HEAD` 与窗口

一轮热更**最多**产生一个 Changeset。只有通过 `VcsCommitGate` 的轮次才分配 `seq` 并推进 `HEAD`；
被评估拒绝、部分类 redefine 失败、整组回滚、`LAMBDA_STRICT` 拒绝的轮次**不产生 Changeset、
不推进 `seq`**，只在诊断侧记一条 `aborted` 事实（不含树根、不进链）。`seq` 只表示"已提交代"，
否则回退侧要额外处理第二类节点。

| 字段 | 类型 | 语义 |
|:--|:--|:--|
| `id` | 128-bit hex | `SHA-256(parentId ‖ seq ‖ 排序后的 entries 摘要 ‖ rawRoot ‖ effRoot ‖ metaRoot ‖ manifestHash ‖ algoVersion)` 截断 |
| `parent` | id 或 `null` | 同一 `loaderScope` 内上一提交；首代为 `null` |
| `seq` | long | 同一 `loaderScope` 内单调 +1，不复用（见 §5.4） |
| `roundId` | 字符串 | 本轮批次标识；跨 scope 拆分的多条 Changeset 共享它，便于把一轮热更当整体诊断 |
| `loaderScope` | long | 复用 loader 单调代号；**不持有 `ClassLoader`** |
| `entries` | 每类记录 | `ClassKey → (rawHash, effHash, metaHash, accepted)`；只含 `accepted=true` |
| `manifest` | 批次清单 | 见 §2.1.2，独立字段 |
| `replayFacts` | 字段级事实 | 见 §3.8，独立字段 |
| `rawRoot` / `effRoot` / `metaRoot` | 128-bit hex | 本轮三棵树根哈希 |
| `algoVersion` | 字符串 | ASM/JDK 指纹 + Remold 版本 + 判据开关摘要 |
| `buildId` | 字符串或 `null` | 与构建完成 marker 的 `build_id` 对齐 |

确定性口径：`id` 对 `(parentId, seq, 内容)` 确定；**同一份字节内容在不同 `seq` 上必须得到不同 `id`**
（`A → B → A` 的回改是新代，不是旧代的复用），因此不得把"内容相同"写成"`id` 相同"。

窗口与淘汰：

- Changeset 自身窗口 `M`，默认 **32**，`-Dremold.vcs.changeset_window` 覆盖。取 32 的理由是与
  `HISTORY_PER_CLASS = 8` 对齐：典型调试会话在 3～4 个互依赖类之间轮流改时（`8 × 4 = 32`），
  `M` 小于该值会出现"某类字节还在单类历史上限内、对应 Changeset 却先出窗"的错配。Changeset 不持字节、
  不持 `ClassLoader`，该窗口的堆占用可忽略。
- 两套淘汰互不替代：`HISTORY_PER_CLASS` 与 `Raw_prev` 预算决定"该类还能不能走 `Raw ↔ Raw`"，
  `M` 决定"还能查多少代"。
- Changeset 出窗后，`HEAD` 与 `parent` 链不回指它；引用它的导出包须经 `refs/pins/*` 提前固定（§5.4）。

**三级可用性**（对回退能力的显式承诺，不许含糊）：

| 级别 | 成立条件 | 允许的操作 |
|:--|:--|:--|
| `addressable` | Changeset 仍在窗口内 | 查代、查哈希、读 `manifest` / `replayFacts` |
| `replayable` | 该代 `Raw` 字节与 `Meta` 记录都可得 | 重算 Eff、离线逐字节重放 |
| `revertable` | `Eff` 字节可得（现存，或由 `Raw + Meta` 重算成功） | 运行期回退到该代 |

- `addressable` **不依赖** `AlignmentMetaStore` 的每类 8 代上限：`entries` / `manifest` / `replayFacts`
  是 Changeset 自带的轻量快照。因此某类的 per-class 条目被淘汰只把该代降级为 `addressable`，
  不破坏链的完整性。
- 回退执行时目标代必须达到 `revertable`；只有 `addressable` 时**拒绝执行并报 `NOT_REVERTABLE`**，
  不做"缺类的半吊子回退"——那会毁掉批次一致性，比不回退更危险。
- **区间回退的附加约束**：单代 `revertable` 只约束该代自身 `entries`。把 `HEAD` 回退到 `target`
  还需要把 `(target, HEAD]` 之间改过、但不在 `target.entries` 里的类还原到 `seq ≤ target` 时的状态，
  其中包含某类**首次进入链之前**的基线 `Eff_0`。因此首次纳入链的类必须在其首个 Changeset 里保留
  （或可由首次 `Raw_prev` 导出）`Eff_0`；该槽位由本规格要求预留，具体落地不在 PR1 范围。

### 2.1.2 `manifest`：批次分析上下文

`manifest` 是分析期产物的一次性快照，随 Changeset 一并固化，用于离线复现"这一轮为什么按这个顺序补"。

| 字段 | 内容 |
|:--|:--|
| `orderedHosts` | 本轮 Stage B 实际宿主顺序（`loaderScope:类名`），拓扑序 |
| `edges` | 字段级有向边：`(fromHost, fromField) → (toHost, toField)` |
| `nodes` | 参与依赖判定的字段集合；被拒者带**枚举**拒绝码，不带自由文本 |
| `candidateMissing` | 因候选类字节缺失而 fail-closed 的类名集合 |

- 与 `entries` 分家的理由：`entries` 是"每类一档"，塞入批次级数据会污染其语义，且区间回退要迁移格式。
- **空边集是合法提交**：类在 scope 内、字节确有变化、但本轮没有跨类依赖边时，照常推进 `seq`；
  否则该类的 Raw/Eff 变化没有提交坐标，后续它产生依赖时 `parent` 会断层。
- 接入点只有 `InitFix` 的 `batchCommitManifest()`（只导出字符串与枚举）。提交时刻 `BatchContext`
  仍然存活（见 §2.1.3），因此跨类收敛逻辑不需要改动。

### 2.1.3 提交时序契约

| 阶段 | 位置 | 与提交的关系 |
|:--|:--|:--|
| 批次开始 | `processChangesBatch` 建立批次字节视图 | 无 |
| 分析 | `transform*` → `setBatchDefinitions` → `finishBatchTransforms` | 产出 `manifest` 与 `replayFacts` 素材 |
| **提交点** | `commitVcsBatch` | 分配 `seq`、推进 `HEAD`、固化不可变 Changeset |
| 补丁（单阶段） | Stage B 的 `afterRedefine` 循环 | **提交之后** |
| 清视图 | `endBatchBytecodeView` | 提交之后 |
| 补丁（两阶段） | `applyTwoPhase` 内的 A→B→C | **提交之前** |

- `HEAD` 的语义固定为"**redefine 已提交（结构 + 字节）**"。两种模式的差异必须显式记录：
  附加字段 `advisory.patchTiming ∈ {BEFORE_COMMIT, AFTER_COMMIT}`，让回退侧知道该代记录时刻的字段状态
  是否已终态。
- 字段补丁结果写入独立的可变旁路（`FieldLedger` / `REINIT_APPLIED` 现状不变），**不参与** `id` 计算
  与 `HEAD` 推进。把补丁失败塞进原子提交会造出第二种失败模型。
- `applyTwoPhase` 成功时，提交点处批次上下文与 `manifest` / `replayFacts` 素材仍存活未释放；
  A→B→C 任一步失败并回滚时不得推进 `VcsCommitGate`，只记 `aborted`。
- 跨 scope：单个批次可能触及多个 loader（`newBatchBytes` 按类解析各自的 loader）。此时**按 scope 各生成
  一条 Changeset**（各自的 `parent` / `seq` 链，共享 `roundId`），原子性仍按整批判定——要么全部提交，
  要么全部不提交。

### 2.2 三棵树与 Ref

- **Raw 树**：内容寻址 blob 存储，键 = `rawHash`（128 位内容哈希，见下），值 = 原始字节。
- **Eff 树**：键 = `effHash`，值 = 实际喂给 JVM 的字节。
- **Meta 树**：键 = `metaHash`，值 = 规范化序列化的逐类元数据记录（§2.5/§3.5）。
- **Ref**：本地命名的指针表，最小集合为 `HEAD`、`refs/rounds/<seq>`（每代留痕，受预算淘汰）、
  `refs/pins/<name>`（显式固定，例如"正在被导出包引用"或"诊断现场"）。

内容哈希规则：

- 哈希算法在规格层面固定为**SHA-256 截断到 128 位**，十六进制小写表示；插入时若命中同 hash 但字节不同，
  必须做全量字节比较并把冲突记为 error（不静默覆盖）。
- 树根哈希 = 对 (相对路径/类名, blob hash) 按字典序规范化后计算；不得依赖 map 迭代顺序。
- `AlignmentResult` 里既有的 `oldHash/newHash` 是 32 位的 `Arrays.hashCode`，继续作为**兼容诊断字段**保留在
  meta 记录里，但**不得**用作树寻址键（32 位不足以做内容寻址）。

### 2.3 两级存储与预算淘汰

存储分两级，**驱逐策略不同**：

**Level-1：Head Meta（永不驱逐）**

- 每个 `ClassKey` 恰好一条 head 记录，随类第一次进入热更而建立、随该 loader 域整体卸载而整体移除。
- 内容只放"不可由字节重建、且下一轮立刻要用"的事实：当前代 `rawHash/effHash/metaHash`、自造名集合摘要、
  幽灵生前指纹摘要、已应用 InitFix 字段与其旧值 key 摘要、捕获桥登记摘要。
- 体积预算：单类 head 记录目标 ≤ 1 KiB；超出时必须**降级为哈希引用**（head 只留 `metaHash`，
  明细放 Meta 树 blob），不得让 head 随历史线性增长。
- head 不因"历史被淘汰"而失效：它只依赖当前代，不依赖任何历史 blob。
- **head 的驱逐边界**：head 只随 `ClassLoader` 被 GC 回收而消失（记录挂在弱键槽内，随槽不可达），
  **不随历史预算淘汰**。这条边界防止"反复新建/销毁临时 ClassLoader"的场景下 head 变成永久泄漏。

**Level-2：History Store（按预算淘汰）**

- 存放历史 Changeset、历史 Raw/Eff/Meta blob、`refs/rounds/*`。
- 预算：字节预算与代数预算两者同时生效（参数名与默认值见 §2.3.1）；任一超限即淘汰最老的已提交代。
- 淘汰顺序：最老的 `committed` 代优先；**跳过**被 `refs/pins/*` 固定、被当前导出任务引用、以及最近 K 代
  （K 默认 1，即至少保留上一代给"回退"用）。淘汰只减少"可回放深度"，不得改变 HEAD 语义。
- Eff blob 是**可重建的派生数据**，因此在预算紧张时 Eff 树优先于 Raw/Meta 被淘汰；重建走 §3 的 derive。
- 淘汰后 `refs/rounds/<seq>` 解析失败是合法状态：调用方必须能降级（§4.3 的字节埋点兜底）。

**2.3.1 参数与默认值（规格建议值，PR 阶段按实测 min/median/max 校准）**

| 参数 | 默认 | 语义 |
|:--|:--|:--|
| `-Dremold.vcs.enabled` | `false` | 总开关；关闭时所有读写为 no-op（§8.1） |
| `-Dremold.vcs.history_bytes` | `64 MiB` | History Store 字节预算 |
| `-Dremold.vcs.max_generations` | `64` | History Store 代数预算 |
| `-Dremold.vcs.retain_generations` | `1` | 淘汰时至少保留的最近代数（回退深度下限） |
| `-Dremold.vcs.head_meta_bytes_per_class` | `1024` | 单类 head 记录降级阈值 |

参数只允许影响"保留多少历史"，不得影响"当前代是什么"。

### 2.4 ClassKey：`(loader identity, name)`

硬约束：**class key = (loader identity, name)**，且**不得强持有 `ClassLoader`**。

```
ClassKey := (LoaderToken token, String internalName)
LoaderToken := { int id; String kind; WeakReference<ClassLoader> weak; String loaderClassSummary }
```

规则：

- `id` 是进程内单调递增的唯一代号（**不是** 32 位 `identityHashCode`；后者在大量动态加载器下会碰撞）。
- 槽本身以弱键挂在 loader 上：loader 被回收 → 槽不可达 → 该 token 名下 head/history 一并消失，
  既不需要 `ReferenceQueue` 手工清扫，也不会留悬挂条目。
- `kind` 区分 boot/app/plugin 等域（仅日志与 ref scope 划分）；相等性只看 `(id, internalName)`。
- 相等性只看 `(id, internalName)`；`internalName` 用内部名（`a/b/C`），避免点分名与内部名混用。
- loader 被 GC 后：token 进入 tombstone 状态，该 token 下所有 head 记录整体移除（不保留强引用、不留悬挂
  `WeakHashMap` 条目）；日志只记 `id + kind`，不记类名以外的对象。
- 存量代码的 key 是点分类名；迁移期允许"点分名 → 内部名"的规范化转换，但**不得**在 VCS 层同时接受两种形态
  作为不同 key（否则同一类会被记两代）。
- `CACHE_LOADER_RANK` / `CACHE_LOADER_NAME` 的 loader 维度并入 ClassKey 后语义不变：rank 仍表示同一 key 上
  多个来源的覆盖优先级。

### 2.5 Raw 与 Effective：强类型隔离

两个**名义类型**，不提供隐式互转（构造只能从显式工厂进入，转换必须经过 §2.6 的映射）：

```
RawBytes   // 只允许：读盘、算 rawHash、作为 Raw 树 blob、作为 Raw↔Raw 对齐的输入
EffBytes   // 只允许：交给 redefineClasses、算 effHash、作为 Eff 树 blob
```

不变量（任何实现都必须成立，测试必须能逐条打红）：

1. **永不用 Eff 哈希判断"源码没改"**：源码变更检测只能是 `rawHash(old) vs rawHash(new)`。
2. **永不用 Eff 当对齐输入**：Eff 是"上一轮为了让老 `CallSite` 活着而做的全部决定"的累积结果
   （自造名、幽灵桩、保留体、捕获桥），把它当旧侧配对证据会让这些决定反过来污染本轮配对。
   对齐的旧侧只能是上一轮的 Raw。
3. **活体路径是 `Raw ↔ Raw`**：`align(Raw(old), Raw(new), resolverOld, resolverNew, inventedOldNames)`；
   `inventedOldNames` 来自 head meta（缺失时回退 `inventedSlots`）。Eff 只作为
   `redefineClasses` 的输入与继承来源，不参与配对——活体侧的准入闸门、回退与继承落地见
   [`03-live-raw-to-raw-alignment.md`](03-live-raw-to-raw-alignment.md)。
4. **遗留幽灵/保留体/捕获桥从 meta/Eff 继承**，不从 Raw 推导：它们是"上一轮为了让老 CallSite 活着而做的
   决定"，Raw 里没有这些信息。继承集枚举判据与顺序约束见
   [`03-live-raw-to-raw-alignment.md`](03-live-raw-to-raw-alignment.md) §4～§5。
5. 两棵树必须**同批同源**写入：同一次 accept 判定同时写 Raw 与 Eff；不允许只写一树。
6. 被拒绝的字节不进任何树（§2.1）。

### 2.6 RawToEff：复合映射

每类每代一条 `RawToEff` 记录：

```
RawToEff := (ClassKey, rawHash, parentMetaHash, effHash, derivedSummary, algoVersion)
derivedSummary := inventedNames, methodMapping, oldAnonHashes/newAnonHashes,
                  bridgeCount, ghostOrigins, retainedBodyIds, missingLvtLambdas,
                  initfixApplied(field -> oldValueKey)
```

- 复合：`RawToEff` 可沿代际级联（`raw@n → eff@n`），但**不得**跨 `algoVersion` 级联复用
  （算法语义变了，旧结论不再等价）。
- 记录本身入 Meta 树（内容寻址）；head meta 只保留当前代的 `metaHash` 与降级所需摘要。
- `derivedSummary` 是规范化序列化（字段固定顺序、map 按 key 排序、集合按字典序），
  逐字节可比（§7.3 依赖这一点）。

---

## 3. Derived Data

### 3.1 定义与重建边界

Derived Data = 由 **Raw 树 + Meta 树 + algoVersion** 可重新计算出的:

- Eff 树（每类每代的 `effHash → EffBytes`）；
- 对齐结论（`AlignmentResult` 的规范化形式）；
- InitFix 补丁计划与 PatchReport 结论（枚举 + 字段名集合）；
- 布局/成员删除判据的判决记录（`COMPATIBLE` / 拒绝原因）。

可丢弃的部分：Eff 树、派生结论缓存、deferred 视图。
**不可重建**的部分：Raw 字节本身、head meta 指针、以及已被淘汰的历史（这就是"可回放深度"有上限的原因）。

### 3.2 缓存键与算法版本

派生缓存键 = `(rawHash, parentMetaHash, algoVersion)`：

- **不得只用 `rawHash`**：同一份 Raw 在 parent meta 不同（上一代自造名/幽灵不同）时派生出不同 Eff；
- `algoVersion` 必须包含：ASM 版本、JDK 主版本、Remold 版本、以及**影响输出的判据开关摘要**
  （如 `LAMBDA_STRICT`、`two_phase`、`init_drift` 系列）。任何一项变化 → 不跨版本复用，
  只能重算或回放（回放时版本不符直接判失败，不做"尽力而为"）。
- 缓存写入必须是"算完再写"（compute-then-publish），不得先占位后补写（否则并发读会看到半条结论）。

### 3.3 递归派生

匿名类/局部类/内部类/lambda 体的派生是递归的：

- 归属按 Nest 与内部名推导（沿用 `AnonClassHasher`/`AnonClassAligner` 的既有归属规则，不另造一套）；
- 父类/宿主类的编辑会级联触发子类重算：`oldAnonHashes/newAnonHashes` 与 `methodMapping` 必须一并进入
  子类的缓存键（即子类的 `parentMetaHash` 必须涵盖宿主代）；
- 递归必须**确定性**：同一输入、同一 algoVersion、同一遍历顺序（按内部名字典序）→ 同一输出；不得依赖
  `HashMap` 迭代序、线程调度或 wall clock；
- 递归必须有深度/环保护：Nest 关系成环或超出深度上限 → 拒绝该派生并记 warn（fail-closed），不得死循环。

### 3.4 eager 与 deferred

| 模式 | 触发 | 要求 |
|:--|:--|:--|
| eager | 本批次要 redefine 的类；InitFix 计划判定前 | Eff 字节与 PatchPlan 必须在本轮 redefine 之前确定；确定性最重要 |
| deferred | 历史代重建、导出包组装、诊断视图、离线回放材料 | 只读；**不得回写 head**；允许在下批次空闲或批后执行 |

一致性要求：同一个 `(rawHash, parentMetaHash, algoVersion)` 无论 eager 还是 deferred 生成，
结果必须**逐字节相等**。任何"eager 走 A 路径、deferred 走 B 路径"的分叉都属于规格违反。

### 3.5 与 `LambdaAligner` 的对应

`AlignmentResult` 的字段到 Meta 记录的映射：

| `AlignmentResult` 字段 | Meta 记录字段 | 备注 |
|:--|:--|:--|
| `oldHash/newHash`（32 位） | 兼容诊断字段 | 树寻址用 §2.2 的 128 位哈希；此字段只做关联 |
| `inventedNames` | `inventedNames` | head meta 必须存；下一轮作为 `inventedOldNames` 回灌 |
| `methodMapping` | `methodMapping` | 规范化按 key 排序 |
| `oldAnonHashes/newAnonHashes` | 同名 | 供子类级联与回放比对 |
| `bridgeCount` | `bridgeCount` | 计数只作诊断，不作判据 |
| `missingLvtLambdas` | 同名 | 与 `LAMBDA_STRICT` 判定强相关，必须保留 |
| `stats` | 摘要 | 统计只作诊断；`null` 表示"本轮没算出结论"，必须与"算出来是空"区分 |

按类检索语义：`LAST_RESULT`（单槽）保留"align 返回后同线程立即读"的既有语义；
`LAST_RESULTS`（按类、有界）保留给批末消费者，但其条目必须带 `seq`，消费者必须能判断"这是不是本轮"。
VCS 层在此之上提供按 `(ClassKey, seq)` 的持久化读取，作为回放与诊断的来源。

### 3.6 与 `InitFix` 的对应

- `LEDGER`（待补台账）从"单代集合"变为"**按代记账 + head 汇总**"：每条记录带 `seq`，
  head 只保留尚未闭环的项；台账职责不变——**只负责不遗忘，不负责放行**，回流字段仍走同一套安全门。
- `REINIT_APPLIED`（字段 → 旧值 key）必须按代记录：跨代覆盖会让"回退（正向 revert）后的条件 CAS"
  认错旧值，从而把用户运行期改过的值当成"未改过"或反之。规格要求：任何 CAS 的期望值必须来自
  **当前代**的旧值 key，不得来自被回退掉的代。
- `@RemoldReinit(OVERWRITE)` 例外、L1 存量初值重放、A 档 Kotlin `apply {}` 重放通道的**判据不变**；
  变化的只是"已应用"这一事实的记账粒度（按代）。
- `requiresTwoPhase` 与 PatchPlan 是**派生结论**，不持久化（可由 Raw+Meta 重算）；持久化的只有
  "已应用字段 + 旧值 key"这类堆事实。
- 写入协议不变：默认条件 CAS（`KIND_CONDITIONAL`），仅 OVERWRITE 用 `KIND_FORCE`；
  `float/double` 走 raw bits（`compareAndSetInt/Long`）。

### 3.7 派生缓存的粒度与键维度

本层缓存的是**单份 blob 的纯派生量**，而不是"整轮结论"。粒度与键维度按下表固定，任何新增派生种类
必须先在表中登记维度，再实现：

| 派生种类 | 粒度（键） | 值形态（跨轮可复用） | 判据/备注 |
|:--|:--|:--|:--|
| `anonRefs` | `(blob, 类)` | `Map<方法名+描述符, 匿名类内部名集合>` | 只由该方法体的引用目标决定，与 resolver 提供的匿名类字节无关 |
| `alignConsumers` | `(blob, 方法 name+desc)` | 按**指令流下标**对齐的消费者标记表 | `findConsumers` 的输出键是本轮解析出的 `AbstractInsnNode`，不能跨轮持有，因此换成下标；命中后在本轮新解析出的节点上按下标重建 |
| `readerTypeInfo` | `(blob, 类)` | `TypeInfo`（不可变记录） | 只读类结构，跳过代码与调试信息 |
| `readerRefs` | `(blob, 类)` | 不可变 `Ref` 列表 | 只读常量池（tag 9/10/11） |

硬约束：

- **不得缓存 `align()` 整包结果**，也不得用 `(preAlignHash, oldEffHash)` 之类"两端哈希"做键：
  对齐输出还取决于 `inventedOldNames`、外部匿名类字节、策略开关与 `LAST_RESULT` 发布副作用，
  用两端哈希会静默错配。内容寻址到**单份 blob** 则没有这个问题。
- 值的不可变性是硬要求：不得持有 `Class`/`ClassLoader`/`ClassNode`/`AbstractInsnNode`
  以及任何"本轮才有效"的可变对象。派生器在计算期内可以使用这些对象，但返回前必须剥离。
- **带语义副作用的开关必须旁路缓存**：设计级变异开关（`ACTIVE_MUTATION`）非空时一律走未缓存路径，
  否则变异档位之间的结果会互相污染。
- **算法版本维度不在此层**：本层是进程内纯派生，输入只有字节与进程内固定的算法（ASM/JDK 版本随进程固定），
  因此不需要 §3.2 的 `algoVersion`；跨版本/跨进程复用由导出包与离线回放承担（§7）。
- **预算语义**：字节预算承担内存上限，条目预算只作"小值刷屏"的兜底。条目预算必须能覆盖**一轮工作集**，
  否则 LRU 每轮都把上一轮的条目冲干净，命中率退化为 0、缓存只剩开销（实测边界见
  [`02-derived-data-cache-probe.md`](02-derived-data-cache-probe.md)）。
- **等价判据**：同一输入下缓存开/关必须①产物字节逐字节相等、②`AlignmentResult` 全字段相等、
  ③换序遍历（`TEST_REVERSE_GROUP_ORDER`）下仍相等，且用例必须证明缓存**确实命中过**（否则等价性
  可以靠"缓存从未生效"空转成立）。

---

### 3.8 提交级事实：`replayFacts` 不是派生数据

`replayFacts` 记录"某一代该字段的初值是什么、来自哪种重放档位"，用于多代初值重放（PR4-B）。
它**不能**由 `Raw + Meta` 重建（旧一代字节可能已被预算淘汰），因此属于**事实**而非派生数据，
必须随 Changeset 落盘。

| 字段 | 语义 |
|:--|:--|
| `ownerClass` | 声明类（点分名） |
| `field` / `desc` | 字段名与描述符 |
| `tier` | `L1` / `A` / `REINIT` |
| `oldLiteral` / `newLiteral` | 相邻两代初值，**两侧都记**（跨代跳读、以及"理论初值 vs 实际已应用"对账都只依赖它） |
| `literalKind` | `INT` / `LONG` / `FLOAT_BITS` / `DOUBLE_BITS` / `STRING` / `NULL` / `NONE` |
| `provenance` | `<clinit>` 切片哈希；`A` 档含唯一 `NEW` 站点标识 |
| `scope` / `mode` | 仅 `REINIT` 档：`SELF` / `INCLUDE_SUBCLASSES`、`CONDITIONAL` / `OVERWRITE` |

档位与字面量的关系（避免"非常量不产出"与 `REINIT` 档冲突）：

- `L1` / `A` 两档**要求**初值是编译期常量，必须给出 `literalKind` 与两侧字面量；
- `REINIT` 档**允许无字面量**：`literalKind = NONE`、两侧字面量为 `null`，只记录 `scope` / `mode` /
  `provenance` 供重放判据使用；
- 三档之外的非常量初始化不猜、不产出记录（该字段在本代无事实，回退侧按"无代际承诺"处理）。

字面量规范化（回退与 CAS 对账的硬约束）：

- `float` / `double` **只存原始位模式的十六进制**（`-0.0f` 记 `0x80000000`），与写入协议一致；
  不得存十进制近似，也不得依赖 `Double.toString` 的回读等价。
- **新旧两侧同一口径**：`oldLiteral` 与 `newLiteral` 都必须来自字节码里的常量本身（`A` 档取
  `newRaw`，`L1` 档取重放计划里的新侧值），而不是从任何一层渲染文本反推。只保证一侧规范、
  另一侧留十进制文本，会让该字段的多代重放整体不可用——`float`/`double` 的
  `-0.0`、NaN payload 与十进制舍入都会在往返中失真。
- **渲染文本只是人读的**：`ReplayField.newText` / `oldText` 一类的十进制渲染**不得**参与对账；
  记录里出现"位模式字段 + 十进制新侧"这种混口径形态即视为**非规范记录**，消费侧一律
  fail-closed 拒绝（落 `PENDING` + 告警），不得就地规范化、也不得回读猜测。
- `int` / `long` 存十进制；`String` 存转义后的 UTF-8 与字节长度；`null` 记 `NULL`。
- 规范口径的变更必须同步 bump `Changeset.currentAlgoVersion()`：该版本号参与 `id` 预算，
  是"这条记录属于哪种口径"的唯一判据，历史记录因此可以被识别并拒绝，而不是被当成同口径数据消费。

计算时机与纯度：

- 在**分析期**由既有的纯函数 `InitializerReplay.plan(host, diff)` 与 `ApplyInitializerReplay.plan(host, diff)`
  产出（二者只依赖宿主与 `ClassDiff`），随 `commitVcsBatch` 一并写入；
- **不得**依赖 `afterRedefine` 的写回循环，也不得在读侧重新解析旧字节——被淘汰的代必须仍能读事实；
- 运行期跳过的判定（例如 `A` 档 `dest` 为 `null`/类型不符）属于运行期结果，记入可变旁路，不进 `replayFacts`。

## 4. 三项安全约束

### 4.1 回退 = 正向 revert，绝不 reset

**语义定义**：回退到第 N 代 = 以第 N 代的 Raw/meta 为**内容基础**，在当前 HEAD 之上**新建**一个 commit：
parent = 当前 HEAD，`seq = head.seq + 1`，Eff 树重新派生。**不是**把 HEAD 指针拨回去，也不是删除后续代。

- 绝不 `reset --hard`：HEAD 只前进；历史代在预算内保留，超预算即不可回退（只能前向重建）。
- JVM 堆不可倒流：老 CallSite 的常量池引用仍指向旧名，老实例仍在堆上，静态值不会自己变回去。
  因此回退后的 Eff 树**必须保留**仍被活体引用的幽灵方法、保留体与捕获桥（判据见 §4.2）。
- 回退**不恢复**：实例字段值、静态字段值、已应用的 InitFix 写入、用户运行期修改。这些是堆事实，
  只能"记录在 meta 里并在回退代重新评估"，不能由字节重建。规格要求：回退代的 PatchReport 必须显式列出
  "属于堆事实、无法由字节恢复"的项，而不是静默假设它们回到了旧值。
- 回退的**事前拦截**：回退同样要过既有安全门——删除成员读者扫描（`RemovedMemberReaders`）、
  `LayoutGate`、静态性翻转、抽象方法缺失。**扫描未完成时一律拒绝**，不得以"反正要回退"为由放行。
- 回退的**可观测判据**（测试必须能打红）：HEAD 的 `seq` 必须单调 +1；新 commit 的 `rawRoot` 等于目标代
  `rawRoot`；`effRoot` 允许不同（因为保留体/桥会继承）；旧 CallSite 在回退后仍能解析。

### 4.2 幽灵 / 保留体 / 捕获桥的 GC

生命周期由 head meta 决定，判据如下：

- 幽灵方法存在 ⇔ head meta 中有对应 `ghostOrigin` 记录，**且**该 `ClassKey` 仍处于活体可达范围
  （loader 未卸载 + 仍在保留窗口内）；
- **不得**以"新字节里没有这个名字"作为 GC 依据：活体 CallSite 仍可能引用它，这正是保留体存在的原因；
- 保守窗口：默认保留最近 `retain_generations`（≥1）代的幽灵；被 `refs/pins/*` 固定或正在被导出包引用的代，
  其幽灵不得回收；
- GC 时机：只允许在一次成功的 redefine 之后（此时新 Eff 已进 JVM，旧代的保留需求可由新 meta 判定），
  不得在批次中途、也不得在 Staging 期间；
- 误判兜底：meta 缺失（历史淘汰 / 首次热更 / attach 前已加载类）时，仍靠 §4.3 的字节埋点认领；
  因此"meta 说可以 GC"与"字节埋点还在"是**两个独立信号**，GC 只清理 meta 侧记录与未来复用，
  **不删除**已写进旧字节里的埋点。

### 4.3 字节内埋点：降级为 fallback，不删

- 规格要求：`LDC <long>; POP2` 埋点**保留**，不删除；它从"唯一来源"降级为"meta 缺失时的兜底来源"。
- 读取顺序：先读 head meta 的 `ghostOrigin`；缺失（历史被淘汰、首次热更、attach 前已加载类）时，
  由 `readGhostOriginalHash` 反解析字节埋点，再由 `stepGhostRevive` 认领。
- 写入规则不变：注入孤儿桩体时仍必须写埋点，**不得**因为"meta 里有"就跳过写入——否则历史淘汰后无法兜底。
- 反解析判据不变：必须处理"普通 `LDC long` 常量被误读成生前指纹"的边界（`readGhostOriginalHash`
  的既有判据），不得放宽。
- 因此 §8 里路线图中"PR3 收尾删除埋点"的旧预期作废，改为"**埋点仅在 meta 缺失时兜底**"，
  并以"清空 meta 后仍能认领"作为验收用例。

### 4.4 命名空间隔离的运行时约束

- 任何"是不是同一份源码"的比较只允许 Raw↔Raw；
- 任何"进 JVM 的字节"只允许 Eff；
- 任何"下一轮 oldBytes"只允许上一轮 Eff；
- 拒绝路径（accept=false、`LinkageError` 熔断、`LAMBDA_STRICT` 拒绝、两阶段回滚）不得写入任何树；
- 读路径必须能区分"代不存在（已淘汰）"与"代存在但内容为空"——`null` 与"空集合"是两件事，
  与 `AlignmentResult` 既有的空值语义保持一致。

---

## 5. 与现有子系统的契约

### 5.1 `LambdaAligner` 插口

`LambdaAligner.align(oldBytes, newBytes, oldResolver, newResolver, inventedOldNames)` 已经带有**跨类
resolver** 参数（`Function<String, byte[]>` 形态），这是接入 commit tree 的天然插口：

- `oldResolver` / `newResolver` 由 VCS 层提供：按 `ClassKey` + 相邻代从 Raw 树取字节（注意：resolver 返回的
  是**相邻匿名类的 Raw**，用于归属推导；活体对齐的 `oldBytes/newBytes` 是该类上一轮 Raw 与本轮 Raw，
  Eff 不是对齐输入，见 §2.5 与 [`03-live-raw-to-raw-alignment.md`](03-live-raw-to-raw-alignment.md)）。
- `inventedOldNames` 由 head meta 提供；meta 缺失回退 `inventedSlots`（既有行为）。
- 灰度期存在两条跑道（`Raw ↔ Raw` 与"上一轮 Eff ↔ 本轮 Raw"兼容跑道），准入闸门、整类回退与
  "无继承集成员时两跑道逐字节等价"的口径由
  [`03-live-raw-to-raw-alignment.md`](03-live-raw-to-raw-alignment.md) §3～§6 定义；`align` 的签名与
  返回值不因跑道切换而变化。
- `align` 内部**不得**直接读全局静态表作为真值来源；表只作为兜底与迁移期兼容。
- 结论发布仍走既有 `publishResult` / `LAST_RESULTS`，但持久化由 VCS 层从 `AlignmentResult` 抽取，
  不改变 `align` 的返回值与调用方语义。
- 幂等要求：同输入、同 meta、同 algoVersion → 输出逐字节相同（这是 §7 回放等价的前提）。

### 5.2 `InitFix` 契约

- 安全门与判决分层不变（`extractFieldInits`/`checkSafe`/`effectReason`/`blacklistedCallReason`/
  `builderMutatorReason` 等）；
- 台账（`LEDGER`）多代化，但职责仍是"不遗忘"；台账字段回流后仍走同一套安全门，不因 VCS 记录而放行；
- `REINIT_APPLIED` 按代记录（§3.6），保证回退后的条件 CAS 期望值正确；
- 两阶段（`applyTwoPhase` / `redefineStageA`）的整组回滚语义不变；VCS 只在**两阶段全部成功后**推进 HEAD
  （§6.3）；
- 新增拒绝路径仍必须经 `InitFix.transform` 的统一出口发 warn（英文），VCS 不得吞掉拒绝原因。

### 5.3 `RemoldAgent` 钩子（提交点契约）

PR1 的记录是**旁路**：`LambdaAligner.align()` 只读（纯函数，不写仓），写入口由 `RemoldAgent`
在"该类最终重定义成功"之后显式提交。提交点与边界如下：

- **捕获点**：批次内 `LambdaAligner.align(...)` 返回之后，把 `(loader, oldEffective, preAlign,
  alignOutput, AlignmentResult)` 放进本批次暂存（`PENDING_VCS`）。暂存**不生效**，不得被读侧看见。
- **提交点**：批次最终结果确定之后，只在**本批所有进入重定义的类都 `redefinedOk`**、且无事务回滚、
  无 `LAMBDA_STRICT` 拒绝时，才逐类提交并清空暂存；否则整批丢弃。这是 PR1 的原子口径，牺牲覆盖率换
  "绝不把半生效批次记成当前代"。该判据抽成纯函数 `VcsCommitGate.shouldCommit(...)`，用真值表用例钉住
  （放宽成"部分生效也提交"必须变红）。
- **提交刻度与 `seq`**：通过判据的轮次才分配 `seq` 并推进 `HEAD`；两种重定义模式下"提交时刻字段是否已
  补完"并不相同，差异用 `advisory.patchTiming` 记录，完整时序见 §2.1.3。
- **两阶段**：阶段 A 走 `redefineStageA`，不经过提交点、也不更新 `bytecodeCache`；阶段 C 的最终字节由
  `applyRedefinitions` 应用。因此"只在 Stage C 成功后记一次"由结构保证，不存在 Stage A 记账。
- **`Raw` 取向（PR1 定义）**：`preAlign` = **进入 `align()` 之前的待对齐产物**（已过前置归一化
  `forceStaticLambdas` 等变换），不是磁盘原生编译产物；磁盘原始字节仍在 `sourceBytecodeCache`。
  PR1 同时记录 `preAlignHash` / `alignOutputHash` / `jvmEffHash`，PR3 换基准时不得混用。

  > **口径预留（Ctrl+Z 识别）**：将来若要识别"IDE 把源码退回原版本"，对照基线应取**进变换链前的
  > 原生 `.class` 哈希**（即 `sourceBytecodeCache` 侧的原始字节），而不是 `preAlignHash`——
  > 前置变换（如 `forceStaticLambdas`）一旦跨 agent 版本改变，`preAlignHash` 会随变换实现漂移，
  > 拿它判断"源码没变"会误判。
- **动态/外部入口**：`redefineDynamic` 不经过 `LambdaAligner.align`（它的 Lambda 对齐发生在重定义变换链里），
  PR1 不为它产生记录；读侧遇到无记录时退回字节埋点（§4.3），属安全降级而非失败。
- **失败路径**：`afterRedefineFailed`、`bytecodeCache` 回滚、批次 abort 语义全部不变；VCS 不替代它们，
  也不在失败路径写任何 head。
- **可观测事实不变**：`canRetransform=false` 组整组先于 `true` 组执行；cache 里存的是对齐/织入之后的字节。

### 5.4 Loader 维度

- Ref 按 loader scope 独立：boot / app / 各插件 loader 各自一条 HEAD 链，`seq` 各自计数；
- 跨 loader 同名类**不得**共享 Eff、幽灵、ledger、`REINIT_APPLIED`（元数据与运行时实例绑定）；
- Raw **内容 blob** 可按 `rawHash` 跨 loader 共享（同一份字节只存一份，按引用计数决定淘汰），
  但这是"blob 共享"，不是"结论共享"——两个 loader 的 Eff 可以不同；
- loader 卸载后整体移除该 scope 的 head；仍在被引用回放包/导出任务的代由 `refs/pins/*` 固定。

---

## 6. 原子 Staging 提交

### 6.1 状态机

```
（批次开始）
  → COLLECTING   收集本轮各类候选：(ClassKey, Raw, Eff, meta, 判决)
  → EVALUATING   跨类拓扑 + LAMBDA_STRICT + 两阶段评估 + 成员删除/布局判据
  → （全过）COMMITTED   写两树 + 写 Changeset + HEAD 前进（单次 volatile 发布）
  → （任一失败）DISCARDED  不写树、不前进、整批丢弃
```

约束：一轮一个 Staging；不允许嵌套；`COLLECTING` 期间读侧仍看到旧 HEAD。

### 6.2 评估项（全过才允许推进 HEAD）

1. **跨类拓扑**：匿名类/内部类归属、Nest 关系、`oldAnonHashes/newAnonHashes` 一致性；
2. **`LAMBDA_STRICT`**：含捕获 lambda 且缺 `LocalVariableTable` → 整批拒绝（不回退为"只告警"）；
3. **两阶段评估**：`InitFix.requiresTwoPhase` 命中 → 必须走 A→B→C，且阶段 C 成功；
4. **布局与成员删除**：`LayoutGate` 判决、`RemovedMemberReaders` 扫描**必须完成**；
   扫描未完成 → 拒绝（与既有规则一致，不退回存活实例判据）；
5. **编译期常量放行**等既有例外不变。

### 6.3 提交顺序与失败语义

- 顺序：评估通过 → redefine 成功（含两阶段 C）→ 写 Raw/Eff/Meta 三树 → 写 Changeset → 发布新 HEAD
  （刷新 `bytecodeCache`/`sourceBytecodeCache` 与 meta 视图）→ 释放 Staging。
- 任一失败：不写树、不前进、整批 discard；日志必须能区分"评估拒绝"与"redefine 失败"（两类原因分开记）。
- 部分类 redefine 失败的补偿：沿用 `applyTwoPhase` 的整组回滚语义；HEAD 是原子的，**不允许出现半代**。
- 提交判据的精确语义（"无关类"必须区分两种，否则 `parent` 会断层）：

| 情形 | 是否产生 Changeset |
|:--|:--|
| 字节无变化 / 不在任何 `loaderScope` / 非 Remold 管理的类 | 否 |
| 在 scope 内、字节确有变化、`manifest.edges` 为空 | **是**（空边集合法；否则该类的 Raw/Eff 变化没有提交坐标） |
| 任一类 redefine 失败或整组回滚 | 否（整批零提交，`seq` 不动） |
| 两阶段 A→B→C 任一步失败回滚 | 否 |
| 全部 redefine 成功、部分字段被安全门拒绝 | 是（结构已装，字段状态走 `advisory`） |
| 批次跨多个 scope | 每个 scope 一条 Changeset（共享 `roundId`），原子性按整批 |
- redefine 成功但树写入失败（例如预算/溢出）：视为提交失败，必须回滚到"写树之前"的状态，
  并让下一轮从旧 HEAD 继续（下一轮 `oldBytes` 仍是旧 Eff，与 JVM 事实不符时必须拒绝并 warn，
  不得静默降级）。

### 6.4 与既有不变量

- `PENDING` 弱键（`WeakHashMap<Class<?>, PendingPatch>`）与 `PatchReport` 解耦不变；
  Staging 不持有 `Class`/`ClassLoader` 强引用，只持有 ClassKey、字节与元数据；
- `LinkageError` 熔断（含 `BootstrapMethodError` 窄例外）不变；
- 未初始化守卫（`shouldBeInitialized`）不变。

---

## 7. 离线回放与验收判据

### 7.1 导出包格式

导出包是自包含的目录/归档，内容：

```
manifest.json（或等价的规范化清单）
  formatVersion, algoVersion, envFingerprint(jdk/asm/flags),
  loaderScopes[], changesets 按 seq 排序, refs{}, 
raw/  <rawHash>           原始字节 blob
meta/ <metaHash>          规范化元数据 blob
eff/  <effHash>           （可选；存在时必须与 derive 结果逐字节相等）
```

要求：

- **确定性**：清单字段顺序固定、列表排序确定（loader 按 kind/id、changeset 按 seq、blob 按哈希）；
  同一输入两次导出必须逐字节相同。
- 不含 `Class`/`ClassLoader`/方法句柄等运行时对象；loader 只以 `(id, kind, summary)` 表示。
- `eff/` 可选：导出时允许只带 Raw+Meta，由回放端 derive；若两者都在，回放必须断言一致（防止导出的 Eff
  与 Raw+Meta 不自洽）。
- 导出包必须记录被淘汰的代（`pruned` 列表），回放遇到不连续代时明确失败，不得"跳着放"。

### 7.2 回放流程

```
新进程（干净 loader scope）
  → 读闭包：从最后一棵被引用的树往前收集需要的 blob（跳过被淘汰代）
  → 按 seq 顺序重放：每轮把 Raw 序列喂给 align/transform
  → 每轮与原始运行的输出对齐比较
```

回放**不得**依赖 wall clock、GC、JIT、线程调度、类加载顺序；时间戳只作元数据。回放必须显式指定
`algoVersion` 与 `envFingerprint`，不匹配即失败（§3.2）。

### 7.3 等价判据（必须全部满足）

1. **Eff 字节逐字节相等**：每轮每个类，长度 + 全部字节相等；
2. **align 输出逐字节相等**：按 §3.5 的规范化序列化逐字节比较（不是 `toString` 比较）；
3. **transform 输出逐字节相等**：定义 `transform 输出 = (effBytes, patchPlan)`，其中 `patchPlan` 是
   InitFix 计划（字段名 + 写入类别 + 旧值 key 摘要）的规范化序列化；
4. **判决一致**：`LayoutGate` 判决、`RemovedMemberReaders` 结论（悬空/抽象缺失/分派转移/判不了/字段改指向）
   逐项相等；
5. **"关闭记录"对照**：同一序列在 `-Dremold.vcs.enabled=false` 下跑，align/transform 输出必须与
   "开启记录"逐字节相等 → 证明 VCS 是**纯观察者**，不改变语义；
6. **判定力对照（防空转）**：故意删掉一条 meta、或改掉一代 `rawHash`、或把 `algoVersion` 改一位，
   回放必须**不等价**（或按规格走可观测的 fallback 失败路径）；若仍等价，说明等价断言测不到东西，
   该断言无效，必须重写。

### 7.4 PR1 验收判据（旁路记录）

PR1 只验证"记录 / 导出 / 离线重放"这一层，不引入任何决策变化：

- **记录开启 vs 关闭**：同一真实重定义序列在 `-Dremold.vcs.enabled=true/false` 下跑，对齐输出与重定义结果
  逐字节 / 逐值相等（记录是纯观察者）。
- **整批原子**：批次中任一类回滚或 `LAMBDA_STRICT` 拒绝 → 本批零提交（`PENDING_VCS` 丢弃）；
  两阶段只记阶段 C 生效的最终字节，不出现"Stage A 代"。
- **E2E 序列**（`vcsPackProbe`）：真实 agent + 文件监听 + 真实 DCEVM redefine 跑"删除 Lambda → 加回 Lambda"
  两轮：记录开启的那一轮恰好产出一个回放条目；关闭记录的那一轮不产出新条目，且重定义照常生效。
- **两阶段 E2E**（`vcsTwoPhaseProbe`）：真实 `InitFix` 两阶段批次（新增实例字段 + 存量实例 + Lambda 删除）下，
  断言 stage B 计数器前进（证明确实走 A→B→C）、head generation 精确 `+1`（不是 0 也不是 2）、存量实例新增字段
  被补成初值、包内 `jvmEffective` 等于 agent 报告的运行期字节。
- **拒绝批次零提交**：缺 `-g` 的捕获 Lambda 在 `LAMBDA_STRICT` 下被拒（空批次、无回调），随后用一次合法改动
  作围栏批次；围栏批次正常生效且前一个被拒批次未推进任何 generation、未导出条目。
- **离线重放**：读回放包后逐条执行 `align(oldEffective, preAlign)`，输出必须与 `alignOutput` 逐字节相等，
  `inventedNames` / `methodMapping` / `bridgeCount` 契约字段相等，且 `jvmEffective == alignOutput`。
- **判定力**：把包内 `preAlign` 改一个字节、或把 `inventedNames` 少一个 → 重放断言必须变红；
  关闭记录重跑同一序列，条目数不增加（去触发对照）。
- 耗时类读数按 §7.5 口径；PR1 不声称任何加速。

### 7.5 性能口径

- 只允许声称"**跨轮重复解码可省**"（derive 命中缓存后少解码/少对齐）；不得声称端到端 redefine 更快，
  除非有同轮次单跑的完整证据。
- 任何数字必须：同轮次、单跑（不与并行任务共享 CPU/IO）、报告 **min / median / max** 三个数；
  统计量在看到数据之前选定；禁止用并行期读数。
- 若结论只对 median 成立而对 min 不成立，必须标注"口径敏感"，不得只报有利的那个。

---

## 8. 渐进路线（PR1～PR4）

每一步都能**独立合入、独立 CI**；后一步只在前一步合入后开始；每步都必须带"去触发"与"换无关输入"两类对照。

### 8.1 PR1：旁路只读 Changeset 记录（范围锁）

**只允许包含以下交付物**：

- 本规格（只写已确认契约，不写未经实测的收益数字）；
- `AlignmentMetaStore`（`remold.vcs`：弱键 loader 槽 + `long` 代号、head/history 两级、无 Class/Loader 强引用）；
- `BytecodePack` 离线回放包（`-Dremold.vcs.pack_dir`，未设置时 no-op）；
- `RemoldAgent` 成功钩子处**只读旁路提交** + 批次原子门槛（§5.3）；
- `vcsPackProbe` 端到端验收（真实 redefine → 导出 → 离线重放 + 关闭记录对照）。

**绝对不带入 PR1**：`META_SOURCE` 保持默认 `false`；不接 `InitFix` 的 `FieldLedger` / `REINIT_APPLIED`；
不做 Derived Data 缓存；不改默认对齐行为；不把本地沙箱工具链配置（如 `build/agent-env`、
跳过 JDK 11 的 `-Dhstest.requireJdks=false`）带进仓库。

**验收**：§7.4 全项 + `verifyStatusSingleSource` / `verifyDocStatusMarkers` / `verifyLogLanguage`。
**变异检查**：把 `preAlign` 记录改一字节 → 离线重放必须不等价；把批次原子门槛去掉（部分生效也提交）→
"部分失败批次零提交"用例必须变红；关掉 `remold.vcs.enabled` → 不产生任何条目且重定义结果不变。

### 8.2 PR2：Derived Data 只读缓存层

- 范围：Eff/派生结论缓存，只读命中；未命中时按既有路径重算并写入缓存（compute-then-publish）。
- 验收：cache-hit 与 recompute 的 Eff 逐字节相等；人工驱逐后结果不变；`algoVersion` 变化后不复用旧缓存；
  `refs/pins/*` 固定的代在预算淘汰下仍可读。
- 变异检查：缓存键去掉 `algoVersion` → 版本变更用例变红；缓存返回错误 blob → 等价断言变红；
  淘汰逻辑改成"连 head 一起淘汰" → head 语义用例变红。

### 8.3 PR3：`Raw ↔ Raw` 对齐灰度

- 范围：对齐改走 commit tree（resolver 与 `inventedOldNames` 从树/meta 取），保留字节埋点兜底；
  灰度开关（默认 off，支持按类白名单/百分比）。活体侧的准入闸门、继承集落地、整类回退与灰度命中
  判据以 [`03-live-raw-to-raw-alignment.md`](03-live-raw-to-raw-alignment.md) 为准，本节只锁交付范围。
- 验收：灰度 on/off 输出逐字节相等（口径：`E_prev` 无继承集成员时；有继承集成员时只要求 Run R
  自身逐轮可复现）；attach 前已加载类走 fallback 仍能认领幽灵；清空 meta 后
  `LDC <long>; POP2` 兜底可用；`LAMBDA_STRICT` 拒绝行为不变。
- 变异检查：把"meta 缺失"错误地当成"meta 命中" → fallback 用例变红；把 Raw 当 `oldBytes` →
  自造名错配用例变红；删除埋点写入 → §4.3 兜底用例变红。
- 收尾预期（相对旧预期已调整）：**不删除**字节埋点；只验证"meta 缺失时兜底"这条路径。

### 8.4 PR4：Staging 跨类原子提交 + InitFix 多代轨迹

- 范围：一轮全部类先入 Staging；跨类拓扑、`LAMBDA_STRICT`、两阶段评估全过才推进 HEAD；
  任一失败整体 discard；InitFix 台账/`REINIT_APPLIED` 按代记录。
- 验收：注入一个类失败 → HEAD 不动、无半代、下一轮读到的仍是旧代；成功路径 `seq` 单调 +1；
  回退（正向 revert）后条件 CAS 期望值来自回退代而非被回退代；两阶段 A→B→C 失败时 HEAD 不变。
- 变异检查：改成逐类提交 → 失败用例变红；失败仍写 HEAD → 变红；`REINIT_APPLIED` 退回单代 →
  回退后 CAS 用例变红。

---

## 9. 不抄 Scion 的取舍

| 维度 | 采纳 / 不采纳 | 说明 |
|:--|:--|:--|
| Changeset WAL + Derived Data 分层 | 采纳 | 不可变日志 + 可重建派生，是内存受限场景下最省的结构 |
| 内容寻址（哈希即身份） | 采纳 | 天然去重、可校验、可离线传输；128 位截断 + 碰撞全量比较 |
| 不可变变更集 + 本地 Ref | 采纳 | 回退 = 正向新 commit（§4.1）；Ref 是进程内指针，不是服务端资源 |
| 中心化服务端 / 客户端同步协议 | 不采纳 | 热更在游戏进程内发生；引入服务端只增加故障面 |
| 事后冲突检测与修复/合并 | 不采纳 | 热更的冲突必须**事前 fail-closed**；写进堆后无法合并 |
| 全局单调序号 / 租约 | 不采纳 | 单写者；按 loader scope 的 `seq` 足够 |
| 后台物化视图 / 常驻重建线程 | 不采纳（降级） | 改为 deferred derive（批间空闲、只读、不回写 head） |
| 跨机器分发 / 云存储 | 不采纳 | 导出包（§7.1）只作为离线验收与诊断载体，不是运行时依赖 |

---

## 10. 风险与不在当前范围

### 10.1 风险表

| # | 风险 | 成因 | 缓解 / 需要的证据 |
|:--|:--|:--|:--|
| 1 | 回退后"当下"不一致 | JVM 堆不可倒流；老 CallSite/老实例仍在 | 回退只承诺"未来正确"；PatchReport 显式列堆事实；§4.1 验收用例 |
| 2 | loader 卸载与 token 竞争 | `WeakReference` 可能在读写之间被清 | tombstone + 单写者 + 代际整体移除；并发用例（写入期间 GC 触发） |
| 3 | ClassKey 身份复用 | 加载器可能复用实现类/名字 | `id` 单调不复用；相等性只看 `(id, name)`；用例：同名不同 loader 不串代 |
| 4 | 历史淘汰导致不可回放 | 预算淘汰 | `LDC` 埋点兜底；导出前 pin；用例：淘汰后 fallback 仍认领 |
| 5 | Staging 期间读侧看到半代 | 提前写树/提前发布 HEAD | 单次 volatile 发布；用例：评估失败时读侧仍是旧代 |
| 6 | 跨 JDK/ASM 版本回放不确定 | 字节码库/规则集变化 | `algoVersion` 入键、入包；不匹配即失败；用例：改一位版本必须判不等价 |
| 7 | 内存双份（Raw+Eff） | 内容寻址天然冗余 | Eff 可重建、优先淘汰；预算同时限制字节与代数 |
| 8 | 幽灵 GC 误删 | 活体可达性无法精确判定 | 保守窗口 + 埋点兜底 + pin；用例：清空 meta 后仍能认领 |
| 9 | accept=false 与 HEAD 不一致 | 拒绝字节误入树 | §4.4 硬约束；用例：拒绝路径后两树与 head 哈希不变 |
| 10 | 未来并行批次 | 当前单写者假设 | 明确不在当前范围；若要做需锁或 CAS + 顺序化（§10.2） |
| 11 | 元数据规范化不唯一 | map 顺序/浮点/编码 | 规范化序列化规格（字段固定序、map 排序、UTF-8、无浮点）；双次序列化逐字节断言 |

### 10.2 不在当前范围

- 通用 VCS 能力：分支、合并、三方 diff、worktree、网络传输、多人协作；
- 跨进程/跨机器同步与分发（导出包只用于离线验收与诊断）；
- 资源/资产流的版本化；
- Android/ART 与 `$extras` 扩展槽（Engine II）；
- 自动冲突修复/合并；删除字段、修改方法签名等既有禁止项；
- 并行热更批次的并发提交（当前单写者；未来需要锁/CAS 设计）；
- 任何"端到端更快"的性能承诺（只允许"跨轮重复解码可省"，口径见 §7.5）。

---

## 参考

- 双树与写入钩子：[`RemoldAgent`](../../core/src/remold/RemoldAgent.java)
- 对齐结论与按类发布：[`LambdaAligner`](../../core/src/remold/LambdaAligner.java)、
  [`../lambda/03-publication-contract.md`](../lambda/03-publication-contract.md)
- 孤儿方法体保留（幽灵语义）：[`../lambda/02-orphan-body-retention.md`](../lambda/02-orphan-body-retention.md)、
  [`OrphanBodyRetention`](../../core/src/remold/OrphanBodyRetention.java)
- 捕获桥：[`../lambda/01-capture-bridge.md`](../lambda/01-capture-bridge.md)
- InitFix 台账与写入协议：[`02-closure-and-ledger.md`](../initfix/02-closure-and-ledger.md)、
  [`03-runtime-driver.md`](../initfix/03-runtime-driver.md)、[`InitFix`](../../core/src/remold/ref/InitFix.java)
- 两阶段与对齐不变量：[`04-target-design.md`](../initfix/04-target-design.md)、
  [`../topology/03-cascading-pipeline.md`](../topology/03-cascading-pipeline.md)
- 成员删除读者判据：[`07-p05-probe-results.md`](../initfix/07-p05-probe-results.md) §13、§15、§16
- 构建完成 marker（`buildId` 关联）：[`../runtime/01-build-trigger-mode.md`](../runtime/01-build-trigger-mode.md)
- 当前状态与验收证据：[`docs/status.md`](../status.md)
