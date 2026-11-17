#include <jni.h>
#include <jvmti.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <expected>
#if defined(_WIN32)
#include <chrono>
#include <mutex>
using NativeMutex = std::mutex;
using NativeLockGuard = std::lock_guard<std::mutex>;
static inline jlong monotonicNowNs() {
    return static_cast<jlong>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()
        ).count()
    );
}
#else
#include <pthread.h>
#include <time.h>

static inline jlong monotonicNowNs() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<jlong>(ts.tv_sec) * 1000000000LL + static_cast<jlong>(ts.tv_nsec);
}

/**
 * POSIX 独占锁封装（使用 pthread_mutex_t）。
 * 避免引用 libc++.a 的 std::mutex，从而允许两段式 zig cc 链接：既跳过 zig 构建内置 libc++ 时
 * 的 -Wnullability-completeness 噪声，又让锁符号直接绑定到 libpthread.so.0@GLIBC_2.17。
 */
struct NativeMutex {
    pthread_mutex_t handle = PTHREAD_MUTEX_INITIALIZER;
    NativeMutex() = default;
    NativeMutex(const NativeMutex&) = delete;
    NativeMutex& operator=(const NativeMutex&) = delete;
    void lock() {
        if (pthread_mutex_lock(&handle) != 0) std::abort();
    }
    void unlock() {
        if (pthread_mutex_unlock(&handle) != 0) std::abort();
    }
};

class NativeLockGuard {
    NativeMutex& mutex;
public:
    explicit NativeLockGuard(NativeMutex& m) : mutex(m) {
        mutex.lock();
    }
    ~NativeLockGuard() {
        mutex.unlock();
    }
    NativeLockGuard(const NativeLockGuard&) = delete;
    NativeLockGuard& operator=(const NativeLockGuard&) = delete;
};
#endif
#include <span>
#include <type_traits>
#include <vector>
#include <utility>

/** 万能 Scope Guard 实现，利用析构函数在离开作用域时执行清理逻辑 */
template <typename F>
class ScopeGuard {
    F fn;
    bool active{true};
public:
    // C++20 requires 约束：防止万能引用劫持拷贝与移动构造函数
    template <typename Fn>
        requires (!std::is_same_v<std::remove_cvref_t<Fn>, ScopeGuard>)
    explicit ScopeGuard(Fn&& f) : fn(std::forward<Fn>(f)) {}

    ~ScopeGuard() { if (active) fn(); }
    void dismiss() noexcept { active = false; }
    ScopeGuard(const ScopeGuard&) = delete;
    ScopeGuard& operator=(const ScopeGuard&) = delete;
    ScopeGuard(ScopeGuard&& other) noexcept : fn(std::move(other.fn)), active(other.active) {
        other.dismiss();
    }
};

template <typename F>
[[nodiscard]] auto make_scope_guard(F&& f) {
    return ScopeGuard<std::decay_t<F>>(std::forward<F>(f));
}

#define CONCAT_IMPL(a, b) a##b
#define CONCAT(a, b) CONCAT_IMPL(a, b)
#define SCOPE_EXIT auto CONCAT(_scope_guard_, __LINE__) = make_scope_guard

/**
 * 保护整串堆操作序列（Iterate → GetObjectsWithTags → SetTag×N）的全局互斥锁。
 * 不使用 JVMTI Raw Monitor：Raw Monitor 的等待者被 JVM 视为需要挂起的线程，
 * 会卡住 safepoint 造成死锁；std::mutex 持有者处于 native 状态，safepoint 可正常推进。
 */
static NativeMutex g_heap_mutex;

/** 全局递增 Tag 序列号，用于生成唯一的动态标签 */
static std::atomic<jlong> g_tag_sequence{100000};

struct TagPair {
    jlong first;
    jlong second;
};

/** 原子分配成对的唯一 JVMTI 标签 */
static inline TagPair allocateTagPair() {
    jlong base = g_tag_sequence.fetch_add(2, std::memory_order_relaxed);
    return {.first = base, .second = base + 1};
}

/** 原子分配连续 N 个唯一 JVMTI 标签。 */
static inline jlong allocateTagRange(jint count) {
    return g_tag_sequence.fetch_add(static_cast<jlong>(count), std::memory_order_relaxed);
}

struct TagRange { jlong begin; jlong end; };

/** 单次批次查询覆盖的 target 上限；超过由 Java 侧分块，每块仍只做一次全堆遍历。 */
static constexpr jint kMaxBatchTargets = 64;

// 对象 tag 的槽位预算：槽位按「不同 target 掩码组合」计，组合数**可以超过** target 数
// （一个类同时命中多个 target 时是并集掩码），所以不能拿 kMaxBatchTargets 当标签容量
// —— 否则超出的槽位会写到预留 tag 范围之外，与类 tag 撞车，表现为「某个 target 里混进别的类的实例」
// （静默错配，不抛异常）。这里独立给容量，超出即整体退回逐类路径。
static constexpr jint kMaxBatchSlots = 64;

/**
 * 实例收集类全堆遍历的真实计数（逐类路径与批路径都计），供探针断言「K 次 → 1 次」。
 * 只统计「为收集/判定实例而做的遍历」：tag 清理用的 JVMTI_HEAP_FILTER_UNTAGGED 过滤遍历不计。
 */
static std::atomic<jlong> g_heap_walk_count{0};

static inline void noteHeapWalk() {
    g_heap_walk_count.fetch_add(1, std::memory_order_relaxed);
}


static jvmtiIterationControl JNICALL ClearClassTagRangeCallback(
    jlong, jlong, jlong* tag_ptr, void* user_data) {
    if (tag_ptr) {
        const auto* r = static_cast<const TagRange*>(user_data);
        const jlong t = *tag_ptr;
        if (t >= r->begin && t < r->end) *tag_ptr = 0;
    }
    return JVMTI_ITERATION_CONTINUE;
}

static jint JNICALL ClearHeapTagRangeCallback(
    jlong, jlong, jlong* tag_ptr, jint, void* user_data) {
    if (tag_ptr) {
        const auto* r = static_cast<const TagRange*>(user_data);
        const jlong t = *tag_ptr;
        if (t >= r->begin && t < r->end) *tag_ptr = 0;
    }
    return JVMTI_VISIT_OBJECTS;
}

static void clearTagRangeFromHeap(jvmtiEnv* jvmti, jlong begin, jlong count) {
    if (count <= 0) return;
    TagRange r{begin, begin + count};
    jvmtiHeapCallbacks cbs{ .heap_iteration_callback = ClearHeapTagRangeCallback };
    // JVMTI_HEAP_FILTER_UNTAGGED filters out untagged objects so only tagged objects visit.
    jvmti->IterateThroughHeap(JVMTI_HEAP_FILTER_UNTAGGED, nullptr, &cbs, &r);
}

static void clearTagRange(jvmtiEnv* jvmti, jclass klass, jlong begin, jlong count) {
    if (count <= 0) return;
    TagRange r{begin, begin + count};
    if (klass && jvmti->IterateOverInstancesOfClass(klass, JVMTI_HEAP_OBJECT_TAGGED,
            ClearClassTagRangeCallback, &r) == JVMTI_ERROR_NONE) {
        return;
    }
    clearTagRangeFromHeap(jvmti, begin, count);
}

static void clearTagFromHeap(jvmtiEnv* jvmti, jlong tag) {
    clearTagRangeFromHeap(jvmti, tag, 1);
}

struct BucketSeqContext { jlong base; jlong seq; jint* max_bucket; jint buckets; jint shift; };

static jvmtiIterationControl JNICALL BucketSeqCallback(
    jlong, jlong, jlong* tag_ptr, void* user_data) {
    if (tag_ptr) {
        auto* ctx = static_cast<BucketSeqContext*>(user_data);
        const jlong raw = ctx->seq >> ctx->shift;
        const jint b = raw >= ctx->buckets ? (ctx->buckets - 1) : static_cast<jint>(raw);
        if (b > *ctx->max_bucket) *ctx->max_bucket = b;
        *tag_ptr = ctx->base + b;
        ctx->seq++;
    }
    return JVMTI_ITERATION_CONTINUE;
}

/** 确保当前 JVMTI 环境已启用对象标记能力 */
static inline jvmtiError ensureCapabilities(jvmtiEnv* jvmti) {
    jvmtiCapabilities caps{
        .can_tag_objects = 1,
    };
    return jvmti->AddCapabilities(&caps);
}

