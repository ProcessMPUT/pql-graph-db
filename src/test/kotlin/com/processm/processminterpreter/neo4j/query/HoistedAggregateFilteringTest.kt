package com.processm.processminterpreter.neo4j.query

import com.processm.processminterpreter.neo4j.xes.Neo4jXesLogWriter
import com.processm.processminterpreter.pql.interpreter.BaseInterpreterTest
import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression for ProcessM's groupIdsQuery -> groupExpressionsQuery contract:
 * WHERE selects group members at the aggregate scope; hoisted arguments then
 * read all descendants of those members. Limits apply to the resulting groups.
 */
class HoistedAggregateFilteringTest : BaseInterpreterTest() {
    @BeforeEach
    fun loadData() {
        clearDatabase()
        val writer = Neo4jXesLogWriter(driver)
        writer.write(fixture("A", listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6)), setOf(1, 5, 6)), "A")
        writer.write(fixture("B", listOf(listOf(7, 8), listOf(9, 10)), setOf(7)), "B")
        // An unattached log must never contribute to aggregate membership/arguments.
        writer.write(fixture("Outside", listOf(listOf(1000, 2000)), setOf(1000)), "outside")
        attachLogToInterpreterDataStore("A")
        attachLogToInterpreterDataStore("B")
    }

    @Test
    fun `log aggregate counts all descendants of the selected log`() {
        for (predicate in listOf("[^e:attr_1] = 'hit'", "[e:attr_1] = 'hit'", "t:name = 'T0'")) {
            val log = query("select count(^t:name), count(^^e:name) where $predicate limit l:1, t:1", "A").single()
            assertEquals(3L, log.count("count(^trace:concept:name)"), predicate)
            assertEquals(6L, log.count("count(^^event:concept:name)"), predicate)
            assertNull(log.conceptName)
            assertEquals(1, log.traces.size)
            assertEquals(if (predicate.startsWith("[e:")) 1 else 2, log.traces.single().nullEventCount)
            assertTrue(log.traces.single().events.isEmpty())
        }
    }

    @Test
    fun `implicit log aggregation includes both matching logs before applying log limit`() {
        val log = query(
            "select count(l:name), count(^t:name), count(^^e:name), " +
                "sum(^^e:total), min(^^e:total), max(^^e:total) " +
                "where [^e:attr_1] = 'hit' limit l:1, t:1",
        ).single()

        assertEquals(2L, log.count("count(log:concept:name)"))
        assertEquals(5L, log.count("count(^trace:concept:name)"))
        assertEquals(10L, log.count("count(^^event:concept:name)"))
        assertEquals(55.0, log.number("sum(^^event:cost:total)"))
        assertEquals(1.0, log.number("min(^^event:cost:total)"))
        assertEquals(10.0, log.number("max(^^event:cost:total)"))
        assertEquals(1, log.traces.size)
        assertEquals(2, log.traces.single().nullEventCount)
    }

    @Test
    fun `log filter excludes another log from aggregate arguments`() {
        val log = query(
            "select count(^t:name), sum(^^e:total) " +
                "where l:name = 'A' and [^e:attr_1] = 'hit' limit l:1, t:1",
        ).single()
        assertEquals(3L, log.count("count(^trace:concept:name)"))
        assertEquals(21.0, log.number("sum(^^event:cost:total)"))
    }

    @Test
    fun `trace scoped counts count matching traces and preserve placeholder events`() {
        for (predicate in listOf("[e:attr_1] = 'hit'", "[^e:attr_1] = 'hit'")) {
            val logs = query("select count(t:name) where $predicate limit l:10, t:1")
            assertEquals(2, logs.size)
            assertEquals(listOf(2L, 1L), logs.map { it.traces.single().count("count(trace:concept:name)") })
            logs.forEachIndexed { index, log ->
                assertEquals(1, log.traces.size)
                val trace = log.traces.single()
                assertNull(trace.conceptName)
                assertEquals(if (index == 1 && predicate.startsWith("[e:")) 1 else 2, trace.nullEventCount)
                assertTrue(trace.events.isEmpty())
            }
        }
    }

    @Test
    fun `trace hoisted numeric aggregates read all events of matching traces`() {
        val logs = query(
            "select sum(^e:total), avg(^e:total) " +
                "where [e:attr_1] = 'hit' limit l:10, t:1",
        )
        assertEquals(2, logs.size)
        val actual = logs.map { log ->
            val trace = log.traces.single()
            listOf("sum", "avg").map { trace.number("$it(^event:cost:total)") }
        }
        assertEquals(listOf(listOf(14.0, 3.5), listOf(15.0, 7.5)), actual)
        val bounds = query("select min(^e:total), max(^e:total) where [e:attr_1] = 'hit' limit l:10, t:1")
        assertEquals(
            listOf(listOf(1.0, 6.0), listOf(7.0, 8.0)),
            bounds.map { log -> listOf("min", "max").map { log.traces.single().number("$it(^event:cost:total)") } },
        )
    }

    @Test
    fun `global average uses original values rather than averaging per log averages`() {
        val log = query("select sum(^^e:total), avg(^^e:total) where [e:attr_1] = 'hit' limit l:1, t:1").single()
        assertEquals(55.0, log.number("sum(^^event:cost:total)"))
        assertEquals(5.5, log.number("avg(^^event:cost:total)"))
    }

    @Test
    fun `explicit trace groups preserve membership separately from aggregate inputs`() {
        val logs = query(
            "select t:name, count(^e:name), sum(^e:total) where [e:attr_1] = 'hit' " +
                "group by t:name order by t:name limit l:10, t:10",
        )
        assertEquals(2, logs.size)
        assertEquals(
            listOf(listOf("T0" to 3.0, "T2" to 11.0), listOf("T0" to 15.0)),
            logs.map { log -> log.traces.map { it.conceptName to it.number("sum(^event:cost:total)") } },
        )
        assertTrue(logs.flatMap { it.traces }.all { it.count("count(^event:concept:name)") == 2L })
    }

    @Test
    fun `explicit log groups retain distinct totals`() {
        val logs = query(
            "select l:name, count(^t:name), sum(^^e:total) where [e:attr_1] = 'hit' " +
                "group by l:name order by l:name limit l:10, t:1",
        )
        assertEquals(listOf("A", "B"), logs.map { it.conceptName })
        assertEquals(listOf(3L, 2L), logs.map { it.count("count(^trace:concept:name)") })
        assertEquals(listOf(21.0, 34.0), logs.map { it.number("sum(^^event:cost:total)") })
    }

    @Test
    fun `event scoped aggregates still use only matching events`() {
        val logs = query(
            "select count(e:name), sum(e:total) where [e:attr_1] = 'hit' limit l:10, t:10, e:10",
        )
        assertEquals(2, logs.size)
        assertEquals(
            listOf(listOf(1L to 1.0, 2L to 11.0), listOf(1L to 7.0)),
            logs.map { log ->
                log.traces.map { trace ->
                    val event = trace.events.single()
                    (event.customAttributes.getValue("count(event:concept:name)") as Number).toLong() to
                        (event.customAttributes.getValue("sum(event:cost:total)") as Number).toDouble()
                }
            },
        )
    }

    @Test
    fun `placeholder window bounds driver rows without truncating aggregates`() {
        val result = executeDataStoreQuery(
            "select sum(^^e:total), count(^t:name) where [e:attr_1] = 'hit' limit l:1, t:1",
        )
        assertTrue(result.success, result.error)
        assertEquals(1, result.resultCount, "Only the requested placeholder prefix should cross the driver")
        val log = result.logs.single()
        assertEquals(55.0, log.number("sum(^^event:cost:total)"))
        assertEquals(5L, log.count("count(^trace:concept:name)"))
    }

    @Test
    fun `aggregate order is applied before the trace window`() {
        val log = query(
            "select t:name, sum(^e:total) where [e:attr_1] = 'hit' " +
                "group by t:name order by sum(^e:total) desc limit l:1, t:1",
            "A",
        ).single()
        val trace = log.traces.single()
        assertEquals("T2", trace.conceptName)
        assertEquals(11.0, trace.number("sum(^event:cost:total)"))
    }

    @Test
    fun `trace without events survives a trace scoped aggregate`() {
        val writer = Neo4jXesLogWriter(driver)
        writer.write(XesLog(conceptName = "Empty", traces = listOf(XesTrace(conceptName = "EmptyTrace"))), "empty")
        attachLogToInterpreterDataStore("empty")
        val log = query("select count(t:name) where t:name = 'EmptyTrace'", "empty").single()
        assertEquals(1L, log.traces.single().count("count(trace:concept:name)"))
        assertEquals(0, log.traces.single().nullEventCount)
        assertTrue(log.traces.single().events.isEmpty())
    }

    @Test
    fun `no matching log produces no synthetic zero aggregate`() {
        assertTrue(query("select count(^t:name), count(^^e:name) where [e:attr_1] = 'missing'").isEmpty())
    }

    @Test
    fun `mixed aggregate scopes select their own member populations`() {
        val log = query(
            "select count(l:name), count(^t:name), count(^^e:name), " +
                "count(t:name), count(^e:name), count(e:name) where [e:attr_1] = 'hit'",
        ).single()
        assertEquals(2L, log.count("count(log:concept:name)"))
        assertEquals(5L, log.count("count(^trace:concept:name)"))
        assertEquals(10L, log.count("count(^^event:concept:name)"))
        val trace = log.traces.single()
        assertEquals(3L, trace.count("count(trace:concept:name)"))
        assertEquals(6L, trace.count("count(^event:concept:name)"))
        assertEquals(4L, trace.events.single().customAttributes["count(event:concept:name)"])
    }

    @Test
    fun `sequence groups use filtered keys while hoisted aggregates read complete traces`() {
        val log = query(
            "select count(^e:name) where [e:attr_1] = 'hit' group by ^e:name",
            "A",
        ).single()
        // Both full traces contain E0,E1, but their filtered sequences are E0
        // and E0,E1, so ProcessM returns two groups, each counting two events.
        assertEquals(listOf(2L, 2L), log.traces.map { it.count("count(^event:concept:name)") })
        assertEquals(listOf(1, 2), log.traces.map { it.nullEventCount })
    }

    @Test
    fun `group ordering uses filtered values separately from projected hoisted aggregate`() {
        val log = query(
            "select l:name, sum(^^e:total) where [e:attr_1] = 'hit' " +
                "group by l:name order by sum(^^e:total) desc limit l:1, t:1",
        ).single()
        // Filtered sums order A(12) before B(7); projected sums are A(21), B(34).
        assertEquals("A", log.conceptName)
        assertEquals(21.0, log.number("sum(^^event:cost:total)"))
    }

    private fun query(pql: String, logId: String? = null): List<XesLog> {
        val result = executeDataStoreQuery(pql, logId)
        assertTrue(result.success, "$pql: ${result.error}")
        return result.logs
    }

    private fun fixture(name: String, costs: List<List<Int>>, hits: Set<Int>): XesLog = XesLog(
        conceptName = name,
        traces = costs.mapIndexed { index, values ->
            XesTrace(
                conceptName = "T$index",
                events = values.mapIndexed { eventIndex, value ->
                    XesEvent(
                        conceptName = "E$eventIndex",
                        costTotal = value.toDouble(),
                        customAttributes = mapOf("attr_1" to if (value in hits) "hit" else "out"),
                    )
                },
            )
        },
    )

    private fun XesLog.count(key: String): Long = (customAttributes.getValue(key) as Number).toLong()
    private fun XesLog.number(key: String): Double = (customAttributes.getValue(key) as Number).toDouble()
    private fun XesTrace.count(key: String): Long = (customAttributes.getValue(key) as Number).toLong()
    private fun XesTrace.number(key: String): Double = (customAttributes.getValue(key) as Number).toDouble()
}
