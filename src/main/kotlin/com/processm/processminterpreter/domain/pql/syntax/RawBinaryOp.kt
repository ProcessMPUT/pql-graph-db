package com.processm.processminterpreter.domain.pql.syntax

import com.processm.processminterpreter.domain.pql.catalog.SourceLocation

data class RawBinaryOp(
    val op: BinaryOperator,
    val left: RawExpression,
    val right: RawExpression,
    override val location: SourceLocation,
) : RawExpression

enum class BinaryOperator(val symbol: String) {
    EQ("="), NEQ("<>"), LT("<"), LTE("<="), GT(">"), GTE(">="),
    AND("and"), OR("or"),
    PLUS("+"), MINUS("-"), MUL("*"), DIV("/"),
    LIKE("like"), NOT_LIKE("not like"),
    MATCHES_REGEX("matches"),
    IS("is"), IS_NOT("is not"),
    IN("in"), NOT_IN("not in"),
}
