package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

class ThesisReportWriterTest {
    @Test
    fun `quantile uses linear interpolation on a known 10-sample vector`() {
        // Hyndman & Fan type 7 on x = 1..10 (deliberately unsorted input):
        // h = 9p -> Q(0.25) = 3.25, Q(0.5) = 5.5, Q(0.75) = 7.75, Q(0.95) = 9.55.
        val values = listOf(10.0, 1.0, 9.0, 2.0, 8.0, 3.0, 7.0, 4.0, 6.0, 5.0)
        assertEquals(3.25, ThesisStatistics.quantile(values, 0.25), 1e-9)
        assertEquals(5.5, ThesisStatistics.quantile(values, 0.50), 1e-9)
        assertEquals(7.75, ThesisStatistics.quantile(values, 0.75), 1e-9)
        assertEquals(9.55, ThesisStatistics.quantile(values, 0.95), 1e-9)

        val stats = ThesisStatistics.stats(values)
        assertEquals(10, stats.samples)
        assertEquals(3.25, stats.q1, 1e-9)
        assertEquals(7.75, stats.q3, 1e-9)
        assertEquals(9.55, stats.p95, 1e-9)
    }

    /**
     * The hoisted trace-variant difference is a REFERENCE deviation from the PQL
     * spec's source-order rule, not a defect here — the report must say so, with the
     * citation, or the invalidated rows read as our incompatibility. Guarded by a test
     * so the argument cannot be dropped silently.
     */
    @Test
    fun `documents the source-order finding for hoisted trace-variant invalidations`(
        @TempDir tempDir: Path,
    ) {
        writeSyntheticRun(tempDir, sourceOrderCandidate = true)
        val md = tempDir.resolve("thesis-report.md").readText()

        assertTrue(
            md.contains("## Kolejność zdarzeń w wariantach śladu — zgodność ze specyfikacją PQL"),
            "report must carry the dedicated spec-compliance section",
        )
        assertTrue(
            md.contains("generator nie diagnozuje przyczyny"),
            "the section must distinguish a candidate signature from an automatic diagnosis",
        )
        assertTrue(
            md.contains("By omitting the `order by` clause, the components are returned in the same order"),
            "the section must quote the PQL specification verbatim",
        )
        assertTrue(
            md.contains("https://github.com/ProcessMPUT/processm/blob/master/docs/pql.md"),
            "the quote must be attributed to a citable source",
        )
        // The hoisted pair is flagged in the invalidated list; the plain MISMATCH is not.
        assertTrue(
            md.contains("- ds-beta / hoistedGroup: MISMATCH") &&
                md.substringAfter("- ds-beta / hoistedGroup:").substringBefore('\n').contains("kandydat"),
            "the real hoisted pair must be marked only as a source-order candidate",
        )
        assertFalse(
            md.substringAfter("- ds_alpha / custom_attr:").substringBefore('\n').contains("kandydat"),
            "a plain count MISMATCH must NOT be attributed to the source-order deviation",
        )
    }

    @Test
    fun `legacy protocol report does not claim full semantic parity`(
        @TempDir tempDir: Path,
    ) {
        writeSyntheticRun(tempDir, protocolVersion = 1)
        val md = tempDir.resolve("thesis-report.md").readText()

        assertTrue(md.contains("według starszego protokołu"))
        assertTrue(md.contains("pełna zgodność semantyczna nierozstrzygnięta"))
        assertFalse(md.contains("3 par (dataset, zapytanie) zgodnych (OK)"))
    }

