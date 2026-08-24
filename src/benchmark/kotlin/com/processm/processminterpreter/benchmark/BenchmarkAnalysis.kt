package com.processm.processminterpreter.benchmark

/** Derives every current-protocol comparison from paired raw samples. */
object BenchmarkAnalysis {
    private const val STATUS_OK = "OK"
    private const val STATUS_INVALID = "INVALID"

    enum class TemporalGateMode {
        LEGACY_ABSOLUTE_SYSTEM,
        PAIRED_SAMPLE_RATIOS,
        PAIRED_RATIO_OF_MEDIANS,
        DIAGNOSTIC_ONLY_RATIO_OF_MEDIANS,
    }

    fun queryComparisons(
        datasets: List<PreparedDataset>,
        querySpecs: List<BenchmarkQuerySpec>,
        samples: List<QueryBenchmarkResult>,
        expectedPairs: Int? = null,
        temporalGateMode: TemporalGateMode = TemporalGateMode.DIAGNOSTIC_ONLY_RATIO_OF_MEDIANS,
    ): List<BenchmarkComparisonResult> {
        val unadjusted = datasets.flatMap { dataset ->
            querySpecs.filter { it.isMeasuredFor(dataset.series) }.map { spec ->
                pairedComparison(
                    metric = "query",
                    datasetName = dataset.name,
                    series = dataset.series,
                    operationLabel = spec.label,
                    displayName = spec.displayName,
                    role = spec.role.name.lowercase(),
                    inferential = spec.isInferential,
                    expectedPairs = expectedPairs,
                    temporalGateMode = temporalGateMode,
                    rows = samples.filter {
                        it.datasetName == dataset.name &&
                            it.queryLabel == spec.label &&
                            it.phase == QUERY_PHASE_WARM
                    }.map { TimedRow(it.system, it.run, it.seconds, it.status, it.details) },
                )
            }
        }
        return adjustFamilies(unadjusted) { row -> row.datasetName }
    }

    fun importComparisons(
        datasets: List<PreparedDataset>,
        samples: List<ImportBenchmarkResult>,
        expectedPairs: Int? = null,
        temporalGateMode: TemporalGateMode = TemporalGateMode.DIAGNOSTIC_ONLY_RATIO_OF_MEDIANS,
    ): List<BenchmarkComparisonResult> {
        val unadjusted = datasets.map { dataset ->
            pairedComparison(
                metric = "import",
                datasetName = dataset.name,
                series = dataset.series,
                operationLabel = "import",
                displayName = "Import XES",
                role = if (dataset.series == "size-scaling") "primary" else "validation",
                inferential = dataset.series == "size-scaling",
                expectedPairs = expectedPairs,
                temporalGateMode = temporalGateMode,
                rows = samples.filter { it.datasetName == dataset.name }
                    .map { TimedRow(it.system, it.run, it.seconds, it.status, it.details) },
            )
        }
        // The full size-scaling ladder forms the single predeclared import family.
        return adjustFamilies(unadjusted) { row -> if (row.series == "size-scaling") "size-scaling" else row.datasetName }
    }

