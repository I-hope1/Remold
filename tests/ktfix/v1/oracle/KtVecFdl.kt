package oracle

/**
 * Real kotlinc data class used by the value-type new-initializer replay scenario
 * (docs/initfix/14-value-type-new-replay.md A3) for Float / Double / Long components.
 * kotlinc emits Float.compare / Double.compare / LCMP in equals and the boxed static
 * hashCode helpers in hashCode; both must be recognized as compiler-generated.
 */
data class KtVecFdl(val x: Float, val y: Double, val z: Long)
