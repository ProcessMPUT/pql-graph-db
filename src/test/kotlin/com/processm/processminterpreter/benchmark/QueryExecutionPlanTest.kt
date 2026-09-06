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

        BenchmarkConfig.catalog().queries.forEach { spec ->
            parser.parse(spec.query)
        }
    }

    @Test
    fun `ordering control uses four attributes populated by the synthetic generator`() {
        val spec = BenchmarkConfig.catalog().queries.single {
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
        val specs = BenchmarkConfig.catalog().queries.associateBy { it.label }
        val allowed = setOf("size-scaling", "variant-scaling", "selectivity-scaling", "trace-length-scaling")

        assertTrue(specs.values.flatMap { it.scalingSeries }.all { it in allowed })
        assertEquals(6, specs.values.count { it.role == BenchmarkQueryRole.PRIMARY })
        assertEquals(6, specs.values.count { it.role == BenchmarkQueryRole.CONTROL })
        assertEquals(1, specs.values.count { it.role == BenchmarkQueryRole.BASELINE })
        assertEquals(5, specs.values.count { it.role == BenchmarkQueryRole.DESCRIPTIVE })
        assertTrue(specs.getValue("standardAttributesOrder").isMeasuredFor("size-100k", "size-scaling"))
        assertTrue(!specs.getValue("standardAttributesOrder").isMeasuredFor("real-sepsis", "real-validation"))
        assertTrue(!specs.getValue("standardAttributesOrder").isMeasuredFor("variants-1", "variant-scaling"))
        assertTrue(specs.getValue("hierarchyCardinality").isMeasuredFor("size-100k", "size-scaling"))
        assertTrue(specs.getValue("hierarchyCardinality").isMeasuredFor("real-sepsis", "real-validation"))
        assertTrue(!specs.getValue("hierarchyCardinality").isMeasuredFor("variants-1", "variant-scaling"))
        assertTrue(specs.getValue("globalEventAggregation").isMeasuredFor("real-sepsis", "real-validation"))
        assertTrue(!specs.getValue("genericVariantGroup").isMeasuredFor("real-sepsis", "real-validation"))
        assertTrue(!specs.getValue("hoistedPositive").isMeasuredFor("real-sepsis", "real-validation"))
        assertEquals(11, specs.values.count { it.isMeasuredFor("size-100k", "size-scaling") })
        assertEquals(7, specs.values.count { it.isMeasuredFor("size-20k", "size-scaling") })
        assertEquals(5, specs.values.count { it.isMeasuredFor("variants-1", "variant-scaling") })
        assertEquals(3, specs.values.count { it.isMeasuredFor("variants-100", "variant-scaling") })
        assertEquals(7, specs.values.count { it.isMeasuredFor("real-sepsis", "real-validation") })
    }

    @Test
    fun `plan runs interleaved warmups then paired repetitions`() {
        val plan = buildQueryExecutionPlan(warmups = 2, repetitions = 3)
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
    fun `measured repetitions counterbalance which system starts`() {
        val plan = buildQueryExecutionPlan(warmups = 0, repetitions = 5)
        val measured = plan.filter { it.kind == QueryStepKind.MEASURED }
        assertEquals(listOf(0, 1, 1, 0, 0, 1, 1, 0, 0, 1), measured.map { it.systemIndex })
        assertTrue(plan.all { it.kind == QueryStepKind.MEASURED })
    }

    @Test
    fun `pair can start with the second system`() {
        val plan = buildQueryExecutionPlan(warmups = 1, repetitions = 2, initialSystemIndex = 1)
        assertEquals(
            listOf(1, 0, 1, 0, 0, 1),
            plan.map { it.systemIndex },
        )
    }

    @Test
    fun `count mismatch rejects only its pair while preserving times`() {
        val samples = listOf(sample("local", 1, traces = 99), sample("reference", 1, traces = 7),
            sample("local", 2, traces = 7), sample("reference", 2, traces = 7))
        val bodies = samples.associate { (it.run to it.system) to "[]" }
        val checked = applyEveryResponseParity(samples, bodies)
        assertEquals(listOf("MISMATCH", "MISMATCH", "OK", "OK"), checked.map { it.status })
        assertEquals(samples.map { it.seconds }, checked.map { it.seconds })
    }

    @Test
    fun `missing counterpart evidence and HTTP errors cannot pass parity`() {
        val local = sample("local", 1, traces = 7)
        val reference = sample("reference", 1, traces = 7)
        val bodies = mapOf((1 to "local") to "[]", (1 to "reference") to "[]")
        for (pair in listOf(listOf(local), listOf(local, local), listOf(local, reference.copy(status = "ERROR")))) {
            assertTrue(applyEveryResponseParity(pair, bodies).none { it.status == "OK" })
        }
        assertTrue(applyEveryResponseParity(listOf(local, reference), bodies - (1 to "reference"))
            .all { it.status == "MISMATCH" })
        assertEquals("ERROR", applyEveryResponseParity(listOf(local, reference.copy(status = "ERROR")), bodies).last().status)
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
