package com.processm.processminterpreter.application.processm

import com.processm.processminterpreter.domain.log.GlobalAttribute
import com.processm.processminterpreter.domain.log.xes.XesEvent
import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.log.xes.XesTrace
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Converts hierarchical Log objects to XES JSON format
 * matching ProcessM's XML→JSON conversion format
 *
 * ProcessM uses XML attributes converted to JSON with @key/@value notation
 */
object XESJsonConverter {
    private val logger = LoggerFactory.getLogger(XESJsonConverter::class.java)

    private val LOG_INTERNAL_ATTRIBUTES =
        setOf(
            "description",
            "traceGlobals",
            "eventGlobals",
            "extensions",
            "classifiers",
            "dataStoreId",
            "logId",
            "concept:name",
            "lifecycle:model",
            "identity:id",
        )

    private val TRACE_STANDARD_ATTRIBUTES =
        setOf(
            "concept:name",
            "identity:id",
            "cost:currency",
            "cost:total",
        )

    private val EVENT_STANDARD_ATTRIBUTES =
        setOf(
            "concept:name",
            "concept:instance",
            "identity:id",
            "lifecycle:transition",
            "lifecycle:state",
            "org:resource",
            "org:role",
            "org:group",
            "time:timestamp",
            "cost:currency",
            "cost:total",
        )

