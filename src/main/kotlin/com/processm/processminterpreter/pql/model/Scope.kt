package com.processm.processminterpreter.pql.model

/**
 * Represents the three-level hierarchy of process mining scopes.
 *
 * Hierarchy: Log (top) → Trace (middle) → Event (bottom/default)
 *
 * Based on ProcessM: https://github.com/ProcessMPUT/processm
 */
enum class Scope {
    Log,
    Trace,
    Event,
    ;

    /**
     * The short name for this scope ("l", "t", "e").
     */
    val shortName: String
        get() =
            when (this) {
                Log -> "l"
                Trace -> "t"
                Event -> "e"
            }

    /**
     * Returns the parent scope in the hierarchy.
     * - Log.upper = null (no parent)
     * - Trace.upper = Log
     * - Event.upper = Trace
     */
    val upper: Scope?
        get() =
            when (this) {
                Log -> null
                Trace -> Log
                Event -> Trace
            }

    /**
     * Returns the child scope in the hierarchy.
     * - Log.lower = Trace
     * - Trace.lower = Event
     * - Event.lower = null (no child)
     */
    val lower: Scope?
        get() =
            when (this) {
                Log -> Trace
                Trace -> Event
                Event -> null
            }

    override fun toString(): String = name.lowercase()

    companion object {
        /**
         * Parse a string to Scope enum.
         * Accepts: "log"/"l", "trace"/"t", "event"/"e" (case-insensitive)
         *
         * @param s the string to parse
         * @param default the default scope if parsing fails (default: null, throws exception)
         * @return the parsed Scope
         * @throws IllegalArgumentException if string is invalid and no default provided
         */
        fun parse(
            s: String,
            default: Scope? = null,
        ): Scope =
            when (s.lowercase()) {
                "log", "l" -> Log
                "trace", "t" -> Trace
                "event", "e" -> Event
                else -> default ?: throw IllegalArgumentException("Invalid scope: $s")
            }
    }
}

/**
 * Extension property to generate scope prefix for attributes.
 *
 * Examples:
 * - Scope.Log.prefix = "log:"
 * - Scope.Event.prefix = "event:"
 * - null.prefix = ""
 */
val Scope?.prefix: String
    get() = this?.let { "$it:" } ?: ""
