package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L18: only the innermost anonymous class changes, so the enclosing local class is loaded but not
 * submitted to Stage A. {@code stageAConsistent} must not treat that unchanged host as a half-applied
 * group (which produced a spurious {@code ROLLED-BACK-A} and dropped the anonymous class).
 *
 * <p>The production path is driven directly: {@code redefineStageA} is the code that decides
 * {@code ok}/{@code ROLLED-BACK-A}. A helper-only test would not see a regression at the call site.</p>
 */
class StageARequestedScopeTest {

	private static final String HOST = "probe.stagea.LocalBox";
	private static final String ANON = HOST + "$1";

	private final Instrumentation originalInstrumentation = RemoldAgent.getInstrumentation();
	private final RecordingInstrumentation instrumentation = new RecordingInstrumentation();

	@BeforeEach
	void install() {
		RemoldAgent.swapInstrumentationForTest(this.instrumentation.proxy);
	}

	@AfterEach
	void cleanup() {
		RemoldAgent.swapInstrumentationForTest(this.originalInstrumentation);
		RemoldAgent.loadedClassesMap.remove(HOST);
		RemoldAgent.loadedClassesMap.remove(ANON);
		RemoldAgent.bytecodeCache.remove(ANON);
		AnnotationTransformer.pendingAlignedClasses.clear();
	}

	/** L18 production path: unchanged host must not be reported as ROLLED-BACK-A. */
	@Test
	void unchangedLoadedHostDoesNotAbandonTheAnonymousClass() {
		FixtureLoader loader = new FixtureLoader(StageARequestedScopeTest.class.getClassLoader());
		Class<?> host = loader.define(HOST, classBytes(HOST.replace('.', '/'), "java/lang/Object"));
		Class<?> anon = loader.define(ANON, classBytes(ANON.replace('.', '/'), HOST.replace('.', '/')));
		assertFixturePrecondition(host, anon);
		RemoldAgent.loadedClassesMap.put(HOST, host);
		RemoldAgent.loadedClassesMap.put(ANON, anon);

		RemoldAgent.AlignmentTransaction tx = new RemoldAgent.AlignmentTransaction(HOST);
		tx.targetClasses.add(ANON);
		RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();
		Set<String> abandoned = new HashSet<>();
		ClassDefinition anonDefinition = new ClassDefinition(anon,
		 classBytes(ANON.replace('.', '/'), HOST.replace('.', '/')));

		Set<String> ok = TwoPhaseCoordinator.redefineStageA(
		 List.of(anonDefinition), Map.of(ANON, anonDefinition.getDefinitionClassFile()),
		 List.of(tx), skips, Set.of(), abandoned);

		assertTrue(ok.contains(ANON), "the changed anonymous class must stay in stage A: " + ok);
		assertTrue(abandoned.isEmpty(), "an unchanged loaded host must not abandon the group: " + abandoned);
		assertNull(skips.get(HOST), "no ROLLED-BACK-A may be recorded for an unchanged host: " + skips);
		assertFalse(instrumentation.redefinitions.isEmpty(), "stage A really reached redefineClasses");
	}

	/** A host submitted to stage A but missing from ok still rolls the group back. */
	@Test
	void requestedHostMissingFromOkStillRollsBack() {
		assertFalse(TwoPhaseCoordinator.stageAConsistent(
		 HOST, Set.of(ANON), Set.of(ANON), Set.of(), Set.of(HOST, ANON), Set.of(HOST, ANON)),
		 "a requested host that failed stage A must still be rejected");
	}

	/** A changed anonymous target submitted to stage A but missing from ok still rolls the group back. */
	@Test
	void requestedAnonMissingFromOkStillRollsBack() {
		assertFalse(TwoPhaseCoordinator.stageAConsistent(
		 HOST, Set.of(ANON), Set.of(HOST), Set.of(), Set.of(HOST, ANON), Set.of(HOST, ANON)),
		 "a requested anonymous class that failed stage A must still be rejected");
	}

	/** Relaxing the host must not relax a loaded anonymous target: it still needs an ok entry. */
	@Test
	void loadedUnsubmittedAnonStillNeedsAnOkEntry() {
		assertFalse(TwoPhaseCoordinator.stageAConsistent(
		 HOST, Set.of(ANON), Set.of(HOST), Set.of(), Set.of(HOST, ANON), Set.of(HOST)),
		 "only the unchanged host is exempt; a loaded anonymous target is still checked");
	}

	private static void assertFixturePrecondition(Class<?> host, Class<?> anon) {
		// Keep the fixture real: both classes are genuinely loaded by the same fixture loader.
		assertTrue(host != null && anon != null);
		assertTrue(host.getClassLoader() == anon.getClassLoader());
	}

	private static byte[] classBytes(String internalName, String superName) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, superName, null);
		MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static final class FixtureLoader extends ClassLoader {
		FixtureLoader(ClassLoader parent) {
			super(parent);
		}

		Class<?> define(String dotName, byte[] bytes) {
			return defineClass(dotName, bytes, 0, bytes.length);
		}
	}

	private static final class RecordingInstrumentation implements InvocationHandler {
		final List<ClassDefinition[]> redefinitions = new ArrayList<>();
		final Instrumentation proxy = (Instrumentation) Proxy.newProxyInstance(
		 StageARequestedScopeTest.class.getClassLoader(), new Class<?>[] {Instrumentation.class}, this);

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			return switch (method.getName()) {
				case "isRedefineClassesSupported", "isRetransformClassesSupported",
				     "isModifiableClass", "isRetransformClassesSupportedByDefault" -> true;
				case "getAllLoadedClasses", "getInitiatedClasses" -> new Class<?>[0];
				case "redefineClasses" -> {
					this.redefinitions.add((ClassDefinition[]) args[0]);
					yield null;
				}
				case "addTransformer", "removeTransformer" -> true;
				case "getObjectSize" -> 0L;
				default -> defaultFor(method.getReturnType());
			};
		}

		private static Object defaultFor(Class<?> type) {
			if (!type.isPrimitive()) return null;
			if (type == boolean.class) return false;
			if (type == long.class) return 0L;
			return 0;
		}
	}
}
