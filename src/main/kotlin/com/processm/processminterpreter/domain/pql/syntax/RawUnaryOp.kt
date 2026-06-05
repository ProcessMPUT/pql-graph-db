package com.processm.processminterpreter.domain.pql.syntax

import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.catalog.UnaryOperator

data class RawUnaryOp(
    val op: UnaryOperator,
    val operand: RawExpression,
    override val location: SourceLocation,
) : RawExpression
