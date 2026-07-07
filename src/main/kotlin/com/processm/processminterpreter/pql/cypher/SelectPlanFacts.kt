package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.plan.LogicalPlan
import com.processm.processminterpreter.pql.ast.PqlExpression

internal class SelectPlanFacts(private val plan: LogicalPlan.Select) {
    val materializedScopes: Set<Scope> = plan.materializedScopes
    val usedScopes: Set<Scope> = plan.source.usedScopes + plan.source.fromScope + materializedScopes

    val projectedScopes: Set<Scope> =
        plan.projection.columns.map { it.scope }.toSet() +
            plan.projection.selectAll.filterValues { it }.keys

    val hierarchyScopes: Set<Scope> = projectedScopes + materializedScopes

    val hasAnyAggregation: Boolean = CypherAggregationInspector.hasAnyAggregation(plan)
    val hasEventScopeAggregation: Boolean = CypherAggregationInspector.hasEventScopeAggregation(plan)

    fun scopesOf(e: PqlExpression): Set<Scope> = when (e) {
        is PqlExpression.Attribute -> setOf(e.effectiveScope)
        is PqlExpression.Binary -> scopesOf(e.left) + scopesOf(e.right)
        is PqlExpression.Unary -> scopesOf(e.operand)
        is PqlExpression.Call -> e.scope?.let { setOf(it) } ?: e.arguments.flatMap { scopesOf(it) }.toSet()
        is PqlExpression.InList -> e.values.flatMap { scopesOf(it) }.toSet()
        is PqlExpression.Aggregation -> scopesOf(e.argument)
        is PqlExpression.Literal -> e.scope?.let { setOf(it) } ?: emptySet()
        is PqlExpression.AttributeRef ->
            error("Internal error: unresolved attribute reference reached the Cypher planner facts")
    }

    fun expressionUsesBaseScope(
        e: PqlExpression,
        scope: Scope,
    ): Boolean = when (e) {
        is PqlExpression.Attribute -> e.baseScope == scope
        is PqlExpression.Aggregation -> expressionUsesBaseScope(e.argument, scope)
        is PqlExpression.Binary -> expressionUsesBaseScope(e.left, scope) || expressionUsesBaseScope(e.right, scope)
        is PqlExpression.Unary -> expressionUsesBaseScope(e.operand, scope)
        is PqlExpression.Call -> e.arguments.any { expressionUsesBaseScope(it, scope) }
        is PqlExpression.InList -> e.values.any { expressionUsesBaseScope(it, scope) }
        is PqlExpression.Literal, is PqlExpression.AttributeRef -> false
    }

    fun aggregationArgumentUsesBaseScope(
        e: PqlExpression,
        scope: Scope,
    ): Boolean = when (e) {
        is PqlExpression.Aggregation -> expressionUsesBaseScope(e.argument, scope)
        is PqlExpression.Binary -> aggregationArgumentUsesBaseScope(e.left, scope) ||
            aggregationArgumentUsesBaseScope(e.right, scope)
        is PqlExpression.Unary -> aggregationArgumentUsesBaseScope(e.operand, scope)
        is PqlExpression.Call -> e.arguments.any { aggregationArgumentUsesBaseScope(it, scope) }
        is PqlExpression.InList -> e.values.any { aggregationArgumentUsesBaseScope(it, scope) }
        is PqlExpression.Attribute, is PqlExpression.Literal, is PqlExpression.AttributeRef -> false
    }
}
