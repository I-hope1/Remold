package oracle

/**
 * Real kotlinc data class used by the value-type new-initializer replay scenario
 * (docs/initfix/14-value-type-new-replay.md A3). Both versions compile to the same shape: the host
 * only changes the constructor arguments of the field initializer.
 */
data class KtVec(val x: Int, val y: Int)
