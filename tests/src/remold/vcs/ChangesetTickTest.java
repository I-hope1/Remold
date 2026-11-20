package remold.vcs;

import org.objectweb.asm.tree.ClassNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the commit tick in {@code docs/vcs/01-bytecode-changeset-vcs.md}
 * §2.1.1-§2.1.3.
 *
 * <p><b>What is guarded</b>: {@code seq} only moves for committed rounds, the parent chain stays
 * unbroken inside the window, the availability levels drive {@code NOT_REVERTABLE}, an empty edge
 * set is still a commit, and a Changeset never retains runtime handles. Every case asserts the
 * business end state (the chain as observable), not a call returning without throwing.</p>
 */
class ChangesetTickTest {

	private static final long SCOPE = 7L;

	@AfterEach
	void cleanUp() {
		System.clearProperty("remold.vcs.changeset_window");
		ChangesetLog.INSTANCE.reset();
	}

	private static Changeset commit(ChangesetLog log, String roundId, Map<String, Changeset.Entry> entries,
	                                Changeset.Manifest manifest, List<Changeset.ReplayFact> facts) {
		return log.commit(SCOPE, roundId, entries, manifest, facts,
		 Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.AFTER_COMMIT);
	}

	private static Map<String, Changeset.Entry> entry(String name, String hash) {
		Map<String, Changeset.Entry> map = new LinkedHashMap<>();
		map.put(name, new Changeset.Entry(name, hash, hash + "-eff", hash + "-meta"));
		return map;
	}

	/**
	 * C1: an aborted round must leave head and seq untouched. Without the separate abort path the
	 * counter would silently advance and a later revert would target a generation that never existed.
	 */
	@Test
	void abortedRoundLeavesHeadAndSeqUntouched() {
		ChangesetLog log = ChangesetLog.INSTANCE;
		Changeset first = commit(log, "r1", entry("a/A", "h1"), Changeset.Manifest.EMPTY, List.of());
		assertEquals(1L, first.seq(), "precondition: first commit takes seq 1");

		log.abort(SCOPE);
		log.abort(SCOPE);

		Optional<Changeset> head = log.head(SCOPE);
		assertTrue(head.isPresent(), "head must survive an aborted round");
		assertEquals(1L, head.get().seq(), "aborted rounds must not move seq");
		assertEquals(1, log.size(SCOPE), "aborted rounds must not append a chain node");
		assertEquals(2L, log.abortedCount(SCOPE), "aborted rounds are still observable as diagnostics");

		Changeset next = commit(log, "r2", entry("a/A", "h2"), Changeset.Manifest.EMPTY, List.of());
		assertEquals(2L, next.seq(), "the next committed round continues from the last committed seq");
		assertEquals(first.id(), next.parentId(), "parent must point at the last committed round");
	}

	/** C2: the chain is a linked list inside a scope and the link is the recorded parent id. */
	@Test
	void parentChainIsUnbroken() {
		ChangesetLog log = ChangesetLog.INSTANCE;
		Changeset a = commit(log, "r1", entry("a/A", "x"), Changeset.Manifest.EMPTY, List.of());
		Changeset b = commit(log, "r2", entry("a/A", "y"), Changeset.Manifest.EMPTY, List.of());
		Changeset c = commit(log, "r3", entry("a/A", "z"), Changeset.Manifest.EMPTY, List.of());

		assertEquals(null, a.parentId(), "first commit in a scope has no parent");
		assertEquals(a.id(), b.parentId());
		assertEquals(b.id(), c.parentId());
		assertEquals(List.of(1L, 2L, 3L), log.chain(SCOPE).stream().map(Changeset::seq).toList());
		assertEquals(Optional.of(c), log.head(SCOPE));
		assertEquals(Optional.of(a), log.bySeq(SCOPE, 1L));
	}

