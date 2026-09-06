package com.processm.processminterpreter.benchmark

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.fileSize
import kotlin.random.Random

/** One preparation: raw measurements and evidence only. Statistical analysis runs offline. */
internal class StudyCollector(private val job: StudyJob, private val out: Path, private val cache: Path) {
    private val catalog = BenchmarkConfig.catalog().also(job::validate)
    private val settings = BenchmarkSettings.fromEnvironment()
    private val config = catalog.copy(
        datasets = catalog.datasets.filter { it.name in job.datasetNames }.shuffled(Random(job.seed)),
        queries = catalog.queries.filter { it.label in job.queryLabels }.shuffled(Random(job.seed + 1)),
    )

    init { Files.createDirectory(out) }

    private val journal = BenchmarkJournal(out, job.id, job.protocolVersion)
    private val generator = XesDatasetGenerator()
    private val generated = out.resolve("generated-datasets")
    private var datasets = emptyList<PreparedDataset>()
    private val systems = benchmarkSystems(settings)
    private val containers = systems.flatMap { it.containers }.toSet()
    private val writer = BenchmarkResultsWriter(out)
    private var clients = emptyMap<BenchmarkSystem, BenchmarkHttpClient>()
    private val stores = mutableListOf<CreatedDataStoreHandle>()
    private val imports = mutableListOf<ImportBenchmarkResult>()
    private val queries = mutableListOf<QueryBenchmarkResult>()
    private val roundtrips = mutableListOf<RoundtripBenchmarkResult>()
    private val io = mutableListOf<ContainerIoBenchmarkResult>()
    private val cleanup = mutableListOf<DataStoreCleanupResult>()
    private val sampler = MemorySampler(if (job.collectsResources) listOf(DockerStatsMemorySource(containers)) else emptyList())
    private val ioMeter = ContainerIoMeter(systems.flatMap { system -> system.containers.map { it to system.name } }.toMap())

    fun run() {
        var failure: Throwable? = null
        try {
            datasets = prepareInputs()
            clients = connect()
            checkpoint()
            if (job.kind == "compatibility") {
                collectStudyCompatibility(settings, clients, generator, generated, stores, out, journal)
            } else {
                val warm = runGlobalWarmup(job.globalWarmupRounds, config, clients, generator, generated, stores, job.id, journal)
                clean(storesFor(warm))
                if (job.collectsResources) sampler.start()
                datasets.forEachIndexed { datasetIndex, dataset ->
                    val handles = collectImports(dataset, datasetIndex)
                    // Keep the pilot's early XES check; coverage checks after latency.
                    if (job.kind == "pilot") checkRoundtrip(dataset, handles)
                    config.queries.filter { it.isMeasuredFor(dataset.name, dataset.series) }.forEachIndexed { queryIndex, query ->
                        collectQueryBlock(dataset, datasetIndex, query, queryIndex, handles)
                        if (job.collectsResourcesFor(dataset.name, query.label)) collectResources(dataset, query, queryIndex, handles)
                    }
                    if (job.checksRoundtrip && job.kind != "pilot") checkRoundtrip(dataset, handles)
                    clean(storesFor(handles))
                }
            }
        } catch (error: Throwable) {
            failure = error
            journal.event("failure", mapOf("type" to error.javaClass.simpleName, "message" to error.message,
                "retryable" to (error !is StudyDataMismatch && (error !is BenchmarkRequestException ||
                    error.statusCode >= 500 || error.statusCode == 401 || error.statusCode == 403))))
        } finally {
            try {
                sampler.stop()
                cleanup += cleanupCreatedDataStores(stores, clients, journal.progress)
                checkpoint()
                val after = journal.progress.operation("environment-after") { EnvironmentProbe.collect(containers) }
                journal.snapshot("environment-after", after)
                journal.snapshot("cleanup", cleanup)
                require(cleanup.all { it.status == "DELETED" } && EnvironmentProbe.runtimeIssues(after, containers).isEmpty()) {
                    "Cleanup or final runtime validation failed"
                }
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            } finally {
                journal.event("run-ended", mapOf("status" to if (failure == null) "COMPLETED" else "FAILED", "at" to Instant.now().toString()))
                journal.progress.finish(failure)
            }
        }
        failure?.let { throw it }
    }

