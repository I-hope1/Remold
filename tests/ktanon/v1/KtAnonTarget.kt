package ktanon

/**
 * Baseline (R0) of the Kotlin anonymous-class extreme sequence.
 *
 * Every shape below is a *named* Kotlin anonymous object, which is exactly where name-based host
 * derivation used to invent hosts that never exist (`KtAnonTarget$propAnonA` and friends): kotlinc
 * splices the property/method/local-variable name into the class name and appends the index, while
 * the authoritative outer class is only recorded in the `EnclosingMethod` attribute.
 *
 * Shapes (must stay byte-for-byte stable across the sequence unless the round says otherwise):
 *   - property initializer          -> KtAnonTarget$propAnonA$1
 *   - property initializer (2nd)    -> KtAnonTarget$fnObjAnon$1
 *   - property initializer, nested  -> KtAnonTarget$outerAnon$1 + $outerAnon$1$innerObj$1
 *   - inline object inside a method -> KtAnonTarget$anonStruct$1
 *   - three unnamed args of listOf  -> KtAnonTarget$makeUnnamedAnons$1..$3
 *   - local val object              -> KtAnonTarget$makeLocalNamedAnons$first$1
 */
interface Marker {
    fun label(): String
}

class KtAnonTarget {

    val propAnonA: Marker = object : Marker {
        override fun label(): String = "propA:v1"
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
        override fun label(): String = "structA:v1"
    }).label()

    fun makeUnnamedAnons(): List<Any> = listOf(
        object : Marker {
            override fun label(): String = "u1:v1"
        },
        object : Marker {
            override fun label(): String = "u2:v1"
        },
        object : Marker {
            override fun label(): String = "u3:v1"
        }
    )

    fun makeLocalNamedAnons(): Any {
        val first = object : Marker {
            override fun label(): String = "localFirst:v1"
        }
        return first
    }
}
