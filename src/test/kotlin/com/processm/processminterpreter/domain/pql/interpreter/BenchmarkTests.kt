package com.processm.processminterpreter.domain.pql.interpreter

import com.processm.processminterpreter.TestUtils
import com.processm.processminterpreter.application.query.DataStorePqlQueryResult
import com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.Neo4jXesLogWriter
import com.processm.processminterpreter.infrastructure.xes.XESLoadResult
import com.processm.processminterpreter.infrastructure.xes.XESLoader
import com.processm.processminterpreter.infrastructure.xes.XESParser
import com.processm.processminterpreter.infrastructure.xes.OpenXesReader
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
        xesLoader = XESLoader(OpenXesReader(XESParser()), Neo4jXesLogWriter(driver))
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
        attachLogToInterpreterDataStore(importResult.logId ?: "benchmark-log")
        assertEquals(traceCount, importResult.tracesCount, "Expected $traceCount traces imported")
        assertEquals(traceCount * eventsPerTrace, importResult.eventsCount, "Expected ${traceCount * eventsPerTrace} events imported")

        println("Starting Query Execution...")
        val query = "select t:name, count(e:name), sum([e:cost:total]) group by t:name"
        lateinit var queryResult: DataStorePqlQueryResult
        val queryTime =
            measureTimeMillis {
                queryResult = executeDataStoreQuery(query)
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


