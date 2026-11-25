# 战报 · 战役 battle-20261125150724-bec6

> 生成时间 2026-11-25T15:37:14.775Z · 阶段 reported · 基线 feat/kotlin-align-followups@db015dc9b889 · 证据 11 条（已作废 0）

## 1. 目标
Kotlin 实测清单收尾：@JvmStatic @OnReload 双触发的去重派发；把 primitive↔boxed 捕获桥从"合成单测"升级为按 kotlinc 字节形态端到端复现的实证

## 2. 批次结果
| 批次 | 标题 | 耦合 | 状态 | 提交数 | 探针(通过/终态) | 红基线 | 变异(击杀/总) | 合流 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| D | @JvmStatic @OnReload 不再双触发：静态转发器与它委派的目标只派发一次 | high | merged | 1 | 3/3 | 0 | 1/1 | ✓ |
| E | kotlinc 形态（impl 原始 (I)I + indy 装箱 instSig）的捕获桥端到端证据 | high | merged | 2 | 3/3 | 0 | 1/1 | ✓ |

### 2.1 台账口径
- **终态探针**：`expect_exit=0` 的活跃探针，计入 `通过/终态` 分母；同一 batch+label 只有最后一条是终态。
- **红基线**：`expect_exit≠0`，只证明“期望失败确实发生”（TDD 红基线），单独计数，**不进终态分母**。
- **已作废**：`battle_invalidate` 排除；**SUPERSEDED**：被同 batch+label 的后续证据覆盖，均不计入终态。

### 2.2 结构变更摘要（git diff --numstat，客观事实）
| 批次 | 文件 | +/- | 标记 |
| --- | --- | --- | --- |
| D | core/src/remold/BatchHotswapExecutor.java | +103 / -5 |  |
| D | tests/build.gradle | +3 / -0 | ⚠ 测试/构建/CI |
| D | tests/src/OnReloadJvmStaticProbe.java | +249 / -0 | ⚠ 测试/构建/CI |
| E | core/src/remold/BatchHotswapExecutor.java | +103 / -5 |  |
| E | tests/build.gradle | +6 / -0 | ⚠ 测试/构建/CI |
| E | tests/src/KotlinShapeBridgeProbe.java | +258 / -0 | ⚠ 测试/构建/CI |
| E | tests/src/OnReloadJvmStaticProbe.java | +249 / -0 | ⚠ 测试/构建/CI |

> ⚠ 有 5 个测试/构建/CI 相关文件被改动：主指挥官应追问是否削弱了断言或门禁（diffStat 只陈述事实，不做裁决）。

## 3. 不变量证据矩阵
| 不变量 | 声明 | 覆盖证据 |
| --- | --- | --- |
| D1 | @JvmStatic 生成的静态转发器与它委派的 @OnReload 方法在同一个批次里只执行一次 | 202611251522, 202611251526, 202611251536 |
| D2 | 非转发器的 @OnReload 静态方法（自己的方法体）与无 @JvmStatic 的普通用例派发次数不变 | 202611251522, 202611251526, 202611251536 |
| E1 | impl 描述符为原始 (I)I 而 indy 的 instantiatedMethodType 为装箱的 Kotlin 形态 lambda，在真实重定义里能建出捕获桥且旧 CallSite 行为保持 | 202611251522, 202611251529, 202611251536 |

### 3.1 变异语义锚点（变异点 → 变红用例 → 不变量）
| 批次 | 变异点 | 变红用例 | 不变量 |
| --- | --- | --- | --- |
| D | (旧记录无片段) | === SUMMARY: passed=7, failed=1 ===<br>> Task :tests:onReloadJvmStaticProbe FAILED | D1, D2 |
| E | core/src/remold/LambdaCaptureBridge.java: return boxingEquivalentLayout(expected… | > Task :tests:kotlinShapeBridgeProbe FAILED<br>=== SUMMARY: passed=6, failed=2 === | E1 |

## 4. 实证台账（压缩）
- [ev-20261125152237-d9f7] probe 批次 D · exit 0 · PASS · stdout cc3fc2c8f037
- [ev-20261125152246-3a97] probe 批次 E · exit 0 · PASS · stdout d236e5308a92
- [mut-20261125152332-3d08] mutation 批次 D · killed · 基线 0 → 变异 1 · 不变量 D1,D2
- [mut-20261125152356-b096] mutation 批次 E · killed · 基线 0 → 变异 1 · 不变量 E1
- [ev-20261125152648-df5e] probe 批次 D · exit 0 · PASS · stdout 9b11fc0c8925
- [ev-20261125152926-7cbd] probe 批次 E · exit 0 · PASS · stdout 09ef37e5b8cc
- [mrg-20261125152931-34cc] merge 批次 D · OK · 8d59ed1
- [mrg-20261125152931-f1bb] merge 批次 E · OK · e4885a9
- [ev-20261125153202-ec50] probe 批次 D · exit 0 · PASS · stdout ef498ef681cf
- [ev-20261125153203-9808] probe 批次 E · exit 0 · PASS · stdout a4a6a22635f5
- [ev-20261125153616-5524] probe 批次 - · exit 0 · PASS · stdout 03ebfba365ee

## 5. 机械缺口与风险 (0)
- 无

## 5.1 已接受局限 / 挂账债务 (3)
- 战役执行形态偏离：两批均由 Lead 在同一 worktree 单刷，先在 feat 分支提交、事后把提交寄到 agent/batch-* 分支再走 battle_merge（AGENTS §10.8 要求批次分支自己先有提交）。代码与证据链完整，但分支形态不是标准 dispatch 流程。
- @JvmStatic 去重只在"静态成员是纯转发器"时生效：Kotlin 若把回调体直接放进静态方法（非 @JvmStatic 生成形态），两次派发本来就不存在，不受影响；反过来，转发器若夹带了字段写入/跳转就不会被识别，仍会双触发（判据刻意收窄，宁可漏判不可误判）。
- kotlinc 形态桥的探针用 ASM 复刻字节码（impl 原始描述符 + 装箱 instantiatedMethodType），不是真的跑 kotlinc 编译产物；ktfix 目录里的 Kotlin 夹具仍未覆盖 (Int)->Int 捕获桥场景。

## 6. 下一步
- 可执行 battle_audit 复核；通过后 battle_seal 结案。
