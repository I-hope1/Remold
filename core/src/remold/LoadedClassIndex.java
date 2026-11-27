package remold;

import remold.offset.LayoutPredictor;
import remold.vcs.AlignmentMetaStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已加载类快照：<b>按 (loader scope, 类名) 分键</b>，同名类在不同 loader 下互不覆盖。
 *
 * <p><b>为什么不能只按类名</b>：一个 dot 名在本 VM 里可能有多份 {@code Class} 对象（宿主加载器的
 * 权威副本 + Agent/System 加载器兜底定义出的裸副本 + 抛弃式预测加载器副本）。只按类名存，
 * 后登记的会顶掉先登记的；查询方拿到的是"最后一个登记的那个 loader 的类"，
 * 于是 {@code pendingInvented} 的"仅类名"解析、"经 loader 反查 scope"这类路径会把 A 的结果
 * 送给 B（{@code docs/status.md} §5 风险 15）。scope 号统一由
 * {@link AlignmentMetaStore#loaderScopeId} 分配，与 {@code PendingAlignedClasses} /
 * {@code PendingLoadPatch} / {@code LoadFrontier} 同一口径。</p>
 *
 * <p><b>"仅类名"查询的降级语义</b>（{@link #get(Object)}，DTO 调用方 / 拿不到 loader 的老代码用）：</p>
 * <ol>
 *   <li>该类名只有一份副本 → 返回它；</li>
 *   <li>多份副本，但按全仓唯一的副本仲裁策略（{@link ClassCopySelector}：预测加载器副本永不入选、
 *       rank 高者优先）能判出<b>唯一</b>权威副本 → 返回它，并打一条 {@code [LOADED-NAME-ARBITRATED]}；</li>
 *   <li>其余情况一律判为<b>歧义</b> → 返回 {@code null} 并打 {@code [LOADED-NAME-AMBIGUOUS]}（含各副本的
 *       loader 清单）。<b>绝不按 map 迭代顺序随便挑一个</b>——挑错副本会让增强重定义在错位的元数据上
 *       拓扑排序（{@code docs/status.md} §5 风险 13/15）。</li>
 * </ol>
 *
 * <p>拿得到 loader 的调用方必须用 {@link #get(long, String)} 精确取，不要退回"仅类名"。</p>
 *
 * <p>{@code keySet} / {@code values} / {@code entrySet} 是<b>并集视图</b>（同名的每个副本都在），
 * 供"扫一遍所有已加载类"的读者扫描 / 宿主家族扫描使用：那里要的是"有没有"，不是"哪一个是权威"。</p>
 *
 * <p><b>为什么继承 {@link ConcurrentHashMap}</b>：{@code RemoldAgent.loadedClassesMap} 是 agent 的公开面，
 * 已发布的插件 jar（{@code plugin-fabric} 的就绪探测、{@code plugin-mc-toast} 的宿主类定位）按
 * <b>旧字段描述符</b> {@code Ljava/util/concurrent/ConcurrentHashMap;} 链接它，声明类型一变就是链接期
 * {@code NoSuchFieldError}。因此本类继承 {@code ConcurrentHashMap}，字段声明类型保持旧描述符、装入本类
 * 实例：描述符不变、行为升级。</p>
 *
 * <p><b>父类存储的语义</b>：{@code ConcurrentHashMap} 自己那张表维护的是<b>纯类名 → 仲裁后的权威副本</b>
 * （与引入 scope 之前的读法一致），每次 {@code (scope, 类名)} 变更后同步。这样任何<b>未被本类覆盖</b>的
 * {@code ConcurrentHashMap} 方法（{@code forEach} / {@code getOrDefault} 等）读到的也是真实副本，
 * 而不是一张空表。写入侧例外：{@code putIfAbsent} / {@code merge} / {@code compute*} 这类复合写没有
 * scope 维度，实现上只落在父类表里——agent 自身与已发布插件都只用 {@code get}/{@code put}/{@code remove}
 * 这一组，不要新增复合写调用点。</p>
 */
public final class LoadedClassIndex extends ConcurrentHashMap<String, Class<?>> {

	/** 类名 → (scope → Class)。同一 scope 内同名类只可能有一份（loader 定义语义保证）。 */
	private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Class<?>>> byName = new ConcurrentHashMap<>();

	/** 已经告警过的类名，避免每轮快照重建时重复刷同一条歧义日志。 */
	private final Set<String> warned = ConcurrentHashMap.newKeySet();

	/** 空快照。 */
	public LoadedClassIndex() {}

	/** 从"类名 → 类"批量登记：scope 由每个类自己的 loader 推导。 */
	public LoadedClassIndex(Map<String, Class<?>> source) {
		putAll(source);
	}

	// ------------------------------------------------------------------ 精确 scope 维度

	/**
	 * 按 scope 登记一份副本。scope 必须来自 {@link AlignmentMetaStore#loaderScopeId}。
	 *
	 * @return 该 (scope, 类名) 上原有的类（没有则 {@code null}）
	 */
	public Class<?> put(long scope, String dotClassName, Class<?> type) {
		if (dotClassName == null || type == null) return null;
		Class<?> previous = byName.computeIfAbsent(dotClassName, ignored -> new ConcurrentHashMap<>())
		 .put(scope, type);
		syncMirror(dotClassName);
		return previous;
	}

	/**
	 * 按 scope 精确查询：同名类的别的 loader 副本<b>一步都不借</b>。
	 *
	 * @return 该 scope 下登记的那一份；没有则 {@code null}
	 */
	public Class<?> get(long scope, String dotClassName) {
		if (dotClassName == null) return null;
		ConcurrentHashMap<Long, Class<?>> copies = byName.get(dotClassName);
		return copies == null ? null : copies.get(scope);
	}

	/** 按 scope 删除：只影响该 scope，别的 loader 的副本原样保留。 */
	public Class<?> remove(long scope, String dotClassName) {
		if (dotClassName == null) return null;
		ConcurrentHashMap<Long, Class<?>> copies = byName.get(dotClassName);
		if (copies == null) return null;
		Class<?> removed = copies.remove(scope);
		if (copies.isEmpty()) byName.remove(dotClassName, copies);
		syncMirror(dotClassName);
		return removed;
	}

	/**
	 * 把某个类名的登记结果同步进父类表（纯类名 → 一份副本）。
	 *
	 * <p><b>键必须齐</b>：父类表的 {@code keySet()} 是"类名维度"的完整名单（{@code loadedBefore}、
	 * {@code stageAConsistent} 这类"这个类加载了没有"的判据都读它），漏一个名字就会让调用方以为它没加载。</p>
	 *
	 * <p><b>值只是占位</b>：真歧义（多副本且判不出唯一权威）时这里放的是其中一份，但
	 * {@link #get(Object)} 仍按同一套仲裁返回 {@code null} + {@code warn}——对外暴露的"按名字取权威副本"
	 * 语义没有放宽，父类表的值只服务"未被本类覆盖的读法"（如 {@code forEach}）。</p>
	 */
	private void syncMirror(String dotClassName) {
		ConcurrentHashMap<Long, Class<?>> copies = byName.get(dotClassName);
		if (copies == null || copies.isEmpty()) {
			super.remove(dotClassName);
			return;
		}
		if (copies.size() == 1) {
			super.put(dotClassName, copies.values().iterator().next());
			return;
		}
		Class<?> authoritative = strictlyPreferred(copies.values());
		super.put(dotClassName, authoritative != null ? authoritative : copies.values().iterator().next());
	}

	/** 该名字当前登记的全部副本（scope → Class 的只读快照），用于诊断与歧义日志。 */
	public Map<Long, Class<?>> copies(String dotClassName) {
		if (dotClassName == null) return Map.of();
		ConcurrentHashMap<Long, Class<?>> copies = byName.get(dotClassName);
		return copies == null ? Map.of() : Map.copyOf(copies);
	}

	/** 该类名下登记的副本数（0 = 未加载，>1 = 同名多 loader）。 */
	public int copyCount(String dotClassName) {
		if (dotClassName == null) return 0;
		ConcurrentHashMap<Long, Class<?>> copies = byName.get(dotClassName);
		return copies == null ? 0 : copies.size();
	}

	// ------------------------------------------------------------------ "仅类名"降级查询

	/**
	 * "仅类名"查询：唯一副本 / 唯一权威副本 → 返回；歧义 → {@code null} + {@code warn}。
	 *
	 * <p>降级语义的契约见类 Javadoc 第 2 段：<b>不许随便挑一个</b>。拿得到 loader 的代码请用
	 * {@link #get(long, String)}。</p>
	 */
	@Override
	public Class<?> get(Object dotClassName) {
		if (!(dotClassName instanceof String name)) return null;
		ConcurrentHashMap<Long, Class<?>> copies = byName.get(name);
		if (copies == null || copies.isEmpty()) return null;
		if (copies.size() == 1) return copies.values().iterator().next();
		Class<?> authoritative = strictlyPreferred(copies.values());
		if (authoritative != null) {
			// EphemeralClassLoader 是 Remold 内部用于离线布局预测的抛弃式加载器；仅因它尚未 GC 留下临时副本
			// 不构成宿主侧多 loader 竞争，避免无意义的 [LOADED-NAME-ARBITRATED] 告警刷屏。
			if (hasMultipleNonEphemeralCopies(copies.values()) && warned.add(name)) {
				RemoldAgent.warn("[LOADED-NAME-ARBITRATED] " + name + " has " + copies.size()
				 + " loaded copies (" + describe(copies) + "); returning the copy the repository-wide copy"
				 + " arbitration ranks uniquely above the others (" + authoritative.getClassLoader()
				 + "). Use the (scope, name) lookup when the loader is known.");
			}
			return authoritative;
		}
		if (warned.add(name)) {
			RemoldAgent.warn("[LOADED-NAME-AMBIGUOUS] " + name + " has " + copies.size()
			 + " loaded copies (" + describe(copies) + ") and none of them is uniquely authoritative;"
			 + " the name-only lookup returns null instead of picking one. Resolve the loader and use the"
			 + " (scope, name) lookup.");
		}
		return null;
	}

	private static boolean hasMultipleNonEphemeralCopies(Collection<Class<?>> copies) {
		int liveCount = 0;
		for (Class<?> candidate : copies) {
			if (!LayoutPredictor.isEphemeralLoader(candidate.getClassLoader())) {
				if (++liveCount > 1) return true;
			}
		}
		return false;
	}

	/**
	 * 多副本里能否判出<b>唯一</b>权威副本：剔除抛弃式预测加载器副本后，恰好一份，或 rank 严格最高者
	 * 只有一份 → 它；否则 {@code null}（真歧义）。
	 */
	private static Class<?> strictlyPreferred(Collection<Class<?>> copies) {
		List<Class<?>> live = new ArrayList<>(copies.size());
		for (Class<?> candidate : copies) {
			if (LayoutPredictor.isEphemeralLoader(candidate.getClassLoader())) continue;
			live.add(candidate);
		}
		if (live.size() == 1) return live.get(0);
		if (live.isEmpty()) return copies.size() == 1 ? copies.iterator().next() : null;
		int topRank = -1;
		for (Class<?> candidate : live) topRank = Math.max(topRank, ClassCopySelector.loaderRank(candidate));
		Class<?> winner = null;
		for (Class<?> candidate : live) {
			if (ClassCopySelector.loaderRank(candidate) != topRank) continue;
			if (winner != null) return null;   // 同 rank 两份：真歧义，不按迭代顺序挑
			winner = candidate;
		}
		return winner;
	}

	private static String describe(Map<Long, Class<?>> copies) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Long, Class<?>> entry : copies.entrySet()) {
			if (sb.length() > 0) sb.append(", ");
			sb.append("scope=").append(entry.getKey()).append(" loader=")
			 .append(entry.getValue().getClassLoader());
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ Map 视图（并集语义）

	/**
	 * 登记一份副本：scope 由 {@code type} 自己的 loader 推导，所以老代码直接
	 * {@code put(类名, 类)} 也不会把两份副本混进同一个 key。
	 *
	 * @return 该 (scope, 类名) 上原有的类
	 */
	@Override
	public Class<?> put(String dotClassName, Class<?> type) {
		if (dotClassName == null || type == null) return null;
		long scope = AlignmentMetaStore.INSTANCE.loaderScopeId(type.getClassLoader());
		return put(scope, dotClassName, type);
	}

	/** 删除该类名在<b>所有</b> scope 下的副本（测试与"整类下线"用）。 */
	@Override
	public Class<?> remove(Object dotClassName) {
		if (!(dotClassName instanceof String name)) return null;
		ConcurrentHashMap<Long, Class<?>> copies = byName.remove(name);
		Class<?> previous = super.remove(name);
		if (copies == null || copies.isEmpty()) return previous;
		return copies.size() == 1 ? copies.values().iterator().next() : previous;
	}

	@Override
	public void clear() {
		byName.clear();
		warned.clear();
		super.clear();
	}

	@Override
	public boolean containsKey(Object dotClassName) {
		return dotClassName instanceof String name && byName.containsKey(name);
	}

	@Override
	public int size() {
		return byName.size();
	}

	@Override
	public boolean isEmpty() {
		return byName.isEmpty();
	}

	// keySet() 刻意不覆盖：{@link ConcurrentHashMap#keySet()} 的返回类型是 {@code KeySetView}，
	// 而父类表的键已经覆盖所有已登记类名（见 {@link #syncMirror}），名字维度语义与并集视图一致。

	@Override
	public Collection<Class<?>> values() {
		List<Class<?>> all = new ArrayList<>();
		for (ConcurrentHashMap<Long, Class<?>> copies : byName.values()) {
			all.addAll(copies.values());
		}
		return Collections.unmodifiableList(all);
	}

	/** 并集视图：同名多副本时会有多条 key 相同、value 不同的条目（Map 契约上仍然互不相等）。 */
	@Override
	public Set<Entry<String, Class<?>>> entrySet() {
		Set<Entry<String, Class<?>>> all = new LinkedHashSet<>();
		for (Map.Entry<String, ConcurrentHashMap<Long, Class<?>>> named : byName.entrySet()) {
			for (Class<?> type : named.getValue().values()) {
				all.add(new SimpleImmutableEntry<>(named.getKey(), type));
			}
		}
		return Collections.unmodifiableSet(all);
	}

	@Override
	public void putAll(Map<? extends String, ? extends Class<?>> source) {
		for (Map.Entry<? extends String, ? extends Class<?>> entry : source.entrySet()) {
			put(entry.getKey(), entry.getValue());
		}
	}

	@Override
	public boolean containsValue(Object value) {
		for (ConcurrentHashMap<Long, Class<?>> copies : byName.values()) {
			if (copies.containsValue(value)) return true;
		}
		return false;
	}
}
