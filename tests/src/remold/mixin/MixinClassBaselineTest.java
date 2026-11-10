package remold.mixin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Premain baseline recorder contract (invariant C2).
 *
 * <p>The recorder is a pure <em>observer</em>: it captures the bytes the JVM really defines
 * (after the Mixin/Knot transformation, i.e. after every rewriter ran) and hands them to the
 * synthetic-class redefine gate. It never rewrites a byte, it never keeps a strong reference to a
 * class loader, and it keys entries by <b>(loader identity, class name)</b> — a dot-name-only table
 * cannot tell two loaders apart and would silently accept the wrong baseline.</p>
 *
 * <p>The gate side is the negative control of the same contract: a class whose baseline was never
 * captured (loaded before the agent registered) must be refused with a {@code restart} hint instead
 * of degrading silently.</p>
 */
class MixinClassBaselineTest {

	private static final String DOTTED = "fixture.Target$Anonymous$deadbeef";
	private static final String INTERNAL = "fixture/Target$Anonymous$deadbeef";

	/** No parent loader: instances differ only by identity, which is exactly what the key must use. */
	private static final class IsolatedLoader extends ClassLoader {
		IsolatedLoader() {
			super("isolated", null);
		}
	}

	private static ClassFileTransformer recorder() {
		return MixinClassBaseline.recorder();
	}

	/** Feeds one transformer event and asserts the recorder never rewrites (returns {@code null}). */
	private static void observe(ClassLoader loader, String internalName, Class<?> beingRedefined, byte[] bytes) {
		byte[] rewritten;
		try {
			rewritten = recorder().transform(loader, internalName, beingRedefined, null, bytes);
		} catch (IllegalClassFormatException e) {
			throw new AssertionError("the recorder must never reject a class format at define time", e);
		}
		assertNull(rewritten, "the baseline recorder must return null: it observes bytes, it never changes them");
	}

	@BeforeEach
	void resetBaseline() {
		MixinClassBaseline.clearForTest();
	}

	// ------------------------------------------------------------ record only, never rewrite

	@Test
	void recorderReturnsNullAndKeepsAPrivateCopy() {
		ClassLoader loader = new IsolatedLoader();
		byte[] defined = new byte[] {1, 2, 3, 4};

		observe(loader, INTERNAL, null, defined);

		byte[] baseline = MixinClassBaseline.baselineOf(loader, DOTTED);
		assertNotNull(baseline, "a defineClass event must be recorded under the (loader, name) key");
		assertArrayEquals(defined, baseline, "the baseline must be the bytes the JVM actually defined");
		assertNotSame(defined, baseline,
			"the recorder must store a copy: the JVM-owned array may be reused after the transformer returns");
	}

	@Test
	void baselineIsKeyedByLoaderIdentityNotByClassName() {
		ClassLoader first = new IsolatedLoader();
		ClassLoader second = new IsolatedLoader();

		observe(first, INTERNAL, null, new byte[] {1});
		observe(second, INTERNAL, null, new byte[] {2});

		assertArrayEquals(new byte[] {1}, MixinClassBaseline.baselineOf(first, DOTTED),
			"each loader must keep its own baseline for the same class name");
		assertArrayEquals(new byte[] {2}, MixinClassBaseline.baselineOf(second, DOTTED),
			"the second loader must not overwrite the first loader's baseline");
		assertNull(MixinClassBaseline.baselineOf(new IsolatedLoader(), DOTTED),
			"an unrelated loader must not inherit anybody's baseline");
	}

	@Test
	void retransformNeverOverwritesTheDefineClassBaseline() {
		ClassLoader loader = new IsolatedLoader();
		observe(loader, INTERNAL, null, new byte[] {7});

		// classBeingRedefined != null: a redefine/retransform round, not a defineClass event.
		observe(loader, INTERNAL, MixinClassBaselineTest.class, new byte[] {9});

		assertArrayEquals(new byte[] {7}, MixinClassBaseline.baselineOf(loader, DOTTED),
			"a retransform delivers the current bytes, not the load-time baseline: it must not overwrite it");
	}