    @Test
    fun `writes thesis report and latex tables from synthetic records`(
        @TempDir tempDir: Path,
    ) {
        writeSyntheticRun(tempDir)
        val md = tempDir.resolve("thesis-report.md").readText()
        val tex = tempDir.resolve("thesis-tables.tex").readText()

        // MISMATCH pair (ds_alpha x custom_attr) is excluded from tables and listed
        // under the invalidated-measurements section with its reason.
        assertTrue(md.contains("### Pomiary unieważnione"), "md must contain the invalidated-pair section")
        assertTrue(md.contains("- ds_alpha / custom_attr: MISMATCH"), "invalidated pair must be listed with reason")
        assertTrue(md.contains("traces 5 vs 7"), "MISMATCH details must be propagated to the reason")
        assertTrue(
            md.contains("3 par (dataset, zapytanie) zgodnych (OK), 2 par unieważnionych"),
            "Q4 parity summary must count 3 OK pairs and 2 MISMATCH pairs",
        )

        // Every research question gets an answer, including "unresolved" ones.
        assertTrue(md.contains("## Podsumowanie — odpowiedzi na pytania badawcze"), "report must open with per-question verdicts")
        assertTrue(
            md.contains("**Q1 (import, pojedynczy blok): nierozstrzygnięte.**") &&
                md.contains("**Q2 (zapytania): nierozstrzygnięte.**") &&
                md.contains("**Q4 (poprawność).**"),
            "Q1, Q2 and Q4 must be answered",
        )
        assertTrue(
            md.contains("Q1 ma osobną bramkę") && md.contains("Q1 pozostaje nierozstrzygnięte"),
            "unstable one-shot imports must not be conflated with the Q2 validity gate",
        )

        // ds_alpha and ds-beta share their parameters, so they are replicates: the same
        // experiment measured twice. Their disagreement is the run's measurement error
        // and becomes the practical-significance floor.
        assertTrue(md.contains("## Kontrola replikacji"), "replicate control section must be present")
        assertTrue(md.contains("ds-beta = ds_alpha"), "the replicate group must be named")

        // Hand-computed: local 18/20/22 ms (x4) -> median 20.0, quartiles [18.0; 22.0];
        // reference 48/50/52 ms (x4) -> median 50.0, [48.0; 52.0]. Ratio 20/50 = 0.40,
        // i.e. a x2.50 advantage for LOCAL. Replicate spread for hierarchyWindow is
        // x2.00 (local 20 ms vs 40 ms), which this effect clears.
        assertTrue(
            md.contains("| hierarchyWindow | okno | 20.0 | [18.0; 22.0] | 50.0 | [48.0; 52.0] | ×2.50 (LOCAL) |"),
            "warm-stats row must match the hand-computed median/quartiles/advantage",
        )
        assertTrue(md.contains("×2.50 (LOCAL)"), "the within-block effect size must remain visible")
        assertTrue(
            md.contains("przebieg nieważny — diagnostyka") && !md.contains("| istotna |"),
            "an invalid replicate gate must suppress inferential per-pair verdicts",
        )

        // ds-beta: 40 ms vs 50 ms is x1.25 — real and statistically resolvable, but
        // smaller than the x2.00 the replicates prove the measurement itself varies by.
        assertTrue(
            md.contains("×1.25 (LOCAL)"),
            "the smaller effect must be reported as a directed factor",
        )
        assertTrue(
            md.contains("poniżej błędu pomiaru"),
            "an effect below the measured replicate spread must not be called significant",
        )
        // The disjoint-IQR rule is gone; nothing may still claim significance by it.
        assertFalse(md.contains("IQR rozłączne"), "the disjoint-IQR rule must no longer appear")
        assertFalse(md.contains("porównywalne (IQR nachodzą)"), "the disjoint-IQR rule must no longer appear")

        // p95 is withheld below the sample size at which it describes a tail at all.
        assertFalse(md.contains("p95 [ms]"), "p95 must not be tabulated at n far below the threshold")
        assertTrue(md.contains("kwantyl 0,95 jest funkcją dwóch obserwacji"), "the report must say why p95 is absent")

        // A single run carries one import sample, so it must not manufacture an
        // advantage verdict; cross-run Q1 aggregation is done by compare-runs.py.
        assertTrue(
            md.contains("| ds_alpha | 1.00 | 100 | 1000 | 2.00 | 4.00 |"),
            "import row must preserve the two raw block measurements",
        )

        // Global warm-up removes the special first-touch class; all single cold
        // samples remain diagnostics and appear in one table.
        assertFalse(md.contains("### Pierwsze dotknięcie"), "global warm-up makes a separate first-touch class obsolete")
        assertTrue(
            md.contains("| ds_alpha | hierarchyWindow | 100.0 | 200.0 |"),
            "the first dataset belongs in the common diagnostic cold table",
        )
        assertTrue(
            md.contains("| ds-beta | hierarchyWindow | 70.0 | 80.0 |"),
            "cold row for a non-first dataset must carry the advantage",
        )

        // Disk: a positive LOCAL delta stays behind the marker (METODOLOGIA §Q3), and a
        // negative delta is named as a contaminated measurement rather than folded into
        // "below granularity" — the two have different causes and different consequences.
        assertTrue(md.contains("nie raportowane (§Q3)"), "LOCAL storage cells must carry the marker")
        assertTrue(md.contains("pomiar skażony (delta ujemna)"), "a negative delta must be named as contaminated")
        assertFalse(
            Regex("""\| ds_alpha \| 10\.00 \| 10\.00 \|""").containsMatchIn(md),
            "a positive LOCAL delta must not be rendered as a number",
        )
        assertTrue(md.contains("## Załącznik: pomiar dysku z protokołu benchmarku"), "protocol disk data belongs in an appendix")

        // Memory: the sum is stated, the verdict is explicitly deferred to the
        // cross-run spread rather than claimed from a single run.
        assertTrue(md.contains("**Zestawienie.** LOCAL 1536.0 MiB"), "memory must be summed per system")
        assertTrue(md.contains("**Warunek rozstrzygnięcia.**"), "memory verdict must state its precondition")

        // LaTeX: booktabs tables with one \label per table environment.
        val tableCount = Regex("""\\begin\{table}""").findAll(tex).count()
        val labelCount = Regex("""\\label\{tab:bench-""").findAll(tex).count()
        assertEquals(tableCount, labelCount, "every LaTeX table must carry exactly one tab:bench-* label")
        assertEquals(
            10,
            tableCount,
            "environment, replicate, import, 2 query tables, cold, storage appendix, memory, roundtrip, caveats",
        )
        assertTrue(tex.contains("% generated by ThesisReportWriter run-test"), "tex must start with the generator comment")
        assertTrue(tex.contains("\\toprule") && tex.contains("\\midrule") && tex.contains("\\bottomrule"), "booktabs rules")

        // Underscores in labels must be escaped for LaTeX.
        assertTrue(tex.contains("ds\\_alpha"), "dataset underscore must be escaped")
        assertTrue(tex.contains("custom\\_attr"), "query-label underscore must be escaped")
        for (index in tex.indices) {
            if (tex[index] == '_') {
                assertTrue(index > 0 && tex[index - 1] == '\\', "unescaped underscore at index $index")
            }
        }
        assertFalse(md.contains("NaN"), "no NaN may leak into the report")
    }

