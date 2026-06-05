package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.plan.ProjectedColumn

/**
 * Emits ProcessM-compatible placeholder hierarchies for aggregate-only SELECTs.
 *
 * These queries carry aggregate values at log/trace scope while preserving lower
 * hierarchy shape as empty placeholder events, which is not something Cypher's
 * default aggregation output can represent by itself.
 */
internal class CypherAggregatePlaceholderHierarchyRenderer(
    private val expressions: CypherExpressionRenderer,
) {
    fun emitIfNeeded(s: CypherBuildState): Boolean =
        emitLogAggregatePlaceholderHierarchyIfNeeded(s) ||
            emitTraceAggregatePlaceholderHierarchyIfNeeded(s)

    private fun emitLogAggregatePlaceholderHierarchyIfNeeded(s: CypherBuildState): Boolean {
        val placeholderCase = logAggregatePlaceholderCase(s) ?: return false

        registerLogAggregatePlaceholderAliases(s, placeholderCase)
        emitLogAggregatePlaceholderCypher(s, placeholderCase)
        return true
    }

    private fun logAggregatePlaceholderCase(s: CypherBuildState): LogAggregatePlaceholderCase? {
        if (!s.facts.hasAnyAggregation) return null
        if (s.plan.groupBy?.keys?.isNotEmpty() == true) return null
        if (s.plan.projection.columns.isEmpty()) return null
        if (s.plan.projection.columns.any { it.scope != Scope.LOG || !CypherAggregationInspector.containsAggregation(it.expression) }) {
            return null
        }

        return LogAggregatePlaceholderCase(s.plan.projection.columns)
    }

    private fun registerLogAggregatePlaceholderAliases(
        s: CypherBuildState,
        placeholderCase: LogAggregatePlaceholderCase,
    ) {
        placeholderCase.aggregateColumns.forEach(s::registerProjectedColumnAlias)
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_LOG_ID_ALIAS,
            scope = Scope.LOG,
        )
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_TRACE_ID_ALIAS,
            scope = Scope.TRACE,
        )
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_TRACE_ORDER_ALIAS,
            scope = Scope.TRACE,
        )
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_EVENT_ALIAS,
            scope = Scope.EVENT,
        )
    }

    private fun emitLogAggregatePlaceholderCypher(
        s: CypherBuildState,
        placeholderCase: LogAggregatePlaceholderCase,
    ) {
        val aggregateColumns = placeholderCase.aggregateColumns.map { col ->
            "${expressions.render(col.expression, s)} AS ${col.alias}"
        }
        s.cypher.append(" WITH log, ").append(aggregateColumns.joinToString(", "))
        s.cypher.append(" MATCH (log)-[:CONTAINS]->(_placeholder_trace:Trace)")
        s.cypher.append(" OPTIONAL MATCH (_placeholder_trace)-[:HAS_EVENT]->(_placeholder_event:Event)")
        s.cypher.append(" WITH log, ")
            .append(placeholderCase.aggregateColumns.joinToString(", ") { it.alias })
            .append(", _placeholder_trace, _placeholder_event")
        val returnColumns = buildList {
            add("log.logId AS $SYNTHETIC_LOG_ID_ALIAS")
            add(logMetadataProjection())
            add("_placeholder_trace.traceId AS $SYNTHETIC_TRACE_ID_ALIAS")
            add("_placeholder_trace.importOrder AS $SYNTHETIC_TRACE_ORDER_ALIAS")
            add("{} AS $SYNTHETIC_EVENT_ALIAS")
            addAll(placeholderCase.aggregateColumns.map { it.alias })
        }
        s.cypher.append(" RETURN ").append(returnColumns.joinToString(", "))
        s.cypher.append(" ORDER BY $SYNTHETIC_LOG_ID_ALIAS, $SYNTHETIC_TRACE_ORDER_ALIAS, _placeholder_event.eventId")
    }

    private fun emitTraceAggregatePlaceholderHierarchyIfNeeded(s: CypherBuildState): Boolean {
        val placeholderCase = traceAggregatePlaceholderCase(s) ?: return false

        registerTraceAggregatePlaceholderAliases(s, placeholderCase)
        emitTraceAggregatePlaceholderCypher(s, placeholderCase)
        return true
    }

    private fun traceAggregatePlaceholderCase(s: CypherBuildState): TraceAggregatePlaceholderCase? {
        if (!s.facts.hasAnyAggregation) return null
        if (s.plan.groupBy?.keys?.isNotEmpty() == true) return null
        if (s.plan.projection.columns.isEmpty()) return null
        if (s.plan.projection.columns.any {
                it.scope != Scope.TRACE ||
                    !CypherAggregationInspector.containsAggregation(it.expression) ||
                    !s.facts.aggregationArgumentUsesBaseScope(it.expression, Scope.LOG)
            }
        ) {
            return null
        }
        if (s.plan.filter?.let { s.facts.scopesOf(it) - Scope.LOG }?.isNotEmpty() == true) {
            return null
        }

        return TraceAggregatePlaceholderCase(s.plan.projection.columns)
    }

    private fun registerTraceAggregatePlaceholderAliases(
        s: CypherBuildState,
        placeholderCase: TraceAggregatePlaceholderCase,
    ) {
        placeholderCase.aggregateColumns.forEach(s::registerProjectedColumnAlias)
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_LOG_ID_ALIAS,
            scope = Scope.LOG,
        )
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_NULL_EVENT_COUNT_ALIAS,
            scope = Scope.TRACE,
        )
    }

    private fun emitTraceAggregatePlaceholderCypher(
        s: CypherBuildState,
        placeholderCase: TraceAggregatePlaceholderCase,
    ) {
        val aggregateColumns = placeholderCase.aggregateColumns.map { col ->
            "${expressions.render(col.expression, s)} AS ${col.alias}"
        }
        s.cypher.append(" WITH log, ").append(aggregateColumns.joinToString(", "))
        s.cypher.append(" MATCH (log)-[:CONTAINS]->(_placeholder_trace:Trace)")
        s.cypher.append(" OPTIONAL MATCH (_placeholder_trace)-[:HAS_EVENT]->(_placeholder_event:Event)")
        s.cypher.append(" WITH log, ")
            .append(placeholderCase.aggregateColumns.joinToString(", ") { it.alias })
            .append(", _placeholder_trace, count(_placeholder_event) AS _trace_event_count_")
        val returnColumns = buildList {
            add("log.logId AS $SYNTHETIC_LOG_ID_ALIAS")
            add(logMetadataProjection())
            add("max(_trace_event_count_) AS $SYNTHETIC_NULL_EVENT_COUNT_ALIAS")
            addAll(placeholderCase.aggregateColumns.map { it.alias })
        }
        s.cypher.append(" RETURN ").append(returnColumns.joinToString(", "))
        s.cypher.append(" ORDER BY $SYNTHETIC_LOG_ID_ALIAS")
    }

    private data class LogAggregatePlaceholderCase(
        val aggregateColumns: List<ProjectedColumn>,
    )

    private data class TraceAggregatePlaceholderCase(
        val aggregateColumns: List<ProjectedColumn>,
    )
}
