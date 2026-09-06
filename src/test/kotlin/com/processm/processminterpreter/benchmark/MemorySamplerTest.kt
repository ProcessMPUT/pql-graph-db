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
            sampler.setPhase("queries")
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
    fun `coverage requires all active components in one complete in-window probe`() {
        fun sample(t: String, c: String) = MemorySample(t, "queries", c, 100, "d", "q", activeSystem = "local")
        val samples = listOf(
            sample("1", "app"), sample("1", "db"),
            sample("2", "app"), // incomplete
            sample("3", "app"), sample("3", "db").copy(withinWindow = false),
            sample("4", "app"), sample("4", "db").copy(activeSystem = "reference"),
            sample("5", "app"), sample("5", "db").copy(operationLabel = "other"),
            sample("6", "app"), sample("6", "db"), sample("6", "db"), // duplicate
        )
        assertEquals(1, completeMemorySampleCount(samples, "d", "q", "local", setOf("app", "db")))
    }
}
