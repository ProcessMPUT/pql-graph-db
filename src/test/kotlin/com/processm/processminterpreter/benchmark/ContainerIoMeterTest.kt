package com.processm.processminterpreter.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainerIoMeterTest {
    @Test
    fun `parses docker block and network counters`() {
        val parsed = parseContainerIoStatsLine("processm-neo4j;1.5MB / 2KiB;800kB / 3.25MB")

        assertEquals("processm-neo4j", parsed?.first)
        assertEquals(1_500_000L, parsed?.second?.blockReadBytes)
        assertEquals(2_048L, parsed?.second?.blockWriteBytes)
        assertEquals(800_000L, parsed?.second?.networkReceiveBytes)
        assertEquals(3_250_000L, parsed?.second?.networkTransmitBytes)
    }

    @Test
    fun `rejects malformed docker counter pairs`() {
        assertNull(parseDockerCounterPair("12MB"))
        assertNull(parseContainerIoStatsLine("container;12MB / 2MB"))
    }

    @Test
    fun `sums cgroup operations over devices`() {
        val output = """
            8:0 rbytes=100 wbytes=200 rios=3 wios=4 dbytes=0 dios=0
            8:16 rbytes=50 wbytes=70 rios=5 wios=6 dbytes=0 dios=0
        """.trimIndent()

        assertEquals(CgroupIoCounters(150, 270, 8, 10), parseCgroupIoCounters(output))
        assertEquals(CgroupIoCounters(100, 200, 0, 0), parseCgroupIoCounters("8:0 rbytes=100 wbytes=200"))
        assertNull(parseCgroupIoCounters(""))
    }

    @Test
    fun `retries an incomplete multi-container docker snapshot`() {
        var reads = 0
        var pauses = 0

        val result = retryCompleteSnapshot(
            expectedKeys = setOf("app", "database"),
            attempts = 3,
            pause = { pauses++ },
        ) {
            reads++
            if (reads == 1) mapOf("app" to 1) else mapOf("app" to 2, "database" to 3)
        }

        assertEquals(mapOf("app" to 2, "database" to 3), result)
        assertEquals(2, reads)
        assertEquals(1, pauses)
    }

    @Test
    fun `returns the last incomplete snapshot after bounded retries`() {
        var pauses = 0

        val result = retryCompleteSnapshot(
            expectedKeys = setOf("app", "database"),
            attempts = 3,
            pause = { pauses++ },
        ) { mapOf("app" to 1) }

        assertEquals(mapOf("app" to 1), result)
        assertEquals(2, pauses)
        assertTrue("database" !in result)
    }
}
