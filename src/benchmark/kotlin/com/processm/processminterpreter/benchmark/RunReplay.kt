package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Minimal RFC 4180 reader for the artifacts this project writes ([CsvWriter]).
 * Handles quoted fields and doubled quotes; enough for round-tripping our own CSVs.
 */
object CsvReader {
    fun read(path: Path): List<Map<String, String>> {
        if (!path.isRegularFile()) return emptyList()
        val rows = parse(path.readText())
        if (rows.isEmpty()) return emptyList()
        val headers = rows.first()
        return rows.drop(1)
            .filter { it.any { cell -> cell.isNotBlank() } }
            .map { row -> headers.indices.associate { headers[it] to row.getOrElse(it) { "" } } }
    }

    private fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < text.length) {
            val c = text[index]
            when {
                inQuotes && c == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> {
                    row.add(cell.toString())
                    cell.clear()
                }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (cell.isNotEmpty() || row.isNotEmpty()) {
                        row.add(cell.toString())
                        cell.clear()
                        rows.add(row)
                        row = mutableListOf()
                    }
                    if (c == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                }
                else -> cell.append(c)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows
    }
}

/**
 * Rebuilds `thesis-report.md` and `thesis-tables.tex` for a run that already exists
 * on disk, from its raw CSV artifacts.
 *
 * Why this exists: the report's analysis (effect sizes, intervals, tests, fitted
 * models, verdicts) evolves faster than the measurements do. Without a replay path,
 * improving the analysis would mean re-running hours of benchmark against live
 * containers, and every past run would stay frozen at the analysis it shipped with.
 *
 * The replay reads only *measurements* (`query-results.csv` keeps every individual
 * sample) and re-derives every *interpretation*. In particular the storage status is
 * recomputed from the raw byte deltas, so a run recorded before negative deltas were
 * given their own status is re-analysed correctly rather than inheriting the old
 * `OK`.
 */
object RunReplay {
    private val mapper = jacksonObjectMapper()

    fun rebuildReport(runDirectory: Path) {
        val datasets = readDatasets(runDirectory)
        require(datasets.isNotEmpty()) { "No datasets.csv rows in $runDirectory — cannot rebuild the report" }
        val queries = readQueries(runDirectory)
        val environment = readEnvironment(runDirectory)
        val querySpecs = readQuerySpecs(runDirectory).ifEmpty { inferQuerySpecs(queries) }

        // Also (re)write queries.csv: the chart generator selects scaling figures
        // from the explicit query-series contract, so a replayed older run must
        // carry the same metadata the report just used.
        CsvWriter.write(
            runDirectory.resolve("queries.csv"),
            listOf("queryLabel", "workload", "scalingSeries", "clause", "pql"),
            querySpecs.map {
                listOf(it.label, it.workload, it.scalingSeries.sorted().joinToString(";"), it.clause, it.query)
            },
        )

        ThesisReportWriter(runDirectory).write(
            runId = runDirectory.fileName.toString(),
            settings = replaySettings(environment),
            datasets = datasets,
            imports = readImports(runDirectory),
            queries = queries,
            storage = readStorage(runDirectory),
            roundtrips = readRoundtrips(runDirectory),
            memorySummaries = readMemorySummaries(runDirectory),
            querySpecs = querySpecs,
        )
        println("Rebuilt thesis-report.md and thesis-tables.tex in $runDirectory")
    }

    private fun readEnvironment(runDirectory: Path): Map<String, Any?> {
        val file = runDirectory.resolve("environment.json")
        if (!file.isRegularFile()) return emptyMap()
        return runCatching {
            mapper.readValue(file.readText(), object : TypeReference<Map<String, Any?>>() {})
        }
            .getOrDefault(emptyMap())
    }

