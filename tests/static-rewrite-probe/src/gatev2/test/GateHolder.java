package test;

import remold.annotation.RemoldReinit;

/**
 * v2: the same field schema, but {@code @RemoldReinit(OVERWRITE)} asks InitFix to rewrite the existing
 * static final initializer. On a plain JVM (no {@code -XX:+AllowEnhancedClassRedefinition}) this rewrite
 * must be rejected per field; on JBR enhanced it must be accepted and applied.
 */
public class GateHolder {
    @RemoldReinit(mode = RemoldReinit.Mode.OVERWRITE)
    private static final StringBuilder MSG = new StringBuilder("new");

    public static String msg() {
        return MSG.toString();
    }
}
