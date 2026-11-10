package remold.ref;

import java.lang.reflect.Field;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;

import remold.ClassCopySelector;
import remold.RemoldAgent;
import remold.util.LibTool;

/**
 * 堆实例字段哨兵（Heap Watchpoint Probe）：在重定义的关键切面上枚举内存中某一类实例，反射读取指定字段，
 * 统计 {@code null} 数量并打印实例身份，用于定位「某类的字段在重定义/字段初始化补丁窗口内被置为 null」
 * 这类跨类污染。
 *
 * <p>开关（默认关闭，零开销）：</p>
 * <pre>
 * -Dremold.probe.heapwatch=true
 * -Dremold.probe.heapwatch.class=net.minecraft.predicate.entity.LootContextPredicate
 * -Dremold.probe.heapwatch.field=combinedCondition
 * </pre>
 *
 * <p>切面（由调用方传入 {@code phase}）：</p>
 * <ol>
 *   <li>{@code before-redefine}：{@code inst.redefineClasses} 调用前；</li>
 *   <li>{@code after-redefine}：{@code inst.redefineClasses} 返回后、{@code InitFix.afterRedefine}（字段初始化补丁）前；</li>
 *   <li>{@code after-field-patch}：字段初始化补丁完成后。</li>
 * </ol>
 *
 * <p>只读：只做反射读字段与身份打印，不写任何对象。堆遍历失败只 warn 一次，不影响热更主链路。</p>
 *
 * <h3>读数语义（校准契约）</h3>
 * <p>一次切面的读数拆成四个互斥计数，任何时候都满足 {@code total = nullCount + absent + unreadable + 正常非空}：</p>
 * <ul>
 *   <li>{@code total}：本次枚举到的该类实例数（分母，取 {@link LibTool#getInstances} 的精确类语义）；</li>
 *   <li>{@code nullCount}：字段真实读到 {@code null} 的实例数（判据的唯一来源）；</li>
 *   <li>{@code absent}：实例的整条父类链上都没有该字段——**不得**并进 {@code nullCount}，也不得静默当作 0；</li>
 *   <li>{@code unreadable}：字段存在但读失败（{@code IllegalAccess} 等）——同上。</li>
 * </ul>
 * <p>把 {@code absent}/{@code unreadable} 单独亮出来，是为了让"看不到字段"与"看到 0 个 null"在日志上可区分：
 * 前者会让 {@code nullCount=0} 变成假阴性，后者才是真的干净。</p>
 *
 * <h3>跨切面余数分组</h3>
 * <p>相邻两次切面的 {@code total} 常常不同（实例在两次遍历之间被创建/回收）。把差值当成"字段被置空"的证据是错的，
 * 因此第二份切面会自动打一条 {@code [HEAPWATCH-DIFF]}，按实例身份把差值分组为：</p>
 * <pre>
 * total{ before, after, same, created, collected } null{ before, after, persisted, vanished, newlyNull }
 * </pre>
 * <p>{@code created}/{@code collected} 解释分母变化；{@code vanished} 是"上一次为 null、这一次已不在堆里"（无害，实例已回收）；
 * {@code newlyNull} 是唯一有嫌疑的一组——上一次非空、这一次为 null（实例仍在堆里）。只有它才构成跨切面置空证据。</p>
 */
public final class HeapFieldWatch {

	private static final boolean ENABLED = Boolean.getBoolean("remold.probe.heapwatch");
	private static final String CLASS_NAME = System.getProperty("remold.probe.heapwatch.class",
		"net.minecraft.predicate.entity.LootContextPredicate");
	private static final String FIELD_NAME = System.getProperty("remold.probe.heapwatch.field",
		"combinedCondition");

	private static volatile boolean warned;
	private static volatile int totalSnapshots;
	private static volatile int nullHits;
	/** 解析结果缓存（类身份在进程内稳定；同名多副本由 {@link ClassCopySelector} 选宿主权威副本）。 */
	private static volatile Class<?> resolved;
	private static volatile String resolvedVia;
	/** 上一次切面读数（仅存计数与身份哈希，不持有实例引用），供跨切面余数分组。 */
	private static volatile Snapshot last;
	private static volatile String lastDiff = "";

	private HeapFieldWatch() {
	}

	/** 是否开启（供调用方跳过无谓的实参计算）。 */
	public static boolean enabled() {
		return ENABLED;
	}

