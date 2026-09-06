package com.processm.processminterpreter.processm.json

import com.processm.processminterpreter.xes.model.GlobalAttribute
import com.processm.processminterpreter.xes.model.XesAttributeValue
import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Converts hierarchical XES domain objects to the JSON shape exposed by ProcessM.
 *
 * ProcessM builds this JSON by streaming XES XML through StAXON, so this class is
 * deliberately a wire adapter. It must not leak ProcessM quirks back into the
 * domain model or XES writer.
 */
object XESJsonConverter {
    private const val ATTR_CONCEPT_NAME = "concept:name"
    private const val ATTR_CONCEPT_INSTANCE = "concept:instance"
    private const val ATTR_IDENTITY_ID = "identity:id"
    private const val ATTR_LIFECYCLE_MODEL = "lifecycle:model"
    private const val ATTR_LIFECYCLE_TRANSITION = "lifecycle:transition"
    private const val ATTR_LIFECYCLE_STATE = "lifecycle:state"
    private const val ATTR_ORG_RESOURCE = "org:resource"
    private const val ATTR_ORG_ROLE = "org:role"
    private const val ATTR_ORG_GROUP = "org:group"
    private const val ATTR_TIME_TIMESTAMP = "time:timestamp"
    private const val ATTR_COST_CURRENCY = "cost:currency"
    private const val ATTR_COST_TOTAL = "cost:total"
    private const val UNKNOWN_CONCEPT_NAME = "unknown"

    private val logger = LoggerFactory.getLogger(XESJsonConverter::class.java)

    private val LOG_STANDARD_ATTRIBUTES =
        setOf(
            ATTR_CONCEPT_NAME,
            ATTR_LIFECYCLE_MODEL,
            ATTR_IDENTITY_ID,
        )

    private val TRACE_STANDARD_ATTRIBUTES =
        setOf(
            ATTR_CONCEPT_NAME,
            ATTR_IDENTITY_ID,
            ATTR_COST_CURRENCY,
            ATTR_COST_TOTAL,
        )

    private val EVENT_STANDARD_ATTRIBUTES =
        setOf(
            ATTR_CONCEPT_NAME,
            ATTR_CONCEPT_INSTANCE,
            ATTR_IDENTITY_ID,
            ATTR_LIFECYCLE_TRANSITION,
            ATTR_LIFECYCLE_STATE,
            ATTR_ORG_RESOURCE,
            ATTR_ORG_ROLE,
            ATTR_ORG_GROUP,
            ATTR_TIME_TIMESTAMP,
            ATTR_COST_CURRENCY,
            ATTR_COST_TOTAL,
        )

