package probe;

/** v2 static-final host: the runtime initializer argument changes 4 -> 12. */
public class StaticHost {
    public static final Vec SV = new Vec(4, 12);

    public static Vec sv() {
        return SV;
    }

    public static String stamp() {
        return "v2";
    }
}
