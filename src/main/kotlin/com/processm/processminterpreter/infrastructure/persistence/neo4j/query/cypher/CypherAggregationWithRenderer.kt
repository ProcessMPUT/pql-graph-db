package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.resolved.Aggregation
import com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression
import com.processm.processminterpreter.domain.pql.resolved.ResolvedInList
import com.processm.processminterpreter.domain.pql.resolved.ScalarFunction
import com.processm.processminterpreter.domain.pql.resolved.TypedBinaryOp
import com.processm.processminterpreter.domain.pql.resolved.TypedLiteral
import com.processm.processminterpreter.domain.pql.resolved.TypedUnaryOp

internal class CypherAggregationWithRenderer(
    private val expressions: CypherExpressionRenderer,
) {
    /**
     * If the plan needs aggregation, emit a single `WITH` that carries:
     *  - pass-through identity (log, trace, event) so hierarchy reconstruction sees
     *    the original nodes,
     *  - grouping keys as aliased expressions (`<expr> AS _gb_N`),
     *  - sub-aggregations extracted from complex expressions (`<agg> AS _cagg_N`),
     *  - simple aggregations reused directly under their SELECT alias.
     *
     * Non-aggregation queries fall through to the plain RETURN path.
     */
    fun emitIfAggregation(s: CypherBuildState) {
        if (!s.facts.hasAnyAggregation) return

        if (requiresTraceDeduplication(s)) {
            s.cypher.append(" WITH DISTINCT log, trace")
            return
        }

        val columns = buildWithColumns(s)
        if (columns.isNotEmpty()) {
            s.cypher.append(" WITH ").append(columns.joinToString(", "))
        }
    }

    private fun requiresTraceDeduplication(s: CypherBuildState): Boolean {
        if (s.plan.groupBy?.keys?.isNotEmpty() == true) return false
        if (Scope.EVENT !in s.facts.usedScopes) return false
        return aggregationArgumentUsage(s).requiresDistinctTraceRows
    }

    private fun aggregationArgumentUsage(s: CypherBuildState): AggregationArgumentUsage =
        AggregationArgumentUsage(
            trace = s.plan.projection.columns.any { col ->
                s.facts.aggregationArgumentUsesBaseScope(col.expression, Scope.TRACE)
            },
            event = s.plan.projection.columns.any { col ->
                s.facts.aggregationArgumentUsesBaseScope(col.expression, Scope.EVENT)
            },
        )

    private fun buildWithColumns(s: CypherBuildState): List<String> =
        passThroughBindings(s) +
            groupKeyColumns(s) +
            complexAggregationColumns(s)

    private fun passThroughBindings(s: CypherBuildState): List<String> =
        buildList {
            add("log")
            if (Scope.TRACE in s.facts.usedScopes || Scope.EVENT in s.facts.usedScopes) add("trace")
            if (Scope.EVENT in s.facts.usedScopes) add("event")
        }

    private fun groupKeyColumns(s: CypherBuildState): List<String> =
        s.plan.groupBy?.keys?.mapIndexed { idx, key ->
            val alias = "_gb_$idx"
            val rendered = expressions.render(key, s)
            s.registerGroupByAlias(expressions.exprKey(key), alias)
            "$rendered AS $alias"
        } ?: emptyList()

    private fun complexAggregationColumns(s: CypherBuildState): List<String> =
        buildList {
            s.plan.projection.columns.forEach { col ->
                collectComplexAggregations(col.expression, s, this)
            }
        }

    private fun collectComplexAggregations(
        expression: ResolvedExpression,
        s: CypherBuildState,
        columns: MutableList<String>,
    ) {
        fun walk(node: ResolvedExpression) {
            when (node) {
                is Aggregation -> Unit
                is TypedBinaryOp -> {
                    if (CypherAggregationInspector.containsAggregation(node)) {
                        extractAggregationColumns(node.left, s, columns)
                        extractAggregationColumns(node.right, s, columns)
                    }
                }
                is TypedUnaryOp -> if (CypherAggregationInspector.containsAggregation(node)) {
                    extractAggregationColumns(node.operand, s, columns)
                }
                is ScalarFunction -> node.arguments.forEach(::walk)
                is ResolvedInList -> node.values.forEach(::walk)
                is ResolvedAttribute, is TypedLiteral -> Unit
            }
        }
        if (expression !is Aggregation) walk(expression)
    }

    private fun extractAggregationColumns(
        expression: ResolvedExpression,
        s: CypherBuildState,
        columns: MutableList<String>,
    ) {
        when (expression) {
            is Aggregation -> {
                val key = expressions.exprKey(expression)
                if (s.complexAggregationAlias(key) == null) {
                    val alias = "_cagg_${s.nextCaggId()}"
                    s.registerComplexAggregationAlias(key, alias)
                    columns.add("${expressions.renderAggregation(expression, s)} AS $alias")
                }
            }
            is TypedBinaryOp -> {
                extractAggregationColumns(expression.left, s, columns)
                extractAggregationColumns(expression.right, s, columns)
            }
            is TypedUnaryOp -> extractAggregationColumns(expression.operand, s, columns)
            else -> Unit
        }
    }

    private data class AggregationArgumentUsage(
        val trace: Boolean,
        val event: Boolean,
    ) {
        val requiresDistinctTraceRows: Boolean
            get() = trace && !event
    }
}
