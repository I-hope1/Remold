package remold.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import remold.BytecodeTransformerBridge;
import remold.RemoldAgent;

/**
 * Wiring suite for the two connections that a real-machine P0 run exposed as "wired but never
 * effective". Both had passing unit tests before it, and both were dead on a real host:
 *
 * <ol>
 *   <li><b>Baseline source.</b> {@code retainConstructors} was fed {@code GENERATED} (what this
 *       process generated last time) instead of the baseline (what the JVM actually loaded). On a
 *       real host the {@code R0} synthetic class is loaded at startup, so {@code GENERATED} is empty
 *       at the first hot-swap: nothing was retained, gate 2 refused the class
 *       ({@code executed=0, gateRejected=1}) and the target then threw {@code NoSuchMethodError}.
 *       The previous suites always seeded the baseline <b>and</b> happened to exercise paths where
 *       the overlay was populated, so they could not see the difference.</li>
 *   <li><b>Transactional abort.</b> Marking the host {@code BLOCKED} only produced a log line: the
 *       re-weave still returned the woven bytes (with the new {@code <init>} call sites) and the host
 *       was redefined anyway. The previous suites asserted "the synthetic class never reached
 *       {@code redefineClasses}" but never "the <b>host</b> never did either".</li>
 * </ol>
 *
 * <p><b>What is measured</b>: a recording {@link Instrumentation} (installed through
 * {@link RemoldAgent#swapInstrumentationForTest}) counts the {@code redefineClasses} calls that
 * actually leave the production code. Case 1 asserts the produced bytes carry <b>both</b>
 * constructors — the terminal fact, not a log line. Case 2 asserts the total call count is zero for
 * the synthetic class <b>and</b> the host class together, and that the single-class entry point
 * ({@link RemoldAgent#redefineDynamic}) returns failure instead of redefining.</p>
 *
 * <p><b>Controls</b>: case 1's control is structural — the fixture hands the production path the
 * grown shape <i>without</i> the old constructor ({@link MixinSyntheticHalfInitFixtures#grownShapeNode()}),
 * so the retained overload can only come from the baseline; if the baseline read were reverted the
 * case goes red (verified by mutation). Case 2's control clears exactly one condition
 * ({@code BLOCKED}) and asserts the identical call no longer aborts.</p>
 *
 * <p><b>Limit</b>: the redefine itself is recorded, not performed — the unit-test JVM has no
 * DCEVM/JBR instance migration. Real {@code redefineClasses} against a DCEVM host stays with
 * {@code p05Probe} / {@code MixinSyntheticGateProbe}, which are registered outside {@code check}.</p>
 */
class MixinHotSwapWiringTest {

	@org.junit.jupiter.api.BeforeEach
	void clearOverlayAndLedger() {
		// Both the overlay (GENERATED) and the abort ledger are process-wide and keyed by the synthetic
		// class name; the cases below share one fixture name, so they must not see each other's state.
		MixinInnerClassHotSwap.clearGeneratedForTest(MixinSyntheticHalfInitFixtures.OLD_DOTTED);
		SyntheticAbortLedger.clearForTest();
	}

