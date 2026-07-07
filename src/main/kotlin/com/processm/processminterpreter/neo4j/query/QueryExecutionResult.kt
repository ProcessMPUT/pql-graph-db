package com.processm.processminterpreter.neo4j.query

import com.processm.processminterpreter.xes.model.XesLog

/**
 * [logs] is supplied as a provider and reconstructed on first access: the
 * interactive JSON path consumes only [rows] (the flat rows ARE the response
 * payload) and would otherwise pay an O(events) heap copy for a hierarchy it
 * never reads. Paths that need the hierarchy (XES export, ProcessM-compatible
 * JSON) force it exactly once.
 */
class QueryExecutionResult(
    logsProvider: () -> List<XesLog>,
    val rows: List<Map<String, Any?>>,
    val rowCount: Int,
    val executedQueryDescription: String,
) {
    constructor(
        logs: List<XesLog>,
        rows: List<Map<String, Any?>>,
        rowCount: Int,
        executedQueryDescription: String,
    ) : this({ logs }, rows, rowCount, executedQueryDescription)

    val logs: List<XesLog> by lazy(logsProvider)
}