static jvmtiIterationControl JNICALL
HasInstanceCallback(jlong /*class_tag*/, jlong /*size*/, jlong* /*tag_ptr*/, void* user_data) {
    if (user_data) {
        *static_cast<bool*>(user_data) = true;
    }
    return JVMTI_ITERATION_ABORT;
}

static std::atomic<jclass> g_object_class{nullptr};
static NativeMutex g_jvmti_mutex;

/** 获取缓存的 java/lang/Object 全局引用（懒初始化并在 JNI_OnUnload 释放）。 */
static jclass getObjectClass(JNIEnv* env) {
    jclass cached = g_object_class.load(std::memory_order_acquire);
    if (cached) return cached;

    NativeLockGuard lock(g_jvmti_mutex);
    cached = g_object_class.load(std::memory_order_relaxed);
    if (cached) return cached;

    jclass local = env->FindClass("java/lang/Object");
    if (!local) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jclass global = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!global) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    g_object_class.store(global, std::memory_order_release);
    return global;
}

static jvmtiError
validateInstanceQueryClass(jvmtiEnv* jvmti, JNIEnv* env, jclass klass) {
    if (!jvmti || !env || !klass) return JVMTI_ERROR_NULL_POINTER;

    // 基本类型镜像（int.class / void.class …）在堆中没有物理实例，且 NewObjectArray 要求
    // elementClass 必须是引用类型 Class（HotSpot 内部 as_Klass 返回 nullptr 会直接 SIGSEGV）。
    // 这里必须前置拦截，绝不能把基本类型镜像交给 NewObjectArray。
    // 判定方式：基本类型镜像（含 void）都不是 java.lang.Object 的子类型。
    jclass obj_clazz = getObjectClass(env);
    if (!obj_clazz) {
        return JVMTI_ERROR_CLASS_NOT_PREPARED;
    }
    if (!env->IsAssignableFrom(klass, obj_clazz)) {
        return JVMTI_ERROR_ILLEGAL_ARGUMENT;
    }

    // 数组类型（int[].class / int[][].class …）：IterateOverInstancesOfClass 会对该数组类
    // 及其高维衍生类一并打 tag，与按 klass 创建的强类型数组不兼容，触发 ArrayStoreException。
    // 用 JVMTI 的 IsArrayClass 直接判定，比接口推导更精确（无能力/阶段要求）。
    jboolean is_array_class = JNI_FALSE;
    if (auto e = jvmti->IsArrayClass(klass, &is_array_class); e != JVMTI_ERROR_NONE) {
        return e;
    }
    if (is_array_class) {
        return JVMTI_ERROR_ILLEGAL_ARGUMENT;
    }
    return JVMTI_ERROR_NONE;
}

/**
 * 命中首个未回收实例即通过 JVMTI_ITERATION_ABORT 提前终止遍历，不做打 tag 与对象数组构建。
 */
static std::expected<jboolean, jvmtiError>
hasLiveInstanceInternal(jvmtiEnv* jvmti, JNIEnv* env, jclass klass) {
    if (auto err = validateInstanceQueryClass(jvmti, env, klass); err != JVMTI_ERROR_NONE) {
        return std::unexpected(err);
    }
    bool found = false;
    noteHeapWalk();
    if (auto e = jvmti->IterateOverInstancesOfClass(klass, JVMTI_HEAP_OBJECT_EITHER, HasInstanceCallback, &found);
        e != JVMTI_ERROR_NONE) {
        return std::unexpected(e);
    }
    return found ? JNI_TRUE : JNI_FALSE;
}

struct InstanceQueryTimings {
    jlong validate_ns{0};
    jlong iterate_ns{0};
    jlong collect_ns{0};
    jlong alloc_ns{0};
    jlong fill_ns{0};
    jlong cleanup_ns{0};
    jlong raw_count{0};
    jlong kept_count{0};
};

/** 全局缓存的 JavaVM 与常驻 JVMTI 环境（复用到 JNI_OnUnload，保留预热好的 JvmtiTagMap）。 */
static std::atomic<JavaVM*> g_jvm{nullptr};
static std::atomic<jvmtiEnv*> g_jvmti{nullptr};

static std::expected<jvmtiEnv*, jvmtiError> getOrAcquireJvmti(JNIEnv* env) {
    jvmtiEnv* ti = g_jvmti.load(std::memory_order_acquire);
    if (ti) return ti;

    NativeLockGuard lock(g_jvmti_mutex);
    ti = g_jvmti.load(std::memory_order_relaxed);
    if (ti) return ti;

    JavaVM* vm = g_jvm.load(std::memory_order_acquire);
    if (!vm) {
        JavaVM* raw_vm = nullptr;
        if (env->GetJavaVM(&raw_vm) == JNI_OK && raw_vm) {
            g_jvm.store(raw_vm, std::memory_order_release);
            vm = raw_vm;
        }
    }
    if (!vm) {
        return std::unexpected(JVMTI_ERROR_INTERNAL);
    }
    void* jvmti_raw = nullptr;
    if (vm->GetEnv(&jvmti_raw, JVMTI_VERSION_1_2) != JNI_OK || !jvmti_raw) {
        return std::unexpected(JVMTI_ERROR_NOT_AVAILABLE);
    }
    ti = reinterpret_cast<jvmtiEnv*>(jvmti_raw);
    if (auto e = ensureCapabilities(ti); e != JVMTI_ERROR_NONE) {
        // 能力申请失败时立即释放新建的 jvmtiEnv，避免后续重试反复泄漏环境。
        (void) ti->DisposeEnvironment();
        return std::unexpected(e);
    }
    g_jvmti.store(ti, std::memory_order_release);
    return ti;
}

/**
 * 复用已预热标签表的常驻 jvmtiEnv，按实例规模分流清空本次打上的 tag。
 *
 * 选择 32768（2^15）作为切换阈值的权衡依据：
 * - 实例数 <= 32768（日常热更常见类规模）时，直接沿用既有实例句柄逐个 SetTag(0)：
 *   单次 SetTag 约 O(1) 锁内哈希删除，几万次的总开销低于一次完整 VM_HeapWalkOperation
 *   Safepoint STW（几百毫秒级），因此省掉整轮清标 STW。
 * - 实例数 > 32768（十万到百万级大堆）时，百万级逐个 SetTag(0) 会反复获取/释放
 *   JvmtiTagMap_lock 并做海量 JNI/JVMTI 跨界调用；改用单次 JVMTI_HEAP_OBJECT_TAGGED
 *   堆遍历由 VM 线程批量把 *tag_ptr 置零更划算（JBR 25 2M 稳态实测 cleanup ≈ 259–297 ms）。
 * - 不使用 DisposeEnvironment() 替代 > 32768 清标：本地 JBR 25 2M 5 轮对照实测显示，
 *   DisposeEnvironment() 同步销毁 2M 个 OopHandle 并释放哈希表使 cleanup 涨到 600–638 ms，
 *   且下轮不得不重新把 JvmtiTagMapTable 从冷态扩/重哈希到 4M 槽，拖慢 iterate 到 683–713 ms
 *   （稳态比复用常驻 g_jvmti + clearTagRange 净慢 ~500 ms）。
 */
static constexpr jint kTagClearWalkThreshold = 32768;

// 实例枚举的计数排序分桶：窗口固定 2^kEnumBucketShift 个实例。
// 4M 等窗口实验定位到拐点在 2^19→2^20 个对象之间（稳态 inst 1095→5942 ms，测试对象约 32–48 B）；
// 取 2^18 留 2 倍余量（2^19 贴着拐点，2^20 已明显回退）。该拐点只在上述对象大小上实测，
// 对象更大时字节量会移动拐点，但 128 B 胖对象对照显示逐元素成本主要随对象跨 cache line 数上升，
// 与本窗口粒度关系不大。桶数 = ceil(N / window)，上限 kEnumBucketCap；超过上限时尾部实例
// 落最后一桶，该桶内不再有序——即回到旧行为（不是“降级”），避免 tag 数膨胀。
// 该规划被抽出为纯函数，便于单元测试覆盖大 N 的上限路径。
static constexpr jint kEnumBucketShift = 18;
static constexpr jint kEnumBucketCap = 64;
static constexpr jlong kEnumBucketWindow = 1LL << kEnumBucketShift;

