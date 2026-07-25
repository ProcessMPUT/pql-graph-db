package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class BenchmarkResultsWriter(
    private val outputDirectory: Path,
) {
    private val mapper = jacksonObjectMapper()

    fun write(
        settings: BenchmarkSettings,
        datasets: List<PreparedDataset>,
        imports: List<ImportBenchmarkResult>,
        queries: List<QueryBenchmarkResult>,
        querySummaries: List<QueryBenchmarkSummary>,
        storage: List<StorageBenchmarkResult>,
        roundtrips: List<RoundtripBenchmarkResult>,
        cleanup: List<DataStoreCleanupResult>,
        memorySamples: List<MemorySample> = emptyList(),
        memorySummaries: List<MemorySummary> = emptyList(),
        environmentDetails: Map<String, Any?> = emptyMap(),
    ) {
        outputDirectory.createDirectories()
        writeDatasets(datasets)
        writeImports(imports)
        writeQueries(queries)
        writeQuerySummaries(querySummaries)
        writeStorage(storage)
        writeRoundtrips(roundtrips)
        writeMemory(memorySamples, memorySummaries)
        writeCleanup(cleanup)
        writeEnvironment(settings, environmentDetails)
        writeSummary(settings, datasets, imports, queries, querySummaries, storage, roundtrips, cleanup, memorySamples)
    }

    private fun writeDatasets(datasets: List<PreparedDataset>) {
        CsvWriter.write(
            outputDirectory.resolve("datasets.csv"),
            listOf(
                "datasetName",
                "series",
                "traces",
                "eventsPerTrace",
                "totalEvents",
                "attributesPerEvent",
                "totalAttributes",
                "xesBytes",
                "xesGzBytes",
            ),
            datasets.map {
                listOf(
                    it.name,
                    it.series,
                    it.traces,
                    it.eventsPerTrace,
                    it.totalEvents,
                    it.attributesPerEvent,
                    it.totalAttributes,
                    it.xesBytes,
                    it.xesGzBytes,
                )
            },
        )
    }

    private fun writeImports(imports: List<ImportBenchmarkResult>) {
        CsvWriter.write(
            outputDirectory.resolve("import-results.csv"),
            listOf("system", "datasetName", "run", "seconds", "status", "dataStoreId", "logCount", "details"),
            imports.map { listOf(it.system, it.datasetName, it.run, it.seconds, it.status, it.dataStoreId, it.logCount, it.details) },
        )
    }

    private fun writeQueries(queries: List<QueryBenchmarkResult>) {
        CsvWriter.write(
            outputDirectory.resolve("query-results.csv"),
            listOf(
                "system",
                "datasetName",
                "queryLabel",
                "run",
                "phase",
                "seconds",
                "status",
                "responseBytes",
                "logCount",
                "traceCount",
                "eventCount",
                "details",
            ),
            queries.map {
                listOf(
                    it.system,
                    it.datasetName,
                    it.queryLabel,
                    it.run,
                    it.phase,
                    it.seconds,
                    it.status,
                    it.responseBytes,
                    it.logCount,
                    it.traceCount,
                    it.eventCount,
                    it.details,
                )
            },
        )
    }

    private fun writeMemory(
        samples: List<MemorySample>,
        summaries: List<MemorySummary>,
    ) {
        CsvWriter.write(
            outputDirectory.resolve("memory-results.csv"),
            listOf("timestamp", "phase", "component", "bytes"),
            samples.map { listOf(it.timestamp, it.phase, it.component, it.bytes) },
        )
        CsvWriter.write(
            outputDirectory.resolve("memory-summary.csv"),
            listOf("component", "phase", "medianBytes", "peakBytes"),
            summaries.map { listOf(it.component, it.phase, it.medianBytes, it.peakBytes) },
        )
    }

    private fun writeQuerySummaries(summaries: List<QueryBenchmarkSummary>) {
        CsvWriter.write(
            outputDirectory.resolve("query-summary.csv"),
            listOf(
                "system",
                "datasetName",
                "queryLabel",
                "samples",
                "medianSeconds",
                "p95Seconds",
                "minSeconds",
                "maxSeconds",
                "averageSeconds",
            ),
            summaries.map {
                listOf(
                    it.system,
                    it.datasetName,
                    it.queryLabel,
                    it.samples,
                    it.medianSeconds,
                    it.p95Seconds,
                    it.minSeconds,
                    it.maxSeconds,
                    it.averageSeconds,
                )
            },
        )
    }

    private fun writeStorage(storage: List<StorageBenchmarkResult>) {
        CsvWriter.write(
            outputDirectory.resolve("storage-results.csv"),
            listOf(
                "system",
                "datasetName",
                "beforeBytes",
                "afterBytes",
                "deltaBytes",
                "deltaToXesRatio",
                "deltaToGzipRatio",
                "status",
            ),
            storage.map {
                listOf(
                    it.system,
                    it.datasetName,
                    it.beforeBytes,
                    it.afterBytes,
                    it.deltaBytes,
                    it.deltaToXesRatio,
                    it.deltaToGzipRatio,
                    it.status,
                )
            },
        )
    }

    private fun writeRoundtrips(roundtrips: List<RoundtripBenchmarkResult>) {
        CsvWriter.write(
            outputDirectory.resolve("roundtrip-results.csv"),
            listOf("datasetName", "status", "differencesCount", "detailsPath"),
            roundtrips.map { listOf(it.datasetName, it.status, it.differencesCount, it.detailsPath) },
        )
    }

    fun writeCleanupOnly(
        settings: BenchmarkSettings,
        cleanup: List<DataStoreCleanupResult>,
    ) {
        outputDirectory.createDirectories()
        writeCleanup(cleanup)
        writeEnvironment(settings)
        val errors = cleanup.count { it.status == "ERROR" }
        outputDirectory.resolve("summary.md").writeText(
            buildString {
                appendLine("# ProcessM Benchmark Cleanup Report")
                appendLine()
                appendLine("- Matched benchmark datastores: ${cleanup.size}")
                appendLine("- Cleanup errors: $errors")
                appendLine()
                appendLine("Only datastores with the `bench-` prefix are deleted.")
                appendLine()
                appendLine("## Output Files")
                appendLine()
                appendLine("- `cleanup-results.csv`")
                appendLine("- `environment.json`")
                appendLine("- `environment.md`")
            },
        )
    }

    private fun writeCleanup(cleanup: List<DataStoreCleanupResult>) {
        CsvWriter.write(
            outputDirectory.resolve("cleanup-results.csv"),
            listOf("system", "dataStoreName", "dataStoreId", "status", "details"),
            cleanup.map { listOf(it.system, it.dataStoreName, it.dataStoreId, it.status, it.details) },
        )
    }

    private fun writeEnvironment(
        settings: BenchmarkSettings,
        environmentDetails: Map<String, Any?> = emptyMap(),
    ) {
        val environment = mapOf(
            "profile" to settings.profile.name.lowercase(),
            "warmups" to settings.profile.warmups,
            "repetitions" to settings.profile.repetitions,
            "localApi" to settings.localApi,
            "referenceApi" to settings.referenceApi,
            "datasetFilter" to settings.datasetFilter.sorted(),
            "systemFilter" to settings.systemFilter.sorted(),
            "keepBenchmarkDataStores" to settings.keepBenchmarkDataStores,
            "javaVersion" to System.getProperty("java.version"),
            "osName" to System.getProperty("os.name"),
            "osVersion" to System.getProperty("os.version"),
            "userName" to System.getProperty("user.name"),
        ) + environmentDetails
        outputDirectory.resolve("environment.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(environment))
        outputDirectory.resolve("environment.md").writeText(
            buildString {
                appendLine("# Benchmark Environment")
                appendLine()
                appendLine("## Runtime")
                appendLine()
                appendLine("- Profile: ${settings.profile.name.lowercase()}")
                appendLine("- Warmups per query: ${settings.profile.warmups}")
                appendLine("- Measured repetitions per query: ${settings.profile.repetitions}")
                appendLine("- Java: ${System.getProperty("java.version")}")
                appendLine("- OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
                appendLine("- Available processors reported by JVM: ${Runtime.getRuntime().availableProcessors()}")
                appendLine("- JVM max memory bytes: ${Runtime.getRuntime().maxMemory()}")
                appendLine("- Dataset filter: ${settings.datasetFilter.ifEmpty { setOf("all") }.joinToString(", ")}")
                appendLine("- System filter: ${settings.systemFilter.ifEmpty { setOf("all") }.joinToString(", ")}")
                appendLine("- Keep benchmark datastores after run: ${settings.keepBenchmarkDataStores}")
                appendLine()
                appendLine("## Compared Systems")
                appendLine()
                appendLine("- Local interpreter API: `${settings.localApi}`")
                appendLine("- Reference ProcessM API: `${settings.referenceApi}`")
                appendLine("- Local storage probe: logical file size of Neo4j `/data/databases` and `/data/transactions`; Neo4j community edition has no manual checkpoint procedure, so sizes reflect the naturally checkpointed state (stabilized by repeated reads)")
                appendLine("- Reference storage probe: allocated directory size of ProcessM PostgreSQL `/var/lib/postgresql/data`, after an explicit `CHECKPOINT;`")
                appendLine("- Host hardware, Docker container limits, and database memory configuration are recorded in `environment.json` (`host` / `containers` keys)")
                appendLine()
                appendLine("## Measurement Protocol")
                appendLine()
                appendLine("- Both systems are measured through HTTP API as black-box services.")
                appendLine("- Operations are issued sequentially by one benchmark runner, not concurrently.")
                appendLine("- Every dataset/system pair gets a fresh datastore for this run.")
                appendLine("- Benchmark datastores use the `bench-` prefix and are deleted after the run unless `BENCHMARK_KEEP_DATASTORES=true`.")
                appendLine("- Storage is measured as stabilized directory size before and after importing a dataset.")
                appendLine("- Memory is sampled every 1 s by a background daemon thread: `docker stats` for `processm-server` and `processm-neo4j` plus host JVM RSS (`tasklist`) for the local application; phases: `idle` (${settings.profile.idleBaselineSeconds} s baseline before imports) and `queries`.")
                appendLine("- Each (dataset, query) pair runs one recorded `cold` execution per system before warmups; measured repetitions alternate between systems (local, reference, local, reference, ...).")
                appendLine("- Response log/trace/event counts of the last warm sample are compared between systems; on divergence all samples of the pair are marked `MISMATCH` (Q4 parity).")
                appendLine("- Neo4j may report `BELOW_ALLOCATION_GRANULARITY` on already-grown stores; use fresh storage and `BENCHMARK_DATASET_FILTER` for thesis-grade per-dataset storage measurements.")
                appendLine("- Query charts should use medians or p95 values from `query-summary.csv`, not single samples.")
                appendLine()
                appendLine("## Recommended Manual Controls")
                appendLine()
                appendLine("- Run on AC power with Windows power mode set to performance.")
                appendLine("- Close unrelated CPU/RAM-heavy applications before `runBenchmarkFull`.")
                appendLine("- Keep Docker Desktop CPU/RAM limits fixed for all compared runs.")
                appendLine("- Run the compatibility report before using benchmark results as thesis evidence.")
            },
        )
    }

    private fun writeSummary(
        settings: BenchmarkSettings,
        datasets: List<PreparedDataset>,
        imports: List<ImportBenchmarkResult>,
        queries: List<QueryBenchmarkResult>,
        querySummaries: List<QueryBenchmarkSummary>,
        storage: List<StorageBenchmarkResult>,
        roundtrips: List<RoundtripBenchmarkResult>,
        cleanup: List<DataStoreCleanupResult>,
        memorySamples: List<MemorySample>,
    ) {
        val importErrors = imports.count { it.status != "OK" }
        val queryErrors = queries.count { it.status == "ERROR" }
        val queryMismatches = queries.count { it.status == QUERY_STATUS_MISMATCH }
        val roundtripErrors = roundtrips.count { it.status != "MATCH" }
        val storageErrors = storage.count { it.status != "OK" }
        val cleanupErrors = cleanup.count { it.status == "ERROR" }
        val markdown = buildString {
            appendLine("# ProcessM Benchmark Report")
            appendLine()
            appendLine("- Profile: ${settings.profile.name.lowercase()}")
            appendLine("- Keep benchmark datastores: ${settings.keepBenchmarkDataStores}")
            appendLine("- Datasets: ${datasets.size}")
            appendLine("- Import results: ${imports.size}, errors: $importErrors")
            appendLine("- Query samples: ${queries.size}, errors: $queryErrors, response-count mismatches: $queryMismatches")
            appendLine("- Query summaries: ${querySummaries.size}")
            appendLine("- Memory samples: ${memorySamples.size}")
            appendLine("- Storage measurements: ${storage.size}, errors: $storageErrors")
            appendLine("- Roundtrip checks: ${roundtrips.size}, errors: $roundtripErrors")
            appendLine("- Datastore cleanup results: ${cleanup.size}, errors: $cleanupErrors")
            appendLine()
            appendLine("## Compatibility Baseline")
            appendLine()
            appendLine("Run the compatibility baseline separately before using this benchmark as thesis evidence:")
            appendLine()
            appendLine("```bash")
            appendLine("python3 scripts/run-compatibility-report.py --query-source dropdown --profile extended --include-multi-log-checks --multi-log-cases scripts/verify-compatibility.multi-log.cases.local.json --skip-failure-snapshots --measure-payload-size")
            appendLine("```")
            appendLine()
            appendLine("Expected baseline: `0` strict compatibility problems.")
            appendLine()
            appendLine("## Output Files")
            appendLine()
            appendLine("- `datasets.csv`")
            appendLine("- `import-results.csv`")
            appendLine("- `query-results.csv`")
            appendLine("- `query-summary.csv`")
            appendLine("- `storage-results.csv`")
            appendLine("- `roundtrip-results.csv`")
            appendLine("- `memory-results.csv`")
            appendLine("- `memory-summary.csv`")
            appendLine("- `cleanup-results.csv`")
            appendLine("- `environment.json`")
            appendLine("- `environment.md`")
            appendLine("- `thesis-report.md`")
            appendLine("- `thesis-tables.tex`")
            appendLine()
            appendLine("Generate SVG plots with:")
            appendLine()
            appendLine("```bash")
            appendLine("python3 scripts/benchmarks/plot-benchmark-results.py $outputDirectory")
            appendLine("```")
        }
        outputDirectory.resolve("summary.md").writeText(markdown)
    }
}

object QueryStatistics {
    /**
     * Summarizes only successful warm repetitions. Cold samples (`phase=cold`) are
     * reported raw in `query-results.csv` and must not skew medians; MISMATCH
     * samples are invalidated measurements.
     *
     * Quantiles use the same type-7 estimator as the thesis tables
     * ([ThesisStatistics]), so a median or p95 in `query-summary.csv` — and in
     * every chart plotted from it — is numerically identical to the one printed
     * in `thesis-report.md` for the same samples.
     */
    fun summarize(results: List<QueryBenchmarkResult>): List<QueryBenchmarkSummary> =
        results
            .filter { it.status == "OK" && it.phase == QUERY_PHASE_WARM }
            .groupBy { Triple(it.system, it.datasetName, it.queryLabel) }
            .map { (key, samples) ->
                val seconds = samples.map { it.seconds }.sorted()
                QueryBenchmarkSummary(
                    system = key.first,
                    datasetName = key.second,
                    queryLabel = key.third,
                    samples = seconds.size,
                    medianSeconds = ThesisStatistics.quantile(seconds, 0.50),
                    p95Seconds = ThesisStatistics.quantile(seconds, 0.95),
                    minSeconds = seconds.first(),
                    maxSeconds = seconds.last(),
                    averageSeconds = seconds.average(),
                )
            }
            .sortedWith(compareBy<QueryBenchmarkSummary> { it.datasetName }.thenBy { it.system }.thenBy { it.queryLabel })
}
