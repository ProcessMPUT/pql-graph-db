package com.processm.processminterpreter.processm.hierarchical

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import kotlin.test.*

/**
 * WHERE query tests ported from ProcessM
 *
 * Tests based on: DBHierarchicalXESInputStreamWithWhereQueryTests.kt
 * Original: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/log/hierarchical/DBHierarchicalXESInputStreamWithWhereQueryTests.kt
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhereQueryTests : HierarchicalTestsBase() {

    private var journalLogId: String = ""

    @BeforeAll
    fun loadTestData() {
        journalLogId = loadTestDataWithUniqueId()
        println("Loaded JournalReview log: $journalLogId")
    }

    // =====================
    // WHERE simple tests (from ProcessM)
    // =====================

    @Test
    fun whereSimpleTest() {
        // ProcessM: where dayofweek(e:timestamp) in (1, 7) and l:id=$journal
        // Events that occur on Saturday (7) or Sunday (1) - US convention
        val result = q(
            "where dayofweek(e:timestamp) in (1, 7) and l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Should have exactly 1 log")

        val log = result.first()
        standardLogAssertionsWithMetadata(log)

        assertTrue(log.traces.count() > 0, "Log should have traces")
        for (trace in log.traces) {
            val conceptName = Integer.parseInt(trace.conceptName!!)
            assertTrue(conceptName >= -1, "Trace conceptName >= -1")
            assertTrue(conceptName <= 100, "Trace conceptName <= 100")
            assertEquals("EUR", trace.costCurrency, "Trace cost:currency should be EUR")
            assertTrue(trace.costTotal === null || trace.costTotal!! > 0.0, "Trace cost:total should be > 0 if not null")
            assertNull(trace.identityId, "Trace identity:id should be null")
            assertFalse(trace.isEventStream, "Trace isEventStream should be false")

            assertTrue(trace.events.count() > 0, "Trace should have events")
            for (event in trace.events) {
                standardEventAssertions(event)
                // Verify dayofweek is Saturday(7) or Sunday(1) in US convention
                val ts = event.timeTimestamp!!
                val dayOfWeek = ts.atZone(java.time.ZoneOffset.UTC).dayOfWeek
                // Java DayOfWeek: MONDAY=1..SUNDAY=7
                // US convention: Sunday=1, Saturday=7
                val usDayOfWeek = if (dayOfWeek.value == 7) 1 else dayOfWeek.value + 1
                assertTrue(usDayOfWeek == 1 || usDayOfWeek == 7,
                    "Event should be on Saturday(7) or Sunday(1), got US dayofweek=$usDayOfWeek for $ts")
            }
        }
    }

    @Test
    fun whereSimpleWithHoistingTest() {
        // ProcessM: where dayofweek(^e:timestamp) in (1, 7) and l:id=$journal
        // Hoisting at trace level - filter traces that have at least one event on a weekend day
        val result = q(
            "where dayofweek(^e:timestamp) in (1, 7) and l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Should have exactly 1 log")

        val log = result.first()
        standardLogAssertionsWithMetadata(log)

        assertTrue(log.traces.count() > 0, "Log should have traces")
        // With hoisting, traces are filtered but ALL events within matching traces are returned
        // (the filter applies at trace level, not event level)
        for (trace in log.traces) {
            val conceptName = Integer.parseInt(trace.conceptName!!)
            assertTrue(conceptName >= -1)
            assertTrue(conceptName <= 100)
            assertEquals("EUR", trace.costCurrency)
            assertTrue(trace.costTotal === null || trace.costTotal!! > 0.0)
            assertNull(trace.identityId)
            assertFalse(trace.isEventStream)

            assertTrue(trace.events.count() > 0, "Trace should have events")
            for (event in trace.events) {
                standardEventAssertions(event)
            }
            // At least one event per trace must be on a weekend (that's why trace was included)
            val validDays = setOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY)
            assertTrue(
                trace.events.any { e -> e.timeTimestamp!!.atZone(java.time.ZoneOffset.UTC).dayOfWeek in validDays },
                "Trace should have at least one weekend-day event (that's why it was included by hoisting)"
            )
            // NOT all events need to be weekend events (hoisting includes ALL trace events)
        }
    }

    @Test
    fun whereSimpleWithHoistingTest2() {
        // ProcessM: where dayofweek(^^e:timestamp) in (1, 7) and l:id=$journal
        // Double hoisting at log level — if ANY event in the log is on a weekend, the whole log is returned
        val result = q(
            "where dayofweek(^^e:timestamp) in (1, 7) and l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Should have exactly 1 log (included because some events are on weekends)")

        val log = result.first()
        standardLogAssertionsWithMetadata(log)

        assertTrue(log.traces.count() > 0, "Log should have traces")
        // ALL traces and ALL events are returned (filter at log level)
        for (trace in log.traces) {
            val conceptName = Integer.parseInt(trace.conceptName!!)
            assertTrue(conceptName >= -1)
            assertTrue(conceptName <= 100)
            assertEquals("EUR", trace.costCurrency)
            assertTrue(trace.costTotal === null || trace.costTotal!! > 0.0)
            assertNull(trace.identityId)
            assertFalse(trace.isEventStream)

            for (event in trace.events) {
                standardEventAssertions(event)
            }
        }
    }

    @Test
    fun whereLogicExprTest() {
        // ProcessM: where t:currency != e:currency and l:id=$journal
        // Filter events where trace currency (EUR) differs from event currency (USD)
        val result = q(
            "where t:currency != e:currency and l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Should have exactly 1 log")

        val log = result.first()
        standardLogAssertionsWithMetadata(log)

        assertTrue(log.traces.count() > 0, "Log should have traces")
        for (trace in log.traces) {
            val conceptName = Integer.parseInt(trace.conceptName!!)
            assertTrue(conceptName >= -1)
            assertTrue(conceptName <= 100)
            assertEquals("EUR", trace.costCurrency, "Trace cost:currency should be EUR")
            assertTrue(trace.costTotal === null || trace.costTotal!! > 0.0)
            assertNull(trace.identityId)
            assertFalse(trace.isEventStream)

            assertTrue(trace.events.count() > 0, "Trace should have events")
            for (event in trace.events) {
                assertTrue(event.conceptName in eventNames)
                assertTrue(event.timeTimestamp!!.isAfter(begin))
                assertTrue(event.timeTimestamp!!.isBefore(end))
                if (event.conceptInstance != null) {
                    assertNotNull(event.conceptInstance!!.toIntOrNull(), "concept:instance should be parseable as int")
                }
                assertEquals("USD", event.costCurrency, "Event cost:currency should be USD (mismatch with trace EUR)")
                assertEquals(1.08, event.costTotal, "Event cost:total should be exactly 1.08 (USD events)")
                assertNull(event.lifecycleState)
                assertTrue(event.lifecycleTransition in lifecycleTransitions)
                assertNull(event.orgGroup)
                assertTrue(event.orgResource in orgResources)
                assertNull(event.orgRole)
                assertNull(event.identityId)
            }
        }
    }

    @Test
    fun whereLogicExprWithHoistingTest() {
        // ProcessM: where not(t:currency = ^e:currency) and l:id=$journal
        val result = q(
            "where not(t:currency = ^e:currency) and l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Should have exactly 1 log")

        val log = result.first()
        standardLogAssertionsWithMetadata(log)

        assertTrue(log.traces.count() > 0, "Log should have traces")
        for (trace in log.traces) {
            val conceptName = Integer.parseInt(trace.conceptName!!)
            assertTrue(conceptName >= -1)
            assertTrue(conceptName <= 100)
            assertEquals("EUR", trace.costCurrency, "Trace cost:currency should be EUR")
            assertTrue(trace.costTotal === null || trace.costTotal!! > 0.0)
            assertNull(trace.identityId)
            assertFalse(trace.isEventStream)

            assertTrue(trace.events.count() > 0)
            for (event in trace.events) {
                assertTrue(event.conceptName in eventNames)
                assertTrue(event.timeTimestamp!!.isAfter(begin))
                assertTrue(event.timeTimestamp!!.isBefore(end))
                if (event.conceptInstance != null) {
                    assertNotNull(event.conceptInstance!!.toIntOrNull(), "concept:instance should be parseable as int")
                }
                assertEquals("USD", event.costCurrency, "Event cost:currency should be USD")
                assertEquals(1.08, event.costTotal, "Event cost:total should be exactly 1.08")
                assertNull(event.lifecycleState)
                assertTrue(event.lifecycleTransition in lifecycleTransitions)
                assertNull(event.orgGroup)
                assertTrue(event.orgResource in orgResources)
                assertNull(event.orgRole)
                assertNull(event.identityId)
            }
        }
    }

    @Test
    fun whereLogicExpr2Test() {
        // ProcessM: where not(t:currency = ^e:currency) and t:total is null and l:id=$journal
        val result = q(
            "where not(t:currency = ^e:currency) and t:total is null and l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Should have exactly 1 log")

        val log = result.first()
        standardLogAssertionsWithMetadata(log)

        assertTrue(log.traces.count() > 0, "Log should have traces")
        for (trace in log.traces) {
            val conceptName = Integer.parseInt(trace.conceptName!!)
            assertTrue(conceptName >= -1)
            assertTrue(conceptName <= 100)
            assertEquals("EUR", trace.costCurrency)
            assertNull(trace.costTotal, "Trace cost:total should be null (filtered by t:total is null)")
            assertNull(trace.identityId)
            assertFalse(trace.isEventStream)

            assertTrue(trace.events.count() > 0)
            for (event in trace.events) {
                assertTrue(event.conceptName in eventNames)
                assertTrue(event.timeTimestamp!!.isAfter(begin))
                assertTrue(event.timeTimestamp!!.isBefore(end))
                if (event.conceptInstance != null) {
                    assertNotNull(event.conceptInstance!!.toIntOrNull(), "concept:instance should be parseable as int")
                }
                assertEquals("USD", event.costCurrency, "Event cost:currency should be USD")
                assertEquals(1.08, event.costTotal, "Event cost:total should be exactly 1.08")
                assertNull(event.lifecycleState)
                assertTrue(event.lifecycleTransition in lifecycleTransitions)
                assertNull(event.orgGroup)
                assertTrue(event.orgResource in orgResources)
                assertNull(event.orgRole)
                assertNull(event.identityId)
            }
        }
    }

    @Test
    fun whereLogicExpr3Test() {
        // ProcessM: where (not(t:currency = ^e:currency) or ^e:timestamp >= D2007-01-01) and t:total is null and l:id=$journal
        val myBegin = Instant.parse("2007-01-01T00:00:00Z")
        val result = q(
            "where (not(t:currency = ^e:currency) or ^e:timestamp >= D2007-01-01) and t:total is null and l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Should have exactly 1 log")

        val log = result.first()
        standardLogAssertionsWithMetadata(log)

        assertTrue(log.traces.count() > 0)
        // All traces must have null total (filtered by t:total is null)
        assertTrue(log.traces.all { t -> t.costTotal === null }, "All traces should have null cost:total")
        // The OR condition: trace included if (t:currency != some event currency) OR (some event timestamp >= 2007-01-01)
        assertTrue(log.traces.all { t ->
            t.events.any { e -> e.costCurrency != t.costCurrency } ||
            t.events.any { e -> !e.timeTimestamp!!.isBefore(myBegin) }
        }, "Each trace should satisfy the OR condition")

        for (trace in log.traces) {
            val conceptName = Integer.parseInt(trace.conceptName!!)
            assertTrue(conceptName >= -1)
            assertTrue(conceptName <= 100)
            assertEquals("EUR", trace.costCurrency)
            assertNull(trace.costTotal)
            assertNull(trace.identityId)
            assertFalse(trace.isEventStream)

            assertTrue(trace.events.count() > 0)
        }
    }

    @Test
    fun whereLikeAndMatchesTest() {
        // ProcessM: where t:name like '%5' and ^e:resource matches '^[SP]am$' and l:id=$journal
        val result = q(
            "where t:name like '%5' and ^e:resource matches '^[SP]am\$' and l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Should have exactly 1 log")

        val log = result.first()
        standardLogAssertionsWithMetadata(log)

        assertTrue(log.traces.count() > 0, "Log should have traces")
        for (trace in log.traces) {
            val traceName = trace.conceptName
            assertNotNull(traceName, "Trace should have name")
            assertTrue(traceName.endsWith("5"),
                "Trace name should end with '5', got: $traceName")
            assertNull(trace.identityId)
            assertFalse(trace.isEventStream)

            // With hoisting (^e:resource), filter is at trace level — all events returned
            // At least one event per trace must have Sam/Pam resource (that's why trace was included)
            val hasMatchingResource = trace.events.any { it.orgResource == "Sam" || it.orgResource == "Pam" }
            assertTrue(hasMatchingResource, "Trace should have at least one Sam/Pam event")
            // Do NOT check every event's resource — hoisting returns all events in matching traces
        }
    }

    @Test
    fun whereNotNull() {
        // ProcessM: where l:id=$journal and [t:cost:total] is not null
        val result = q(
            "where l:logId='$journalLogId' and [t:cost:total] is not null",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have logs")

        val log = result.first()
        val traces = log.traces.toList()
        assertTrue(traces.isNotEmpty(), "Should have traces with non-null cost:total")
        assertNotEquals(101, traces.size, "Should be fewer than 101 traces (some have null cost:total)")

        for (trace in traces) {
            val total = trace.costTotal
            assertNotNull(total, "Trace cost:total should not be null (filtered by IS NOT NULL)")
            assertTrue(total > 0.0, "Trace cost:total should be > 0, got: $total")
        }
    }

    @Test
    fun whereNotNull2() {
        // ProcessM: where l:id=$hospital and [t:Diagnosis] is not null
        val hospitalLogId = testDataLoader.loadHospitalLog() ?: run {
            println("Hospital dataset not available, skipping"); return
        }
        val result = q("where l:logId='$hospitalLogId' and [t:Diagnosis] is not null", hospitalLogId)
        assertTrue(result.success, "Query should succeed: ${result.error}")
        val traces = result.first().traces.toList()
        assertTrue(traces.isNotEmpty(), "Should have traces with non-null Diagnosis")
        for (trace in traces) {
            val diagnosis = trace.attributes["Diagnosis"]
            assertNotNull(diagnosis, "Trace Diagnosis should not be null (filtered by IS NOT NULL)")
            assertTrue(diagnosis.toString().isNotBlank(), "Trace Diagnosis should not be blank")
        }
    }
}
