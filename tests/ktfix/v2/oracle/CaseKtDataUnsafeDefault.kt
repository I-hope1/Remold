package oracle

data class CaseKtDataUnsafeDefault(
	val id: Int,
	val tag: String = if (id > 0) "pos" else "neg",
	val ts: Long = System.currentTimeMillis()
)
