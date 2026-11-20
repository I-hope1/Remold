# 07 · 加载前沿（Load Frontier）与出生水位线（birth watermark）

文档类型：设计规格

本文件定义"代码变更流"与"运行期加载流"分离后的记录契约，以及由此产生的两条判据：哪些结论可以跨越加载
状态保持有效，哪些必须显式降级。实现状态见 [`status.md`](../status.md)；回放包格式见
[`01-bytecode-changeset-vcs.md`](01-bytecode-changeset-vcs.md) §6；代际字段台账见
[`05-generational-field-ledger.md`](05-generational-field-ledger.md)。

## 1. 问题

Minecraft / Mindustry 这类开放世界里，类不是启动时全部加载的：玩家跑图、打开菜单都会让 JVM 懒加载一批新类。
于是同一份代码存在两套时间轴：

- **编辑时间轴**：`ChangesetLog` 的 `seq`，只在编译产物真的变化时前进。
- **加载时间轴**：某类第一次被 `ClassFileLoadHook` 看见的时刻。它与编辑无关，由玩家行为驱动，单调、不可重放。

现有回放包（`BytecodePack`）只记录前者。而 `InitFix` 的一部分判断依赖"当时世界上已经加载了哪些类"
（读者扫描范围、`HierarchyTree.knownSubtypes`、`ReinitScopeGate` 的继承边界）。因此：

> **同一个包，在两个加载状态不同的世界里回放，可能得到不同的 `FixPlan`，而包本身无法证明它复现的是哪一个。**

红基线（`tests/src/remold/vcs/LoadFrontierReplayRedBaselineTest.java`）把这一缺口钉在两条可执行断言上：
两个加载状态不同的世界导出**逐字节相同**的包；包清单里**没有任何**承载出生/加载信息的字段。

## 2. 术语

| 术语 | 含义 |
|:--|:--|
| **Edit seq** | `ChangesetLog` 的代序号，仅在编译产物变化时前进；`O(1)` 提交语义不变 |
| **Birth** | 某 `(loader, 内部名)` 在一次类加载中被 JVM 第一次看见的事件 |
| **Load Frontier** | 截至某时刻，已经发生过 Birth 的集合；单调只增 |
| **birth watermark** | 某一代 `seq` 提交时，Load Frontier 已推进到的位置（用于回放对齐） |
| **LoadedWorld** | `(birth watermark, 该水位线下已见的类集合)` 的合称 |

## 3. 不变量

- **L1（双轨正交）**：Birth 永不创建 `Changeset`。加载噪声不得进入编辑时间轴，也不得改变 `seq` 的取值。
- **L2（零解析入队）**：Birth 发生时只允许写入 `(内部名, eff 字节哈希)` 这样的标量元组；禁止在加载路径上解析
  字节码、计算摘要或做任何跨类分析。分析只能发生在队列 flush 时。
- **L3（flush 边界）**：队列在**下一次真实 `seq` 提交之前**被 flush 成索引增量；flush 之外不得读该索引。
- **L4（有界与显式记账）**：队列与历史索引都必须有上限。触限丢弃必须计数并写入当轮清单；
  `droppedBirths > 0` 的那一代，所有"不可变/可覆写"结论一律禁止，只允许 `OPAQUE_GUARDED`（见 §6）。
- **L5（保守退化）**：任何遗忘（裁剪、丢弃、loader 卸载）只允许把结论推向更保守的方向。裁剪之后该字段的
  结论从"可证明不可变"退化为 `OPAQUE_GUARDED`，并累计 `forgottenWriters`；"忘了但当作没发生"是禁止的。
- **L6（裁决面不变）**：本机制**不引入**任何自动覆写路径。字段裁决仍然只有三种：`GUARDED`（条件 CAS）、
  显式 `@RemoldReinit(OVERWRITE)`、`private`/`NestView` 不可变证明。写点分析最多能把某次裁决**显式地**
  升级为一次覆写决定，永不成为默认值。

## 4. 数据模型

### 4.1 清单水位线

每轮 `seq` 的清单增加一个出生水位线：

```
births = <prevWatermark>..<currWatermark>
```

它只回答一个问题：**这一代提交时，作者可见的世界推进到了哪里**。它不记录类的字节，字节仍由条目承担。

**进入内容寻址前置像，必须版本化。** `Changeset.id` 的前置像目前包含
`manifestDigest(orderedHosts, edges, nodes, candidateMissing)`；加入 `births` 会让新代 id 变味、旧代 id 不变，
形成混血历史。因此：

- `algoVersion` 升到 `vcs=3`，只有 `vcs>=3` 的代才把 `births` 纳入 `manifestDigest`；
- `BytecodePack.FORMAT_VERSION` 升到 `2`；
- **回放端遇到 v1 包或混血链时必须显式拒绝**（`REFUSED_LEGACY_PACK` / `REFUSED_MIXED_FRONTIER`），
  不得猜测、不得按"没有 births 就当空集"放行——那等价于伪造加载状态。

### 4.2 身份与生命周期

