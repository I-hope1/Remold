# 运行时架构与性能约束

> **实现状态与审计**：本规范定义客观的架构与设计规格。当前实现状态、验收证据与详细审计参见 [docs/status.md](../status.md) 与 [AGENTS.md](../../AGENTS.md)。

---

## 1. JBR-21 / DCEVM 运行时能力与约束

在开启增强类重定义能力的环境下（JBR-21+ 或 DCEVM `-XX:+AllowEnhancedClassRedefinition`）：

1. **NestMembers 与 InnerClasses 修改**：
   支持向宿主类追加新的 `NestMembers` 和 `InnerClasses` 属性，调用 `redefineClasses` 成功返回，新内部类可正常访问宿主私有成员。
2. **继承体系守卫（拒绝变更）**：
   当检测到类继承关系变更（父类或接口修改，`diff.hierarchyChanged`）时，系统选择安全熔断并拒绝重定义，提示重启应用，确保全局类型系统的稳定性。
3. **初次加载拦截通道**：
   通过 `AnnotationTransformer.pendingAlignedClasses` 覆盖所有尚未加载的目标类（无论是全新类还是被改名的历史类），确保类加载器首次读入的字节码即为对齐后的正确产物。
4. **能力探测判据**：
   增强能力只认「运行时身份」与「flag 开启」两个条件同时成立。
   `-XX:+AllowEnhancedClassRedefinition` 是 DCEVM 系专有参数：标准 HotSpot 单独加它会在启动阶段报
   `Unrecognized VM option` 并终止；若同时加了 `-XX:+IgnoreUnrecognizedVMOptions`，标准 HotSpot 会
   **接受并忽略**它，但 `RuntimeMXBean.getInputArguments()` 仍会原样回显该字符串 —— 因此参数文本单独
   不构成能力证据，必须叠加厂商身份（`java.vm.vendor` / `java.runtime.version` 含 `JetBrains` /
   `DCEVM` / `Trava`）判定。
   探测不通过时，含字段或方法结构变化的类在提交 `redefineClasses` 之前被摘出本批：标准 HotSpot 不允许
   schema 变化，而批量重定义是全有或全无，放行该类会让同批次纯方法体修改一起失败。
5. **无增强能力时的降级开关**：
   探测不通过时，强制关闭 {@code remold.agent.force_static_lambda}，并以
   `DEGRADED: <开关名> forced OFF. <原因>` 写入初始化日志（用户显式配置的值不会被静默丢弃）。
   理由是它<b>会自己制造 schema 变化</b>：把实例 lambda 改写为「静态 + 显式 `this` 首参数」会改变方法
   描述符，在标准 HotSpot 上放行它等于每一轮都主动失败一次。

   `remold.agent.init_fix` 则<b>不降级</b>：它不会失败，只是没机会执行——新增字段构成的 schema 变化
   已在上一条被整类摘出。强制关闭换不来任何收益，却会波及所有<b>直接</b>调用 `InitFix.transform` 的
   路径（回归套件 `InitFixOracle` 就在标准 JVM 上跑全部场景），让它们静默空转。降级只关「做了就会失败」
   的东西，不关「做了也没用」的东西。

---

## 2. 事务生命周期与并发控制

### 2.1 事务阶段规范
```
prepare -> tx.preRegister() -> inst.redefineClasses() -> tx.commit()
```
* **乐观预登记（Pre-Registration）**：在执行 `redefineClasses` 之前对所有事务执行 `preRegister()`，消除"宿主重定义成功到事务提交之间并发线程首次加载未加载类时读到磁盘错位产物"的竞态。
* **降级与一致性校验**：当批量重定义失败时，自动切换至单类降级重定义，并按事务组校验一致性（宿主与下属全部匿名类均成功才 `commit`，否则 `rollback(true)`）。

### 2.2 并发互斥与短路优化
* **全局独占锁（`HOTSWAP_LOCK`）**：热更入口与事务执行覆盖全局锁，杜绝并发热更重入与内部状态竞争。
* **字节码摘要短路**：在差分比对阶段，新旧字节码完全相同时直接短路跳过；在重命名改写阶段，若 `renameMap` 全为恒等映射则直接返回原字节码，避免不必要的解析与写回开销。

---

## 3. 算法复杂度与安全闸门

### 3.1 复杂度特征
单宿主类内对齐时间复杂度为 $O(N^2)$（其中 $N$ 为匿名类数量）。

### 3.2 性能安全闸门
1. **数量硬上限（`MAX_ANON_PER_HOST = 128`）**：
   单个宿主类下匿名类数量超过 128 时，抛出 `AlignmentRejectedException` 整体拒绝该宿主组。坚决不采用"降级为不重命名"策略，因为不对齐会直接打开编号位移篡夺的内存安全漏洞。
2. **软超时中断（`ALIGN_TIMEOUT_MS = 2000`）**：
   对齐流程设定 2000ms 软超时，采用 elapsed 方式比较（`now - start`）。检查点设立在 `parseInfos` 之后、每个层级循环入口以及 `matchTier` 的外层循环处。超时立即触发宿主组原子拒绝。

---

## 4. 系统特性开关配置

系统支持通过 JVM 系统属性控制对齐与安全门行为，首选属性名遵循 `remold.agent.*` 规范，同时兼容 `remold.anonAlign.*` 历史别名：

| 首选属性名                     | 兼容别名                 | 默认值   | 语义与行为                                                               |
|:-------------------------------|:-------------------------|:---------|:-------------------------------------------------------------------------|
| `remold.agent.anon_align`        | `remold.anonAlign.enabled` | `true`   | 对齐总开关。为 `false` 时含匿名类的宿主整体拒绝，杜绝编号位移篡夺        |
| `remold.agent.anon_strict`       | `remold.anonAlign.strict`  | `false`  | 严格模式开关。开启后遇 Tier 4 歧义或 `depth > 4` 直接拒绝宿主组          |
| `remold.agent.anon_debug`        | `remold.anonAlign.debug`   | `false`  | 诊断日志开关。打印完整的层级决策链与详细匹配计数                         |
| `remold.agent.anon_layout_gate`  | -                        | `reject` | 匿名类捕获字段布局门模式：`reject`（有存活实例拒绝）/ `warn` / `off`     |
| `remold.agent.layout_gate`       | -                        | `reject` | 重定义层显式字段布局门模式：`reject`（删/改类型拒绝）/ `warn` / `off`    |
| `remold.agent.local_class_guard` | -                        | `reject` | 同名局部类编号漂移止血门模式：`reject`（同名 >= 2 拒绝）/ `warn` / `off` |
