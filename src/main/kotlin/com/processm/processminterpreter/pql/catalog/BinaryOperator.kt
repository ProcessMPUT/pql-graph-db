package com.processm.processminterpreter.pql.catalog

/**
 * Binary operators used both in the raw PQL AST and in the resolved/typed expression tree.
 * Stable semantic identifiers — not a parser artifact — so they live in the catalog package
 * alongside [Scope] and [Type].
 */
enum class BinaryOperator(val symbol: String) {
    EQ("="), NEQ("<>"), LT("<"), LTE("<="), GT(">"), GTE(">="),
    AND("and"), OR("or"),
    PLUS("+"), MINUS("-"), MUL("*"), DIV("/"),
    LIKE("like"), NOT_LIKE("not like"),
    MATCHES_REGEX("matches"),
    IS("is"), IS_NOT("is not"),
    IN("in"), NOT_IN("not in"),
}
