package com.example.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link HotSwapToastModel} 的纯逻辑测试：不加载 Minecraft，直接断言最终展示文案。
 *
 * <p>断言关注两件事：信息维度齐全（耗时 / 类 / Mixin / InitFix），以及数据缺失时
 * 必须显式降级而不是编造 0 值。</p>
 */
class HotSwapToastModelTest {

    @Test
    void formatsAllObservedDimensions() {
        HotSwapToastModel model = HotSwapToastModel.from(
            38,
            Collections.singletonList("com.example.mixin.PlayerRendererMixin"),
            Collections.singletonList(new HotSwapToastModel.MixinSummary(
                "com.example.mixin.PlayerRendererMixin", true, 1, 0, 1, 0, 2, 1)),
            new HotSwapToastModel.InitFixSummary(
                Collections.singletonList("com.example.entity.PlayerData"),
                15, 1, 0, Collections.singletonList("shield")));

        assertEquals("[HotSwap] 38 ms", model.getTitle());
        assertEquals(3, model.getSummaryLines().size());
        assertTrue(model.getSummaryLines().get(0).contains("1 个类已热更"));
        assertTrue(model.getSummaryLines().get(0).contains("PlayerRendererMixin"),
            "class line should use the simple name");
        assertEquals("Mixin PlayerRendererMixin：+1 @Unique / +1 @Inject",
            model.getSummaryLines().get(1));
        assertEquals("InitFix：15 个实例 / 1 字段（shield）", model.getSummaryLines().get(2));
    }

    @Test
    void unknownElapsedIsNotReportedAsZero() {
        HotSwapToastModel model = HotSwapToastModel.from(
            -1, Collections.singletonList("com.example.Foo"), Collections.emptyList(), null);

        assertEquals("[HotSwap] 耗时未知", model.getTitle());
        assertFalse(model.getTitle().contains("0 ms"), "unknown elapsed must not become 0 ms");
    }

    @Test
    void unknownMixinDeltaShowsTotalsAndNeverFabricatesDeltas() {
        HotSwapToastModel model = HotSwapToastModel.from(
            12,
            Collections.singletonList("com.example.Foo"),
            Collections.singletonList(new HotSwapToastModel.MixinSummary(
                "com.example.mixin.FooMixin", false, 0, 0, 0, 0, 2, 1)),
            null);

        String line = model.getSummaryLines().get(1);
        assertTrue(line.contains("增量未知"), "deltaKnown=false must be surfaced: " + line);
        assertTrue(line.contains("2 @Unique") && line.contains("1 @Inject"), line);
        assertFalse(line.contains("+0"), "unknown delta must not be rendered as +0: " + line);
        assertFalse(line.contains("-0"), "unknown delta must not be rendered as -0: " + line);
    }

    @Test
    void removedMembersAreVisible() {
        HotSwapToastModel model = HotSwapToastModel.from(
            7,
            Collections.singletonList("com.example.Foo"),
            Collections.singletonList(new HotSwapToastModel.MixinSummary(
                "com.example.mixin.FooMixin", true, 0, 2, 0, 1, 1, 0)),
            null);

        assertEquals("Mixin FooMixin：-2 @Unique / -1 @Inject", model.getSummaryLines().get(1));
    }

    @Test
    void summaryIsCappedAtThreeLines() {
        List<String> classes = Arrays.asList("a.One", "a.Two", "a.Three", "a.Four", "a.Five");
        List<HotSwapToastModel.MixinSummary> mixins = Arrays.asList(
            new HotSwapToastModel.MixinSummary("m.One", true, 1, 0, 0, 0, 1, 0),
            new HotSwapToastModel.MixinSummary("m.Two", true, 0, 0, 1, 0, 0, 1),
            new HotSwapToastModel.MixinSummary("m.Three", false, 0, 0, 0, 0, 1, 0));

        HotSwapToastModel model = HotSwapToastModel.from(
            120, classes, mixins,
            new HotSwapToastModel.InitFixSummary(Collections.singletonList("a.One"), 4, 2, 0,
                Arrays.asList("x", "y")));

        assertEquals(HotSwapToastModel.MAX_SUMMARY_LINES, model.getSummaryLines().size());
        assertTrue(model.getSummaryLines().get(0).contains("5 个类已热更"));
    }

    @Test
    void legacyModeMarksUnavailableDimensionsInsteadOfInventingThem() {
        HotSwapToastModel model = HotSwapToastModel.legacy(Arrays.asList("a.One", "a.Two"));

        assertEquals("[HotSwap] 热更完成", model.getTitle());
        assertEquals(2, model.getSummaryLines().size());
        assertTrue(model.getSummaryLines().get(0).contains("2 个类已热更"));
        assertTrue(model.getSummaryLines().get(1).contains("旧版 Agent"));
        assertTrue(model.getSummaryLines().get(1).contains("耗时"));
        assertFalse(model.getTitle().contains("ms"));
    }

    @Test
    void staticOnlyInitFixDoesNotClaimZeroInstances() {
        HotSwapToastModel model = HotSwapToastModel.from(
            9,
            Collections.singletonList("com.example.Foo"),
            Collections.emptyList(),
            new HotSwapToastModel.InitFixSummary(
                Collections.singletonList("com.example.Foo"), 0, 1, 0,
                Collections.singletonList("counter")));

        String line = model.getSummaryLines().get(1);
        assertEquals("InitFix：1 字段（counter）", line);
        assertFalse(line.contains("0 个实例"), "no instance patch must not print 0 instances: " + line);
    }

    @Test
    void incompleteFieldsAreDisclosed() {
        HotSwapToastModel model = HotSwapToastModel.from(
            21,
            Collections.singletonList("com.example.Foo"),
            Collections.emptyList(),
            new HotSwapToastModel.InitFixSummary(
                Collections.singletonList("com.example.Foo"), 3, 2, 1,
                Arrays.asList("a", "b")));

        assertEquals("InitFix：3 个实例 / 2 字段（a, b），1 个未完成", model.getSummaryLines().get(1));
    }

    @Test
    void multipleMixinsAggregateOnlyWhenEveryDeltaIsKnown() {
        HotSwapToastModel known = HotSwapToastModel.from(
            5, Collections.emptyList(),
            Arrays.asList(
                new HotSwapToastModel.MixinSummary("m.One", true, 1, 0, 0, 0, 1, 0),
                new HotSwapToastModel.MixinSummary("m.Two", true, 0, 0, 1, 0, 0, 1)),
            null);
        assertEquals("Mixin 2 个（+1 @Unique / +1 @Inject）", known.getSummaryLines().get(0));
        assertFalse(known.getSummaryLines().get(0).startsWith("0 个类"),
            "mixin-only batch must not print a fabricated class line");

        HotSwapToastModel mixed = HotSwapToastModel.from(
            5, Collections.emptyList(),
            Arrays.asList(
                new HotSwapToastModel.MixinSummary("m.One", true, 1, 0, 0, 0, 1, 0),
                new HotSwapToastModel.MixinSummary("m.Two", false, 0, 0, 0, 0, 1, 0)),
            null);
        assertEquals("Mixin 2 个（增量未知）", mixed.getSummaryLines().get(0));
    }

    @Test
    void simpleNameHandlesMissingOrNestedNames() {
        assertEquals("Outer$Inner", HotSwapToastModel.simpleName("a.b.Outer$Inner"));
        assertEquals("?", HotSwapToastModel.simpleName(null));
        assertEquals("Foo", HotSwapToastModel.simpleName("Foo"));
    }
}
