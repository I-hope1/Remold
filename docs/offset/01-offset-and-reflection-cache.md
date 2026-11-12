# 物理内存偏移与第三方缓存驱逐规格说明

本文档定义类重定义（Redefine）场景下物理字段偏移量（Field Offset）漂移的防护体系（P0）与第三方反射/序列化框架缓存驱逐契约（P1）。
实现状态唯一真相源参见 [docs/status.md](../status.md)。实测探针源码参见 [OffsetStabilityProbe.java](../../tests/src/OffsetStabilityProbe.java)。

---

## 1. 物理真相与问题边界

### 1.1 布局重排与漂移成因

在增强类重定义运行时（DCEVM / JBR）下，新增字段并非简单尾部追加，HotSpot 布局算法会根据字段尺寸排序以填补对齐空洞（Padding Hole）并进行基本类型聚类（Primitive Clustering）：

1. **单类填洞漂移**：若存量字段间存在对齐空洞，新增同等大小字段会填入空洞，导致空洞之后的存量字段物理偏移向后顺移；
2. **单类类型聚类漂移**：存量全引用类型类新增基本类型（如 `long`、`double`）时，基本类型排在引用类型之前，导致存量引用字段整体向后挤压；
3. **父类变更引发子类级联漂移**：基类新增实例字段会导致所有已加载子孙类**自身声明**的实例字段按照对齐步长整体后移；从父类继承来的字段不在 `SuperclassDriftGate` 的 declared-field 扫描范围内，继承字段的偏移绑定属已知盲区。

### 1.2 实例迁移与物理槽位紧缩的双重性

类重定义删除字段时，JVM 行为存在两层异构语义：

* **字节码语义（实例迁移）**：DCEVM 在重定义期间执行实例状态迁移（Instance Migration），按字段名将存量实例的数据复制到新槽位。因此通过未修改的字节码访问未删字段（如 `getfield y`）依然能正确读取原值；
* **物理内存语义（槽位紧缩）**：物理内存布局实际紧凑压缩回收被删字段槽位。原位于旧偏移的内存区域将被后续字段覆盖。若外部底层代码直接通过 `Unsafe.getInt(liveObj, oldOffset)` 访问，将产生严重的内存错位读写（读写到相邻字段）。

因此，只要外部存在对被删除字段物理偏移的持有者，该次重定义必须坚决阻断（判 `DELETED_FIELD_OFFSET_CONFLICT`）。

---

## 2. 访问器自愈能力与受害者边界

通过对各类反射与方法句柄机制进行实测比对，确立以下受害者边界：

```mermaid
flowchart TD
    subgraph S_SAFE["自愈群体（无需拦截，避免误杀）"]
        F1["Field.get / set（accessor 随版本感知自动刷新）"]
        M1["MethodHandle getter/setter（findGetter / unreflectGetter 自动重定向）"]
    end

    subgraph S_VICTIM["漂移受害者（必须由安全门严格阻断）"]
        V1["VarHandle（findVarHandle 固化物理偏移，漂移后错读相邻字段）"]
        U1["Atomic*FieldUpdaterImpl（持有 private final long offset，且被 C2 折叠）"]
        O1["Unsafe static final long OFFSET（绝对内存偏移，且被 C2 折叠）"]
        D1["删除字段（物理槽位紧缩，旧偏移被相邻字段抢占）"]
    end
```

### 2.1 自愈群体（不纳入 Binding）

* **反射 `Field` 访问器**：现代 JDK 中缓存的 `Field` 实例在重定义后，底层 accessor 自动感知版本变动并按新偏移读取，不会读取错位内存；
* **`MethodHandle` getter / setter**：`MethodHandles.Lookup.findGetter`、`findSetter`、`findStaticGetter`、`findStaticSetter`、`unreflectGetter`、`unreflectSetter` 获取的直接方法句柄在重定义发生后，底层 DirectMethodHandle 被 VM 自动失效并重定向至最新物理偏移。
* **边界契约**：`OffsetScanner` 严禁将上述 MethodHandle 产生源抓入 `OffsetBindingIndex`，防止对 Jackson、Record、Kotlin 等依赖反射句柄的现代框架造成误杀。

### 2.2 漂移受害者（必须纳入 Binding）

* **`VarHandle`**：`findVarHandle`、`findStaticVarHandle`、`unreflectVarHandle` 以及 `ConstantBootstraps.fieldVarHandle` 产生的句柄内部固化了物理偏移量，且在重定义后不会自愈，继续读写旧偏移；
* **`Atomic*FieldUpdater`**：内部字段 `offset` 在构造时确定，且极易被 JIT C2 激进常量折叠；
* **`Unsafe.objectFieldOffset`**：直接计算出的物理偏移量。

