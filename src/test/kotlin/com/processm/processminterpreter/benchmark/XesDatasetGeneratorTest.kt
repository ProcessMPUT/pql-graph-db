package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.inputStream
import kotlin.test.assertFailsWith

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
    fun `selectivity distributes exact matching traces throughout input without changing other values`() {
        val spec = BenchmarkDatasetSpec(DatasetType.SYNTHETIC, "selectivity-test", "selectivity-scaling",
            traces = 1000, eventsPerTrace = 10, attributesPerEvent = 5, activityCount = 10)
        var baseline: String? = null
        for (percent in listOf(1, 10, 50, 100)) {
            val generated = XesDatasetGenerator().prepare(spec.copy(matchingTracePercent = percent), tempDir.resolve("p$percent"))
            val xml = GZIPInputStream(generated.file.inputStream()).bufferedReader().use { it.readText() }
            val traces = xml.split("<trace>").drop(1)
            val expectedPositions = (100 / percent - 1 until 1000 step 100 / percent).toList()
            val selected = traces.indices.filter { traces[it].contains("key=\"attr_1\" value=\"hit\"") }
            assertEquals(expectedPositions, selected)
            assertEquals(percent * 100, Regex("key=\"attr_1\" value=\"hit\"").findAll(xml).count())
            traces.forEachIndexed { i, trace ->
                val value = if (i in expectedPositions) "hit" else "out"
                assertEquals(10, Regex("key=\"attr_1\" value=\"$value\"").findAll(trace).count())
            }
            val normalized = xml.replace("key=\"attr_1\" value=\"hit\"", "key=\"attr_1\" value=\"v-1\"")
                .replace("key=\"attr_1\" value=\"out\"", "key=\"attr_1\" value=\"v-1\"")
            if (baseline == null) baseline = normalized else assertEquals(baseline, normalized)
            val stats = XesDatasetInspector.inspect(generated.file)
            assertEquals(10, stats.activityCount)
            assertEquals(1, stats.variantCount)
            assertEquals(8.0, stats.meanEventAttributes)
        }
    }

    @Test
    fun `trace length preserves event count activity histogram cost histogram and timestamps`() {
        val spec = BenchmarkDatasetSpec(DatasetType.SYNTHETIC, "shape-test", "trace-length-scaling",
            attributesPerEvent = 5, activityCount = 10, costCycleLength = 10)
        val timestampSequences = mutableListOf<List<String>>()
        for (length in listOf(10, 100, 1000)) {
            val generated = XesDatasetGenerator().prepare(spec.copy(traces = 10000 / length, eventsPerTrace = length),
                tempDir.resolve("length$length"))
            val xml = GZIPInputStream(generated.file.inputStream()).bufferedReader().use { it.readText() }
            val costs = Regex("key=\"cost:total\" value=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1].toDouble() }
                .groupingBy { it }.eachCount()
            assertEquals((1..10).associate { it * 1.25 to 1000 }, costs)
            val activities = Regex("value=\"activity-([0-9]+)\"").findAll(xml).map { it.groupValues[1].toInt() }
                .groupingBy { it }.eachCount()
            assertEquals((1..10).associateWith { 1000 }, activities)
            timestampSequences += Regex("key=\"time:timestamp\" value=\"([^\"]+)\"").findAll(xml)
                .map { it.groupValues[1] }.toList()
            val stats = XesDatasetInspector.inspect(generated.file)
            assertEquals(10000, stats.events)
            assertEquals(10000 / length, stats.traces)
            assertEquals(length, stats.maxEventsPerTrace)
            assertEquals(8.0, stats.meanEventAttributes)
            assertEquals(1, stats.variantCount)
        }
        assertTrue(timestampSequences.distinct().size == 1)
    }

    @Test
    fun `controlled generators reject rounded proportions or incomplete cost cycles`() {
        val spec = BenchmarkDatasetSpec(DatasetType.SYNTHETIC, "invalid", "controlled", traces = 100,
            eventsPerTrace = 10, attributesPerEvent = 5, matchingTracePercent = 1)
        for (invalid in listOf(spec.copy(traces = 99), spec.copy(matchingTracePercent = 0),
            spec.copy(matchingTracePercent = 101), spec.copy(attributesPerEvent = 0), spec.copy(costCycleLength = 3))) {
            assertFailsWith<IllegalArgumentException> { XesDatasetGenerator().prepare(invalid, tempDir) }
        }
    }

    @Test
    fun `full trace ladder stays inside the common import domain`() {
        val traceCounts = BenchmarkConfig.catalog()
            .datasets
            .filter { it.series == "size-scaling" }
            .map { it.traces }

        assertEquals(listOf(100, 500, 2_000, 10_000, 20_000, 50_000, 100_000), traceCounts)
        assertEquals(27, CURRENT_BENCHMARK_PROTOCOL_VERSION)
    }

    @Test
    fun `full real logs stay below stock reference compressed input limit`() {
        val realFiles = BenchmarkConfig.catalog()
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
