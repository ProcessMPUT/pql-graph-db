package com.processm.processminterpreter.domain.pql.syntax

data class RawOrderKey(
    val expression: RawExpression,
    val direction: OrderDirection,
)

enum class OrderDirection { ASC, DESC }
