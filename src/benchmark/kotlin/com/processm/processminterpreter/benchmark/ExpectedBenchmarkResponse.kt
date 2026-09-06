package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.databind.JsonNode
import com.processm.processminterpreter.processm.json.ProcessMXesJsonParser
import java.time.Instant

/** A literal oracle from the controlled input definition, independent of inter-system parity. */
data class ExpectedBenchmarkResponse(
    val logs: Int,
    val traces: Int,
    val events: Int,
    val logAttributes: Map<String, Attribute> = emptyMap(),
    val traceAttributes: Map<String, Attribute> = emptyMap(),
) {
    data class Attribute(val type: String, val value: String)

    fun mismatch(body: String): String? {
        val root = runCatching { ProcessMXesJsonParser.parse(body) }.getOrNull()
            ?: return "Expected an XES-JSON response"
        val expectedCounts = XesJsonCounts(logs, traces, events)
        val actualCounts = XesJsonCounting.count(root)
        if (actualCounts != expectedCounts) return "Expected $expectedCounts, received $actualCounts"
        if (logAttributes.isEmpty() && traceAttributes.isEmpty()) return null
        if (!root.isArray || root.size() != 1 || !root[0].path("log").isObject)
            return "Aggregate controls require exactly one log document"
        val log = root[0]["log"]
        attributeMismatch(log, "log", logAttributes)?.let { return it }
        if (traceAttributes.isNotEmpty()) {
            val traces = log.path("trace").let { if (it.isArray) it.toList() else listOf(it) }
            if (actualCounts.traces != 1 || traces.size != 1 || !traces.single().isObject)
                return "Trace aggregate controls require exactly one trace"
            attributeMismatch(traces.single(), "trace", traceAttributes)?.let { return it }
        }
        return null
    }

    private fun attributeMismatch(node: JsonNode, scope: String, attributes: Map<String, Attribute>): String? {
        for ((key, expected) in attributes) {
            val matches = node.properties().asSequence().flatMap { (type, value) ->
                (if (value.isArray) value.toList() else listOf(value)).asSequence()
                    .filter { it.isObject && it.path("@key").asText() == key }
                    .map { type to it.path("@value").asText() }
            }.toList()
            if (matches.size != 1 || matches.single().first != expected.type)
                return "Expected one ${expected.type} $scope attribute $key, received $matches"
            val actualValue = matches.single().second
            val equal = runCatching {
                when (expected.type) {
                    "int" -> actualValue.toBigInteger() == expected.value.toBigInteger()
                    "date" -> Instant.parse(actualValue) == Instant.parse(expected.value)
                    else -> actualValue == expected.value
                }
            }.getOrDefault(false)
            if (!equal) return "Expected $scope attribute $key=${expected.value}, received $actualValue"
        }
        return null
    }

}
