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

    // XES date format in UTC (matching ProcessM output): yyyy-MM-dd'T'HH:mm:ssZ
    private val xesDateFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)

    /**
     * Convert a list of Log objects to XES JSON format
     *
     * @param logs List of Log objects
     * @param isProjectedQuery Whether this is a projected query (SELECT specific fields vs SELECT *)
     * @return Map representing XES JSON structure
     */
    fun convertToXESJson(logs: List<Log>, isProjectedQuery: Boolean = false, excludeEventAttrs: List<String> = emptyList()): Map<String, Any> {
        logger.debug("Converting ${logs.size} logs to XES JSON format (projected: $isProjectedQuery)")

        if (logs.isEmpty()) {
            return mapOf("log" to emptyMap<String, Any>())
        }

        // For now, convert first log (ProcessM typically works with single log)
        val log = logs.first()
        return mapOf("log" to convertLog(log, isProjectedQuery, excludeEventAttrs))
    }

    /**
     * Convert a single Log to JSON structure
     */
    private fun convertLog(log: Log, isProjectedQuery: Boolean = false, excludeEventAttrs: List<String> = emptyList()): Map<String, Any> {
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
            val globalArray = mutableListOf<Map<String, Any>>()

            // Add trace globals
            log.traceGlobals.forEach { global ->
                globalArray.add(convertGlobalAttribute(global))
            }

            // Add event globals
            log.eventGlobals.forEach { global ->
                globalArray.add(convertGlobalAttribute(global))
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

        // For SELECT *, add all log attributes from XES (match ProcessM)
        // For projected queries, skip custom log attributes (user didn't select them)
        if (!isProjectedQuery) {
            logger.debug("convertLog - log.attributes keys: {}", log.attributes.keys)
            logger.debug("convertLog - log.attributes values: {}", log.attributes)

            // Add all attributes from log.attributes (e.g., source, lifecycle:model, identity:id)
            log.attributes.forEach { (key, value) ->
                if (value != null) {
                    logger.debug("Processing log attribute: {} = {}", key, value)
                    // Special handling for identity:id - use "id" type instead of "string"
                    if (key == "identity:id") {
                        addAttribute(logAttributes, "id", key, value.toString())
                    } else {
                        addAttributeByType(logAttributes, key, value)
                    }
                }
            }

            // Also add identity:id from log.identityId if not already in attributes
            if (!log.attributes.containsKey("identity:id")) {
                log.identityId?.let { id ->
                    addAttribute(logAttributes, "id", "identity:id", id.toString())
                }
            }
        }

        // Add log attribute groups to result (single object if 1 item, array if >1)
        logAttributes.forEach { (type, attrs) ->
            result[type] = toSingleOrArray(attrs)
        }

        // Traces (single object if 1 trace, array if >1)
        val traces = log.traces.toList()
        if (traces.isNotEmpty()) {
            val traceMaps = traces.map { trace -> convertTrace(trace, excludeEventAttrs) }
            result["trace"] = toSingleOrArray(traceMaps)
        }

        return result
    }

    /**
     * Convert a single Trace to JSON structure
     */
    private fun convertTrace(trace: Trace, excludeEventAttrs: List<String> = emptyList()): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val traceAttributes = mutableMapOf<String, MutableList<Map<String, String>>>()

        // Standard attributes
        trace.conceptName?.let { name ->
            addAttribute(traceAttributes, "string", "concept:name", name)
        }

        trace.identityId?.let { id ->
            addAttribute(traceAttributes, "string", "identity:id", id.toString())
        }

        trace.costCurrency?.let { currency ->
            addAttribute(traceAttributes, "string", "cost:currency", currency)
        }

        trace.costTotal?.let { cost ->
            addAttribute(traceAttributes, "float", "cost:total", cost.toString())
        }

        // Custom attributes
        trace.attributes.forEach { (key, value) ->
            if (value != null) {
                addAttributeByType(traceAttributes, key, value)
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
            if (value != null && key !in excluded) {
                addAttributeByType(eventAttributes, key, value)
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
            is java.time.ZonedDateTime -> addAttribute(attributes, "date", key, value.toInstant().atZone(ZoneOffset.UTC).format(xesDateFormatter))
            is java.time.LocalDateTime -> addAttribute(attributes, "date", key,
                value.toInstant(ZoneOffset.UTC).atZone(ZoneOffset.UTC).format(xesDateFormatter))
            else -> addAttribute(attributes, "string", key, value.toString())
        }
    }

    /**
     * Format Instant timestamp to XES date format (UTC)
     */
    private fun formatTimestamp(instant: Instant): String {
        return instant.atZone(ZoneOffset.UTC).format(xesDateFormatter)
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