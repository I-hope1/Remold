import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import remold.AnnotationTransformer;
import remold.BytecodeTransformerBridge;
import remold.RemoldAgent;

import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for the third defect found on the live client: JBR's enhanced redefine pulls every loaded
 * subclass into the transaction and asks the ClassFileLoadHook for that subclass' current bytes.
 * Remold used to treat that callback like an external redefine (re-weaving it through the Mixin bridge),
 * so JBR got a double-woven "new version" of e.g. {@code MobEntity}; its link/verify then failed and
 * JBR surfaced only a bare {@code InternalError}.
 *
 * <p>The transformer must pass affected (non-target) classes through untouched while an internal
 * redefine is in progress, and still apply bridges for real targets / external redefines.</p>
 */
public class InternalAffectedClassPassthroughTest {

	private static final class CountingBridge implements BytecodeTransformerBridge {
		final AtomicInteger calls = new AtomicInteger();

		@Override
		public byte[] transform(ClassLoader loader, String dotClassName, Class<?> targetClass, byte[] sourceBytecode) {
			calls.incrementAndGet();
			return sourceBytecode == null ? null : sourceBytecode.clone();
		}
	}

	@AfterEach
	public void cleanUp() {
		RemoldAgent.exitInternalRedefine();
	}

	private static byte[] ownClassBytes() throws Exception {
		String resource = "/" + InternalAffectedClassPassthroughTest.class.getName().replace('.', '/') + ".class";
		try (InputStream in = InternalAffectedClassPassthroughTest.class.getResourceAsStream(resource)) {
			return in.readAllBytes();
		}
	}

	@Test
	public void affectedSubclassIsPassedThroughDuringInternalRedefine() throws Exception {
		CountingBridge bridge = new CountingBridge();
		RemoldAgent.addTransformerBridge(bridge);
		try {
			RemoldAgent.enterInternalRedefine(Set.of("net.minecraft.entity.LivingEntity"));
			byte[] out = new AnnotationTransformer().transform(
				InternalAffectedClassPassthroughTest.class.getClassLoader(),
				"net/minecraft/entity/mob/MobEntity",
				InternalAffectedClassPassthroughTest.class,
				null,
				ownClassBytes());
			assertEquals(null, out,
				"an affected (non-target) class must be returned untouched during an internal redefine");
			assertEquals(0, bridge.calls.get(),
				"the Mixin bridge must not run for affected subclasses — that would double-weave them");
		} finally {
			RemoldAgent.removeTransformerBridge(bridge);
		}
	}

	@Test
	public void externalRedefineStillGoesThroughTheBridge() throws Exception {
		CountingBridge bridge = new CountingBridge();
		RemoldAgent.addTransformerBridge(bridge);
		try {
			try {
				new AnnotationTransformer().transform(
					InternalAffectedClassPassthroughTest.class.getClassLoader(),
					"net/minecraft/entity/mob/MobEntity",
					InternalAffectedClassPassthroughTest.class,
					null,
					ownClassBytes());
			} catch (Throwable ignored) {
				// external path may reject the layout of the fixture; bridge invocation is what matters
			}
			org.junit.jupiter.api.Assertions.assertTrue(bridge.calls.get() > 0,
				"external (non-internal-context) redefines must keep the previous bridge behaviour");
		} finally {
			RemoldAgent.removeTransformerBridge(bridge);
		}
	}
}
