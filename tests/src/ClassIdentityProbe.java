import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;
import java.util.Map;

/**
 * Problem B diagnosis (plain JVM + DCEVM, no Forge): does a structural redefine
 * (adding an instance field) change the identity of the {@code Class} object?
 *
 * <p>Terminal observation: {@code Class} reference identity before vs after
 * {@link RemoldAgent#redefineDynamic}, the instance's own class identity, and whether the
 * added field is visible through the pre-redefine {@code Class} reference.</p>
 */
public class ClassIdentityProbe {
	static int passed = 0, failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	/** Static holder: does a structural redefine rewrite Class references in static fields? */
	static Class<?> staticRef;
	static final class Holder { static Class<?> ref; }

	static final String V1 = """
		package ci;
		public class Host { public int a = 1; }
		""";
	static final String V2 = """
		package ci;
		public class Host { public int a = 1; public int b = 42; }
		""";

	public static void main(String[] args) throws Exception {
		System.out.println("=== ClassIdentityProbe ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "instrumentation available");

		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of("ci.Host", V1));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of("ci.Host", V2));
		FixtureLoader loader = new FixtureLoader(v1);
		Class<?> before = Class.forName("ci.Host", true, loader);
		Object instance = before.getConstructor().newInstance();

		int idBefore = System.identityHashCode(before);
		System.out.println("IDENTITY phase=before classId=" + idBefore
			+ " instanceClassId=" + System.identityHashCode(instance.getClass())
			+ " instanceClassSame=" + (instance.getClass() == before));

		staticRef = before;
		Holder.ref = before;
		RemoldAgent.bytecodeCache.put("ci.Host", v1.get("ci.Host"));
		RemoldAgent.sourceBytecodeCache.put("ci.Host", v1.get("ci.Host"));
		boolean ok = RemoldAgent.redefineDynamic(before, v2.get("ci.Host"));
		check(ok, "structural redefine (add field b) returned true");

		Class<?> after = Class.forName("ci.Host", false, loader);
		int idAfter = System.identityHashCode(after);
		System.out.println("IDENTITY phase=after classId=" + idAfter
			+ " sameClassObject=" + (before == after)
			+ " instanceClassId=" + System.identityHashCode(instance.getClass())
			+ " instanceStillSameClass=" + (instance.getClass() == before));

		System.out.println("IDENTITY refs phase=after localId=" + System.identityHashCode(before)
			+ " staticFieldId=" + System.identityHashCode(staticRef)
			+ " holderStaticId=" + System.identityHashCode(Holder.ref)
			+ " instanceClassId=" + System.identityHashCode(instance.getClass())
			+ " forNameId=" + System.identityHashCode(after)
			+ " staticSame=" + (staticRef == before)
			+ " holderSame=" + (Holder.ref == before)
			+ " staticEqForName=" + (staticRef == after)
			+ " holderEqForName=" + (Holder.ref == after)
			+ " forNameEqLocal=" + (after == before)
			+ " staticName=" + staticRef.getName()
			+ " forNameName=" + after.getName()
			+ " staticStable=" + (System.identityHashCode(staticRef) == System.identityHashCode(staticRef)));

		// Control 2: method-body-only redefine (no structural change) — does it also rewrite refs?
		Map<String, byte[]> v3 = LayoutGateAssert.compile(Map.of("ci.Host", """
			package ci;
			public class Host { public int a = 1; public int b = 42; public int bodyOnly() { return 7; } }
			"""));
		Class<?> ctrl = Class.forName("ci.Host", false, loader);
		ClassIdentityProbe.staticRef = ctrl;
		Holder.ref = ctrl;
		RemoldAgent.bytecodeCache.put("ci.Host", v2.get("ci.Host"));
		RemoldAgent.sourceBytecodeCache.put("ci.Host", v2.get("ci.Host"));
		boolean ok3 = RemoldAgent.redefineDynamic(ctrl, v3.get("ci.Host"));
		Class<?> ctrlAfter = Class.forName("ci.Host", false, loader);
		System.out.println("IDENTITY method-only phase=after ok=" + ok3
			+ " localId=" + System.identityHashCode(ctrl)
			+ " staticFieldId=" + System.identityHashCode(ClassIdentityProbe.staticRef)
			+ " holderStaticId=" + System.identityHashCode(Holder.ref)
			+ " forNameId=" + System.identityHashCode(ctrlAfter)
			+ " staticSame=" + (ClassIdentityProbe.staticRef == ctrl)
			+ " holderSame=" + (Holder.ref == ctrl));
		check(before == after, "canonical Class identity unchanged across structural redefine");
		// OBSERVATION (not an assertion): JBR/DCEVM rewrites Class references held in static
		// fields to a *different* Class object of the same name; see the report below.
		System.out.println("   [OBS] static-field Class reference rewritten: staticRefSame="
			+ (staticRef == before) + " holderSame=" + (Holder.ref == before));
		check(instance.getClass() == before, "instance.getClass() still the same Class object");
		check(hasField(before, "b"), "added field b is visible through the same Class reference");
		check(hasField(after, "b"), "added field b is visible through a fresh Class.forName lookup");

		// Also compare the heap-scan entry point, which is keyed by Class.
		try {
			Object[] found = remold.util.LibTool.getInstances(before);
			check(found != null && found.length >= 1,
				"LibTool.getInstances(oldClass) still finds the live instance (count="
					+ (found == null ? "null" : found.length) + ")");
		} catch (Throwable t) {
			System.out.println("   [OBS] LibTool.getInstances unavailable: " + t);
		}

		// Control 3: direct Instrumentation.redefineClasses, no Remold code at all.
		Map<String, byte[]> d1 = LayoutGateAssert.compile(Map.of("ci.Direct", """
			package ci;
			public class Direct { public int a = 1; }
			"""));
		Map<String, byte[]> d2 = LayoutGateAssert.compile(Map.of("ci.Direct", """
			package ci;
			public class Direct { public int a = 1; public int b = 2; }
			"""));
		FixtureLoader directLoader = new FixtureLoader(d1);
		Class<?> direct = Class.forName("ci.Direct", true, directLoader);
		ClassIdentityProbe.staticRef = direct;
		Holder.ref = direct;
		inst.redefineClasses(new java.lang.instrument.ClassDefinition(direct, d2.get("ci.Direct")));
		Class<?> directAfter = Class.forName("ci.Direct", false, directLoader);
		System.out.println("IDENTITY direct-redefine phase=after"
			+ " localId=" + System.identityHashCode(direct)
			+ " staticFieldId=" + System.identityHashCode(ClassIdentityProbe.staticRef)
			+ " holderStaticId=" + System.identityHashCode(Holder.ref)
			+ " forNameId=" + System.identityHashCode(directAfter)
			+ " staticSame=" + (ClassIdentityProbe.staticRef == direct)
			+ " holderSame=" + (Holder.ref == direct)
			+ " forNameSame=" + (directAfter == direct));
		System.out.println("   [OBS] direct Instrumentation.redefineClasses: staticRefSame="
			+ (ClassIdentityProbe.staticRef == direct)
			+ " holderSame=" + (Holder.ref == direct)
			+ " canonicalSame=" + (directAfter == direct));

		System.out.println("=== SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
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
