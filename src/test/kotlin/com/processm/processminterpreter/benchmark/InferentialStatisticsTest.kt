package com.processm.processminterpreter.benchmark

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InferentialStatisticsTest {
    @Test
    fun `normal cdf matches known quantiles`() {
        assertEquals(0.5, InferentialStatistics.standardNormalCdf(0.0), 1e-6)
        assertEquals(0.975, InferentialStatistics.standardNormalCdf(1.959964), 1e-5)
        assertEquals(0.99, InferentialStatistics.standardNormalCdf(2.326348), 1e-5)
    }

    @Test
    fun `wilcoxon separates consistently different paired samples`() {
        val a = (1..30).map { it.toDouble() }
        val b = (101..130).map { it.toDouble() }
        assertTrue(InferentialStatistics.wilcoxonSignedRank(a, b) < 1e-6, "consistently signed differences must be significant")
    }

    @Test
    fun `wilcoxon finds no difference between identical pairs`() {
        val a = (1..30).map { it.toDouble() }
        assertEquals(1.0, InferentialStatistics.wilcoxonSignedRank(a, a), 1e-9)
    }

    @Test
    fun `wilcoxon rejects an incomplete pairing`() {
        assertEquals(1.0, InferentialStatistics.wilcoxonSignedRank(listOf(1.0), listOf(2.0, 3.0)))
    }

    @Test
    fun `holm adjustment is monotone and matches R p adjust`() {
        // R: p.adjust(c(0.01, 0.02, 0.03, 0.04), method = "holm")
        //    -> 0.04 0.06 0.06 0.06
        val adjusted = InferentialStatistics.holmAdjust(listOf(0.01, 0.02, 0.03, 0.04))
        assertEquals(0.04, adjusted[0], 1e-12)
        assertEquals(0.06, adjusted[1], 1e-12)
        assertEquals(0.06, adjusted[2], 1e-12)
        assertEquals(0.06, adjusted[3], 1e-12)
    }

    @Test
    fun `holm adjustment never exceeds one`() {
        val adjusted = InferentialStatistics.holmAdjust(listOf(0.5, 0.6, 0.9))
        assertTrue(adjusted.all { it <= 1.0 }, "adjusted p-values must stay in [0, 1]: $adjusted")
    }

    @Test
    fun `bootstrap interval brackets a clear ratio and excludes unity`() {
        val local = List(30) { 4.0 + it * 0.01 }
        val reference = List(30) { 8.0 + it * 0.01 }
        val ci = InferentialStatistics.pairedMedianRatioConfidenceInterval(local, reference, seed = 42)
        assertNotNull(ci)
        assertEquals(0.5, ci.point, 0.02)
        assertTrue(ci.low <= ci.point && ci.point <= ci.high, "point estimate must lie inside the interval")
        assertTrue(ci.excludesUnity(), "a two-fold difference must resolve its direction")
    }

    @Test
    fun `bootstrap interval covers unity for indistinguishable samples`() {
        val samples = List(30) { 5.0 + (it % 7) * 0.1 }
        val ci = InferentialStatistics.pairedMedianRatioConfidenceInterval(samples, samples, seed = 7)
        assertNotNull(ci)
        assertFalse(ci.excludesUnity(), "identical samples must not resolve a direction")
    }

    @Test
    fun `bootstrap interval is deterministic for a given seed`() {
        val local = List(30) { 3.0 + (it % 5) * 0.2 }
        val reference = List(30) { 5.0 + (it % 3) * 0.3 }
        val first = InferentialStatistics.pairedMedianRatioConfidenceInterval(local, reference, seed = 123)
        val second = InferentialStatistics.pairedMedianRatioConfidenceInterval(local, reference, seed = 123)
        assertEquals(first, second, "the reported interval must be reproducible from the artifacts")
    }

    @Test
    fun `linear fit recovers a known line`() {
        val xs = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val ys = xs.map { 7.0 + 3.0 * it }
        val fit = InferentialStatistics.fitLinear(xs, ys)
        assertNotNull(fit)
        assertEquals(7.0, fit.intercept, 1e-9)
        assertEquals(3.0, fit.slope, 1e-9)
        assertEquals(1.0, fit.r2, 1e-9)
    }

    @Test
    fun `power law fit recovers the scaling exponent`() {
        val xs = listOf(100.0, 500.0, 2000.0, 10000.0)
        val ys = xs.map { 0.5 * Math.pow(it, 0.75) }
        val fit = InferentialStatistics.fitPowerLaw(xs, ys)
        assertNotNull(fit)
        assertEquals(0.75, fit.slope, 1e-9)
        assertEquals(1.0, fit.r2, 1e-9)
    }

    @Test
    fun `power law fit reports a flat exponent for size-independent timings`() {
        // The measured shape of every `limit`-bounded query: constant work,
        // so the "scaling" plot is noise around a horizontal line.
        val xs = listOf(100.0, 500.0, 2000.0, 10000.0)
        val ys = listOf(6.84, 5.26, 5.56, 6.61)
        val fit = InferentialStatistics.fitPowerLaw(xs, ys)
        assertNotNull(fit)
        assertTrue(abs(fit.slope) < 0.05, "expected a flat exponent, was ${fit.slope}")
        assertTrue(fit.r2 < 0.05, "expected no explanatory power, R2 was ${fit.r2}")
    }

    @Test
    fun `fit needs at least three points`() {
        assertEquals(null, InferentialStatistics.fitLinear(listOf(1.0, 2.0), listOf(1.0, 2.0)))
    }

    @Test
    fun `replicate groups find datasets with identical shape`() {
        val groups = ReplicateControl.replicateGroups(
            listOf(
                dataset("trace-100", traces = 100, eventsPerTrace = 10, attributesPerEvent = 5),
                dataset("event-10", traces = 100, eventsPerTrace = 10, attributesPerEvent = 5),
                dataset("attr-5", traces = 100, eventsPerTrace = 10, attributesPerEvent = 5),
                dataset("trace-500", traces = 500, eventsPerTrace = 10, attributesPerEvent = 5),
            ),
        )
        assertEquals(listOf(listOf("attr-5", "event-10", "trace-100")), groups)
    }

    @Test
    fun `real logs with coincident counts are not treated as replicates`() {
        val groups = ReplicateControl.replicateGroups(
            listOf(
                dataset("synthetic-a", traces = 100, eventsPerTrace = 10, attributesPerEvent = 5),
                dataset(
                    "real-log",
                    traces = 100,
                    eventsPerTrace = 10,
                    attributesPerEvent = 5,
                    series = "real-validation",
                ),
            ),
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `replicate spread is measured across identical datasets`() {
        val datasets = listOf(
            dataset("trace-100", traces = 100, eventsPerTrace = 10, attributesPerEvent = 5),
            dataset("event-10", traces = 100, eventsPerTrace = 10, attributesPerEvent = 5),
        )
        val queries = warmSamples("trace-100", "q", "local", 0.008) +
            warmSamples("event-10", "q", "local", 0.004)
        val report = ReplicateControl.measure(datasets, queries)
        assertNotNull(report)
        assertEquals(2.0, report.worstSpread, 1e-9)
        assertEquals(2.0, report.floorFor("q"), 1e-9)
        assertFalse(report.runIsValid, "a two-fold spread on identical data must fail the validity gate")
    }

    @Test
    fun `one noisy query keeps its own floor without declaring broad run instability`() {
        val datasets = listOf(
            dataset("trace-100", traces = 100, eventsPerTrace = 10, attributesPerEvent = 5),
            dataset("event-10", traces = 100, eventsPerTrace = 10, attributesPerEvent = 5),
        )
        val queries = (1..5).flatMap { index ->
            val label = "q$index"
            warmSamples("trace-100", label, "local", 0.010) +
                warmSamples("event-10", label, "local", if (index == 5) 0.020 else 0.010)
        }

        val report = ReplicateControl.measure(datasets, queries)

        assertNotNull(report)
        assertEquals(1.0, report.upperQuartileQuerySpread, 1e-9)
        assertEquals(2.0, report.worstQuerySpread, 1e-9)
        assertEquals(2.0, report.floorFor("q5"), 1e-9)
        assertTrue(report.runIsValid)
    }

    @Test
    fun `one-shot import stability does not invalidate stable query measurements`() {
        val datasets = listOf(
            dataset("trace-100", traces = 100, eventsPerTrace = 10, attributesPerEvent = 5),
            dataset("event-10", traces = 100, eventsPerTrace = 10, attributesPerEvent = 5),
        )
        val stableQueries = warmSamples("trace-100", "q", "local", 0.010) +
            warmSamples("event-10", "q", "local", 0.010)
        val imports = listOf(
            ImportBenchmarkResult("local", "trace-100", 1, 1.0, "OK", "a", 1),
            ImportBenchmarkResult("local", "event-10", 1, 2.0, "OK", "b", 1),
        )

        val report = ReplicateControl.measure(datasets, stableQueries, imports)

        assertNotNull(report)
        assertEquals(2.0, report.worstSpread, 1e-9)
        assertEquals(1.0, report.worstQuerySpread, 1e-9)
        assertEquals(2.0, report.worstImportSpread, 1e-9)
        assertEquals(2.0, report.floorFor(IMPORT_REPLICATE_LABEL), 1e-9)
        assertTrue(report.runIsValid)
        assertFalse(report.importIsStable)
    }

    @Test
    fun `verdict rejects effects below the measurement error floor`() {
        val verdict = ComparisonVerdict(
            datasetName = "trace-500",
            queryLabel = "eventNameFilter",
            ratio = 0.93,
            confidenceInterval = ConfidenceInterval(point = 0.93, low = 0.90, high = 0.96),
            rawPValue = 0.001,
            adjustedPValue = 0.01,
            practicalFloor = 1.8,
        )
        assertTrue(verdict.statisticallySignificant)
        assertFalse(verdict.practicallySignificant)
        assertEquals(SignificanceVerdict.BELOW_MEASUREMENT_ERROR, verdict.verdict)
        assertEquals("LOCAL", verdict.fasterSystem)
    }

    @Test
    fun `verdict accepts a large effect that clears the floor`() {
        val verdict = ComparisonVerdict(
            datasetName = "real-hospital",
            queryLabel = "customAttrFilter",
            ratio = 0.02,
            confidenceInterval = ConfidenceInterval(point = 0.02, low = 0.019, high = 0.022),
            rawPValue = 1e-10,
            adjustedPValue = 1e-8,
            practicalFloor = 2.0,
        )
        assertEquals(SignificanceVerdict.SIGNIFICANT, verdict.verdict)
        assertEquals(50.0, verdict.magnitude, 1e-9)
        assertEquals("LOCAL", verdict.fasterSystem)
    }

    private fun dataset(
        name: String,
        traces: Int,
        eventsPerTrace: Int,
        attributesPerEvent: Int,
        series: String = "test",
    ): PreparedDataset =
        PreparedDataset(
            name = name,
            series = series,
            file = java.nio.file.Path.of("$name.xes"),
            traces = traces,
            eventsPerTrace = eventsPerTrace,
            totalEvents = traces * eventsPerTrace,
            attributesPerEvent = attributesPerEvent,
            totalAttributes = traces * eventsPerTrace * attributesPerEvent,
            xesBytes = 1000,
            xesGzBytes = 100,
        )

    private fun warmSamples(
        dataset: String,
        label: String,
        system: String,
        seconds: Double,
    ): List<QueryBenchmarkResult> =
        (0 until 5).map { run ->
            QueryBenchmarkResult(
                system = system,
                datasetName = dataset,
                queryLabel = label,
                run = run,
                seconds = seconds,
                status = "OK",
                responseBytes = 10,
                phase = QUERY_PHASE_WARM,
            )
        }
}
