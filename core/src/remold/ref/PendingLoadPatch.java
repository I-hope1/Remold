package remold.ref;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.function.BiFunction;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import remold.vcs.AlignmentMetaStore;

/**
 * 出生点补丁：某类在构建输出里已被改成 v2、但 JVM 还没加载它时，用它第一次加载的那一刻替换字节。
 *
 * <p><b>为什么需要</b>：批次提交时"类尚未加载"的分支只能等 JVM 来取字节；如果加载器给的是旧字节，那次编辑
 * 会静默失效，并且 {@code bytecodeCache} 还会把旧字节记成基线，污染后续 diff（红基线
 * {@code tests/src/remold/BirthPatchTest.java}）。</p>
 *
 * <p><b>三条护栏</b>（规格 {@code docs/vcs/07-load-frontier-and-birth-watermark.md} §5）：</p>
 * <ol>
 *   <li><b>归属 + 身份</b>：类必须属于工作区；待决记录按 {@code (loaderScope, 内部名)} 精确定位，
 *       scope 不匹配一律 fail-closed——名字键会让不同 loader 互相冒名。</li>
 *   <li><b>织入冲突 fail-closed</b>：Fabric 的 Knot 在进入我们的 hook <b>之前</b>就完成了 Mixin 织入，
 *       因此 {@code classfileBuffer} 可能是已织入产物。命中 Mixin 生成成员或合成类形态时<b>不替换</b>，
 *       保留上游结果（判"已织入"就跳过，代价是编辑要等下一次重织入通道，而不是织入丢失）。</li>
 *   <li><b>载荷口径</b>：替换字节必须是 {@code applySourceTransforms(磁盘 Raw v2)}，<b>不是</b>磁盘 Raw 本身
 *       ——后者会丢 {@code PackageAccessFixer}/AccessWidener 的权限提升（已知 112 成员权限退化事故）。</li>
 * </ol>
 *
 * <p>本类只做判定与记账，不碰 JVM：调用方在加载路径上拿到 {@link Decision} 后自行决定是否返回新字节。</p>
 */
public final class PendingLoadPatch {

	/** Mixin 织入生成成员的名字形态（{@code handler$z…$tail} 等），命中即视为已织入。 */
	private static final Pattern MIXIN_MEMBER = Pattern.compile(
	 "^(handler|redirect|wrapOperation|modify|localvar|args|md)\\$[a-z0-9]+\\$.+$");

	/** 合成/内部类的名字形态：这些类即使工作区里有同名产物也不该由本机制替换。 */
	private static final Pattern SYNTHETIC_NAME = Pattern.compile(".*(\\$Anonymous\\$|\\$mixin\\$|\\$\\$).*");

	/**
	 * "加载器未知"的 scope 哨兵。
	 *
	 * <p>批次提交时类还没加载，谁会成为它的定义加载器要等 JVM 决定；此时无法按 scope 精确定位。
	 * 这类待决记录用哨兵登记，出生时<b>先查精确 scope、再退回哨兵</b>。跨 loader 泄漏由出生点的
	 * 工作区归属校验兜住：非工作区类一律拒绝，所以外部类拿不到这条记录。</p>
	 */
	public static final long ANY_SCOPE = Long.MIN_VALUE;

	/** 待决队列上限：类加载路径上的记账必须有界。 */
	public static final int DEFAULT_CAPACITY = 1024;

	/** 判定结果。 */
	public enum Outcome {
		/** 没有待决记录：不干预。 */
		NONE,
		/** 已替换：调用方必须返回 {@link Decision#bytes()}。 */
		APPLIED,
		/** 待决载荷与当前 buffer 相同：不需要替换，但待决记录要消费掉。 */
		EQUAL,
		/** 非工作区类：不替换。 */
		REFUSED_NON_PROJECT,
		/** 疑似已织入：不替换，保留上游结果。 */
		REFUSED_WOVEN,
		/** 磁盘 Raw 取不到或形态无法解释：不替换。 */
		REFUSED_UNRESOLVED_PAYLOAD
	}

