package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.time.LocalDate
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.math.floor

/**
 * Sample statistics used by the thesis artifacts (`thesis-report.md`, `thesis-tables.tex`).
 *
 * All quantiles are computed with **linear interpolation between closest ranks**,
 * i.e. the Hyndman & Fan (1996) *type 7* estimator — the default of R (`quantile`),
 * NumPy (`numpy.quantile`) and Excel (`PERCENTILE.INC`):
 *
 * given ascending samples `x[0..n-1]` and probability `p` in `[0, 1]`,
 * let `h = (n - 1) * p`; then `Q(p) = x[floor(h)] + (h - floor(h)) * (x[floor(h) + 1] - x[floor(h)])`.
 *
 * The quartile range reported in the thesis is the interval `[Q(0.25), Q(0.75)]`
 * under this estimator; the median is `Q(0.5)`.
 *
 * Reference: Hyndman, R. J. & Fan, Y. (1996). *Sample Quantiles in Statistical
 * Packages.* The American Statistician, 50(4), 361–365.
 */
object ThesisStatistics {
    /**
     * Minimum sample size at which a p95 is reported at all.
     *
     * At the FULL profile's `n = 30`, `Q(0.95)` interpolates between the 28th and
     * 29th order statistic: it is a function of two observations, so its sampling
     * variance is enormous and it describes the draw rather than the distribution.
     * Below this threshold the tail column is left empty instead of printing a
     * number that cannot support a claim.
     */
    const val P95_MIN_SAMPLES: Int = 200

    fun quantile(
        values: List<Double>,
        p: Double,
    ): Double {
        require(values.isNotEmpty()) { "Cannot compute a quantile of an empty sample" }
        require(p in 0.0..1.0) { "Quantile probability must be in [0, 1], was $p" }
        val sorted = values.sorted()
        val h = (sorted.size - 1) * p
        val lower = floor(h).toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        return sorted[lower] + (h - lower) * (sorted[upper] - sorted[lower])
    }

    fun stats(values: List<Double>): SampleStats =
        SampleStats(
            samples = values.size,
            median = quantile(values, 0.50),
            q1 = quantile(values, 0.25),
            q3 = quantile(values, 0.75),
            p95 = quantile(values, 0.95),
            raw = values,
        )
}

data class SampleStats(
    val samples: Int,
    val median: Double,
    val q1: Double,
    val q3: Double,
    val p95: Double,
    val raw: List<Double> = emptyList(),
) {
    /** True only when the sample is large enough for `Q(0.95)` to describe a tail. */
    val p95IsReportable: Boolean get() = samples >= ThesisStatistics.P95_MIN_SAMPLES
}

/**
 * Shared table model: every thesis table is computed once and rendered twice
 * (markdown in `thesis-report.md`, LaTeX in `thesis-tables.tex`) from the same rows.
 */
data class ThesisTable(
    /** Used as `\label{tab:bench-<slug>}` in LaTeX. */
    val slug: String,
    /** Polish caption used as markdown section heading and LaTeX `\caption`. */
    val caption: String,
    val headers: List<String>,
    /** Per-column alignment; numeric columns are right-aligned. */
    val rightAligned: List<Boolean>,
    val rows: List<List<String>>,
    /** Rendered under the table in both formats; carries `n`, estimators and caveats. */
    val note: String = "",
) {
    init {
        require(rightAligned.size == headers.size) { "Alignment list must match header count" }
        rows.forEach { require(it.size == headers.size) { "Row width must match header count in table '$slug'" } }
    }
}

data class InvalidatedPair(
    val datasetName: String,
    val queryLabel: String,
    val reason: String,
    /**
     * True when the pair has the same observable signature as the previously
     * investigated REFERENCE source-order deviation. This is a candidate
     * classification, not an automatic diagnosis of a new mismatch.
     */
    val sourceOrderCandidate: Boolean = false,
)

private const val SYSTEM_LOCAL = "local"
private const val SYSTEM_REFERENCE = "reference"
private const val MISSING = "—"
private const val NOT_ATTRIBUTABLE = "nieprzypisywalne"
private const val CONTAMINATED_CELL = "pomiar skażony (delta ujemna)"
private const val BELOW_GRANULARITY = "poniżej granulacji"
private const val LOCAL_NOT_REPORTED = "nie raportowane (§Q3)"
private const val SOURCE_ORDER_FINDING_HEADING =
    "Kolejność zdarzeń w wariantach śladu — zgodność ze specyfikacją PQL"
private const val SOURCE_ORDER_MARK = "kandydat: znane odstępstwo kolejności źródłowej"

/**
 * The PQL specification fixes the default component order, which is what makes the
 * hoisted trace-variant difference a REFERENCE deviation rather than a defect here.
 */
private const val PQL_SPEC_URL = "https://github.com/ProcessMPUT/processm/blob/master/docs/pql.md"
private const val PQL_SPEC_QUOTE =
    "By omitting the `order by` clause, the components are returned in the same order " +
        "as provided by the data source."

/**
 * Report model computed once from the in-memory benchmark records; both output
 * formats render from this model so their tables can never diverge.
 */
