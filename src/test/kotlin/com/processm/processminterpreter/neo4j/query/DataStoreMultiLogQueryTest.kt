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
    fun `event order decides log window before technical log id`() {
        val result = executeDataStoreQuery("order by e:timestamp, e:name limit l:1, t:5, e:10")

        assertTrue(result.success, "Query should succeed: ${result.error}")
        assertEquals(1, result.count())

        val log = result.first()
        assertEquals(BETA_LOG_NAME, log.conceptName)
        assertLogShape(log, mapOf("Beta-1" to listOf("Shared intake", "Beta rejection")))
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

    private fun logNames(logs: List<XesLog>): Set<String> =
        logs.mapTo(mutableSetOf<String>()) { log -> assertNotNull(log.conceptName) }

    private companion object {
        const val ALPHA_LOG_ID = "multi-log-alpha"
        const val ALPHA_LOG_NAME = "Alpha Log"
        const val BETA_LOG_ID = "multi-log-beta"
        const val BETA_LOG_NAME = "Beta Log"
        const val ORPHAN_LOG_ID = "multi-log-orphan"
        const val ORPHAN_LOG_NAME = "Orphan Log"

        val BASE_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