static inline jint enumBucketCount(jlong count) {
    if (count <= 0) return 1;
    const jlong buckets = ((count - 1) >> kEnumBucketShift) + 1;
    return buckets > kEnumBucketCap ? kEnumBucketCap : static_cast<jint>(buckets);
}

// ============================================================================================
// 批次级单次全堆遍历（docs/initfix/05-jvmti-heap.md §「批次实例收集」）
//
// 同一批 K 个类各自调一次 getInstances 就是 K 次全堆遍历；这里把「按对象精确运行时类归类」
// 交给 JVMTI 的 class tag 机制，让一次遍历同时填满 K 个 target 的结果桶：
//   1. GetLoadedClasses 枚举已加载类，对每个类用 IsAssignableFrom 求出「它命中哪些 target」的掩码
//      （掩码含全部已加载子类/实现类，多态等价性由这一层保证，不依赖遍历顺序）；
//   2. 只给命中类打 class_tag（tag = class_tag_base + 掩码表下标）；
//   3. IterateThroughHeap(JVMTI_HEAP_FILTER_CLASS_UNTAGGED)：HotSpot 在进回调前就把
//      「类没有 tag」的对象过滤掉，回调只对命中对象触发，2M 级 String/byte[]/HashMap$Node 不产生回调；
//   4. 回调只做纯 native 计算（读 class_tag、写 *tag_ptr、累加计数），绝不调用任何 JNI
//      （JVMTI 规范明禁在堆遍历回调里调 JNI）；
//   5. 只有真正命中的组才占用搜索 tag：全部 target 都零实例时直接跳过 GetObjectsWithTags，
//      也不产生任何对象 tag 清理遍历。
//
// tag 空间：类 tag 与对象 tag 各用一段独立区间（都来自 g_tag_sequence 单调区间），退出时
// 类 tag 逐个还原为 0、对象 tag 逐个清零（超过 kTagClearWalkThreshold 时退回按区间的过滤遍历）。
// 全进程只有本库使用 JVMTI tag，且所有打标都在 g_heap_mutex 内完成并在释放前清零，
// 因此「清零」与「恢复旧 tag」等价（旧 tag 恒为 0）。
// ============================================================================================

struct BatchScanTimings {
    jlong enumerate_ns{0};
    jlong iterate_ns{0};
    jlong collect_ns{0};
    jlong fill_ns{0};
    jlong cleanup_ns{0};
    jlong raw_count{0};
};

/** 一次批次遍历的结果：对象句柄数组 + 与之一一对应的搜索 tag + 搜索 tag 表。 */
struct BatchScanOutcome {
    jobject* objects{nullptr};
    jlong*   tags{nullptr};
    jint     count{0};
    jlong    object_tag_base{0};
    /** 搜索 tag 槽位 → target 掩码（位 i = targets[i]）。 */
    std::vector<jlong> active_masks;
    /** 只做存在性判定时的「已见到」掩码。 */
    jlong    seen_mask{0};
};

/** 遍历上下文：回调里只允许纯 native 计算。 */
struct BatchWalkContext {
    jlong class_tag_base{0};
    jlong object_tag_base{0};
    jint  slot_count{0};
    const jlong* masks{nullptr};
    std::vector<jint>  group_slot;   // 掩码下标 → 活跃搜索 tag 槽位（-1 表示尚未命中）
    std::vector<jlong> active_masks;
    std::vector<jlong> active_hits;
    bool  presence_only{false};
    jint  slot_capacity{0};   // 掩码槽位预算（见 kMaxBatchSlots）
    bool  overflow{false};    // 槽位预算用尽：调用方必须退回逐类路径
    jlong wanted_mask{0};
    jlong seen_mask{0};
    jlong total_hits{0};
};

static jint JNICALL BatchHeapIterationCallback(
    jlong class_tag, jlong /*size*/, jlong* tag_ptr, jint /*length*/, void* user_data) {
    auto* ctx = static_cast<BatchWalkContext*>(user_data);
    const jlong rel = class_tag - ctx->class_tag_base;
    if (rel < 0 || rel >= ctx->slot_count) return JVMTI_VISIT_OBJECTS;
    const jlong mask = ctx->masks[rel];
    if (ctx->presence_only) {
        ctx->seen_mask |= mask;
        return ((ctx->seen_mask & ctx->wanted_mask) == ctx->wanted_mask)
            ? JVMTI_VISIT_ABORT : JVMTI_VISIT_OBJECTS;
    }
    jint slot = ctx->group_slot[static_cast<size_t>(rel)];
    if (slot < 0) {
        // 只有真正命中的组才消耗搜索 tag：零实例的 target 不占 tag、不进 GetObjectsWithTags。
        slot = static_cast<jint>(ctx->active_masks.size());
        if (slot >= ctx->slot_capacity) {
            // 槽位预算用尽：立即中止本次遍历并标记 overflow，由调用方退回逐类路径。
            ctx->overflow = true;
            return JVMTI_VISIT_ABORT;
        }
        ctx->group_slot[static_cast<size_t>(rel)] = slot;
        ctx->active_masks.push_back(mask);
        ctx->active_hits.push_back(0);
    }
    if (tag_ptr) *tag_ptr = ctx->object_tag_base + slot;
    ctx->active_hits[static_cast<size_t>(slot)]++;
    ctx->total_hits++;
    return JVMTI_VISIT_OBJECTS;
}

/**
 * 一次全堆遍历完成 K 个 target 的实例收集（{@code presence_only} 时只判定「有没有」）。
 *
 * <p>多态语义与 {@code getInstancesInternal} 一致：包含子类/接口实现实例、包含不可达但尚未被
 * GC 物理回收的对象。K=1 由 Java 侧直接走单类路径，这里只处理 K&gt;=2。</p>
 *
 * <p>不支持的形态（target 是 {@code java.lang.Class} 或数组类的超类型）返回
 * {@code JVMTI_ERROR_NOT_AVAILABLE}，由调用方退回逐类路径——类镜像的 tag 与 class tag 共用
 * 同一存储，给它打对象 tag 会覆盖我们自己写下的类 tag；数组类不在 {@code GetLoadedClasses}
 * 的结果里，打不上类 tag 会静默漏掉数组实例。</p>
 */
