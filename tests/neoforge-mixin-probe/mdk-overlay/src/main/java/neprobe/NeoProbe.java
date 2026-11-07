package neprobe;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * NeoForge-side Mixin template hot-update probe, run inside a real NeoForge 21.1 dev dedicated
 * server ({@code runServer}, ModDevGradle).
 *
 * <p><b>What it measures</b> — every check is a per-call <em>delta</em> or a field value read from
 * the live target instance, so a target that silently lost its woven members can never pass on a
 * cumulative counter:</p>
 * <ol>
 *   <li>{@code NP-BASE} — baseline precondition: the target really is woven (one call invokes the
 *       Mixin v1 {@code @Inject} handler exactly once, the {@code @Unique uniqueA} field reads 123,
 *       and {@code onReload()} still returns its source value {@code T0}).</li>
 *   <li>{@code NP-M1} — stage 1: after template 1 gains a <b>new {@code @Inject} handler</b> and is
 *       hot-swapped, a call invokes that handler.</li>
 *   <li>{@code NP-M2} — stage 2: after template 2 gains a <b>new {@code @Unique} field</b>, the
 *       re-woven target reads the field back with its initializer value (456).</li>
 *   <li>{@code NP-PRESERVE-1/2} — the pre-existing handlers still fire exactly once per call and
 *       the pre-existing {@code @Unique} field still reads 123 (the hot update must not drop the
 *       original members).</li>
 * </ol>
 *
 * <p>The probe only reports facts as {@code [NP] CHECK <id> PASS|FAIL <detail>} lines. Whether a
 * run is <em>attributable</em> to the ClassInfo-sync mechanism is decided by {@code Verdict.java},
 * which additionally requires the driver's per-stage reload observation
 * ({@code [NP-DRIVER] phase=stageN reload=...}) plus the absence of Mixin-registration shortcuts
 * and metadata-sync degradation markers: "no reload was even attempted" is never a red result.</p>
 *
 * <p>This class must not touch {@code org.spongepowered.asm.*} at runtime: in NeoForge the Mixin
 * module lives in a different loader/layer, and reaching into it from here would both fail and
 * muddy the attribution. Observation channels are the target class's own static fields.</p>
 */
public final class NeoProbe {
    private static final PrintStream OUT = System.out;

    /** System property (set by the MDK run configuration) pointing at the trigger directory. */
    private static final String CONTROL_PROP = "remold.probe.neoforge.control";

    private NeoProbe() {
    }

    public static void start() {
        Thread t = new Thread(NeoProbe::run, "np-probe");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        Path control = Paths.get(System.getProperty(CONTROL_PROP, "np-control")).toAbsolutePath();
        // The dev server is "up" only after the driver sees the vanilla "Done" line; the driver
        // then drops probe.start. Waiting for that file (instead of an event) keeps the probe free
        // of game/event API imports, so a fixture failure can only come from the Mixin path.
        long startSeconds = Long.getLong("remold.probe.neoforge.start_s", 600L);
        long settleSeconds = Long.getLong("remold.probe.neoforge.settle_s", 90L);
        out("PROBE-VERSION=neoforge-mixin-probe/1 control=" + control);
        try {
            if (!awaitFile(control.resolve("probe.start"), startSeconds)) {
                out("PROBE-START-TIMEOUT waited_s=" + startSeconds);
                out("RESULT-PROBE-DONE");
                done(3);
                return;
            }
            out("PROBE-START");

            ProbeTarget target = new ProbeTarget();

            // ---- baseline: the target really is woven by Mixin v1 -------------------------
            Call base = call(target);
            out("BASELINE " + base);
            check("NP-BASE", base.d1 == 1 && base.d2 == 0 && base.seen1 == 123 && "T0".equals(base.ret),
                base.toString());

            // ---- stage 1: new @Inject handler method on template 1 ------------------------
            out("NEXT stage1");
            if (!awaitTrigger(control, "stage1", 600)) {
                check("NP-M1", false, "trigger-missing");
                check("NP-PRESERVE-1", false, "trigger-missing");
            } else {
                Call last = settle(target, settleSeconds, c -> c.d2 > 0);
                out("STAGE1 " + last);
                check("NP-M1", last.d2 > 0, "new-handler-per-call-delta=" + last.d2 + " err=" + last.err);
                check("NP-PRESERVE-1", last.d1 == 1 && last.seen1 == 123,
                    "baseline-handler-per-call-delta=" + last.d1 + " seen1=" + last.seen1
                        + " err=" + last.err);
            }

            // ---- stage 2: new @Unique field on template 2 --------------------------------
            out("NEXT stage2");
            if (!awaitTrigger(control, "stage2", 600)) {
                check("NP-M2", false, "trigger-missing");
                check("NP-PRESERVE-2", false, "trigger-missing");
            } else {
                Call last = settle(target, settleSeconds, c -> c.seen3 == 456);
                out("STAGE2 " + last);
                check("NP-M2", last.seen3 == 456,
                    "new-unique-field-value=" + last.seen3 + " err=" + last.err);
                check("NP-PRESERVE-2", last.d1 == 1 && last.d3 == 1 && last.seen1 == 123,
                    "template1-handler-delta=" + last.d1 + " template2-handler-delta=" + last.d3
                        + " seen1=" + last.seen1 + " err=" + last.err);
            }

            out("RESULT-PROBE-DONE");
            done(0);
        } catch (Throwable t) {
            out("PROBE-ERROR " + describe(t));
            out("RESULT-PROBE-DONE");
            done(4);
        }
    }

