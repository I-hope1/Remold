package com.example.game;

/**
 * Plain (non-Mixin) business class. Its {@code poke()} value is the observed end
 * result: {@code AnonTargetMixin} cancels the call at HEAD and returns the value
 * produced by its own code, so the returned string tells which version of the
 * Mixin handler (and of the handler's anonymous class) is really loaded.
 */
public class AnonTarget {

    /** Written by the un-woven class body; never observed once the Mixin is applied. */
    public String poke() {
        return "target-body";
    }
}