    private fun pairedComparison(
        metric: String,
        datasetName: String,
        series: String,
        operationLabel: String,
        displayName: String,
        role: String,
        inferential: Boolean,
        expectedPairs: Int?,
        temporalGateMode: TemporalGateMode,
        rows: List<TimedRow>,
    ): BenchmarkComparisonResult {
        val failures = rows.filter { it.status != STATUS_OK }
        val local = rows.filter { it.system == "local" && it.status == STATUS_OK }.associateBy { it.run }
        val reference = rows.filter { it.system == "reference" && it.status == STATUS_OK }.associateBy { it.run }
        val commonRuns = local.keys.intersect(reference.keys).sorted()
        val duplicateRows = rows.groupBy { it.system to it.run }.filterValues { it.size > 1 }
        val valid = failures.isEmpty() && duplicateRows.isEmpty() &&
            local.isNotEmpty() && local.keys == reference.keys && commonRuns.size == local.size &&
            (expectedPairs == null || commonRuns.size == expectedPairs)
        if (!valid) {
            val reason = buildList {
                if (failures.isNotEmpty()) add("non-OK samples: ${failures.groupingBy { it.status }.eachCount()}")
                if (duplicateRows.isNotEmpty()) add("duplicate system/run rows: ${duplicateRows.keys}")
                if (local.keys != reference.keys) add("unpaired runs: local=${local.keys.sorted()}, reference=${reference.keys.sorted()}")
                if (local.isEmpty() || reference.isEmpty()) add("both systems are required")
                if (expectedPairs != null && commonRuns.size != expectedPairs) {
                    add("expected $expectedPairs pairs, found ${commonRuns.size}")
                }
            }.joinToString("; ")
            return emptyComparison(metric, datasetName, series, operationLabel, displayName, role, reason)
        }

        val localSeconds = commonRuns.map { local.getValue(it).seconds }
        val referenceSeconds = commonRuns.map { reference.getValue(it).seconds }
        if (localSeconds.any { it <= 0.0 } || referenceSeconds.any { it <= 0.0 }) {
            return emptyComparison(
                metric, datasetName, series, operationLabel, displayName, role,
                "non-positive duration in a paired sample",
            )
        }
        val localStability = TemporalStability.assess(localSeconds)
        val referenceStability = TemporalStability.assess(referenceSeconds)
        val pairedStability = when (temporalGateMode) {
            TemporalGateMode.PAIRED_SAMPLE_RATIOS -> TemporalStability.assess(
                referenceSeconds.indices.map { index -> referenceSeconds[index] / localSeconds[index] },
            )
            TemporalGateMode.LEGACY_ABSOLUTE_SYSTEM,
            TemporalGateMode.PAIRED_RATIO_OF_MEDIANS,
            TemporalGateMode.DIAGNOSTIC_ONLY_RATIO_OF_MEDIANS,
            -> TemporalStability.assessPairedRatio(
                numeratorInRunOrder = referenceSeconds,
                denominatorInRunOrder = localSeconds,
            )
        }
        val absoluteDrift = listOfNotNull(
            localStability?.takeUnless { it.isStable }?.let { "LOCAL ×${formatRatio(it.ratio)}" },
            referenceStability?.takeUnless { it.isStable }?.let { "REFERENCE ×${formatRatio(it.ratio)}" },
        )
        val windowSamples = pairedStability?.windowSamples
            ?: localStability?.windowSamples
            ?: referenceStability?.windowSamples
        val absoluteDriftDescription = absoluteDrift.takeIf { it.isNotEmpty() }?.let {
            "${it.joinToString(", ")} > ×${formatRatio(TemporalStability.MAX_EARLY_LATE_RATIO)} " +
                "(medians of first and last $windowSamples runs)"
        }.orEmpty()
        val pairedDriftDescription = pairedStability?.takeUnless { it.isStable }?.let {
            val method = when (temporalGateMode) {
                TemporalGateMode.PAIRED_SAMPLE_RATIOS -> "median paired effects"
                TemporalGateMode.LEGACY_ABSOLUTE_SYSTEM,
                TemporalGateMode.PAIRED_RATIO_OF_MEDIANS,
                TemporalGateMode.DIAGNOSTIC_ONLY_RATIO_OF_MEDIANS,
                -> "ratio of medians"
            }
            "paired R/L effect ×${formatRatio(it.ratio)} > ×${formatRatio(TemporalStability.MAX_EARLY_LATE_RATIO)} " +
                "($method in first and last ${it.windowSamples} runs: " +
                "${formatRatio(it.earlyMedian)} → ${formatRatio(it.lateMedian)})"
        }.orEmpty()
        val hardGateDriftDescription = when (temporalGateMode) {
            TemporalGateMode.LEGACY_ABSOLUTE_SYSTEM -> absoluteDriftDescription
            TemporalGateMode.PAIRED_SAMPLE_RATIOS,
            TemporalGateMode.PAIRED_RATIO_OF_MEDIANS,
            -> pairedDriftDescription
            TemporalGateMode.DIAGNOSTIC_ONLY_RATIO_OF_MEDIANS -> ""
        }
        if (hardGateDriftDescription.isNotEmpty() && inferential) {
            return BenchmarkComparisonResult(
                metric = metric,
                datasetName = datasetName,
                series = series,
                operationLabel = operationLabel,
                displayName = displayName,
                role = role,
                pairs = commonRuns.size,
                localMedianSeconds = ThesisStatistics.quantile(localSeconds, 0.50),
                referenceMedianSeconds = ThesisStatistics.quantile(referenceSeconds, 0.50),
                ratioReferenceToLocal = null,
                confidenceLow = null,
                confidenceHigh = null,
                rawPValue = null,
                holmPValue = null,
                verdict = "INVALID",
                status = STATUS_INVALID,
                stabilityWindowSamples = windowSamples,
                localEarlyMedianSeconds = localStability?.earlyMedian,
                localLateMedianSeconds = localStability?.lateMedian,
                localEarlyLateRatio = localStability?.ratio,
                referenceEarlyMedianSeconds = referenceStability?.earlyMedian,
                referenceLateMedianSeconds = referenceStability?.lateMedian,
                referenceEarlyLateRatio = referenceStability?.ratio,
                pairedEarlyMedianRatio = pairedStability?.earlyMedian,
                pairedLateMedianRatio = pairedStability?.lateMedian,
                pairedEarlyLateRatio = pairedStability?.ratio,
                details = "${TemporalStability.DETAILS_PREFIX}: $hardGateDriftDescription" +
                    if (temporalGateMode != TemporalGateMode.LEGACY_ABSOLUTE_SYSTEM) {
                        absoluteDriftDescription.takeIf { it.isNotEmpty() }
                            ?.let { "; absolute diagnostics: $it" }
                            .orEmpty()
                    } else {
                        pairedDriftDescription.takeIf { it.isNotEmpty() }
                            ?.let { "; paired diagnostics: $it" }
                            .orEmpty()
                    },
            )
        }
        val ci = InferentialStatistics.pairedMedianRatioConfidenceInterval(
            local = referenceSeconds,
            reference = localSeconds,
            seed = stableSeed(metric, datasetName, operationLabel),
        )
        val rawP = if (inferential) {
            InferentialStatistics.wilcoxonSignedRank(referenceSeconds, localSeconds)
        } else {
            null
        }
        return BenchmarkComparisonResult(
            metric = metric,
            datasetName = datasetName,
            series = series,
            operationLabel = operationLabel,
            displayName = displayName,
            role = role,
            pairs = commonRuns.size,
            localMedianSeconds = ThesisStatistics.quantile(localSeconds, 0.50),
            referenceMedianSeconds = ThesisStatistics.quantile(referenceSeconds, 0.50),
            ratioReferenceToLocal = ci?.point,
            confidenceLow = ci?.low,
            confidenceHigh = ci?.high,
            rawPValue = rawP,
            holmPValue = null,
            verdict = if (inferential) "PENDING_HOLM" else "DESCRIPTIVE",
            status = STATUS_OK,
            stabilityWindowSamples = windowSamples,
            localEarlyMedianSeconds = localStability?.earlyMedian,
            localLateMedianSeconds = localStability?.lateMedian,
            localEarlyLateRatio = localStability?.ratio,
            referenceEarlyMedianSeconds = referenceStability?.earlyMedian,
            referenceLateMedianSeconds = referenceStability?.lateMedian,
            referenceEarlyLateRatio = referenceStability?.ratio,
            pairedEarlyMedianRatio = pairedStability?.earlyMedian,
            pairedLateMedianRatio = pairedStability?.lateMedian,
            pairedEarlyLateRatio = pairedStability?.ratio,
            details = when {
                !inferential && (pairedDriftDescription.isNotEmpty() || absoluteDriftDescription.isNotEmpty()) ->
                    "${TemporalStability.DESCRIPTIVE_DETAILS_PREFIX}: " +
                        listOf(pairedDriftDescription, absoluteDriftDescription)
                            .filter { it.isNotEmpty() }
                            .joinToString("; ")
                inferential && pairedDriftDescription.isNotEmpty() ->
                    "${TemporalStability.DIAGNOSTIC_DETAILS_PREFIX}: $pairedDriftDescription" +
                        absoluteDriftDescription.takeIf { it.isNotEmpty() }
                            ?.let { "; absolute diagnostics: $it" }
                            .orEmpty()
                inferential && absoluteDriftDescription.isNotEmpty() ->
                    "${TemporalStability.COMMON_MODE_DETAILS_PREFIX}: $absoluteDriftDescription"
                else -> ""
            },
        )
    }

