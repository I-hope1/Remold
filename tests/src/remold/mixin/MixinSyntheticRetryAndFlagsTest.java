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
	 * Round trip with the <b>real data flow</b>: the host target class's own bytes never change (the user
	 * edits the mixin / anonymous class, not the target), so the suppression signature must be the
	 * <b>candidate bytes that would be handed to the JVM</b> — keyed per synthetic class. R1 refused ->
	 * identical R1 bytes suppressed -> R2 (another capture shape) re-evaluated and refused -> R4 (capture
	 * list restored, body only) accepted, all in one session.
	 */
	@Test
	void aRefusedFieldTableChangeIsNotRetriedUntilTheCandidateChanges() {
		byte[] oldShape = MixinSyntheticHalfInitFixtures.oldShapeBytes();
		Class<?> live = MixinSyntheticHalfInitFixtures.loadInFreshLoader(oldShape);
		String synthetic = MixinSyntheticHalfInitFixtures.OLD_DOTTED;
		String host = "retryfix.Target$HostRoundTrip";
		MixinClassBaseline.recordForTest(live.getClassLoader(), synthetic, oldShape);
		SyntheticAbortLedger.clearForTest();

		byte[] r1 = MixinSyntheticHalfInitFixtures.expandedCandidate().bytes();
		byte[] r2 = withPrimitiveCaptureWidened(r1);
		byte[] r4 = MixinSyntheticHalfInitFixtures.bodyOnlyCandidateBytes();
		assertFalse(java.util.Arrays.equals(r1, r2), "precondition: R2 must be a different candidate");
		assertFalse(java.util.Arrays.equals(r1, r4), "precondition: R4 must be a different candidate");

		RecordingInstrumentation recorder = new RecordingInstrumentation(new Class<?>[]{live});
		Instrumentation previous = RemoldAgent.swapInstrumentationForTest(recorder.proxy);
		MixinInnerClassHotSwap.setInstanceProbeForTest(type -> new Object[0]);
		try {
			// R1: capture list changes -> refused, recorded under the synthetic class name.
			int[] beforeR1 = metrics();
			boolean okR1 = MixinInnerClassHotSwap.redefineSyntheticSafely(recorder.proxy, live, synthetic, r1, host);
			int[] afterR1 = metrics();
			assertFalse(okR1, "R1: a capture-list change must be refused");
			assertEquals(0, afterR1[0] - beforeR1[0], "R1: nothing may be redefined");
			assertEquals(1, afterR1[2] - beforeR1[2], "R1: exactly one rejection");
			assertTrue(MixinInnerClassHotSwap.isBlocked(host), "R1: the host must be blocked");
			assertEquals(1, SyntheticAbortLedger.size(), "R1: the refused candidate must be on the ledger");
			long r1Signature = java.util.Arrays.hashCode(r1) & 0xffffffffL;
			assertTrue(SyntheticAbortLedger.isSameCandidateAborted(synthetic, r1Signature),
				"R1: the ledger must be keyed by the synthetic class and the candidate bytes");

			// R1 retried with the SAME bytes (background re-trigger) -> suppressed, still no redefine.
			MixinInnerClassHotSwap.clearBlocked(host);
			int[] beforeRetry = metrics();
			boolean okRetry = MixinInnerClassHotSwap.redefineSyntheticSafely(recorder.proxy, live, synthetic, r1, host);
			int[] afterRetry = metrics();
			assertFalse(okRetry, "the identical candidate must be suppressed");
			assertEquals(0, afterRetry[0] - beforeRetry[0], "the retry must not redefine");
			assertEquals(0, afterRetry[2] - beforeRetry[2], "the identical candidate must not be counted twice"
				+ " (one candidate = one rejection; the real machine saw +3 per round before this dedupe)");
			assertTrue(MixinInnerClassHotSwap.isBlocked(host), "the retry must block the host again");
			assertEquals(0, recorder.redefinitions.size(), "nothing may have reached redefineClasses");

			// R2: a DIFFERENT capture shape -> new signature -> re-evaluated and refused on its own merits.
			MixinInnerClassHotSwap.clearBlocked(host);
			int[] beforeR2 = metrics();
			boolean okR2 = MixinInnerClassHotSwap.redefineSyntheticSafely(recorder.proxy, live, synthetic, r2, host);
			int[] afterR2 = metrics();
			assertFalse(okR2, "R2: still a member-schema change, so still refused");
			assertEquals(0, afterR2[0] - beforeR2[0], "R2: nothing may be redefined");
			assertEquals(1, afterR2[2] - beforeR2[2], "R2: it must be judged again (not suppressed)");
			assertEquals(1, afterR2[1] - beforeR2[1], "R2: the change must be reported");
			assertEquals(1, SyntheticAbortLedger.size(), "R2: the ledger keeps one entry per synthetic class");

			// R4: capture list restored to the loaded shape, only the body changes -> accepted, ledger cleared.
			MixinInnerClassHotSwap.clearBlocked(host);
			int[] beforeR4 = metrics();
			boolean okR4 = MixinInnerClassHotSwap.redefineSyntheticSafely(recorder.proxy, live, synthetic, r4, host);
			int[] afterR4 = metrics();
			assertTrue(okR4, "R4: a body-only edit on the restored shape must be accepted in the same session");
			assertEquals(1, afterR4[0] - beforeR4[0], "R4: exactly one redefine");
			assertEquals(0, afterR4[1] - beforeR4[1], "R4: the field table is unchanged");
			assertEquals(0, afterR4[2] - beforeR4[2], "R4: no gate may reject it");
			assertEquals(1, recorder.redefinitions.size(), "R4: exactly one redefineClasses call");
			assertEquals(0, SyntheticAbortLedger.size(), "R4: an accepted candidate must clear its stale record");
		} finally {
			RemoldAgent.swapInstrumentationForTest(previous);
			MixinClassBaseline.forgetForTest(live.getClassLoader(), synthetic);
			MixinInnerClassHotSwap.clearBlocked(host);
		}
	}


	/**
	 * Gate order: a capture-list change moves <b>both</b> the field table and the {@code <init>} set, so the
	 * schema gate must be evaluated first and must count {@code fieldTableChanged}. Before this order was
	 * fixed the {@code missing loaded <init>} gate returned first, the counter stayed 0 and the real machine
	 * read {@code fieldTableChanged=0} on every refused capture change. The identical candidate asked twice
	 * must count once.
	 */
	@Test
	void aCaptureListChangeCountsAsAFieldTableChangeEvenWhenTheConstructorSetAlsoChanges() {
		byte[] oldShape = MixinSyntheticHalfInitFixtures.oldShapeBytes();
		Class<?> live = MixinSyntheticHalfInitFixtures.loadInFreshLoader(oldShape);
		String synthetic = MixinSyntheticHalfInitFixtures.OLD_DOTTED;
		String host = "retryfix.Target$HostOrder";
		MixinClassBaseline.recordForTest(live.getClassLoader(), synthetic, oldShape);
		SyntheticAbortLedger.clearForTest();
		// No retained <init>: the loaded 2-arg constructor is gone AND the field table grew.
		byte[] candidate = MixinSyntheticHalfInitFixtures.grownCandidateBytes();

		RecordingInstrumentation recorder = new RecordingInstrumentation(new Class<?>[]{live});
		Instrumentation previous = RemoldAgent.swapInstrumentationForTest(recorder.proxy);
		MixinInnerClassHotSwap.setInstanceProbeForTest(type -> new Object[0]);
		try {
			int[] before = metrics();
			boolean first = MixinInnerClassHotSwap.redefineSyntheticSafely(recorder.proxy, live, synthetic, candidate, host);
			int[] after = metrics();
			assertFalse(first, "the candidate must be refused");
			assertEquals(1, after[1] - before[1],
				"the schema gate must count the field-table change even though <init> also changed");
			assertEquals(1, after[2] - before[2], "one candidate = one rejection");
			assertEquals(0, after[0] - before[0], "nothing may be redefined");
			assertTrue(recorder.redefinitions.isEmpty(), "it must not reach redefineClasses");

			// Same bytes again (another entry point in the same round) -> no second accounting.
			int[] beforeDup = metrics();
			boolean dup = MixinInnerClassHotSwap.redefineSyntheticSafely(recorder.proxy, live, synthetic, candidate, host);
			int[] afterDup = metrics();
			assertFalse(dup, "the duplicate must stay refused");
			assertEquals(0, afterDup[1] - beforeDup[1], "a duplicate candidate must not be counted again");
			assertEquals(0, afterDup[2] - beforeDup[2], "a duplicate candidate must not be counted again");
		} finally {
			RemoldAgent.swapInstrumentationForTest(previous);
			MixinClassBaseline.forgetForTest(live.getClassLoader(), synthetic);
			MixinInnerClassHotSwap.clearBlocked(host);
		}
	}

	/** Same capture list as the growth candidate, but {@code val$c} widened {@code int -> long}. */
	private static byte[] withPrimitiveCaptureWidened(byte[] candidate) {
		ClassNode node = new ClassNode();
		new org.objectweb.asm.ClassReader(candidate).accept(node, 0);
		for (org.objectweb.asm.tree.FieldNode field : node.fields) {
			if ("val$c".equals(field.name)) {
				field.desc = "J";
			}
		}
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
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
