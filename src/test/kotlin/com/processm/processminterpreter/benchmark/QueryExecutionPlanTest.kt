package com.processm.processminterpreter.benchmark

import com.processm.processminterpreter.pql.ast.PqlQuery
import com.processm.processminterpreter.pql.ast.PqlExpression
import com.processm.processminterpreter.pql.parser.AntlrPqlParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryExecutionPlanTest {
    @Test
    fun `every protocol query parses`() {
        val parser = AntlrPqlParser()

        BenchmarkConfig.load(BenchmarkProfile.FULL).queries.forEach { spec ->
            parser.parse(spec.query)
        }
    }

    @Test
    fun `ordering control uses four attributes populated by the synthetic generator`() {
        val spec = BenchmarkConfig.load(BenchmarkProfile.FULL).queries.single {
            it.label == "standardAttributesOrder"
        }
        val parsed = AntlrPqlParser().parse(spec.query) as PqlQuery.Select

        assertEquals(4, parsed.orderBy.size)
        assertTrue(spec.query.contains("e:cost:total"))
        assertTrue(spec.query.contains("[e:attr_1]"))
        val customAttribute = parsed.orderBy.last().expression as PqlExpression.AttributeRef
        assertTrue(customAttribute.wasBracketed)
        assertEquals("attr_1", customAttribute.name)
    }

    @Test
    fun `query scaling eligibility is declared per varied dataset series`() {
        val specs = BenchmarkConfig.load(BenchmarkProfile.FULL).queries.associateBy { it.label }
        val allowed = setOf("size-scaling", "variant-scaling")

        assertTrue(specs.values.flatMap { it.scalingSeries }.all { it in allowed })
        assertEquals(6, specs.values.count { it.role == BenchmarkQueryRole.PRIMARY })
        assertEquals(4, specs.values.count { it.role == BenchmarkQueryRole.CONTROL })
        assertEquals(1, specs.values.count { it.role == BenchmarkQueryRole.BASELINE })
        assertTrue(specs.getValue("standardAttributesOrder").isMeasuredFor("size-scaling"))
        assertTrue(!specs.getValue("standardAttributesOrder").isMeasuredFor("real-validation"))
        assertTrue(!specs.getValue("standardAttributesOrder").isMeasuredFor("variant-scaling"))
        assertTrue(specs.getValue("hierarchyCardinality").isMeasuredFor("size-scaling"))
        assertTrue(specs.getValue("hierarchyCardinality").isMeasuredFor("real-validation"))
        assertTrue(!specs.getValue("hierarchyCardinality").isMeasuredFor("variant-scaling"))
        assertTrue(specs.getValue("globalEventAggregation").isMeasuredFor("real-validation"))
        assertTrue(!specs.getValue("genericVariantGroup").isMeasuredFor("real-validation"))
        assertTrue(!specs.getValue("hoistedPositive").isMeasuredFor("real-validation"))
        assertEquals(11, specs.values.count { it.isMeasuredFor("size-scaling") })
        assertEquals(3, specs.values.count { it.isMeasuredFor("variant-scaling") })
        assertEquals(5, specs.values.count { it.isMeasuredFor("real-validation") })
    }

    @Test
    fun `full profile retains its design under modular protocol 25`() {
        val config = BenchmarkConfig.load(BenchmarkProfile.FULL)
        assertEquals(22, config.datasets.size)
        assertEquals(7, config.datasets.count { it.series == "size-scaling" })
        assertEquals(3, config.datasets.count { it.series == "variant-scaling" })
        assertEquals(12, config.datasets.count { it.series == "real-validation" })
        assertEquals(10, config.datasets.count { it.collection == "bpi-challenge" })
        assertEquals(11, config.queries.size)
        assertEquals(30, BenchmarkProfile.FULL.repetitions)
        assertEquals(10, BenchmarkProfile.FULL.importRepetitions)
        assertEquals(40, BenchmarkProfile.FULL.warmups)
        assertEquals(25, CURRENT_BENCHMARK_PROTOCOL_VERSION)
    }

    @Test
    fun `block profile keeps full query evidence but uses one setup import`() {
        val config = BenchmarkConfig.load(BenchmarkProfile.BLOCK)

        assertEquals(BenchmarkConfig.load(BenchmarkProfile.FULL).datasets, config.datasets)
        assertEquals(11, config.queries.size)
        assertEquals(40, BenchmarkProfile.BLOCK.warmups)
        assertEquals(30, BenchmarkProfile.BLOCK.repetitions)
        assertEquals(1, BenchmarkProfile.BLOCK.importRepetitions)
    }

    @Test
    fun `control profile isolates corrected size series controls`() {
        val config = BenchmarkConfig.load(BenchmarkProfile.CONTROL)

        assertEquals(
            listOf("standardAttributesOrder", "eventEquality", "likeNoMatch", "likeMatching"),
            config.queries.map { it.label },
        )
        assertEquals(40, BenchmarkProfile.CONTROL.warmups)
        assertEquals(30, BenchmarkProfile.CONTROL.repetitions)
        assertEquals(1, BenchmarkProfile.CONTROL.importRepetitions)
    }

    @Test
    fun `query profile contains the complete audited size workload`() {
        val config = BenchmarkConfig.load(BenchmarkProfile.QUERY)

        assertEquals(BenchmarkConfig.load(BenchmarkProfile.FULL).queries, config.queries)
        assertTrue(config.queries.all { it.isMeasuredFor("size-scaling") })
        assertEquals(40, BenchmarkProfile.QUERY.warmups)
        assertEquals(30, BenchmarkProfile.QUERY.repetitions)
        assertEquals(1, BenchmarkProfile.QUERY.importRepetitions)
    }

    @Test
    fun `diagnostic isolates small size stationarity workload`() {
        val config = BenchmarkConfig.load(BenchmarkProfile.DIAGNOSTIC)

        assertEquals(listOf("size-1k", "size-5k", "size-20k"), config.datasets.map { it.name })
        assertEquals(listOf("minimalWindow", "hierarchyWindow"), config.queries.map { it.label })
        assertEquals(40, BenchmarkProfile.DIAGNOSTIC.warmups)
        assertEquals(30, BenchmarkProfile.DIAGNOSTIC.repetitions)
        assertEquals(1, BenchmarkProfile.DIAGNOSTIC.importRepetitions)
    }

    @Test
    fun `pilot uses full datasets with non inferential repetition counts`() {
        assertEquals(
            BenchmarkConfig.load(BenchmarkProfile.FULL).datasets,
            BenchmarkConfig.load(BenchmarkProfile.PILOT).datasets,
        )
        assertEquals(3, BenchmarkProfile.PILOT.repetitions)
        assertEquals(1, BenchmarkProfile.PILOT.importRepetitions)
    }

    @Test
    fun `plan runs interleaved warmups then paired repetitions`() {
        val plan = buildQueryExecutionPlan(systemCount = 2, warmups = 2, repetitions = 3)
        val expected = listOf(
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
    fun `single system plan keeps warmups before sequential repetitions`() {
        val plan = buildQueryExecutionPlan(systemCount = 1, warmups = 1, repetitions = 2)
        assertEquals(
            listOf(
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
        assertTrue(plan.all { it.kind == QueryStepKind.MEASURED })
    }

    @Test
    fun `pair can start with the second system`() {
        val plan = buildQueryExecutionPlan(systemCount = 2, warmups = 1, repetitions = 2, initialSystemIndex = 1)
        assertEquals(
            listOf(1, 0, 1, 0, 0, 1),
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
