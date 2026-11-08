package remold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BatchReportScope} 的纯 JVM 测试：不重定义任何类，只验证批次数据的收集、去重、
 * 嵌套恢复与“无活动批次”时的静默行为。
 */
@DisplayName("Hotswap Batch Report Scope")
public class BatchReportScopeTest {

	@Test
	@DisplayName("collects elapsed, classes, mixin changes and merged InitFix stats")
	void collectsBatchData() {
		BatchReportScope.begin();
		HotswapBatchReport report;
		try {
			assertTrue(BatchReportScope.isActive(), "begin() must arm the scope");

			BatchReportScope.recordMixinChange(new HotswapBatchReport.MixinChange(
				"com.example.mixin.FooMixin", true, 1, 0, 1, 0, 2, 1,
				Collections.singletonList("extra"), Collections.singletonList("onUse")));

			// Two hosts, one shared field name: totals add up, field names dedupe.
			BatchReportScope.recordInitFix("com.example.A", 15,
				Arrays.asList("shield", "hp"), 1);
			BatchReportScope.recordInitFix("com.example.B", 2,
				Arrays.asList("shield"), 0);

			List<String> classes = Arrays.asList("com.example.A", "com.example.B");
			report = BatchReportScope.finish(classes);
		} finally {
			BatchReportScope.discard();
		}

		assertNotNull(report);
		assertFalse(BatchReportScope.isActive(), "finish() must clear the active scope");
		assertTrue(report.getElapsedMillis() >= 0, "elapsed must be a measured non-negative value");
		assertEquals(Arrays.asList("com.example.A", "com.example.B"), report.getRedefinedClasses());
		assertEquals(1, report.getMixinChanges().size());

		HotswapBatchReport.MixinChange change = report.getMixinChanges().get(0);
		assertTrue(change.isDeltaKnown());
		assertEquals(1, change.getUniqueFieldsAdded());
		assertEquals(1, change.getInjectHandlersAdded());
		assertEquals(2, change.getUniqueFieldCount());

		HotswapBatchReport.InitFixStats stats = report.getInitFixStats();
		assertEquals(2, stats.getPatchedClasses().size());
		assertEquals(17, stats.getLiveInstances(), "instance targets are summed per host");
		assertEquals(2, stats.getPatchedFields(), "field names are deduped across hosts");
		assertEquals(1, stats.getIncompleteFields());
		assertEquals(Arrays.asList("shield", "hp"), stats.getFieldNames());
		assertTrue(report.hasObservableActivity());
	}

	@Test
	@DisplayName("records are ignored and finish returns null without an active scope")
	void noActiveScopeIsNoOp() {
		assertFalse(BatchReportScope.isActive());
		assertNull(BatchReportScope.finish(Collections.singletonList("com.example.A")),
			"finish without begin must not fabricate a report");

		// Must not throw and must not leak state.
		BatchReportScope.recordInitFix("com.example.A", 1, Collections.singletonList("f"), 0);
		BatchReportScope.recordMixinChange(new HotswapBatchReport.MixinChange(
			"m", false, 0, 0, 0, 0, 0, 0, null, null));
		assertFalse(BatchReportScope.isActive());
		assertNull(BatchReportScope.finish(null));
	}

	@Test
	@DisplayName("nested scopes are isolated and restore the outer scope")
	void nestedScopesAreIsolated() {
		BatchReportScope.begin();
		BatchReportScope.recordInitFix("outer.A", 1, Collections.singletonList("x"), 0);

		BatchReportScope.begin();
		BatchReportScope.recordInitFix("inner.B", 2, Collections.singletonList("y"), 0);
		HotswapBatchReport inner = BatchReportScope.finish(Collections.singletonList("inner.B"));
		assertNotNull(inner);
		assertEquals(Collections.singletonList("inner.B"), inner.getInitFixStats().getPatchedClasses());
		assertTrue(BatchReportScope.isActive(), "inner finish must restore the outer scope");

		HotswapBatchReport outer = BatchReportScope.finish(Collections.singletonList("outer.A"));
		assertNotNull(outer);
		assertEquals(Collections.singletonList("outer.A"), outer.getInitFixStats().getPatchedClasses());
		assertFalse(BatchReportScope.isActive());
	}

	@Test
	@DisplayName("report and nested lists are immutable defensive copies")
	void reportIsImmutable() {
		List<String> classes = new java.util.ArrayList<>(Collections.singletonList("com.example.A"));
		HotswapBatchReport report = new HotswapBatchReport(5, classes,
			Collections.emptyList(), HotswapBatchReport.InitFixStats.EMPTY);
		classes.add("com.example.B");

		assertEquals(Collections.singletonList("com.example.A"), report.getRedefinedClasses());
		assertThrows(UnsupportedOperationException.class,
			() -> report.getRedefinedClasses().add("com.example.C"));
		assertThrows(UnsupportedOperationException.class,
			() -> report.getMixinChanges().add(null));
		assertEquals(HotswapBatchReport.UNKNOWN_ELAPSED,
			new HotswapBatchReport(-1, null, null, null).getElapsedMillis());
	}

	@Test
	@DisplayName("mixin-only batches are observable even with zero redefined classes")
	void mixinOnlyBatchIsObservable() {
		BatchReportScope.begin();
		BatchReportScope.recordMixinChange(new HotswapBatchReport.MixinChange(
			"com.example.mixin.FooMixin", false, 0, 0, 0, 0, 3, 2, null, null));
		HotswapBatchReport report = BatchReportScope.finish(Collections.emptyList());

		assertNotNull(report);
		assertTrue(report.getRedefinedClasses().isEmpty());
		assertTrue(report.hasObservableActivity(),
			"a mixin-only batch must still be published to report listeners");
		assertFalse(BatchReportScope.isActive());
	}
}
