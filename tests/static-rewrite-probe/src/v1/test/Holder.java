package test;

/** v1: runtime-initialized static final reference (no ConstantValue attribute) + old body. */
public class Holder {
    public static final String VAL = new String("OLD");

    public static String tag() {
        return "v1";
    }
}
