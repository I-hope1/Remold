package remold.mixin;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import remold.RemoldAgent;

/**
 * JVM-level replay of the synthetic-inner-class gate suite (invariant C3).
 *
 * <p><b>Why it exists</b>: {@code MixinSyntheticGateTest} runs without {@code -javaagent}, so its
 * {@link Instrumentation} is a recording proxy — it can assert which calls reach the single exit,
 * but not what the JVM does with them. In particular the half-migrated-instance case (a shrinking
 * field table must never reach {@code redefineClasses}) is asserted there only up to the call
 * boundary. This probe replays the same decisions against a real {@link Instrumentation} so the
 * instance-state claim is settled by the JVM itself:</p>
 * <ol>
 *   <li><b>P1 body-only update</b> (any JVM): redefine the committed fixture to a version whose
 *       {@code describe()} returns {@code v2:...}. Asserts both halves at once — the JVM accepted
 *       the new body <em>and</em> the instance created before the redefine kept its values.</li>
 *   <li><b>P2 the gate on a real JVM</b>: hand the single exit a capture-list shrink. Asserts it is
 *       refused, the host is blocked, the counters move by exactly one rejection, and the live
 *       instance still reads the same state — i.e. the JVM was never asked to migrate it.</li>
 *   <li><b>P3 the negative control that P2 buys</b> (enhanced host only): bypass the gate and call
 *       {@code redefineClasses} with those same shrinking bytes. On a JBR/DCEVM host the JVM
 *       <em>accepts</em> the shrink the gate refuses; this case then asserts that the pre-existing
 *       instance's undeleted fields survived the migration (the deferred-failure boundary — stale
 *       readers of the deleted field — is out of scope here because the fixture keeps no reader of
 *       {@code spare}). On a host without enhanced redefinition the JVM refuses the schema change
 *       before it can be observed; that case is counted as {@code SKIP(non-jbr)} <b>separately</b>
 *       so "did not run" can never be read as "passed".</li>
 * </ol>
 *
 * <p><b>Fixtures stay in the repo</b>: the bytes come from the committed
 * {@link MixinSyntheticGateFixtures} on the test runtime classpath — the very classes the unit
 * suite uses. Nothing is generated at run time, so no scratch/tmp fixture can silently diverge from
 * what was committed. The fixture lives under {@code remold/}, a namespace the premain recorder
 * intentionally skips, so gate 4 is satisfied through its documented legacy source (a seeded
 * bytecode cache) and the probe asserts that precondition explicitly — leave it out and a
 * gate-3 refusal would be indistinguishable from a gate-4 refusal.</p>
 *
 * <p><b>Run</b> (needs a real agent for {@link RemoldAgent#getInstrumentation()}; {@code ASM} jars
 * are the project's {@code asmVersion}):</p>
 * <pre>
 * java -javaagent:core/build/libs/remold-agent.jar -cp
 *   "tests/build/classes/java/main;plugin-mixin/build/classes/java/main;core/build/libs/remold-agent.jar;&lt;asm jars&gt;"
 *   remold.mixin.MixinSyntheticGateProbe
 * </pre>
 * <p>Optional artifact pinning (the "same hash as the template" check):
 * {@code -Dgateprobe.expectAgentMd5=<md5> -Dgateprobe.expectPluginMd5=<md5>} makes the probe fail
 * unless the jars it actually loaded match, so a green line cannot come from a differently built
 * agent/plugin.</p>
 */
public final class MixinSyntheticGateProbe {

	/**
	 * Committed fixture under test. It lives under {@code remold/}, so the premain recorder
	 * deliberately skips it (that namespace belongs to the agent); gate 4 is therefore satisfied the
	 * same way the unit suite satisfies it — an explicitly seeded bytecode cache, the gate's
	 * documented legacy source — so the field-table gate stays the only possible reason for the
	 * refusal in P2.
	 */
	private static final String FIXTURE = "remold.mixin.MixinSyntheticGateFixtures$Drifting";

	private static final String HOST = "remold.synthetic.GateProbe$Host";

	private static int passed;
	private static int skippedNonJbr;
	private static final List<String> skips = new ArrayList<>();
	private static final Map<String, String> notes = new LinkedHashMap<>();

