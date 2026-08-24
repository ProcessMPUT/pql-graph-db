package com.processm.processminterpreter.benchmark

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BenchmarkAnalysisTest {
    @Test
    fun `query effect is reference divided by local and holm is scoped per dataset`() {
        val dataset = prepared("size-1k", "size-scaling")
        val specs = (1..5).map { index ->
            BenchmarkQuerySpec(
                label = "q$index",
                displayName = "Query $index",
                query = "limit l:1",
                role = if (index <= 3) BenchmarkQueryRole.PRIMARY else BenchmarkQueryRole.CONTROL,
                measurementSeries = listOf("size-scaling"),
            )
        } + BenchmarkQuerySpec(
            label = "baseline",
            query = "limit l:1",
            role = BenchmarkQueryRole.BASELINE,
            measurementSeries = listOf("size-scaling"),
        )
        val samples = specs.flatMap { spec ->
            (1..30).flatMap { run ->
                listOf(
                    querySample("local", dataset.name, spec.label, run, 1.0 + run / 1_000.0),
                    querySample("reference", dataset.name, spec.label, run, 2.0 + run / 1_000.0),
                )
            }
        }

        val rows = BenchmarkAnalysis.queryComparisons(listOf(dataset), specs, samples)
        val primary = rows.first()
        assertEquals(2.0, primary.ratioReferenceToLocal!!, 0.03)
        assertNotNull(primary.holmPValue)
        assertEquals("LOCAL_FASTER", primary.verdict)
        assertEquals("DESCRIPTIVE", rows.last().verdict)
        assertEquals(null, rows.last().holmPValue)
    }

    @Test
    fun `mismatch invalidates the complete paired comparison`() {
        val dataset = prepared("real", "real-validation")
        val spec = BenchmarkQuerySpec(
            label = "q",
            query = "limit l:1",
            role = BenchmarkQueryRole.PRIMARY,
            measurementSeries = listOf("real-validation"),
        )
        val samples = listOf(
            querySample("local", dataset.name, spec.label, 1, 1.0, QUERY_STATUS_MISMATCH),
            querySample("reference", dataset.name, spec.label, 1, 2.0, QUERY_STATUS_MISMATCH),
        )

        val row = BenchmarkAnalysis.queryComparisons(listOf(dataset), listOf(spec), samples).single()
        assertEquals("INVALID", row.status)
        assertEquals(0, row.pairs)
        assertTrue(row.details.contains("MISMATCH"))
    }

    @Test
    fun `truncated run cannot become a valid comparison during replay`() {
        val dataset = prepared("size-1k", "size-scaling")
        val spec = BenchmarkQuerySpec(
            label = "q",
            query = "limit l:1",
            role = BenchmarkQueryRole.PRIMARY,
            measurementSeries = listOf("size-scaling"),
        )
        val samples = listOf(
            querySample("local", dataset.name, spec.label, 1, 1.0),
            querySample("reference", dataset.name, spec.label, 1, 2.0),
        )

        val row = BenchmarkAnalysis.queryComparisons(
            listOf(dataset), listOf(spec), samples, expectedPairs = 30,
        ).single()

        assertEquals("INVALID", row.status)
        assertTrue(row.details.contains("expected 30 pairs"))
    }

    @Test
    fun `protocol 21 temporal drift invalidates an otherwise complete paired comparison`() {
        val dataset = prepared("size-5k", "size-scaling")
        val spec = BenchmarkQuerySpec(
            label = "minimalWindow",
            query = "limit l:1, t:1, e:1",
            role = BenchmarkQueryRole.PRIMARY,
            measurementSeries = listOf("size-scaling"),
        )
        val samples = (1..30).flatMap { run ->
            listOf(
                querySample("local", dataset.name, spec.label, run, if (run <= 10) 1.4 else 1.0),
                querySample("reference", dataset.name, spec.label, run, 2.0),
            )
        }

        val row = BenchmarkAnalysis.queryComparisons(
            datasets = listOf(dataset),
            querySpecs = listOf(spec),
            samples = samples,
            temporalGateMode = BenchmarkAnalysis.TemporalGateMode.PAIRED_RATIO_OF_MEDIANS,
        ).single()

        assertEquals("INVALID", row.status)
        assertEquals("INVALID", row.verdict)
        assertEquals(10, row.stabilityWindowSamples)
        assertEquals(1.4, row.localEarlyLateRatio!!, 1e-9)
        assertEquals(1.0, row.referenceEarlyLateRatio!!, 1e-9)
        assertEquals(1.4, row.pairedEarlyLateRatio!!, 1e-9)
        assertTrue(row.details.startsWith("${TemporalStability.DETAILS_PREFIX}: paired R/L"))
        assertTrue(row.details.contains("> ×1.100"))
    }

    @Test
    fun `current temporal drift is reported without discarding paired evidence`() {
        val dataset = prepared("size-5k", "size-scaling")
        val spec = BenchmarkQuerySpec(
            label = "minimalWindow",
            query = "limit l:1, t:1, e:1",
            role = BenchmarkQueryRole.PRIMARY,
            measurementSeries = listOf("size-scaling"),
        )
        val samples = (1..30).flatMap { run ->
            listOf(
                querySample("local", dataset.name, spec.label, run, if (run <= 10) 1.4 else 1.0),
                querySample("reference", dataset.name, spec.label, run, 2.0),
            )
        }

        val row = BenchmarkAnalysis.queryComparisons(listOf(dataset), listOf(spec), samples).single()

        assertEquals("OK", row.status)
        assertEquals("LOCAL_FASTER", row.verdict)
        assertNotNull(row.ratioReferenceToLocal)
        assertNotNull(row.holmPValue)
        assertEquals(1.4, row.pairedEarlyLateRatio!!, 1e-9)
        assertTrue(row.details.startsWith(TemporalStability.DIAGNOSTIC_DETAILS_PREFIX))
        assertTrue(row.details.contains("> ×1.100"))
    }

    @Test
    fun `common absolute drift remains diagnostic when the paired effect is stable`() {
        val dataset = prepared("size-5k", "size-scaling")
        val spec = BenchmarkQuerySpec(
            label = "hierarchyWindow",
            query = "limit l:1, t:10, e:20",
            role = BenchmarkQueryRole.PRIMARY,
            measurementSeries = listOf("size-scaling"),
        )
        val samples = (1..30).flatMap { run ->
            val commonScale = if (run <= 10) 1.4 else 1.0
            listOf(
                querySample("local", dataset.name, spec.label, run, commonScale),
                querySample("reference", dataset.name, spec.label, run, commonScale * 2.0),
            )
        }

        val row = BenchmarkAnalysis.queryComparisons(listOf(dataset), listOf(spec), samples).single()

        assertEquals("OK", row.status)
        assertEquals("LOCAL_FASTER", row.verdict)
        assertEquals(1.4, row.localEarlyLateRatio!!, 1e-9)
        assertEquals(1.4, row.referenceEarlyLateRatio!!, 1e-9)
        assertEquals(1.0, row.pairedEarlyLateRatio!!, 1e-9)
        assertTrue(row.details.startsWith(TemporalStability.COMMON_MODE_DETAILS_PREFIX))
    }

    @Test
    fun `legacy replay preserves the absolute temporal gate`() {
        val dataset = prepared("size-5k", "size-scaling")
        val spec = BenchmarkQuerySpec(
            label = "hierarchyWindow",
            query = "limit l:1, t:10, e:20",
            role = BenchmarkQueryRole.PRIMARY,
            measurementSeries = listOf("size-scaling"),
        )
        val samples = (1..30).flatMap { run ->
            val commonScale = if (run <= 10) 1.4 else 1.0
            listOf(
                querySample("local", dataset.name, spec.label, run, commonScale),
                querySample("reference", dataset.name, spec.label, run, commonScale * 2.0),
            )
        }

        val row = BenchmarkAnalysis.queryComparisons(
            datasets = listOf(dataset),
            querySpecs = listOf(spec),
            samples = samples,
            temporalGateMode = BenchmarkAnalysis.TemporalGateMode.LEGACY_ABSOLUTE_SYSTEM,
        ).single()

        assertEquals("INVALID", row.status)
        assertEquals("INVALID", row.verdict)
        assertTrue(row.details.startsWith("${TemporalStability.DETAILS_PREFIX}: LOCAL"))
        assertTrue(row.details.contains("REFERENCE"))
    }

    @Test
    fun `protocol 20 pair-ratio gate stays distinct from current ratio-of-medians gate`() {
        val dataset = prepared("size-1k", "size-scaling")
        val spec = BenchmarkQuerySpec(
            label = "hierarchyWindow",
            query = "limit l:1, t:10, e:20",
            role = BenchmarkQueryRole.PRIMARY,
            measurementSeries = listOf("size-scaling"),
        )
        val samples = (1..30).flatMap { run ->
            val (local, reference) = when {
                run <= 20 && run % 10 <= 5 && run % 10 != 0 -> 1.0 to 2.0
                run <= 20 -> 2.0 to 4.0
                run <= 25 -> 1.0 to 4.0
                else -> 2.0 to 2.0
            }
            listOf(
                querySample("local", dataset.name, spec.label, run, local),
                querySample("reference", dataset.name, spec.label, run, reference),
            )
        }

        val current = BenchmarkAnalysis.queryComparisons(listOf(dataset), listOf(spec), samples).single()
        val protocol20 = BenchmarkAnalysis.queryComparisons(
            datasets = listOf(dataset),
            querySpecs = listOf(spec),
            samples = samples,
            temporalGateMode = BenchmarkAnalysis.TemporalGateMode.PAIRED_SAMPLE_RATIOS,
        ).single()

        assertEquals("OK", current.status)
        assertEquals(1.0, current.pairedEarlyLateRatio!!, 1e-9)
        assertEquals("INVALID", protocol20.status)
        assertEquals(1.25, protocol20.pairedEarlyLateRatio!!, 1e-9)
        assertTrue(protocol20.details.contains("median paired effects"))
    }

    @Test
    fun `temporal drift remains visible but descriptive baseline stays usable`() {
        val dataset = prepared("size-5k", "size-scaling")
        val spec = BenchmarkQuerySpec(
            label = "minimalWindow",
            query = "limit l:1, t:1, e:1",
            role = BenchmarkQueryRole.BASELINE,
            measurementSeries = listOf("size-scaling"),
        )
        val samples = (1..30).flatMap { run ->
            listOf(
                querySample("local", dataset.name, spec.label, run, if (run <= 10) 1.4 else 1.0),
                querySample("reference", dataset.name, spec.label, run, 2.0),
            )
        }

        val row = BenchmarkAnalysis.queryComparisons(listOf(dataset), listOf(spec), samples).single()

        assertEquals("OK", row.status)
        assertEquals("DESCRIPTIVE", row.verdict)
        assertNotNull(row.ratioReferenceToLocal)
        assertEquals(1.4, row.localEarlyLateRatio!!, 1e-9)
        assertEquals(1.4, row.pairedEarlyLateRatio!!, 1e-9)
        assertTrue(row.details.startsWith(TemporalStability.DESCRIPTIVE_DETAILS_PREFIX))
    }

    private fun querySample(
        system: String,
        dataset: String,
        query: String,
        run: Int,
        seconds: Double,
        status: String = "OK",
    ) = QueryBenchmarkResult(system, dataset, query, run, seconds, status, 1, QUERY_PHASE_WARM)

    private fun prepared(name: String, series: String) = PreparedDataset(
        name, series, Path.of(name), 1, 1, 1, 1, 1, 1, 1,
    )
}
