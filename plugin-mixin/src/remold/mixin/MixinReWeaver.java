package remold.mixin;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import remold.RemoldAgent;

/**
 * 运行期 Mixin 重织入器。
 *
 * <p>把「源码级编译产物」重新交给当前生效的 Mixin 织入器处理，得到与首次类加载等价的
 * 「生效字节码」。Remold 在类对齐之后、布局安全门（LayoutGate）与 {@code redefineClasses}
 * 之前调用本类，因此热更后的字节码仍然带有 Mixin 注入的钩子方法、{@code @Unique} 字段与
 * 合成成员，不会在重定义中被抹掉。</p>
 *
 * <p>织入器实例取自 {@link MixinEnvironment#getActiveTransformer()}：Mixin 在构造自己的
 * transformer 时会把它登记为当前环境的 active transformer。取不到实例时（未启用 Mixin、
 * 或 Mixin 与插件不在同一个 ClassLoader 中）本类退化为恒等变换，不抛异常、不改变入参。</p>
 *
 * <p>约定：目标类没有被任何 Mixin 命中时，Mixin 的 {@code transformClassBytes} 原样返回
 * 入参数组；Remold 依据数组同一性判断「未被改动」，因此非目标类不产生额外拷贝。</p>
 */
public final class MixinReWeaver {

	private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static volatile IMixinTransformer transformer;

	private MixinReWeaver() {
	}

	/**
	 * 对源码级字节码执行 Mixin 重织入。
	 *
	 * @param dotClassName   点分隔类名（如 {@code com.example.item.RemoldWandItem}）
	 * @param sourceBytecode 尚未织入的源码级字节码
	 * @return 重织入后的字节码；无 Mixin 命中或织入失败时返回入参本身
	 */
	public static byte[] reweave(String dotClassName, byte[] sourceBytecode) {
		if (dotClassName == null || sourceBytecode == null) {
			return sourceBytecode;
		}
		if (MixinHotSwap.isMixin(dotClassName, sourceBytecode)) {
			if (RemoldAgent.DEBUG) {
				RemoldAgent.log("[Mixin] bridge in: " + dotClassName
					+ " classified as mixin; bypassing re-weave, sourceBytes=" + sourceBytecode.length);
			}
			return sourceBytecode;
		}
		IMixinTransformer weaver = activeTransformer();
		if (weaver == null) {
			if (RemoldAgent.DEBUG) {
				RemoldAgent.log("[Mixin] bridge in: " + dotClassName
					+ " has no active transformer; returning sourceBytes=" + sourceBytecode.length);
			}
			return sourceBytecode;
		}
		if (RemoldAgent.DEBUG) {
			RemoldAgent.log("[Mixin] bridge in: " + dotClassName
				+ ", sourceBytes=" + sourceBytecode.length
				+ ", transformer=" + weaver.getClass().getName());
		}
		try {
			byte[] woven = weaver.transformClassBytes(dotClassName, dotClassName, sourceBytecode);
			if (woven == null) {
				reportOnce(dotClassName, "[Mixin] Bytecode re-weaving returned null; falling back to source bytecode", null);
				return sourceBytecode;
			}
			boolean unchanged = woven == sourceBytecode || java.util.Arrays.equals(woven, sourceBytecode);
			if (!unchanged) {
				// 目标类刚被织入：此刻 Mixin 已登记本次新增的合成内部类（分配了稳定唯一名），
				// 但字节尚未生成。InitFix 会在同一次 redefine 的内部读这些类的 <init> 元数据，
				// 因此必须在这里把字节提前生成并回灌（S7 时序缺口）。
				MixinInnerClassHotSwap.refeedForTarget(weaver, dotClassName);
			}
			if (RemoldAgent.DEBUG) {
				RemoldAgent.log("[Mixin] bridge out: " + dotClassName
					+ ", wovenBytes=" + woven.length
					+ (unchanged ? " (unchanged from source; no mixin applied or already woven)" : " (rewritten)"));
			}
			return unchanged ? sourceBytecode : woven;
		} catch (Throwable t) {
			if (isIllegalClassLoadError(t)) {
				RemoldAgent.info("[Mixin] Skipping bytecode re-weaving for mixin package class: " + dotClassName);
				return sourceBytecode;
			}
			// 织入失败时回退源码字节码：交给 LayoutGate / JVM 拒绝，而不是带着半成品重定义。
			reportOnce(dotClassName, "[Mixin] Bytecode re-weaving failed; falling back to source bytecode", t);
			return sourceBytecode;
		}
	}

	/** 解析并缓存当前生效的 Mixin 织入器；未启用 Mixin 时返回 {@code null}。 */
	static IMixinTransformer activeTransformer() {
		IMixinTransformer cached = transformer;
		if (cached != null) {
			return cached;
		}
		try {
			Object active = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
			if (!(active instanceof IMixinTransformer)) {
				active = MixinEnvironment.getDefaultEnvironment().getActiveTransformer();
			}
			if (active instanceof IMixinTransformer) {
				transformer = (IMixinTransformer) active;
			}
		} catch (Throwable t) {
			reportOnce("<resolver>", "[Mixin] Unable to acquire Mixin transformer; re-weaving disabled for this run", t);
		}
		return transformer;
	}

	private static boolean isIllegalClassLoadError(Throwable t) {
		for (Throwable cur = t; cur != null; cur = cur.getCause()) {
			if ("IllegalClassLoadError".equals(cur.getClass().getSimpleName())) {
				return true;
			}
		}
		return false;
	}

	private static void reportOnce(String key, String message, Throwable t) {
		if (REPORTED.add(key)) {
			if (t == null) {
				RemoldAgent.warn(message + ": " + key);
			} else {
				RemoldAgent.warn(message + ": " + key + "\n" + describeFull(t));
			}
		}
	}

	/**
	 * 把 Throwable 的完整 cause 链与每层栈帧格式化成一行一条的形式。
	 *
	 * <p>排查纪律要求：Mixin 抛出的 {@code MixinTransformerError} 只是外层包装，
	 * 真正的失败原因在 {@code getCause()} 里。只打 {@code toString()} 会让真因
	 * 完全不可见，热更失败现场只能靠猜。这里逐层展开 cause 并打印栈帧，
	 * 让日志本身就能回答失败发生在哪。
	 */
	private static String describeFull(Throwable t) {
		StringBuilder sb = new StringBuilder();
		java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		Throwable cur = t;
		int depth = 0;
		while (cur != null && depth < 12 && seen.add(cur)) {
			sb.append(depth == 0 ? "" : "\n  Caused by: ")
				.append(cur.getClass().getName());
			if (cur.getMessage() != null) {
				sb.append(": ").append(cur.getMessage());
			}
			StackTraceElement[] frames = cur.getStackTrace();
			int limit = Math.min(frames.length, 16);
			for (int i = 0; i < limit; i++) {
				sb.append("\n      at ").append(frames[i]);
			}
			if (frames.length > limit) {
				sb.append("\n      ... ").append(frames.length - limit).append(" more");
			}
			cur = cur.getCause();
			depth++;
		}
		return sb.toString();
	}
}
