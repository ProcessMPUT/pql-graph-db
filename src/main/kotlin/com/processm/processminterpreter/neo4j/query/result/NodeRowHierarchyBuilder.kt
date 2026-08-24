package com.processm.processminterpreter.neo4j.query.result

import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.cypher.SYNTHETIC_GROUPED_EVENT_ALIAS
import java.util.IdentityHashMap

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
        private val logs = linkedMapOf<Any?, LogBuilder>()
        private val traceOrders = IdentityHashMap<TraceBuilder, Long>()
        private val eventOrders = IdentityHashMap<EventBuilder, Long>()
        private var sawSplitRow = false

        fun absorb(row: Map<String, Any?>) {
            sawSplitRow = sawSplitRow || row.containsKey("_kind")
            val eventList = row.nodePropertyList("events")

            val logBuilder = absorbLog(row)
            if (!row.hasNodeColumn("trace") && eventList == null && !row.hasNodeColumn("event")) return

            val traceBuilder = absorbTrace(row, logBuilder)
            traceBuilder.absorbEvents(row, eventList)
        }

        private fun absorbLog(row: Map<String, Any?>): LogBuilder {
            val logProps = row.nodeProperties("log")
            val logKey = row["_logKey"] ?: logProps["logId"] ?: SyntheticHierarchyKey
            val logBuilder = logs.getOrPut(logKey) { LogBuilder() }
            logBuilder.absorbMetadataNode(logProps)
            if (Scope.LOG in selectedScopes && logProps.isNotEmpty()) {
                logBuilder.absorbNode(logProps)
            }
            return logBuilder
        }

        private fun absorbTrace(row: Map<String, Any?>, logBuilder: LogBuilder): TraceBuilder {
            val traceProps = row.nodeProperties("trace")
            val traceKey = row["_traceKey"] ?: traceProps["traceId"] ?: SyntheticHierarchyKey
            val traceBuilder = logBuilder.traces.getOrPut(traceKey) { TraceBuilder() }
            (row["_traceOrder"] as? Number)?.toLong()?.let { traceOrders.putIfAbsent(traceBuilder, it) }
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
                eventList != null -> eventList.forEach { addEvent(it, groupedEvent, order = null) }
                row.hasNodeColumn("event") -> addEvent(
                    row.nodeProperties("event"),
                    groupedEvent,
                    order = (row["_eventOrder"] as? Number)?.toLong(),
                )
            }
        }

        private fun TraceBuilder.addEvent(
            props: Map<String, Any?>,
            groupedEvent: Boolean,
            order: Long?,
        ) {
            val eventBuilder = EventBuilder()
            if (Scope.EVENT in selectedScopes || groupedEvent) {
                eventBuilder.absorbNode(props)
            }
            events.add(eventBuilder)
            if (order != null) eventOrders[eventBuilder] = order
        }

        fun build(): List<XesLog> {
            if (!sawSplitRow) return logs.values.map { it.build() }

            logs.values.forEach { log ->
                val orderedTraces = log.traces.entries.sortedWith(
                    compareBy<Map.Entry<Any?, TraceBuilder>> { traceOrders[it.value] ?: Long.MAX_VALUE }
                        .thenBy { it.key.toString() },
                )
                log.traces.clear()
                orderedTraces.forEach { (key, trace) -> log.traces[key] = trace }
                log.traces.values.forEach { trace ->
                    trace.events.sortWith(compareBy { eventOrders[it] ?: Long.MAX_VALUE })
                }
            }

            return logs.entries
                .sortedBy { it.key.toString() }
                .map { it.value.build() }
        }
    }
}
