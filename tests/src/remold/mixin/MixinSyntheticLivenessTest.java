package remold.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import remold.BytecodeTransformerBridge;
import remold.RemoldAgent;

/**
 * Liveness suite: whether a synthetic inner class may be redefined in place depends on <b>how many
 * of its instances are still alive</b>, not only on how its field table changed.
 *
 * <p><b>The three tiers</b> (same shape as {@code AnonClassAligner}'s layout gate):</p>
 * <ol>
 *   <li>{@code fieldTableChanged == false} — only the body changed. Live instances and new
 *       instances both take effect in place, so it is allowed <b>regardless</b> of liveness
 *       (case C).</li>
 *   <li>{@code fieldTableChanged == true} + a live instance — the old instance has no value for the
 *       new capture, so after the JVM migrates it the new field reads {@code 0/null} (the
 *       half-migrated instance measured by {@code MixinSyntheticGateTest}'s expansion case), and the
 *       migration itself rewrites unrelated heaps. Refused, and the host target is aborted with it
 *       (case A).</li>
 *   <li>{@code fieldTableChanged == true} + no live instance — nothing can be left half-migrated.
 *       Allowed, so a synthetic class that is only created transiently can still change its capture
 *       list without a restart (case B). This is the branch the real-machine P0 run decides: if a
 *       real {@code redefineClasses} still pollutes unrelated heaps with zero instances, this branch
 *       must be folded into the refusal.</li>
 * </ol>
 *
 * <p><b>Two layers of evidence.</b> Cases A/B/C drive the real gate through a recording
 * {@link Instrumentation} with a <b>substituted</b> instance probe, so the truth table is
 * deterministic on any machine (no native library, no GC timing). {@link
 * #theProductProbeDistinguishesHeldInstancesFromCollectedOnes} then runs the <b>product</b> probe
 * ({@code LibTool.getInstances}, JVMTI heap walk) against a real heap: one strongly held instance
 * must read {@code reachable > 0}, and after dropping the reference plus a GC it must read
 * {@code reachable == 0}. Without that layer the substituted probe would only prove the gate reads a
 * number, not that the number means what the gate assumes.</p>
 */
class MixinSyntheticLivenessTest {

	@AfterEach
	void restoreProductProbe() {
		MixinInnerClassHotSwap.setInstanceProbeForTest(null);
	}

