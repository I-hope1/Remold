import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Problem B / item 1.5 diagnosis (plain JVM + DCEVM, no Forge).
 *
 * <p>HotSpot static fields live inside the {@code java.lang.Class} mirror object on the heap.
 * The previous run only proved "stack/live lookup vs static-field heap slot" differs. This probe
 * separates "static fields only" from "all heap oops pointing at the redefined Class":
 * ordinary instance fields, array elements, and {@code ConcurrentHashMap}/{@code HashSet} keys
 * are also heap oops and are checked across a structural redefine.</p>
 *
 * <p>Also probes the shadow Class object itself: field metadata, JVMTI heap scan, and
 * {@code retransformClasses(shadow)}.</p>
 */
public class ClassIdentityProbe {
	static int passed = 0, failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static void obs(String msg) {
		System.out.println("   [OBS] " + msg);
	}

	/** Static holder: Class reference in a static field (lives in Holder's Class mirror). */
	static Class<?> staticRef;
	static final class Holder { static Class<?> ref; }

	/** Instance holder: Class reference in an ordinary heap instance field / array element. */
	static final class InstanceHolder {
		Class<?> clsField;
		Class<?>[] arr;
	}
	static InstanceHolder instanceHolder;

	/** Heap collections keyed by canonical Class before the redefine. */
	static ConcurrentHashMap<Class<?>, String> map;
	static HashSet<Class<?>> set;

	static final String V1 = """
		package ci;
		public class Host { public int a = 1; }
		""";
	static final String V2 = """
		package ci;
		public class Host { public int a = 1; public int b = 42; }
		""";

	public static void main(String[] args) throws Exception {
		System.out.println("=== ClassIdentityProbe (item 1.5) ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "instrumentation available");

		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of("ci.Host", V1));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of("ci.Host", V2));
		FixtureLoader loader = new FixtureLoader(v1);
		Class<?> canonical = Class.forName("ci.Host", true, loader);
		Object instance = canonical.getConstructor().newInstance();
		int canonicalId = System.identityHashCode(canonical);
		System.out.println("IDENTITY phase=before canonicalId=" + canonicalId
			+ " instanceClassId=" + System.identityHashCode(instance.getClass())
			+ " instanceClassSame=" + (instance.getClass() == canonical));

		// Prepare heap references BEFORE the redefine: static field, instance field, array,
		// map key, set element — all pointing at the canonical Class.
		staticRef = canonical;
		Holder.ref = canonical;
		instanceHolder = new InstanceHolder();
		instanceHolder.clsField = canonical;
		instanceHolder.arr = new Class<?>[]{canonical};
		map = new ConcurrentHashMap<>();
		map.put(canonical, "canonical-value");
		set = new HashSet<>();
		set.add(canonical);
		obs("pre-redefine: map.size=" + map.size() + " set.size=" + set.size()
			+ " instanceFieldId=" + System.identityHashCode(instanceHolder.clsField)
			+ " arrayId=" + System.identityHashCode(instanceHolder.arr[0]));

		RemoldAgent.bytecodeCache.put("ci.Host", v1.get("ci.Host"));
		RemoldAgent.sourceBytecodeCache.put("ci.Host", v1.get("ci.Host"));
		boolean ok = RemoldAgent.redefineDynamic(canonical, v2.get("ci.Host"));
		check(ok, "structural redefine (add field b) returned true");

		Class<?> after = Class.forName("ci.Host", false, loader);
		int afterId = System.identityHashCode(after);
		Class<?> shadow = Holder.ref;
		System.out.println("IDENTITY phase=after canonicalId=" + canonicalId
			+ " forNameId=" + afterId
			+ " sameClassObject=" + (canonical == after)
			+ " instanceClassId=" + System.identityHashCode(instance.getClass()));
		check(canonical == after, "canonical Class identity unchanged across structural redefine");

