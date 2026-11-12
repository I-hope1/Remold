package probe;

import test.Color;

/** Switch on enum compiled against v1 (cases RED, GREEN only). */
public final class SwV1 {

    private SwV1() {
    }

    public static int sw(Color c) {
        switch (c) {
            case RED:
                return 1;
            case GREEN:
                return 2;
            default:
                return 0;
        }
    }
}
