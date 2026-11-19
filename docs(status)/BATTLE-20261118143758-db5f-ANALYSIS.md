# 战报 · 战役 battle-20261118143758-db5f

> 生成时间 2026-11-19T00:44:13.277Z · 阶段 reported · 基线 feat/pr4-a-cross-class@2717cd679dcd · 证据 34 条（已作废 2）

## 1. 目标
Implement PR4-A only: use same-batch latest bytecode for InitFix analysis, converge cross-class field dependencies at (Class, Field) precision before redefine, execute classes in topological Stage B order in both two-phase and single-phase paths, and propagate runtime failures to dependent host fields in FieldLedger; exclude multi-generation replay and preserve all five InitFix invariants.

## 2. 批次结果
| 批次 | 标题 | 耦合 | 状态 | 提交数 | 探针(通过/终态) | 红基线 | 变异(击杀/总) | 合流 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | PR4-A: cross-class same-batch InitFix topology | high | merged | 1 | 14/14 | 0 | 5/12 | ✓ |

### 2.1 台账口径
- **终态探针**：`expect_exit=0` 的活跃探针，计入 `通过/终态` 分母；同一 batch+label 只有最后一条是终态。
- **红基线**：`expect_exit≠0`，只证明“期望失败确实发生”（TDD 红基线），单独计数，**不进终态分母**。
- **已作废**：`battle_invalidate` 排除；**SUPERSEDED**：被同 batch+label 的后续证据覆盖，均不计入终态。

### 2.2 结构变更摘要（git diff --numstat，客观事实）
| 批次 | 文件 | +/- | 标记 |
| --- | --- | --- | --- |
| A | core/src/remold/RemoldAgent.java | +24 / -7 |  |
| A | core/src/remold/ref/HierarchyTreeOracle.java | +4 / -1 |  |
| A | core/src/remold/ref/InitFix.java | +514 / -143 |  |
| A | core/src/remold/ref/InitFixBatchGraph.java | +134 / -0 |  |
| A | tests/build.gradle | +3 / -0 | ⚠ 测试/构建/CI |
| A | tests/src/CrossClassInitFixProbe.java | +120 / -0 | ⚠ 测试/构建/CI |
| A | tests/src/remold/ref/CrossClassInitFixTest.java | +112 / -0 | ⚠ 测试/构建/CI |
| A | tests/src/remold/ref/InitFixBatchGraphTest.java | +69 / -0 | ⚠ 测试/构建/CI |

> ⚠ 有 4 个测试/构建/CI 相关文件被改动：主指挥官应追问是否削弱了断言或门禁（diffStat 只陈述事实，不做裁决）。

## 3. 不变量证据矩阵
| 不变量 | 声明 | 覆盖证据 |
| --- | --- | --- |
| I1 | UNSAFE.shouldBeInitialized(clazz) true always skips patching. | 202611190011, 202611190012, 202611190013, 202611190024, 202611190026, 202611190031 |
| I2 | PENDING, REPORTS, LEDGER, REINIT_APPLIED and related campaign state never strongly retain Class or ClassLoader; weak-key and lock-order constraints remain. | 202611181515, 202611181522, 202611181529, 202611181605, 202611190011, 202611190011, 202611190024, 202611190026, 202611190026, 202611190031 |
| I3 | PatchReport stores only class-name strings and immutable enum data, never Class/ClassLoader. | 202611181515, 202611181522, 202611181529, 202611181605, 202611190011, 202611190011, 202611190024, 202611190026, 202611190026, 202611190031 |
| I4 | LinkageError immediately trips the current-class fuse except BootstrapMethodError whose cause is not LinkageError, which may isolate only one field. | 202611181522, 202611190011, 202611190012, 202611190013, 202611190024, 202611190026, 202611190026, 202611190031 |
| I5 | afterRedefineFailed immediately clears staged patch state and records failed fields in FieldLedger. | 202611190011, 202611190012, 202611190013, 202611190024, 202611190026, 202611190026, 202611190031 |
| A1 | A loader-scoped immutable view of final candidate bytecode for the whole batch is available to InitFix hierarchy, nest and reinit-scope analysis before any redefine; absent or incomplete facts fail closed. | 202611181515, 202611181529, 202611181605, 202611190011, 202611190012, 202611190024, 202611190026, 202611190026, 202611190031 |
| A2 | Cross-class dependency/closure uses individual (Class, Field) nodes; reads of old fields and confirmed T0-zero fields create no edge; rejection propagates only to exact dependent fields. | 202611181529, 202611181605, 202611190011, 202611190012, 202611190024, 202611190026, 202611190031 |
| A3 | Stage B host execution is topologically ordered in both two-phase and single-phase flows without splitting per-host fused field loops. | 202611190011, 202611190024, 202611190026, 202611190031 |
| A4 | At host execution granularity, cross-class cycles or contradictory interleavings are rejected conservatively and all affected fields are recorded in their owning FieldLedger, while independent fields remain eligible. | 202611190012, 202611190013, 202611190024, 202611190026, 202611190031 |
| A5 | If a prerequisite host field fails at runtime, only dependent fields in downstream hosts are skipped and recorded in their own FieldLedger; unrelated fields still execute. | 202611190012, 202611190024, 202611190026, 202611190031 |

