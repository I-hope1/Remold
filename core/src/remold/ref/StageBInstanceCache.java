package remold.ref;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

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
 * <p>生命周期：仅在 {@code RemoldAgent} 的 stageB → 回调之间存活；批次开始清空旧条目，
 * 未被消费的条目在 {@link #TTL_NANOS} 后过期。条目对 {@code Class<?>} 键与实例数组均只持有
 * <b>弱引用</b>（符合 AGENTS.md 对内部 {@code Class} 键表的规范）：既不阻拦类卸载，也绝不延长
 * 实例生命周期（P0.5 探针会显式 System.gc() 检查残留实例；强引用缓存会把旧实例钉在堆里）。
 * 弱引用被 GC 清掉时回调路径回退到实时枚举，行为仍然正确。</p>
 */
public final class StageBInstanceCache {

	/** 入口过期时间：足够覆盖 postToMain 排队到主线程执行的延迟，又不会长期持有实例引用。 */
	static final long TTL_NANOS = 120_000_000_000L; // 120s

	/**
	 * 弱引用实例数组条目（WeakHashMap 弱持有键，Entry 弱持有实例数组）。
	 * stageB 与 @OnReload 之间若发生 GC（或测试里显式 System.gc()），数组被回收，
	 * {@link #take} 返回 null，回调路径回退到实时枚举——语义正确，只是慢一次。
	 */
	private record Entry(WeakReference<Object[]> instances, long timestampNanos) { }

	private record CountEntry(int count, long timestampNanos) { }

	private static final Map<Class<?>, Entry> CACHE = Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<Class<?>, CountEntry> COUNTS = Collections.synchronizedMap(new WeakHashMap<>());
	private static long lastSweepNanos = System.nanoTime();

	private StageBInstanceCache() { }

	/** 登记本批次补丁枚举的结果（含 0 实例结果）；同类的旧条目直接替换。 */
	public static void put(Class<?> clazz, Object[] instances) {
		put(clazz, instances, System.nanoTime());
	}

	static void put(Class<?> clazz, Object[] instances, long nowNanos) {
		if (clazz == null || instances == null) return;
		synchronized (CACHE) {
			CACHE.put(clazz, new Entry(new WeakReference<>(instances), nowNanos));
			COUNTS.put(clazz, new CountEntry(instances.length, nowNanos));
			if (CACHE.size() > 8 || COUNTS.size() > 8 || nowNanos - lastSweepNanos > TTL_NANOS) {
				sweepLocked(nowNanos);
			}
		}
	}

	/** 取出并移除该类的枚举结果；不存在或已过期返回 null（调用方回退到实时枚举）。 */
	public static Object[] take(Class<?> clazz) {
		return take(clazz, System.nanoTime());
	}

	static Object[] take(Class<?> clazz, long nowNanos) {
		if (clazz == null) return null;
		Entry entry;
		synchronized (CACHE) {
			entry = CACHE.remove(clazz);
			if (CACHE.size() > 8 || COUNTS.size() > 8 || nowNanos - lastSweepNanos > TTL_NANOS) {
				sweepLocked(nowNanos);
			}
		}
		if (entry == null) return null;
		if (nowNanos - entry.timestampNanos() > TTL_NANOS) return null;
		return entry.instances().get();
	}

	/**
	 * 取出并移除本批次补丁阶段登记的实例个数（不依赖 {@link #take} 是否已被 {@code @OnReload} 消费）；
	 * 供同批次末尾的 {@link InitializerDrift} 直接复用，避免对同一个类再跑一遍百万级 JVMTI 枚举。
	 */
	public static Integer takeCount(Class<?> clazz) {
		return takeCount(clazz, System.nanoTime());
	}

	static Integer takeCount(Class<?> clazz, long nowNanos) {
		if (clazz == null) return null;
		CountEntry entry;
		synchronized (CACHE) {
			entry = COUNTS.remove(clazz);
			if (CACHE.size() > 8 || COUNTS.size() > 8 || nowNanos - lastSweepNanos > TTL_NANOS) {
				sweepLocked(nowNanos);
			}
		}
		if (entry == null) return null;
		if (nowNanos - entry.timestampNanos() > TTL_NANOS) return null;
		return entry.count();
	}

	/** 批次开始 / 结束时清空，避免上一批的实例引用滞留。 */
	public static void clear() {
		synchronized (CACHE) {
			CACHE.clear();
			COUNTS.clear();
		}
	}

	static int size() {
		synchronized (CACHE) {
			return CACHE.size();
		}
	}

	private static void sweepLocked(long nowNanos) {
		lastSweepNanos = nowNanos;
		CACHE.entrySet().removeIf(e ->
			nowNanos - e.getValue().timestampNanos() > TTL_NANOS || e.getValue().instances().get() == null);
		COUNTS.entrySet().removeIf(e -> nowNanos - e.getValue().timestampNanos() > TTL_NANOS);
	}
}
