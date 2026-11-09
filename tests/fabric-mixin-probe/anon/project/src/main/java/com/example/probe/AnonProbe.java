package com.example.probe;

import com.example.game.AnonTarget;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import net.fabricmc.api.ModInitializer;

/**
 * Driver-side observation point.
 *
 * <p>It keeps calling the live {@link AnonTarget#poke()} (i.e. the real business
 * method of the real, already-woven class) and prints the returned string. A
 * stage is "read" by dropping a {@code <label>.trigger} file into the run
 * directory: the probe then prints exactly one {@code [ANON] <label> poke=<value>}
 * line plus a {@code [ANON] <label>-DONE} marker. Continuous ticks exist so the
 * log also shows a transition that happens without a trigger.</p>
 *
 * <p>The value is produced by the class that is actually loaded at that moment:
 * if the Mixin hot reload failed, the old woven handler keeps answering; if only
 * the anonymous class body changed but the generated (conformed) inner class was
 * never regenerated, the old anonymous body keeps answering.</p>
 */
public class AnonProbe implements ModInitializer {

    private static final long TICK_MS = 2000L;

    @Override
    public void onInitialize() {
        String dir = System.getProperty("anon.dir", "run");
        Thread thread = new Thread(() -> loop(Paths.get(dir)), "anon-probe");
        thread.setDaemon(true);
        thread.start();
        System.out.println("[ANON] probe started dir=" + Paths.get(dir).toAbsolutePath());
    }

    private static void loop(Path dir) {
        long lastTick = 0L;
        while (true) {
            long now = System.currentTimeMillis();
            try {
                for (Path trigger : triggers(dir)) {
                    String label = trigger.getFileName().toString().replace(".trigger", "");
                    String value = new AnonTarget().poke();
                    System.out.println("[ANON] " + label + " poke=" + value);
                    Files.deleteIfExists(trigger);
                    System.out.println("[ANON] " + label + "-DONE");
                }
                if (now - lastTick >= TICK_MS) {
                    lastTick = now;
                    System.out.println("[ANON] tick poke=" + new AnonTarget().poke());
                }
            } catch (Throwable t) {
                printError(t);
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Prints the full cause chain: a bare Mixin error message hides the real reason. */
    private static void printError(Throwable t) {
        StringBuilder sb = new StringBuilder("[ANON] probe error: ");
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 8) {
            sb.append(depth == 0 ? "" : " | caused by: ").append(cur);
            cur = cur.getCause();
            depth++;
        }
        System.out.println(sb);
        if (t.getCause() != null) {
            t.getCause().printStackTrace(System.out);
        }
    }

    private static java.util.List<Path> triggers(Path dir) throws IOException {
        java.util.List<Path> out = new java.util.ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.trigger")) {
            for (Path p : stream) {
                out.add(p);
            }
        }
        out.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }
}
