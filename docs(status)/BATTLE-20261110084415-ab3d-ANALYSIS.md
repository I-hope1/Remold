# 战报 · 战役 battle-20261110084415-ab3d

> 生成时间 2026-11-10T08:50:29.035Z · 阶段 integrating · 基线 main@0a24aa68bcf5 · 证据 6 条（已作废 0）

## 1. 目标
修复 Mixin→Target 描述符重映射在 priority/interfaces/retarget 往返后失效：保证 ClassInfo.cache 中已注册 Mixin 的条目恒等于 MixinInfo.getClassInfo()（isMixin=true），使匿名内部类 this$0 的构造器签名不再停留在 Mixin 类上（NoSuchMethodError），并发布 0.2.7。

## 2. 批次结果
| 批次 | 标题 | 耦合 | 状态 | 提交数 | 探针(通过/总) | 变异(击杀/总) | 合流 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| P4 | Mixin→Target 描述符重映射修复：ClassInfo.cache 锚回 MixinInfo.getClassInfo() | high | merged | 1 | 4/4 | 1/1 | ✓ |

## 3. 不变量证据矩阵
| 不变量 | 声明 | 覆盖证据 |
| --- | --- | --- |
| C1 | ClassInfo.cache 中每个已注册 Mixin 的内部名条目必须与 MixinInfo.getClassInfo() 引用同一且 isMixin()==true；任何驱逐/懒加载都不得让普通 ClassInfo（isMixin=false）留在该键上。 | 202611100846, 202611100847, 202611100850 |
| C2 | 含匿名内部类的 Mixin 经历 priority→interfaces→retarget A→B→A 往返后，目标类合成匿名类 <Target>$Anonymous$<hash> 的 <init> 签名与宿主调用点 100% 一致；运行期实例化不得抛 NoSuchMethodError/IllegalClassLoadError。 | 202611100846 |
| C3 | 修复必须幂等且 O(已注册 Mixin 数)：每次登记/重载成功后重新锚定，重复调用为 no-op；取不到 ClassInfo.cache 或结构不符时只 warn（点名），不打断热更主链路。 | 202611100846, 202611100847, 202611100850 |

## 4. 实证台账（压缩）
- [ev-20261110084637-8872] probe 批次 P4 · exit 0 · PASS · stdout 68dc37541348
- [ev-20261110084709-4e09] probe 批次 P4 · exit 0 · PASS · stdout e7fe546f8754
- [mut-20261110084719-14b3] mutation 批次 P4 · killed · 基线 0 → 变异 1
- [mrg-20261110084813-a3b3] merge 批次 P4 · OK · c8f52cf
- [ev-20261110084832-d90f] probe 批次 P4 · exit 0 · PASS · stdout 917449c82014
- [ev-20261110085022-85a4] probe 批次 P4 · exit 0 · PASS · stdout 83b7443f3ea8

## 5. 缺口与风险 (0)
- 无

## 6. 下一步
- 可执行 battle_audit 复核；通过后 battle_seal 结案。
