package remold.ref;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import remold.RemoldAgent;
import remold.util.LibTool;

/**
 * 真机现象的最小复刻夹具（在**子 JVM** 里跑，父进程是 {@code HeapFieldWatchCalibrationTest}）。
 *
 * <p>真机现象：JBR-21 DCEVM 对「含匿名类的 Mixin」做结构热更时，
 * {@code net.minecraft.predicate.entity.LootContextPredicate} 的 230/244 个实例的
 * {@code combinedCondition}（静态类型 {@code java.util.function.Predicate}）被置为 null。</p>
 *
 * <p>复刻的四个变量（每个变量单独可归因，读数逐项报 {@code total / newlyNull / agree / conflict}）：</p>
 * <ol>
 *   <li>{@code predicate-groups}：同一个类、同一次结构重定义下的两组 {@code Predicate} 字段——
 *       {@code namedCondition} 装的是<b>具名</b>实现类实例，{@code anonCondition} 装的是<b>匿名</b>实现类实例，
 *       每组 {@value #GROUP_SIZE} 个实例、字段全非 null。两组的唯一差别就是"字段值是不是匿名类实例"，
 *       因此它同时是"换无关输入"的对照；被重定义的类自己也声明了匿名类（{@code PredicatePair$1}），
 *       与"Mixin 里含匿名类"同形。</li>
 *   <li>{@code predicate-anon}：{@value #ANON_INSTANCES} 个<b>同时存活</b>的匿名类实例（分属不同匿名类），
 *       各自被一个旁观实例（{@code Bystander.anonRef}）强引用；重定义它们的全部外层类后，
 *       实例必须还活着、引用必须还是非 null。</li>
 *   <li>{@code predicate-g1}：与 {@code predicate-groups} 同体，但子 JVM 用真机同款的
 *       {@code -XX:+UseG1GC -Xmx4020m}（不缩小；真机日志即此量级）启动；夹具自报<b>实际生效</b>的
 *       收集器与堆上限，而不是回显命令行。</li>
 *   <li>{@code predicate-concurrent}：重定义期间 4 条后台线程持续 {@code new} 新实例并<b>穿过字段</b>读
 *       {@code Predicate}（读不到就 NPE），逐次计数读/分配/异常数。</li>
 * </ol>
 *
 * <p><b>读数口径</b>：{@code total} 来自 JVMTI 堆遍历，包含<b>尚未被 GC 回收</b>的不可达对象。因此并发场景的
 * {@code after} 分母会远大于存活实例数（实测 8 万余），这不是泄漏也不是"实例被复制"；跨切面分组会把它记进
 * {@code created}（尚未回收则 {@code collected=0}）。读真机 {@code 230/244} 时同样要注意这一点：
 * 分母是"堆里现有的实例"，不是"业务仍持有的实例"。</p>
 *
 * <p>另有两个通道/分桶场景：</p>
 * <ul>
 *   <li>{@code channel-heapwalk}：验证读数走的是产品通道 {@link LibTool#getInstances}（JVMTI 堆遍历），
 *       不是夹具自己的注册表——{@code LIVE} 注册表里 12 个实例全非 null，另外 7 个只挂在
 *       {@code SIDECAR}（注册表看不见）且其中 3 个字段为 null；只有堆遍历能报出 {@code total=19 nullCount=3}。</li>
 *   <li>{@code predicate-groups} 末尾的 {@code control} 切面：故意把 1 个存活实例的两个字段置 null，
 *       证明"重定义前持有的具体实现类"分桶机器真的能点名到类（这是不变量 C1 对 230 个被置 null 字段的要求）。</li>
 * </ul>
 *
 * <p>输出是给父进程解析的 {@code KEY=value} 行；值里不得出现空格与 {@code =}（父进程的解析器按空白分词）。</p>
 */
public final class HeapWatchPredicateFixture {

