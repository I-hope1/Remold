package com.example.evo;

/**
 * Item 2.6 scenario A target: one method whose injected hook evolves from
 * non-cancellable to {@code cancellable = true} with a forced return value.
 */
public class EvoTarget {
    public String fire() {
        return "ORIGINAL";
    }
}
