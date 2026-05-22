package com.processm.processminterpreter.domain.pql.syntax

import com.processm.processminterpreter.domain.pql.catalog.SourceLocation

data class RawFunctionCall(
    val name: String,
    val arguments: List<RawExpression>,
    override val location: SourceLocation,
) : RawExpression
