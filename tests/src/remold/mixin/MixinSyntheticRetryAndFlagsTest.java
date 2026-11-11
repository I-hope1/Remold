package remold.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

import remold.RemoldAgent;

/**
 * The two follow-ups the final real-machine verdict asked for: <b>no automatic retry of a refused
 * candidate</b> and <b>field flags are part of the schema</b>.
 *
 * <p><b>Why the retry rule exists</b>: the gate's verdict now depends only on immutable inputs (the
 * loaded class's member schema vs the candidate's). The old wiring cleared the abort flag at the next
 * attempt, so the same unchanged candidate was re-judged with a *different* heap reading — the real
 * machine caught it: first attempt {@code live{raw=4 reachable=4}} refused, retry
 * {@code live{raw=0 reachable=0}} accepted, then {@code newlyNull=230} and a crashed server.
 * {@link SyntheticAbortLedger} makes "same candidate = judged once" explicit, and the ledger is
 * cleared only when the candidate bytes change (i.e. the user recompiled something) or the host is
 * redefined successfully.</p>
 *
 * <p><b>Why the flags matter</b>: {@code name:desc} alone cannot see a {@code static} flip or a
 * dropped {@code ACC_SYNTHETIC} on the same field. {@code AnonClassAligner}'s Tier 4 gate compares
 * those flags; a name+descriptor-only comparison would let such a candidate through the schema gate
 * and straight into a JBR instance migration.</p>
 */
class MixinSyntheticRetryAndFlagsTest {

	@AfterEach
	void cleanUp() {
		MixinInnerClassHotSwap.setInstanceProbeForTest(null);
		SyntheticAbortLedger.clearForTest();
	}

	/**
	 * Round trip, real-machine shape (R1 refused -> R2/R3 not retried -> R4 accepted after the capture
	 * list is restored and only the body changes).
	 */
	@Test
	void aRefusedFieldTableChangeIsNotRetriedUntilTheCandidateChanges() {
		byte[] oldShape = MixinSyntheticHalfInitFixtures.oldShapeBytes();
		Class<?> live = MixinSyntheticHalfInitFixtures.loadInFreshLoader(oldShape);
		String host = "retryfix.Target$HostRoundTrip";
		MixinClassBaseline.recordForTest(live.getClassLoader(), MixinSyntheticHalfInitFixtures.OLD_DOTTED, oldShape);
		SyntheticAbortLedger.clearForTest();

		byte[] grown = MixinSyntheticHalfInitFixtures.expandedCandidate().bytes();
		byte[] bodyOnly = MixinSyntheticHalfInitFixtures.bodyOnlyCandidateBytes();
		long grownSignature = java.util.Arrays.hashCode(grown) & 0xffffffffL;
		long bodySignature = java.util.Arrays.hashCode(bodyOnly) & 0xffffffffL;
		assertNotEquals(grownSignature, bodySignature, "precondition: the two candidates must be distinguishable");

		RecordingInstrumentation recorder = new RecordingInstrumentation(new Class<?>[]{live});
		Instrumentation previous = RemoldAgent.swapInstrumentationForTest(recorder.proxy);
		MixinInnerClassHotSwap.setInstanceProbeForTest(type -> new Object[0]);
		try {
			// R1: the capture list changes -> refused, host blocked, candidate recorded as aborted.
			int[] beforeR1 = metrics();
			boolean r1 = MixinInnerClassHotSwap.redefineSyntheticSafely(
				recorder.proxy, live, MixinSyntheticHalfInitFixtures.OLD_DOTTED, grown, host);
			int[] afterR1 = metrics();
			assertFalse(r1, "R1: a capture-list change must be refused");
			assertEquals(0, afterR1[0] - beforeR1[0], "R1: nothing may be redefined");
			assertEquals(1, afterR1[2] - beforeR1[2], "R1: exactly one rejection");
			assertTrue(MixinInnerClassHotSwap.isBlocked(host), "R1: the host must be blocked");
			SyntheticAbortLedger.record(host, grownSignature);

			// R2/R3: the identical bytes are asked again (the automatic retry) -> the ledger skips it.
			assertTrue(SyntheticAbortLedger.isSameCandidateAborted(host, grownSignature),
				"the identical candidate must be recognised as already-aborted, so it is not retried");
			assertEquals(1, SyntheticAbortLedger.size(), "one aborted candidate must be on the ledger");
			assertEquals(0, recorder.redefinitions.size(), "no redefine may have left the gate so far");

			// R4: the user restores the capture list and only edits the body -> new signature, accepted.
			assertFalse(SyntheticAbortLedger.isSameCandidateAborted(host, bodySignature),
				"a changed candidate must not be suppressed");
			MixinInnerClassHotSwap.clearBlocked(host);
			SyntheticAbortLedger.clear(host);
			int[] beforeR4 = metrics();
			boolean r4 = MixinInnerClassHotSwap.redefineSyntheticSafely(
				recorder.proxy, live, MixinSyntheticHalfInitFixtures.OLD_DOTTED, bodyOnly, host);
			int[] afterR4 = metrics();
			assertTrue(r4, "R4: a body-only edit on the restored shape must be accepted");
			assertEquals(1, afterR4[0] - beforeR4[0], "R4: exactly one redefine");
			assertEquals(0, afterR4[1] - beforeR4[1], "R4: the field table is unchanged, so nothing to report");
			assertEquals(0, afterR4[2] - beforeR4[2], "R4: no gate may reject it");
			assertEquals(1, recorder.redefinitions.size(), "R4: exactly one redefineClasses call");
			assertEquals(0, SyntheticAbortLedger.size(), "R4: the ledger must be empty after the accepted round");
		} finally {
			RemoldAgent.swapInstrumentationForTest(previous);
			MixinClassBaseline.forgetForTest(live.getClassLoader(), MixinSyntheticHalfInitFixtures.OLD_DOTTED);
			MixinInnerClassHotSwap.clearBlocked(host);
		}
	}

