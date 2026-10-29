package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 热更摘要里"跳过了谁"的台账与格式断言。
 *
 * <p>要守的性质只有一条，但它是最容易被无声破坏的那条：<b>被门拦下的类必须出现在摘要里</b>。
 * {@code HotSwap successful: N classes redefined} 只报成功个数，而布局门 / 对齐器 / 结构门拦下的
 * 类恰恰是用户最需要知道的 —— 否则控制台显示"成功"，运行态却是旧代码，排查只能靠翻全量日志。</p>
 *
 * <h2>覆盖边界（如实说明）</h2>
 * <p>本类只覆盖 {@link RemoldAgent.RoundSkips} 的语义与摘要格式。各门里的 {@code skips.add(...)}
 * 接线点需要真的跑一轮 redefine（DCEVM/JBR）才能观测，属已知缺口；它们与本文件断言的是同一个
 * 数据结构的写入侧，故格式与去重规则仍被守住。</p>
 */
class HotswapSkipSummaryTest {

	/** 捕获日志，按级别分开记 —— 级别本身是被断言的对象（摘要必须走 warn）。 */
	private static final class CapturingLogger implements RemoldAgent.Logger {
		final List<String> warns = new ArrayList<>();
		final List<String> infos = new ArrayList<>();
		final List<String> logs  = new ArrayList<>();
		final List<String> errors = new ArrayList<>();

		@Override public void log(String msg)   { logs.add(msg); }
		@Override public void info(String msg)  { infos.add(msg); }
		@Override public void warn(String msg)  { warns.add(msg); }
		@Override public void error(String msg) { errors.add(msg); }
		@Override public void error(String msg, Throwable t) { errors.add(msg); }
	}

	private RemoldAgent.Logger previous;
	private CapturingLogger    capture;

	@BeforeEach
	void installLogger() {
		previous = RemoldAgent.logger;
		capture  = new CapturingLogger();
		RemoldAgent.logger = capture;
	}

	@AfterEach
	void restoreLogger() {
		RemoldAgent.logger = previous;
	}

	/** 只取摘要自己的行，避免将来别的日志混进来时断言变脆。 */
	private List<String> summaryLines() {
		List<String> out = new ArrayList<>();
		for (String s : capture.warns) if (s.startsWith("[HOTSWAP-SKIPPED]")) out.add(s);
		return out;
	}

	@Test
	void emptyLedgerPrintsNothing() {
		// 阴性对照：没有跳过项时必须完全静默。否则"每轮都打一段摘要"会让真正的跳过信息被淹掉，
		// 断言也就失去意义（这条同时挡住"无条件打印"这种实现）。
		new RemoldAgent.RoundSkips().report();
		assertTrue(summaryLines().isEmpty(), "没有跳过项时不得输出任何摘要行");
		assertTrue(capture.warns.isEmpty(), "没有跳过项时不得产生 warn");
	}

	@Test
	void listsEverySkippedClassWithItsVerdict() {
		RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();
		skips.add("com.example.Widget", "LAYOUT-REJECT");
		skips.add("com.example.Other", "REDEFINE-FAILED");
		skips.report();

		List<String> lines = summaryLines();
		assertEquals(3, lines.size(), "应为 1 行表头 + 2 行类名，实际：" + lines);
		assertTrue(lines.get(0).contains("2 class(es)"), "表头必须报出被跳过的个数：" + lines.get(0));
		assertTrue(lines.get(1).contains("com.example.Widget") && lines.get(1).contains("LAYOUT-REJECT"),
			"每个被跳过的类都必须点名并带上判决码：" + lines.get(1));
		assertTrue(lines.get(2).contains("com.example.Other") && lines.get(2).contains("REDEFINE-FAILED"),
			"第二个类同样要点名：" + lines.get(2));
	}

	@Test
	void firstVerdictWins() {
		// 同一个类可能被多个门先后拦下（布局门拒绝 → 已被移出本批，正常不会再走到别处）。
		// 摘要里必须只出现一次，且保留**第一个**门的原因，否则"为什么被跳过"会指向次要原因。
		RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();
		skips.add("com.example.Widget", "LAYOUT-REJECT");
		skips.add("com.example.Widget", "REDEFINE-FAILED");
		skips.report();

		List<String> lines = summaryLines();
		assertEquals(2, lines.size(), "同一个类只能占一行，实际：" + lines);
		assertTrue(lines.get(1).contains("LAYOUT-REJECT"), "应保留首个判决：" + lines.get(1));
		assertFalse(lines.get(1).contains("REDEFINE-FAILED"), "后续判决不得覆盖首个原因：" + lines.get(1));
	}