static jvmtiError batchScanInternal(jvmtiEnv* jvmti, JNIEnv* env, jclass* targets, jint k,
                                    bool presence_only, BatchScanOutcome& out,
                                    BatchScanTimings* timings) {
    if (!jvmti || !env || !targets) return JVMTI_ERROR_NULL_POINTER;
    if (k <= 0 || k > kMaxBatchTargets) return JVMTI_ERROR_ILLEGAL_ARGUMENT;

    const jlong t_start = timings ? monotonicNowNs() : 0;

    jclass obj_clazz = getObjectClass(env);
    if (!obj_clazz) return JVMTI_ERROR_CLASS_NOT_PREPARED;

    // 前置拦截：基本类型镜像/数组类（与 validateInstanceQueryClass 同一取向），以及两种
    // 「class tag 编码不可用」的形态。任何一种命中都整体退回逐类路径（fail-closed）。
    jclass class_clazz = env->FindClass("java/lang/Class");
    jclass obj_array_clazz = env->FindClass("[Ljava/lang/Object;");
    if (!class_clazz || !obj_array_clazz) {
        if (class_clazz) env->DeleteLocalRef(class_clazz);
        if (obj_array_clazz) env->DeleteLocalRef(obj_array_clazz);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JVMTI_ERROR_NOT_AVAILABLE;
    }
    jvmtiError verdict = JVMTI_ERROR_NONE;
    for (jint i = 0; i < k && verdict == JVMTI_ERROR_NONE; ++i) {
        jclass t = targets[i];
        if (!t || !env->IsAssignableFrom(t, obj_clazz)) {
            verdict = JVMTI_ERROR_ILLEGAL_ARGUMENT;
        } else if (env->IsAssignableFrom(class_clazz, t) || env->IsAssignableFrom(obj_array_clazz, t)) {
            verdict = JVMTI_ERROR_NOT_AVAILABLE;
        }
    }
    env->DeleteLocalRef(class_clazz);
    env->DeleteLocalRef(obj_array_clazz);
    if (verdict != JVMTI_ERROR_NONE) return verdict;

    NativeLockGuard lock(g_heap_mutex);

    // ---- 1) 已加载类 → target 掩码（含全部已加载子类/实现类） ----
    jint class_count = 0;
    jclass* loaded = nullptr;
    if (auto e = jvmti->GetLoadedClasses(&class_count, &loaded); e != JVMTI_ERROR_NONE) {
        return e;
    }
    SCOPE_EXIT([&] {
        if (loaded) {
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(loaded));
            loaded = nullptr;
        }
    });

    // 已加载类数组里每个 jclass 都是 JNI **局部引用**（大进程动辄上万级）。这里逐轮释放：
    // 命中的类转成全局引用留下（数量级远小于已加载类总数），未命中的当场 DeleteLocalRef。
    // 整段再套一层局部帧兜住临时引用，避免频繁热更把局部引用表顶爆。
    const bool has_enum_frame = (env->PushLocalFrame(32) == JNI_OK);
    if (!has_enum_frame && env->ExceptionCheck()) env->ExceptionClear();
    SCOPE_EXIT([&] {
        if (has_enum_frame) env->PopLocalFrame(nullptr);
    });

    std::vector<jlong> masks;
    std::vector<jclass> tagged_classes;   // 全局引用；清除 tag 后逐个 DeleteGlobalRef
    masks.reserve(16);
    tagged_classes.reserve(16);
    const jlong class_tag_base = allocateTagRange(class_count + 1);
    for (jint c = 0; c < class_count; ++c) {
        jclass cls = loaded[c];
        if (!cls) continue;
        jlong mask = 0;
        for (jint i = 0; i < k; ++i) {
            if (targets[i] && env->IsAssignableFrom(cls, targets[i])) mask |= (jlong{1} << i);
        }
        if (mask == 0) {
            env->DeleteLocalRef(cls);
            loaded[c] = nullptr;
            continue;
        }
        jint slot = -1;
        for (size_t m = 0; m < masks.size(); ++m) {
            if (masks[m] == mask) { slot = static_cast<jint>(m); break; }
        }
        if (slot < 0) {
            slot = static_cast<jint>(masks.size());
            masks.push_back(mask);
        }
        jclass global_cls = reinterpret_cast<jclass>(env->NewGlobalRef(cls));
        env->DeleteLocalRef(cls);
        loaded[c] = nullptr;
        if (!global_cls) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            for (jclass tagged : tagged_classes) {
                jvmti->SetTag(tagged, 0);
                env->DeleteGlobalRef(tagged);
            }
            return JVMTI_ERROR_OUT_OF_MEMORY;
        }
        if (auto e = jvmti->SetTag(global_cls, class_tag_base + slot); e != JVMTI_ERROR_NONE) {
            env->DeleteGlobalRef(global_cls);
            for (jclass tagged : tagged_classes) {
                jvmti->SetTag(tagged, 0);
                env->DeleteGlobalRef(tagged);
            }
            return e;
        }
        tagged_classes.push_back(global_cls);
    }
    const jlong t_after_enumerate = timings ? monotonicNowNs() : 0;
    if (timings) timings->enumerate_ns = t_after_enumerate - t_start;

    // 类 tag 必须在任何退出路径上清零（否则脏 tag 会污染 JvmtiTagMap，让后续过滤遍历命中错对象）。
    SCOPE_EXIT([&] {
        for (jclass tagged : tagged_classes) {
            if (tagged) jvmti->SetTag(tagged, 0);
        }
    });
    SCOPE_EXIT([&] {
        for (jclass tagged : tagged_classes) {
            if (tagged) env->DeleteGlobalRef(tagged);
        }
        tagged_classes.clear();
    });

    if (masks.empty()) {
        // 一个已加载类都不命中（例如目标类还没加载）：无需遍历。
        if (timings) timings->cleanup_ns = monotonicNowNs() - t_after_enumerate;
        return JVMTI_ERROR_NONE;
    }

    // ---- 2) 一次全堆遍历：只对「类有 tag」的对象触发回调 ----
    const jlong object_tag_base = allocateTagRange(kMaxBatchSlots);
    BatchWalkContext ctx;
    ctx.class_tag_base = class_tag_base;
    ctx.object_tag_base = object_tag_base;
    ctx.slot_count = static_cast<jint>(masks.size());
    ctx.masks = masks.data();
    ctx.group_slot.assign(masks.size(), -1);
    ctx.active_masks.reserve(masks.size());
    ctx.active_hits.reserve(masks.size());
    ctx.presence_only = presence_only;
    ctx.slot_capacity = kMaxBatchSlots;
    ctx.overflow = false;
    ctx.wanted_mask = (k >= 64) ? ~jlong{0} : ((jlong{1} << k) - 1);

    jvmtiHeapCallbacks callbacks{ .heap_iteration_callback = BatchHeapIterationCallback };
    noteHeapWalk();
    const jlong t_before_iterate = timings ? monotonicNowNs() : 0;
    if (auto e = jvmti->IterateThroughHeap(JVMTI_HEAP_FILTER_CLASS_UNTAGGED, nullptr, &callbacks, &ctx);
        e != JVMTI_ERROR_NONE) {
        return e;
    }
    const jlong t_after_iterate = timings ? monotonicNowNs() : 0;
    if (timings) timings->iterate_ns = t_after_iterate - t_before_iterate;

    if (ctx.overflow) {
        // 不同掩码组合超出槽位预算：不冒"越界写 tag / 静默错配"的风险，交给调用方退回逐类路径。
        if (timings) timings->cleanup_ns = monotonicNowNs() - t_after_iterate;
        return JVMTI_ERROR_NOT_AVAILABLE;
    }

    if (presence_only) {
        out.seen_mask = ctx.seen_mask;
        if (timings) timings->cleanup_ns = monotonicNowNs() - t_after_iterate;
        return JVMTI_ERROR_NONE;
    }

    if (ctx.total_hits == 0) {
        // 全部 target 都零实例：0 次 GetObjectsWithTags、0 次清理遍历。
        if (timings) timings->cleanup_ns = monotonicNowNs() - t_after_iterate;
        return JVMTI_ERROR_NONE;
    }

    // ---- 3) 只按「真正命中的组」取回对象句柄 ----
    const jint slot_count = static_cast<jint>(ctx.active_masks.size());
    jlong query_tags[kMaxBatchTargets];
    for (jint s = 0; s < slot_count; ++s) query_tags[s] = object_tag_base + s;

    jint collected = 0;
    jobject* instances = nullptr;
    jlong* out_tags = nullptr;
    if (auto e = jvmti->GetObjectsWithTags(slot_count, query_tags, &collected, &instances, &out_tags);
        e != JVMTI_ERROR_NONE) {
        if (instances) jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances));
        if (out_tags) jvmti->Deallocate(reinterpret_cast<unsigned char*>(out_tags));
        return e;
    }
    SCOPE_EXIT([&] {
        if (instances) { jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances)); instances = nullptr; }
        if (out_tags) { jvmti->Deallocate(reinterpret_cast<unsigned char*>(out_tags)); out_tags = nullptr; }
    });
    const jlong t_after_collect = timings ? monotonicNowNs() : 0;
    if (timings) {
        timings->collect_ns = t_after_collect - t_after_iterate;
        timings->raw_count = static_cast<jlong>(collected);
    }

    // 对象 tag 清零：小规模逐个 SetTag（复用既有阈值），大规模退回按区间的过滤遍历。
    const bool direct_clear = (collected <= kTagClearWalkThreshold);
    if (direct_clear) {
        for (jint i = 0; i < collected; ++i) {
            if (instances && instances[i]) jvmti->SetTag(instances[i], 0);
        }
    } else {
        clearTagRangeFromHeap(jvmti, object_tag_base, slot_count);
    }
    if (timings) timings->cleanup_ns = monotonicNowNs() - t_after_collect;

    out.objects = instances;
    out.tags = out_tags;
    out.count = collected;
    out.object_tag_base = object_tag_base;
    out.active_masks = ctx.active_masks;
    instances = nullptr;  // 所有权移交调用方
    out_tags = nullptr;
    return JVMTI_ERROR_NONE;
}


/**
 * 获取指定类及其子类在堆中所有尚未被回收的实例并返回局部引用数组。
 * 包含多态子类与接口实现，包含不可达但尚未被 GC 物理回收的对象。
 */
