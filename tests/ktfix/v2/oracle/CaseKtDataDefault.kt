package oracle

data class CaseKtDataDefault(
	val id: Int,
	val label: String = "v0",
	val extra: Int = 99,
	val tag: String = "  def  ".trim(),
	val derived: String = "$label:$extra",
	val zeroCount: Int = 0,
	var mutableFlag: Int = 7
)