	/** 每组实例数（要求 ≥100，与真机 244 个同量级）。 */
	static final int GROUP_SIZE = 120;
	/** 同时存活的匿名类实例数（要求 ≥3）。 */
	static final int ANON_INSTANCES = 4;
	/** 并发场景：线程数、每线程保留的最近实例数、重定义前后各跑多久（毫秒）。 */
	static final int CONCURRENT_THREADS = 4;
	static final int CONCURRENT_RING = 64;
	static final long CONCURRENT_WARMUP_MS = 150L;

	// ------------------------------------------------------------------------------------------------
	// 被测类：可空引用字段 Predicate；PredicatePair 自己声明匿名类（与"含匿名类的 Mixin"同形）
	// ------------------------------------------------------------------------------------------------

	/** 具名 Predicate 实现（对照组：字段里装的是它，不涉及匿名类）。 */
	public static final class NamedCondition implements Predicate<String> {
		private final String prefix;

		NamedCondition(String prefix) {
			this.prefix = prefix;
		}

		@Override
		public boolean test(String value) {
			return value != null && value.startsWith(prefix);
		}
	}

	/**
	 * 被重定义的那个类：真机 {@code LootContextPredicate} 的替身。
	 *
	 * <p>两个 {@code Predicate} 字段分别装具名实现与匿名实现；类自己用 {@link #makeAnon(String)} 声明匿名类
	 * （编译成 {@code PredicatePair$1}），于是"被重定义的类含匿名类"这个真机条件在这里成立。</p>
	 */
	public static final class PredicatePair {
		final int id;
		Predicate<String> namedCondition;
		Predicate<String> anonCondition;

		PredicatePair(int id, Predicate<String> namedCondition, Predicate<String> anonCondition) {
			this.id = id;
			this.namedCondition = namedCondition;
			this.anonCondition = anonCondition;
		}

		/** 在<b>本类</b>内声明匿名类：PredicatePair$1，持有捕获变量 {@code tag}。 */
		static Predicate<String> makeAnon(final String tag) {
			return new Predicate<String>() {
				@Override
				public boolean test(String value) {
					return value != null && value.startsWith(tag);
				}
			};
		}

		/** 业务终局读法（不经过反射）：两条谓词都必须可调用。返回 false 表示字段为 null。 */
		boolean conditionsUsable() {
			Predicate<String> named = namedCondition;
			Predicate<String> anon = anonCondition;
			if (named == null || anon == null) {
				return false;
			}
			// 穿过字段调用一次：字段为 null 的实例会在这里 NPE —— 这就是终局读法，不是"看起来还行"。
			named.test("probe");
			anon.test("probe");
			return true;
		}

		String version() {
			return "v1";
		}
	}

	/** 旁观实例：字段持有匿名类实例（原样指向，不复制）。 */
	public static final class Bystander {
		final int id;
		Predicate<String> anonRef;

		Bystander(int id, Predicate<String> anonRef) {
			this.id = id;
			this.anonRef = anonRef;
		}

		boolean anonUsable() {
			Predicate<String> ref = anonRef;
			if (ref == null) {
				return false;
			}
			ref.test("probe");
			return true;
		}
	}

	/** 另一个含匿名类的外层类（匿名实例存活期间它也会被结构重定义）。 */
	public static final class AnonBeta {
		static Predicate<String> make() {
			return new Predicate<String>() {
				@Override
				public boolean test(String value) {
					return value != null && !value.isEmpty();
				}
			};
		}
	}

	/** 第三个含匿名类的外层类。 */
	public static final class AnonGamma {
		static Predicate<String> make() {
			return new Predicate<String>() {
				@Override
				public boolean test(String value) {
					return "ok".equals(value) || "go".equals(value);
				}
			};
		}
	}

	/** 第四个含匿名类的外层类。 */
	public static final class AnonDelta {
		static Predicate<String> make() {
			return new Predicate<String>() {
				@Override
				public boolean test(String value) {
					return value == null || value.length() < 10;
				}
			};
		}
	}

	// ------------------------------------------------------------------------------------------------
	// 状态
	// ------------------------------------------------------------------------------------------------

