package reader;

import test.Holder;

/** External hot readers: one direct GETSTATIC, one call through an inlinable host method. */
public final class Reader {
    private Reader() {
    }

    public static Object read() {
        return Holder.VAL;
    }

    public static String tag() {
        return Holder.tag();
    }
}
