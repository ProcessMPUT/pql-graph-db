package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryExecutionPlanTest {
    @Test
    fun `query scaling eligibility is declared per varied dataset series`() {
        val specs = BenchmarkConfig.load(BenchmarkProfile.FULL).queries.associateBy { it.label }
        val allowed = setOf("trace-scaling", "event-scaling", "attribute-scaling", "shape-scaling")

        assertTrue(specs.values.flatMap { it.scalingSeries }.all { it in allowed })
        assertEquals(
            listOf("event-scaling", "shape-scaling"),
            specs.getValue("timestampAggregates").scalingSeries,
            "a trace-windowed aggregate still reads every event of each retained trace",
        )
        assertTrue("attribute-scaling" !in specs.getValue("hoistedGroup").scalingSeries)
        assertTrue("attribute-scaling" in specs.getValue("absentAttrScan").scalingSeries)
        assertTrue(specs.getValue("hierarchyWindow").scalingSeries.isEmpty())
    }

    @Test
    fun `reversed odd dataset list flips which system imports each dataset first`() {
        val count = 25
        repeat(count) { declaredIndex ->
            val reversedIndex = count - 1 - declaredIndex
            val declaredStart = counterbalancedImportRound(declaredIndex, count, DatasetOrder.DECLARED) % 2
            val reversedStart = counterbalancedImportRound(reversedIndex, count, DatasetOrder.REVERSED) % 2
            assertEquals(1 - declaredStart, reversedStart)
        }
    }

    @Test
    fun `reversal alone flips import order for an even dataset list`() {
        val count = 24
        repeat(count) { declaredIndex ->
            val reversedIndex = count - 1 - declaredIndex
            val declaredStart = counterbalancedImportRound(declaredIndex, count, DatasetOrder.DECLARED) % 2
            val reversedStart = counterbalancedImportRound(reversedIndex, count, DatasetOrder.REVERSED) % 2
            assertEquals(1 - declaredStart, reversedStart)
        }
    }

    @Test
    fun `plan runs cold first then interleaved warmups then interleaved repetitions`() {
        val plan = buildQueryExecutionPlan(systemCount = 2, warmups = 2, repetitions = 3)
        val expected = listOf(
            QueryExecutionStep(0, QueryStepKind.COLD, run = 0),
            QueryExecutionStep(1, QueryStepKind.COLD, run = 0),
            QueryExecutionStep(0, QueryStepKind.WARMUP, run = 0),
            QueryExecutionStep(1, QueryStepKind.WARMUP, run = 0),
            QueryExecutionStep(1, QueryStepKind.WARMUP, run = 0),
            QueryExecutionStep(0, QueryStepKind.WARMUP, run = 0),
            QueryExecutionStep(0, QueryStepKind.MEASURED, run = 1),
            QueryExecutionStep(1, QueryStepKind.MEASURED, run = 1),
            QueryExecutionStep(1, QueryStepKind.MEASURED, run = 2),
            QueryExecutionStep(0, QueryStepKind.MEASURED, run = 2),
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
    fun `measured repetitions counterbalance which system starts`() {
        val plan = buildQueryExecutionPlan(systemCount = 2, warmups = 0, repetitions = 5)
        val measured = plan.filter { it.kind == QueryStepKind.MEASURED }
        assertEquals(listOf(0, 1, 1, 0, 0, 1, 1, 0, 0, 1), measured.map { it.systemIndex })
        assertTrue(plan.takeWhile { it.kind == QueryStepKind.COLD }.size == 2, "cold steps must come first")
    }

    @Test
    fun `pair can start with the second system`() {
        val plan = buildQueryExecutionPlan(systemCount = 2, warmups = 1, repetitions = 2, initialSystemIndex = 1)
        assertEquals(
            listOf(1, 0, 1, 0, 1, 0, 0, 1),
            plan.map { it.systemIndex },
        )
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
        val checked = applyResponseParity(samples)
        val (failed, ok) = checked.partition { it.status == QUERY_STATUS_MISMATCH }
        assertEquals(5, failed.size)
        assertEquals(listOf("ERROR"), ok.map { it.status })
        assertTrue(failed.all { it.details.contains("Response count mismatch") })
        // timings survive invalidation
        assertEquals(samples.filter { it.status == "OK" }.map { it.seconds }, failed.map { it.seconds })
    }

    @Test
    fun `parity check keeps samples when every warm repetition count matches`() {
        val samples = listOf(
            sample("local", run = 1, traces = 7),
            sample("local", run = 2, traces = 7),
            sample("reference", run = 1, traces = 7),
            sample("reference", run = 2, traces = 7),
        )
        assertEquals(samples, applyResponseParity(samples))
    }

    @Test
    fun `parity check compares counts in every warm repetition`() {
        val samples = listOf(
            sample("local", run = 1, traces = 99),
            sample("local", run = 2, traces = 7),
            sample("reference", run = 1, traces = 7),
            sample("reference", run = 2, traces = 7),
        )
        val checked = applyResponseParity(samples)

        assertTrue(checked.all { it.status == QUERY_STATUS_MISMATCH })
        assertTrue(checked.all { it.details.startsWith("Response count mismatch: warm run 1;") })
    }

    @Test
    fun `parity check is skipped for a single system`() {
        val samples = listOf(sample("local", run = 1, traces = 3))
        assertEquals(samples, applyResponseParity(samples))
    }

    @Test
    fun `semantic mismatch invalidates equal-count responses`() {
        val samples = listOf(
            sample("local", run = 1, traces = 0),
            sample("reference", run = 1, traces = 0),
        )
        val checked = applyResponseParity(
            samples,
            mapOf(
                "local" to "[]",
                "reference" to """[{"log":{}}]""",
            ),
        )

        assertTrue(checked.all { it.status == QUERY_STATUS_MISMATCH })
        assertTrue(checked.all { it.details.startsWith("Semantic response mismatch:") })
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
