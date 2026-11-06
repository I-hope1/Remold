# E2 修复设计：继承字段的偏移漂移检测

本文只定义设计，不含实现代码。对应已记录风险 14（P2）：`Host$1.property` 实际声明在 `XXX`，`XXX` 新增字段后偏移 16 → 20，旧偏移读到新字段 `added=9`，而 `SuperclassDriftGate` 未拒绝。

## 1. 根因

`LayoutPredictor` 的子孙漂移扫描只取 `liveDescClass.getDeclaredFields()`；`SuperclassDriftGate.checkSuperclassDrift` 的兜底也只取 `getDeclaredInstanceFields`。继承字段既不在子类的 declared 集合里，也不在预测器的比较集合里，因此：

- 预测结果 `driftingFields` 为空；
- `OffsetBindingIndex.lookup(子类, 继承字段)` 永远不会被查询；
- 父类 redefine 照常执行。

## 2. 修复原则

1. **不改变“有绑定才阻断”的判据**：只对 `OffsetBindingIndex` 中真实存在的绑定拒绝，不因为“父类新增字段”一律拒绝。
2. **解析到声明类**：绑定 target 是子类时，字段沿继承链解析到声明类；索引查询同时尝试 `(target, field)` 与 `(declaring, field)`。
3. **离线层级**：解析走 `ClassHierarchyOracle`/已加载 Class 的 `getSuperclass()`，不使用 `Class.forName` 触发新加载。
4. **失败关闭**：解析歧义（同名遮蔽、接口字段、private）时按“可能漂移”处理，不静默放行。

## 3. 方案

### 3.1 LayoutPredictor：把继承字段纳入比较

在 `findDriftingFields` 的子孙扫描中，对每个 `liveDescClass`：

- 收集 `declared fields`（现有）；
- 再沿 `getSuperclass()` 向上收集可访问的实例字段，直到 JDK 边界；
- 对每个字段解析其**声明类** `declaring`；
- 旧偏移：`UNSAFE.objectFieldOffset(declaring.getDeclaredField(name))`（对 live 实例有效）；
- 新偏移：在预测加载器中取 `predictedDeclaring` 的对应字段，`UNSAFE.objectFieldOffset`；
- 若偏移不同，产出 `DriftingField(descendantInternal, fieldName, oldOffset, newOffset)`，并携带 `declaringInternal`（新增字段或旁路映射）。

注意：预测加载器里的继承字段必须来自**同一预测批次**的父类新字节码；不能回退到 live 父类，否则又会得到旧偏移。

### 3.2 SuperclassDriftGate：解析绑定

`checkLayoutDrift` 对每个 `DriftingField df`：

```text
target = df.owner()                 // 子类
declaring = df.declaring()          // 声明类
hit = index.lookup(target, df.fieldName())
   or index.lookup(declaring, df.fieldName())
```

若 target 绑定存在但 declaring 不同，视为同一物理字段的别名，命中一次即可；日志同时打印 target 与 declaring，避免用户只看到子类名。

### 3.3 OffsetBindingIndex

不需要重写索引；在查询侧增加“declaring 回退”即可。若后续要支持 `targetField=null` 通配，仍按现有 wildcard 逻辑，不扩大范围。

## 4. 负向与正向对照

- **E2（继承字段）**：必须从“未检测”变为 `LAYOUT-REJECT [SUPERCLASS_FIELD_DRIFT]`；旧偏移 16 不再可读。
- **E2-own（XXX 自身字段）**：仍拒绝（现有行为），证明修复没有只对子类特判。
- **E3（子类声明字段）**：仍拒绝。
- **E1（无绑定）**：仍放行并由 InitFix 补 `added=9`，证明没有把所有父类新增字段一刀切拒绝。
- **无漂移绑定**：绑定字段偏移不变时不得拒绝（避免误杀）。

## 5. 风险

- 继承字段可能被同名遮蔽；解析必须按 JVMS 5.4.3.2 落到实际声明类，不能只按名字。
- private 父类字段在子类不可见，但 `Unsafe` 仍可读；检测只做偏移比较，不写字段。
- 预测器多扫继承链会增加成本；只在有子孙且有绑定的父类变更时启用，避免全量扫描。

## 6. 验收

1. E2 从 `[KNOWN-BUG]` 变为 `[PASS]`（拒绝 + 旧偏移不再读到 `property`）。
2. E2-own/E3 保持拒绝；E1 保持放行 + `added=9`。
3. `LayoutPredictorTest` 增加“继承字段 + 点分 batch key”用例（与已修的 key 归一叠加）。
4. 变异检查：把 declaring 回退去掉，E2 必须重新变红。
