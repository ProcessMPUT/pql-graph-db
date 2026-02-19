package com.processm.processminterpreter.util

import com.processm.processminterpreter.model.hierarchical.*
import com.processm.processminterpreter.pql.ColumnAlias
import org.slf4j.LoggerFactory
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Reconstructs hierarchical Log/Trace/Event structure from flat Neo4j query results
 *
 * Converts List<Map<String, Any?>> → List<Log>
 * Handles grouping by log → trace → events
 */
object HierarchyReconstructor {
    private val logger = LoggerFactory.getLogger(HierarchyReconstructor::class.java)

    /**
     * Reconstruct hierarchical structure from flat query results
     *
     * @param flatResults Flat results from Neo4j Cypher query
     * @param hierarchicalLimits Optional hierarchical limits to apply (log, trace, event)
     * @return List of Log objects with hierarchical structure
     */
    // Column alias metadata from QLToCypherVisitor — maps alias → PQL expression + scope
    private var currentColumnAliases: Map<String, ColumnAlias> = emptyMap()

    fun reconstruct(
        flatResults: List<Map<String, Any?>>,
        hierarchicalLimits: Map<String, Int?> = emptyMap(),
        columnAliases: Map<String, ColumnAlias> = emptyMap()
    ): List<Log> {
        currentColumnAliases = columnAliases
        if (flatResults.isEmpty()) {
            return emptyList()
        }

        logger.debug("Reconstructing hierarchy from ${flatResults.size} flat records")
        logger.debug("Hierarchical limits: $hierarchicalLimits")

        // Group by log ID → trace ID → events
        val logGroups = groupByLog(flatResults)

        // Apply log limit
        val logLimit = hierarchicalLimits["log"]
        val limitedLogGroups = if (logLimit != null && logLimit > 0) {
            logGroups.entries.take(logLimit).associate { it.key to it.value }
        } else {
            logGroups
        }

        val logs = limitedLogGroups.map { (logId, logRecords) ->
            buildLog(logId, logRecords, hierarchicalLimits)
        }

        logger.debug("Reconstructed ${logs.size} logs")
        return logs
    }

    /**
     * Check if results contain projected columns (SELECT specific fields, not SELECT *)
     * Projected columns have scope prefixes like: l_, t_, e_
     */
    private fun hasProjectedColumns(record: Map<String, Any?>): Boolean {
        return record.keys.any { key ->
            key.startsWith("l_") || key.startsWith("t_") || key.startsWith("e_") ||
            key.startsWith("log_") || key.startsWith("trace_") || key.startsWith("event_")
        }
    }

    /**
     * Split projected columns by scope (log, trace, event)
     * Example: t_caseId → {scope: "trace", attrName: "caseId", value: "00000060"}
     */
    private fun splitProjectedColumns(record: Map<String, Any?>): Map<String, MutableMap<String, Any?>> {
        val result = mutableMapOf(
            "log" to mutableMapOf<String, Any?>(),
            "trace" to mutableMapOf<String, Any?>(),
            "event" to mutableMapOf<String, Any?>()
        )

        record.forEach { (key, value) ->
            when {
                key.startsWith("l_") -> {
                    val attrName = key.substring(2)
                    result["log"]!![attrName] = value
                }
                key.startsWith("log_") -> {
                    val attrName = key.substring(4)
                    result["log"]!![attrName] = value
                }
                key.startsWith("t_") -> {
                    val attrName = key.substring(2)
                    result["trace"]!![attrName] = value
                }
                key.startsWith("trace_") -> {
                    val attrName = key.substring(6)
                    result["trace"]!![attrName] = value
                }
                key.startsWith("e_") -> {
                    val attrName = key.substring(2)
                    result["event"]!![attrName] = value
                }
                key.startsWith("event_") -> {
                    val attrName = key.substring(6)
                    result["event"]!![attrName] = value
                }
                // Check if this is a function-result alias (e.g., "year_event_time_timestamp_")
                key in currentColumnAliases -> {
                    val alias = currentColumnAliases[key]!!
                    val scopeKey = when (alias.scope) {
                        "EVENT" -> "event"
                        "TRACE" -> "trace"
                        "LOG" -> "log"
                        else -> "log"
                    }
                    // ProcessM returns datetime extraction functions (year, month, day, etc.) as float,
                    // but aggregation functions (count, sum) keep their original type (int).
                    val isDatetimeExtraction = alias.pqlExpression.startsWith("year(") ||
                            alias.pqlExpression.startsWith("month(") ||
                            alias.pqlExpression.startsWith("day(") ||
                            alias.pqlExpression.startsWith("hour(") ||
                            alias.pqlExpression.startsWith("minute(") ||
                            alias.pqlExpression.startsWith("second(") ||
                            alias.pqlExpression.startsWith("dayofweek(") ||
                            alias.pqlExpression.startsWith("quarter(")
                    val adjustedValue = if (isDatetimeExtraction) {
                        when (value) {
                            is Int -> value.toDouble()
                            is Long -> value.toDouble()
                            else -> value
                        }
                    } else {
                        value
                    }
                    // Use the PQL expression as the attribute name (e.g., "year(event:time:timestamp)")
                    result[scopeKey]!![alias.pqlExpression] = adjustedValue
                }
                // If no scope prefix, treat as log-level attribute
                // Skip keys that are full entity names (properties() results, not projected columns)
                else -> {
                    if (key !in setOf("event", "trace", "log", "e", "t", "l")) {
                        result["log"]!![key] = value
                    }
                }
            }
        }

        return result
    }

