# 战报 · 战役 battle-20261117104452-e8de

> **文档类型**：审计记录（战役实证战报；全局实现状态以 [docs/status.md](../status.md) 为准）

> 生成时间 2026-11-17T14:30:30.904Z · 阶段 integrating · 基线 feat/pr2-slice3-initfix@50b2c7c0f420 · 证据 42 条（已作废 1）

## 1. 目标
PR2 slice 3（InitFix 侧）：先用探针量化 InitFix 三项热点（AliasInterpreter 数据流、HierarchyTreeOracle/NestView 跨类字节读取广度、ReinitScopeGate 触发频次），按读数决定是否做前置剪枝/派生缓存；只缓存不可变纯数据，绝不缓存 ClassNode/AbstractInsnNode/Class；缓存与剪枝开/关必须行为完全等价

## 2. 批次结果
| 批次 | 标题 | 耦合 | 状态 | 提交数 | 探针(通过/终态) | 红基线 | 变异(击杀/总) | 合流 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | 侦察测绘批（串行排他，不改生产缓存逻辑）：InitFixScanProbe 三项分解读数 + 频次分布 | high | merged | 1 | 2/2 | 0 | 0/0 | ✓ |
| D | 批次级单次堆遍历（Batch D）：把同一批 K 个类的实例收集从 K 次全堆扫描收敛为 1 次，并审计 hasLiveInstance 惰性探测 | high | merged | 10 | 7/7 | 1 | 6/9 | ✓ |

### 2.1 台账口径
- **终态探针**：`expect_exit=0` 的活跃探针，计入 `通过/终态` 分母；同一 batch+label 只有最后一条是终态。
- **红基线**：`expect_exit≠0`，只证明“期望失败确实发生”（TDD 红基线），单独计数，**不进终态分母**。
- **已作废**：`battle_invalidate` 排除；**SUPERSEDED**：被同 batch+label 的后续证据覆盖，均不计入终态。

### 2.2 结构变更摘要（git diff --numstat，客观事实）
| 批次 | 文件 | +/- | 标记 |
| --- | --- | --- | --- |
| A | tests/build.gradle | +19 / -0 | ⚠ 测试/构建/CI |
| A | tests/src/InitFixScanProbe.java | +805 / -0 | ⚠ 测试/构建/CI |
| D | CHANGELOG.md | +52 / -0 |  |
| D | core/cpp/tool.cpp | +533 / -0 |  |
| D | core/src/remold/RemoldAgent.java | +102 / -14 |  |
| D | core/src/remold/ref/InitFix.java | +119 / -0 |  |
| D | core/src/remold/util/LibTool.java | +177 / -0 |  |
| D | docs/initfix/05-jvmti-heap.md | +80 / -0 |  |
| D | docs/status.md | +1 / -0 |  |
| D | tests/build.gradle | +121 / -2 | ⚠ 测试/构建/CI |
| D | tests/src/BatchHeapScanEquivalenceTest.java | +281 / -0 | ⚠ 测试/构建/CI |
| D | tests/src/InitFixBatchScanProbe.java | +967 / -0 | ⚠ 测试/构建/CI |
| D | tests/src/InitFixScanProbe.java | +805 / -0 | ⚠ 测试/构建/CI |
| D | tests/src/remold/BatchLivenessPrefetchTest.java | +127 / -0 | ⚠ 测试/构建/CI |

> ⚠ 有 7 个测试/构建/CI 相关文件被改动：主指挥官应追问是否削弱了断言或门禁（diffStat 只陈述事实，不做裁决）。

