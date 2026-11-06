package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import remoldtest.BridgeCountTarget;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Counting-bridge regression for the external-redefine channels.
 *
 * <p>The Mixin bridge must run only for a real source-level redefine. Two channels hand the
 * transformer bytes that are <b>already</b> woven and must not be re-woven:</p>
 * <ul>
 *   <li>baseline capture ({@code retransformLoaded} / {@code enterBaselineCapture}) — the
 *       C2 fix: the JVM input is the current runtime bytes, re-weaving appends hash-mangled
 *       duplicate {@code @Unique} fields;</li>
 *   <li>an internal retransform ({@code enterInternalRetransform}, e.g. the entity-probe
 *       toggle) — the class is not being hot-swapped at all, so Remold must leave it alone.</li>
 * </ul>
 *
 * <p>The positive control calls the same bridge function directly and asserts the counter
 * moves, so a silent no-op cannot make this test pass.</p>
 */
public class AnnotationTransformerBridgeCountTest {

	static final class CountingBridge implements BytecodeTransformerBridge {
		int calls;

		@Override
		public byte[] transform(ClassLoader loader, String dotClassName, Class<?> targetClass, byte[] bytecode) {
			calls++;
			return bytecode;
		}
	}

	private static byte[] classBytes(Class<?> type) throws Exception {
		try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
			assertNotNull(in, "class resource must be on the test classpath");
			return in.readAllBytes();
		}
	}

	private static byte[] withExtraMethod(byte[] bytes) {
		ClassReader reader = new ClassReader(bytes);
		ClassWriter writer = new ClassWriter(reader, 0);
		reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public void visitEnd() {
				MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
					"addedForBridgeCount", "()V", null, null);
				mv.visitCode();
				mv.visitInsn(Opcodes.RETURN);
				mv.visitMaxs(0, 0);
				mv.visitEnd();
				super.visitEnd();
			}
		}, 0);
		return writer.toByteArray();
	}

	@Test
	void bridgeRunsOnlyForRealSourceRedefines() throws Exception {
		String name = BridgeCountTarget.class.getName();
		String slashName = name.replace('.', '/');
		ClassLoader loader = BridgeCountTarget.class.getClassLoader();
		byte[] original = classBytes(BridgeCountTarget.class);
		CountingBridge bridge = new CountingBridge();
		RemoldAgent.addTransformerBridge(bridge);
		try {
			AnnotationTransformer transformer = new AnnotationTransformer();

			// Positive control: the bridge is registered and its counter moves when invoked.
			RemoldAgent.applyTransformerBridges(loader, name, BridgeCountTarget.class, original);
			assertEquals(1, bridge.calls, "positive control: the counting bridge must be callable");

			// C2 regression: baseline capture must not re-weave already-woven bytes.
			RemoldAgent.bytecodeCache.put(name, original);
			RemoldAgent.sourceBytecodeCache.put(name, original);
			RemoldAgent.enterBaselineCapture();
			try {
				transformer.transform(loader, slashName, BridgeCountTarget.class, null, original);
			} finally {
				RemoldAgent.exitInternalRedefine();
			}
			assertEquals(1, bridge.calls, "baseline capture must not invoke the Mixin bridge");

			// C1 entry: an internal retransform is not a Remold hot-swap; the transformer must
			// return null (leave the class untouched) and must not invoke the bridge.
			RemoldAgent.enterInternalRetransform(Set.of(name));
			try {
				byte[] out = transformer.transform(loader, slashName, BridgeCountTarget.class, null, original);
				assertNull(out, "internal retransform must be passed through untouched");
			} finally {
				RemoldAgent.exitInternalRedefine();
			}
			assertEquals(1, bridge.calls, "internal retransform must not invoke the Mixin bridge");

			// Negative-input control: a real source-level change (different bytes) does call it.
			byte[] changed = withExtraMethod(original);
			RemoldAgent.bytecodeCache.put(name, original);
			RemoldAgent.sourceBytecodeCache.put(name, original);
			transformer.transform(loader, slashName, BridgeCountTarget.class, null, changed);
			assertEquals(2, bridge.calls, "a real external redefine must invoke the Mixin bridge");
		} finally {
			RemoldAgent.removeTransformerBridge(bridge);
			RemoldAgent.bytecodeCache.remove(name);
			RemoldAgent.sourceBytecodeCache.remove(name);
		}
	}
}
