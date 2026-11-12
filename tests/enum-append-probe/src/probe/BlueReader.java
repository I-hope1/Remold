package probe;

import test.Color;

/**
 * Compiled against v2 bytes, so its bytecode contains {@code GETSTATIC test/Color.BLUE}.
 * Must only be loaded AFTER the redefine (v1 Color has no BLUE field).
 */
public final class BlueReader {

    private BlueReader() {
    }

    public static Object read() {
        return Color.BLUE;
    }

    public static String name() {
        return Color.BLUE.name();
    }

    public static int ordinal() {
        return Color.BLUE.ordinal();
    }
}
