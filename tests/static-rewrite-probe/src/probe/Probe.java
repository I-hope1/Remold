package probe;

import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jdk.internal.misc.Unsafe;
import remold.ClassDiffUtil;
import remold.RemoldAgent;
import remold.ref.InitFix;
import test.Holder;

/**
 * A/B probe for rewriting an EXISTING static final reference field (runtime-initialized, so it has no
 * ConstantValue attribute):
 *
 * <pre>
 *   single: redefine v2 -> patch (Unsafe write VAL)            (single-phase pipeline order)
 *   two:    redefine stage A -> patch -> redefine stage C      (two-phase pipeline order)
 *   gate:   InitFix.transform on GateHolder (enhanced vs plain) -> patch / fail closed
 * </pre>
 *
 * Both redefine modes warm the readers BEFORE the redefine and warm again after stage A/redefine, so the
 * compiled readers are produced inside the "patch not yet landed" window; then the field is written
 * and the readers are sampled. Expected: single keeps the old value (stale JIT), two refreshes it
 * because stage C redefines the host after the write.
 *
 * <p>{@code gate} drives the production {@link InitFix#transform} path on a runtime-initialized
 * {@code static final} rewrite ({@code @RemoldReinit(OVERWRITE)}). On an enhanced JBR the rewrite must be
 * accepted and applied; on a plain JVM (no {@code -XX:+AllowEnhancedClassRedefinition}) it must be
 * rejected per field, no patch task emitted, and the warn must name the flag — without enhanced class
 * redefinition a compiled cross-class reader can keep the folded old value.</p>
 */
public final class Probe {

    static final Unsafe U = unsafe();

    private Probe() {
    }

    public static void main(String[] args) throws Throwable {
        String mode = args[0];
        byte[] v1 = Files.readAllBytes(Path.of(args[1]));
        byte[] v2 = Files.readAllBytes(Path.of(args[2]));
        out("mode", mode);
        if ("gate".equals(mode)) {
            runGate(v1, v2);
            return;
        }
        out("agent", ProbeAgent.INST == null ? "MISSING" : "ok");
        out("baseline.tag", hotTag());
        out("baseline.read", hotRead());
        out("baseline.rawVal", rawVal());

        long sink = warm(3_000_000L);
        out("warm.sink", sink);
        out("warm.tag", hotTag());
        out("warm.read", hotRead());

        if ("single".equals(mode)) {
            ProbeAgent.INST.redefineClasses(new ClassDefinition(Holder.class, v2));
            out("single.afterRedefine.tag", hotTag());
            sink = warm(2_000_000L);
            out("single.window.sink", sink);
            out("single.window.tag", hotTag());
            out("single.window.read", hotRead());
            writeNew();
            out("single.afterPatch.rawVal", rawVal());
            for (int i = 0; i < 5; i++) {
                out("single.afterPatch.read.sample" + i, hotRead());
                Thread.sleep(100);
            }
            out("single.afterPatch.tag", hotTag());
            out("single.afterPatch.reflectRead", reflectRead());
        } else if ("two".equals(mode)) {
            byte[] stageA = remold.ref.StageASkeleton.buildWithLiveConstructors(v1, v2);
            out("two.stageA.size", stageA.length);
            ProbeAgent.INST.redefineClasses(new ClassDefinition(Holder.class, stageA));
            out("two.afterStageA.tag", hotTag());
            sink = warm(2_000_000L);
            out("two.window.sink", sink);
            out("two.window.tag", hotTag());
            out("two.window.read", hotRead());
            writeNew();
            out("two.afterPatch.rawVal", rawVal());
            out("two.beforeStageC.read", hotRead());
            ProbeAgent.INST.redefineClasses(new ClassDefinition(Holder.class, v2));
            out("two.afterStageC.tag", hotTag());
            for (int i = 0; i < 5; i++) {
                out("two.afterStageC.read.sample" + i, hotRead());
                Thread.sleep(100);
            }
            out("two.afterStageC.reflectRead", reflectRead());
        } else {
            throw new IllegalArgumentException("mode must be single|two|gate: " + mode);
        }
        out("done", "ok");
    }

