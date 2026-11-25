# 战报 · 战役 battle-20261125135539-dc8b

> 生成时间 2026-11-25T14:39:00.200Z · 阶段 reported · 基线 feat/kotlin-align-hardening@6dc4a5a1642a · 证据 19 条（已作废 1）

## 1. 目标
Kotlin 对齐/初始化实测挖出的 P1 缺陷：LambdaAligner PASS_A 无锚点插队抢槽、LambdaCaptureBridge 对 Kotlin 原始类型 lambda 的 primitive↔boxed 类型不等价导致桥全灭、InitializerDrift 把 lambda 捕获盒字段误报成继承初值漂移

## 2. 批次结果
| 批次 | 标题 | 耦合 | 状态 | 提交数 | 探针(通过/终态) | 红基线 | 变异(击杀/总) | 合流 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | PASS_A 两段式：先跑完全部带唯一 PUT 锚点的候选，再让无锚点候选参与回退 | low | merged | 2 | 5/5 | 1 | 2/2 | ✓ |
| B | LambdaCaptureBridge 的 primitive↔boxed SAM 边界等价（Kotlin (Int)->Int / ()->Int 桥） | high | merged | 2 | 2/2 | 0 | 1/1 | ✓ |
| C | INIT-DRIFT 噪声：捕获盒字段不再被当成继承初值漂移 | low | merged | 3 | 2/2 | 0 | 1/1 | ✓ |

### 2.1 台账口径
- **终态探针**：`expect_exit=0` 的活跃探针，计入 `通过/终态` 分母；同一 batch+label 只有最后一条是终态。
- **红基线**：`expect_exit≠0`，只证明“期望失败确实发生”（TDD 红基线），单独计数，**不进终态分母**。
- **已作废**：`battle_invalidate` 排除；**SUPERSEDED**：被同 batch+label 的后续证据覆盖，均不计入终态。

### 2.2 结构变更摘要（git diff --numstat，客观事实）
| 批次 | 文件 | +/- | 标记 |
| --- | --- | --- | --- |
| A | core/src/remold/LambdaAligner.java | +80 / -21 |  |
| A | tests/src/remold/LambdaAlignerPassAOrderTest.java | +429 / -0 | ⚠ 测试/构建/CI |
| B | core/src/remold/LambdaCaptureBridge.java | +76 / -3 |  |
| B | tests/src/remold/LambdaCaptureBridgeBoxingLayoutTest.java | +117 / -0 | ⚠ 测试/构建/CI |
| C | core/src/remold/ref/InitializerDrift.java | +91 / -12 |  |
| C | tests/src/remold/ref/InitializerDriftOwnerTest.java | +118 / -0 | ⚠ 测试/构建/CI |

> ⚠ 有 3 个测试/构建/CI 相关文件被改动：主指挥官应追问是否削弱了断言或门禁（diffStat 只陈述事实，不做裁决）。

## 3. 不变量证据矩阵
| 不变量 | 声明 | 覆盖证据 |
| --- | --- | --- |
| A1 | 带唯一 PUT 字段锚点的候选必须在无锚点候选之前完成配对：无锚点新 lambda 不得抢走旧组中已被唯一锚点指名的槽位 | 202611251412, 202611251416, 202611251426, 202611251437 |
| A2 | 无锚点回退路径的配对能力不得退化：同一输入下配对数不减少，且锚点优先不改变确定性与最终 renameMap 的稳定形态 | 202611251412, 202611251416, 202611251426, 202611251437 |
| B1 | LambdaCaptureBridge 的布局自检接受 primitive 与对应 boxed 类型在 SAM 实例化类型边界的等价（Kotlin (Int)->Int 的 impl 描述符用原始 I，而 instantiated SAM 类型是 Integer） | 202611251420, 202611251437 |
| B2 | primitive↔boxed 等价只放宽类型边界，不放松任何一个既有门（捕获表布局、SAM 门、子序列门、1:1 唯一性） | 202611251420, 202611251437 |
| C1 | InitializerDrift 只对宿主类自己声明的字段或真实继承字段做初值漂移判定，lambda 捕获盒（kotlin.jvm.internal.Ref$*）等第三方容器字段不得产生 INIT-DRIFT 误报 | 202611251423, 202611251437 |

