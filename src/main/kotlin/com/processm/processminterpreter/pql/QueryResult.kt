package com.processm.processminterpreter.pql

import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.pql.catalog.Scope

/**
 * [logs] is supplied as a provider and materialized on first access, so the
 * laziness of the executor's hierarchy reconstruction survives into the
 * application layer: JSON responses read only [rows] and never trigger it.
 */
class QueryResult(
    logsProvider: () -> List<XesLog>,
    val rows: List<Map<String, Any?>> = emptyList(),
    val rowCount: Int = 0,
    val executedQueryDescription: String = "",
    val hasExplicitSelect: Boolean = false,
    val selectAllScopes: Set<Scope> = emptySet(),
    val projectedLogAttributes: Set<String> = emptySet(),
    val projectedTraceStandardAttributes: Set<String> = emptySet(),
) {
    constructor(
        logs: List<XesLog>,
        rows: List<Map<String, Any?>> = emptyList(),
        rowCount: Int = 0,
        executedQueryDescription: String = "",
        hasExplicitSelect: Boolean = false,
        selectAllScopes: Set<Scope> = emptySet(),
        projectedLogAttributes: Set<String> = emptySet(),
        projectedTraceStandardAttributes: Set<String> = emptySet(),
    ) : this(
        { logs },
        rows,
        rowCount,
        executedQueryDescription,
        hasExplicitSelect,
        selectAllScopes,
        projectedLogAttributes,
        projectedTraceStandardAttributes,
    )

    val logs: List<XesLog> by lazy(logsProvider)
}
