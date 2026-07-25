package com.processm.processminterpreter.benchmark

import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.relativeToOrSelf

fun main(args: Array<String>) {
    val command = args.firstOrNull()?.lowercase()
    val profile = command
        ?.takeIf { it != "cleanup" }
        ?.let { BenchmarkProfile.valueOf(it.uppercase()) }
        ?: BenchmarkProfile.SMOKE
    val settings = BenchmarkSettings.fromEnvironment(profile)
    val runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val outputDirectory = settings.outputRoot.resolve(if (command == "cleanup") "cleanup-$runId" else runId)
    if (command == "cleanup") {
        runCleanup(settings, outputDirectory)
        return
    }

    val generatedDatasetsDirectory = outputDirectory.resolve("generated-datasets")
    outputDirectory.createDirectories()

    println("Starting ${profile.name.lowercase()} benchmark run: $outputDirectory")

    val config = BenchmarkConfig.load(profile)
    val generator = XesDatasetGenerator()
    val selectedSpecs = config.datasets.filter { spec ->
        settings.datasetFilter.isEmpty() || spec.name in settings.datasetFilter
    }
    require(selectedSpecs.isNotEmpty()) {
        "No benchmark datasets selected. Filter was: ${settings.datasetFilter}"
    }

    val datasets = selectedSpecs.map { spec ->
        println("Preparing dataset ${spec.name}")
        generator.prepare(spec, generatedDatasetsDirectory)
    }

    val systems = benchmarkSystems(settings)
    require(systems.isNotEmpty()) {
        "No benchmark systems selected. Filter was: ${settings.systemFilter}"
    }
    val clients = systems.associateWith { system ->
        BenchmarkHttpClient(system.apiBase, settings.processMLogin, settings.processMPassword).also {
            println("Authenticating ${system.name} at ${system.apiBase}")
            it.authenticateIfSupported()
        }
    }

    val storageMeter = DockerStorageMeter()
    val imports = mutableListOf<ImportBenchmarkResult>()
    val queries = mutableListOf<QueryBenchmarkResult>()
    val storage = mutableListOf<StorageBenchmarkResult>()
    val roundtrips = mutableListOf<RoundtripBenchmarkResult>()
    val cleanup = mutableListOf<DataStoreCleanupResult>()
    val createdDataStores = mutableListOf<CreatedDataStoreHandle>()
    val importedHandles = mutableListOf<ImportedDatasetHandle>()
    val memorySampler = MemorySampler(memorySources(settings, systems))
    var fatalError: Throwable? = null

    try {
        memorySampler.start()
        println("Sampling idle memory baseline for ${settings.profile.idleBaselineSeconds}s")
        memorySampler.sampleBlocking(MEMORY_PHASE_IDLE, settings.profile.idleBaselineSeconds)

        datasets.forEach { dataset ->
            systems.forEach { system ->
                val client = clients.getValue(system)
                val dataStoreName = "bench-$runId-${system.name}-${dataset.name}"
                println("[${system.name}] Creating datastore $dataStoreName")
                val dataStoreId = runCatching { client.createDataStore(dataStoreName) }
                    .getOrElse { error ->
                        imports += ImportBenchmarkResult(
                            system = system.name,
                            datasetName = dataset.name,
                            run = 1,
                            seconds = 0.0,
                            status = "ERROR",
                            dataStoreId = "",
                            logCount = 0,
                            details = error.message.orEmpty(),
                        )
                        return@forEach
                    }
                createdDataStores += CreatedDataStoreHandle(system, dataStoreName, dataStoreId)

                val beforeStorage = storageMeter.measureStable(system.storage)
                val importResult = runCatching { client.uploadLogAndWait(dataStoreId, dataset.file) }
                val afterStorage = storageMeter.measureStable(system.storage)

                importResult
                    .onSuccess { result ->
                        imports += ImportBenchmarkResult(
                            system = system.name,
                            datasetName = dataset.name,
                            run = 1,
                            seconds = result.seconds,
                            status = "OK",
                            dataStoreId = dataStoreId,
                            logCount = result.logCount,
                        )
                        importedHandles += ImportedDatasetHandle(system, dataset, dataStoreId)
                    }
                    .onFailure { error ->
                        imports += ImportBenchmarkResult(
                            system = system.name,
                            datasetName = dataset.name,
                            run = 1,
                            seconds = 0.0,
                            status = "ERROR",
                            dataStoreId = dataStoreId,
                            logCount = 0,
                            details = error.message.orEmpty(),
                        )
                    }

                storage += storageResult(system, dataset, beforeStorage, afterStorage)
            }
        }

        // Query phase (methodology 5.4): per (dataset, query) pair, one recorded cold
        // execution per system first, then interleaved warmups, then interleaved
        // measured repetitions (local, reference, local, reference, ...).
        memorySampler.setPhase(MEMORY_PHASE_QUERIES)
        val measuredQueries = config.queries
        datasets.forEach { dataset ->
            val handles = importedHandles.filter { it.dataset.name == dataset.name }
            if (handles.isEmpty()) return@forEach
            measuredQueries.forEach { query ->
                println("Query ${query.label} on ${dataset.name} [${handles.joinToString(",") { it.system.name }}]")
                val plan = buildQueryExecutionPlan(
                    systemCount = handles.size,
                    warmups = settings.profile.warmups,
                    repetitions = settings.profile.repetitions,
                )
                val pairSamples = mutableListOf<QueryBenchmarkResult>()
                plan.forEach { step ->
                    val handle = handles[step.systemIndex]
                    val client = clients.getValue(handle.system)
                    when (step.kind) {
                        QueryStepKind.WARMUP -> runCatching { client.executeQuery(handle.dataStoreId, query.query) }
                        QueryStepKind.COLD ->
                            pairSamples += recordedQuerySample(client, handle, query.label, query.query, step.run, QUERY_PHASE_COLD)
                        QueryStepKind.MEASURED ->
                            pairSamples += recordedQuerySample(client, handle, query.label, query.query, step.run, QUERY_PHASE_WARM)
                    }
                }
                queries += applyResponseCountParity(pairSamples)
            }
        }
        memorySampler.setPhase(null)

        importedHandles
            .filter { it.system.name == "local" }
            .forEach { handle ->
                println("[local] Roundtrip ${handle.dataset.name}")
                val detailsPath = outputDirectory.resolve("roundtrip-details").resolve("${handle.dataset.name}.txt")
                val result = runCatching {
                    val exported = clients.getValue(handle.system).exportQueryAsXes(handle.dataStoreId)
                    CanonicalXesComparator.compareFiles(handle.dataset.file, exported, detailsPath)
                }
                roundtrips += result.fold(
                    onSuccess = {
                        RoundtripBenchmarkResult(
                            datasetName = handle.dataset.name,
                            status = if (it.matches) "MATCH" else "MISMATCH",
                            differencesCount = it.differences.size,
                            detailsPath = if (it.matches) "" else detailsPath.relativeToOrSelf(Path.of("")).toString(),
                        )
                    },
                    onFailure = {
                        RoundtripBenchmarkResult(
                            datasetName = handle.dataset.name,
                            status = "ERROR",
                            differencesCount = 1,
                            detailsPath = it.message.orEmpty(),
                        )
                    },
                )
            }
    } catch (error: Throwable) {
        fatalError = error
    } finally {
        memorySampler.stop()
        cleanup += cleanupCreatedDataStores(settings, createdDataStores, clients)
    }

    val querySummaries = QueryStatistics.summarize(queries)
    BenchmarkResultsWriter(outputDirectory).write(
        settings = settings,
        datasets = datasets,
        imports = imports,
        queries = queries,
        querySummaries = querySummaries,
        storage = storage,
        roundtrips = roundtrips,
        cleanup = cleanup,
        memorySamples = memorySampler.samples(),
        memorySummaries = memorySampler.summaries(),
        environmentDetails = EnvironmentProbe.collect(systems.map { it.storage.container }),
    )
    // Thesis artifacts (METODOLOGIA §6): generated at the end of every run from the
    // in-memory records, never by re-reading the CSVs written above.
    ThesisReportWriter(outputDirectory).write(
        runId = runId,
        settings = settings,
        datasets = datasets,
        imports = imports,
        queries = queries,
        storage = storage,
        roundtrips = roundtrips,
        memorySummaries = memorySampler.summaries(),
        querySpecs = config.queries,
    )

    println("Benchmark report written to $outputDirectory")
    fatalError?.let { throw it }
    val mismatches = queries.count { it.status == QUERY_STATUS_MISMATCH }
    if (mismatches > 0) {
        println("WARNING: $mismatches query sample(s) invalidated by response-count MISMATCH. See query-results.csv")
    }
    val strictErrors = imports.count { it.status != "OK" } +
        queries.count { it.status == "ERROR" } +
        roundtrips.count { it.status == "ERROR" }
    if (strictErrors > 0) {
        error("Benchmark finished with $strictErrors infrastructure/runtime error(s). See $outputDirectory")
    }
}

