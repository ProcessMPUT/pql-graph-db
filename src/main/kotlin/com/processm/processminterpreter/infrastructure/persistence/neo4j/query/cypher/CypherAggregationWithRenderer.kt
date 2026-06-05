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

        val precomputedAliases = emitTraceScopedHoistedAggregations(s)
        val columns = buildWithColumns(s, precomputedAliases)
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

    private fun buildWithColumns(
        s: CypherBuildState,
        precomputedAliases: List<String>,
    ): List<String> =
        passThroughBindings(s, precomputedAliases) +
            groupKeyColumns(s) +
            complexAggregationColumns(s)

    private fun passThroughBindings(
        s: CypherBuildState,
        precomputedAliases: List<String>,
    ): List<String> =
        buildList {
            add("log")
            if (Scope.TRACE in s.facts.usedScopes || Scope.EVENT in s.facts.usedScopes) add("trace")
            if (Scope.EVENT in s.facts.usedScopes) add("event")
            addAll(precomputedAliases)
        }

    private fun groupKeyColumns(s: CypherBuildState): List<String> =
        buildList {
            val groupKeys = s.plan.groupBy?.keys ?: return@buildList
            groupKeys.forEachIndexed { idx, key ->
                val alias = "_gb_$idx"
                val rendered = expressions.render(key, s)
                s.registerGroupByAlias(expressions.exprKey(key), alias)
                add("$rendered AS $alias")
            }
            if (groupKeys.any { Scope.EVENT in s.facts.scopesOf(it) }) {
                s.registerSyntheticColumnAlias(
                    alias = SYNTHETIC_EVENT_GROUP_ORDER_ALIAS,
                    scope = Scope.EVENT,
                )
            }
        }

    private fun complexAggregationColumns(s: CypherBuildState): List<String> =
        buildList {
            s.plan.projection.columns.forEach { col ->
                collectComplexAggregations(col.expression, s, this)
            }
        }

    private fun emitTraceScopedHoistedAggregations(s: CypherBuildState): List<String> {
        if (!needsTraceScopedHoistedPrecompute(s)) return emptyList()

        val aliases = linkedMapOf<String, String>()
        traceScopedHoistedAggregations(s).forEach { aggregation ->
            val key = expressions.exprKey(aggregation)
            aliases.getOrPut(key) {
                s.complexAggregationAlias(key) ?: "_cagg_${s.nextCaggId()}".also { alias ->
                    s.registerComplexAggregationAlias(key, alias)
                }
            }
        }
        if (aliases.isEmpty()) return emptyList()

        val returnColumns = traceScopedHoistedAggregations(s)
            .distinctBy(expressions::exprKey)
            .joinToString(", ") { aggregation ->
                val alias = aliases.getValue(expressions.exprKey(aggregation))
                val rendered = s.withHoistedEventNodeVar(TRACE_AGG_EVENT_ALIAS) {
                    expressions.renderAggregation(aggregation, s)
                }
                "$rendered AS $alias"
            }

        s.cypher.append(
            " CALL { WITH trace MATCH (trace)-[:HAS_EVENT]->($TRACE_AGG_EVENT_ALIAS:Event) RETURN $returnColumns }",
        )
        return aliases.values.toList()
    }

    private fun needsTraceScopedHoistedPrecompute(s: CypherBuildState): Boolean {
        val filter = s.plan.filter
        if (filter != null && Scope.EVENT in s.facts.scopesOf(filter)) return false
        val groupKeys = s.plan.groupBy?.keys ?: return false
        return groupKeys.any { Scope.EVENT in s.facts.scopesOf(it) }
    }

    private fun traceScopedHoistedAggregations(s: CypherBuildState): List<Aggregation> =
        (s.plan.projection.columns.map { it.expression } + s.plan.orderBy.map { it.expression })
            .flatMap(CypherAggregationInspector::aggregationsIn)
            .filter(::isTraceScopedHoistedEventAggregation)

    private fun isTraceScopedHoistedEventAggregation(aggregation: Aggregation): Boolean {
        val attribute = aggregation.argument as? ResolvedAttribute ?: return false
        return attribute.baseScope == Scope.EVENT && attribute.effectiveScope == Scope.TRACE
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

private const val TRACE_AGG_EVENT_ALIAS = "_trace_agg_event"