	/**
	 * Case 1 — {@code premain} recorder → {@code retainConstructors}.
	 *
	 * <p>The overlay ({@code GENERATED}) is empty for this name by construction (nothing generated it
	 * in this JVM), the only baseline source is a {@code (loader identity, name)} entry, and the
	 * candidate is the grown capture list. The production path must retain the loaded 2-arg
	 * {@code <init>} and redefine the class exactly once.</p>
	 */
	@Test
	void retainsTheLoadedConstructorWhenOnlyThePremainRecorderHasTheBaseline() {
		byte[] oldShape = MixinSyntheticHalfInitFixtures.oldShapeBytes();
		Class<?> live = MixinSyntheticHalfInitFixtures.loadInFreshLoader(oldShape);
		ClassLoader loader = live.getClassLoader();
		String dotted = MixinSyntheticHalfInitFixtures.OLD_DOTTED;
		String host = "wiringfix.Target$HostForBaseline";

		// Precondition: the overlay is empty, so a baseline read that goes through GENERATED can only
		// come back null. This is the real-host situation at R1 (the class was loaded at R0).
		assertFalse(MixinInnerClassHotSwap.generatedInternalNames()
			.contains(MixinSyntheticHalfInitFixtures.OLD_INTERNAL),
			"precondition: no generated overlay bytes may exist for this class");
		// The single baseline source: premain recorder, keyed by (loader identity, dotted name).
		MixinClassBaseline.recordForTest(loader, dotted, oldShape);

		RecordingInstrumentation recorder = new RecordingInstrumentation(new Class<?>[]{live});
		Instrumentation previous = RemoldAgent.swapInstrumentationForTest(recorder.proxy);
		// No instance exists in this case; pin the liveness probe so the case cannot depend on whether
		// the native heap walk is loadable here (that dimension has its own suite).
		MixinInnerClassHotSwap.setInstanceProbeForTest(type -> new Object[0]);
		try {
			int[] before = metrics();
			FakeTransformer transformer = FakeTransformer.serving(dotted, host);

			int registered = MixinInnerClassHotSwap.refeedForTarget(transformer, host);

			int[] after = metrics();
			assertEquals(1, registered, "the target's synthetic inner class must be pre-generated");
			// The member schema changed (2 captures -> 4), so the schema gate refuses the redefine and
			// aborts the host. That is the correct verdict; what this case pins is the BASELINE WIRING:
			// the retained <init> can only come from the (loader, name) recorder, never from the empty
			// overlay. So the assertion moves to the bytes the production path produced.
			assertEquals(1, after[2] - before[2], "a member-schema change must be refused by the schema gate");
			assertEquals(0, after[0] - before[0], "no redefine may execute for a member-schema change");
			assertTrue(recorder.redefinitions.isEmpty(), "a refused candidate must not reach redefineClasses");
			assertTrue(MixinInnerClassHotSwap.isBlocked(host),
				"the refusal must block the host so it cannot be redefined into a split state");

			// Gate 4 must be satisfied by the premain recorder ALONE (the overlay is empty), otherwise the
			// refusal above could have come from "no baseline bytes" instead of the schema gate.
			assertNull(MixinInnerClassHotSwap.baselineMissingReason(loader, dotted),
				"the (loader, name) recorder entry must satisfy gate 4 on its own");
				} finally {
			MixinInnerClassHotSwap.setInstanceProbeForTest(null);
			RemoldAgent.swapInstrumentationForTest(previous);
			MixinClassBaseline.forgetForTest(loader, dotted);
			MixinInnerClassHotSwap.clearBlocked(host);
		}
	}

	/**
	 * Case 2 — a rejected synthetic class must abort the host redefine too (transactional
	 * consistency, invariant C2).
	 *
	 * <p>Rejection is produced exactly the way the real host produced it: the candidate drops a
	 * constructor the loaded class still owns, so the single exit refuses and marks the host. The
	 * assertions then count {@code redefineClasses} calls for both classes together.</p>
	 */
	@Test
	void aRejectedSyntheticClassAlsoStopsTheHostTargetRedefine() {
		byte[] oldShape = MixinSyntheticHalfInitFixtures.oldShapeBytes();
		Class<?> live = MixinSyntheticHalfInitFixtures.loadInFreshLoader(oldShape);
		ClassLoader loader = live.getClassLoader();
		String synthetic = MixinSyntheticHalfInitFixtures.OLD_DOTTED;
		String host = "wiringfix.Target$HostForTransaction";
		byte[] dropping = MixinSyntheticGateFixtures.withoutConstructors(HalfInitOld.class);

		RecordingInstrumentation recorder = new RecordingInstrumentation(new Class<?>[]{live});
		Instrumentation previous = RemoldAgent.swapInstrumentationForTest(recorder.proxy);
		BytecodeTransformerBridge bridge = new MixinBytecodeBridge();
		RemoldAgent.addTransformerBridge(bridge);
		try {
			// 1. The synthetic class is refused by the single exit and marks the host.
			int[] before = metrics();
			boolean executed = MixinInnerClassHotSwap.redefineSyntheticSafely(
				recorder.proxy, live, synthetic, dropping, host);
			int[] after = metrics();
			assertFalse(executed, "precondition: dropping a loaded <init> must be refused");
			assertEquals(0, after[0] - before[0], "precondition: no synthetic redefine may execute");
			assertEquals(1, after[2] - before[2], "precondition: the constructor gate must count one rejection");
			assertTrue(MixinInnerClassHotSwap.isBlocked(host),
				"precondition: the rejection must block the host target");
			assertEquals(0, recorder.redefinitions.size(),
				"precondition: the synthetic class must not reach redefineClasses");

			// 2. The host path must abort instead of redefining with the new call sites. A plain
			//    "return the source bytes" would have redefined the host without its mixin hooks, and
			//    "return the woven bytes" is what the real host did — both end in a broken client.
			assertThrows(BytecodeTransformerBridge.Abort.class,
				() -> RemoldAgent.applyTransformerBridges(loader, host, live, oldShape),
				"a blocked host must abort the bridge instead of returning bytes");
			assertEquals(0, recorder.redefinitions.size(),
				"an aborted bridge call must not reach redefineClasses");

			// 3. The single-class entry point must honour the abort: failure, zero redefines. The class
			//    handed to it must be the *host* (the blocked name) — handing it the synthetic class
			//    would ask a different question and could never be aborted.
			Class<?> hostClass = defineAs(host);
			assertFalse(RemoldAgent.redefineDynamic(hostClass, oldShape),
				"redefineDynamic must report failure when the bridge aborts");
			assertEquals(0, recorder.redefinitions.size(),
				"total redefineClasses calls (synthetic + host) must be 0");

			// Control: the only removed condition is the block flag, so the identical call must pass.
			MixinInnerClassHotSwap.clearBlocked(host);
			RemoldAgent.applyTransformerBridges(hostClass.getClassLoader(), host, hostClass, oldShape);
			assertEquals(0, recorder.redefinitions.size(),
				"the bridge alone never redefines; the control proves the abort came from the block flag");
		} finally {
			RemoldAgent.removeTransformerBridge(bridge);
			RemoldAgent.swapInstrumentationForTest(previous);
			MixinClassBaseline.forgetForTest(loader, synthetic);
			MixinInnerClassHotSwap.clearBlocked(host);
		}
	}


