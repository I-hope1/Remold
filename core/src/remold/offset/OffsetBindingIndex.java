package remold.offset;

import java.util.*;

import static remold.offset.OffsetScanner.Binding;
import static remold.offset.OffsetScanner.Confidence;
import static remold.offset.OffsetScanner.Report;

/**
 * 偏移量与访问器绑定索引（S1 查询层）。
 *
 * <p>将 {@link OffsetScanner} 产出的平面 {@link Binding} 集合构造成多维只读索引：
 * <ul>
 *   <li>精确索引：{@code (targetClass, targetField) -> List<Binding>} (CONFIDENCE: EXACT)</li>
 *   <li>类级通配：{@code targetClass -> List<Binding>} (CONFIDENCE: WILDCARD_FIELD)</li>
 *   <li>动态持有者：{@code dynamicHolders} (CONFIDENCE: UNRESOLVED_CLASS，目标类在运行时动态传入)</li>
 * </ul>
 * 所有类名均使用 JVM 内部名（如 {@code "java/lang/String"}）。
 */
public final class OffsetBindingIndex {

	private static final OffsetBindingIndex EMPTY = new OffsetBindingIndex(
		Collections.emptyMap(), Collections.emptyMap(), Collections.emptySet(), 0
	);

	private final Map<String, Map<String, List<Binding>>> exact;
	private final Map<String, List<Binding>>             wildcard;
	private final Set<String>                           dynamicHolders;
	private final int                                   totalCount;

	private OffsetBindingIndex(Map<String, Map<String, List<Binding>>> exact,
	                           Map<String, List<Binding>> wildcard,
	                           Set<String> dynamicHolders,
	                           int totalCount) {
		this.exact = exact;
		this.wildcard = wildcard;
		this.dynamicHolders = dynamicHolders;
		this.totalCount = totalCount;
	}

	public static OffsetBindingIndex empty() {
		return EMPTY;
	}

	public static OffsetBindingIndex from(Report report) {
		if (report == null || report.bindings.isEmpty()) return empty();
		return from(report.bindings);
	}

	public static OffsetBindingIndex from(Collection<Binding> bindings) {
		if (bindings == null || bindings.isEmpty()) return empty();

		Map<String, Map<String, List<Binding>>> exactMap = new LinkedHashMap<>();
		Map<String, List<Binding>> wildcardMap = new LinkedHashMap<>();
		Set<String> dynamicSet = new LinkedHashSet<>();
		int count = 0;

		for (Binding b : bindings) {
			if (b == null) continue;
			count++;

			String targetClass = b.targetClass();
			String targetField = b.targetField();

			if (b.conf() == Confidence.UNRESOLVED_CLASS || targetClass == null) {
				if (b.holderClass() != null) {
					dynamicSet.add(b.holderClass());
				}
				continue;
			}

			if (targetField == null || b.conf() == Confidence.WILDCARD_FIELD) {
				wildcardMap.computeIfAbsent(targetClass, k -> new ArrayList<>()).add(b);
			} else {
				exactMap.computeIfAbsent(targetClass, k -> new LinkedHashMap<>())
				        .computeIfAbsent(targetField, k -> new ArrayList<>())
				        .add(b);
			}
		}

		// 冻结为不可变结构
		Map<String, Map<String, List<Binding>>> frozenExact = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, List<Binding>>> entry : exactMap.entrySet()) {
			Map<String, List<Binding>> fieldMap = new LinkedHashMap<>();
			for (Map.Entry<String, List<Binding>> sub : entry.getValue().entrySet()) {
				fieldMap.put(sub.getKey(), Collections.unmodifiableList(sub.getValue()));
			}
			frozenExact.put(entry.getKey(), Collections.unmodifiableMap(fieldMap));
		}

		Map<String, List<Binding>> frozenWildcard = new LinkedHashMap<>();
		for (Map.Entry<String, List<Binding>> entry : wildcardMap.entrySet()) {
			frozenWildcard.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
		}

		return new OffsetBindingIndex(
			Collections.unmodifiableMap(frozenExact),
			Collections.unmodifiableMap(frozenWildcard),
			Collections.unmodifiableSet(dynamicSet),
			count
		);
	}

	/**
	 * 查询指定类与指定字段的全部偏移持有绑定。
	 *
	 * <p>返回精确匹配该字段的绑定以及作用于该类的全部通配绑定的并集。若均无匹配返回空列表。</p>
	 */
	public List<Binding> lookup(String targetClassInternal, String targetField) {
		if (targetClassInternal == null) return Collections.emptyList();

		List<Binding> exactHits = null;
		Map<String, List<Binding>> fieldMap = exact.get(targetClassInternal);
		if (fieldMap != null && targetField != null) {
			exactHits = fieldMap.get(targetField);
		}

		List<Binding> wildcardHits = wildcard.get(targetClassInternal);

		if (exactHits == null && wildcardHits == null) {
			return Collections.emptyList();
		}
		if (exactHits == null) return wildcardHits;
		if (wildcardHits == null) return exactHits;

		List<Binding> merged = new ArrayList<>(exactHits.size() + wildcardHits.size());
		merged.addAll(exactHits);
		merged.addAll(wildcardHits);
		return Collections.unmodifiableList(merged);
	}

	/**
	 * 查询作用于指定类的通配绑定（如通过反射遍历 {@code getDeclaredFields()} 或字段名动态拼接生成的绑定）。
	 */
	public List<Binding> lookupClassWildcards(String targetClassInternal) {
		if (targetClassInternal == null) return Collections.emptyList();
		List<Binding> hits = wildcard.get(targetClassInternal);
		return hits != null ? hits : Collections.emptyList();
	}

	/**
	 * 是否存在持有指定字段偏移的已知记录。
	 */
	public boolean hasHolders(String targetClassInternal, String targetField) {
		return !lookup(targetClassInternal, targetField).isEmpty();
	}

	/**
	 * 获取所有动态持有者类名集合（内部名）。
	 *
	 * <p>动态持有者在静态分析期由于参数传递等原因无法确定 targetClass（如 Kryo 运行时缓存、
	 * {@code AtomicIntegerFieldUpdaterImpl} 实例）。在判定父类字段漂移时需结合堆存活实例检查。
	 */
	public Set<String> dynamicHolders() {
		return dynamicHolders;
	}

	public int totalBindings() {
		return totalCount;
	}

	public boolean isEmpty() {
		return totalCount == 0;
	}

	@Override
	public String toString() {
		return "OffsetBindingIndex{exactClasses=" + exact.size()
		       + ", wildcardClasses=" + wildcard.size()
		       + ", dynamicHolders=" + dynamicHolders.size()
		       + ", totalBindings=" + totalCount + "}";
	}
}
