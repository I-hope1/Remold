package probe;

/** v2 host: same schema, the instance field initializer argument changes 4 -> 12. */
public class Host {
    public final Vec v = new Vec(4, 12);

    public Vec v() {
        return v;
    }

    public String stamp() {
        return "v2";
    }
}
