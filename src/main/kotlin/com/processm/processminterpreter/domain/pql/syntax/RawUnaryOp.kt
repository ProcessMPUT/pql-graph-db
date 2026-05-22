package com.processm.processminterpreter.domain.pql.syntax

import com.processm.processminterpreter.domain.pql.catalog.SourceLocation

data class RawUnaryOp(
    val op: UnaryOperator,
    val operand: RawExpression,
    override val location: SourceLocation,
) : RawExpression

enum class UnaryOperator { NEGATE, NOT }