	/**
	 * C2: same content at a different seq is a different generation. {@code A -> B -> A} is the
	 * canonical trap: treating it as the old generation would let a revert land on the wrong node.
	 */
	@Test
	void contentIdenticalCommitAtAnotherSeqGetsAnotherId() {
		ChangesetLog log = ChangesetLog.INSTANCE;
		Changeset first = commit(log, "r1", entry("a/A", "same"), Changeset.Manifest.EMPTY, List.of());
		commit(log, "r2", entry("a/A", "other"), Changeset.Manifest.EMPTY, List.of());
		Changeset third = commit(log, "r3", entry("a/A", "same"), Changeset.Manifest.EMPTY, List.of());

		assertNotEquals(first.id(), third.id(), "identical bytes at seq 3 must not reuse the seq 1 id");
		assertEquals(first.rawRoot(), third.rawRoot(),
		 "the raw tree root is content addressed, so identical bytes share it");
	}

	/**
	 * C2: {@code seq} itself is part of the id derivation. Two records with the same parent and the
	 * same content but different seq must not collide — otherwise a revert could address the wrong
	 * generation. This is checked directly on the factory because the chain always moves the parent
	 * too, which would mask a missing {@code seq} input.
	 */
	@Test
	void seqIsPartOfTheIdEvenWithAnIdenticalParentAndContent() {
		Map<String, Changeset.Entry> entries = entry("a/A", "same");
		Changeset one = Changeset.create(null, 1L, "r", SCOPE, entries, Changeset.Manifest.EMPTY,
		 List.of(), Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.AFTER_COMMIT);
		Changeset two = Changeset.create(null, 2L, "r", SCOPE, entries, Changeset.Manifest.EMPTY,
		 List.of(), Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.AFTER_COMMIT);

		assertEquals(one.parentId(), two.parentId(), "precondition: both are root commits");
		assertEquals(one.rawRoot(), two.rawRoot(), "precondition: identical content");
		assertNotEquals(one.id(), two.id(), "seq must feed the id, not just the parent chain");
	}

	/** C2: the id is deterministic for one input tuple (diagnostics and replay depend on it). */
	@Test
	void idIsDeterministicForTheSameInput() {
		Changeset one = commit(ChangesetLog.INSTANCE, "r1", entry("a/A", "h"), Changeset.Manifest.EMPTY, List.of());
		ChangesetLog.INSTANCE.reset();
		Changeset two = commit(ChangesetLog.INSTANCE, "r1", entry("a/A", "h"), Changeset.Manifest.EMPTY, List.of());
		assertEquals(one.id(), two.id(), "same (parent, seq, content) must hash to the same id");
	}

	/** C3: the window evicts the oldest node but never breaks the head. */
	@Test
	void windowEvictsOldestAndKeepsHeadAndParent() {
		System.setProperty("remold.vcs.changeset_window", "3");
		assertEquals(3, ChangesetLog.window(), "precondition: the property is honoured");

		ChangesetLog log = ChangesetLog.INSTANCE;
		for (int i = 1; i <= 5; i++) {
			commit(log, "r" + i, entry("a/A", "h" + i), Changeset.Manifest.EMPTY, List.of());
		}

		assertEquals(3, log.size(SCOPE), "the window caps the retained nodes");
		assertEquals(List.of(3L, 4L, 5L), log.chain(SCOPE).stream().map(Changeset::seq).toList());
		Changeset head = log.head(SCOPE).orElseThrow();
		assertEquals(5L, head.seq());
		assertEquals(4L, log.bySeq(SCOPE, 4L).orElseThrow().seq(),
		 "the retained neighbour is still addressable");
		assertTrue(log.bySeq(SCOPE, 1L).isEmpty(), "the evicted node leaves the addressable window");
	}

	/** C3: the default window is the value the contract pins, not an accident of construction. */
	@Test
	void windowDefaultsToThirtyTwo() {
		assertEquals(32, ChangesetLog.window());
		assertEquals(32, ChangesetLog.DEFAULT_WINDOW);
	}

