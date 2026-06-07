package com.processm.processminterpreter.application.ports

import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.pql.catalog.Scope

data class QueryJsonProjection(
    val logs: List<XesLog>,
    val rows: List<Map<String, Any?>> = emptyList(),
    val hasExplicitSelect: Boolean = false,
    val selectAllScopes: Set<Scope> = emptySet(),
    val projectedLogAttributes: Set<String> = emptySet(),
    val projectedTraceStandardAttributes: Set<String> = emptySet(),
    val includeTraces: Boolean = true,
    val includeEvents: Boolean = true,
)

fun interface ProcessMJsonFormatter {
    fun formatAsXesJson(result: QueryJsonProjection): List<Map<String, Any?>>
}