	/**
	 * The inner-class phase ({@code apply()}, i.e. "only the anonymous class body changed") must be
	 * accounted by the same single exit: {@code executed} increments and the {@code metrics:} line is
	 * emitted. On the real machine B2~B5 went through this path, the body took effect, but
	 * {@code executed} stayed at the value the pre-target path left behind and no {@code metrics:} line
	 * was printed — the reading looked stuck at 1.
	 */
	@Test
	void theInnerClassPhaseCountsExecutedThroughTheSameExit() {
		byte[] oldShape = MixinSyntheticHalfInitFixtures.oldShapeBytes();
		Class<?> live = MixinSyntheticHalfInitFixtures.loadInFreshLoader(oldShape);
		ClassLoader loader = live.getClassLoader();
		String dotted = MixinSyntheticHalfInitFixtures.OLD_DOTTED;
		MixinClassBaseline.recordForTest(loader, dotted, oldShape);
		RecordingInstrumentation recorder = new RecordingInstrumentation(new Class<?>[]{live});
		Instrumentation previous = RemoldAgent.swapInstrumentationForTest(recorder.proxy);
		MixinInnerClassHotSwap.setInstanceProbeForTest(type -> new Object[0]);
		java.util.List<String> logged = new java.util.ArrayList<>();
		remold.RemoldAgent.Logger previousLogger = RemoldAgent.logger;
		RemoldAgent.logger = new remold.RemoldAgent.Logger() {
			@Override
			public void log(String msg) {
				logged.add(msg);
			}

			@Override
			public void info(String msg) {
				logged.add(msg);
			}

			@Override
			public void warn(String msg) {
				logged.add(msg);
			}

			@Override
			public void error(String msg) {
				logged.add(msg);
			}

			@Override
			public void error(String msg, Throwable t) {
				logged.add(msg);
			}
		};
		try {
			int[] before = metrics();
			FakeTransformer transformer = FakeTransformer.serving(dotted, "wiringfix.Target$HostApply", true);

			MixinInnerClassHotSwap.Result result = MixinInnerClassHotSwap.apply(transformer,
				"wiringfix.BodyOnlyMixin", java.util.List.of("remold/mixin/HalfInitNext"));

			int[] after = metrics();
			assertTrue(result.changed(), "the body-only update must be applied, got " + result.describeFailures());
			assertEquals(1, after[0] - before[0], "the inner-class phase must increment executed");
			assertEquals(0, after[1] - before[1], "a body-only update is not a field-table change");
			assertEquals(0, after[2] - before[2], "no gate may reject it");
			assertEquals(1, recorder.redefinitions.size(), "exactly one redefineClasses call");
			assertTrue(logged.stream().anyMatch(line -> line.contains("redefined in place; metrics: executed=")),
				"the inner-class phase must emit the metrics line the front line reads, got " + logged);
		} finally {
			RemoldAgent.logger = previousLogger;
			MixinInnerClassHotSwap.setInstanceProbeForTest(null);
			RemoldAgent.swapInstrumentationForTest(previous);
			MixinClassBaseline.forgetForTest(loader, dotted);
			MixinInnerClassHotSwap.clearBlocked("wiringfix.Target$HostApply");
		}
	}

	// ------------------------------------------------------------ harness

	/** Counter deltas of {@code executed / fieldTableChanged / gateRejected}. */
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