    // XES date format in UTC (matching ProcessM output)
    // Uses ISO_INSTANT-like format but with conditional sub-second precision:
    // - No fractional seconds when they're zero: 2020-03-13T16:45:50Z
    // - Milliseconds when present: 2020-03-13T16:45:50.123Z
    // - Microseconds when present: 2020-03-13T16:45:50.123456Z
    private val xesDateFormatterNoMillis =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)

    /**
     * Convert a list of Log objects to XES JSON format
     *
     * @param logs List of Log objects
     * @param isProjectedQuery Whether this is a projected query (SELECT specific fields vs SELECT *)
     * @return Map representing XES JSON structure
     */
    fun convertToXESJson(
        logs: List<XesLog>,
        isProjectedQuery: Boolean = false,
        projectedTraceAttrs: Set<String> = emptySet(),
        includeTraces: Boolean = true,
        includeEvents: Boolean = true,
    ): Map<String, Any> {
        logger.debug("Converting ${logs.size} logs to XES JSON format (projected: $isProjectedQuery)")

        if (logs.isEmpty()) {
            return mapOf("log" to emptyMap<String, Any>())
        }

        // For now, convert first log (ProcessM typically works with single log)
        val log = logs.first()
        return mapOf("log" to convertLog(log, isProjectedQuery, projectedTraceAttrs, includeTraces, includeEvents))
    }

    /**
     * Convert a single Log to JSON structure
     */
    private fun convertLog(
        log: XesLog,
        isProjectedQuery: Boolean = false,
        projectedTraceAttrs: Set<String> = emptySet(),
        includeTraces: Boolean = true,
        includeEvents: Boolean = true,
    ): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        // XES metadata attributes (must be first)
        result["@xes.version"] = "1.0"
        result["@xmlns"] = "http://www.xes-standard.org/"

        // Extensions
        if (log.extensions.isNotEmpty()) {
            result["extension"] =
                log.extensions.map { ext ->
                    mapOf(
                        "@name" to ext.name,
                        "@prefix" to ext.prefix,
                        "@uri" to ext.uri,
                    )
                }
        }

        // Global attributes (after extensions, before classifiers)
        // ONLY add global array for SELECT *, NOT for projected queries
        if (!isProjectedQuery) {
            // Group globals by scope — ProcessM merges globals with the same scope
            // into a single object with string/date/etc arrays
            val globalsByScope = linkedMapOf<String, MutableMap<String, Any?>>()

            fun addGlobal(global: GlobalAttribute) {
                val scopeAttrs = globalsByScope.getOrPut(global.scope.name.lowercase()) { linkedMapOf() }
                scopeAttrs[global.key] = global.value
            }

            log.traceGlobals.forEach { addGlobal(it) }
            log.eventGlobals.forEach { addGlobal(it) }

            val globalArray =
                globalsByScope.map { (scope, attrs) ->
                    val obj = mutableMapOf<String, Any>("@scope" to scope)
                    processMJsonAttributeView(attrs).forEach { (type, encodedAttrs) ->
                        obj[type] = encodedAttrs
                    }
                    obj
                }

            if (globalArray.isNotEmpty()) {
                result["global"] = globalArray
            }
        }

        // Classifiers
        if (log.classifiers.isNotEmpty()) {
            result["classifier"] =
                log.classifiers.map { classifier ->
                    mapOf(
                        "@name" to classifier.name,
                        "@scope" to "event", // ProcessM adds @scope for event classifiers
                        "@keys" to classifier.keys.joinToString(" "),
                    )
                }
        }

        // Log-level attributes (after classifiers, before trace)
        // Add log.customAttributes and log.identityId
        val logAttributes = linkedMapOf<String, Any?>()

        // For projected queries, output log concept:name if it was explicitly selected
        // (ProcessM only includes log concept:name when l:name is in SELECT)
        if (isProjectedQuery) {
            log.conceptName?.let { name ->
                if (name != "unknown") {
                    logAttributes["concept:name"] = name
                }
            }
        } else {
            log.lifecycleModel?.let { model ->
                logAttributes["lifecycle:model"] = model
            }
        }

        // Add log attributes from log.customAttributes
        // For SELECT *: all XES attributes (source, lifecycle:model, identity:id, etc.)
        // For projected queries: only projected expression results (e.g., "log:1.0")
        // In both cases, log.customAttributes contains the right set (populated by HierarchyReconstructor)
        logger.debug("convertLog - log.customAttributes keys: {}", log.customAttributes.keys)

        log.customAttributes.forEach { (key, value) ->
            if (key in LOG_INTERNAL_ATTRIBUTES) return@forEach
            logAttributes[key] = value
        }

        // Also add identity:id from log.identityId if not already in attributes (SELECT * only)
        if (!isProjectedQuery && !log.customAttributes.containsKey("identity:id")) {
            log.identityId?.let { id ->
                logAttributes["identity:id"] = id
            }
        }

        processMJsonAttributeView(logAttributes).forEach { (type, attrs) ->
            result[type] = attrs
        }

        // Traces (single object if 1 trace, array if >1)
        val traces = if (includeTraces) log.traces.toList() else emptyList()
        if (traces.isNotEmpty()) {
            val traceMaps = traces.map { trace -> convertTrace(trace, isProjectedQuery, projectedTraceAttrs, includeEvents) }
            result["trace"] = toSingleOrArray(traceMaps)
        }

        return result
    }

    /**
     * Convert a single Trace to JSON structure
     */
    private fun convertTrace(
        trace: XesTrace,
        isProjectedQuery: Boolean = false,
        projectedTraceAttrs: Set<String> = emptySet(),
        includeEvents: Boolean = true,
    ): Map<String, Any?> {
        val result: MutableMap<String, Any?> = mutableMapOf()
        val traceAttributes = linkedMapOf<String, Any?>()

        // For projected queries, only include trace attributes that were explicitly selected
        // ProcessM omits trace concept:name when t:name is not in SELECT
        val includeTraceName =
            !isProjectedQuery ||
                "concept:name" in projectedTraceAttrs
        val includeTraceId = !isProjectedQuery || "identity:id" in projectedTraceAttrs
        val includeTraceCurrency = !isProjectedQuery || "cost:currency" in projectedTraceAttrs
        val includeTraceTotal = !isProjectedQuery || "cost:total" in projectedTraceAttrs

        // Standard attributes (conditionally for projected queries)
        if (includeTraceName) {
            trace.conceptName?.let { name ->
                traceAttributes["concept:name"] = name
            }
        }

        if (includeTraceId) {
            trace.identityId?.let { id ->
                traceAttributes["identity:id"] = id
            }
        }

        if (includeTraceCurrency) {
            trace.costCurrency?.let { currency ->
                traceAttributes["cost:currency"] = currency
            }
        }

        if (includeTraceTotal) {
            trace.costTotal?.let { cost ->
                traceAttributes["cost:total"] = cost
            }
        }

        // Custom attributes
        trace.customAttributes.forEach { (key, value) ->
            if (key in TRACE_STANDARD_ATTRIBUTES) return@forEach
            traceAttributes[key] = value
        }

        // IMPORTANT: ProcessM returns attributes BEFORE events in trace
        // We must match this order for JSON comparison to work

        // Add attribute lists to result (single object if 1 item, array if >1)
        // ADD ATTRIBUTES FIRST to match ProcessM order
        processMJsonAttributeView(traceAttributes).forEach { (type, attrs) ->
            result[type] = attrs
        }

        // Events (single object if 1 event, array if >1)
        // ADD EVENTS AFTER attributes to match ProcessM order
        val events = if (includeEvents) trace.events.toList() else emptyList()
        if (events.isNotEmpty()) {
            val eventMaps =
                events.map { event ->
                    val converted = convertEvent(event)
                    // Empty event maps (no projected attributes) become null (ProcessM behavior)
                    if (converted.isEmpty()) null else converted
                }
            result["event"] = toSingleOrNullableArray(eventMaps)
        } else if (includeEvents && trace.nullEventCount >= 1) {
            // ProcessM outputs null event placeholders for events that exist
            // in the hierarchy but weren't projected by the query.
            // count=1: toSingleOrArray([null]) → null (event: null)
            // count>1: [null, null, ...] array
            val nullList: List<Any?> = (1..trace.nullEventCount).map { null }
            result["event"] = toSingleOrNullableArray(nullList)
        } else if (includeEvents && trace.nullEventCount == 0 && !isProjectedQuery) {
            // Aggregation queries (implicit GROUP BY) collapse events — no event data in output.
            // ProcessM outputs "event": null for these traces (matches toSingleOrArray(null)).
            result["event"] = null
        }

        return result
    }

    /**
     * Convert a single Event to JSON structure
     */
    private fun convertEvent(event: XesEvent): Map<String, Any> =
        processMJsonAttributeView(
            linkedMapOf<String, Any?>().apply {
                event.conceptName?.let { put("concept:name", it) }
                event.conceptInstance?.let { put("concept:instance", it) }
                event.identityId?.let { put("identity:id", it) }
                event.timeTimestamp?.let { put("time:timestamp", it) }
                event.lifecycleTransition?.let { put("lifecycle:transition", it) }
                event.lifecycleState?.let { put("lifecycle:state", it) }
                event.orgResource?.let { put("org:resource", it) }
                event.orgRole?.let { put("org:role", it) }
                event.orgGroup?.let { put("org:group", it) }
                event.costCurrency?.let { put("cost:currency", it) }
                event.costTotal?.let { put("cost:total", it) }
                event.customAttributes.forEach { (key, value) ->
                    if (key !in EVENT_STANDARD_ATTRIBUTES) put(key, value)
                }
            },
        )


                // Expression evaluates to null → output with "null" value (ProcessM behavior)

    

    /**
     * ProcessM's JSON endpoint is produced by streaming XES XML through StAXON.
     * Repeated sibling tags are only preserved within the last contiguous run of
     * a given tag name; an earlier `string` run followed by `float` and another
     * `string` run gets overwritten. This is a wire-compatibility concern only;
     * the domain model and XES export still retain the full attribute set.
     */
    private fun processMJsonAttributeView(values: Map<String, Any?>): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val lastRunByType = linkedMapOf<String, MutableList<Map<String, String>>>()
        var previousType: String? = null
        var currentRun: MutableList<Map<String, String>>? = null

        orderedAttributes(values).forEach { attribute ->
            if (attribute.type != previousType) {
                currentRun = mutableListOf()
                lastRunByType[attribute.type] = currentRun!!
                previousType = attribute.type
            }
            currentRun!!.add(mapOf("@key" to attribute.key, "@value" to attribute.value))
        }

        lastRunByType.forEach { (type, attrs) ->
            result[type] = toSingleOrArray(attrs)
        }
        return result
    }

    private fun orderedAttributes(values: Map<String, Any?>): List<AttributeToken> {
        return values.keys.sorted().map { key -> attributeToken(key, values[key]) }
    }

    private fun attributeToken(
        key: String,
        value: Any?,
    ): AttributeToken =
        when (value) {
            null -> AttributeToken("string", key, "null")
            is String -> AttributeToken("string", key, value)
            is Int, is Long -> AttributeToken("int", key, value.toString())
            is Float, is Double -> AttributeToken("float", key, value.toString())
            is Boolean -> AttributeToken("boolean", key, value.toString())
            is UUID -> AttributeToken("id", key, value.toString())
            is Instant -> AttributeToken("date", key, formatTimestamp(value))
            is java.time.ZonedDateTime -> AttributeToken("date", key, formatTimestamp(value.toInstant()))
            is java.time.LocalDateTime ->
                AttributeToken("date", key, formatTimestamp(value.toInstant(ZoneOffset.UTC)))
            else -> AttributeToken("string", key, value.toString())
        }

    private data class AttributeToken(
        val type: String,
        val key: String,
        val value: String,
    )

    /**
     * Convert list to single object or array based on size
     * Matches ProcessM XML→JSON behavior:
     * - 1 element → single object
     * - >1 elements → array
     */
    private fun <T> toSingleOrArray(list: List<T>): Any =
        when (list.size) {
            0 -> emptyList<T>()
            1 -> list.first()!!
            else -> list
        }

    private fun <T> toSingleOrNullableArray(list: List<T>): Any? =
        when (list.size) {
            0 -> emptyList<T>()
            1 -> list.first()
            else -> list
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

}