    /** One target invocation, with the per-field deltas and field values it produced. */
    private static final class Call {
        int d1;
        int d2;
        int d3;
        int seen1 = -1;
        int seen3 = -1;
        String ret = "";
        String err = "";

        @Override
        public String toString() {
            return "d1=" + d1 + " d2=" + d2 + " d3=" + d3 + " seen1=" + seen1 + " seen3=" + seen3
                + " ret=" + (ret.isEmpty() ? "none" : ret) + " err=" + (err.isEmpty() ? "none" : err);
        }
    }

    private interface Stop {
        boolean reached(Call call);
    }

    /**
     * Invokes the target method until {@code stop} is satisfied or the settle window expires, and
     * returns the last call. The end state (delta / field value) decides pass/fail; an exception is
     * recorded as diagnostics only, because a rejected re-weave can also surface as a thrown
     * {@code NoSuchFieldError} on an older weave.
     */
    private static Call settle(ProbeTarget target, long settleSeconds, Stop stop) throws InterruptedException {
        Call last = call(target);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settleSeconds);
        while (!stop.reached(last) && System.nanoTime() < deadline) {
            Thread.sleep(250L);
            last = call(target);
        }
        return last;
    }

    private static Call call(ProbeTarget target) {
        int h1 = ProbeTarget.hits1;
        int h2 = ProbeTarget.hits2;
        int h3 = ProbeTarget.hits3;
        Call call = new Call();
        try {
            call.ret = target.onReload();
        } catch (Throwable t) {
            call.err = describe(t);
        }
        call.d1 = ProbeTarget.hits1 - h1;
        call.d2 = ProbeTarget.hits2 - h2;
        call.d3 = ProbeTarget.hits3 - h3;
        call.seen1 = ProbeTarget.seen1;
        call.seen3 = ProbeTarget.seen3;
        return call;
    }

    /** Dual-path trigger poll: {@code <control>/<stage>.go} and {@code <control>/run/<stage>.go}. */
    private static boolean awaitTrigger(Path control, String phase, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        Path direct = control.resolve(phase + ".go");
        Path nested = control.resolve("run").resolve(phase + ".go");
        while (System.nanoTime() < deadline) {
            if (Files.exists(direct) || Files.exists(nested)) {
                return true;
            }
            Thread.sleep(200L);
        }
        return false;
    }

    private static boolean awaitFile(Path file, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (Files.exists(file)) {
                return true;
            }
            Thread.sleep(200L);
        }
        return false;
    }

    private static void check(String id, boolean pass, String detail) {
        OUT.println("[NP] CHECK " + id + " " + (pass ? "PASS" : "FAIL") + " " + detail);
        OUT.flush();
    }

    private static void out(String message) {
        OUT.println("[NP] " + message);
        OUT.flush();
    }

    private static void done(int code) {
        OUT.println("[NP] HALT rc=" + code);
        OUT.flush();
        Runtime.getRuntime().halt(code);
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 4 && seen.add(cur)) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(cur.getClass().getSimpleName());
            if (cur.getMessage() != null) {
                sb.append(": ").append(cur.getMessage().replace('\n', ' '));
            }
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }
}