	/**
	 * 一次判定的结果。
	 *
	 * @param outcome 判定
	 * @param bytes   仅在 {@link Outcome#APPLIED} 时非空：调用方应返回的生效字节
	 */
	public record Decision(Outcome outcome, byte[] bytes) {
		public static final Decision NONE = new Decision(Outcome.NONE, null);

		public boolean applied() {
			return outcome == Outcome.APPLIED && bytes != null;
		}

		/** 是否"用掉了"待决记录（无论替换与否，判定完成就消费，避免同一类反复尝试）。 */
		public boolean consumed() {
			return outcome != Outcome.NONE;
		}
	}

	/** 待决记录：磁盘 Raw v2 字节（变换在出生时做，见护栏 3）。 */
	private record Pending(long loaderScope, byte[] diskRaw) { }

	private static final Object LOCK = new Object();
	private static final Map<String, Pending> PENDING = new LinkedHashMap<>();
	private static int capacity = DEFAULT_CAPACITY;
	private static long refused;
	private static long applied;

	private PendingLoadPatch() { }

	private static String key(long loaderScope, String internalName) {
		return loaderScope + "\u0000" + internalName;
	}

	/**
	 * 登记一条"加载器未知"的待决补丁（批次提交时类尚未加载的典型形态）。
	 *
	 * @param internalName 内部名
	 * @param diskRaw      磁盘上的 v2 Raw 字节（变换在出生时做）
	 */
	public static void offerAny(String internalName, byte[] diskRaw) {
		offer(ANY_SCOPE, internalName, diskRaw);
	}

	/**
	 * 登记一条待决补丁（批次提交时调用：类已改但尚未加载）。
	 *
	 * @param diskRaw 磁盘上的 v2 Raw 字节；<b>不是</b>变换后字节，变换在出生时做
	 */
	public static void offer(long loaderScope, String internalName, byte[] diskRaw) {
		if (internalName == null || diskRaw == null || diskRaw.length == 0) return;
		synchronized (LOCK) {
			if (PENDING.size() >= capacity && !PENDING.containsKey(key(loaderScope, internalName))) {
				// 超限时丢最旧的一条：丢弃方向是"少一次替换"，保守且可观测。
				String oldest = PENDING.keySet().iterator().next();
				PENDING.remove(oldest);
			}
			PENDING.put(key(loaderScope, internalName),
			 new Pending(loaderScope, Arrays.copyOf(diskRaw, diskRaw.length)));
		}
	}

	/**
	 * 出生时判定是否替换。
	 *
	 * @param loaderScope  当前加载器的 scope 号
	 * @param internalName 内部名（{@code a/b/C}）
	 * @param buffer       加载器送来的当前字节（可能已织入）
	 * @param projectLocated 该类是否属于工作区（{@code ProtectionDomain}/{@code CodeSource} 判定）
	 * @param applySourceTransforms 工作区的 pre-Mixin 变换（{@code RemoldAgent.applySourceTransforms}）
	 */
	public static Decision decide(long loaderScope, String internalName, byte[] buffer,
	                              boolean projectLocated,
	                              BiFunction<String, byte[], byte[]> applySourceTransforms) {
		Pending pending;
		synchronized (LOCK) {
			String exact = key(loaderScope, internalName);
			pending = PENDING.get(exact);
			if (pending != null) {
				PENDING.remove(exact);
			} else {
				String any = key(ANY_SCOPE, internalName);
				pending = PENDING.get(any);
				if (pending != null) PENDING.remove(any);
			}
			if (pending == null) return Decision.NONE;
		}
		Decision d = evaluate(pending, internalName, buffer, projectLocated, applySourceTransforms);
		synchronized (LOCK) {
			if (d.applied()) applied++;
			else if (d.consumed()) refused++;
		}
		return d;
	}