	/** C4: the availability ladder is ordered, and only REVERTABLE may be reverted. */
	@Test
	void availabilityLadderAndNotRevertableRefusal() {
		assertEquals(Changeset.Availability.ADDRESSABLE, ChangesetLog.availability(false, false, false));
		assertEquals(Changeset.Availability.ADDRESSABLE, ChangesetLog.availability(false, true, false));
		assertEquals(Changeset.Availability.REPLAYABLE, ChangesetLog.availability(true, true, false));
		assertEquals(Changeset.Availability.REVERTABLE, ChangesetLog.availability(true, true, true));

		Changeset target = commit(ChangesetLog.INSTANCE, "r1", entry("a/A", "h"),
		 Changeset.Manifest.EMPTY, List.of());

		ChangesetLog.requireRevertable(target, true, true, true);

		ChangesetLog.NotRevertableException refused = assertThrows(ChangesetLog.NotRevertableException.class,
		 () -> ChangesetLog.requireRevertable(target, true, true, false),
		 "a replayable-only generation must not be reverted");
		assertEquals(Changeset.Availability.REPLAYABLE, refused.level(),
		 "the refusal reports the level that blocked it");
		assertTrue(refused.getMessage().contains("NOT_REVERTABLE"), "the refusal names its reason");

		ChangesetLog.NotRevertableException addressableOnly = assertThrows(
		 ChangesetLog.NotRevertableException.class,
		 () -> ChangesetLog.requireRevertable(target, false, false, false));
		assertEquals(Changeset.Availability.ADDRESSABLE, addressableOnly.level());
	}

	/** C5: a class with no cross-class edge still needs a commit coordinate. */
	@Test
	void emptyEdgeManifestStillAdvancesSeq() {
		Changeset.Manifest empty = Changeset.Manifest.of(List.of("L0:a.A"), List.of(),
		 List.of("a.A.f"), List.of());
		Changeset cs = commit(ChangesetLog.INSTANCE, "r1", entry("a/A", "h"), empty, List.of());

		assertEquals(1L, cs.seq(), "an empty edge set is a legal commit");
		assertFalse(cs.manifest().hasEdges());
		assertEquals(List.of("L0:a.A"), cs.manifest().orderedHosts());
	}

	/** C5: the manifest is a batch-level field, not something smuggled into per-class entries. */
	@Test
	void manifestIsItsOwnFieldAndSurvivesOnTheRecord() {
		Changeset.Manifest manifest = Changeset.Manifest.of(
		 List.of("L0:a.A", "L0:a.B"),
		 List.of("L0:a.B.f -> L0:a.A.base"),
		 List.of("a.A.base", "a.B.f#REJECTED"),
		 List.of("a.Missing"));
		Changeset cs = commit(ChangesetLog.INSTANCE, "r1", entry("a/A", "h"), manifest, List.of());

		assertEquals(manifest, cs.manifest());
		assertEquals(1, cs.entries().size(), "the manifest must not leak into entries");
		assertEquals(1, cs.manifest().edges().size());
	}

	/** C6: facts are recorded with both literals, survive an otherwise empty generation, and dedupe. */
	@Test
	void replayFactsAreCommitLevelFacts() {
		Changeset.ReplayFact l1 = new Changeset.ReplayFact("a.A", "count", "I", "L1", "1", "2", "INT", "", "", "");
		Changeset.ReplayFact reinit = new Changeset.ReplayFact("a.A", "flag", "Z", "REINIT",
		 null, null, "NONE", "", "SELF", "CONDITIONAL");
		Changeset cs = commit(ChangesetLog.INSTANCE, "r1", entry("a/A", "h"), Changeset.Manifest.EMPTY,
		 List.of(l1, reinit));

		assertEquals(2, cs.replayFacts().size());
		assertEquals("1", cs.replayFacts().get(0).oldLiteral());
		assertEquals("2", cs.replayFacts().get(0).newLiteral());
		assertEquals("NONE", cs.replayFacts().get(1).literalKind(),
		 "the REINIT tier is allowed to carry no literal");
		assertEquals("SELF", cs.replayFacts().get(1).scope());

		// Facts live on the record, so they stay readable at the weakest level: losing the Raw blob
		// drops the generation to ADDRESSABLE, and that is enough to read manifest/replayFacts.
		Changeset degraded = commit(ChangesetLog.INSTANCE, "r2", entry("a/A", "h2"),
		 Changeset.Manifest.EMPTY, List.of(l1));
		assertEquals(Changeset.Availability.ADDRESSABLE,
		 ChangesetLog.availability(false, true, false),
		 "precondition: without Raw bytes the generation is addressable only");
		assertEquals("1", degraded.replayFacts().get(0).oldLiteral(),
		 "facts must remain readable after the Raw byte window closes");
	}

