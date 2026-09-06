package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionEvidenceTest {
    @Test
    fun `an earlier equal-count value mismatch cannot be hidden by the final response`() {
        fun body(value: String) = """[{"log":{"string":[{"@key":"concept:name","@value":"$value"}]}}]"""
        val samples = (1..2).flatMap { n -> listOf("local", "reference").map { system ->
            QueryBenchmarkResult(system, "d", "q", n, .01, "OK", 10, logCount = 1)
        } }
        val bodies = mapOf((1 to "local") to body("a"), (1 to "reference") to body("b"),
            (2 to "local") to body("a"), (2 to "reference") to body("a"))
        val checked = applyEveryResponseParity(samples, bodies)
        assertTrue(checked.filter { it.run == 1 }.all { it.status == QUERY_STATUS_MISMATCH })
        assertTrue(checked.filter { it.run == 2 }.all { it.status == "OK" })
        assertTrue(applyEveryResponseParity(samples, emptyMap()).all { it.status == QUERY_STATUS_MISMATCH })
    }

    @Test
    fun `journal preserves completed block and content-addressed response`(@TempDir directory: Path) {
        val journal = BenchmarkJournal(directory, "run-1")
        journal.event("query-block-started", mapOf("query" to "q"))
        val response = journal.response("[]")
        journal.event("query-block-completed", mapOf("response" to response))
        assertEquals(response, journal.response("[]"))
        assertEquals("[]", Files.readString(directory.resolve(response)))
        val records = Files.readAllLines(directory.resolve("execution.jsonl")).map { jacksonObjectMapper().readTree(it) }
        assertEquals(listOf(1L,2L,3L), records.map { it["sequence"].asLong() })
        val progress = Files.readString(directory.resolve("live-progress.json"))
        assertFailsWith<IllegalStateException> { BenchmarkJournal(directory, "another-run") }
        assertEquals(progress, Files.readString(directory.resolve("live-progress.json")))
    }

    @Test
    fun `variant ranking projects no values varying between tied groups`() {
        val queries = BenchmarkConfig.catalog().queries
        for (label in listOf("variantGroupCount", "genericVariantGroup")) {
            val projection = queries.single { it.label == label }.query.substringBefore(" group by ")
            assertEquals("select count(t:name)", projection)
        }
        assertFalse(queries.single { it.label == "hoistedPositive" }.displayName.contains("Selektywny"))
        assertEquals(0, queries.single { it.label == "realLikeNoMatch" }.maximumResponseEvents)
    }
}
