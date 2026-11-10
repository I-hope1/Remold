package remold;

import remold.offset.LayoutPredictor;

import java.util.Collection;
import java.util.List;

/**
 * 同名类多副本仲裁（全仓唯一策略）。
 *
 * <p><b>背景</b>：一个 dot 名在同一个 VM 里可能有多份 {@code Class} 对象——宿主加载器
 * （Fabric Knot / Forge ModLauncher / NeoForge TransformingClassLoader）加载的<b>权威副本</b>，
 * 以及 Agent/System（{@code AppClassLoader}）因某次 {@code Class.forName} 兜底而提前定义的
 * <b>裸副本</b>。裸副本没有 Mixin 织入、没有加载器访问变换（{@code PackageAccessFixer}/
 * AccessWidener/AccessTransformer），成员仍是 {@code protected}，也没有宿主注入的接口。</p>
 *
 * <p>选错副本的后果（真机已验证）：JBR 增强重定义会报
 * {@code added interface ...} 并最终
 * {@code VerifyError: Bad access to protected data in invokevirtual}
 * （受影响子类在旧副本上验证失败）→ 裸 {@code InternalError} → 半残 VM 崩溃。</p>
 *
 * <p><b>策略</b>（从高到低）：</p>
 * <ol>
 *   <li>抛弃式预测加载器（{@link LayoutPredictor#isEphemeralLoader}）定义的副本永不入选——它没有实例、
 *       不会被初始化，赢了仲裁会导致"热更成功但 @OnReload / InitFix 全静默"；</li>
 *   <li>宿主加载器副本（rank 2）优先于 System/Agent 加载器副本（rank 1），bootstrap（rank 0）最后；</li>
 *   <li>同 rank 时选"子加载器"的那份（宿主加载器通常是 System 的子）；
 *       仍无法判定时保持先到者，保证结果只依赖 Class 对象而不依赖遍历顺序以外的不确定因素。</li>
 * </ol>
 */
public final class ClassCopySelector {

	private ClassCopySelector() {}

	/** bootstrap=0，System/Agent=1，宿主加载器（Knot/ModLauncher/...）=2。 */
	public static int loaderRank(ClassLoader loader) {
		if (loader == null) return 0;
		if (loader == ClassLoader.getSystemClassLoader()) return 1;
		if (loader == ClassCopySelector.class.getClassLoader()) return 1;   // Agent/Core 自带加载器
		return 2;
	}

	public static int loaderRank(Class<?> type) {
		return type == null ? -1 : loaderRank(type.getClassLoader());
	}

	/** 两份同名副本里选权威的一份；null 视为不存在。 */
	public static Class<?> prefer(Class<?> a, Class<?> b) {
		if (a == null) return b;
		if (b == null) return a;
		return isBetter(b, a) ? b : a;
	}

	/**
	 * {@code candidate} 是否应取代 {@code existing}。
	 *
	 * <p>兼容旧的 {@code RemoldAgent.isBetterLoadedClassCandidate} 语义（子加载器优先），
	 * 同时加入 rank 判定与"拒绝预测副本"规则。</p>
	 */
	public static boolean isBetter(Class<?> candidate, Class<?> existing) {
		if (candidate == null) return false;
		if (existing == null) return true;
		if (candidate == existing) return false;
		ClassLoader candidateLoader = candidate.getClassLoader();
		ClassLoader existingLoader  = existing.getClassLoader();
		if (LayoutPredictor.isEphemeralLoader(candidateLoader)) return false;
		if (LayoutPredictor.isEphemeralLoader(existingLoader)) return true;

		int candidateRank = loaderRank(candidateLoader);
		int existingRank  = loaderRank(existingLoader);
		if (candidateRank != existingRank) return candidateRank > existingRank;
		return MountManager.isChildClassLoader(candidateLoader, existingLoader);
	}

	/** 从一批同名副本里选出权威副本；空集合返回 {@code null}。 */
	public static Class<?> pick(Collection<Class<?>> candidates) {
		Class<?> best = null;
		if (candidates == null) return null;
		for (Class<?> candidate : candidates) {
			best = prefer(best, candidate);
		}
		return best;
	}

	/** 从（可能含重复名）的已加载类列表里收集指定 dot 名的全部副本。 */
	public static Class<?> pickNamed(Class<?>[] allLoaded, String dotClassName) {
		if (allLoaded == null || dotClassName == null) return null;
		Class<?> best = null;
		for (Class<?> candidate : allLoaded) {
			if (candidate == null || !dotClassName.equals(candidate.getName())) continue;
			best = prefer(best, candidate);
		}
		return best;
	}

	/** 人类可读的副本清单，用于日志定位"是谁又定义了一份裸副本"。 */
	public static String describe(Collection<Class<?>> copies) {
		if (copies == null || copies.isEmpty()) return "[]";
		List<String> names = copies.stream()
			.map(c -> String.valueOf(c.getClassLoader()))
			.toList();
		return names.toString();
	}
}
