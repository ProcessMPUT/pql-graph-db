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



    @Test
    fun `test performance with large log`() {
        clearDatabase()
        
        val traceCount = 100 // Adjust for benchmark size
        val eventsPerTrace = 10
        
        println("Generating synthetic log with $traceCount traces and $eventsPerTrace events/trace...")
        val xesContent = com.processm.processminterpreter.TestUtils.generateSyntheticLog(traceCount, eventsPerTrace)
        val inputStream = ByteArrayInputStream(xesContent.toByteArray())
        
        println("Starting Import...")
        val importTime = measureTimeMillis {
            val result = xesLoader.loadXESFile(inputStream, "benchmark-log")
            assertTrue(result.success)
        }
        println("Import Time: ${importTime}ms")
        
        println("Starting Query Execution...")
        val query = "select t:name, count(e:name), sum([e:cost:total]) group by t:name"
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