static std::expected<jobjectArray, jvmtiError>
getInstancesInternal(jvmtiEnv* jvmti, JNIEnv* env, jclass klass, InstanceQueryTimings* timings = nullptr) {
    const jlong t_start = timings ? monotonicNowNs() : 0;
    if (auto err = validateInstanceQueryClass(jvmti, env, klass); err != JVMTI_ERROR_NONE) {
        return std::unexpected(err);
    }
    const jlong t_after_validate = timings ? monotonicNowNs() : 0;
    if (timings) timings->validate_ns = t_after_validate - t_start;

    NativeLockGuard lock(g_heap_mutex);

    // 单趟计数排序：遍历时按“序号 >> kEnumBucketShift”把实例分到最多 kEnumBucketCap 个桶，
    // tag 值即桶号；一次 GetObjectsWithTags 取回句柄与桶号后，从实得 out_tags 统计每桶前缀计数
    // 并直接落位到最终数组（避免两次遍历间 GC 回收导致空洞或漏放）。
    const jlong base = allocateTagRange(kEnumBucketCap);

    jint max_bucket = 0;
    jint collected = 0;
    jobject* instances = nullptr;
    jlong* out_tags = nullptr;
    jlong query_tags[kEnumBucketCap];
    bool has_local_frame = false;
    jint used_buckets = 1;

    auto releaseLocalRefsIfNeeded = [&] {
        if (!has_local_frame && instances) {
            for (jint i = 0; i < collected; ++i) {
                if (instances[i]) env->DeleteLocalRef(instances[i]);
            }
        }
        if (has_local_frame) {
            env->PopLocalFrame(nullptr);
            has_local_frame = false;
        }
    };

    SCOPE_EXIT([&] {
        if (out_tags) { jvmti->Deallocate(reinterpret_cast<unsigned char*>(out_tags)); out_tags = nullptr; }
        if (instances) { jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances)); instances = nullptr; }
        if (has_local_frame) { env->PopLocalFrame(nullptr); has_local_frame = false; }
    });

    BucketSeqContext bucket_ctx{base, 0, &max_bucket, kEnumBucketCap, kEnumBucketShift};

    const jlong t_before_iterate = timings ? monotonicNowNs() : 0;
    noteHeapWalk();
    if (auto e = jvmti->IterateOverInstancesOfClass(klass, JVMTI_HEAP_OBJECT_EITHER, BucketSeqCallback, &bucket_ctx);
        e != JVMTI_ERROR_NONE) {
        clearTagRange(jvmti, klass, base, kEnumBucketCap);
        return std::unexpected(e);
    }
    const jlong t_after_iterate = timings ? monotonicNowNs() : 0;
    if (timings) timings->iterate_ns = t_after_iterate - t_before_iterate;

    if (bucket_ctx.seq == 0) {
        jobjectArray empty = env->NewObjectArray(0, klass, nullptr);
        if (!empty) { if (env->ExceptionCheck()) env->ExceptionClear(); return std::unexpected(JVMTI_ERROR_OUT_OF_MEMORY); }
        if (timings) timings->alloc_ns = monotonicNowNs() - t_after_iterate;
        return empty;
    }
    used_buckets = max_bucket + 1;
    for (jint b = 0; b < used_buckets; ++b) query_tags[b] = base + b;

    if (env->PushLocalFrame(16) == JNI_OK) has_local_frame = true;
    else if (env->ExceptionCheck()) env->ExceptionClear();

    if (auto e = jvmti->GetObjectsWithTags(
            used_buckets, query_tags, &collected, &instances, used_buckets > 1 ? &out_tags : nullptr);
        e != JVMTI_ERROR_NONE || (used_buckets > 1 && collected > 0 && out_tags == nullptr)) {
        releaseLocalRefsIfNeeded();
        clearTagRange(jvmti, klass, base, used_buckets);
        return std::unexpected(e != JVMTI_ERROR_NONE ? e : JVMTI_ERROR_INTERNAL);
    }
    const jlong t_after_collect = timings ? monotonicNowNs() : 0;
    if (timings) {
        timings->collect_ns = t_after_collect - t_after_iterate;
        timings->raw_count = static_cast<jlong>(collected);
    }

    if (collected == 0) {
        jobjectArray empty = env->NewObjectArray(0, klass, nullptr);
        if (!empty) { if (env->ExceptionCheck()) env->ExceptionClear(); return std::unexpected(JVMTI_ERROR_OUT_OF_MEMORY); }
        if (timings) timings->alloc_ns = monotonicNowNs() - t_after_collect;
        return empty;
    }

    auto clearCollectedTagsDirect = [&] {
        for (jint i = 0; i < collected; ++i) {
            if (instances && instances[i]) jvmti->SetTag(instances[i], 0);
        }
    };

    jobjectArray result = env->NewObjectArray(collected, klass, nullptr);
    const jlong t_after_alloc = timings ? monotonicNowNs() : 0;
    if (timings) timings->alloc_ns = t_after_alloc - t_after_collect;
    if (!result) {
        env->ExceptionClear();
        const bool direct = (collected <= kTagClearWalkThreshold && instances != nullptr);
        if (direct) clearCollectedTagsDirect();
        if (out_tags) { jvmti->Deallocate(reinterpret_cast<unsigned char*>(out_tags)); out_tags = nullptr; }
        releaseLocalRefsIfNeeded();
        if (instances) { jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances)); instances = nullptr; }
        if (!direct) clearTagRange(jvmti, klass, base, used_buckets);
        return std::unexpected(JVMTI_ERROR_OUT_OF_MEMORY);
    }

    bool can_place_by_bucket = (has_local_frame && used_buckets > 1 && out_tags != nullptr);
    jlong bucket_pos[kEnumBucketCap]{};
    if (can_place_by_bucket) {
        jlong actual_counts[kEnumBucketCap]{};
        for (jint i = 0; i < collected; ++i) {
            if (!instances || !instances[i]) continue;
            const jlong b = out_tags[i] - base;
            if (b < 0 || b >= used_buckets) {
                can_place_by_bucket = false;
                break;
            }
            actual_counts[static_cast<size_t>(b)]++;
        }
        if (can_place_by_bucket) {
            jlong acc = 0;
            for (jint b = 0; b < used_buckets; ++b) {
                bucket_pos[b] = acc;
                acc += actual_counts[b];
            }
        }
    }

    jsize idx = 0;
    bool tags_cleared = false;
    if (has_local_frame) {
        if (can_place_by_bucket) {
            for (jint i = 0; i < collected; ++i) {
                jobject o = instances ? instances[i] : nullptr;
                if (o) {
                    const jint b = static_cast<jint>(out_tags[i] - base);
                    env->SetObjectArrayElement(result, static_cast<jsize>(bucket_pos[b]++), o);
                    ++idx;
                }
            }
        } else {
            for (jint i = 0; i < collected; ++i) {
                jobject o = instances ? instances[i] : nullptr;
                if (o) {
                    env->SetObjectArrayElement(result, idx++, o);
                }
            }
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    } else {
        const bool direct_clear_inline = (collected <= kTagClearWalkThreshold);
        for (jint i = 0; i < collected; ++i) {
            jobject o = instances ? instances[i] : nullptr;
            if (o && env->IsInstanceOf(o, klass)) {
                env->SetObjectArrayElement(result, idx, o);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                } else {
                    ++idx;
                }
            }
            if (o) {
                if (direct_clear_inline) jvmti->SetTag(o, 0);
                env->DeleteLocalRef(o);
            }
        }
        if (direct_clear_inline) tags_cleared = true;
    }
    if (out_tags) { jvmti->Deallocate(reinterpret_cast<unsigned char*>(out_tags)); out_tags = nullptr; }

    const jlong t_after_fill = timings ? monotonicNowNs() : 0;
    if (timings) { timings->fill_ns = t_after_fill - t_after_alloc; timings->kept_count = static_cast<jlong>(idx); }

    jobjectArray final_result = result;
    if (idx != collected) {
        jobjectArray trimmed = env->NewObjectArray(idx, klass, nullptr);
        if (!trimmed) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (!has_local_frame) env->DeleteLocalRef(result);
            const bool direct = (!tags_cleared && collected <= kTagClearWalkThreshold && instances != nullptr);
            if (direct) { clearCollectedTagsDirect(); tags_cleared = true; }
            if (instances) { jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances)); instances = nullptr; }
            if (has_local_frame) { env->PopLocalFrame(nullptr); has_local_frame = false; }
            if (!tags_cleared) clearTagRange(jvmti, klass, base, used_buckets);
            return std::unexpected(JVMTI_ERROR_OUT_OF_MEMORY);
        }
        for (jsize i = 0; i < idx; ++i) {
            jobject e = env->GetObjectArrayElement(result, i);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                if (e) env->DeleteLocalRef(e);
                continue;
            }
            if (e) {
                env->SetObjectArrayElement(trimmed, i, e);
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->DeleteLocalRef(e);
            }
        }
        if (!has_local_frame) env->DeleteLocalRef(result);
        final_result = trimmed;
    }

    if (!tags_cleared && collected <= kTagClearWalkThreshold && instances) {
        clearCollectedTagsDirect();
        tags_cleared = true;
    }
    if (instances) { jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances)); instances = nullptr; }
    if (has_local_frame) { final_result = reinterpret_cast<jobjectArray>(env->PopLocalFrame(final_result)); has_local_frame = false; }
    if (!tags_cleared) { clearTagRange(jvmti, klass, base, used_buckets); tags_cleared = true; }
    if (timings) timings->cleanup_ns = monotonicNowNs() - t_after_fill;

    return final_result;
}

