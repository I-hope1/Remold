package probe;

import test.Color;

/** Switch on enum compiled against v2 (cases RED, GREEN, BLUE). */
public final class SwV2 {

    private SwV2() {
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