    /**
     * Drive the production InitFix path for rewriting an EXISTING static final reference field.
     *
     * <p>Expected on a plain JVM: REJECTED per field, no patch, warn naming the flag, no write.
     * Expected on JBR enhanced ({@code -XX:+AllowEnhancedClassRedefinition}): ACCEPTED, patch generated,
     * value rewritten. Any mismatch throws, so the run exits non-zero.</p>
     */
    static void runGate(byte[] v1, byte[] v2) throws Throwable {
        String name = "test.GateHolder";
        Class<?> host = Class.forName(name, true, new GateClassLoader(v2));
        Field msg = host.getDeclaredField("MSG");
        long off = U.staticFieldOffset(msg);
        Object base = U.staticFieldBase(msg);
        U.putReference(base, off, new StringBuilder("stale"));

        boolean enhanced = RemoldAgent.isEnhancedHotswapEnabled();
        out("gate.enhanced", enhanced);
        out("gate.pre.transform", gateMsg(host));
        expect("stale".equals(gateMsg(host)),
            "precondition: static environment holds the old runtime value, actual=" + gateMsg(host));

        RemoldAgent.initConfig();
        RemoldAgent.bytecodeCache.put(name, v2);

        List<String> warns = new ArrayList<>();
        RemoldAgent.Logger previous = RemoldAgent.logger;
        RemoldAgent.logger = new RemoldAgent.Logger() {
            public void log(String msg) {
            }

            public void info(String msg) {
            }

            public void warn(String msg) {
                warns.add(msg);
            }

            public void error(String msg) {
            }

            public void error(String msg, Throwable t) {
            }
        };
        InitFix.PatchReport report;
        try {
            ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(v1, v2);
            InitFix.transform(host, v2, diff);
            report = InitFix.getLastReport(host);
        } finally {
            RemoldAgent.logger = previous;
        }
        expect(report != null, "InitFix produced a PatchReport");

        InitFix.FieldDecision decision = report.staticFields().get("MSG");
        String status = decision == null ? "MISSING" : String.valueOf(decision.status());
        String reason = decision == null ? "" : decision.reason();
        boolean warnNamesFlag = warns.stream()
            .anyMatch(w -> w.contains("-XX:+AllowEnhancedClassRedefinition"));

        out("gate.status", status);
        out("gate.reason", reason);
        out("gate.patchGenerated", report.patchGenerated());
        out("gate.warnNamesFlag", warnNamesFlag);

        if (enhanced) {
            expect("ACCEPTED".equals(status), "enhanced runtime must accept the rewrite, status=" + status);
            expect(report.patchGenerated(), "enhanced runtime must generate the patch");
        } else {
            expect("REJECTED".equals(status), "plain runtime must reject the rewrite, status=" + status);
            expect(reason.contains("enhanced") && reason.contains("-XX:+AllowEnhancedClassRedefinition"),
                "plain runtime reason must name enhanced class redefinition, reason=" + reason);
            expect(!report.patchGenerated(), "plain runtime must not emit a patch task");
            expect(warnNamesFlag, "plain runtime warn must name -XX:+AllowEnhancedClassRedefinition, warns=" + warns);
        }

        InitFix.afterRedefine(host);
        out("gate.afterApply", gateMsg(host));
        if (enhanced) {
            expect("new".equals(gateMsg(host)),
                "enhanced runtime must rewrite the value to new, actual=" + gateMsg(host));
        } else {
            expect("stale".equals(gateMsg(host)),
                "plain runtime must not rewrite the value, actual=" + gateMsg(host));
        }
        out("gate.done", "ok");
    }

    static String gateMsg(Class<?> host) throws Throwable {
        Field f = host.getDeclaredField("MSG");
        f.setAccessible(true);
        Object value = f.get(null);
        return value == null ? null : value.toString();
    }

    static void expect(boolean ok, String message) {
        if (!ok) throw new IllegalStateException("GATE ASSERTION FAILED: " + message);
        out("gate.assert.ok", message);
    }

    /** Defines {@code test.GateHolder} from the v2 bytes so the transform analyses the new version. */
    static final class GateClassLoader extends ClassLoader {
        private final byte[] bytes;

        GateClassLoader(byte[] bytes) {
            super(Probe.class.getClassLoader());
            this.bytes = bytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!"test.GateHolder".equals(name)) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    static long warm(long iterations) {
        long sink = 0;
        for (long i = 0; i < iterations; i++) {
            sink += String.valueOf(hotRead()).length();
            sink += hotTag().length();
        }
        return sink;
    }

    static Object hotRead() {
        return reader.Reader.read();
    }

    static String hotTag() {
        return reader.Reader.tag();
    }

    static Object reflectRead() throws Throwable {
        return reader.Reader.class.getMethod("read").invoke(null);
    }

    static Object rawVal() {
        try {
            Field f = Holder.class.getDeclaredField("VAL");
            return U.getReference(U.staticFieldBase(f), U.staticFieldOffset(f));
        } catch (Throwable t) {
            return "ERR " + t;
        }
    }

    static void writeNew() {
        try {
            Field f = Holder.class.getDeclaredField("VAL");
            U.putReference(U.staticFieldBase(f), U.staticFieldOffset(f), new String("NEW"));
        } catch (Throwable t) {
            throw new IllegalStateException("Unsafe write failed", t);
        }
    }

    static void out(String key, Object value) {
        System.out.println("OBS " + key + " = " + value);
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