    /**
     * Settings are only used for the environment table and the profile-derived
     * constants shown in it; the numbers themselves all come from the CSVs.
     */
    private fun replaySettings(environment: Map<String, Any?>): BenchmarkSettings {
        val profile = runCatching {
            BenchmarkProfile.valueOf((environment["profile"] as? String ?: "full").uppercase())
        }.getOrDefault(BenchmarkProfile.FULL)
        return BenchmarkSettings(
            profile = profile,
            localApi = environment["localApi"] as? String ?: "",
            referenceApi = environment["referenceApi"] as? String ?: "",
            processMLogin = "",
            processMPassword = "",
            outputRoot = Path("."),
            datasetFilter = emptySet(),
            systemFilter = emptySet(),
            keepBenchmarkDataStores = false,
            localAppContainer = "",
            datasetOrder = runCatching {
                DatasetOrder.parse(environment["datasetOrder"] as? String ?: DatasetOrder.DECLARED.name)
            }.getOrDefault(DatasetOrder.DECLARED),
            datasetOrderSeed = (environment["datasetOrderSeed"] as? Number)?.toLong() ?: 0L,
            // Absent in legacy runs. This prevents a rebuilt report from claiming
            // that collection-time semantic checks existed before they were added.
            protocolVersion = (environment["benchmarkProtocolVersion"] as? Number)?.toInt() ?: 1,
            // Absent in runs collected before the global warm-up phase existed: those
            // runs performed zero rounds, and saying so is the point of the banner.
            globalWarmupRounds = (environment["globalWarmupRounds"] as? Number)?.toInt() ?: 0,
            postIdleWarmupRounds = (environment["postIdleWarmupRounds"] as? Number)?.toInt() ?: 0,
        )
    }

    private fun readDatasets(runDirectory: Path): List<PreparedDataset> =
        CsvReader.read(runDirectory.resolve("datasets.csv")).map { row ->
            PreparedDataset(
                name = row.getValue("datasetName"),
                series = row["series"].orEmpty(),
                file = Path(row["datasetName"].orEmpty()),
                traces = row["traces"]?.toIntOrNull() ?: 0,
                eventsPerTrace = row["eventsPerTrace"]?.toIntOrNull() ?: 0,
                totalEvents = row["totalEvents"]?.toIntOrNull() ?: 0,
                attributesPerEvent = row["attributesPerEvent"]?.toIntOrNull() ?: 0,
                totalAttributes = row["totalAttributes"]?.toIntOrNull() ?: 0,
                xesBytes = row["xesBytes"]?.toLongOrNull() ?: 0L,
                xesGzBytes = row["xesGzBytes"]?.toLongOrNull() ?: 0L,
            )
        }

    private fun readQuerySpecs(runDirectory: Path): List<BenchmarkQuerySpec> {
        val known = runCatching { BenchmarkConfig.load(BenchmarkProfile.FULL).queries }
            .getOrDefault(emptyList())
            .associateBy { it.label }
        return CsvReader.read(runDirectory.resolve("queries.csv")).map { row ->
            val label = row.getValue("queryLabel")
            val recordedSeries = row["scalingSeries"]
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            BenchmarkQuerySpec(
                label = label,
                query = row["pql"].orEmpty(),
                workload = when (val recorded = row["workload"]?.ifBlank { WORKLOAD_WINDOW } ?: WORKLOAD_WINDOW) {
                    "fullPass" -> WORKLOAD_DATA_DEPENDENT
                    else -> recorded
                },
                clause = row["clause"].orEmpty(),
                // Runs predating this column are reinterpreted with the current,
                // explicitly reviewed query-axis contract rather than by guessing
                // from the coarse workload class.
                scalingSeries = recordedSeries.ifEmpty { known[label]?.scalingSeries.orEmpty() },
            )
        }
    }

    /**
     * Older runs predate `queries.csv`. Their workload class is recovered from the
     * current workload definition where the label still exists, so a replay of an old
     * run classifies its queries the same way a fresh run would.
     */
    private fun inferQuerySpecs(queries: List<QueryBenchmarkResult>): List<BenchmarkQuerySpec> {
        val known = runCatching { BenchmarkConfig.load(BenchmarkProfile.FULL).queries }
            .getOrDefault(emptyList())
            .associateBy { it.label }
        return queries.map { it.queryLabel }.distinct().map { label ->
            known[label] ?: BenchmarkQuerySpec(label = label, query = "", workload = WORKLOAD_WINDOW)
        }
    }

