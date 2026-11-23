# 匿名类非合成字段形态矩阵实测（JBR 21 DCEVM 真机，轻量验证）

> 文档类型：实测记录。这里只记读数与口径；实现状态、残余风险以 [`docs/status.md`](../status.md) 为准。

## 1. 要回答的两个问题

主指挥官裁定口径（本文件即该项验证的收队读数）：

1. **护城河**：`layout_gate=reject` 下，普通（javac）匿名类的非合成字段在 *新增 / 改类型 / 缩容* 三种形态上，
   是否都按契约被拦死或合法放行、有没有漏网；
2. **连坐边界**：`layout_gate=warn`（照旧原地重定义、只告警）下，除被改字段之外，夹具里**没被修改的旁观字段**
   是否纹丝不动 —— 即 JBR 的实例重排会不会跨槽位污染。

## 2. 夹具与判据

- 夹具：`drillprobe.AnonHost.make()` 返回普通匿名类实例（`AnonHost$1`），声明三个**非合成**字段：
  `payload`（被试）、`bystander`（旁观对照，三版本签名与值都不变）、`dropped`（缩容牺牲品）；
  另有一个逐字节不变的对照类 `drillprobe.DrillCtl`（含自己的匿名类）。
- 每轮独立 JVM，探针持有 **2 个存活实例**；判据一律走实例方法 `ver()` / `read()`
  （redefine 后反射字段元数据可能仍是旧布局，只作诊断打印）。
- 哨兵口径与真机台账一致：`-Dremold.probe.heapwatch=true`、`heapwatch.class=drillprobe.AnonHost$1`、
  `heapwatch.field=payload`，切面 before-redefine / after-redefine / after-field-patch。
- 跑法与原始日志：[`tests/anon-field-shape-drill/`](../../tests/anon-field-shape-drill/README.md)。

## 3. 读数矩阵（7 格，全部一轮新 JVM）

| 形态 | 档位 | 是否生效 | 实例读数 `read()` | 旁观字段 | 哨兵 `newlyNull` | 判决 |
|:--|:--|:--|:--|:--|:--|:--|
| 新增 `extra` | `reject` | 是（两阶段） | `P-v1\|B-v1\|D-v1\|E-v2` | `B-v1` 未动 | 0 | 合法放行 |
| 新增 `extra` | `warn` | 是（两阶段） | 同上 | `B-v1` 未动 | 0 | 合法放行 |
| 改类型 `Object→String` | `reject` | **否** | `P-v1\|B-v1\|D-v1` | `B-v1` 未动 | 0 | 被拒 |
| 改类型 `Object→String` | `warn` | **否** | 同上 | `B-v1` 未动 | 0 | 被拒 |
| 改类型 + `anon_layout_gate=warn` | `warn` | **否** | 同上 | `B-v1` 未动 | 0 | 被拒 |
| 缩容删 `dropped` | `reject` | 是 | `P-v1\|B-v1` | `B-v1` 未动 | 0 | `WAIVE_NO_READERS` 放行 |
| 缩容删 `dropped` | `warn` | 是 | 同上 | `B-v1` 未动 | 0 | 同上 |

沿用 `drillprobe.DrillCtl` 的对照读数：7 格全部 `ctl=K-v1`（参与同批但不被改的类完全不动）。

## 4. 结论（读数支撑，不是推演）

1. **改类型在普通匿名类上到不了 JBR 的丢值路径。** 拦截有**三道**，且第二道没有档位：
   ① `ANON-LAYOUT` 配对门（`remold.agent.anon_layout_gate`，默认 `reject`，可 `warn`）——
   本夹具首个开火点，日志 `REFUSED to pair (incompatible layout with live instances)`；
   ② **后置换名布局校验**（`ANON_ALIGN_VALIDATION`）——把两档都放宽到 `warn` 后仍然硬拒，
   日志 `post-rename field layout mismatch on field 'payload' … cannot be redefined safely`；
   ③ 全局 `LayoutGate`（`remold.agent.layout_gate`）。
   因此"普通匿名类改类型 → `newlyNull>0`"这条**在可配置口径下不可达**；此前把它写成可验证预测是错的，
   以本读数为准。
2. **新增与缩容合法放行，且不连坐。** 新增字段走两阶段并补值（`E-v2` 写在存量实例上），缩容按
   "批次外读者为空"判 `WAIVE` 放行（本夹具读者只在类内）；两者旁观字段与既有字段全部保留，`newlyNull=0`。
   即：JBR 的实例重排在"同名字段表只增 / 删尾"时**没有观测到跨槽位污染**。
3. **缩容不是一律被拦**（与"三种形态 100% 拦死"的假设不同）：判据是批次外读者集合，不是"字段数变少"本身。

## 5. 主语澄清：`raw/reachable` 与 `newlyNull` 量的不是同一个类

此前台账里"`live{raw=0}` 时仍然 `newlyNull=230` ⇒ 污染与实例数无关"是一句**主语打架**的表述，必须拆开写：

| 读数 | 主机 | 来源 | 含义 |
|:--|:--|:--|:--|
| `live{raw,reachable}` | **被重定义的合成匿名类**（`<Target>$Anonymous$<hash>`） | `MixinInnerClassHotSwap.syntheticLive(Class)` → `LibTool.getInstances`，`live` 取 `findLoadedClass(合成类名)` | 该合成类在堆上的实例数 |
| `newlyNull` | **目标/宿主类**（默认 `net.minecraft.predicate.entity.LootContextPredicate`，字段 `combinedCondition`） | `heapwatch` 哨兵三切面对**被监视类**的实例逐一切面比对 | 被监视字段"原来有值、现在变 null"的实例数 |

两者是不同类，所以正确读法是：**闸门的 liveness 主语（合成类）覆盖不到真实受损主语（宿主类实例）**，
而不是"污染与实例数无关"。判决（成员 schema 变化无条件拒绝）不变、且照样正确——它是对的结论；
但配套理由必须按上表重述。另 `[推断]` 标注保持不变：`ModifyInstances` 内部机理未直读 JBR 源码，
"跨类重排"仍是推断。

## 6. 顺带观测到的口径问题（未修，登记待裁）

同一轮日志里出现**自相矛盾的汇总**：

- 改类型（1 阶段）格：`HotSwap successful: 2 classes redefined.` 与
  `2 class(es) were skipped this round and did NOT take effect` 并存，而终局读数 `ver=1` 证明**没有生效**；
- 新增 / 缩容（两阶段）格：变更文件只有 2 个类，却报 `HotSwap successful: 4 classes redefined`。

即"成功计数"既不等于实际生效类数，也不等于变更文件数（疑似按阶段/入口重复计数）。这是**假成功**形态的
可观测性缺陷，属既有汇总口径问题，本次验证不修。
