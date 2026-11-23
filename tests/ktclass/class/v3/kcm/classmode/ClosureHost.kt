package kcm.classmode

import java.util.function.Supplier

/**
 * SAM-off ("class mode") fixture, v3: v2 plus one extra SAM conversion, so the class SET grows
 * (`...$metricTable$N$M` gains a member the old build never had).
 *
 * This is the shape that turns "host redefined, closures left behind" into a hard failure: the new
 * host bytecode references a closure class name that was never defined, so the next call to the
 * factory throws NoClassDefFoundError. The only safe outcome is again "whole host group out of this
 * batch + warn".
 */
class ClosureHost {

    @JvmField
    var slots: MutableList<Supplier<String>> = ArrayList()

    fun metricTable(): String {
        slots.clear()
        slots.add(Supplier { "v3-header" })
        slots.add(Supplier { "v3-body" })
        slots.add(Supplier { "v3-extra" })
        val sb = StringBuilder("t3")
        for (s in slots) sb.append(':').append(s.get())
        return sb.toString()
    }

    fun nestedTable(): String {
        slots.clear()
        slots.add(Supplier {
            slots.add(Supplier { "v3-nested-inner" })
            "v3-nested-outer:" + slots[slots.size - 1].get()
        })
        return slots[0].get()
    }

    fun alphaLabel(): String = slots[0].get()

    fun betaLabel(): String = slots[1].get()
}