## 3. 不变量证据矩阵
| 不变量 | 声明 | 覆盖证据 |
| --- | --- | --- |
| C1 | 先读数后写码：先产出 InitFix 侧三项分解基线（min/median/max + 频次分布），确认热区占比后才进优化批；所有测速探针串行排他运行，严禁并发 Gradle 污染耗时读数 | 202611171059, 202611171132, 202611171401, 202611171430 |
| C2 | InitFix 五条核心不变量零退化：不动 UNSAFE.shouldBeInitialized 守卫、缓存与产物零 Class/ClassLoader 强引用、LinkageError 熔断边界不变、FieldLedger 回滚/重试一致 | 202611171423, 202611171430 |
| C3 | 优化/缓存开与关行为完全等价：同一输入语料下 PatchReport（实例/静态/replay 字段的 status、reason、warnings）与补丁产物字节、写入行为逐项等价 | 202611171423, 202611171424, 202611171430 |
| C4 | 前置剪枝不得制造静默漏判：类似 hasConsumerSite() 的快速跳过必须证明覆盖全形态（继承/嵌套/跨包/@RemoldReinit/抽象方法/静态性翻转），不允许只认单一调用形态 | 202611171423, 202611171430 |
| C5 | 反空转绿与变异必杀：探针必须证明缓存确实命中（防关闭即绿）；每条缓存键维度、每条剪枝边界、每处消除重复读字节的逻辑都必须被 battle_mutation 逐一击杀 | 202611171424 |
| D1 | 多态子类/接口等价性绝不退化：堆遍历的 class_tag 只认对象精确运行时类（不认父类）。严禁只给 targets 打标而漏掉未列入批的已加载子类/接口实现类（否则静默破坏 @RemoldReinit(INCLUDE_SUBCLASSES) 与 InitFix 子类覆盖契约）；Sub extends Base 同批出现时 Sub 实例必须同时落入两者结果并正确进入 StageBInstanceCache；位图映射类归属不得死上限 64 类，超限必须自动分块或退回 | 202611171217, 202611171219, 202611171228, 202611171229, 202611171351, 202611171354, 202611171355, 202611171401, 202611171424, 202611171430 |
| D2 | Lifecycle / Native 安全零破口：收集前经 ClassIdentity.canonicalize 并剔除 !InitFix.isInitialized(c)；保留基本类型镜像与数组类拦截、保留 g_heap_mutex 原生锁（禁用 JVMTI RawMonitor）；临时 class_tag 必须完整保存/恢复或清零，不留脏 tag 污染 JvmtiTagMap；K=1 时直走原单类路径零额外开销；无 native 时 InstanceTracker 降级路径正常 | 202611171217, 202611171219, 202611171219, 202611171220, 202611171228, 202611171229, 202611171230, 202611171351, 202611171354, 202611171355, 202611171401, 202611171424, 202611171430 |
| D3 | 审计 requiresTwoPhase 里的 hasLiveInstance 惰性探测：同一批 50 个零实例类仍会触发 50 次全堆扫描；必须明确一并批探测，或给实测证明其不在热路径；若一并改掉，Stage A 前后状态必须不变 | 202611171219, 202611171229, 202611171351, 202611171354, 202611171355, 202611171401, 202611171424, 202611171430 |
| D4 | 真实活实例测试 + 排他读数 + 变异击杀：Batch A 只有零存活夹具，D 不许只测零实例。等价夹具必须含不在 targets 里的存活子类/实现类、@RemoldReinit(SELF vs INCLUDE_SUBCLASSES)、零/非零混居，断言批收集与逐类 getInstances 集合逐项一致；K=1/16/64 下的 collect 与全流程 min/median/max 排他读数并核验堆遍历次数 K→1；至少 2 个变异击杀（丢子类→多态用例红；退化逐类→计数断言红） | 202611171217, 202611171228, 202611171351, 202611171401, 202611171424, 202611171430 |

