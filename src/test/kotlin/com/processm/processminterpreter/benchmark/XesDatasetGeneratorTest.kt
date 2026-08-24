package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.inputStream

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
        assertEquals(65, stats.totalAttributes)
        assertEquals(stats.totalAttributes, dataset.totalAttributes)
        assertEquals(4.0, stats.meanEventsPerTrace)
        assertEquals(4.0, stats.medianEventsPerTrace)
        assertEquals(4, stats.p95EventsPerTrace)
        assertEquals(4, stats.maxEventsPerTrace)
        assertEquals(4, stats.activityCount)
        assertEquals(1, stats.variantCount)
        assertEquals(5.0, stats.meanEventAttributes)
        assertEquals(5.0, dataset.meanEventAttributes)
        assertEquals(64, dataset.fileSha256.length)

        val xml = GZIPInputStream(dataset.file.inputStream()).bufferedReader().use { it.readText() }
        assertEquals(12, Regex("value=\"v-1\"").findAll(xml).count())
        assertEquals(12, Regex("value=\"v-2\"").findAll(xml).count())
        assertEquals(0, Regex("value=\"v-[0-9]+-[0-9]+-").findAll(xml).count())
    }

    @Test
    fun `variant generator changes only the requested trace sequences`() {
        val dataset = XesDatasetGenerator().prepare(
            BenchmarkDatasetSpec(
                type = DatasetType.SYNTHETIC,
                name = "test-variants",
                series = "variant-scaling",
                traces = 12,
                eventsPerTrace = 6,
                attributesPerEvent = 2,
                activityCount = 4,
                variantCount = 12,
            ),
            tempDir,
        )

        val stats = XesDatasetInspector.inspect(dataset.file)

        assertEquals(72, stats.events)
        assertEquals(4, stats.activityCount)
        assertEquals(12, stats.variantCount)
        assertEquals(5.0, stats.meanEventAttributes)
        assertEquals(6.0, stats.meanEventsPerTrace)
        assertEquals(6.0, stats.medianEventsPerTrace)
    }

    @Test
    fun `full trace ladder stays inside the common import domain`() {
        val traceCounts = BenchmarkConfig.load(BenchmarkProfile.FULL)
            .datasets
            .filter { it.series == "size-scaling" }
            .map { it.traces }

        assertEquals(listOf(100, 500, 2_000, 10_000, 20_000, 50_000, 100_000), traceCounts)
        assertEquals(23, CURRENT_BENCHMARK_PROTOCOL_VERSION)
    }

    @Test
    fun `full real logs stay below stock reference compressed input limit`() {
        val realFiles = BenchmarkConfig.load(BenchmarkProfile.FULL)
            .datasets
            .filter { it.series == "real-validation" }
            .map { Path.of(requireNotNull(it.resourcePath)) }

        assertEquals(12, realFiles.size)
        realFiles.forEach { file ->
            require(file.toFile().length() < 5L * 1024 * 1024) {
                "$file exceeds the stock REFERENCE compressed XES limit"
            }
        }
    }
}