    /**
     * Group flat results by log ID
     */
    private fun groupByLog(results: List<Map<String, Any?>>): Map<String, List<Map<String, Any?>>> {
        return results.groupBy { record ->
            extractLogId(record) ?: "unknown"
        }
    }

    /**
     * Extract log ID from a record
     * Tries multiple common field names
     */
    private fun extractLogId(record: Map<String, Any?>): String? {
        return when {
            record.containsKey("logId") -> record["logId"]?.toString()
            record.containsKey("l_logId") -> record["l_logId"]?.toString()
            record.containsKey("log_logId") -> record["log_logId"]?.toString()
            record.containsKey("l") -> {
                val logNode = record["l"]
                if (logNode is Map<*, *>) {
                    logNode["logId"]?.toString()
                } else null
            }
            record.containsKey("log") -> {
                val logNode = record["log"]
                if (logNode is Map<*, *>) {
                    logNode["logId"]?.toString()
                } else null
            }
            else -> null
        }
    }

    /**
     * Build a Log object from grouped records
     */
    private fun buildLog(
        logId: String,
        logRecords: List<Map<String, Any?>>,
        hierarchicalLimits: Map<String, Int?> = emptyMap()
    ): Log {
        val log = Log()

        // Set log attributes from first record
        val firstRecord = logRecords.firstOrNull()
        if (firstRecord != null) {
            populateLogAttributes(log, firstRecord, logId)
        }

        // Group by trace and build traces
        val traceGroups = groupByTrace(logRecords)

        // Apply trace limit
        val traceLimit = hierarchicalLimits["trace"]
        val limitedTraceGroups = if (traceLimit != null && traceLimit > 0) {
            traceGroups.entries.take(traceLimit).associate { it.key to it.value }
        } else {
            traceGroups
        }

        log.traces = limitedTraceGroups.map { (traceId, traceRecords) ->
            buildTrace(traceId, traceRecords, hierarchicalLimits)
        }.asSequence()

        return log
    }