### 3.1 变异语义锚点（变异点 → 变红用例 → 不变量）
| 批次 | 变异点 | 变红用例 | 不变量 |
| --- | --- | --- | --- |
| A | (旧记录无片段) | LambdaAlignerPassAOrderTest > A1：无论无锚点新 lambda 声明在锚点候选之前还是之后，锚点槽位都归锚点候选 FAILED<br>org.opentest4j.AssertionFailedError at LambdaAlignerPassAOrderTest.java:210 | A1, A2 |
| A | (旧记录无片段) | LambdaAlignerPassAOrderTest > A1：无论无锚点新 lambda 声明在锚点候选之前还是之后，锚点槽位都归锚点候选 FAILED<br>org.opentest4j.AssertionFailedError at LambdaAlignerPassAOrderTest.java:210 | A1, A2 |
| B | core/src/remold/LambdaCaptureBridge.java: return boxingEquivalentLayout(expected…<br>core/src/remold/LambdaCaptureBridge.java: if (!forwardedTailMatches(oi, ni)) ret… | > Task :tests:test FAILED<br>LambdaCaptureBridgeBoxingLayoutTest > kotlinNoArgIntLayoutHolds() FAILED | B1, B2 |
| C | core/src/remold/ref/InitializerDrift.java: if (!inheritedWrite) continue; → inhe… | InitializerDriftOwnerTest > unresolvableAncestryFailsQuietInsteadOfBlamingAParen…<br>org.opentest4j.AssertionFailedError at InitializerDriftOwnerTest.java:50 | C1 |

## 4. 实证台账（压缩）
- [ev-20261125141202-7fa8] probe 批次 A · exit 1 · BASELINE · stdout db043f9f88f9 · BASELINE（expect_exit=1，不计终态分母）
- [ev-20261125141236-5085] probe 批次 A · exit 0 · PASS · stdout 15f734cfcdd2
- [ev-20261125141615-5dc1] probe 批次 A · exit 0 · PASS · stdout c46a31b9e7e1
- [mut-20261125141621-6c3a] mutation 批次 A · killed · 基线 0 → 变异 1 · 不变量 A1,A2
- [ev-20261125142030-2209] probe 批次 B · exit 0 · PASS · stdout 6234e72b8b4e
- [ev-20261125142041-098a] probe 批次 C · exit 0 · PASS · stdout 6797204172e9 · **已作废**（3.7s 完成 :tests:test 且日志无任何 Task 输出 = Gradle 判定 UP-TO-DATE（我此前已在本 worktree 跑过一次全量），不是本次执行，不计 PASS。改用 --rerun-tasks 重跑。）
- [ev-20261125142330-07df] probe 批次 C · exit 0 · PASS · stdout 49aa87266fab
- [ev-20261125142603-2788] probe 批次 A · exit 0 · PASS · stdout 375aaa150204
- [mut-20261125142619-6b4e] mutation 批次 A · killed · 基线 0 → 变异 1 · 不变量 A1,A2
- [mut-20261125142704-92eb] mutation 批次 B · killed · 基线 0 → 变异 1 · 不变量 B1,B2
- [mut-20261125142755-333e] mutation 批次 C · killed · 基线 0 → 变异 1 · 不变量 C1
- [mrg-20261125142838-3433] merge 批次 A · OK · da3bcc5
- [mrg-20261125142839-943d] merge 批次 B · OK · 61ff5ba
- [mrg-20261125142839-64ad] merge 批次 C · OK · 2d47e50
- [ev-20261125143117-c091] probe 批次 A · exit 0 · PASS · stdout eb8dc02eabc5
- [ev-20261125143122-8f42] probe 批次 A · exit 0 · PASS · stdout adcee50c62bf
- [ev-20261125143331-d708] probe 批次 B · exit 0 · PASS · stdout 083e310084f9
- [ev-20261125143333-bade] probe 批次 C · exit 0 · PASS · stdout a4a6a22635f5
- [ev-20261125143740-6f58] probe 批次 - · exit 0 · PASS · stdout 0689b368018f

## 5. 机械缺口与风险 (0)
- 无

## 5.1 已接受局限 / 挂账债务 (3)
- 批次 A 的缺陷复现顺序依赖 javac 夹具的槽号倒序遍历形态：无锚点声明在前的顺序在旧实现下侥幸正确，真正的红形态是"锚点候选在前、无锚点候选在后"。两种顺序现已都被 LambdaAlignerPassAOrderTest 锁死，但真机 Kotlin 序列（DebugUtil.kt V1→V2→V3→prepend V4）本轮未重跑，A1 的实际收益仍属推断而非实测。
- 批次 B 的修复依据是 kotlinc 的 instSig 装箱 / impl 原始特化分离这一字节码事实，用合成 SyntheticInfo 单测锁定；尚未用真实 kotlinc 产物跑通端到端桥（ktfix 夹具未覆盖 (Int)->Int 捕获桥场景），端到端收益待补。
- 批次 C 的祖先链在报告期通过"批次字节 → 运行期字节"解析，解析不到时按静默处理（告警面 fail-quiet）。第三方父类（父类不在任何缓存里）的 double-brace 写父类字段会因此漏报；这是刻意取舍，不是遗漏。

## 6. 下一步
- 可执行 battle_audit 复核；通过后 battle_seal 结案。
