package com.processm.processminterpreter.processm.hierarchical

import com.processm.processminterpreter.processm.TestDataLoader
import com.processm.processminterpreter.service.PQLQueryService
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.neo4j.driver.Driver
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for hierarchical LIMIT functionality
 *
 * Based on ProcessM behavior where LIMIT is applied at each hierarchical level:
 * - limit l:2, t:3, e:5 means:
 *   - Maximum 2 logs
 *   - Maximum 3 traces PER LOG (not total)
 *   - Maximum 5 events PER TRACE (not total)
 *
 * This is fundamentally different from applying LIMIT in Cypher which limits total rows.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HierarchicalLimitTest {

    @Autowired
    private lateinit var pqlQueryService: PQLQueryService

    @Autowired
    private lateinit var testDataLoader: TestDataLoader

    @Autowired
    private lateinit var neo4jDriver: Driver

    private var journalLogId: String = ""

    @BeforeAll
    fun loadTestData() {
        // Clear Neo4j database before loading test data to prevent memory issues from accumulated data
        println("Clearing Neo4j database...")
        neo4jDriver.session().use { session ->
            session.run("MATCH (n) DETACH DELETE n").consume()
        }
        println("Database cleared")

        // Load test data
        journalLogId = testDataLoader.loadJournalReviewLog()
        println("Loaded JournalReview log: $journalLogId")
    }

    @Test
    fun `test limit event count per trace`() {
        // Query: limit 5 events per trace
        val result = pqlQueryService.executePQLQuery(
            "where l:logId='$journalLogId' limit l:1, t:3, e:5",
            journalLogId
        )

        assertNotNull(result, "Result should not be null")
        assertTrue(result.success, "Query should succeed")
        assertNotNull(result.logs, "Should have logs")
        assertEquals(1, result.logs.size, "Should have exactly 1 log (limit l:1)")

        val log = result.logs.first()
        val traces = log.traces.toList()

        // CRITICAL: Should have UP TO 3 traces (limit t:3)
        assertTrue(traces.size <= 3, "Should have at most 3 traces per log, got ${traces.size}")
        assertTrue(traces.isNotEmpty(), "Should have at least 1 trace")

        // CRITICAL: Each trace should have UP TO 5 events (limit e:5)
        traces.forEach { trace ->
            val events = trace.events.toList()
            assertTrue(
                events.size <= 5,
                "Each trace should have at most 5 events, trace ${trace.conceptName} has ${events.size}"
            )
        }

        println("✓ Limit per trace test:")
        println("  - Logs: ${result.logs.size} (expected: 1)")
        println("  - Traces: ${traces.size} (expected: ≤3)")
        traces.forEachIndexed { idx, trace ->
            val eventCount = trace.events.count()
            println("    Trace $idx (${trace.conceptName}): $eventCount events (expected: ≤5)")
        }
    }

    @Test
    fun `test limit trace count per log`() {
        // Query: limit 2 traces per log
        val result = pqlQueryService.executePQLQuery(
            "where l:logId='$journalLogId' limit l:1, t:2, e:100",
            journalLogId
        )

        assertNotNull(result, "Result should not be null")
        assertTrue(result.success, "Query should succeed")
        assertEquals(1, result.logs.size, "Should have exactly 1 log")

        val log = result.logs.first()
        val traces = log.traces.toList()

        // CRITICAL: Should have EXACTLY 2 traces (limit t:2)
        assertEquals(2, traces.size, "Should have exactly 2 traces per log (limit t:2)")

        println("✓ Limit trace count test:")
        println("  - Logs: ${result.logs.size}")
        println("  - Traces: ${traces.size} (expected: 2)")
    }

    @Test
    fun `test limit with multiple traces - each gets event limit`() {
        // Query: Get 3 traces, each with max 4 events
        val result = pqlQueryService.executePQLQuery(
            "where l:logId='$journalLogId' limit l:1, t:3, e:4",
            journalLogId
        )

        assertNotNull(result, "Result should not be null")
        assertTrue(result.success, "Query should succeed")

        val log = result.logs.first()
        val traces = log.traces.toList()

        assertTrue(traces.size <= 3, "Should have at most 3 traces, got ${traces.size}")

        // CRITICAL: EACH trace should independently have up to 4 events
        // This is the key difference from Cypher LIMIT which would limit total rows
        traces.forEach { trace ->
            val events = trace.events.toList()
            assertTrue(
                events.size <= 4,
                "Trace ${trace.conceptName} should have at most 4 events, has ${events.size}"
            )
        }

        // If we have 3 traces with 4 events each, total should be UP TO 12 events
        val totalEvents = traces.sumOf { it.events.count() }
        assertTrue(
            totalEvents <= 12,
            "With 3 traces × 4 events = max 12 total events, got $totalEvents"
        )

        println("✓ Multiple traces with event limits:")
        println("  - Traces: ${traces.size}")
        println("  - Total events: $totalEvents (max expected: 12)")
        traces.forEachIndexed { idx, trace ->
            println("    Trace $idx: ${trace.events.count()} events (max: 4)")
        }
    }

    @Test
    fun `test limit applies independently per trace not globally`() {
        // This test verifies the key behavioral difference:
        // ProcessM: limit e:3 means "3 events PER TRACE"
        // Wrong impl: limit e:3 means "3 events TOTAL across all traces"

        val result = pqlQueryService.executePQLQuery(
            "where l:logId='$journalLogId' limit l:1, t:2, e:3",
            journalLogId
        )

        assertNotNull(result, "Result should not be null")
        assertTrue(result.success, "Query should succeed")

        val log = result.logs.first()
        val traces = log.traces.toList()

        assertEquals(2, traces.size, "Should have 2 traces")

        val trace0Events = traces[0].events.count()
        val trace1Events = traces[1].events.count()

        // CRITICAL TEST: Both traces should have events (up to 3 each)
        // If LIMIT was applied globally in Cypher, we'd get max 3 events TOTAL
        // But with hierarchical limit, we should get up to 3 events PER TRACE = 6 total

        assertTrue(trace0Events > 0, "First trace should have events")
        assertTrue(trace1Events > 0, "Second trace should have events")
        assertTrue(trace0Events <= 3, "First trace should have at most 3 events")
        assertTrue(trace1Events <= 3, "Second trace should have at most 3 events")

        val totalEvents = trace0Events + trace1Events

        // This is the SMOKING GUN test:
        // If total is 3 or less, LIMIT was applied globally (WRONG)
        // If total is 4-6, LIMIT was applied per trace (CORRECT)
        assertTrue(
            totalEvents > 3,
            "CRITICAL: Total events should be >3 (got $totalEvents). " +
                "If ≤3, LIMIT is being applied globally in Cypher instead of per-trace! " +
                "Trace 0: $trace0Events events, Trace 1: $trace1Events events"
        )

        println("✓ Independent limit per trace test:")
        println("  - Trace 0: $trace0Events events")
        println("  - Trace 1: $trace1Events events")
        println("  - Total: $totalEvents events (should be >3 to prove per-trace limiting)")
    }

    @Test
    fun `test limit with zero should return no results at that level`() {
        // limit e:0 should mean "no events"
        val result = pqlQueryService.executePQLQuery(
            "where l:logId='$journalLogId' limit l:1, t:2, e:0",
            journalLogId
        )

        assertNotNull(result, "Result should not be null")
        assertTrue(result.success, "Query should succeed")

        val log = result.logs.first()
        val traces = log.traces.toList()

        assertEquals(2, traces.size, "Should have 2 traces")

        traces.forEach { trace ->
            val events = trace.events.toList()
            assertEquals(
                0,
                events.size,
                "Trace ${trace.conceptName} should have 0 events with limit e:0"
            )
        }

        println("✓ Zero limit test passed")
    }

    @Test
    fun `test limit with only event scope specified`() {
        // Query: "limit e:5" - should limit only events, not traces or logs
        val result = pqlQueryService.executePQLQuery(
            "where l:logId='$journalLogId' limit e:5",
            journalLogId
        )

        assertNotNull(result, "Result should not be null")
        assertTrue(result.success, "Query should succeed")
        assertTrue(result.logs.isNotEmpty(), "Should have logs")

        // Should have all traces from the log (no trace limit)
        val log = result.logs.first()
        val traces = log.traces.toList()

        // Each trace should have max 5 events
        traces.forEach { trace ->
            val events = trace.events.toList()
            assertTrue(
                events.size <= 5,
                "Trace ${trace.conceptName} should have at most 5 events, has ${events.size}"
            )
        }

        println("✓ Event-only limit test:")
        println("  - Logs: ${result.logs.size}")
        println("  - Traces: ${traces.size} (no limit specified)")
        println("  - Events per trace: max 5")
    }
}