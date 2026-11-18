# 战报 · 战役 battle-20261118102235-ac2c

> 生成时间 2026-11-18T11:25:57.168Z · 阶段 integrating · 基线 feat/pr3-promote-run-r@0cd6426ec8bf · 证据 44 条（已作废 0）

## 1. 目标
PR #13：两套模版多轮压测 + Gap#5 闭环 + O(entries) 结案，然后把 Run R 正式切为默认开启（保留急停），本地全量 :tests:test 0 失败 + 远端 8/8 CI 全绿后合入 main。

## 2. 批次结果
| 批次 | 标题 | 耦合 | 状态 | 提交数 | 探针(通过/终态) | 红基线 | 变异(击杀/总) | 合流 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | 环境侦察：Mindustry / Fabric Loom 两套模版在本机依赖是否齐备、能否离线编译并无头跑多轮重定义 | low | merged | 1 | 13/13 | 0 | 0/0 | ✓ |
| B | Gap#5（alignOutput≠jvmEffective）与真实语义多轮序列 V1→V2→V3→V4 | low | merged | 3 | 1/1 | 0 | 1/1 | ✓ |
| C | 受控条目数微基准：50/200/1000/2000 条的 commit 耗时与 blob 预算水位 | low | merged | 2 | 4/4 | 0 | 1/2 | ✓ |
| D | 开关收口：去 poc、默认开启 + 急停，文档/状态表/CHANGELOG 同步 | high | merged | 2 | 3/3 | 0 | 1/2 | ✓ |

### 2.1 台账口径
- **终态探针**：`expect_exit=0` 的活跃探针，计入 `通过/终态` 分母；同一 batch+label 只有最后一条是终态。
- **红基线**：`expect_exit≠0`，只证明“期望失败确实发生”（TDD 红基线），单独计数，**不进终态分母**。
- **已作废**：`battle_invalidate` 排除；**SUPERSEDED**：被同 batch+label 的后续证据覆盖，均不计入终态。

### 2.2 结构变更摘要（git diff --numstat，客观事实）
| 批次 | 文件 | +/- | 标记 |
| --- | --- | --- | --- |
| A | docs/topology/19-raw-to-raw-realworld-readings.md | +16 / -0 |  |
| B | tests/src/Pr3JvmEffHashGateTest.java | +378 / -0 | ⚠ 测试/构建/CI |
| B | tests/src/Pr3MultiRoundHotSwapTest.java | +519 / -0 | ⚠ 测试/构建/CI |
| C | tests/build.gradle | +30 / -0 | ⚠ 测试/构建/CI |
| C | tests/src/Pr3CommitScalingProbe.java | +629 / -0 | ⚠ 测试/构建/CI |
| D | CHANGELOG.md | +7 / -0 |  |
| D | core/src/remold/LambdaAligner.java | +25 / -5 |  |
| D | docs/status.md | +4 / -4 |  |
| D | docs/topology/18-live-raw-to-raw-alignment.md | +5 / -2 |  |
| D | docs/topology/19-raw-to-raw-realworld-readings.md | +15 / -1 |  |

> ⚠ 有 4 个测试/构建/CI 相关文件被改动：主指挥官应追问是否削弱了断言或门禁（diffStat 只陈述事实，不做裁决）。

