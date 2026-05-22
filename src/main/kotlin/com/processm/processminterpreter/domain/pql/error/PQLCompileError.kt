package com.processm.processminterpreter.domain.pql.error

import com.processm.processminterpreter.domain.pql.catalog.SourceLocation

/**
 * Root of the PQL compilation-error hierarchy. Every problem thrown by
 * parser / resolver / validator / planner extends this class.
 *
 * Separate from the legacy `com.processm.processminterpreter.domain.pql.model.PQLException`
 * hierarchy — the new compiler pipeline throws this type exclusively. The legacy
 * hierarchy will remain in place until the old stack is removed.
 */
open class PQLCompileError(
    val problem: Problem,
    val location: SourceLocation,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(buildMessage(problem, location, message), cause) {

    companion object {
        private fun buildMessage(problem: Problem, location: SourceLocation, message: String): String =
            "Line ${location.line} position ${location.charPositionInLine}: $problem — $message"
    }
}