private fun recordedQuerySample(
    client: BenchmarkHttpClient,
    handle: ImportedDatasetHandle,
    queryLabel: String,
    pql: String,
    run: Int,
    phase: String,
): QueryBenchmarkResult =
    runCatching { client.executeQuery(handle.dataStoreId, pql) }.fold(
        onSuccess = {
            QueryBenchmarkResult(
                system = handle.system.name,
                datasetName = handle.dataset.name,
                queryLabel = queryLabel,
                run = run,
                seconds = it.seconds,
                status = "OK",
                responseBytes = it.responseBytes,
                phase = phase,
                logCount = it.counts.logs,
                traceCount = it.counts.traces,
                eventCount = it.counts.events,
            )
        },
        onFailure = {
            QueryBenchmarkResult(
                system = handle.system.name,
                datasetName = handle.dataset.name,
                queryLabel = queryLabel,
                run = run,
                seconds = 0.0,
                status = "ERROR",
                responseBytes = 0,
                phase = phase,
                details = it.message.orEmpty(),
            )
        },
    )


private fun memorySources(
    settings: BenchmarkSettings,
    systems: List<BenchmarkSystem>,
): List<MemorySampler.MemorySource> =
    buildList {
        add(DockerStatsMemorySource(systems.map { it.storage.container }.toSet()))
        if (systems.any { it.name == "local" }) {
            val port = runCatching { java.net.URI(settings.localApi).port }.getOrNull().takeIf { it != null && it > 0 } ?: 8080
            val pid = LocalAppPidResolver.resolve(port)
            if (pid != null) {
                val source = ProcessMemorySource("local-jvm", pid)
                // Probe once up front: resolving a PID is not proof the RSS probe works
                // (it used to be Windows-only and failed silently elsewhere), and a
                // missing local-jvm series understates LOCAL memory in Q3.
                if (source.sample().isEmpty()) {
                    println(
                        "WARNING: local JVM RSS probe returned nothing for PID $pid; " +
                            "local-jvm memory will NOT be sampled and the Q3 memory comparison " +
                            "would understate LOCAL — fix the probe before using this run as thesis data",
                    )
                } else {
                    println("Sampling local JVM RSS for PID $pid (port $port)")
                    add(source)
                }
            } else {
                println("WARNING: could not resolve local application PID on port $port; local-jvm memory will not be sampled")
            }
        }
    }

