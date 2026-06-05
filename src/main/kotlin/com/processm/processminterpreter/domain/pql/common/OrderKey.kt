package com.processm.processminterpreter.domain.pql.common

import com.processm.processminterpreter.domain.pql.catalog.OrderDirection
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression

/**
 * ORDER BY key in the resolved-and-planned layers.
 *
 * The syntax layer uses [com.processm.processminterpreter.domain.pql.syntax.RawOrderKey]
 * because its expression type is different ([com.processm.processminterpreter.domain.pql.syntax.RawExpression]).
 * From the resolver onwards the shape is identical, so it lives once here.
 */
data class OrderKey(
    val expression: ResolvedExpression,
    val direction: OrderDirection,
)