---

## 3. P0 阶段：结果驱动型安全门与布局预测器（S1）

### 3.1 抛弃式 ClassLoader 布局预测器（`LayoutPredictor`）

在执行实际重定义之前，利用 `EphemeralClassLoader` 进行干净加载（Clean-Load）：

1. **协同注入加载**：
   * 本批次重定义目标类注入新字节码；
   * 目标类的所有已加载子孙类（`descendants`）使用原字节码注入同一 `EphemeralClassLoader`，使其在干净环境中链接到新基类上；
2. **包级私有父类 Runtime Package 隔离防御**：
   * 若子类 `com.foo.Sub` 继承自未改动的包级私有父类 `com.foo.Base`，若将 `Base` 委派给父 ClassLoader，JVM 会因 `(EphemeralClassLoader, com.foo)` 与 `(AppClassLoader, com.foo)` 属于不同运行时包而在 `defineClass` 时抛出 `IllegalAccessError`；
   * **防御规格**：在构建预测类加载器时，必须通过 `fallbackProvider` 将目标类及其全部已加载子孙类的**整条非 JDK 祖先链（superclass hierarchy）**预先提取并注入同一个 `EphemeralClassLoader`，保证子类与祖先类处于同一运行时包内；
3. **未预测类安全降级（`PredictionResult.unpredictedClasses`）**：
   * 运行时生成类（动态代理、Hidden Class、Lambda 等）或跨 Loader 导致字节码不可读/链接报错者，严禁当作“无漂移”放行，必须记入 `unpredictedClasses`。

### 3.2 判定规则序列（`SuperclassDriftGate.checkLayoutDrift`）

```mermaid
flowchart TD
    Start["收到 LayoutPredictor 预测结果"] --> CheckDel{"是否存在被删除字段<br>在索引中有缓存？"}
    CheckDel -- 是 --> RejDel["拒绝：DELETED_FIELD_OFFSET_CONFLICT"]
    CheckDel -- 否 --> CheckUnpred{"是否存在未预测类<br>(unpredictedClasses)？"}

    CheckUnpred -- 是 --> CheckStrict{"是否处于严格模式<br>(OFFSET_STRICT)？"}
    CheckStrict -- 是 --> RejStrict["拒绝：UNPREDICTABLE_LAYOUT_DRIFT (Strict)"]
    CheckStrict -- 否 --> CheckSubHolders{"未预测类是否在索引中<br>存在持有者？"}
    CheckSubHolders -- 是 --> RejSubHolders["拒绝：UNPREDICTABLE_LAYOUT_DRIFT (Holder Hit)"]
    CheckSubHolders -- 否 --> CheckDrift

    CheckUnpred -- 否 --> CheckDrift{"存在偏移漂移字段<br>且命中持有者/存活动态持有者？"}
    CheckDrift -- 是 --> RejDrift["拒绝：LAYOUT_DRIFT / SUPERCLASS_FIELD_DRIFT"]
    CheckDrift -- 否 --> Pass["安全放行 (SAFE)"]
```

---

## 4. P1 阶段：第三方框架实例级缓存驱逐 SPI（S4）

### 4.1 废除无效的 JVM 内部反射清理

* JDK 12+ 已通过 `Reflection.fieldFilterMap` 屏蔽 `Class.reflectionData`，反射访问必抛 `NoSuchFieldException`；
* JVM 在类重定义成功后自动递增内部 `classRedefinedCount`，`Class.reflectionData()` 下次调用时检测到计数器不一致会自动重建。因此无需且禁止对 `Class.reflectionData` 做任何非法反射入侵。

### 4.2 实例级缓存驱逐架构

框架元数据（如字段映射、类型适配器）通常缓存在具体的解析器实例内部，需要进行实例级清理：

```mermaid
flowchart LR
    subgraph Trigger["触发源 (RemoldAgent)"]
        R1["applyRedefinitions 成功"]
        R2["notifyExternalClassRedefined 外部通道"]
    end

    subgraph Manager["ReflectionCacheManager (core)"]
        M1["evictAll(redefinedClasses)"]
        H1["LibTool.getInstances (堆遍历)"]
        H2["WeakHashMap (显式注册实例)"]
    end

    subgraph Evictors["SPI 驱逐器实现"]
        E1["ArcJsonEvictor (plugin-arc 注册)"]
        E2["JacksonEvictor (core 内置)"]
        E3["GsonEvictor (core 内置)"]
    end

    Trigger --> M1
    M1 --> H1 & H2
    H1 & H2 --> E1 & E2 & E3
```