	/**
	 * Case A — field table grows while an instance is still reachable: refuse, and abort the host.
	 */
	@Test
	void aGrowingFieldTableIsRefusedWhileAnInstanceIsStillReachable() {
		Class<?> live = MixinSyntheticHalfInitFixtures.loadInFreshLoader(
			MixinSyntheticHalfInitFixtures.oldShapeBytes());
		Object[] holder = {MixinSyntheticHalfInitFixtures.newInstance(live,
			new Class<?>[]{Object.class, Object.class}, "host", "captured")};
		byte[] candidate = MixinSyntheticHalfInitFixtures.expandedCandidate().bytes();
		String host = "livenessfix.Target$HostA";
		// Gate 4 pre-satisfied so the refusal below can only come from the liveness gate.
		MixinClassBaseline.recordForTest(live.getClassLoader(), MixinSyntheticHalfInitFixtures.OLD_DOTTED,
			MixinSyntheticHalfInitFixtures.oldShapeBytes());

		RecordingInstrumentation recorder = new RecordingInstrumentation(new Class<?>[]{live});
		Instrumentation previous = RemoldAgent.swapInstrumentationForTest(recorder.proxy);
		MixinInnerClassHotSwap.setInstanceProbeForTest(
			type -> holder[0] == null ? new Object[0] : new Object[]{holder[0]});
		BytecodeTransformerBridge bridge = new MixinBytecodeBridge();
		RemoldAgent.addTransformerBridge(bridge);
		try {
			int[] before = metrics();
			boolean executed = MixinInnerClassHotSwap.redefineSyntheticSafely(
				recorder.proxy, live, MixinSyntheticHalfInitFixtures.OLD_DOTTED, candidate, host);
			int[] after = metrics();

			assertFalse(executed, "a growing capture list must be refused while an instance is alive");
			assertEquals(0, after[0] - before[0], "no redefine may execute");
			assertEquals(1, after[2] - before[2], "the liveness gate must count exactly one rejection");
			assertEquals(1, after[1] - before[1],
				"the field-table change must still be reported (the front line reads it to know what happened)");
			assertTrue(recorder.redefinitions.isEmpty(), "the synthetic class must not reach redefineClasses");
			assertTrue(MixinInnerClassHotSwap.isBlocked(host), "the host must be blocked with it");

			// The host half of the transaction: the abort must reach the single-class entry point.
			Class<?> hostClass = defineAs(host, MixinSyntheticHalfInitFixtures.oldShapeBytes());
			assertThrows(BytecodeTransformerBridge.Abort.class,
				() -> RemoldAgent.applyTransformerBridges(hostClass.getClassLoader(), host, hostClass,
					MixinSyntheticHalfInitFixtures.oldShapeBytes()),
				"a blocked host must abort instead of being redefined into a half-applied state");
			assertFalse(RemoldAgent.redefineDynamic(hostClass, MixinSyntheticHalfInitFixtures.oldShapeBytes()),
				"redefineDynamic must report failure for the blocked host");
			assertTrue(recorder.redefinitions.isEmpty(),
				"total redefineClasses calls (synthetic + host) must be 0");

			// Control (new contract): drop the only strong reference and run the identical call -> it is
			// STILL refused. Liveness is no longer an input to the verdict (the real machine showed the
			// pollution happens with zero instances too), so the refusal must be reproducible with a probe
			// that reports nothing at all. A regression that re-introduces "no instance -> allow" goes red.
			holder[0] = null;
			MixinInnerClassHotSwap.clearBlocked(host);
			int[] beforeControl = metrics();
			boolean controlExecuted = MixinInnerClassHotSwap.redefineSyntheticSafely(
				recorder.proxy, live, MixinSyntheticHalfInitFixtures.OLD_DOTTED, candidate, host);
			int[] afterControl = metrics();
			assertFalse(controlExecuted, "with zero live instances the same member-schema change must still be"
				+ " refused (the verdict does not depend on the heap)");
			assertEquals(0, afterControl[0] - beforeControl[0], "the control must not redefine anything");
			assertEquals(1, afterControl[2] - beforeControl[2], "the control must be rejected by the schema gate");
		} finally {
			RemoldAgent.removeTransformerBridge(bridge);
			RemoldAgent.swapInstrumentationForTest(previous);
			MixinClassBaseline.forgetForTest(live.getClassLoader(), MixinSyntheticHalfInitFixtures.OLD_DOTTED);
			MixinInnerClassHotSwap.clearBlocked(host);
		}
	}

	/**
	 * Case B — field table grows with <b>zero reachable instances</b>: still refused.
	 *
	 * <p>This is the branch the real machine decided (HEAD {@code ac20e60b}): with
	 * {@code live{raw=0 reachable=0}} a real {@code redefineClasses} of a schema-changed synthetic
	 * class still wiped 230 named {@code LootCondition} references ({@code newlyNull=230}). The
	 * pollution depends on "member schema differs", not on the instance count, so this branch is
	 * refused too; the two numbers stay in the log as diagnostics only.</p>
	 */
	@Test
	void aGrowingFieldTableIsRefusedEvenWhenNoInstanceSurvivesTheGc() {
		Class<?> live = MixinSyntheticHalfInitFixtures.loadInFreshLoader(
			MixinSyntheticHalfInitFixtures.oldShapeBytes());
		byte[] candidate = MixinSyntheticHalfInitFixtures.expandedCandidate().bytes();
		String host = "livenessfix.Target$HostB";
		MixinClassBaseline.recordForTest(live.getClassLoader(), MixinSyntheticHalfInitFixtures.OLD_DOTTED,
			MixinSyntheticHalfInitFixtures.oldShapeBytes());

		RecordingInstrumentation recorder = new RecordingInstrumentation(new Class<?>[]{live});
		Instrumentation previous = RemoldAgent.swapInstrumentationForTest(recorder.proxy);
		// The protocol under test: 2 raw instances are visible, none of them survives the GC.
		int[] calls = {0};
		MixinInnerClassHotSwap.setInstanceProbeForTest(type -> {
			calls[0]++;
			return new Object[2];
		});
		try {
			assertEquals(2, MixinInnerClassHotSwap.syntheticLive(live),
				"the diagnostic counter must report what the heap walk sees (no GC is performed any more)");

			calls[0] = 0;
			int[] before = metrics();
			boolean executed = MixinInnerClassHotSwap.redefineSyntheticSafely(
				recorder.proxy, live, MixinSyntheticHalfInitFixtures.OLD_DOTTED, candidate, host);
			int[] after = metrics();

			assertFalse(executed, "a member-schema change must be refused even with zero reachable instances"
				+ " (real machine: newlyNull=230 at raw=0/reachable=0)");
			assertEquals(0, after[0] - before[0], "no redefine may execute");
			assertEquals(1, after[1] - before[1], "the field-table change must still be reported");
			assertEquals(1, after[2] - before[2], "the schema gate must count exactly one rejection");
			assertTrue(recorder.redefinitions.isEmpty(), "the synthetic class must not reach redefineClasses");
			assertTrue(MixinInnerClassHotSwap.isBlocked(host),
				"the refusal must block the host so it cannot be redefined into a split state");
		} finally {
			RemoldAgent.swapInstrumentationForTest(previous);
			MixinClassBaseline.forgetForTest(live.getClassLoader(), MixinSyntheticHalfInitFixtures.OLD_DOTTED);
			MixinInnerClassHotSwap.clearBlocked(host);
		}
	}

