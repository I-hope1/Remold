package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch-path wiring for the two-phase deopt barrier on existing static-field rewrites.
 *
 * <p>{@code requiresTwoPhase} returning true is not enough: the batch driver must actually run
 * stage A → patch → stage C. This test drives the real {@code RemoldAgent.applyTwoPhase} with a
 * recording {@link Instrumentation} and asserts the two {@code redefineClasses} calls plus the
 * patched static value.</p>
 */
class BatchTwoPhaseStaticRewriteTest {

	private static final String NAME = "probe.batch.StaticTarget";

	private static final String V1 = """
		package probe.batch;
		public class StaticTarget {
		    public static String s = "a";
		    public static String tag() { return "v1"; }
		}
		""";

	private static final String V2 = """
		package probe.batch;
		import remold.annotation.RemoldReinit;
		public class StaticTarget {
		    @RemoldReinit
		    public static String s = "b";
		    public static String tag() { return "v2"; }
		}
		""";

	@Test
	void batchDriverRunsStageAThenPatchThenStageCForStaticReinit() throws Exception {
		Map<String, byte[]> v1Map = Fixtures.compile(Map.of(NAME, V1));
		Map<String, byte[]> v2Map = Fixtures.compile(Map.of(NAME, V2));
		byte[] v1 = v1Map.get(NAME);
		byte[] v2 = v2Map.get(NAME);

		ClassLoader loader = new ClassLoader(BatchTwoPhaseStaticRewriteTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (name.equals(NAME)) return defineClass(name, v1, 0, v1.length);
				return super.findClass(name);
			}
		};
		Class<?> host = Class.forName(NAME, true, loader);
		assertEquals("a", host.getField("s").get(null), "precondition: v1 static value");

		RemoldAgent.bytecodeCache.put(NAME, v1);
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(v1, v2);
		remold.ref.InitFix.transform(host, v2, diff);
		assertTrue(remold.ref.InitFix.requiresTwoPhase(host),
			"precondition: existing static-field rewrite must request two-phase");

		List<byte[]> redefinitions = new ArrayList<>();
		Instrumentation previous = RemoldAgent.swapInstrumentationForTest(recorder(redefinitions));
		try {
			Method applyTwoPhase = RemoldAgent.class.getDeclaredMethod("applyTwoPhase",
				List.class, Map.class, java.util.Collection.class, RemoldAgent.RoundSkips.class);
			applyTwoPhase.setAccessible(true);
			Object outcome = applyTwoPhase.invoke(null,
				List.of(new ClassDefinition(host, v2)), Map.of(NAME, v1), List.of(),
				new RemoldAgent.RoundSkips());

			assertEquals(2, redefinitions.size(),
				"the batch driver must call redefineClasses twice (stage A + stage C), actual=" + redefinitions.size());
			assertEquals("v1", tagBody(redefinitions.get(0)),
				"stage A must keep the old method body");
			assertEquals("v2", tagBody(redefinitions.get(1)),
				"stage C must carry the new method body");
			assertEquals("b", host.getField("s").get(null),
				"the stage-B patch must have written the new static value before stage C");
			String successful = String.valueOf(readSuccessful(outcome));
			assertTrue(successful.contains(NAME), "the class must be reported as redefined, outcome=" + successful);
		} finally {
			RemoldAgent.swapInstrumentationForTest(previous);
			RemoldAgent.bytecodeCache.remove(NAME);
			remold.ref.InitFix.afterRedefineFailed(host);
		}
	}

	private static Object readSuccessful(Object outcome) {
		try {
			Method m = outcome.getClass().getDeclaredMethod("successful");
			m.setAccessible(true);
			return m.invoke(outcome);
		} catch (Throwable t) {
			return "ERR " + t;
		}
	}

	private static String tagBody(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		for (MethodNode mn : cn.methods) {
			if (!mn.name.equals("tag")) continue;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) return s;
			}
		}
		return null;
	}

	/** Records redefineClasses bytes; all other Instrumentation methods return neutral values. */
	private static Instrumentation recorder(List<byte[]> redefinitions) {
		InvocationHandler handler = (proxy, method, args) -> {
			switch (method.getName()) {
				case "isRedefineClassesSupported":
				case "isModifiableClass":
				case "isRetransformClassesSupported":
					return true;
				case "redefineClasses": {
					for (ClassDefinition definition : (ClassDefinition[]) args[0]) {
						redefinitions.add(definition.getDefinitionClassFile());
					}
					return null;
				}
				case "getAllLoadedClasses":
				case "getInitiatedClasses":
					return new Class<?>[0];
				case "getObjectSize":
					return 0L;
				case "toString":
					return "RecordingInstrumentation";
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == args[0];
				default:
					return defaultValue(method.getReturnType());
			}
		};
		return (Instrumentation) Proxy.newProxyInstance(
			Instrumentation.class.getClassLoader(), new Class<?>[] { Instrumentation.class }, handler);
	}

	private static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive()) return null;
		if (type == boolean.class) return false;
		if (type == void.class) return null;
		if (type == long.class) return 0L;
		if (type == float.class) return 0f;
		if (type == double.class) return 0d;
		if (type == char.class) return (char) 0;
		if (type == byte.class) return (byte) 0;
		if (type == short.class) return (short) 0;
		return 0;
	}
}
