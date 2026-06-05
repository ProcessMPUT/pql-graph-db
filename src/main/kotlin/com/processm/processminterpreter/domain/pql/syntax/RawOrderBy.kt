package com.processm.processminterpreter.domain.pql.syntax

import com.processm.processminterpreter.domain.pql.catalog.OrderDirection

data class RawOrderKey(
    val expression: RawExpression,
    val direction: OrderDirection,
)