		// 1) Ordinary heap instance field + array element.
		int instanceFieldId = System.identityHashCode(instanceHolder.clsField);
		int arrayId = System.identityHashCode(instanceHolder.arr[0]);
		System.out.println("IDENTITY heap-refs phase=after instanceFieldId=" + instanceFieldId
			+ " arrayId=" + arrayId
			+ " instanceFieldSame=" + (instanceHolder.clsField == canonical)
			+ " arraySame=" + (instanceHolder.arr[0] == canonical)
			+ " instanceFieldIsShadow=" + (instanceHolder.clsField == shadow)
			+ " arrayIsShadow=" + (instanceHolder.arr[0] == shadow));
		obs("heap instance field rewritten=" + (instanceHolder.clsField != canonical)
			+ " heap array element rewritten=" + (instanceHolder.arr[0] != canonical));

		// 2) Heap collections: keys inserted before the redefine, never re-put.
		System.out.println("IDENTITY map phase=after size=" + map.size()
			+ " getCanonical=" + map.get(canonical)
			+ " getForName=" + map.get(after)
			+ " getShadow=" + map.get(shadow)
			+ " containsCanonical=" + map.containsKey(canonical)
			+ " containsShadow=" + map.containsKey(shadow));
		for (Class<?> k : map.keySet()) {
			System.out.println("IDENTITY map.key id=" + System.identityHashCode(k)
				+ " sameCanonical=" + (k == canonical)
				+ " isShadow=" + (k == shadow)
				+ " name=" + k.getName());
		}
		System.out.println("IDENTITY set phase=after size=" + set.size()
			+ " containsCanonical=" + set.contains(canonical)
			+ " containsShadow=" + set.contains(shadow));
		for (Class<?> k : set) {
			System.out.println("IDENTITY set.key id=" + System.identityHashCode(k)
				+ " sameCanonical=" + (k == canonical)
				+ " isShadow=" + (k == shadow));
		}
		obs("map.get(canonical)=" + map.get(canonical)
			+ " map.get(shadow)=" + map.get(shadow)
			+ " set.contains(canonical)=" + set.contains(canonical)
			+ " set.contains(shadow)=" + set.contains(shadow));

		// 3) Shadow Class metadata / JVMTI / retransform behaviour.
		String shadowFields = fieldNames(shadow);
		String canonicalFields = fieldNames(canonical);
		System.out.println("IDENTITY shadow-meta shadowId=" + System.identityHashCode(shadow)
			+ " shadowName=" + shadow.getName()
			+ " shadowLoaderSame=" + (shadow.getClassLoader() == canonical.getClassLoader())
			+ " shadowFields=" + shadowFields
			+ " canonicalFields=" + canonicalFields);
		obs("shadow.getDeclaredFields() sees added field b=" + hasField(shadow, "b")
			+ " (canonical sees b=" + hasField(canonical, "b") + ")");
		try {
			Object[] foundShadow = remold.util.LibTool.getInstances(shadow);
			obs("LibTool.getInstances(shadow) count="
				+ (foundShadow == null ? "null" : foundShadow.length));
			Object[] foundCanonical = remold.util.LibTool.getInstances(canonical);
			obs("LibTool.getInstances(canonical) count="
				+ (foundCanonical == null ? "null" : foundCanonical.length));
		} catch (Throwable t) {
			obs("LibTool.getInstances unavailable: " + t);
		}
		try {
			inst.retransformClasses(shadow);
			obs("retransformClasses(shadow): returned normally (no exception)");
		} catch (Throwable t) {
			obs("retransformClasses(shadow) threw: " + t.getClass().getName() + ": " + t.getMessage());
		}

		System.out.println("=== SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	static String fieldNames(Class<?> type) {
		StringBuilder sb = new StringBuilder("[");
		for (java.lang.reflect.Field f : type.getDeclaredFields()) {
			if (sb.length() > 1) sb.append(',');
			sb.append(f.getName());
		}
		return sb.append(']').toString();
	}

	static boolean hasField(Class<?> type, String name) {
		try {
			type.getDeclaredField(name);
			return true;
		} catch (NoSuchFieldException e) {
			return false;
		}
	}

	static final class FixtureLoader extends ClassLoader {
		final Map<String, byte[]> classes;

		FixtureLoader(Map<String, byte[]> classes) {
			super(ClassIdentityProbe.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}
