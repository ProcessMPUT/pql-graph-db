package com.processm.processminterpreter.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemporalStabilityTest {
    @Test
    fun `gate compares separated first and last thirds`() {
        val assessment = TemporalStability.assess(
            List(10) { 1.09 } + List(10) { 5.0 } + List(10) { 1.0 },
        )!!

        assertEquals(10, assessment.windowSamples)
        assertEquals(1.09, assessment.ratio, 1e-9)
        assertTrue(assessment.isStable)
    }

    @Test
    fun `gate rejects a change above ten percent`() {
        val assessment = TemporalStability.assess(List(10) { 1.11 } + List(10) { 1.0 } + List(10) { 1.0 })!!

        assertFalse(assessment.isStable)
    }

    @Test
    fun `short diagnostic samples do not manufacture a stability verdict`() {
        assertNull(TemporalStability.assess(List(19) { 1.0 }))
    }

    @Test
    fun `paired gate estimates a ratio of medians rather than a median of ratios`() {
        val localBand = listOf(1.0, 0.8).let { band -> List(15) { band }.flatten() }
        val referenceBand = listOf(1.3, 1.6).let { band -> List(15) { band }.flatten() }

        val assessment = TemporalStability.assessPairedRatio(referenceBand, localBand)!!

        assertEquals(1.45 / 0.9, assessment.earlyMedian, 1e-9)
        assertEquals(assessment.earlyMedian, assessment.lateMedian, 1e-9)
        assertEquals(1.0, assessment.ratio, 1e-9)
    }

    @Test
    fun `paired gate rejects unequal sample counts`() {
        assertNull(TemporalStability.assessPairedRatio(List(30) { 2.0 }, List(29) { 1.0 }))
    }
}
