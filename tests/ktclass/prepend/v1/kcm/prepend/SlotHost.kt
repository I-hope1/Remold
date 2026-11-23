package kcm.prepend

import java.util.function.Supplier

/**
 * Kotlin host-suffix anonymous-class fixture, v1.
 *
 * The anonymous objects live as anonymous *arguments* of `slots.add(...)`, so the compiler names
 * them `SlotHost$metricTable$1` / `$2` by source order (no local-name hint). The nested one below
 * produces the `SlotHost$nestedTable$1$1` shape.
 *
 * `slots` exists in both versions (no schema change): the probe reads the *surviving* objects after
 * a hot-swap round through `alphaLabel()` / `betaLabel()`, so a silent replacement cannot be hidden
 * by re-running the factory method.
 */
class SlotHost {

    @JvmField
    var slots: MutableList<Supplier<String>> = ArrayList()

    fun metricTable(): String {
        slots.clear()
        slots.add(object : Supplier<String> {
            override fun get(): String = "v1-alpha"
        })
        slots.add(object : Supplier<String> {
            override fun get(): String = "v1-beta"
        })
        val sb = StringBuilder("t1")
        for (s in slots) sb.append(':').append(s.get())
        return sb.toString()
    }

    fun nestedTable(): String {
        slots.clear()
        slots.add(object : Supplier<String> {
            override fun get(): String {
                slots.add(object : Supplier<String> {
                    override fun get(): String = "v1-nested-inner"
                })
                return "v1-nested-outer:" + slots[slots.size - 1].get()
            }
        })
        return slots[0].get()
    }

    fun alphaLabel(): String = slots[0].get()

    fun betaLabel(): String = slots[1].get()
}
