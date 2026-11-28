package probe;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import jdk.internal.misc.Unsafe;

import remold.BatchReportScope;
import remold.ClassDiffUtil;
import remold.HotswapBatchReport;
import remold.InstanceTracker;
import remold.RemoldAgent;
import remold.ref.InitFix;
import remold.ref.StageASkeleton;

/**
 * Real-JVM end-to-end probe for the value-type {@code new} initializer replay channel
 * ({@code docs/initfix/14-value-type-new-replay.md}).
 *
 * <p>Every scenario runs one JVM, loads the v1 fixture from the classpath, JIT-compiles the hot reader
 * (tier-3/4 compile lines are polled from the unified JIT log <b>before</b> any write), then drives the
 * production path: {@code InitFix.transform(v2 bytes + real ClassDiff)} -&gt; real
 * {@code Instrumentation.redefineClasses(v2)} -&gt; {@code InitFix.afterRedefine(host)}.</p>
 *
 * <pre>
 *   instance       property ON, instance field v: two live instances get one Vec(4,12) each (distinct)
 *   skip           property ON, live value was replaced through Unsafe: W2 preserves it (skipped)
 *   off            property OFF: negative baseline, no value-replay task and no write
 *   static         property ON + enhanced JBR: static final SV, two-phase stage A -> patch -> stage C
 *   static-plain   property ON, plain JBR: A4 rejects the static final target, no write
 * </pre>
 *
 * <p>Any mismatch throws, so the process exits non-zero. Stable readings are printed as {@code OBS}
 * lines; {@code run.sh} greps the reader compile lines and checks they precede the redefine marker.</p>
 */
public final class Probe {

    private static final Unsafe U = unsafe();
    private static final List<String> WARNS = new ArrayList<>();
    private static volatile long SINK;

    private static String mode;
    private static String jitLog;
    private static int checks;
    private static int failures;

    private Probe() {
    }

