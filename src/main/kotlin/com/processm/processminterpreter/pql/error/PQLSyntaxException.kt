package com.processm.processminterpreter.pql.error

import com.processm.processminterpreter.pql.catalog.SourceLocation

/**
 * Thrown by the new pipeline when PQL source text violates syntactic or semantic rules.
 */
class PQLSyntaxException(
    problem: Problem,
    location: SourceLocation,
    message: String,
    cause: Throwable? = null,
) : PQLCompileError(problem, location, message, cause) {

    /** Convenience constructor for generic syntax errors without a specific Problem. */
    constructor(location: SourceLocation, message: String) :
        this(Problem.SyntaxError, location, message)
}
