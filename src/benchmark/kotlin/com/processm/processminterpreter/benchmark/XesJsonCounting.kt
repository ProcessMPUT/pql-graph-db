package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.databind.JsonNode
import com.processm.processminterpreter.processm.json.ProcessMXesJsonParser

data class XesJsonCounts(
    val logs: Int,
    val traces: Int,
    val events: Int,
) {
    companion object {
        val EMPTY = XesJsonCounts(logs = 0, traces = 0, events = 0)
    }
}

/**
 * Counts logs, traces, and events in a ProcessM XES-JSON query response (Q4 parity input).
 *
 * Expected shape: a JSON array of `{"log": {...}}` documents. Inside a log, `trace` is
 * absent, null, a single object, or an array; the same applies to `event` inside a trace.
 * A node may additionally nest further siblings under its own key, mirroring the
 * ProcessM XES-JSON single-or-array convention handled by the compatibility reader.
 */
object XesJsonCounting {
    fun count(body: String): XesJsonCounts =
        runCatching { count(ProcessMXesJsonParser.parse(body)) }.getOrDefault(XesJsonCounts.EMPTY)

    fun count(root: JsonNode): XesJsonCounts {
        val documents = if (root.isArray) root.toList() else listOf(root)
        var logs = 0
        var traces = 0
        var events = 0
        documents.forEach { document ->
            collectNodes(document.get("log"), "log").forEach { log ->
                logs++
                val traceNodes = collectNodes(log.get("trace"), "trace")
                traces += traceNodes.size
                traceNodes.forEach { trace ->
                    events += collectNodes(trace.get("event"), "event").size
                }
            }
        }
        return XesJsonCounts(logs = logs, traces = traces, events = events)
    }

    private fun collectNodes(
        value: JsonNode?,
        key: String,
    ): List<JsonNode> =
        when {
            value == null || value.isNull -> emptyList()
            value.isArray -> value.flatMap { collectNodes(it, key) }
            value.isObject -> listOf(value) + collectNodes(value.get(key), key)
            else -> emptyList()
        }
}
