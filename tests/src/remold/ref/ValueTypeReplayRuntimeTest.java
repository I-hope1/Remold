package remold.ref;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Battle C runtime helpers of the value-type {@code new} replay channel
 * ({@code docs/initfix/14-value-type-new-replay.md} §3): the W1 volatile reference read / W4
 * reference CAS slot, the null-safe W2 precheck and the bounded W1-W4 retry kernel.
 *
 * <p>The retry path is exercised through the package-private read hook, so "the value changed
 * after W1" happens deterministically instead of relying on thread races.</p>
 */
class ValueTypeReplayRuntimeTest {

	/** SJ: no-op read hook for the success/skip cases. */
	private static final HotswapBridge.ReferenceReplayReadHook NO_HOOK = null;

	static class BaseFixture {
		Object inherited;
	}

	static final class ProbeFixture extends BaseFixture {
		Object ref;
		Object other;
		@SuppressWarnings("unused")
		int primitive;
	}

	static final class StaticFixture {
		static Object slot;
	}

	private static HotswapBridge.ReferenceFieldSlot instanceSlot(String field) {
		return HotswapBridge.resolveReferenceField(
		 ProbeFixture.class, field, "Ljava/lang/Object;", false);
	}

	private static HotswapBridge.ReferenceFieldSlot staticSlot() {
		return HotswapBridge.resolveReferenceField(
		 StaticFixture.class, "slot", "Ljava/lang/Object;", true);
	}

	// ==================== slot resolution ====================

	@Test
	void referenceSlotReadsInstanceAndStaticFields() throws Throwable {
		HotswapBridge.ReferenceFieldSlot instance = instanceSlot("ref");
		ProbeFixture probe = new ProbeFixture();
		Object value = new Object();
		probe.ref = value;

		assertFalse(instance.isStatic());
		assertEquals("remold.ref.ValueTypeReplayRuntimeTest$ProbeFixture.ref", instance.display());
		assertSame(value, instance.read(probe), "instance slot must read the live reference");
		assertNull(instanceSlot("other").read(probe), "an unset reference field reads as null");

		HotswapBridge.ReferenceFieldSlot stat = staticSlot();
		Object staticValue = new Object();
		StaticFixture.slot = staticValue;
		try {
			assertTrue(stat.isStatic());
			assertSame(staticValue, stat.read(null), "static slot ignores the target argument");
		} finally {
			StaticFixture.slot = null;
		}
	}

	@Test
	void referenceSlotReadsInheritedFields() throws Throwable {
		HotswapBridge.ReferenceFieldSlot inherited = instanceSlot("inherited");
		ProbeFixture probe = new ProbeFixture();
		Object value = new Object();
		probe.inherited = value;
		assertSame(value, inherited.read(probe));
	}

	@Test
	void referenceSlotFailsClosedForUnsupportedShapes() {
		// Non-reference descriptor: the channel only replaces object references.
		assertThrows(BootstrapMethodError.class,
		 () -> HotswapBridge.resolveReferenceField(
			ProbeFixture.class, "primitive", "I", false));
		// Static-ness declared by the task disagrees with the field.
		assertThrows(BootstrapMethodError.class,
		 () -> HotswapBridge.resolveReferenceField(
			ProbeFixture.class, "ref", "Ljava/lang/Object;", true));
		assertThrows(BootstrapMethodError.class,
		 () -> HotswapBridge.resolveReferenceField(
			StaticFixture.class, "slot", "Ljava/lang/Object;", false));
		// Missing field.
		assertThrows(BootstrapMethodError.class,
		 () -> HotswapBridge.resolveReferenceField(
			ProbeFixture.class, "missing", "Ljava/lang/Object;", false));
	}

	// ==================== W2 null-safe precheck ====================

