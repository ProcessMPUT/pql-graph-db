package com.processm.processminterpreter.processm.hierarchical

import com.processm.processminterpreter.processm.TestDataLoader
import com.processm.processminterpreter.service.PQLQueryService
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Simple end-to-end hierarchical query test
 *
 * Verifies that PQL queries return hierarchical results (Log/Trace/Event objects)
 * compatible with ProcessM's hierarchical model
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimpleHierarchicalQueryTest {

    @Autowired
    private lateinit var pqlQueryService: PQLQueryService

    @Autowired
    private lateinit var testDataLoader: TestDataLoader

    private var journalLogId: String = ""

    @BeforeAll
    fun loadTestData() {
        journalLogId = testDataLoader.loadJournalReviewLog()
        println("Loaded JournalReview log: $journalLogId")
    }

    @Test
    fun `test simple query returns hierarchical results`() {
        // Execute a simple PQL query
        val result = pqlQueryService.executePQLQuery(
            "where l:logId='$journalLogId' limit l:1, t:2, e:5",
            journalLogId
        )

        // Verify we got results
        assertNotNull(result, "Result should not be null")
        assertTrue(result.success, "Query should succeed")

        // Verify hierarchical logs are present
        assertNotNull(result.logs, "Hierarchical logs should not be null")
        assertTrue(result.logs.isNotEmpty(), "Should have at least one log")

        // Verify log structure
        val log = result.logs.first()
        assertNotNull(log.conceptName, "Log should have a name")
        assertNotNull(log.traces, "Log should have traces")
        assertTrue(log.traces.any(), "Log should have at least one trace")

        // Verify trace structure
        val trace = log.traces.first()
        assertNotNull(trace.conceptName, "Trace should have a name")
        assertNotNull(trace.events, "Trace should have events")
        assertTrue(trace.events.any(), "Trace should have at least one event")

        // Verify event structure
        val event = trace.events.first()
        assertNotNull(event.conceptName, "Event should have a name")

        println("✓ Hierarchical query test passed")
        println("  - Log: ${log.conceptName}")
        println("  - Traces: ${log.traces.count()}")
        println("  - First trace: ${trace.conceptName}")
        println("  - Events in first trace: ${trace.events.count()}")
    }
}