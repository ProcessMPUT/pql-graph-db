package com.processm.processminterpreter.benchmark

import java.nio.file.Path
import java.time.LocalDate
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
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
 * The interquartile range (IQR) reported in the thesis is the interval
 * `[Q(0.25), Q(0.75)]` under this estimator; `p95 = Q(0.95)`; the median is `Q(0.5)`.
 *
 * Reference: Hyndman, R. J. & Fan, Y. (1996). *Sample Quantiles in Statistical
 * Packages.* The American Statistician, 50(4), 361–365.
 */
object ThesisStatistics {
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
        )
}

data class SampleStats(
    val samples: Int,
    val median: Double,
    val q1: Double,
    val q3: Double,
    val p95: Double,
) {
    /**
     * Disjoint-IQR significance rule from METODOLOGIA §5: a median difference is
     * claimed significant only when the IQR intervals of both systems are disjoint.
     */
    fun iqrOverlaps(other: SampleStats): Boolean = q1 <= other.q3 && other.q1 <= q3
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
)

private const val SYSTEM_LOCAL = "local"
private const val SYSTEM_REFERENCE = "reference"
private const val MISSING = "—"
private const val COMPARABLE_MARK = "porównywalne (IQR nachodzą)"
private const val SIGNIFICANT_MARK = "istotna (IQR rozłączne)"

/**
 * Report model computed once from the in-memory benchmark records; both output
 * formats render from this model so their tables can never diverge.
 */
