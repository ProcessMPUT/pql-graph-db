package com.processm.processminterpreter.neo4j.query.result

import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import com.processm.processminterpreter.pql.common.HierarchicalOffsets

internal object HierarchicalWindowing {
    fun apply(
        logs: List<XesLog>,
        limits: HierarchicalLimits,
        offsets: HierarchicalOffsets,
        defaultLimits: HierarchicalLimits,
    ): List<XesLog> {
        val logOffset = nonNegativeInt(offsets.log) ?: 0
        val logLimit = cappedLimit(limits.log, defaultLimits.log)
        val traceOffset = nonNegativeInt(offsets.trace) ?: 0
        val traceLimit = cappedLimit(limits.trace, defaultLimits.trace)
        val eventOffset = nonNegativeInt(offsets.event) ?: 0
        val eventLimit = cappedLimit(limits.event, defaultLimits.event)

        val windowedLogs = slice(logs, logOffset, logLimit)
        var changedLogs: MutableList<XesLog>? = null
        for ((logIndex, log) in windowedLogs.withIndex()) {
            val windowedTraces = slice(log.traces, traceOffset, traceLimit)
            var changedTraces: MutableList<XesTrace>? = null
            for ((traceIndex, trace) in windowedTraces.withIndex()) {
                val events = slice(trace.events, eventOffset, eventLimit)
                val nullEventCount = sliceNullEventCount(trace.nullEventCount, eventOffset, eventLimit)
                if (events !== trace.events || nullEventCount != trace.nullEventCount) {
                    if (changedTraces == null) {
                        changedTraces = ArrayList(windowedTraces.size)
                        changedTraces.addAll(windowedTraces.subList(0, traceIndex))
                    }
                    changedTraces.add(trace.copy(events = events, nullEventCount = nullEventCount))
                } else {
                    changedTraces?.add(trace)
                }
            }

            val traces = changedTraces ?: windowedTraces
            val transformedLog = if (traces === log.traces) log else log.copy(traces = traces)
            if (transformedLog !== log) {
                if (changedLogs == null) {
                    changedLogs = ArrayList(windowedLogs.size)
                    changedLogs.addAll(windowedLogs.subList(0, logIndex))
                }
                changedLogs.add(transformedLog)
            } else {
                changedLogs?.add(log)
            }
        }
        return changedLogs ?: windowedLogs
    }

    private fun <T> slice(list: List<T>, offset: Int, limit: Int?): List<T> {
        if (offset >= list.size) return emptyList()
        val toIndex = limit?.let { (offset.toLong() + it).coerceAtMost(list.size.toLong()).toInt() } ?: list.size
        if (offset == 0 && toIndex == list.size) return list
        return list.subList(offset, toIndex).toList()
    }

    private fun sliceNullEventCount(count: Int, offset: Int, limit: Int?): Int {
        val afterOffset = (count - offset).coerceAtLeast(0)
        return limit?.let { afterOffset.coerceAtMost(it) } ?: afterOffset
    }

    private fun cappedLimit(explicit: Long?, default: Long?): Int? {
        val explicitLimit = nonNegativeInt(explicit)
        val defaultLimit = nonNegativeInt(default)
        return when {
            explicitLimit == null -> defaultLimit
            defaultLimit == null -> explicitLimit
            else -> minOf(explicitLimit, defaultLimit)
        }
    }

    private fun nonNegativeInt(value: Long?): Int? =
        value?.takeIf { it >= 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
}
