package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.plan.ProjectedColumn
import com.processm.processminterpreter.domain.pql.catalog.BinaryOperator
import com.processm.processminterpreter.domain.pql.resolved.Aggregation
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression
import com.processm.processminterpreter.domain.pql.resolved.TypedBinaryOp

/**
 * Handles trace-level grouped aggregates, e.g.
 * `SELECT max(^e:timestamp)-min(^e:timestamp) GROUP BY t:name`.
 *
 * The generic aggregation path must preserve event bindings for event-shaped
 * results. For trace-grouped aggregate rows that is actively harmful: it returns
 * one row per event instead of one row per group. This renderer keeps `event` only
 * inside aggregate expressions and returns compact trace-group rows.
 */
internal class CypherTraceGroupAggregateRenderer(
    private val expressions: CypherExpressionRenderer,
) {
    fun emitIfNeeded(s: CypherBuildState): Boolean {
        val groupCase = traceGroupAggregateCase(s) ?: return false

        registerAliases(s, groupCase)
        emitCypher(s, groupCase)
        return true
    }

    private fun traceGroupAggregateCase(s: CypherBuildState): TraceGroupAggregateCase? {
        val groupKeys = s.plan.groupBy?.keys ?: return null
        if (groupKeys.isEmpty()) return null
        if (!s.facts.hasAnyAggregation) return null
        if (s.plan.projection.columns.isEmpty()) return null
        if (s.plan.projection.selectAll.any { it.value }) return null

        val groupAliases = traceGroupAliases(s, groupKeys) ?: return null

        val groupAliasByExpr = groupAliases.associate { expressions.exprKey(it.expression) to it.alias }
        if (s.plan.projection.columns.any { !canProjectAtTraceGroupScope(it, s, groupAliasByExpr) }) return null
        if (s.plan.orderBy.any { !canOrderAtTraceGroupScope(it.expression, s, groupAliasByExpr) }) return null

        return TraceGroupAggregateCase(
            groupAliases = groupAliases,
            logAliases = logAliases(s, groupAliasByExpr),
            aggregateAliases = aggregateAliases(s),
        )
    }

    private fun traceGroupAliases(
        s: CypherBuildState,
        groupKeys: List<ResolvedExpression>,
    ): List<ExpressionAlias>? {
        val aliases = groupKeys.mapIndexed { idx, key ->
            if (Scope.EVENT in s.facts.scopesOf(key)) return null
            if (Scope.TRACE !in s.facts.scopesOf(key) && Scope.LOG !in s.facts.scopesOf(key)) return null
            ExpressionAlias(key, groupKeyAlias(idx))
        }
        return aliases.takeIf { groupAliases ->
            groupAliases.any { Scope.TRACE in s.facts.scopesOf(it.expression) }
        }
    }

    private fun canProjectAtTraceGroupScope(
        column: ProjectedColumn,
        s: CypherBuildState,
        groupAliasByExpr: Map<String, String>,
    ): Boolean {
        if (column.scope == Scope.EVENT) return false
        if (CypherAggregationInspector.containsAggregation(column.expression)) return true
        if (column.scope == Scope.LOG) return true
        return expressions.exprKey(column.expression) in groupAliasByExpr
    }

    private fun canOrderAtTraceGroupScope(
        expression: ResolvedExpression,
        s: CypherBuildState,
        groupAliasByExpr: Map<String, String>,
    ): Boolean =
        CypherAggregationInspector.containsAggregation(expression) ||
            Scope.LOG in s.facts.scopesOf(expression) ||
            expressions.exprKey(expression) in groupAliasByExpr

    private fun aggregateAliases(s: CypherBuildState): List<AggregateAlias> {
        val aliases = linkedMapOf<String, AggregateAlias>()

        fun add(aggregation: Aggregation) {
            val key = expressions.exprKey(aggregation)
            aliases.getOrPut(key) {
                AggregateAlias(aggregation, aggregateAlias(aliases.size))
            }
        }

        aggregateExpressions(s)
            .flatMap(CypherAggregationInspector::aggregationsIn)
            .forEach(::add)
        return aliases.values.toList()
    }

    private fun aggregateExpressions(s: CypherBuildState): List<ResolvedExpression> =
        s.plan.projection.columns.map { it.expression } +
            s.plan.orderBy.map { it.expression }

    private fun registerAliases(
        s: CypherBuildState,
        groupCase: TraceGroupAggregateCase,
    ) {
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_LOG_ID_ALIAS,
            scope = Scope.LOG,
        )
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_NULL_EVENT_COUNT_ALIAS,
            scope = Scope.TRACE,
        )
        groupCase.groupAliases.forEachIndexed { idx, _ ->
            s.registerSyntheticColumnAlias(
                alias = traceGroupOutputAlias(idx),
                scope = Scope.TRACE,
            )
        }
        s.plan.projection.columns.forEach(s::registerProjectedColumnAlias)
    }

    private fun emitCypher(
        s: CypherBuildState,
        groupCase: TraceGroupAggregateCase,
    ) {
        s.cypher.append(" WITH ").append(buildWithColumns(s, groupCase).joinToString(", "))
        bindExpressionAliases(s, groupCase)

        s.cypher.append(" RETURN ").append(buildReturnColumns(s, groupCase).joinToString(", "))
        emitOrderBy(s, groupCase)
    }

    private fun buildWithColumns(
        s: CypherBuildState,
        groupCase: TraceGroupAggregateCase,
    ): List<String> =
        buildList {
            add("log.logId AS $SYNTHETIC_LOG_ID_ALIAS")
            add(logMetadataProjection())
            groupCase.logAliases.forEach { add("${expressions.render(it.expression, s)} AS ${it.alias}") }
            groupCase.groupAliases.forEach { add("${expressions.render(it.expression, s)} AS ${it.alias}") }
            add("min(trace.importOrder) AS $TRACE_GROUP_ORDER_ALIAS")
            add("count(event) AS $SYNTHETIC_NULL_EVENT_COUNT_ALIAS")
            groupCase.aggregateAliases.forEach { add("${expressions.renderAggregation(it.aggregation, s)} AS ${it.alias}") }
        }

    private fun buildReturnColumns(
        s: CypherBuildState,
        groupCase: TraceGroupAggregateCase,
    ): List<String> =
        buildList {
            add(SYNTHETIC_LOG_ID_ALIAS)
            add(SYNTHETIC_LOG_METADATA_ALIAS)
            add(SYNTHETIC_NULL_EVENT_COUNT_ALIAS)
            groupCase.groupAliases.forEachIndexed { idx, groupAlias ->
                add("${groupAlias.alias} AS ${traceGroupOutputAlias(idx)}")
            }
            s.plan.projection.columns.forEach { column ->
                add("${expressions.render(column.expression, s)} AS ${column.alias}")
            }
        }

    private fun emitOrderBy(
        s: CypherBuildState,
        groupCase: TraceGroupAggregateCase,
    ) {
        val orderTerms = orderTerms(s, groupCase)
        if (orderTerms.isNotEmpty()) {
            s.cypher.append(" ORDER BY ").append(orderTerms.joinToString(", "))
        }
    }

    private fun orderTerms(
        s: CypherBuildState,
        groupCase: TraceGroupAggregateCase,
    ): List<String> =
        if (s.plan.orderBy.isEmpty()) {
            listOf(TRACE_GROUP_ORDER_ALIAS) + groupCase.groupAliases.map { it.alias }
        } else {
            s.plan.orderBy.map { key ->
                "${renderOrderExpression(key.expression, s)} ${key.direction.name}"
            } + groupCase.groupAliases.map { it.alias }
        }

    private fun renderOrderExpression(
        expression: ResolvedExpression,
        s: CypherBuildState,
    ): String {
        temporalDifferenceOrderExpression(expression, s)?.let { return it }

        return s.plan.projection.columns
            .firstOrNull { it.expression == expression }
            ?.alias
            ?: expressions.render(expression, s)
    }

    private fun temporalDifferenceOrderExpression(
        expression: ResolvedExpression,
        s: CypherBuildState,
    ): String? {
        val binary = expression as? TypedBinaryOp ?: return null
        if (binary.op != BinaryOperator.MINUS) return null
        if (!CypherAggregationInspector.isTemporalAggregation(binary.left)) return null
        if (!CypherAggregationInspector.isTemporalAggregation(binary.right)) return null

        return expressions.renderTemporalDifferenceInDays(binary.left, binary.right, s)
    }

    private fun bindExpressionAliases(
        s: CypherBuildState,
        groupCase: TraceGroupAggregateCase,
    ) {
        groupCase.groupAliases.forEach {
            s.registerGroupByAlias(expressions.exprKey(it.expression), it.alias)
        }
        groupCase.logAliases.forEach {
            s.registerGroupByAlias(expressions.exprKey(it.expression), it.alias)
        }
        groupCase.aggregateAliases.forEach {
            s.registerComplexAggregationAlias(expressions.exprKey(it.aggregation), it.alias)
        }
    }

    private data class TraceGroupAggregateCase(
        val groupAliases: List<ExpressionAlias>,
        val logAliases: List<ExpressionAlias>,
        val aggregateAliases: List<AggregateAlias>,
    )

    private data class ExpressionAlias(
        val expression: ResolvedExpression,
        val alias: String,
    )

    private data class AggregateAlias(
        val aggregation: Aggregation,
        val alias: String,
    )

    private fun logAliases(
        s: CypherBuildState,
        groupAliasByExpr: Map<String, String>,
    ): List<ExpressionAlias> {
        val aliases = linkedMapOf<String, ExpressionAlias>()

        fun add(expression: ResolvedExpression) {
            if (CypherAggregationInspector.containsAggregation(expression)) return
            if (Scope.LOG !in s.facts.scopesOf(expression)) return
            val key = expressions.exprKey(expression)
            if (key in groupAliasByExpr) return
            aliases.getOrPut(key) {
                ExpressionAlias(expression, logExpressionAlias(aliases.size))
            }
        }

        s.plan.projection.columns.forEach { add(it.expression) }
        s.plan.orderBy.forEach { add(it.expression) }
        return aliases.values.toList()
    }

    private fun groupKeyAlias(index: Int): String = "_gb_$index"

    private fun aggregateAlias(index: Int): String = "_cagg_$index"

    private fun logExpressionAlias(index: Int): String = "_log_$index"

    private fun traceGroupOutputAlias(index: Int): String = "_trace_group_$index"
}

private const val TRACE_GROUP_ORDER_ALIAS = "_trace_group_order_"
