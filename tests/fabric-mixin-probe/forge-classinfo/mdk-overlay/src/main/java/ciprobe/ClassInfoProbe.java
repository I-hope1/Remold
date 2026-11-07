package ciprobe;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Forge-side ClassInfo member-sync probe, run inside a real Forge 1.21.1 dedicated server.
 *
 * <p><b>What it measures</b> — every check is a per-call <em>delta</em> on a live instance, so a
 * class that silently lost its woven members can never pass on a cumulative counter:</p>
 * <ol>
 *   <li>{@code CI-BASE} — baseline precondition: the target really is woven (one call invokes the
 *       Mixin v1 {@code @Inject} handler exactly once and the {@code @Unique} field reads 123).</li>
 *   <li>{@code CI-M1} — stage 1: after template 1 gains a <b>new {@code @Inject} handler</b> and is
 *       hot-swapped, a call invokes that handler.</li>
 *   <li>{@code CI-M2} — stage 2: after template 2 gains a <b>new {@code @Unique} field</b>, the
 *       re-woven target reads the field back with its initializer value (456).</li>
 *   <li>{@code CI-PRESERVE-1/2} — the pre-existing handlers still fire exactly once per call and
 *       the pre-existing {@code @Unique} field still reads 123 (the hot update must not drop the
 *       original members).</li>
 * </ol>
 *
 * <p>The probe only reports facts as {@code [CI] CHECK <id> PASS|FAIL <detail>} lines. Whether a
 * run is <em>attributable</em> to the ClassInfo-sync mechanism is decided by {@code Verdict.java},
 * which additionally requires the driver's per-stage reload observation
 * ({@code [CI-DRIVER] phase=stageN reload=success|rejected|weave-failed|not-attempted}): "no
 * reload was even attempted" is never reported as a red result.</p>
 */
public final class ClassInfoProbe {
    private static final PrintStream OUT = System.out;
    private static final CountDownLatch SERVER_STARTED = new CountDownLatch(1);

    private ClassInfoProbe() {
    }

    public static void start() {
        Thread t = new Thread(ClassInfoProbe::run, "ci-probe");
        t.setDaemon(true);
        t.start();
    }

    public static void serverStarted() {
        SERVER_STARTED.countDown();
    }

    private static void run() {
        Path control = Paths.get(System.getProperty("remold.probe.classinfo.control", "ci-control")).toAbsolutePath();
        long settleSeconds = Long.getLong("remold.probe.classinfo.settle_s", 60L);
        out("PROBE-VERSION=forge-classinfo/1 control=" + control);
        try {
            if (!SERVER_STARTED.await(300, TimeUnit.SECONDS)) {
                out("SERVER-STARTED-TIMEOUT");
                done(3);
                return;
            }
            out("SERVER-STARTED");

            registerMixinConfig();

            Class<?> target = Class.forName("ciprobe.ClassInfoTarget", true, ClassInfoProbe.class.getClassLoader());
            Object instance = target.getConstructor().newInstance();
            Method onReload = target.getMethod("onReload");

            // ---- baseline: the target really is woven by Mixin v1 -------------------------
            Call base = call(onReload, instance, target);
            out("BASELINE " + base);
            check("CI-BASE", base.d1 == 1 && base.d2 == 0 && base.uniqueSeen1 == 123, base.toString());

            // ---- stage 1: new @Inject handler method on template 1 ------------------------
            out("NEXT stage1");
            if (!awaitTrigger(control, "stage1", 300)) {
                check("CI-M1", false, "trigger-missing");
                check("CI-PRESERVE-1", false, "trigger-missing");
            } else {
                Call last = settle(onReload, instance, target, settleSeconds, c -> c.d2 > 0);
                out("STAGE1 " + last);
                check("CI-M1", last.d2 > 0, "new-handler-per-call-delta=" + last.d2 + " err=" + last.err);
                check("CI-PRESERVE-1", last.d1 == 1 && last.uniqueSeen1 == 123,
                    "baseline-handler-per-call-delta=" + last.d1 + " uniqueSeen1=" + last.uniqueSeen1);
            }

            // ---- stage 2: new @Unique field on template 2 --------------------------------
            out("NEXT stage2");
            if (!awaitTrigger(control, "stage2", 300)) {
                check("CI-M2", false, "trigger-missing");
                check("CI-PRESERVE-2", false, "trigger-missing");
            } else {
                Call last = settle(onReload, instance, target, settleSeconds, c -> c.uniqueSeen3 == 456);
                out("STAGE2 " + last);
                check("CI-M2", last.uniqueSeen3 == 456,
                    "new-unique-field-value=" + last.uniqueSeen3 + " err=" + last.err);
                check("CI-PRESERVE-2", last.d1 == 1 && last.d3 == 1 && last.uniqueSeen1 == 123,
                    "template1-handler-delta=" + last.d1 + " template2-handler-delta=" + last.d3
                        + " uniqueSeen1=" + last.uniqueSeen1);
            }

            out("RESULT-PROBE-DONE");
            done(0);
        } catch (Throwable t) {
            out("PROBE-ERROR " + describe(t));
            out("RESULT-PROBE-DONE");
            done(4);
        }
    }

