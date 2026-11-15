package remold.ref;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次热更批次内的实例枚举缓存。
 *
 * <p>一次结构性热更在 stageB 里会枚举同一批类的存活实例两次：{@link InitFix#afterRedefine}
 * 的存量字段补丁一次，{@code @OnReload} 实例回调一次。每次枚举都是一次完整 JVMTI 堆遍历，
 * 百万级实例时单次约 3s（2M 实例实测 ~2.9s），而两次拿到的是同一批对象（补丁枚举的数组持有
 * 强引用，枚举后这些实例不会消失）。这里把补丁枚举的结果交给回调侧复用，省掉第二次堆遍历。</p>
 *
 * <p>语义边界：回调拿到的是"重定义完成时"的实例快照。之后新创建的实例本来就不属于
 * "存量实例回调"的目标（它们是在重载之后才构造的，初值已经是新的），因此与逐次重新枚举
 * 的可见差异仅为"枚举窗口内新建实例是否收到回调"，按 {@code @OnReload} 的文档语义应跳过。</p>
 *
 * <p>生命周期：仅在 {@link RemoldAgent} 的 stageB → 回调之间存活；批次开始清空旧条目，
 * 未被消费的条目在 {@link #TTL_NANOS} 后过期。条目只持有实例数组的<b>弱引用</b>：
 * 缓存绝不能延长实例生命周期（P0.5 探针会显式 System.gc() 检查残留实例；强引用缓存
 * 会把旧实例钉在堆里，导致测试失败且真机上造成内存滞留）。弱引用被 GC 清掉时回调路径
 * 回退到实时枚举，行为仍然正确。</p>
 */
public final class StageBInstanceCache {

	/** 入口过期时间：足够覆盖 postToMain 排队到主线程执行的延迟，又不会长期持有实例引用。 */
	private static final long TTL_NANOS = 120_000_000_000L; // 120s

	/**
	 * 用弱引用的原因见 docs：缓存只是"省一次堆遍历"的旁路，绝不能成为实例的强引用持有者。
	 * stageB 与 @OnReload 之间若发生 GC（或测试里显式 System.gc()），数组被回收，
	 * {@link #take} 返回 null，回调路径回退到实时枚举——语义正确，只是慢一次。
	 */
	private record Entry(WeakReference<Object[]> instances, long timestampNanos) { }

	private static final Map<Class<?>, Entry> CACHE = new ConcurrentHashMap<>();

	private StageBInstanceCache() { }

	/** 登记本批次补丁枚举的结果；同类的旧条目直接替换。 */
	public static void put(Class<?> clazz, Object[] instances) {
		if (clazz == null || instances == null || instances.length == 0) return;
		long now = System.nanoTime();
		CACHE.put(clazz, new Entry(new WeakReference<>(instances), now));
		if (CACHE.size() > 8) sweep(now);
	}

	/** 取出并移除该类的枚举结果；不存在或已过期返回 null（调用方回退到实时枚举）。 */
	public static Object[] take(Class<?> clazz) {
		if (clazz == null) return null;
		Entry entry = CACHE.remove(clazz);
		if (entry == null) return null;
		if (System.nanoTime() - entry.timestampNanos() > TTL_NANOS) return null;
		return entry.instances().get();
	}

	/** 批次开始 / 结束时清空，避免上一批的实例引用滞留。 */
	public static void clear() {
		CACHE.clear();
	}

	private static void sweep(long now) {
		CACHE.entrySet().removeIf(e -> now - e.getValue().timestampNanos() > TTL_NANOS);
	}
}
