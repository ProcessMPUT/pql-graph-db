package com.processm.processminterpreter.benchmark

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Inferential statistics for the thesis comparison (METODOLOGIA §5, *Statystyka*).
 *
 * Replaces the earlier "disjoint IQR" rule, which was not a test: the IQR measures
 * the **spread of the sample**, not the **uncertainty of the median**, so its
 * disjointness controls no error rate and reacts to `n` only through the quantile
 * estimator. On the FULL profile that rule declared 86 % of all (dataset, query)
 * pairs significant, i.e. it carried almost no information.
 *
 * What is reported instead, for every pair:
 *
 * 1. **Paired bootstrap percentile CI for the ratio of medians** — the effect size with
 *    its uncertainty, on the scale the thesis actually argues in ("LOCAL is ×k
 *    faster"). Deterministic: the resampling seed is derived from the pair key,
 *    so re-running the report on the same samples reproduces the same interval.
 * 2. **Wilcoxon signed-rank test** (exact permutation distribution for at most
 *    20 non-zero pairs; tie- and continuity-corrected normal approximation for
 *    larger samples) with **Holm** adjustment in the family defined by the
 *    current paired analysis.
 *
 * The samples are back-to-back executions against one warm process, so they are
 * autocorrelated; the p-values are therefore optimistic and are reported only as
 * a secondary criterion, never as the sole basis for a claim. This is stated in
 * the report itself, not only here.
 */
object InferentialStatistics {
    /** Resamples per bootstrap interval. 10 000 keeps the 2,5 %/97,5 % percentiles stable to ~0,01. */
    const val BOOTSTRAP_RESAMPLES: Int = 10_000

    /** Two-sided confidence level of every reported interval. */
    const val CONFIDENCE_LEVEL: Double = 0.95

    /** Family-wise error rate controlled by the Holm–Bonferroni adjustment. */
    const val ALPHA: Double = 0.05

    /**
     * Percentile bootstrap CI for `median(local) / median(reference)`.
     *
     * Repetition *i* on both systems is one adjacent, deliberately interleaved block.
     * Resampling the pair together preserves the shared temporal drift that an
     * independent bootstrap would incorrectly treat as two unrelated samples.
     */
    fun pairedMedianRatioConfidenceInterval(
        local: List<Double>,
        reference: List<Double>,
        seed: Long,
        resamples: Int = BOOTSTRAP_RESAMPLES,
    ): ConfidenceInterval? {
        if (local.isEmpty() || local.size != reference.size) return null
        val referenceMedian = ThesisStatistics.quantile(reference, 0.50)
        if (referenceMedian <= 0.0) return null

        val random = Random(seed)
        val ratios = DoubleArray(resamples)
        val localBuffer = DoubleArray(local.size)
        val referenceBuffer = DoubleArray(reference.size)
        var usable = 0
        repeat(resamples) {
            for (i in localBuffer.indices) {
                val sampledPair = random.nextInt(local.size)
                localBuffer[i] = local[sampledPair]
                referenceBuffer[i] = reference[sampledPair]
            }
            val denominator = medianOf(referenceBuffer)
            if (denominator > 0.0) ratios[usable++] = medianOf(localBuffer) / denominator
        }
        if (usable == 0) return null
        val sorted = ratios.copyOf(usable).also { it.sort() }.toList()
        val tail = (1.0 - CONFIDENCE_LEVEL) / 2.0
        return ConfidenceInterval(
            point = ThesisStatistics.quantile(local, 0.50) / referenceMedian,
            low = ThesisStatistics.quantile(sorted, tail),
            high = ThesisStatistics.quantile(sorted, 1.0 - tail),
        )
    }

    /**
     * Two-sided Wilcoxon signed-rank test for paired repetitions via the normal
     * exact sign-permutation distribution for up to 20 non-zero differences and a
     * tie-/continuity-corrected normal approximation above that. Zero differences
     * are discarded. Returns 1.0 for missing/degenerate inputs so a broken pairing
     * can never manufacture significance.
     */
    fun wilcoxonSignedRank(
        a: List<Double>,
        b: List<Double>,
    ): Double {
        if (a.isEmpty() || a.size != b.size) return 1.0
        val differences = a.indices.map { a[it] - b[it] }.filter { it != 0.0 }
        if (differences.isEmpty()) return 1.0
        val ordered = differences.withIndex().sortedBy { abs(it.value) }
        val ranks = DoubleArray(differences.size)
        var tieCorrection = 0.0
        var index = 0
        while (index < ordered.size) {
            var end = index
            while (end + 1 < ordered.size && abs(ordered[end + 1].value) == abs(ordered[index].value)) end++
            val midRank = (index + end + 2) / 2.0
            for (i in index..end) ranks[ordered[i].index] = midRank
            val tieSize = (end - index + 1).toDouble()
            if (tieSize > 1) tieCorrection += tieSize * tieSize * tieSize - tieSize
            index = end + 1
        }

        val positiveRank = differences.indices.filter { differences[it] > 0.0 }.sumOf { ranks[it] }
        val n = differences.size.toDouble()
        val mean = n * (n + 1.0) / 4.0
        if (differences.size <= 20) {
            val observed = abs(positiveRank - mean)
            val assignments = 1L shl differences.size
            var atLeastAsExtreme = 0L
            for (mask in 0 until assignments) {
                var rankSum = 0.0
                for (bit in ranks.indices) {
                    if (mask and (1L shl bit) != 0L) rankSum += ranks[bit]
                }
                if (abs(rankSum - mean) + 1e-12 >= observed) atLeastAsExtreme++
            }
            return atLeastAsExtreme.toDouble() / assignments
        }
        val variance = n * (n + 1.0) * (2.0 * n + 1.0) / 24.0 - tieCorrection / 48.0
        if (variance <= 0.0) return 1.0
        val numerator = abs(positiveRank - mean) - 0.5
        if (numerator <= 0.0) return 1.0
        return (2.0 * (1.0 - standardNormalCdf(numerator / sqrt(variance)))).coerceIn(0.0, 1.0)
    }

    /**
     * Holm–Bonferroni step-down adjustment. Controls the family-wise error rate
     * across every comparison in the run while being uniformly more powerful than
     * plain Bonferroni. Returned in the input order.
     */
    fun holmAdjust(pValues: List<Double>): List<Double> {
        if (pValues.isEmpty()) return emptyList()
        val order = pValues.indices.sortedBy { pValues[it] }
        val adjusted = DoubleArray(pValues.size)
        var running = 0.0
        order.forEachIndexed { rank, original ->
            val scaled = (pValues.size - rank) * pValues[original]
            running = max(running, scaled)
            adjusted[original] = min(1.0, running)
        }
        return adjusted.toList()
    }

    /**
     * Ordinary least squares `y = intercept + slope * x` with the coefficient of
     * determination. Used for the disk-growth model (Q3) — where a constant
     * pre-allocation term makes the naive `delta / xesBytes` ratio a hyperbola
     * rather than a property of the storage format — and, on log-transformed
     * axes, for the query scaling exponent (Q2).
     */
    fun fitLinear(
        xs: List<Double>,
        ys: List<Double>,
    ): LinearFit? {
        require(xs.size == ys.size) { "fitLinear needs paired samples" }
        if (xs.size < 3) return null
        val meanX = xs.average()
        val meanY = ys.average()
        val sxx = xs.sumOf { (it - meanX) * (it - meanX) }
        if (sxx <= 0.0) return null
        val sxy = xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) }
        val slope = sxy / sxx
        val intercept = meanY - slope * meanX
        val totalSumOfSquares = ys.sumOf { (it - meanY) * (it - meanY) }
        val residualSumOfSquares = xs.indices.sumOf {
            val predicted = intercept + slope * xs[it]
            (ys[it] - predicted) * (ys[it] - predicted)
        }
        val r2 = if (totalSumOfSquares > 0.0) 1.0 - residualSumOfSquares / totalSumOfSquares else Double.NaN
        return LinearFit(intercept = intercept, slope = slope, r2 = r2, points = xs.size)
    }

    /**
     * Scaling exponent `alpha` of `t ~ n^alpha`, fitted as a straight line in
     * log10–log10 space. Eligibility for interpreting a fit is a separate,
     * predeclared query-series property: hierarchical response limits do not imply
     * that a lower-scope sort, group, or aggregate can avoid reading all children.
     */
    fun fitPowerLaw(
        xs: List<Double>,
        ys: List<Double>,
    ): LinearFit? {
        val pairs = xs.indices
            .filter { xs[it] > 0.0 && ys[it] > 0.0 }
            .map { log10(xs[it]) to log10(ys[it]) }
        if (pairs.size < 3) return null
        return fitLinear(pairs.map { it.first }, pairs.map { it.second })
    }

    private fun log10(value: Double): Double = ln(value) / ln(10.0)

    private fun medianOf(values: DoubleArray): Double {
        val sorted = values.copyOf().also { it.sort() }
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    /** Abramowitz & Stegun 7.1.26 error-function approximation; |error| < 1.5e-7. */
    fun standardNormalCdf(z: Double): Double {
        val sign = if (z < 0) -1.0 else 1.0
        val x = abs(z) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val y = 1.0 - (
            ((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592
            ) * t * exp(-x * x)
        return 0.5 * (1.0 + sign * y)
    }
}

data class ConfidenceInterval(
    val point: Double,
    val low: Double,
    val high: Double,
) {
    /** True when the interval excludes 1.0, i.e. the direction of the difference is resolved. */
    fun excludesUnity(): Boolean = low > 1.0 || high < 1.0
}

data class LinearFit(
    val intercept: Double,
    val slope: Double,
    val r2: Double,
    val points: Int,
)

/**
 * Measurement error demonstrated by the run itself.
 *
 * The synthetic workload contains datasets that are **the same experiment under
 * three names**: `trace-100`, `event-10` and `attr-5` are all 100 traces × 10
 * events × 5 attributes — they are the point where the three scaling series
 * intersect. Their generated files differ only in the log's `concept:name`.
 *
 * Measuring the same thing three times in one run therefore bounds the error of
 * the whole experiment from below, and it does so *without* the confounder that
 * the run-to-run comparison in §5 pkt 6 cannot remove: it varies position in the
 * measurement sequence, which is exactly where the JVM warm-up bias lives.
 *
 * The spread is used two ways:
 * - as the practical-significance floor: a median difference smaller than the
 *   spread observed on identical data is not evidence about the systems;
 * - as a metric-specific validity gate. The upper quartile of query/system
 *   spreads gates broad Q2 instability; the exact per-query maximum still gates
 *   every effect. The one-shot, polling-quantized import spread gates Q1 and must
 *   not invalidate Q2.
 */
object ReplicateControl {
    /**
     * Maximum tolerated upper-quartile max/min spread between replicate query
     * measurements of the same dataset shape before the run is declared invalid.
     * A run at or below this still has measurable query-specific drift, which is
     * why each query's measured maximum spread — not this constant — is what its
     * significance rule uses.
     */
    const val VALIDITY_GATE_SPREAD: Double = 1.25

    /**
     * Groups dataset names that describe an identical synthetic experiment.
     * Real logs are excluded (their identity is the file, not the parameters).
     */
    fun replicateGroups(datasets: List<PreparedDataset>): List<List<String>> =
        datasets
            .filter { it.series != "real-validation" && it.traces > 0 && it.eventsPerTrace > 0 }
            .groupBy { Triple(it.traces, it.eventsPerTrace, it.attributesPerEvent) }
            .values
            .filter { it.size > 1 }
            .map { group -> group.map { it.name }.sorted() }
            .sortedBy { it.first() }

    /**
     * Per (query label, system) max/min spread across replicate datasets, and the
     * worst spread overall. Returns null when the run has no replicate group.
     */
    fun measure(
        datasets: List<PreparedDataset>,
        queries: List<QueryBenchmarkResult>,
        imports: List<ImportBenchmarkResult> = emptyList(),
    ): ReplicateReport? {
        val groups = replicateGroups(datasets)
        if (groups.isEmpty()) return null

        val medians = queries
            .filter { it.phase == QUERY_PHASE_WARM && it.status == "OK" }
            .groupBy { Triple(it.datasetName, it.queryLabel, it.system) }
            .mapValues { (_, samples) -> ThesisStatistics.quantile(samples.map { it.seconds }, 0.50) }

        val entries = mutableListOf<ReplicateSpread>()
        groups.forEach { group ->
            val labels = queries.map { it.queryLabel }.distinct()
            val systems = queries.map { it.system }.distinct()
            labels.forEach { label ->
                systems.forEach { system ->
                    val values = group.mapNotNull { medians[Triple(it, label, system)] }.filter { it > 0.0 }
                    if (values.size > 1) {
                        entries += ReplicateSpread(
                            datasets = group,
                            queryLabel = label,
                            system = system,
                            minSeconds = values.min(),
                            maxSeconds = values.max(),
                        )
                    }
                }
            }
        }
        val importMedians = imports
            .filter { it.status == "OK" && it.seconds > 0.0 }
            .groupBy { it.datasetName to it.system }
            .mapValues { (_, samples) -> ThesisStatistics.quantile(samples.map { it.seconds }, 0.50) }
        groups.forEach { group ->
            imports.map { it.system }.distinct().forEach { system ->
                val values = group.mapNotNull { importMedians[it to system] }.filter { it > 0.0 }
                if (values.size > 1) {
                    entries += ReplicateSpread(
                        datasets = group,
                        queryLabel = IMPORT_REPLICATE_LABEL,
                        system = system,
                        minSeconds = values.min(),
                        maxSeconds = values.max(),
                    )
                }
            }
        }
        if (entries.isEmpty()) return null
        return ReplicateReport(groups = groups, spreads = entries.sortedByDescending { it.spread })
    }
}

const val IMPORT_REPLICATE_LABEL = "IMPORT (Q1)"

data class ReplicateSpread(
    val datasets: List<String>,
    val queryLabel: String,
    val system: String,
    val minSeconds: Double,
    val maxSeconds: Double,
) {
    val spread: Double get() = if (minSeconds > 0.0) maxSeconds / minSeconds else Double.NaN
}

data class ReplicateReport(
    val groups: List<List<String>>,
    val spreads: List<ReplicateSpread>,
) {
    val querySpreads: List<ReplicateSpread> get() = spreads.filter { it.queryLabel != IMPORT_REPLICATE_LABEL }
    val importSpreads: List<ReplicateSpread> get() = spreads.filter { it.queryLabel == IMPORT_REPLICATE_LABEL }

    val worstSpread: Double get() = spreads.maxOfOrNull { it.spread } ?: Double.NaN
    val worstQuerySpread: Double get() = querySpreads.maxOfOrNull { it.spread } ?: Double.NaN
    val worstImportSpread: Double get() = importSpreads.maxOfOrNull { it.spread } ?: Double.NaN

    val medianQuerySpread: Double
        get() = querySpreadQuantile(0.50)

    val upperQuartileQuerySpread: Double
        get() = querySpreadQuantile(0.75)

    private fun querySpreadQuantile(p: Double): Double =
        querySpreads.map { it.spread }.filter { it.isFinite() }
            .takeIf { it.isNotEmpty() }
            ?.let { ThesisStatistics.quantile(it, p) }
            ?: Double.NaN

    /** Worst spread seen for one query label, across systems — the floor that label's effects must clear. */
    fun floorFor(queryLabel: String): Double =
        spreads.filter { it.queryLabel == queryLabel }.maxOfOrNull { it.spread }?.takeIf { it.isFinite() } ?: 1.0

    /** Q2 validity. Q1 has an independent [importIsStable] gate. */
    val runIsValid: Boolean
        get() =
            upperQuartileQuerySpread.isFinite() &&
                upperQuartileQuerySpread <= ReplicateControl.VALIDITY_GATE_SPREAD

    val importIsStable: Boolean
        get() = worstImportSpread.isFinite() && worstImportSpread <= ReplicateControl.VALIDITY_GATE_SPREAD
}

/** Effect size and both significance criteria for one (dataset, query) comparison. */
data class ComparisonVerdict(
    val datasetName: String,
    val queryLabel: String,
    val ratio: Double,
    val confidenceInterval: ConfidenceInterval?,
    val rawPValue: Double,
    val adjustedPValue: Double,
    /** Measurement-error floor this effect had to clear, from [ReplicateControl]. */
    val practicalFloor: Double,
) {
    /** Effect size as a factor ≥ 1, direction-free. */
    val magnitude: Double get() = if (ratio >= 1.0) ratio else 1.0 / ratio

    val fasterSystem: String get() = if (ratio < 1.0) "LOCAL" else "REFERENCE"

    val statisticallySignificant: Boolean
        get() = adjustedPValue < InferentialStatistics.ALPHA &&
            (confidenceInterval?.excludesUnity() ?: false)

    val practicallySignificant: Boolean get() = magnitude >= practicalFloor

    val verdict: SignificanceVerdict
        get() = when {
            !statisticallySignificant -> SignificanceVerdict.NOT_SIGNIFICANT
            !practicallySignificant -> SignificanceVerdict.BELOW_MEASUREMENT_ERROR
            else -> SignificanceVerdict.SIGNIFICANT
        }
}

enum class SignificanceVerdict(val label: String) {
    SIGNIFICANT("istotna"),
    BELOW_MEASUREMENT_ERROR("poniżej błędu pomiaru"),
    NOT_SIGNIFICANT("nieistotna"),
}
