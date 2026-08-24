package com.processm.processminterpreter.neo4j.query

import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import com.processm.processminterpreter.pql.interpreter.BaseInterpreterTest
import com.processm.processminterpreter.neo4j.xes.Neo4jXesLogWriter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataStoreMultiLogQueryTest : BaseInterpreterTest() {
    private lateinit var writer: Neo4jXesLogWriter

    @BeforeEach
    fun loadMultiLogDataStore() {
        clearDatabase()
        writer = Neo4jXesLogWriter(driver)

        writeAndAttach(
            logId = ALPHA_LOG_ID,
            log = log(
                name = ALPHA_LOG_NAME,
                startTime = BASE_TIME.plusSeconds(1000),
                traces = mapOf(
                    "Alpha-1" to listOf("Shared intake", "Alpha approval"),
                    "Alpha-2" to listOf("Alpha archive"),
                ),
            ),
        )
        writeAndAttach(
            logId = BETA_LOG_ID,
            log = log(
                name = BETA_LOG_NAME,
                traces = mapOf(
                    "Beta-1" to listOf("Shared intake", "Beta rejection"),
                ),
            ),
        )

        writer.write(
            log(
                name = ORPHAN_LOG_NAME,
                traces = mapOf("Orphan-1" to listOf("Orphan event")),
            ),
            ORPHAN_LOG_ID,
        )
    }

    @Test
    fun `data store query returns all attached logs and excludes unattached logs`() {
        val result = executeDataStoreQuery("select l:name, t:name, e:name limit l:10, t:10, e:10")

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(setOf(ALPHA_LOG_NAME, BETA_LOG_NAME), logNames(result.logs))

        assertLogShape(
            result.logs.single { it.conceptName == ALPHA_LOG_NAME },
            mapOf(
                "Alpha-1" to listOf("Shared intake", "Alpha approval"),
                "Alpha-2" to listOf("Alpha archive"),
            ),
        )
        assertLogShape(
            result.logs.single { it.conceptName == BETA_LOG_NAME },
            mapOf("Beta-1" to listOf("Shared intake", "Beta rejection")),
        )
    }

    @Test
    fun `where clause narrows one log inside a multi-log data store`() {
        val result = executeDataStoreQuery(
            "select l:name, t:name, e:name where l:name='$BETA_LOG_NAME' limit l:10, t:10, e:10",
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(BETA_LOG_NAME, log.conceptName)
        assertLogShape(log, mapOf("Beta-1" to listOf("Shared intake", "Beta rejection")))
    }

    @Test
    fun `grouped query keeps event variants separated per log`() {
        val result = executeDataStoreQuery(
            "select l:name, e:name group by ^e:name order by l:name, e:name limit l:10, t:10, e:10",
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(setOf(ALPHA_LOG_NAME, BETA_LOG_NAME), logNames(result.logs))

        val alphaEvents = groupedEventNames(result.logs.single { it.conceptName == ALPHA_LOG_NAME })
        val betaEvents = groupedEventNames(result.logs.single { it.conceptName == BETA_LOG_NAME })

        assertEquals(setOf("Alpha approval", "Alpha archive", "Shared intake"), alphaEvents)
        assertEquals(setOf("Beta rejection", "Shared intake"), betaEvents)
    }

    @Test
    fun `count-only activity variants execute from imported trace cache`() {
        val result = executeDataStoreQuery(
            "select count(t:name), count(^e:name) group by ^e:name " +
                "order by count(t:name) desc limit l:1, t:3",
            logId = ALPHA_LOG_ID,
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.logs.size)
        val variants = result.first().traces
        assertEquals(2, variants.size)
        assertEquals(listOf(2, 1), variants.map { it.nullEventCount })
        assertEquals(listOf(1L, 1L), variants.map { it.countAttribute("count(trace:concept:name)") })
        assertEquals(listOf(2L, 1L), variants.map { it.countAttribute("count(^event:concept:name)") })
        assertTrue(variants.all { it.events.isEmpty() })
    }

    @Test
    fun `activity variants distinguish unnamed traces from total trace count`() {
        clearDatabase()
        writeAndAttach(
            logId = UNNAMED_TRACE_LOG_ID,
            log = XesLog(
                conceptName = "Unnamed trace log",
                traces = listOf(
                    XesTrace(
                        conceptName = null,
                        events = listOf(XesEvent(conceptName = "A"), XesEvent(conceptName = "B")),
                    ),
                ),
            ),
        )

        val result = executeDataStoreQuery(
            "select count(t:name), count(^e:name) group by ^e:name " +
                "order by count(t:name) desc limit l:1, t:3",
            logId = UNNAMED_TRACE_LOG_ID,
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        val variant = result.first().traces.single()
        assertEquals(0L, variant.countAttribute("count(trace:concept:name)"))
        assertEquals(2L, variant.countAttribute("count(^event:concept:name)"))
        assertEquals(2, variant.nullEventCount)
        assertTrue(variant.events.isEmpty())

        val expandedResult = executeDataStoreQuery(
            "select count(t:name), count(^e:name), e:name group by ^e:name " +
                "order by count(t:name) desc limit l:1, t:3",
            logId = UNNAMED_TRACE_LOG_ID,
        )

        assertTrue(expandedResult.success, "Expanded query should succeed: ${expandedResult.error}")
        val expandedVariant = expandedResult.first().traces.single()
        assertEquals(0L, expandedVariant.countAttribute("count(trace:concept:name)"))
        assertEquals(2L, expandedVariant.countAttribute("count(^event:concept:name)"))
        assertEquals(listOf("A", "B"), expandedVariant.events.map { it.conceptName })
    }

    @Test
    fun `event order decides log window before technical log id`() {
        val result = executeDataStoreQuery("order by e:timestamp, e:name limit l:1, t:5, e:10")

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(BETA_LOG_NAME, log.conceptName)
        assertLogShape(log, mapOf("Beta-1" to listOf("Shared intake", "Beta rejection")))
    }

    @Test
    fun `indexed event contains selects the matching datastore log`() {
        val result = executeDataStoreQuery(
            "where e:name like '%rejection%' order by e:name, e:timestamp limit l:1, t:5, e:10",
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())
        val log = result.first()
        assertEquals(BETA_LOG_NAME, log.conceptName)
        assertLogShape(log, mapOf("Beta-1" to listOf("Beta rejection")))
    }

    @Test
    fun `default log window follows data store attachment order for aggregate projections`() {
        val result = executeDataStoreQuery(
            "select l:*, t:*, avg(e:total), min(e:timestamp), max(e:timestamp) limit l:1",
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(ALPHA_LOG_NAME, log.conceptName)
        assertLogShape(
            log,
            mapOf(
                "Alpha-1" to listOf(null),
                "Alpha-2" to listOf(null),
            ),
        )
    }

    @Test
    fun `aggregate placeholder trace window preserves full counts for every log`() {
        val result = executeDataStoreQuery(
            "select count(l:name), count(^t:name), count(^^e:name)" +
                " limit l:10, t:1 offset t:1",
            defaultTraceLimit = -1,
        )

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(2, result.logs.size)

        // l:name is aggregated rather than projected, so identify the otherwise
        // anonymous logs by their full event counts.
        val alpha = result.logs.single { it.aggregateCount("count(^^event:concept:name)") == 3L }
        val beta = result.logs.single { it.aggregateCount("count(^^event:concept:name)") == 2L }
        assertEquals(1, alpha.traces.size, "Alpha should retain its second placeholder trace")
        assertEquals(0, beta.traces.size, "Beta has no second trace after applying the offset")
        assertAggregateCounts(alpha, traces = 2L, events = 3L)
        assertAggregateCounts(beta, traces = 1L, events = 2L)
    }

    private fun writeAndAttach(
        logId: String,
        log: XesLog,
    ) {
        writer.write(log, logId)
        attachLogToInterpreterDataStore(logId)
    }

    private fun log(
        name: String,
        startTime: Instant = BASE_TIME,
        traces: Map<String, List<String>>,
    ): XesLog =
        XesLog(
            conceptName = name,
            traces = traces.map { (traceName, eventNames) ->
                XesTrace(
                    conceptName = traceName,
                    events = eventNames.mapIndexed { index, eventName ->
                        XesEvent(
                            conceptName = eventName,
                            timeTimestamp = startTime.plusSeconds(index.toLong()),
                        )
                    },
                )
            },
        )

    private fun assertLogShape(log: XesLog, expectedTraces: Map<String, List<String?>>) {
        val actual = log.traces.associate { trace ->
            val traceName = assertNotNull(trace.conceptName)
            traceName to trace.events.map { event -> event.conceptName }
        }
        assertEquals(expectedTraces, actual)
    }

    private fun groupedEventNames(log: XesLog): Set<String> =
        log.traces
            .flatMap { trace -> trace.events }
            .mapTo(mutableSetOf<String>()) { event -> assertNotNull(event.conceptName) }

    private fun assertAggregateCounts(log: XesLog, traces: Long, events: Long) {
        assertEquals(1L, log.aggregateCount("count(log:concept:name)"))
        assertEquals(traces, log.aggregateCount("count(^trace:concept:name)"))
        assertEquals(events, log.aggregateCount("count(^^event:concept:name)"))
    }

    private fun XesLog.aggregateCount(name: String): Long =
        (customAttributes[name] as Number).toLong()

    private fun XesTrace.countAttribute(name: String): Long =
        (customAttributes[name] as Number).toLong()

    private fun logNames(logs: List<XesLog>): Set<String> =
        logs.mapTo(mutableSetOf<String>()) { log -> assertNotNull(log.conceptName) }

    private companion object {
        const val ALPHA_LOG_ID = "multi-log-alpha"
        const val ALPHA_LOG_NAME = "Alpha Log"
        const val BETA_LOG_ID = "multi-log-beta"
        const val BETA_LOG_NAME = "Beta Log"
        const val ORPHAN_LOG_ID = "multi-log-orphan"
        const val ORPHAN_LOG_NAME = "Orphan Log"
        const val UNNAMED_TRACE_LOG_ID = "unnamed-trace-log"

        val BASE_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