	/** 强引用登记表：保证被校准的实例在扫描时真的活着。 */
	private static final List<PredicatePair> LIVE = new ArrayList<PredicatePair>();
	/** 只在堆里、注册表看不见的实例（通道证明用）。 */
	private static final List<PredicatePair> SIDECAR = new ArrayList<PredicatePair>();
	/** 重定义前字段值的具体实现类：身份 → 类名（分桶用）。 */
	private static final Map<Integer, String> NAMED_TYPES = new LinkedHashMap<Integer, String>();
	private static final Map<Integer, String> ANON_TYPES = new LinkedHashMap<Integer, String>();
	/** 重定义前字段值本身（强引用）：让"字段变成 null"不可能是"值被回收"。 */
	private static final List<Object> KEEP_ALIVE = new ArrayList<Object>();
	private static final List<Bystander> BYSTANDERS = new ArrayList<Bystander>();
	private static final List<WeakReference<Object>> ANON_REFS = new ArrayList<WeakReference<Object>>();

	private HeapWatchPredicateFixture() {
	}

	public static void main(String[] args) throws Exception {
		String scenario = args.length > 0 ? args[0] : "predicate-groups";
		Instrumentation inst = RemoldAgent.getInstrumentation();
		printChildVm();
		System.out.println("RESULT agent state=" + (inst == null ? "absent" : "attached"));
		System.out.println("RESULT channel kind=libtool-getInstances libtool="
			+ (LibTool.initialized() ? "yes" : "no"));
		if (inst == null) {
			System.out.println("RESULT fatal reason=no-instrumentation");
			return;
		}
		if ("channel-heapwalk".equals(scenario)) {
			runChannelHeapwalk();
		} else if ("predicate-anon".equals(scenario)) {
			runAnonInstances(inst);
		} else if ("predicate-g1".equals(scenario)) {
			printHeap("g1");
			runGroups(inst);
		} else if ("predicate-concurrent".equals(scenario)) {
			runConcurrent(inst);
		} else {
			runGroups(inst);
		}
	}

	/** 子 JVM 自报身份：父进程据此判断"这一轮到底跑在哪个运行时上"。 */
	private static void printChildVm() {
		System.out.println("RESULT childvm home=" + token(System.getProperty("java.home"))
			+ " vendor=" + token(System.getProperty("java.vm.vendor"))
			+ " version=" + token(System.getProperty("java.runtime.version"))
			+ " enhanced=" + (enhancedRuntime() ? "yes" : "no"));
	}

