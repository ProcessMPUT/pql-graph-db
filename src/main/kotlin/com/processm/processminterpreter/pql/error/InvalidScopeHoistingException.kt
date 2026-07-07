package com.processm.processminterpreter.pql.error

import com.processm.processminterpreter.pql.catalog.SourceLocation

/**
 * Raised when `^` / `^^` hoisting operators attempt to exceed LOG scope.
 *
 * Examples:
 *  - `^l:name`  — LOG has no parent scope
 *  - `^^t:name` — TRACE hoisted twice goes past LOG
 */
class InvalidScopeHoistingException(
    message: String,
    location: SourceLocation,
) : PQLCompileError(Problem.NoHoistingBeyondLong, location, message)
