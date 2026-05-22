package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.plan.LogicalPlan
import com.processm.processminterpreter.domain.pql.resolved.Aggregation
import com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression
import com.processm.processminterpreter.domain.pql.resolved.ResolvedInList
import com.processm.processminterpreter.domain.pql.resolved.ScalarFunction
import com.processm.processminterpreter.domain.pql.resolved.TypedBinaryOp
import com.processm.processminterpreter.domain.pql.resolved.TypedLiteral
import com.processm.processminterpreter.domain.pql.resolved.TypedUnaryOp

internal class SelectPlanFacts(private val plan: LogicalPlan.Select) {
    val materializedScopes: Set<Scope> = plan.materializedScopes
    val usedScopes: Set<Scope> = plan.source.usedScopes + plan.source.fromScope + materializedScopes

    val projectedScopes: Set<Scope> =
        plan.projection.columns.map { it.scope }.toSet() +
            plan.projection.selectAll.filterValues { it }.keys

    val hierarchyScopes: Set<Scope> = projectedScopes + materializedScopes

    val hasAnyAggregation: Boolean = CypherAggregationInspector.hasAnyAggregation(plan)
    val hasEventScopeAggregation: Boolean = CypherAggregationInspector.hasEventScopeAggregation(plan)

    fun scopesOf(e: ResolvedExpression): Set<Scope> = when (e) {
        is ResolvedAttribute -> setOf(e.effectiveScope)
        is TypedBinaryOp -> scopesOf(e.left) + scopesOf(e.right)
        is TypedUnaryOp -> scopesOf(e.operand)
        is ScalarFunction -> e.scope?.let { setOf(it) } ?: e.arguments.flatMap { scopesOf(it) }.toSet()
        is ResolvedInList -> e.values.flatMap { scopesOf(it) }.toSet()
        is Aggregation -> scopesOf(e.argument)
        is TypedLiteral -> e.scope?.let { setOf(it) } ?: emptySet()
    }

    fun expressionUsesBaseScope(
        e: ResolvedExpression,
        scope: Scope,
    ): Boolean = when (e) {
        is ResolvedAttribute -> e.baseScope == scope
        is Aggregation -> expressionUsesBaseScope(e.argument, scope)
        is TypedBinaryOp -> expressionUsesBaseScope(e.left, scope) || expressionUsesBaseScope(e.right, scope)
        is TypedUnaryOp -> expressionUsesBaseScope(e.operand, scope)
        is ScalarFunction -> e.arguments.any { expressionUsesBaseScope(it, scope) }
        is ResolvedInList -> e.values.any { expressionUsesBaseScope(it, scope) }
        is TypedLiteral -> false
    }

    fun aggregationArgumentUsesBaseScope(
        e: ResolvedExpression,
        scope: Scope,
    ): Boolean = when (e) {
        is Aggregation -> expressionUsesBaseScope(e.argument, scope)
        is TypedBinaryOp -> aggregationArgumentUsesBaseScope(e.left, scope) ||
            aggregationArgumentUsesBaseScope(e.right, scope)
        is TypedUnaryOp -> aggregationArgumentUsesBaseScope(e.operand, scope)
        is ScalarFunction -> e.arguments.any { aggregationArgumentUsesBaseScope(it, scope) }
        is ResolvedInList -> e.values.any { aggregationArgumentUsesBaseScope(it, scope) }
        is ResolvedAttribute, is TypedLiteral -> false
    }
}