	@Test
	void groupMembersAreLabelledWithTheirHost() {
		// 宿主被拒时整组匿名类一起移出本批。摘要里单看 Foo$3 毫无意义，
		// 必须说明它属于哪个宿主组，否则用户会以为那是另一个独立问题。
		Set<String> dropped = new LinkedHashSet<>(List.of("com.example.Widget", "com.example.Widget$1"));
		RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();
		skips.addGroup(dropped, "com.example.Widget", "LAYOUT-REJECT");
		skips.report();

		List<String> lines = summaryLines();
		assertEquals(3, lines.size(), "宿主 + 1 个匿名类应为两行，实际：" + lines);
		assertEquals("[HOTSWAP-SKIPPED]   com.example.Widget  <- LAYOUT-REJECT", lines.get(1),
			"宿主行不应带从属说明");
		assertTrue(lines.get(2).contains("com.example.Widget$1"), "匿名类必须点名：" + lines.get(2));
		assertTrue(lines.get(2).contains("same host group as com.example.Widget"),
			"匿名类必须标注从属于哪个宿主组：" + lines.get(2));
	}

	@Test
	void capsListedNamesButKeepsTheCount() {
		// 一次改了几十个类全被拒时，摘要不能把控制台刷屏，但总数必须准确 ——
		// 少报个数会让用户以为"只跳过了几个"，实际影响面更大。
		RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();
		for (int i = 0; i < 20; i++) skips.add("com.example.C" + i, "LAYOUT-REJECT");
		skips.report();

		List<String> lines = summaryLines();
		assertEquals(1 + RemoldAgent.RoundSkips.MAX_LISTED + 1, lines.size(),
			"应为表头 + " + RemoldAgent.RoundSkips.MAX_LISTED + " 行类名 + 1 行省略，实际：" + lines.size());
		assertTrue(lines.get(0).contains("20 class(es)"), "表头必须报总数而不是列出数：" + lines.get(0));
		assertTrue(lines.get(lines.size() - 1).contains("5 more"), "省略行要给出剩余个数：" + lines.get(lines.size() - 1));
	}

	@Test
	void summaryGoesThroughWarnNotDebugGatedLog() {
		// 这条守的是"默认配置下能不能看见"。log() 受 DEBUG 门控（默认关），
		// 摘要一旦走 log() 就等于在发布版里彻底消失 —— 那正是这条需求要解决的问题本身。
		RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();
		skips.add("com.example.Widget", "LAYOUT-REJECT");
		skips.report();

		assertFalse(capture.warns.isEmpty(), "摘要必须走 warn");
		assertTrue(capture.logs.isEmpty(), "摘要不得走 log（受 DEBUG 门控，默认不输出）");
	}

	@Test
	void layoutRejectLineNamesTheFieldAndTheNextStep() {
		// 摘要行必须能**单独读懂**：光有 LAYOUT-REJECT 这个码，用户还得往上翻几屏才知道
		// 是哪个字段、该干什么。这条把"判决码 + 具体字段 + 下一步"的措辞钉死。
		RemoldAgent.LayoutDecision d = new RemoldAgent.LayoutDecision(
			RemoldAgent.LayoutAction.REJECT,
			LayoutGate.checkChangedFields(List.of("- COST", "+ scratchProbe")),
			"com.example.LightSpeedConveyor", Set.of("com.example.LightSpeedConveyor"));

		String line = RemoldAgent.skipVerdictForLayout(d, RemoldAgent.RemovedMemberScan.NOT_SCANNED);

		assertEquals("LAYOUT-REJECT: field removed: COST"
		             + " — restore the removed field(s) or restart to continue", line,
			"摘要行必须点名被删的字段并给出下一步，实际：" + line);
	}

	@Test
	void layoutRejectLineSaysWhenTheReaderScanCouldNotRun() {
		// 扫描没完成是独立的拒绝理由。若摘要只写"删了字段"，看起来像"删字段就会被拒"，
		// 而实际规则是"删字段且证明不了没有批次外读者才拒"——两者必须能区分。
		RemoldAgent.LayoutDecision d = new RemoldAgent.LayoutDecision(
			RemoldAgent.LayoutAction.REJECT,
			LayoutGate.checkChangedFields(List.of("- COST")),
			"com.example.LightSpeedConveyor", Set.of("com.example.LightSpeedConveyor"),
			"field(s) removed but the reader scan could not complete");

		String line = RemoldAgent.skipVerdictForLayout(d, RemoldAgent.RemovedMemberScan.NOT_SCANNED);

		assertTrue(line.contains("(reader scan could not complete)"),
			"扫描没完成必须写进摘要，实际：" + line);
	}

	@Test
	void verdictOmitsEmptyParts() {
		// 不是每个判决都有细节可填（例如 BLACKLIST 之外的一些路径）。缺的部分不能留下
		// 空的 "CODE:  — " 残骸。
		assertEquals("X", RemoldAgent.RoundSkips.verdict("X", null, null));
		assertEquals("X: d", RemoldAgent.RoundSkips.verdict("X", "d", null));
		assertEquals("X — a", RemoldAgent.RoundSkips.verdict("X", "", "a"));
	}
}
