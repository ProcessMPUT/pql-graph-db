package com.processm.processminterpreter.domain.pql.plan

import com.processm.processminterpreter.domain.pql.syntax.OrderDirection
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression

data class GroupBySpec(
    val keys: List<ResolvedExpression>,
    val hasAggregation: Boolean,
)

data class OrderKey(
    val expression: ResolvedExpression,
    val direction: OrderDirection,
)

data class HierarchicalLimits(val log: Long? = null, val trace: Long? = null, val event: Long? = null)
data class HierarchicalOffsets(val log: Long? = null, val trace: Long? = null, val event: Long? = null)
