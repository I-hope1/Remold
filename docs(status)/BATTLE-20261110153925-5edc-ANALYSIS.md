# 战报 · 战役 battle-20261110153925-5edc

> 生成时间 2026-11-11T03:45:26.285Z · 阶段 integrating · 基线 main@0eeeb65c5a76 · 证据 48 条（已作废 1）

## 1. 目标
按主指挥官裁决补齐三路：A3=漏项点名/过滤规则/LOADED 键/产品侧影子类反查/F6 24 次拆分/模板制品部署校验；B3=校准通道声明 + 夹具四项（不持引用 Predicate 组、多存活匿名实例、G1 堆参数、并发分配）；C3=真正的「扩容 + 旧 <init> 半残实例」语义用例。停损与发布冻结维持。

## 2. 批次结果
| 批次 | 标题 | 耦合 | 状态 | 提交数 | 探针(通过/总) | 变异(击杀/总) | 合流 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A3 | A3：点名 missed=1 / 353 过滤规则 / LOADED 键结构 / 产品侧影子类反查 / F6 的 24 次拆分（读模板日志）/ 最终产物写入模板 .gradle/remold 并校验 md5 | low | merged | 5 | 13/13 | 4/4 | ✓ |
| B3 | B3：声明校准通道 + 夹具四项（不持引用 vs 持引用 Predicate 组 / 多存活匿名实例 / G1 堆参数 / redefine 期并发分配读取） | low | merged | 2 | 7/9 | 6/8 | ✓ |
| C3 | C3：真「扩容 + 旧 <init> 半残实例」语义用例（2 字段→4 字段含新引用字段，旧 2 参构造的实例调新方法体读新字段，实测 NPE 还是安全） | low | merged | 2 | 7/7 | 1/3 | ✓ |

## 3. 不变量证据矩阵
| 不变量 | 声明 | 覆盖证据 |
| --- | --- | --- |
| C1 | B3 必须声明校准通道（产品 JVMTI getInstances 或夹具注册表）；夹具四项任一项 newlyNull>0 即标 [复现] 并停损；被置 null 的 230 个字段在重定义前持有的具体实现类必须可分桶。 | 202611101601, 202611101615, 202611101616, 202611101619, 202611101620, 202611101621 |
| C2 | A3 必须点名 missed=1 的类全称与漏录原因、给出 353 过滤项的匹配规则、写明 LOADED 键结构（含 loader 身份）；产品侧影子 Class 缓存排查给出有/无 + 文件行号；模板 .gradle/remold 落盘 md5 与构建产物 100% 一致。 | 202611101548, 202611101601, 202611101601, 202611101601, 202611101602, 202611101602, 202611101605, 202611101605, 202611101610, 202611101612, 202611101613, 202611101613 |
| C3 | C3 的半残实例定义为「扩容后旧 <init> 构造的实例，其新增引用字段为 null，调新方法体读该字段」；必须实测 NPE/安全并定性，不得用「缩容保旧字段」代替。 | **无** |

