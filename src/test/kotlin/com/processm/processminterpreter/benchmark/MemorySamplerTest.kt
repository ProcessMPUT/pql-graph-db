package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MemorySamplerTest {
    @Test
    fun `parses docker stats memory usage lines`() {
        assertEquals(
            "processm-neo4j" to (1.5 * 1024 * 1024 * 1024).toLong(),
            parseDockerStatsLine("processm-neo4j;1.5GiB / 7.653GiB"),
        )
        assertEquals(
            "processm-server" to (988.4 * 1024 * 1024).toLong(),
            parseDockerStatsLine("processm-server;988.4MiB / 7.653GiB"),
        )
        assertNull(parseDockerStatsLine(""))
        assertNull(parseDockerStatsLine("no-separator"))
        assertNull(parseDockerStatsLine("name;not-a-size / 1GiB"))
    }

    @Test
    fun `parses docker size units`() {
        assertEquals(73L, parseByteSize("73B"))
        assertEquals(512_000L, parseByteSize("512kB"))
        assertEquals(2048L, parseByteSize("2KiB"))
        assertEquals(3_000_000L, parseByteSize("3MB"))
        assertNull(parseByteSize("1.5XB"))
        assertNull(parseByteSize(""))
    }

    @Test
    fun `parses tasklist csv memory field`() {
        val output = "\"java.exe\",\"31337\",\"Console\",\"1\",\"1,234,567 K\"\r\n"
        assertEquals(1_234_567L * 1024, parseTasklistMemoryBytes(output))
        assertNull(parseTasklistMemoryBytes("INFO: No tasks are running which match the specified criteria."))
    }

    @Test
    fun `summarize reports median and peak per component and phase`() {
        fun sample(component: String, phase: String, bytes: Long) =
            MemorySample(timestamp = "t", phase = phase, component = component, bytes = bytes)

        val summaries = summarizeMemory(
            listOf(
                sample("neo4j", MEMORY_PHASE_IDLE, 100),
                sample("neo4j", MEMORY_PHASE_IDLE, 300),
                sample("neo4j", MEMORY_PHASE_IDLE, 200),
                sample("neo4j", MEMORY_PHASE_QUERIES, 500),
                sample("jvm", MEMORY_PHASE_QUERIES, 50),
                sample("jvm", MEMORY_PHASE_QUERIES, 70),
            ),
        )

        assertEquals(
            listOf(
                MemorySummary("jvm", MEMORY_PHASE_QUERIES, medianBytes = 50, peakBytes = 70),
                MemorySummary("neo4j", MEMORY_PHASE_IDLE, medianBytes = 200, peakBytes = 300),
                MemorySummary("neo4j", MEMORY_PHASE_QUERIES, medianBytes = 500, peakBytes = 500),
            ),
            summaries,
        )
    }
}
