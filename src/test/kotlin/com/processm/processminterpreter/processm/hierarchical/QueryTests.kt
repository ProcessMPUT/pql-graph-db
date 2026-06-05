package com.processm.processminterpreter.processm.hierarchical

import com.processm.processminterpreter.TestcontainersConfiguration
import com.processm.processminterpreter.domain.log.xes.XesAttributeValue
import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.log.xes.XesTrace
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Main PQL query tests ported from ProcessM
 *
 * Tests based on: DBHierarchicalXESInputStreamWithQueryTests.kt
 * Original: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/log/hierarchical/DBHierarchicalXESInputStreamWithQueryTests.kt
 *
 * Includes tests from:
 * - PQLQueryTests.kt (original)
 * - HierarchicalLimitTest.kt (consolidated)
 * - SimpleHierarchicalQueryTest.kt (consolidated)
 * - HierarchicalPQLFeaturesTest.kt (consolidated, non-duplicate tests)
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryTests : HierarchicalTestsBase() {
    private var journalLogId: String = ""

    @BeforeAll
    fun loadTestData() {
        journalLogId = loadTestDataWithUniqueId()
        println("Loaded JournalReview log: $journalLogId")
    }

    private fun assertSelectedLogNameOnly(
        log: XesLog,
        expectedName: String? = null,
    ) {
        if (expectedName == null) {
            assertNotNull(log.conceptName, "Selected l:name should materialize as log.conceptName")
        } else {
            assertEquals(expectedName, log.conceptName)
        }
        assertNull(log.lifecycleModel, "lifecycle:model should not be selected by l:name")
        assertNull(log.identityId, "identity:id should not be selected by l:name")
        assertTrue(
            log.customAttributes.isEmpty(),
            "Selected l:name should not create duplicate or unrelated log custom attributes: ${log.customAttributes}",
        )
    }

    private fun nestedAttribute(
        attributes: Map<String, Any?>,
        key: String,
    ): XesAttributeValue =
        attributes[key] as? XesAttributeValue
            ?: error("Expected nested XES attribute '$key', got: ${attributes[key]}")

    // =====================
    // LIMIT Tests (from ProcessM)
    // =====================

    @Test
    fun limitSingleTest() {
        // ProcessM: where l:name='JournalReview' limit l:1
        val result =
            q(
                "where l:logId='$journalLogId' limit l:1",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Should have exactly 1 log")

        val log = result.first()
        assertTrue(log.traces.count() > 1, "Log should have multiple traces")
        assertTrue(log.traces.any { trace -> trace.events.count() > 1 }, "At least one trace should have multiple events")
    }

    @Test
    fun limitAllTest() {
        // ProcessM: limit e:3, t:2, l:1
        val result =
            q(
                "limit e:3, t:2, l:1",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Should have exactly 1 log")

        val log = result.first()
        assertTrue(log.traces.count() <= 2, "Should have at most 2 traces (limit t:2)")
        assertTrue(log.traces.all { trace -> trace.events.count() <= 3 }, "Each trace should have at most 3 events (limit e:3)")
    }

    @Test
    fun `limits do not affect upper scopes`() {
        // ProcessM: limits do not affect upper scopes
        val bpiLogId = testDataLoader.loadBPILog()
        assertNotNull(bpiLogId, "This ProcessM port requires BPI test data as a second log")

        val totalLogsResult = q("select l:name")
        assertTrue(totalLogsResult.success, "Log count baseline should succeed: ${totalLogsResult.error}")
        val totalLogs = totalLogsResult.count()
        assertTrue(totalLogs >= 2, "This test requires at least two event logs")

        val result1 = q("select l:name limit l:$totalLogs, t:1, e:1")
        val result2 = q("select l:name limit l:$totalLogs, e:1")
        val result3 = q("select l:name limit l:$totalLogs, t:1")

        assertTrue(result1.success, "Query 1 should succeed: ${result1.error}")
        assertTrue(result2.success, "Query 2 should succeed: ${result2.error}")
        assertTrue(result3.success, "Query 3 should succeed: ${result3.error}")

        assertEquals(totalLogs, result1.count())
        assertEquals(totalLogs, result2.count())
        assertEquals(totalLogs, result3.count())
    }

    // =====================
    // OFFSET Tests (from ProcessM)
    // =====================

    @Test
    fun offsetSingleTest() {
        // ProcessM: where l:id=$journal offset l:1
        val result =
            q(
                "where l:logId='$journalLogId' offset l:1",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(0, result.count(), "Should have 0 logs with offset 1")

        // ProcessM also asserts readVersion() == 0 here. Our application query result
        // does not expose stream versioning yet, so the portable contract is only the
        // hierarchy effect of log-level offset.
        val journalAll = q("where l:logId='$journalLogId'", journalLogId)
        val journalWithOffset = q("where l:logId='$journalLogId' offset l:1", journalLogId)
        assertTrue(journalAll.success, "Baseline query should succeed: ${journalAll.error}")
        assertTrue(journalWithOffset.success, "Offset query should succeed: ${journalWithOffset.error}")
        assertEquals(max(journalAll.count() - 1, 0), journalWithOffset.count())
    }

    @Test
    fun offsetAllTest() {
        // ProcessM: where l:id=$journal offset e:3, t:2, l:1
        val result =
            q(
                "where l:logId='$journalLogId' offset e:3, t:2, l:1",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(0, result.count(), "Should have 0 logs with log offset 1")

        // ProcessM also asserts readVersion() == 0 here. Our application query result
        // does not expose stream versioning yet; keep the remaining offset semantics 1:1.
        val journalAll = q("where l:logId='$journalLogId' limit l:1", journalLogId)
        val journalWithOffset = q("where l:logId='$journalLogId' limit l:1 offset e:3, t:2", journalLogId)
        assertTrue(journalAll.success, "Baseline query should succeed: ${journalAll.error}")
        assertTrue(journalWithOffset.success, "Offset query should succeed: ${journalWithOffset.error}")
        assertEquals(journalAll.count(), journalWithOffset.count())

        val baselineLog = journalAll.first()
        val offsetLog = journalWithOffset.first()
        val baselineTraces = baselineLog.traces.associateBy { it.conceptName }
        assertEquals(max(baselineTraces.size - 2, 0), offsetLog.traces.count())

        for (trace in offsetLog.traces) {
            val baselineTrace = baselineTraces[trace.conceptName]
            assertNotNull(baselineTrace, "Offset trace should come from baseline trace set: ${trace.conceptName}")
            assertEquals(max(baselineTrace.events.count() - 3, 0), trace.events.count())
        }
    }

    // =====================
    // ORDER BY Tests (from ProcessM)
    // =====================

    @Test
    fun orderBySimpleTest() {
        // ProcessM: where l:name='JournalReview' order by e:timestamp limit l:3
        val result =
            q(
                "where l:logId='$journalLogId' order by e:timestamp limit l:3",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Filtering by the loaded JournalReview log should return exactly one log")
        assertTrue(result.count() <= 3, "Log-level limit should return at most 3 logs")

        for (log in result.logs) {
            standardLogAssertions(log)
            assertTrue(log.traces.count() > 0, "Log should have traces")
            assertTrue(log.traces.count() <= TOTAL_TRACES, "Log should not exceed fixture trace count")
            for (trace in log.traces) {
                standardTraceAssertions(trace)
                val events = trace.events.toList()
                assertTrue(events.isNotEmpty(), "Trace should have events")
                assertTrue(events.size <= 55, "Trace should not exceed fixture max event count")

                // Verify timestamps are strictly non-decreasing
                var lastTimestamp = begin
                for (event in events) {
                    standardEventAssertions(event)
                    assertTrue(
                        !event.timeTimestamp!!.isBefore(lastTimestamp),
                        "Events should be ordered by timestamp ascending",
                    )
                    lastTimestamp = event.timeTimestamp!!
                }
            }
        }
    }

    @Test
    fun orderByWithModifierAndScopesTest() {
        // ProcessM: where l:name='JournalReview' order by t:total desc, e:timestamp limit l:3
        val result =
            q(
                "where l:logId='$journalLogId' order by t:total desc, e:timestamp limit l:3",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Filtering by the loaded JournalReview log should return exactly one log")

        val log = result.first()
        standardLogAssertions(log)
        assertTrue(log.traces.count() == 101, "Should have all 101 traces")
        val traces = log.traces.toList()
        for (trace in traces) {
            standardTraceAssertions(trace)
        }

        // Traces ordered by cost:total DESC (nulls first — cmp treats null as max)
        var lastTotal: Double? = null // nulls first
        for (trace in traces) {
            assertTrue(
                cmp(trace.costTotal, lastTotal) <= 0,
                "Traces should be ordered by cost:total DESC (nulls first)",
            )
            lastTotal = trace.costTotal

            assertTrue(trace.events.count() <= 55, "Events per trace should be <= 55")
            var lastTimestamp = begin
            for (event in trace.events) {
                standardEventAssertions(event)
                assertFalse(
                    event.timeTimestamp!!.isBefore(lastTimestamp),
                    "Events within trace should be ordered by timestamp ASC",
                )
                lastTimestamp = event.timeTimestamp!!
            }
        }
    }

    @Test
    fun orderByWithModifierAndScopes2Test() {
        // ProcessM: order by e:timestamp, t:total desc limit l:3
        // Same ordering as orderByWithModifierAndScopesTest but with reversed ORDER BY arguments
        val result =
            q(
                "where l:logId='$journalLogId' order by e:timestamp, t:total desc limit l:3",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Filtering by the loaded JournalReview log should return exactly one log")

        for (log in result.logs) {
            assertEquals(101, log.traces.count(), "Should have all 101 traces")

            var lastTotal: Double? = null // nulls first (cmp treats null as max)
            for (trace in log.traces) {
                assertTrue(
                    cmp(trace.costTotal, lastTotal) <= 0,
                    "Traces should be ordered by cost:total DESC (nulls first)",
                )
                lastTotal = trace.costTotal

                assertTrue(trace.events.count() <= 55, "Events per trace should be <= 55")
                var lastTimestamp = begin
                for (event in trace.events) {
                    standardEventAssertions(event)
                    assertFalse(
                        event.timeTimestamp!!.isBefore(lastTimestamp),
                        "Events within trace should be ordered by timestamp ASC",
                    )
                    lastTimestamp = event.timeTimestamp!!
                }
            }
        }
    }

    // =====================
    // selectEmpty / hierarchy tests (from ProcessM)
    // =====================

    @Test
    fun selectEmpty() {
        // ProcessM: where 0=1
        val result = q("where 0=1", journalLogId)

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(0, result.count(), "Should have 0 logs")
        // ProcessM also asserts readVersion() == 0. Version is not part of our PQL
        // application result contract yet.
    }

    // =====================
    // GROUP BY Tests (from ProcessM) - TDD specs
    // =====================

    @Test
    fun groupEventByStandardAttributeTest() {
        // ProcessM: select t:name, e:name, sum(e:total) where l:id=$journal group by e:name
        val result =
            q(
                "select t:name, e:name, sum(e:total) where l:logId='$journalLogId' group by e:name",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(101, log.traces.count(), "Should have 101 traces (one per original trace)")

        for (trace in log.traces) {
            val conceptName = trace.conceptName?.toIntOrNull()
            assertNotNull(conceptName, "Trace conceptName should be a number")
            assertTrue(conceptName in -1..100, "Trace conceptName should be in -1..100, got: $conceptName")
            assertNull(trace.costCurrency)
            assertNull(trace.costTotal)
            assertTrue(trace.events.count() >= 1, "Each trace should have at least 1 grouped event")

            // All events within a trace should have distinct names (each event is a group)
            val distinctConceptNames = trace.events.distinctBy { it.conceptName }.count()
            assertEquals(
                distinctConceptNames,
                trace.events.count(),
                "Each event should have a unique name within the trace (grouped)",
            )

            for (event in trace.events) {
                assertTrue(
                    event.conceptName in eventNames,
                    "Event name should be in eventNames: ${event.conceptName}",
                )
                assertNull(event.costCurrency)
                assertNull(event.costTotal)

                val sumTotal = event.customAttributes["sum(event:cost:total)"]
                assertNotNull(sumTotal, "sum(event:cost:total) should be present in event attributes")
                assertTrue((sumTotal as Number).toDouble() >= 1.0, "sum(e:total) should be >= 1.0, got: $sumTotal")
            }
        }
    }

    @Test
    fun groupLogByEventStdAttrAndImplicitGroupEventByTest() {
        // ProcessM: select sum(e:total) where l:name='JournalReview' group by ^^e:name
        // ^^e:name hoists to log scope — log-scope GROUP BY is a no-op for single-log queries.
        // Implicit event GROUP BY means "aggregate all events per trace" → one trace per trace,
        // each with sum(e:total) across all its events. ProcessM returns 30 traces (default limit).
        val result =
            q(
                "select sum(e:total) where l:logId='$journalLogId' group by ^^e:name",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertTrue(log.traces.count() > 1, "Should have multiple traces (one per trace, not per event name)")

        for (trace in log.traces) {
            assertEquals(1, trace.events.count(), "Each trace should have exactly 1 aggregated event")
            val event = trace.events.first()
            val sumAttr = event.customAttributes["sum(event:cost:total)"]
            assertNotNull(sumAttr, "sum(event:cost:total) should be present in event attributes")
            val sumValue = (sumAttr as Number).toDouble()
            assertTrue(sumValue >= 1.0, "sum(e:total) should be >= 1.0, got: $sumValue")
        }
    }

    @Test
    fun groupLogByEventStdAndGroupEventByStdAttrTest() {
        // ProcessM: select e:name, sum(e:total) where l:name='JournalReview' group by ^^e:name, e:name
        val result =
            q(
                "select e:name, sum(e:total) where l:logId='$journalLogId' group by ^^e:name, e:name",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertTrue(log.traces.count() > 1, "Should have multiple trace-groups")

        for (trace in log.traces) {
            assertTrue(trace.events.count() >= 1, "Each trace-group should have at least 1 event")
            for (event in trace.events) {
                assertNotNull(event.conceptName, "Event should have concept name (selected via e:name)")
                assertTrue(event.conceptName in eventNames, "Event name should be in eventNames, got: ${event.conceptName}")
                val sumAttr = event.customAttributes["sum(event:cost:total)"]
                assertNotNull(sumAttr, "sum(event:cost:total) should be present")
                assertTrue((sumAttr as Number).toDouble() >= 1.0, "sum should be >= 1.0")
            }
        }
    }

    @Test
    fun groupByImplicitScopeTest() {
        // ProcessM: where l:id=$journal group by c:Resource
        val result =
            q(
                "where l:logId='$journalLogId' group by c:Resource",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        standardLogAssertions(log)
        assertEquals("JournalReview", log.conceptName)
        assertEquals("standard", log.lifecycleModel)
        assertNull(log.identityId, "storage logId must not leak as XES identity:id")
        val traces = log.traces.toList()
        assertEquals(101, traces.size, "Should have 101 groups (one per trace)")

        for (trace in traces) {
            val conceptName = trace.conceptName?.toIntOrNull()
            assertNotNull(conceptName, "Grouped trace conceptName should be numeric: ${trace.conceptName}")
            assertTrue(conceptName >= -1, "Grouped trace conceptName should be >= -1: $conceptName")
            assertTrue(conceptName <= 100, "Grouped trace conceptName should be <= 100: $conceptName")
            assertEquals("EUR", trace.costCurrency)
            assertTrue(
                trace.costTotal == null || trace.costTotal!!.toInt() in 1..50,
                "Trace cost:total should be null or in 1..50",
            )
            assertNull(trace.identityId)

            val events = trace.events.toList()
            assertTrue(events.isNotEmpty(), "Each trace-group should have at least 1 aggregated row")
            for (event in events) {
                assertNull(event.conceptName)
                assertNull(event.conceptInstance)
                assertNull(event.costCurrency)
                assertNull(event.costTotal)
                assertNull(event.orgGroup)
                assertNull(event.orgRole)
                assertTrue(event.orgResource in orgResources, "Event org:resource should be in fixture resources")
                assertNull(event.timeTimestamp)
                assertEquals(
                    emptySet(),
                    event.customAttributes.keys,
                    "Classifier Resource should materialize as org:resource only, not as duplicate custom attributes",
                )
            }
        }
    }

    @Test
    fun groupByOuterScopeTest() {
        // ProcessM: select t:min(l:name) where l:name='JournalReview' limit l:3
        val result =
            q(
                "select t:min(l:name) where l:logId='$journalLogId' limit l:3",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.count() in 1..3)

        for (log in result.logs) {
            assertEquals(1, log.traces.count(), "Each log should have 1 trace (grouped)")
            val trace = log.traces.first()
            assertEquals(1, trace.customAttributes.size, "Trace should have 1 attribute")
            assertEquals(
                "JournalReview",
                trace.customAttributes["trace:min(log:concept:name)"],
                "trace:min(l:name) should be JournalReview",
            )
        }
    }

    @Test
    fun groupByImplicitFromSelectTest() {
        // ProcessM: select l:*, t:*, avg(e:total), min(e:timestamp), max(e:timestamp) where l:name matches '...' limit l:1
        val result =
            q(
                "select l:*, t:*, avg(e:total), min(e:timestamp), max(e:timestamp) where l:logId='$journalLogId' limit l:1",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        standardLogAssertionsWithMetadata(log)
        assertEquals(101, log.traces.count(), "Should have all 101 traces")

        for (trace in log.traces) {
            val conceptName = Integer.parseInt(trace.conceptName!!)
            assertTrue(conceptName >= -1, "Trace conceptName >= -1, got $conceptName")
            assertTrue(conceptName <= 100, "Trace conceptName <= 100, got $conceptName")
            assertEquals("EUR", trace.costCurrency, "Trace cost:currency should be EUR")
            assertTrue(
                trace.costTotal === null || trace.costTotal!!.toInt() in 1..50,
                "Trace cost:total should be null or in 1..50",
            )
            assertNull(trace.identityId, "Trace identity:id should be null")

            assertEquals(1, trace.events.count(), "Each trace should have exactly 1 aggregated event")
            val event = trace.events.first()
            assertNull(event.conceptName, "Event conceptName should be null (grouped away)")
            assertNull(event.conceptInstance, "Event concept:instance should be null")
            assertNull(event.costCurrency, "Event cost:currency should be null")
            assertNull(event.costTotal, "Event cost:total should be null")
            assertNull(event.orgGroup, "Event org:group should be null")
            assertNull(event.orgRole, "Event org:role should be null")
            assertNull(event.orgResource, "Event org:resource should be null")
            assertNull(event.timeTimestamp, "Event timestamp should be null (aggregated away)")
            assertEquals(3, event.customAttributes.size, "Event should have exactly 3 aggregate attributes")
            val avgAttr = event.customAttributes["avg(event:cost:total)"]
            val minAttr = event.customAttributes["min(event:time:timestamp)"]
            val maxAttr = event.customAttributes["max(event:time:timestamp)"]
            assertNotNull(avgAttr, "avg(event:cost:total) should be present")
            assertNotNull(minAttr, "min(event:time:timestamp) should be present")
            assertNotNull(maxAttr, "max(event:time:timestamp) should be present")
            assertTrue(
                (avgAttr as Number).toDouble() in 1.0..1.08,
                "avg(e:total) should be in 1.0..1.08, got: $avgAttr",
            )
            assertTrue(
                (minAttr as Instant).isAfter(begin),
                "min(e:timestamp) should be after begin",
            )
            assertTrue(
                (maxAttr as Instant).isBefore(end),
                "max(e:timestamp) should be before end",
            )
        }
    }

    @Test
    fun groupByImplicitFromOrderByTest() {
        // ProcessM: where l:id=$journal order by avg(e:total), min(e:timestamp), max(e:timestamp)
        val result =
            q(
                "where l:logId='$journalLogId' order by avg(e:total), min(e:timestamp), max(e:timestamp)",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        standardLogAssertionsWithMetadata(log)
        assertEquals(101, log.traces.count(), "Should have all 101 traces")

        for (trace in log.traces) {
            val conceptName = Integer.parseInt(trace.conceptName!!)
            assertTrue(conceptName >= -1, "Trace conceptName >= -1, got $conceptName")
            assertTrue(conceptName <= 100, "Trace conceptName <= 100, got $conceptName")
            assertEquals("EUR", trace.costCurrency, "Trace cost:currency should be EUR")
            assertTrue(
                trace.costTotal === null || trace.costTotal!!.toInt() in 1..50,
                "Trace cost:total should be null or in 1..50",
            )
            assertNull(trace.identityId, "Trace identity:id should be null")

            assertEquals(1, trace.events.count(), "Each trace should have exactly 1 placeholder event")
            val event = trace.events.first()
            assertNull(event.conceptName)
            assertNull(event.conceptInstance)
            assertNull(event.costCurrency)
            assertNull(event.costTotal)
            assertNull(event.orgGroup)
            assertNull(event.orgRole)
            assertNull(event.orgResource)
            assertNull(event.timeTimestamp)
            assertTrue(event.customAttributes.isEmpty(), "ORDER BY-only aggregates must not leak into event attributes")
        }
    }

    @Test
    fun groupByImplicitWithHoistingTest() {
        // ProcessM: select avg(^^e:total), min(^^e:timestamp), max(^^e:timestamp) where l:id=$journal
        val result =
            q(
                "select avg(^^e:total), min(^^e:timestamp), max(^^e:timestamp) where l:logId='$journalLogId'",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        // Selected aggregates are log-scoped (^^ = log level)
        assertNull(log.conceptName, "Log conceptName should be null (not selected)")
        assertNull(log.lifecycleModel, "Log lifecycle:model should be null")
        assertNull(log.identityId, "Log identity:id should be null")
        val avgAttr = log.customAttributes["avg(^^event:cost:total)"]
        val minAttr = log.customAttributes["min(^^event:time:timestamp)"]
        val maxAttr = log.customAttributes["max(^^event:time:timestamp)"]
        assertNotNull(avgAttr, "avg(^^event:cost:total) should be in log.customAttributes")
        assertNotNull(minAttr, "min(^^event:time:timestamp) should be in log.customAttributes")
        assertNotNull(maxAttr, "max(^^event:time:timestamp) should be in log.customAttributes")
        assertTrue(
            (avgAttr as Number).toDouble() in 1.0..1.08,
            "avg(^^e:total) should be in 1.0..1.08, got: $avgAttr",
        )
        assertTrue(
            (minAttr as Instant).isAfter(begin),
            "min(^^e:timestamp) should be after begin, got: $minAttr",
        )
        assertTrue(
            (maxAttr as Instant).isBefore(end),
            "max(^^e:timestamp) should be before end, got: $maxAttr",
        )

        assertEquals(101, log.traces.count(), "Should have 101 traces")
        for (trace in log.traces) {
            assertNull(trace.conceptName, "Trace conceptName should be null")
            assertNull(trace.costCurrency, "Trace cost:currency should be null")
            assertNull(trace.costTotal, "Trace cost:total should be null")
            assertNull(trace.identityId, "Trace identity:id should be null")

            assertTrue(trace.events.count() >= 1, "Trace should have events")
            for (event in trace.events) {
                assertNull(event.conceptName, "Event conceptName should be null")
                assertNull(event.conceptInstance, "Event concept:instance should be null")
                assertNull(event.costCurrency, "Event cost:currency should be null")
                assertNull(event.costTotal, "Event cost:total should be null")
                assertNull(event.orgGroup, "Event org:group should be null")
                assertNull(event.orgRole, "Event org:role should be null")
                assertNull(event.orgResource, "Event org:resource should be null")
                assertNull(event.timeTimestamp, "Event timestamp should be null")
                assertEquals(0, event.customAttributes.size, "Event should have 0 attributes")
            }
        }
    }

    @Test
    fun groupByWithHoistingAndOrderByWithinGroupTest() {
        // ProcessM: where l:id=$journal group by ^e:name order by name
        // 78 trace-variants (order-insensitive grouping, events sorted by name within group)
        val result =
            q(
                "where l:logId='$journalLogId' group by ^e:name order by name limit t:200",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have results")

        val log = result.first()
        val variants = log.traces.toList()
        assertEquals(78, variants.size, "Should have 78 unique trace-variants (order-insensitive grouping)")

        fun validate(
            validTraces: List<List<String>>,
            count: Int,
        ) {
            for (validTrace in validTraces) {
                assertTrue(
                    variants
                        .filter {
                            it.count == count
                        }.any {
                            it.events
                                .map { e -> e.conceptName }
                                .filterNotNull() == validTrace
                        },
                    "Expected variant with count=$count and events $validTrace not found",
                )
            }
        }

        // Variants with count=4 (sorted alphabetically within group)
        val fourTraces =
            listOf(
                "accept,accept,collect reviews,collect reviews,decide,decide,get review 1,get review 2,get review 3,invite reviewers,invite reviewers",
            ).map { it.split(',') }

        // Variants with count=3
        val threeTraces =
            listOf(
                "collect reviews,collect reviews,decide,decide,get review 1,get review 3,invite reviewers,invite reviewers,reject,reject,time-out 2",
                "accept,accept,collect reviews,collect reviews,decide,decide,get review 1,get review 2,get review X,get review X,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,time-out 3",
                "collect reviews,collect reviews,decide,decide,get review 1,get review 2,get review 3,get review X,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,reject,reject",
            ).map { it.split(',') }

        val twoTraces =
            listOf(
                "collect reviews,collect reviews,decide,decide,get review 2,get review 3,get review X,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,reject,reject,time-out 1",
                "accept,accept,collect reviews,collect reviews,decide,decide,get review 2,get review 3,get review X,get review X,get review X,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,time-out 1",
                "accept,accept,collect reviews,collect reviews,decide,decide,get review 3,get review X,get review X,get review X,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,time-out 1,time-out 2",
                "collect reviews,collect reviews,decide,decide,get review 2,get review 3,get review X,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,reject,reject,time-out 1,time-out X",
                "collect reviews,collect reviews,decide,decide,get review 1,get review 2,get review X,get review X,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,reject,reject,time-out 3",
                "collect reviews,collect reviews,decide,decide,get review 3,get review X,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,reject,reject,time-out 1,time-out 2",
                "collect reviews,collect reviews,decide,decide,get review 1,get review 2,get review X,get review X,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,reject,reject,time-out 3,time-out X,time-out X,time-out X",
                "accept,accept,collect reviews,collect reviews,decide,decide,get review 3,get review X,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,time-out 1,time-out 2",
                "accept,accept,collect reviews,collect reviews,decide,decide,get review 1,get review 2,get review X,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,time-out 3,time-out X,time-out X",
                "collect reviews,collect reviews,decide,decide,get review 1,get review 2,get review X,get review X,get review X,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,reject,reject,time-out 3,time-out X,time-out X,time-out X,time-out X",
                "collect reviews,collect reviews,decide,decide,get review X,get review X,get review X,get review X,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,reject,reject,time-out 1,time-out 2,time-out 3,time-out X,time-out X,time-out X,time-out X",
                "accept,accept,collect reviews,collect reviews,decide,decide,get review 1,get review 3,invite reviewers,invite reviewers,time-out 2",
                "collect reviews,collect reviews,decide,decide,get review 3,get review X,get review X,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,reject,reject,time-out 1,time-out 2,time-out X,time-out X",
                "accept,accept,collect reviews,collect reviews,decide,decide,get review 1,get review 2,get review 3,get review X,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite additional reviewer,invite reviewers,invite reviewers,time-out X,time-out X",
            ).map { it.split(',') }

        validate(fourTraces, 4)
        validate(threeTraces, 3)
        validate(twoTraces, 2)
    }

    @Test
    fun groupByWithHoistingAndOrderByCountTest() {
        // ProcessM: select l:name, count(t:name), e:name where l:id=$journal group by ^e:name order by count(t:name) desc limit l:1
        // ADAPTED_PORT: ProcessM also asserts readVersion()==2298 and trace.count. Our contract has no stream version
        // and represents the grouped trace cardinality as count(trace:concept:name).
        val result =
            q(
                "select l:name, count(t:name), e:name where l:logId='$journalLogId' group by ^e:name order by count(t:name) desc limit l:1",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertSelectedLogNameOnly(log, "JournalReview")
        assertEquals(97, log.traces.count(), "Should have 97 variant groups")

        fun traceCount(trace: XesTrace): Long =
            (trace.customAttributes["count(trace:concept:name)"] as? Number)?.toLong()
                ?: error("Missing count(trace:concept:name) for trace ${trace.conceptName}")

        // Sum of all grouped trace counts = 101 (all JournalReview traces distributed across variants).
        val totalCount =
            log.traces.sumOf { trace ->
                traceCount(trace)
            }
        assertEquals(101L, totalCount, "Total trace count should be 101 (all traces distributed)")

        for (trace in log.traces.drop(3)) {
            assertEquals(1L, traceCount(trace), "Remaining variants should have count=1")
        }

        val threeTraces =
            listOf(
                "invite reviewers,invite reviewers,get review 2,get review 3,get review 1,collect reviews,collect reviews,decide,decide,invite additional reviewer,invite additional reviewer,get review X,reject,reject",
            ).map { it.split(',') }
        val twoTraces =
            listOf(
                "invite reviewers,invite reviewers,get review 2,get review 1,get review 3,collect reviews,collect reviews,decide,decide,accept,accept",
                "invite reviewers,invite reviewers,get review 2,get review 1,time-out 3,collect reviews,collect reviews,decide,decide,invite additional reviewer,invite additional reviewer,time-out X,invite additional reviewer,invite additional reviewer,time-out X,invite additional reviewer,invite additional reviewer,get review X,accept,accept",
            ).map { it.split(',') }

        fun validate(
            validTraces: List<List<String>>,
            actualTraces: List<XesTrace>,
            count: Long,
        ) {
            for (actualTrace in actualTraces) {
                assertEquals(count, traceCount(actualTrace))
            }
            for (validTrace in validTraces) {
                assertTrue(
                    actualTraces.any { actualTrace ->
                        actualTrace.events
                            .map { it.conceptName }
                            .zip(validTrace)
                            .all { (actual, expected) -> actual == expected }
                    },
                    "Missing grouped variant: ${validTrace.joinToString(",")}",
                )
            }
        }

        validate(threeTraces, log.traces.take(1), 3L)
        validate(twoTraces, log.traces.drop(1).take(2), 2L)
    }

    @Test
    fun aggregationFunctionIndependence() {
        // ProcessM: adding count(^e:name) must not change the grouped trace variants
        // or their count(trace:concept:name) cardinalities.
        val result1 =
            q(
                "select l:name, count(t:name), e:name where l:logId='$journalLogId' group by ^e:name order by count(t:name) desc limit l:1",
                journalLogId,
            )
        val result2 =
            q(
                "select l:name, count(t:name), count(^e:name), e:name where l:logId='$journalLogId' group by ^e:name order by count(t:name) desc limit l:1",
                journalLogId,
            )

        assertTrue(result1.success, "Query 1 should succeed: ${result1.error}")
        assertTrue(result2.success, "Query 2 should succeed: ${result2.error}")
        assertEquals(result1.count(), result2.count())

        val log1 = result1.first()
        val log2 = result2.first()
        assertEquals(log1.traces.count(), log2.traces.count())

        fun traceCount(trace: XesTrace): Long =
            (trace.customAttributes["count(trace:concept:name)"] as? Number)?.toLong()
                ?: error("Missing count(trace:concept:name) for variant ${trace.events.map { it.conceptName }}")

        for ((trace1, trace2) in log1.traces zip log2.traces) {
            assertEquals(traceCount(trace1), traceCount(trace2))
            assertEquals(
                trace1.events.map { it.conceptName },
                trace2.events.map { it.conceptName },
                "Adding count(^e:name) should not change variant events",
            )
        }
    }

    @Test
    fun groupByWithAndWithoutHoistingAndOrderByCountTest() {
        // ProcessM: group by t:name, ^e:name → 101 trace groups (each trace name is unique)
        // count(t:name)=1 for every group since each trace name appears exactly once
        val result =
            q(
                "select l:name, count(t:name), e:name where l:logId='$journalLogId' group by t:name, ^e:name order by count(t:name) desc limit l:1",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertSelectedLogNameOnly(log, "JournalReview")
        assertEquals(101, log.traces.count(), "Should have 101 trace groups (one per unique trace name)")

        // Each trace group has count=1 since t:name is unique per trace
        for (trace in log.traces) {
            assertEquals(
                1L,
                (trace.customAttributes["count(trace:concept:name)"] as? Number)?.toLong()
                    ?: error("Missing count(trace:concept:name) for trace ${trace.conceptName}"),
                "Each trace group should have count=1",
            )
            assertTrue(trace.events.isNotEmpty(), "Each trace group should carry grouped event names")
        }
    }

    @Test
    fun groupByWithTwoLogs() {
        // ProcessM: where l:id in ($journal, $bpi) group by ^e:name
        val bpiLogId = testDataLoader.loadBPILog()
        assertNotNull(bpiLogId, "This ProcessM port requires BPI test data as a second log")

        val result =
            q(
                "select l:name, count(t:name), e:name where l:logId in ('$journalLogId', '$bpiLogId') group by ^e:name order by count(t:name) desc",
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(2, result.count(), "Should have 2 logs (JournalReview + BPI)")

        val logs = result.logs.toList()
        val journalLog =
            logs.singleOrNull { log ->
                log.traces.flatMap { trace -> trace.events }.any { event -> event.conceptName in eventNames }
            } ?: error("Expected one JournalReview result log, got: ${logs.map { it.conceptName }}")
        val bpiLog =
            logs.singleOrNull { log ->
                log.traces.flatMap { trace -> trace.events }.any { event -> event.conceptName in bpiEventNames }
            } ?: error("Expected one BPI result log, got: ${logs.map { it.conceptName }}")

        assertSelectedLogNameOnly(journalLog, "JournalReview")
        assertSelectedLogNameOnly(bpiLog)
        assertTrue(
            journalLog.traces
                .flatMap { trace -> trace.events }
                .all { event -> event.conceptName in eventNames },
            "JournalReview grouped variants must not contain BPI events",
        )
        assertTrue(
            bpiLog.traces
                .flatMap { trace -> trace.events }
                .all { event -> event.conceptName in bpiEventNames },
            "BPI grouped variants must not contain JournalReview events",
        )
    }

    @Test
    fun multiScopeGroupBy() {
        // ProcessM: select l:name, t:name, max(^e:timestamp)-min(^e:timestamp), e:name, count(e:name) group by t:name, e:name
        val result =
            q(
                "select l:name, t:name, max(^e:timestamp)-min(^e:timestamp), e:name, count(e:name) where l:logId='$journalLogId' group by t:name, e:name",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertSelectedLogNameOnly(log, "JournalReview")
        assertEquals(101, log.traces.count(), "Should have 101 traces (one per trace name)")

        for (trace in log.traces) {
            // Events are grouped by (t:name, e:name) — so each conceptName appears exactly once per trace
            assertNotNull(trace.conceptName, "Trace conceptName should be selected by t:name")
            assertNotNull(
                trace.customAttributes["max(^event:time:timestamp) - min(^event:time:timestamp)"],
                "Trace should carry the projected duration expression",
            )
            val eventsByName = trace.events.toList().groupBy { it.conceptName }
            for ((name, group) in eventsByName) {
                assertEquals(
                    1,
                    group.size,
                    "Events grouped by conceptName should appear exactly once per trace, but '$name' has ${group.size}",
                )
                assertEquals(name, group.first().conceptName)
                assertNotNull(
                    group.first().customAttributes["count(event:concept:name)"],
                    "Grouped event should carry count(event:concept:name)",
                )
            }
        }
    }

    @Test
    fun multiScopeImplicitGroupBy() {
        // ProcessM: select count(l:name), count(^t:name), count(^^e:name) where l:id=$journal
        // ProcessM: stream.first().customAttributes["count(log:concept:name)"] == 1
        //           stream.first().customAttributes["count(^trace:concept:name)"] == 101
        //           stream.first().customAttributes["count(^^event:concept:name)"] == 2298
        // In ProcessM, stream.first() is the Log component itself — so check log.customAttributes
        val result =
            q(
                "select count(l:name), count(^t:name), count(^^e:name) where l:logId='$journalLogId'",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have results")

        val log = result.first()
        // All three are log-scope aggregates (^ and ^^ hoist trace/event names to log level)
        val logCount = log.customAttributes["count(log:concept:name)"]
        val traceCount = log.customAttributes["count(^trace:concept:name)"]
        val eventCount = log.customAttributes["count(^^event:concept:name)"]
        assertNotNull(logCount, "count(l:name) should not be null")
        assertNotNull(traceCount, "count(^t:name) should not be null")
        assertNotNull(eventCount, "count(^^e:name) should not be null")
        assertEquals(1L, (logCount as Number).toLong(), "Should have 1 log")
        assertEquals(101L, (traceCount as Number).toLong(), "Should have 101 traces")
        assertEquals(TOTAL_EVENTS, (eventCount as Number).toLong(), "Should have $TOTAL_EVENTS events")
    }

    @Test
    fun orderByExpressionTest() {
        // ProcessM: select min(timestamp) where l:id=$journal group by ^e:name order by min(^e:timestamp)
        val result =
            q(
                "select min(timestamp) where l:logId='$journalLogId' group by ^e:name order by min(^e:timestamp)",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(97, log.traces.count(), "Should have 97 trace-variants (grouped by ^e:name)")

        var lastTimestamp = begin
        for (trace in log.traces) {
            val minTimestamp = trace.events.mapNotNull { it.customAttributes["min(event:time:timestamp)"] as? Instant }.minOrNull()
            assertNotNull(minTimestamp, "Each trace should have min(event:time:timestamp)")
            assertFalse(
                lastTimestamp.isAfter(minTimestamp),
                "Traces should be ordered by min(^e:timestamp) ASC: $lastTimestamp > $minTimestamp",
            )
            lastTimestamp = minTimestamp
        }
    }

    @Test
    fun missingAttributes() {
        // ProcessM: select l:name, t:name, min(^e:timestamp), max(^e:timestamp), max(^e:timestamp)-min(^e:timestamp)
        //           where l:id=$hospital group by t:name limit l:1, t:10
        val hospitalLogId = testDataLoader.loadHospitalLog()
        if (hospitalLogId == null) {
            println("Hospital dataset not available, skipping test")
            return
        }

        val result =
            q(
                "select l:name, t:name, min(^e:timestamp), max(^e:timestamp), max(^e:timestamp)-min(^e:timestamp) where l:logId='$hospitalLogId' group by t:name limit l:1, t:10",
                hospitalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(10, log.traces.count(), "Should have exactly 10 traces (limit t:10)")

        for (trace in log.traces) {
            assertNotNull(trace.conceptName, "Trace should have conceptName (t:name selected)")
            assertTrue(
                trace.customAttributes.keys.none { it == "concept:name" || it == "trace:concept:name" },
                "Selected t:name should not duplicate trace conceptName in custom attributes: ${trace.customAttributes}",
            )
            assertNotNull(
                trace.customAttributes["min(^event:time:timestamp)"],
                "min(^event:time:timestamp) should be present",
            )
            assertNotNull(
                trace.customAttributes["max(^event:time:timestamp)"],
                "max(^event:time:timestamp) should be present",
            )
            assertNotNull(
                trace.customAttributes["max(^event:time:timestamp) - min(^event:time:timestamp)"],
                "max - min duration expression should be present",
            )
        }
    }

    @Test
    fun orderByAggregationExpression() {
        // ProcessM: select max(^e:timestamp)-min(^e:timestamp) where l:id=$hospital
        //           group by t:name order by max(^e:timestamp)-min(^e:timestamp) desc
        val hospitalLogId = testDataLoader.loadHospitalLog()
        if (hospitalLogId == null) {
            println("Hospital dataset not available, skipping test")
            return
        }

        val result =
            q(
                "select max(^e:timestamp)-min(^e:timestamp) where l:logId='$hospitalLogId' group by t:name order by max(^e:timestamp)-min(^e:timestamp) desc",
                hospitalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertTrue(log.traces.count() > 10, "Should have more than 10 traces")

        var lastDurationSeconds = Double.MAX_VALUE
        for (trace in log.traces) {
            val durationAttr = trace.customAttributes["max(^event:time:timestamp) - min(^event:time:timestamp)"]
            assertNotNull(durationAttr, "Duration attribute should be present")
            // Neo4j returns IsoDuration; ProcessM returns Double (days). Convert to comparable seconds.
            val durationSeconds =
                when (durationAttr) {
                    is Number -> durationAttr.toDouble()
                    is org.neo4j.driver.types.IsoDuration -> durationAttr.seconds().toDouble() + durationAttr.nanoseconds() / 1e9
                    else -> throw IllegalStateException("Unexpected duration type: ${durationAttr::class}")
                }
            assertTrue(
                durationSeconds <= lastDurationSeconds,
                "Traces should be ordered by duration DESC: $durationSeconds > $lastDurationSeconds",
            )
            lastDurationSeconds = durationSeconds
        }
    }

    // =====================
    // Classifier Tests (from ProcessM)
    // =====================

    @Test
    fun groupScopeByClassifierTest() {
        // ProcessM: select [e:classifier:concept:name+lifecycle:transition] where l:id=$journal
        //           group by [^e:classifier:concept:name+lifecycle:transition]
        // Original expects: 97 traces, specific variant counts (1×count=3, 2×count=2, 94×count=1)
        val result =
            q(
                "select [e:classifier:concept:name+lifecycle:transition] where l:logId='$journalLogId' group by [^e:classifier:concept:name+lifecycle:transition]",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(97, log.traces.count(), "Should have 97 trace-variants")

        assertEquals(1, log.traces.count { it.count == 3 })
        assertEquals(2, log.traces.count { it.count == 2 })
        assertEquals(94, log.traces.count { it.count == 1 })

        val variant1 =
            listOf("inv", "inv", "get", "get", "get", "col", "col", "dec", "dec", "inv", "inv", "get", "rej", "rej")
        val variant2 = listOf("inv", "inv", "get", "get", "get", "col", "col", "dec", "dec", "acc", "acc")
        val variant3 =
            listOf(
                "inv", "inv", "get", "get", "tim", "col", "col", "dec", "dec", "inv", "inv", "tim", "inv",
                "inv", "tim", "inv", "inv", "get", "acc", "acc",
            )

        fun hasVariantWithPrefixes(
            count: Int,
            expectedPrefixes: List<String>,
        ): Boolean =
            log.traces
                .filter { it.count == count }
                .any { trace ->
                    trace.events
                        .map { event -> event.conceptName }
                        .zip(expectedPrefixes)
                        .all { (actual, expected) -> actual?.startsWith(expected) == true }
                }

        assertTrue(hasVariantWithPrefixes(3, variant1), "Missing count=3 classifier variant")
        assertTrue(hasVariantWithPrefixes(2, variant2), "Missing first count=2 classifier variant")
        assertTrue(hasVariantWithPrefixes(2, variant3), "Missing second count=2 classifier variant")

        for (trace in log.traces) {
            assertTrue(trace.events.count() > 0, "Each trace should have events")
        }
    }

    @Test
    fun errorHandlingTest() {
        // ProcessM: both queries throw IllegalArgumentException with "not found" and "classifier" in message
        val result1 = q("order by c:nonexistent", journalLogId)
        val result2 = q("group by [^c:nonstandard nonexisting]", journalLogId)

        listOf(result1, result2).forEach { result ->
            assertFalse(result.success, "Nonexistent classifier query should fail")
            assertTrue(
                result.error?.contains("not found", ignoreCase = true) == true,
                "Error should say classifier was not found: ${result.error}",
            )
            assertTrue(
                result.error?.contains("classifier", ignoreCase = true) == true,
                "Error should mention classifier: ${result.error}",
            )
        }
    }

    @Test
    fun invalidUseOfClassifiers() {
        // ProcessM: ClassifierInWhere — using classifier in WHERE clause should fail
        // ADAPTED_PORT: ProcessM exposes PQLSyntaxException.Problem.ClassifierInWhere; our application result
        // exposes the same semantic failure as an error string.
        val invalidResult =
            q(
                "where [e:classifier:concept:name+lifecycle:transition] in ('acceptcomplete', 'rejectcomplete') and l:logId='$journalLogId'",
                journalLogId,
            )
        assertFalse(invalidResult.success, "Classifier in WHERE should fail")
        assertTrue(
            invalidResult.error?.contains("classifier", ignoreCase = true) == true,
            "Error should mention classifier semantics: ${invalidResult.error}",
        )
        assertTrue(
            invalidResult.error?.contains("ClassifierInWhere", ignoreCase = true) == true ||
                invalidResult.error?.contains("not allowed in WHERE", ignoreCase = true) == true,
            "Error should preserve ClassifierInWhere semantics: ${invalidResult.error}",
        )

        // ProcessM: valid use of classifier in SELECT
        // ADAPTED_PORT: ProcessM stores selected concept:name in event.attributes; our XesEvent exposes it as the
        // typed conceptName field and keeps customAttributes empty to avoid duplicate XES attributes.
        val validResult =
            q(
                "select [e:c:Event Name] where l:logId='$journalLogId'",
                journalLogId,
            )
        assertTrue(validResult.success, "Valid classifier SELECT should succeed: ${validResult.error}")
        assertEquals(1, validResult.count())

        val log = validResult.first()
        assertEquals(101, log.traces.count(), "Should have 101 traces")
        for (trace in log.traces) {
            assertTrue(trace.events.count() >= 1, "Trace should have events")
            for (event in trace.events) {
                // Classifier [e:c:Event Name] resolves to concept:name → event.activity in Cypher.
                // Aliased as e_c_Event_Name → key "c_Event_Name" in attributes after split.
                assertTrue(event.conceptName in eventNames, "Event name should be in eventNames, got: ${event.conceptName}")
                assertNull(event.timeTimestamp, "Only classifier Event Name should be selected")
                assertNull(event.conceptInstance, "Only classifier Event Name should be selected")
                assertNull(event.costCurrency, "Only classifier Event Name should be selected")
                assertNull(event.costTotal, "Only classifier Event Name should be selected")
                assertNull(event.lifecycleState, "Only classifier Event Name should be selected")
                assertNull(event.lifecycleTransition, "Only classifier Event Name should be selected")
                assertNull(event.orgGroup, "Only classifier Event Name should be selected")
                assertNull(event.orgResource, "Only classifier Event Name should be selected")
                assertNull(event.orgRole, "Only classifier Event Name should be selected")
                assertNull(event.identityId, "Only classifier Event Name should be selected")
                assertTrue(event.customAttributes.isEmpty(), "Classifier Event Name should deduplicate to concept:name")
            }
        }
    }

    @Test
    fun duplicateAttributes() {
        // ProcessM: select e:name, [e:c:Event Name] where l:id=$journal
        // Both select the same underlying attribute (concept:name via classifier)
        // ADAPTED_PORT: ProcessM asserts one CONCEPT_NAME attribute in event.attributes. Our equivalent is exactly one
        // selected concept name exposed as event.conceptName and no duplicate entry in customAttributes.
        val result =
            q(
                "select e:name, [e:c:Event Name] where l:logId='$journalLogId'",
                journalLogId,
            )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(101, log.traces.count(), "Should have 101 traces")
        for (trace in log.traces) {
            assertTrue(trace.events.count() >= 1, "Trace should have events")
            for (event in trace.events) {
                assertTrue(
                    event.conceptName in eventNames,
                    "Event name should be in eventNames, got: ${event.conceptName}",
                )
                assertTrue(
                    event.customAttributes.isEmpty(),
                    "Selecting e:name and [e:c:Event Name] should deduplicate to one concept:name attribute",
                )
            }
        }
    }

    // =====================
    // Nested Attribute Tests (from ProcessM - require Hospital dataset)
    // =====================

    @Test
    fun readNestedAttributes() {
        val hospitalLogId =
            testDataLoader.loadHospitalLog() ?: run {
                error("Hospital dataset not available")
            }
        val result = q("where l:logId='$hospitalLogId' limit l:1, t:1, e:1", hospitalLogId)
        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        val attribute = nestedAttribute(log.customAttributes, "meta_concept:named_events_total")

        assertEquals(150291, attribute.value)
        with(attribute.children) {
            assertEquals(624, size)
            assertEquals(23, this["haptoglobine"])
            assertEquals(24, this["ijzer"])
            assertEquals(27, this["bekken"])
            assertEquals(2, this["cortisol"])
            assertEquals(9, this["ammoniak"])
        }
    }

    @Test
    @Disabled("Nested attribute skip flag not applicable — original uses nestedAttributes=false constructor param")
    fun skipNestedAttributes() {
        val hospitalLogId =
            testDataLoader.loadHospitalLog() ?: run {
                println("Hospital dataset not available, skipping")
                return
            }
        val result = q("where l:logId='$hospitalLogId' limit l:1, t:1, e:1", hospitalLogId)
        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have results")
    }

    @Test
    fun whereOnANestedAttribute() {
        val hospitalLogId = testDataLoader.loadHospitalLog() ?: error("Hospital dataset not available")
        val nestedGroupAverage = "\u001fsmeta_org:group_events_average\u001fMaternity ward"

        val result = q(
            "where (l:logId='$hospitalLogId' or l:logId='$journalLogId') " +
                "and [l:$nestedGroupAverage]='0.016' limit l:1, t:1, e:1",
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        val log = result.first()
        val attribute = nestedAttribute(log.customAttributes, "meta_org:group_events_average")
        assertEquals(1.728, (attribute.children["Pathology"] as Number).toDouble())
    }
}
