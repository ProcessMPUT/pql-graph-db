package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

/**
 * Routes SELECT plans to first-class ProcessM result shapes before the generic
 * match/aggregate/project pipeline is used.
 */
internal class CypherSelectShapeRouter(
    expressions: CypherExpressionRenderer,
) {
    private val aggregatePlaceholders = CypherAggregatePlaceholderHierarchyRenderer(expressions)
    private val traceGroupAggregates = CypherTraceGroupAggregateRenderer(expressions)
    private val traceVariants = CypherTraceVariantGroupByRenderer(expressions)
    private val eventGroupByOnly = CypherEventGroupByOnlyRenderer(expressions)
    private val aggregateOrderBy = CypherAggregateOrderByRenderer(expressions)

    fun emitPreMatchShapeIfNeeded(s: CypherBuildState): Boolean =
        eventGroupByOnly.emitLimitedBeforeMatchIfNeeded(s)

    fun emitPostMatchShapeIfNeeded(s: CypherBuildState): Boolean =
        aggregatePlaceholders.emitIfNeeded(s) ||
            traceVariants.emitIfNeeded(s) ||
            traceGroupAggregates.emitIfNeeded(s) ||
            eventGroupByOnly.emitIfNeeded(s) ||
            aggregateOrderBy.emitIfNeeded(s)
}