    /** FG7 dev runs classpath mods without a jar manifest, so register the config by hand. */
    private static void registerMixinConfig() {
        try {
            Class<?> envType = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment");
            Object env = envType.getMethod("getDefaultEnvironment").invoke(null);
            envType.getMethod("addConfiguration", String.class).invoke(env, "ci.mixins.json");
            out("MIXIN-CONFIG registered ci.mixins.json");
        } catch (Throwable t) {
            out("MIXIN-CONFIG-FAILED " + describe(t));
        }
    }

    /** One target invocation, with the per-field deltas it produced. */
    private static final class Call {
        int d1;
        int d2;
        int d3;
        int uniqueSeen1 = -1;
        int uniqueSeen3 = -1;
        String err = "";

        @Override
        public String toString() {
            return "d1=" + d1 + " d2=" + d2 + " d3=" + d3
                + " uniqueSeen1=" + uniqueSeen1 + " uniqueSeen3=" + uniqueSeen3
                + " err=" + (err.isEmpty() ? "none" : err);
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
    private static Call settle(Method onReload, Object instance, Class<?> target, long settleSeconds, Stop stop)
        throws InterruptedException {
        Call last = call(onReload, instance, target);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settleSeconds);
        while (!stop.reached(last) && System.nanoTime() < deadline) {
            Thread.sleep(250L);
            last = call(onReload, instance, target);
        }
        return last;
    }

    private static Call call(Method onReload, Object instance, Class<?> target) {
        int h1 = stat(target, "hits1");
        int h2 = stat(target, "hits2");
        int h3 = stat(target, "hits3");
        String err = callOnce(onReload, instance);
        Call call = new Call();
        call.d1 = stat(target, "hits1") - h1;
        call.d2 = stat(target, "hits2") - h2;
        call.d3 = stat(target, "hits3") - h3;
        call.uniqueSeen1 = stat(target, "uniqueSeen1");
        call.uniqueSeen3 = stat(target, "uniqueSeen3");
        call.err = err;
        return call;
    }

    private static String callOnce(Method onReload, Object instance) {
        try {
            onReload.invoke(instance);
            return "";
        } catch (Throwable t) {
            return describe(t);
        }
    }

    private static int stat(Class<?> target, String name) {
        try {
            Field f = target.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean awaitTrigger(Path control, String phase, long seconds) throws InterruptedException {
        Path trigger = control.resolve(phase + ".go");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (Files.exists(trigger)) {
                return true;
            }
            Thread.sleep(200L);
        }
        return false;
    }

    private static void check(String id, boolean pass, String detail) {
        OUT.println("[CI] CHECK " + id + " " + (pass ? "PASS" : "FAIL") + " " + detail);
        OUT.flush();
    }

    private static void out(String message) {
        OUT.println("[CI] " + message);
        OUT.flush();
    }

    private static void done(int code) {
        OUT.println("[CI] HALT rc=" + code);
        OUT.flush();
        Runtime.getRuntime().halt(code);
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
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
