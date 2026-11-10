package remold.reflect.mixin;

import java.util.function.Function;

import remold.BytecodeSources;
import remold.RemoldAgent;

/**
 * 新列出的 Mixin（及其 {@code $N} 内部类）的字节码查找链（纯 JDK + core 内既有 API）。
 *
 * <p><b>为什么需要这条链</b>：运行期把 {@code NewMixin} 追加进 {@code *.mixins.json} 后，
 * {@link MixinConfigDiffer} 需要它的 ASM {@code ClassNode} 才能读 {@code @Mixin} 目标并登记。
 * 宿主插件的 {@code classNodeLoader} 只走 Mixin 自己的 {@code MixinService} 资源流，
 * 而这条流只认「类加载器可见的类路径资源」，于是两类真实场景必然读不到：</p>
 * <ul>
 *   <li><b>时序断层</b>：{@code RemoldAgent} 对同一批变更先发资源（{@code *.mixins.json}）回调，
 *       后跑 class 批次 {@code processChanges}。此刻新 Mixin 的字节还没有进
 *       {@code bytecodeCache}/{@code sourceBytecodeCache}，但它<b>已经落盘</b>在输出目录里。</li>
 *   <li><b>路径断层</b>：IDE 会同时维护多个输出目录（例如 {@code build/classes/java/main} 与
 *       {@code out/production/classes}），宿主资源流只命中其中一条。Remold 的
 *       {@code activeWatchDirs} 是全部监控目录，扫全部并取最新 mtime 才不会"只命中其一"。</li>
 * </ul>
 *
 * <p><b>查找顺序</b>（每一档命中即返回）：</p>
 * <ol>
 *   <li>{@link BytecodeSources#lookup}：插件（含 Mixin 内部类生成器）登记的内存最新字节；</li>
 *   <li>{@link RemoldAgent#findClassBytesOnDisk}：扫<b>全部</b> active watch dirs，取最新 mtime；</li>
 *   <li>{@link RemoldAgent#getSourceBytecode}：上一次批次的源码级字节缓存（磁盘文件已被删时的兜底）。</li>
 * </ol>
 *
 * <p><b>失败语义</b>：每个来源都吞掉任何 {@code Throwable} 并继续问下一档（来源坏掉不得打断调用方，
 * 更不得把"读不到"伪装成"读到了"）；空数组按未命中处理。全部未命中返回 {@code null}，
 * 调用方保持原有的 fail-closed 与指向性诊断（{@code C5}）。</p>
 *
 * <p><b>不加缓存</b>：这些字节在每轮热更后都可能变化，缓存会静默返回过期字节。
 * 真正的复用发生在调用方（一次配置差异只查一遍）。</p>
 */
public final class MixinClassByteSources {

	/** 诊断用：本链覆盖的通道（顺序即查找顺序）。 */
	public static final String CHANNELS = "registered bytecode sources -> watch directories -> last batch source cache";

	/** 本类登记过的"本轮新类字节"（内部名 → 字节）。只回答登记过的名字，绝不遮蔽普通类。 */
	private static final java.util.Map<String, byte[]> NEW_CLASS_BYTES = new java.util.concurrent.ConcurrentHashMap<>();

	private static final java.util.concurrent.atomic.AtomicBoolean NEW_CLASS_SOURCE_INSTALLED =
		new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * 登记进 {@link BytecodeSources} 的来源：只回答本类显式登记过的名字。
	 *
	 * <p>不以"全量来源"的形式注册（不读磁盘、不猜名字）：未登记的名字一律返回 {@code null}，
	 * 于是 core 侧其它查询者看到的仍然是原来的失败语义，不会有"多出一条来源就把未知当已知"。</p>
	 */
	private static final Function<String, byte[]> NEW_CLASS_SOURCE = name -> {
		if (name == null || name.isEmpty()) {
			return null;
		}
		return NEW_CLASS_BYTES.get(name.replace('.', '/'));
	};

	private MixinClassByteSources() {
	}

