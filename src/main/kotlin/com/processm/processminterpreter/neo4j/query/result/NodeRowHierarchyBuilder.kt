package com.processm.processminterpreter.neo4j.query.result

import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.cypher.SYNTHETIC_GROUPED_EVENT_ALIAS

internal class NodeRowHierarchyBuilder {
    fun reconstruct(
        rows: List<Map<String, Any?>>,
        selectAllScopes: Set<Scope> = emptySet(),
    ): List<XesLog> {
        val accumulator = accumulator(selectAllScopes)
        rows.forEach(accumulator::absorb)
        return accumulator.build()
    }

    fun accumulator(selectAllScopes: Set<Scope> = emptySet()): Accumulator = Accumulator(selectAllScopes)

    class Accumulator(selectAllScopes: Set<Scope>) {
        private val selectedScopes =
            selectAllScopes.ifEmpty {
                setOf(Scope.LOG, Scope.TRACE, Scope.EVENT)
            }
        private val logs = linkedMapOf<Any, LogBuilder>()

        fun absorb(row: Map<String, Any?>) {
            val eventList = row.nodePropertyList("events")

            val logBuilder = absorbLog(row)
            if (!row.hasNodeColumn("trace") && eventList == null && !row.hasNodeColumn("event")) return

            val traceBuilder = absorbTrace(row, logBuilder)
            traceBuilder.absorbEvents(row, eventList)
        }

        private fun absorbLog(row: Map<String, Any?>): LogBuilder {
            val logProps = row.nodeProperties("log")
            val logKey: Any = row["_logKey"] ?: logProps["logId"] ?: SYNTHETIC_KEY
            val logBuilder = logs.getOrPut(logKey) { LogBuilder() }
            logBuilder.absorbMetadataNode(logProps)
            if (Scope.LOG in selectedScopes && logProps.isNotEmpty()) {
                logBuilder.absorbNode(logProps)
            }
            return logBuilder
        }

        private fun absorbTrace(row: Map<String, Any?>, logBuilder: LogBuilder): TraceBuilder {
            val traceProps = row.nodeProperties("trace")
            val traceKey: Any = row["_traceKey"] ?: traceProps["traceId"] ?: SYNTHETIC_KEY
            val traceBuilder = logBuilder.traces.getOrPut(mapOf("_id" to traceKey)) { TraceBuilder() }
            if (Scope.TRACE in selectedScopes && traceProps.isNotEmpty()) {
                traceBuilder.absorbNode(traceProps)
            }
            return traceBuilder
        }

        private fun TraceBuilder.absorbEvents(
            row: Map<String, Any?>,
            eventList: List<Map<String, Any?>>?,
        ) {
            val groupedEvent = row[SYNTHETIC_GROUPED_EVENT_ALIAS] == true
            when {
                eventList != null -> eventList.forEach { addEvent(it, groupedEvent) }
                row.hasNodeColumn("event") -> addEvent(row.nodeProperties("event"), groupedEvent)
            }
        }

        private fun TraceBuilder.addEvent(props: Map<String, Any?>, groupedEvent: Boolean) {
            val eventBuilder = EventBuilder()
            if (Scope.EVENT in selectedScopes || groupedEvent) {
                eventBuilder.absorbNode(props)
            }
            events.add(eventBuilder)
        }

        fun build(): List<XesLog> = logs.values.map { it.build() }
    }
}
