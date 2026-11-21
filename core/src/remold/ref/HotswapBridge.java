package remold.ref;

import sun.misc.Unsafe;
import remold.Reflect;
import org.objectweb.asm.*;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * 补丁类引用的两个 bridge 的合并实现，统一走 {@code invokedynamic} + 单一 bootstrap。
 *
 * <p><b>设计文档与状态索引</b>：
 * 设计文档参见 {@code docs/initfix/03-runtime-driver.md} §1、§2 与 §4；
 * 实现状态参见 {@code docs/status.md} 与 {@code AGENTS.md}。</p>
 *
 * <p><b>用途一（{@link #KIND_PROTECTED}）</b>：宿主访问跨包 {@code protected} 成员时，
 * hidden class 不是宿主子类，直接调用会因 JVMS §5.4.4 receiver check 抛
 * {@code IllegalAccessError}。用宿主特权 Lookup 在此解析出 {@link MethodHandle}，
 * 调用点直接命中。</p>
 *
 * <p><b>用途二（{@link #KIND_CONDITIONAL}）</b>：新增字段的写入（final 与非 final 统一）
 * 走 Unsafe 条件 CAS——仅当字段当前等于该类型默认值（引用 {@code null}、数值 {@code 0}、
 * {@code boolean} {@code false}、{@code char} {@code '\u0000'}）时才写入。
 * CAS 本身即 volatile 语义，原子读-比较-写一次完成，无 TOCTOU 竞态。
 * 这样 redefine 之后、补丁执行之前或期间其他线程给字段赋过值时，补丁不会覆盖；
 * 代价是字段已是默认值以外时跳过（保守方向）。</p>
 *
 * <p><b>方法名分布（对照 {@code jdk.internal.misc.Unsafe} / {@code sun.misc.Unsafe}）</b>：</p>
 * <ul>
 *   <li><b>引用</b>：JDK 8 是 {@code compareAndSwapObject}；JDK 9~11 是 {@code compareAndSetObject}/{@code putObjectVolatile}；
 *       JDK 12+ 通过 JDK-8207146 改为 {@code compareAndSetReference}/{@code putReferenceVolatile}，
 *       同时 {@code compareAndSetObject} 作为 @Deprecated 别名保留；
 *       JDK 23+ 通过 JDK-8327729 移除别名。用 fallback 链分流。</li>
 *   <li><b>boolean/byte/char/short/int/long</b>：JDK 9 引入 VarHandle 时就叫
 *       {@code compareAndSetXxx}。JDK 8 下 {@code sun.misc.Unsafe} 仅包含 {@code compareAndSwapInt/Long}，
 *       无子字 CAS（boolean/byte/char/short 找不到 CAS 时自动降级为 volatile 写）。</li>
 *   <li><b>float/double</b>：<b>不依赖 {@code compareAndSetFloat/Double} 是否存在</b>，
 *       一律用 raw bits 转成 int/long CAS（见 {@link #rawBitsConditional}）。
 *       原因：{@code jdk.internal.misc.Unsafe}（JDK 9+）<b>有</b>
 *       {@code compareAndSetFloat/Double}，而 JDK 8 的 {@code sun.misc.Unsafe}
 *       <b>没有</b>（实测 1.8.0_332：absent；25.0.2：present）。若按名字探测，
 *       同一补丁在 JDK 9+ 是条件 CAS、在 JDK 8 退化成 {@code putFloatVolatile}
 *       无条件写 —— 会覆盖其他线程已写入的值，与本节"保守方向"相反。
 *       统一走 raw bits 后两条 JDK 语义一致，且与 {@code docs/initfix/01-safety-gate.md} §1 T0 的<b>按位判零</b>口径对齐：
 *       {@code -0.0f} 位模式非零，被视为"已有值"而跳过。</li>
 * </ul>
 *
 * <p><b>bsmArgs 布局</b>：{@code [int kind, int opcode, Class owner, Class host]}。
 * <ul>
 *   <li>{@code KIND_PROTECTED}：{@code owner} = 成员声明类，{@code host} = 宿主类。</li>
 *   <li>{@code KIND_CONDITIONAL}：{@code owner} = 宿主类，{@code host} = 宿主类（占位）。
 *       字段类型从 {@code callSiteType} 派生：静态字段在参数 0，实例字段在参数 1。</li>
 * </ul>
 *
 * <p><b>模板预缓存</b>：9 个模板在静态块里一次组合成 {@code (Object, long, V)void}，
 * BSM 每次调用点链接时只做 {@code insertArguments(offset)}（静态字段再加一次
 * {@code insertArguments(base)}）。</p>
 */
@SuppressWarnings("removal")
public final class HotswapBridge {

	/** protected 成员桥接：owner 是声明类，host 是宿主类。 */
	public static final int KIND_PROTECTED = 0;

	/** 新增字段的条件 CAS 写：owner 是宿主类。 */
	public static final int KIND_CONDITIONAL = 1;

	/**
	 * 强制写（{@code @RemoldReinit(mode = OVERWRITE)}，参考 {@code docs/initfix/03-runtime-driver.md} §3 与 §4）：
	 * owner 是宿主类。
	 * <p>与 {@link #KIND_CONDITIONAL} 的唯一差别是<b>不比较旧值</b>：无条件 volatile 写。
	 * 之操作必须经 Unsafe 而不是 {@code putfield}：{@code final} 字段只允许在声明类的
	 * 构造器里被赋值，而补丁是宿主的一个 hidden nestmate class，直接 {@code putfield}
	 * 会在链接期抛 {@code IllegalAccessError}。</p>
	 */
	public static final int KIND_FORCE = 2;

	/**
	 * L1 存量初值重放的条件 CAS 写：仅当字段当前值仍等于<b>旧初值常量</b>时写入
	 * （与 {@link #KIND_CONDITIONAL} 不同，expected 不是类型零值而是调用点携带的常量）。
	 */
	public static final int KIND_CONDITIONAL_EXPECTED = 3;

	/**
	 * 批循环专用的条件 CAS 写：与 {@link #KIND_CONDITIONAL} 同构，但直接返回 {@code boolean}
	 * 写入结果而不逐实例调用 {@code LongAdder}；由生成的批循环在末次通过
	 * {@link #addConditionalStats} 统一汇总。
	 */
	public static final int KIND_CONDITIONAL_BATCH = 4;

	/**
	 * 批循环专用的 L1 重放条件 CAS 写：直接返回 {@code boolean}，由批循环汇总至
	 * {@link #addReplayStats}。
	 */
	public static final int KIND_CONDITIONAL_EXPECTED_BATCH = 5;

	/**
	 * 遮蔽新增字段的条件 CAS 写：expected 不是常量，而是<b>同一实例上祖先字段的当前值</b>。
	 *
	 * <p>DCEVM 重定义会把被遮蔽的继承槽位值复制进子类新增的同名槽位，于是存量实例上该槽位
	 * "已有非默认值"却仍是合法初始状态。此处把比较基准换成祖先字段的运行时读数：
	 * 相等则按新初值回写，其余非默认值仍保守跳过。祖先字段不可证明可读时在链接期失败
	 * （按 {@code BootstrapMethodError} 单字段隔离），不退化成猜测。</p>
	 */
	public static final int KIND_CONDITIONAL_ANCESTOR = 6;

	private static final Unsafe UNSAFE      = Reflect.UNSAFE;
	private static final Lookup IMPL_LOOKUP = Reflect.IMPL_LOOKUP;

	/**
	 * JDK 9+ 的 {@code jdk.internal.misc.Unsafe}（经 IMPL_LOOKUP 取得，无需 --add-exports）。
	 * 它自带 {@code compareAndSetByte/Short/Char/Boolean/Int/Long/Reference}，条件 CAS 语义完整；
	 * 而 JDK 9+ 的 {@code sun.misc.Unsafe} 只剩 {@code compareAndSwapInt/Long/Object}（且无子字 CAS）。
	 * JDK 8 / Android 没有该类，两者为 null，退回 {@code sun.misc.Unsafe} 并对子字做模拟。
	 */
	private static final Class<?> INTERNAL_UNSAFE_CLASS;
	private static final Object   INTERNAL_UNSAFE;

	static {
		Class<?> c = null;
		Object   o = null;
		try {
			c = Class.forName("jdk.internal.misc.Unsafe");
			o = IMPL_LOOKUP.findStatic(c, "getUnsafe", MethodType.methodType(c)).invoke();
		} catch (Throwable ignored) {
			c = null;
			o = null;
		}
		INTERNAL_UNSAFE_CLASS = c;
		INTERNAL_UNSAFE = o;
	}

	private static final boolean BIG_ENDIAN = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN;

	// ==================== 条件 CAS 的写入/跳过统计 ====================

	/**
	 * {@code "owner#field"} -> {@code [written, skipped]}（{@code LongAdder} 抗竞争）。
	 *
	 * <p>存在的理由：条件 CAS 的 boolean 结果原本被丢弃，"字段已被别人写过所以补丁没生效"
	 * 与"补丁生效"在驱动侧完全无法区分 —— 跳过是<b>静默</b>的。计数让 {@code InitFix}
	 * 能在补丁结束后把跳过数汇总进告警。</p>
	 *
	 * <p>用 {@code ConcurrentHashMap} + {@code LongAdder}：写入发生在被补丁线程上
	 * （多线程/多实例），读清零发生在补丁线程。增长在 {@link #drainConditionalStats} 时移除空条目。</p>
	 */
	private static final ConcurrentHashMap<String, LongAdder[]> CONDITIONAL_STATS =
	 new ConcurrentHashMap<>();

	/**
	 * 统计句柄：{@code (LongAdder[], boolean)void}。
	 * 用 {@code filterReturnValue} 接在条件 CAS 之后，把 boolean 送进计数器。
	 */
	private static final MethodHandle RECORD_CONDITIONAL;

	static {
		try {
			RECORD_CONDITIONAL = MethodHandles.lookup().findStatic(
			 HotswapBridge.class, "recordConditional",
			 MethodType.methodType(void.class, LongAdder[].class, boolean.class));
		} catch (Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
	}

	private static void recordConditional(LongAdder[] counters, boolean written) {
		counters[written ? 0 : 1].increment();
	}

	/** 取出（或创建）某字段的计数器；{@code owner#field} 为键。 */
	private static LongAdder[] countersFor(Class<?> owner, String fieldName) {
		return CONDITIONAL_STATS.computeIfAbsent(owner.getName() + "#" + fieldName,
		 k -> new LongAdder[]{new LongAdder(), new LongAdder()});
	}

	/** 批循环结束/中止时一次性累加该字段的写入与跳过次数，避免逐实例递增 {@code LongAdder}。 */
	public static void addConditionalStats(
	 Class<?> owner, String fieldName, long written, long skipped) {
		if (written == 0L && skipped == 0L) return;
		LongAdder[] counters = countersFor(owner, fieldName);
		if (written != 0L) counters[0].add(written);
		if (skipped != 0L) counters[1].add(skipped);
	}

	/**
	 * 测试探针：命中的 {@code "owner#field"} 在<b>链接期</b>强制失败，用于模拟
	 * indy bootstrap 抛 {@code BootstrapMethodError}（例如 {@code findField} 解析失败）。
	 * 生产环境恒为 {@code null}。
	 */
	private static volatile String bootstrapFailureProbe;

	/** 测试/诊断用：设置链接失败探针（{@code "owner.Class#field"}）；传 null 清除。 */
	public static void setBootstrapFailureProbe(String ownerHashField) {
		bootstrapFailureProbe = ownerHashField;
	}

	private static void checkProbe(Class<?> owner, String fieldName)
	 throws NoSuchFieldException {
		String probe = bootstrapFailureProbe;
		if (probe != null && probe.equals(owner.getName() + "#" + fieldName)) {
			throw new NoSuchFieldException("probe: forced link failure for "
			                               + owner.getName() + "." + fieldName);
		}
	}

	/**
	 * 读取并清零全部条件 CAS 统计（补丁结束时调用一次）。
	 * @return {@code "owner#field"} -> {@code [written, skipped]}；无数据时返回空 Map。
	 * 清零后条目被移除：下一轮重新开始计数，不会把历史累计混进本轮告警。
	 */
	public static Map<String, long[]> drainConditionalStats() {
		Map<String, long[]> out = new LinkedHashMap<>();
		for (Map.Entry<String, LongAdder[]> e : CONDITIONAL_STATS.entrySet()) {
			LongAdder[] c = CONDITIONAL_STATS.remove(e.getKey());
			if (c == null) continue;
			long written = c[0].sum();
			long skipped = c[1].sum();
			if (written == 0 && skipped == 0) continue;
			out.put(e.getKey(), new long[]{written, skipped});
		}
		return out;
	}

	// ==================== L1 存量初值重放的条件 CAS 统计 ====================

	/**
	 * {@code "owner#field"} -> {@code [written, skipped]} for {@link #KIND_CONDITIONAL_EXPECTED}.
	 * Kept separate from {@link #CONDITIONAL_STATS}: the skip reason is different
	 * ("value no longer equals the old initializer" vs "value is not the type default").
	 */
	private static final ConcurrentHashMap<String, LongAdder[]> REPLAY_STATS = new ConcurrentHashMap<>();

	private static final MethodHandle RECORD_REPLAY;

	static {
		try {
			RECORD_REPLAY = MethodHandles.lookup().findStatic(
			 HotswapBridge.class, "recordReplay",
			 MethodType.methodType(void.class, LongAdder[].class, boolean.class));
		} catch (Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
	}

	private static void recordReplay(LongAdder[] counters, boolean written) {
		counters[written ? 0 : 1].increment();
	}

	private static LongAdder[] replayCountersFor(Class<?> owner, String fieldName) {
		return REPLAY_STATS.computeIfAbsent(owner.getName() + "#" + fieldName,
		 k -> new LongAdder[]{new LongAdder(), new LongAdder()});
	}

	/** 批循环结束/中止时一次性累加该字段的重放写入与跳过次数。 */
	public static void addReplayStats(
	 Class<?> owner, String fieldName, long written, long skipped) {
		if (written == 0L && skipped == 0L) return;
		LongAdder[] counters = replayCountersFor(owner, fieldName);
		if (written != 0L) counters[0].add(written);
		if (skipped != 0L) counters[1].add(skipped);
	}

	/** Drain replay-write statistics ({@code "owner#field"} -> {@code [written, skipped]}). */
	public static Map<String, long[]> drainReplayStats() {
		Map<String, long[]> out = new LinkedHashMap<>();
		for (Map.Entry<String, LongAdder[]> e : REPLAY_STATS.entrySet()) {
			LongAdder[] c = REPLAY_STATS.remove(e.getKey());
			if (c == null) continue;
			long written = c[0].sum();
			long skipped = c[1].sum();
			if (written == 0 && skipped == 0) continue;
			out.put(e.getKey(), new long[]{written, skipped});
		}
		return out;
	}

	/**
	 * valueClass -> MethodHandle {@code (Object, long, V)boolean}。
	 * <p>键：引用类型统一用 {@link Object Object.class}；基本类型用各自的 {@code Class}。</p>
	 *
	 * <p><b>保留 boolean 返回值</b>：条件 CAS 的成败必须能被上层区分 ——
	 * "字段已被别人写过所以跳过"与"补丁生效"是完全不同的事实，丢弃它就等于
	 * 对用户隐瞒了跳过。<b>唯一例外是 float/double 以外的无 CAS 回退</b>
	 * （JDK 8 的子字类型走模拟、以及任何落到 volatile put 的情形）：
	 * 那些路径没有条件语义可报告，统一返回 {@code true}（"已写入"）。</p>
	 */
	private static final int SLOT_OBJECT = 0;
	private static final int SLOT_BOOLEAN = 1;
	private static final int SLOT_BYTE = 2;
	private static final int SLOT_CHAR = 3;
	private static final int SLOT_SHORT = 4;
	private static final int SLOT_INT = 5;
	private static final int SLOT_LONG = 6;
	private static final int SLOT_FLOAT = 7;
	private static final int SLOT_DOUBLE = 8;
	private static final int SLOT_COUNT = 9;

	private static final AtomicReferenceArray<MethodHandle> CONDITIONAL_PUTTERS =
	 new AtomicReferenceArray<>(SLOT_COUNT);

	/**
	 * valueClass -> MethodHandle {@code (Object, long, V)void}，无条件 volatile 写。
	 * <p>供 {@link #KIND_FORCE} 使用。这些方法在 {@code sun.misc.Unsafe} 与
	 * {@code jdk.internal.misc.Unsafe} 上都存在且命名一致（不存在 JDK 版本差异），
	 * 不需要像条件 CAS 那样维护 fallback 链。</p>
	 */
	private static final AtomicReferenceArray<MethodHandle> FORCE_PUTTERS =
	 new AtomicReferenceArray<>(SLOT_COUNT);

	/**
	 * valueClass -> MethodHandle {@code (Object, long, V, V)boolean}：expected 作为参数传入的
	 * 条件 CAS，供 {@link #KIND_CONDITIONAL_EXPECTED} 使用（L1 存量初值重放）。
	 *
	 * <p>与 {@link #CONDITIONAL_PUTTERS} 的区别只有一处：expected 不再在模板期固定为零值，
	 * 而是链接期由调用点常量绑定。子字类型在 JDK 8 / Android 上复用同一套整字 CAS 模拟；
	 * 默认关闭的场景按首次调用懒加载，不在 {@code <clinit>} 提前构造。</p>
	 */
	private static final AtomicReferenceArray<MethodHandle> EXPECTED_PUTTERS =
	 new AtomicReferenceArray<>(SLOT_COUNT);

	private static final AtomicReferenceArray<MethodHandle> CONDITIONAL_BATCH_LOOPS =
	 new AtomicReferenceArray<>(SLOT_COUNT);

	private static final AtomicReferenceArray<MethodHandle> FORCE_BATCH_LOOPS =
	 new AtomicReferenceArray<>(SLOT_COUNT);

	static {
		try {
			// 启动期仅预建 int 条件 CAS 作为环境兼容探针；其余 25 条管线按槽位懒加载，
			// 且任何构造失败都不写入槽位（不毒化后续链接）。
			CONDITIONAL_PUTTERS.set(SLOT_INT, buildConditionalPutter(SLOT_INT));
		} catch (Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
	}

	/**
	 * 把任意字段值类型折叠进封闭的 9 槽位空间（所有非基本类型统一归一到 {@link #SLOT_OBJECT}），
	 * 避免按用户 {@link Class} 为键缓存句柄而留住热更 {@link ClassLoader}。
	 */
	private static int typeSlot(Class<?> valType) {
		if (!valType.isPrimitive()) return SLOT_OBJECT;
		if (valType == int.class) return SLOT_INT;
		if (valType == boolean.class) return SLOT_BOOLEAN;
		if (valType == long.class) return SLOT_LONG;
		if (valType == float.class) return SLOT_FLOAT;
		if (valType == double.class) return SLOT_DOUBLE;
		if (valType == byte.class) return SLOT_BYTE;
		if (valType == char.class) return SLOT_CHAR;
		if (valType == short.class) return SLOT_SHORT;
		throw new IllegalArgumentException("unsupported field value type: " + valType);
	}

	private static MethodHandle conditionalPutter(Class<?> valType) throws Throwable {
		int slot = typeSlot(valType);
		MethodHandle cached = CONDITIONAL_PUTTERS.get(slot);
		if (cached != null) return cached;
		return cachePutter(CONDITIONAL_PUTTERS, slot, buildConditionalPutter(slot));
	}

	private static MethodHandle forcePutter(Class<?> valType) throws Throwable {
		int slot = typeSlot(valType);
		MethodHandle cached = FORCE_PUTTERS.get(slot);
		if (cached != null) return cached;
		return cachePutter(FORCE_PUTTERS, slot, buildForcePutter(slot));
	}

	private static MethodHandle expectedPutter(Class<?> valType) throws Throwable {
		int slot = typeSlot(valType);
		if (slot == SLOT_OBJECT) {
			throw new IllegalStateException("no expected-CAS putter for " + valType);
		}
		MethodHandle cached = EXPECTED_PUTTERS.get(slot);
		if (cached != null) return cached;
		return cachePutter(EXPECTED_PUTTERS, slot, buildExpectedPutter(slot));
	}

	private static MethodHandle cachePutter(
	 AtomicReferenceArray<MethodHandle> table, int slot, MethodHandle built
	) {
		if (table.compareAndSet(slot, null, built)) return built;
		MethodHandle existing = table.get(slot);
		return existing != null ? existing : built;
	}

	/** 仅测试可见：统计指定 kind 封闭 9 槽表中已构造的 putter 数量。 */
	static int cachedPutterCount(int kind) {
		AtomicReferenceArray<MethodHandle> table = putterTable(kind);
		int count = 0;
		for (int i = 0; i < SLOT_COUNT; i++) {
			if (table.get(i) != null) count++;
		}
		return count;
	}

	/** 仅测试可见：检查指定字段值类型（归一化后）对应的 putter 槽位是否已构造。 */
	static boolean isPutterCached(int kind, Class<?> valType) {
		return putterTable(kind).get(typeSlot(valType)) != null;
	}

	private static AtomicReferenceArray<MethodHandle> putterTable(int kind) {
		return switch (kind) {
			case KIND_CONDITIONAL, KIND_CONDITIONAL_BATCH -> CONDITIONAL_PUTTERS;
			case KIND_FORCE -> FORCE_PUTTERS;
			case KIND_CONDITIONAL_EXPECTED, KIND_CONDITIONAL_EXPECTED_BATCH -> EXPECTED_PUTTERS;
			default -> throw new IllegalArgumentException("unknown putter kind: " + kind);
		};
	}

	/**
	 * 组合无条件写句柄：依次尝试 {@code names} 中第一个存在的方法，得到
	 * {@code (Object, long, V)void}。都不存在则抛异常（懒加载期会暴露给 bootstrap 并避免写入缓存槽位）。
	 */
	private static MethodHandle volatilePutter(String[] names, Class<?> valueClass)
	 throws NoSuchMethodException {
		MethodType type = MethodType.methodType(void.class, Object.class, long.class, valueClass);
		for (String name : names) {
			MethodHandle put = tryFind(name, type);
			if (put != null) return put;
		}
		throw new NoSuchMethodException(
		 "no volatile put found for " + valueClass + " (tried " + Arrays.toString(names) + ")");
	}

	private static MethodHandle buildForcePutter(int slot) throws Throwable {
		return switch (slot) {
			case SLOT_OBJECT -> volatilePutter(
			 new String[]{"putReferenceVolatile", "putObjectVolatile"}, Object.class);
			case SLOT_BOOLEAN -> volatilePutter(new String[]{"putBooleanVolatile"}, boolean.class);
			case SLOT_BYTE -> volatilePutter(new String[]{"putByteVolatile"}, byte.class);
			case SLOT_CHAR -> volatilePutter(new String[]{"putCharVolatile"}, char.class);
			case SLOT_SHORT -> volatilePutter(new String[]{"putShortVolatile"}, short.class);
			case SLOT_INT -> volatilePutter(new String[]{"putIntVolatile"}, int.class);
			case SLOT_LONG -> volatilePutter(new String[]{"putLongVolatile"}, long.class);
			case SLOT_FLOAT -> volatilePutter(new String[]{"putFloatVolatile"}, float.class);
			case SLOT_DOUBLE -> volatilePutter(new String[]{"putDoubleVolatile"}, double.class);
			default -> throw new IllegalArgumentException("unexpected slot: " + slot);
		};
	}

	private static MethodHandle buildConditionalPutter(int slot) throws Throwable {
		return switch (slot) {
			// 引用类型：JDK 12+ (Reference) -> JDK 9~11 (Object) -> JDK 8 (compareAndSwapObject)
			case SLOT_OBJECT -> conditional(
			 new String[]{"compareAndSetReference", "compareAndSetObject", "compareAndSwapObject"},
			 new String[]{"putReferenceVolatile", "putObjectVolatile"},
			 Object.class, null);
			// 子字类型：JDK 9+ 内部 Unsafe 原生支持；JDK 8 / Android 用 int CAS 模拟
			case SLOT_BOOLEAN -> conditional(
			 new String[]{"compareAndSetBoolean"}, new String[]{"putBooleanVolatile"},
			 boolean.class, false);
			case SLOT_BYTE -> conditional(
			 new String[]{"compareAndSetByte"}, new String[]{"putByteVolatile"},
			 byte.class, (byte) 0);
			case SLOT_CHAR -> conditional(
			 new String[]{"compareAndSetChar"}, new String[]{"putCharVolatile"},
			 char.class, '\u0000');
			case SLOT_SHORT -> conditional(
			 new String[]{"compareAndSetShort"}, new String[]{"putShortVolatile"},
			 short.class, (short) 0);
			// int / long：JDK 9+ 叫 compareAndSetXxx，JDK 8 / sun.misc 叫 compareAndSwapXxx
			case SLOT_INT -> conditional(
			 new String[]{"compareAndSetInt", "compareAndSwapInt"}, new String[]{"putIntVolatile"},
			 int.class, 0);
			case SLOT_LONG -> conditional(
			 new String[]{"compareAndSetLong", "compareAndSwapLong"}, new String[]{"putLongVolatile"},
			 long.class, 0L);
			// float/double：一律用 raw bits 走 int/long CAS，不依赖
			// compareAndSetFloat/Double 是否存在（JDK 8 没有；见类注释）。
			// 这样 JDK 8 与 JDK 9+ 语义一致，且与 T0 的按位判零口径对齐。
			case SLOT_FLOAT -> rawBitsConditional(float.class);
			case SLOT_DOUBLE -> rawBitsConditional(double.class);
			default -> throw new IllegalArgumentException("unexpected slot: " + slot);
		};
	}

	private static MethodHandle buildExpectedPutter(int slot) throws Throwable {
		return switch (slot) {
			case SLOT_BOOLEAN -> expectedSubWordPutter("casBoolean", boolean.class);
			case SLOT_BYTE -> expectedSubWordPutter("casByte", byte.class);
			case SLOT_CHAR -> expectedSubWordPutter("casChar", char.class);
			case SLOT_SHORT -> expectedSubWordPutter("casShort", short.class);
			case SLOT_INT -> expectedCasPutter("Int", int.class);
			case SLOT_LONG -> expectedCasPutter("Long", long.class);
			case SLOT_FLOAT -> rawBitsExpectedPutter(float.class);
			case SLOT_DOUBLE -> rawBitsExpectedPutter(double.class);
			default -> throw new IllegalArgumentException("unexpected expected-CAS slot: " + slot);
		};
	}

	/** {@code (Object, long, int|long, V)boolean}：expected 由调用点绑定。 */
	private static MethodHandle expectedCasPutter(String suffix, Class<?> valueClass)
	 throws NoSuchMethodException {
		MethodType type = MethodType.methodType(
		 boolean.class, Object.class, long.class, valueClass, valueClass);
		MethodHandle cas = tryFind("compareAndSet" + suffix, type);
		if (cas == null) cas = tryFind("compareAndSwap" + suffix, type);
		if (cas == null) {
			throw new NoSuchMethodException("no conditional CAS for " + valueClass.getName());
		}
		return cas;
	}

	/** 子字类型：复用 {@code casBoolean/byte/char/short(Object,long,V,V)} 模拟（expected 不预绑定）。 */
	private static MethodHandle expectedSubWordPutter(String method, Class<?> valueClass)
	 throws NoSuchMethodException, IllegalAccessException {
		return MethodHandles.lookup().findStatic(
		 HotswapBridge.class, method,
		 MethodType.methodType(boolean.class, Object.class, long.class, valueClass, valueClass));
	}

	/** float/double 的 expected-CAS：在 raw bits 上比较，参数与 expected 都先转位模式。 */
	private static MethodHandle rawBitsExpectedPutter(Class<?> valueClass) throws Throwable {
		boolean  isFloat   = valueClass == float.class;
		Class<?> bitsClass = isFloat ? int.class : long.class;
		MethodHandle cas = expectedCasPutter(isFloat ? "Int" : "Long", bitsClass);
		MethodHandle toBits = MethodHandles.lookup().findStatic(
		 isFloat ? Float.class : Double.class,
		 isFloat ? "floatToRawIntBits" : "doubleToRawLongBits",
		 MethodType.methodType(bitsClass, valueClass));
		return MethodHandles.filterArguments(cas, 2, toBits, toBits);
	}

	/**
	 * 把 bootstrap 常量解码成字段类型的自然装箱值。BSM 常量只允许 Integer/Long/Float/Double，
	 * 因此 Z/B/C/S/I 统一以 Integer 传输。
	 */
	private static Object coerceExpected(Object raw, Class<?> valueClass) {
		if (raw == null) throw new IllegalArgumentException("missing expected value");
		if (!(raw instanceof Number number)) {
			throw new IllegalArgumentException("expected value must be numeric, got "
			                                   + raw.getClass().getName());
		}
		if (valueClass == boolean.class) return number.intValue() != 0;
		if (valueClass == byte.class) return (byte) number.intValue();
		if (valueClass == char.class) return (char) number.intValue();
		if (valueClass == short.class) return (short) number.intValue();
		if (valueClass == int.class) return number.intValue();
		if (valueClass == long.class) return number.longValue();
		if (valueClass == float.class) return number.floatValue();
		if (valueClass == double.class) return number.doubleValue();
		throw new IllegalArgumentException("unsupported expected-CAS field type: " + valueClass);
	}

	/**
	 * float/double 的条件 CAS：在 raw bits（int/long）上做比较-交换，
	 * 再把句柄签名适配回 {@code (Object, long, V)void}。
	 *
	 * <p>为什么不用 {@code compareAndSetFloat/Double}：JDK 8 的 {@code sun.misc.Unsafe}
	 * 没有这两个方法（实测 1.8.0_332 absent），按名字探测会静默退化成无条件 volatile 写，
	 * 覆盖其他线程已写入的值。raw bits 在所有目标 JDK 上都可用（{@code compareAndSwapInt/Long}
	 * 从 JDK 8 起就有），语义一致。</p>
	 *
	 * <p><b>按位判零</b>：expected 取 {@code floatToRawIntBits(0.0f) == 0} / {@code doubleToRawLongBits(0.0d) == 0L}，
	 * 与 {@code docs/initfix/01-safety-gate.md} §1 T0 的判零口径一致。于是 {@code -0.0f}（位模式 {@code 0x80000000}）与
	 * 各类 NaN 都被视为"字段已有值"，条件 CAS 失败、补丁跳过 —— 这正是保守方向所需。</p>
	 *
	 * <p>句柄组合：{@code casInt(Object,long,int,int)boolean}
	 * --insertArguments(expected=0)--> {@code (Object,long,int)boolean}
	 * --filterArguments(floatToRawIntBits)--> {@code (Object,long,float)boolean}。</p>
	 */
	private static MethodHandle rawBitsConditional(Class<?> valueClass) throws Throwable {
		boolean  isFloat   = valueClass == float.class;
		Class<?> bitsClass = isFloat ? int.class : long.class;

		MethodType casType = MethodType.methodType(
		 boolean.class, Object.class, long.class, bitsClass, bitsClass);
		MethodHandle cas = tryFind("compareAndSet" + (isFloat ? "Int" : "Long"), casType);
		if (cas == null) {
			cas = tryFind("compareAndSwap" + (isFloat ? "Int" : "Long"), casType);
		}
		if (cas == null) {
			throw new NoSuchMethodException(
			 "no " + bitsClass.getName() + " CAS for raw-bits " + valueClass.getName());
		}

		// expected = 零值的 raw bits（两者都是 0）
		cas = MethodHandles.insertArguments(cas, 2, isFloat ? (Object) 0 : (Object) 0L);

		// 把传入的 float/double 参数转成 raw bits
		MethodHandle toBits = MethodHandles.lookup().findStatic(
		 isFloat ? Float.class : Double.class,
		 isFloat ? "floatToRawIntBits" : "doubleToRawLongBits",
		 MethodType.methodType(bitsClass, valueClass));
		return MethodHandles.filterArguments(cas, 2, toBits);
	}

	/**
	 * 组合条件写模板，依次尝试：
	 * <ol>
	 *   <li>{@code casNames} 中第一个存在的真 CAS（先查内部 Unsafe，再查 sun.misc.Unsafe）；
	 *       得到 {@code (Object, long, V)boolean} 的条件 CAS。<b>必须先于 put 回退</b>，
	 *       否则找不到 {@code compareAndSetXxx} 就会误降级成无条件写。</li>
	 *   <li>boolean/byte/char/short：用同字 int CAS 模拟（JDK 8 / Android）。</li>
	 *   <li>{@code putNames} 中第一个存在的 volatile put——无条件写（仅无 CAS 的类型会走到这里）。</li>
	 *   <li>都没有则抛异常，让类初始化失败——"JDK 版本超出预期"应该大声暴露。</li>
	 * </ol>
	 */
	private static MethodHandle conditional(
	 String[] casNames, String[] putNames, Class<?> valueClass, Object expected
	) throws Throwable {
		MethodType casType = MethodType.methodType(
		 boolean.class, Object.class, long.class, valueClass, valueClass);
		for (String casName : casNames) {
			MethodHandle cas = tryFind(casName, casType);
			if (cas != null) {
				// 保留 boolean：调用点要用它统计"因已有值而跳过"
				return MethodHandles.insertArguments(cas, 2, expected);
			}
		}

		MethodHandle emulated = emulatedSubWordCas(valueClass, expected);
		if (emulated != null) return emulated;

		MethodType putType = MethodType.methodType(
		 void.class, Object.class, long.class, valueClass);
		for (String putName : putNames) {
			MethodHandle put = tryFind(putName, putType);
			if (put != null) {
				// 无条件写没有条件语义可报告：包成恒 true 的 (Object,long,V)boolean，
				// 让上层统一按 boolean 处理（此处"写入成功"是事实）。
				return MethodHandles.dropArguments(
				 MethodHandles.insertArguments(
					MethodHandles.constant(boolean.class, true), 0),
				 0, Object.class, long.class, valueClass);
			}
		}
		throw new NoSuchMethodException(
		 "no CAS or volatile put found for " + valueClass
		 + " (cas=" + Arrays.toString(casNames) + ", put=" + Arrays.toString(putNames) + ")");
	}

	/** 返回已绑定 Unsafe 接收者的句柄；先查 JDK 9+ 内部 Unsafe，再查 sun.misc.Unsafe。 */
	private static MethodHandle tryFind(String name, MethodType type) {
		if (INTERNAL_UNSAFE != null) {
			try {
				return IMPL_LOOKUP.findVirtual(INTERNAL_UNSAFE_CLASS, name, type).bindTo(INTERNAL_UNSAFE);
			} catch (NoSuchMethodException | IllegalAccessException ignored) { }
		}
		try {
			return IMPL_LOOKUP.findVirtual(Unsafe.class, name, type).bindTo(UNSAFE);
		} catch (NoSuchMethodException | IllegalAccessException e) {
			return null;
		}
	}

	// ==================== 子字 CAS 模拟（仅 JDK 8 / Android） ====================

	private static MethodHandle emulatedSubWordCas(Class<?> valueClass, Object expected) throws Throwable {
		String name;
		if (valueClass == boolean.class) { name = "casBoolean"; } else if (valueClass == byte.class) {
			name = "casByte";
		} else if (valueClass == char.class) {
			name = "casChar";
		} else if (valueClass == short.class) {
			name = "casShort";
		} else { return null; }
		MethodHandle h = MethodHandles.lookup().findStatic(HotswapBridge.class, name,
		 MethodType.methodType(boolean.class, Object.class, long.class, valueClass, valueClass));
		// 保留 boolean：调用点要统计"因已有值而跳过"
		return MethodHandles.insertArguments(h, 2, expected);
	}

	private static boolean casBoolean(Object o, long off, boolean e, boolean x) {
		return casSubWord(o, off, 1, e ? 1 : 0, x ? 1 : 0);
	}

	private static boolean casByte(Object o, long off, byte e, byte x) {
		return casSubWord(o, off, 1, e, x);
	}

	private static boolean casChar(Object o, long off, char e, char x) {
		return casSubWord(o, off, 2, e, x);
	}

	private static boolean casShort(Object o, long off, short e, short x) {
		return casSubWord(o, off, 2, e, x);
	}

	/**
	 * 在包含该子字的 4 字节对齐字上做 int CAS：读整字 -> 比较目标子字 -> 替换子字位 -> CAS 整字，
	 * 其他字节的并发写入会令 CAS 失败并重试，不会被覆盖。字段偏移必然落在对象体内，
	 * 向下对齐到 4 字节不会越过对象头。
	 */
	private static boolean casSubWord(Object o, long offset, int bytes, int expected, int x) {
		long wordOffset = offset & ~3L;
		int  shift      = (int) (offset & 3L) << 3;
		if (BIG_ENDIAN) shift = 32 - (bytes << 3) - shift;
		int valueMask = (1 << (bytes << 3)) - 1;
		int e         = expected & valueMask;
		int v         = (x & valueMask) << shift;
		int clear     = ~(valueMask << shift);
		for (; ; ) {
			int cur = UNSAFE.getIntVolatile(o, wordOffset);
			if (((cur >>> shift) & valueMask) != e) return false;
			if (UNSAFE.compareAndSwapInt(o, wordOffset, cur, (cur & clear) | v)) return true;
		}
	}

	private HotswapBridge() { }

	public static CallSite bootstrap(
	 Lookup caller,
	 String name,
	 MethodType callSiteType,
	 int kind,
	 int opcode,
	 Class<?> owner,
	 Class<?> host
	) {
		try {
			return switch (kind) {
				case KIND_PROTECTED -> protectedCallSite(caller, name, callSiteType, opcode, owner, host);
				case KIND_CONDITIONAL -> conditionalFieldCallSite(caller, name, callSiteType, opcode, owner, true);
				case KIND_CONDITIONAL_BATCH -> conditionalFieldCallSite(caller, name, callSiteType, opcode, owner, false);
				case KIND_FORCE -> forceFieldCallSite(caller, name, callSiteType, opcode, owner);
				default -> throw new IllegalArgumentException("unknown bridge kind: " + kind);
			};
		} catch (BootstrapMethodError bme) {
			throw bme;
		} catch (Throwable t) {
			throw new BootstrapMethodError(
			 "Cannot link bridge for " + owner.getName() + "." + name
			 + " (kind=" + kind + ", opcode=" + opcode + ")", t);
		}
	}

	/**
	 * {@link #KIND_CONDITIONAL_EXPECTED} 的 bootstrap：expected 是调用点携带的<b>旧初值常量</b>，
	 * 只有字段当前值仍等于它才写入。用于 L1 存量初值重放（{@link remold.ref.InitializerReplay}）。
	 *
	 * <p>bsmArgs 布局：{@code [int kind, int opcode, Class owner, Class host, Object expected]}；
	 * expected 只允许 ASM 支持的常量类型（Integer/Long/Float/Double）。</p>
	 */
	public static CallSite bootstrapExpected(
	 Lookup caller,
	 String name,
	 MethodType callSiteType,
	 int kind,
	 int opcode,
	 Class<?> owner,
	 Class<?> host,
	 Object expected
	) {
		try {
			if (kind != KIND_CONDITIONAL_EXPECTED && kind != KIND_CONDITIONAL_EXPECTED_BATCH) {
				throw new IllegalArgumentException("unexpected kind for expected-CAS bootstrap: " + kind);
			}
			return conditionalExpectedCallSite(
			 caller, name, callSiteType, opcode, owner, expected,
			 kind == KIND_CONDITIONAL_EXPECTED);
		} catch (BootstrapMethodError bme) {
			throw bme;
		} catch (Throwable t) {
			throw new BootstrapMethodError(
			 "Cannot link replay bridge for " + owner.getName() + "." + name
			 + " (opcode=" + opcode + ", expected=" + expected + ")", t);
		}
	}

	private static CallSite conditionalExpectedCallSite(
	 Lookup caller, String fieldName, MethodType callSiteType, int opcode,
	 Class<?> owner, Object expected, boolean recordCounters
	) throws Throwable {
		boolean isStatic = opcode == Opcodes.PUTSTATIC;

		Class<?> valType = callSiteType.parameterType(isStatic ? 0 : 1);
		MethodHandle template = expectedPutter(valType);

		Object coerced = coerceExpected(expected, valType);
		MethodHandle writer = MethodHandles.insertArguments(template, 2, coerced);

		Field field = findField(owner, fieldName, Type.getDescriptor(valType));
		// 运行期复核调用点的静态性/可写性：分析期用的是 redefine 之前的字节码，
		// 若字段在这场热更里被改成 static/final，按旧元数据写会破坏字段语义（final 写尤其危险）。
		// 重放通道（L1 / A 档 apply）都只写非 final 字段，因此这里对两者统一 fail-closed。
		checkReplayFieldWritable(field, isStatic);
		long offset = isStatic
		 ? UNSAFE.staticFieldOffset(field)
		 : UNSAFE.objectFieldOffset(field);
		Object base = isStatic ? UNSAFE.staticFieldBase(field) : null;

		writer = MethodHandles.insertArguments(writer, 1, offset);
		if (isStatic) {
			writer = MethodHandles.insertArguments(writer, 0, base);
		}

		if (recordCounters) {
			LongAdder[] counters = replayCountersFor(owner, fieldName);
			writer = MethodHandles.filterReturnValue(writer,
			 MethodHandles.insertArguments(RECORD_REPLAY, 0, (Object) counters));
		}

		return new ConstantCallSite(writer.asType(callSiteType));
	}

	/**
	 * 重放通道（{@link #KIND_CONDITIONAL_EXPECTED}）的运行期字段复核：静态性必须与调用点一致，
	 * 且字段不得是 final。分析期基于 redefine 之前的字节码，若字段在本轮被改成 final/static，
	 * Unsafe 写入会得到一个与字段声明矛盾的终局，因此这里直接让 indy 链接失败（单字段隔离）。
	 */
	private static void checkReplayFieldWritable(Field field, boolean expectedStatic)
	 throws NoSuchFieldException {
		int modifiers = field.getModifiers();
		if (expectedStatic != java.lang.reflect.Modifier.isStatic(modifiers)) {
			throw new NoSuchFieldException("field " + field + " static-ness differs from the"
			                               + " replay call site (expected static=" + expectedStatic + ")");
		}
		if (java.lang.reflect.Modifier.isFinal(modifiers)) {
			throw new NoSuchFieldException("field " + field + " is final; replay channels may not"
			                               + " rewrite final fields");
		}
	}

	// ==================== 遮蔽新增字段的条件 CAS 写 ====================

	/**
	 * {@link #KIND_CONDITIONAL_ANCESTOR} 的 bootstrap：expected 在<b>每次调用时</b>从同一实例的
	 * 祖先字段读出（DCEVM 迁移复制的就是那个值）。
	 *
	 * <p>bsmArgs 布局：{@code [int kind, int opcode, Class owner, Class host, Class ancestorOwner]}，
	 * {@code ancestorOwner} 是分析期用 {@link ClassHierarchyOracle#findInheritedField} 定位到的
	 * 祖先声明类。祖先字段在链接期复核可读性（实例性、非 private、确为祖先），任一不满足即抛
	 * {@code BootstrapMethodError}；上层把它当作单字段链接失败隔离，字段留在待补台账，不会静默写入。</p>
	 */
	public static CallSite bootstrapAncestor(
	 Lookup caller,
	 String name,
	 MethodType callSiteType,
	 int kind,
	 int opcode,
	 Class<?> owner,
	 Class<?> host,
	 Class<?> ancestorOwner
	) {
		try {
			if (kind != KIND_CONDITIONAL_ANCESTOR || opcode != Opcodes.PUTFIELD) {
				throw new IllegalArgumentException(
				 "unexpected ancestor-CAS call site (kind=" + kind + ", opcode=" + opcode + ")");
			}
			return conditionalAncestorCallSite(caller, name, callSiteType, owner, ancestorOwner);
		} catch (BootstrapMethodError bme) {
			throw bme;
		} catch (Throwable t) {
			throw new BootstrapMethodError(
			 "Cannot link ancestor bridge for " + owner.getName() + "." + name
			 + " (ancestor=" + ancestorOwner.getName() + ")", t);
		}
	}

	private static CallSite conditionalAncestorCallSite(
	 Lookup caller, String fieldName, MethodType callSiteType,
	 Class<?> owner, Class<?> ancestorOwner
	) throws Throwable {
		Class<?> valType = callSiteType.parameterType(1);
		String   desc    = Type.getDescriptor(valType);

		if (!ancestorOwner.isAssignableFrom(owner)) {
			throw new NoSuchFieldException(ancestorOwner.getName() + " is not an ancestor of " + owner.getName());
		}
		checkProbe(owner, fieldName);
		Field field = findField(owner, fieldName, desc);
		if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
			throw new NoSuchFieldException("field " + field + " is static; a shadowed added field"
			                               + " must be an instance field");
		}
		long offset = UNSAFE.objectFieldOffset(field);

		Field inherited = findDeclaredField(ancestorOwner, fieldName, desc);
		int   inheritedModifiers = inherited.getModifiers();
		if (java.lang.reflect.Modifier.isStatic(inheritedModifiers)) {
			throw new NoSuchFieldException("inherited field " + inherited + " is static; its value"
			                               + " cannot be compared on the instance");
		}
		if (java.lang.reflect.Modifier.isPrivate(inheritedModifiers)) {
			throw new NoSuchFieldException("inherited field " + inherited + " is private and not readable"
			                               + " from the subclass");
		}

		MethodHandle getter = IMPL_LOOKUP.findGetter(ancestorOwner, fieldName, valType)
		 .asType(MethodType.methodType(valType, Object.class));
		// 引用类型的 CAS 模板参数全是 Object，先适配到字段类型，permuteArguments 才认得出同一组参数。
		MethodHandle expected = ancestorExpectedPutter(valType)
		 .asType(MethodType.methodType(boolean.class, Object.class, long.class, valType, valType));
		MethodHandle bound = MethodHandles.insertArguments(expected, 1, offset);
		MethodHandle reordered = MethodHandles.permuteArguments(bound,
		 MethodType.methodType(boolean.class, valType, Object.class, valType), 1, 0, 2);
		MethodHandle writer = MethodHandles.foldArguments(reordered, getter);

		LongAdder[] counters = countersFor(owner, fieldName);
		writer = MethodHandles.filterReturnValue(writer,
		 MethodHandles.insertArguments(RECORD_CONDITIONAL, 0, (Object) counters));
		return new ConstantCallSite(writer.asType(callSiteType));
	}

	/** 只在 {@code owner} 自身声明里找字段（不向上遍历，保证比较基准就是那条祖先声明）。 */
	private static Field findDeclaredField(Class<?> owner, String name, String desc)
	 throws NoSuchFieldException {
		for (Field field : owner.getDeclaredFields()) {
			if (name.equals(field.getName()) && Type.getDescriptor(field.getType()).equals(desc)) {
				return field;
			}
		}
		throw new NoSuchFieldException(owner.getName() + "." + name + ":" + desc);
	}

	/**
	 * expected 作为参数的 CAS 模板 {@code (Object, long, V, V)boolean}，覆盖引用类型：
	 * 引用字段的期望值是运行期对象，不能像 L1 那样在链接期绑定常量。
	 */
	private static MethodHandle ancestorExpectedPutter(Class<?> valType) throws Throwable {
		if (typeSlot(valType) != SLOT_OBJECT) return expectedPutter(valType);
		MethodType type = MethodType.methodType(
		 boolean.class, Object.class, long.class, Object.class, Object.class);
		MethodHandle cas = tryFind("compareAndSetReference", type);
		if (cas == null) cas = tryFind("compareAndSetObject", type);
		if (cas == null) cas = tryFind("compareAndSwapObject", type);
		if (cas == null) {
			throw new NoSuchMethodException("no reference CAS for a shadowed object field");
		}
		return cas;
	}

	// ==================== 字段条件 CAS 写 ====================

	private static CallSite conditionalFieldCallSite(
	 Lookup caller, String fieldName, MethodType callSiteType, int opcode,
	 Class<?> owner, boolean recordCounters
	) throws Throwable {
		boolean isStatic = opcode == Opcodes.PUTSTATIC;

		// 从 callSiteType 派生字段类型：静态在参数 0，实例在参数 1
		Class<?> valType = callSiteType.parameterType(isStatic ? 0 : 1);
		MethodHandle template = conditionalPutter(valType);

		checkProbe(owner, fieldName);
		Field field = findField(owner, fieldName, Type.getDescriptor(valType));
		long offset = isStatic
		 ? UNSAFE.staticFieldOffset(field)
		 : UNSAFE.objectFieldOffset(field);
		Object base = isStatic ? UNSAFE.staticFieldBase(field) : null;

		MethodHandle writer = MethodHandles.insertArguments(template, 1, offset);
		if (isStatic) {
			writer = MethodHandles.insertArguments(writer, 0, base);
		}

		if (recordCounters) {
			// 接上计数器：把条件 CAS 的 boolean 结果记进 [written, skipped]。
			// 这是"跳过"唯一的出口 —— 不接的话，字段因已有值而未补的事实对用户完全不可见。
			LongAdder[] counters = countersFor(owner, fieldName);
			writer = MethodHandles.filterReturnValue(writer,
			 MethodHandles.insertArguments(RECORD_CONDITIONAL, 0, (Object) counters));
		}

		return new ConstantCallSite(writer.asType(callSiteType));
	}

	// ==================== 字段强制写（@RemoldReinit OVERWRITE） ====================

	/**
	 * {@link #KIND_FORCE} 的调用点：与 {@link #conditionalFieldCallSite} 同构，
	 * 只是换成无条件 volatile 写（因此 final 字段也能写）。
	 */
	private static CallSite forceFieldCallSite(
	 Lookup caller, String fieldName, MethodType callSiteType, int opcode, Class<?> owner
	) throws Throwable {
		boolean isStatic = opcode == Opcodes.PUTSTATIC;

		Class<?> valType = callSiteType.parameterType(isStatic ? 0 : 1);
		MethodHandle template = forcePutter(valType);

		Field field = findField(owner, fieldName, Type.getDescriptor(valType));
		long offset = isStatic
		 ? UNSAFE.staticFieldOffset(field)
		 : UNSAFE.objectFieldOffset(field);
		Object base = isStatic ? UNSAFE.staticFieldBase(field) : null;

		MethodHandle writer = MethodHandles.insertArguments(template, 1, offset);
		if (isStatic) {
			writer = MethodHandles.insertArguments(writer, 0, base);
		}
		return new ConstantCallSite(writer.asType(callSiteType));
	}

	/**
	 * 解析实例字段偏移；供 {@link InitFix} 常驻常量批写通道使用，保持与 indy bootstrap
	 * 相同的失败探测（{@link #checkProbe}）与 {@link BootstrapMethodError} 隔离形态。
	 */
	static long resolveInstanceFieldOffset(Class<?> owner, String fieldName, String fieldDesc) {
		try {
			checkProbe(owner, fieldName);
			Field field = findField(owner, fieldName, fieldDesc);
			return UNSAFE.objectFieldOffset(field);
		} catch (Throwable t) {
			throw new BootstrapMethodError(
			 "Cannot resolve field offset for " + owner.getName() + "." + fieldName + ":" + fieldDesc, t);
		}
	}

	/**
	 * 获取按 {@code (kind, slot)} 缓存在常驻隐藏类中的常量批写循环句柄：
	 * 签名 {@code (Class host, String fieldName, long offset, V val, boolean selfOnly,
	 * Object[] targets, int from, int to, Throwable[] errOut)int}。
	 * 与每轮重定义新建的 {@code $$HotswapPatch} 解耦，预热后永久保持 C2 编译态。
	 */
	static MethodHandle constantBatchLoop(Class<?> valType, boolean forceWrite) throws Throwable {
		int slot = typeSlot(valType);
		AtomicReferenceArray<MethodHandle> table = forceWrite ? FORCE_BATCH_LOOPS : CONDITIONAL_BATCH_LOOPS;
		MethodHandle cached = table.get(slot);
		if (cached != null) return cached;
		return cachePutter(table, slot, buildConstantBatchLoop(slot, forceWrite));
	}

	/** 供生成出的常驻批循环隐藏类在 {@code <clinit>} 绑定 {@code static final} putter 句柄。 */
	static MethodHandle resolveLoopPutter(boolean forceWrite, int slot) throws Throwable {
		return forceWrite ? forcePutter(slotType(slot)) : conditionalPutter(slotType(slot));
	}

	private static Class<?> slotType(int slot) {
		return switch (slot) {
			case SLOT_OBJECT -> Object.class;
			case SLOT_BOOLEAN -> boolean.class;
			case SLOT_BYTE -> byte.class;
			case SLOT_CHAR -> char.class;
			case SLOT_SHORT -> short.class;
			case SLOT_INT -> int.class;
			case SLOT_LONG -> long.class;
			case SLOT_FLOAT -> float.class;
			case SLOT_DOUBLE -> double.class;
			default -> throw new IllegalArgumentException("unexpected slot: " + slot);
		};
	}

	private static Object frameTypeOf(int slot) {
		return switch (slot) {
			case SLOT_OBJECT -> "java/lang/Object";
			case SLOT_LONG -> Opcodes.LONG;
			case SLOT_FLOAT -> Opcodes.FLOAT;
			case SLOT_DOUBLE -> Opcodes.DOUBLE;
			default -> Opcodes.INTEGER;
		};
	}

	private static int varLoadOpcode(int slot) {
		return switch (slot) {
			case SLOT_OBJECT -> Opcodes.ALOAD;
			case SLOT_LONG -> Opcodes.LLOAD;
			case SLOT_FLOAT -> Opcodes.FLOAD;
			case SLOT_DOUBLE -> Opcodes.DLOAD;
			default -> Opcodes.ILOAD;
		};
	}

	private static MethodHandle buildConstantBatchLoop(int slot, boolean forceWrite) throws Throwable {
		Class<?> valType = slotType(slot);
		Type asmValType = Type.getType(valType);
		String valDesc = asmValType.getDescriptor();
		int valSize = asmValType.getSize();
		String bridgeInternal = Type.getInternalName(HotswapBridge.class);
		String selfInternal = bridgeInternal + "$ConstLoop_" + (forceWrite ? "F_" : "C_") + slot;
		String putterSig = "(Ljava/lang/Object;J" + valDesc + ")" + (forceWrite ? "V" : "Z");
		String runSig = "(Ljava/lang/Class;Ljava/lang/String;J" + valDesc
		                + "Z[Ljava/lang/Object;II[Ljava/lang/Throwable;)I";

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
		 selfInternal, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
		 "WRITER", "Ljava/lang/invoke/MethodHandle;", null, null).visitEnd();

		MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		clinit.visitInsn(forceWrite ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
		clinit.visitIntInsn(Opcodes.BIPUSH, slot);
		clinit.visitMethodInsn(Opcodes.INVOKESTATIC, bridgeInternal, "resolveLoopPutter",
		 "(ZI)Ljava/lang/invoke/MethodHandle;", false);
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, selfInternal, "WRITER", "Ljava/lang/invoke/MethodHandle;");
		clinit.visitInsn(Opcodes.RETURN);
		clinit.visitMaxs(0, 0);
		clinit.visitEnd();

		final int localHost     = 0;
		final int localField    = 1;
		final int localOffset   = 2;
		final int localVal      = 4;
		final int localSelfOnly = 4 + valSize;
		final int localTargets  = 5 + valSize;
		final int localFrom     = 6 + valSize;
		final int localTo       = 7 + valSize;
		final int localErrOut   = 8 + valSize;
		final int localIndex    = 9 + valSize;
		final int localWritten  = 10 + valSize;
		final int localSkipped  = 12 + valSize;
		final int localTarget   = forceWrite ? (10 + valSize) : (14 + valSize);

		Object valFrame = frameTypeOf(slot);
		Object[] loopLocals = forceWrite
		 ? new Object[]{
			"java/lang/Class", "java/lang/String", Opcodes.LONG, valFrame,
			Opcodes.INTEGER, "[Ljava/lang/Object;", Opcodes.INTEGER, Opcodes.INTEGER,
			"[Ljava/lang/Throwable;", Opcodes.INTEGER
		 }
		 : new Object[]{
			"java/lang/Class", "java/lang/String", Opcodes.LONG, valFrame,
			Opcodes.INTEGER, "[Ljava/lang/Object;", Opcodes.INTEGER, Opcodes.INTEGER,
			"[Ljava/lang/Throwable;", Opcodes.INTEGER, Opcodes.LONG, Opcodes.LONG
		 };
		Object[] targetLocals = new Object[loopLocals.length + 1];
		System.arraycopy(loopLocals, 0, targetLocals, 0, loopLocals.length);
		targetLocals[loopLocals.length] = "java/lang/Object";
		Object[] catchLocals = new Object[loopLocals.length + 1];
		System.arraycopy(loopLocals, 0, catchLocals, 0, loopLocals.length);
		catchLocals[loopLocals.length] = "java/lang/Throwable";

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", runSig, null, null);
		mv.visitCode();
		Label tryStart     = new Label();
		Label tryEnd       = new Label();
		Label loopCond     = new Label();
		Label loopEnd      = new Label();
		Label notSelfOnly  = new Label();
		Label doWrite      = new Label();
		Label onSkipped    = new Label();
		Label nextIter     = new Label();
		Label catchHandler = new Label();
		mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Throwable");

		mv.visitVarInsn(Opcodes.ILOAD, localFrom);
		mv.visitVarInsn(Opcodes.ISTORE, localIndex);
		if (!forceWrite) {
			mv.visitInsn(Opcodes.LCONST_0);
			mv.visitVarInsn(Opcodes.LSTORE, localWritten);
			mv.visitInsn(Opcodes.LCONST_0);
			mv.visitVarInsn(Opcodes.LSTORE, localSkipped);
		}

		mv.visitLabel(tryStart);
		mv.visitLabel(loopCond);
		mv.visitFrame(Opcodes.F_FULL, loopLocals.length, loopLocals, 0, new Object[0]);
		mv.visitVarInsn(Opcodes.ILOAD, localIndex);
		mv.visitVarInsn(Opcodes.ILOAD, localTo);
		mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);

		mv.visitVarInsn(Opcodes.ALOAD, localTargets);
		mv.visitVarInsn(Opcodes.ILOAD, localIndex);
		mv.visitInsn(Opcodes.AALOAD);
		mv.visitVarInsn(Opcodes.ASTORE, localTarget);
		mv.visitVarInsn(Opcodes.ALOAD, localTarget);
		mv.visitJumpInsn(Opcodes.IFNULL, nextIter);

		mv.visitVarInsn(Opcodes.ILOAD, localSelfOnly);
		mv.visitJumpInsn(Opcodes.IFEQ, notSelfOnly);
		mv.visitVarInsn(Opcodes.ALOAD, localTarget);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
		mv.visitVarInsn(Opcodes.ALOAD, localHost);
		mv.visitJumpInsn(Opcodes.IF_ACMPNE, nextIter);
		mv.visitJumpInsn(Opcodes.GOTO, doWrite);

		mv.visitLabel(notSelfOnly);
		mv.visitFrame(Opcodes.F_FULL, targetLocals.length, targetLocals, 0, new Object[0]);
		mv.visitVarInsn(Opcodes.ALOAD, localHost);
		mv.visitVarInsn(Opcodes.ALOAD, localTarget);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "cast", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
		mv.visitInsn(Opcodes.POP);

		mv.visitLabel(doWrite);
		mv.visitFrame(Opcodes.F_FULL, targetLocals.length, targetLocals, 0, new Object[0]);
		mv.visitFieldInsn(Opcodes.GETSTATIC, selfInternal, "WRITER", "Ljava/lang/invoke/MethodHandle;");
		mv.visitVarInsn(Opcodes.ALOAD, localTarget);
		mv.visitVarInsn(Opcodes.LLOAD, localOffset);
		mv.visitVarInsn(varLoadOpcode(slot), localVal);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", putterSig, false);
		if (!forceWrite) {
			mv.visitJumpInsn(Opcodes.IFEQ, onSkipped);
			mv.visitVarInsn(Opcodes.LLOAD, localWritten);
			mv.visitInsn(Opcodes.LCONST_1);
			mv.visitInsn(Opcodes.LADD);
			mv.visitVarInsn(Opcodes.LSTORE, localWritten);
			mv.visitJumpInsn(Opcodes.GOTO, nextIter);

			mv.visitLabel(onSkipped);
			mv.visitFrame(Opcodes.F_FULL, targetLocals.length, targetLocals, 0, new Object[0]);
			mv.visitVarInsn(Opcodes.LLOAD, localSkipped);
			mv.visitInsn(Opcodes.LCONST_1);
			mv.visitInsn(Opcodes.LADD);
			mv.visitVarInsn(Opcodes.LSTORE, localSkipped);
		}

		mv.visitLabel(nextIter);
		mv.visitFrame(Opcodes.F_FULL, targetLocals.length, targetLocals, 0, new Object[0]);
		mv.visitIincInsn(localIndex, 1);
		mv.visitJumpInsn(Opcodes.GOTO, loopCond);
		mv.visitLabel(tryEnd);

		mv.visitLabel(loopEnd);
		mv.visitFrame(Opcodes.F_FULL, loopLocals.length, loopLocals, 0, new Object[0]);
		if (!forceWrite) {
			Label skipStats = new Label();
			emitBatchStatsFlush(mv, bridgeInternal, localHost, localField, localWritten, localSkipped, skipStats);
			mv.visitLabel(skipStats);
			mv.visitFrame(Opcodes.F_FULL, loopLocals.length, loopLocals, 0, new Object[0]);
		}
		mv.visitInsn(Opcodes.ICONST_M1);
		mv.visitInsn(Opcodes.IRETURN);

		mv.visitLabel(catchHandler);
		mv.visitFrame(Opcodes.F_FULL, loopLocals.length, loopLocals, 1, new Object[]{"java/lang/Throwable"});
		mv.visitVarInsn(Opcodes.ASTORE, localTarget);
		if (!forceWrite) {
			Label skipErrStats = new Label();
			emitBatchStatsFlush(mv, bridgeInternal, localHost, localField, localWritten, localSkipped, skipErrStats);
			mv.visitLabel(skipErrStats);
			mv.visitFrame(Opcodes.F_FULL, catchLocals.length, catchLocals, 0, new Object[0]);
		}
		mv.visitVarInsn(Opcodes.ALOAD, localErrOut);
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitVarInsn(Opcodes.ALOAD, localTarget);
		mv.visitInsn(Opcodes.AASTORE);
		mv.visitVarInsn(Opcodes.ILOAD, localIndex);
		mv.visitInsn(Opcodes.IRETURN);

		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();

		Lookup lookup = Reflect.defineHiddenClass(HotswapBridge.class, cw.toByteArray());
		return lookup.findStatic(lookup.lookupClass(), "run", MethodType.fromMethodDescriptorString(
		 runSig, HotswapBridge.class.getClassLoader()));
	}

	private static void emitBatchStatsFlush(
	 MethodVisitor mv, String bridgeInternal, int localHost, int localField,
	 int localWritten, int localSkipped, Label skipLabel) {
		mv.visitVarInsn(Opcodes.LLOAD, localWritten);
		mv.visitVarInsn(Opcodes.LLOAD, localSkipped);
		mv.visitInsn(Opcodes.LOR);
		mv.visitInsn(Opcodes.LCONST_0);
		mv.visitInsn(Opcodes.LCMP);
		mv.visitJumpInsn(Opcodes.IFEQ, skipLabel);
		mv.visitVarInsn(Opcodes.ALOAD, localHost);
		mv.visitVarInsn(Opcodes.ALOAD, localField);
		mv.visitVarInsn(Opcodes.LLOAD, localWritten);
		mv.visitVarInsn(Opcodes.LLOAD, localSkipped);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, bridgeInternal, "addConditionalStats",
		 "(Ljava/lang/Class;Ljava/lang/String;JJ)V", false);
	}

	/**
	 * 沿 owner -> 父类链逐层 {@code getDeclaredFields()} 查找。
	 * 热更注入的字段就在 owner 上，第一次迭代即命中。
	 */
	private static Field findField(Class<?> owner, String name, String desc)
	 throws NoSuchFieldException {
		for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (name.equals(f.getName()) && Type.getDescriptor(f.getType()).equals(desc)) {
					return f;
				}
			}
		}
		throw new NoSuchFieldException(owner.getName() + "." + name + ":" + desc);
	}

	// ==================== protected 成员桥接 ====================

	private static CallSite protectedCallSite(
	 Lookup caller, String name, MethodType callSiteType, int opcode,
	 Class<?> owner, Class<?> host
	) throws Throwable {
		Lookup hostLookup = Reflect.isAndroid ? caller : IMPL_LOOKUP;

		MethodHandle mh = switch (opcode) {
			case Opcodes.GETFIELD -> hostLookup.findGetter(owner, name, callSiteType.returnType());
			case Opcodes.GETSTATIC -> hostLookup.findStaticGetter(owner, name, callSiteType.returnType());
			case Opcodes.PUTFIELD -> {
				Class<?> t = callSiteType.parameterType(callSiteType.parameterCount() - 1);
				yield hostLookup.findSetter(owner, name, t);
			}
			case Opcodes.PUTSTATIC -> {
				Class<?> t = callSiteType.parameterType(0);
				yield hostLookup.findStaticSetter(owner, name, t);
			}
			case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE ->
			 hostLookup.findVirtual(owner, name, callSiteType.dropParameterTypes(0, 1));
			case Opcodes.INVOKESTATIC -> hostLookup.findStatic(owner, name, callSiteType);
			case Opcodes.INVOKESPECIAL -> hostLookup.findSpecial(owner, name,
			 callSiteType.dropParameterTypes(0, 1), host);
			default -> throw new IllegalArgumentException(
			 "bad opcode for protected bridge: " + opcode);
		};

		return new ConstantCallSite(mh.asType(callSiteType));
	}

	// ==================== 后台预热（消除首次热更冷启动毛刺） ====================

	/** 包内可访问的极简预热宿主，供 {@link #warmUp()} 触发一次完整的 hidden class 定义与高频类型的 indy 链接。 */
	static final class WarmupHost {
		@SuppressWarnings("unused")
		int v;
		@SuppressWarnings("unused")
		Object ref;
	}

	private static final AtomicBoolean WARMED_UP = new AtomicBoolean();

	/**
	 * 幂等预热 {@link HotswapBridge} 最常见类型（{@code int}/{@code Object} 条件 CAS）、
	 * {@link Reflect#defineHiddenClass} 与 {@code invokedynamic} 链接骨架，将首次结构性热更的
	 * 冷启动开销移出关键路径；其余冷门基本类型与 force/expected 通道保持按需懒加载。
	 *
	 * <p>只读写 {@link WarmupHost} 自有字段并只清理这些字段自身的统计条目，
	 * 即使与真实热更并发也不污染业务统计。</p>
	 */
	public static void warmUp() {
		if (!WARMED_UP.compareAndSet(false, true)) return;
		try {
			String hostInternal = Type.getInternalName(WarmupHost.class);
			String hostDesc = Type.getDescriptor(WarmupHost.class);
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
			 hostInternal + "$WarmupPatch", null, "java/lang/Object", null);
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
			 "patch", "(" + hostDesc + ")V", null, null);
			Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, Type.getInternalName(HotswapBridge.class), "bootstrap",
			 "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;IILjava/lang/Class;Ljava/lang/Class;)Ljava/lang/invoke/CallSite;",
			 false);
			Type hostType = Type.getType(WarmupHost.class);
			mv.visitCode();
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitInsn(Opcodes.ICONST_1);
			mv.visitInvokeDynamicInsn("v", "(" + hostDesc + "I)V",
			 bsm, KIND_CONDITIONAL, Opcodes.PUTFIELD, hostType, hostType);
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitLdcInsn("warmup");
			mv.visitInvokeDynamicInsn("ref", "(" + hostDesc + "Ljava/lang/Object;)V",
			 bsm, KIND_CONDITIONAL, Opcodes.PUTFIELD, hostType, hostType);
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(2, 1);
			mv.visitEnd();

			MethodVisitor bmv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
			 "patchBatch", "([Ljava/lang/Object;)V", null, null);
			bmv.visitCode();
			bmv.visitVarInsn(Opcodes.ALOAD, 0);
			bmv.visitInsn(Opcodes.ICONST_0);
			bmv.visitInsn(Opcodes.AALOAD);
			bmv.visitTypeInsn(Opcodes.CHECKCAST, hostInternal);
			bmv.visitInsn(Opcodes.DUP);
			bmv.visitInsn(Opcodes.ICONST_1);
			bmv.visitInvokeDynamicInsn("v", "(" + hostDesc + "I)Z",
			 bsm, KIND_CONDITIONAL_BATCH, Opcodes.PUTFIELD, hostType, hostType);
			bmv.visitInsn(Opcodes.POP);
			bmv.visitLdcInsn("warmup");
			bmv.visitInvokeDynamicInsn("ref", "(" + hostDesc + "Ljava/lang/Object;)Z",
			 bsm, KIND_CONDITIONAL_BATCH, Opcodes.PUTFIELD, hostType, hostType);
			bmv.visitInsn(Opcodes.POP);
			bmv.visitLdcInsn(hostType);
			bmv.visitLdcInsn("v");
			bmv.visitInsn(Opcodes.LCONST_1);
			bmv.visitInsn(Opcodes.LCONST_0);
			bmv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(HotswapBridge.class),
			 "addConditionalStats", "(Ljava/lang/Class;Ljava/lang/String;JJ)V", false);
			bmv.visitInsn(Opcodes.RETURN);
			bmv.visitMaxs(6, 1);
			bmv.visitEnd();
			cw.visitEnd();

			Lookup lookup = Reflect.defineHiddenClass(WarmupHost.class, cw.toByteArray());
			MethodHandle mh = lookup.findStatic(lookup.lookupClass(), "patch",
			 MethodType.methodType(void.class, WarmupHost.class));
			mh.invokeExact(new WarmupHost());
			MethodHandle batchMh = lookup.findStatic(lookup.lookupClass(), "patchBatch",
			 MethodType.methodType(void.class, Object[].class));
			batchMh.invokeExact(new Object[]{new WarmupHost()});

			// 预热常驻常量批写循环（int / Object），以 ~2500 次短调用直接跨过 C1/C2 方法入口阈值，
			// 同时训练 written/skipped、selfOnly=false/true 与 null 跳过分支，消除首次热更的解释器窗口。
			long offV   = resolveInstanceFieldOffset(WarmupHost.class, "v", "I");
			long offRef = resolveInstanceFieldOffset(WarmupHost.class, "ref", "Ljava/lang/Object;");
			MethodHandle intLoop = MethodHandles.insertArguments(
			 constantBatchLoop(int.class, false), 0, WarmupHost.class, "v", offV, 1);
			MethodHandle refLoop = MethodHandles.insertArguments(
			 constantBatchLoop(Object.class, false), 0, WarmupHost.class, "ref", offRef, "warmup");
			Object[] warmBatch = new Object[]{new WarmupHost(), new WarmupHost(), null, new WarmupHost()};
			Throwable[] errOut = new Throwable[1];
			for (int iter = 0; iter < 2500; iter++) {
				boolean selfOnly = (iter & 1) != 0;
				int ignoredInt = (int) intLoop.invokeExact(selfOnly, warmBatch, 0, warmBatch.length, errOut);
				int ignoredRef = (int) refLoop.invokeExact(selfOnly, warmBatch, 0, warmBatch.length, errOut);
				if (iter < 10) {
					WarmupHost w = (WarmupHost) warmBatch[0];
					w.v = 0;
					w.ref = null;
				}
			}
		} catch (Throwable ignored) {
			// Best-effort warmup: cold path still initializes lazily on demand.
		} finally {
			CONDITIONAL_STATS.remove(WarmupHost.class.getName() + "#v");
			CONDITIONAL_STATS.remove(WarmupHost.class.getName() + "#ref");
		}
	}
}