### 3.1 变异语义锚点（变异点 → 变红用例 → 不变量）
| 批次 | 变异点 | 变红用例 | 不变量 |
| --- | --- | --- | --- |
| D | core/cpp/tool.cpp: if (targets[i] && env->IsAssignableFrom(cls, targets[i])) ma…… | > Task :tests:test FAILED<br>BatchHeapScanEquivalenceTest > subclassInstancesLandInBothAncestorAndOwnBucket()… | - |
| D | core/src/remold/ref/InitFix.java: 			collected = LibTool.getInstancesBatch(targe… | === INITFIX BATCH SCAN PROBE: 7 CHECK(S) FAILED ===<br>> Task :tests:initFixBatchScanProbe FAILED | - |
| D | core/src/remold/RemoldAgent.java: 		prefetchBatchLiveness(); 		cached = LIVE_INS… | > Task :tests:test FAILED<br>BatchLivenessPrefetchTest > oneBatchWalkCoversTheWholeRound() FAILED | - |
| D | core/cpp/tool.cpp: if (targets[i] && env->IsAssignableFrom(cls, targets[i])) ma……<br>core/src/remold/util/LibTool.java: 		if (batchScanSupported) { 			boolean done =…<br>core/cpp/tool.cpp:     popEnumFrame(); →     // mutation: keep the enumeration f… | > Task :tests:test FAILED<br>BatchHeapScanEquivalenceTest > subclassInstancesLandInBothAncestorAndOwnBucket()… | D1, D2, D4 |
| D | core/cpp/tool.cpp:     // 枚举结束：临时类引用已经全部处理完（命中类已转全局引用），立即收起局部帧，     // 让后续 GetO…… | === INITFIX BATCH SCAN PROBE: 8 CHECK(S) FAILED ===<br>> Task :tests:initFixBatchScanProbe FAILED | D2, D4 |
| D | core/src/remold/ref/InitFix.java: 		if (!batchHeapScanEnabled) return; → 		if (f… | === INITFIX BATCH SCAN PROBE: 1 CHECK(S) FAILED ===<br>> Task :tests:initFixBatchScanProbe FAILED | C3, C5 |

## 4. 实证台账（压缩）
- [ev-20261117105944-96c3] probe 批次 A · exit 0 · PASS · stdout 721b663670e5
- [mrg-20261117112558-c884] merge 批次 A · CONFLICT · 
- [mrg-20261117112623-32aa] merge 批次 A · OK · ffe483b
- [ev-20261117112707-d213] probe 批次 A · exit 0 · SUPERSEDED · stdout 7a50b99ea166 · SUPERSEDED（被 ev-20261117113138-c14e 覆盖）
- [ev-20261117112731-ec47] probe 批次 - · exit 1 · SUPERSEDED · stdout 34da3eed97f2 · SUPERSEDED（被 ev-20261117143009-3471 覆盖）
- [mrg-20261117113054-b493] merge 批次 A · OK · 4bf84e9
- [ev-20261117113138-c14e] probe 批次 A · exit 0 · PASS · stdout 91a2eef32ec0
- [ev-20261117113234-c7a4] probe 批次 - · exit 0 · SUPERSEDED · stdout 7d179f127917 · SUPERSEDED（被 ev-20261117143009-3471 覆盖）
- [ev-20261117121735-c570] probe 批次 d · exit 0 · PASS · stdout 7ab467827fc3
- [ev-20261117121748-8b58] probe 批次 d · exit 0 · PASS · stdout 6797204172e9 · **已作废**（探针 up-to-date 空转证据（构建未重跑，仅有 Task up-to-date 输出），不构成真实执行证据；由后续 fresh 探针替代）
- [ev-20261117121921-2b3a] probe 批次 d · exit 0 · PASS · stdout 63270bdcc65b
- [ev-20261117121943-6641] probe 批次 d · exit 0 · PASS · stdout 9f0ae7851a05
- [ev-20261117122031-47ee] probe 批次 d · exit 0 · PASS · stdout d5fb522cf7b2
- [mut-20261117122130-3905] mutation 批次 D · killed · 基线 0 → 变异 1
- [mut-20261117122154-3e62] mutation 批次 D · killed · 基线 0 → 变异 1
- [mut-20261117122413-6690] mutation 批次 D · killed · 基线 0 → 变异 1
- [ev-20261117122825-7c78] probe 批次 d · exit 0 · PASS · stdout 93e43e1ffa4a
- [ev-20261117122922-04b8] probe 批次 d · exit 0 · PASS · stdout 4fb5d8f401c6
- [ev-20261117123012-cdd3] probe 批次 d · exit 0 · PASS · stdout c5cde25accbe
- [ev-20261117132003-e09c] probe 批次 D · exit 1 · BASELINE · stdout 749e8ead410b · BASELINE（expect_exit=1，不计终态分母）
- [mut-20261117134257-1073] mutation 批次 D · killed · 基线 0 → 变异 1 · 不变量 D1,D2,D4
- [mut-20261117134320-abb4] mutation 批次 D · survived · 基线 0 → 变异 0 · 不变量 D1,D4
- [mut-20261117134350-fbab] mutation 批次 D · killed · 基线 0 → 变异 1 · 不变量 D2,D4
- [ev-20261117135156-d459] probe 批次 D · exit 0 · SUPERSEDED · stdout eff9cf80bff1 · SUPERSEDED（被 ev-20261117142442-3a60 覆盖）
- [ev-20261117135214-c008] probe 批次 D · exit 1 · SUPERSEDED · stdout ee95a6aabe4c · SUPERSEDED（被 ev-20261117135500-883c 覆盖）
- [ev-20261117135445-6250] probe 批次 D · exit 0 · PASS · stdout 4ea51c0713b1
- [ev-20261117135500-883c] probe 批次 D · exit 0 · PASS · stdout a2ace2b92945
- [mrg-20261117135558-7902] merge 批次 D · OK · 3a485ef
- [ev-20261117135658-939d] probe 批次 D · exit 0 · SUPERSEDED · stdout ec8785290e1c · SUPERSEDED（被 ev-20261117142555-8e5a 覆盖）
- [ev-20261117135733-84ce] probe 批次 D · exit 0 · SUPERSEDED · stdout eed24ce1d23c · SUPERSEDED（被 ev-20261117142629-f61a 覆盖）
- [ev-20261117135752-f323] probe 批次 D · exit 0 · SUPERSEDED · stdout 421a9395289d · SUPERSEDED（被 ev-20261117142641-0bec 覆盖）
- [ev-20261117140139-184c] probe 批次 - · exit 0 · SUPERSEDED · stdout cdfd9cf09761 · SUPERSEDED（被 ev-20261117143009-3471 覆盖）
- [mut-20261117141306-d026] mutation 批次 D · survived · 基线 0 → 变异 1 · 不变量 C3,C5,D3
- [mut-20261117141635-ffe5] mutation 批次 D · survived · 基线 0 → 变异 0 · 不变量 C3,C5
- [mut-20261117141921-56a0] mutation 批次 D · killed · 基线 0 → 变异 1 · 不变量 C3,C5
- [ev-20261117142347-c7e3] probe 批次 D · exit 0 · PASS · stdout 382550f1e695
- [ev-20261117142442-3a60] probe 批次 D · exit 0 · PASS · stdout b1e400fe1228
- [mrg-20261117142452-1660] merge 批次 D · OK · be2b161
- [ev-20261117142555-8e5a] probe 批次 D · exit 0 · PASS · stdout 17ae15245795
- [ev-20261117142629-f61a] probe 批次 D · exit 0 · PASS · stdout ef30e477b733
- [ev-20261117142641-0bec] probe 批次 D · exit 0 · PASS · stdout 4ab6b00f65be
- [ev-20261117143009-3471] probe 批次 - · exit 0 · PASS · stdout 2c9c2b783264

## 5. 机械缺口与风险 (0)
- 无

## 5.1 已接受局限 / 挂账债务 (5)
- 批次 A 的 require_mutation=false 理由：A 是纯测量批次（只新增 tests/src/InitFixScanProbe.java 与一个 Gradle JavaExec 任务，零产品代码改动），没有可反向移除的实现，变异检查对测量工具不适用；测量工具本身的有效性由探针 5 条自检（afterRedefine 成功、门必须拒绝、Nest 必须被读、perf 日志必须产出、JFR 归因占比>50%）与两次‘测量假象被抓住并修正’的记录承担。
- tests/build.gradle 的改动不削弱任何门禁：批次 A 新增一个 JavaExec 任务；批次 D 把测试用的 native 库改为‘由当前源码编译’并加入 test 任务 inputs（+121/-2 的两行删除就是旧的固定发布产物路径），目的是让‘改 tool.cpp → 用例变红’这条变异链真的成立；测试数下限、日志语言、状态单一真相源三条守卫均已跑绿。
- C2~C5 的口径：主指挥官按批次 A 读数撤销了 B/C 两批（扫描侧前置剪枝与派生缓存均未实施），因此 C3/C4/C5 按‘批次收集通道’的同义口径验证——C3 = 批收集开/关的 PatchReport 与写入结果逐项等价（探针 [6]，含关掉后遍历次数回到 K 次的反空转对照）；C4 = 归类全形态覆盖（未列子类、孙类、接口实现、零实例混居、K=64 分块、20 万无关对象大堆）；C5 = 5 次变异击杀（丢子类、批收集退化逐类、恢复修复前局部帧生命周期、存在性探测退化逐类、无视开关）+ 探针自身的‘必须真的打了补丁’断言。若按字面要求扫描侧缓存/剪枝的等价性，本战役没有对应实现，需另开批次。
- core/libs/*.so 未随本批重编（只有源码与测试内编译的探针库）：未重编平台上的发布产物缺 nGetInstancesBatch/nHasLiveInstancesBatch，运行期会整体退回逐类枚举——行为正确、只是没有性能收益；三平台产物重建与校验留给发版流水线。
- 接合阈值与分块的实测局限：InitFix.BATCH_HEAP_SCAN_MIN_TARGETS=8 取自本机 aarch64/JDK 25 的 K=4/8/16 交点，换堆规模或平台需复测；实例收集单块上限 16（检索 tag 预算）会让 K>16 时遍历次数为 ceil(K/16) 而不是恒为 1。

## 6. 下一步
- 可执行 battle_audit 复核；通过后 battle_seal 结案。
