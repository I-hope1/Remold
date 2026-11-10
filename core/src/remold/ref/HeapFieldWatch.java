package remold.ref;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 *
 * <p>分组是多重集运算，三个恒等式由构造保证，但**必须被逐次核对**：一旦哪一个被破坏，说明分母/余数不是同一份读数
 * （分组算错、读的数字来自别的字段、或两次切面读了不同类）。因此每次分组都会额外打一条
 * {@code [HEAPWATCH-IDENTITY]}，把三条等式两侧的实际数字与 {@code ok=} 一起亮出来；不成立时额外 {@code warn}。
 * 恒等式：{@code same+collected=beforeTotal}、{@code same+created=afterTotal}、{@code persisted+vanished=beforeNull}。</p>
 *
 * <h3>值类型直方图</h3>
 * <p>与 {@code nullCount} 并排给出字段取值的<b>运行期类型</b>分布（{@code valueTypes{null=5 java.lang.String=7}}）。
 * 它回答的是 {@code nullCount} 回答不了的问题：字段非 null 时装的到底是不是该装的东西。{@code absent}/{@code unreadable}
 * 的实例没有取值，不进直方图（它们已有独立计数）。桶按计数降序、同数按类型名升序，因此同一份输入永远打印同一行。</p>
 *
 * <h3>第二通道（字段句柄 / getter）</h3>
 * <p>反射读是唯一判据来源，但它自己也会错（缓存、访问器失效、字段被重定义挪位）。每次扫描额外解析一条独立通道并逐实例比对：
 * 优先字段句柄（{@code MethodHandles.Lookup#findGetter} 得到的 {@code MethodHandle}，反射解析以免把 JDK 9+ 专有 API
 * 写进静态类型），可用时再叠加一条按 {@code -Dremold.probe.heapwatch.getter=<无参方法名>} 指定的 getter 通道。比对结果按实例计三档：
 * {@code agree}（所有可用通道与反射一致）、{@code conflict}（任一通道读到不同值，有嫌疑：读数本身不可信）、
 * {@code unavailable}（该实例上没有任何通道读成功）。{@code conflict>0} 时 {@code warn}。
 * 通道每次扫描重新解析、**不跨扫描缓存**：重定义会改变字段偏移，跨切面复用的句柄会读到错位数据。</p>
 */
public final class HeapFieldWatch {

	private static final boolean ENABLED = Boolean.getBoolean("remold.probe.heapwatch");
	private static final String CLASS_NAME = System.getProperty("remold.probe.heapwatch.class",
		"net.minecraft.predicate.entity.LootContextPredicate");
	private static final String FIELD_NAME = System.getProperty("remold.probe.heapwatch.field",
		"combinedCondition");
	/** 可选第二通道：无参 getter 方法名（默认空=只用字段句柄通道）。 */
	private static final String GETTER_NAME = System.getProperty("remold.probe.heapwatch.getter", "");

	private static volatile boolean warned;
	private static volatile int totalSnapshots;
	private static volatile int nullHits;
	/** 解析结果缓存（类身份在进程内稳定；同名多副本由 {@link ClassCopySelector} 选宿主权威副本）。 */
	private static volatile Class<?> resolved;
	private static volatile String resolvedVia;
	/** 上一次切面读数（仅存计数与身份哈希，不持有实例引用），供跨切面余数分组。 */
	private static volatile Snapshot last;
	private static volatile Grouping lastGrouping;
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
				Grouping grouping = group(previous, current);
				lastGrouping = grouping;
				lastDiff = grouping.line();
				RemoldAgent.info(lastDiff);
				RemoldAgent.info(grouping.identityLine());
				if (!grouping.identitiesHold()) {
					RemoldAgent.warn("[HEAPWATCH] grouping identities broken (the counts above do not add up,"
						+ " so neither nullCount nor newlyNull may be read as evidence): " + grouping.identityLine());
				}
				if (grouping.newlyNull > 0) {
					RemoldAgent.warn("[HEAPWATCH] " + grouping.newlyNull
						+ " instance(s) went non-null -> null between phase=" + previous.phase
						+ " and phase=" + current.phase + " on " + current.className + "."
						+ current.fieldName + " (" + lastDiff + ")");
				}
			} else {
				lastDiff = "";
				lastGrouping = null;
			}
			if (current.channelConflict > 0) {
				RemoldAgent.warn("[HEAPWATCH] second channel disagrees with the reflective read on "
					+ current.channelConflict + " of " + current.total + " instance(s) at phase=" + phase
					+ " (" + current.channelText() + "); treat this reading as unreliable"
					+ " until the disagreement is explained.");
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
		/**
		 * 值类型直方图：{@code null} 桶的键是 {@code "null"}，其余是值的运行期类型名。
		 * 按计数降序、同数按类型名升序；{@code absent}/{@code unreadable} 的实例不进入直方图。
		 */
		public final Map<String, Integer> valueTypes;
		/** 第二通道与反射读一致的实例数。 */
		public final int channelAgree;
		/** 第二通道与反射读不一致的实例数（读数本身有嫌疑）。 */
		public final int channelConflict;
		/** 该实例上没有任何第二通道读成功的个数（不构成证据，但也不得算作一致）。 */
		public final int channelUnavailable;
		/** 第一处通道分歧的细节（无分歧时为空串）。 */
		public final String firstConflict;
		private final int[] identities;
		private final int[] nullIdentities;

		Snapshot(String phase, String className, String fieldName,
				 int total, int nullCount, int absent, int unreadable, String firstNull,
				 Map<String, Integer> valueTypes,
				 int channelAgree, int channelConflict, int channelUnavailable, String firstConflict,
				 int[] identities, int[] nullIdentities) {
			this.phase = phase;
			this.className = className;
			this.fieldName = fieldName;
			this.total = total;
			this.nullCount = nullCount;
			this.absent = absent;
			this.unreadable = unreadable;
			this.firstNull = firstNull;
			this.valueTypes = valueTypes;
			this.channelAgree = channelAgree;
			this.channelConflict = channelConflict;
			this.channelUnavailable = channelUnavailable;
			this.firstConflict = firstConflict;
			this.identities = identities;
			this.nullIdentities = nullIdentities;
		}

		/** 值类型直方图的日志形态：{@code valueTypes{null=5 java.lang.String=7}}。 */
		public String histogramText() {
			StringBuilder sb = new StringBuilder("valueTypes{");
			boolean first = true;
			for (Map.Entry<String, Integer> entry : valueTypes.entrySet()) {
				if (!first) {
					sb.append(' ');
				}
				sb.append(entry.getKey()).append('=').append(entry.getValue());
				first = false;
			}
			return sb.append('}').toString();
		}

		/** 第二通道比对结果的日志形态：{@code channels{agree=12 conflict=0 unavailable=0}}。 */
		public String channelText() {
			if (channelAgree + channelConflict + channelUnavailable == 0) {
				return "channels{none}";
			}
			StringBuilder sb = new StringBuilder("channels{agree=").append(channelAgree)
				.append(" conflict=").append(channelConflict)
				.append(" unavailable=").append(channelUnavailable);
			if (!firstConflict.isEmpty()) {
				sb.append(" firstConflict{").append(firstConflict).append('}');
			}
			return sb.append('}').toString();
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
			return sb.append(' ').append(histogramText()).append(' ').append(channelText()).toString();
		}

		@Override
		public String toString() {
			return className + "." + fieldName + " phase=" + phase + " total=" + total
				+ " nullCount=" + nullCount + " absent=" + absent + " unreadable=" + unreadable
				+ " " + histogramText() + " " + channelText();
		}
	}

	/**
	 * 对 {@code target} 的堆实例做一次读数（不写日志、不改全局状态）。供校准夹具在 JVM 内直接取结构化读数；
	 * 生产路径走 {@link #snapshot(String, String)}。
	 */
	public static Snapshot scan(String phase, Class<?> scanned, String fieldName) {
		Class<?> target = currentClassVersion(scanned);
		Object[] instances = LibTool.getInstances(target);
		ChannelSet channels = ChannelSet.resolve(target, fieldName);
		int total = 0;
		int nulls = 0;
		int absent = 0;
		int unreadable = 0;
		int agree = 0;
		int conflict = 0;
		int unavailable = 0;
		String firstNull = "";
		String firstConflict = "";
		Map<String, Integer> histogram = new HashMap<String, Integer>();
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
			if (read.status == READ_OK) {
				String bucket = valueTypeOf(read.value);
				Integer old = histogram.get(bucket);
				histogram.put(bucket, old == null ? 1 : old + 1);
				int outcome = channels.compare(instance, read.value);
				if (outcome == CHANNEL_CONFLICT) {
					conflict++;
					if (firstConflict.isEmpty()) {
						firstConflict = "identity=" + id + " " + channels.detail();
					}
				} else if (outcome == CHANNEL_AGREE) {
					agree++;
				} else {
					unavailable++;
				}
			}
		}
		int[] idsExact = new int[total];
		System.arraycopy(ids, 0, idsExact, 0, total);
		int[] nullsExact = new int[nulls];
		System.arraycopy(nullIds, 0, nullsExact, 0, nulls);
		return new Snapshot(phase, target.getName(), fieldName,
			total, nulls, absent, unreadable, firstNull,
			sortedHistogram(histogram),
			agree, conflict, unavailable, firstConflict, idsExact, nullsExact);
	}

	/**
	 * 把待扫描的 {@code Class} 归一到"当前注册的那一个"。
	 *
	 * <p>JBR/DCEVM 的 {@code redefineClasses} 会把<b>静态字段里</b>持有的 {@code Class} 引用改写成旧版本
	 * "影子类"（同名同加载器，字段元数据是旧的）。本类的 {@code resolved} 正是这样一个静态字段：
	 * 不归一的话，重定义之后的每个切面都在读<b>旧类版本</b>的字段表——{@code nullCount} 会变成假阴性，
	 * 第二通道会因为拿不到新版本成员而全线 {@code unavailable}。按名字 + 加载器重新取一次即可
	 * （{@code Class.forName} 命中已注册的类，不触发初始化）。</p>
	 */
	private static Class<?> currentClassVersion(Class<?> clazz) {
		if (clazz == null || clazz.isPrimitive() || clazz.isArray() || clazz.getName().indexOf('/') >= 0) {
			return clazz;
		}
		try {
			Class<?> canonical = Class.forName(clazz.getName(), false, clazz.getClassLoader());
			if (canonical != clazz) {
				RemoldAgent.warn("[HEAPWATCH] cached Class for " + clazz.getName()
					+ " was a stale shadow written by a redefine; using the current class version."
					+ " Readings through the stale reference describe the OLD field table.");
				resolved = canonical;
			}
			return canonical;
		} catch (Throwable t) {
			return clazz;
		}
	}

	/** 直方图桶的键：{@code null} 单独一桶，其余按值的运行期类型名。 */
	private static String valueTypeOf(Object value) {
		return value == null ? "null" : value.getClass().getName();
	}

	/** 直方图定序：计数降序、同数按类型名升序（同一份输入永远打印同一行）。 */
	private static Map<String, Integer> sortedHistogram(Map<String, Integer> histogram) {
		List<Map.Entry<String, Integer>> entries = new ArrayList<Map.Entry<String, Integer>>(histogram.entrySet());
		Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
			@Override
			public int compare(Map.Entry<String, Integer> left, Map.Entry<String, Integer> right) {
				int byCount = right.getValue().compareTo(left.getValue());
				return byCount != 0 ? byCount : left.getKey().compareTo(right.getKey());
			}
		});
		Map<String, Integer> ordered = new LinkedHashMap<String, Integer>();
		for (Map.Entry<String, Integer> entry : entries) {
			ordered.put(entry.getKey(), entry.getValue());
		}
		return Collections.unmodifiableMap(ordered);
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
	 * <p>身份键是 {@code System.identityHashCode}（多重集计数）：不持有实例引用，也不受业务
	 * {@code equals}/{@code hashCode} 影响。身份哈希碰撞时按多重集保守归入 {@code same}，
	 * 因此 {@code created}/{@code collected} 只可能偏小、不会凭空造出"跨切面置空"。</p>
	 *
	 * @return 形如 {@code [HEAPWATCH-DIFF] before=A after=B total{...} null{...}} 的一行
	 */
	public static String diffLine(Snapshot before, Snapshot after) {
		return group(before, after).line();
	}

	/** 最近一次 {@link #snapshot(String, String)} 产出的分组（未拍过或无可比上一份时为 null）。 */
	public static Grouping lastGrouping() {
		return lastGrouping;
	}

	/**
	 * 按实例身份把两份读数分组（纯计数，不持有实例引用）。
	 *
	 * <p>分组是多重集运算，三个恒等式由构造保证，但仍要能被独立核对——见 {@link Grouping#identitiesHold()}。</p>
	 */
	public static Grouping group(Snapshot before, Snapshot after) {
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
		return new Grouping(before.phase, after.phase,
			before.total, after.total, same, created, collected,
			before.nullCount, after.nullCount, persisted, vanished, newlyNull);
	}

	/**
	 * 一次跨切面分组（不可变）。三个恒等式必须同时对两侧读数成立，否则这组数字不能当证据：
	 * <ul>
	 *   <li>{@code same + collected == beforeTotal}（上一份读数里每个实例要么还在、要么已回收）；</li>
	 *   <li>{@code same + created == afterTotal}（这一份读数里每个实例要么原来就有、要么新建）；</li>
	 *   <li>{@code persisted + vanished == beforeNull}（上一次的 null 要么还在、要么随实例消失）。</li>
	 * </ul>
	 */
	public static final class Grouping {
		public final String beforePhase;
		public final String afterPhase;
		public final int beforeTotal;
		public final int afterTotal;
		public final int same;
		public final int created;
		public final int collected;
		public final int beforeNull;
		public final int afterNull;
		public final int persisted;
		public final int vanished;
		public final int newlyNull;

		Grouping(String beforePhase, String afterPhase, int beforeTotal, int afterTotal,
				 int same, int created, int collected,
				 int beforeNull, int afterNull, int persisted, int vanished, int newlyNull) {
			this.beforePhase = beforePhase;
			this.afterPhase = afterPhase;
			this.beforeTotal = beforeTotal;
			this.afterTotal = afterTotal;
			this.same = same;
			this.created = created;
			this.collected = collected;
			this.beforeNull = beforeNull;
			this.afterNull = afterNull;
			this.persisted = persisted;
			this.vanished = vanished;
			this.newlyNull = newlyNull;
		}

		/** {@code same + collected == beforeTotal} */
		public boolean totalBeforeHolds() {
			return same + collected == beforeTotal;
		}

		/** {@code same + created == afterTotal} */
		public boolean totalAfterHolds() {
			return same + created == afterTotal;
		}

		/** {@code persisted + vanished == beforeNull} */
		public boolean nullBeforeHolds() {
			return persisted + vanished == beforeNull;
		}

		/** 三个恒等式是否同时成立。 */
		public boolean identitiesHold() {
			return totalBeforeHolds() && totalAfterHolds() && nullBeforeHolds();
		}

		/** 分组行（与 {@link HeapFieldWatch#diffLine} 同形）。 */
		public String line() {
			return "[HEAPWATCH-DIFF] before=" + beforePhase + " after=" + afterPhase
				+ " total{before=" + beforeTotal + " after=" + afterTotal
				+ " same=" + same + " created=" + created + " collected=" + collected + "}"
				+ " null{before=" + beforeNull + " after=" + afterNull
				+ " persisted=" + persisted + " vanished=" + vanished + " newlyNull=" + newlyNull + "}";
		}

		/** 恒等式核对行：把三条等式两侧的实际数字与判定一起亮出来（{@code ok=} 只看结论）。 */
		public String identityLine() {
			return "[HEAPWATCH-IDENTITY] before=" + beforePhase + " after=" + afterPhase
				+ " same+collected=" + (same + collected) + " beforeTotal=" + beforeTotal
				+ " same+created=" + (same + created) + " afterTotal=" + afterTotal
				+ " persisted+vanished=" + (persisted + vanished) + " beforeNull=" + beforeNull
				+ " ok=" + identitiesHold();
		}
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

	// ------------------------------------------------------------------------------------------------
	// 第二通道（字段句柄 / getter）：与反射读交叉验证
	// ------------------------------------------------------------------------------------------------

	private static final int CHANNEL_UNAVAILABLE = 0;
	private static final int CHANNEL_AGREE = 1;
	private static final int CHANNEL_CONFLICT = 2;

	/** 一条独立于反射的字段读通道。 */
	private interface Channel {
		String name();

		Object read(Object instance) throws Throwable;
	}

	/**
	 * 一次扫描里的通道集合。**每次扫描重新解析、不跨扫描缓存**：重定义会改变字段偏移，
	 * 跨切面复用的字段句柄会按旧偏移读到相邻字段（这正是本哨兵要观测的错读形态）。
	 */
	private static final class ChannelSet {
		private final Channel fieldHandle;
		private final Channel getter;
		private String detail = "";

		private ChannelSet(Channel fieldHandle, Channel getter) {
			this.fieldHandle = fieldHandle;
			this.getter = getter;
		}

		static ChannelSet resolve(Class<?> target, String fieldName) {
			Channel handle = fieldHandleChannel(target, fieldName);
			Channel getter = GETTER_NAME.isEmpty() ? null : getterChannel(target, GETTER_NAME);
			return new ChannelSet(handle, getter);
		}

		/** 该实例上所有可用通道与反射值是否一致：任一通道读失败只是不可用，读到不同值才是分歧。 */
		int compare(Object instance, Object reflected) {
			int combined = CHANNEL_UNAVAILABLE;
			combined = merge(combined, compareOne(fieldHandle, instance, reflected));
			combined = merge(combined, compareOne(getter, instance, reflected));
			return combined;
		}

		/** 第一处分歧的说明（供告警与日志定位）。 */
		String detail() {
			return detail;
		}

		private static int merge(int current, int next) {
			if (current == CHANNEL_CONFLICT || next == CHANNEL_CONFLICT) {
				return CHANNEL_CONFLICT;
			}
			if (current == CHANNEL_AGREE || next == CHANNEL_AGREE) {
				return CHANNEL_AGREE;
			}
			return CHANNEL_UNAVAILABLE;
		}

		private int compareOne(Channel channel, Object instance, Object reflected) {
			if (channel == null) {
				return CHANNEL_UNAVAILABLE;
			}
			try {
				Object value = channel.read(instance);
				if (Objects.equals(value, reflected)) {
					return CHANNEL_AGREE;
				}
				if (detail.isEmpty()) {
					detail = channel.name() + "=" + describe(value) + " vs reflection=" + describe(reflected);
				}
				return CHANNEL_CONFLICT;
			} catch (Throwable t) {
					return CHANNEL_UNAVAILABLE;
			}
		}
	}

	/**
	 * "字段句柄"通道：{@code MethodHandles.Lookup#findGetter} 得到的 {@code MethodHandle}。
	 *
	 * <p><b>为什么不是 {@code VarHandle}</b>：{@code VarHandle} 的访问模式方法（{@code get} 等）是变参多态签名方法，
	 * JDK 明确拒绝反射调用（{@code UnsupportedOperationException: VarHandle access mode methods cannot be invoked
	 * reflectively}），而 {@code MethodHandles.lookup()} 本身也拒绝被反射调用（{@code InternalError: calling lookup()
	 * reflectively is not supported}）——因此"全反射"方案在这条通道上根本走不通。这里改用直接调用：
	 * 它读的是与 {@code VarHandle} 同一类"成员名解析 → 直接读字段槽位"的引擎，对"偏移错位/句柄过期"的交叉校验
	 * 能力等价。整段包在 {@code try/catch(Throwable)} 里，因此在没有该 API 的运行时上只会退化成
	 * {@code channels{none}}（ART 低版本缺 {@code privateLookupIn} 时的失败形态就是链接错误，同样被兜住），
	 * 不影响反射主通道。</p>
	 *
	 * @return 解析失败（无该字段/无 private 访问/运行时没有该 API）时返回 null
	 */
	private static Channel fieldHandleChannel(Class<?> target, String fieldName) {
		try {
			Field field = declaredField(target, fieldName);
			if (field == null) {
				return null;
			}
			java.lang.invoke.MethodHandles.Lookup lookup =
				java.lang.invoke.MethodHandles.privateLookupIn(target, java.lang.invoke.MethodHandles.lookup());
			return new FieldHandleChannel(lookup.findGetter(target, fieldName, field.getType()));
		} catch (Throwable t) {
			return null;
		}
	}

	/** 通过反射解析的无参 getter 通道（对照通道：它读的是方法，不是字段槽位）。 */
	private static Channel getterChannel(Class<?> target, String methodName) {
		for (Class<?> c = target; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Method method = c.getDeclaredMethod(methodName);
				if (method.getParameterTypes().length != 0
					|| java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
					continue;
				}
				method.setAccessible(true);
				return new GetterChannel(method);
			} catch (NoSuchMethodException ignored) {
				// 继续向父类找
			} catch (Throwable t) {
				return null;
			}
		}
		return null;
	}

	private static Field declaredField(Class<?> target, String fieldName) {
		for (Class<?> c = target; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Field field = c.getDeclaredField(fieldName);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
				// 继续向父类找
			} catch (Throwable t) {
				return null;
			}
		}
		return null;
	}

	private static final class FieldHandleChannel implements Channel {
		private final java.lang.invoke.MethodHandle getter;

		FieldHandleChannel(java.lang.invoke.MethodHandle getter) {
			this.getter = getter;
		}

		@Override
		public String name() {
			return "fieldhandle";
		}

		@Override
		public Object read(Object instance) throws Throwable {
			return getter.invokeWithArguments(instance);
		}
	}

	private static final class GetterChannel implements Channel {
		private final Method getter;

		GetterChannel(Method getter) {
			this.getter = getter;
		}

		@Override
		public String name() {
			return "getter";
		}

		@Override
		public Object read(Object instance) throws Throwable {
			try {
				return getter.invoke(instance);
			} catch (java.lang.reflect.InvocationTargetException e) {
				throw e.getCause() == null ? e : e.getCause();
			}
		}
	}
}
