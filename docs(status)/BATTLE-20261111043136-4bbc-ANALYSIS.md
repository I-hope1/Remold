# 战报 · 战役 battle-20261111043136-4bbc

> 生成时间 2026-11-11T04:38:42.290Z · 阶段 integrating · 基线 main@4cdea1f2c07f · 证据 7 条（已作废 0）

## 1. 目标
按主指挥官裁决补齐「有无存量实例」维度：在合成类重定义前测 raw/reachable 实例数并打点，按 fieldTableChanged×liveness 三档实施闸门（未变→放行；变化+存活→拒绝并 Abort 宿主；变化+无存活→放行待真机裁定），并补齐成对单测与变异。

## 2. 批次结果
| 批次 | 标题 | 耦合 | 状态 | 提交数 | 探针(通过/总) | 变异(击杀/总) | 合流 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| E | 合成类存量实例（liveness）读数 + 分级闸门 | high | merged | 1 | 4/4 | 2/2 | ✓ |

## 3. 不变量证据矩阵
| 不变量 | 声明 | 覆盖证据 |
| --- | --- | --- |
| C1 | 合成类重定义前必须报出 syntheticLive{raw,reachable}：raw=堆上全部实例（含未被 GC 物理回收的不可达临时实例），reachable=一次 System.gc() 之后的实例数；探针不可用（-1）必须 fail-closed。 | 202611110432, 202611110436 |
| C2 | 闸门分三档：fieldTableChanged==0 → 无论 liveness 一律放行；fieldTableChanged>0 且 reachable>0 → 拒绝（gateRejected=1）并 Abort 宿主（redefineClasses 合计 0 次）；fieldTableChanged>0 且 reachable==0 → 放行（真机待裁定）。 | 202611110432, 202611110436 |
| C3 | 三档均需成对用例（有存活/无存活/仅改方法体）与变异检查：反转 liveness 判据或去掉宿主 Abort 必须使对应用例变红。 | 202611110432, 202611110436 |

## 4. 实证台账（压缩）
- [ev-20261111043245-c332] probe 批次 E · exit 0 · PASS · stdout d552e12fae27
- [mut-20261111043255-c74b] mutation 批次 E · error · 基线 0 → 变异 1
- [mut-20261111043334-4bf3] mutation 批次 E · killed · 基线 0 → 变异 1
- [ev-20261111043658-444d] probe 批次 E · exit 0 · PASS · stdout ef8823e5efa3
- [mrg-20261111043720-d400] merge 批次 E · OK · 8109ad8
- [ev-20261111043727-384c] probe 批次 E · exit 0 · PASS · stdout b95fdff19466
- [ev-20261111043807-9bf8] probe 批次 E · exit 0 · PASS · stdout 82263c6f36b8

## 5. 缺口与风险 (0)
- 无

## 6. 下一步
- 可执行 battle_audit 复核；通过后 battle_seal 结案。
