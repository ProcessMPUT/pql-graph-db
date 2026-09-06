package com.processm.processminterpreter.benchmark

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

// Covers two ten-minute queries, or upload + readiness (15 minutes each) and metadata calls.
internal val MINIMUM_SESSION_VALIDITY: Duration = Duration.ofMinutes(35)

/** Session maintenance precedes a complete pair/window, never a measured request or its replay. */
fun maintainBenchmarkSessions(
    clients: Map<BenchmarkSystem, BenchmarkHttpClient>,
    journal: BenchmarkJournal,
    minimumValidity: Duration = MINIMUM_SESSION_VALIDITY,
) {
    clients.forEach { (system, client) ->
        val renewed = journal.progress.operation("session-check", system = system.name) {
            client.ensureSessionValid(minimumValidity)
        }
        if (renewed) journal.event("session-renewed", mapOf("system" to system.name,
            "minimumValiditySeconds" to minimumValidity.seconds))
    }
}

/**
 * Archives and consumes the proof emitted immediately after `docker compose down -v`.
 * Zero datastore rows alone are insufficient: an old volume with manually deleted
 * stores would otherwise pass the same check. Moving the marker makes it single-use,
 * so every benchmark block requires a separate destructive preparation.
 */
internal fun consumeFreshStackProof(outputDirectory: Path) {
    val marker = Path.of("tmp", "benchmark-stack-ready.json")
    require(Files.isRegularFile(marker)) {
        "Missing single-use fresh-stack proof. Run " +
            "scripts/benchmarks/prepare-benchmark-stack.py --confirm-destroy-volumes immediately before this block."
    }
    Files.move(
        marker,
        outputDirectory.resolve("stack-preparation.json"),
        StandardCopyOption.REPLACE_EXISTING,
    )
}

/**
 * Warm both systems on a separately imported 100-trace, 10-event-per-trace log.
 * Every round is recorded and compared semantically, outside measured blocks.
 * The caller deletes these datastores before importing the measured inputs.
 */
internal fun runGlobalWarmup(
    rounds: Int,
    config: BenchmarkConfig,
    clients: Map<BenchmarkSystem, BenchmarkHttpClient>,
    generator: XesDatasetGenerator,
    generatedDatasetsDirectory: Path,
    createdDataStores: MutableList<CreatedDataStoreHandle>,
    runId: String,
    journal: BenchmarkJournal,
): List<ImportedDatasetHandle> {
    if (rounds <= 0 || clients.isEmpty()) return emptyList()

    val spec = BenchmarkDatasetSpec(
        type = DatasetType.SYNTHETIC,
        name = "warmup-throwaway",
        series = "warmup",
        traces = 100,
        eventsPerTrace = 10,
        attributesPerEvent = 5,
        matchingTracePercent = 50.takeIf {
            config.queries.any { "selectivity-scaling" in it.measurementSeries }
        },
    )
    val dataset = journal.progress.operation("prepare-input", dataset = spec.name) {
        generator.prepare(spec, generatedDatasetsDirectory)
    }
    val handles = mutableListOf<ImportedDatasetHandle>()
    clients.forEach { (system, client) ->
        maintainBenchmarkSessions(clients, journal)
        val storeName = "bench-$runId-${system.name}-warmup"
        val storeId = runCatching {
            journal.progress.operation("create-datastore", dataset = dataset.name, system = system.name) {
                client.createDataStore(storeName)
            }
        }
            .getOrElse { error("Global warm-up datastore creation failed on ${system.name}: ${it.message}") }
        createdDataStores += CreatedDataStoreHandle(system, storeName, storeId)
        runCatching {
            journal.progress.operation("global-warmup-import", dataset = dataset.name, system = system.name) {
                client.uploadLogAndWait(storeId, dataset.file)
            }
        }
            .getOrElse { error("Global warm-up import failed on ${system.name}: ${it.message}") }
        handles += ImportedDatasetHandle(system, dataset, storeId)
    }
    require(handles.size == clients.size) {
        "Global warm-up imported ${handles.size}/${clients.size} system datasets"
    }
    runWarmupQueries(
        label = "Global warm-up",
        rounds = rounds,
        config = config,
        clients = clients,
        handles = handles,
        journal = journal,
    )
    return handles
}

