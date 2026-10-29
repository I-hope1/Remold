package remold.offset;

import java.util.*;

import static remold.offset.OffsetScanner.Binding;

/**
 * 父类新增实例字段引发的物理偏移漂移安全门（S1 查询与判定层）。
 *
 * <p><b>判定契约（S1 第 6 节）</b>：
 * 当重定义批次中，基类 {@code Base} 新增了<b>实例字段</b>时：
 * <ul>
 *   <li>JVM 规范强制父类字段排在子类字段之前；</li>
 *   <li>因此，{@code Base} 的所有已加载子孙类（{@code Descendants}）所声明的全部实例字段，其物理内存偏移量（{@code fieldOffset}）
 *       必定整体向后位移（漂移）；</li>
 *   <li>如果系统中有任何静态常量、实例字段或访问器缓存了子孙类的这些实例字段偏移，重定义后将直接发生内存脏写或崩溃。</li>
 * </ul>
 *
 * <p><b>判定算法</b>：
 * <pre>{@code
 * descendants = 所有已加载的 Base 的子孙类
 * for D in descendants:
 *     for f in D.声明的全部实例字段:
 *         hits += index.lookup(D, f) ∪ index.lookup(D, *)
 * hits += dynamicHolders 中在堆上有存活实例者
 * if hits 非空 -> SUPERCLASS_FIELD_DRIFT
 * }</pre>
 *
 * <p>本类保持纯函数逻辑设计，通过接口解耦类加载器与堆实例探测，完全支持离线与单元测试。</p>
 */
public final class SuperclassDriftGate {

	private SuperclassDriftGate() {}

	/**
	 * 子孙类提供器：给定基类内部名，返回所有已加载的子孙类内部名集合。
	 */
	@FunctionalInterface
	public interface DescendantSupplier {
		Set<String> getDescendants(String baseInternalName);
	}

	/**
	 * 实例字段提供器：给定类内部名，返回其自身直接声明的全部实例字段名集合（不含父类继承字段，不含 static 字段）。
	 */
	@FunctionalInterface
	public interface InstanceFieldProvider {
		Collection<String> getDeclaredInstanceFields(String internalClassName);
	}

	/**
	 * 存活实例探测器：给定持有者类内部名，判定堆中是否存在存活实例（底层对接 LibTool.getInstances）。
	 */
	@FunctionalInterface
	public interface LiveInstancePredicate {
		boolean hasLiveInstances(String internalClassName);
	}

	/**
	 * 精确或类级通配漂移命中。
	 */
	public record DriftHit(
		String descendantClass,
		String fieldName,
		Binding binding
	) {
		@Override
		public String toString() {
			return descendantClass + "." + fieldName + " -> cached by "
			       + binding.holderClass() + "." + binding.holderMember()
			       + " (" + binding.kind() + ", " + binding.conf() + ") @ " + binding.site();
		}
	}

	/**
	 * 动态持有者漂移命中（UNRESOLVED_CLASS 在堆上有存活实例）。
	 */
	public record DynamicDriftHit(
		String holderClass,
		String reason
	) {
		@Override
		public String toString() {
			return holderClass + " (dynamic holder has live instances on heap: " + reason + ")";
		}
	}

	/**
	 * 漂移判定结论。
	 */
	public record DriftDecision(
		boolean isSafe,
		String baseClass,
		List<DriftHit> hits,
		List<DynamicDriftHit> dynamicHits
	) {
		public static DriftDecision pass(String baseClass) {
			return new DriftDecision(true, baseClass, Collections.emptyList(), Collections.emptyList());
		}

		public static DriftDecision reject(String baseClass, List<DriftHit> hits, List<DynamicDriftHit> dynamicHits) {
			return new DriftDecision(false, baseClass,
				Collections.unmodifiableList(hits),
				Collections.unmodifiableList(dynamicHits));
		}

		/**
		 * 格式化用于告警输出与审计日志的多行报告。
		 */
		public String formatReport() {
			if (isSafe) {
				return "[DRIFT-GATE] PASS: Base class " + baseClass
				       + " added instance field(s), no cached offset holders detected for loaded descendants.";
			}

			StringBuilder sb = new StringBuilder();
			boolean isSubclassOnly = true;
			for (DriftHit h : hits) {
				if (h.descendantClass().equals(baseClass)) {
					isSubclassOnly = false;
					break;
				}
			}

			if (isSubclassOnly) {
				sb.append("[SUPERCLASS_FIELD_DRIFT] Base class ").append(baseClass)
				  .append(" added instance field(s), shifting memory layout for descendants:\n");
			} else {
				sb.append("[LAYOUT_DRIFT] Class ").append(baseClass)
				  .append(" physical memory layout shifted (hole filling / type clustering / drift):\n");
			}

			if (!hits.isEmpty()) {
				sb.append("  Detected ").append(hits.size()).append(" cached offset holder(s):\n");
				for (DriftHit hit : hits) {
					sb.append("    - Field:         ").append(hit.descendantClass).append(".").append(hit.fieldName)
					  .append("\n      Cached by:     ").append(hit.binding.holderClass()).append(".").append(hit.binding.holderMember())
					  .append(" (").append(hit.binding.kind()).append(", ").append(hit.binding.conf()).append(")")
					  .append("\n      Trigger site:  ").append(hit.binding.site()).append("\n");
				}
			}

			if (!dynamicHits.isEmpty()) {
				sb.append("  Detected ").append(dynamicHits.size()).append(" live dynamic offset holder(s):\n");
				for (DynamicDriftHit dh : dynamicHits) {
					sb.append("    - Holder: ").append(dh.holderClass)
					  .append(" (").append(dh.reason).append(")\n");
				}
			}

			sb.append("  Action: FAIL-CLOSED. Redefinition rejected to prevent silent memory corruption.");
			return sb.toString();
		}
	}

