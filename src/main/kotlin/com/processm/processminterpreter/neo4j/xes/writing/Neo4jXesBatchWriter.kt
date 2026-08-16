package com.processm.processminterpreter.neo4j.xes.writing

import com.processm.processminterpreter.neo4j.repository.Neo4jLogNodeWrite
import com.processm.processminterpreter.neo4j.repository.deleteLogSubtreesBatched
import com.processm.processminterpreter.neo4j.xes.mapping.Neo4jXesImportBatch
import com.processm.processminterpreter.neo4j.xes.mapping.Neo4jXesTraceBatch
import org.neo4j.driver.Driver
import org.neo4j.driver.TransactionContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class Neo4jXesBatchWriter(
    private val driver: Driver,
    @param:Value("\${processm.neo4j.persist-follows:false}")
    private val persistFollows: Boolean = false,
) {
    private val logger = LoggerFactory.getLogger(Neo4jXesBatchWriter::class.java)

    fun write(batch: Neo4jXesImportBatch) {
        try {
            writeLog(batch)
            writeTraceBatches(batch)
            publishLog(batch.logId)
        } catch (failure: Exception) {
            runCatching { discardImport(batch.logId) }
                .onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun writeLog(batch: Neo4jXesImportBatch) {
        driver.session().use { session ->
            session.executeWrite { tx ->
                writeLog(tx, batch)
            }
        }
    }

    private fun writeTraceBatches(batch: Neo4jXesImportBatch) {
        var traceOffset = 0

        // Sequence, not list: counting batches up-front would materialize every
        // row map before the first write — the laziness is the point.
        batch.traceBatches.forEachIndexed { batchIndex, traceBatch ->
            val startTrace = traceOffset + 1
            traceOffset += traceBatch.traces.size
            val endTrace = traceOffset
            logger.trace(
                "Processing batch {} of {} traces. Traces {} to {}",
                batchIndex + 1,
                batch.traceCount,
                startTrace,
                endTrace,
            )

            driver.session().use { session ->
                session.executeWrite { tx ->
                    writeTraceHierarchy(tx, batch.logId, traceBatch)
                    if (persistFollows) writeFollows(tx, traceBatch)
                }
            }
        }
    }

    private fun writeLog(
        tx: TransactionContext,
        batch: Neo4jXesImportBatch,
    ) {
        tx.run(
            Neo4jLogNodeWrite.CREATE_IMPORT,
            Neo4jLogNodeWrite.parameters(
                logId = batch.logId,
                name = batch.logName,
                createdAt = batch.importedAt,
                updatedAt = batch.importedAt,
                classifiers = batch.classifiers,
                extensions = batch.extensions,
                traceGlobals = batch.traceGlobals,
                eventGlobals = batch.eventGlobals,
                attributes = batch.logAttributes,
            ),
        ).consume()
    }

    private fun publishLog(logId: String) {
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run(
                    "MATCH (log:ImportingLog {logId: ${'$'}logId}) SET log:Log REMOVE log:ImportingLog",
                    mapOf("logId" to logId),
                ).consume()
            }
        }
    }

    private fun discardImport(logId: String) {
        driver.session().use { session ->
            val params = mapOf<String, Any?>("logId" to logId)
            deleteLogSubtreesBatched(session, "MATCH (log:ImportingLog {logId: ${'$'}logId})", params)
            session.run(
                "MATCH (log:ImportingLog {logId: ${'$'}logId}) DETACH DELETE log",
                params,
            ).consume()
        }
    }

    /**
     * Creates a whole trace batch in one driver round-trip. Events are grouped
     * by trace before sending the parameters, so Neo4j performs one indexed
     * trace lookup per trace rather than one lookup per event.
     */
    private fun writeTraceHierarchy(
        tx: TransactionContext,
        logId: String,
        traceBatch: Neo4jXesTraceBatch,
    ) {
        if (traceBatch.traces.isEmpty() && traceBatch.events.isEmpty()) return

        tx.run(
            CREATE_TRACE_HIERARCHY,
            mapOf(
                "logId" to logId,
                "traces" to traceBatch.traces,
                "eventGroups" to traceBatch.events.groupByTrace(),
            ),
        ).consume()
    }

    private fun List<Map<String, Any?>>.groupByTrace(): List<Map<String, Any?>> {
        val eventsByTrace = linkedMapOf<Any?, MutableList<Map<String, Any?>>>()
        for (event in this) {
            eventsByTrace.getOrPut(event["traceId"], ::mutableListOf).add(event)
        }
        return eventsByTrace.map { (traceId, events) ->
            mapOf("traceId" to traceId, "events" to events)
        }
    }

    private fun writeFollows(
        tx: TransactionContext,
        traceBatch: Neo4jXesTraceBatch,
    ) {
        if (traceBatch.follows.isEmpty()) return

        tx.run(CREATE_FOLLOWS, mapOf("follows" to traceBatch.follows)).consume()
    }

    private companion object {
        val CREATE_TRACE_HIERARCHY =
            """
            MATCH (log:ImportingLog {logId: ${'$'}logId})
            CALL (log) {
                UNWIND ${'$'}traces AS traceProps
                CREATE (trace:Trace {
                    traceId: traceProps.traceId,
                    parentLogId: ${'$'}logId,
                    caseId: traceProps.caseId,
                    createdAt: traceProps.createdAt,
                    importOrder: traceProps.importOrder
                })
                SET trace += traceProps.attributes
                CREATE (log)-[:CONTAINS]->(trace)
                RETURN count(trace) AS _createdTraces
            }
            WITH _createdTraces
            UNWIND ${'$'}eventGroups AS eventGroup
            MATCH (trace:Trace {traceId: eventGroup.traceId})
            UNWIND eventGroup.events AS eventProps
            CREATE (event:Event {
                eventId: eventProps.eventId,
                parentTraceId: eventProps.traceId,
                activity: eventProps.activity,
                timestamp: eventProps.timestamp,
                resource: eventProps.resource,
                lifecycle: eventProps.lifecycle,
                cost: eventProps.cost,
                createdAt: eventProps.createdAt,
                importOrder: eventProps.importOrder
            })
            SET event += eventProps.attributes
            CREATE (trace)-[:HAS_EVENT]->(event)
            RETURN _createdTraces, count(event) AS _createdEvents
            """.trimIndent()

        val CREATE_FOLLOWS =
            """
            UNWIND ${'$'}follows as followPair
            MATCH (from:Event {eventId: followPair.fromEventId})
            MATCH (to:Event {eventId: followPair.toEventId})
            CREATE (from)-[:FOLLOWS]->(to)
            """.trimIndent()
    }
}
