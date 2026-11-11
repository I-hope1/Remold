# 01 · 核心不变量与类加载器权威仲裁

> 本文只定义**客观规格与判据**，不登记实现状态。逐项落地位置与验收证据见 [`docs/status.md`](../status.md)。
> 证据标签纪律：`[观测]` = 有日志/断言/实测读数；`[推断]` = 依赖未验证前提；`[假设]` = 待实验。

## 1. 不变量 M1：同名类的权威副本唯一

JVM 允许同一个点分名在多个加载器下各定义一份。Remold 的一切判据（布局门、`InitFix`、重定义）都必须
作用在**宿主真正使用的那一份**上，否则会在裸副本上做无用功、并把诊断引向错误方向。

仲裁顺序（`core/src/remold/ClassCopySelector.java`）：

| 级别 | 加载器 | 权威度 | 理由 |
|:--|:--|:--|:--|
| 1 | Bootstrap（`null`） | 最低（JDK 自身类，永不是热更目标） | 不可重定义 |
| 2 | System / Agent 自建的 `AppClassLoader` 副本 | 低 → **拒绝** | 未经过 Mixin / `PackageAccessFixer` / AW 变换，字节与宿主不同源 |
| 3 | 宿主加载器（Fabric `KnotClassLoader` / Forge `ModLauncher`） | 最高 | 唯一携带织入与平台预变换结果的副本 |

`ClassCopySelector.pickNamed(Class<?>[], dotName)` / `prefer(a, b)` 是唯一入口；`MixinHotSwap` 发现多副本时
把全部副本的加载器点名打进日志（`[Mixin] Multiple loaded copies of …`）再选权威副本。
`HeapFieldWatch` 解析哨兵类同样经此入口，**绝不**用 `ClassLoader.getSystemClassLoader()` 兜底。

## 2. 不变量 M2：初始基线不可被低权威副本覆盖

`RemoldAgent.updateInitialBaseline(dotClassName, runtimeBytes, sourceBytes, loader)`（`RemoldAgent:1840`）
维护 `bytecodeCache` 的初始基线：

- **高权威可替换**：宿主加载器带来的字节可以替换已有基线；
- **低权威拒绝**：`AppClassLoader` 之类的裸副本到达时**不覆盖**，只记一条 `[DUP-LOAD]`（`RemoldAgent:1854`），
  并附 40 帧调用栈点名"是谁又定义了一次"；
- **ephemeral 不入账**：一次性/临时加载器（`LayoutPredictor` 的抛弃式加载器、`InitFix` 的探测加载器）
  产生的定义**不写基线**——它们不是"JVM 里生效的那份"。

> `[观测]` 反例形态：真机上 `LivingEntity` 曾被 `AppClassLoader` 裸副本抢占基线，导致后续重织入产物以更窄
> 访问权限回送，JBR 报 `VerifyError: Bad access to protected data` → `InternalError`。见 §3。

## 3. 不变量 M3：访问权限单调不降级

`core/src/remold/ref/AccessAlign.java` 以**已加载运行时字节码**为基线，对重织入产物做单调对齐：
`public` 不得退化为 `protected`、`protected` 不得退化为包私有、基线已剥离的 `ACC_FINAL` 必须同步剥离。
送入 `redefineClasses` 之前有一道 fail-closed 自检门：对齐后仍比基线窄 → `[ACCESS-GATE]` 拒绝该次重定义。

配套的**来源侧**修复是"平台预变换重放"：`readOriginalBytes` 取到的是未经变换的磁盘/资源字节，
必须重放加载器在 Mixin 之前做的变换（Fabric dev 的 `PackageAccessFixer`、`AccessWidener`、Forge `AccessTransformer`），
否则产物天然更窄。

> `[观测]` 实证：真机 `MobEntity.isInAttackRange` 一次热更中 **112 个可访问成员**退化为 `protected`，
> JBR 增强重定义连带 link/verify 子类时抛 `VerifyError`。对齐层接入后同一序列不再出现。

## 4. 不变量 M4：`ClassInfo.cache` 必须锚回 `isMixin=true`

`MixinClassInfoRegistry.reassert(transformer)` 在每次 reload / 登记之后，把
`ClassInfo.cache[mixin]` 重新锚定到 `MixinInfo.getClassInfo()`。

**为什么必须有**：`ClassInfo.forName` 是懒加载的，reload 期间缓存条目被驱逐后，下一次织入会经它构造出
**普通** `ClassInfo`（`isMixin == false`）并写回缓存；此时 `MixinTargetContext.transformSingleDescriptor` /
`findRealType` 的 Mixin→Target 描述符重映射被整段跳过，匿名内部类 `this$0` 的构造器签名停留在 Mixin 类上，
运行期表现为 `NoSuchMethodError: <Target>$Anonymous$<hash>.<init>(Mixin, Target)`。

> `[观测]` 该异常在真实 KnotServer 上稳定复现，锚回后消失。

## 5. 判据清单（可机械核对）

1. 任何"按名字找类"的路径都不得回退到 `SystemClassLoader`；
2. 基线写入必须带加载器权威度判断，低权威只记 `[DUP-LOAD]`；
3. 重织入产物在 `redefineClasses` 前必须过访问权限自检门；
4. reload / 登记成功之后必须 `reassert` 一次 `ClassInfo.cache`。
