package com.processm.processminterpreter.infrastructure.processm.json

import com.processm.processminterpreter.domain.log.GlobalAttribute
import com.processm.processminterpreter.domain.log.xes.XesAttributeValue
import com.processm.processminterpreter.domain.log.xes.XesEvent
import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.log.xes.XesTrace
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

    private val LOG_INTERNAL_ATTRIBUTES =
        setOf(
            "description",
            "traceGlobals",
            "eventGlobals",
            "extensions",
            "classifiers",
            "dataStoreId",
            "logId",
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
        projectedTraceAttrs: Set<String> = emptySet(),
        includeTraces: Boolean = true,
        includeEvents: Boolean = true,
    ): Map<String, Any> {
        logger.debug("Converting ${logs.size} logs to XES JSON format (projected: $isProjectedQuery)")

        if (logs.isEmpty()) {
            return mapOf("log" to emptyMap<String, Any>())
        }

        val log = logs.first()
        return mapOf("log" to convertLog(log, isProjectedQuery, logSelectAll, projectedTraceAttrs, includeTraces, includeEvents))
    }

    fun convertToXESJsonDocuments(
        logs: List<XesLog>,
        isProjectedQuery: Boolean = false,
        logSelectAll: Boolean = false,
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
        result.putAll(processMJsonAttributeView(logAttributes(log, isProjectedQuery, logSelectAll)))
        appendLogIdentityId(result, log, isProjectedQuery, logSelectAll)
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
                    "@scope" to "event",
                    "@keys" to classifier.keys.joinToString(" "),
                )
            }
    }

    private fun logAttributes(
        log: XesLog,
        isProjectedQuery: Boolean,
        logSelectAll: Boolean,
    ): Map<String, Any?> {
        val attributes = linkedMapOf<String, Any?>()

        if (isProjectedQuery && !logSelectAll) {
            log.conceptName
                ?.takeIf { it != UNKNOWN_CONCEPT_NAME }
                ?.let { attributes[ATTR_CONCEPT_NAME] = it }
        } else {
            log.lifecycleModel?.let { attributes[ATTR_LIFECYCLE_MODEL] = it }
        }

        logger.debug("convertLog - log.customAttributes keys: {}", log.customAttributes.keys)
        log.customAttributes.forEach { (key, value) ->
            if (key !in LOG_INTERNAL_ATTRIBUTES) {
                attributes[key] = value
            }
        }

        return attributes
    }

    private fun appendLogIdentityId(
        result: MutableMap<String, Any>,
        log: XesLog,
        isProjectedQuery: Boolean,
        logSelectAll: Boolean,
    ) {
        if (isProjectedQuery && !logSelectAll) return
        if (log.customAttributes.containsKey(ATTR_IDENTITY_ID)) return
        val identityId = log.identityId ?: syntheticLogIdentityId(log)
        result["id"] = mapOf("@key" to ATTR_IDENTITY_ID, "@value" to identityId.toString())
    }

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
     * ProcessM's JSON endpoint is produced by streaming XES XML through StAXON.
     * Repeated sibling tags are only preserved within the last contiguous run of
     * a given tag name; an earlier `string` run followed by `float` and another
     * `string` run gets overwritten. This is a wire-compatibility concern only;
     * the domain model and XES export still retain the full attribute set.
     */
    private fun processMJsonAttributeView(values: Map<String, Any?>): Map<String, Any> {
        val lastRunByType = linkedMapOf<String, MutableList<Map<String, String>>>()
        var previousType: String? = null
        var currentRun = mutableListOf<Map<String, String>>()

        orderedAttributes(values).forEach { attribute ->
            if (attribute.type != previousType) {
                currentRun = mutableListOf()
                lastRunByType[attribute.type] = currentRun
                previousType = attribute.type
            }
            currentRun.add(mapOf("@key" to attribute.key, "@value" to attribute.value))
        }

        return lastRunByType.mapValuesTo(linkedMapOf()) { (_, attrs) -> toSingleOrArray(attrs) }
    }

    private fun orderedAttributes(values: Map<String, Any?>): List<AttributeToken> =
        values.keys.sorted().map { key -> attributeToken(key, values[key]) }

    private fun attributeToken(
        key: String,
        value: Any?,
    ): AttributeToken {
        val scalarValue = if (value is XesAttributeValue) value.value else value
        return when (scalarValue) {
            null -> AttributeToken("string", key, "null")
            is String -> AttributeToken("string", key, scalarValue)
            is Int, is Long -> AttributeToken("int", key, scalarValue.toString())
            is Float, is Double -> AttributeToken("float", key, scalarValue.toString())
            is Boolean -> AttributeToken("boolean", key, scalarValue.toString())
            is UUID -> AttributeToken("id", key, scalarValue.toString())
            is Instant -> AttributeToken("date", key, formatTimestamp(scalarValue))
            is java.time.ZonedDateTime -> AttributeToken("date", key, formatTimestamp(scalarValue.toInstant()))
            is java.time.LocalDateTime ->
                AttributeToken("date", key, formatTimestamp(scalarValue.toInstant(ZoneOffset.UTC)))
            else -> AttributeToken("string", key, scalarValue.toString())
        }
    }

    private data class AttributeToken(
        val type: String,
        val key: String,
        val value: String,
    )

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
