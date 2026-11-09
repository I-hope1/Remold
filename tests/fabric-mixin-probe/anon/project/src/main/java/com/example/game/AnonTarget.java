package com.example.game;

import java.util.function.Supplier;

/**
 * Plain (non-Mixin) business class. Its {@code poke()} value is the observed end
 * result: {@code AnonTargetMixin} cancels the call at HEAD and returns the value
 * produced by its own code, so the returned string tells which version of the
 * Mixin handler (and of the handler's anonymous class) is really loaded.
 */
public class AnonTarget {

    /**
     * S4: long-lived instance of the anonymous class the Mixin handler allocates.
     *
     * <p>The handler creates it exactly once ({@code new} inside the handler body)
     * and stores it here, so the object outlives the call that created it. S4b then
     * changes only the anonymous body: the value read back through this field tells
     * whether that live instance was really redefined in place or still runs the
     * old code (orphaned anonymous class).</p>
     */
    public static Supplier<String> HELD4;

    /**
     * S6: same idea, but the anonymous class is renumbered {@code $1 -> $2} by a
     * second, structurally identical anonymous class inserted in front of it.
     */
    public static Supplier<String> HELD6;

    /** Written by the un-woven class body; never observed once the Mixin is applied. */
    public String poke() {
        return "target-body";
    }
}
