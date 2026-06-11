package com.processm.processminterpreter

import com.processm.processminterpreter.domain.pql.interpreter.BaseInterpreterTest
import com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.Neo4jXesLogWriter
import com.processm.processminterpreter.infrastructure.xes.XESLoader
import com.processm.processminterpreter.infrastructure.xes.XESParser
import com.processm.processminterpreter.infrastructure.xes.OpenXesReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

import java.io.ByteArrayInputStream

@Tag("Integration")
class IntegrationTest : BaseInterpreterTest() {
    private lateinit var xesLoader: XESLoader

    @BeforeEach
    fun initServices() {
        xesLoader = XESLoader(OpenXesReader(XESParser()), Neo4jXesLogWriter(driver))
    }

    @Test
    fun `test end-to-end flow`() {
        // 1. Generate Synthetic Log
        val traceCount = 10
        val eventsPerTrace = 5
        val xesContent = TestUtils.generateSyntheticLog(traceCount, eventsPerTrace)
        val inputStream = ByteArrayInputStream(xesContent.toByteArray())

        // 2. Import Log
        val importResult = xesLoader.loadXESFile(inputStream, "integration-log")
        assertTrue(importResult.success, "Log import should succeed")
        attachLogToInterpreterDataStore(importResult.logId ?: "integration-log")

        // 3. Execute PQL Query
        // Count total events
        val countQuery = "select count(e:name)"
        val countResult = executeDataStoreQuery(countQuery, defaultTraceLimit = -1)

        assertTrue(countResult.success, "Count query should succeed: ${countResult.error}")
        assertEquals(1, countResult.logs.size, "Should return one log")
        val countTraces = countResult.logs.single().traces
        assertEquals(traceCount, countTraces.size, "Should have one result per trace")
        val totalEvents =
            countTraces.sumOf { trace ->
                assertEquals(1, trace.events.size, "Each trace should contain one aggregation event")
                (trace.events.single().customAttributes["count(event:concept:name)"] as Number).toInt()
            }
        assertEquals(
            traceCount * eventsPerTrace,
            totalEvents,
            "Sum of per-trace counts should equal total events",
        )

        // 4. Verify Grouping and Aggregation
        // Count events per trace
        val groupQuery = "select t:name, count(e:name) group by t:name order by t:name"
        val groupResult = executeDataStoreQuery(groupQuery, defaultTraceLimit = -1)

        assertTrue(groupResult.success, "Group query should succeed: ${groupResult.error}")
        assertEquals(1, groupResult.logs.size, "Should return one log")
        val groupedTraces = groupResult.logs.single().traces
        assertEquals(traceCount, groupedTraces.size, "Should have one result per trace")
        assertEquals(
            (1..traceCount).map { "Case $it" }.sorted(),
            groupedTraces.map { it.conceptName },
            "Traces should be ordered by concept:name",
        )
        groupedTraces.forEach { trace ->
            assertEquals(1, trace.events.size, "Each grouped trace should contain one aggregation event")
            assertEquals(
                eventsPerTrace,
                (trace.events.single().customAttributes["count(event:concept:name)"] as Number).toInt(),
                "Each grouped trace should contain the expected event count",
            )
        }
    }
}
