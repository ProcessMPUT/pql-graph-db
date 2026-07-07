package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readLines

class BenchmarkResultsWriterTest {
    @Test
    fun `writes query results with phase and count columns plus memory files`(
        @TempDir tempDir: Path,
    ) {
        val settings = BenchmarkSettings(
            profile = BenchmarkProfile.SMOKE,
            localApi = "http://localhost:8080/api",
            referenceApi = "http://localhost:80/api",
            processMLogin = "admin@example.com",
            processMPassword = "secret",
            outputRoot = tempDir,
            datasetFilter = emptySet(),
            systemFilter = emptySet(),
            keepBenchmarkDataStores = false,
        )
        val querySample = QueryBenchmarkResult(
            system = "local",
            datasetName = "trace-100",
            queryLabel = "hierarchyWindow",
            run = 0,
            seconds = 0.123,
            status = "OK",
            responseBytes = 456,
            phase = QUERY_PHASE_COLD,
            logCount = 1,
            traceCount = 10,
            eventCount = 100,
        )
        val memorySample = MemorySample(
            timestamp = "2026-07-02T10:00:00Z",
            phase = MEMORY_PHASE_IDLE,
            component = "processm-neo4j",
            bytes = 1_073_741_824,
        )
        val memorySummary = MemorySummary(
            component = "processm-neo4j",
            phase = MEMORY_PHASE_IDLE,
            medianBytes = 1_073_741_824,
            peakBytes = 2_147_483_648,
        )

        BenchmarkResultsWriter(tempDir).write(
            settings = settings,
            datasets = emptyList(),
            imports = emptyList(),
            queries = listOf(querySample),
            querySummaries = emptyList(),
            storage = emptyList(),
            roundtrips = emptyList(),
            cleanup = emptyList(),
            memorySamples = listOf(memorySample),
            memorySummaries = listOf(memorySummary),
        )

        val queryLines = tempDir.resolve("query-results.csv").readLines()
        assertEquals(
            "system,datasetName,queryLabel,run,phase,seconds,status,responseBytes,logCount,traceCount,eventCount,details",
            queryLines[0],
        )
        assertEquals("local,trace-100,hierarchyWindow,0,cold,0.123,OK,456,1,10,100,", queryLines[1])

        val memoryLines = tempDir.resolve("memory-results.csv").readLines()
        assertEquals("timestamp,phase,component,bytes", memoryLines[0])
        assertEquals("2026-07-02T10:00:00Z,idle,processm-neo4j,1073741824", memoryLines[1])

        val summaryLines = tempDir.resolve("memory-summary.csv").readLines()
        assertEquals("component,phase,medianBytes,peakBytes", summaryLines[0])
        assertEquals("processm-neo4j,idle,1073741824,2147483648", summaryLines[1])
    }

    @Test
    fun `summarize uses only successful warm samples`() {
        fun sample(
            run: Int,
            seconds: Double,
            phase: String = QUERY_PHASE_WARM,
            status: String = "OK",
        ) = QueryBenchmarkResult(
            system = "local",
            datasetName = "trace-100",
            queryLabel = "hierarchyWindow",
            run = run,
            seconds = seconds,
            status = status,
            responseBytes = 1,
            phase = phase,
        )

        val summaries = QueryStatistics.summarize(
            listOf(
                sample(run = 0, seconds = 9.0, phase = QUERY_PHASE_COLD),
                sample(run = 1, seconds = 1.0),
                sample(run = 2, seconds = 2.0),
                sample(run = 3, seconds = 3.0),
                sample(run = 4, seconds = 8.0, status = QUERY_STATUS_MISMATCH),
            ),
        )

        assertEquals(1, summaries.size)
        assertEquals(3, summaries.single().samples)
        assertEquals(2.0, summaries.single().medianSeconds)
        assertTrue(summaries.single().maxSeconds == 3.0, "cold/mismatch samples must not reach the summary")
    }
}