/** 引用者扫描上下文 */
struct ReferrerContext {
    jlong target_tag;
    jlong referrer_tag;
    bool has_self_ref{false};
};

/** 引用关系遍历核心回调函数，支持自引用标记与空指针防护 */
static jint JNICALL ReferrerCallback(
    jvmtiHeapReferenceKind reference_kind,
    const jvmtiHeapReferenceInfo* reference_info,
    jlong class_tag,
    jlong referrer_class_tag,
    jlong size,
    jlong* tag_ptr,
    jlong* referrer_tag_ptr,
    jint length,
    void* user_data
) {
    (void)reference_kind;
    (void)reference_info;
    (void)class_tag;
    (void)referrer_class_tag;
    (void)size;
    (void)length;
    auto* ctx = reinterpret_cast<ReferrerContext*>(user_data);
    if (tag_ptr != nullptr && *tag_ptr == ctx->target_tag) {
        // GC Roots（如线程栈局部变量、JNI 全局引用）的 referrer_tag_ptr 为空指针，此处仅追踪堆内对象的字段引用
        if (referrer_tag_ptr != nullptr) {
            if (*referrer_tag_ptr == ctx->target_tag) {
                ctx->has_self_ref = true;
            } else {
                *referrer_tag_ptr = ctx->referrer_tag;
            }
        }
    }
    return JVMTI_VISIT_OBJECTS;
}

/**
 * 获取指定 Java 对象在堆中的所有直接引用者（Referrers）并返回局部引用数组。
 * 语义说明：仅统计从 GC Roots 可达的堆中其他对象对它的直接对象间引用（不含栈帧局部变量、JNI 全局引用等 Root，
 * 也不包含尚未回收的不可达对象）；不按 reference_kind 过滤，因此类元信息、数组元素与普通字段引用均会纳入。
 * 若目标对象本身为 Class 对象，堆中所有该类的实例均会被视作引用者（即实例对自身类的类引用关系）。
 */
static std::expected<jobjectArray, jvmtiError> getReferrersInternal(jvmtiEnv* jvmti, JNIEnv* env, jobject target_object) {
    if (!jvmti || !env || !target_object) return std::unexpected(JVMTI_ERROR_NULL_POINTER);

    NativeLockGuard lock(g_heap_mutex);

    TagPair tags = allocateTagPair();
    jlong target_tag = tags.first;
    jlong referrer_tag = tags.second;

    jvmtiError err_tag = jvmti->SetTag(target_object, target_tag);
    if (err_tag != JVMTI_ERROR_NONE) {
        return std::unexpected(err_tag);
    }

    jvmtiHeapCallbacks callbacks{
        .heap_reference_callback = ReferrerCallback,
    };

    ReferrerContext ctx{
        .target_tag = target_tag,
        .referrer_tag = referrer_tag,
        .has_self_ref = false,
    };

    // 传入 JVMTI_HEAP_FILTER_UNTAGGED：FollowReferences 仍遍历完整可达图，但仅对已打标目标对象
    // 触发回调，消除海量无关边回调与 JDK 15+ 未逃逸对象的全局 EscapeBarrier 去优化冲击。
    jvmtiError err_follow = jvmti->FollowReferences(
        JVMTI_HEAP_FILTER_UNTAGGED,
        nullptr,
        nullptr,
        &callbacks,
        &ctx
    );
    if (err_follow != JVMTI_ERROR_NONE) {
        jvmti->SetTag(target_object, 0);
        clearTagFromHeap(jvmti, referrer_tag);
        return std::unexpected(err_follow);
    }

    if (ctx.has_self_ref) {
        jvmti->SetTag(target_object, referrer_tag);
    }

    bool has_local_frame = (env->PushLocalFrame(64) == JNI_OK);
    if (!has_local_frame && env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    jint count = 0;
    jobject* instances = nullptr;

    SCOPE_EXIT([&] {
        if (instances != nullptr) {
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances));
            instances = nullptr;
        }
        if (has_local_frame) {
            env->PopLocalFrame(nullptr);
            has_local_frame = false;
        }
    });

    jlong search_tag = referrer_tag;
    jvmtiError err_get = jvmti->GetObjectsWithTags(1, &search_tag, &count, &instances, nullptr);
    if (err_get != JVMTI_ERROR_NONE) {
        jvmti->SetTag(target_object, 0);
        clearTagFromHeap(jvmti, referrer_tag);
        return std::unexpected(err_get);
    }

    auto cleanupReferrers = [&] {
        const bool direct = (count <= kTagClearWalkThreshold);
        if (direct) {
            jvmti->SetTag(target_object, 0);
            for (jint i = 0; i < count; ++i) {
                if (instances[i]) jvmti->SetTag(instances[i], 0);
            }
        }
        if (instances) {
            if (!has_local_frame) {
                for (jint i = 0; i < count; ++i) {
                    if (instances[i]) env->DeleteLocalRef(instances[i]);
                }
            }
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances));
            instances = nullptr;
        }
        if (has_local_frame) {
            env->PopLocalFrame(nullptr);
            has_local_frame = false;
        }
        if (!direct) {
            clearTagFromHeap(jvmti, referrer_tag);
        }
    };

    jclass obj_class = getObjectClass(env);
    if (!obj_class) {
        cleanupReferrers();
        return std::unexpected(JVMTI_ERROR_CLASS_NOT_PREPARED);
    }

    jobjectArray result_array = env->NewObjectArray(count, obj_class, nullptr);
    if (!result_array) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        cleanupReferrers();
        return std::unexpected(JVMTI_ERROR_OUT_OF_MEMORY);
    }

    const bool direct_clear = (count <= kTagClearWalkThreshold);
    std::span<jobject> objs(instances, static_cast<size_t>(count));
    jsize idx = 0;
    for (jobject element : objs) {
        env->SetObjectArrayElement(result_array, idx, element);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        } else {
            ++idx;
        }
        if (direct_clear && element) jvmti->SetTag(element, 0);
        if (!has_local_frame && element) env->DeleteLocalRef(element);
    }

    if (direct_clear) {
        jvmti->SetTag(target_object, 0);
    }
    if (instances) {
        jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances));
        instances = nullptr;
    }
    if (has_local_frame) {
        result_array = reinterpret_cast<jobjectArray>(env->PopLocalFrame(result_array));
        has_local_frame = false;
    }
    if (!direct_clear) {
        jvmti->SetTag(target_object, 0);
        clearTagFromHeap(jvmti, referrer_tag);
    }
    return result_array;
}

static void throwJvmtiException(JNIEnv* env, const char* msg, jvmtiError err) {
    // 优先尊重底层已有异常（如内存不足导致的 OOM），避免二次调用引发未知行为
    if (env->ExceptionCheck()) return;
    const char* ex_name = "java/lang/RuntimeException";
    switch (err) {
        case JVMTI_ERROR_NULL_POINTER:
            ex_name = "java/lang/NullPointerException";
            break;
        case JVMTI_ERROR_ILLEGAL_ARGUMENT:
            ex_name = "java/lang/IllegalArgumentException";
            break;
        case JVMTI_ERROR_OUT_OF_MEMORY:
            ex_name = "java/lang/OutOfMemoryError";
            break;
        default:
            break;
    }
    jclass ex_class = env->FindClass(ex_name);
    if (!ex_class) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        ex_class = env->FindClass("java/lang/RuntimeException");
    }
    if (ex_class) {
        char buf[256];
        std::snprintf(buf, sizeof(buf), "%s (jvmtiError: %d)", msg, static_cast<int>(err));
        env->ThrowNew(ex_class, buf);
        env->DeleteLocalRef(ex_class);
    }
}

