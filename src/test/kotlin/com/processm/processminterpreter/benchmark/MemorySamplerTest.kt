package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MemorySamplerTest {
    @Test
    fun `explicit resource sample guarantees data for a short replay`() {
        val sampler = MemorySampler(
            sources = listOf(MemorySampler.MemorySource { listOf("component" to 456L) }),
        )

        sampler.sampleOnce(MEMORY_PHASE_QUERIES)

        assertEquals(1, sampler.samples().size)
        assertEquals(456L, sampler.samples().single().bytes)
        assertEquals(MEMORY_PHASE_QUERIES, sampler.samples().single().phase)
    }

    @Test
    fun `pause waits for an in-flight resource probe and prevents another one`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sampler = MemorySampler(
            sources = listOf(MemorySampler.MemorySource {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS)) { "test probe was not released" }
                listOf("component" to 123L)
            }),
            intervalMillis = 5,
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            sampler.start()
            sampler.setPhase(MEMORY_PHASE_QUERIES)
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            val pause = executor.submit { sampler.pauseAndAwaitQuiescence(2_000) }
            Thread.sleep(50)
            assertFalse(pause.isDone)
            release.countDown()
            pause.get(2, TimeUnit.SECONDS)

            val samplesAfterPause = sampler.samples().size
            Thread.sleep(30)
            assertEquals(samplesAfterPause, sampler.samples().size)
        } finally {
            release.countDown()
            sampler.stop()
            executor.shutdownNow()
        }
    }

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

    /**
     * The POSIX RSS probe backs the `local-jvm` series. It used to be missing entirely
     * (Windows-only `tasklist`), which silently dropped the LOCAL application JVM from
     * the Q3 memory comparison and understated this system's footprint.
     */
    @Test
    fun `parses ps rss output in kilobytes`() {
        assertEquals(1_234_567L * 1024, parsePsRssBytes(" 1234567\n"))
        assertEquals(4096L * 1024, parsePsRssBytes("  RSS\n4096\n"))
        assertNull(parsePsRssBytes(""))
        assertNull(parsePsRssBytes("ps: no such process\n"))
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
                MemorySummary("jvm", MEMORY_PHASE_QUERIES, medianBytes = 60, peakBytes = 70),
                MemorySummary("neo4j", MEMORY_PHASE_IDLE, medianBytes = 200, peakBytes = 300),
                MemorySummary("neo4j", MEMORY_PHASE_QUERIES, medianBytes = 500, peakBytes = 500),
            ),
            summaries,
        )
    }

    @Test
    fun `summarize computes system totals before taking their median`() {
        val samples = listOf(
            MemorySample("t1", MEMORY_PHASE_QUERIES, "processm-interpreter", 10),
            MemorySample("t1", MEMORY_PHASE_QUERIES, "processm-neo4j", 100),
            MemorySample("t1", MEMORY_PHASE_QUERIES, "processm-server", 80),
            MemorySample("t2", MEMORY_PHASE_QUERIES, "processm-interpreter", 100),
            MemorySample("t2", MEMORY_PHASE_QUERIES, "processm-neo4j", 10),
            MemorySample("t2", MEMORY_PHASE_QUERIES, "processm-server", 90),
        )

        val summaries = summarizeMemory(samples).associateBy { it.component }
        assertEquals(110, summaries.getValue("local-total").medianBytes)
        assertEquals(85, summaries.getValue("reference-total").medianBytes)
    }

    @Test
    fun `summaries keep dataset and operation blocks separate`() {
        val samples = listOf(
            MemorySample("t1", MEMORY_PHASE_QUERIES, "processm-interpreter", 10, "bpi11", "q1"),
            MemorySample("t1", MEMORY_PHASE_QUERIES, "processm-neo4j", 20, "bpi11", "q1"),
            MemorySample("t2", MEMORY_PHASE_QUERIES, "processm-interpreter", 100, "bpi12", "q1"),
            MemorySample("t2", MEMORY_PHASE_QUERIES, "processm-neo4j", 200, "bpi12", "q1"),
        )

        val totals = summarizeMemory(samples).filter { it.component == "local-total" }

        assertEquals(2, totals.size)
        assertEquals(30, totals.single { it.datasetName == "bpi11" }.medianBytes)
        assertEquals(300, totals.single { it.datasetName == "bpi12" }.medianBytes)
    }
}
