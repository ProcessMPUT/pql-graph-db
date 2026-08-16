package com.processm.processminterpreter.neo4j.xes.writing

import com.processm.processminterpreter.neo4j.xes.mapping.Neo4jXesImportBatch
import com.processm.processminterpreter.neo4j.xes.mapping.Neo4jXesTraceBatch
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesSchemaInitializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.exceptions.Neo4jException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.neo4j.Neo4jContainer
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jXesBatchWriterTest {
    companion object {
        const val LOG_ID = "batch-writer-test"

        @Container
        val neo4jContainer =
            Neo4jContainer("neo4j:5.26.25-community-ubi10")
                .withAdminPassword("password")
    }

    private lateinit var driver: Driver
    private lateinit var writer: Neo4jXesBatchWriter

    @BeforeAll
    fun setUp() {
        driver = GraphDatabase.driver(
            neo4jContainer.boltUrl,
            AuthTokens.basic("neo4j", "password"),
        )
        Neo4jXesSchemaInitializer(driver).ensureIndexes()
        writer = Neo4jXesBatchWriter(driver)
    }

    @AfterEach
    fun clearDatabase() {
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run("MATCH (n) DETACH DELETE n").consume()
            }
        }
    }

    @AfterAll
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `writes log trace and events without unused event order relationships by default`() {
        writer.write(minimalBatch())

        driver.session().use { session ->
            val graph = session.run(
                """
                MATCH (log:Log {logId: ${'$'}logId})
                OPTIONAL MATCH (log)-[:CONTAINS]->(trace:Trace)
                OPTIONAL MATCH (trace)-[:HAS_EVENT]->(event:Event)
                RETURN log.name AS logName,
                       trace.parentLogId AS parentLogId,
                       min(event.parentTraceId) AS eventParentTraceId,
                       count(DISTINCT trace) AS traces,
                       count(DISTINCT event) AS events
                """.trimIndent(),
                mapOf("logId" to LOG_ID),
            ).single()

            assertEquals("Audit", graph["logName"].asString())
            assertEquals(LOG_ID, graph["parentLogId"].asString())
            assertEquals("trace-1", graph["eventParentTraceId"].asString())
            assertEquals(1, graph["traces"].asLong())
            assertEquals(2, graph["events"].asLong())

            val follows = session.run(
                """
                MATCH (:Event {eventId: ${'$'}fromEventId})-[follow:FOLLOWS]->(:Event {eventId: ${'$'}toEventId})
                RETURN count(follow) AS follows
                """.trimIndent(),
                mapOf(
                    "fromEventId" to "trace-1-event-1",
                    "toEventId" to "trace-1-event-2",
                ),
            ).single()

            assertEquals(0, follows["follows"].asLong())
        }
    }

    @Test
    fun `writes event order relationships when explicitly enabled`() {
        Neo4jXesBatchWriter(driver, persistFollows = true).write(minimalBatch())

        driver.session().use { session ->
            val follows = session.run(
                "MATCH (:Event)-[follow:FOLLOWS]->(:Event) RETURN count(follow) AS follows",
            ).single()["follows"].asLong()

            assertEquals(1, follows)
        }
    }

    @Test
    fun `writes traces that contain no events`() {
        val batch = minimalBatch()
        val traceOnly = batch.traceBatches.single().copy(events = emptyList(), follows = emptyList())

        writer.write(batch.copy(eventCount = 0, traceBatches = sequenceOf(traceOnly)))

        driver.session().use { session ->
            val counts = session.run(
                "MATCH (:Log {logId: ${'$'}logId})-[:CONTAINS]->(trace:Trace)" +
                    " OPTIONAL MATCH (trace)-[:HAS_EVENT]->(event:Event)" +
                    " RETURN count(DISTINCT trace) AS traces, count(DISTINCT event) AS events",
                mapOf("logId" to LOG_ID),
            ).single()
            assertEquals(1, counts["traces"].asLong())
            assertEquals(0, counts["events"].asLong())
        }
    }

    @Test
    fun `rejects reimport of the same batch`() {
        val batch = minimalBatch()

        writer.write(batch)

        val error = assertFailsWith<Neo4jException> {
            writer.write(batch)
        }

        assertEquals("Neo.ClientError.Schema.ConstraintValidationFailed", error.code())
    }

    @Test
    fun `failed later batch leaves no visible or orphaned data and can be retried`() {
        val valid = minimalBatch()
        val first = valid.traceBatches.single()
        val importedAt = valid.importedAt
        val failingSecond = Neo4jXesTraceBatch(
            traces = listOf(
                mapOf(
                    "traceId" to "trace-2",
                    "caseId" to "Case 2",
                    "createdAt" to importedAt,
                    "importOrder" to 1,
                    "attributes" to emptyMap<String, Any?>(),
                ),
            ),
            events = listOf(eventRow("trace-1-event-1", "duplicate", importedAt, importOrder = 0)),
            follows = emptyList(),
        )
        val failing = valid.copy(
            traceCount = 2,
            eventCount = 3,
            traceBatches = sequenceOf(first, failingSecond),
        )

        assertFailsWith<Neo4jException> { writer.write(failing) }

        driver.session().use { session ->
            val counts = session.run(
                "MATCH (n) RETURN count(n) AS nodes",
            ).single()
            assertEquals(0, counts["nodes"].asInt())
        }

        writer.write(minimalBatch())
        driver.session().use { session ->
            val logCount = session.run(
                "MATCH (:Log {logId: ${'$'}logId}) RETURN count(*) AS logs",
                mapOf("logId" to LOG_ID),
            ).single()["logs"].asInt()
            assertEquals(1, logCount)
        }
    }

    private fun minimalBatch(): Neo4jXesImportBatch {
        val importedAt = LocalDateTime.parse("2026-04-24T09:00:00")
        return Neo4jXesImportBatch(
            logId = LOG_ID,
            logName = "Audit",
            importedAt = importedAt,
            logAttributes = mapOf("concept:name" to "Audit"),
            classifiers = null,
            traceGlobals = null,
            eventGlobals = null,
            extensions = null,
            traceCount = 1,
            eventCount = 2,
            traceBatches = sequenceOf(
                Neo4jXesTraceBatch(
                    traces = listOf(
                        mapOf(
                            "traceId" to "trace-1",
                            "caseId" to "Case 1",
                            "createdAt" to importedAt,
                            "importOrder" to 0,
                            "attributes" to mapOf("concept:name" to "Case 1"),
                        ),
                    ),
                    events = listOf(
                        eventRow("trace-1-event-1", "A", importedAt, importOrder = 0),
                        eventRow("trace-1-event-2", "B", importedAt.plusMinutes(1), importOrder = 1),
                    ),
                    follows = listOf(
                        mapOf(
                            "fromEventId" to "trace-1-event-1",
                            "toEventId" to "trace-1-event-2",
                        ),
                    ),
                ),
            ),
        )
    }

    private fun eventRow(
        eventId: String,
        activity: String,
        timestamp: LocalDateTime,
        importOrder: Int,
    ): Map<String, Any?> =
        mapOf(
            "traceId" to "trace-1",
            "eventId" to eventId,
            "activity" to activity,
            "timestamp" to timestamp,
            "resource" to null,
            "lifecycle" to null,
            "cost" to null,
            "createdAt" to timestamp,
            "importOrder" to importOrder,
            "attributes" to mapOf("concept:name" to activity),
        )

}
