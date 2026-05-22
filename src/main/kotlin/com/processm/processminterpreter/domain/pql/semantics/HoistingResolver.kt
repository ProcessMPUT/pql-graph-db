package com.processm.processminterpreter.domain.pql.semantics

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.error.InvalidScopeHoistingException

/**
 * Pure function that applies the `^` / `^^` hoisting operators to a base scope.
 *
 *  EVENT ^  -> TRACE
 *  EVENT ^^ -> LOG
 *  TRACE ^  -> LOG
 *  LOG   ^  -> error (cannot hoist above the top scope)
 *
 * Scope enum ordering is load-bearing: LOG=0, TRACE=1, EVENT=2, so hoisting is
 * expressed as `ordinal - hoistingLevels`.
 */
object HoistingResolver {
    fun apply(baseScope: Scope, hoistingLevels: Int, location: SourceLocation): Scope {
        require(hoistingLevels >= 0) { "hoistingLevels must be non-negative, got $hoistingLevels" }
        if (hoistingLevels == 0) return baseScope

        val targetOrdinal = baseScope.ordinal - hoistingLevels
        if (targetOrdinal < 0) {
            throw InvalidScopeHoistingException(
                "Cannot hoist $baseScope by $hoistingLevels level(s); would exceed LOG scope",
                location,
            )
        }
        return Scope.entries[targetOrdinal]
    }
}
