package com.processm.processminterpreter.processm.hierarchical

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.*

/**
 * PQL DELETE tests adapted from ProcessM DBXESDeleterTests.kt
 *
 * Tests based on: DBXESDeleterTests.kt
 * Original: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/log/DBXESDeleterTests.kt
 *
 * ProcessM's DELETE query removes matching components from the database.
 * Our implementation has parser/visitor support for DELETE but execution via
 * PQLQueryService is not yet implemented for Neo4j.
 *
 * All tests are @Disabled as TDD specs until DELETE execution is implemented.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeleteTests : HierarchicalTestsBase() {

    private var journalLogId: String = ""

    @BeforeAll
    fun loadTestData() {
        journalLogId = loadTestDataWithUniqueId()
        println("Loaded JournalReview log for delete tests: $journalLogId")
    }

    // =====================
    // DELETE event tests (from ProcessM DBXESDeleterTests)
    // =====================

    @Test
    @Disabled("DELETE execution not yet implemented in PQLQueryService - TDD spec from ProcessM")
    fun deleteEventTest() {
        // ProcessM: delete e:* where e:name = 'accept' and l:id = $journal
        // Deletes all events named 'accept' from the journal log
        //
        // ProcessM behavior: after delete, querying for 'accept' events returns 0
        // The traces remain but without those events.

        // First count events before delete
        val beforeResult = q(
            "where e:name = 'accept' and l:logId='$journalLogId' limit l:1, t:100, e:100",
            journalLogId
        )
        assertTrue(beforeResult.success, "Pre-query should succeed: ${beforeResult.error}")
        val eventCountBefore = beforeResult.logs.flatMap { log ->
            log.traces.flatMap { trace -> trace.events.toList() }
        }.count()
        assertTrue(eventCountBefore > 0, "Should have 'accept' events before delete")

        // Execute delete
        val deleteResult = q(
            "delete e:* where e:name = 'accept' and l:logId='$journalLogId'",
            journalLogId
        )
        assertTrue(deleteResult.success, "Delete should succeed: ${deleteResult.error}")

        // Verify events are deleted
        val afterResult = q(
            "where e:name = 'accept' and l:logId='$journalLogId' limit l:1, t:100, e:100",
            journalLogId
        )
        assertTrue(afterResult.success, "Post-query should succeed: ${afterResult.error}")
        val eventCountAfter = afterResult.logs.flatMap { log ->
            log.traces.flatMap { trace -> trace.events.toList() }
        }.count()
        assertEquals(0, eventCountAfter, "Should have no 'accept' events after delete")
    }

    @Test
    @Disabled("DELETE execution not yet implemented in PQLQueryService - TDD spec from ProcessM")
    fun deleteTraceTest() {
        // ProcessM: delete t:* where t:name = 'Case 5' and l:id = $journal
        // Deletes entire trace (case) named 'Case 5' and all its events

        val deleteResult = q(
            "delete t:* where t:name = 'Case 5' and l:logId='$journalLogId'",
            journalLogId
        )
        assertTrue(deleteResult.success, "Delete should succeed: ${deleteResult.error}")

        // Verify trace is deleted
        val afterResult = q(
            "where t:name = 'Case 5' and l:logId='$journalLogId'",
            journalLogId
        )
        assertTrue(afterResult.success, "Post-query should succeed: ${afterResult.error}")
        val traces = afterResult.logs.flatMap { log -> log.traces.toList() }
        assertEquals(0, traces.size, "Trace 'Case 5' should be deleted")
    }

    @Test
    @Disabled("DELETE execution not yet implemented in PQLQueryService - TDD spec from ProcessM")
    fun deleteWithTimestampConditionTest() {
        // ProcessM: delete e:* where e:timestamp < D2006-01-01 and l:id = $journal
        // Deletes events before a specific date

        val deleteResult = q(
            "delete e:* where e:timestamp < D2006-01-01 and l:logId='$journalLogId'",
            journalLogId
        )
        assertTrue(deleteResult.success, "Delete should succeed: ${deleteResult.error}")

        // Verify no events remain before 2006
        val afterResult = q(
            "where e:timestamp < D2006-01-01 and l:logId='$journalLogId' limit l:1, t:100, e:100",
            journalLogId
        )
        assertTrue(afterResult.success, "Post-query should succeed: ${afterResult.error}")
        val events = afterResult.logs.flatMap { log ->
            log.traces.flatMap { trace -> trace.events.toList() }
        }
        assertEquals(0, events.size, "Should have no events before 2006 after delete")
    }

    @Test
    @Disabled("DELETE execution not yet implemented in PQLQueryService - TDD spec from ProcessM")
    fun deleteLogTest() {
        // ProcessM: delete l:* where l:id = $journal
        // Deletes entire log and all its traces and events

        val deleteResult = q(
            "delete l:* where l:logId='$journalLogId'",
            journalLogId
        )
        assertTrue(deleteResult.success, "Delete should succeed: ${deleteResult.error}")

        // Verify log is deleted
        val afterResult = q(
            "where l:logId='$journalLogId'",
            journalLogId
        )
        assertTrue(afterResult.success || afterResult.logs.isEmpty(),
            "Log should be deleted or return empty results")
    }

    @Test
    @Disabled("DELETE execution not yet implemented in PQLQueryService - TDD spec from ProcessM")
    fun deleteWithComplexWhereTest() {
        // ProcessM: delete e:* where e:name in ('accept', 'reject') and t:name like 'Case%' and l:id = $journal
        // Complex WHERE condition with IN and LIKE

        val deleteResult = q(
            "delete e:* where e:name in ('accept', 'reject') and t:name like 'Case%' and l:logId='$journalLogId'",
            journalLogId
        )
        assertTrue(deleteResult.success, "Delete should succeed: ${deleteResult.error}")

        // Verify matching events are deleted
        val afterResult = q(
            "where e:name in ('accept', 'reject') and t:name like 'Case%' and l:logId='$journalLogId' limit l:1, t:100, e:100",
            journalLogId
        )
        assertTrue(afterResult.success, "Post-query should succeed: ${afterResult.error}")
        val events = afterResult.logs.flatMap { log ->
            log.traces.flatMap { trace -> trace.events.toList() }
        }
        assertEquals(0, events.size, "Should have no matching events after delete")
    }
}