data class ThesisReportModel(
    val runId: String,
    val profileName: String,
    val generatedOn: String,
    val environmentTable: ThesisTable,
    val importTable: ThesisTable,
    val queryTables: List<ThesisTable>,
    val coldTable: ThesisTable,
    val storageTable: ThesisTable,
    val memoryTable: ThesisTable,
    val roundtripTable: ThesisTable,
    val parityOkPairs: Int,
    val parityMismatchPairs: Int,
    val invalidatedPairs: List<InvalidatedPair>,
    val caveats: List<String>,
) {
    fun allTables(): List<ThesisTable> =
        buildList {
            add(environmentTable)
            add(importTable)
            addAll(queryTables)
            add(coldTable)
            add(storageTable)
            add(memoryTable)
            add(roundtripTable)
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
        ): ThesisReportModel {
            val datasetOrder = orderedDatasetNames(datasets, queries)
            val queryLabelOrder = queries.map { it.queryLabel }.distinct()
            val measuredSamples = queries.filter { it.phase == QUERY_PHASE_COLD || it.phase == QUERY_PHASE_WARM }
            val samplesByPair = measuredSamples.groupBy { it.datasetName to it.queryLabel }

            val invalidated = samplesByPair
                .filterValues { samples -> samples.any { it.status == QUERY_STATUS_MISMATCH || it.status == "ERROR" } }
                .map { (pair, samples) -> InvalidatedPair(pair.first, pair.second, invalidationReason(samples)) }
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

            val overlapPairs = warmStats
                .filterValues { bySystem ->
                    val local = bySystem[SYSTEM_LOCAL]
                    val reference = bySystem[SYSTEM_REFERENCE]
                    local != null && reference != null && local.iqrOverlaps(reference)
                }
                .keys
                .sortedWith(compareBy({ datasetOrder.indexOf(it.first) }, { queryLabelOrder.indexOf(it.second) }))

            val caveats = buildList {
                queries
                    .filter { it.status == "ERROR" }
                    .forEach {
                        add(
                            "Błąd wykonania (ERROR): system ${it.system}, dataset ${it.datasetName}, " +
                                "zapytanie ${it.queryLabel}, faza ${it.phase}" +
                                (if (it.details.isNotBlank()) " — ${it.details}" else ""),
                        )
                    }
                overlapPairs.forEach { (dataset, label) ->
                    add(
                        "$dataset / $label: mediany obu systemów różnią się, ale przedziały IQR nachodzą — " +
                            "zgodnie z regułą rozłącznych IQR (METODOLOGIA §5) różnica jest nieistotna, " +
                            "wynik opisany jako $COMPARABLE_MARK.",
                    )
                }
                storage
                    .filter { it.status != "OK" }
                    .forEach { add("Pomiar storage: system ${it.system}, dataset ${it.datasetName} — status ${it.status}.") }
            }

            return ThesisReportModel(
                runId = runId,
                profileName = settings.profile.name.lowercase(),
                generatedOn = LocalDate.now().toString(),
                environmentTable = environmentTable(settings),
                importTable = importTable(datasets, imports),
                queryTables = queryTables(datasetOrder, queryLabelOrder, warmStats, overlapPairs.toSet()),
                coldTable = coldTable(datasetOrder, queryLabelOrder, validPairs),
                storageTable = storageTable(datasetOrder, storage),
                memoryTable = memoryTable(memorySummaries),
                roundtripTable = roundtripTable(roundtrips),
                parityOkPairs = validPairs.count { (_, samples) -> samples.any { it.phase == QUERY_PHASE_WARM } },
                parityMismatchPairs = samplesByPair.count { (_, samples) -> samples.any { it.status == QUERY_STATUS_MISMATCH } },
                invalidatedPairs = invalidated,
                caveats = caveats,
            )
        }

        private fun orderedDatasetNames(
            datasets: List<PreparedDataset>,
            queries: List<QueryBenchmarkResult>,
        ): List<String> = (datasets.map { it.name } + queries.map { it.datasetName }).distinct()

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

        private fun environmentTable(settings: BenchmarkSettings): ThesisTable =
            ThesisTable(
                slug = "srodowisko",
                caption = "Środowisko pomiarowe",
                headers = listOf("Parametr", "Wartość"),
                rightAligned = listOf(false, false),
                rows = listOf(
                    listOf("System operacyjny", "${System.getProperty("os.name")} ${System.getProperty("os.version")}"),
                    listOf("Procesory logiczne (JVM)", Runtime.getRuntime().availableProcessors().toString()),
                    listOf("Pamięć maksymalna JVM [MiB]", fmt0(Runtime.getRuntime().maxMemory() / MIB)),
                    listOf("Java", System.getProperty("java.version")),
                    listOf("Profil benchmarku", settings.profile.name.lowercase()),
                    listOf("Rozgrzewki na zapytanie", settings.profile.warmups.toString()),
                    listOf("Repetycje mierzone na zapytanie", settings.profile.repetitions.toString()),
                    listOf("Okno pomiaru pamięci spoczynkowej [s]", settings.profile.idleBaselineSeconds.toString()),
                    listOf("API LOCAL (Kotlin/Neo4j)", settings.localApi),
                    listOf("API REFERENCE (ProcessM/PostgreSQL)", settings.referenceApi),
                    listOf("Limity kontenerów Docker i konfiguracja pamięci baz", "zob. environment.json tego przebiegu"),
                ),
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
            return ThesisTable(
                slug = "import",
                caption = "Import (Q1): czas importu XES (mediana przy co najmniej 2 powtórzeniach)",
                headers = listOf(
                    "Dataset",
                    "Rozmiar XES [MiB]",
                    "Trace'y",
                    "Zdarzenia",
                    "LOCAL [s]",
                    "REFERENCE [s]",
                    "Stosunek L/R",
                ),
                rightAligned = listOf(false, true, true, true, true, true, true),
                rows = datasets.map { dataset ->
                    val local = seconds(dataset.name, SYSTEM_LOCAL)
                    val reference = seconds(dataset.name, SYSTEM_REFERENCE)
                    listOf(
                        dataset.name,
                        fmt2(dataset.xesBytes.toDouble() / MIB),
                        dataset.traces.toString(),
                        dataset.totalEvents.toString(),
                        local?.let(::fmt2) ?: MISSING,
                        reference?.let(::fmt2) ?: MISSING,
                        ratio(local, reference),
                    )
                },
            )
        }

        private fun queryTables(
            datasetOrder: List<String>,
            queryLabelOrder: List<String>,
            warmStats: Map<Pair<String, String>, Map<String, SampleStats>>,
            overlapPairs: Set<Pair<String, String>>,
        ): List<ThesisTable> =
            datasetOrder.mapNotNull { dataset ->
                val labels = queryLabelOrder.filter { label ->
                    warmStats[dataset to label]?.values?.any { it.samples > 0 } == true
                }
                if (labels.isEmpty()) return@mapNotNull null
                ThesisTable(
                    slug = "zapytania-${slugify(dataset)}",
                    caption = "Zapytania (Q2), dataset $dataset: czasy odpowiedzi (próbki warm) [ms]",
                    headers = listOf(
                        "Zapytanie",
                        "LOCAL mediana [ms]",
                        "LOCAL IQR [ms]",
                        "LOCAL p95 [ms]",
                        "REFERENCE mediana [ms]",
                        "REFERENCE IQR [ms]",
                        "REFERENCE p95 [ms]",
                        "Mediana L/R",
                        "Istotność różnicy",
                    ),
                    rightAligned = listOf(false, true, true, true, true, true, true, true, false),
                    rows = labels.map { label ->
                        val bySystem = warmStats.getValue(dataset to label)
                        val local = bySystem[SYSTEM_LOCAL]
                        val reference = bySystem[SYSTEM_REFERENCE]
                        val significance = when {
                            local == null || reference == null -> MISSING
                            (dataset to label) in overlapPairs -> COMPARABLE_MARK
                            else -> SIGNIFICANT_MARK
                        }
                        listOf(
                            label,
                            local?.let { fmt1(it.median * MS) } ?: MISSING,
                            local?.let { iqr(it) } ?: MISSING,
                            local?.let { fmt1(it.p95 * MS) } ?: MISSING,
                            reference?.let { fmt1(it.median * MS) } ?: MISSING,
                            reference?.let { iqr(it) } ?: MISSING,
                            reference?.let { fmt1(it.p95 * MS) } ?: MISSING,
                            ratio(local?.median, reference?.median),
                            significance,
                        )
                    },
                )
            }

        private fun coldTable(
            datasetOrder: List<String>,
            queryLabelOrder: List<String>,
            validPairs: Map<Pair<String, String>, List<QueryBenchmarkResult>>,
        ): ThesisTable {
            val rows = datasetOrder.flatMap { dataset ->
                queryLabelOrder.mapNotNull { label ->
                    val samples = validPairs[dataset to label] ?: return@mapNotNull null
                    fun cold(system: String): Double? =
                        samples
                            .filter { it.phase == QUERY_PHASE_COLD && it.status == "OK" && it.system == system }
                            .map { it.seconds }
                            .takeIf { it.isNotEmpty() }
                            ?.let { ThesisStatistics.quantile(it, 0.50) }
                    val local = cold(SYSTEM_LOCAL)
                    val reference = cold(SYSTEM_REFERENCE)
                    if (local == null && reference == null) return@mapNotNull null
                    listOf(
                        dataset,
                        label,
                        local?.let { fmt1(it * MS) } ?: MISSING,
                        reference?.let { fmt1(it * MS) } ?: MISSING,
                        ratio(local, reference),
                    )
                }
            }
            return ThesisTable(
                slug = "zapytania-cold",
                caption = "Zapytania (Q2): pierwsze (zimne) wykonania po imporcie [ms]",
                headers = listOf("Dataset", "Zapytanie", "LOCAL cold [ms]", "REFERENCE cold [ms]", "Stosunek L/R"),
                rightAligned = listOf(false, false, true, true, true),
                rows = rows,
            )
        }

        private fun storageTable(
            datasetOrder: List<String>,
            storage: List<StorageBenchmarkResult>,
        ): ThesisTable {
            fun probe(
                dataset: String,
                system: String,
            ): StorageBenchmarkResult? = storage.firstOrNull { it.datasetName == dataset && it.system == system }
            val rows = datasetOrder.mapNotNull { dataset ->
                val local = probe(dataset, SYSTEM_LOCAL)
                val reference = probe(dataset, SYSTEM_REFERENCE)
                if (local == null && reference == null) return@mapNotNull null
                listOf(
                    dataset,
                    local?.deltaBytes?.let { fmt2(it.toDouble() / MIB) } ?: MISSING,
                    local?.deltaToXesRatio?.let(::fmt2) ?: MISSING,
                    reference?.deltaBytes?.let { fmt2(it.toDouble() / MIB) } ?: MISSING,
                    reference?.deltaToXesRatio?.let(::fmt2) ?: MISSING,
                    ratio(local?.deltaBytes?.toDouble(), reference?.deltaBytes?.toDouble()),
                )
            }
            return ThesisTable(
                slug = "storage",
                caption = "Zasobożerność (Q3): przyrost miejsca na dysku po imporcie i współczynnik ekspansji (bajty bazy / bajty XES)",
                headers = listOf(
                    "Dataset",
                    "LOCAL przyrost [MiB]",
                    "LOCAL ekspansja [B/B XES]",
                    "REFERENCE przyrost [MiB]",
                    "REFERENCE ekspansja [B/B XES]",
                    "Stosunek przyrostów L/R",
                ),
                rightAligned = listOf(false, true, true, true, true, true),
                rows = rows,
            )
        }

        private fun memoryTable(memorySummaries: List<MemorySummary>): ThesisTable =
            ThesisTable(
                slug = "pamiec",
                caption = "Zasobożerność (Q3): pamięć operacyjna per składnik i faza (mediana i szczyt próbek co 1 s)",
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

        private fun iqr(stats: SampleStats): String = "[${fmt1(stats.q1 * MS)}; ${fmt1(stats.q3 * MS)}]"

        private fun ratio(
            local: Double?,
            reference: Double?,
        ): String =
            if (local != null && reference != null && reference != 0.0) fmt2(local / reference) else MISSING

        private fun slugify(name: String): String =
            name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")

        private const val MIB = 1024.0 * 1024.0
        private const val MS = 1000.0

        private fun fmt0(value: Double): String = String.format(Locale.ROOT, "%.0f", value)

        private fun fmt1(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

        private fun fmt2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
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
    fun write(
        runId: String,
        settings: BenchmarkSettings,
        datasets: List<PreparedDataset>,
        imports: List<ImportBenchmarkResult>,
        queries: List<QueryBenchmarkResult>,
        storage: List<StorageBenchmarkResult>,
        roundtrips: List<RoundtripBenchmarkResult>,
        memorySummaries: List<MemorySummary>,
    ) {
        val model = ThesisReportModel.build(runId, settings, datasets, imports, queries, storage, roundtrips, memorySummaries)
        outputDirectory.createDirectories()
        outputDirectory.resolve("thesis-report.md").writeText(renderMarkdown(model))
        outputDirectory.resolve("thesis-tables.tex").writeText(renderLatex(model))
    }

    private fun renderMarkdown(model: ThesisReportModel): String =
        buildString {
            appendLine("# Wyniki benchmarku — raport do pracy magisterskiej")
            appendLine()
            appendLine("- Profil: ${model.profileName}")
            appendLine("- Identyfikator przebiegu (runId): ${model.runId}")
            appendLine("- Data wygenerowania: ${model.generatedOn}")
            appendLine()
            appendLine(
                "Niniejszy raport zawiera wyłącznie wyniki jednego przebiegu benchmarku. " +
                    "Pełna metodologia pomiarów (pytania badawcze Q1–Q4, zasady fair play, protokół " +
                    "naprzemienny, reguła istotności oparta na rozłącznych IQR) opisana jest w " +
                    "dokumencie `src/benchmark/METODOLOGIA.md`; wszystkie tabele poniżej stosują " +
                    "tę metodologię. Czasy podano w jednostkach wskazanych w nagłówkach kolumn, " +
                    "z kropką jako separatorem dziesiętnym.",
            )
            appendLine()
            appendLine("## Środowisko pomiarowe")
            appendLine()
            appendMarkdownTable(model.environmentTable)
            appendLine("## Import (Q1)")
            appendLine()
            appendMarkdownTable(model.importTable)
            appendLine(
                "Czas importu to czas ściany żądania HTTP od wysłania pliku XES do odpowiedzi 2xx. " +
                    "Przy co najmniej 2 powtórzeniach importu raportowana jest mediana czasów.",
            )
            appendLine()
            appendLine("## Zapytania (Q2)")
            appendLine()
            appendLine(
                "Tabele obejmują wyłącznie próbki warm (mierzone repetycje po rozgrzewce); " +
                    "pary (dataset, zapytanie) ze statusem MISMATCH lub ERROR są wykluczone z tabel " +
                    "i wymienione w sekcji „Pomiary unieważnione”. Różnica median jest deklarowana " +
                    "jako istotna tylko przy rozłącznych przedziałach IQR (METODOLOGIA §5); " +
                    "w przeciwnym razie wynik oznaczono jako $COMPARABLE_MARK.",
            )
            appendLine()
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
                    appendLine("- ${it.datasetName} / ${it.queryLabel}: ${it.reason}")
                }
            }
            appendLine()
            appendLine("## Zasobożerność (Q3)")
            appendLine()
            appendLine("### Przestrzeń dyskowa")
            appendLine()
            appendMarkdownTable(model.storageTable)
            appendLine("### Pamięć operacyjna")
            appendLine()
            appendMarkdownTable(model.memoryTable)
            appendLine(
                "Ze względu na różną architekturę procesów wartości LOCAL są sumą składników " +
                    "(kontener Neo4j `processm-neo4j` + RSS procesu JVM aplikacji na hoście, " +
                    "składnik `local-jvm`), podczas gdy REFERENCE mierzony jest jako jeden kontener " +
                    "`processm-server` obejmujący aplikację i PostgreSQL; porównując systemy, " +
                    "należy sumować składniki LOCAL w obrębie tej samej fazy.",
            )
            appendLine()
            appendLine("## Poprawność (Q4)")
            appendLine()
            appendMarkdownTable(model.roundtripTable)
            appendLine(
                "Parytet liczności odpowiedzi (logi/trace'y/zdarzenia) między systemami: " +
                    "${model.parityOkPairs} par (dataset, zapytanie) zgodnych (OK), " +
                    "${model.parityMismatchPairs} par unieważnionych rozjazdem liczności (MISMATCH).",
            )
            appendLine()
            appendLine("## Zastrzeżenia")
            appendLine()
            if (model.caveats.isEmpty()) {
                appendLine("Brak automatycznie wykrytych anomalii w tym przebiegu.")
            } else {
                model.caveats.forEach { appendLine("- $it") }
            }
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
        appendLine("\\end{table}")
    }

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
}