	@Test
	void bootstrapAndNonCandidateClassesAreNotRecorded() {
		observe(null, INTERNAL, null, new byte[] {1});
		assertNull(MixinClassBaseline.baselineOf(null, DOTTED),
			"bootstrap classes (loader == null) are JDK classes: recording them would only cost memory");

		ClassLoader loader = new IsolatedLoader();
		observe(loader, "fixture/Plain", null, new byte[] {2});
		assertNull(MixinClassBaseline.baselineOf(loader, "fixture.Plain"),
			"only synthetic/inner-class candidates ('$' in the name) are recorded, to keep the table bounded");
	}

	// ------------------------------------------------------------ gate wiring (C2)

	@Test
	void gateRefusesWithoutABaselineAndAcceptsWithIt() {
		ClassLoader loader = new IsolatedLoader();

		String reason = MixinInnerClassHotSwap.baselineMissingReason(loader, DOTTED);
		assertNotNull(reason, "without a premain baseline the synthetic redefine must be refused");
		assertTrue(reason.contains("restart"),
			"the refusal must tell the developer what to do (restart), not degrade silently: " + reason);

		observe(loader, INTERNAL, null, new byte[] {5});

		assertNull(MixinInnerClassHotSwap.baselineMissingReason(loader, DOTTED),
			"once the premain baseline was captured the gate must let the redefine through");
	}

	@Test
	void recorderKeyingIsTheOnlyLoaderScopedSource() {
		ClassLoader loader = new IsolatedLoader();

		observe(loader, "fixture/Target$Anonymous$other", null, new byte[] {1});
		assertNotNull(MixinInnerClassHotSwap.baselineMissingReason(loader, DOTTED),
			"a baseline recorded for a different class must not satisfy the gate (negative control)");

		observe(new IsolatedLoader(), INTERNAL, null, new byte[] {2});
		assertNotNull(MixinInnerClassHotSwap.baselineMissingReason(loader, DOTTED),
			"a same-named class defined by another loader must not satisfy the gate: the recorder's key is"
				+ " (loader identity, name), not the name alone");
	}

	/**
	 * Gate-4 source rule (invariant C2): the legacy dot-name cache is <b>not</b> a baseline source,
	 * not even when it holds bytes for exactly this name.
	 *
	 * <p>{@link remold.RemoldAgent#bytecodeCache} is keyed by dot name alone, so two loaders defining
	 * the same synthetic name collapse onto one entry. Serving that entry as a baseline launders one
	 * loader's bytes into another loader's class — the class then gets "redefined" against a shape the
	 * JVM never loaded. The premain recorder — keyed by (loader identity, name) — is the only source
	 * that can prove ownership, so a cache-only entry must keep the refusal (and its restart hint)
	 * instead of degrading into a silent pass.</p>
	 */
	@Test
	void legacyDotNameCacheIsNeverABaselineSource() {
		ClassLoader loader = new IsolatedLoader();
		byte[] previous = remold.RemoldAgent.bytecodeCache.put(DOTTED, new byte[] {3});
		try {
			String reason = MixinInnerClassHotSwap.baselineMissingReason(loader, DOTTED);
			assertNotNull(reason,
				"a dot-name cache entry must not satisfy gate 4: it proves nothing about which loader loaded it");
			assertTrue(reason.contains("restart"), "the refusal must still ask for a restart: " + reason);
		} finally {
			restoreLegacyEntry(previous);
		}
	}

	/**
	 * The cross-loader shape the JVM-level probe caught: Remold's own first-load path mirrors classes
	 * into the dot-name cache, so a coarse-key fallback would lend loader A's bytes to loader B. With
	 * the fallback gone this is refused for the same reason as a cache-only entry — and the recorder's
	 * own entry for loader A must not help loader B either (its key includes the loader).
	 */
	@Test
	void legacyEntryCannotLaunderAnotherLoadersClass() {
		ClassLoader loader = new IsolatedLoader();
		observe(new IsolatedLoader(), INTERNAL, null, new byte[] {4});
		byte[] previous = remold.RemoldAgent.bytecodeCache.put(DOTTED, new byte[] {3});
		try {
			String reason = MixinInnerClassHotSwap.baselineMissingReason(loader, DOTTED);
			assertNotNull(reason, "the legacy entry must not satisfy a loader that the recorder did not record");
			assertTrue(reason.contains("restart"), "the refusal must still ask for a restart: " + reason);
		} finally {
			restoreLegacyEntry(previous);
		}
	}