### 3.1 变异语义锚点（变异点 → 变红用例 → 不变量）
| 批次 | 变异点 | 变红用例 | 不变量 |
| --- | --- | --- | --- |
| A | core/src/remold/ref/HierarchyTreeOracle.java: if (batch != null) return batch; →… | > Task :tests:test FAILED<br>CrossClassInitFixTest > loaderScopedOverlaySuppliesDependencyMetadata() FAILED | A1, A2 |
| A | core/src/remold/ref/InitFix.java: return ordered.stream().map(byKey::get).filter… | [Remold] [WARN] Field init patch incomplete for ccprobe.failure.Y: failed=[base]…<br>CROSS-CLASS INITFIX SUMMARY: passed=5, failed=3 | A2, A4 |
| A | core/src/remold/ref/InitFixBatchGraph.java: if (!reader.host().equals(prerequisi… | > Task :tests:test FAILED<br>InitFixBatchGraphTest > identifiesConflictingInterleavingsWithoutAFieldCycle() F… | A2, A3 |
| A | core/src/remold/ref/InitFix.java: 					BATCH_FAILURES.get().add(token); 					con… | [Remold] [WARN] Field init patch incomplete for ccprobe.failure.Y: failed=[base]…<br>CROSS-CLASS INITFIX SUMMARY: passed=6, failed=2 | A5, I5 |
| A | core/src/remold/ref/InitFixBatchGraph.java: return Set.copyOf(cycles); → return … | > Task :tests:test FAILED<br>InitFixBatchGraphTest > detectsFieldCyclesAndContradictoryHostOrdering() FAILED | A2, A3 |

## 4. 实证台账（压缩）
- [ev-20261118151508-2cff] probe 批次 A · exit 126 · SUPERSEDED · stdout e3b0c44298fc · SUPERSEDED（被 ev-20261118151532-e1c3 覆盖） · harness-error 不计分
- [ev-20261118151532-e1c3] probe 批次 A · exit 0 · PASS · stdout d4e65ee01074
- [ev-20261118152053-52c7] probe 批次 A · exit 1 · SUPERSEDED · stdout 743a2187426e · SUPERSEDED（被 ev-20261118152259-6a77 覆盖）
- [ev-20261118152259-6a77] probe 批次 A · exit 0 · PASS · stdout 10f386ac6340
- [ev-20261118152930-53c7] probe 批次 A · exit 0 · PASS · stdout c15828182ac4
- [ev-20261118160556-aa9b] probe 批次 A · exit 0 · PASS · stdout 5f4329006e02
- [ev-20261119001138-6f06] probe 批次 A · exit 0 · PASS · stdout 2c1b77ec4cd9
- [ev-20261119001159-1da8] probe 批次 A · exit 0 · PASS · stdout e43635edf49c
- [ev-20261119001215-7a7d] probe 批次 A · exit 0 · PASS · stdout 6c5e61fedfc3
- [ev-20261119001300-3441] probe 批次 A · exit 0 · PASS · stdout 1f05398c5904
- [mut-20261119001321-869f] mutation 批次 A · survived · 基线 0 → 变异 0 · 不变量 A1,A2
- [mut-20261119001539-141a] mutation 批次 A · killed · 基线 0 → 变异 1 · 不变量 A1,A2
- [mut-20261119001607-b73c] mutation 批次 A · survived · 基线 0 → 变异 0 · 不变量 A2,A4
- [mut-20261119001633-2fde] mutation 批次 A · killed · 基线 0 → 变异 1 · 不变量 A2,A4
- [mut-20261119001657-54bf] mutation 批次 A · survived · 基线 0 → 变异 0 · 不变量 A2,A3
- [mut-20261119001808-f4dc] mutation 批次 A · killed · 基线 0 → 变异 1 · 不变量 A2,A3
- [mut-20261119001831-f18a] mutation 批次 A · error · 基线 0 → 变异 - · 不变量 A5,I5
- [mut-20261119001903-13dd] mutation 批次 A · survived · 基线 0 → 变异 0 · 不变量 A5,I5
- [mut-20261119001927-b27a] mutation 批次 A · killed · 基线 0 → 变异 1 · 不变量 A5,I5
- [mut-20261119001955-0717] mutation 批次 A · survived · 基线 0 → 变异 0 · 不变量 A2,A3
- [mut-20261119002025-f211] mutation 批次 A · error · 基线 0 → 变异 - · 不变量 A2,A3
- [mut-20261119002050-55e9] mutation 批次 A · killed · 基线 0 → 变异 1 · 不变量 A2,A3
- [ev-20261119002424-2159] probe 批次 A · exit 0 · PASS · stdout 788f8dc0b9f5
- [mrg-20261119002527-1b60] merge 批次 A · OK · a0f2c8e
- [ev-20261119002528-d55a] probe 批次 A · exit 126 · FAIL · stdout e3b0c44298fc · **已作废**（Harness error, not a code result: the per-batch acceptance command was ./gradlew and the repository wrapper is mode 100644, so bash returned exit 126 (Permission denied) with empty stdout before Gradle started. Acceptance commands were corrected to bash ./gradlew and the following integration merge passed every per-batch acceptance plus the union gate.） · harness-error 不计分
- [ev-20261119002600-f18e] probe 批次 A · exit 0 · PASS · stdout 9b247826d419
- [mrg-20261119002614-c164] merge 批次 A · OK · b12c005
- [ev-20261119002615-000c] probe 批次 A · exit 126 · FAIL · stdout e3b0c44298fc · **已作废**（Harness error, not a code result: acceptance command was ./gradlew and the repository wrapper is mode 100644, so bash returned exit 126 (Permission denied) with empty stdout before Gradle ever started. Campaign acceptance commands were corrected to bash ./gradlew and the subsequent integration merge passed per-batch acceptance and the union gate.） · harness-error 不计分
- [mrg-20261119002757-c513] merge 批次 A · OK · 4507372
- [ev-20261119002829-7270] probe 批次 A · exit 0 · PASS · stdout 8c963f2acdba
- [ev-20261119002837-3481] probe 批次 A · exit 0 · PASS · stdout d1cf27ef0c52
- [ev-20261119002841-b276] probe 批次 A · exit 0 · PASS · stdout 03e3e6a1c925
- [ev-20261119003140-8c63] probe 批次 A · exit 0 · PASS · stdout df18af925a6a
- [ev-20261119003148-db66] probe 批次 - · exit 0 · PASS · stdout fb28fb4db4f2

## 5. 机械缺口与风险 (0)
- 无

## 5.1 已接受局限 / 挂账债务 (4)
- `gradlew` is mode 100644, so bare `./gradlew` fails with exit 126; all campaign acceptance commands were normalized to `bash ./gradlew`, and the two exit-126 records were invalidated rather than counted as code failures. Restore +x on `gradlew` if bare invocations should work.
- Five of twelve mutation trials survived: they were duplicate probes of the same mutation point, mutations of unreachable helper code, or trials whose `test_cmd` did not include the file holding the target assertion. Every real implementation point (overlay, host order, runtime token, cycle detection, cross-class edges) has at least one killed trial anchored to a failing assertion.
- Five of twelve recorded mutation trials survived. Surviving trials were either duplicate probes of the same mutation point, mutations of unreachable helper code, or mutations whose target assertion lives only in a separate test file excluded by that trial's test_cmd; each real implementation point (overlay, host order, runtime token, cycle detection, cross-class edges) has at least one killed trial anchored to a failing assertion.
- 1 条 harness-error 证据（环境/命令未真正执行，不计入通过/击杀）: ev-20261118151508-2cff

## 6. 下一步
- 可执行 battle_audit 复核；通过后 battle_seal 结案。
