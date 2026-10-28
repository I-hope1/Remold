# 08 测试判定力审计（"从未红过"的用例）

适用：新增或修改 `InitFix` 枚举补齐、安全门、JVMTI 堆遍历相关测试时阅读。

本文记录一次**限定范围**的判定力审计：不扫全部用例，只抽查三类高风险测试——
依赖环境条件才能触发的、断言"没发生"的、靠日志或副作用间接判定的。
方法统一为**变异检查**：注入一处破坏，看对应用例是否变红；变绿即为空转嫌疑。

审计结论写在这里而不是 `docs/status.md`，因为这是**一次性证据**而非长期状态；
状态表只登记由审计产生的缺口。

## 1. 审计范围

| 用例 | 关注点 | 高风险类别 |
|:--|:--|:--|
| `InitFixSnapshotTimingTest` | 存活实例枚举补齐 | 依赖 native 堆遍历 |
| `initFixOracle`（48 场景 / 318 断言） | 切片安全门 | 断言"拒绝"这类未发生事件 |
| `LayoutGateWiringTest` | 布局门接线（`decideLayout`） | 纯函数，易写成自证 |
| `FieldDescriptorMaskTest` | 布局门规则表（`LayoutGate.check`） | 同上 |
| `LocalClassGuardTest` | 局部类位移止血 | 断言"拒绝" |
| `HashSemanticsTest` | 新增哈希语义门 | 断言"检出" |
| `ConstructorTailInjectorTest` / `StageASkeletonTest` | 两阶段组件（已冻结） | 纯字节码变换 |
| `remold.LibToolTest` | JVMTI 堆遍历 | 依赖 native、断言"包含" |
| `CompeteDeleteTest` / `LocalClassNameDriftTest` | 匿名类对齐 | 断言"未发生" |

纯字节码解析/常量池类单测（`NameIndexTest`、`SwitchMapAlignTest`、`MethodRefForwardTest` 等）
按约定不在本次范围——它们的断言是精确值比较，结构上不可能空转。

## 2. 变异检查结果

每次只改一处，跑完立即还原，`git status` 收尾必须为空。

| # | 注入的破坏 | 运行的用例 | 结果 |
|:--|:--|:--|:--|
| A | `InitFix.applyPatch` 里 `alive = collectInstancesForPatch(host)` 改成空表 | `InitFixSnapshotTimingTest` | **红**（`instanceCreatedAfterSnapshotIsStillPatched`） |
| B | `LayoutGate.check` 首行恒返回 `COMPATIBLE` | `FieldDescriptorMaskTest` | **红**（`namedInnerClassRefNotMasked`） |
| C | `LayoutGate.checkChangedFields` 首行恒返回 `COMPATIBLE` | `LayoutGateWiringTest` | **红，7/7** |
| D | `LocalClassGuard.collisions` 恒返回空表 | `LocalClassGuardTest` | **红，5 条** |
| E | `InitFix.checkSafe` 首行恒返回 `null`（永不拒绝） | `initFixOracle` | **红，124/318 断言失败** |

两点值得记下来：

1. **变异 B 最初跑的是 `LayoutGateWiringTest`，结果全绿。** 这不是用例空转，而是两者守的不是同一个函数：
   `decideLayout` 走 `checkChangedFields`（`changedFields` 字符串协议），`LayoutGate.check` 走字段列表。
   前者由 `LayoutGateWiringTest` + 变异 C 覆盖，后者由 `FieldDescriptorMaskTest` + `layoutGateAssert` 覆盖。
   教训：**变异必须打在用例真正依赖的那个函数上**，打偏了会误判成"用例无判定力"。
2. 变异 E 打掉安全门后 `initFixOracle` 失败 124/318，说明"拒绝"这类否定断言并非空转——
   它们会随安全门一起失效。

## 3. 发现的缺口

### 3.1 `LibTool.getInstances` 的匹配范围无测试（已补）

原 `heapScanFindsLiveInstances` 只断言 `length >= 2` 且返回数组包含 d1/d2，属于**下界**断言：
它能发现"少返回"，但发现不了"多返回"或"匹配范围偏移"。而匹配范围直接决定补丁循环会写到哪些对象上：

- 若父类查询**不含**子类实例 → 子类实例的新增字段永久停在默认值（漏补）；
- 若子类查询**含**父类实例 → 补丁会写进一个根本没被重定义的类（`WrongMethodTypeException` 或静默错写）。

新增 `heapScanIncludesSubclassesButNotSuperclasses` 双向断言，实测通过：
`getInstances(P)` 含子类实例，`getInstances(C)` 不含父类实例。

这条同时把「单次堆遍历按类分发」那个待办方案的语义固定下来了——它必须复现这个**不对称**，
而不是简单地"按 klass 精确匹配"。

**局限**：该断言的方向性来自 native 实现，本地无法注入破坏（不能改 C++ 后重编），
因此没有拿到"去触发"的红。它属于**断言了终局状态**的守卫，不是经过红绿验证的守卫。

### 3.2 记录但未修：`heapScanFindsLiveInstances` 的下界断言

`assertTrue(instances.length >= 2)` 保留原样。改成 `== 2` 会与"堆里可能有其它测试遗留实例"耦合，
而判定力已由 `contains(d1)`/`contains(d2)` 与 3.1 的双向断言承担，收益不足。

## 4. 不采纳的方向

**JIT 断言后果（哨兵值校验）**：原计划用"旧字段哨兵未被破坏"来断言陈旧分配点真的被执行过。
但本项目无法给 DCEVM 注入 bug，该断言永远见不到红；它只能算"断言了后果的探针"，不具判定力。
`07-p05-probe-results.md` §11 已记录 JIT 场景的对照组证伪结论，此项**挂起，不再投入**。

## 5. 结论

抽查的 5 条机制全部拿到红，无空转用例。审计产出一个真实缺口（3.1），已补测试；
产出一个待办方案的语义约束（子类不对称）。四项待办（JIT 两种对照变体、JIT 断言后果、
从未红过的测试审计、STW 内外拆分）就此闭环。
