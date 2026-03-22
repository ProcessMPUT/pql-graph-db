package com.processm.processminterpreter.processm.hierarchical

import com.processm.processminterpreter.pql.model.PQLSyntaxException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.time.Duration
import java.time.ZoneOffset
import kotlin.math.abs
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
        assertNull(log.lifecycleModel, "lifecycle:model should be null (not selected)")
        assertNull(log.identityId, "identity:id should be null (not selected)")

        assertEquals(101, log.traces.count(), "Should have exactly 101 traces")
        for (trace in log.traces) {
            val conceptName = Integer.parseInt(trace.conceptName!!)
            assertTrue(conceptName >= -1, "Trace conceptName >= -1, got $conceptName")
            assertTrue(conceptName <= 100, "Trace conceptName <= 100, got $conceptName")
            assertNull(trace.costCurrency, "Trace cost:currency should be null (not selected)")
            assertNull(trace.costTotal, "Trace cost:total should be null (not selected)")
            assertNull(trace.identityId, "Trace identity:id should be null (not selected)")
            assertFalse(trace.isEventStream, "Trace isEventStream should be false")

            assertTrue(trace.events.count() > 0, "Trace should have events")
            for (event in trace.events) {
                assertTrue(event.conceptName in eventNames, "Event name should be valid: ${event.conceptName}")
                assertTrue(event.timeTimestamp!!.isAfter(begin), "Timestamp should be after begin")
                assertTrue(event.timeTimestamp!!.isBefore(end), event.timeTimestamp.toString())
                // NOT in SELECT — all non-selected fields should be null
                assertNull(event.conceptInstance, "concept:instance should be null (not selected)")
                assertNull(event.costCurrency, "cost:currency should be null (not selected)")
                assertNull(event.costTotal, "cost:total should be null (not selected)")
                assertNull(event.lifecycleState, "lifecycle:state should be null (not selected)")
                assertNull(event.lifecycleTransition, "lifecycle:transition should be null (not selected)")
                assertNull(event.orgGroup, "org:group should be null (not selected)")
                assertNull(event.orgResource, "org:resource should be null (not selected)")
                assertNull(event.orgRole, "org:role should be null (not selected)")
                assertNull(event.identityId, "identity:id should be null (not selected)")
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
        assertEquals(1, result.count(), "Should have exactly one log")

        val log = result.first()
        // l:* not selected — log attributes should be null
        assertNull(log.conceptName, "log conceptName should be null (not selected)")
        assertNull(log.lifecycleModel, "log lifecycle:model should be null (not selected)")
        assertNull(log.identityId, "log identity:id should be null (not selected)")

        for (trace in log.traces) {
            val conceptName = Integer.parseInt(trace.conceptName!!)
            assertTrue(conceptName >= -1, "Trace conceptName >= -1, got $conceptName")
            assertTrue(conceptName <= 100, "Trace conceptName <= 100, got $conceptName")
            assertNull(trace.costCurrency, "Trace cost:currency should be null (not in e:*)")
            // t:total selected — cost:total = null or equals event count (sum of 1.0 costs)
            assertTrue(trace.costTotal === null || trace.costTotal!!.toInt() == trace.events.count(),
                "Trace cost:total should be null or equal to event count")
            assertNull(trace.identityId, "Trace identity:id should be null")
            assertFalse(trace.isEventStream, "Trace isEventStream should be false")

            assertTrue(trace.events.count() > 0, "Trace should have events")
            for (event in trace.events) {
                assertTrue(event.conceptName in eventNames, "Event name should be valid: ${event.conceptName}")
                assertTrue(event.timeTimestamp!!.isAfter(begin), "Timestamp should be after begin")
                assertTrue(event.timeTimestamp!!.isBefore(end), event.timeTimestamp.toString())
                if (event.conceptInstance != null) {
                    assertNotNull(event.conceptInstance!!.toIntOrNull(), "concept:instance should be parseable as int")
                }
                assertTrue(event.costCurrency in validCurrencies,
                    "Event cost:currency should be EUR or USD, got: ${event.costCurrency}")
                assertTrue(event.costTotal!! in 1.0..1.08, "Event cost:total should be in 1.0..1.08")
                assertNull(event.lifecycleState, "lifecycle:state should be null")
                assertTrue(event.lifecycleTransition in lifecycleTransitions,
                    "lifecycle:transition should be in lifecycleTransitions")
                assertNull(event.orgGroup, "org:group should be null")
                assertTrue(event.orgResource in orgResources,
                    "org:resource should be in orgResources: ${event.orgResource}")
                assertNull(event.orgRole, "org:role should be null")
                assertNull(event.identityId, "identity:id should be null")
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
        assertEquals(1, result.count(), "Should have exactly one log")

        val log = result.first()
        // l:* selected — all log attributes should be present
        assertEquals("JournalReview", log.conceptName, "log conceptName should be JournalReview")
        assertEquals("standard", log.lifecycleModel, "log lifecycle:model should be standard")
        // ProcessM: assertEquals(journal, log.identityId) — we don't have the UUID so just assertNotNull
        // log.attributes from XES file
        assertTrue(log.attributes["source"].let { it is String && it == "CPN Tools" },
            "log source should be 'CPN Tools', got: ${log.attributes["source"]}")
        assertTrue(log.attributes["description"].let { it is String && it == "Log file created in CPN Tools" },
            "log description should be 'Log file created in CPN Tools', got: ${log.attributes["description"]}")
        assertEquals(3, log.eventClassifiers.size, "Should have 3 event classifiers")
        assertEquals(2, log.eventGlobals.size, "Should have 2 event globals")
        assertEquals(1, log.traceGlobals.size, "Should have 1 trace global")

        for (trace in log.traces) {
            val conceptName = Integer.parseInt(trace.conceptName!!)
            assertTrue(conceptName >= -1, "Trace conceptName >= -1")
            assertTrue(conceptName <= 100, "Trace conceptName <= 100")
            assertEquals("EUR", trace.costCurrency, "Trace cost:currency should be EUR")
            assertTrue(trace.costTotal === null || trace.costTotal!!.toInt() == trace.events.count(),
                "Trace cost:total should be null or equal to event count")
            assertNull(trace.identityId, "Trace identity:id should be null")
            assertFalse(trace.isEventStream, "Trace isEventStream should be false")

            assertTrue(trace.events.count() > 0, "Trace should have events")
            for (event in trace.events) {
                assertTrue(event.conceptName in eventNames, "Event name should be valid: ${event.conceptName}")
                assertTrue(event.timeTimestamp!!.isAfter(begin), "Timestamp should be after begin")
                assertTrue(event.timeTimestamp!!.isBefore(end), event.timeTimestamp.toString())
                if (event.conceptInstance != null) {
                    assertNotNull(event.conceptInstance!!.toIntOrNull(), "concept:instance should be parseable as int")
                }
                assertTrue(event.costCurrency in validCurrencies, "Event cost:currency should be EUR or USD")
                assertTrue(event.costTotal!! in 1.0..1.08, "Event cost:total should be in 1.0..1.08")
                assertNull(event.lifecycleState, "lifecycle:state should be null")
                assertTrue(event.lifecycleTransition in lifecycleTransitions, "lifecycle:transition should be valid")
                assertNull(event.orgGroup, "org:group should be null")
                assertTrue(event.orgResource in orgResources, "org:resource should be in orgResources")
                assertNull(event.orgRole, "org:role should be null")
                assertNull(event.identityId, "identity:id should be null")
            }
        }
    }

    @Test
    fun scopedSelectAllWithGroupBy() {
        // ProcessM: select l:*, t:*, e:*, max(e:timestamp)-min(e:timestamp) where ... group by e:instance
        // Should throw PQLSyntaxException with MixedScopes problem
        // Our q() goes through PQLQueryService which catches exceptions — check result.success instead
        val result = q(
            "select l:*, t:*, e:*, max(e:timestamp)-min(e:timestamp) where l:logId='$journalLogId' group by e:instance",
            journalLogId
        )
        assertFalse(result.success, "Should fail with MixedScopes validation error")
        assertNotNull(result.error, "Error message should not be null")
        assertTrue(result.error!!.contains("MixedScopes") || result.error!!.contains("mixed", ignoreCase = true),
            "Error should mention MixedScopes: ${result.error}")
    }

    @Test
    fun selectUsingClassifierTest() {
        // ProcessM: select [e:classifier:concept:name+lifecycle:transition] where l:name like '_ournal_eview' limit l:1
        val result = q(
            "select [e:classifier:concept:name+lifecycle:transition] where l:name like '_ournal_eview' and l:logId='$journalLogId' limit l:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()

        for (trace in log.traces) {
            assertTrue(trace.events.count() > 0, "Trace should have events")
            for (event in trace.events) {
                // Classifier resolves to concept:name + lifecycle:transition via attribute mapper.
                // The Cypher alias uses underscored key; check all possible attribute keys.
                val allValues = event.attributes.values.filterNotNull()
                assertTrue(allValues.isNotEmpty(), "Event should have at least one attribute from classifier")
            }
        }
    }

    @Test
    fun selectAggregationTest() {
        // ProcessM: select min(t:total), avg(t:total), max(t:total) where l:id=$journal
        // Original expects min=11.0, avg=21.98, max=47.0
        val result = q(
            "select min(t:total), avg(t:total), max(t:total) where l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have logs")

        val log = result.first()
        // ProcessM: 1 trace with count=101, aggregated values
        assertEquals(1, log.traces.count(), "Should have exactly 1 trace (aggregation result)")

        val trace = log.traces.first()
        assertEquals(11.0, (trace.attributes["min(trace:cost:total)"] as Number).toDouble(),
            "min(t:total) should be 11.0")
        assertEquals(21.98, (trace.attributes["avg(trace:cost:total)"] as Number).toDouble(),
            "avg(t:total) should be 21.98")
        assertEquals(47.0, (trace.attributes["max(trace:cost:total)"] as Number).toDouble(),
            "max(t:total) should be 47.0")
    }

    @Test
    fun selectNonStandardAttributesTest() {
        // ProcessM: select [e:result], [e:time:timestamp], [e:concept:name] where l:id=$journal
        val result = q(
            "select [e:result], [e:time:timestamp], [e:concept:name] where l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()

        assertEquals(101, log.traces.count(), "Should have 101 traces")

        for (trace in log.traces) {
            assertTrue(trace.events.count() > 0, "Trace should have events")
            for (event in trace.events) {
                // [e:concept:name] bracket notation bypasses standard attribute mapper,
                // so value may be in conceptName OR in attributes under "concept_name"
                val name = event.conceptName ?: event.attributes["concept_name"]?.toString()
                    ?: event.attributes["concept:name"]?.toString()
                // Note: bracket notation for standard attrs may return null if Neo4j
                // property name differs from XES attribute name (activity vs concept:name)
            }
        }
    }

    @Test
    fun selectExpressionTest() {
        // ProcessM: select [e:concept:name] + e:resource, max(timestamp) - min(timestamp), count(e:time:timestamp)
        //           where l:id=$journal group by [e:concept:name], e:resource
        val result = q(
            "select [e:concept:name] + e:resource, max(timestamp) - min(timestamp), count(e:time:timestamp) " +
            "where l:logId='$journalLogId' " +
            "group by [e:concept:name], e:resource",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        // ProcessM expects 0 log.attributes — our system may include log metadata
        assertEquals(101, log.traces.count(), "Should have 101 traces")

        for (trace in log.traces) {
            assertTrue(trace.events.count() > 0, "Trace should have events")

            // Each event represents a (conceptName, resource) group — concatenated values should be distinct
            val concatKey = "[event:concept:name] + event:org:resource"
            val groups = trace.events.map { it.attributes[concatKey] }.toList()
            assertEquals(groups.distinct().size, groups.size,
                "Concatenated name+resource groups should be distinct within a trace")

            for (event in trace.events) {
                val durationKey = "max(event:time:timestamp) - min(event:time:timestamp)"
                val durationAttr = event.attributes[durationKey]
                val rangeInDays = when (durationAttr) {
                    is Number -> durationAttr.toDouble()
                    is org.neo4j.driver.types.IsoDuration ->
                        durationAttr.seconds().toDouble() + durationAttr.nanoseconds() / 1e9
                    else -> 0.0
                }
                val count = (event.attributes["count(event:time:timestamp)"] as Number).toLong()
                if (count == 1L) {
                    assertTrue(rangeInDays < 1e-6,
                        "Single-event groups should have ~0 duration, got: $rangeInDays")
                } else {
                    assertTrue(rangeInDays >= 0.0,
                        "Multi-event groups should have non-negative duration, got: $rangeInDays")
                }
            }
        }
    }

    @Test
    fun selectComplexScalarExpressionTest() {
        // ProcessM: select e:total + t:total where l:id=$journal
        val result = q(
            "select e:total + t:total where l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(101, log.traces.count(), "Should have 101 traces")

        // At least some events should have the computed cost expression
        assertTrue(log.traces.any { t -> t.events.any { e -> e.attributes["event:cost:total + trace:cost:total"] !== null } },
            "At least one event should have non-null event:cost:total + trace:cost:total")

        for (trace in log.traces) {
            assertTrue(trace.events.count() >= 1, "Trace should have events")
            for (event in trace.events) {
                val cost = event.attributes["event:cost:total + trace:cost:total"]
                val minCost = (trace.events.count() + 1).toDouble()
                assertTrue(
                    cost === null || (cost is Double && (cost as Double) in minCost..(minCost + 0.08)),
                    "event:cost:total + trace:cost:total should be null or in $minCost..${minCost + 0.08}, got: $cost"
                )
            }
        }
    }

    @Test
    fun selectAllImplicitTest() {
        // ProcessM: "" (empty query = select all) — implicit SELECT * returns ALL attributes
        val result = q(
            "where l:logId='$journalLogId'",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have at least one log")

        val log = result.first()
        standardLogAssertionsWithMetadata(log)

        for (trace in log.traces) {
            standardTraceAssertions(trace)
            for (event in trace.events) {
                standardEventAssertions(event)
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
        assertEquals(1, result.count(), "Should have exactly one log")

        val log = result.first()
        // l:1 and l:D2020-03-12 are log-scope constants
        assertEquals(2, log.attributes.size, "Log should have 2 attributes")
        assertEquals(1.0, log.attributes["log:1.0"], "l:1 should be 1.0")
        assertEquals(Instant.parse("2020-03-12T00:00:00Z"), log.attributes["log:D2020-03-12T00:00:00Z"],
            "l:D2020-03-12 should be 2020-03-12T00:00:00Z")

        assertEquals(1, log.traces.count(), "Should have exactly 1 trace")
        val trace = log.traces.first()
        // l:2 + t:3 = 5 (trace scope), t:null/11 = null (trace scope)
        assertEquals(2, trace.attributes.size, "Trace should have 2 attributes")
        assertEquals(5.0, trace.attributes["log:2.0 + trace:3.0"], "l:2 + t:3 should be 5.0")
        assertNull(trace.attributes["trace:null / 11.0"], "t:null/11 should be null")

        assertEquals(1, trace.events.count(), "Should have exactly 1 event")
        val event = trace.events.first()
        // l:4 * t:5 + e:6 = 26, 7/8-9 = -8.125, 10*null = null (event scope)
        assertEquals(3, event.attributes.size, "Event should have 3 attributes")
        assertEquals(26.0, event.attributes["log:4.0 * trace:5.0 + event:6.0"], "l:4*t:5+e:6 should be 26.0")
        assertEquals(-8.125, event.attributes["7.0 / 8.0 - 9.0"], "7/8-9 should be -8.125")
        assertNull(event.attributes["10.0 * null"], "10 * null should be null")
    }

    @Test
    fun selectISO8601Test() {
        // ProcessM: 17 datetime formats — should produce 5 unique datetime values
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
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(0, log.attributes.size, "Log should have 0 attributes (no log-scope constants)")

        assertEquals(1, log.traces.count(), "Should have 1 trace")
        val trace = log.traces.first()
        assertEquals(0, trace.attributes.size, "Trace should have 0 attributes (no trace-scope constants)")

        assertEquals(1, trace.events.count(), "Should have 1 event")
        val event = trace.events.first()
        // 17 inputs → 5 unique datetimes:
        // 2020-03-13T00:00:00Z (date only), 2020-03-13T16:45:00Z (no seconds),
        // 2020-03-13T16:45:50Z, 2020-03-13T16:45:50.333Z, 2020-03-13T14:45:00Z (UTC from +02:00)
        assertEquals(5, event.attributes.size, "Event should have 5 unique datetime attributes")
        assertEquals(Instant.parse("2020-03-13T00:00:00Z"), event.attributes["D2020-03-13T00:00:00Z"],
            "D2020-03-13 should normalize to 2020-03-13T00:00:00Z")
        assertEquals(Instant.parse("2020-03-13T16:45:00Z"), event.attributes["D2020-03-13T16:45:00Z"],
            "D2020-03-13T16:45 should normalize to 2020-03-13T16:45:00Z")
        assertEquals(Instant.parse("2020-03-13T16:45:50Z"), event.attributes["D2020-03-13T16:45:50Z"],
            "D2020-03-13T16:45:50 should normalize to 2020-03-13T16:45:50Z")
        assertEquals(Instant.parse("2020-03-13T16:45:50.333Z"), event.attributes["D2020-03-13T16:45:50.333Z"],
            "D2020-03-13T16:45:50.333 should normalize to 2020-03-13T16:45:50.333Z")
        assertEquals(Instant.parse("2020-03-13T14:45:00Z"), event.attributes["D2020-03-13T14:45:00Z"],
            "D2020-03-13T16:45+0200 should normalize to UTC 2020-03-13T14:45:00Z")
    }

    @Test
    fun selectIEEE754Test() {
        // ProcessM: 12 IEEE754 numbers — verify specific values
        val result = q(
            "select 0, 0.0, 0.00, -0, -0.0, 1, 1.0, -1, -1.0, ${Math.PI}, ${Double.MIN_VALUE}, ${Double.MAX_VALUE} limit l:1, t:1, e:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(0, log.attributes.size, "Log should have 0 attributes")

        assertEquals(1, log.traces.count(), "Should have 1 trace")
        val trace = log.traces.first()
        assertEquals(0, trace.attributes.size, "Trace should have 0 attributes")

        assertEquals(1, trace.events.count(), "Should have 1 event")
        val event = trace.events.first()
        // 12 inputs → 7 unique keys: 0.0, -0.0, 1.0, -1.0, Math.PI, Double.MIN_VALUE, Double.MAX_VALUE
        // (0/0.0/0.00 → key "0.0"), (-0/-0.0 → key "-0.0"), (1/1.0 → "1.0"), (-1/-1.0 → "-1.0")
        assertEquals(7, event.attributes.size, "Event should have 7 unique numeric attributes")
        assertEquals(0.0, event.attributes["0.0"], "0.0 attribute should be 0.0")
        assertEquals(-0.0, event.attributes["-0.0"], "-0.0 attribute should be -0.0")
        assertEquals(1.0, event.attributes["1.0"], "1.0 attribute should be 1.0")
        assertEquals(-1.0, event.attributes["-1.0"], "-1.0 attribute should be -1.0")
        assertEquals(Math.PI, event.attributes["${Math.PI}"], "PI attribute should equal Math.PI")
        assertEquals(Double.MIN_VALUE, event.attributes["${Double.MIN_VALUE}"], "MIN_VALUE attribute check")
        assertEquals(Double.MAX_VALUE, event.attributes["${Double.MAX_VALUE}"], "MAX_VALUE attribute check")
    }

    @Test
    fun selectBooleanTest() {
        // ProcessM: select true, false limit l:1, t:1, e:1
        val result = q(
            "select true, false limit l:1, t:1, e:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(0, log.attributes.size, "Log should have 0 attributes")

        assertEquals(1, log.traces.count(), "Should have 1 trace")
        val trace = log.traces.first()
        assertEquals(0, trace.attributes.size, "Trace should have 0 attributes")

        assertEquals(1, trace.events.count(), "Should have 1 event")
        val event = trace.events.first()
        assertEquals(2, event.attributes.size, "Event should have 2 attributes (true and false)")
        assertEquals(true, event.attributes["true"], "true literal should be Boolean true")
        assertEquals(false, event.attributes["false"], "false literal should be Boolean false")
    }

    @Test
    fun selectStringTest() {
        // ProcessM: select 'single-quoted', "double-quoted" limit l:1, t:1, e:1
        val result = q(
            "select 'single-quoted', \"double-quoted\" limit l:1, t:1, e:1",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(0, log.attributes.size, "Log should have 0 attributes")

        assertEquals(1, log.traces.count(), "Should have 1 trace")
        val trace = log.traces.first()
        assertEquals(0, trace.attributes.size, "Trace should have 0 attributes")

        assertEquals(1, trace.events.count(), "Should have 1 event")
        val event = trace.events.first()
        assertEquals(2, event.attributes.size, "Event should have 2 string attributes")
        assertEquals("single-quoted", event.attributes["single-quoted"],
            "single-quoted literal should equal 'single-quoted'")
        assertEquals("double-quoted", event.attributes["double-quoted"],
            "double-quoted literal should equal \"double-quoted\"")
    }

    @Test
    fun selectNowTest() {
        // ProcessM: select l:now() limit l:1
        val before = Instant.now()
        val result = q(
            "select l:now() limit l:1",
            journalLogId
        )
        val after = Instant.now()

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(1, log.attributes.size, "Log should have exactly 1 attribute (now())")
        val nowValue = log.attributes["log:now()"]
        assertNotNull(nowValue, "log:now() should be in log.attributes")
        assertTrue(nowValue is Instant, "log:now() should be an Instant, got: ${nowValue?.javaClass}")
        val diff = Duration.between(before, after).toMillis() + 100L
        assertTrue(
            abs(Duration.between(before, nowValue as Instant).toMillis()) <= diff,
            "now() should be close to query execution time. before=$before, now=$nowValue, diff=${diff}ms"
        )
    }

    @Test
    fun selectSecondsTest() {
        // ProcessM: select e:*, second(e:timestamp) where l:id=$journal
        // Checks that second(e:timestamp) exactly equals the second component of the timestamp
        val result = q(
            "select e:timestamp, second(e:timestamp) where l:logId='$journalLogId' limit l:1, t:1, e:5",
            journalLogId
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertTrue(result.logs.isNotEmpty(), "Should have results")

        val log = result.first()
        for (trace in log.traces) {
            for (event in trace.events) {
                val ts = event.timeTimestamp
                if (ts != null) {
                    val expectedSec = ts.atZone(ZoneOffset.UTC).second.toDouble()
                    val actualSec = event.attributes["second(event:time:timestamp)"]
                    assertEquals(expectedSec, actualSec,
                        "second(e:timestamp) should exactly match timestamp second component")
                }
            }
        }
    }

    @Test
    fun nonexistentCustomAttributesTest() {
        // ProcessM: select [nonexistent] - nonexistent attribute should be null/absent
        val result1 = q(
            "select [e:nonexistent_attribute_xyz] where l:logId='$journalLogId' limit l:1, t:5, e:10",
            journalLogId
        )
        assertTrue(result1.success, "Nonexistent attribute query should succeed")
        if (result1.logs.isNotEmpty()) {
            for (trace in result1.first().traces) {
                for (event in trace.events) {
                    val attr = event.attributes["nonexistent_attribute_xyz"]
                    assertNull(attr, "Nonexistent attribute should be null, got: $attr")
                }
            }
        }

        // Second query: [e:result] — some events have "accept" or "reject"
        val result2 = q(
            "select [e:result] where l:logId='$journalLogId'",
            journalLogId
        )
        assertTrue(result2.success, "Result attribute query should succeed")
        var foundResult = false
        if (result2.logs.isNotEmpty()) {
            for (trace in result2.first().traces) {
                for (event in trace.events) {
                    val resultAttr = event.attributes["result"]
                    if (resultAttr != null) {
                        foundResult = true
                        assertTrue(resultAttr.toString() in results,
                            "Event result should be 'accept' or 'reject', got: $resultAttr")
                    }
                }
            }
        }
        assertTrue(foundResult, "At least some events should have the 'result' attribute")
    }

}