	private MixinSyntheticGateProbe() {
	}

	public static void main(String[] args) throws Exception {
		Instrumentation inst = RemoldAgent.getInstrumentation();
		require(inst != null, "run this probe with -javaagent:core/build/libs/remold-agent.jar");

		Class<?> live0 = MixinSyntheticGateFixtures.Drifting.class;
		// Fixtures stay in the repo: these are the class-file bytes compiled from
		// tests/src/remold/mixin/MixinSyntheticGateFixtures.java, i.e. exactly what the unit suite runs.
		byte[] committed = MixinSyntheticGateFixtures.bytesOf(live0);
		require(Arrays.equals(committed, MixinSyntheticGateFixtures.candidateBytes(live0)),
			"the probe must run on the committed fixture bytes, not on a generated copy");
		require(MixinSyntheticSchema.loadedFields(live0).toString().contains("spare"),
			"precondition: the fixture must declare the droppable field, otherwise the shrink models nothing: "
				+ MixinSyntheticSchema.loadedFields(live0));

		// Gate 4 pre-satisfied through its documented legacy source, so P2 isolates gate 3.
		remold.RemoldAgent.bytecodeCache.put(FIXTURE, committed);
		require(MixinInnerClassHotSwap.baselineMissingReason(live0.getClassLoader(), FIXTURE) == null,
			"precondition: the baseline must be resolvable, else the refusal could come from gate 4");

		// ---------------------------------------------------------------- P1: body-only, any JVM
		MixinSyntheticGateFixtures.Drifting live =
			new MixinSyntheticGateFixtures.Drifting(7, "spare-marker", "payload");
		String beforeP1 = live.describe();
		require("v1:7:payload".equals(beforeP1),
			"precondition: instance state must be observable through behaviour, got " + beforeP1);
		require(!beforeP1.contains("spare-marker"),
			"precondition: the droppable field must have no reader, else dropping it would be an invalid candidate");
		byte[] bodyOnly = MixinSyntheticGateFixtures.renamedConstant(
			MixinSyntheticGateFixtures.Drifting.class, "v1:", "v2:");
		inst.redefineClasses(new ClassDefinition(live0, bodyOnly));
		String afterP1 = live.describe();
		require("v2:7:payload".equals(afterP1),
			"a real body-only redefine must swap the body AND keep the live instance's field values:"
				+ " expected v2:7:payload, got " + afterP1);
		passed++;
		notes.put("p1-body-only-redefine", "desc=" + afterP1);

		// ---------------------------------------------------------------- P2: the gate, real JVM
		byte[] shrink = MixinSyntheticGateFixtures.withoutField(
			MixinSyntheticGateFixtures.Drifting.class, "spare");
		int[] before = metrics();
		boolean executed = MixinInnerClassHotSwap.redefineSyntheticSafely(inst, live0, FIXTURE, shrink, HOST);
		int[] after = metrics();
		require(!executed, "the gate must refuse a capture-list shrink on a real JVM too");
		require(after[0] - before[0] == 0, "no redefine may reach the JVM while the field table would shrink");
		require(after[2] - before[2] == 1, "the field-table gate must count exactly one rejection, got "
			+ (after[2] - before[2]));
		require(MixinInnerClassHotSwap.isBlocked(HOST),
			"the host must be blocked so the batch cannot be half-applied");
		String afterP2 = live.describe();
		require("v2:7:payload".equals(afterP2),
			"the live instance must keep every field value: the JVM never received the shrinking bytes, got "
				+ afterP2);
		MixinInnerClassHotSwap.clearBlocked(HOST);
		passed++;
		notes.put("p2-gate-real-jvm", "rejected=1 executed=0 state=" + afterP2);

		// ------------------------------- P3: negative control (JBR/DCEVM only, counted separately)
		if (!RemoldAgent.isEnhancedHotswapEnabled()) {
			skipNonJbr("half-migrated control (P3): host has no enhanced redefinition, so the JVM"
				+ " refuses a field-table shrink before the migration can be observed");
		} else {
			boolean observable = true;
			try {
				inst.redefineClasses(new ClassDefinition(live0, shrink));
			} catch (Throwable t) {
				// A host that advertises enhanced redefinition can still refuse this shape; that is an
				// observation, not a pass, so it is counted as a skip with the JVM's own reason.
				observable = false;
				skipNonJbr("half-migrated control (P3): the JVM refused the schema shrink: " + brief(t));
			}
			if (observable) {
				// The shrink candidate is derived from the committed bytes, so this redefine also
				// reverts the body from v2 to v1 — which is itself the control that the JVM really
				// applied these bytes. Reading v1 back therefore means: body swapped AND both
				// undeleted fields (value, payload) survived the instance migration.
				String afterP3 = live.describe();
				require("v1:7:payload".equals(afterP3),
					"the enhanced JVM accepted the shrink the gate refuses, so the body must revert to the"
						+ " committed v1 and the instance migration must keep the undeleted fields readable:"
						+ " expected v1:7:payload, got " + afterP3);
				passed++;
				notes.put("p3-enhanced-shrink-accepted", "state=" + afterP3);
			}
		}

		// ---------------------------------------------------------------- artifact identity
		// The agent jar is appended to the bootstrap search, so its loaded classes carry no
		// CodeSource; the jars are therefore pinned explicitly instead of guessed.
		String agentMd5 = md5OfArtifact("gateprobe.agentJar");
		String pluginMd5 = md5OfArtifact("gateprobe.pluginJar");
		requireMatches("gateprobe.expectAgentMd5", agentMd5, "gateprobe.agentJar",
			"the staged agent jar must be the artifact this run actually used");
		requireMatches("gateprobe.expectPluginMd5", pluginMd5, "gateprobe.pluginJar",
			"the staged plugin-mixin artifact must be the artifact this run actually used");

		int cases = passed + skippedNonJbr;
		System.out.println("RESULT PASS cases=" + cases + " pass=" + passed + " fail=0 skip(non-jbr)="
			+ skippedNonJbr + " enhanced=" + RemoldAgent.isEnhancedHotswapEnabled());
		for (String skip : skips) {
			System.out.println("SKIP(non-jbr) " + skip);
		}
		for (Map.Entry<String, String> note : notes.entrySet()) {
			System.out.println("OBSERVED " + note.getKey() + " " + note.getValue());
		}
		System.out.println("ARTIFACTS agent-md5=" + (agentMd5 == null ? "(unpinned)" : agentMd5)
			+ " plugin-md5=" + (pluginMd5 == null ? "(unpinned)" : pluginMd5));
	}