    private fun adjustFamilies(
        comparisons: List<BenchmarkComparisonResult>,
        family: (BenchmarkComparisonResult) -> String,
    ): List<BenchmarkComparisonResult> {
        val adjustedByIndex = mutableMapOf<Int, Double>()
        comparisons.indices.groupBy { family(comparisons[it]) }.values.forEach { indices ->
            val planned = indices.filter { index ->
                comparisons[index].role in setOf("primary", "control")
            }
            // A failed hypothesis remains part of the preregistered family as p=1.
            // Dropping it would make the correction less conservative precisely
            // after observing which cells failed.
            val adjusted = InferentialStatistics.holmAdjust(planned.map { comparisons[it].rawPValue ?: 1.0 })
            planned.zip(adjusted).forEach { (index, value) ->
                if (comparisons[index].rawPValue != null) adjustedByIndex[index] = value
            }
        }
        return comparisons.mapIndexed { index, row ->
            val adjusted = adjustedByIndex[index]
            if (adjusted == null) row else row.copy(
                holmPValue = adjusted,
                verdict = verdict(row.ratioReferenceToLocal, row.confidenceLow, row.confidenceHigh, adjusted),
            )
        }
    }

    private fun verdict(
        ratio: Double?,
        low: Double?,
        high: Double?,
        adjustedP: Double,
    ): String = when {
        ratio == null || low == null || high == null -> "INVALID"
        adjustedP >= InferentialStatistics.ALPHA || (low <= 1.0 && high >= 1.0) -> "NO_DIFFERENCE"
        ratio > 1.0 -> "LOCAL_FASTER"
        ratio < 1.0 -> "REFERENCE_FASTER"
        else -> "NO_DIFFERENCE"
    }

    private fun emptyComparison(
        metric: String,
        datasetName: String,
        series: String,
        operationLabel: String,
        displayName: String,
        role: String,
        details: String,
    ) = BenchmarkComparisonResult(
        metric = metric,
        datasetName = datasetName,
        series = series,
        operationLabel = operationLabel,
        displayName = displayName,
        role = role,
        pairs = 0,
        localMedianSeconds = null,
        referenceMedianSeconds = null,
        ratioReferenceToLocal = null,
        confidenceLow = null,
        confidenceHigh = null,
        rawPValue = null,
        holmPValue = null,
        verdict = "INVALID",
        status = STATUS_INVALID,
        details = details,
    )

    private fun stableSeed(vararg parts: String): Long =
        parts.joinToString("\u001f").fold(0xcbf29ce484222325UL) { hash, char ->
            (hash xor char.code.toULong()) * 0x100000001b3UL
        }.toLong()

    private fun formatRatio(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

    private data class TimedRow(
        val system: String,
        val run: Int,
        val seconds: Double,
        val status: String,
        val details: String,
    )
}
