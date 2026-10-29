# 布局安全门、局部类防御与风险清单

> **实现状态与审计**：本规范定义客观的架构与设计规格。当前实现状态、验收证据与详细审计参见 [docs/status.md](../status.md)
> 与 [AGENTS.md](../../AGENTS.md)。

---

## 1. 实例状态布局安全门（`LayoutGate`）

### 1.1 核心问题：存活实例字段布局不一致

> **实测证据与决策**：参见决策记录 [D-ANON-4](08-decisions.md#d-anon-4)
> 与 [附录 A](08-decisions.md#附录-a-jbr-21-增强类重定义模式下-8-组字段布局实测数据)。  
> JBR-21 DCEVM 真机探针证实：增强重定义下 8/8 组字段变更均被 JVM 接受，但
> **改类型与改静态性将导致旧值被静默丢弃并置零值**。

在增强类重定义下，若类的字段布局（字段数量、类型、修饰符）发生变动：

* 已经存在的存活实例无法获得新字段的初始化空间与赋值，新代码在读取新字段时将读取到类型零值（引用为 `null`，数值为 `0`）；
* 同名字段若改变类型或静态性，存活实例的旧值会被丢弃并置零值；
* **字段删除是另一类问题**：实测（`docs/initfix/07-p05-probe-results.md` §13）布局不压缩、其余字段偏移不变、废弃槽位不复用，失败形态是**批次外读者抛确定性的 `NoSuchFieldError`**，而不是读到脏数据。判据是"**批次外读者为空**"（批次内扫新字节码、批次外扫当前字节码），已实现并接线：命中 `Fieldref` 才拒，且 owner 是编译期静态类型而非声明类，须按 JVMS 5.4.3.2 判"解析是否真的落在被删字段上"——沿继承树展开、父类遮蔽与接口截胡都要排除，private 例外（JLS §6.6，owner 恒为声明类）。不能简化成"有删除就拒"，那会误杀删 private 字段与正常连带重编的批次（做法见 §13.6）。**两个入口判决不同**：匿名类合成字段删除由 `LayoutGate.check` 判 `COMPATIBLE` 放行（读者必为空，可证），具名类字段删除由 `checkChangedFields` 判 `REMOVED_FIELD` 后再按读者集判。完备性取决于扫描范围（只扫已加载类会漏掉未加载的陈旧读者），扫描未完成时退回存活实例判据（`docs/status.md` 风险 12）。
* **方法删除另有机制**：实例方法在解析之后还有一层**向下分派**（JVMS 5.4.6），所以引用 owner 可以是 `A` 的**任何祖先**，失败形态有三种——`NoSuchMethodError`（整条链都没有）、`AbstractMethodError`（只有接口声明没有实现）、以及**静默分派转移**（祖先实现接住；实测连 `Methodref A.m` 本身都不抛错）。判据与字段共用"批次外读者为空"，但**扫描未完成时只能拒绝**——方法删除没有存活实例判据可以退。见 §15。
* 匿名类的合成捕获字段（`val$*` / `this$0`）被 `ClassDiffUtil` 过滤，无法通过 InitFix 补丁覆盖。

### 1.2 双入口协同防护机制

系统通过两道布局安全门形成互补防御：

1. **匿名类对齐器门（Tier 4 配对前）**：
    - 接入点：`AnonClassAligner.matchHierarchical`；
    - 判决函数：`LayoutGate.check(oldFields, newFields)`；
    - 职责：专门拦截匿名类 **合成捕获字段**（`val$*`, `this$0`）的新增、类型变更或静态性变更；
    - 存活实例感知：仅当该类存在存活实例时触发拒绝（无存活实例时新实例会执行新构造器，安全放行）。
    - 开关控制：`remold.agent.anon_layout_gate`（`reject` / `warn` / `off`，默认 `reject`）。
2. **重定义层全局门（逐类重定义前）**：
    - 接入点：`RemoldAgent.applyRedefineLayoutGate`；
    - 判决函数：`LayoutGate.checkRedefine(diff.changedFields)`；
    - 职责：拦截 **具名类及匿名类中用户声明的非合成字段**的删除、类型变更或静态性变更；
    - 判决三态：`COMPATIBLE` 放行、`WAIVED`（warn 模式放行但记录）、`REJECTED` 拒绝；
    - **静态字段强拒绝**：静态字段为类级共享状态，类只要加载就可能已有有效状态。因此静态字段变更命中时，即便存活堆实例判定为零，依然坚决拒绝（不可
      waive）；
    - 纯新增字段放行：由 InitFix 在重定义后为存活实例执行初始化补丁；
    - 开关控制：`remold.agent.layout_gate`（`reject` / `warn` / `off`，默认 `reject`）。

### 1.3 残余风险清单

* **TOCTOU 竞态窗口**：从堆遍历判定“无存活实例”到 `redefineClasses`
  真正生效之间，应用工作线程可能并发新建一个旧布局实例，导致其读到零值。由于该窗口极小且加全局锁停顿应用的代价过大，当前作为已知残余记录。

* **拒绝后的粘滞状态（设计如此，但容易被误解）**：拒绝只把宿主移出本批，运行态字节码**不变**
  （`dropFromBatch` 不动 `bytecodeCache`）。于是源码与运行态之间的差异被永久保留：下一次热更会
  diff 出同一条变更、得到同一个结论。这是必要的——放行就会让批次外读者在运行期抛
  `LinkageError`——但它有两个副作用：

  1. **该类的方法体改动也一起被丢掉**（整类不参与 redefine），用户改了方法却看不到效果；
  2. **只有四条路能打破循环**：把成员补回源码（类能继续更新，但再删一次仍是同样的拒绝）、
     改掉所有读者（唯一能真正删掉的路）、重启（源码与运行态重新一致）、
     `-Dremold.agent.layout_gate=warn`（强制放行，读者会在运行期抛错）。

  诊断：同一类因同一原因被拒第二次起，额外输出 `[LAYOUT-STUCK]` 说明这是循环而非"再热一次就好"。
  实现见 `RemoldAgent.noteRejected` / `REJECT_HISTORY`。

* **"成功"的计数不等于"你要的那个类生效了"**：`HotSwap successful: N classes redefined` 只统计
  进入 `redefineClasses` 且成功的类。被布局门 / 对齐器 / 结构门拦下的类不在其中，也不在任何一行
  汇总里 —— 用户看到"成功"、实际运行态仍是旧代码，只能靠翻全量日志才能发现自己关心的那个类被跳过。

  因此每轮维护一份"改了但没生效"的台账（`RemoldAgent.RoundSkips`），在成功行之后以
  `[HOTSWAP-SKIPPED]` 点名每个被跳过的类及其判决码（`LAYOUT-REJECT` / `ANON-ALIGN-REJECT` /
  `LOCAL-CLASS-REJECT` / `HIERARCHY-CHANGED` / `NO-ENHANCED-REDEFINE` / `BLACKLIST` /
  `NOT-MODIFIABLE` / `REDEFINE-FAILED` / `ROLLED-BACK`），宿主组的匿名类标注其归属。

  每条判决的格式是 **`CODE: 具体是什么 — 怎么办`**，一行读完不需要往上翻：

  ```
  [HOTSWAP-SKIPPED]   com.example.LightSpeedConveyor  <- LAYOUT-REJECT: field removed: COST — restore the removed field(s) or restart to continue
  [HOTSWAP-SKIPPED]   com.example.LightSpeedConveyor$1  <- LAYOUT-REJECT: ... (same host group as com.example.LightSpeedConveyor)
  ```

  "具体是什么"取自 `LayoutGate.Result.detail()`（已点名字段）；读者侧信息只在**确实扫出了东西**时
  追加（`3 out-of-batch reader(s)`），扫描没完成时追加 `(reader scan could not complete)` ——
  否则"只是删了个字段"看起来像"删字段就会被拒"，而实际规则是"删字段且证明不了没有批次外读者才拒"。

  三条刻意的取舍：
  1. **只收"本该生效却被拦下"的类**。磁盘哈希未变（本来就没改）不计入 —— 否则每轮刷出一大串
     无关类名，摘要立刻失去意义；那类计数单独输出为 `Unchanged: N class(es) had identical bytes on disk.`
  2. **走 `warn` 而非 `log`**。`log` 受 `DEBUG` 门控（默认关），摘要若走它就等于在发布版里消失，
     而"跳过了谁"恰恰是默认配置下最需要看见的信息。
  3. **只列前 15 个类名 + 总数**。整批被拒时不刷屏，但总数必须准确，否则会让人低估影响面。

  台账按轮次传递（随 `processChanges` 逐层下传，非静态共享），并发热更时两轮记录不会混。

---

## 2. 同名局部类编号漂移与止血门（`LocalClassGuard`）

### 2.1 局部类编号漂移风险机制

* javac 对局部类的命名模式为 `宿主$<N><简单名>`（如 `Foo$1Helper`, `Foo$2Helper`）。
* 局部类原名直通（不对齐）。若开发者在代码前部新增、删除或换序局部类，后续同名局部类的物理编号将整体位移。
* JVM 重定义时将按照物理类名一一对应，导致老实例的方法实现被无关的新类替换，产生静默错配。
* 局部类的捕获字段同样被 `ClassDiffUtil` 过滤，常规字段差分无法识别。

### 2.2 止血门机制与当前覆盖边界

* **判据**：自身 `InnerClasses` 条目中 `outerName == null && innerName != null` 且字节码包含 `EnclosingMethod`。
* **归并与碰撞检测**：按 `(EnclosingMethod.owner, innerName)` 归并；新旧任一侧同名计数 $\ge 2$ 即判定为潜在位移风险。
* **熔断处置**：一旦命中，把该宿主及其 `host$...` 整族全部移出本轮重定义（复用宿主组原子拒绝通道），存活实例继续保持旧逻辑执行。
* **开关控制**：`remold.agent.local_class_guard`（`reject` / `warn` / `off`，默认 `reject`）。
* **当前覆盖边界与已知残余**：
  当前止血门仅在检测到“同名局部类计数 $\ge 2$”时熔断。对于**“唯一简单名局部类内部的匿名类位移”**（例如单一 `Foo$1Helper`
  内部包含多个匿名类发生位移），当前未予拦截。该形态属于已知残余，完整对齐机制尚待后续推进。

---

## 3. 蜕变测试准则 (Metamorphic Testing Suite)

为严格守卫拓扑对齐系统的确定性与安全性，确立四项蜕变测试准则：

1. **语义标记置换不变性 (Semantic Tag Permutation Invariance)**：
   为测试匿名类嵌入唯一的语义字符串（如 `"TAG_SAVE"`）。对新旧输入集合执行随机打乱，断言所有映射关系恒定，且目标语义标记 100%
   精准对应。
2. **幂等性测试 (Idempotence)**：
   对同一份新编译产物连续执行两次对齐，断言第二次对齐的映射结果为恒等映射（无多余改名）。
3. **尾部追加不变性 (Append Invariance)**：
   在源码末尾追加全新的匿名类，断言之前所有已配对的一级与嵌套匿名类映射结果不受任何扰动。
4. **故障注入拒绝率 (Failure Injection Reject Rate)**：
   故意构造高危歧义场景（如两个完全同构且无结构差异的候选），断言系统 100% 触发 Reject & Rollback，无静默错配。

---

## 4. 编译器未决问题跟踪

### ECJ 编译器特殊表现

* **switch 表差异**：ECJ 不生成 `Foo$N` 形式的枚举 switch 映射类，而是生成宿主类内部的 `$SWITCH_TABLE$` 方法。因此 switch
  类的对齐与排除对 ECJ 不适用。
* **嵌套类 EnclosingMethod 表现**：ECJ 在多层嵌套匿名类下的 `EnclosingMethod` 属性结构与 javac 存在差异，需持续进行样本特征跟踪。
