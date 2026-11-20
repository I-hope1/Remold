package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import remold.ref.InitFix;
import remold.vcs.Changeset;
import remold.vcs.ChangesetLog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单类批次上下文（`DynamicVcsCommit` 的 manifest/facts 来源）。
 *
 * <p>锁两件事：①上下文开着的时候，单类 manifest 能反映该类与它的候选字段口径；
 * ②上下文收起之后必须彻底回到"无批次"，不能把脏上下文漏给下一轮文件热更批次。</p>
 */
class DynamicBatchContextTest {

	private ClassLoader loader;

	@BeforeEach
	void setUp() {
		loader = new java.net.URLClassLoader(new java.net.URL[0], getClass().getClassLoader());
	}

	@AfterEach
	void tearDown() {
		InitFix.endBatchBytecodeView();
		ChangesetLog.INSTANCE.reset();
	}

	private void openSingleClassView(Class<?> cls) {
		Map<ClassLoader, Map<String, byte[]>> view = new IdentityHashMap<>();
		Map<String, byte[]> bytes = new LinkedHashMap<>();
		bytes.put(cls.getName(), new byte[] {1, 2, 3});
		view.put(cls.getClassLoader(), bytes);
		InitFix.beginBatchBytecodeView(view);
	}

	@Test
	void singleClassManifestIsNotEmptyWhileTheContextIsOpen() {
		openSingleClassView(DynamicBatchContextTest.class);

		Changeset.Manifest manifest = InitFix.batchCommitManifest(List.of(DynamicBatchContextTest.class));
		assertTrue(manifest.orderedHosts().size() == 1,
		 "a single-class batch must record exactly one host, actual=" + manifest.orderedHosts());
		assertTrue(manifest.orderedHosts().get(0).contains(DynamicBatchContextTest.class.getName()));
	}

	@Test
	void closingTheContextRestoresTheNoBatchAnswer() {
		openSingleClassView(DynamicBatchContextTest.class);
		InitFix.endBatchBytecodeView();

		Changeset.Manifest manifest = InitFix.batchCommitManifest(List.of(DynamicBatchContextTest.class));
		assertEquals(List.of(), manifest.orderedHosts(),
		 "after the context closes, the manifest must fall back to the empty no-batch answer");
		assertEquals(List.of(), InitFix.batchReplayFacts());
	}

	/** 没开上下文时开一次、关一次也必须安全（动态路径可能在任何分支提前返回）。 */
	@Test
	void closeWithoutOpenIsHarmless() {
		InitFix.endBatchBytecodeView();
		assertEquals(List.of(), InitFix.batchCommitManifest(List.of()).orderedHosts());
	}
}
