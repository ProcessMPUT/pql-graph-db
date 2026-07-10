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
    fun `writes log trace events and event order relationships`() {
        writer.write(minimalBatch())

        driver.session().use { session ->
            val graph = session.run(
                """
                MATCH (log:Log {logId: ${'$'}logId})
                OPTIONAL MATCH (log)-[:CONTAINS]->(trace:Trace)
                OPTIONAL MATCH (trace)-[:HAS_EVENT]->(event:Event)
                RETURN log.name AS logName,
                       count(DISTINCT trace) AS traces,
                       count(DISTINCT event) AS events
                """.trimIndent(),
                mapOf("logId" to LOG_ID),
            ).single()

            assertEquals("Audit", graph["logName"].asString())
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

            assertEquals(1, follows["follows"].asLong())
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
