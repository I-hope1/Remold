package test;

/**
 * v2 for the "new constructor" test: adds {@code Color(int)} in addition to
 * {@code Color()}, so the class gains a brand new ctor descriptor
 * {@code (Ljava/lang/String;II)V} that does not exist in v1.
 */
public enum Color {
    RED, GREEN, BLUE(3);

    private int code;

    Color() {
        this.code = 0;
    }

    Color(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public String tag() {
        return "v2ctor";
    }
}