### 4.3 框架适配规格

1. **Arc `arc.util.serialization.Json`**（实现位于 `:plugin-arc` 的 `remold.arc.ArcJsonEvictor`）：
   * 目标字段：`typeToFields`（`ObjectMap<Class, OrderedMap<String, FieldMetadata>>`）、`classToDefaultValues`（`ObjectMap<Class, Object[]>`）、`classToSerializer`（`ObjectMap<Class, Serializer>`）；
   * 动作：调用 `remove(redefinedClass)` 清除变动类的缓存条目，驱逐后下次读写自动重新反射字段元数据；
   * 分层约束：类与字段以 Arc 编译期类型引用（`Json`、`ObjectMap`），只有"私有字段名"仍是字符串契约；字段不可读时在装载期 `warn`，不静默失效。该驱逐器由 `ArcHotswapPlugin.onAgentInit` 经 `RemoldAgent.registerCacheEvictor` 注册，核心引擎不持有任何 Arc 类名或字段名。
2. **Jackson `ObjectMapper`**（`core` 内置）：
   * 全局缓存：调用 `TypeFactory.defaultInstance().clearCache()`；
   * 实例缓存：对每个存活 `ObjectMapper`，分别对其 `_serializerProvider._serializerCache` 与 `_deserializationContext._cache` 执行 `flush()`。
3. **Gson `Gson`**：
   * 实例缓存：对每个存活 `Gson` 实例，清除其 `typeTokenCache`（`ConcurrentMap`）。
4. **双通道获取策略**：
   * **原生堆遍历通道**：若 Native 探针 `LibTool` 可用，通过 `LibTool.getInstances(targetClass)` 自动发现堆中所有存活解析器实例，实现免注册无感清理；
   * **显式注册通道**：使用弱引用集合持有实例，作为无 native 时的确定性通道。各框架的注册入口归属其实现所在层：`RemoldAgent.registerObjectMapper` / `registerGson`（core）与 `ArcJsonEvictor.register`（plugin-arc）。

## 5. 分 JVM 实测读数（批次 K，`tests/src/ParameterAnnotationDriftProbe.java`）

探针入口 `./gradlew :tests:parameterAnnotationDriftProbe`，可加 `-Pkprobe.javaHome=<jdk>` 与 `-Pkprobe.dcevm`
切换运行时；入参是"变更前后两份 class 字节"，与变更类型无关。
读数分四档：**旧句柄**（重定义前取得的 `Method`）、**重新 `getDeclaredMethods()`**、**首次解析**（按签名现取），
另加"重定义期间并发读"与"GC + 常量池争用后重读"。每格都记录 `java.version` 与完整 `inputArguments`，
并用"加一个实例字段再 redefine"的真实结果区分 DCEVM 格与标准 HotSpot 格（不靠参数名或 JVM 名字推断）。

### 5.1 三格环境

| 格 | java.version / vm | 结构重定义能力实测 | 备注 |
|:--|:--|:--|:--|
| ① JBR 25 标准 HotSpot | `25.0.2+1-b329.72`（`OpenJDK 64-Bit Server VM`） | `THROWN:java.lang.UnsupportedOperationException`（`attempted to change the schema (add/remove fields)`） | 无 DCEVM 参数 |
| ② JBR 21 标准 HotSpot | `21.0.9+1-b1163.86`（同上） | 同上 `UnsupportedOperationException` | 无 DCEVM 参数 |
| ③ JBR 21 + DCEVM | 同② | `OK`（字段增加后 redefine 成功） | `inputArguments` 含 `-XX:+AllowEnhancedClassRedefinition` |

### 5.2 三格 × 九场景 × 三组读数