    /**
     * Populate log-level attributes
     */
    private fun populateLogAttributes(log: Log, record: Map<String, Any?>, logId: String) {
        // Check if we have projected columns (SELECT specific fields)
        val hasProjected = hasProjectedColumns(record)
        logger.debug("populateLogAttributes - hasProjected: {}, record keys: {}", hasProjected, record.keys)

        val logData = if (hasProjected) {
            // Extract only log-scoped attributes from projected columns
            val split = splitProjectedColumns(record)
            split["log"]!!
        } else {
            // Try to extract from nested log object (SELECT *)
            when {
                record.containsKey("l") && record["l"] is Map<*, *> -> record["l"] as Map<*, *>
                record.containsKey("log") && record["log"] is Map<*, *> -> record["log"] as Map<*, *>
                else -> record
            }
        }

        log.conceptName = extractString(logData, "name", "concept:name") ?: "unknown"

        // Extract lifecycle:model to both property and attributes (for XES output)
        val lifecycleModel = logData["lifecycle:model"]?.toString()
        log.lifecycleModel = lifecycleModel ?: "standard"
        logger.debug("lifecycleModel from logData: {}, hasProjected: {}", lifecycleModel, hasProjected)
        if (lifecycleModel != null && !hasProjected) {
            log.attributes["lifecycle:model"] = lifecycleModel
            logger.debug("Added lifecycle:model to log.attributes: {}", lifecycleModel)
        }

        // Extract identity:id if present (only for SELECT *)
        if (!hasProjected) {
            logData["identity:id"]?.toString()?.let { id ->
                log.attributes["identity:id"] = id
                try {
                    log.identityId = UUID.fromString(id)
                } catch (e: Exception) {
                    // Not a valid UUID, but still keep in attributes
                }
            }
        }

        // Copy additional attributes from XES (skip only internal Neo4j fields)
        // excludeKeys now includes lifecycle:model and identity:id to avoid duplication
        copyAttributes(logData, log.attributes, excludeKeys = setOf("name", "logId", "lifecycle:model", "identity:id", "concept:name"))

        // Add standard extensions (order matches ProcessM output)
        log.extensions["Lifecycle"] = Extension("Lifecycle", "lifecycle", "http://www.xes-standard.org/lifecycle.xesext")
        log.extensions["Semantic"] = Extension("Semantic", "semantic", "http://www.xes-standard.org/semantic.xesext")
        log.extensions["Org"] = Extension("Organizational", "org", "http://www.xes-standard.org/org.xesext")
        log.extensions["Concept"] = Extension("Concept", "concept", "http://www.xes-standard.org/concept.xesext")
        log.extensions["Time"] = Extension("Time", "time", "http://www.xes-standard.org/time.xesext")

        // Add default classifiers (matching XES standard format)
        log.eventClassifiers.add(EventClassifier("Event Name", listOf("concept:name")))
        log.eventClassifiers.add(EventClassifier("Resource", listOf("org:resource")))
        log.eventClassifiers.add(EventClassifier("concept:name+lifecycle:transition", listOf("concept:name", "lifecycle:transition")))

        // Add default globals (match ProcessM format)
        log.traceGlobals.add(GlobalAttribute("trace", mapOf("concept:name" to "__INVALID__")))
        log.eventGlobals.add(GlobalAttribute("event", mapOf(
            "concept:name" to "__INVALID__",
            "lifecycle:transition" to "complete"
        )))
    }

    /**
     * Group records by trace ID
     */
    private fun groupByTrace(records: List<Map<String, Any?>>): Map<String, List<Map<String, Any?>>> {
        return records.groupBy { record ->
            extractTraceId(record) ?: "unknown"
        }
    }

    /**
     * Extract trace ID from a record
     * For projected columns, use t_concept_name as identifier
     */
    private fun extractTraceId(record: Map<String, Any?>): String? {
        return when {
            // Projected columns - use concept:name as trace identifier
            record.containsKey("t_concept_name") -> record["t_concept_name"]?.toString()
            record.containsKey("t_caseId") -> record["t_caseId"]?.toString()
            record.containsKey("t_traceId") -> record["t_traceId"]?.toString()
            record.containsKey("trace_traceId") -> record["trace_traceId"]?.toString()
            // Regular fields (SELECT *)
            record.containsKey("traceId") -> record["traceId"]?.toString()
            record.containsKey("caseId") -> record["caseId"]?.toString()
            // Nested objects
            record.containsKey("t") -> {
                val traceNode = record["t"]
                if (traceNode is Map<*, *>) {
                    traceNode["traceId"]?.toString() ?: traceNode["caseId"]?.toString()
                } else null
            }
            record.containsKey("trace") -> {
                val traceNode = record["trace"]
                if (traceNode is Map<*, *>) {
                    traceNode["traceId"]?.toString() ?: traceNode["caseId"]?.toString()
                } else null
            }
            else -> null
        }
    }

    /**
     * Build a Trace object from grouped records
     */
    private fun buildTrace(
        traceId: String,
        traceRecords: List<Map<String, Any?>>,
        hierarchicalLimits: Map<String, Int?> = emptyMap()
    ): Trace {
        val trace = Trace()

        // Set trace attributes from first record
        val firstRecord = traceRecords.firstOrNull()
        if (firstRecord != null) {
            populateTraceAttributes(trace, firstRecord, traceId)
        }

        // Build events
        val allEvents = traceRecords.mapNotNull { record ->
            buildEvent(record)
        }

        // Apply event limit
        val eventLimit = hierarchicalLimits["event"]
        val limitedEvents = if (eventLimit != null && eventLimit > 0) {
            allEvents.take(eventLimit)
        } else {
            allEvents
        }

        trace.events = limitedEvents.asSequence()

        return trace
    }

