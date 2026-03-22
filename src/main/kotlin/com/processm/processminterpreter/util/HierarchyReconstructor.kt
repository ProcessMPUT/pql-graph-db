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

    // Internal Neo4j properties that should not appear in XES output
    private val INTERNAL_PROPERTIES = setOf(
        "importOrder", "traceId", "eventId", "logId", "createdAt", "updatedAt"
    )

    // Internal identity columns injected by QLToCypherVisitor — not real projections
    private val INTERNAL_ALIAS_KEYS = setOf("t_traceId", "l_logId", "e_eventId")

    private val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()

    /**
     * Reconstruct hierarchical structure from flat query results
     *
     * @param flatResults Flat results from Neo4j Cypher query
     * @param hierarchicalLimits Optional hierarchical limits to apply (log, trace, event)
     * @return List of Log objects with hierarchical structure
     */
    // Column alias metadata from QLToCypherVisitor — maps alias → PQL expression + scope
    private var currentColumnAliases: Map<String, ColumnAlias> = emptyMap()
    // Whether the query has explicit ORDER BY on trace scope (skip importOrder sorting if true)
    private var currentHasTraceOrderBy: Boolean = false

    fun reconstruct(
        flatResults: List<Map<String, Any?>>,
        hierarchicalLimits: Map<String, Int?> = emptyMap(),
        columnAliases: Map<String, ColumnAlias> = emptyMap(),
        isAggregationResult: Boolean = false,
        hasTraceOrderBy: Boolean = false
    ): List<Log> {
        this.currentHasTraceOrderBy = hasTraceOrderBy
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
            buildLog(logId, logRecords, hierarchicalLimits, isAggregationResult)
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
            key !in INTERNAL_ALIAS_KEYS && (
                key.startsWith("l_") || key.startsWith("t_") || key.startsWith("e_") ||
                key.startsWith("log_") || key.startsWith("trace_") || key.startsWith("event_") ||
                key in currentColumnAliases
            )
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
                // Check columnAliases FIRST — expression aliases like "log_1_0" would otherwise
                // be incorrectly intercepted by the startsWith("log_") check below.
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
                key.startsWith("l_") -> result["log"]!![key.substring(2)] = value
                key.startsWith("log_") -> result["log"]!![key.substring(4)] = value
                key.startsWith("t_") -> result["trace"]!![key.substring(2)] = value
                key.startsWith("trace_") -> result["trace"]!![key.substring(6)] = value
                key.startsWith("e_") -> result["event"]!![key.substring(2)] = value
                key.startsWith("event_") -> result["event"]!![key.substring(6)] = value
                // If no scope prefix, handle full entity maps returned by properties() AS log/trace/event,
                // or fall through to log-level for any other unprefixed keys.
                else -> {
                    when {
                        (key == "log" || key == "l") && value is Map<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            (value as Map<String, Any?>).forEach { (k, v) ->
                                if (k !in INTERNAL_PROPERTIES) result["log"]!![k] = v
                            }
                        }
                        (key == "trace" || key == "t") && value is Map<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            (value as Map<String, Any?>).forEach { (k, v) ->
                                if (k !in INTERNAL_PROPERTIES) result["trace"]!![k] = v
                            }
                        }
                        (key == "event" || key == "e") && value is Map<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            (value as Map<String, Any?>).forEach { (k, v) ->
                                if (k !in INTERNAL_PROPERTIES) result["event"]!![k] = v
                            }
                        }
                        key !in setOf("event", "trace", "log", "e", "t", "l", "_null_event_count_") -> {
                            result["log"]!![key] = value
                        }
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
        hierarchicalLimits: Map<String, Int?> = emptyMap(),
        isAggregationResult: Boolean = false
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
            buildTrace(traceId, traceRecords, hierarchicalLimits, isAggregationResult)
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
            // Extract log-scoped attributes from projected columns
            val split = splitProjectedColumns(record)
            val projected = split["log"]!!
            // If projected log data only has identity columns (logId),
            // but full properties(log) is also in the record, merge them
            val hasRealProjected = projected.keys.any { it !in setOf("logId") }
            if (!hasRealProjected && record.containsKey("log") && record["log"] is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val fullLog = record["log"] as Map<String, Any?>
                val merged = mutableMapOf<String, Any?>()
                merged.putAll(fullLog)
                merged.putAll(projected)
                merged
            } else {
                projected
            }
        } else {
            // Try to extract from nested log object (SELECT *)
            when {
                record.containsKey("l") && record["l"] is Map<*, *> -> record["l"] as Map<*, *>
                record.containsKey("log") && record["log"] is Map<*, *> -> record["log"] as Map<*, *>
                else -> record
            }
        }

        log.conceptName = extractString(logData, "name", "concept:name") ?: if (hasProjected) null else "unknown"

        // Extract lifecycle:model to both property and attributes (for XES output)
        val lifecycleModel = logData["lifecycle:model"]?.toString()
        log.lifecycleModel = if (hasProjected) lifecycleModel else (lifecycleModel ?: "standard")
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
        copyAttributes(logData, log.attributes, excludeKeys = setOf("name", "logId", "lifecycle:model", "identity:id", "concept:name", "classifiers", "createdAt", "updatedAt"))

        // Add standard extensions (order matches ProcessM output)
        log.extensions["Lifecycle"] = Extension("Lifecycle", "lifecycle", "http://www.xes-standard.org/lifecycle.xesext")
        log.extensions["Semantic"] = Extension("Semantic", "semantic", "http://www.xes-standard.org/semantic.xesext")
        log.extensions["Org"] = Extension("Organizational", "org", "http://www.xes-standard.org/org.xesext")
        log.extensions["Concept"] = Extension("Concept", "concept", "http://www.xes-standard.org/concept.xesext")
        log.extensions["Time"] = Extension("Time", "time", "http://www.xes-standard.org/time.xesext")

        // Read classifiers from log data (stored as JSON by XESLoader), fallback to defaults
        val classifiersJson = logData["classifiers"] as? String
        if (classifiersJson != null) {
            try {
                val classifiersMap = objectMapper
                    .readValue(classifiersJson, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, List<String>>>() {})
                classifiersMap.forEach { (name, keys) ->
                    log.eventClassifiers.add(EventClassifier(name, keys))
                }
                // ProcessM orders single-key classifiers before multi-key ones
                log.eventClassifiers.sortBy { it.keys.size }
            } catch (e: Exception) {
                logger.warn("Failed to parse classifiers JSON, using defaults: ${e.message}")
                addDefaultClassifiers(log)
            }
        } else {
            addDefaultClassifiers(log)
        }

        // Add default globals (match ProcessM format — each XES attribute in <global> block is a separate entry)
        log.traceGlobals.add(GlobalAttribute("trace", mapOf("concept:name" to "__INVALID__")))
        log.eventGlobals.add(GlobalAttribute("event", mapOf("concept:name" to "__INVALID__")))
        log.eventGlobals.add(GlobalAttribute("event", mapOf("lifecycle:transition" to "complete")))
    }

    /**
     * Add default classifiers when none are stored on the log node.
     */
    private fun addDefaultClassifiers(log: Log) {
        log.eventClassifiers.add(EventClassifier("Event Name", listOf("concept:name")))
        log.eventClassifiers.add(EventClassifier("Resource", listOf("org:resource")))
        log.eventClassifiers.add(EventClassifier("concept:name+lifecycle:transition", listOf("concept:name", "lifecycle:transition")))
    }

    /**
     * Group records by trace ID
     */
    private fun groupByTrace(records: List<Map<String, Any?>>): Map<String, List<Map<String, Any?>>> {
        val grouped = records.groupBy { record ->
            extractTraceId(record) ?: "unknown"
        }
        // Sort traces by importOrder to preserve XES file order (matches ProcessM).
        // Only sort when importOrder is available (SELECT * with properties(trace))
        // AND no explicit ORDER BY on trace scope (which should override import order).
        val hasImportOrder = !currentHasTraceOrderBy &&
            (grouped.values.firstOrNull()?.firstOrNull()?.let { first ->
                val traceData = first["trace"]
                traceData is Map<*, *> && traceData.containsKey("importOrder")
            } ?: false)

        return if (hasImportOrder) {
            grouped.entries.sortedBy { (_, traceRecords) ->
                val traceData = traceRecords.first()["trace"] as? Map<*, *>
                (traceData?.get("importOrder") as? Number)?.toInt() ?: Int.MAX_VALUE
            }.associate { it.key to it.value }
        } else {
            grouped
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
        hierarchicalLimits: Map<String, Int?> = emptyMap(),
        isAggregationResult: Boolean = false
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

        val events = allEvents

        // Apply event limit
        val eventLimit = hierarchicalLimits["event"]
        val limitedEvents = if (eventLimit != null && eventLimit > 0) {
            events.take(eventLimit)
        } else {
            events
        }

        trace.events = limitedEvents.asSequence()

        // Read null event count from Cypher results (ProcessM null event placeholders)
        val nullEventCount = firstRecord?.get("_null_event_count_")
        if (nullEventCount != null && limitedEvents.isEmpty()) {
            trace.nullEventCount = when (nullEventCount) {
                is Number -> nullEventCount.toInt()
                else -> 0
            }
        }

        return trace
    }

    /**
     * Populate trace-level attributes
     */
    private fun populateTraceAttributes(trace: Trace, record: Map<String, Any?>, traceId: String) {
        // Check if we have projected columns
        val hasProjected = hasProjectedColumns(record)

        val traceData = if (hasProjected) {
            // Extract trace-scoped attributes from projected columns
            val split = splitProjectedColumns(record)
            val projected = split["trace"]!!
            // If projected trace data only has identity columns (traceId, logId),
            // but full properties(trace) is also in the record, merge them
            val hasRealProjected = projected.keys.any { it !in setOf("traceId", "logId") }
            logger.debug("populateTraceAttributes - projected keys: {}, hasRealProjected: {}, record has 'trace': {}, trace type: {}",
                projected.keys, hasRealProjected, record.containsKey("trace"), record["trace"]?.javaClass?.name)
            if (!hasRealProjected && record.containsKey("trace") && record["trace"] is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val fullTrace = record["trace"] as Map<String, Any?>
                logger.debug("Merging projected with full trace properties. Full trace keys: {}", fullTrace.keys)
                val merged = mutableMapOf<String, Any?>()
                merged.putAll(fullTrace)
                merged.putAll(projected)
                merged
            } else {
                projected
            }
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
            // Projected columns - only set conceptName if REAL trace attributes were selected
            // (not just internal identity columns like traceId/logId)
            val hasRealTraceData = traceData.keys.any { it !in setOf("traceId", "logId") }
            if (traceData.isNotEmpty() && hasRealTraceData) {
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
            // If traceData is empty or only has identity columns, don't set conceptName
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
        copyAttributes(traceData, trace.attributes, excludeKeys = setOf("traceId", "caseId", "concept:name", "concept_name", "name", "cost:currency", "cost:total", "currency", "total", "importOrder", "createdAt", "updatedAt", "logId", "eventId"))
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
            "cost:total", "cost:currency", "total", "currency", "cost_total", "cost_currency",
            "importOrder", "eventId", "createdAt"
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

            if (keyStr !in excludeKeys) {
                // Skip nested objects (already extracted); allow null expression results
                if (value !is Map<*, *>) {
                    val storedValue = when (value) {
                        is LocalDateTime -> value.atZone(ZoneOffset.UTC).toInstant()
                        is ZonedDateTime -> value.toInstant()
                        is OffsetDateTime -> value.toInstant()
                        else -> value
                    }
                    target[keyStr] = storedValue
                }
            }
        }
    }
}