    private fun readImports(runDirectory: Path): List<ImportBenchmarkResult> =
        CsvReader.read(runDirectory.resolve("import-results.csv")).map { row ->
            ImportBenchmarkResult(
                system = row.getValue("system"),
                datasetName = row.getValue("datasetName"),
                run = row["run"]?.toIntOrNull() ?: 1,
                seconds = row["seconds"]?.toDoubleOrNull() ?: 0.0,
                status = row["status"].orEmpty(),
                dataStoreId = row["dataStoreId"].orEmpty(),
                logCount = row["logCount"]?.toIntOrNull() ?: 0,
                details = row["details"].orEmpty(),
            )
        }

    private fun readQueries(runDirectory: Path): List<QueryBenchmarkResult> =
        CsvReader.read(runDirectory.resolve("query-results.csv")).map { row ->
            QueryBenchmarkResult(
                system = row.getValue("system"),
                datasetName = row.getValue("datasetName"),
                queryLabel = row.getValue("queryLabel"),
                run = row["run"]?.toIntOrNull() ?: 0,
                seconds = row["seconds"]?.toDoubleOrNull() ?: 0.0,
                status = row["status"].orEmpty(),
                responseBytes = row["responseBytes"]?.toLongOrNull() ?: 0L,
                phase = row["phase"]?.ifBlank { QUERY_PHASE_WARM } ?: QUERY_PHASE_WARM,
                logCount = row["logCount"]?.toIntOrNull() ?: 0,
                traceCount = row["traceCount"]?.toIntOrNull() ?: 0,
                eventCount = row["eventCount"]?.toIntOrNull() ?: 0,
                details = row["details"].orEmpty(),
            )
        }

    private fun readStorage(runDirectory: Path): List<StorageBenchmarkResult> =
        CsvReader.read(runDirectory.resolve("storage-results.csv")).map { row ->
            val delta = row["deltaBytes"]?.toLongOrNull()
            StorageBenchmarkResult(
                system = row.getValue("system"),
                datasetName = row.getValue("datasetName"),
                beforeBytes = row["beforeBytes"]?.toLongOrNull(),
                afterBytes = row["afterBytes"]?.toLongOrNull(),
                deltaBytes = delta,
                deltaToXesRatio = row["deltaToXesRatio"]?.toDoubleOrNull(),
                deltaToGzipRatio = row["deltaToGzipRatio"]?.toDoubleOrNull(),
                // Recomputed, never taken from the file: runs recorded before negative
                // deltas got their own status wrote them as OK, which is exactly the
                // defect that let a shrinking database be plotted as a data point.
                status = when {
                    delta == null -> STORAGE_STATUS_UNAVAILABLE
                    delta > 0L -> STORAGE_STATUS_OK
                    delta < 0L -> STORAGE_STATUS_CONTAMINATED
                    else -> STORAGE_STATUS_BELOW_GRANULARITY
                },
            )
        }

    private fun readRoundtrips(runDirectory: Path): List<RoundtripBenchmarkResult> =
        CsvReader.read(runDirectory.resolve("roundtrip-results.csv")).map { row ->
            RoundtripBenchmarkResult(
                datasetName = row.getValue("datasetName"),
                status = row["status"].orEmpty(),
                differencesCount = row["differencesCount"]?.toIntOrNull() ?: 0,
                detailsPath = row["detailsPath"].orEmpty(),
            )
        }

    private fun readMemorySummaries(runDirectory: Path): List<MemorySummary> =
        CsvReader.read(runDirectory.resolve("memory-summary.csv")).map { row ->
            MemorySummary(
                component = row.getValue("component"),
                phase = row["phase"].orEmpty(),
                medianBytes = row["medianBytes"]?.toLongOrNull() ?: 0L,
                peakBytes = row["peakBytes"]?.toLongOrNull() ?: 0L,
            )
        }
}
