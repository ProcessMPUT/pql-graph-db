package com.processm.processminterpreter.pql

/**
 * Data class representing a Cypher query with parameters
 */
data class CypherQuery(
    val query: String,
    val parameters: Map<String, Any> = emptyMap(),

    /**
     * Hierarchical limits to be applied after reconstruction
     * Format: [logLimit, traceLimit, eventLimit]
     * Example: [1, 3, 5] means max 1 log, 3 traces per log, 5 events per trace
     * null means no limit at that level
     */
    val hierarchicalLimits: Map<String, Int?> = emptyMap(),

    /**
     * Maps column aliases to their original PQL expressions.
     * Used by HierarchyReconstructor to identify function-result columns.
     * Example: "year_event_time_timestamp_" → "year(event:time:timestamp)"
     */
    val columnAliases: Map<String, ColumnAlias> = emptyMap(),

    /**
     * True when the query uses aggregation grouping (explicit GROUP BY or implicit per-trace grouping).
     * HierarchyReconstructor uses this to create a synthetic aggregated event per trace
     * when no event data is projected.
     */
    val isAggregationResult: Boolean = false,

    /**
     * True when the SELECT clause explicitly selects trace-scope attributes (t:*, t:name, etc.).
     * Used by PQLQueryController to determine whether properties(trace) in RETURN
     * represents user-selected data vs internal grouping metadata.
     */
    val hasExplicitTraceSelect: Boolean = false,

    /**
     * True when this is a DELETE query (DETACH DELETE).
     * PQLQueryService uses this to execute in a write transaction.
     */
    val isDelete: Boolean = false,

    /**
     * True when the query has an explicit ORDER BY on trace-scope attributes.
     * HierarchyReconstructor uses this to skip importOrder-based sorting
     * and preserve the Cypher-determined trace order.
     */
    val hasTraceOrderBy: Boolean = false,
) {
    override fun toString(): String {
        return "CypherQuery(query='$query', parameters=$parameters, hierarchicalLimits=$hierarchicalLimits)"
    }
}

/**
 * Metadata for a projected column alias
 */
data class ColumnAlias(
    /** Original PQL expression (e.g., "year(event:time:timestamp)") */
    val pqlExpression: String,
    /** Scope of the innermost attribute (LOG, TRACE, EVENT) */
    val scope: String,
)
