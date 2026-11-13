package probe;

import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import jdk.internal.misc.Unsafe;
import test.Holder;

/**
 * A/B probe for rewriting an EXISTING static final reference field (runtime-initialized, so it has no
 * ConstantValue attribute):
 *
 * <pre>
 *   single: redefine v2 -> patch (Unsafe write VAL)            (single-phase pipeline order)
 *   two:    redefine stage A -> patch -> redefine stage C      (two-phase pipeline order)
 * </pre>
 *
 * Both modes warm the readers BEFORE the redefine and warm again after stage A/redefine, so the
 * compiled readers are produced inside the "patch not yet landed" window; then the field is written
 * and the readers are sampled. Expected: single keeps the old value (stale JIT), two refreshes it
 * because stage C redefines the host after the write.
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
            throw new IllegalArgumentException("mode must be single|two: " + mode);
        }
        out("done", "ok");
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
