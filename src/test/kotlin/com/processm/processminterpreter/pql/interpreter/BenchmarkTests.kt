package com.processm.processminterpreter.pql.interpreter

import com.processm.processminterpreter.service.LogService
import com.processm.processminterpreter.xes.XESLoader
import com.processm.processminterpreter.xes.XESParser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.measureTimeMillis

@Tag("Benchmark")
class BenchmarkTests : BaseInterpreterTest() {

    private lateinit var xesLoader: XESLoader
    private lateinit var logService: LogService

    @BeforeEach
    fun initLoader() {
        logService = Mockito.mock(LogService::class.java)
        xesLoader = XESLoader(XESParser(), logService, driver)
    }

    private fun generateSyntheticLog(traceCount: Int, eventsPerTrace: Int): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
        sb.append("<log xes.version=\"1.0\" xes.features=\"nested-attributes\" xmlns=\"http://www.xes-standard.org/\">\n")
        sb.append("    <extension name=\"Concept\" prefix=\"concept\" uri=\"http://www.xes-standard.org/concept.xesext\"/>\n")
        sb.append("    <extension name=\"Time\" prefix=\"time\" uri=\"http://www.xes-standard.org/time.xesext\"/>\n")
        sb.append("    <string key=\"concept:name\" value=\"Synthetic Benchmark Log\"/>\n")

        val baseTime = LocalDateTime.of(2023, 1, 1, 10, 0)
        
        for (i in 1..traceCount) {
            sb.append("    <trace>\n")
            sb.append("        <string key=\"concept:name\" value=\"Case $i\"/>\n")
            
            for (j in 1..eventsPerTrace) {
                val timestamp = baseTime.plusMinutes((i * eventsPerTrace + j).toLong())
                val activity = listOf("A", "B", "C", "D", "E").random()
                val resource = listOf("User1", "User2", "User3").random()
                val cost = (10..100).random().toDouble()
                
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
                val timestampStr = timestamp.format(formatter) + "Z"
                
                sb.append("        <event>\n")
                sb.append("            <string key=\"concept:name\" value=\"$activity\"/>\n")
                sb.append("            <date key=\"time:timestamp\" value=\"$timestampStr\"/>\n")
                sb.append("            <string key=\"org:resource\" value=\"$resource\"/>\n")
                sb.append("            <float key=\"cost:total\" value=\"$cost\"/>\n")
                sb.append("        </event>\n")
            }
            sb.append("    </trace>\n")
        }
        
        sb.append("</log>")
        return sb.toString()
    }

    @Test
    fun `test performance with large log`() {
        clearDatabase()
        
        val traceCount = 100 // Adjust for benchmark size
        val eventsPerTrace = 10
        
        println("Generating synthetic log with $traceCount traces and $eventsPerTrace events/trace...")
        val xesContent = generateSyntheticLog(traceCount, eventsPerTrace)
        val inputStream = ByteArrayInputStream(xesContent.toByteArray())
        
        println("Starting Import...")
        val importTime = measureTimeMillis {
            val result = xesLoader.loadXESFile(inputStream, "benchmark-log")
            assertTrue(result.success)
        }
        println("Import Time: ${importTime}ms")
        
        println("Starting Query Execution...")
        val query = "select t:caseId, count(e:eventId), sum(e:cost:total) group by t:caseId"
        val queryTime = measureTimeMillis {
            val result = pqlQueryService.executePQLQuery(query)
            assertTrue(result.success)
            assertTrue(result.results.isNotEmpty())
        }
        println("Query Time: ${queryTime}ms")
        
        // Assert reasonable performance (soft limits)
        // These are just sanity checks, not strict SLAs
        assertTrue(importTime < 10000, "Import took too long: ${importTime}ms") 
        assertTrue(queryTime < 5000, "Query took too long: ${queryTime}ms")
    }
}
