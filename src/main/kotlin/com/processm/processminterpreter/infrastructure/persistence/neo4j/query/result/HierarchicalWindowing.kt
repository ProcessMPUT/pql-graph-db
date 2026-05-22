package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.result

import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.pql.plan.HierarchicalLimits
import com.processm.processminterpreter.domain.pql.plan.HierarchicalOffsets

internal object HierarchicalWindowing {
    fun apply(
        logs: List<XesLog>,
        limits: HierarchicalLimits,
        offsets: HierarchicalOffsets,
        defaultLimits: HierarchicalLimits,
    ): List<XesLog> {
        val logOffset = offsets.log?.toInt()?.coerceAtLeast(0) ?: 0
        val logLimit = cappedLimit(limits.log, defaultLimits.log)
        val traceOffset = offsets.trace?.toInt()?.coerceAtLeast(0) ?: 0
        val traceLimit = cappedLimit(limits.trace, defaultLimits.trace)
        val eventOffset = offsets.event?.toInt()?.coerceAtLeast(0) ?: 0
        val eventLimit = cappedLimit(limits.event, defaultLimits.event)

        return slice(logs, logOffset, logLimit).map { log ->
            log.copy(
                traces = slice(log.traces, traceOffset, traceLimit).map { trace ->
                    trace.copy(
                        events = slice(trace.events, eventOffset, eventLimit),
                        nullEventCount = sliceNullEventCount(trace.nullEventCount, eventOffset, eventLimit),
                    )
                },
            )
        }
    }

    private fun <T> slice(list: List<T>, offset: Int, limit: Int?): List<T> {
        if (offset >= list.size) return emptyList()
        val afterOffset = list.drop(offset)
        return if (limit == null) afterOffset else afterOffset.take(limit)
    }

    private fun sliceNullEventCount(count: Int, offset: Int, limit: Int?): Int {
        val afterOffset = (count - offset).coerceAtLeast(0)
        return limit?.let { afterOffset.coerceAtMost(it) } ?: afterOffset
    }

    private fun cappedLimit(explicit: Long?, default: Long?): Int? {
        val candidates = listOfNotNull(explicit, default).mapNotNull { value ->
            value.toInt().takeIf { it >= 0 }
        }
        return candidates.minOrNull()
    }
}