	@Test
	void precheckIsNullSafe() {
		assertTrue(HotswapBridge.valueReplayPrecheck(null, null),
			"null vs null must compare equal without NPE");
		assertFalse(HotswapBridge.valueReplayPrecheck(null, new Object()),
			"a nulled field is a runtime change, not a match");
		assertFalse(HotswapBridge.valueReplayPrecheck(new Object(), null));
		Object same = new Object();
		assertTrue(HotswapBridge.valueReplayPrecheck(same, same));
		assertFalse(HotswapBridge.valueReplayPrecheck(new Object(), new Object()),
			"equality is Objects.equals, not identity");
	}

	@Test
	void nullFieldIsSkippedNotThrown() throws Throwable {
		HotswapBridge.ReferenceFieldSlot slot = instanceSlot("ref");
		ProbeFixture probe = new ProbeFixture();
		probe.ref = null;
		AtomicInteger built = new AtomicInteger();

		HotswapBridge.ReferenceReplayOutcome outcome = HotswapBridge.replayReferenceField(
		 slot, probe, new Object(), () -> { built.incrementAndGet(); return new Object(); },
		 3, NO_HOOK);

		assertEquals(HotswapBridge.ReferenceReplayOutcome.SKIPPED, outcome,
			"a nulled field must be skipped by the W2 precheck, not crash into failed");
		assertEquals(0, built.get(), "W3 must not construct a new value when W2 fails");
	}

	// ==================== W1-W4 success / skip / exhaustion ====================

	@Test
	void replayWritesWhenTheLiveValueEqualsTheRebuiltOldValue() throws Throwable {
		HotswapBridge.ReferenceFieldSlot slot = instanceSlot("ref");
		ProbeFixture probe = new ProbeFixture();
		Object rebuiltOld = new String("old");
		probe.ref = new String("old");
		Object current = probe.ref;

		HotswapBridge.ReferenceReplayOutcome outcome = HotswapBridge.replayReferenceField(
		 slot, probe, rebuiltOld, () -> new String("new"), 3, NO_HOOK);

		assertEquals(HotswapBridge.ReferenceReplayOutcome.WRITTEN, outcome);
		assertNotSame(current, probe.ref, "the reference must be replaced by the freshly built value");
		assertEquals("new", probe.ref);
	}

	@Test
	void replaySkipsWhenTheLiveValueWasModifiedAtRuntime() throws Throwable {
		HotswapBridge.ReferenceFieldSlot slot = instanceSlot("ref");
		ProbeFixture probe = new ProbeFixture();
		Object modified = new String("runtime");
		probe.ref = modified;
		AtomicInteger built = new AtomicInteger();

		HotswapBridge.ReferenceReplayOutcome outcome = HotswapBridge.replayReferenceField(
		 slot, probe, new String("old"),
		 () -> { built.incrementAndGet(); return new String("new"); }, 3, NO_HOOK);

		assertEquals(HotswapBridge.ReferenceReplayOutcome.SKIPPED, outcome);
		assertSame(modified, probe.ref, "the runtime value must be preserved (no write, not even a retry)");
		assertEquals(0, built.get());
	}

	@Test
	void staticSlotReplayReplacesTheSingleStaticSlot() throws Throwable {
		HotswapBridge.ReferenceFieldSlot slot = staticSlot();
		StaticFixture.slot = new String("old");
		try {
			HotswapBridge.ReferenceReplayOutcome outcome = HotswapBridge.replayReferenceField(
			 slot, null, new String("old"), () -> new String("new"), 3, NO_HOOK);
			assertEquals(HotswapBridge.ReferenceReplayOutcome.WRITTEN, outcome);
			assertEquals("new", StaticFixture.slot);
		} finally {
			StaticFixture.slot = null;
		}
	}

	// ==================== W4 bounded retry: restart from W1 ====================

