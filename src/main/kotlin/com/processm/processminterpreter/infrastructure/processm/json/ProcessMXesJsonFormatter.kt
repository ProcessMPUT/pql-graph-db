package com.processm.processminterpreter.infrastructure.processm.json

import com.processm.processminterpreter.application.ports.ProcessMJsonFormatter
import com.processm.processminterpreter.application.ports.QueryJsonProjection
import com.processm.processminterpreter.domain.pql.catalog.Scope
import org.springframework.stereotype.Component

@Component
class ProcessMXesJsonFormatter : ProcessMJsonFormatter {
    override fun formatAsXesJson(result: QueryJsonProjection): List<Map<String, Any?>> {
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
