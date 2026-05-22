package com.processm.processminterpreter.domain.pql.syntax

import com.processm.processminterpreter.domain.pql.catalog.SourceLocation

/**
 * Root of the PQL AST expression tree produced by the parser.
 * Subtypes added in Task 8.
 */
sealed interface RawExpression {
    val location: SourceLocation
}
