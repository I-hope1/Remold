package com.example.examplemod;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * C5: Forge (ModLauncher) main-path probe. Prints the target class's ClassLoader identity and
 * the retransform/fetchOriginal byte hashes (Q1/Q2), then drives a real source-edit hot-swap:
 * stage 1 adds a method, stage 2 adds a field, and the redefined class's own @OnReload prints
 * the values (the terminal assertion).
 */
public final class C5Probe {
    private static final String TAG = "[C5] ";
    private static final String TARGET = "com.example.examplemod.HotswapTarget";
    // server working dir is <project>/run, so control files live under run/c5-control
    private static final Path RUN = Path.of("c5-control");
    private static final List<String> CAPTURED = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger BATCHES = new AtomicInteger();
    private static final List<String> FAILURES = Collections.synchronizedList(new ArrayList<>());
    private static PrintStream originalOut;
    private static Class<?> agent;
    private static Class<?> target;

    public static void start() {
        if (!Boolean.getBoolean("remold.probe.c5")) return;
        Thread t = new Thread(C5Probe::run, "c5-probe");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        try {
            installTee();
            agent = Class.forName("remold.RemoldAgent");
            registerListener();
            target = Class.forName(TARGET, true, C5Probe.class.getClassLoader());
            Object instance = target.getConstructor().newInstance();
            Field value = target.getDeclaredField("value");
            value.setAccessible(true);
            value.setInt(instance, 7);
            out("PHASE0 target=" + target.getName());
            out("PHASE0 classLoader=" + target.getClassLoader()
                + " classLoaderClass=" + (target.getClassLoader() == null ? "bootstrap" : target.getClassLoader().getClass().getName()));
            out("PHASE0 instanceLoader=" + instance.getClass().getClassLoader());
            report("Q1_retransform_input", (byte[]) call("fetchBytecodeMemory", new Class<?>[]{Class.class}, target));
            report("Q2_fetchOriginal", (byte[]) call("fetchOriginalBytecode", new Class<?>[]{Class.class}, target));
            report("Q2_bytecodeCache", cache("bytecodeCache"));
            report("Q2_sourceBytecodeCache", cache("sourceBytecodeCache"));
            out("WAIT-STAGE1");
            await("c5-stage1.trigger", 240_000);
            awaitBatch(1, 180_000);
            check("stage1 redefine batch observed", true);
            check("stage1 @OnReload on live instance saw the new method",
                awaitCaptured("[C5-READ] stage1 method=C5-V1", 60_000));
            out("STAGE1-DONE");

            out("WAIT-STAGE2");
            await("c5-stage2.trigger", 240_000);
            awaitBatch(2, 180_000);
            check("stage2 redefine batch observed", true);
            check("stage2 InitFix patched live instance: @OnReload read added=456",
                awaitCaptured("[C5-READ] stage2 method=C5-V1 added=456", 60_000));
            out("STAGE2-DONE");
        } catch (Throwable t) {
            out("PROBE-ERROR " + t);
            t.printStackTrace(System.out);
            FAILURES.add("probe error: " + t);
        } finally {
            out("RESULT " + (FAILURES.isEmpty() ? "PASS" : "FAIL") + " failures=" + FAILURES);
            System.out.flush();
            Runtime.getRuntime().halt(FAILURES.isEmpty() ? 0 : 3);
        }
    }

    private static void registerListener() throws Exception {
        Class<?> iface = Class.forName("remold.HotswapBatchListener");
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "afterInternalBatch": {
                    List<?> defs = (List<?>) args[0];
                    List<String> names = new ArrayList<>();
                    if (defs != null) for (Object d : defs) {
                        names.add(String.valueOf(((ClassDefinition) d).getDefinitionClass().getName()));
                    }
                    BATCHES.incrementAndGet();
                    out("BATCH#" + BATCHES.get() + " classes=" + names);
                    return null;
                }
                case "onExternalClassRedefined":
                    return null;
                case "toString":
                    return "c5-probe-listener";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        Object listener = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
        agent.getMethod("addBatchListener", iface).invoke(null, listener);
        out("LISTENER registered");
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = agent.getMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static byte[] cache(String field) throws Exception {
        Field f = agent.getDeclaredField(field);
        f.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) f.get(null);
        Object v = map.get(TARGET);
        return v instanceof byte[] b ? b : null;
    }

    private static void report(String label, byte[] bytes) throws Exception {
        if (bytes == null) { out(label + ": null"); return; }
        out(label + ": len=" + bytes.length + " sha256=" + sha(bytes));
    }

    private static String sha(byte[] bytes) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void awaitBatch(int n, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (BATCHES.get() < n) {
            if (System.currentTimeMillis() > deadline) throw new IllegalStateException("timeout waiting for batch #" + n + " (got " + BATCHES.get() + ")");
            sleep(100);
        }
    }

    private static void await(String triggerName, long timeoutMs) {
        Path trigger = RUN.resolve(triggerName);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!Files.exists(trigger)) {
            if (System.currentTimeMillis() > deadline) throw new IllegalStateException("timeout waiting for " + trigger);
            sleep(200);
        }
    }

    private static boolean awaitCaptured(String fragment, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            synchronized (CAPTURED) {
                for (String line : CAPTURED) if (line.contains(fragment)) return true;
            }
            if (System.currentTimeMillis() > deadline) { out("CAPTURE-MISS " + fragment); return false; }
            sleep(100);
        }
    }

    private static void installTee() {
        originalOut = System.out;
        System.setOut(new PrintStream(new OutputStream() {
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();
            @Override public synchronized void write(int b) {
                line.write(b);
                if (b == '\n') flushLine();
            }
            @Override public synchronized void write(byte[] b, int off, int len) {
                for (int i = 0; i < len; i++) write(b[off + i]);
            }
            @Override public synchronized void flush() {
                if (line.size() > 0) flushLine();
                originalOut.flush();
            }
            private void flushLine() {
                byte[] data = line.toByteArray();
                line.reset();
                try { originalOut.write(data); originalOut.flush(); } catch (Throwable ignored) { }
                CAPTURED.add(new String(data, StandardCharsets.UTF_8).trim());
            }
        }, true, StandardCharsets.UTF_8));
    }

    private static void check(String what, boolean ok) {
        if (ok) out("PASS " + what); else { out("FAIL " + what); FAILURES.add(what); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void out(String msg) {
        System.out.println(TAG + msg);
    }
}
