package test;

/** v1: a runtime-initialized static final reference field (no ConstantValue attribute) and its old initializer. */
public class GateHolder {
    private static final StringBuilder MSG = new StringBuilder("old");

    public static String msg() {
        return MSG.toString();
    }
}
