# 04 · 突破 `ReloadMixinInfo` 的三大结构契约

> 上游 `MixinInfo.reloadMixin` 会调 `Reloaded.validateChanges`，对
> `priority` / `targets` / `interfaces` 三处结构变化一律抛异常。本文记录**在允许它们变化**的前提下，
> 如何让 Mixin 的内部索引保持一致、并在失败时原子回滚。

## 1. `@Mixin(priority)`：final 字段 + 有序集合

`priority` 是 `MixinInfo` 的 `final` 字段，且 `MixinInfo` 被放进 `TreeSet`（按 priority 排序）。
重载时 `MixinReloadPolicy` 先：

1. 用 `Unsafe`/反射写入新的 `priority`（唯一能改 final 的手段）；
2. 从 `TreeSet` 摘除 → 重新插入（否则比较器与字段值不一致，集合内部结构会被破坏）；
3. 记录旧值快照，失败时回滚。

**必须先对齐再 reload**：`validateChanges` 读的是同一份字段，顺序反了等于没改。

## 2. `@Mixin(targets)`：就地迁移（`MixinRetarget`）

不能"detach + 重新登记"：目标类此时已加载，重新登记会撞 `MixinTargetAlreadyLoadedException`。
`MixinRetarget` 做**就地元数据迁移**，四处索引必须同时更新：

| 索引 | 作用 | 漏改后果 |
|:--|:--|:--|
| `mixinMapping` | Mixin → 目标 | 重织入找不到目标 |
| `unhandledTargets` | 尚未织入的目标集合 | 新目标永不织入 |
| `globalMixinList` | 全局 Mixin 清单 | 配置差异计算漏项 |
| 目标 `ClassInfo.mixins` | 目标 → Mixin 反查 | `transformSingleDescriptor` 不重映射 |

迁移前拍快照，任一步抛异常则整体回滚（不做"半迁移"）。

## 3. `interfaces`：双向对齐 + 层级变更的分离

- `+Interface`：把接口加进 `ClassInfo.interfaces` 与产物 `implements`；
- `-Interface`：从两者精确移除（不能只从字节里删，`ClassInfo` 里留着会让下一次织入重新加回来）；
- `ClassDiffUtil.mustRejectHierarchyChange(diff, enhanced)`：**父类变更永远拒绝**（任何 JVM 都不支持），
  **接口增删在 JBR/DCEVM 下放行**（增强重定义支持接口表变化）。两者混在一起拒绝会让 `Duck` 接口这类
  合法热更变成"必须重启"。

## 4. `re-entrant lock` 瞬态

reload 与织入共享 Mixin 的全局锁，同一批次内的重入会抛 `Cannot reload mixin if re-entrant lock entered`。
`MixinHotSwap` 对该瞬态做**有界退避重试**（固定次数 + 短退避），耗尽才报失败；不得无限重试。

## 5. 判据清单

1. `priority` 写入必须在 reload 之前，且同步重排有序集合；
2. `targets` 变化只能就地迁移四处索引，失败必须整组回滚；
3. 接口增删必须同时改字节与 `ClassInfo`，父类变更必须拒绝；
4. 重入锁只做有界退避，不得无限重试。
