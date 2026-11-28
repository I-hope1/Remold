package oracle

/** v1 Kotlin host: the existing instance field initializer is `new KtVecFdl(1.5f, 2.5, 3L)`. */
class KtFdlHolder {
    val v = KtVecFdl(1.5f, 2.5, 3L)

    fun v(): KtVecFdl = v
}
