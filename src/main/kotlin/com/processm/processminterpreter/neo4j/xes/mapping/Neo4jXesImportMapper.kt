package com.processm.processminterpreter.neo4j.xes.mapping

import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import com.processm.processminterpreter.neo4j.property.Neo4jPropertySanitizer
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.neo4j.xes.metadata.XesLogMetadataCodec
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesSchema
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Maps the domain XES projection to backend-neutral row maps consumed by the
 * Neo4j batch writer. No Cypher or driver calls live here.
 */
@Component
class Neo4jXesImportMapper(
    private val attributes: Neo4jXesAttributeMapper = Neo4jXesAttributeMapper(),
    @param:Value("\${processm.neo4j.persist-follows:false}")
    private val persistFollows: Boolean = false,
) {
    fun toImportBatch(
        log: XesLog,
        requestedLogId: String?,
        importedAt: LocalDateTime,
    ): Neo4jXesImportBatch {
        val logId = logId(requestedLogId)
        val activityVariantIds = linkedMapOf<String, Long>()
        val indexedTraces = log.traces.asSequence().mapIndexed { index, trace ->
            val activityVariant = activityVariant(trace)
            IndexedTrace(
                trace = trace,
                index = index,
                traceId = traceId(logId, trace, index),
                activityVariantId = activityVariantIds.getOrPut(activityVariant.key) {
                    activityVariantIds.size.toLong()
                },
                activityNonNullCount = activityVariant.nonNullCount,
            )
        }

        return Neo4jXesImportBatch(
            logId = logId,
            logName = log.conceptName ?: "Unnamed Log",
            importedAt = importedAt,
            logAttributes = attributePayload(Scope.LOG, attributes.logAttributes(log)),
            classifiers = XesLogMetadataCodec.serializeClassifiers(log.classifiers),
            traceGlobals = XesLogMetadataCodec.serializeGlobals(log.traceGlobals),
            eventGlobals = XesLogMetadataCodec.serializeGlobals(log.eventGlobals),
            extensions = XesLogMetadataCodec.serializeExtensions(log.extensions),
            traceCount = log.traces.size,
            eventCount = log.traces.sumOf { it.events.size },
            traceBatches = chunkByEventBudget(indexedTraces).map { traceBatch ->
                Neo4jXesTraceBatch(
                    traces = traceRows(traceBatch, importedAt),
                    events = eventRows(traceBatch, importedAt),
                    follows = if (persistFollows) followRows(traceBatch, importedAt) else emptyList(),
                )
            },
        )
    }

    private fun traceRows(
        traceBatch: List<IndexedTrace>,
        importedAt: LocalDateTime,
    ): List<Map<String, Any?>> =
        traceBatch.map { indexed ->
            mapOf(
                TRACE_ID_PROPERTY to indexed.traceId,
                TRACE_NAME_PROPERTY to indexed.trace.conceptName,
                TRACE_ACTIVITY_VARIANT_ID_PROPERTY to indexed.activityVariantId,
                TRACE_ACTIVITY_NON_NULL_COUNT_PROPERTY to indexed.activityNonNullCount,
                "createdAt" to importedAt,
                "importOrder" to indexed.index,
                "attributes" to attributePayload(Scope.TRACE, attributes.traceAttributes(indexed.trace)),
            )
        }

    /**
     * Encodes the ordered, non-null activity sequence without delimiters that
     * could collide with source values. Cypher's `collect(event.activity)` drops
     * nulls, so omitting them here preserves the existing ProcessM-compatible
     * variant grouping semantics exactly.
     */
    private fun activityVariant(trace: XesTrace): ActivityVariant {
        var nonNullCount = 0
        val key = buildString {
            trace.events.forEach { event ->
                event.conceptName?.let { activity ->
                    append(activity.length).append(':').append(activity)
                    nonNullCount++
                }
            }
        }
        return ActivityVariant(key, nonNullCount)
    }

    private fun eventRows(
        traceBatch: List<IndexedTrace>,
        importedAt: LocalDateTime,
    ): List<Map<String, Any?>> =
        traceBatch.flatMap { indexed ->
            indexed.trace.events.mapIndexed { eventIndex, event ->
                mapOf(
                    TRACE_ID_PROPERTY to indexed.traceId,
                    EVENT_ID_PROPERTY to eventId(indexed.traceId, eventIndex),
                    EVENT_NAME_PROPERTY to event.conceptName,
                    EVENT_TIMESTAMP_PROPERTY to attributes.physicalEventTimestamp(event),
                    EVENT_RESOURCE_PROPERTY to event.orgResource,
                    EVENT_LIFECYCLE_PROPERTY to event.lifecycleTransition,
                    EVENT_COST_PROPERTY to event.costTotal,
                    "createdAt" to importedAt,
                    "importOrder" to eventIndex,
                    "attributes" to attributePayload(Scope.EVENT, attributes.eventAttributes(event)),
                )
            }
        }

    /**
     * Sanitizes attributes for `SET node += attributes` and strips any key that
     * collides with a writer-managed structural column (see
     * [Neo4jXesSchema.writerManagedProperties]) so the generated id/columns
     * survive. Stripped after sanitize because a `.`→`_` rewrite can turn a
     * custom key into a reserved name.
     */
    private fun attributePayload(scope: Scope, raw: Map<String, Any?>): Map<String, Any> {
        val sanitized = Neo4jPropertySanitizer.sanitizeAttributesWithNestedPayload(raw)
        val reserved = Neo4jXesSchema.writerManagedProperties(scope)
        return if (sanitized.keys.none { it in reserved }) {
            sanitized
        } else {
            sanitized.filterKeys { it !in reserved }
        }
    }

    private fun followRows(
        traceBatch: List<IndexedTrace>,
        importedAt: LocalDateTime,
    ): List<Map<String, String>> =
        traceBatch.flatMap { indexed ->
            indexed.indexedEvents()
                .sortedWith(
                    compareBy<IndexedEvent> { it.event.timestampOr(importedAt) }
                        .thenBy { it.index },
                )
                .windowed(2)
                .map { (from, to) ->
                    mapOf("fromEventId" to from.eventId, "toEventId" to to.eventId)
                }
        }

    private data class IndexedTrace(
        val trace: XesTrace,
        val index: Int,
        val traceId: String,
        val activityVariantId: Long,
        val activityNonNullCount: Int,
    )

    private data class ActivityVariant(
        val key: String,
        val nonNullCount: Int,
    )

    private fun IndexedTrace.indexedEvents(): List<IndexedEvent> =
        trace.events.mapIndexed { eventIndex, event ->
            IndexedEvent(event = event, index = eventIndex, eventId = eventId(traceId, eventIndex))
        }

    private data class IndexedEvent(
        val event: XesEvent,
        val index: Int,
        val eventId: String,
    )

    private fun XesEvent.timestampOr(fallback: LocalDateTime): LocalDateTime =
        timeTimestamp?.toNeo4jDateTime() ?: fallback

    private fun Instant.toNeo4jDateTime(): LocalDateTime =
        LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    /**
     * Groups traces into import batches bounded by total event count, so each
     * write transaction carries a similar payload regardless of trace sizes.
     * A single oversized trace still forms its own batch — the budget caps the
     * batch, never splits a trace.
     */
    private fun chunkByEventBudget(traces: Sequence<IndexedTrace>): Sequence<List<IndexedTrace>> = sequence {
        var current = mutableListOf<IndexedTrace>()
        var eventsInBatch = 0
        for (indexed in traces) {
            val traceEvents = indexed.trace.events.size
            val overBudget = eventsInBatch + traceEvents > EVENT_BATCH_BUDGET ||
                current.size >= MAX_TRACES_PER_BATCH
            if (current.isNotEmpty() && overBudget) {
                yield(current)
                current = mutableListOf()
                eventsInBatch = 0
            }
            current += indexed
            eventsInBatch += traceEvents
        }
        if (current.isNotEmpty()) yield(current)
    }

    private companion object {
        /** Upper bound on events per write transaction (UNWIND payload size). */
        const val EVENT_BATCH_BUDGET = 10_000

        /** Hard cap on traces per batch for logs with very small traces. */
        const val MAX_TRACES_PER_BATCH = 500

        fun logId(requestedLogId: String?): String =
            requestedLogId ?: "log-${UUID.randomUUID().toString().substring(0, 8)}"

        fun traceId(logId: String, trace: XesTrace, index: Int): String =
            "$logId-trace-${index + 1}-${(trace.conceptName ?: "unnamed").replace(" ", "_")}"

        fun eventId(traceId: String, index: Int): String =
            "$traceId-event-${index + 1}"

        /* Generated storage keys, not the XES `identity:id` of the trace/event. */
        const val TRACE_ID_PROPERTY: String = Neo4jXesSchema.TRACE_ID_PROPERTY
        const val TRACE_ACTIVITY_VARIANT_ID_PROPERTY: String =
            Neo4jXesSchema.TRACE_ACTIVITY_VARIANT_ID_PROPERTY
        const val TRACE_ACTIVITY_NON_NULL_COUNT_PROPERTY: String =
            Neo4jXesSchema.TRACE_ACTIVITY_NON_NULL_COUNT_PROPERTY
        const val EVENT_ID_PROPERTY: String = Neo4jXesSchema.EVENT_ID_PROPERTY

        val TRACE_NAME_PROPERTY: String = Neo4jXesSchema.physicalName(Scope.TRACE, StandardAttributeCatalog.CONCEPT_NAME)
        val EVENT_NAME_PROPERTY: String = Neo4jXesSchema.physicalName(Scope.EVENT, StandardAttributeCatalog.CONCEPT_NAME)
        val EVENT_TIMESTAMP_PROPERTY: String = Neo4jXesSchema.physicalName(Scope.EVENT, StandardAttributeCatalog.TIME_TIMESTAMP)
        val EVENT_RESOURCE_PROPERTY: String = Neo4jXesSchema.physicalName(Scope.EVENT, StandardAttributeCatalog.ORG_RESOURCE)
        val EVENT_LIFECYCLE_PROPERTY: String = Neo4jXesSchema.physicalName(Scope.EVENT, StandardAttributeCatalog.LIFECYCLE_TRANSITION)
        val EVENT_COST_PROPERTY: String = Neo4jXesSchema.physicalName(Scope.EVENT, StandardAttributeCatalog.COST_TOTAL)
    }
}
