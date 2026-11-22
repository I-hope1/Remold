package kcm.classmode

import java.util.function.Supplier

/**
 * SAM-off ("class mode") fixture, v2: the same class shape (same count and nesting of SAM
 * conversions), different labels only.
 *
 * Because the two top-level closures are topology-equal, the alignment stage cannot prove a unique
 * v1-to-v2 pairing. The only safe outcome is "whole host group out of this batch + warn"; a host
 * redefined together with stale closure bodies is a partial application.
 */
class ClosureHost {

    @JvmField
    var slots: MutableList<Supplier<String>> = ArrayList()

    fun metricTable(): String {
        slots.clear()
        slots.add(Supplier { "v2-header" })
        slots.add(Supplier { "v2-body" })
        val sb = StringBuilder("t2")
        for (s in slots) sb.append(':').append(s.get())
        return sb.toString()
    }

    fun nestedTable(): String {
        slots.clear()
        slots.add(Supplier {
            slots.add(Supplier { "v2-nested-inner" })
            "v2-nested-outer:" + slots[slots.size - 1].get()
        })
        return slots[0].get()
    }

    fun alphaLabel(): String = slots[0].get()

    fun betaLabel(): String = slots[1].get()
}