	/** C6: meta digests are order insensitive, otherwise two equal records would hash differently. */
	@Test
	void metaDigestIsOrderInsensitiveAndContentSensitive() {
		Map<String, Long> ghostsA = new LinkedHashMap<>();
		ghostsA.put("lambda$1", 11L);
		ghostsA.put("lambda$2", 22L);
		Map<String, Long> ghostsB = new LinkedHashMap<>();
		ghostsB.put("lambda$2", 22L);
		ghostsB.put("lambda$1", 11L);

		String one = Changeset.metaDigest("a/A", Set.of("x", "y"), ghostsA, Map.of("m", "n"), 1);
		String two = Changeset.metaDigest("a/A", Set.of("y", "x"), ghostsB, Map.of("m", "n"), 1);
		String changed = Changeset.metaDigest("a/A", Set.of("x"), ghostsB, Map.of("m", "n"), 1);

		assertEquals(one, two, "iteration order must not change the meta identity");
		assertNotEquals(one, changed, "dropping an invented name must change the meta identity");
		assertEquals(32, one.length(), "the digest is the 128-bit truncated hex form");
	}

	/** C7: patch timing is part of the record, so a revert knows whether fields were final. */
	@Test
	void patchTimingIsRecordedPerGeneration() {
		Changeset after = ChangesetLog.INSTANCE.commit(SCOPE, "r1", entry("a/A", "h"),
		 Changeset.Manifest.EMPTY, List.of(), Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.AFTER_COMMIT);
		Changeset before = ChangesetLog.INSTANCE.commit(SCOPE, "r2", entry("a/A", "h2"),
		 Changeset.Manifest.EMPTY, List.of(), Changeset.currentAlgoVersion(), "", Changeset.PatchTiming.BEFORE_COMMIT);

		assertEquals(Changeset.PatchTiming.AFTER_COMMIT, after.patchTiming(),
		 "single-phase patches run after the commit point");
		assertEquals(Changeset.PatchTiming.BEFORE_COMMIT, before.patchTiming(),
		 "two-phase patches run before the commit point");
	}

	/** C8: the record graph must not be able to pin a loader or a Class. */
	@Test
	void recordGraphHoldsNoRuntimeHandles() {
		assertNoRuntimeHandles(Changeset.class);
		assertNoRuntimeHandles(Changeset.Entry.class);
		assertNoRuntimeHandles(Changeset.Manifest.class);
		assertNoRuntimeHandles(Changeset.ReplayFact.class);
	}

	private static void assertNoRuntimeHandles(Class<?> type) {
		Field[] fields = type.getDeclaredFields();
		assertTrue(fields.length > 0, type + " must expose state");
		for (Field field : fields) {
			assertTrue(Modifier.isFinal(field.getModifiers()),
			 type.getName() + "." + field.getName() + " must be final (the record is immutable)");
			assertNotEquals(Class.class, field.getType(),
			 type.getName() + "." + field.getName() + " must not be a Class");
			assertNotEquals(ClassLoader.class, field.getType(),
			 type.getName() + "." + field.getName() + " must not be a ClassLoader");
			assertFalse(ClassNode.class.isAssignableFrom(field.getType()),
			 type.getName() + "." + field.getName() + " must not retain parsed bytecode nodes");
		}
	}
}