	/** A {@code static} flip on an identical {@code name:desc} set is a member-schema change. */
	@Test
	void aStaticFlagFlipOnTheSameFieldSignatureIsTreatedAsASchemaChange() {
		byte[] oldShape = MixinSyntheticHalfInitFixtures.oldShapeBytes();
		Class<?> live = MixinSyntheticHalfInitFixtures.loadInFreshLoader(oldShape);
		String host = "retryfix.Target$HostFlags";
		MixinClassBaseline.recordForTest(live.getClassLoader(), MixinSyntheticHalfInitFixtures.OLD_DOTTED, oldShape);

		ClassNode flipped = MixinSyntheticHalfInitFixtures.bodyOnlyShapeNode();
		org.objectweb.asm.tree.FieldNode target = null;
		for (org.objectweb.asm.tree.FieldNode field : flipped.fields) {
			if ("val$a".equals(field.name)) {
				target = field;
			}
		}
		assertNotNull(target, "precondition: the fixture must declare the capture field val$a");
		target.access |= org.objectweb.asm.Opcodes.ACC_STATIC;
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		flipped.accept(writer);
		byte[] candidate = writer.toByteArray();
		// Precondition: the name:descriptor view is identical, only the flag differs.
		assertEquals(MixinSyntheticSchema.loadedFields(live), MixinSyntheticSchema.nodeFields(flipped),
			"precondition: this candidate must differ ONLY in the static flag");

		RecordingInstrumentation recorder = new RecordingInstrumentation(new Class<?>[]{live});
		Instrumentation previous = RemoldAgent.swapInstrumentationForTest(recorder.proxy);
		MixinInnerClassHotSwap.setInstanceProbeForTest(type -> new Object[0]);
		try {
			int[] before = metrics();
			boolean executed = MixinInnerClassHotSwap.redefineSyntheticSafely(
				recorder.proxy, live, MixinSyntheticHalfInitFixtures.OLD_DOTTED, candidate, host);
			int[] after = metrics();
			assertFalse(executed, "a static flip on an identical name:descriptor set is a schema change");
			assertEquals(0, after[0] - before[0], "no redefine may execute");
			assertEquals(1, after[1] - before[1], "the change must be reported");
			assertEquals(1, after[2] - before[2], "the schema gate must reject it");
			assertTrue(recorder.redefinitions.isEmpty(), "it must not reach redefineClasses");
		} finally {
			RemoldAgent.swapInstrumentationForTest(previous);
			MixinClassBaseline.forgetForTest(live.getClassLoader(), MixinSyntheticHalfInitFixtures.OLD_DOTTED);
			MixinInnerClassHotSwap.clearBlocked(host);
		}
	}

	/** The ledger is a plain map keyed by host: same signature suppresses, different signature does not. */
	@Test
	void theLedgerSuppressesOnlyTheIdenticalCandidate() {
		SyntheticAbortLedger.clearForTest();
		SyntheticAbortLedger.record("host.A", 11L);
		assertTrue(SyntheticAbortLedger.isSameCandidateAborted("host.A", 11L), "same signature must suppress");
		assertFalse(SyntheticAbortLedger.isSameCandidateAborted("host.A", 12L), "a new signature must not suppress");
		assertFalse(SyntheticAbortLedger.isSameCandidateAborted("host.B", 11L), "another host must not suppress");
		SyntheticAbortLedger.record("host.A", 12L);
		assertFalse(SyntheticAbortLedger.isSameCandidateAborted("host.A", 11L),
			"re-recording must replace the previous signature (the table cannot grow per host)");
		assertTrue(SyntheticAbortLedger.isSameCandidateAborted("host.A", 12L), "the latest signature wins");
		assertEquals(1, SyntheticAbortLedger.size(), "one entry per host");
		SyntheticAbortLedger.clear("host.A");
		assertEquals(0, SyntheticAbortLedger.size(), "clearing removes the entry");
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