    public static void main(String[] args) throws Throwable {
        mode = args[0];
        byte[] hostV1 = Files.readAllBytes(Path.of(args[1]));
        byte[] hostV2 = Files.readAllBytes(Path.of(args[2]));
        byte[] staticV1 = Files.readAllBytes(Path.of(args[3]));
        byte[] staticV2 = Files.readAllBytes(Path.of(args[4]));
        jitLog = System.getProperty("vr.jitlog", "");

        installLogger();
        RemoldAgent.initConfig();

        out("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        out("enhanced", RemoldAgent.isEnhancedHotswapEnabled());
        out("agent", ProbeAgent.INST == null ? "MISSING" : "ok");
        if (ProbeAgent.INST == null) fail("probe must be started with -javaagent (no Instrumentation)");

        switch (mode) {
            case "instance" -> runInstance(hostV1, hostV2);
            case "skip" -> runSkip(hostV1, hostV2);
            case "off" -> runOff(hostV1, hostV2);
            case "static" -> runStatic(staticV1, staticV2);
            case "static-plain" -> runStaticPlain(staticV1, staticV2);
            default -> fail("unknown mode '" + mode + "' (expected instance|skip|off|static|static-plain)");
        }
        if (failures != 0) fail(failures + " assertion(s) failed");
        out("asserts", checks);
        out("done", "ok");
    }

    // ==================== scenario a: instance channel writes new value to live instances ====================

    static void runInstance(byte[] v1, byte[] v2) throws Throwable {
        Class<Host> host = Host.class;
        Field field = host.getDeclaredField("v");
        long offset = U.objectFieldOffset(field);
        out("host", host.getName());

        Host h1 = host.getDeclaredConstructor().newInstance();
        Host h2 = host.getDeclaredConstructor().newInstance();
        InstanceTracker.register(h1);
        InstanceTracker.register(h2);

        Vec old1 = (Vec) U.getReference(h1, offset);
        Vec old2 = (Vec) U.getReference(h2, offset);
        out("baseline.h1.v", old1);
        out("baseline.h2.v", old2);
        check(vecIs(old1, 4, 4) && vecIs(old2, 4, 4),
            "both live instances hold the old initializer Vec(4,4) before the patch");
        check(old1 != old2, "the two old values are distinct objects");

        warmReaderCompiled("read", () -> warmInstance(h1, 200_000L));
        int baseline1 = Reader.read(h1);
        int baseline2 = Reader.read(h2);
        out("baseline.reader.h1", baseline1);
        out("baseline.reader.h2", baseline2);
        check(baseline1 == 4 && baseline2 == 4,
            "compiled reader sees the old value through the live field (actual " + baseline1 + "/" + baseline2 + ")");

        HotswapBatchReport.InitFixStats stats = null;
        BatchReportScope.begin();
        try {
            InitFix.PatchReport planned = transform(host, v1, v2);
            expectDecision(planned, "valueReplay", "v", InitFix.FieldStatus.ACCEPTED);
            out("plan.valueReplay", planned.valueReplayFields());
            out("plan.patchGenerated", planned.patchGenerated());
            check(!planned.patchGenerated(), "a value-replay-only round must not claim the default init patch");
            check(!ledgerHas(host, "v"),
                "value replay must not write FieldLedger (actual " + InitFix.getUnpatchedFields(host) + ")");

            InitFix.PatchPlan plan = pendingPlan(host);
            check(plan != null, "transform stages a pending patch");
            check(plan != null && plan.valueReplayTasks().size() == 1,
                "exactly one value-replay task (actual " + (plan == null ? "no plan" : plan.valueReplayTasks()) + ")");
            InitFix.ValueReplayTask task = plan == null || plan.valueReplayTasks().isEmpty()
                ? null : plan.valueReplayTasks().get(0);
            out("plan.task", task);
            check(task != null && "v".equals(task.fieldName()) && !task.isStatic(),
                "the task targets the instance field v");
            check(plan != null && plan.allTasks().isEmpty(),
                "value-replay tasks stay out of the ledger-facing allTasks()");
            check(!InitFix.requiresTwoPhase(host),
                "the instance channel needs no deopt barrier (two-phase must stay off)");

            redefine(host, v2);
            out("redefine", "ok");
            int window = Reader.read(h1);
            out("window.reader", window);
            check(window == 4, "redefine alone does not change the live instance value (actual " + window + ")");
            out("window.raw", U.getReference(h1, offset));
            check(U.getReference(h1, offset) == old1,
                "the redefine does not touch the live field slot before apply");

            InitFix.afterRedefine(host);
            out("apply", "ok");
            stats = finishStats();
        } finally {
            if (BatchReportScope.isActive()) BatchReportScope.discard();
        }

        Vec new1 = (Vec) U.getReference(h1, offset);
        Vec new2 = (Vec) U.getReference(h2, offset);
        out("afterPatch.h1.v", new1);
        out("afterPatch.h2.v", new2);
        check(vecIs(new1, 4, 12), "first live instance now holds Vec(4,12) (actual " + new1 + ")");
        check(vecIs(new2, 4, 12), "second live instance now holds Vec(4,12) (actual " + new2 + ")");
        check(new1 != old1 && new2 != old2, "each old reference was replaced");
        check(new1 != new2, "W3 builds one new object per instance (identity must differ)");
        check(new Vec(4, 12).equals(new1) && new Vec(4, 12).equals(new2),
            "the replayed values are equal to the new initializer expression");

        int after1 = Reader.read(h1);
        int after2 = Reader.read(h2);
        out("afterPatch.reader.h1", after1);
        out("afterPatch.reader.h2", after2);
        check(after1 == 12 && after2 == 12,
            "the compiled reader sees the new value after the patch (actual " + after1 + "/" + after2 + ")");
        for (int i = 0; i < 5; i++) {
            out("afterPatch.reader.h1.sample" + i, Reader.read(h1));
            Thread.sleep(50L);
        }

        InitFix.PatchReport after = InitFix.getLastReport(host);
        expectDecision(after, "valueReplay", "v", InitFix.FieldStatus.ACCEPTED);
        expectStats(stats, 1, 0, 0, 0);

        // A0: the applied record must suppress a second plan for the same new initializer.
        InitFix.PatchReport again = transform(host, v1, v2);
        out("secondRound.valueReplay", again.valueReplayFields());
        check(again.valueReplayFields().isEmpty(),
            "the applied record suppresses a re-plan for the same new initializer (actual "
                + again.valueReplayFields() + ")");
        check(pendingPlan(host) == null, "the suppressed round stages no pending patch");
        out("secondRound.h1.v", U.getReference(h1, offset));
        check(U.getReference(h1, offset) == new1, "a suppressed round does not rewrite the value");
    }

    // ==================== scenario b: W2 preserves a runtime-modified value ====================

    static void runSkip(byte[] v1, byte[] v2) throws Throwable {
        Class<Host> host = Host.class;
        Field field = host.getDeclaredField("v");
        long offset = U.objectFieldOffset(field);

        Host h = host.getDeclaredConstructor().newInstance();
        InstanceTracker.register(h);
        Vec modified = new Vec(9, 9);
        U.putReference(h, offset, modified);

        Vec current = (Vec) U.getReference(h, offset);
        out("baseline.v", current);
        check(current == modified && vecIs(current, 9, 9),
            "precondition: the live value was replaced at runtime and holds Vec(9,9)");

        warmReaderCompiled("read", () -> warmInstance(h, 200_000L));
        int baseline = Reader.read(h);
        out("baseline.reader", baseline);
        check(baseline == 9, "compiled reader sees the runtime-modified value (actual " + baseline + ")");

        HotswapBatchReport.InitFixStats stats = null;
        BatchReportScope.begin();
        try {
            InitFix.PatchReport planned = transform(host, v1, v2);
            expectDecision(planned, "valueReplay", "v", InitFix.FieldStatus.ACCEPTED);

            redefine(host, v2);
            out("redefine", "ok");
            InitFix.afterRedefine(host);
            out("apply", "ok");
            stats = finishStats();
        } finally {
            if (BatchReportScope.isActive()) BatchReportScope.discard();
        }

        Vec afterFirst = (Vec) U.getReference(h, offset);
        out("afterPatch.v", afterFirst);
        check(afterFirst == modified && vecIs(afterFirst, 9, 9),
            "W2 skipped the runtime-modified value and preserved the exact reference");

        InitFix.PatchReport after = InitFix.getLastReport(host);
        InitFix.FieldDecision decision = expectDecision(after, "valueReplay", "v",
            InitFix.FieldStatus.NOTHING_TO_PATCH);
        out("report.reason", decision == null ? null : decision.reason());
        check(decision != null && decision.reason() != null && decision.reason().contains("skipped"),
            "the skip reason is visible in PatchReport.valueReplayFields");
        expectStats(stats, 0, 1, 0, 0);
        check(!ledgerHas(host, "v"),
            "skipped value replay must not write FieldLedger (actual " + InitFix.getUnpatchedFields(host) + ")");

        // skipped must not be remembered as applied: the same diff is planned again.
        InitFix.PatchReport again = transform(host, v1, v2);
        InitFix.FieldDecision replanned = again.valueReplayFields().get("v");
        out("secondRound.valueReplay", again.valueReplayFields());
        check(replanned != null && replanned.status() == InitFix.FieldStatus.ACCEPTED,
            "skipped is not an applied record: the second round plans the field again");
        check(pendingPlan(host) != null, "the second round stages a pending patch");

        redefine(host, v2);
        out("secondRound.redefine", "ok");
        InitFix.afterRedefine(host);
        Vec afterSecond = (Vec) U.getReference(h, offset);
        out("secondRound.v", afterSecond);
        check(afterSecond == modified && vecIs(afterSecond, 9, 9),
            "the second round still preserves the runtime-modified value");
    }

    // ==================== scenario c: property-off negative baseline ====================

    static void runOff(byte[] v1, byte[] v2) throws Throwable {
        Class<Host> host = Host.class;
        Field field = host.getDeclaredField("v");
        long offset = U.objectFieldOffset(field);

        Host h = host.getDeclaredConstructor().newInstance();
        InstanceTracker.register(h);
        Vec old = (Vec) U.getReference(h, offset);
        out("baseline.v", old);
        check(vecIs(old, 4, 4), "precondition: the live instance holds Vec(4,4)");

        warmReaderCompiled("read", () -> warmInstance(h, 200_000L));
        int baseline = Reader.read(h);
        out("baseline.reader", baseline);
        check(baseline == 4, "compiled reader sees the old value (actual " + baseline + ")");

        HotswapBatchReport.InitFixStats stats = null;
        BatchReportScope.begin();
        try {
            InitFix.PatchReport planned = transform(host, v1, v2);
            out("plan.valueReplay", planned.valueReplayFields());
            check(planned.valueReplayFields().isEmpty(),
                "property off plans no value replay (actual " + planned.valueReplayFields() + ")");
            check(!planned.patchGenerated(), "property off generates no patch");
            check(pendingPlan(host) == null, "property off stages no pending patch");

            redefine(host, v2);
            out("redefine", "ok");
            InitFix.afterRedefine(host);
            out("apply", "ok");
            stats = finishStats();
        } finally {
            if (BatchReportScope.isActive()) BatchReportScope.discard();
        }

        Vec after = (Vec) U.getReference(h, offset);
        out("afterPatch.v", after);
        check(after == old && vecIs(after, 4, 4),
            "property off leaves the live instance untouched (negative baseline)");
        int reader = Reader.read(h);
        out("afterPatch.reader", reader);
        check(reader == 4, "the compiled reader still sees Vec(4,4) with the property off");
        expectStats(stats, 0, 0, 0, 0);
    }

    // ==================== scenario d: static final on enhanced JBR (two-phase) ====================

    static void runStatic(byte[] v1, byte[] v2) throws Throwable {
        Class<StaticHost> host = StaticHost.class;
        boolean enhanced = RemoldAgent.isEnhancedHotswapEnabled();
        out("enhancedHotswap", enhanced);
        check(enhanced,
            "the static-final replay path requires an enhanced-redefinition JBR"
                + " (-XX:+AllowEnhancedClassRedefinition)");

        Vec oldSv = StaticHost.SV;
        out("baseline.SV", oldSv);
        check(vecIs(oldSv, 4, 4), "the static field starts at Vec(4,4) (actual " + oldSv + ")");

        warmReaderCompiled("readStatic", () -> warmStatic(200_000L));
        int baseline = Reader.readStatic();
        out("baseline.reader", baseline);
        check(baseline == 4, "compiled static reader sees the old value before the patch (actual " + baseline + ")");

        HotswapBatchReport.InitFixStats stats = null;
        BatchReportScope.begin();
        try {
            InitFix.PatchReport planned = transform(host, v1, v2);
            expectDecision(planned, "valueReplay", "SV", InitFix.FieldStatus.ACCEPTED);
            out("plan.valueReplay", planned.valueReplayFields());
            out("plan.patchGenerated", planned.patchGenerated());
            check(!planned.patchGenerated(), "a value-replay-only round keeps patchGenerated=false");

            InitFix.PatchPlan plan = pendingPlan(host);
            check(plan != null, "transform stages a pending patch");
            check(plan != null && plan.valueReplayTasks().size() == 1,
                "exactly one value-replay task (actual " + (plan == null ? "no plan" : plan.valueReplayTasks()) + ")");
            InitFix.ValueReplayTask task = plan == null || plan.valueReplayTasks().isEmpty()
                ? null : plan.valueReplayTasks().get(0);
            out("plan.task", task);
            check(task != null && "SV".equals(task.fieldName()) && task.isStatic(),
                "the task targets the static field SV");
            boolean twoPhase = InitFix.requiresTwoPhase(host);
            out("plan.twoPhaseRequired", twoPhase);
            check(twoPhase, "the static value-replay target must request the two-phase deopt barrier");

            // Production two-phase order: transform (analysis) -> stage A redefinition -> patch -> stage C.
            byte[] stageA = StageASkeleton.buildWithLiveConstructors(v1, v2);
            out("stageA.size", stageA.length);
            redefine(host, stageA);
            out("redefineStageA", "ok");

            int window = warmStaticSample(200_000L);
            out("window.reader", window);
            check(window == 4, "the reader still sees the old value before the patch write (actual " + window + ")");

            InitFix.afterRedefine(host);
            out("apply", "ok");
            out("afterPatch.SV", StaticHost.SV);
            check(vecIs(StaticHost.SV, 4, 12),
                "the patch wrote Vec(4,12) into the static slot (actual " + StaticHost.SV + ")");
            check(StaticHost.SV != oldSv, "the old static reference was replaced");
            int stale = Reader.readStatic();
            out("afterPatch.readerBeforeStageC", stale);
            check(stale == 4,
                "without stage C the already-compiled static reader is stale (folded old value),"
                    + " actual=" + stale);

            redefine(host, v2);
            out("redefineStageC", "ok");
            int after = warmStaticSample(2_000_000L);
            out("afterStageC.reader", after);
            check(after == 12,
                "the compiled reader must see Vec(4,12) after Stage C (actual " + after + ")");
            for (int i = 0; i < 5; i++) {
                out("afterStageC.reader.sample" + i, Reader.readStatic());
                Thread.sleep(50L);
            }
            check(Reader.readStaticVec().equals(new Vec(4, 12)),
                "the static slot holds an equal Vec(4,12) after stage C");

            InitFix.PatchReport afterReport = InitFix.getLastReport(host);
            expectDecision(afterReport, "valueReplay", "SV", InitFix.FieldStatus.ACCEPTED);
            stats = finishStats();
        } finally {
            if (BatchReportScope.isActive()) BatchReportScope.discard();
        }
        expectStats(stats, 1, 0, 0, 0);
    }

    // ==================== scenario e: static final on plain JBR is rejected (A4) ====================

    static void runStaticPlain(byte[] v1, byte[] v2) throws Throwable {
        Class<StaticHost> host = StaticHost.class;
        boolean enhanced = RemoldAgent.isEnhancedHotswapEnabled();
        out("enhancedHotswap", enhanced);
        check(!enhanced, "the rejection scenario must run WITHOUT enhanced class redefinition");

        Vec oldSv = StaticHost.SV;
        out("baseline.SV", oldSv);
        check(vecIs(oldSv, 4, 4), "the static field starts at Vec(4,4)");

        warmReaderCompiled("readStatic", () -> warmStatic(200_000L));
        int baseline = Reader.readStatic();
        out("baseline.reader", baseline);
        check(baseline == 4, "compiled static reader sees the old value (actual " + baseline + ")");

        HotswapBatchReport.InitFixStats stats = null;
        BatchReportScope.begin();
        try {
            InitFix.PatchReport planned = transform(host, v1, v2);
            InitFix.FieldDecision decision = expectDecision(planned, "valueReplay", "SV",
                InitFix.FieldStatus.REJECTED);
            out("plan.reason", decision == null ? null : decision.reason());
            check(decision != null && decision.reason() != null
                    && decision.reason().contains("VALUE-REPLAY-STATIC-FINAL-NEEDS-ENHANCED"),
                "the rejection carries VALUE-REPLAY-STATIC-FINAL-NEEDS-ENHANCED");
            InitFix.FieldDecision staticDecision = planned.staticFields().get("SV");
            out("plan.staticDecision", staticDecision);
            check(staticDecision != null && staticDecision.status() == InitFix.FieldStatus.REJECTED,
                "the reject also surfaces through the unified static decision table");
            check(!planned.patchGenerated(), "a rejected static-final target generates no patch");
            check(pendingPlan(host) == null, "a rejected static-final target stages no pending patch");

            boolean warned = false;
            synchronized (WARNS) {
                for (String w : WARNS) {
                    if (w.contains("VALUE-REPLAY-STATIC-FINAL-NEEDS-ENHANCED")) warned = true;
                }
            }
            out("plan.warnVisible", warned);
            check(warned, "the rejection goes through the unified warn exit (VALUE-REPLAY-STATIC-FINAL-NEEDS-ENHANCED)");

            redefine(host, v2);
            out("redefine", "ok");
            InitFix.afterRedefine(host);
            out("apply", "ok");
            stats = finishStats();
        } finally {
            if (BatchReportScope.isActive()) BatchReportScope.discard();
        }

        out("afterPatch.SV", StaticHost.SV);
        check(StaticHost.SV == oldSv && vecIs(StaticHost.SV, 4, 4),
            "a rejected static-final target must not be written");
        int reader = Reader.readStatic();
        out("afterPatch.reader", reader);
        check(reader == 4, "the reader still sees Vec(4,4) after the rejected round (actual " + reader + ")");
        expectStats(stats, 0, 0, 0, 1);
    }

    // ==================== shared production-path helpers ====================

    static InitFix.PatchReport transform(Class<?> host, byte[] v1, byte[] v2) {
        RemoldAgent.bytecodeCache.put(host.getName(), v2);
        ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(v1, v2);
        InitFix.transform(host, v2, diff);
        InitFix.PatchReport report = InitFix.getLastReport(host);
        check(report != null, "InitFix.transform produced a PatchReport for " + host.getName());
        return report;
    }

    static void redefine(Class<?> host, byte[] bytes) {
        try {
            ProbeAgent.INST.redefineClasses(new ClassDefinition(host, bytes));
        } catch (Throwable t) {
            fail("Instrumentation.redefineClasses failed for " + host.getName() + ": " + t);
        }
    }

    static InitFix.PatchPlan pendingPlan(Class<?> host) {
        try {
            Method m = InitFix.class.getDeclaredMethod("pendingPlanForTest", Class.class);
            m.setAccessible(true);
            return (InitFix.PatchPlan) m.invoke(null, host);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("InitFix.pendingPlanForTest shape changed", e);
        }
    }

    static HotswapBatchReport.InitFixStats finishStats() {
        try {
            Method m = BatchReportScope.class.getDeclaredMethod("finish", List.class);
            m.setAccessible(true);
            HotswapBatchReport report = (HotswapBatchReport) m.invoke(null, List.of());
            check(report != null, "batch report scope produced a report");
            return report == null ? null : report.getInitFixStats();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("BatchReportScope.finish shape changed", e);
        }
    }

    static InitFix.FieldDecision expectDecision(InitFix.PatchReport report, String table,
                                                String field, InitFix.FieldStatus want) {
        if (report == null) {
            fail("no PatchReport while checking " + table + "." + field);
            return null;
        }
        var decisions = "valueReplay".equals(table) ? report.valueReplayFields()
            : "instance".equals(table) ? report.instanceFields() : report.staticFields();
        InitFix.FieldDecision decision = decisions.get(field);
        out("decision." + table + "." + field, decision);
        check(decision != null && decision.status() == want,
            table + "." + field + " must be " + want + " (actual " + decision + ")");
        return decision;
    }

    static void expectStats(HotswapBatchReport.InitFixStats stats, int applied, int skipped,
                            int failed, int rejected) {
        if (stats == null) {
            fail("no InitFixStats (batch report scope missing?)");
            return;
        }
        out("stats.applied", stats.getValueReplayApplied());
        out("stats.skipped", stats.getValueReplaySkipped());
        out("stats.failed", stats.getValueReplayFailed());
        out("stats.rejected", stats.getValueReplayRejected());
        check(stats.getValueReplayApplied() == applied,
            "InitFixStats valueReplayApplied == " + applied + " (actual " + stats.getValueReplayApplied() + ")");
        check(stats.getValueReplaySkipped() == skipped,
            "InitFixStats valueReplaySkipped == " + skipped + " (actual " + stats.getValueReplaySkipped() + ")");
        check(stats.getValueReplayFailed() == failed,
            "InitFixStats valueReplayFailed == " + failed + " (actual " + stats.getValueReplayFailed() + ")");
        check(stats.getValueReplayRejected() == rejected,
            "InitFixStats valueReplayRejected == " + rejected + " (actual " + stats.getValueReplayRejected() + ")");
    }

    // ==================== JIT compile evidence ====================

    /**
     * Warms the reader and polls the unified JIT log for a C2 (tier 4) compile line of the method.
     * Fails when the line never appears: the visibility assertions below must run against compiled code.
     */
    static String warmReaderCompiled(String method, Runnable warmer) throws Exception {
        String owner = "probe.Reader::" + method + " (";
        if (jitLog == null || jitLog.isEmpty()) fail("missing -Dvr.jitlog=<unified JIT log file>");
        Path path = Path.of(jitLog);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(30_000L);
        while (System.nanoTime() < deadline) {
            String c2 = findCompiledLine(path, owner, "4");
            if (c2 != null) {
                String c1 = findCompiledLine(path, owner, "3");
                if (c1 == null) c1 = findCompiledLine(path, owner, "2");
                out("warm.readerCompile.c1", c1 == null ? "n/a" : c1);
                out("warm.readerCompile.c2", c2);
                check(true, "reader method probe.Reader::" + method + " reached C2 before the write");
                return c2;
            }
            warmer.run();
            Thread.sleep(50L);
        }
        fail("no tier-4 JIT compile line for " + owner + " within 30s (jit log " + jitLog + ")");
        return null;
    }

    static String findCompiledLine(Path path, String needle, String tier) {
        List<String> lines = readLog(path);
        for (String line : lines) {
            if (line.contains("made not entrant")) continue;
            if (isTierLine(line, needle, tier)) return line;
        }
        return null;
    }

    static boolean isTierLine(String line, String needle, String tier) {
        int idx = line.indexOf(needle);
        if (idx <= 0) return false;
        String prefix = line.substring(0, idx);
        return prefix.matches("(?s).*\\s" + tier + "\\s+$");
    }

    static List<String> readLog(Path path) {
        try {
            if (!Files.isRegularFile(path)) return List.of();
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    // ==================== fixtures / assertions ====================

    static int warmInstance(Host h, long iterations) {
        long sink = 0L;
        int last = 0;
        for (long i = 0; i < iterations; i++) {
            last = Reader.read(h);
            sink += last;
        }
        SINK = sink;
        return last;
    }

    static int warmStatic(long iterations) {
        long sink = 0L;
        int last = 0;
        for (long i = 0; i < iterations; i++) {
            last = Reader.readStatic();
            sink += last;
        }
        SINK = sink;
        return last;
    }

    static int warmStaticSample(long iterations) {
        return warmStatic(iterations);
    }

    static boolean vecIs(Object value, int x, int y) {
        return value instanceof Vec vec && vec.x() == x && vec.y() == y;
    }

    static boolean ledgerHas(Class<?> host, String field) {
        for (InitFix.UnpatchedField unpatched : InitFix.getUnpatchedFields(host)) {
            if (field.equals(unpatched.fieldName())) return true;
        }
        return false;
    }

    static void check(boolean ok, String message) {
        checks++;
        if (ok) {
            out("check." + checks, "PASS: " + message);
        } else {
            failures++;
            out("check." + checks, "FAIL: " + message);
            fail(message);
        }
    }

    static void fail(String message) {
        out("FAILURE", message);
        throw new IllegalStateException("VALUE-REPLAY PROBE FAILURE: " + message);
    }

    static void out(String key, Object value) {
        System.out.println("OBS " + mode + "." + key + " = " + value);
    }

    static void installLogger() {
        RemoldAgent.logger = new RemoldAgent.Logger() {
            @Override
            public void log(String msg) {
            }

            @Override
            public void info(String msg) {
            }

            @Override
            public void warn(String msg) {
                synchronized (WARNS) {
                    WARNS.add(msg);
                }
            }

            @Override
            public void error(String msg) {
                synchronized (WARNS) {
                    WARNS.add("ERROR " + msg);
                }
            }

            @Override
            public void error(String msg, Throwable t) {
                synchronized (WARNS) {
                    WARNS.add("ERROR " + msg + " :: " + t);
                }
            }
        };
    }

    static Unsafe unsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }
}
