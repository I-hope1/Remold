package probe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import jdk.internal.misc.Unsafe;
import test.Color;

/**
 * Enum-append runtime probe. One scenario per JVM run (state cannot be undone).
 *
 * <pre>
 * java -XX:+AllowEnhancedClassRedefinition -javaagent:agent.jar \
 *      --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
 *      --add-opens java.base/java.lang=ALL-UNNAMED \
 *      -cp "probe;v1" probe.Probe &lt;scenario&gt; &lt;v2-class-bytes&gt; [aux-bytes]
 * </pre>
 */
public final class Probe {

    interface Throwing {
        Object run() throws Throwable;
    }

    static final Unsafe U = unsafe();

    static Field F_CONSTANTS;
    static Field F_DIRECTORY;

    static Class<?> CLASS_BEFORE;
    static Class<?> STATIC_MIRROR;
    static final Object[] MIRROR_HOLD = new Object[1];
    static Object CONSTS_BEFORE;
    static Object DIR_BEFORE;

    static EnumMap<Color, String> EM0;
    static EnumSet<Color> ES0_ALL;
    static EnumSet<Color> ES0_SOME;

    static final AtomicBoolean SPIN_STOP = new AtomicBoolean();
    static final List<Integer> SPIN_SEEN = Collections.synchronizedList(new ArrayList<>());

    private Probe() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        Path v2Path = Path.of(args[1]);
        byte[] v2 = Files.readAllBytes(v2Path);
        byte[] aux = args.length > 2 ? Files.readAllBytes(Path.of(args[2])) : null;