private fun runCleanup(settings: BenchmarkSettings, outputDirectory: Path) {
    outputDirectory.createDirectories()
    val systems = benchmarkSystems(settings)
    val cleanup = mutableListOf<DataStoreCleanupResult>()
    systems.forEach { system ->
        val client = BenchmarkHttpClient(system.apiBase, settings.processMLogin, settings.processMPassword)
        println("Authenticating ${system.name} at ${system.apiBase}")
        client.authenticateIfSupported()
        val benchmarkStores = runCatching { client.listDataStores().filter { it.name.startsWith(BENCHMARK_DATASTORE_PREFIX) } }
            .getOrElse { error ->
                cleanup += DataStoreCleanupResult(
                    system = system.name,
                    dataStoreName = "",
                    dataStoreId = "",
                    status = "ERROR",
                    details = "List datastores failed: ${error.message.orEmpty()}",
                )
                emptyList()
            }
        benchmarkStores.forEach { dataStore ->
            cleanup += deleteDataStore(system, client, dataStore.name, dataStore.id)
        }
    }
    BenchmarkResultsWriter(outputDirectory).writeCleanupOnly(settings, cleanup)
    println("Benchmark cleanup report written to $outputDirectory")
    val errors = cleanup.count { it.status == "ERROR" }
    if (errors > 0) {
        error("Benchmark cleanup finished with $errors error(s). See $outputDirectory")
    }
}

