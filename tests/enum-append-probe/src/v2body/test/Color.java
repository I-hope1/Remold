package test;

/**
 * v2 for the "constant-specific class body" test: BLUE carries a class body,
 * so javac emits a synthetic subclass {@code test.Color$1} (and lists it in
 * Color's NestMembers).
 */
public enum Color {
    RED, GREEN,
    BLUE {
        @Override
        public String tag() {
            return "blue-body";
        }
    };

    public String tag() {
        return "v2body";
    }
}
