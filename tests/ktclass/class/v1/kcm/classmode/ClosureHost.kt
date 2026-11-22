package kcm.classmode

import java.util.function.Supplier

/**
 * SAM-off ("class mode") fixture, v1.
 *
 * Compiled with `-Xsam-conversions=class`: each `Supplier { ... }` SAM conversion is a real class
 * file, emitted with the same `Host$method$N` naming family as a Kotlin anonymous object (both are
 * anonymous classes in the bytecode sense: `InnerClasses` with `innerName = null`).
 *
 * The closures are passed as call arguments rather than assigned to locals, so the compiler uses
 * plain per-method numbering instead of a local-name hint — that is what makes the v2 pairing
 * genuinely ambiguous when only the labels change.
 *
 * `slots` exists in both versions and keeps every live closure instance reachable, so the probe can
 * read the surviving objects without re-running the factory.
 */
class ClosureHost {

    @JvmField
    var slots: MutableList<Supplier<String>> = ArrayList()

    fun metricTable(): String {
        slots.clear()
        slots.add(Supplier { "v1-header" })
        slots.add(Supplier { "v1-body" })
        val sb = StringBuilder("t1")
        for (s in slots) sb.append(':').append(s.get())
        return sb.toString()
    }

    fun nestedTable(): String {
        slots.clear()
        slots.add(Supplier {
            slots.add(Supplier { "v1-nested-inner" })
            "v1-nested-outer:" + slots[slots.size - 1].get()
        })
        return slots[0].get()
    }

    fun alphaLabel(): String = slots[0].get()

    fun betaLabel(): String = slots[1].get()
}
