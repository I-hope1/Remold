package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import remold.vcs.AlignmentMetaStore;
import remold.vcs.Changeset;
import remold.vcs.ChangesetLog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态重定义的提交刻度（选 B：自成一代 + {@code dynamic:} 标记）。
 *
 * <p>三条状态转移各自独立断言：成功 → {@code seq + 1}；中止 → {@code seq} 不动、只加计数；
 * no-op 走的是 {@code redefineDynamic} 的 true 分支，不经过本类（由调用方结构保证，见
 * {@code DynamicRedefineExecutor.redefineDynamic} 的包装层）。</p>
 */
class DynamicVcsCommitTest {

	private ClassLoader loader;

	@BeforeEach
	void setUp() {
		ChangesetLog.INSTANCE.reset();
		AlignmentMetaStore.INSTANCE.reset();
		loader = new java.net.URLClassLoader(new java.net.URL[0], getClass().getClassLoader());
	}

	@AfterEach
	void cleanUp() {
		ChangesetLog.INSTANCE.reset();
		AlignmentMetaStore.INSTANCE.reset();
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void successCommitsOneRoundWithDynamicMarker() {
		Changeset cs = DynamicVcsCommit.commit(DynamicVcsCommitTest.class, bytes("OLD"), bytes("SRC"), bytes("NEW"), false);

		assertNotNull(cs, "a successful redefine must produce a round");
		assertEquals(1L, cs.seq(), "the first dynamic round takes seq 1");
		assertTrue(cs.roundId().startsWith(DynamicVcsCommit.ROUND_PREFIX),
		 "dynamic rounds must be distinguishable from batch rounds, actual=" + cs.roundId());
		assertEquals(Changeset.PatchTiming.AFTER_COMMIT, cs.patchTiming());
		assertEquals(1, cs.entries().size());
		assertTrue(cs.entries().containsKey("remold/DynamicVcsCommitTest"),
		 "entries are keyed by internal name, actual=" + cs.entries().keySet());
	}

	@Test
	void secondRoundAdvancesSeqAndKeepsMarker() {
		DynamicVcsCommit.commit(DynamicVcsCommitTest.class, bytes("OLD1"), bytes("SRC1"), bytes("NEW1"), false);
		Changeset second = DynamicVcsCommit.commit(DynamicVcsCommitTest.class, bytes("NEW1"), bytes("SRC2"), bytes("NEW2"), true);

		assertNotNull(second);
		assertEquals(2L, second.seq(), "each successful dynamic redefine advances seq by exactly one");
		assertEquals(Changeset.PatchTiming.BEFORE_COMMIT, second.patchTiming(),
		 "a two-phase dynamic redefine must be recorded as BEFORE_COMMIT");
		assertEquals(0L, second.revertedToSeq(), "sanity: a normal round is not a revert");
	}

	/** 中止不推进 {@code seq}，只累加诊断计数。 */
	@Test
	void abortDoesNotAdvanceSeq() {
		long before = currentSeq();
		DynamicVcsCommit.abort(loader);
		assertEquals(before, currentSeq(), "an aborted dynamic redefine must not move the sequence");

		Changeset cs = DynamicVcsCommit.commit(DynamicVcsCommitTest.class, bytes("OLD"), bytes("SRC"), bytes("NEW"), false);
		assertNotNull(cs);
		assertEquals(before + 1L, cs.seq(), "the next successful round still takes the next seq");
	}

	/** 空入参不产生代：调用方的 no-op/非法路径不该留下鬼代。 */
	@Test
	void missingInputsDoNotCommit() {
		assertNull(DynamicVcsCommit.commit(null, bytes("OLD"), bytes("SRC"), bytes("NEW"), false));
		assertNull(DynamicVcsCommit.commit(DynamicVcsCommitTest.class, bytes("OLD"), bytes("SRC"), null, false));
		assertEquals(0L, currentSeq());
	}

	/** 提交后必须能在链上读回，且 4 个内容入口都在（动态代不是"只记计数"的空壳）。 */
	@Test
	void committedRoundIsReadableFromTheChain() {
		DynamicVcsCommit.commit(DynamicVcsCommitTest.class, bytes("OLD"), bytes("SRC"), bytes("NEW"), false);

		long scope = AlignmentMetaStore.INSTANCE.loaderScopeId(DynamicVcsCommitTest.class.getClassLoader());
		List<Changeset> chain = ChangesetLog.INSTANCE.chain(scope);
		assertEquals(1, chain.size());
		Changeset head = chain.get(0);
		assertNotNull(head.manifest(), "a dynamic round must still carry a manifest object");
		assertTrue(head.entries().containsKey("remold/DynamicVcsCommitTest"));
	}

	private static long currentSeq() {
		// 只读探针：遍历各 scope 的链取最后一代序号（提交失败/无链时为 0）。
		return headSeq();
	}

	private static long headSeq() {
		for (long scope = 0; scope < 4096; scope++) {
			List<Changeset> chain = ChangesetLog.INSTANCE.chain(scope);
			if (!chain.isEmpty()) return chain.get(chain.size() - 1).seq();
		}
		return 0L;
	}
}