	/**
	 * 场景 ①③：两组 {@code Predicate} 字段在同一次结构重定义切面上的读数。
	 *
	 * <p>两组共用一次 redefine，唯一差别是字段值是不是匿名类实例——因此"一组变 null、另一组不变"
	 * 只能由匿名引用解释，反之"两组都不变"则排除匿名引用这个变量。</p>
	 */
	private static void runGroups(Instrumentation inst) throws Exception {
		for (int i = 0; i < GROUP_SIZE; i++) {
			PredicatePair pair = new PredicatePair(i, new NamedCondition("ok" + i), PredicatePair.makeAnon("a" + i));
			LIVE.add(pair);
			NAMED_TYPES.put(Integer.valueOf(identity(pair)), pair.namedCondition.getClass().getName());
			ANON_TYPES.put(Integer.valueOf(identity(pair)), pair.anonCondition.getClass().getName());
			KEEP_ALIVE.add(pair.namedCondition);
			KEEP_ALIVE.add(pair.anonCondition);
		}
		System.out.println("RESULT truth phase=before-redefine namedNulls=" + businessNulls(true)
			+ " anonNulls=" + businessNulls(false) + " live=" + LIVE.size());

		HeapFieldWatch.Snapshot namedBefore =
			HeapFieldWatch.scan("before-redefine", PredicatePair.class, "namedCondition");
		HeapFieldWatch.Snapshot anonBefore =
			HeapFieldWatch.scan("before-redefine", PredicatePair.class, "anonCondition");
		printItem("named", "before", namedBefore, null);
		printItem("anon", "before", anonBefore, null);

		boolean schema = redefineSchema(inst, PredicatePair.class);
		System.out.println("RESULT version phase=after-redefine value=" + LIVE.get(1).version());

		HeapFieldWatch.Snapshot namedAfter =
			HeapFieldWatch.scan("after-redefine", PredicatePair.class, "namedCondition");
		HeapFieldWatch.Snapshot anonAfter =
			HeapFieldWatch.scan("after-redefine", PredicatePair.class, "anonCondition");
		printItem("named", "after", namedAfter, HeapFieldWatch.group(namedBefore, namedAfter));
		printItem("anon", "after", anonAfter, HeapFieldWatch.group(anonBefore, anonAfter));
		System.out.println("RESULT truth phase=after-redefine namedNulls=" + businessNulls(true)
			+ " anonNulls=" + businessNulls(false) + " live=" + LIVE.size()
			+ " schema=" + (schema ? "changed" : "unchanged"));
		// 业务终局：所有实例的两条谓词都必须仍可调用（读不到就是 NPE）。
		int usable = 0;
		for (PredicatePair pair : LIVE) {
			if (pair.conditionsUsable()) {
				usable++;
			}
		}
		System.out.println("RESULT truth phase=after-redefine usable=" + usable + "/" + LIVE.size());

		// 分桶现场（若无复现，桶为空；control 切面证明这台机器能点名类）。
		printBuckets("after-redefine", true);
		printBuckets("after-redefine", false);

		// control：故意置空 1 个存活实例，证明"重定义前持有的具体实现类"分桶真的能落到类名上。
		PredicatePair victim = LIVE.get(0);
		System.out.println("RESULT control phase=named field=namedCondition type="
			+ NAMED_TYPES.get(Integer.valueOf(identity(victim))));
		System.out.println("RESULT control phase=anon field=anonCondition type="
			+ ANON_TYPES.get(Integer.valueOf(identity(victim))));
		victim.namedCondition = null;
		victim.anonCondition = null;
		HeapFieldWatch.Snapshot namedControl =
			HeapFieldWatch.scan("after-field-patch", PredicatePair.class, "namedCondition");
		HeapFieldWatch.Snapshot anonControl =
			HeapFieldWatch.scan("after-field-patch", PredicatePair.class, "anonCondition");
		printItem("named", "control", namedControl, HeapFieldWatch.group(namedAfter, namedControl));
		printItem("anon", "control", anonControl, HeapFieldWatch.group(anonAfter, anonControl));
		printBuckets("control", true);
		printBuckets("control", false);
	}

	/**
	 * 场景 ②：{@value #ANON_INSTANCES} 个同时存活的匿名类实例，各自被旁观实例引用；重定义它们的外层类后
	 * 实例必须还在、引用必须还是非 null。
	 */
	private static void runAnonInstances(Instrumentation inst) throws Exception {
		List<Predicate<String>> instances = new ArrayList<Predicate<String>>();
		instances.add(PredicatePair.makeAnon("alpha"));
		instances.add(AnonBeta.make());
		instances.add(AnonGamma.make());
		instances.add(AnonDelta.make());
		Set<String> distinct = new LinkedHashSet<String>();
		for (int i = 0; i < instances.size(); i++) {
			Predicate<String> instance = instances.get(i);
			ANON_REFS.add(new WeakReference<Object>(instance));
			distinct.add(instance.getClass().getName());
			BYSTANDERS.add(new Bystander(i, instance));
			System.out.println("RESULT anon index=" + i + " class=" + token(instance.getClass().getName()));
		}
		System.out.println("RESULT anon instances=" + instances.size() + " distinctClasses=" + distinct.size());
		HeapFieldWatch.Snapshot before =
			HeapFieldWatch.scan("before-redefine", Bystander.class, "anonRef");
		printItem("anonRef", "before", before, null);

		boolean schema = redefineSchema(inst, PredicatePair.class);
		boolean beta = redefineSchema(inst, AnonBeta.class);
		boolean gamma = redefineSchema(inst, AnonGamma.class);
		boolean delta = redefineSchema(inst, AnonDelta.class);
		System.out.println("RESULT schema all=" + (schema && beta && gamma && delta ? "changed" : "unchanged"));

		HeapFieldWatch.Snapshot after =
			HeapFieldWatch.scan("after-redefine", Bystander.class, "anonRef");
		printItem("anonRef", "after", after, HeapFieldWatch.group(before, after));

		int alive = 0;
		for (WeakReference<Object> ref : ANON_REFS) {
			if (ref.get() != null) {
				alive++;
			}
		}
		int usable = 0;
		for (Bystander bystander : BYSTANDERS) {
			if (bystander.anonUsable()) {
				usable++;
			}
		}
		System.out.println("RESULT anonAlive alive=" + alive + "/" + ANON_REFS.size()
			+ " distinctClasses=" + distinct.size() + " viaBystanders=" + usable + "/" + BYSTANDERS.size());
	}

