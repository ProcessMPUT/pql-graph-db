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

        if (!countResult.success) {
            println("Count query failed: ${countResult.error}")
        }
        assertTrue(countResult.success, "Count query should succeed")
        // ProcessM groups event-level aggregation per trace implicitly
        assertEquals(traceCount, countResult.results.size, "Should have one result per trace")
        val totalEvents = countResult.results.sumOf { row ->
            val countValue = row.entries.first { (key, _) -> key.startsWith("count") }.value as Number
            countValue.toInt()
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

        if (!groupResult.success) {
            println("Group query failed: ${groupResult.error}")
        }
        assertTrue(groupResult.success, "Group query should succeed")
        assertEquals(traceCount, groupResult.results.size, "Should have one result per trace")

        val firstTrace = groupResult.results[0]
        // Depending on map key naming (e.g. t_caseId or trace.caseId), check values
        // The current implementation returns map keys based on alias or property name
        // Let's just check that we have results and they look reasonable
        assertTrue(firstTrace.values.any { it == "Case 1" }, "Should contain Case 1")

        val countValue = firstTrace.values.find { it is Number }
        if (countValue == null) {
            println("First trace values: ${firstTrace.values}")
            println("First trace types: ${firstTrace.values.map { it?.javaClass?.name }}")
        }
        assertTrue(firstTrace.values.any { (it as? Number)?.toInt() == eventsPerTrace }, "Should have correct event count per trace")
    }
}
