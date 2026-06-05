package com.processm.processminterpreter.domain.pql.syntax

import com.processm.processminterpreter.domain.pql.catalog.BinaryOperator
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation

data class RawBinaryOp(
    val op: BinaryOperator,
    val left: RawExpression,
    val right: RawExpression,
    override val location: SourceLocation,
) : RawExpression