	private static Decision evaluate(Pending pending, String internalName, byte[] buffer,
	                                 boolean projectLocated,
	                                 BiFunction<String, byte[], byte[]> transforms) {
		if (!projectLocated) return new Decision(Outcome.REFUSED_NON_PROJECT, null);
		String dot = internalName.replace('/', '.');
		if (looksWoven(internalName, buffer)) return new Decision(Outcome.REFUSED_WOVEN, null);

		byte[] payload;
		try {
			payload = transforms == null ? pending.diskRaw() : transforms.apply(dot, pending.diskRaw());
		} catch (Throwable t) {
			return new Decision(Outcome.REFUSED_UNRESOLVED_PAYLOAD, null);
		}
		if (payload == null || payload.length == 0) {
			return new Decision(Outcome.REFUSED_UNRESOLVED_PAYLOAD, null);
		}
		if (buffer != null && Arrays.equals(buffer, payload)) {
			return new Decision(Outcome.EQUAL, null);
		}
		return new Decision(Outcome.APPLIED, payload);
	}

	/**
	 * 判断 buffer 是否疑似已被上游织入。
	 *
	 * <p>两个信号：类名形态（合成类）与 Mixin 生成成员名。前者便宜且确定，后者需要扫常量池里的成员名。
	 * <b>反向不成立</b>：没命中不代表未织入（纯 {@code @Overwrite} 不新增成员），所以本判定只用于
	 * "命中即拒绝"，不用于"未命中就放心替换"——后者还需要 {@code MixinConfig} 的目标集合查询口，
	 * 该口子不在本类范围内（见 {@code docs/status.md}）。</p>
	 */
	static boolean looksWoven(String internalName, byte[] buffer) {
		if (internalName != null && SYNTHETIC_NAME.matcher(internalName).matches()) return true;
		if (buffer == null || buffer.length < 10) return false;
		return hasMixinMemberName(buffer);
	}

	private static boolean hasMixinMemberName(byte[] buffer) {
		try {
			ClassReader reader = new ClassReader(buffer);
			boolean[] hit = {false};
			reader.accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public FieldVisitor visitField(int access, String name, String desc,
				                                                 String signature, Object value) {
					if (name != null && MIXIN_MEMBER.matcher(name).matches()) hit[0] = true;
					return null;
				}

				@Override
				public MethodVisitor visitMethod(int access, String name, String desc,
				                                                   String signature, String[] exceptions) {
					if (name != null && MIXIN_MEMBER.matcher(name).matches()) hit[0] = true;
					return null;
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return hit[0];
		} catch (Throwable t) {
			// 读不出来就当作"疑似织入"：拒绝替换的方向是安全的（否则可能覆盖掉不可解析的产物）。
			return true;
		}
	}

	/** 丢弃某类的待决记录（对齐队列已经给出生效字节时调用）。 */
	public static void discard(ClassLoader loader, String internalName) {
		if (internalName == null) return;
		long scope = AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		synchronized (LOCK) {
			PENDING.remove(key(scope, internalName));
		}
	}

	/**
	 * 出生时按 {@link ClassLoader} 判定：scope 号由 {@code AlignmentMetaStore} 统一分配，
	 * 保证"登记"与"消费"用的是同一个 loader 身份（跨 loader 冒名在这里被封死）。
	 */
	public static Decision take(ClassLoader loader, String internalName, byte[] buffer,
	                            boolean projectLocated,
	                            BiFunction<String, byte[], byte[]> transforms) {
		long scope = AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		return decide(scope, internalName, buffer, projectLocated, transforms);
	}

	/** 待决条目数。 */
	public static int pending() {
		synchronized (LOCK) {
			return PENDING.size();
		}
	}

	/** 已替换次数。 */
	public static long appliedCount() {
		synchronized (LOCK) {
			return applied;
		}
	}

	/** 判定完成但未替换的次数（含 fail-closed 与等价短路）。 */
	public static long refusedCount() {
		synchronized (LOCK) {
			return refused;
		}
	}

	/** 测试用：重置全部状态。 */
	public static void reset() {
		synchronized (LOCK) {
			PENDING.clear();
			capacity = DEFAULT_CAPACITY;
			refused = 0L;
			applied = 0L;
		}
	}

	/** 测试用：改容量验证触限丢弃。 */
	static void capacityForTest(int value) {
		synchronized (LOCK) {
			capacity = Math.max(1, value);
		}
	}
}
