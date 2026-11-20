package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import remold.vcs.LoadFrontier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2：空 {@code offsetIndex} 不等于"没有偏移持有者"——新类出生可能带来新绑定。
 *
 * <p>判据是加载前沿：只有水位线相对"上次判空"推进过才重扫；水位线不变时不得重扫
 * （否则每次无偏移的普通热更都会白跑一遍全量预测）。</p>
 */
class OffsetIndexFrontierRescanTest {

	@BeforeEach
	void setUp() {
		LoadFrontier.reset();
		BatchLayoutCoordinator.clearOffsetIndex();
		BatchLayoutCoordinator.resetEmptyIndexWatermarkForTest();
	}

	@AfterEach
	void tearDown() {
		LoadFrontier.reset();
		BatchLayoutCoordinator.clearOffsetIndex();
		BatchLayoutCoordinator.resetEmptyIndexWatermarkForTest();
	}

	/** 水位线不变：判定为"确实没有偏移持有者"，不再重扫。 */
	@Test
	void unchangedFrontierDoesNotRescan() {
		LoadFrontier.birth(1L, "probe/NoOffsets", "");
		assertTrue(BatchLayoutCoordinator.getOffsetIndex().isEmpty());

		// 第一次判定必须重扫一次：此前从没在这个水位线上判过空。
		assertEquals(1, BatchLayoutCoordinator.rescanOnEmptyIndexForTest());
		assertEquals(0, BatchLayoutCoordinator.rescanOnEmptyIndexForTest(),
		 "with an unchanged frontier the empty index is a final answer, not a stale one");
		assertEquals(1, BatchLayoutCoordinator.rescanCountForTest());
	}

	/** 水位线推进：允许重扫一次；重扫后再判定就不再重复。 */
	@Test
	void advancedFrontierTriggersAtMostOneRescan() {
		LoadFrontier.birth(1L, "probe/First", "");
		BatchLayoutCoordinator.rescanOnEmptyIndexForTest();

		LoadFrontier.birth(1L, "probe/Second", "");
		BatchLayoutCoordinator.rescanOnEmptyIndexForTest();

		// 每次水位线推进各触发一次：First 一次、Second 一次。
		assertEquals(2, BatchLayoutCoordinator.rescanCountForTest(),
		 "each frontier advance must trigger exactly one rescan");
		assertEquals(0, BatchLayoutCoordinator.rescanOnEmptyIndexForTest());
		assertEquals(2, BatchLayoutCoordinator.rescanCountForTest(),
		 "the same watermark must not trigger another rescan");
	}
}
