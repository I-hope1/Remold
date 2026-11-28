package probe;

/**
 * v1 host: the existing instance field initializer is a straight-line {@code new Vec(4, 4)}.
 * v2 changes only the constructor arguments (see {@code src/v2/probe/Host.java}).
 */
public class Host {
    public final Vec v = new Vec(4, 4);

    public Vec v() {
        return v;
    }

    public String stamp() {
        return "v1";
    }
}
