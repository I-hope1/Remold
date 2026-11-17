# 05 JVMTI 多态堆实例检索

> **文档类型**：设计规格（只写规格/决策；实现状态以 [`docs/status.md`](../status.md) 为准）

适用：修改 `LibTool.getInstances` 或相关 Native 代码时阅读。实现状态参见 `docs/status.md` 与 `AGENTS.md`。
实现以代码为准，本文只记录必须保持的约束，不复制源码。

## 实现位置

- `LibTool.getInstances`（C++，基于 `IterateOverInstancesOfClass`）

## 必须保持的约束

1. **多态覆盖**：`IterateOverInstancesOfClass` 天然包含目标类、所有派生子类和接口实现，不需要自己展开类层级。
2. **私有 Tag 序列**：用 `std::atomic<jlong>` 动态分配标签（起始值 100000，`fetch_add`），避免与其它 Agent 的标签踩踏。
3. **全局互斥**：整个"打标 → 取对象 → 清标"流程持有 `g_heap_mutex`。
4. **流程**：
   - 遍历目标类实例并打标
   - `GetObjectsWithTags` 取出专属标记的对象
   - 组装 `jobjectArray` 返回
   - 现场清理 Tag，释放局部引用
5. **错误路径必须回滚 Tag**：遍历失败、`GetObjectsWithTags` 失败、`NewObjectArray` 失败，都要把本次分配的 Tag 清掉，只清属于本次的 Tag（回调里比较 `*tag_ptr == 本次 tag`）。
6. **缓冲区释放**：`GetObjectsWithTags` 返回的 `instances` 缓冲区用 RAII（scope guard）调用 `Deallocate` 释放。
7. **局部引用**：循环里对每个 `instances[i]` 调 `DeleteLocalRef`，避免百万级对象撑爆局部引用表。
8. **错误返回**：用 `std::expected<jobjectArray, jvmtiError>`，不要吞掉 `jvmtiError`。

## 已知边界

- 堆遍历期间其他线程可能正在执行旧构造器，会抓到 final 字段尚未赋值的半构造对象。这是已知限制，需在风险报告中标明，调用端应避开高并发初始化峰值热更。
- 遍历耗时受全堆 STW 影响，毫秒到秒级。

## 待验证（P0.5）

- 百万级对象下的耗时与内存开销尚未压测。

## 批次实例收集（一次全堆遍历填 K 个 target）

入口：`LibTool.getInstancesBatch(Class[])`（Java）→ `nGetInstancesBatch`（C++）。
用途：一次结构性热更里 K 个类各调一次 `getInstances` 就是 K 次全堆遍历；批次驱动在
「重定义生效之后、补丁执行之前」只遍历一次，每个宿主在自己的 `applyPatch` 里消费自己的切片
（`InitFix.beginBatchInstanceCollection` / `endBatchInstanceCollection` / `takeBatchInstances`）。

**归类机制（class tag，而不是"给对象猜类"）**

1. `GetLoadedClasses` 枚举已加载类，对每个类用 `IsAssignableFrom(cls, target[i])` 求「命中哪些
   target」的位掩码。**这一层就是多态等价性的唯一来源**：`Sub extends Base` 的 `Sub` 会被打上
   `{Base, Sub}` 两个位，即使 `Sub` 本身不在 targets 里、甚至不在本批字节码里。
   因此 class tag 只认对象的**精确运行时类**，但类 → target 的映射是沿类层级展开过的。
2. 只给命中类打 `class_tag = class_tag_base + 掩码表下标`（掩码表按"不同掩码"去重，tag 数
   = 不同掩码数，与 K 无关；位图只用于"这个类属于哪几个 target"，不用于跨批次记录类归属）。
3. `IterateThroughHeap(JVMTI_HEAP_FILTER_CLASS_UNTAGGED, ...)`：HotSpot 在进回调前就把
   「类没有 tag」的对象过滤掉，`String` / `byte[]` / `HashMap$Node` 不产生任何回调。