	/**
	 * 把"本轮新类的字节"同时登记进 {@link BytecodeSources}，让 core 侧的分析（Nest 扫描、
	 * 成员可见性判定、内部类归属）也能看到这个还没被 JVM 加载的类。
	 *
	 * <p>为什么要登记：新列出的 Mixin 及其 {@code $N} 内部类在纯 IDE 编译（只有
	 * {@code out/production/classes}）时尚未进入 ClassLoader，{@code bytecodeCache} 里也没有，
	 * core 的离线层级分析会读到"类不存在"而降级。登记是幂等的（同一个来源只装一次），
	 * 重复登记同名类以最后一次为准（热更后字节会变）。</p>
	 *
	 * @param dotClassName 点分类名（内部名也接受）
	 * @param bytes        源码级字节；{@code null}/空数组时忽略
	 */
	public static void register(String dotClassName, byte[] bytes) {
		if (dotClassName == null || dotClassName.isEmpty() || bytes == null || bytes.length == 0) {
			return;
		}
		NEW_CLASS_BYTES.put(dotClassName.replace('.', '/'), bytes);
		if (NEW_CLASS_SOURCE_INSTALLED.compareAndSet(false, true)) {
			BytecodeSources.register(NEW_CLASS_SOURCE);
		}
	}

	/** 撤销一个登记项（登记失败回滚用；来源本身保留，它只回答仍在表里的名字）。 */
	public static void unregister(String dotClassName) {
		if (dotClassName == null || dotClassName.isEmpty()) {
			return;
		}
		NEW_CLASS_BYTES.remove(dotClassName.replace('.', '/'));
	}

	/** 已登记的内部名快照（诊断用）。 */
	public static java.util.Set<String> registeredNames() {
		return new java.util.LinkedHashSet<>(NEW_CLASS_BYTES.keySet());
	}

	/**
	 * 按默认通道查找类字节。
	 *
	 * @param dotClassName 点分类名（内部名也接受）
	 * @return 命中字节；全部通道都读不到时返回 {@code null}
	 */
	public static byte[] lookup(String dotClassName) {
		return lookup(dotClassName,
			BytecodeSources::lookup,
			RemoldAgent::findClassBytesOnDisk,
			RemoldAgent::getSourceBytecode);
	}

	/**
	 * 可注入的三档查找链（测试用；生产路径见 {@link #lookup(String)}）。
	 *
	 * @param dotClassName     点分类名（内部名也接受）
	 * @param registeredSources 已登记的内存字节来源
	 * @param watchDirectories  监控目录磁盘字节来源
	 * @param lastBatchCache    上次批次的源码字节缓存
	 * @return 第一个命中的字节；全部未命中返回 {@code null}
	 */
	static byte[] lookup(String dotClassName,
			Function<String, byte[]> registeredSources,
			Function<String, byte[]> watchDirectories,
			Function<String, byte[]> lastBatchCache) {
		if (dotClassName == null || dotClassName.isEmpty()) {
			return null;
		}
		String internalName = dotClassName.replace('.', '/');
		byte[] bytes = ask(registeredSources, dotClassName, internalName);
		if (bytes != null) {
			return bytes;
		}
		bytes = ask(watchDirectories, dotClassName, internalName);
		if (bytes != null) {
			return bytes;
		}
		return ask(lastBatchCache, dotClassName, internalName);
	}

	/** 先按调用方给的写法问一次，写法不同时再按内部名问一次；两种键法都能命中。 */
	private static byte[] ask(Function<String, byte[]> source, String dotClassName, String internalName) {
		if (source == null) {
			return null;
		}
		byte[] bytes = query(source, dotClassName);
		if (bytes != null) {
			return bytes;
		}
		return internalName.equals(dotClassName) ? null : query(source, internalName);
	}

	private static byte[] query(Function<String, byte[]> source, String name) {
		try {
			byte[] bytes = source.apply(name);
			return bytes == null || bytes.length == 0 ? null : bytes;
		} catch (Throwable ignored) {
			// 单个来源坏掉只意味着"这一档没有"，继续问下一档；调用方拿不到字节时保持 fail-closed。
			return null;
		}
	}
}
