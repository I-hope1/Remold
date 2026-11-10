package remold.mixin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Gate suite for the single exit of synthetic-inner-class redefinition.
 *
 * <p>Every path that redefines a {@code <Target>$Anonymous$<hash>} class must go through
 * {@link MixinInnerClassHotSwap#redefineSyntheticSafely}. That exit owns four pre-checks:</p>
 * <ol>
 *   <li>the host target is not already {@code BLOCKED} by a rejected sibling in the same batch;</li>
 *   <li>every constructor of the loaded class is still declared by the candidate (else old call
 *       sites throw {@code NoSuchMethodError});</li>
 *   <li>the loaded class's field table does not shrink (else the JVM re-lays out live objects);</li>
 *   <li>a baseline snapshot of the synthetic class exists (otherwise the class was loaded before
 *       the agent registered and a redefine cannot be reasoned about).</li>
 * </ol>
 *
 * <p>Each gate gets one rejection case plus a control that removes only the gated condition, so a
 * green run cannot be explained by "the candidate was invalid anyway". A passing case asserts the
 * redefine reaches the exit exactly once, and a half-migrated-instance case asserts that shrinking
 * candidates never reach {@code redefineClasses} at all, which is the precondition for
 * {@code ModifyInstances} object rewriting.</p>
 *
 * <p><b>Scope and its limit</b>: the unit-test JVM has no {@code -javaagent}, so
 * {@link Instrumentation} is a recording proxy — this suite asserts the gate decisions and the
 * calls that reach the exit, not the JVM's own structural acceptance of those calls. Real
 * {@code redefineClasses} against a DCEVM/JBR host (and the resulting instance migration) is
 * covered by {@code p05Probe} / {@code twoPhaseProbe} / {@code offsetStabilityProbe} tasks,
 * which are registered outside {@code check} for exactly that reason and are not duplicated
 * here.</p>
 *
 * <p><b>Case ledger (read this before quoting a case count)</b>: the suite holds
 * {@value #GATE_CASE_COUNT} {@code @Test} methods, not 8. The two numbers that show up in
 * reports count different things:</p>
 * <table border="1">
 *   <caption>Ledger</caption>
 *   <tr><th>case</th><th>gate</th><th>kind</th><th>in-method control</th></tr>
 *   <tr><td>{@code passingCandidateIsRedefinedExactlyOnceThroughTheSingleExit}</td>
 *       <td>single exit (all four gates pass)</td><td>pass</td><td>no</td></tr>
 *   <tr><td>{@code rejectsEveryCandidateOnceTheHostBatchIsBlocked}</td>
 *       <td>1 — host batch BLOCKED</td><td>reject</td><td>yes (clear the flag)</td></tr>
 *   <tr><td>{@code rejectsCandidateThatDropsAnAlreadyLoadedConstructor}</td>
 *       <td>2 — loaded {@code <init>} subset</td><td>reject</td><td>yes (keep the ctors)</td></tr>
 *   <tr><td>{@code rejectsCandidateThatWouldShrinkTheFieldTable}</td>
 *       <td>3 — field table must not shrink</td><td>reject</td><td>yes (keep the field)</td></tr>
 *   <tr><td>{@code rejectsCandidateWhenNoBaselineBytesExist}</td>
 *       <td>4 — baseline bytes required</td><td>reject</td><td>yes (seed the baseline)</td></tr>
 *   <tr><td>{@code shrinkingTableNeverReachesTheJvmSoLiveInstancesKeepTheirState}</td>
 *       <td>3 — half-migrated instance semantics</td><td>half</td><td>yes (keep the field)</td></tr>
 * </table>
 * <p>So the three numbers in circulation count different things, and only the first is a case
 * count:</p>
 * <ul>
 *   <li><b>{@value #GATE_CASE_COUNT}</b> {@code @Test} methods = {@code 1 pass + 4 rejections +
 *       1 half-migrated};</li>
 *   <li><b>{@value #GATE_GROUP_COUNT}</b> gate-scoped assertion groups = {@code 4 gates ×
 *       (rejection + its control)} — the figure reported as "8", which counts each gate's control
 *       as if it were a case of its own;</li>
 *   <li><b>{@value #CONTROL_COUNT}</b> in-method controls = the 4 gate controls plus the file
 *       half-migrated case's restore-field control.</li>
 * </ul>
 * <p>The controls are deliberately <b>inside</b> their rejection method instead of being separate
 * {@code @Test}s: a control only carries causal weight when it re-runs the identical name, host
 * and bytes after clearing exactly one condition, and a standalone control case goes green on its
 * own while proving nothing about the rejection it belongs to.
 * {@link #caseLedgerMatchesTheSuite()} pins the ledger mechanically (name set, per-gate coverage
 * and the 6-vs-8 arithmetic), so no report can drift away from the code.</p>
 *
 * <p><b>JVM-level evidence</b>: the half-migrated semantics above run here against a recording
 * proxy. {@code MixinSyntheticGateProbe} replays the same gate decisions on a real
 * {@link Instrumentation} (real {@code redefineClasses}), and counts a host without enhanced
 * redefinition as {@code SKIP(non-jbr)} separately instead of folding it into a pass.</p>
 */
class MixinSyntheticGateTest {

	/**
	 * @Test methods in this suite. Changing this number means editing the Javadoc ledger too —
	 * {@link #caseLedgerMatchesTheSuite()} fails otherwise.
	 */
	static final int GATE_CASE_COUNT = 6;

	/** Rejections that must each own exactly one in-method control. */
	static final int REJECTION_CASE_COUNT = 4;

	/** Gate-scoped assertion groups = {@code REJECTION_CASE_COUNT × 2}; this is the reported "8". */
	static final int GATE_GROUP_COUNT = 8;

	/** In-method controls: one per rejection, plus the half-migrated case's restore-field control. */
	static final int CONTROL_COUNT = 5;

	/** {@code name, gate, kind} for every {@code @Test} method; {@code kind} is pass/control-driven. */
	private record Case(String method, String gate, String kind, boolean control) {
	}

	private static final Case[] LEDGER = {
		new Case("passingCandidateIsRedefinedExactlyOnceThroughTheSingleExit", "single-exit", "pass", false),
		new Case("rejectsEveryCandidateOnceTheHostBatchIsBlocked", "gate1", "reject", true),
		new Case("rejectsCandidateThatDropsAnAlreadyLoadedConstructor", "gate2", "reject", true),
		new Case("rejectsCandidateThatWouldShrinkTheFieldTable", "gate3", "reject", true),
		new Case("rejectsCandidateWhenNoBaselineBytesExist", "gate4", "reject", true),
		new Case("shrinkingTableNeverReachesTheJvmSoLiveInstancesKeepTheirState", "gate3-half", "half", true),
	};

	/**
	 * Pins the case ledger to the code: the documented {@value #GATE_CASE_COUNT} {@code @Test}
	 * methods, no orphan cases, one rejection per gate, and a control behind every rejection.
	 */
	@BeforeAll
	static void caseLedgerMatchesTheSuite() {
		java.util.Set<String> annotated = new java.util.TreeSet<>();
		for (Method method : MixinSyntheticGateTest.class.getDeclaredMethods()) {
			if (method.isAnnotationPresent(Test.class)) {
				annotated.add(method.getName());
			}
		}
		java.util.Set<String> documented = new java.util.TreeSet<>();
		for (Case entry : LEDGER) {
			assertTrue(documented.add(entry.method()), "the ledger must not list a case twice: " + entry.method());
		}
		assertEquals(documented, annotated,
			"every @Test method must be in the ledger and every ledger row must name a real case");

		assertEquals(GATE_CASE_COUNT, LEDGER.length,
			"the ledger (and the Javadoc table) must describe exactly " + GATE_CASE_COUNT + " cases");
		assertEquals(GATE_CASE_COUNT, annotated.size(),
			"a case was added or removed without updating the ledger");

		int pass = 0;
		int rejections = 0;
		int half = 0;
		int controls = 0;
		java.util.Set<String> gatedGates = new java.util.TreeSet<>();
		for (Case entry : LEDGER) {
			switch (entry.kind()) {
				case "pass" -> pass++;
				case "reject" -> rejections++;
				case "half" -> half++;
				default -> assertEquals("pass|reject|half", entry.kind(),
					"unknown ledger kind for " + entry.method());
			}
			if (entry.control()) {
				controls++;
			}
			if (!"pass".equals(entry.kind())) {
				gatedGates.add(entry.gate());
			}
		}
		assertEquals(1, pass, "exactly one passing case: the single exit must be exercised once");
		assertEquals(REJECTION_CASE_COUNT, rejections, "every gate owns exactly one rejection case");
		assertEquals(1, half, "the half-migrated-instance semantics is one dedicated case");
		assertEquals(CONTROL_COUNT, controls,
			"every rejection and the half-migrated case must carry an in-method control");
		assertEquals(REJECTION_CASE_COUNT + 1, controls,
			"controls are the 4 gate controls plus the half-migrated case's restore-field control");
		assertEquals(GATE_GROUP_COUNT, REJECTION_CASE_COUNT * 2,
			"the '8' in reports is 4 gates x (rejection + control) = gate-scoped assertion groups,"
				+ " not 8 @Test methods");
		assertEquals(GATE_CASE_COUNT, pass + rejections + half,
			"the case count is pass + rejections + half; it must not include the controls");
		assertTrue(gatedGates.containsAll(List.of("gate1", "gate2", "gate3", "gate4")),
			"each of the four gates needs a rejection case, found only: " + gatedGates);
	}

	private static final AtomicInteger SEQ = new AtomicInteger();

	// ------------------------------------------------------------ pass case

	@Test
	void passingCandidateIsRedefinedExactlyOnceThroughTheSingleExit() {
		RecordingInstrumentation recorder = new RecordingInstrumentation();
		String name = uniqueName("pass");
		String host = uniqueHost("pass");
		byte[] candidate = MixinSyntheticGateFixtures.candidateBytes(MixinSyntheticGateFixtures.Capturing.class);
		remold.RemoldAgent.bytecodeCache.put(name, candidate);
		try {
			GateOutcome outcome = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, candidate, host);

			assertTrue(outcome.executed, "a candidate satisfying all four gates must be redefined");
			assertEquals(1, outcome.executedDelta, "the single exit must count exactly one executed redefine");
			assertEquals(0, outcome.gateRejectedDelta, "no gate may reject a passing candidate");
			assertEquals(0, outcome.fieldChangedDelta, "an unchanged field table must not be counted as a change");
			assertEquals(1, outcome.redefinitions.size(), "exactly one redefine call may leave the single exit");
			assertSame(MixinSyntheticGateFixtures.Capturing.class, outcome.redefinitions.get(0).getDefinitionClass(),
				"the redefine must target the loaded class that passed the gates");
			assertArrayEquals(candidate, outcome.redefinitions.get(0).getDefinitionClassFile(),
				"the redefine must carry the candidate bytes exactly (no silent rewriting)");
			assertFalse(MixinInnerClassHotSwap.isBlocked(host), "a passing redefine must not block its host");
		} finally {
			cleanup(name, host);
		}
	}

	// ------------------------------------------------------------ gate 1: host batch blocked

	@Test
	void rejectsEveryCandidateOnceTheHostBatchIsBlocked() {
		RecordingInstrumentation recorder = new RecordingInstrumentation();
		String shrinker = uniqueName("g1-shrinker");
		String victim = uniqueName("g1-victim");
		String host = uniqueHost("g1");
		byte[] shrinking = MixinSyntheticGateFixtures.withoutField(
			MixinSyntheticGateFixtures.Capturing.class, "payload");
		byte[] valid = MixinSyntheticGateFixtures.candidateBytes(MixinSyntheticGateFixtures.Capturing.class);
		remold.RemoldAgent.bytecodeCache.put(victim, valid);
		try {
			// Baseline: a sibling synthetic class under the same host is rejected for a shrinking
			// field table, which blocks the whole host batch (transactional consistency).
			GateOutcome sibling = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, shrinker, shrinking, host);
			assertFalse(sibling.executed, "precondition: the shrinking sibling must be rejected");
			assertTrue(MixinInnerClassHotSwap.isBlocked(host), "precondition: the rejection must block the host batch");

			GateOutcome rejected = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, victim, valid, host);

			assertFalse(rejected.executed, "a blocked batch must refuse a valid candidate instead of half-applying it");
			assertEquals(0, rejected.executedDelta, "no redefine may run while the host batch is blocked");
			assertEquals(1, rejected.gateRejectedDelta, "the blocked-batch gate must count exactly one rejection");
			assertTrue(rejected.redefinitions.isEmpty(), "a blocked batch must not reach redefineClasses");

			// Control: the only difference is the block flag, so the same call must now execute.
			MixinInnerClassHotSwap.clearBlocked(host);
			GateOutcome control = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, victim, valid, host);
			assertTrue(control.executed, "with the block cleared the identical candidate must execute");
			assertEquals(1, control.executedDelta, "the control must reach the exit exactly once");
		} finally {
			cleanup(shrinker, host);
			cleanup(victim, host);
		}
	}

	// ------------------------------------------------------------ gate 2: loaded <init> subset

	@Test
	void rejectsCandidateThatDropsAnAlreadyLoadedConstructor() {
		RecordingInstrumentation recorder = new RecordingInstrumentation();
		String name = uniqueName("g2");
		String host = uniqueHost("g2");
		byte[] dropping = MixinSyntheticGateFixtures.withoutConstructors(MixinSyntheticGateFixtures.Capturing.class);
		// Baseline seeded on purpose: with it the baseline gate cannot fire, so the constructor gate is
		// the only reason this candidate can be refused (keeps the gate-to-case attribution one-to-one).
		remold.RemoldAgent.bytecodeCache.put(
			name, MixinSyntheticGateFixtures.candidateBytes(MixinSyntheticGateFixtures.Capturing.class));
		try {
			GateOutcome rejected = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, dropping, host);

			assertFalse(rejected.executed, "dropping a constructor the loaded class still owns must be refused");
			assertEquals(0, rejected.executedDelta, "a rejected candidate must not be executed");
			assertEquals(1, rejected.gateRejectedDelta, "the constructor gate must count exactly one rejection");
			assertTrue(rejected.redefinitions.isEmpty(), "a rejected candidate must never reach redefineClasses");
			assertTrue(MixinInnerClassHotSwap.isBlocked(host),
				"the host must be blocked so old call sites keep running the previous bytes");

			// Control: same name, constructors kept -> the only removed condition is the gate itself.
			MixinInnerClassHotSwap.clearBlocked(host);
			byte[] candidate = MixinSyntheticGateFixtures.candidateBytes(MixinSyntheticGateFixtures.Capturing.class);
			GateOutcome control = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, candidate, host);
			assertTrue(control.executed, "keeping the loaded constructors must let the candidate through");
			assertEquals(1, control.executedDelta, "the control must reach the exit exactly once");
			assertEquals(0, control.gateRejectedDelta, "the control must not be rejected by any gate");
		} finally {
			cleanup(name, host);
		}
	}

	// ------------------------------------------------------------ gate 3: field table must not shrink

	@Test
	void rejectsCandidateThatWouldShrinkTheFieldTable() {
		RecordingInstrumentation recorder = new RecordingInstrumentation();
		String name = uniqueName("g3");
		String host = uniqueHost("g3");
		byte[] shrinking = MixinSyntheticGateFixtures.withoutField(
			MixinSyntheticGateFixtures.Capturing.class, "payload");
		// Baseline seeded on purpose: the field-table gate must be the only reason this is refused.
		remold.RemoldAgent.bytecodeCache.put(
			name, MixinSyntheticGateFixtures.candidateBytes(MixinSyntheticGateFixtures.Capturing.class));
		try {
			GateOutcome rejected = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, shrinking, host);

			assertFalse(rejected.executed, "a shrinking field table must be refused (it triggers object re-layout)");
			assertEquals(0, rejected.executedDelta, "a rejected candidate must not be executed");
			assertEquals(1, rejected.gateRejectedDelta, "the field-table gate must count exactly one rejection");
			assertTrue(rejected.redefinitions.isEmpty(), "shrinking bytes must never reach redefineClasses");
			assertTrue(MixinInnerClassHotSwap.isBlocked(host), "the host must be blocked after a field-table rejection");

			// Control: the padded candidate keeps the same field table -> it passes.
			MixinInnerClassHotSwap.clearBlocked(host);
			byte[] padded = MixinSyntheticGateFixtures.candidateBytes(MixinSyntheticGateFixtures.Capturing.class);
			GateOutcome control = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, padded, host);
			assertTrue(control.executed, "a candidate that keeps every loaded field must be redefined");
			assertEquals(1, control.executedDelta, "the control must reach the exit exactly once");
			assertEquals(0, control.gateRejectedDelta, "the control must not be rejected by any gate");
		} finally {
			cleanup(name, host);
		}
	}

	// ------------------------------------------------------------ gate 4: baseline bytes required

	@Test
	void rejectsCandidateWhenNoBaselineBytesExist() {
		RecordingInstrumentation recorder = new RecordingInstrumentation();
		String name = uniqueName("g4");
		String host = uniqueHost("g4");
		byte[] candidate = MixinSyntheticGateFixtures.candidateBytes(MixinSyntheticGateFixtures.Capturing.class);
		try {
			GateOutcome rejected = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, candidate, host);

			assertFalse(rejected.executed,
				"without baseline bytes the class was loaded before the agent registered: refuse and ask for a restart");
			assertEquals(0, rejected.executedDelta, "a rejected candidate must not be executed");
			assertEquals(1, rejected.gateRejectedDelta, "the baseline gate must count exactly one rejection");
			assertTrue(rejected.redefinitions.isEmpty(), "a baseline-less candidate must never reach redefineClasses");
			assertTrue(MixinInnerClassHotSwap.isBlocked(host), "the host must be blocked rather than silently degraded");

			// Control: seeding a baseline snapshot is the only difference -> the candidate passes.
			MixinInnerClassHotSwap.clearBlocked(host);
			remold.RemoldAgent.bytecodeCache.put(name, candidate);
			GateOutcome control = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, candidate, host);
			assertTrue(control.executed, "with a baseline snapshot present the same candidate must be redefined");
			assertEquals(1, control.executedDelta, "the control must reach the exit exactly once");
			assertEquals(0, control.gateRejectedDelta, "the control must not be rejected by any gate");
		} finally {
			cleanup(name, host);
		}
	}

	// ------------------------------------------------------------ half-migrated instance

	@Test
	void shrinkingTableNeverReachesTheJvmSoLiveInstancesKeepTheirState() {
		// A live instance is the thing that actually gets damaged when the field table shrinks: the
		// JVM re-lays out every object of the class (ModifyInstances). The gate's contract is that
		// such bytes never reach redefineClasses at all.
		MixinSyntheticGateFixtures.Capturing live = new MixinSyntheticGateFixtures.Capturing(7, "payload");
		assertEquals("v1:7:payload", live.describe(), "precondition: instance state is observable through behaviour");

		RecordingInstrumentation recorder = new RecordingInstrumentation();
		String name = uniqueName("half");
		String host = uniqueHost("half");
		byte[] shrinking = MixinSyntheticGateFixtures.withoutField(
			MixinSyntheticGateFixtures.Capturing.class, "payload");
		// Baseline seeded on purpose: only the field-table gate may refuse this candidate.
		remold.RemoldAgent.bytecodeCache.put(
			name, MixinSyntheticGateFixtures.candidateBytes(MixinSyntheticGateFixtures.Capturing.class));
		try {
			GateOutcome rejected = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, shrinking, host);

			assertFalse(rejected.executed, "the shrinking candidate must be refused");
			assertTrue(rejected.redefinitions.isEmpty(), "no redefine may be attempted, so no object re-layout window opens");
			assertEquals("v1:7:payload", live.describe(),
				"the live instance must keep every field value: a half-migrated instance is the failure this gate prevents");
			assertTrue(MixinInnerClassHotSwap.isBlocked(host),
				"the host must stay blocked so the batch cannot be half-applied either");

			// Control: only the shrinking field is restored, so the execute branch is reachable.
			MixinInnerClassHotSwap.clearBlocked(host);
			byte[] padded = MixinSyntheticGateFixtures.candidateBytes(MixinSyntheticGateFixtures.Capturing.class);
			remold.RemoldAgent.bytecodeCache.put(name, padded);
			GateOutcome control = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, padded, host);
			assertTrue(control.executed, "the field-table-preserving candidate must reach the exit");
			assertEquals("v1:7:payload", live.describe(), "the control must not disturb the live instance either");
		} finally {
			cleanup(name, host);
		}
	}

	// ------------------------------------------------------------ harness

	private static GateOutcome attempt(RecordingInstrumentation recorder, Class<?> live, String name, byte[] bytes,
			String host) {
		int[] before = metrics();
		int callsBefore = recorder.redefinitions.size();
		boolean executed = MixinInnerClassHotSwap.redefineSyntheticSafely(recorder.proxy, live, name, bytes, host);
		int[] after = metrics();
		GateOutcome outcome = new GateOutcome();
		outcome.executed = executed;
		outcome.executedDelta = after[0] - before[0];
		outcome.fieldChangedDelta = after[1] - before[1];
		outcome.gateRejectedDelta = after[2] - before[2];
		outcome.redefinitions.addAll(recorder.redefinitions.subList(callsBefore, recorder.redefinitions.size()));
		return outcome;
	}

	/** Counters reported by {@link MixinInnerClassHotSwap#metricsSummary()} as deltas. */
	private static int[] metrics() {
		int[] out = new int[3];
		for (String part : MixinInnerClassHotSwap.metricsSummary().split("\\s+")) {
			int eq = part.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			int value;
			try {
				value = Integer.parseInt(part.substring(eq + 1).trim());
			} catch (NumberFormatException e) {
				continue;
			}
			String key = part.substring(0, eq).trim();
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

	private static String uniqueName(String tag) {
		return "remold.synthetic.GateFixture$Anonymous$" + tag + "-" + SEQ.incrementAndGet();
	}

	private static String uniqueHost(String tag) {
		return "remold.synthetic.GateFixture$Host" + tag + "-" + SEQ.incrementAndGet();
	}

	private static void cleanup(String name, String host) {
		remold.RemoldAgent.bytecodeCache.remove(name);
		MixinInnerClassHotSwap.clearBlocked(host);
	}

	/** Outcome of one call into the single exit, expressed as counter deltas. */
	private static final class GateOutcome {
		boolean executed;
		int executedDelta;
		int fieldChangedDelta;
		int gateRejectedDelta;
		final List<ClassDefinition> redefinitions = new ArrayList<>();
	}

	/**
	 * Recording {@link Instrumentation}: the unit-test JVM has no agent, so the only observable at
	 * this level is "did the call reach {@code redefineClasses}, and with which bytes".
	 */
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
				ClassDefinition[] definitions = (ClassDefinition[]) args[0];
				for (ClassDefinition definition : definitions) {
					this.redefinitions.add(definition);
				}
				return null;
			}
			if ("isRedefineClassesSupported".equals(name) || "isRetransformClassesSupported".equals(name)) {
				return Boolean.TRUE;
			}
			if ("isModifiableClass".equals(name)) {
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
