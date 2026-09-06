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

class BenchmarkProgressTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `active request is visible without claiming completion or changing on a read`(@TempDir directory: Path) {
        val progress = BenchmarkProgress(directory, "coverage-size")
        val path = directory.resolve("live-progress.json")
        progress.operation("warm", "size-1m", "hierarchyWindow", "reference", 17, 30) {
            val bytes = Files.readString(path)
            val state = mapper.readTree(bytes)
            assertEquals("RUNNING", state["status"].asText())
            assertEquals(0L, state["completedOperations"].asLong())
            assertTrue(state["lastCompleted"].isNull)
            assertEquals("reference", state["current"]["system"].asText())
            assertEquals("size-1m", state["current"]["dataset"].asText())
            assertEquals("hierarchyWindow", state["current"]["query"].asText())
            assertEquals(17, state["current"]["repetition"].asInt())
            assertEquals(30, state["current"]["total"].asInt())
            assertTrue(state["current"]["startedAt"].asText().isNotBlank())
            assertEquals(bytes, Files.readString(path))
        }
        val completed = mapper.readTree(Files.readString(path))
        assertEquals(1L, completed["completedOperations"].asLong())
        assertTrue(completed["current"].isNull)
        assertEquals("reference", completed["lastCompleted"]["system"].asText())
        assertTrue(completed["lastCompleted"]["completedAt"].asText().isNotBlank())
        progress.finish(null)
        assertEquals("COMPLETED", mapper.readTree(Files.readString(path))["status"].asText())
        assertFalse(Files.exists(directory.resolve("live-progress.json.tmp")))
    }

    @Test
    fun `failed request remains diagnosed after cleanup without becoming successful progress`(@TempDir directory: Path) {
        val progress = BenchmarkProgress(directory, "coverage-size")
        val failure = assertFailsWith<IllegalStateException> {
            progress.operation("import", dataset = "size-1m", system = "reference") {
                error("Readiness timeout")
            }
        }
        progress.operation("cleanup", system = "reference") { }
        progress.finish(failure)
        val state = mapper.readTree(Files.readString(directory.resolve("live-progress.json")))
        assertEquals("FAILED", state["status"].asText())
        assertEquals(1L, state["completedOperations"].asLong())
        assertEquals("cleanup", state["lastCompleted"]["phase"].asText())
        assertEquals("import", state["lastFailure"]["phase"].asText())
        assertEquals("size-1m", state["lastFailure"]["dataset"].asText())
        assertEquals("Readiness timeout", state["failure"]["message"].asText())
        assertTrue(state["current"].isNull)
    }
}
