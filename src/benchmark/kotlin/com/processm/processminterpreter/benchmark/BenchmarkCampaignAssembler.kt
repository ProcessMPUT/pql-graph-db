package com.processm.processminterpreter.benchmark

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries

/**
 * Combines complete one-dataset BLOCK runs into one reportable campaign.
 *
 * Raw repetitions are combined and all comparisons are derived again.  Ready-made
 * p-values and verdicts from block reports are deliberately ignored.
 */
object BenchmarkCampaignAssembler {
    fun assemble(
        outputDirectory: Path,
        blockDirectories: List<Path>,
    ) {
        require(blockDirectories.isNotEmpty()) { "A campaign needs at least one block" }
        val normalizedOutput = outputDirectory.toAbsolutePath().normalize()
        val normalizedInputs = blockDirectories.map { it.toAbsolutePath().normalize() }
        require(normalizedOutput !in normalizedInputs) { "Campaign output must not overwrite an input block" }
        require(!Files.exists(normalizedOutput) || normalizedOutput.listDirectoryEntries().isEmpty()) {
            "Campaign output already exists and is not empty: $normalizedOutput"
        }

        val blocks = normalizedInputs.map(RunReplay::load)
        blocks.forEach(::validateBlock)
        require(blocks.map { it.datasets.single().name }.distinct().size == blocks.size) {
            "A dataset may occur in only one campaign block: " +
                blocks.groupBy { it.datasets.single().name }.filterValues { it.size > 1 }.keys.joinToString()
        }

        val first = blocks.first()
        val signature = signature(first)
        blocks.drop(1).forEach { candidate ->
            require(signature(candidate) == signature) {
                "Campaign block ${candidate.directory} was collected with a different protocol, workload, source, " +
                    "container image, resource budget, JVM, Docker engine, or host"
            }
        }

        val datasets = blocks.flatMap { it.datasets }
        val imports = blocks.flatMap { it.imports }
        val queries = blocks.flatMap { it.queries }
        val memorySamples = blocks.flatMap { it.memorySamples }
        val memorySummaries = summarizeMemory(memorySamples)
        val comparisons = BenchmarkAnalysis.queryComparisons(
            datasets = datasets,
            querySpecs = first.querySpecs,
            samples = queries,
            expectedPairs = BenchmarkProfile.BLOCK.repetitions,
        ) + BenchmarkAnalysis.importComparisons(
            datasets = datasets,
            samples = imports,
            expectedPairs = BenchmarkProfile.BLOCK.importRepetitions,
        )
        require(comparisons.all { it.status == "OK" }) {
            "Campaign contains invalid comparisons: " +
                comparisons.filter { it.status != "OK" }.joinToString { "${it.datasetName}/${it.operationLabel}" }
        }

        normalizedOutput.createDirectories()
        val settings = first.settings.copy(
            profile = BenchmarkProfile.CAMPAIGN,
            outputRoot = normalizedOutput.parent,
            datasetFilter = datasets.map { it.name }.toSet(),
            seriesFilter = datasets.map { it.series }.toSet(),
        )
        val environmentDetails = buildMap<String, Any?> {
            listOf("host", "dockerEngine", "source", "containers").forEach { key ->
                put(key, first.environment[key])
            }
            put(
                "campaign",
                mapOf(
                    "status" to "assembled-from-complete-blocks",
                    "blockCount" to blocks.size,
                    "blockDirectories" to normalizedInputs.map(Path::toString),
                ),
            )
        }
        BenchmarkResultsWriter(normalizedOutput).write(
            settings = settings,
            datasets = datasets,
            imports = imports,
            queries = queries,
            querySummaries = QueryStatistics.summarize(queries),
            storage = blocks.flatMap { it.storage },
            roundtrips = blocks.flatMap { it.roundtrips },
            cleanup = blocks.flatMap { it.cleanup },
            memorySamples = memorySamples,
            memorySummaries = memorySummaries,
            environmentDetails = environmentDetails,
            querySpecs = first.querySpecs,
            comparisons = comparisons,
            containerIo = blocks.flatMap { it.containerIo },
        )
        BenchmarkReportWriter(normalizedOutput).write(
            runId = normalizedOutput.fileName.toString(),
            settings = settings,
            datasets = datasets,
            querySpecs = first.querySpecs,
            imports = imports,
            queries = queries,
            comparisons = comparisons,
            containerIo = blocks.flatMap { it.containerIo },
            memorySummaries = memorySummaries,
            roundtrips = blocks.flatMap { it.roundtrips },
        )
        generateReportArtifacts(normalizedOutput)
        println("Benchmark campaign written to $normalizedOutput")
    }

