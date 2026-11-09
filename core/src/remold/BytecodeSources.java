package remold;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 宿主外字节码来源的登记口（纯 JDK，不依赖任何 Mixin / 加载器类型）。
 *
 * <p><b>为什么 Remold 会读不到类字节</b>：{@code core} 侧的两条字节来源是
 * {@link RemoldAgent#bytecodeCache}（内存里当前生效的、被 Remold 处理过的版本）与
 * ClassLoader 的资源流（磁盘上的 {@code .class}）。字节码织入框架还会在<b>织入期</b>凭空生成
 * 类——Mixin 的 {@code InnerClassGenerator} 就按随机 UUID 生成
 * {@code Target$Anonymous$<uuid>} 并把名字登记进目标类的 {@code nestMembers}。这类字节
 * 磁盘上没有、也不经过 Remold 的 {@code ClassFileTransformer}，于是两条来源皆空，
 * 一切依赖"读得到成员元数据"的判定都会降级：Nest 扫描 {@code complete=false}、
 * 构造器可见性判定返回"无法确定"→ 字段初始化补丁被拒绝（fail-closed）。
 *
 * <p><b>做法</b>：core 只提供登记口与查询顺序，<b>不</b>自己持有按类名的字节表。生成方
 * （{@code plugin-mixin} 的匿名/内部类热更）把"内部名 → 字节"的函数登记进来，core 的
 * 离线层级分析在 {@code bytecodeCache} 之后、ClassLoader 资源流之前查询它。</p>
 *
 * <p><b>查询顺序与失败语义</b>：</p>
 * <ul>
 *   <li>按<b>登记顺序</b>依次询问；任一来源返回非 {@code null} 即采纳（先登记者优先，
 *       后登记者只填空，便于"专用来源在前、兜底来源在后"）。</li>
 *   <li>每个来源先按<b>内部名</b>（{@code a/b/C$1}）询问，未命中再按<b>点分名</b>
 *       （{@code a.b.C$1}）询问一次（两者不同时才多问一次）——两种键法都能命中。</li>
 *   <li>来源自身抛任何 {@code Throwable} 只记一次诊断并跳过，<b>绝不</b>打断调用方；
 *       诊断只发一次是为了避免"每次查类都刷一条"的日志风暴。</li>
 * </ul>
 *
 * <p><b>失败时仍 fail-closed</b>：本类只是"多一条来源"，查不到依旧是查不到，调用方原有的
 * "元数据不可用 → 拒绝"路径保持不变；不会因为登记了一般来源就把未知当成已知。</p>
 *
 * <p>不加缓存：来源的内容可以在一次热更后变化，缓存会静默返回过期字节。真正的复用发生在
 * 调用方（{@code HierarchyTreeOracle} 按分析实例缓存已解析的 {@code ClassNode}）。</p>
 */
public final class BytecodeSources {

	/** 按登记顺序查询的来源；读多写极少，{@link CopyOnWriteArrayList} 足够。 */
	private static final CopyOnWriteArrayList<Function<String, byte[]>> SOURCES =
	 new CopyOnWriteArrayList<>();

	/** 来源故障诊断的一次性闸门（见类注释的失败语义）。 */
	private static final AtomicBoolean DIAGNOSED = new AtomicBoolean();

	private BytecodeSources() {}

	/**
	 * 登记一个字节码来源。
	 *
	 * <p>来源接收类名（内部名优先，未命中时调用方会再用点分名问一次），返回该类的字节；
	 * 没有这个类返回 {@code null}（返回空数组等价于"没有"）。同一个函数实例重复登记是幂等的。
	 *
	 * @param source 来源；{@code null} 时忽略
	 */
	public static void register(Function<String, byte[]> source) {
		if (source == null || SOURCES.contains(source)) return;
		SOURCES.add(source);
	}

	/**
	 * 撤销登记（测试与插件卸载用）。
	 *
	 * @param source 之前登记过的函数实例
	 * @return {@code true} 表示确实移除了一条登记
	 */
	public static boolean unregister(Function<String, byte[]> source) {
		return source != null && SOURCES.remove(source);
	}

	/**
	 * 按登记顺序查询已登记的来源。
	 *
	 * @param internalNameOrDotted 内部名（{@code a/b/C$1}）或点分名（{@code a.b.C$1}）
	 * @return 第一个命中的字节；无命中返回 {@code null}
	 */
	public static byte[] lookup(String internalNameOrDotted) {
		if (internalNameOrDotted == null || internalNameOrDotted.isEmpty()) return null;
		String internal = internalNameOrDotted.replace('.', '/');
		String dotted   = internal.replace('/', '.');
		for (Function<String, byte[]> source : SOURCES) {
			byte[] bytes = ask(source, internal);
			if (bytes != null) return bytes;
			if (!dotted.equals(internal)) {
				bytes = ask(source, dotted);
				if (bytes != null) return bytes;
			}
		}
		return null;
	}

	/** 已登记的来源数量（诊断与测试用）。 */
	public static int size() {
		return SOURCES.size();
	}

	private static byte[] ask(Function<String, byte[]> source, String name) {
		try {
			byte[] bytes = source.apply(name);
			return bytes == null || bytes.length == 0 ? null : bytes;
		} catch (Throwable failure) {
			reportFailure(source, failure);
			return null;
		}
	}

	private static void reportFailure(Function<String, byte[]> source, Throwable failure) {
		if (!DIAGNOSED.compareAndSet(false, true)) return;
		Throwable root = failure;
		while (root.getCause() != null && root.getCause() != root) root = root.getCause();
		String message = root.getMessage();
		try {
			RemoldAgent.warn("BytecodeSources lookup failed for one registered source ("
				+ source.getClass().getName() + "): " + root.getClass().getSimpleName()
				+ (message == null ? "" : ": " + message)
				+ " — the source is skipped; classes it would have provided stay unresolved"
				+ " (callers keep fail-closing). Further source failures are not logged.");
		} catch (Throwable ignored) {
			// 诊断本身不得成为失败路径。
		}
	}
}