	/**
	 * 在指定切面拍一次堆快照。
	 *
	 * @param phase          切面名（before-redefine / after-redefine / after-field-patch）
	 * @param redefiningClass 当前正在被重定义的类（点分名，仅用于日志关联）
	 */
	public static void snapshot(String phase, String redefiningClass) {
		if (!ENABLED) {
			return;
		}
		totalSnapshots++;
		try {
			Class<?> target = resolveTarget();
			if (target == null) {
				return;   // resolveTarget 已经 warn 过一次
			}
			Snapshot current = scan(phase, target, FIELD_NAME);
			RemoldAgent.info(current.line(redefiningClass, resolvedVia));
			if (current.absent > 0 || current.unreadable > 0) {
				RemoldAgent.warn("[HEAPWATCH] field cannot be observed on " + current.absent
					+ " instance(s) (absent) and " + current.unreadable + " instance(s) (unreadable) at phase="
					+ phase + "; nullCount=" + current.nullCount
					+ " is NOT evidence that the field is intact; resolve the class/field names first.");
			}
			Snapshot previous = last;
			if (previous != null && previous.className.equals(current.className)
				&& previous.fieldName.equals(current.fieldName)) {
				lastDiff = diffLine(previous, current);
				RemoldAgent.info(lastDiff);
				if (diffNewlyNull(lastDiff) > 0) {
					RemoldAgent.warn("[HEAPWATCH] " + diffNewlyNull(lastDiff)
						+ " instance(s) went non-null -> null between phase=" + previous.phase
						+ " and phase=" + current.phase + " on " + current.className + "."
						+ current.fieldName + " (" + lastDiff + ")");
				}
			} else {
				lastDiff = "";
			}
			last = current;
			nullHits += current.nullCount;
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				RemoldAgent.warn("[HEAPWATCH] disabled after failure: " + t);
			}
		}
	}

	/**
	 * 一次切面的读数（不可变）。持有实例的 {@code identityHashCode} 而不是实例引用：哨兵不得延长实例寿命。
	 */
	public static final class Snapshot {
		public final String phase;
		public final String className;
		public final String fieldName;
		/** 枚举到的实例数（分母）。 */
		public final int total;
		/** 字段真实读到 null 的实例数。 */
		public final int nullCount;
		/** 整条父类链都没有该字段的实例数（不得计入 nullCount）。 */
		public final int absent;
		/** 字段存在但读取失败的实例数（不得计入 nullCount）。 */
		public final int unreadable;
		/** 第一个 null 实例的身份与相邻字段快照（便于人工定位；无 null 时为空串）。 */
		public final String firstNull;
		private final int[] identities;
		private final int[] nullIdentities;

		Snapshot(String phase, String className, String fieldName,
				 int total, int nullCount, int absent, int unreadable, String firstNull,
				 int[] identities, int[] nullIdentities) {
			this.phase = phase;
			this.className = className;
			this.fieldName = fieldName;
			this.total = total;
			this.nullCount = nullCount;
			this.absent = absent;
			this.unreadable = unreadable;
			this.firstNull = firstNull;
			this.identities = identities;
			this.nullIdentities = nullIdentities;
		}

		/** 生产日志行（人读的那条）。 */
		public String line(String redefiningClass, String loader) {
			StringBuilder sb = new StringBuilder("[HEAPWATCH] phase=").append(phase)
				.append(" redefining=").append(redefiningClass)
				.append(" class=").append(className)
				.append(" loader=").append(loader)
				.append(" field=").append(fieldName)
				.append(" total=").append(total)
				.append(" nullCount=").append(nullCount);
			if (absent > 0) {
				sb.append(" absent=").append(absent);
			}
			if (unreadable > 0) {
				sb.append(" unreadable=").append(unreadable);
			}
			if (!firstNull.isEmpty()) {
				sb.append(" firstNull{").append(firstNull).append("}");
			}
			return sb.toString();
		}

		@Override
		public String toString() {
			return className + "." + fieldName + " phase=" + phase + " total=" + total
				+ " nullCount=" + nullCount + " absent=" + absent + " unreadable=" + unreadable;
		}
	}

	/**
	 * 对 {@code target} 的堆实例做一次读数（不写日志、不改全局状态）。供校准夹具在 JVM 内直接取结构化读数；
	 * 生产路径走 {@link #snapshot(String, String)}。
	 */
	public static Snapshot scan(String phase, Class<?> target, String fieldName) {
		Object[] instances = LibTool.getInstances(target);
		int total = 0;
		int nulls = 0;
		int absent = 0;
		int unreadable = 0;
		String firstNull = "";
		int[] ids = new int[instances.length];
		int[] nullIds = new int[instances.length];
		for (Object instance : instances) {
			if (instance == null) {
				continue;
			}
			int id = System.identityHashCode(instance);
			ids[total++] = id;
			Read read = readField(instance, fieldName);
			if (read.status == READ_ABSENT) {
				absent++;
			} else if (read.status == READ_UNREADABLE) {
				unreadable++;
			} else if (read.value == null) {
				if (nulls == 0) {
					firstNull = "identity=" + id + " conditions=" + describe(readField(instance, "conditions").value);
				}
				nullIds[nulls++] = id;
			}
		}
		int[] idsExact = new int[total];
		System.arraycopy(ids, 0, idsExact, 0, total);
		int[] nullsExact = new int[nulls];
		System.arraycopy(nullIds, 0, nullsExact, 0, nulls);
		return new Snapshot(phase, target.getName(), fieldName,
			total, nulls, absent, unreadable, firstNull, idsExact, nullsExact);
	}

	/** 最近一次 {@link #snapshot(String, String)} 的读数（未拍过为 null）。 */
	public static Snapshot lastSnapshot() {
		return last;
	}

	/** 最近一次跨切面余数分组行（无上一份读数时为空串）。 */
	public static String lastDiff() {
		return lastDiff;
	}

	/**
	 * 把两次读数的差异按实例身份分组，解释 {@code total} 与 {@code nullCount} 的变化从哪来。
	 *
	 * @return 形如 {@code [HEAPWATCH-DIFF] before=A after=B total{...} null{...}} 的一行
	 */
	public static String diffLine(Snapshot before, Snapshot after) {
		Map<Integer, Integer> beforeIds = counts(before.identities);
		Map<Integer, Integer> afterIds = counts(after.identities);
		int same = 0;
		int created = 0;
		int collected = 0;
		for (Map.Entry<Integer, Integer> e : afterIds.entrySet()) {
			int inBefore = value(beforeIds, e.getKey());
			int inAfter = e.getValue();
			same += Math.min(inBefore, inAfter);
			created += Math.max(0, inAfter - inBefore);
			collected += Math.max(0, inBefore - inAfter);
		}
		for (Map.Entry<Integer, Integer> e : beforeIds.entrySet()) {
			int inAfter = value(afterIds, e.getKey());
			collected += Math.max(0, e.getValue() - inAfter);
		}
		Map<Integer, Integer> nullBefore = counts(before.nullIdentities);
		Map<Integer, Integer> nullAfter = counts(after.nullIdentities);
		int persisted = 0;
		int vanished = 0;
		int newlyNull = 0;
		for (Map.Entry<Integer, Integer> e : nullAfter.entrySet()) {
			int inBefore = value(nullBefore, e.getKey());
			persisted += Math.min(inBefore, e.getValue());
			newlyNull += Math.max(0, e.getValue() - inBefore);
		}
		for (Map.Entry<Integer, Integer> e : nullBefore.entrySet()) {
			vanished += Math.max(0, e.getValue() - value(nullAfter, e.getKey()));
		}
		return "[HEAPWATCH-DIFF] before=" + before.phase + " after=" + after.phase
			+ " total{before=" + before.total + " after=" + after.total
			+ " same=" + same + " created=" + created + " collected=" + collected + "}"
			+ " null{before=" + before.nullCount + " after=" + after.nullCount
			+ " persisted=" + persisted + " vanished=" + vanished + " newlyNull=" + newlyNull + "}";
	}

	private static Map<Integer, Integer> counts(int[] ids) {
		Map<Integer, Integer> map = new HashMap<Integer, Integer>();
		for (int id : ids) {
			Integer old = map.get(id);
			map.put(id, old == null ? 1 : old + 1);
		}
		return map;
	}

	private static int value(Map<Integer, Integer> map, int key) {
		Integer v = map.get(key);
		return v == null ? 0 : v;
	}

	/** 解析 {@code null{... newlyNull=N ...}} 中的 N（供告警复用，避免再算一遍）。 */
	private static int diffNewlyNull(String line) {
		int at = line.indexOf("newlyNull=");
		if (at < 0) {
			return 0;
		}
		int start = at + "newlyNull=".length();
		int end = start;
		while (end < line.length() && Character.isDigit(line.charAt(end))) {
			end++;
		}
		return end == start ? 0 : Integer.parseInt(line.substring(start, end));
	}

	/** 累计统计（进程结束时打印一次即可）。 */
	public static String summary() {
		return "[HEAPWATCH] snapshots=" + totalSnapshots + " cumulativeNullCount=" + nullHits;
	}

	/**
	 * 解析目标类——**不能用 system classloader**：Knot / ModLauncher 等宿主把游戏类装在自己的
	 * 类加载器里，{@code ClassLoader.getSystemClassLoader()} 根本看不见（表现为 probe 静默失效）。
	 *
	 * <p>解析顺序：</p>
	 * <ol>
	 *   <li>{@code Instrumentation.getAllLoadedClasses()} + {@link ClassCopySelector#pickNamed}
	 *       —— loader 无关，同名多副本时选宿主权威副本；</li>
	 *   <li>线程上下文类加载器（宿主通常会把 TCCL 设为 Knot 的加载器）；</li>
	 *   <li>Agent 自身类加载器（兜底，至少能解析 agent 自己的类）。</li>
	 * </ol>
	 *
	 * <p>全部失败时只 warn 一次并点名已尝试的途径；后续切面直接跳过（不刷屏）。</p>
	 */
	private static Class<?> resolveTarget() {
		Class<?> cached = resolved;
		if (cached != null) {
			return cached;
		}
		synchronized (HeapFieldWatch.class) {
			if (resolved != null) {
				return resolved;
			}
			Instrumentation inst = RemoldAgent.getInstrumentation();
			if (inst != null) {
				try {
					Class<?> picked = ClassCopySelector.pickNamed(inst.getAllLoadedClasses(), CLASS_NAME);
					if (picked != null) {
						resolved = picked;
						resolvedVia = "loadedClasses/" + loaderName(picked);
						return picked;
					}
				} catch (Throwable t) {
					// 继续尝试 TCCL
				}
			}
			ClassLoader tccl = Thread.currentThread().getContextClassLoader();
			Class<?> viaTccl = tryLoad(tccl);
			if (viaTccl != null) {
				resolved = viaTccl;
				resolvedVia = "tccl/" + loaderName(viaTccl);
				return viaTccl;
			}
			Class<?> viaAgent = tryLoad(HeapFieldWatch.class.getClassLoader());
			if (viaAgent != null) {
				resolved = viaAgent;
				resolvedVia = "agent/" + loaderName(viaAgent);
				return viaAgent;
			}
			if (!warned) {
				warned = true;
				RemoldAgent.warn("[HEAPWATCH] cannot resolve " + CLASS_NAME + " through loadedClasses /"
					+ " tccl(" + loaderName(tccl) + ") / agent loader; probe stays idle."
					+ " (system classloader deliberately not used: host loaders such as Knot cannot see"
					+ " game classes there)");
			}
			return null;
		}
	}

	private static Class<?> tryLoad(ClassLoader loader) {
		if (loader == null) {
			return null;
		}
		try {
			return Class.forName(CLASS_NAME, false, loader);
		} catch (Throwable t) {
			return null;
		}
	}

	private static String loaderName(Class<?> type) {
		return type == null ? "null" : loaderName(type.getClassLoader());
	}

	private static String loaderName(ClassLoader loader) {
		if (loader == null) {
			return "bootstrap";
		}
		String name = loader.getClass().getName();
		return name.isEmpty() ? "unnamed" : name;
	}

	private static final int READ_OK = 0;
	private static final int READ_ABSENT = 1;
	private static final int READ_UNREADABLE = 2;

	/** 一次字段读取的结果：{@code value == null && status == READ_OK} 才是真的 null。 */
	private static final class Read {
		final Object value;
		final int status;

		Read(Object value, int status) {
			this.value = value;
			this.status = status;
		}
	}

	private static Read readField(Object instance, String name) {
		for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return new Read(f.get(instance), READ_OK);
			} catch (NoSuchFieldException ignored) {
				// 继续向父类找
			} catch (Throwable t) {
				return new Read(t, READ_UNREADABLE);
			}
		}
		return new Read(null, READ_ABSENT);
	}

	private static String describe(Object value) {
		if (value == null) {
			return "null";
		}
		try {
			String text = String.valueOf(value);
			return text.length() > 200 ? text.substring(0, 200) + "..." : text;
		} catch (Throwable t) {
			return "<unprintable: " + t + ">";
		}
	}
}