    private fun validateBlock(block: LoadedBenchmarkRun) {
        require(block.settings.protocolVersion == CURRENT_BENCHMARK_PROTOCOL_VERSION) {
            "${block.directory}: expected protocol $CURRENT_BENCHMARK_PROTOCOL_VERSION, " +
                "found ${block.settings.protocolVersion}"
        }
        require(block.settings.profile == BenchmarkProfile.BLOCK) {
            "${block.directory}: only BLOCK runs can be assembled, found ${block.settings.profile}"
        }
        require((block.environment["repetitions"] as? Number)?.toInt() == BenchmarkProfile.BLOCK.repetitions &&
            (block.environment["importRepetitions"] as? Number)?.toInt() == BenchmarkProfile.BLOCK.importRepetitions &&
            (block.environment["warmups"] as? Number)?.toInt() == BenchmarkProfile.BLOCK.warmups &&
            (block.environment["globalWarmupRounds"] as? Number)?.toInt() == BenchmarkProfile.BLOCK.globalWarmupRounds
        ) { "${block.directory}: BLOCK repetition or warm-up counts are incomplete" }
        require(block.datasets.size == 1) { "${block.directory}: expected exactly one dataset" }
        val dataset = block.datasets.single()
        val currentConfig = BenchmarkConfig.load(BenchmarkProfile.FULL)
        val expectedDataset = currentConfig.datasets.singleOrNull { it.name == dataset.name }
        require(expectedDataset != null && dataset.series == "real-validation" &&
            dataset.series == expectedDataset.series && dataset.sourceDoi == expectedDataset.sourceDoi &&
            dataset.collection == expectedDataset.collection && dataset.collectionOrder == expectedDataset.collectionOrder
        ) { "${block.directory}: dataset metadata is not a current real-validation contract" }
        val expectedQuerySpecs = currentConfig.queries
        require(queryContract(block.querySpecs) == queryContract(expectedQuerySpecs)) {
            "${block.directory}: queries.csv is not the complete current query contract"
        }
        val expectedLabels = expectedQuerySpecs.filter { it.isMeasuredFor(dataset.series) }.map { it.label }.toSet()
        val observedLabels = block.queries.map { it.queryLabel }.toSet()
        require(observedLabels == expectedLabels) {
            "${block.directory}: expected query results for ${expectedLabels.sorted()}, found ${observedLabels.sorted()}"
        }
        require(block.imports.all { it.status == "OK" }) { "${block.directory}: import errors are present" }
        require(block.queries.all { it.status == "OK" }) { "${block.directory}: query errors or mismatches are present" }
        require(block.roundtrips.singleOrNull { it.datasetName == dataset.name }?.status == "MATCH") {
            "${block.directory}: exactly one successful dataset round-trip gate is required"
        }
        val memoryContexts = block.memorySummaries.filter { it.phase == MEMORY_PHASE_QUERIES }
            .groupBy { it.datasetName to it.operationLabel }
        expectedLabels.forEach { label ->
            val components = memoryContexts[dataset.name to label].orEmpty().map { it.component }.toSet()
            require(setOf("local-total", "reference-total").all(components::contains)) {
                "${block.directory}: complete LOCAL and REFERENCE memory totals are required for $label"
            }
        }
        require(block.containerIo.none {
            ContainerIoValidity.isCriticalFailure(it, block.settings.localAppContainer)
        }) { "${block.directory}: critical container I/O gaps are present" }
        expectedLabels.forEach { label ->
            val systems = block.containerIo.filter {
                it.phase == "query" && it.datasetName == dataset.name && it.operationLabel == label
            }.map { it.system }.toSet()
            require(systems == setOf("local", "reference")) {
                "${block.directory}: complete LOCAL and REFERENCE I/O rows are required for $label"
            }
        }
        require(block.cleanup.isNotEmpty() &&
            block.cleanup.map { it.system }.toSet().containsAll(setOf("local", "reference")) &&
            block.cleanup.all { it.status in setOf("DELETED", "SKIPPED") }
        ) {
            "${block.directory}: datastore cleanup did not complete"
        }
        val source = block.environment["source"] as? Map<*, *>
        require(source?.get("gitDirty") == false) {
            "${block.directory}: final campaign blocks require a clean Git worktree"
        }
    }

    private fun signature(block: LoadedBenchmarkRun): CampaignSignature {
        val environmentKeys = listOf(
            "benchmarkProtocolVersion", "warmups", "repetitions", "importRepetitions", "globalWarmupRounds",
            "localApi", "referenceApi", "localAppContainer", "systemFilter", "javaVersion", "osName", "osVersion",
            "host", "dockerEngine", "source", "containers",
        )
        return CampaignSignature(
            environment = environmentKeys.associateWith(block.environment::get),
            queries = block.querySpecs,
        )
    }

    private data class CampaignSignature(
        val environment: Map<String, Any?>,
        val queries: List<BenchmarkQuerySpec>,
    )

    private fun queryContract(specs: List<BenchmarkQuerySpec>): List<List<Any>> = specs.map { spec ->
        listOf(
            spec.label,
            spec.query,
            spec.workload,
            spec.clause,
            spec.scalingSeries.sorted(),
            spec.displayName,
            spec.purpose,
            spec.role,
            spec.measurementSeries.sorted(),
        )
    }
}