- 存活期身份沿用现有槽位：`WeakHashMap<ClassLoader, LoaderSlot>` + 单调 `long loaderId`；
- 需要跨卸载记忆的部分只存字符串：`loaderId + loaderClassName + codeSource` 三元组。
  不依赖 `ClassLoader.getName()`（大量自定义 loader 返回 `null`）；
- 极端情况下两个不同 loader 撞到同一字符串身份，只会让结论更保守（见 L5），不会放宽安全门。

### 4.3 保留

历史索引按 `ChangesetLog.window()` 同源裁剪。裁剪动作本身必须：①留下 `forgottenWriters` 计数；
②使受影响键的结论立即降级为 `OPAQUE_GUARDED`；③在状态面可观测（不允许静默遗忘）。

降级是**构造性**的，不需要额外的"锁死"标志：更早的代一旦离开台账，后续任何新事实都无法再证明跨代连续性，
于是解析结论停在 `REFUSED_DISCONTINUOUS`，判决只能拒。`forgottenWriters` 的作用是让这件事**可归因**
（否则"找不到事实"会被读成"从来没有事实"），而不是承担锁死职责。

### 4.4 裁剪态如何进入离线回放

`forgottenWriters` 目前只活在进程内台账（`GenerationalFieldLedger` / `ScopeVerdicts`），回放包
（`BytecodePack` v2）的 manifest 只带 `droppedBirths` / `unresolvedBirths`。因此线下回放一个"线上已经裁过"
的包时，无法从包本身区分"这条键从没有事实"与"这条键的事实被裁掉了"。

**口径（不改包格式）**：离线回放必须使用与线上**同一窗口**（`remold.vcs.changeset_window`）重算裁剪，
并在回放开始时校验窗口一致；窗口不一致即拒绝回放并报出两个窗口值，不允许"用更宽的窗口回放一个被裁过的
历史"再拿结论去解释线上行为。这样裁剪态是**可重算**的，包格式不需要携带键级遗忘信息。

若将来需要"包自证裁剪态"，走 manifest 增加每轮标量增量的路线（格式升版并对旧版显式拒绝），
而不是把被遗忘键列表塞进每轮 manifest——后者会让常规提交路径随裁剪量变重。

## 5. 出生点补丁（PendingLoadPatch）

"编辑早于加载"是独立于 VCS 的缺口：`seq 1` 改了尚未加载的类，`redefineClasses` 当时无事可做；之后它首次加载时，
若加载器给出的是陈旧字节，那次编辑就静默失效。红基线
`tests/src/remold/BirthPatchTest.java` 记录当前形态——出生点 transformer 永远返回 `null`
：出生点曾把 stale buffer 原样放行并记进运行时基线缓存，连后续 diff 的基线一起污染。

补丁语义：对"构建输出已更新、但类尚未加载"的 `(loader, class)` 保留一份待消费记录；在该类出生时用新字节替换。

**三条硬护栏**（缺一不可）：

1. **归属校验**：必须用 `ProtectionDomain`/`CodeSource` 判定该类确实属于工作区；工作区之外的类（第三方 jar、
   JDK）一律不替换。
2. **织入冲突 fail-closed**：Fabric 的 `KnotClassLoader` / Forge 常常在进入本 agent 的 `ClassFileLoadHook`
   **之前**就完成了 Mixin 织入。出生点补丁不得用工作区 Raw 字节覆盖已织入的类；命中 Mixin 目标类时
   **fail-closed**：要么不替换（保留上游织入结果），要么走既有织入通道（`MixinBytecodeOverlay`）。
   不允许"因为类名对得上就替换"。
3. **记账生效字节**：替换生效后，`CacheWarmingTransformer` 与 LoadFrontier 必须记录**替换后的生效字节**，
   而不是原始 `classfileBuffer`；同时把该 `(loader, class)` 的 pending 标记为已消费，避免重复替换。

## 6. 结论的三态可见性

| 问题 | 事实源 | 边界 |
|:--|:--|:--|
| 过去有没有被改过（写点） | 已加载单调 Eff 流 + 批次按需的工作区 Raw 索引 | **封闭**：未加载的类不可能执行过字节码 |
| 将来会不会有未加载子类/调用方 | 开放世界 | **开放**：不得把"当前没看到"缓存成永久事实 |

关于"封闭"的边界必须写清：`LibTool` 这类 JNI/native 代码可以改堆而不产生任何 Java 栈帧，因此
"写点 ⊆ 已加载类"的闭包**不覆盖 native 与其它 agent**。结论：写点分析只能作为**显式升级**的证据，
不能作为默认裁决（L6）。

## 7. 验收要求

1. 红基线 A、B 由"断言现状"整体反转为"断言缺陷已消失"；
2. 回放确定性门禁：同一包两次回放逐字节一致；两个加载状态不同的世界**必须**导出不同的包；
3. 变异检查至少覆盖：把 `births` 从 id 前置像摘掉、flush 时机改到 seq 之后、丢弃不记账、
   裁剪不降级、出生点补丁跳过归属校验、跳过织入冲突校验——每一条都必须让对应用例变红；
4. 队列与索引在触限、裁剪、loader 卸载后的内存表现要有读数（含强持有对照），不得只断言计数器。
