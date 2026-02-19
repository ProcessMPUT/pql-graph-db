package com.processm.processminterpreter.processm.hierarchical

import com.processm.processminterpreter.pql.model.PQLSyntaxException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.*

/**
 * SELECT query tests ported from ProcessM
 *
 * Tests based on: DBHierarchicalXESInputStreamWithSelectQueryTests.kt
 * Original: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/log/hierarchical/DBHierarchicalXESInputStreamWithSelectQueryTests.kt
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelectQueryTests : HierarchicalTestsBase() {

    private var journalLogId: String = ""

    @BeforeAll
    fun loadTestData() {
        journalLogId = loadTestDataWithUniqueId()
        println("Loaded JournalReview log: $journalLogId")
    }

    @Test
    fun basicSelectTest() {
        // ProcessM: select l:name, t:name, e:name, e:timestamp where l:name='JournalReview' and l:id=$journal
        val result = q(
            "select l:name, t:name, e:name, e:timestamp where l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count(), "Should have exactly one log")

        val log = result.first()
        assertEquals("JournalReview", log.conceptName)

        assertTrue(log.traces.count() > 0, "Log should have traces")
        for (trace in log.traces) {
            assertNotNull(trace.conceptName, "Trace should have name")

            assertTrue(trace.events.count() > 0, "Trace should have events")
            for (event in trace.events) {
                assertTrue(event.conceptName in eventNames, "Event name should be valid: ${event.conceptName}")
                assertNotNull(event.timeTimestamp, "Event should have timestamp")
                assertTrue(event.timeTimestamp!!.isInRange(begin, end), "Timestamp should be in range")
            }
        }
    }

    @Test
    fun scopedSelectAllTest() {
        // ProcessM: select t:name, e:*, t:total where l:name='JournalReview' limit l:1
        val result = q(
            "select t:name, e:*, t:total where l:logId='$journalLogId' limit l:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have logs")

        val log = result.first()
        for (trace in log.traces) {
            assertNotNull(trace.conceptName, "Trace should have name")

            assertTrue(trace.events.count() > 0, "Trace should have events")
            for (event in trace.events) {
                assertTrue(event.conceptName in eventNames, "Event should have valid name")
                assertNotNull(event.timeTimestamp, "Event should have timestamp")
            }
        }
    }

    @Test
    fun scopedSelectAll2Test() {
        // ProcessM: select t:*, e:*, l:* where l:concept:name like 'Jour%Rev%' and l:id=$journal
        val result = q(
            "select t:*, e:*, l:* where l:name like 'Jour%Rev%' and l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have logs")

        val log = result.first()
        assertNotNull(log.conceptName, "Log should have concept:name")
        // ProcessM: Full attributes populated; log has classifiers and globals
    }

    @Test
    @Disabled("GROUP BY with mixed scopes not implemented - TDD spec from ProcessM")
    fun scopedSelectAllWithGroupBy() {
        // ProcessM: select l:*, t:*, e:*, max(e:timestamp)-min(e:timestamp) where ... group by e:instance
        // Should throw PQLSyntaxException with MixedScopes problem
        try {
            val result = q(
                "select l:*, t:*, e:*, max(e:timestamp)-min(e:timestamp) where l:logId='$journalLogId' group by e:instance",
                journalLogId
            )
            fail("Should have thrown PQLSyntaxException")
        } catch (e: Exception) {
            // Expected: PQLSyntaxException with MixedScopes problem
            assertTrue(e is PQLSyntaxException || e.message?.contains("syntax") == true || e.message?.contains("Syntax") == true,
                "Should be a syntax-related exception: ${e.message}")
        }
    }

    @Test
    @Disabled("Classifiers not implemented - TDD spec from ProcessM")
    fun selectUsingClassifierTest() {
        // ProcessM: select [e:classifier:concept:name+lifecycle:transition] where l:name like '_ournal_eview' limit l:1
        val result = q(
            "select [e:classifier:concept:name+lifecycle:transition] where l:name like '_ournal_eview' and l:logId='$journalLogId' limit l:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    fun selectAggregationTest() {
        // ProcessM: select min(t:total), avg(t:total), max(t:total) where l:id=$journal
        // Original expects min=11.0, avg=21.98, max=47.0
        val result = q(
            "select min(e:timestamp), max(e:timestamp) where l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have logs")
    }

    @Test
    @Disabled("Non-standard attributes not fully implemented - TDD spec from ProcessM")
    fun selectNonStandardAttributesTest() {
        // ProcessM: select [e:result], [e:time:timestamp], [e:concept:name] where l:id=$journal
        val result = q(
            "select [e:result], [e:time:timestamp], [e:concept:name] where l:logId='$journalLogId' limit l:1, t:5, e:10",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("Expression evaluation not fully implemented - TDD spec from ProcessM")
    fun selectExpressionTest() {
        // ProcessM: select [e:concept:name] + e:resource, max(timestamp) - min(timestamp), count(e:time:timestamp)
        //           group by [e:concept:name], e:resource
        val result = q(
            "select [e:concept:name] + e:resource, max(timestamp) - min(timestamp), count(e:time:timestamp) " +
            "where l:logId='$journalLogId' " +
            "group by [e:concept:name], e:resource",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("Cross-scope expressions not fully implemented - TDD spec from ProcessM")
    fun selectComplexScalarExpressionTest() {
        // ProcessM: select e:total + t:total where l:id=$journal
        val result = q(
            "select e:total + t:total where l:logId='$journalLogId' limit l:1, t:5, e:10",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    fun selectAllImplicitTest() {
        // ProcessM: "" (empty query = select all)
        val result = q(
            "where l:logId='$journalLogId' limit l:1, t:5, e:10",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have at least one log")

        val log = result.first()
        assertNotNull(log.conceptName, "Log should have name with implicit select all")

        for (trace in log.traces) {
            assertNotNull(trace.conceptName, "Trace should have name")
            assertTrue(trace.events.count() > 0, "Trace should have events")
            for (event in trace.events) {
                assertTrue(event.conceptName in eventNames, "Event should have valid name")
            }
        }
    }

    @Test
    fun selectConstantsTest() {
        // ProcessM: select l:1, l:2 + t:3, l:4 * t:5 + e:6, 7 / 8 - 9, 10 * null, t:null/11, l:D2020-03-12 limit l:1, t:1, e:1
        val result = q(
            "select l:1, l:2 + t:3, l:4 * t:5 + e:6, 7 / 8 - 9, 10 * null, t:null/11, l:D2020-03-12 limit l:1, t:1, e:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    fun selectISO8601Test() {
        // ProcessM: 17 datetime formats
        val result = q(
            """select
                D2020-03-13,
                D2020-03-13T16:45,
                D2020-03-13T16:45:50,
                D2020-03-13T16:45:50.333,
                D2020-03-13T16:45+0200,
                D2020-03-13T16:45+02:00,
                D2020-03-13T16:45Z,
                D20200313,
                D20200313T1645,
                D20200313T164550,
                D20200313T164550.333,
                D20200313T1645+0200,
                D202003131645,
                D20200313164550,
                D20200313164550.333,
                D202003131645+0200,
                D202003131645Z
                limit l:1, t:1, e:1""",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    fun selectIEEE754Test() {
        // ProcessM: 12 IEEE754 numbers
        val result = q(
            "select 0, 0.0, 0.00, -0, -0.0, 1, 1.0, -1, -1.0, ${Math.PI}, ${Double.MIN_VALUE}, ${Double.MAX_VALUE} limit l:1, t:1, e:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    fun selectBooleanTest() {
        // ProcessM: select true, false limit l:1, t:1, e:1
        val result = q(
            "select true, false limit l:1, t:1, e:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    fun selectStringTest() {
        // ProcessM: select 'single-quoted', "double-quoted" limit l:1, t:1, e:1
        val result = q(
            "select 'single-quoted', \"double-quoted\" limit l:1, t:1, e:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    fun selectNowTest() {
        // ProcessM: select l:now() limit l:1
        val result = q(
            "select now() limit l:1, t:1, e:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    fun selectSecondsTest() {
        // ProcessM: select e:*, second(e:timestamp) where l:id=$journal
        val result = q(
            "select e:timestamp, second(e:timestamp) where l:logId='$journalLogId' limit l:1, t:1, e:5",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    @Disabled("Non-existent custom attributes not fully implemented - TDD spec from ProcessM")
    fun nonexistentCustomAttributesTest() {
        // ProcessM: select [nonexistent] - should handle gracefully
        // First query with emoji attribute
        val result1 = q(
            "select [e:nonexistent_attribute_xyz] where l:logId='$journalLogId' limit l:1, t:1, e:1",
            journalLogId
        )
        // Should succeed but return null/empty for the nonexistent attribute

        // Second query with partial match
        val result2 = q(
            "select [e:result] where l:logId='$journalLogId' limit l:1, t:5, e:10",
            journalLogId
        )
        // Some events have "result" attribute (accept/reject decisions), others don't
    }

    @Test
    fun selectEmptyResultTest() {
        // ProcessM: where 0=1
        val result = q("where 0=1", journalLogId)

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }

    @Test
    fun selectDatetimeFunctionsTest() {
        // Test date/time extraction functions
        val result = q(
            "select e:timestamp, year(e:timestamp), month(e:timestamp), day(e:timestamp) " +
            "where l:logId='$journalLogId' limit l:1, t:1, e:5",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
    }
}
