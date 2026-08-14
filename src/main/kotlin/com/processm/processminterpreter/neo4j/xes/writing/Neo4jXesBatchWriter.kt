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
            logger.trace("Processing batch ${batchIndex + 1} of ${batch.traceCount} traces. Traces $startTrace to $endTrace")

            driver.session().use { session ->
                session.executeWrite { tx ->
                    writeTraces(tx, batch.logId, traceBatch)
                    writeEvents(tx, traceBatch)
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

    private fun writeTraces(
        tx: TransactionContext,
        logId: String,
        traceBatch: Neo4jXesTraceBatch,
    ) {
        if (traceBatch.traces.isEmpty()) return

        tx.run(
            CREATE_TRACES,
            mapOf("logId" to logId, "traces" to traceBatch.traces),
        ).consume()
    }

    private fun writeEvents(
        tx: TransactionContext,
        traceBatch: Neo4jXesTraceBatch,
    ) {
        if (traceBatch.events.isEmpty()) return

        tx.run(CREATE_EVENTS, mapOf("events" to traceBatch.events)).consume()
    }

    private fun writeFollows(
        tx: TransactionContext,
        traceBatch: Neo4jXesTraceBatch,
    ) {
        if (traceBatch.follows.isEmpty()) return

        tx.run(CREATE_FOLLOWS, mapOf("follows" to traceBatch.follows)).consume()
    }

    private companion object {
        val CREATE_TRACES =
            """
            UNWIND ${'$'}traces as traceProps
            MATCH (log:ImportingLog {logId: ${'$'}logId})
            CREATE (trace:Trace {
                traceId: traceProps.traceId,
                parentLogId: ${'$'}logId,
                caseId: traceProps.caseId,
                createdAt: traceProps.createdAt,
                importOrder: traceProps.importOrder
            })
            SET trace += traceProps.attributes
            CREATE (log)-[:CONTAINS]->(trace)
            """.trimIndent()

        val CREATE_EVENTS =
            """
            UNWIND ${'$'}events as eventProps
            MATCH (trace:Trace {traceId: eventProps.traceId})
            CREATE (event:Event {
                eventId: eventProps.eventId,
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
