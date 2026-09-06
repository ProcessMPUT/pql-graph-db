package com.processm.processminterpreter.benchmark

import com.processm.processminterpreter.pql.PqlCompiler
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.cypher.CypherCodegen
import com.processm.processminterpreter.pql.cypher.PhysicalAttributeMapper
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import com.processm.processminterpreter.pql.parser.AntlrPqlParser
import com.processm.processminterpreter.pql.plan.LogicalPlan
import com.processm.processminterpreter.xes.DataStoreRepository
import com.processm.processminterpreter.xes.LogRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlledWorkloadTest {
    private val config = BenchmarkConfig.catalog()

    @Test
    fun `controlled matrix covers three selectivities three shapes and one response log`() {
        val selectivity = config.datasets.filter { it.series == "selectivity-scaling" }
        assertEquals(setOf(100_000), selectivity.map { it.traces!! * it.eventsPerTrace!! }.toSet())
        assertEquals(3, selectivity.size)
        assertEquals(listOf(1, 10, 100), selectivity.map { it.matchingTracePercent })
        assertTrue(selectivity.all { it.eventsPerTrace == 10 && it.activityCount == 10 && it.attributesPerEvent == 5 })
        val shape = config.datasets.filter { it.series == "trace-length-scaling" }
        assertEquals(listOf(10, 100, 1000), shape.map { it.eventsPerTrace })
        assertTrue(shape.all { it.traces!! * it.eventsPerTrace!! == 100_000 && it.activityCount == 10 && it.costCycleLength == 10 })
        for (dataset in selectivity + shape) {
            val queries = config.queries.filter { it.isMeasuredFor(dataset.name, dataset.series) }
            assertEquals(2, queries.size)
            assertTrue(queries.all { dataset.name in it.expectedResponses })
        }
        val windows = config.queries.filter { it.label in setOf("responseWindow20", "hierarchyWindow", "responseWindow600") }
        assertEquals(setOf(20, 200, 600), windows.map { it.expectedResponses.getValue("variants-1").events }.toSet())
        assertTrue(windows.all { it.isMeasuredFor("variants-1", "variant-scaling") })
        assertTrue(windows.filter { it.label != "hierarchyWindow" }.none { it.isMeasuredFor("variants-100", "variant-scaling") })
        assertEquals(168, config.datasets.sumOf { d -> config.queries.count { it.isMeasuredFor(d.name, d.series) } })
    }

    @Test
    fun `controlled aggregate projections compile to canonical scoped attributes and windows respect reference limits`() {
        // Reference docs/pql.md specifies bracketed hoisting and implicit grouping at the aggregate scope.
        // Reference Attribute.toString expands scopes and standard names; LogsService applies 10/30/90 API limits.
        val compiler = PqlCompiler(AntlrPqlParser(), mock(LogRepository::class.java), mock(DataStoreRepository::class.java))
        val defaults = HierarchicalLimits(log = 10, trace = 30, event = 90)
        config.queries.filter { it.expectedResponses.isNotEmpty() }.forEach { query ->
            val plan = compiler.compile(query.query, "controlled-log", defaultLimits = defaults) as LogicalPlan.Select
            assertTrue(requireNotNull(plan.limits.log) <= 10)
            assertTrue(requireNotNull(plan.limits.trace) <= 30)
            plan.limits.event?.let { assertTrue(it <= 90) }
            val expectedColumns = query.expectedResponses.values.flatMap { expected ->
                expected.logAttributes.keys.map { it to Scope.LOG } +
                    expected.traceAttributes.keys.map { it to Scope.TRACE }
            }.toSet()
            val rendered = CypherCodegen(PhysicalAttributeMapper()).generate(plan)
            assertEquals(expectedColumns, rendered.columnAliases.values.filterNot { it.synthetic }
                .map { it.pqlExpression to it.scope }.toSet())
        }
    }

    @Test
    fun `independent controls reject the same wrong aggregate on both systems`() {
        val query = config.queries.single { it.label == "selectivityEventFilter" }
        val expected = query.expectedResponses.getValue("selectivity-100k-1pct")
        val correct = """[{"log":{"trace":{"int":{"@key":"count(trace:concept:name)","@value":"100"}}}}]"""
        assertNull(expected.mismatch(correct))
        val wrong = correct.replace("\"100\"", "\"10000\"")
        assertTrue(XesJsonSemanticParity.compare(wrong, wrong).matches)
        assertNotNull(expected.mismatch(wrong))
        assertNotNull(expected.mismatch(correct.replace("\"int\"", "\"string\"")))
        assertNotNull(expected.mismatch(correct.replace("count(trace:concept:name)", "unrelated")))
        val duplicate = """[{"log":{"trace":{"int":[{"@key":"count(trace:concept:name)","@value":"100"},{"@key":"count(trace:concept:name)","@value":"100"}]}}}]"""
        assertNotNull(expected.mismatch(duplicate))
        val wrongScope = """[{"log":{"int":{"@key":"count(trace:concept:name)","@value":"100"},"trace":{}}}]"""
        assertNotNull(expected.mismatch(wrongScope))
        val traceArray = """[{"log":{"trace":[{"int":{"@key":"count(trace:concept:name)","@value":"100"}}]}}]"""
        assertNull(expected.mismatch(traceArray))
        val multipleTraces = """[{"log":{"trace":[{"int":{"@key":"count(trace:concept:name)","@value":"100"}},{"int":{"@key":"count(trace:concept:name)","@value":"100"}}]}}]"""
        assertNotNull(expected.mismatch(multipleTraces))
        assertNotNull(expected.mismatch("[]"))
    }

    @Test
    fun `aggregate controls check full cardinality beyond the response trace window`() {
        for (query in config.queries.filter { it.label.startsWith("selectivity") }) {
            for (dataset in config.datasets.filter { it.series == "selectivity-scaling" }) {
                val expected = query.expectedResponses.getValue(dataset.name)
                assertEquals(1, expected.traces)
                assertEquals(0, expected.events)
                assertEquals((dataset.traces!! * dataset.matchingTracePercent!! / 100).toString(),
                    expected.traceAttributes.values.single().value)
                assertTrue(expected.traceAttributes.values.single().value.toInt() > 1)
                assertTrue(expected.logAttributes.isEmpty())
            }
        }
    }

    @Test
    fun `response controls require exact cardinality and timestamps tolerate equivalent offset notation`() {
        val expected = ExpectedBenchmarkResponse(1, 1, 1)
        assertNull(expected.mismatch("""[{"log":{"trace":{"event":{}}}}]"""))
        assertNotNull(expected.mismatch("""[{"log":{"trace":{"event":[{},{}]}}}]"""))
        val dates = ExpectedBenchmarkResponse(1, 0, 0,
            mapOf("min(^^event:time:timestamp)" to ExpectedBenchmarkResponse.Attribute("date", "2020-01-01T00:00:00Z")))
        assertNull(dates.mismatch("""[{"log":{"date":{"@key":"min(^^event:time:timestamp)","@value":"2020-01-01T01:00:00+01:00"}}}]"""))
        assertNotNull(dates.mismatch("""[{"log":{"date":{"@key":"min(^^event:time:timestamp)","@value":"2020-01-02T00:00:00Z"}}}]"""))
    }
}
