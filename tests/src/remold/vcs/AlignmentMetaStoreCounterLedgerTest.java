package remold.vcs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Double-ledger gate for the incremental counters in {@link AlignmentMetaStore}.
 *
 * <p><b>What is guarded</b>: {@code blobBytes()} and the live-history reading are now maintained as
 * incrementally updated per-slot counters instead of a full scan of every {@code head}/{@code history}
 * entry. The optimisation is only allowed to change the cost, never the value, so every mutation point
 * (insert / per-class eviction / global trim / budget blob drop / reset) is followed by
 * {@link AlignmentMetaStore#assertCountersConsistent()}, which re-derives both numbers by scanning and
 * throws on any drift.</p>
 *
 * <p><b>Attribution</b>: if a counter-update line is deleted (for example the decrement on per-class
 * eviction, or the byte release on a budget drop), these cases fail; a test that only compared
 * {@code blobBytes()} against itself could not tell the difference between "counter correct" and
 * "counter never updated". The recount side is the independent truth here.</p>
 */
public class AlignmentMetaStoreCounterLedgerTest {

	private static final int ENTRY_BYTES = 256;

	@AfterEach
	void tearDown() {
		AlignmentMetaStore.INSTANCE.reset();
	}

	private static byte[] blob(int marker) {
		byte[] b = new byte[ENTRY_BYTES];
		for (int i = 0; i < b.length; i++) b[i] = (byte) (i * 31 + marker);
		return b;
	}

	private static AlignmentMetaStore.Entry commit(AlignmentMetaStore store, ClassLoader loader,
	                                               String className, byte[] blob) {
		return store.commit(loader, className, blob, blob, blob,
			Set.of("lambda$run$0"), Map.of("lambda$run$5", 7L),
			Map.of("a()V", "b()V"), 1, Map.of("lambda$run$0:()V", "lambda$run$0:()V"));
	}

	@Test
	void countersMatchTheRecountAfterEveryMutationKind() {
		AlignmentMetaStore store = AlignmentMetaStore.INSTANCE;
		store.reset();
		ClassLoader loader = new ClassLoader(AlignmentMetaStoreCounterLedgerTest.class.getClassLoader()) { };
		String name = "ledger/Case";

		// 1) insert: n entries, n blobs.
		for (int i = 0; i < 5; i++) {
			commit(store, loader, name, blob(i));
			store.assertCountersConsistent();
			assertEquals((i + 1), store.historyCount(), "history count after insert " + i);
			assertEquals(1, store.headCount(), "one class name holds exactly one head entry");
			assertEquals((long) ENTRY_BYTES * (i + 1), store.blobBytes(),
				"blob bytes after insert " + i);
		}

		// 2) overwrite beyond HISTORY_PER_CLASS: the oldest generation is evicted, and its blob
		//    (if it still owned one) must leave the ledger too.
		int generations = AlignmentMetaStore.HISTORY_PER_CLASS + 4;
		for (int i = 0; i < generations; i++) {
			commit(store, loader, name, blob(100 + i));
			store.assertCountersConsistent();
		}
		assertEquals(AlignmentMetaStore.HISTORY_PER_CLASS, store.historyCount(),
			"history is capped at HISTORY_PER_CLASS");
		assertEquals((long) ENTRY_BYTES * AlignmentMetaStore.HISTORY_PER_CLASS, store.blobBytes(),
			"evicted generations must release their blob bytes from the ledger");
	}

	@Test
	void evictionOfAnEntryWithoutBlobKeepsTheLedgerBalanced() {
		AlignmentMetaStore store = AlignmentMetaStore.INSTANCE;
		store.reset();
		ClassLoader loader = new ClassLoader() { };
		String name = "ledger/NoBlob";

		// Empty pre-align bytes => retainBlob() returns null, so this generation owns no blob.
		for (int i = 0; i < AlignmentMetaStore.HISTORY_PER_CLASS + 2; i++) {
			commit(store, loader, name, new byte[0]);
			store.assertCountersConsistent();
		}
		assertEquals(0L, store.blobBytes(), "no blob was ever retained, so the byte ledger stays at zero");
		assertEquals(AlignmentMetaStore.HISTORY_PER_CLASS, store.historyCount(), "entries still occupy history");
	}

	@Test
	void globalTrimKeepsBothLedgersBalanced() {
		AlignmentMetaStore store = AlignmentMetaStore.INSTANCE;
		store.reset();
		ClassLoader loader = new ClassLoader() { };
		int total = AlignmentMetaStore.HISTORY_MAX_ENTRIES + 40;

		for (int i = 0; i < total; i++) {
			commit(store, loader, "trim/C" + i, blob(i % 251));
			if ((i & 0xFF) == 0) store.assertCountersConsistent();
		}
		store.assertCountersConsistent();
		assertTrue(store.historyCount() <= AlignmentMetaStore.HISTORY_MAX_ENTRIES,
			"global trim must keep history at or below HISTORY_MAX_ENTRIES, got " + store.historyCount());
		assertEquals(store.recountBlobBytes(), store.blobBytes(), "byte ledger equals recount after trim");
		assertEquals(store.recountLiveHistoryEntries(), store.historyCount(),
			"history ledger equals recount after trim");
	}

	/**
	 * Budget pressure path: the reclaimer drops the oldest blob and must credit the ledger with the
	 * released bytes. Only this case can catch a missing credit on the "drop blob, keep hash" branch —
	 * the per-class eviction cases never run it.
	 */
	@Test
	void budgetDropCreditsTheReleasedBytes() {
		long budget = AlignmentMetaStore.BLOB_BUDGET_BYTES;
		assertTrue(budget > 0 && budget <= Integer.MAX_VALUE,
			"precondition: this case needs a positive, sane blob budget, got " + budget);
		int bigEntry = (int) Math.min(128 * 1024, AlignmentMetaStore.PER_CLASS_BLOB_BYTES / 2);
		assertTrue(bigEntry > 1024, "precondition: the fixture blob must be small enough for the per-class cap");
		int entries = (int) (budget / bigEntry) + 4;

		AlignmentMetaStore store = AlignmentMetaStore.INSTANCE;
		store.reset();
		ClassLoader loader = new ClassLoader() { };
		byte[] big = new byte[bigEntry];

		for (int i = 0; i < entries; i++) {
			big[0] = (byte) i;
			commit(store, loader, "budget/Big" + i, big);
			store.assertCountersConsistent();
		}
		assertTrue(store.blobBytes() <= budget,
			"held bytes must stay within the budget, got " + store.blobBytes() + "B > " + budget + "B");
		assertEquals(store.recountBlobBytes(), store.blobBytes(),
			"the reclaimer must credit every released byte back to the ledger");
		assertTrue(store.droppedBlobCount() == 0,
			"the reclaim path frees the oldest blob instead of refusing the new one, so no drop is counted");
	}

	@Test
	void resetDropsEveryCounter() {		AlignmentMetaStore store = AlignmentMetaStore.INSTANCE;
		ClassLoader loader = new ClassLoader() { };
		store.reset();
		commit(store, loader, "reset/C1", blob(1));
		commit(store, loader, "reset/C2", blob(2));
		assertTrue(store.blobBytes() > 0, "precondition: blobs are held before reset");

		store.reset();
		store.assertCountersConsistent();
		assertEquals(0L, store.blobBytes(), "reset must zero the byte ledger");
		assertEquals(0, store.historyCount(), "reset must zero the history ledger");
		assertEquals(0, store.headCount(), "reset must zero the head ledger");
	}

	/**
	 * The check has to be able to fail: a corrupted counter must be reported, not smoothed over.
	 *
	 * <p>This is the negative control for the whole gate — without it a green
	 * {@code assertCountersConsistent()} could simply mean "the comparison never looks at anything".</p>
	 *
	 * <p>Corruption path is the real failure mode, not a synthetic field poke: an entry's blob is
	 * dropped (same package call as the budget reclaimer does) <b>without</b> the ledger being credited.
	 * The recount notices the missing {@value #ENTRY_BYTES} bytes and the check must throw with the delta.</p>
	 */
	@Test
	void theCheckRejectsAnInconsistentLedger() {
		AlignmentMetaStore store = AlignmentMetaStore.INSTANCE;
		store.reset();
		ClassLoader loader = new ClassLoader() { };
		commit(store, loader, "neg/C1", blob(1));
		store.assertCountersConsistent();

		AlignmentMetaStore.Entry head = store.head(loader, "neg/C1");
		assertTrue(head != null, "precondition: the committed entry must be readable");
		assertEquals(ENTRY_BYTES, head.dropBlob(), "precondition: the entry really owned a blob");

		IllegalStateException ex = assertThrows(IllegalStateException.class,
			store::assertCountersConsistent,
			"a blob release that skipped the ledger must be reported, not silently tolerated");
		assertTrue(ex.getMessage().contains("blobBytes counter")
				&& ex.getMessage().contains("delta=" + ENTRY_BYTES),
			"the drift report must name the counter and the byte delta, got: " + ex.getMessage());
	}
}
