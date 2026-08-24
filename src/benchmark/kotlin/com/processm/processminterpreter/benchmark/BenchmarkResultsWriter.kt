package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class BenchmarkResultsWriter(
    private val outputDirectory: Path,
) {
    private val mapper = jacksonObjectMapper()

    /** Rewrites derived paired statistics without touching raw measurements. */
    fun writeComparisonsOnly(comparisons: List<BenchmarkComparisonResult>) {
        outputDirectory.createDirectories()
        writeComparisons(comparisons)
    }

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
        querySpecs: List<BenchmarkQuerySpec> = emptyList(),
        comparisons: List<BenchmarkComparisonResult> = emptyList(),
        containerIo: List<ContainerIoBenchmarkResult> = emptyList(),
    ) {
        outputDirectory.createDirectories()
        writeDatasets(datasets)
        writeQuerySpecs(querySpecs)
        writeImports(imports)
        writeQueries(queries)
        writeQuerySummaries(querySummaries)
        writeStorage(storage)
        writeRoundtrips(roundtrips)
        writeMemory(memorySamples, memorySummaries)
        writeComparisons(comparisons)
        writeContainerIo(containerIo)
        writeCleanup(cleanup)
        writeEnvironment(settings, environmentDetails, datasets, querySpecs)
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
                "meanEventsPerTrace",
                "medianEventsPerTrace",
                "p95EventsPerTrace",
                "maxEventsPerTrace",
                "activityCount",
                "variantCount",
                "sourceDoi",
                "fileSha256",
                "meanEventAttributes",
                "collection",
                "collectionOrder",
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
                    it.meanEventsPerTrace,
                    it.medianEventsPerTrace,
                    it.p95EventsPerTrace,
                    it.maxEventsPerTrace,
                    it.activityCount,
                    it.variantCount,
                    it.sourceDoi,
                    it.fileSha256,
                    it.meanEventAttributes,
                    it.collection,
                    it.collectionOrder,
                )
            },
        )
    }

    /** Query semantics used by every downstream table and figure. */
    private fun writeQuerySpecs(querySpecs: List<BenchmarkQuerySpec>) {
        CsvWriter.write(
            outputDirectory.resolve("queries.csv"),
            listOf(
                "queryLabel", "displayName", "role", "workload", "measurementSeries",
                "scalingSeries", "purpose", "clause", "pql",
            ),
            querySpecs.map {
                listOf(
                    it.label, it.displayName, it.role.name.lowercase(), it.workload,
                    it.measurementSeries.sorted().joinToString(";"),
                    it.scalingSeries.sorted().joinToString(";"), it.purpose, it.clause, it.query,
                )
            },
        )
    }

    private fun writeComparisons(rows: List<BenchmarkComparisonResult>) {
        CsvWriter.write(
            outputDirectory.resolve("comparison-results.csv"),
            listOf(
                "metric", "datasetName", "series", "operationLabel", "displayName", "role", "pairs",
                "localMedianSeconds", "referenceMedianSeconds", "ratioReferenceToLocal",
                "confidenceLow", "confidenceHigh", "rawPValue", "holmPValue", "verdict", "status",
                "stabilityWindowSamples", "localEarlyMedianSeconds", "localLateMedianSeconds", "localEarlyLateRatio",
                "referenceEarlyMedianSeconds", "referenceLateMedianSeconds", "referenceEarlyLateRatio",
                "pairedEarlyMedianRatio", "pairedLateMedianRatio", "pairedEarlyLateRatio", "details",
            ),
            rows.map {
                listOf(
                    it.metric, it.datasetName, it.series, it.operationLabel, it.displayName, it.role, it.pairs,
                    it.localMedianSeconds, it.referenceMedianSeconds, it.ratioReferenceToLocal,
                    it.confidenceLow, it.confidenceHigh, it.rawPValue, it.holmPValue,
                    it.verdict, it.status, it.stabilityWindowSamples,
                    it.localEarlyMedianSeconds, it.localLateMedianSeconds, it.localEarlyLateRatio,
                    it.referenceEarlyMedianSeconds, it.referenceLateMedianSeconds, it.referenceEarlyLateRatio,
                    it.pairedEarlyMedianRatio, it.pairedLateMedianRatio, it.pairedEarlyLateRatio,
                    it.details,
                )
            },
        )
    }

    private fun writeContainerIo(rows: List<ContainerIoBenchmarkResult>) {
        CsvWriter.write(
            outputDirectory.resolve("container-io.csv"),
            listOf(
                "system", "phase", "datasetName", "operationLabel", "run", "component",
                "blockReadBytes", "blockWriteBytes", "blockReadOperations", "blockWriteOperations",
                "networkReceiveBytes", "networkTransmitBytes", "status", "details",
            ),
            rows.map {
                listOf(
                    it.system, it.phase, it.datasetName, it.operationLabel, it.run, it.component,
                    it.blockReadBytes, it.blockWriteBytes, it.blockReadOperations, it.blockWriteOperations,
                    it.networkReceiveBytes, it.networkTransmitBytes, it.status, it.details,
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
            listOf("timestamp", "phase", "datasetName", "operationLabel", "component", "bytes"),
            samples.map { listOf(it.timestamp, it.phase, it.datasetName, it.operationLabel, it.component, it.bytes) },
        )
        CsvWriter.write(
            outputDirectory.resolve("memory-summary.csv"),
            listOf("datasetName", "operationLabel", "component", "phase", "medianBytes", "peakBytes"),
            summaries.map {
                listOf(it.datasetName, it.operationLabel, it.component, it.phase, it.medianBytes, it.peakBytes)
            },
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
                "q1Seconds",
                "q3Seconds",
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
                    it.q1Seconds,
                    it.q3Seconds,
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
        datasets: List<PreparedDataset> = emptyList(),
        querySpecs: List<BenchmarkQuerySpec> = emptyList(),
    ) {
        val environment = mapOf(
            "benchmarkProtocolVersion" to settings.protocolVersion,
            "profile" to settings.profile.name.lowercase(),
            "warmups" to settings.queryWarmups,
            "repetitions" to settings.profile.repetitions,
            "importRepetitions" to settings.profile.importRepetitions,
            "globalWarmupRounds" to settings.globalWarmupRounds,
            "datasetOrder" to "fixed-declared",
            "localApi" to settings.localApi,
            "referenceApi" to settings.referenceApi,
            "localAppContainer" to settings.localAppContainer,
            "datasetFilter" to settings.datasetFilter.sorted(),
            "seriesFilter" to settings.seriesFilter.sorted(),
            "systemFilter" to settings.systemFilter.sorted(),
            "keepBenchmarkDataStores" to settings.keepBenchmarkDataStores,
            "experiment" to mapOf(
                "fingerprintSha256" to experimentFingerprint(datasets, querySpecs),
                "datasetCount" to datasets.size,
                "queryCount" to querySpecs.size,
                "queryPairing" to "adjacent-ab-ba",
                "queryTest" to "two-sided-wilcoxon-signed-rank",
                "confidenceInterval" to "paired-bootstrap-median-ratio",
                "multipleTestingCorrection" to "holm-within-dataset",
                "temporalStabilityGate" to "diagnostic-only-paired-ratio-of-medians-first-last-third",
                "temporalStabilityMaxRatio" to TemporalStability.MAX_EARLY_LATE_RATIO,
                "temporalStabilityMinimumSamples" to TemporalStability.MINIMUM_SAMPLES,
            ),
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
                appendLine("- Benchmark protocol version: ${settings.protocolVersion}")
                appendLine("- Profile: ${settings.profile.name.lowercase()}")
                appendLine("- Warmups per query and system: ${settings.queryWarmups}")
                appendLine("- Global warm-up rounds before the first measured dataset: ${settings.globalWarmupRounds}")
                appendLine("- Measured repetitions per query: ${settings.profile.repetitions}")
                appendLine("- Paired import repetitions per dataset: ${settings.profile.importRepetitions}")
                appendLine("- Dataset order: fixed, as declared in benchmark-datasets.json")
                appendLine("- Java: ${System.getProperty("java.version")}")
                appendLine("- OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
                appendLine("- Available processors reported by JVM: ${Runtime.getRuntime().availableProcessors()}")
                appendLine("- JVM max memory bytes: ${Runtime.getRuntime().maxMemory()}")
                appendLine("- Dataset filter: ${settings.datasetFilter.ifEmpty { setOf("all") }.joinToString(", ")}")
                appendLine("- Series filter: ${settings.seriesFilter.ifEmpty { setOf("all") }.joinToString(", ")}")
                appendLine("- System filter: ${settings.systemFilter.ifEmpty { setOf("all") }.joinToString(", ")}")
                appendLine("- Keep benchmark datastores after run: ${settings.keepBenchmarkDataStores}")
                appendLine()
                appendLine("## Compared Systems")
                appendLine()
                appendLine("- Local interpreter API: `${settings.localApi}`")
                appendLine("- Reference ProcessM API: `${settings.referenceApi}`")
                appendLine("- Local storage probe: logical file size of Neo4j `/data/databases` and `/data/transactions`; Neo4j community edition has no manual checkpoint procedure, so sizes reflect the naturally checkpointed state (stabilized by repeated reads)")
                appendLine("- Reference storage probe: allocated directory size of ProcessM PostgreSQL `/var/lib/postgresql/data`, after an explicit `CHECKPOINT;`")
                appendLine("- Host hardware, Docker container limits, container JVM versions, and database memory configuration are recorded in `environment.json` (`host` / `containers` keys)")
                appendLine("- Docker engine/VM budget, exact image IDs, Git commit/dirty state, and the workload fingerprint are recorded in `environment.json`")
                appendLine()
                appendLine("## Measurement Protocol")
                appendLine()
                appendLine("- Both systems are measured through HTTP API as black-box services.")
                appendLine("- Operations are issued sequentially by one benchmark runner, not concurrently.")
                appendLine("- The runner refuses to start unless both APIs expose zero pre-existing datastores; every dataset/system pair then gets a fresh datastore for this run.")
                appendLine("- Benchmark datastores use the `bench-` prefix and are deleted after the run unless `BENCHMARK_KEEP_DATASTORES=true`.")
                appendLine("- Import is repeated in fresh datastores; readiness is polled every 100 ms. The final pair remains for query measurements.")
                appendLine("- Memory sampling targets a 1 s pause between probes during a duplicate, unmeasured resource block after latency collection. The sampler is quiescent during timed requests; raw timestamps in `memory-results.csv` are authoritative because `docker stats --no-stream` adds probe latency.")
                appendLine("- Each (dataset, query) pair receives unrecorded warmups, then adjacent measured pairs in alternating LOCAL/REFERENCE order. No ambiguous recorded cold sample is used.")
                appendLine("- With at least ${TemporalStability.MINIMUM_SAMPLES} measured pairs, the REFERENCE/LOCAL ratio of medians is computed separately in the first and last chronological thirds. A direction-free ratio above ${TemporalStability.MAX_EARLY_LATE_RATIO} is reported as a drift warning, not used to discard otherwise complete paired evidence. This matches the reported effect estimand; a median of individual pair ratios is deliberately not used. Absolute system drift remains diagnostic because common-mode drift is controlled by pairing.")
                appendLine("- Docker Block I/O, Network I/O and, when exposed, cgroup v2 read/write operation deltas are sampled outside timed intervals and written to `container-io.csv`.")
                appendLine("- Log/trace/event counts are compared in every measured warm repetition. The last warm responses are also checked with the strict XES-JSON semantic comparator; on divergence all samples of the pair are marked `MISMATCH` (Q4 parity).")
                appendLine("- Per-dataset rows in `storage-results.csv` are protocol diagnostics. Thesis-grade Q3 disk evidence comes only from `measure-storage-scaling.py`, one fresh stack per dataset.")
                appendLine("- Query comparisons use REFERENCE/LOCAL median ratios, paired bootstrap 95% intervals and paired Wilcoxon tests. Holm correction is applied to inferential queries within each dataset.")
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

    private fun experimentFingerprint(
        datasets: List<PreparedDataset>,
        querySpecs: List<BenchmarkQuerySpec>,
    ): String {
        val canonical = buildString {
            datasets.sortedBy { it.name }.forEach {
                appendLine(
                    listOf(
                        "dataset", it.name, it.series, it.traces, it.eventsPerTrace,
                        it.totalEvents, it.attributesPerEvent, it.totalAttributes,
                        it.xesBytes, it.xesGzBytes, it.meanEventsPerTrace, it.medianEventsPerTrace,
                        it.p95EventsPerTrace, it.maxEventsPerTrace, it.activityCount,
                        it.variantCount, it.sourceDoi, it.fileSha256, it.meanEventAttributes,
                        it.collection, it.collectionOrder,
                    ).joinToString("\u001f"),
                )
            }
            querySpecs.sortedBy { it.label }.forEach {
                appendLine(
                    listOf(
                        "query", it.label, it.displayName, it.role, it.workload,
                        it.measurementSeries.sorted().joinToString(";"),
                        it.scalingSeries.sorted().joinToString(";"), it.purpose, it.clause, it.query,
                    ).joinToString("\u001f"),
                )
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
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
        val queryMismatchSamples = queries.count { it.status == QUERY_STATUS_MISMATCH }
        val queryMismatchPairs = queries
            .asSequence()
            .filter { it.status == QUERY_STATUS_MISMATCH }
            .map { it.datasetName to it.queryLabel }
            .toSet()
            .size
        val roundtripErrors = roundtrips.count { it.status != "MATCH" }
        val storageErrors = storage.count { it.status == STORAGE_STATUS_UNAVAILABLE || it.status == "ERROR" }
        val storageDiagnostics = storage.count {
            it.status != STORAGE_STATUS_OK && it.status != STORAGE_STATUS_UNAVAILABLE && it.status != "ERROR"
        }
        val cleanupErrors = cleanup.count { it.status == "ERROR" }
        val markdown = buildString {
            appendLine("# ProcessM Benchmark Report")
            appendLine()
            appendLine("- Profile: ${settings.profile.name.lowercase()}")
            appendLine("- Keep benchmark datastores: ${settings.keepBenchmarkDataStores}")
            appendLine("- Datasets: ${datasets.size}")
            appendLine("- Import results: ${imports.size}, errors: $importErrors")
            appendLine("- Query samples: ${queries.size}, errors: $queryErrors, mismatch samples: $queryMismatchSamples across $queryMismatchPairs (dataset, query) pairs")
            appendLine("- Query summaries: ${querySummaries.size}")
            appendLine("- Memory samples: ${memorySamples.size}")
            appendLine("- Storage measurements: ${storage.size}, errors: $storageErrors, non-OK diagnostics: $storageDiagnostics")
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
            appendLine("- `comparison-results.csv`")
            appendLine("- `container-io.csv`")
            appendLine("- `storage-results.csv`")
            appendLine("- `roundtrip-results.csv`")
            appendLine("- `memory-results.csv`")
            appendLine("- `memory-summary.csv`")
            appendLine("- `cleanup-results.csv`")
            appendLine("- `environment.json`")
            appendLine("- `environment.md`")
            appendLine("- `benchmark-report.md`")
            appendLine("- `benchmark-appendix.md`")
            appendLine()
            appendLine("Generate SVG plots with:")
            appendLine()
            appendLine("```bash")
            appendLine("python3 scripts/benchmarks/plot-readable-benchmark-results.py $outputDirectory")
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
                    q1Seconds = ThesisStatistics.quantile(seconds, 0.25),
                    q3Seconds = ThesisStatistics.quantile(seconds, 0.75),
                    p95Seconds = ThesisStatistics.quantile(seconds, 0.95),
                    minSeconds = seconds.first(),
                    maxSeconds = seconds.last(),
                    averageSeconds = seconds.average(),
                )
            }
            .sortedWith(compareBy<QueryBenchmarkSummary> { it.datasetName }.thenBy { it.system }.thenBy { it.queryLabel })
}
