package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import remold.vcs.Changeset;
import remold.vcs.ChangesetLog;
import remold.vcs.GenerationalFieldLedger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR #41 评审抓到的两处遗漏：{@code reset()} 漏清遗忘计数、{@code forgottenWriters(null, ...)}
 * 漏走 bootstrap 槽。
 */
class LedgerResetAndBootstrapSlotTest {

	private static final GenerationalFieldLedger.LedgerKey KEY =
	 new GenerationalFieldLedger.LedgerKey(0L, "a/B", "f");

	@AfterEach
	void cleanUp() {
		GenerationalFieldLedger.INSTANCE.reset();
		ChangesetLog.INSTANCE.reset();
	}

	private Changeset commit(String roundId) {
		return ChangesetLog.INSTANCE.commit(0L, roundId, Map.of(), Changeset.Manifest.EMPTY, List.of(),
		 Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.AFTER_COMMIT, Map.of(), Map.of());
	}

	private void recordBootstrap(Changeset cs, String oldLiteral, String newLiteral) {
		GenerationalFieldLedger.INSTANCE.record(null, KEY,
		 new GenerationalFieldLedger.GenRef(cs.seq(), cs.id()),
		 new Changeset.ReplayFact("a.B", "f", "I", "L1", oldLiteral, newLiteral, "INT",
		  "analysis", "a/B", "OVERWRITE"),
		 Changeset.currentAlgoVersion());
	}

	/** reset() 必须把遗忘计数与 bootstrap 槽的遗忘表一起清掉，否则用例之间会互相污染。 */
	@Test
	void resetClearsForgottenCounters() {
		Changeset cs = commit("r1");
		recordBootstrap(cs, "1", "2");
		GenerationalFieldLedger.INSTANCE.pruneToWindow(null, cs.seq() + 10L);
		assertTrue(GenerationalFieldLedger.INSTANCE.forgottenWritersTotal() > 0L);

		GenerationalFieldLedger.INSTANCE.reset();

		assertEquals(0L, GenerationalFieldLedger.INSTANCE.forgottenWritersTotal(),
		 "reset must clear the process-wide forgotten counter");
		assertEquals(0, GenerationalFieldLedger.INSTANCE.forgottenWriters(null, KEY),
		 "reset must clear the bootstrap slot's own forgotten table");
	}

	/** loader == null 的类被裁剪后，查询必须看得见（与 pruneToWindow 的 slotFor 同口径）。 */
	@Test
	void bootstrapSlotForgettingIsVisible() {
		Changeset cs = commit("r2");
		recordBootstrap(cs, "1", "2");
		assertEquals(0, GenerationalFieldLedger.INSTANCE.forgottenWriters(null, KEY));

		GenerationalFieldLedger.INSTANCE.pruneToWindow(null, cs.seq() + 10L);

		assertEquals(1, GenerationalFieldLedger.INSTANCE.forgottenWriters(null, KEY),
		 "bootstrap-slot classes must report their pruned writers too, not always 0");
	}
}
