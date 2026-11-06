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

		// 3. 从 Jar 资源或本地工程目录解包加载
		File resolved = resolveNativeLibrary();
		if (resolved != null && resolved.exists()) {
			System.load(resolved.getAbsolutePath());
			initialized = true;
			RemoldAgent.info("[Lib] Loaded libtool from: " + resolved.getAbsolutePath());
			return;
		}

		throw new UnsatisfiedLinkError("Cannot locate or load tool64 native library (tried remold.path.libtool, java.library.path, and embedded resources)");
	}

	private static File resolveNativeLibrary() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		String libName;
		if (os.contains("win")) {
			libName = "tool64.dll";
		} else if (os.contains("mac") || os.contains("darwin")) {
			libName = "libtool64.dylib";
		} else if (arch.contains("aarch64") || arch.contains("arm")) {
			libName = "libtoolarm64.so";
		} else {
			libName = "libtool64.so";
		}

		// 3.1 尝试从 classpath / jar 内部解包
		String resourcePath = "/libs/" + libName;
		try (InputStream in = LibTool.class.getResourceAsStream(resourcePath)) {
			if (in != null) {
				byte[] bytes = in.readAllBytes();
				if (bytes.length > 0) {
					File tempDir = new File(System.getProperty("java.io.tmpdir"), "remold_natives");
					if (!tempDir.exists() && !tempDir.mkdirs() && !tempDir.isDirectory()) {
						tempDir = new File(System.getProperty("user.home"), ".remold/natives");
						tempDir.mkdirs();
					}
					File target = new File(tempDir, libName);
					if (!target.exists() || target.length() != bytes.length) {
						try {
							Files.write(target.toPath(), bytes);
						} catch (IOException e) {
							// Windows 上同名 DLL 可能已被其他并发进程锁定，生成独立文件名
							target = new File(tempDir, System.currentTimeMillis() + "_" + Math.abs(new Random().nextInt()) + "_" + libName);
							Files.write(target.toPath(), bytes);
						}
						target.deleteOnExit();
					}
					return target;
				}
			}
		} catch (Throwable ignored) {
		}

		// 3.2 开发环境探测本地相对路径
		String[] searchPaths = {
			"core/libs/" + libName,
			"../core/libs/" + libName,
			"libs/" + libName,
			"../../core/libs/" + libName
		};
		for (String p : searchPaths) {
			File f = new File(p);
			if (f.exists() && f.isFile()) {
				return f.getAbsoluteFile();
			}
		}

		return null;
	}

	private static native Object[] nGetInstances(Class<?> clazz);
	private static native Object[] nGetReferrers(Object targetObject);

	/**
	 * 获取指定类及其子类在堆中所有尚未被回收的实例并返回局部引用数组。
	 * 包含多态子类与接口实现，包含不可达但尚未被 GC 物理回收的对象。
	 */
	@SuppressWarnings("unchecked")
	public synchronized static <T> T[] getInstances(Class<T> clazz) {
		Objects.requireNonNull(clazz, "clazz cannot be null");
		clazz = (Class<T>) ClassIdentity.canonicalize(clazz);
		if (clazz.isPrimitive()) {
			throw new IllegalArgumentException("Primitive types have no heap instances: " + clazz);
		}
		if (clazz.isArray()) {
			throw new IllegalArgumentException("Array types are not supported for instance queries: " + clazz);
		}
		if (!initialized) init();
		T[] res = (T[]) nGetInstances(clazz);
		return res != null ? res : (T[]) Array.newInstance(clazz, 0);
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
