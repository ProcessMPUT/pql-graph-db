package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.ast.PqlExpression
import com.processm.processminterpreter.pql.catalog.Scope

/**
 * Paths from a selected component to the physical components read by an expression.
 * Hoisting first visits the effective scope, then descends to the physical scope.
 * Distinct paths stay distinct: e:name and ^e:name need different event bindings.
 */
internal class CypherScopeBindings(
    private val scope: Scope,
    node: String,
    private val prefix: String,
) {
    private val variables = linkedMapOf((scope to scope) to node)
    private val matches = StringBuilder()

    fun prepare(expression: PqlExpression) {
        attributes(expression).forEach { node(it.baseScope, it.effectiveScope) }
    }

    fun render(expression: PqlExpression, expressions: CypherExpressionRenderer, state: CypherBuildState): String =
        state.withAttributeNodeVariables(variables) {
            state.withAggregationArgumentRendering { expressions.render(expression, state) }
        }

    fun clauses(): String = matches.toString()

    fun node(baseScope: Scope, effectiveScope: Scope): String = variables.getOrPut(baseScope to effectiveScope) {
        val parentScope: Scope
        val parent: String
        if (baseScope == effectiveScope) {
            parentScope = Scope.entries[baseScope.ordinal + if (baseScope < scope) 1 else -1]
            parent = node(parentScope, parentScope)
        } else {
            parentScope = Scope.entries[baseScope.ordinal - 1]
            parent = node(parentScope, effectiveScope)
        }
        val alias = "${prefix}_${baseScope.name.lowercase()}_${effectiveScope.name.lowercase()}"
        val relationship = if (maxOf(baseScope, parentScope) == Scope.TRACE) "CONTAINS" else "HAS_EVENT"
        val label = when (baseScope) {
            Scope.LOG -> "Log"
            Scope.TRACE -> "Trace"
            Scope.EVENT -> "Event"
        }
        matches.append(" OPTIONAL MATCH ($parent)")
        if (baseScope > parentScope) matches.append("-[:$relationship]->") else matches.append("<-[:$relationship]-")
        matches.append("($alias:$label)")
        alias
    }

    companion object {
        fun attributes(expression: PqlExpression): List<PqlExpression.Attribute> = when (expression) {
            is PqlExpression.Attribute -> listOf(expression)
            is PqlExpression.Aggregation -> attributes(expression.argument)
            is PqlExpression.Binary -> attributes(expression.left) + attributes(expression.right)
            is PqlExpression.Unary -> attributes(expression.operand)
            is PqlExpression.Call -> expression.arguments.flatMap(::attributes)
            is PqlExpression.InList -> expression.values.flatMap(::attributes)
            is PqlExpression.Literal, is PqlExpression.AttributeRef -> emptyList()
        }
    }
}
