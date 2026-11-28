package oracle

/** v2 Kotlin host: the field initializer argument changes 2.5 -> 12.5 (y is the changed component). */
class KtFdlHolder {
    val v = KtVecFdl(1.5f, 12.5, 3L)

    fun v(): KtVecFdl = v
}
