package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryExecutionPlanTest {
    @Test
    fun `plan runs cold first then interleaved warmups then interleaved repetitions`() {
        val plan = buildQueryExecutionPlan(systemCount = 2, warmups = 2, repetitions = 3)
        val expected = listOf(
            QueryExecutionStep(0, QueryStepKind.COLD, run = 0),
            QueryExecutionStep(1, QueryStepKind.COLD, run = 0),
            QueryExecutionStep(0, QueryStepKind.WARMUP, run = 0),
            QueryExecutionStep(1, QueryStepKind.WARMUP, run = 0),
            QueryExecutionStep(0, QueryStepKind.WARMUP, run = 0),
            QueryExecutionStep(1, QueryStepKind.WARMUP, run = 0),
            QueryExecutionStep(0, QueryStepKind.MEASURED, run = 1),
            QueryExecutionStep(1, QueryStepKind.MEASURED, run = 1),
            QueryExecutionStep(0, QueryStepKind.MEASURED, run = 2),
            QueryExecutionStep(1, QueryStepKind.MEASURED, run = 2),
            QueryExecutionStep(0, QueryStepKind.MEASURED, run = 3),
            QueryExecutionStep(1, QueryStepKind.MEASURED, run = 3),
        )
        assertEquals(expected, plan)
    }

    @Test
    fun `single system plan keeps cold before warmups and sequential repetitions`() {
        val plan = buildQueryExecutionPlan(systemCount = 1, warmups = 1, repetitions = 2)
        assertEquals(
            listOf(
                QueryExecutionStep(0, QueryStepKind.COLD, run = 0),
                QueryExecutionStep(0, QueryStepKind.WARMUP, run = 0),
                QueryExecutionStep(0, QueryStepKind.MEASURED, run = 1),
                QueryExecutionStep(0, QueryStepKind.MEASURED, run = 2),
            ),
            plan,
        )
    }

    @Test
    fun `measured steps alternate between systems`() {
        val plan = buildQueryExecutionPlan(systemCount = 2, warmups = 0, repetitions = 5)
        val measured = plan.filter { it.kind == QueryStepKind.MEASURED }
        assertEquals(listOf(0, 1, 0, 1, 0, 1, 0, 1, 0, 1), measured.map { it.systemIndex })
        assertTrue(plan.takeWhile { it.kind == QueryStepKind.COLD }.size == 2, "cold steps must come first")
    }

    @Test
    fun `parity check downgrades all successful samples on count mismatch`() {
        val samples = listOf(
            sample("local", run = 0, phase = QUERY_PHASE_COLD, traces = 10),
            sample("local", run = 1, traces = 10),
            sample("local", run = 2, traces = 10),
            sample("reference", run = 1, traces = 9),
            sample("reference", run = 2, traces = 9),
            sample("reference", run = 3, seconds = 0.0, status = "ERROR", traces = 0),
        )
        val checked = applyResponseCountParity(samples)
        val (failed, ok) = checked.partition { it.status == QUERY_STATUS_MISMATCH }
        assertEquals(5, failed.size)
        assertEquals(listOf("ERROR"), ok.map { it.status })
        assertTrue(failed.all { it.details.contains("Response count mismatch") })
        // timings survive invalidation
        assertEquals(samples.filter { it.status == "OK" }.map { it.seconds }, failed.map { it.seconds })
    }

    @Test
    fun `parity check keeps samples when last warm counts match`() {
        val samples = listOf(
            sample("local", run = 1, traces = 7),
            sample("local", run = 2, traces = 7),
            sample("reference", run = 1, traces = 7),
            sample("reference", run = 2, traces = 7),
        )
        assertEquals(samples, applyResponseCountParity(samples))
    }

    @Test
    fun `parity check compares only the LAST warm sample per system`() {
        val samples = listOf(
            sample("local", run = 1, traces = 99),
            sample("local", run = 2, traces = 7),
            sample("reference", run = 1, traces = 7),
            sample("reference", run = 2, traces = 7),
        )
        assertEquals(samples, applyResponseCountParity(samples))
    }

    @Test
    fun `parity check is skipped for a single system`() {
        val samples = listOf(sample("local", run = 1, traces = 3))
        assertEquals(samples, applyResponseCountParity(samples))
    }

    private fun sample(
        system: String,
        run: Int,
        phase: String = QUERY_PHASE_WARM,
        status: String = "OK",
        seconds: Double = 0.01 * (run + 1),
        traces: Int,
    ): QueryBenchmarkResult =
        QueryBenchmarkResult(
            system = system,
            datasetName = "trace-100",
            queryLabel = "hierarchyWindow",
            run = run,
            seconds = seconds,
            status = status,
            responseBytes = 100,
            phase = phase,
            logCount = 1,
            traceCount = traces,
            eventCount = traces * 10,
        )
}