    private val xesDateFormatterNoMillis =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)

    fun convertToXESJson(
        logs: List<XesLog>,
        isProjectedQuery: Boolean = false,
        logSelectAll: Boolean = false,
        projectedLogAttrs: Set<String> = emptySet(),
        projectedTraceAttrs: Set<String> = emptySet(),
        includeTraces: Boolean = true,
        includeEvents: Boolean = true,
    ): Map<String, Any> {
        logger.debug("Converting {} logs to XES JSON format (projected: {})", logs.size, isProjectedQuery)

        if (logs.isEmpty()) {
            return mapOf("log" to emptyMap<String, Any>())
        }

        val log = logs.first()
        return mapOf(
            "log" to convertLog(
                log,
                isProjectedQuery,
                logSelectAll,
                projectedLogAttrs,
                projectedTraceAttrs,
                includeTraces,
                includeEvents,
            ),
        )
    }

    fun convertToXESJsonDocuments(
        logs: List<XesLog>,
        isProjectedQuery: Boolean = false,
        logSelectAll: Boolean = false,
        projectedLogAttrs: Set<String> = emptySet(),
        projectedTraceAttrs: Set<String> = emptySet(),
        includeTraces: Boolean = true,
        includeEvents: Boolean = true,
    ): List<Map<String, Any?>> =
        logs.map { log ->
            mapOf(
                "log" to convertLog(
                    log,
                    isProjectedQuery,
                    logSelectAll,
                    projectedLogAttrs,
                    projectedTraceAttrs,
                    includeTraces,
                    includeEvents,
                ),
            )
        }

    private fun convertLog(
        log: XesLog,
        isProjectedQuery: Boolean = false,
        logSelectAll: Boolean = false,
        projectedLogAttrs: Set<String> = emptySet(),
        projectedTraceAttrs: Set<String> = emptySet(),
        includeTraces: Boolean = true,
        includeEvents: Boolean = true,
    ): Map<String, Any> {
        val result = linkedMapOf<String, Any>(
            "@xes.version" to "1.0",
            "@xmlns" to "http://www.xes-standard.org/",
        )

        appendExtensions(result, log)
        if (!isProjectedQuery || logSelectAll) appendGlobals(result, log)
        appendClassifiers(result, log)
        result.putAll(
            processMJsonAttributeView(logAttributes(log, isProjectedQuery, logSelectAll, projectedLogAttrs)),
        )
        appendTraces(result, log, isProjectedQuery, projectedTraceAttrs, includeTraces, includeEvents)

        return result
    }

    private fun appendExtensions(
        result: MutableMap<String, Any>,
        log: XesLog,
    ) {
        if (log.extensions.isEmpty()) return

        result["extension"] =
            log.extensions.map { ext ->
                mapOf(
                    "@name" to ext.name,
                    "@prefix" to ext.prefix,
                    "@uri" to ext.uri,
                )
            }
    }

    private fun appendGlobals(
        result: MutableMap<String, Any>,
        log: XesLog,
    ) {
        val globalsByScope = linkedMapOf<String, MutableMap<String, Any?>>()

        fun addGlobal(global: GlobalAttribute) {
            val scopeAttrs = globalsByScope.getOrPut(global.scope.name.lowercase()) { linkedMapOf() }
            scopeAttrs[global.key] = global.value
        }

        log.traceGlobals.forEach { addGlobal(it) }
        log.eventGlobals.forEach { addGlobal(it) }

        val globalArray =
            globalsByScope.map { (scope, attrs) ->
                mapOf("@scope" to scope) + processMJsonAttributeView(attrs)
            }

        if (globalArray.isNotEmpty()) {
            result["global"] = globalArray
        }
    }

    private fun appendClassifiers(
        result: MutableMap<String, Any>,
        log: XesLog,
    ) {
        if (log.classifiers.isEmpty()) return

        result["classifier"] =
            log.classifiers.map { classifier ->
                mapOf(
                    "@name" to classifier.name,
                    "@scope" to classifier.scope.name.lowercase(),
                    "@keys" to classifier.keys.joinToString(" "),
                )
            }
    }

    private fun logAttributes(
        log: XesLog,
        isProjectedQuery: Boolean,
        logSelectAll: Boolean,
        projectedLogAttrs: Set<String>,
    ): Map<String, Any?> {
        val attributes = linkedMapOf<String, Any?>()

        log.conceptName
            ?.takeIf { it != UNKNOWN_CONCEPT_NAME }
            ?.let { attributes[ATTR_CONCEPT_NAME] = it }
        if (!isProjectedQuery || logSelectAll) {
            log.lifecycleModel?.let { attributes[ATTR_LIFECYCLE_MODEL] = it }
            attributes[ATTR_IDENTITY_ID] = log.identityId
                ?: log.customAttributes[ATTR_IDENTITY_ID]
                ?: syntheticLogIdentityId(log)
        }

        logger.debug("convertLog - log.customAttributes keys: {}", log.customAttributes.keys)
        // Reconstruction already separates storage metadata from source XES keys.
        // Custom attributes such as "description" or "logId" are user data here.
        log.customAttributes.forEach { (key, value) ->
            if (
                key !in LOG_STANDARD_ATTRIBUTES ||
                shouldEmitProjectedLogAttribute(key, isProjectedQuery, logSelectAll, projectedLogAttrs)
            ) {
                attributes[key] = value
            }
        }

        return attributes
    }

    private fun shouldEmitProjectedLogAttribute(
        key: String,
        isProjectedQuery: Boolean,
        logSelectAll: Boolean,
        projectedLogAttrs: Set<String>,
    ): Boolean =
        isProjectedQuery && !logSelectAll && key in projectedLogAttrs

    private fun syntheticLogIdentityId(log: XesLog): UUID {
        val seed = buildString {
            append("processm-json-log:")
            append(log.conceptName ?: "")
            append('|')
            log.customAttributes.toSortedMap().forEach { (key, value) ->
                append(key).append('=').append(value).append(';')
            }
        }
        return UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8))
    }

    private fun appendTraces(
        result: MutableMap<String, Any>,
        log: XesLog,
        isProjectedQuery: Boolean,
        projectedTraceAttrs: Set<String>,
        includeTraces: Boolean,
        includeEvents: Boolean,
    ) {
        if (!includeTraces || log.traces.isEmpty()) return

        val traceMaps =
            log.traces.map { trace ->
                convertTrace(trace, isProjectedQuery, projectedTraceAttrs, includeEvents)
            }
        result["trace"] = toSingleOrArray(traceMaps)
    }

    private fun convertTrace(
        trace: XesTrace,
        isProjectedQuery: Boolean = false,
        projectedTraceAttrs: Set<String> = emptySet(),
        includeEvents: Boolean = true,
    ): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        result.putAll(processMJsonAttributeView(traceAttributes(trace, isProjectedQuery, projectedTraceAttrs)))
        appendTraceEvents(result, trace, isProjectedQuery, includeEvents)
        return result
    }

    private fun traceAttributes(
        trace: XesTrace,
        isProjectedQuery: Boolean,
        projectedTraceAttrs: Set<String>,
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            putProjectedTraceAttribute(ATTR_CONCEPT_NAME, trace.conceptName, isProjectedQuery, projectedTraceAttrs)
            putProjectedTraceAttribute(ATTR_IDENTITY_ID, trace.identityId, isProjectedQuery, projectedTraceAttrs)
            putProjectedTraceAttribute(ATTR_COST_CURRENCY, trace.costCurrency, isProjectedQuery, projectedTraceAttrs)
            putProjectedTraceAttribute(ATTR_COST_TOTAL, trace.costTotal, isProjectedQuery, projectedTraceAttrs)

            trace.customAttributes.forEach { (key, value) ->
                if (key !in TRACE_STANDARD_ATTRIBUTES) put(key, value)
            }
        }

    private fun MutableMap<String, Any?>.putProjectedTraceAttribute(
        key: String,
        value: Any?,
        isProjectedQuery: Boolean,
        projectedTraceAttrs: Set<String>,
    ) {
        if ((!isProjectedQuery || key in projectedTraceAttrs) && value != null) {
            put(key, value)
        }
    }

    private fun appendTraceEvents(
        result: MutableMap<String, Any?>,
        trace: XesTrace,
        isProjectedQuery: Boolean,
        includeEvents: Boolean,
    ) {
        if (!includeEvents) return

        val eventValue =
            when {
                trace.events.isNotEmpty() ->
                    toSingleOrNullableArray(
                        trace.events.map { event ->
                            val converted = convertEvent(event)
                            if (converted.isEmpty()) null else converted
                        },
                    )
                trace.nullEventCount >= 1 ->
                    toSingleOrNullableArray(List(trace.nullEventCount) { null })
                !isProjectedQuery -> null
                else -> return
            }

        result["event"] = eventValue
    }

    private fun convertEvent(event: XesEvent): Map<String, Any> =
        processMJsonAttributeView(
            linkedMapOf<String, Any?>().apply {
                event.conceptName?.let { put(ATTR_CONCEPT_NAME, it) }
                event.conceptInstance?.let { put(ATTR_CONCEPT_INSTANCE, it) }
                event.identityId?.let { put(ATTR_IDENTITY_ID, it) }
                event.timeTimestamp?.let { put(ATTR_TIME_TIMESTAMP, it) }
                event.lifecycleTransition?.let { put(ATTR_LIFECYCLE_TRANSITION, it) }
                event.lifecycleState?.let { put(ATTR_LIFECYCLE_STATE, it) }
                event.orgResource?.let { put(ATTR_ORG_RESOURCE, it) }
                event.orgRole?.let { put(ATTR_ORG_ROLE, it) }
                event.orgGroup?.let { put(ATTR_ORG_GROUP, it) }
                event.costCurrency?.let { put(ATTR_COST_CURRENCY, it) }
                event.costTotal?.let { put(ATTR_COST_TOTAL, it) }
                event.customAttributes.forEach { (key, value) ->
                    if (key !in EVENT_STANDARD_ATTRIBUTES) put(key, value)
                }
            },
        )

    /**
     * ProcessM may emit repeated JSON fields for separated runs of one XES tag.
     * Represent those same siblings as one single-or-array field, retaining every
     * attribute instead of reproducing a last-field-wins JSON reader's data loss.
     */
    private fun processMJsonAttributeView(values: Map<String, Any?>): Map<String, Any> {
        val attributesByType = linkedMapOf<String, MutableList<Map<String, String>>>()
        for (key in values.keys.sorted()) {
            val scalarValue = (values[key] as? XesAttributeValue)?.value ?: values[key]
            attributesByType.getOrPut(attributeType(scalarValue), ::mutableListOf)
                .add(mapOf("@key" to key, "@value" to attributeValue(scalarValue)))
        }
        return attributesByType.mapValuesTo(linkedMapOf()) { (_, attrs) -> toSingleOrArray(attrs) }
    }

    private fun attributeType(value: Any?): String = when (value) {
        null, is String -> "string"
        is Int, is Long -> "int"
        is Float, is Double -> "float"
        is Boolean -> "boolean"
        is UUID -> "id"
        is Instant, is java.time.ZonedDateTime, is java.time.LocalDateTime -> "date"
        else -> "string"
    }

    private fun attributeValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is Instant -> formatTimestamp(value)
        is java.time.ZonedDateTime -> formatTimestamp(value.toInstant())
        is java.time.LocalDateTime -> formatTimestamp(value.toInstant(ZoneOffset.UTC))
        else -> value.toString()
    }

    private fun <T : Any> toSingleOrArray(list: List<T>): Any =
        when (list.size) {
            0 -> emptyList<T>()
            1 -> list.first()
            else -> list
        }

    private fun <T> toSingleOrNullableArray(list: List<T>): Any? =
        when (list.size) {
            0 -> emptyList<T>()
            1 -> list.first()
            else -> list
        }

    private fun formatTimestamp(instant: Instant): String {
        val nano = instant.nano
        return if (nano == 0) {
            instant.atZone(ZoneOffset.UTC).format(xesDateFormatterNoMillis)
        } else {
            DateTimeFormatter.ISO_INSTANT.format(instant)
        }
    }
}
