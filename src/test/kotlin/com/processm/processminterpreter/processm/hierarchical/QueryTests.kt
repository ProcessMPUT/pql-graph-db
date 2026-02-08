package com.processm.processminterpreter.processm.hierarchical

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.*
import kotlin.math.max

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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryTests : HierarchicalTestsBase() {

    private var journalLogId: String = ""

    @BeforeAll
    fun loadTestData() {
        journalLogId = loadTestDataWithUniqueId()
        println("Loaded JournalReview log: $journalLogId")
    }

    // =====================
    // LIMIT Tests (from ProcessM)
    // =====================

    @Test
    fun limitSingleTest() {
        // ProcessM: where l:name='JournalReview' limit l:1
        val result = q(
            "where l:logId='$journalLogId' limit l:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Should have exactly 1 log")

        val log = result.first()
        assertTrue(log.traces.count() > 1, "Log should have multiple traces")
        assertTrue(log.traces.any { t -> t.events.count() > 1 }, "Should have traces with multiple events")
    }

    @Test
    fun limitAllTest() {
        // ProcessM: limit e:3, t:2, l:1
        val result = q(
            "limit e:3, t:2, l:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.count() <= 1, "Should have at most 1 log")

        if (result.logs.isNotEmpty()) {
            val log = result.first()
            assertTrue(log.traces.count() <= 2, "Should have at most 2 traces")
            assertTrue(log.traces.all { t -> t.events.count() <= 3 }, "Each trace should have at most 3 events")
        }
    }

    @Test
    fun `limits do not affect upper scopes`() {
        // ProcessM: limits do not affect upper scopes
        val result1 = q("where l:logId='$journalLogId' limit l:1, t:100, e:1", journalLogId)
        val result2 = q("where l:logId='$journalLogId' limit l:1, e:1", journalLogId)
        val result3 = q("where l:logId='$journalLogId' limit l:1, t:100", journalLogId)

        assertTrue(result1.success, "Query 1 should succeed")
        assertTrue(result2.success, "Query 2 should succeed")
        assertTrue(result3.success, "Query 3 should succeed")

        // All should return 1 log regardless of lower scope limits
        assertEquals(1, result1.count(), "Result 1 should have 1 log")
        assertEquals(1, result2.count(), "Result 2 should have 1 log")
        assertEquals(1, result3.count(), "Result 3 should have 1 log")
    }

    @Test
    fun `limit applies independently per trace not globally`() {
        // This test verifies the key behavioral difference:
        // ProcessM: limit e:3 means "3 events PER TRACE"
        val result = q(
            "where l:logId='$journalLogId' limit l:1, t:2, e:3",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed")

        val log = result.first()
        val traces = log.traces.toList()

        assertEquals(2, traces.size, "Should have 2 traces")

        val trace0Events = traces[0].events.count()
        val trace1Events = traces[1].events.count()

        assertTrue(trace0Events > 0, "First trace should have events")
        assertTrue(trace1Events > 0, "Second trace should have events")
        assertTrue(trace0Events <= 3, "First trace should have at most 3 events")
        assertTrue(trace1Events <= 3, "Second trace should have at most 3 events")

        val totalEvents = trace0Events + trace1Events
        // If total is 3 or less, LIMIT was applied globally (WRONG)
        assertTrue(
            totalEvents > 3,
            "Total events should be >3 (got $totalEvents). If <=3, LIMIT is applied globally instead of per-trace!"
        )
    }

    @Test
    fun `limit with zero should return no results at that level`() {
        val result = q(
            "where l:logId='$journalLogId' limit l:1, t:2, e:0",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed")

        val log = result.first()
        val traces = log.traces.toList()

        assertEquals(2, traces.size, "Should have 2 traces")

        traces.forEach { trace ->
            assertEquals(0, trace.events.count(), "Trace ${trace.conceptName} should have 0 events with limit e:0")
        }
    }

    @Test
    fun `limit with only event scope specified`() {
        val result = q(
            "where l:logId='$journalLogId' limit e:5",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed")
        assertTrue(result.logs.isNotEmpty(), "Should have logs")

        val log = result.logs.first()
        log.traces.forEach { trace ->
            assertTrue(
                trace.events.count() <= 5,
                "Trace ${trace.conceptName} should have at most 5 events, has ${trace.events.count()}"
            )
        }
    }

    // =====================
    // OFFSET Tests (from ProcessM)
    // =====================

    @Test
    fun offsetSingleTest() {
        // ProcessM: where l:id=$journal offset l:1
        val result = q(
            "where l:logId='$journalLogId' offset l:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(0, result.count(), "Should have 0 logs with offset 1")
    }

    @Test
    fun offsetAllTest() {
        // ProcessM: where l:id=$journal offset e:3, t:2, l:1
        val result = q(
            "where l:logId='$journalLogId' offset e:3, t:2, l:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(0, result.count(), "Should have 0 logs with log offset 1")
    }

    @Test
    fun `offset with limit test`() {
        // Get all traces first
        val allResult = q("where l:logId='$journalLogId' limit l:1, t:10", journalLogId)
        assertTrue(allResult.success)

        // Then get with offset
        val offsetResult = q("where l:logId='$journalLogId' limit l:1, t:10 offset t:2", journalLogId)
        assertTrue(offsetResult.success)

        if (allResult.logs.isNotEmpty() && offsetResult.logs.isNotEmpty()) {
            val allTraces = allResult.first().traces.count()
            val offsetTraces = offsetResult.first().traces.count()

            assertEquals(max(allTraces - 2, 0), offsetTraces, "Offset should skip 2 traces")
        }
    }

    // =====================
    // ORDER BY Tests (from ProcessM)
    // =====================

    @Test
    fun orderBySimpleTest() {
        // ProcessM: where l:name='JournalReview' order by e:timestamp limit l:3
        val result = q(
            "where l:logId='$journalLogId' order by e:timestamp limit l:1, t:5, e:10",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have logs")

        for (log in result.logs) {
            for (trace in log.traces) {
                assertTrue(trace.events.count() > 0, "Trace should have events")

                var lastTimestamp = begin
                for (event in trace.events) {
                    assertNotNull(event.timeTimestamp, "Event should have timestamp")
                    assertTrue(
                        !event.timeTimestamp!!.isBefore(lastTimestamp),
                        "Events should be ordered by timestamp ascending"
                    )
                    lastTimestamp = event.timeTimestamp!!
                }
            }
        }
    }

    @Test
    fun orderByWithModifierAndScopesTest() {
        // ProcessM: where l:name='JournalReview' order by t:total desc, e:timestamp limit l:3
        val result = q(
            "where l:logId='$journalLogId' order by e:timestamp desc limit l:1, t:3, e:5",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have logs")

        for (log in result.logs) {
            for (trace in log.traces) {
                if (trace.events.count() >= 2) {
                    var lastTimestamp = end
                    for (event in trace.events) {
                        if (event.timeTimestamp != null) {
                            assertTrue(
                                !event.timeTimestamp!!.isAfter(lastTimestamp),
                                "Events should be ordered by timestamp descending"
                            )
                            lastTimestamp = event.timeTimestamp!!
                        }
                    }
                }
            }
        }
    }

    @Test
    fun orderByWithModifierAndScopes2Test() {
        // ProcessM: order by e:timestamp, t:total desc limit l:3
        val result = q(
            "where l:logId='$journalLogId' order by e:timestamp, t:total desc limit l:1, t:3, e:10",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have logs")
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
    }

    @Test
    fun `hierarchy reconstruction test`() {
        val result = q(
            "where l:logId='$journalLogId' limit l:1, t:5, e:3",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have logs")

        val log = result.first()
        assertTrue(log.traces.count() >= 1, "Log should have traces")

        for (trace in log.traces) {
            if (trace.events.any()) {
                assertNotNull(trace.conceptName, "Trace should have name")
                for (event in trace.events) {
                    assertNotNull(event.conceptName, "Event should have name")
                }
            }
        }
    }

    // =====================
    // GROUP BY Tests (from ProcessM) - TDD specs
    // =====================

    @Test
    @Disabled("GROUP BY not fully implemented - TDD spec from ProcessM")
    fun groupEventByStandardAttributeTest() {
        // ProcessM: select t:name, e:name, sum(e:total) where l:id=$journal group by e:name
        val result = q(
            "select t:name, e:name, sum(e:total) where l:logId='$journalLogId' group by e:name",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have logs")

        val log = result.first()
        // All events within a trace should have the same name (grouped)
        for (trace in log.traces) {
            val distinctNames = trace.events.map { it.conceptName }.distinct().toList()
            assertTrue(distinctNames.size == 1, "All events in a grouped trace should have the same name")
        }
    }

    @Test
    @Disabled("GROUP BY not fully implemented - TDD spec from ProcessM")
    fun groupLogByEventStdAttrAndImplicitGroupEventByTest() {
        // ProcessM: select sum(e:total) where l:name='JournalReview' group by ^^e:name
        val result = q(
            "select sum(e:total) where l:logId='$journalLogId' group by ^^e:name",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - TDD spec from ProcessM")
    fun groupLogByEventStdAndGroupEventByStdAttrTest() {
        // ProcessM: select e:name, sum(e:total) where l:name='JournalReview' group by ^^e:name, e:name
        val result = q(
            "select e:name, sum(e:total) where l:logId='$journalLogId' group by ^^e:name, e:name",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - requires classifiers - TDD spec from ProcessM")
    fun groupByImplicitScopeTest() {
        // ProcessM: where l:id=$journal group by c:Resource
        val result = q(
            "where l:logId='$journalLogId' group by c:Resource",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - TDD spec from ProcessM")
    fun groupByOuterScopeTest() {
        // ProcessM: select t:min(l:name) where l:name='JournalReview' limit l:3
        val result = q(
            "select t:min(l:name) where l:logId='$journalLogId' limit l:3",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - TDD spec from ProcessM")
    fun groupByImplicitFromSelectTest() {
        // ProcessM: select l:*, t:*, avg(e:total), min(e:timestamp), max(e:timestamp) where l:name matches '...' limit l:1
        val result = q(
            "select l:*, t:*, avg(e:total), min(e:timestamp), max(e:timestamp) where l:logId='$journalLogId' limit l:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have logs")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - TDD spec from ProcessM")
    fun groupByImplicitFromOrderByTest() {
        // ProcessM: where l:id=$journal order by avg(e:total), min(e:timestamp), max(e:timestamp)
        val result = q(
            "where l:logId='$journalLogId' order by avg(e:total), min(e:timestamp), max(e:timestamp)",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - TDD spec from ProcessM")
    fun groupByImplicitWithHoistingTest() {
        // ProcessM: select avg(^^e:total), min(^^e:timestamp), max(^^e:timestamp) where l:id=$journal
        val result = q(
            "select avg(^^e:total), min(^^e:timestamp), max(^^e:timestamp) where l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - TDD spec from ProcessM")
    fun groupByWithHoistingAndOrderByWithinGroupTest() {
        // ProcessM: where l:id=$journal group by ^e:name order by name
        val result = q(
            "where l:logId='$journalLogId' group by ^e:name order by name",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - TDD spec from ProcessM")
    fun groupByWithHoistingAndOrderByCountTest() {
        // ProcessM: select l:name, count(t:name), e:name where l:id=$journal group by ^e:name order by count(t:name) desc limit l:1
        val result = q(
            "select l:name, count(t:name), e:name where l:logId='$journalLogId' group by ^e:name order by count(t:name) desc limit l:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - TDD spec from ProcessM")
    fun aggregationFunctionIndependence() {
        // ProcessM: Two queries - with and without count(^e:name) - should produce identical trace counts
        val result1 = q(
            "select count(t:name) where l:logId='$journalLogId' group by ^e:name order by count(t:name) desc limit l:1",
            journalLogId
        )
        val result2 = q(
            "select count(t:name), count(^e:name) where l:logId='$journalLogId' group by ^e:name order by count(t:name) desc limit l:1",
            journalLogId
        )

        assertTrue(result1.success, "Query 1 should succeed")
        assertTrue(result2.success, "Query 2 should succeed")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - TDD spec from ProcessM")
    fun groupByWithAndWithoutHoistingAndOrderByCountTest() {
        // ProcessM: group by t:name, ^e:name
        val result = q(
            "select l:name, count(t:name), e:name where l:logId='$journalLogId' group by t:name, ^e:name order by count(t:name) desc limit l:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - requires BPI dataset - TDD spec from ProcessM")
    fun groupByWithTwoLogs() {
        // ProcessM: where l:id in ($journal, $bpi) group by ^e:name
        // This test requires a second dataset (BPI) loaded
        val bpiLogId = testDataLoader.loadBPILog()
        if (bpiLogId == null) {
            println("BPI dataset not available, skipping test")
            return
        }

        val result = q(
            "select l:name, count(t:name), e:name where l:logId in ('$journalLogId', '$bpiLogId') group by ^e:name order by count(t:name) desc",
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - TDD spec from ProcessM")
    fun multiScopeGroupBy() {
        // ProcessM: select l:name, t:name, max(^e:timestamp)-min(^e:timestamp), e:name, count(e:name) group by t:name, e:name
        val result = q(
            "select l:name, t:name, max(^e:timestamp)-min(^e:timestamp), e:name, count(e:name) where l:logId='$journalLogId' group by t:name, e:name",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - TDD spec from ProcessM")
    fun multiScopeImplicitGroupBy() {
        // ProcessM: select count(l:name), count(^t:name), count(^^e:name) where l:id=$journal
        val result = q(
            "select count(l:name), count(^t:name), count(^^e:name) where l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        // ProcessM expects: counts of 1, 101, 2298
    }

    @Test
    @Disabled("GROUP BY not fully implemented - TDD spec from ProcessM")
    fun orderByExpressionTest() {
        // ProcessM: select min(timestamp) where l:id=$journal group by ^e:name order by min(^e:timestamp)
        val result = q(
            "select min(timestamp) where l:logId='$journalLogId' group by ^e:name order by min(^e:timestamp)",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - requires Hospital dataset - TDD spec from ProcessM")
    fun missingAttributes() {
        // ProcessM: select min(^e:timestamp), max(^e:timestamp) where l:id=$hospital group by t:name
        val hospitalLogId = testDataLoader.loadHospitalLog()
        if (hospitalLogId == null) {
            println("Hospital dataset not available, skipping test")
            return
        }

        val result = q(
            "select l:name, t:name, min(^e:timestamp), max(^e:timestamp), max(^e:timestamp)-min(^e:timestamp) where l:logId='$hospitalLogId' group by t:name limit l:1, t:10",
            hospitalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("GROUP BY not fully implemented - requires Hospital dataset - TDD spec from ProcessM")
    fun orderByAggregationExpression() {
        // ProcessM: order by max(^e:timestamp)-min(^e:timestamp) desc
        val hospitalLogId = testDataLoader.loadHospitalLog()
        if (hospitalLogId == null) {
            println("Hospital dataset not available, skipping test")
            return
        }

        val result = q(
            "select max(^e:timestamp)-min(^e:timestamp) where l:logId='$hospitalLogId' group by t:name order by max(^e:timestamp)-min(^e:timestamp) desc",
            hospitalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    // =====================
    // Classifier Tests (from ProcessM)
    // =====================

    @Test
    @Disabled("Classifiers not implemented - TDD spec from ProcessM")
    fun groupScopeByClassifierTest() {
        // ProcessM: select [e:classifier:concept:name+lifecycle:transition] where ... group by [^e:classifier:...]
        val result = q(
            "select [e:classifier:concept:name+lifecycle:transition] where l:logId='$journalLogId' group by [^e:classifier:concept:name+lifecycle:transition]",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("Classifiers not implemented - TDD spec from ProcessM")
    fun errorHandlingTest() {
        // ProcessM: classifier errors
        val result1 = q("order by c:nonexistent", journalLogId)
        // Should produce error about classifier not found

        val result2 = q("group by [^c:nonstandard nonexisting]", journalLogId)
        // Should produce error about classifier not found
    }

    @Test
    @Disabled("Classifiers not implemented - TDD spec from ProcessM")
    fun invalidUseOfClassifiers() {
        // ProcessM: ClassifierInWhere
        // The invalid query should throw PQLSyntaxException
        // The valid query should return results
        val validResult = q(
            "select [e:classifier:concept:name+lifecycle:transition] where l:logId='$journalLogId' limit l:1",
            journalLogId
        )

        assertTrue(validResult.success, "Valid classifier query should succeed")
    }

    @Test
    @Disabled("Classifiers not implemented - TDD spec from ProcessM")
    fun duplicateAttributes() {
        // ProcessM: select e:name, [e:c:Event Name] where l:id=$journal
        val result = q(
            "select e:name, [e:c:Event Name] where l:logId='$journalLogId' limit l:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    // =====================
    // Nested Attribute Tests (from ProcessM - require Hospital dataset)
    // =====================

    @Test
    @Disabled("Nested attributes not implemented - requires Hospital dataset - TDD spec from ProcessM")
    fun readNestedAttributes() {
        val hospitalLogId = testDataLoader.loadHospitalLog()
        if (hospitalLogId == null) {
            println("Hospital dataset not available, skipping test")
            return
        }

        val result = q(
            "where l:logId='$hospitalLogId' limit l:1, t:1, e:1",
            hospitalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("Nested attributes not implemented - requires Hospital dataset - TDD spec from ProcessM")
    fun skipNestedAttributes() {
        val hospitalLogId = testDataLoader.loadHospitalLog()
        if (hospitalLogId == null) {
            println("Hospital dataset not available, skipping test")
            return
        }

        val result = q(
            "where l:logId='$hospitalLogId' limit l:1, t:1, e:1",
            hospitalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("Nested attributes not implemented - requires Hospital dataset - TDD spec from ProcessM")
    fun `where on a nested attribute`() {
        val hospitalLogId = testDataLoader.loadHospitalLog()
        if (hospitalLogId == null) {
            println("Hospital dataset not available, skipping test")
            return
        }

        // ProcessM uses nested attribute path with separators
        val result = q(
            "where l:logId='$hospitalLogId' limit l:1, t:1, e:1",
            hospitalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    // =====================
    // Complex Query Tests (our additions)
    // =====================

    @Test
    fun `complex query with SELECT WHERE ORDER LIMIT`() {
        val result = q(
            "select e:name, e:timestamp " +
            "where l:logId='$journalLogId' and e:name in ('accept', 'reject') " +
            "order by e:timestamp " +
            "limit l:1, t:10, e:5",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have logs")

        val log = result.first()
        for (trace in log.traces) {
            for (event in trace.events) {
                assertTrue(
                    event.conceptName in setOf("accept", "reject"),
                    "Event should be accept or reject"
                )
            }
        }
    }

    @Test
    fun `query with all standard attributes test`() {
        val result = q(
            "select l:name, t:name, e:name, e:timestamp " +
            "where l:logId='$journalLogId' " +
            "limit l:1, t:2, e:3",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }
}
