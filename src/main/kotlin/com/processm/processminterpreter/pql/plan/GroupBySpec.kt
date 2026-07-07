package com.processm.processminterpreter.pql.plan

import com.processm.processminterpreter.pql.ast.PqlExpression

data class GroupBySpec(
    val keys: List<PqlExpression>,
    val hasAggregation: Boolean,
)
