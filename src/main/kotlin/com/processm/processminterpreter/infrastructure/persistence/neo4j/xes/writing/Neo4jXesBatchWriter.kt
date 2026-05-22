package com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.writing

import com.processm.processminterpreter.infrastructure.persistence.neo4j.repository.Neo4jLogNodeWrite
import com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.mapping.Neo4jXesImportBatch
import com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.mapping.Neo4jXesTraceBatch
import org.neo4j.driver.Driver
import org.neo4j.driver.TransactionContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class Neo4jXesBatchWriter(private val driver: Driver) {
    private val logger = LoggerFactory.getLogger(Neo4jXesBatchWriter::class.java)

    fun write(batch: Neo4jXesImportBatch) {
        writeLog(batch)
        writeTraceBatches(batch)
    }

    private fun writeLog(batch: Neo4jXesImportBatch) {
        driver.session().use { session ->
            session.executeWrite { tx ->
                writeLog(tx, batch)
            }
        }
    }

    private fun writeTraceBatches(batch: Neo4jXesImportBatch) {
        val totalBatches = batch.traceBatches.size
        var traceOffset = 0

        batch.traceBatches.forEachIndexed { batchIndex, traceBatch ->
            val startTrace = traceOffset + 1
            traceOffset += traceBatch.traces.size
            val endTrace = traceOffset
            logger.trace("Processing batch ${batchIndex + 1} / $totalBatches. Traces $startTrace to $endTrace")

            driver.session().use { session ->
                session.executeWrite { tx ->
                    writeTraces(tx, batch.logId, traceBatch)
                    writeEvents(tx, traceBatch)
                    writeFollows(tx, traceBatch)
                }
            }
        }
    }

    private fun writeLog(
        tx: TransactionContext,
        batch: Neo4jXesImportBatch,
    ) {
        tx.run(
            Neo4jLogNodeWrite.MERGE,
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
            MATCH (log:Log {logId: ${'$'}logId})
            CREATE (trace:Trace {
                traceId: traceProps.traceId,
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
