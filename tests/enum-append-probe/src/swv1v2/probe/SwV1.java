package probe;

import test.Color;

/**
 * Same class name as {@link SwV1}, recompiled against v2 (cases RED, GREEN, BLUE).
 *
 * <p>javac names the synthetic {@code $SwitchMap$} holder {@code probe.SwV1$1} in both versions,
 * so redefining {@code probe.SwV1} with these bytes keeps the consumer wired to the same holder
 * field while its holder {@code <clinit>} (which fills the table) does not re-run.
 */
public final class SwV1 {

    private SwV1() {
    }

    public static int sw(Color c) {
        switch (c) {
            case RED:
                return 1;
            case GREEN:
                return 2;
            case BLUE:
                return 3;
            default:
                return 0;
        }
    }
}
