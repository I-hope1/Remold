package com.example.probe;

import net.fabricmc.api.ModInitializer;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Item 2.6: {@code @Inject} SELF-evolution on a real Fabric 1.21.1 KnotServer.
 *
 * <p>Three scenarios, each driven by a real {@code ./gradlew compileJava} + Remold
 * hot swap (the Mixin class file changes, {@code remold.mixin.MixinHotSwap} reloads the
 * Mixin and re-weaves the target class through JBR structural redefine):</p>
 *
 * <ul>
 *   <li><b>A cancellable evolution</b>: {@code @Inject} v1 is non-cancellable, v2 adds
 *       {@code cancellable = true} + {@code cir.setReturnValue("CANCELLED")}. Assertions:
 *       before the swap the target returns its original value and the applied target
 *       bytecode carries no cancellation code; after the swap it returns {@code CANCELLED}
 *       and the applied bytecode contains the {@code isCancelled} conditional-return call.</li>
 *   <li><b>B target-method switching</b>: the Mixin moves its hook from {@code methodOne}
 *       to {@code methodTwo}. Assertions: {@code methodOne} is fully restored (original
 *       return, no {@code [EVO-1]} line, no stale handler in the applied bytes) and
 *       {@code methodTwo} carries the new hook exactly once.</li>
 *   <li><b>C add/remove @Inject handler method</b>: the Mixin class itself gains a second
 *       {@code @Inject} method and later loses it. Assertions: both hooks fire after the
 *       add; only the original hook fires after the remove, and the applied target bytes
 *       follow the same transitions.</li>
 * </ul>
 *
 * <p>Enable with {@code -Dremold.probe.evo=true}; the driver is
 * {@code evo/run-evo.sh}. Every assertion is on a business-observable end state
 * (method return value / hook output count / applied class bytes), not on internal
 * counters.</p>
 */
public final class EvoProbe implements ModInitializer {

    static final String TAG = "[EVO] ";
    static final Path RUN = Path.of("run");
    static final Path LOG = RUN.resolve("evo.log");
    static final Path DUMPS = Path.of("build", "evo-dumps");

    static final String A = "com.example.evo.EvoTarget";
    static final String B = "com.example.evo.EvoSwitchTarget";
    static final String C = "com.example.evo.EvoLifecycleTarget";

    static final List<String> CAPTURED = Collections.synchronizedList(new ArrayList<>());
    static final List<String> FAILURES = Collections.synchronizedList(new ArrayList<>());

