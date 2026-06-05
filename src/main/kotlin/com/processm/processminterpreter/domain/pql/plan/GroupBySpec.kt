package com.processm.processminterpreter.domain.pql.plan

import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression

data class GroupBySpec(
    val keys: List<ResolvedExpression>,
    val hasAggregation: Boolean,
)
