package com.processm.processminterpreter.domain.pql.resolved

import com.processm.processminterpreter.domain.pql.syntax.BinaryOperator
import com.processm.processminterpreter.domain.pql.syntax.UnaryOperator
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.catalog.Type

/**
 * Root of the typed, semantically-resolved expression tree.
 *
 * Distinct from [com.processm.processminterpreter.domain.pql.syntax.RawExpression] in that
 *  - types are inferred,
 *  - attributes are classified (standard / custom / classifier),
 *  - hoisting is materialized into effective scopes,
 *  - aggregations are represented as a distinct variant (not overloaded onto ScalarFunction).
 */
sealed interface ResolvedExpression {
    val type: Type
    val location: SourceLocation
}

data class TypedBinaryOp(
    val op: BinaryOperator,
    val left: ResolvedExpression,
    val right: ResolvedExpression,
    override val type: Type,
    override val location: SourceLocation,
) : ResolvedExpression

data class TypedUnaryOp(
    val op: UnaryOperator,
    val operand: ResolvedExpression,
    override val type: Type,
    override val location: SourceLocation,
) : ResolvedExpression

data class ScalarFunction(
    val name: String,
    val arguments: List<ResolvedExpression>,
    val scope: Scope? = null,
    override val type: Type,
    override val location: SourceLocation,
) : ResolvedExpression

data class Aggregation(
    /** count, sum, avg, min, max */
    val name: String,
    val argument: ResolvedExpression,
    override val type: Type,
    override val location: SourceLocation,
    val scope: Scope? = null,
) : ResolvedExpression

/** RHS of `x IN (...)` / `x NOT IN (...)`. Values are typed but the list itself is untyped. */
data class ResolvedInList(
    val values: List<ResolvedExpression>,
    override val location: SourceLocation,
) : ResolvedExpression {
    override val type: Type get() = Type.UNKNOWN
}
