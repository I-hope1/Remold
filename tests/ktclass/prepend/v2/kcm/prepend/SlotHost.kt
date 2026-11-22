package kcm.prepend

import java.util.function.Supplier

/**
 * Kotlin host-suffix anonymous-class fixture, v2: one extra anonymous object is *prepended* into
 * `metricTable()`, so the compiler's `$method$N` suffixes shift (`$1` was alpha in v1, is the new
 * header in v2; `$2` was beta, is alpha; `$3` is the new beta).
 *
 * A live v1 instance still holds objects of classes `SlotHost$metricTable$1` (alpha) and `$2`
 * (beta). Redefining by suffix would make the surviving "alpha" object answer header semantics —
 * exactly the silent slot takeover this fixture must expose.
 */
class SlotHost {

    @JvmField
    var slots: MutableList<Supplier<String>> = ArrayList()

    fun metricTable(): String {
        slots.clear()
        slots.add(object : Supplier<String> {
            override fun get(): String = "v2-header"
        })
        slots.add(object : Supplier<String> {
            override fun get(): String = "v2-alpha"
        })
        slots.add(object : Supplier<String> {
            override fun get(): String = "v2-beta"
        })
        val sb = StringBuilder("t2")
        for (s in slots) sb.append(':').append(s.get())
        return sb.toString()
    }

    fun nestedTable(): String {
        slots.clear()
        slots.add(object : Supplier<String> {
            override fun get(): String {
                slots.add(object : Supplier<String> {
                    override fun get(): String = "v2-nested-inner"
                })
                return "v2-nested-outer:" + slots[slots.size - 1].get()
            }
        })
        return slots[0].get()
    }

    fun alphaLabel(): String = slots[0].get()

    fun betaLabel(): String = slots[1].get()
}
