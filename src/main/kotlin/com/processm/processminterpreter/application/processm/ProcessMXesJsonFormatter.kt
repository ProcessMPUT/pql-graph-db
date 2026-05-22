package com.processm.processminterpreter.application.processm

import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.pql.catalog.Scope
import org.springframework.stereotype.Component

data class QueryJsonProjection(
    val logs: List<XesLog>,
    val rows: List<Map<String, Any?>> = emptyList(),
    val hasExplicitSelect: Boolean = false,
    val selectAllScopes: Set<Scope> = emptySet(),
    val projectedTraceStandardAttributes: Set<String> = emptySet(),
    val includeTraces: Boolean = true,
    val includeEvents: Boolean = true,
)

@Component
class ProcessMXesJsonFormatter {
    fun formatAsXesJson(result: QueryJsonProjection): List<Map<String, Any?>> {
        if (result.logs.isEmpty()) {
            return emptyList()
        }

        val internalKeys = setOf("t_traceId", "l_logId")
        val resultKeys = result.rows.firstOrNull()?.keys ?: emptySet()
        val isProjectedQuery =
            result.hasExplicitSelect ||
            resultKeys.any { key ->
                key !in internalKeys && (
                    key.startsWith("l_") || key.startsWith("t_") || key.startsWith("e_") ||
                        key.startsWith("log_") || key.startsWith("trace_") || key.startsWith("event_")
                )
            } ||
                resultKeys.any { key ->
                    key !in internalKeys && !key.startsWith("l_") && !key.startsWith("t_") &&
                        !key.startsWith("e_") && key !in setOf("event", "trace", "log", "e", "t", "l")
                }

        val projectedTraceAttrs =
            if (Scope.TRACE in result.selectAllScopes) {
                setOf("concept:name", "identity:id", "cost:currency", "cost:total")
            } else {
                result.projectedTraceStandardAttributes
            }

        return listOf(
            XESJsonConverter.convertToXESJson(
                logs = result.logs,
                isProjectedQuery = isProjectedQuery,
                projectedTraceAttrs = projectedTraceAttrs,
                includeTraces = result.includeTraces,
                includeEvents = result.includeEvents,
            ),
        )
    }
}
