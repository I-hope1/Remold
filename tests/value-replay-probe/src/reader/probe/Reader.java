package probe;

/**
 * Hot readers. One reads the host instance field ({@code Host.v.y()}), one reads the static final
 * target ({@code StaticHost.SV.y()}); both are compiled by C1/C2 before any write so the assertions
 * exercise already-compiled code, not the interpreter. The v2 initializer changes the {@code y}
 * argument (4 -&gt; 12), so {@code y} is the component the write must make visible.
 */
public final class Reader {
    private Reader() {
    }

    public static int read(Host h) {
        return h.v.y();
    }

    public static int readX(Host h) {
        return h.v.x();
    }

    public static Vec readVec(Host h) {
        return h.v;
    }

    public static int readStatic() {
        return StaticHost.SV.y();
    }

    public static Vec readStaticVec() {
        return StaticHost.SV;
    }
}
