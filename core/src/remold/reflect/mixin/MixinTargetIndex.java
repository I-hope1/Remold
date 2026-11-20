package remold.reflect.mixin;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Mixin 目标类索引：回答"这个类是不是某个 Mixin 的织入目标"。
 *
 * <p><b>为什么要三态而不是布尔</b>：Fabric 的 Knot 在进入我们的 {@code ClassFileLoadHook} <b>之前</b>
 * 就完成织入（字节级证据见 {@code docs/mixin/} 的实测读数）。出生点补丁若把未织入的字节覆盖上去，
 * 会静默冲掉织入（{@code @Overwrite} 这类不新增成员的 Mixin 尤其难察觉）。所以：
 * {@link Lookup#TARGET} 明确拒绝；{@link Lookup#UNKNOWN} 表示"本索引无法判断"，
 * 调用方仍须走自己的启发式，<b>不得</b>把 UNKNOWN 当成 NOT_TARGET。</p>
 *
 * <p><b>数据来源</b>：宿主侧的 Mixin 配置对象（不透明 {@code Object}），只读两个字段：
 * {@code mixinMapping}（{@code Map<target, List<MixinInfo>>}）与 {@code unhandledTargets}
 * （尚未解析出目标的点分类名集合）。两者都用 {@link MixinReflection} 反射读取，core 不依赖 Mixin。</p>
 *
 * <p><b>键形态</b>：{@code mixinMapping} 的键在 Mixin 实现里是<b>点分名</b>，本索引统一归一化成
 * 斜杠内部名后再比对；{@code unhandledTargets} 同样按点分名规范化。两侧都归一化的原因很实际——
 * 上一次同类缺陷就是"登记用点分名、查找用斜杠名"造成的静默全量 miss。</p>
 */
public final class MixinTargetIndex {

	/** 查询结果：三态。 */
	public enum Lookup {
		/** 确认是某个 Mixin 的目标类。 */
		TARGET,
		/** 确认不是：已发布的配置里既无映射也无待解析目标。 */
		NOT_TARGET,
		/** 本索引没有可用配置（尚未发布，或宿主没有 Mixin）：调用方不得据此放行。 */
		UNKNOWN
	}

	private static final Object LOCK = new Object();
	/**
	 * 不可变快照引用：{@link #lookup} 无锁读它，{@link #publish} 在锁内整表换引用。
	 *
	 * <p>用可变集合 + {@code clear()/addAll()} 会出现"半空表瞬间"：读线程恰好在两者之间查到
	 * NOT_TARGET，把真实目标误判成可替换——那是会冲掉上游织入的错误方向。</p>
	 */
	private static volatile Set<String> targets = Set.of();
	private static volatile boolean published;
	private static volatile long stamp;

	private MixinTargetIndex() { }

	/**
	 * 用一批宿主 Mixin 配置刷新索引（整表替换，不是增量合并）。
	 *
	 * <p>整表替换的理由：配置可能被热增删，增量合并会留下"已被删除的目标仍是 TARGET"的幽灵；
	 * 而漏判一个真实目标会让补丁冲掉织入，方向比多拒一次危险得多。</p>
	 */
	public static void publish(Iterable<?> configs) {
		Set<String> scratch = new HashSet<>();
		int seen = 0;
		if (configs != null) {
			for (Object config : configs) {
				if (config == null) continue;
				seen++;
				collectMapping(config, scratch);
				collectUnhandled(config, scratch);
			}
		}
		Set<String> snapshot = Set.copyOf(scratch);
		synchronized (LOCK) {
			// 整表换引用（原子可见），不清空再填充。
			targets = snapshot;
			published = seen > 0;
			stamp++;
		}
	}

	private static void collectMapping(Object config, Set<String> out) {
		Object mapping = MixinReflection.readField(config, "mixinMapping");
		if (!(mapping instanceof Map<?, ?> map)) return;
		for (Object key : map.keySet()) {
			if (key != null) out.add(normalize(String.valueOf(key)));
		}
	}

	private static void collectUnhandled(Object config, Set<String> out) {
		Object unhandled = MixinReflection.readField(config, "unhandledTargets");
		if (!(unhandled instanceof Iterable<?> iterable)) return;
		for (Object target : iterable) {
			if (target != null) out.add(normalize(String.valueOf(target)));
		}
	}

	/**
	 * 查询某个类是否是 Mixin 目标。
	 *
	 * @param internalName 斜杠内部名或点分名（两者都接受，内部统一归一化）
	 */
	public static Lookup lookup(String internalName) {
		if (internalName == null || internalName.isEmpty()) return Lookup.UNKNOWN;
		if (!published) return Lookup.UNKNOWN;
		return targets.contains(normalize(internalName)) ? Lookup.TARGET : Lookup.NOT_TARGET;
	}

	/** 是否已经发布过配置（用于诊断与"宿主没有 Mixin"的判定）。 */
	public static boolean available() {
		return published;
	}

	/** 已收录的目标类数量。 */
	public static int size() {
		return targets.size();
	}

	/** 索引版本：每次 {@link #publish} 递增，便于调用方判断是否需要重算派生结论。 */
	public static long stamp() {
		return stamp;
	}

	/** 测试用：回到"未发布"状态。 */
	public static void reset() {
		synchronized (LOCK) {
			targets = Set.of();
			published = false;
			stamp = 0L;
		}
	}

	static String normalize(String name) {
		return name == null ? null : name.trim().replace('.', '/');
	}
}
