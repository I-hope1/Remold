package ktanon

/**
 * R2 — the two *named* anonymous objects (`propAnonA`, and the inline object in `anonStruct`) grow a
 * field. `propAnonA` is held by the target instance created before the round, so the new field has to
 * be patched onto that surviving instance by InitFix (two-phase redefine); reading it back through a
 * method is the business-observable end state (reflection on field metadata would report the stale
 * layout).
 *
 * R1's reorder is kept so the sequence stays cumulative.
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
        val innerObj: Marker = object : Marker {
            override fun label(): String = "inner:v1"
        }

        override fun label(): String = "outer:v1>" + innerObj.label()
    }

    fun anonStruct(): String = (object : Marker {
        val addedStructFlag: Int = 77

        override fun label(): String = "structA:v3:" + addedStructFlag
    }).label()

    fun makeUnnamedAnons(): List<Any> = listOf(
        object : Marker {
            override fun label(): String = "u3:v2"
        },
        object : Marker {
            override fun label(): String = "u1:v2"
        },
        object : Marker {
            override fun label(): String = "u2:v2"
        }
    )

    fun makeLocalNamedAnons(): Any {
        val first = object : Marker {
            override fun label(): String = "localFirst:v1"
        }
        return first
    }
}
