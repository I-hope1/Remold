package remold;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 动态单类重定义（{@code redefineDynamic}）的布局判决回归。
 *
 * <p>删除 {@code @Unique} 字段属于“删字段”而非“改类型”：判据是<b>批次外读者是否为空</b>，
 * 不是存活实例。动态路径必须把 target 自己算进批次（{@code Set.of(target)}），
 * 否则扫描器会把 target 的旧缓存当成外部读者，删除永远被拒。</p>
 *
 * <p>这里锁的是判决层：同一份 {@code RemoldAgent.RemovedMemberScan} 输入必须得到
 * {@code WAIVE_NO_READERS / REJECT / REJECT}。接线层的真机验证见模板中的删除场景复跑。</p>
 */
public class DynamicLayoutWaiverTest {

	private static final String TARGET = "com.example.item.RemoldWandItem";
	private static final List<String> REMOVED_AUTHOR = List.of("- remold$author");

	@Test
	void removedFieldWithNoOutOfBatchReaderIsWaived() {
		RemoldAgent.RemovedMemberScan scan = new RemoldAgent.RemovedMemberScan(
			true, List.of(), List.of(), false, false, List.of());

		RemoldAgent.LayoutDecision decision = RemoldAgent.decideLayout(
			TARGET, REMOVED_AUTHOR, LayoutGate.MODE_REJECT, RemoldAgent.Liveness.LIVE,
			Set.of(TARGET), scan, null, Set.of());

		assertEquals(RemoldAgent.LayoutAction.WAIVE_NO_READERS, decision.action(),
			"removing a field with no out-of-batch reader must be waived, not blocked by liveness");
	}

	@Test
	void removedFieldWithAnOutOfBatchReaderIsStillRejected() {
		RemoldAgent.RemovedMemberScan scan = new RemoldAgent.RemovedMemberScan(
			true, List.of("com.example.Reader"), List.of(), false, false, List.of());

		RemoldAgent.LayoutDecision decision = RemoldAgent.decideLayout(
			TARGET, REMOVED_AUTHOR, LayoutGate.MODE_REJECT, RemoldAgent.Liveness.LIVE,
			Set.of(TARGET), scan, null, Set.of());

		assertEquals(RemoldAgent.LayoutAction.REJECT, decision.action(),
			"a dangling out-of-batch reader must keep the removal blocked");
	}

	@Test
	void removedFieldWithoutACompletedScanIsRejected() {
		RemoldAgent.LayoutDecision decision = RemoldAgent.decideLayout(
			TARGET, REMOVED_AUTHOR, LayoutGate.MODE_REJECT, RemoldAgent.Liveness.LIVE,
			Set.of(TARGET), RemoldAgent.RemovedMemberScan.NOT_SCANNED, null, Set.of());

		assertEquals(RemoldAgent.LayoutAction.REJECT, decision.action(),
			"an incomplete reader scan must fail closed; liveness cannot answer future readers");
	}
}