| 场景 | 读数 API | 格 | 旧句柄 | 重新 getDeclaredMethods() | 首次解析 |
|:--|:--|:--|:--|:--|:--|
| control-identical | getParameterAnnotations | ① JBR 25 标准 | `value=v1 type=java.lang.Object` | `value=v1 type=java.lang.Object` | `value=v1 type=java.lang.Object` |
| control-identical | getParameterAnnotations | ② JBR 21 标准 | `value=v1 type=java.lang.Object` | `value=v1 type=java.lang.Object` | `value=v1 type=java.lang.Object` |
| control-identical | getParameterAnnotations | ③ JBR 21+DCEVM | `value=v1 type=java.lang.Object` | `value=v1 type=java.lang.Object` | `value=v1 type=java.lang.Object` |
| param-annotation-value-change | getParameterAnnotations | ① JBR 25 标准 | `value=v1 type=java.lang.Object` | `value=v2-a-much-longer-value type=java.lang.Object` | `value=v2-a-much-longer-value type=java.lang.Object` |
| param-annotation-value-change | getParameterAnnotations | ② JBR 21 标准 | `value=v1 type=java.lang.Object` | `value=v2-a-much-longer-value type=java.lang.Object` | `value=v2-a-much-longer-value type=java.lang.Object` |
| param-annotation-value-change | getParameterAnnotations | ③ JBR 21+DCEVM | `value=v2-a-much-longer-value type=java.lang.Object` | `value=v2-a-much-longer-value type=java.lang.Object` | `value=v2-a-much-longer-value type=java.lang.Object` |
| param-annotation-removed | getParameterAnnotations | ① JBR 25 标准 | `value=v1 type=java.lang.Object` | `[#0={}]` | `[#0={}]` |
| param-annotation-removed | getParameterAnnotations | ② JBR 21 标准 | `value=v1 type=java.lang.Object` | `[#0={}]` | `[#0={}]` |
| param-annotation-removed | getParameterAnnotations | ③ JBR 21+DCEVM | `THROWN java.lang.reflect.GenericSignatureFormatError` | `[#0={}]` | `[#0={}]` |
| generic-signature-change | getGenericParameterTypes | ① JBR 25 标准 | `[java.util.List<java.lang.String>]` | `[java.util.List<java.lang.Integer>]` | `[java.util.List<java.lang.Integer>]` |
| generic-signature-change | getGenericParameterTypes | ② JBR 21 标准 | `[java.util.List<java.lang.String>]` | `[java.util.List<java.lang.Integer>]` | `[java.util.List<java.lang.Integer>]` |
| generic-signature-change | getGenericParameterTypes | ③ JBR 21+DCEVM | `[java.util.List<java.lang.String>]` | `[java.util.List<java.lang.Integer>]` | `[java.util.List<java.lang.Integer>]` |
| annotation-and-signature-change | getParameterAnnotations | ① JBR 25 标准 | `value=v1 type=java.lang.Object` | `value=v2 type=java.lang.Object` | `value=v2 type=java.lang.Object` |
| annotation-and-signature-change | getParameterAnnotations | ② JBR 21 标准 | `value=v1 type=java.lang.Object` | `value=v2 type=java.lang.Object` | `value=v2 type=java.lang.Object` |
| annotation-and-signature-change | getParameterAnnotations | ③ JBR 21+DCEVM | `value=v2 type=java.lang.Object` | `value=v2 type=java.lang.Object` | `value=v2 type=java.lang.Object` |
| param-annotation-moved-between-parameters | getParameterAnnotations | ① JBR 25 标准 | `value=v1 type=java.lang.Object` | `value=v1 type=java.lang.Object` | `value=v1 type=java.lang.Object` |
| param-annotation-moved-between-parameters | getParameterAnnotations | ② JBR 21 标准 | `value=v1 type=java.lang.Object` | `value=v1 type=java.lang.Object` | `value=v1 type=java.lang.Object` |
| param-annotation-moved-between-parameters | getParameterAnnotations | ③ JBR 21+DCEVM | `value=v1 type=java.lang.Object` | `value=v1 type=java.lang.Object` | `value=v1 type=java.lang.Object` |
| param-annotation-type-changed | getParameterAnnotations | ① JBR 25 标准 | `value=v1 type=java.lang.Object` | `[#0={@java.lang.Deprecated(since=,forRemoval=false)}]` | `[#0={@java.lang.Deprecated(since=,forRemoval=false)}]` |
| param-annotation-type-changed | getParameterAnnotations | ② JBR 21 标准 | `value=v1 type=java.lang.Object` | `[#0={@java.lang.Deprecated(since=,forRemoval=false)}]` | `[#0={@java.lang.Deprecated(since=,forRemoval=false)}]` |
| param-annotation-type-changed | getParameterAnnotations | ③ JBR 21+DCEVM | `[#0={@java.lang.Deprecated(since=,forRemoval=false)}]` | `[#0={@java.lang.Deprecated(since=,forRemoval=false)}]` | `[#0={@java.lang.Deprecated(since=,forRemoval=false)}]` |
| class-generic-signature-change | getGenericSuperclass | ① JBR 25 标准 | `java.util.ArrayList<java.lang.String>` | `java.util.ArrayList<java.lang.String>` | `java.util.ArrayList<java.lang.String>` |
| class-generic-signature-change | getGenericSuperclass | ② JBR 21 标准 | `java.util.ArrayList<java.lang.String>` | `java.util.ArrayList<java.lang.String>` | `java.util.ArrayList<java.lang.String>` |
| class-generic-signature-change | getGenericSuperclass | ③ JBR 21+DCEVM | `java.util.ArrayList<java.lang.String>` | `java.util.ArrayList<java.lang.Integer>` | `java.util.ArrayList<java.lang.Integer>` |
| annotation-class-literal-value-change | getParameterAnnotations | ① JBR 25 标准 | `value=v1 type=java.lang.String` | `value=v1 type=java.lang.Integer` | `value=v1 type=java.lang.Integer` |
| annotation-class-literal-value-change | getParameterAnnotations | ② JBR 21 标准 | `value=v1 type=java.lang.String` | `value=v1 type=java.lang.Integer` | `value=v1 type=java.lang.Integer` |
| annotation-class-literal-value-change | getParameterAnnotations | ③ JBR 21+DCEVM | `value=v1 type=java.lang.String` | `value=v1 type=java.lang.Integer` | `value=v1 type=java.lang.Integer` |

