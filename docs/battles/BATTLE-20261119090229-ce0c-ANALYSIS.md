# 战报 · 战役 battle-20261119090229-ce0c

> **文档类型**：审计记录（战役实证战报；全局实现状态以 [docs/status.md](../status.md) 为准）

> 生成时间 2026-11-19T09:56:39.670Z · 阶段 reported · 基线 feat/vcs-commit-tick-impl@de91cdd0086b · 证据 17 条（已作废 4）

## 1. 目标
Implement the PR1 commit-tick contract from docs/topology/16 §2.1.1-2.1.3/§3.8: immutable per-scope Changeset chain with seq/parent/roundId/loaderScope/entries/roots, manifest and replayFacts recorded at commit time, HEAD advanced only when VcsCommitGate passes, window M=32, and addressable/replayable/revertable availability with NOT_REVERTABLE refusal.

## 2. 批次结果
| 批次 | 标题 | 耦合 | 状态 | 提交数 | 探针(通过/终态) | 红基线 | 变异(击杀/总) | 合流 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | PR1 commit tick: Changeset log + manifest + replayFacts | high | merged | 3 | 7/7 | 0 | 4/4 | ✓ |

### 2.1 台账口径
- **终态探针**：`expect_exit=0` 的活跃探针，计入 `通过/终态` 分母；同一 batch+label 只有最后一条是终态。
- **红基线**：`expect_exit≠0`，只证明“期望失败确实发生”（TDD 红基线），单独计数，**不进终态分母**。
- **已作废**：`battle_invalidate` 排除；**SUPERSEDED**：被同 batch+label 的后续证据覆盖，均不计入终态。

### 2.2 结构变更摘要（git diff --numstat，客观事实）
| 批次 | 文件 | +/- | 标记 |
| --- | --- | --- | --- |
| A | CHANGELOG.md | +21 / -0 |  |
| A | core/src/remold/RemoldAgent.java | +81 / -3 |  |
| A | core/src/remold/ref/InitFix.java | +139 / -4 |  |
| A | core/src/remold/vcs/AlignmentMetaStore.java | +11 / -0 |  |
| A | core/src/remold/vcs/BytecodePack.java | +363 / -36 |  |
| A | core/src/remold/vcs/Changeset.java | +221 / -0 |  |
| A | core/src/remold/vcs/ChangesetLog.java | +164 / -0 |  |
| A | docs/status.md | +1 / -0 |  |
| A | tests/src/VcsPackProbe.java | +220 / -0 | ⚠ 测试/构建/CI |
| A | tests/src/remold/ref/ReplayFactLiteralTest.java | +55 / -0 | ⚠ 测试/构建/CI |
| A | tests/src/remold/vcs/ChangesetTickTest.java | +299 / -0 | ⚠ 测试/构建/CI |

> ⚠ 有 3 个测试/构建/CI 相关文件被改动：主指挥官应追问是否削弱了断言或门禁（diffStat 只陈述事实，不做裁决）。

## 3. 不变量证据矩阵
| 不变量 | 声明 | 覆盖证据 |
| --- | --- | --- |
| C1 | seq advances only for rounds that pass VcsCommitGate; aborted/rejected/rolled-back rounds leave HEAD and seq untouched and only record a diagnostic aborted fact. | 202611190923, 202611190945, 202611190947, 202611190953 |
| C2 | Each Changeset is immutable after write and carries a parent pointer within its loaderScope; content-identical commits at different seq get different ids. | 202611190923, 202611190945, 202611190947, 202611190953 |
| C3 | The changeset window defaults to 32 entries per loaderScope (overridable) and eviction never breaks the head/parent chain. | 202611190923, 202611190945, 202611190947, 202611190953 |
| C4 | Availability is explicit: addressable requires only the Changeset, replayable requires Raw bytes and Meta, revertable requires Eff bytes or a successful Raw+Meta rebuild; a revert target below revertable is refused as NOT_REVERTABLE rather than partially applied. | 202611190923, 202611190947, 202611190953 |
| C5 | manifest is a batch-level snapshot stored outside per-class entries, and a commit with an empty edge set is still a valid commit that advances seq. | 202611190923, 202611190945, 202611190947, 202611190953 |
| C6 | replayFacts are commit-level facts recorded at commit time from pure analysis (InitializerReplay/ApplyInitializerReplay plans), survive Raw byte eviction, record both old and new literals with raw bit patterns for float/double, and allow REINIT tier with literalKind NONE. | 202611190923, 202611190944, 202611190947, 202611190953 |
| C7 | HEAD means redefine committed; advisory.patchTiming records whether field patches happened before (two-phase) or after (single-phase) the commit point. | 202611190923, 202611190947, 202611190953 |
| C8 | Changeset records hold only strings, hashes and loader scope ids; no Class, ClassLoader or ClassNode strong references are retained, and per-loader chains follow the existing weak-key loader slots. | 202611190923, 202611190947, 202611190953 |