4. 回调内**只做纯 native 计算**（读 `class_tag`、写 `*tag_ptr`、累加计数），绝不调用 JNI
   ——JVMTI 规范明禁在堆遍历回调里调 JNI。
5. 只有真正命中的组才占用搜索 tag：全部 target 都零实例时**直接跳过 `GetObjectsWithTags`**，
   也不产生任何对象 tag 清理遍历。搜索 tag 数 = 命中组数，不给未命中的零实例类分配 tag。

**必须保持的约束**

- **临时 tag 全清**：类 tag 逐个还原为 0、对象 tag 逐个清零（超过 `kTagClearWalkThreshold` 时退回
  按区间的过滤遍历）。全进程只有本库使用 JVMTI tag，且所有打标都在 `g_heap_mutex` 内完成并在释放
  前清零，因此「清零」与「恢复旧 tag」等价（旧 tag 恒为 0）。
- **原生锁**：整段"枚举类 → 打类 tag → 遍历 → 取对象 → 清 tag"持有 `g_heap_mutex`；
  禁止改用 JVMTI `RawMonitor`（等待者被 JVM 视为需挂起的线程，会卡 safepoint）。
- **基本类型/数组拦截**：Java 侧 `prepareInstanceQueryClass` 拒绝基本类型镜像与数组类；
  native 侧另有两条「class tag 编码不可用」的判据——target 是 `java.lang.Class` 或其超类型
  （类镜像的 tag 与 class tag 共用同一存储，打对象 tag 会覆盖类 tag）、target 是数组类的超类型
  （数组类不在 `GetLoadedClasses` 结果里，打不上 tag 会静默漏掉数组实例）。命中即整体退回逐类路径。
- **K 阈值**：一次批次遍历 ≈ 9 倍单类遍历（同一进程同一堆实测：64 个零实例类批 1 次 ≈ 84 ms，
  逐类 64 次 ≈ 588 ms），因此 `InitFix.BATCH_HEAP_SCAN_MIN_TARGETS = 8` 以下一律走原逐类路径，
  保证小批次零额外开销；K=1 也在此列。
- **降级**：旧 native 库没有 `nGetInstancesBatch`（`UnsatisfiedLinkError`）或 native 返回失败时，
  `LibTool` 整体退回逐类 `getInstances`，结果相同、遍历次数回到 K 次；无 native 时退回
  `InstanceTracker` 的既有降级路径。
- **`Object[][]` 而不是强类型数组**：按 target 建强类型数组会与数组类/子类实例冲突
  （`ArrayStoreException`），统一用 `Object[]`，由调用方按需转换。

**批次存在性探测（与实例收集分开）**

`LibTool.hasLiveInstancesBatch(Class[])` 只回答"有没有"，不物化对象数组。它跑在**重定义之前**
（布局门），而实例收集跑在**重定义之后**：两者语义不可混用——把重定义前的快照留给 `applyPatch`
会漏掉窗口期新建的实例（`docs/status.md` 风险 6）。实现同样是一次 `IterateThroughHeap`
（`JVMTI_HEAP_FILTER_CLASS_UNTAGGED`），全部 target 都已见到实例时立即 `JVMTI_VISIT_ABORT` 早停。
布局门在批次上下文（`RemoldAgent.setLivenessBatch`）里第一次未命中缓存时对整批做一次遍历并填满
缓存，避免"同一批 50 个零实例类 ⇒ 50 次全堆扫描"。

**取证入口**

- `LibTool.heapWalkCount()`：native 侧真实计数（逐类与批路径都计；tag 清理的过滤遍历不计），
  用于断言"K 次 → 1 次"。
- `LibTool.getInstancesBatch(Class[], long[] timings)`：`enumerate` / `iterate` / `collect` / `fill` /
  `cleanup` / `rawCount` 六段纳秒，供探针判断时间花在哪一段。
- `tests/src/InitFixBatchScanProbe.java`（`./gradlew :tests:initFixBatchScanProbe`）：K=1/4/8/16/64
  的开/关读数与遍历次数；`tests/src/BatchHeapScanEquivalenceTest.java`：批收集 vs 逐类的集合等价门禁。

