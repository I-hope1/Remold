package test;

/** v2: same field schema, new method body. The field rewrite is done by the probe via Unsafe. */
public class Holder {
    public static final String VAL = new String("OLD");

    public static String tag() {
        return "v2";
    }
}
