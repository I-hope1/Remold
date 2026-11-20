package remold.vcs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Revert planning and forward-revert commits
 * ({@code docs/vcs/01-bytecode-changeset-vcs.md} §2.1.1 interval revert constraints).
 *
 * <p><b>What is guarded</b>: the target byte set for a revert is the union of classes changed in
 * {@code (target, HEAD]}, each resolved to its latest Eff at {@code seq <= target} or to its
 * {@code Eff_0} baseline; a missing byte refuses the whole revert; and the never-roll-back rule
 * appends a new generation instead of rewriting history.</p>
 */
class ChangesetRevertTest {

	private static final long SCOPE = 11L;

	@AfterEach
	void cleanUp() {
		System.clearProperty("remold.vcs.eff_bytes");
		System.clearProperty("remold.vcs.changeset_window");
		ChangesetLog.INSTANCE.reset();
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static Map<String, Changeset.Entry> entry(String className, byte[] raw, byte[] eff) {
		Map<String, Changeset.Entry> map = new LinkedHashMap<>();
		// effHash must be the real content hash: the revert planner resolves bytes by it.
		map.put(className, new Changeset.Entry(className, ChangesetLog.effSha256(raw),
		 ChangesetLog.effSha256(eff), Changeset.metaDigest(className, List.of(), Map.of(), Map.of(), 0)));
		return map;
	}

	private static Changeset commit(ChangesetLog log, String round, String className, byte[] eff) {
		return commit(log, round, className, null, eff);
	}

	private static Changeset commit(ChangesetLog log, String round, String className,
	                                byte[] effBefore, byte[] eff) {
		Map<String, byte[]> before = new LinkedHashMap<>();
		if (effBefore != null) before.put(className, effBefore);
		Map<String, byte[]> now = new LinkedHashMap<>();
		now.put(className, eff);
		return log.commit(SCOPE, round, entry(className, eff, eff), Changeset.Manifest.EMPTY,
		 List.of(), Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.AFTER_COMMIT,
		 before, now);
	}

	/** D1: a class modified twice reverts to the byte set of the earlier generation. */
	@Test
	void revertUsesTheLatestEffAtOrBeforeTheTarget() {
		ChangesetLog log = ChangesetLog.INSTANCE;
		byte[] one = bytes("v1");
		byte[] two = bytes("v2");
		commit(log, "r1", "a/A", one);
		commit(log, "r2", "a/A", two);

		ChangesetLog.RevertPlan plan = log.revertPlan(SCOPE, 1L);

		assertEquals(1L, plan.targetSeq());
		assertEquals(2L, plan.headSeq());
		assertEquals(1, plan.targets().size());
		assertArrayEquals(one, plan.targets().get("a/A").bytes(),
		 "reverting to seq 1 must restore the seq 1 bytes, not the current head bytes");
	}

	/**
	 * D1/D2: a class whose first modification sits inside the reverted range reverts to its Eff_0
	 * baseline. Without the baseline slot the revert would have no bytes for that class at all.
	 */
	@Test
	void revertFallsBackToTheEff0BaselineForFirstTimeClasses() {
		ChangesetLog log = ChangesetLog.INSTANCE;
		byte[] baseline = bytes("pre-hot-reload");
		byte[] firstEdit = bytes("first-edit");

		commit(log, "r1", "a/A", bytes("a1"));
		// B first enters the chain at seq 2: the bytes in effect before that round are its Eff_0.
		commit(log, "r2", "a/B", baseline, firstEdit);

		ChangesetLog.RevertPlan toOne = log.revertPlan(SCOPE, 1L);
		assertArrayEquals(baseline, toOne.targets().get("a/B").bytes(),
		 "B was first modified inside the reverted range, so its target is the Eff_0 baseline");
		assertEquals(1, toOne.targets().size(),
		 "A was last changed at the target generation itself, so it needs no revert");

		Changeset second = log.bySeq(SCOPE, 2L).orElseThrow();
		assertTrue(second.eff0().containsKey("a/B"), "the baseline is recorded on the generation that introduced it");
	}

	/**
	 * D2/D3: when the retained Eff blob is gone the revert is refused as a whole. A partial revert
	 * would leave half the batch on the new generation, which is worse than refusing.
	 */
	@Test
	void missingEffBytesRefuseTheWholeRevert() {
		System.setProperty("remold.vcs.eff_bytes", "0");
		ChangesetLog log = ChangesetLog.INSTANCE;
		commit(log, "r1", "a/A", bytes("v1"));
		commit(log, "r2", "a/A", bytes("v2"));

		ChangesetLog.NotRevertableException refused = assertThrows(ChangesetLog.NotRevertableException.class,
		 () -> log.revertPlan(SCOPE, 1L));
		assertTrue(refused.getMessage().contains("NOT_REVERTABLE"));
		assertEquals(Changeset.Availability.REPLAYABLE, refused.level(),
		 "the generation itself is still addressable/replayable; only the bytes are gone");
	}

	/** D3: a target seq that is not in the window is refused outright. */
	@Test
	void targetOutsideTheWindowIsRefused() {
		ChangesetLog log = ChangesetLog.INSTANCE;
		commit(log, "r1", "a/A", bytes("v1"));
		commit(log, "r2", "a/A", bytes("v2"));

		ChangesetLog.NotRevertableException refused = assertThrows(ChangesetLog.NotRevertableException.class,
		 () -> log.revertPlan(SCOPE, 99L));
		assertEquals(Changeset.Availability.ADDRESSABLE, refused.level(),
		 "an unknown generation is addressable at best");
	}

	/**
	 * D5: the revert lands as a NEW generation. Rewriting seq would create a chain that no longer
	 * describes what happened; appending keeps the export linear and the parent link intact.
	 */
	@Test
	void forwardRevertAppendsANewGenerationInsteadOfRewinding() {
		ChangesetLog log = ChangesetLog.INSTANCE;
		byte[] one = bytes("v1");
		commit(log, "r1", "a/A", one);
		commit(log, "r2", "a/A", bytes("v2"));
		Changeset before = log.head(SCOPE).orElseThrow();

		ChangesetLog.RevertPlan plan = log.revertPlan(SCOPE, 1L);
		Changeset forward = log.appendForwardRevert(SCOPE, plan, "revert", Changeset.currentAlgoVersion(),
		 "", Changeset.PatchTiming.AFTER_COMMIT);

		assertEquals(before.seq() + 1, forward.seq(), "the revert is the next generation, not a rewind");
		assertEquals(before.id(), forward.parentId());
		assertEquals(1L, forward.revertedToSeq());
		assertEquals(3, log.size(SCOPE), "history keeps every generation it had");

		ChangesetLog.RevertPlan back = log.revertPlan(SCOPE, 3L);
		assertTrue(back.isEmpty(), "reverting to the newest generation is a no-op");
	}

	/** D8: identical inputs produce identical plans, so a plan can be compared and logged. */
	@Test
	void plansAreDeterministic() {
		ChangesetLog log = ChangesetLog.INSTANCE;
		commit(log, "r1", "a/A", bytes("v1"));
		commit(log, "r2", "a/B", bytes("pre-b"), bytes("x1"));
		commit(log, "r3", "a/A", bytes("v2"));

		ChangesetLog.RevertPlan first = log.revertPlan(SCOPE, 1L);
		ChangesetLog.RevertPlan second = log.revertPlan(SCOPE, 1L);

		assertEquals(first.targets().keySet(), second.targets().keySet());
		for (String name : first.targets().keySet()) {
			assertArrayEquals(first.targets().get(name).bytes(), second.targets().get(name).bytes());
			assertEquals(first.targets().get(name).effHash(), second.targets().get(name).effHash());
		}
	}

	/** D1: the plan covers every class changed inside the reverted range, not just the head's. */
	@Test
	void planCoversTheWholeRevertedRange() {
		ChangesetLog log = ChangesetLog.INSTANCE;
		commit(log, "r1", "a/A", bytes("a1"));
		commit(log, "r2", "a/A", bytes("a2"));
		commit(log, "r3", "a/B", bytes("pre-b"), bytes("b1"));

		ChangesetLog.RevertPlan plan = log.revertPlan(SCOPE, 1L);

		assertEquals(2, plan.targets().size(), "A changed at seq 2 and B first appeared at seq 3");
		assertTrue(plan.targets().containsKey("a/A"));
		assertTrue(plan.targets().containsKey("a/B"));
		assertNotEquals(plan.targets().get("a/A").effHash(), plan.targets().get("a/B").effHash());
	}
}
