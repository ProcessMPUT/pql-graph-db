package com.processm.processminterpreter.benchmark

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.relativeToOrSelf

fun main(args: Array<String>) {
    val command = args.firstOrNull()?.lowercase()

    // `report <runDir>` re-derives the thesis artifacts from an existing run's CSVs
    // without touching the containers, so an improved analysis can be applied to runs
    // that are already collected (METODOLOGIA §6).
    if (command == "report") {
        val runDirectory = args.getOrNull(1)?.let { Path.of(it) }
            ?: error("Usage: report <benchmark-run-directory>")
        RunReplay.rebuildReport(runDirectory)
        return
    }

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

    val orderedSpecs = settings.datasetOrder.apply(selectedSpecs, settings.datasetOrderSeed)
    println(
        "Dataset order: ${settings.datasetOrder}" +
            (if (settings.datasetOrder == DatasetOrder.RANDOM) " (seed ${settings.datasetOrderSeed})" else "") +
            " — ${orderedSpecs.joinToString(", ") { it.name }}",
    )
    val datasets = orderedSpecs.map { spec ->
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
    clients.forEach { (system, client) ->
        val existing = client.listDataStores()
        require(existing.isEmpty()) {
            "${system.name} exposes ${existing.size} pre-existing datastore(s): " +
                existing.joinToString { it.name } + ". Thesis runs require a clean stack; run " +
                "scripts/benchmarks/prepare-benchmark-stack.py --confirm-destroy-volumes first."
        }
    }
    consumeFreshStackProof(outputDirectory)

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
        // Global warm-up (METODOLOGIA §5 pkt 3a) BEFORE anything is recorded — including
        // before the idle baseline, so the baseline describes a warmed process rather
        // than one still loading classes. Pays the JIT/class-loading/page-cache cost
        // that the replicate datasets proved the per-query warm-ups cannot cover, and
        // absorbs the databases' first-import page pre-allocation so it is not billed
        // to whichever dataset happens to come first.
        runGlobalWarmup(settings, config, clients, generator, generatedDatasetsDirectory, createdDataStores, runId)

        memorySampler.start()
        println("Sampling idle memory baseline for ${settings.profile.idleBaselineSeconds}s")
        memorySampler.sampleBlocking(MEMORY_PHASE_IDLE, settings.profile.idleBaselineSeconds)

        datasets.forEachIndexed { datasetIndex, dataset ->
            val importRound = counterbalancedImportRound(datasetIndex, datasets.size, settings.datasetOrder)
            balancedOrder(systems, importRound).forEach { system ->
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
        var queryPairIndex = 0
        datasets.forEach { dataset ->
            val handles = importedHandles
                .filter { it.dataset.name == dataset.name }
                .sortedBy { handle -> systems.indexOf(handle.system) }
            if (handles.isEmpty()) return@forEach
            measuredQueries.forEach { query ->
                println("Query ${query.label} on ${dataset.name} [${handles.joinToString(",") { it.system.name }}]")
                val plan = buildQueryExecutionPlan(
                    systemCount = handles.size,
                    warmups = settings.profile.warmups,
                    repetitions = settings.profile.repetitions,
                    initialSystemIndex = queryPairIndex % handles.size,
                )
                queryPairIndex++
                val pairSamples = mutableListOf<QueryBenchmarkResult>()
                val lastWarmBodies = mutableMapOf<String, String>()
                var pairFailure: QueryBenchmarkResult? = null
                plan.forEach planStep@{ step ->
                    if (pairFailure != null) return@planStep
                    val handle = handles[step.systemIndex]
                    val client = clients.getValue(handle.system)
                    when (step.kind) {
                        QueryStepKind.WARMUP -> client.executeQuery(handle.dataStoreId, query.query)
                        QueryStepKind.COLD, QueryStepKind.MEASURED -> {
                            val phase = if (step.kind == QueryStepKind.COLD) QUERY_PHASE_COLD else QUERY_PHASE_WARM
                            val recorded = recordedQuerySample(client, handle, query.label, query.query, step.run, phase)
                            pairSamples += recorded.first
                            if (phase == QUERY_PHASE_WARM && recorded.second != null) {
                                lastWarmBodies[handle.system.name] = recorded.second!!
                            }
                            if (recorded.first.status == "ERROR") pairFailure = recorded.first
                        }
                    }
                }
                queries += applyResponseParity(pairSamples, lastWarmBodies)
                pairFailure?.let {
                    error("Query ${query.label} failed on ${dataset.name}/${it.system}: ${it.details}")
                }
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
        environmentDetails = EnvironmentProbe.collect(memoryContainers(settings, systems)),
        querySpecs = config.queries,
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
        println("WARNING: $mismatches query sample(s) invalidated by response MISMATCH. See query-results.csv")
    }
    val strictErrors = imports.count { it.status != "OK" } +
        queries.count { it.status == "ERROR" } +
        roundtrips.count { it.status == "ERROR" }
    if (strictErrors > 0) {
        error("Benchmark finished with $strictErrors infrastructure/runtime error(s). See $outputDirectory")
    }
}

/**
 * Archives and consumes the proof emitted immediately after `docker compose down -v`.
 * Zero datastore rows alone are insufficient: an old volume with manually deleted
 * stores would otherwise pass the same check. Moving the marker makes it single-use,
 * so every benchmark block requires a separate destructive preparation.
 */
private fun consumeFreshStackProof(outputDirectory: Path) {
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
 * Imports one throw-away dataset into both systems and executes the whole query set
 * against it repeatedly, recording nothing.
 *
 * Why this exists: `trace-100`, `event-10` and `attr-5` are the same 100×10×5
 * experiment under three names, and in the pre-fix runs their medians differed by up
 * to ×2,4 on LOCAL, monotonically decreasing with position in the sequence (import:
 * ×10,4). Three warm-ups per query cannot fix that — the warm-up horizon is the run,
 * not the query. Failing to pay it here makes whichever dataset is measured first
 * look slow, and the effect is larger for LOCAL (interpreter JVM + Neo4j JVM) than
 * for the single-JVM REFERENCE.
 *
 * The dataset deliberately has the same shape as the replicate group, so the state it
 * warms is the state the first measured dataset will need.
 */
private fun runGlobalWarmup(
    settings: BenchmarkSettings,
    config: BenchmarkConfig,
    clients: Map<BenchmarkSystem, BenchmarkHttpClient>,
    generator: XesDatasetGenerator,
    generatedDatasetsDirectory: Path,
    createdDataStores: MutableList<CreatedDataStoreHandle>,
    runId: String,
) {
    val rounds = settings.globalWarmupRounds
    if (rounds <= 0 || clients.isEmpty()) return

    val spec = BenchmarkDatasetSpec(
        type = DatasetType.SYNTHETIC,
        name = "warmup-throwaway",
        series = "warmup",
        traces = 100,
        eventsPerTrace = 10,
        attributesPerEvent = 5,
    )
    val dataset = generator.prepare(spec, generatedDatasetsDirectory)
    println("Global warm-up: $rounds round(s) of ${config.queries.size} queries on ${spec.name} (not recorded)")

    val handles = mutableListOf<ImportedDatasetHandle>()
    clients.forEach { (system, client) ->
        val storeName = "bench-$runId-${system.name}-warmup"
        val storeId = runCatching { client.createDataStore(storeName) }
            .getOrElse { error("Global warm-up datastore creation failed on ${system.name}: ${it.message}") }
        createdDataStores += CreatedDataStoreHandle(system, storeName, storeId)
        runCatching { client.uploadLogAndWait(storeId, dataset.file) }
            .getOrElse { error("Global warm-up import failed on ${system.name}: ${it.message}") }
        handles += ImportedDatasetHandle(system, dataset, storeId)
    }
    require(handles.size == clients.size) {
        "Global warm-up imported ${handles.size}/${clients.size} system datasets"
    }
    repeat(rounds) { round ->
        config.queries.forEachIndexed { queryIndex, query ->
            val results = mutableMapOf<String, TimedQueryResult>()
            balancedOrder(handles, round * config.queries.size + queryIndex).forEach { handle ->
                results[handle.system.name] = runCatching {
                    clients.getValue(handle.system).executeQuery(handle.dataStoreId, query.query)
                }.getOrElse {
                    error("Global warm-up query ${query.label} failed on ${handle.system.name}: ${it.message}")
                }
            }
            val local = results["local"]
            val reference = results["reference"]
            if (local != null && reference != null) {
                require(local.counts == reference.counts) {
                    "Global warm-up query ${query.label} count mismatch: local=${local.counts}, reference=${reference.counts}"
                }
                val semantic = XesJsonSemanticParity.compare(local.body, reference.body)
                require(semantic.matches) {
                    "Global warm-up query ${query.label} semantic mismatch: ${semantic.details}"
                }
            }
        }
    }
    println("Global warm-up complete")
}

private fun recordedQuerySample(
    client: BenchmarkHttpClient,
    handle: ImportedDatasetHandle,
    queryLabel: String,
    pql: String,
    run: Int,
    phase: String,
): Pair<QueryBenchmarkResult, String?> =
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
                ) to it.body
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
                ) to null
        },
    )

private fun <T> balancedOrder(
    values: List<T>,
    round: Int,
): List<T> {
    if (values.size <= 1) return values
    val start = Math.floorMod(round, values.size)
    return values.indices.map { values[(start + it) % values.size] }
}


/**
 * Containers whose memory is sampled with `docker stats`: the databases plus, when
 * the LOCAL interpreter runs in a container, the interpreter itself. Measuring both
 * applications through the same probe is what makes the Q3 comparison symmetric.
 */
internal fun memoryContainers(
    settings: BenchmarkSettings,
    systems: List<BenchmarkSystem>,
): Set<String> =
    systems.map { it.storage.container }.toSet() +
        setOfNotNull(settings.localAppContainer.takeIf { it.isNotBlank() && dockerContainerExists(it) })

private fun dockerContainerExists(name: String): Boolean =
    runCatching {
        val process = ProcessBuilder("docker", "inspect", name)
            .redirectErrorStream(true)
            .start()
        process.inputStream.readAllBytes()
        process.waitFor() == 0
    }.getOrDefault(false)

private fun memorySources(
    settings: BenchmarkSettings,
    systems: List<BenchmarkSystem>,
): List<MemorySampler.MemorySource> =
    buildList {
        val containers = memoryContainers(settings, systems)
        add(DockerStatsMemorySource(containers))

        val appContainer = settings.localAppContainer
        if (appContainer.isNotBlank() && appContainer in containers) {
            println("Sampling LOCAL interpreter memory from container $appContainer (docker stats, as for REFERENCE)")
            return@buildList
        }
        if (systems.none { it.name == "local" }) return@buildList

        // Fallback: the interpreter runs on the host (development setup). Its memory
        // then comes from a different probe than REFERENCE's, so the Q3 comparison is
        // not measured like-for-like — see METODOLOGIA §7.
        println(
            "WARNING: container '$appContainer' not found; falling back to host RSS sampling of the " +
                "LOCAL interpreter. REFERENCE is measured with docker stats, so the Q3 memory " +
                "comparison will NOT be like-for-like — start the app with `docker compose up -d app` " +
                "before collecting thesis data",
        )
        val port = runCatching { java.net.URI(settings.localApi).port }.getOrNull().takeIf { it != null && it > 0 } ?: 8080
        val pid = LocalAppPidResolver.resolve(port)
        if (pid == null) {
            println("WARNING: could not resolve local application PID on port $port; local-jvm memory will not be sampled")
            return@buildList
        }
        val source = ProcessMemorySource("local-jvm", pid)
        // Resolving a PID is not proof the RSS probe works (it used to be Windows-only
        // and failed silently elsewhere), and a missing series understates LOCAL in Q3.
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
    // A per-dataset disk delta is only a measurement when it is strictly positive.
    // The three failure modes are physically different and must not share a status:
    // labelling a *negative* delta "OK" is what let charts draw a line through
    // -19,7 MB (REFERENCE/trace-2000) while the table beside it printed
    // "poniżej granulacji" for the same cell.
    val status = when {
        delta == null -> STORAGE_STATUS_UNAVAILABLE
        delta > 0L -> STORAGE_STATUS_OK
        // The database shrank across the import: autovacuum, page reuse or WAL
        // recycling moved more bytes than the import added. Nothing about the
        // dataset can be read off such a sample.
        delta < 0L -> STORAGE_STATUS_CONTAMINATED
        else -> STORAGE_STATUS_BELOW_GRANULARITY
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
