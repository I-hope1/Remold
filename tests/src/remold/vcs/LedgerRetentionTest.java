package remold.vcs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两级保留（规格 {@code docs/vcs/07} L4/L5）：窗口裁剪只允许把结论推向保守，并且遗忘必须可观测。
 */
class LedgerRetentionTest {

	private ClassLoader loader;
	private GenerationalFieldLedger.LedgerKey key;
	private Changeset first;
	private Changeset second;

	@BeforeEach
	void setUp() {
		GenerationalFieldLedger.INSTANCE.reset();
		ChangesetLog.INSTANCE.reset();
		loader = new java.net.URLClassLoader(new java.net.URL[0], getClass().getClassLoader());
		key = new GenerationalFieldLedger.LedgerKey(1L, "a/B", "f");
		first = commit("r1");
		second = commit("r2");
		record(first, "1", "2");
		record(second, "2", "3");
	}

	@AfterEach
	void tearDown() {
		GenerationalFieldLedger.INSTANCE.reset();
		ChangesetLog.INSTANCE.reset();
	}

	private Changeset commit(String roundId) {
		return ChangesetLog.INSTANCE.commit(1L, roundId, Map.of(), Changeset.Manifest.EMPTY, List.of(),
		 Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.AFTER_COMMIT, Map.of(), Map.of());
	}

	private void record(Changeset cs, String oldLiteral, String newLiteral) {
		GenerationalFieldLedger.INSTANCE.record(loader, key,
		 new GenerationalFieldLedger.GenRef(cs.seq(), cs.id()),
		 new Changeset.ReplayFact("a.B", "f", "I", "L1", oldLiteral, newLiteral, "INT",
		  "analysis", "a/B", "OVERWRITE"),
		 Changeset.currentAlgoVersion());
	}

	private GenerationalFieldLedger.Resolved resolve(long targetSeq) {
		return GenerationalFieldLedger.INSTANCE.resolve(loader, ChangesetLog.INSTANCE, 1L, "a/B", "f",
		 targetSeq);
	}

	@Test
	void pruningCountsForgottenWritersAndIsObservable() {
		assertEquals(0, GenerationalFieldLedger.INSTANCE.forgottenWriters(loader, key));
		long beforeTotal = GenerationalFieldLedger.INSTANCE.forgottenWritersTotal();

		int dropped = GenerationalFieldLedger.INSTANCE.pruneToWindow(loader, second.seq());

		assertEquals(1, dropped, "only the generation below the window is pruned");
		assertEquals(1, GenerationalFieldLedger.INSTANCE.forgottenWriters(loader, key),
		 "a pruned writer must be counted, never silently forgotten");
		// 进程累计值按增量断言：其它用例也会推进它，绝对值断言会变成跨用例耦合。
		assertEquals(beforeTotal + 1L, GenerationalFieldLedger.INSTANCE.forgottenWritersTotal());
		assertEquals(beforeTotal + 1L,
		 GenerationalFieldLedger.INSTANCE.counters().get("forgottenWriters"),
		 "the counter must be visible on the diagnostics surface");
	}

	/** 裁剪是幂等的：同一窗口再裁一次不得重复计数（否则 forgottenWriters 会随对账次数膨胀）。 */
	@Test
	void pruneIsIdempotent() {
		assertEquals(1, GenerationalFieldLedger.INSTANCE.pruneToWindow(loader, second.seq()));
		assertEquals(0, GenerationalFieldLedger.INSTANCE.pruneToWindow(loader, second.seq()),
		 "re-running the same prune must not drop or count anything again");
		assertEquals(1, GenerationalFieldLedger.INSTANCE.forgottenWriters(loader, key),
		 "the forgotten count must stay at the number of actually pruned writers");
	}

	/** 全部被裁掉之后：resolve 说"没有事实"，但遗忘计数必须还看得见。 */
	@Test
	void fullyPrunedKeyStillReportsForgetting() {
		GenerationalFieldLedger.INSTANCE.pruneToWindow(loader, second.seq() + 10L);

		GenerationalFieldLedger.Resolved resolved = resolve(second.seq());

		assertEquals(GenerationalFieldLedger.Resolution.MISS_NO_FACT, resolved.resolution(),
		 "with every fact pruned the ledger has no answer, actual=" + resolved.resolution());
		assertEquals(2, GenerationalFieldLedger.INSTANCE.forgottenWriters(loader, key),
		 "forgetting is exactly what keeps this from looking like 'never had a fact'");
	}
}