	/**
	 * 场景 ④：重定义期间持续分配 + 读字段。读路径穿过字段解引用，字段为 null 就是 NPE——
	 * 计数落到 {@code npe=} 上，而不是靠"没日志"推断。
	 */
	private static void runConcurrent(Instrumentation inst) throws Exception {
		for (int i = 0; i < GROUP_SIZE; i++) {
			LIVE.add(new PredicatePair(i, new NamedCondition("ok" + i), PredicatePair.makeAnon("a" + i)));
		}
		HeapFieldWatch.Snapshot namedBefore =
			HeapFieldWatch.scan("before-redefine", PredicatePair.class, "namedCondition");
		HeapFieldWatch.Snapshot anonBefore =
			HeapFieldWatch.scan("before-redefine", PredicatePair.class, "anonCondition");
		printItem("named", "before", namedBefore, null);
		printItem("anon", "before", anonBefore, null);

		final java.util.concurrent.atomic.AtomicBoolean running =
			new java.util.concurrent.atomic.AtomicBoolean(true);
		final java.util.concurrent.atomic.AtomicLong reads = new java.util.concurrent.atomic.AtomicLong();
		final java.util.concurrent.atomic.AtomicLong allocations = new java.util.concurrent.atomic.AtomicLong();
		final java.util.concurrent.atomic.AtomicLong errors = new java.util.concurrent.atomic.AtomicLong();
		final java.util.concurrent.atomic.AtomicLong npes = new java.util.concurrent.atomic.AtomicLong();
		final java.util.concurrent.atomic.AtomicLong nullReads = new java.util.concurrent.atomic.AtomicLong();
		final Object allocationLock = new Object();
		final List<PredicatePair> ring = new ArrayList<PredicatePair>();
		final int liveSize = LIVE.size();
		List<Thread> workers = new ArrayList<Thread>();
		for (int t = 0; t < CONCURRENT_THREADS; t++) {
			final int slot = t;
			Thread worker = new Thread(new Runnable() {
				@Override
				public void run() {
					while (running.get()) {
						try {
							PredicatePair fresh = new PredicatePair(1000 + slot,
								new NamedCondition("c" + slot), PredicatePair.makeAnon("w" + slot));
							allocations.incrementAndGet();
							// 新分配实例：两条字段都必须立即可用（构造器写入的值不得丢）。
							if (!fresh.conditionsUsable()) {
								nullReads.incrementAndGet();
							}
							// 存量实例：真机读的就是这些老实例上的 combinedCondition（跨重定义存活）。
							long n = reads.get();
							PredicatePair shared = LIVE.get((int) (n % liveSize));
							if (!shared.conditionsUsable()) {
								nullReads.incrementAndGet();
							}
							synchronized (allocationLock) {
								if (ring.size() < CONCURRENT_RING) {
									ring.add(fresh);
								} else {
									ring.set((int) (n % CONCURRENT_RING), fresh);
								}
							}
							reads.incrementAndGet();
						} catch (NullPointerException e) {
							npes.incrementAndGet();
						} catch (Throwable e) {
							errors.incrementAndGet();
						}
					}
				}
			}, "predicate-reader-" + t);
			worker.setDaemon(true);
			workers.add(worker);
			worker.start();
		}
		Thread.sleep(CONCURRENT_WARMUP_MS);
		long readsBefore = reads.get();
		boolean schema = redefineSchema(inst, PredicatePair.class);
		Thread.sleep(CONCURRENT_WARMUP_MS);
		long readsDuring = reads.get() - readsBefore;
		running.set(false);
		for (Thread worker : workers) {
			worker.join(30000L);
		}

		HeapFieldWatch.Snapshot namedAfter =
			HeapFieldWatch.scan("after-redefine", PredicatePair.class, "namedCondition");
		HeapFieldWatch.Snapshot anonAfter =
			HeapFieldWatch.scan("after-redefine", PredicatePair.class, "anonCondition");
		printItem("named", "after", namedAfter, HeapFieldWatch.group(namedBefore, namedAfter));
		printItem("anon", "after", anonAfter, HeapFieldWatch.group(anonBefore, anonAfter));
		System.out.println("RESULT concurrent threads=" + CONCURRENT_THREADS
			+ " reads=" + reads.get() + " allocations=" + allocations.get()
			+ " readsAcrossRedefine=" + readsDuring
			+ " nullReads=" + nullReads.get() + " npe=" + npes.get() + " errors=" + errors.get()
			+ " schema=" + (schema ? "changed" : "unchanged"));
		System.out.println("RESULT truth phase=after-redefine namedNulls=" + businessNulls(true)
			+ " anonNulls=" + businessNulls(false) + " live=" + LIVE.size());
	}

