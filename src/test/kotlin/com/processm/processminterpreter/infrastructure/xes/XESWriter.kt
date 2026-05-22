package com.processm.processminterpreter.infrastructure.xes

import com.processm.processminterpreter.domain.log.AttributeScope
import com.processm.processminterpreter.domain.log.Classifier
import com.processm.processminterpreter.domain.log.GlobalAttribute
import com.processm.processminterpreter.domain.log.xes.XesEvent
import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.log.xes.XesTrace
import com.processm.processminterpreter.application.ports.XesWriteOptions
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Test helper for legacy flat query-result rows.
 *
 * Production export goes through [com.processm.processminterpreter.application.ports.XesWriter]
 * and [OpenXesWriter]. This helper stays in test sources because some imported
 * regression suites still exercise the old flat-row shape directly.
 */
class XESWriter(
    private val delegate: OpenXesWriter = OpenXesWriter(),
) {
    private val logger = LoggerFactory.getLogger(XESWriter::class.java)

    fun writeXES(
        results: List<Map<String, Any?>>,
        outputStream: OutputStream,
        compress: Boolean = false,
        logName: String = "Query Result Log",
    ) {
        logger.info("Writing XES output: ${results.size} records, compress: $compress")

        delegate.write(
            logs = listOf(toXesLog(results, logName)),
            output = outputStream,
            options = XesWriteOptions(compress = compress, logName = logName),
        )

        logger.info("XES output written successfully")
    }

    private fun toXesLog(
        results: List<Map<String, Any?>>,
        logName: String,
    ): XesLog {
        val traces =
            groupByTraces(results).map { (traceId, rows) ->
                toXesTrace(traceId, rows)
            }

        return XesLog(
            conceptName = logName,
            classifiers =
                listOf(
                    Classifier("Activity", listOf("concept:name")),
                    Classifier("Activity+Lifecycle", listOf("concept:name", "lifecycle:transition")),
                ),
            traceGlobals =
                listOf(
                    GlobalAttribute(AttributeScope.TRACE, "concept:name", "__INVALID__"),
                ),
            eventGlobals =
                listOf(
                    GlobalAttribute(AttributeScope.EVENT, "concept:name", "__INVALID__"),
                    GlobalAttribute(AttributeScope.EVENT, "lifecycle:transition", "complete"),
                    GlobalAttribute(AttributeScope.EVENT, "time:timestamp", "1970-01-01T01:00:00.000+01:00"),
                ),
            customAttributes =
                mapOf(
                    "source" to "ProcessM Interpreter Neo4j",
                    "created" to LocalDateTime.now().atZone(ZoneOffset.UTC).toInstant(),
                ),
            traces = traces,
        )
    }

    private fun toXesTrace(
        traceId: String,
        rows: List<Map<String, Any?>>,
    ): XesTrace {
        val firstRow = rows.firstOrNull()
        val traceData = firstRow?.let { extractTraceData(it) }.orEmpty()
        val customTraceAttributes =
            traceData.filterKeys { it !in TRACE_EXCLUDED_KEYS }

        val events =
            rows
                .sortedBy { extractTimestamp(it) }
                .map { row -> toXesEvent(row) }

        return XesTrace(
            conceptName = traceId,
            customAttributes = customTraceAttributes,
            events = events,
        )
    }

    private fun toXesEvent(row: Map<String, Any?>): XesEvent {
        val eventData = extractEventData(row)
        val activity = eventData["activity"]?.toString() ?: eventData["concept:name"]?.toString() ?: "Unknown Activity"
        val timestamp = parseTimestamp(eventData["timestamp"] ?: eventData["time:timestamp"])
        val resource = eventData["resource"]?.toString() ?: eventData["org:resource"]?.toString()
        val lifecycle = eventData["lifecycle"]?.toString() ?: eventData["lifecycle:transition"]?.toString() ?: "complete"
        val cost = parseDouble(eventData["cost"] ?: eventData["cost:total"])

        val customAttributes =
            eventData.filterKeys { it !in EVENT_EXCLUDED_KEYS }

        return XesEvent(
            conceptName = activity,
            timeTimestamp = timestamp,
            orgResource = resource,
            lifecycleTransition = lifecycle,
            costTotal = cost,
            customAttributes = customAttributes,
        )
    }

    private fun groupByTraces(results: List<Map<String, Any?>>): Map<String, List<Map<String, Any?>>> =
        results.groupBy { record ->
            when {
                record.containsKey("traceId") -> record["traceId"]?.toString()
                record.containsKey("caseId") -> record["caseId"]?.toString()
                record.containsKey("t_traceId") -> record["t_traceId"]?.toString()
                record.containsKey("trace_traceId") -> record["trace_traceId"]?.toString()
                record.containsKey("t_caseId") -> record["t_caseId"]?.toString()
                record.containsKey("trace_caseId") -> record["trace_caseId"]?.toString()
                record.containsKey("trace") -> extractTraceData(record)["traceId"]?.toString() ?: extractTraceData(record)["caseId"]?.toString()
                record.containsKey("t") -> extractTraceData(record)["traceId"]?.toString() ?: extractTraceData(record)["caseId"]?.toString()
                else -> null
            } ?: "unknown-trace-${System.nanoTime()}"
        }

    private fun extractTraceData(record: Map<String, Any?>): Map<String, Any?> =
        toStringMap(record["trace"])
            ?: toStringMap(record["t"])
            ?: emptyMap()

    private fun extractEventData(record: Map<String, Any?>): Map<String, Any?> =
        toStringMap(record["event"])
            ?: toStringMap(record["e"])
            ?: record

    private fun toStringMap(value: Any?): Map<String, Any?>? =
        when {
            value == null -> null
            value is Map<*, *> && value.keys.all { it is String } ->
                value.entries.associate { (key, nested) -> key.toString() to nested }
            else -> null
        }

    private fun extractTimestamp(record: Map<String, Any?>): Instant? =
        parseTimestamp(extractEventData(record)["timestamp"] ?: extractEventData(record)["time:timestamp"])

    private fun parseTimestamp(value: Any?): Instant? =
        when (value) {
            is Instant -> value
            is ZonedDateTime -> value.toInstant()
            is LocalDateTime -> value.toInstant(ZoneOffset.UTC)
            is String -> runCatching { Instant.parse(value) }.getOrNull()
            else -> null
        }

    private fun parseDouble(value: Any?): Double? =
        when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }

    private companion object {
        val TRACE_EXCLUDED_KEYS = setOf("traceId", "id", "labels")

        val EVENT_EXCLUDED_KEYS =
            setOf(
                "id",
                "eventId",
                "activity",
                "timestamp",
                "resource",
                "lifecycle",
                "cost",
                "concept:name",
                "time:timestamp",
                "org:resource",
                "lifecycle:transition",
                "cost:total",
                "labels",
                "createdAt",
            )
    }
}