### 5.3 读数摘要（逐字取自上表，不写机制标签）

- **格① / 格②（标准 HotSpot）**：`param-annotation-value-change` 的旧句柄读数为 `value=v1`，同一场景
  "重新 `getDeclaredMethods()`" 与"首次解析"读数为 `value=v2-a-much-longer-value`；`generic-signature-change`
  的旧句柄为 `List<java.lang.String>`、其余两组为 `List<java.lang.Integer>`。九场景**均未抛出 `Error` 级失败**
  （`escapedError=none`）。即：同一批读数的三组之间**不一致**，且不一致处**没有异常**。
- **格③（JBR 21 + DCEVM）**：`param-annotation-removed` 的**旧句柄** `getParameterAnnotations()` 抛出
  `java.lang.reflect.GenericSignatureFormatError: Signature Parse error: Expected Field Type Signature`
  （连跑 3 次同场景同阶段同异常，确定性复现）；同一格另有 `param-annotation-value-change`、
  `annotation-and-signature-change` 的旧句柄**直接读到新值**，`class-generic-signature-change` 的
  `getGenericSuperclass()` 也在重定义后从 `ArrayList<java.lang.String>` 变为 `ArrayList<java.lang.Integer>`。
- **跨格不一致**：同一场景的旧句柄读数在①②与③之间不同（①②为旧值、③为新值或抛 `Error`）；
  类级 `getGenericSuperclass()` 只在③刷新。**因此本主题任何"旧句柄行为"的表述都必须分 JVM 写，
  不得跨格归纳。**

### 5.4 灵敏度对照（证明读数链路能看见 `Error`，不构成缺陷证据）

| 对照 | API | 真实 `Throwable` |
|:--|:--|:--|
| `param-annotation-count-mismatch`（属性 `num_parameters` 与实际参数数不符） | `getParameterAnnotations` | `java.lang.annotation.AnnotationFormatError: Unexpected end of parameter annotations.` |
| `malformed-generic-signature`（方法 `Signature` 语法非法） | `getGenericParameterTypes` | `java.lang.reflect.GenericSignatureFormatError: Signature Parse error: expected ')'` |
| `param-annotation-type-is-not-an-annotation` | `getParameterAnnotations` | 无异常（JDK 静默忽略） |
| `duplicate-annotation-member` | `getParameterAnnotations` | 无异常（JDK 静默忽略） |

两份"属性被故意损坏"的字节由 ASM 直接生成，异常全部由 JVM / 反射库解析器抛出；探针内没有任何
`throw` 语句。该对照只验证读数链路，**不能替代复现证据**。

### 5.5 与缓存驱逐的关系（能力边界）

本文读数只覆盖"反射句柄读什么"。`ReflectionCacheManager` 的驱逐只作用于**已注册**的框架缓存
（Gson / Jackson / 插件注册的 `ReflectionCacheEvictor`），它无法改变用户自持句柄的读数：
cherry-pick 后的实现落在树上时，格③ 的 `param-annotation-removed` 旧句柄**仍然**抛
`GenericSignatureFormatError`（同一探针、同一阶段）。本批次**没有**取得"已注册缓存路径：驱逐前读到错误值、
驱逐后读到正确值"这组前后对照读数，因此驱逐范围扩展（`EvictionScope.WITH_DESCENDANTS`）**不作为**
本主题已验证的能力登记；它保持显式缺口。
