package com.processm.processminterpreter.pql.interpreter

import com.processm.processminterpreter.TestUtils
import com.processm.processminterpreter.service.PQLQueryResult
import com.processm.processminterpreter.xes.XESLoadResult
import com.processm.processminterpreter.xes.XESLoader
import com.processm.processminterpreter.xes.XESParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

import java.io.ByteArrayInputStream
import kotlin.system.measureTimeMillis

@Tag("Benchmark")
class BenchmarkTests : BaseInterpreterTest() {
    private lateinit var xesLoader: XESLoader

    @BeforeEach
    fun initLoader() {
        xesLoader = XESLoader(XESParser(), driver)
    }

    @Test
    fun `test performance with large log`() {
        clearDatabase()

        val traceCount = 100 // Adjust for benchmark size
        val eventsPerTrace = 10

        println("Generating synthetic log with $traceCount traces and $eventsPerTrace events/trace...")
        val xesContent = TestUtils.generateSyntheticLog(traceCount, eventsPerTrace)
        val inputStream = ByteArrayInputStream(xesContent.toByteArray())

        println("Starting Import...")
        lateinit var importResult: XESLoadResult
        val importTime =
            measureTimeMillis {
                importResult = xesLoader.loadXESFile(inputStream, "benchmark-log")
            }
        println("Import Time: ${importTime}ms")
        assertTrue(importResult.success)
        assertEquals(traceCount, importResult.tracesCount, "Expected $traceCount traces imported")
        assertEquals(traceCount * eventsPerTrace, importResult.eventsCount, "Expected ${traceCount * eventsPerTrace} events imported")

        println("Starting Query Execution...")
        val query = "select t:name, count(e:name), sum([e:cost:total]) group by t:name"
        lateinit var queryResult: PQLQueryResult
        val queryTime =
            measureTimeMillis {
                queryResult = pqlQueryService.executePQLQuery(query)
            }
        println("Query Time: ${queryTime}ms")
        assertTrue(queryResult.success)
        assertEquals(traceCount, queryResult.results.size, "GROUP BY t:name should produce one row per distinct trace name")

        // Assert reasonable performance (soft limits)
        // These are just sanity checks, not strict SLAs
        assertTrue(importTime < 10000, "Import took too long: ${importTime}ms")
        assertTrue(queryTime < 5000, "Query took too long: ${queryTime}ms")
    }
}
