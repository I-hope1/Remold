package ktanon

/**
 * R3 — the extreme round: one local variable is renamed (`first` -> `renamedFirst`, so the generated
 * class name changes from `...$first$1` to `...$renamedFirst$1` on the same host/sequence), the nested
 * anonymous objects under `outerAnon` are renumbered (named `$innerObj$1` becomes two unnamed
 * `$1`/`$2` siblings), and one `makeUnnamedAnons` object grows a capture (`grownCapture`).
 *
 * Correct end state: no `NoClassDefFoundError` / `NullPointerException`, the previously created
 * instances stay usable, and the renamed/nested new classes are loadable.
 */
interface Marker {
    fun label(): String
}

class KtAnonTarget {

    val propAnonA: Marker = object : Marker {
        val addedFlag: Int = 42

        override fun label(): String = "propA:v3"

        fun addedFlagValue(): Int = addedFlag
    }

    val fnObjAnon: Marker = object : Marker {
        override fun label(): String = "fnObj:v1"
    }

    val outerAnon: Marker = object : Marker {
        private val innerAnons: List<Marker> = listOf(
            object : Marker {
                override fun label(): String = "extra:v4"
            },
            object : Marker {
                override fun label(): String = "inner:v4"
            }
        )

        override fun label(): String = "outer:v4>" + innerAnons.joinToString("|") { it.label() }
    }

    fun anonStruct(): String = (object : Marker {
        val addedStructFlag: Int = 77

        override fun label(): String = "structA:v3:" + addedStructFlag
    }).label()

    fun makeUnnamedAnons(): List<Any> {
        val grownCapture = "cap:v4"
        return listOf(
            object : Marker {
                override fun label(): String = "u3:v2"
            },
            object : Marker {
                override fun label(): String = "u1:v2"
            },
            object : Marker {
                override fun label(): String = "u2:v4:" + grownCapture
            }
        )
    }

    fun makeLocalNamedAnons(): Any {
        val renamedFirst = object : Marker {
            override fun label(): String = "localFirst:v4"
        }
        return renamedFirst
    }
}
