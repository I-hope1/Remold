package com.example.evo;

/**
 * Item 2.6 scenario C target: the Mixin class itself gains and then loses an
 * {@code @Inject} handler method across two hot swaps (structural Mixin change).
 */
public class EvoLifecycleTarget {
    public String fire() {
        return "LIVE";
    }
}
