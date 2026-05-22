package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.Type
import com.processm.processminterpreter.domain.pql.plan.LogicalPlan
import com.processm.processminterpreter.domain.pql.resolved.Aggregation
import com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression
import com.processm.processminterpreter.domain.pql.resolved.ResolvedInList
import com.processm.processminterpreter.domain.pql.resolved.ScalarFunction
import com.processm.processminterpreter.domain.pql.resolved.TypedBinaryOp
import com.processm.processminterpreter.domain.pql.resolved.TypedLiteral
import com.processm.processminterpreter.domain.pql.resolved.TypedUnaryOp

internal object CypherAggregationInspector {
    fun hasAnyAggregation(plan: LogicalPlan.Select): Boolean {
        if (plan.groupBy?.hasAggregation == true) return true
        return plan.projection.columns.any { containsAggregation(it.expression) }
            || plan.orderBy.any { containsAggregation(it.expression) }
    }

    fun containsAggregation(e: ResolvedExpression): Boolean = when (e) {
        is Aggregation -> true
        is TypedBinaryOp -> containsAggregation(e.left) || containsAggregation(e.right)
        is TypedUnaryOp -> containsAggregation(e.operand)
        is ScalarFunction -> e.arguments.any(::containsAggregation)
        is ResolvedInList -> e.values.any(::containsAggregation)
        is ResolvedAttribute, is TypedLiteral -> false
    }

    fun hasEventScopeAggregation(plan: LogicalPlan.Select): Boolean =
        plan.projection.columns.any { col ->
            col.scope == Scope.EVENT && containsAggregation(col.expression)
        }

    fun aggregationsIn(e: ResolvedExpression): List<Aggregation> =
        buildList {
            collectAggregations(e, this)
        }

    fun isTemporalAggregation(e: ResolvedExpression): Boolean =
        e is Aggregation && e.type == Type.DATETIME &&
            e.name.lowercase() in setOf("max", "min")

    private fun collectAggregations(
        e: ResolvedExpression,
        out: MutableList<Aggregation>,
    ) {
        when (e) {
            is Aggregation -> out += e
            is TypedBinaryOp -> {
                collectAggregations(e.left, out)
                collectAggregations(e.right, out)
            }
            is TypedUnaryOp -> collectAggregations(e.operand, out)
            is ScalarFunction -> e.arguments.forEach { collectAggregations(it, out) }
            is ResolvedInList -> e.values.forEach { collectAggregations(it, out) }
            is ResolvedAttribute, is TypedLiteral -> Unit
        }
    }
}