	/** Puts the dot-name cache back the way the case found it (the suite shares one static cache). */
	private static void restoreLegacyEntry(byte[] previous) {
		if (previous == null) {
			remold.RemoldAgent.bytecodeCache.remove(DOTTED);
		} else {
			remold.RemoldAgent.bytecodeCache.put(DOTTED, previous);
		}
	}

	// ------------------------------------------------------------ the three denominators

	/**
	 * The three denominators are counted on three different events, so they must not be derived from
	 * each other; this case names each one and pins its increment rule.
	 *
	 * <p>Concretely (and this is the part that is easy to report wrongly in a battle summary):</p>
	 * <ul>
	 *   <li>{@code seen} = every defineClass offer the recorder could key, <b>including</b> names the
	 *       candidate filter later drops (no {@code '$'}); bootstrap/hidden/agent-self offers are
	 *       excluded before the counter, because the table can never key them;</li>
	 *   <li>{@code recorded} = stored (loader, name) keys = candidate names only, so
	 *       {@code seen - recorded} is exactly "offers filtered out as non-candidates";</li>
	 *   <li>{@code missed} = baseline lookups that found nothing, so one refused gate call adds
	 *       exactly <b>one</b> — independent of how many classes were recorded.</li>
	 * </ul>
	 */
	@Test
	void denominatorsAreIndependentAndOneRefusedGateCallAddsExactlyOneMiss() {
		ClassLoader loader = new IsolatedLoader();
		observe(loader, "fixture/A$1", null, new byte[] {1});
		observe(loader, "fixture/A$2", null, new byte[] {2});
		observe(loader, "fixture/Plain", null, new byte[] {3});          // filtered: no '$' -> seen, not recorded
		observe(loader, "remold/Internal$1", null, new byte[] {4});      // excluded before seen (agent's own)
		observe(null, "fixture/B$1", null, new byte[] {5});              // excluded before seen (bootstrap)

		MixinClassBaseline.Metrics before = MixinClassBaseline.metrics();

		assertEquals(3, before.seen,
			"seen counts every keyable defineClass offer, filtered ones (no '$') included");
		assertEquals(2, before.recorded, "recorded counts stored (loader, name) keys: candidate names only");
		assertEquals(1, before.seen - before.recorded,
			"seen - recorded is the number of filtered offers, not a failure count and not a loader count");
		assertEquals(0, before.missed, "no gate lookup happened yet, so missed must still be 0");

		String reason = MixinInnerClassHotSwap.baselineMissingReason(loader, "fixture.A$Never");
		assertNotNull(reason, "the lookup below is a gate refusal (that is what missed counts)");
		assertTrue(reason.contains("missed=1"),
			"the refusal must carry the denominators it was decided with: " + reason);

		MixinClassBaseline.Metrics after = MixinClassBaseline.metrics();

		assertEquals(1, after.missed, "missed counts lookups that found nothing: exactly ONE per refused gate call");
		assertEquals(before.seen, after.seen, "a lookup is not a defineClass offer: seen must not move");
		assertEquals(before.recorded, after.recorded, "a lookup stores nothing: recorded must not move");
		assertTrue(MixinClassBaseline.metricsSummary().contains("missed=1"),
			"the summary logged into the battle report must carry all three denominators: "
				+ MixinClassBaseline.metricsSummary());
	}

	// ------------------------------------------------------------ naming the two denominators (C2)

	/**
	 * A bare {@code missed=1} is not evidence anyone can audit: the battle report has to name the
	 * class, the loader and the reason. The reason is mechanical — either another loader's baseline
	 * exists for that name, or no loader ever defined it.
	 *
	 * <p>Control: the second half repeats the lookup with a name nobody defined, which must flip the
	 * reason text. Without that control a hard-coded reason string could pass this case.</p>
	 */
	@Test
	void missedLookupNamesTheClassTheLoaderAndTheReason() {
		ClassLoader owner = new IsolatedLoader();
		ClassLoader stranger = new IsolatedLoader();
		observe(owner, INTERNAL, null, new byte[] {7});

		assertNull(MixinClassBaseline.baselineOf(stranger, DOTTED),
			"the stranger loader never defined this class, so the lookup must miss");

		assertEquals(1, MixinClassBaseline.missedCount(), "one refused lookup adds exactly one miss");
		String details = MixinClassBaseline.missDetailsSummary();
		assertTrue(details.contains(DOTTED), "the missed lookup must name the class in full: " + details);
		assertTrue(details.contains(MixinClassBaseline.loaderLabel(stranger)),
			"the missed lookup must name the loader identity: " + details);
		assertTrue(details.contains("recorded under 1 other loader"),
			"the reason must point at the other loader that owns the baseline: " + details);
		assertFalse(details.contains(MixinClassBaseline.loaderLabel(owner)),
			"the loader that DID record the class is not the miss and must not be blamed: " + details);

		// Control: same code path, a name no loader ever defined -> different reason.
		assertNull(MixinClassBaseline.baselineOf(stranger, "fixture.Target$Anonymous$nobodyDefined"));
		String second = MixinClassBaseline.missDetailsSummary();
		assertTrue(second.contains("no defineClass submission for this name on any loader"),
			"with no baseline anywhere the reason must change instead of reusing the previous one: " + second);
	}

