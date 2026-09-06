package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudyJobTest {
    private fun job() = StudyJob("primary-01", "latency", "primary", "a".repeat(64), "frozen",
        listOf("size-100k", "size-1m"), listOf("hierarchyWindow", "hoistedPositive", "hierarchyCardinality", "minimalWindow"),
        1, 40, 30, 1, 200, 10, 3,
        StudyJob.resourceSha256("benchmark-queries.json"), StudyJob.resourceSha256("benchmark-datasets.json"))

    @Test
    fun `a frozen latency task cannot accidentally replay resources or repeated imports`() {
        val job = job()
        job.validate(BenchmarkConfig.catalog())
        assertTrue(job.collectsLatency)
        assertFalse(job.collectsResources)
        assertFalse(job.checksRoundtrip)
        assertEquals(1, job.importPairs)
        assertEquals(30, job.pairs)
        assertFalse(jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(job).has("resourceProbe"))
    }

    @Test
    fun `both final latency phases require thirty pairs with fifteen starts per system`() {
        val config = BenchmarkConfig.catalog()
        for (phase in listOf("primary", "coverage")) {
            job().copy(phase = phase).validate(config)
            for (pairs in listOf(20, 28, 32)) {
                val error = assertFailsWith<IllegalArgumentException> {
                    job().copy(phase = phase, pairs = pairs).validate(config)
                }
                assertTrue(error.message.orEmpty().contains("exactly 30 pairs"))
            }
        }
        for (first in 0..1) {
            val plan = buildQueryExecutionPlan(40, job().pairs, first).filter { it.kind == QueryStepKind.MEASURED }
            assertEquals(60, plan.size)
            assertEquals(mapOf(0 to 15, 1 to 15), plan.groupBy { it.run }.values
                .groupingBy { it.first().systemIndex }.eachCount())
        }
    }

    @Test
    fun `pilot wire contract keeps two pairs without changing the final counts`(@TempDir directory: Path) {
        val probe = StudyResourceProbe("size-1k", "hierarchyWindow")
        val pilot = job().copy(id = "pilot", kind = "pilot", phase = "pilot", planStatus = "draft", pairs = 2,
            datasetNames = listOf("size-1k", "size-1m"), resourceProbe = probe)
        val file = directory.resolve("job.json")
        Files.writeString(file, jacksonObjectMapper().writeValueAsString(pilot))
        val parsed = StudyJob.load(file)
        parsed.validate(BenchmarkConfig.catalog())
        assertEquals(2, parsed.pairs)
        assertEquals(probe, parsed.resourceProbe)
        assertTrue(parsed.collectsResources && parsed.checksRoundtrip)
        assertTrue(parsed.collectsResourcesFor("size-1k", "hierarchyWindow"))
        assertFalse(parsed.collectsResourcesFor("size-1m", "hierarchyWindow"))
        assertFalse(parsed.collectsResourcesFor("size-1k", "minimalWindow"))
        assertFailsWith<IllegalArgumentException> {
            parsed.copy(kind = "latency").validate(BenchmarkConfig.catalog())
        }
    }

    @Test
    fun `pilot resource probe must select a planned measured cell and cannot leak to final jobs`() {
        val config = BenchmarkConfig.catalog()
        val pilot = job().copy(id = "pilot", kind = "pilot", phase = "pilot", planStatus = "draft", pairs = 2,
            resourceProbe = StudyResourceProbe("size-100k", "hierarchyWindow"))
        pilot.validate(config)
        for (invalid in listOf(
            pilot.copy(resourceProbe = null),
            pilot.copy(resourceProbe = StudyResourceProbe("size-1k", "hierarchyWindow")),
            pilot.copy(resourceProbe = StudyResourceProbe("size-100k", "missing")),
            pilot.copy(datasetNames = listOf("variants-1"), resourceProbe = StudyResourceProbe("variants-1", "hoistedPositive")),
            job().copy(resourceProbe = pilot.resourceProbe),
        )) assertFailsWith<IllegalArgumentException> { invalid.validate(config) }
        val resources = job().copy(kind = "resources", phase = "resources")
        resources.validate(config)
        assertTrue(resources.collectsResourcesFor("size-100k", "hierarchyWindow"))
        assertTrue(resources.collectsResourcesFor("size-1m", "hierarchyCardinality"))
        assertFalse(job().collectsResourcesFor("size-100k", "hierarchyWindow"))
    }

    @Test
    fun `missing input definitions and unbalanced pairing are rejected before services`() {
        val config = BenchmarkConfig.catalog()
        for (invalid in listOf(job().copy(datasetNames = listOf("missing")), job().copy(queryLabels = listOf("missing")),
            job().copy(pairs = 3), job().copy(queryDefinitionsSha256 = "0".repeat(64)))) {
            assertFailsWith<IllegalArgumentException> { invalid.validate(config) }
        }
        val resources = job().copy(kind = "resources", phase = "resources", warmups = 5, pairs = 2)
        resources.validate(config)
        assertFalse(resources.collectsLatency)
        assertTrue(resources.collectsResources)
    }

}