        out("scenario", scenario);
        out("v2.bytes", v2Path + " size=" + v2.length);
        out("java.version", System.getProperty("java.version")
                + " / " + System.getProperty("java.vm.name")
                + " / " + System.getProperty("java.vm.info"));
        out("agent", ProbeAgent.INST == null ? "MISSING"
                : ("ok, redefineSupported=" + ProbeAgent.INST.isRedefineClassesSupported()
                   + ", retransformSupported=" + ProbeAgent.INST.isRetransformClassesSupported()));
        try {
            switch (scenario) {
                case "sanity" -> sanity();
                case "cache-after-redefine" -> cacheAfterRedefine(v2);
                case "mirror" -> mirrorScenario(v2);
                case "mirror-body" -> mirrorBodyScenario(v2);
                case "race" -> raceScenario(v2);
                case "race-consts" -> racePartial(v2, true, false);
                case "race-dir" -> racePartial(v2, false, true);
                case "baseline" -> baseline(v2);
                case "repair" -> repair(v2, "both", false);
                case "cache-none" -> repair(v2, "none", false);
                case "cache-consts" -> repair(v2, "consts", false);
                case "cache-dir" -> repair(v2, "dir", false);
                case "repair-resize" -> repair(v2, "both", true);
                case "jit-spin" -> jitSpin(v2);
                case "jit-cold" -> jitCold(v2);
                case "jit-field" -> jitField(v2);
                case "deopt-unsafe-only" -> jitDeopt(v2, "unsafe-only");
                case "deopt-redefine-reader" -> jitDeopt(v2, "redefine-reader");
                case "deopt-retransform-reader" -> jitDeopt(v2, "retransform-reader");
                case "deopt-retransform-enum" -> jitDeopt(v2, "retransform-enum");
                case "deopt-redefine-enum" -> jitDeopt(v2, "redefine-enum");
                case "jit-inline" -> jitInline(v2);
                case "jit-values-write" -> jitValuesWrite(v2);
                case "jit-spin-reader-deopt" -> jitSpinReaderDeopt(v2);
                case "stagec-static" -> stagecStatic(v2);
                case "swv2-after" -> swv2After(v2);
                case "swv2-before" -> swv2Before(v2);
                case "ctor" -> ctorScenario(v2);
                case "body" -> bodyScenario(v2, aux);
                default -> out("error", "unknown scenario " + scenario);
            }
        } catch (Throwable t) {
            out("FATAL", describe(t));
            t.printStackTrace(System.out);
        }
        out("done", "true");
    }

    // =====================================================================================
    // scenarios
    // =====================================================================================

    /** No redefine: validates the measurement tools on the v1 class itself. */
    static void sanity() throws Throwable {
        out("sanity.class.identity", Color.class == Color.RED.getClass());
        out("sanity.values", valuesInfo());
        probe("sanity.mh.RED", () -> mhStatic("RED"));
        probe("sanity.reflect.RED", () -> reflectStatic("RED"));
        probe("sanity.unsafe.RED", () -> unsafeStatic("RED"));
        probe("sanity.mh.RED.identity", () -> mhStatic("RED") == Color.RED);
        probe("sanity.valueOf.RED", () -> Color.valueOf("RED"));
        probe("sanity.getEnumConstants", () -> Color.class.getEnumConstants());
        out("sanity.cache.enumConstants", cacheConstants());
        out("sanity.cache.enumConstantDirectory", cacheDirectory());
        EnumMap<Color, String> m = new EnumMap<>(Color.class);
        m.put(Color.RED, "r");
        out("sanity.EnumMap", "size=" + m.size());
        out("sanity.EnumSet.allOf", "size=" + EnumSet.allOf(Color.class).size());
        out("sanity.switch.RED", SwV1.sw(Color.RED));
        out("sanity.switchMap", switchMapInfo("probe.SwV1$1"));
        probe("sanity.mh.BLUE", () -> mhStatic("BLUE"));
        probe("sanity.reflect.BLUE", () -> reflectStatic("BLUE"));
        probe("sanity.values[0].tag", () -> Color.values()[0].tag());
        probe("sanity.cache.after.valueOf", () -> cacheConstants());
        probe("sanity.cache.directory.after.valueOf", () -> cacheDirectory());
        probe("sanity.BlueReader.load.before.redefine", () -> {
            Class<?> br = Class.forName("probe.BlueReader");
            return br.getMethod("read").invoke(null);
        });
    }

    /**
     * Which Class mirror is canonical after redefine? Store the pre-redefine Class in an array
     * element (docs say array elements are NOT rewritten) and in a static field (docs say static
     * fields ARE rewritten to a shadow), then compare both with the post-redefine literal.
     */
    static void mirrorScenario(byte[] v2) throws Throwable {
        preCache();
        MIRROR_HOLD[0] = Color.class;
        STATIC_MIRROR = Color.class;
        final Class<?> localMirror = Color.class;
        out("mirror.before.canonical", Color.class + " name=" + Color.class.getName());
        redefine(v2);
        out("mirror.after.canonical==arrayHeld(old)", MIRROR_HOLD[0] == Color.class);
        out("mirror.after.canonical==staticHeld(old)", STATIC_MIRROR == Color.class);
        out("mirror.after.canonical==local(old)", localMirror == Color.class);
        out("mirror.after.arrayHeld.name", ((Class<?>) MIRROR_HOLD[0]).getName());
        out("mirror.after.staticHeld.name", STATIC_MIRROR.getName());
        out("mirror.after.arrayHeld.enumConstantsField", readCache((Class<?>) MIRROR_HOLD[0], F_CONSTANTS));
        out("mirror.after.staticHeld.enumConstantsField", readCache(STATIC_MIRROR, F_CONSTANTS));
        out("mirror.after.canonical.enumConstantsField", cacheConstants());
        out("mirror.after.arrayHeld.getEnumConstants", ((Class<?>) MIRROR_HOLD[0]).getEnumConstants());
        out("mirror.after.staticHeld.getEnumConstants", STATIC_MIRROR.getEnumConstants());
        probe("mirror.after.arrayHeld.valueOf.BLUE", () -> Enum.valueOf((Class) MIRROR_HOLD[0], "BLUE"));
        probe("mirror.after.staticHeld.valueOf.BLUE", () -> Enum.valueOf((Class) STATIC_MIRROR, "BLUE"));
        probe("mirror.after.arrayHeld.valueOf.GREEN", () -> Enum.valueOf((Class) MIRROR_HOLD[0], "GREEN"));
        probe("mirror.after.canonical.RED.getClass", () -> Color.RED.getClass() == Color.class);
        probe("mirror.after.arrayHeld.isInstance(blue)", () -> {
            Color blue = newConstant("BLUE", 2);
            return "arrayHeld.isInstance=" + ((Class<?>) MIRROR_HOLD[0]).isInstance(blue)
                    + " staticHeld.isInstance=" + STATIC_MIRROR.isInstance(blue)
                    + " canonical.isInstance=" + Color.class.isInstance(blue);
        });

        // now repair and re-test consumers that still hold the SHADOW mirror
        final Color blue = newConstant("BLUE", 2);
        unsafePutStatic("BLUE", blue);
        rebuildValues(blue);
        clearCaches(true, true);
        out("mirror.repair.values", valuesInfo());
        probe("mirror.afterRepair.canonical.valueOf.BLUE", () -> Color.valueOf("BLUE"));
        probe("mirror.afterRepair.shadow.valueOf.BLUE", () -> Enum.valueOf((Class) STATIC_MIRROR, "BLUE"));
        probe("mirror.afterRepair.shadow.getEnumConstants", () -> STATIC_MIRROR.getEnumConstants());
        probe("mirror.afterRepair.shadow.isInstance(blue)", () -> STATIC_MIRROR.isInstance(blue));
        probe("mirror.afterRepair.shadowEnumMap.put(blue)", () -> {
            EnumMap raw = new EnumMap((Class) STATIC_MIRROR);
            raw.put(blue, "x");
            return "size=" + raw.size();
        });
        probe("mirror.afterRepair.shadowEnumSet.allOf.size", () -> EnumSet.allOf((Class) STATIC_MIRROR).size());
    }

    /**
     * Realistic hot-swap window: after redefine, application code calls valueOf()/getEnumConstants()
     * BEFORE the repair happens, repopulating the Class caches from the stale $VALUES. Then repair
     * does NOT clear the caches -> does valueOf("BLUE") still fail? Then clear and re-check.
     */
    static void raceScenario(byte[] v2) throws Throwable {
        preCache();
        redefine(v2);
        out("race.afterRedefine.cache.enumConstants", cacheConstants());
        out("race.afterRedefine.cache.enumConstantDirectory", cacheDirectory());

        // window consumers (application threads may do exactly this between redefine and patch)
        probe("race.window.valueOf.GREEN", () -> Color.valueOf("GREEN"));
        probe("race.window.getEnumConstants", () -> Color.class.getEnumConstants());
        out("race.window.cache.enumConstants", cacheConstants());
        out("race.window.cache.enumConstantDirectory", cacheDirectory());

        final Color blue = newConstant("BLUE", 2);
        unsafePutStatic("BLUE", blue);
        rebuildValues(blue);
        out("race.repair.values", valuesInfo());
        // NOTE: no cache clear here, on purpose
        probe("race.afterRepairNoClear.valueOf.BLUE", () -> Color.valueOf("BLUE"));
        probe("race.afterRepairNoClear.getEnumConstants", () -> Color.class.getEnumConstants());
        probe("race.afterRepairNoClear.newEnumMap", () -> {
            EnumMap<Color, String> mm = new EnumMap<>(Color.class);
            mm.put(blue, "b");
            return "size=" + mm.size();
        });
        probe("race.afterRepairNoClear.newEnumSet.allOf.size", () -> EnumSet.allOf(Color.class).size());

        clearCaches(true, true);
        probe("race.afterClear.valueOf.BLUE", () -> Color.valueOf("BLUE"));
        probe("race.afterClear.getEnumConstants", () -> Color.class.getEnumConstants());
        probe("race.afterClear.newEnumMap", () -> {
            EnumMap<Color, String> mm = new EnumMap<>(Color.class);
            mm.put(blue, "b");
            return "size=" + mm.size();
        });
        probe("race.afterClear.newEnumSet.allOf.size", () -> EnumSet.allOf(Color.class).size());
    }

    /** Body-only redefine (same field table): does redefine still clear the Class enum caches? */
    static void mirrorBodyScenario(byte[] v2) throws Throwable {
        preCache();
        MIRROR_HOLD[0] = Color.class;
        STATIC_MIRROR = Color.class;
        redefine(v2);
        out("mirrorbody.canonical==arrayHeld", MIRROR_HOLD[0] == Color.class);
        out("mirrorbody.canonical==staticHeld", STATIC_MIRROR == Color.class);
        out("mirrorbody.canonical.enumConstantsField", cacheConstants());
        out("mirrorbody.canonical.enumConstantDirectoryField", cacheDirectory());
        out("mirrorbody.staticHeld.enumConstantsField", readCache(STATIC_MIRROR, F_CONSTANTS));
        probe("mirrorbody.values", () -> valuesInfo());
        probe("mirrorbody.valueOf.RED", () -> Color.valueOf("RED"));
        probe("mirrorbody.RED.tag", () -> Color.RED.tag());
        probe("mirrorbody.getEnumConstants", () -> Color.class.getEnumConstants());
        out("mirrorbody.cache.enumConstants.afterConsumers", cacheConstants());
    }

    static Object readCache(Class<?> c, Field f) {
        try {
            f.setAccessible(true);
            return f.get(c);
        } catch (Throwable t) {
            return "ERR " + describe(t);
        }
    }

    /**
     * Window consumer repopulates BOTH Class caches, then repair + clear only ONE of them.
     * Isolates which cache backs which consumer (measured, not inferred).
     */
    static void racePartial(byte[] v2, boolean clearConsts, boolean clearDir) throws Throwable {
        preCache();
        redefine(v2);
        // window consumers: populate BOTH caches with the stale (2-constant) universe
        probe("racepartial.window.valueOf.GREEN", () -> Color.valueOf("GREEN"));
        probe("racepartial.window.getEnumConstants", () -> Color.class.getEnumConstants());
        out("racepartial.window.cache.enumConstants", cacheConstants());
        out("racepartial.window.cache.enumConstantDirectory", cacheDirectory());

        final Color blue = newConstant("BLUE", 2);
        unsafePutStatic("BLUE", blue);
        rebuildValues(blue);
        out("racepartial.mode", "clearConsts=" + clearConsts + " clearDir=" + clearDir);
        clearCaches(clearConsts, clearDir);

        probe("racepartial.valueOf.BLUE", () -> Color.valueOf("BLUE"));
        probe("racepartial.getEnumConstants", () -> Color.class.getEnumConstants());
        probe("racepartial.newEnumMap", () -> {
            EnumMap<Color, String> mm = new EnumMap<>(Color.class);
            mm.put(blue, "b");
            return "size=" + mm.size();
        });
        probe("racepartial.newEnumSet.allOf.size", () -> EnumSet.allOf(Color.class).size());
        probe("racepartial.serialize.blue", () -> {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (ObjectOutputStream oo = new ObjectOutputStream(bo)) {
                oo.writeObject(blue);
            }
            try (ObjectInputStream oi = new ObjectInputStream(new ByteArrayInputStream(bo.toByteArray()))) {
                return "back==blue:" + (oi.readObject() == blue);
            }
        });
    }

    /** Does redefineClasses itself drop Class.enumConstants / enumConstantDirectory? */
    static void cacheAfterRedefine(byte[] v2) throws Throwable {
        preCache();
        redefine(v2);
        out("cacheredef.class.identity.beforeAfter", CLASS_BEFORE == Color.class);
        out("cacheredef.cache.enumConstants", cacheConstants());
        out("cacheredef.cache.enumConstantDirectory", cacheDirectory());
        probe("cacheredef.getEnumConstants", () -> Color.class.getEnumConstants());
        probe("cacheredef.valueOf.GREEN", () -> Color.valueOf("GREEN"));
        out("cacheredef.cache.enumConstants.afterNativeConsumer", cacheConstants());
        out("cacheredef.cache.enumConstantDirectory.afterNativeConsumer", cacheDirectory());
        probe("cacheredef.newEnumMap", () -> new EnumMap<>(Color.class).size());
        probe("cacheredef.newEnumSet.allOf", () -> EnumSet.allOf(Color.class).size());
    }

    /** Redefine to v2 WITHOUT any repair; measure every consumer. */
    static void baseline(byte[] v2) throws Throwable {
        preCache();
        Object oldValues = unsafeStatic("$VALUES");
        out("baseline.before.values", valuesInfo());
        out("baseline.before.class.identity", Color.class == Color.RED.getClass());

        redefine(v2);

        out("baseline.class.identity.beforeAfter", CLASS_BEFORE == Color.class);
        out("baseline.cache.enumConstants.immediatelyAfterRedefine", cacheConstants());
        out("baseline.cache.enumConstantDirectory.immediatelyAfterRedefine", cacheDirectory());
        out("baseline.after.class.identity", Color.class == Color.RED.getClass());
        out("baseline.after.RED.getClass==Color.class", Color.RED.getClass() == Color.class);
        probe("baseline.after.RED.name", () -> Color.RED.name());
        probe("baseline.after.RED.ordinal", () -> Color.RED.ordinal());
        probe("baseline.after.RED.tag", () -> Color.RED.tag());
        probe("baseline.after.mh.BLUE", () -> mhStatic("BLUE"));
        probe("baseline.after.reflect.BLUE", () -> reflectStatic("BLUE"));
        probe("baseline.after.unsafe.BLUE", () -> unsafeStatic("BLUE"));
        probe("baseline.after.values", () -> valuesInfo());
        out("baseline.after.$VALUES.sameArray", unsafeStatic("$VALUES") == oldValues);
        probe("baseline.after.valueOf.BLUE", () -> Color.valueOf("BLUE"));
        probe("baseline.after.getEnumConstants", () -> Color.class.getEnumConstants());
        probe("baseline.after.BlueReader.read", () -> readBlueReader("read"));
        probe("baseline.after.BlueReader.name", () -> readBlueReader("name"));
        probe("baseline.after.BlueReader.ordinal", () -> readBlueReader("ordinal"));
        probe("baseline.after.newEnumMap", () -> {
            EnumMap<Color, String> mm = new EnumMap<>(Color.class);
            return "size=" + mm.size();
        });
        probe("baseline.after.newEnumSet.allOf.size", () -> EnumSet.allOf(Color.class).size());

        final Color blue = newConstant("BLUE", 2);
        out("baseline.blue.reflectivelyCreated", render(blue) + " name=" + blue.name() + " ordinal=" + blue.ordinal());
        probe("baseline.hazard.preEnumMap.put(blue)", () -> {
            EM0.put(blue, "b");
            return "size=" + EM0.size();
        });
        probe("baseline.hazard.preEnumMap.get(blue)", () -> EM0.get(blue));
        probe("baseline.hazard.newEnumMap.put(blue)", () -> {
            EnumMap<Color, String> mm = new EnumMap<>(Color.class);
            mm.put(blue, "b");
            return "size=" + mm.size();
        });
        probe("baseline.hazard.newEnumSet.of(blue)", () -> "size=" + EnumSet.of(blue).size());
        probe("baseline.hazard.newEnumSet.of(blue).iterate", () -> {
            int n = 0;
            for (Color c : EnumSet.of(blue)) {
                n++;
            }
            return "iterated=" + n;
        });
        probe("baseline.hazard.preEnumSet.add(blue)", () -> {
            boolean added = ES0_ALL.add(blue);
            return "added=" + added + " size=" + ES0_ALL.size();
        });
        probe("baseline.hazard.preEnumSet.iterate", () -> {
            int n = 0;
            for (Color c : ES0_ALL) {
                n++;
            }
            return "iterated=" + n;
        });
        probe("baseline.switch.sw(RED)", () -> SwV1.sw(Color.RED));
        probe("baseline.switch.sw(blue)", () -> SwV1.sw(blue));
        probe("baseline.switch.sw(null)", () -> SwV1.sw(null));
        out("baseline.switchMap", switchMapInfo("probe.SwV1$1"));
        probe("baseline.swv2.load.after.redefine", () -> {
            Class<?> c = Class.forName("probe.SwV2");
            return c.getMethod("sw", Color.class).invoke(null, blue);
        });
        probe("baseline.swv2.retry", () -> Class.forName("probe.SwV2").getName());
    }

    /**
     * The user's proposed repair: new constant instance + Unsafe static-field write +
     * $VALUES refresh + optional Class enum-cache eviction.
     */
    static void repair(byte[] v2, String cacheMode, boolean resizeSwitchMap) throws Throwable {
        preCache();
        Object oldValues = unsafeStatic("$VALUES");
        out("repair.before.values", valuesInfo());

        redefine(v2);

        out("repair.class.identity.beforeAfter", CLASS_BEFORE == Color.class);
        out("repair.cache.enumConstants.immediatelyAfterRedefine", cacheConstants());
        out("repair.cache.enumConstantDirectory.immediatelyAfterRedefine", cacheDirectory());

        final Color blue = newConstant("BLUE", 2);
        out("repair.blue", render(blue) + " name=" + blue.name() + " ordinal=" + blue.ordinal());
        unsafePutStatic("BLUE", blue);
        out("repair.BLUE.afterWrite.mh", mhStatic("BLUE"));
        out("repair.BLUE.afterWrite.unsafe", unsafeStatic("BLUE"));

        rebuildValues(blue);
        out("repair.$VALUES.changedIdentity", unsafeStatic("$VALUES") != oldValues);
        out("repair.$VALUES", unsafeStatic("$VALUES"));

        boolean consts = cacheMode.equals("both") || cacheMode.equals("consts");
        boolean dir = cacheMode.equals("both") || cacheMode.equals("dir");
        out("repair.cacheMode", cacheMode);
        clearCaches(consts, dir);

        out("repair.class.identity", Color.class == Color.RED.getClass());
        probe("repair.values", () -> valuesInfo());
        probe("repair.values[2]==blue", () -> Color.values()[2] == blue);
        probe("repair.values[2].ordinal", () -> Color.values()[2].ordinal());
        probe("repair.BlueReader.read", () -> readBlueReader("read"));
        probe("repair.BlueReader.name", () -> readBlueReader("name"));
        probe("repair.BlueReader.ordinal", () -> readBlueReader("ordinal"));
        probe("repair.valueOf.BLUE", () -> Color.valueOf("BLUE"));
        probe("repair.valueOf.BLUE==blue", () -> Color.valueOf("BLUE") == blue);
        probe("repair.getEnumConstants", () -> Color.class.getEnumConstants());
        probe("repair.getEnumConstants[2]==blue", () -> Color.class.getEnumConstants()[2] == blue);
        probe("repair.newEnumMap", () -> {
            EnumMap<Color, String> mm = new EnumMap<>(Color.class);
            mm.put(Color.RED, "r");
            mm.put(blue, "b");
            return "size=" + mm.size() + " get(blue)=" + mm.get(blue);
        });
        probe("repair.newEnumSet.allOf.size", () -> EnumSet.allOf(Color.class).size());
        probe("repair.newEnumSet.of(blue)", () -> "size=" + EnumSet.of(blue).size());
        probe("repair.newEnumSet.allOf.iterate", () -> {
            int n = 0;
            for (Color c : EnumSet.allOf(Color.class)) {
                n++;
            }
            return "iterated=" + n;
        });
        probe("repair.serialize.blue", () -> {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (ObjectOutputStream oo = new ObjectOutputStream(bo)) {
                oo.writeObject(blue);
            }
            try (ObjectInputStream oi = new ObjectInputStream(new ByteArrayInputStream(bo.toByteArray()))) {
                Object back = oi.readObject();
                return "back==blue:" + (back == blue);
            }
        });

        // ---- residual hazards ----
        probe("repair.hazard.preEnumMap.put(blue)", () -> {
            EM0.put(blue, "b");
            return "size=" + EM0.size();
        });
        probe("repair.hazard.preEnumMap.get(blue)", () -> EM0.get(blue));
        probe("repair.hazard.preEnumSet.add(blue)", () -> {
            boolean added = ES0_ALL.add(blue);
            return "added=" + added + " size=" + ES0_ALL.size() + " contains=" + ES0_ALL.contains(blue);
        });
        probe("repair.hazard.preEnumSet.iterate", () -> {
            int n = 0;
            for (Color c : ES0_ALL) {
                n++;
            }
            return "iterated=" + n;
        });
        probe("repair.hazard.preEnumSet.toArray", () -> ES0_ALL.toArray().length);
        probe("repair.hazard.preEnumSet.complementOf", () -> EnumSet.complementOf(ES0_SOME).size());
        probe("repair.switch.sw(RED)", () -> SwV1.sw(Color.RED));
        probe("repair.switch.sw(blue)", () -> SwV1.sw(blue));
        out("repair.switchMap.beforeResize", switchMapInfo("probe.SwV1$1"));

        if (resizeSwitchMap) {
            resizeSwitchMap("probe.SwV1$1");
            out("repair.switchMap.afterResize", switchMapInfo("probe.SwV1$1"));
            probe("repair.switch.sw(RED).afterResize", () -> SwV1.sw(Color.RED));
            probe("repair.switch.sw(blue).afterResize", () -> SwV1.sw(blue));
            probe("repair.switch.sw(blue).afterResize.mapped3", () -> {
                setSwitchMapSlot("probe.SwV1$1", 2, 3);
                return "slot2=" + switchMapInfoRaw("probe.SwV1$1") + " sw(blue)=" + SwV1.sw(blue);
            });
        }
    }

    /** Warm values()/switch 1e6x, then redefine+repair while a hot spinner observes. */
    static void jitSpin(byte[] v2) throws Throwable {
        preCache();
        long warm = 1_000_000L;
        long sink = 0;
        long t0 = System.nanoTime();
        for (long i = 0; i < warm; i++) {
            sink += hotLen();
            sink += hotSwitch();
        }
        long t1 = System.nanoTime();
        out("jit.warm.iterations", warm);
        out("jit.warm.sink", sink);
        out("jit.warm.ms", (t1 - t0) / 1_000_000);
        out("jit.warm.hotLen", hotLen());

        Thread spinner = new Thread(() -> {
            int last = -1;
            long n = 0;
            while (!SPIN_STOP.get()) {
                int len = hotLen();
                if (len != last) {
                    SPIN_SEEN.add(len);
                    last = len;
                }
                n++;
                if ((n & 0xFFFFFL) == 0) {
                    Thread.onSpinWait();
                }
            }
            out("jit.spin.iterations", n);
        }, "spinner");
        spinner.setDaemon(true);
        spinner.start();
        Thread.sleep(400);
        out("jit.before.repair.spinnerSeen", SPIN_SEEN.toString());

        redefine(v2);
        final Color blue = newConstant("BLUE", 2);
        unsafePutStatic("BLUE", blue);
        rebuildValues(blue);
        clearCaches(true, true);
        out("jit.after.repair.rawValuesLen", getRawValuesLen());
        out("jit.after.repair.valuesLen", Color.values().length);
        out("jit.after.repair.hotLen", hotLen());
        out("jit.after.repair.coldLen", coldLen());
        for (int i = 0; i < 5; i++) {
            out("jit.after.repair.hotLen.sample" + i, hotLen());
            Thread.sleep(100);
        }
        out("jit.after.repair.mhValuesLen", mhValuesLen());
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !SPIN_SEEN.contains(3)) {
            Thread.sleep(10);
        }
        out("jit.after.repair.spinnerSeen", SPIN_SEEN.toString());
        out("jit.after.repair.spinnerObservedNewLen", SPIN_SEEN.contains(3) ? "YES" : "NO");
        out("jit.after.repair.hotLen.final", hotLen());
        // force a full deopt of code that depends on Color by redefining the same bytes again
        redefine(v2);
        out("jit.after.forcedDeopt.hotLen", hotLen());
        out("jit.after.forcedDeopt.valuesLen", Color.values().length);
        long deadline2 = System.currentTimeMillis() + 1500;
        boolean observed3b = SPIN_SEEN.contains(3);
        while (System.currentTimeMillis() < deadline2 && !observed3b) {
            Thread.sleep(10);
            observed3b = SPIN_SEEN.contains(3);
        }
        out("jit.after.forcedDeopt.spinnerSeen", SPIN_SEEN.toString());
        SPIN_STOP.set(true);
        spinner.join(3000);
        out("jit.after.repair.hotSwitch(blue)", hotSwitchOf(blue));
    }

    /**
     * Direct deopt experiments: after the repair, can already-compiled readers be forced to see the
     * new {@code $VALUES} without redefining the enum class again? One mechanism per JVM.
     */
    static void jitDeopt(byte[] v2, String mode) throws Throwable {
        preCache();
        long sink = 0;
        for (long i = 0; i < 1_000_000L; i++) {
            sink += hotLen();
        }
        out("deopt.mode", mode);
        out("deopt.warm.sink", sink);
        out("deopt.warm.hotLen", hotLen());

        redefine(v2);
        final Color blue = newConstant("BLUE", 2);
        unsafePutStatic("BLUE", blue);
        rebuildValues(blue);
        clearCaches(true, true);

        out("deopt.after.repair.rawValuesLen", getRawValuesLen());
        out("deopt.after.repair.hotLen", hotLen());
        out("deopt.after.repair.valuesLen", Color.values().length);
        out("deopt.after.repair.newlyCompiledLen", newlyCompiledLen());

        switch (mode) {
            case "unsafe-only" -> out("deopt.action", "none (Unsafe write only, no deopt requested)");
            case "redefine-reader" -> {
                byte[] bytes = classBytes(Probe.class);
                ProbeAgent.INST.redefineClasses(new ClassDefinition(Probe.class, bytes));
                out("deopt.action", "redefineClasses(Probe, sameBytes) size=" + bytes.length);
            }
            case "retransform-reader" -> {
                try {
                    ProbeAgent.INST.retransformClasses(Probe.class);
                    out("deopt.action", "retransformClasses(Probe) ok");
                } catch (Throwable t) {
                    out("deopt.action", "retransformClasses(Probe) THREW " + describe(t));
                }
            }
            case "retransform-enum" -> {
                try {
                    ProbeAgent.INST.retransformClasses(Color.class);
                    out("deopt.action", "retransformClasses(Color) ok");
                } catch (Throwable t) {
                    out("deopt.action", "retransformClasses(Color) THREW " + describe(t));
                }
            }
            case "redefine-enum" -> {
                redefine(v2);
                out("deopt.action", "redefineClasses(Color, v2) again");
            }
            default -> throw new IllegalArgumentException(mode);
        }

        for (int i = 0; i < 5; i++) {
            final int n = i;
            probe("deopt.after." + n + ".hotLen", () -> hotLen());
            Thread.sleep(100);
        }
        out("deopt.after.hotLen.final", hotLen());
        out("deopt.after.valuesLen", Color.values().length);
        out("deopt.after.newlyCompiledLen", newlyCompiledLen());
        probe("deopt.after.BlueReader.read", () -> readBlueReader("read"));
    }

    /**
     * Does the JIT inline {@code Color.values()} (and thus the {@code $VALUES} load) into a hot reader?
     * Run with {@code -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining} to capture the inline decision;
     * the numeric readings compare warmed vs. newly-compiled readers after the repair.
     */
    static void jitInline(byte[] v2) throws Throwable {
        preCache();
        long sink = 0;
        for (long i = 0; i < 1_000_000L; i++) {
            sink += hotLen();
        }
        out("inline.warm.sink", sink);
        out("inline.warm.hotLen", hotLen());

        redefine(v2);
        final Color blue = newConstant("BLUE", 2);
        unsafePutStatic("BLUE", blue);
        rebuildValues(blue);
        clearCaches(true, true);

        out("inline.after.repair.rawValuesLen", getRawValuesLen());
        out("inline.after.repair.hotLen", hotLen());
        out("inline.after.repair.newlyCompiledLen", newlyCompiledLen());
        out("inline.after.repair.valuesLen", Color.values().length);

        probe("inline.varHandle.$VALUES.write", () -> {
            VarHandle vh = MethodHandles.privateLookupIn(Color.class, MethodHandles.lookup())
                    .findStaticVarHandle(Color.class, "$VALUES", Color[].class);
            vh.set(Color.values());
            return "VarHandle.set accepted";
        });
        out("inline.after.varHandle.hotLen", hotLen());
        out("inline.after.varHandle.valuesLen", Color.values().length);
        out("inline.after.repair.newlyCompiledLen2", newlyCompiledLen());
    }

    /**
     * Real hot-swap stale-reader case, but the refresh is a direct deopt of the *reader* class
     * (redefineClasses with identical bytes) instead of redefining the enum class a second time.
     */
    static void jitSpinReaderDeopt(byte[] v2) throws Throwable {
        preCache();
        long sink = 0;
        for (long i = 0; i < 1_000_000L; i++) {
            sink += hotLen();
            sink += hotSwitch();
        }
        out("spj.warm.sink", sink);
        out("spj.warm.hotLen", hotLen());

        Thread spinner = new Thread(() -> {
            int last = -1;
            long n = 0;
            while (!SPIN_STOP.get()) {
                int len = hotLen();
                if (len != last) {
                    SPIN_SEEN.add(len);
                    last = len;
                }
                n++;
            }
            out("spj.spin.iterations", n);
        }, "spinner");
        spinner.setDaemon(true);
        spinner.start();
        Thread.sleep(400);

        redefine(v2);
        final Color blue = newConstant("BLUE", 2);
        unsafePutStatic("BLUE", blue);
        rebuildValues(blue);
        clearCaches(true, true);
        out("spj.after.repair.hotLen", hotLen());
        out("spj.after.repair.spinnerSeen", SPIN_SEEN.toString());

        byte[] bytes = classBytes(Probe.class);
        ProbeAgent.INST.redefineClasses(new ClassDefinition(Probe.class, bytes));
        out("spj.readerDeopt", "redefineClasses(Probe, sameBytes) size=" + bytes.length);
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !SPIN_SEEN.contains(3)) {
            Thread.sleep(10);
        }
        out("spj.after.readerDeopt.hotLen", hotLen());
        out("spj.after.readerDeopt.spinnerSeen", SPIN_SEEN.toString());
        out("spj.after.readerDeopt.spinnerObservedNewLen", SPIN_SEEN.contains(3) ? "YES" : "NO");
        SPIN_STOP.set(true);
        spinner.join(3000);
    }

    /**
     * Isolates the JIT treatment of the {@code $VALUES} field itself (no enum redefine in this JVM):
     * warm a reader against a 3-constant enum, then replace the static final array with a 4-element one
     * through Unsafe. A stale reading means the compiled code hoisted/inlined the {@code getstatic};
     * afterwards we force a deopt of just the reader class and see whether a fresh load happens.
     * Run with {@code -cp probe:v2} (the classpath enum already has three constants).
     */
    static void jitValuesWrite(byte[] ignored) throws Throwable {
        long sink = 0;
        for (long i = 0; i < 1_000_000L; i++) {
            sink += hotLen();
        }
        out("vwarm.sink", sink);
        out("vwarm.hotLen", hotLen());
        out("vwarm.valuesLen", Color.values().length);
        out("vwarm.rawLen", getRawValuesLen());

        Object old = unsafeStatic("$VALUES");
        int n = Array.getLength(old);
        Object bigger = Array.newInstance(Color.class, n + 1);
        for (int i = 0; i < n; i++) {
            Array.set(bigger, i, Array.get(old, i));
        }
        Array.set(bigger, n, Array.get(old, 0));
        unsafePutStatic("$VALUES", bigger);
        out("vwarm.afterWrite.rawLen", getRawValuesLen());
        out("vwarm.afterWrite.oldArrayLen", n);

        for (int i = 0; i < 5; i++) {
            final int k = i;
            probe("vwrite.hotLen.sample" + k, () -> hotLen());
            Thread.sleep(100);
        }
        probe("vwrite.warmValuesLen", () -> Color.values().length);
        out("vwrite.newlyCompiledLen", newlyCompiledLen());
        probe("vwrite.VarHandle.write", () -> {
            VarHandle vh = MethodHandles.privateLookupIn(Color.class, MethodHandles.lookup())
                    .findStaticVarHandle(Color.class, "$VALUES", Color[].class);
            vh.set(Color.values());
            return "VarHandle.set accepted";
        });

        // Direct deopt of the reader class only: redefineClasses with identical bytes.
        byte[] bytes = classBytes(Probe.class);
        ProbeAgent.INST.redefineClasses(new ClassDefinition(Probe.class, bytes));
        out("vdeopt.action", "redefineClasses(Probe, sameBytes) size=" + bytes.length);
        for (int i = 0; i < 5; i++) {
            final int k = i;
            probe("vdeopt.hotLen.sample" + k, () -> hotLen());
            Thread.sleep(100);
        }
        out("vdeopt.valuesLen", Color.values().length);
        out("vdeopt.newlyCompiledLen", newlyCompiledLen());

        // Retransform is the other public deopt switch; measure on the enum class itself.
        try {
            ProbeAgent.INST.retransformClasses(Color.class);
            out("vdeopt.retransformColor", "ok");
        } catch (Throwable t) {
            out("vdeopt.retransformColor", "THREW " + describe(t));
        }
        out("vdeopt.afterRetransformColor.hotLen", hotLen());
        out("vdeopt.afterRetransformColor.valuesLen", Color.values().length);
    }

    /**
     * Does Stage C (a second redefine of the host class, same schema) act as a deopt barrier for
     * readers of a rewritten static final reference field?
     * Warm a getstatic reader (Probe.hotBlue) and a separate direct reader class (BlueReader.read),
     * swap Color.BLUE to Color.GREEN via Unsafe, then redefine Color again (Stage C analogue).
     * Run with {@code -cp probe:v2}.
     */
    static void stagecStatic(byte[] v2) throws Throwable {
        Color.values();
        Object blueConst = unsafeStatic("BLUE");
        out("sc.blueConstAtWarm", blueConst);
        long same = 0;
        for (long i = 0; i < 1_000_000L; i++) {
            if (hotBlue() == blueConst) same++;
            readBlueReader("read");
        }
        out("sc.warm.hotBlueIsBlue", same + "/1000000");
        out("sc.warm.blueReader", readBlueReader("read"));

        unsafePutStatic("BLUE", Color.GREEN);
        out("sc.afterWrite.unsafeBLUE", unsafeStatic("BLUE"));
        out("sc.afterWrite.hotBlue", hotBlue());
        out("sc.afterWrite.blueReader", readBlueReader("read"));
        for (int i = 0; i < 3; i++) {
            out("sc.afterWrite.sample" + i, hotBlue() + " / " + readBlueReader("read"));
            Thread.sleep(100);
        }

        // Stage C analogue: redefine the host class again (same schema, same methods).
        redefine(v2);
        out("sc.afterStageC.hotBlue", hotBlue());
        out("sc.afterStageC.blueReader", readBlueReader("read"));
        out("sc.afterStageC.blueReaderCompiled", readBlueReader("read"));

        // Control: deopt the direct reader class itself.
        ProbeAgent.INST.redefineClasses(new ClassDefinition(
                Class.forName("probe.BlueReader"), classBytes(Class.forName("probe.BlueReader"))));
        out("sc.afterReaderDeopt.blueReader", readBlueReader("read"));
        out("sc.afterReaderDeopt.hotBlue", hotBlue());
    }

    static byte[] classBytes(Class<?> type) throws Throwable {
        String res = "/" + type.getName().replace('.', '/') + ".class";
        try (java.io.InputStream in = type.getResourceAsStream(res)) {
            if (in == null) {
                throw new IllegalStateException("class bytes not found: " + res);
            }
            return in.readAllBytes();
        }
    }

    /** First call to this method happens after the repair, so it is compiled after the new $VALUES. */
    static int newlyCompiledLen() {
        return Color.values().length;
    }

    /**
     * JIT staleness of the *constant field itself*: warm compiled code that reads Color.BLUE
     * (null) 1e6 times, then Unsafe-write the new constant, then check whether warm code sees it.
     */
    static void jitField(byte[] v2) throws Throwable {
        preCache();
        redefine(v2);
        final Color blue = newConstant("BLUE", 2);
        long nulls = 0;
        for (long i = 0; i < 1_000_000; i++) {
            if (hotBlue() == null) {
                nulls++;
            }
            hotBlueOrdinal();
        }
        out("jitfield.warm.iterations", "1000000, nullCount=" + nulls);
        out("jitfield.before.write.hotBlue", hotBlue());

        unsafePutStatic("BLUE", blue);
        rebuildValues(blue);
        clearCaches(true, true);
        out("jitfield.after.write.unsafeRead", unsafeStatic("BLUE"));
        for (int i = 0; i < 5; i++) {
            final int n = i;
            probe("jitfield.after.write.hotBlue.sample" + n, () -> "value=" + hotBlue() + " ordinal=" + hotBlueOrdinal());
            Thread.sleep(100);
        }
        probe("jitfield.after.write.BlueReaderDirect", () -> BlueReader.read() + " / " + BlueReader.ordinal());
        redefine(v2);
        probe("jitfield.after.forcedDeopt.hotBlue", () -> "value=" + hotBlue() + " ordinal=" + hotBlueOrdinal());
    }

    static Object hotBlue() {
        return BlueReader.read();
    }

    static int hotBlueOrdinal() {
        try {
            return BlueReader.ordinal();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Cold contrast: no warmup at all, then redefine+repair. */
    static void jitCold(byte[] v2) throws Throwable {
        preCache();
        redefine(v2);
        final Color blue = newConstant("BLUE", 2);
        unsafePutStatic("BLUE", blue);
        rebuildValues(blue);
        clearCaches(true, true);
        out("jitcold.valuesLen", Color.values().length);
        out("jitcold.hotLen", hotLen());
    }

    /** Newly initialized switch consumer AFTER repair: expects to see 3 constants. */
    static void swv2After(byte[] v2) throws Throwable {
        preCache();
        redefine(v2);
        final Color blue = newConstant("BLUE", 2);
        unsafePutStatic("BLUE", blue);
        rebuildValues(blue);
        clearCaches(true, true);
        out("swv2.after.values", valuesInfo());
        out("swv2.after.switchMap", switchMapInfo("probe.SwV2$1"));
        probe("swv2.after.sw(RED)", () -> SwV2.sw(Color.RED));
        probe("swv2.after.sw(blue)", () -> SwV2.sw(blue));
    }

    /** New switch consumer initialized AFTER redefine but BEFORE repair (BLUE null). */
    static void swv2Before(byte[] v2) throws Throwable {
        preCache();
        redefine(v2);
        final Color blue = newConstant("BLUE", 2);
        probe("swv2.before.sw(blue)", () -> SwV2.sw(blue));
        probe("swv2.before.retry", () -> Class.forName("probe.SwV2").getName());
    }

    /** Can DCEVM add a brand-new constructor descriptor and can it be invoked reflectively? */
    static void ctorScenario(byte[] v2) throws Throwable {
        preCache();
        out("ctor.before.constructors", Arrays.toString(Color.class.getDeclaredConstructors()));
        redefine(v2);
        out("ctor.after.constructors", Arrays.toString(Color.class.getDeclaredConstructors()));
        probe("ctor.getDeclaredConstructor(String,int,int)", () -> Color.class.getDeclaredConstructor(String.class, int.class, int.class));
        probe("ctor.mh.linkable", () -> {
            MethodHandles.Lookup lk = MethodHandles.privateLookupIn(Color.class, MethodHandles.lookup());
            return lk.findConstructor(Color.class,
                    MethodType.methodType(void.class, String.class, int.class, int.class));
        });
        probe("ctor.mh.invoke", () -> {
            MethodHandles.Lookup lk = MethodHandles.privateLookupIn(Color.class, MethodHandles.lookup());
            MethodHandle mh = lk.findConstructor(Color.class,
                    MethodType.methodType(void.class, String.class, int.class, int.class));
            mh.invoke("BLUE", 2, 7);
            return "invoked ok (ctor MH returns void: instance itself is not capturable this way)";
        });
        probe("ctor.reflect.newInstance", () -> {
            Constructor<Color> c = Color.class.getDeclaredConstructor(String.class, int.class, int.class);
            c.setAccessible(true);
            Color x = c.newInstance("BLUE", 2, 7);
            return "name=" + x.name() + " code()=" + x.getClass().getMethod("code").invoke(x);
        });
        probe("ctor.allocateInstance+code", () -> {
            Color x = (Color) U.allocateInstance(Color.class);
            U.putReference(x, NAME_OFF, "BLUE");
            U.putInt(x, ORDINAL_OFF, 2);
            return "name=" + x.name() + " ordinal=" + x.ordinal()
                    + " code=" + x.getClass().getMethod("code").invoke(x)
                    + " (per-constant state is NOT reconstructed by allocateInstance)";
        });
        probe("ctor.oldCtor(String,int)", () -> {
            Constructor<Color> c = Color.class.getDeclaredConstructor(String.class, int.class);
            c.setAccessible(true);
            Color x = c.newInstance("X", 5);
            return "name=" + x.name() + " ordinal=" + x.ordinal();
        });
    }

    /** Constant-specific class body: can a synthetic subclass be defined and instantiated? */
    static void bodyScenario(byte[] v2, byte[] color1Bytes) throws Throwable {
        if (color1Bytes == null) {
            out("body.error", "missing Color$1 bytes argument");
            return;
        }
        preCache();
        redefine(v2);
        out("body.nestMembers", Arrays.toString(Color.class.getNestMembers()));
        MethodHandles.Lookup lk = MethodHandles.privateLookupIn(Color.class, MethodHandles.lookup());
        final Class<?> c1 = lk.defineClass(color1Bytes);
        out("body.defined", c1.getName() + " nestHost=" + c1.getNestHost().getName()
                + " super=" + c1.getSuperclass().getName()
                + " flags=" + Modifier.toString(c1.getModifiers()));
        out("body.defined.constructors", Arrays.toString(c1.getDeclaredConstructors()));
        probe("body.materialize", () -> {
            Object o = allocateEnum(c1, "BLUE", 2);
            Method decl = Enum.class.getMethod("getDeclaringClass");
            return "tag=" + ((Color) o).tag()
                    + " declaringClass=" + decl.invoke(o)
                    + " instanceOfColor=" + (o instanceof Color)
                    + " name=" + ((Enum<?>) o).name()
                    + " ordinal=" + ((Enum<?>) o).ordinal();
        });
        probe("body.materialize.intoColor", () -> {
            Object o = allocateEnum(c1, "BLUE", 2);
            unsafePutStatic("BLUE", o);
            rebuildValues((Color) o);
            clearCaches(true, true);
            return "values=" + valuesInfo()
                    + " valueOf(BLUE)=" + Color.valueOf("BLUE")
                    + " blueTag=" + ((Color) unsafeStatic("BLUE")).tag()
                    + " blueClass=" + ((Color) unsafeStatic("BLUE")).getClass().getName();
        });
    }

    static Object allocateEnum(Class<?> enumType, String name, int ordinal) throws Throwable {
        Object o = U.allocateInstance(enumType);
        U.putReference(o, NAME_OFF, name);
        U.putInt(o, ORDINAL_OFF, ordinal);
        return o;
    }

    // =====================================================================================
    // repair primitives
    // =====================================================================================

    static void preCache() throws Throwable {
        out("pre.values", valuesInfo());
        probe("pre.valueOf.RED", () -> Color.valueOf("RED"));
        probe("pre.getEnumConstants", () -> Color.class.getEnumConstants());
        out("pre.cache.enumConstants", cacheConstants());
        out("pre.cache.enumConstantDirectory", cacheDirectory());
        CLASS_BEFORE = Color.class;
        CONSTS_BEFORE = cacheConstants();
        DIR_BEFORE = cacheDirectory();
        out("pre.class.identity", "saved Color.class object");
        EM0 = new EnumMap<>(Color.class);
        EM0.put(Color.RED, "r");
        EM0.put(Color.GREEN, "g");
        out("pre.EnumMap", "size=" + EM0.size());
        ES0_ALL = EnumSet.allOf(Color.class);
        ES0_SOME = EnumSet.of(Color.RED);
        out("pre.EnumSet.allOf", "size=" + ES0_ALL.size() + " type=" + ES0_ALL.getClass().getSimpleName());
        out("pre.EnumSet.some", "size=" + ES0_SOME.size());
        out("pre.switch.RED", SwV1.sw(Color.RED));
        out("pre.switchMap", switchMapInfo("probe.SwV1$1"));
    }

    static void redefine(byte[] v2) throws Throwable {
        if (ProbeAgent.INST == null) {
            throw new IllegalStateException("agent not installed (-javaagent missing)");
        }
        ProbeAgent.INST.redefineClasses(new java.lang.instrument.ClassDefinition(Color.class, v2));
        out("redefine.status", "ACCEPTED by redefineClasses");
    }

    static void rebuildValues(Color blue) throws Throwable {
        try {
            Method m = Color.class.getDeclaredMethod("$values");
            m.setAccessible(true);
            Object nw = m.invoke(null);
            out("repair.values.rebuild", "via synthetic $values() len=" + Array.getLength(nw));
            unsafePutStatic("$VALUES", nw);
        } catch (NoSuchMethodException e) {
            Object old = Color.values();
            int n = Array.getLength(old);
            Object nw = Array.newInstance(Color.class, n + 1);
            System.arraycopy(old, 0, nw, 0, n);
            Array.set(nw, n, blue);
            out("repair.values.rebuild", "manual (no $values()) oldLen=" + n + " newLen=" + (n + 1));
            unsafePutStatic("$VALUES", nw);
        }
    }

    static void clearCaches(boolean consts, boolean dir) {
        if (consts) {
            clearOne(F_CONSTANTS, "enumConstants");
        }
        if (dir) {
            clearOne(F_DIRECTORY, "enumConstantDirectory");
        }
        out("cache.enumConstants.after", cacheConstants());
        out("cache.enumConstantDirectory.after", cacheDirectory());
    }

    static void clearOne(Field f, String name) {
        if (f == null) {
            out("cache." + name + ".write", "FIELD NOT FOUND");
            return;
        }
        try {
            f.setAccessible(true);
            f.set(Color.class, null);
            out("cache." + name + ".write", "reflective Field.set(null) ok");
            return;
        } catch (Throwable t) {
            out("cache." + name + ".write", "reflective FAILED: " + describe(t));
        }
        try {
            long off = U.objectFieldOffset(f);
            U.putReferenceVolatile(Color.class, off, null);
            out("cache." + name + ".write", "Unsafe.putObjectVolatile ok");
        } catch (Throwable t) {
            out("cache." + name + ".write", "Unsafe FAILED: " + describe(t));
        }
    }

    // =====================================================================================
    // measurement helpers
    // =====================================================================================

    static Object readBlueReader(String method) throws Throwable {
        Class<?> br = Class.forName("probe.BlueReader");
        return br.getMethod(method).invoke(null);
    }

    static Object cacheConstants() {
        if (F_CONSTANTS == null) {
            return "FIELD NOT FOUND";
        }
        try {
            F_CONSTANTS.setAccessible(true);
            return F_CONSTANTS.get(Color.class);
        } catch (Throwable t) {
            return "ERR " + describe(t);
        }
    }

    static Object cacheDirectory() {
        if (F_DIRECTORY == null) {
            return "FIELD NOT FOUND";
        }
        try {
            F_DIRECTORY.setAccessible(true);
            Map<?, ?> m = (Map<?, ?>) F_DIRECTORY.get(Color.class);
            return m == null ? null : ("Map(size=" + m.size() + ")");
        } catch (Throwable t) {
            return "ERR " + describe(t);
        }
    }

    static int hotLen() {
        return Color.values().length;
    }

    static int coldLen() {
        return Color.values().length;
    }

    static int mhValuesLen() throws Throwable {
        MethodHandle mh = MethodHandles.publicLookup().findStatic(Color.class, "values",
                MethodType.methodType(Color[].class));
        return ((Color[]) mh.invoke()).length;
    }

    static int getRawValuesLen() throws Throwable {
        return Array.getLength(unsafeStatic("$VALUES"));
    }

    static long hotSwitch() {
        return SwV1.sw(Color.RED) + SwV1.sw(Color.GREEN);
    }

    static String hotSwitchOf(Color c) {
        try {
            return String.valueOf(SwV1.sw(c));
        } catch (Throwable t) {
            return "THREW " + describe(t);
        }
    }

    static Object mhStatic(String name) throws Throwable {
        MethodHandle mh = MethodHandles.publicLookup().findStaticGetter(Color.class, name, Color.class);
        return mh.invoke();
    }

    static Object reflectStatic(String name) throws Throwable {
        Field f = Color.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    static Object unsafeStatic(String name) throws Throwable {
        Field f = Color.class.getDeclaredField(name);
        f.setAccessible(true);
        return U.getReference(U.staticFieldBase(f), U.staticFieldOffset(f));
    }

    static void unsafePutStatic(String name, Object value) throws Throwable {
        Field f = Color.class.getDeclaredField(name);
        f.setAccessible(true);
        U.putReference(U.staticFieldBase(f), U.staticFieldOffset(f), value);
    }

    static long NAME_OFF;
    static long ORDINAL_OFF;

    /**
     * Reflection cannot construct enum constants ({@code Constructor.newInstance} throws
     * "Cannot reflectively create enum objects"), so materialize one the way serializers do:
     * allocate without running a constructor, then write the Enum base state ({@code name},
     * {@code ordinal}) exactly like {@code Enum.<init>} would. Per-constant fields of enums that
     * declare their own state are NOT filled here (that is an extra hazard, reported separately).
     */
    static Color newConstant(String name, int ordinal) throws Throwable {
        Color o = (Color) U.allocateInstance(Color.class);
        U.putReference(o, NAME_OFF, name);
        U.putInt(o, ORDINAL_OFF, ordinal);
        return o;
    }

    static String valuesInfo() {
        Color[] v = Color.values();
        StringBuilder sb = new StringBuilder("len=").append(v.length).append(" names=");
        for (Color c : v) {
            sb.append(c == null ? "null" : c.name()).append(',');
        }
        return sb.toString();
    }

    static String switchMapInfo(String holderName) throws Throwable {
        Class<?> h = Class.forName(holderName);
        Field f = h.getDeclaredField("$SwitchMap$test$Color");
        f.setAccessible(true);
        int[] arr = (int[]) f.get(null);
        return "holder=" + h.getName()
                + " fieldFlags=[" + Modifier.toString(f.getModifiers()) + "]"
                + " len=" + arr.length
                + " vals=" + Arrays.toString(arr);
    }

    static String switchMapInfoRaw(String holderName) throws Throwable {
        Class<?> h = Class.forName(holderName);
        Field f = h.getDeclaredField("$SwitchMap$test$Color");
        f.setAccessible(true);
        int[] arr = (int[]) f.get(null);
        return Arrays.toString(arr);
    }

    static void resizeSwitchMap(String holderName) throws Throwable {
        Class<?> h = Class.forName(holderName);
        Field f = h.getDeclaredField("$SwitchMap$test$Color");
        f.setAccessible(true);
        int[] old = (int[]) f.get(null);
        int[] nw = Arrays.copyOf(old, old.length + 1);
        U.putReference(U.staticFieldBase(f), U.staticFieldOffset(f), nw);
    }

    static void setSwitchMapSlot(String holderName, int slot, int value) throws Throwable {
        Class<?> h = Class.forName(holderName);
        Field f = h.getDeclaredField("$SwitchMap$test$Color");
        f.setAccessible(true);
        int[] arr = (int[]) f.get(null);
        arr[slot] = value;
    }

    static Unsafe unsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (Throwable t) {
            throw new AssertionError("cannot access jdk.internal.misc.Unsafe.theUnsafe", t);
        }
    }

    static {
        try {
            NAME_OFF = U.objectFieldOffset(Enum.class.getDeclaredField("name"));
            ORDINAL_OFF = U.objectFieldOffset(Enum.class.getDeclaredField("ordinal"));
        } catch (Throwable t) {
            throw new AssertionError("cannot resolve Enum.name/ordinal offsets", t);
        }
        try {
            F_CONSTANTS = Class.class.getDeclaredField("enumConstants");
        } catch (Throwable ignored) {
            F_CONSTANTS = null;
        }
        try {
            F_DIRECTORY = Class.class.getDeclaredField("enumConstantDirectory");
        } catch (Throwable ignored) {
            F_DIRECTORY = null;
        }
    }

    static void out(String k, Object v) {
        System.out.println("OBS " + k + " = " + render(v));
    }

    static void probe(String key, Throwing r) {
        try {
            out(key, r.run());
        } catch (Throwable t) {
            out(key, "THREW " + describe(t));
        }
    }

    static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        int depth = 0;
        while (c != null && depth++ < 6) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(c.getClass().getName());
            if (c.getMessage() != null) {
                sb.append(": ").append(c.getMessage());
            }
            c = c.getCause();
        }
        return sb.toString();
    }

    static String render(Object v) {
        if (v == null) {
            return "null";
        }
        Class<?> t = v.getClass();
        if (t.isArray()) {
            int n = Array.getLength(v);
            StringBuilder sb = new StringBuilder("array(len=").append(n).append(')');
            if (n > 0 && n <= 16) {
                sb.append('[');
                for (int i = 0; i < n; i++) {
                    sb.append(render(Array.get(v, i))).append(',');
                }
                sb.append(']');
            }
            return sb.toString();
        }
        if (v instanceof Class) {
            return "class " + ((Class<?>) v).getName();
        }
        if (v instanceof Enum) {
            Enum<?> e = (Enum<?>) v;
            return "enum[" + e.name() + "#" + e.ordinal() + "]";
        }
        if (v instanceof EnumMap) {
            return "EnumMap(size=" + ((EnumMap<?, ?>) v).size() + ")";
        }
        if (v instanceof EnumSet) {
            return "EnumSet(size=" + ((EnumSet<?>) v).size() + ")";
        }
        if (v instanceof Map) {
            return "Map(size=" + ((Map<?, ?>) v).size() + ")";
        }
        if (v instanceof Set) {
            return "Set(size=" + ((Set<?>) v).size() + ")";
        }
        if (v instanceof List) {
            return v.toString();
        }
        return String.valueOf(v);
    }
}
