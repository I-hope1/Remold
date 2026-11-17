package remold;

import org.junit.jupiter.api.Test;
import remold.vcs.VcsCommitGate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR1 batch-atomic commit gate truth table.
 *
 * <p>Anchor: relaxing {@link VcsCommitGate#shouldCommit} to "commit whenever anything applied" must turn
 * this class red, because a half-applied batch must never be recorded as a generation.</p>
 */
public class VcsCommitGateTest {

	@Test
	void commitsOnlyWhenTheWholeBatchApplied() {
		assertTrue(VcsCommitGate.shouldCommit(3, 3, 0, false), "fully applied batch must commit");
		assertTrue(VcsCommitGate.shouldCommit(0, 0, 0, false), "empty batch is a no-op success");
	}

	@Test
	void refusesPartialBatch() {
		assertFalse(VcsCommitGate.shouldCommit(3, 2, 0, false), "2/3 applied must not commit");
		assertFalse(VcsCommitGate.shouldCommit(1, 0, 0, false), "0/1 applied must not commit");
	}

	@Test
	void refusesRolledBackBatch() {
		assertFalse(VcsCommitGate.shouldCommit(3, 3, 1, false),
			"a rolled-back transaction group must invalidate the whole batch");
	}

	@Test
	void refusesLambdaStrictRejectedBatch() {
		assertFalse(VcsCommitGate.shouldCommit(2, 2, 0, true),
			"a LAMBDA_STRICT rejection must invalidate the whole batch");
	}

	@Test
	void refusesInvalidCounts() {
		assertFalse(VcsCommitGate.shouldCommit(-1, 0, 0, false));
		assertFalse(VcsCommitGate.shouldCommit(2, -1, 0, false));
		assertFalse(VcsCommitGate.shouldCommit(2, 2, -1, false));
	}
}