	/**
	 * 通道证明：{@code LIVE} 注册表 12 个实例全非 null；另外 7 个实例只挂在 {@code SIDECAR} 上（注册表看不见），
	 * 其中 3 个字段为 null。只有 JVMTI 堆遍历能同时报出 {@code total=19 nullCount=3}。
	 */
	private static void runChannelHeapwalk() throws Exception {
		for (int i = 0; i < 12; i++) {
			LIVE.add(new PredicatePair(i, new NamedCondition("ok" + i), PredicatePair.makeAnon("a" + i)));
		}
		for (int i = 0; i < 7; i++) {
			Predicate<String> value = i < 3 ? null : new NamedCondition("s" + i);
			SIDECAR.add(new PredicatePair(100 + i, value, PredicatePair.makeAnon("z" + i)));
		}
		System.out.println("RESULT channel kind=libtool-getInstances libtool=" + (LibTool.initialized() ? "yes" : "no")
			+ " registry=" + LIVE.size() + " sidecar=" + SIDECAR.size()
			+ " enumerable=" + (LIVE.size() + SIDECAR.size()));
		System.out.println("RESULT truth phase=before-redefine registryNulls=" + businessNulls(true)
			+ " sidecarNulls=" + sidecarNulls() + " live=" + LIVE.size());
		// 产品路径（snapshot 解析类 + 打人读日志行），同时也给出结构化读数。
		HeapFieldWatch.snapshot("before-redefine", PredicatePair.class.getName());
		HeapFieldWatch.Snapshot named =
			HeapFieldWatch.scan("before-redefine", PredicatePair.class, "namedCondition");
		printItem("named", "before", named, null);
	}

	// ------------------------------------------------------------------------------------------------
	// 读数输出
	// ------------------------------------------------------------------------------------------------

	/** 一行 {@code RESULT item-<group> phase=<phase> total=… newlyNull=… agree=… conflict=…}。 */
	private static void printItem(String group, String phase, HeapFieldWatch.Snapshot snapshot,
								  HeapFieldWatch.Grouping grouping) {
		StringBuilder sb = new StringBuilder("RESULT item-").append(group)
			.append(" phase=").append(phase)
			.append(" total=").append(snapshot.total)
			.append(" nullCount=").append(snapshot.nullCount)
			.append(" absent=").append(snapshot.absent)
			.append(" unreadable=").append(snapshot.unreadable)
			.append(" agree=").append(snapshot.channelAgree)
			.append(" conflict=").append(snapshot.channelConflict)
			.append(" unavailable=").append(snapshot.channelUnavailable);
		if (grouping != null) {
			sb.append(" newlyNull=").append(grouping.newlyNull)
				.append(" persisted=").append(grouping.persisted)
				.append(" vanished=").append(grouping.vanished)
				.append(" created=").append(grouping.created)
				.append(" collected=").append(grouping.collected)
				.append(" beforeTotal=").append(grouping.beforeTotal)
				.append(" afterTotal=").append(grouping.afterTotal)
				.append(" identities=").append(grouping.identitiesHold() ? "ok" : "broken");
		}
		System.out.println(sb);
	}