    static PrintStream originalOut;
    static Class<?> targetA;
    static Class<?> targetB;
    static Class<?> targetC;
    static Object instanceA;
    static Object instanceB;
    static Object instanceC;
    static Method aFire;
    static Method bOne;
    static Method bTwo;
    static Method cFire;
    static Map<String, byte[]> bytecodeCache;

    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("remold.probe.evo")) {
            return;
        }
        Thread thread = new Thread(EvoProbe::run, "evo-probe");
        thread.setDaemon(true);
        thread.start();
    }

    static void run() {
        try {
            installTee();
            Thread.sleep(3000);
            targetA = Class.forName(A);
            targetB = Class.forName(B);
            targetC = Class.forName(C);
            instanceA = targetA.getConstructor().newInstance();
            instanceB = targetB.getConstructor().newInstance();
            instanceC = targetC.getConstructor().newInstance();
            aFire = targetA.getMethod("fire");
            bOne = targetB.getMethod("methodOne");
            bTwo = targetB.getMethod("methodTwo");
            cFire = targetC.getMethod("fire");
            Class<?> agent = Class.forName("remold.RemoldAgent");
            bytecodeCache = (Map<String, byte[]>) agent.getField("bytecodeCache").get(null);
            out("targets loaded: " + A + ", " + B + ", " + C);
            out("agent bytecodeCache reachable=" + (bytecodeCache != null));

            // ---------------------------------------------------------------- stage 1
            String rA = call(aFire, instanceA);
            check("stage1 A returns original value", "ORIGINAL".equals(rA), "actual=" + rA);
            check("stage1 A v1 hook fires exactly once", count("[EVO-A] v1") == 1, "count=" + count("[EVO-A] v1"));
            byte[] appliedA = dump("stage1-A", A);
            check("stage1 A applied bytes carry the v1 hook", contains(appliedA, "[EVO-A] v1"), describe(appliedA));
            check("stage1 A applied bytes carry no cancellation code",
                !contains(appliedA, "isCancelled") && !contains(appliedA, "CANCELLED"), describe(appliedA));

            clear();
            String rOne = invoke(bOne, instanceB);
            String rTwo = invoke(bTwo, instanceB);
            check("stage1 B methodOne returns ONE", "ONE".equals(rOne), "actual=" + rOne);
            check("stage1 B methodTwo returns TWO", "TWO".equals(rTwo), "actual=" + rTwo);
            check("stage1 B methodOne hook fires exactly once", count("[EVO-B] one v1") == 1,
                "count=" + count("[EVO-B] one v1"));
            check("stage1 B methodTwo has no hook", count("[EVO-B] two") == 0, "count=" + count("[EVO-B] two"));
            byte[] appliedB = dump("stage1-B", B);
            check("stage1 B applied bytes carry the methodOne hook", contains(appliedB, "[EVO-B] one v1"),
                describe(appliedB));
            check("stage1 B applied bytes carry no methodTwo hook", !contains(appliedB, "[EVO-B] two"),
                describe(appliedB));

            String rC = call(cFire, instanceC);
            check("stage1 C returns LIVE", "LIVE".equals(rC), "actual=" + rC);
            check("stage1 C hookOne fires exactly once", count("[EVO-C] hookOne") == 1,
                "count=" + count("[EVO-C] hookOne"));
            check("stage1 C has no hookTwo", count("[EVO-C] hookTwo") == 0, "count=" + count("[EVO-C] hookTwo"));
            byte[] appliedC = dump("stage1-C", C);
            check("stage1 C applied bytes carry hookOne", contains(appliedC, "[EVO-C] hookOne"), describe(appliedC));
            check("stage1 C applied bytes carry no hookTwo", !contains(appliedC, "[EVO-C] hookTwo"), describe(appliedC));

            out("WAIT-STAGE1");

            // ---------------------------------------------------------------- stage A2
            awaitTrigger("evo-stage-a2.trigger");
            pollUntil(90_000, "A cancellable return after hot swap", () -> {
                String r = call(aFire, instanceA);
                return "CANCELLED".equals(r) && count("[EVO-A] v2") == 1;
            });
            String rA2 = call(aFire, instanceA);
            check("A2 A returns CANCELLED (generated conditional return active)", "CANCELLED".equals(rA2), "actual=" + rA2);
            check("A2 v2 hook fires exactly once", count("[EVO-A] v2") == 1, "count=" + count("[EVO-A] v2"));
            check("A2 old v1 hook no longer fires", count("[EVO-A] v1") == 0, "count=" + count("[EVO-A] v1"));
            byte[] appliedA2 = dump("stageA2-A", A);
            check("A2 applied bytes contain the CANCELLED constant", contains(appliedA2, "CANCELLED"), describe(appliedA2));
            check("A2 applied bytes contain the isCancelled conditional-return call", contains(appliedA2, "isCancelled"),
                describe(appliedA2));
            check("A2 applied bytes drop the stale v1 hook", !contains(appliedA2, "[EVO-A] v1"), describe(appliedA2));
            // unrelated-input control: the two untouched scenarios must not move.
            clear();
            String ctlOne = invoke(bOne, instanceB);
            String ctlTwo = invoke(bTwo, instanceB);
            check("A2 unrelated input: B still v1 on methodOne",
                "ONE".equals(ctlOne) && "TWO".equals(ctlTwo)
                    && count("[EVO-B] one v1") == 1 && count("[EVO-B] two") == 0,
                "one=" + ctlOne + " two=" + ctlTwo);
            clear();
            String ctlC = invoke(cFire, instanceC);
            check("A2 unrelated input: C still only hookOne",
                "LIVE".equals(ctlC) && count("[EVO-C] hookOne") == 1 && count("[EVO-C] hookTwo") == 0,
                "c=" + ctlC);
            check("A2 no LAYOUT-REJECT", !logContains("LAYOUT-REJECT"));
            out("STAGE-A2-DONE");

            // ---------------------------------------------------------------- stage B2
            awaitTrigger("evo-stage-b2.trigger");
            pollUntil(90_000, "B target-method switch after hot swap", () -> {
                clear();
                String one = invoke(bOne, instanceB);
                String two = invoke(bTwo, instanceB);
                return "ONE".equals(one) && "TWO".equals(two)
                    && count("[EVO-B] one") == 0 && count("[EVO-B] two v2") == 1;
            });
            clear();
            String bOneAfter = invoke(bOne, instanceB);
            String bTwoAfter = invoke(bTwo, instanceB);
            check("B2 methodOne returns ONE (original body restored)", "ONE".equals(bOneAfter), "actual=" + bOneAfter);
            check("B2 methodOne hook fully removed (no [EVO-B] one)", count("[EVO-B] one") == 0,
                "count=" + count("[EVO-B] one"));
            check("B2 methodTwo returns TWO", "TWO".equals(bTwoAfter), "actual=" + bTwoAfter);
            check("B2 methodTwo hook fires exactly once", count("[EVO-B] two v2") == 1,
                "count=" + count("[EVO-B] two v2"));
            byte[] appliedB2 = dump("stageB2-B", B);
            check("B2 applied bytes contain the methodTwo hook", contains(appliedB2, "[EVO-B] two v2"), describe(appliedB2));
            check("B2 applied bytes carry no stale methodOne hook", !contains(appliedB2, "[EVO-B] one"), describe(appliedB2));
            clear();
            String ctlA2 = invoke(aFire, instanceA);
            check("B2 unrelated input: A still returns CANCELLED", "CANCELLED".equals(ctlA2), "actual=" + ctlA2);
            check("B2 no LAYOUT-REJECT", !logContains("LAYOUT-REJECT"));
            out("STAGE-B2-DONE");

            // ---------------------------------------------------------------- stage C2
            awaitTrigger("evo-stage-c2.trigger");
            pollUntil(90_000, "C added @Inject method applied after hot swap", () -> {
                String r = call(cFire, instanceC);
                return "LIVE".equals(r) && count("[EVO-C] hookOne") == 1 && count("[EVO-C] hookTwo") == 1;
            });
            String rC2 = call(cFire, instanceC);
            check("C2 C returns LIVE", "LIVE".equals(rC2), "actual=" + rC2);
            check("C2 added hookTwo fires exactly once", count("[EVO-C] hookTwo") == 1,
                "count=" + count("[EVO-C] hookTwo"));
            check("C2 original hookOne still fires exactly once", count("[EVO-C] hookOne") == 1,
                "count=" + count("[EVO-C] hookOne"));
            byte[] appliedC2 = dump("stageC2-C", C);
            check("C2 applied bytes carry hookTwo", contains(appliedC2, "[EVO-C] hookTwo"), describe(appliedC2));
            check("C2 applied bytes carry hookOne", contains(appliedC2, "[EVO-C] hookOne"), describe(appliedC2));
            check("C2 no LAYOUT-REJECT", !logContains("LAYOUT-REJECT"));
            out("STAGE-C2-DONE");

            // ---------------------------------------------------------------- stage C3
            awaitTrigger("evo-stage-c3.trigger");
            pollUntil(90_000, "C removed @Inject method applied after hot swap", () -> {
                String r = call(cFire, instanceC);
                return "LIVE".equals(r) && count("[EVO-C] hookOne") == 1 && count("[EVO-C] hookTwo") == 0;
            });
            String rC3 = call(cFire, instanceC);
            check("C3 C returns LIVE", "LIVE".equals(rC3), "actual=" + rC3);
            check("C3 original hookOne still fires exactly once", count("[EVO-C] hookOne") == 1,
                "count=" + count("[EVO-C] hookOne"));
            check("C3 removed hookTwo no longer fires", count("[EVO-C] hookTwo") == 0,
                "count=" + count("[EVO-C] hookTwo"));
            byte[] appliedC3 = dump("stageC3-C", C);
            check("C3 applied bytes drop hookTwo", !contains(appliedC3, "[EVO-C] hookTwo"), describe(appliedC3));
            check("C3 applied bytes keep hookOne", contains(appliedC3, "[EVO-C] hookOne"), describe(appliedC3));
            check("C3 no LAYOUT-REJECT", !logContains("LAYOUT-REJECT"));
            out("STAGE-C3-DONE");
        } catch (Throwable t) {
            out("PROBE-ERROR " + t);
            t.printStackTrace(originalOut == null ? System.out : originalOut);
            fail("probe error: " + t);
        } finally {
            out("RESULT " + (FAILURES.isEmpty() ? "PASS" : "FAIL") + " failures=" + FAILURES);
            System.out.flush();
            if (!FAILURES.isEmpty()) {
                Runtime.getRuntime().halt(3);
            }
        }
    }

    // ------------------------------------------------------------------ invocation helpers

    static String call(Method method, Object instance) {
        clear();
        return invoke(method, instance);
    }

    static String invoke(Method method, Object instance) {
        try {
            return String.valueOf(method.invoke(instance));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            fail("invoke " + method.getName() + " failed: " + cause);
            return "<error:" + cause + ">";
        } catch (Throwable t) {
            fail("invoke " + method.getName() + " failed: " + t);
            return "<error:" + t + ">";
        }
    }

    static void pollUntil(long timeoutMs, String label, BooleanSupplier ok) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (ok.getAsBoolean()) {
                    return;
                }
            } catch (Throwable t) {
                out("poll error (" + label + "): " + t);
            }
            Thread.sleep(500);
        }
        fail("timeout waiting for " + label);
    }

    // ------------------------------------------------------------------ assertions

    static void check(String what, boolean ok) {
        check(what, ok, "");
    }

    static void check(String what, boolean ok, String detail) {
        if (ok) {
            out("PASS " + what);
        } else {
            FAILURES.add(what);
            out("FAIL " + what + (detail.isEmpty() ? "" : " [" + detail + "]"));
        }
    }

    static void fail(String what) {
        FAILURES.add(what);
        out("FAIL " + what);
    }

    // ------------------------------------------------------------------ bytecode evidence

    static byte[] appliedBytes(String dotClassName) {
        if (bytecodeCache == null) {
            return null;
        }
        return bytecodeCache.get(dotClassName);
    }

    static byte[] dump(String phase, String dotName) {
        byte[] bytes = appliedBytes(dotName);
        if (bytes == null) {
            out("applied bytes for " + dotName + " @ " + phase + ": <none in bytecodeCache>");
            return null;
        }
        try {
            Files.createDirectories(DUMPS);
            Files.write(DUMPS.resolve(phase + ".class"), bytes);
        } catch (Throwable t) {
            out("dump " + phase + " failed: " + t);
        }
        out("applied " + phase + ": len=" + bytes.length + " sha256=" + sha256Hex(bytes));
        return bytes;
    }

    static String describe(byte[] bytes) {
        if (bytes == null) {
            return "applied=<null>";
        }
        return "applied len=" + bytes.length + " sha=" + sha256Hex(bytes).substring(0, 16);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "<sha-error:" + e + ">";
        }
    }

    static boolean contains(byte[] haystack, String needle) {
        if (haystack == null) {
            return false;
        }
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ plumbing

    static int count(String needle) {
        int n = 0;
        synchronized (CAPTURED) {
            for (String line : CAPTURED) {
                if (line.contains(needle)) {
                    n++;
                }
            }
        }
        return n;
    }

    static void clear() {
        CAPTURED.clear();
    }

    static boolean logContains(String needle) {
        try {
            // Skip the probe's own lines: assertion text such as "no LAYOUT-REJECT" would
            // otherwise make this lookup match itself.
            for (String line : Files.readAllLines(LOG)) {
                if (line.contains(TAG)) {
                    continue;
                }
                if (line.contains(needle)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    static void awaitTrigger(String name) throws InterruptedException {
        Path path = RUN.resolve(name);
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path)) {
                return;
            }
            Thread.sleep(250);
        }
        fail("timeout waiting for trigger " + name);
    }

    static void installTee() {
        originalOut = System.out;
        System.setOut(new PrintStream(new OutputStream() {
            final StringBuilder line = new StringBuilder();

            @Override
            public void write(int b) {
                originalOut.write(b);
                if (b == '\n') {
                    CAPTURED.add(line.toString());
                    line.setLength(0);
                } else {
                    line.append((char) b);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) {
                for (int i = off; i < off + len; i++) {
                    write(b[i]);
                }
            }

            @Override
            public void flush() {
                originalOut.flush();
            }
        }, true, StandardCharsets.UTF_8));
    }

    static void out(String message) {
        System.out.println(TAG + message);
    }
}
