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
 * {@value #GATE_CASE_COUNT} {@code @Test} methods. The numbers that show up in reports count
 * different things, and two of them now coincide numerically — that is a coincidence, not a
 * synonym:</p>
 * <table border="1">
 *   <caption>Ledger</caption>
 *   <tr><th>case</th><th>gate</th><th>kind</th><th>in-method control</th></tr>
 *   <tr><td>{@code passingCandidateIsRedefinedExactlyOnceThroughTheSingleExit}</td>
 *       <td>single exit (all four gates pass)</td><td>pass</td><td>no</td></tr>
 *   <tr><td>{@code failedRedefineNeverMovesTheBaselineAndTheChainKeepsOnlyAcceptedBytes}</td>
 *       <td>baseline chain</td><td>pass</td><td>no</td></tr>
 *   <tr><td>{@code rejectsEveryCandidateOnceTheHostBatchIsBlocked}</td>
 *       <td>1 — host batch BLOCKED</td><td>reject</td><td>yes (clear the flag)</td></tr>
 *   <tr><td>{@code rejectsCandidateThatDropsAnAlreadyLoadedConstructor}</td>
 *       <td>2 — loaded {@code <init>} subset</td><td>reject</td><td>yes (keep the ctors)</td></tr>
 *   <tr><td>{@code rejectsCandidateThatWouldShrinkTheFieldTable}</td>
 *       <td>3 — field table must not shrink</td><td>reject</td><td>yes (keep the field)</td></tr>
 *   <tr><td>{@code rejectsCandidateWhenNoBaselineBytesExist}</td>
 *       <td>4 — baseline bytes required</td><td>reject</td><td>yes (seed the baseline)</td></tr>
 *   <tr><td>{@code shrinkingTableNeverReachesTheJvmSoLiveInstancesKeepTheirState}</td>
 *       <td>3 — half-migrated instance, shrink direction</td><td>half</td><td>yes (keep the field)</td></tr>
 *   <tr><td>{@code expandedTableKeepsTheOldConstructorAndLeavesTheNewReferenceFieldNull}</td>
 *       <td>3 — half-migrated instance, expansion direction</td><td>half</td><td>yes (grown ctor)</td></tr>
 * </table>
 * <p>So the three numbers in circulation count different things:</p>
 * <ul>
 *   <li><b>{@value #GATE_CASE_COUNT}</b> {@code @Test} methods = {@code 2 pass + 4 rejections +
 *       2 half-migrated};</li>
 *   <li><b>{@value #GATE_GROUP_COUNT}</b> gate-scoped assertion groups = {@code 4 gates ×
 *       (rejection + its control)} — a different thing that happens to be the same integer now
 *       that the suite has both half-migrated directions;</li>
 *   <li><b>{@value #CONTROL_COUNT}</b> in-method controls = the 4 gate controls plus the two
 *       half-migrated cases' respective restore-field / grown-constructor controls.</li>
 * </ul>
 * <p>The controls are deliberately <b>inside</b> their rejection method instead of being separate
 * {@code @Test}s: a control only carries causal weight when it re-runs the identical name, host
 * and bytes after clearing exactly one condition, and a standalone control case goes green on its
 * own while proving nothing about the rejection it belongs to.
 * {@link #caseLedgerMatchesTheSuite()} pins the ledger mechanically (name set, per-gate coverage
 * and the pass/reject/half arithmetic), so no report can drift away from the code.</p>
 *
 * <p><b>JVM-level evidence</b>: the half-migrated semantics above run here against a recording
 * proxy for the redefine itself, and against a real {@code defineClass} of the identical candidate
 * bytes for the instance semantics. {@code MixinSyntheticGateProbe} additionally replays the same
 * gate decisions on a real {@link Instrumentation} (real {@code redefineClasses}, including the
 * expansion direction with a live pre-existing instance), and counts a host without enhanced
 * redefinition as {@code SKIP(non-jbr)} separately instead of folding it into a pass.</p>
 */
class MixinSyntheticGateTest {

	/**
	 * @Test methods in this suite. Changing this number means editing the Javadoc ledger too —
	 * {@link #caseLedgerMatchesTheSuite()} fails otherwise.
	 */
	// A2 合入后新增「失败 redefine 不改基线 + R0→R1→R2 链」用例（kind=pass）。
	// C3 新增「扩容 + 保留旧 <init> 的半残实例」用例（kind=half），与缩容方向并列。
	static final int GATE_CASE_COUNT = 8;

	/** Rejections that must each own exactly one in-method control. */
	static final int REJECTION_CASE_COUNT = 4;

	/** Gate-scoped assertion groups = {@code REJECTION_CASE_COUNT × 2} (distinct from the case count). */
	static final int GATE_GROUP_COUNT = 8;

	/** In-method controls: one per rejection, plus one for each half-migrated direction. */
	static final int CONTROL_COUNT = 6;

	/** {@code name, gate, kind} for every {@code @Test} method; {@code kind} is pass/control-driven. */
	private record Case(String method, String gate, String kind, boolean control) {
	}

	private static final Case[] LEDGER = {
		new Case("passingCandidateIsRedefinedExactlyOnceThroughTheSingleExit", "single-exit", "pass", false),
		new Case("failedRedefineNeverMovesTheBaselineAndTheChainKeepsOnlyAcceptedBytes", "baseline-chain", "pass", false),
		new Case("rejectsEveryCandidateOnceTheHostBatchIsBlocked", "gate1", "reject", true),
		new Case("rejectsCandidateThatDropsAnAlreadyLoadedConstructor", "gate2", "reject", true),
		new Case("rejectsCandidateThatWouldShrinkTheFieldTable", "gate3", "reject", true),
		new Case("rejectsCandidateWhenNoBaselineBytesExist", "gate4", "reject", true),
		new Case("shrinkingTableNeverReachesTheJvmSoLiveInstancesKeepTheirState", "gate3-half-shrink", "half", true),
		new Case("expandedTableKeepsTheOldConstructorAndLeavesTheNewReferenceFieldNull", "gate3-half-expand", "half",
			true),
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
		assertEquals(2, pass, "two passing cases: the single exit once + the accepted-bytes baseline chain");
		assertEquals(REJECTION_CASE_COUNT, rejections, "every gate owns exactly one rejection case");
		assertEquals(2, half, "the half-migrated semantics needs both directions: shrink (blocked by gate 3)"
			+ " and expansion (accepted, old <init> retained)");
		assertEquals(CONTROL_COUNT, controls,
			"every rejection and both half-migrated cases must carry an in-method control");
		assertEquals(REJECTION_CASE_COUNT + 2, controls,
			"controls are the 4 gate controls plus the two half-migrated cases' controls");
		assertEquals(GATE_GROUP_COUNT, REJECTION_CASE_COUNT * 2,
			"gate-scoped assertion groups are 4 gates x (rejection + control); they are NOT the case count"
				+ " even when the two integers happen to be equal");
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
		MixinSyntheticGateFixtures.seedBaseline(definingLoader(), name, candidate);
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
		MixinSyntheticGateFixtures.seedBaseline(definingLoader(), victim, valid);
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
		MixinSyntheticGateFixtures.seedBaseline(definingLoader(), name,
			MixinSyntheticGateFixtures.candidateBytes(MixinSyntheticGateFixtures.Capturing.class));
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
		MixinSyntheticGateFixtures.seedBaseline(definingLoader(), name,
			MixinSyntheticGateFixtures.candidateBytes(MixinSyntheticGateFixtures.Capturing.class));
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
			MixinSyntheticGateFixtures.seedBaseline(definingLoader(), name, candidate);
			GateOutcome control = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, candidate, host);
			assertTrue(control.executed, "with a baseline snapshot present the same candidate must be redefined");
			assertEquals(1, control.executedDelta, "the control must reach the exit exactly once");
			assertEquals(0, control.gateRejectedDelta, "the control must not be rejected by any gate");
		} finally {
			cleanup(name, host);
		}
	}

	// ------------------------------------------------------------ baseline chain (invariant C2)

	/**
	 * The baseline is "the bytes the JVM actually loaded", so the chain only advances on a redefine
	 * the JVM accepted: {@code R0} (premain capture) -> {@code R1} (first accepted redefine) ->
	 * {@code R2} (second accepted redefine).
	 *
	 * <p>Both halves sit in one case on purpose, because a chain can break in two opposite ways:</p>
	 * <ul>
	 *   <li>a <b>rejected</b> redefine must not become the baseline — otherwise the next batch would
	 *       treat never-loaded bytes as the live shape (the exact silent degradation C2 forbids);</li>
	 *   <li>an <b>accepted</b> redefine must move the baseline — otherwise the chain freezes at the
	 *       premain capture {@code R0} and every later batch is reasoned about against stale bytes.</li>
	 * </ul>
	 *
	 * <p>All three generations keep the same member schema ({@code SourceFile} differs only), so the
	 * constructor/field gates cannot be the reason a step is refused: the only difference between the
	 * refused and the accepted attempt is the JVM's own verdict.</p>
	 */
	@Test
	void failedRedefineNeverMovesTheBaselineAndTheChainKeepsOnlyAcceptedBytes() {
		RecordingInstrumentation recorder = new RecordingInstrumentation();
		ClassLoader loader = definingLoader();
		String name = uniqueName("chain");
		String host = uniqueHost("chain");
		byte[] r0 = MixinSyntheticGateFixtures.candidateBytes(MixinSyntheticGateFixtures.Capturing.class);
		byte[] r1 = MixinSyntheticGateFixtures.tagged(MixinSyntheticGateFixtures.Capturing.class, "R1");
		byte[] r2 = MixinSyntheticGateFixtures.tagged(MixinSyntheticGateFixtures.Capturing.class, "R2");
		MixinSyntheticGateFixtures.seedBaseline(loader, name, r0);
		try {
			assertArrayEquals(r0, MixinInnerClassHotSwap.baselineBytes(loader, name),
				"precondition: R0 is the premain-captured baseline for this loader");

			// Step R1: the JVM rejects the redefine. The gate must report the refusal and leave R0.
			recorder.rejectNextRedefine = true;
			GateOutcome refusedR1 = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, r1, host);
			assertFalse(refusedR1.executed, "a redefine the JVM rejects must not be reported as executed");
			assertEquals(1, refusedR1.redefinitions.size(),
				"the refused attempt must have reached redefineClasses (otherwise the baseline gate, not the JVM,"
					+ " refused it and this case would be vacuous)");
			assertArrayEquals(r0, MixinInnerClassHotSwap.baselineBytes(loader, name),
				"a REFUSED redefine must never become the baseline: the baseline stays R0");

			// Step R1 again, accepted: the only changed condition is the JVM verdict.
			MixinInnerClassHotSwap.clearBlocked(host);
			GateOutcome acceptedR1 = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, r1, host);
			assertTrue(acceptedR1.executed, "with the block cleared the identical candidate must be accepted");
			assertArrayEquals(r1, MixinInnerClassHotSwap.baselineBytes(loader, name),
				"the chain takes the bytes that actually went live: R0 -> R1");

			// Step R2 refused: R1 must survive.
			recorder.rejectNextRedefine = true;
			GateOutcome refusedR2 = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, r2, host);
			assertFalse(refusedR2.executed, "a refused R2 must not be reported as executed");
			assertArrayEquals(r1, MixinInnerClassHotSwap.baselineBytes(loader, name),
				"a refused R2 must leave R1 as the baseline");

			// Step R2 accepted: the chain advances to R2.
			MixinInnerClassHotSwap.clearBlocked(host);
			GateOutcome acceptedR2 = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, r2, host);
			assertTrue(acceptedR2.executed, "the R2 candidate satisfies every gate and must be accepted");
			assertArrayEquals(r2, MixinInnerClassHotSwap.baselineBytes(loader, name),
				"the chain ends on R2: neither the premain capture R0 nor R1 may be served as the baseline");
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
		MixinSyntheticGateFixtures.seedBaseline(definingLoader(), name,
			MixinSyntheticGateFixtures.candidateBytes(MixinSyntheticGateFixtures.Capturing.class));
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
			MixinSyntheticGateFixtures.seedBaseline(definingLoader(), name, padded);
			GateOutcome control = attempt(recorder, MixinSyntheticGateFixtures.Capturing.class, name, padded, host);
			assertTrue(control.executed, "the field-table-preserving candidate must reach the exit");
			assertEquals("v1:7:payload", live.describe(), "the control must not disturb the live instance either");
		} finally {
			cleanup(name, host);
		}
	}

	// ------------------------------------------------------------ half-migrated instance (expansion)

	/**
	 * Expansion direction of the half-migrated-instance semantics (invariant C3).
	 *
	 * <p>The capture list grows from two members to four ({@code + val$b:Object},
	 * {@code + val$c:int}) and the loaded 2-arg {@code <init>} survives because
	 * {@link MixinSyntheticSchema#retainConstructors} copies it out of the loaded bytes. That
	 * candidate is <b>accepted</b> by all four gates — nothing shrinks and the loaded constructor
	 * set stays a subset — so the question this case answers is not "does the gate refuse it" but
	 * "what does an instance built by the kept old constructor do afterwards".</p>
	 *
	 * <p>Measured, in two mechanical halves:</p>
	 * <ol>
	 *   <li>the retained constructor <b>is the old body</b>: its {@code PUTFIELD} set is exactly
	 *       {@code this$0, val$a}, so the new {@code val$b} stays {@code null} and {@code val$c}
	 *       stays {@code 0};</li>
	 *   <li>calling the <b>new</b> body on such an instance throws {@link NullPointerException} at
	 *       {@code test()} (the read of {@code val$b}), with the full stack asserted, not merely
	 *       "some exception".</li>
	 * </ol>
	 *
	 * <p><b>Why the shrink case is not a substitute</b>: {@link
	 * #shrinkingTableNeverReachesTheJvmSoLiveInstancesKeepTheirState} proves gate 3 keeps a
	 * shrinking table away from the JVM. That is the opposite direction; it never exercises the
	 * expansion that a growing capture list produces, and it cannot show the NPE.</p>
	 *
	 * <p><b>Scope</b>: the unit-test JVM has no agent, so the redefine itself is the recording
	 * proxy. The instance semantics therefore runs the identical candidate bytes through a real
	 * {@code defineClass} in a private loader under the loaded name. The real
	 * {@code redefineClasses} variant (a JVM-migrated pre-existing instance included) runs in
	 * {@code MixinSyntheticGateProbe} on a JBR/DCEVM host; a host without enhanced redefinition is
	 * counted there as {@code SKIP(non-jbr)}, never as a pass.</p>
	 */
	@Test
	void expandedTableKeepsTheOldConstructorAndLeavesTheNewReferenceFieldNull() {
		assertEquals(java.util.Set.of("this$0:Ljava/lang/Object;", "val$a:Ljava/lang/Object;"),
			new java.util.LinkedHashSet<>(MixinSyntheticSchema.loadedFields(HalfInitOld.class)),
			"precondition: the loaded class must be the 2-field shape, otherwise this is not an expansion");
		Object outer = new Object();
		assertEquals("v1:a", new HalfInitOld(outer, "a").test(),
			"precondition: the 2-field body reads its captured field before any change");

		MixinSyntheticHalfInitFixtures.Candidate candidate = MixinSyntheticHalfInitFixtures.expandedCandidate();
		assertEquals(java.util.Set.of("this$0:Ljava/lang/Object;", "val$a:Ljava/lang/Object;",
			"val$b:Ljava/lang/Object;", "val$c:I"), new java.util.LinkedHashSet<>(candidate.fields()),
			"the candidate must be a strict expansion: the two captured fields plus a NEW REFERENCE field"
				+ " and a primitive one");
		assertEquals(4, candidate.fields().size(), "the growth case adds exactly two fields, no padding");
		assertEquals(List.of(MixinSyntheticHalfInitFixtures.GROWN_CTOR_DESC, MixinSyntheticHalfInitFixtures.OLD_CTOR_DESC),
			candidate.constructors(),
			"the tombstone union must keep BOTH constructors: the grown capture list and the loaded 2-arg one");
		assertEquals(List.of(MixinSyntheticHalfInitFixtures.OLD_CTOR_DESC), candidate.retained(),
			"the 2-arg <init> exists in the candidate only because it was retained from the loaded bytes");
		assertEquals(List.of("this$0", "val$a"), candidate.writesOfRetainedConstructor(),
			"the retained <init> is the OLD body copied verbatim: it writes only the two old fields, so it cannot"
				+ " initialise val$b / val$c");

		RecordingInstrumentation recorder = new RecordingInstrumentation();
		String name = uniqueName("expand");
		String host = uniqueHost("expand");
		MixinSyntheticGateFixtures.seedBaseline(definingLoader(), name, MixinSyntheticHalfInitFixtures.oldShapeBytes());
		try {
			assertArrayEquals(MixinSyntheticHalfInitFixtures.oldShapeBytes(),
				MixinInnerClassHotSwap.baselineBytes(definingLoader(), name),
				"precondition: the baseline is the bytes the JVM actually loaded, i.e. the 2-field shape");

			GateOutcome outcome = attempt(recorder, HalfInitOld.class, name, candidate.bytes(), host);

			assertTrue(outcome.executed, "an expansion must pass all four gates: no field shrinks and the retained"
				+ " <init> keeps the loaded constructor set a subset");
			assertEquals(0, outcome.gateRejectedDelta, "no gate may reject the expansion");
			assertEquals(1, outcome.fieldChangedDelta, "an expanded field table must be counted as a field-table"
				+ " change — that is the signal that the JVM re-lays out instances");
			assertEquals(1, outcome.redefinitions.size(), "exactly one redefine call may leave the single exit");
			assertArrayEquals(candidate.bytes(), outcome.redefinitions.get(0).getDefinitionClassFile(),
				"the bytes handed to redefineClasses must be the retained-constructor candidate, so the semantics"
					+ " measured below is the semantics the JVM would install");

			// The identical bytes, defined under the loaded name in a private loader: same shape as after a
			// real redefine, minus the redefine machinery the unit-test JVM cannot drive.
			Class<?> expanded = MixinSyntheticHalfInitFixtures.loadInFreshLoader(candidate.bytes());
			Object half = MixinSyntheticHalfInitFixtures.newInstance(expanded,
				new Class<?>[]{Object.class, Object.class}, outer, "a");

			Throwable npe = MixinSyntheticHalfInitFixtures.failureOf(half, MixinSyntheticHalfInitFixtures.BODY);
			assertTrue(npe instanceof NullPointerException, "the NEW body reads the new reference field, and the"
				+ " retained OLD constructor never assigned it: expected a NullPointerException, got " + npe
				+ (npe == null ? "" : "\nfull stack:\n" + MixinSyntheticHalfInitFixtures.stackOf(npe)));
			StackTraceElement top = npe.getStackTrace()[0];
			assertEquals(MixinSyntheticHalfInitFixtures.BODY, top.getMethodName(),
				"the NPE must be thrown by the new body's read, not by the constructor:\n"
					+ MixinSyntheticHalfInitFixtures.stackOf(npe));
			assertEquals(MixinSyntheticHalfInitFixtures.OLD_DOTTED, top.getClassName(),
				"the failing frame must be the expanded class itself:\n" + MixinSyntheticHalfInitFixtures.stackOf(npe));
			assertTrue(npe.getMessage() != null && npe.getMessage().contains("val$b"),
				"the NPE must name the new reference field, otherwise the observation does not pin WHICH field"
					+ " stayed null; message=" + npe.getMessage());

			assertEquals(0, ((Integer) MixinSyntheticHalfInitFixtures.call(half,
				MixinSyntheticHalfInitFixtures.COUNT)).intValue(),
				"the retained <init> never writes val$c either, so the new primitive keeps the JVM default 0");

			// Control: the ONLY difference is which constructor built the instance. The grown constructor
			// does assign the new fields, so the identical new body returns normally.
			Object fresh = MixinSyntheticHalfInitFixtures.newInstance(expanded,
				new Class<?>[]{Object.class, Object.class, Object.class, int.class}, outer, "a", "b", 7);
			assertEquals("v2:b:7", MixinSyntheticHalfInitFixtures.call(fresh, MixinSyntheticHalfInitFixtures.BODY),
				"control: with the new fields initialised the same body returns normally, so the NPE above is"
					+ " caused by the retained constructor, not by the new body");
			assertEquals(7, ((Integer) MixinSyntheticHalfInitFixtures.call(fresh,
				MixinSyntheticHalfInitFixtures.COUNT)).intValue(),
				"control: the primitive capture is readable when the grown constructor wrote it");
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

	/**
	 * Loader that actually defined the fixture class: the recorder keys baselines by loader
	 * identity, so every seeded baseline must use exactly this one.
	 */
	private static ClassLoader definingLoader() {
		return MixinSyntheticGateFixtures.Capturing.class.getClassLoader();
	}

	/**
	 * Unique synthetic-class name per case. Deliberately outside the {@code remold.} package: the
	 * premain recorder drops {@code remold/} names (its own classes), so a {@code remold.} name would
	 * never be recorded and the seeding helper would fail loudly instead of testing the gate.
	 */
	private static String uniqueName(String tag) {
		return "gatefix.GateFixture$Anonymous$" + tag + "-" + SEQ.incrementAndGet();
	}

	private static String uniqueHost(String tag) {
		return "gatefix.GateFixture$Host" + tag + "-" + SEQ.incrementAndGet();
	}

	private static void cleanup(String name, String host) {
		MixinSyntheticGateFixtures.forgetBaseline(definingLoader(), name);
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
		/** One-shot failure injection: the JVM-side rejection of a redefineClasses call. */
		boolean rejectNextRedefine;
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
				if (this.rejectNextRedefine) {
					// The call is recorded first on purpose: "reached redefineClasses" and "the JVM
					// accepted it" are two different facts, and the chain case must assert both.
					this.rejectNextRedefine = false;
					throw new UnsupportedOperationException("simulated JVM rejection of the redefine");
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
