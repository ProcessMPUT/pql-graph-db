package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

class BenchmarkCampaignAssemblerTest {
    @Test
    fun `campaign recomputes complete block comparisons and keeps resource context`(@TempDir tempDir: Path) {
        val first = tempDir.resolve("block-2011")
        val second = tempDir.resolve("block-2012")
        writeBlock(first, "real-hospital", 2011, localSeconds = 0.010, referenceSeconds = 0.020)
        writeBlock(second, "real-bpic12", 2012, localSeconds = 0.012, referenceSeconds = 0.024)

        val output = tempDir.resolve("campaign")
        BenchmarkCampaignAssembler.assemble(output, listOf(first, second))

        assertEquals(2, CsvReader.read(output.resolve("datasets.csv")).size)
        assertTrue(output.resolve("environment.json").readText().contains("\"profile\" : \"campaign\""))
        val comparisons = CsvReader.read(output.resolve("comparison-results.csv"))
            .filter { it["metric"] == "query" }
        assertEquals(14, comparisons.size)
        assertTrue(comparisons.all { it["pairs"] == "30" })
        assertTrue(comparisons.filter { it["role"] in setOf("primary", "control") }
            .all { it["holmPValue"].orEmpty().isNotBlank() })
        val memory = CsvReader.read(output.resolve("memory-summary.csv"))
        assertEquals(setOf("real-hospital", "real-bpic12"), memory.map { it["datasetName"] }.toSet())
        val report = output.resolve("benchmark-report.md").readText()
        assertTrue(report.contains("## Walidacja przekrojowa BPI Challenge"))
        assertTrue(report.contains("fig-05-query-effect-bpi.svg"))
        assertTrue(output.resolve("figures/fig-06-query-effect-bpi-heatmap.svg").toFile().isFile)
    }

    @Test
    fun `campaign rejects blocks collected from different source revisions`(@TempDir tempDir: Path) {
        val first = tempDir.resolve("block-a")
        val second = tempDir.resolve("block-b")
        writeBlock(first, "real-hospital", 2011, 0.010, 0.020, gitCommit = "a".repeat(40))
        writeBlock(second, "real-bpic12", 2012, 0.012, 0.024, gitCommit = "b".repeat(40))

        val error = assertThrows(IllegalArgumentException::class.java) {
            BenchmarkCampaignAssembler.assemble(tempDir.resolve("campaign"), listOf(first, second))
        }

        assertTrue(error.message.orEmpty().contains("different protocol, workload, source"))
    }

    private fun writeBlock(
        directory: Path,
        datasetName: String,
        year: Int,
        localSeconds: Double,
        referenceSeconds: Double,
        gitCommit: String = "a".repeat(40),
    ) {
        val settings = BenchmarkSettings(
            profile = BenchmarkProfile.BLOCK,
            localApi = "local",
            referenceApi = "reference",
            processMLogin = "",
            processMPassword = "",
            outputRoot = directory,
            datasetFilter = setOf(datasetName),
            systemFilter = emptySet(),
            keepBenchmarkDataStores = false,
            localAppContainer = "processm-interpreter",
        )
        val dataset = PreparedDataset(
            name = datasetName,
            series = "real-validation",
            file = Path.of("$datasetName.xes.gz"),
            traces = 10,
            eventsPerTrace = 10,
            totalEvents = 100,
            attributesPerEvent = 3,
            totalAttributes = 300,
            xesBytes = 1_000,
            xesGzBytes = 500,
            fileSha256 = year.toString().repeat(32),
            sourceDoi = when (datasetName) {
                "real-hospital" -> "10.4121/uuid:d9769f3d-0ab0-4fb8-803b-0d1120ffcf54"
                "real-bpic12" -> "10.4121/uuid:3926db30-f712-4394-aebc-75976070e91f"
                else -> error("No test DOI for $datasetName")
            },
            collection = "bpi-challenge",
            collectionOrder = year,
        )
        val specs = BenchmarkConfig.load(BenchmarkProfile.FULL).queries
        val measuredSpecs = specs.filter { it.isMeasuredFor(dataset.series) }
        val queries = measuredSpecs.flatMapIndexed { queryIndex, spec ->
            (1..30).flatMap { run ->
                listOf(
                    QueryBenchmarkResult(
                        "local", datasetName, spec.label, run, localSeconds + queryIndex * 0.001, "OK", 100,
                    ),
                    QueryBenchmarkResult(
                        "reference", datasetName, spec.label, run, referenceSeconds + queryIndex * 0.002, "OK", 100,
                    ),
                )
            }
        }
        val imports = listOf("local", "reference").map { system ->
            ImportBenchmarkResult(system, datasetName, 1, 0.1, "OK", "$system-store", 1)
        }
        val memorySamples = measuredSpecs.flatMap { spec ->
            listOf(
                MemorySample("$year-local-${spec.label}", MEMORY_PHASE_QUERIES, "local-total", 1_000, datasetName, spec.label),
                MemorySample(
                    "$year-reference-${spec.label}", MEMORY_PHASE_QUERIES, "reference-total", 1_500,
                    datasetName, spec.label,
                ),
            )
        }
        val io = measuredSpecs.flatMap { spec ->
            listOf(
                ContainerIoBenchmarkResult(
                    "local", "query", datasetName, spec.label, 0, "processm-interpreter",
                    1, 2, 3, 4, 5, 6, "OK",
                ),
                ContainerIoBenchmarkResult(
                    "reference", "query", datasetName, spec.label, 0, "processm-server",
                    1, 2, 3, 4, 5, 6, "OK",
                ),
            )
        }
        val cleanup = listOf("local", "reference").map { system ->
            DataStoreCleanupResult(system, "$system-store", "$system-id", "DELETED")
        }
        BenchmarkResultsWriter(directory).write(
            settings = settings,
            datasets = listOf(dataset),
            imports = imports,
            queries = queries,
            querySummaries = QueryStatistics.summarize(queries),
            storage = emptyList(),
            roundtrips = listOf(RoundtripBenchmarkResult(datasetName, "MATCH", 0, "")),
            cleanup = cleanup,
            memorySamples = memorySamples,
            memorySummaries = summarizeMemory(memorySamples),
            environmentDetails = mapOf(
                "host" to mapOf("cpuModel" to "test"),
                "dockerEngine" to mapOf("serverVersion" to "test"),
                "source" to mapOf("gitCommit" to gitCommit, "gitDirty" to false),
                "containers" to mapOf("images" to "identical"),
            ),
            querySpecs = specs,
            containerIo = io,
        )
    }
}
