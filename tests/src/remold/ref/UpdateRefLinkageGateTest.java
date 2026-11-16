package remold.ref;

import arc.Application;
import arc.Core;
import arc.func.Cons;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Linkage-gate contract of {@link UpdateRef}.
 * <p>
 * Scope of the assertions:
 * <ol>
 *   <li><b>Widening</b>: {@code BootstrapMethodError} counts as a hot-swap structural linkage error, so a fused
 *       {@code invokedynamic} bootstrap point (deleted / re-signed lambda target) is isolated instead of crashing the
 *       per-frame callback loop forever.</li>
 *   <li><b>Narrowing kept</b>: a cause chain containing {@link ExceptionInInitializerError} stays a business
 *       exception, and non-structural linkage errors such as {@link VerifyError} are still rethrown.</li>
 *   <li><b>Behaviour end state</b>: the classification is asserted on the observable end state of a wrapped
 *       callback (exception does not escape, ref is fused, deferred cleanup actually ran, original reference cleared),
 *       not on the exception type alone.</li>
 *   <li><b>eventsRun registry contract</b>: the entry registered by the {@code WrappedRef} branch of
 *       {@link UpdateRef#eventsRun} is removable by content, which is what the doc comment on that branch states.</li>
 * </ol>
 * Controls: {@link #verifyErrorStillRethrowsAndRefStaysLive()} reuses the exact wrapper shape of
 * {@link #bootstrapMethodErrorFusesWrappedRunnable()} but with an unrelated non-structural error, so the fusion in
 * case 1 cannot be attributed to "any throwable fuses". {@link #structuralErrorsKeptAsHotSwap()} pins the previously
 * accepted error set, so the widening cannot silently narrow an existing branch.
 */
class UpdateRefLinkageGateTest {

	private Application oldApp;

	@BeforeEach
	void setUp() {
		oldApp = Core.app;
		Core.app = null;
	}

	@AfterEach
	void tearDown() {
		Core.app = oldApp;
		UpdateRef.flushDeferredRemovals();
		UpdateRef.setEventsMap(null);
	}

	// region classification

	@Test
	void structuralErrorsKeptAsHotSwap() {
		assertTrue(UpdateRef.isHotSwapLinkageError(new NoSuchMethodError("gone")),
			"NoSuchMethodError must stay a hot-swap linkage error");
		assertTrue(UpdateRef.isHotSwapLinkageError(new NoSuchFieldError("gone")),
			"NoSuchFieldError must stay a hot-swap linkage error");
		assertTrue(UpdateRef.isHotSwapLinkageError(new IncompatibleClassChangeError("flip")),
			"IncompatibleClassChangeError must stay a hot-swap linkage error");
		assertTrue(UpdateRef.isHotSwapLinkageError(new NoClassDefFoundError("Missing")),
			"NoClassDefFoundError must stay a hot-swap linkage error");
		assertTrue(UpdateRef.isHotSwapLinkageError(new AbstractMethodError("gone")),
			"AbstractMethodError must stay a hot-swap linkage error");
	}

	@Test
	void bootstrapMethodErrorIsHotSwapLinkage() {
		// Failure form produced by LambdaMetafactory when the implMethod recorded in the bootstrap arguments
		// no longer resolves: the cause is a NoSuchMethodError.
		assertTrue(UpdateRef.isHotSwapLinkageError(new BootstrapMethodError(new NoSuchMethodError("lambda$target"))),
			"BootstrapMethodError wrapping NoSuchMethodError must be a hot-swap linkage error");
		assertTrue(UpdateRef.isHotSwapLinkageError(new BootstrapMethodError("bootstrap failed")),
			"BootstrapMethodError without cause must be a hot-swap linkage error");
		NoSuchMethodError buried = new NoSuchMethodError("lambda$target");
		buried.initCause(new IllegalAccessError("moved"));
		assertTrue(UpdateRef.isHotSwapLinkageError(new BootstrapMethodError(buried)),
			"BootstrapMethodError with a structural cause chain must be a hot-swap linkage error");
	}

	@Test
	void initializerErrorInCauseChainKeepsBusinessSemantics() {
		assertFalse(UpdateRef.isHotSwapLinkageError(
				new BootstrapMethodError(new ExceptionInInitializerError("clinit failed"))),
			"BootstrapMethodError caused by ExceptionInInitializerError is a business exception");
		assertFalse(UpdateRef.isHotSwapLinkageError(
				new BootstrapMethodError(new RuntimeException(new ExceptionInInitializerError("clinit failed")))),
			"ExceptionInInitializerError anywhere in the cause chain vetoes fusion");
		assertFalse(UpdateRef.isHotSwapLinkageError(new ExceptionInInitializerError("clinit failed")),
			"ExceptionInInitializerError itself is a business exception");
		NoClassDefFoundError initFailure = new NoClassDefFoundError("Could not initialize class X");
		initFailure.initCause(new ExceptionInInitializerError("clinit failed"));
		assertFalse(UpdateRef.isHotSwapLinkageError(initFailure),
			"NoClassDefFoundError caused by ExceptionInInitializerError stays a business exception");
	}

	@Test
	void nonStructuralErrorsAreNotHotSwap() {
		assertFalse(UpdateRef.isHotSwapLinkageError(new VerifyError("bad bytecode")),
			"VerifyError is not a structural-miss linkage error");
		assertFalse(UpdateRef.isHotSwapLinkageError(new RuntimeException("business")),
			"an arbitrary runtime exception is not a linkage error");
		assertFalse(UpdateRef.isHotSwapLinkageError(null), "null is not a linkage error");
	}

	// endregion

	// region wrapped-callback end state

	@Test
	void bootstrapMethodErrorFusesWrappedRunnable() {
		AtomicBoolean removed = new AtomicBoolean(false);
		Runnable wrapped = UpdateRef.wrap((Runnable) () -> {
			throw new BootstrapMethodError(new NoSuchMethodError("lambda$target"));
		}, () -> removed.set(true));
		assertTrue(wrapped instanceof UpdateRef.WrappedRef, "precondition: wrap must return a WrappedRef");
		UpdateRef ref = ((UpdateRef.WrappedRef) wrapped).getUpdateRef();
		// Baseline precondition: the ref starts live, so a later "fused" reading cannot be a pre-existing state.
		assertFalse(ref.isRemoved(), "precondition: a freshly wrapped ref is live");
		assertNotNull(ref.getOriginal(), "precondition: a freshly wrapped ref still holds the original");

		assertDoesNotThrow(wrapped::run, "BootstrapMethodError must not escape a wrapped callback");

		assertTrue(ref.isRemoved(), "the ref must be fused after a BootstrapMethodError");
		assertNull(ref.getOriginal(), "the original closure must be released to cut the stale reference");
		UpdateRef.flushDeferredRemovals();
		assertTrue(removed.get(), "the deferred cleanup action must actually run after fusion");
	}

	@Test
	void verifyErrorStillRethrowsAndRefStaysLive() {
		AtomicBoolean removed = new AtomicBoolean(false);
		Runnable wrapped = UpdateRef.wrap((Runnable) () -> {
			throw new VerifyError("bad bytecode");
		}, () -> removed.set(true));
		UpdateRef ref = ((UpdateRef.WrappedRef) wrapped).getUpdateRef();
		assertFalse(ref.isRemoved(), "precondition: a freshly wrapped ref is live");

		assertThrows(VerifyError.class, wrapped::run, "VerifyError must still propagate to the caller");

		assertFalse(ref.isRemoved(), "a non-structural linkage error must not fuse the ref");
		assertNotNull(ref.getOriginal(), "a non-structural linkage error must not release the original");
		UpdateRef.flushDeferredRemovals();
		assertFalse(removed.get(), "a non-structural linkage error must not run the cleanup action");
	}

	// endregion

	// region eventsRun registry contract

	@Test
	void eventsRunWrappedRefEntryCanBeRemovedByContent() {
		ObjectMap<Object, Seq<Cons<?>>> map = new ObjectMap<>();
		Object type = UpdateRefLinkageGateTest.class;
		Runnable original = () -> { };
		Runnable wrapped = UpdateRef.wrap(original);
		assertFalse(((UpdateRef.WrappedRef) wrapped).getUpdateRef().isRemoved(),
			"precondition: the wrapped listener is live before registration");

		UpdateRef.eventsRun(type, wrapped, map);

		Seq<Cons<?>> seq = map.get(type);
		assertNotNull(seq, "precondition: eventsRun must register an entry for the type");
		assertEquals(1, seq.size, "eventsRun must register exactly one entry");
		assertTrue(seq.items[0] instanceof UpdateRef.EventCons,
			"the WrappedRef branch must register an EventCons wrapper, not the listener itself");

		// Removal is attempted with a *different* wrapper instance sharing the same original, so a pass proves
		// content matching (unwrapTargetKey unwraps EventCons through WrappedRef) instead of identity matching.
		Runnable sameOriginalOtherWrapper = UpdateRef.wrap(original);
		assertNotSame(wrapped, sameOriginalOtherWrapper, "precondition: the probe wrapper is a distinct instance");

		assertTrue(UpdateRef.removeEventRun(type, sameOriginalOtherWrapper),
			"an eventsRun entry must be removable by content, as the branch comment states");
		Seq<Cons<?>> after = map.get(type);
		assertTrue(after == null || after.size == 0, "the registry entry must be gone after content-based removal");
	}

	// endregion
}
