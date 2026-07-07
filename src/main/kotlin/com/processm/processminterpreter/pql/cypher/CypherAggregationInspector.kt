package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.Type
import com.processm.processminterpreter.pql.plan.LogicalPlan
import com.processm.processminterpreter.pql.ast.PqlExpression

internal object CypherAggregationInspector {
    fun hasAnyAggregation(plan: LogicalPlan.Select): Boolean {
        if (plan.groupBy?.hasAggregation == true) return true
        return plan.projection.columns.any { containsAggregation(it.expression) }
            || plan.orderBy.any { containsAggregation(it.expression) }
    }

    fun containsAggregation(e: PqlExpression): Boolean = when (e) {
        is PqlExpression.Aggregation -> true
        is PqlExpression.Binary -> containsAggregation(e.left) || containsAggregation(e.right)
        is PqlExpression.Unary -> containsAggregation(e.operand)
        is PqlExpression.Call -> e.arguments.any(::containsAggregation)
        is PqlExpression.InList -> e.values.any(::containsAggregation)
        is PqlExpression.Attribute, is PqlExpression.Literal, is PqlExpression.AttributeRef -> false
    }

    fun hasEventScopeAggregation(plan: LogicalPlan.Select): Boolean =
        plan.projection.columns.any { col ->
            col.scope == Scope.EVENT && containsAggregation(col.expression)
        }

    fun aggregationsIn(e: PqlExpression): List<PqlExpression.Aggregation> =
        buildList {
            collectAggregations(e, this)
        }

    fun isTemporalAggregation(e: PqlExpression): Boolean =
        e is PqlExpression.Aggregation && e.type == Type.DATETIME &&
            e.name.lowercase() in setOf("max", "min")

    private fun collectAggregations(
        e: PqlExpression,
        out: MutableList<PqlExpression.Aggregation>,
    ) {
        when (e) {
            is PqlExpression.Aggregation -> out += e
            is PqlExpression.Binary -> {
                collectAggregations(e.left, out)
                collectAggregations(e.right, out)
            }
            is PqlExpression.Unary -> collectAggregations(e.operand, out)
            is PqlExpression.Call -> e.arguments.forEach { collectAggregations(it, out) }
            is PqlExpression.InList -> e.values.forEach { collectAggregations(it, out) }
            is PqlExpression.Attribute, is PqlExpression.Literal, is PqlExpression.AttributeRef -> Unit
        }
    }
}
