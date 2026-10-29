import remold.RemoldAgent;
import remold.RemoldAgent.LayoutAction;
import remold.RemoldAgent.LayoutDecision;
import remold.RemoldAgent.Liveness;
import remold.LayoutGate.Verdict;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 布局门<b>接线</b>的 JUnit 断言（§7.2 风险 1 的重定义层）。
 *
 * <p>与 {@code LayoutGateAssert} 的分工：那边守"规则表 + changedFields 格式对接"；本类守
 * {@link RemoldAgent#decideLayout} 这个纯函数 —— 它把接线里最易错的部分（取哪份字节、
 * 拒绝要带哪些类、off/skip/waive 三个出口、存活实例分级）抽了出来，可脱离 Instrumentation 单测。</p>
 *
 * <p>调用位置（必须在 {@code applyRedefinitions} 之前）不在本类覆盖范围内，属已知缺口。</p>
 */
class LayoutGateWiringTest {

	private static final String HOST = "lg.Host";

	/** 编译一个顶层类，返回其字节码。 */
	private static byte[] cls(String dotName, String source) {
		Map<String, byte[]> out = LayoutGateAssert.compile(Map.of(dotName, source));
		byte[] b = out.get(dotName);
		assertNotNull(b, "夹具编译失败：" + dotName);
		return b;
	}

	private static byte[] withField()    { return cls(HOST, "package lg;\npublic class Host { int a; }\n"); }
	private static byte[] withoutField() { return cls(HOST, "package lg;\npublic class Host { }\n"); }
	private static byte[] addedField()   { return cls(HOST, "package lg;\npublic class Host { int a; int b; }\n"); }
	private static byte[] retypedField() { return cls(HOST, "package lg;\npublic class Host { long a; }\n"); }
	private static byte[] staticField()  { return cls(HOST, "package lg;\npublic class Host { static int s; }\n"); }
	private static byte[] noStatic()     { return cls(HOST, "package lg;\npublic class Host { }\n"); }
	private static byte[] withMethod()   { return cls(HOST, "package lg;\npublic class Host { void m() { } }\n"); }
	private static byte[] constantField(){ return cls(HOST, "package lg;\npublic class Host { public static final int C = 1; }\n"); }
	private static byte[] noConstant()   { return cls(HOST, "package lg;\npublic class Host { }\n"); }
	private static byte[] withoutMethod(){ return cls(HOST, "package lg;\npublic class Host { }\n"); }

	/** 批次类名集：宿主 + 两个匿名类 + 一个具名内部类（后者不得被整组带走）+ 无关类。 */
	private static final Set<String> BATCH = new LinkedHashSet<>(List.of(
		HOST, "lg.Host$1", "lg.Host$2", "lg.Host$Builder", "lg.Other"));

	private static LayoutDecision decide(byte[] oldB, byte[] newB, String mode, Liveness live) {
		return RemoldAgent.decideLayout(HOST, oldB, newB, mode, live, BATCH);
	}

	@Test
	void removedFieldDropsWholeGroup() {
		LayoutDecision d = decide(withField(), withoutField(), "reject", Liveness.LIVE);
		assertEquals(LayoutAction.REJECT, d.action(), "删除字段 + 有实例必须拒绝");
		assertEquals(Verdict.REMOVED_FIELD, d.layout().verdict(), "档位应为 REMOVED_FIELD");
		assertEquals(HOST, d.hostName());
		assertEquals(Set.of(HOST, "lg.Host$1", "lg.Host$2"), d.dropped(),
			"整组 = 宿主 + 匿名类；具名内部类 Host$Builder 与无关类不得被带走");
	}

	@Test
	void typeChangeDropsWholeGroup() {
		LayoutDecision d = decide(withField(), retypedField(), "reject", Liveness.LIVE);
		assertEquals(LayoutAction.REJECT, d.action());
		assertEquals(Verdict.CHANGED_FIELD_TYPE, d.layout().verdict());
		assertTrue(d.dropped().contains(HOST) && d.dropped().contains("lg.Host$1"));
	}

	@Test
	void pureAddPasses() {
		LayoutDecision d = decide(withField(), addedField(), "reject", Liveness.LIVE);
		assertEquals(LayoutAction.PASS, d.action(), "纯新增字段放行，交给 InitFix");
		assertTrue(d.layout().compatible());
		assertTrue(d.dropped().isEmpty(), "放行时不得移出任何类");
	}

	@Test
	void warnModePassesButReports() {
		LayoutDecision d = decide(withField(), withoutField(), "warn", Liveness.LIVE);
		assertEquals(LayoutAction.PASS, d.action(), "warn 模式必须放行");
		assertNotNull(d.layout());
		assertFalse(d.layout().compatible(), "但规则结果必须非兼容，调用方据此强告警");
		assertTrue(d.dropped().isEmpty(), "warn 模式不产生移出集合");
	}

	@Test
	void offModeSkipsEntirely() {
		LayoutDecision d = decide(withField(), withoutField(), "off", Liveness.LIVE);
		assertEquals(LayoutAction.PASS, d.action());
		assertNull(d.layout(), "off 模式完全不解析字节");
		assertTrue(d.dropped().isEmpty());
	}

	@Test
	void missingBaselineIsNotSilent() {
		assertEquals(LayoutAction.SKIP,
			decide(null, withoutField(), "reject", Liveness.LIVE).action(),
			"取不到旧字节 → SKIP（放行但调用方必须告警）");
		assertEquals(LayoutAction.SKIP,
			decide(withField(), null, "reject", Liveness.LIVE).action());
	}

	/**
	 * 无存活实例时，<b>改类型</b>放行——旧值随布局一起丢弃，新实例走新构造器。
	 *
	 * <p>这条<b>不适用于删除</b>：删除的风险是"之后的实例被陈旧代码读"，与当前有无实例正交
	 * （`docs/status.md` 风险 12）。删字段走读者判据，见 `unscannedRemovalFallsBackToLiveness`
	 * 与 `removedFieldWithNoOutOfBatchReaderIsWaivedEvenWithLiveInstances`。</p>
	 */
	@Test
	void noLiveInstancesWaivesTypeChange() {
		LayoutDecision d = decide(withField(), retypedField(), "reject", Liveness.NONE);
		assertEquals(LayoutAction.WAIVE, d.action(),
			"a type change with no surviving instances is safe: the old values are gone either way");
		assertFalse(d.layout().compatible(), "但仍带非兼容规则结果供调用方记 waived");
		assertTrue(d.dropped().isEmpty());
	}

	@Test
	void unknownLivenessIsTreatedAsLive() {
		LayoutDecision d = decide(withField(), withoutField(), "reject", Liveness.UNKNOWN);
		assertEquals(LayoutAction.REJECT, d.action(),
			"实例判定取不到时必须按『可能有实例』处理，不得当成零实例放行");
		assertTrue(d.dropped().contains(HOST));
	}

	@Test
	void staticChangeNeverWaivedEvenWithoutInstances() {
		LayoutDecision d = decide(staticField(), noStatic(), "reject", Liveness.NONE);
		assertEquals(LayoutAction.REJECT, d.action(),
			"静态字段的旧值类级别共享，无实例也必须拒绝（不得 waive）");
		assertTrue(d.dropped().contains(HOST));
	}

	@Test
	void nestedAnonHostResolvesToOuter() {
		LayoutDecision d = RemoldAgent.decideLayout("lg.Host$1$2",
			withField(), withoutField(), "reject", Liveness.LIVE,
			new LinkedHashSet<>(List.of("lg.Host", "lg.Host$1", "lg.Host$1$2")));
		assertEquals("lg.Host", d.hostName(), "Foo$1$2 的宿主应归约到 Foo");
		assertEquals(LayoutAction.REJECT, d.action());
		assertTrue(d.dropped().contains("lg.Host$1") && d.dropped().contains("lg.Host$1$2"),
			"嵌套匿名类也归约到最外层宿主整组");
	}

	// ------------------------------------------------------------------------------------------------
	// 删除字段：判据是"批次外读者为空"，不是存活实例
	// （判据推导见 docs/initfix/07-p05-probe-results.md §13.6）
	// ------------------------------------------------------------------------------------------------

	private static LayoutDecision decideWithReaders(byte[] oldB, byte[] newB, Liveness live,
	                                                RemoldAgent.RemovedMemberScan readers) {
		return RemoldAgent.decideLayout(HOST, oldB, newB, "reject", live, BATCH, readers);
	}

	/**
	 * 删字段 + 扫描完成 + 批次外没有读者 → 放行，**哪怕还有存活实例**。
	 * 存活实例只是丢了一个没人读的槽位，不会让任何代码读到零值或抛错。
	 */
	@Test
	void removedFieldWithNoOutOfBatchReaderIsWaivedEvenWithLiveInstances() {
		LayoutDecision d = decideWithReaders(withField(), withoutField(), Liveness.LIVE,
		                                     new RemoldAgent.RemovedMemberScan(true, List.of(), List.of(), false));

		assertEquals(LayoutAction.WAIVE_NO_READERS, d.action(),
			"deleting a field nobody reads after the change is safe regardless of live instances");
		assertEquals(Verdict.REMOVED_FIELD, d.layout().verdict());
		assertTrue(d.dropped().isEmpty(), "放行时不得移出任何类");
	}

	/** 有批次外读者 → 拒绝，并把宿主整组带走。 */
	@Test
	void removedFieldWithOutOfBatchReaderIsRejected() {
		LayoutDecision d = decideWithReaders(withField(), withoutField(), Liveness.LIVE,
		                                     new RemoldAgent.RemovedMemberScan(
			                                     true, List.of("lg.Reader"), List.of(), false));

		assertEquals(LayoutAction.REJECT, d.action());
		assertEquals(Set.of(HOST, "lg.Host$1", "lg.Host$2"), d.dropped());
	}

	/**
	 * 没做扫描（{@code scanned == false}）时必须退回存活实例判据——
	 * 把"没扫"当成"没读者"是这条最危险的读法。
	 */
	@Test
	void unscannedRemovalFallsBackToLiveness() {
		LayoutDecision live = decideWithReaders(withField(), withoutField(), Liveness.LIVE,
		                                        RemoldAgent.RemovedMemberScan.NOT_SCANNED);
		assertEquals(LayoutAction.REJECT, live.action(),
			"without a reader scan nothing proves the deletion is safe");

		// 无实例也必须拒绝：liveness 回答的是"现在有没有实例会被错读"，而删除的风险是
		// "之后新建的实例被陈旧代码读"——两者正交。这里退回 WAIVE 就是把"没扫成"读成
		// "没读者"，正是风险 12 本身。
		LayoutDecision none = decideWithReaders(withField(), withoutField(), Liveness.NONE,
		                                        RemoldAgent.RemovedMemberScan.NOT_SCANNED);
		assertEquals(LayoutAction.REJECT, none.action(),
			"an empty liveness result says nothing about stale readers that will run later");
	}

	/**
	 * 静态字段删除与实例字段共用读者判据。实测（`P05Probe` M9）表明它的失败形态同样有
	 * "静默转移"（父类静态字段 / 接口字段接住）与"响亮的错"（`NoSuchFieldError`、
	 * `IncompatibleClassChangeError`），所以"无读者"同样是有效的放行依据。
	 */
	@Test
	void staticFieldRemovalUsesTheSameReaderRule() {
		LayoutDecision clean = decideWithReaders(staticField(), noStatic(), Liveness.LIVE,
			new RemoldAgent.RemovedMemberScan(true, List.of(), List.of(), false, true, List.of()));
		assertEquals(LayoutAction.WAIVE_NO_READERS, clean.action(),
			"a static field nobody reads after the change is safe regardless of live instances");

		LayoutDecision dirty = decideWithReaders(staticField(), noStatic(), Liveness.LIVE,
			new RemoldAgent.RemovedMemberScan(true, List.of("lg.Reader"), List.of(), false, true, List.of()));
		assertEquals(LayoutAction.REJECT, dirty.action(),
			"a dangling or retargeted static read must still be refused");
	}

	/**
	 * 静态字段删除<b>没有</b>存活实例兜底：静态值不随实例存在，扫描未完成时只能拒绝。
	 */
	@Test
	void unscannedStaticFieldRemovalIsRejected() {
		LayoutDecision d = decideWithReaders(staticField(), noStatic(), Liveness.NONE,
			new RemoldAgent.RemovedMemberScan(false, List.of(), List.of(), false, true, List.of()));
		assertEquals(LayoutAction.REJECT, d.action(),
			"liveness says nothing about static state, so an incomplete scan cannot be waived");
		assertNotNull(d.reason());
		assertTrue(d.reason().contains("scan"), "reason should name the incomplete scan: " + d.reason());
	}

	/**
	 * 编译期常量（{@code ConstantValue}）：javac 把值内联进读者的 {@code ldc}/{@code bipush}，
	 * 读者常量池里<b>没有 Fieldref</b>。所以"扫描不到读者"不是安全证明，而是没有信号——
	 * 必须保持强拒绝，哪怕读者集合是空的。
	 */
	@Test
	void constantFieldRemovalIsRejectedEvenWithNoReaders() {
		LayoutDecision d = decideWithReaders(constantField(), noConstant(), Liveness.LIVE,
			new RemoldAgent.RemovedMemberScan(true, List.of(), List.of(), false, true, List.of("C")));
		assertEquals(LayoutAction.REJECT, d.action(),
			"an empty reader set proves nothing when readers inline the value at compile time");
		assertNotNull(d.reason());
		assertTrue(d.reason().contains("C"), "reason should name the constant: " + d.reason());
		assertTrue(d.reason().contains("inline"), "reason should explain why the scan is blind: " + d.reason());
	}

	/**
	 * 换无关输入对照：改类型（不是删除）即使"没有读者"也仍按存活实例判——
	 * 因为旧值会被静默丢弃/错读，读者集合证明不了安全。
	 */
	@Test
	void typeChangeIgnoresReaderScan() {
		LayoutDecision d = decideWithReaders(withField(), retypedField(), Liveness.LIVE,
		                                     new RemoldAgent.RemovedMemberScan(true, List.of(), List.of(), false));
		assertEquals(LayoutAction.REJECT, d.action(),
			"a type change silently discards the old value, so an empty reader set proves nothing");
	}

	// ------------------------------------------------------------------------------------------------
	// 删除方法：不走 LayoutGate，靠 removedMethods 标志单独判
	// （机制差异与实测见 docs/initfix/07-p05-probe-results.md §15）
	// ------------------------------------------------------------------------------------------------

	private static RemoldAgent.RemovedMemberScan methodScan(boolean scanned, List<String> readers,
	                                                       List<String> shifted) {
		return new RemoldAgent.RemovedMemberScan(scanned, readers, shifted, true);
	}

	/**
	 * 删方法 + 批次外有读者 → 拒绝。注意这里<b>字段一个都没动</b>（{@code changedFields} 是空的），
	 * 所以判据必须在 {@code compatible} 早退之前——否则"只删方法"的编辑永远不会被拦。
	 */
	@Test
	void removedMethodWithOutOfBatchReaderIsRejected() {
		LayoutDecision d = decideWithReaders(withMethod(), withoutMethod(), Liveness.LIVE,
		                                     methodScan(true, List.of("lg.Reader"), List.of()));

		assertEquals(LayoutAction.REJECT, d.action(),
			"a dangling method reference must be refused even though no field changed");
		assertEquals(Set.of(HOST, "lg.Host$1", "lg.Host$2"), d.dropped());
		assertNotNull(d.reason(), "the log must not blame the field layout here");
		assertTrue(d.reason().contains("method"), "reason should say the removal was of methods: " + d.reason());
	}

	/** 分派静默转移（祖先实现接住了调用）同样拒绝：不抛异常，但行为已经变了。 */
	@Test
	void removedMethodWithDispatchShiftIsRejected() {
		LayoutDecision d = decideWithReaders(withMethod(), withoutMethod(), Liveness.LIVE,
		                                     methodScan(true, List.of(), List.of("lg.Reader")));

		assertEquals(LayoutAction.REJECT, d.action(),
			"a silent dispatch shift changes behaviour without throwing, so it cannot be allowed");
		assertTrue(d.reason().contains("dispatch"), "reason should name the dispatch shift: " + d.reason());
	}

	/** 证明"批次外无读者"之后放行——字段侧 compatible，所以整体 PASS。 */
	@Test
	void removedMethodWithNoReaderPasses() {
		LayoutDecision d = decideWithReaders(withMethod(), withoutMethod(), Liveness.LIVE,
		                                     methodScan(true, List.of(), List.of()));

		assertEquals(LayoutAction.PASS, d.action(), "no reader resolves to the removed method any more");
		assertTrue(d.dropped().isEmpty());
	}

	/**
	 * 扫描没完成 → 拒绝。方法删除<b>没有</b>存活实例判据可以退：它的影响与"当前有没有实例"正交
	 * （实测 M8a：有存活实例时三条路径照样全部静默转移）。
	 */
	@Test
	void unscannedMethodRemovalIsRejectedEvenWithoutLiveInstances() {
		LayoutDecision none = decideWithReaders(withMethod(), withoutMethod(), Liveness.NONE,
		                                        methodScan(false, List.of(), List.of()));
		assertEquals(LayoutAction.REJECT, none.action(),
			"without a completed scan there is nothing to prove safety with");

		LayoutDecision live = decideWithReaders(withMethod(), withoutMethod(), Liveness.LIVE,
		                                        methodScan(false, List.of(), List.of()));
		assertEquals(LayoutAction.REJECT, live.action());
	}

	/** warn 模式仍然放行（用户显式接受风险），但不静默——由调用方告警。 */
	@Test
	void removedMethodInWarnModePasses() {
		LayoutDecision d = RemoldAgent.decideLayout(HOST, withMethod(), withoutMethod(), "warn",
			Liveness.LIVE, BATCH, methodScan(true, List.of("lg.Reader"), List.of()));
		assertEquals(LayoutAction.PASS, d.action());
	}

	/**
	 * 换无关输入对照：本轮<b>没删方法</b>（{@code removedMethods == false}）时，
	 * 即使扫描报出读者也不能影响字段侧的判决。
	 */
	@Test
	void readerScanWithoutRemovedMethodsDoesNotRejectOnItsOwn() {
		LayoutDecision d = decideWithReaders(withField(), addedField(), Liveness.LIVE,
			new RemoldAgent.RemovedMemberScan(true, List.of("lg.Reader"), List.of(), false));
		assertEquals(LayoutAction.PASS, d.action(),
			"a pure field addition must not be rejected because some unrelated scan reported readers");
	}
}
