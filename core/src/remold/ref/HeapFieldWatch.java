package remold.ref;

import java.lang.reflect.Field;

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
			Class<?> target = Class.forName(CLASS_NAME, false, ClassLoader.getSystemClassLoader());
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
				+ " class=" + CLASS_NAME + " field=" + FIELD_NAME
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