	/**
	 * Case C — body-only change with a live instance: allowed, and the instance observes the new body.
	 *
	 * <p>The end state is asserted on the bytes that leave the gate: the loaded shape answers
	 * {@code v1:captured} and the produced candidate answers {@code v2:captured} while keeping the
	 * instance's field values. Real {@code redefineClasses} of the same shape (live instance keeps
	 * its fields and starts answering with the new body) is measured by {@code MixinSyntheticGateProbe}
	 * P1 on a real JVM, which is registered outside {@code check}.</p>
	 */
	@Test
	void aBodyOnlyChangeIsAllowedWhileAnInstanceIsReachableAndTheBodyTakesEffect() {
		byte[] oldShape = MixinSyntheticHalfInitFixtures.oldShapeBytes();
		Class<?> live = MixinSyntheticHalfInitFixtures.loadInFreshLoader(oldShape);
		Object held = MixinSyntheticHalfInitFixtures.newInstance(live,
			new Class<?>[]{Object.class, Object.class}, "host", "captured");
		byte[] candidate = MixinSyntheticHalfInitFixtures.bodyOnlyCandidateBytes();
		String host = "livenessfix.Target$HostC";
		MixinClassBaseline.recordForTest(live.getClassLoader(), MixinSyntheticHalfInitFixtures.OLD_DOTTED,
			oldShape);

		// Precondition: the loaded shape answers v1, so "v2 afterwards" cannot be a pre-existing value.
		assertEquals("v1:captured", MixinSyntheticHalfInitFixtures.call(held, MixinSyntheticHalfInitFixtures.BODY),
			"precondition: the loaded body must be the old one");

		RecordingInstrumentation recorder = new RecordingInstrumentation(new Class<?>[]{live});
		Instrumentation previous = RemoldAgent.swapInstrumentationForTest(recorder.proxy);
		MixinInnerClassHotSwap.setInstanceProbeForTest(type -> held == null ? new Object[0] : new Object[]{held});
		try {
			int[] before = metrics();
			boolean executed = MixinInnerClassHotSwap.redefineSyntheticSafely(
				recorder.proxy, live, MixinSyntheticHalfInitFixtures.OLD_DOTTED, candidate, host);
			int[] after = metrics();

			assertTrue(executed, "a body-only change must be accepted even with a live instance");
			assertEquals(1, after[0] - before[0], "it must be redefined exactly once");
			assertEquals(0, after[1] - before[1], "an unchanged field table must not be counted as a change");
			assertEquals(0, after[2] - before[2], "no gate may reject a body-only change");
			assertEquals(1, recorder.redefinitions.size(), "exactly one redefineClasses call may leave the gate");

			byte[] produced = recorder.redefinitions.get(0).getDefinitionClassFile();
			Class<?> afterShape = MixinSyntheticHalfInitFixtures.loadInFreshLoader(produced);
			Object fresh = MixinSyntheticHalfInitFixtures.newInstance(afterShape,
				new Class<?>[]{Object.class, Object.class}, "host", "captured");
			assertEquals("v2:captured", MixinSyntheticHalfInitFixtures.call(fresh, MixinSyntheticHalfInitFixtures.BODY),
				"the produced bytes must answer with the new body");
			assertEquals(0, MixinSyntheticSchema.loadedFields(afterShape).size() - 2,
				"the produced bytes must keep exactly the two capture fields");
		} finally {
			RemoldAgent.swapInstrumentationForTest(previous);
			MixinClassBaseline.forgetForTest(live.getClassLoader(), MixinSyntheticHalfInitFixtures.OLD_DOTTED);
			MixinInnerClassHotSwap.clearBlocked(host);
		}
	}