	/**
	 * 分桶：把"重定义前非 null、现在为 null"的实例按<b>重定义前持有的具体实现类</b>归类。
	 *
	 * <p>不变量 C1 要求真机上被置 null 的 230 个字段可以分桶到具体实现类；这里把同一份机器先建好，
	 * 真复现时桶里就会出现类名而不是一个光秃秃的计数。</p>
	 */
	private static void printBuckets(String phase, boolean namedField) {
		Map<Integer, String> types = namedField ? NAMED_TYPES : ANON_TYPES;
		Map<String, Integer> buckets = new LinkedHashMap<String, Integer>();
		for (PredicatePair pair : LIVE) {
			Object value = namedField ? pair.namedCondition : pair.anonCondition;
			String before = types.get(Integer.valueOf(identity(pair)));
			if (value != null || before == null) {
				continue;
			}
			Integer old = buckets.get(before);
			buckets.put(before, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
		}
		String field = namedField ? "namedCondition" : "anonCondition";
		if (buckets.isEmpty()) {
			System.out.println("RESULT bucket phase=" + phase + " field=" + field + " type=none count=0");
			return;
		}
		for (Map.Entry<String, Integer> entry : buckets.entrySet()) {
			System.out.println("RESULT bucket phase=" + phase + " field=" + field
				+ " type=" + token(entry.getKey()) + " count=" + entry.getValue());
		}
	}

	/** 实际生效的收集器与堆上限（不是回显命令行）。 */
	private static void printHeap(String phase) {
		List<String> gcNames = new ArrayList<String>();
		boolean g1 = false;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			String name = gc.getName();
			gcNames.add(token(name));
			if (name.toLowerCase(Locale.ROOT).contains("g1")) {
				g1 = true;
			}
		}
		long max = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
		boolean arg = false;
		for (String value : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
			if ("-XX:+UseG1GC".equals(value)) {
				arg = true;
			}
		}
		System.out.println("RESULT heap phase=" + phase + " g1arg=" + (arg ? "yes" : "no")
			+ " g1=" + (g1 ? "yes" : "no") + " gc=" + join(gcNames, "+") + " max=" + max);
	}

	private static String join(List<String> values, String separator) {
		StringBuilder sb = new StringBuilder();
		for (String value : values) {
			if (sb.length() > 0) {
				sb.append(separator);
			}
			sb.append(value);
		}
		return sb.toString();
	}

	/** 把值里对父进程解析器有歧义的字符（空白、{@code =}）换成下划线。 */
	private static String token(String value) {
		if (value == null) {
			return "null";
		}
		return value.replace(' ', '_').replace('\t', '_').replace('=', '_');
	}

	private static int identity(Object instance) {
		return System.identityHashCode(instance);
	}

	/** 业务终局：逐个实例读字段（不经过反射、不经过哨兵）。 */
	private static int businessNulls(boolean namedField) {
		int nulls = 0;
		for (PredicatePair pair : LIVE) {
			Object value = namedField ? pair.namedCondition : pair.anonCondition;
			if (value == null) {
				nulls++;
			}
		}
		return nulls;
	}

	/** 注册表看不见的那些实例里有几个字段为 null（通道证明的已知答案）。 */
	private static int sidecarNulls() {
		int nulls = 0;
		for (PredicatePair pair : SIDECAR) {
			if (pair.namedCondition == null) {
				nulls++;
			}
		}
		return nulls;
	}