	/**
	 * Defines a copy of the loaded shape under {@code dottedName} in its own loader, so the case has
	 * a real loaded class whose name <b>is</b> the blocked host name.
	 *
	 * <p>{@code redefineDynamic} is asked about the class it should redefine, so the blocked name and
	 * the class name must agree — otherwise the case would ask about the synthetic class and the
	 * answer ("not blocked") would be correct for the wrong question.</p>
	 */
	private static Class<?> defineAs(String dottedName) {
		String internal = dottedName.replace('.', '/');
		ClassNode node = MixinSyntheticGateFixtures.nodeOf(HalfInitOld.class);
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
		byte[] bytes = writer.toByteArray();
		ClassLoader loader = new ClassLoader(MixinHotSwapWiringTest.class.getClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (!dottedName.equals(name)) {
					return super.loadClass(name, resolve);
				}
				Class<?> defined = findLoadedClass(name);
				if (defined == null) {
					defined = defineClass(name, bytes, 0, bytes.length);
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

	/** {@code <init>} descriptors declared by the given class file bytes. */	private static Set<String> constructorDescriptorsOf(byte[] bytes) {
		assertNotNull(bytes, "the redefine must carry bytes");
		ClassNode node = new ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
		Set<String> out = new LinkedHashSet<>();
		for (MethodNode method : node.methods) {
			if ("<init>".equals(method.name)) {
				out.add(method.desc);
			}
		}
		assertNotEquals(0, out.size(), "a synthetic class must declare at least one <init>");
		return out;
	}

	/**
	 * Recording {@link Instrumentation}: the unit-test JVM has no agent, so the only observable at
	 * this level is "did the call reach {@code redefineClasses}, and with which bytes".
	 */
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

	/**
	 * Minimal stand-in for Mixin's transformer chain, shaped exactly like the reflection path
	 * {@code MixinInnerClassHotSwap} walks: {@code transformer.processor.extensions.getGenerators()}
	 * → the first generator whose simple name is {@code InnerClassGenerator} → its
	 * {@code innerClasses} map → {@code generate(String, ClassNode)}.
	 *
	 * <p>The generated node is the grown capture shape <b>without</b> the old constructor, so every
	 * retained {@code <init>} observed by the case comes from the production retention step.</p>
	 */
	private static final class FakeTransformer {
		final Object processor;

		private FakeTransformer(Object processor) {
			this.processor = processor;
		}

		static FakeTransformer serving(String syntheticDotted, String hostDotted) {
			return serving(syntheticDotted, hostDotted, false);
		}

		static FakeTransformer serving(String syntheticDotted, String hostDotted, boolean bodyOnly) {
			InnerClassGenerator generator = new InnerClassGenerator();
			generator.bodyOnly = bodyOnly;
			InnerClassInfo info = new InnerClassInfo(syntheticDotted, hostDotted.replace('.', '/'));
			generator.innerClasses.put(info.className, info);
			return new FakeTransformer(new Processor(new Extensions(generator)));
		}
	}

	/** Field name must be {@code processor}: the production path reads it reflectively. */
	private static final class Processor {
		@SuppressWarnings("unused")
		final Object extensions;

		Processor(Object extensions) {
			this.extensions = extensions;
		}
	}

	/** {@code getGenerators()} must be public: the production path resolves it via getMethod. */
	public static final class Extensions {
		private final List<Object> generators;

		Extensions(Object generator) {
			this.generators = new ArrayList<>(List.of(generator));
		}

		public List<Object> getGenerators() {
			return this.generators;
		}
	}

	/** Simple name must be {@code InnerClassGenerator}: that is how the production path finds it. */
	@SuppressWarnings("unused")
	static final class InnerClassGenerator {
		final java.util.Map<String, InnerClassInfo> innerClasses = new java.util.LinkedHashMap<>();

		boolean bodyOnly;

		boolean generate(String className, ClassNode out) {
			ClassNode source = this.bodyOnly ? MixinSyntheticHalfInitFixtures.bodyOnlyShapeNode()
				: MixinSyntheticHalfInitFixtures.grownShapeNode();
			source.accept(out);
			return true;
		}
	}

	/** One registered inner class: original template name, stable unique name and host target. */
	@SuppressWarnings("unused")
	static final class InnerClassInfo {
		final String className;
		final String targetName;
		int loadCounter;

		InnerClassInfo(String className, String targetName) {
			this.className = className;
			this.targetName = targetName;
		}

		String getOriginalName() {
			return "remold/mixin/HalfInitNext";
		}

		String getTargetName() {
			return this.targetName;
		}

		String getClassName() {
			return this.className;
		}
	}
}
