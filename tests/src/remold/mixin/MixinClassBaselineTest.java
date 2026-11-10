package remold.mixin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
	void unrelatedLoaderOrDotNameCacheEntryDoesNotSatisfyTheGate() {
		ClassLoader loader = new IsolatedLoader();

		observe(loader, "fixture/Target$Anonymous$other", null, new byte[] {1});
		assertNotNull(MixinInnerClassHotSwap.baselineMissingReason(loader, DOTTED),
			"a baseline recorded for a different class must not satisfy the gate (negative control)");

		observe(new IsolatedLoader(), INTERNAL, null, new byte[] {2});
		assertNotNull(MixinInnerClassHotSwap.baselineMissingReason(loader, DOTTED),
			"a same-named class defined by another loader must not satisfy the gate");

		byte[] previous = remold.RemoldAgent.bytecodeCache.put(DOTTED, new byte[] {3});
		try {
			assertNotNull(MixinInnerClassHotSwap.baselineMissingReason(loader, DOTTED),
				"the dot-name bytecode cache is not loader-scoped: it must not silently satisfy the (loader, name) gate");
		} finally {
			if (previous == null) {
				remold.RemoldAgent.bytecodeCache.remove(DOTTED);
			} else {
				remold.RemoldAgent.bytecodeCache.put(DOTTED, previous);
			}
		}
	}

	// ------------------------------------------------------------ the three denominators

	@Test
	void metricsCarryTheThreeDenominators() {
		ClassLoader loader = new IsolatedLoader();
		observe(loader, "fixture/A$1", null, new byte[] {1});
		observe(loader, "fixture/A$2", null, new byte[] {2});
		observe(loader, "fixture/Plain", null, new byte[] {3});   // filtered candidate
		assertNull(MixinClassBaseline.baselineOf(loader, "fixture.A$Never"), "miss lookup");

		MixinClassBaseline.Metrics metrics = MixinClassBaseline.metrics();

		assertEquals(3, metrics.seen, "seen: defineClass offers the recorder observed");
		assertEquals(2, metrics.recorded, "recorded: (loader, name) keys actually stored");
		assertEquals(1, metrics.missed, "missed: baseline lookups that found nothing (gate refusals)");
		assertTrue(MixinClassBaseline.metricsSummary().contains("missed=1"),
			"the summary logged into the battle report must carry all three denominators: "
				+ MixinClassBaseline.metricsSummary());
	}
}