    private fun writeSyntheticRun(
        tempDir: Path,
        sourceOrderCandidate: Boolean = false,
        protocolVersion: Int = CURRENT_BENCHMARK_PROTOCOL_VERSION,
    ) {
        val settings = BenchmarkSettings(
            profile = BenchmarkProfile.SMOKE,
            localApi = "http://localhost:8080/api",
            referenceApi = "http://localhost:80/api",
            processMLogin = "admin@example.com",
            processMPassword = "secret",
            outputRoot = tempDir,
            datasetFilter = emptySet(),
            systemFilter = emptySet(),
            keepBenchmarkDataStores = false,
            localAppContainer = "processm-interpreter",
            protocolVersion = protocolVersion,
        )
        val datasets = listOf(
            dataset("ds_alpha", tempDir),
            dataset("ds-beta", tempDir, if (sourceOrderCandidate) "real-validation" else "synthetic"),
        )
        val imports = listOf(
            importResult("local", "ds_alpha", 2.0),
            importResult("reference", "ds_alpha", 4.0),
            importResult("local", "ds-beta", 1.0),
            importResult("reference", "ds-beta", 1.0),
        )

        // Twelve repetitions per cell: below roughly ten, no arrangement of the data can
        // reach p < 0.05 two-sided, so a three-sample fixture could only ever exercise
        // the "not significant" branch.
        fun spread(
            dataset: String,
            label: String,
            system: String,
            centre: Double,
        ) = listOf(centre - 2, centre, centre + 2).flatMap { value ->
            (1..4).map { warm(system, dataset, label, it, value / 1000.0) }
        }

        val queries = buildList {
            // Pair 1: ds_alpha x hierarchyWindow — 20 ms vs 50 ms, clearly separated.
            addAll(spread("ds_alpha", "hierarchyWindow", "local", 20.0))
            addAll(spread("ds_alpha", "hierarchyWindow", "reference", 50.0))
            add(cold("local", "ds_alpha", "hierarchyWindow", 0.100))
            add(cold("reference", "ds_alpha", "hierarchyWindow", 0.200))
            // Pair 2: ds_alpha x custom_attr — invalidated by response-count MISMATCH.
            add(warm("local", "ds_alpha", "custom_attr", 1, 0.010, status = QUERY_STATUS_MISMATCH, details = "Response count mismatch: traces 5 vs 7"))
            add(warm("reference", "ds_alpha", "custom_attr", 1, 0.011, status = QUERY_STATUS_MISMATCH, details = "Response count mismatch: traces 5 vs 7"))
            add(cold("local", "ds_alpha", "custom_attr", 0.050))
            add(cold("reference", "ds_alpha", "custom_attr", 0.060))
            // Pair 3: ds-beta x hierarchyWindow — 40 ms vs 50 ms. Separated, so the test
            // resolves the direction, but x1.25 is under the x2.00 the replicates show.
            addAll(spread("ds-beta", "hierarchyWindow", "local", 40.0))
            addAll(spread("ds-beta", "hierarchyWindow", "reference", 50.0))
            add(cold("local", "ds-beta", "hierarchyWindow", 0.070))
            add(cold("reference", "ds-beta", "hierarchyWindow", 0.080))
            // Pair 4: ds-beta x custom_attr — small, clean, separated.
            addAll(spread("ds-beta", "custom_attr", "local", 10.0))
            addAll(spread("ds-beta", "custom_attr", "reference", 20.0))
            add(cold("local", "ds-beta", "custom_attr", 0.003))
            add(cold("reference", "ds-beta", "custom_attr", 0.004))
            // Pair 5: ds-beta x hoistedGroup — invalidated on a hoisted trace-variant
            // query, i.e. the REFERENCE source-order deviation class.
            add(warm("local", "ds-beta", "hoistedGroup", 1, 0.010, status = QUERY_STATUS_MISMATCH, details = "Response count mismatch: events 714 vs 723"))
            add(warm("reference", "ds-beta", "hoistedGroup", 1, 0.011, status = QUERY_STATUS_MISMATCH, details = "Response count mismatch: events 714 vs 723"))
            add(cold("local", "ds-beta", "hoistedGroup", 0.050))
            add(cold("reference", "ds-beta", "hoistedGroup", 0.060))
        }
        val storage = listOf(
            storageResult("local", "ds_alpha", 10L * 1024 * 1024, 10.0),
            storageResult("reference", "ds_alpha", 5L * 1024 * 1024, 5.0),
            storageResult("local", "ds-beta", 2L * 1024 * 1024, 2.0),
            // The database shrank across this import: not a small measurement, no
            // measurement. It must reach neither a table cell nor a chart point.
            storageResult("reference", "ds-beta", -4L * 1024 * 1024, -4.0, STORAGE_STATUS_CONTAMINATED),
        )
        val roundtrips = listOf(
            RoundtripBenchmarkResult("ds_alpha", "MATCH", 0, ""),
            RoundtripBenchmarkResult("ds-beta", "MATCH", 0, ""),
        )
        val memorySummaries = listOf(
            MemorySummary("processm-neo4j", MEMORY_PHASE_QUERIES, 1_073_741_824, 2_147_483_648),
            MemorySummary("processm-interpreter", MEMORY_PHASE_QUERIES, 536_870_912, 1_073_741_824),
            MemorySummary("processm-server", MEMORY_PHASE_QUERIES, 1_610_612_736, 3_221_225_472),
            MemorySummary("local-total", MEMORY_PHASE_QUERIES, 1_610_612_736, 3_221_225_472),
            MemorySummary("reference-total", MEMORY_PHASE_QUERIES, 1_610_612_736, 3_221_225_472),
        )

        ThesisReportWriter(tempDir).write(
            runId = "run-test",
            settings = settings,
            datasets = datasets,
            imports = imports,
            queries = queries,
            storage = storage,
            roundtrips = roundtrips,
            memorySummaries = memorySummaries,
            querySpecs = listOf(
                BenchmarkQuerySpec("hierarchyWindow", "limit l:1, t:10, e:20"),
                BenchmarkQuerySpec("custom_attr", "where [e:attr_1] is not null limit l:1, t:10"),
                BenchmarkQuerySpec(
                    "hoistedGroup",
                    "group by ^e:name order by count(t:name) desc",
                    WORKLOAD_DATA_DEPENDENT,
                ),
            ),
        )
    }

