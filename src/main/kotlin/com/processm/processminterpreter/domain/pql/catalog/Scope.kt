package com.processm.processminterpreter.domain.pql.catalog

/**
 * Three-level scope of PQL attributes and clauses.
 * Hierarchy: LOG contains TRACE contains EVENT. Ordinals are load-bearing for
 * hoisting arithmetic (see HoistingResolver): LOG=0, TRACE=1, EVENT=2 so that
 * `ordinal - hoistingLevels` walks up the hierarchy.
 */
enum class Scope(val shortName: String, val fullName: String) {
    LOG("l", "log"),
    TRACE("t", "trace"),
    EVENT("e", "event"),
    ;

    /** One level up in the hierarchy; null when at LOG. */
    val upper: Scope?
        get() = when (this) {
            EVENT -> TRACE
            TRACE -> LOG
            LOG -> null
        }

    override fun toString(): String = fullName

    companion object {
        /**
         * Lower a scope token (short or full name, case-insensitive) to a [Scope],
         * or null if the token is not a recognized scope. Use this instead of catching
         * [IllegalArgumentException] from [parse] when the input may legitimately be
         * a non-scope string (e.g. attribute names containing a `:`).
         */
        fun tryParse(s: String): Scope? = when (s.lowercase()) {
            "l", "log" -> LOG
            "t", "trace" -> TRACE
            "e", "event" -> EVENT
            else -> null
        }

        fun parse(s: String): Scope =
            tryParse(s) ?: throw IllegalArgumentException("Unknown scope: $s")
    }
}