### 3.1 变异语义锚点（变异点 → 变红用例 → 不变量）
| 批次 | 变异点 | 变红用例 | 不变量 |
| --- | --- | --- | --- |
| A | core/src/remold/vcs/ChangesetLog.java: 		int value = raw == null ? DEFAULT_WINDO…<br>core/src/remold/vcs/ChangesetLog.java: 			if (level != Changeset.Availability.RE…<br>core/src/remold/vcs/Changeset.java: 		List<String> invented = new ArrayList<>(in… | > Task :tests:test FAILED<br>ChangesetTickTest > windowDefaultsToThirtyTwo() FAILED | C2, C3, C4, C5, C6 |
| A | core/src/remold/vcs/Changeset.java: 		 Long.toString(seq), → 		 "", | ChangesetTickTest > seqIsPartOfTheIdEvenWithAnIdenticalParentAndContent() FAILED<br>org.opentest4j.AssertionFailedError at ChangesetTickTest.java:124 | C2 |
| A | core/src/remold/vcs/BytecodePack.java: 		sb.append("parentId=").append(escape(cs… | [Remold] [WARN] [Forge/NeoForge] Unable to resolve Mixin transformer across Clas…<br>=== VCS PACK PROBE SUMMARY: passed=93, failed=6 === | C2 |
| A | core/src/remold/vcs/ChangesetLog.java: 		int value = raw == null ? DEFAULT_WINDO…<br>core/src/remold/vcs/ChangesetLog.java: 		if (level != Changeset.Availability.REV…<br>core/src/remold/vcs/Changeset.java: 		List<String> invented = new ArrayList<>(in… | > Task :tests:test FAILED<br>ChangesetTickTest > windowDefaultsToThirtyTwo() FAILED | C2, C3, C4, C6 |

## 4. 实证台账（压缩）
- [mut-20261119092043-d59e] mutation 批次 A · killed · 基线 0 → 变异 1 · 不变量 C2,C3,C4,C5,C6
- [mut-20261119092137-e547] mutation 批次 A · survived · 基线 0 → 变异 1 · 不变量 C2,C3,C4,C6 · **已作废**（Superseded: its single surviving trial (seq removed from the id) was a genuine weak spot, fixed by adding seqIsPartOfTheIdEvenWithAnIdenticalParentAndContent and re-killed in mut-20261119092255-b380; the four killed trials in this record are re-recorded cleanly in mut-20261119095504-8e47 with the same test command.）
- [mut-20261119092255-b380] mutation 批次 A · killed · 基线 0 → 变异 1 · 不变量 C2
- [ev-20261119092349-2972] probe 批次 A · exit 0 · PASS · stdout 06ce01470918
- [ev-20261119093926-6953] probe 批次 A · exit 1 · FAIL · stdout 6266c6c07243 · **已作废**（Concurrent Gradle builds in the same worktree: another teammate was running :tests:vcsPackProbe at the same time, so the PassB fixture's strict javac read core/build/libs/remold-core-0.2.8-raw.jar mid-rewrite and failed with "zip END header not found" (rc=2). This is an artifact-contention error, not a code regression; the same command passed 30s earlier on a quiet tree. Will re-record on a serialized run.）
- [ev-20261119094452-1d25] probe 批次 A · exit 0 · PASS · stdout 6e66150e9dcb
- [ev-20261119094513-dd88] probe 批次 A · exit 0 · PASS · stdout 3c9ee8c31bde
- [ev-20261119094749-311f] probe 批次 A · exit 0 · PASS · stdout fbeeb5fb5c84
- [mut-20261119094816-9716] mutation 批次 A · survived · 基线 0 → 变异 0 · 不变量 C2,C5 · **已作废**（Superseded: its surviving trial was an inert rewrite (appended a duplicate parentId line that the manifest reader overwrote, so the mutation changed no behaviour). Replaced with a real one-line parentId rewrite and re-run under the probe command, which killed it: mut-20261119094906-a9d1.）
- [mut-20261119094906-a9d1] mutation 批次 A · killed · 基线 0 → 变异 1 · 不变量 C2
- [mrg-20261119094956-ac33] merge 批次 A · OK · 58427c2
- [ev-20261119095003-aba5] probe 批次 A · exit 0 · PASS · stdout 8fd425625a9b
- [ev-20261119095022-6b3e] probe 批次 A · exit 0 · PASS · stdout 653ab368d963
- [ev-20261119095328-baa5] probe 批次 A · exit 0 · PASS · stdout bc7d6aba4514
- [ev-20261119095336-ddcd] probe 批次 - · exit 0 · PASS · stdout 26d594f79034
- [mut-20261119095413-3675] mutation 批次 A · survived · 基线 0 → 变异 0 · 不变量 C2,C3,C4,C6 · **已作废**（Mixed-scope run: five unit-observable trials were killed, but the sixth (manifest parentId) cannot be observed by the unit test command at all — it needs the DCEVM pack probe. Replaced by two scope-correct records: mut-20261119095504-8e47 (five unit trials, 5/5 killed) and mut-20261119094906-a9d1 (parentId trial under the probe command, killed). Its 5/6 reading is a test-command mismatch, not a weak test.）
- [mut-20261119095504-8e47] mutation 批次 A · killed · 基线 0 → 变异 1 · 不变量 C2,C3,C4,C6

## 5. 机械缺口与风险 (0)
- 无

## 5.1 已接受局限 / 挂账债务 (4)
- `Changeset` 的 `metaHash` 取自自造名/幽灵指纹/方法映射/桥数量的规范化摘要，不是 Meta 树完整序列化的哈希；Meta 树格式本身未在本切片引入（`metaRoot` 仍是该摘要的树根）。
- PR4-B 的区间回退 `(target, HEAD]` 仍需首次纳入链的类的基线 `Eff_0`；本切片只在 `docs/topology/16` §2.1.1 预留该槽位，未实现。
- `replayFacts` 的 `L1` 档 `provenance` 留空（只有 A 档填写 `dest:desc`）；L1 的 provenance 语义待 PR4-B 定义后再补。
- 跨 scope 批次按 scope 各自成链，但 `roundId` 复用 `batchWatchLabel` 文本，未引入独立批次序号；诊断侧只能按文本聚合。

## 6. 下一步
- 可执行 battle_audit 复核；通过后 battle_seal 结案。
