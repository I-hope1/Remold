package remold.vcs;

import org.junit.jupiter.api.Test;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.ref.WeakReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F8: the generational ledger must not keep a {@code ClassLoader} (and therefore not the
 * {@code Class} objects defined by it) alive past the window or past a scope unload
 * ({@code docs/topology/20-generational-field-ledger.md} §2.4).
 *
 * <p><b>What is guarded</b>: {@link GenerationalFieldLedger} keys its scope slots by a
 * {@link java.util.WeakHashMap} on the loader and stores only scalars in the values. Both halves
 * matter: a strong key would pin the loader forever, and a value holding the loader/class would
 * defeat the weak key from the other side.</p>
 *
 * <p><b>Terminal assertion, not a proxy</b>: the test holds only a {@link WeakReference} to the
 * loader and asserts that the reference is cleared <i>and</i> that the ledger's scope count drops,
 * after the ledger has verifiably held state for that loader beforehand. A control loader stays
 * strongly reachable across the same collection and its slot must survive: without that control,
 * "count dropped" could equally come from a ledger that never records anything.</p>
 */
class GenerationalFieldLedgerRetentionTest {

	private static final class ScopeLoader extends ClassLoader {
		ScopeLoader() {
			super(null);
		}

		Class<?> define(String name, byte[] bytes) {
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	private static Changeset.ReplayFact intFact() {
		return new Changeset.ReplayFact("probe/Holder", "f", "I", "L1", "1", "2", "INT", "", "", "");
	}

	private static GenerationalFieldLedger.LedgerKey key(long scope) {
		return new GenerationalFieldLedger.LedgerKey(scope, "probe/Holder", "f");
	}

	private static GenerationalFieldLedger.GenRef gen(long seq) {
		return new GenerationalFieldLedger.GenRef(seq, "cs-" + seq);
	}

	/** Collect until the reference clears; bounded so a stubborn JVM makes the test fail, not hang. */
	private static boolean collected(WeakReference<?> ref) {
		for (int i = 0; i < 40 && ref.get() != null; i++) {
			System.gc();
			try {
				Thread.sleep(20L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return ref.get() == null;
	}

	/**
	 * The scope slot must not pin its loader: once the last strong reference is gone, the slot with
	 * its history rows and {@code REINIT_APPLIED} rows must be unreachable.
	 */
	@Test
	void unloadedScopeReleasesItsSlotAndItsAppliedRows() {
		GenerationalFieldLedger ledger = GenerationalFieldLedger.INSTANCE;
		ledger.reset();
		// Control: this loader stays strongly reachable for the whole test.
		ClassLoader control = new ScopeLoader();

		ScopeLoader doomed = new ScopeLoader();
		ledger.record(doomed, key(7L), gen(1L), intFact(), Changeset.currentAlgoVersion());
		ledger.recordReinitApplied(doomed, "probe/Holder", "f", "k", "APPLIED", gen(1L));
		ledger.record(control, key(8L), gen(1L), intFact(), Changeset.currentAlgoVersion());

		// Precondition: the ledger really does hold state for both scopes (otherwise the post-GC
		// assertion below would be vacuously true).
		assertEquals(1, ledger.facts(doomed, "probe/Holder", "f").size(),
		 "the doomed scope's fact is indexed before the unload");
		assertEquals(1, ledger.reinitApplied(doomed, "probe/Holder", "f").size(),
		 "the doomed scope's REINIT_APPLIED row is indexed before the unload");
		assertEquals(2, ledger.liveLoaderCount(), "two scopes are tracked before the unload");

		WeakReference<ClassLoader> weak = new WeakReference<>(doomed);
		doomed = null;
		assertTrue(collected(weak), "the ledger must not keep the unloaded ClassLoader alive");

		// Terminal reading: the unloaded scope is gone, the still-reachable control scope is not.
		assertEquals(1, ledger.liveLoaderCount(),
		 "only the still-reachable scope keeps a slot after the collection");
		assertEquals(1, ledger.facts(control, "probe/Holder", "f").size(),
		 "the control scope's row survives - the drop above is reachability, not a no-op ledger");
		ledger.reset();
	}

	/**
	 * The {@code Class} dimension: rows are scalars, so dropping every loader reference must also let
	 * the classes defined by that loader be collected.
	 */
	@Test
	void scopeSlotReleasesClassesDefinedByTheLoader() throws Exception {
		GenerationalFieldLedger ledger = GenerationalFieldLedger.INSTANCE;
		ledger.reset();
		WeakReference<Class<?>> weakClass = defineAndRecord(ledger);
		assertTrue(collected(weakClass),
		 "the ledger must not keep a Class defined by an unloaded scope alive");
		assertEquals(0, ledger.liveLoaderCount(),
		 "the unloaded scope leaves no slot behind (actual=" + ledger.liveLoaderCount() + ")");
		ledger.reset();
	}

	/** Defines one throwaway class, records a fact for it and returns only a weak handle to it. */
	private static WeakReference<Class<?>> defineAndRecord(GenerationalFieldLedger ledger) throws Exception {
		byte[] bytes = fixtureBytes("probe/RetentionFixture");
		ScopeLoader loader = new ScopeLoader();
		Class<?> defined = loader.define("probe.RetentionFixture", bytes);
		ledger.record(loader, new GenerationalFieldLedger.LedgerKey(
		 AlignmentMetaStore.INSTANCE.loaderScopeId(loader), "probe/RetentionFixture", "f"),
		 gen(1L), intFact(), Changeset.currentAlgoVersion());
		assertFalse(ledger.facts(loader, "probe/RetentionFixture", "f").isEmpty(),
		 "the fixture fact is indexed before the loader is dropped");
		return new WeakReference<>(defined);
	}

	/** Minimal standalone classfile: a public class with a no-arg constructor and nothing else. */
	private static byte[] fixtureBytes(String internalName) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Window pruning drops history rows but must not touch the debt set (that is not history). */
	@Test
	void windowPruningKeepsDebtAndDropsHistory() {
		GenerationalFieldLedger ledger = GenerationalFieldLedger.INSTANCE;
		ledger.reset();
		ClassLoader loader = new ScopeLoader();
		ledger.record(loader, key(9L), gen(1L), intFact(), Changeset.currentAlgoVersion());
		ledger.record(loader, key(9L), gen(2L), intFact(), Changeset.currentAlgoVersion());
		ledger.markUnclosed(loader, "probe/Holder", "g");
		assertEquals(2, ledger.facts(loader, "probe/Holder", "f").size(), "two generations are indexed");

		int dropped = ledger.pruneToWindow(loader, 3L);
		assertEquals(2, dropped, "both rows are outside the window");
		assertEquals(List.of("probe/Holder\u0001g"), ledger.unclosed(loader),
		 "the unclosed debt survives pruning (it is not history)");
		assertTrue(ledger.latest(loader, "probe/Holder", "f", 9L).isEmpty(),
		 "no fact is resolvable after the window dropped it");
		ledger.reset();
	}
}
