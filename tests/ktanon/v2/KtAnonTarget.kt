package ktanon

/**
 * R1 — the three unnamed anonymous objects passed to `listOf` are reordered and their bodies change
 * (`u1/u2/u3` now sit at different indices). Only names/indices carry positional meaning here, so an
 * index-based pairing would hand an old CallSite another slot's body: the surviving instance that
 * used to say `u1:v1` would silently start saying `u3:v2`.
 *
 * `propAnonA` also changes its label: a *uniquely* identifiable member is what proves the round
 * reached the JVM at all (the host must not be rejected as a whole just because the unnamed trio is
 * ambiguous). Everything else stays as in v1 on purpose: the round is judged on this edit alone.
 */
interface Marker {
    fun label(): String
}

class KtAnonTarget {

    val propAnonA: Marker = object : Marker {
        override fun label(): String = "propA:r1"
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