private fun benchmarkSystems(settings: BenchmarkSettings): List<BenchmarkSystem> =
    listOf(
        BenchmarkSystem(
            name = "local",
            apiBase = settings.localApi,
            storage = StorageProbe(
                container = "processm-neo4j",
                path = "/data",
                sizeCommand = "total=0; for f in \$(find /data/databases /data/transactions -type f 2>/dev/null); do size=\$(stat -c %s \"\$f\" 2>/dev/null || echo 0); total=\$((total + size)); done; echo \$total",
                // Neo4j community exposes no manual checkpoint procedure (a
                // `CALL db.checkpoint()` here failed silently for months) —
                // sizes reflect naturally checkpointed state. Attributable
                // local per-dataset deltas come from the sequential probe in
                // scripts/benchmarks/measure-storage-scaling.py instead.
            ),
        ),
        BenchmarkSystem(
            name = "reference",
            apiBase = settings.referenceApi,
            storage = StorageProbe(
                container = "processm-server",
                path = "/var/lib/postgresql/data",
                // PostgreSQL is checkpointed before measurement so un-flushed page
                // state is not reported as disk size; Neo4j community has no manual
                // checkpoint equivalent (see the local probe comment above).
                flushCommand = "psql -U postgres -c 'CHECKPOINT;'",
            ),
        ),
    ).filter { settings.systemFilter.isEmpty() || it.name in settings.systemFilter }

private fun cleanupCreatedDataStores(
    settings: BenchmarkSettings,
    createdDataStores: List<CreatedDataStoreHandle>,
    clients: Map<BenchmarkSystem, BenchmarkHttpClient>,
): List<DataStoreCleanupResult> {
    if (settings.keepBenchmarkDataStores) {
        return createdDataStores.map {
            DataStoreCleanupResult(
                system = it.system.name,
                dataStoreName = it.name,
                dataStoreId = it.dataStoreId,
                status = "SKIPPED",
                details = "BENCHMARK_KEEP_DATASTORES=true",
            )
        }
    }
    return createdDataStores
        .asReversed()
        .map { handle ->
            val client = clients.getValue(handle.system)
            deleteDataStore(handle.system, client, handle.name, handle.dataStoreId)
        }
}

private fun deleteDataStore(
    system: BenchmarkSystem,
    client: BenchmarkHttpClient,
    dataStoreName: String,
    dataStoreId: String,
): DataStoreCleanupResult {
    println("[${system.name}] Deleting datastore $dataStoreName")
    return runCatching { client.deleteDataStore(dataStoreId) }.fold(
        onSuccess = {
            DataStoreCleanupResult(
                system = system.name,
                dataStoreName = dataStoreName,
                dataStoreId = dataStoreId,
                status = it.status,
                details = if (it.status == "ERROR") "HTTP ${it.statusCode}: ${it.body.take(500)}" else "HTTP ${it.statusCode}",
            )
        },
        onFailure = {
            DataStoreCleanupResult(
                system = system.name,
                dataStoreName = dataStoreName,
                dataStoreId = dataStoreId,
                status = "ERROR",
                details = it.message.orEmpty(),
            )
        },
    )
}

private const val BENCHMARK_DATASTORE_PREFIX = "bench-"

private fun storageResult(
    system: BenchmarkSystem,
    dataset: PreparedDataset,
    before: Long?,
    after: Long?,
): StorageBenchmarkResult {
    val delta = if (before != null && after != null) after - before else null
    val status = when {
        delta == null -> "UNAVAILABLE"
        delta > 0 -> "OK"
        system.name == "local" -> "BELOW_ALLOCATION_GRANULARITY"
        else -> "OK"
    }
    return StorageBenchmarkResult(
        system = system.name,
        datasetName = dataset.name,
        beforeBytes = before,
        afterBytes = after,
        deltaBytes = delta,
        deltaToXesRatio = delta?.toDouble()?.div(dataset.xesBytes.takeIf { it > 0 } ?: 1),
        deltaToGzipRatio = delta?.toDouble()?.div(dataset.xesGzBytes.takeIf { it > 0 } ?: 1),
        status = status,
    )
}