    /**
     * Populate trace-level attributes
     */
    private fun populateTraceAttributes(trace: Trace, record: Map<String, Any?>, traceId: String) {
        // Check if we have projected columns
        val hasProjected = hasProjectedColumns(record)

        val traceData = if (hasProjected) {
            // Extract only trace-scoped attributes from projected columns
            val split = splitProjectedColumns(record)
            split["trace"]!!
        } else {
            // Try to extract from nested trace object (SELECT *)
            when {
                record.containsKey("t") && record["t"] is Map<*, *> -> record["t"] as Map<*, *>
                record.containsKey("trace") && record["trace"] is Map<*, *> -> record["trace"] as Map<*, *>
                else -> record
            }
        }

        // Extract trace concept:name
        // For projected columns: ONLY extract if trace attributes were selected (traceData not empty)
        // For SELECT *: ALWAYS extract concept:name
        if (hasProjected) {
            // Projected columns - only set conceptName if trace attributes were actually selected
            if (traceData.isNotEmpty()) {
                trace.conceptName = if (traceData.containsKey("traceId")) {
                    // Extract full trace name from traceId
                    extractTraceNameFromTraceId(traceData)
                        ?: extractString(traceData, "concept:name", "concept_name")
                        ?: extractString(traceData, "caseId")
                        ?: "unknown"
                } else {
                    extractString(traceData, "concept:name", "concept_name")
                        ?: extractString(traceData, "caseId")
                        ?: "unknown"
                }
            }
            // If traceData is empty, don't set conceptName at all
        } else {
            // SELECT * - always set concept:name
            trace.conceptName = extractString(traceData, "concept:name", "concept_name")
                ?: extractTraceNameFromTraceId(traceData)
                ?: extractString(traceData, "caseId")
                ?: "unknown"
        }

        // Extract cost attributes (only if present in data)
        // Check both XES names (cost:currency, cost:total) and projected column names (currency, total)
        trace.costCurrency = extractString(traceData, "cost:currency", "currency")
        trace.costTotal = extractDouble(traceData, "cost:total", "total")

        // Copy additional attributes
        copyAttributes(traceData, trace.attributes, excludeKeys = setOf("traceId", "caseId", "concept:name", "concept_name", "name", "cost:currency", "cost:total", "currency", "total"))
    }

    /**
     * Build an Event object from a record
     */
    private fun buildEvent(record: Map<String, Any?>): Event? {
        // Check if we have projected columns
        val hasProjected = hasProjectedColumns(record)

        val eventData = if (hasProjected) {
            // Extract only event-scoped attributes from projected columns
            val split = splitProjectedColumns(record)
            val eventAttrs = split["event"]!!
            if (eventAttrs.isEmpty()) {
                // Fall back to full event properties Map (mixed mode: e.g. SELECT t:name, e:*, t:total)
                // The RETURN clause may have "properties(event) as event" alongside projected t_* columns
                val fullEventProps = record["event"] ?: record["e"]
                if (fullEventProps is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    fullEventProps as Map<String, Any?>
                } else {
                    return null
                }
            } else {
                eventAttrs
            }
        } else {
            // Try to extract from nested event object (SELECT *)
            when {
                record.containsKey("e") && record["e"] is Map<*, *> -> record["e"] as Map<*, *>
                record.containsKey("event") && record["event"] is Map<*, *> -> record["event"] as Map<*, *>
                else -> record
            }
        }

        // If no event data, skip
        if (!hasProjected && !hasEventData(eventData)) {
            return null
        }

        val event = Event()

        // Standard attributes (also check concept_name and "name" for projected columns)
        // When SELECT e:name, the alias is e_name which splits to "name"
        event.conceptName = extractString(eventData, "activity", "concept:name", "concept_name", "name")
        event.conceptInstance = extractString(eventData, "concept:instance")
        event.orgResource = extractString(eventData, "resource", "org:resource")
        event.orgRole = extractString(eventData, "org:role")
        event.orgGroup = extractString(eventData, "org:group")
        event.lifecycleTransition = extractString(eventData, "lifecycle", "lifecycle:transition")
        event.lifecycleState = extractString(eventData, "lifecycle:state")
        event.costCurrency = extractString(eventData, "cost:currency", "currency", "cost_currency")
        event.costTotal = extractDouble(eventData, "cost", "cost:total", "total", "cost_total")

        // Time:timestamp
        event.timeTimestamp = extractTimestamp(eventData, "timestamp", "time:timestamp")

        // Copy additional attributes
        copyAttributes(eventData, event.attributes, excludeKeys = setOf(
            "activity", "resource", "lifecycle", "timestamp", "cost", "name",
            "concept:name", "concept_name", "org:resource", "lifecycle:transition", "time:timestamp",
            "cost:total", "cost:currency", "total", "currency", "cost_total", "cost_currency"
        ))

        return event
    }