data class ThesisReportModel(
    val runId: String,
    val profileName: String,
    val generatedOn: String,
    val datasetOrder: String,
    val protocolVersion: Int,
    val globalWarmupRounds: Int,
    val postIdleWarmupRounds: Int,
    val repetitions: Int,
    val environmentTable: ThesisTable,
    val replicateTable: ThesisTable?,
    val replicateReport: ReplicateReport?,
    val importTable: ThesisTable,
    val floorTable: ThesisTable?,
    val floorMedianMs: Map<String, Double>,
    val scalingTable: ThesisTable?,
    val queryTables: List<ThesisTable>,
    val coldTable: ThesisTable,
    val storageProtocolTable: ThesisTable,
    val memoryTable: ThesisTable,
    val memoryComparison: MemoryComparison?,
    val roundtripTable: ThesisTable,
    val roundtripAllMatch: Boolean,
    val parityOkPairs: Int,
    val parityMismatchPairs: Int,
    val invalidatedPairs: List<InvalidatedPair>,
    /** Subset matching the observable signature of the independently investigated finding. */
    val sourceOrderCandidatePairs: List<InvalidatedPair> = invalidatedPairs.filter { it.sourceOrderCandidate },
    val caveatTable: ThesisTable,
    val verdicts: List<ComparisonVerdict>,
    val conclusions: List<String>,
) {
    fun allTables(): List<ThesisTable> =
        buildList {
            add(environmentTable)
            replicateTable?.let { add(it) }
            add(importTable)
            floorTable?.let { add(it) }
            scalingTable?.let { add(it) }
            addAll(queryTables)
            add(coldTable)
            add(storageProtocolTable)
            add(memoryTable)
            add(roundtripTable)
            add(caveatTable)
        }

    companion object {
        fun build(
            runId: String,
            settings: BenchmarkSettings,
            datasets: List<PreparedDataset>,
            imports: List<ImportBenchmarkResult>,
            queries: List<QueryBenchmarkResult>,
            storage: List<StorageBenchmarkResult>,
            roundtrips: List<RoundtripBenchmarkResult>,
            memorySummaries: List<MemorySummary>,
            querySpecs: List<BenchmarkQuerySpec>,
            environment: Map<String, Any?> = emptyMap(),
        ): ThesisReportModel {
            val datasetOrder = orderedDatasetNames(datasets, queries)
            val queryLabelOrder = querySpecs.map { it.label }
                .ifEmpty { queries.map { it.queryLabel }.distinct() }
            val specByLabel = querySpecs.associateBy { it.label }
            val measuredSamples = queries.filter { it.phase == QUERY_PHASE_COLD || it.phase == QUERY_PHASE_WARM }
            val samplesByPair = measuredSamples.groupBy { it.datasetName to it.queryLabel }

            val hoistedVariantLabels = querySpecs
                .filter { it.query.isHoistedTraceVariantQuery() }
                .map { it.label }
                .toSet()
            val realDatasetNames = datasets.filter { it.series == "real-validation" }.map { it.name }.toSet()

            val invalidated = samplesByPair
                .filterValues { samples -> samples.any { it.status == QUERY_STATUS_MISMATCH || it.status == "ERROR" } }
                .map { (pair, samples) ->
                    val mismatchOnly = samples.none { it.status == "ERROR" }
                    InvalidatedPair(
                        datasetName = pair.first,
                        queryLabel = pair.second,
                        reason = invalidationReason(samples),
                        sourceOrderCandidate = mismatchOnly &&
                            pair.first in realDatasetNames &&
                            pair.second in hoistedVariantLabels,
                    )
                }
                .sortedWith(compareBy({ datasetOrder.indexOf(it.datasetName) }, { queryLabelOrder.indexOf(it.queryLabel) }))
            val invalidatedKeys = invalidated.map { it.datasetName to it.queryLabel }.toSet()
            val validPairs = samplesByPair.filterKeys { it !in invalidatedKeys }

            val warmStats: Map<Pair<String, String>, Map<String, SampleStats>> =
                validPairs.mapValues { (_, samples) ->
                    samples
                        .filter { it.phase == QUERY_PHASE_WARM && it.status == "OK" }
                        .groupBy { it.system }
                        .mapValues { (_, s) -> ThesisStatistics.stats(s.map { it.seconds }) }
                }

            // Measurement error demonstrated by this very run (A3): replicate datasets
            // are the same experiment under different names. Their spread is the floor
            // every claimed effect must clear.
            val replicateReport = ReplicateControl.measure(datasets, queries, imports)
            val verdicts = buildVerdicts(datasetOrder, queryLabelOrder, warmStats, replicateReport)
            val verdictByPair = verdicts.associateBy { it.datasetName to it.queryLabel }

            val floorLabels = querySpecs.filter { it.workload == WORKLOAD_FLOOR }.map { it.label }.toSet()
            val floorMedianMs = floorMedians(warmStats, floorLabels)

            return ThesisReportModel(
                runId = runId,
                profileName = settings.profile.name.lowercase(),
                generatedOn = LocalDate.now().toString(),
                datasetOrder = settings.datasetOrder.name.lowercase(),
                protocolVersion = settings.protocolVersion,
                globalWarmupRounds = settings.globalWarmupRounds,
                postIdleWarmupRounds = settings.postIdleWarmupRounds,
                repetitions = settings.profile.repetitions,
                environmentTable = environmentTable(settings, environment),
                replicateTable = replicateReport?.let(::replicateTable),
                replicateReport = replicateReport,
                importTable = importTable(datasets, imports),
                floorTable = floorTable(datasetOrder, warmStats, floorLabels),
                floorMedianMs = floorMedianMs,
                scalingTable = scalingTable(
                    datasets,
                    warmStats,
                    queryLabelOrder,
                    specByLabel,
                    replicateReport?.runIsValid == true,
                ),
                queryTables = queryTables(
                    datasetOrder,
                    queryLabelOrder,
                    warmStats,
                    verdictByPair,
                    specByLabel,
                    floorMedianMs,
                    replicateReport?.runIsValid == true,
                ),
                coldTable = coldTable(datasetOrder, queryLabelOrder, validPairs),
                storageProtocolTable = storageProtocolTable(datasetOrder, storage),
                memoryTable = memoryTable(memorySummaries),
                memoryComparison = MemoryComparison.from(memorySummaries),
                roundtripTable = roundtripTable(roundtrips),
                roundtripAllMatch = roundtrips.isNotEmpty() && roundtrips.all { it.status == "MATCH" },
                parityOkPairs = validPairs.count { (_, samples) -> samples.any { it.phase == QUERY_PHASE_WARM } },
                parityMismatchPairs = samplesByPair.count { (_, samples) -> samples.any { it.status == QUERY_STATUS_MISMATCH } },
                invalidatedPairs = invalidated,
                caveatTable = caveatTable(queries, storage),
                verdicts = verdicts,
                conclusions = emptyList(),
            )
        }

        /**
         * Effect size, bootstrap interval and both significance criteria for every
         * comparable pair. Holm–Bonferroni is applied across the whole family of
         * comparisons in the run, not per table.
         */
        private fun buildVerdicts(
            datasetOrder: List<String>,
            queryLabelOrder: List<String>,
            warmStats: Map<Pair<String, String>, Map<String, SampleStats>>,
            replicateReport: ReplicateReport?,
        ): List<ComparisonVerdict> {
            data class Candidate(
                val dataset: String,
                val label: String,
                val local: SampleStats,
                val reference: SampleStats,
            )

            val candidates = warmStats
                .mapNotNull { (pair, bySystem) ->
                    val local = bySystem[SYSTEM_LOCAL] ?: return@mapNotNull null
                    val reference = bySystem[SYSTEM_REFERENCE] ?: return@mapNotNull null
                    if (local.raw.isEmpty() || reference.raw.isEmpty() || reference.median <= 0.0) return@mapNotNull null
                    Candidate(pair.first, pair.second, local, reference)
                }
                .sortedWith(
                    compareBy({ datasetOrder.indexOf(it.dataset) }, { queryLabelOrder.indexOf(it.label) }),
                )
            if (candidates.isEmpty()) return emptyList()

            val rawP = candidates.map { InferentialStatistics.wilcoxonSignedRank(it.local.raw, it.reference.raw) }
            val adjustedP = InferentialStatistics.holmAdjust(rawP)

            return candidates.mapIndexed { index, candidate ->
                ComparisonVerdict(
                    datasetName = candidate.dataset,
                    queryLabel = candidate.label,
                    ratio = candidate.local.median / candidate.reference.median,
                    confidenceInterval = InferentialStatistics.pairedMedianRatioConfidenceInterval(
                        local = candidate.local.raw,
                        reference = candidate.reference.raw,
                        // Seeded from the pair identity so the published interval is
                        // reproducible from the artifacts by anyone re-running the report.
                        seed = ("${candidate.dataset}/${candidate.label}").hashCode().toLong(),
                    ),
                    rawPValue = rawP[index],
                    adjustedPValue = adjustedP[index],
                    practicalFloor = replicateReport?.floorFor(candidate.label) ?: 1.0,
                )
            }
        }

        private fun floorMedians(
            warmStats: Map<Pair<String, String>, Map<String, SampleStats>>,
            floorLabels: Set<String>,
        ): Map<String, Double> {
            if (floorLabels.isEmpty()) return emptyMap()
            return listOf(SYSTEM_LOCAL, SYSTEM_REFERENCE).mapNotNull { system ->
                val medians = warmStats
                    .filterKeys { it.second in floorLabels }
                    .mapNotNull { (_, bySystem) -> bySystem[system]?.median }
                if (medians.isEmpty()) null else system to ThesisStatistics.quantile(medians, 0.50) * MS
            }.toMap()
        }

        private fun orderedDatasetNames(
            datasets: List<PreparedDataset>,
            queries: List<QueryBenchmarkResult>,
        ): List<String> = (datasets.map { it.name } + queries.map { it.datasetName }).distinct()

        /**
         * `group by ^e:attr` — the caret hoists an event attribute to trace scope, so
         * traces are grouped by the SEQUENCE of that attribute's values. Detected on the
         * query text (not the label) so renaming a workload entry cannot silently drop
         * the classification.
         */
        private fun String.isHoistedTraceVariantQuery(): Boolean {
            val normalized = lowercase()
            val groupByAt = normalized.indexOf("group by")
            if (groupByAt < 0) return false
            return normalized.substring(groupByAt).contains("^")
        }

        private fun invalidationReason(samples: List<QueryBenchmarkResult>): String {
            val mismatch = samples.firstOrNull { it.status == QUERY_STATUS_MISMATCH }
            if (mismatch != null) {
                val details = mismatch.details.ifBlank { "rozjazd liczności odpowiedzi między systemami" }
                return "MISMATCH — $details"
            }
            val error = samples.first { it.status == "ERROR" }
            return "ERROR (system ${error.system}, faza ${error.phase})" +
                (if (error.details.isNotBlank()) " — ${error.details}" else "")
        }

        private fun environmentTable(
            settings: BenchmarkSettings,
            environment: Map<String, Any?>,
        ): ThesisTable {
            fun nested(vararg keys: String): Any? {
                var current: Any? = environment
                keys.forEach { key -> current = (current as? Map<*, *>)?.get(key) }
                return current
            }

            fun bytes(value: Any?): String =
                (value as? Number)?.toLong()?.let { "${fmt0(it.toDouble() / MIB)} MiB" }
                    ?: value?.toString()
                    ?: MISSING

            val containers = (environment["containers"] as? Map<*, *>)
                ?.entries
                ?.sortedBy { it.key.toString() }
                ?.joinToString("; ") { (name, details) ->
                    val values = details as? Map<*, *>
                    "$name=${values?.get("imageId") ?: MISSING}"
                }
                ?.ifBlank { MISSING }
                ?: MISSING
            val heaps = (environment["containers"] as? Map<*, *>)
                ?.entries
                ?.sortedBy { it.key.toString() }
                ?.mapNotNull { (name, details) ->
                    (details as? Map<*, *>)?.get("effectiveJvmHeap")
                        ?.takeUnless { it == "unavailable" }
                        ?.let { "$name=$it" }
                }
                ?.joinToString("; ")
                ?.ifBlank { MISSING }
                ?: MISSING

            return ThesisTable(
                slug = "srodowisko",
                caption = "Środowisko pomiarowe",
                headers = listOf("Parametr", "Wartość"),
                rightAligned = listOf(false, false),
                rows = listOf(
                    listOf(
                        "System operacyjny",
                        listOf(environment["osName"], environment["osVersion"])
                            .mapNotNull { it?.toString() }
                            .joinToString(" ")
                            .ifBlank { "${System.getProperty("os.name")} ${System.getProperty("os.version")}" },
                    ),
                    listOf("CPU hosta", nested("host", "cpuModel")?.toString() ?: MISSING),
                    listOf(
                        "Procesory logiczne (Docker VM / host JVM)",
                        "${nested("dockerEngine", "logicalProcessors") ?: MISSING} / " +
                            "${nested("host", "logicalProcessors") ?: Runtime.getRuntime().availableProcessors()}",
                    ),
                    listOf("Pamięć hosta", bytes(nested("host", "totalPhysicalMemoryBytes"))),
                    listOf("Budżet pamięci Docker VM", bytes(nested("dockerEngine", "totalMemoryBytes"))),
                    listOf("Java", environment["javaVersion"]?.toString() ?: System.getProperty("java.version")),
                    listOf("Commit Git / dirty", "${nested("source", "gitCommit") ?: MISSING} / ${nested("source", "gitDirty") ?: MISSING}"),
                    listOf("Image ID kontenerów", containers),
                    listOf("Efektywne sterty JVM kontenerów", heaps),
                    listOf("Profil benchmarku", settings.profile.name.lowercase()),
                    listOf("Wersja protokołu benchmarku", settings.protocolVersion.toString()),
                    listOf("Rundy globalnej rozgrzewki (przed pierwszym pomiarem)", settings.globalWarmupRounds.toString()),
                    listOf("Rundy aktywacyjne po pomiarze bezczynności", settings.postIdleWarmupRounds.toString()),
                    listOf("Rozgrzewki na zapytanie", settings.profile.warmups.toString()),
                    listOf("Repetycje mierzone na zapytanie", settings.profile.repetitions.toString()),
                    listOf("Kolejność zbiorów", "${settings.datasetOrder.name.lowercase()} (ziarno ${settings.datasetOrderSeed})"),
                    listOf("Okno pomiaru pamięci spoczynkowej [s]", settings.profile.idleBaselineSeconds.toString()),
                    listOf("API LOCAL (Kotlin/Neo4j)", settings.localApi),
                    listOf("API REFERENCE (ProcessM/PostgreSQL)", settings.referenceApi),
                    listOf("Pełne limity kontenerów i konfiguracja pamięci baz", "zob. environment.json tego przebiegu"),
                ),
            )
        }

        private fun replicateTable(report: ReplicateReport): ThesisTable =
            ThesisTable(
                slug = "replikacja",
                caption = "Kontrola replikacji: rozrzut pomiarów na zbiorach o identycznych parametrach",
                headers = listOf("Zbiory replikacyjne", "Operacja", "System", "min [ms]", "max [ms]", "Rozrzut max/min"),
                rightAligned = listOf(false, false, false, true, true, true),
                rows = report.spreads.take(20).map {
                    listOf(
                        it.datasets.joinToString(" = "),
                        it.queryLabel,
                        it.system,
                        fmt1(it.minSeconds * MS),
                        fmt1(it.maxSeconds * MS),
                        "×${fmt2(it.spread)}",
                    )
                },
                note = "Grupy replikacyjne: ${report.groups.joinToString("; ") { it.joinToString(" = ") }}. " +
                    "Zbiory w każdej grupie mają identyczne parametry (liczba śladów × zdarzeń × atrybutów), " +
                    "różnią się wyłącznie nazwą logu, więc każda różnica ich pomiarów jest błędem pomiaru, " +
                    "nie własnością danych. Kontrola obejmuje import Q1 oraz zapytania Q2, ale " +
                    "bramki obu metryk są rozdzielone. " +
                    "Mediana rozrzutu ×${fmt2(report.medianSpread)}, " +
                    "maksimum Q2 ×${fmt2(report.worstQuerySpread)}, maksimum Q1 ×${fmt2(report.worstImportSpread)} " +
                    "(próg każdej metryki: ×${fmt2(ReplicateControl.VALIDITY_GATE_SPREAD)}). " +
                    "Tabela pokazuje 20 najgorszych z ${report.spreads.size} par."

            )

        private fun importTable(
            datasets: List<PreparedDataset>,
            imports: List<ImportBenchmarkResult>,
        ): ThesisTable {
            fun seconds(
                dataset: String,
                system: String,
            ): Double? =
                imports
                    .filter { it.datasetName == dataset && it.system == system && it.status == "OK" }
                    .map { it.seconds }
                    .takeIf { it.isNotEmpty() }
                    ?.let { ThesisStatistics.quantile(it, 0.50) }

            val sequence = imports.map { it.datasetName }.distinct()
            return ThesisTable(
                slug = "import",
                caption = "Import (Q1): pojedynczy pomiar tego przebiegu [s]",
                headers = listOf(
                    "Poz.",
                    "Dataset",
                    "Rozmiar XES [MiB]",
                    "Trace'y",
                    "Zdarzenia",
                    "LOCAL [s]",
                    "REFERENCE [s]",
                ),
                rightAligned = listOf(true, false, true, true, true, true, true),
                rows = datasets.map { dataset ->
                    val local = seconds(dataset.name, SYSTEM_LOCAL)
                    val reference = seconds(dataset.name, SYSTEM_REFERENCE)
                    val position = sequence.indexOf(dataset.name).let { if (it < 0) MISSING else (it + 1).toString() }
                    listOf(
                        position,
                        dataset.name,
                        fmt2(dataset.xesBytes.toDouble() / MIB),
                        dataset.traces.toString(),
                        dataset.totalEvents.toString(),
                        local?.let(::fmt2) ?: MISSING,
                        reference?.let(::fmt2) ?: MISSING,
                    )
                },
                note = "Czas importu obejmuje upload HTTP oraz oczekiwanie, aż log stanie się widoczny " +
                    "na liście datastore'u. Polling co 1 s dodaje nieujemne opóźnienie < 1 s, przede " +
                    "wszystkim po stronie asynchronicznego REFERENCE. Każdy przebieg dostarcza jedną " +
                    "próbkę; finalne mediany i zakresy Q1 wylicza `compare-runs.py` z co najmniej trzech " +
                    "pełnych przebiegów. Kolumna „Poz.” wskazuje pozycję datasetu w bieżącym bloku.",
            )
        }

        /** Smallest observed end-to-end query window, used only as descriptive context. */
        private fun floorTable(
            datasetOrder: List<String>,
            warmStats: Map<Pair<String, String>, Map<String, SampleStats>>,
            floorLabels: Set<String>,
        ): ThesisTable? {
            if (floorLabels.isEmpty()) return null
            val rows = datasetOrder.flatMap { dataset ->
                floorLabels.sorted().mapNotNull { label ->
                    val bySystem = warmStats[dataset to label] ?: return@mapNotNull null
                    val local = bySystem[SYSTEM_LOCAL]
                    val reference = bySystem[SYSTEM_REFERENCE]
                    if (local == null && reference == null) return@mapNotNull null
                    listOf(
                        dataset,
                        local?.let { fmt2(it.median * MS) } ?: MISSING,
                        reference?.let { fmt2(it.median * MS) } ?: MISSING,
                    )
                }
            }
            if (rows.isEmpty()) return null
            return ThesisTable(
                slug = "podloga-pomiaru",
                caption = "Najmniejsze obserwowane okno zapytania [ms]",
                headers = listOf("Dataset", "LOCAL [ms]", "REFERENCE [ms]"),
                rightAligned = listOf(false, true, true),
                rows = rows,
                note = "Zapytanie `limit l:1, t:1, e:1` ogranicza wynik do najmniejszego okna, ale nadal " +
                    "odczytuje i serializuje metadane logu. Jest opisowym punktem odniesienia dla kosztu " +
                    "end-to-end dwóch aplikacji; nie jest stałą niezależną od danych i nie wolno go " +
                    "odejmować w celu przypisania reszty czasu bazie danych.",
            )
        }

        /** Fitted scaling exponent for predeclared, semantically meaningful query-axis pairs. */
        private fun scalingTable(
            datasets: List<PreparedDataset>,
            warmStats: Map<Pair<String, String>, Map<String, SampleStats>>,
            queryLabelOrder: List<String>,
            specByLabel: Map<String, BenchmarkQuerySpec>,
            runIsValid: Boolean,
        ): ThesisTable? {
            val axes = listOf(
                Triple("trace-scaling", "liczba śladów") { d: PreparedDataset -> d.traces.toDouble() },
                Triple("event-scaling", "liczba zdarzeń") { d: PreparedDataset -> d.totalEvents.toDouble() },
                Triple("attribute-scaling", "atrybuty/zdarzenie") { d: PreparedDataset -> d.attributesPerEvent.toDouble() },
            )
            val rows = mutableListOf<List<String>>()
            axes.forEach { (series, axisLabel, axisValue) ->
                val seriesDatasets = datasets.filter { it.series == series }
                if (seriesDatasets.size < 3) return@forEach
                queryLabelOrder
                    .filter { label -> series in specByLabel[label]?.scalingSeries.orEmpty() }
                    .forEach { label ->
                        listOf(SYSTEM_LOCAL, SYSTEM_REFERENCE).forEach systemLoop@{ system ->
                            val points = seriesDatasets.mapNotNull { dataset ->
                                warmStats[dataset.name to label]?.get(system)?.median
                                    ?.let { axisValue(dataset) to it }
                            }
                            if (points.size < 3) return@systemLoop
                            val fit = InferentialStatistics.fitPowerLaw(points.map { it.first }, points.map { it.second })
                                ?: return@systemLoop
                            rows += listOf(
                                label,
                                workloadLabel(specByLabel[label]?.workload),
                                axisLabel,
                                system,
                                points.size.toString(),
                                fmtSigned(fit.slope),
                                fmt2(fit.r2),
                                when {
                                    !runIsValid -> "diagnostyka — przebieg nieważny"
                                    else -> "blok: ${scalingInterpretation(fit)} — seria wymagana"
                                },
                            )
                        }
                    }
            }
            if (rows.isEmpty()) return null
            return ThesisTable(
                slug = "skalowanie-wykladniki",
                caption = "Skalowanie (Q2): wykładnik potęgowy dopasowany do t ~ n^α",
                headers = listOf("Zapytanie", "Klasa", "Oś", "System", "Punkty", "α", "R²", "Interpretacja"),
                rightAligned = listOf(false, false, false, false, true, true, true, false),
                rows = rows,
                note = (if (runIsValid) "" else "Przebieg nie przeszedł kontroli replikacji; dopasowania są diagnostyczne. ") +
                    "Dopasowanie metodą najmniejszych kwadratów w przestrzeni log10–log10; " +
                    "α = 1 oznacza koszt liniowy względem osi, α = 0 — brak zależności od rozmiaru danych. " +
                    "Tabela zawiera tylko pary zapytanie–seria zadeklarowane przed pomiarem w `queries.csv`. " +
                    "Limit odpowiedzi nie usuwa kosztu sortowania, grupowania lub agregacji wykonywanych " +
                    "przed limitem niższego zakresu. Wiersz z R² < 0,3 nie uprawnia do wniosku o kształcie " +
                    "zależności; nawet pozostałe wiersze jednego bloku są opisowe, a finalny wniosek " +
                    "pochodzi z sekcji skalowania między przebiegami.",
            )
        }

        private fun workloadLabel(workload: String?): String =
            when (workload) {
                WORKLOAD_FLOOR -> "najmniejsze okno"
                WORKLOAD_DATA_DEPENDENT -> "zależne od danych"
                "fullPass" -> "zależne od danych (stary zapis)"
                WORKLOAD_WINDOW -> "okno"
                else -> MISSING
            }

        private fun scalingInterpretation(fit: LinearFit): String =
            when {
                fit.r2 < 0.30 -> "brak dopasowania (szum)"
                abs(fit.slope) < 0.05 -> "brak zależności od rozmiaru"
                fit.slope < 0.0 -> "malejący (artefakt pomiaru)"
                fit.slope < 0.5 -> "podliniowy"
                fit.slope < 1.2 -> "ok. liniowy"
                else -> "nadliniowy"
            }

        private fun queryTables(
            datasetOrder: List<String>,
            queryLabelOrder: List<String>,
            warmStats: Map<Pair<String, String>, Map<String, SampleStats>>,
            verdictByPair: Map<Pair<String, String>, ComparisonVerdict>,
            specByLabel: Map<String, BenchmarkQuerySpec>,
            floorMedianMs: Map<String, Double>,
            runIsValid: Boolean,
        ): List<ThesisTable> =
            datasetOrder.mapNotNull { dataset ->
                val labels = queryLabelOrder.filter { label ->
                    warmStats[dataset to label]?.values?.any { it.samples > 0 } == true
                }
                if (labels.isEmpty()) return@mapNotNull null
                val samplesPerCell = warmStats
                    .filterKeys { it.first == dataset }
                    .values
                    .flatMap { it.values }
                    .map { it.samples }
                    .distinct()
                val anyP95 = warmStats.filterKeys { it.first == dataset }
                    .values.flatMap { it.values }.any { it.p95IsReportable }
                ThesisTable(
                    slug = "zapytania-${slugify(dataset)}",
                    caption = "Zapytania (Q2), dataset $dataset: czasy odpowiedzi (próbki warm) [ms]",
                    headers = listOf(
                        "Zapytanie",
                        "Klasa",
                        "LOCAL mediana [ms]",
                        "LOCAL Q1–Q3 [ms]",
                        "REFERENCE mediana [ms]",
                        "REFERENCE Q1–Q3 [ms]",
                        "Efekt w bloku",
                        "95% CI ilorazu",
                        "p (Holm)",
                        "Werdykt",
                    ),
                    rightAligned = listOf(false, false, true, true, true, true, true, true, true, false),
                    rows = labels.map { label ->
                        val bySystem = warmStats.getValue(dataset to label)
                        val local = bySystem[SYSTEM_LOCAL]
                        val reference = bySystem[SYSTEM_REFERENCE]
                        val verdict = verdictByPair[dataset to label]
                        listOf(
                            label,
                            workloadLabel(specByLabel[label]?.workload),
                            local?.let { fmt1(it.median * MS) } ?: MISSING,
                            local?.let { quartiles(it) } ?: MISSING,
                            reference?.let { fmt1(it.median * MS) } ?: MISSING,
                            reference?.let { quartiles(it) } ?: MISSING,
                            verdict?.let { advantageOf(it) } ?: MISSING,
                            verdict?.confidenceInterval?.let { "[${fmt2(it.low)}; ${fmt2(it.high)}]" } ?: MISSING,
                            verdict?.let { formatPValue(it.adjustedPValue) } ?: MISSING,
                            if (runIsValid) verdict?.verdict?.label ?: MISSING else "przebieg nieważny — diagnostyka",
                        )
                    },
                    note = buildString {
                        append("n = ")
                        append(samplesPerCell.sorted().joinToString(", "))
                        append(" próbek warm na komórkę. ")
                        append(
                            "„Efekt w bloku” podaje iloraz median jako czynnik ≥ 1 wraz z kierunkiem; " +
                                "przedział to percentylowy bootstrap (${InferentialStatistics.BOOTSTRAP_RESAMPLES} " +
                                "losowań par repetycji) dla ilorazu median; p to sparowany test rang " +
                                "Wilcoxona po korekcie Holma na wszystkie porównania przebiegu. ",
                        )
                        if (runIsValid) {
                            append(
                                "Werdykt „istotna” oznacza wyłącznie rozróżnialny efekt wewnątrz tego bloku " +
                                    "i wymaga jednocześnie p < ${fmt2(InferentialStatistics.ALPHA)}, przedziału " +
                                    "nieobejmującego 1,00 oraz efektu przekraczającego kontrolę replikacji. " +
                                    "Wniosek między systemami wymaga serii niezależnych bloków. ",
                            )
                        } else {
                            append(
                                "Przebieg nie przeszedł kontroli replikacji: efekty, przedziały i p pozostają " +
                                    "diagnostyką i nie otrzymują werdyktu inferencyjnego. ",
                            )
                        }
                        if (!anyP95) {
                            append(
                                "Kolumny p95 nie podano: przy n < ${ThesisStatistics.P95_MIN_SAMPLES} " +
                                    "kwantyl 0,95 jest funkcją dwóch obserwacji i nie opisuje ogona rozkładu. ",
                            )
                        }
                        floorMedianMs.forEach { (system, floor) ->
                            append("Najmniejsze obserwowane okno $system: ${fmt2(floor)} ms. ")
                        }
                    },
                )
            }

        private fun coldOf(
            samples: List<QueryBenchmarkResult>,
            system: String,
        ): Double? =
            samples
                .filter { it.phase == QUERY_PHASE_COLD && it.status == "OK" && it.system == system }
                .map { it.seconds }
                .takeIf { it.isNotEmpty() }
                ?.let { ThesisStatistics.quantile(it, 0.50) }

        private fun coldTable(
            datasetOrder: List<String>,
            queryLabelOrder: List<String>,
            validPairs: Map<Pair<String, String>, List<QueryBenchmarkResult>>,
        ): ThesisTable {
            val rows = datasetOrder.flatMap { dataset ->
                queryLabelOrder.mapNotNull { label ->
                    val samples = validPairs[dataset to label] ?: return@mapNotNull null
                    val local = coldOf(samples, SYSTEM_LOCAL)
                    val reference = coldOf(samples, SYSTEM_REFERENCE)
                    if (local == null && reference == null) return@mapNotNull null
                    listOf(
                        dataset,
                        label,
                        local?.let { fmt1(it * MS) } ?: MISSING,
                        reference?.let { fmt1(it * MS) } ?: MISSING,
                    )
                }
            }
            return ThesisTable(
                slug = "zapytania-cold",
                caption = "Zapytania (Q2): pierwsze (zimne) wykonania po imporcie [ms]",
                headers = listOf("Dataset", "Zapytanie", "LOCAL cold [ms]", "REFERENCE cold [ms]"),
                rightAligned = listOf(false, false, true, true),
                rows = rows,
                note = "Globalna rozgrzewka poprzedza całą fazę pomiarową, dlatego pierwszy dataset " +
                    "nie jest traktowany jako osobna klasa. Cold oznacza pierwsze wykonanie na nowym " +
                    "datastore po imporcie. To pojedyncza próbka na komórkę — bez ilorazu, przedziału " +
                    "ufności i testu; tabela pozostaje wyłącznie diagnostyką.",
            )
        }

        private fun storageProtocolTable(
            datasetOrder: List<String>,
            storage: List<StorageBenchmarkResult>,
        ): ThesisTable {
            fun probe(
                dataset: String,
                system: String,
            ): StorageBenchmarkResult? = storage.firstOrNull { it.datasetName == dataset && it.system == system }

            // One shared validity filter for the cell text — the charts read the same
            // status column, so a value the table refuses to print can no longer be
            // drawn as a point (the negative REFERENCE deltas used to be plotted while
            // this table showed "poniżej granulacji" for the very same cell).
            fun cell(
                result: StorageBenchmarkResult?,
                render: (StorageBenchmarkResult) -> String,
            ): String =
                when {
                    result == null -> MISSING
                    result.isAttributable -> render(result)
                    result.status == STORAGE_STATUS_CONTAMINATED -> CONTAMINATED_CELL
                    result.status == STORAGE_STATUS_BELOW_GRANULARITY -> BELOW_GRANULARITY
                    else -> MISSING
                }

            val rows = datasetOrder.mapNotNull { dataset ->
                val local = probe(dataset, SYSTEM_LOCAL)
                val reference = probe(dataset, SYSTEM_REFERENCE)
                if (local == null && reference == null) return@mapNotNull null
                listOf(
                    dataset,
                    cell(local) { LOCAL_NOT_REPORTED },
                    cell(reference) { fmt2(it.deltaBytes!!.toDouble() / MIB) },
                    cell(reference) { it.deltaToXesRatio?.let(::fmt2) ?: MISSING },
                )
            }
            return ThesisTable(
                slug = "storage-protokol",
                caption = "Załącznik: przyrost dysku zmierzony w protokole benchmarku (pomocniczo)",
                headers = listOf(
                    "Dataset",
                    "LOCAL przyrost",
                    "REFERENCE przyrost [MiB]",
                    "REFERENCE delta/XES",
                ),
                rightAligned = listOf(false, true, true, true),
                rows = rows,
                note = "**Tabela pomocnicza — w pracy należy cytować model przyrostu (sekcja Q3), " +
                    "nie te liczby.** W protokole benchmarku kolejne zbiory importowane są narastająco, " +
                    "więc przyrost per dataset miesza koszt danych z prealokacją. Neo4j w wersji community " +
                    "nie udostępnia wymuszonego checkpointu, dlatego strona LOCAL nie ma tu wartości " +
                    "liczbowych; REFERENCE jest checkpointowany (`CHECKPOINT;`) przed każdym pomiarem, " +
                    "ale i tam pojedyncze pomiary bywają skażone (delta ujemna po odzysku stron przez " +
                    "autovacuum lub recykling WAL) — takie komórki są oznaczone i **nie są rysowane " +
                    "jako wartości liczbowe**; wykres załącznika pokazuje w ich miejscu pusty znacznik.",
            )
        }

        private fun memoryTable(memorySummaries: List<MemorySummary>): ThesisTable =
            ThesisTable(
                slug = "pamiec",
                caption = "Zasobożerność (Q3): pamięć operacyjna per składnik i faza (surowe interwały w memory-results.csv)",
                headers = listOf("Składnik", "Faza", "Mediana [MiB]", "Szczyt [MiB]"),
                rightAligned = listOf(false, false, true, true),
                rows = memorySummaries
                    .sortedWith(compareBy({ it.component }, { it.phase }))
                    .map {
                        listOf(
                            it.component,
                            it.phase,
                            fmt1(it.medianBytes.toDouble() / MIB),
                            fmt1(it.peakBytes.toDouble() / MIB),
                        )
                    },
            )

        private fun roundtripTable(roundtrips: List<RoundtripBenchmarkResult>): ThesisTable =
            ThesisTable(
                slug = "roundtrip",
                caption = "Poprawność (Q4): roundtrip XES (import do LOCAL, eksport, porównanie kanoniczne z oryginałem)",
                headers = listOf("Dataset", "Status", "Liczba różnic"),
                rightAligned = listOf(false, false, true),
                rows = roundtrips.map { listOf(it.datasetName, it.status, it.differencesCount.toString()) },
            )

        /**
         * Anomalies as a table rather than one repeated sentence per pair. The previous
         * report emitted 19 paragraphs differing only in the pair name.
         */
        private fun caveatTable(
            queries: List<QueryBenchmarkResult>,
            storage: List<StorageBenchmarkResult>,
        ): ThesisTable {
            val rows = mutableListOf<List<String>>()
            queries
                .filter { it.status == "ERROR" }
                .groupBy { Triple(it.system, it.datasetName, it.queryLabel) }
                .forEach { (key, samples) ->
                    rows += listOf(
                        "Błąd wykonania",
                        "${key.second} / ${key.third}",
                        key.first,
                        samples.first().details.ifBlank { "brak szczegółów" },
                    )
                }
            storage
                .filter { it.status == STORAGE_STATUS_CONTAMINATED }
                .groupBy { it.system }
                .forEach { (system, results) ->
                    rows += listOf(
                        "Pomiar dysku skażony (delta ujemna)",
                        results.joinToString(", ") { it.datasetName },
                        system,
                        "Baza skurczyła się w trakcie importu — pomiar wyłączony z tabel, wykresów i dopasowania.",
                    )
                }
            storage
                .filter { it.status == STORAGE_STATUS_UNAVAILABLE }
                .groupBy { it.system }
                .forEach { (system, results) ->
                    rows += listOf(
                        "Sonda dysku bez odczytu",
                        results.joinToString(", ") { it.datasetName },
                        system,
                        "Sonda nie zwróciła rozmiaru.",
                    )
                }
            return ThesisTable(
                slug = "zastrzezenia",
                caption = "Zastrzeżenia: anomalie wykryte automatycznie w tym przebiegu",
                headers = listOf("Rodzaj", "Czego dotyczy", "System", "Szczegóły"),
                rightAligned = listOf(false, false, false, false),
                rows = rows,
                note = "Pary o werdykcie „nieistotna” lub „poniżej błędu pomiaru” **nie są** anomaliami " +
                    "i nie są tu wymieniane — ich status wynika wprost z kolumny „Werdykt” w tabelach Q2.",
            )
        }

        private fun quartiles(stats: SampleStats): String = "[${fmt1(stats.q1 * MS)}; ${fmt1(stats.q3 * MS)}]"

        /** Ratio as a factor >= 1 plus the direction, so one column never mixes 0,02 with 4,49. */
        private fun advantage(
            local: Double?,
            reference: Double?,
        ): String {
            if (local == null || reference == null || local <= 0.0 || reference <= 0.0) return MISSING
            val ratio = local / reference
            return if (ratio >= 1.0) {
                "×${fmt2(ratio)} (REFERENCE)"
            } else {
                "×${fmt2(1.0 / ratio)} (LOCAL)"
            }
        }

        private fun advantageOf(verdict: ComparisonVerdict): String =
            "×${fmt2(verdict.magnitude)} (${verdict.fasterSystem})"

        private fun formatPValue(p: Double): String =
            when {
                p < 1e-4 -> "< 0.0001"
                else -> String.format(Locale.ROOT, "%.4f", p)
            }

        private fun slugify(name: String): String =
            name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")

        private const val MIB = 1024.0 * 1024.0
        private const val MS = 1000.0

        private fun fmt0(value: Double): String = String.format(Locale.ROOT, "%.0f", value)

        private fun fmt1(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

        private fun fmt2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

        private fun fmtSigned(value: Double): String = String.format(Locale.ROOT, "%+.3f", value)
    }
}

/**
 * Q3 memory from simultaneous per-timestamp totals. Taking the median after summing
 * avoids the generally false identity median(A) + median(B) = median(A + B).
 */
data class MemoryComparison(
    val phase: String,
    val localMiB: Double,
    val referenceMiB: Double,
    val localComponents: List<String>,
) {
    val differenceMiB: Double get() = localMiB - referenceMiB

    val ratio: Double get() = if (referenceMiB > 0) localMiB / referenceMiB else Double.NaN

    companion object {
        fun from(summaries: List<MemorySummary>): MemoryComparison? {
            val phase = MEMORY_PHASE_QUERIES
            val inPhase = summaries.filter { it.phase == phase }
            if (inPhase.isEmpty()) return null
            val local = inPhase.singleOrNull { it.component == "local-total" } ?: return null
            val reference = inPhase.singleOrNull { it.component == "reference-total" } ?: return null
            return MemoryComparison(
                phase = phase,
                localMiB = local.medianBytes.toDouble() / (1024.0 * 1024.0),
                referenceMiB = reference.medianBytes.toDouble() / (1024.0 * 1024.0),
                localComponents = listOf("processm-interpreter", "processm-neo4j"),
            )
        }
    }
}

/**
 * Writes the two thesis artifacts mandated by METODOLOGIA §6 at the end of every
 * benchmark run, from the in-memory records (never by re-reading the CSVs):
 *
 * - `thesis-report.md` — self-contained Polish report with all result tables,
 * - `thesis-tables.tex` — the same tables as standalone booktabs LaTeX for `\input{}`.
 */
class ThesisReportWriter(
    private val outputDirectory: Path,
) {
    private val mapper = jacksonObjectMapper()

    fun write(
        runId: String,
        settings: BenchmarkSettings,
        datasets: List<PreparedDataset>,
        imports: List<ImportBenchmarkResult>,
        queries: List<QueryBenchmarkResult>,
        storage: List<StorageBenchmarkResult>,
        roundtrips: List<RoundtripBenchmarkResult>,
        memorySummaries: List<MemorySummary>,
        querySpecs: List<BenchmarkQuerySpec>,
    ) {
        val model = ThesisReportModel.build(
            runId, settings, datasets, imports, queries, storage, roundtrips, memorySummaries, querySpecs,
            recordedEnvironment(),
        )
        outputDirectory.createDirectories()
        outputDirectory.resolve("thesis-report.md").writeText(renderMarkdown(model))
        outputDirectory.resolve("thesis-tables.tex").writeText(renderLatex(model))
    }

    private fun recordedEnvironment(): Map<String, Any?> {
        val path = outputDirectory.resolve("environment.json")
        if (!path.isRegularFile()) return emptyMap()
        return runCatching {
            mapper.readValue(path.readText(), object : TypeReference<Map<String, Any?>>() {})
        }.getOrDefault(emptyMap())
    }

    private fun renderMarkdown(model: ThesisReportModel): String =
        buildString {
            appendLine("# Wyniki benchmarku — raport do pracy magisterskiej")
            appendLine()
            appendLine("- Profil: ${model.profileName}")
            appendLine("- Identyfikator przebiegu (runId): ${model.runId}")
            appendLine("- Data wygenerowania: ${model.generatedOn}")
            appendLine("- Wersja protokołu benchmarku: ${model.protocolVersion}")
            appendLine(
                "- Kolejność zbiorów: ${model.datasetOrder}; rundy globalnej rozgrzewki: " +
                    "${model.globalWarmupRounds}; rundy aktywacyjne po idle: ${model.postIdleWarmupRounds}",
            )
            appendLine()
            appendValidityBanner(model)
            appendLine(
                "Niniejszy raport zawiera wyłącznie wyniki jednego przebiegu benchmarku. " +
                    "Pełna metodologia pomiarów (pytania badawcze Q1–Q4, zasady fair play, protokół " +
                    "naprzemienny, reguła istotności) opisana jest w dokumencie " +
                    "`src/benchmark/METODOLOGIA.md`; wszystkie tabele poniżej stosują tę metodologię. " +
                    "Czasy podano w jednostkach wskazanych w nagłówkach kolumn, z kropką jako " +
                    "separatorem dziesiętnym.",
            )
            appendLine()
            appendConclusions(model)
            appendLine("## Środowisko pomiarowe")
            appendLine()
            appendMarkdownTable(model.environmentTable)
            appendReplicateSection(model)
            appendLine("## Import (Q1)")
            appendLine()
            appendMarkdownTable(model.importTable)
            appendLine("## Zapytania (Q2)")
            appendLine()
            appendLine(
                "Tabele obejmują wyłącznie próbki warm (mierzone repetycje po rozgrzewce); " +
                    "pary (dataset, zapytanie) ze statusem MISMATCH lub ERROR są wykluczone z tabel " +
                    "i wymienione w sekcji „Pomiary unieważnione”.",
            )
            appendLine()
            appendLine(
                "**Ograniczenie wspólne dla obu systemów.** Oba API stosują domyślne limity " +
                    "hierarchiczne (10 logów / 30 śladów / 90 zdarzeń), którymi ograniczany jest także " +
                    "jawny `limit` — po stronie LOCAL odwzorowuje to `LogsService.applyLimits()` " +
                    "systemu ProcessM, więc porównanie pozostaje symetryczne. Rozmiar odpowiedzi **nie " +
                    "zależy od rozmiaru zbioru**, lecz koszt wykonania może zależeć: sortowanie, grupowanie " +
                    "lub agregacja niższego zakresu zachodzą przed jego limitem. Dlatego interpretowalność " +
                    "skalowania jest zadeklarowana osobno dla każdej pary zapytanie–seria w `queries.csv`; " +
                    "nie wynika automatycznie z ogólnej klasy *okno* / *zależne od danych*.",
            )
            appendLine()
            model.floorTable?.let {
                appendLine("### Najmniejsze obserwowane okno")
                appendLine()
                appendMarkdownTable(it)
            }
            model.scalingTable?.let {
                appendLine("### Skalowanie — dopasowane wykładniki")
                appendLine()
                appendMarkdownTable(it)
            }
            model.queryTables.forEach { table ->
                appendLine("### ${table.caption}")
                appendLine()
                appendMarkdownTable(table)
            }
            appendLine("### Zimne wykonania (cold)")
            appendLine()
            appendMarkdownTable(model.coldTable)
            appendLine("### Pomiary unieważnione")
            appendLine()
            if (model.invalidatedPairs.isEmpty()) {
                appendLine("Brak — żadna para (dataset, zapytanie) nie została unieważniona.")
            } else {
                model.invalidatedPairs.forEach {
                    val mark = if (it.sourceOrderCandidate) " **[$SOURCE_ORDER_MARK]**" else ""
                    appendLine("- ${it.datasetName} / ${it.queryLabel}: ${it.reason}$mark")
                }
                if (model.sourceOrderCandidatePairs.isNotEmpty()) {
                    appendLine()
                    appendLine(
                        "**Uwaga:** pozycje oznaczone jako *$SOURCE_ORDER_MARK* mają tę samą sygnaturę " +
                            "co wcześniej zbadane odstępstwo REFERENCE. Sam kształt zapytania nie dowodzi " +
                            "jednak przyczyny nowego mismatchu; rozpoznanie wymaga raportu kompatybilności.",
                    )
                }
            }
            appendLine()
            appendLine("## Zasobożerność (Q3)")
            appendLine()
            appendLine("### Przestrzeń dyskowa")
            appendLine()
            appendLine(
                "Wynik Q3-dysk pochodzi wyłącznie z dedykowanej sondy " +
                    "`scripts/benchmarks/measure-storage-scaling.py`, która importuje każdy dataset " +
                    "na osobnym świeżym stacku. Skrypt wykresów wstawia w tym miejscu model dopiero " +
                    "po dołączeniu `storage-scaling.csv`. Pomiary z głównego protokołu pozostają " +
                    "diagnostycznym załącznikiem i nie są używane do estymacji ekspansji.",
            )
            appendLine()
            appendLine("### Pamięć operacyjna")
            appendLine()
            appendMarkdownTable(model.memoryTable)
            appendMemoryVerdict(model)
            appendLine("## Poprawność (Q4)")
            appendLine()
            appendMarkdownTable(model.roundtripTable)
            if (model.protocolVersion >= 2) {
                appendLine(
                    "Parytet odpowiedzi między systemami (liczności w każdej repetycji warm oraz " +
                        "ścisłe porównanie semantyczne ostatniej odpowiedzi): " +
                        "${model.parityOkPairs} par (dataset, zapytanie) zgodnych (OK), " +
                        "${model.parityMismatchPairs} par unieważnionych (MISMATCH).",
                )
            } else {
                appendLine(
                    "Parytet odpowiedzi według starszego protokołu (liczności tylko ostatniej " +
                        "odpowiedzi warm; bez ścisłego porównania pełnej semantyki): " +
                        "${model.parityOkPairs} par bez wykrytego mismatchu, " +
                        "${model.parityMismatchPairs} par unieważnionych. Wyniku nie należy " +
                        "opisywać jako pełnej zgodności semantycznej.",
                )
            }
            appendLine()
            appendSourceOrderFinding(model)
            appendLine("## Zastrzeżenia")
            appendLine()
            if (model.caveatTable.rows.isEmpty()) {
                appendLine("Brak automatycznie wykrytych anomalii w tym przebiegu.")
                appendLine()
            } else {
                appendMarkdownTable(model.caveatTable)
            }
            appendLine("## Załącznik: pomiar dysku z protokołu benchmarku")
            appendLine()
            appendMarkdownTable(model.storageProtocolTable)
        }

    /** Metric-specific validity, stated before any number is shown. */
    private fun StringBuilder.appendValidityBanner(model: ThesisReportModel) {
        val report = model.replicateReport ?: return
        if (report.runIsValid) {
            appendLine(
                "> **Ważność Q2: OK.** Maksymalny rozrzut zapytań na zbiorach replikacyjnych wynosi " +
                    "×${format2(report.worstQuerySpread)} przy progu " +
                    "×${format2(ReplicateControl.VALIDITY_GATE_SPREAD)}. Q1 ma osobną bramkę: " +
                    "×${format2(report.worstImportSpread)} — " +
                    (if (report.importIsStable) "stabilne." else "niestabilne; Q1 pozostaje nierozstrzygnięte."),
            )
        } else {
            appendLine(
                "> **UWAGA — Q2 nie spełnia warunku ważności.** Zbiory o identycznych parametrach " +
                    "dają rozrzut zapytań do ×${format2(report.worstQuerySpread)} przy progu " +
                    "×${format2(ReplicateControl.VALIDITY_GATE_SPREAD)}. Zmienność na identycznych " +
                    "danych jest zbyt duża, by przypisywać obserwowane efekty systemom; należy " +
                    "sprawdzić rozgrzewkę i warunki hosta, a następnie powtórzyć przebieg " +
                    "(rundy globalnej rozgrzewki: ${model.globalWarmupRounds}; po idle: " +
                    "${model.postIdleWarmupRounds}). Wszystkie werdykty " +
                    "niżej stosują ten zmierzony rozrzut jako próg istotności praktycznej, więc pozostają " +
                    "ostrożne, ale przebiegu nie należy cytować jako dowodu przewagi żadnego z systemów.",
            )
            appendLine(
                "> Q1 ma osobną bramkę: ×${format2(report.worstImportSpread)} — " +
                    (if (report.importIsStable) "stabilne." else "niestabilne; Q1 pozostaje nierozstrzygnięte."),
            )
        }
        appendLine()
    }

    private fun StringBuilder.appendConclusions(model: ThesisReportModel) {
        appendLine("## Podsumowanie — odpowiedzi na pytania badawcze")
        appendLine()
        appendLine(
            "Sekcja generowana automatycznie z danych tego przebiegu. Wniosek „nierozstrzygnięte” " +
                "jest wynikiem tak samo jak każdy inny i **nie** jest brakiem danych.",
        )
        appendLine()
        val significant = model.verdicts.filter { it.verdict == SignificanceVerdict.SIGNIFICANT }
        val belowError = model.verdicts.count { it.verdict == SignificanceVerdict.BELOW_MEASUREMENT_ERROR }
        val notSignificant = model.verdicts.count { it.verdict == SignificanceVerdict.NOT_SIGNIFICANT }

        val runIsValid = model.replicateReport?.runIsValid == true
        val importIsStable = model.replicateReport?.importIsStable == true
        appendLine(
            "- **Q1 (import, pojedynczy blok): nierozstrzygnięte.** Każda komórka ma jedną próbkę; " +
                "wniosek końcowy powstaje dopiero z pełnych bloków. Kontrola identycznych datasetów Q1: " +
                (
                    model.replicateReport?.let { "rozrzut ×${format2(it.worstImportSpread)} — " } ?: "brak — "
                ) +
                (if (importIsStable) "poniżej progu jakości." else "powyżej progu jakości; czasy są diagnostyczne."),
        )
        appendLine()
        if (!runIsValid) {
            appendLine(
                "- **Q2 (zapytania): nierozstrzygnięte.** Przebieg nie przeszedł bramki replikatów; " +
                    "czasy pozostają diagnostyczne, ale generator celowo nie publikuje z nich " +
                    "rankingu ani wniosku o przewadze systemu.",
            )
        } else {
            appendLine(
                "- **Q2 (zapytania, pojedynczy blok).** Porównano ${model.verdicts.size} par. " +
                    "Wewnątrz bloku rozróżnialnych statystycznie i praktycznie: ${significant.size}; " +
                    "poniżej błędu pomiaru: $belowError; nierozróżnialnych statystycznie: $notSignificant. " +
                    "Wniosek końcowy wymaga zgodnego efektu w serii kontrbalansowanych przebiegów.",
            )
        }
        if (runIsValid && significant.isEmpty()) {
            appendLine("  Żadna różnica nie przekroczyła jednocześnie progu statystycznego i progu błędu pomiaru.")
        }
        model.floorMedianMs.forEach { (system, floor) ->
            appendLine("  Najmniejsze okno ($system): mediana ${format2(floor)} ms; opisowy punkt odniesienia, nie koszt stały.")
        }
        appendLine()
        appendLine(
            "- **Q3 (dysk).** Wynik daje osobna sonda izolowanych importów. " +
                "Bez dołączonego `storage-scaling.csv` pytanie pozostaje nierozstrzygnięte.",
        )
        appendLine(
            "- **Q3 (pamięć).** " +
                (
                    model.memoryComparison?.let {
                        "LOCAL ${format1(it.localMiB)} MiB (suma: ${it.localComponents.joinToString(" + ")}) " +
                            "wobec REFERENCE ${format1(it.referenceMiB)} MiB, różnica " +
                            "${format1(it.differenceMiB)} MiB (×${format2(it.ratio)}). " +
                            "Rozstrzygnięcie wymaga zestawienia z rozrzutem między przebiegami — " +
                            "zob. sekcję powtarzalności."
                    } ?: "Brak kompletu składników — nierozstrzygnięte."
                    ),
        )
        val parityClaim = if (model.protocolVersion >= 2) {
            "${model.parityOkPairs} par przeszło kontrolę semantyczną protokołu"
        } else {
            "${model.parityOkPairs} par bez mismatchu w ograniczonej kontroli starszego protokołu; " +
                "pełna zgodność semantyczna nierozstrzygnięta"
        }
        appendLine(
            "- **Q4 (poprawność).** $parityClaim, ${model.parityMismatchPairs} unieważnionych. " +
                "${model.sourceOrderCandidatePairs.size} mismatchów ma sygnaturę znanego odstępstwa " +
                "kolejności REFERENCE; raport nie przypisuje im przyczyny bez osobnej weryfikacji.",
        )
        appendLine()
    }

    private fun StringBuilder.appendReplicateSection(model: ThesisReportModel) {
        val table = model.replicateTable ?: return
        appendLine("## Kontrola replikacji — błąd pomiaru tego przebiegu")
        appendLine()
        appendLine(
            "Zestaw zbiorów zawiera **ten sam eksperyment pod kilkoma nazwami**: zbiory wymienione " +
                "niżej mają identyczne parametry i są punktem przecięcia serii skalowania. Zmierzenie " +
                "tej samej rzeczy kilka razy w jednym przebiegu ogranicza błąd całego eksperymentu od " +
                "dołu — i robi to bez założenia, którego nie usuwa porównanie międzyprzebiegowe: " +
                "zmienia pozycję zbioru w sekwencji, czyli dokładnie tam, gdzie mieszka bias rozgrzewki.",
        )
        appendLine()
        appendMarkdownTable(table)
        appendLine(
            "Zmierzony rozrzut jest używany jako **próg istotności praktycznej** w tabelach Q2: " +
                "różnica median mniejsza niż rozrzut na identycznych danych nie jest dowodem " +
                "o systemach, tylko o pomiarze.",
        )
        appendLine()
    }

    private fun StringBuilder.appendMemoryVerdict(model: ThesisReportModel) {
        appendLine(
            "LOCAL składa się z dwóch kontenerów — interpretera (`processm-interpreter`) i bazy " +
                "(`processm-neo4j`) — podczas gdy REFERENCE to jeden kontener `processm-server` " +
                "obejmujący aplikację i PostgreSQL. Porównując systemy, należy zsumować składniki " +
                "LOCAL w obrębie tej samej fazy. Wszystkie składniki mierzone są tą samą sondą " +
                "(`docker stats`), a sumy `local-total` i `reference-total` są tworzone dla każdego " +
                "wspólnego znacznika czasu przed obliczeniem mediany. Składnik `local-jvm` oznacza " +
                "przebieg deweloperski z niesymetryczną sondą i dyskwalifikuje go jako dowód Q3.",
        )
        appendLine()
        val comparison = model.memoryComparison
        if (comparison == null) {
            appendLine("*(brak kompletu składników — porównanie Q3-pamięć nierozstrzygnięte)*")
            appendLine()
            return
        }
        appendLine(
            "**Zestawienie.** LOCAL ${format1(comparison.localMiB)} MiB wobec REFERENCE " +
                "${format1(comparison.referenceMiB)} MiB w fazie zapytań — różnica " +
                "${format1(comparison.differenceMiB)} MiB (×${format2(comparison.ratio)}).",
        )
        appendLine()
        appendLine(
            "**Warunek rozstrzygnięcia.** Pojedynczy przebieg nie uprawnia do wniosku o przewadze " +
                "w Q3-pamięć. `scripts/benchmarks/compare-runs.py` porównuje sumy systemowe w co " +
                "najmniej trzech pełnych blokach; kierunek jest raportowany dopiero wtedy, gdy znak " +
                "różnicy LOCAL−REFERENCE jest taki sam w każdym przebiegu.",
        )
        appendLine()
        appendLine(
            "**Asymetria budżetu.** Konfiguracja pamięci warstwy bazodanowej obu systemów nie jest " +
                "symetryczna (Neo4j ma jawnie ustawiony heap i page cache, PostgreSQL działa na " +
                "wartościach domyślnych) — dokładne wartości zapisuje `environment.json` tego " +
                "przebiegu. Kierunek tego obciążenia należy podać w zagrożeniach trafności.",
        )
        appendLine()
    }

    /**
     * Marks hoisted trace-variant mismatches as candidates for a previously verified
     * source-order deviation. The generator deliberately does not diagnose a fresh
     * mismatch from query shape alone.
     */
    private fun StringBuilder.appendSourceOrderFinding(model: ThesisReportModel) {
        val pairs = model.sourceOrderCandidatePairs
        if (pairs.isEmpty()) return

        appendLine("## $SOURCE_ORDER_FINDING_HEADING")
        appendLine()
        appendLine(
            "**Wcześniej potwierdzone ustalenie.** Dla zapytań `group by ^e:name` na wskazanych " +
                "logach niezależna analiza pliku XES i powtarzanych odpowiedzi wykazała, że LOCAL " +
                "zachowuje kolejność źródłową, a REFERENCE porządkuje po `time:timestamp`. Pary z " +
                "bieżącego przebiegu są niżej oznaczone wyłącznie jako **kandydaci o tej samej " +
                "sygnaturze**; generator nie diagnozuje przyczyny tylko na podstawie tekstu PQL.",
        )
        appendLine()
        appendLine("**Czego dotyczy.** Zapytania grupujące ślady po *hoistowanym* atrybucie zdarzenia")
        appendLine("(`group by ^e:name`) dzielą ślady na warianty procesu według **sekwencji** wartości")
        appendLine("tego atrybutu. Wynik zależy więc wprost od kolejności zdarzeń wewnątrz śladu.")
        appendLine()
        appendLine("**Co mówi specyfikacja.** Specyfikacja PQL ustala domyślną kolejność komponentów:")
        appendLine()
        appendLine("> $PQL_SPEC_QUOTE")
        appendLine()
        appendLine(
            "(*ProcessM PQL specification*, `docs/pql.md`; dostępna pod adresem $PQL_SPEC_URL). " +
                "Domyślną kolejnością jest zatem **kolejność ze źródła danych** — czyli kolejność " +
                "zapisu zdarzeń w pliku XES — a nie kolejność chronologiczna według `time:timestamp`.",
        )
        appendLine()
        appendLine(
            "**Zachowanie obu systemów.** Niniejsza implementacja (LOCAL) zachowuje kolejność " +
                "źródłową: porządek zdarzeń odpowiada kolejności ich wystąpienia w pliku XES " +
                "(pole `importOrder` nadawane przy imporcie), co jest zgodne z przytoczoną regułą. " +
                "System REFERENCE porządkuje zdarzenia według znacznika czasu, a przy **równych " +
                "znacznikach** — w kolejności wynikającej z planu zapytania relacyjnej bazy danych. " +
                "Realne logi zawierają zdarzenia o identycznych znacznikach czasu w obrębie jednego " +
                "śladu, więc obie strony budują wówczas różne sekwencje wariantów, co zmienia podział " +
                "śladów na grupy i łączną liczbę zwracanych zdarzeń.",
        )
        appendLine()
        appendLine(
            "**Dlaczego to nie jest niedeterminizm.** Oba systemy są w tej klasie zapytań " +
                "powtarzalne — wielokrotne wykonanie tego samego zapytania daje po każdej stronie " +
                "identyczne liczności. Różnica jest więc systematyczna i wynika z odmiennej " +
                "interpretacji domyślnego porządku, a nie z losowości wykonania.",
        )
        appendLine()
        appendLine(
            "**Zakres ustalenia historycznego.** Zjawisko dotyczyło logów rzeczywistych, zawierających zdarzenia " +
                "o równych znacznikach czasu; na zbiorach syntetycznych (o ściśle rosnących " +
                "znacznikach) obie implementacje zwracają identyczne wyniki. Poprawność samego " +
                "przechowywania danych potwierdza niezależnie test roundtrip XES (sekcja " +
                "„Poprawność (Q4)”). " +
                if (model.roundtripAllMatch) {
                    "W tym przebiegu wszystkie wykonane testy roundtrip raportują `MATCH` bez różnic."
                } else {
                    "W tym przebiegu nie wszystkie testy roundtrip mają status `MATCH`, więc nie stanowią pełnego potwierdzenia."
                },
        )
        appendLine()
        appendLine("Pary-kandydaci unieważnione w niniejszym przebiegu:")
        appendLine()
        pairs.forEach { appendLine("- ${it.datasetName} / ${it.queryLabel}: ${it.reason}") }
        appendLine()
        appendLine(
            "Pary te wykluczono z tabel czasów (sekcja „Zapytania (Q2)”), aby nie porównywać " +
                "czasów wykonania dla różniących się semantycznie odpowiedzi.",
        )
        appendLine()
    }

    private fun StringBuilder.appendMarkdownTable(table: ThesisTable) {
        appendLine("| " + table.headers.joinToString(" | ") { escapeMarkdownCell(it) } + " |")
        appendLine("| " + table.rightAligned.joinToString(" | ") { if (it) "---:" else ":---" } + " |")
        table.rows.forEach { row ->
            appendLine("| " + row.joinToString(" | ") { escapeMarkdownCell(it) } + " |")
        }
        if (table.rows.isEmpty()) {
            appendLine()
            appendLine("*(brak danych w tym przebiegu)*")
        }
        appendLine()
        if (table.note.isNotBlank()) {
            appendLine(table.note)
            appendLine()
        }
    }

    private fun escapeMarkdownCell(cell: String): String = cell.replace("|", "\\|")

    private fun renderLatex(model: ThesisReportModel): String =
        buildString {
            appendLine("% generated by ThesisReportWriter ${model.runId}")
            appendLine("% profil: ${model.profileName}, data: ${model.generatedOn}")
            appendLine("% wymaga pakietu booktabs; tabele odpowiadaja 1:1 tabelom z thesis-report.md")
            model.allTables().forEach { table ->
                appendLine()
                appendLatexTable(table)
            }
        }

    private fun StringBuilder.appendLatexTable(table: ThesisTable) {
        val columnSpec = table.rightAligned.joinToString("") { if (it) "r" else "l" }
        appendLine("\\begin{table}[htbp]")
        appendLine("\\centering")
        appendLine("\\caption{${escapeLatex(table.caption)}}")
        appendLine("\\label{tab:bench-${table.slug}}")
        appendLine("\\begin{tabular}{$columnSpec}")
        appendLine("\\toprule")
        appendLine(table.headers.joinToString(" & ") { escapeLatex(it) } + " \\\\")
        appendLine("\\midrule")
        table.rows.forEach { row ->
            appendLine(row.joinToString(" & ") { escapeLatex(it) } + " \\\\")
        }
        appendLine("\\bottomrule")
        appendLine("\\end{tabular}")
        if (table.note.isNotBlank()) {
            appendLine("\\begin{minipage}{\\linewidth}\\footnotesize ${escapeLatex(stripMarkdown(table.note))}\\end{minipage}")
        }
        appendLine("\\end{table}")
    }

    private fun stripMarkdown(text: String): String =
        text.replace("**", "").replace("`", "")

    private fun escapeLatex(text: String): String =
        buildString {
            text.forEach { c ->
                when (c) {
                    '\\' -> append("\\textbackslash{}")
                    '&', '%', '$', '#', '_', '{', '}' -> {
                        append('\\')
                        append(c)
                    }
                    '~' -> append("\\textasciitilde{}")
                    '^' -> append("\\textasciicircum{}")
                    else -> append(c)
                }
            }
        }

    private fun format1(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private fun format2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
}