## 4. 实证台账（压缩）
- [ev-20261110154413-63fb] probe 批次 C3 · exit 0 · PASS · stdout 523ba888922a
- [ev-20261110154857-ae7d] probe 批次 A3 · exit 0 · PASS · stdout fceaf80cd0eb
- [ev-20261110155439-f95d] probe 批次 C3 · exit 0 · FAIL · stdout f89e0dc4c19d · **已作废**（Label/expect mismatch: I set expect_exit=1 intending to capture the PRE-fix red baseline, but the gate-4 seeding fix was already compiled when this ran, so the probe actually PASSED (exit 0, RESULT PASS cases=4 pass=4 skip=0 enhanced=true). The run itself is valid green evidence but its recorded verdict is FAIL, so it is excluded from PASS counting; superseded by a correctly-labelled fresh green run.）
- [ev-20261110155525-146b] probe 批次 C3 · exit 0 · PASS · stdout f89e0dc4c19d
- [ev-20261110155544-b835] probe 批次 C3 · exit 0 · PASS · stdout 61ba53fa3123
- [mut-20261110155609-a0cf] mutation 批次 C3 · inconclusive · 基线 1 → 变异 -
- [mut-20261110155620-eeec] mutation 批次 C3 · error · 基线 0 → 变异 -
- [mut-20261110155642-4c2d] mutation 批次 C3 · killed · 基线 0 → 变异 1
- [ev-20261110155811-2fd6] probe 批次 C3 · exit 0 · PASS · stdout 713abefdd695
- [ev-20261110155849-3f51] probe 批次 C3 · exit 0 · PASS · stdout 0dd20eeda50c
- [ev-20261110155943-315b] probe 批次 B3 · exit 1 · FAIL · stdout e3b0c44298fc
- [ev-20261110160009-c466] probe 批次 C3 · exit 0 · PASS · stdout 74179abe54a3
- [ev-20261110160100-6c28] probe 批次 A3 · exit 0 · PASS · stdout 7fbb6b5287f3
- [ev-20261110160104-6178] probe 批次 B3 · exit 0 · PASS · stdout 434b39b5872a
- [ev-20261110160119-7525] probe 批次 A3 · exit 0 · PASS · stdout 1227cf685a0a
- [ev-20261110160143-025f] probe 批次 A3 · exit 0 · PASS · stdout 90a27e410aaf
- [ev-20261110160206-94b0] probe 批次 A3 · exit 0 · PASS · stdout 35efcd5c2882
- [ev-20261110160243-bd87] probe 批次 A3 · exit 0 · PASS · stdout 45347362a945
- [mut-20261110160156-60a7] mutation 批次 B3 · killed · 基线 0 → 变异 1
- [mut-20261110160326-a789] mutation 批次 B3 · killed · 基线 0 → 变异 1
- [ev-20261110160535-214b] probe 批次 A3 · exit 0 · PASS · stdout c6fa33d3403a
- [ev-20261110160541-dee2] probe 批次 A3 · exit 0 · PASS · stdout 775caf196a8a
- [mut-20261110160453-c825] mutation 批次 B3 · killed · 基线 0 → 变异 1
- [mut-20261110160649-4696] mutation 批次 A3 · error · 基线 0 → 变异 1
- [mut-20261110160649-7855] mutation 批次 B3 · killed · 基线 0 → 变异 1
- [mut-20261110160826-2db7] mutation 批次 A3 · killed · 基线 0 → 变异 1
- [mut-20261110160849-cb8c] mutation 批次 A3 · killed · 基线 0 → 变异 1
- [mut-20261110160843-8aa8] mutation 批次 B3 · error · 基线 0 → 变异 -
- [mut-20261110160916-bb5a] mutation 批次 A3 · killed · 基线 0 → 变异 1
- [ev-20261110161016-f01b] probe 批次 A3 · exit 0 · PASS · stdout 8a962aa46c0a
- [mut-20261110161003-c348] mutation 批次 B3 · error · 基线 0 → 变异 -
- [mut-20261110161108-50ae] mutation 批次 B3 · killed · 基线 0 → 变异 1
- [ev-20261110161232-82ed] probe 批次 A3 · exit 0 · PASS · stdout e2bf96e8d85b
- [ev-20261110161329-62b2] probe 批次 A3 · exit 0 · PASS · stdout 711a6802c8c3
- [ev-20261110161331-24b0] probe 批次 A3 · exit 0 · PASS · stdout 1227cf685a0a
- [mut-20261110161244-4047] mutation 批次 B3 · killed · 基线 0 → 变异 1
- [mrg-20261110161425-e1c4] merge 批次 C3 · OK · 46f1357
- [ev-20261110161450-b361] probe 批次 C3 · exit 0 · PASS · stdout 5d7b5c58edcd
- [mrg-20261110161451-6ad4] merge 批次 A3 · OK · 0f96381
- [ev-20261110161458-5613] probe 批次 A3 · exit 0 · PASS · stdout 91e58f268e80
- [ev-20261110161554-5224] probe 批次 B3 · exit 0 · PASS · stdout f6679b596145
- [ev-20261110161652-9878] probe 批次 B3 · exit 0 · PASS · stdout b0c42e1c3b02
- [ev-20261110161757-b71c] probe 批次 B3 · exit 1 · FAIL · stdout a0005b2222e4
- [ev-20261110161935-3973] probe 批次 B3 · exit 0 · PASS · stdout 7171ced187e0
- [ev-20261110162021-0923] probe 批次 B3 · exit 0 · PASS · stdout b7ae2d8b8195
- [ev-20261110162111-553c] probe 批次 B3 · exit 0 · PASS · stdout d607098b0c0c
- [mrg-20261110162137-98e2] merge 批次 B3 · OK · 792509a
- [ev-20261110162210-16e1] probe 批次 B3 · exit 0 · PASS · stdout b7f8d706cfe1

## 5. 缺口与风险 (1)
- 不变量 C3 无任何通过证据覆盖

## 6. 下一步
- 逐条补齐缺口；对弱测试补变异检查；重新 battle_report 后交给主指挥官审计。
