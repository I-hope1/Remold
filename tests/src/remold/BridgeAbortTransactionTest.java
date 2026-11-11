package remold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Seam suite for {@link BytecodeTransformerBridge.Abort}: the signal a bridge uses to say
 * "do not redefine this class in this round".
 *
 * <p><b>Why this needs its own suite</b>: before it existed, the only way a bridge could express
 * "I refuse" was to return bytes — and every return value was wrong. Returning the woven bytes
 * sends the JVM a class whose new call sites reference members a sibling class does not have;
 * returning the un-woven source bytes redefines the class without its mixin hooks. The signal is
 * therefore only useful if it survives two layers: {@link RemoldAgent#applyTransformerBridges} must
 * rethrow it (instead of folding it into the "keep the previous bytes" fallback), and
 * {@link RemoldAgent#redefineDynamic} must turn it into "no {@code redefineClasses} at all".</p>
 *
 * <p><b>Controls</b>: a bridge that throws an ordinary {@link RuntimeException} must still be
 * swallowed into "keep the previous bytes" — that is the pre-existing contract for bridge failures
 * and it must not have been widened into "any exception aborts". A recording
 * {@link Instrumentation} counts {@code redefineClasses} calls, so the abort assertion is about the
 * call never happening, not about a return value.</p>
 */
class BridgeAbortTransactionTest {

	/** Host fixture: any ordinary class the redefine entry point can be pointed at. */
	static final class Fixture {
		int value;
	}

	@Test
	void theAbortSignalSurvivesTheBridgeLoopWhileOrdinaryFailuresStillFallBack() {
		byte[] bytes = sourceBytesOf(Fixture.class);
		BytecodeTransformerBridge aborting = (loader, name, target, input) -> {
			throw new BytecodeTransformerBridge.Abort("synthetic sibling refused");
		};
		BytecodeTransformerBridge failing = (loader, name, target, input) -> {
			throw new IllegalStateException("ordinary bridge failure");
		};
		RemoldAgent.addTransformerBridge(aborting);
		try {
			BytecodeTransformerBridge.Abort abort = assertThrows(BytecodeTransformerBridge.Abort.class,
				() -> RemoldAgent.applyTransformerBridges(Fixture.class.getClassLoader(),
					Fixture.class.getName(), Fixture.class, bytes),
				"an abort must reach the caller, not be folded into the byte fallback");
			assertTrue(abort.getMessage().contains("synthetic sibling refused"),
				"the abort reason must survive verbatim so the log can name the cause");
		} finally {
			RemoldAgent.removeTransformerBridge(aborting);
		}

		// Control: the same call shape with an ordinary failure keeps the previous bytes (the
		// pre-existing contract). This is what makes the case above specific to Abort.
		RemoldAgent.addTransformerBridge(failing);
		try {
			byte[] out = RemoldAgent.applyTransformerBridges(Fixture.class.getClassLoader(),
				Fixture.class.getName(), Fixture.class, bytes);
			assertSame(bytes, out, "an ordinary bridge failure must still fall back to the previous bytes");
		} finally {
			RemoldAgent.removeTransformerBridge(failing);
		}
	}

	@Test
	void anAbortedRedefineNeverReachesRedefineClasses() {
		byte[] bytes = sourceBytesOf(Fixture.class);
		AtomicInteger bridgeCalls = new AtomicInteger();
		BytecodeTransformerBridge aborting = (loader, name, target, input) -> {
			bridgeCalls.incrementAndGet();
			throw new BytecodeTransformerBridge.Abort("transactional consistency");
		};
		RecordingInstrumentation recorder = new RecordingInstrumentation();
		Instrumentation previous = RemoldAgent.swapInstrumentationForTest(recorder.proxy);
		RemoldAgent.addTransformerBridge(aborting);
		try {
			assertFalse(RemoldAgent.redefineDynamic(Fixture.class, bytes),
				"the single-class entry point must report failure when the bridge aborts");
			assertEquals(1, bridgeCalls.get(), "the bridge must have been consulted exactly once");
			assertEquals(0, recorder.redefinitions.size(),
				"an aborted redefine must not reach redefineClasses at all");
		} finally {
			RemoldAgent.removeTransformerBridge(aborting);
			RemoldAgent.swapInstrumentationForTest(previous);
		}
	}

	private static byte[] sourceBytesOf(Class<?> type) {
		String resource = "/" + type.getName().replace('.', '/') + ".class";
		try (java.io.InputStream in = type.getResourceAsStream(resource)) {
			if (in == null) {
				throw new AssertionError("fixture bytes not found on the classpath: " + resource);
			}
			return in.readAllBytes();
		} catch (java.io.IOException e) {
			throw new AssertionError("cannot read fixture bytes: " + resource, e);
		}
	}

	/** Recording {@link Instrumentation}: counts the redefine calls that leave the production path. */
	private static final class RecordingInstrumentation implements InvocationHandler {
		final List<ClassDefinition> redefinitions = new ArrayList<>();
		final Instrumentation proxy;

		RecordingInstrumentation() {
			this.proxy = (Instrumentation) Proxy.newProxyInstance(
				Instrumentation.class.getClassLoader(), new Class<?>[]{Instrumentation.class}, this);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			String name = method.getName();
			if ("redefineClasses".equals(name)) {
				for (ClassDefinition definition : (ClassDefinition[]) args[0]) {
					this.redefinitions.add(definition);
				}
				return null;
			}
			if ("isRedefineClassesSupported".equals(name) || "isRetransformClassesSupported".equals(name)
				|| "isModifiableClass".equals(name)) {
				return Boolean.TRUE;
			}
			if ("getAllLoadedClasses".equals(name)) {
				return new Class<?>[]{Fixture.class};
			}
			if ("toString".equals(name)) {
				return "RecordingInstrumentation";
			}
			if ("hashCode".equals(name)) {
				return System.identityHashCode(proxy);
			}
			if ("equals".equals(name)) {
				return proxy == args[0];
			}
			Class<?> returnType = method.getReturnType();
			if (returnType == boolean.class) {
				return Boolean.FALSE;
			}
			if (returnType == int.class) {
				return 0;
			}
			if (returnType == long.class) {
				return 0L;
			}
			return null;
		}
	}
}