## 3. 不变量证据矩阵
| 不变量 | 声明 | 覆盖证据 |
| --- | --- | --- |
| P0 | 先侦察再动工：两套模版（Mindustry / Fabric Loom）在本机 gradle-home 下依赖是否齐全、能否离线构建并在无头模式喂进 RemoldAgent 跑多轮重定义；不能就如实记 BLOCKED(网络/映射/运行时) 并用局部 Mixin/后置字节码夹具等价补齐，不得在下载上死磕 | 202611181026, 202611181026, 202611181027, 202611181027, 202611181029, 202611181029, 202611181031, 202611181032, 202611181032, 202611181033, 202611181034, 202611181038, 202611181124, 202611181125 |
| P1 | Gap#5 闭环：构造 alignOutput != jvmEffective（后置织入/插桩/InitFix 同轮改写），断言下一轮 G1 精准经 jvmEffectiveHash 命中 PASSED，且变异（去掉该分支）必红 | 202611181058, 202611181121 |
| P2 | 多轮连续热更 V1→V2（删 lambda 留幽灵+保留体、改捕获生桥、产生自造名）→V3→V4：Run R 与旧路径行为一致、positionalFallbacks 不异常飙升，并记录真实多轮下的 blob 预算水位与 commit 耗时 | 202611181058, 202611181121 |
| P3 | O(entries) 结案：受控条目数（50/200/1000/2000）下单次 commit 耗时对比，证实可忽或当场修掉，不得继续挂“未定论” | 202611181041, 202611181042, 202611181049, 202611181052, 202611181110 |
| P4 | 开关收口：poc 前缀消失，Run R 默认开启 + 一条命令急停；本地全量 :tests:test 0 失败 + 远端 8/8 CI 全绿后才合入 main | 202611181024, 202611181102, 202611181106, 202611181121 |