	/**
	 * {@code seen - recorded} must be explainable by a rule, not by a failure story: the recorder only
	 * keys candidates (names containing {@code '$'}), and every filtered offer is counted with a real
	 * example name. The identity {@code seen == filtered + attempts} pins the split.
	 */
	@Test
	void filteredOffersAreCountedWithTheRuleAndExamples() {
		ClassLoader loader = new IsolatedLoader();
		observe(loader, "fixture/PlainOne", null, new byte[] {1});
		observe(loader, "fixture/PlainTwo", null, new byte[] {2});
		observe(loader, "fixture/Target$1", null, new byte[] {3});

		assertEquals(2, MixinClassBaseline.filteredCount(), "two offers carry no '$' and must be filtered");
		assertEquals(1, MixinClassBaseline.recordAttempts(),
			"one offer is a candidate and must be counted as an attempt");
		assertEquals(MixinClassBaseline.metrics().seen,
			MixinClassBaseline.filteredCount() + MixinClassBaseline.recordAttempts(),
			"seen must split exactly into filtered offers + candidate attempts");
		assertEquals(java.util.List.of("fixture/PlainOne", "fixture/PlainTwo"),
			MixinClassBaseline.filteredExamples(),
			"the filter must keep the first example names so the rule can be checked against real input");
		for (String example : MixinClassBaseline.filteredExamples()) {
			assertFalse(example.contains("$"), "a filtered example must really lack '$': " + example);
		}
		assertTrue(MixinClassBaseline.filterRule().contains("'$'"),
			"the rule must name the actual candidate test: " + MixinClassBaseline.filterRule());
		assertTrue(MixinClassBaseline.filterSummary().contains("filtered=2"),
			"the summary must report the filtered count: " + MixinClassBaseline.filterSummary());
	}

	/**
	 * Invariant C2: the post-redefine bytes must be keyed by <b>(loader identity, name)</b>, exactly like
	 * the premain capture. A dot-name-only table lets two loaders overwrite each other's baseline, and
	 * gate 4 would then reason about a shape this loader never loaded.
	 */
	@Test
	void appliedBytesAreScopedPerLoaderIdentity() {
		ClassLoader first = new IsolatedLoader();
		ClassLoader second = new IsolatedLoader();

		MixinClassBaseline.recordApplied(first, DOTTED, new byte[] {1});
		MixinClassBaseline.recordApplied(second, DOTTED, new byte[] {2});

		assertArrayEquals(new byte[] {1}, MixinClassBaseline.appliedOf(first, DOTTED),
			"each loader must keep its own post-redefine bytes for the same class name");
		assertArrayEquals(new byte[] {2}, MixinClassBaseline.appliedOf(second, DOTTED),
			"the second loader must not overwrite the first loader's applied bytes");
		assertNull(MixinClassBaseline.appliedOf(new IsolatedLoader(), DOTTED),
			"a loader that never redefined the class must not inherit anybody's applied bytes");

		// Gate-4 resolution goes through the same loader-scoped slot: own slot wins, no cross-loader leak.
		observe(first, INTERNAL, null, new byte[] {9});
		assertArrayEquals(new byte[] {1}, MixinInnerClassHotSwap.baselineBytes(first, DOTTED),
			"the applied slot (R1) must outrank the premain capture (R0) for the loader that owns it");
		assertNotNull(MixinInnerClassHotSwap.baselineMissingReason(new IsolatedLoader(), DOTTED),
			"a third loader still has no baseline, so gate 4 must refuse it instead of borrowing one");
	}
}