    private fun dataset(
        name: String,
        tempDir: Path,
        series: String = "synthetic",
    ) = PreparedDataset(
        name = name,
        series = series,
        file = tempDir.resolve("$name.xes"),
        traces = 100,
        eventsPerTrace = 10,
        totalEvents = 1000,
        attributesPerEvent = 5,
        totalAttributes = 5000,
        xesBytes = 1024L * 1024,
        xesGzBytes = 128L * 1024,
    )

    private fun importResult(
        system: String,
        datasetName: String,
        seconds: Double,
    ) = ImportBenchmarkResult(
        system = system,
        datasetName = datasetName,
        run = 1,
        seconds = seconds,
        status = "OK",
        dataStoreId = "ds-id",
        logCount = 1,
    )

    private fun warm(
        system: String,
        datasetName: String,
        queryLabel: String,
        run: Int,
        seconds: Double,
        status: String = "OK",
        details: String = "",
    ) = QueryBenchmarkResult(
        system = system,
        datasetName = datasetName,
        queryLabel = queryLabel,
        run = run,
        seconds = seconds,
        status = status,
        responseBytes = 100,
        phase = QUERY_PHASE_WARM,
        logCount = 1,
        traceCount = 10,
        eventCount = 100,
        details = details,
    )

    private fun cold(
        system: String,
        datasetName: String,
        queryLabel: String,
        seconds: Double,
    ) = QueryBenchmarkResult(
        system = system,
        datasetName = datasetName,
        queryLabel = queryLabel,
        run = 0,
        seconds = seconds,
        status = "OK",
        responseBytes = 100,
        phase = QUERY_PHASE_COLD,
        logCount = 1,
        traceCount = 10,
        eventCount = 100,
    )

    private fun storageResult(
        system: String,
        datasetName: String,
        deltaBytes: Long,
        xesRatio: Double,
        status: String = STORAGE_STATUS_OK,
    ) = StorageBenchmarkResult(
        system = system,
        datasetName = datasetName,
        beforeBytes = 0,
        afterBytes = deltaBytes,
        deltaBytes = deltaBytes,
        deltaToXesRatio = xesRatio,
        deltaToGzipRatio = xesRatio * 8,
        status = status,
    )
}