extern "C" {

/**
 * 兼容传统 Panama FFM 符号调用的导出包装 (获取指定类的活跃实例，调用方拥有全局引用)
 * 注意：首参数 jvmtiEnv* 被忽略，内部统一使用经过验证并缓存的全局单例 JVMTI 环境。
 */
JNIEXPORT jobjectArray JNICALL GetInstances(jvmtiEnv* /*ignored*/, JNIEnv* env, jclass klass) {
    if (!env) return nullptr;
    auto ti = getOrAcquireJvmti(env);
    if (!ti) return nullptr;
    auto res = getInstancesInternal(*ti, env, klass);
    if (!res) {
        // Panama FFM 离开 native 栈不会自动抛出异常，必须清空内部遗留的未决异常
        env->ExceptionClear();
        return nullptr;
    }
    jobjectArray local = *res;
    jobjectArray global = reinterpret_cast<jobjectArray>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!global) {
        // 全局引用创建失败时清空未决异常，避免污染后续调用栈
        env->ExceptionClear();
        return nullptr;
    }
    return global;
}

/**
 * 兼容传统 Panama FFM 符号调用的导出包装 (获取指定对象的引用者，调用方拥有全局引用)
 * 注意：首参数 jvmtiEnv* 被忽略，内部统一使用经过验证并缓存的全局单例 JVMTI 环境。
 */
JNIEXPORT jobjectArray JNICALL GetReferrers(jvmtiEnv* /*ignored*/, JNIEnv* env, jobject target_object) {
    if (!env) return nullptr;
    auto ti = getOrAcquireJvmti(env);
    if (!ti) return nullptr;
    auto res = getReferrersInternal(*ti, env, target_object);
    if (!res) {
        // Panama FFM 离开 native 栈不会自动抛出异常，必须清空内部遗留的未决异常
        env->ExceptionClear();
        return nullptr;
    }
    jobjectArray local = *res;
    jobjectArray global = reinterpret_cast<jobjectArray>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!global) {
        // 全局引用创建失败时清空未决异常，避免污染后续调用栈
        env->ExceptionClear();
        return nullptr;
    }
    return global;
}

/** JNI 导出函数：快速探测指定类在堆中是否存在活跃实例（命中首个即中止） */
JNIEXPORT jboolean JNICALL Java_remold_util_LibTool_nHasLiveInstance(
    JNIEnv* env,
    jclass,
    jclass target_clazz
) {
    if (!env) return JNI_FALSE;
    auto jvmti = getOrAcquireJvmti(env);
    if (!jvmti) {
        throwJvmtiException(env, "Failed to acquire JVMTI environment", jvmti.error());
        return JNI_FALSE;
    }
    auto res = hasLiveInstanceInternal(*jvmti, env, target_clazz);
    if (!res) {
        throwJvmtiException(env, "JVMTI hasLiveInstance failed", res.error());
        return JNI_FALSE;
    }
    return *res;
}

/** JNI 导出函数：获取指定类所有未回收实例，失败时向 Java 抛出异常 */
JNIEXPORT jint JNICALL Java_remold_util_LibTool_nEnumBucketCount(JNIEnv* /*env*/, jclass /*cls*/, jlong count) {
    return enumBucketCount(count);
}

JNIEXPORT jint JNICALL Java_remold_util_LibTool_nEnumBucketWindow(JNIEnv* /*env*/, jclass /*cls*/) {
    return static_cast<jint>(kEnumBucketWindow);
}

JNIEXPORT jobjectArray JNICALL Java_remold_util_LibTool_nGetInstances(
    JNIEnv* env,
    jclass,
    jclass target_clazz
) {
    if (!env) return nullptr;
    auto jvmti = getOrAcquireJvmti(env);
    if (!jvmti) {
        throwJvmtiException(env, "Failed to acquire JVMTI environment", jvmti.error());
        return nullptr;
    }
    auto res = getInstancesInternal(*jvmti, env, target_clazz);
    if (!res) {
        throwJvmtiException(env, "JVMTI getInstances failed", res.error());
        return nullptr;
    }
    return *res;
}

/** JNI 导出函数：获取指定类所有未回收实例并回传各阶段纳秒计时，失败时向 Java 抛出异常 */
JNIEXPORT jobjectArray JNICALL Java_remold_util_LibTool_nGetInstancesTimed(
    JNIEnv* env,
    jclass,
    jclass target_clazz,
    jlongArray timings_out
) {
    if (!env) return nullptr;
    auto jvmti = getOrAcquireJvmti(env);
    if (!jvmti) {
        throwJvmtiException(env, "Failed to acquire JVMTI environment", jvmti.error());
        return nullptr;
    }
    InstanceQueryTimings timings{};
    const bool write_timings = timings_out != nullptr && env->GetArrayLength(timings_out) >= 8;
    auto res = getInstancesInternal(*jvmti, env, target_clazz, write_timings ? &timings : nullptr);
    if (!res) {
        throwJvmtiException(env, "JVMTI getInstances failed", res.error());
        return nullptr;
    }
    if (write_timings) {
        const jlong buf[8] = {
            timings.validate_ns,
            timings.iterate_ns,
            timings.collect_ns,
            timings.alloc_ns,
            timings.fill_ns,
            timings.cleanup_ns,
            timings.raw_count,
            timings.kept_count,
        };
        env->SetLongArrayRegion(timings_out, 0, 8, buf);
    }
    return *res;
}

/**
 * JNI 导出函数：一次全堆遍历收集一批类的实例，返回与 targets 等长的 {@code Object[]} 数组
 * （下标对齐 targets）。失败/形态不支持时返回 {@code null}，Java 侧退回逐类路径。
 */
JNIEXPORT jobjectArray JNICALL Java_remold_util_LibTool_nGetInstancesBatch(
    JNIEnv* env,
    jclass,
    jobjectArray targets_array,
    jlongArray timings_out
) {
    if (!env || !targets_array) return nullptr;
    auto jvmti = getOrAcquireJvmti(env);
    if (!jvmti) {
        throwJvmtiException(env, "Failed to acquire JVMTI environment", jvmti.error());
        return nullptr;
    }

    const jint k = env->GetArrayLength(targets_array);
    if (k <= 0 || k > kMaxBatchTargets) return nullptr;

    // 局部引用：targets 的每个元素、遍历产生的类引用与对象引用都在这个 frame 里，
    // 退出时只保留结果数组（PopLocalFrame(result)）。
    if (env->PushLocalFrame(k + 64) != JNI_OK) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }

    std::vector<jclass> targets(static_cast<size_t>(k), nullptr);
    for (jint i = 0; i < k; ++i) {
        targets[static_cast<size_t>(i)] = static_cast<jclass>(env->GetObjectArrayElement(targets_array, i));
    }

    BatchScanOutcome out;
    BatchScanTimings timings{};
    const bool write_timings = timings_out != nullptr && env->GetArrayLength(timings_out) >= 6;
    if (auto e = batchScanInternal(*jvmti, env, targets.data(), k, false, out,
                                   write_timings ? &timings : nullptr);
        e != JVMTI_ERROR_NONE) {
        // 形态不支持 / 环境失败：交给 Java 侧退回逐类路径，不向调用方抛错。
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->PopLocalFrame(nullptr);
        return nullptr;
    }
    SCOPE_EXIT([&] {
        if (out.objects) (*jvmti)->Deallocate(reinterpret_cast<unsigned char*>(out.objects));
        if (out.tags) (*jvmti)->Deallocate(reinterpret_cast<unsigned char*>(out.tags));
    });

    const jlong t_before_fill = write_timings ? monotonicNowNs() : 0;
    jclass obj_clazz = getObjectClass(env);
    if (!obj_clazz) {
        env->PopLocalFrame(nullptr);
        return nullptr;
    }

    // 先按组计数再分配精确长度，避免 Null 槽（集合比较口径下 null 槽会被误判成差异）。
    std::vector<jlong> counts(static_cast<size_t>(k), 0);
    std::vector<jlong> slots(static_cast<size_t>(out.count), 0);
    for (jint i = 0; i < out.count; ++i) {
        if (!out.objects || !out.objects[i]) continue;
        const jlong rel = out.tags ? (out.tags[i] - out.object_tag_base) : -1;
        if (rel < 0 || rel >= static_cast<jlong>(out.active_masks.size())) continue;
        const jlong mask = out.active_masks[static_cast<size_t>(rel)];
        slots[static_cast<size_t>(i)] = mask;
        for (jint t = 0; t < k; ++t) {
            if ((mask & (jlong{1} << t)) != 0) counts[static_cast<size_t>(t)]++;
        }
    }

    jobjectArray result = env->NewObjectArray(k, obj_clazz, nullptr);
    if (!result) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->PopLocalFrame(nullptr);
        return nullptr;
    }
    std::vector<jobjectArray> rows(static_cast<size_t>(k), nullptr);
    std::vector<jlong> cursors(static_cast<size_t>(k), 0);
    for (jint t = 0; t < k; ++t) {
        rows[static_cast<size_t>(t)] = env->NewObjectArray(static_cast<jsize>(counts[static_cast<size_t>(t)]),
                                                           obj_clazz, nullptr);
        if (!rows[static_cast<size_t>(t)]) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->PopLocalFrame(nullptr);
            return nullptr;
        }
        env->SetObjectArrayElement(result, t, rows[static_cast<size_t>(t)]);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    for (jint i = 0; i < out.count; ++i) {
        jobject o = out.objects ? out.objects[i] : nullptr;
        if (!o) continue;
        const jlong mask = slots[static_cast<size_t>(i)];
        for (jint t = 0; t < k; ++t) {
            if ((mask & (jlong{1} << t)) == 0) continue;
            env->SetObjectArrayElement(rows[static_cast<size_t>(t)],
                                       static_cast<jsize>(cursors[static_cast<size_t>(t)]++), o);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }
    if (write_timings) {
        timings.fill_ns = monotonicNowNs() - t_before_fill;
        const jlong buf[6] = {
            timings.enumerate_ns,
            timings.iterate_ns,
            timings.collect_ns,
            timings.fill_ns,
            timings.cleanup_ns,
            timings.raw_count,
        };
        env->SetLongArrayRegion(timings_out, 0, 6, buf);
    }
    return reinterpret_cast<jobjectArray>(env->PopLocalFrame(result));
}

