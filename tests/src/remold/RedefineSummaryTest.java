package remold;

import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassDefinition;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 摘要计数口径与"假成功"红线的回归断言。
 *
 * <p>要守的性质有两条，都来自真机观测到的误导（见
 * {@code docs/runtime/06-anon-field-shape-drill.md} §6）：</p>
 * <ol>
 *   <li><b>零生效禁称成功</b>：用户改的类全被门拒绝、同批只剩"字节未变"的类被重新提交时，
 *       日志不得出现 {@code HotSwap successful}；</li>
 *   <li><b>按类去重</b>：两阶段路径把同一类分别提交给阶段 A / 阶段 C，计数不得按调用次数累加。</li>
 * </ol>
 *
 * <p>覆盖边界：本类只锁 {@link RedefineSummary} 的计数与措辞分支；门拒绝与 redefine 的接线点需要
 * DCEVM/JBR 真机才能观测，由 {@code :tests:anonFieldShapeProbe} 的日志证据覆盖（同一口径）。</p>
 */
class RedefineSummaryTest {

	private static ClassDefinition def(Class<?> type) {
		return new ClassDefinition(type, new byte[] {(byte) 0xCA, (byte) 0xFE});
	}

	@Test
	void zeroEffectiveMustNotClaimSuccess() {
		// 现场形态：changed = {String}，本批实际提交的却是两个"字节未变"的类，且 changed 被跳过。
		RedefineSummary summary = RedefineSummary.of(
			List.of("java.lang.String"),
			List.of(def(Integer.class), def(Long.class)),
			1);

		assertEquals(1, summary.changed, "去重变更类数");
		assertEquals(0, summary.applied, "真实改动一个都没落地");
		assertEquals(2, summary.noop, "两个类只是被无实质变更地重新提交");
		assertTrue(summary.nothingTookEffect(), "applied == 0 必须被识别为'零生效'");
		String line = summary.line();
		assertFalse(line.contains("HotSwap successful"),
			"零生效时禁止出现成功字样，实际行: " + line);
		assertTrue(line.contains("0 changed class(es) took effect"), "必须明确报零生效，实际行: " + line);
		assertTrue(line.contains("2 unchanged class(es)"), "无实质变更数必须点名，实际行: " + line);
	}

	@Test
	void appliedCountsOnlyChangedClasses() {
		RedefineSummary summary = RedefineSummary.of(
			List.of("java.lang.String", "java.lang.Integer"),
			List.of(def(String.class), def(Integer.class), def(Long.class), def(Object.class)),
			0);

		assertEquals(2, summary.applied, "只有变更类算生效");
		assertEquals(2, summary.noop, "其余入参算无实质变更");
		assertFalse(summary.nothingTookEffect());
		String line = summary.line();
		assertTrue(line.startsWith("HotSwap successful: 2 class(es) redefined."),
			"成功行必须按变更类计数，实际行: " + line);
		assertTrue(line.contains("2 unchanged class(es)"), "无实质变更数必须与生效数分开报，实际行: " + line);
	}

	@Test
	void twoPhaseSameClassIsCountedOnce() {
		// 同一类被阶段 A / 阶段 C 各提交一次：按调用次数累加会得到 2，去重后必须是 1。
		RedefineSummary summary = RedefineSummary.of(
			List.of("java.lang.String"),
			List.of(def(String.class), def(String.class), def(Long.class)),
			0);

		assertEquals(1, summary.changed, "变更类按名去重");
		assertEquals(1, summary.applied, "同一类提交两次仍只算一个生效类");
		assertEquals(1, summary.noop, "同一无变更类提交两次也只算一个");
	}

	@Test
	void pureRescanStaysSilent() {
		// 启动期首轮形态：没有任何真实改动、也没有跳过项 —— 只重新提交了字节未变的类。
		// 此时必须完全静默：否则每轮启动都打一条"nothing reached the JVM"，把真正的告警淹掉。
		RedefineSummary summary = RedefineSummary.of(List.of(), List.of(def(Integer.class), def(Long.class)), 0);
		assertEquals(0, summary.changed);
		assertEquals(0, summary.applied);
		assertNull(summary.line(), "无改动且无跳过时不得发声");
	}

	@Test
	void wholeFamilyRejectedWithEmptyDefinitionsStillReportsZeroEffect() {
		// Kotlin 宿主名后缀族整族被拒的形态：一个定义都没提交，但有真实改动 + 跳过项。
		// 两条零生效路径（空定义 / 非空但全 no-op）必须共用同一措辞与同一"禁称成功"红线。
		RedefineSummary summary = RedefineSummary.of(
			List.of("kcm.classmode.ClosureHost$metricTable$1$1"),
			List.of(),
			5);
		assertEquals(1, summary.changed);
		assertEquals(0, summary.applied);
		assertTrue(summary.nothingTookEffect());
		String line = summary.line();
		assertNotNull(line, "有改动且有跳过时必须发声");
		assertFalse(line.contains("HotSwap successful"), "整族被拒时不得出现成功字样，实际行: " + line);
		assertTrue(summary.warnLevel(), "零生效必须 warn 级");
	}

	@Test
	void legacyEntryWithoutChangedSetDoesNotInventNoops() {
		// 旧入口（changedNames == null）：没有变更集信息时不得凭空判断"无实质变更"。
		RedefineSummary summary = RedefineSummary.of(null, List.of(def(String.class), def(Long.class)), 0);
		assertEquals(2, summary.applied, "缺变更集时退化为'入参即变更'");
		assertEquals(0, summary.noop);
		assertTrue(summary.line().contains("HotSwap successful: 2 class(es) redefined."));
	}

	@Test
	void countersLineExposesAllFourNumbers() {
		RedefineSummary summary = RedefineSummary.of(
			List.of("java.lang.String", "java.lang.Integer"),
			List.of(def(String.class), def(Long.class)),
			3);
		String counters = summary.counters();
		assertTrue(counters.contains("changed=2"), counters);
		assertTrue(counters.contains("applied=1"), counters);
		assertTrue(counters.contains("noop=1"), counters);
		assertTrue(counters.contains("skipped=3"), counters);
	}
}
