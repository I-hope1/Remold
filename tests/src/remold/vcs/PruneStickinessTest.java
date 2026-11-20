package remold.vcs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 裁剪之后的"粘性保守"：旧记录消失不得让结论重新变激进。
 *
 * <p>两条读数都在这里钉住：①被裁掉的键解出 {@code MISS_NO_FACT}，判决拒并点名降级为
 * {@code OPAQUE_GUARDED}；②即使之后又记进一条新事实，连续性也无法再证明，解出
 * {@code REFUSED_DISCONTINUOUS}，判决仍然拒——保守是**构造性**的，不依赖某个额外的锁死标志。</p>
 */
class PruneStickinessTest {

	@AfterEach
	void tearDown() {
		GenerationalFieldLedger.INSTANCE.reset();
		ChangesetLog.INSTANCE.reset();
	}

	@Test
	void prunedFieldStaysConservativeEvenAfterANewFact() {
		ClassLoader loader = new java.net.URLClassLoader(new java.net.URL[0], getClass().getClassLoader());
		GenerationalFieldLedger.LedgerKey key = new GenerationalFieldLedger.LedgerKey(1L, "a/B", "f");

		Changeset first = commit("r1");
		Changeset second = commit("r2");
		record(loader, key, first, "1", "2");
		record(loader, key, second, "2", "3");

		GenerationalFieldLedger.INSTANCE.pruneToWindow(loader, second.seq() + 10L);
		assertEquals(2, GenerationalFieldLedger.INSTANCE.forgottenWriters(loader, key));

		GenerationalFieldLedger.Resolved pruned = GenerationalFieldLedger.INSTANCE.resolve(
		 loader, ChangesetLog.INSTANCE, 1L, "a/B", "f", second.seq());
		assertEquals(GenerationalFieldLedger.Resolution.MISS_NO_FACT, pruned.resolution());

		GenerationalReplayGate.Decision refused =
		 GenerationalReplayGate.decide(pruned, "I", "INT", true);
		assertTrue(refused.refused(), "a pruned key must not be replayed");
		assertTrue(refused.reason().contains("OPAQUE_GUARDED"),
		 "the refusal must name the downgrade target, actual=" + refused.reason());

		// 再记一条新事实：连续性已经不可能证明，结论必须仍然是拒。
		Changeset third = commit("r3");
		record(loader, key, third, "3", "4");
		GenerationalFieldLedger.Resolved again = GenerationalFieldLedger.INSTANCE.resolve(
		 loader, ChangesetLog.INSTANCE, 1L, "a/B", "f", third.seq());
		assertEquals(GenerationalFieldLedger.Resolution.REFUSED_DISCONTINUOUS, again.resolution(),
		 "after pruning, a fresh fact cannot re-establish continuity");
		assertTrue(GenerationalReplayGate.decide(again, "I", "INT", true).refused(),
		 "conservatism must be structural, not a flag that a new fact can flip");
	}

	private Changeset commit(String roundId) {
		return ChangesetLog.INSTANCE.commit(1L, roundId, Map.of(), Changeset.Manifest.EMPTY, List.of(),
		 Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.AFTER_COMMIT, Map.of(), Map.of());
	}

	private void record(ClassLoader loader, GenerationalFieldLedger.LedgerKey key, Changeset cs,
	                    String oldLiteral, String newLiteral) {
		GenerationalFieldLedger.INSTANCE.record(loader, key,
		 new GenerationalFieldLedger.GenRef(cs.seq(), cs.id()),
		 new Changeset.ReplayFact("a.B", "f", "I", "L1", oldLiteral, newLiteral, "INT",
		  "analysis", "a/B", "OVERWRITE"),
		 Changeset.currentAlgoVersion());
	}
}
