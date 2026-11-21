package remold;

import remold.ref.PendingLoadPatch;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code (loader scope, 类名)} 分键的「类名 → 名字集合」表。
 *
 * <p><b>为什么键必须带 loader</b>：同一个点分名在一个 VM 里可能有多份 {@code Class}（宿主加载器的权威
 * 副本 + 兜底定义出的裸副本 + 抛弃式预测副本）。只按类名键时，后写入的条目会顶掉先写入的：A 算出的
 * lambda 自造槽位名被 B 的成功回执取走，下一轮 B 的 lambda 按 A 的槽位名配对——静默跑错
 * （{@code docs/status.md} §5 风险 15）。scope 号由 {@link remold.vcs.AlignmentMetaStore#loaderScopeId}
 * 统一分配，与 {@code PendingAlignedClasses}、{@code PendingLoadPatch}、{@code LoadFrontier} 同一口径，
 * 本类不自己造 loader 身份。</p>
 *
 * <p><b>映射口径</b>：键是 {@code scope + '\0' + 类名}。因此 {@code keySet} / {@code size} /
 * {@code entrySet} / {@code get(Object)} 都是<b>名字维度的并集视图</b>（同一名字的每个 scope 各算一条），
 * 供编号避让、诊断与"有没有"这类读者使用；<b>需要精确身份的地方必须用 {@code (long, String)} 重载</b>。</p>
 *
 * <p><b>只按类名的写入</b>（拿不到 loader 的旧路径、VCS 回退等）在写入时用
 * {@link RemoldAgent#scopeOfLoadedClass} 解析一次并记进 {@code writeScope}：写入到消费之间
 * {@code loadedClassesMap} 被重建也不会把暂存读丢，而消费侧仍按<b>同一个写入者解析出的 scope</b>
 * 取，不会跨 loader 借条目。</p>
 */
final class ScopeKeyedNames extends AbstractMap<String, Set<String>> {

	private final Map<String, Set<String>> entries = new ConcurrentHashMap<>();

	/** 只按类名写入时解析出的 scope（写入者身份）；精确读写不依赖它。 */
	private final Map<String, Long> writeScope = new ConcurrentHashMap<>();

	private static String key(long scope, String className) {
		return scope + "\u0000" + className;
	}

	private static String nameOf(String key) {
		int split = key.indexOf('\u0000');
		return split < 0 ? key : key.substring(split + 1);
	}

	// ------------------------------------------------------------------ 写入

	/** 按当前定义 loader 解析 scope 后写入（拿不到 loader 的旧路径）。 */
	@Override
	public Set<String> put(String className, Set<String> names) {
		if (className == null) return null;
		return put(RemoldAgent.scopeOfLoadedClass(className), className, names);
	}

	/** 精确 scope 写入：scope 必须来自 {@link remold.vcs.AlignmentMetaStore#loaderScopeId}。 */
	Set<String> put(long scope, String className, Set<String> names) {
		if (className == null) return null;
		writeScope.put(className, scope);
		return entries.put(key(scope, className), names == null ? Set.of() : names);
	}

	// ------------------------------------------------------------------ 取走（写入者的 scope）

	/** 精确 scope 取走；scope 不匹配说明条目属于别的 loader，一步都不借。 */
	Set<String> remove(long scope, String className) {
		if (className == null) return null;
		Long written = writeScope.get(className);
		if (written != null && written == scope) writeScope.remove(className, scope);
		return entries.remove(key(scope, className));
	}

	/** 按类名取走：scope 取写入时记下的那一个，没有则按当前定义 loader 解析。 */
	@Override
	public Set<String> remove(Object className) {
		if (!(className instanceof String name)) return null;
		Long written = writeScope.remove(name);
		long scope = written != null ? written : RemoldAgent.scopeOfLoadedClass(name);
		return entries.remove(key(scope, name));
	}

	/**
	 * 成功回执取走一轮暂存：精确 scope 优先，取不到再退回 {@link PendingLoadPatch#ANY_SCOPE} 哨兵。
	 *
	 * <p>"先精确、再哨兵"是仓库里统一的加载器未知语义（{@code PendingLoadPatch} /
	 * {@code PendingAlignedClasses} / {@code LoadFrontier} 同一口径）：写入时拿不到定义 loader 的条目
	 * 只能落在哨兵上，回执侧退回哨兵取的是<b>那一条</b>。绝不按"最后一个写入者的 scope"或"名字随便挑
	 * 一个"取——那正是跨 loader 串用的来源（{@code docs/status.md} §5 风险 15）。</p>
	 */
	Set<String> take(long scope, String className) {
		Set<String> exact = remove(scope, className);
		return exact != null ? exact : remove(PendingLoadPatch.ANY_SCOPE, className);
	}

	// ------------------------------------------------------------------ 读取

	/**
	 * 精确 scope 读：同名类的别的 loader 副本一步都不借。
	 *
	 * <p>取不到时只退回 {@link PendingLoadPatch#ANY_SCOPE} 哨兵（"加载器未知"的登记），
	 * 不按名字随便挑一个、也不按最后一次写入的 scope 取。</p>
	 */
	Set<String> lookup(long scope, String className) {
		if (className == null) return null;
		Set<String> exact = entries.get(key(scope, className));
		return exact != null ? exact : entries.get(key(PendingLoadPatch.ANY_SCOPE, className));
	}

	/** 名字维度并集读取（诊断与旧读者用；精确判定请用 {@link #lookup(long, String)}）。 */
	@Override
	public Set<String> get(Object className) {
		if (!(className instanceof String name)) return null;
		Long written = writeScope.get(name);
		if (written != null) {
			Set<String> exact = entries.get(key(written, name));
			if (exact != null) return exact;
		}
		for (Map.Entry<String, Set<String>> entry : entries.entrySet()) {
			if (name.equals(nameOf(entry.getKey()))) return entry.getValue();
		}
		return null;
	}

	@Override
	public boolean containsKey(Object className) {
		return get(className) != null;
	}

	@Override
	public int size() {
		return entries.size();
	}

	@Override
	public void clear() {
		entries.clear();
		writeScope.clear();
	}

	/** 名字维度并集视图：同名多 scope 时只保留先遍历到的那一条（编号避让只需要名字）。 */
	@Override
	public Set<Entry<String, Set<String>>> entrySet() {
		Map<String, Set<String>> names = new LinkedHashMap<>();
		for (Map.Entry<String, Set<String>> entry : entries.entrySet()) {
			names.putIfAbsent(nameOf(entry.getKey()), entry.getValue());
		}
		return Set.copyOf(names.entrySet());
	}
}