	/**
	 * The deterministic "read then concurrently modified" case: after W1 the hook replaces the
	 * slot with a value that no longer equals the rebuilt old value. The retry must restart at W1
	 * and re-run W2, so the outcome is SKIPPED and the concurrently written value stays untouched.
	 * An implementation that retried the CAS on the stale expected value would report EXHAUSTED;
	 * one that re-read but skipped W2 would overwrite the concurrent value (WRITTEN).
	 */
	@Test
	void casRetryRestartsFromReadAndPrecheck() throws Throwable {
		HotswapBridge.ReferenceFieldSlot slot = instanceSlot("ref");
		ProbeFixture probe = new ProbeFixture();
		Object rebuiltOld = new String("old");
		Object initial = new String("old");
		Object concurrent = new String("concurrent");
		probe.ref = initial;

		List<Integer> attempts = new ArrayList<>();
		AtomicInteger built = new AtomicInteger();
		HotswapBridge.ReferenceReplayOutcome outcome = HotswapBridge.replayReferenceField(
		 slot, probe, rebuiltOld,
		 () -> { built.incrementAndGet(); return new String("new"); }, 3,
		 (current, attempt) -> {
			 attempts.add(attempt);
			 if (attempt == 1) probe.ref = concurrent;   // simulate a concurrent write right after W1
		 });

		assertEquals(HotswapBridge.ReferenceReplayOutcome.SKIPPED, outcome,
			"the retry must restart from W1 and W2 must observe the concurrent value");
		assertEquals(List.of(1, 2), attempts, "exactly one restart is enough to observe the change");
		assertSame(concurrent, probe.ref,
			"the retry must not overwrite the concurrently written value");
		assertEquals(1, built.get(), "the already built new value is reused across the retry");
	}

	/**
	 * Every attempt loses the CAS: the hook rewrites the slot with an equal-but-distinct reference,
	 * so W2 keeps passing while W4 keeps failing. The kernel must stop after the bound and report
	 * EXHAUSTED, still constructing the new value only once.
	 */
	@Test
	void casExhaustionIsBoundedAndReusesTheBuiltValue() throws Throwable {
		HotswapBridge.ReferenceFieldSlot slot = instanceSlot("ref");
		ProbeFixture probe = new ProbeFixture();
		Object rebuiltOld = new String("old");
		probe.ref = new String("old");

		AtomicInteger built = new AtomicInteger();
		AtomicInteger reads = new AtomicInteger();
		HotswapBridge.ReferenceReplayOutcome outcome = HotswapBridge.replayReferenceField(
		 slot, probe, rebuiltOld,
		 () -> { built.incrementAndGet(); return new String("new"); }, 3,
		 (current, attempt) -> {
			 reads.incrementAndGet();
			 // Equal to rebuiltOld but a different reference: W2 passes, CAS(expected=current) fails.
			 probe.ref = new String("old");
		 });

		assertEquals(HotswapBridge.ReferenceReplayOutcome.EXHAUSTED, outcome);
		assertEquals(3, reads.get(), "the retry must be bounded by the configured attempt count");
		assertEquals(1, built.get(), "W3 must run once per instance even when CAS keeps failing");
	}

	// ==================== W3 identity semantics ====================

	/**
	 * W3 is per instance: two instances with the same old value must each receive a distinct new
	 * object (the driver relies on this for the "no shared reference identity" requirement).
	 */
	@Test
	void eachInstanceReceivesItsOwnNewObject() throws Throwable {
		HotswapBridge.ReferenceFieldSlot slot = instanceSlot("ref");
		Object rebuiltOld = new String("old");
		ProbeFixture first = new ProbeFixture();
		ProbeFixture second = new ProbeFixture();
		first.ref = new String("old");
		second.ref = new String("old");

		HotswapBridge.replayReferenceField(
		 slot, first, rebuiltOld, () -> new String("new"), 3, NO_HOOK);
		HotswapBridge.replayReferenceField(
		 slot, second, rebuiltOld, () -> new String("new"), 3, NO_HOOK);

		assertEquals("new", first.ref);
		assertEquals("new", second.ref);
		assertNotSame(first.ref, second.ref,
			"W3 must construct one new value per instance, never share one object");
	}
}