	/**
	 * The product probe on a real heap: a strongly held instance reads {@code reachable > 0}, and the
	 * same class reads {@code reachable == 0} once the reference is dropped and the heap is collected.
	 *
	 * <p>Skipped (with an explicit assumption message) only when the native {@code LibTool} cannot be
	 * loaded, which is the same condition that makes the production probe fail closed.</p>
	 */
	@Test
	void theProductProbeDistinguishesHeldInstancesFromCollectedOnes() {
		Class<?> live = MixinSyntheticHalfInitFixtures.loadInFreshLoader(
			MixinSyntheticHalfInitFixtures.oldShapeBytes());
		Assumptions.assumeTrue(MixinInnerClassHotSwap.syntheticLive(live) >= 0,
			"the product live-instance probe (LibTool.getInstances) is unavailable in this JVM");

		Object[] holder = {MixinSyntheticHalfInitFixtures.newInstance(live,
			new Class<?>[]{Object.class, Object.class}, "host", "captured")};
		int heldRaw = MixinInnerClassHotSwap.syntheticLive(live);
		assertTrue(heldRaw >= 1, "a strongly held instance must be visible on the heap, raw=" + heldRaw);

		holder[0] = null;
		int collected = heldRaw;
		for (int attempt = 0; attempt < 8 && collected > 0; attempt++) {
			System.gc();
			collected = MixinInnerClassHotSwap.syntheticLive(live);
		}
		assertEquals(0, collected,
			"after dropping the last reference and collecting, the diagnostic count must be zero");
	}

	// ------------------------------------------------------------ harness

	private static int[] metrics() {
		int[] out = new int[3];
		for (String part : MixinInnerClassHotSwap.metricsSummary().split("\\s+")) {
			int eq = part.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = part.substring(0, eq).trim();
			int value;
			try {
				value = Integer.parseInt(part.substring(eq + 1).trim());
			} catch (NumberFormatException e) {
				continue;
			}
			if ("executed".equals(key)) {
				out[0] = value;
			} else if ("fieldTableChanged".equals(key)) {
				out[1] = value;
			} else if ("gateRejected".equals(key)) {
				out[2] = value;
			}
		}
		return out;
	}

	private static Set<String> constructorDescriptorsOf(byte[] bytes) {
		assertNotNull(bytes, "the redefine must carry bytes");
		ClassNode node = new ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
		Set<String> out = new LinkedHashSet<>();
		for (MethodNode method : node.methods) {
			if ("<init>".equals(method.name)) {
				out.add(method.desc);
			}
		}
		return out;
	}

	/** Defines {@code bytes} under {@code dottedName} in its own loader (the host stand-in). */
	private static Class<?> defineAs(String dottedName, byte[] bytes) {
		String internal = dottedName.replace('.', '/');
		ClassNode node = new ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
		String from = node.name;
		node.name = internal;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) {
				continue;
			}
			for (org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.getFirst();
					insn != null; insn = insn.getNext()) {
				if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field) {
					if (from.equals(field.owner)) {
						field.owner = internal;
					}
				} else if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) {
					if (from.equals(call.owner)) {
						call.owner = internal;
					}
				} else if (insn instanceof org.objectweb.asm.tree.TypeInsnNode type && from.equals(type.desc)) {
					type.desc = internal;
				}
			}
		}
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		node.accept(writer);
		byte[] hostBytes = writer.toByteArray();
		ClassLoader loader = new ClassLoader(MixinSyntheticLivenessTest.class.getClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (!dottedName.equals(name)) {
					return super.loadClass(name, resolve);
				}
				Class<?> defined = findLoadedClass(name);
				if (defined == null) {
					defined = defineClass(name, hostBytes, 0, hostBytes.length);
				}
				if (resolve) {
					resolveClass(defined);
				}
				return defined;
			}
		};
		try {
			return loader.loadClass(dottedName);
		} catch (ClassNotFoundException e) {
			throw new AssertionError("the host shape must define under " + dottedName, e);
		}
	}

	/** Recording {@link Instrumentation}: counts the redefine calls that leave the gate. */
	private static final class RecordingInstrumentation implements InvocationHandler {
		final List<ClassDefinition> redefinitions = new ArrayList<>();
		private final Class<?>[] loadedClasses;
		final Instrumentation proxy;

		RecordingInstrumentation(Class<?>[] loadedClasses) {
			this.loadedClasses = loadedClasses;
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
			if ("getAllLoadedClasses".equals(name)) {
				return this.loadedClasses.clone();
			}
			if ("isRedefineClassesSupported".equals(name) || "isRetransformClassesSupported".equals(name)
				|| "isModifiableClass".equals(name)) {
				return Boolean.TRUE;
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
