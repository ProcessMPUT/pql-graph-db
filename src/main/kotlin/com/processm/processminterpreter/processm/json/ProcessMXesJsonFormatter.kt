package com.processm.processminterpreter.processm.json

import com.processm.processminterpreter.processm.json.QueryJsonProjection
import com.processm.processminterpreter.pql.catalog.Scope
import org.springframework.stereotype.Component

@Component
class ProcessMXesJsonFormatter {
    fun formatAsXesJson(result: QueryJsonProjection): List<Map<String, Any?>> {
        if (result.logs.isEmpty()) {
            return emptyList()
        }

        val isProjectedQuery = result.hasExplicitSelect

        val logSelectAll = Scope.LOG in result.selectAllScopes
        val projectedTraceAttrs =
            if (Scope.TRACE in result.selectAllScopes) {
                setOf("concept:name", "identity:id", "cost:currency", "cost:total")
            } else {
                result.projectedTraceStandardAttributes
            }

        return XESJsonConverter.convertToXESJsonDocuments(
            logs = result.logs,
            isProjectedQuery = isProjectedQuery,
            logSelectAll = logSelectAll,
            projectedLogAttrs = result.projectedLogAttributes,
            projectedTraceAttrs = projectedTraceAttrs,
            includeTraces = result.includeTraces,
            includeEvents = result.includeEvents,
        )
    }
}
