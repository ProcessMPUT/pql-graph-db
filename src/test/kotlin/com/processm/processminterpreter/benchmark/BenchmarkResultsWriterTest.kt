package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkResultsWriterTest {
    @Test
    fun `checkpoint retains raw evidence and never turns a failure into zero latency`(@TempDir out: Path) {
        val success = QueryBenchmarkResult("local", "d", "q", 1, .123, "OK", 10,
            executionIndex = 1, responsePath = "responses/a.json")
        val samples = listOf(success, success.copy(run = 2, status = "ERROR", seconds = 0.0),
            success.copy(run = 3, status = "MISMATCH", seconds = .456))
        BenchmarkResultsWriter(out).checkpoint(emptyList(), emptyList(), emptyList(), samples, emptyList(),
            listOf(MemorySample("t", "queries", "processm-server", 100, activeSystem = "reference", withinWindow = false)),
            emptyList())
        val rows = Files.readAllLines(out.resolve("query-results.csv")).map { it.split(',') }
        val time = rows.first().indexOf("seconds")
        assertEquals(listOf("0.123", "", "0.456"), rows.drop(1).map { it[time] })
        assertTrue(Files.readString(out.resolve("query-results.csv")).contains("responses/a.json"))
        assertTrue(Files.readString(out.resolve("memory-results.csv")).contains("reference,false"))
        Files.list(out).use { files ->
            assertEquals(setOf("datasets.csv", "queries.csv", "import-results.csv", "query-results.csv",
                "roundtrip-results.csv", "memory-results.csv", "container-io.csv"), files.map { it.fileName.toString() }.toList().toSet())
        }
    }

    @Test
    fun `environment records actual job counts and source evidence without credentials`(@TempDir out: Path) {
        val job = StudyJob("pilot", "pilot", "pilot", "a".repeat(64), "draft", listOf("size-100k"),
            listOf("minimalWindow"), 91, 7, 8, 3, 11, 13, 5, "q", "d",
            resourceProbe = StudyResourceProbe("size-100k", "minimalWindow"))
        BenchmarkResultsWriter(out).writeEnvironment(job, BenchmarkSettings("local-api", "ref-api", "secret-login", "secret-password"),
            mapOf("source" to mapOf("gitCommit" to "revision")))
        val text = Files.readString(out.resolve("environment.json"))
        val json = jacksonObjectMapper().readTree(text)
        for ((key, value) in mapOf("warmups" to 7, "repetitions" to 8, "importRepetitions" to 3,
            "globalWarmupRounds" to 11, "resourceWindowSeconds" to 13, "resourceMinimumSamples" to 5, "datasetOrderSeed" to 91)) {
            assertEquals(value, json[key].asInt())
        }
        assertEquals("revision", json["source"]["gitCommit"].asText())
        assertFalse(text.contains("secret"))
    }
}
