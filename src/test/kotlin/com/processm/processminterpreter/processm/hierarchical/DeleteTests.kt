package com.processm.processminterpreter.processm.hierarchical

import com.processm.processminterpreter.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PQL DELETE tests adapted from ProcessM DBXESDeleterTests.kt
 *
 * Tests based on: DBXESDeleterTests.kt
 * Original: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/log/DBXESDeleterTests.kt
 *
 * Each test loads its own copy of test data to avoid destructive interference.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class DeleteTests : HierarchicalTestsBase() {
    private var baseLogId: String = ""
    private var copyLogId: String = ""

    @BeforeEach
    fun loadTestData() {
        baseLogId = testDataLoader.loadJournalReviewLog("JournalReview-delete-base-${UUID.randomUUID().toString().take(8)}")
        copyLogId = testDataLoader.loadJournalReviewLog("JournalReview-delete-${UUID.randomUUID().toString().take(8)}")
        assertJournalReviewCopiesLoaded()
    }

    @AfterEach
    fun cleanupTestData() {
        listOf(baseLogId, copyLogId)
            .filter { it.isNotBlank() }
            .forEach(::removeGraphForLogId)
    }

    @Test
    fun `delete a copy of JournalReview_extra`() {
        delete("delete log where l:logId='$copyLogId'")

        assertEquals(GraphCounts(), graphCounts(copyLogId))
        assertEquals(GraphCounts(logs = 1, traces = 101, events = 2298), graphCounts(baseLogId))
        assertEquals(1, countLoadedTestLogs())
    }

    @Test
    fun `delete all traces from JournalReview_extra but not a log itself`() {
        delete("delete trace where l:logId='$copyLogId'")

        assertEquals(GraphCounts(logs = 1, traces = 0, events = 0), graphCounts(copyLogId))
        assertEquals(GraphCounts(logs = 1, traces = 101, events = 2298), graphCounts(baseLogId))
        assertEquals(2, countLoadedTestLogs())
    }

    @Test
    fun `delete from JournalReview_extra the traces with cost_total unset`() {
        delete("delete trace where l:logId='$copyLogId' and t:total is null")

        val remaining = q("where l:logId='$copyLogId'")
        assertTrue(remaining.success, "Remaining query should succeed: ${remaining.error}")
        val traces = remaining.first().traces
        assertEquals(50, traces.size)
        traces.forEach { trace -> assertNotNull(trace.costTotal) }
        assertEquals(GraphCounts(logs = 1, traces = 101, events = 2298), graphCounts(baseLogId))
    }

    @Test
    fun `delete all events from JournalReview_extra but not the traces and the log themselves`() {
        delete("delete event where l:logId='$copyLogId'")

        assertEquals(GraphCounts(logs = 1, traces = 101, events = 0), graphCounts(copyLogId))
        assertEquals(GraphCounts(logs = 1, traces = 101, events = 2298), graphCounts(baseLogId))
        assertEquals(2, countLoadedTestLogs())
    }

    @Test
    fun `delete from JournalReview_extra the additional review events`() {
        delete(
            "delete event where l:logId='$copyLogId' " +
                "and e:name in ('invite additional reviewer', 'get review X', 'time-out X')",
        )

        val remaining = q("where l:logId='$copyLogId'")
        assertTrue(remaining.success, "Remaining query should succeed: ${remaining.error}")
        assertEquals(101, remaining.first().traces.size)
        for (trace in remaining.first().traces) {
            assertEquals("invite reviewers", trace.events.first().conceptName)
            if (trace.conceptName != "-1") {
                assertTrue(trace.events.last().conceptName.let { it == "accept" || it == "reject" })
            }
            val eventNames = trace.events.map { it.conceptName }
            assertTrue("invite additional reviewer" !in eventNames)
            assertTrue("get review X" !in eventNames)
            assertTrue("time-out X" !in eventNames)
        }
        assertEquals(GraphCounts(logs = 1, traces = 101, events = 2298), graphCounts(baseLogId))
        assertNotEquals(2298, graphCounts(copyLogId).events)
    }

    private fun assertJournalReviewCopiesLoaded() {
        assertEquals(GraphCounts(logs = 1, traces = 101, events = 2298), graphCounts(baseLogId))
        assertEquals(GraphCounts(logs = 1, traces = 101, events = 2298), graphCounts(copyLogId))
        assertEquals(2, countLoadedTestLogs())
    }

    private fun delete(query: String) {
        val result = q(query)
        assertTrue(result.success, "Delete should succeed: ${result.error}")
        assertTrue(result.resultCount > 0, "Delete should remove at least one node")
    }

    private fun countLoadedTestLogs(): Int =
        neo4jDriver.session().use { session ->
            session
                .run(
                    "MATCH (log:Log) WHERE log.logId IN ${'$'}logIds RETURN count(log) AS logs",
                    mapOf("logIds" to listOf(baseLogId, copyLogId)),
                ).single()["logs"]
                .asInt()
        }

    private fun graphCounts(logId: String): GraphCounts =
        neo4jDriver.session().use { session ->
            val record =
                session.run(
                    """
                    MATCH (log:Log {logId: ${'$'}logId})
                    WITH count(log) AS logs
                    OPTIONAL MATCH (trace:Trace)
                    WHERE trace.traceId STARTS WITH ${'$'}tracePrefix
                    WITH logs, count(trace) AS traces
                    OPTIONAL MATCH (event:Event)
                    WHERE event.eventId STARTS WITH ${'$'}tracePrefix
                    RETURN logs, traces, count(event) AS events
                    """.trimIndent(),
                    mapOf("logId" to logId, "tracePrefix" to "$logId-trace-"),
                ).single()
            GraphCounts(
                logs = record["logs"].asInt(),
                traces = record["traces"].asInt(),
                events = record["events"].asInt(),
            )
        }

    private fun removeGraphForLogId(logId: String) {
        neo4jDriver.session().use { session ->
            session.run(
                """
                MATCH (log:Log {logId: ${'$'}logId})
                OPTIONAL MATCH (log)-[:CONTAINS]->(trace:Trace)
                OPTIONAL MATCH (trace)-[:HAS_EVENT]->(event:Event)
                WITH collect(DISTINCT log) AS logs, collect(DISTINCT trace) AS traces, collect(DISTINCT event) AS events
                FOREACH (e IN events | DETACH DELETE e)
                FOREACH (t IN traces | DETACH DELETE t)
                FOREACH (l IN logs | DETACH DELETE l)
                """.trimIndent(),
                mapOf("logId" to logId),
            ).consume()
            session.run(
                """
                MATCH (n)
                WHERE (n:Trace AND n.traceId STARTS WITH ${'$'}tracePrefix)
                   OR (n:Event AND n.eventId STARTS WITH ${'$'}tracePrefix)
                DETACH DELETE n
                """.trimIndent(),
                mapOf("tracePrefix" to "$logId-trace-"),
            ).consume()
        }
    }

    private data class GraphCounts(
        val logs: Int = 0,
        val traces: Int = 0,
        val events: Int = 0,
    )
}
