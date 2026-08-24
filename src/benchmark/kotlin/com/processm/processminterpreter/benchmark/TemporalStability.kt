package com.processm.processminterpreter.benchmark

/**
 * Predeclared stationarity diagnostic for a chronological positive-valued series.
 *
 * Thirty repetitions are split into equal first/last thirds (10 + 10). The middle
 * third is deliberately ignored so the diagnostic compares separated windows
 * instead of reacting to a single adjacent boundary. Protocol 21 applied a hard
 * gate to the same ratio-of-medians REFERENCE/LOCAL effect reported by the analysis.
 * Protocol 22 retains that calculation as a visible diagnostic: a fixed 10% cutoff
 * is not used to discard otherwise complete paired evidence. Absolute LOCAL and
 * REFERENCE series expose common-mode host drift that the paired design controls.
 */
object TemporalStability {
    const val MAX_EARLY_LATE_RATIO: Double = 1.10
    const val MINIMUM_SAMPLES: Int = 20
    const val DETAILS_PREFIX: String = "temporal instability"
    const val DIAGNOSTIC_DETAILS_PREFIX: String = "paired temporal drift diagnostic"
    const val DESCRIPTIVE_DETAILS_PREFIX: String = "descriptive temporal drift"
    const val COMMON_MODE_DETAILS_PREFIX: String = "absolute temporal drift with stable paired effect"

    data class Assessment(
        val windowSamples: Int,
        val earlyMedian: Double,
        val lateMedian: Double,
        val ratio: Double,
    ) {
        val isStable: Boolean get() = ratio <= MAX_EARLY_LATE_RATIO
    }

    fun assess(valuesInRunOrder: List<Double>): Assessment? {
        if (valuesInRunOrder.size < MINIMUM_SAMPLES || valuesInRunOrder.any { it <= 0.0 }) return null
        val window = valuesInRunOrder.size / 3
        if (window == 0) return null
        val early = ThesisStatistics.quantile(valuesInRunOrder.take(window), 0.50)
        val late = ThesisStatistics.quantile(valuesInRunOrder.takeLast(window), 0.50)
        if (early <= 0.0 || late <= 0.0) return null
        return Assessment(
            windowSamples = window,
            earlyMedian = early,
            lateMedian = late,
            ratio = maxOf(early / late, late / early),
        )
    }

    /**
     * Compare the paired ratio-of-medians estimand in separated chronological windows.
     *
     * Computing a median of individual ratios would estimate a different quantity
     * and becomes brittle when alternating AB/BA execution creates two latency bands.
     */
    fun assessPairedRatio(
        numeratorInRunOrder: List<Double>,
        denominatorInRunOrder: List<Double>,
    ): Assessment? {
        if (numeratorInRunOrder.size != denominatorInRunOrder.size) return null
        if (numeratorInRunOrder.size < MINIMUM_SAMPLES) return null
        if (numeratorInRunOrder.any { it <= 0.0 } || denominatorInRunOrder.any { it <= 0.0 }) return null
        val window = numeratorInRunOrder.size / 3
        if (window == 0) return null
        val earlyDenominator = ThesisStatistics.quantile(denominatorInRunOrder.take(window), 0.50)
        val lateDenominator = ThesisStatistics.quantile(denominatorInRunOrder.takeLast(window), 0.50)
        if (earlyDenominator <= 0.0 || lateDenominator <= 0.0) return null
        val earlyEffect = ThesisStatistics.quantile(numeratorInRunOrder.take(window), 0.50) / earlyDenominator
        val lateEffect = ThesisStatistics.quantile(numeratorInRunOrder.takeLast(window), 0.50) / lateDenominator
        if (earlyEffect <= 0.0 || lateEffect <= 0.0) return null
        return Assessment(
            windowSamples = window,
            earlyMedian = earlyEffect,
            lateMedian = lateEffect,
            ratio = maxOf(earlyEffect / lateEffect, lateEffect / earlyEffect),
        )
    }
}
