# 战报 · 战役 battle-20261110113150-bee7

> 生成时间 2026-11-10T11:37:40.640Z · 阶段 integrating · 基线 main@23c827891ccf · 证据 5 条（已作废 0）

## 1. 目标
定位并根除 LootContextPredicate.combinedCondition 被置 null：为 MixinMemberAlign 补「基线字段墓碑保留」（@Unique 字段 retarget 移走时不从目标类字段表消失，实例内存布局 R1~R4 恒定，消除 DCEVM ModifyInstances 缩容/扩容与重复字段初始化补丁），并在 RemoldAgent 重定义三切面（before-redefine / after-redefine / after-field-patch）接入可开关的堆实例字段哨兵（-Dremold.probe.heapwatch=true），供真机定位与复验。

## 2. 批次结果
| 批次 | 标题 | 耦合 | 状态 | 提交数 | 探针(通过/总) | 变异(击杀/总) | 合流 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D | 故障 B 治本：基线字段墓碑保留（@Unique 字段不消失 → 实例布局恒定，消除 DCEVM ModifyInstances 伸缩）+ 三点堆实例哨兵 | high | merged | 1 | 3/3 | 1/1 | ✓ |

## 3. 不变量证据矩阵
| 不变量 | 声明 | 覆盖证据 |
| --- | --- | --- |
| C1 | 重织入后目标类字段表只增不减：基线中存在、候选字节码中消失的字段必须以同名/同描述符/同访问标志保留（字段表位置与基线一致），使实例内存布局在 retarget 往返期间恒定。 | 202611101133, 202611101137 |
| C2 | 堆实例哨兵必须在 redefineClasses 前、redefineClasses 返回后、字段初始化补丁完成后三个切面枚举目标类实例并报告 null 计数；默认关闭，开启后只读不写。 | 202611101137 |

## 4. 实证台账（压缩）
- [ev-20261110113314-cb41] probe 批次 D · exit 0 · PASS · stdout cf336698d5e7
- [mut-20261110113325-0ff2] mutation 批次 D · killed · 基线 0 → 变异 1
- [mrg-20261110113451-d2d3] merge 批次 D · OK · 3bb2850
- [ev-20261110113524-0499] probe 批次 D · exit 0 · PASS · stdout fa2c05c3ded7
- [ev-20261110113722-4633] probe 批次 D · exit 0 · PASS · stdout eb941ce5eb80

## 5. 缺口与风险 (0)
- 无

## 6. 下一步
- 可执行 battle_audit 复核；通过后 battle_seal 结案。
