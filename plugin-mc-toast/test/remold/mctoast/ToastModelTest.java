package remold.mctoast;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import remold.HotswapBatchReport;
import remold.HotswapBatchReport.InitFixStats;
import remold.HotswapBatchReport.MixinChange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToastModelTest {

	@Test
	void unknownElapsedAndEmptyReportDegradeExplicitly() {
		ToastModel model = ToastModel.from(null);
		assertEquals("[HotSwap] Elapsed unknown", model.getTitle());
		assertEquals(Collections.singletonList("No observable reload changes"), model.getSummaryLines());
	}

	@Test
	void formatsSingleAndMultipleClasses() {
		ToastModel single = ToastModel.from(
			new HotswapBatchReport(42L, Collections.singletonList("com.example.ItemWand"), null, null));
		assertEquals("[HotSwap] 42 ms", single.getTitle());
		assertEquals(Collections.singletonList("1 class reloaded: ItemWand"), single.getSummaryLines());

		ToastModel three = ToastModel.from(
			new HotswapBatchReport(15L, Arrays.asList("a.One", "b.Two", "c.Three"), null, null));
		assertEquals(Collections.singletonList("3 classes reloaded: One, Two, Three"), three.getSummaryLines());

		ToastModel many = ToastModel.from(
			new HotswapBatchReport(15L, Arrays.asList("a.A", "b.B", "c.C", "d.D"), null, null));
		assertEquals(Collections.singletonList("4 classes reloaded"), many.getSummaryLines());
	}

	@Test
	void formatsMixinDeltaKnownAndUnknown() {
		MixinChange known = new MixinChange("com.example.mixin.PlayerMixin", true,
			1, 0, 2, 1, 3, 4, null, null);
		ToastModel singleKnown = ToastModel.from(new HotswapBatchReport(18L, null,
			Collections.singletonList(known), null));
		assertEquals(Collections.singletonList("Mixin PlayerMixin: +1 @Unique / +2 @Inject / -1 @Inject"),
			singleKnown.getSummaryLines());

		MixinChange unknown = new MixinChange("com.example.mixin.HudMixin", false,
			0, 0, 0, 0, 2, 3, null, null);
		ToastModel singleUnknown = ToastModel.from(new HotswapBatchReport(18L, null,
			Collections.singletonList(unknown), null));
		assertEquals(Collections.singletonList("Mixin HudMixin: delta unknown (2 @Unique / 3 @Inject)"),
			singleUnknown.getSummaryLines());

		ToastModel multiUnknown = ToastModel.from(new HotswapBatchReport(18L, null,
			Arrays.asList(known, unknown), null));
		assertEquals(Collections.singletonList("Mixins (2): delta unknown"), multiUnknown.getSummaryLines());
	}

	@Test
	void formatsInitFixAndEnforcesThreeLineLimit() {
		InitFixStats stats = new InitFixStats(Collections.singletonList("com.example.Foo"),
			5, 3, 1, Arrays.asList("alpha", "beta", "gamma"));
		MixinChange mixin = new MixinChange("com.example.mixin.FooMixin", true,
			1, 0, 0, 0, 1, 0, null, null);
		HotswapBatchReport report = new HotswapBatchReport(99L,
			Collections.singletonList("com.example.Foo"),
			Collections.singletonList(mixin),
			stats);

		ToastModel model = ToastModel.from(report);
		List<String> lines = model.getSummaryLines();
		assertEquals(3, lines.size());
		assertEquals("1 class reloaded: Foo", lines.get(0));
		assertEquals("Mixin FooMixin: +1 @Unique", lines.get(1));
		assertEquals("InitFix: 5 instances / 3 fields (alpha, beta + 1 more), 1 incomplete", lines.get(2));
		assertTrue(lines.size() <= ToastModel.MAX_SUMMARY_LINES);
	}
}
