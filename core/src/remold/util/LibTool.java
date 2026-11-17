package remold.util;

import remold.RemoldAgent;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.lang.ref.*;
import java.lang.reflect.Array;
import java.util.*;

/** 依赖jvmti的工具类 */
public class LibTool {
	private static volatile boolean initialized;

	static {
		tryInit();
	}

	public static boolean initialized() {
		return initialized;
	}

	public static boolean tryInit() {
		if (initialized) return true;
		try {
			init();
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	public synchronized static void init() {
		if (initialized) return;

		// 1. 显式指定属性优先（测试夹具或自定义路径）
		String libPath = System.getProperty("remold.path.libtool");
		if (libPath != null && !libPath.trim().isEmpty()) {
			File file = new File(libPath.trim());
			if (file.exists() && file.isFile()) {
				System.load(file.getAbsolutePath());
				initialized = true;
				RemoldAgent.info("[Lib] Loaded libtool from property: " + file.getAbsolutePath());
				return;
			}
		}

		// 2. 尝试从系统 java.library.path 加载
		try {
			System.loadLibrary("tool64");
			initialized = true;
			RemoldAgent.info("[Lib] Loaded libtool from java.library.path.");
			return;
		} catch (Throwable ignored) {
		}

		// 3. 从 java.library.path、Jar 资源或本地工程目录按平台候选顺序加载
		// （在无 android.os.Build 的 AndroidJBR / Termux 上，Linux/aarch64 会从 glibc 产物回退到 bionic NDK 产物）
		String os = System.getProperty("os.name", "unknown");
		String arch = System.getProperty("os.arch", "unknown");
		boolean android = isAndroid();
		List<String> candidates = NativeLibraryName.candidates(os, arch, android);
		Throwable lastFailure = null;
		for (String libName : candidates) {
			Throwable loadFailure = tryLoadCandidate(libName);
			if (initialized) return;
			if (loadFailure != null) lastFailure = loadFailure;
		}

		String detail = lastFailure != null && lastFailure.getMessage() != null
			? ": " + lastFailure.getClass().getSimpleName() + ": " + lastFailure.getMessage()
			: "";
		UnsatisfiedLinkError err = new UnsatisfiedLinkError("Cannot locate or load native JVMTI library "
			+ candidates + " for " + os + "/" + arch + (android ? " (Android)" : "")
			+ " (tried remold.path.libtool, java.library.path, and embedded resources)" + detail);
		if (lastFailure != null) err.initCause(lastFailure);
		throw err;
	}

	private static Throwable tryLoadCandidate(String libName) {
		Throwable lastFailure = null;
		String shortName = stripLibraryAffixes(libName);
		if (shortName != null && !"tool64".equals(shortName)) {
			try {
				System.loadLibrary(shortName);
				initialized = true;
				RemoldAgent.info("[Lib] Loaded " + libName + " from java.library.path.");
				return null;
			} catch (Throwable ignored) {
			}
		}

		byte[] embeddedBytes = readEmbeddedLibrary(libName);
		if (embeddedBytes != null) {
			for (File dir : nativeCacheDirs()) {
				File target = writeNativeLibrary(dir, libName, embeddedBytes);
				if (target == null) continue;
				try {
					System.load(target.getAbsolutePath());
					initialized = true;
					RemoldAgent.info("[Lib] Loaded " + libName + " from: " + target.getAbsolutePath());
					return null;
				} catch (Throwable t) {
					lastFailure = t;
				}
			}
		}

		for (File local : localSearchFiles(libName)) {
			try {
				System.load(local.getAbsolutePath());
				initialized = true;
				RemoldAgent.info("[Lib] Loaded " + libName + " from: " + local.getAbsolutePath());
				return null;
			} catch (Throwable t) {
				lastFailure = t;
			}
		}
		return lastFailure;
	}

	private static String stripLibraryAffixes(String libName) {
		if (libName == null) return null;
		String s = libName;
		if (s.startsWith("lib")) s = s.substring(3);
		int dot = s.lastIndexOf('.');
		return dot > 0 ? s.substring(0, dot) : s;
	}

	private static byte[] readEmbeddedLibrary(String libName) {
		String resourcePath = "/libs/" + libName;
		try (InputStream in = LibTool.class.getResourceAsStream(resourcePath)) {
			if (in != null) {
				byte[] bytes = in.readAllBytes();
				if (bytes.length > 0) return bytes;
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	private static List<File> nativeCacheDirs() {
		List<File> dirs = new ArrayList<>(2);
		String tmp = System.getProperty("java.io.tmpdir");
		if (tmp != null && !tmp.trim().isEmpty()) {
			dirs.add(new File(tmp.trim(), "remold_natives"));
		}
		String home = System.getProperty("user.home");
		if (home != null && !home.trim().isEmpty()) {
			File userDir = new File(home.trim(), ".remold/natives");
			if (!dirs.contains(userDir)) {
				dirs.add(userDir);
			}
		}
		return dirs;
	}

	private static File writeNativeLibrary(File tempDir, String libName, byte[] bytes) {
		try {
			if (!tempDir.exists() && !tempDir.mkdirs() && !tempDir.isDirectory()) {
				return null;
			}
			File target = new File(tempDir, libName);
			if (!target.exists() || target.length() != bytes.length) {
				try {
					Files.write(target.toPath(), bytes);
				} catch (IOException e) {
					// Windows 上同名 DLL 可能已被其他并发进程锁定，生成独立文件名
					target = new File(tempDir, System.currentTimeMillis() + "_"
						+ Math.abs(new Random().nextInt()) + "_" + libName);
					Files.write(target.toPath(), bytes);
				}
				target.deleteOnExit();
			}
			return target;
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static List<File> localSearchFiles(String libName) {
		String[] searchPaths = {
			"core/libs/" + libName,
			"../core/libs/" + libName,
			"libs/" + libName,
			"../../core/libs/" + libName
		};
		List<File> files = new ArrayList<>(searchPaths.length);
		for (String p : searchPaths) {
			File f = new File(p);
			if (f.exists() && f.isFile()) {
				files.add(f.getAbsoluteFile());
			}
		}
		return files;
	}

	/** Android/ART 下 {@code os.name} 同样是 {@code Linux}，只能靠该平台独有的类区分。 */
	private static boolean isAndroid() {
		try {
			Class.forName("android.os.Build", false, LibTool.class.getClassLoader());
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static native Object[] nGetInstances(Class<?> clazz);
	private static native Object[] nGetInstancesTimed(Class<?> clazz, long[] timingsOut);
	private static native boolean  nHasLiveInstance(Class<?> clazz);
	private static native Object[] nGetReferrers(Object targetObject);
	private static native int nEnumBucketCount(long count);
	private static native int nEnumBucketWindow();
	private static native Object[][] nGetInstancesBatch(Class<?>[] targets, long[] timingsOut);
	private static native long nHasLiveInstancesBatch(Class<?>[] targets);
	private static native long nHeapWalkCount();
	private static volatile boolean enumPlanSupported = true;

	/**
	 * 单次批次查询覆盖的 target 上限（与 native 侧 {@code kMaxBatchTargets} 同一常量）。
	 * 超过上限由 {@link #getInstancesBatch(Class[])} 自动分块，每块仍只做一次全堆遍历。
	 */
	/**
	 * 单次 native 批查询的 target 上限：对象 tag 的槽位按「不同 target 掩码组合」计，
	 * 组合数可以超过 target 数（一个类同时命中多个 target 时是并集掩码），而
	 * {@code GetObjectsWithTags} 的成本随 tag 数增长（历史实测：4M 实例 16 桶 ≈3.3 s）。
	 * 取 16：槽位预算与检索 tag 数都压在 16，超出由本类按块切分（K=64 时 4 次遍历，仍是 K 次的 1/16）。
	 */
	public static final int BATCH_TARGET_LIMIT = 16;

	/**
	 * 存在性探测（{@link #hasLiveInstancesBatch}）的单次 target 上限，可大于
	 * {@link #BATCH_TARGET_LIMIT}：存在性探测只置位掩码、**不产生对象 tag 槽位**，不受
	 * 「检索 tag 数」预算约束，所以一次遍历可以覆盖到 native 的 target 上限（64）。
	 */
	public static final int BATCH_LIVENESS_TARGET_LIMIT = 64;

	/** 新 native 库（含批次入口）是否可用；旧库缺导出符号时置 false 并整体退回逐类路径。 */
	private static volatile boolean batchScanSupported = true;
	private static volatile boolean batchFallbackWarned;

	/**
	 * 当前加载的 native 库是否提供批次级单次全堆遍历入口。
	 *
	 * <p>旧版 native 库（未随本改动重编）没有 {@code nGetInstancesBatch}：此时
	 * {@link #getInstancesBatch(Class[])} 会自动退回逐类路径，行为仍然正确，只是遍历次数回到 K 次。</p>
	 */
	public static boolean batchScanIsNative() {
		return heapWalkCount() >= 0;
	}

	/**
	 * 实例收集类全堆遍历的累计真实次数（native 侧计数，非估算）。
	 *
	 * <p>统计口径：为「收集实例 / 判定存活」而做的全堆遍历（逐类路径的
	 * {@code IterateOverInstancesOfClass} 与批次路径的 {@code IterateThroughHeap} 各计一次）；
	 * tag 清理用的过滤遍历不计。旧版 native 库返回 {@code -1}（无法取证）。</p>
	 */
	public static long heapWalkCount() {
		if (!initialized) init();
		try {
			return nHeapWalkCount();
		} catch (UnsatisfiedLinkError e) {
			return -1L;
		}
	}

	/** 实例枚举分桶窗口（实例数）：{@code 1 << 18}，经验值（约 8–12 MB 对象量，为 4M 实测拐点留 2 倍余量）。 */
	public static int enumerationBucketWindow() {
		if (!initialized) init();
		if (enumPlanSupported) {
			try {
				int window = nEnumBucketWindow();
				// 旧 native 库（未随本改动重编）窗口不同：视为不支持，整体回落到 Java 镜像，
				// 避免"Java 侧报 2^18、C++ 侧仍按 2^19 分桶"的不一致让测试假绿/假红。
				if (window == (1 << 18)) return window;
				enumPlanSupported = false;
			} catch (UnsatisfiedLinkError e) {
				enumPlanSupported = false;
			}
		}
		return 1 << 18;
	}

	/** 是否直接由当前加载的 native 库提供 2^18 分桶规划（未回落到 Java 镜像）。 */
	public static boolean enumerationBucketPlanIsNative() {
		if (!initialized) init();
		enumerationBucketWindow();
		return enumPlanSupported;
	}

	/**
	 * 实例枚举分桶规划：桶数 = ceil(count / window)，上限 64；超过上限时尾部实例进入最后一桶。
	 * 与 native 侧 {@code enumBucketCount} 保持同一实现，旧版 native 库缺少导出符号时走纯 Java 兜底。
	 */
	public static int enumerationBucketCount(long count) {
		if (!initialized) init();
		if (enumPlanSupported) {
			try {
				return nEnumBucketCount(count);
			} catch (UnsatisfiedLinkError e) {
				enumPlanSupported = false;
			}
		}
		if (count <= 0) return 1;
		long buckets = ((count - 1) >> 18) + 1;
		return buckets > 64 ? 64 : (int) buckets;
	}
	private static volatile boolean fastLiveProbeSupported = true;
	private static volatile boolean timedQuerySupported = true;

	@SuppressWarnings("unchecked")
	private static <T> Class<T> prepareInstanceQueryClass(Class<T> clazz) {
		Objects.requireNonNull(clazz, "clazz cannot be null");
		clazz = (Class<T>) ClassIdentity.canonicalize(clazz);
		if (clazz.isPrimitive()) {
			throw new IllegalArgumentException("Primitive types have no heap instances: " + clazz);
		}
		if (clazz.isArray()) {
			throw new IllegalArgumentException("Array types are not supported for instance queries: " + clazz);
		}
		if (!initialized) init();
		return clazz;
	}

	/**
	 * 非物化快速判定堆中是否存在指定类（含子类/实现）的未回收实例。
	 *
	 * <p>底层支持时会在遍历到第一个实例后立即中止 JVMTI 堆迭代（不打 tag、不构建对象数组）；
	 * 若运行时加载的旧版 native 库尚无该导出符号，自动降级为 {@link #getInstances(Class)} 判空。</p>
	 */
	public synchronized static boolean hasLiveInstance(Class<?> clazz) {
		clazz = prepareInstanceQueryClass(clazz);
		if (fastLiveProbeSupported) {
			try {
				return nHasLiveInstance(clazz);
			} catch (UnsatisfiedLinkError ignored) {
				fastLiveProbeSupported = false;
			}
		}
		return getInstances(clazz).length > 0;
	}

	/**
	 * 获取指定类及其子类在堆中所有尚未被回收的实例并返回局部引用数组。
	 * 包含多态子类与接口实现，包含不可达但尚未被 GC 物理回收的对象。
	 */
	@SuppressWarnings("unchecked")
	public synchronized static <T> T[] getInstances(Class<T> clazz) {
		clazz = prepareInstanceQueryClass(clazz);
		if (RemoldAgent.PERF_LOG && timedQuerySupported) {
			long[] timings = new long[8];
			long startNanos = System.nanoTime();
			try {
				T[] res = (T[]) nGetInstancesTimed(clazz, timings);
				long totalNanos = System.nanoTime() - startNanos;
				logInstanceQueryBreakdown(clazz, totalNanos, timings);
				return res != null ? res : (T[]) Array.newInstance(clazz, 0);
			} catch (UnsatisfiedLinkError ignored) {
				timedQuerySupported = false;
			}
		}
		T[] res = (T[]) nGetInstances(clazz);
		return res != null ? res : (T[]) Array.newInstance(clazz, 0);
	}

	/**
	 * 一次全堆遍历收集一批类（含各自全部已加载子类/接口实现）的存活实例。
	 *
	 * <p>返回值与 {@code targets} 下标对齐：{@code result[i]} 是 {@code targets[i]} 的实例集合，
	 * 元素类型为 {@code Object}（不做强类型数组，避免数组类带来的 {@code ArrayStoreException}）。
	 * 多态语义与 {@link #getInstances(Class)} 完全一致：按对象的<b>精确运行时类</b>归类，
	 * 运行时类是某个 target 或其子类型即计入该 target；同一对象可以同时出现在多个 target 的结果里
	 * （{@code Sub extends Base} 同批出现时，{@code Sub} 实例同时进 {@code Base} 与 {@code Sub}）。</p>
	 *
	 * <p><b>K=1 时直接走单类路径</b>（零额外开销）；K&gt;{@link #BATCH_TARGET_LIMIT} 时自动分块，
	 * 每块一次全堆遍历；旧版 native 库缺少批次入口时整体退回逐类路径（结果相同，遍历次数回到 K 次）。</p>
	 *
	 * @param targets 目标类；不得为 {@code null}、基本类型镜像或数组类
	 * @return 与 targets 等长的实例集合数组
	 */
	public synchronized static Object[][] getInstancesBatch(Class<?>[] targets) {
		return getInstancesBatch(targets, null);
	}

	/**
	 * {@link #getInstancesBatch(Class[])} 的诊断版本：额外回传 native 分段耗时（纳秒）。
	 *
	 * <p>{@code timings} 长度 ≥ 6：{@code [0]=enumerate}（已加载类 → target 掩码的归类）、
	 * {@code [1]=iterate}（全堆遍历）、{@code [2]=collect}（{@code GetObjectsWithTags}）、
	 * {@code [3]=fill}（落进各 target 的数组）、{@code [4]=cleanup}（tag 清零）、
	 * {@code [5]=rawCount}（本次命中的对象数）。只用于探针取证，产品路径不传。</p>
	 */
	public synchronized static Object[][] getInstancesBatch(Class<?>[] targets, long[] timings) {
		if (targets == null || targets.length == 0) return new Object[0][];
		Class<?>[] canonical = new Class<?>[targets.length];
		for (int i = 0; i < targets.length; i++) {
			canonical[i] = prepareInstanceQueryClass(targets[i]);
		}
		if (canonical.length == 1) {
			// K=1：直走原单类路径，不引入任何额外开销（docs/initfix/05-jvmti-heap.md）。
			return new Object[][]{ getInstances(canonical[0]) };
		}
		Object[][] out = new Object[canonical.length][];
		if (batchScanSupported) {
			boolean done = true;
			try {
				for (int start = 0; start < canonical.length; start += BATCH_TARGET_LIMIT) {
					int n = Math.min(BATCH_TARGET_LIMIT, canonical.length - start);
					Class<?>[] chunk = Arrays.copyOfRange(canonical, start, start + n);
					Object[][] part = nGetInstancesBatch(chunk, timings);
					if (part == null) {
						done = false;
						break;
					}
					for (int i = 0; i < n; i++) {
						out[start + i] = part[i] != null ? part[i] : EMPTY_OBJECTS;
					}
				}
			} catch (UnsatisfiedLinkError e) {
				batchScanSupported = false;
				done = false;
			}
			if (done) return out;
			warnBatchFallbackOnce();
		}
		// 降级：逐类路径（旧 native 库 / 目标形态不支持批次归类）。
		for (int i = 0; i < canonical.length; i++) {
			out[i] = getInstances(canonical[i]);
		}
		return out;
	}

	/**
	 * 一次全堆遍历判定一批类各自「有没有存活实例」（{@link #hasLiveInstance(Class)} 的批量版）。
	 *
	 * <p>只做存在性判定、不物化对象数组，也<b>不</b>返回可复用的实例快照：存在性探测跑在
	 * 重定义之前，实例收集跑在重定义之后，两者语义不能混用（{@code docs/initfix/05-jvmti-heap.md}）。
	 * 全部 target 都已见到实例时 native 侧提前中止遍历。</p>
	 *
	 * @param targets 目标类；不得为 {@code null}
	 * @return 与 targets 等长的布尔数组
	 */
	public synchronized static boolean[] hasLiveInstancesBatch(Class<?>[] targets) {
		if (targets == null || targets.length == 0) return new boolean[0];
		Class<?>[] canonical = new Class<?>[targets.length];
		for (int i = 0; i < targets.length; i++) {
			canonical[i] = prepareInstanceQueryClass(targets[i]);
		}
		boolean[] out = new boolean[canonical.length];
		if (canonical.length == 1) {
			out[0] = hasLiveInstance(canonical[0]);
			return out;
		}
		if (batchScanSupported) {
			boolean done = true;
			try {
				for (int start = 0; start < canonical.length; start += BATCH_LIVENESS_TARGET_LIMIT) {
					int n = Math.min(BATCH_LIVENESS_TARGET_LIMIT, canonical.length - start);
					Class<?>[] chunk = Arrays.copyOfRange(canonical, start, start + n);
					long mask = nHasLiveInstancesBatch(chunk);
					if (mask < 0) {
						done = false;
						break;
					}
					for (int i = 0; i < n; i++) {
						out[start + i] = (mask & (1L << i)) != 0L;
					}
				}
			} catch (UnsatisfiedLinkError e) {
				batchScanSupported = false;
				done = false;
			}
			if (done) return out;
			warnBatchFallbackOnce();
		}
		for (int i = 0; i < canonical.length; i++) {
			out[i] = hasLiveInstance(canonical[i]);
		}
		return out;
	}

	private static void warnBatchFallbackOnce() {
		if (batchFallbackWarned) return;
		batchFallbackWarned = true;
		RemoldAgent.warn("[Lib] Native batch heap scan is unavailable (old libtool build or unsupported target"
			+ " shape); instance collection falls back to one full heap walk per class.");
	}

	private static final Object[] EMPTY_OBJECTS = new Object[0];

	private static void logInstanceQueryBreakdown(Class<?> clazz, long totalNanos, long[] timings) {
		RemoldAgent.info(String.format(
			Locale.ROOT,
			"[Lib] getInstances(%s): count=%d (kept=%d), total=%.1fms [validate=%.1fms, iterate=%.1fms, collect=%.1fms, alloc=%.1fms, fill=%.1fms, cleanup=%.1fms]",
			clazz.getName(),
			timings[6],
			timings[7],
			totalNanos / 1_000_000.0,
			timings[0] / 1_000_000.0,
			timings[1] / 1_000_000.0,
			timings[2] / 1_000_000.0,
			timings[3] / 1_000_000.0,
			timings[4] / 1_000_000.0,
			timings[5] / 1_000_000.0
		));
	}

	/**
	 * 获取指定对象在 JVM 中的所有引用对象，如果是Class
	 * 语义说明：仅包含堆中其他对象对它的字段引用，不包含线程栈局部变量、JNI 全局引用等 GC Roots。
	 * 若目标对象本身为 Class 对象，堆中所有该类的实例均会被视作引用者（即实例对自身类的类引用关系）。
	 * 目标对象自身的自引用（this.self = this）不会出现在返回结果中。
	 */
	public synchronized static Object[] getReferrers(Object targetObject) {
		if (targetObject == null) return new Object[0];
		if (!initialized) init();
		Object[] res = nGetReferrers(targetObject);
		return res != null ? res : new Object[0];
	}

	/**
	 * 获取指定目标对象的所有【强引用持有者】，并全部以 WeakReference 包装返回。
	 *
	 * <p>特性：
	 * <ul>
	 *   <li>自动排除 WeakReference、SoftReference、PhantomReference 等通过 referent 指向它的弱引者；</li>
	 *   <li>支持识别 WeakHashMap.Entry（若目标是 Key 则被视作弱引用排除；若目标是 Value 则正确保留为强引用）；</li>
	 *   <li>返回的全部为 WeakReference，确保诊断工具本身【绝对不会】阻碍这些引用者被 GC 回收；</li>
	 *   <li>默认剔除自引用（持有自身的循环引用）。</li>
	 * </ul>
	 * @param target 目标对象
	 * @return 强引用者的弱引用列表（若无强引用或 target 为 null，返回空列表）
	 */
	public static List<WeakReference<Object>> getStrongReferrersAsWeak(Object target) {
		return getStrongReferrersAsWeak(target, true);
	}

	/**
	 * @param target      目标对象
	 * @param excludeSelf 是否排除自身对自身的引用
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static List<WeakReference<Object>> getStrongReferrersAsWeak(Object target, boolean excludeSelf) {
		if (target == null) return Collections.emptyList();

		// 调用底层 C++ JVMTI 接口获取堆中所有直接引用者（包含强、弱、软、虚引用）
		// 必须走公开的 getReferrers：它负责触发 init() 装载 native 库并做 null 防护，
		// 直接调 native 方法会在库未加载时抛 UnsatisfiedLinkError。
		Object[] rawReferrers = getReferrers(target);
		if (rawReferrers == null || rawReferrers.length == 0) {
			return Collections.emptyList();
		}

		List<WeakReference<Object>> safeList = new ArrayList<>(rawReferrers.length);

		// 内存过滤与弱引用转化
		for (Object referrer : rawReferrers) {
			if (referrer == null) continue;

			// 选项：排除自身引用自身（如 this.self = this）
			if (excludeSelf && referrer == target) {
				continue;
			}

			// 核心判定：如果引用者本身是一个 java.lang.ref.Reference，
			// 且它正是通过弱指针槽位（referent）指向了 target，则说明这是无害的弱引用，直接过滤！
			if (referrer instanceof Reference ref && ref.refersTo(target)) {
				continue;
			}

			// 走到这里的全部是真正的【强引用持有者】！
			// 使用 WeakReference 封装，打破长效观察者对元凶对象的生命周期锁定
			safeList.add(new WeakReference<>(referrer));
		}

		// \rawReferrers 数组在方法结束后随局部变量出栈，强引用瞬间全部解脱！
		return safeList;
	}
}