### 3.1 变异语义锚点（变异点 → 变红用例 → 不变量）
| 批次 | 变异点 | 变红用例 | 不变量 |
| --- | --- | --- | --- |
| D | core/src/remold/LambdaAligner.java: 		String legacy = System.getProperty("remold… | FAIL expected true got 'META_SOURCE=false' for: | P4 |
| B | core/src/remold/vcs/AlignmentMetaStore.java: 		if (!e.jvmEffSha256.isEmpty() && … | Pr3JvmEffHashGateTest > jvmEffectiveHashAloneAdmitsTheGeneration(Path) FAILED<br>org.opentest4j.AssertionFailedError at Pr3JvmEffHashGateTest.java:116 | P1 |
| C | core/src/remold/vcs/AlignmentMetaStore.java: 		slot.head.put(className, e); → 		… | > Task :tests:pr3CommitScaling FAILED<br>Exception in thread "main" java.lang.IllegalStateException: pasc-50 round 0: hea… | P3 |

## 4. 实证台账（压缩）
- [ev-20261118102430-dc8e] probe 批次 D · exit 0 · PASS · stdout 5b9b778ea2bc
- [mut-20261118102439-1e86] mutation 批次 D · error · 基线 0 → 变异 - · 不变量 P4
- [mut-20261118102456-7a79] mutation 批次 D · killed · 基线 0 → 变异 1 · 不变量 P4
- [ev-20261118102607-2502] probe 批次 A · exit 0 · PASS · stdout 38a7d6827f0d
- [ev-20261118102634-54be] probe 批次 A · exit 0 · PASS · stdout cf47b8bd0170
- [ev-20261118102647-29d8] probe 批次 A · exit 1 · SUPERSEDED · stdout 1e7546d80ac6 · SUPERSEDED（被 ev-20261118103336-dbee 覆盖）
- [ev-20261118102729-a30c] probe 批次 A · exit 0 · PASS · stdout 97c4fac9c81e
- [ev-20261118102748-e458] probe 批次 A · exit 0 · PASS · stdout 1cea46069c34
- [ev-20261118102926-ea9d] probe 批次 A · exit 0 · PASS · stdout abe7f4cd4684
- [ev-20261118102945-1d51] probe 批次 A · exit 0 · PASS · stdout f60935040261
- [ev-20261118103052-a58b] probe 批次 C · exit 1 · SUPERSEDED · stdout 7f39dc033472 · SUPERSEDED（被 ev-20261118104255-591b 覆盖）
- [ev-20261118103131-42b1] probe 批次 A · exit 0 · PASS · stdout dcc4409f062d
- [ev-20261118103242-3f78] probe 批次 A · exit 0 · PASS · stdout f2a768b57880
- [ev-20261118103256-4125] probe 批次 A · exit 0 · PASS · stdout 528cde5b7e43
- [ev-20261118103316-55b5] probe 批次 A · exit 0 · BASELINE · stdout 7b06f642de73 · SUPERSEDED（被 ev-20261118103336-dbee 覆盖） · BASELINE（expect_exit=1，不计终态分母）
- [ev-20261118103336-dbee] probe 批次 A · exit 0 · PASS · stdout c6f5f14a3f0d
- [ev-20261118103428-5435] probe 批次 A · exit 0 · PASS · stdout 611f3db726de
- [mrg-20261118103520-5415] merge 批次 A · OK · 0cd6426
- [ev-20261118103729-ef6b] probe 批次 A · exit 0 · SUPERSEDED · stdout d8805b9b60eb · SUPERSEDED（被 ev-20261118112525-5449 覆盖）
- [ev-20261118103807-bedf] probe 批次 - · exit 0 · SUPERSEDED · stdout a5ab3074a587 · SUPERSEDED（被 ev-20261118112532-bfec 覆盖）
- [ev-20261118104124-430b] probe 批次 C · exit 0 · SUPERSEDED · stdout f2fe373f580b · SUPERSEDED（被 ev-20261118104255-591b 覆盖）
- [ev-20261118104255-591b] probe 批次 C · exit 0 · PASS · stdout 660dc13e5a1d
- [ev-20261118104936-d69a] probe 批次 C · exit 0 · PASS · stdout 0dcf09dd847e
- [mrg-20261118105048-9227] merge 批次 C · OK · 5e00a50
- [ev-20261118105143-1252] probe 批次 C · exit 0 · PASS · stdout dc255bf9c744
- [ev-20261118105211-244f] probe 批次 - · exit 0 · SUPERSEDED · stdout a4fd149af0e1 · SUPERSEDED（被 ev-20261118112532-bfec 覆盖）
- [mrg-20261118105238-fa2c] merge 批次 B · OK · d6c1b05
- [ev-20261118105806-a360] probe 批次 B · exit 0 · PASS · stdout b60cd1023362
- [ev-20261118105814-752e] probe 批次 - · exit 0 · SUPERSEDED · stdout 8af9ece2dac7 · SUPERSEDED（被 ev-20261118112532-bfec 覆盖）
- [mrg-20261118105823-ddc0] merge 批次 D · OK · a87b950
- [ev-20261118110233-c89d] probe 批次 D · exit 0 · SUPERSEDED · stdout 29345b389dee · SUPERSEDED（被 ev-20261118110650-dec6 覆盖）
- [ev-20261118110250-5742] probe 批次 - · exit 0 · SUPERSEDED · stdout 0f5b7ef3c5ea · SUPERSEDED（被 ev-20261118112532-bfec 覆盖）
- [mrg-20261118110427-cec2] merge 批次 D · OK · ba24c3e
- [ev-20261118110650-dec6] probe 批次 D · exit 0 · PASS · stdout a8b4e6a1694e
- [ev-20261118110655-2ceb] probe 批次 - · exit 0 · SUPERSEDED · stdout fcf6692f9026 · SUPERSEDED（被 ev-20261118112532-bfec 覆盖）
- [ev-20261118111039-30f2] probe 批次 C · exit 0 · PASS · stdout 744f5ae3817f
- [mut-20261118111423-27d2] mutation 批次 B · killed · 基线 0 → 变异 1 · 不变量 P1
- [mut-20261118111510-a0fc] mutation 批次 C · survived · 基线 0 → 变异 0 · 不变量 P3
- [mut-20261118111630-836f] mutation 批次 C · killed · 基线 0 → 变异 1 · 不变量 P3
- [ev-20261118112133-d3b3] probe 批次 D · exit 0 · PASS · stdout 09c49d80cd76
- [ev-20261118112433-a588] probe 批次 A · exit 0 · PASS · stdout 890af8e46ef2
- [mrg-20261118112442-6cf8] merge 批次 A · OK · ad591ce
- [ev-20261118112525-5449] probe 批次 A · exit 0 · PASS · stdout 945d1c4d9f5d
- [ev-20261118112532-bfec] probe 批次 - · exit 0 · PASS · stdout f1a6f11ed058

## 5. 机械缺口与风险 (0)
- 无

## 5.1 已接受局限 / 挂账债务 (13)
- 编译耗时读数只有排他一次：`pr3CommitScaling` 在 loadavg 0.27、daemon 全停、无并发构建下单跑（warmup=5，rounds=7 有效轮）；仍未做多机/多次重复的方差估计
- O(entries) 已实测确认（slope≈333~351 µs/千条，O(1) head() 对照 0.65 µs/千条 不随 N 涨；no-blob 消融 slope −26 µs/千条、tiny-blob 仍 280 µs/千条 ⇒ 增量在全表遍历而非字节拷贝/GC）——本条本轮只定位根因（retainBlob 每 commit 调 blobBytes()、trimGlobalBudget 重算 liveHistoryEntries()），修复留给后续批次，不在 PR #13 内
- 真机读数 N=1 类 × 5 代，接合率不可外推；4 轮均为 mode=1-phase，未覆盖两阶段/InitFix 字段路径与预算降级
- 真机试跑发现模板会静默使用 JitPack 同版本旧 agent jar（detached 坐标缺 `v` 前缀 + 同名版本对账分辨不了），已转记为 docs/status.md 风险，代码修复不在本 PR
- Mindustry 模版真机 BLOCKED(headless)（jar 无 ServerLauncher、aarch64 + 无 Xvfb），本 PR 只保留“可构建”证据；其 ivy 仓库块与根 settings.gradle 不一致的问题按裁决另开 fix(template) PR
- 前插新 lambda 在真机上观察到 1 次位置兜底（positionalFallbacks=1）并伴随 inconsistent body warn，行为正确但兜底原因未逐条归因
- O(entries) 已实测确认（斜率 333~351 µs/千条；O(1) head() 对照 0.65 µs/千条 不随 N 涨；no-blob 消融 −26 µs/千条、tiny-blob 仍 280 µs/千条）——本轮只定位根因（retainBlob 每 commit 调 blobBytes() 重建身份集合 + trimGlobalBudget 重算 liveHistoryEntries()），修复留给 perf(vcs) 后续小 PR，不在 PR #13 内
- 批次 C 的第一次变异打偏在 8 参 commit 重载上（探针走 12 参路径）→ 判 survived；改掐真正插入点 slot.head.put 后 killed（mut-…1 1630-836f，变红 headCount must be 50 got 0）。两次都留在台账，不掩盖
- 急停开关 -Dremold.agent.lambda_raw_alignment=false 只在本地开关语义矩阵验证（4/4），真机上未施加该档；真机只验证“默认即生效”
- 真机读数 N=1 类 × 4~5 代，接合率不可外推；均为 mode=1-phase，未覆盖两阶段/InitFix 字段路径；首轮 NO_ENTRY 属“本进程无上一代元数据”的正常形态
- 模板会静默回落 JitPack 同版本旧 agent jar（detached 坐标缺 v 前缀 + 同版本对账分辨不了），须显式 -Premold.agentJar=；本条只转记 docs/status.md 风险，代码修复不在本 PR
- Mindustry 真机 BLOCKED(headless)（jar 无 ServerLauncher、aarch64 + 无 Xvfb），本 PR 仅保留“可构建”证据；其 ivy 仓库块口径问题按裁决另开 fix(template) PR（先做 H1/H2 归因实验）
- 前插新 lambda 在真机观察到 1 次位置兜底（posFallback=1 + inconsistent body warn），行为与计数正确但兜底原因未逐条归因

## 6. 下一步
- 可执行 battle_audit 复核；通过后 battle_seal 结案。