	/**
	 * 结果型物理布局漂移判定：基于预测出的具体漂移字段集合，与偏移绑定索引进行交集判定。
	 *
	 * @param rootClassInternal     被重定义的类内部名（如 {@code "com/example/Base"}）
	 * @param driftingFields        通过 LayoutPredictor 探测出的所有发生物理偏移漂移的字段列表
	 * @param index                 偏移绑定索引
	 * @param liveInstancePredicate 动态持有者堆存活实例探测谓词
	 * @return 判定结论（安全放行或阻断拒绝）
	 */
	public static DriftDecision checkLayoutDrift(
		String rootClassInternal,
		List<LayoutPredictor.DriftingField> driftingFields,
		OffsetBindingIndex index,
		LiveInstancePredicate liveInstancePredicate
	) {
		if (rootClassInternal == null || driftingFields == null || driftingFields.isEmpty() || index == null || index.isEmpty()) {
			return DriftDecision.pass(rootClassInternal);
		}

		List<DriftHit> hits = new ArrayList<>();

		// 1. 遍历全部发生物理偏移漂移的字段，检查是否存在已知偏移持有者
		for (LayoutPredictor.DriftingField df : driftingFields) {
			if (df == null) continue;
			List<Binding> bound = index.lookup(df.internalClassName(), df.fieldName());
			for (Binding b : bound) {
				hits.add(new DriftHit(df.internalClassName(), df.fieldName(), b));
			}
		}

		// 2. 如果存在漂移字段，检查动态持有者（UNRESOLVED_CLASS）是否在堆上有存活实例
		List<DynamicDriftHit> dynamicHits = new ArrayList<>();
		if (liveInstancePredicate != null) {
			for (String dynamicHolder : index.dynamicHolders()) {
				if (dynamicHolder == null) continue;
				if (liveInstancePredicate.hasLiveInstances(dynamicHolder)) {
					dynamicHits.add(new DynamicDriftHit(
						dynamicHolder,
						"live instances exist; unresolved target offset may shift"
					));
				}
			}
		}

		if (hits.isEmpty() && dynamicHits.isEmpty()) {
			return DriftDecision.pass(rootClassInternal);
		}

		return DriftDecision.reject(rootClassInternal, hits, dynamicHits);
	}

	/**
	 * 核心纯函数判定：当基类 {@code baseClass} 新增实例字段时，检测是否安全。
	 *
	 * @param baseClassInternal     新增了实例字段的基类内部名（如 {@code "com/example/Base"}）
	 * @param index                 当前扫描器构建的偏移绑定索引
	 * @param descendantSupplier    获取已加载子孙类列表的提供器
	 * @param fieldProvider         获取类所声明的全部实例字段提供器
	 * @param liveInstancePredicate 探测动态持有者堆存活实例的谓词（通常对接 LibTool）
	 * @return 判定结论（安全放行或拒绝阻断）
	 */
	public static DriftDecision checkSuperclassDrift(
		String baseClassInternal,
		OffsetBindingIndex index,
		DescendantSupplier descendantSupplier,
		InstanceFieldProvider fieldProvider,
		LiveInstancePredicate liveInstancePredicate
	) {
		if (baseClassInternal == null || index == null || index.isEmpty()) {
			return DriftDecision.pass(baseClassInternal);
		}

		Set<String> descendants = descendantSupplier.getDescendants(baseClassInternal);
		if (descendants == null || descendants.isEmpty()) {
			// 没有已加载的子孙类，基类自身加字段在尾部追加，天然安全
			return DriftDecision.pass(baseClassInternal);
		}

		List<DriftHit> hits = new ArrayList<>();

		// 1. 遍历全部已加载子孙类的全部实例字段，检查是否存在已知偏移持有者
		for (String descendant : descendants) {
			if (descendant == null || descendant.equals(baseClassInternal)) continue;

			Collection<String> instanceFields = fieldProvider.getDeclaredInstanceFields(descendant);
			if (instanceFields == null || instanceFields.isEmpty()) continue;

			for (String fieldName : instanceFields) {
				List<Binding> bound = index.lookup(descendant, fieldName);
				for (Binding b : bound) {
					hits.add(new DriftHit(descendant, fieldName, b));
				}
			}
		}

		// 2. 检查动态持有者（UNRESOLVED_CLASS）是否在堆上有存活实例
		List<DynamicDriftHit> dynamicHits = new ArrayList<>();
		if (liveInstancePredicate != null) {
			for (String dynamicHolder : index.dynamicHolders()) {
				if (dynamicHolder == null) continue;
				if (liveInstancePredicate.hasLiveInstances(dynamicHolder)) {
					dynamicHits.add(new DynamicDriftHit(
						dynamicHolder,
						"live instances exist; unresolved target offset may shift"
					));
				}
			}
		}

		if (hits.isEmpty() && dynamicHits.isEmpty()) {
			return DriftDecision.pass(baseClassInternal);
		}

		return DriftDecision.reject(baseClassInternal, hits, dynamicHits);
	}
}
