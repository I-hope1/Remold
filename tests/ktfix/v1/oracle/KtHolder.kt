package oracle

/** v1 Kotlin host: the existing instance field initializer is `new KtVec(4, 4)`. */
class KtHolder {
    val v = KtVec(4, 4)

    fun v(): KtVec = v
}