	// ---------------------------------------------------------------- helpers

	private static void skipNonJbr(String why) {
		skippedNonJbr++;
		skips.add(why);
	}

	/** Counters reported by {@link MixinInnerClassHotSwap#metricsSummary()}: executed, fields, rejected. */
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

	private static void requireMatches(String expectProperty, String actual, String artifactProperty,
			String message) {
		String expected = System.getProperty(expectProperty);
		if (expected == null || expected.isBlank()) {
			return;
		}
		require(actual != null, message + ": pass -D" + artifactProperty + "=<file> together with -D"
			+ expectProperty + ", otherwise the hash cannot be checked");
		require(expected.trim().equalsIgnoreCase(actual), message + ": expected " + expected.trim()
			+ " but the artifact hashes to " + actual);
	}

	/** md5 of an explicitly pinned artifact; {@code null} when the property was not supplied. */
	private static String md5OfArtifact(String property) throws Exception {
		String raw = System.getProperty(property);
		if (raw == null || raw.isBlank()) {
			return null;
		}
		Path artifact = Paths.get(raw.trim());
		require(Files.isRegularFile(artifact), property + " must point at a file: " + artifact);
		byte[] digest = MessageDigest.getInstance("MD5").digest(Files.readAllBytes(artifact));
		StringBuilder hex = new StringBuilder(digest.length * 2);
		for (byte b : digest) {
			hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return hex.toString();
	}

	private static String brief(Throwable t) {
		String message = t.getMessage();
		String text = t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
		return text.length() > 200 ? text.substring(0, 200) + "..." : text;
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalStateException("synthetic gate probe FAILED: " + message);
		}
	}
}