	// ------------------------------------------------------------------------------------------------
	// 重定义 / 字节码
	// ------------------------------------------------------------------------------------------------

	/**
	 * 结构重定义：新增可空字段 {@code addedProbe} 并改 {@code version()} 的常量（既改字段表也改方法体，
	 * 与真机 Mixin 织入同形）。运行时不允许 schema 变化时退回"只改方法体"并明确打印 {@code value=cold}。
	 */
	private static boolean redefineSchema(Instrumentation inst, Class<?> type) throws Exception {
		byte[] original = readClassBytes(type);
		byte[] patched = withAddedProbeField(original);
		try {
			inst.redefineClasses(new ClassDefinition(type, patched));
		} catch (Throwable t) {
			System.out.println("RESULT schema value=cold class=" + token(type.getName())
				+ " reason=" + token(t.getClass().getName() + ":" + String.valueOf(t.getMessage())));
			byte[] methodOnly = patchVersionConstant(original);
			try {
				inst.redefineClasses(new ClassDefinition(type, methodOnly));
			} catch (Throwable ignored) {
				// 只改方法体都失败时留痕：不能把"什么都没做"当成 cold 分支。
				System.out.println("RESULT schema value=failed class=" + token(type.getName())
					+ " reason=" + token(ignored.getClass().getName()));
			}
			return false;
		}
		System.out.println("RESULT schema value=ok class=" + token(type.getName())
			+ " bytes=" + original.length + "->" + patched.length);
		return true;
	}

	private static byte[] readClassBytes(Class<?> type) throws Exception {
		String resource = "/" + type.getName().replace('.', '/') + ".class";
		InputStream in = type.getResourceAsStream(resource);
		if (in == null) {
			throw new IllegalStateException("cannot read bytes of " + type.getName() + " (resource " + resource + ")");
		}
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			int read;
			while ((read = in.read(buffer)) >= 0) {
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		} finally {
			in.close();
		}
	}

	/** 在类末尾追加 {@code public String addedProbe;}（字段表变化）。 */
	private static byte[] withAddedProbeField(byte[] bytes) {
		ClassReader reader = new ClassReader(bytes);
		ClassWriter writer = new ClassWriter(reader, 0);
		reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public void visitEnd() {
				FieldVisitor field = super.visitField(Opcodes.ACC_PUBLIC, "addedProbe", "Ljava/lang/String;",
					null, null);
				if (field != null) {
					field.visitEnd();
				}
				super.visitEnd();
			}
		}, 0);
		return writer.toByteArray();
	}

	private static byte[] patchVersionConstant(byte[] bytes) {
		ClassReader reader = new ClassReader(bytes);
		ClassWriter writer = new ClassWriter(reader, 0);
		reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
											 String signature, String[] exceptions) {
				MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
				if (!"version".equals(name)) {
					return delegate;
				}
				return new MethodVisitor(Opcodes.ASM9, delegate) {
					@Override
					public void visitLdcInsn(Object value) {
						super.visitLdcInsn("v1".equals(value) ? "v2" : value);
					}
				};
			}
		}, 0);
		return writer.toByteArray();
	}

	/**
	 * 当前运行时是否真有增强重定义能力：判据与 {@code remold.EnvironmentProbe}、校准夹具同口径
	 * （厂商标识属于 DCEVM 系，且 {@code -XX:+AllowEnhancedClassRedefinition} 真的在启动参数里）。
	 */
	private static boolean enhancedRuntime() {
		String identity = (System.getProperty("java.vm.vendor", "") + ' '
			+ System.getProperty("java.vm.name", "") + ' '
			+ System.getProperty("java.runtime.version", "")).toLowerCase(Locale.ROOT);
		boolean looksEnhanced = identity.contains("jetbrains") || identity.contains("dcevm")
			|| identity.contains("trava");
		return looksEnhanced && ManagementFactory.getRuntimeMXBean().getInputArguments()
			.contains("-XX:+AllowEnhancedClassRedefinition");
	}
}
