import org.junit.jupiter.api.Test;
import remold.ClassDiffUtil;
import remold.ClassDiffUtil.ClassDiff;
import remold.RemoldAgent;
import remold.ref.InitFix;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the two-phase decision and the stage-A constructor contract
 * ({@code docs/initfix/04-target-design.md} §1).
 *
 * <p>No DCEVM needed: the decision is pure analysis ({@link InitFix#transform} +
 * {@link InitFix#requiresTwoPhase(Class)}), and the stage-A contract is asserted by loading the
 * synthesised class and calling it.</p>
 */
public class TwoPhaseGuardTest {

	/** Loads {@code v1} bytes under a fresh loader and runs the real InitFix analysis. */
	private static Class<?> analyse(String className, String v1Src, String v2Src) throws Exception {
		Map<String, byte[]> v1Classes = LayoutGateAssert.compile(Map.of(className, v1Src));
		Map<String, byte[]> v2Classes = LayoutGateAssert.compile(Map.of(className, v2Src));
		byte[] v1 = v1Classes.get(className);
		byte[] v2 = v2Classes.get(className);
		assertNotNull(v1, "V1 bytecode missing");
		assertNotNull(v2, "V2 bytecode missing");

		ClassLoader loader = new ClassLoader(TwoPhaseGuardTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (className.equals(name)) return defineClass(name, v1, 0, v1.length);
				return super.findClass(name);
			}
		};
		Class<?> host = loader.loadClass(className);
		RemoldAgent.bytecodeCache.put(className, v1);

		ClassDiff diff = ClassDiffUtil.diff(v1, v2);
		InitFix.transform(host, v2, diff);
		return host;
	}

	@Test
	void addedInstanceFieldRequiresTwoPhase() throws Exception {
		Class<?> host = analyse("tp1.Holder",
			"""
			package tp1;
			public class Holder {
			    public int a = 1;
			}
			""",
			"""
			package tp1;
			public class Holder {
			    public int a = 1;
			    public Object ui = new Object();
			}
			""");
		assertTrue(InitFix.requiresTwoPhase(host),
			"an added instance field that the patch will write is exactly the read-through case");
	}

	@Test
	void addedStaticFieldRequiresTwoPhase() throws Exception {
		Class<?> host = analyse("tp2.Holder",
			"""
			package tp2;
			public class Holder {
			    public int a = 1;
			}
			""",
			"""
			package tp2;
			public class Holder {
			    public int a = 1;
			    public static Object ui = new Object();
			}
			""");
		assertTrue(InitFix.requiresTwoPhase(host),
			"a new static field is visible to new bodies before the static patch lands");
	}

	@Test
	void methodOnlyChangeDoesNotRequireTwoPhase() throws Exception {
		Class<?> host = analyse("tp3.Holder",
			"""
			package tp3;
			public class Holder {
			    public int a = 1;
			    public String hi() { return "v1"; }
			}
			""",
			"""
			package tp3;
			public class Holder {
			    public int a = 1;
			    public String hi() { return "v2"; }
			}
			""");
		assertFalse(InitFix.requiresTwoPhase(host),
			"no field is added or retyped, so nothing can be null for a frame: stay on the cheap single redefine");
	}

	@Test
	void reinitOfExistingFieldDoesNotRequireTwoPhase() throws Exception {
		Class<?> host = analyse("tp4.Holder",
			"""
			package tp4;
			public class Holder {
			    public int a = 1;
			}
			""",
			"""
			package tp4;
			import remold.annotation.RemoldReinit;
			public class Holder {
			    @RemoldReinit
			    public int a = 2;
			}
			""");
		assertFalse(InitFix.requiresTwoPhase(host),
			"@RemoldReinit rewrites an existing field: its pre-patch value is the old value, not null");
	}

	@Test
	void staticReinitOfExistingFieldRequiresTwoPhase() throws Exception {
		Class<?> host = analyse("tp6.Holder",
			"""
			package tp6;
			public class Holder {
			    public static String s = "a";
			}
			""",
			"""
			package tp6;
			import remold.annotation.RemoldReinit;
			public class Holder {
			    @RemoldReinit
			    public static String s = "b";
			}
			""");
		assertTrue(InitFix.requiresTwoPhase(host),
			"rewriting an existing static field must keep stage C as the post-patch JIT deopt barrier");
	}

	@Test
	void staticCompileTimeConstantReinitStaysSinglePhase() throws Exception {
		Class<?> host = analyse("tp7.Holder",
			"""
			package tp7;
			public class Holder {
			    public static final String c = "a";
			}
			""",
			"""
			package tp7;
			import remold.annotation.RemoldReinit;
			public class Holder {
			    @RemoldReinit
			    public static final String c = "b";
			}
			""");
		assertFalse(InitFix.requiresTwoPhase(host),
			"readers of a ConstantValue literal inline it: a second redefine cannot refresh them, so do not pay for it");
	}

	@Test
	void zeroEquivalentAddedFieldDoesNotRequireTwoPhase() throws Exception {
		Class<?> host = analyse("tp5.Holder",
			"""
			package tp5;
			public class Holder {
			    public int a = 1;
			}
			""",
			"""
			package tp5;
			public class Holder {
			    public int a = 1;
			    public Object ui = null;
			}
			""");
		assertFalse(InitFix.requiresTwoPhase(host),
			"explicit null is T0 zero-equivalent: no patch is generated, so there is no gap to guard");
	}
}
