package remold.ref;

import java.lang.reflect.Field;
import java.lang.instrument.Instrumentation;

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
			Object[] instances = LibTool.getInstances(target);
			int total = 0;
			int nulls = 0;
			StringBuilder firstNull = null;
			for (Object instance : instances) {
				if (instance == null) {
					continue;
				}
				total++;
				Object value = readField(instance, FIELD_NAME);
				if (value == null) {
					nulls++;
					if (firstNull == null) {
						firstNull = new StringBuilder()
							.append("identity=").append(System.identityHashCode(instance))
							.append(" conditions=").append(describe(readField(instance, "conditions")));
					}
				}
			}
			nullHits += nulls;
			RemoldAgent.info("[HEAPWATCH] phase=" + phase + " redefining=" + redefiningClass
				+ " class=" + CLASS_NAME + " loader=" + resolvedVia + " field=" + FIELD_NAME
				+ " total=" + total + " nullCount=" + nulls
				+ (firstNull == null ? "" : " firstNull{" + firstNull + "}"));
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				RemoldAgent.warn("[HEAPWATCH] disabled after failure: " + t);
			}
		}
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

	private static Object readField(Object instance, String name) {
		for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f.get(instance);
			} catch (NoSuchFieldException ignored) {
				// 继续向父类找
			} catch (Throwable t) {
				return "<unreadable: " + t + ">";
			}
		}
		return "<absent>";
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