private fun runWarmupQueries(
    label: String,
    rounds: Int,
    config: BenchmarkConfig,
    clients: Map<BenchmarkSystem, BenchmarkHttpClient>,
    handles: List<ImportedDatasetHandle>,
    journal: BenchmarkJournal,
) {
    if (rounds <= 0 || handles.isEmpty()) return
    println("$label: $rounds round(s) of ${config.queries.size} queries (recorded as global-warmup, excluded from estimates)")
    repeat(rounds) { round ->
        config.queries.forEachIndexed { queryIndex, query ->
            maintainBenchmarkSessions(clients, journal)
            val results = mutableMapOf<String, TimedQueryResult>()
            balancedOrder(handles, round * config.queries.size + queryIndex).forEach { handle ->
                results[handle.system.name] = runCatching {
                    journal.progress.operation("global-warmup", dataset = handle.dataset.name, query = query.label,
                        system = handle.system.name, repetition = round + 1, total = rounds) {
                        clients.getValue(handle.system).executeQuery(handle.dataStoreId, query.query)
                    }
                }.getOrElse {
                    if (it is BenchmarkRequestException && it.statusCode in 400..499 && it.statusCode != 401 && it.statusCode != 403)
                        throw StudyDataMismatch("$label query ${query.label} rejected on ${handle.system.name}: ${it.message}")
                    error("$label query ${query.label} failed on ${handle.system.name}: ${it.message}")
                }
            }
            journal.event("global-warmup", mapOf("round" to round + 1, "query" to query.label,
                "samples" to results.mapValues { (_, r) -> mapOf("seconds" to r.seconds, "startedAt" to r.startedAt,
                    "startedNanos" to r.startedNanos, "finishedNanos" to r.finishedNanos, "httpStatus" to r.statusCode) }))
            val local = results["local"]
            val reference = results["reference"]
            if (local != null && reference != null) {
                if (local.counts != reference.counts) throw StudyDataMismatch(
                    "$label query ${query.label} count mismatch: local=${local.counts}, reference=${reference.counts}")
                val semantic = XesJsonSemanticParity.compare(local.body, reference.body)
                if (!semantic.matches) throw StudyDataMismatch("$label query ${query.label} semantic mismatch: ${semantic.details}")
            }
        }
    }
    println("$label complete")
}

internal fun recordedQuerySample(
    client: BenchmarkHttpClient,
    handle: ImportedDatasetHandle,
    queryLabel: String,
    pql: String,
    run: Int,
    phase: String,
    progress: BenchmarkProgress,
    total: Int,
): Pair<QueryBenchmarkResult, String?> {
    val attemptedAt = java.time.Instant.now().toString()
    val attemptedNanos = System.nanoTime()
    return runCatching {
        progress.operation(phase, dataset = handle.dataset.name, query = queryLabel,
            system = handle.system.name, repetition = run, total = total) {
            client.executeQuery(handle.dataStoreId, pql)
        }
    }.fold(
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
                    startedAt = it.startedAt, startedNanos = it.startedNanos,
                    finishedNanos = it.finishedNanos, httpStatus = it.statusCode,
                ) to it.body
        },
        onFailure = {
            val http = it as? BenchmarkRequestException
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
                    startedAt = http?.startedAt ?: attemptedAt, startedNanos = http?.startedNanos ?: attemptedNanos,
                    finishedNanos = http?.finishedNanos ?: System.nanoTime(), httpStatus = http?.statusCode,
                ) to http?.body
        },
    )
}

internal fun <T> balancedOrder(
    values: List<T>,
    round: Int,
): List<T> {
    if (values.size <= 1) return values
    val start = Math.floorMod(round, values.size)
    return values.indices.map { values[(start + it) % values.size] }
}


internal fun benchmarkSystems(settings: BenchmarkSettings): List<BenchmarkSystem> = listOf(
    BenchmarkSystem("local", settings.localApi, setOf("processm-interpreter", "processm-neo4j")),
    BenchmarkSystem("reference", settings.referenceApi, setOf("processm-server")),
)

internal fun cleanupCreatedDataStores(
    createdDataStores: List<CreatedDataStoreHandle>,
    clients: Map<BenchmarkSystem, BenchmarkHttpClient>,
    progress: BenchmarkProgress,
): List<DataStoreCleanupResult> {
    return createdDataStores
        .asReversed()
        .map { handle ->
            val client = clients.getValue(handle.system)
            deleteDataStore(handle.system, client, handle.name, handle.dataStoreId, progress)
        }
}

internal fun deleteDataStore(
    system: BenchmarkSystem,
    client: BenchmarkHttpClient,
    dataStoreName: String,
    dataStoreId: String,
    progress: BenchmarkProgress,
): DataStoreCleanupResult {
    println("[${system.name}] Deleting datastore $dataStoreName")
    return runCatching {
        progress.operation("session-check", system = system.name) {
            client.ensureSessionValid(MINIMUM_SESSION_VALIDITY)
        }
        progress.operation("cleanup", dataset = dataStoreName, system = system.name) {
            client.deleteDataStore(dataStoreId)
        }
    }.fold(
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
