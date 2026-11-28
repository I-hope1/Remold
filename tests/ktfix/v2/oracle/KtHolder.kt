package oracle

/** v2 Kotlin host: the field initializer argument changes 4 -> 12 (y is the changed component). */
class KtHolder {
    val v = KtVec(4, 12)

    fun v(): KtVec = v
}