    /**
     * Check if record contains event data
     */
    private fun hasEventData(data: Map<*, *>): Boolean {
        return data.containsKey("activity") ||
                data.containsKey("concept:name") ||
                data.containsKey("concept_name") ||
                data.containsKey("timestamp") ||
                data.containsKey("time:timestamp")
    }

    /**
     * Extract trace name from traceId field
     * traceId format: "log-{logId}-trace-{traceName}"
     * Returns the traceName part
     */
    private fun extractTraceNameFromTraceId(data: Map<*, *>): String? {
        val traceId = extractString(data, "traceId") ?: return null

        // Extract everything after "trace-"
        val tracePrefix = "-trace-"
        val index = traceId.indexOf(tracePrefix)

        return if (index != -1) {
            traceId.substring(index + tracePrefix.length)
        } else {
            null
        }
    }

    /**
     * Extract string value from multiple possible keys
     */
    private fun extractString(data: Map<*, *>, vararg keys: String): String? {
        for (key in keys) {
            val value = data[key]
            if (value != null) {
                return value.toString()
            }
        }
        return null
    }

    /**
     * Extract double value
     */
    private fun extractDouble(data: Map<*, *>, vararg keys: String): Double? {
        for (key in keys) {
            val value = data[key]
            if (value != null) {
                return when (value) {
                    is Number -> value.toDouble()
                    is String -> value.toDoubleOrNull()
                    else -> null
                }
            }
        }
        return null
    }

    /**
     * Extract timestamp from various formats
     */
    private fun extractTimestamp(data: Map<*, *>, vararg keys: String): Instant? {
        for (key in keys) {
            val value = data[key]
            if (value != null) {
                return when (value) {
                    is Instant -> value
                    is ZonedDateTime -> value.toInstant()
                    is LocalDateTime -> value.atZone(ZoneOffset.UTC).toInstant()
                    is String -> parseTimestampString(value)
                    else -> null
                }
            }
        }
        return null
    }

    /**
     * Parse timestamp from string
     */
    private fun parseTimestampString(value: String): Instant? {
        return try {
            // Try ISO 8601 format
            Instant.parse(value)
        } catch (e: Exception) {
            try {
                // Try with ZonedDateTime
                ZonedDateTime.parse(value).toInstant()
            } catch (e2: Exception) {
                try {
                    // Try LocalDateTime
                    LocalDateTime.parse(value).atZone(ZoneOffset.UTC).toInstant()
                } catch (e3: Exception) {
                    logger.warn("Could not parse timestamp: $value")
                    null
                }
            }
        }
    }

    /**
     * Copy attributes to target map, excluding specified keys
     * Also filters nested attributes and internal Neo4j fields
     */
    private fun copyAttributes(source: Map<*, *>, target: MutableMap<String, Any?>, excludeKeys: Set<String> = emptySet()) {
        // Internal Neo4j fields that should never appear in XES output
        val internalFields = setOf("eventId", "createdAt", "traceId", "logId", "updatedAt")

        source.forEach { (key, value) ->
            val keyStr = key.toString()

            // Skip nested attributes (keys starting with SEPARATOR '\u001f')
            // This matches ProcessM's behavior when readNestedAttributes=false
            if (keyStr.startsWith('\u001f')) {
                return@forEach
            }

            // Skip internal Neo4j fields
            if (keyStr in internalFields) {
                return@forEach
            }

            if (keyStr !in excludeKeys && value != null) {
                // Skip nested objects (already extracted)
                if (value !is Map<*, *>) {
                    target[keyStr] = value
                }
            }
        }
    }
}
