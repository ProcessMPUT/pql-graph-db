package com.processm.processminterpreter

import com.processm.processminterpreter.config.ProcessMConfig
import com.processm.processminterpreter.pql.AntlrPQLTranslator
import com.processm.processminterpreter.pql.interpreter.BaseInterpreterTest
import com.processm.processminterpreter.service.LogService
import com.processm.processminterpreter.service.PQLQueryService
import com.processm.processminterpreter.xes.XESLoader
import com.processm.processminterpreter.xes.XESParser
import com.processm.processminterpreter.xes.XESWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.io.ByteArrayInputStream

@Tag("Integration")
class IntegrationTest : BaseInterpreterTest() {

    private lateinit var xesLoader: XESLoader
    private lateinit var logService: LogService
    private lateinit var queryService: PQLQueryService

    @BeforeEach
    fun initServices() {
        logService = Mockito.mock(LogService::class.java)
        xesLoader = XESLoader(XESParser(), logService, driver)
        queryService = PQLQueryService(AntlrPQLTranslator(ProcessMConfig()), driver, XESWriter())
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

        // 3. Execute PQL Query
        // Count total events
        val countQuery = "select count(e:id)"
        val countResult = queryService.executePQLQuery(countQuery)
        
        if (!countResult.success) {
            println("Count query failed: ${countResult.error}")
        }
        assertTrue(countResult.success, "Count query should succeed")
        assertEquals(1, countResult.results.size)
        val totalEvents = (countResult.results[0].values.first() as Number).toInt()
        assertEquals(traceCount * eventsPerTrace, totalEvents, "Should count all events")

        // 4. Verify Grouping and Aggregation
        // Count events per trace
        val groupQuery = "select t:caseId, count(e:id) group by t:caseId order by t:caseId"
        val groupResult = queryService.executePQLQuery(groupQuery)
        
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
