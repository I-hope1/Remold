package com.example.evo;

/**
 * Item 2.6 scenario B target: the Mixin moves its {@code @Inject} from
 * {@link #methodOne()} to {@link #methodTwo()} across a hot swap.
 */
public class EvoSwitchTarget {
    public String methodOne() {
        return "ONE";
    }

    public String methodTwo() {
        return "TWO";
    }
}