/**
 * JNI 导出函数：一次全堆遍历判定一批类「有没有存活实例」，返回位掩码（位 i = targets[i]）。
 * 全部 target 都已见到实例时立即 JVMTI_VISIT_ABORT 早停；失败/形态不支持返回 -1，
 * Java 侧退回逐类 {@link #Java_remold_util_LibTool_nHasLiveInstance} 路径。
 */
JNIEXPORT jlong JNICALL Java_remold_util_LibTool_nHasLiveInstancesBatch(
    JNIEnv* env,
    jclass,
    jobjectArray targets_array
) {
    if (!env || !targets_array) return -1;
    auto jvmti = getOrAcquireJvmti(env);
    if (!jvmti) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return -1;
    }
    const jint k = env->GetArrayLength(targets_array);
    if (k <= 0 || k > kMaxBatchTargets) return -1;
    if (env->PushLocalFrame(k + 64) != JNI_OK) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return -1;
    }
    std::vector<jclass> targets(static_cast<size_t>(k), nullptr);
    for (jint i = 0; i < k; ++i) {
        targets[static_cast<size_t>(i)] = static_cast<jclass>(env->GetObjectArrayElement(targets_array, i));
    }
    BatchScanOutcome out;
    jlong seen = -1;
    if (auto e = batchScanInternal(*jvmti, env, targets.data(), k, true, out, nullptr);
        e == JVMTI_ERROR_NONE) {
        seen = out.seen_mask;
    } else if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->PopLocalFrame(nullptr);
    return seen;
}

/** JNI 导出函数：实例收集类全堆遍历的累计次数（探针取证用，真实计数而非估算）。 */
JNIEXPORT jlong JNICALL Java_remold_util_LibTool_nHeapWalkCount(JNIEnv* /*env*/, jclass /*cls*/) {
    return g_heap_walk_count.load(std::memory_order_relaxed);
}

/** JNI 导出函数：获取指定对象的所有直接引用者，失败时向 Java 抛出异常 */
JNIEXPORT jobjectArray JNICALL Java_remold_util_LibTool_nGetReferrers(
    JNIEnv* env,
    jclass,
    jobject target_object
) {
    if (!env) return nullptr;
    auto jvmti = getOrAcquireJvmti(env);
    if (!jvmti) {
        throwJvmtiException(env, "Failed to acquire JVMTI environment", jvmti.error());
        return nullptr;
    }
    auto res = getReferrersInternal(*jvmti, env, target_object);
    if (!res) {
        throwJvmtiException(env, "JVMTI getReferrers failed", res.error());
        return nullptr;
    }
    return *res;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    if (!vm) return JNI_ERR;
    g_jvm.store(vm, std::memory_order_release);

    void* env_raw = nullptr;
    if (vm->GetEnv(&env_raw, JNI_VERSION_1_6) != JNI_OK || !env_raw) {
        return JNI_ERR;
    }
    auto* env = reinterpret_cast<JNIEnv*>(env_raw);

    (void)getOrAcquireJvmti(env);
    (void)getObjectClass(env);

    jclass clazz = env->FindClass("remold/util/LibTool");
    if (clazz) {
        JNINativeMethod methods[] = {
            {
                .name = const_cast<char*>("nGetInstances"),
                .signature = const_cast<char*>("(Ljava/lang/Class;)[Ljava/lang/Object;"),
                .fnPtr = reinterpret_cast<void*>(&Java_remold_util_LibTool_nGetInstances),
            },
            {
                .name = const_cast<char*>("nGetReferrers"),
                .signature = const_cast<char*>("(Ljava/lang/Object;)[Ljava/lang/Object;"),
                .fnPtr = reinterpret_cast<void*>(&Java_remold_util_LibTool_nGetReferrers),
            },
            {
                .name = const_cast<char*>("nHasLiveInstance"),
                .signature = const_cast<char*>("(Ljava/lang/Class;)Z"),
                .fnPtr = reinterpret_cast<void*>(&Java_remold_util_LibTool_nHasLiveInstance),
            },
            {
                .name = const_cast<char*>("nGetInstancesTimed"),
                .signature = const_cast<char*>("(Ljava/lang/Class;[J)[Ljava/lang/Object;"),
                .fnPtr = reinterpret_cast<void*>(&Java_remold_util_LibTool_nGetInstancesTimed),
            },
            {
                .name = const_cast<char*>("nEnumBucketCount"),
                .signature = const_cast<char*>("(J)I"),
                .fnPtr = reinterpret_cast<void*>(&Java_remold_util_LibTool_nEnumBucketCount),
            },
            {
                .name = const_cast<char*>("nEnumBucketWindow"),
                .signature = const_cast<char*>("()I"),
                .fnPtr = reinterpret_cast<void*>(&Java_remold_util_LibTool_nEnumBucketWindow),
            },
            {
                .name = const_cast<char*>("nGetInstancesBatch"),
                .signature = const_cast<char*>("([Ljava/lang/Class;[J)[Ljava/lang/Object;"),
                .fnPtr = reinterpret_cast<void*>(&Java_remold_util_LibTool_nGetInstancesBatch),
            },
            {
                .name = const_cast<char*>("nHasLiveInstancesBatch"),
                .signature = const_cast<char*>("([Ljava/lang/Class;)J"),
                .fnPtr = reinterpret_cast<void*>(&Java_remold_util_LibTool_nHasLiveInstancesBatch),
            },
            {
                .name = const_cast<char*>("nHeapWalkCount"),
                .signature = const_cast<char*>("()J"),
                .fnPtr = reinterpret_cast<void*>(&Java_remold_util_LibTool_nHeapWalkCount),
            },
        };
        for (auto& m : methods) {
            if (env->RegisterNatives(clazz, &m, 1) != JNI_OK && env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(clazz);
    } else {
        env->ExceptionClear();
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
    NativeLockGuard lock(g_jvmti_mutex);
    jvmtiEnv* ti = g_jvmti.exchange(nullptr, std::memory_order_acq_rel);
    if (ti) {
        (void) ti->DisposeEnvironment();
    }
    jclass obj_cls = g_object_class.exchange(nullptr, std::memory_order_acq_rel);
    if (obj_cls && vm) {
        void* env_raw = nullptr;
        if (vm->GetEnv(&env_raw, JNI_VERSION_1_6) == JNI_OK && env_raw) {
            reinterpret_cast<JNIEnv*>(env_raw)->DeleteGlobalRef(obj_cls);
        }
    }
}

} // extern "C"