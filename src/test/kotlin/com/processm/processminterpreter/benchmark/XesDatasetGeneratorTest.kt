package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class XesDatasetGeneratorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `synthetic generator produces expected trace event and attribute counts`() {
        val dataset = XesDatasetGenerator().prepare(
            BenchmarkDatasetSpec(
                type = DatasetType.SYNTHETIC,
                name = "test-synthetic",
                series = "test",
                traces = 3,
                eventsPerTrace = 4,
                attributesPerEvent = 2,
            ),
            tempDir,
        )

        val stats = XesDatasetInspector.inspect(dataset.file)

        assertEquals(3, dataset.traces)
        assertEquals(12, dataset.totalEvents)
        assertEquals(2, dataset.attributesPerEvent)
        assertEquals(3, stats.traces)
        assertEquals(12, stats.events)
        assertEquals(60, stats.eventAttributes)
    }

    @Test
    fun `full trace ladder stays inside the common import domain`() {
        val traceCounts = BenchmarkConfig.load(BenchmarkProfile.FULL)
            .datasets
            .filter { it.series == "trace-scaling" }
            .map { it.traces }

        assertEquals(listOf(100, 500, 2_000, 10_000, 20_000), traceCounts)
        assertEquals(10, CURRENT_BENCHMARK_PROTOCOL_VERSION)
    }
}
