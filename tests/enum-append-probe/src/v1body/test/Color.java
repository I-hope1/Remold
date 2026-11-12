package test;

/** v1 with a body-only change (same constants, different tag body). Control for cache clearing. */
public enum Color {
    RED, GREEN;

    public String tag() {
        return "v1-body-only";
    }
}
