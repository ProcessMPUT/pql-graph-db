package com.processm.processminterpreter.pql.model

/**
 * Represents the sorting direction in ORDER BY clauses.
 *
 * Based on ProcessM: https://github.com/ProcessMPUT/processm
 */
enum class OrderDirection {
    Ascending {
        override fun toString() = "asc"
    },
    Descending {
        override fun toString() = "desc"
    }, ;

    companion object {
        /**
         * Parse a string to OrderDirection enum.
         * Accepts: "asc", "ascending", "desc", "descending" (case-insensitive)
         *
         * @param s the string to parse (null defaults to Ascending)
         * @param default the default direction if parsing fails (default: Ascending)
         * @return the parsed OrderDirection
         * @throws IllegalArgumentException if string is invalid and no default provided
         */
        fun parse(
            s: String?,
            default: OrderDirection = Ascending,
        ): OrderDirection {
            if (s == null) return default

            return when (s.lowercase()) {
                "desc", "descending" -> Descending
                "asc", "ascending" -> Ascending
                else -> throw IllegalArgumentException("Invalid order direction: $s")
            }
        }
    }
}
