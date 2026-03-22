package com.processm.processminterpreter.util

import com.processm.processminterpreter.model.hierarchical.Event
import com.processm.processminterpreter.model.hierarchical.Log
import com.processm.processminterpreter.model.hierarchical.Trace
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Converts hierarchical Log objects to XES JSON format
 * matching ProcessM's XML→JSON conversion format
 *
 * ProcessM uses XML attributes converted to JSON with @key/@value notation
 */
object XESJsonConverter {
    private val logger = LoggerFactory.getLogger(XESJsonConverter::class.java)

    // XES date format in UTC (matching ProcessM output)
    // Uses ISO_INSTANT-like format but with conditional sub-second precision:
    // - No fractional seconds when they're zero: 2020-03-13T16:45:50Z
    // - Milliseconds when present: 2020-03-13T16:45:50.123Z
    // - Microseconds when present: 2020-03-13T16:45:50.123456Z
    private val xesDateFormatterNoMillis = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)

    /**
     * Convert a list of Log objects to XES JSON format
     *
     * @param logs List of Log objects
     * @param isProjectedQuery Whether this is a projected query (SELECT specific fields vs SELECT *)
     * @return Map representing XES JSON structure
     */
    fun convertToXESJson(logs: List<Log>, isProjectedQuery: Boolean = false, excludeEventAttrs: List<String> = emptyList(), projectedTraceAttrs: Set<String> = emptySet()): Map<String, Any> {
        logger.debug("Converting ${logs.size} logs to XES JSON format (projected: $isProjectedQuery)")

        if (logs.isEmpty()) {
            return mapOf("log" to emptyMap<String, Any>())
        }

        // For now, convert first log (ProcessM typically works with single log)
        val log = logs.first()
        return mapOf("log" to convertLog(log, isProjectedQuery, excludeEventAttrs, projectedTraceAttrs))
    }

    /**
     * Convert a single Log to JSON structure
     */
    private fun convertLog(log: Log, isProjectedQuery: Boolean = false, excludeEventAttrs: List<String> = emptyList(), projectedTraceAttrs: Set<String> = emptySet()): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        // XES metadata attributes (must be first)
        result["@xes.version"] = "1.0"
        result["@xmlns"] = "http://www.xes-standard.org/"

        // Extensions
        if (log.extensions.isNotEmpty()) {
            result["extension"] = log.extensions.values.map { ext ->
                mapOf(
                    "@name" to ext.name,
                    "@prefix" to ext.prefix,
                    "@uri" to ext.uri
                )
            }
        }

        // Global attributes (after extensions, before classifiers)
        // ONLY add global array for SELECT *, NOT for projected queries
        if (!isProjectedQuery) {
            // Group globals by scope — ProcessM merges globals with the same scope
            // into a single object with string/date/etc arrays
            val globalsByScope = linkedMapOf<String, MutableMap<String, MutableList<Map<String, String>>>>()

            fun addGlobal(global: com.processm.processminterpreter.model.hierarchical.GlobalAttribute) {
                val scopeAttrs = globalsByScope.getOrPut(global.scope) { mutableMapOf() }
                global.attributes.forEach { (key, value) ->
                    if (value != null) {
                        addAttributeByType(scopeAttrs, key, value)
                    }
                }
            }

            log.traceGlobals.forEach { addGlobal(it) }
            log.eventGlobals.forEach { addGlobal(it) }

            val globalArray = globalsByScope.map { (scope, attrsByType) ->
                val obj = mutableMapOf<String, Any>("@scope" to scope)
                attrsByType.forEach { (type, attrs) ->
                    obj[type] = toSingleOrArray(attrs)
                }
                obj
            }

            if (globalArray.isNotEmpty()) {
                result["global"] = globalArray
            }
        }

        // Classifiers
        if (log.eventClassifiers.isNotEmpty()) {
            result["classifier"] = log.eventClassifiers.map { classifier ->
                mapOf(
                    "@name" to classifier.name,
                    "@scope" to "event",  // ProcessM adds @scope for event classifiers
                    "@keys" to classifier.keys.joinToString(" ")
                )
            }
        }

        // Log-level attributes (after classifiers, before trace)
        // Add log.attributes and log.identityId
        val logAttributes = mutableMapOf<String, MutableList<Map<String, String>>>()

        // For projected queries, output log concept:name if it was explicitly selected
        // (ProcessM only includes log concept:name when l:name is in SELECT)
        if (isProjectedQuery) {
            log.conceptName?.let { name ->
                if (name != "unknown") {
                    addAttribute(logAttributes, "string", "concept:name", name)
                }
            }
        }

        // Add log attributes from log.attributes
        // For SELECT *: all XES attributes (source, lifecycle:model, identity:id, etc.)
        // For projected queries: only projected expression results (e.g., "log:1.0")
        // In both cases, log.attributes contains the right set (populated by HierarchyReconstructor)
        logger.debug("convertLog - log.attributes keys: {}", log.attributes.keys)

        // ProcessM does not export 'description' attribute in JSON API
        val excludeLogAttrs = setOf("description")

        log.attributes.forEach { (key, value) ->
            if (key in excludeLogAttrs) return@forEach
            if (value != null) {
                // Special handling for identity:id - use "id" type instead of "string"
                if (key == "identity:id") {
                    addAttribute(logAttributes, "id", key, value.toString())
                } else {
                    addAttributeByType(logAttributes, key, value)
                }
            } else {
                // Null values: output as string "null" (matches ProcessM behavior)
                addAttribute(logAttributes, "string", key, "null")
            }
        }

        // Also add identity:id from log.identityId if not already in attributes (SELECT * only)
        if (!isProjectedQuery && !log.attributes.containsKey("identity:id")) {
            log.identityId?.let { id ->
                addAttribute(logAttributes, "id", "identity:id", id.toString())
            }
        }

        // Add log attribute groups to result (single object if 1 item, array if >1)
        logAttributes.forEach { (type, attrs) ->
            result[type] = toSingleOrArray(attrs)
        }

        // Traces (single object if 1 trace, array if >1)
        val traces = log.traces.toList()
        if (traces.isNotEmpty()) {
            val traceMaps = traces.map { trace -> convertTrace(trace, excludeEventAttrs, isProjectedQuery, projectedTraceAttrs) }
            result["trace"] = toSingleOrArray(traceMaps)
        }

        return result
    }

    /**
     * Convert a single Trace to JSON structure
     */
    private fun convertTrace(trace: Trace, excludeEventAttrs: List<String> = emptyList(), isProjectedQuery: Boolean = false, projectedTraceAttrs: Set<String> = emptySet()): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        val traceAttributes = mutableMapOf<String, MutableList<Map<String, String>>>()

        // For projected queries, only include trace attributes that were explicitly selected
        // ProcessM omits trace concept:name when t:name is not in SELECT
        val includeTraceName = !isProjectedQuery || "t_name" in projectedTraceAttrs || "concept:name" in projectedTraceAttrs
        val includeTraceId = !isProjectedQuery || "t_id" in projectedTraceAttrs || "identity:id" in projectedTraceAttrs
        val includeTraceCurrency = !isProjectedQuery || "t_currency" in projectedTraceAttrs || "cost:currency" in projectedTraceAttrs
        val includeTraceTotal = !isProjectedQuery || "t_total" in projectedTraceAttrs || "cost:total" in projectedTraceAttrs

        // Standard attributes (conditionally for projected queries)
        if (includeTraceName) {
            trace.conceptName?.let { name ->
                addAttribute(traceAttributes, "string", "concept:name", name)
            }
        }

        if (includeTraceId) {
            trace.identityId?.let { id ->
                addAttribute(traceAttributes, "string", "identity:id", id.toString())
            }
        }

        if (includeTraceCurrency) {
            trace.costCurrency?.let { currency ->
                addAttribute(traceAttributes, "string", "cost:currency", currency)
            }
        }

        if (includeTraceTotal) {
            trace.costTotal?.let { cost ->
                addAttribute(traceAttributes, "float", "cost:total", cost.toString())
            }
        }

        // Custom attributes
        trace.attributes.forEach { (key, value) ->
            if (value != null) {
                addAttributeByType(traceAttributes, key, value)
            } else {
                addAttribute(traceAttributes, "string", key, "null")
            }
        }

        // IMPORTANT: ProcessM returns attributes BEFORE events in trace
        // We must match this order for JSON comparison to work

        // Add attribute lists to result (single object if 1 item, array if >1)
        // ADD ATTRIBUTES FIRST to match ProcessM order
        traceAttributes.forEach { (type, attrs) ->
            result[type] = toSingleOrArray(attrs)
        }

        // Events (single object if 1 event, array if >1)
        // ADD EVENTS AFTER attributes to match ProcessM order
        val events = trace.events.toList()
        if (events.isNotEmpty()) {
            val eventMaps = events.map { event -> convertEvent(event, excludeEventAttrs) }
            result["event"] = toSingleOrArray(eventMaps)
        } else if (trace.nullEventCount > 1) {
            // ProcessM outputs null event placeholders for events that exist
            // in the hierarchy but weren't projected by the query.
            // Output as a list of nulls: [null, null, ...] matching ProcessM format.
            // Skip count=1: ProcessM's toSingleOrArray unwraps [null] to null,
            // which JSON serializes as absent/null (effectively 0 events).
            val nullList: List<Any?> = (1..trace.nullEventCount).map { null }
            result["event"] = nullList
        } else if (trace.nullEventCount == 0 && !isProjectedQuery) {
            // Aggregation queries (implicit GROUP BY) collapse events — no event data in output.
            // ProcessM outputs "event": null for these traces (matches toSingleOrArray(null)).
            result["event"] = null
        }

        return result
    }

    /**
     * Convert a single Event to JSON structure
     */
    private fun convertEvent(event: Event, excludeEventAttrs: List<String> = emptyList()): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val eventAttributes = mutableMapOf<String, MutableList<Map<String, String>>>()

        val excluded = excludeEventAttrs.toSet()

        // Standard attributes (skip those in exclude list)
        if ("concept:name" !in excluded) {
            event.conceptName?.let { name ->
                addAttribute(eventAttributes, "string", "concept:name", name)
            }
        }

        event.conceptInstance?.let { instance ->
            addAttribute(eventAttributes, "string", "concept:instance", instance)
        }

        event.identityId?.let { id ->
            addAttribute(eventAttributes, "string", "identity:id", id.toString())
        }

        event.lifecycleTransition?.let { transition ->
            addAttribute(eventAttributes, "string", "lifecycle:transition", transition)
        }

        event.lifecycleState?.let { state ->
            addAttribute(eventAttributes, "string", "lifecycle:state", state)
        }

        event.orgResource?.let { resource ->
            addAttribute(eventAttributes, "string", "org:resource", resource)
        }

        event.orgRole?.let { role ->
            addAttribute(eventAttributes, "string", "org:role", role)
        }

        event.orgGroup?.let { group ->
            addAttribute(eventAttributes, "string", "org:group", group)
        }

        event.timeTimestamp?.let { timestamp ->
            addAttribute(eventAttributes, "date", "time:timestamp", formatTimestamp(timestamp))
        }

        if ("cost:currency" !in excluded) {
            event.costCurrency?.let { currency ->
                addAttribute(eventAttributes, "string", "cost:currency", currency)
            }
        }

        event.costTotal?.let { cost ->
            addAttribute(eventAttributes, "float", "cost:total", cost.toString())
        }

        // Custom attributes (skip those in exclude list)
        event.attributes.forEach { (key, value) ->
            if (key !in excluded) {
                if (value != null) {
                    addAttributeByType(eventAttributes, key, value)
                } else {
                    addAttribute(eventAttributes, "string", key, "null")
                }
            }
        }

        // Add attribute lists to result (single object if 1 item, array if >1)
        eventAttributes.forEach { (type, attrs) ->
            result[type] = toSingleOrArray(attrs)
        }

        return result
    }

    /**
     * Add an attribute to the attributes map
     */
    private fun addAttribute(
        attributes: MutableMap<String, MutableList<Map<String, String>>>,
        type: String,
        key: String,
        value: String
    ) {
        val attrList = attributes.getOrPut(type) { mutableListOf() }
        attrList.add(mapOf("@key" to key, "@value" to value))
    }

    /**
     * Convert list to single object or array based on size
     * Matches ProcessM XML→JSON behavior:
     * - 1 element → single object
     * - >1 elements → array
     */
    private fun <T> toSingleOrArray(list: List<T>): Any {
        return when (list.size) {
            0 -> emptyList<T>()
            1 -> list.first()!!
            else -> list
        }
    }

    /**
     * Add an attribute based on its value type
     */
    private fun addAttributeByType(
        attributes: MutableMap<String, MutableList<Map<String, String>>>,
        key: String,
        value: Any
    ) {
        when (value) {
            is String -> addAttribute(attributes, "string", key, value)
            is Int, is Long -> addAttribute(attributes, "int", key, value.toString())
            is Float, is Double -> addAttribute(attributes, "float", key, value.toString())
            is Boolean -> addAttribute(attributes, "boolean", key, value.toString())
            is Instant -> addAttribute(attributes, "date", key, formatTimestamp(value))
            is java.time.ZonedDateTime -> addAttribute(attributes, "date", key, formatTimestamp(value.toInstant()))
            is java.time.LocalDateTime -> addAttribute(attributes, "date", key,
                formatTimestamp(value.toInstant(ZoneOffset.UTC)))
            else -> addAttribute(attributes, "string", key, value.toString())
        }
    }

    /**
     * Format Instant timestamp to XES date format (UTC)
     * Preserves sub-second precision when present (millis, micros)
     */
    private fun formatTimestamp(instant: Instant): String {
        val nano = instant.nano
        return if (nano == 0) {
            instant.atZone(ZoneOffset.UTC).format(xesDateFormatterNoMillis)
        } else {
            // Use ISO_INSTANT which preserves fractional seconds
            DateTimeFormatter.ISO_INSTANT.format(instant)
        }
    }

    /**
     * Convert a GlobalAttribute to JSON structure
     * Format: {"@scope": "trace/event", "string": [...], "date": [...], etc.}
     */
    private fun convertGlobalAttribute(global: com.processm.processminterpreter.model.hierarchical.GlobalAttribute): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        result["@scope"] = global.scope

        // Group attributes by type
        val attributesByType = mutableMapOf<String, MutableList<Map<String, String>>>()

        global.attributes.forEach { (key, value) ->
            if (value != null) {
                addAttributeByType(attributesByType, key, value)
            }
        }

        // Add attribute type groups to result (single object if 1 item, array if >1)
        attributesByType.forEach { (type, attrs) ->
            result[type] = toSingleOrArray(attrs)
        }

        return result
    }
}