    private fun prepareInputs(): List<PreparedDataset> {
        journal.snapshot("job", job)
        for (name in listOf("benchmark-queries.json", "benchmark-datasets.json")) {
            Files.write(out.resolve(name), requireNotNull(StudyJob::class.java.classLoader.getResourceAsStream(name)).use { it.readBytes() })
        }
        val process = ProcessBuilder("git", "rev-parse", "HEAD").start()
        val revision = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0 && revision.isNotEmpty())
        return config.datasets.map {
            journal.progress.operation("prepare-input", dataset = it.name) {
                StudyDatasetCache.prepare(it, cache, generated, job.planSha256 + revision, includePlainXes = job.id == "coverage-size")
            }
        }.also { data ->
            require(data.all { it.file.fileSize() < 5L * 1024 * 1024 }) { "Stock REFERENCE input limit exceeded" }
        }
    }

    private fun connect(): Map<BenchmarkSystem, BenchmarkHttpClient> {
        consumeFreshStackProof(out)
        require(containers == setOf("processm-interpreter", "processm-neo4j", "processm-server"))
        val before = journal.progress.operation("environment-before") { EnvironmentProbe.collect(containers) }
        journal.snapshot("environment-before", before)
        require(EnvironmentProbe.runtimeIssues(before, containers).isEmpty()) { "Unhealthy starting environment" }
        require((before["source"] as? Map<*, *>)?.get("gitDirty") == false) { "Commit measurement code before collection" }
        writer.writeEnvironment(job, settings, before)
        return systems.associateWith { system ->
            BenchmarkHttpClient(system.apiBase, settings.processMLogin, settings.processMPassword).also {
                journal.progress.operation("connect", system = system.name) {
                    it.authenticateIfSupported(allowUnsupported = system.name == "local")
                    require(it.listDataStores().isEmpty()) { "${system.name} contains pre-existing datastores" }
                }
            }
        }
    }

    private fun collectImports(dataset: PreparedDataset, datasetIndex: Int): List<ImportedDatasetHandle> {
        var handles = emptyList<ImportedDatasetHandle>()
        repeat(job.importPairs) { runIndex ->
            val currentStores = mutableListOf<CreatedDataStoreHandle>()
            val currentHandles = mutableListOf<ImportedDatasetHandle>()
            for (system in balancedOrder(systems, datasetIndex + runIndex + (job.seed % 2).toInt())) {
                maintainBenchmarkSessions(clients, journal)
                val client = clients.getValue(system)
                val name = "bench-${job.id}-${dataset.name}-${system.name}-$runIndex"
                val id = journal.progress.operation("create-datastore", dataset = dataset.name, system = system.name) {
                    client.createDataStore(name)
                }
                val store = CreatedDataStoreHandle(system, name, id)
                stores += store
                currentStores += store
                val observations = mutableListOf<Map<String, Any?>>()
                journal.event("import-started", mapOf("dataset" to dataset.name, "system" to system.name, "run" to runIndex + 1))
                if (job.kind == "resources" || (job.kind == "pilot" && job.resourceProbe?.dataset == dataset.name))
                    sampler.setPhase("import-resource", dataset.name, "import", system.name)
                try {
                    val r = journal.progress.operation("import", dataset = dataset.name, system = system.name,
                        repetition = runIndex + 1, total = job.importPairs) {
                        client.uploadLogAndWait(id, dataset.file, observe = observations::add)
                    }
                    imports += ImportBenchmarkResult(system.name, dataset.name, runIndex + 1, r.seconds, "OK", id,
                        r.logCount, startedAt = r.startedAt, startedNanos = r.startedNanos,
                        finishedNanos = r.finishedNanos, httpStatus = r.statusCode)
                    currentHandles += ImportedDatasetHandle(system, dataset, id)
                } finally {
                    sampler.pauseAndAwaitQuiescence()
                    journal.event("import-readiness", mapOf("dataset" to dataset.name, "system" to system.name,
                        "run" to runIndex + 1, "observations" to observations))
                    checkpoint()
                }
            }
            if (runIndex == job.importPairs - 1) handles = currentHandles.sortedBy { systems.indexOf(it.system) }
            else clean(currentStores)
        }
        return handles
    }

    private fun checkRoundtrip(dataset: PreparedDataset, handles: List<ImportedDatasetHandle>) {
        val local = handles.single { it.system.name == "local" }
        val detail = out.resolve("roundtrip-details/${dataset.name}.txt")
        maintainBenchmarkSessions(clients, journal)
        val exported = journal.progress.operation("export-xes", dataset = dataset.name, system = local.system.name) {
            clients.getValue(local.system).exportQueryAsXes(local.dataStoreId)
        }
        val comparison = journal.progress.operation("roundtrip-check", dataset = dataset.name, system = local.system.name) {
            CanonicalXesComparator.compareFiles(dataset.file, exported, detail)
        }
        roundtrips += RoundtripBenchmarkResult(dataset.name, if (comparison.matches) "MATCH" else "MISMATCH",
            comparison.differences.size, out.relativize(detail).toString())
        checkpoint()
        if (!comparison.matches) throw StudyDataMismatch("XES roundtrip mismatch: ${dataset.name}")
    }

    private fun collectQueryBlock(
        dataset: PreparedDataset, datasetIndex: Int, query: BenchmarkQuerySpec, queryIndex: Int,
        handles: List<ImportedDatasetHandle>,
    ) {
        println("${job.id}: ${dataset.name}/${query.label}")
        val steps = buildQueryExecutionPlan(job.warmups, if (job.collectsLatency) job.pairs else 0,
            (datasetIndex + queryIndex + (job.seed % 2).toInt()) % 2)
        val block = mutableListOf<QueryBenchmarkResult>()
        val bodies = mutableMapOf<Pair<Int, String>, String>()
        journal.event("query-block-started", mapOf("dataset" to dataset.name, "query" to query.label, "steps" to steps))
        try {
            for ((index, step) in steps.withIndex()) {
                if (index % 2 == 0) maintainBenchmarkSessions(clients, journal)
                val handle = handles[step.systemIndex]
                val (record, body) = recordedQuerySample(clients.getValue(handle.system), handle, query.label,
                    query.query, if (step.kind == QueryStepKind.WARMUP) index / 2 + 1 else step.run,
                    if (step.kind == QueryStepKind.WARMUP) "warmup" else QUERY_PHASE_WARM,
                    journal.progress, if (step.kind == QueryStepKind.WARMUP) job.warmups else job.pairs)
                val sample = record.copy(executionIndex = index + 1)
                block += sample
                if (step.kind == QueryStepKind.MEASURED && body != null) bodies[step.run to handle.system.name] = body
                check(sample.httpStatus != 401 && sample.httpStatus != 403) { "Authentication failed: ${sample.details}" }
                if (sample.httpStatus in 400..499) throw StudyDataMismatch("Rejected planned PQL: ${sample.details}")
                require(sample.status == "OK") { "HTTP failure: ${sample.details}" }
                if (sample.logCount < query.minimumResponseLogs || sample.eventCount < query.minimumResponseEvents ||
                    query.maximumResponseEvents?.let { sample.eventCount > it } == true)
                    throw StudyDataMismatch("Response violates declared counts: ${dataset.name}/${query.label}")
            }
        } finally {
            // Only the small progress cursor is written between requests. Responses, comparison and CSV wait for the block.
            queries += block.filter { it.phase == "warmup" }
            val checked = journal.progress.operation("response-parity", dataset = dataset.name, query = query.label) {
                applyEveryResponseParity(block.filter { it.phase == QUERY_PHASE_WARM }, bodies).map { sample ->
                    val body = bodies[sample.run to sample.system]
                    val problem = body?.let { query.expectedResponses[dataset.name]?.mismatch(it) }
                    sample.copy(
                        status = if (sample.status == "OK" && problem != null) "CONTROL_MISMATCH" else sample.status,
                        details = listOfNotNull(sample.details.takeIf(String::isNotEmpty), problem).joinToString("; "),
                        responsePath = body?.let(journal::response).orEmpty(),
                    )
                }
            }
            queries += checked
            checkpoint()
        }
        if (queries.filter { it.datasetName == dataset.name && it.queryLabel == query.label }.any { it.status != "OK" })
            throw StudyDataMismatch("Semantic mismatch: ${dataset.name}/${query.label}")
        journal.event("query-block-completed", mapOf("dataset" to dataset.name, "query" to query.label))
    }

    private fun collectResources(
        dataset: PreparedDataset, query: BenchmarkQuerySpec, queryIndex: Int, handles: List<ImportedDatasetHandle>,
    ) {
        for (handle in balancedOrder(handles, queryIndex + (job.seed % 2).toInt())) {
            maintainBenchmarkSessions(clients, journal,
                MINIMUM_SESSION_VALIDITY.plusSeconds(job.resourceWindowSeconds.toLong()))
            val beforeIo = ioMeter.snapshot()
            val started = System.nanoTime()
            var completed = 0
            sampler.setPhase("queries", dataset.name, query.label, handle.system.name)
            try {
                do {
                    val r = journal.progress.operation("resource-query", dataset = dataset.name, query = query.label,
                        system = handle.system.name, repetition = completed + 1) {
                        clients.getValue(handle.system).executeQuery(handle.dataStoreId, query.query)
                    }
                    if (r.counts.logs < query.minimumResponseLogs || r.counts.events < query.minimumResponseEvents ||
                        query.maximumResponseEvents?.let { r.counts.events > it } == true)
                        throw StudyDataMismatch("Resource response violates declared counts")
                    query.expectedResponses[dataset.name]?.let { expected ->
                        if (r.counts != XesJsonCounts(expected.logs, expected.traces, expected.events))
                            throw StudyDataMismatch("Resource response violates expected hierarchy cardinality")
                    }
                    completed++
                } while (System.nanoTime() - started < job.resourceWindowSeconds * 1_000_000_000L)
            } finally {
                val finished = System.nanoTime()
                sampler.pauseAndAwaitQuiescence()
                io += ioMeter.deltas("query", dataset.name, query.label, 0, beforeIo, ioMeter.snapshot())
                    .filter { it.system == handle.system.name }.map { it.copy(completedOperations = completed,
                        windowStartedNanos = started, windowFinishedNanos = finished) }
                val count = completeMemorySampleCount(sampler.samples(), dataset.name, query.label,
                    handle.system.name, handle.system.containers)
                journal.event("resource-window-completed", mapOf("dataset" to dataset.name, "query" to query.label,
                    "system" to handle.system.name, "samples" to count, "completedRequests" to completed,
                    "startedNanos" to started, "finishedNanos" to finished,
                    "status" to if (count >= job.resourceMinimumSamples) "OK" else "INSUFFICIENT_COVERAGE"))
                checkpoint()
                require(count >= job.resourceMinimumSamples) { "Insufficient memory coverage: ${dataset.name}/${query.label}" }
            }
        }
    }

    private fun storesFor(handles: List<ImportedDatasetHandle>): List<CreatedDataStoreHandle> =
        stores.filter { store -> handles.any { it.system == store.system && it.dataStoreId == store.dataStoreId } }

    private fun clean(handles: List<CreatedDataStoreHandle>) {
        val result = cleanupCreatedDataStores(handles, clients, journal.progress)
        cleanup += result
        stores.removeAll(handles.toSet())
        require(result.all { it.status == "DELETED" }) { "Datastore cleanup failed" }
    }

    private fun checkpoint() = writer.checkpoint(datasets, config.queries, imports, queries, roundtrips, sampler.samples(), io)
}
