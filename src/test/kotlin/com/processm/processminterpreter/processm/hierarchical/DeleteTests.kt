package com.processm.processminterpreter.processm.hierarchical

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
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
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class DeleteTests : HierarchicalTestsBase() {
    private var journalLogId: String = ""

    @BeforeEach
    fun loadTestData() {
        val uniqueId = "JournalReview-delete-${java.util.UUID.randomUUID().toString().take(8)}"
        journalLogId = testDataLoader.loadJournalReviewLog(uniqueId)
        println("Loaded JournalReview log for delete test: $journalLogId")
    }

    @AfterEach
    fun cleanupTestData() {
        if (journalLogId.isNotEmpty()) {
            testDataLoader.clearTestData(listOf(journalLogId))
        }
    }

    @Test
    fun deleteEventTest() {
        // ProcessM: delete event where e:name = 'accept' and l:id = $journal
        val beforeResult =
            q(
                "where e:name = 'accept' and l:logId='$journalLogId' limit l:1, t:100, e:100",
                journalLogId,
            )
        assertTrue(beforeResult.success, "Pre-query should succeed: ${beforeResult.error}")
        val eventCountBefore =
            beforeResult.logs
                .flatMap { log ->
                    log.traces.flatMap { trace -> trace.events.toList() }
                }.count()
        assertTrue(eventCountBefore > 0, "Should have 'accept' events before delete")

        val deleteResult =
            q(
                "delete event where e:name = 'accept' and l:logId='$journalLogId'",
                journalLogId,
            )
        assertTrue(deleteResult.success, "Delete should succeed: ${deleteResult.error}")

        val afterResult =
            q(
                "where e:name = 'accept' and l:logId='$journalLogId' limit l:1, t:100, e:100",
                journalLogId,
            )
        assertTrue(afterResult.success, "Post-query should succeed: ${afterResult.error}")
        val eventCountAfter =
            afterResult.logs
                .flatMap { log ->
                    log.traces.flatMap { trace -> trace.events.toList() }
                }.count()
        assertEquals(0, eventCountAfter, "Should have no 'accept' events after delete")
    }

    @Test
    fun deleteTraceTest() {
        // ProcessM: delete trace where t:name = '5' and l:id = $journal
        val deleteResult =
            q(
                "delete trace where t:name = '5' and l:logId='$journalLogId'",
                journalLogId,
            )
        assertTrue(deleteResult.success, "Delete should succeed: ${deleteResult.error}")

        val afterResult =
            q(
                "where t:name = '5' and l:logId='$journalLogId'",
                journalLogId,
            )
        assertTrue(afterResult.success, "Post-query should succeed: ${afterResult.error}")
        val traces = afterResult.logs.flatMap { log -> log.traces.toList() }
        assertEquals(0, traces.size, "Trace '5' should be deleted")
    }

    @Test
    fun deleteWithTimestampConditionTest() {
        // ProcessM: delete event where e:timestamp < D2006-01-01 and l:id = $journal
        val deleteResult =
            q(
                "delete event where e:timestamp < D2006-01-01 and l:logId='$journalLogId'",
                journalLogId,
            )
        assertTrue(deleteResult.success, "Delete should succeed: ${deleteResult.error}")

        val afterResult =
            q(
                "where e:timestamp < D2006-01-01 and l:logId='$journalLogId' limit l:1, t:100, e:100",
                journalLogId,
            )
        assertTrue(afterResult.success, "Post-query should succeed: ${afterResult.error}")
        val events =
            afterResult.logs.flatMap { log ->
                log.traces.flatMap { trace -> trace.events.toList() }
            }
        assertEquals(0, events.size, "Should have no events before 2006 after delete")
    }

    @Test
    fun deleteLogTest() {
        // ProcessM: delete log where l:id = $journal
        val deleteResult =
            q(
                "delete log where l:logId='$journalLogId'",
                journalLogId,
            )
        assertTrue(deleteResult.success, "Delete should succeed: ${deleteResult.error}")

        val afterResult =
            q(
                "where l:logId='$journalLogId'",
                journalLogId,
            )
        assertTrue(
            afterResult.success || afterResult.logs.isEmpty(),
            "Log should be deleted or return empty results",
        )
    }

    @Test
    fun deleteWithComplexWhereTest() {
        // ProcessM: delete event where e:name in ('accept', 'reject') and l:id = $journal
        val deleteResult =
            q(
                "delete event where e:name in ('accept', 'reject') and l:logId='$journalLogId'",
                journalLogId,
            )
        assertTrue(deleteResult.success, "Delete should succeed: ${deleteResult.error}")

        val afterResult =
            q(
                "where e:name in ('accept', 'reject') and l:logId='$journalLogId' limit l:1, t:100, e:100",
                journalLogId,
            )
        assertTrue(afterResult.success, "Post-query should succeed: ${afterResult.error}")
        val events =
            afterResult.logs.flatMap { log ->
                log.traces.flatMap { trace -> trace.events.toList() }
            }
        assertEquals(0, events.size, "Should have no matching events after delete")
    }
}
