package com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.mapping

import com.processm.processminterpreter.domain.log.xes.XesEvent
import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.log.xes.XesTrace
import com.processm.processminterpreter.infrastructure.persistence.neo4j.property.Neo4jPropertySanitizer
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.metadata.XesLogMetadataCodec
import com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.schema.Neo4jXesSchema
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
) {
    fun toImportBatch(
        log: XesLog,
        requestedLogId: String?,
        importedAt: LocalDateTime,
    ): Neo4jXesImportBatch {
        val logId = logId(requestedLogId)
        val indexedTraces = log.traces.mapIndexed { index, trace ->
            IndexedTrace(trace, index, traceId(logId, trace, index))
        }

        return Neo4jXesImportBatch(
            logId = logId,
            logName = log.conceptName ?: "Unnamed Log",
            importedAt = importedAt,
            logAttributes = Neo4jPropertySanitizer.sanitizeAttributes(attributes.logAttributes(log)),
            classifiers = XesLogMetadataCodec.serializeClassifiers(log.classifiers),
            traceGlobals = XesLogMetadataCodec.serializeGlobals(log.traceGlobals),
            eventGlobals = XesLogMetadataCodec.serializeGlobals(log.eventGlobals),
            extensions = XesLogMetadataCodec.serializeExtensions(log.extensions),
            traceCount = log.traces.size,
            eventCount = log.traces.sumOf { it.events.size },
            traceBatches = indexedTraces.chunked(BATCH_SIZE).map { traceBatch ->
                Neo4jXesTraceBatch(
                    traces = traceRows(traceBatch, importedAt),
                    events = eventRows(traceBatch, importedAt),
                    follows = followRows(traceBatch, importedAt),
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
                "createdAt" to importedAt,
                "importOrder" to indexed.index,
                "attributes" to Neo4jPropertySanitizer.sanitizeAttributes(
                    attributes.traceAttributes(indexed.trace),
                ),
            )
        }

    private fun eventRows(
        traceBatch: List<IndexedTrace>,
        importedAt: LocalDateTime,
    ): List<Map<String, Any?>> =
        traceBatch.flatMap { indexed ->
            indexed.indexedEvents().map { indexedEvent ->
                mapOf(
                    TRACE_ID_PROPERTY to indexed.traceId,
                    EVENT_ID_PROPERTY to indexedEvent.eventId,
                    EVENT_NAME_PROPERTY to indexedEvent.event.conceptName,
                    EVENT_TIMESTAMP_PROPERTY to attributes.physicalEventTimestamp(indexedEvent.event),
                    EVENT_RESOURCE_PROPERTY to indexedEvent.event.orgResource,
                    EVENT_LIFECYCLE_PROPERTY to indexedEvent.event.lifecycleTransition,
                    EVENT_COST_PROPERTY to indexedEvent.event.costTotal,
                    "createdAt" to importedAt,
                    "importOrder" to indexedEvent.index,
                    "attributes" to Neo4jPropertySanitizer.sanitizeAttributes(
                        attributes.eventAttributes(indexedEvent.event),
                    ),
                )
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

    private companion object {
        const val BATCH_SIZE = 25

        fun logId(requestedLogId: String?): String =
            requestedLogId ?: "log-${UUID.randomUUID().toString().substring(0, 8)}"

        fun traceId(logId: String, trace: XesTrace, index: Int): String =
            "$logId-trace-${index + 1}-${(trace.conceptName ?: "unnamed").replace(" ", "_")}"

        fun eventId(traceId: String, index: Int): String =
            "$traceId-event-${index + 1}"

        val TRACE_ID_PROPERTY: String = Neo4jXesSchema.physicalName(Scope.TRACE, StandardAttributeCatalog.IDENTITY_ID)
        val TRACE_NAME_PROPERTY: String = Neo4jXesSchema.physicalName(Scope.TRACE, StandardAttributeCatalog.CONCEPT_NAME)
        val EVENT_ID_PROPERTY: String = Neo4jXesSchema.physicalName(Scope.EVENT, StandardAttributeCatalog.IDENTITY_ID)
        val EVENT_NAME_PROPERTY: String = Neo4jXesSchema.physicalName(Scope.EVENT, StandardAttributeCatalog.CONCEPT_NAME)
        val EVENT_TIMESTAMP_PROPERTY: String = Neo4jXesSchema.physicalName(Scope.EVENT, StandardAttributeCatalog.TIME_TIMESTAMP)
        val EVENT_RESOURCE_PROPERTY: String = Neo4jXesSchema.physicalName(Scope.EVENT, StandardAttributeCatalog.ORG_RESOURCE)
        val EVENT_LIFECYCLE_PROPERTY: String = Neo4jXesSchema.physicalName(Scope.EVENT, StandardAttributeCatalog.LIFECYCLE_TRANSITION)
        val EVENT_COST_PROPERTY: String = Neo4jXesSchema.physicalName(Scope.EVENT, StandardAttributeCatalog.COST_TOTAL)
    }
}
