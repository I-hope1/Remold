package probe;

/**
 * v1 static-final host: the existing {@code static final} reference field is initialized at runtime
 * (in {@code <clinit>}, no {@code ConstantValue}), so it is a valid value-replay static target (A4).
 */
public class StaticHost {
    public static final Vec SV = new Vec(4, 4);

    public static Vec sv() {
        return SV;
    }

    public static String stamp() {
        return "v1";
    }
}
